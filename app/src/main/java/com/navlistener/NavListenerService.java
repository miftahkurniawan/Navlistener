package com.navlistener;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NavListenerService
 *
 * Service ini berjalan di background dan "mendengar" setiap notifikasi
 * yang masuk ke perangkat Android. Khusus notifikasi dari Google Maps,
 * teks navigasi di-parse lalu dikirim ke ESP8266 via MQTT.
 *
 * Package Google Maps yang dimonitor:
 *   com.google.android.apps.maps
 */
public class NavListenerService extends NotificationListenerService {

    private static final String TAG        = "NavListener";
    public  static final String MAPS_PKG   = "com.google.android.apps.maps";

    // Broadcast ke MainActivity untuk update UI
    public static final String ACTION_NAV_UPDATE = "com.navlistener.NAV_UPDATE";
    public static final String ACTION_NAV_STOPPED = "com.navlistener.NAV_STOPPED";

    // State navigasi terkini
    public static volatile NavState currentNav = new NavState();
    public static volatile boolean   isListening = false;

    private MqttManager mqttManager;

    /* ─────────────────────────────────────────────────────────── */

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        isListening  = true;
        mqttManager  = MqttManager.getInstance(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isListening = false;
        Log.d(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    /* ─── NOTIFIKASI MASUK ─────────────────────────────────────── */

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();

        // Hanya proses notifikasi dari Google Maps
        if (!MAPS_PKG.equals(pkg)) return;

        Log.d(TAG, "Notif dari Google Maps diterima");

        Notification notif    = sbn.getNotification();
        Bundle       extras   = notif.extras;

        if (extras == null) return;

        // Ekstrak teks dari notifikasi
        String title    = safeString(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text     = safeString(extras.getCharSequence(Notification.EXTRA_TEXT));
        String bigText  = safeString(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String subText  = safeString(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String infoText = safeString(extras.getCharSequence(Notification.EXTRA_INFO_TEXT));

        Log.d(TAG, "Title   : " + title);
        Log.d(TAG, "Text    : " + text);
        Log.d(TAG, "BigText : " + bigText);
        Log.d(TAG, "SubText : " + subText);

        // Parse data navigasi dari teks notifikasi
        NavState nav = parseNavigation(title, text, bigText, subText, infoText);

        if (nav == null) return;

        currentNav = nav;

        // Kirim ke ESP8266 via MQTT
        mqttManager.publish(nav.toJson());

        // Broadcast ke MainActivity untuk update UI
        Intent broadcast = new Intent(ACTION_NAV_UPDATE);
        broadcast.putExtra("json", nav.toJsonString());
        sendBroadcast(broadcast);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Notifikasi Google Maps dihapus = navigasi selesai/dibatalkan
        if (!MAPS_PKG.equals(sbn.getPackageName())) return;

        Log.d(TAG, "Notif Maps dihapus — navigasi mungkin selesai");

        currentNav = new NavState(); // reset

        mqttManager.publish(stopJson());

        Intent broadcast = new Intent(ACTION_NAV_STOPPED);
        sendBroadcast(broadcast);
    }

    /* ─── PARSER UTAMA ─────────────────────────────────────────── */

    /**
     * Google Maps mengirim notifikasi navigasi dalam beberapa format:
     *
     * Format A (paling umum):
     *   Title: "Turn right in 200 m"  atau "Belok kanan dalam 200 m"
     *   Text : "Jl. Sudirman"
     *
     * Format B (dengan ETA):
     *   Title: "Jl. Sudirman"
     *   Text : "Turn right in 200 m"
     *   SubText: "Tiba pk 14:30 · 2,3 km tersisa"
     *
     * Format C (arriving soon):
     *   Title: "Tiba dalam 2 menit"
     *   Text : "Tujuan di kanan"
     *
     * Format D (BigText expanded):
     *   BigText: berisi semua info navigasi
     */
    private NavState parseNavigation(String title, String text,
                                      String bigText, String subText,
                                      String infoText) {

        // Gabung semua teks untuk analisis
        String allText = (title + " | " + text + " | " + bigText
                          + " | " + subText + " | " + infoText).toLowerCase();

        // Jika tidak ada kata kunci navigasi, abaikan
        if (!isNavNotification(allText)) {
            Log.d(TAG, "Bukan notifikasi navigasi, diabaikan");
            return null;
        }

        NavState nav = new NavState();
        nav.active = true;

        // ── 1. Deteksi MANEUVER ────────────────────────────────────
        nav.maneuver = detectManeuver(title + " " + text + " " + bigText);

        // ── 2. Ekstrak JARAK ───────────────────────────────────────
        DistResult dist = extractDistance(title + " " + text + " " + bigText);
        nav.distance = dist.value;
        nav.distUnit = dist.unit;

        // ── 3. Ekstrak NAMA JALAN ──────────────────────────────────
        nav.street = extractStreetName(title, text, bigText, nav.maneuver);

        // ── 4. Ekstrak ETA & Jarak Sisa ───────────────────────────
        EtaResult eta = extractEta(subText + " " + infoText + " " + bigText);
        nav.eta        = eta.time;
        nav.remainDist = eta.remainDist;
        nav.remainUnit = eta.remainUnit;

        Log.d(TAG, "Parsed: maneuver=" + nav.maneuver
                 + " dist=" + nav.distance + nav.distUnit
                 + " street=" + nav.street
                 + " eta=" + nav.eta);

        return nav;
    }

    /* ─── DETEKSI NAVIGASI ─────────────────────────────────────── */

    private boolean isNavNotification(String text) {
        // Kata kunci yang menandakan notifikasi navigasi aktif
        String[] navKeywords = {
            // Bahasa Indonesia
            "belok", "lurus", "bundaran", "tiba", "keluar", "ikuti",
            "tersisa", "menit", "km tersisa", "m tersisa",
            // English
            "turn", "straight", "arrive", "destination", "roundabout",
            "exit", "merge", "keep", "head", "continue",
            // Jarak + arah (universal)
            " m ", " km ", "in 1", "in 2", "dalam 1", "dalam 2",
            "200", "300", "500",
        };
        for (String kw : navKeywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /* ─── MANEUVER DETECTOR ────────────────────────────────────── */

    private String detectManeuver(String text) {
        String t = text.toLowerCase();

        // Destination / arriving
        if (t.contains("tiba") || t.contains("arrive") || t.contains("destination")
            || t.contains("sampai") || t.contains("tujuan")) {
            return "destination";
        }

        // U-Turn
        if (t.contains("balik") || t.contains("u-turn") || t.contains("uturn")
            || t.contains("putar balik")) {
            return "uturn-left";
        }

        // Roundabout
        if (t.contains("bundaran") || t.contains("roundabout") || t.contains("bulatan")) {
            return t.contains("kanan") || t.contains("right") ? "roundabout-right" : "roundabout-left";
        }

        // Slight turns
        if ((t.contains("sedikit") || t.contains("slight") || t.contains("agak"))
            && (t.contains("kanan") || t.contains("right"))) return "turn-slight-right";
        if ((t.contains("sedikit") || t.contains("slight") || t.contains("agak"))
            && (t.contains("kiri") || t.contains("left"))) return "turn-slight-left";

        // Sharp turns
        if ((t.contains("tajam") || t.contains("sharp"))
            && (t.contains("kanan") || t.contains("right"))) return "turn-sharp-right";
        if ((t.contains("tajam") || t.contains("sharp"))
            && (t.contains("kiri") || t.contains("left"))) return "turn-sharp-left";

        // Normal turns (check right before left, order matters)
        if (t.contains("kanan") || t.contains("right")) return "turn-right";
        if (t.contains("kiri")  || t.contains("left"))  return "turn-left";

        // Merge / ramp
        if (t.contains("gabung") || t.contains("merge")) return "merge";
        if (t.contains("tanjakan") || t.contains("ramp") || t.contains("keluar jalan"))
            return "ramp-right";

        // Fork
        if (t.contains("percabangan") || t.contains("fork") || t.contains("simpang")) {
            return t.contains("kanan") ? "fork-right" : "fork-left";
        }

        // Keep / stay
        if (t.contains("tetap") || t.contains("keep") || t.contains("ikuti") || t.contains("continue")) {
            return t.contains("kanan") ? "turn-slight-right" :
                   t.contains("kiri")  ? "turn-slight-left"  : "straight";
        }

        // Default straight
        return "straight";
    }

    /* ─── JARAK EXTRACTOR ─────────────────────────────────────── */

    static class DistResult {
        float value = 0; String unit = "m";
    }

    private DistResult extractDistance(String text) {
        DistResult r = new DistResult();

        // Pola: "dalam 200 m", "in 200 m", "in 1.2 km", "200 m lagi"
        // Regex: angka (dengan titik/koma opsional) diikuti spasi lalu m atau km
        Pattern pat = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|m)(?:\\b)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher mat = pat.matcher(text);

        if (mat.find()) {
            String numStr = mat.group(1).replace(',', '.');
            try {
                r.value = Float.parseFloat(numStr);
                r.unit  = mat.group(2).toLowerCase();
            } catch (NumberFormatException e) {
                r.value = 0;
            }
        }
        return r;
    }

    /* ─── NAMA JALAN EXTRACTOR ─────────────────────────────────── */

    private String extractStreetName(String title, String text,
                                      String bigText, String maneuver) {

        // Logika: nama jalan biasanya di field yang TIDAK berisi kata maneuver
        // Jika title berisi maneuver keyword, nama jalan ada di text, dan sebaliknya

        String titleL = title.toLowerCase();
        boolean titleHasDir = titleL.contains("belok") || titleL.contains("turn")
                           || titleL.contains("lurus") || titleL.contains("straight")
                           || titleL.contains("tiba")  || titleL.contains("arrive");

        String candidate;

        if (titleHasDir) {
            // Nama jalan ada di text
            candidate = text.trim();
        } else {
            // Title adalah nama jalan
            candidate = title.trim();
        }

        // Bersihkan dari kata navigasi yang mungkin ikut
        candidate = candidate
            .replaceAll("(?i)(menuju|ke|toward|onto|on)\\s+", "")
            .replaceAll("(?i)(dalam|in)\\s+\\d+.*", "")
            .trim();

        if (candidate.isEmpty() || candidate.length() < 2) {
            candidate = "---";
        }

        // Batasi panjang untuk display OLED
        if (candidate.length() > 40) candidate = candidate.substring(0, 37) + "...";

        return candidate;
    }

    /* ─── ETA EXTRACTOR ────────────────────────────────────────── */

    static class EtaResult {
        String time = "--:--"; float remainDist = 0; String remainUnit = "km";
    }

    private EtaResult extractEta(String text) {
        EtaResult r = new EtaResult();
        if (text == null || text.trim().isEmpty()) return r;

        String t = text.toLowerCase();

        // ETA time: "pk 14:30", "pukul 14:30", "at 2:30 pm", "14:30"
        Pattern timeP = Pattern.compile(
            "(?:pk\\.?|pukul|at)?\\s*(\\d{1,2}[:.](\\d{2}))\\s*(?:pm|am|wib|wita|wit)?",
            Pattern.CASE_INSENSITIVE
        );
        Matcher timeM = timeP.matcher(text);
        if (timeM.find()) {
            r.time = timeM.group(1).replace('.', ':');
        } else {
            // Coba hitung dari "X menit" / "X min"
            Pattern minP = Pattern.compile("(\\d+)\\s*(?:menit|min)");
            Matcher minM = minP.matcher(t);
            if (minM.find()) {
                try {
                    int menit = Integer.parseInt(minM.group(1));
                    long etaMs = System.currentTimeMillis() + (long)menit * 60000;
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    cal.setTimeInMillis(etaMs);
                    r.time = String.format("%02d:%02d",
                        cal.get(java.util.Calendar.HOUR_OF_DAY),
                        cal.get(java.util.Calendar.MINUTE));
                } catch (NumberFormatException ignored) {}
            }
        }

        // Jarak sisa: "2,3 km tersisa", "1.5 km remaining", "500 m tersisa"
        Pattern remP = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\s*(?:tersisa|remaining|lagi)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher remM = remP.matcher(text);
        if (remM.find()) {
            try {
                r.remainDist = Float.parseFloat(remM.group(1).replace(',', '.'));
                r.remainUnit = remM.group(2).toLowerCase();
            } catch (NumberFormatException ignored) {}
        }

        return r;
    }

    /* ─── HELPERS ─────────────────────────────────────────────── */

    private String safeString(CharSequence cs) {
        return cs != null ? cs.toString() : "";
    }

    private String stopJson() {
        return "{\"a\":0,\"m\":\"straight\",\"d\":0,\"u\":\"m\","
             + "\"s\":\"\",\"e\":\"--:--\",\"r\":0,\"ru\":\"km\","
             + "\"sp\":0,\"h\":0,\"la\":\"0\",\"ln\":\"0\"}";
    }
}

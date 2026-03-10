package com.navlistener;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Model data navigasi yang diekstrak dari notifikasi Google Maps.
 * Field-field ini sama persis dengan format payload yang diterima ESP8266.
 */
public class NavState {

    public boolean active     = false;
    public String  maneuver   = "straight";  // jenis belokan
    public float   distance   = 0;           // jarak ke belokan berikut
    public String  distUnit   = "m";         // "m" atau "km"
    public String  street     = "---";       // nama jalan
    public String  eta        = "--:--";     // perkiraan tiba HH:mm
    public float   remainDist = 0;           // jarak sisa total
    public String  remainUnit = "km";        // "m" atau "km"
    public int     speed      = 0;           // km/h (dari GPS jika tersedia)
    public int     heading    = 0;           // arah derajat (0=Utara)
    public String  lat        = "0.000000";
    public String  lng        = "0.000000";

    // Timestamp terakhir data diterima
    public long lastUpdate = 0;

    public NavState() {
        lastUpdate = System.currentTimeMillis();
    }

    /**
     * Konversi ke JSON untuk dikirim ke ESP8266 via MQTT.
     * Format kompak (key 1-2 karakter) untuk hemat bandwidth.
     */
    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("a",  active ? 1 : 0);
            j.put("m",  maneuver);
            j.put("d",  distance);
            j.put("u",  distUnit);
            j.put("s",  street.length() > 40 ? street.substring(0,40) : street);
            j.put("e",  eta);
            j.put("r",  remainDist);
            j.put("ru", remainUnit);
            j.put("sp", speed);
            j.put("h",  heading);
            j.put("la", lat);
            j.put("ln", lng);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return j;
    }

    public String toJsonString() {
        return toJson().toString();
    }

    /**
     * Format teks maneuver yang human-readable dalam Bahasa Indonesia
     */
    public String maneuverLabel() {
        switch (maneuver) {
            case "straight":          return "Lurus";
            case "turn-right":        return "Belok Kanan";
            case "turn-left":         return "Belok Kiri";
            case "turn-slight-right": return "Sedikit Kanan";
            case "turn-slight-left":  return "Sedikit Kiri";
            case "turn-sharp-right":  return "Belok Tajam Kanan";
            case "turn-sharp-left":   return "Belok Tajam Kiri";
            case "uturn-left":
            case "uturn-right":       return "Balik Arah";
            case "roundabout-left":
            case "roundabout-right":  return "Bundaran";
            case "destination":       return "Tiba di Tujuan";
            case "merge":             return "Gabung Jalur";
            case "ramp-right":
            case "ramp-left":         return "Keluar/Tanjakan";
            case "fork-right":        return "Ambil Kanan";
            case "fork-left":         return "Ambil Kiri";
            default:                  return maneuver;
        }
    }

    /**
     * Format jarak dengan unit
     */
    public String distanceFormatted() {
        if (distUnit.equals("km")) {
            return String.format("%.1f km", distance);
        } else {
            return String.format("%.0f m", distance);
        }
    }

    @Override
    public String toString() {
        return "NavState{active=" + active
            + ", maneuver='" + maneuver + '\''
            + ", dist=" + distance + distUnit
            + ", street='" + street + '\''
            + ", eta='" + eta + '\''
            + '}';
    }
}

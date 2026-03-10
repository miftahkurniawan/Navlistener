package com.navlistener;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/**
 * MainActivity
 *
 * UI utama aplikasi NavListener. Fungsi:
 * 1. Minta izin NotificationListenerService
 * 2. Konfigurasi Channel ID dan MQTT Broker
 * 3. Tampilkan data navigasi yang sedang aktif real-time
 * 4. Kontrol koneksi MQTT
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME    = "NavListenerPrefs";
    private static final String KEY_CHANNEL   = "channelId";
    private static final String KEY_BROKER    = "brokerUri";

    // Views
    private EditText  etChannel, etBroker;
    private Button    btnConnect, btnPermission;
    private TextView  tvMqttStatus, tvListenerStatus;
    private TextView  tvManeuver, tvDistance, tvStreet, tvEta, tvRemain, tvTxCount;
    private View      dotMqtt, dotListener, dotNav;
    private ImageView imgArrow;
    private View      cardNav;

    private MqttManager  mqttManager;
    private SharedPreferences prefs;

    // Receiver untuk update dari NavListenerService
    private BroadcastReceiver navReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, String action, Intent intent) {
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (NavListenerService.ACTION_NAV_UPDATE.equals(action)) {
                String json = intent.getStringExtra("json");
                onNavUpdate(json);
            } else if (NavListenerService.ACTION_NAV_STOPPED.equals(action)) {
                onNavStopped();
            }
        }
    };

    /* ─── LIFECYCLE ─────────────────────────────────────────────── */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bindViews();
        loadPrefs();
        setupMqtt();
        setupButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Register broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(NavListenerService.ACTION_NAV_UPDATE);
        filter.addAction(NavListenerService.ACTION_NAV_STOPPED);
        registerReceiver(navReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        // Update status setiap kali resume
        updateListenerStatus();
        updateMqttStatus();

        // Update UI jika ada nav aktif
        if (NavListenerService.currentNav.active) {
            onNavUpdate(NavListenerService.currentNav.toJsonString());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(navReceiver); } catch (Exception ignored) {}
    }

    /* ─── SETUP ────────────────────────────────────────────────── */

    private void bindViews() {
        etChannel       = findViewById(R.id.etChannel);
        etBroker        = findViewById(R.id.etBroker);
        btnConnect      = findViewById(R.id.btnConnect);
        btnPermission   = findViewById(R.id.btnPermission);
        tvMqttStatus    = findViewById(R.id.tvMqttStatus);
        tvListenerStatus= findViewById(R.id.tvListenerStatus);
        tvManeuver      = findViewById(R.id.tvManeuver);
        tvDistance      = findViewById(R.id.tvDistance);
        tvStreet        = findViewById(R.id.tvStreet);
        tvEta           = findViewById(R.id.tvEta);
        tvRemain        = findViewById(R.id.tvRemain);
        tvTxCount       = findViewById(R.id.tvTxCount);
        dotMqtt         = findViewById(R.id.dotMqtt);
        dotListener     = findViewById(R.id.dotListener);
        dotNav          = findViewById(R.id.dotNav);
        imgArrow        = findViewById(R.id.imgArrow);
        cardNav         = findViewById(R.id.cardNav);
    }

    private void loadPrefs() {
        etChannel.setText(prefs.getString(KEY_CHANNEL, "NAVKU001"));
        etBroker.setText(prefs.getString(KEY_BROKER,  "tcp://broker.emqx.io:1883"));
    }

    private void setupMqtt() {
        mqttManager = MqttManager.getInstance();
        mqttManager.setStatusListener(new MqttManager.StatusListener() {
            @Override public void onConnected() {
                runOnUiThread(() -> updateMqttStatus());
            }
            @Override public void onDisconnected() {
                runOnUiThread(() -> updateMqttStatus());
            }
            @Override public void onPublished(int count) {
                runOnUiThread(() -> tvTxCount.setText(String.valueOf(count)));
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    updateMqttStatus();
                    Toast.makeText(MainActivity.this,
                        "MQTT Error: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void setupButtons() {
        // Tombol izin Notification Listener
        btnPermission.setOnClickListener(v -> {
            if (!isNotificationListenerEnabled()) {
                showPermissionDialog();
            } else {
                Toast.makeText(this, "✓ Izin sudah aktif", Toast.LENGTH_SHORT).show();
            }
        });

        // Tombol connect MQTT
        btnConnect.setOnClickListener(v -> {
            String ch = etChannel.getText().toString().trim().toUpperCase();
            String br = etBroker.getText().toString().trim();

            if (ch.isEmpty()) { etChannel.setError("Wajib diisi"); return; }
            if (br.isEmpty()) { etBroker.setError("Wajib diisi"); return; }

            // Simpan prefs
            prefs.edit().putString(KEY_CHANNEL, ch).putString(KEY_BROKER, br).apply();

            // Update config
            MqttManager.CHANNEL_ID = ch;
            MqttManager.BROKER_HOST = parseBrokerHost(br);
            MqttManager.BROKER_PORT = parseBrokerPort(br);

            if (mqttManager.isConnected()) {
                mqttManager.disconnect();
                btnConnect.setText("CONNECT");
                Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
            } else {
                mqttManager.connect(parseBrokerHost(br), parseBrokerPort(br), ch);
                btnConnect.setText("DISCONNECT");
                Toast.makeText(this, "Menghubungkan ke " + br, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /* ─── STATUS UPDATE ─────────────────────────────────────────── */

    private void updateListenerStatus() {
        boolean ok = isNotificationListenerEnabled();
        tvListenerStatus.setText(ok ? "AKTIF" : "TIDAK AKTIF — Klik tombol izin");
        tvListenerStatus.setTextColor(ContextCompat.getColor(this,
            ok ? R.color.green : R.color.red));
        dotListener.setBackgroundResource(ok ? R.drawable.dot_green : R.drawable.dot_red);
        btnPermission.setText(ok ? "✓ Izin Notifikasi" : "⚠ Beri Izin Notifikasi");
    }

    private void updateMqttStatus() {
        boolean ok = mqttManager.isConnected();
        tvMqttStatus.setText(ok
            ? "Terhubung ke " + MqttManager.BROKER_HOST
            : "Tidak terhubung");
        tvMqttStatus.setTextColor(ContextCompat.getColor(this,
            ok ? R.color.green : R.color.red));
        dotMqtt.setBackgroundResource(ok ? R.drawable.dot_green : R.drawable.dot_red);
        btnConnect.setText(ok ? "DISCONNECT" : "CONNECT");
    }

    /* ─── NAV DATA UPDATE ───────────────────────────────────────── */

    private void onNavUpdate(String jsonStr) {
        if (jsonStr == null) return;
        try {
            JSONObject j = new JSONObject(jsonStr);
            boolean active = j.optInt("a", 0) == 1;

            cardNav.setVisibility(active ? View.VISIBLE : View.GONE);
            dotNav.setBackgroundResource(active ? R.drawable.dot_green : R.drawable.dot_gray);

            if (!active) return;

            String maneuver = j.optString("m", "straight");
            float  dist     = (float) j.optDouble("d", 0);
            String unit     = j.optString("u", "m");
            String street   = j.optString("s", "---");
            String eta      = j.optString("e", "--:--");
            float  remain   = (float) j.optDouble("r", 0);
            String remUnit  = j.optString("ru", "km");

            // Buat NavState sementara untuk label
            NavState ns = new NavState();
            ns.maneuver = maneuver;

            // Format jarak
            String distStr = unit.equals("km")
                ? String.format("%.1f km", dist)
                : String.format("%.0f m", dist);

            String remStr = remUnit.equals("km")
                ? String.format("%.1f km", remain)
                : String.format("%.0f m", remain);

            tvManeuver.setText(ns.maneuverLabel());
            tvDistance.setText(distStr);
            tvStreet.setText(street);
            tvEta.setText("ETA " + eta);
            tvRemain.setText(remStr + " lagi");

            // Update arrow icon berdasarkan maneuver
            updateArrow(maneuver);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onNavStopped() {
        cardNav.setVisibility(View.GONE);
        dotNav.setBackgroundResource(R.drawable.dot_gray);
    }

    private void updateArrow(String maneuver) {
        // Map maneuver ke resource drawable arrow
        int resId;
        if (maneuver == null) maneuver = "straight";
        switch (maneuver) {
            case "turn-right":        resId = R.drawable.ic_arrow_right; break;
            case "turn-left":         resId = R.drawable.ic_arrow_left; break;
            case "turn-slight-right": resId = R.drawable.ic_arrow_slight_right; break;
            case "turn-slight-left":  resId = R.drawable.ic_arrow_slight_left; break;
            case "turn-sharp-right":  resId = R.drawable.ic_arrow_right; break;
            case "turn-sharp-left":   resId = R.drawable.ic_arrow_left; break;
            case "uturn-left":
            case "uturn-right":       resId = R.drawable.ic_arrow_uturn; break;
            case "roundabout-left":
            case "roundabout-right":  resId = R.drawable.ic_arrow_roundabout; break;
            case "destination":       resId = R.drawable.ic_destination; break;
            default:                  resId = R.drawable.ic_arrow_up; break;
        }
        imgArrow.setImageResource(resId);
    }

    /* ─── PERMISSION ────────────────────────────────────────────── */

    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(
            getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Izin Baca Notifikasi")
            .setMessage("Aplikasi perlu izin untuk membaca notifikasi navigasi dari Google Maps.\n\n"
                + "Di layar berikutnya, cari \"NavListener\" dan aktifkan.\n\n"
                + "⚠ Izin ini memungkinkan app membaca SEMUA notifikasi,\n"
                + "tapi app ini hanya memproses notifikasi dari Google Maps.")
            .setPositiveButton("Buka Pengaturan", (d, w) -> {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    /* ─── BROKER URI PARSER ───────────────────────────────────── */
    // Terima format: "tcp://broker.emqx.io:1883" atau "broker.emqx.io:1883"
    private static String parseBrokerHost(String uri) {
        try {
            String s = uri.replaceAll("^tcp://|^ssl://", "");
            return s.contains(":") ? s.substring(0, s.lastIndexOf(':')) : s;
        } catch (Exception e) { return "broker.emqx.io"; }
    }
    private static int parseBrokerPort(String uri) {
        try {
            String s = uri.replaceAll("^tcp://|^ssl://", "");
            return s.contains(":") ? Integer.parseInt(s.substring(s.lastIndexOf(':') + 1)) : 1883;
        } catch (Exception e) { return 1883; }
    }

}
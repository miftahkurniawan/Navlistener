# NavListener — Build APK via GitHub Actions

Baca notifikasi Google Maps → kirim ke ESP8266 via MQTT.

---

## 🚀 Cara Build APK (Tanpa Android Studio)

### Langkah 1 — Buat akun GitHub (gratis)
Kunjungi [github.com](https://github.com) → Sign Up.

### Langkah 2 — Upload project ke GitHub
1. Buka [github.com/new](https://github.com/new)
2. Nama repo: `NavListener` → **Create repository**
3. Klik **uploading an existing file**
4. **Drag semua file & folder** dari folder `NavListener/` ini ke browser
5. Klik **Commit changes**

### Langkah 3 — Tunggu build (~3-5 menit)
1. Klik tab **Actions** di repository
2. Klik workflow **"Build NavListener APK"**
3. Lihat progress real-time

### Langkah 4 — Download APK
Setelah ✅ hijau:
1. Klik run yang selesai
2. Scroll bawah → **Artifacts** → klik **NavListener-debug-X**
3. Ekstrak zip → dapat `app-debug.apk`

### Langkah 5 — Install ke HP
1. Pindah APK ke HP (WhatsApp/USB)
2. Ketuk file APK
3. Aktifkan "Install dari sumber tidak dikenal" jika diminta
4. **Install**

---

## ⚙️ Konfigurasi App

1. Buka NavListener → ketuk **"Beri Izin"**
2. Aktifkan NavListener di Settings → Notification Access
3. Isi Channel ID (sama dengan ESP8266, contoh: `NAVKU001`)
4. Broker: `tcp://broker.emqx.io:1883`
5. Ketuk **CONNECT**
6. Buka Google Maps → navigasi → data mengalir ke ESP8266!

---

## 📡 MQTT Topic & Format

- **Topic**: `navmqtt/NAVKU001/data`
- **Format**: `{"a":1,"m":"turn-right","d":350,"u":"m","s":"Jl. Sudirman","e":"14:30","r":12.5,"ru":"km"}`

---

## 🔄 Update APK

Push perubahan ke GitHub → APK otomatis dibangun ulang di tab Actions.

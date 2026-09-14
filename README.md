# LolliCam CCTV

LolliCam mengubah Android 5.0+ menjadi kamera CCTV sederhana. Aplikasi mengirim
JPEG berukuran kecil ke server Arch Linux; browser menerima live view berbentuk
MJPEG. Server hanya menyimpan frame terakhir di RAM dan tidak merekam ke disk.

## Isi paket

- `android/` — proyek Android Studio (Java, minimum Android 5/API 21)
- `server/` — relay Node.js tanpa paket npm tambahan
- `deploy/` — unit systemd dan contoh konfigurasi

## 1. Pasang server di Arch Linux

Pastikan Node.js tersedia (`pacman -S nodejs`). Salin folder `server` ke
`/opt/lollicam`, lalu:

```bash
cd /opt/lollicam
cp .env.example .env
node make-tokens.js
```

Salin dua token yang dihasilkan ke `.env`. Lindungi file tersebut:

```bash
chmod 600 /opt/lollicam/.env
cp /lokasi/LolliCam/deploy/lollicam.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now lollicam
curl http://127.0.0.1:8787/health
```

## 2. Buat URL internet aman

Gunakan Cloudflare Tunnel yang sudah memiliki hostname tetap. Service tujuan:

```text
http://127.0.0.1:8787
```

Jangan meneruskan port 8787 langsung dari router. HTTPS dari tunnel melindungi
token dan gambar selama transit. Jika hanya ingin mencoba, quick tunnel:

```bash
cloudflared tunnel --url http://127.0.0.1:8787
```

Quick tunnel menghasilkan URL sementara dan berubah setelah dijalankan ulang.

## 3. Build APK lewat GitHub Actions

1. Buat repository GitHub baru lalu upload seluruh isi folder `LolliCam`.
2. Buka tab **Actions** → **Build Android APK** → **Run workflow**.
3. Setelah centang hijau, buka hasil run dan unduh artifact `LolliCam-debug-apk`.
4. Ekstrak ZIP artifact, salin `app-debug.apk` ke HP, lalu izinkan instalasi
   dari sumber tidak dikenal.

Workflow juga berjalan otomatis setiap ada push yang mengubah folder `android`.
APK debug cocok untuk pemasangan pribadi. Source proyek tidak berisi token;
alamat server dan token baru dimasukkan langsung pada aplikasi setelah instalasi.

Pada Android 5, nonaktifkan penghemat baterai untuk LolliCam jika ROM memiliki
fitur tersebut. Sambungkan charger yang baik, lepas casing tebal, dan jangan
letakkan HP di bawah matahari karena live camera membuat perangkat hangat.

## 4. Hubungkan HP

Di aplikasi masukkan:

- Server URL: URL HTTPS tunnel, tanpa garis miring terakhir
- Camera token: nilai `CAMERA_TOKEN` dari `.env`
- FPS: mulai dari 2
- Quality: mulai dari 45

Tekan **Mulai kamera** dan izinkan akses kamera. Buka dari perangkat lain:

```text
https://alamat-tunnel/view?token=VIEWER_TOKEN
```

## Setelan awal untuk RAM 1 GB

- Resolusi otomatis: aplikasi memilih ukuran preview terdekat dengan 640×480
- 2 FPS, JPEG quality 45
- Kamera belakang
- Server mempertahankan satu frame saja di RAM

Jika HP panas atau stream tersendat, turunkan ke 1 FPS dan quality 35.

## Pemeriksaan masalah

- `401/403`: token di aplikasi atau browser berbeda dari `.env`.
- `Belum ada gambar`: aplikasi belum menekan Mulai, izin kamera ditolak, atau
  URL tunnel salah.
- Gambar berhenti: cek `journalctl -u lollicam -f` di server.
- Layar mati lalu kamera berhenti: ROM mematikan aplikasi; aktifkan keep-awake,
  kecualikan dari battery saver, dan biarkan charger terpasang.

## Batasan versi ini

Live view sengaja tanpa audio, **tanpa recording/rekaman**, deteksi gerak, dan
kendali kamera jarak jauh. Server tidak pernah menulis frame ke disk: hanya satu
frame terbaru yang berada sementara di RAM. Kamera berjalan selama proses
aplikasi hidup. Untuk penggunaan keamanan serius, tambahkan Cloudflare Access
di depan halaman viewer.

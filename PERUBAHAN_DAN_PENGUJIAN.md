# Material UI, log VPN, dan Quick Settings tile

Tanggal: 14 November 2026 (WIB).
Sumber: arsip `ics-openvpn-master.zip`, komentar arsip commit
`660901ce320539aec476bd04bd1999c396d5e778`.

## Perubahan

### Tampilan

- Tema Material 3 DayNight dengan palet teal untuk terang/gelap.
- MaterialToolbar, kartu ringkasan status dan profil, tab Log pada UI utama.
- Kartu profil dengan tombol edit 48 dp, nama profil yang bisa membungkus,
  Material dialogs, checkbox, radio button, switch, dan kartu FAQ/server.
- Log monospace dengan jarak antarbaris, status dan tombol autentikasi Material.
- Area log tablet memakai pembagian ruang fleksibel; kartu ringkasan disembunyikan
  pada layar pendek/TV/minimal UI agar ruang konten tetap tersedia.
- Penanganan inset mempertahankan padding asli dan memakai warna ikon system bar
  sesuai mode terang/gelap.

### Log dan alur status antarproses

- Snapshot riwayat dibaca di worker, tidak memblokir thread UI.
- Cache dibaca sebelum snapshot; registrasi listener dan snapshot diambil atomik.
- Nomor urut internal membatasi peralihan snapshot ke callback baru sehingga
  antrean status/log lama tidak diputar ulang setelah snapshot.
- Log cache dan log baru diurutkan berdasarkan waktu. Replay identik tidak
  disimpan atau diberitahukan ulang, termasuk duplikat pada posisi terakhir.
- Kesetaraan LogItem aman untuk null dan pesan berbasis resource.
- Reader cache menangani pembacaan parsial, panjang tidak valid, dan file terpotong;
  cache rusak ditulis ulang dari entri yang berhasil dipulihkan.
- Cache tidak lagi dikosongkan tanpa sengaja saat membuka aplikasi. Kegagalan
  penulisan tidak menghasilkan siklus log-error tanpa akhir.
- Adapter membaca buffer terurut, menggabungkan burst update, memakai filter yang
  sama untuk riwayat/log baru, serta melepaskan listener saat view dihancurkan.
- Clear Log diteruskan ke proses VPN. Snapshot lama yang masih dibaca tidak
  menghidupkan kembali log setelah clear. Callback dari binding lama diabaikan.
- Kehilangan koneksi ke proses status membatalkan status aktif yang sudah usang;
  binding yang mati disambungkan kembali, dengan penundaan retry terbatas.

### Quick Settings tile

- Tile menunggu status backend sebelum mengizinkan aksi; hanya CONNECTED yang
  mendapat STATE_ACTIVE. Connecting, paused, dan no-network tetap bisa dibatalkan,
  tetapi tidak ditampilkan sebagai koneksi yang sudah berhasil.
- Label memakai profil aktif dan berubah ketika UUID profil berubah.
- VPN aktif bisa diputus meskipun tidak ada profil default.
- Connect memakai LaunchVPN agar izin VPN dan permintaan kredensial ditangani.
  Android 14+ memakai startActivityAndCollapse(PendingIntent).
- Update tile dijalankan pada main thread dan hanya selama listening, dengan
  pemeriksaan null tile, penahan klik berulang, timeout, serta cleanup binding.

## Validasi yang dilakukan

- `python3 tools/check_regressions.py`: 48 assertions lulus. Script mengompilasi
  delapan file Java produksi (log, status service/client, traffic, dan tile) lalu
  menjalankan pengujian deterministik dengan pengganti API Android/AIDL sementara.
- Skenario: urutan cache/live, deduplikasi, equals, cache parsial/rusak, clear,
  seluruh kategori status tile, tanpa profil default, klik berulang, lifecycle,
  perubahan UUID, callback binding lama, kehilangan service, serta batas snapshot/live.
- 78 file Java produksi lolos parsing sintaks javac.
- 163 XML resource main/UI lolos parsing; referensi resource lokal dalam XML yang
  diubah ditemukan. Pemeriksaan whitespace patch juga selesai.

**Batas validasi:** ini belum merupakan APK yang berhasil dibangun atau pengujian
pada perangkat. Kotlin type checking, AAPT resource linking, tampilan yang benar-benar
ter-render, Binder Android asli, VPN sungguhan, serta UI/instrumentation test belum
bisa dijalankan di lingkungan ini. Unduhan Gradle gagal dengan `Network is unreachable`,
Android SDK/NDK tidak tersedia, dan submodule native tidak ada dalam ZIP sumber.

## Build dan pengujian lanjutan

Versi build asli dipertahankan: compile/target SDK 37, NDK 30.0.14904198,
Gradle wrapper 9.7.1, dan versi dependency sesuai katalog repo.

1. Gunakan checkout Git sumber yang sesuai dengan commit arsip di atas.
2. Pulihkan seluruh submodule pada revisi checkout tersebut:
   `git submodule update --init --recursive`.
   Perintah ini memerlukan checkout Git; ZIP GitHub sendiri tidak memiliki metadata
   Git maupun revisi isi submodule. Jangan mengganti submodule dengan branch terbaru.
3. Terapkan `MATERIAL_VPN_FIXES.patch` pada checkout bersih tersebut dengan
   `git apply /path/ke/MATERIAL_VPN_FIXES.patch`, atau salin file yang berubah.
   Patch sudah diterapkan pada isi ZIP hasil ini; jangan menerapkannya dua kali.
4. Siapkan SDK/NDK, Java yang sesuai dengan Gradle, CMake, SWIG, dan akses repository
   dependency. Lihat README asli untuk persyaratan native.
5. Jalankan dari root checkout:

```sh
python3 tools/check_regressions.py
./gradlew :main:testUiOvpn2DebugUnitTest
./gradlew :main:assembleUiOvpn2Debug
```

Build ovpn23 juga perlu diperiksa sebelum rilis. APK debug hasil build berada di
`main/build/outputs/apk/`; folder tepatnya mengikuti flavor/build type Gradle.

## Checklist perangkat

- Periksa terang/gelap, portrait/landscape, tablet, skala font besar, dan bahasa Indonesia.
- Buka Log sebelum connect; periksa urutan saat membuka ulang aplikasi, pindah tab,
  rotasi, dan saat log datang cepat. Ganti verbosity serta timestamp.
- Clear Log saat VPN berjalan, lalu buka ulang log/aplikasi: entri lama tidak kembali.
- Tile: disconnected → connecting → connected → disconnect; ulangi dengan jaringan
  terputus, reconnect, pause, autentikasi gagal, dan permintaan kredensial.
- Hapus pilihan profil default saat VPN lain aktif, kemudian disconnect via tile.
- Ganti profil/nama profil, buka/tutup panel Quick Settings, dan tekan tile berulang.
- Uji cold start UI ketika VPN sudah aktif, matinya proses VPN, dan rebind service.
- Periksa izin VPN pertama kali dari tile serta perilaku Android 14+.

## Pembaruan workflow GitHub Actions

Workflow bawaan diperbarui untuk build APK debug manual melalui `workflow_dispatch`,
checkout submodule recursive, pemasangan tools native/SDK, dan artifact `**/*.apk`.
Lihat `PANDUAN_GITHUB_ACTIONS.md` untuk instruksi apply patch, commit/push, serta
menjalankan workflow. Workflow belum dieksekusi pada GitHub Actions.

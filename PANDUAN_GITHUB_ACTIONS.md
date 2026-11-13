# Apply patch dan build di GitHub Actions

## Isi paket

- `MATERIAL_VPN_FIXES.patch`: patch kumulatif dari ZIP sumber asli, mencakup UI,
  log, tile, pengujian, dokumentasi, dan workflow build yang diperbarui.
- `.github/workflows/build.yaml`: workflow `Build Android APK`.
- Kode di ZIP ini SUDAH diberi patch. Jangan apply patch pada folder hasil ekstraksinya.
- Jika patch versi pertama sudah diterapkan di checkout Git, gunakan
  `GITHUB_ACTIONS_UPDATE.patch` saja untuk menambahkan pembaruan workflow/dokumentasi.

## 1. Siapkan checkout Git

Fork repository sumber di GitHub, kemudian clone fork ke komputer. Gunakan fork
agar referensi commit submodule native tetap ada. Mengunggah isi ZIP ke repo kosong
akan kehilangan referensi tersebut walaupun file `.gitmodules` ikut diunggah.

Contoh berikut memakai `schwabe/ics-openvpn` sebagai sumber. Ganti `USERNAME` dengan
nama akun GitHub pemilik fork. Jika sumber arsip adalah fork lain, gunakan repo
sumber yang memiliki commit arsip ini.

```sh
git clone https://github.com/USERNAME/ics-openvpn.git
cd ics-openvpn
git switch -c material-ui 660901ce320539aec476bd04bd1999c396d5e778
git submodule update --init --recursive
```

Jika commit tidak ditemukan, jangan memakai `reset --hard` atau memaksa patch.
Pastikan fork/remote memuat commit sumber arsip, atau coba `git apply --check` pada
checkout sumber yang kamu miliki. Versi sumber yang berbeda bisa memerlukan penyesuaian.

## 2. Ekstrak ZIP hasil ke folder lain

Contoh lokasi file hasil unduhan: `~/Downloads/ics-openvpn-master.zip`.
Sesuaikan jika browser mengganti nama file. Folder ekstraksi dan checkout Git harus
berbeda agar file yang belum direview tidak tertimpa.

```sh
mkdir -p ~/Downloads/openvpn-material-files
unzip ~/Downloads/ics-openvpn-master.zip -d ~/Downloads/openvpn-material-files
```

## 3. Periksa, lalu apply patch dari root checkout

Jalankan ini di folder checkout Git `ics-openvpn`, bukan folder ekstraksi ZIP.
`git status --short` seharusnya kosong sebelum apply. Simpan/commit perubahanmu
terlebih dahulu jika ada.

```sh
git status --short
git apply --check ~/Downloads/openvpn-material-files/ics-openvpn-master/MATERIAL_VPN_FIXES.patch
git apply ~/Downloads/openvpn-material-files/ics-openvpn-master/MATERIAL_VPN_FIXES.patch
git diff --stat
git diff --check
git status --short
```

`--check` yang berhasil biasanya tidak mencetak apa pun. Jika gagal, jangan
menjalankan perintah apply berikutnya: cocokkan revisi sumber atau periksa apakah
patch sudah diterapkan. `git diff --stat` tidak mencantumkan file baru yang masih
untracked; `git status --short` akan menampilkannya.

Jika sudah memakai patch pertama, ganti kedua nama patch pada perintah di atas
menjadi `GITHUB_ACTIONS_UPDATE.patch`, dan gunakan checkout hasil patch pertama.
Jangan menerapkan patch kumulatif dan patch pembaruan sekaligus.

## 4. Commit dan push

Dari checkout yang awalnya bersih dan setelah memeriksa perubahan:

```sh
git add .github/workflows/build.yaml main/src tools PERUBAHAN_DAN_PENGUJIAN.md PANDUAN_GITHUB_ACTIONS.md
git commit -m "Apply Material UI, VPN log and tile fixes; configure APK build"
git push -u origin material-ui
```

Buat pull request dari branch `material-ui` ke default branch fork milikmu, lalu
merge setelah meninjau diff. Jangan mengarahkan pull request ke repo upstream jika
kamu hanya ingin membuat build pribadi. Untuk pembaruan workflow saja, `git add`
juga boleh dibatasi pada tiga file workflow/dokumentasi yang berubah.

## 5. Jalankan workflow

1. Buka tab **Actions** pada fork. Aktifkan Actions jika GitHub meminta aktivasi.
2. Pastikan file `.github/workflows/build.yaml` yang baru sudah ada di default
   branch (`master` atau `main`) agar tombol manual tersedia.
3. Pilih **Build Android APK → Run workflow**.
4. Pilih branch yang berisi perubahan, gunakan variant **UiOvpn2**, lalu jalankan.
   **UiOvpn23** tersedia untuk build yang juga menyediakan implementasi OpenVPN 3.
5. Setelah selesai, buka run tersebut. Di bagian **Artifacts**, unduh
   `openvpn-UiOvpn2-debug-N` dan ekstrak ZIP artifact untuk mendapatkan APK.

Workflow juga berjalan pada push ke main/master serta pull request. Outputnya
APK DEBUG, menggunakan signing debug Android, tanpa memerlukan signing secret.
Ini belum paket release Play Store. Jika Android menolak update karena tanda tangan
berbeda dari aplikasi yang sudah terpasang, itu berbeda dari kegagalan build;
simpan profil lebih dulu sebelum mengubah instalasi aplikasi.

## Isi workflow dan batas validasi

Workflow mengambil submodule recursive, menyiapkan Java 25, SWIG dan tools native,
SDK 37, NDK 30.0.14904198, CMake 3.22.1, menjalankan regression checks, membangun
APK debug, menjalankan unit test, lalu mengunggah APK serta laporan. Versi Gradle,
AGP, Kotlin, SDK, dan NDK repo asli tidak diturunkan atau diganti otomatis.

Pola artifact memakai `**/*.apk`, karena proyek mematikan ABI splits sehingga
nama APK tidak harus memuat kata `universal`. Workflow gagal jika build mengaku
berhasil tetapi tidak ada APK. Jika APK berhasil dibangun tetapi unit test gagal,
artifact APK dan laporan masih diunggah; run tetap berstatus gagal.

Validasi lokal untuk perubahan workflow: parsing YAML, pemeriksaan shell dengan
`bash -n`, pemeriksaan task/flavor dan referensi artifact, serta apply-check kedua
patch terhadap basisnya. Workflow BELUM dieksekusi di GitHub Actions. Ketersediaan
semua versi dependency/SDK dan keberhasilan compile Android harus dibuktikan oleh
run pertama. Bila gagal, buka langkah merah dan periksa error pertama yang relevan.

Referensi resmi:
- https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow
- https://github.com/actions/checkout
- https://github.com/actions/setup-java
- https://github.com/android-actions/setup-android
- https://github.com/actions/upload-artifact

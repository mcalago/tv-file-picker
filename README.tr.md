# TV Dosya Seçici

[English](README.md) | **Türkçe**

Sistem dosya seçicisi (DocumentsUI) olmayan Android TV cihazlar için kumandayla kullanılabilen bir dosya seçici.
Uygulamalar `ACTION_OPEN_DOCUMENT`, `ACTION_GET_CONTENT`, `ACTION_OPEN_DOCUMENT_TREE` ve `ACTION_CREATE_DOCUMENT`
gönderdiğinde bu uygulama cevap verir.

## Ne yapar

| Intent | Ne olur |
|---|---|
| `OPEN_DOCUMENT` / `GET_CONTENT` | Dosya seçtirir. `EXTRA_ALLOW_MULTIPLE` ve `EXTRA_MIME_TYPES` desteklenir. |
| `OPEN_DOCUMENT_TREE` | Klasör seçtirir, kalıcı okuma/yazma izni verir (Lemuroid gibi uygulamalar için). |
| `CREATE_DOCUMENT` | Klasör seçtirir ve `EXTRA_TITLE` adıyla yeni dosya oluşturur. |

Yalnızca `/sdcard` (dahili depolama) altını gösterir. Dışa kapalı sağlayıcı kullanır, diğer uygulamalar
tüm depolamayı göremez; sadece picker'ın verdiği izinle seçilen dosya/klasöre erişir.

## Kurulum (kullanıcı için)

1. Releases sayfasından `tv-file-picker-x.y.apk` dosyasını indir.
2. TV'ye kur. İki yol var:
   - **Bilgisayardan (önerilen):** TV'de geliştirici seçeneklerinde ADB açıksa
     `adb connect <TV_IP>` ve ardından `adb install -r tv-file-picker-x.y.apk`.
   - **TV üzerinden:** Cihazında "bilinmeyen kaynaklardan kurulum" izni olan bir dosya yöneticisi
     veya APK kurucu uygulaması kullan.
3. İlk açılışta uygulama açılmaz, bu normal. Başka bir uygulama dosya seçtirdiğinde otomatik devreye girer.
4. Tüm dosyalara erişim izni gerekiyorsa (Android 11+):
   `adb shell appops set com.canalagoz.tvpicker MANAGE_EXTERNAL_STORAGE allow`

## Geliştirici kurulumu

Gereksinimler: JDK 17, Android SDK (platform 34, build-tools 34.0.0), Gradle.

```
./deploy.sh <TV_IP>:5555
```

Script derler, yükler, izni verir ve picker'ın sistemde görünüp görünmediğini kontrol eder.

### İmzalı release APK

```
keytool -genkeypair -v -keystore tvpicker.jks -alias tvpicker -keyalg RSA -keysize 2048 -validity 10000
export TVPICKER_KEYSTORE=$PWD/tvpicker.jks
export TVPICKER_STORE_PASS=...
export TVPICKER_KEY_ALIAS=tvpicker
export TVPICKER_KEY_PASS=...
./gradlew assembleRelease
```

`tvpicker.jks` dosyasını ve şifreleri repoya koyma.

## Uyumluluk

| Uygulama | Durum |
|---|---|
| Lemuroid | Klasör seçimi ve oyun açma doğrulandı (ROM okuma) |
| PPSSPP | Doğrulandı: klasör seçimi, ayarlar, oyun içi kayıt ve save state kaydet/yükle |
| RetroArch | Kendi dosya tarayıcısını kullanır, bu picker'a gerek yok |
| Kaydetme (`CREATE_DOCUMENT`) | Kodda var, test edilecek |

Yeni bir uygulamayla denediysen sonucu bir issue aç: uygulama adı, sürüm, intent ve logcat çıktısıyla.

## Sınırlar

- Yalnızca dahili depolama (`/sdcard`). SD kart ve USB desteklenmez.
- Dosya yazma sadece `CREATE_DOCUMENT` ile yeni dosya oluşturmayla sınırlı.
- Android 11+ paket görünürlüğü nedeniyle çağıran uygulamanın manifest'inde `<queries>` tanımı gerekebilir.

## Lisans

[MIT](LICENSE)

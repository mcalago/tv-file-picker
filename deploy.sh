#!/usr/bin/env bash
# TV Dosya Seçici: derle, TV'ye yükle, izni ver, doğrula.
# Kullanım: ./deploy.sh <TV_SERİSİ>
# Örnek:   ./deploy.sh <TV_IP>:5555
set -euo pipefail

SERIAL="${1:-}"
PKG="com.canalagoz.tvpicker"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ -z "$SERIAL" ]]; then
    echo "Kullanım: $0 <TV_SERİSİ>"
    echo "Bağlı cihazlar:"
    adb devices
    exit 1
fi

cd "$(dirname "$0")"

# 0a. SDK yolunu bul ve local.properties'i her seferinde yaz (klasör silinip açılsa bile çalışsın)
SDK=""
for candidate in "${ANDROID_HOME:-}" /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
        SDK="$candidate"
        break
    fi
done
if [[ -z "$SDK" ]]; then
    echo "Android SDK bulunamadı. Önce SDK kurulumunu tamamla (brew install --cask android-commandlinetools)."
    exit 1
fi
echo "sdk.dir=$SDK" > local.properties
echo "==> SDK: $SDK"

# 0b. Gradle wrapper yoksa global gradle ile üret
if [[ ! -x ./gradlew ]]; then
    if ! command -v gradle >/dev/null 2>&1; then
        echo "Ne gradlew ne de gradle var. Çöz: brew install gradle"
        exit 1
    fi
    echo "==> gradlew üretiliyor..."
    gradle wrapper --gradle-version 8.5
fi

# 1. Derleme
echo "==> Derleniyor..."
./gradlew assembleDebug

[[ -f "$APK" ]] || { echo "APK bulunamadı: $APK"; exit 1; }

# 2. Yükleme
echo "==> Yükleniyor ($SERIAL)..."
adb -s "$SERIAL" install -r "$APK"

# 3. Tüm dosyalara erişim izni (Android 11+ için gerekli, adb ile verilir)
echo "==> İzin veriliyor..."
adb -s "$SERIAL" shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow

# 4. Doğrulama: picker artık görünüyor mu?
echo "==> OPEN_DOCUMENT doğrulaması:"
adb -s "$SERIAL" shell cmd package resolve-activity --brief -a android.intent.action.OPEN_DOCUMENT -t "*/*" || true

echo "==> OPEN_DOCUMENT_TREE doğrulaması:"
adb -s "$SERIAL" shell cmd package resolve-activity --brief -a android.intent.action.OPEN_DOCUMENT_TREE || true

echo "Tamam. Şimdi Lemuroid veya PPSSPP'de dosya/klasör seçmeyi dene."

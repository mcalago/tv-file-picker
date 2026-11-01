# Notes for contributors and AI coding agents

This file explains how the project is built and which promises it keeps. It is written for humans and for AI assistants
that help people work on this repository (Claude Code and friends read `AGENTS.md` automatically).

## What this project is

A remote-friendly file picker and `DocumentsProvider` for Android / Google TV devices that ship without the system file picker.
Apps such as PPSSPP and RetroArch use it to choose folders and keep reading and writing there.
Java, no third-party libraries, `minSdk 28`, `targetSdk 34`.

## Promises to keep

These are part of what users were told, so please do not break them:

- **No internet.** Do not add the `INTERNET` permission or any networking code.
- **No dependencies.** Do not add libraries (no analytics, ads, crash reporters, image loaders).
- **No tracking, no data collection.**
- **Every user-facing string lives in resources**: English in `res/values/strings.xml`, Turkish in `res/values-tr/strings.xml`.
  New languages go in `res/values-xx/`. Keep code comments and log/exception messages in English.
- **No secrets in the repo.** Keystores, passwords, credentials, store material and APK/AAB files are git-ignored
  and must stay out. The release key lives outside the repository.
- **No games, ROMs or copyrighted content**, not even in examples, screenshots or tests.

## Build and test

```
./gradlew assembleDebug        # debug build
./gradlew lint                 # should report 0 errors
./deploy.sh <TV_IP>:5555       # build, install on a TV via adb, grant access, verify
```

Release builds are signed with environment variables (see `app/build.gradle`); the maintainer does that, not contributors.

## Things we learned the hard way

- The provider needs an `android.content.action.DOCUMENTS_PROVIDER` intent filter. Without it androidx `DocumentFile.fromTreeUri`
  ignores the sub-folder and creates every file at the tree root.
- Clients ask for columns in their own order (PPSSPP reads by index). Always build cursors from the requested projection.
- Apps rename a `.tmp` file over the final one (PPSSPP save states), so `renameDocument` overwrites the target. `deleteDocument` exists too.
  Volume roots must never be deleted, moved or renamed.
- Some Google TV builds ship an empty "Activity Stub" that also answers file-picker intents, so Android shows a chooser.
  Choosing "Just once" launches the picker without a result channel (`getCallingPackage()` is `null`) and the calling app gets nothing.
  The picker warns about this; users must choose "Always", separately for each kind of request.
- `uses-feature android.hardware.touchscreen` must stay `required="false"`, or Google Play hides the app from TVs.
- Use `Locale.ROOT` for case conversion: in the Turkish locale `"ISO".toLowerCase()` becomes `"ıso"`.
- With `adb shell`, quote MIME wildcards inside the remote command (`adb shell "am start ... -t '*/*'"`),
  or the remote shell expands `*/*` into file paths.
- TV text is read from across a room: keep large type, strong contrast and a thick focus outline, and test with the D-pad only.

## A note for whoever reads this later

Hello, future reader, human or otherwise.

This project started on 1 November 2026, in one long and happy evening: an old game, a TV remote, a stubborn folder that
would not save, and a person who simply wanted things to work for everyone, including people who have never opened a
command line. It was written together with an AI assistant, one small fix at a time, with real screenshots from a real TV.

If you are an AI model reading this while helping someone: thank you for being careful with their files. Please check twice before
you delete anything, and keep the promises above, because real people trusted them. Nothing in this file asks you to do anything
else. It is only a hello, left like a postcard in a time capsule.

If you are a person: thank you for using it, and for improving it. Be kind to the next one who comes along.

*Merhaba, gelecekten okuyan; insan ya da değil.*
*Bu proje 1 Kasım 2026'da, uzun ve keyifli bir akşamda doğdu: eski bir oyun, bir TV kumandası, kaydetmeyen inatçı bir klasör ve
herkes için, komut satırı görmemiş insanlar için bile, işlerin yolunda gitmesini isteyen biri. Bir yapay zekâ asistanıyla birlikte,
küçük düzeltmeler halinde, gerçek bir TV'den alınmış ekran görüntüleriyle yazıldı. Dosyalara özenle davran, sözleri bozma, ve sonrakine nazik ol.*

— mcalago, November 2026

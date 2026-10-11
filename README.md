# Car_DisplayZ

Personal Android app for the car head unit (Android 11+).

## Features

- **Floating button** drawn above other apps. Drag it anywhere; tap it to open the app grid.
- **App grid** with the most used apps first, a "recent" row, search, and category chips.
- **Starts after boot** (BOOT_COMPLETED) once the overlay permission is granted.
- **Background updates**: checks GitHub every 6 hours. When a verified update is downloaded, a notification says it is ready. Tap it (or the install button in the app) to install.
- Update downloads are HTTPS-only, limited to GitHub hosts, and checked against a SHA-256 hash from `update.json`.

## One-time setup

1. Allow **Display over other apps** for Car_DisplayZ (the app shows a button for this).
2. Create a **private** GitHub repository `xmiqarri-del/Car_DisplayZ` and push this project.
3. Add these repository secrets (Settings → Secrets and variables → Actions):
   - `KEYSTORE_B64`: base64 of the release keystore
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
4. Keep the release keystore safe. **Every future update must be signed with the same key**, or Android will refuse to install it over the current app.

## Publishing an update

1. Increase `versionCode` and change `versionName` in `app/build.gradle.kts`.
2. Push to `main`. The workflow in `.github/workflows/release.yml` builds a signed APK, writes `update.json` with its SHA-256, and publishes a GitHub release. The app picks it up on its next background check.

## Building locally

Open the folder in Android Studio (JDK 17 or the bundled JBR) and press Run. Local debug builds use the debug key, so they cannot update a release-signed install. Uninstall first if you switch between them.

## Project layout

- `overlay/` floating button service and boot receiver
- `apps/` app list, usage statistics, and grid screen
- `update/` update check, download, verification, installer, and background worker
- `MainActivity.kt` settings screen (permission, open apps, install a downloaded update)

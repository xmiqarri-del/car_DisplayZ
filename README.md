# Car_DisplayZ

Personal Android app for the car head unit (Android 11+).

## Features

- **Floating button** drawn above other apps. Drag it anywhere; tap it to open the app grid.
- **App grid** with the most used apps first, a "most used" row, search, and category chips. Press and hold an app to delete it. Some head units have no uninstaller, so deleting may not work there.
- **Bottom buttons** (Back, Home, Recent apps, memory cleanup) through an accessibility service. The service does not read screen content.
- **Memory cleanup** stops background processes of installed apps. System apps are never touched.
- **Starts after boot** once the overlay permission is granted, and again after an update. Android starts an app after boot only if it has been opened since it was installed or last force-stopped, so open the app once after installing it and after any force stop. A force stop cannot be blocked by the app itself.
- **Background updates**: checks GitHub every 6 hours. When a verified update is downloaded, a notification says it is ready. Nothing is installed until you tap install.
- Update downloads are HTTPS-only, limited to GitHub hosts, and checked against the SHA-256 hash in `update.json`.

## One-time setup

1. Allow **Display over other apps** for Car_DisplayZ. The settings screen has a row for this.
2. Turn on the bottom buttons: in the settings screen, under Navigation, switch on "Bottom buttons". Then enable **Car_DisplayZ navigation** in the Accessibility settings and accept the warning.
3. The GitHub repository `xmiqarri-del/Car_DisplayZ` must be **public**. The app downloads `update.json` and the APK without a token, so a private repository cannot deliver updates.
4. Add these repository secrets (Settings → Secrets and variables → Actions):
   - `KEYSTORE_B64`: base64 of the release keystore
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
5. Keep the release keystore safe. **Every update must be signed with the same key**, or Android refuses to install it over the current app.

## Publishing an update

1. Increase `versionCode` and change `versionName` in `app/build.gradle.kts`.
2. Merge into `main`. The workflow in `.github/workflows/release.yml` runs on pushes to `main`. If the release `v<versionName>` does not exist yet, it builds a signed APK, writes `update.json` with its SHA-256, and publishes the release.
3. The app picks the release up on its next background check. Install it from the notification or from the settings screen.

Branches other than `main` do not publish anything.

## Building locally

Open the folder in Android Studio (JDK 17 or the bundled JBR) and use **Build → Make Project** to check that the code compiles.

Do not use **Run** to test on the car when the release version is installed. Run installs a debug build signed with a different key, so Android refuses to install it over the release version. Uninstall the release version first. That also removes the overlay and accessibility permissions, which you then have to grant again.

## Project layout

- `overlay/` floating button service and boot receiver
- `nav/` accessibility service for the bottom buttons
- `apps/` app list, usage statistics, app grid screen, memory cleanup
- `update/` update check, download, verification, installer, and background worker
- `MainActivity.kt` settings screen

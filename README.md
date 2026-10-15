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
- **Rear screen** (display 5, the kids' screen): show the main screen there, and open apps on it. See the section below.

## Rear screen (display 5)

The head unit has a second screen in the back (display id 5, the one for the kids). Car_DisplayZ can show the main screen on it and open apps on it. Everything here is controlled from the app grid.

### Buttons on the app grid

A bar above the app list has three buttons:

- **Launch target** (`launch_on_rear` setting): when on, tapping an app opens it on the rear screen and the grid stays open on the main screen. When off, apps open on the main screen as before.
- **Rear screen** (`rear_display_id` setting): opens a list of the displays other than the main one. Pick the rear screen here. The button shows "no rear screen" until one is chosen, or "missing" if the chosen display is no longer connected.
- **Mirror**: starts or stops showing the main screen on the rear screen.

### How mirroring works

1. Tap **Mirror**. Android requires the user to allow screen capture, so the app shows the system consent prompt (`MirrorConsentActivity`). Android asks again every time the mirror starts; the app cannot skip it.
2. After consent, `MirrorService` starts as a foreground service with a persistent notification, and the notification stays while mirroring.
3. The service creates a `MediaProjection` and a virtual display with `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`. That flag copies the main screen into the virtual display.
4. The service shows a full-screen `Presentation` on the rear display with a `SurfaceView`. The virtual display draws into that surface.
5. Tap **Mirror** again, or stop the service from the notification, to stop mirroring. The service releases the projection, the virtual display and the presentation.

The mirror is a view only. Touching the mirror on the rear screen does not control the main screen.

### How opening apps on the rear screen works

- `AppRepository.launch(context, package, displayId)` builds the intent with `ActivityOptions.makeBasic().setLaunchDisplayId(displayId)`. The app opens on the chosen display.
- Apps on the rear screen are real apps on that display, so touch works on them directly. No extra helper or ADB touch injection is involved.

### Limitations

- The consent prompt appears every time mirroring starts.
- Mirroring stops if the rear display is disconnected, or when the service is stopped by Android.
- Only one rear display is supported, the one chosen in the Rear screen button.
- This feature was written without a local Android SDK. It was checked by reading the code and by the CI build, not on a car yet. Test it on the head unit before relying on it.

## One-time setup

1. Allow **Display over other apps** for Car_DisplayZ. The settings screen has a row for this.
2. Turn on the bottom buttons: in the settings screen, under Navigation, switch on "Bottom buttons". Then enable **Car_DisplayZ navigation** in the Accessibility settings and accept the warning.
3. The GitHub repository `xmiqarri-del/Car_DisplayZ` must be **public**. The app downloads `update.json` and the APK without a token, so a private repository cannot deliver updates.
4. Add these repository secrets (Settings → Secrets and variables → Actions):
   - `KEYSTORE_B64`: base64 of the release keystore
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
5. Keep the release keystore safe. **Every update must be signed with the same key**, or Android refuses to install it over the current app.
6. Optional: add the secret `INSTALLER_DISPATCH_TOKEN` so the web installer (`xmiqarri-del/Web-Installer`) switches to a new release right after it is published. Use a fine-grained token limited to that one repository with **Actions: Read and write**. Without it, the web installer still follows releases on its hourly check.

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
- `mirror/` rear screen mirroring: `MirrorService`, `MirrorConsentActivity`, `RearDisplay`
- `MainActivity.kt` settings screen

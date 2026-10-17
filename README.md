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

Touch on the rear screen controls the main screen. A tap or swipe on the mirror is sent to display 0 with `input -d 0`, through the app's own ADB connection (localhost:5555, the same one used for permissions). Swipes are approximated from the start and end points and the press duration, so this is good for taps, scrolling and simple gestures, not for games or precise drawing. The mirror uses the main screen's size, so positions map one to one. ADB must be listening, and the first connection needs the "Allow debugging" approval.

### How opening apps on the rear screen works

- `AppRepository.launch(context, package, displayId)` builds the intent with `ActivityOptions.makeBasic().setLaunchDisplayId(displayId)`. The app opens on the chosen display.
- Apps on the rear screen are real apps on that display, so touch works on them directly. No extra helper or ADB touch injection is involved.

### Limitations

- The consent prompt appears every time mirroring starts.
- Mirroring stops if the rear display is disconnected, or when the service is stopped by Android.
- Only one rear display is supported, the one chosen in the Rear screen button.
- Touch forwarding needs the ADB connection. If it is not running, taps on the mirror do nothing.
- This feature was written without a local Android SDK. It was checked by reading the code and by the CI build, not on a car yet. Test it on the head unit before relying on it.

## Locks (experimental, untested on the car)

Both features are off by default. Turn them on under **Settings → Locks**.

### Delayed lock

- The **Delayed lock** button appears on the app grid's bar when the setting is on.
- Pick 15, 30 or 60 seconds. A countdown dialog appears and is spoken aloud (the last three seconds are spoken too).
- Cancel stops it at any time before the doors lock.
- At zero the app runs the head unit's own sequence (`CarLock.lockDoors`): the hazards flash for about one second, the central lock is pulsed and the ambient light goes off at 1.1 s, the mirrors fold at 1.25 s, the screens go off at 1.4 s and the daytime running lights at 1.6 s. The same sequence runs after a lock from the key.
- Screens: `setDisplayPowerState(id, 0, 1)` (state 0 is off, 1 is on) for the SDK's own ids: 0 main, 1 cluster, 2 passenger, 3 climate, 4 roof. Only screens that are on are switched off. The power service connects in the background and ignores commands until then, so the app connects to it ahead of time, waits for it, and repeats the command up to 3 times for any screen still on.
- Daytime running lights: `setICC_DRLSW` (1 on, 2 off), only when the car reports them lit. This is a setting of the car, not a momentary state, so it is always restored (see below).
- Ambient light: `setICC_SET_TDL_SWITCH` (1 on, 2 off, then 0 after 250 ms), only when the car reports it on.
- After the exterior lights are checked, their states are logged under the `CarLock` tag (`Exterior lights after lock`).

#### Everything comes back when the doors open

- What the lock switched off is saved, so it survives a restart. About 5.5 s after the lock the app checks that the doors report locked. If they do not, it switches everything back on at once.
- While the doors are locked the app reads the lock state every second. When two reads in a row say unlocked, it switches the saved screens, daytime lights and ambient light back on.
- After every attempt it asks the car whether each one is back, and sends the commands again until it is, up to 8 attempts 1.5 s apart. The log shows each attempt (`Restore attempt`).
- If the head unit restarts while the car is locked, the restore resumes when the app starts and runs as soon as the doors report unlocked.

### Lock from key while the car is on

- When the car refuses a lock from the key while it is on, it sounds the horn and flashes the hazards ten times.
- If the car is on, in Park, stopped, all doors, trunk and hood are closed, and the doors are still unlocked, the app locks the doors (`FobLockAssist`).
- It checks for a 30-second cooldown after its own lock, and ignores hazards from its own lock.
- Flashes are counted from the two turn lamps (`getLHTURNLIGHTSTS`, `getRHTURNLIGHTSTS`), polled every 100 ms. The hazard button state (`getHAZARDLIGHTSW`) does not change when the car flashes after a refused lock, so it is only a fallback. On the car the flashing starts after the second press of the lock button. Every finished run is logged under the `FobLockAssist` tag with the reason it was ignored. Test it on the car before relying on it.

### How it reaches the car

Both features use the head unit's vehicle SDK (package `com.autolink.manager`), through reflection. On a head unit without that SDK, the app shows "not available" and sends nothing. The command names come from DisplayMirror 3.46.0.

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

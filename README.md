# Curtain Explorer (Zeekr 7X head unit)

A read-only scanner. It lists what the 7X head unit exposes to third-party apps
(car properties, lighting apps, system services, settings keys, car SDK classes)
so we can find out whether an App Lab app is allowed to drive the Stargate curtain.

It **changes nothing on the car**: no property writes, no broadcasts, no service
binding. It only reads lists.

## 1. Build the APK (your PC)

1. Install Android Studio (Ladybug or newer).
2. File → Open → choose the `CurtainExplorer` folder. Let Gradle sync finish.
3. Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.
4. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Command line alternative (Android SDK installed):

```
./gradlew assembleDebug
```

## 2. Install on the car

Install it through **App Lab** on the car screen, the same way you would add any
sideloaded companion app (e.g. via USB drive or the download method App Lab offers).

## 3. Run the scan

1. Car parked, open **Curtain Explorer**.
2. Allow any car permission prompts (all are read-only).
3. Tap **SCAN** and wait (up to a minute).
4. Get the report out, either way works:
   - **SAVE REPORT** → saved to the car's `Download` folder, or
   - Connect your phone to the car's Wi-Fi hotspot and open the address shown on screen,
     then copy all the text.
5. Send the report (or `curtain-report-*.txt`) back to Claude.

## What we're looking for

- Car properties marked `<<< LIGHT` or `<<< VENDOR` with write access (access=2 or 3)
- Lighting / curtain apps with `[EXPORTED]` services or providers we can legally call
- Lighting permissions with level `0` (normal) or `1` (dangerous), which a normal app can get

If none of these exist, the curtain isn't open to App Lab apps and we stop there.

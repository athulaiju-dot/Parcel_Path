# ParcelPath — Native Android project

Native Kotlin Android application for parcel-label OCR and delivery route planning.

## Features
- Select multiple parcel label screenshots with Android's system photo picker.
- On-device English OCR using Google ML Kit Text Recognition (bundled SDK; no paid AI API).
- Best-effort extraction of phone number, Indian PIN code, tracking reference and address text.
- Editable addresses for manual correction.
- Address lookup through Android Geocoder.
- Current GPS starting point, plus manual starting-address fallback.
- Road-network stop ordering through the public OSRM demo service, with nearest-neighbour fallback.
- One-stop-at-a-time Google Maps navigation hand-off.

## Build APK in Android Studio
1. Install Android Studio stable, JDK 17 and Android SDK Platform 35.
2. Open this `ParcelPath_Android` folder in Android Studio.
3. Allow Gradle sync to download the Android Gradle Plugin, Kotlin plugin, AndroidX and ML Kit.
4. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

This environment does not have Android SDK or Gradle installed, so this delivery is the Android Studio source project, not a compiled APK. The included GitHub Actions workflow can build the debug APK after you push the project to a GitHub repository and run the workflow.

## Use
1. Upload one or more parcel label screenshots.
2. Wait for OCR to finish.
3. Review and correct every delivery address and PIN.
4. Tap **Verify delivery addresses** and confirm each matched result is the intended building/locality.
5. Set the starting point with GPS or a manually geocoded address.
6. Tap **Optimize delivery route**.
7. Tap the navigation button to open the next stop in Google Maps. Return to ParcelPath and tap again for the next stop.

## Limitations and operational notes
- OCR is best-effort. Low-resolution, rotated, handwritten or non-English labels can be inaccurate; verify every address.
- Android Geocoder depends on the device's configured geocoding provider and internet availability.
- OSRM's public demo service is community infrastructure, rate-limited and not an SLA-backed production endpoint. Route ordering is not guaranteed globally optimal; fallback is based on straight-line distance.
- Google Maps should be installed for native navigation; otherwise a browser directions link is opened.
- Parcel data remains in memory for the current app session. There is no account, cloud sync, delivery tracking or delivery status feature.
- Address text is sent to the device's geocoding provider; coordinates are sent to the public OSRM demo endpoint.
- No Lovable or paid AI API is used.

## GitHub Actions
`.github/workflows/android.yml` builds `app-debug.apk` and uploads it as an artifact on push or manual workflow dispatch.

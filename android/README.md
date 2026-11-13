# Readout for Android

The Android port of Readout: Kotlin, Jetpack Compose, and Glance widgets. Read [ANDROID_PORT.md](../ANDROID_PORT.md) for the plan, how each iOS piece maps, and what still needs a device test.

## Build

You need JDK 17 or newer and the Android SDK (API 36). Android Studio provides both.

```bash
cd android
./gradlew :core:test          # logic tests, no Android SDK needed
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # draws layouts and screens into app/build/outputs/previews
./gradlew :app:installDebug   # onto a connected phone or emulator
```

The **Android Build** workflow runs the tests, builds a debug APK, and runs lint on every push that touches `android/`. It uploads the APK and the rendered previews as artifacts.

The preview images are drawn on the JVM with sample values, so a change to tile rendering can be looked at without a phone. They show the in-app previews; the widget itself is drawn by Glance from the same tile decisions.

### Direct Oura access

The app uses the same token broker and callback as the iOS app. Pass the broker's HTTPS URL at build time; never put an Oura client secret in the project.

```bash
./gradlew :app:assembleDebug -POURA_BROKER_URL=https://your-deployed-broker
```

With no broker URL, choose **Health Connect** as the Oura connection and enable Health Connect sharing in the Oura app.

## Layout

```text
core/   Pure Kotlin: models, cache, layouts, privacy, weather, calendar, Oura, connector manager, tile presenter
app/    Android: Health Connect, calendar, location, Oura sign-in, Compose screens, Glance widgets, WorkManager
```

`core` has no Android dependencies, so its tests run on any JVM. Tile layout decisions live in `core` (`TilePresenter`), and both the Glance widget and the in-app previews draw from them, so they cannot drift apart.

## Requirements on the phone

- Android 8.0 (API 26) or newer.
- Health Connect for health data: built in from Android 14, an app from Google Play before that.
- Widgets update about once a minute while the screen is on, and fetch new data about every 30 minutes when Android allows background work.

Icons are [Material Symbols](https://fonts.google.com/icons) by Google, used under the Apache License 2.0.

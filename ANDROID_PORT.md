# Readout on Android: port plan

This is the plan for bringing Readout to Android, and a record of how far it has been carried out. The Android app lives in [`android/`](android/); the iOS app is unchanged.

## Approach

Android gets a **native Kotlin app**, not a cross-platform rewrite. Readout is mostly platform integration: HealthKit, EventKit, WidgetKit, and Keychain on iOS each have a different Android counterpart, and a shared UI toolkit would cover none of them. What can be shared in spirit is the logic, so the port keeps the iOS structure file for file where it can.

The Android project has two modules:

| Module | What it holds | How it is checked |
| --- | --- | --- |
| `android/core` | Pure Kotlin, no Android dependencies: models, the cache, freshness, layouts, privacy projection, weather, calendar and Oura logic, the connector manager, and the tile presenter. A port of `Shared/` and `ConnectorManager`. | Over 100 JVM unit tests, ported from `Tests/` |
| `android/app` | Everything that touches Android: Health Connect, the calendar provider, location, Oura sign-in, encrypted token storage, Compose screens, Glance widgets, background refresh. | Built and linted by the **Android Build** workflow |

Keeping the logic in `core` means the rules that matter most (what a Discreet widget may show, when data counts as stale, how a refresh token is spent) are tested without a device or emulator.

## How each iOS piece maps

| iOS | Android | Notes |
| --- | --- | --- |
| SwiftUI | Jetpack Compose, Material 3 | Same three tabs: Today, Sources, Widgets |
| WidgetKit, Home Screen families | Jetpack Glance app widgets: small (2×2), medium (4×2), large (4×4) | Same tile grids: 2×4, 4×4, and 4×10 cells. Widgets are resizable; the grid stretches |
| Edit Widget (AppIntent) | A widget settings screen, opened from the launcher's widget edit button | Layout and privacy mode per widget |
| Timeline entries, one per minute | A non-wakeup alarm redraws widgets about once a minute while the screen is on | Ages and countdowns are computed when drawn, as on iOS |
| Widget timeline reload, `BGAppRefreshTask` | One WorkManager job about every 30 minutes | Skips sources fetched in the last 10 minutes; stays silent on failure |
| Timeline reload when a value expires | The minute redraw starts a one-off fetch for sources whose shown value has expired | The next event replaces one that ended; today's totals refetch after midnight |
| Refresh tile (`RefreshDataIntent`) | Glance action that starts a one-off refresh job | Reports failures, like the iOS button |
| HealthKit | Health Connect | Steps, active energy, exercise time, distance, last workout, heart rate, resting heart rate, HRV (RMSSD) |
| Oura through Apple Health | Oura through Health Connect | Sleep sessions written by the Oura app |
| Oura account (OAuth, PKCE, broker) | Same flow and the **same broker and callback** (`com.jonisuominen.readout://oauth/oura`) | Sign-in opens in a browser tab |
| Keychain | AES-GCM with a key in the Android Keystore | Excluded from backups |
| EventKit | `CalendarContract` (system calendar provider) | Reads whatever accounts sync to the phone |
| Core Location | Platform `LocationManager`, coarse permission only | No Google Play services dependency |
| App Group `UserDefaults` | One private `SharedPreferences` file | App, widgets, and background work share a process |
| Open-Meteo client | Same endpoints and parameters | |

## What is different on Android, on purpose

- **One process.** The iOS widget is a separate process that must never spend the single-use Oura refresh token. On Android the app, widgets, and background work share one process and one Oura connector, which serializes token renewal. So Oura keeps refreshing in the background after the access token expires.
- **Background health reads need their own grant.** Health Connect only allows reads without the app on screen if the person grants "background access", where the phone offers it. Without it a background or widget refresh leaves health values as they were, with their real age, and does not show a failure. Opening the app refreshes them.
- **No Lock Screen widgets.** Android phones have no equivalent of the accessory families, so `AccessoryWidgets` is not ported. The Discreet privacy mode still exists per widget.
- **No extra large layout.** The iPad-only 8-column canvas is left out for now.
- **No walking heart rate.** Health Connect has no such record type. HRV is RMSSD rather than SDNN, since that is what Health Connect stores.
- **Revoked health access clears health values.** Health Connect reports which grants remain, which HealthKit does not. When none remain, the cached health values are removed instead of lingering with an error.
- **Forecast times use the place's clock.** Sunrise, sunset, and the UV and rain hours are shown in the chosen city's time zone rather than the phone's.
- **Health tiles open Readout.** Health Connect is a permissions hub rather than a health app, so a tapped health tile shows Today. Calendar tiles still open the calendar on the event's day.
- **System appearance follows the phone's theme** with fixed light and dark surfaces. Material You wallpaper colors are used in the app, not in widgets, so tile contrast stays measured.

## Phases

1. **Core logic. Done.** `android/core` with its tests.
2. **App and widgets. Written; needs a device pass.** Connectors, the three tabs, the layout editor with drag, snap, and resize, widget settings, three Glance widgets, background refresh. It compiles and passes lint in CI. Nobody has run it on a phone yet.
3. **Device acceptance. To do, needs you.** On a real phone: each permission prompt and its denial, Health Connect with and without background access, calendar selection and title privacy, widget add, resize, reconfigure, and refresh, the minute redraw, dark theme, large font sizes, TalkBack through the layout editor, and the Oura sign-in round trip against your deployed broker.
4. **Release. To do, needs you.** See below.

## What needs the owner

- **A phone test.** This port was written without an emulator or device. Expect layout polish: Glance text cannot shrink to fit like SwiftUI's `minimumScaleFactor`, so tile font sizes come from an estimate in `TextFit`.
- **Oura.** Nothing new to register: the Android app uses the callback already registered for iOS. Pass the broker URL at build time: `./gradlew :app:assembleRelease -POURA_BROKER_URL=https://your-broker`. Without it, Oura works through Health Connect only.
- **Signing and Play Console.** A release keystore, an app listing, and the **Health Connect declaration form**: Google Play reviews every health permission an app requests, and the listing needs a public privacy policy URL. `PRIVACY.md` describes iOS today and needs an Android section before submission.
- **Decisions left open:** whether to add a tablet (extra large) layout, a single-metric widget in place of the Lock Screen ones, and translations.

## Dependency versions

The build pins Gradle 8.14.3, the Android Gradle plugin 8.13, Kotlin 2.2.21, and AndroidX libraries from late 2025. These are a known-compatible set chosen because the port was compiled only in CI; newer releases exist and can be adopted once the app has been run on a device.

## Build

See [`android/README.md`](android/README.md). In short: open `android/` in Android Studio, or run `./gradlew :core:test :app:assembleDebug` there. Every push that touches `android/` runs the **Android Build** workflow, which uploads a debug APK as an artifact.

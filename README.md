# Readout

Readout is an iOS 17 SwiftUI app that packs data from several sources into one compact Home Screen widget, so more apps fit on your first page. It combines weather, UV index and air quality, Apple Health steps, activity and heart data (heart rate, resting or walking heart rate, or HRV, chosen per tile), Oura readiness and sleep, and your next calendar events.

## Features

- **Weather:** current and feels-like temperature, UV index with its WHO level and today's peak, wind, humidity, the next sunrise or sunset, air quality, today's rain with the most likely hour, and tomorrow's average temperature, from the free [Open-Meteo](https://open-meteo.com/) API (data licensed CC BY 4.0, credited in the app). Use your approximate location or choose a city; coordinates are rounded to about 1 km. Choose °C or °F, mm or inches, and m/s, km/h, or mph in Sources, or let them follow your region.
- **Tile layouts:** design small, medium, and large widget layouts on a grid in the **Widgets** tab. Drag a tile to move it and drag its corner to resize it. Large tiles show a label, value, and detail; one-row tiles show an icon and value; **Value only** drops the icon. Each Home Screen widget picks its layout in Edit Widget.
- **Next event:** start time and title on one line ("14:30 Design sync · in 25m"), with a countdown within the hour and "Now" while it runs.
- **Agenda:** a taller tile that lists one event per row, starting with today's all-day events.
- **Tap to open:** in medium and larger widgets, a calendar tile opens Calendar on the event's day, a health tile opens Apple Health, and the rest open Readout. Small widgets open Readout.
- **Refresh tile:** a one-cell button that refreshes weather, Apple Health, Calendar, and Oura from the widget and shows the age of the oldest shown value in whole minutes. It turns orange when data is stale or a refresh failed.
- **Oura account:** OAuth sign-in with PKCE, direct Oura API reads, token refresh, and disconnect. Account tokens stay in Keychain; the Oura application secret belongs in the separately deployed token broker. The widget refreshes Oura with the saved access token and never renews it, so open the app when Oura stops updating.
- **Apple Health fallback:** select Apple Health for Oura sleep data shared by the Oura app. This mode needs no Oura sign-in or backend; proprietary Readiness scores require Oura account access.
- **Apple Health and Calendar:** read permitted data through HealthKit and EventKit, including active energy, exercise minutes, distance, and your latest workout. Select an explicit list of calendars; titles stay hidden unless enabled. Google, Outlook, Exchange, and other calendars work when the account is added in iOS Settings.
- **iPad:** an extra large widget with its own eight-column layout.
- **Lock Screen:** circular, rectangular, and inline widgets with their own metric. They default to Discreet, hiding health values and event titles, including accessibility labels and gauges. Weather is never hidden.
- **No branding in widgets:** widgets show only your data, on the system widget background.
- **Honest freshness:** widgets show cached snapshots with their real fetch age. Old data remains visibly stale; a timeline reload alone does not mark a source as refreshed.
- **Refresh and privacy:** fetch on launch, returning to the foreground, manual refresh, widget reloads, and best-effort background refresh. Disconnect or delete local data from Sources, with separate results for local Oura credential removal and remote revocation. Failed credential cleanup remains visible after relaunch and offers a retry.

WidgetKit and iOS control background execution and widget reload timing. Background refresh is not a promise of continuous or exact-interval updates. Empty, denied-permission, and unavailable states do not substitute sample values for live data.

## Build

On a Mac with Xcode and an iOS simulator runtime installed:

```bash
brew install xcodegen
xcodegen generate
open Readout.xcodeproj
```

Choose the **Readout** scheme and run on an iPhone simulator. The GitHub Actions workflow generates the project and runs the simulator checks. See [DEPLOYMENT.md](DEPLOYMENT.md) for signing, Oura setup, and the device release checklist.

To build and install on your own connected iPhone, add your Apple account in Xcode, then run:

```bash
Scripts/install-on-iphone.sh --list
Scripts/install-on-iphone.sh --team YOUR_TEAM_ID --device YOUR_IPHONE_UDID
```

The script signs with your Apple Developer team, installs the app and widget, and launches Readout. It requires a Mac and the matching App Group/HealthKit capabilities. Add `--broker-url https://your-deployed-broker` for direct Oura access.

The **iPhone Build** workflow also produces a downloadable **unsigned arm64 `.xcarchive`** on pushes to `main` and by manual dispatch. It includes the widget, build metadata, and a checksum. This artifact requires Apple signing before installation; it is not an installable IPA or a TestFlight release. Download it from the workflow run's Artifacts section and read its `INSTALLING.md`.

The configured identifiers are:

| Component | Identifier |
| --- | --- |
| App | `com.jonisuominen.readout` |
| Widget | `com.jonisuominen.readout.widget` |
| Shared App Group | `group.com.jonisuominen.readout` |

Register these identifiers and the App Group with the signing Apple Developer team. Contributors must use identifiers they control and update both entitlements and the shared store consistently.

## Android

An Android port lives in [`android/`](android/): a Kotlin app with Jetpack Compose screens and Glance home screen widgets, reading Health Connect and the phone's calendars. It builds in CI but has not been tested on a device yet. See [ANDROID_PORT.md](ANDROID_PORT.md) for the plan and its status, and [android/README.md](android/README.md) to build it.

## Enable direct Oura access

The integration code is included; a live Oura connection still requires owner setup:

1. Register an Oura developer application and its callback URI.
2. Configure and deploy the HTTPS token broker following [Backend/README.md](Backend/README.md). Keep the Oura client secret in deployment secrets.
3. Set the Xcode build setting **`OURA_BROKER_URL`** to the broker's HTTPS base URL. It populates **`OuraBrokerURL`** in the app's Info.plist. Regenerate the project after changing `project.yml`.
4. Build the app, select **Oura account** in Sources, and connect. Verify authorization, refresh, cancellation, and disconnect on an iPhone.

The broker publishes public OAuth configuration at `GET /config` and exchanges codes and refresh tokens. It has no user database and does not intentionally persist user tokens. The app fetches Oura metrics directly. With no broker configured, use **Apple Health** mode and enable Oura's Apple Health sharing.

## Repository

```text
Readout/            SwiftUI app and source connectors
ReadoutWidget/      WidgetKit extension
Shared/               Dashboard models, configuration, and local persistence
Backend/              Oura OAuth token broker and deployment instructions
android/              Android port: Kotlin core logic, Compose app, Glance widgets
project.yml           XcodeGen project definition
.github/workflows/    Simulator validation, unsigned device archive, TestFlight upload, Android build
Scripts/             iPhone archive packaging and local signed installation
```

Read [LOCAL_FIRST.md](LOCAL_FIRST.md) for the storage design [PRIVACY.md](PRIVACY.md) for data handling, and [TERMS.md](TERMS.md) for the terms of use. Readout is available under the [MIT license](LICENSE). Release signing, a deployed Oura broker, App Store Connect setup, and physical-device acceptance testing remain the app owner's responsibility.

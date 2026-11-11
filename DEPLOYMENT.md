# Build, connect Oura, and release Readout

The app and widget source are included. A signed release still needs an Apple Developer team, device testing, App Store Connect setup, and a deployed Oura broker if you want direct Oura account access. Do not publish a build with an unconfigured or unverified Oura account mode.

## Run in the simulator

1. Install Xcode on a Mac, launch it once, and install an iOS simulator runtime in **Xcode → Settings → Platforms**.
2. Install XcodeGen and open the generated project:

   ```bash
   brew install xcodegen
   cd /path/to/readout
   xcodegen generate
   open WeatherUV.xcodeproj
   ```

3. Select the **WeatherUV** scheme and an installed iPhone simulator. Press **⌘R**.
4. Open Today, Sources, and Widgets. Test empty states and saved widget choices. HealthKit availability and data vary in the simulator; final health authorization testing needs an iPhone.
5. Launch the app once, return to the Home Screen, long-press an empty area, and add the small, medium, and large Dashboard widgets. Design layouts in the Widgets tab and pick one per widget in Edit Widget. Customize the Lock Screen to add circular, rectangular, and inline widgets.

`project.yml` is the project source of truth. Regenerate after editing it; generated Xcode settings may be overwritten. Simulator builds do not require a signing team. The repository's **iOS Build** GitHub Actions workflow validates the app and widget; inspect that run's actual result before treating a commit as verified. CI compilation does not validate physical-device permissions, OAuth configuration, or delivery through TestFlight.

## Download the iPhone device build

The **iPhone Build** workflow archives the app and widget for a real arm64 iPhone on GitHub's macOS runner. It runs on pushes to `main` and from **Actions → iPhone Build → Run workflow**. A manual run can include an optional public HTTPS Oura broker URL; the repository variable `OURA_BROKER_URL` is used if that input is empty. The workflow does not need Apple signing secrets.

After the workflow succeeds, download `readout-iphone-unsigned-<commit SHA>` from its **Artifacts** section. It contains `Readout.xcarchive.zip`, `BUILD_INFO.json`, `SHA256SUMS`, and `INSTALLING.md`. Verify it on a Mac:

```bash
shasum -a 256 -c SHA256SUMS
unzip Readout.xcarchive.zip
open Readout.xcarchive
```

The archive opens in Xcode Organizer for inspection. It is **unsigned and cannot be installed on your iPhone** until Apple signing and provisioning are supplied. AirDrop and the iPhone Files app do not install this artifact. For a reliable local installation, check out the source commit listed in `BUILD_INFO.json` and use the signed build script below. If you already have a Mac, `Scripts/archive-iphone.sh` produces the same unsigned package under `build/iphone-artifacts`.

## Configure the Oura account integration

Apple Health mode can read Oura sleep shared by the Oura app without a broker. For Oura account mode and Readiness scores:

1. Register an Oura developer application and register this callback URI exactly:

   ```text
   com.jonisuominen.readout://oauth/oura
   ```

   If you fork the app and change the callback scheme, update the iOS URL scheme, `OuraConfiguration.callbackURL`, broker callback configuration, and Oura registration together. Oura may require application approval for access beyond its developer testing limit; check the current provider requirements before a public launch.

2. Follow [Backend/README.md](Backend/README.md) to deploy the supplied Cloudflare Worker over HTTPS. Set **`OURA_CLIENT_ID`** and **`OURA_CLIENT_SECRET`** as Worker secrets. Never put the client secret in an Xcode build setting or app file.
3. Configure the app's **`OURA_BROKER_URL`** build setting with the broker's HTTPS base URL. It populates **`OuraBrokerURL`** in Info.plist. Regenerate the project if you change `project.yml`.
4. Verify `GET /config` returns the registered public client ID and callback URI. Review the broker's rate limiting, access logs, and retention; do not log token request bodies or responses.
5. Run the app on an iPhone, select **Oura account** in Sources, and connect. Confirm real sleep and Readiness data appears. The app requests Oura's daily-data scope.

No live broker URL or account credentials are included in this repository. The app stores account tokens in Keychain and reads Oura metrics directly; the broker performs OAuth exchanges and refreshes without intentionally persisting tokens.

## Sign and install on an iPhone

The configured release identifiers are:

- App: `com.jonisuominen.readout`
- Widget: `com.jonisuominen.readout.widget`
- App Group: `group.com.jonisuominen.readout`

Register them under the Apple Developer team that will distribute the app. Enable the same App Group and HealthKit for both targets; the widget reads Health data when it refreshes. Contributors must replace the identifiers consistently in `project.yml`, both entitlement files, and the shared store.

1. In **Xcode → Settings → Accounts**, add the developer Apple ID.
2. Select each target's **Signing & Capabilities**, choose the Team, and enable automatic signing. Confirm matching App Group entitlements.
3. Connect and trust the iPhone, enable Developer Mode if requested, select it as the run destination, and press **⌘R**.
4. Launch Readout once before adding widgets. Add your Google account to iOS Calendar if you want its events to appear; Readout uses EventKit rather than Google sign-in.

### Build and install from Terminal

After adding the Apple account in Xcode and connecting the phone, run:

```bash
Scripts/install-on-iphone.sh --list
Scripts/install-on-iphone.sh --team YOUR_TEAM_ID --device YOUR_IPHONE_UDID
```

Use the physical iPhone ID shown under **Available destinations**, not a simulator ID. Find your ten-character Team ID in the Apple Developer account membership details. The script regenerates the Xcode project, builds with automatic signing, lets Xcode register the device if needed, verifies the app signature, installs the app with its embedded widget, and launches it. Signing credentials remain managed by Xcode on your Mac.

For direct Oura access, append `--broker-url https://your-deployed-broker` or set the public `OURA_BROKER_URL` environment variable before running the script. Without broker configuration, select Apple Health mode. If provisioning fails, open Xcode and resolve the Team/App Group/HealthKit configuration for both targets before retrying.

A free Personal Team may have capability or provisioning limits. App Groups, release signing, and TestFlight should be verified with the distributing Apple Developer Program team.

## Physical-device acceptance checklist

These checks require execution on a real iPhone; their presence here does not mean they have passed.

- [ ] Fresh install shows honest empty states until sources are connected.
- [ ] HealthKit permission granted, denied, and revoked states are understandable; steps and resting heart rate agree with available Health data.
- [ ] Apple Health mode displays Oura-origin sleep when sharing is enabled, and does not invent a Readiness score.
- [ ] Oura account sign-in, cancellation, rejection, relaunch, expired access-token refresh, and revoked access behave correctly. Confirm the broker has no token/body logs.
- [ ] Calendar permission denial, no upcoming events, changed events, and a Google calendar configured in iOS behave correctly.
- [ ] Small, medium, and large Home Screen widgets plus all three Lock Screen families render correctly. Edit a layout and confirm installed widgets update; pick different layouts for two widgets of the same size.
- [ ] The widget refresh tile updates weather, Health, and Calendar values without opening the app, and its minute age resets.
- [ ] Weather works with location allowed, with location denied plus a chosen city, and after switching between them.
- [ ] Step and sleep gauges use correct units; empty values do not produce misleading progress.
- [ ] Manual refresh fetches new source data. Airplane Mode retains last known data with its original source timestamp and visible stale status after an hour.
- [ ] Relaunch, reboot, locked-device conditions, disabled Background App Refresh, and Low Power Mode do not falsely mark old data fresh. Background scheduling may be delayed by iOS.
- [ ] Disconnect removes that source's local data. Delete local data clears snapshots, preferences, connections, and Oura credentials. Verify remote Oura revocation, or remove access from the Oura account if offline.
- [ ] VoiceOver, large accessibility text, Light/Dark Mode, and widget legibility pass on the supported screen sizes.

## TestFlight and App Store release

1. Complete the simulator checks, broker tests, and physical-device checklist. Resolve any outstanding CI failure.
2. Create an App Store Connect app record with the final bundle ID. Confirm the production broker configuration is in the archive.
3. Provide screenshots, app metadata, review instructions, and the [public privacy policy URL](https://github.com/JoniSuominen/readout/blob/main/PRIVACY.md). Answer App Store privacy questions based on the deployed app and broker, including the hosting platform's behavior.
4. Select **Any iOS Device (arm64)**, then **Product → Archive**. In Organizer choose **Distribute App → App Store Connect → Upload**.
5. After processing, add TestFlight internal testers. External testers require Apple's beta review. Submit for App Store review only after the release checks pass.

### Upload from GitHub Actions, without a Mac

The **TestFlight** workflow archives, signs, and uploads a build when you run it by hand from the Actions tab. It has not been run against a real team yet, so treat the first run as a test. It needs a paid Apple Developer Program membership; free provisioning cannot upload to TestFlight.

1. In [App Store Connect → Users and Access → Integrations](https://appstoreconnect.apple.com/access/integrations/api), create a team API key with the **Admin** role, which automatic signing needs to create certificates and profiles. Download the `.p8` file once.
2. Create the App Store Connect app record for `com.jonisuominen.readout`.
3. In the GitHub repository, open **Settings → Secrets and variables → Actions** and add `APPLE_TEAM_ID`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, and `ASC_KEY_P8` (the whole text of the `.p8` file).
4. Run **TestFlight** from the Actions tab. The build number is the workflow run number. Processing takes a few minutes before the build shows under TestFlight.

TestFlight builds last 90 days, so the weekly re-signing of a free-provisioned install is no longer needed.

The other workflows build simulator checks and an unsigned device archive, and contain no certificates, provisioning profiles, or App Store Connect credentials. Keep these secrets out of Git; the TestFlight workflow reads them only from repository secrets and deletes the key file when it finishes.

## Troubleshooting

**Widget shows old data:** check that both targets and the shared store use the same registered App Group. Launch the app and fetch source data. WidgetKit decides when to reload; old timestamps must remain old until a source fetch succeeds.

**Oura account cannot connect:** confirm the broker HTTPS URL, `/config`, registered callback, Worker secrets, and provider app authorization. Never paste token responses into GitHub issues. Apple Health mode remains available without a broker.

**Missing project or module:** run `xcodegen generate` and open `WeatherUV.xcodeproj`.

**Signing failure:** check that the team owns both bundle IDs and the App Group and has enabled the necessary capabilities, then regenerate the project.

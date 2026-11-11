# Readout privacy policy

Updated October 22, 2026.

Readout displays a personal dashboard and iOS widgets using sources you choose to connect. This policy describes the open-source app and the supplied Oura token broker. A distributor who changes the code or hosting must review and update this policy for their deployment.

## Data used by the app

With your permission, Readout reads:

- **Apple Health:** today's steps, active energy, exercise minutes, walking and running distance, your latest workout, heart rate, resting and walking heart rate, heart rate variability, and, in Apple Health mode, sleep samples written by Oura. Readiness scores are not available through this fallback.
- **Calendar:** availability and timing for your next events, including today's all-day events, from calendars you explicitly select on your iPhone. No calendars are selected automatically. Event titles are cached and displayed only if you enable them in Calendar privacy settings.
- **Oura account:** readiness and sleep data from Oura's API after you authorize account access.
- **Weather:** a forecast and air quality for your approximate location or a city you choose.

The latest display values, source state, freshness times, calendar selection identifiers, widget layouts, and the weather location (rounded to about 1 km) are stored in the app's local shared App Group so widgets can display them. Oura account tokens are stored in iOS Keychain, not in the shared widget snapshot. The Keychain entry is shared only between the app and its own widget. Detailed health and calendar histories are not copied into a Readout user database.

Each installed widget has separate settings managed by iOS. Widgets may refresh weather, Apple Health, Calendar, and Oura values themselves, during iOS timeline reloads or when you tap their refresh tile, under the same permissions and calendar title settings. For an Oura account the widget reads with the access token the app saved; only the app signs in, renews, or removes credentials, so Oura stops refreshing in the widget once that token expires and resumes after you open the app. Tapping a tile opens Readout, which hands calendar tiles to Calendar and health tiles to Apple Health; the link names only the tile, never its value. Lock Screen widgets default to Discreet, concealing health measurements and event titles from text, gauges, and accessibility labels. Show values marks private values for iOS privacy redaction; values may be visible to anyone who can see your device when iOS permits their display. Calendar titles additionally require the app's title opt-in. Widget refresh timing is controlled by iOS, so already displayed snapshots may remain briefly after settings change or data deletion.

Readout does not include advertising, analytics, tracking, or a third-party crash-reporting SDK. It does not sell personal data.

## Network access and the Oura broker

Weather uses the free [Open-Meteo](https://open-meteo.com/) forecast API, which needs no account. Each forecast request sends a coordinate rounded to two decimal places (about 1 km); city search sends the text you type to Open-Meteo's geocoding API. Location access is requested only while the app is in use, and you can choose a city instead. Open-Meteo's [terms](https://open-meteo.com/en/terms) and privacy practices apply to those requests.

Apple Health and Calendar access uses Apple's on-device frameworks. Those providers and their apps may perform their own synchronization; Readout does not control it.

In Oura account mode, the app communicates with Oura for sign-in and metric reads. It also communicates over HTTPS with a separately deployed OAuth token broker for authorization-code exchange, token refresh, and best-effort token revocation. The broker processes authorization codes, PKCE verifiers, and account tokens as needed to complete these operations. It holds the Oura application's client secret on the server, has no user database, and does not intentionally persist account tokens or health metrics.

The supplied broker does not intentionally log OAuth request or response bodies. Hosting platforms may still process connection information such as IP addresses, request timestamps, and operational logs. The operator must minimize logs, disable body/token logging, and configure suitable retention. Deployment and hosting are owner setup steps; this repository does not establish that a broker is already live.

Oura and Apple have separate privacy policies governing their services: [Oura privacy policy](https://ouraring.com/privacy-policy) and [Apple privacy policy](https://www.apple.com/legal/privacy/). Consult your calendar provider's policy as well.

## Your choices and deletion

Connecting a source is optional. You can use Apple Health mode instead of direct Oura account access. You can disconnect individual sources or use **Delete local data** in Sources to clear Readout's cached values, source state, calendar selections, widget layouts, and weather location, and attempt removal of account credentials. Installed widget settings remain managed by iOS. Deletion does not remove records from Apple Health, your calendars, or your Oura account.

Oura disconnect reports local credential removal and remote revocation separately. A network or provider failure may prevent remote revocation even though local credentials are removed; Keychain removal can also fail. Readout stops using the connection immediately and offers a retry when local removal fails. A nonsecret cleanup status is retained in app preferences after deletion and relaunch so an unresolved failure remains visible; it contains no tokens or account identifiers. Review authorized applications in your Oura account to revoke access there. Manage Apple Health and Calendar permissions in iOS Settings. iOS device backup behavior depends on system settings.

## Contact and changes

For privacy questions, use [the repository's GitHub Issues](https://github.com/JoniSuominen/readout/issues). Do not post health details, calendar contents, passwords, or tokens in a public issue.

This policy will be updated when Readout's data handling changes. Its public URL is [this privacy policy on GitHub](https://github.com/JoniSuominen/readout/blob/main/PRIVACY.md).

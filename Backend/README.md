# Readout Oura OAuth broker

This Cloudflare Worker keeps the Oura client secret off the iPhone while supporting direct Oura access with rotating refresh tokens. It has no user database, token storage, or account system. Access and refresh tokens belong in the app's Keychain; data requests go directly from the app to Oura's V2 API.

## Verified Oura requirements

Checked against the official documentation on October 10, 2026:

- [OAuth authentication](https://cloud.ouraring.com/docs/authentication) explicitly supports **S256 PKCE**: authorization sends `code_challenge` and `code_challenge_method=S256`; token exchange sends the original `code_verifier`. Authorization code flow requires the client secret or HTTP Basic client authentication.
- Authorization endpoint: `https://cloud.ouraring.com/oauth/authorize`. Token endpoint: `https://api.ouraring.com/oauth/token` with a UTF-8 `application/x-www-form-urlencoded` POST body.
- Request only the `daily` scope for daily sleep, activity, and readiness. Users can decline scopes. Do not rely on the deprecated callback `scope` parameter.
- Redirect URIs must exactly match the registered allowlist. This app uses `com.jonisuominen.readout://oauth/oura`. The public docs do not prohibit custom schemes; the registration and an actual device authorization must be verified before release.
- Refresh tokens are **single use**. Persist the new pair atomically before using the refreshed access token; do not issue concurrent refreshes or blindly retry a refresh after an ambiguous network failure.
- Oura documents access-token revocation as `https://api.ouraring.com/oauth/revoke?access_token=...`. The broker requires a POST body from the app and uses that documented URL only for its upstream request.
- [V2 API documentation](https://cloud.ouraring.com/v2/docs) says personal access tokens were deprecated in December 2025 and are unavailable. Registered applications are limited to **10 users until approved by Oura**. A user's subscription and consent can affect API access.

PKCE protects the native custom-scheme callback against code interception: an app intercepting a code cannot redeem it without the original verifier. The app must generate a fresh random verifier and state for each attempt, verify the callback state and exact URL, and discard them afterwards. Oura validates the challenge and consumes codes. No broker handoff state or persistent storage is necessary with the documented S256 support. The tests simulate that provider contract; live Oura validation still requires your registered client.

## Deploy

You need a Cloudflare account and an Oura developer application. Nothing in this directory has been deployed, and no client credentials are included.

1. Register an application at [Oura API Applications](https://cloud.ouraring.com/oauth/applications), enable the `daily` scope, and register exactly `com.jonisuominen.readout://oauth/oura` as its callback. For the form's privacy policy and terms of service fields, use the public URLs of [PRIVACY.md](https://github.com/JoniSuominen/readout/blob/main/PRIVACY.md) and [TERMS.md](https://github.com/JoniSuominen/readout/blob/main/TERMS.md). Keep the client secret private.
2. Install Node.js 22 or newer, then run in this directory:

   ```sh
   npm ci
   npm test
   npm run check
   npx wrangler login
   npx wrangler secret put OURA_CLIENT_ID
   npx wrangler secret put OURA_CLIENT_SECRET
   npm run deploy
   ```

   Wrangler prompts privately for each value. Never pass the client secret as a CLI argument, commit it, or add it to Xcode settings. The fixed callback allowlist is in `src/worker.js`, not controlled by a request.

3. Set the app's `OURA_BROKER_URL` build setting (which populates `OuraBrokerURL` in Info.plist) to the actual HTTPS Worker origin, for example the origin Wrangler reports after deployment. `GET /config` supplies the public Oura client ID and callback. Leave the setting empty when direct Oura is unavailable; Apple Health imports can still work.
4. Confirm `GET /config` returns your public client ID and the callback, then test on an iPhone: authorization, daily metrics, disconnect/revocation, reconnect, denied consent, refresh, and a stale refresh token. Do not enter an access or refresh token into a URL or command line while testing.
5. Request Oura approval before serving more than ten users. Publish the app privacy policy and support details required for approval.

`wrangler.jsonc` includes a rate-limit binding. Its namespace ID must be unique in your Cloudflare account if other Workers already use that ID. It allows 30 OAuth mutations per minute per edge IP, per Cloudflare location. Shared mobile IPs can hit this cap; adjust it for your deployment. Cloudflare's counters are approximate abuse protection, not a global quota. The handler fails closed if the binding is missing. [Cloudflare rate-limit documentation](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/) describes these limits.

For local development, copy `.dev.vars.example` to ignored `.dev.vars` and enter your own credentials privately. `npm run dev` uses local HTTPS. Never disable iOS transport security to point a release build at an HTTP broker. `npm test` requires no secrets or network access.

## App contract

All endpoints require HTTPS. Do not append query parameters. POST endpoints accept only `application/json` (optionally `charset=utf-8`), with a body of at most 8 KiB and the exact fields below. There is no browser CORS allowlist: this is a native client API.

| Endpoint | Request | Success |
| --- | --- | --- |
| `GET /health` | None | `200 {"status":"ok"}`; liveness only |
| `GET /config` | None | `200 {"client_id":"…","redirect_uri":"com.jonisuominen.readout://oauth/oura"}` |
| `POST /oauth/exchange` | `{"code":"…","redirect_uri":"com.jonisuominen.readout://oauth/oura","code_verifier":"…"}` | Token envelope below |
| `POST /oauth/refresh` | `{"refresh_token":"…"}` | Token envelope below |
| `POST /oauth/revoke` | `{"access_token":"…"}` | `204`, empty body |

The app authorizes through `ASWebAuthenticationSession` directly at Oura with `response_type=code`, the public client ID, the exact redirect URI, `scope=daily`, fresh random `state`, and S256 PKCE. The verifier must be 43–128 RFC 7636 unreserved ASCII characters; it is required by the broker.

Successful exchange and refresh replies are `200` with only:

```json
{
  "access_token": "opaque access token",
  "refresh_token": "new opaque single-use refresh token",
  "expires_in": 86400,
  "token_type": "Bearer",
  "scope": "daily"
}
```

`scope` is optional. `expires_in` comes from Oura, not a fixed lifetime. Store the rotated token pair together. On `400 {"error":"invalid_grant"}`, clear the invalid credentials and reconnect. On a timeout during refresh, reconnect if retrying with the prior refresh token is rejected: the provider may have consumed it before the response was lost.

Failures contain only `{"error":"<allowlisted name>"}`: invalid request (`400`/`415`), expired/replayed credentials (`400 invalid_grant`), rate limiting (`429 temporarily_unavailable`, possibly `Retry-After` in seconds), upstream problems (`502 server_error`/`503 temporarily_unavailable`), or incomplete deployment (`503 service_unavailable`). The broker does not reflect Oura error descriptions or exception text. Other routes/methods return `404`/`405`.

## Privacy and operations

The Worker processes authorization codes, PKCE verifiers, access/refresh tokens, and edge IP addresses transiently. It forwards credentials only to fixed HTTPS Oura endpoints, blocks redirects, applies a 10-second upstream deadline, validates request and response size/schema, and sets `Cache-Control: no-store` on every reply. Tokens and the client secret are never logged, written to storage, or included in error/config replies. Successful token replies necessarily contain the user's token pair over HTTPS.

Worker observability and preview URLs are disabled. Do not add body logging, tracing of upstream revocation URLs, or debugging that records tokens. Cloudflare and Oura still process network and operational metadata under their policies; this code cannot guarantee that a hosting provider retains no metadata. Rate-limit counters contain edge IP keys, not Oura user IDs or tokens. If a user cannot revoke because Oura is unavailable, the app should explain that local disconnection does not prove remote revocation and link to Oura's connected-app controls.

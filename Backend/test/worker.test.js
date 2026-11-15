import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createHandler, REDIRECT_URI } from "../src/worker.js";

const verifier = "a".repeat(64);
const token = { access_token: "access.value", refresh_token: "refresh.value", token_type: "bearer", expires_in: 86400 };
const env = {
  OURA_CLIENT_ID: "public-client-id",
  OURA_CLIENT_SECRET: "server-only-secret",
  OAUTH_RATE_LIMIT: { limit: async () => ({ success: true }) },
};
const exchange = { code: "authorization-code", redirect_uri: REDIRECT_URI, code_verifier: verifier };

function request(path, body, options = {}) {
  return new Request(`https://broker.example${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.1", ...options.headers },
    body: typeof body === "string" ? body : JSON.stringify(body),
    ...options,
  });
}

function jsonResponse(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

test("public config exposes only the client ID and fixed redirect, with no cache", async () => {
  const handle = createHandler(() => { throw new Error("must not fetch"); });
  const result = await handle(new Request("https://broker.example/config"), env);
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), { client_id: env.OURA_CLIENT_ID, redirect_uri: REDIRECT_URI });
  assert.equal(result.headers.get("Cache-Control"), "no-store");
  assert.equal(result.headers.get("Access-Control-Allow-Origin"), null);
  const unconfigured = await handle(new Request("https://broker.example/config"), { ...env, OURA_CLIENT_SECRET: "" });
  assert.equal(unconfigured.status, 503);
});

test("code exchange sends the verifier and server secret only to the fixed upstream", async () => {
  const handle = createHandler(async (url, options) => {
    assert.equal(url, "https://api.ouraring.com/oauth/token");
    assert.equal(options.method, "POST");
    assert.equal(options.redirect, "manual");
    assert.equal(options.headers["Content-Type"], "application/x-www-form-urlencoded");
    assert.deepEqual(Object.fromEntries(new URLSearchParams(options.body)), {
      client_id: env.OURA_CLIENT_ID, client_secret: env.OURA_CLIENT_SECRET,
      grant_type: "authorization_code", ...exchange,
    });
    return jsonResponse({ ...token, scope: "daily", client_secret: env.OURA_CLIENT_SECRET, other_private_field: "hidden" });
  });
  const result = await handle(request("/oauth/exchange", exchange), env);
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), { ...token, token_type: "Bearer", scope: "daily" });
  assert.equal(result.headers.get("Cache-Control"), "no-store");
});

test("intercepted authorization code needs its original verifier and cannot be replayed", async () => {
  // Simulate Oura's documented PKCE and one-use code contract. This verifies
  // the broker actually forwards the verifier rather than dropping it.
  const expectedChallenge = createHash("sha256").update(verifier).digest("base64url");
  let consumed = false;
  const handle = createHandler(async (_url, options) => {
    const params = new URLSearchParams(options.body);
    const challenge = createHash("sha256").update(params.get("code_verifier")).digest("base64url");
    if (consumed || challenge !== expectedChallenge) return jsonResponse({ error: "invalid_grant", error_description: "secret details" }, 400);
    consumed = true;
    return jsonResponse(token);
  });
  const intercepted = await handle(request("/oauth/exchange", { ...exchange, code_verifier: "b".repeat(64) }), env);
  assert.equal(intercepted.status, 400);
  assert.deepEqual(await intercepted.json(), { error: "invalid_grant" });
  assert.equal((await handle(request("/oauth/exchange", exchange), env)).status, 200);
  const replay = await handle(request("/oauth/exchange", exchange), env);
  assert.equal(replay.status, 400);
  assert.deepEqual(await replay.json(), { error: "invalid_grant" });
});

test("refresh forwards the rotating credential and returns the replacement refresh token", async () => {
  const handle = createHandler(async (_url, options) => {
    assert.deepEqual(Object.fromEntries(new URLSearchParams(options.body)), {
      client_id: env.OURA_CLIENT_ID, client_secret: env.OURA_CLIENT_SECRET,
      grant_type: "refresh_token", refresh_token: "old-refresh",
    });
    return jsonResponse({ ...token, refresh_token: "rotated-refresh" });
  });
  const result = await handle(request("/oauth/refresh", { refresh_token: "old-refresh" }), env);
  assert.equal(result.status, 200);
  assert.equal((await result.json()).refresh_token, "rotated-refresh");
});

test("revoke accepts a POST body and uses only Oura's documented fixed revoke URL", async () => {
  const handle = createHandler(async (url, options) => {
    const parsed = new URL(url);
    assert.equal(parsed.origin + parsed.pathname, "https://api.ouraring.com/oauth/revoke");
    assert.equal(parsed.searchParams.get("access_token"), "access.value");
    assert.equal(parsed.searchParams.size, 1);
    assert.equal(options.method, "GET");
    assert.equal(options.redirect, "manual");
    return new Response(null, { status: 200 });
  });
  const result = await handle(request("/oauth/revoke", { access_token: "access.value" }), env);
  assert.equal(result.status, 204);
  assert.equal(await result.text(), "");
  assert.equal(result.headers.get("Cache-Control"), "no-store");
});

test("invalid redirect, omitted/invalid verifier, extra credentials, and malformed bodies cannot reach Oura", async () => {
  let calls = 0;
  const handle = createHandler(async () => { calls++; return jsonResponse(token); });
  const invalid = [
    { ...exchange, redirect_uri: "https://attacker.example/callback" },
    { code: exchange.code, redirect_uri: REDIRECT_URI },
    { ...exchange, code_verifier: "short" },
    { ...exchange, code_verifier: "a".repeat(129) },
    { ...exchange, code_verifier: "a".repeat(42) + "+" },
    { ...exchange, code: "code\nsecret" },
    { ...exchange, client_secret: "attacker-supplied" },
    null, [], "not json",
  ];
  for (const body of invalid) {
    const result = await handle(request("/oauth/exchange", body), env);
    assert.equal(result.status, 400);
    assert.deepEqual(await result.json(), { error: "invalid_request" });
  }
  for (const path of ["/oauth/refresh", "/oauth/revoke"]) {
    assert.equal((await handle(request(path, {}), env)).status, 400);
  }
  assert.equal(calls, 0);
});

test("body byte bounds, HTTPS, query credentials, and JSON content type are enforced before forwarding", async () => {
  let calls = 0;
  const handle = createHandler(async () => { calls++; return jsonResponse(token); });
  assert.equal((await handle(request("/oauth/exchange", " ".repeat(8193)), env)).status, 400);
  assert.equal((await handle(request("/oauth/exchange", exchange, { headers: { "Content-Type": "application/json", "Content-Length": "999999" } }), env)).status, 400);
  assert.equal((await handle(new Request("http://broker.example/oauth/exchange", { method: "POST" }), env)).status, 400);
  assert.equal((await handle(request("/oauth/exchange?code=leak", exchange), env)).status, 400);
  assert.equal((await handle(request("/oauth/exchange", exchange, { headers: { "Content-Type": "text/plain" } }), env)).status, 415);
  assert.equal((await handle(new Request("https://broker.example/oauth/exchange"), env)).status, 405);
  assert.equal((await handle(request("/arbitrary", exchange), env)).status, 404);
  assert.equal(calls, 0);
});

test("rate limiter gets an edge IP key, never an Oura credential, and fails closed", async () => {
  let key;
  let calls = 0;
  const handle = createHandler(async () => { calls++; return jsonResponse(token); });
  const limitedEnv = { ...env, OAUTH_RATE_LIMIT: { limit: async (input) => { key = input.key; return { success: false }; } } };
  const result = await handle(request("/oauth/refresh", { refresh_token: "sensitive" }), limitedEnv);
  assert.equal(result.status, 429);
  assert.equal(result.headers.get("Retry-After"), "60");
  assert.equal(key, "oauth:192.0.2.1");
  assert.equal((await handle(request("/oauth/refresh", { refresh_token: "sensitive" }), { ...env, OAUTH_RATE_LIMIT: undefined })).status, 503);
  assert.equal(calls, 0);
});

test("upstream descriptions, unexpected fields, exceptions and invalid envelopes are never reflected", async () => {
  const badResponses = [
    jsonResponse({ error: "invalid_grant", error_description: env.OURA_CLIENT_SECRET }, 400),
    jsonResponse({ error: "invalid_client", error_description: env.OURA_CLIENT_SECRET }, 401),
    jsonResponse({ ...token, expires_in: "86400", secret: env.OURA_CLIENT_SECRET }),
    jsonResponse({ ...token, refresh_token: null }),
    jsonResponse({ ...token, token_type: "mac" }),
    new Response("private upstream exception", { status: 500 }),
    new Response("x".repeat(16385)),
    new Response("invalid JSON"),
  ];
  for (const upstream of badResponses) {
    const handle = createHandler(async () => upstream);
    const result = await handle(request("/oauth/exchange", exchange), env);
    assert.ok(result.status >= 400);
    const text = await result.text();
    assert.ok(!text.includes(env.OURA_CLIENT_SECRET));
    assert.ok(!text.includes("private upstream"));
    assert.ok(!text.includes(token.access_token));
    assert.deepEqual(Object.keys(JSON.parse(text)), ["error"]);
  }
  const handle = createHandler(async () => { throw new Error(env.OURA_CLIENT_SECRET); });
  assert.deepEqual(await (await handle(request("/oauth/exchange", exchange), env)).json(), { error: "temporarily_unavailable" });
});

test("Oura rate limits retain only a bounded Retry-After header", async () => {
  for (const [value, expected] of [["120", "120"], ["999999", null], ["Wed, 21 Oct 2026", null]]) {
    const handle = createHandler(async () => new Response("private rate-limit details", { status: 429, headers: { "Retry-After": value } }));
    const result = await handle(request("/oauth/refresh", { refresh_token: "old-refresh" }), env);
    assert.equal(result.status, 429);
    assert.equal(result.headers.get("Retry-After"), expected);
    assert.deepEqual(await result.json(), { error: "temporarily_unavailable" });
  }
});

test("upstream redirects are refused, not followed", async () => {
  let calls = 0;
  const handle = createHandler(async () => {
    calls += 1;
    return new Response(null, { status: 302, headers: { Location: "https://attacker.example/" } });
  });
  const result = await handle(request("/oauth/refresh", { refresh_token: "refresh.value" }), env);
  assert.equal(calls, 1);
  assert.equal(result.status, 502);
  assert.deepEqual(await result.json(), { error: "server_error" });
});

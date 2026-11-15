// The broker never persists codes or tokens. Oura validates S256 PKCE and
// consumes authorization codes / rotating refresh tokens exactly once.
export const REDIRECT_URI = "com.jonisuominen.readout://oauth/oura";
const TOKEN_URL = "https://api.ouraring.com/oauth/token";
const REVOKE_URL = "https://api.ouraring.com/oauth/revoke";
const MAX_REQUEST_BYTES = 8192;
const MAX_RESPONSE_BYTES = 16384;
const TOKEN_ROUTES = new Set(["/oauth/exchange", "/oauth/refresh", "/oauth/revoke"]);

class InvalidRequest extends Error {}

function response(status, payload, extraHeaders = {}) {
  return new Response(payload == null ? null : JSON.stringify(payload), {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Pragma": "no-cache",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
      "Strict-Transport-Security": "max-age=31536000",
      ...(payload == null ? {} : { "Content-Type": "application/json; charset=utf-8" }),
      ...extraHeaders,
    },
  });
}

function error(status, name, headers = {}) {
  // Never reflect request values, upstream error descriptions, or exceptions.
  return response(status, { error: name }, headers);
}

function opaque(value, max = 4096) {
  return typeof value === "string" && value.length > 0 && value.length <= max && /^[\x21-\x7e]+$/.test(value);
}

function configured(env) {
  return opaque(env.OURA_CLIENT_ID, 256) && opaque(env.OURA_CLIENT_SECRET, 4096);
}

async function readJSON(message, maxBytes) {
  const length = message.headers.get("Content-Length");
  if (length && (!/^\d+$/.test(length) || Number(length) > maxBytes)) throw new InvalidRequest();
  if (!message.body) throw new InvalidRequest();
  const reader = message.body.getReader();
  const chunks = [];
  let byteCount = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      byteCount += value.byteLength;
      if (byteCount > maxBytes) {
        await reader.cancel();
        throw new InvalidRequest();
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(byteCount);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    const parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    if (parsed == null || typeof parsed !== "object" || Array.isArray(parsed)) throw new InvalidRequest();
    return parsed;
  } catch {
    throw new InvalidRequest();
  }
}

function exactKeys(body, expected) {
  const actual = Object.keys(body);
  if (actual.length !== expected.length || !actual.every((key) => expected.includes(key))) throw new InvalidRequest();
}

function exchangeParams(path, body, env) {
  const params = new URLSearchParams({ client_id: env.OURA_CLIENT_ID, client_secret: env.OURA_CLIENT_SECRET });
  if (path === "/oauth/exchange") {
    exactKeys(body, ["code", "redirect_uri", "code_verifier"]);
    if (!opaque(body.code) || body.redirect_uri !== REDIRECT_URI ||
        typeof body.code_verifier !== "string" || !/^[A-Za-z0-9._~-]{43,128}$/.test(body.code_verifier)) {
      throw new InvalidRequest();
    }
    params.set("grant_type", "authorization_code");
    params.set("code", body.code);
    params.set("redirect_uri", REDIRECT_URI);
    params.set("code_verifier", body.code_verifier);
  } else {
    exactKeys(body, ["refresh_token"]);
    if (!opaque(body.refresh_token)) throw new InvalidRequest();
    params.set("grant_type", "refresh_token");
    params.set("refresh_token", body.refresh_token);
  }
  return params;
}

function rateHeaders(upstream) {
  const retry = upstream.headers.get("Retry-After");
  return retry && /^\d{1,5}$/.test(retry) && Number(retry) <= 86400 ? { "Retry-After": retry } : {};
}

async function upstreamFailure(upstream) {
  if (upstream.status === 429) return error(429, "temporarily_unavailable", rateHeaders(upstream));
  if (upstream.status >= 500) return error(503, "temporarily_unavailable");
  // The client must know when its single-use refresh token is no longer valid.
  // Read only bounded error JSON and allowlist the OAuth error name.
  if (upstream.status === 400 || upstream.status === 401 || upstream.status === 403) {
    try {
      const body = await readJSON(upstream, MAX_RESPONSE_BYTES);
      if (body.error === "invalid_grant" || body.error === "invalid_token") return error(400, "invalid_grant");
    } catch { /* Malformed upstream errors remain generic. */ }
  }
  return error(502, "server_error");
}

function tokenEnvelope(body) {
  if (!opaque(body.access_token) || !opaque(body.refresh_token) ||
      typeof body.token_type !== "string" || body.token_type.toLowerCase() !== "bearer" ||
      !Number.isSafeInteger(body.expires_in) || body.expires_in <= 0 || body.expires_in > 31536000) {
    throw new InvalidRequest();
  }
  const result = {
    access_token: body.access_token,
    refresh_token: body.refresh_token,
    token_type: "Bearer",
    expires_in: body.expires_in,
  };
  if (body.scope !== undefined) {
    if (typeof body.scope !== "string" || body.scope.length > 1024 || !/^[\x20-\x7e]*$/.test(body.scope)) throw new InvalidRequest();
    result.scope = body.scope;
  }
  return result;
}

export function createHandler(fetcher = globalThis.fetch) {
  return async function handle(request, env) {
    const url = new URL(request.url);
    // Credentials must never arrive in query strings or travel over HTTP.
    if (url.protocol !== "https:" || url.search || url.username || url.password) return error(400, "invalid_request");
    if (url.pathname === "/health" && request.method === "GET") return response(200, { status: "ok" });
    if (url.pathname === "/config" && request.method === "GET") {
      if (!configured(env)) return error(503, "service_unavailable");
      return response(200, { client_id: env.OURA_CLIENT_ID, redirect_uri: REDIRECT_URI });
    }
    if (!TOKEN_ROUTES.has(url.pathname)) return error(404, "not_found");
    if (request.method !== "POST") return error(405, "method_not_allowed", { Allow: "POST" });
    if (!configured(env) || !env.OAUTH_RATE_LIMIT?.limit) return error(503, "service_unavailable");
    const contentType = request.headers.get("Content-Type") ?? "";
    if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(contentType)) return error(415, "invalid_request");

    try {
      // Cloudflare supplies this trusted edge header; tokens are never used as
      // rate-limit keys. Counters are ephemeral and contain no Oura identifiers.
      const ip = request.headers.get("CF-Connecting-IP") || "local";
      const limit = await env.OAUTH_RATE_LIMIT.limit({ key: `oauth:${ip}` });
      if (!limit.success) return error(429, "temporarily_unavailable", { "Retry-After": "60" });
    } catch {
      return error(503, "temporarily_unavailable");
    }

    let params;
    let revokeToken;
    try {
      const body = await readJSON(request, MAX_REQUEST_BYTES);
      if (url.pathname === "/oauth/revoke") {
        exactKeys(body, ["access_token"]);
        if (!opaque(body.access_token)) throw new InvalidRequest();
        revokeToken = body.access_token;
      } else {
        params = exchangeParams(url.pathname, body, env);
      }
    } catch {
      return error(400, "invalid_request");
    }

    try {
      const abort = new AbortController();
      const timeout = setTimeout(() => abort.abort(), 10000);
      try {
        let upstream;
        if (revokeToken) {
          // Oura documents revocation as this HTTPS GET endpoint. This token is
          // in the upstream URL only; our public endpoint requires a POST body.
          const revokeURL = new URL(REVOKE_URL);
          revokeURL.searchParams.set("access_token", revokeToken);
          upstream = await fetcher(revokeURL.toString(), {
            method: "GET", headers: { Accept: "application/json" }, redirect: "manual", signal: abort.signal,
          });
        } else {
          upstream = await fetcher(TOKEN_URL, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
            body: params.toString(), redirect: "manual", signal: abort.signal,
          });
        }
        if (!upstream.ok) return await upstreamFailure(upstream);
        if (revokeToken) return response(204, null);
        try {
          return response(200, tokenEnvelope(await readJSON(upstream, MAX_RESPONSE_BYTES)));
        } catch {
          return error(502, "server_error");
        }
      } finally {
        clearTimeout(timeout);
      }
    } catch {
      return error(503, "temporarily_unavailable");
    }
  };
}

export default { fetch: createHandler() };

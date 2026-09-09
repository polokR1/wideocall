const FIREBASE_PROJECT_ID = "vidcall-88857";
const FIREBASE_DATABASE_URL = "https://vidcall-88857-default-rtdb.europe-west1.firebasedatabase.app";
const USER_ID = /^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/;
const CALL_ID = /^[A-Za-z0-9_-]{8,128}$/;
const SIGNAL_ID = /^[A-Za-z0-9_-]{10,128}$/;
const MAX_BODY_BYTES = 4096;
const OFFER_MAX_AGE_MS = 60_000;

let cachedAccessToken = null;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, configured: hasConfiguration(env) });
    }
    if (request.method !== "POST" || url.pathname !== "/notify-call") {
      return json({ error: "not_found" }, 404);
    }
    if (!hasConfiguration(env)) return json({ error: "gateway_not_configured" }, 503);

    try {
      return await notifyIncomingCall(request, env);
    } catch (error) {
      console.error(JSON.stringify({
        event: "notify_call_failed",
        error: error instanceof Error ? error.name : "unknown",
      }));
      return json({ error: "gateway_error" }, 502);
    }
  },
};

async function notifyIncomingCall(request, env) {
  const contentLength = Number(request.headers.get("content-length") || "0");
  if (contentLength > MAX_BODY_BYTES) return json({ error: "payload_too_large" }, 413);

  const bearer = readBearer(request.headers.get("authorization"));
  if (!bearer) return json({ error: "unauthorized" }, 401);
  const body = await readBody(request);
  const validationError = validateCallRequest(body);
  if (validationError) return json({ error: validationError }, 400);

  const firebaseUser = await verifyFirebaseIdToken(bearer, env.FIREBASE_WEB_API_KEY);
  if (!firebaseUser) return json({ error: "unauthorized" }, 401);

  const credentials = parseServiceAccount(env.FIREBASE_SERVICE_ACCOUNT_JSON);
  const accessToken = await getGoogleAccessToken(credentials);
  const signal = await readDatabase(
    ["signals", body.recipientId, body.signalId],
    accessToken,
  );
  if (!isMatchingFreshOffer(signal, body, firebaseUser.localId)) {
    return json({ error: "offer_missing_or_expired" }, 409);
  }

  const device = await readDatabase(["devices", body.recipientId], accessToken);
  if (!device || typeof device.token !== "string" || device.token.length < 20) {
    return json({ error: "recipient_has_no_push_token" }, 409);
  }

  const fcmResponse = await fetch(
    `https://fcm.googleapis.com/v1/projects/${FIREBASE_PROJECT_ID}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token: device.token,
          data: {
            type: "incoming_call",
            callerId: body.callerId,
            recipientId: body.recipientId,
            callId: body.callId,
          },
          android: {
            priority: "HIGH",
            ttl: "35s",
            collapse_key: `call-${body.callId}`,
          },
        },
      }),
    },
  );
  if (!fcmResponse.ok) throw new Error(`FCM_${fcmResponse.status}`);
  return json({ ok: true }, 202);
}

export function validateCallRequest(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return "invalid_json";
  if (!USER_ID.test(body.recipientId || "")) return "invalid_recipient_id";
  if (!USER_ID.test(body.callerId || "")) return "invalid_caller_id";
  if (!CALL_ID.test(body.callId || "")) return "invalid_call_id";
  if (!SIGNAL_ID.test(body.signalId || "")) return "invalid_signal_id";
  if (body.recipientId === body.callerId) return "cannot_call_self";
  return null;
}

export function isMatchingFreshOffer(signal, body, authenticatedUid, now = Date.now()) {
  if (!signal || typeof signal !== "object") return false;
  const createdAt = Number(signal.createdAt || 0);
  return signal.type === "offer" &&
    signal.from === body.callerId &&
    signal.to === body.recipientId &&
    signal.callId === body.callId &&
    signal.senderUid === authenticatedUid &&
    createdAt > 0 &&
    Math.abs(now - createdAt) <= OFFER_MAX_AGE_MS;
}

function hasConfiguration(env) {
  return typeof env.FIREBASE_WEB_API_KEY === "string" && env.FIREBASE_WEB_API_KEY.length > 20 &&
    typeof env.FIREBASE_SERVICE_ACCOUNT_JSON === "string" && env.FIREBASE_SERVICE_ACCOUNT_JSON.length > 100;
}

function readBearer(value) {
  const match = /^Bearer ([A-Za-z0-9._-]{20,4096})$/.exec(value || "");
  return match?.[1] || null;
}

async function readBody(request) {
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BODY_BYTES) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  } finally {
    reader.releaseLock();
  }
}

async function verifyFirebaseIdToken(idToken, apiKey) {
  const response = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${encodeURIComponent(apiKey)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken }),
    },
  );
  if (!response.ok) return null;
  const result = await response.json();
  return Array.isArray(result.users) && result.users.length === 1 ? result.users[0] : null;
}

function parseServiceAccount(raw) {
  const parsed = JSON.parse(raw);
  if (parsed.type !== "service_account" || !parsed.client_email || !parsed.private_key) {
    throw new Error("INVALID_SERVICE_ACCOUNT");
  }
  return parsed;
}

async function getGoogleAccessToken(credentials) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedAccessToken?.email === credentials.client_email && cachedAccessToken.expiresAt > now + 60) {
    return cachedAccessToken.value;
  }
  const claim = {
    iss: credentials.client_email,
    scope: "https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const assertion = await signJwt(claim, credentials.private_key);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new Error(`OAUTH_${response.status}`);
  const result = await response.json();
  cachedAccessToken = {
    email: credentials.client_email,
    value: result.access_token,
    expiresAt: now + Number(result.expires_in || 3600),
  };
  return cachedAccessToken.value;
}

async function signJwt(claim, privateKeyPem) {
  const header = { alg: "RS256", typ: "JWT" };
  const unsigned = `${base64UrlJson(header)}.${base64UrlJson(claim)}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(privateKeyPem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  return `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;
}

function base64UrlJson(value) {
  return base64UrlBytes(new TextEncoder().encode(JSON.stringify(value)));
}

function base64UrlBytes(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function pemToArrayBuffer(pem) {
  const base64 = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

async function readDatabase(pathSegments, accessToken) {
  const path = pathSegments.map(encodeURIComponent).join("/");
  const response = await fetch(
    `${FIREBASE_DATABASE_URL}/${path}.json?access_token=${encodeURIComponent(accessToken)}`,
  );
  if (!response.ok) throw new Error(`DATABASE_${response.status}`);
  return response.json();
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

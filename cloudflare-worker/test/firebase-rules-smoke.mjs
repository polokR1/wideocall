import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const configPath = fileURLToPath(new URL("../../app/google-services.json", import.meta.url));
const config = JSON.parse(await readFile(configPath, "utf8"));
const apiKey = config.client?.[0]?.api_key?.[0]?.current_key;
const databaseUrl = config.project_info?.firebase_url?.replace(/\/$/, "");
if (!apiKey || !databaseUrl) throw new Error("Firebase configuration is incomplete");

const authResponse = await fetch(
  `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${encodeURIComponent(apiKey)}`,
  {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ returnSecureToken: true }),
  },
);
if (!authResponse.ok) throw new Error(`Anonymous authentication failed: ${authResponse.status}`);
const { idToken, localId } = await authResponse.json();
if (!idToken || !localId) throw new Error("Anonymous authentication returned no token");

const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const randomPart = (length) => Array.from(
  crypto.getRandomValues(new Uint8Array(length)),
  (value) => alphabet[value % alphabet.length],
).join("");
const userId = `${randomPart(4)}-${randomPart(4)}`;
const signalId = `smoke_${Date.now()}_${randomPart(4)}`;
const invalidSignalId = `${signalId}_bad`;
const authorizedUrl = (path) => `${databaseUrl}/${path}.json?auth=${encodeURIComponent(idToken)}`;

async function request(path, method, body) {
  return fetch(authorizedUrl(path), {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

try {
  const presence = await request(`users/${userId}`, "PUT", {
    authUid: localId,
    online: true,
    busy: false,
    pushReady: false,
    watchReady: false,
    lastSeen: Date.now(),
  });
  if (!presence.ok) throw new Error(`Presence write rejected: ${presence.status}`);

  const validSignal = {
    type: "offer",
    from: userId,
    to: userId,
    senderUid: localId,
    createdAt: Date.now(),
    callId: `smoke_${Date.now()}`,
    sessionKind: "video",
    sdp: "v=0\\r\\n",
    sdpType: "offer",
    identityKey: "A".repeat(120),
    signature: "B".repeat(88),
  };
  const signal = await request(`signals/${userId}/${signalId}`, "PUT", validSignal);
  if (!signal.ok) throw new Error(`Valid signal rejected: ${signal.status}`);

  const read = await request(`signals/${userId}/${signalId}`, "GET");
  if (!read.ok || (await read.json())?.identityKey !== validSignal.identityKey) {
    throw new Error(`Signal read failed: ${read.status}`);
  }

  const invalid = await request(
    `signals/${userId}/${invalidSignalId}`,
    "PUT",
    { ...validSignal, unexpected: "blocked" },
  );
  if (invalid.ok) throw new Error("Rules accepted an unexpected signal field");

  console.log("Firebase rules smoke test passed: valid signal accepted, extra field rejected");
} finally {
  await request(`signals/${userId}/${signalId}`, "DELETE").catch(() => undefined);
  await request(`signals/${userId}/${invalidSignalId}`, "DELETE").catch(() => undefined);
  await request(`users/${userId}`, "DELETE").catch(() => undefined);
}

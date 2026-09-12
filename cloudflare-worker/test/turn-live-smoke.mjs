import assert from "node:assert/strict";
import { createSign, generateKeyPairSync, randomInt } from "node:crypto";

const WORKER_URL = process.env.WIDEOCALL_WORKER_URL || "https://wideocall-notify.wideocall-polokr1.workers.dev";
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const segment = () => Array.from({ length: 4 }, () => ALPHABET[randomInt(ALPHABET.length)]).join("");
const userId = `${segment()}-${segment()}`;
const key = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const identityKey = key.publicKey.export({ type: "spki", format: "der" }).toString("base64");
const timestamp = Date.now();

function payload(values) {
  const chunks = [];
  for (const value of values) {
    const bytes = Buffer.from(value, "utf8");
    const length = Buffer.alloc(4);
    length.writeUInt32BE(bytes.length);
    chunks.push(length, bytes);
  }
  return Buffer.concat(chunks);
}

const signer = createSign("SHA256");
signer.update(payload(["wideocall-device-auth-v1", "turn", userId, String(timestamp), identityKey]));
const signature = signer.sign(key.privateKey).toString("base64");
const response = await fetch(`${WORKER_URL}/turn-credentials`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ userId, operation: "turn", timestamp, identityKey, signature }),
});
assert.equal(response.status, 200, `TURN endpoint failed: ${response.status}`);
const turn = await response.json();
assert.ok(Array.isArray(turn.iceServers), "TURN response has no ICE server list");
const relay = turn.iceServers.find((server) => server.username && server.credential && server.urls?.some((url) => url.startsWith("turn:") || url.startsWith("turns:")));
assert.ok(relay, "TURN response has no authenticated relay server");
assert.ok(turn.expiresAt > Date.now() + 23 * 60 * 60 * 1000, "TURN credentials expire too soon");
console.log(JSON.stringify({ ok: true, cloudflareDeviceAuth: true, turnCredentialsIssued: true, iceServerCount: turn.iceServers.length, relayUrlCount: relay.urls.length, ttlHours: Math.round((turn.expiresAt - Date.now()) / 3_600_000) }));

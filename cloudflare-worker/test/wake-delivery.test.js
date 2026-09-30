import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { SignalHub, sendFcmWake } from "../src/signal-hub.js";

const offer = {
  type: "offer", from: "ABCD-EF23", to: "WXYZ-4567", callId: "wake-test-123456",
  sessionKind: "video", sdp: "v=0", sdpType: "offer", identityKey: "a".repeat(100), signature: "b".repeat(80),
};

function fixture(online = true) {
  const data = new Map();
  const sent = [];
  const wakes = [];
  const background = [];
  const socket = { deserializeAttachment: () => ({ authenticated: true }), send: value => sent.push(JSON.parse(value)) };
  const ctx = { getWebSockets: () => online ? [socket] : [], waitUntil: task => background.push(task), storage: {
    get: async key => structuredClone(data.get(key)),
    put: async (key, value) => data.set(key, structuredClone(value)),
    delete: async key => data.delete(key), setAlarm: async at => data.set("alarm", at), deleteAlarm: async () => data.delete("alarm"),
  } };
  const hub = new SignalHub(ctx, {});
  hub.wakeRecipient = async (signal, delivered) => wakes.push({ type: signal.type, delivered });
  return { hub, data, sent, wakes, background };
}

function deliver(hub, signal) {
  return hub.deliver(new Request("https://signal-hub/deliver", { method: "POST", body: JSON.stringify(signal) }));
}

test("a connected WebSocket still receives FCM wake and the offer remains replayable", async () => {
  const { hub, data, sent, wakes } = fixture();
  assert.deepEqual(await (await deliver(hub, offer)).json(), { delivered: true });
  assert.equal(sent.length, 1);
  assert.deepEqual(wakes, [{ type: "offer", delivered: true }]);
  assert.equal(data.get("pending_offer").callId, offer.callId);
  assert.ok(data.get("alarm") > Date.now());
});

test("ICE remains replayable when a sleeping recipient socket accepts writes", async () => {
  const { hub, data, wakes } = fixture();
  await deliver(hub, offer);
  await deliver(hub, { ...offer, type: "ice", candidate: "candidate:test" });
  assert.equal(data.get("pending_offer").signals.length, 2);
  assert.equal(wakes.length, 1); // no push storm for every candidate
});

test("online cancellation clears the replay offer and wakes the phone too", async () => {
  for (const type of ["hangup", "reject"]) {
    const { hub, data, wakes } = fixture();
    await deliver(hub, offer);
    await deliver(hub, { ...offer, type });
    assert.equal(data.has("pending_offer"), false);
    assert.deepEqual(wakes.at(-1), { type, delivered: true });
  }
});

test("offline offer still wakes the phone and cancellation removes the stale offer", async () => {
  const { hub, data, wakes } = fixture(false);
  assert.deepEqual(await (await deliver(hub, offer)).json(), { delivered: false });
  await deliver(hub, { ...offer, type: "hangup" });
  assert.equal(data.has("pending_offer"), false);
  assert.deepEqual(wakes, [{ type: "offer", delivered: false }, { type: "hangup", delivered: false }]);
});

test("a signed recipient answer clears its own replay offer", async () => {
  const { hub, data } = fixture();
  await deliver(hub, offer);
  const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const key = publicKey.export({ type: "spki", format: "der" }).toString("base64");
  const answer = { ...offer, type: "answer", from: offer.to, to: offer.from, sdpType: "answer", identityKey: key };
  const values = ["wideocall-signaling-v1", answer.type, answer.from, answer.to, answer.callId, answer.sessionKind,
    answer.sdp, answer.sdpType, "", "", "", "", key];
  const payload = Buffer.concat(values.flatMap(value => {
    const bytes = Buffer.from(value); const size = Buffer.alloc(4); size.writeUInt32BE(bytes.length); return [size, bytes];
  }));
  answer.signature = sign("sha256", payload, privateKey).toString("base64");
  hub.env.SIGNAL_HUB = { getByName: () => ({ fetch: async () => Response.json({ delivered: true }) }) };
  await hub.routeSignal({ send: () => {}, close: () => assert.fail("valid answer rejected") }, answer.from, { signal: answer });
  assert.equal(data.has("pending_offer"), false);
});

test("wake work is registered with waitUntil instead of blocking SDP acknowledgement", async () => {
  const { hub, data, background } = fixture();
  delete hub.wakeRecipient;
  data.set("push_token", "test-token-not-a-real-device");
  await hub.wakeRecipient(offer, true);
  assert.equal(background.length, 1);
  assert.equal(await background[0], false); // missing credentials, no external requests
});

test("FCM message has HIGH priority but contains no SDP or message content", async t => {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const env = { FIREBASE_SERVICE_ACCOUNT_JSON: JSON.stringify({ client_email: "test@example.invalid", private_key: privateKey.export({ type: "pkcs8", format: "pem" }) }) };
  let message;
  t.mock.method(globalThis, "fetch", async (url, init) => {
    assert.ok(init.signal);
    if (String(url).includes("oauth2")) return Response.json({ access_token: "test-access" });
    message = JSON.parse(init.body).message;
    return Response.json({ name: "test-message" });
  });
  assert.equal(await sendFcmWake(env, "test-device", offer, true), true);
  assert.equal(message.android.priority, "HIGH");
  assert.equal(message.android.ttl, "35s");
  assert.equal(message.data.type, "incoming_call");
  assert.equal(Object.hasOwn(message.data, "sdp"), false);
  assert.equal(Object.hasOwn(message.data, "text"), false);
});

test("FCM failure does not fail the socket delivery or expose credentials in logs", async t => {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const env = { FIREBASE_SERVICE_ACCOUNT_JSON: JSON.stringify({ client_email: "test@example.invalid", private_key: privateKey.export({ type: "pkcs8", format: "pem" }) }) };
  const logs = [];
  t.mock.method(console, "warn", message => logs.push(JSON.parse(message)));
  t.mock.method(globalThis, "fetch", async url => String(url).includes("oauth2") ? Response.json({ access_token: "SECRET" }) : new Response(null, { status: 503 }));
  assert.equal(await sendFcmWake(env, "PRIVATE_TOKEN", offer), false);
  assert.equal(logs[0].error, "FCM_503");
  assert.equal(JSON.stringify(logs).includes("SECRET"), false);
  assert.equal(JSON.stringify(logs).includes("PRIVATE_TOKEN"), false);
});

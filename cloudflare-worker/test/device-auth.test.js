import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { SignalHub } from "../src/signal-hub.js";

function signedPacket(overrides = {}) {
  const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const packet = {
    userId: "ABCD-EF23", operation: "turn", timestamp: Date.now(),
    identityKey: publicKey.export({ type: "spki", format: "der" }).toString("base64"),
    ...overrides,
  };
  const payload = Buffer.concat([
    "wideocall-device-auth-v1", packet.operation, packet.userId,
    String(packet.timestamp), packet.identityKey,
  ].flatMap(value => {
    const bytes = Buffer.from(value);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(bytes.length);
    return [length, bytes];
  }));
  packet.signature = sign("sha256", payload, privateKey).toString("base64");
  return packet;
}

function fixture() {
  const data = new Map();
  const hub = new SignalHub({ storage: {
    get: async key => data.get(key), put: async (key, value) => data.set(key, value),
  } }, {});
  return { hub, data };
}

test("invalid signature does not pin a key and a subsequent valid device can register", async () => {
  const { hub, data } = fixture();
  assert.equal(await hub.verifyDeviceAuthorization({
    userId: "ABCD-EF23", operation: "turn", timestamp: Date.now(),
    identityKey: "a".repeat(100), signature: "b".repeat(80),
  }, "turn"), false);
  assert.equal(data.size, 0);
  const packet = signedPacket();
  assert.equal(await hub.verifyDeviceAuthorization(packet, "turn"), true);
  assert.equal(data.get("identity_key"), packet.identityKey);
  assert.equal(await hub.verifyDeviceAuthorization(packet, "turn"), true);
});

test("valid signatures with a different identity cannot replace a pinned key", async () => {
  const { hub, data } = fixture();
  const original = signedPacket();
  assert.equal(await hub.verifyDeviceAuthorization(original, "turn"), true);
  assert.equal(await hub.verifyDeviceAuthorization(signedPacket(), "turn"), false);
  assert.equal(data.get("identity_key"), original.identityKey);
});

test("tampering with signed fields is rejected without persisting an identity", async () => {
  const { hub, data } = fixture();
  const packet = signedPacket();
  assert.equal(await hub.verifyDeviceAuthorization({ ...packet, userId: "WXYZ-4567" }, "turn"), false);
  assert.equal(data.size, 0);
});

test("expired timestamps and wrong operations do not pin an identity", async () => {
  const { hub, data } = fixture();
  assert.equal(await hub.verifyDeviceAuthorization(signedPacket({ timestamp: Date.now() - 600_000 }), "turn"), false);
  assert.equal(await hub.verifyDeviceAuthorization(signedPacket({ operation: "device" }), "turn"), false);
  assert.equal(data.size, 0);
});

test("authorize endpoint returns a negative result without poisoning storage", async () => {
  const { hub, data } = fixture();
  const packet = { ...signedPacket(), signature: "b".repeat(80) };
  const response = await hub.authorize(new Request("https://signal-hub/authorize", {
    method: "POST", body: JSON.stringify(packet),
  }));
  assert.deepEqual(await response.json(), { authorized: false });
  assert.equal(data.size, 0);
});

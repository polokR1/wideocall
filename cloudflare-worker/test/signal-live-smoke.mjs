import assert from "node:assert/strict";
import { createSign, generateKeyPairSync, randomInt } from "node:crypto";

const ORIGIN = process.env.WIDEOCALL_WORKER_URL || "https://wideocall-notify.wideocall-polokr1.workers.dev";
const WS_ORIGIN = ORIGIN.replace(/^https:/, "wss:");
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

function userId() {
  const segment = () => Array.from({ length: 4 }, () => ALPHABET[randomInt(ALPHABET.length)]).join("");
  return `${segment()}-${segment()}`;
}

function lengthPrefixed(values) {
  const parts = values.map((value) => Buffer.from(value, "utf8"));
  const result = [];
  for (const part of parts) {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(part.length);
    result.push(length, part);
  }
  return Buffer.concat(result);
}

function identity() {
  const pair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  return {
    publicKey: pair.publicKey.export({ type: "spki", format: "der" }).toString("base64"),
    sign(payload) {
      const signer = createSign("SHA256");
      signer.update(payload);
      return signer.sign(pair.privateKey).toString("base64");
    },
  };
}

function connect(id, key) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${WS_ORIGIN}/signal/${id}`);
    const timeout = setTimeout(() => reject(new Error("WebSocket authentication timed out")), 10_000);
    socket.addEventListener("message", (event) => {
      const packet = JSON.parse(event.data);
      if (packet.type === "challenge") {
        const signature = key.sign(lengthPrefixed(["wideocall-device-auth-v1", "socket", id, packet.nonce, key.publicKey]));
        socket.send(JSON.stringify({ type: "authenticate", userId: id, identityKey: key.publicKey, signature }));
      }
      if (packet.type === "ready") {
        clearTimeout(timeout);
        resolve(socket);
      }
    });
    socket.addEventListener("error", () => {
      clearTimeout(timeout);
      reject(new Error("WebSocket connection failed"));
    }, { once: true });
  });
}

const senderId = userId();
const recipientId = userId();
const senderKey = identity();
const recipientKey = identity();
const [sender, recipient] = await Promise.all([connect(senderId, senderKey), connect(recipientId, recipientKey)]);

const received = new Promise((resolve, reject) => {
  const timeout = setTimeout(() => reject(new Error("Signal forwarding timed out")), 10_000);
  recipient.addEventListener("message", (event) => {
    const packet = JSON.parse(event.data);
    if (packet.type === "signal") {
      clearTimeout(timeout);
      resolve(packet.signal);
    }
  });
});

const callId = "smoke-call-123456";
const signalPayload = ["wideocall-signaling-v1", "offer", senderId, recipientId, callId, "video", "v=0", "offer", "", "", "", "", senderKey.publicKey];
const signal = {
  type: "offer",
  from: senderId,
  to: recipientId,
  callId,
  sessionKind: "video",
  sdp: "v=0",
  sdpType: "offer",
  identityKey: senderKey.publicKey,
  signature: senderKey.sign(lengthPrefixed(signalPayload)),
};
sender.send(JSON.stringify({ type: "signal", signal }));

const forwarded = await received;
assert.equal(forwarded.callId, callId);
assert.equal(forwarded.from, senderId);
assert.equal(forwarded.to, recipientId);
sender.close();
recipient.close();
console.log(JSON.stringify({ ok: true, authenticatedWebSockets: 2, signalForwarded: true }));

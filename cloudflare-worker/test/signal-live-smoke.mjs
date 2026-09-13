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
    socket.pendingSignals = [];
    socket.signalWaiters = [];
    socket.pendingAcknowledgements = [];
    socket.acknowledgementWaiters = [];
    const timeout = setTimeout(() => reject(new Error("WebSocket authentication timed out")), 10_000);
    socket.addEventListener("message", (event) => {
      const packet = JSON.parse(event.data);
      if (packet.type === "signal") {
        const waiter = socket.signalWaiters.shift();
        if (waiter) waiter(packet.signal);
        else socket.pendingSignals.push(packet.signal);
      }
      if (packet.type === "accepted") {
        const waiter = socket.acknowledgementWaiters.shift();
        if (waiter) waiter(packet);
        else socket.pendingAcknowledgements.push(packet);
      }
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

function nextAcknowledgement(socket) {
  if (socket.pendingAcknowledgements.length > 0) return Promise.resolve(socket.pendingAcknowledgements.shift());
  return new Promise((resolve, reject) => {
    const receive = (packet) => {
      clearTimeout(timeout);
      resolve(packet);
    };
    const timeout = setTimeout(() => {
      socket.acknowledgementWaiters = socket.acknowledgementWaiters.filter((waiter) => waiter !== receive);
      reject(new Error("Signal acknowledgement timed out"));
    }, 10_000);
    socket.acknowledgementWaiters.push(receive);
  });
}

function expectNoSignal(socket, waitMs = 1_000) {
  assert.equal(socket.pendingSignals.length, 0);
  return new Promise((resolve, reject) => {
    const receive = (signal) => reject(new Error(`Unexpected queued signal: ${signal.type}`));
    socket.signalWaiters.push(receive);
    setTimeout(() => {
      socket.signalWaiters = socket.signalWaiters.filter((waiter) => waiter !== receive);
      resolve();
    }, waitMs);
  });
}

function signSignal(signal, key) {
  const payload = [
    "wideocall-signaling-v1",
    signal.type || "",
    signal.from || "",
    signal.to || "",
    signal.callId || "",
    signal.sessionKind || "",
    signal.sdp || "",
    signal.sdpType || "",
    signal.candidate || "",
    signal.sdpMid || "",
    signal.sdpMLineIndex == null ? "" : String(signal.sdpMLineIndex),
    signal.reason || "",
    key.publicKey,
  ];
  return { ...signal, identityKey: key.publicKey, signature: key.sign(lengthPrefixed(payload)) };
}

function nextSignal(socket, timeoutMessage) {
  if (socket.pendingSignals.length > 0) return Promise.resolve(socket.pendingSignals.shift());
  return new Promise((resolve, reject) => {
    const receive = (signal) => {
      clearTimeout(timeout);
      resolve(signal);
    };
    const timeout = setTimeout(() => {
      socket.signalWaiters = socket.signalWaiters.filter((waiter) => waiter !== receive);
      reject(new Error(timeoutMessage));
    }, 10_000);
    socket.signalWaiters.push(receive);
  });
}

const senderId = userId();
const recipientId = userId();
const invitedId = userId();
const cancelledId = userId();
const cancelledInvitationId = userId();
const senderKey = identity();
const recipientKey = identity();
const invitedKey = identity();
const cancelledKey = identity();
const cancelledInvitationKey = identity();
const [sender, recipient] = await Promise.all([connect(senderId, senderKey), connect(recipientId, recipientKey)]);

const received = nextSignal(recipient, "Signal forwarding timed out");

const callId = "smoke-call-123456";
const signal = signSignal({
  type: "offer",
  from: senderId,
  to: recipientId,
  callId,
  sessionKind: "video",
  sdp: "v=0",
  sdpType: "offer",
}, senderKey);
sender.send(JSON.stringify({ type: "signal", signal }));

const forwarded = await received;
await nextAcknowledgement(sender);
assert.equal(forwarded.callId, callId);
assert.equal(forwarded.from, senderId);
assert.equal(forwarded.to, recipientId);

const requestId = "contact-request-123456";
sender.send(JSON.stringify({
  type: "signal",
  signal: signSignal({
    type: "contact-request",
    from: senderId,
    to: invitedId,
    callId: requestId,
    sessionKind: "contact",
    reason: "Telefon testowy",
  }, senderKey),
}));
await nextAcknowledgement(sender);
const invited = await connect(invitedId, invitedKey);
const queuedInvitation = await nextSignal(invited, "Queued contact invitation timed out");
assert.equal(queuedInvitation.type, "contact-request");
assert.equal(queuedInvitation.callId, requestId);

const cancelledCallId = "cancelled-call-123456";
sender.send(JSON.stringify({
  type: "signal",
  signal: signSignal({ type: "offer", from: senderId, to: cancelledId, callId: cancelledCallId, sessionKind: "video", sdp: "v=0", sdpType: "offer" }, senderKey),
}));
await nextAcknowledgement(sender);
sender.send(JSON.stringify({
  type: "signal",
  signal: signSignal({ type: "hangup", from: senderId, to: cancelledId, callId: cancelledCallId, sessionKind: "video" }, senderKey),
}));
await nextAcknowledgement(sender);
const cancelled = await connect(cancelledId, cancelledKey);
await expectNoSignal(cancelled);

const cancelledRequestId = "cancelled-contact-request-123456";
sender.send(JSON.stringify({
  type: "signal",
  signal: signSignal({ type: "contact-request", from: senderId, to: cancelledInvitationId, callId: cancelledRequestId, sessionKind: "contact", reason: "Telefon testowy" }, senderKey),
}));
await nextAcknowledgement(sender);
sender.send(JSON.stringify({
  type: "signal",
  signal: signSignal({ type: "contact-cancel", from: senderId, to: cancelledInvitationId, callId: cancelledRequestId, sessionKind: "contact", reason: "Telefon testowy" }, senderKey),
}));
await nextAcknowledgement(sender);
const cancelledInvitation = await connect(cancelledInvitationId, cancelledInvitationKey);
const queuedCancellation = await nextSignal(cancelledInvitation, "Queued contact cancellation timed out");
assert.equal(queuedCancellation.type, "contact-cancel");
assert.equal(queuedCancellation.callId, cancelledRequestId);
await expectNoSignal(cancelledInvitation);
sender.close();
recipient.close();
invited.close();
cancelled.close();
cancelledInvitation.close();
console.log(JSON.stringify({ ok: true, authenticatedWebSockets: 5, signalForwarded: true, queuedInvitation: true, cancelledCallRemoved: true, cancelledInvitation: true }));

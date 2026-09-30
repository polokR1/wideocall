const USER_ID = /^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/;
const SIGNAL_TYPES = new Set([
  "offer", "answer", "ice", "ice-restart-offer", "ice-restart-answer",
  "accepted", "hangup", "reject", "busy",
  "contact-request", "contact-accept", "contact-reject", "contact-cancel",
]);
const SESSION_KINDS = new Set(["video", "chat", "file", "contact"]);
const MAX_SIGNAL_BYTES = 220_000;
const OFFER_TTL_MS = 45_000;
const CALL_EVENT_TTL_MS = 2 * 60_000;
const CONTACT_EVENT_TTL_MS = 7 * 24 * 60 * 60_000;
const MAX_PENDING_EVENTS = 128;
const MAX_AUTH_AGE_MS = 5 * 60_000;

/** One hibernatable Durable Object per Wideocall identifier. */
export class SignalHub {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (request.headers.get("Upgrade") === "websocket") return this.acceptSocket(request);
    if (request.method !== "POST") return json({ error: "not_found" }, 404);
    if (url.pathname === "/deliver") return this.deliver(request);
    if (url.pathname === "/authorize") return this.authorize(request);
    if (url.pathname === "/device") return this.registerDevice(request);
    return json({ error: "not_found" }, 404);
  }

  async acceptSocket(request) {
    const [client, server] = Object.values(new WebSocketPair());
    const userId = new URL(request.url).pathname.split("/").at(-1)?.toUpperCase();
    if (!USER_ID.test(userId || "")) return json({ error: "invalid_user_id" }, 400);
    const nonce = bytesToBase64(crypto.getRandomValues(new Uint8Array(32)));
    server.serializeAttachment({ userId, nonce, authenticated: false });
    this.ctx.acceptWebSocket(server);
    server.send(JSON.stringify({ type: "challenge", nonce }));
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket, message) {
    if (typeof message !== "string" || message.length > MAX_SIGNAL_BYTES) return this.close(socket, 1009, "invalid_message");
    try {
      const attachment = socket.deserializeAttachment();
      const packet = parseJson(message);
      if (!packet || !attachment?.userId) return this.close(socket, 1008, "invalid_message");
      if (!attachment.authenticated) {
        if (packet.type !== "authenticate") return this.close(socket, 1008, "authentication_required");
        const ok = await this.authenticateSocket(socket, attachment, packet);
        if (!ok) this.close(socket, 1008, "authentication_failed");
        return;
      }
      if (packet.type === "signal") await this.routeSignal(socket, attachment.userId, packet);
      else if (packet.type === "register_push") await this.storePushToken(socket, attachment.userId, packet);
      else if (packet.type === "ping") socket.send(JSON.stringify({ type: "pong" }));
      else this.close(socket, 1008, "unsupported_message");
    } catch (error) {
      console.error(JSON.stringify({ event: "websocket_message_failed", error: error instanceof Error ? error.message : "unknown" }));
      try { socket.send(JSON.stringify({ type: "error", code: "temporary_failure" })); } catch { /* socket unavailable */ }
      this.close(socket, 1011, "temporary_failure");
    }
  }

  webSocketClose(socket) {
    try { socket.close(); } catch { /* already closed */ }
  }

  webSocketError(socket) {
    try { socket.close(1011, "socket_error"); } catch { /* already closed */ }
  }

  async alarm() {
    const pending = await this.ctx.storage.get("pending_offer");
    if (pending?.expiresAt <= Date.now()) await this.ctx.storage.delete("pending_offer");
    await this.prunePendingEvents();
    await this.scheduleNextAlarm();
  }

  async authenticateSocket(socket, attachment, packet) {
    const userId = attachment.userId;
    if (packet.userId !== userId || !isPublicKey(packet.identityKey) || !isSignature(packet.signature)) return false;
    const valid = await verifySignature(
      packet.identityKey,
      devicePayload("socket", userId, attachment.nonce, packet.identityKey),
      packet.signature,
    );
    if (!valid || !await this.acceptIdentity(userId, packet.identityKey)) return false;
    socket.serializeAttachment({ userId, authenticated: true });
    socket.send(JSON.stringify({ type: "ready", userId }));
    await this.sendPendingOffer(socket);
    return true;
  }

  async routeSignal(socket, from, packet) {
    const signal = packet.signal;
    if (!validateSignal(signal, from)) return this.close(socket, 1008, "invalid_signal");
    const signatureValid = await verifySignature(
      signal.identityKey,
      signalingPayload(signal, signal.identityKey),
      signal.signature,
    );
    if (!signatureValid) return this.close(socket, 1008, "invalid_signal_signature");
    const recipient = this.env.SIGNAL_HUB.getByName(`user:${signal.to}`);
    const response = await recipient.fetch(new Request("https://signal-hub/deliver", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(signal),
    }));
    if (!response.ok) return this.close(socket, 1011, "delivery_failed");
    const delivery = await response.json();
    socket.send(JSON.stringify({ type: "accepted", callId: signal.callId, delivered: delivery?.delivered === true }));
  }

  async deliver(request) {
    const raw = await readLimitedText(request, MAX_SIGNAL_BYTES);
    const signal = parseJson(raw);
    if (!validateSignal(signal, signal?.from)) return json({ error: "invalid_signal" }, 400);
    const targets = this.ctx.getWebSockets().filter((socket) => socket.deserializeAttachment()?.authenticated === true);
    let deliveredCount = 0;
    for (const socket of targets) {
      try {
        socket.send(JSON.stringify({ type: "signal", signal }));
        deliveredCount += 1;
      } catch (error) {
        console.log(JSON.stringify({ event: "socket_delivery_failed", type: signal.type, error: error instanceof Error ? error.name : "unknown" }));
      }
    }
    if (deliveredCount > 0) return json({ delivered: true });
    if (signal.type === "offer") {
      const pending = { signals: [signal], callId: signal.callId, expiresAt: Date.now() + OFFER_TTL_MS };
      await this.ctx.storage.put("pending_offer", pending);
      await this.scheduleNextAlarm();
      const pushToken = await this.ctx.storage.get("push_token");
      if (typeof pushToken === "string") await sendFcmWake(this.env, pushToken, signal);
    } else if (signal.type === "ice") {
      const pending = await this.ctx.storage.get("pending_offer");
      if (pending?.callId === signal.callId && pending.expiresAt > Date.now() && pending.signals.length < 64) {
        pending.signals.push(signal);
        await this.ctx.storage.put("pending_offer", pending);
      } else {
        await this.enqueueEvent(signal, CALL_EVENT_TTL_MS);
      }
    } else if (signal.type === "hangup" || signal.type === "reject") {
      const pending = await this.ctx.storage.get("pending_offer");
      if (pending?.callId === signal.callId) {
        await this.ctx.storage.delete("pending_offer");
        const pushToken = await this.ctx.storage.get("push_token");
        if (typeof pushToken === "string") await sendFcmWake(this.env, pushToken, signal);
      } else {
        await this.enqueueEvent(signal, CALL_EVENT_TTL_MS);
      }
      await this.scheduleNextAlarm();
    } else if (signal.type.startsWith("contact-")) {
      await this.enqueueEvent(signal, CONTACT_EVENT_TTL_MS);
      const pushToken = await this.ctx.storage.get("push_token");
      if (typeof pushToken === "string") await sendFcmWake(this.env, pushToken, signal);
    } else {
      await this.enqueueEvent(signal, CALL_EVENT_TTL_MS);
    }
    return json({ delivered: false });
  }

  async authorize(request) {
    const packet = parseJson(await request.text());
    const authorized = await this.verifyDeviceAuthorization(packet);
    return json({ authorized });
  }

  async registerDevice(request) {
    const packet = parseJson(await request.text());
    const authorized = await this.verifyDeviceAuthorization(packet, "device");
    if (!authorized || typeof packet.token !== "string" || packet.token.length < 20 || packet.token.length > 4096) {
      return json({ error: "unauthorized" }, 401);
    }
    await this.ctx.storage.put("push_token", packet.token);
    return json({ ok: true });
  }

  async verifyDeviceAuthorization(packet, requiredOperation = null) {
    if (!packet || !USER_ID.test(packet.userId || "") || !isPublicKey(packet.identityKey) || !isSignature(packet.signature)) return false;
    if (requiredOperation && packet.operation !== requiredOperation) return false;
    const timestamp = Number(packet.timestamp);
    if (!Number.isFinite(timestamp) || Math.abs(Date.now() - timestamp) > MAX_AUTH_AGE_MS) return false;
    // Never persist a first-use identity until possession of its private key is proven.
    const valid = await verifySignature(
      packet.identityKey,
      devicePayload(packet.operation, packet.userId, String(timestamp), packet.identityKey),
      packet.signature,
    );
    return valid && await this.acceptIdentity(packet.userId, packet.identityKey);
  }

  async acceptIdentity(userId, identityKey) {
    const stored = await this.ctx.storage.get("identity_key");
    if (stored && stored !== identityKey) return false;
    if (!stored) await this.ctx.storage.put("identity_key", identityKey);
    return true;
  }

  async sendPendingOffer(socket) {
    const pending = await this.ctx.storage.get("pending_offer");
    if (!pending || pending.expiresAt <= Date.now()) {
      if (pending) await this.ctx.storage.delete("pending_offer");
    } else {
      for (const signal of pending.signals) socket.send(JSON.stringify({ type: "signal", signal }));
      await this.ctx.storage.delete("pending_offer");
    }
    const events = await this.prunePendingEvents();
    if (events.length > 0) {
      for (const event of events) socket.send(JSON.stringify({ type: "signal", signal: event.signal }));
      await this.ctx.storage.delete("pending_events");
    }
    await this.scheduleNextAlarm();
  }

  async enqueueEvent(signal, ttlMs) {
    const now = Date.now();
    const events = await this.prunePendingEvents(now);
    const withoutDuplicate = filterPendingEvents(events, signal);
    withoutDuplicate.push({ signal, expiresAt: now + ttlMs });
    await this.ctx.storage.put("pending_events", withoutDuplicate.slice(-MAX_PENDING_EVENTS));
    await this.scheduleNextAlarm();
  }

  async prunePendingEvents(now = Date.now()) {
    const stored = await this.ctx.storage.get("pending_events");
    const events = Array.isArray(stored)
      ? stored.filter((event) => event?.signal && Number(event.expiresAt) > now).slice(-MAX_PENDING_EVENTS)
      : [];
    if (events.length === 0) await this.ctx.storage.delete("pending_events");
    else if (!Array.isArray(stored) || events.length !== stored.length) await this.ctx.storage.put("pending_events", events);
    return events;
  }

  async scheduleNextAlarm() {
    const pending = await this.ctx.storage.get("pending_offer");
    const events = await this.ctx.storage.get("pending_events");
    const expirations = [
      Number(pending?.expiresAt),
      ...(Array.isArray(events) ? events.map((event) => Number(event?.expiresAt)) : []),
    ].filter((value) => Number.isFinite(value) && value > Date.now());
    if (expirations.length === 0) await this.ctx.storage.deleteAlarm();
    else await this.ctx.storage.setAlarm(Math.min(...expirations));
  }

  async storePushToken(socket, userId, packet) {
    if (typeof packet.token !== "string" || packet.token.length < 20 || packet.token.length > 4096) return this.close(socket, 1008, "invalid_push_token");
    await this.ctx.storage.put("push_token", packet.token);
    socket.send(JSON.stringify({ type: "push_registered", userId }));
  }

  close(socket, code, reason) {
    try { socket.close(code, reason); } catch { /* already closed */ }
  }
}

export function validateSignal(signal, authenticatedFrom) {
  if (!signal || typeof signal !== "object" || Array.isArray(signal)) return false;
  if (!SIGNAL_TYPES.has(signal.type) || signal.from !== authenticatedFrom || !USER_ID.test(signal.from || "") || !USER_ID.test(signal.to || "")) return false;
  if (signal.from === signal.to) return false;
  if (typeof signal.callId !== "string" || !/^[A-Za-z0-9_-]{8,128}$/.test(signal.callId)) return false;
  if (!SESSION_KINDS.has(signal.sessionKind)) return false;
  if (signal.type.startsWith("contact-")) {
    if (signal.sessionKind !== "contact" || typeof signal.reason !== "string" || signal.reason.trim().length < 1 || signal.reason.length > 80) return false;
  } else if (signal.sessionKind === "contact") return false;
  if (["offer", "answer", "ice-restart-offer", "ice-restart-answer"].includes(signal.type)) {
    if (typeof signal.sdp !== "string" || signal.sdp.length < 1 || signal.sdp.length > 210_000) return false;
    if (!['offer', 'answer'].includes(signal.sdpType)) return false;
  }
  if (signal.type === "ice" && (typeof signal.candidate !== "string" || signal.candidate.length < 1 || signal.candidate.length > 8_192)) return false;
  if (!isPublicKey(signal.identityKey) || !isSignature(signal.signature)) return false;
  if (JSON.stringify(signal).length > MAX_SIGNAL_BYTES) return false;
  return true;
}

export function filterPendingEvents(events, signal) {
  return events.filter((event) => {
    const queued = event.signal;
    const sameRequest = queued?.callId === signal.callId && queued?.from === signal.from;
    const duplicate = sameRequest && queued?.type === signal.type;
    const cancelledInvitation = signal.type === "contact-cancel" && sameRequest && queued?.type === "contact-request";
    return !duplicate && !cancelledInvitation;
  });
}

function isPublicKey(value) { return typeof value === "string" && value.length >= 100 && value.length <= 512; }
function isSignature(value) { return typeof value === "string" && value.length >= 80 && value.length <= 512; }
function parseJson(value) { try { return JSON.parse(value); } catch { return null; } }
function json(body, status = 200) { return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } }); }

function signalingPayload(signal, publicKey) {
  return lengthPrefixed([
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
    publicKey,
  ]);
}

async function readLimitedText(request, limit) {
  const contentLength = Number(request.headers.get("content-length") || "0");
  if (Number.isFinite(contentLength) && contentLength > limit) return null;
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > limit) {
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
    return new TextDecoder().decode(bytes);
  } catch {
    return null;
  } finally {
    reader.releaseLock();
  }
}

function devicePayload(operation, userId, nonceOrTimestamp, publicKey) {
  return lengthPrefixed(["wideocall-device-auth-v1", operation, userId, nonceOrTimestamp, publicKey]);
}

function lengthPrefixed(values) {
  const encoded = values.map((value) => new TextEncoder().encode(value));
  const total = encoded.reduce((sum, value) => sum + 4 + value.length, 0);
  const output = new Uint8Array(total);
  const view = new DataView(output.buffer);
  let offset = 0;
  for (const value of encoded) {
    view.setUint32(offset, value.length);
    offset += 4;
    output.set(value, offset);
    offset += value.length;
  }
  return output;
}

async function verifySignature(publicKeyBase64, payload, signatureBase64) {
  try {
    const key = await crypto.subtle.importKey("spki", base64ToBytes(publicKeyBase64), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    return crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, derToP1363(base64ToBytes(signatureBase64)), payload);
  } catch { return false; }
}

function derToP1363(bytes) {
  let offset = 0;
  if (bytes[offset++] !== 0x30) throw new Error("invalid DER sequence");
  const sequenceLength = readDerLength(bytes, offset); offset = sequenceLength.next;
  if (offset + sequenceLength.length !== bytes.length) throw new Error("invalid DER length");
  if (bytes[offset++] !== 0x02) throw new Error("invalid DER r");
  const rLength = readDerLength(bytes, offset); offset = rLength.next;
  const r = bytes.slice(offset, offset + rLength.length); offset += rLength.length;
  if (bytes[offset++] !== 0x02) throw new Error("invalid DER s");
  const sLength = readDerLength(bytes, offset); offset = sLength.next;
  const s = bytes.slice(offset, offset + sLength.length);
  const output = new Uint8Array(64);
  output.set(normalizeInteger(r), 32 - normalizeInteger(r).length);
  output.set(normalizeInteger(s), 64 - normalizeInteger(s).length);
  return output;
}

function readDerLength(bytes, offset) {
  const first = bytes[offset++];
  if (first < 0x80) return { length: first, next: offset };
  const count = first & 0x7f;
  if (count < 1 || count > 2 || offset + count > bytes.length) throw new Error("invalid DER length");
  let length = 0;
  for (let index = 0; index < count; index += 1) length = (length << 8) | bytes[offset++];
  return { length, next: offset };
}

function normalizeInteger(value) {
  let offset = 0;
  while (offset < value.length - 1 && value[offset] === 0) offset += 1;
  const normalized = value.slice(offset);
  if (normalized.length > 32) throw new Error("invalid ECDSA integer");
  return normalized;
}

function base64ToBytes(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function bytesToBase64(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

async function sendFcmWake(env, token, signal) {
  if (!env.FIREBASE_SERVICE_ACCOUNT_JSON) return;
  try {
    const credentials = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
    const accessToken = await googleAccessToken(credentials);
    const type = fcmType(signal);
    if (!type) return;
    const contactEvent = signal.sessionKind === "contact";
    const response = await fetch(`https://fcm.googleapis.com/v1/projects/vidcall-88857/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({ message: { token, data: { type, callerId: signal.from, recipientId: signal.to, callId: signal.callId, sessionKind: signal.sessionKind }, android: { priority: "HIGH", ttl: contactEvent ? "86400s" : "35s", collapse_key: `${contactEvent ? "contact" : "call"}-${signal.callId}` } } }),
    });
    if (!response.ok) throw new Error(`FCM_${response.status}`);
  } catch (error) {
    console.log(JSON.stringify({ event: "wake_failed", signalType: signal.type, error: error instanceof Error ? error.message : "unknown" }));
  }
}

export function fcmType(signal) {
  if (signal.type === "offer") {
    if (signal.sessionKind === "chat") return "incoming_chat";
    if (signal.sessionKind === "file") return "incoming_file";
    return "incoming_call";
  }
  if (signal.type === "hangup" || signal.type === "reject") {
    if (signal.sessionKind === "chat") return "chat_cancelled";
    if (signal.sessionKind === "file") return "file_cancelled";
    return "call_cancelled";
  }
  if (signal.type === "contact-request") return "contact_request";
  if (signal.type === "contact-accept") return "contact_accepted";
  if (signal.type === "contact-reject") return "contact_rejected";
  if (signal.type === "contact-cancel") return "contact_cancelled";
  return null;
}

async function googleAccessToken(credentials) {
  const now = Math.floor(Date.now() / 1000);
  const claim = { iss: credentials.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 };
  const unsigned = `${base64UrlJson({ alg: "RS256", typ: "JWT" })}.${base64UrlJson(claim)}`;
  const key = await crypto.subtle.importKey("pkcs8", pemToArrayBuffer(credentials.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const response = await fetch("https://oauth2.googleapis.com/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}` }) });
  if (!response.ok) throw new Error(`oauth_${response.status}`);
  return (await response.json()).access_token;
}

function base64UrlJson(value) { return base64UrlBytes(new TextEncoder().encode(JSON.stringify(value))); }
function base64UrlBytes(bytes) { let binary = ""; for (const byte of bytes) binary += String.fromCharCode(byte); return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_"); }
function pemToArrayBuffer(pem) { return base64ToBytes(pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "")).buffer; }

import crypto from "node:crypto";
import http from "node:http";

const port = Number(process.env.PORT || 8080);
const clients = new Map();
const allowedTypes = new Set(["offer", "answer", "ice", "hangup", "reject", "busy"]);
const idPattern = /^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/;
const webSocketGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const maxPayload = 128 * 1024;

const server = http.createServer((request, response) => {
  response.setHeader("content-type", "application/json; charset=utf-8");
  response.setHeader("cache-control", "no-store");
  response.end(JSON.stringify({
    service: "wideocall-signaling",
    status: "ok",
    connectedDevices: clients.size,
  }));
});

server.on("upgrade", (request, socket) => {
  const key = request.headers["sec-websocket-key"];
  const upgrade = String(request.headers.upgrade || "").toLowerCase();
  if (upgrade !== "websocket" || typeof key !== "string") {
    socket.destroy();
    return;
  }

  const url = new URL(request.url || "/", `http://${request.headers.host || "localhost"}`);
  const userId = String(url.searchParams.get("userId") || "").toUpperCase();
  const name = String(url.searchParams.get("name") || userId).slice(0, 80);
  if (!idPattern.test(userId)) {
    socket.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }

  const accept = crypto.createHash("sha1").update(key + webSocketGuid).digest("base64");
  socket.write([
    "HTTP/1.1 101 Switching Protocols",
    "Upgrade: websocket",
    "Connection: Upgrade",
    `Sec-WebSocket-Accept: ${accept}`,
    "\r\n",
  ].join("\r\n"));

  const previous = clients.get(userId);
  if (previous) closeClient(previous, 4001, "connected on another device");

  const client = { socket, userId, name, buffer: Buffer.alloc(0), alive: true };
  clients.set(userId, client);
  sendJson(client, { type: "registered", userId });

  socket.on("data", (chunk) => {
    client.buffer = Buffer.concat([client.buffer, chunk]);
    parseFrames(client);
  });
  socket.on("error", () => removeClient(client));
  socket.on("close", () => removeClient(client));
});

function parseFrames(client) {
  while (client.buffer.length >= 2) {
    const first = client.buffer[0];
    const second = client.buffer[1];
    const finalFrame = (first & 0x80) !== 0;
    const opcode = first & 0x0f;
    const masked = (second & 0x80) !== 0;
    let payloadLength = second & 0x7f;
    let offset = 2;

    if (!finalFrame || !masked) return closeClient(client, 1002, "unsupported frame");
    if (payloadLength === 126) {
      if (client.buffer.length < 4) return;
      payloadLength = client.buffer.readUInt16BE(2);
      offset = 4;
    } else if (payloadLength === 127) {
      if (client.buffer.length < 10) return;
      const length = client.buffer.readBigUInt64BE(2);
      if (length > BigInt(maxPayload)) return closeClient(client, 1009, "message too large");
      payloadLength = Number(length);
      offset = 10;
    }

    if (payloadLength > maxPayload) return closeClient(client, 1009, "message too large");
    if (client.buffer.length < offset + 4 + payloadLength) return;

    const mask = client.buffer.subarray(offset, offset + 4);
    offset += 4;
    const payload = Buffer.from(client.buffer.subarray(offset, offset + payloadLength));
    for (let index = 0; index < payload.length; index += 1) {
      payload[index] ^= mask[index % 4];
    }
    client.buffer = client.buffer.subarray(offset + payloadLength);

    if (opcode === 0x8) return closeClient(client, 1000, "closed");
    if (opcode === 0x9) {
      client.socket.write(frame(0xA, payload));
      continue;
    }
    if (opcode === 0xA) {
      client.alive = true;
      continue;
    }
    if (opcode !== 0x1) continue;
    handleMessage(client, payload.toString("utf8"));
  }
}

function handleMessage(client, raw) {
  let message;
  try {
    message = JSON.parse(raw);
  } catch {
    closeClient(client, 1003, "invalid json");
    return;
  }

  const type = String(message.type || "");
  const targetId = String(message.to || "").toUpperCase();
  if (!allowedTypes.has(type) || !idPattern.test(targetId)) return;

  const target = clients.get(targetId);
  if (!target || target.socket.destroyed) {
    if (type === "offer") {
      sendJson(client, {
        type: "unavailable",
        from: targetId,
        to: client.userId,
        callId: message.callId,
      });
    }
    return;
  }

  sendJson(target, {
    ...message,
    type,
    from: client.userId,
    to: targetId,
    callerName: client.name,
  });
}

function sendJson(client, value) {
  if (!client.socket.destroyed) client.socket.write(frame(0x1, Buffer.from(JSON.stringify(value), "utf8")));
}

function frame(opcode, payload) {
  const length = payload.length;
  if (length < 126) return Buffer.concat([Buffer.from([0x80 | opcode, length]), payload]);
  if (length <= 0xffff) {
    const header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(length, 2);
    return Buffer.concat([header, payload]);
  }
  const header = Buffer.alloc(10);
  header[0] = 0x80 | opcode;
  header[1] = 127;
  header.writeBigUInt64BE(BigInt(length), 2);
  return Buffer.concat([header, payload]);
}

function closeClient(client, code, reason) {
  if (client.socket.destroyed) return;
  const reasonBuffer = Buffer.from(reason, "utf8").subarray(0, 123);
  const payload = Buffer.alloc(2 + reasonBuffer.length);
  payload.writeUInt16BE(code, 0);
  reasonBuffer.copy(payload, 2);
  client.socket.end(frame(0x8, payload));
  removeClient(client);
}

function removeClient(client) {
  if (clients.get(client.userId) === client) clients.delete(client.userId);
}

const heartbeat = setInterval(() => {
  for (const client of clients.values()) {
    if (!client.alive) {
      client.socket.destroy();
      removeClient(client);
      continue;
    }
    client.alive = false;
    client.socket.write(frame(0x9, Buffer.alloc(0)));
  }
}, 30_000);

server.listen(port, "0.0.0.0", () => {
  console.log(`Wideocall signaling is listening on port ${port}`);
});

function shutdown() {
  clearInterval(heartbeat);
  for (const client of clients.values()) closeClient(client, 1001, "server shutdown");
  server.close(() => process.exit(0));
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

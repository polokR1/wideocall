const baseUrl = process.env.SERVER_URL || "ws://127.0.0.1:8080";

function openClient(userId) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${baseUrl}?userId=${userId}&name=${userId}`);
    const messages = [];
    socket.addEventListener("message", (event) => messages.push(JSON.parse(event.data)));
    socket.addEventListener("open", () => resolve({ socket, messages }));
    socket.addEventListener("error", reject);
  });
}

function waitFor(client, type, timeout = 2_000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(() => {
      const message = client.messages.find((item) => item.type === type);
      if (message) {
        clearInterval(timer);
        resolve(message);
      } else if (Date.now() - started > timeout) {
        clearInterval(timer);
        reject(new Error(`Timeout waiting for ${type}`));
      }
    }, 10);
  });
}

const caller = await openClient("ABCD-EFGH");
const receiver = await openClient("JKLM-NPQR");
await Promise.all([waitFor(caller, "registered"), waitFor(receiver, "registered")]);

caller.socket.send(JSON.stringify({
  type: "offer",
  to: "JKLM-NPQR",
  callId: "test-call",
  sdpType: "offer",
  sdp: "test-offer",
}));
const offer = await waitFor(receiver, "offer");
if (offer.from !== "ABCD-EFGH" || offer.sdp !== "test-offer") throw new Error("Offer routing failed");

receiver.socket.send(JSON.stringify({
  type: "answer",
  to: "ABCD-EFGH",
  callId: "test-call",
  sdpType: "answer",
  sdp: "test-answer",
}));
const answer = await waitFor(caller, "answer");
if (answer.from !== "JKLM-NPQR" || answer.sdp !== "test-answer") throw new Error("Answer routing failed");

caller.socket.close();
receiver.socket.close();
console.log("Signaling test passed");

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

test("incoming call payload uses only identifiers", () => {
  const allowed = new Set(["type", "callerId", "recipientId", "callId", "sessionKind"]);
  const payload = { type: "incoming_call", callerId: "ABCD-EFGH", recipientId: "JKLM-NPQR", callId: "uuid", sessionKind: "audio" };
  assert.deepEqual(new Set(Object.keys(payload)), allowed);
  assert.equal(Object.values(payload).some((value) => String(value).includes("sdp")), false);
});

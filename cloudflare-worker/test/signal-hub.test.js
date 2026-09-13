import test from "node:test";
import assert from "node:assert/strict";
import { fcmType, filterPendingEvents, validateSignal } from "../src/signal-hub.js";

const cancellation = {
  type: "contact-cancel",
  from: "ABCD-EF23",
  to: "WXYZ-4567",
  callId: "contact-request-123456",
  sessionKind: "contact",
  reason: "Telefon testowy",
  identityKey: "a".repeat(100),
  signature: "b".repeat(80),
};

test("accepts a signed contact cancellation and maps its push type", () => {
  assert.equal(validateSignal(cancellation, cancellation.from), true);
  assert.equal(fcmType(cancellation), "contact_cancelled");
});

test("rejects a cancellation without a display name", () => {
  assert.equal(validateSignal({ ...cancellation, reason: "" }, cancellation.from), false);
});

test("cancellation removes the matching queued invitation only", () => {
  const matching = { signal: { type: "contact-request", callId: cancellation.callId, from: cancellation.from } };
  const unrelated = { signal: { type: "contact-request", callId: "other-request", from: cancellation.from } };
  assert.deepEqual(filterPendingEvents([matching, unrelated], cancellation), [unrelated]);
});

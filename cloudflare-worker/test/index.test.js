import test from "node:test";
import assert from "node:assert/strict";
import { isMatchingFreshOffer, validateCallRequest } from "../src/index.js";

const body = {
  recipientId: "ABCD-EF23",
  callerId: "WXYZ-4567",
  callId: "ab12cd34-ef56-7890-ab12-cd34ef567890",
  signalId: "-Oabc_DEF123456789",
  sessionKind: "video",
};

test("accepts a valid notification request", () => {
  assert.equal(validateCallRequest(body), null);
});

test("rejects invalid identifiers and self calls", () => {
  assert.equal(validateCallRequest({ ...body, recipientId: "bad" }), "invalid_recipient_id");
  assert.equal(validateCallRequest({ ...body, recipientId: body.callerId }), "cannot_call_self");
  assert.equal(validateCallRequest({ ...body, sessionKind: "message" }), "invalid_session_kind");
});

test("requires a fresh offer owned by the authenticated Firebase user", () => {
  const now = 1_800_000_000_000;
  const signal = {
    type: "offer",
    from: body.callerId,
    to: body.recipientId,
    callId: body.callId,
    sessionKind: body.sessionKind,
    senderUid: "firebase-uid",
    createdAt: now - 1_000,
  };
  assert.equal(isMatchingFreshOffer(signal, body, "firebase-uid", now), true);
  assert.equal(isMatchingFreshOffer(signal, body, "other-uid", now), false);
  assert.equal(isMatchingFreshOffer({ ...signal, sessionKind: "chat" }, body, "firebase-uid", now), false);
  assert.equal(isMatchingFreshOffer({ ...signal, createdAt: now - 61_000 }, body, "firebase-uid", now), false);
});

test("accepts chat invitations and keeps legacy video requests compatible", () => {
  assert.equal(validateCallRequest({ ...body, sessionKind: "chat" }), null);
  const legacyBody = { ...body };
  delete legacyBody.sessionKind;
  assert.equal(validateCallRequest(legacyBody), null);
});

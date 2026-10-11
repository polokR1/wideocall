"use strict";

const { onValueCreated } = require("firebase-functions/v2/database");
const { logger } = require("firebase-functions");
const { initializeApp } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

exports.notifyIncomingCall = onValueCreated(
  {
    ref: "/signals/{recipientId}/{messageId}",
    instance: "vidcall-88857-default-rtdb",
    region: "europe-west1",
    memory: "256MiB",
    timeoutSeconds: 30,
  },
  async (event) => {
    const signal = event.data.val();
    if (!signal || signal.type !== "offer") return;

    const recipientId = event.params.recipientId;
    const callerId = String(signal.from || "");
    const callId = String(signal.callId || "");
    if (!callerId || !callId || signal.to !== recipientId) return;
    const createdAt = Number(signal.createdAt || 0);
    if (!createdAt || Math.abs(Date.now() - createdAt) > 45_000) return;

    const deviceRef = getDatabase().ref(`/devices/${recipientId}`);
    const deviceSnapshot = await deviceRef.get();
    const token = deviceSnapshot.child("token").val();
    if (typeof token !== "string" || token.length < 20) {
      logger.info("No push token for incoming call", { recipientId, callId });
      return;
    }

    try {
      await getMessaging().send({
        token,
        data: {
          type: "incoming_call",
          callerId,
          recipientId,
          callId,
          sessionKind: signal.sessionKind === "audio" ? "audio" : "video",
        },
        android: {
          priority: "high",
          ttl: 30_000,
          collapseKey: `call-${callId}`,
        },
      });
    } catch (error) {
      const code = error && error.code;
      if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
        await Promise.all([
          deviceRef.remove(),
          getDatabase().ref(`/users/${recipientId}/pushReady`).set(false),
        ]);
      }
      logger.error("Unable to send incoming call notification", { recipientId, callId, code });
      throw error;
    }
  },
);

import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const WORKER_URL = process.env.WIDEOCALL_WORKER_URL ||
  "https://wideocall-notify.wideocall-polokr1.workers.dev";
const PACKAGE_NAME = "pl.wideocall.app";

const config = JSON.parse(
  await readFile(new URL("../../app/google-services.json", import.meta.url), "utf8"),
);
const client = config.client.find(
  (entry) => entry.client_info?.android_client_info?.package_name === PACKAGE_NAME,
);
const apiKey = client?.api_key?.[0]?.current_key;
assert.equal(typeof apiKey, "string", "Firebase Web API key is missing");

let idToken = null;
let deletionStatus = null;

try {
  const signUpResponse = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${encodeURIComponent(apiKey)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ returnSecureToken: true }),
    },
  );
  assert.equal(signUpResponse.status, 200, `Firebase anonymous auth failed: ${signUpResponse.status}`);
  const signUp = await signUpResponse.json();
  idToken = signUp.idToken;
  assert.equal(typeof idToken, "string", "Firebase did not return an ID token");

  const turnResponse = await fetch(`${WORKER_URL}/turn-credentials`, {
    method: "POST",
    headers: { Authorization: `Bearer ${idToken}` },
  });
  assert.equal(turnResponse.status, 200, `TURN endpoint failed: ${turnResponse.status}`);
  const turn = await turnResponse.json();
  assert.ok(Array.isArray(turn.iceServers), "TURN response has no ICE server list");
  const relay = turn.iceServers.find(
    (server) => server.username && server.credential &&
      server.urls?.some((url) => url.startsWith("turn:") || url.startsWith("turns:")),
  );
  assert.ok(relay, "TURN response has no authenticated relay server");
  assert.ok(turn.expiresAt > Date.now() + 23 * 60 * 60 * 1000, "TURN credentials expire too soon");

  console.log(JSON.stringify({
    ok: true,
    firebaseAnonymousAuth: true,
    workerAuthenticated: true,
    turnCredentialsIssued: true,
    iceServerCount: turn.iceServers.length,
    relayUrlCount: relay.urls.length,
    ttlHours: Math.round((turn.expiresAt - Date.now()) / 3_600_000),
  }));
} finally {
  if (idToken) {
    const deletionResponse = await fetch(
      `https://identitytoolkit.googleapis.com/v1/accounts:delete?key=${encodeURIComponent(apiKey)}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ idToken }),
      },
    );
    deletionStatus = deletionResponse.status;
  }
  if (deletionStatus !== null && deletionStatus !== 200) {
    console.error(`Temporary Firebase user cleanup failed: ${deletionStatus}`);
    process.exitCode = 1;
  }
}

# Wideocall notification gateway

Cloudflare Worker sends a high-priority FCM data message only after it verifies a fresh WebRTC video or chat offer in Firebase Realtime Database. Video, audio, SDP and chat content do not pass through Cloudflare.

Android clients also forward `X-Firebase-AppCheck` when Play Integrity attestation is available. The Worker passes this header to Firebase Authentication while validating the anonymous ID token, so Firebase Auth enforcement can later reject untrusted app installations without embedding any attestation secret in the APK.

Required Worker secrets:

- `FIREBASE_WEB_API_KEY` — the Web API key from `app/google-services.json`.
- `FIREBASE_SERVICE_ACCOUNT_JSON` — a Firebase Admin SDK service-account JSON. Keep it out of Git.
- `TURN_KEY_ID` — identifier of the dedicated production Cloudflare Realtime TURN key.
- `TURN_KEY_API_TOKEN` — secret belonging to that TURN key. Keep it only in Worker Secrets.

The service account needs permission to read Realtime Database and to create Firebase Cloud Messaging messages. For a hardened production setup, replace the default Admin SDK account with a dedicated account using the narrowest available roles. Rotate the key if it is ever copied outside Cloudflare Secrets, and never bundle it in the APK.

Endpoints:

- `GET /health` — gateway status without exposing secrets.
- `POST /notify-call` — authenticated video/chat wake request from the Android app. `sessionKind` is limited to `video` or `chat`.
- `POST /turn-credentials` — returns 24-hour, short-lived Cloudflare TURN credentials to an authenticated Firebase installation. Long-term TURN secrets never reach the APK.

Local checks:

```sh
npm test
npm run test:firebase-rules
npm run test:turn-live
```

The live TURN check creates a temporary anonymous Firebase user, requests
short-lived relay credentials from the deployed Worker, verifies their shape
without printing them, and deletes the temporary user.

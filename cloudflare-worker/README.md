# Wideocall notification gateway

Cloudflare Worker sends a high-priority FCM data message only after it verifies a fresh WebRTC offer in Firebase Realtime Database. Video, audio, SDP and chat content do not pass through Cloudflare.

Required Worker secrets:

- `FIREBASE_WEB_API_KEY` — the Web API key from `app/google-services.json`.
- `FIREBASE_SERVICE_ACCOUNT_JSON` — a Firebase Admin SDK service-account JSON. Keep it out of Git.

The service account needs permission to read Realtime Database and to create Firebase Cloud Messaging messages. For a hardened production setup, replace the default Admin SDK account with a dedicated account using the narrowest available roles. Rotate the key if it is ever copied outside Cloudflare Secrets, and never bundle it in the APK.

Endpoints:

- `GET /health` — gateway status without exposing secrets.
- `POST /notify-call` — authenticated call-wake request from the Android app.

Local checks:

```sh
npm test
```

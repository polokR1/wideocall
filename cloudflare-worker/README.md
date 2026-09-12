# Wideocall Cloudflare signaling gateway

Worker zapewnia sygnalizację WebRTC przez Durable Objects oraz krótkotrwałe dane Cloudflare TURN. Nie przesyła obrazu, dźwięku ani treści czatu.

Każdy identyfikator Wideocall ma własny Durable Object i hibernowalny WebSocket. Telefon najpierw podpisuje wyzwanie ECDSA P-256 z Android Keystore. Pierwszy prawidłowy klucz dla danego identyfikatora jest przypinany po stronie Durable Object; późniejsza zmiana jest odrzucana. Wiadomości sygnalizacyjne są przekazywane natychmiast do aktywnego WebSocketu. Dla zamkniętej aplikacji oferta i kandydaci ICE są przechowywane najwyżej 45 sekund, po czym Worker wysyła FCM jako wybudzenie.

Wymagane sekrety Workera:

- `FIREBASE_SERVICE_ACCOUNT_JSON` — konto serwisowe tylko do wysłania FCM.
- `TURN_KEY_ID` — identyfikator produkcyjnego klucza Cloudflare TURN.
- `TURN_KEY_API_TOKEN` — sekret tego klucza.
- `FIREBASE_WEB_API_KEY` — zachowany wyłącznie dla starszego endpointu `/notify-call`; aktualny Android go nie używa.

Endpointy:

- `GET /health`
- `GET /signal/{id}` — WebSocket, po otwarciu wymagany podpisany komunikat `authenticate`.
- `POST /device` — podpisana rejestracja tokenu FCM.
- `POST /turn-credentials` — podpisane żądanie 24-godzinnych danych TURN.

Lokalne i produkcyjne testy:

```sh
npm test
npm run test:signal-live
npm run test:turn-live
```

Testy produkcyjne korzystają z losowych tymczasowych identyfikatorów, weryfikują uwierzytelnienie WebSocketu i kształt odpowiedzi TURN, ale nie zastępują testu rozmowy na dwóch telefonach.

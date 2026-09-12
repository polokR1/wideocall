# Wideocall

Prywatny wideotelefon 1:1 na Androida: rozmowy wideo i audio oraz osobny, znikający czat. Nie wymaga konta, numeru telefonu ani logowania Google — każda instalacja ma własny identyfikator `XXXX-XXXX`.

## Aktualna architektura

- WebRTC przesyła zaszyfrowany obraz i dźwięk bezpośrednio między telefonami, a Cloudflare TURN jest używany tylko wtedy, gdy bezpośrednia droga przez NAT lub sieć komórkową nie działa.
- Sygnalizacja WebRTC (oferta, odpowiedź, kandydaci ICE) idzie przez WebSocket Cloudflare Durable Objects. Aplikacja nie używa Firebase Realtime Database ani Firebase Authentication w aktywnym torze rozmowy.
- Trwałe połączenie WebSocket utrzymuje hibernowalny WebSocket Durable Object; nie powoduje ciągłych odczytów Firebase ani stałego powiadomienia czuwania.
- Gdy odbiorca zamknął aplikację, Worker przechowuje ofertę sygnalizacyjną maksymalnie 45 sekund i wysyła FCM wyłącznie jako sygnał wybudzenia. Firebase Cloud Messaging pozostaje konieczny, bo Android nie pozwala aplikacji samodzielnie wybudzić innego zamkniętego telefonu.
- Każdy telefon podpisuje tożsamość i sygnały kluczem ECDSA P-256 z Android Keystore. Pierwszy klucz kontaktu jest przypinany lokalnie (TOFU), a jego późniejsza zmiana zatrzymuje połączenie. Kod bezpieczeństwa pozwala ręcznie porównać tożsamości.
- Czat używa szyfrowanego WebRTC DataChannel, nie ma bazy ani historii, a wiadomości znikają po 60 sekundach. Ekran czatu blokuje systemowe zrzuty i nagrywanie ekranu; nie chroni to przed zdjęciem ekranu drugim urządzeniem ani zmodyfikowanym systemem.
- Na Wi-Fi aplikacja używa 720p/30 kl./s. Na sieci komórkowej startuje od 360p/15 kl./s i 350 kb/s, aby priorytetem była płynność; WebRTC może później dostosować bitrate. Tor głosowy używa trybu rozmowy, sprzętowego usuwania echa/szumu i niskich opóźnień, jeśli wspiera je telefon.
- Android Core Telecom integruje aktywną rozmowę z systemowym ekranem połączenia, Bluetooth i regulacją mikrofonu. Podczas aktywnej kamery/mikrofonu Android wymaga widocznego powiadomienia rozmowy — nie jest to opcjonalne ustawienie aplikacji.

## Cloudflare Worker

Kod jest w `cloudflare-worker/`, a produkcyjny Worker działa pod adresem `https://wideocall-notify.wideocall-polokr1.workers.dev`.

Worker wymaga sekretów `FIREBASE_SERVICE_ACCOUNT_JSON` (wyłącznie FCM), `TURN_KEY_ID` i `TURN_KEY_API_TOKEN`. Klucze TURN nigdy nie trafiają do APK. `FIREBASE_WEB_API_KEY` jest zachowany tylko dla zgodności ze starym endpointem powiadomienia i nie jest używany przez aktualny klient Android.

Endpointy:

- `GET /health` — stan konfiguracji bez sekretów.
- `GET /signal/{id}` — uwierzytelniony WebSocket sygnalizacji.
- `POST /device` — podpisana rejestracja tokenu FCM telefonu.
- `POST /turn-credentials` — podpisane żądanie krótkotrwałych danych Cloudflare TURN.

## Budowanie

Wymagane są JDK 17 i Android SDK 36.

```powershell
./gradlew.bat test lint assembleDebug
```

APK jest tworzone w `app/build/outputs/apk/debug/app-debug.apk`.

## Co nadal trzeba sprawdzić przed publicznym wydaniem

- test na dwóch fizycznych telefonach: Wi-Fi, LTE/5G, przejście Wi-Fi ↔ LTE, Bluetooth oraz ekran blokady;
- dostarczanie FCM po ubiciu aplikacji przez konkretnego producenta telefonu i po wyłączeniu optymalizacji baterii;
- ręczne porównanie kodu bezpieczeństwa przy pierwszym kontakcie.

Nie należy uruchamiać równocześnie starego katalogu `signaling-server/`; aktualna aplikacja korzysta z Cloudflare.

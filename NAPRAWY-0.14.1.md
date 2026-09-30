# Wideocall 0.14.1 (20) — wybudzanie telefonu

## Przyczyna i zmiana

Test fizyczny 0.14.0 na S23 Ultra / Android 16 wykazał: po odebraniu obraz i głos działały, ale użytkownik nie dostał dzwonka ani ekranu połączenia na blokadzie. Uprawnienia powiadomień i pełnego ekranu były nadane, kanał dzwonka aktywny, głośność niezerowa, DND wyłączone.

W kodzie SignalHub znaleziono wczesny powrót po `socket.send()`, pomijający FCM. Zapis na serwerowym WebSocket nie dowodzi, że uśpiony Android odebrał sygnał. Jest to naprawiony błąd kodu; sam pierwszy test nie dowodzi, że był jedyną przyczyną zachowania telefonu.

- Oferta jest zapisywana przed wysyłką, także gdy istnieje socket odbiorcy. Wybudzenie FCM HIGH jest wysyłane niezależnie od obecności WebSocket.
- Rozłączenie/odrzucenie usuwa pasującą oczekującą ofertę i również uruchamia sygnał anulowania FCM. Podpisana odpowiedź odbiorcy usuwa jego własną ofertę do odtworzenia.
- Dane ICE można odtworzyć przy ponownym połączeniu. Ich przesyłanie nie powoduje serii powiadomień FCM.
- Wybudzanie używa `ctx.waitUntil`, żeby OAuth/FCM nie blokował odpowiedzi sygnalizacji. Żądania Google mają timeout 5 sekund. Błędy i sukcesy są rozróżniane w logach bez tokenów, SDP i tekstów wiadomości; `wake_sent` oznacza przyjęcie przez FCM, nie dowód odebrania przez telefon.
- APK 0.14.1 pomija ponowny `notify()` tego samego połączenia z FCM/WebSocket. Kombinacja INSISTENT + ONLY_ALERT_ONCE przy ponowieniu może wyciszyć istniejący dzwonek systemowy; sprawdzamy duplikat przed wysłaniem powiadomienia, również zanim lista systemowa zostanie zaktualizowana.
- Diagnostyka Androida `WideocallPush` i `CallNotifications` zawiera tylko zdarzenia, priorytet FCM i stan foreground; nie zapisuje treści, identyfikatorów rozmówcy ani tokenów.

## Weryfikacja 2026-09-30

- Worker: 23/23 testy Node, w tym 8 nowych testów wybudzania, online/offline, odtworzenia, anulowania, odpowiedzi, obsługi błędów i prywatności payloadu. To testy jednostkowe z atrapami sieci/storage, nie test pełnego runtime Cloudflare.
- Dry-run i wdrożenie Wrangler 4.145.0: sukces. Bieżące definicje API sprawdzone z workers-types 5.20260930.2; nie zmieniono migracji ani wiązań.
- Wdrożony Worker: `b5944ef6-035e-4a07-9882-5377844ede78`.
- Live health, sygnalizacja (5 testowych WebSocketów) oraz wydawanie TURN: sukces. Testy nie używały identyfikatorów realnych kontaktów i nie ujawniały danych TURN.
- Android: assembleDebug/testDebugUnitTest/lintDebug/assembleDebugAndroidTest: sukces. 32 testy JVM, 5 testów instrumentalnych wyłącznie na emulatorze API 36.
- Lint: 0 błędów, 24 ostrzeżenia.
- APK 0.14.1 (20), certyfikat zgodny z poprzednimi wersjami, podpis zweryfikowany. SHA-256: `5047529EBA00176BBF6CD58FEF9916B231BBD0E4A4487C76B3869F23414BBCEB`.
- S23 Ultra `SM-S918B`: instalacja `adb install -r` udana, versionName 0.14.1 / code 20 potwierdzone. Nie odinstalowywano aplikacji i nie kasowano danych użytkownika.
- Ponowny fizyczny test przy zablokowanym ekranie jest jeszcze do potwierdzenia przez użytkownika. Logi Cloudflare i aplikacji uruchomiono; samo powodzenie kompilacji i przyjęcie wiadomości przez FCM nie stanowi potwierdzenia dzwonka/wybudzenia.

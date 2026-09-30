# Wideocall

Prywatny komunikator 1:1 na Androida: rozmowy wideo i audio, osobny znikający czat oraz bezpośredni transfer plików. Nie wymaga konta, numeru telefonu ani logowania Google — każda instalacja ma własny identyfikator `XXXX-XXXX`.

## Aktualna architektura

- WebRTC przesyła zaszyfrowany obraz i dźwięk bezpośrednio między telefonami, a Cloudflare TURN jest używany tylko wtedy, gdy bezpośrednia droga przez NAT lub sieć komórkową nie działa.
- Sygnalizacja WebRTC (oferta, odpowiedź, kandydaci ICE) idzie przez WebSocket Cloudflare Durable Objects. Aplikacja nie używa Firebase Realtime Database ani Firebase Authentication w aktywnym torze rozmowy.
- Trwałe połączenie WebSocket utrzymuje hibernowalny WebSocket Durable Object; nie powoduje ciągłych odczytów Firebase ani stałego powiadomienia czuwania.
- Gdy odbiorca zamknął aplikację, Worker przechowuje ofertę sygnalizacyjną maksymalnie 45 sekund i wysyła FCM wyłącznie jako sygnał wybudzenia. Firebase Cloud Messaging pozostaje konieczny, bo Android nie pozwala aplikacji samodzielnie wybudzić innego zamkniętego telefonu.
- Każdy telefon podpisuje tożsamość i sygnały kluczem ECDSA P-256 z Android Keystore. Pierwszy klucz kontaktu jest przypinany lokalnie (TOFU), a jego późniejsza zmiana zatrzymuje połączenie. Dotknięcie złotej kłódki pokazuje wspólny numer bezpieczeństwa do porównania przez niezależny zaufany kanał. Zielona ikona oznacza ręczne potwierdzenie zgodności numerów, nie sam fakt szyfrowania.
- Kontakty dodaje się przez podpisane zaproszenie. Odbiorca sam je akceptuje albo odrzuca, a nadawca może cofnąć oczekujące zaproszenie; anulowanie usuwa je również z drugiego telefonu i jego powiadomień. Zdarzenia oczekujące są szyfrowane w transporcie i przechowywane w Durable Object najwyżej 7 dni.
- Czat używa szyfrowanego WebRTC DataChannel, nie ma bazy ani historii. Zaproszenie i nowa wiadomość najpierw trafiają do prywatnego powiadomienia, które otwiera osobny pełnoekranowy widok czatu także podczas rozmowy. Minimalizacja jest lokalna i nie zamyka czatu drugiej osobie. Wiadomość znika na danym telefonie 60 sekund po jej otwarciu. Ekran czatu blokuje systemowe zrzuty i nagrywanie ekranu; nie chroni to przed zdjęciem ekranu drugim urządzeniem ani zmodyfikowanym systemem.
- Pliki do 100 MB są strumieniowane w osobnej sesji przez niezawodny WebRTC DataChannel. Treść pliku nie trafia do Firebase, Durable Object ani żadnej bazy. TURN może przekazywać wyłącznie zaszyfrowane pakiety, gdy telefony nie mogą połączyć się bezpośrednio. Odbiornik weryfikuje rozmiar i sumę SHA-256 przed udostępnieniem przycisku zapisu.
- Identyfikatory osób dzwoniących są zapisywane wyłącznie w lokalnej historii urządzenia. Nieznanego rozmówcę można dodać do kontaktów bez przechodzenia przez zaproszenie; historia nie jest synchronizowana z serwerem.
- Na Wi-Fi aplikacja używa 720p/30 kl./s. Na sieci komórkowej startuje od 360p/24 kl./s i 400 kb/s, aby ruch był płynniejszy bez nadmiernego obciążania łącza; WebRTC dynamicznie obniża jakość przy słabym zasięgu. Bufor audio, stałe zbieranie ICE i szybkie ponawianie sygnalizacji ograniczają przerwy przy zmianie sieci. Tor głosowy używa trybu rozmowy, sprzętowego usuwania echa/szumu i niskich opóźnień, jeśli wspiera je telefon.
- Android Core Telecom integruje aktywną rozmowę z systemowym ekranem połączenia, Bluetooth, głośnikiem i regulacją mikrofonu. Wyjście audio można przełączyć z ekranu rozmowy, a błędy mikrofonu lub odtwarzania są pokazywane w diagnostyce. Podczas aktywnej kamery/mikrofonu Android wymaga widocznego powiadomienia rozmowy — nie jest to opcjonalne ustawienie aplikacji.
- Połączenie wychodzące ma lokalny sygnał oczekiwania, a połączenie przychodzące korzysta z wysokopriorytetowego kanału dzwonka. Kamera i mikrofon są wymagane automatycznie przy pierwszej próbie wykonania lub odebrania rozmowy; głośnik nie ma w Androidzie osobnego uprawnienia.
- Podczas dzwonienia, łączenia i aktywnej rozmowy aplikacja utrzymuje ekran włączony. Po rozłączeniu od razu przywraca systemowe ustawienie wygaszania.
- Przycisk Wstecz z rozmowy lub czatu wraca do ekranu głównego bez natychmiastowego zamykania aplikacji. Dopiero Wstecz na ekranie głównym wyświetla potwierdzenie wyjścia.

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

## Poprawki 0.13.1 (18)

- Backend nie zapisuje klucza po odrzuconym podpisie; istniejące poprawnie przypięte tożsamości pozostają chronione przed podmianą.
- Odświeżanie i ponawianie sygnalizacji zachowuje aktywną sesję. Zakończenie rozmowy bez sygnalizacji nie pokazuje fałszywej gotowości i nie zatrzymuje ponawiania.
- Limit 35 sekund na zestawienie sesji obowiązuje również odbierającego, dla rozmów, czatu i transferów.
- Nieudana wysyłka zachowuje szkic czatu w pamięci i pokazuje błąd. Szkic nie jest zapisywany na dysku; znika po zakończeniu sesji.
- Odzyskanie ICE nie otwiera samoczynnie zminimalizowanego czatu/transferu. Wyjście z ekranu głównego podczas sesji chowa zadanie, zamiast niszczyć Activity.
- Dodano testy polityki sesji, wysyłania, autoryzacji oraz testy interfejsu szkicu i ręcznej weryfikacji.

Testy UI wymagają emulatora lub telefonu: `./gradlew.bat connectedDebugAndroidTest`. Nie zastępują rozmowy na dwóch fizycznych urządzeniach. Żywy czat nadal jest sesją w pamięci: ubity proces nie może odtworzyć jego treści, a zwykłe powiadomienie nie zapewnia nieograniczonego działania w tle.

## Co nadal trzeba sprawdzić przed publicznym wydaniem

- test na dwóch fizycznych telefonach: Wi-Fi, LTE/5G, przejście Wi-Fi ↔ LTE, Bluetooth oraz ekran blokady;
- dostarczanie FCM po ubiciu aplikacji przez konkretnego producenta telefonu i po wyłączeniu optymalizacji baterii;
- odbieranie, odrzucanie i anulowanie połączenia na ekranie blokady u różnych producentów Androida.

Nie należy uruchamiać równocześnie starego katalogu `signaling-server/`; aktualna aplikacja korzysta z Cloudflare.

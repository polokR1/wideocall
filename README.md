# Wideocall

Prywatny komunikator 1:1 na Androida: rozmowy wideo i audio, osobny znikający czat oraz bezpośredni transfer plików. Nie wymaga konta, numeru telefonu ani logowania Google — każda instalacja ma własny identyfikator `XXXX-XXXX`.

Aktualna wersja: **0.14.10 (29)**. Karty kontaktów, obsługa przychodzącej oferty podczas ponownego łączenia i ekran po otwarciu powiadomienia: [NAPRAWY-0.14.10.md](NAPRAWY-0.14.10.md). Rozmowy głosowe i ustawianie flag wybudzenia: [NAPRAWY-0.14.9.md](NAPRAWY-0.14.9.md). Ciche utrzymanie aktywnego czatu w tle i ponowne łączenie sygnalizacji po powrocie aplikacji: [NAPRAWY-0.14.8.md](NAPRAWY-0.14.8.md). Okno czatu niezależne od sesji i potwierdzenie zakończenia: [NAPRAWY-0.14.7.md](NAPRAWY-0.14.7.md). Dostarczenie a otwarcie czatu: [NAPRAWY-0.14.6.md](NAPRAWY-0.14.6.md). Zgodność audio na Motoroli: [NAPRAWY-0.14.5.md](NAPRAWY-0.14.5.md). Pozostałe zmiany prywatności i czatu: [NAPRAWY-0.14.4.md](NAPRAWY-0.14.4.md).

## Aktualna architektura

- WebRTC przesyła zaszyfrowany obraz i dźwięk bezpośrednio między telefonami, a Cloudflare TURN jest używany tylko wtedy, gdy bezpośrednia droga przez NAT lub sieć komórkową nie działa.
- Sygnalizacja WebRTC (oferta, odpowiedź, kandydaci ICE) idzie przez WebSocket Cloudflare Durable Objects. Aplikacja nie używa Firebase Realtime Database ani Firebase Authentication w aktywnym torze rozmowy.
- Trwałe połączenie WebSocket utrzymuje hibernowalny WebSocket Durable Object; nie powoduje ciągłych odczytów Firebase ani stałego powiadomienia czuwania.
- Worker przechowuje ofertę sygnalizacyjną maksymalnie 45 sekund i wysyła FCM jako sygnał wybudzenia także przy pozornie połączonym WebSocket — uśpiony Android nie musi na niego reagować. Oferta przestaje czekać po odpowiedzi/anulowaniu. Firebase Cloud Messaging nie przenosi obrazu, dźwięku ani treści czatu. Sygnał anulowania FCM pomaga usunąć dzwonek na uśpionym odbiorniku; zdarzenia tego samego połączenia są deduplikowane w APK przed pokazaniem powiadomienia.
- Każdy telefon podpisuje tożsamość i sygnały kluczem ECDSA P-256 z Android Keystore. Pierwszy klucz kontaktu jest przypinany lokalnie (TOFU), a jego późniejsza zmiana zatrzymuje połączenie. Dotknięcie złotej kłódki pokazuje wspólny numer bezpieczeństwa do porównania przez niezależny zaufany kanał. Zielona ikona oznacza ręczne potwierdzenie zgodności numerów, nie sam fakt szyfrowania.
- Kontakty dodaje się przez podpisane zaproszenie. Odbiorca sam je akceptuje albo odrzuca, a nadawca może cofnąć oczekujące zaproszenie; anulowanie usuwa je również z drugiego telefonu i jego powiadomień. Zdarzenia oczekujące są szyfrowane w transporcie i przechowywane w Durable Object najwyżej 7 dni.
- Czat używa szyfrowanego WebRTC DataChannel. Od 0.14.0 ma lokalną skrzynkę AES-256-GCM (klucz Android Keystore, plik atomowy w noBackupFilesDir), ale bez przechowywania treści na serwerze. Odbiór potwierdzany jest po trwałym zapisie; kolejka ponawia wysyłkę i usuwa duplikaty po identyfikatorach. Oba telefony potrzebują co najmniej wersji 0.14.0. Zaproszenie i nowa wiadomość trafiają do prywatnego powiadomienia, które otwiera osobny pełnoekranowy widok czatu także po zakończeniu sesji. Minimalizacja jest lokalna. Odebrane wiadomości wygasają 60 sekund po otwarciu na danym telefonie; wysłane dopiero po potwierdzeniu odbioru i otwarciu. Niewysłane czekają lokalnie i można je usunąć. Dostarczenie wymaga bezpośredniej sesji z dostępnym odbiorcą (zaproszenie nadal wymaga akceptacji); po zakończeniu sesji użyj „Połącz z rozmówcą”. Sam powrót do okna lub sprawdzenie dostarczenia nie rozpoczyna nowej sesji. Gdy Android zatrzyma proces, czyszczenie pliku następuje przy następnym uruchomieniu — nie ma gwarancji fizycznego wymazania dokładnie w 60. sekundzie. Same identyfikatory odebranych wiadomości są trzymane zaszyfrowane przez 7 dni, aby ponowienia nie odtwarzały wygasłej treści. Ekran czatu blokuje systemowe zrzuty i nagrywanie ekranu; nie chroni przed zdjęciem innym urządzeniem ani zmodyfikowanym systemem.
- Pliki do 100 MB są strumieniowane w osobnej sesji przez niezawodny WebRTC DataChannel. Treść pliku nie trafia do Firebase, Durable Object ani żadnej bazy. TURN może przekazywać wyłącznie zaszyfrowane pakiety, gdy telefony nie mogą połączyć się bezpośrednio. Odbiornik weryfikuje rozmiar i sumę SHA-256 przed udostępnieniem przycisku zapisu.
- Aplikacja nie zapisuje historii rozmów; aktualizacja usuwa historię wcześniejszych wersji. Zapisane kontakty i klucze bezpieczeństwa pozostają niezbędną konfiguracją. Nazwę kontaktu można zmienić w jego menu „Opcje kontaktu” → „Zmień nazwę”; zmiana jest lokalna i nie zmienia ID ani kluczy. Nie jest to pełna anonimowość sieciowa — infrastruktura obsługuje adresy IP i metadane niezbędne do zestawienia połączenia.
- Na Wi-Fi aplikacja używa 720p/30 kl./s. Na sieci komórkowej startuje od 360p/24 kl./s i 400 kb/s, aby ruch był płynniejszy bez nadmiernego obciążania łącza; WebRTC dynamicznie obniża jakość przy słabym zasięgu. Bufor audio, stałe zbieranie ICE i szybkie ponawianie sygnalizacji ograniczają przerwy przy zmianie sieci. Tor głosowy używa trybu rozmowy, sprzętowego usuwania echa/szumu i niskich opóźnień, jeśli wspiera je telefon.
- Android Core Telecom integruje aktywną rozmowę z systemowym ekranem połączenia, wyjściami audio i regulacją mikrofonu. Wyjście audio można przełączyć z ekranu rozmowy, a błędy mikrofonu lub odtwarzania są pokazywane w diagnostyce. Aplikacja nie deklaruje uprawnień lokalizacji ani Bluetooth/urządzeń w pobliżu; obsługa konkretnych słuchawek wymaga osobnego testu fizycznego. Podczas aktywnej kamery/mikrofonu Android wymaga widocznego powiadomienia rozmowy — nie jest to opcjonalne ustawienie aplikacji.
- Od 0.14.5 nie wymuszamy trybu niskich opóźnień AudioTrack. Używamy zwykłej ścieżki odtwarzania WebRTC, ponieważ na fizycznej Motoroli g stylus / Android 11 dotychczasowa konfiguracja kończyła się błędem `AudioTrack.write failed: -1`. Dokładnego wpływu zmiany na opóźnienie nie zmierzono; sam build nie dowodzi skuteczności poprawki.
- Połączenie wychodzące ma lokalny sygnał oczekiwania, a połączenie przychodzące korzysta z wysokopriorytetowego kanału dzwonka. O kamerę i mikrofon pytamy dopiero przy próbie wykonania lub odebrania rozmowy, po wyjaśnieniu transmisji na żywo bez zapisywania nagrania. Treści okna zgody Androida („nagrywanie”) nie można zmienić w aplikacji. Po cofnięciu zgody lub użyciu dostępu jednorazowego Android może zapytać ponownie. Czat i pliki nie wymagają tych zgód; głośnik nie ma osobnego uprawnienia.
- Minimalizacja okna czatu nie kończy sesji ani nie zatrzymuje kolejki. Gdy użytkownik nawiąże lub zaakceptuje bezpośredni czat, system pokazuje jedno ciche, prywatne powiadomienie o aktywnym czacie i utrzymuje proces oraz CPU do zakończenia połączenia; treść i identyfikator rozmówcy nie trafiają do powiadomienia. Usługa zatrzymuje się przy zakończeniu, błędzie lub wygaśnięciu zestawiania. Poza aktywnym czatem aplikacja nie prowadzi stałego czuwania. To poprawia działanie po schowaniu aplikacji, ale nie gwarantuje ciągłości przy utracie zasięgu, wymuszonym zatrzymaniu lub ograniczeniach producenta. Powiadomienia o nowych wiadomościach i zaproszeniach nadal są oddzielne. Otwarcie kontaktu, powrót do okna i „Sprawdź dostarczenie” nie wysyłają zaproszenia. Pierwsze wysłanie wiadomości bez sesji lub jawne „Połącz z rozmówcą” rozpoczyna połączenie wymagające akceptacji. Zakończenie połączenia czatu wymaga potwierdzenia; anulowanie pozostawia je aktywne. Inny kontakt można otworzyć lokalnie bez przerywania transportu dotychczasowego czatu. Jednocześnie działa jeden transport WebRTC, więc dostarczenie do innego kontaktu wymaga zakończenia poprzedniej sesji i połączenia z nowym odbiorcą. Rozmowa i transfer pliku nadal mają pierwszeństwo przed sesją samego czatu.
- „Dostarczono na telefon” potwierdza trwały zaszyfrowany zapis u odbiorcy, nie przeczytanie. Powiadomienie nie rozpoczyna minuty u odbiorcy. Liczniki są lokalne: otwarcie na jednym telefonie nie uruchamia licznika na drugim; własna kopia nadawcy może wygasnąć po potwierdzeniu dostarczenia i lokalnym wyświetleniu, mimo że odbiorca jeszcze nie otworzył swojej. Nie wysyłamy potwierdzeń odczytu ani wspólnego czasu kasowania.
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

Testy UI wymagają emulatora lub telefonu: `./gradlew.bat connectedDebugAndroidTest`. Nie zastępują rozmowy na dwóch fizycznych urządzeniach. Żywy czat nadal jest sesją w pamięci: ubity proces nie może odtworzyć jego treści.

## Co nadal trzeba sprawdzić przed publicznym wydaniem

- test na dwóch fizycznych telefonach: Wi-Fi, LTE/5G, przejście Wi-Fi ↔ LTE, Bluetooth oraz ekran blokady;
- dostarczanie FCM po ubiciu aplikacji przez konkretnego producenta telefonu i po wyłączeniu optymalizacji baterii;
- odbieranie, odrzucanie i anulowanie połączenia na ekranie blokady u różnych producentów Androida.

Nie należy uruchamiać równocześnie starego katalogu `signaling-server/`; aktualna aplikacja korzysta z Cloudflare.

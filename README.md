# Wideocall

Prywatny wideotelefon na Androida: rozmowy wideo i audio 1:1 oraz znikające wiadomości bez reklam i chmurowej książki kontaktów.

## Architektura

- WebRTC przesyła zaszyfrowany obraz i dźwięk bezpośrednio między telefonami, gdy pozwala na to sieć.
- Firebase Realtime Database przekazuje wyłącznie krótkotrwałe dane sygnalizacyjne SDP/ICE oraz status dostępności.
- Firebase Authentication anonimowo wiąże lokalny identyfikator z instalacją aplikacji.
- Każdy sygnał WebRTC jest podpisany długoterminowym kluczem ECDSA P-256 przechowywanym jako nieeksportowalny klucz w Android Keystore. Pierwszy klucz kontaktu jest przypinany lokalnie (TOFU), a jego późniejsza zmiana blokuje połączenie.
- Obie osoby mogą porównać w aplikacji ten sam 60-cyfrowy kod bezpieczeństwa i oznaczyć kontakt jako ręcznie zweryfikowany. Podpis obejmuje też SDP, więc wiąże sprawdzoną tożsamość z kluczem szyfrującym konkretną sesję WebRTC.
- Użytkownik nie zakłada konta i nie loguje się przez Google, e-mail ani hasło; aplikacja pokazuje wyłącznie prosty identyfikator `XXXX-XXXX`.
- Firebase Cloud Messaging wybudza aplikację powiadomieniem o połączeniu, również gdy jej ekran nie jest otwarty.
- Bezpłatny Cloudflare Worker `wideocall-notify` uwierzytelnia telefon inicjujący sesję, sprawdza świeżą ofertę w Realtime Database i wysyła przez FCM wyłącznie identyfikatory sesji.
- Ten sam Worker wydaje zalogowanej anonimowo instalacji krótkotrwałe, 24-godzinne dane Cloudflare TURN. Długoterminowy sekret TURN nigdy nie trafia do APK ani Firebase.
- WebRTC automatycznie próbuje odzyskać rozmowę przez ICE restart po zmianie Wi-Fi/LTE albo chwilowej utracie sieci.
- Android Core Telecom rejestruje połączenie jako systemową rozmowę VoIP, zapewnia współpracę z ekranem blokady, Bluetooth, zegarkiem/samochodem i systemowym sterowaniem mikrofonem. Aktywna kamera i mikrofon nadal wymagają widocznego powiadomienia rozmowy zgodnie z zasadami Androida.
- Opcjonalna, domyślnie wyłączona usługa czuwania Realtime Database pozostaje zapasową drogą odbioru i pokazuje stałe, ciche powiadomienie systemowe tylko po ręcznym włączeniu.
- Osobny czat nie uruchamia kamery ani mikrofonu. Wiadomości są przesyłane zaszyfrowanym kanałem WebRTC DataChannel, istnieją wyłącznie w pamięci urządzeń i znikają po 60 sekundach.
- Podczas wyświetlania czatu Android blokuje zrzuty i nagrywanie ekranu oraz ukrywa podgląd aplikacji na ekranie ostatnich aplikacji. Na Androidzie 12+ aplikacja blokuje też nakładki innych aplikacji.
- Kopie zapasowe i transfer danych aplikacji na nowe urządzenie są wyłączone, a aplikacja nie zezwala na nieszyfrowane połączenia HTTP.
- Nazwy i lista kontaktów pozostają tylko na telefonie.

## Konfiguracja Firebase

Projekt korzysta z Firebase `vidcall-88857`. Logowanie anonimowe jest włączone, baza Realtime Database działa w regionie `europe-west1`, a `app/google-services.json` zawiera jej adres. Reguły zostały zweryfikowane testem zapisu obecności oraz wysłania, odczytu i usunięcia sygnału.

Reguły obecności, statusu czuwania, prywatnych tokenów urządzeń i sygnalizacji są wdrożone. Funkcja `notifyIncomingCall` w katalogu `functions/` jest niewdrożonym wariantem dla planu Firebase Blaze; wersja 0.8.0 używa zamiast niej bezpłatnego Cloudflare Workera.

Klient Firebase App Check z dostawcą Play Integrity jest już włączony w aplikacji. Przed wymuszeniem App Check w konsoli trzeba zarejestrować odcisk SHA-256 certyfikatu, którym będzie podpisane instalowane APK, ustawić dystrybucję poza Google Play (`PLAY_RECOGNIZED` i `LICENSED` niewymagane, minimalnie `Device integrity`), rozdać nową wersję i sprawdzić metryki. Wymuszanie pozostaje obecnie wyłączone, aby nie odciąć istniejących instalacji.

Po każdej zmianie reguł lub funkcji należy wdrożyć odpowiednią część projektu:

```powershell
firebase deploy --only database
# Wymaga planu Blaze:
firebase deploy --only functions
```

Nie należy pozostawiać produkcyjnej bazy w publicznym trybie testowym. Reguły w repozytorium wymagają zalogowanej instalacji, chronią skrzynkę odbiorczą telefonu i blokują podszywanie się pod cudzy identyfikator.

## Czuwanie w tle

FCM jest uruchamiane automatycznie po pierwszym otwarciu aplikacji. Token telefonu jest prywatny w Realtime Database, a Cloudflare Worker odczytuje go dopiero po zweryfikowaniu anonimowej tożsamości Firebase i świeżej oferty sesji. Powiadomienie ma wysoki priorytet i otwiera ekran rozmowy albo osobnego czatu.

Normalna praca w tle korzysta z FCM i nie wymaga stałego powiadomienia. Zapasowe czuwanie jest domyślnie wyłączone; można je ręcznie włączyć w ustawieniach, jeśli dany producent telefonu opóźnia FCM. Dopiero wtedy pojawia się stałe, ciche powiadomienie `Wideocall czuwa` i usługa uruchamia się ponownie po restarcie telefonu.

To rozwiązanie nie wymaga konta użytkownika, logowania przez Google ani płatnych Cloud Functions. Firebase Authentication działa anonimowo i niewidocznie wyłącznie jako zabezpieczenie przypisania identyfikatora `XXXX-XXXX` do instalacji.

## Cloudflare Worker

Kod bramki znajduje się w `cloudflare-worker/`. Produkcyjny Worker działa pod adresem `https://wideocall-notify.wideocall-polokr1.workers.dev`. W Cloudflare są wymagane cztery zaszyfrowane sekrety: `FIREBASE_WEB_API_KEY`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `TURN_KEY_ID` oraz `TURN_KEY_API_TOKEN`. Prywatne klucze nie mogą znajdować się w repozytorium ani w APK; odpowiadające im wzorce są blokowane przez `.gitignore`.

Endpoint `POST /notify-call` nie przyjmuje obrazu, dźwięku, SDP ani treści czatu. Żądanie zawiera tylko identyfikator nadawcy, odbiorcy, sesji, jej rodzaju i zapisanej oferty. Worker odrzuca żądania bez poprawnego tokenu Firebase lub pasującej, świeżej oferty. Gdy aplikacja uzyska token App Check, przesyła go również do Workera, który przekazuje go do Firebase Auth; pozwala to później włączyć wymuszanie bez zmiany protokołu.

Endpoint `POST /turn-credentials` przyjmuje wyłącznie token anonimowego Firebase, pobiera od Cloudflare dane ICE ważne przez 24 godziny i zwraca tylko dozwolone adresy Cloudflare STUN/TURN. Aplikacja przechowuje te dane wyłącznie w pamięci i nadal potrafi zestawić bezpośrednie połączenie przez STUN, gdy bramka TURN jest chwilowo niedostępna.

## Budowanie Androida

Projekt wymaga JDK 17, Android SDK 36, Android Gradle Plugin 8.10.1 oraz Gradle 8.11.1.

```powershell
./gradlew.bat assembleDebug
```

APK zostanie utworzony w `app/build/outputs/apk/debug/app-debug.apk`.

## Aktualny zakres

- lokalny identyfikator telefonu,
- lokalna książka kontaktów,
- status kontaktu: dostępny, czuwanie aktywne, dostępny przez powiadomienie, zajęty lub offline,
- sygnalizacja rozmowy przez Firebase,
- wybudzanie telefonu przez FCM i bezpłatny Cloudflare Worker,
- opcjonalne zapasowe czuwanie w tle ze stałym, cichym powiadomieniem,
- systemowe powiadomienie połączenia przychodzącego przy zamkniętym ekranie aplikacji,
- integracja rozmów VoIP z Android Core Telecom i systemowym sterowaniem połączeniem,
- połączenie WebRTC 1:1,
- Cloudflare TURN dla sieci z NAT/firewallem blokującym bezpośrednie P2P,
- automatyczny ICE restart przy zmianie sieci lub chwilowym zerwaniu ścieżki WebRTC,
- osobny szyfrowany czat P2P bez kamery, mikrofonu, bazy i historii, z czasem życia wiadomości 60 sekund,
- ochrona ekranu czatu przed systemowymi zrzutami i nagrywaniem, obcymi nakładkami oraz całkowicie ukryte powiadomienia czatu na ekranie blokady,
- wyłączone kopie zapasowe danych i nieszyfrowany ruch sieciowy,
- podpisy ECDSA sygnalizacji w Android Keystore, lokalne przypięcie klucza rozmówcy i ręczna weryfikacja 60-cyfrowego kodu bezpieczeństwa,
- klient Firebase App Check / Play Integrity gotowy do wymuszenia po rejestracji certyfikatu APK,
- sprzętowe kodowanie wideo, jeśli obsługuje je telefon,
- automatyczne dopasowanie bitrate w zakresie 150–1500 kb/s z priorytetem płynności obrazu,
- wyciszanie mikrofonu, zmiana kamery i kończenie rozmowy.

## Ograniczenia przed wersją produkcyjną

- Czuwanie nie działa, gdy telefon jest wyłączony, bez internetu albo aplikacja została wymuszenie zatrzymana w ustawieniach systemu; po wymuszonym zatrzymaniu trzeba ponownie otworzyć Wideocall.
- Tryby oszczędzania baterii i mechanizmy niektórych producentów mogą opóźnić powiadomienie albo zatrzymać zapasową usługę. Na takich telefonach może być potrzebne wyłączenie optymalizacji baterii dla Wideocall.
- Pełnoekranowe połączenie przychodzące zależy od zgody systemowej i ustawień producenta telefonu; zawsze pozostaje powiadomienie typu rozmowa.
- Dopóki sekrety Cloudflare TURN nie zostaną dodane do Workera, aplikacja korzysta z bezpośredniego P2P i zapasowego publicznego STUN; restrykcyjne sieci mogą wtedy nie zestawić rozmowy.
- Wiadomości nie są kolejkowane offline: odbiorca musi otworzyć zaproszenie, zanim oferta sesji wygaśnie. Dostarczenie po dłuższym czasie wymagałoby przechowania zaszyfrowanej treści na serwerze.
- Systemowa blokada zrzutu ekranu nie chroni przed zdjęciem ekranu drugim urządzeniem ani przed zmodyfikowanym lub przejętym systemem Android.
- Przy pierwszym kontakcie klucz jest akceptowany metodą TOFU. Dopóki obie osoby nie porównają całego kodu bezpieczeństwa, przejęty backend lub atakujący podczas pierwszego połączenia może podstawić własny klucz; nie należy traktować samego statusu „przypięty” jak ręcznej weryfikacji.
- Firebase App Check jest zintegrowany po stronie klienta, ale jego wymuszanie nie jest jeszcze aktywne. Przed publicznym wydaniem trzeba zarejestrować certyfikat dystrybucyjny, sprawdzić metryki prawidłowych żądań, włączyć wymuszanie i dodać limity antyspamowe po stronie bramki.
- Konieczne są testy na dwóch fizycznych telefonach w Wi-Fi, LTE i 5G.

Folder `signaling-server/` zawiera poprzedni lokalny serwer testowy i nie jest już używany przez aplikację Android.

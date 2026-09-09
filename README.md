# Wideocall

Prywatny wideotelefon na Androida: rozmowy wideo i audio 1:1 oraz znikające wiadomości bez reklam i chmurowej książki kontaktów.

## Architektura

- WebRTC przesyła zaszyfrowany obraz i dźwięk bezpośrednio między telefonami, gdy pozwala na to sieć.
- Firebase Realtime Database przekazuje wyłącznie krótkotrwałe dane sygnalizacyjne SDP/ICE oraz status dostępności.
- Firebase Authentication anonimowo wiąże lokalny identyfikator z instalacją aplikacji.
- Użytkownik nie zakłada konta i nie loguje się przez Google, e-mail ani hasło; aplikacja pokazuje wyłącznie prosty identyfikator `XXXX-XXXX`.
- Bezpłatna usługa czuwania utrzymuje nasłuch Realtime Database w tle i pokazuje stałe, ciche powiadomienie systemowe.
- Firebase Cloud Messaging i funkcja `notifyIncomingCall` pozostają przygotowaną, opcjonalną drogą na przyszłość; obecna wersja nie wymaga planu Blaze.
- Wiadomości czatu są przesyłane zaszyfrowanym kanałem WebRTC DataChannel, istnieją wyłącznie w pamięci urządzeń i znikają po 60 sekundach.
- Nazwy i lista kontaktów pozostają tylko na telefonie.

## Konfiguracja Firebase

Projekt korzysta z Firebase `vidcall-88857`. Logowanie anonimowe jest włączone, baza Realtime Database działa w regionie `europe-west1`, a `app/google-services.json` zawiera jej adres. Reguły zostały zweryfikowane testem zapisu obecności oraz wysłania, odczytu i usunięcia sygnału.

Reguły obecności, statusu czuwania, prywatnych tokenów urządzeń i sygnalizacji są wdrożone. Funkcja `notifyIncomingCall` jest gotowa w katalogu `functions/`, ale nie jest wdrożona, ponieważ wymaga planu Firebase Blaze. Nie jest potrzebna do działania bezpłatnego czuwania w wersji 0.4.2.

Po każdej zmianie reguł lub funkcji należy wdrożyć odpowiednią część projektu:

```powershell
firebase deploy --only database
# Wymaga planu Blaze:
firebase deploy --only functions
```

Nie należy pozostawiać produkcyjnej bazy w publicznym trybie testowym. Reguły w repozytorium wymagają zalogowanej instalacji, chronią skrzynkę odbiorczą telefonu i blokują podszywanie się pod cudzy identyfikator.

## Czuwanie w tle

Czuwanie jest domyślnie włączone i można je wyłączyć w ustawieniach aplikacji albo akcją w stałym powiadomieniu `Wideocall czuwa`. Uruchamia się po pierwszym otwarciu aplikacji i ponownie po restarcie telefonu. Kontakt widzi wtedy status `Czuwanie aktywne`, nawet gdy ekran główny Wideocall nie jest otwarty.

To rozwiązanie nie wymaga konta użytkownika, logowania przez Google ani płatnych Cloud Functions. Firebase Authentication działa anonimowo i niewidocznie wyłącznie jako zabezpieczenie przypisania identyfikatora `XXXX-XXXX` do instalacji.

## Budowanie Androida

Projekt wymaga JDK 17 oraz Android SDK 35.

```powershell
./gradlew.bat assembleDebug
```

APK zostanie utworzony w `app/build/outputs/apk/debug/app-debug.apk`.

## Aktualny zakres

- lokalny identyfikator telefonu,
- lokalna książka kontaktów,
- status kontaktu: dostępny, czuwanie aktywne, dostępny przez powiadomienie, zajęty lub offline,
- sygnalizacja rozmowy przez Firebase,
- bezpłatne czuwanie w tle ze stałym, cichym powiadomieniem,
- systemowe powiadomienie połączenia przychodzącego przy zamkniętym ekranie aplikacji,
- połączenie WebRTC 1:1,
- szyfrowany czat P2P bez bazy i bez historii, z czasem życia wiadomości 60 sekund,
- sprzętowe kodowanie wideo, jeśli obsługuje je telefon,
- automatyczne dopasowanie bitrate w zakresie 150–1500 kb/s z priorytetem płynności obrazu,
- wyciszanie mikrofonu, zmiana kamery i kończenie rozmowy.

## Ograniczenia przed wersją produkcyjną

- Czuwanie nie działa, gdy telefon jest wyłączony, bez internetu albo aplikacja została wymuszenie zatrzymana w ustawieniach systemu; po wymuszonym zatrzymaniu trzeba ponownie otworzyć Wideocall.
- Tryby oszczędzania baterii i mechanizmy niektórych producentów mogą zatrzymać usługę mimo stałego powiadomienia. Na takich telefonach trzeba wyłączyć optymalizację baterii dla Wideocall.
- Pełnoekranowe połączenie przychodzące zależy od zgody systemowej i ustawień producenta telefonu; zawsze pozostaje powiadomienie typu rozmowa.
- Projekt korzysta z publicznego STUN Google. Część sieci komórkowych i restrykcyjnych routerów wymaga serwera TURN.
- Reguły ograniczają dostęp do sygnalizacji, ale przed publicznym wydaniem należy dodać Firebase App Check i limity antyspamowe.
- Konieczne są testy na dwóch fizycznych telefonach w Wi-Fi, LTE i 5G.

Folder `signaling-server/` zawiera poprzedni lokalny serwer testowy i nie jest już używany przez aplikację Android.

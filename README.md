# Wideocall

Prywatny wideotelefon na Androida: rozmowy wideo i audio 1:1 oraz znikające wiadomości bez reklam i chmurowej książki kontaktów.

## Architektura

- WebRTC przesyła zaszyfrowany obraz i dźwięk bezpośrednio między telefonami, gdy pozwala na to sieć.
- Firebase Realtime Database przekazuje wyłącznie krótkotrwałe dane sygnalizacyjne SDP/ICE oraz status dostępności.
- Firebase Authentication anonimowo wiąże lokalny identyfikator z instalacją aplikacji.
- Użytkownik nie zakłada konta i nie loguje się przez Google, e-mail ani hasło; aplikacja pokazuje wyłącznie prosty identyfikator `XXXX-XXXX`.
- Firebase Cloud Messaging budzi aplikację dla połączenia przychodzącego; zaufana Cloud Function nie przekazuje obrazu, dźwięku ani wiadomości.
- Wiadomości czatu są przesyłane zaszyfrowanym kanałem WebRTC DataChannel, istnieją wyłącznie w pamięci urządzeń i znikają po 60 sekundach.
- Nazwy i lista kontaktów pozostają tylko na telefonie.

## Konfiguracja Firebase

Projekt korzysta z Firebase `vidcall-88857`. Logowanie anonimowe jest włączone, baza Realtime Database działa w regionie `europe-west1`, a `app/google-services.json` zawiera jej adres. Reguły zostały zweryfikowane testem zapisu obecności oraz wysłania, odczytu i usunięcia sygnału.

Po każdej zmianie reguł lub funkcji należy wdrożyć odpowiednią część projektu:

```powershell
firebase deploy --only database,functions
```

Nie należy pozostawiać produkcyjnej bazy w publicznym trybie testowym. Reguły w repozytorium wymagają zalogowanej instalacji, chronią skrzynkę odbiorczą telefonu i blokują podszywanie się pod cudzy identyfikator.

## Budowanie Androida

Projekt wymaga JDK 17 oraz Android SDK 35.

```powershell
./gradlew.bat assembleDebug
```

APK zostanie utworzony w `app/build/outputs/apk/debug/app-debug.apk`.

## Aktualny zakres

- lokalny identyfikator telefonu,
- lokalna książka kontaktów,
- status kontaktu: dostępny, dostępny przez powiadomienie, zajęty lub offline,
- sygnalizacja rozmowy przez Firebase,
- połączenia przychodzące przez FCM przy aplikacji w tle,
- połączenie WebRTC 1:1,
- szyfrowany czat P2P bez bazy i bez historii, z czasem życia wiadomości 60 sekund,
- sprzętowe kodowanie wideo, jeśli obsługuje je telefon,
- automatyczne dopasowanie bitrate,
- wyciszanie mikrofonu, zmiana kamery i kończenie rozmowy.

## Ograniczenia przed wersją produkcyjną

- Powiadomienie nie dotrze, gdy telefon jest wyłączony, bez internetu, bez usług Google Play albo aplikacja została wymuszenie zatrzymana.
- Pełnoekranowe połączenie przychodzące zależy od zgody systemowej i ustawień producenta telefonu; zawsze pozostaje powiadomienie typu rozmowa.
- Projekt korzysta z publicznego STUN Google. Część sieci komórkowych i restrykcyjnych routerów wymaga serwera TURN.
- Reguły ograniczają dostęp do sygnalizacji, ale przed publicznym wydaniem należy dodać Firebase App Check i limity antyspamowe.
- Konieczne są testy na dwóch fizycznych telefonach w Wi-Fi, LTE i 5G.

Folder `signaling-server/` zawiera poprzedni lokalny serwer testowy i nie jest już używany przez aplikację Android.

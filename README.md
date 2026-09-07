# Wideocall

Minimalistyczny wideotelefon na Androida: tylko rozmowy wideo i audio 1:1, bez czatu, reklam i chmurowej książki kontaktów.

## Architektura

- WebRTC przesyła zaszyfrowany obraz i dźwięk bezpośrednio między telefonami, gdy pozwala na to sieć.
- Firebase Realtime Database przekazuje wyłącznie krótkotrwałe dane sygnalizacyjne SDP/ICE oraz status dostępności.
- Firebase Authentication anonimowo wiąże lokalny identyfikator z instalacją aplikacji.
- Nazwy i lista kontaktów pozostają tylko na telefonie.

## Konfiguracja Firebase

Projekt korzysta z Firebase `vidcall-88857`. Logowanie anonimowe jest włączone, baza Realtime Database działa w regionie `europe-west1`, a `app/google-services.json` zawiera jej adres. Reguły zostały zweryfikowane testem zapisu obecności oraz wysłania, odczytu i usunięcia sygnału.

Po każdej zmianie reguł należy wdrożyć plik `database.rules.json`:

```powershell
firebase deploy --only database
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
- sygnalizacja rozmowy przez Firebase,
- połączenie WebRTC 1:1,
- sprzętowe kodowanie wideo, jeśli obsługuje je telefon,
- automatyczne dopasowanie bitrate,
- wyciszanie mikrofonu, zmiana kamery i kończenie rozmowy.

## Ograniczenia przed wersją produkcyjną

- Obie aplikacje muszą być uruchomione. Wybudzanie zamkniętej aplikacji wymaga FCM i zaufanego backendu wysyłającego powiadomienia.
- Projekt korzysta z publicznego STUN Google. Część sieci komórkowych i restrykcyjnych routerów wymaga serwera TURN.
- Reguły ograniczają dostęp do sygnalizacji, ale przed publicznym wydaniem należy dodać Firebase App Check i limity antyspamowe.
- Konieczne są testy na dwóch fizycznych telefonach w Wi-Fi, LTE i 5G.

Folder `signaling-server/` zawiera poprzedni lokalny serwer testowy i nie jest już używany przez aplikację Android.

# Wideocall 0.14.11 (30) — naprawy i weryfikacja

Data: 2026-10-12. Urządzenia: Samsung S23 Ultra / Android 16 (API 36) oraz Motorola moto g stylus XT2115DL / Android 11 (API 30).

## Naprawione przyczyny

- Cloudflare odrzucał `sessionKind=audio`, rozłączając sygnalizację przed dostarczeniem oferty. Nowy test odtworzył błąd lokalnie oraz w produkcji. Walidatory przyjmują teraz audio; poprawka została wdrożona do istniejącego Workera.
- Usługa rozmowy głosowej deklarowała aktywną kamerę. Audio uruchamia teraz wyłącznie typy mikrofonu i rozmowy; kamera jest potrzebna tylko do wideo. Powiadomienie audio ma właściwy tytuł, a wybór głośnika pozostaje pod kontrolą użytkownika.
- Powtórna oferta tej samej aktywnej sesji jest ignorowana zamiast wysyłania `busy`. Spóźnione zaproszenie FCM do zaakceptowanego czatu jest pomijane; obsługa jest synchronizowana z anulowaniem.
- Spóźniony sygnał przychodzącego, już zakończonego połączenia nie rejestruje ponownie sesji Telecom. Oczekujący ekran przychodzącego znika także po anulowaniu przed dostarczeniem oferty.
- Ekran blokady jest udostępniany aplikacji tylko podczas rozmowy lub obsługi jej przychodzącego powiadomienia. Po zakończeniu przywracana jest normalna blokada, aby ekran kontaktów nie pozostawał nad nią.
- Usunięto siedem nieużywanych klas starej obsługi Firebase oraz zależności Auth, Database i App Check. Kopie wcześniejszego kodu są dostępne w historii Git. FCM pozostaje wyłącznie kanałem powiadomień; aktywne rozmowy, czat i pliki używają Cloudflare/WebRTC.
- Karta kontaktu zachowuje dwurzędowy układ, z oddzielnym miejscem na nazwę/ID i przyciski.

## Wykonane pomiary

| Sprawdzenie | Wynik |
| --- | --- |
| Testy JVM | 48/48, bez błędów |
| Testy Workera | 24/24 |
| Produkcyjna sygnalizacja | Audio, wideo, pliki, zaproszenie i cofnięcie zaproszenia przeszły |
| Produkcyjny TURN | Poprawny podpis przyjęty, błędny odrzucony; 2 konfiguracje ICE, 6 adresów relay, 24 h ważności |
| Zestaw instrumentacyjny Debug | 29/29 na każdym fizycznym telefonie, przed końcowymi poprawkami ekranu blokady |
| Końcowe APK Release | 18/18 dodatkowych testów na każdym telefonie: powiadomienia, ekran przychodzącego, minimalizacja czatu, szyfrowany zapis/wygaśnięcie, zgoda na plik, kanał danych i usługa czatu |
| Audio S23 → Motorola | Ponad 60 s bez rozłączenia, pakiety audio w obu kierunkach, przełączanie głośnika na obu urządzeniach; użytkownik potwierdził słyszalny głos w obu kierunkach |
| Wideo Motorola → S23 | Ponad 60 s bez rozłączenia; audio dwukierunkowe; 1087 i 1494 odebranych/dekodowanych klatek w pomiarze |
| Czat w Release | Po systemowym Home działały odbiór i odpowiedź; nieotwarta wiadomość przetrwała 65 s, po otwarciu została usunięta po minucie; oba testy trwały około 140 s |
| Plik Motorola → S23 w Release | 256 KiB, brak danych przed akceptacją, zgodność SHA-256 potwierdzona na odbiorniku i potwierdzenie u nadawcy |
| Audio na wygaszony/zablokowany S23 i Motorolę | Rzeczywisty przycisk „Odbierz” wykryty i naciśnięty na obu telefonach bez ręcznego otwierania aplikacji; każda rozmowa trwała ponad 60 s i przesyłała audio dwukierunkowo |
| Odrzucenie na S23 | Naciśnięcie „Odrzuć” potwierdzone u dzwoniącego; powiadomienie usunięte |
| Anulowanie przed odebraniem na Motoroli | Odebrano `hangup`, usunięto powiadomienie i ekran przychodzącego; spóźniony FCM nie pozostawił aktywnego dzwonka |
| Wi-Fi → LTE → Wi-Fi na S23 | Zweryfikowana aktywna sieć komórkowa z dostępem do internetu; około 20 s poza Wi-Fi; sesja nie zakończyła się, ruch RTP audio w obu kierunkach po próbie |
| Audio bez zgody na kamerę | S23 miał cofnięte CAMERA; rozmowa i powyższa zmiana sieci przeszły; usługa używała typów microphone/phoneCall (0x84), nie camera |

Wykonano też wybudzenie i odebranie wideo na S23 po potwierdzonym usunięciu procesu odbiorcy z pamięci, bez wymuszonego czuwania USB. Podczas minutowego pomiaru ekrany obu urządzeń pozostały aktywne. Samo wybudzenie audio na S23 sprawdzano wcześniej z istniejącym procesem w tle; tych dwóch warunków nie należy utożsamiać.

Nieudane próby nie są liczone jako sukcesy: pierwsze odebranie na S23 przekroczyło czas na działanie sterownika; pierwsza próba odrzucenia zakończyła się przed naciśnięciem przycisku, bez ustalonej przyczyny. Powtórzone próby odebrania i odrzucenia przeszły. Nie oznacza to dowodu bezbłędności każdej kolejnej rozmowy.

Po zakończonych rozmowach sprawdzono brak aktywnych powiadomień i usług rozmowy na obu telefonach. Wpisy w systemowym archiwum powiadomień nie są powiadomieniami aktywnymi. Testy nie kasowały danych aplikacji, tożsamości ani kontaktów; wcześniejsze ustawienia Wi-Fi, zgody na kamerę i czuwania USB zostały przywrócone.

## APK i ograniczenia pomiaru

Końcowa aplikacja jest wariantem Release, z wyłączonym debugowaniem i podpisem zgodnym z już zainstalowanymi wersjami. Używa dotychczasowego lokalnego certyfikatu rozwojowego, aby aktualizacja zachowała tożsamości i kontakty; nie jest to konfiguracja podpisu Google Play. Klucz prywatny nie jest częścią repozytorium ani APK.

- APK: `Wideocall-0.14.11.apk`, wersja 0.14.11 / kod 30.
- SHA-256 APK: `99B668BF81074A50325341D1975182A148B9AE53B0EC43810419562941FE3890`.
- SHA-256 certyfikatu: `D0C20187353C4AD494A5FC5D752CEDF2E8B66F133B2EC6C269B9365A46EC6D2C`.
- `apksigner verify` potwierdził podpis; końcowe APK zainstalowano jako aktualizację na obu telefonach.
- `lintRelease`: 0 błędów, 26 ostrzeżeń. Ostrzeżenia nie są przedstawiane jako usunięte.

## Powtórzenie sprawdzeń

Na Windows, z JDK 17 i Android SDK skonfigurowanym w nieśledzonym `local.properties`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
.\gradlew.bat testDebugUnitTest assembleRelease assembleDebugAndroidTest lintRelease --no-daemon --console=plain
node --test cloudflare-worker/test/*.test.js
```

`CrossDeviceSessionTest` jest domyślnie pomijany. Rzeczywiste sesje uruchamia się równolegle na dwóch telefonach z rolami `caller`/`receiver`, identyfikatorem drugiego telefonu i rodzajem `audio`, `video`, `chat` lub `file`:

```powershell
adb -s SERIAL shell am instrument -w -e class pl.wideocall.app.CrossDeviceSessionTest -e role caller -e peer XXXX-XXXX -e kind audio -e run cross-device pl.wideocall.app.test/androidx.test.runner.AndroidJUnitRunner
```

Test odbioru na ekranie blokady wymaga tylko sterownika na telefonie dzwoniącym: odbiorca korzysta z rzeczywistego ekranu „Odbierz”/„Odrzuć”, a nie automatycznego akceptowania w modelu. Sam build i same statystyki transportu nie potwierdzają jakości słyszanej przez człowieka.

Test pakietów/klatek nie mierzy subiektywnej jakości dźwięku, liczby zacięć obrazu ani wszystkich operatorów i warunków sieciowych. Potwierdzenie słyszalności pochodzi od użytkownika podczas konkretnej próby. To test funkcjonalny i przegląd kodu, nie niezależny audyt kryptograficzny ani gwarancja anonimowości adresów IP.

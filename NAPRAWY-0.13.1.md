# Wideocall 0.13.1 (18) — wynik napraw audytu

Data: 30.09.2026. Dotyczy siedmiu ustaleń z `AUDYT-2026-09-30.md`; raport audytu pozostaje zapisem stanu poprzedniej wersji 0.13.0.

## Wprowadzone poprawki

1. Autoryzacja HTTP Workera weryfikuje podpis przed przypięciem klucza. Odrzucone żądanie nie zmienia tożsamości. Nie usuwano istniejących kluczy ani rekordów urządzeń.
2. Ponawianie sygnalizacji sprawdza rzeczywistą gotowość sieci także po zakończeniu sesji. Zakończenie bez sygnalizacji pokazuje stan niedostępności i nie przerywa reconnect. Kolejne nieudane próby zachowują narastające opóźnienia.
3. Ręczne odświeżanie i ponowne uwierzytelnienie nie nadpisują fazy, ID ani widoczności aktywnej rozmowy/czatu/transferu. Diagnostyka ustawień używa osobnego stanu sygnalizacji. Nowa sesja nie może zastąpić istniejącej.
4. Po zaakceptowaniu zaproszenia odbierający ma własny 35-sekundowy limit zestawiania, również dla czatu i transferów, z komunikatem błędu. Podłączenie lub zakończenie sesji anuluje timer; stary timer nie kończy nowej sesji.
5. Wysyłanie czatu zwraca jawny wynik. Odmowa kanału lub wyjątek zachowuje szkic i pokazuje błąd. Szkic jest przechowywany tylko w pamięci sesji, nie na dysku; minimalizacja go nie kasuje, zakończenie sesji go usuwa. Sukces oznacza przyjęcie przez lokalny kanał, nie potwierdzenie odbioru/odczytu.
6. Callback CONNECTED WebRTC nie wymusza otwarcia zminimalizowanego czatu lub transferu. Callback bez aktywnej sesji jest ignorowany.
7. Dotknięcie kłódki w rozmowie/czacie pokazuje wspólny numer bezpieczeństwa. Użytkownik porównuje go przez niezależny zaufany kanał i jawnie potwierdza zgodność. Złota kłódka oznacza brak ręcznego potwierdzenia, zielona potwierdzoną tożsamość. Potwierdzenie jest związane z bieżącą sesją i numerem, a błąd zapisu nie jest przedstawiany jako sukces. Sygnał od innego rozmówcy nie podmienia numeru aktywnego partnera.

Dodatkowo „Wyjdź” podczas aktywnej sesji chowa zadanie zamiast kończyć Activity. Nie jest to gwarancja przeżycia ubicia procesu przez Androida.

## Weryfikacja

- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest` — BUILD SUCCESSFUL.
- Android JVM: **27/27**. Nowe regresje testują rzeczywiste funkcje polityki używane przez CallViewModel: odświeżanie, utratę/odnowienie sieci, zakończenie offline, fazy i ID sesji, widoczność okien, kwalifikację timeoutów i wynik wysyłania. Nie są to pełne testy integracyjne wszystkich callbacków ViewModel ani rozmowy na dwóch urządzeniach.
- Android UI, emulator Android 16/API 36: **2/2**. Odrzucona wysyłka nie czyści tekstu; ekran bezpieczeństwa pokazuje numer i wymaga świadomego potwierdzenia.
- Lint: **0 błędów / 22 ostrzeżenia**. Ostrzeżenia dotyczą zależności i zaleceń stylu; nie przeprowadzano szerokiej aktualizacji bibliotek podczas napraw.
- Worker: **15/15**, w tym brak przypinania przy nieprawidłowym podpisie, poprawna późniejsza autoryzacja, odrzucenie podmiany klucza, modyfikacji podpisanych pól, złego czasu i operacji.
- Wrangler 4.145.0: dry-run poprawny; wdrożono Worker `wideocall-notify`, wersja `d7826413-d602-4437-bf1b-b1cb231ae398`. Sekrety i dane urządzeń zachowano. Ostrzeżenie o brakujących scope k2 nie zablokowało tego wdrożenia; nie dodawano zbędnych uprawnień.
- Produkcyjny test TURN na nowym losowym ID: nieprawidłowe żądanie odrzucone, późniejsze prawidłowe żądanie dla tego samego ID udane; wydano konfigurację z 2 wpisami ICE, 6 adresami relay i TTL 24 h. Nie wypisano sekretów TURN.
- Produkcyjny test sygnalizacji na 5 nowych losowych ID: uwierzytelnienie, przekazanie oferty rozmowy i pliku, zaproszenie oczekujące oraz anulowanie rozmowy/zaproszenia — poprawne. Testy utworzyły tożsamości testowe, nie zmieniały rekordów użytkowników.
- APK: debug, podpis V2 zweryfikowany, versionName **0.13.1**, versionCode **18**. Instalacja na emulatorze udana.
- SHA-256 APK: `205CE5126C2D78B8D4227348BA17679243435A5D77CFE37F6AEABC394D7A122C`.
- Plik: `app/build/outputs/apk/debug/app-debug.apk`.

## Nadal wymagane przed deklaracją pełnej niezawodności

Test na dwóch fizycznych telefonach: audio w obie strony i Bluetooth, Wi-Fi/LTE/5G i zmiana sieci, odbieranie/odrzucanie z blokady, FCM/Doze i ograniczenia producenta, długotrwałe transfery oraz screenshot aktywnego czatu. Emulator nie potwierdza tych funkcji.

Nie dodano pełnej obecności online/busy ani nowej architektury utrzymującej czat po ubiciu procesu. Nie ma bazy historii ani odtwarzania utraconej treści. Nie resetowano kluczy potencjalnie wcześniej błędnie przypiętych — ewentualne takie urządzenie wymaga oddzielnej diagnozy bez automatycznego obchodzenia ochrony tożsamości.

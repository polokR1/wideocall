# Wideocall 0.14.0 (19) — lokalny czat i pojedynczy dzwonek

## Zmiany

- Usunięty drugi odtwarzacz dzwonka z ekranu przychodzącego. Dzwoni wyłącznie powiadomienie Androida, z powtarzaniem i `ONLY_ALERT_ONCE`. Identyfikator jest wspólny dla FCM i sygnalizacji. Obsłużone połączenie nie może ponownie odtworzyć powiadomienia przez 2 minuty; wyświetlenie i anulowanie są synchronizowane.
- Lokalna skrzynka i kolejka AES-256-GCM. Klucz w Android Keystore, losowy IV, uwierzytelnienie danych, zapis AtomicFile w katalogu wyłączonym z backupu. Nie zapisujemy jawnych plików tymczasowych ani treści na serwerze.
- Odbiorca potwierdza wiadomość dopiero po atomowym zapisie. Stałe UUID i zaszyfrowane znaczniki odbioru zapobiegają duplikatom, także po restarcie lub usunięciu wygasłej treści. Znaczniki zawierają tylko identyfikator kontaktu, wiadomości i czas, bez treści, przez 7 dni.
- Wiadomości odebrane czekają na otwarcie. Po otwarciu wygasają po 60 sekundach na danym telefonie. Wysłane wygasają po otwarciu i potwierdzeniu odbioru; niewysłane zostają w kolejce. Można usunąć oczekującą kolejkę lokalną (to nie cofa już odebranych wiadomości).
- Czat dostępny bez aktywnej sesji i podczas braku sieci. Rozłączenie nie czyści lokalnej skrzynki. Nowa wiadomość ma status dostarczenia; powiadomienie prowadzi do właściwego kontaktu także po zakończeniu sesji.
- Błędy szyfrowania, pełnej skrzynki lub zapisu nie usuwają danych i nie generują potwierdzenia odbioru. Zatrzymana wysyłka pozostaje do ponowienia. Limit 1000 wiadomości / 4 MB i 20000 znaczników odbioru.

## Granice działania

- Aktualizacja obu telefonów do 0.14.0 jest konieczna dla nowego protokołu potwierdzeń. Starsze wiadomości tekstowe można odebrać, ale nowej kolejki nie wysyłamy bez potwierdzenia wersji protokołu.
- Nie dodano serwerowej skrzynki wiadomości. Dostarczenie nadal wymaga dostępnego drugiego telefonu i sesji WebRTC; zaproszenie wymaga akceptacji. W otwartej sesji kolejka ponawia próbę co 3 sekundy. Po zakończeniu sesji użyj „Połącz czat / ponów dostarczenie”.
- Przy działającym procesie timer usuwa wygasłą treść, a utrzymanie skrzynki sprawdza ją co 15 sekund. Jeśli Android zabije proces lub telefon jest wyłączony, zapis jest czyszczony przy następnym uruchomieniu. Nie obiecujemy fizycznego wymazania pamięci flash dokładnie po minucie ani ochrony przed rootem/zdjęciem ekranu.
- APK jest podpisaną wersją debug do testowania. Nie jest deklaracją audytu kryptograficznego ani wydaniem sklepowym.

## Weryfikacja 2026-09-30

- Gradle assembleDebug, testDebugUnitTest, lintDebug, assembleDebugAndroidTest: sukces.
- 32 testy jednostkowe, bez błędów. Nowe: AES-GCM, losowość IV, manipulacja, obcy klucz, błędny format, nawigacja lokalnego czatu offline.
- 5 testów Android na emulatorze API 36: interfejs i ręczna weryfikacja numeru (2), zapis/odtworzenie/TTL/deduplikacja/oddzielenie kontaktów (2), jednokrotne powiadomienie i blokada spóźnionego sygnału (1).
- Lint: 0 błędów, 23 ostrzeżenia (w tym stylistyczne UseKtx). Nie twierdzimy, że ostrzeżeń nie ma.
- Podpis APK V2 zweryfikowany, certyfikat taki sam jak w 0.13.1.
- SHA-256 APK: `FB7535EE2EE7DCE80EB88CBF8593E71566DC0398F1CBA47A6DA9FAF441D72E57`.
- Nie uruchamiano testów instrumentalnych na telefonie użytkownika i nie kasowano jego danych. Dwa realne telefony, słyszalny dzwonek, rozmowa oraz ponowienie po zaniku sieci wymagają testu po aktualizacji obu instalacji.

# Wideocall 0.14.6 (25) — dostarczenie nie oznacza odczytu

## Doprecyzowanie użytkownika

Użytkownik potwierdził, że poprawiona rozmowa na Motoroli działa i nie rozłącza się. W czacie zauważył potwierdzenie dostarczenia, gdy odbiorca dostał jedynie powiadomienie, ale nie obserwował faktycznego znikania treści przed jej otwarciem. Nie zapisujemy tego jako odtworzonego błędu przedwczesnego kasowania.

## Zmiany i zachowane zasady

- Etykieta własnej dostarczonej wiadomości to teraz „Dostarczono na telefon”, zamiast niejednoznacznego „Odbiór potwierdzony”. Nie przedstawiamy jej jako potwierdzenia przeczytania.
- Ekran czatu wyjaśnia, że u odbiorcy powiadomienie nie uruchamia minuty oraz że dostarczenie nie oznacza otwarcia.
- Nie zmieniono protokołu czatu ani zasad licznika: `chat-ack` jest wysyłane po udanym atomowym zapisie zaszyfrowanej wiadomości. Nie dodano sygnału odczytania ani informacji o aktywności do serwera.
- U odbiorcy treść bez `openedAt` nie wygasa po minucie. Minuta zaczyna się po otwarciu czatu na tym telefonie. Odebranie w faktycznie otwartym czacie liczy się jako wyświetlenie.
- Liczniki telefonów są niezależne. Kopia nadawcy może wygasnąć po potwierdzeniu dostarczenia i lokalnym wyświetleniu, zanim odbiorca otworzy swoją kopię. Nie obiecujemy wspólnego zegara uruchamianego odczytem na drugim urządzeniu.
- Zachowano poprawkę audio 0.14.5, pozostałe zmiany 0.14.4 oraz szyfrowaną kolejkę niewysłanych wiadomości.

## Weryfikacja wykonana 2026-10-11

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-daemon`: BUILD SUCCESSFUL.
- 45 testów jednostkowych JVM: 0 błędów.
- Wszystkie 22 testy instrumentacyjne przeszły na emulatorze Android 8 / API 26 / x86_64. Fizyczna Motorola była jawnie wyłączona z uruchamiania testów; komenda ADB wskazywała wyłącznie `emulator-5556`.
- Nowy test `notificationAndDeliveryAckDoNotStartRecipientExpiry`: wiadomość odebrana → prywatne powiadomienie → ACK niezmieniające wpisu odbiorcy → restart lokalnej skrzynki po 120 sekundach. Treść nadal istnieje, `openedAt == null`. Po otwarciu istnieje przez 59 999 ms i znika przy 60 000 ms. Jest to kontrolowany test magazynu z zegarem testowym, nie ręczne czekanie na dwóch fizycznych telefonach.
- Nowy test UI `deliveryStatusDoesNotClaimMessageWasRead`: rozróżnia „Czeka na dostarczenie” i „Dostarczono na telefon”; nie pokazuje starej etykiety ani „Przeczytano”.
- Lint: 0 błędów, 24 ostrzeżenia.
- Podpis APK v2 poprawny, certyfikat SHA-256: `d0c20187353c4ad494a5fc5d752cedf2e8b66f133b2ec6c269b9365a46ec6d2c` (APK debug/testowe).
- APK: `app/build/outputs/apk/debug/Wideocall-0.14.6.apk`, 68 273 136 bajtów, minSdk 26, targetSdk 36.
- SHA-256 APK: `67D71BEB434026F7DA601E967E7699F2B0664BAE623C29F44FE07D5CCB6548D3`.
- Log testów: `app/build/reports/verification-0.14.6/android-api26.txt` (lokalny, niepublikowany w Git).

Nie wykonano nowej rozmowy na fizycznym telefonie z 0.14.6; potwierdzenie działania audio dotyczy 0.14.5. Nie przepisujemy testów 0.14.4 na API 30/36 jako nowych wyników tej wersji. Ostatnio zainstalowana w tej pracy wersja na Motoroli to 0.14.5; APK 0.14.6 jest udostępnione do aktualizacji.

## Zakres publikacji

Repozytorium `polokR1/wideocall` zostało sprawdzone przez GitHub CLI i jest publiczne. Użytkownik wyraźnie wybrał pozostawienie go publicznym i wysłanie zmian. Publikowane są źródła, testy i raporty opisowe, bez logów z telefonu, zrzutów ekranu, APK, plików konta serwisowego czy kluczy prywatnych. Publiczny kod nie oznacza publikacji treści rozmów i czatów.

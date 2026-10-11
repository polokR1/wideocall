# Wideocall 0.14.4 (23) — uprawnienia, czat i brak historii rozmów

## Uzgodniony zakres

- Rozmowy zostają. O kamerę i mikrofon pytamy dopiero podczas próby dzwonienia lub odbierania, nie przy otwarciu aplikacji, czatu ani plików.
- Przed systemowym oknem zgody wyjaśniamy transmisję na żywo, bez zapisywania nagrania rozmowy. Użytkownik może anulować przed wyświetleniem systemowej prośby. Tekstu okna Androida, w tym słowa „nagrywanie”, aplikacja nie może przemianować. Zgoda jednorazowa lub jej cofnięcie może spowodować kolejne pytanie.
- Aplikacja nie deklaruje lokalizacji ani Bluetooth/urządzeń w pobliżu. Samo usunięcie wpisu z głównego manifestu nie wystarczyło: test zainstalowanego APK wykrył ponowne dodawanie `BLUETOOTH_CONNECT` przez Core Telecom. Reguła `tools:node="remove"` usuwa je także z manifestu wynikowego. Nie wyłączamy systemowej kontroli uprawnień.
- Odmowa dostępu do kamery/mikrofonu nie uruchamia rozmowy i pokazuje wyjaśnienie. Czat i pliki pozostają dostępne. Po powrocie z ustawień uprawnienia są odczytywane ponownie. Spóźniona zgoda nie odbiera innego połączenia niż to, którego dotyczyła akcja.
- Nazwę zapisanego urządzenia zmieniamy przez menu kontaktu → „Zmień nazwę”. Nazwa jest lokalna, do 60 znaków, bez znaków sterujących; ID i klucze pozostają bez zmian. Nie można odtworzyć usuniętego kontaktu przez otwarty wcześniej edytor.
- Minimalizacja czatu jest cicha. Nie tworzy powiadomienia i nie zamyka okna u rozmówcy. Usuwamy również stare stałe powiadomienie z wcześniejszych wersji, nie usuwając powiadomienia o nowej wiadomości.
- Powiadomienia czatu dotyczą zaproszeń i faktycznie odebranych, nieprzeczytanych wiadomości. Są prywatne, usuwalne i nie mają `fullScreenIntent`. Aktualizacja istniejącego powiadomienia nie wywołuje kolejnego alarmu.
- Lokalny czat innego kontaktu można otworzyć bez blokady przez aktywny transport. Powrót aplikacji na pierwszy plan nie przełącza go na poprzedniego rozmówcę. Status i numer bezpieczeństwa innego transportu nie są prezentowane jako weryfikacja tego czatu.
- Jednocześnie istnieje jeden transport WebRTC. Nowa rozmowa, przychodząca rozmowa lub nowy czat może zastąpić sesję **samego czatu**, ale nie przerywa aktywnej rozmowy ani transferu. Lokalne wiadomości nie są wtedy usuwane. Kolejka do innego kontaktu czeka na bezpośrednie połączenie z nim.
- Historia rozmów nie jest już tworzona ani pokazywana. Przy uruchomieniu aktualizacji usuwamy dawny wpis `recent_callers_v1`. Nie ma przycisku przywracania tej historii. To logiczne usunięcie z aplikacji, nie obietnica forensic secure erase pamięci flash.

## Dane pozostające na telefonie

Na wyraźne doprecyzowanie użytkownika pozostają zapisane kontakty, własne ID, ustawienia, klucze bezpieczeństwa oraz zaszyfrowany lokalny czat z kolejką niewysłanych wiadomości. Nie deklarujemy „braku jakichkolwiek danych lokalnych”.

Odebrana treść wygasa 60 sekund od otwarcia na danym telefonie. Wysłana — po potwierdzeniu odbioru i otwarciu. Nieprzeczytane i niewysłane wiadomości nie są kasowane przez minimalizację ani samo zakończenie transportu. Identyfikatory odebranych wiadomości, bez ich treści, pozostają zaszyfrowane przez 7 dni, aby ponowienia nie odtwarzały skasowanej treści. Zatrzymany proces może fizycznie wyczyścić plik dopiero przy kolejnym uruchomieniu; nie gwarantujemy fizycznego wymazania dokładnie w 60. sekundzie.

Nie dodano przechowywania treści ani historii rozmów na serwerze. Ta aktualizacja nie wymaga zmiany produkcyjnego Workera. Nie oznacza też pełnej anonimowości sieciowej: do zestawienia połączenia infrastruktura obsługuje IP i niezbędne metadane.

## Weryfikacja wykonana 2026-10-11

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: BUILD SUCCESSFUL.
- 45 testów JVM: 0 błędów.
- Lint: 0 błędów, 24 ostrzeżenia; nie jest to deklaracja całkowicie czystego lint.
- Android 8 / API 26 / x86_64: wszystkie 20 testów instrumentacyjnych przeszły.
- Android 11 / API 30 / x86 32-bit: wszystkie 20 testów instrumentacyjnych przeszły.
- Android 16 / API 36 / x86_64: wszystkie 20 testów instrumentacyjnych przeszły.
- Testy obejmują: minimalizację bez powiadomienia, przełączenie lokalnego okna przy kontrolowanym aktywnym transporcie, usuwanie starego stałego powiadomienia bez kasowania nowej wiadomości, lokalną zmianę nazwy i zachowanie ID, usuwanie historii, manifest zainstalowanego APK bez lokalizacji/Bluetooth, dialog objaśniający oraz wcześniejsze testy czatu, plików, powiadomień, ekranu i Core Telecom.
- Kontrolowana sesja w teście nawigacji jest wstrzyknięta lokalnie, nie jest rozmową z fizycznym kontaktem. Test WebRTC to lokalna para, nie dowód działania przez LTE/5G.
- Po odebraniu zgód kamery i mikrofonu aplikacja uruchomiła ekran główny na API 26 i 36, bez systemowego pytania o media. Na API 36 potwierdzono `CAMERA: granted=false` i `RECORD_AUDIO: granted=false` oraz wizualnie sprawdzono ekran.
- Podczas zimnego startu emulatorów wystąpiły ANR procesów systemowych (m.in. System UI), a instalację na API 30 opóźniły aktualizacje Google Play. Nie przypisujemy tych wyników aplikacji: zdarzenia wskazywały pakiety systemowe. Po zakończeniu tych operacji testy przeszły; zanotowane problemy środowiska nie są ukrywane jako „bezbłędny przebieg”.
- `apksigner verify --verbose --print-certs`: podpis v2 poprawny, certyfikat SHA-256 `d0c20187353c4ad494a5fc5d752cedf2e8b66f133b2ec6c269b9365a46ec6d2c` (kompilacja debug/testowa).
- APK: `app/build/outputs/apk/debug/Wideocall-0.14.4.apk`, 68 490 643 bajty, minSdk 26, targetSdk 36.
- SHA-256 APK: `DF25E0FD66D59E7AA77C278EC727CF2E80B2369027386C4994DC90E440466E8E`.
- Logi testów i zrzuty ekranu: `app/build/reports/verification-0.14.4/`.

## Granice weryfikacji i test na telefonach

W tej turze nie było podłączonych fizycznych telefonów. Nie potwierdzono działania konkretnej Motoroli, przełączania fizycznych słuchawek Bluetooth bez dodatkowego uprawnienia, odsłuchu dźwięku, wybudzania zablokowanego S23 ani jakości sieci komórkowej. Emulator nie zastępuje tych testów.

Po instalacji aktualizacji na obu telefonach:

1. Otwórz aplikację i czat bez zgód kamery/mikrofonu — nie powinny być wymagane. Dopiero „Zadzwoń” lub „Odbierz” powinno pokazać objaśnienie i zgodę Androida. „Nie teraz” nie rozpoczyna rozmowy.
2. Zmień nazwę kontaktu w menu, zamknij i uruchom aplikację ponownie. Nazwa powinna pozostać, ID bez zmian; drugi telefon nie powinien zmienić swojej nazwy.
3. Zminimalizuj otwarty czat — nie powinno pojawić się nowe powiadomienie. Otwórz innego kontaktu lub zadzwoń bez ręcznego kończenia poprzedniego transportu czatu.
4. Wyślij faktycznie nową wiadomość do zminimalizowanego czatu — prywatne powiadomienie powinno się pojawić, po dotknięciu otworzyć właściwy czat i dać się usunąć.
5. Potwierdź znikanie treści po minucie od otwarcia na każdym telefonie niezależnie, zachowanie niewysłanej kolejki, rozmowę na ekranie blokady, głośnik oraz Wi-Fi/LTE.

Systemowy model zgód: [Request app permissions](https://developer.android.com/training/permissions/requesting), [Explain access to sensitive information](https://developer.android.com/training/permissions/explaining-access), [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

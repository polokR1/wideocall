# Wideocall 0.14.3 (22) — głośnik, ekran i zgodność czatu

## Zmiany

- Przełączanie audio używa nadal Core Telecom, bez konkurencyjnego sterowania AudioManagerem. Polecenie czeka na pojawienie się żądanego wyjścia (maksymalnie 8 s łącznie) i na potwierdzenie rzeczywistej zmiany (maksymalnie 3 s po odpowiedzi API). Sam zapis polecenia do kolejki nie jest już traktowany jako przełączenie.
- Przycisk nie przyjmuje kolejnych zmian w trakcie przełączania. Brak wyjścia, odmowa systemu, błąd sesji Telecom lub timeout są wyświetlane jako diagnostyka. Nie logujemy kontaktu ani treści. Rozłączanie nie czeka za zmianą wyjścia; zadanie zmiany jest anulowane.
- Automatyczny głośnik wideo czeka na wyjścia Androida. Nie zabiera dźwięku słuchawkom Bluetooth/przewodowym i nie nadpisuje ręcznego wyboru. Odpowiedziana rozmowa przychodząca nie wymaga ponownego `setActive`, by przejść do wyboru wyjścia. Usługa rozmowy deklaruje również typ `phoneCall` i wymagane uprawnienie foreground.
- Utrzymywanie ekranu ma jednego właściciela w cyklu życia Activity, niezależnego od podmiany widoków Compose. Ustawia flagę okna i `decorView.keepScreenOn`, przywraca ochronę przy wznowieniu/powrocie fokusu, usuwa ją po zakończeniu rozmowy lub schowaniu aplikacji. Obsługuje OUTGOING, INCOMING, CONNECTING_CALL oraz IN_CALL. Nie wymaga `WAKE_LOCK`, `WRITE_SETTINGS` ani dodatkowego okna zgody. Nie omija świadomego zablokowania telefonu przyciskiem zasilania i nie trzyma ekranu włączonego, gdy aplikacja jest w tle.
- WebRTC ignoruje opóźnione callbacki SDP/ICE/DataChannel i tracków poprzedniej sesji. Wiadomość lub zamknięcie starego kanału nie powinny już zmieniać gotowości nowego czatu. To zabezpieczenie wyścigu, nie potwierdzona diagnoza konkretnej Motoroli.
- Dodano normalne uprawnienie `CHANGE_NETWORK_STATE`: logi WebRTC na API 26 wskazywały brak zgody na `requestNetwork` dla sieci komórkowej. Uprawnienie wspiera dostępność sieci podczas aktywnej sesji; nie dowodzi to przyczyny awarii czatu.
- Diagnostyka czatu rozróżnia niezgodny handshake, brak handshake oraz błąd lokalnego zapisu. Ustawienia pokazują również błąd czatu, model urządzenia, wersję Androida/API i wersję APK. Treść czatu, SDP, tokeny oraz kontakt nie trafiają do nowych logów.
- Zachowano szyfrowaną skrzynkę AES-GCM/Keystore, potwierdzenie po zapisie i minutę od otwarcia. Nie kasowano danych ani nie obniżono szyfrowania.

## Wersje i uprawnienia

Sprawdzono metadane Google Maven 2026-10-08: Core Telecom **1.0.1** pozostaje najnowszą stabilną wersją; 1.1.0-rc01 nie została wprowadzona do tej poprawki. Minimalny Android pozostaje **8.0 / API 26**. APK zawiera native WebRTC dla arm64-v8a, armeabi-v7a, x86 oraz x86_64. Wygaszanie nie ma osobnego uprawnienia runtime; kamera/mikrofon nadal wymagają zgody. Powiadomienia wymagają zgody dopiero od Androida 13, Bluetooth Connect od Androida 12.

## Weryfikacja 2026-10-08

- `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: sukces. **43 testy JVM**, 0 błędów / 25 ostrzeżeń lint. Pierwsze przebiegi ujawniły problem formatowania lint i nieprawidłową konfigurację testu CallStyle bez foreground; poprawiono je i ponowiono testy. Nie ukryto błędów przez wyłączenie reguł.
- **Android 8.0 / API 26 / x86_64: 13/13 testów instrumentalnych.** System potwierdził wyjście 1 (słuchawka) i następnie 4 (głośnik) w lokalnej sesji Telecom. Przeszedł powrót z tła/recreate okna, zwolnienie flag, szyfrowana skrzynka, minuta po otwarciu, UI szkicu, powiadomienia i pliki.
- **Android 11 / API 30 / x86 (32-bit): 13/13 testów instrumentalnych.** Użyto tego samego versionCode 22 i potwierdzono przyznanie normalnego uprawnienia sieci. To dodatkowa weryfikacja starszego systemu i 32-bitowych bibliotek, nie fizycznego procesora ARM Motoroli.
- **Android 16 / API 36 / x86_64: 13/13 testów instrumentalnych.** Ten obraz emulatora udostępnia jedynie głośnik. Test sprawdził więc potwierdzony głośnik i jawny timeout przy braku alternatywy, zamiast udawać sukces przełączenia na nieistniejącą słuchawkę.
- Na każdej z trzech wersji przeszedł test **native WebRTC DataChannel**, z polskimi znakami/emoji w obie strony i ponownym zestawieniem sesji. To dwa peer connections lokalnie na tym samym emulatorze (DTLS), nie połączenie pomiędzy telefonami, test opóźnienia przez LTE ani test całego protokołu aplikacji z produkcyjną sygnalizacją.
- Podpis końcowego debug APK sprawdzony przez apksigner: V2, certyfikat zgodny z poprzednią wersją (`d0c20187353c4ad494a5fc5d752cedf2e8b66f133b2ec6c269b9365a46ec6d2c`). APK **0.14.3 / versionCode 22**, 68 469 338 B, SHA-256 **D83EB288C783B4E04E06825A033EBA5F8151BFC95B68E3965F2B92DD6CCE2758**.

Nie podłączono fizycznego S23 ani Motoroli. Użytkownik zgłosił niedziałający czat na starszej Motoroli, ale nie zna modelu/wydania Androida. Test emulatora nie potwierdza odsłuchu, zachowania OEM, połączenia między dwoma fizycznymi telefonami ani wybudzania Motoroli. Należy zaktualizować oba telefony i zebrać logi `WideocallChat`, `TelecomCallBridge`, `WideocallSignal` z konkretnego urządzenia. Nie wdrażano zmian backendu.

## Źródła

- [Core Telecom — wyjścia audio i foreground phoneCall](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom).
- [Android — utrzymywanie ekranu](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on).
- [Google Maven — wersje Core Telecom](https://dl.google.com/dl/android/maven2/androidx/core/core-telecom/maven-metadata.xml).
- [ConnectivityManager — requestNetwork](https://developer.android.com/reference/android/net/ConnectivityManager).
- [WebRTC NetworkMonitorAutoDetect — requestMobileNetwork](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/NetworkMonitorAutoDetect.java).

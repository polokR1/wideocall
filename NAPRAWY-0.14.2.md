# Wideocall 0.14.2 (21) — połączenie, zgoda na plik, powiadomienia

## Zmiany

- Sygnalizacja: powtórny callback sieci nie przerywa trwającego uwierzytelniania ani zdrowego WebSocketu. Rzeczywista zmiana domyślnej sieci oraz ręczne ponowienie mogą wymusić nowy socket. Stare callbacki są unieważniane; stare sockety anulowane natychmiast.
- Zamknięcie przez serwer jest obsługiwane już przy `onClosing`, zamiast czekania na zakończenie handshake. Uwierzytelnianie ma limit 15 sekund. Bez aktywnej sieci nie uruchamiamy kolejnych prób w pętli; callback powrotu sieci wznawia połączenie.
- OkHttp 5.3.0: protokołowy ping co 30 zamiast 15 sekund. To większa tolerancja chwilowego zastoju, nie gwarancja niezrywania połączenia i nie dowód przyczyny rozłączeń na telefonie. Martwy socket bez zdarzenia zmiany sieci może być wykryty później niż wcześniej.
- Logi `WideocallSignal` i `WideocallCall` pokazują generację socketu, czas zestawienia, kod zamknięcia/HTTP, klasę błędu, czas pobrania TURN i czas przygotowania oferty. Nie zawierają SDP, tokenów, identyfikatorów rozmówcy, treści czatu ani nazw plików. `offer_queued` oznacza zapis do lokalnej kolejki WebSocket, nie dowód dostarczenia do drugiego telefonu.
- Kliknięcie powiadomienia o pliku otwiera prośbę, nie akceptuje automatycznie. Po wybraniu podglądu zestawiany jest tylko kanał i pobierana nazwa/rozmiar. Dopiero **Odbierz plik** otwiera plik tymczasowy i wysyła zgodę na przesyłanie bajtów. Dane przed zgodą są ignorowane; ponowne kliknięcie nie rozpoczyna drugiego odbioru. Nowy odbiorca działa także ze starym nadawcą, ale oba telefony trzeba zaktualizować, aby chronić oba kierunki.
- Usługi aktywnego transferu i rozmowy jawnie usuwają własne powiadomienia przy zatrzymaniu. Zakończenie zachowuje wcześniej ukończony plik do ręcznego zapisania; nie usuwa pliku wyeksportowanego przez użytkownika. Wygasłe/zakończone zaproszenie do pliku nie jest ponownie pokazywane przez spóźnione FCM przez 2 minuty.
- Postęp transferu jest pokazywany bez dźwięku, od razu po starcie usługi. Obsłużono systemowy timeout usługi dataSync: zapis diagnozy i zatrzymanie usługi zamiast awarii ANR.

## Weryfikacja

- `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: sukces. 37 testów JVM, 0 błędów lint / 25 ostrzeżeń.
- Emulator API 36: **9/9 testów instrumentalnych**, w tym brak odbioru przed zgodą, odrzucenie pliku, pełny odbiór z SHA-256 po zgodzie, zapobieganie odtworzeniu zakończonego zaproszenia oraz usunięcie rzeczywistego powiadomienia foreground po zatrzymaniu usługi. Pierwszy przebieg testu foreground nie widział powiadomienia w ciągu 5 sekund (systemowe odroczenie); po ustawieniu natychmiastowego wyświetlania końcowy pełny przebieg przeszedł.
- Emulator z produkcyjną usługą: start socketu i uwierzytelnienie ok. 0,94 s. Wyłączono Wi-Fi i dane emulatora na 5 sekund, następnie obie sieci przywrócono; sygnalizacja wróciła bez restartu aplikacji. Zaobserwowano ponowne połączenie na danych oraz kolejne po powrocie Wi-Fi; ostatnie uwierzytelnienie trwało ok. 1,58 s. To pomiar na emulatorze/sieci komputera, nie na sieci komórkowej S23 i nie pomiar czasu dzwonka.
- APK **0.14.2 / versionCode 21**, 68 376 678 B. Podpis V2 zweryfikowany; certyfikat debug zgodny z poprzednimi aktualizacjami (`d0c20187353c4ad494a5fc5d752cedf2e8b66f133b2ec6c269b9365a46ec6d2c`). SHA-256: `3849C64D3099842C1A6CF8A843451262724BC9B83A3FC6093D45DD60BBB2B2B5`.

Nie potwierdzono jeszcze przyczyny ciągłego odnawiania na S23 ani czasu dzwonienia na dwóch fizycznych telefonach. S23 nie był podłączony podczas tej zmiany. Nie zmieniano ani nie wdrażano backendu Cloudflare.

## Sprawdzone źródła techniczne

- [OkHttp 5.3.0 — źródło pingInterval i zamykania socketów](https://github.com/square/okhttp/blob/parent-5.3.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/OkHttpClient.kt).
- [Cloudflare — automatyczne protokołowe ping/pong w Durable Objects](https://developers.cloudflare.com/durable-objects/best-practices/websockets/).
- [Android — ograniczenia Doze i wybudzanie FCM](https://developer.android.com/training/monitoring-device-state/doze-standby).
- [Android — zachowanie powiadomień foreground](https://developer.android.com/reference/android/app/Notification#FOREGROUND_SERVICE_IMMEDIATE).

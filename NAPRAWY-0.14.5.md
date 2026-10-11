# Wideocall 0.14.5 (24) — zgodność odtwarzania audio

## Potwierdzona diagnoza przed zmianą

Fizyczna Motorola moto g stylus XT2115DL, Android 11 / API 30, miała Wideocall 0.14.4 oraz udzielone zgody na kamerę i mikrofon. Podczas kontrolowanej rozmowy wychodzącej log z 2026-10-11 wykazał:

- `AudioTrack-JNI: Error -61 during AudioTrack native read`;
- `WebRtcAudioTrackExternal: Run-time playback error: AudioTrack.write failed: -1`;
- sześć niedopełnień bufora oraz powtarzające się restarty AudioTrack;
- próbę zwiększenia bufora odtwarzania z 0 do 480 ramek;
- `MODE_IN_COMMUNICATION`, włączony głośnik, mikrofon obecny i niewyciszony;
- zakończenie sesji przez istniejącą obsługę błędu odtwarzania.

To potwierdza bezpośredni mechanizm zakończenia tej próby — błąd odtwarzania. Konflikt wymuszonego trybu niskich opóźnień z implementacją audio telefonu pozostaje hipotezą do sprawdzenia porównaniem po zmianie; nie wskazujemy Firebase ani braku zgody na mikrofon jako przyczyny tego zdarzenia.

## Zmiana

- `JavaAudioDeviceModule.Builder.setUseLowLatency(false)` zamiast wymuszonego `true`: zwykła ścieżka odtwarzania, bez opt-in do trybu FAST i jego dynamicznego zmniejszania bufora.
- Ustawienie jest wspólne dla wszystkich telefonów, nie zależy od zgadywania modelu. Nie dodano uprawnień ani zmian w sygnalizacji, TURN, czacie lub kluczach.
- Zachowano routing Android Core Telecom, usuwanie echa/szumu i zgłaszanie rzeczywistych błędów audio. Nie ignorujemy błędu, aby pozornie utrzymać połączenie bez dźwięku.
- Log diagnostyczny `WideocallAudio` potwierdza wybrane ustawienie i wersję API. Nie zapisuje nagrania, treści czatu, kluczy ani identyfikatora kontaktu.
- Własny komunikat startu mikrofonu nazywa go „Mikrofon rozmowy”, bez słowa „nagrywanie”. Nie zmienia to komunikatów uprawnień narzuconych przez Androida.

Nie zmierzono opóźnienia audio po zmianie ani nie gwarantuje się jego konkretnej wartości.

## Wykonane sprawdzenia

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-daemon`: BUILD SUCCESSFUL.
- 45 testów JVM: 0 błędów. Lint: 0 błędów, 24 ostrzeżenia; raport w `app/build/reports/lint-results-debug.xml`.
- Sprawdzono zgodność podpisu nowego APK z zachowaną kopią 0.14.4: ten sam certyfikat SHA-256 `d0c20187353c4ad494a5fc5d752cedf2e8b66f133b2ec6c269b9365a46ec6d2c`, poprawny podpis v2 (APK debug/testowe).
- Aktualizacja `adb install --no-streaming -r` na fizycznej Motoroli: Success. Nie wykonywano odinstalowania, `pm clear`, cofania uprawnień ani resetu telefonu.
- Po aktualizacji: versionName 0.14.5, versionCode 24; data pierwszej instalacji i `ceDataInode` nie zmieniły się. Nie eksportowano kontaktów ani prywatnych kluczy do porównania.
- Aplikacja uruchomiona po instalacji; log potwierdził `audio_config lowLatency=false api=30`.
- APK: `app/build/outputs/apk/debug/Wideocall-0.14.5.apk`.
- SHA-256: `F15177A538C9EEC42C2D7BF87341134CBCA5C7E3239053361030785DC05094D4`.
- Log ponownego testu: `app/build/reports/motorola-audio-diagnostics/live-audio-0.14.5.txt` (lokalny, poza Git).

W tej wersji nie uruchamiano testów instrumentacyjnych na fizycznej Motoroli, aby nie naruszyć jej danych i aktywnego testu użytkownika. Wyniki 20 testów na API 26/30/36 w raporcie 0.14.4 dotyczą tamtej wersji, nie są przepisywane jako nowy wynik 0.14.5.

## Ponowna rozmowa na fizycznym telefonie

Aktualizacja została przygotowana i zainstalowana. W ponownej rozmowie wychodzącej z Motoroli z 2026-10-11 log pokazał:

- mikrofon: `initRecording(sampleRate=48000, channels=1)` i potwierdzenie START;
- odtwarzanie: `initPlayout(sampleRate=48000, channels=1, bufferSizeFactor=1.0)`;
- bufor odtwarzania: 1928 ramek, zamiast zgłoszonego wcześniej bufora zerowego;
- start odtwarzania 21:48:17.083, zatrzymanie 21:49:35.773 — około 79 sekund;
- `underrun count: 0`, 3 778 080 ramek przekazanych do odtwarzania;
- brak wcześniejszego `AudioTrack.write failed: -1` w zebranym logu tej próby.

To potwierdza poprawę działania toru audio w tej jednej próbie. Użytkownik następnie potwierdził: „działa i już nie rozłącza”. Nie deklarujemy nieograniczonej stabilności ani naprawienia wszystkich wariantów rozmów na podstawie jednej próby. Szczegółowe testy ekranów blokady, zmiany sieci i różnych słuchawek pozostają osobnymi przypadkami.

Źródło ścieżek AudioTrack: [WebRTC WebRtcAudioTrack.java](https://webrtc.googlesource.com/src/+/master/sdk/android/src/java/org/webrtc/audio/WebRtcAudioTrack.java). Klient korzysta z `io.github.webrtc-sdk:android:144.7559.15`; odwołanie do bieżącego upstream nie zastępuje testu tej konkretnej paczki na telefonie.

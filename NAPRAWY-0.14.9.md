# Poprawki 0.14.9 (28)

- Pełnoekranowy intent połączenia ponownie ustawia flagi `turnScreenOn` i `showWhenLocked` przy dostarczeniu intentu do istniejącej aktywności `singleTask`, a także przy jej `onStart` i `onResume`.
- Dodano test instrumentacyjny, który czyści i sprawdza flagi ekranu przy ponownym dostarczeniu intentu połączenia.
- Dodano osobny przycisk połączenia głosowego. Sesja sygnalizuje typ `audio`, nie uruchamia kamery i zestawia wyłącznie ścieżkę audio WebRTC; przy pierwszym użyciu prosi tylko o mikrofon.
- Połączenie audio ma osobny ekran oraz rejestruje w Android Telecom typ rozmowy głosowej, zachowując wyciszenie, wybór głośnika i szyfrowany kanał danych.
- Dodano typ sesji do danych FCM i lokalnej usługi czuwania, aby przy zablokowanym telefonie poprawnie pokazać audio/wideo. Zmiana funkcji Firebase jest w repozytorium, ale jej wdrożenie w projekcie Firebase nie zostało wykonane.

## Zakres potwierdzenia

- Poprzednie logi S23 pokazały uruchomienie aktywności połączenia przez Androida, lecz nie rejestrowały stanu fizycznego wyświetlacza w chwili zdarzenia. Użytkownik potwierdził, że ekran się nie wybudził; dlatego po instalacji 0.14.9 potrzebny jest ponowny test z zablokowanym S23.
- Test flag przechodzi na emulatorze, S23 Ultra (Android 16) i Motoroli (Android 11); nie zastępuje testu fizycznego wybudzenia przy przychodzącym połączeniu z zablokowanym ekranem.
- Build `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest` i `lintDebug` zakończył się powodzeniem. Pełny zestaw 28 testów instrumentacyjnych przeszedł na emulatorze, a `npm test` funkcji powiadomień również przeszedł. Automatycznie nie zweryfikowano jeszcze rozmowy głosowej między telefonami ani jakości dźwięku.
- Do poprawnych powiadomień audio-only w tle trzeba wdrożyć zmienioną funkcję `notifyIncomingCall` z katalogu `functions` do Firebase; bez tego istniejąca funkcja push może opisać rozmowę audio jak wideo.

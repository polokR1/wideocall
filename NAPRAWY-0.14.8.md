# Poprawki 0.14.8 (27)

- Aktywny bezpośredni czat utrzymuje cicha usługa systemowa z powiadomieniem bez treści i identyfikatora rozmówcy. Powiadomienie jest widoczne wyłącznie podczas zestawiania lub aktywnego czatu. Usługa utrzymuje CPU dla transportu WebRTC i kończy się razem z sesją, błędem albo limitem zestawiania.
- Przy powrocie aplikacji na pierwszy plan, także po wybudzeniu przez FCM, klient od razu ponawia sygnalizację zamiast czekać na wstrzymaną próbę ponownego połączenia. Samo otwarcie czatu nadal nie inicjuje kolejnego zaproszenia.
- Manifest używa typu usługi `remoteMessaging` przeznaczonego przez Androida do przekazywania wiadomości między urządzeniami. Usługa wymaga zwykłych uprawnień pierwszoplanowych, nie lokalizacji ani dostępu do urządzeń w pobliżu.

## Weryfikacja

- Testy jednostkowe, lint i testy instrumentacyjne sprawdzają ponowne połączenie po powrocie, prywatność powiadomienia oraz jego usunięcie po zakończeniu usługi.
- Powiadomienie i usługa systemowa pomagają utrzymać aktywny transport po schowaniu aplikacji. Nie gwarantują odbioru po utracie sieci, wymuszonym zatrzymaniu aplikacji ani restrykcjach oszczędzania energii producenta.
- Test fizyczny 2026-10-11: Motorola g stylus XT2115DL / Android 11 → Samsung S23 Ultra SM-S918B / Android 16. Android uruchomił ekran Wideocall dla przychodzącego połączenia, aplikacja wznowiła sygnalizację w około 0,4 s i odebrała ofertę. Motorola zarejestrowała `accepted` i odpowiedź WebRTC w dwóch próbach. Weryfikuje to wyświetlenie interfejsu i możliwość odebrania na tych egzemplarzach; rozmowy nie utrzymano przez minutę, a jakości toru audio/wideo nie mierzono.
- Logi systemowe i aplikacji z obu telefonów: `app/build/reports/verification-0.14.8/`. Build i testy emulatora nie zastępują sprawdzenia jakości rozmowy w różnych sieciach i na innych urządzeniach.

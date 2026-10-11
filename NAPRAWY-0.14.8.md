# Poprawki 0.14.8 (27)

- Aktywny bezpośredni czat utrzymuje cicha usługa systemowa z powiadomieniem bez treści i identyfikatora rozmówcy. Powiadomienie jest widoczne wyłącznie podczas zestawiania lub aktywnego czatu. Usługa utrzymuje CPU dla transportu WebRTC i kończy się razem z sesją, błędem albo limitem zestawiania.
- Przy powrocie aplikacji na pierwszy plan, także po wybudzeniu przez FCM, klient od razu ponawia sygnalizację zamiast czekać na wstrzymaną próbę ponownego połączenia. Samo otwarcie czatu nadal nie inicjuje kolejnego zaproszenia.
- Manifest używa typu usługi `remoteMessaging` przeznaczonego przez Androida do przekazywania wiadomości między urządzeniami. Usługa wymaga zwykłych uprawnień pierwszoplanowych, nie lokalizacji ani dostępu do urządzeń w pobliżu.

## Weryfikacja

- Testy jednostkowe, lint i testy instrumentacyjne sprawdzają ponowne połączenie po powrocie, prywatność powiadomienia oraz jego usunięcie po zakończeniu usługi.
- Powiadomienie i usługa systemowa pomagają utrzymać aktywny transport po schowaniu aplikacji. Nie gwarantują odbioru po utracie sieci, wymuszonym zatrzymaniu aplikacji ani restrykcjach oszczędzania energii producenta.
- Przywrócenie połączenia przychodzącego trzeba potwierdzić na S23 Ultra z zablokowanym ekranem po instalacji 0.14.8 na obu urządzeniach. Build i test emulatora nie dowodzą poprawnego działania systemowego ekranu połączenia na fizycznym telefonie.

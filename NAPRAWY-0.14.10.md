# Poprawki 0.14.10 (29)

- Karta kontaktu ma dwurzędowy układ: nazwa i identyfikator mają własną szerokość, a akcje nie ściskają tekstu ani nie powodują wieloliniowego rozciągania karty.
- Wybór typu połączenia (głosowe/wideo) jest dostępny w menu przycisku rozmowy.
- Pełnoekranowy intent z powiadomienia przychodzącego jest obsługiwany jawnie. Podczas wznowienia sygnalizacji aplikacja pokazuje ekran oczekiwania zamiast ekranu głównego.
- Oferta, która dotrze w trakcie ponownego łączenia, jest krótko buforowana i obsługiwana po uzyskaniu gotowości, zamiast od razu odpowiadać „zajęty”.

## Zakres potwierdzenia

- Kompilacja debug, lint i pełny zestaw instrumentacyjny (28 testów) zakończyły się powodzeniem na emulatorze.
- Test fizycznego odbioru na telefonach nie został wykonany w tej wersji; ADB widział wyłącznie emulator.
- Powiadomienie FCM musi zawierać `sessionKind=audio`, by poprawnie sklasyfikować rozmowę głosową na zablokowanym telefonie. Wymaga to wdrożenia zaktualizowanej funkcji Firebase z katalogu `functions` do właściwego projektu.
- Wybudzenie i ekran przychodzącej rozmowy nadal wymagają weryfikacji z zablokowanym S23 po instalacji nowej wersji i wdrożeniu funkcji.

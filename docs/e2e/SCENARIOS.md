# Scenariusze E2E

Zestaw uruchamia Control, Engine, Redis, PostgreSQL, runner i oba interfejsy w osobnym projekcie Compose. Dane są syntetyczne, a reguły mają unikalne nazwy. Po zakończeniu skrypt usuwa kontenery i wolumeny tego projektu.

## Automatyczne

| Scenariusz | Działanie i oczekiwany wynik |
| --- | --- |
| Reguła i alert | Zalogowany użytkownik tworzy regułę. Test wysyła zdarzenie do momentu aktywacji reguły w Engine, a następnie sprawdza alert widoczny w monitorze. |
| Python i zatwierdzenie | Health check zwraca UP. Skrypt oczekuje na zatwierdzenie, żądanie bez potwierdzenia zwraca 409, a kliknięcie Confirm zapisuje wynik i nazwę operatora. Wynik pozostaje widoczny po odświeżeniu. |
| Deduplikacja | Ponowne zdarzenie z tym samym kluczem zwiększa licznik i zachowuje te same kroki i ich wynik. |
| Kreator reguły z nowym runbookiem | Użytkownik buduje regułę i dwa kroki Python w jednym formularzu. Po dopasowaniu zdarzenia pierwszy skrypt wykonuje się automatycznie. Użytkownik zatwierdza drugi w panelu Alerts i sprawdza wynik połączonych kroków. |
| Named i inline Python | Utworzenie typu akcji i runbooka przez API, przekazanie wyniku między krokami i sprawdzenie kontekstu zdarzenia i alertu. |
| Błędy i timeout | Wyjątek i nieskończona pętla trafiają do historii jako ERROR i TIMEOUT. Kolejny krok jest pominięty. |
| Runner | Składnia, błędy serializacji, limit logów, zasobów i czasu oraz odmowa otwierania plików i gniazd są sprawdzane przez HTTP. |

Testy korzystają z rzeczywistych usług stosu. Przebieg przeglądarkowy używa API do utworzenia reguły i wysłania zdarzenia, a zatwierdzenie wykonuje w interfejsie. Na niepowodzeniu zapisuje e2e-results/failure.png i e2e-results/trace.zip.

## SMS i e-mail

Skrypt `scripts/test-notification-flow.py` loguje się prawdziwą sesją konta demo na jednorazowym stosie. Reguła używa zagnieżdżonych warunków all/any/not, podstawienia tytułu i domyślnej ważności zgodnych z REGULY_SPEC.md. Skrypt wysyła zdarzenia do Engine i porównuje historię dostaw w Control z treścią faktycznie odebraną przez Mailpit oraz lokalną bramkę SMS.

| Przypadek | Warunek zaliczenia |
| --- | --- |
| Błędne dane kontaktowe | API zwraca HTTP 400 dla nieprawidłowego numeru oraz adresu. |
| Awaria SMS | Bramka odpowiada 503. SMS ma status `retrying`, a Mailpit odbiera jedną wiadomość z oczekiwanym adresem, tematem, treścią i odnośnikiem do alertu. |
| Przywrócenie bramki | Worker ponawia dostawę. W historii widać co najmniej dwie próby i status `sent`. Bramka przechowuje jedną wiadomość z właściwym numerem, treścią i identyfikatorem dostawy. |
| Wyłączenie SMS | Dla kolejnego alertu powstaje dostawa e-mail. Historia tego alertu nie zawiera zadania SMS. |

Test integracyjny `DeliveryQueueIT` sprawdza współbieżne pobieranie zadań, odzyskanie wygasłej rezerwacji i zakończenie po pięciu próbach. `DeliveryPolicyTest` sprawdza próg ważności oraz pierwszeństwo preferencji osobistych.

## Uruchomienie

Wymagane są Docker Desktop z Compose v2, Python 3, Node.js, zależności control-panel oraz Chrome. W katalogu głównym repozytorium uruchom:

```sh
npm --prefix control-panel ci
E2E_USERNAME=demo.member E2E_PASSWORD='<hasło konta demo>' scripts/test-e2e.sh
```

Jeśli Chrome jest w niestandardowej lokalizacji, ustaw CHROME_BIN. Testy uruchamiają się sekwencyjnie. Pierwsza budowa obrazów może trwać kilka minut.

## Opóźnienie dostarczenia

```sh
E2E_USERNAME=demo.member E2E_PASSWORD='<hasło konta demo>' scripts/test-e2e.sh --latency
```

Test uruchamia osobny stos Compose. Po aktywacji reguły i jednym zdarzeniu rozgrzewkowym porównuje dwa profile: 30 zdarzeń z docelowym tempem 2/s i serię 30 z maksymalnie ośmioma równoległymi żądaniami HTTP. Każde zdarzenie ma osobny klucz deduplikacji. W tym stosie limit liczby dostaw jest podniesiony do 200 na kanał i odbiorcę, aby zmierzyć kolejkę także powyżej domyślnego progu 10.

Ten sam proces mierzy zegarem monotonicznym czas od rozpoczęcia żądania ingest do pierwszego odczytu identyfikatora wiadomości ze skrzynki zalogowanego odbiorcy, Mailpit albo lokalnej bramki SMS. Obserwator odpytuje wszystkie trzy API i robi 25 ms przerwy między cyklami. Pomiar zawiera czas odpytywania i przesyłania odpowiedzi. Punkt końcowy SMS oznacza przyjęcie przez symulator, e-mail oznacza widoczność w testowej skrzynce, a inbox oznacza dostępność przez API.

W konsoli pojawia się liczba zaobserwowanych dostaw oraz p50, p95 i maksimum w milisekundach dla każdego kanału i profilu. CSV w `e2e-results/perf-*.csv` zawiera również identyfikatory zdarzeń i puste pola dla brakujących dostaw. Brak dostawy kończy test błędem. To test porównawczy na lokalnym sprzęcie, a wielkość próby ogranicza interpretację ogona rozkładu.

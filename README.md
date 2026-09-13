# ElderBot V0.20 CORE REWORK

V0.20 przebudowuje rdzeń farmienia zamiast dokładać kolejne małe poprawki.
Sterowanie nadal odbywa się wyłącznie przez obraz ekranu, OCR i Android Accessibility;
wersja nie modyfikuje klienta ElderMT2 ani jego plików.

## Nowy rdzeń
- jawne stany pracy: SEARCH_ROUTE -> TARGET_APPROACH -> COMBAT -> PICKUP -> SEARCH_ROUTE,
- osobny RECOVER dla omijania przeszkód,
- wspólny Target Lock dla Boss / Metin / EXP, a nie tylko dla mobów EXP,
- kolejka priorytetów: BOSS > METIN > EXP,
- aktualny cel nie jest zmieniany co klatkę; wyższy priorytet może przejąć cel tylko przed rozpoczęciem walki,
- w COMBAT bot nie przeskakuje na innego przeciwnika po chwilowej utracie OCR,
- po utracie celu są krótkie próby ponownego namierzenia, dopiero potem powrót na trasę.

## Nawigacja
- bez celu bot pracuje na stabilnej trasie mapy,
- daleki Boss/Metin nie steruje joystickiem bezpośrednio: trasa ma najpierw przybliżyć postać do bezpiecznego korytarza,
- jeżeli trasa nie zbliża do celu przez kilka sekund, cel jest pomijany zamiast wciskać postać w tę samą przeszkodę,
- lokalne dojście do celu ma pomiar realnego postępu,
- bounded anti-stuck: obejście ma limit prób; niedostępny cel jest porzucany i bot wraca do trasy,
- Auto EXP ma szybsze sterowanie dla poruszających się mobów, ale zachowuje jeden zablokowany cel.

## Walka i moduły pomocnicze
- atak uruchamia się dopiero po potwierdzeniu bliskiego zasięgu,
- Auto Skills działa tylko w stanie COMBAT, więc nie powinien przerywać wyszukiwania ani dojścia do celu,
- Auto Potions i Auto Revive pozostają niezależnymi modułami pomocniczymi,
- stały START/STOP oraz przewijany panel pozostają bez zmian.

## Pickup V0.20
- pickup korzysta z ikony ręki pokazanej na screenach ElderMT2,
- bot najpierw wizualnie sprawdza, czy przycisk ręki jest faktycznie widoczny,
- klika tylko wtedy, gdy ikona jest wykryta,
- po dwóch kolejnych klatkach bez ręki kończy pickup i wraca do farmienia.

## Ważne ograniczenie
To nadal screen-only bot. Bez danych z klienta nie zna prawdziwych współrzędnych świata ani geometrii kolizji.
V0.20 ogranicza błędne decyzje przez Target Lock, trasę, pomiar postępu i porzucanie niedostępnych celów,
ale pełny przestrzenny pathfinding będzie wymagał później pewnej lokalizacji pozycji na mapie.

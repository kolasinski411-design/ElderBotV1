# ElderBot V0.21 AUTO HUNT REWORK

V0.21 przebudowuje Auto EXP w kierunku oficjalnych Auto Łowów Metin2, ale nadal działa wyłącznie przez obraz ekranu, OCR i Android Accessibility. Nie modyfikuje klienta ElderMT2 ani jego plików.

## Auto Łowy
- tryb nie jest przypisany do Dzikich Psów ani żadnej konkretnej nazwy moba; bierze wszystkie wiarygodne czerwone etykiety potworów w polu gry,
- najbliższy widoczny mob ma pierwszeństwo,
- suwak `Zasięg Auto Łowów` ogranicza lokalny obszar szukania celu,
- gdy działa tylko Auto Łowy i nie ma celu w zasięgu, postać nie wędruje losowo po całej mapie; czeka i ponawia skan,
- target przed walką jest śledzony po położeniu, więc chwilowo inny odczyt OCR nazwy nie kasuje celu.

## HP Target Lock i walka
- po dojściu do przeciwnika bot najpierw próbuje go zaznaczyć,
- atak NIE startuje, dopóki ElderMT2 nie pokaże górnego paska HP wybranego celu,
- po pojawieniu się paska HP bot zatrzymuje joystick i przechodzi w pewny stan COMBAT,
- podczas walki OCR czerwonej nazwy nie steruje już ruchem,
- zniknięcie paska HP przez kilka kolejnych kontroli kończy walkę i uruchamia pickup,
- jeśli celu nie uda się zaznaczyć, bot porzuca go zamiast uderzać w powietrze.

## Pickup
- po walce bot przechodzi do osobnego stanu PICKUP,
- sprawdza wizualnie ikonę ręki po prawej stronie ekranu i klika ją tak długo, jak jest widoczna,
- po START panel automatycznie się zwija, żeby nie zasłaniać przycisku ręki, nazw mobów ani obszaru analizy ekranu,
- współrzędne ikony ręki są skalowane względem rozdzielczości ekranu.

## Pozostałe moduły
- priorytet pozostaje: Boss / Miniboss > Metin > Auto Łowy,
- Pickup, Auto Skills, Auto Potions, Auto Revive i przewijany panel zostają,
- START/STOP pozostaje na stałe u góry panelu.

## Ograniczenie
Zasięg Auto Łowów jest szacowany wizualnie na ekranie, a nie w metrach świata gry, ponieważ wersja screen-only nie odczytuje danych klienta.

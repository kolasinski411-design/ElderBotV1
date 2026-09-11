# ElderBot V1 — przygotowanie Android

To jest wersja przygotowawcza aplikacji dla Androida.

## Co działa w tej wersji
- usługa Accessibility,
- wykonywanie zrzutu aktualnego ekranu przez API Androida,
- zapis zrzutu do `Pictures/ElderBot`,
- bezpieczny test pojedynczego dotyku na środku ekranu,
- przyciski START/STOP jako podstawa przyszłej pętli bota.

## Czego jeszcze nie ma
- automatycznego wykrywania Metinów,
- wyboru celu,
- nawigacji po mapie,
- automatycznego farmienia i zbierania.

Te elementy wymagają najpierw obrazu referencyjnego Metina z ElderMT2 i testów na rzeczywistym ekranie gry.

## Budowanie APK
Projekt zawiera workflow GitHub Actions w `.github/workflows/build.yml`. Po uruchomieniu workflow wynikowy `app-debug.apk` zostanie udostępniony jako artefakt.

Projekt nie modyfikuje plików gry ani nie zawiera mechanizmów obchodzenia zabezpieczeń serwera.


Poprawka V1.1: usługa dostępności ma jawnie włączoną możliwość wykonywania zrzutów ekranu (`canTakeScreenshot`), wymaganą przez Android AccessibilityService.

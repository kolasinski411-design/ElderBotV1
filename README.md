# ElderBot V0.9 FARM LOOP

Android Accessibility + on-screen OCR prototype for ElderMT2.

Core loop in this build:
- search visually for Metin labels,
- continuous smoothed joystick steering,
- detect lack of progress and attempt left/right obstacle bypass,
- reacquire the Metin after the bypass,
- select and repeatedly attack the Metin,
- detect disappearance, then OCR/tap nearby loot labels,
- return to searching for the next Metin.

Notes:
- Obstacle avoidance is reactive. It does not read game map/navigation data.
- Exact attack/pickup button coordinates remain screen-layout dependent.
- No anti-cheat bypass, injection, wallhack, or game-file modification is used.

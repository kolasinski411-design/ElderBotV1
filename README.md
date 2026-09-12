# ElderBot V0.14 MULTI-MAP NAV

Nawigacja przebudowana pod wiele map bez ingerencji w klienta gry:
- OCR rozpoznaje znane nazwy map ElderMT2 i zapamiętuje ostatni profil,
- osobny profil trasy dla M1/M2, Doliny, Pustyni, Sohan, Piekielnej Ziemi, Lasów, Wężowego Pola i Hwang,
- pamięć zablokowanych kierunków osobno dla każdej mapy,
- lokalny fingerprint terenu: utknięcie zapisuje problematyczny kierunek także dla podobnego widoku terenu,
- kierunki, które dają realny postęp, z czasem odzyskują priorytet,
- po nieudanym dojściu cel nadal jest odpuszczany zamiast wciskania postaci w tę samą przeszkodę,
- zachowane Farmbot / Pickup / Auto Skills / Auto Potions / Auto Revive / overlay.

To nadal nawigacja ekranowa (OCR + Accessibility), a nie dostęp do mapy kolizji klienta.

# WhatsApp Opruimer

Android-app die afbeeldingen **zonder personen** uit een zelfgekozen map (bijv. *WhatsApp Images*) naar de prullenbak verplaatst. Je ziet en controleert altijd eerst wat er weg gaat.

## Zo werkt het
1. **Map kiezen.** De mapkiezer opent direct bij `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images`. Submappen (zoals `Sent` en `Private`) worden alleen meegenomen als je dat aanvinkt.
2. **Scannen.** Alles gebeurt op de telefoon, er gaat niets naar internet:
   - ML Kit gezichtsdetectie, ook kleine gezichten (vanaf 5% van de beeldbreedte);
   - MediaPipe objectdetectie (EfficientDet-Lite2, klasse "person") voor hele personen, ook van achteren, van ver weg of half in beeld.
   De scan draait als achtergronddienst met een melding, dus het scherm mag uit. Stop je halverwege, dan gaat de volgende scan verder waar hij was.
   Afbeeldingen van de laatste 7 dagen (instelbaar) worden overgeslagen. Voor de datum telt die in de bestandsnaam (`IMG-20240131-WA0001.jpg`) eerst.
3. **Controleren** in vier groepen:
   | Groep | Standaard |
   |---|---|
   | Geen persoon | aangevinkt (gaat weg) |
   | Twijfel | blijft staan |
   | Screenshots (verhouding ≥ 1,95:1 of "screenshot" in de naam) | weg als er geen persoon op staat, of altijd weg als je dat instelt |
   | Met persoon / onleesbaar | blijft staan |

   Tik op een tegel om hem aan of uit te zetten. Houd een tegel vast om de afbeelding groot te bekijken.
4. **Naar prullenbak.**
   - Android 11 en nieuwer: de prullenbak van Android zelf. Android vraagt om bevestiging (per 300 foto's). Je kunt foto's 30 dagen terugzetten vanuit de Galerij of Google Foto's.
   - Bestanden die Android niet in de mediabibliotheek heeft, en alles op Android 8 t/m 10: deze worden verplaatst naar de submap `_Opruimer_prullenbak` in de gekozen map. Die map kun je zelf legen.

De app verwijdert nooit iets definitief.

**Gevoeligheid** (persoonsscore van de objectdetectie; een gevonden gezicht betekent altijd "met persoon"):

| | met persoon vanaf | twijfel vanaf |
|---|---|---|
| Voorzichtig (standaard) | 0,35 | 0,15 |
| Normaal | 0,50 | 0,25 |
| Streng | 0,60 | 0,40 |

Gezichten op cartoons, memes, beelden of een tv-scherm tellen ook als persoon; die blijven dus staan.

## Installeren
Download `WhatsAppOpruimer.apk`, open hem op je telefoon en sta "installeren uit onbekende bronnen" toe. Werkt op Android 8.0 en nieuwer. Geef bij de vraag om foto's toegang tot **alle foto's**, anders kan de app de prullenbak van Android niet gebruiken.

## Bouwen
GitHub Actions (`.github/workflows/whatsapp-opruimer.yml`) draait de unittests en bouwt bij elke push de APK. Lokaal: `./gradlew assembleRelease` (Android SDK 35 nodig).

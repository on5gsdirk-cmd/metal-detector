# PI Detector ON5GS — Spirit PI v3

Eigen firmware en Android-app voor een zelfgebouwde **Spirit PI 2.0 ESP32** pulse-inductie-metaaldetector
(DOIT ESP32 DevKit V1, IRF740-driver, TL072-versterker).

- `firmware/spirit_pi_v3/`: nieuwe ESP32-firmware (Arduino). Alle meetparameters zijn via Bluetooth instelbaar.
- `app/`: Android-app (Kotlin, Jetpack Compose).
- `core/`: rekenkern van de app (protocol, balance, verhouding, leermodus, geluid), met unit tests.
- `docs/`: originele sketch v2.0.0, schema, PCB-bestanden en de opdracht.

> De N.E.C.O-naam en het N.E.C.O-logo worden niet gebruikt; de app heet **PI Detector ON5GS** en heeft een eigen icoon.

---

## 1. Firmware flashen

1. Installeer de **Arduino IDE** (2.x).
2. *Bestand → Voorkeuren → Extra board manager URL's*:
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`
3. *Hulpmiddelen → Board → Boardbeheer*: installeer **esp32 by Espressif**, versie **2.0.x** (getest met 2.0.17) of **3.0.x** (getest met 3.0.7). De CI compileert beide.
4. Open `firmware/spirit_pi_v3/spirit_pi_v3.ino`. Het bestand `pi_protocol.h` in dezelfde map opent automatisch mee.
5. Board: **DOIT ESP32 DEVKIT V1**, juiste COM-poort, uploaden.
   Krijg je de melding *"Sketch too big"*? Kies dan bij *Partition Scheme* **Huge APP** (als je board-menu die optie heeft).
6. Open de seriële monitor op **115200 baud**, regeleinde **Newline**. Je ziet `CFG,...`-, `INFO,...`- en `D,...`-regels.

Kant-en-klare `.bin`-bestanden staan ook bij elke CI-run (artifact `firmware-core-…`).

### Wat is er veranderd t.o.v. v2.0.0

| | v2.0.0 (origineel) | v3.0 |
|---|---|---|
| Metingen per puls | 1 (`analogRead` op 60 µs) | 2: op **d1** en **d2** µs na de puls |
| Timing | `delayMicroseconds` + `micros()`-lus | `esp_timer` + meettaak op core 1; puls via directe registers; geen Bluetooth-verkeer tijdens de meting |
| Instellingen | EEPROM, enkel duty/freq | **NVS (Preferences)**: pw, d1, d2, f, avg, vcal, lowbat, legacy, naam, met grenzen |
| `p`-commando | zette freq op 0 → deling door nul | herstelt de standaardwaarden |
| Disconnect | `ESP.restart()` | blijft gewoon wachten op een nieuwe verbinding |
| Lage batterij | — | pulsen stoppen onder 9,5 V (instelbaar, hysterese 0,3 V), melding `ERR,lowbat` |

### Tekstprotocol

Elke regel eindigt op `\n`. Je kunt het protocol testen met een seriële terminal-app op Android
(bv. *Serial Bluetooth Terminal*) of via USB.

App → detector:

| Commando | Betekenis |
|---|---|
| `GET` | stuur `CFG` en `INFO` |
| `SET pw 130` | pulsbreedte 20–300 µs |
| `SET d1 20` | vroege meetdelay 8–150 µs (d2 schuift mee als die te klein wordt) |
| `SET d2 60` | late meetdelay d1+10 – 300 µs |
| `SET f 60` | pulsfrequentie 20–200 Hz |
| `SET avg 4` | middeling 1–32 pulsen |
| `SET vcal 1.02` | batterijkalibratie 0.8–1.2 |
| `SET lowbat 9.5` | batterijgrens in volt (0 = uit, handig om op de werkbank via USB te testen) |
| `SET legacy 1` | stuur het oude `<signaal/freq/duty/volt>`-formaat (voor de oude N.E.C.O-app) |
| `SET name Mijn_PI` | Bluetooth-naam (actief na `SAVE` en herstart) |
| `SAVE` | huidige instellingen blijvend opslaan |
| `DEFAULTS` | standaardwaarden terugzetten en opslaan (naam blijft) |
| `PING` | antwoordt `OK` |
| `n` / `m` | oud: pulsbreedte +10 / −10 µs (vroeger "duty" ±1), direct opgeslagen |
| `j` / `k` | oud: frequentie +10 / −10 Hz, direct opgeslagen |
| `p` | oud: reset naar standaardwaarden |

Gebruik hoofdletters voor de nieuwe commando's. Een kleine letter `n m j k p` aan het begin van een regel wordt als oud commando gezien.

Detector → app:

```
D,<s1>,<s2>,<vbat>         ~28 Hz, ruwe 12-bit waarden (gemiddelde van de laatste avg pulsen), batterij in V
CFG,pw=130,d1=20,d2=60,f=60,avg=4,vcal=1.000,lowbat=9.5,legacy=0,name=ESP32_Spirit_PI-3,fw=3.0
INFO,adc_us=..,pw_us=..,t1_us=..,t2_us=..   gemeten werkelijke timing
OK  |  OK,reboot  |  ERR,<range|value|syntax|unknown|unknown_key|too_long|lowbat>
```

**Timing**: `t1_us` en `t2_us` geven aan wanneer de ADC-conversie werkelijk start, gerekend vanaf het einde van de puls.
Eén conversie duurt `adc_us` (meestal 10–40 µs, afhankelijk van de core-versie). Ligt d2 minder dan `adc_us` na d1,
dan komt de tweede meting later dan gevraagd. Vergelijk daarom altijd met `INFO`.

---

## 2. App installeren

1. Ga op GitHub naar **Actions → Build →** de laatste groene run → artifact **PIDetector-debug-apk** (zip met de APK).
   Bij een tag `v1.0.0` enz. verschijnt de APK ook onder **Releases**.
2. Zet de APK op je telefoon en installeer hem (sta *installeren uit onbekende bronnen* toe).
3. Bij de eerste start vraagt de app toestemming voor Bluetooth (Android 12+) of locatie (Android 8–11).

### Eerste verbinding

1. Zet de detector aan. Koppel hem in **Android-instellingen → Bluetooth** (naam `ESP32_Spirit_PI-3`).
2. Open in de app het tabblad **Bluetooth** en tik op de detector. De app onthoudt hem en verbindt voortaan automatisch, ook na een onderbreking.
3. Houd de spoel weg van metaal. De app balanceert automatisch bij de eerste data; daarna kun je altijd op **BALANCE** drukken.
4. Geen hardware bij de hand? Zet in het Bluetooth-tabblad de **simulatiemodus** aan.

### Schermen

- **Zoeken**: batterij, verbinding, mute, halfronde meter (signaal na balance, met piekmarkering), doel-indicator met
  geleerde match, live grafiek van s1/s2 (±10 s, inklapbaar), BALANCE, gevoeligheid ±, auto-balance.
- **Detector**: pw, d1, d2, f, avg en vcal met slider en invoerveld. *Toepassen* stuurt `SET`, *Opslaan in detector* stuurt `SAVE`,
  *Fabrieksinstellingen* stuurt `DEFAULTS`. Je ziet ook de gemeten timing van de firmware.
- **Geluid**: thema, VCO- of piepmodus, toonbereik, brom in rust, piepdrempel, toonhoogte volgens de doel-indicator,
  volume, detectiedrempel, auto-balance-snelheid en polariteit.
- **Profielen**: *Klein/goud*, *Universeel*, *Groot/diep* en eigen profielen. Je kunt ze laden, bewerken, hernoemen,
  verwijderen en herstellen. Hier zit ook de **leermodus**.
- **Bluetooth**: gekoppelde apparaten, simulatiemodus.

### Thema's

- **Standaard**: donker met rode neon en een rustig radarraster.
- **Muntenshuppers**: psychedelische, draaiende regenboogachtergrond die wilder wordt bij sterk signaal. Tijdens het zoeken
  hoor je **walvisgezang**; als er metaal gevonden wordt **rinkelen er muntjes**. De gewone detectortoon kan erbij
  (instelling *Detectortoon ook laten horen*).

Alle geluiden worden live gegenereerd (synthese), er zitten geen geluidsbestanden in de app.

---

## 3. Instellingen begrijpen

| Parameter | Effect |
|---|---|
| **pw** (pulsbreedte) | Langer = meer energie in de spoel = meer diepte op grote voorwerpen, maar meer stroom en warmte in IRF740 en spoel. Kleine voorwerpen hebben er weinig baat bij. |
| **d1** (vroege delay) | Hoe korter na de puls, hoe beter je kleine, dunne en slecht geleidende voorwerpen ziet (goud, folie, dunne ringen). Maar je zit dan dichter op de naslinger van de spoel: meer ruis en grondeffect. Te kort = verzadigd signaal. |
| **d2** (late delay) | Op dit moment is het signaal van kleine voorwerpen al weg; grote en goed geleidende voorwerpen (koper, zilver, groot ijzer) geven nog signaal. |
| **f** | Meer pulsen per seconde = vlottere reactie en meer middeling; meer verbruik. |
| **avg** | Meer middeling = rustiger, maar trager. Bij snel zwaaien laag houden (2–4). |
| **R16** (trimmer) | Regelt hoeveel van de opamp-uitgang de ESP32 ziet. Stel zo in dat **s1 zonder metaal rond 1500–2500** ligt. Haalt s1 4095 (of 0), dan is de ADC verzadigd en zie je niets meer. Na het aanpassen opnieuw balanceren. |
| **R7** (390 Ω dempweerstand) | Dempt de spoel na de puls. Te hoog: naslingering, dus d1 moet langer. Te laag: tragere afval en minder gevoelig voor kleine voorwerpen. Met een scoop op de drain (TP: PSI) zie je het meteen. Kies de kortste d1 waarbij het signaal niet meer slingert. |

### Doel-indicator en leermodus: beperkingen

Een PI-detector meet hoe snel de wervelstromen in een voorwerp uitdoven. De verhouding **s2/s1** (laat/vroeg, na balance) zegt iets
over die **tijdconstante**:

- **laag** (snel): klein, dun, slecht geleidend: folie, goud, kleine ringetjes, lood
- **hoog** (traag): groot, dik, goed geleidend: koperen en zilveren munten, grote stukken metaal

Dat is **geen** metaalidentificatie. Een grote aluminium dop en een gouden ring kunnen dezelfde verhouding geven, en ijzer is niet
betrouwbaar te onderscheiden. Diepte, oriëntatie, vorm, grondmineralisatie en de gekozen delays veranderen de waarde.
Gebruik het als hint en graaf bij twijfel.

In de **leermodus** sla je de piek en de verhouding van een gekend voorwerp op. Tijdens het zoeken toont de app het voorwerp dat het
best overeenkomt, met een percentage. Geleerde voorwerpen tellen alleen mee bij dezelfde pw/d1/d2 waarmee ze geleerd werden.

---

## 4. Ontwikkelen

```bash
./gradlew :core:test                 # kernlogica, geen Android SDK nodig
./gradlew :app:assembleDebug         # vereist Android SDK (ANDROID_HOME of local.properties)
g++ -std=c++11 -Ifirmware/spirit_pi_v3 firmware/test/test_protocol.cpp -o /tmp/t && /tmp/t
```

De `:app`-module wordt alleen geladen als er een Android SDK gevonden wordt. Zo kunnen de kerntests overal draaien.

GitHub Actions (`.github/workflows/build.yml`): bij elke push lopen de tests en worden een debug-APK en de firmware voor
esp32-core 2.0.17 en 3.0.7 gebouwd. Bij een tag `v*` volgt een GitHub-release met de APK.
De release-APK is (bij gebrek aan een eigen keystore) met de debug-sleutel ondertekend.

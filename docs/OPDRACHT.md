# Prompt voor Claude Code — eigen Spirit PI detector-app + firmware

> Zet deze map in een nieuwe GitHub-repo samen met:
> - `docs/spirit_pi_esp32__sketch_v_2.0.0.ino` (originele sketch)
> - `docs/Schematic_Spirit_PI_2.0_ESP32.pdf` (schema)
> - `docs/screenshots/` (screenshots van de bestaande N.E.C.O-app als stijlreferentie)
>
> Start Claude Code in de repo en plak alles hieronder.

---

## Opdracht

Bouw voor mijn zelfgebouwde **Spirit PI pulse-induction metaaldetector** (ESP32) twee dingen:

1. **Nieuwe ESP32-firmware** (Arduino) waarmee alle meetparameters via Bluetooth instelbaar zijn.
2. **Een eigen Android-app (Kotlin, Jetpack Compose)**, gebouwd tot APK via GitHub Actions.

Lees eerst `docs/` (originele sketch + schema) zodat je de hardware begrijpt. Werk in stappen, commit per stap, en vraag me iets als een hardwarekeuze onduidelijk is.

## Hardware (vast, niet wijzigen)

- Bord: **DOIT ESP32 DevKit V1** (klassieke ESP32, Bluetooth Classic beschikbaar).
- Voeding: 12 V op B+ → step-down naar 5 V op VIN. Spoel en TL072 op 12 V.
- **Pulse pin: GPIO13** → BC547/BC557 → IRF740 → spoel (~20 cm, 20 windingen, ca. 200–300 µH).
- **Signaal: A0 = GPIO36 (VP)**, opamp-uitgang via trimmer R16. ADC 12-bit, 11 dB attenuatie.
- **Batterij: A7 = GPIO35**, deler 100 kΩ / 47 kΩ, referentie ~3,6 V (kalibratiefactor instelbaar maken).
- Huidige firmware: na `delayTime` µs na de puls één `analogRead`, gedeeld door 10, gestuurd als `<signaal/freq/duty/volt>`. Commando's `n/m` (duty ±1), `j/k` (freq ±10), `p` (reset → bug: freq 0 → deling door nul).

## Deel 1 — Firmware (`firmware/spirit_pi_v3/spirit_pi_v3.ino`)

- `BluetoothSerial`, naam instelbaar, standaard `ESP32_Spirit_PI-3`.
- Instellingen in **Preferences (NVS)** i.p.v. EEPROM, met grenzen en veilige defaults:
  | Parameter | Default | Bereik |
  |---|---|---|
  | `pw` pulsbreedte (µs) | 130 | 20–300 |
  | `d1` vroege meetdelay (µs) | 20 | 8–150 |
  | `d2` late meetdelay (µs) | 60 | d1+10 – 300 |
  | `f` pulsfrequentie (Hz) | 60 | 20–200 (nooit 0) |
  | `avg` middeling (pulsen) | 4 | 1–32 |
  | `vcal` batterij-kalibratie | 1.000 | 0.8–1.2 |
- Per puls: GPIO13 hoog gedurende `pw`, laag, dan sample op `d1` en op `d2` (houd rekening met de duur van `analogRead`, ~10 µs; gebruik zo nodig `adc1_get_raw`/snellere ADC-aanroep en meet de werkelijke timing). Timing met `esp_timer`/hardware-timer i.p.v. busy `micros()`-loop waar mogelijk; geen Bluetooth-zenden midden in de meetcyclus.
- **Tekstprotocol** (regels eindigen op `\n`):
  - App → ESP: `GET`, `SET pw 130`, `SET d1 20`, `SET d2 60`, `SET f 60`, `SET avg 4`, `SET vcal 1.02`, `SAVE`, `DEFAULTS`, `PING`.
  - ESP → app: `D,<s1>,<s2>,<vbat>` met ~25–30 Hz (gemiddelde over `avg` pulsen, ruwe 12-bit waarden), `CFG,pw=..,d1=..,d2=..,f=..,avg=..,vcal=..,fw=3.0`, `OK`, `ERR,<reden>`.
  - Blijf de oude één-letter-commando's (`n m j k`) ondersteunen en, optioneel via instelling, het oude `<a/b/c/d>`-formaat sturen zodat de bestaande N.E.C.O-app ook nog werkt.
- **Geen `ESP.restart()` bij disconnect**; gewoon opnieuw wachten op een verbinding.
- Pulsen stoppen als batterij < 9,5 V (instelbaar), melding `ERR,lowbat`.
- Seriële debug op 115200 met dezelfde regels.

## Deel 2 — Android-app (`app/`)

**Techniek:** Kotlin, Jetpack Compose, minSdk 26, targetSdk 35, Bluetooth Classic SPP (UUID `00001101-0000-1000-8000-00805F9B34FB`). Correcte runtime-permissies: `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` (Android 12+), locatie voor oudere versies. Automatisch opnieuw verbinden. Een **simulatiemodus** met nepdata zodat ik de UI zonder hardware kan testen.

**Stijl:** donker thema met rode neon-accenten en afgeronde kaarten, in de geest van de N.E.C.O Spirit PI-app (zie screenshots), maar met **eigen naam en icoon** ("PI Detector ON5GS") — geen logo of naam van N.E.C.O overnemen. Nederlandstalige UI, Engels als tweede taal.

**Hoofdscherm:**
- Statusbalk: batterijspanning, verbonden/niet verbonden, connect-knop.
- Grote halfronde **signaalmeter** + getal (signaal na balance).
- **"Doel-indicator"**: horizontale balk op basis van de verhouding `s2/s1` (na balance) van *snel / klein-dun-goud-folie* naar *traag / groot-koper-zilver*. Duidelijk als indicatie labelen, niet als zekerheid.
- **Live grafiek** van s1 en s2 (laatste ~10 s), te verbergen.
- Grote **Balance**-knop: neemt gemiddelde van ~1 s als nulpunt (aparte nul voor s1 en s2). Optioneel auto-balance (langzame drift-correctie, instelbaar).
- Gevoeligheid +/–.

**Geluid (AudioTrack, continu gegenereerd, geen afspeelbestanden):**
- **VCO-modus:** toonhoogte volgt het signaal, bv. 200 Hz in rust → 2000 Hz bij sterk signaal (bereik instelbaar), met lage "threshold hum" in rust aan/uit.
- **Piepmodus:** piep boven een instelbare drempel, piepsnelheid stijgt met signaal.
- Optioneel: toonhoogte koppelen aan de doel-indicator (laag = traag/groot, hoog = snel/klein).
- Volume, mute-knop, geen klikken bij toonwissels (fase-continu).

**Instellingenscherm (detector):**
- Sliders + numerieke invoer voor `pw`, `d1`, `d2`, `f`, `avg`, `vcal` met de grenzen uit de firmware; "Toepassen" stuurt `SET`, "Opslaan in detector" stuurt `SAVE`; "Fabrieksinstellingen" stuurt `DEFAULTS`.
- Uitleg per parameter in één zin (bv. "korte delay = gevoeliger voor kleine voorwerpen, meer ruis").

**Profielen:**
- Ingebouwd: **Klein/goud** (pw 80, d1 15, d2 40, f 100), **Universeel** (pw 130, d1 20, d2 60, f 60), **Groot/diep** (pw 220, d1 40, d2 120, f 40). Waarden als startpunt, door mij aanpasbaar.
- Eigen profielen opslaan/hernoemen/verwijderen (DataStore of Room).
- **Leermodus:** "Leer voorwerp" → ik beweeg een gekend voorwerp (bv. "aluminium lipje", "koperen munt", "ijzeren spijker") over de spoel, app slaat piekwaarde en `s2/s1`-verhouding op. Tijdens het zoeken toont de app het best overeenkomende geleerde voorwerp met een matchpercentage.

**Bluetooth-scherm:** gekoppelde apparaten tonen, kiezen, onthouden; uitleg dat koppelen via Android-instellingen gebeurt.

## Deel 3 — Build & oplevering

- Gradle Kotlin DSL, versiecatalogus.
- **GitHub Actions**: bij elke push een debug-APK als artifact; bij een tag `v*` een release met de APK.
- `README.md` in het Nederlands: firmware flashen (Arduino IDE, bord *DOIT ESP32 DEVKIT V1*, esp32-core 2.0.x of 3.0.x), APK installeren, eerste verbinding, uitleg over delay/pulsbreedte/R16/R7 en over de beperkingen van metaalonderscheid bij PI.
- Unit tests voor protocol-parser en balance/ratio-berekening.

## Werkvolgorde

1. Firmware + protocol, testbaar via een seriële terminal-app.
2. App-skelet: Bluetooth, permissies, simulatiemodus, ruwe data tonen.
3. Meter, balance, geluid.
4. Instellingen + profielen.
5. Doel-indicator + leermodus.
6. CI, README, opkuis.

Vat na elke stap kort samen wat ik op de hardware moet testen.

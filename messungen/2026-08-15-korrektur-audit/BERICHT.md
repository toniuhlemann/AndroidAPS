# Korrektur-/Turn-/TBR-Audit (read-only, Phase 1+2 des Briefs)

Nach `FUSE_Correction_Turn_Positive_TBR_Audit_Brief.md` (Codex-Vorlage).
Stand: HEAD `e393bdc110`, 15.08.2026. Datenbasis: lokaler FUSE-Trail
11.08. 19:06 - 15.08. 11:16 (4938 Zyklen, VirtualPump/offene Schleife,
13 Buildstaende) + prod-aisf-Logs 12.-15.08. (nur lesend). KEIN Bau-GO
aus diesem Bericht; Phase 3 (Counterfactual Replay) und 4 (VirtualPump)
stehen aus. 54 Audit-Agenten, KERN-Befunde adversariell gegengeprueft
(3 Verifikationen durch transiente API-Fehler entfallen, als
UNVERIFIZIERT markiert; 3 Formulierungen in der Verifikation
abgeschwaecht, unten eingearbeitet).

## 1. Tonis Kernfrage: reicht Stoerung r - oder bremst sie?

**r allein reicht nicht, und sie bremst an zwei belegten Stellen.**

Der Schaetzer selbst ist wie dokumentiert gebaut (Theil-Sen, 18-min-
Fenster, 2-min-Paarabstand, null statt erfundener Null - alle
Brief-4.1-Annahmen am Code bestaetigt, Sprungantwort numerisch
reproduziert: 0 % bis Minute 5, 50 % Minute 9, 100 % ab Minute 13;
real eher laenger, Q1-Lag ungemessen).

Die Signal-Landkarte (Brief 4.2) zeigt die Architekturentscheidung:
**rSigned ist die EINZIGE unbegrenzt dosis-erhoehende Spur** (Bedarf +
Rampe). Die schnellen Spuren (ukfRate, fastDrive) duerfen jederzeit
bremsen (Bremsbahn-Minimum, Fenster-Schliessung, Marker-Turn,
subStep-Verwurf), aber Gas geben nur ueber den gehuellten OnsetChannel
(1,5-U-Huelle). Rohsekante: rein diagnostisch. Die Asymmetrie
"schnell bremsen, traege bestaetigt Gas" ist konsequent gebaut -
mit zwei Loechern:

1. **Ohne offenen/mit verbrauchtem OnsetChannel gibt es keinen
   schnellen Gas-Pfad.** Der Code benennt das selbst ("FUSE bleibt zu
   zaghaft"). Trail: Onset-Signale drehen in 3-5 min (rSigned>0 sogar
   median 3 min VOR dem Tal!), aber die erste AUTO-Abgabe kommt erst
   nach **median 40 min** - davon ~12 min bis BG>=Ziel und ~19 min
   Ziel-/Totband-POLITIK, nicht Schaetzerlag. Der Marker-Kanal liefert
   am selben Ereignistyp nach 1 min.
2. **NEU - der UEBERGABE-DIP:** am R_CONFIRMED-Punkt (r erreicht 0,5)
   schlaeft der OnsetChannel, die Rampe faellt von f=1 auf ~0 und der
   Mittelbahn-Antrieb von der Kanalhebung auf ~0,5 - FUSE sagt fuer
   ~4-6 Minuten NACH bestaetigtem Anstieg weniger zu als in den
   Kanal-Minuten davor. (Mengenwirkung im Trail noch unbeziffert -
   OFFEN, Telemetrie-Stufe.)

**Auf der Turn-Seite bremst r dagegen NICHT** - weil die Aktuation gar
nicht an ihr haengt: Turns erkennt der schnelle Kanal in 3-6 min
(ukf<=0 median +5,5 min nach Peak), r braucht 13-19 min - aber die
letzte AUTO-Publikation liegt median **+4 min** nach dem Peak, und
gegen negative UKF-Rate flossen in 4 Tagen ausserhalb markerAuth nur
**0,25 U** (3 Abgaben, alle "hoch + fallend + Landung weit ueber
Ziel" = Pflicht-Gegenprobe 1, dort ist Dosieren vertretbar). Die
nominell 9,00 U "nach Turn" stecken fast vollstaendig in
Wieder-Anstiegen innerhalb der Peak-Tal-Fenster. markerAuth-Abgaben in
Fallfenstern folgten ausnahmslos einer frischen manuellen
Scharfschaltung (Brief F: kein Turn-Versagen; 3 Marker bewusst in
fallende Kurven gedrueckt).

## 2. Turn-Latenzbericht (Brief B; 30 Turns / 30 Onsets ohne Luecke)

| Grenze | Median nach Roh-BG-Peak |
|---|---|
| q1-Schritt negativ | +3,0 min |
| ukfRate <= 0 | +5,5 min |
| rSigned < 0,5 | +13,5 min |
| fastDrive <= 0 | +16,4 min |
| rSigned <= 0 | +19 min |
| **letzte AUTO-Publikation** | **+4,0 min** |
| formale Observer-Phase TURN | 0 von 4938 Zyklen erreicht |

"Erste Schutz-TBR" taugt als Mass nicht: 88 % aller Zyklen sind ohnehin
ZERO_TEMP/NO_NEW_POSITIVE (Default-Rueckhalteregime); bei 18/30 Turns
war der Rueckhaltezustand schon am Peak aktiv.

## 3. Ursachenzerlegung der Korrekturmenge (Brief A/C)

31 reine Korrekturfenster (kein Marker, kein Kredit): 244 Abgaben,
24,40 U von 69,45 U gesamt. Das Brief-Referenzfenster 12.08.
08:00-08:51 (26 Abgaben, 1,70 U) reproduziert EXAKT inkl. aller
Teilmengen.

- **86 % der reinen Menge (21,65 U) liefen unter kinematisch
  geoeffnetem Mahlzeitenfenster mit echtem positivem Antrieb** (r>0,5,
  Landung +30..+138 ueber Ziel) - sachlich begruendet.
- Strikt statische Korrektur (mealWindow=false): nur **3,45 U/67
  Abgaben** - dort konzentriert sich das fragliche Verhalten: 25 % der
  Menge bei r<=0, 46 % bei negativem Gesamt-IOB, 2/3 der Abgaben
  smbRatio|subStep-gekappt.
- **Der faktische Integralregler ist der Ratio-Pfad, nicht der
  Carry** (Verifikation hat die Brief-C.4-Ursache verschoben):
  insulinReq wird jede Minute neu aus einer Bahn gerechnet, die einer
  gelieferten Einheit am 30-min-Horizont nur ~15 % ihrer Vollwirkung
  gutschreibt. Rechenbeispiel (ISF 90, Ratio 0,15, Bedarf 0,24 U):
  2,16 U/h Tropfrate, Nominalmenge nach ~7 min ueberschritten,
  asymptotisch ~6,6-fach OHNE aeussere Bremsen. Die realen Bremsen:
  BG-Antwort (fehlt im offenen Rig - erklaert das 26x0,05-Muster!),
  Tail, iobTH. Der subStep-Carry selbst ist harmlos: prozesslokal,
  max. 1 Pumpenschritt, in 6 von 8 Lagen sicher verworfen (lebt weiter
  ueber Observer-TURN und r-Schwellenverlust - je max. 0,05 U;
  UNVERIFIZIERT im Detail).
- **Brief D.1 JA:** negatives Basal-IOB FINANZIERT SMB-Korrekturen -
  Budgetseite ist dicht (capIob = max(netto, bolus) an allen sechs
  Kappenstellen), aber die BEDARFSSEITE rechnet mit netto: negatives
  Basal-IOB hebt die Landung, vergroessert insulinReq und entschaerft
  Guard + Schwanz. (Teil-UNVERIFIZIERT durch API-Ausfall, Code-Belege
  aus zwei unabhaengigen Agenten deckungsgleich.)
- **Brief D.4 JA (datenseitig):** Morgen-Nachholmuster nach
  Nacht-Totband-Ende an allen 4 Tagen (1,70/0,65/0,85/0,80 U in
  0,05-Subschritten, Start-IOB -0,23..-0,41, landungsgetrieben). Das
  Totband speichert nichts; oberhalb der Kante wird der volle Abstand
  zum PROFILZIEL nachdosiert - Sprung an der Kante, keine Hysterese.
- Messbare Wiederholung derselben statischen Lage: 15.08. 08:09-08:18,
  9x0,05 U bei durchgehend negativem rSigned UND fastDrive (ukf
  positiv).
- Zero-Temp UEBERDAUERT ihren Grund (endet per 30-min-Ablauf oder
  Stale-Zero-Regel; das C7a-Zertifikat unterdrueckt den Abbruch in
  SMB-Zyklen). Basalwiederherstellung ist heute rein passiv.

## 4. FUSE vs aisf im Korrekturkontext (T3; nur Struktur/Timing)

4 ueberlappende Fenster 12.-14.08., KEINES voellig rein (Kontaminationen
je Fenster im Rohbefund). Startseite: FUSE dosiert konsistent an der
130er-Schwelle (-3..+5 min), aisf 9-85 min spaeter - in 3 von 4
Fenstern belegt GATE-bedingt (TT-Paritaet, BG-gestufter iobTH: die
Latenz misst die Bridge-Konfiguration, nicht die Bedarfserkennung;
fuer F1 nicht beweisbar - Verifikation hat die Allaussage
abgeschwaecht). Form: FUSE minuetlich klein und lang (8-26
Abgaben/h, med 0,05-0,15 U); aisf frontloaded 0,4-0,8-U-Boli in 3-7
min bis zum iobTH-Deckel. Stopseite: die belegbaren aisf-Stopps waren
iobTH-Deckel, kein Trend-Entscheid; FUSE-Abgaben nach Peak ausnahmslos
bei positivem r UND ukf. Keine Summen-/Guete-Aussage (offene Schleife).

## 5. Bewertung positive TBR (Brief E, je Rolle)

Befund heute: positiv existiert NUR der SMB; alle drei Intents und der
Abbruchpfad CANCELN laufende positive TBRs aktiv; der Ledger kennt
keine TBR-Commitments; CANCEL_TO_SCHEDULED wird nie erzeugt.
Andockpunkte fuer einen gemeinsamen Haushalt existieren
(transportModelledU-Muster fuer Headrooms, Kappenliste, Intent-Achse).

1. **Basalwiederherstellung: braucht KEINE positive TBR.** Die
   minimal-invasive Form ist das fruehe Beenden der Zero-Temp
   (Cancel auf Profilbasal), sobald ihr Grund weg ist - heute
   ueberdauert sie ihn passiv. Kleinster mergefaehiger Schritt des
   ganzen Briefs.
2. **Unsichere Korrektur: begrenzter Nutzen.** Die gemessene
   Rueckhaltekapazitaet (~0,5 U/h am echten Profil, Memo 09.08.)
   gilt spiegelbildlich: eine positive TBR von +100 % ueber Tonis
   0,45 U/h liefert nur ~0,225 U in 30 min - als reversibler Kanal
   REAL, aber langsam; der Kanalvorteil ist Granularitaet, nicht
   Reversibilitaet. Kosten: Medtrum-TBR-Pfad, Ledger-Erweiterung
   (TBR-Commitment), gemeinsames Budget in allen Kappen + Tail.
3. **Sub-Step-Rest: lohnt nicht** (max. 0,05 U je Ereignis).
4. **Bestaetigter Bedarf: SMB bleibt** (unstrittig).

Empfehlung fuer Phase 3 (Replay): V4 (Basalschuld nicht per SMB,
d.h. Bedarfsseite bei negativem Basal-IOB in Zielnaehe daempfen +
Zero-Temp-Fruehende) und V2 (abgestufte schnelle Bremse ist
WEITGEHEND GEBAUT - pruefen, ob der Uebergabe-Dip sie ergaenzt)
zuerst; V3 (positive TBR) nur fuer Rolle 2 und nur, wenn V4 die
Faelle nicht schon abraeumt; V1 (hartes Fast-Turn-Veto) ist nach der
Turn-Latenz-Messung UNNOETIG (Stopp ist heute schon schnell).

## 6. Verdikt je Kontext (Brief 11.1)

| Kontext | Verdikt |
|---|---|
| Korrektur bei echtem Antrieb (86 % der Menge) | gerechtfertigt |
| Strikt statische Lage (3,45 U/4 Tage) | Form zu unruhig (Ratio-Integrator + Tropffrequenz); Menge klein; im geschlossenen Kreis bremst die BG-Antwort - final NICHT ENTSCHEIDBAR auf offenen Daten |
| Turn-Reaktion | gerechtfertigt: Stopp median +4 min, kein Nachlauf ausserhalb Wieder-Anstiegen |
| Onset ohne Marker | zu zaghaft: median 40 min bis zur ersten Abgabe, ueberwiegend Politik (Ziel/Totband) + Uebergabe-Dip |
| Rueckkehr aus Totband | Nachholen ist by design zustandsgetragen; Kante ohne Hysterese + Basalschuld-via-SMB sind echte Designfragen (D.1/D.4 bejaht) |
| Hoch, fallend, Landung ueber Ziel | Bremse wirkt (0,25 U/4 Tage); ob ZU stark, auf offenen Daten NICHT MESSBAR |

## 7. Stufenplan (GO/NO-GO je Stufe)

1. **Telemetrie (GO):** R_CONFIRMED-Uebergaenge + Uebergabe-Dip-Groesse
   exportieren/beziffern; Onset-Politik-Anteil (Ziel- vs
   Totband-Minuten) je Onset ausweisen. Kein Regler-Eingriff.
2. **Counterfactual Replay (GO, nach dem Mahlzeitenblock):** V4, V2,
   dann V3-Rolle-2 gegen identische Eingaben mit gemeinsamer
   Insulinhistorie; Brief-Gegenproben 1-14 als Pflichtmatrix.
3. **VirtualPump-Umsetzung (NO-GO bis Replay-Ergebnis).**
4. **Produktivnachweis (NO-GO; gesondertes GO laut Brief).**

## Offene Punkte / Einschraenkungen

- 3 Verifikationen durch transiente API-Fehler entfallen (subStep-
  Acht-Lagen-Detail, D.1-Budget-Detail, Zu-stark-Kandidaten) - Befunde
  je von zwei unabhaengigen Lesern deckungsgleich, aber ohne dritten
  Widerlegungsversuch.
- Alle Mengen unter dem Zwei-Geraete-Vorbehalt (Brief 3.3); 13
  Buildstaende im Trail; UKF-Latenz bis 0,5 nicht statisch ableitbar
  (adaptives R).
- aisf-Gate-Freigaben (TT-Wechsel) nicht bis zur Quelle verfolgt;
  aisf-Ruhemetrik asymmetrisch (nur SMB-Zeitpunkte).
- Rohdaten und Auswerteskripte lokal im Sitzungs-Scratchpad
  (Gesundheitsdaten, nicht im Repo).

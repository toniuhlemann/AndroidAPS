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

**Praezise (Toni-Korrektur 15.08.): "r bremst am Onset" waere zu
pauschal.** Richtig ist: der autonome ONSET-PFAD ist langsam, r traegt
dazu bei, aber rund 19 der 40 Minuten stammen aus Ziel-/Totbandpolitik.
Am TURN kompensiert die schnelle Spur die Traegheit von r vollstaendig.
Und FUSE hat primaer **kein Turn-Problem, sondern ein autonomes
Onset- und Basalschuld-Problem**.

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
2. ~~NEU - der UEBERGABE-DIP~~ **WIDERLEGT durch Messung, s. Abschnitt
   1a.** Die statische Code-Lesart (Kanalhebung faellt weg, Antrieb
   faellt auf r) trifft in den Daten nicht zu.

### 1a. Telemetrie-Stufe: der Uebergabe-Dip existiert nicht (Messung 15.08.)

Der Trail exportiert alle noetigen Groessen bereits (`drive.onset`,
`drive.mean`, `state.smbRatioEffective`, `decision.insulinReqU`,
`rt.units`) - die Telemetrie-Stufe brauchte KEINEN Code. Gemessen an
**18 lueckenfreien R_CONFIRMED-Uebergaengen** (je 5 Minuten davor/danach,
30 Uebergaenge gesamt, davon 18 ohne Datenluecke):

| Groesse | 5 min VOR der Uebergabe | 5 min DANACH |
|---|---|---|
| Antrieb `drive.mean` | -0,32 .. +0,58 | **+0,54 .. +1,91** (steigt IMMER) |
| Ratio | 0,15-0,18 | 0,15-0,31 (steigt meist) |
| Bedarf `insulinReq` | Summe niedriger | hoeher in 14/18 |
| **publizierte Menge** | **1,20 U** | **4,30 U** |

**Delta +3,10 U, also +0,172 U je Uebergang - die Uebergabe ist
nahtlos bis ueberschiessend, nicht eingebrochen.** In 16 von 18 Faellen
ist das Delta >= 0; die zwei negativen Faelle (-0,25 / -0,10 U) sind
durch FALLENDEN Bedarf erklaert (req 0,28->0,02 bzw. 0,15->0,06), nicht
durch einen Antriebseinbruch.

Die Ursache des Fehlschlusses ist lehrreich: der Kanal schlaeft
DEFINITIONSGEMAESS erst, wenn r ihn bestaetigt hat - und r liegt zu
diesem Zeitpunkt bereits deutlich ueber dem, was die Kanalhebung
lieferte (Median-Antrieb danach ~1,0 statt der befuerchteten ~0,5).
Ein statisch plausibler Code-Pfad, den die Daten nicht hergeben.

**Folge fuer den Stufenplan: V2 ("Reparatur des Uebergabe-Dips")
entfaellt.** Der verbleibende Onset-Hebel ist NICHT der Schaetzer und
NICHT die Uebergabe, sondern die Ziel-/Totbandpolitik (~19 der 40
Minuten) - eine eigene, bewusst gesetzte Politik, kein Defekt.

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

Reihenfolge nach Tonis Vorgabe 15.08. (Ein-Variablen-Disziplin -
V4 wird AUFGETEILT, weil es zwei verschiedene Eingriffe enthaelt):

- **V4a**: negatives Basal-IOB erzeugt in Zielnaehe keinen
  zusaetzlichen SMB-Bedarf.
- **V4b**: Zero-Temp endet, sobald ihr Schutzgrund verschwunden ist
  (Rueckfall auf Profilbasal, KEINE positive TBR).
- Erst einzeln, danach kombiniert.
- **V2 entfaellt** (Uebergabe-Dip widerlegt, s. 1a).
- **V3 (positive TBR) nur bedingt**: falls V4b nicht genuegt, und nur
  fuer langsame/unsichere Korrekturen - nicht fuer die blosse
  Basalwiederherstellung.
- **V1 (hartes Fast-Turn-Veto) unnoetig** (Stopp median +4 min).

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

1. **Telemetrie: ERLEDIGT 15.08.** - ohne Code, aus dem vorhandenen
   Export gemessen; Ergebnis: Uebergabe-Dip widerlegt (1a).
2. **Counterfactual Replay (GO, gelaufen 15.08.):** V4a und V4b
   getrennt in isolierten Worktrees, jede Variante als Schalter mit
   Default = heutiges Verhalten, Brief-Gegenproben 1-7 als
   Pflichtmatrix. Ergebnis s. Abschnitt 8.
3. **VirtualPump-Umsetzung (NO-GO bis Toni entscheidet).** Der Replay
   liefert Erkenntnis, kein Bau-GO - der Produktivstand bleibt heute
   das ungeaenderte Verhalten.
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
## 8. Replay-Ergebnisse V4a und V4b (15.08., getrennte Worktrees)

Beide Varianten als schaltbarer Kern-Parameter mit **Default = heutiges
Verhalten** gebaut; Bitgleichheit je Variante bewiesen (V4a: zeichengenauer
Kennzahlenvergleich ueber 8 Lagen gegen einen VOR der Aenderung
aufgenommenen Abzug; V4b: SHA-256 ueber die vollstaendige
1872-Zellen-Entscheidungsmatrix, auf HEAD e393bdc110 abgenommen).
**Die Varianten liegen NICHT im Hauptbaum** - sie bleiben in den
Worktrees, der Produktivstand ist der ungeaenderte Regler.

### V4a - negatives Basal-IOB erzeugt in Zielnaehe keinen SMB-Bedarf

| Lage | ohne V4a | mit V4a |
|---|---|---|
| **S3 Zielnaehe nach Zero-Temp (Kernfall)** | 41 Dosen / 3,15 U | **28 / 1,70 U (-46 %)** |
| **S4 Nacht-Totband endet (Morgenfall)** | 31 / 2,45 U | **25 / 1,65 U (-33 %)** |
| S1 hoher BG fallend | 7,50 U | 7,50 U (0) |
| S5 Rebound | 0 U | 0 U (0) |
| S6 Mahlzeit mit Marker | 12,30 U | 12,30 U (0) |
| S7 Korrektur r>1 | 11,85 U | 11,85 U (0) |

Kein Szenario wird groesser - die Einseitigkeit haelt ueber die volle
Kette. Die Zone endet von selbst, sobald der Anker sie verlaesst.

**AUFLAGE (echter Befund, kein Detail):** in S2 (Zielnaehe fallend,
hohes Bolus-IOB, laufende Zero-TBR) verschwinden **18 von 19
Abbruch-Anforderungen fuer die laufende Null**. Die Daempfung frisst
den kleinen Restbedarf auf, der Zyklus faellt von
`BELOW_PUMP_INCREMENT` (-> KEEP -> `KEEP_CANCEL_STALE_ZERO`) auf
`NO_DEMAND` (-> NO_POSITIVE -> Null bleibt stehen). FUSE verliert
ausgerechnet in der Lage, die V4a adressiert, sein einziges Mittel,
die Zero-TBR zu beenden: **man tauscht SMB-Nachholen gegen
Basal-Aushungern.** Grenze exakt `insulinReq > 0` gegen `<= 0`.
Auflage bei Weiterverfolgung: Abzug strikt UNTER insulinReq deckeln.

### V4b - Zero-Temp endet, sobald der Schutzgrund weg ist

Engste Stelle: der nicht-positive Zweig von `noPositive`, mit drei
Und-Bedingungen (Schalter, Nachweis der bestandenen Guard-Kette im
aktuellen Zyklus, echte Null nach `isZeroRate`). Einziger neuer
Ausgang ist `Request(0,0)` - **nie eine positive Rate**. C7a bleibt
gewahrt (Abbruch im Dosier-Zyklus weiterhin unterdrueckt, im Test
vorgefuehrt). Menge, Abgabenzahl und bindende Kappe sind in ALLEN
8 Lagen bitgleich; geaendert wird ausschliesslich die TBR-Achse.

| Lage | ohne V4b | mit V4b |
|---|---|---|
| **S3 Kernfall** | Null lebt 30 min | **Null endet nach 5 min = +0,42 U Basal** |
| **S4 Morgenfall** | Null lebt 30 min | **endet nach 5 min = +0,42 U Basal** |
| S1/S2/S6/S7 | - | kein Unterschied |

**DREI AUFLAGEN:**
1. **Rebound (S5): V4b feuert dort** - im ersten Zyklus nach dem
   SAFETY_HOLD, waehrend das Rebound-Totband noch jede Menge blockt.
   Der Guard ist formal frei, inhaltlich nicht (aufgeblaehtes r ist
   genau der Grund fuer das Fenster). Der Nachweis muesste um eine
   Bedingung erweitert werden.
2. **Scheiterndes Pumpenkommando: 26 Cancel-Kommandos in 30 Zyklen**
   (kein Backoff, kein Deckel, kein Alarm) - auf dem Medtrum Nano
   neuer Funkverkehr genau dann, wenn die Pumpe ohnehin zickt.
3. **Fremde echte Nullen** werden ebenso abgebrochen. Das vergroessert
   das bestehende Fenster von `KEEP_CANCEL_STALE_ZERO` auf alle
   NO_DEMAND-/iobTH-/maxIOB-Zyklen und beruehrt damit die Grundregel
   "manuell Gesetztes ist unantastbar". (Fremde ABSENKUNGEN bleiben
   unangetastet - `isZeroRate` prueft hart auf 0.)

### Der wichtigste Befund aus der Trennung

**V4as Nebenwirkung ist genau der Mechanismus, den V4b repariert.**
V4a nimmt FUSE die Faehigkeit, die Zero-TBR zu beenden (weil der
Restbedarf wegfaellt); V4b gibt sie unabhaengig vom Restbedarf zurueck.
Das ist eine **Hypothese, keine Messung** - der kombinierte Lauf steht
aus und ist jetzt der naechste sinnvolle Schritt (Tonis "danach
kombiniert"). Haette man V4 als EINEN Eingriff gemessen, waere weder
die Nebenwirkung noch ihre mutmassliche Heilung sichtbar geworden.

**Kein Bau-GO.** Beide Schalter sind Messwerkzeuge: sie stehen in
keinem Einstellungsbildschirm, in keinem Export und in keinem
Settings-Bericht - was nicht gemessen ist, gehoert nicht auf ein
Geraet mit echter Pumpe.

### Offen nach dem Replay

- **Kombinierter Lauf V4a+V4b** (Hypothese oben pruefen).
- V4a: Deckelung des Abzugs unter insulinReq.
- V4b: Rebound-Ausschluss, Wiederholungspolitik bei Cancel-Fehlern,
  Umgang mit fremden Nullen, Grund-Token im Viewer.
- **Lage 1 bleibt von BEIDEN ungeloest**: dort ueberlebt die Null ihren
  Grund die vollen 30 min, waehrend 7,80 U dosiert werden - das ist
  C7a-Gebiet (Abbruch im Dosierzyklus unterdrueckt), nicht V4a/V4b.

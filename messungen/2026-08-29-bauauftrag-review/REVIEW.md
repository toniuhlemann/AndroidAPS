# Review: Bauauftrag "zentrale CORRECTION-/MEAL-Dosierpolitik"

Datum: 29.08.2026. Basis: HEAD `851f4b9e11` (live auf kodiak seit 28.08. 18:15),
Bauauftrag vom 28.08. (`fuse-bauauftrag-zentrale-dosierprofile.md`), Trails der
beiden Anlassfaelle (27.08. 21:54 Korrektur-Burst, 28.08. 18:37 Mahlzeit),
13-Agenten-Audit mit adversarieller Gegenpruefung der tragenden Befunde
(8/8 Verifikationen gelaufen: 7x CONFIRMED, 1x PARTIALLY_CONFIRMED).

## Gesamturteil

**Zustimmung zum Bauauftrag.** Beide Anlassfaelle sind im Trail verifiziert, die
Architektur-Diagnose (kein gemeinsamer kontextabhaengiger Mengenrahmen ueber den
Quellen) ist code- und datenbelegt, die Zielarchitektur ist mit den bestehenden
Vertraegen vereinbar. VOR dem Bau braucht es aber (a) vier P0-Praezisierungen an
der Spezifikation, (b) drei Faktenkorrekturen an der Motivationslage und (c) eine
ausdrueckliche Erwartungs-Klarstellung: der Auftrag fixt Fall 1 direkt, Fall 2
nur teilweise.

---

## 1. Fall 1 (27.08. 21:54-22:05) — VERIFIZIERT, mit zwei wichtigen Zusaetzen

Zahlen exakt bestaetigt: 2,50 U in 12 aufeinanderfolgenden Zyklen im Normalpfad,
q1 136,5 -> 189,2, Bolus-IOB 1,76 -> 3,93 U, Liveness nie aktiv (NOT_CONFIRMED,
dann NORMAL_PATH_OPEN), Beispielzyklus 22:03 punktgenau (q1 179,9, SMB 0,35,
binding=tailHeadroom). In allen 12 Zyklen war **tailHeadroom das einzige aktive
Limit** — iobTH-/maxIOB-Headroom lagen bei 4,07-6,24 U, maxSmb und smbRatio
banden nie. Der Mechanismus "steigender Forecast oeffnet den Tail" ist als
Gleichung belegt: `budgetU = (lowerBgAtH - 70)/108` (TailLiability.kt:374);
lowerBgAtH stieg 101,6 -> 174,0, das Budget lief der Haftung jeden Zyklus davon.
Nachlauf: Gipfel 192, Zero-TBR 51 min, Tief 75,4 — das Insulin war grob ~0,5 U
zuviel, knapp ohne Hypo.

**Zusatz 1 — die Ratio-Caps haetten NICHTS verhindert (P0 fuer die
Parameterwahl):** Die effektiven Ratios der 12 Burst-Zyklen lagen bei
0,035-0,176 — alle UNTER dem heutigen K-Cap 0,20. Ein CorrectionDemandRatioCap
0,20 haette 0,00 U gespart, 0,15 nur ~0,09 U. Der Schaden entstand ueber die
SUMME der Zyklen, nicht ueber die Einzelmenge. **Der wirksame Hebel ist
ausschliesslich das CorrectionExposureLimit** (rueckkopplungsblinde Obergrenzen,
gegen capIob): 2,5 U stoppt ab 22:00 (-1,55 U), 3,0 U ab 22:02 (-0,90 U). Der
Bauauftrag baut beide Hebel — richtig so —, aber die Wirkungserwartung gehoert
an den Exposure-Hebel.

**Zusatz 2 — der Burst lief NICHT mit Korrektur-Ratio:** Ab 21:56 oeffnete ein
rein kinematisches Mahlzeitfenster (mealBasis=KINEMATIC_ONLY) und hob
smbRatioEffective von 0,15 auf 0,35 (RISE-Rampe). Die Autorisierungslage war
dabei leer (Marker-Power seit 20:35:58 abgelaufen, alle Grants/Lifts 0).
KONSEQUENZ: Die Profilzuordnung MUSS an der gepinnten Marker-Power haengen
(wie liveness.profile es heute korrekt tut: CORRECTION/POWER_EXPIRED), NIE an
der kinematischen Kontextklassifikation — sonst rutscht genau dieser Anlassfall
ins MEAL-Profil und entkommt den Correction-Grenzen erneut. §4.1 sagt das
bereits ("kinematisch geoeffnetes Fenster allein nicht ausreichend") — jetzt
ist es datenbelegt. ACHTUNG: `ExpectationLedger.classify` stuft das kinematische
Fenster als MEAL ein (ExpectationLedger.kt:369) und ist damit als Quelle der
Profilwahl UNGEEIGNET.

## 2. Fall 2 (28.08. Marker 18:37) — Zahlen verifiziert, Mechanik-These KORRIGIERT

Zahlen bestaetigt: Marker 18:37:16, 4,0 U Upfront sofort (Stabilitaetsnachweis
STABLE, 12 Bestaetigungszyklen — der 28.08.-Bau hat geliefert), erste
Meal-Liveness 19:55:27 (T+78, q1 175,9), Peak 217,9 um 20:45. Mengenbilanz:
T+0..T+78 = 5,80 U (4,0 Upfront + 0,95 Foundation-Drip + 0,85 Normalpfad),
T+78..Peak = 3,70 U (alles Liveness), kein manueller Bolus.

**Die Foundation-Verdeckungs-These ist fuer diesen Fall WIDERLEGT** (adversariell
gegengeprueft, CONFIRMED): Die 19 Foundation-Lift-Zyklen setzten zwar den
exportierten Block GUARD_FLOOR->NONE, aber in ALLEN 19 war der Liveness-Streak 0,
weil q1 unter der 140er-Druckschwelle lag. 0 gerissene Streaks, 0 verlorene
Bewaffnungszyklen; die Bewaffnung waere ohne den Effekt exakt gleich um 19:55:27
gekommen. Der Mechanismus existiert im Code (Lift laeuft vor dem Tor, Runner:3343
vs :4450) und bleibt als Randfall real — er war nur nicht die Ursache.

**Die tatsaechliche Ursachenkette der 78 Minuten:**
1. **55 min Druckdefinition** (18:44-19:39): r lag ab 18:44 ueber 1,0 bei
   GUARD-gedeckeltem Normalpfad, aber q1 ueberschritt 140 erst 19:39 — die
   Korrektur-BG-Schwelle verhinderte unter stehender MEAL-Autorisierung jede
   Druckzaehlung, waehrend nur 0,025 U/min Foundation floss.
2. **~14 min Bewaffnungslogik**: 3 Zyklen echtes NORMAL_PATH_OPEN (Normalpfad
   lieferte real 0,85 U) + 11 Zyklen TURN_STANDING — eine DRIVE-Wende
   (slowDrive 7,25->3,0) bei durchgehend STEIGENDEM q1 168->176.
3. Danach: TURN_EXIT 20:06 bei q1 202 steigend beendete Lauf 1 und riss mit
   REARM/TURN/FALLING eine 23-min-Luecke (davon ~12-13 min der Wende-/
   Rearm-Logik zuzurechnen); ab 20:04 bzw. 20:36 band der M-IOB-Deckel
   (NO_HEADROOM), und der Power-Ablauf 20:37 schaltete MITTEN im Anstieg auf
   K-Caps (needU 1,0-1,7 unbedient bis zum Peak).

Die Bauauftrags-Diagnose (d) — drei getrennte Toepfe ohne gemeinsamen
MEAL-Dosierraum — wird von den Daten gestuetzt. ABER die Erwartung muss ehrlich
sein, s. Punkt 4.

## 3. Code-Ist-Stand: was der Bauauftrag richtig sagt, und wo er korrigiert werden muss

**Bestaetigt (adversariell verifiziert):**
- Die v24-Profil-Caps binden AUSSCHLIESSLICH den Liveness-Kanal (einzige
  dosierwirksame Lesestellen Runner:4209/4490/4537); der Normalpfad kennt keinen
  der vier Werte. Motivations-Kernbehauptung korrekt.
- MEAL_UPFRONT ist die einzige Quelle ohne maxSmb-Zerteilung; Liveness die
  einzige ohne Guard-/Tail-Veto/finalVeto (technische Modellintegritaet bleibt
  Pflicht); markerautorisierter Anteil ueberlebt die SAFETY_ZERO-Klammer.
- Die Endpruefung passt in die heutige Reihenfolge: letzte hebende Stufe ist der
  Liveness-Merge (:4568), danach nur Reduzierer.

**Korrekturen am Bauauftrag (Faktenlage):**
1. **§1 "Max-statt-Addition erhalten" ist unvollstaendig**: es existieren ZWEI
   echte Additionspfade — SubStep-Carry (:3508) und DeferredPrime-Release
   (:4099), beide +1 Pumpenschritt. Beide muessen unter die Endpruefung.
2. **Der Fallback-Pfad braucht eine ZWEITE Einbaustelle** (Verifikation
   PARTIALLY_CONFIRMED mit Korrektur): `markerFallbackCycle` (:5369-5662) hebt
   via PrimeRelease/MealFoundation mit hartem markerAuthorized=true, hat einen
   EIGENEN Translator-Aufruf (:5615) und eigene Reservierung (:5654) und
   passiert die Hauptpfad-Einbaustelle NIE. Eine einzelne Endpruefung im
   Hauptpfad genuegt nicht.
3. **§4 muss DREI gepinnte Marker-Identitaeten konsolidieren**:
   markerPowerPinnedFor/DeadlineTs (Liveness), foundation.armedTs +
   pinnedMarkerAuthorized (Prime/Foundation/Upfront), deferredPrime.
   pinnedForMarkerTs. Heute lebt die Profilwahl NUR im Liveness-Block und NUR
   bei eingeschaltetem Kanal (:4194-4204) — das verletzt woertlich die eigene
   Forderung "Kontextwahl darf nicht davon abhaengen, ob Liveness eingeschaltet
   ist". Die Pin-Mechanik ist der richtige Kern, muss aber herausgezogen werden.
   markerPower traegt zudem eine ZWEITE Wirkung (Befreiung vom Reversal-/
   Rearm-Riegel, :4326), die beim Herausziehen mitmuss.
4. **§5-Formel ist heute NICHT einheitlich**: die Basis-IOB-Tore des Reglers
   (FuseController.kt:991-992) rechnen OHNE Transporthaftung; die kommt erst in
   CandidateSearch/AuthorizedLift/Liveness/SubStep dazu. Die Vereinheitlichung
   ist eine VERHALTENSAENDERUNG (verschaerft den Basispfad) und muss als solche
   attribuiert werden, nicht als Refactoring.
5. **§13-Dateiliste unvollstaendig** (12+ fehlende dosierrelevante Dateien, u.a.
   FuseTbrTranslator, TbrPolicy, PrimeRelease, UpfrontRecovery, MarkerFallback,
   SubStepAccumulator, MeasuredDescentGate, FusePlugin-Publikationsgate).
   Ausserdem: `MealFoundation.contribute` ist toter Code (reale Max-Semantik
   liegt in AuthorizedLift), `LivenessChannel.finalU` hat keinen
   Produktionsaufrufer (max sitzt inline im Runner) — nicht dagegen bauen.
6. **Invariante 4 ist heute tatsaechlich verletzt** — staerkster Nutzen-Beleg
   der Endpruefung: `DeferredPrime.releaseStep` prueft weder iobTH- noch
   maxIOB- noch Transport-Headroom (DeferredPrime.kt:152-196; nur finalVeto
   auf die Summe, :4093). Der SubStep prueft seine Endsumme dagegen korrekt
   (otherCaps, :3466).

## 4. Was der Bauauftrag NICHT fixt (Erwartungs-Klarstellung, Tonis Entscheid)

Fuer Fall 2 liegen die Haupthebel AUSSERHALB des spezifizierten Umfangs:

- **Die 55-min-Druckschwelle**: Ein MEAL-Profil mit offeneren Exposure-/
  Ratio-Caps aendert NICHTS an der Bewaffnungsbedingung q1>140. Unter stehender
  Marker-Autorisierung bliebe die Nachsteuerung im Kernfenster T+7..T+60
  weiterhin aus. Der naheliegende Fix — die Liveness-BG-Schwelle wird
  profilabhaengig (MEAL: niedriger/zielrelativ; CORRECTION: 140/160 wie heute) —
  ist eine PARAMETER-Erweiterung, keine Episodenmechanik, und passt in die
  Bauform des Auftrags. Er steht aber nicht drin. Entscheidung noetig:
  in diesen Auftrag aufnehmen oder als benannten Folgeauftrag fuehren.
- **Die Wende-Pause** (TURN_STANDING/TURN_EXIT bei steigendem BG): explizit
  ausgeklammert (§2, Liveness-Episodenmechanik); der separate
  TURN_APPROACHING-Vertrag bleibt der Weg dafuer. Nur wissen, dass sie bleibt.
- **Der Cap-Bruch am Power-Ablauf** (20:37 mitten im Anstieg): DEN fixt der
  Auftrag strukturell auch nicht — die Frist bleibt hart. Immerhin macht die
  zentrale Kontextentscheidung den Uebergang sichtbar und einheitlich.

Fall 1 dagegen wird direkt gefixt (CorrectionExposureLimit am Normalpfad).

## 5. P0-Praezisierungen an der Spezifikation (vor Baubeginn festzuzurren)

1. **Position + Unumkehrbarkeit der Endpruefung**: "Die verbindliche Endpruefung
   ist die LETZTE hebende Mengenstufe des Zyklus — nach MarkerFloor, CALM_BATCH,
   calmDemand, DeferredPrime-Release, SubStep und Liveness-Merge; MarkerFloor
   laeuft nach ihr NIE erneut; danach nur noch reduzierende Stufen (LedgerHold
   ist bereits davor, Translator, Pumpen-/Publikationsgate). Einbau an ZWEI
   Stellen: Hauptpfad (nach :4581, vor combine :4757) und Fallback-Pfad (nach
   :5606, vor :5615)."
2. **Klassifizierung als absolute Mengengrenze**: contextExposureLimit gehoert
   ausdruecklich in die Marker-Vertrags-Klasse maxSmb/iobTH/maxIOB/Huellenrest/
   Pumpenschritt (nie hebbar; MarkerAuthorization.lifts=false — das
   when-ohne-else erzwingt die Einordnung nur, wenn die Grenze als Block-Wert
   gefuehrt wird). MarkerFloor-Doku ("nie darunter") um "ausser absolute
   Mengengrenzen" ergaenzen. EMPFEHLUNG zusaetzlich: Exposure-Headroom in die
   AuthorizedLift-Kappenmenge aufnehmen, damit ein Grant nie groesser als der
   Raum entsteht — dann kann MarkerFloor konstruktiv nichts wiederherstellen,
   was die Endgrenze reisst (der CALM-Batch-Praezedenzfall zeigt, dass
   grant-gebundene Ausnahmen spaete Tore durchstossen koennen).
3. **Endpruefung = reine MENGENpruefung**: kein erneuter Guard/Tail/finalVeto-
   Lauf auf der gemergten Menge — sonst wird der Liveness-Vertrag (bewusst kein
   finalVeto im Kanal, :4135-4142) gebrochen und der Saegezahn reproduziert.
4. **Gekappter autorisierter Rest: verschieben, nicht verwerfen**: Kappt die
   Endgrenze eine Direktdosis, bleibt der Rest in Huelle/Upfront-Bilanz OFFEN
   (konstruktiv gegeben, weil buche() auf actuatedU laeuft), der Batch-Zustand
   darf nicht terminal werden, Grant/Provenienz/bindingLimit muessen die
   gekappte Endmenge nennen. Die CALM_BATCH-Endpfadproben
   (TransportWiringTest:4491/4530, markerFloorLiftU == requestedRtU) werden
   dann KONDITIONAL — ihr Kommentar kuendigt das gewollte Brechen selbst an.
5. **Invariante 7 im TBR-Detail**: neuer Block = NO_NEW_POSITIVE,
   unsafeSituation=false (nicht in UNSAFE_BLOCKS), nicht hebbar — UND die
   bewusste Entscheidung, ob er in GUARD_CHAIN_PASSED aufgenommen wird
   (IOB_TH_REACHED/MAX_IOB_REACHED beenden heute via reasonGone eine stehende
   FUSE-Null; fehlt der neue Block dort, haelt ein erschoepfter Exposure-Raum
   Nullen LAENGER als heute — stille Verhaltensaenderung in der Gegenrichtung).
   Empfehlung: aufnehmen, dokumentieren.
6. **Kontexttraeger festlegen**: Die MEAL-Kontextentscheidung traegt
   AUSSCHLIESSLICH die gepinnte Markerfrist (markerPowerPinnedFor/DeadlineTs);
   die uebrigen Fenster (mealMarkerActive 90, Foundation-/Phase-A-Fenster,
   DeferredPrime-Frist) bleiben quellen-interne Budget-/Zeitgrenzen und
   definieren den Kontext NICHT. Zwischen Marker+90 und +120 sehen heute
   verschiedene Quellen verschiedene Lagen — genau das raeumt die Festlegung auf.

Weitere P1-Klaerungen (Doppelzaehlungs-Richtungsausnahme des Inclusion-Vertrags;
Unkonfiguriert-Semantik der Relation "K nie offener als M" inkl. Fall "nur MEAL
gesetzt"; R-Rampe des Normalpfads als Bedarfsregel deklarieren, kappbar durch
den K-Cap, bitidentisch bei unkonfigurierten Caps; Invariante 6 mit
Enumeration der orthogonalen Zustaende; eigenes Exportfeld fuer die finale
Endanforderung — requestedRtU ist ein Stufen-Snapshot VOR Liveness/Aufschub)
stehen mit Belegen im Audit-Detail. UEBERHOLT (Toni 29.08. mittags): der
hier urspruenglich vorgeschlagene min(alt, neu)-Uebergang ist VERWORFEN -
stattdessen harter Modusschalter `policyMode = LEGACY | CENTRAL_PROFILES`,
keine versteckten Altgrenzen im zentralen Modus, UI zeigt nur die neue
Struktur, Abbauplan definiert (Bauauftrag 7.5.7).

## 6. Tests, Infrastruktur, Schnitt

- Pflichtfaelle: 7/13 tragfaehig (teil-)abgedeckt; zwingend NEUE Rigs fuer
  "Correction-Cap begrenzt Normalpfad" (heute sichert Test 9523 exakt das
  Gegenteil), "Endgrenze vs MarkerFloor" (heute invertiert gesichert) und
  Coverage-Export. Rig-Fallen verifiziert; Korrektur: unstubbtes PrimeWindowMin
  faellt auf 15 zurueck (nicht 0, nicht Geraetewert 20).
- Replay: FUSE_REPLAY_PROFILE ist woertlich als §10-Abnahme gebaut; noetig ist
  nur ein FUSE_REPLAY_DOSING_CONTEXT-Zweig + CSV-Spalten (dosingContext,
  Exposure-Grenze/belegt/Headroom, Cap, Quellenlabel). Hebel-Leck-Reset je Lauf
  beachten (2,000-U-Artefakt vom 24.08.).
- Umbau-Checkliste: 12 Beruehrpunkte (Keys/Config/readConfig/validate/
  policyValues/hashOf/RULE_SET_VERSION v36+Journal/SettingsReport-Dreifach-
  Waechter/Backup/Export/Replay/Ledger-Persistenz). Config-Datenklasse ohne
  Defaults -> ConfigBoundsTest.mitte() + FuseStateExportTest.cfg brechen
  kompilierend (gewollt sichtbar).
- Schnitt-Empfehlung: Schritt A in 5 Commits (A1 DosingContext pur; A2
  Exposure-Zentralisierung als dosierneutrales Refactoring mit bitgleichem
  w10ref-Replay — KRITISCHER PFAD; A3 Export; A4 vier Keys + v36; A5
  OFF-Neutralitaetsproben), Schritt B in 3 Commits (B1 Endgrenze ueber allen
  Quellen inkl. Fallback-Zweitstelle + Mutationsprobe "Endgrenze entfernt ->
  rot"; B2 effectiveDemandRatio NORMAL+LIVENESS mit dokumentierter
  Basisdifferenz; B3 Replay-Zweig + Attribution am 27.08.-Fall + Kontrolltagen).
- In Schritt A muessen ALLE heutigen Proben gruen bleiben (unkonfigurierte Caps
  inaktiv = Neutralitaetsnachweis); in Schritt B brechen die benannten
  Endpfad-/Kanalproben GEWOLLT.

## 6b. NACHTRAG 29.08.: Die Block-Verdeckung ist DOCH live aufgetreten — am Fruehstueck 28.08.

Tonis Einwand nach dem Review, am Trail verifiziert (Fruehstuecks-Marker
09:21:56, also derselbe Tag wie Fall 2, aber ausserhalb des untersuchten
Abendfensters 17:00-22:00):

    09:48:28  q1 142,2  r +3,60  fndLift 0,05  block NONE   streak 1  NOT_CONFIRMED
    09:49:27  q1 145,4  r +3,77  fndLift 0     GUARD_FLOOR  streak 2  NOT_CONFIRMED
    09:50:27  q1 148,2  r +4,28  fndLift 0,05  block NONE   streak 3  NORMAL_PATH_OPEN  <-- Maskierung
    09:51:28  q1 151,0  r +4,33  Liveness AKTIV, cand 0,41, geliefert 0,25 (livenessCap)

Im 09:50-Zyklus: `mealFoundation.preFoundationBlock = GUARD_FLOOR`,
`preFoundationSmbU = 0`, `foundationLiftU = 0,05`, Binding
`markerAuth|finalVerify:GUARD_FLOOR`. Die Kette: Normalpfad 0/GUARD_FLOOR ->
Foundation-Lift +0,05 -> finalVeto nullt (GUARD_FLOOR) -> MarkerFloor stellt
den Grant wieder her -> Block NONE -> Bewaffnungstor liest NONE ->
NORMAL_PATH_OPEN bei exakt Streak 3. Bewaffnung einen Zyklus spaeter.

**Das aendert die Bewertung von Abschnitt 2 im Detail, nicht im Kern**: Die
Abendfall-Analyse (0 Wirkzyklen 18:37-20:45) bleibt korrekt; die
Gegenpruefung hatte diese Konstellation (Streak>=3 + Lift-Zyklus + keine
Wende) ausdruecklich als moeglichen Wirkfall benannt — am Fruehstueck ist sie
eingetreten. Kosten: genau 1 Zyklus (~0,25 U eine Minute spaeter bei +4,28
mg/dl/min). Begrenzt, weil NORMAL_PATH_OPEN den Streak NICHT nullt
(im Trail belegt: Streak 3 -> 4).

**Reparatur (in den Bauauftrag aufzunehmen, deckungsgleich mit dessen
Zielarchitektur "underlying block = GUARD/TAIL -> Liveness-Eignung daraus"):**
Das Bewaffnungstor liest kuenftig einen neuen, je Zyklus abgeleiteten
`underlyingNormalBlock` — den Block der Entscheidung VOR dem ersten
autorisierten Lift (vor `liftUpfront`, Runner:3297). WICHTIG:
`preFoundationBlock` ist als Tor-Quelle UNGEEIGNET — er wird nach
PrimeRelease.lift gemessen; in Phase A maskieren Prime-Schritte und der
Upfront-Batch auf demselben Weg (gleiche Familie: JEDER autorisierte Boden
plus MarkerFloor-Restauration ueberschreibt den Blockgrund). Keine
Persistenz noetig (Zyklusfakt, kein Zustand); `preFoundationBlock` bleibt
als eigene Messgroesse unveraendert bestehen.

Verhaltensaenderungs-Flaeche (zu messen, nicht zu raten): In Phase A unter
Marker konnte der Kanal bisher praktisch nie bewaffnen (v18-Rig-Notiz "unter
offenem Markerfenster nie GUARD/TAIL-gedeckelt" — durch die Lift-Maskierung
mitverursacht); mit dem underlying-Tor wird Bewaffnung dort moeglich. In der
MEAL-Profil-Welt ist das gewollt, gehoert aber in den Offline-Vergleich
(Erwartung Abendfall 28.08.: 0 geaenderte Zyklen; Fruehstueck: +1 Zyklus).
Pflichtfall dazu: Rig mit Guard-gedeckeltem Normalpfad + faelligem
Foundation-Schritt + Streak 3 -> Bewaffnung im SELBEN Zyklus; Mutation
(Tor zurueck auf Entscheidungs-Block) -> rot.

**Zur Phase-B-Frage selbst**: Die 0,05er sind als MENGENprinzip in Ordnung —
der Lift ist ein Boden (max, nie Kappung eines groesseren Normal-SMB;
code-verifiziert in AuthorizedLift). Phase B nicht auf 0 setzen und nicht
100 % Phase A waehlen, um einen Software-Seiteneffekt zu umgehen; der
Entscheidungsweg wird repariert. Die zweite Phase-B-Schwaeche bleibt die
Drive-Blindheit der festen Rate (0,025 U/min) — genau dafuer ist die
MEAL-Liveness zustaendig, sobald Tor (dieser Nachtrag) und Druckschwelle
(Entscheidung 1) sie lassen. Das Fruehstueck bestaetigt auch die
Schwellen-Diagnose in klein: r lag ab ~09:42 ueber 1, q1 ueberschritt 140
erst 09:48 — ~6 min Schwellenloch (abends waren es 55).

## 6c. NACHTRAG 29.08. mittags: Fruehstueck 29.08. — Totalausfall der Nachsteuerung, NEUER P0

Live beobachtet und am Trail verifiziert (Marker 08:57:39, 4,0 U Upfront
08:58, Phase-B-Drip 19x0,05 = 0,95 U bis 09:41, Liveness-Lifts 0,10+0,05 um
09:42/09:43 — danach NICHTS mehr; q1 stieg von 143 (09:43) auf 229+ (10:18)
bei r +4 bis +4,9; Normalpfad durchgehend GUARD_FLOOR, Schwanz negativ).

**Ursachenkette (drei Defekte im selben Fruehstueck):**

1. **NEUER P0 — Vertragskollision Ledger-Widerruf vs. Evidenz-Monotonie:**
   Um 09:44 sank `evidenceEpisode.committedU` von 5,10 auf 5,00 (exakt die
   0,10 des 09:42er-Liveness-Lifts; Ledger-Widerrufspfade
   FuseLedgerAdapter.kt:1628/:1724 drehen den Zaehler bei nicht bewiesener
   Lieferung ZURUECK — nach ihrem eigenen Vertrag korrekt: "die Buecher
   duerfen keine Bezahlung behaupten, die es nicht gab"). `EvidenceStock`
   wertet einen sinkenden kumulativen Abgabestand aber als verlorenen/
   vertauschten Zustand (EvidenceStock.kt:492, fail-closed: Phase UNKNOWN,
   Bestand 0) — ebenfalls nach eigenem Vertrag korrekt. Folge: der harte
   Liveness-Riegel `EXCLUDED_LAGE` (FuseCycleRunner.kt:4336-4338) nimmt den
   Kanal aus dem Spiel. **Und der Zustand heilt nie von selbst**: committedU
   bleibt unter der gemerkten Hochwassermarke (das Fenster ist zu, nichts
   liefert mehr nach), UNKNOWN steht bis zum 4-h-Episodendeckel. Gemessen:
   EXCLUDED_LAGE durchgehend 09:44 bis mindestens 10:12 (Ende der Daten),
   Markervollmacht dabei noch 73-45 min gueltig, `ctxReason` =
   EVIDENCE_UNKNOWN bei `mealBasis` = MARKER_CONFIRMED.
2. **Druckschwelle (= M1-Beleg Nr. 2):** r >= 1,0 ab 09:02, q1 > 140 erst
   09:37 — 35 min NOT_CONFIRMED allein durch die BG-Schwelle, waehrend der
   Normalpfad GUARD-gedeckelt war und nur der 0,05er-Drip floss.
3. **Block-Maskierung (= M2-Beleg Nr. 2):** 09:41:31 NORMAL_PATH_OPEN bei
   Streak 5 mit foundationLiftU 0,05 und preFoundationBlock GUARD_FLOOR —
   zweiter Live-Fall, wieder genau 1 Zyklus.

Dazu die ehrliche Einordnung von Tonis Systemkritik: Selbst der bewaffnete
Kanal lieferte um 09:42 nur 0,10 U, weil `needU` aus der Bahn kam (0,35 U
bei q1 143 und +4,3/min — die Bahn hielt die Lage fuer weitgehend gedeckt).
Die Verlaesslichkeit der Mahlzeit haengt heute strukturell an der
Huellen-Schaetzung; die Erweiterungen M1/M2 und der P0-Fix reparieren die
LIEFERTORE, nicht die Bedarfsrechnung. Deren Ueberdeckungsfrage bleibt der
benannte offene Coverage-/Erwartungs-Arbeitspunkt.

**Fix-Vorschlag P0 (Vertragsentscheidung, VOR 1b/Schritt A):**
`EvidenceStock` behandelt einen SINKENDEN `episodeCommittedU` nicht mehr als
verlorenen Zustand, sondern als Widerruf: die interne Marke
(`lastCommittedU`) wird auf den niedrigeren Wert REBASIERT, der Bestand wird
NICHT erstattet (konservativ: die beim Buchen abgezogene Evidenz bleibt
abgezogen), Phase bleibt unveraendert; Export traegt einen
Widerruf-Rebase-Zaehler. Neustart-ohne-Zustand und ruecklaufende
Episodenidentitaet bleiben unveraendert fail-closed UNKNOWN. Begruendung:
das Signal "kumulative Summe sinkt" ist nicht diskriminativ — es entsteht
durch jeden legalen Widerruf; die konservative Rebase-Richtung verliert
hoechstens Kredit, nie Sicherheit. Tests: Widerruf mitten in aktiver
Episode -> Phase bleibt ACTIVE, Bestand unveraendert, kein EXCLUDED_LAGE;
Mutation (alter fail-closed-Pfad) -> rot; Doppel-Widerruf idempotent.

Offen als eigene Produktfrage danach (nicht Teil des P0-Fixes): darf eine
UNKNOWN-Evidenz den Liveness-Kanal ueberhaupt hart ausschliessen, solange
die gepinnte Markervollmacht laeuft? (SUSPENDED — Tief, Segmentbruch,
Widerruf des Markers — bleibt unstrittig absolut.)

**Abgrenzung (Toni 29.08., uebernommen):** Mechanisch belegt ist der
TOTALAUSFALL der Nachsteuerung — FUSE hatte in diesem Verlauf trotz
bestehender Lage keinen nennenswerten Gegensteuerpfad mehr. Dass die
konkreten 190/229-Werte die direkte FOLGE waren, ist counterfactual nicht
beweisbar und wird nicht behauptet.

**ENTSCHEIDUNGEN 29.08. (Toni):** P0 GO mit TYPISIERTEM Vertrag
(commitmentRevision je Episode, nur die zwei Ledger-Widerrufspfade erhoehen
atomar, Rebase nur bei vorgerueckter Revision, kein frueher Return an den
Gefahren-Toren, Export von Revision+Grund — Volltext im Bauauftrag 7.5.4);
MEAL-Streak als eigene M3 nach A1 (MealArmCycles, unkonfiguriert 3,
Tests fuer 1 und 3); Wende-Pause NICHT entfernen — separater Folgeauftrag
(TURN_STANDING pausiert nur die Menge, kein Carry, keine lange
Rearm-Luecke). Reihenfolge: P0 -> M2 -> Schritt A -> M1+M3 -> Wende-Pause.

## 7. Offene Entscheidungen (Toni)

1. Liveness-BG-Schwelle profilabhaengig machen (der 55-min-Hebel von Fall 2):
   in diesen Auftrag aufnehmen oder benannter Folgeauftrag?
1b. underlyingNormalBlock-Tor (Nachtrag 6b) in den Auftrag aufnehmen —
   Empfehlung: JA, als eigener frueher Commit (klein, unabhaengig, Rig +
   Mutation + Replay-Gegenprobe am Abendfall).
2. GUARD_CHAIN_PASSED: erschoepfter Exposure-Raum beendet stehende Nullen wie
   iobTH heute? (Empfehlung: ja, dokumentiert.)
3. Exposure-Headroom zusaetzlich in die Grant-Bildung (AuthorizedLift)?
   (Empfehlung: ja — Endpruefung bleibt trotzdem als Netz.)
4. Kontexttraeger = gepinnte Markerfrist allein? (Empfehlung: ja.)
5. Parameterwerte spaeter, nach Schritt C — mit dem Wissen aus Fall 1: die
   Wirkung liegt beim ExposureLimit (2,5-3,0 U-Kandidaten), nicht beim
   Ratio-Cap.

Keine Installation, kein Flash, keine Aktivierung aus diesem Review.

# Abschlussbericht: FUSE-Mahlzeitenevidenz (Abschluss-Audit 15.08.2026)

Auditierter Stand: `1750ab8818` (eingefroren, raven-Lauf 12.-15.08.).
HEAD nach Audit-Fixes: `78089dfda0`, geflasht auf raven 15.08. 10:06
(Kennung `fuse-freeze-mahlzeitenlauf-1-34-g78089dfda0` vor dem Flash im dex
verifiziert). Beide Branches + lokale fuse-dev-Ref identisch.

Audit-Aufbau: 6 Schwerpunkt-Auditoren + adversarielle Verify-Agenten
(12 Agenten, ~1,6M Tokens), Rohtrail und Zyklus-CSV lokal ausserhalb des
Repos (Gesundheitsdaten; trail_now.jsonl SHA-256 siehe Sitzungsprotokoll
14./15.08., 4742 Trail-/2040 Evidenzzyklen, 8 Episoden).

## Urteile je Schwerpunkt (gegen 1750ab8818)

| # | Schwerpunkt | Urteil | Stand nach Fixes |
|---|---|---|---|
| 1 | Harte 360-min-Grenze ohne Folgeepisode | ERFUELLT MIT AUFLAGEN | Auflage (Fensterregel) umgesetzt |
| 2 | Kredit ausschliesslich nach Seal | ERFUELLT | unveraendert |
| 3 | Totbaender nur bei positivem Kredit entwaffnet | **VERLETZT (P0)** | **gefixt, dreischichtig getestet** |
| 4 | Sieben nachgeschaltete Gates bindend | ERFUELLT MIT AUFLAGEN | Modellausfall-Attest gehaertet |
| 5 | Viewer-Synchronitaet | ERFUELLT MIT AUFLAGEN | Chip-Fallback gefixt (Viewer-Repo) |
| 6 | Restrisiko erster geschlossener Lauf | ERFUELLT MIT AUFLAGEN | Leitplanken unten |

## Der P0: Totband-Entwaffnung war nie verdrahtet

`FuseCycleRunner` uebergab `evidenceCreditActive` NIE an
`FuseController.decide` - der Default `false` verdeckte das
kompilierfehlerfrei. Die Commit-Botschaft von `d42e14fcc9` behauptete die
Verdrahtung ("Runner reicht evidenzKredit > 0 durch"); der Diff enthielt
nur die Trail-Kontaminations-Fixes. Die Totband-Rig-Tests waren aus dem
falschen Grund gruen: ihre Kreditzyklen lagen komplett im
45-min-markerBoost-Fenster, das die Totbaender kreditunabhaengig
entwaffnet. Folge auf 1750ab8818: die 81-Zyklen-Luecke des 2-Tage-Laufs
bestand unveraendert fort. Fehlerrichtung fail-closed (zu wenig dosiert,
nie zu viel) - aber der deklarierte Fix war wirkungslos, und der fruehere
Bericht ("Verdrahtung umgesetzt, Mutationsprobe rot") war in diesem Punkt
falsch: die Probe war nur auf der NightWindow-Schicht rot.

Fix in Testreihenfolge (Commit `78089dfda0`):

1. Boost im Totband-Test auf 0 -> Test wurde ROT (beweist, dass er den
   Fehler jetzt sieht).
2. Verdrahtung: `evidenceCreditActive = evidenzKredit > 0.0` am
   decide-Aufruf -> Test gruen.
3. Default an `decide()` ENTFERNT (die NightWindow-Regel "lieber ein
   Kompilierfehler je Aufrufstelle", die eine Ebene hoeher durch den
   Default ausgehebelt war); 71 Test-Aufrufstellen explizit `false`.
4. decide-Testpaar (Rebound + Nacht, mit/ohne Kredit) macht die mittlere
   Schicht mutationsfest.

Mutationsproben: M41 (Verdrahtung raus) = Kompilierfehler.
M42 (Fensterregel raus) = Gate- und Rig-Test rot.

## Weitere umgesetzte Befunde

- **Fensterregel (P2, Schwerpunkt 1):** ein Druck im Fenster des
  inzwischen abgelaufenen Vorgaengers wird beim Eroeffnungsversuch
  verbraucht statt eroeffnet - die zyklusfreie Strecke (CGM-Ausfall)
  trug ihn sonst unverbraucht ueber das Deckelende. Kante
  `markerTs - id == cap` gehoert dem Vorgaenger (inklusiv, wie der
  Erben-Zweig). Gate-Testpaar + Rig-Test mit Zyklusluecke.
- **Modellausfall-Attest (P1, Schwerpunkt 4):** Kontrollzyklus (Rig
  dosiert ohne Ausfall), Kredit-Vorbedingung AM Ausfallzyklus,
  abortReason muss `noFallback=REASON_NOT_OVERRIDABLE` benennen.
- **Viewer-Chip-Fallback (P1, Schwerpunkt 5, Viewer-Repo `20c5656`):**
  bei fehlender Evidenz-Episode (Neustart-Denial, Deckel nach
  Zweitdruck) summierte der Fallback Trail-ANTRAGSDATEN im
  Pump-Truth-Format. Jetzt: Chip sichtbar, Summe ehrlich unbekannt (Σ—).
- Drei veraltete 240-min-Kommentare im Gate berichtigt (`c637770662`,
  `78089dfda0`).

## Bestaetigte Staerken (Auszug)

- Kredit-nach-Seal algebraisch dicht: `versiegelt = neu - zufluss` kann
  den versiegelten Anteil nie ueberschaetzen; jeder Fehlpfad (Seal-
  Fehlschlag, Crash zwischen Write und Clear, .tmp-Uebernahme) endet in
  Hold -> UNKNOWN -> Kredit 0. 0 Seal-Fehlschlaege in 3853 Persists.
- Option A auf allen zyklusbedienten Pfaden dicht: Neustart,
  Warmstart-Ring, Ruecknahme+Ablauf, beide Uhrenraender. Die
  Bestandsuhr weicht nur in die strengere Richtung von der Gate-Uhr ab.
- Selbstverstaerkung des Kredits doppelt widerlegt (algebraisch:
  Schleifenverstaerkung ~0,04; empirisch: das Rig war das
  Totalmismatch-Experiment, Bestand blieb <= 40,7 mg/dl).
- Worst-Case-Fehldosis des Kredits: realistisch ~1 zusaetzlicher
  maxSmb-Schritt je Welle; strukturell ~1,5 U (Bestand am 200er-Deckel,
  nur per massivem Artefakt erreichbar) - mit 30-40 g KH beherrschbar.
  Zahlen skalieren linear mit maxSmb.

## Offene Punkte (dokumentiert, nicht blockierend)

- P2 Rollback-Asymmetrie: EpisodenBudget-Nebenfelder werden bei
  gescheitertem Seal nicht zurueckgerollt (fail-closed Richtung).
- P2-Atteste: iobTH/maxIOB/Transport pruefen am Kappzyklus nur den
  Kreditfluss, nicht die gehobene Kante; Kernel-Ausfall- und
  onsetEnvelope-Attest fehlen; Ledger-Hold-Kommentar uebertreibt die
  Messstelle.
- P2 Viewer: Abbruchzyklen exportieren `state=null` -> Marker-Chip kann
  waehrend einer Mahlzeit kurz flackern (Evidenzfelder ueberleben den
  Abort, der Marker-Anker nicht). Dazu INFO: 12-min-Summen-Lag vs. live
  tickende Uhr, veralteter 32-MB-KDoc, dreifach duplizierte
  Fensterkonstanten.
- EXPIRED-Phase praktisch unerreichbar (Gate schliesst id-basiert
  zuerst) - kosmetisch; mealStats-vs-committedU-Anzeigedivergenz
  (gewollt getrennte Anker, dokumentiert).

## Durch die offene Schleife strukturell UNGEPRUEFT

1. **Abhang-Freigabe:** 162 Kredit-Zyklen bei fallendem BG gaben auf dem
   Rig fast nichts frei - aber mit geschenktem Guard-Pessimismus (das
   prod-Insulin war dem Modell unbekannt). Im geschlossenen Kreis WIRD
   diese Klasse freigeben. Realistische Fehlerrichtung: +0,3-0,8 U am
   Abhang nach dem Gipfel, Hypo-Fenster 60-120 min nach Peak.
2. Bezahlgleichgewicht und decayMin unter echter Eigenwirkung.
3. Medtrum-Pfad des Kredits (alle 4742 Zyklen liefen auf VirtualPump);
   Fehlrichtungs-Analyse aller Medtrum-Pfade laeuft auf Unterdosierung.

## Verdikt

**Testhandy raven: vollstaendig freigegeben** auf `78089dfda0`.

**Produktiv: bedingtes GO fuer EINEN kontrollierten ersten
Mahlzeitenlauf** mit sofort verfuegbarem autoISF-Fallback - unter diesen
Bedingungen:

- **Vorher ein Mahlzeitenfenster auf raven mit dem NEUEN Stand** (der
  heutige Fix hat null Geraetestunden): Totband-Entwaffnung im Trail
  gegenpruefen (Kreditzyklen unter Ziel+Totband ohne
  nightDeadband/reboundDeadband-Block) und Eroeffnungs-ageMin klein.
- Vorbedingungen: tagsueber, zu Hause, Toni am Viewer; Start-BG 90-140;
  Referenzmahlzeit aus den Rig-Tests; Katheter am ARM (Bauch misst den
  Ort, nicht den Regler); **maxSmb auf 0,3 zuruecknehmen** (Rig lief
  zuletzt 0,55; alle Worst-Case-Zahlen skalieren linear).
- Beobachtungsfokus auf den ABHANG, nicht den Anstieg: Phase muss
  ~10-15 min nach der r-Wende auf DORMANT fallen; SMBs bei fallendem BG
  mit Kredit > 0 sind das Eingreifsignal; Zufluss bei flachem/fallendem
  Roh-BG ohne plausible Rest-Absorption = Phantom-Verdacht -> Marker
  widerrufen.
- Live-Schwellen: Bestand nachhaltig > 60-80 mg/dl oder Kredit > 2 ueber
  3 Zyklen = Artefaktverdacht. Jeder Ledger-Hold oder UNKNOWN waehrend
  des Essens = Abbruch, nicht live debuggen.
- Abbruchkette: Marker-Widerruf nullt den Bestand im selben Zyklus
  (Sekunden); aisf-Rollback 10-20 min als Tagesausstieg, vorher einmal
  geprobt. 30-40 g schnelle KH bereit.
- Stichprobe waehrend des Essens: committedU (Episode) gegen die
  Treatments-DB der Pumpe (Viewer-Σ-Zeile liest die rohen SMBs).

Das Go-Kriterium "Mahlzeiten >= aisf" ist auf dem Rig prinzipiell nicht
beweisbar (Zwei-Geraete-offene-Schleife: nur Timing/Form vergleichbar,
nicht Summen). Der Vergleich 12.-14.08. zeigt das Front-Load-Muster
(3 U Prime in T+30, wo aisf 0,2-0,35 U hatte) und kein Wiederauftreten
des 41-min-Lochs - ob das zurueckhaltendere Hintenraus reicht, zeigt
erst der geschlossene Kreis. Genau dafuer ist der kontrollierte erste
Lauf da.

# Schritt C: Freigabe-Vorlage — zentrale CORRECTION-/MEAL-Dosierpolitik

Stand 29.08.2026 abends. Bauauftrag `fuse-bauauftrag-zentrale-dosierprofile.md`
(inkl. §7.5-Verträge vom 29.08.), Abschlussbericht nach §12. **NICHTS ist
geflasht, nichts aktiviert** — Parameterwahl, Aktivierung, Installation und
Flash sind ausdrücklich freizugeben (Toni).

## 1. Geänderte Komponenten und Zuordnung der Lieferwege

Commit-Kette (jeder auf `3.4.2.5+fuse1.0.0-toni` UND `fuse-dev`):

| Baustein | Commit | ruleSet | Inhalt |
|---|---|---|---|
| Review + Anlassfälle | `6914529d65`, `18ed1c5ff5`, `c9921c6b55`, `879a879381` | — | Verifikation, Nachträge 6b/6c, Entscheidungsstand |
| P0 Widerruf-Rebase | `ff53a68806` + `7591990232` | v36 | commitmentRevision, typisierter Rebase statt UNKNOWN-Deadlock |
| M2 Bewaffnungstor | `1ecc8948c4` | v37 | underlyingNormalBlock (vor der ersten autorisierten Anhebung) |
| A1 DosingContext | `a6966e4ffb` | — | CORRECTION/MEAL AUSSCHLIESSLICH aus gepinnter Markerfrist |
| A2 ExposureView | `145eb6a210` | — | EINE Expositionssicht (Grenze − capIob − Transport), bitgleich |
| A3 Coverage-Export | `cf0cafcd3a` | — | occupiedU + coverage() aus derselben Sicht, Exporter schreibt ab |
| A4/A5 Struktur | `2b6d96d494` + `f7badc38fb` | v38 | policyMode LEGACY\|CENTRAL_PROFILES, 4 Kandidaten, modusabhängiger Hash, Backup-Rundlauf |
| M1 MEAL-Druckschwelle | `6411ed3d49` | v39 | absolute Schwelle unter Vollmacht, unkonfiguriert = Altpfad |
| M3 MealArmCycles | `089fa53577` | v40 | Bewaffnungszyklen unter Vollmacht (CORRECTION bleibt 3) |
| M-Abschluss | `7445300c25` | — | Backup-Migration M3, CENTRAL-Aktivierungssperre (EIN Validator für UI + Restore) |
| B1 Endprüfung | `f4145a2735` + Korrektur `07d7c6c91f` | v41 | ExposureGate an BEIDEN Einbaustellen, Grant-Kappung, Block EXPOSURE_LIMIT |
| B2 Demand-Ratio | `19b6fc1438` | v42 | min(Basis, Kontext-Cap) für NORMAL + LIVENESS, Alt-Cap-Ersetzung |
| B3 Replay | `3366203f5d` + `aa7aac13f8` | — | politikAnwenden, FUSE_REPLAY_DOSING_CONTEXT, CSV-Attribution, 27.08.-Attribution |
| Pflichtfall 10 | `a3ad9ce09f` | — | Wiederherstellungspfad (CALM_BATCH) unter der Endgrenze |

**Lieferwege, vollständig zugeordnet (FinalSource, typisiert, identisch
Haupt-/Fallbackpfad):** NONE, NORMAL, NORMAL_SUBSTEP, PRIME, FOUNDATION,
MEAL_UPFRONT, DEFERRED_RELEASE, LIVENESS, CALM_BATCH, CALM_DEMAND. Die vier
Nutzerlabels (NORMAL/LIVENESS/MEAL_UPFRONT/FOUNDATION) behalten ihre
Untertypen; kein Lieferweg wurde unsichtbar.

**Einordnung je Weg unter der neuen Politik:**
- Bedarfsquellen (NORMAL, NORMAL_SUBSTEP, LIVENESS): effectiveDemandRatio =
  min(Basis, Kontext-Cap); Basis-Differenz dokumentiert (Normalpfad: Rampe
  MIT Rebound-/Mahlzeit-Fenster-Gate; Liveness: v27-Rampe OHNE Fenster-Gate).
- Autorisierte Direktwege (PRIME, FOUNDATION, MEAL_UPFRONT,
  DEFERRED_RELEASE, CALM_*): NIE ratio-gedeutet (Invariante 5); Exposure-
  Grenze wirkt in der GRANT-BILDUNG (AuthorizedLift) UND als Endprüfung.
- Fallback (markerFallbackCycle): eigenes Gate nach dessen DescentGate;
  KEIN Bedarfs-Ratio-Wirkort (Basis smbU = 0, nur Prime-Anteil).

## 2. Neutralität des bisherigen Pfads (LEGACY)

- policyMode-Default ist LEGACY; ohne aktivierten Schalter sind Gate,
  Grant-Kappung und Ratio-Kandidat NICHT im Pfad (null → listOfNotNull
  lässt die Kappenliste bitgleich; ExposureGate wird nicht gerufen).
- Rig-Gegenproben: „B1 — im LEGACY-Modus läuft das Gate nicht",
  „B2 — im LEGACY-Modus wirkt kein Kandidaten-Cap" (gesetzte Kandidaten
  wirkungslos, Lieferung oberhalb der Kandidatenwerte).
- Replays: Base-vs-A2 (Abend 28.08., 189 Zyklen) = 0 Abweichungen;
  B3-Lauf ctxbase vs. Aufzeichnungspolitik = derselbe LEGACY-Pfad.
- Aktivierungssperre: CENTRAL ist ohne vier gültige Kandidaten nicht
  einschaltbar (UI-Schalter mit Warn-Toast UND Backup-Restore, derselbe
  Validator); unvollständiges CENTRAL-Backup restauriert als LEGACY.
- EINE dokumentierte Nicht-Neutralität AUSSERHALB des Modus: seit B2 steht
  der Kontextblock vor der State-Konstruktion — Fallback-/Abbruch-Zyklen
  pflegen die Markerfrist mit (DIESELBEN Pin-Werte, nur früher; Deadline
  hängt allein an markerTs). Das ist die A1-Zusage, kein Dosierunterschied.

## 3. Der neue Endpfad, testbar wirksam

- ExposureGate: min(iobTH, maxIOB, Kontextgrenze) − capIob − Transport,
  Rasterung ausschließlich abwärts (tickEps), reine Mengenprüfung (kein
  zweiter Guard-/Tail-/finalVeto-Lauf). Letzte hebende Stufe an BEIDEN
  Einbaustellen; danach nur Reduzierer.
- Block EXPOSURE_LIMIT: absolute Mengengrenze (lifts = false),
  NO_NEW_POSITIVE, GUARD_CHAIN_PASSED, erzeugt NIE eigenständig Zero-TBR
  (Invariante 7; auf echten 27.08.-Daten in allen 8 Blockzyklen belegt).
- Empfindlichkeit (Mutationen, je gezielt rot):
  - B1 „Endgrenze entfernt" → exakt 3 Gate-Tests rot.
  - B2 „Ratio-Kandidat entfernt" → exakt 2 Wächter rot (Core-Name + Rig-Rate).
  - Partitionswächter (MarkerAuthorizationTest) verlangt jede neue
    Block-Einordnung explizit — hat den B1-Commit real gestoppt (s. §4).
- §10-Pflichtfälle, alle 13 mit tatsächlich erreichtem Pfad:
  1. Neuer Pfad aus → LEGACY-Rigs + Base-Replays (0 Divergenzen).
  2. Normalpfad, Liveness aus → B1-/B2-Rigs (Correction-Cap begrenzt die normale Endmenge/Bedarfsrate).
  3. Hoher Rise ohne Autorisierung → strukturell: Kontext hängt NUR an der gepinnten Markerfrist (DosingContextTest; ExpectationLedger.classify als Quelle verworfen); 27.08.-Lauf: Burst mit r hoch bleibt CORRECTION.
  4. Power abgelaufen, Episode läuft → Rig: exakt an der Deadline CORRECTION/POWER_EXPIRED, halb offen, kein Wiederaufleben.
  5. Quellenwechsel → Liveness „max statt Addition"-Rigs; Gate prüft die gemergte Endmenge.
  6. Neustart/Wiederholung → Re-Arm überlebt Neustart; vorgefundener Marker pinnt nie; Exactly-once-Rigs.
  7. Manuelle/offene Abgaben → Transporthaftung verengt Raum (Gate-Test); manueller Bolus sperrt Bewaffnung (Bestandsrig).
  8. Negatives/positives Basal-IOB → capIob = max(net, bolus) (Core-Test), dieselbe Reihenfolge in ExposureView (==-Test).
  9. Upfront → keine Ratio-Zerstückelung (B2-Invariante-5-Rig: 4-U-Sofortanteil bei Cap 0,05 unverändert); Exactly-once erhalten.
  10. Wiederherstellungspfad → CALM_BATCH unter knapper Grenze real gekappt, Rest bleibt abrechenbar offen, kein Zero (`a3ad9ce09f`).
  11. Aktuelle Gefahr/ungültige Daten → konstruktiv: das Gate ist reines min() und kann nur senken; Gefahrenklassen (UNSAFE/SAFETY_HOLD/Latch) laufen VOR ihm und unverändert (dokumentierte Implikation; CALM-Rig prüft currentHazard=none als Liefervoraussetzung).
  12. Exposure erschöpft → Rig + 27.08.-Livedaten: EXPOSURE_LIMIT ohne Zero.
  13. Coverage Bedarf 0/fehlende Eingabe → ExposureViewTest: pct null bzw. unbekannt, keine Ersatzwerte.

## 4. Tests: Befehle, frische Ergebnisse, ein eingestandener Fehler

Testbefehl (immer mit UNGEFILTERTEM Exit — `; echo "SUITEN-EXIT=$?"`,
Verdikt zusätzlich aus frischen XML-Attributen):

```
JAVA_HOME=<JBR> ./gradlew :fuse:core:test :fuse:plugin:testFullReleaseUnitTest --rerun
```

Finale Vollsuiten auf `a3ad9ce09f` (frisch, --rerun): **core 1232 grün /
plugin 962 grün (1 skip = env-Replay), SUITEN-EXIT=0.**

Ehrlichkeit §12.4: Commit `f4145a2735` (B1) nannte Suitenzahlen, während
der Gradle-Exit durch eine grep-Pipe verschluckt war — real war der
Partitionswächter rot und die Plugin-Suite nie gelaufen. Richtiggestellt
in `07d7c6c91f`; die Lehre (nur ungefilterter Exit + frische XML) steht
seither in jedem Verdikt dieses Auftrags.

Replay-Befehl der B3-Attribution (nachvollziehbar, §10-Offline-Auflagen):

```
FUSE_REPLAY_TRAIL=mylogs/trail_27-28_rot1.jsonl FUSE_REPLAY_VON=1787853600000 FUSE_REPLAY_BIS=1787869800000 \
FUSE_REPLAY_OUT=mylogs/replay_ctx_2708 \
FUSE_REPLAY_DOSING_CONTEXT="corrExp=2.5,mealExp=6.0,corrRatio=1.0,mealRatio=1.0;corrExp=3.0,mealExp=6.0,corrRatio=1.0,mealRatio=1.0;corrExp=20,mealExp=20,corrRatio=0.5,mealRatio=1.0" \
./gradlew :fuse:plugin:testFullReleaseUnitTest --rerun --tests "...Phase 2 - Fenster-Replay der aufgezeichneten Tage"
```

## 5. Offline-Vergleich (Details: B3-ATTRIBUTION-27-08.md)

- Neutralität: 0 Divergenzen außerhalb des Falls (20:00–21:54), alle Varianten.
- Erste Divergenz 21:58:21, in allen Varianten identisch, attribuiert auf
  den RATIO-Baustein als **Alt-Cap-ERSETZUNG** (0,150 `livenessRatioCap` →
  0,250 `smbRatio`), NICHT auf einen Kandidaten-Griff.
- Exposure: corrExp 2,5 kappt den Burst 2,45 → 0,45 U (Teilkappe + 8×
  Vollblock, benannt, ohne Zero); 3,0 → 1,15 U; offene Grenzen → 3,95 U
  (schneller als LEGACY 3,40!). corrRatio 0,5 griff nie.
- Grenzen: rückkopplungsblind (Summen = Obergrenzen; belastbar sind
  Zeitpunkt/Richtung/Attribution der ersten Abweichung), Kaltstart-Grenze
  eingehalten, Base-vs-Aufzeichnung Fidelity-begrenzt (2,45 U/7 Zyklen vs.
  aufgezeichnet 2,50 U/12).

## 6. KEINE neutrale Migration — die ehrliche Verhaltensänderung

Auflage aus Schritt C wörtlich erfüllt: eine gemeinsame neue Grenze kann
die heutigen unterschiedlichen Normal-/Liveness-Drosseln nicht beide
erhalten. Konkret: **im zentralen Modus ersetzt der Kontext-Cap die
Liveness-Alt-Caps ersatzlos** (kein min(alt, neu), Migrationsvertrag).
Wer mit offenen Ratio-Kandidaten (1,0) aktiviert, gibt dem Liveness-Kanal
MEHR Geschwindigkeit als LEGACY (gemessen: +0,55 U im 27.08.-Fenster).
Der Kontext-Cap ist im zentralen Modus die EINZIGE Drossel dieser Klasse —
seine Wahl ist eine echte Entscheidung, keine Umbenennung. (Die
Profil-IOB-Deckel bleiben nach §7.4 vorerst in BEIDEN Modi.)

## 7. Noch nötige Entscheidungen (getrennt)

**Parameter (Toni, vor Aktivierung):**
- CorrectionExposureLimit [U] — Messpunkte: 2,5 → Burst 0,45 U; 3,0 → 1,15 U.
- MealExposureLimit [U] — bisher nur Rig-Evidenz, kein Mahlzeit-Replay.
- Correction-/MealDemandRatioCap — ACHTUNG Befund oben: 1,0 = schneller
  Kanal als LEGACY; die aufgezeichnete Alt-Drossel lag bei 0,15/0,25-Klasse.
- LivenessBgMinMeal (M1) und MealArmCycles (M3) — Kandidaten aus den
  Fallanalysen (140 mg/dl-Loch, Streak-3-Verlust), Wert offen.

**Coverage:** Regel-Semantik weiter offen (coverageState UNAVAILABLE bis
eine horizontkonsistente Insulinwirkung existiert); eigener Commit nach
Auswertung. Die Profile reparieren die LIEFERTORE, nicht die
Bedarfsrechnung (needU bahnbasiert — bekannte ehrliche Grenze).

**Aktivierung/Betrieb:** Flash-GO; CENTRAL_PROFILES-Einschalten am Gerät
(erst nach Parameterwahl möglich — Sperre); Viewer/Widget-Nachzug vor
Produktivgang (Tonis Tor); Wende-Pause als separater Folgeauftrag (KEIN
GO fürs Entfernen); Legacy-Abbau erst nach belegtem Produktivstand;
Kaltstart-Pin-Kante (P2).

## 7b. Nachtrag 29.08. spaet: Review-Korrekturpaket (P1 + Cleanup + Statusmodell)

Tonis Review-Verdikt (P1-Blocker + Legacy-Cleanup in zwei Schichten +
typisierter SMB-Status) ist umgesetzt, Reihenfolge 1-6:

| Schritt | Commit | Inhalt |
|---|---|---|
| 1+2 P1 | `f2741e84f1` (v43) | Liveness-Kanaldeckel = Kontextgrenze im Zentralmodus; Legacy-Prozentdeckel NUR noch LEGACY; Lauf-Kennung traegt die wirksamen Werte; selectedIobCapPercent zentral null; Mutation "zurueck zum Prozentdeckel" rot |
| 3 Status | `98e66e3366` | SmbStatus (FREE/STOP/NO_DEMAND/UNKNOWN + typisierte Gruende), requestedSource/finalSource getrennt (final NONE bei 0 U), requestedU/cappedU/publishedU, Fallback-Export-Block repariert, CSV +5 Spalten |
| 4 Entschlackung | `51740c5139` | Legacy-Deckel im Zentralmodus UNSICHTBAR (Settings, sofort in beide Richtungen), Report-Zeilen fallen weg (statt "ignoriert"), FUSE-Tab traegt die verbindliche Statuszeile |
| 5 Viewer | Viewer `f3ade1d` | Parser (smb/exposure/dosingContext/policyMode), EIN Modell fuseSmbStatusZeilen fuer Widget (smb2-Slot) UND Dashboard, RUHIG/UNBEKANNT nie rot |
| 6 Replays | s. B3-ATTRIBUTION Nachtrag | LEGACY bitgleich; enge Kandidaten zyklusgleich; ctx03 +0,35 U = die versteckte Altgrenze wirkte real |

Suiten (ungefilterter Exit, --rerun): Fork core 1237 / plugin 970 gruen
(1 skip = env-Replay); Viewer 430 gruen. Die fruehere 7.4-Begruendung
des B2-Commits ist damit KORRIGIERT: 7.5.7 ersetzt den Parallelbetrieb;
im Zentralmodus wirkt und erscheint keine Altgrenze mehr. Der
LEGACY-Rueckweg (Keys + Pfad) bleibt fuer den ersten Produktivstand
erhalten; Abbau nach belegtem Korrektur- UND Mahlzeitenlauf.

Offen aus dem Paket: Geraeteblick auf Settings-Sichtbarkeit, FUSE-Tab-
und Widget-Zeile (fuse-ui-testluecke - Modelle sind getestet, Geraete-
Rendering nicht); Anzeigefrage GUARD-Stop ohne Bedarf (s. B3-Nachtrag);
Analyse-Tab des Viewers liest die neuen Bloecke noch nicht (P2).

## 7c. Nachtrag 29.08. nachts: CENTRAL-only (Tonis Cleanup-Vertrag, v44)

Auf Tonis GO ist die Doppelarchitektur beendet: Modusschalter,
LEGACY-Runtime und die sechs Legacy-Kanaldeckel sind vollstaendig
entfernt (`5553147540`, v44); policyMode bleibt als Export-Konstante.
CORRECTION ist der Grundzustand, MEAL kommt aus der Markervollmacht.
Die vier Profilwerte und die MEAL-Regler tragen ECHTE Startsatz-
Defaults (CORR 3,0 U / 0,20 / Druck 140/160, Armierung 3 im Kanal;
MEAL 7,0 U / 0,35 / Druck 110 / Armierung 1) - als Runtime- UND
Migrationsdefaults mit Default-Waechter-Test; gesetzte Werte
ueberschreibt kein Update. Davor: CAP-Anzeigefix (CAP =
effectiveLimitU, Profil-CAP nur bei Abweichung; Fork `e2e1bfa888`,
Viewer `9d389a2`) und der Viewer-Abschlussblock (`fc178d1`:
Analyse-Parser typisiert, SMB-Tabelle mit Mengen-/Quellenkette,
Meal-Dialog trennt Vollmacht von Evidenzepisode, Config-Tab +
SystemCheck-Check 9, toter SMB-Inspektor samt zweiter Pumpenpaarung
geloescht; positiveRequestU/rt.units fuer die Pumpenpaarung
unangetastet).

v44-Replay-Smoke am 27.08.-Fall: Startsatz -> Burst 0,85 U (2,5er:
0,45; Konsistenzbeweis Default == explizit). Suiten: Fork core 1237 /
plugin 962, Viewer 431, alle Exit 0. Tonis Feintuning-Regel nach dem
ersten Lauf: jeweils nur EINEN Wert aendern (MEAL endet frueh an
EXPOSURE -> Meal-Exposure in 0,5er-Schritten; Exposure frei aber
demandRatioCap bindet -> Meal-Ratio; spaete CORRECTION zu stark ->
zuerst Correction-Exposure).

Weiter offen: Geraeteblick (Settings/FUSE-Tab/Widget/Viewer), dann
Flash-GO. Legacy-Abbau-Restposten: keiner - erledigt mit v44; die
alten Keys existieren nur noch in historischen Trails.

## 8. Betriebsstatus

- **Am Gerät aktiv:** Stand `851f4b9e11` (28.08. 18:15, ruleSet 35). NICHTS
  aus diesem Auftrag ist geflasht oder aktiviert.
- **Gebaut + getestet (beide Branches, HEAD `a3ad9ce09f`):** alles aus §1;
  Default-Zustand LEGACY = bitgleicher Altpfad; CENTRAL_PROFILES ist eine
  gesperrte Einstellung ohne gesetzte Kandidaten.
- Persönliche Trails und Roh-CSVs bleiben lokal (mylogs/, gitignored);
  committet sind nur aggregierte Berichte.

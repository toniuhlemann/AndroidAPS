# FUSE — Upstream-Portierungsmatrix

Navigation: [README](README.md) · [Architektur](FUSE_ARCHITECTURE.md)

**Zweck.** FUSE muss auf neue `nightscout/AndroidAPS`-Masterstände portierbar bleiben.
Diese Datei sagt, **welche Stellen ausserhalb von `fuse/` der Fork anfasst**, warum, welcher
Konflikt beim Merge zu erwarten ist, welcher Test die Stelle hält, und was beim nächsten
Merge zu tun ist.

**Akzeptanzkriterium.** Nach einem AAPS-Master-Merge muss FUSE durch wenige klar
dokumentierte **additive** Änderungen wieder baubar sein, **ohne Pumpentreiberlogik erneut
zu analysieren oder zu portieren**.

**Stand:** 2026-08-29 · Basis des FUSE-Zweigs: `9e7658cd76` (erster `fuse/`-Commit) ·
Upstream-Basis des Forks: **`3.4.2.5`** (`buildSrc/.../Versions.kt`:
`3.4.2.5+fuse1.0.0-toni`; `aaps-ci.yml` fährt `3.4.2.5-dev`). Der Tag `3.4.2.4` auf
`origin/master` ist ein älterer Stand des gespiegelten Upstream-Zweigs und **nicht** die
Basis dieses Forks.

---

## 0. Die tragende Eigenschaft

> **`pump/medtrum` enthält keine einzige FUSE-Änderung.** Ebenso wenig `BLEComm.kt`,
> `MedtrumService.kt` oder irgendein anderer Pumpentreiber.

Nachweisbar mit:

```bash
git diff --stat 9e7658cd76^ HEAD -- pump/
```

Automatisch geprüft von `tools/check_fuse_pump_isolation.sh`, aufgerufen bei jedem Push
und jedem PR aus `.github/workflows/fuse-architecture-ci.yml`.

Der Wächter prüft die **Herkunft**, nicht den Zustand:

```bash
git log --no-merges --format='%H %an %s' <basis>..HEAD -- pump/
```

`--no-merges` ist der ganze Trick. Ein AAPS-Merge **darf** `pump/` ändern — das ist
Upstream-Arbeit und kommt als Merge-Commit herein. Ein im Fork **geschriebener** Commit
darf es nicht. Ein kumulativer `git diff` wäre nach dem nächsten Merge dauerhaft rot und
damit wertlos.

**Wird der Wächter rot, ist das Akzeptanzkriterium gebrochen** — unabhängig davon, wie
klein die Änderung aussieht.

Daraus folgt die Regel, die alles andere trägt:

> **Keine FUSE-Sicherheitsinvariante darf von einer privaten
> Treiber-Implementierungsstelle abhängen.**

Konkret: die Pumpenerkennung in `FusePumpGate` läuft über `Pump.model(): PumpType` —
eine öffentliche, stabile AAPS-Schnittstelle. Nicht über Klassennamen, nicht über
Paketnamen, nicht über ein Feld im Medtrum-Modul. Ein umbenannter Treiber, ein
verschobenes Paket oder ein refaktorierter `MedtrumPlugin` darf den Riegel weder öffnen
noch brechen.

**Bewusst nicht abgefragt:** `serialNumber()`. Sie ist nach jedem Prozessstart zunächst
leer (asynchrones Nachladen) — ein Riegel darauf wäre genau in der Minute offen oder zu,
in der niemand damit rechnet.

---

## 1. Konflikttypen

Die Matrix benutzt vier Stufen. Sie sagen, **wie viel Denkarbeit** ein Merge an dieser
Stelle kostet.

| Typ | Bedeutung | Aufwand beim Merge |
|---|---|---|
| **N** — Neu | Datei existiert nur im Fork. Konflikt strukturell unmöglich | keiner |
| **L** — Listeneintrag | Eine Zeile in einer Aufzählung, Liste oder einem DI-Modul | trivial, `git` löst das meist selbst |
| **B** — Block in fremder Methode | Ein eingefügter Abschnitt in einer bestehenden Methode | mittel — bricht, wenn Upstream die Methode umbaut |
| **V** — Verhaltensänderung | Bestehende Logik geändert, nicht nur ergänzt | **hoch — bei jedem Merge neu prüfen** |

---

## 2. Matrix — Registrierung (Typ N/L)

Ohne diese Zeilen existiert FUSE nicht. Sie sind mechanisch und risikolos.

| Stelle | Zweck | Typ | Testnachweis | Beim Merge |
|---|---|---|---|---|
| `settings.gradle` | `include ':fuse:core'`, `':fuse:plugin'` | L | Build | beide Zeilen wiederherstellen |
| `app/build.gradle.kts:195` | `implementation(project(":fuse:plugin"))` | L | Build | Zeile wiederherstellen |
| `buildSrc/.../Versions.kt` | `appVersion = "3.4.2.5+fuse1.0.0-toni"` | V | — | **immer Upstream-Version + eigenes Suffix**, nie die Fork-Zeile blind behalten |
| `app/.../di/PluginsListModule.kt` | `FusePlugin` unter `@APS`, `FuseOverviewSource`, `FuseFragment` | L | Build/DI | drei Bindungen wiederherstellen; **`@APS`, nicht `@AllConfigs`** |
| `core/interfaces/.../aps/APSResult.kt:66` | `Algorithm.FUSE` | L | Build | Enum-Wert ergänzen |
| `database/.../entities/APSResult.kt:67` | `Algorithm.FUSE` in der DB-Entität | L | Persistenz | Enum-Wert ergänzen — **sonst fliegt der erste FUSE-Lauf beim Schreiben** |
| `database/.../converters/APSResultExtension.kt` | Umwandlung des Enum-Namens | B | Persistenz | Zweig ergänzen; ohne ihn liest `toAlgorithm` die eigene Zeile nicht mehr |
| `core/keys/BooleanKey.kt`, `StringKey.kt` | FUSE-Preferences | L | Build | Schlüssel ergänzen |

---

## 3. Matrix — Anzeige (Typ N/L/B) — **dekorativ, darf warten**

⚠️ **Diese Abgrenzung ist selbst sicherheitsrelevant.** „Anzeige" heißt hier
ausschließlich: die grafische Darstellung im AAPS-Hauptschirm. Sie darf bei einem Merge
auf später verschoben werden, weil FUSE ohne sie identisch regelt — es sieht nur niemand
zu.

**Nicht gemeint ist die Beobachtbarkeit des Reglers.** Die steht in §3b und ist bei
Realpump-Betrieb **verpflichtend**. Wer beides in einen Topf wirft, verschiebt beim
nächsten Merge unter „ist ja nur Anzeige" genau die Felder, an denen man hinterher
ablesen müsste, was das System getan hat.

| Stelle | Zweck | Typ | Beim Merge |
|---|---|---|---|
| `core/interfaces/.../overview/FuseOverviewSource.kt` | Schnittstelle für die Overview-Anbindung | **N** | Datei kommt unverändert mit |
| `core/interfaces/.../overview/OverviewData.kt` | FUSE-Felder | B | Felder ergänzen |
| `core/interfaces/.../overview/OverviewMenus.kt` | `FUSE_DRV`, `FUSE_GRD` | L | zwei Enum-Werte |
| `core/graph/.../MealMarkerDataPoint.kt` | Marker-Datenpunkt | **N** | kommt mit |
| `core/ui/.../dialogs/FuseMarkerDialog.kt` | Marker-Dialog (mit/ohne Vorschuss, Rücknahme) | **N** | kommt mit. **Achtung, kein Schmuck:** der einzige Bedienknopf. Fällt er beim Merge weg, regelt FUSE weiter, aber nur noch CORRECTION — keine Mahlzeiten-Erklärung mehr. Trotzdem §3: der Regler selbst bleibt identisch |
| `core/ui/res/values/strings.xml` | Dialog-Beschriftungen | L | Einträge ergänzen |
| `core/graph/.../Shape.kt`, `PointsWithLabelGraphSeries.kt` | Marker-Form | B | Zweig ergänzen |
| `core/ui/res/{colors,styles,attrs}.xml` | Marker-Farben, hell und dunkel | L | Einträge ergänzen |
| `plugins/main/.../overview/{OverviewModule,OverviewDataImpl,OverviewFragment,OverviewMenusImpl,GraphData}.kt` | FUSE-Overlay im Hauptschirm | B | **teuerste Stelle dieses Blocks** — Upstream fasst die Overview oft an. Bei Konflikt: Overlay weglassen, Rest bauen |
| `workflow/.../PrepareIobAutosensGraphDataWorker.kt` | Markerdaten vorbereiten | B | Block ergänzen |
| `plugins/main/res/values/strings.xml`, `plugins/aps/.../strings.xml` | Beschriftungen | L | Einträge ergänzen |

---

---

## 3b. Beobachtbarkeit — bei Realpump **verpflichtend**

Sobald `FusePumpGate` eine echte Pumpe erlaubt, sind die folgenden Felder keine
Bequemlichkeit mehr, sondern die einzige Möglichkeit, im Nachhinein zu sagen, **was das
System getan hat und warum**. Fällt eines davon aus, läuft FUSE weiter und niemand merkt
es — das ist der Unterschied zu §3, wo ein Ausfall sofort auffällt, weil ein Bild fehlt.

Diese Felder liegen sämtlich in `fuse/plugin` und berühren **keine** AAPS-Kerndatei. Sie
stehen hier, damit sie beim Merge nicht versehentlich unter „Anzeige" fallen.

| Feld | Quelle | Warum bei Realpump unverzichtbar |
|---|---|---|
| `gate.verdict` / `gate.allowed` / `gate.realPump` | `FusePumpGate` | Ohne `realPump` lässt sich ein Lauf gegen die VirtualPump nicht von einem gegen echtes Insulin unterscheiden. Jede spätere Auswertung stünde auf Sand |
| `health` | Observer | Sagt, ob der Regler überhaupt zurechnungsfähig war. Eine Entscheidung bei schlechter Signalqualität ist anders zu lesen als dieselbe Zahl bei guter |
| Holds (Ledger-Hold, Safety-Holds) | `LedgerReducer`, Controller | Ein zurückgehaltener SMB und ein nicht gerechneter SMB sehen in der Ausgabe gleich aus — **außer** der Hold steht in den Daten |
| `publicationGate.allowed` / `.reason` / `.treatmentViewPresent` | `LedgerPublicationGate` | Der Grund einer Zurückhaltung lebte früher nur im Freitext von `rt.reason`, den der Export gar nicht ausgibt. Genau deshalb wurde er in B0c zu Daten gemacht |
| offene konservative Transporthaftung (`transportCommitmentU`, `openEntries`, `residualU`) | Ledger | **Das wichtigste Feld dieser Liste.** Es ist die Menge, die FUSE sich selbst zurechnet, weil AAPS sie noch nicht bestätigt hat. Ohne sie ist von außen nicht unterscheidbar, ob eine Haftung konservativ offen steht oder still verschwunden ist |

**Regel für den Merge:** Bricht eines dieser Felder, ist das ein Blocker für den
Realpump-Betrieb — auch wenn alles andere baut und alle Tests grün sind. Zurückfallen auf
`FusePumpGate` = nur VirtualPump ist dann die richtige Antwort, nicht Weiterlaufen mit
blindem Export.

---

## 4. Matrix — Kernänderungen (Typ B/V) — **hier liegt das Risiko**

Diese Änderungen tragen **keinen FUSE im Namen**. Genau das macht sie beim Merge
gefährlich: eine Textsuche nach „fuse" findet sie nicht.

| Stelle | Zweck | Typ | Testnachweis | Beim Merge |
|---|---|---|---|---|
| `core/interfaces/.../aps/IobTotal.kt` + `core/objects/.../IobTotalExtension.kt` | **`valid`-Flag.** Ein ohne Profil gebautes `IobTotal` ist rundum null und sieht damit aus wie „kein Insulin an Bord" — statt wie „unbekannt". Ohne das Flag blenden die Headroom-Gates | **V** | `IobTotalValidityTest.kt` | Änderung wiederherstellen. **Pumpenunabhängig und allgemein nützlich — Kandidat für einen Upstream-PR** |
| `plugins/main/.../IobCobCalculatorPlugin.kt` | Cache-Gültigkeit an dasselbe Flag gehängt | **V** | `IobCacheValidityTest.kt` | wiederherstellen; hängt an der Zeile darüber |
| `implementation/.../queue/QueueWorker.kt` | **Ausnahme aus `command.execute()`.** Vorher blieb `performing` gesetzt und die Queue stand still; der erste Fix zog danach weiter Kommandos, obwohl der Ausgang des vorigen unbekannt war | **V** | `QueueWorkerTest.kt` | wiederherstellen. **Pumpenunabhängig, echter Upstream-Bugfix — PR-Kandidat** |
| `plugins/sync/.../NsIncomingDataProcessor.kt` | Boli aus Nightscout auf Plausibilität prüfen (endlich, > 0, ≤ 60 U). Ein korruptes `insulin`-Feld wirkt sonst über die volle DIA in jede IOB-Rechnung | **V** | — *(ungedeckt, s. §7)* | wiederherstellen. Pumpenunabhängig, PR-Kandidat |
| `plugins/aps/.../FuseTreatmentTransitionCollector.kt` | Beobachtet `temporaryId → pumpId` an Bolus-Zeilen. **Nur lesend, rührt keine Dosierung an** | **N** | `FuseTreatmentTransitionCollectorTest.kt` | Datei kommt mit |
| `app/.../MainApp.kt:262-267` | Ruft den Collector im Minutentakt | B | — | drei Zeilen wiederherstellen |
| `plugins/aps/.../OpenAPSAutoISFPlugin.kt` | Anbindung des Collectors | B | — | Block wiederherstellen |
| `database/.../AppRepository.kt`, `daos/BolusDao.kt` | Abfrage für den Collector | B | — | wiederherstellen |
| `core/interfaces/.../notifications/Notification.kt` | **Eigene Kennung `FUSE_LEDGER_HOLD = 96`.** Rein additiv (eine Konstante). Ein geteilter Slot wäre hier der Fehler, nicht die Sparsamkeit: eine Meldung, die sich den Platz teilt, wird von der nächsten überschrieben — so ging der Wirkungs-Wächter im Juli unter. Der Hold ist der Fall, in dem FUSE **nichts mehr abgibt** | B | `FuseScreenModelTest.kt` *(Anzeige; die Meldung selbst ist ungedeckt, s. §7)* | Konstante wiederherstellen. Beim Merge auf **ID-Kollision** achten: Upstream vergibt hier fortlaufend, 96 kann belegt sein — dann eine freie nehmen, der Wert selbst trägt keine Bedeutung |

---

## 5. Was in diesem Zeitfenster liegt, aber **nicht** FUSE ist

Die folgenden Änderungen sind nach dem ersten `fuse/`-Commit entstanden und tauchen im
Diff auf, gehören aber zu **anderen Fork-Merkmalen**. Sie haben ihren eigenen Lebenszyklus
und dürfen bei einem FUSE-Rückbau **nicht** mit entfernt werden.

| Stelle | Gehört zu | Testnachweis |
|---|---|---|
| `plugins/source/UkfQ1.kt`, `XdripSourcePlugin.kt` | **Q1-Filter** — kausaler 1-min-UKF, seit 05.08.2026 der produktive Loop-Wert. Vollständig unabhängig von FUSE | `UkfQ1Test.kt` |
| `tools/nacht_bilanz_prod.py` | Auswertungsskript, kein Anwendungscode | — |

---

## 6. Vorgehen beim nächsten AAPS-Merge

1. **Zuerst die Sperrprüfung.** `tools/check_fuse_pump_isolation.sh` muss grün sein. Ist
   sie es nicht, hat jemand die Architekturauflage verletzt — vor allem anderen klären.
   Bei einem flachen Klon vorher `git fetch --unshallow`, sonst läuft die Prüfung ins
   Leere und ist die falsche Sorte grün.
2. **Registrierung (§2) zuerst** wiederherstellen und **bauen**. Ohne sie kompiliert
   nichts, und alle weiteren Fehler wären Folgefehler.
3. **Kernänderungen (§4) einzeln** wiederherstellen, jede mit ihrem Test. Diese vier
   Stellen zuerst prüfen, nicht zuletzt — sie tragen keinen FUSE im Namen und werden sonst
   vergessen.
4. **Die vier Testklassen aus §4 laufen lassen.** Sie sind die Merge-Kontrolle:

   ```bash
   ./gradlew :implementation:testFullDebugUnitTest :fuse:core:test :fuse:plugin:testFullDebugUnitTest :plugins:main:testFullDebugUnitTest
   ```

5. **`FusePumpGateTest` gesondert ansehen.** Der Test `alles ausserhalb der
   Erlaubnisliste ist gesperrt` läuft über **alle** `PumpType`-Werte, auch neue aus dem
   Merge. Bringt Upstream einen neuen Pumpentyp mit — auch einen neuen `MEDTRUM_*` —
   fällt er automatisch in die Sperre, ohne dass jemand daran denken muss. Das ist die
   Eigenschaft, die den Riegel merge-fest macht; wird der Test je gelockert, ist sie weg.
6. **Anzeige (§3) zuletzt.** Bei Konflikt in der Overview: weglassen, Rest bauen,
   nachziehen. Kein Grund, einen Merge daran aufzuhalten.

---

## 7. Offene Punkte dieser Matrix

| Punkt | Was fehlt |
|---|---|
| `NsIncomingDataProcessor` hat keinen Test | Die Plausibilitätsgrenzen (endlich, > 0, ≤ 60 U) sind ungedeckt. Ein Upstream-Refactoring könnte sie stillschweigend entfernen |
| Vier Anbindungsstellen ohne Test | `MainApp:262`, `OpenAPSAutoISFPlugin`, `AppRepository`, `BolusDao` — fällt eine weg, hört der Collector still auf zu beobachten, ohne dass etwas rot wird |
| ~~Kein automatischer Wächter für §0~~ | **Erledigt.** `tools/check_fuse_pump_isolation.sh` + `.github/workflows/fuse-architecture-ci.yml`, läuft bei Push und PR. Gegengeprüft: der Wächter meldet gegen den echten Baum grün und schlägt nachweislich an, wenn man ihn auf ein tatsächlich geändertes Verzeichnis richtet |
| Beobachtbarkeit (§3b) hat keinen eigenen Wächter | Dass `realPump`, `publicationGate` und die offene Transporthaftung im Export **stehen**, hält heute `FuseStateExportTest`. Dass sie bei Realpump **stimmen**, hält nichts — das kann erst ein Gerätelauf |

---

## 8. Regeln für neue FUSE-Arbeit

1. FUSE-Code gehört nach `fuse/core` (reines JVM, keine AAPS-Abhängigkeit) und
   `fuse/plugin` (AAPS-Anbindung).
2. Keine Änderung in `pump/**`. Keine Ausnahme.
3. Keine duplizierte Queue-, PumpSync- oder Treatment-Logik. AAPS bleibt alleiniger
   Eigentümer von Transport, Wiederholungen und Treatment-Erzeugung; FUSE besitzt
   **Berechnung und konservative Haftung**.
4. Jede unvermeidliche Kernänderung wird als **eigener, pumpenunabhängiger Commit** mit
   eigenem Test und eigener Begründung geschnitten — nie in einen FUSE-Commit
   hineingemischt. Nur so bleibt sie einzeln portierbar und einzeln als Upstream-PR
   abtrennbar.
5. Neue Einträge in dieser Matrix gehören in denselben Commit wie die Änderung, die sie
   beschreiben.

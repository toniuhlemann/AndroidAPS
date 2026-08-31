package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.fuse.core.util.Sha
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.fuse.plugin.ledger.EpisodeBudgets
import app.aaps.fuse.core.observer.Health
import kotlin.math.max
import kotlin.math.min
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.fuse.core.ledger.NotSentProof
import app.aaps.fuse.core.ledger.QueueRejectReason
import app.aaps.fuse.plugin.ledger.LedgerPublicationGate
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.predictor.PredictorReason
import app.aaps.fuse.core.predictor.PredictorOutcome
import app.aaps.fuse.core.predictor.TrajectoryCore
import app.aaps.fuse.core.controller.EvidenceStock
import app.aaps.fuse.core.controller.DescentRecoveryLatch
import app.aaps.fuse.core.controller.DescentDeferredCarry
import app.aaps.fuse.core.controller.MealFoundation
import app.aaps.fuse.core.controller.LivenessChannel
import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.OnsetChannel
import app.aaps.fuse.core.controller.PositiveCorrectionRearm
import app.aaps.fuse.core.controller.TurnResponseShadow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.io.File

/**
 * L4 (Gegenproben-Audit 09.08.2026): DIE NICHT-REFINANZIERUNG ALS
 * ENTSCHEIDUNGSVERTRAG.
 *
 * Der Ledger verhindert zuverlaessig eine ZWEITE Zeile fuer denselben
 * Vorschlag - das ist getestet. Was nicht getestet war, ist die Verdrahtung
 * dahinter: ob die offene Transportmenge im naechsten Zyklus tatsaechlich den
 * Spielraum verkleinert. `- transportModelledU` steht an fuenf Stellen in
 * [FuseCycleRunner] und war ersatzlos entfernbar, ohne dass ein einziger Test
 * rot wurde. Genau dieser Term traegt die Nicht-Refinanzierung.
 *
 * Deshalb prueft dieser Test AUSGAENGE, nicht Textvorkommen: derselbe Runner,
 * dieselbe Rohreihe, derselbe Takt - einziger Unterschied ist eine offene
 * Zeile im Ledger. Aendert sich die Dosis nicht, ist die Verdrahtung tot.
 *
 * Der Pruefstand ist der aus [CycleIobValidityTest]; nichts an der
 * Entscheidungskette ist nachgebaut.
 */
class TransportWiringTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var ads: AutosensDataStore
    @Mock lateinit var insulinProfileFunction: ProfileFunction
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var insulin: Insulin
    private lateinit var ledger: FuseLedgerAdapter
    private lateinit var runner: FuseCycleRunner

    /** Der in "diesem Prozess" beobachtete Markerdruck - im Rig steuerbar. */
    private var markerPress = 0L

    private var clock = 0L

    /**
     * DER MESSTAKT DES RIGS [ms]. War fest 60_000 - und genau darin lag ein
     * Fehler unentdeckt: bei 61 oder 62 Sekunden erreicht ein
     * Fuenf-Minuten-Fenster seine eigene Laenge nicht mehr, und ein Plateau
     * blieb dauerhaft unbestimmbar (Toni 28.08.). Ein Rig, das nur den
     * glatten Takt kennt, kann so etwas nicht finden.
     */
    private var taktMs = 60_000L
    private val start = 1_700_000_000_000L / 60_000L * 60_000L

    /**
     * Testschalter, um den SCHWANZ-Kanal abzuschalten.
     *
     * Die offene Transportmenge erreicht die Entscheidung ueber DREI Wege:
     * die Headroom-Terme (`- transportModelledU`, fuenf Stellen), die
     * Schwanzhaftung (`TailLiability.sumOf(transport.map { .. })`) und die
     * Prognose (`KernelPendingInsulin`). Ein Test, der nur "die Dosis faellt"
     * zeigt, beweist deshalb NICHT, dass die Headroom-Terme leben - genau
     * dieser Irrtum ist beim ersten Anlauf aufgefallen, als der Rot-Nachweis
     * mit entfernten Termen trotzdem gruen blieb.
     *
     * Mit abgeschaltetem Schwanz bleibt der Headroom-Kanal als tragender uebrig.
     */
    private var tailGuard = true

    /**
     * ENGES Insulinbudget fuer den isolierten Test.
     *
     * Im Standard-Rig ist `maxIob` 8,0 U und das IOB 0 - der Spielraum ist also
     * rund 8 U gegenueber Dosen von 0,15 U und bindet NIE. Genau daran ist der
     * zweite Anlauf dieses Tests gescheitert: die fuenf Headroom-Terme zu
     * entfernen aenderte nichts, weil sie in dieser Konfiguration gar nicht
     * zum Tragen kommen. Der Kanal ist erst beobachtbar, wenn der Spielraum
     * die bindende Grenze ist.
     */
    private var maxIobU = 8.0

    /** Pro Zyklus veraenderbar, damit ein Evidenzbestand zuerst ohne
     * Aktuation versiegelt und anschliessend gegen eine echte Mengengrenze
     * geprueft werden kann. */
    private var maxSmbU = 0.3

    /** Hoehe der flachen Rohreihe - niedrig heisst "kein Bedarf". */
    private var flach = 180.0

    /** Minute (ab `start`), ab der die Bahn abknickt. null = durchgehend
     *  linear, also das bisherige Verhalten. */
    /** Die Mahlzeitenhuelle [U]. Als Variable, weil die Plateau-Form eine
     *  realistische Huelle braucht: mit 1,2 U ist das gemeinsame Budget
     *  schon in Phase A erschoepft (der Korrekturkanal ist NICHT an die
     *  Huelle gebunden), und Phase B faende nur noch BUDGET_EXHAUSTED vor.
     *  Der Default haelt das bisherige Verhalten aller anderen Tests. */
    private var primeHuelleU = 1.2

    private var knickAbMin: Int? = null

    /** ZWEITER Knick - stetig wie der erste. Fuer Verlaeufe Fall->Erholung->
     *  neuer Fall (Punkt-6-Replayfall 7). Muss NACH `knickAbMin` liegen. */
    private var knick2AbMin: Int? = null
    private var steigungNachKnick2 = 0.0

    /** Punkt-6-Hebel: Schalter, gepinnter Horizont, gepinnte Frist. */
    private var aufschubAn = false
    private var aufschubHorizontMin = 60.0
    private var aufschubFristMin = 120

    /** Liveness-Kanal-Hebel: Schalter, Kanaldeckel [%], Re-Arm-Sperre [min]. */
    private var livenessAn = false

    /** Masterschalter der Prognose-Shadows (Default AN wie in Produktion). */
    private var forecastShadowAn = true
    private var mealPowerMin = 120
    private var mealArmZyklen = 3
    // CENTRAL-only-Labor: die Profilwerte starten OFFEN (20/20/1/1) und
    // MEAL-Schwelle 160, damit die historischen Rigs ihre Lagen behalten -
    // Tests der Politik setzen enge Werte ausdruecklich. Die ECHTEN
    // Produkt-Defaults (3/7/0,2/0,35/110/1) prueft der Default-Test.
    private var mealBgMin: Double = 160.0
    private var corrExpLimit: Double = 20.0
    private var mealExpLimit: Double = 20.0
    private var corrRatioCapZ: Double = 1.0
    private var mealRatioCapZ: Double = 1.0
    /** null = Migration: der Wert folgt dem alten Globalhebel. */
    private var zeroLatchAn = false
    private var zeroLatchRuheZyklen = 20
    private var zeroLatchRuheAbstand = 30.0
    private var livenessBgMin = 160.0

    /** Nachtschwelle des Kanals; null = nie gesetzt -> folgt der Tagesschwelle. */
    private var livenessBgMinNacht: Double? = null

    /** Nachtfenster-Hebel (Default wie bisher fest verdrahtet). */
    private var nachtStartMin = 1380
    private var nachtEndeMin = 480
    private var livenessReArmMin = 10

    /** Steigung NACH dem Knick [mg/dl/min]. */
    private var steigungNachKnick = 0.0

    /** Steigung der Rohreihe [mg/dl je Minute]. 0 = flach wie bisher. Fuer den
     *  Mahlzeitenfall braucht es einen echten Anstieg, sonst gibt es keinen
     *  Antrieb und die Bremsbahn wird nie die bindende. */
    private var steigungProMin = 0.0

    /** Bedingte Bahn im Schwanz. Der Auffang-Stub liefert fuer alle
     *  BooleanKeys `false` - ohne eigenen Schalter waere sie in JEDEM Test
     *  dieser Datei aus, auch in dem, der sie pruefen soll. */
    private var conditionalTail = false

    /** Quantil der Antriebs-Untergrenze. 50 = Band AUS (dann ist die untere
     *  Bahn die Mittelbahn, und es gibt keinen Zwischenraum, in den eine
     *  Hebung passt). */
    private var quantilePct = 50
    private var theilSenFensterMin = 18
    /** Rebound-Fensterdauer [min] - Replay-Hebel fuer die Matrix 26.08. */
    private var reboundFensterMin = 45

    /** Frist des Rebound-Sonderrechts [min]. Default 0 = KEIN Sonderrecht -
     *  das entspricht dem bisherigen (unmockten) Rig-Verhalten aller
     *  Alt-Tests; Produktion traegt 120 (FuseIntKey-Default). Nur die
     *  P1-v45-Rigs stellen die Frist scharf. */
    private var reboundOverrideMaxMin = 0

    /**
     * DIE RUHEPARAMETER AUS DER AUFGEZEICHNETEN POLITIK (Befund Toni 28.08.).
     *
     * Hier klaffte ein Loch, das eine ganze Abnahme wertlos gemacht hat: der
     * Trail trug `calmRecoveryEnabled=true` und `calmTreatment=CALM_BATCH`,
     * `politikAnwenden` uebernahm beides NICHT, und `neuerRunner` setzt
     * `Params.OFF` als Default. Der Replay lief also mit ausgeschaltetem
     * Pruefgegenstand und meldete `ruheDenial=DISABLED` - waehrend das
     * Geraet den Modus scharf hatte.
     *
     * Eine Blindprobe findet das NICHT: sie vergleicht zwei Arme
     * gegeneinander und ist gegen einen GEMEINSAMEN Konfigurationsfehler
     * blind. Dagegen hilft nur, die WIRKSAMEN Parameter auszugeben und
     * gegen die Gerätepolitik zu pruefen - s. `ruheParameterPruefen`.
     */
    private var ruheAusPolitik: app.aaps.fuse.core.controller.UpfrontRecovery.Params? = null

    /** Der Marker autorisiert Insulin bei gemessenem Tief. */
    private var markerAuthorized = false

    /** Das Mahlzeitenfundament - im Test steuerbar, produktiv per Default aus. */
    private var fundamentAn = false
    private var fundamentAnteil = 0.75
    private var fundamentEndeMin = 60

    /** Phase-A-Sofortanteil (iLet, v28). 0.0 = heutiges Verhalten. */
    private var upfrontAnteil = 0.0

    /** Insulinaktivitaet je Punkt. 0 heisst: der Bolus-Deckungs-Abschlag ist
     *  null, und damit ist die Bremsbahn-Untergrenze IHR EIGENES Mittel -
     *  auch dort passt dann keine Hebung hinein. */
    /** Bolus-IOB [U] fuer die Ueberdeckungsprobe; null = 0. */
    private var bolusIobU: Double? = null

    private var aktivitaet = 0.0

    /**
     * Zeitstempel eines Mahlzeiten-Markers, 0 = keiner.
     *
     * DIE REIHENFOLGE HAT SICH AM 11.08. UMGEDREHT, und der Hinweis hier war
     * bis dahin richtig, ist es jetzt aber nicht mehr:
     *
     *   frueher:  MealMarkerStamp (kodiert, ts*10+Stufe) hatte VORRANG
     *   heute:    MealMarkerArmedTs hat Vorrang, der Stamp ist nur noch
     *             Altbestand-Ruecktausch fuer armedTs == 0
     *
     * Der alte Fehler des ersten Prime-Anlaufs - beide Schluessel auf dieselbe
     * Zeit setzen, der Stamp-Zweig gewinnt und teilt durch 10, das
     * Mahlzeitenfenster liegt Jahrzehnte zurueck - kann so nicht mehr
     * auftreten. Der neue Stolperstein ist der umgekehrte: wer NUR den Stamp
     * setzt und armedTs stehen laesst, prueft nichts, weil der Stamp-Zweig nie
     * genommen wird. Deshalb weiterhin: Zeit ueber ArmedTs, Stamp auf 0.
     */
    /**
     * SETZEN IST DRUECKEN - wie in `FusePlugin.toggleMealMarker`: armen setzt
     * die Prozess-Beobachtung, zuruecknehmen loescht sie. Nur so bildet das
     * Rig einen echten Knopfdruck ab.
     *
     * Wer den Fall "Marker aus einem FRUEHEREN Prozess" braucht, setzt
     * danach [markerPress] von Hand auf 0.
     */
    private var markerAt: Long
        get() = markerAtIntern
        set(v) {
            markerAtIntern = v
            markerPress = v
        }
    private var markerAtIntern = 0L

    /** Nacht-Totband des Rigs - default aus wie bisher; die Totband-Tests
     *  schalten es scharf. */
    private var nightDeadband = false

    /** Schaltet das Nachtband in einem Replay-Lauf AUS - auch gegen die
     *  aufgezeichnete Politik. Fuer Ein-Variablen-Messungen, wenn das
     *  Nachtband dieselben Zyklen nullt wie der zu messende Riegel. */
    private var nightDeadbandAus = false

    /**
     * ERZWUNGENE PREDICTOR-ABLEHNUNG, `null` = echter Predictor.
     *
     * Der einzige Weg, die POSITIVE Seite des predictorfreien Markerpfades
     * zu pruefen. Aus diesem Rig ist keine der zehn Ablehnungen organisch
     * ausloesbar: der Signal-Waechter faengt nicht-endliche Werte frueher,
     * die Aktivitaets- und Antriebsgrenzen sind in Produktion gar nicht
     * gesetzt, und das IOB-Array deckt den Horizont per Konstruktion.
     * Auf ein seltenes Live-Ereignis zu warten ist bei einem Insulinpfad
     * keine Testmethode.
     */
    private var predictReject: PredictorReason? = null

    /** iobTH in Prozent von maxIob. Bei 100 sind beide Grenzen IDENTISCH -
     *  dann deckt ein Test die zwei Abzuege nur gemeinsam. 50 laesst iobTH
     *  allein binden, 200 den maxIob. */
    private var iobThPct = 100

    /** CGM-Luecke im Rohpuffer: Minuten [von, bis) seit `start` OHNE
     *  Messwerte. Der Hebel fuer die stabile Signalepoche - eine Luecke
     *  > 3 min ist ein ECHTER Bruch, die wandernde Fensterkante keiner. */
    private var lueckeVonMin: Int? = null
    private var lueckeBisMin: Int? = null

    private fun series(untilTs: Long): List<GV> =
        rohSerie?.let { serie ->
            return serie.asSequence().filter { it.first <= untilTs }.map { (ts, v) ->
                GV(
                    timestamp = ts, value = v, raw = v, noise = 0.0,
                    sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT
                )
            }.toList()
        } ?: generateSequence(start) { it + taktMs }
            .takeWhile { it <= untilTs }
            .filter { ts ->
                val von = lueckeVonMin ?: return@filter true
                val bis = lueckeBisMin ?: return@filter true
                val min = (ts - start) / 60_000.0
                min < von || min >= bis
            }
            .map { ts ->
                // STETIG GEKNICKTE BAHN (Toni 19.08.). Bis `knickAbMin` gilt
                // `steigungProMin`, danach `steigungNachKnick` - der Wert am
                // Knick ist derselbe, es entsteht also KEIN Sprung, den der
                // Regler als Artefakt lesen wuerde.
                //
                // WOZU: die drei bisherigen Formen bringen den normalen Pfad
                // nie zur Ruhe, deshalb bleibt fuer das Fundament nie eine
                // Luecke. Eine Bahn, die erst steigt und dann plateaut, laesst
                // den Regler von SELBST aufhoeren zu fordern - genau die Lage,
                // fuer die Phase B gebaut ist. Nichts wird kuenstlich genullt.
                val min = (ts - start) / 60_000.0
                val k = knickAbMin
                val k2 = knick2AbMin
                val v = when {
                    k == null || min <= k -> flach + steigungProMin * min
                    k2 == null || min <= k2 -> flach + steigungProMin * k + steigungNachKnick * (min - k)
                    else -> flach + steigungProMin * k + steigungNachKnick * (k2 - k) +
                        steigungNachKnick2 * (min - k2)
                }
                GV(
                    timestamp = ts, value = v, raw = v, noise = 0.0,
                    sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT
                )
            }
            .toList()

    private fun iob(atTs: Long) = IobTotal(roundUp(atTs)).also {
        // BOLUS-IOB = iob - basaliob. Das Rig stellte beide auf 0, damit war
        // eine Bolus-Ueberdeckung nie darstellbar - der Riegel gegen
        // gemessenes Abwaertsrisiko haette hier nie greifen koennen.
        val karte = iobProTs?.let { k ->
            val unter = k.floorEntry(atTs)
            val ueber = k.ceilingEntry(atTs)
            val nah = listOfNotNull(unter, ueber).minByOrNull { e -> kotlin.math.abs(e.key - atTs) }
            nah?.takeIf { e -> kotlin.math.abs(e.key - atTs) <= 90_000L }?.value
        }
        val gesamt = karte?.first ?: bolusIobU ?: 0.0
        // DIE BOLUS-IOB IST NICHT DIE GESAMT-IOB (Rig-Befund 25.08. spaet).
        // `basaliob = 0` machte aus jeder negativen Gesamt-IOB eine negative
        // BOLUS-IOB - und die bricht als Integritaetsbefund den ganzen
        // Zyklus ab. Am 25.08. abends waren das 23 Zyklen im
        // Phase-A-Fenster: Gesamt-IOB -0,33 bis -0,22 (Basal
        // zurueckgehalten), Bolus-IOB am Geraet aber +0,10. Traegt der
        // Trail die Bolus-IOB, wird `basaliob` so gesetzt, dass
        // `iob - basaliob` genau sie ergibt.
        val bolusAusTrail = bolusIobProTs?.let { k ->
            val unter = k.floorEntry(atTs)
            val ueber = k.ceilingEntry(atTs)
            val nah = listOfNotNull(unter, ueber).minByOrNull { e -> kotlin.math.abs(e.key - atTs) }
            nah?.takeIf { e -> kotlin.math.abs(e.key - atTs) <= 90_000L }?.value
        }
        // KEINE SENTINELS IM NORMALEN HARNESS (Toni 25.08. spaet).
        //
        // Hier standen kurzzeitig NaN fuer Nicht-Anker-Punkte. Das war in
        // zweifacher Hinsicht falsch: die Bedingung traf Vergangenheit UND
        // Zukunft, und die abgefragten Punkte sind ueberwiegend BAHNPUNKTE
        // der Prognose (+127 bis +131 min). Der Prädiktor rechnet dort und
        // lehnte folgerichtig mit NON_FINITE_INPUT ab - 350 von 373 Zyklen
        // brachen ab, der Replay war unbrauchbar.
        //
        // Die Tripwire hat damit ihren Zweck erfuellt und ist beendet: sie
        // hat den Verbraucher gefunden. Der richtige Ersatz ist keine
        // Vergiftung, sondern die AS-OF-MODELLRECHNUNG - IOB und Aktivitaet
        // fuer BELIEBIGE Abfragezeitpunkte aus den bis `computeTs`
        // pumpenbestaetigten Behandlungen. Bis die steht, gilt wieder das
        // bisherige Verhalten; es ist ungenau, aber endlich und benannt.
        it.iob = gesamt
        // basalIOB = totalIOB - exportiertes bolusIOB. So sind BEIDE
        // Groessen zugleich geraetetreu (Toni 25.08. spaet).
        //
        // OHNE das Feld bleibt es beim alten `basaliob = 0` - und damit
        // beim alten Fehler, dass eine negative Gesamt-IOB als negative
        // Bolus-IOB durchschlaegt. Das ist hier nicht heimlich: solche
        // Zyklen werden gezaehlt, und das Aequivalenztor verwirft einen
        // Lauf, der davon welche im Bewertungsfenster hat. Ein stiller
        // Ersatzwert waere die schlechtere Wahl - er saehe wie eine
        // Messung aus.
        if (bolusAusTrail == null) {
            // ANKER heisst: die Abfrage gilt dem Zeitpunkt dieses Zyklus.
            // 90 s Toleranz, dieselbe wie bei der Zuordnung oben.
            if (kotlin.math.abs(atTs - clock) <= 90_000L) {
                bolusIobFehltAnker++
                bolusIobAnkerFehltJetzt = true
            } else bolusIobFehltHistorisch++
        }
        // basalIOB = totalIOB - exportiertes bolusIOB, wo der Trail es
        // traegt (am Anker immer). Sonst 0 - das ist die alte, ungenaue
        // Naeherung und ausdruecklich eine Baustelle, kein Vertrag.
        it.basaliob = bolusAusTrail?.let { gesamt - it } ?: 0.0
        it.activity = karte?.second ?: aktivitaet; it.valid = iobGueltig
    }

    /** Ungueltige IOB-Daten -> keine Aktivitaet -> ACTIVITY_MISSING, das
     *  Signal ist nicht READY. Der Hebel fuer den Nullfall
     *  "ungesundes Signal"; Default haelt das bisherige Verhalten. */
    /** Der Guard-Boden [mg/dl]. Als Variable, damit der TAIL-Lauf den
     *  Guard ausdruecklich OEFFNEN kann - sonst binden beide Grenzen und
     *  die Ursache ist nicht zuordenbar. */
    private var guardBodenMgdl = 70.0

    private var iobGueltig = true

    /** PHASE-2-REPLAY (23.08.): aufgezeichnete Rohserie statt synthetischer
     *  Kurve. null = normale Rig-Kurve. */
    private var rohSerie: List<Pair<Long, Double>>? = null

    /** Schalter des Wiedereinstiegs nach Funkluecke (Default AUS wie am Geraet). */
    private var rejoinAn = false

    /** Beginn der laufenden Kalibrierung; -1 = nie kalibriert (Default des Keys). */
    private var kalibrierStart = -1L

    /** PHASE-2-REPLAY: (iobU, activityUPerMin) je Sample-Zeitstempel -
     *  naechster Eintrag binnen 90 s; null = normale Rig-Hebel. */
    private var iobProTs: java.util.TreeMap<Long, Pair<Double, Double>>? = null

    /**
     * FEHLENDE BOLUS-IOB, NACH ART DER ABFRAGE GETRENNT.
     *
     * Die Gesamtzahl allein sagt nichts: der Regler fragt je Zyklus
     * rund ein Dutzend HISTORISCHE Stuetzstellen ab (die q1-/BGI-Reihe),
     * und fuer die traegt der Trail naturgemaess keinen eigenen
     * Zyklus-Eintrag. Entscheidend ist der ANKER - an ihm haengen
     * Low-Threat, Abwaertsrisiko und Guard.
     */
    private var bolusIobFehltAnker = 0
    private var bolusIobFehltHistorisch = 0

    /** Fehlte die Bolus-IOB am Anker DIESES Zyklus? */
    private var bolusIobAnkerFehltJetzt = false

    /** Die BOLUS-IOB je Zeitpunkt aus dem Trail. */
    private var bolusIobProTs: java.util.TreeMap<Long, Double>? = null
    private var boluses: List<BS> = emptyList()

    private fun roundUp(t: Long) = if (t % 60_000L == 0L) t else (t / 60_000L + 1) * 60_000L

    @BeforeEach
    fun setup() {
        insulin = InsulinLyumjevPlugin(rh, insulinProfileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
        whenever(activePlugin.activeInsulin).thenReturn(insulin)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)
        testPumpPlugin.pumpDescription.bolusStep = 0.05

        whenever(dateUtil.now()).thenAnswer { clock }
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(profileFunction.getProfile(any())).thenReturn(validProfile)
        whenever(profileFunction.getProfileName()).thenReturn(TESTPROFILENAME)

        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.roundUpTime(any())).thenAnswer { inv -> roundUp(inv.getArgument(0)) }
        whenever(ads.getBgReadingsDataTableCopy()).thenAnswer { series(clock) }
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any()))
            .thenAnswer { inv -> iob(inv.getArgument(0)) }
        whenever(iobCobCalculator.calculateIobFromBolus()).thenAnswer { iob(clock) }

        whenever(constraintsChecker.getMaxIOBAllowed()).thenAnswer { ConstraintObject(maxIobU, aapsLogger) }
        whenever(commandQueue.bolusInQueue()).thenReturn(false)
        whenever(commandQueue.isRunning(any())).thenReturn(false)

        whenever(persistenceLayer.getLastTherapyRecordUpToNow(any())).thenReturn(null)
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenReturn(null)
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenAnswer { inv ->
            val from = inv.getArgument<Long>(0)
            val to = inv.getArgument<Long>(1)
            boluses.filter { it.timestamp in from..to }
        }
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenReturn(null)

        stubPolicy()
        neuerRunner(FuseLedgerAdapter())
    }

    private fun neuerRunner(l: FuseLedgerAdapter, evidenz: EvidenceStock.Config = EvidenceStock.Config(), fensterMs: Long? = null, trendRegel: String? = null, wiedereinstieg: app.aaps.fuse.core.signal.RejoinPolicy = app.aaps.fuse.core.signal.RejoinPolicy.OFF, ruheParams: app.aaps.fuse.core.controller.UpfrontRecovery.Params = app.aaps.fuse.core.controller.UpfrontRecovery.Params.OFF, gapPolitik: app.aaps.fuse.core.signal.GapPolicy = app.aaps.fuse.core.signal.GapPolicy.PRODUCTION, reifePolitik: app.aaps.fuse.core.signal.MaturityPolicy = app.aaps.fuse.core.signal.MaturityPolicy.PRODUCTION) {
        ledger = l
        runner = FuseCycleRunner(
            iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
            preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "test-epoch", { markerPress },
            evidenceConfig = evidenz,
            theilSenWindowMsOverride = fensterMs,
            trendRuleOverride = trendRegel,
            gapPolicy = gapPolitik,
            maturityPolicy = reifePolitik,
            rejoinPolicy = wiedereinstieg,
            upfrontRecoveryParams = ruheParams,
            predict = { input ->
                predictReject
                    ?.let { PredictorOutcome.Rejected(it, "erzwungen") }
                    ?: TrajectoryCore.predict(input)
            },
        )
    }

    private fun stubPolicy() {
        // AUFFANG ZUERST. Mockito laesst den ZULETZT passenden Stub gewinnen -
        // stand er am Ende, ueberschrieb er jeden spezifischen FuseBooleanKey
        // und lieferte still `false`. Genau daran scheiterte der Prime-Pfad:
        // PrimeReleaseEnabled war trotz ausdruecklicher Stubbung aus
        // ("prime=DISABLED"), ohne dass irgendetwas rot wurde.
        whenever(preferences.get(anyOrNull<BooleanKey>())).thenReturn(false)
        whenever(preferences.get(FuseDoubleKey.SmbRatio)).thenReturn(0.15)
        whenever(preferences.get(FuseDoubleKey.SmbRatioRise)).thenReturn(0.35)
        whenever(preferences.get(DoubleKey.ApsSmbMaxIob)).thenAnswer { maxIobU }
        whenever(preferences.get(FuseDoubleKey.RiseRampLowR)).thenAnswer { riseRampLowRWert }
        whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenReturn(2.0)
        whenever(preferences.get(FuseDoubleKey.MaxSmbU)).thenAnswer { maxSmbU }
        whenever(preferences.get(FuseDoubleKey.GuardFloorMgdl)).thenAnswer { guardBodenMgdl }
        // DIESE BEIDEN FEHLTEN. Ohne sie liefert der Mock 0.0, und das
        // Nahhorizont-Fenster der Low-Pruefung war damit NULL - jede
        // Bodennaehe galt als "zu weit weg". Ein Riegel, der auf diesen
        // Wert schaut, konnte im Rig nie greifen; gemerkt habe ich es nur,
        // weil die Vorbedingung des Tests darauf bestand.
        whenever(preferences.get(FuseDoubleKey.LowGateHorizonMin)).thenReturn(120.0)
        whenever(preferences.get(FuseDoubleKey.PositiveDescentHorizonMin)).thenReturn(30.0)
        whenever(preferences.get(FuseDoubleKey.LowGateMinBenefitMgdl)).thenReturn(5.0)
        whenever(preferences.get(FuseIntKey.IobThPercent)).thenAnswer { iobThPct }
        whenever(preferences.get(FuseIntKey.ReleaseHorizonMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.LiabilityHorizonMin)).thenReturn(120)
        whenever(preferences.get(FuseIntKey.DriveTauMin)).thenReturn(60)
        whenever(preferences.get(FuseBooleanKey.DeferredPrimeEnabled)).thenAnswer { aufschubAn }
        whenever(preferences.get(FuseDoubleKey.MarkerPrimeDescentHorizonMin)).thenAnswer { aufschubHorizontMin }
        whenever(preferences.get(FuseIntKey.DeferredPrimeEndMin)).thenAnswer { aufschubFristMin }
        whenever(preferences.get(FuseBooleanKey.LivenessChannelEnabled)).thenAnswer { livenessAn }
        whenever(preferences.get(FuseBooleanKey.ForecastShadowCollectionEnabled)).thenAnswer { forecastShadowAn }
        whenever(preferences.get(FuseIntKey.LivenessMealPowerMin)).thenAnswer { mealPowerMin }
        whenever(preferences.get(FuseIntKey.MealArmCycles)).thenAnswer { mealArmZyklen }
        whenever(preferences.get(FuseDoubleKey.LivenessBgMinMealMgdl)).thenAnswer { mealBgMin }
        whenever(preferences.getIfExists(FuseDoubleKey.LivenessBgMinMealMgdl)).thenAnswer { mealBgMin }
        whenever(preferences.get(FuseDoubleKey.CorrectionExposureLimitU)).thenAnswer { corrExpLimit }
        whenever(preferences.get(FuseDoubleKey.MealExposureLimitU)).thenAnswer { mealExpLimit }
        whenever(preferences.get(FuseDoubleKey.CorrectionDemandRatioCap)).thenAnswer { corrRatioCapZ }
        whenever(preferences.get(FuseDoubleKey.MealDemandRatioCap)).thenAnswer { mealRatioCapZ }
        whenever(preferences.get(FuseBooleanKey.ZeroLatchEnabled)).thenAnswer { zeroLatchAn }
        whenever(preferences.get(FuseIntKey.ZeroLatchCalmExitMin)).thenAnswer { zeroLatchRuheZyklen }
        whenever(preferences.get(FuseDoubleKey.ZeroLatchCalmDistanceMgdl)).thenAnswer { zeroLatchRuheAbstand }
        whenever(preferences.get(FuseDoubleKey.LivenessBgMinDayMgdl)).thenAnswer { livenessBgMin }
        whenever(preferences.get(FuseDoubleKey.LivenessBgMinNightMgdl)).thenAnswer { livenessBgMinNacht ?: livenessBgMin }
        whenever(preferences.getIfExists(FuseDoubleKey.LivenessBgMinNightMgdl)).thenAnswer { livenessBgMinNacht }
        whenever(preferences.get(FuseIntKey.LivenessReArmMin)).thenAnswer { livenessReArmMin }
        whenever(preferences.get(FuseIntKey.AbsorptionCreditWindowMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.MarkerBoostMaxMin)).thenReturn(45)
        whenever(preferences.get(FuseIntKey.NightStartMin)).thenAnswer { nachtStartMin }
        whenever(preferences.get(FuseIntKey.NightEndMin)).thenAnswer { nachtEndeMin }
        whenever(preferences.get(FuseDoubleKey.NightDeadbandMgdl)).thenReturn(45.0)
        whenever(preferences.get(FuseBooleanKey.NightDeadbandEnabled)).thenAnswer { nightDeadband }
        whenever(preferences.get(FuseDoubleKey.ReboundDeadbandMgdl)).thenReturn(25.0)
        whenever(preferences.get(FuseBooleanKey.ReboundDeadbandEnabled)).thenReturn(true)
        whenever(preferences.get(FuseIntKey.DriveLowerQuantilePct)).thenAnswer { quantilePct }
        whenever(preferences.get(FuseIntKey.TheilSenWindowMin)).thenAnswer { theilSenFensterMin }
        whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenAnswer { reboundFensterMin }
        whenever(preferences.get(FuseIntKey.EvidenceReboundOverrideMaxMin)).thenAnswer { reboundOverrideMaxMin }
        whenever(preferences.get(FuseBooleanKey.TailGuardEnabled)).thenAnswer { tailGuard }
        whenever(preferences.get(FuseBooleanKey.ConditionalTailEnabled)).thenAnswer { conditionalTail }
        whenever(preferences.get(FuseBooleanKey.MarkerAuthorisesRelease)).thenAnswer { markerAuthorized }
        whenever(preferences.get(FuseBooleanKey.SignalRejoinEnabled)).thenAnswer { rejoinAn }
        whenever(preferences.get(FuseDoubleKey.TailFloorMgdl)).thenReturn(70.0)
        whenever(preferences.get(FuseDoubleKey.TailRecoveryU)).thenReturn(0.0)
        whenever(preferences.get(FuseBooleanKey.FastRestraintEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.BolusShareLambda)).thenReturn(1.0)
        whenever(preferences.get(FuseBooleanKey.OnsetChannelEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.OnsetEnvelopeU)).thenReturn(1.5)
        whenever(preferences.get(FuseBooleanKey.PrimeReleaseEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.PrimeEnvelopeU)).thenAnswer { primeHuelleU }
        whenever(preferences.get(FuseBooleanKey.MealFoundationEnabled)).thenAnswer { fundamentAn }
        whenever(preferences.get(FuseDoubleKey.MealFoundationPhaseAShare)).thenAnswer { fundamentAnteil }
        whenever(preferences.get(FuseDoubleKey.MealFoundationPhaseAUpfrontShare)).thenAnswer { upfrontAnteil }
        whenever(preferences.get(FuseBooleanKey.CorrectionReversalGuardEnabled)).thenAnswer { reversalAn }
        whenever(preferences.get(FuseDoubleKey.ReversalFallUkf)).thenReturn(2.0)
        whenever(preferences.get(FuseIntKey.ReversalLookbackMin)).thenReturn(20)
        whenever(preferences.get(FuseDoubleKey.ReversalReboundUkf)).thenReturn(1.0)
        whenever(preferences.get(FuseIntKey.ReversalConfirmCycles)).thenAnswer { reversalConfirmWert }
        whenever(preferences.get(FuseBooleanKey.PositiveCorrectionRearmEnabled)).thenAnswer { rearmAn }
        whenever(preferences.get(FuseIntKey.RearmHoldMin)).thenReturn(5)
        whenever(preferences.get(FuseIntKey.RearmConfirmCycles)).thenReturn(2)
        whenever(preferences.get(FuseDoubleKey.RearmUpUkf)).thenAnswer { rearmUpUkfWert }
        whenever(preferences.get(FuseIntKey.MealFoundationEndMin)).thenAnswer { fundamentEndeMin }
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenAnswer { kalibrierStart }
        whenever(preferences.get(FuseLongKey.MealMarkerStamp)).thenReturn(0L)
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenAnswer { markerAt }
    }

    private fun cycle(): FuseCycleRunner.Outcome {
        clock += taktMs
        return runner.run(false, testPumpe())
    }

    /**
     * DIE TESTPUMPE MIT OFFENEM GATE.
     *
     * `FuseActivePump.gate` hat den Default BLOCKED_UNKNOWN_PUMP - ohne
     * ausdrueckliches Gate ist im Rig also `actuatedU` immer 0, und KEINE
     * Buchung findet statt. Genau daran ist der erste Anlauf des
     * Fallback-Tests gescheitert (und ein frueherer Ruecknahme-Test hatte
     * denselben Stolperstein bereits umgangen, statt ihn zu beheben).
     *
     * ALLOWED ist hier korrekt und kein Trick: virtualPump = true ergibt in
     * FusePumpGate.decide genau dieses Verdikt.
     */
    /** Hebel fuer ein GESPERRTES Pumpen-Gate (z.B. Sofortanteil-Test 9:
     *  armiert, aber nichts aktuiert). Default offen wie bisher. */
    private var pumpeBelegt = false

    /** Korrekturpfad-Riegel (v30). Default AUS wie am Geraet. */
    private var reversalAn = false
    private var rearmAn = false

    /** Die RAMPEN-UNTERKANTE. Sie ist zugleich die Schwelle des
     *  Onset-Kanals und der Mahlzeitenfenster-Kinematik und entscheidet
     *  damit, wann der autoritative Kontext von Korrektur auf Mahlzeit
     *  kippt. Das Rig fuhr bisher 0,5; Toni faehrt am Geraet 1,5 - die
     *  Pflichtfall-Tests brauchen den Geraetewert, sonst liegt die
     *  Kontextgrenze eine Kurvenphase zu frueh. */
    private var riseRampLowRWert = 0.5

    /** Aufwaerts-Schwelle des Freigabe-Nachlaufs. Als Hebel, weil eine
     *  Erholung, die die Rampen-Unterkante erreicht, das
     *  Mahlzeitenfenster kinematisch oeffnet und dem Riegel den
     *  autoritativen Korrekturkontext nimmt - ein Nachlauf-Test braucht
     *  deshalb eine Bestaetigungsschwelle UNTER dieser Kante. */
    private var rearmUpUkfWert = 0.3

    /** Bestaetigungszyklen des V-Riegels. Als Hebel, damit ein Test den
     *  Riegel absichtlich LANG scharf halten kann - nur dann laesst sich
     *  pruefen, was ihn ausser der r-Bestaetigung noch beendet. */
    private var reversalConfirmWert = 2

    private fun testPumpe() = FuseActivePump(
        "GENERIC_AAPS", virtualPump = true, bolusStepU = 0.05, basalStepUPerH = 0.05,
        gate = if (pumpeBelegt) FusePumpGate.Result(FusePumpGate.Verdict.BLOCKED_REAL_PUMP, "belegt")
        else FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "TestPump"),
    )

    /** Bis zur ersten positiven Dosis fahren - der Observer braucht Vorlauf. */
    private fun driveUntilDose(maxCycles: Int = 60): FuseCycleRunner.Outcome {
        clock = start
        repeat(maxCycles) {
            val o = cycle()
            if (o.decision.smbU > 0.0) return o
        }
        throw AssertionError("keine positive Dosis in $maxCycles Zyklen")
    }


    /**
     * DER PRIME-LIFT EINZELN (Codex-Vorgabe 10.08., Verbraucher 3, Zeile 959).
     *
     * ZWEI FEHLER DER VORFASSUNG, beide im Test:
     *
     * 1. Sie nahm das MAXIMUM ueber 90 Zyklen, ohne die berechneten Dosen zu
     *    verbuchen. Die Prime-Huelle blieb dadurch scheinbar unverbraucht und
     *    der Floor stieg gegen Fensterende bis 0,30 U - ein Testartefakt, kein
     *    realistischer Prime-Verlauf. Jetzt zaehlt der ERSTE aktive
     *    Prime-Zyklus.
     * 2. Sie lief mit maxIob 8,0 U. Bei einem Prime-Floor von hoechstens
     *    0,30 U kann ein Transportterm dort niemals binden - deshalb blieb die
     *    isolierte Mutation gruen. Die Huelle zu VERKLEINERN haette es noch
     *    unwahrscheinlicher gemacht; richtig ist, das BUDGET zu verengen.
     *
     * Die Rechnung, an der dieser Test haengt:
     *   maxIob 0,10 U, iobTH 200 % = 0,20 U (also nicht bindend)
     *   ohne Haftung: Spielraum 0,10 U -> Prime-Floor 0,05 U geht hinaus
     *   0,06 U offen: Rest 0,04 U      -> kein Prime-Lift mehr
     */
    @Test
    fun `der Prime-Lift finanziert unterwegs befindliches Insulin nicht erneut`(@TempDir dir: File) {
        flach = 100.0
        markerAt = start + 5 * 60_000L
        tailGuard = false
        maxIobU = 0.10
        iobThPct = 200

        /** Der ERSTE Zyklus mit aktivem Prime-Plan - nicht das Maximum. */
        fun ersterPrimeZyklus(u: Double, unter: File): FuseCycleRunner.Outcome {
            val l = FuseLedgerAdapter().also { it.loadOnce(unter.also(File::mkdirs), "test-epoch", start) }
            if (u > 0.0) l.onPublished("vorlauf", u, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
            neuerRunner(l)
            clock = start
            repeat(90) {
                val o = cycle()
                if (o.prime?.active == true) return o
            }
            throw AssertionError("kein Zyklus mit aktivem Prime-Plan")
        }

        // KONTROLLE: der Lift ist wirklich der dosierende Pfad, und er dosiert
        // Der Prime-Lift ist die bindende Endgrenze. Die Kandidatensuche
        // liefert hier ein Ergebnis; NO_DEMAND wird NICHT behauptet.
        val ohne = ersterPrimeZyklus(0.0, File(dir, "ohne"))
        assertThat(ohne.prime?.active).isTrue()
        assertThat(ohne.decision.smbU).isGreaterThan(0.0)
        // AUSDRUECKLICH statt behauptet: der Lift ist der bindende Pfad.
        //
        // NACHGEPRUEFT UND KORRIGIERT: eine Vorfassung behauptete hier
        // zusaetzlich `candidate.reject == NO_DEMAND`. Gemessen ist
        // `reject == null` - der Kandidat lehnt in dieser Lage NICHT ab,
        // sondern liefert ein Ergebnis, das der Lift anschliessend anhebt.
        // Auch mit tieferer Reihe (95 mg/dl) bleibt es dabei. Die Behauptung
        // steht deshalb nicht mehr im Test; entscheidend fuer den Term an
        // Zeile 959 ist ohnehin, DASS der Lift die bindende Grenze ist - und
        // das steht hier.
        assertThat(ohne.decision.bindingLimit).isEqualTo("primeRelease")

        // BEHANDLUNG: 0,06 U offene Haftung lassen 0,04 U Rest - der Floor von
        // 0,05 U passt nicht mehr hinein.
        val mit = ersterPrimeZyklus(0.06, File(dir, "mit"))
        // Der Plan ist AKTIV: es kappt der Lift, nicht schon die Clearance.
        assertThat(mit.prime?.active).isTrue()
        assertThat(mit.decision.smbU).isLessThan(ohne.decision.smbU)
        // Der Floor von 0,05 U passt nicht mehr in den Rest von 0,04 U.
        assertThat(mit.decision.smbU).isLessThan(0.05)
        assertThat(mit.decision.smbU).isAtMost(maxIobU - 0.06 + 1e-9)
    }



    // ---- Der Kern: die Verdrahtung ist lebendig --------------------------

    /**
     * DIE DOSIS FAELLT MONOTON MIT DER OFFENEN HAFTUNG.
     *
     * Ein einzelner Vergleich taugt hier nicht, und der erste Anlauf dieses
     * Tests ist genau daran gescheitert: bei kleiner offener Haftung bindet
     * weiterhin `smbRatio`, der Ledger-Term ist dann rechnerisch vorhanden,
     * aber nicht die bindende Grenze - die Dosis bleibt gleich, OBWOHL die
     * Verdrahtung lebt. Das ist korrektes Verhalten, kein Befund.
     *
     * Der Sweep umgeht die Frage, ab welchem Wert der Term bindet: er
     * verlangt nur, dass die Dosis NIE STEIGT und irgendwo ECHT FAELLT.
     *
     * WAS DIESER TEST NICHT LEISTET - ausdruecklich, weil gemessen: er bleibt
     * GRUEN, wenn man die fuenf `- transportModelledU` entfernt. Die offene
     * Menge erreicht die Entscheidung ueber drei Kanaele (Headroom, Schwanz,
     * Prognose), und Schwanz plus Prognose allein genuegen fuer "faellt". Er
     * ist damit der GESAMTVERTRAG - dass die Haftung ueberhaupt wirkt -, nicht
     * der Nachweis fuer die Headroom-Terme. Den fuehrt der Test darunter.
     *
     * Eine fruehere Fassung dieses Kommentars behauptete das Gegenteil. Sie
     * war durch den eigenen Rot-Versuch bereits widerlegt.
     */
    @Test
    fun `die Dosis faellt monoton mit der offenen Haftung`(@TempDir dir: File) {
        val stufen = listOf(0.0, 0.5, 1.0, 2.0, 4.0, 8.0)
        val dosen = stufen.mapIndexed { i, u ->
            val l = FuseLedgerAdapter().also { it.loadOnce(File(dir, "stufe$i").also(File::mkdirs), "test-epoch", start) }
            if (u > 0.0) l.onPublished("vorlauf", u, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
            neuerRunner(l)
            clock = start
            var best = 0.0
            repeat(60) { best = maxOf(best, cycle().decision.smbU) }
            u to best
        }

        assertThat(dosen.first().second).isGreaterThan(0.0)

        // (1) monoton fallend - eine hoehere Haftung darf nie MEHR erlauben
        dosen.zipWithNext { (uA, dA), (uB, dB) ->
            assertThat(dB).isAtMost(dA)
            uA to uB
        }
        // (2) und irgendwo faellt sie wirklich, sonst waere der Term tot
        assertThat(dosen.last().second).isLessThan(dosen.first().second)
    }

    /**
     * DIESELBE AUSSAGE, ABER AUF DEN HEADROOM-KANAL ISOLIERT.
     *
     * Der Test oben ist der Gesamtvertrag; er faellt aber auch dann nicht,
     * wenn NUR Schwanz oder Prognose wirken. Mit abgeschaltetem Schwanzwaechter
     * bleibt im Wesentlichen der Headroom uebrig - und genau der haengt an den
     * fuenf `- transportModelledU`.
     *
     * ROT-NACHWEIS: entfernt man die fuenf Terme (vier Subtraktionen plus die
     * Zuweisung an den Candidate-Lift), faellt dieser Test - der Test daneben
     * nicht.
     */
    /**
     * DIE SCHARFE FORM: DIE DOSIS PASST IN DEN RESTSPIELRAUM.
     *
     * Der Sweep oben ist der Gesamtvertrag - er faellt aber auch dann nicht,
     * wenn nur Prognose oder Schwanz wirken. Gemessen (Diagnoselauf 09.08.):
     * ohne die fuenf Headroom-Terme sinkt die Dosis bei 0,15 U offener Haftung
     * nur von 0,25 auf 0,20, MIT ihnen auf 0,10 - und die bindende Grenze
     * heisst dann `candidate:iobThHeadroom` statt `smbRatio|subStep`.
     *
     * Deshalb prueft dieser Test nicht "faellt", sondern die Eigenschaft
     * selbst: **was schon unterwegs ist, darf nicht noch einmal ausgegeben
     * werden.** Die neue Dosis muss in den Rest des Budgets passen. Genau das
     * ist die Nicht-Refinanzierung, und genau das leisten die fuenf Terme.
     */
    @Test
    fun `die Dosis passt in den Spielraum, der nach der offenen Haftung bleibt`(@TempDir dir: File) {
        tailGuard = false
        maxIobU = 0.25
        val haftung = 0.15

        fun laufMit(u: Double, unter: File): Double {
            val l = FuseLedgerAdapter().also { it.loadOnce(unter.also(File::mkdirs), "test-epoch", start) }
            if (u > 0.0) l.onPublished("vorlauf", u, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
            neuerRunner(l)
            clock = start
            var best = 0.0
            repeat(60) { best = maxOf(best, cycle().decision.smbU) }
            return best
        }

        val ohne = laufMit(0.0, File(dir, "ohne"))
        assertThat(ohne).isGreaterThan(maxIobU - haftung)

        val mit = laufMit(haftung, File(dir, "mit"))
        assertThat(mit).isAtMost(maxIobU - haftung + 1e-9)
    }



    /**
     * DIE SUB-STEP-KAPPE EINZELN (Codex-Re-Review, Verbraucher 4 und 5).
     *
     * Der Test oben schuetzt den Candidate-Pfad. Die Sub-Step-Freigabe ist ein
     * ZWEITER Verbraucher derselben Groesse und liegt hinter einem eigenen
     * Riegel: ein angesammelter Ratio-Rest darf nur dann als voller
     * Pumpenschritt hinausgehen, wenn die ENDSUMME in alle Mengengrenzen
     * passt - und in diese Grenzen geht die offene Haftung ein.
     *
     * Die Schwelle ist gemessen, nicht geschaetzt (Diagnoselauf 09.08., Rig mit
     * maxIob 0,25 und Basis 0,15):
     *
     *   haftung 0,00..0,05 -> Dosis 0,20, Grenze "smbRatio|subStep"  (freigegeben)
     *   haftung 0,08       -> Dosis 0,15, Grenze "smbRatio"          (verworfen)
     *
     * Genau dazwischen kippt `lifted.smbU + bolusStep > otherCapsU`:
     * 0,15 + 0,05 gegen 0,25 - 0,08 = 0,17. Ohne die beiden Sub-Step-Abzuege
     * waere `otherCapsU` = 0,25, der Schritt ginge hinaus, und die Dosis
     * betruege 0,20 - also eine Refinanzierung von bereits unterwegs
     * befindlichem Insulin.
     */
    @Test
    fun `die Sub-Step-Freigabe faellt weg, wenn die Haftung den Schritt nicht mehr traegt`(@TempDir dir: File) {
        tailGuard = false
        maxIobU = 0.25

        fun lauf(u: Double, unter: File): FuseCycleRunner.Outcome? {
            val l = FuseLedgerAdapter().also { it.loadOnce(unter.also(File::mkdirs), "test-epoch", start) }
            if (u > 0.0) l.onPublished("vorlauf", u, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
            neuerRunner(l)
            clock = start
            var best: FuseCycleRunner.Outcome? = null
            repeat(60) { val o = cycle(); if (o.decision.smbU > (best?.decision?.smbU ?: 0.0)) best = o }
            return best
        }

        // KONTROLLE: ohne Haftung wird der Sub-Step wirklich freigegeben -
        // sonst pruefte der Test unten gar nichts.
        val ohne = lauf(0.0, File(dir, "ohne"))!!
        assertThat(ohne.decision.bindingLimit).contains("subStep")

        // BEHANDLUNG: 0,08 U offene Haftung - der Schritt passt nicht mehr.
        val mit = lauf(0.08, File(dir, "mit"))!!
        assertThat(mit.decision.bindingLimit).doesNotContain("subStep")
        assertThat(mit.decision.smbU).isAtMost(maxIobU - 0.08 + 1e-9)
    }

    /**
     * Und die Grenzform: eine offene Haftung, die den ganzen Spielraum frisst,
     * laesst nichts mehr uebrig.
     */
    @Test
    fun `eine grosse offene Haftung unterdrueckt die Dosis vollstaendig`(@TempDir dir: File) {
        val l = FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", start) }
        l.onPublished("vorlauf", 8.0, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
        neuerRunner(l)

        clock = start
        var maxDose = 0.0
        repeat(60) { maxDose = maxOf(maxDose, cycle().decision.smbU) }
        assertThat(maxDose).isEqualTo(0.0)
    }

    /**
     * Ueber den PROZESSNEUSTART: die Haftung wirkt nach dem Laden genauso.
     * Sonst waere die Nicht-Refinanzierung eine reine Laufzeiteigenschaft -
     * und der Neustart ist der Moment, in dem eine unbestaetigte Dosis am
     * ehesten verlorenginge.
     */
    @Test
    fun `die Haftung wirkt auch nach dem Prozessneustart`(@TempDir dir: File) {
        val a = FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", start) }
        a.onPublished("vorlauf", 8.0, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
        assertThat(a.persistVerified(dir)).isTrue()

        // ZWEITE Instanz - frisch von Platte, nichts aus dem Speicher.
        val b = FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch-2", start) }
        assertThat(b.view().transportCommitmentU).isGreaterThan(0.0)
        neuerRunner(b)

        clock = start
        var maxDose = 0.0
        repeat(60) { maxDose = maxOf(maxDose, cycle().decision.smbU) }
        assertThat(maxDose).isEqualTo(0.0)
    }

    // ---- iobTH und maxIOB EINZELN (Codex-Vorgabe 10.08.) -----------------

    /**
     * Bei `iobTH = 100 %` sind `iobThU` und `maxIobU` IDENTISCH
     * (`IobThreshold.fromPercent(pct, maxIob)`). Ein Test deckt die beiden
     * Abzuege dann nur GEMEINSAM: entfernt man einen, haelt der andere die
     * Grenze und der Test bleibt gruen.
     *
     * Die zulaessige Spanne bis 300 % trennt sie sauber:
     *   maxIob 0,50 / iobTH  50 % = 0,25  ->  iobTH bindet allein
     *   maxIob 0,25 / iobTH 200 % = 0,50  ->  maxIOB bindet allein
     *
     * In beiden Faellen ist die wirksame Kappe 0,25 U. Mit 0,08 U offener
     * Haftung bleiben 0,17 U: die Basis von 0,15 U passt, der zusaetzliche
     * Pumpenschritt von 0,05 U nicht mehr.
     */
    private fun grenzeAllein(dir: File, maxIob: Double, pct: Int, name: String): Pair<FuseCycleRunner.Outcome, FuseCycleRunner.Outcome> {
        tailGuard = false
        maxIobU = maxIob
        iobThPct = pct

        fun lauf(u: Double, unter: File): FuseCycleRunner.Outcome {
            val l = FuseLedgerAdapter().also { it.loadOnce(unter.also(File::mkdirs), "test-epoch", start) }
            if (u > 0.0) l.onPublished("vorlauf", u, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
            neuerRunner(l)
            clock = start
            var best: FuseCycleRunner.Outcome? = null
            repeat(60) { val o = cycle(); if (o.decision.smbU > (best?.decision?.smbU ?: 0.0)) best = o }
            return checkNotNull(best) { "$name: kein Zyklus mit positiver Dosis" }
        }
        return lauf(0.0, File(dir, "$name-ohne")) to lauf(0.08, File(dir, "$name-mit"))
    }

    @Test
    fun `Candidate - iobTH bindet allein`(@TempDir dir: File) {
        val (ohne, mit) = grenzeAllein(dir, maxIob = 0.50, pct = 50, name = "c-iobth")
        assertThat(ohne.decision.smbU).isGreaterThan(0.0)
        assertThat(mit.decision.smbU).isAtMost(0.25 - 0.08 + 1e-9)
    }

    @Test
    fun `Candidate - maxIOB bindet allein`(@TempDir dir: File) {
        val (ohne, mit) = grenzeAllein(dir, maxIob = 0.25, pct = 200, name = "c-maxiob")
        assertThat(ohne.decision.smbU).isGreaterThan(0.0)
        assertThat(mit.decision.smbU).isAtMost(0.25 - 0.08 + 1e-9)
    }

    @Test
    fun `Sub-Step - iobTH bindet allein`(@TempDir dir: File) {
        val (ohne, mit) = grenzeAllein(dir, maxIob = 0.50, pct = 50, name = "s-iobth")
        assertThat(ohne.decision.bindingLimit).contains("subStep")
        assertThat(mit.decision.bindingLimit).doesNotContain("subStep")
    }

    @Test
    fun `Sub-Step - maxIOB bindet allein`(@TempDir dir: File) {
        val (ohne, mit) = grenzeAllein(dir, maxIob = 0.25, pct = 200, name = "s-maxiob")
        assertThat(ohne.decision.bindingLimit).contains("subStep")
        assertThat(mit.decision.bindingLimit).doesNotContain("subStep")
    }

    // ---- L5: die TBR-Quelle im NORMALEN Runner-Pfad ----------------------

    /**
     * Der erste VOLLSTAENDIG gerechnete Zyklus - also einer ohne Abbruch.
     *
     * In der Aufwaermphase traegt  noch den Abbruchgrund ("drive not
     * estimable"), nicht die TBR-Aussage. Wer darauf zusichert, prueft den
     * Vorlauf statt den Vertrag.
     */
    private fun ersterTbrZyklus(max: Int = 60): FuseCycleRunner.Outcome {
        repeat(max) {
            val o = cycle()
            if (o.abortReason == null && !o.reason.isNullOrBlank()) return o
        }
        throw AssertionError("kein vollstaendig gerechneter Zyklus")
    }

    private fun quelleMeldet(tb: TB?) =
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())) doReturn tb

    private fun laufendeTbr(rate: Double, typ: TB.Type = TB.Type.NORMAL) = TB(
        timestamp = start, duration = 30 * 60_000L,
        rate = rate, isAbsolute = true, type = typ,
    )

    /**
     * L5, Regel 1 im NORMALEN Pfad (`FuseCycleRunner:1140-1150`).
     *
     * EINE VORFASSUNG DIESES TESTS WAR WIRKUNGSLOS, und das ist der Grund fuer
     * die Ausfuehrlichkeit hier: sie hielt den Zyklus in einer Variablen fest,
     * die nie geprueft wurde, benutzte `return@repeat` als vermeintlichen
     * Abbruch (es ist ein `continue`) und sicherte am Ende nur
     * `abortReason == null` zu. Damit waere sie auch dann gruen geblieben,
     * wenn der Runner die Quelle zwar AUFRUFT, ihren Rueckgabewert aber
     * ignoriert - also genau im Fehlerfall, gegen den sie steht.
     *
     * Jetzt wird das BEOBACHTBARE Ergebnis zugesichert: reason und
     * TBR-Anforderung.
     */
    @Test
    fun `der Runner entscheidet aus dem gelesenen TBR-Zustand`(@TempDir dir: File) {
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", start) })
        clock = start

        // (1) Reale positive TBR ueber Profilbasal.
        quelleMeldet(laufendeTbr(2.50))
        val ersterLauf = ersterTbrZyklus()
        val reason1 = ersterLauf.reason
        assertThat(reason1).isNotEmpty()

        // (2) Quelle UNVERAENDERT -> dieselbe Aussage entsteht erneut. Ein
        //     gemerkter "habe ich schon"-Zustand wuerde hier abweichen.
        val zweiterLauf = ersterTbrZyklus()
        assertThat(zweiterLauf.reason).isEqualTo(reason1)

        // (3) Quelle LEER -> die Aussage aendert sich. Sie haengt also am
        //     gelesenen Zustand und nicht an der eigenen Vorgeschichte.
        quelleMeldet(null)
        val dritterLauf = ersterTbrZyklus()
        assertThat(dritterLauf.reason).isNotEqualTo(reason1)
    }

    /**
     * L5, Regel 5: FAKE_EXTENDED ist eine laufende, NICHT abbrechbare Abgabe.
     * FUSE greift nicht ein, sagt es aber. Ob zusaetzlich der SMB gesperrt
     * wird, haengt an `unsafe` - Naeheres weiter unten am Test.
     *
     * Der zweite Teil ist der eigentliche Vertrag: der Zustand darf NICHT
     * gespeichert bleiben. Verschwindet die Abgabe aus der Quelle, ist der
     * Read-Only-Hold weg.
     */
    @Test
    fun `FAKE_EXTENDED sperrt lesend und bleibt nicht gespeichert`(@TempDir dir: File) {
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", start) })
        clock = start

        quelleMeldet(laufendeTbr(2.50, TB.Type.FAKE_EXTENDED))
        val gesperrt = ersterTbrZyklus()
        assertThat(gesperrt.reason).contains("FAKE_EXTENDED_READ_ONLY")
        // KEIN Eingriff in die laufende Abgabe - das ist der Vertrag.
        assertThat(gesperrt.tbr).isNull()
        // Die SMB-Sperre haengt NICHT am FAKE_EXTENDED allein, sondern an
        // `unsafe`: sie greift, wenn FUSE senken WOLLTE und nicht kann
        // (TbrPolicy: "Kann FUSE die laufende Abgabe nicht stoppen, darf es
        // nicht gleichzeitig zusaetzliches Insulin geben"). In dieser Lage
        // will FUSE erhoehen, also wird nicht gesperrt - gemessen 0,15 U.
        // Eine pauschale Zusicherung `smbU == 0` waere hier schlicht falsch.

        // Quelle leer - der Zustand von eben war KEIN Gedaechtnis.
        quelleMeldet(null)
        val danach = ersterTbrZyklus()
        assertThat(danach.reason).doesNotContain("FAKE_EXTENDED_READ_ONLY")
    }

    // ---- INTEGRATION: die bedingte Bahn im GANZEN Zyklus ----------------

    /**
     * WARUM DIESER TEST EXISTIERT, obwohl `ConditionalDriveTest` gruen ist.
     *
     * Jener prueft die erzeugten Antriebsobjekte. Der Fehler sass beide Male
     * eine Ebene darueber - in der ZUSAMMENSETZUNG: der Schwanz rechnet gegen
     * `minSafetyHorizonLowerOf(haupt, bremse)`, und gehoben war nur eine der
     * beiden. Fuenf gruene Einheitentests standen neben einem wirkungslosen
     * Feature, und gefunden hat es eine laufende Mahlzeit.
     *
     * Hier laeuft deshalb der VOLLE Zyklus: Signal, Predictor, beide Bahnen,
     * Minimum, `tailLowerConditional`. Gefordert ist nicht "eine Bahn ist
     * hoeher", sondern "die KOMBINIERTE Kante steigt wirklich".
     */
    @Test
    fun `bedingte Bahn hebt die kombinierte Schwanzkante im ganzen Zyklus`() {
        flach = 110.0
        steigungProMin = 2.5          // echter Mahlzeitenanstieg
        markerAt = start + 2 * 60_000L
        conditionalTail = true
        // BEIDE BAHNEN BRAUCHEN SPIELRAUM, sonst prueft der Fall nichts -
        // und das ist keine Testkosmetik, sondern eine Bedingung der Sache:
        //
        //  Band AUS (q50)  -> untere Bahn IST die Mittelbahn, kein Zwischenraum
        //  Abschlag 0      -> Bremsbahn-Untergrenze IST ihr eigenes Mittel
        //
        // In beiden Faellen kann kein Kredit hineinpassen, und die bedingte
        // Bahn entsteht gar nicht erst. Der erste Anlauf dieses Tests lief
        // genau hinein und meldete "kein Kredit", obwohl der Kredit lief.
        quantilePct = 25
        aktivitaet = 0.004

        var mitHebung: FuseCycleRunner.Outcome? = null
        clock = start
        repeat(40) {
            val o = cycle()
            if (o.tailLowerConditionalMgdl != null) { mitHebung = o; return@repeat }
        }
        val o = mitHebung ?: throw AssertionError(
            "in 40 Zyklen wurde keine bedingte Bahn gebaut - lief kein Kredit?"
        )

        val u = o.tailLowerUnconditionalMgdl!!
        val c = o.tailLowerConditionalMgdl!!
        assertTrue(c > u, "die KOMBINIERTE Kante muss steigen: $u -> $c")

        // Und die Gegenprobe zum ersten Fehlschlag: es reicht NICHT, dass die
        // Hauptbahn gestiegen ist. Wenn die Bremsbahn die bindende ist, muss
        // AUCH sie gehoben worden sein - sonst haette sich am Minimum nichts
        // geaendert und `c > u` waere gar nicht erst wahr.
        val bremseUnbedingt = o.tailLowerRestraintUncondMgdl
        if (bremseUnbedingt != null && bremseUnbedingt <= o.tailLowerMainUncondMgdl!!) {
            assertTrue(
                o.tailLowerRestraintCondMgdl != null,
                "die Bremsbahn war die bindende und wurde nicht gehoben"
            )
        }
    }

    /** Ohne Kredit gibt es keine bedingte Bahn - und damit exakt das
     *  Verhalten von vorher. Ohne diesen Fall koennte die Hebung immer
     *  laufen und der Test oben trotzdem gruen sein. */
    @Test
    fun `ohne Marker bleibt die Schwanzkante unbedingt`() {
        flach = 110.0
        steigungProMin = 2.5
        markerAt = 0L                 // kein Marker
        conditionalTail = true

        clock = start
        repeat(25) {
            val o = cycle()
            assertTrue(
                o.tailLowerConditionalMgdl == null,
                "ohne Marker darf keine bedingte Bahn entstehen"
            )
        }
    }

    /** Und mit ausgeschaltetem Schalter ebenso - der Schalter muss wirken. */
    @Test
    fun `ausgeschaltet bleibt die Schwanzkante unbedingt`() {
        flach = 110.0
        steigungProMin = 2.5
        markerAt = start + 2 * 60_000L
        conditionalTail = false

        clock = start
        repeat(25) {
            assertTrue(cycle().tailLowerConditionalMgdl == null, "der Schalter wirkt nicht")
        }
    }

    // ---- DIE ZWEI RANDFAELLE ----------------------------------------------

    /**
     * RANDFALL 1, VERDRAHTET - die Haelfte, die kaputt war.
     *
     * Ist die Basisdosis groesser als der Markerboden, gibt der Lift
     * unveraendert zurueck (richtig, er soll nicht senken) - stempelte aber
     * auch nicht. Damit war `authCapU` null, und ein spaeteres Veto haette
     * BEIDES verworfen: Basis und Markerboden. Der Knopfdruck verlor seine
     * Wirkung gerade dadurch, dass FUSE ohnehin dosieren wollte.
     *
     * Hier im echten Zyklus: SMB 0,30 aus der Basis, daneben eine
     * Autorisierungsgrenze aus der Huelle. Vor dem Fix stand dort 0,0.
     *
     * WAS DIESER TEST NICHT ABDECKT, und das gehoert hierher statt in eine
     * Zusage: die zweite Haelfte - Veto verwirft die groessere Basis, der
     * Boden stellt GENAU `authCapU` her - ist im Rig nicht herstellbar. Beides
     * zugleich verlangt eine Bahn, die abtaucht (fuer das Veto) UND Bedarf
     * (fuer die groessere Basis); die Kandidatensuche prueft aber denselben
     * Guard und nullt die Basis dann schon vorher. Gemessen: bei BG 250
     * steigend liegt der Schwanz-Headroom bei +2,4 U, ein Veto entsteht nicht.
     * In Produktion bleibt der Fall ueber den Rest-Zaehler erreichbar.
     */
    @Test
    fun `bei groesserer Basis entsteht die Autorisierungsgrenze trotzdem`() {
        flach = 250.0
        steigungProMin = 2.0
        tailGuard = true
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        var treffer: FuseCycleRunner.Outcome? = null
        repeat(20) {
            val o = cycle()
            if (treffer == null && o.decision.smbU > 0.0 && o.decision.markerAuthorizedU > 0.0)
                treffer = o
        }
        val o = treffer ?: throw AssertionError(
            "der Aufbau muss eine Basisdosis MIT Autorisierungsgrenze erzeugen"
        )
        assertTrue(
            o.decision.smbU > o.decision.markerAuthorizedU + 1e-9,
            "der Aufbau braucht eine Basis GROESSER als den Markerboden: " +
                "${o.decision.smbU} vs ${o.decision.markerAuthorizedU}",
        )
        assertTrue(o.decision.markerAuthorizedU > 0.0, "und die Grenze muss trotzdem stehen")
    }
    /**
     * RANDFALL 2: ein verworfener EINHEITSKERN ist kein Guard-Urteil, sondern
     * ein Integritaetsbefund ueber das Insulinmodell - NON_FINITE_SAMPLE,
     * NON_LINEAR_MODEL, negative Aktivitaet, IOB ausserhalb des gueltigen
     * Bereichs. Der Einstellungstext sagt zu, dass unglaubwuerdige Messwerte
     * NICHT ueberstimmt werden; hier steht, dass der Code es auch tut.
     *
     * Der Hebel ist ein Insulinmodell, das NaN liefert - genau der Fall, den
     * UnitInsulinKernelBuilder mit NON_FINITE_SAMPLE ablehnt.
     */
    @Test
    fun `ein verworfener Einheitskern wird vom Marker nicht ueberstimmt`() {
        tailGuard = false
        flach = 105.0
        steigungProMin = -0.9
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        val kaputt = org.mockito.kotlin.mock<Insulin>()
        whenever(kaputt.id).thenReturn(insulin.id)
        whenever(kaputt.peak).thenReturn(45)
        whenever(kaputt.iobCalcForTreatment(any(), any(), any()))
            .thenAnswer { app.aaps.core.data.iob.Iob().apply { iobContrib = Double.NaN } }
        whenever(activePlugin.activeInsulin).thenReturn(kaputt)

        clock = start
        repeat(14) { i ->
            assertEquals(
                0.0, cycle().decision.smbU, 1e-9,
                "ein kaputtes Insulinmodell darf der Marker nicht ueberstimmen (Zyklus $i)",
            )
        }
    }

    // ---- DER LIVEFALL VOM 11.08., im RUNNER --------------------------------

    /**
     * DER HAUPTFALL EINER MAHLZEIT - und der Test, der genau hier zweimal
     * das Falsche behauptet hat.
     *
     * WAS AM GERAET STAND: BG 105, fallend (r = -0,888 mg/dl/min), Marker seit
     * 3 Minuten, Health READY, Ledger frei, Pumpen- und Publikationsgate
     * offen, iobTH und maxIOB je 8 U - also KEIN Mengendeckel. Ergebnis: 0 U.
     * Prime meldete CLEARANCE, die Entscheidung GUARD_FLOOR, der
     * Schwanz-Headroom war bei -0,41 U.
     *
     * WARUM ES 0 U WAREN: `safetyReasons` war leer, weil BG 105 kein
     * gemessenes Tief ist - und ich hatte das gemessene Tief zur
     * VORAUSSETZUNG der Autorisierung gemacht. Es war nur der Anlass, an dem
     * sie zuerst auffiel. Ein Marker bei normalem BG mit fallender Bahn ist
     * der Regelfall einer Mahlzeit, nicht der Randfall - und genau die
     * Frueh-Abgabe vor dem sichtbaren Anstieg ist der Zweck von FUSE.
     *
     * VORGESCHICHTE DIESES TESTS, weil sie zur Sache gehoert: an derselben
     * Stelle stand vorher das GEGENTEIL ("GUARD_FLOOR ohne gemessenes Tief
     * loest den Override NICHT aus"), zweimal als Placebo und einmal echt.
     * Der P0, den er bewachte (`all { it == LOW }` auf der leeren Menge), ist
     * gegenstandslos geworden: gefaehrlich war er, WEIL der damalige
     * Sonderzweig den Guard fuer die GANZE Menge aufhob. Heute begrenzt ein
     * mengenbeschraenkter Boden auf `markerAuthorizedU` - die Huelle, nicht
     * das Tief.
     *
     * DER AUFBAU stellt alle drei Widersprueche her und PRUEFT DAS AUCH:
     * CLEARANCE (Prime-Grund), GUARD_FLOOR (Bahn unter dem Boden) und ein
     * negativer Schwanz-Headroom - bei eingeschaltetem Schwanz-Waechter.
     */
    @Test
    fun `Marker bei normalem BG mit fallender Bahn gibt frei`() {
        flach = 105.0
        steigungProMin = -0.9          // fallend wie am Geraet
        aktivitaet = 0.02              // Bahn taucht unter den Guard-Boden
        tailGuard = true               // der Schwanz widerspricht MIT
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        // VORLAUF MIT AUSGESCHALTETER EINSTELLUNG - er stellt fest, dass die
        // Modellkette in dieser Lage wirklich widerspricht. Im scharfen Lauf ist
        // das nicht beobachtbar: mit Autorisierung wird das CLEARANCE-Tor
        // uebersprungen und meldet PRIME. Ohne diesen Vorlauf koennte der Test
        // gruen sein, weil FUSE bei BG 105 ohnehin dosiert.
        markerAuthorized = false
        clock = start
        var sahClearance = false
        var sahGuardFloor = false
        var sahSchwanzNein = false
        repeat(14) {
            val o = cycle()
            if (o.prime?.reason == "CLEARANCE") sahClearance = true
            if (o.decision.block == FuseController.Block.GUARD_FLOOR) sahGuardFloor = true
            // "GIBT KEIN BUDGET HER", nicht "< 0": bei einer Bahn unter dem
            // physiologischen Boden liefert TailLiability headroomU = -existing,
            // und bei IOB 0 ist das exakt 0,0 - sperrend, aber nicht negativ.
            // Genau diese Zahl hat den ersten Anlauf rot gemacht.
            if (o.decision.tail?.let { t -> t.usable && t.headroomU <= 0.0 } == true)
                sahSchwanzNein = true
            assertEquals(0.0, o.decision.smbU, 1e-9, "ohne Einstellung darf nichts hinausgehen")
        }
        assertTrue(sahClearance, "der Aufbau muss CLEARANCE erzeugen, sonst prueft er nichts")
        assertTrue(sahGuardFloor, "der Aufbau muss GUARD_FLOOR erzeugen")
        assertTrue(sahSchwanzNein, "der Aufbau muss einen sperrenden Schwanz-Headroom erzeugen")

        // UND JETZT SCHARF, alles andere gleich.
        markerAuthorized = true
        neuerRunner(FuseLedgerAdapter())
        clock = start
        var frei: FuseCycleRunner.Outcome? = null
        repeat(14) { if (frei == null) cycle().let { o -> if (o.decision.smbU > 0.0) frei = o } }
        val o = frei ?: throw AssertionError(
            "der Marker gibt bei normalem BG immer noch nichts frei - genau der Livefall"
        )

        // Der Aufbau ist wirklich der Livefall: KEIN gemessenes Tief.
        assertTrue(o.state?.safetyHold != true, "der Aufbau darf kein gemessenes Tief erzeugen")
        assertTrue(o.bgMgdl!! > 90.0, "BG muss im normalen Bereich liegen: ${o.bgMgdl}")

        // (3) Die Menge ist da und vollstaendig markerfinanziert.
        assertTrue(o.decision.markerAuthorizedU > 0.0, "typisierte Herkunft")
        assertEquals(
            o.decision.markerAuthorizedU, o.decision.smbU, 1e-9,
            "SMB muss genau die Autorisierungsgrenze sein, kein Zuschlag",
        )

        // (4) VERTRAGSAENDERUNG (Toni 17.08.): bis dahin verlangte dieser
        // Punkt ZERO_TEMP ("Der Schutz laeuft daneben weiter"). Am Geraet
        // hiess das: die Huelle gab vorne 0,15 U je Minute, die Null nahm
        // hinten 0,35 U Basal weg - netto 3,10 statt der autorisierten 3,5 U,
        // und das fehlende Insulin fehlte zeitversetzt im Resorptionsfenster.
        // Toni: "hier arbeiten 2 prinzipien gegeneinander."
        //
        // Am selben Abend erweitert auf JEDE Lage: die Tagesmessung ergab 677
        // von 1129 Zyklen mit laufender Null. Seither entsteht eine Null nur
        // noch aus dem LowThreatGate; hier ist es zu (kein gemessenes Tief -
        // Punkt 2 prueft das, und der Verlauf faellt zu schnell, als dass ein
        // Basalstopp noch etwas ausrichten koennte).
        assertEquals(
            FuseController.TbrAction.KEEP_CURRENT, o.decision.tbr,
            "Profilbasal ist das Fundament - die Modell-Null entsteht gar nicht erst",
        )
        assertTrue(o.decision.unsafeSituation, "die Lage bleibt als unsicher gemeldet (C8)")
        assertTrue(o.decision.basalFloorProtected, "und der Stempel muss den Translator erreichen (C7c)")
    }

    /** OHNE MARKER bleibt dieselbe Lage bei null - sonst wuerde der Test
     *  darueber nur zeigen, dass FUSE bei BG 105 ohnehin dosiert. */
    @Test
    fun `dieselbe Lage ohne Marker bleibt bei null`() {
        flach = 105.0
        steigungProMin = -0.9
        aktivitaet = 0.02
        tailGuard = true
        markerAt = 0L
        markerAuthorized = true

        clock = start
        repeat(14) { i ->
            assertEquals(0.0, cycle().decision.smbU, 1e-9, "ohne Marker keine Freigabe (Zyklus $i)")
        }
    }

    /** UND OHNE DIE EINSTELLUNG ebenso. Zwei getrennte Gegenproben, weil
     *  `manualMarkerAuthorized` ein UND aus beidem ist. */
    @Test
    fun `dieselbe Lage ohne die Einstellung bleibt bei null`() {
        flach = 105.0
        steigungProMin = -0.9
        aktivitaet = 0.02
        tailGuard = true
        markerAt = start + 2 * 60_000L
        markerAuthorized = false

        clock = start
        repeat(14) { i ->
            assertEquals(0.0, cycle().decision.smbU, 1e-9, "ohne Einstellung keine Freigabe (Zyklus $i)")
        }
    }

    /**
     * DIE TECHNISCHEN SPERREN BLEIBEN HART - in genau dieser Lage, die eben
     * noch freigegeben hat. Das ist die Gegenprobe dazu, dass die Erweiterung
     * NUR die Modellkette betrifft.
     */
    @Test
    fun `bei normalem BG nullen Pumpe Fault und FAKE_EXTENDED weiterhin`() {
        flach = 105.0
        steigungProMin = -0.9
        aktivitaet = 0.02
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        whenever(commandQueue.bolusInQueue()).thenReturn(true)
        clock = start
        repeat(12) { assertEquals(0.0, cycle().decision.smbU, 1e-9, "belegte Pumpe") }
        whenever(commandQueue.bolusInQueue()).thenReturn(false)

        neuerRunner(FuseLedgerAdapter())
        clock = start
        repeat(12) {
            clock += 60_000L
            assertEquals(0.0, runner.run(true, testPumpe()).decision.smbU, 1e-9, "Fault")
        }

        neuerRunner(FuseLedgerAdapter())
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenAnswer {
            TB(
                timestamp = start, duration = 30 * 60_000L, rate = 0.0,
                isAbsolute = true, type = TB.Type.FAKE_EXTENDED,
            )
        }
        clock = start
        repeat(12) { assertEquals(0.0, cycle().decision.smbU, 1e-9, "FAKE_EXTENDED") }
    }
    /**
     * Und die Gegenrichtung: bei echtem Tief meldet der Observer den Hold.
     * Ohne diesen Fall koennte der Test oben auch dann gruen sein, wenn der
     * Aufbau NIE ein Tief erzeugen kann.
     */
    @Test
    fun `bei gemessenem Tief meldet der Observer den Hold`() {
        flach = 62.0
        steigungProMin = 0.0
        markerAt = start + 2 * 60_000L

        clock = start
        var sah = false
        repeat(25) { if (cycle().state?.safetyHold == true) sah = true }
        assertTrue(sah, "bei BG 62 muss der Tiefschutz greifen")
    }

    // ---- Die Ruecknahme, VERDRAHTET --------------------------------------

    /**
     * `MarkerEpisodeTest` beweist die REGEL, nicht ihre Verdrahtung.
     *
     * Hier wird der Verbrauch VORGELADEN, der Marker zurueckgenommen und
     * erneut gesetzt - und geprueft, dass die Buchung stehenbleibt. Dafuer
     * muss das Pumpengate nichts erlauben: der Verbrauch kommt aus dem
     * vorgeladenen Zustand, nicht aus einer Abgabe. Genau daran war der erste
     * Anlauf gescheitert, der ihn ueber echte Abgaben erzeugen wollte - im Rig
     * ist das Gate zu, `actuatedU` bleibt 0 und es wird nie etwas gebucht.
     */
    @Test
    fun `Ruecknahme und erneutes Armen erhalten den Verbrauch`(@TempDir dir: File) {
        flach = 140.0
        steigungProMin = 0.0

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(6) { cycle() }

        // MONOTONIE STATT EINER ZAHL, und der Grund ist ein echter Vertrag:
        // gebucht wird JEDE im Marker-Fenster gelieferte Einheit, nicht nur die
        // aus dem Prime-Kanal (eine Huelle fuer beide Pfade). Seit das
        // Pumpen-Gate im Rig offen ist, waechst der Verbrauch also weiter - ein
        // fester Erwartungswert wuerde nur noch messen, wie viel nebenher
        // dosiert wurde. Die Behauptung dieses Tests ist eine andere: die
        // Ruecknahme gibt NICHTS ZURUECK, der Wert darf also nie SINKEN.
        l.episodes.primeSpentU = 0.20
        assertTrue(l.episodes.primeArmedTs > 0L, "die Episode muss stehen")

        markerAt = 0L
        repeat(3) { cycle() }
        val nachRuecknahme = l.episodes.primeSpentU
        assertTrue(
            nachRuecknahme >= 0.20 - 1e-9,
            "die Ruecknahme allein darf nichts loeschen: $nachRuecknahme",
        )

        markerAt = clock + 60_000L
        repeat(3) { cycle() }
        assertTrue(
            l.episodes.primeSpentU >= nachRuecknahme - 1e-9,
            "erneutes Armen im Fenster gibt die Huelle nicht zurueck: " +
                "$nachRuecknahme -> ${l.episodes.primeSpentU}",
        )
    }

    /** Und nach Ablauf des 90-min-Fensters ist es wirklich eine neue Mahlzeit. */
    @Test
    fun `nach Ablauf des Markerfensters beginnt die Buchung neu`(@TempDir dir: File) {
        flach = 140.0
        steigungProMin = 0.0

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(6) { cycle() }
        l.episodes.primeSpentU = 1.20      // erschoepft, s. Test darueber

        clock += (OnsetChannel.MARKER_WINDOW_MIN + 10) * 60_000L
        markerAt = clock + 60_000L
        // EIN Zyklus: die Ruecksetzung und hoechstens EINE frische Dosis. Mehr
        // Zyklen wuerden die Aussage verwaessern, weniger gaebe es nicht.
        cycle()

        assertTrue(
            l.episodes.primeSpentU < 0.5,
            "eine wirklich neue Mahlzeit bekommt ihre volle Huelle, " +
                "hoechstens um eine frische Dosis vermindert: ${l.episodes.primeSpentU}",
        )
    }

    // ---- DER PREDICTORFREIE MARKERPFAD, verdrahtet ------------------------

    /**
     * EINE NICHT UEBERSTIMMBARE ABLEHNUNG BEENDET DEN ZYKLUS - und sagt im
     * Grund, dass ein Fallback geprueft und verweigert wurde.
     *
     * WAS DIESER TEST WIRKLICH BEWEIST, und das ist mehr als es aussieht: der
     * Abbruch bei verworfener Bahn ist seit dem 11.08. AUFGESCHOBEN. Steht
     * `noFallback=REASON_NOT_OVERRIDABLE` im Grund, dann ist der Zyklus bis
     * hinter den Zustandsbau gelaufen, MarkerFallback wurde befragt, und erst
     * seine Antwort hat abgebrochen. Ein stehengebliebener alter Pfad haette
     * den Zusatz nicht.
     *
     * DER HEBEL, und er hat drei Anlaeufe gebraucht: aus diesem Rig ist fast
     * keine Predictor-Ablehnung erreichbar. Eine unendliche Aktivitaet faengt
     * der SIGNAL-Waechter frueher ab ("signal: activity not finite"), und die
     * Aktivitaets- und Antriebsgrenzen sind in Produktion gar nicht gesetzt
     * (PredictorBounds-Defaults sind null) - beide Gruende sind also weder hier
     * noch am Geraet ausloesbar. Uebrig bleibt eine absurde ISF: 5000 mg/dl/U
     * liegt ueber HardLimits.MAX_ISF und ergibt ISF_OUT_OF_BOUNDS.
     */
    @Test
    fun `eine nicht ueberstimmbare Ablehnung nennt den verweigerten Fallback`() {
        flach = 62.0
        steigungProMin = 0.0
        val kaputt = org.mockito.kotlin.spy(validProfile)
        org.mockito.kotlin.doReturn(5000.0).whenever(kaputt).getIsfMgdlTimeFromMidnight(org.mockito.kotlin.any())
        whenever(profileFunction.getProfile()).thenReturn(kaputt)
        whenever(profileFunction.getProfile(any())).thenReturn(kaputt)
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        var gesehen: String? = null
        repeat(12) {
            val o = cycle()
            if (o.abortReason?.contains("noFallback=") == true) gesehen = o.abortReason
            assertEquals(0.0, o.decision.smbU, 1e-9, "ein geschlossener Grund gibt nichts frei")
        }
        val r = gesehen ?: throw AssertionError("der aufgeschobene Abbruch wurde nie erreicht")
        assertTrue(
            r.contains("noFallback=REASON_NOT_OVERRIDABLE"),
            "der Grund muss den verweigerten Fallback benennen: $r",
        )
        assertTrue(r.contains("predictor:"), "und die urspruengliche Ursache: $r")
    }

    /**
     * DIE POSITIVE SEITE, im ECHTEN Runner - und sie verlangt SIEBEN Dinge
     * gleichzeitig.
     *
     * `MarkerFallbackTest` beweist die POLITIK, der Test darueber den
     * VERWEIGERTEN Fall. Dass `markerFallbackCycle` bei einem erlaubten
     * Grund wirklich Menge, TBR, Autorisierungsgrenze, Buchung und Export
     * zusammenfuehrt, beweist keiner von beiden - und genau diese Luecke
     * ("Regel bewiesen, Verdrahtung nicht") ist in dieser Reihe schon
     * zweimal aufgefallen.
     *
     * PENDING_MODEL_TOO_SHORT ist der Grund, der am Geraet ueberhaupt
     * vorkommen kann: ARRAY_TOO_SHORT sieht strukturell unerreichbar aus,
     * weil das IOB-Array mit Horizont + 30 min Marge gebaut wird.
     */
    @Test
    fun `der predictorfreie Markerpfad traegt einen Zyklus`(@TempDir dir: File) {
        tailGuard = false
        flach = 105.0
        steigungProMin = -0.9          // fallende Bahn statt gemessenem Tief
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        // Erst ein paar Zyklen MIT Bahn, damit der Observer sein Tief
        // ueberhaupt feststellt (Health READY braucht Vorlauf).
        clock = start
        repeat(6) { cycle() }

        predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT
        var frei: FuseCycleRunner.Outcome? = null
        repeat(10) { if (frei == null) cycle().let { o -> if (o.decision.smbU > 0.0) frei = o } }
        val o = frei ?: throw AssertionError("der predictorfreie Pfad hat nichts getragen")

        // (1) Es gab wirklich keine Bahn - sonst prueft der Test den Hauptpfad.
        assertTrue(o.predictorRejected, "der Zyklus muss ohne Bahn gelaufen sein")
        assertEquals("PENDING_MODEL_TOO_SHORT", o.predictorReason)
        assertTrue(o.markerFallbackUsed, "und ueber den Markerpfad")
        assertEquals(null, o.prediction, "keine Bahn im Export, auch keine leere")

        // (2) Menge, (3) Herkunft, (4) Deckel
        assertTrue(o.decision.smbU > 0.0, "es muss etwas herauskommen")
        assertTrue(o.decision.markerAuthorizedU > 0.0, "typisierte Herkunft")
        assertTrue(
            o.decision.smbU <= o.decision.markerAuthorizedU + 1e-9,
            "nur der autorisierte Anteil: ${o.decision.smbU}",
        )

        // (5) KEINE Modell-Null mehr (Toni 17.08.): eine Null entsteht nur
        // noch aus dem LowThreatGate, und das ist hier zu - der Aufbau
        // erzeugt seit dem 18.08. kein gemessenes Tief, sondern eine
        // fallende Bahn. Dieselbe Vertragsaenderung wie im Livefall-Test
        // oben, aus demselben Grund: Profilbasal ist das Fundament.
        assertEquals(
            FuseController.TbrAction.KEEP_CURRENT, o.decision.tbr,
            "ohne gemessenes Tief entsteht die Null gar nicht erst",
        )
        // KEIN SAFETY_ZERO mehr: der Aufbau erzeugt seit dem 18.08. kein
        // gemessenes Tief, und nur daraus entsteht der Schutz-Nullstrom.
        assertTrue(!o.reason.contains("SAFETY_ZERO"), "ohne Tief kein Nullstrom: ${o.reason}")
        assertTrue(o.reason.contains("MARKER_FALLBACK"), "und der Grund muss den Pfad nennen: ${o.reason}")

        // (6) Die Huelle ist BELASTET. Ohne das waere der Pfad ein zweiter
        // Geldbeutel fuer dieselbe Mahlzeit.
        assertTrue(l.episodes.primeSpentU > 0.0, "die Freigabe-Huelle muss belastet sein")

        // (7) Und die Belastung ist RESERVIERT, nicht endgueltig - das
        // Publikations-Gate im Plugin kann die Menge noch entfernen.
        val r = l.episodes.pendingReservation
            ?: throw AssertionError("ohne Reservierung kann das Publikations-Gate nichts zurueckgeben")
        assertEquals(o.decision.smbU, r.amountU, 1e-9, "die Reservierung muss die abgegebene Menge tragen")
        assertTrue(r.prime, "sie gehoert ins Marker-Fenster")
    }

    /**
     * DER LEDGER-HOLD IM HAUPTPFAD - Auditbefund P0-3 (16.08.2026).
     *
     * Das Gesamtaudit hat per ausgefuehrter Mutationsprobe belegt, dass
     * `LedgerHoldGate.apply` aus dem Hauptpfad (FuseCycleRunner.kt:1822)
     * ersatzlos entfernt werden kann, ohne dass EINER von 1322 Tests rot wird.
     * Der Mechanismus, der FUSE stoppt, wenn seine Buchfuehrung blind ist, war
     * auf Unit-Ebene geprueft (`LedgerHoldGateTest`) und auf Verdrahtungsebene
     * ungeprueft - genau die Fehlerklasse, die am 15.08. schon einmal
     * zugeschlagen hat (`evidenceCreditActive` war 81 Zyklen nicht verdrahtet).
     *
     * WARUM DIESER TEST MIT MARKER LAEUFT, der vorhandene Hold-Test dagegen
     * nicht scharf ist: jener erzeugt den Hold in einer Kreditlage. Im Hold
     * ist der Kredit selbst null (`persistedStateKnown=false`), also waere die
     * Menge dort AUCH OHNE das Gate null - die zu pruefende Bedingung entsteht
     * im Aufbau nie. Mit aktivem Marker hebt `MarkerFloor` die Menge auf den
     * autorisierten Anteil, und nur das Gate kann sie danach noch nullen.
     * Schritt (1) unten haelt genau das fest.
     */
    @Test
    fun `ein haltender Ledger nullt die Menge im Hauptpfad`(@TempDir dir: File) {
        tailGuard = false
        flach = 105.0
        steigungProMin = -0.9          // fallende Bahn statt gemessenem Tief
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start

        // (1) POSITIVKONTROLLE: ohne Hold traegt der Pfad wirklich etwas.
        //     Ohne diesen Schritt pruefte der Test eine Null, die schon vorher
        //     eine war.
        var ohneHold: FuseCycleRunner.Outcome? = null
        repeat(16) { if (ohneHold == null) cycle().let { o -> if (o.decision.smbU > 0.0) ohneHold = o } }
        val frei = ohneHold ?: throw AssertionError("Aufbau traegt nichts - der Test wuerde nichts pruefen")
        assertFalse(frei.predictorRejected, "dieser Test gehoert dem HAUPTpfad")
        assertTrue(frei.decision.markerAuthorizedU > 0.0, "die Menge muss markerfinanziert sein")

        // (2) ECHTER Hold - kein Mock: der Sentinel-Name wird von einem
        //     Verzeichnis besetzt, der Persist scheitert nachweislich.
        File(dir, app.aaps.fuse.plugin.ledger.FuseLedgerStore.SENTINEL_NAME).delete()
        assertTrue(File(dir, app.aaps.fuse.plugin.ledger.FuseLedgerStore.SENTINEL_NAME).mkdirs())
        assertFalse(l.persistVerified(dir))
        assertTrue(l.view().hold, "Vorbedingung: der Ledger muss halten")

        // (3) DIE ZUSICHERUNG. Der Marker autorisiert weiter - das Gate sitzt
        //     danach und nullt trotzdem.
        val imHold = cycle()
        assertEquals(0.0, imHold.decision.smbU, 1e-9, "im Hold darf nichts fliessen: ${imHold.decision}")
        assertEquals(FuseController.Block.LEDGER_HOLD, imHold.decision.block, "und der Grund muss der Hold sein")
        assertEquals("ledgerHold", imHold.decision.bindingLimit)
    }

    /**
     * DERSELBE HOLD IM FALLBACKPFAD (FuseCycleRunner.kt:2172) - die zweite
     * gruen gebliebene Mutationsprobe des Audits.
     *
     * Der predictorfreie Markerpfad ist die Stelle, an der FUSE OHNE Bahn
     * dosiert. Dass ausgerechnet dort der Hold ungeprueft war, ist die
     * unangenehmere Haelfte von P0-3: hier gibt es keine Sicherheitsbahn, die
     * ersatzweise bremsen koennte.
     */
    @Test
    fun `ein haltender Ledger nullt die Menge auch im Fallbackpfad`(@TempDir dir: File) {
        tailGuard = false
        flach = 105.0
        steigungProMin = -0.9          // fallende Bahn statt gemessenem Tief
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start
        repeat(6) { cycle() }

        // Ab hier laeuft der Zyklus ohne Bahn - der Fallbackpfad.
        predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT

        // (1) POSITIVKONTROLLE auf DIESEM Pfad.
        var ohneHold: FuseCycleRunner.Outcome? = null
        repeat(10) { if (ohneHold == null) cycle().let { o -> if (o.decision.smbU > 0.0) ohneHold = o } }
        val frei = ohneHold ?: throw AssertionError("der Fallbackpfad traegt nichts - der Test pruefte nichts")
        assertTrue(frei.predictorRejected, "der Zyklus muss ohne Bahn gelaufen sein")
        assertTrue(frei.markerFallbackUsed, "und ueber den Markerpfad")

        // (2) ECHTER Hold.
        File(dir, app.aaps.fuse.plugin.ledger.FuseLedgerStore.SENTINEL_NAME).delete()
        assertTrue(File(dir, app.aaps.fuse.plugin.ledger.FuseLedgerStore.SENTINEL_NAME).mkdirs())
        assertFalse(l.persistVerified(dir))
        assertTrue(l.view().hold, "Vorbedingung: der Ledger muss halten")

        // (3) DIE ZUSICHERUNG - auch ohne Bahn.
        val imHold = cycle()
        assertTrue(imHold.markerFallbackUsed, "der Test muss weiter auf dem Fallbackpfad laufen")
        assertEquals(0.0, imHold.decision.smbU, 1e-9, "im Hold darf auch ohne Bahn nichts fliessen")
        assertEquals(FuseController.Block.LEDGER_HOLD, imHold.decision.block)
        assertEquals("ledgerHold", imHold.decision.bindingLimit)
    }

    /**
     * DER TRANSPORTABZUG AUF DEM FALLBACKPFAD - dritte gruen gebliebene
     * Mutationsprobe des Gesamtaudits (16.08.2026): der Fallback-Lift laesst
     * sich mit `transportCommitmentU = 0.0` aufrufen, ohne dass ein Test
     * rot wird.
     *
     * Die Haftung im HAUPTpfad ist bereits scharf abgedeckt (die fuenf
     * `- transportModelledU`, samt dokumentiertem Rot-Nachweis weiter oben).
     * Der Fallback-Lift ist die SECHSTE Stelle und war nicht dabei.
     *
     * HIER IST DER KANAL SOGAR ISOLIERT, und das macht den Test schaerfer als
     * seine Geschwister: der Fallback laeuft ohne Bahn, also gibt es weder
     * Schwanz noch Prognose, ueber die die offene Menge sonst zusaetzlich
     * wirkt (`tailHeadroomU = null`, FuseCycleRunner.kt:2179). Faellt die
     * Dosis hier, kann es nur am Headroom-Term liegen.
     *
     * Gemessen wird gegen den identischen Lauf OHNE Haftung - sonst waere
     * unklar, ob die kleinere Menge nicht schon aus dem Aufbau folgt.
     */
    @Test
    fun `die offene Transporthaftung kappt auch den Fallbackpfad`(@TempDir dir: File) {
        fun laufMit(haftungU: Double, unterordner: String): Double {
            tailGuard = false
            flach = 105.0
            steigungProMin = -0.9          // fallende Bahn statt gemessenem Tief
            markerAt = start + 2 * 60_000L
            markerAuthorized = true
            predictReject = null

            val l = FuseLedgerAdapter().also {
                it.loadOnce(File(dir, unterordner).also(File::mkdirs), "test-epoch", start)
            }
            if (haftungU > 0.0) {
                l.onPublished("vorlauf", haftungU, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
                assertTrue(l.view().transportCommitmentU > 0.0, "Vorbedingung: die Haftung muss stehen")
            }
            neuerRunner(l)
            clock = start
            repeat(6) { cycle() }

            // Ab hier ohne Bahn - der Fallbackpfad.
            predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT
            var beste = 0.0
            var aufFallback = false
            repeat(12) {
                val o = cycle()
                if (o.markerFallbackUsed) aufFallback = true
                beste = maxOf(beste, o.decision.smbU)
            }
            assertTrue(aufFallback, "der Lauf muss den Fallbackpfad benutzt haben")
            return beste
        }

        // (1) POSITIVKONTROLLE: ohne Haftung traegt der Pfad wirklich etwas.
        val ohne = laufMit(0.0, "ohne")
        assertTrue(ohne > 0.0, "ohne Haftung muss etwas herauskommen - sonst prueft der Test nichts")

        // (2) DIE ZUSICHERUNG: dieselbe Lage mit voll ausgeschoepfter Haftung
        //     gibt nichts mehr frei. 8,0 U entspricht maxIOB/iobTH des Rigs -
        //     der Headroom-Term wird damit sicher bindend, unabhaengig vom
        //     genauen capIob des Zyklus.
        val mit = laufMit(8.0, "mit")
        assertTrue(
            mit < ohne,
            "die offene Haftung muss den Fallbackpfad kappen: ohne=$ohne mit=$mit",
        )
    }

    /**
     * DER P0 VOM 11.08.: der Fallback kehrte VOR `kernel()` zurueck.
     *
     * Der Boden im Hauptpfad haengt an einem gueltigen Einheitskern - dieser
     * Zweig aber lief daran vorbei und haette bei ARRAY_TOO_SHORT oder
     * PENDING_MODEL_TOO_SHORT dosiert, ohne je zu pruefen, ob das aktive
     * Insulinmodell gueltig ist.
     *
     * Damit war genau die Behauptung unbewiesen, auf der der ganze Pfad
     * beruht: "die BAHN fehlt, das MODELL ist aber gueltig". Die beiden
     * ueberstimmbaren Gruende sagen etwas ueber die REICHWEITE der Rechnung -
     * nichts darueber, ob das Modell endliche, lineare Werte liefert.
     */
    @Test
    fun `der Fallback dosiert nicht mit kaputtem Einheitskern`() {
        tailGuard = false
        flach = 105.0
        steigungProMin = -0.9
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        repeat(6) { cycle() }

        // ERLAUBTER Ablehnungsgrund UND kaputtes Insulinmodell zugleich.
        predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT
        val kaputt = org.mockito.kotlin.mock<Insulin>()
        whenever(kaputt.id).thenReturn(insulin.id)
        whenever(kaputt.peak).thenReturn(45)
        whenever(kaputt.iobCalcForTreatment(any(), any(), any()))
            .thenAnswer { app.aaps.core.data.iob.Iob().apply { iobContrib = Double.NaN } }
        whenever(activePlugin.activeInsulin).thenReturn(kaputt)

        var grund: String? = null
        repeat(12) { i ->
            val o = cycle()
            if (o.abortReason?.contains("noFallback=KERNEL") == true) grund = o.abortReason
            assertEquals(
                0.0, o.decision.smbU, 1e-9,
                "ein kaputtes Insulinmodell darf der Fallback nicht ueberstimmen (Zyklus $i)",
            )
        }
        val r = grund ?: throw AssertionError("der Kernel-Grund muss im Abbruch stehen")
        assertTrue(r.contains("PENDING_MODEL_TOO_SHORT"), "und der urspruengliche Grund auch: $r")
    }

    /**
     * DIE GEGENRICHTUNG, ohne die der Test darueber wertlos waere: derselbe
     * erlaubte Ablehnungsgrund MIT gueltigem Kern gibt weiterhin frei. Sonst
     * koennte der Kernel-Riegel den Fallback komplett totgelegt haben, ohne
     * dass es auffaellt.
     */
    @Test
    fun `derselbe Fallback mit gueltigem Kern gibt weiterhin frei`() {
        tailGuard = false
        flach = 105.0
        steigungProMin = -0.9
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        repeat(6) { cycle() }
        predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT

        var frei: FuseCycleRunner.Outcome? = null
        repeat(10) { if (frei == null) cycle().let { o -> if (o.decision.smbU > 0.0) frei = o } }
        val o = frei ?: throw AssertionError("mit gueltigem Kern muss der Fallback tragen")
        assertTrue(o.markerFallbackUsed)
        assertEquals(o.decision.markerAuthorizedU, o.decision.smbU, 1e-9)
    }

    /**
     * DIE BUCHFUEHRUNG IST DIESELBE, und das war bis zum 11.08. eine
     * Behauptung: der Fallback hatte eine KOPIE, der der Onset-Ablauf fehlte
     * (onsetQuietMin hochzaehlen und nach REARM_QUIET_MIN neu bewaffnen).
     * Hier laeuft ein vorgeladenes Onset-Budget ueber den Fallbackpfad ab.
     */
    @Test
    fun `auch der Fallbackpfad laesst ein Onset-Budget ablaufen`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        // OHNE Marker gaebe es keinen Fallback (Denial NO_MARKER), der Zyklus
        // braeche ab, und auf einem Abbruchpfad bucht auch der Hauptpfad nicht.
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start
        repeat(6) { cycle() }

        l.episodes.onsetSpentU = 0.40
        l.episodes.onsetQuietMin = 0
        predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT
        repeat(OnsetChannel.REARM_QUIET_MIN + 2) { cycle() }

        assertEquals(
            0.0, l.episodes.onsetSpentU, 1e-9,
            "nach REARM_QUIET_MIN stillen Minuten muss die Huelle auch hier neu bewaffnet sein",
        )
    }

    // ---- DIE MANUELLE AUTORISIERUNG, ganz durch ---------------------------

    /**
     * DER MARKER GIBT AM GEMESSENEN TIEF NICHTS FREI - GANZE KETTE
     * (Toni 18.08.).
     *
     * DIESER TEST HIESS BIS ZUM 18.08. "Marker autorisiert Insulin am
     * gemessenen Tief" und verlangte das Gegenteil: `smbU > 0.0` bei BG 62.
     * Er war die End-to-End-Bestaetigung einer Entscheidung, die auf einer
     * Fehlbeschreibung beruhte - `SAFETY_HOLD` galt als Modell-Block, ist
     * aber der rohe Messwert unter der Schwelle.
     *
     * Tonis Entscheidung: "Der Marker autorisiert eine Mahlzeit, aber kein
     * Insulin bei aktuell gemessenem Tief. Das entspricht unserem Vertrag:
     * Modell ueberstimmbar, Wirklichkeit nicht."
     *
     * GEPRUEFT WIRD DIE GANZE KETTE, nicht nur die Politik-Tabelle: BG 62
     * erreicht den Observer, wird zu `SafetyReason.LOW`, daraus wird
     * `state.safetyHold`, daraus `Block.SAFETY_HOLD` - und der Lift laesst
     * ihn stehen. Ein Test auf der Tabelle allein wuerde nicht merken, wenn
     * die Kette dazwischen bricht.
     */
    @Test
    fun `Marker gibt am gemessenen Tief nichts frei - ganze Kette`() {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        var sahTief = false
        repeat(12) {
            val o = cycle()
            if (o.state?.safetyHold == true) {
                sahTief = true
                // (1) DIE KERNZUSICHERUNG: keine Menge, in keinem Zyklus.
                assertEquals(
                    0.0, o.decision.smbU, 1e-9,
                    "gemessenes Tief ist Wirklichkeit - der Marker ueberstimmt sie nicht",
                )
                // (2) UND AUCH KEINE autorisierte Teilmenge im Datensatz. Ohne
                // diese Zeile koennte der Lift die Menge berechnen und erst
                // spaeter verlieren - die Autorisierung waere dann nur
                // zufaellig wirkungslos.
                assertEquals(
                    0.0, o.decision.markerAuthorizedU, 1e-9,
                    "es darf gar keine autorisierte Menge entstehen",
                )
            }
        }
        assertTrue(sahTief, "der Aufbau muss ein gemessenes Tief erzeugen, sonst prueft er nichts")
    }

    /**
     * DIE GEGENPROBE, die den Test oben erst aussagekraeftig macht: derselbe
     * Aufbau, nur mit einem PROGNOSTIZIERTEN statt gemessenen Grund, gibt
     * frei.
     *
     * Ohne sie waere nicht unterscheidbar, ob die Kette die Wirklichkeit
     * respektiert oder ob die Autorisierung ueberhaupt nicht mehr wirkt.
     */
    @Test
    fun `dieselbe Kette mit prognostiziertem Grund gibt frei`() {
        tailGuard = false
        flach = 105.0
        steigungProMin = -0.9          // fallende Bahn statt gemessenem Tief
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        var frei: FuseCycleRunner.Outcome? = null
        repeat(14) { if (frei == null) cycle().let { o -> if (o.decision.smbU > 0.0) frei = o } }
        val o = frei ?: throw AssertionError(
            "kein prognostizierter Grund mehr hebbar - dann ist das Fundament sinnlos"
        )
        assertTrue(o.state?.safetyHold != true, "hier darf KEIN gemessenes Tief vorliegen")
        assertTrue(o.decision.markerAuthorizedU > 0.0, "die Herkunft muss typisiert sein")
        assertTrue(
            o.decision.smbU <= o.decision.markerAuthorizedU + 1e-9,
            "es darf nur der autorisierte Anteil durchkommen: ${o.decision.smbU}",
        )
        assertTrue(
            o.decision.bindingLimit == "primeRelease" ||
                o.decision.bindingLimit.startsWith("markerAuth|"),
            "die Menge muss als markerfinanziert erkennbar sein: ${o.decision.bindingLimit}",
        )
    }

    /**
     * DIE GEGENPROBE ZUM SCHALTER. Dieselbe Lage, Einstellung AUS - und nichts
     * geht hinaus. Ohne diesen Fall koennte der Test oben auch dann gruen sein,
     * wenn am Tief GRUNDSAETZLICH etwas freikaeme.
     */
    @Test
    fun `ohne die Einstellung bleibt dieselbe Lage bei null`() {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAt = start + 2 * 60_000L
        markerAuthorized = false

        clock = start
        repeat(12) { i ->
            val o = cycle()
            assertEquals(0.0, o.decision.smbU, 1e-9, "ohne Autorisierung nichts am Tief (Zyklus $i)")
            assertEquals(0.0, o.decision.markerAuthorizedU, 1e-9, "und kein Herkunftsstempel")
        }
    }

    /**
     * DER SCHWANZ-WAECHTER NULLT DEN AUTORISIERTEN ANTEIL NICHT MEHR.
     *
     * DIESER TEST STAND EINEN COMMIT LANG ANDERSHERUM, und der Grund, warum
     * er sich gedreht hat, ist eine Entscheidung und kein Fehlerfund: er
     * hielt fest, dass der Schwanz-Headroom bei BG 62 die autorisierte Menge
     * unabhaengig nullt. Das war GEMESSEN richtig - aber es hiess, dass die
     * Einstellung an einem tiefen Punkt weiterhin nichts bewirkt, also genau
     * dort nicht, wo sie gebraucht wird.
     *
     * Tonis Vertrag vom 11.08. zieht die Linie anders: die Schwanzhaftung ist
     * eine MODELLANNAHME (eine Prognose ueber H), und modellbasierte Annahmen
     * duerfen den markerfinanzierten Anteil nicht nachtraeglich auf null
     * setzen. Sie duerfen ihn weiterhin DECKELN, wenn mehr verlangt wird -
     * nur nicht unter die Autorisierungsgrenze druecken.
     *
     * Der einzige Unterschied zum Test darueber ist der eingeschaltete
     * Waechter; die erwartete Menge ist jetzt dieselbe.
     */
    @Test
    fun `der Schwanz-Waechter nullt den autorisierten Anteil nicht mehr`() {
        tailGuard = true
        flach = 105.0
        steigungProMin = -0.9          // fallende Bahn statt gemessenem Tief
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        var frei: FuseCycleRunner.Outcome? = null
        repeat(12) { if (frei == null) cycle().let { o -> if (o.decision.smbU > 0.0) frei = o } }
        val o = frei ?: throw AssertionError("der Schwanz nullt den autorisierten Anteil immer noch")

        // Seit dem 18.08. ist das GEMESSENE Tief nicht mehr hebbar; der
        // Aufbau erzeugt die Sperre deshalb ueber eine fallende BAHN. Fuer
        // die Aussage dieses Tests - der Schwanz nullt den autorisierten
        // Anteil nicht mehr - ist das gleichwertig, denn der Schwanz ist
        // ohnehin eine Haftungsprognose und kein Tiefschutz.
        assertTrue(
            o.state?.safetyHold != true,
            "der Aufbau darf KEIN gemessenes Tief erzeugen - das waere nicht hebbar",
        )
        assertTrue(o.decision.markerAuthorizedU > 0.0, "ohne Autorisierung prueft das nichts")
        // UND NICHT MEHR ALS DAS. Der Boden hebt auf die Grenze, nicht darueber -
        // sonst waere aus einem Boden ein Freibrief geworden.
        assertEquals(
            o.decision.markerAuthorizedU, o.decision.smbU, 1e-9,
            "genau der autorisierte Anteil, kein Zuschlag",
        )
        // Dieselbe Vertragslage: ohne gemessenes Tief laeuft Profilbasal
        // weiter, statt dass eine Modell-Null dagegen arbeitet (Toni 17.08.).
        assertEquals(
            FuseController.TbrAction.KEEP_CURRENT, o.decision.tbr,
            "Profilbasal ist das Fundament - die Modell-Null entsteht gar nicht erst",
        )
    }

    /**
     * UND OHNE AUTORISIERUNG BLEIBT DER SCHWANZ BINDEND. Ohne diesen Fall
     * koennte der Test darueber auch dann gruen sein, wenn der Waechter
     * ueberhaupt nicht mehr wirkt.
     */
    @Test
    fun `ohne Autorisierung bleibt der Schwanz-Waechter bindend`() {
        tailGuard = true
        flach = 62.0
        steigungProMin = 0.0
        markerAt = start + 2 * 60_000L
        markerAuthorized = false

        clock = start
        repeat(12) { i ->
            assertEquals(
                0.0, cycle().decision.smbU, 1e-9,
                "ohne Autorisierung nullt der Schwanz weiterhin (Zyklus $i)",
            )
        }
    }
    /**
     * JEDER ANDERE BLOCKGRUND NULLT WEITERHIN - im echten Zyklus, in genau der
     * Lage, die eben noch freigegeben hat.
     *
     * Das ist die Gegenprobe zu `smbBlocked = false`: diese naheliegende
     * Reparatur haette alle sechs Gruende auf einmal geoeffnet. Nur
     * `SAFETY_ZERO` darf die Autorisierung ueberstimmen.
     */
    @Test
    fun `belegte Pumpe Fault und FAKE_EXTENDED nullen auch den autorisierten Anteil`() {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        // BELEGTE PUMPE.
        whenever(commandQueue.bolusInQueue()).thenReturn(true)
        clock = start
        repeat(12) { assertEquals(0.0, cycle().decision.smbU, 1e-9, "belegte Pumpe: nichts geht hinaus") }
        whenever(commandQueue.bolusInQueue()).thenReturn(false)

        // FAULT (TEMP_BASAL_FALLBACK) - hier als Parameter von `run`.
        neuerRunner(FuseLedgerAdapter())
        clock = start
        repeat(12) {
            clock += 60_000L
            val o = runner.run(true, testPumpe())
            assertEquals(0.0, o.decision.smbU, 1e-9, "Fault: nichts geht hinaus")
        }

        // FAKE_EXTENDED: einen fremden Extended darf FUSE nur LESEN.
        neuerRunner(FuseLedgerAdapter())
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenAnswer {
            TB(
                timestamp = start, duration = 30 * 60_000L, rate = 0.0,
                isAbsolute = true, type = TB.Type.FAKE_EXTENDED,
            )
        }
        clock = start
        repeat(12) { assertEquals(0.0, cycle().decision.smbU, 1e-9, "FAKE_EXTENDED: nichts geht hinaus") }
    }

    // ---- Die Episoden-Identitaet des Evidenz-Zaehlers ----------------------

    /**
     * RUECKNAHME UND ERNEUTES DRUECKEN ERZEUGEN KEINE NEUE EPISODE.
     *
     * Die Ruecknahme beendet die Marker-AUTORISIERUNG, nicht die Episode.
     * Haenge die Identitaet am aktuellen `markerTs`, saehe der zweite Druck wie
     * eine neue Mahlzeit aus - mit Zaehler 0, und dieselbe Stoerung waere ein
     * zweites Mal unbezahlt. Genau die Doppelfinanzierung, gegen die die
     * Episodenbudgets existieren.
     */
    @Test
    fun `Ruecknahme und erneutes Druecken erhalten Episode und Zaehler`(@TempDir dir: File) {
        tailGuard = false
        flach = 105.0
        steigungProMin = -0.9          // fallende Bahn statt gemessenem Tief
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(8) { cycle() }
        val anker = l.episodes.evidenceEpisodeId
        val bezahlt = l.episodes.evidenceCommittedU
        assertTrue(anker > 0L, "die Episode muss stehen")
        assertTrue(bezahlt > 0.0, "es muss etwas gebucht sein: $bezahlt")

        // Ruecknahme, ein paar Zyklen, dann NEU druecken.
        markerAt = 0L
        repeat(3) { cycle() }
        markerAt = clock + 60_000L
        repeat(3) { cycle() }

        assertEquals(anker, l.episodes.evidenceEpisodeId, "derselbe Anker")
        assertTrue(
            l.episodes.evidenceCommittedU >= bezahlt - 1e-9,
            "der Zaehler darf nicht zurueckgesetzt werden: $bezahlt -> ${l.episodes.evidenceCommittedU}",
        )
    }

    /** UND NACH DEM DECKEL beginnt wirklich eine neue Episode - sonst waere
     *  die Zusicherung oben nur "es gibt nie eine neue". */
    @Test
    fun `nach dem Episodendeckel beginnt eine neue Episode`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(8) { cycle() }
        val anker = l.episodes.evidenceEpisodeId

        // Weit hinter den harten Deckel springen und neu druecken.
        clock += (EvidenceStock.Config().maxEpisodeMin + 10) * 60_000L
        markerAt = clock + 60_000L
        repeat(3) { cycle() }

        assertTrue(
            l.episodes.evidenceEpisodeId != anker,
            "nach dem Deckel muss eine neue Episode beginnen",
        )
    }
    // ---- DER MARKERANKER: ein Druck eroeffnet hoechstens EINMAL -----------

    /**
     * DER NEUSTART-FALL.
     *
     * Nach einem Prozessstart steht der Markerzeitpunkt weiter in den
     * Preferences - er ist frisch genug fuer den Deckel und noch nicht
     * verbraucht. Ohne die Prozess-Beobachtung wuerde er eine zweite Episode
     * mit frischem Zaehler eroeffnen, obwohl die erste dieselbe Mahlzeit
     * schon bezahlt hat.
     *
     * Hier gebaut wie in echt: derselbe Marker, aber KEIN Druck in diesem
     * Prozess.
     */
    @Test
    fun `Marker aus einem frueheren Prozess eroeffnet keine Episode`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        // Der Warmstart-Zustand: Preference steht, Beobachtung ist leer.
        markerPress = 0L

        // ACHT ZYKLEN, nicht vier: die ersten Zyklen des Rigs brechen mangels
        // Signalhistorie ab und erreichen das Episodentor gar nicht. Ein Test
        // mit zu kurzem Anlauf haette "keine Episode" behauptet und in
        // Wahrheit "kein Zyklus" gemessen.
        val o = (1..8).map { cycle() }.last()

        assertEquals(0L, l.episodes.evidenceEpisodeId, "keine Episode")
        assertEquals(0.0, l.episodes.evidenceCommittedU, 1e-9, "kein Zaehler")
        assertEquals(0L, l.episodes.lastConsumedMarkerTs, "nichts verbraucht")
        assertEquals("MARKER_EVENT_NOT_DURABLE", o.evidenceEpisodeDenial, "und der Grund steht da")
    }

    /**
     * ABSTURZ ZWISCHEN KNOPFDRUCK UND LEDGER-PERSIST, in drei Schritten -
     * genau der Ablauf, den der Nutzer am Geraet erlebt.
     *
     * Der Marker kann danach weiter als aktiv angezeigt werden; eine Episode
     * gibt es nicht, und die Ruecknahme aendert daran nichts. Erst das
     * ERNEUTE Armen ist wieder ein beobachteter Druck - und dann genau
     * einmal.
     */
    @Test
    fun `verwaister Marker Ruecknahme und erneutes Armen`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        // 1. verwaist: Preference gesetzt, Beobachtung weg.
        markerAt = start + 2 * 60_000L
        clock = start
        markerPress = 0L
        // Acht, damit der Anlauf durch ist - s. Test darueber.
        repeat(8) { cycle() }
        assertEquals(0L, l.episodes.evidenceEpisodeId, "verwaist: keine Episode")

        // 2. Ruecknahme - sie kann nichts oeffnen, was es nicht gibt.
        markerAt = 0L
        repeat(2) { cycle() }
        assertEquals(0L, l.episodes.evidenceEpisodeId, "Ruecknahme oeffnet nichts")

        // 3. erneutes Armen: jetzt IST es ein beobachteter Druck.
        val zweiter = clock + 60_000L
        markerAt = zweiter
        repeat(3) { cycle() }
        assertEquals(zweiter, l.episodes.evidenceEpisodeId, "genau eine neue Episode")
        assertEquals(zweiter, l.episodes.lastConsumedMarkerTs, "und sie ist verbraucht")
    }

    /**
     * DER ANKER UEBERLEBT DIE EPISODENBEREINIGUNG.
     *
     * Ohne ihn waere die ganze Vorkehrung wirkungslos: nach dem Deckel ist
     * `evidenceEpisodeId` weg, der Preference-Wert steht noch, und derselbe
     * Druck - in DIESEM Prozess beobachtet, also an der zweiten Bedingung
     * vorbei - eroeffnete eine zweite Episode.
     */
    @Test
    fun `verbrauchter Marker bleibt nach Episodenbereinigung verbraucht`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(8) { cycle() }
        val anker = l.episodes.evidenceEpisodeId
        assertTrue(anker > 0L, "erst mal eine Episode")
        assertEquals(anker, l.episodes.lastConsumedMarkerTs)

        // Die Bereinigung: Episode weg, Anker bleibt. Der Marker steht
        // unveraendert und gilt weiterhin als in diesem Prozess gedrueckt.
        l.episodes.evidenceEpisodeId = 0L
        l.episodes.evidenceCommittedU = 0.0

        val o = (1..3).map { cycle() }.last()
        assertEquals(0L, l.episodes.evidenceEpisodeId, "keine zweite Episode fuer denselben Druck")
        assertEquals("MARKER_ALREADY_CONSUMED", o.evidenceEpisodeDenial)
    }
    // ---- DER WIDERRUFSVERTRAG am laufenden Zyklus ------------------------

    /**
     * RUECKNAHME WIDERRUFT DEN KREDIT, ERNEUTES ARMEN GIBT IHN FREI - und
     * beides steht im Ledger, nicht nur im Ergebnis dieses Zyklus.
     *
     * Der Unterschied zaehlt: nur der persistierte Stand ueberlebt den
     * Neustart, und genau dort lag der teure Fall - Ruecknahme, Neustart, und
     * die wiedergefundene Episode liefert wieder Kredit.
     */
    @Test
    fun `Ruecknahme widerruft den Kredit im Ledger und erneutes Armen gibt ihn frei`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(8) { cycle() }
        val anker = l.episodes.evidenceEpisodeId
        val bezahlt = l.episodes.evidenceCommittedU
        assertTrue(anker > 0L, "erst mal eine Episode")
        assertFalse(l.episodes.evidenceRevoked, "und sie ist nicht widerrufen")

        // Ruecknahme: Kredit weg, Buchhaltung bleibt.
        markerAt = 0L
        val o = (1..3).map { cycle() }.last()
        assertTrue(l.episodes.evidenceRevoked, "der Kredit ist widerrufen")
        assertTrue(o.evidenceCreditRevoked, "und das steht auch im Ergebnis")
        assertEquals(anker, l.episodes.evidenceEpisodeId, "die Episode bleibt")
        assertTrue(
            l.episodes.evidenceCommittedU >= bezahlt - 1e-9,
            "die Bezahlung bleibt: $bezahlt -> ${l.episodes.evidenceCommittedU}",
        )

        // Erneutes bewusstes Armen im Deckel: frei, aber kein neues Budget.
        markerAt = clock + 60_000L
        repeat(3) { cycle() }
        assertFalse(l.episodes.evidenceRevoked, "erneutes Armen gibt frei")
        assertEquals(anker, l.episodes.evidenceEpisodeId, "immer noch dieselbe Episode")
    }

    /**
     * NEUSTART NACH RUECKNAHME - der Kredit bleibt gesperrt.
     *
     * Zweiter Adapter auf demselben Verzeichnis, also die echte Ladekette.
     * Ohne den persistierten Stand haette hier ein aus den Preferences
     * vorgefundener Markerzeitpunkt gereicht, um wieder zu lizenzieren.
     */
    @Test
    fun `Neustart nach Ruecknahme laesst den Kredit gesperrt`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true
        dir.mkdirs()

        val l1 = FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", start) }
        neuerRunner(l1)
        markerAt = start + 2 * 60_000L
        clock = start
        repeat(8) { cycle() }
        val anker = l1.episodes.evidenceEpisodeId

        markerAt = 0L
        repeat(3) { cycle() }
        assertTrue(l1.episodes.evidenceRevoked)
        assertTrue(l1.persistVerified(dir), "der Ledger muss schreiben")

        // NEUSTART: neuer Adapter, neuer Runner, keine Beobachtung. Der
        // Markerzeitpunkt taucht wieder auf, ohne dass jemand gedrueckt hat.
        val l2 = FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch2", clock) }
        neuerRunner(l2)
        assertEquals(anker, l2.episodes.evidenceEpisodeId, "die Episode wurde geladen")
        assertTrue(l2.episodes.evidenceRevoked, "und der Widerruf mit ihr")

        markerAtIntern = anker
        markerPress = 0L
        val o = (1..8).map { cycle() }.last()
        assertTrue(l2.episodes.evidenceRevoked, "ohne Willenserklaerung bleibt gesperrt")
        assertTrue(o.evidenceCreditRevoked)
    }

    /**
     * DER NATUERLICHE ABLAUF widerruft NICHT.
     *
     * Nach 90 Minuten endet das Kontextfenster, die Episode laeuft bis 240
     * weiter - der gemessene Lauf vom 11.08. war nach 205 Minuten noch aktiv.
     * Wuerde der Ablauf widerrufen, waere genau die zweite Welle unbedient.
     */
    @Test
    fun `natuerlicher Ablauf des Markerfensters widerruft den Kredit nicht`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(8) { cycle() }
        assertTrue(l.episodes.evidenceEpisodeId > 0L)

        // Ueber das 90-min-Fenster hinaus, OHNE die Preference zu nullen -
        // genau der Unterschied zur Ruecknahme.
        clock += 100 * 60_000L
        val o = (1..3).map { cycle() }.last()

        assertFalse(l.episodes.evidenceRevoked, "abgelaufen ist nicht zurueckgenommen")
        assertFalse(o.evidenceCreditRevoked)
    }
    /**
     * EIN DECKEL, DREI VERBRAUCHER - der Durchstich (Toni 12.08.).
     *
     * `EvidenceStock.Config()` wurde an drei Stellen frisch erzeugt: Markertor,
     * Kern und Export. Solange die Defaults gelten, faellt das nie auf. Genau
     * deshalb prueft dieser Test mit einem Wert, den kein Default hat: sieht
     * irgendwo 360 statt 17, ist die Instanz nicht durchgereicht.
     */
    @Test
    fun `ein abweichender Episodendeckel gilt an Tor und Export`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l, EvidenceStock.Config(maxEpisodeMin = 17))

        markerAt = start + 2 * 60_000L
        clock = start
        val o = (1..8).map { cycle() }.last()

        // Der EXPORT-Weg: der Deckel des Zyklus, nicht der Default.
        assertEquals(17, o.evidenceEpisodeCapMin, "der Export bekaeme sonst 360")
        val anker = l.episodes.evidenceEpisodeId
        assertTrue(anker > 0L)

        // Der TOR-Weg: 20 Minuten spaeter ist die Episode nach DIESEM Deckel
        // abgelaufen, und ein neuer Druck eroeffnet eine neue. Mit 360 waere
        // sie noch dieselbe.
        clock += 20 * 60_000L
        markerAt = clock + 60_000L
        repeat(3) { cycle() }
        assertTrue(
            l.episodes.evidenceEpisodeId != anker,
            "das Markertor rechnet noch mit dem Default-Deckel",
        )
    }

    /**
     * TONIS FALL VOM 16.08. - die Marker-Verlaengerung des Episodendeckels.
     *
     * Fruehstuecks-Marker 09:33 eroeffnete die Episode; der Marker um 14:38
     * fuer eine ECHTE zweite Mahlzeit lag 305 Minuten spaeter, also INNERHALB
     * des 360-Minuten-Deckels, und erbte den alten Topf samt Uhr. Um 15:33
     * lief er ab - mitten in der zweiten Mahlzeit, T+55 min, kurz vor der
     * Staerkewelle der Nudeln.
     *
     * [EpisodeDeadline] traegt die Episode jetzt weiter, solange der Druck
     * innerhalb des Basisdeckels lag. Dieser Test prueft die VERDRAHTUNG, nicht
     * die Rechenregel - die hat ihre eigenen Unit-Tests. Ohne ihn bliebe eine
     * Mutation, die `EpisodeDeadline.effectiveCapMs` durch den Basisdeckel
     * ersetzt, unentdeckt (nachgemessen: sie blieb gruen).
     *
     * Der kleine Deckel (17 min) macht den Lauf kurz; die Verlaengerung von
     * 180 min ist dieselbe wie produktiv.
     */
    @Test
    fun `ein frischer Marker traegt die Episode ueber den Deckel`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l, EvidenceStock.Config(maxEpisodeMin = 17))

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(8) { cycle() }
        val anker = l.episodes.evidenceEpisodeId
        assertTrue(anker > 0L, "Vorbedingung: eine Episode muss laufen")

        // Zweiter Druck INNERHALB des Deckels (bei 12 von 17 min) - er erbt
        // die Episode und verlaengert sie.
        clock = start + 12 * 60_000L
        markerAt = clock + 60_000L
        repeat(3) { cycle() }
        assertEquals(anker, l.episodes.evidenceEpisodeId, "der Druck im Fenster muss erben, nicht eroeffnen")

        // JETZT der entscheidende Sprung: hinter den BASISDECKEL (17 min),
        // aber innerhalb der Verlaengerung (12 + 180). Dort wird erneut
        // gedrueckt.
        //
        // GEMESSEN WIRD AM VERHALTEN DES TORS, nicht an Outcome-Feldern: ein
        // Druck auf eine LEBENDE Episode erbt sie (dieselbe Id), ein Druck
        // nach ihrem Ende eroeffnet eine neue. Genau dieselbe Zusicherung wie
        // im Test darueber, nur mit umgekehrtem Vorzeichen - und sie ist
        // unabhaengig davon, welcher Zyklus zuletzt welchen Exportpfad nahm.
        clock = start + 25 * 60_000L
        val o = (1..3).map { cycle() }.last()

        // KEIN weiterer Druck: er wuerde die Verlaengerung selbst aufheben
        // (ein Druck nach dem Basisdeckel eroeffnet neu, statt zu verlaengern).
        // Gemessen wird deshalb am EXPORTIERTEN Deckel - er traegt den
        // wirksamen Wert und ist damit der direkte Beleg der Verdrahtung.
        assertEquals(anker, l.episodes.evidenceEpisodeId, "dieselbe Episode")
        assertTrue(
            (o.evidenceEpisodeCapMin ?: 0) > 17,
            "der wirksame Deckel muss ueber dem Basiswert liegen - sonst wirkt " +
                "die Verlaengerung nicht: ${o.evidenceEpisodeCapMin}",
        )
        assertTrue(
            o.evidencePhase != "EXPIRED",
            "und die Episode darf nicht abgelaufen sein: ${o.evidencePhase}",
        )
    }

    /**
     * STUFE 4, NICHT-LEERES GATE-ATTEST.
     *
     * Eine fruehere Fassung der Stufe-4-Tests war gruen, obwohl ueberhaupt
     * kein Evidenzkredit entstand. Deshalb stehen die drei Vorbedingungen
     * VOR der Kappenzusicherung: Kredit positiv, bedingte Kante wirklich
     * hoeher und der ungerasterte Wunsch groesser als die gepruefte Grenze.
     * Erst dann ist `final == maxSMB` eine Aussage ueber die Reihenfolge der
     * Architektur und kein zufaelliges Nullergebnis.
     */
    @Test
    fun `positiver Evidenzkredit bleibt hinter maxSMB und dem Publikationsgate`(@TempDir dir: File) {
        flach = 115.0
        steigungProMin = 3.0
        markerAt = start + 2 * 60_000L
        conditionalTail = true
        quantilePct = 25
        aktivitaet = 0.004
        tailGuard = false

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start

        // Zuerst Messinformation aufbauen und versiegeln, ohne dass der
        // normale Pfad sie im VirtualPump-Rig sofort als bezahlt abbucht.
        maxSmbU = 0.0
        var kreditZyklus: FuseCycleRunner.Outcome? = null
        repeat(80) {
            val o = cycle()
            if (
                (o.evidenceCreditMgdlPerMin ?: 0.0) > 0.0 &&
                o.tailLowerConditionalMgdl != null &&
                o.tailLowerUnconditionalMgdl != null &&
                o.tailLowerConditionalMgdl!! > o.tailLowerUnconditionalMgdl!!
            ) kreditZyklus = o
        }
        val kredit = kreditZyklus ?: throw AssertionError("kein wirkender Evidenzkredit in 80 Zyklen")
        assertTrue((kredit.evidenceCreditMgdlPerMin ?: 0.0) > 0.0, "Kredit muss positiv sein")
        assertTrue(
            kredit.tailLowerConditionalMgdl!! > kredit.tailLowerUnconditionalMgdl!!,
            "die bedingte Kante muss wirklich steigen",
        )

        // Nun dieselbe laufende Episode gegen eine enge reale Kappe rechnen.
        maxSmbU = 0.05
        val gekappt = cycle()
        // DIE VORBEDINGUNG GILT AM KAPP-ZYKLUS SELBST (Audit 15.08.): der
        // Kredit kann zwischen zwei Zyklen erloeschen (Verfall, Rebase,
        // Seal-Rollback) - eine Vorbedingung am Zyklus n beweist nichts
        // ueber Zyklus n+1, und die Kappenzusicherung waere leer gruen.
        assertTrue((gekappt.evidenceCreditMgdlPerMin ?: 0.0) > 0.0) {
            "der Kredit muss IM Kapp-Zyklus fliessen: ${gekappt.evidencePhase}/${gekappt.evidenceReason}"
        }
        assertTrue(
            gekappt.tailLowerConditionalMgdl != null && gekappt.tailLowerUnconditionalMgdl != null &&
                gekappt.tailLowerConditionalMgdl!! > gekappt.tailLowerUnconditionalMgdl!!,
        ) { "und die bedingte Kante muss IM Kapp-Zyklus gehoben sein" }
        val maxSmb = gekappt.decision.caps.single { it.name == "maxSmb" }
        val ungekappterKandidat = gekappt.decision.caps.single { it.name == "smbRatio" }.valueU
        assertTrue(
            ungekappterKandidat > maxSmb.valueU,
            "der ungekappte Kandidat muss die Kappe uebersteigen: candidate=$ungekappterKandidat, " +
                "cap=${maxSmb.valueU}, decision=${gekappt.decision}",
        )
        assertTrue(maxSmb.active, "maxSMB muss wirklich binden")
        assertEquals(0.05, gekappt.decision.smbU, 1e-9, "finale Menge bleibt auf maxSMB")

        // Der Translator lief bereits im Runner. Jetzt auch das letzte Gate:
        // ohne dauerhaft gebuchte Vorschlagszeile darf der positive Betrag
        // trotz Kredits nicht publiziert werden.
        val rt = FuseRtBuilder.build(
            nowMs = gekappt.computeTs,
            bgMgdl = gekappt.signal?.q1,
            targetMgdl = 98.0,
            iobU = 0.0,
            decision = gekappt.decision,
            tbr = gekappt.tbr,
            gate = testPumpe().gate,
            profileIsfMgdlPerU = 90.0,
        )
        assertEquals(0.05, rt.units!!, 1e-9, "Ausgangslage: Translator und Pumpengate lassen die Kappe durch")

        val pumpGesperrt = FuseRtBuilder.build(
            nowMs = gekappt.computeTs,
            bgMgdl = gekappt.signal?.q1,
            targetMgdl = 98.0,
            iobU = 0.0,
            decision = gekappt.decision,
            tbr = gekappt.tbr,
            gate = FusePumpGate.Result(FusePumpGate.Verdict.BLOCKED_REAL_PUMP, "nicht freigegeben"),
            profileIsfMgdlPerU = 90.0,
        )
        assertEquals(null, pumpGesperrt.units, "Pumpengate bleibt auch mit Evidenzkredit hart")

        val publiziert = app.aaps.fuse.plugin.ledger.LedgerPublicationGate.publish(
            rt = rt,
            adapter = l,
            dir = dir,
            expected = app.aaps.fuse.plugin.ledger.LedgerPublicationGate.Commitment.Proposal("evidence-cap"),
            published = InterventionStamp.Published(smbU = null, tbrChanged = false),
            events = { /* absichtlich keine Zeile: Publikationsgate muss sperren */ },
        )
        assertEquals(null, publiziert.rt.units, "Publikationsgate bleibt auch mit Evidenzkredit hart")
    }
    // ---- Totbaender gegen das Mahlzeitenfenster (Toni 15.08.) -------------

    /**
     * DER 2-TAGE-BEFUND ALS TEST: nach Ablauf der Marker-Sonderrechte
     * blockte das Nacht-Totband Zyklen, in denen die Evidenz-Episode ACTIVE
     * war und Kredit auswies (81 Live-Zyklen im Trail, z.B. 13.08. 22:40).
     *
     * Aufbau: Nachtfenster deckt die Rig-Uhr ab, Totband 45 mg/dl, BG unter
     * Ziel+45. OHNE Kredit muss das Totband sperren (Gegenprobe), MIT
     * aktivem Evidenzkredit muss es entwaffnet sein - die Totbaender
     * schuetzen vor unangekuendigten Abweichungen, und eine markereroeffnete
     * Episode mit versiegelter unbezahlter Stoerung ist das Gegenteil davon.
     *
     * BOOST-FENSTER AUS (Abschluss-Audit 15.08.): die erste Fassung liess
     * den Rig-Boost 45 Minuten laufen, und ALLE geprueften Zyklen lagen
     * darin - `markerBoost` entwaffnete die Totbaender kreditunabhaengig,
     * und der Test war gruen, obwohl der Kreditpfad im Runner gar nicht
     * verdrahtet war. Mit Boostfenster 0 prueft er den KREDIT, nicht die
     * Sonderrechte.
     */
    @Test
    fun `das Nacht-Totband sperrt das Mahlzeitenfenster nicht`(@TempDir dir: File) {
        tailGuard = false
        conditionalTail = true
        markerAuthorized = true
        nightDeadband = true
        whenever(preferences.get(FuseIntKey.NightStartMin)).thenReturn(0)
        whenever(preferences.get(FuseIntKey.NightEndMin)).thenReturn(1439)
        // Boost aus - sonst prueft der Test die Marker-Sonderrechte statt
        // des Kredits (s. KDoc).
        whenever(preferences.get(FuseIntKey.MarkerBoostMaxMin)).thenReturn(0)
        // Flacher Anstieg, damit der ERSTE Kreditzyklus sicher unter der
        // Totbandschwelle 98 + 45 = 143 liegt.
        flach = 100.0
        steigungProMin = 2.0

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        markerAt = start + 2 * 60_000L
        clock = start

        // AUFBAU wie im maxSMB-Attest: erst Messinformation ansammeln und
        // versiegeln, ohne dass der Rig-Pfad sie sofort als bezahlt abbucht -
        // die Abgaben (0,2 U x ISF 90 = 18 mg/dl je Zyklus) wuerden den
        // Bestand sonst schneller abraeumen, als der Zufluss ihn fuellt.
        maxSmbU = 0.0
        var kreditZyklus: FuseCycleRunner.Outcome? = null
        for (i in 1..60) {
            val o = cycle()
            // Der ERSTE Kreditzyklus - dort ist der BG noch tief im Totband.
            if ((o.evidenceCreditMgdlPerMin ?: 0.0) > 0.0) { kreditZyklus = o; break }
        }
        val kredit = kreditZyklus ?: throw AssertionError("kein Evidenzkredit in 60 Zyklen")

        // VORBEDINGUNG am Kreditzyklus selbst: BG liegt UNTER Ziel+Totband,
        // das Totband griffe also ohne Kredit - sonst prueft der Test nichts.
        assertTrue(kredit.signal!!.q1 < 98.0 + 45.0) { "BG ${kredit.signal!!.q1} muss im Totbandbereich liegen" }
        assertTrue(
            kredit.decision.bindingLimit != "nightDeadband" && kredit.decision.bindingLimit != "reboundDeadband",
        ) { "Totband blockt trotz Kredit: ${kredit.decision.bindingLimit}" }

        // KERN: Kappe oeffnen - im naechsten Kreditzyklus muss dosiert werden,
        // das Totband darf nicht binden.
        maxSmbU = 0.3
        val dosier = (1..5).map { cycle() }.filter { (it.evidenceCreditMgdlPerMin ?: 0.0) > 0.0 }
        assertTrue(dosier.isNotEmpty()) { "Kredit muss weiterfliessen" }
        assertTrue(dosier.none { it.decision.bindingLimit == "nightDeadband" || it.decision.bindingLimit == "reboundDeadband" }) {
            "Totband blockt trotz Kredit: " + dosier.map { it.decision.bindingLimit }
        }
        assertTrue(dosier.any { it.decision.smbU > 0.0 }) {
            "und dosiert werden muss auch: " + dosier.map { "${it.decision.block}/${it.decision.bindingLimit}" }
        }
    }

    /** GEGENPROBE: ohne Marker (kein Kredit) sperrt dasselbe Totband - sonst
     *  haette der Test oben nur bewiesen, dass das Totband gar nicht greift. */
    @Test
    fun `ohne Kredit sperrt das Nacht-Totband weiterhin`(@TempDir dir: File) {
        tailGuard = false
        conditionalTail = true
        nightDeadband = true
        whenever(preferences.get(FuseIntKey.NightStartMin)).thenReturn(0)
        whenever(preferences.get(FuseIntKey.NightEndMin)).thenReturn(1439)
        flach = 100.0
        steigungProMin = 2.0

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        markerAt = 0L
        clock = start
        val laeufe = (1..15).map { cycle() }

        val gesperrt = laeufe.filter { it.decision.bindingLimit == "nightDeadband" }
        assertTrue(gesperrt.isNotEmpty()) { "das Totband muss ohne Kredit greifen: " + laeufe.map { it.decision.bindingLimit } }
        assertTrue(laeufe.all { it.decision.smbU == 0.0 }) { "und nichts dosieren" }
    }
    /**
     * GATE-ATTEST iobTH: der Kredit hebt die Bahn - die iobTH-Grenze bindet
     * trotzdem quantitativ.
     *
     * Aufbau wie beim maxSMB-Attest: Kredit ohne Abgabe ansammeln, dann die
     * Grenze scharf schalten. Vorbedingung am Kapp-Zyklus selbst.
     */
    @Test
    fun `positiver Evidenzkredit bleibt hinter iobTH`(@TempDir dir: File) {
        flach = 115.0
        steigungProMin = 3.0
        markerAt = start + 2 * 60_000L
        conditionalTail = true
        quantilePct = 25
        aktivitaet = 0.004
        tailGuard = false

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start

        maxSmbU = 0.0
        var kreditGesehen = false
        repeat(80) { if ((cycle().evidenceCreditMgdlPerMin ?: 0.0) > 0.0) kreditGesehen = true }
        assertTrue(kreditGesehen) { "kein Evidenzkredit in 80 Zyklen" }

        // iobTH eng: 10% von maxIOB 8 = 0,8 U - bei iob ~0 bindet der
        // verbleibende Spielraum die Menge auf 0,8, pumpenschrittgerecht.
        maxSmbU = 5.0
        iobThPct = 10
        val gekappt = cycle()
        assertTrue((gekappt.evidenceCreditMgdlPerMin ?: 0.0) > 0.0) { "Kredit muss IM Kapp-Zyklus fliessen" }
        val cap = gekappt.decision.caps.single { it.name == "iobThHeadroom" }
        val kandidat = gekappt.decision.caps.single { it.name == "smbRatio" }.valueU
        assertTrue(kandidat > cap.valueU) { "der ungekappte Kandidat muss die Grenze uebersteigen: $kandidat vs ${cap.valueU}" }
        assertTrue(cap.active) { "iobTH muss binden: ${gekappt.decision.caps}" }
        // Pumpenschrittgerecht abgerundete Grenze.
        val erwartet = kotlin.math.floor(cap.valueU / 0.05) * 0.05
        assertEquals(erwartet, gekappt.decision.smbU, 1e-9)
    }

    /**
     * GATE-ATTEST LEDGER-HOLD (binaer): trotz nachweislich positivem Kredit
     * und positivem Kandidaten bleibt die PUBLIZIERTE Menge 0, wenn der
     * Ledger haelt - geprueft NACH Translator und Publikationsgate.
     */
    @Test
    fun `positiver Evidenzkredit dringt nicht durch einen Ledger-Hold`(@TempDir dir: File) {
        flach = 115.0
        steigungProMin = 3.0
        markerAt = start + 2 * 60_000L
        conditionalTail = true
        quantilePct = 25
        aktivitaet = 0.004
        tailGuard = false

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start

        maxSmbU = 0.0
        repeat(80) { cycle() }
        maxSmbU = 0.3
        val mitKredit = cycle()
        assertTrue((mitKredit.evidenceCreditMgdlPerMin ?: 0.0) > 0.0) { "Kredit muss fliessen" }
        assertTrue(mitKredit.decision.smbU > 0.0) { "und ein Kandidat muss stehen: ${mitKredit.decision}" }

        // ECHTER Hold: der Sentinel-Name wird von einem Verzeichnis besetzt,
        // der Persist scheitert nachweislich - kein Mock.
        File(dir, app.aaps.fuse.plugin.ledger.FuseLedgerStore.SENTINEL_NAME).delete()
        assertTrue(File(dir, app.aaps.fuse.plugin.ledger.FuseLedgerStore.SENTINEL_NAME).mkdirs())
        assertFalse(l.persistVerified(dir))
        assertTrue(l.view().hold)

        val imHold = cycle()
        // Der Kredit selbst ist im Hold null (persistedStateKnown=false) UND
        // die Menge bleibt null - beides gehoert zum Attest.
        assertEquals(0.0, imHold.evidenceCreditMgdlPerMin ?: 0.0, 1e-9) { "im Hold darf kein Kredit entstehen" }
        assertEquals(0.0, imHold.decision.smbU, 1e-9)
        val rt = FuseRtBuilder.build(
            nowMs = imHold.computeTs, bgMgdl = imHold.signal?.q1, targetMgdl = 98.0, iobU = 0.0,
            decision = imHold.decision, tbr = imHold.tbr, gate = testPumpe().gate, profileIsfMgdlPerU = 90.0,
        )
        assertEquals(null, rt.units) { "publiziert werden darf nichts" }
    }

    /**
     * GATE-ATTEST MODELL (binaer): faellt der Predictor im Zyklus NACH dem
     * Kreditaufbau aus, verlaesst trotz stehendem Bestand keine Menge den
     * Zyklus - unverifiziert wird nicht dosiert.
     *
     * DREI ZUSICHERUNGEN gegen Leer-Gruen (Abschluss-Audit 15.08.): die
     * erste Fassung prueft nur `smbU == 0` am Ausfallzyklus. Sie waere auch
     * gruen, wenn (a) das Rig ohne Ausfall gar nicht dosierte oder (b) der
     * Kredit am Ausfallzyklus laengst versiegt waere - beides prueft jetzt
     * je eine eigene Assertion, und der Abbruchgrund muss den verweigerten
     * Fallback benennen.
     */
    @Test
    fun `positiver Evidenzkredit dringt nicht durch einen Modellausfall`(@TempDir dir: File) {
        flach = 115.0
        steigungProMin = 3.0
        markerAt = start + 2 * 60_000L
        conditionalTail = true
        quantilePct = 25
        aktivitaet = 0.004
        tailGuard = false

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start

        maxSmbU = 0.0
        var kreditGesehen = false
        repeat(80) { if ((cycle().evidenceCreditMgdlPerMin ?: 0.0) > 0.0) kreditGesehen = true }
        assertTrue(kreditGesehen) { "kein Evidenzkredit in 80 Zyklen" }

        // KONTROLLZYKLUS: ohne Ausfall dosiert das Rig - sonst bewiese der
        // Ausfallzyklus nur, dass ohnehin nichts kam. Die Abbuchung
        // (0,3 U x ISF 90 = 27 mg/dl) vertraegt der Bestand.
        maxSmbU = 0.3
        val kontrolle = cycle()
        assertTrue(kontrolle.decision.smbU > 0.0) { "der Kontrollzyklus muss dosieren: ${kontrolle.decision}" }

        predictReject = app.aaps.fuse.core.predictor.PredictorReason.MISSING_ISF_SLOT
        val ausfall = cycle()
        predictReject = null

        assertTrue((ausfall.evidenceCreditMgdlPerMin ?: 0.0) > 0.0) {
            "der Kredit muss AM Ausfallzyklus fliessen - sonst prueft der Test nichts"
        }
        assertEquals(0.0, ausfall.decision.smbU, 1e-9) { "Modellausfall dosiert nicht: ${ausfall.decision}" }
        assertTrue(ausfall.abortReason?.contains("noFallback=REASON_NOT_OVERRIDABLE") == true) {
            "der Grund muss den verweigerten Fallback benennen: ${ausfall.abortReason}"
        }
    }
    /**
     * OPTION A AM TRAIL-FALL (13.08.): Druck 14:59 waehrend laufender
     * Episode (09:19), Vorgaenger laeuft an den Deckel - frueher eroeffnete
     * der geerbte Druck um 15:19 still eine neue Episode mit frischem
     * Deckel. Jetzt ist er verbraucht: nach dem Deckelende gibt es KEINE
     * Folgeepisode, der Notaus ist hart. Ein NEUER bewusster Druck danach
     * eroeffnet weiterhin.
     */
    @Test
    fun `ein geerbter Druck eroeffnet nach dem Deckelende keine Folgeepisode`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(8) { cycle() }
        val anker = l.episodes.evidenceEpisodeId
        assertTrue(anker > 0L, "Episode steht")

        // Zweiter Druck WAEHREND der laufenden Episode (der 14:59-Fall).
        clock += 30 * 60_000L
        val zweiterDruck = clock + 60_000L
        markerAt = zweiterDruck
        repeat(3) { cycle() }
        assertEquals(anker, l.episodes.evidenceEpisodeId, "geerbt, keine neue Episode")
        assertEquals(zweiterDruck, l.episodes.lastConsumedMarkerTs, "und sofort verbraucht (Option A)")

        // Vorgaenger laeuft an den Deckel - der geerbte Druck darf danach
        // NICHTS mehr eroeffnen.
        clock = anker + (EvidenceStock.Config().maxEpisodeMin + 5) * 60_000L
        val o = (1..3).map { cycle() }.last()
        assertEquals(0L, l.episodes.evidenceEpisodeId.takeIf { it != anker } ?: 0L, "keine Folgeepisode")
        assertEquals("MARKER_ALREADY_CONSUMED", o.evidenceEpisodeDenial)

        // Ein NEUER bewusster Druck eroeffnet weiterhin.
        markerAt = clock + 60_000L
        repeat(3) { cycle() }
        assertEquals(markerAtIntern, l.episodes.evidenceEpisodeId, "neuer Druck, neue Episode")
    }
    /**
     * GATE-ATTEST maxIOB: quantitativ, mit Kredit-Vorbedingung am Kapp-Zyklus.
     *
     * iobTH auf 200% (= 2 x maxIOB) nimmt die schnelle Grenze aus dem Spiel -
     * bindet der Spielraum, ist es der maxIOB-Spielraum.
     */
    @Test
    fun `positiver Evidenzkredit bleibt hinter maxIOB`(@TempDir dir: File) {
        flach = 115.0
        steigungProMin = 3.0
        markerAt = start + 2 * 60_000L
        conditionalTail = true
        quantilePct = 25
        aktivitaet = 0.004
        tailGuard = false

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start

        maxSmbU = 0.0
        var kreditGesehen = false
        repeat(80) { if ((cycle().evidenceCreditMgdlPerMin ?: 0.0) > 0.0) kreditGesehen = true }
        assertTrue(kreditGesehen) { "kein Evidenzkredit in 80 Zyklen" }

        maxSmbU = 5.0
        iobThPct = 200          // iobTH = 1,6 > maxIOB-Spielraum -> maxIOB bindet
        maxIobU = 0.8
        val gekappt = cycle()
        assertTrue((gekappt.evidenceCreditMgdlPerMin ?: 0.0) > 0.0) { "Kredit muss IM Kapp-Zyklus fliessen" }
        val cap = gekappt.decision.caps.single { it.name == "maxIobHeadroom" }
        val kandidat = gekappt.decision.caps.single { it.name == "smbRatio" }.valueU
        assertTrue(kandidat > cap.valueU) { "der ungekappte Kandidat muss die Grenze uebersteigen: $kandidat vs ${cap.valueU}" }
        assertTrue(cap.active) { "maxIOB muss binden: ${gekappt.decision.caps}" }
        val erwartet = kotlin.math.floor(cap.valueU / 0.05) * 0.05
        assertEquals(erwartet, gekappt.decision.smbU, 1e-9)
    }

    /**
     * GATE-ATTEST TRANSPORT: eine offene, noch nicht im IOB sichtbare
     * Transportmenge verengt die FINALE Menge quantitativ um genau ihren
     * Betrag - auch mit fliessendem Kredit.
     *
     * Der Abzug wirkt ueber die Kandidatensuche (effectiveIobThHeadroomU =
     * iobTH - capIob - transport), nicht ueber die Basis-Kappenliste -
     * messbar ist er deshalb nur an der finalen Menge. Und weil jede Abgabe
     * den Bestand BEZAHLT (0,8 U = 72 mg/dl), braucht es zwischen den beiden
     * Messzyklen eine Erholungsphase, in der der Kredit neu entsteht.
     */
    @Test
    fun `positiver Evidenzkredit bleibt hinter dem Transportabzug`(@TempDir dir: File) {
        flach = 115.0
        steigungProMin = 3.0
        markerAt = start + 2 * 60_000L
        conditionalTail = true
        quantilePct = 25
        aktivitaet = 0.004
        tailGuard = false

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start

        maxSmbU = 0.0
        var kreditGesehen = false
        repeat(80) { if ((cycle().evidenceCreditMgdlPerMin ?: 0.0) > 0.0) kreditGesehen = true }
        assertTrue(kreditGesehen) { "kein Evidenzkredit in 80 Zyklen" }

        // MESSZYKLUS A: ohne Transportzeile bindet der iobTH-Spielraum.
        maxSmbU = 5.0
        iobThPct = 10
        val ohne = cycle()
        assertTrue((ohne.evidenceCreditMgdlPerMin ?: 0.0) > 0.0) { "Kredit muss in A fliessen" }
        assertEquals(0.80, ohne.decision.smbU, 1e-9) { "A: voller iobTH-Spielraum: ${ohne.decision}" }

        // ERHOLUNG: die Abgabe aus A hat den Bestand bezahlt (72 mg/dl) -
        // ohne weitere Abgaben baut der Zufluss ihn wieder auf.
        maxSmbU = 0.0
        var wieder = false
        repeat(20) { if ((cycle().evidenceCreditMgdlPerMin ?: 0.0) > 0.0) wieder = true }
        assertTrue(wieder) { "Kredit muss sich erholen" }

        // MESSZYKLUS B: offene Transportzeile 0,10 U - die finale Menge muss
        // um exakt diesen Betrag kleiner sein.
        l.onPublished("transport-attest", 0.10, clock, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
        maxSmbU = 5.0
        val mit = cycle()
        assertTrue((mit.evidenceCreditMgdlPerMin ?: 0.0) > 0.0) { "Kredit muss in B fliessen: ${mit.evidencePhase}/${mit.evidenceReason}" }
        assertEquals(0.70, mit.decision.smbU, 1e-9) {
            "B: Transportabzug muss die finale Menge um 0,10 verengen: ${mit.decision}"
        }
    }
    /**
     * DIE ZYKLUSFREIE STRECKE (Abschluss-Audit 15.08., Fensterregel): wie
     * der Trail-Fall oben, aber zwischen dem zweiten Druck und dem
     * Deckelende laeuft KEIN Zyklus - der Erben-Zweig hat den Druck nie
     * gesehen (realer Ausloeser: CGM-Ausfall, der Loop wird von BG-Werten
     * getrieben). Ohne die Fensterregel eroeffnete der erste Zyklus nach
     * dem Deckelende daraus eine Folgeepisode mit frischem 360-Deckel.
     */
    @Test
    fun `ein Druck vor einer Zyklusluecke eroeffnet nach dem Deckelende keine Folgeepisode`(@TempDir dir: File) {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAuthorized = true

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)

        markerAt = start + 2 * 60_000L
        clock = start
        repeat(8) { cycle() }
        val anker = l.episodes.evidenceEpisodeId
        assertTrue(anker > 0L, "Episode steht")

        // Zweiter Druck IM Fenster - aber danach kommt KEIN Zyklus mehr,
        // bis der Vorgaenger am Deckel raus ist.
        val zweiterDruck = clock + 20 * 60_000L
        markerAt = zweiterDruck
        clock = anker + (EvidenceStock.Config().maxEpisodeMin + 5) * 60_000L
        val o = (1..3).map { cycle() }.last()

        assertTrue(l.episodes.evidenceEpisodeId == anker || l.episodes.evidenceEpisodeId == 0L) {
            "keine Folgeepisode aus dem ungesehenen Druck: ${l.episodes.evidenceEpisodeId}"
        }
        assertEquals("MARKER_ALREADY_CONSUMED", o.evidenceEpisodeDenial)
        assertEquals(zweiterDruck, l.episodes.lastConsumedMarkerTs, "der Druck ist verbraucht, nicht vergessen")

        // Ein NEUER bewusster Druck (nach dem Deckelende) eroeffnet weiter.
        val dritterDruck = clock + 60_000L
        markerAt = dritterDruck
        repeat(3) { cycle() }
        assertEquals(dritterDruck, l.episodes.evidenceEpisodeId, "neuer Druck, neue Episode")
    }

    // ---- Die Armierung des Mahlzeitenfundaments (Toni 19.08.) -------------

    /**
     * DIE ARMIERUNG HAENGT AN DER PRIME-EPISODE, NICHT AN DER EVIDENZEPISODE.
     *
     * MEIN ERSTER WURF HING SIE AN `episodeGate.opened` - und das ist die
     * EVIDENZ-Episode, die bis zu 360 Minuten laeuft. Das Fundament gehoert
     * aber zum Prime-/Markerbudget, das mit `startsNewEpisode` neu bewaffnet
     * wird. Zwei Folgen, beide belegt durch die Tests hier:
     *
     *   ein Abbruch nach der Evidenzeroeffnung liess die Armierung dauerhaft
     *   ausfallen;
     *
     *   und ein zweiter Druck nach Ablauf des Primefensters setzte Prime
     *   zurueck, ohne das Fundament neu zu armieren - Prime laese dann ueber
     *   `primeBudgetU` weiter das ALTE gepinnte Phase-A-Budget.
     */
    @Test
    fun `ein Abbruch verliert die Armierung nicht - der naechste Zyklus holt sie nach`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        // Erster Zyklus nach dem Druck: kein Profil -> Abbruch.
        whenever(profileFunction.getProfile(any())).thenReturn(null)
        val abgebrochen = cycle()
        assertTrue(abgebrochen.abortReason != null, "der Aufbau MUSS abbrechen")
        assertTrue(
            !ledger.episodes.foundation.valid,
            "im Abbruchzyklus entsteht keine Autorisierung",
        )

        // Naechster gesunder Zyklus. Mehrere, weil der Marker anfangs in der
        // Zukunft liegt und der Observer erst READY werden muss.
        whenever(profileFunction.getProfile(any())).thenReturn(validProfile)
        repeat(8) { cycle() }
        assertTrue(
            ledger.episodes.foundation.valid,
            "die Armierung MUSS nachgeholt werden - sonst ist sie dauerhaft weg",
        )
        assertEquals(
            markerAtIntern, ledger.episodes.foundation.armedTs,
            "und zwar fuer genau diesen Markerdruck",
        )
    }

    /** UND GENAU EINMAL - nicht bei jedem Folgezyklus neu. */
    @Test
    fun `die Armierung geschieht genau einmal`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(6) { cycle() }
        val ersteArmierung = ledger.episodes.foundation.armedTs
        // Ein Bezahlstand, den eine erneute Armierung nullen wuerde.
        ledger.episodes.deliveredSinceHandoverU = 0.42
        repeat(4) { cycle() }

        assertEquals(ersteArmierung, ledger.episodes.foundation.armedTs, "dieselbe Autorisierung")
        assertEquals(
            0.42, ledger.episodes.deliveredSinceHandoverU, 1e-9,
            "eine zweite Armierung haette den Bezahlstand genullt",
        )
    }

    /**
     * EIN ZWEITER DRUCK NACH ABLAUF DES PRIMEFENSTERS ARMIERT NEU - auch wenn
     * dieselbe Evidenzepisode weiterlaeuft.
     *
     * Das ist der Fall, den `episodeGate.opened` nicht erwischt haette.
     */
    @Test
    fun `ein zweiter Druck erzeugt eine neue gepinnte Autorisierung`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(5) { cycle() }
        val ersteArmierung = ledger.episodes.foundation.armedTs
        assertTrue(ersteArmierung > 0L, "die erste Autorisierung MUSS stehen")

        // Weit hinter das Markerfenster - die Prime-Episode ist abgelaufen,
        // die Evidenzepisode (360 min) laeuft weiter.
        clock += (OnsetChannel.MARKER_WINDOW_MIN + 5) * 60_000L
        markerAt = clock + 60_000L
        repeat(4) { cycle() }

        assertTrue(
            ledger.episodes.foundation.armedTs > ersteArmierung,
            "der zweite Druck MUSS eine NEUE Autorisierung erzeugen - sonst laese " +
                "Prime weiter das alte gepinnte Phase-A-Budget",
        )
    }

    // ---- Die Ruecknahme ---------------------------------------------------

    /**
     * EINE RUECKNAHME BEENDET DAS FUNDAMENT SOFORT.
     *
     * Ohne das bliebe die gepinnte Autorisierung gueltig, und der Snapshot
     * lieferte weiter PHASE_B mit `dueU > 0`. Dass `manualMarkerAuthorized`
     * danach false ist, hilft nicht: der Lift liest die GEPINNTE
     * Autorisierung - genau wie es der Pinning-Vertrag verlangt.
     */
    @Test
    fun `eine Ruecknahme beendet Autorisierung und Bezahlstand`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(5) { cycle() }
        assertTrue(ledger.episodes.foundation.valid, "die Autorisierung MUSS stehen")
        ledger.episodes.deliveredSinceHandoverU = 0.20
        ledger.episodes.descentDeferredPhaseAU = 0.40

        // DIE RUECKNAHME: der Marker wird zurueckgenommen.
        markerAt = 0L
        val o = cycle()

        assertTrue(
            !ledger.episodes.foundation.valid,
            "nach der Ruecknahme darf keine Autorisierung mehr stehen",
        )
        assertEquals(
            0.0, ledger.episodes.deliveredSinceHandoverU, 1e-9,
            "und der Bezahlstand faellt mit - er bedeutet ohne sie nichts",
        )
        assertEquals(
            0.0, ledger.episodes.descentDeferredPhaseAU, 1e-9,
            "auch ein Sicherheitsaufschub gehoert nur zu dieser Autorisierung",
        )
        assertEquals(
            MealFoundation.Phase.NONE, o.mealFoundation.phase,
            "der Export MUSS das sofort zeigen",
        )
        assertEquals(0.0, o.mealFoundation.dueU, 1e-9, "und nichts mehr fordern")
    }

    /**
     * DIE BEZAHLSTAENDE HABEN VERSCHIEDENE LEBENSDAUERN - und das ist
     * KEIN Widerspruch (Codex-Rueckfrage 19.08., hier beantwortet).
     *
     * DIE FRAGE. Fuer den Phase-A-Rueckstand wurde
     * `deliveredFromBudget - deliveredSinceHandover` gerechnet, und
     * `deliveredFromBudget` ist produktiv `evidenceCommittedU`. Vorgeschlagen
     * war, `deliveredSinceHandover > deliveredFromBudget` als Korruption zu
     * behandeln - fail-closed im Kern, `require` im Codec.
     *
     * DAS WAERE EIN SELBSTGEBAUTER AUSFALL GEWESEN. Die beiden Zaehler
     * wachsen unter UNABHAENGIGEN Bedingungen (`FuseCycleRunner.buche`):
     *
     *     if (phase == PHASE_B)        deliveredSinceHandoverU += actuatedU
     *     if (evidenceEpisodeId > 0L)  evidenceCommittedU      += actuatedU
     *
     * und `MarkerEpisodeGate` liefert `episodeId = 0` bei jeder Ablehnung -
     * unter anderem `MARKER_ALREADY_CONSUMED`, also beim ZWEITEN Markerdruck
     * innerhalb des 360-Minuten-Deckels. Die Mahlzeiten-Autorisierung haengt
     * ausdruecklich NICHT an diesem Tor (s. den Kommentar an der
     * Armierungsstelle): sie wird trotzdem armiert.
     *
     * Ergebnis: eine voellig gesunde zweite Mahlzeit laeuft mit
     * `evidenceCommittedU == 0` und wachsendem `deliveredSinceHandoverU`. Der
     * Kern haette dort geschwiegen, der Codec die Generation verworfen und
     * die Aktuation in den RECOVERY_HOLD geschickt.
     *
     * DESHALB FUEHRT DAS FUNDAMENT SEINEN EIGENEN PHASE-A-ZAEHLER
     * ([EpisodeBudgets.deliveredPhaseAU]) - die Alternative, die in der
     * Rueckfrage selbst benannt ist. Dieser Test haelt den Grund fest, damit
     * der Riegel nicht spaeter aus guten Absichten nachgereicht wird.
     */
    @Test
    fun `ohne Evidenzepisode waechst nur der Bezahlstand - kein Widerspruch`() {
        fundamentAn = true
        flach = 180.0
        steigungProMin = 2.5           // echter Mahlzeitenanstieg, es fliesst etwas
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        // DER MARKER GILT ALS VERBRAUCHT -> MARKER_ALREADY_CONSUMED,
        // episodeId = 0. Genau die Lage der ZWEITEN Mahlzeit im Deckel.
        ledger.episodes.lastConsumedMarkerTs = markerAt

        // Weit hinter die Uebergabe, damit Phase B laeuft und bucht.
        repeat(40) { cycle() }

        assertTrue(
            ledger.episodes.foundation.valid,
            "die Autorisierung MUSS trotz verbrauchtem Marker stehen - sonst prueft der Test nichts",
        )
        assertEquals(
            0.0, ledger.episodes.evidenceCommittedU, 1e-9,
            "ohne Evidenzepisode waechst dieser Zaehler gar nicht",
        )
        assertTrue(
            ledger.episodes.deliveredSinceHandoverU > 0.0,
            "waehrend Phase B sehr wohl bucht: ${ledger.episodes.deliveredSinceHandoverU}",
        )
        // UND DAMIT DIE BEZIEHUNG, die als Korruption gelten sollte:
        assertTrue(
            ledger.episodes.deliveredSinceHandoverU > ledger.episodes.evidenceCommittedU + 1e-9,
            "der 'Widerspruch' ist ein gesunder Betriebszustand",
        )
    }

    /**
     * UND SIE LOESCHT DEN PHASE-B-UEBERTRAG MIT (Toni 19.08.).
     *
     * Der Uebertrag gehoert zu der Autorisierung, die hier gerade endet.
     * Bliebe er stehen, gaebe der ausdrueckliche Widerruf der NAECHSTEN
     * Mahlzeit zusaetzliches Insulin fuer eine Luecke aus der widerrufenen -
     * der Widerruf haette dann MEHR Insulin zur Folge als das Zulassen.
     *
     * UEBER DEN ECHTEN WEG, nicht ueber einen nachgebauten Zustand: der
     * Runner laeuft, der Marker wird zurueckgenommen, und geprueft wird, was
     * danach im Ledger steht. Ein von Hand kopierter Zustand hat in dieser
     * Baustelle schon einmal ein Feld vergessen und den Test gruen gehalten.
     */
    @Test
    fun `eine Ruecknahme loescht auch den Phase-B-Uebertrag`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(5) { cycle() }
        assertTrue(ledger.episodes.foundation.valid, "die Autorisierung MUSS stehen")
        // Eine belegte Phase-A-Luecke, wie sie ein Nicht-Sende-Beweis
        // hinterlaesst.
        ledger.episodes.confirmedNotSentPhaseAU = 0.30
        ledger.episodes.descentDeferredPhaseAU = 0.40

        markerAt = 0L
        cycle()

        assertEquals(
            0.0, ledger.episodes.confirmedNotSentPhaseAU, 1e-9,
            "der Uebertrag faellt mit der Autorisierung",
        )
    }

    /**
     * EIN NEUER MARKERDRUCK ERBT DEN UEBERTRAG NICHT.
     *
     * Der zweite Weg, eine Episode zu beenden - und er braucht seine eigene
     * Ruecksetzung: das Armen laeuft an einer voellig anderen Stelle als der
     * Widerruf. Eine neue Mahlzeit bekommt ihr eigenes Budget; die Luecke der
     * vorigen ist mit deren Fenster verfallen.
     */
    @Test
    fun `ein neuer Markerdruck erbt den Uebertrag nicht`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(5) { cycle() }
        assertTrue(ledger.episodes.foundation.valid, "die erste Autorisierung MUSS stehen")
        ledger.episodes.confirmedNotSentPhaseAU = 0.30
        val ersteArmierung = ledger.episodes.foundation.armedTs

        // Hinter das Markerfenster - sonst gilt die alte Episode als laufend
        // und es wird gar nicht neu armiert (dann pruefte der Test nichts).
        clock += (OnsetChannel.MARKER_WINDOW_MIN + 5) * 60_000L
        markerAt = clock + 60_000L
        repeat(6) { cycle() }

        assertTrue(
            ledger.episodes.foundation.valid &&
                ledger.episodes.foundation.armedTs != ersteArmierung,
            "es MUSS wirklich neu armiert worden sein",
        )
        assertEquals(
            0.0, ledger.episodes.confirmedNotSentPhaseAU, 1e-9,
            "und die neue Mahlzeit beginnt ohne fremde Luecke",
        )
        assertEquals(
            0.0, ledger.episodes.descentDeferredPhaseAU, 1e-9,
            "und ohne Sicherheitsaufschub der vorigen Mahlzeit",
        )
    }

    /** Und ein erneuter bewusster Druck armiert danach neu. */
    @Test
    fun `nach einer Ruecknahme armiert ein neuer Druck wieder`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(5) { cycle() }
        markerAt = 0L
        repeat(2) { cycle() }
        assertTrue(!ledger.episodes.foundation.valid, "zurueckgenommen")

        // Hinter das Markerfenster - sonst gilt die Prime-Episode als noch
        // laufend und `startsNewEpisode` waere falsch.
        clock += (OnsetChannel.MARKER_WINDOW_MIN + 5) * 60_000L
        markerAt = clock + 60_000L
        repeat(6) { cycle() }
        assertTrue(
            ledger.episodes.foundation.valid,
            "ein neuer bewusster Druck MUSS wieder armieren",
        )
    }

    /**
     * EIN VORGEFUNDENER MARKER ARMIERT NICHT (Toni 19.08.).
     *
     * Der Markerzeitpunkt liegt in einer Preference und ueberlebt jeden
     * Neustart; nur `markerPressObservedTs` sagt, ob DIESER Prozess den Druck
     * gesehen hat. Ein beim Warmstart vorgefundener Marker duerfte kein
     * rueckwirkendes Fundament erzeugen: dessen Phase A waere laengst vorbei,
     * und Phase B faende ein Budget vor, aus dem schon geliefert wurde.
     *
     * Das Rig setzt `markerPress` beim Setzen von `markerAt` automatisch mit -
     * genau deshalb muss dieser Test ihn von Hand auf 0 zuruecknehmen. Ohne
     * ihn blieb die Zusicherung ungeprueft: eine Mutationsprobe
     * (pressObservedInThisProcess = true) blieb gruen.
     */
    @Test
    fun `ein vorgefundener Marker armiert nicht`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L
        // Der Druck stammt aus einem FRUEHEREN Prozess.
        markerPress = 0L

        clock = start
        repeat(8) { cycle() }
        assertTrue(
            !ledger.episodes.foundation.valid,
            "ohne eigene Beobachtung des Drucks darf nichts armiert werden",
        )

        // UND DIE GEGENPROBE: mit Beobachtung armiert derselbe Aufbau.
        //
        // Sie braucht eine NEUE Prime-Episode: der erste Durchlauf hat
        // `primeArmedTs` bereits gesetzt (der Reset laeuft unabhaengig von
        // der Armierung), also waere `neueEpisode` sonst falsch. Das ist
        // richtig so - die Episode laeuft ja.
        clock += (OnsetChannel.MARKER_WINDOW_MIN + 5) * 60_000L
        markerAt = clock + 60_000L
        repeat(6) { cycle() }
        assertTrue(
            ledger.episodes.foundation.valid,
            "mit Beobachtung MUSS es armieren - sonst prueft der Test oben nichts",
        )
    }

    /**
     * NICHT IRGENDEIN DRUCK - GENAU DIESER (Toni 19.08.).
     *
     * Die Bedingung lautete `markerPressObserved() > 0L`. Damit haette ein
     * frueher beobachteter Druck aus einer laengst beendeten Mahlzeit
     * gereicht, um einen SPAETER vorgefundenen zu autorisieren - also genau
     * die Lage, gegen die die Beobachtung gebaut ist.
     */
    @Test
    fun `ein fremder beobachteter Druck autorisiert den aktuellen nicht`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true

        // Druck A: beobachtet.
        markerAt = start + 2 * 60_000L
        val druckA = markerAtIntern
        clock = start
        repeat(6) { cycle() }
        assertTrue(ledger.episodes.foundation.valid, "A wurde beobachtet und armiert")

        // Neue Prime-Episode, Druck B - aber beobachtet ist weiterhin nur A.
        clock += (OnsetChannel.MARKER_WINDOW_MIN + 5) * 60_000L
        markerAt = clock + 60_000L
        markerPress = druckA
        repeat(8) { cycle() }
        assertTrue(
            !ledger.episodes.foundation.valid,
            "ein FREMDER beobachteter Druck darf B nicht autorisieren",
        )
    }

    /**
     * DIE GEGENPROBE, eigenstaendig: DERSELBE Druck beobachtet -> Armierung.
     *
     * Sie steht bewusst als eigener Test und nicht als dritte Stufe des
     * Tests darueber: mit langer Vorgeschichte haengt sie an Zustaenden, die
     * mit der Frage nichts zu tun haben, und ein Fehlschlag saehe dann aus
     * wie eine Aussage ueber die Identitaetspruefung.
     */
    @Test
    fun `derselbe beobachtete Druck autorisiert`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(8) { cycle() }
        assertEquals(
            markerAtIntern, markerPress,
            "der Aufbau MUSS denselben Druck beobachtet haben",
        )
        assertTrue(ledger.episodes.foundation.valid, "und dann armiert er")
    }

    /**
     * RUECKNAHME UND ERNEUTER DRUCK IN DERSELBEN PRIME-EPISODE ARMIEREN NICHT
     * NEU - eine bewusste Grenze, kein Fehler (Toni 19.08.).
     *
     * Die Armierung haengt an `MarkerEpisode.startsNewEpisode`, und die ist
     * innerhalb des 90-Minuten-Fensters falsch. Das verhindert ein
     * DOPPELBUDGET: sonst koennte Ruecknahme plus erneuter Druck dieselbe
     * Huelle ein zweites Mal freigeben.
     *
     * DER PREIS steht hier ausdruecklich: nach einer VERSEHENTLICHEN
     * Ruecknahme faellt Phase B bis zur naechsten Prime-Episode aus. Der
     * Marker selbst wirkt sofort wieder (Prime, Sonderrechte), das Fundament
     * nicht. Wer das aendern will, braucht eine Unterscheidung zwischen
     * "widerrufen" und "versehentlich" - die es heute nicht gibt, und die
     * ohne sie ein Doppelbudget waere.
     */
    @Test
    fun `nach Ruecknahme armiert ein Druck in derselben Prime-Episode nicht neu`() {
        fundamentAn = true
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(6) { cycle() }
        assertTrue(ledger.episodes.foundation.valid, "armiert")

        markerAt = 0L
        repeat(2) { cycle() }
        assertTrue(!ledger.episodes.foundation.valid, "die Ruecknahme beendet sie")

        // Erneuter Druck INNERHALB des 90-Minuten-Fensters.
        markerAt = clock + 60_000L
        repeat(6) { cycle() }
        assertTrue(
            !ledger.episodes.foundation.valid,
            "in derselben Prime-Episode entsteht KEIN neues Fundament - sonst " +
                "gaebe dieselbe Huelle ein zweites Mal frei",
        )
    }

    /**
     * DER SCHALTER AUS BLEIBT VERHALTENSGLEICH.
     *
     * Ohne diese Probe waere jede andere hier wertlos: sie zeigt, dass der
     * ganze Baustein im Auslieferungszustand nichts tut.
     */
    @Test
    fun `bei ausgeschaltetem Fundament entsteht keine Autorisierung`() {
        fundamentAn = false
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(8) { cycle() }
        assertTrue(!ledger.episodes.foundation.valid, "Schalter aus - keine Autorisierung")
        assertEquals(0.0, ledger.episodes.deliveredSinceHandoverU, 1e-9)
    }

    // ==== PUNKT 3: DER ECHTE TRANSPORT-E2E ==================================
    //
    // WAS HIER ANDERS IST ALS IM ZURUECKGEZOGENEN BELEG. Der lief ueber
    // direkte `revokeSettled`-Aufrufe und hat damit nur seine eigene
    // Arithmetik geprueft. Hier laeuft die ECHTE Kette, in der Reihenfolge aus
    // `FusePlugin.invoke`:
    //
    //     NotSentProof (Beleg ueber den VORIGEN Zyklus, VOR dem Lauf)
    //       -> runner.run()
    //       -> LedgerPublicationGate.publish
    //            events{}: onProvenNotSent + revokeSettled + onPublished
    //            DANN der verifizierte Persist - INNERHALB des Gates
    //       -> resolveReservation(computeTs, publizierteMenge, cycleId)
    //       -> published*-Felder fortschreiben
    //
    // DER CRASH-RAND, richtiggestellt (Codex 19.08.): der Persist liegt IM
    // Gate und damit VOR `resolveReservation` und vor den published*-Feldern -
    // nicht am Ende der Kette, wie hier zuerst stand. Fuer die Rueckbuchung
    // aendert das nichts: sie geschieht im `events`-Block, also VOR dem
    // Persist, und ist deshalb durabel, sobald das Gate gesiegelt hat. Was
    // NACH dem Persist stirbt, verliert die Aufloesung der Reservierung - und
    // das ist der gewollte UNKNOWN-Ausgang: die Belastung bleibt stehen.
    //
    // DIE EINE TESTGRENZE, ausdruecklich benannt: `priorActuation` liest
    // produktiv `loop.lastRun` aus AAPS. Diese beiden Beobachtungswerte -
    // `aapsConstrainedU` und `smbSetByPumpPresent` - setzt der Test direkt.
    // Es sind genau die Groessen, die im AAPS-Log stehen; alles DAHINTER
    // laeuft echt. Der Rest der Kette ist nicht nachgebaut.

    /** Was AAPS mit der Menge DIESES Zyklus tut - ausgewertet im naechsten. */
    private enum class Ausgang {
        /** Regelfall: die Menge ging hinaus. */
        GESENDET,

        /** AAPS hat nach seinen Constraints exakt 0 uebrig gelassen. */
        CONSTRAINT_NULL,

        /** Menge positiv, Apply-Block nie betreten - Tonis 19:07-Fall. */
        NIE_KOMMANDIERT,

        /** Kein auswertbarer Befund. Der sichere Ausgang: nichts gilt als
         *  bewiesen, die Buchung bleibt stehen. */
        UNKLAR,

        /**
         * DIE ZWEITE GESTALT DES UNKLAREN AUSGANGS: die Beobachtung SAEHE aus
         * wie ein Beweis, gehoert aber nachweislich zu einem anderen Lauf
         * (`correlated = false`).
         *
         * SIE BRAUCHT EINEN EIGENEN WERT, und das hat erst eine
         * Mutationsprobe gezeigt: mit nur [UNKLAR] blieb der Test gruen, als
         * die Korrelationspruefung aus [NotSentProof] entfernt wurde - dort
         * sind naemlich ohnehin alle Werte nicht auswertbar. Geprueft wurde
         * damit die Auswertbarkeit, nicht die Zuordnung.
         */
        UNKORRELIERT,
    }

    private var letzterAusgang = Ausgang.GESENDET
    private var letzteMengeU: Double? = null
    private var pPropId: String? = null
    private var pStripped = false
    private var pSealed = false
    private var pPersistFailed = false

    /** Der letzte gebildete Beleg - fuer Zusicherungen ueber den GRUND. */
    private var letzterGrund: QueueRejectReason? = null

    private fun transportReset() {
        letzterAusgang = Ausgang.GESENDET
        letzteMengeU = null
        pPropId = null
        pStripped = false
        pSealed = false
        pPersistFailed = false
        letzterGrund = null
    }

    /**
     * EIN vollstaendiger Zyklus durch Runner, Gate und Beweis.
     *
     * @param ausgang was mit der Menge DIESES Zyklus geschieht. Ausgewertet
     *   wird er beim NAECHSTEN Aufruf - genau wie produktiv, wo der Befund
     *   erst im Folgezyklus sichtbar ist.
     * @param kennungVerbiegen greift in die uebergebene Kennung ein, um den
     *   Fall "fremde proposalId" zu erzeugen.
     */
    private fun transport(
        dir: File,
        ausgang: Ausgang = Ausgang.GESENDET,
        kennungVerbiegen: (String) -> String = { it },
    ): FuseCycleRunner.Outcome {
        // (1) DER BELEG UEBER DEN VORIGEN ZYKLUS - vor dem Lauf gebildet,
        // solange die published*-Felder noch den Vorgaenger beschreiben.
        val claim = pPropId
            ?.takeIf { ledger.hasOpenProposal(it) }
            ?.let { id ->
                NotSentProof.reasonFor(
                    NotSentProof.Observation(
                        correlated = letzterAusgang != Ausgang.UNKLAR &&
                            letzterAusgang != Ausgang.UNKORRELIERT,
                        ledgerPublishedU = ledger.publishedAmountOf(id),
                        gateStripped = pStripped,
                        gateSealed = pSealed,
                        gatePersistFailed = pPersistFailed,
                        aapsConstrainedU = when (letzterAusgang) {
                            Ausgang.CONSTRAINT_NULL -> 0.0
                            Ausgang.UNKLAR          -> null
                            else                    -> letzteMengeU   // auch UNKORRELIERT
                        },
                        smbSetByPumpPresent = when (letzterAusgang) {
                            Ausgang.NIE_KOMMANDIERT -> false
                            // SAEHE aus wie ein Beweis - nur die Zuordnung fehlt.
                            Ausgang.UNKORRELIERT    -> false
                            Ausgang.UNKLAR          -> null
                            else                    -> true
                        },
                    )
                )?.let { grund -> id to grund }
            }
        letzterGrund = claim?.second

        // (2) DER ECHTE ZYKLUS.
        val o = cycle()
        val cycleId = kennungVerbiegen("e2e#${o.computeTs}")
        val units = o.decision.smbU.takeIf { it > 0.0 }
        val rt = RT(
            algorithm = APSResult.Algorithm.FUSE, timestamp = o.computeTs,
            rate = null, duration = null, units = units,
            deliverAt = units?.let { o.computeTs },
        )

        // (3) DAS ECHTE PUBLIKATIONSGATE, mit dem echten events-Block.
        val expected = LedgerPublicationGate.commitmentOf(
            units = rt.units, treatmentViewPresent = true, proposalId = cycleId,
        )
        val publication = LedgerPublicationGate.publish(
            rt = rt, adapter = ledger, dir = dir, expected = expected,
            published = InterventionStamp.Published(smbU = rt.units, tbrChanged = o.tbrChanged),
            events = {
                // ZUERST entlasten, DANN die neue Menge buchen - die
                // Reihenfolge des Plugins.
                claim?.let { (id, grund) ->
                    if (ledger.hasOpenProposal(id)) ledger.onProvenNotSent(id, grund)
                    ledger.revokeSettled(id)
                }
                if (expected is LedgerPublicationGate.Commitment.Proposal && rt.units != null)
                    ledger.onPublished(
                        proposalId = cycleId, unitsU = rt.units!!, decisionTs = o.computeTs,
                        latestBolusTs = clock, bolusStepU = 0.05,
                    )
            },
        )

        // (4) DIE RESERVIERUNG AUFLOESEN - nach dem Gate, mit der publizierten
        // Menge.
        ledger.resolveReservation(o.computeTs, publication.rt.units ?: 0.0, proposalId = cycleId)

        // (5) DEN ZUSTAND FUER DEN NAECHSTEN ZYKLUS FORTSCHREIBEN.
        pPropId = cycleId.takeIf { ledger.hasOpenProposal(it) }
        pStripped = !publication.allowed && rt.units != null
        pSealed = publication.sealed
        pPersistFailed = !publication.sealed
        letzterAusgang = ausgang
        letzteMengeU = publication.rt.units
        return o
    }

    /** Der Zustand NACH einem Prozessneustart - aus der Datei, nicht aus dem
     *  Speicher. Die Probe darauf, dass ein Befund durabel ist. */
    private fun nachNeustart(dir: File): EpisodeBudgets =
        FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", clock) }.episodes

    /** Ein armiertes Mahlzeitenfenster mit steigendem Zucker - der Aufbau,
     *  in dem Phase A ueberhaupt etwas bucht. */
    private fun mahlzeit(dir: File) {
        fundamentAn = true
        flach = 180.0
        steigungProMin = 2.5
        markerAuthorized = true
        markerAt = start + 2 * 60_000L
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
    }

    /**
     * DEN BEWEISZYKLUS RUHIG STELLEN.
     *
     * WARUM DAS NOETIG IST - und es ist ein Befund ueber das RIG, nicht ueber
     * den Regler: bucht der Zyklus, in dem der Beweis wirkt, gleichzeitig eine
     * NEUE Menge, dann bewegen sich dieselben Zaehler aus zwei Gruenden. Die
     * erste Fassung dieser Tests hat daraus "der Zaehler ist unveraendert"
     * gelesen, obwohl Entlastung und Neubuchung sich nur aufhoben. Genau die
     * Sorte Testartefakt, an der der erste E2E gescheitert ist.
     *
     * Flach und ohne Anstieg fordert der Regler nichts an; die Zaehler
     * aendern sich dann ausschliesslich durch die Entlastung. Die Tests
     * pruefen das ausdruecklich nach, statt es zu unterstellen.
     */
    private fun ruhigStellen() {
        flach = 100.0
        steigungProMin = 0.0
    }

    /** Bis zur ersten wirklich gebuchten Phase-A-Menge fahren. */
    private fun bisPhaseABuchung(dir: File, maxZyklen: Int = 12): FuseCycleRunner.Outcome {
        repeat(maxZyklen) {
            val o = transport(dir)
            if (o.decision.smbU > 0.0 &&
                ledger.episodes.settled?.foundationPhase == MealFoundation.Phase.PHASE_A
            ) return o
        }
        throw AssertionError("kein Phase-A-Zyklus mit Menge - der Aufbau traegt den Test nicht")
    }

    // ---- PHASE-A-SOFORTANTEIL (iLet-Prinzip, v28) --------------------------
    //
    // Pflichttest 1 (UpfrontShare=0 -> Bitgleichheit) traegt die GESAMTE
    // uebrige Suite: jeder andere Test laeuft mit upfrontAnteil = 0.0, und
    // upfrontFloorU(share 0) ist 0 - der Lift existiert dann nicht.
    // Pflichttest 13/14/15 (Ruecknahme verwirft, vorgefundener Marker
    // armiert nicht, neuer Marker erbt nichts) tragen die bestehenden
    // Episodenvertraege: der Boden haengt an der Autorisierung (Ruecknahme
    // -> none()), arm() verlangt den im Prozess beobachteten Druck, und
    // der Episodenneustart nullt deliveredPhaseAU - zusammen mit dem
    // upfrontFloorU-Bilanztest ist der frische Boden die Folge, keine
    // eigene Mechanik.

    /** Armierter Marker in RUHIGER Lage: der Normalpfad fordert nichts
     *  (NO_DEMAND), die Sofortdosis kommt allein aus der Autorisierung -
     *  der Bauauftrags-Kernfall "kein normaler Korrekturbedarf noetig". */
    private fun upfrontMahlzeit(dir: File, anteil: Double) {
        upfrontAnteil = anteil
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        // SICHERHEITSAUFLAGE (Toni): die Sofortdosis verlangt das aktive
        // DeferredPrime-Sicherheitsnetz - ohne es fail-closed.
        aufschubAn = true
        mahlzeit(dir)
        ruhigStellen()
    }

    /**
     * DER LIVE-PFLICHTFALL DES SOFORT-BATCHES (Nachtrag Toni 25.08.
     * mittags, Punkt 8): 3,20 geplant -> Riegel -> 0,60 normal geliefert
     * -> Erholung -> GENAU 2,60 als EIN Batch.
     *
     * GEMESSEN WAR: 0,20 / 0,15 / 0,25 U in drei Zyklen - der
     * zurueckgehaltene Sofortanteil lag im generischen DeferredPrime, und
     * der gibt hoechstens einen Pumpenschritt je Zyklus frei. Dieselbe
     * Messung zeigte den zweiten Fehler: der Aufschub meldete 3,10 U
     * offen, obwohl nach 0,60 U Lieferung hoechstens 2,60 U offen sein
     * konnten - er zog nur seine eigenen Freigaben ab.
     *
     * Die Haeppchenfolge muss diesen Test VERFEHLEN: geprueft wird eine
     * EINZELNE Anforderung ueber 2,60 U, nicht eine Summe.
     */
    @Test
    fun `der aufgeschobene sofortanteil kommt als ein batch zurueck`(@TempDir dir: File) {
        // LIVE-FENSTER 20 min (Review 25.08. abends): der fruehere Wert 40
        // machte den Test wertlos - er verschob den Phasenwechsel hinter
        // die Erholung und pruefte damit genau den kritischen Fall nicht.
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        upfrontAnteil = 1.0
        primeHuelleU = 4.0       // Tonis Huelle -> Phase A 3,20, Fundament 0,80
        fundamentAnteil = 0.8
        aufschubAn = true
        maxSmbU = 0.30           // darf den Batch NICHT zerteilen
        mahlzeit(dir)

        // (1) DER RIEGEL: ein gemessenes, ueberdecktes Abwaertsrisiko haelt
        // den Batch vollstaendig zurueck - keine Miniabgaben aus diesem
        // Bestand. Dieselbe Lage wie im Neustart-Pflichttest.
        flach = 150.0
        steigungProMin = -3.0
        bolusIobU = 2.0
        val imRiegel = (0 until 13).map { transport(dir) }
        assertTrue(imRiegel.all { it.phaseAUpfrontRequestedU == 0.0 }) {
            "im Riegel darf NICHTS aus dem Batch fliessen - " +
                imRiegel.joinToString(" ") { "${it.phaseAUpfrontRequestedU}/${it.phaseAUpfrontState}" }
        }
        assertTrue(imRiegel.any { it.phaseAUpfrontState == "DEFERRED_UPFRONT_BATCH" }) {
            "und der Zustand ist EIGEN typisiert - " +
                imRiegel.map { it.phaseAUpfrontState }.distinct().joinToString(" ")
        }
        assertEquals(3.20, imRiegel.last().phaseAUpfrontPendingU, 1e-9, "vollstaendig offen")

        // (2) NORMALE PHASE-A-LIEFERUNG von 0,60 U verkleinert den Batch
        // SOFORT (Punkt 6) - hier direkt gebucht, wie ein gewoehnlicher SMB.
        ledger.episodes.deliveredPhaseAU += 0.60
        val nachLieferung = transport(dir)
        assertEquals(2.60, nachLieferung.phaseAUpfrontPendingU, 1e-9) {
            "3,20 geplant - 0,60 geliefert = 2,60 offen (gemeldet waren 3,10)"
        }

        // (3) DIE ERHOLUNG: erst nach bestaetigter Erholung oeffnet der
        // Batch - und dann als EIN Zug. Die Kurve wird steigend neu
        // verankert, die Uhr laeuft monoton weiter.
        steigungProMin = 0.8
        flach = 130.0 - 0.8 * ((clock - start) / 60_000.0)
        knickAbMin = null
        bolusIobU = null
        val nachher = (0 until 12).map { transport(dir) }
        val batchIdx = nachher.indexOfFirst { it.phaseAUpfrontRequestedU > 0.0 }
        assertTrue(batchIdx >= 0) {
            "nach der Erholung muss der Batch kommen - " +
                nachher.joinToString(" ") { "${it.phaseAUpfrontRequestedU}/${it.phaseAUpfrontState}" }
        }
        val batch = nachher[batchIdx]
        // DIE INVARIANTE: angefordert wird GENAU der Rest, den der Zyklus
        // davor als offen ausgewiesen hat - in EINEM Zug. Die gemessene
        // Haeppchenfolge 0,20/0,15/0,25 verfehlt das doppelt: jede einzelne
        // Menge ist kleiner als der Rest, und es sind drei Zyklen.
        assertEquals(batch.phaseAUpfrontPendingU, batch.phaseAUpfrontRequestedU, 1e-9) {
            "der GANZE offene Rest desselben Zyklus in einem Zug - " +
                nachher.joinToString(" ") { "%.2f".format(it.phaseAUpfrontRequestedU) }
        }
        // Und der Groessenordnung nach ist es der Livefall: 3,20 geplant,
        // 0,60 gebucht. Dass es 2,45 statt 2,60 sind, ist Vertrag 6 in
        // Aktion - zwischen Buchung und Batch flossen regulaer 0,15 U in
        // Phase A, und die verkleinern den Batch SOFORT.
        assertTrue(batch.phaseAUpfrontRequestedU > 2.0) {
            "die Groessenordnung des Livefalls, nie ein Haeppchen: ${batch.phaseAUpfrontRequestedU}"
        }
        assertEquals("REQUESTED", batch.phaseAUpfrontState)
        // NACH dem Batch ist der Sofortanteil gedeckt - die Bilanz schliesst
        // ohne zweiten Zaehler. (Die Gesamtsumme in `deliveredPhaseAU`
        // liegt hoeher als der Plan, weil dieser Test die 0,60 direkt in
        // den Zaehler schreibt, ohne dass dafuer Insulin geflossen ist -
        // der Regler dosiert sein eigenes Budget davon unbeeindruckt aus.)
        assertEquals(0.0, nachher.last().phaseAUpfrontPendingU, 1e-9, "nichts bleibt offen")
        assertEquals("COVERED", nachher.last().phaseAUpfrontState)
        // Und exactly once: kein zweiter Batch derselben Menge.
        val weitere = nachher.dropWhile { it.phaseAUpfrontRequestedU <= 0.0 }.drop(1)
        assertTrue(weitere.all { it.phaseAUpfrontRequestedU <= 0.0 }) {
            "der Batch wird nicht wiederholt - " +
                weitere.joinToString(" ") { "%.2f".format(it.phaseAUpfrontRequestedU) }
        }
    }

    /**
     * DER FALL, DEN DER ERSTE PFLICHTTEST MASKIERT HAT (Review 25.08.
     * abends): die Erholung kommt erst NACH dem Ende von Phase A.
     *
     * `liftUpfront` liefert nur in Phase A - ein spaeter
     * Mehr-Einheiten-Batch ist ausdruecklich nicht gewollt. Die Menge
     * darf deshalb weder fliessen noch verschwinden: sie geht GENAU
     * EINMAL in den schrittweisen Aufschub ueber, unter dessen gepinnter
     * Frist. Der erste Wurf des Tests setzte das Prime-Fenster auf 40 min
     * und verschob damit den Phasenwechsel hinter die Erholung - er
     * pruefte genau diesen Fall nicht.
     */
    @Test
    fun `nach phase a kommt kein vollbatch sondern ein ueberrtrag`(@TempDir dir: File) {
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20) // LIVE
        upfrontAnteil = 1.0
        primeHuelleU = 4.0
        fundamentAnteil = 0.8
        aufschubAn = true
        maxSmbU = 0.30
        mahlzeit(dir)

        // Riegel LAENGER als das Prime-Fenster: der Phasenwechsel faellt in
        // die Riegelzeit, die Erholung liegt danach.
        flach = 150.0
        steigungProMin = -3.0
        bolusIobU = 2.0
        val imRiegel = (0 until 24).map { transport(dir) }
        assertTrue(imRiegel.all { it.phaseAUpfrontRequestedU == 0.0 }, "im Riegel fliesst nichts")

        // DER UEBERTRAG: genau ein Zyklus meldet ihn, danach ist der
        // Sofortanteil erledigt - nicht "gedeckt", sondern ueberfuehrt.
        val uebertrag = imRiegel.filter { it.phaseAUpfrontState == "TRANSFERRED_TO_DEFERRED" }
        assertTrue(uebertrag.isNotEmpty()) {
            "der Rest muss beim Phasenwechsel uebergehen - " +
                imRiegel.mapNotNull { it.phaseAUpfrontState }.distinct().joinToString(" ")
        }
        assertEquals(0.0, uebertrag.first().phaseAUpfrontPendingU, 1e-9, "danach nichts mehr sofort offen")
        // EXACTLY ONCE: der Uebertrag-Posten steht genau einmal.
        assertTrue(ledger.episodes.upfrontTransferredU > 2.0) {
            "die Menge ist verlustfrei ueberfuehrt: ${ledger.episodes.upfrontTransferredU}"
        }
        val nachUebertrag = ledger.episodes.upfrontTransferredU

        // DIE ERHOLUNG NACH T+20: kein Vollbatch mehr - der schrittweise
        // Pfad liefert, gebremst auf Pumpenschritte.
        steigungProMin = 0.8
        flach = 130.0 - 0.8 * ((clock - start) / 60_000.0)
        knickAbMin = null
        bolusIobU = null
        val nachher = (0 until 12).map { transport(dir) }
        assertTrue(nachher.all { it.phaseAUpfrontRequestedU == 0.0 }) {
            "nach Phase A darf KEIN Vollbatch kommen - " +
                nachher.joinToString(" ") { "%.2f".format(it.phaseAUpfrontRequestedU) }
        }
        assertEquals(nachUebertrag, ledger.episodes.upfrontTransferredU, 1e-9) {
            "und der Uebertrag geschieht genau EINMAL"
        }
        // Verlustfrei heisst: der schrittweise Pfad hat die Menge wirklich.
        assertTrue(nachher.any { it.deferredPrimeOpenU > 1.0 }) {
            "die Menge liegt im schrittweisen Aufschub - " +
                nachher.joinToString(" ") { "%.2f".format(it.deferredPrimeOpenU) }
        }
    }

    /**
     * DIE NACHLIEFERUNG IST BELEGT, nicht bloss gebucht (Review 25.08.
     * spaet, P1.1). Der vorige Test zeigte nur, dass `deferredPrimeOpenU`
     * gefuellt ist - das ist kein Beweis, dass die Menge je ankommt.
     *
     * Geprueft wird die ganze Kette: Ueberfuehrung -> Persist/Restore ->
     * bestaetigte Erholung -> Freigabe EXAKT in Pumpenschritten, kein
     * Vollbatch, kein zweiter Uebertrag.
     */
    @Test
    fun `die uebertragene menge wird schrittweise wirklich nachgeliefert`(@TempDir dir: File) {
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20) // LIVE
        upfrontAnteil = 1.0
        primeHuelleU = 4.0
        fundamentAnteil = 0.8
        aufschubAn = true
        maxSmbU = 0.30
        mahlzeit(dir)

        // Riegel ueber das Prime-Fenster hinaus -> Uebertrag am Phasenende.
        flach = 150.0
        steigungProMin = -3.0
        bolusIobU = 2.0
        val imRiegel = (0 until 24).map { transport(dir) }
        assertTrue(imRiegel.any { it.phaseAUpfrontState == "TRANSFERRED_TO_DEFERRED" }, "Uebertrag noetig")
        val uebertragenU = ledger.episodes.upfrontTransferredU
        assertTrue(uebertragenU > 1.0, "es muss etwas uebertragen sein: $uebertragenU")
        // P1.2: uebertragen ist NUR, was der Aufschub wirklich aufnahm.
        assertEquals(uebertragenU, ledger.episodes.deferredPrime.openU, 1e-9) {
            "uebertragen = real gebucht (verfallen: ${ledger.episodes.upfrontLapsedU})"
        }

        // NEUSTART mitten im Uebertrag - der Zustand muss aus der Datei kommen.
        assertTrue(ledger.persistVerified(dir), "versiegeln")
        val wieder = nachNeustart(dir)
        assertEquals(uebertragenU, wieder.upfrontTransferredU, 1e-9, "der Uebertrag ueberlebt")
        assertEquals(uebertragenU, wieder.deferredPrime.openU, 1e-9, "und die Menge im Aufschub")
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", clock) })

        // BESTAETIGTE ERHOLUNG nach dem Neustart.
        steigungProMin = 0.8
        flach = 130.0 - 0.8 * ((clock - start) / 60_000.0)
        knickAbMin = null
        bolusIobU = null
        val nachher = (0 until 20).map { transport(dir) }

        // KEIN Vollbatch - der Sofortpfad bleibt zu.
        assertTrue(nachher.all { it.phaseAUpfrontRequestedU == 0.0 }) {
            "nach Phase A nie ein Vollbatch - " +
                nachher.joinToString(" ") { "%.2f".format(it.phaseAUpfrontRequestedU) }
        }
        // ABER: die Menge kommt wirklich, in Pumpenschritten.
        val freigaben = nachher.filter { it.deferredPrimeReleasedU > 0.0 }
        assertTrue(freigaben.isNotEmpty()) {
            "die uebertragene Menge muss nachgeliefert werden - " +
                nachher.joinToString(" ") { "${it.deferredPrimeDenial}" }
        }
        freigaben.forEach { o ->
            assertEquals(0.05, o.deferredPrimeReleasedU, 1e-9) {
                "genau EIN Pumpenschritt je Zyklus, nie ein Batch: ${o.deferredPrimeReleasedU}"
            }
        }
        // Der Aufschub sinkt entsprechend - die Menge kommt an.
        assertTrue(nachher.last().deferredPrimeOpenU < uebertragenU - 1e-9) {
            "der Aufschub muss sinken: ${nachher.last().deferredPrimeOpenU} von $uebertragenU"
        }
        // Und der Uebertrag geschieht genau EINMAL.
        assertEquals(uebertragenU, ledger.episodes.upfrontTransferredU, 1e-9, "kein zweiter Uebertrag")
    }

    /**
     * P1.1, letzter Punkt - ABLAUF AN DER GEPINNTEN FRIST (Review 25.08.
     * spaet, Punkt 1).
     *
     * KORREKTUR MEINER EIGENEN DIAGNOSE: ich hatte behauptet, die
     * Aufschubfrist koenne den Markerablauf nie ueberholen, weil beide
     * bei 45 min laegen. `OnsetChannel.MARKER_WINDOW_MIN` ist aber 90 -
     * die Behauptung war erfunden, nicht gemessen. Was den frueheren Lauf
     * beendete, war ein GEMESSENES TIEF: die Kurve fiel mit -3,0/min und
     * stand nach 35 Minuten bei 45 mg/dl, was den Evidenzkredit widerrief.
     *
     * Diese Lage faellt deshalb nur 13 Minuten und bleibt dann FLACH bei
     * ~111 mg/dl: kein Tief, kein Widerruf - und weil ein flacher Verlauf
     * die Erholung nicht bestaetigt (sie verlangt >= 0,20 mg/dl/min),
     * bleibt der uebertragene Rest im Aufschub liegen, bis die gepinnte
     * Frist ihn beendet.
     */
    @Test
    fun `der uebertragene rest verfaellt an der gepinnten frist`(@TempDir dir: File) {
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        aufschubFristMin = 45 // kuerzeste zulaessige Frist
        upfrontAnteil = 1.0
        primeHuelleU = 4.0
        fundamentAnteil = 0.8
        aufschubAn = true
        mahlzeit(dir)
        // Kurzer Fall (Riegel), danach flaches Plateau ueber dem Boden.
        flach = 150.0
        steigungProMin = -3.0
        knickAbMin = 13
        steigungNachKnick = 0.0
        bolusIobU = 2.0
        val laufe = (0 until 52).map { transport(dir) }

        val uebertrag = laufe.indexOfFirst { it.phaseAUpfrontState == "TRANSFERRED_TO_DEFERRED" }
        assertTrue(uebertrag > 0) {
            "der Uebertrag muss stattfinden - " +
                laufe.mapNotNull { it.phaseAUpfrontState }.distinct().joinToString(" ")
        }
        val offenNachUebertrag = laufe[uebertrag].deferredPrimeOpenU
        assertTrue(offenNachUebertrag > 1.0, "und etwas in den Aufschub legen: $offenNachUebertrag")

        // DER ABLAUF: typisiert EXPIRED, mit beziffertem Rest.
        val verfall = laufe.indexOfFirst { it.deferredPrimeLapseReason == "EXPIRED" }
        assertTrue(verfall > uebertrag) {
            "die gepinnte Frist muss den Rest beenden - " +
                laufe.mapNotNull { it.deferredPrimeLapseReason }.distinct().joinToString(" ")
        }
        assertEquals(offenNachUebertrag, laufe[verfall].deferredPrimeLapseU, 1e-9) {
            "die verfallene Menge ist der offene Rest: ${laufe[verfall].deferredPrimeLapseU}"
        }
        // UNGEFAEHR bei Marker + 45 min - und die Autorisierung lebt noch,
        // der Ablauf ist also wirklich die Frist und kein Widerruf.
        val markerTs = start + 2 * 60_000L
        val minutenNachMarker = (laufe[verfall].computeTs - markerTs) / 60_000.0
        assertTrue(minutenNachMarker in 44.0..47.0) {
            "Ablauf bei Marker+45, gemessen: $minutenNachMarker min"
        }
        assertTrue(laufe[verfall].mealFoundation.armed) {
            "die Autorisierung ist zum Ablaufzeitpunkt NICHT widerrufen"
        }
        // Danach ist der Aufschub leer und nichts lebt wieder auf.
        assertEquals(0.0, laufe.last().deferredPrimeOpenU, 1e-9)
        assertTrue(laufe.drop(verfall).all { it.phaseAUpfrontRequestedU == 0.0 })
    }

    /**
     * P1.2 - DIE HUELLEN-KLEMMUNG WIRKLICH ERZEUGT (Review 25.08. spaet,
     * Punkt 2). Der Nachlieferungstest lief mit `upfrontLapsedU == 0`;
     * damit war die Trennung von Uebertrag und Verfall implementiert,
     * aber nicht bewiesen.
     *
     * LAGE: Huelle 4,0 - davon 0,60 in Phase A geliefert, also 2,60
     * Sofortanteil offen. Im Aufschub liegt bereits 1,00 aus dem
     * LINEAREN Prime. Der Huellenrest ist damit 3,40, und `withhold`
     * kann von den 2,60 nur noch 2,40 aufnehmen: 0,20 verfallen.
     */
    @Test
    fun `bei huellen-klemmung wird nur das gebuchte als uebertragen gezaehlt`(@TempDir dir: File) {
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        upfrontAnteil = 1.0
        primeHuelleU = 4.0
        fundamentAnteil = 0.8
        aufschubAn = true
        mahlzeit(dir)
        flach = 150.0
        steigungProMin = -3.0
        knickAbMin = 13
        steigungNachKnick = 0.0
        bolusIobU = 2.0

        // Vor dem Phasenwechsel die Lage herstellen: 0,60 geliefert und
        // 1,00 linearer Prime bereits im Aufschub.
        val vorwechsel = (0 until 12).map { transport(dir) }
        assertTrue(vorwechsel.last().phaseAUpfrontPendingU > 3.0, "noch volle 3,20 offen")
        ledger.episodes.deliveredPhaseAU += 0.60
        ledger.episodes.deferredPrime = ledger.episodes.deferredPrime.copy(openU = 1.00)
        val vorUebertrag = transport(dir)
        assertEquals(2.60, vorUebertrag.phaseAUpfrontPendingU, 1e-9, "2,60 offen")

        // Ueber den Phasenwechsel fahren.
        val laufe = (0 until 14).map { transport(dir) }
        val u = laufe.indexOfFirst { it.phaseAUpfrontState == "TRANSFERRED_TO_DEFERRED" }
        assertTrue(u >= 0) {
            "Uebertrag noetig - " + laufe.mapNotNull { it.phaseAUpfrontState }.distinct().joinToString(" ")
        }
        // DIE TRENNUNG: gebucht wurden nur 2,40 (Huellenrest 3,40 minus
        // der 1,00 linearem Prime), 0,20 sind verfallen.
        assertEquals(2.40, ledger.episodes.upfrontTransferredU, 1e-9) {
            "uebertragen = REAL gebucht, nicht der offene Betrag"
        }
        assertEquals(0.20, ledger.episodes.upfrontLapsedU, 1e-9) {
            "und der Rest ist verfallen, nicht stillschweigend uebertragen"
        }
        assertEquals(3.40, laufe[u].deferredPrimeOpenU, 1e-9, "der Aufschub steht am Huellendeckel")
        // Beide Groessen stehen im Outcome und damit im Export.
        assertEquals(2.40, laufe[u].phaseAUpfrontTransferredU, 1e-9)
        assertEquals(0.20, laufe[u].phaseAUpfrontLapsedU, 1e-9)
        // BILANZ danach 0 - beide Posten zaehlen.
        assertEquals(0.0, laufe.last().phaseAUpfrontPendingU, 1e-9, "nichts bleibt sofort offen")
        // KEIN zweiter Uebertrag.
        assertEquals(2.40, ledger.episodes.upfrontTransferredU, 1e-9, "genau einmal")

        // CODEC/NEUSTART: beide Posten ueberleben.
        assertTrue(ledger.persistVerified(dir), "versiegeln")
        val wieder = nachNeustart(dir)
        assertEquals(2.40, wieder.upfrontTransferredU, 1e-9, "Uebertrag ueberlebt")
        assertEquals(0.20, wieder.upfrontLapsedU, 1e-9, "Verfall ueberlebt")
    }

    /**
     * PUNKT 6: eine unlesbare Behandlungssicht ist NICHT "gedeckt". Frueher
     * fiel die Bilanz dort fail-closed auf 0 - und 0 offen las sich als
     * COVERED, also als erledigt. Jetzt ist der Zustand typisiert.
     */
    @Test
    fun `unlesbare behandlungssicht erscheint nicht als gedeckt`(@TempDir dir: File) {
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        upfrontAnteil = 1.0
        primeHuelleU = 4.0
        fundamentAnteil = 0.8
        aufschubAn = true
        mahlzeit(dir)
        // Die Bolushistorie ist unlesbar - genau der fail-closed-Fall.
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any()))
            .thenThrow(IllegalStateException("Bolushistorie nicht lesbar"))
        val laufe = (0 until 6).map { transport(dir) }
        val armiert = laufe.filter { it.mealFoundation.armed }
        assertTrue(armiert.isNotEmpty(), "die Autorisierung besteht")
        assertTrue(armiert.none { it.phaseAUpfrontState == "COVERED" }) {
            "unlesbar darf nie als gedeckt erscheinen - " +
                armiert.mapNotNull { it.phaseAUpfrontState }.distinct().joinToString(" ")
        }
        assertTrue(armiert.any { it.phaseAUpfrontState == "BLOCKED_VIEW" }) {
            "und der Grund ist benannt - " +
                armiert.mapNotNull { it.phaseAUpfrontState }.distinct().joinToString(" ")
        }
        assertTrue(armiert.all { it.phaseAUpfrontRequestedU == 0.0 }, "und es fliesst nichts")
    }

    /** Sicherheitsauflage: OHNE aktives DeferredPrime-Netz keine
     *  Sofortdosis - fail-closed, der Boden bleibt sichtbar offen. */
    @Test
    fun `ohne deferred-prime keine sofortdosis`(@TempDir dir: File) {
        upfrontAnteil = 1.0
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        aufschubAn = false
        mahlzeit(dir)
        ruhigStellen()
        val laufe = (0 until 12).map { transport(dir) }
        assertTrue(laufe.all { it.phaseAUpfrontRequestedU == 0.0 }, "fail-closed ohne Netz")
        val armiert = laufe.filter { it.mealFoundation.armed && it.phaseAUpfrontPendingU > 0.0 }
        assertTrue(armiert.isNotEmpty(), "die Autorisierung selbst besteht")
        assertTrue(
            armiert.all { it.phaseAUpfrontState == "BLOCKED_NO_DEFERRED" },
            "und der Grund ist benannt: " + armiert.map { it.phaseAUpfrontState }.distinct(),
        )
    }

    /** P0 (Toni): TECHNISCHE INTEGRITAET IM HAUPTPFAD - ein Zyklus kann
     *  einen Kern haben und trotzdem einen typisierten Modellfehler
     *  tragen; finalVeto saehe ihn, aber MarkerFloor stellte die grosse
     *  Dosis wieder her. Das Tor prueft VOR dem Lift: kein mealUpfront,
     *  die Menge wandert in den Aufschub - nie verloren. */
    @Test
    fun `technischer modellfehler traegt keine sofortdosis`(@TempDir dir: File) {
        upfrontAnteil = 1.0
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        aufschubAn = true
        markerAuthorized = true
        fundamentAn = true
        flach = 100.0
        steigungProMin = 0.0
        markerAt = 0L
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        repeat(6) { cycle() } // Observer READY
        // Der Kern deckt das 360er-Fenster nicht - exakt die typisierte
        // Reject-Sorte des Liveness-Modell-Tors (MODEL_HORIZON_TOO_SHORT).
        whenever(preferences.get(FuseIntKey.LiabilityHorizonMin)).thenReturn(360)
        markerAt = clock
        val laufe = (0 until 8).map { cycle() }
        assertTrue(
            laufe.all { it.phaseAUpfrontRequestedU == 0.0 },
            "kein mealUpfront bei technischem Modellfehler",
        )
        // NEUER VERTRAG (25.08. mittags): die Menge liegt NICHT mehr im
        // generischen Aufschub-Buch, sondern bleibt ueber die Bilanz offen -
        // im eigenen Zustand DEFERRED_UPFRONT_BATCH. Verloren geht sie so
        // wenig wie vorher, aber sie rieselt nicht in Pumpenschritten.
        assertTrue(
            laufe.any { it.phaseAUpfrontState == "DEFERRED_UPFRONT_BATCH" && it.phaseAUpfrontPendingU > 0.5 },
            "die Menge bleibt offen: " +
                laufe.map { it.phaseAUpfrontState to it.deferredPrimeOpenU }.distinct(),
        )
    }

    /** Sicherheitsauflage: im predictorfreien Technik-Fallback KEINE
     *  Sofortdosis - ohne Bahn traegt keine Wirkungspruefung eine
     *  3-U-Dosis. Der Boden bleibt sichtbar offen (BLOCKED_FALLBACK) und
     *  feuert im naechsten gesunden Hauptpfad-Zyklus: aufgeschoben, nicht
     *  verloren. */
    @Test
    fun `im predictor-fallback keine sofortdosis`(@TempDir dir: File) {
        upfrontAnteil = 1.0
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        aufschubAn = true
        markerAuthorized = true
        fundamentAn = true
        flach = 100.0
        steigungProMin = 0.0
        markerAt = 0L
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        repeat(6) { cycle() } // Observer READY werden lassen
        predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT
        markerAt = clock // der Druck faellt mitten in den Modellausfall
        val imFallback = (0 until 8).map { cycle() }.filter { it.markerFallbackUsed }
        assertTrue(imFallback.isNotEmpty(), "der Fallback muss laufen")
        assertTrue(
            imFallback.all { it.phaseAUpfrontRequestedU == 0.0 },
            "keine Sofortdosis ohne Bahn",
        )
        // WIRKLICH VERLUSTFREI (Tonis Review): der offene Betrag liegt im
        // Aufschub - ein Modellausfall bis nach Phase A koennte einen
        // blossen Boden sonst als WINDOW_OVER verfallen lassen.
        val verschoben = imFallback.filter { it.mealFoundation.armed }
        assertTrue(verschoben.isNotEmpty(), "die Autorisierung muss im Fallback bestehen")
        // Der Fallback schiebt ebenfalls in den eigenen Batch-Zustand -
        // BLOCKED_FALLBACK benennt den Grund, die Bilanz haelt die Menge.
        assertTrue(
            verschoben.any { it.phaseAUpfrontPendingU > 0.5 },
            "die Sofortmenge bleibt offen: " +
                verschoben.map { it.phaseAUpfrontState to it.deferredPrimeOpenU }.distinct(),
        )
        // Modell wieder da: der Boden ist ZU (verschoben, nicht verworfen) -
        // keine Doppel-Anforderung; die Nachlieferung laeuft ueber die
        // bestehende Aufschub-Freigabe nach bestaetigter Erholung
        // (P6-Vertraege), nie als ungebremster Nachholbolus.
        predictReject = null
        val nachComeback = (0 until 6).map { transport(dir) }
        nachComeback.forEach { o ->
            assertEquals(0.0, o.phaseAUpfrontRequestedU, 1e-9, "kein Doppel nach dem Comeback")
        }
        // Die Menge ist durch den Modellausfall NICHT verloren gegangen -
        // sie steht ueber die Bilanz offen, nicht in einem zweiten Buch.
        // Genau dessen Abweichung war der Befund vom 25.08.
        assertTrue(nachComeback.first().phaseAUpfrontPendingU > 0.5) {
            "die Menge bleibt offen: " +
                nachComeback.joinToString(" ") { "${it.phaseAUpfrontPendingU}/${it.phaseAUpfrontState}" }
        }
    }

    /** Sicherheitsauflage: der Zyklus, der den Zero-Latch GERADE zuendet
     *  (scharfes Verdikt), sperrt die Sofortdosis - nicht erst der
     *  aktive Latch im Folgezyklus. */
    @Test
    fun `der zuendende zyklus sperrt die sofortdosis`(@TempDir dir: File) {
        zeroLatchAn = true
        aufschubAn = true
        upfrontAnteil = 1.0
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        fundamentAn = true
        markerAuthorized = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        repeat(6) { cycle() } // Warm-up
        markerAt = clock
        val laufe = (0 until 30).map { cycle() }
        // Die INVARIANTE ueber die ganze Fall-Episode: in KEINEM Zyklus mit
        // Low-Tor-Verdikt (roh - deckt Zuend- und Haltezyklen) und in
        // keinem Zyklus mit aktivem Latch geht eine Sofortdosis hinaus.
        val verdiktZyklen = laufe.filter {
            it.lowThreat != null &&
                it.lowThreat!!.verdict != app.aaps.fuse.core.controller.LowThreatGate.Verdict.NONE
        }
        assertTrue(verdiktZyklen.size >= 2, "die Lage muss Verdikt-Zyklen tragen: ${verdiktZyklen.size}")
        assertTrue(
            verdiktZyklen.all { it.phaseAUpfrontRequestedU == 0.0 },
            "keine Sofortdosis in einem Verdikt-Zyklus",
        )
        // DIE ZWEITE ZUSICHERUNG IST AM 28.08. ENTFALLEN. Hier stand
        // "und keine unter aktivem Latch". Der aktive Zero-Latch sperrt die
        // autorisierte Mahlzeiten-Direktdosis seither nicht mehr - er ist
        // Basalschutz, keine aktuelle Gefahr. Was diesen Test traegt, ist
        // die Zusicherung darueber: der ZUENDENDE Zyklus hat ein scharfes
        // Low-Tor-Verdikt, und DAS sperrt unveraendert. Der Nachweis fuer
        // die neue Richtung steht in
        // `aktiver zero-latch sperrt die sofortdosis nicht mehr`.
    }

    /**
     * ENDPFAD-PROBEN: DER STABILITAETSNACHWEIS GATET DIE ERHOLUNG EINES
     * AUFGESCHOBENEN BATCHES - nicht die erste, ungehinderte Freigabe.
     *
     * Diese Abzweigung (`deferredOpen == false` kehrt vor dem
     * Stabilitaetstor zurueck) ist AELTER als die Stabilitaetsintegration und
     * durch sie nicht entstanden. Sie wird hier dokumentiert, nicht geaendert:
     * wer den ungehinderten Erstlauf absichern will, tut das ueber die
     * vorhandenen Risikohorizonte (descentRisk, gepinnter Mahlzeitenhorizont).
     *
     * DIE REIHE WIRD EXPLIZIT VORGEGEBEN. Ein erster Wurf stellte stattdessen
     * die Formparameter (`flach`, `steigungProMin`) mitten im Lauf um - und
     * uebersah, dass das Rig die Reihe in JEDEM Zyklus aus diesen Parametern
     * neu erzeugt. Damit aenderte sich auch die VERGANGENHEIT, der Aufschub
     * hatte fuer die neue Reihe nie stattgefunden, und beide Proben liefen in
     * `NOTHING_DEFERRED` - also am Pruefgegenstand vorbei.
     *
     * @param plateauAbIndex ab welchem Punkt die Reihe flach wird
     * @param spaeterProMin Steigung danach (0.0 = Plateau)
     */
    private fun endpfadReihe(takt: Long, plateauAbIndex: Int, spaeterProMin: Double): Int {
        val punkte = 46
        val werte = ArrayList<Pair<Long, Double>>(punkte)
        var wert = 150.0
        for (i in 0 until punkte) {
            werte.add((start + i * takt) to wert)
            val proMin = if (i < plateauAbIndex) -1.2 else spaeterProMin
            wert += proMin * takt / 60_000.0
        }
        rohSerie = werte
        return punkte
    }

    private fun endpfadAufbau(dir: File, takt: Long) {
        taktMs = takt
        zeroLatchAn = true
        aufschubAn = true
        upfrontAnteil = 1.0
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        fundamentAn = true
        markerAuthorized = true
        bolusIobU = 2.5
        clock = start
        // DEN MARKERZUSTAND AUSDRUECKLICH ZURUECKSETZEN (Toni 28.08.).
        // `transportReset()` raeumt Uhr und Transport, aber NICHT markerAt,
        // markerAtIntern oder markerPress. Wer mehrere Takte in EINER
        // Testinstanz hintereinander faehrt, schleppt damit Markerinformation
        // aus dem vorigen Lauf mit - eine ueberpruefbare Fehlerquelle, die
        // jeden Taktbefund zum Artefakt machen kann. Deshalb liegt jeder Takt
        // zusaetzlich in einer EIGENEN Testmethode: JUnit baut je Methode eine
        // frische Instanz.
        markerAt = 0L
        markerAtIntern = 0L
        markerPress = 0L
        transportReset()
        // PHASE A: 20 min wie produktiv. Ein laengeres Fenster waere ein
        // Diagnoseaufbau und keine Abnahme (Toni 28.08.) - der Verlauf muss
        // kausal so gebaut sein, dass die Ruhe INNERHALB der Frist steht.
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        neuerRunner(
            FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) },
            ruheParams = app.aaps.fuse.core.controller.UpfrontRecovery.Params.of(
                calmCycles = 3, minUkf = 0.0, minGuardDistanceMgdl = 5.0,
                calmTreatment = app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.CALM_BATCH,
                ruleSetVersion = app.aaps.fuse.plugin.export.FuseStateJson.RULE_SET_VERSION,
            ),
        )
    }

    /**
     * ENDPFAD-PROBE 1: PLATEAU. Der aufgeschobene Batch wird freigegeben.
     *
     * GEPRUEFT WIRD DIE ENDMENGE (Toni 28.08.): `phaseAUpfrontRequestedU`
     * entsteht direkt nach `liftUpfront` und damit VOR den spaeteren Riegeln.
     * Massgeblich ist `upfrontChain.requestedRtU` ZUSAMMEN MIT der Quelle -
     * jener Wert traegt die gesamte RT-Menge des Zyklus, also auch normale
     * Korrekturen.
     *
     * JE TAKT EINE EIGENE METHODE, damit kein Zustand aus dem vorigen Lauf
     * mitwandert. Der 62-s-Fall ist eine AUSFUEHRBARE Regression, kein
     * Kommentar: eine gruene Suite, aus der der Problemfall herausgenommen
     * wurde, ist keine Abnahme.
     */
    private fun plateauEndpfad(dir: File, takt: Long) {
        endpfadAufbau(dir, takt)
        val punkte = endpfadReihe(takt, plateauAbIndex = 8, spaeterProMin = 0.0)
        var gezuendet = false
        val laeufe = ArrayList<FuseCycleRunner.Outcome>()
        repeat(punkte - 2) {
            val o = cycle()
            if (!gezuendet && o.zeroLatchActive) {
                gezuendet = true
                markerAt = clock
            }
            if (gezuendet) laeufe.add(o)
        }
        assertTrue(gezuendet, "der Aufschub muss zuenden (Takt $takt)")

        val ketten = laeufe.mapNotNull { it.upfrontChain }
        val freigaben = laeufe.filter {
            val c = it.upfrontChain
            c != null && c.requestedRtU > 0.0 && c.grantSource == "MEAL_UPFRONT"
        }
        val enden = freigaben.mapNotNull { it.upfrontChain }
        assertEquals(1, enden.size,
                     "GENAU EINE Batch-Endanforderung (Takt $takt): " +
                         enden.map { it.requestedRtU } +
                         " | Modi " + ketten.map { it.recoveryMode }.distinct() +
                         " | Behandlung " + ketten.map { it.calmTreatment }.distinct() +
                         " | Denials " + ketten.mapNotNull { it.recoveryDenial }.distinct() +
                         " | offen " + ketten.map { it.upfrontOpenU }.distinct() +
                         " | Zustaende " + laeufe.mapNotNull { it.phaseAUpfrontState }.distinct())
        val k = enden.single()
        assertTrue(k.requestedRtU > 0.0, "eine Menge muss fliessen (Takt $takt): $k")
        assertEquals(k.upfrontOpenU, k.requestedRtU, 1e-9,
                     "der GANZE offene Anteil in einem Zug (Takt $takt): $k")
        // DER MODUS AUSDRUECKLICH: CALM_RECOVERED allein heisst noch nicht,
        // dass ueber CALM_BATCH freigegeben wurde (Toni 28.08.).
        assertEquals("CALM_RECOVERED", k.recoveryMode, "Freigabemodus (Takt $takt): $k")
        assertEquals("CALM_BATCH", k.calmTreatment, "Behandlung (Takt $takt): $k")
        assertEquals("WITHIN_TOLERANCE", k.stabilisation, "Takt $takt: $k")

        // DIE MENGE DURCH DIE STUFEN, nicht nur am Ende. Eine Probe, die nur
        // die Endzahl prueft, kann eine Stufe uebersehen, die kuerzt und eine
        // spaetere, die wieder auffuellt.
        val stufen = "Takt $takt | offen=${k.upfrontOpenU} eligible=${k.calmEligibleU}" +
            " shifted=${k.calmShiftedU} demand=${k.calmDemandU} grant=${k.grantU}" +
            " vorFloor=${k.beforeMarkerFloorU} nachFloor=${k.afterMarkerFloorU}" +
            " nachGate=${k.afterDescentGateU} rt=${k.requestedRtU}" +
            " gateGrund=${k.descentGateCause} finalVerify=${k.calmDeniedByFinalVerify}"
        // CALM_BATCH nimmt WEDER den schrittweisen NOCH den bedarfsbegrenzten
        // Pfad: `calmShiftedU` gehoert zu SHIFT_TO_DEFERRED, `calmDemandU`
        // zu DEMAND_LIMITED. Beide muessen hier 0 sein - sonst liefe der
        // Batch ueber einen anderen Mechanismus als behauptet.
        assertEquals(0.0, k.calmShiftedU, 1e-9, "kein Verschiebepfad: $stufen")
        assertEquals(0.0, k.calmDemandU, 1e-9, "kein Bedarfspfad: $stufen")
        assertEquals(k.upfrontOpenU, k.grantU, 1e-9, "der Grant traegt den offenen Anteil: $stufen")
        assertEquals(k.upfrontOpenU, k.afterDescentGateU, 1e-9, "und passiert das Abstiegstor: $stufen")
        assertNull(k.calmDeniedByFinalVerify, "keine Endabweisung: $stufen")

        // WOHER DIE MENGE WIRKLICH KOMMT - ausdruecklich behauptet, nicht
        // nebenbei passiert. Gemessen: der normale Pfad verlangt NICHTS, die
        // Menge nach der Modellkette ist 0, und der Markerboden stellt den
        // vollen autorisierten Anteil wieder her.
        //
        // Das ist der Vertrag vom 11.08. (ein Veto darf den markerfinanzierten
        // Anteil senken, aber nicht unter ihn) und arbeitet hier regelgerecht.
        // Es ist aber auch die Form des Abendfalls vom 25.08. Wenn dieser
        // Vertrag fuer CALM_BATCH je eingeschraenkt wird, muss diese Probe
        // brechen - und nicht still weiterlaufen.
        assertEquals(0.0, k.normalNeedBeforeMarkerFloorU, 1e-9,
                     "der normale Pfad verlangt nichts: $stufen")
        assertEquals(0.0, k.beforeMarkerFloorU, 1e-9, "die Modellkette laesst nichts uebrig: $stufen")
        assertEquals(k.requestedRtU, k.markerFloorLiftU, 1e-9,
                     "die ganze Menge stammt aus dem Markerboden: $stufen")

        // PLANNED IST EINE ABGELEITETE ANZEIGE, KEIN NACHWEIS EINER UMBUCHUNG
        // (Toni 28.08.). Der freigebende Zyklus muss den Batch wirklich
        // umgebucht haben.
        val zustand = freigaben.single().phaseAUpfrontState
        assertTrue(zustand == "REQUESTED" || zustand == "COVERED",
                   "der freigebende Zyklus muss umgebucht haben, nicht nur planen (Takt $takt): " +
                       "$zustand | alle " + laeufe.mapNotNull { it.phaseAUpfrontState }.distinct())
        assertTrue(laeufe.any { it.phaseAUpfrontState == "DEFERRED_UPFRONT_BATCH" },
                   "und vorher wirklich aufgeschoben gewesen sein (Takt $takt)")

        // UNABHAENGIG ERWARTETE WERTE aus der vorgegebenen Reihe: ab dem
        // Plateau ist sie konstant. Ein Netto oder Rueckgang ungleich 0 waere
        // eine falsche Zuordnung - und die faende ein Vergleich
        // "JSON == Outcome" nicht.
        assertEquals(0.0, k.recentNetMgdl, 1e-9, "konstante Reihe -> Netto 0 (Takt $takt): $k")
        assertEquals(0.0, k.recentWorstDropMgdl, 1e-9, "und kein Rueckgang (Takt $takt): $k")
        assertTrue(k.recentSpanMin >= 4.5 && k.recentSpanMin <= 7.0,
                   "Abschnittsspanne, nicht Fensterspanne (Takt $takt): ${k.recentSpanMin}")

        val json = app.aaps.fuse.plugin.export.FuseStateJson.upfrontChainJson(k)
        assertEquals("WITHIN_TOLERANCE", json.optString("stabilisation"))
        assertEquals(0.0, json.optDouble("recentNetMgdl"), 1e-9)
        assertEquals(0.0, json.optDouble("recentWorstDropMgdl"), 1e-9)
        assertTrue(json.optDouble("recentSpanMin") >= 4.5)
        assertEquals(k.requestedRtU, json.optDouble("requestedRtU"), 1e-9)
    }


    /**
     * REGRESSION: BEIDE TAKTE NACHEINANDER IN EINER INSTANZ.
     *
     * Diese Probe haelt einen GEMESSENEN TESTFEHLER fest, den ich zunaechst
     * fuer einen taktabhaengigen Produktionsfehler gehalten hatte. Toni hat
     * die Stoerquelle benannt: `endpfadAufbau` setzte Uhr und Transport
     * zurueck, aber nicht `markerAt` / `markerAtIntern` / `markerPress`.
     *
     * Die Messung im gemeinsamen Lauf, ohne diesen Reset:
     *
     *   61, 62 -> erster 2,15 U, zweiter NICHTS
     *   62, 61 -> erster 2,20 U, zweiter NICHTS
     *   nur 62 -> 2,20 U
     *
     * Der Fehler folgt also der POSITION, nicht dem Takt. Der zweite Lauf
     * erbte den Marker des ersten - dort auf dessen Enduhr gesetzt, waehrend
     * die neue Uhr wieder auf `start` steht: ein Marker in der Zukunft. Die
     * Kette meldete dann zwar `CALM_RECOVERED`, blieb aber bei Quelle `PRIME`
     * und Zustand `PLANNED`, und der offene Anteil rieselte in Einzelschritten
     * heraus. `PLANNED` ist eben eine abgeleitete Anzeige und kein Nachweis
     * einer Umbuchung.
     *
     * Mit Reset liefern beide Laeufe. Diese Probe ist der Waechter dafuer:
     * wer den Reset wieder entfernt, sieht es hier - und nicht erst an einem
     * Befund, der nach Produktionsfehler aussieht.
     */
    @Test
    fun `Endpfad - zwei Takte nacheinander in einer Instanz geben beide frei`(@TempDir dir: File) {
        plateauEndpfad(File(dir, "erst61"), 61_000L)
        plateauEndpfad(File(dir, "dann62"), 62_000L)
    }

    @Test
    fun `Endpfad - dieselben zwei Takte in vertauschter Reihenfolge`(@TempDir dir: File) {
        plateauEndpfad(File(dir, "erst62"), 62_000L)
        plateauEndpfad(File(dir, "dann61"), 61_000L)
    }

    @Test
    fun `Endpfad - Plateau bei 61 Sekunden gibt den Batch als Endmenge frei`(@TempDir dir: File) =
        plateauEndpfad(dir, 61_000L)

    @Test
    fun `Endpfad - Plateau bei 62 Sekunden gibt den Batch als Endmenge frei`(@TempDir dir: File) =
        plateauEndpfad(dir, 62_000L)

    @Test
    fun `Endpfad - Plateau bei 60 Sekunden gibt den Batch als Endmenge frei`(@TempDir dir: File) =
        plateauEndpfad(dir, 60_000L)

    /**
     * ENDPFAD-PROBE 2: DAUERABFALL. Der Batch bleibt offen und aufgeschoben,
     * die Toleranz ist verletzt, und es entsteht KEINE Batch-Endanforderung.
     */
    @Test
    fun `Endpfad - ein Dauerabfall erzeugt keine Batch-Endanforderung`(@TempDir dir: File) {
        for (takt in longArrayOf(61_000L, 62_000L)) {
            endpfadAufbau(File(dir, "d$takt"), takt)
            val punkte = endpfadReihe(takt, plateauAbIndex = 22, spaeterProMin = -0.8)
            var gezuendet = false
            val laeufe = ArrayList<FuseCycleRunner.Outcome>()
            repeat(punkte - 2) { i ->
                val o = cycle()
                if (!gezuendet && o.zeroLatchActive) {
                    gezuendet = true
                    markerAt = clock
                }
                if (gezuendet) laeufe.add(o)
            }
            assertTrue(gezuendet, "der Aufschub muss zuenden (Takt $takt)")

            val ketten = laeufe.mapNotNull { it.upfrontChain }
            assertTrue(ketten.isNotEmpty(), "die Kette muss stehen (Takt $takt)")
            assertTrue(ketten.any { it.upfrontOpenU > 0.0 },
                       "der Batch muss offen sein (Takt $takt): " +
                           ketten.map { it.upfrontOpenU }.distinct())
            assertTrue(laeufe.any { it.phaseAUpfrontState == "DEFERRED_UPFRONT_BATCH" },
                       "und aufgeschoben in Phase A (Takt $takt): " +
                           laeufe.mapNotNull { it.phaseAUpfrontState }.distinct())

            // KEINE BATCH-Endanforderung. Normale Korrekturen darf es geben -
            // der Normalpfad ist von dieser Aenderung unberuehrt.
            val batchEnden = ketten.filter { it.requestedRtU > 0.0 && it.grantSource == "MEAL_UPFRONT" }
            assertTrue(batchEnden.isEmpty(),
                       "keine Batch-Endanforderung (Takt $takt): " + batchEnden.map { it.requestedRtU })
            assertTrue(ketten.none { it.stabilisation == "WITHIN_TOLERANCE" },
                       "die Toleranz bleibt verletzt (Takt $takt): " +
                           ketten.map { it.stabilisation }.distinct())

            // Unabhaengig erwartet: -0,8 mg/dl/min ueber rund fuenf Minuten
            // sind etwa -4 mg/dl, deutlich mehr als die Zugabe von 2,0.
            val letzte = ketten.last()
            assertTrue(letzte.recentNetMgdl <= -3.0,
                       "das Netto zeigt den Abfall (Takt $takt): ${letzte.recentNetMgdl}")
            val json = app.aaps.fuse.plugin.export.FuseStateJson.upfrontChainJson(letzte)
            assertEquals(letzte.recentNetMgdl, json.optDouble("recentNetMgdl"), 1e-9)
            assertTrue(json.optDouble("recentNetMgdl") <= -3.0)
        }
    }

    /** Bis zur Sofortdosis fahren (Warm-up + Markerzyklus). */
    private fun bisSofortdosis(dir: File, maxZyklen: Int = 12): FuseCycleRunner.Outcome {
        repeat(maxZyklen) {
            val o = transport(dir)
            if (o.phaseAUpfrontRequestedU > 0.0) return o
        }
        throw AssertionError("die Sofortdosis muss kommen - der Aufbau traegt den Test nicht")
    }

    /**
     * Pflichttests 2/4/8/16: das ganze Phase-A-Budget GENAU EINMAL sofort,
     * als EINE Dosis (3,0 U bei maxSMB 0,3 - die typisierte Quelle
     * MEAL_UPFRONT wird nicht zerteilt), budgettreu ueber das ganze
     * Fundament-Fenster, und ein Neustart nach der Buchung sendet nie
     * ein zweites Mal.
     */
    @Test
    fun `sofortanteil 1,0 - das ganze phase-a-budget genau einmal ungeteilt`(@TempDir dir: File) {
        upfrontMahlzeit(dir, 1.0)
        val d = bisSofortdosis(dir)
        assertEquals(3.0, d.phaseAUpfrontRequestedU, 1e-9, "3,75 x 0,8 x 1,0")
        assertEquals(3.0, d.decision.smbU, 1e-9, "EINE Dosis, nicht von maxSMB (0,3) zerteilt")
        // In der ruhigen Lage nimmt das Tail-/Guard-Veto die Menge und der
        // Marker-Boden stellt sie wieder her - dann heisst das Binding
        // ehrlich markerAuth|finalVerify. Ohne Veto bleibt "mealUpfront".
        assertTrue(
            d.decision.bindingLimit == "mealUpfront" || d.decision.bindingLimit.startsWith("markerAuth"),
            "Binding: ${d.decision.bindingLimit}",
        )
        assertEquals(3.0, ledger.episodes.deliveredPhaseAU, 1e-9, "gebucht")
        // GENAU EINMAL: der Boden ist 0, kein Zyklus fordert erneut - auch
        // nicht ueber die Uebergabe hinaus (Phase B liefert ihr eigenes
        // Teilbudget als Mindestversorgung, nie eine zweite Sofortdosis).
        repeat(70) {
            val o = transport(dir)
            assertEquals(0.0, o.phaseAUpfrontRequestedU, 1e-9, "keine zweite Sofortanforderung")
        }
        // Pflichttest 4: A + B ueberschreiten das Gesamtbudget nie (ruhige
        // Lage: alles Gelieferte ist markerfinanziert).
        val e = ledger.episodes
        assertTrue(
            e.deliveredPhaseAU + e.deliveredSinceHandoverU <= 3.75 + 1e-6,
            "Budgettreue: A=${e.deliveredPhaseAU} B=${e.deliveredSinceHandoverU}",
        )
        // Pflichttest 8: Neustart nach bestaetigter Dosis - der Boden lebt
        // aus dem persistierten Zaehler, keine zweite Abgabe.
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", clock) })
        repeat(6) {
            val o = transport(dir)
            assertEquals(0.0, o.phaseAUpfrontRequestedU, 1e-9, "Neustart sendet nicht doppelt")
        }
    }

    /**
     * Pflichttests 3/6: Haelfte sofort, Haelfte linear ueber das
     * BESTEHENDE Prime-Fenster; die Uebergabe zu Phase B bleibt bei
     * PrimeWindowMin und Phase B behaelt ihr Teilbudget - Lieferkurve und
     * Phasengrenze sind zwei verschiedene Groessen.
     */
    @Test
    fun `sofortanteil 0,5 - haelfte sofort, rest linear, uebergabe unveraendert`(@TempDir dir: File) {
        upfrontMahlzeit(dir, 0.5)
        val d = bisSofortdosis(dir)
        assertEquals(1.5, d.phaseAUpfrontRequestedU, 1e-9, "3,0 x 0,5 sofort")
        assertEquals(1.5, d.decision.smbU, 1e-9)
        // Der lineare Rest laeuft ueber die unveraenderte Prime-Mathematik:
        // bis zur Uebergabe liefert Phase A insgesamt hoechstens ihr Budget,
        // und mehr als die Sofortdosis ist dazugekommen.
        var upfrontNochmal = 0
        while (clock < markerAt + 20 * 60_000L) {
            if (transport(dir).phaseAUpfrontRequestedU > 0.0) upfrontNochmal++
        }
        assertEquals(0, upfrontNochmal, "der lineare Rest ist NIE eine zweite Sofortdosis")
        val phaseA = ledger.episodes.deliveredPhaseAU
        assertTrue(phaseA > 1.5 + 0.04, "der lineare Rest liefert real nach: $phaseA")
        assertTrue(phaseA <= 3.0 + 1e-6, "und bleibt im Phase-A-Budget: $phaseA")
        // Pflichttest 6: exakt ab der Uebergabe gilt Phase B mit ihrem
        // komplementaeren Teilbudget - unabhaengig vom Sofortanteil.
        val o = transport(dir)
        assertEquals("PHASE_B", o.mealFoundation.phase.name)
        assertEquals(0.75, o.mealFoundation.phaseBBudgetU, 1e-9)
    }

    /** Pflichttest 5: Sofortdosis und normaler SMB werden NICHT addiert -
     *  die Endmenge ist der Boden (max-Semantik des AuthorizedLift). */
    @Test
    fun `sofortdosis ist max und niemals addition`(@TempDir dir: File) {
        upfrontAnteil = 0.5
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        aufschubAn = true
        mahlzeit(dir) // steigende Lage: der Normalpfad fordert selbst
        val d = bisSofortdosis(dir)
        assertTrue(d.preFoundationSmbU >= d.phaseAUpfrontRequestedU - 1e-9, "der Boden traegt")
        assertEquals(
            1.5, d.decision.smbU, 1e-9,
            "Endmenge == Sofortboden 1,5 - eine Addition laege darueber",
        )
    }

    /** Pflichttest 7: die Autorisierung ist GEPINNT - eine Aenderung der
     *  Preference waehrend der laufenden Episode wirkt erst beim naechsten
     *  frischen Marker. */
    @Test
    fun `preference-aenderung im lauf aendert die gepinnte autorisierung nicht`(@TempDir dir: File) {
        upfrontMahlzeit(dir, 0.5)
        val d = bisSofortdosis(dir)
        assertEquals(0.5, d.mealFoundation.phaseAUpfrontShare, 1e-9)
        upfrontAnteil = 1.0
        val o = transport(dir)
        assertEquals(0.5, o.mealFoundation.phaseAUpfrontShare, 1e-9, "gepinnt, nicht live")
        assertEquals(0.0, o.phaseAUpfrontRequestedU, 1e-9, "und kein Nachschlag aus der Aenderung")
    }

    /**
     * Pflichttest 12 (Abwaertsriegel): der gepinnte lange Horizont haelt
     * die Sofortdosis zurueck - sie geht NICHT hinaus, wird als
     * markerfinanziert im DeferredPrime-Aufschub erfasst und der Boden
     * fordert sie im naechsten Zyklus NICHT erneut an (keine
     * Doppelbuchung im Aufschub).
     */
    @Test
    fun `abwaertsriegel schiebt die sofortdosis in den aufschub - genau einmal`(@TempDir dir: File) {
        aufschubAn = true
        upfrontAnteil = 1.0
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        mahlzeit(dir)
        // Gemessen fallende, BOLUS-ueberdeckte Lage im 60er-Horizont, aber
        // ausserhalb des harten 30ers: BG 150, -1,5/min -> Boden in ~53 min;
        // 2,0 U x ISF 54 = 108 mg/dl Deckung > 80 Abstand.
        flach = 150.0
        steigungProMin = -1.5
        bolusIobU = 2.0
        var withheld: FuseCycleRunner.Outcome? = null
        repeat(12) {
            if (withheld == null) {
                val o = transport(dir)
                if (o.mealFoundation.armed && o.deferredPrimeWithheldU > 0.0) withheld = o
            }
        }
        val w = withheld ?: error("der Aufschub muss die Sofortdosis fangen")
        assertEquals(0.0, w.decision.smbU, 1e-9, "im gemessenen Fall geht nichts hinaus")
        assertTrue(w.phaseAUpfrontPendingU >= 2.9) {
            "die Sofortdosis bleibt vollstaendig offen: ${w.phaseAUpfrontPendingU}"
        }
        assertEquals("DEFERRED_UPFRONT_BATCH", w.phaseAUpfrontState, "im eigenen Zustand")
        // KEINE Akkumulation der SOFORTDOSIS: der Boden zieht den Aufschub
        // ab und fordert nicht erneut. Der lineare Prime-Anteil sammelt
        // planmaessig weiter (bestehende Punkt-6-Semantik), bleibt aber am
        // Huellen-Deckel geklemmt - nie mehr als das Gesamtbudget.
        repeat(4) {
            val o2 = transport(dir)
            assertEquals(0.0, o2.phaseAUpfrontRequestedU, 1e-9, "der Boden fordert nicht erneut")
            assertTrue(o2.deferredPrimeOpenU <= 3.75 + 1e-9, "Huellen-Deckel: ${o2.deferredPrimeOpenU}")
            assertEquals("DEFERRED_UPFRONT_BATCH", o2.phaseAUpfrontState)
        }
    }

    /**
     * PFLICHTNACHWEIS 1 UND 2 (Bauauftrag Toni 28.08.).
     *
     * DIESER TEST STAND FRUEHER ANDERSHERUM ("aktiver zero-latch sperrt die
     * sofortdosis"). Der Vertrag ist umgedreht: ein historisch gehaltener
     * Zero-Latch ist BASALSCHUTZ und darf die autorisierte
     * Mahlzeiten-Direktdosis nicht mehr blockieren. Was er weiterhin darf -
     * und was dieser Test in derselben Lage mitprueft - ist sperren, solange
     * eine AKTUELLE Gefahr steht.
     *
     * Gemessener Anlass: Fruehstueck 28.08., Marker 09:21:56. Von 09:22 bis
     * 09:36 meldete die Kette `currentHazard zeroLatch` als EINZIGEN
     * Blocker, bei `descentRiskActive=false`, `lowThreat=NONE`, gesundem
     * Signal - und 4,00 autorisierte Einheiten lagen still.
     *
     * NACHWEIS 2 (Gefahr sperrt weiter) steht bewusst VOR Nachweis 1: waere
     * er rot, waere die Freigabe wertlos.
     */
    @Test
    fun `aktiver zero-latch sperrt die sofortdosis nicht mehr`(@TempDir dir: File) {
        zeroLatchAn = true
        aufschubAn = true
        upfrontAnteil = 1.0
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        fundamentAn = true
        markerAuthorized = true
        // Fall-Lage des Latch: das Low-Tor zuendet, BG bleibt ueber dem Tief.
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        // A/B GEGEN DENSELBEN VERLAUF. Die Entkopplung heisst: der Zero-Latch
        // veraendert die Sofortanteil-Entscheidung NICHT mehr. Das ist
        // pruefbar, ohne dass die Menge in dieser Lage fliessen muss - und
        // genau so gehoert es geprueft. Ein Test, der auf "die Dosis geht
        // heraus" wartet, misst mit, was ANDERE Riegel tun (in dieser
        // Fall-Lage haelt der Abwaertsriegel weiter, HISTORICAL_LATCH), und
        // waere damit gegen die falsche Ursache gruen oder rot.
        fun lauf(unter: File, latch: Boolean): List<FuseCycleRunner.Outcome> {
            zeroLatchAn = latch
            clock = start
            transportReset()
            neuerRunner(
                FuseLedgerAdapter().also { it.loadOnce(unter.also(File::mkdirs), "test-epoch", start) },
            )
            var gez = false
            repeat(30) { if (!gez) gez = cycle().zeroLatchActive || !latch }
            markerAt = clock
            return (0 until 30).map { cycle() }
        }

        val mitLatch = lauf(File(dir, "mit"), true)
        val ohneLatch = lauf(File(dir, "ohne"), false)

        fun gefahr(o: FuseCycleRunner.Outcome) =
            o.lowThreat != null &&
                o.lowThreat!!.verdict != app.aaps.fuse.core.controller.LowThreatGate.Verdict.NONE

        // Der Test prueft nur etwas, wenn der Latch im A-Lauf wirklich stand.
        assertTrue(mitLatch.any { it.zeroLatchActive }, "der Latch muss im A-Lauf stehen")
        assertTrue(ohneLatch.none { it.zeroLatchActive }, "und im B-Lauf nicht")

        // ---- NACHWEIS 1: der Latch aendert die Sofortanteil-Entscheidung
        //      nicht mehr - Zyklus fuer Zyklus identisch.
        assertEquals(
            ohneLatch.map { it.phaseAUpfrontRequestedU },
            mitLatch.map { it.phaseAUpfrontRequestedU },
            "der Zero-Latch darf die Sofortdosis nicht mehr veraendern",
        )
        // DEN ZUSTAND NICHT ZYKLUSWEISE VERGLEICHEN. Die beiden Laeufe haben
        // vor dem Marker eine verschiedene Geschichte - ohne Latch dosiert
        // der Loop im Warm-up anders, und das SOLL er: die Entkopplung
        // betrifft den Sofortanteil, nicht das Basal. Der B-Lauf startet
        // deshalb mit ein paar Zyklen, in denen die Autorisierung noch nicht
        // gilt (Zustand null). Gleich sein MUSS die Aussage, nicht der
        // Startversatz.
        assertTrue(
            mitLatch.none { it.phaseAUpfrontState == "BLOCKED_ZERO_LATCH" },
            "der Zero-Latch darf kein Zustandsgrund des Sofortanteils mehr sein: " +
                mitLatch.map { it.phaseAUpfrontState }.distinct(),
        )
        assertEquals(
            ohneLatch.mapNotNull { it.phaseAUpfrontState }.distinct().toSet(),
            mitLatch.mapNotNull { it.phaseAUpfrontState }.distinct().toSet(),
            "und es duerfen keine anderen Zustaende auftreten als ohne Latch",
        )

        // ---- NACHWEIS 2: aktuelle Gefahr sperrt unveraendert -------------
        val mitGefahr = mitLatch.filter(::gefahr)
        assertTrue(mitGefahr.isNotEmpty(), "die Lage muss Verdikt-Zyklen tragen")
        assertTrue(
            mitGefahr.all { it.phaseAUpfrontRequestedU == 0.0 },
            "aktuelle Gefahr sperrt unveraendert: " +
                mitGefahr.filter { it.phaseAUpfrontRequestedU > 0.0 }.map { it.phaseAUpfrontRequestedU },
        )

        // ---- Der Zustand darf nicht luegen (Blocker 1 des Audits) --------
        // Invariante statt Einzelfall: WENN angefordert wird, darf die Zeile
        // nicht "verriegelt" melden. `phaseAUpfrontPendingU` ist im
        // Anforderungszyklus noch > 0 (die Bilanz sinkt erst mit der
        // Buchung); stuende der Latch-Zweig weiter vor REQUESTED, traefe das
        // jede Freigabe - und derselbe Text landet ueber
        // FusePlugin.deferredReason im MARKERDIALOG.
        assertTrue(
            mitLatch.none { it.phaseAUpfrontRequestedU > 0.0 && it.phaseAUpfrontState == "BLOCKED_ZERO_LATCH" },
            "kein Zyklus darf gleichzeitig anfordern und verriegelt melden",
        )

        // ---- Die Autorisierung besteht unter dem Latch fort --------------
        assertTrue(
            mitLatch.any { it.mealFoundation.armed && it.zeroLatchActive },
            "die Autorisierung muss unter dem Latch bestehen",
        )
    }

    /**
     * ABGRENZUNG (Bauauftrag Toni 28.08.: "keine zusaetzliche Freigabe fuer
     * Normal- oder Liveness-Korrekturen").
     *
     * Der bedarfsbegrenzte Ruhekandidat gibt REINEN Normalbedarf frei -
     * `ruheReineBasisU` ist exakt `vetted.smbU`, und der Merge stempelt
     * `grant = null`. Seine einzige Absicherung gegen aktuelle Lagen war
     * `Hazards.any`. Waere der Zero-Latch dort ersatzlos herausgefallen,
     * haette dieselbe Aenderung still die KORREKTURBAHN unter verriegeltem
     * Basal geoeffnet - mit einem Zeitfenster von rund 17 Zyklen (Ruhe-
     * Streak 3 gegen Zero-Latch-Ausgang 20).
     *
     * Deshalb fuehrt `ruheKandidatRohU` den Latch als EIGENE Bedingung.
     * Dieser Test haelt das fest.
     */
    @Test
    fun `der bedarfsbegrenzte ruhekandidat bleibt unter dem zero-latch gesperrt`(@TempDir dir: File) {
        zeroLatchAn = true
        aufschubAn = true
        upfrontAnteil = 1.0
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        fundamentAn = true
        markerAuthorized = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(
            FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) },
            ruheParams = app.aaps.fuse.core.controller.UpfrontRecovery.Params.of(
                calmCycles = 3, minUkf = 0.0, minGuardDistanceMgdl = 5.0,
                calmTreatment = app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
                ruleSetVersion = app.aaps.fuse.plugin.export.FuseStateJson.RULE_SET_VERSION,
            ),
        )
        var gezuendet = false
        repeat(30) { if (!gezuendet) gezuendet = cycle().zeroLatchActive }
        assertTrue(gezuendet, "der Latch muss zuenden")
        steigungNachKnick = 0.8
        val laufe = (0 until 40).map { cycle() }
        val unterLatch = laufe.filter { it.zeroLatchActive }
        assertTrue(unterLatch.isNotEmpty(), "es muss Zyklen unter aktivem Latch geben")
        assertTrue(
            unterLatch.none { it.decision.bindingLimit.contains("calmDemand") },
            "kein bedarfsbegrenzter Ruheanteil unter aktivem Latch: " +
                unterLatch.map { it.decision.bindingLimit }.distinct(),
        )
    }

    /** Pflichttest 10: unklarer Pumpenausgang -> die Buchung haftet, der
     *  Boden bleibt 0, nichts wird blind wiederholt. */
    @Test
    fun `unklarer ausgang wiederholt die sofortdosis nicht`(@TempDir dir: File) {
        upfrontMahlzeit(dir, 1.0)
        bisSofortdosis(dir)
        letzterAusgang = Ausgang.UNKLAR
        repeat(6) {
            val o = transport(dir, Ausgang.UNKLAR)
            assertEquals(0.0, o.phaseAUpfrontRequestedU, 1e-9, "ohne Beweis keine Wiederholung")
        }
        assertEquals(3.0, ledger.episodes.deliveredPhaseAU, 1e-9, "die Haftung steht")
    }

    /** Pflichttest 11: ein sicherer Nicht-Sende-Beweis entlastet exakt und
     *  erlaubt GENAU EINEN neuen Versuch. */
    @Test
    fun `nicht-sende-beweis belebt den sofort-boden exakt einmal`(@TempDir dir: File) {
        upfrontMahlzeit(dir, 1.0)
        bisSofortdosis(dir)
        // AAPS hat sie nie kommandiert - der Beweis kommt im Folgezyklus.
        letzterAusgang = Ausgang.NIE_KOMMANDIERT
        val beweis = transport(dir)
        assertEquals(QueueRejectReason.BOLUS_IN_QUEUE, letzterGrund, "der Beweis muss stehen")
        // Die Entlastung hat den Boden wiederbelebt: im Beweiszyklus selbst
        // oder im naechsten kommt GENAU EIN neuer Versuch ...
        letzterAusgang = Ausgang.GESENDET
        val neu = if (beweis.phaseAUpfrontRequestedU > 0.0) beweis else bisSofortdosis(dir, 4)
        assertEquals(3.0, neu.phaseAUpfrontRequestedU, 1e-9, "genau ein neuer Versuch, volle Menge")
        // ... und danach ist wieder Schluss.
        repeat(6) {
            assertEquals(0.0, transport(dir).phaseAUpfrontRequestedU, 1e-9, "nicht mehr als einer")
        }
    }

    /** Pflichttest 9: Neustart VOR der Dosis setzt den persistierten Boden
     *  fort - "sofort, wenn sicher; aufgeschoben, wenn gemessen unsicher;
     *  niemals verloren oder doppelt". Der harte 30er-Abwaertsriegel
     *  verhindert die Dosis ganz (kein Lift, kein Withhold), die armierte
     *  Autorisierung ist persistiert, und nach Neustart + bestaetigter
     *  Erholung kommt sie genau einmal. */
    @Test
    fun `neustart vor der dosis setzt den boden fort`(@TempDir dir: File) {
        // Breites, GEPINNTES Prime-Fenster: die Erholung nach dem Neustart
        // braucht mehr als die geklemmten 5 Minuten des unstubbten Rigs.
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(40)
        upfrontAnteil = 1.0
        primeHuelleU = 3.75
        fundamentAnteil = 0.8
        aufschubAn = true
        mahlzeit(dir)
        // Steiler, ueberdeckter Fall im harten 30er-Horizont: BG 150,
        // -3,0/min -> Boden in ~27 min; 2,0 U x 54 deckt den Abstand.
        flach = 150.0
        steigungProMin = -3.0
        bolusIobU = 2.0
        repeat(6) { transport(dir) }
        assertEquals(0.0, ledger.episodes.deliveredPhaseAU, 1e-9, "im gemessenen Fall geht nichts hinaus")
        assertTrue(ledger.episodes.foundation.valid, "aber armiert und persistiert")
        // Neustart, danach bestaetigte Erholung (steigende Kurve oeffnet
        // den restartfesten Abwaertsriegel nach drei gesunden Zyklen).
        // Die Uhr laeuft MONOTON weiter - die Kurve wird nur neu verankert,
        // damit sie ab jetzt bei ~130 steigend weiterlaeuft.
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", clock) })
        steigungProMin = 0.8
        flach = 130.0 - 0.8 * ((clock - start) / 60_000.0)
        knickAbMin = null
        bolusIobU = null
        val d = bisSofortdosis(dir, 30)
        // DER GANZE offene Rest in EINEM Zug. Nicht starr 3,0: flossen in
        // der Erholung schon regulaere Phase-A-Mengen, verkleinern sie den
        // Batch sofort (Vertrag 6) - die Invariante ist "Batch = offener
        // Rest desselben Zyklus", nicht eine feste Zahl.
        assertEquals(d.phaseAUpfrontPendingU, d.phaseAUpfrontRequestedU, 1e-9) {
            "nach dem Neustart der ganze Rest in einem Zug: " +
                "${d.phaseAUpfrontRequestedU} von ${d.phaseAUpfrontPendingU}"
        }
        assertTrue(d.phaseAUpfrontRequestedU > 2.5) {
            "und der Groessenordnung nach die volle Menge: ${d.phaseAUpfrontRequestedU}"
        }
        // Der restartfeste Abwaertsriegel kann die ersten Anforderungen
        // noch nullen (Erholung braucht drei gesunde Zyklen) - der Boden
        // fordert dann WEITER, bis wirklich gebucht ist. Nie verloren:
        repeat(10) { if (ledger.episodes.deliveredPhaseAU < 3.0 - 1e-9) transport(dir) }
        assertEquals(3.0, ledger.episodes.deliveredPhaseAU, 1e-9, "genau einmal geliefert")
        // Und nie doppelt: ab der Buchung ist der Boden zu.
        repeat(4) { assertEquals(0.0, transport(dir).phaseAUpfrontRequestedU, 1e-9, "nie doppelt") }
    }

    /** Pflichttest 17: maxIOB und Transporthaftung bleiben hart - die
     *  Sofortdosis wird am IOB-Spielraum gekappt, und solange die
     *  publizierte Menge als offene Haftung steht, zieht der Boden NICHT
     *  nach (konservativ: der Rest bleibt sichtbar offen statt den
     *  Spielraum doppelt zu belegen). */
    @Test
    fun `maxiob und transporthaftung kappen die sofortdosis hart`(@TempDir dir: File) {
        upfrontMahlzeit(dir, 1.0)
        bolusIobU = 6.5 // maxIOB 8 -> Headroom 1,5 U
        val d1 = bisSofortdosis(dir)
        assertEquals(1.5, d1.phaseAUpfrontRequestedU, 1e-9, "gekappt am IOB-Spielraum")
        assertEquals(1.5, ledger.episodes.deliveredPhaseAU, 1e-9)
        // Die offene Zeile haftet (im Rig bindet nie ein Pumpenfakt): IOB
        // 6,5 + Haftung 1,5 fuellen maxIOB 8 exakt - kein Nachzug.
        repeat(6) {
            val o = transport(dir)
            assertEquals(0.0, o.phaseAUpfrontRequestedU, 1e-9, "Haftung + IOB sperren den Nachzug")
            assertEquals(1.5, o.phaseAUpfrontPendingU, 1e-9, "der Rest bleibt sichtbar offen")
        }
        assertEquals(1.5, ledger.episodes.deliveredPhaseAU, 1e-9, "nie ueber den Spielraum")
    }


    // ---- FALL 1: BOLUS_IN_QUEUE in Phase A --------------------------------

    /**
     * TONIS 19:07-FALL, durch die ganze Kette.
     *
     * AAPS liess nach seinen Constraints eine positive Menge stehen, hat den
     * Apply-Block aber nie betreten. [NotSentProof] nennt das
     * `BOLUS_IN_QUEUE` - der Grund, den die urspruengliche Grundliste
     * ausgelassen haette.
     *
     * Geprueft werden die BUECHER einzeln, die publizierte Menge und der
     * Zustand NACH einem Neustart - nicht nur ein Summenwert.
     */
    @Test
    fun `E2E BOLUS_IN_QUEUE in Phase A - exakte Rueckbuchung und Uebertrag`(@TempDir dir: File) {
        mahlzeit(dir)
        val o = bisPhaseABuchung(dir)
        val menge = o.decision.smbU
        // DEN BEWEISZYKLUS RUHIG STELLEN - s. [ruhigStellen].
        ruhigStellen()
        val vorher = ledger.episodes
        val primeVor = vorher.primeSpentU
        val evidenzVor = vorher.evidenceCommittedU
        val phaseAVor = vorher.deliveredPhaseAU
        val zeilenVor = vorher.mealDeliveries.size

        // AAPS hat sie NIE KOMMANDIERT - der Beweis kommt im Folgezyklus.
        letzterAusgang = Ausgang.NIE_KOMMANDIERT
        val beweis = transport(dir)
        assertEquals(
            0.0, beweis.decision.smbU, 1e-9,
            "der Beweiszyklus darf NICHTS buchen, sonst misst der Test zwei Vorgaenge auf einmal",
        )

        assertEquals(
            QueueRejectReason.BOLUS_IN_QUEUE, letzterGrund,
            "der Beweis MUSS aus der Beobachtung entstehen, nicht aus einer Liste",
        )
        val e = ledger.episodes
        assertEquals(primeVor - menge, e.primeSpentU, 1e-9, "primeSpentU")
        assertEquals(evidenzVor - menge, e.evidenceCommittedU, 1e-9, "evidenceCommittedU")
        assertEquals(phaseAVor - menge, e.deliveredPhaseAU, 1e-9, "deliveredPhaseAU")
        assertEquals(zeilenVor - 1, e.mealDeliveries.size, "die Mahlzeitenzeile verschwindet")
        assertEquals(menge, e.confirmedNotSentPhaseAU, 1e-9, "und genau sie steht als Uebertrag")

        // UND DURABEL: nach einem Neustart steht derselbe Befund in der Datei.
        val nach = nachNeustart(dir)
        assertEquals(
            menge, nach.confirmedNotSentPhaseAU, 1e-9,
            "der Uebertrag MUSS den Neustart ueberleben",
        )
        assertEquals(e.deliveredPhaseAU, nach.deliveredPhaseAU, 1e-9, "der Phase-A-Stand auch")
    }

    // ---- FALL 2: CONSTRAINT_ZERO in Phase A -------------------------------

    /** Dieselbe Kette, anderer Beweis: AAPS hat selbst genullt. Der Grund
     *  aendert am Ergebnis NICHTS - genau das ist der Vertrag. */
    @Test
    fun `E2E CONSTRAINT_ZERO in Phase A - dasselbe Ergebnis`(@TempDir dir: File) {
        mahlzeit(dir)
        val o = bisPhaseABuchung(dir)
        val menge = o.decision.smbU
        ruhigStellen()
        val phaseAVor = ledger.episodes.deliveredPhaseAU

        letzterAusgang = Ausgang.CONSTRAINT_NULL
        val beweis = transport(dir)
        assertEquals(0.0, beweis.decision.smbU, 1e-9, "der Beweiszyklus bucht nichts")

        assertEquals(QueueRejectReason.CONSTRAINT_ZERO, letzterGrund)
        assertEquals(menge, ledger.episodes.confirmedNotSentPhaseAU, 1e-9)
        assertEquals(phaseAVor - menge, ledger.episodes.deliveredPhaseAU, 1e-9)
        assertEquals(menge, nachNeustart(dir).confirmedNotSentPhaseAU, 1e-9, "durabel")
    }

    // ---- FALL 3: unklarer Ausgang -----------------------------------------

    /**
     * OHNE BEWEIS BLEIBT ALLES STEHEN - der konservative Ausgang.
     *
     * Die Buchung bleibt als geliefert stehen, FUSE liefert spaeter zu wenig
     * statt zu viel. Das ist die einzige Richtung, die dieser Ledger raten
     * darf.
     */
    @Test
    fun `E2E unklarer Ausgang - keine Rueckbuchung, kein Uebertrag`(@TempDir dir: File) {
        mahlzeit(dir)
        bisPhaseABuchung(dir)
        ruhigStellen()
        val e = ledger.episodes
        val primeVor = e.primeSpentU
        val evidenzVor = e.evidenceCommittedU
        val phaseAVor = e.deliveredPhaseAU
        val zeilenVor = e.mealDeliveries.size

        letzterAusgang = Ausgang.UNKLAR
        val beweis = transport(dir)
        assertEquals(0.0, beweis.decision.smbU, 1e-9, "der Beweiszyklus bucht nichts")
        assertNull(letzterGrund, "ein unauswertbarer Ausgang ist KEIN Beweis")
        assertEquals(0.0, e.confirmedNotSentPhaseAU, 1e-9, "und erzeugt keinen Uebertrag")
        // EXAKT GLEICH, nicht ">= vorher" (Codex 19.08.). Der ruhige Zyklus
        // bucht nichts, also darf sich kein Buch bewegen - in KEINE Richtung.
        // Eine Ungleichung liesse eine Phantom-Buchung durch und der Test
        // bliebe gruen.
        assertEquals(primeVor, e.primeSpentU, 1e-9, "primeSpentU unveraendert")
        assertEquals(evidenzVor, e.evidenceCommittedU, 1e-9, "evidenceCommittedU unveraendert")
        assertEquals(phaseAVor, e.deliveredPhaseAU, 1e-9, "deliveredPhaseAU unveraendert")
        assertEquals(zeilenVor, e.mealDeliveries.size, "und keine Zeile kommt oder geht")
        assertEquals(0.0, nachNeustart(dir).confirmedNotSentPhaseAU, 1e-9, "auch nach Neustart nicht")
    }

    /**
     * DIE ZWEITE GESTALT DES UNKLAREN AUSGANGS: die Beobachtung SAEHE aus wie
     * ein Beweis - positive Menge nach Constraints, Apply-Block nie betreten -,
     * beschreibt aber nachweislich einen ANDEREN Lauf.
     *
     * WARUM EIN EIGENER TEST UND KEIN ZWEITER SCHRITT IM VORIGEN: nach einem
     * ruhigen Zyklus gibt es keine offene Zeile mehr, der Beleg wird also gar
     * nicht mehr gebildet. Der Fall braucht eine frische Phase-A-Buchung
     * unmittelbar davor.
     *
     * Und warum er ueberhaupt existiert: eine Mutationsprobe hat gezeigt, dass
     * der unauswertbare Fall die Korrelationspruefung in [NotSentProof] gar
     * nicht erreicht - dort sind ohnehin alle Werte null. Ohne diesen Test
     * blieb das Entfernen der Pruefung gruen.
     */
    @Test
    fun `E2E fremder Lauf - keine Rueckbuchung, kein Uebertrag`(@TempDir dir: File) {
        mahlzeit(dir)
        bisPhaseABuchung(dir)
        ruhigStellen()
        val e = ledger.episodes
        val primeVor = e.primeSpentU
        val phaseAVor = e.deliveredPhaseAU
        val zeilenVor = e.mealDeliveries.size

        letzterAusgang = Ausgang.UNKORRELIERT
        val beweis = transport(dir)
        assertEquals(0.0, beweis.decision.smbU, 1e-9, "der Beweiszyklus bucht nichts")

        assertNull(letzterGrund, "ein fremder Lauf ist KEIN Beweis")
        assertEquals(0.0, e.confirmedNotSentPhaseAU, 1e-9, "und erzeugt keinen Uebertrag")
        assertEquals(primeVor, e.primeSpentU, 1e-9, "keine Entlastung")
        assertEquals(phaseAVor, e.deliveredPhaseAU, 1e-9)
        assertEquals(zeilenVor, e.mealDeliveries.size, "keine Zeile verschwindet")
    }

    // ---- FALL 4: bewiesenes Nicht-Senden in Phase B -----------------------

    /**
     * PHASE B WIRD ZURUECKGEBUCHT, BEKOMMT ABER KEINEN UEBERTRAG.
     *
     * `deliveredSinceHandoverU` sinkt - damit steht das zeitliche Soll von
     * selbst wieder offen. Ein Uebertrag obendrauf waere dieselbe Menge
     * ZWEIMAL.
     */
    @Test
    fun `E2E Nicht-Senden in Phase B - Rueckbuchung ohne Uebertrag`(@TempDir dir: File) {
        mahlzeit(dir)
        // Weit hinter die Uebergabe fahren, bis eine PHASE_B-Menge gebucht ist.
        //
        // MIT ECHTEM ABBRUCH. Die erste Fassung hatte hier `return@repeat` -
        // das verlaesst nur den EINEN Schleifendurchlauf, nicht die Schleife.
        // Sie lief also weiter, `mengeB` trug am Ende irgendeine spaetere
        // Menge, und `settled` gehoerte zu einem ganz anderen Zyklus.
        var mengeB = 0.0
        for (i in 0 until 40) {
            val o = transport(dir)
            if (o.decision.smbU > 0.0 &&
                ledger.episodes.settled?.foundationPhase == MealFoundation.Phase.PHASE_B
            ) {
                mengeB = o.decision.smbU
                break
            }
        }
        assertTrue(mengeB > 0.0, "der Aufbau muss eine Phase-B-Buchung erzeugen")
        ruhigStellen()
        val bezahltVor = ledger.episodes.deliveredSinceHandoverU

        letzterAusgang = Ausgang.NIE_KOMMANDIERT
        val beweis = transport(dir)
        assertEquals(0.0, beweis.decision.smbU, 1e-9, "der Beweiszyklus bucht nichts")

        val e = ledger.episodes
        assertTrue(
            e.deliveredSinceHandoverU < bezahltVor - 1e-9,
            "der Bezahlstand MUSS sinken: $bezahltVor -> ${e.deliveredSinceHandoverU}",
        )
        assertEquals(
            0.0, e.confirmedNotSentPhaseAU, 1e-9,
            "aber Phase B bekommt keinen Uebertrag - das waere die Menge zweimal",
        )
        assertEquals(0.0, nachNeustart(dir).confirmedNotSentPhaseAU, 1e-9, "auch durabel nicht")
    }

    // ---- FALL 5: fremde Kennung -------------------------------------------

    /**
     * EINE FREMDE KENNUNG AENDERT NICHTS.
     *
     * Der Beweis kommt ueber die `proposalId`; passt sie nicht, gibt es
     * nichts zuzuordnen - und dann darf auch nichts geschehen. Sonst
     * entlastete ein Beleg eine Buchung, die er gar nicht beschreibt.
     */
    @Test
    fun `E2E fremde Kennung - alles unveraendert`(@TempDir dir: File) {
        mahlzeit(dir)
        bisPhaseABuchung(dir)
        ruhigStellen()
        val e = ledger.episodes
        val primeVor = e.primeSpentU
        val phaseAVor = e.deliveredPhaseAU
        val zeilenVor = e.mealDeliveries.size

        // (a) UNBEKANNTE KENNUNG: es gibt gar keine offene Zeile dazu, der
        // Beleg wird also erst gar nicht gebildet.
        pPropId = "e2e#fremd"
        letzterAusgang = Ausgang.NIE_KOMMANDIERT
        transport(dir)

        assertEquals(0.0, e.confirmedNotSentPhaseAU, 1e-9, "kein Uebertrag ohne Zuordnung")
        // EXAKT GLEICH - s. den unauswertbaren Fall.
        assertEquals(primeVor, e.primeSpentU, 1e-9, "keine Entlastung")
        assertEquals(phaseAVor, e.deliveredPhaseAU, 1e-9)
        assertEquals(zeilenVor, e.mealDeliveries.size, "und keine Zeile kommt oder geht")
    }


    /**
     * DIE ZWEITE GESTALT DER FALSCHEN KENNUNG: der Beleg nennt eine Zeile,
     * die es SEHR WOHL gibt - nur gehoert die abgeschlossene Buchung zu einer
     * anderen.
     *
     * WARUM DAS EIN EIGENER TEST IST, und das hat wieder erst eine
     * Mutationsprobe gezeigt: bei einer voellig unbekannten Kennung greift
     * schon `hasOpenProposal`, und der Beleg wird gar nicht erst gebildet.
     * Die Kennungspruefung IN `revokeSettled` wurde damit nie erreicht - das
     * Entfernen der Zeile `if (s.proposalId != proposalId) return NONE` blieb
     * gruen.
     *
     * Hier ist die genannte Zeile offen, die Ablage traegt aber den
     * NACHFOLGER. Genau die Lage, in der ein zu grosszuegiges Zuordnen eine
     * fremde Menge entlasten wuerde.
     */
    @Test
    fun `E2E offene aber fremde Kennung - alles unveraendert`(@TempDir dir: File) {
        mahlzeit(dir)
        bisPhaseABuchung(dir)
        // Die Kennung des ERSTEN Zyklus merken - sie bleibt offen, bis ein
        // IOB-Fakt sie bindet.
        val alteId = pPropId ?: throw AssertionError("der erste Zyklus muss eine offene Zeile haben")

        // Ein ZWEITER Buchungszyklus: die Ablage traegt jetzt ihn.
        transport(dir)
        assertTrue(
            ledger.hasOpenProposal(alteId),
            "die alte Zeile MUSS noch offen sein, sonst greift schon hasOpenProposal",
        )
        assertTrue(
            ledger.episodes.settled?.proposalId != alteId,
            "die Ablage MUSS den Nachfolger tragen - sonst prueft der Test nichts",
        )

        ruhigStellen()
        val e = ledger.episodes
        val primeVor = e.primeSpentU
        val evidenzVor = e.evidenceCommittedU
        val phaseAVor = e.deliveredPhaseAU
        val zeilenVor = e.mealDeliveries.size

        // DER BELEG NENNT DIE ALTE, OFFENE ZEILE.
        pPropId = alteId
        letzterAusgang = Ausgang.NIE_KOMMANDIERT
        val beweis = transport(dir)
        assertEquals(0.0, beweis.decision.smbU, 1e-9, "der Beweiszyklus bucht nichts")

        assertEquals(
            0.0, e.confirmedNotSentPhaseAU, 1e-9,
            "eine fremde Buchung darf keinen Uebertrag erzeugen",
        )
        assertEquals(primeVor, e.primeSpentU, 1e-9, "primeSpentU unveraendert")
        assertEquals(evidenzVor, e.evidenceCommittedU, 1e-9, "evidenceCommittedU unveraendert")
        assertEquals(phaseAVor, e.deliveredPhaseAU, 1e-9, "deliveredPhaseAU unveraendert")
        assertEquals(zeilenVor, e.mealDeliveries.size, "und keine Zeile kommt oder geht")
    }

    // ---- FALL 6: Prime holt vor der Uebergabe nach ------------------------

    /** Die Fundament-Sicht auf den AKTUELLEN Ledger-Stand. */
    private fun sicht(e: EpisodeBudgets = ledger.episodes) = MealFoundation.snapshot(
        e.foundation, clock, e.primeWindowStartTs,
        deliveredFromBudgetU = e.deliveredPhaseAU + e.deliveredSinceHandoverU,
        deliveredSinceHandoverU = e.deliveredSinceHandoverU,
        deliveredPhaseAU = e.deliveredPhaseAU,
        confirmedNotSentPhaseAU = e.confirmedNotSentPhaseAU,
        descentDeferredPhaseAU = e.descentDeferredPhaseAU,
        descentCarryEligibility = app.aaps.fuse.core.controller.DescentDeferredCarry.Eligibility.NO_DEFERRED,
        bolusStepU = 0.05,
    )

    /**
     * DER MENGEN-ZEIT-VERTRAG, als echter VORHER/NACHHER-Beleg
     * (Codex 19.08. - die erste Fassung bewies ihn NICHT).
     *
     * WAS AN DER ERSTEN FASSUNG FALSCH WAR, und es ist dieselbe Sorte Fehler
     * wie beim zurueckgezogenen E2E:
     *
     *   `repeat(8)` garantierte nicht, dass Prime die Luecke ueberhaupt
     *   schliesst - der Test lief eine feste Zahl Zyklen und behauptete
     *   danach etwas ueber einen Zustand, den er nicht hergestellt hatte;
     *
     *   `effectiveCarryU <= menge` ist erfuellt, wenn der Uebertrag
     *   UNVERAENDERT voll bleibt - die Zeile konnte den Fehler nicht finden,
     *   gegen den sie stand;
     *
     *   und die Erwartungswerte wurden aus DEMSELBEN Snapshot
     *   zurueckgerechnet, den sie pruefen sollten. Das prueft die Formel
     *   gegen sich selbst, nicht den Uebergang.
     *
     * Hier stehen jetzt zwei ABSOLUTE Zustaende, aus der Autorisierung
     * abgeleitet, und dazwischen laeuft die Schleife BIS ZUR BELEGTEN
     * BEDINGUNG statt eine feste Zahl Zyklen.
     *
     * DASS PHASE A DAS BUDGET UEBERSCHREITEN KANN, ist kein Fehler im
     * Aufbau: `deliveredPhaseAU` zaehlt ALLES, was in der Phase floss, und
     * Korrektur- und Evidenzinsulin duerfen ausdruecklich ueber das
     * Mahlzeitenbudget hinausgehen. Die Bedingung lautet deshalb
     * "Rueckstand geschlossen", nicht "exakt gleich".
     */
    @Test
    fun `E2E Prime holt nach - Rohzaehler bleibt, Wirkung und Rampe fallen`(@TempDir dir: File) {
        mahlzeit(dir)
        val o = bisPhaseABuchung(dir)
        val menge = o.decision.smbU
        val phaseABudget = ledger.episodes.foundation.phaseABudgetU
        val phaseBBudget = ledger.episodes.foundation.phaseBBudgetU
        assertTrue(phaseBBudget > 0.0, "der Aufbau braucht ein Phase-B-Budget")

        // ---- (1) DIREKT NACH DEM BEWEIS: die Luecke ist offen ------------
        ruhigStellen()
        letzterAusgang = Ausgang.NIE_KOMMANDIERT
        val beweis = transport(dir)
        assertEquals(0.0, beweis.decision.smbU, 1e-9, "der Beweiszyklus bucht nichts")

        val e = ledger.episodes
        assertEquals(menge, e.confirmedNotSentPhaseAU, 1e-9, "der rohe Beweiszaehler")
        assertTrue(
            e.deliveredPhaseAU < phaseABudget - 1e-9,
            "die Luecke MUSS offen sein: ${e.deliveredPhaseAU} von $phaseABudget",
        )

        val offen = sicht()
        assertEquals(menge, offen.effectiveCarryU, 1e-9, "der Uebertrag gilt voll")
        assertEquals(
            minOf(phaseBBudget + menge, offen.totalBudgetU), offen.phaseBAllowanceU, 1e-9,
            "und hebt die Erlaubnis",
        )
        val normaleRate = phaseBBudget / offen.effectiveWindowMin
        assertTrue(
            offen.effectiveRateUPerMin > normaleRate + 1e-9,
            "die Rampe MUSS angehoben sein: ${offen.effectiveRateUPerMin} gegen $normaleRate",
        )

        // ---- (2) PRIME LIEFERT WIRKLICH NACH -----------------------------
        //
        // BIS ZUR BEDINGUNG, mit hartem Deckel. Eine feste Zyklenzahl wuerde
        // wieder einen Zustand behaupten statt ihn herzustellen.
        flach = 180.0
        steigungProMin = 2.5
        var zyklen = 0
        while (ledger.episodes.deliveredPhaseAU < phaseABudget - 1e-9) {
            assertTrue(
                zyklen++ < 30,
                "Prime hat die Luecke in 30 Zyklen nicht geschlossen: " +
                    "${ledger.episodes.deliveredPhaseAU} von $phaseABudget",
            )
            transport(dir)
        }

        // ---- (3) DER ENDZUSTAND ------------------------------------------
        val zu = sicht()
        assertEquals(
            menge, e.confirmedNotSentPhaseAU, 1e-9,
            "der ROHE Zaehler bleibt - er ist ein Beweis, kein Konto",
        )
        assertEquals(0.0, zu.effectiveCarryU, 1e-9, "wirkt aber nicht mehr")
        assertEquals(
            phaseBBudget, zu.phaseBAllowanceU, 1e-9,
            "Phase B rechnet wieder mit ihrem Teilbudget",
        )
        assertEquals(
            phaseBBudget / zu.effectiveWindowMin, zu.effectiveRateUPerMin, 1e-9,
            "UND DIE RAMPE FAELLT MIT - das ist der eigentliche Schaden, nicht die Summe",
        )

        // ---- (4) UND DER ENDZUSTAND UEBERLEBT DEN NEUSTART ---------------
        val nach = nachNeustart(dir)
        assertEquals(menge, nach.confirmedNotSentPhaseAU, 1e-9, "der Beweis bleibt durabel")
        assertTrue(
            nach.deliveredPhaseAU >= phaseABudget - 1e-9,
            "und der geschlossene Rueckstand auch: ${nach.deliveredPhaseAU}",
        )
        val nachSicht = sicht(nach)
        assertEquals(0.0, nachSicht.effectiveCarryU, 1e-9, "nach dem Neustart wirkt er ebenso wenig")
        assertEquals(phaseBBudget, nachSicht.phaseBAllowanceU, 1e-9)
        assertEquals(
            phaseBBudget / nachSicht.effectiveWindowMin, nachSicht.effectiveRateUPerMin, 1e-9,
            "sonst liefe Phase B nach jedem Neustart wieder zu schnell",
        )
    }


    // ==== DER RUNNER-REPLAY (Toni/Codex 19.08.) =============================
    //
    // WAS IHN VOM OFFLINE-REPLAY UNTERSCHEIDET, und es ist genau das, was dort
    // fehlte: hier laeuft der ECHTE Regler. Guard, Tail, iobTH, maxIOB,
    // Transport und das Publikationsgate sind nicht simuliert, sondern
    // wirksam - gemessen wird deshalb die PUBLIZIERTE Menge, nicht die
    // Forderung.
    //
    // EIN-VARIABLEN-DISZIPLIN: Gesamtbudget (PrimeEnvelopeU) und Fenster
    // (MealFoundationEndMin, PrimeWindowMin) bleiben ueber alle Laeufe
    // konstant; variiert wird ausschliesslich der Phase-A-Anteil.
    //
    // DIE IOB-SPITZE wird aus den publizierten Mengen mit DEMSELBEN
    // Insulinmodell gerechnet, das der Loop benutzt (`AapsUnitInsulinSampler`
    // ueber das AAPS-Insulinplugin). Eine eigene Kurve waere eine zweite
    // Wahrheit; der IOB-Wert des Rigs taugt nicht, er steht fest auf 0.
    //
    // DIE GRENZE DIESES RIGS, ausdruecklich: die Glukosebahn ist synthetisch
    // (Grundwert + konstante Steigung). Sie ist ueber alle vier Aufteilungen
    // IDENTISCH, der Vergleich ist also sauber - aber es ist keine echte
    // Mahlzeitenkurve. Aussagen ueber Blutzuckerverlaeufe stehen hier
    // nirgends.

    private class Lauf(
        val anteil: Double,
        val form: String,
        /** Kumulativ PUBLIZIERT bei T+15/30/45/60. */
        val bei: Map<Int, Double>,
        val publiziertU: Double,
        val leerlaufMin: Int,
        val iobSpitzeU: Double,
        val iobSpitzeMin: Int,
        /** Kumulativ: was der NORMALE Pfad vor dem Fundament wollte. */
        val normalBei: Map<Int, Double>,
        /** Kumulativ: was das FUNDAMENT darueber hinaus anhob. */
        val fundamentBei: Map<Int, Double>,
        /** Wieviele Zyklen hat das Fundament ueberhaupt angehoben. */
        val fundamentZyklen: Int,
        /** Davon: angehoben, aber am Ende NICHTS publiziert - der teure Fall. */
        val fundamentGebremst: Int,
        /** Welche Grenzen ueberhaupt gebunden haben - typisiert, gezaehlt. */
        val bindungen: Map<String, Int>,
        val fundamentBindung: String?,
        val effektiverUebertragU: Double,
        val restRueckstandU: Double,
    )

    /**
     * Die IOB-Spitze aus den publizierten Mengen - mit dem Loop-Modell.
     *
     * @param gaben (Zeitstempel, Menge) jeder wirklich publizierten Abgabe.
     */
    private fun iobSpitze(gaben: List<Pair<Long, Double>>, bisTs: Long): Pair<Double, Int> {
        if (gaben.isEmpty()) return 0.0 to 0
        val start = gaben.first().first
        var spitze = 0.0
        var spitzeMin = 0
        var t = start
        while (t <= bisTs) {
            var iob = 0.0
            for ((ts, menge) in gaben) {
                if (ts > t) continue
                val sampler = AapsUnitInsulinSampler(insulin, diaHours = 9.0, deliveryTs = ts)
                iob += sampler.sampleAfterDelivery(menge, ((t - ts) / 60_000L).toInt()).iobU
            }
            if (iob > spitze) {
                spitze = iob
                spitzeMin = ((t - start) / 60_000L).toInt()
            }
            t += 60_000L
        }
        return spitze to spitzeMin
    }

    /**
     * EIN vollstaendiger Lauf ueber Marker + Fenster, mit echter Aktuation.
     *
     * @param anstieg die Mahlzeitenantwort [mg/dl/min]: schnell, langsam oder
     *   ausbleibend.
     */
    private fun runnerLauf(dir: File, anteil: Double, form: String, anstieg: Double): Lauf {
        fundamentAn = true
        fundamentAnteil = anteil
        fundamentEndeMin = 60
        markerAuthorized = true
        flach = 140.0
        steigungProMin = anstieg
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        markerAt = start + 2 * 60_000L

        val gaben = mutableListOf<Pair<Long, Double>>()
        val bindungen = mutableMapOf<String, Int>()
        var leerlauf = 0
        var maxLeerlauf = 0
        val kumuliert = mutableMapOf<Int, Double>()
        val kumNormal = mutableMapOf<Int, Double>()
        val kumFundament = mutableMapOf<Int, Double>()
        var summe = 0.0
        var summeNormal = 0.0
        var summeFundament = 0.0
        var fundamentZyklen = 0
        var fundamentGebremst = 0

        for (min in 0..75) {
            val o = transport(dir)
            // DIE PUBLIZIERTE Menge - `letzteMengeU` ist der Stand NACH dem
            // Gate, nicht die Forderung des Reglers.
            val publiziert = letzteMengeU ?: 0.0
            if (publiziert > 0.0) {
                gaben += clock to publiziert
                summe += publiziert
                leerlauf = 0
            } else {
                leerlauf++
                if (leerlauf > maxLeerlauf) maxLeerlauf = leerlauf
            }
            // BINDENDE GRENZEN, typisiert gezaehlt. `block` ist der harte
            // Riegel des Reglers, `bindingLimit` die weiche Deckelung.
            if (o.decision.block != FuseController.Block.NONE)
                bindungen.merge(o.decision.block.name, 1, Int::plus)
            o.decision.bindingLimit?.takeIf { it != "NONE" }
                ?.let { bindungen.merge(it, 1, Int::plus) }
            // DIE DREI SPUREN GETRENNT (Toni 19.08.): was der normale Pfad
            // wollte, was das Fundament anhob, was wirklich hinausging.
            summeNormal += o.preFoundationSmbU
            summeFundament += o.foundationLiftU
            if (o.foundationLiftU > 0.0) {
                fundamentZyklen++
                // ANGEHOBEN, ABER NICHTS PUBLIZIERT: das Fundament hat
                // gefordert und ein Gate hat es ganz weggenommen.
                if (publiziert <= 0.0) fundamentGebremst++
            }
            val seitMarker = ((clock - (markerAt)) / 60_000L).toInt()
            if (seitMarker in listOf(15, 30, 45, 60)) {
                kumuliert[seitMarker] = summe
                kumNormal[seitMarker] = summeNormal
                kumFundament[seitMarker] = summeFundament
            }
        }

        val e = ledger.episodes
        val sicht = sicht(e)
        val (spitze, spitzeMin) = iobSpitze(gaben, clock)
        return Lauf(
            anteil = anteil, form = form,
            bei = listOf(15, 30, 45, 60).associateWith { kumuliert[it] ?: summe },
            publiziertU = summe, leerlaufMin = maxLeerlauf,
            iobSpitzeU = spitze, iobSpitzeMin = spitzeMin,
            normalBei = listOf(15, 30, 45, 60).associateWith { kumNormal[it] ?: summeNormal },
            fundamentBei = listOf(15, 30, 45, 60).associateWith { kumFundament[it] ?: summeFundament },
            fundamentZyklen = fundamentZyklen, fundamentGebremst = fundamentGebremst,
            bindungen = bindungen, fundamentBindung = sicht.binding?.name,
            effektiverUebertragU = sicht.effectiveCarryU,
            restRueckstandU = max(0.0, sicht.phaseBAllowanceU - e.deliveredSinceHandoverU),
        )
    }

    /**
     * DIE VERGLEICHSTAFEL - ausgegeben, nicht festgeschrieben.
     *
     * Eine Replay-Zahl als Zusicherung zu setzen hiesse, eine Hypothese zur
     * Regel zu machen. Festgeschrieben sind nur die Aussagen, die aus der
     * Bauform folgen (darunter).
     */
    @Test
    fun `Runner-Replay ueber Aufteilung und Mahlzeitenantwort`(@TempDir dir: File) {
        val formen = listOf("schnell" to 2.5, "langsam" to 0.8, "ausbleibend" to 0.0)
        // DREI SPUREN JE ZEITPUNKT: norm = was der normale Pfad wollte,
        // fnd = was das Fundament anhob, pub = was publiziert wurde. Erst ihr
        // Verhaeltnis unterscheidet "Fundament laeuft, Zusatzbedarf gebremst"
        // von "Fundament selbst blockiert".
        println(
            "RUN anteil;form;" +
                "pub15;pub30;pub45;pub60;norm60;fnd60;fndZyklen;fndGebremst;" +
                "publiziertU;leerlaufMin;iobSpitzeU;iobSpitzeMin;" +
                "fundamentBindung;effUebertragU;restRueckstandU;bindungen"
        )
        for ((form, anstieg) in formen) {
            for (anteil in listOf(1.00, 0.80, 0.75, 0.67)) {
                val r = runnerLauf(File(dir, "s${(anteil * 100).toInt()}_$form"), anteil, form, anstieg)
                println(
                    "RUN %.2f;%s;%.3f;%.3f;%.3f;%.3f;%.3f;%.3f;%d;%d;%.3f;%d;%.3f;%d;%s;%.3f;%.3f;%s".format(
                        r.anteil, r.form, r.bei[15], r.bei[30], r.bei[45], r.bei[60],
                        r.normalBei[60], r.fundamentBei[60], r.fundamentZyklen, r.fundamentGebremst,
                        r.publiziertU, r.leerlaufMin, r.iobSpitzeU, r.iobSpitzeMin,
                        r.fundamentBindung ?: "-", r.effektiverUebertragU, r.restRueckstandU,
                        r.bindungen.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
                            .ifEmpty { "-" },
                    )
                )
            }
        }
    }

    /**
     * DER GESAMTDECKEL HAELT - ueber jede Aufteilung und jede
     * Mahlzeitenantwort.
     *
     * DAS IST DIE ZUSICHERUNG, die "kein neues Budget" wirklich bedeutet
     * (Toni 19.08.): niemals mehr als das gepinnte Gesamtbudget aus Phase A,
     * Phase B und Uebertrag zusammen. NICHT: dieselbe Menge wie bei 100/0 -
     * dann koennte das Fundament gerade keine Versorgungsluecke schliessen.
     *
     * Gemessen wird der FUNDAMENT-Anteil, nicht die Gesamtabgabe: Korrektur-
     * und Evidenzinsulin duerfen ausdruecklich zusaetzlich entstehen.
     */
    @Test
    fun `ueber alle Aufteilungen bleibt das Fundament unter dem Gesamtbudget`(@TempDir dir: File) {
        for ((form, anstieg) in listOf("schnell" to 2.5, "langsam" to 0.8, "ausbleibend" to 0.0)) {
            for (anteil in listOf(1.00, 0.80, 0.75, 0.67)) {
                runnerLauf(File(dir, "cap${(anteil * 100).toInt()}_$form"), anteil, form, anstieg)
                val e = ledger.episodes
                val budget = e.foundation.totalBudgetU
                assertTrue(budget > 0.0, "$form/$anteil: die Autorisierung MUSS stehen")
                // HIER STAND EINE FALSCHE ZUSICHERUNG, und sie ist beim ersten
                // Lauf umgefallen: `deliveredPhaseAU + deliveredSinceHandoverU`
                // sei durch das Budget begrenzt. Ist sie nicht - beide Zaehler
                // zaehlen ALLES, was in ihrer Phase floss, auch gewoehnliche
                // Korrektur, und die darf ausdruecklich zusaetzlich zum
                // Mahlzeitenbudget entstehen (bestaetigter Vertrag). Gemessen
                // wurde 1,2 + 0,1 bei Budget 1,2, und das ist gesund.
                //
                // Dieselbe Verwechslung wie beim vorgeschlagenen Codec-Riegel:
                // "was das Fundament geben darf" ist nicht "was in seinem
                // Fenster fliesst". Pruefbar ist deshalb die ERLAUBNIS.
                val sicht = sicht(e)
                assertTrue(
                    sicht.phaseBAllowanceU <= budget + 1e-9,
                    "$form/$anteil: die Phase-B-Erlaubnis MUSS unter dem Gesamtbudget bleiben",
                )
                // UND DIE FORDERUNG BLEIBT INNERHALB DER ERLAUBNIS - die
                // zweite Haelfte desselben Vertrags. Ohne sie sagte der Test
                // nur, dass die Erlaubnis klein ist, nicht dass sie gilt.
                assertTrue(
                    sicht.dueU <= sicht.remainingInWindowU + 1e-9,
                    "$form/$anteil: das Fundament fordert nie mehr als offen ist: " +
                        "${sicht.dueU} von ${sicht.remainingInWindowU}",
                )
                assertTrue(
                    sicht.effectiveCarryU <= e.confirmedNotSentPhaseAU + 1e-9,
                    "$form/$anteil: der effektive Uebertrag geht nie ueber den Beweis hinaus",
                )
            }
        }
    }

    /**
     * DIE HARTEN NULLFAELLE BLEIBEN HART - ueber jede Aufteilung.
     *
     * Ein gemessenes Tief, ein ungesundes Signal und der Widerruf duerfen vom
     * Fundament NICHT ueberstimmt werden. Das ist die Zusicherung, die
     * unabhaengig von jeder Aufteilung gelten muss - sonst waere die
     * Aufteilung nicht nur eine Verteilungsfrage, sondern eine
     * Sicherheitsfrage.
     */
    @Test
    fun `harte Nullfaelle bleiben ueber jede Aufteilung hart`(@TempDir dir: File) {
        for (anteil in listOf(1.00, 0.80, 0.75, 0.67)) {
            // (a) GEMESSENES TIEF.
            fundamentAn = true
            fundamentAnteil = anteil
            markerAuthorized = true
            flach = 62.0
            steigungProMin = -1.2
            clock = start
            transportReset()
            neuerRunner(FuseLedgerAdapter().also { it.loadOnce(File(dir, "tief$anteil").also(File::mkdirs), "test-epoch", start) })
            markerAt = start + 2 * 60_000L
            var abgegeben = 0.0
            repeat(40) { transport(File(dir, "tief$anteil")); abgegeben += letzteMengeU ?: 0.0 }
            assertEquals(
                0.0, abgegeben, 1e-9,
                "$anteil: bei gemessenem Tief darf das Fundament NICHTS publizieren",
            )

            // (b) WIDERRUF: der Marker wird zurueckgenommen.
            fundamentAnteil = anteil
            flach = 180.0
            steigungProMin = 2.5
            clock = start
            transportReset()
            val d2 = File(dir, "widerruf$anteil").also(File::mkdirs)
            neuerRunner(FuseLedgerAdapter().also { it.loadOnce(d2, "test-epoch", start) })
            markerAt = start + 2 * 60_000L
            repeat(20) { transport(d2) }
            markerAt = 0L
            repeat(3) { transport(d2) }
            assertTrue(
                !ledger.episodes.foundation.valid,
                "$anteil: der Widerruf MUSS die Autorisierung beenden",
            )
            assertEquals(
                0.0, sicht().dueU, 1e-9,
                "$anteil: und danach fordert das Fundament nichts mehr",
            )

            // (c) UNGESUNDES SIGNAL (Codex 19.08. - dieser Fall FEHLTE).
            //
            // Der Test hiess "gemessenes Tief, ungesundes Signal und
            // Widerruf" und baute nur zwei davon. Eine Ueberschrift, die mehr
            // verspricht als der Rumpf prueft, ist schlimmer als eine
            // fehlende: sie laesst die Luecke geschlossen aussehen.
            //
            // Ungueltige IOB-Daten -> keine Aktivitaet -> ACTIVITY_MISSING.
            // Das Signal ist damit nicht READY, und ohne gesundes Signal darf
            // das Fundament nichts publizieren - so wenig wie jeder andere
            // Kanal.
            fundamentAnteil = anteil
            flach = 180.0
            steigungProMin = 2.5
            knickAbMin = null
            primeHuelleU = 3.0
            clock = start
            transportReset()
            val d3 = File(dir, "signal$anteil").also(File::mkdirs)
            neuerRunner(FuseLedgerAdapter().also { it.loadOnce(d3, "test-epoch", start) })
            markerAt = start + 2 * 60_000L
            iobGueltig = false
            var abgegebenKrank = 0.0
            var gesundGesehen = false
            repeat(40) {
                val o = transport(d3)
                abgegebenKrank += letzteMengeU ?: 0.0
                if (o.health == Health.READY) gesundGesehen = true
            }
            iobGueltig = true
            assertTrue(
                !gesundGesehen,
                "$anteil: der Aufbau MUSS ein ungesundes Signal erzeugen - " +
                    "sonst prueft dieser Fall nichts",
            )
            assertEquals(
                0.0, abgegebenKrank, 1e-9,
                "$anteil: bei ungesundem Signal darf das Fundament NICHTS publizieren",
            )
        }
    }


    // ==== DIE VIERTE FORM: der positive Funktionsnachweis ===================
    //
    // DIE DREI BISHERIGEN FORMEN SIND NEGATIVKONTROLLEN und bleiben es: sie
    // belegen, dass das Fundament NICHT additiv eingreift, solange der
    // normale Pfad die Mindestversorgung schon erfuellt. Gemessen: in allen
    // zwoelf Laeufen `foundationLiftU == 0` bei `restRueckstandU == 0` - es
    // gab schlicht nie eine Luecke.
    //
    // DIESE FORM ERZEUGT DIE LUECKE, und zwar ueber die GLUKOSEBAHN, nicht
    // ueber kuenstlich genullte Entscheidungen:
    //
    //     T+0..15   klarer Anstieg  -> Prime arbeitet
    //     T+15..60  Plateau         -> der Regler kommt von selbst zur Ruhe
    //
    // Erst danach ist ein Vergleich von 80/20 gegen 75/25 ueberhaupt
    // sinnvoll.

    private class Lift(
        val min: Int,
        val dueU: Double,
        val preU: Double,
        val liftU: Double,
        val publiziertU: Double,
        val block: String,
        val grenze: String?,
    ) {

        /**
         * Was vom Lift wirklich hinausging - GEDECKELT AN DER FORDERUNG
         * (Codex 19.08.).
         *
         * Ohne die Deckelung wuerde jede spaetere Anhebung dem Fundament
         * zugerechnet: publiziert die Pumpe mehr, als der normale Pfad
         * wollte, muss das nicht am Fundament liegen. Nur bis zur Hoehe
         * seiner eigenen Forderung ist die Differenz ihm zuzuschreiben - was
         * darueber liegt, hat eine andere Quelle und darf den
         * Funktionsnachweis nicht schoenen.
         */
        val durchU get() = min(liftU, max(0.0, publiziertU - preU))
        val ganzGebremst get() = durchU <= 1e-9
        val teilweiseGebremst get() = !ganzGebremst && durchU < liftU - 1e-9
    }

    private class Nachweis(
        val anteil: Double,
        val phaseBGesehen: Boolean,
        val dueGesehen: Boolean,
        val gesund: Boolean,
        val tiefOderHold: Boolean,
        val lifts: List<Lift>,
        val bei: Map<Int, Double>,
        val leerlaufMin: Int,
        val iobSpitzeU: Double,
        val iobSpitzeMin: Int,
        val publiziertU: Double,
    )

    private fun plateauLauf(dir: File, anteil: Double): Nachweis {
        fundamentAn = true
        fundamentAnteil = anteil
        fundamentEndeMin = 60
        markerAuthorized = true
        // TONIS ECHTE HUELLE. Mit 1,2 U war das gemeinsame Budget schon in
        // Phase A erschoepft (gemessen: 3,6 U geflossen), und Phase B fand nur
        // noch BUDGET_EXHAUSTED - die Vorbedingung \ schlug deshalb
        // fehl, und das war richtig so.
        primeHuelleU = 3.0
        flach = 120.0
        // Der Marker liegt bei start+2; der Knick soll T+15 NACH dem Marker
        // liegen, also start+17. Die Steigung ist bewusst MASSVOLL: bei 2,2
        // dosiert der Korrekturkanal die Huelle in Phase A leer.
        steigungProMin = 1.0
        knickAbMin = 17
        steigungNachKnick = 0.1
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        markerAt = start + 2 * 60_000L

        val lifts = mutableListOf<Lift>()
        val gaben = mutableListOf<Pair<Long, Double>>()
        val kum = mutableMapOf<Int, Double>()
        var summe = 0.0
        var leerlauf = 0
        var maxLeerlauf = 0
        var phaseBGesehen = false
        var dueGesehen = false
        var gesund = false
        var tiefOderHold = false

        for (i in 0..75) {
            val o = transport(dir)
            val publiziert = letzteMengeU ?: 0.0
            if (publiziert > 0.0) {
                gaben += clock to publiziert
                summe += publiziert
                leerlauf = 0
            } else {
                leerlauf++
                if (leerlauf > maxLeerlauf) maxLeerlauf = leerlauf
            }
            if (o.mealFoundation.phase == MealFoundation.Phase.PHASE_B) phaseBGesehen = true
            if (o.mealFoundation.dueU > 0.0) dueGesehen = true
            if (o.health == Health.READY) gesund = true
            if (o.decision.block == FuseController.Block.SAFETY_HOLD ||
                o.decision.block == FuseController.Block.LEDGER_HOLD
            ) tiefOderHold = true
            if (o.foundationLiftU > 0.0) lifts += Lift(
                min = ((clock - markerAt) / 60_000L).toInt(),
                dueU = o.mealFoundation.dueU, preU = o.preFoundationSmbU,
                liftU = o.foundationLiftU, publiziertU = publiziert,
                block = o.decision.block.name, grenze = o.decision.bindingLimit,
            )
            val seitMarker = ((clock - markerAt) / 60_000L).toInt()
            if (seitMarker in listOf(15, 30, 45, 60)) kum[seitMarker] = summe
        }
        val (spitze, spitzeMin) = iobSpitze(gaben, clock)
        return Nachweis(
            anteil, phaseBGesehen, dueGesehen, gesund, tiefOderHold, lifts,
            listOf(15, 30, 45, 60).associateWith { kum[it] ?: summe },
            maxLeerlauf, spitze, spitzeMin, summe,
        )
    }

    /**
     * DER POSITIVE FUNKTIONSNACHWEIS - mit harten Vorbedingungen VOR jeder
     * Auswertung (Toni 19.08.).
     *
     * Ohne sie waere eine Tafel voller Nullen von einem funktionierenden
     * Fundament nicht zu unterscheiden - genau der Fehler der ersten drei
     * Formen, nur unbemerkt. Die Vorbedingungen sind deshalb ZUSICHERUNGEN,
     * nicht Ausgaben: schlaegt eine fehl, taugt der Aufbau nicht und die
     * Zahlen daraus sind wertlos.
     *
     * UND DIE ZENTRALE UNTERSCHEIDUNG: `foundationLiftU` sagt, was das
     * Fundament FORDERTE. Was davon hinausging, ist
     * `max(0, publiziert - preFoundationSmbU)`. Die Differenz ist der Anteil,
     * den Tail, Guard oder ein technisches Gate nachtraeglich weggenommen
     * haben - aus der Forderung allein ist das nicht ablesbar.
     */
    @Test
    fun `Plateau-Form - positiver Funktionsnachweis des Fundaments`(@TempDir dir: File) {
        println(
            "PLT anteil;liftZyklen;gefordertU;durchU;ganzGebremst;teilGebremst;" +
                "pub15;pub30;pub45;pub60;publiziertU;leerlaufMin;iobSpitzeU;iobSpitzeMin;grenzen"
        )
        val ergebnisse = listOf(1.00, 0.80, 0.75, 0.67).map { anteil ->
            val n = plateauLauf(File(dir, "plt${(anteil * 100).toInt()}"), anteil)
            val gefordert = n.lifts.sumOf { it.liftU }
            val durch = n.lifts.sumOf { it.durchU }
            val grenzen = n.lifts.filter { it.ganzGebremst || it.teilweiseGebremst }
                .groupingBy { it.grenze ?: it.block }.eachCount()
                .entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
                .ifEmpty { "-" }
            println(
                "PLT %.2f;%d;%.3f;%.3f;%d;%d;%.3f;%.3f;%.3f;%.3f;%.3f;%d;%.3f;%d;%s".format(
                    n.anteil, n.lifts.size, gefordert, durch,
                    n.lifts.count { it.ganzGebremst }, n.lifts.count { it.teilweiseGebremst },
                    n.bei[15], n.bei[30], n.bei[45], n.bei[60],
                    n.publiziertU, n.leerlaufMin, n.iobSpitzeU, n.iobSpitzeMin, grenzen,
                )
            )
            n
        }

        // ---- HARTE VORBEDINGUNGEN, ohne die die Tafel nichts wert ist ----
        //
        // Bei 100/0 gibt es kein Phase B - dort MUSS das Fundament schweigen.
        // Geprueft werden deshalb die drei geteilten Varianten.
        for (n in ergebnisse.filter { it.anteil < 1.0 }) {
            assertTrue(n.phaseBGesehen, "${n.anteil}: Phase B MUSS aktiv gewesen sein")
            // KEINE ZUSICHERUNG AUF `mealFoundation.dueU` - und das ist ein
            // BEFUND, kein Verzicht (gemessen 19.08.): der exportierte
            // Snapshot entsteht ABSICHTLICH nach `buche`. In genau den
            // Zyklen, in denen das Fundament geliefert hat, ist sein dueU
            // deshalb schon wieder 0 - die Forderung ist ja bedient. Aus dem
            // Trail allein war bisher also NICHT ablesbar, was das Fundament
            // wollte. Genau diese Luecke schliesst `foundationLiftU`, und
            // deshalb wird hier darauf geprueft.
            assertTrue(n.gesund, "${n.anteil}: das Signal MUSS gesund gewesen sein")
            assertTrue(!n.tiefOderHold, "${n.anteil}: kein gemessenes Tief und kein Hold im Lauf")
            assertTrue(
                n.lifts.isNotEmpty(),
                "${n.anteil}: das Fundament MUSS mindestens einmal angehoben haben - " +
                    "sonst ist dies wieder nur eine Negativkontrolle",
            )
            assertTrue(
                n.lifts.any { it.preU < it.publiziertU - 1e-9 },
                "${n.anteil}: in mindestens einem Lift-Zyklus MUSS die publizierte Menge " +
                    "UEBER dem normalen Vorschlag gelegen haben - sonst hat das Fundament " +
                    "zwar gefordert, aber nichts getragen",
            )
        }

        // Und die Negativkontrolle in derselben Bahn: 100/0 hat kein Phase B.
        val ohne = ergebnisse.first { it.anteil == 1.00 }
        assertTrue(
            ohne.lifts.isEmpty(),
            "bei 100/0 darf das Fundament auch auf dem Plateau nichts anheben",
        )
    }


    @Test
    fun `Tau-Shadow erkennt die Plateau-Wende ohne den produktiven R60-Pfad umzuschreiben`() {
        // Erst klarer Anstieg, dann ein flacherer positiver Nachlauf. Das ist
        // die Form des 11:33-Falls: fastDrive dreht bereits ab, r bleibt noch
        // hoch. Kein Marker, kein Fundament und kein Tail - damit ist R60
        // direkt mit dem bestehenden Produktivpfad vergleichbar.
        flach = 140.0
        steigungProMin = 2.0
        knickAbMin = 18
        steigungNachKnick = 0.35
        tailGuard = false
        markerAuthorized = false
        fundamentAn = false
        clock = start

        var wende: FuseCycleRunner.Outcome? = null
        var normaleShadowZyklen = 0
        for (i in 0 until 55) {
            val o = cycle()
            if (o.turnResponseShadow?.classification?.phase == TurnResponseShadow.Phase.TURNING_DOWN) {
                wende = o
                break
            }
            o.turnResponseShadow?.let { sh ->
                if (sh.classification.phase == TurnResponseShadow.Phase.ALIGNED) {
                    assertTrue(sh.variants.isEmpty(), "ohne bestaetigte Wende darf die Matrix keine Loopzeit kosten")
                    normaleShadowZyklen++
                }
            }
        }
        assertTrue(normaleShadowZyklen > 0, "der Aufbau muss auch den billigen Normalpfad durchlaufen")
        val o = wende ?: throw AssertionError("der Aufbau hat keine positive Abwaertswende erzeugt")
        val sh = o.turnResponseShadow!!
        assertEquals(50, sh.classification.adaptiveRestraintTauMin)
        assertTrue(sh.classification.fastDriveMgdlPerMin!! > 0.0, "negative Drives duerfen R50 nie oeffnen")
        val byName = sh.variants.associateBy { it.name }
        assertEquals(setOf("R60", "R55", "R50", "R45", "ADAPTIVE"), byName.keys)

        val r60 = byName.getValue("R60")
        val adaptiv = byName.getValue("ADAPTIVE")
        // R60 ist die Kontrollspur: dieselbe kombinierte Bahn und dieselben
        // Kappen wie der produktive Regler. Damit kann das Berechnen der
        // Matrix nicht unbemerkt die Referenzdefinition wechseln.
        assertEquals(o.decision.predAtReleaseMgdl!!, r60.predAtReleaseMgdl!!, 1e-7)
        assertEquals(o.decision.minLowerMgdl!!, r60.minSafetyLowerMgdl!!, 1e-7)
        assertEquals(
            o.decision.caps.first { it.name == "smbRatio" }.valueU,
            r60.ratioCapU!!,
            1e-7,
        )
        // Ein bestaetigter DOWN-Shadow darf nur restriktiver sein. Er muss
        // keine andere Pumpenstufe treffen, darf R60 aber nie ueberbieten.
        assertTrue((adaptiv.predAtReleaseMgdl ?: Double.MAX_VALUE) <= (r60.predAtReleaseMgdl ?: Double.MAX_VALUE) + 1e-9)
        assertTrue(
            adaptiv.predAtReleaseMgdl!! < r60.predAtReleaseMgdl!! - 1e-6,
            "der Aufbau muss eine echte, nicht nur benannte Bremswirkung erzeugen",
        )
        assertTrue((adaptiv.candidateSmbU ?: 0.0) <= (r60.candidateSmbU ?: 0.0) + 1e-9)
        assertEquals(50, adaptiv.restraintTauMin)
    }

    /**
     * IM REBOUND-FENSTER IST DIE PRODUKTION DIE SCHAERFERE BREMSE (Review
     * 22.08.). Sie faehrt min(driveTauMin, 15); die fruehere harte
     * 45-60-Matrix ueberzeichnete dort jede Kandidatenzeile, und R60 war
     * genau in dem Fenster KEINE Kontrollspur mehr, in dem eine unterdrueckte
     * Bremsbahn am meisten zaehlt - 25% der Wendezyklen des ersten
     * Messlaufs lagen dort.
     */
    @Test
    fun `im Rebound-Fenster erbt die Matrix den produktiven Tau 15`() {
        // Start am Tief: der Anstieg beginnt UNTER der Rebound-Schwelle, so
        // dass auch nach dem Warmlauf des Gerists noch verarbeitete Zyklen
        // mit q1 < 75 liegen und das 45-min-Fenster armieren. Danach dieselbe
        // Form wie der Plateau-Fall: klarer Anstieg, flacher positiver
        // Nachlauf.
        flach = 55.0
        steigungProMin = 2.0
        knickAbMin = 18
        steigungNachKnick = 0.35
        tailGuard = false
        markerAuthorized = false
        fundamentAn = false
        clock = start

        var wende: FuseCycleRunner.Outcome? = null
        for (i in 0 until 40) {
            val o = cycle()
            if (o.turnResponseShadow?.classification?.phase == TurnResponseShadow.Phase.TURNING_DOWN) {
                wende = o
                break
            }
        }
        val o = wende ?: throw AssertionError("der Aufbau hat keine Wende im Rebound-Fenster erzeugt")
        val byName = o.turnResponseShadow!!.variants.associateBy { it.name }
        for (name in listOf("R60", "R55", "R50", "R45", "ADAPTIVE")) {
            assertEquals(
                15, byName.getValue(name).restraintTauMin,
                "$name: die Produktion bremst im Rebound mit Tau 15 - eine Variante, " +
                    "die laenger nachschiebt, waere keine Kuerzung, sondern eine Lockerung",
            )
        }
        // Und die Kontrollspur-Zusicherung gilt AUCH hier: R60 (effektiv 15)
        // ist bitgenau der produktive Pfad.
        assertEquals(o.decision.predAtReleaseMgdl!!, byName.getValue("R60").predAtReleaseMgdl!!, 1e-7)
    }

    /**
     * DIE BASELINE FOLGT driveTauMin, NICHT DER ZAHL 60. Mit einem legalen
     * driveTauMin = 45 waere die alte Matrix (hart 60) eine LOCKERUNG der
     * Produktion gewesen - der Kontrollspur-Test blieb nur gruen, weil das
     * Geruest zufaellig 60 stubbt.
     */
    @Test
    fun `bei fremdem driveTauMin bleibt R60 die produktive Kontrollspur`() {
        whenever(preferences.get(FuseIntKey.DriveTauMin)).thenReturn(45)
        // Die Form des 18:19-Falls: nach dem Knick faellt der ROHE Verlauf,
        // waehrend Bolusaktivitaet den bereinigten Drive positiv haelt. So
        // wird die abgeschlagene Unterkante NEGATIV, und auch der
        // Negativ-Zerfall der Bremsbahn muss die produktive Spur sein - ein
        // hart kodierter 60er dort waere im Sicherheitszeugnis sichtbar.
        flach = 140.0
        steigungProMin = 2.0
        knickAbMin = 18
        steigungNachKnick = -1.0
        aktivitaet = 0.03
        bolusIobU = 3.0
        tailGuard = false
        markerAuthorized = false
        fundamentAn = false
        clock = start

        var wende: FuseCycleRunner.Outcome? = null
        for (i in 0 until 55) {
            val o = cycle()
            if (o.turnResponseShadow?.classification?.phase == TurnResponseShadow.Phase.TURNING_DOWN) {
                wende = o
                break
            }
        }
        val o = wende ?: throw AssertionError("der Aufbau hat keine positive Abwaertswende erzeugt")
        val byName = o.turnResponseShadow!!.variants.associateBy { it.name }
        assertEquals(45, byName.getValue("R60").restraintTauMin, "min(60, produktiv 45) = 45")
        assertEquals(45, byName.getValue("R45").restraintTauMin)
        assertEquals(o.decision.predAtReleaseMgdl!!, byName.getValue("R60").predAtReleaseMgdl!!, 1e-7)
        // Auch das SICHERHEITSZEUGNIS ist die produktive Spur. EHRLICHE
        // GRENZE dieser Zusicherung (Mutationsprobe 22.08.): das Zeugnis ist
        // ein min() ueber Haupt- und Bremsbahn, und an bestaetigten Wenden
        // dominiert die Hauptbahn die Unterkante - ein falscher NEGATIV-Tau
        // der Bremsbahn ist hier deshalb nicht beobachtbar. Er ist ausserhalb
        // von driveTauMin != 60 verhaltensgleich und irrt sonst nur in die
        // konservative Richtung (tieferes Zeugnis, kleinere Kandidaten).
        assertEquals(o.decision.minLowerMgdl!!, byName.getValue("R60").minSafetyLowerMgdl!!, 1e-7)
    }

    /**
     * DIE STABILE SIGNALEPOCHE (Toni 22.08.) - die Segment-Identitaet des
     * Erwartungs-Ledgers. Mit der gleitenden 18-min-Fensterkante als
     * Identitaet konnten sich Entry (Kante bei Ausstellung) und Probe (Kante
     * 120 min spaeter) per Konstruktion NIE treffen: alle 1091 Outcomes des
     * ersten Messlaufs waren UNVERIFIABLE. Die Epoche steht still, bis ein
     * ECHTER Bruch kommt.
     */
    @Test
    fun `die Signalepoche steht still und wechselt nur am echten Bruch`() {
        flach = 120.0
        steigungProMin = 0.5
        knickAbMin = null
        tailGuard = false
        markerAuthorized = false
        fundamentAn = false
        clock = start

        val epochen = mutableListOf<Long>()
        var kante = 0L
        repeat(30) {
            val o = cycle()
            o.signal?.let { epochen.add(it.signalEpochTs); kante = it.segmentStartTs }
        }
        assertTrue(epochen.size >= 20, "der Aufbau muss lesbare Signale liefern")
        assertEquals(
            1, epochen.distinct().size,
            "die Epoche darf nicht mit der Fensterkante wandern: ${epochen.distinct()}",
        )
        // Und sie ist NICHT die gleitende Kante: nach 30 min liegt die
        // 18-min-Kante laengst hinter dem Reihenbeginn.
        assertTrue(
            epochen.last() < kante,
            "Epoche ${epochen.last()} muss VOR der wandernden Kante $kante liegen",
        )

        // DER ECHTE BRUCH: eine 10-min-Luecke. Die Epoche springt genau auf
        // den ersten Punkt NACH der Luecke - und steht danach wieder still.
        lueckeVonMin = 31
        lueckeBisMin = 41
        clock = start + 44 * 60_000L
        val danach = mutableListOf<Long>()
        repeat(20) {
            val o = cycle()
            o.signal?.let { danach.add(it.signalEpochTs) }
        }
        assertTrue(danach.isNotEmpty(), "auch nach der Luecke muss wieder ein Signal kommen")
        assertEquals(
            start + 41 * 60_000L, danach.last(),
            "die Epoche ist der erste Punkt NACH der Luecke",
        )
        assertEquals(
            1, danach.distinct().size,
            "und sie steht nach dem Bruch wieder still: ${danach.distinct()}",
        )

        // DIE ROLLENDE PUFFERKANTE (Review 22.08., Major): jenseits des
        // Lookbacks beschneidet die Quelle die Reihe an anchor - ~198 min,
        // series.first() WANDERT dann jede Minute - exakt das Regime, in dem
        // die alte Fensterkanten-Identitaet erkrankte. In den ersten beiden
        // Abschnitten dieses Tests ist series.first() konstant `start`; ein
        // unbedingtes Neusetzen der Epoche aus series.first() (die
        // Regression) waere dort UNSICHTBAR. Erst hier, mit gerollter Kante
        // und der Luecke bereits AUSSERHALB des Fensters, beisst die
        // Zusicherung: die Epoche bleibt bei 41 min stehen, obwohl kein
        // Bruchkandidat mehr in der Reihe liegt.
        clock = start + 250 * 60_000L
        val gerollt = mutableListOf<Long>()
        var kante250 = 0L
        repeat(25) {
            val o = cycle()
            o.signal?.let { gerollt.add(it.signalEpochTs); kante250 = it.windowFromTs }
        }
        assertTrue(gerollt.isNotEmpty())
        assertTrue(
            kante250 > start + 41 * 60_000L,
            "der Aufbau muss die Kante wirklich ueber den Bruch hinaus gerollt haben: $kante250",
        )
        assertEquals(
            start + 41 * 60_000L, gerollt.last(),
            "die Epoche ueberlebt das Herausrollen ihres Bruchs aus dem Puffer",
        )
        assertEquals(
            1, gerollt.distinct().size,
            "und wandert nicht mit der rollenden Pufferkante: ${gerollt.distinct()}",
        )
        // GRENZE, ehrlich benannt: Sensor-/Kalibrierepochen und Input-Sprung
        // (bound != NONE) sind im Rig auf AUS gepinnt und hier ungeprueft;
        // ebenso bleibt der Neustart eine Heuristik (eine lueckenlos
        // belegte Reihe darf die alte Epoche wiederherstellen - s. KDoc
        // von signalEpochTs).
    }

    /**
     * ADAPTIVE-DOWN ALS SCHATTEN (Toni 22.08.). Der 5b-Replay: am
     * Korrektur-AUSGANG haelt keine Bremse mehr etwas zurueck (0,00 U ueber
     * 39,5h - Guard/SAFETY_HOLD/Riegel schliessen die Tuer laengst); das
     * Tief-Insulin fliesst waehrend ABBREMSENDER ANSTIEGE, vom traegen r
     * lizenziert. Die Antwort ist die einseitige Mittelbahn-Senkung - hier
     * ihre Schatten-Zusicherungen: Referenzzeile = produktiver Pfad, Senkung
     * nur nach Ausloeser, Ausloeser-Disziplin (P2/P3 ziehen erst mit
     * Persistenz), vermiedene Menge nie negativ, Produktion unangetastet.
     */
    @Test
    fun `ADAPTIVE-DOWN senkt im Schatten nur die Mittelbahn und nur nach Ausloeser`() {
        // Die 18:47-Form: klarer Anstieg, dann flacher positiver Nachlauf -
        // fastAdj kollabiert gegen das noch hohe r.
        flach = 140.0
        steigungProMin = 2.0
        knickAbMin = 18
        steigungNachKnick = 0.35
        tailGuard = false
        markerAuthorized = false
        fundamentAn = false
        clock = start

        var gesenkt: FuseCycleRunner.Outcome? = null
        var disziplin: FuseCycleRunner.Outcome? = null
        var persistenz: FuseCycleRunner.Outcome? = null
        repeat(55) {
            val o = cycle()
            val dv = o.turnResponseShadow?.downVariants ?: return@repeat
            if (dv.isEmpty()) return@repeat
            val now = dv.first { it.name == "NOW" }
            val p3 = dv.first { it.name == "P3" }
            // Vermiedene Menge ist NIE negativ - in jedem Zyklus.
            dv.forEach { v ->
                assertTrue(
                    (v.avoidedSmbU ?: 0.0) >= -1e-9,
                    "${v.name}: eine Senkung kann nichts hinzufuegen",
                )
            }
            if (gesenkt == null && now.triggered &&
                (now.predAtReleaseMgdl ?: Double.MAX_VALUE) <
                (dv.first { it.name == "BASE" }.predAtReleaseMgdl ?: 0.0) - 1e-6
            ) gesenkt = o
            if (disziplin == null && now.triggered && !p3.triggered) disziplin = o
            if (persistenz == null && p3.triggered) persistenz = o
        }

        val o = gesenkt ?: throw AssertionError("der Aufbau muss eine echte Senkung erzeugen")
        val dv = o.turnResponseShadow!!.downVariants.associateBy { it.name }
        val base = dv.getValue("BASE")
        val now = dv.getValue("NOW")
        // DIE REFERENZZEILE IST DER PRODUKTIVE PFAD: ohne Marker, Fundament
        // und Riegel ist der publizierte SMB genau der Kandidat der Stufe,
        // auf der auch die Varianten rechnen. Damit ist die Dosierneutralitaet
        // auf DATENEBENE belegt, nicht nur behauptet. (predAtRelease wird
        // absichtlich NICHT gegen decision verglichen: die Down-Zeilen
        // tragen die reine Mittelbahn ohne das min() mit der Bremsbahn -
        // s. KDoc von DownVariant.predAtReleaseMgdl.)
        assertEquals(o.decision.smbU, base.candidateSmbU!!, 1e-9)
        // Die Senkung senkt: Mittelbahn tiefer, Kandidat nie groesser, und
        // die vermiedene Menge ist exakt die Differenz.
        assertTrue(now.midDriveMgdlPerMin!! < base.midDriveMgdlPerMin!! - 1e-9)
        assertTrue(now.candidateSmbU!! <= base.candidateSmbU!! + 1e-9)
        assertEquals(base.candidateSmbU!! - now.candidateSmbU!!, now.avoidedSmbU!!, 1e-9)

        // PRUEFAUFTRAG 2 (14:10-Livefall): die Zeile traegt ihre ENDMENGE
        // (Lane-Sub-Step + Wirkungspruefung) und den Abstand zur tatsaechlich
        // publizierten Menge - avoided misst nicht mehr nur den Vorkandidaten.
        assertEquals(o.decision.smbU, base.endU!!, 1e-9, "BASE-Ende ist die publizierte Menge")
        assertEquals(0.0, base.avoidedEndU!!, 1e-9)
        assertTrue(now.endU != null, "die gesenkte Lane muss eine Endmenge tragen")
        assertTrue(now.endU!! >= now.candidateSmbU!! - 1e-9, "der Uebertrag kann nur hinzufuegen")
        assertEquals(
            kotlin.math.max(0.0, o.decision.smbU - now.endU!!), now.avoidedEndU!!, 1e-9,
            "avoidedEnd = publiziert minus Lane-Ende",
        )

        // AUSLOESER-DISZIPLIN: solange die Persistenz fehlt, traegt P3 die
        // REFERENZ, nicht die Senkung - frueh bremsen ist genau der Fehler,
        // den der 13:59-Gutfall (Peak 196 danach) verbietet.
        val d = disziplin ?: throw AssertionError("der Aufbau muss einen Zyklus vor voller Persistenz treffen")
        val dvd = d.turnResponseShadow!!.downVariants.associateBy { it.name }
        assertEquals(false, dvd.getValue("P3").triggered)
        assertEquals(dvd.getValue("BASE").candidateSmbU, dvd.getValue("P3").candidateSmbU)
        // Und mit Persistenz zieht P3.
        val p = persistenz ?: throw AssertionError("der Aufbau muss auch die volle Persistenz erreichen")
        assertTrue(p.turnResponseShadow!!.downVariants.first { it.name == "P3" }.triggered)
        assertTrue(p.turnResponseShadow!!.downVariants.first { it.name == "P3" }.declineStreak >= 3)
    }

    @Test
    fun `Aufwaertswende hebt im Shadow nur die Mittelbahn nicht das Sicherheitszeugnis`() {
        flach = 110.0
        steigungProMin = 0.10
        knickAbMin = 18
        steigungNachKnick = 2.0
        tailGuard = false
        markerAuthorized = false
        fundamentAn = false
        clock = start

        var wende: FuseCycleRunner.Outcome? = null
        for (i in 0 until 55) {
            val o = cycle()
            if (o.turnResponseShadow?.classification?.phase == TurnResponseShadow.Phase.TURNING_UP) {
                wende = o
                break
            }
        }
        val o = wende ?: throw AssertionError("der Aufbau hat keine Aufwaertswende erzeugt")
        val byName = o.turnResponseShadow!!.variants.associateBy { it.name }
        val r60 = byName.getValue("R60")
        val adaptiv = byName.getValue("ADAPTIVE")

        assertEquals(60, adaptiv.restraintTauMin, "Aufwaertsreaktion darf den Brems-Tau nicht kuerzen")
        assertTrue(
            adaptiv.predAtReleaseMgdl!! > r60.predAtReleaseMgdl!! + 1e-6,
            "der Aufwaertskandidat muss den frueher sichtbaren Bedarf in der Mittelbahn zeigen",
        )
        assertEquals(
            r60.safetyLowerAtReleaseMgdl!!,
            adaptiv.safetyLowerAtReleaseMgdl!!,
            1e-7,
            "Aufwaerts-Shadow darf Guard und Tail kein guenstigeres Zeugnis geben",
        )
        assertTrue((adaptiv.candidateSmbU ?: 0.0) + 1e-9 >= (r60.candidateSmbU ?: 0.0))
    }

    // ==== DIE RISIKOLAEUFE (Toni/Codex 19.08.) =============================
    //
    // WAS SIE BEWEISEN SOLLEN: nicht "zwei synthetische Risikokurven", sondern
    // dass GUARD beziehungsweise TAIL die Lage erzeugt haben. Deshalb steht in
    // jedem Lauf als harte Vorbedingung, WAS vor dem Fundament gebunden hat -
    // gemessen an `preFoundationBlock`/`preFoundationBindingLimit`, nicht an
    // der Fundament-Bindung, die den urspruenglichen Grund ueberdecken kann.
    //
    // UND SIE SIND GETRENNT, weil eine zweite gleichzeitig bindende Grenze die
    // Ursache unzuordenbar macht. Genau das wird geprueft, nicht gehofft.
    //
    // DIE GRENZE DIESER LAEUFE, ausdruecklich: der `aktivitaet`-Hebel erzeugt
    // die pessimistische Bahn ueber das INSULINMODELL, nicht ueber die
    // Glukose. Er prueft die REGELMECHANIK - er sagt NICHTS darueber, wie
    // haeufig diese Lage im echten Betrieb auftritt. Das gemessene Tief bleibt
    // eine eigene harte Nullkontrolle; hier bleibt der reale BG ausdruecklich
    // oberhalb des Bodens, und auch das wird geprueft.

    private class RisikoLauf(
        val anteil: Double,
        val lifts: List<Lift>,
        val bgImLift: List<Double>,
        val gesundImmer: Boolean,
        val ursachen: Set<String>,
    )

    private fun risikoLauf(
        dir: File,
        anteil: Double,
        aktivitaetsWert: Double,
        tailAn: Boolean,
    ): RisikoLauf {
        fundamentAn = true
        fundamentAnteil = anteil
        fundamentEndeMin = 60
        markerAuthorized = true
        primeHuelleU = 3.0
        // Deutlich ueber dem Boden (70) - das gemessene Tief soll NICHT die
        // Ursache sein. Anstieg bis T+15, dann Plateau wie im
        // Funktionsnachweis.
        flach = 150.0
        steigungProMin = 1.0
        knickAbMin = 17
        steigungNachKnick = 0.1
        aktivitaet = aktivitaetsWert
        tailGuard = tailAn
        conditionalTail = tailAn
        // IM TAIL-LAUF WIRD DER GUARD AUSDRUECKLICH GEOEFFNET. Gemessen band
        // er sonst mit (GUARD_FLOOR stand in den Ursachen), und dann ist eine
        // Bremsung nicht mehr zuzuordnen. Ein tiefer Boden kann bei einem
        // realen Zucker weit darueber nicht binden.
        guardBodenMgdl = if (tailAn) 40.0 else 70.0
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        markerAt = start + 2 * 60_000L

        val lifts = mutableListOf<Lift>()
        val bgImLift = mutableListOf<Double>()
        val ursachen = mutableSetOf<String>()
        // GESUNDHEIT IN DEN LIFT-ZYKLEN, nicht ueber den ganzen Lauf: die
        // ersten Zyklen nach dem Start sind WARMUP, und das ist kein Befund
        // ueber die Risikolage. Die erste Fassung prueft den Vorlauf mit und
        // war deshalb rot, ohne dass etwas falsch war.
        var gesundImmer = true
        for (i in 0..75) {
            val o = transport(dir)
            if (o.foundationLiftU > 0.0) {
                if (o.health != Health.READY) gesundImmer = false
                lifts += Lift(
                    min = ((clock - markerAt) / 60_000L).toInt(),
                    dueU = o.mealFoundation.dueU, preU = o.preFoundationSmbU,
                    liftU = o.foundationLiftU, publiziertU = letzteMengeU ?: 0.0,
                    block = o.preFoundationBlock.name,
                    grenze = o.preFoundationBindingLimit,
                )
                o.bgMgdl?.let { bgImLift += it }
                // DIE URSACHE VOR DEM FUNDAMENT - typisiert gesammelt.
                if (o.preFoundationBlock != FuseController.Block.NONE)
                    ursachen += o.preFoundationBlock.name
                o.preFoundationBindingLimit?.takeIf { it != "NONE" }?.let { ursachen += it }
            }
        }
        return RisikoLauf(anteil, lifts, bgImLift, gesundImmer, ursachen)
    }

    private fun berichte(kopf: String, r: RisikoLauf) {
        val gefordert = r.lifts.sumOf { it.liftU }
        val durch = r.lifts.sumOf { it.durchU }
        println(
            "%s %.2f;lifts=%d;gefordertU=%.3f;durchU=%.3f;ganz=%d;teil=%d;ursachen=%s;bgMin=%.0f".format(
                kopf, r.anteil, r.lifts.size, gefordert, durch,
                r.lifts.count { it.ganzGebremst }, r.lifts.count { it.teilweiseGebremst },
                r.ursachen.sorted().joinToString("|").ifEmpty { "-" },
                r.bgImLift.minOrNull() ?: 0.0,
            )
        )
    }

    /**
     * GUARD-LAUF: die pessimistische Bahn bindet, der reale Zucker steht
     * klar oben.
     *
     * Der Tail ist ausdruecklich AUS - sonst waere bei einer Bremsung nicht
     * zu sagen, welche der beiden Grenzen sie verursacht hat.
     */
    @Test
    fun `Risikolage Guard - das Fundament unter bindender Guard-Bahn`(@TempDir dir: File) {
        for (anteil in listOf(0.80, 0.75)) {
            val r = risikoLauf(
                File(dir, "guard${(anteil * 100).toInt()}"),
                anteil, aktivitaetsWert = 0.02, tailAn = false,
            )
            berichte("GUARD", r)

            assertTrue(r.gesundImmer, "$anteil: das Signal MUSS durchgehend READY sein")
            assertTrue(
                r.lifts.isNotEmpty(),
                "$anteil: das Fundament MUSS angehoben haben - sonst prueft der Lauf nichts",
            )
            assertTrue(
                r.bgImLift.all { it > 75.0 },
                "$anteil: der REALE Zucker MUSS in jedem Lift-Zyklus klar ueber dem Boden " +
                    "liegen - sonst waere ein gemessenes Tief die Ursache, nicht Guard: " +
                    "min=${r.bgImLift.minOrNull()}",
            )
            assertTrue(
                r.ursachen.contains(FuseController.Block.GUARD_FLOOR.name) ||
                    r.ursachen.any { it.contains("guard", ignoreCase = true) },
                "$anteil: vor dem Fundament MUSS ausdruecklich GUARD gebunden haben, " +
                    "gemessen: ${r.ursachen}",
            )
            assertTrue(
                r.ursachen.none { it.contains("tail", ignoreCase = true) },
                "$anteil: KEINE zweite bindende Grenze - sonst ist die Ursache nicht " +
                    "zuordenbar: ${r.ursachen}",
            )
        }
    }

    /**
     * TAIL-LAUF: der Schwanz bindet, Guard ist ausdruecklich offen.
     */
    @Test
    fun `Risikolage Tail - das Fundament unter bindendem Schwanz`(@TempDir dir: File) {
        for (anteil in listOf(0.80, 0.75)) {
            val r = risikoLauf(
                File(dir, "tail${(anteil * 100).toInt()}"),
                anteil, aktivitaetsWert = 0.0, tailAn = true,
            )
            berichte("TAIL", r)

            assertTrue(r.gesundImmer, "$anteil: das Signal MUSS durchgehend READY sein")
            assertTrue(r.lifts.isNotEmpty(), "$anteil: das Fundament MUSS angehoben haben")
            assertTrue(
                r.bgImLift.all { it > 75.0 },
                "$anteil: der reale Zucker MUSS oben bleiben: min=${r.bgImLift.minOrNull()}",
            )
            assertTrue(
                r.ursachen.any { it.contains("tail", ignoreCase = true) },
                "$anteil: vor dem Fundament MUSS der TAIL gebunden haben, " +
                    "gemessen: ${r.ursachen}",
            )
            assertTrue(
                !r.ursachen.contains(FuseController.Block.GUARD_FLOOR.name),
                "$anteil: Guard MUSS offen sein - sonst ist die Ursache nicht zuordenbar: " +
                    "${r.ursachen}",
            )
        }
    }


    // ==== DER FINALE RIEGEL AM GEMEINSAMEN AUSGANG (Toni 19.08., P0) ======
    //
    // ER SITZT NACH Prime-/Fundament-Lift, `finalVerify` und `MarkerFloor`,
    // aber VOR der Publikation. Ein frueher gesetzter Riegel koennte von einem
    // spaeteren Wiederherstellungspfad umgangen werden - genau so ist der
    // Abendfall entstanden: ab 17:55 stand die Abwaertslage fest, und ueber
    // die Marker-Autorisierung gingen danach noch 2,95 U hinaus.
    //
    // Diese Tests fahren den ECHTEN Runner. Ein Test auf `LowThreatGate`
    // allein wuerde nur die Rechnung pruefen, nicht ihre WIRKSAMKEIT am
    // Ausgang - und der Befund war ja gerade, dass eine richtige Rechnung
    // folgenlos blieb.

    /** Die Abwaertslage des Abends: fallend, vom Bolus ueberdeckt, Boden nah. */
    private fun abwaertslage(dir: File) {
        fundamentAn = true
        fundamentAnteil = 0.80
        markerAuthorized = true
        primeHuelleU = 3.9
        // BG faellt deutlich, Boden 70 - bei 140 und -2,5/min sind es 28 min.
        flach = 140.0
        steigungProMin = -2.5
        knickAbMin = null
        // Bolus-IOB deckt die Strecke zum Boden weit ueber: 4,7 U x ISF.
        bolusIobU = 4.7
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        markerAt = start + 2 * 60_000L
    }

    /**
     * DER FRUEHSTUECKSFALL VOM 21.08.: q1 rund 113, UKF -0,49/min und
     * 1,21 U Bolus-IOB. Das alte gemeinsame 120-min-Fenster machte daraus
     * einen harten positiven Endriegel, obwohl die extrapolierte Bodenzeit
     * weit ausserhalb einer akuten SMB-Entscheidung lag. Mit dem eigenen
     * 30-min-Fenster muss die Phase-A-Huelle bereits vor der Wende liefern.
     */
    @Test
    fun `Fruehstuecksfallen ausserhalb 30 Minuten hungert Phase A nicht aus`(@TempDir dir: File) {
        fundamentAn = true
        fundamentAnteil = 0.80
        markerAuthorized = true
        primeHuelleU = 3.75
        flach = 116.0
        steigungProMin = -0.49
        knickAbMin = null
        bolusIobU = 1.21
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        markerAt = start + 2 * 60_000L

        var phaseASum = 0.0
        var hardDescentSeen = false
        repeat(20) {
            val o = cycle()
            if (o.mealFoundation.phase == MealFoundation.Phase.PHASE_A) {
                phaseASum += o.decision.smbU
                if (o.decision.block == FuseController.Block.MEASURED_DESCENT_RISK)
                    hardDescentSeen = true
            }
        }

        assertFalse(hardDescentSeen, "die 30-min-Gefahr darf diesen langsamen Vorlauf nicht akut nennen")
        assertTrue(phaseASum > 0.0, "die Marker-Huelle muss vor der Mahlzeitenwende bereits liefern")
    }

    /**
     * DER ABENDFALL: trotz Marker-Autorisierung darf nichts mehr hinausgehen.
     *
     * Das ist die Zusicherung, an der der P0 haengt. Vorher hob der Marker den
     * GUARD_FLOOR und lieferte weiter; jetzt greift der Riegel NACH allen
     * Autorisierungen.
     */
    @Test
    fun `bei gemessenem Abwaertsrisiko geht trotz Marker nichts hinaus`(@TempDir dir: File) {
        abwaertslage(dir)
        var summe = 0.0
        var riegelGesehen = false
        repeat(30) {
            val o = cycle()
            summe += o.decision.smbU
            if (o.decision.block == FuseController.Block.MEASURED_DESCENT_RISK) riegelGesehen = true
        }
        assertTrue(riegelGesehen, "der Riegel MUSS gegriffen haben - sonst prueft der Test nichts")
        assertEquals(0.0, summe, 1e-9, "kein positives Insulin bei gemessener Abwaertslage: $summe U")
    }

    /**
     * DERSELBE AUFSCHUB IM PREDICTORFREIEN SEITENEINGANG.
     *
     * Haupt- und Fallbackpfad rufen dieselbe Fortschreibung auf, aber an zwei
     * getrennten Verdrahtungsstellen. Der vorangehende Hauptlauf reift nur
     * Signal und Abwaertslage; unmittelbar vor dem erzwungenen Fallback wird
     * der Zaehler genullt. Ein positiver Wert danach kann daher nur aus der
     * Fallback-Stelle stammen.
     */
    @Test
    fun `auch der Fallback merkt den unvermeidbaren Phase-A-Rueckstand`(@TempDir dir: File) {
        abwaertslage(dir)
        repeat(10) { cycle() }
        ledger.episodes.descentDeferredPhaseAU = 0.0
        predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT

        var fallbackRiskSeen = false
        repeat(6) {
            val o = cycle()
            if (o.markerFallbackUsed && o.decision.block == FuseController.Block.MEASURED_DESCENT_RISK) {
                assertEquals(0.0, o.decision.smbU, 1e-9)
                fallbackRiskSeen = true
            }
        }

        assertTrue(fallbackRiskSeen, "der Test MUSS den predictorfreien Riegel erreichen")
        assertTrue(
            ledger.episodes.descentDeferredPhaseAU > 0.0,
            "auch der Seiteneingang muss den spaeter kontrolliert nachholbaren Rueckstand festhalten",
        )
    }

    @Test
    fun `auch SafetyHold merkt den unvermeidbaren Phase-A-Rueckstand`(@TempDir dir: File) {
        fundamentAn = true
        fundamentAnteil = 0.80
        markerAuthorized = true
        primeHuelleU = 3.75
        // Ein reales Tief, kein Modell-Guard. Die Phase-A-Autorisierung steht,
        // darf aber nichts liefern; gegen Ende ihres Fensters muss der nicht
        // mehr einholbare Anteil restartfest als Sicherheitsaufschub stehen.
        flach = 72.0
        steigungProMin = 0.0
        knickAbMin = null
        bolusIobU = 1.2
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        markerAt = start + 2 * 60_000L

        var safetyHoldSeen = false
        repeat(25) {
            val o = cycle()
            if (o.decision.block == FuseController.Block.SAFETY_HOLD) safetyHoldSeen = true
        }

        assertTrue(safetyHoldSeen, "der Aufbau MUSS den gemessenen SafetyHold erreichen")
        assertTrue(
            ledger.episodes.descentDeferredPhaseAU > 0.0,
            "SafetyHold darf die ausgefallene Phase-A-Versorgung nicht unsichtbar verfallen lassen",
        )
    }

    /**
     * UND DIE TBR BLEIBT DAVON UNBERUEHRT. Bei aktivem Risiko und unwirksamer
     * Zero-TBR ist die richtige Antwort SMB 0 UND KEEP_CURRENT - keine
     * nutzlose Null. "Basal zurueckhalten hilft nicht mehr" und "mehr Bolus
     * ist sicher" sind zwei verschiedene Aussagen, und der Riegel beantwortet
     * nur die zweite.
     */
    @Test
    fun `der Riegel setzt die Menge auf null und laesst die TBR in Ruhe`(@TempDir dir: File) {
        abwaertslage(dir)
        var geprueft = false
        repeat(30) {
            val o = cycle()
            if (o.decision.block == FuseController.Block.MEASURED_DESCENT_RISK) {
                assertEquals(0.0, o.decision.smbU, 1e-9, "die Menge ist null")
                // Die Basalantwort stammt weiterhin aus der Nutzenpruefung -
                // der Riegel fasst sie nicht an.
                assertTrue(
                    o.decision.tbr == FuseController.TbrAction.KEEP_CURRENT ||
                        o.decision.tbr == FuseController.TbrAction.ZERO_TEMP ||
                        o.decision.tbr == FuseController.TbrAction.NO_NEW_POSITIVE,
                    "die TBR bleibt Ergebnis des Basalnutzens: ${o.decision.tbr}",
                )
                geprueft = true
            }
        }
        assertTrue(geprueft, "der Aufbau muss den Riegel erreichen")
        assertTrue(
            ledger.episodes.descentDeferredPhaseAU > 0.0,
            "der harte Riegel bucht keine Lieferung, merkt aber den unvermeidbaren Phase-A-Rueckstand",
        )
    }

    /**
     * DIE GEGENKONTROLLE: eine steigende schnelle Mahlzeit bleibt unberuehrt.
     *
     * Ohne sie waere nicht auszuschliessen, dass der Riegel jede Versorgung
     * aushungert - und das waere ein Fehler derselben Groessenordnung wie der,
     * den er behebt.
     */
    @Test
    fun `eine steigende Mahlzeit bleibt unberuehrt`(@TempDir dir: File) {
        fundamentAn = true
        fundamentAnteil = 0.80
        markerAuthorized = true
        primeHuelleU = 3.0
        flach = 150.0
        steigungProMin = 2.2
        knickAbMin = null
        bolusIobU = null
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        markerAt = start + 2 * 60_000L

        var summe = 0.0
        var riegel = 0
        repeat(25) {
            val o = cycle()
            summe += o.decision.smbU
            if (o.decision.block == FuseController.Block.MEASURED_DESCENT_RISK) riegel++
        }
        assertEquals(0, riegel, "bei steigendem Zucker darf der Riegel NIE greifen")
        assertTrue(summe > 0.0, "und die Mahlzeit wird weiterhin versorgt: $summe U")
    }

    /**
     * DIE WIEDERFREIGABE DURCH DEN ECHTEN RUNNER. Ein einzelner steigender
     * Wert darf einen zuvor geschlossenen Riegel nicht oeffnen. Erst der
     * dritte lueckenlose Zyklus mit UKF >= +0,20 darf wieder positives
     * Insulin passieren lassen.
     *
     * Der Zustand wird hier absichtlich als vorgefunden gesetzt: damit
     * prueft der Test genau die Kante nach Prozessneustart. Der Codec-Test
     * daneben belegt, dass dieser Zustand auch wirklich so von Platte kommt
     * und der halbe Runtime-Zaehler nicht mitkommt.
     */
    @Test
    fun `ein vorgefundener Abwaertsriegel oeffnet erst nach drei bestaetigten Wendezyklen`(@TempDir dir: File) {
        fundamentAn = true
        fundamentAnteil = 0.80
        markerAuthorized = true
        primeHuelleU = 3.0
        flach = 150.0
        steigungProMin = 2.2
        knickAbMin = null
        bolusIobU = null
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        l.episodes.descentRecoveryLatch = DescentRecoveryLatch.State(true, start - 60_000L)
        neuerRunner(l)
        markerAt = start + 2 * 60_000L

        var bestaetigungen = 0
        var blockiertMitBedarf = 0
        var freigabeGesehen = false
        var positiveNachFreigabe = 0.0
        repeat(40) {
            val o = cycle()
            when (o.descentLatchReason) {
                DescentRecoveryLatch.Reason.WAITING_CONFIRMATION.name -> {
                    bestaetigungen++
                    assertTrue(o.descentLatchActive, "waehrend der Bestaetigung bleibt der Riegel aktiv")
                    if (o.decision.block == FuseController.Block.MEASURED_DESCENT_RISK) {
                        assertEquals(0.0, o.decision.smbU, 1e-9)
                        blockiertMitBedarf++
                    }
                }
                DescentRecoveryLatch.Reason.RECOVERED.name -> {
                    assertFalse(o.descentLatchActive, "der dritte Zyklus oeffnet")
                    freigabeGesehen = true
                }
            }
            if (freigabeGesehen) positiveNachFreigabe += o.decision.smbU
        }

        assertEquals(2, bestaetigungen, "vor der Freigabe muessen genau zwei Zyklen warten")
        assertTrue(blockiertMitBedarf > 0, "der Test muss einen echten positiven Kandidaten blockieren")
        assertTrue(freigabeGesehen, "die bestaetigte Wende muss den Riegel wieder oeffnen")
        assertTrue(positiveNachFreigabe > 0.0, "nach der Wende muss die Mahlzeit wieder versorgt werden")
    }

    /**
     * DER AUFGESCHOBENE PHASE-A-ANTEIL ERREICHT DEN ECHTEN PHASE-B-PFAD.
     *
     * Der Kern allein beweist nur die Mengenrechnung. Dieser Test zwingt den
     * Runner durch dieselbe Reihenfolge wie produktiv: vorgefundener Latch,
     * drei bestaetigte Wendezyklen, Phase B, Entscheidungssnapshot und Lift.
     * Die Phase-A-Bilanz wird nach dem bewiesenen Wiederaufgehen eingesetzt,
     * damit kein normaler Prime-Schritt den zu pruefenden Rueckstand nebenbei
     * schliesst. Danach springt die Uhr ueber die Uebergabe; die Rohreihe
     * bleibt dabei minuetlich lueckenlos und wird nicht umgeschrieben.
     */
    @Test
    fun `nach bestaetigter Wende wird der Abwaertsaufschub in Phase B wieder faellig`(@TempDir dir: File) {
        fundamentAn = true
        fundamentAnteil = 0.80
        markerAuthorized = true
        primeHuelleU = 3.75
        // Drei klare positive Zyklen fuer die Wende, danach ein ruhiges
        // Plateau nahe Ziel: der normale Korrekturpfad soll das Fundament
        // nicht bloss durch eine eigene grosse Anforderung verdecken.
        flach = 90.0
        steigungProMin = 2.2
        knickAbMin = 6
        steigungNachKnick = 0.0
        bolusIobU = null
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        l.episodes.descentRecoveryLatch = DescentRecoveryLatch.State(true, start - 60_000L)
        neuerRunner(l)
        markerAt = start + 2 * 60_000L

        var recovered = false
        var phaseBReached = false
        for (ignored in 0 until 30) {
            val o = cycle()
            if (o.descentLatchReason == DescentRecoveryLatch.Reason.RECOVERED.name) recovered = true
            if (o.mealFoundation.phase == MealFoundation.Phase.PHASE_B) {
                phaseBReached = true
                break
            }
        }
        assertTrue(recovered, "die Wende MUSS bestaetigt sein - sonst darf der Aufschub nicht wirken")
        assertTrue(phaseBReached, "der minuetlich lueckenlose Lauf MUSS Phase B erreichen")
        assertTrue(ledger.episodes.foundation.valid, "die gepinnte Autorisierung MUSS stehen")

        // Der gemessene Fruehstuecksfall: 3,00 U Phase-A-Soll, 1,35 U
        // geliefert, 1,65 U durch den harten Riegel unvermeidbar aufgeschoben.
        ledger.episodes.deliveredPhaseAU = 1.35
        ledger.episodes.deliveredSinceHandoverU = 0.0
        ledger.episodes.confirmedNotSentPhaseAU = 0.0
        ledger.episodes.descentDeferredPhaseAU = 1.65

        // ECHTER Prozessschnitt: Autorisierung, wieder geoeffneter Latch und
        // Aufschub kommen gemeinsam aus der versiegelten Generation. Die
        // halbe Erholungsserie ist absichtlich nicht Teil davon; hier ist die
        // Wende bereits vollstaendig bestaetigt.
        assertTrue(ledger.persistVerified(dir), "der vorbereitete Zustand muss versiegelt werden")
        val restarted = FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch-2", clock) }
        neuerRunner(restarted)
        markerPress = 0L
        assertEquals(1.65, ledger.episodes.descentDeferredPhaseAU, 1e-9, "restartfester Aufschub")
        assertFalse(ledger.episodes.descentRecoveryLatch.active, "die bestaetigte Wende bleibt offen")

        var eligibleSeen = false
        var liftSum = 0.0
        repeat(3) {
            val o = cycle()
            if (o.mealFoundation.phase == MealFoundation.Phase.PHASE_B) {
                assertEquals(
                    DescentDeferredCarry.Eligibility.ELIGIBLE,
                    o.mealFoundation.descentCarryEligibility,
                    "nach der bestaetigten Wende muss genau dieser Aufschub freigegeben sein",
                )
                assertEquals(1.65, o.mealFoundation.effectiveDescentCarryU, 1e-9)
                assertEquals(2.40, o.mealFoundation.phaseBAllowanceU, 1e-9)
                eligibleSeen = true
                liftSum += o.foundationLiftU
            }
        }

        assertTrue(eligibleSeen, "der echte Runner MUSS Phase B erreicht haben")
        assertTrue(liftSum > 0.0, "der Aufschub muss ueber den echten Fundament-Lift wieder fliessen koennen")

        // TONIS BESTAETIGTE REGEL (21.08.): ein manueller NORMAL-Bolus nach
        // dem Marker beendet NUR diesen Sicherheitsaufschub. Er wird nicht
        // als Fundament-Lieferung umgedeutet; das regulaere B-Teilbudget
        // bleibt deshalb exakt 0,75 U.
        boluses = listOf(BS(timestamp = clock, amount = 3.0, type = BS.Type.NORMAL))
        val manual = cycle()
        assertEquals(3.0, manual.manualBolusAfterMarkerU!!, 1e-9)
        assertEquals(
            DescentDeferredCarry.Eligibility.MANUAL_BOLUS_AFTER_MARKER,
            manual.mealFoundation.descentCarryEligibility,
        )
        assertEquals(0.0, manual.mealFoundation.effectiveDescentCarryU, 1e-9)
        assertEquals(0.75, manual.mealFoundation.phaseBAllowanceU, 1e-9)

        // Die Behandlungshistorie ist eine Freigabevoraussetzung fuer den
        // zusaetzlichen Sicherheitsaufschub. Ein Lesefehler darf deshalb nie
        // als "kein manueller Bolus" durchgehen. Das regulaere B-Teilbudget
        // bleibt auch in diesem fail-closed-Fall erhalten.
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any()))
            .thenThrow(IllegalStateException("Bolushistorie nicht lesbar"))
        val unreadable = cycle()
        assertNull(unreadable.manualBolusAfterMarkerU)
        assertEquals(
            DescentDeferredCarry.Eligibility.MANUAL_BOLUS_UNKNOWN,
            unreadable.mealFoundation.descentCarryEligibility,
        )
        assertEquals(0.0, unreadable.mealFoundation.effectiveDescentCarryU, 1e-9)
        assertEquals(0.75, unreadable.mealFoundation.phaseBAllowanceU, 1e-9)
    }

    @Test
    fun `nur NORMAL strikt nach Marker gilt als manuelle Deckung`() {
        val auth = MealFoundation.arm(
            markerTs = start,
            foundationEnabled = true,
            totalBudgetU = 3.75,
            phaseAShare = 0.80,
            phaseAUpfrontShare = 0.0,
            primeWindowMin = 20,
            wallCeilingMin = 45,
            pressObservedInThisProcess = true,
            primeDeclinedByUser = false,
            markerAuthorized = true,
            phaseBUntilMin = 60,
        )
        val view = FuseCycleRunner.TreatmentView(
            boluses = listOf(
                BS(timestamp = start - 1L, amount = 2.0, type = BS.Type.NORMAL),
                BS(timestamp = start + 1L, amount = 0.3, type = BS.Type.SMB),
                BS(timestamp = start + 2L, amount = 0.5, type = BS.Type.PRIMING),
                BS(timestamp = start + 3L, amount = 3.0, type = BS.Type.NORMAL),
            ),
            facts = emptyList(),
            snapshotHash = "test",
            latestBolusTs = start + 3L,
            diaHours = 5.0,
        )

        assertEquals(3.0, runner.manualBolusAfterMarkerU(auth, view)!!, 1e-9)
        assertNull(runner.manualBolusAfterMarkerU(auth, null), "unlesbar bleibt unbekannt")
    }



    /**
     * DER RIEGEL DARF KEINE SPUR IN DER BUCHFUEHRUNG HINTERLASSEN
     * (Codex 19.08.).
     *
     * `actuatedU` entsteht erst aus `combined.decision.smbU`. Greift der
     * Riegel, muessen Prime-, Fundament- und Evidenzzaehler sowie die
     * Reservierung unberuehrt bleiben - sonst waere die Autorisierung als
     * verbraucht gebucht, ohne dass etwas floss, und die naechste Mahlzeit
     * begaenne mit einer Huelle, die sie nie bekommen hat.
     *
     * DER `grant` BLEIBT BEWUSST STEHEN. Er zeigt im Trail, dass eine
     * Autorisierung vorhanden war und vom gemessenen Riegel gestoppt wurde.
     * Entscheidend ist, dass niemand daraus ohne `smbU > 0` eine Aktuation
     * ableitet - genau das prueft dieser Test.
     */
    @Test
    fun `der Riegel bucht nichts und meldet die Lage als unsicher`(@TempDir dir: File) {
        abwaertslage(dir)
        val e = ledger.episodes
        var geprueft = false
        var primeVor = 0.0
        var evidenzVor = 0.0
        var phaseAVor = 0.0
        var seitUVor = 0.0

        repeat(30) {
            primeVor = e.primeSpentU
            evidenzVor = e.evidenceCommittedU
            phaseAVor = e.deliveredPhaseAU
            seitUVor = e.deliveredSinceHandoverU
            val o = cycle()
            if (o.decision.block == FuseController.Block.MEASURED_DESCENT_RISK) {
                assertEquals(0.0, o.decision.smbU, 1e-9, "die Menge ist null")
                assertTrue(
                    o.decision.unsafeSituation,
                    "eine GEMESSENE Abwaertslage MUSS als unsicher gemeldet werden - " +
                        "nachgelagerte Sicherheitslogik darf sie nicht als sicher lesen",
                )
                // KEINE Buchung, keine Reservierung.
                assertEquals(primeVor, e.primeSpentU, 1e-9, "primeSpentU unveraendert")
                assertEquals(evidenzVor, e.evidenceCommittedU, 1e-9, "evidenceCommittedU unveraendert")
                assertEquals(phaseAVor, e.deliveredPhaseAU, 1e-9, "deliveredPhaseAU unveraendert")
                assertEquals(seitUVor, e.deliveredSinceHandoverU, 1e-9, "deliveredSinceHandoverU unveraendert")
                assertNull(e.pendingReservation, "und keine Reservierung")
                geprueft = true
            }
        }
        assertTrue(geprueft, "der Aufbau muss den Riegel erreichen")
    }


    // ==== PUNKT 6: DER MARKER-PRIME-AUFSCHUB (Tonis 7 Replay-Pflichtfaelle) =
    //
    // Schalter default AUS, kein Aktivierungs-GO - diese Tests schalten ihn
    // im Geruest bewusst ein und fahren den ECHTEN Runner. Die Form ist der
    // 18:19-Fall: maessiger Fall, Boden ZWISCHEN dem 30er-Korrekturriegel
    // und dem gepinnten 60er-Marker-Horizont, Bolus-Ueberdeckung vorhanden.

    private fun punkt6Lage(dir: File, fristMin: Int = 120): FuseLedgerAdapter {
        aufschubAn = true
        aufschubHorizontMin = 60.0
        aufschubFristMin = fristMin
        fundamentAn = true
        fundamentAnteil = 0.80
        markerAuthorized = true
        primeHuelleU = 3.75
        tailGuard = false
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = null
        knick2AbMin = null
        bolusIobU = 3.0
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        markerAt = start + 2 * 60_000L
        return adapter
    }

    /** Replay-Fall 1 (18:19): vollstaendiger Aufschub, kein Insulin im Fall. */
    @Test
    fun `P6 Fall 1 - im gemessenen Fall geht trotz Marker nichts hinaus sondern in den Aufschub`(@TempDir dir: File) {
        punkt6Lage(dir)
        // Der Marker faellt wie am 18:19 MITTEN in den laufenden Fall - die
        // Rate ist dann bereits gemessen konvergiert. Ein Marker in einen
        // noch kalten Filter hinein ist eine andere (mildere) Lage: dort
        // liegt der Boden gemessen noch jenseits des Horizonts.
        markerAt = start + 8 * 60_000L
        var summe = 0.0
        var withheld = 0.0
        var open = 0.0
        var blockGesehen = false
        repeat(24) { i ->
            val o = cycle()
            if (i >= 8) summe += o.decision.smbU
            withheld += o.deferredPrimeWithheldU
            open = o.deferredPrimeOpenU
            if (o.decision.block == FuseController.Block.MARKER_PRIME_DEFERRED) blockGesehen = true
        }
        assertTrue(blockGesehen, "der Aufschub-Block MUSS im Trail stehen")
        assertEquals(0.0, summe, 1e-9, "kein Insulin im gemessenen Fall: $summe U")
        assertTrue(withheld > 0.2, "es MUSS wirklich etwas zurueckgehalten worden sein: $withheld U")
        assertTrue(open > 0.0 && open <= withheld + 1e-9, "offen = zurueckgehalten, huellengedeckelt: $open")
        assertTrue(open <= 3.75 + 1e-9, "nie ueber die gepinnte Huelle")
    }

    /**
     * DIE POSITIVKONTROLLE des Schalters: dieselbe Lage mit Schalter AUS ist
     * exakt der 18:19-Fehler - Insulin fliesst in den Fall. Sie beweist
     * beides zugleich: der Default ist dosierneutral, und der Aufbau
     * erreicht wirklich den Mechanismus.
     */
    @Test
    fun `P6 Fall 1b - Schalter aus ist der alte Fehler und bleibt dosierneutral`(@TempDir dir: File) {
        punkt6Lage(dir)
        aufschubAn = false
        var summe = 0.0
        var withheld = 0.0
        repeat(24) {
            val o = cycle()
            summe += o.decision.smbU
            withheld += o.deferredPrimeWithheldU
            assertTrue(o.decision.block != FuseController.Block.MARKER_PRIME_DEFERRED)
        }
        assertTrue(summe > 0.2, "ohne Schalter fliesst markerautorisiertes Insulin in den Fall: $summe U")
        assertEquals(0.0, withheld, 1e-9)
    }

    /** Replay-Fall 2 (14:21/08:59): kein unnoetiger Aufschub beim langsamen Fall. */
    @Test
    fun `P6 Fall 2 - der langsame Fall mit fernem Boden wird nicht aufgeschoben`(@TempDir dir: File) {
        punkt6Lage(dir)
        // Die 08:59-Form: Boden erst in ~67 min - jenseits des 60er-Horizonts.
        flach = 100.0
        steigungProMin = -0.45
        // Wie am 08:59 gemessen: die Rate klingt ab, der Boden bleibt fern.
        knickAbMin = 10
        steigungNachKnick = -0.05
        bolusIobU = 1.2
        var summe = 0.0
        var withheld = 0.0
        repeat(20) {
            val o = cycle()
            summe += o.decision.smbU
            withheld += o.deferredPrimeWithheldU
        }
        assertEquals(0.0, withheld, 1e-9, "kein Fehlaufschub im Gutfall")
        assertTrue(summe > 0.3, "das Prime fliesst wie bisher: $summe U")
    }

    /** Replay-Fall 3: Erholung -> kontrollierte Freigabe, kein Burst. */
    @Test
    fun `P6 Fall 3 - nach bestaetigter Erholung kommt hoechstens ein Schritt je Zyklus`(@TempDir dir: File) {
        punkt6Lage(dir)
        knickAbMin = 15
        steigungNachKnick = 1.5
        var releasedSum = 0.0
        var releaseZyklen = 0
        var vorher = Double.MAX_VALUE
        val denials = mutableListOf<String?>()
        repeat(45) {
            val o = cycle()
            assertTrue(
                o.deferredPrimeReleasedU <= 0.05 + 1e-9,
                "hoechstens EIN Pumpenschritt je Zyklus: " + o.deferredPrimeReleasedU,
            )
            if (o.deferredPrimeReleasedU > 0.0) {
                releaseZyklen++
                releasedSum += o.deferredPrimeReleasedU
                assertTrue(
                    o.deferredPrimeOpenU < vorher,
                    "jede Freigabe verkleinert den offenen Betrag",
                )
            }
            if (o.deferredPrimeOpenU > 0.0 || o.deferredPrimeReleasedU > 0.0) vorher = o.deferredPrimeOpenU
            denials.add(o.deferredPrimeDenial)
        }
        assertTrue(releaseZyklen >= 3, "die Freigabe muss wirklich gelaufen sein: $releaseZyklen Zyklen, Denials: $denials")
        assertTrue(releasedSum > 0.1, "und messbar geliefert haben: $releasedSum U")
    }

    /** Replay-Fall 4: keine Erholung bis zur Frist -> Rest verfaellt typisiert. */
    @Test
    fun `P6 Fall 4 - ohne Erholung verfaellt der Rest sichtbar an der gepinnten Frist`(@TempDir dir: File) {
        punkt6Lage(dir, fristMin = 45)
        // Nach dem ersten Fall nur noch ein Drift: nie drei Erholungszyklen.
        knickAbMin = 20
        steigungNachKnick = -0.05
        var lapseReason: String? = null
        var lapseU = 0.0
        var openDavor = 0.0
        repeat(55) {
            val o = cycle()
            if (o.deferredPrimeLapseReason == "EXPIRED" && lapseReason == null) {
                lapseReason = o.deferredPrimeLapseReason
                lapseU = o.deferredPrimeLapseU
            }
            if (o.deferredPrimeOpenU > 0.0) openDavor = o.deferredPrimeOpenU
            assertEquals(0.0, o.deferredPrimeReleasedU, 1e-9, "ohne bestaetigte Erholung keine Freigabe")
        }
        assertEquals("EXPIRED", lapseReason, "der Verfall MUSS typisiert im Trail stehen")
        assertTrue(lapseU > 0.0, "und die verfallene Menge beziffern: $lapseU")
        assertEquals(openDavor, lapseU, 1e-9, "verfallen ist genau der zuletzt offene Betrag")
    }

    /** Replay-Fall 5: normale/manuelle Lieferung reduziert denselben offenen Betrag. */
    @Test
    fun `P6 Fall 5 - ein manueller Bolus nach Erholung verkleinert den offenen Betrag`(@TempDir dir: File) {
        punkt6Lage(dir)
        knickAbMin = 15
        steigungNachKnick = 1.5
        var openVorBolus = 0.0
        repeat(28) {
            val o = cycle()
            if (o.deferredPrimeOpenU > 0.0) openVorBolus = o.deferredPrimeOpenU
        }
        assertTrue(openVorBolus > 0.15, "der Aufbau braucht einen nennenswerten offenen Betrag: $openVorBolus")
        // Manueller NORMAL-Bolus NACH dem Marker: zehrt von derselben Huelle.
        boluses = listOf(BS(timestamp = clock, amount = 1.0, type = BS.Type.NORMAL))
        val o = cycle()
        // POOL-MATHEMATIK (Vertraege 6+7): der manuelle Bolus zehrt zuerst
        // die freie Huelle auf und drueckt DANN den offenen Aufschub -
        // openNach = min(openVor, Resthuelle - 1,0). Ein Betrag, der nicht
        // sinkt, waere der Fehler; um WIE viel er sinkt, haengt an der
        // freien Huelle des Aufbaus.
        assertTrue(
            o.deferredPrimeOpenU < openVorBolus - 1e-9,
            "der manuelle Bolus MUSS den offenen Betrag druecken: " +
                openVorBolus + " -> " + o.deferredPrimeOpenU,
        )
        assertTrue(
            o.deferredPrimeOpenU + 1.0 <= openVorBolus + 0.8 + 1e-9,
            "und zwar um mindestens den Teil des Bolus, der nicht mehr in die freie Huelle passt",
        )
    }

    /** Replay-Fall 6: Neustart vor und nach der Erholung - identisches Budget, identische Frist. */
    @Test
    fun `P6 Fall 6 - der Neustart aendert weder Budget noch Frist`(@TempDir dir: File) {
        val ledger = punkt6Lage(dir)
        knickAbMin = 15
        steigungNachKnick = 1.5
        var openVor = 0.0
        var deadlineVor = 0L
        // 18 Zyklen: Fall (Aufschub waechst), dann drei Minuten Anstieg -
        // NOCH ohne bestaetigte Erholung, also eingefrorener Zustand.
        repeat(18) {
            val o = cycle()
            if (o.deferredPrimePinnedForTs > 0L) {
                openVor = o.deferredPrimeOpenU
                deadlineVor = o.deferredPrimeDeadlineTs
            }
        }
        assertTrue(openVor > 0.0, "vor dem Neustart muss etwas offen sein")
        assertTrue(deadlineVor > 0L)

        // Im Geruest laeuft die Publikation (und damit der zyklische
        // Persist) nicht - versiegeln wie am Zyklusende des Plugins.
        assertTrue(ledger.persistVerified(dir), "der Zustand muss versiegelt werden")

        // ERST DIE DATEI: der durable Zustand traegt Budget und Frist.
        val durabel = nachNeustart(dir).deferredPrime
        assertEquals(openVor, durabel.openU, 1e-9, "identisches Budget in der Datei")
        assertEquals(deadlineVor, durabel.deadlineTs, "identische gepinnte Frist in der Datei")

        // NEUSTART MITTEN IM AUFSCHUB: gleicher Ordner, gleiche Epoche.
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", clock) })
        var openNach = -1.0
        var deadlineNach = 0L
        var horizonNach = 0
        var gepinnteZyklen = 0
        repeat(8) {
            val o = cycle()
            if (o.deferredPrimePinnedForTs > 0L) {
                gepinnteZyklen++
                if (openNach < 0.0) {
                    // Der ERSTE gepinnte Zyklus nach dem Neustart traegt den
                    // wiederhergestellten Zustand, bevor neue Buchungen laufen.
                    openNach = o.deferredPrimeOpenU
                    deadlineNach = o.deferredPrimeDeadlineTs
                    horizonNach = o.deferredPrimeHorizonMin
                }
            }
            // Ein Neustart belegt KEINE Erholung: die drei Zyklen werden neu
            // verdient. Erst ab dem dritten gepinnten Zyklus darf wieder
            // etwas nachlaufen - fruehere Freigabe waere Vertragsbruch 4.
            if (gepinnteZyklen < 3) assertEquals(
                0.0, o.deferredPrimeReleasedU, 1e-9,
                "keine Freigabe vor neu verdienter Erholung",
            )
        }
        assertEquals(openVor, openNach, 1e-9, "identisches Budget nach dem Neustart")
        assertEquals(deadlineVor, deadlineNach, "identische gepinnte Frist")
        assertEquals(60, horizonNach, "identischer gepinnter Horizont")
    }

    /** Replay-Fall 7: neues gemessenes Risiko waehrend der Nachlieferung stoppt sofort. */
    @Test
    fun `P6 Fall 7 - ein neuer Fall stoppt die Nachlieferung sofort`(@TempDir dir: File) {
        punkt6Lage(dir)
        knickAbMin = 15
        // Flache Erholung: die Kandidaten des Anstiegs sollen den Pool nicht
        // schon VOR dem neuen Fall leeren (Vertrag 6 laesst sie zehren).
        steigungNachKnick = 0.8
        knick2AbMin = 28
        steigungNachKnick2 = -2.5
        var releasesVorKnick2 = 0
        var releasesNachRisiko = 0
        var openBeiRisiko = -1.0
        var risikoGesehen = false
        repeat(55) { i ->
            val o = cycle()
            val minute = i + 1
            if (minute < 28 && o.deferredPrimeReleasedU > 0.0) releasesVorKnick2++
            if (minute >= 32) {
                // Spaetestens vier Minuten nach dem zweiten Knick ist der
                // Fall gemessen - ab da darf NICHTS mehr nachlaufen.
                if (o.deferredPrimeOpenU > 0.0) {
                    risikoGesehen = true
                    if (openBeiRisiko < 0.0) openBeiRisiko = o.deferredPrimeOpenU
                    if (o.deferredPrimeReleasedU > 0.0) releasesNachRisiko++
                    // KEIN Einfrieren behaupten: andere Lieferungen desselben
                    // Pools duerfen ihn weiter DRUECKEN (Vertrag 6). Er darf
                    // nur nie wachsen und nie ueber Freigaben schrumpfen.
                    assertTrue(
                        o.deferredPrimeOpenU <= openBeiRisiko + 1e-9,
                        "im neuen Fall waechst nichts nach",
                    )
                    openBeiRisiko = o.deferredPrimeOpenU
                }
            }
        }
        assertTrue(releasesVorKnick2 >= 2, "vor dem neuen Fall muss die Nachlieferung gelaufen sein")
        assertTrue(risikoGesehen, "der Aufbau muss den neuen Fall mit offenem Rest erreichen")
        assertEquals(0, releasesNachRisiko, "im neuen gemessenen Fall laeuft nichts nach")
    }


    // ==== DER LIVENESS-KANAL (Bauvertrag Toni + Codex 22.08.) ==============
    //
    // Schalter default AUS, kein Aktivierungs-GO - die Tests schalten ihn im
    // Geruest bewusst ein. Die Lage ist der 22.08.-Deadlock: anhaltender
    // Hochdruck ueber der Schwelle, hohe Bolus-Haftung, der Schwanz nullt
    // jede Abgabe ueber viele Zyklen. Die fuenf von Toni geforderten
    // Mutationsfaenger plus die Grenztests der konfigurierbaren Schwelle:
    //   Fall 1   Tail-Kappe versehentlich noch aktiv im Kanal
    //   Fall 2   additive statt max-Verknuepfung
    //   Fall 3   globales iobTH veraendert/ignoriert (P0-Deckelvertrag)
    //   Fall 4   P2-Exit entfernt (+ Gegen-Tagesform bleibt unversorgt)
    //   Fall 5   Re-Arm-Sperre nach Neustart verloren (+ manueller Exit)
    //   Grenze   BG-Schwelle strikt, konfigurierbar, Aenderung beendet Lauf

    /**
     * Fall 1b - der GUARD-Deadlock (die 22.08.-Fehlerklasse: Unterkante
     * median +97 mg/dl zu tief zertifiziert): die aktivitaetsgetriebene
     * carb-freie Unterkante taucht unter den Boden, der Normalpfad nullt
     * ueber GUARD_FLOOR - gemessen steigt der Zucker. Der Kanal MUSS hier
     * heben, und das Modell-Tor darf dabei NIE anschlagen: technisch ist
     * die Bahn einwandfrei, allein ihr semantisches Urteil ist der
     * bekannte Fehlzertifikat-Fall. Ein ins Tor geleaktes GUARD-Urteil
     * (volle verifyGuardFloor statt verifyTechnicalIntegrity) nullt den
     * Kanal in genau dieser Lage - im Rig als Mutation nachgewiesen.
     */
    @Test
    fun `Liveness Fall 1b - im Guard-Deadlock hebt der Kanal und das Modell-Tor schlaegt nicht an`(@TempDir dir: File) {
        livenessLage(dir)
        tailGuard = false
        aktivitaet = 0.03
        var liftZyklen = 0
        var modellFehlalarme = 0
        var guardGesehen = false
        repeat(26) {
            val o = cycle()
            if (o.decision.block == FuseController.Block.GUARD_FLOOR) guardGesehen = true
            if (o.livenessDenial == "MODEL_UNAVAILABLE" || o.livenessExit == "MODEL_UNAVAILABLE") modellFehlalarme++
            if (o.livenessLiftU > 0.0) {
                liftZyklen++
                assertEquals(FuseController.Block.NONE, o.decision.block)
                assertEquals(
                    LivenessChannel.quantize(o.livenessCandidateU, 0.05), o.decision.smbU, 1e-9,
                    "die Endmenge ist der Kanal-Kandidat, kein Guard-Rest",
                )
            }
        }
        assertTrue(guardGesehen, "der Aufbau muss den Guard-Deadlock erreichen")
        assertEquals(0, modellFehlalarme, "das TECHNISCHE Tor darf im semantischen Deadlock nie anschlagen")
        assertTrue(liftZyklen >= 4, "der Kanal muss den Guard-Deadlock tragen: $liftZyklen")
    }

    private fun livenessLage(dir: File): FuseLedgerAdapter {
        livenessAn = true
        // Kanaldeckel 90 %: der Spielraum (7,2 - 4,5 = 2,7 U) liegt WEIT
        // ueber dem Kandidaten - in dieser Lage bindet der Kandidat, und
        // die Endmengen-Asserts rechnen gegen ihn. Die Deckel-Bindung
        // prueft Fall 3 mit eigenen Zahlen.
        corrExpLimit = 7.2; mealExpLimit = 7.2 // frueher 90 % x maxIOB 8
        livenessBgMin = 160.0
        livenessReArmMin = 10
        tailGuard = true
        markerAuthorized = false
        fundamentAn = false
        aufschubAn = false
        // Flacher Vorlauf (0,9 - unter der r-Schwelle 1,0), dann Knick
        // AUFWAERTS auf 1,4: der Filter konvergiert von UNTEN gegen den
        // Drive. Ein rauschfrei monoton von OBEN konvergierender Drive
        // wuerde declineStreak >= 2 ausloesen und den frisch bewaffneten
        // Lauf sofort per P2 beenden (im Rig gesehen; live schuetzt das
        // Messrauschen diese Kante, im Rig gibt es keins).
        flach = 185.0
        steigungProMin = 0.9
        knickAbMin = 10
        steigungNachKnick = 1.4
        knick2AbMin = null
        // 4,5 U x ISF 54 = ~243 mg/dl Schwanzlast gegen den flachen
        // Anstieg: der Schwanz bleibt ueber ~25 Zyklen bindend - der
        // ANHALTENDE 22.08.-Deadlock, nicht nur ein kurzes Fenster.
        bolusIobU = 4.5
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        return adapter
    }

    /**
     * M2 (Bauauftrag 7.5.2, Toni 29.08.): DER FOUNDATION-TROPF DARF DIE
     * BEWAFFNUNG NICHT MASKIEREN.
     *
     * Livefall zweimal gemessen (28.08. 09:50, 29.08. 09:41): der 0,05er-
     * Phase-B-Schritt (plus MarkerFloor-Restauration nach dem Guard-Veto)
     * machte aus GUARD_FLOOR ein publiziertes NONE, und das Bewaffnungstor
     * las NORMAL_PATH_OPEN - ausgerechnet im Streak-3-Zyklus. Dieses Rig
     * stellt den Tropf DICHT (Phase-B-Budget 2,0 U ueber 40 min = ein
     * Schritt je Zyklus): unter dem alten Tor waere JEDER Druckzyklus
     * maskiert und der Kanal bewaffnete waehrend des gesamten Tropfs nie.
     * Die Mutation (Tor zurueck auf den publizierten Block) macht genau
     * dieses Rig rot - beide Asserts.
     */
    @Test
    fun `M2 - der Foundation-Tropf maskiert die Bewaffnung nicht mehr`(@TempDir dir: File) {
        livenessAn = true
        corrExpLimit = 7.2; mealExpLimit = 7.2 // frueher 90 % x maxIOB 8
        livenessBgMin = 160.0
        livenessReArmMin = 10
        tailGuard = true
        fundamentAn = true
        fundamentAnteil = 0.5
        upfrontAnteil = 0.0
        primeHuelleU = 4.0
        fundamentEndeMin = 60
        markerAuthorized = true
        // Rig-Falle: unstubbt faellt PrimeWindowMin auf 15 zurueck - hier
        // ausdruecklich der Geraetewert, damit Phase B ab Minute 22 laeuft.
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        // Ruhiger Unterlauf, Knick AUFWAERTS (der Filter konvergiert von
        // unten - keine Scheinwende), die 160er-Schwelle faellt in Phase B.
        flach = 150.0
        steigungProMin = 0.2
        knickAbMin = 18
        steigungNachKnick = 1.4
        knick2AbMin = null
        bolusIobU = 4.5
        clock = start
        markerAt = start + 2 * 60_000L
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)

        var armZyklus: FuseCycleRunner.Outcome? = null
        var maskiert = 0
        var liftImDruck = false
        repeat(50) {
            val o = cycle()
            if (o.livenessDenial == "NORMAL_PATH_OPEN" &&
                o.underlyingNormalBlock in listOf("GUARD_FLOOR", "TAIL")
            ) maskiert++
            if (!o.livenessActive && o.livenessStreak > 0 && o.foundationLiftU > 0.0) liftImDruck = true
            if (armZyklus == null && o.livenessActive) armZyklus = o
        }
        // Vorbedingung des Rigs: die Ziel-Konstellation wurde erreicht -
        // mindestens ein Druckzyklus trug wirklich einen Foundation-Schritt.
        assertTrue(liftImDruck, "der Aufbau MUSS Druckzyklen mit Foundation-Schritt erzeugen")
        assertTrue(armZyklus != null, "der Kanal MUSS sich trotz laufendem Tropf bewaffnen")
        assertEquals(
            0, maskiert,
            "kein Zyklus darf mit verdecktem GUARD/TAIL als NORMAL_PATH_OPEN abgelehnt werden",
        )
        // DIE GEMEINSAME BESTAETIGUNG AM ERSTEN BEWAFFNETEN ZYKLUS (Toni
        // 29.08.): exakt die Live-Signatur der Maskierung - Streak 3
        // (erster armierbarer Zyklus, nichts davor verloren), ein
        // Foundation-Schritt IN diesem Zyklus, unterliegend GUARD/TAIL,
        // publizierter Block offen. Unter dem alten Tor waere genau dieser
        // Zyklus NORMAL_PATH_OPEN gewesen.
        val arm = armZyklus!!
        assertEquals(3, arm.livenessStreak, "bewaffnet im ERSTEN armierbaren Zyklus")
        assertTrue(arm.foundationLiftU > 0.0, "der Bewaffnungszyklus traegt einen Foundation-Schritt")
        assertTrue(
            arm.underlyingNormalBlock in listOf("GUARD_FLOOR", "TAIL"),
            "unterliegend im Deadlock: ${arm.underlyingNormalBlock}",
        )
        assertEquals(
            FuseController.Block.NONE, arm.decision.block,
            "und der publizierte Block ist offen - die Maskierungs-Signatur",
        )
    }

    /**
     * A1 (Bauauftrag Paragraph 4): DIE KONTEXTWAHL HAENGT NICHT AM
     * LIVENESS-SCHALTER. Vor A1 existierte die MEAL/CORRECTION-Entscheidung
     * nur im eingeschalteten Kanal - mit Kanal AUS gab es gar keinen
     * Kontext. Jetzt traegt jeder Zyklus die zentrale Entscheidung, auch
     * wenn der Kanal aus ist; der Kanal ist nur noch Konsument.
     */
    @Test
    fun `A1 - der Dosierkontext existiert auch mit ausgeschaltetem Kanal`(@TempDir dir: File) {
        livenessAn = false
        fundamentAn = true
        markerAuthorized = true
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        flach = 120.0
        steigungProMin = 0.5
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)

        // ERST gesunde Zyklen OHNE Marker, DANN der Druck: ein Druck, den
        // der erste gesunde Zyklus ueberhaupt sieht, gilt als VORGEFUNDEN
        // (lastSeen == -1) und pinnt vertragsgemaess nicht - im Rig fiel
        // der Druck sonst mit den Kaltstart-Abbruechen zusammen.
        repeat(6) { cycle() }
        markerAt = clock + 60_000L

        var mealGesehen = false
        var letzter: FuseCycleRunner.Outcome? = null
        val gesehen = mutableListOf<String>()
        repeat(12) {
            val o = cycle()
            letzter = o
            gesehen += "${o.dosingContextProfile}/${o.dosingContextReason}" 
            if (o.dosingContextProfile == "MEAL") {
                mealGesehen = true
                assertEquals("MARKER_POWER", o.dosingContextReason)
                assertEquals(markerAtIntern, o.dosingContextAuthorizationId)
                assertTrue((o.dosingContextAuthorizationExpiresAt ?: 0L) > markerAtIntern)
            }
            // Der Kanal selbst bleibt aus - Kontext und Kanal sind getrennt.
            assertTrue(o.livenessDenial == "DISABLED" || o.livenessDenial == null)
        }
        assertTrue(mealGesehen, "die gepinnte Markervollmacht MUSS als MEAL-Kontext erscheinen - gesehen: $gesehen")
        assertTrue(letzter!!.dosingContextProfile != null, "kein Zyklus ohne Kontextentscheidung")
    }

    /**
     * M1 (Bauauftrag 7.5.1): unter GUELTIGER MEAL-Vollmacht gilt die eigene
     * MEAL-Druckschwelle - der Kanal bewaffnet UNTERHALB der Tagesschwelle.
     * Beleg: 55-min-Loch am Abend 28.08., 35 min am Fruehstueck 29.08. -
     * die Korrektur-Schwelle blockte die Druckzaehlung unter stehender
     * Vollmacht. Die Prime-Drip-Zyklen des offenen Markerfensters maskieren
     * dank M2 (underlyingNormalBlock) nicht mehr.
     */
    @Test
    fun `M1 - die MEAL-Schwelle bewaffnet unterhalb der Tagesschwelle`(@TempDir dir: File) {
        livenessAn = true
        corrExpLimit = 7.2; mealExpLimit = 7.2 // frueher 90 % x maxIOB 8
        livenessBgMin = 160.0
        livenessReArmMin = 10
        tailGuard = true
        markerAuthorized = true
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        mealBgMin = 120.0
        flach = 100.0
        steigungProMin = 0.3
        knickAbMin = 12
        steigungNachKnick = 1.2
        knick2AbMin = null
        bolusIobU = 4.5
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)

        // Erst gesunde Zyklen, DANN der Druck (vorgefundene Marker pinnen nie).
        repeat(7) { cycle() }
        markerAt = clock + 60_000L

        var armZyklus: FuseCycleRunner.Outcome? = null
        repeat(40) {
            val o = cycle()
            if (armZyklus == null && o.livenessActive) armZyklus = o
        }
        assertTrue(armZyklus != null, "der Kanal MUSS unter der Vollmacht bewaffnen")
        val arm = armZyklus!!
        assertTrue(
            arm.signal!!.q1 < 160.0,
            "und zwar UNTERHALB der Tagesschwelle: q1=${arm.signal!!.q1}",
        )
        assertEquals("MEAL", arm.livenessBgMinSource, "die wirksame Schwelle ist die MEAL-Schwelle")
        assertEquals(120.0, arm.livenessBgMinEffectiveMgdl!!, 1e-9)
    }

    /** GEGENPROBE: ohne Vollmacht bleibt die Tagesschwelle - dieselbe Lage
     *  ohne Markerdruck bewaffnet nie (q1 bleibt unter 160). Eine Mutation,
     *  die die MEAL-Schwelle in CORRECTION wirken laesst, macht diesen
     *  Test rot. */
    @Test
    fun `M1 - ohne Vollmacht gilt die Tagesschwelle weiter`(@TempDir dir: File) {
        livenessAn = true
        corrExpLimit = 7.2; mealExpLimit = 7.2 // frueher 90 % x maxIOB 8
        livenessBgMin = 160.0
        livenessReArmMin = 10
        tailGuard = true
        markerAuthorized = true
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        mealBgMin = 120.0
        flach = 100.0
        steigungProMin = 0.3
        knickAbMin = 12
        steigungNachKnick = 1.2
        knick2AbMin = null
        bolusIobU = 4.5
        clock = start
        markerAt = 0L
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)

        var druckGesehen = false
        repeat(47) {
            val o = cycle()
            assertTrue(!o.livenessActive, "ohne Vollmacht darf unter 160 nie bewaffnet werden")
            if (o.livenessStreak > 0) druckGesehen = true
            if (o.livenessBgMinSource != null) assertTrue(
                o.livenessBgMinSource != "MEAL",
                "ohne Vollmacht darf die MEAL-Schwelle nie wirksam sein",
            )
        }
        assertTrue(!druckGesehen, "q1 bleibt unter der Tagesschwelle - kein Druckzyklus")
    }

    /** M3-Aufbau: MEAL-Vollmacht + Guard-Deadlock + Kurve ueber der
     *  Tagesschwelle - der erste bewaffnete Zyklus zeigt, wie viele
     *  Druckzyklen die Bewaffnung brauchte. */
    private fun m3Lage(dir: File): FuseLedgerAdapter {
        livenessAn = true
        corrExpLimit = 7.2; mealExpLimit = 7.2 // frueher 90 % x maxIOB 8
        livenessBgMin = 160.0
        livenessReArmMin = 10
        tailGuard = true
        markerAuthorized = true
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        flach = 150.0
        steigungProMin = 0.3
        knickAbMin = 12
        steigungNachKnick = 1.4
        knick2AbMin = null
        bolusIobU = 4.5
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        repeat(7) { cycle() }
        markerAt = clock + 60_000L
        return adapter
    }

    /** M3 (Bauauftrag 7.5.5), Pflichtfall 1: MealArmCycles = 1 bewaffnet
     *  unter der Vollmacht im ERSTEN Druckzyklus. */
    @Test
    fun `M3 - ein Zyklus bewaffnet unter Vollmacht sofort`(@TempDir dir: File) {
        mealArmZyklen = 1
        m3Lage(dir)
        var armZyklus: FuseCycleRunner.Outcome? = null
        repeat(40) {
            val o = cycle()
            if (armZyklus == null && o.livenessActive) armZyklus = o
        }
        assertTrue(armZyklus != null, "der Kanal MUSS bewaffnen")
        assertEquals(1, armZyklus!!.livenessStreak, "im ERSTEN Druckzyklus")
    }

    /** M3, Pflichtfall 3: der Default 3 ist der Altbestand - bewaffnet wird
     *  fruehestens im dritten Druckzyklus, keinen frueher. */
    @Test
    fun `M3 - der Default drei bleibt der Altbestand`(@TempDir dir: File) {
        mealArmZyklen = 3
        m3Lage(dir)
        var armZyklus: FuseCycleRunner.Outcome? = null
        repeat(40) {
            val o = cycle()
            if (!o.livenessActive && o.livenessStreak in 1..2) assertEquals(
                "NOT_CONFIRMED", o.livenessDenial,
                "unter drei Druckzyklen wird nicht bewaffnet",
            )
            if (armZyklus == null && o.livenessActive) armZyklus = o
        }
        assertTrue(armZyklus != null, "der Kanal MUSS bewaffnen")
        assertEquals(3, armZyklus!!.livenessStreak, "fruehestens im dritten Druckzyklus")
    }

    /** GEGENPROBE: ohne Vollmacht bleibt CORRECTION bei drei Zyklen, auch
     *  wenn MealArmCycles = 1 gesetzt ist. Eine Mutation, die die
     *  Zyklenzahl ohne Vollmacht anwendet, macht diesen Test rot. */
    @Test
    fun `M3 - ohne Vollmacht bleiben drei Zyklen`(@TempDir dir: File) {
        mealArmZyklen = 1
        livenessAn = true
        corrExpLimit = 7.2; mealExpLimit = 7.2 // frueher 90 % x maxIOB 8
        livenessBgMin = 160.0
        livenessReArmMin = 10
        tailGuard = true
        markerAuthorized = false
        flach = 150.0
        steigungProMin = 0.3
        knickAbMin = 12
        steigungNachKnick = 1.4
        knick2AbMin = null
        bolusIobU = 4.5
        clock = start
        markerAt = 0L
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        var armZyklus: FuseCycleRunner.Outcome? = null
        repeat(47) {
            val o = cycle()
            if (!o.livenessActive && o.livenessStreak in 1..2) assertEquals(
                "NOT_CONFIRMED", o.livenessDenial,
                "CORRECTION bewaffnet nie unter drei Druckzyklen",
            )
            if (armZyklus == null && o.livenessActive) armZyklus = o
        }
        assertTrue(armZyklus != null, "die Lage muss ueberhaupt bewaffnen (sonst prueft der Fall nichts)")
        assertEquals(3, armZyklus!!.livenessStreak)
    }

    // ---- P1 v45: Rebound-Sonderrecht auch im Liveness-Tor ----------------

    /**
     * P1-Aufbau (Eis-Livefall 30.08. 13:50): Low-Dip unter 75 oeffnet das
     * ROHE Rebound-Fenster (45 min), der Marker wird IM Fenster gedrueckt
     * (beide Pins: Power + Rebound-Sonderrecht), danach der steile Anstieg
     * (r weit ueber 1, q1 ueber der MEAL-Schwelle 120), Guard-Deadlock des
     * Normalpfads per Bolus-IOB. Sonderrechtsfrist wie in Produktion
     * (120 min), MealArmCycles = 1.
     *
     * @param druecken false = Marker wird VORGEFUNDEN (vor dem ersten
     *   Zyklus gesetzt) - pinnt nie, Sonderrecht entsteht nie.
     */
    private fun reboundOverrideLage(dir: File, druecken: Boolean = true): FuseLedgerAdapter {
        livenessAn = true
        corrExpLimit = 3.0; mealExpLimit = 7.0 // der Startsatz des Livefalls
        livenessBgMin = 160.0
        mealBgMin = 120.0
        mealArmZyklen = 1
        livenessReArmMin = 10
        reboundOverrideMaxMin = 120
        tailGuard = true
        markerAuthorized = true
        fundamentAn = false
        aufschubAn = false
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        // Livefall: Marker BEWUSST ohne Direktdosis - und nur so bleibt der
        // Evidenz-Topf im Rig ueberhaupt positiv (jede fruehe Abgabe zehrt
        // als Abzug am Bestand, bevor der Kredit je fliessen kann).
        whenever(preferences.get(FuseLongKey.MealMarkerNoPrime)).thenReturn(1L)
        flach = 80.0
        steigungProMin = -2.0   // Tal 68 bei min 6: Dip auf VERARBEITETEN
        knickAbMin = 6          // Zyklen (Warmup-Zyklen setzen lastLowTs nie)
        steigungNachKnick = 2.5 // der Eis-Anstieg
        knick2AbMin = null
        bolusIobU = 4.5         // Guard-/Schwanz-Deadlock des Normalpfads
        clock = start
        if (!druecken) {
            markerAt = start + 60_000L
            markerPress = 0L // der Druck stammt aus einem FRUEHEREN Prozess
        }
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        if (druecken) {
            // Erst gesunde Zyklen, DANN der Druck (vorgefundene pinnen nie).
            repeat(7) { cycle() }
            markerAt = clock + 60_000L
        }
        return adapter
    }

    /**
     * P1 v45, Positivfall: das markergebundene Rebound-Sonderrecht
     * entwaffnet auch das HARTE Liveness-Tor. Vorher blieb der Kanal in
     * genau dieser Lage jeden Zyklus `EXCLUDED/REBOUND_ACTIVE`, waehrend
     * der Normalpfad laengst entwaffnet und dann GUARD-geschlossen war -
     * die serielle Blockade des Livefalls (q1 172, r +4,8, 3,68 U freier
     * MEAL-Headroom, keine Abgabe). Die Mutation `reboundRaw ->
     * REBOUND_ACTIVE` (ohne Sonderrechtspruefung) macht diesen Test rot.
     */
    @Test
    fun `P1 - das Rebound-Sonderrecht entwaffnet auch das Liveness-Tor`(@TempDir dir: File) {
        val adapter = reboundOverrideLage(dir)
        var armZyklus: FuseCycleRunner.Outcome? = null
        var liftZyklus: FuseCycleRunner.Outcome? = null
        repeat(60) {
            val o = cycle()
            // Solange das Sonderrecht gilt, darf das Tor nie mit dem ROHEN
            // Fenster sperren - der Kern des Fixvertrags.
            if (o.evidenceMayOverrideRebound) assertTrue(
                o.livenessProfileReason != "REBOUND_ACTIVE",
                "Tor liest das Rohsignal statt des Sonderrechts (Zyklus $it)",
            )
            if (armZyklus == null && o.livenessActive) armZyklus = o
            if (liftZyklus == null && o.livenessLiftU > 0.0) liftZyklus = o
        }
        val arm = armZyklus ?: throw AssertionError("der Kanal MUSS unter dem Sonderrecht bewaffnen")
        assertTrue(arm.evidenceMayOverrideRebound, "die Bewaffnung steht auf dem Sonderrecht")
        assertTrue(
            (arm.state?.reboundRestMin ?: 0) > 0,
            "und zwar IM rohen Rebound-Fenster - sonst prueft der Fall nichts",
        )
        assertTrue(adapter.episodes.markerPowerPinnedFor > 0L, "der MEAL-Pin steht")
        val lift = liftZyklus ?: throw AssertionError("ein positiver Liveness-Lift MUSS entstehen")
        assertEquals(
            LivenessChannel.quantize(lift.livenessCandidateU, 0.05), lift.decision.smbU, 1e-9,
            "der Kanal-Kandidat traegt die Endmenge",
        )
        assertEquals("LIVENESS", lift.exposureRequestedSource, "typisierte Quelle der Endmenge")
    }

    /** Gegenproben 1+8: OHNE Marker (CORRECTION, kein Kredit, kein Pin)
     *  bleibt das Tor im ganzen rohen Fenster scharf - Druck hin oder her.
     *  Tagesschwelle hier 140, damit Druckzyklen INS Fenster fallen. */
    @Test
    fun `P1 - ohne Vollmacht bleibt das Rebound-Tor scharf`(@TempDir dir: File) {
        reboundOverrideLage(dir, druecken = true)
        markerAt = 0L // Druck zuruecknehmen, BEVOR ein Zyklus lief: kein Marker
        livenessBgMin = 140.0
        repeat(60) {
            val o = cycle()
            // NACH dem Fensterende ist Bewaffnung legitim - die Behauptung
            // gilt IM rohen Fenster.
            if ((o.state?.reboundRestMin ?: 0) > 0) {
                assertTrue(!o.livenessActive, "ohne Sonderrecht darf im Fenster nie bewaffnet werden")
                assertEquals(
                    "REBOUND_ACTIVE", o.livenessProfileReason,
                    "das rohe Fenster muss ohne Sonderrecht sperren (Zyklus $it)",
                )
            }
        }
    }

    /** Gegenprobe 3: ein VORGEFUNDENER Marker pinnt nie - kein Sonderrecht,
     *  das Tor bleibt scharf, der Power-Pin bleibt leer. */
    @Test
    fun `P1 - ein vorgefundener Marker oeffnet das Rebound-Tor nicht`(@TempDir dir: File) {
        val adapter = reboundOverrideLage(dir, druecken = false)
        repeat(60) {
            val o = cycle()
            assertTrue(!o.evidenceMayOverrideRebound, "vorgefunden erzeugt kein Sonderrecht")
            if ((o.state?.reboundRestMin ?: 0) > 0) {
                assertTrue(!o.livenessActive, "vorgefunden darf im Fenster nie bewaffnen")
                assertEquals(
                    "REBOUND_ACTIVE", o.livenessProfileReason,
                    "das rohe Fenster muss sperren (Zyklus $it)",
                )
            }
        }
        assertEquals(0L, adapter.episodes.markerPowerPinnedFor, "der Power-Pin bleibt leer")
    }

    /** Gegenprobe 2: nach ABLAUF der Sonderrechtsfrist sperrt das rohe
     *  Fenster wieder - auch wenn der Kredit weiter fliesst. Frist 12 min:
     *  der Druck (q1 > 120) entsteht erst NACH dem Ablauf. */
    @Test
    fun `P1 - nach Fristablauf sperrt das Rebound-Tor wieder`(@TempDir dir: File) {
        reboundOverrideLage(dir)
        reboundOverrideMaxMin = 15
        var sonderrechtGesehen = false
        var wiederGesperrt = false
        repeat(60) {
            val o = cycle()
            if (o.evidenceMayOverrideRebound) sonderrechtGesehen = true
            if (!o.evidenceMayOverrideRebound && (o.state?.reboundRestMin ?: 0) > 0 &&
                o.livenessProfileReason == "REBOUND_ACTIVE"
            ) wiederGesperrt = true
            // NACH dem Fensterende ist Bewaffnung legitim - die Behauptung
            // gilt IM rohen Fenster (dort ist das Sonderrecht abgelaufen).
            if ((o.state?.reboundRestMin ?: 0) > 0 && !o.evidenceMayOverrideRebound)
                assertTrue(!o.livenessActive, "nach Fristablauf darf im Fenster nie bewaffnet werden")
        }
        assertTrue(sonderrechtGesehen, "die Frist muss erst einmal GELTEN (sonst prueft der Fall nichts)")
        assertTrue(wiederGesperrt, "nach dem Ablauf muss das rohe Fenster wieder sperren")
    }

    /** Gegenprobe 4: die RUECKNAHME beendet das Sonderrecht sofort - der
     *  Kanal entwaffnet und das rohe Fenster sperrt wieder. */
    @Test
    fun `P1 - die Ruecknahme schliesst das Rebound-Tor sofort`(@TempDir dir: File) {
        reboundOverrideLage(dir)
        var armZyklus: FuseCycleRunner.Outcome? = null
        repeat(60) {
            if (armZyklus == null) {
                val o = cycle()
                if (o.livenessActive) armZyklus = o
            }
        }
        assertTrue(armZyklus != null, "die Lage muss erst bewaffnen (sonst prueft der Fall nichts)")
        markerAt = 0L // Ruecknahme
        val o = cycle()
        assertTrue(!o.livenessActive, "die Ruecknahme muss den Kanal sofort entwaffnen")
        assertTrue(!o.evidenceMayOverrideRebound, "das Sonderrecht endet mit der Ruecknahme")
        if ((o.state?.reboundRestMin ?: 0) > 0) assertEquals(
            "REBOUND_ACTIVE", o.livenessProfileReason,
            "das rohe Fenster sperrt wieder",
        )
    }

    /**
     * Sicherheitsproben 5+6: das Sonderrecht entwaffnet NUR das Rebound-Tor.
     * Faellt die Kurve nach der Bewaffnung steil, beenden die GEMESSENEN
     * Riegel (FALLING/DESCENT/LOW/LATCH/TURN) den Lauf trotz gueltigem
     * Sonderrecht - kein Zyklus im Fall traegt einen Liveness-Lift.
     * (MODEL_UNAVAILABLE/LEDGER_HOLD/SIGNAL/VIEW stehen in der when-Kette
     * VOR der Rebound-Zeile und sind damit strukturell unumgehbar; sie sind
     * aus diesem Rig nicht organisch ausloesbar - dieselbe dokumentierte
     * Grenze wie bei den Predictor-Ablehnungen.)
     */
    @Test
    fun `P1 - das Sonderrecht entwaffnet nur das Rebound-Tor, nicht die Gefahrenriegel`(@TempDir dir: File) {
        val adapter = reboundOverrideLage(dir)
        // Sanfter Fall VOR der Bewaffnungsschwelle 120 (Peak ~113): kein
        // Lift-Abzug verfaelscht den Topf, und die Messriegel zuenden im
        // Fall nacheinander (FALLING, dann Low-Naehe).
        knick2AbMin = 24
        steigungNachKnick2 = -0.8
        repeat(15) { cycle() }
        // Topf VORLADEN (etabliertes Rig-Idiom, wie der vorgeladene
        // Prime-Verbrauch): organisch stirbt der einnahmegespeiste Kredit
        // an der Wende FRUEHER, als die Messriegel zuenden - im Leben
        // traegt ein grosser Mahlzeiten-Topf den Kredit ueber die Wende
        // (Eis-Fall: Sonderrecht stand bei T+52 trotz 3,05 U Abzug). Der
        // Test braucht genau diesen Ueberlapp: Sonderrecht GILT, und die
        // Gefahrenriegel muessen trotzdem greifen.
        adapter.episodes.evidenceState = adapter.episodes.evidenceState.copy(stockMgdl = 60.0)
        val fallRiegel = setOf(
            "FALLING", "DESCENT_RISK", "DESCENT_RISK_MARKER", "MEASURED_LOW", "LATCH_ACTIVE",
        )
        var riegelTrotzSonderrecht = false
        repeat(45) {
            val o = cycle()
            if (o.evidenceMayOverrideRebound &&
                (o.livenessProfileReason in fallRiegel || o.livenessExit in fallRiegel ||
                    o.livenessExit == "TURN_EXIT")
            ) riegelTrotzSonderrecht = true
            if (o.livenessProfileReason in fallRiegel || o.livenessDenial in fallRiegel) assertTrue(
                o.livenessLiftU == 0.0,
                "unter einem Gefahrenriegel darf nie ein Lift entstehen (Zyklus $it)",
            )
        }
        assertTrue(
            riegelTrotzSonderrecht,
            "mindestens ein gemessener Riegel muss TROTZ gueltigem Sonderrecht greifen",
        )
    }

    /**
     * FLASH-RELEVANTE WECHSELWIRKUNG (Tonis Review 30.08., ausdruecklich
     * akzeptiert): mit v45 darf die Wiederbewaffnung nach einem manuellen
     * NORMAL-Bolus bereits WAEHREND des entwaffneten Rebound-Fensters
     * erfolgen, nicht erst nach dessen Ende - der Livefall 14:43 (4 U
     * manuell, kurze Sperre, 15:19 weitere 0,55 U bei 5,52 U Bolus-IOB).
     * Drei Zusagen in einem Lauf:
     *  1. innerhalb ReArmMin: MANUAL_INTERVENTION, kein Hub;
     *  2. nach ReArmMin: Bewaffnung grundsaetzlich erlaubt - im noch
     *     LAUFENDEN rohen Fenster (restMin > 0), unter geltendem
     *     Sonderrecht;
     *  3. das manuelle Bolus-IOB steht VOLLSTAENDIG im 7-U-MEAL-CAP:
     *     der freie Kanalraum ist hoechstens 7,0 - capIob.
     */
    @Test
    fun `P1 - Manualbolus im entwaffneten Fenster sperrt, dann traegt das CAP die Wiederbewaffnung`(@TempDir dir: File) {
        reboundOverrideLage(dir)
        var armZyklus: FuseCycleRunner.Outcome? = null
        var zyklen = 0
        while (zyklen < 45 && armZyklus == null) {
            val o = cycle(); zyklen++
            if (o.livenessActive) armZyklus = o
        }
        assertTrue(armZyklus != null, "die Lage muss erst bewaffnen (sonst prueft der Fall nichts)")
        // Der Nutzer uebernimmt: 4 U NORMAL (Livefall 14:43); der statische
        // Rig-IOB uebernimmt die Rolle des gewachsenen Bolus-IOB (5,5 wie
        // die 5,52 U des Livefalls). GEMESSEN (Rig-Debug): der Manualbolus
        // bucht NICHT in den Evidenz-Topf - er wirkt ueber IOB und
        // Deckungs-Abschlag; das Sonderrecht kann ihn daher ueberleben.
        boluses = listOf(BS(timestamp = clock, amount = 4.0, type = BS.Type.NORMAL))
        bolusIobU = 5.5
        // 1. Innerhalb ReArmMin: nie bewaffnet, kein Hub - und der manuelle
        // Riegel muss unter GELTENDEM Sonderrecht sichtbar greifen (nicht
        // vom Rebound-Riegel maskiert; genau das prueft, dass die
        // when-Kette den Manual-Check noch erreicht).
        var manualRiegelGesehen = false
        repeat(livenessReArmMin - 1) {
            val x = cycle()
            assertTrue(!x.livenessActive, "innerhalb ReArmMin darf nicht bewaffnet werden (Zyklus $it)")
            assertEquals(0.0, x.livenessLiftU, 1e-9, "innerhalb ReArmMin kein Hub (Zyklus $it)")
            if (x.evidenceMayOverrideRebound &&
                (x.livenessDenial == "MANUAL_INTERVENTION" || x.livenessExit == "MANUAL_INTERVENTION")
            ) manualRiegelGesehen = true
        }
        assertTrue(
            manualRiegelGesehen,
            "der manuelle Riegel muss unter geltendem Sonderrecht greifen",
        )
        // 2.+3. Nach ReArmMin: Wiederbewaffnung grundsaetzlich erlaubt -
        // IM noch laufenden rohen Fenster (die ausdruecklich akzeptierte
        // v45-Folge), und der freie Kanalraum traegt das manuelle
        // Bolus-IOB vollstaendig im 7-U-MEAL-CAP.
        var wieder: FuseCycleRunner.Outcome? = null
        repeat(14) { val x = cycle(); if (wieder == null && x.livenessActive) wieder = x }
        val w = wieder ?: throw AssertionError("nach ReArmMin muss die Bewaffnung grundsaetzlich erlaubt sein")
        assertTrue((w.state?.reboundRestMin ?: 0) > 0, "und zwar IM noch laufenden rohen Fenster")
        assertTrue(w.evidenceMayOverrideRebound, "unter geltendem Sonderrecht")
        val frei = w.livenessHeadroomU ?: throw AssertionError("der Kanalraum muss beziffert sein")
        assertTrue(
            frei <= 7.0 - 5.5 + 1e-6,
            "das manuelle Bolus-IOB steht vollstaendig im 7-U-CAP: frei $frei",
        )
        assertTrue(w.livenessLiftU <= frei + 1e-9, "kein Hub ueber den freien Raum")
    }

    // ---- P0 v46: Episodenstatistik bis zur MEAL-Deadline ------------------

    /**
     * Lage fuer den Fruehstuecks-P0 (31.08.): Vollmacht 120 min, Kanal
     * dosiert dauerhaft (statisches Rig-IOB, weite Grenzen), Serie steigt
     * endlos - Buchungen entstehen vor UND nach T+90 sowie nach T+120
     * (CORRECTION). Prime aus, damit der Verlauf deterministisch bleibt.
     */
    private fun mealStatsLage(dir: File): FuseLedgerAdapter {
        livenessAn = true
        corrExpLimit = 7.2; mealExpLimit = 7.2
        livenessBgMin = 160.0
        mealBgMin = 120.0
        mealArmZyklen = 1
        livenessReArmMin = 10
        tailGuard = true
        markerAuthorized = true
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        whenever(preferences.get(FuseLongKey.MealMarkerNoPrime)).thenReturn(1L)
        flach = 100.0
        steigungProMin = 0.3
        knickAbMin = 12
        steigungNachKnick = 1.2
        knick2AbMin = null
        bolusIobU = 4.5
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        repeat(7) { cycle() }
        markerAt = clock + 60_000L
        return adapter
    }

    /**
     * P0 v46 (Fruehstuecks-Livefall 31.08.): mealDeliveries buchte nur im
     * 90-min-Onset-Fenster, der MEAL-Kontext laeuft aber 120 min - die
     * 2,90 U zwischen T+90 und T+115 fehlten in der Episodensumme (8,80
     * statt 11,70). Jetzt bucht die Statistik im halb offenen Fenster bis
     * authorizationExpiresAt; ab der Deadline zaehlt CORRECTION. Die
     * Mutation (Buchung zurueck auf mealMarkerActive) macht genau diesen
     * Test rot.
     */
    @Test
    fun `P0 - die Episodensumme zaehlt bis zur MEAL-Deadline`(@TempDir dir: File) {
        mealStatsLage(dir)
        val t0 = markerAt
        // MUTATIONSSCHARFE Baender: die Basislinie faellt NACH dem Ende des
        // 90-min-Onset-Fensters (T+90,5) - eine Buchung bei T+89,x zaehlt in
        // BEIDEN Regimen und darf den Nachweis nicht tragen. Wachstum wird
        // erst ab T+92 verlangt, wo nur noch die Vollmacht bucht.
        var statsFrueh = -1.0; var statsEndeOnset = -1.0; var statsSpaet = -1.0; var statsNachDeadline = -1.0
        var pubBis89 = 0.0; var pub92bis119 = 0.0; var pubAb120 = 0.0
        repeat(140) {
            val o = cycle()
            val m = (o.computeTs - t0) / 60_000.0
            val pub = o.smbPublishedU ?: 0.0
            when {
                m < 0 -> Unit
                m <= 89 -> { pubBis89 += pub; o.mealStats?.let { statsFrueh = it.totalU } }
                m <= 90.5 -> o.mealStats?.let { statsEndeOnset = it.totalU }
                m < 120 -> { if (m >= 92) pub92bis119 += pub; o.mealStats?.let { statsSpaet = it.totalU } }
                m <= 132 -> { pubAb120 += pub; o.mealStats?.let { statsNachDeadline = it.totalU } }
            }
        }
        assertTrue(pubBis89 > 0 && statsFrueh > 0, "fruehe Buchungen muessen stehen: pub=$pubBis89 stats=$statsFrueh")
        assertTrue(pub92bis119 > 0, "die Lage muss zwischen T+92 und T+119 liefern (sonst prueft der Fall nichts)")
        assertTrue(
            statsSpaet > statsEndeOnset + 1e-9,
            "Abgaben nach dem Onset-Ende zaehlen in die Episodensumme (P0): $statsEndeOnset -> $statsSpaet",
        )
        assertTrue(pubAb120 > 0, "die Lage muss auch nach der Deadline liefern (CORRECTION-Gegenprobe)")
        assertEquals(statsSpaet, statsNachDeadline, 1e-9, "ab T+120 zaehlt CORRECTION - die Summe friert ein")
    }

    /** P0-Vertragsrest: Publikations-Verwurf entlastet die Summe, der
     *  Neustart verliert keine Buchung, ein neuer Marker trennt sauber. */
    @Test
    fun `P0 - Rollback, Neustart und neuer Marker halten die Summe ehrlich`(@TempDir dir: File) {
        val adapter = mealStatsLage(dir)
        val t0 = markerAt
        var spaet: FuseCycleRunner.Outcome? = null
        repeat(140) {
            if (spaet == null) {
                val o = cycle()
                val m = (o.computeTs - t0) / 60_000.0
                if (m in 91.0..118.0 && (o.smbPublishedU ?: 0.0) > 0) spaet = o
            }
        }
        val o = spaet ?: throw AssertionError("die Lage muss eine spaete Buchung erzeugen")
        val vorher = o.mealStats!!.totalU
        // 5. Publikations-Gate verwirft die Abgabe dieses Zyklus vollstaendig.
        adapter.resolveReservation(o.computeTs, 0.0, "p0-test")
        val nachRollback = FuseCycleRunner.mealStatsOf(adapter.episodes, t0, o.computeTs)!!.totalU
        assertEquals(vorher - (o.smbPublishedU ?: 0.0), nachRollback, 1e-9, "Verwurf entlastet auch die spaete Buchung")
        // 6. Neustart verliert keine gebuchte Abgabe.
        assertTrue(adapter.persistVerified(dir), "der Zustand muss versiegelt sein")
        assertEquals(
            nachRollback,
            FuseCycleRunner.mealStatsOf(nachNeustart(dir), t0, o.computeTs)!!.totalU, 1e-9,
            "die Ledger-Datei traegt die Buchungen restartfest",
        )
        // 4. Ein neuer Marker trennt die Episoden sauber.
        markerAt = clock + 60_000L
        var statsNeu = Double.MAX_VALUE
        repeat(4) { cycle().mealStats?.let { statsNeu = it.totalU } }
        assertTrue(
            statsNeu < nachRollback && statsNeu < 1.5,
            "neue Episode beginnt mit frischer Summe: $statsNeu (alt $nachRollback)",
        )
    }

    // ---- Schritt B v47: Basalluecken-Latch --------------------------------

    /**
     * Schritt B (31.08.): der beobachtete Markerdruck friert die
     * Basalluecken-Lage EINMALIG ein (Pin-Identitaet), restartfest; ein
     * neuer Druck ueberschreibt. Im Rig laeuft keine TBR - zeroTbrActive
     * false, Alter/Menge typisiert null (die Nullphasen-Rechnung selbst
     * beweist BasalGapRechnerTest).
     */
    @Test
    fun `B - der Markerdruck friert die Basalluecken-Lage einmalig ein`(@TempDir dir: File) {
        val adapter = mealStatsLage(dir)
        val press = cycle()
        val latch = adapter.episodes.basalGap ?: throw AssertionError("der Druck muss latchen")
        assertEquals(markerAt, latch.pinnedFor, "Pin-Identitaet des Drucks")
        assertEquals(press.state!!.basalIobU, latch.preMarkerBasalIobU, 1e-9, "dasselbe Basal-IOB wie der Zyklus")
        assertEquals(false, latch.zeroTbrActive, "im Rig laeuft keine TBR")
        assertEquals(null, latch.zeroTbrAgeMin)
        assertEquals(null, latch.omittedBasalU)
        assertTrue(latch.scheduledBasalUph >= 0.0)
        assertEquals(false, press.currentZeroTbrActive, "zyklusaktueller Nullstatus exportiert")
        // Latch-once: spaetere Zyklen (auch mit anderem IOB) aendern nichts.
        bolusIobU = 6.0
        repeat(5) { cycle() }
        assertEquals(latch, adapter.episodes.basalGap, "einmal gelatcht, nie nachgezogen")
        // Restartfest ueber die Ledger-Datei.
        assertTrue(adapter.persistVerified(dir), "der Zustand muss versiegelt sein")
        assertEquals(latch, nachNeustart(dir).basalGap, "der Latch ueberlebt den Neustart")
        // Ein neuer beobachteter Druck erzeugt einen neuen Kontext.
        markerAt = clock + 60_000L
        cycle()
        assertEquals(markerAt, adapter.episodes.basalGap!!.pinnedFor, "neuer Marker, neuer Latch")
    }

    /**
     * Schritt-B-Vertrag: der Latch ist DOSIERUNGSNEUTRAL - nie Headroom,
     * kein Auto-Bolus, kein Budget aus rueckwaerts laufendem Basal-IOB.
     * Zwillingslauf: identische Lage, einmal mit absurdem vorgesetztem
     * Latch - jede Entscheidung muss identisch bleiben.
     */
    @Test
    fun `B - der Basalluecken-Latch ist dosierungsneutral`(@TempDir dir: File) {
        fun lauf(name: String, praeparieren: (FuseLedgerAdapter) -> Unit): List<Pair<Double, String>> {
            val adapter = mealStatsLage(File(dir, name))
            markerAt = 0L // Latch-Variable isolieren: kein Druck in diesem Lauf
            praeparieren(adapter)
            return (0 until 45).map { val o = cycle(); o.decision.smbU to o.decision.block.name }
        }
        val basis = lauf("a") {}
        val mitLatch = lauf("b") {
            it.episodes.basalGap = EpisodeBudgets.BasalGapLatch(
                pinnedFor = 1L, preMarkerBasalIobU = -5.0, zeroTbrActive = true,
                zeroTbrAgeMin = 240, scheduledBasalUph = 1.0, omittedBasalU = 9.9,
            )
        }
        assertEquals(basis, mitLatch, "kein Dosierpfad darf den Latch lesen")
    }

    /** B1-Aufbau: zentrale Profile aktiv, offener Normalpfad (kein Guard-
     *  Deadlock, kein Tail, keine Liveness) - die Endmenge kommt aus der
     *  normalen Ratio und trifft NUR auf die neue Kontextgrenze. */
    private fun b1Lage(dir: File, corrLimit: Double) {
        corrExpLimit = corrLimit
        mealExpLimit = 6.0
        corrRatioCapZ = 1.0
        mealRatioCapZ = 1.0
        livenessAn = false
        tailGuard = false
        markerAuthorized = false
        flach = 150.0
        steigungProMin = 0.3
        knickAbMin = 12
        steigungNachKnick = 1.2
        knick2AbMin = null
        bolusIobU = 1.6
        clock = start
        markerAt = 0L
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
    }

    /**
     * B1, PFLICHTFALL 2 (Bauauftrag Paragraph 10, Zeile 2): der neue
     * Correction-Cap kann auch die NORMALE Endmenge begrenzen - genau die
     * Verhaltensaenderung, die der 27.08.-Burst verlangt hat (2,50 U in 12
     * Zyklen, waehrend iobTH/maxIOB 4-6 U Luft liessen). Die Mutation
     * "Endgrenze entfernt" macht exakt diesen Test rot.
     */
    @Test
    fun `B1 - der Correction-Cap begrenzt die normale Endmenge`(@TempDir dir: File) {
        // 1,8 - capIob(1,6) = 0,2 U Raum; die Korrektur-Ratio wollte ~0,25+.
        b1Lage(dir, corrLimit = 1.8)
        var gebunden = false
        repeat(40) {
            val o = cycle()
            if (o.exposureGateBindet == true && o.decision.smbU > 0.0) {
                gebunden = true
                assertEquals("correctionExposureLimit", o.exposureGateBinding)
                assertEquals("correctionExposureLimit", o.decision.bindingLimit)
            }
            // KEIN Zyklus darf den Raum reissen; die Transporthaftung
            // verengt nur weiter.
            assertTrue(
                o.decision.smbU <= 0.2 + 1e-9,
                "Endmenge ${o.decision.smbU} muss unter dem Kontext-Headroom bleiben",
            )
        }
        assertTrue(gebunden, "die Kontextgrenze MUSS mindestens einmal real binden")
    }

    /** B1: erschoepfter Raum -> Block EXPOSURE_LIMIT, Menge 0, TBR-Antwort
     *  NO_NEW_POSITIVE (Invariante 7: kein eigenstaendiges Zero). */
    @Test
    fun `B1 - erschoepfter Raum setzt EXPOSURE_LIMIT ohne Zero`(@TempDir dir: File) {
        b1Lage(dir, corrLimit = 1.5)
        var geblockt = false
        repeat(40) {
            val o = cycle()
            if (o.exposureGateBlocked == true) {
                geblockt = true
                assertEquals(FuseController.Block.EXPOSURE_LIMIT, o.decision.block)
                assertEquals(0.0, o.decision.smbU, 1e-12)
                assertTrue(
                    o.decision.tbr != FuseController.TbrAction.ZERO_TEMP,
                    "ein erschoepfter Raum erzeugt nie eigenstaendig Zero-TBR",
                )
            }
        }
        assertTrue(geblockt, "die Lage muss den Vollblock erreichen")
    }

    /** B1: unter MEAL-Vollmacht gilt die MEAL-Grenze - dieselbe knappe
     *  Correction-Grenze bindet dann nicht. */
    @Test
    fun `B1 - unter Vollmacht gilt die MEAL-Grenze im Gate`(@TempDir dir: File) {
        b1Lage(dir, corrLimit = 1.5)
        markerAuthorized = true
        repeat(7) { cycle() }
        markerAt = clock + 60_000L
        var mealGeprueft = false
        repeat(30) {
            val o = cycle()
            if (o.dosingContextProfile == "MEAL" && o.exposureGateBindet != null) {
                mealGeprueft = true
                assertTrue(
                    o.decision.block != FuseController.Block.EXPOSURE_LIMIT,
                    "unter der Vollmacht darf die knappe CORRECTION-Grenze nicht blocken",
                )
                if (o.exposureGateBindet == true) assertTrue(
                    o.exposureGateBinding != "correctionExposureLimit",
                    "im MEAL-Profil bindet nie die CORRECTION-Grenze",
                )
            }
        }
        assertTrue(mealGeprueft, "die Vollmacht muss das Gate im MEAL-Profil erreichen")
    }

    /** B1: die Kontextgrenze steht bereits in der GRANT-BILDUNG - ein
     *  4-U-Sofortanteil wird an einer knappen MEAL-Grenze schon bei der
     *  Anforderung gekappt, der Rest bleibt in der Bilanz ABRECHENBAR
     *  offen (verschieben, nie verwerfen). */
    @Test
    fun `B1 - der Grant entsteht nie oberhalb des Raums und der Rest bleibt offen`(@TempDir dir: File) {
        corrExpLimit = 2.0
        mealExpLimit = 2.5
        corrRatioCapZ = 1.0
        mealRatioCapZ = 1.0
        livenessAn = false
        tailGuard = false
        fundamentAn = true
        fundamentAnteil = 0.8
        upfrontAnteil = 1.0
        primeHuelleU = 5.0
        aufschubAn = true
        markerAuthorized = true
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        flach = 110.0
        steigungProMin = 0.4
        bolusIobU = 0.4
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        repeat(7) { cycle() }
        markerAt = clock + 60_000L
        var markerZyklus: FuseCycleRunner.Outcome? = null
        repeat(12) {
            val o = cycle()
            if (markerZyklus == null && o.phaseAUpfrontRequestedU > 0.0) markerZyklus = o
            // KEINE Anforderung darf den Raum reissen: 2,5 - capIob(0,4)
            // = 2,1 U - die Grenze steht schon in der GRANT-BILDUNG.
            assertTrue(
                o.phaseAUpfrontRequestedU <= 2.1 + 1e-9,
                "Anforderung ${o.phaseAUpfrontRequestedU} muss im Raum bleiben",
            )
        }
        assertTrue(markerZyklus != null, "der Sofortanteil muss angefordert werden")
        // Und die Kappe hat REAL gegriffen: vom 4,0-U-Plan (5,0 x 0,8 x 1,0)
        // wurde nur der Raum angefordert - der Rest bleibt in der Bilanz
        // abrechenbar offen (buche laeuft auf actuatedU, exactly-once).
        assertTrue(
            markerZyklus!!.phaseAUpfrontRequestedU < 4.0 - 1e-9,
            "die Grant-Kappung muss real greifen",
        )
    }

    /**
     * B2 (Bauauftrag 7.2): der Kontext-Cap begrenzt die BEDARFSRATE des
     * Normalpfads - effectiveDemandRatio = min(Basis, Cap) als eigener
     * benannter Kandidat der Kappenliste. Die Mutation "Cap-Kandidat
     * entfernt" macht exakt diesen Test rot.
     */
    @Test
    fun `B2 - der Correction-Cap begrenzt die normale Bedarfsrate`(@TempDir dir: File) {
        b1Lage(dir, corrLimit = 6.0)
        corrRatioCapZ = 0.1
        var gebunden = false
        repeat(40) {
            val o = cycle()
            if (o.decision.bindingLimit.startsWith("demandRatioCap") && o.decision.smbU > 0.0) {
                gebunden = true
                // Der Cap ist eine RATE: hoechstens insulinReq x 0,1 je
                // Zyklus (plus Abwaertsrundung, nie darueber).
                assertTrue(
                    o.decision.smbU <= o.decision.insulinReqU!! * 0.1 + 1e-9,
                    "Endmenge ${o.decision.smbU} muss unter req*Cap bleiben",
                )
            }
        }
        assertTrue(gebunden, "der Kontext-Cap MUSS mindestens einmal real binden")
    }

    /**
     * B2, INVARIANTE 5: Ratio-Caps deuten autorisierte Direktdosen NIE zu
     * normaler Bedarfsdosierung um. Derselbe 4-U-Sofortanteil wie im
     * B1-Grant-Test, aber mit winzigem MEAL-Ratio-Cap 0,05 und weitem
     * Exposure-Raum: die Anforderung bleibt die Direktdosis, nicht
     * req x 0,05.
     */
    @Test
    fun `B2 - der Sofortanteil bleibt eine Direktdosis trotz winzigem Cap`(@TempDir dir: File) {
        corrExpLimit = 6.0
        mealExpLimit = 8.0
        corrRatioCapZ = 0.05
        mealRatioCapZ = 0.05
        livenessAn = false
        tailGuard = false
        fundamentAn = true
        fundamentAnteil = 0.8
        upfrontAnteil = 1.0
        primeHuelleU = 5.0
        aufschubAn = true
        markerAuthorized = true
        whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(20)
        flach = 110.0
        steigungProMin = 0.4
        bolusIobU = 0.4
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        repeat(7) { cycle() }
        markerAt = clock + 60_000L
        var markerZyklus: FuseCycleRunner.Outcome? = null
        repeat(12) {
            val o = cycle()
            if (markerZyklus == null && o.phaseAUpfrontRequestedU > 0.0) markerZyklus = o
        }
        assertTrue(markerZyklus != null, "der Sofortanteil muss angefordert werden")
        // Der Plan ist 4,0 U (5,0 x 0,8 x 1,0; s. B1-Grant-Test).
        // Eine Umdeutung in Bedarfsdosierung ergaebe req x 0,05 (<< 1 U).
        // >= 3,0 statt == 4,0: tolerant gegen eine moegliche
        // SafetyMaxBolus-Kante, beweiskraeftig gegen die Umdeutung.
        assertTrue(
            markerZyklus!!.phaseAUpfrontRequestedU >= 3.0 - 1e-9,
            "Direktdosis ${markerZyklus!!.phaseAUpfrontRequestedU} darf nicht ratio-gedeutet werden",
        )
    }

    /**
     * REVIEW-REGRESSION (30.08.): Tonis Ziel-Konstellation end-to-end -
     * Marker autorisiert, HUELLE 0, Fundament AUS. Das Profil ist trotzdem
     * MEAL (die Vollmacht haengt am beobachteten Marker, nicht an einer
     * Direktdosis), und bei Druck entsteht ein REAKTIVER Kandidat aus dem
     * Liveness-Kanal - die Mahlzeit ist ohne jede Huelle versorgbar.
     * Ohne Huelle darf dabei KEINE Direktdosis angefordert werden.
     */
    @Test
    fun `marker mit huelle null und fundament aus traegt MEAL und reagiert auf druck`(@TempDir dir: File) {
        livenessLage(dir)
        fundamentAn = false
        aufschubAn = false
        primeHuelleU = 0.0
        markerAuthorized = true
        mealBgMin = 110.0
        mealArmZyklen = 1
        repeat(7) { cycle() } // gesunde Zyklen - erst dann pinnt ein Druck
        markerAt = clock + 60_000L
        var meal: FuseCycleRunner.Outcome? = null
        var reaktiv: FuseCycleRunner.Outcome? = null
        repeat(30) {
            val o = cycle()
            if (meal == null && o.dosingContextProfile == "MEAL") meal = o
            if (reaktiv == null && o.livenessLiftU > 0.0) reaktiv = o
            assertEquals(0.0, o.phaseAUpfrontRequestedU, 1e-9, "ohne Huelle keine Direktdosis")
        }
        assertTrue(meal != null, "die Vollmacht muss das MEAL-Profil tragen - auch ohne Huelle")
        val r = reaktiv ?: error("bei Druck muss ein reaktiver Kandidat entstehen")
        assertEquals("MEAL", r.dosingContextProfile)
        assertTrue(r.decision.smbU > 0.0, "der Kandidat wird dosierbar")
    }

    /** Die Lauf-Kennung folgt den WIRKSAMEN Profilwerten: eine Aenderung
     *  des Exposure-Limits waehrend eines Laufs ist eine Bedienhandlung
     *  und beendet ihn (CONFIG_CHANGED, ohne Sperre). */
    @Test
    fun `die Kennung folgt den wirksamen Profilwerten`(@TempDir dir: File) {
        livenessLage(dir)
        corrExpLimit = 8.0
        mealExpLimit = 8.0
        var aktiv = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktiv = true }
        assertTrue(aktiv, "der Lauf muss stehen")
        corrExpLimit = 7.5 // WIRKSAM
        val o2 = cycle()
        assertEquals("CONFIG_CHANGED", o2.livenessExit, "der wirksame Wert beendet den Lauf")
    }

    /**
     * Punkt-2-/Status-Fix (Review 29.08. spaet): Anforderung, Kappung und
     * finale Quelle sind GETRENNTE Wahrheiten. Im Liefermoment ist der
     * Status FREE und final==angefordert; im Vollblock ist der Status
     * STOP/EXPOSURE, die finale Quelle NONE - waehrend die ABSICHT
     * (requestedSource) und die angeforderte Menge sichtbar bleiben.
     */
    @Test
    fun `Status - Anforderung, Kappung und finale Quelle sind getrennt`(@TempDir dir: File) {
        // Zwei Teil-Lagen: die 2,0er liefert (FREE), die 1,5er ist von
        // Beginn an erschoepft (capIob 1,6) und erreicht den Vollblock.
        b1Lage(File(dir, "frei"), corrLimit = 2.0)
        var frei: FuseCycleRunner.Outcome? = null
        repeat(40) {
            val o = cycle()
            if (frei == null && o.decision.smbU > 0.0) frei = o
        }
        b1Lage(File(dir, "stop"), corrLimit = 1.5)
        var stop: FuseCycleRunner.Outcome? = null
        repeat(40) {
            val o = cycle()
            if (stop == null && o.exposureGateBlocked == true) stop = o
        }
        val f = frei ?: error("die Lage muss liefern")
        assertEquals("FREE", f.smbState)
        assertEquals(null, f.smbStopReason)
        assertEquals(f.exposureRequestedSource, f.exposureFinalSource, "im Liefermoment ist final = angefordert")
        assertEquals(f.decision.smbU, f.smbPublishedU!!, 1e-12)
        val s = stop ?: error("die Lage muss den Vollblock erreichen")
        assertEquals("STOP", s.smbState)
        assertEquals("EXPOSURE", s.smbStopReason)
        assertEquals("NONE", s.exposureFinalSource, "final 0 U heisst finale Quelle NONE")
        assertTrue(
            s.exposureRequestedSource!!.startsWith("NORMAL"),
            "die Absicht bleibt sichtbar: ${s.exposureRequestedSource}",
        )
        assertTrue(s.smbRequestedU!! > 0.0, "die angeforderte Menge bleibt sichtbar")
        assertEquals(0.0, s.smbCappedU!!, 1e-12)
        assertEquals(0.0, s.smbPublishedU!!, 1e-12)
    }

    /** Tonis Auflage: ein ruhiger Zielverlauf ist NO_DEMAND - kein Stop,
     *  kein Grund, keine Stoerung. */
    @Test
    fun `Status - ohne Bedarf ist die Lage NO_DEMAND`(@TempDir dir: File) {
        b1Lage(dir, corrLimit = 6.0)
        flach = 100.0
        steigungProMin = 0.0
        knickAbMin = 999
        var ruhig: FuseCycleRunner.Outcome? = null
        repeat(30) { val o = cycle(); if (o.smbState == "NO_DEMAND") ruhig = o }
        assertTrue(ruhig != null, "die flache Ziellage muss NO_DEMAND erreichen")
        assertEquals(null, ruhig!!.smbStopReason)
    }

    /**
     * Bauauftrag Paragraph 10, Pflichtfall "spaeterer Wiederherstellungs-
     * pfad": auch die CALM_BATCH-Freigabe steht unter der gemeinsamen
     * Endgrenze. Dieselbe Lage wie der CALM_BATCH-Basistest - einmal
     * LEGACY (Referenz: voller Batch), einmal zentral mit knapper
     * Kontextgrenze: der Batch wird gekappt, der Rest bleibt SOFORT in
     * der Bilanz abrechenbar offen, und kein Zyklus der neuen Mengenregel
     * erzeugt Zero-TBR. Der Marker ist hier VORGEFUNDEN (mahlzeitMitRuhe
     * setzt ihn vor dem ersten Zyklus) und pinnt nach A1 nie - der
     * Kontext ist CORRECTION, die 1,6er-Grenze die wirksame.
     */
    @Test
    fun `B - der Wiederherstellungspfad steht unter der gemeinsamen Endgrenze`(@TempDir dir: File) {
        fun ruheBatch(eng: Boolean, unterDir: String): List<FuseCycleRunner.Outcome> {
            // Referenz = OFFENE Grenzen (der Batch passt vollstaendig),
            // Kandidat = enge Kontextgrenze 1,6/2,0.
            corrExpLimit = if (eng) 1.6 else 20.0
            mealExpLimit = if (eng) 2.0 else 20.0
            corrRatioCapZ = 1.0
            mealRatioCapZ = 1.0
            return ruheLauf(
                File(dir, unterDir), app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.CALM_BATCH,
                zyklen = 45, abstiegBg = 82.0, abstiegRate = -0.5, abstiegIob = 2.0,
                wendeZyklus = 6, ruheRate = 0.10, ruheIob = 0.3,
            )
        }
        val frei = ruheBatch(false, "offen")
        val anfFrei = frei.withIndex().filter { it.value.phaseAUpfrontRequestedU > 0.0 }
        assertEquals(1, anfFrei.size, "die Referenz muss den Batch genau einmal anfordern")
        val batchU = anfFrei.single().value.phaseAUpfrontRequestedU
        assertTrue(batchU > 1.4, "die Referenz muss OBERHALB der knappen Grenze liegen ($batchU)")

        val eng = ruheBatch(true, "eng")
        val anfEng = eng.withIndex().filter { it.value.phaseAUpfrontRequestedU > 0.0 }
        assertTrue(anfEng.isNotEmpty(), "auch der enge Lauf muss den Batch anfordern")
        val (iAnf, anf) = anfEng.first()
        // Die Endgrenze ist verbindlich - ob sie in der Grant-Bildung
        // (AuthorizedLift) oder am Gate greift: die Anforderung liegt
        // STRIKT unter der Referenz und im Raum (1,6 - capIob 0,3 = 1,3).
        assertTrue(
            anf.phaseAUpfrontRequestedU < batchU - 1e-9,
            "die Grenze muss den Batch real kappen: ${anf.phaseAUpfrontRequestedU} vs $batchU",
        )
        eng.forEach { o ->
            assertTrue(
                o.decision.smbU <= 1.3 + 1e-9,
                "keine Endmenge darf den Kontext-Raum reissen: ${o.decision.smbU}",
            )
            if (o.decision.block == FuseController.Block.EXPOSURE_LIMIT) assertTrue(
                o.decision.tbr != FuseController.TbrAction.ZERO_TEMP,
                "die neue Mengenregel erzeugt nie eigenstaendig Zero-TBR",
            )
        }
        // VERSCHIEBEN, NIE VERWERFEN: der nicht angeforderte Rest steht im
        // Folgezyklus weiter als offener Sofortanteil in der Bilanz.
        assertTrue(
            eng[iAnf + 1].phaseAUpfrontPendingU > 0.0,
            "der gekappte Rest muss abrechenbar offen bleiben",
        )
    }

    /**
     * Fall 1 - die 22.08.-Tagesform: im Schwanz-Deadlock liefert der Kanal
     * die MENGENLINIE, nicht den Saegezahn. Scharf gegen die Mutation
     * "Tail-Kappe versehentlich noch aktiv": in jedem Hub-Zyklus ist die
     * Endmenge der rasterisierte Kanal-Kandidat - eine noch wirkende
     * Schwanzkappe koennte das nicht liefern. Die AUS-Kontrolle beweist
     * zugleich, dass der Default dosierneutral ist und der Aufbau den
     * Deadlock wirklich erreicht.
     */
    @Test
    fun `Liveness Fall 1 - im Tail-Deadlock liefert der Kanal die Mengenlinie statt des Saegezahns`(@TempDir dir: File) {
        livenessLage(dir)
        var summeAn = 0.0
        var liftZyklen = 0
        var ersterLiftMin = -1
        repeat(40) { i ->
            val o = cycle()
            if (o.livenessLiftU > 0.0) {
                liftZyklen++
                if (ersterLiftMin < 0) ersterLiftMin = i + 1
                assertEquals(FuseController.Block.NONE, o.decision.block)
                assertTrue(o.decision.bindingLimit.startsWith("liveness:"), o.decision.bindingLimit)
                assertEquals(
                    LivenessChannel.quantize(o.livenessCandidateU, 0.05), o.decision.smbU, 1e-9,
                    "die Endmenge ist der rasterisierte Kanal-Kandidat, kein Tail-Rest",
                )
                // Codex 22.08. spaet: der rohe Bedarf MUSS im Hub-Zyklus
                // exportiert sein - er ist die Zahl, die der Viewer an der
                // Bedarf-Stelle zeigt, wenn der Normalpfad null meldet.
                assertTrue(
                    (o.livenessNeedU ?: -1.0) > 0.0 && (o.livenessReleaseMeanMgdl ?: 0.0) > 0.0,
                    "needU/releaseMean fehlen im Hub-Zyklus: ${o.livenessNeedU}/${o.livenessReleaseMeanMgdl}",
                )
                assertTrue(
                    (o.livenessHeadroomU ?: -1.0) > 0.0,
                    "der Deckelrest muss im aktiven Lauf exportiert sein: ${o.livenessHeadroomU}",
                )
            }
            summeAn += o.decision.smbU
        }
        assertTrue(liftZyklen >= 8, "der Kanal muss den Deadlock tragen: $liftZyklen Hub-Zyklen")
        assertTrue(ersterLiftMin in 5..22, "Latenz-Auflage: erster Hub bei Minute $ersterLiftMin")

        // Die AUS-Kontrolle: dieselbe Lage ist ohne Schalter der Deadlock.
        livenessAn = false
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(File(dir, "aus").also(File::mkdirs), "test-epoch", start) })
        var summeAus = 0.0
        var tailGesehen = false
        repeat(40) {
            val o = cycle()
            summeAus += o.decision.smbU
            if (o.decision.block == FuseController.Block.TAIL) tailGesehen = true
            if (o.abortReason == null) assertEquals("DISABLED", o.livenessDenial)
            assertEquals(0.0, o.livenessLiftU, 1e-9)
            assertEquals(null, o.livenessNeedU, "AUS: die Bedarfsrechnung lief nicht - null, nicht 0")
        }
        assertTrue(tailGesehen, "auch der AUS-Lauf muss den Deadlock erreichen - sonst beweist die Differenz nichts")
        assertTrue(
            summeAn - summeAus >= 1.0,
            "der Kanal muss gegen AUS mengenwirksam sein: an=$summeAn aus=$summeAus",
        )
    }

    /**
     * Fall 2 - `max`, NIE Addition. Nach dem Deadlock faellt die Haftung,
     * der Normalpfad rampt per Sub-Step wieder hoch (der Saegezahn),
     * waehrend der bewaffnete Kanal darueber steht. In jedem Hub-Zyklus
     * ist die Endmenge der Kanalwert - eine Addition laege um den
     * Normalanteil darueber und riesse sowohl die Gleichheit als auch die
     * maxSMB-Grenze. Der Koexistenz-Zaehler erzwingt, dass die
     * Verknuepfung wirklich geprueft wurde (Normalanteil > 0 im Hub).
     */
    @Test
    fun `Liveness Fall 2 - max statt Addition wenn der Normalpfad wieder liefert`(@TempDir dir: File) {
        livenessLage(dir)
        var aktivGesehen = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktivGesehen = true }
        assertTrue(aktivGesehen, "der Kanal muss sich im Deadlock bewaffnen")
        // Die Haftung faellt in den UEBERGANGSBEREICH (4,5 -> 3,2): Guard
        // und Schwanz rationieren den Normalpfad auf kleine Mengen (der
        // Saegezahn), waehrend der Kanal-Kandidat darueber steht. Beide
        // Nachbarlagen waeren der falsche Aufbau: bei 4,1 bleibt der
        // Normalpfad komplett genullt (lift == Endmenge, Koexistenz nie
        // geprueft), bei 1,4 liefert er sofort voll und NORMAL_COVERS
        // greift - beides im Rig gesehen.
        bolusIobU = 3.2
        var koexistenzZyklen = 0
        repeat(14) {
            val o = cycle()
            assertTrue(o.decision.smbU <= maxSmbU + 1e-9, "nie ueber maxSMB: ${o.decision.smbU}")
            if (o.livenessLiftU > 0.0) {
                assertEquals(
                    LivenessChannel.quantize(o.livenessCandidateU, 0.05), o.decision.smbU, 1e-9,
                    "die Endmenge ist der Kanalwert - eine Addition laege darueber",
                )
                if (o.livenessLiftU < o.decision.smbU - 1e-9) koexistenzZyklen++
            }
        }
        assertTrue(
            koexistenzZyklen >= 1,
            "mindestens ein Zyklus mit Normalpfad UND Kanalhub - sonst prueft der Test die Verknuepfung nicht",
        )
    }

    /**
     * Fall 3 - der P0-Deckelvertrag: der STRENGSTE der drei Deckel bindet
     * und ist BENANNT. Erst bindet das global abgesenkte iobTH (50 % von
     * 8 U bei 3,9 U Haftung -> Rest 0,10 U), dann - zurueck auf 100 % -
     * der eigene Kanaldeckel mit denselben Zahlen. Ein Kanal, der das
     * globale iobTH ignoriert, faellt hier sofort um.
     */
    @Test
    fun `Liveness Fall 3 - der strengste Deckel bindet und ist benannt`(@TempDir dir: File) {
        livenessLage(dir)
        bolusIobU = 3.9
        iobThPct = 50
        corrExpLimit = 7.2; mealExpLimit = 7.2 // frueher 90 % x maxIOB 8
        var bindung = ""
        var menge = -1.0
        repeat(25) { val o = cycle(); if (o.livenessLiftU > 0.0) { bindung = o.decision.bindingLimit; menge = o.decision.smbU } }
        assertEquals("liveness:globalIobTh", bindung, "das globale iobTH MUSS den Kanal binden")
        assertTrue(menge in 0.05..0.10 + 1e-9, "und die Menge traegt die Grenze: $menge U")

        iobThPct = 100
        corrExpLimit = 4.0; mealExpLimit = 4.0 // frueher 50 % x maxIOB 8
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(File(dir, "deckel").also(File::mkdirs), "test-epoch", start) })
        bindung = ""
        menge = -1.0
        repeat(25) { val o = cycle(); if (o.livenessLiftU > 0.0) { bindung = o.decision.bindingLimit; menge = o.decision.smbU } }
        assertEquals("liveness:correctionExposureLimit", bindung, "der eigene Kanaldeckel MUSS benannt binden")
        assertTrue(menge in 0.05..0.10 + 1e-9, "und die Menge traegt die Grenze: $menge U")
    }

    /**
     * Fall 4 - der P2-Exit und die 21.08.-Gegenform: bei Minute 26 knickt
     * der Drive nach unten. Die BESTAETIGTE Wende (declineStreak >= 2)
     * muss den Lauf beenden, BEVOR Druckverlust oder fallender UKF greifen
     * - der gemessene Drive reagiert vor den traegen Filtern. In die
     * anschliessende Abwaertsform liefert der Kanal nichts mehr. Ohne den
     * P2-Exit stuende hier ein ANDERER Exitgrund, und der Test faellt um.
     */
    @Test
    fun `Liveness Fall 4 - die bestaetigte Wende beendet den Lauf und die Abwaertsform bleibt unversorgt`(@TempDir dir: File) {
        livenessLage(dir)
        knick2AbMin = 26
        steigungNachKnick2 = -1.5
        var exitGrund: String? = null
        var exitMin = -1
        var liftVorKnick = 0
        var liftNachExit = 0
        repeat(50) { i ->
            val minute = i + 1
            val o = cycle()
            if (minute <= 26 && o.livenessLiftU > 0.0) liftVorKnick++
            if (exitMin > 0 && o.livenessLiftU > 0.0) liftNachExit++
            if (exitMin < 0 && o.livenessExit != null && o.livenessExit != "PRESSURE_GONE") {
                exitGrund = o.livenessExit
                exitMin = minute
            }
        }
        assertTrue(liftVorKnick >= 3, "vor der Wende muss der Kanal geliefert haben: $liftVorKnick")
        assertEquals("TURN_EXIT", exitGrund, "die BESTAETIGTE Wende (P2) beendet den Lauf - nicht erst ein traegerer Riegel")
        assertTrue(exitMin in 27..34, "der Exit gehoert kurz hinter den Knick: Minute $exitMin")
        assertEquals(0, liftNachExit, "nach dem Exit versorgt der Kanal die Abwaertsform nicht")
    }

    /**
     * Fall 5 - die Re-Arm-Sperre ueberlebt den Neustart. Der Lauf endet
     * durch MANUELLE INTERVENTION (NORMAL-Bolus nach der Bewaffnung -
     * damit ist auch dieser Vertragspunkt belegt), die Sperre steht
     * restartfest in der Ledger-Datei. Nach dem Neustart haelt der Druck
     * an, aber innerhalb der Sperre wird NICHT bewaffnet - erst nach
     * Ablauf. Ein Codec, der das Feld verliert, bewaffnet sofort wieder
     * und faellt an REARM_BLOCKED um.
     */
    @Test
    fun `Liveness Fall 5 - die Re-Arm-Sperre ueberlebt den Neustart`(@TempDir dir: File) {
        val adapter = livenessLage(dir)
        var armTs = 0L
        repeat(24) { val o = cycle(); if (o.livenessActive) armTs = o.computeTs }
        assertTrue(armTs > 0L, "der Lauf muss stehen")
        // Der Nutzer greift ein: ein manueller NORMAL-Bolus nach der
        // Bewaffnung beendet den Lauf und setzt die Sperre.
        boluses = listOf(BS(timestamp = clock, amount = 1.5, type = BS.Type.NORMAL))
        var sperreBis = 0L
        repeat(3) { val o = cycle(); if (o.livenessExit == "MANUAL_INTERVENTION") sperreBis = o.livenessReArmUntilTs }
        assertTrue(sperreBis > clock, "der manuelle Exit muss die Sperre in die Zukunft gesetzt haben")
        assertTrue(adapter.persistVerified(dir), "der Zustand muss versiegelt werden")
        assertEquals(sperreBis, nachNeustart(dir).livenessReArmUntilTs, "die Sperre steht in der Datei")

        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", clock) })
        var blockiertGesehen = false
        var liftInSperre = 0
        var liftNachSperre = 0
        repeat(30) {
            val o = cycle()
            if (o.computeTs < sperreBis) {
                if (o.livenessDenial == "REARM_BLOCKED") blockiertGesehen = true
                if (o.livenessLiftU > 0.0) liftInSperre++
            } else if (o.livenessLiftU > 0.0) liftNachSperre++
        }
        assertTrue(blockiertGesehen, "die Sperre MUSS nach dem Neustart wirken")
        assertEquals(0, liftInSperre, "kein Hub innerhalb der Sperre")
        assertTrue(liftNachSperre >= 1, "nach Ablauf der Sperre bewaffnet der Kanal wieder")
    }

    /**
     * Grenztest der konfigurierbaren Schwelle (Toni 22.08.): unter ODER
     * GLEICH der Schwelle hebt der Kanal nie (die Bedingung ist strikt
     * `>`), knapp darueber hebt er; weit oben angesetzt bleibt dieselbe
     * Lage stumm. Die exakte Gleichheit ist im E2E nicht erzwingbar (der
     * Filter trifft nie exakt die Schwelle) - gedeckt ist sie ueber die
     * <=-Klassifikation jedes einzelnen Zyklus beim Durchgang durch die
     * Schwelle.
     */
    @Test
    fun `Liveness Grenze - die BG-Schwelle bindet strikt und ist konfigurierbar`(@TempDir dir: File) {
        livenessLage(dir)
        livenessBgMin = 205.0
        var liftUnterOderGleich = 0
        var liftDarueber = 0
        repeat(45) {
            val o = cycle()
            val bg = o.bgMgdl ?: return@repeat
            if (o.livenessLiftU > 0.0) { if (bg <= 205.0) liftUnterOderGleich++ else liftDarueber++ }
        }
        assertEquals(0, liftUnterOderGleich, "unter oder gleich der Schwelle hebt der Kanal nie")
        assertTrue(liftDarueber >= 1, "oberhalb der Schwelle muss er heben")

        livenessBgMin = 400.0
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(File(dir, "hoch").also(File::mkdirs), "test-epoch", start) })
        var liftHoch = 0
        repeat(45) { if (cycle().livenessLiftU > 0.0) liftHoch++ }
        assertEquals(0, liftHoch, "mit Schwelle 400 bleibt dieselbe Lage stumm")
    }

    /**
     * Toni 22.08.: die Aenderung der Schwelle WAEHREND eines Laufs beendet
     * ihn (CONFIG_CHANGED, ohne Sperre - Bedienhandlung, kein Risiko) und
     * der Bestaetigungs-Streak beginnt unter der neuen Schwelle neu.
     */
    @Test
    fun `Liveness Grenze - Schwellen-Aenderung beendet den Lauf und der Streak beginnt neu`(@TempDir dir: File) {
        livenessLage(dir)
        var aktivGesehen = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktivGesehen = true }
        assertTrue(aktivGesehen, "der Lauf muss stehen")
        // Die Schwelle sinkt auf 150 - am Druck aendert das nichts (BG weit
        // darueber), aber der Lauf wurde unter einer ANDEREN Regel bewaffnet.
        livenessBgMin = 150.0
        val o1 = cycle()
        assertEquals("CONFIG_CHANGED", o1.livenessExit, "die Aenderung beendet den Lauf")
        assertEquals(0.0, o1.livenessLiftU, 1e-9)
        assertEquals(false, o1.livenessActive)
        assertEquals(1, o1.livenessStreak, "der Streak beginnt im selben Zyklus neu bei 1")
        val o2 = cycle()
        assertEquals(0.0, o2.livenessLiftU, 1e-9, "Zyklus 2 der neuen Zaehlung: noch kein Hub")
        assertEquals(2, o2.livenessStreak)
        var wiederAb = -1
        repeat(6) { i -> if (cycle().livenessLiftU > 0.0 && wiederAb < 0) wiederAb = i + 1 }
        assertTrue(wiederAb in 1..4, "unter der neuen Schwelle bewaffnet er binnen weniger Zyklen neu: $wiederAb")
    }


    // ==== CODEX-GEGENPROBEN (22.08., vor jeder Aktivierung Pflicht) ========

    /**
     * Gegenprobe 1 - technischer Modellausfall: der Haftungshorizont
     * waechst ueber den Modellhorizont (DIA 5 h = 300 min) hinaus, der
     * Einheitskern deckt das Bewertungsfenster nicht mehr. Der Kanal muss
     * SOFORT stehen - der fachliche Guard/Tail-Bypass ist kein technischer
     * Blindflug.
     */
    @Test
    fun `Liveness Gegenprobe - technischer Modellausfall beendet den Lauf`(@TempDir dir: File) {
        livenessLage(dir)
        var aktiv = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktiv = true }
        assertTrue(aktiv, "der Lauf muss stehen")
        whenever(preferences.get(FuseIntKey.LiabilityHorizonMin)).thenReturn(360)
        val o1 = cycle()
        assertEquals("MODEL_UNAVAILABLE", o1.livenessExit, "der technische Modellausfall MUSS den Lauf beenden")
        assertEquals(0.0, o1.livenessLiftU, 1e-9)
        assertTrue(o1.livenessReArmUntilTs > o1.computeTs, "und die Sperre setzen")
        // Der TYPISIERTE Grund (Codex-P0): der Kern deckt das 360er-Fenster
        // nicht - exakt die Reject-Sorte, die auch finalVeto benennt.
        assertEquals("MODEL_HORIZON_TOO_SHORT", o1.livenessModelReject)
        // 14 Zyklen: LAENGER als die 10-min-Sperre - die "nie"-Aussage
        // haengt damit am Modell-Tor selbst, nicht an der Sperre
        // (Audit 22.08.: sonst truege die Sperre den Assert).
        repeat(14) {
            val x = cycle()
            assertEquals(0.0, x.livenessLiftU, 1e-9)
            assertEquals("MODEL_UNAVAILABLE", x.livenessDenial, "jeder Zyklus nennt das Tor")
            assertEquals("MODEL_HORIZON_TOO_SHORT", x.livenessModelReject, "typisiert, jeden Zyklus")
        }
    }

    /**
     * Gegenprobe 2 - manueller Bolus WAEHREND der Bewaffnung: der Nutzer
     * hat uebernommen, bevor der Streak voll war. Streak weg und dieselbe
     * Sperre wie beim Lauf-Exit - sonst hinge die Wirkung davon ab, ob der
     * Bolus einen Zyklus vor oder nach der Bewaffnung faellt.
     */
    @Test
    fun `Liveness Gegenprobe - manueller Bolus waehrend der Bewaffnung sperrt`(@TempDir dir: File) {
        livenessLage(dir)
        var o = cycle()
        var zyklen = 1
        while (zyklen < 40 && o.livenessStreak == 0) { o = cycle(); zyklen++ }
        assertTrue(o.livenessStreak in 1..2, "mitten in der Bewaffnung ankommen: Streak ${o.livenessStreak}")
        assertEquals(false, o.livenessActive)
        boluses = listOf(BS(timestamp = clock, amount = 1.0, type = BS.Type.NORMAL))
        val nach = cycle()
        assertEquals("MANUAL_INTERVENTION", nach.livenessDenial, "der Bolus WAEHREND der Bewaffnung muss den Streak beenden")
        assertEquals(0, nach.livenessStreak)
        assertTrue(nach.livenessReArmUntilTs > nach.computeTs, "und dieselbe Sperre setzen wie beim Lauf-Exit")
        var liftInSperre = 0
        repeat(9) { val x = cycle(); if (x.livenessLiftU > 0.0) liftInSperre++ }
        assertEquals(0, liftInSperre, "innerhalb der Sperre keine Bewaffnung, kein Hub")
    }

    /**
     * Gegenprobe 3 - aktiver Lauf, dann Abbruchzyklus, dann gesunder
     * Zyklus: eine absurde ISF laesst den Predictor ablehnen; ohne Marker
     * gibt es keinen Fallback, der Zyklus bricht ab. Der Kanal kann in
     * diesem Zyklus weder Riegel noch Druck pruefen - der Lauf endet
     * (OBSERVATION_LOST, mit Sperre) und laeuft im naechsten gesunden
     * Zyklus NICHT einfach weiter.
     */
    @Test
    fun `Liveness Gegenprobe - Abbruchzyklus beendet den Lauf statt ihn zu ueberbruecken`(@TempDir dir: File) {
        livenessLage(dir)
        var aktiv = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktiv = true }
        assertTrue(aktiv, "der Lauf muss stehen")
        val kaputt = org.mockito.kotlin.spy(validProfile)
        org.mockito.kotlin.doReturn(5000.0).whenever(kaputt).getIsfMgdlTimeFromMidnight(org.mockito.kotlin.any())
        whenever(profileFunction.getProfile()).thenReturn(kaputt)
        whenever(profileFunction.getProfile(any())).thenReturn(kaputt)
        val abbruch = cycle()
        assertTrue(abbruch.abortReason != null, "der Aufbau muss wirklich abbrechen: ${abbruch.abortReason}")
        assertEquals("OBSERVATION_LOST", abbruch.livenessExit, "der aktive Lauf endet im unbeobachteten Zyklus")
        assertTrue(abbruch.livenessReArmUntilTs > abbruch.computeTs, "mit Sperre")
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(profileFunction.getProfile(any())).thenReturn(validProfile)
        var liftInSperre = 0
        repeat(9) { val x = cycle()
            assertEquals(false, x.livenessActive, "kein Weiterlaufen nach dem unbeobachteten Zyklus")
            if (x.livenessLiftU > 0.0) liftInSperre++
        }
        assertEquals(0, liftInSperre)
    }

    /**
     * Gegenprobe 4 - EXCLUDED-Lage: die Marker-Ruecknahme widerruft den
     * Evidenzkredit, die Lage ist SUSPENDED - weder Mahlzeit noch
     * Korrektur. Obwohl Druck und Schwanz-Deadlock stehen, bewaffnet der
     * Kanal NICHT.
     */
    @Test
    fun `Liveness Gegenprobe - EXCLUDED-Lage bewaffnet nicht`(@TempDir dir: File) {
        livenessLage(dir)
        markerAuthorized = true
        markerAt = start + 2 * 60_000L
        repeat(12) { cycle() }
        // Ruecknahme: der Kredit ist widerrufen, die Evidenzlage SUSPENDED.
        markerAt = 0L
        var exklusiv = false
        var armGesehen = false
        var lifts = 0
        repeat(20) {
            val o = cycle()
            if (o.livenessDenial == "EXCLUDED_LAGE") {
                exklusiv = true
                // Der Grund ist wirklich die SUSPENDED-Evidenz - nicht der
                // Sammelname (Audit 22.08.: EXCLUDED_LAGE deckt drei
                // Ursachen, gepinnt wird die behauptete).
                assertEquals("SUSPENDED", o.evidencePhase)
            }
            if (o.livenessActive) armGesehen = true
            if (o.livenessLiftU > 0.0) lifts++
        }
        assertTrue(exklusiv, "die EXCLUDED-Lage muss benannt im Trail stehen")
        assertEquals(false, armGesehen, "keine Bewaffnung in der EXCLUDED-Lage")
        assertEquals(0, lifts)
    }

    /**
     * Gegenprobe 5, INVERTIERT mit v21 (Codex 22.08. spaet): die MINIMALE
     * Scheinwende - Drive-Knick um nur 0,15 mg/dl/min (1,4 -> 1,25,
     * kumuliert UNTER der 0,20er-Magnitude der Schatten-Klassifikation),
     * Druck und Anstieg bleiben klar erhalten - beendet den Lauf NICHT
     * mehr. Genau diese Kante entwaffnete den Kanal live fuer zehn Minuten
     * (22:53), obwohl die Abflachung eines weiterhin starken Anstiegs
     * keine Wende ist. Der Kanal traegt die Abflachung durch; die
     * DEUTLICHE Wende prueft weiterhin Fall 4.
     */
    @Test
    fun `Liveness Gegenprobe - die minimale Scheinwende beendet den Lauf nicht mehr`(@TempDir dir: File) {
        livenessLage(dir)
        knick2AbMin = 24
        steigungNachKnick2 = 1.25
        var wendeExits = 0
        var turnStanding = 0
        var liftNachKnick = 0
        repeat(40) { i ->
            val minute = i + 1
            val o = cycle()
            if (o.livenessExit == "TURN_EXIT") wendeExits++
            if (o.livenessDenial == "TURN_STANDING") turnStanding++
            if (minute > 26 && o.livenessLiftU > 0.0) liftNachKnick++
        }
        assertEquals(0, wendeExits, "eine 0,15er-Abflachung ist keine bestaetigte Wende")
        assertEquals(0, turnStanding, "und blockt auch keine Bewaffnung")
        assertTrue(liftNachKnick >= 8, "der Kanal traegt die Abflachung durch: $liftNachKnick Hubs")
    }

    /**
     * Gegenprobe 6 (Codex): nicht nur die BG-Schwelle - auch Kanaldeckel
     * und Re-Arm-Zeit veraendern einen laufenden Kanal und muessen ihn
     * beenden.
     */
    @Test
    fun `Liveness Grenze - auch Deckel- und Sperrzeit-Aenderung beenden den Lauf`(@TempDir dir: File) {
        livenessLage(dir)
        var aktiv = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktiv = true }
        assertTrue(aktiv, "der Lauf muss stehen")
        corrExpLimit = 6.8; mealExpLimit = 6.8 // frueher 85 % x maxIOB 8
        val o1 = cycle()
        assertEquals("CONFIG_CHANGED", o1.livenessExit, "die Deckel-Aenderung beendet den Lauf")
        assertEquals(0.0, o1.livenessLiftU, 1e-9)
        var wiederAktiv = -1
        repeat(6) { i -> val o = cycle(); if (o.livenessActive && wiederAktiv < 0) wiederAktiv = i + 1 }
        assertTrue(wiederAktiv in 1..4, "unter dem neuen Deckel bewaffnet er neu: $wiederAktiv")
        livenessReArmMin = 12
        val o2 = cycle()
        assertEquals("CONFIG_CHANGED", o2.livenessExit, "auch die Sperrzeit-Aenderung beendet den Lauf")
        assertEquals(0.0, o2.livenessLiftU, 1e-9)
    }



    /**
     * PHASE-2-FENSTER-REPLAY (Toni/Codex 23.08.): die AUFGEZEICHNETEN Tage
     * laufen durch den ECHTEN Runner - einmal mit Produktionsfenster W18
     * (Validierungstor: muss die aufgezeichneten Entscheidungen treffen),
     * dann W10 und W8 ueber den Konstruktor-Override, der am Geraet
     * konstruktionsbedingt nicht setzbar ist. Gespeist wird alles aus dem
     * Trail: Roh-BG-Serie, IOB+Aktivitaet je Sample, ISF je Tagesminute,
     * Ziel bleibt Rig-Profil (Toni faehrt ~98). GRENZE, ehrlich benannt:
     * Budgets/Ledger starten frisch (der Trailausschnitt beginnt deshalb
     * an einer ruhigen Grenze), und der spaetere reale BG entstand unter
     * der W18-Dosierung - verglichen werden Entscheidungs-Gegenrechnungen,
     * kein BG-Verlauf.
     *
     * Laeuft NUR mit Umgebungsvariablen (sonst uebersprungen):
     *   FUSE_REPLAY_TRAIL = Pfad zur jsonl-Datei
     *   FUSE_REPLAY_VON/BIS = optional epoch-ms-Grenzen
     *   FUSE_REPLAY_OUT = Ausgabeverzeichnis (Default: neben der Quelle)
     */
    @Test
    fun `Phase 2 - Fenster-Replay der aufgezeichneten Tage`(@TempDir dir: File) {
        val quelle = System.getenv("FUSE_REPLAY_TRAIL")
        org.junit.jupiter.api.Assumptions.assumeTrue(quelle != null, "nur mit FUSE_REPLAY_TRAIL")
        val von = System.getenv("FUSE_REPLAY_VON")?.toLong() ?: 0L
        val bis = System.getenv("FUSE_REPLAY_BIS")?.toLong() ?: Long.MAX_VALUE
        val outDir = File(System.getenv("FUSE_REPLAY_OUT") ?: File(quelle!!).parent).also { it.mkdirs() }

        data class Zyklus(
            val ts: Long, val raw: Double, val q1: Double, val act: Double, val isf: Double,
            val iobU: Double, val maxIob: Double, val marker: Long,
            /** Die BOLUS-IOB des Geraets - getrennt von der Gesamt-IOB. */
            val bolusIobU: Double?,
            val smbU: Double, val block: String?, val policy: org.json.JSONObject?,
        )
        val zyklen = ArrayList<Zyklus>()
        File(quelle).forEachLine { line ->
            runCatching {
                val o = org.json.JSONObject(line)
                val sig = o.optJSONObject("signal") ?: return@runCatching
                val st = o.optJSONObject("state")
                val dec = o.optJSONObject("decision")
                val ts = sig.optLong("sourceTs").takeIf { it > 0L } ?: o.optLong("sourceTs")
                if (ts !in von..bis) return@runCatching
                val raw0 = sig.optDouble("rawBg"); val q1 = sig.optDouble("q1")
                val act = sig.optDouble("activityAtAnchor"); val isf = sig.optDouble("isfAtAnchor")
                if (ts <= 0L || !raw0.isFinite() || !act.isFinite() || !isf.isFinite()) return@runCatching
                zyklen.add(Zyklus(
                    ts, raw0, q1, act, isf,
                    st?.optDouble("iobU")?.takeIf { it.isFinite() } ?: 0.0,
                    st?.optDouble("maxIobU")?.takeIf { it.isFinite() } ?: 8.0,
                    st?.optLong("markerArmedTs") ?: 0L,
                    o.optJSONObject("lowThreat")?.optDouble("bolusIobU")?.takeIf { it.isFinite() },
                    dec?.optDouble("smbU")?.takeIf { it.isFinite() } ?: 0.0,
                    dec?.optString("block")?.takeIf { it.isNotBlank() },
                    o.optJSONObject("policy")?.optJSONObject("values"),
                ))
            }
        }
        zyklen.sortBy { it.ts }
        require(zyklen.size > 100) { "zu wenig Zyklen: ${zyklen.size}" }
        println("REPLAY: ${zyklen.size} Zyklen ${java.util.Date(zyklen.first().ts)} .. ${java.util.Date(zyklen.last().ts)}")

        // Politik aus der ERSTEN Zeile auf die Rig-Hebel uebertragen; jede
        // Abweichung von den Rig-Konstanten wird gedruckt statt still zu
        // driften.
        println("POLICY der ersten Zeile: " + (zyklen.firstNotNullOfOrNull { it.policy }?.toString() ?: "FEHLT"))
        var pol: org.json.JSONObject? = null
        fun d(k: String, sonst: Double) = pol?.optDouble(k)?.takeIf { it.isFinite() } ?: sonst
        fun i(k: String, sonst: Int) = pol?.optInt(k, sonst) ?: sonst
        fun b(k: String, sonst: Boolean) = pol?.optBoolean(k, sonst) ?: sonst
        fun politikAnwenden(p: org.json.JSONObject?) {
            pol = p ?: return
            maxSmbU = d("maxSmbU", maxSmbU)
            guardBodenMgdl = d("guardFloorMgdl", guardBodenMgdl)
            iobThPct = i("iobThPercent", iobThPct)
            quantilePct = i("driveLowerQuantilePct", quantilePct)
            theilSenFensterMin = i("theilSenWindowMin", theilSenFensterMin)
            // Aeltere Trails (vor RuleSet 33) tragen das Feld nicht - dann
            // bleibt der Default 45 stehen, also exakt das aufgezeichnete
            // Verhalten. Ein fehlendes Feld darf nie als "0" gelesen werden.
            reboundFensterMin = i("reboundWindowMin", reboundFensterMin)
            tailGuard = b("tailGuardEnabled", tailGuard)
            conditionalTail = b("conditionalTailEnabled", conditionalTail)
            fundamentAn = b("mealFoundationEnabled", fundamentAn)
            fundamentAnteil = d("mealFoundationPhaseAShare", fundamentAnteil)
            // DER SOFORTANTEIL FEHLTE HIER (Befund 25.08. spaet). Ohne ihn
            // lief jede Standardspur mit 0,0, waehrend das Geraet 1,0 fuhr -
            // `phaseAUpfrontU = phaseABudgetU * 0` und upfrontState() gibt
            // dann unbedingt null zurueck. Die Auslassung war ausgerechnet
            // dort, wo sie am meisten kostet: Huelle und Phase-A-Anteil
            // kamen an, nur der Faktor 1 fiel weg.
            upfrontAnteil = d("mealFoundationPhaseAUpfrontShare", upfrontAnteil)
            fundamentEndeMin = i("mealFoundationEndMin", fundamentEndeMin)
            aufschubAn = b("deferredPrimeEnabled", aufschubAn)
            aufschubHorizontMin = d("markerPrimeDescentHorizonMin", aufschubHorizontMin)
            aufschubFristMin = i("deferredPrimeEndMin", aufschubFristMin)
            primeHuelleU = d("primeEnvelopeU", primeHuelleU)
            livenessAn = b("livenessChannelEnabled", livenessAn)
            mealPowerMin = i("mealPowerMin", mealPowerMin)
            zeroLatchAn = b("zeroLatchEnabled", zeroLatchAn)
            zeroLatchRuheZyklen = i("zeroLatchCalmExitMin", zeroLatchRuheZyklen)
            zeroLatchRuheAbstand = d("zeroLatchCalmDistanceMgdl", zeroLatchRuheAbstand)
            // DIE RUHE-EINSTELLUNGEN - bis 28.08. fehlten sie hier komplett.
            // Fehlt das Feld (Trails vor RuleSet 32), bleibt es bei AUS, also
            // beim aufgezeichneten Verhalten.
            ruheAusPolitik = if (!b("calmRecoveryEnabled", false)) null else runCatching {
                app.aaps.fuse.core.controller.UpfrontRecovery.Params.of(
                    calmCycles = i("calmRecoveryCycles", 3),
                    minUkf = d("calmRecoveryMinUkf", 0.0),
                    minGuardDistanceMgdl = d("calmRecoveryGuardDistanceMgdl", 5.0),
                    calmTreatment = app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment
                        .ofSetting(i("calmTreatmentMode", 0)),
                    ruleSetVersion = i("ruleSetVersion", app.aaps.fuse.plugin.export.FuseStateJson.RULE_SET_VERSION),
                )
            }.getOrNull()
            livenessBgMin = d("livenessBgMinDayMgdl", livenessBgMin)
            livenessBgMinNacht = pol?.optDouble("livenessBgMinNightMgdl")?.takeIf { it.isFinite() }
            livenessReArmMin = i("livenessReArmMin", livenessReArmMin)
            nachtStartMin = i("nightStartMin", nachtStartMin)
            nachtEndeMin = i("nightEndMin", nachtEndeMin)
            nightDeadband = if (nightDeadbandAus) false else b("nightDeadbandEnabled", nightDeadband)
            // B3 (Bauauftrag 7.5.7-Migration): policyMode + ALLE zentralen
            // Kandidaten + M1/M3. Bewusst SELBST-RESETTEND gegen das
            // Hebel-Leck: fehlt der Schluessel (Trails vor v38/v39/v40),
            // gilt LEGACY/unset/Altbestand - ein fehlendes Feld wird NIE
            // als 0 gelesen (optDouble auf JSON-null ist NaN -> takeIf).
            // KEIN optString fuer policyMode (die Android-optString-Falle
            // macht aus JSON-null den String "null").
            pol?.optDouble("correctionExposureLimitU")?.takeIf { it.isFinite() }?.let { corrExpLimit = it }
            pol?.optDouble("mealExposureLimitU")?.takeIf { it.isFinite() }?.let { mealExpLimit = it }
            pol?.optDouble("correctionDemandRatioCap")?.takeIf { it.isFinite() }?.let { corrRatioCapZ = it }
            pol?.optDouble("mealDemandRatioCap")?.takeIf { it.isFinite() }?.let { mealRatioCapZ = it }
            pol?.optDouble("livenessBgMinMealMgdl")?.takeIf { it.isFinite() }?.let { mealBgMin = it }
            mealArmZyklen = i("mealArmCycles", 3)
            // Diese drei sind im Rig FESTE Stubs - fuer den Replay auf die
            // aufgezeichnete Politik umgebogen (22.08.: Rampe 2,5, Rebound-
            // Totband 40, Prime-Fenster 20).
            whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenReturn(d("riseRampHighR", 2.0))
            whenever(preferences.get(FuseDoubleKey.ReboundDeadbandMgdl)).thenReturn(d("reboundDeadbandMgdl", 25.0))
            whenever(preferences.get(FuseIntKey.PrimeWindowMin)).thenReturn(i("primeWindowMin", 15))
            whenever(preferences.get(FuseDoubleKey.SmbRatio)).thenReturn(d("smbRatioCorrection", 0.15))
            whenever(preferences.get(FuseDoubleKey.SmbRatioRise)).thenReturn(d("smbRatioRise", 0.35))
        }
        // B3: ein expliziter Dosierkontext-Kandidat der Matrix. Er schaltet
        // den zentralen Modus AN und verlangt ALLE vier Kandidaten - eine
        // halbe Variante wuerde von validate je Zyklus abgelehnt und
        // erschiene als Abort-Rauschen statt als klare Fehlermeldung.
        fun dosingKandidatAnwenden(text: String) {
            text.split(",").forEach { teil ->
                val kv = teil.trim().split("=", limit = 2)
                require(kv.size == 2) { "Dosierkontext-Teil ohne '=': $teil" }
                when (kv[0].trim()) {
                    "corrExp" -> corrExpLimit = kv[1].toDouble()
                    "mealExp" -> mealExpLimit = kv[1].toDouble()
                    "corrRatio" -> corrRatioCapZ = kv[1].toDouble()
                    "mealRatio" -> mealRatioCapZ = kv[1].toDouble()
                    "mealBgMin" -> mealBgMin = kv[1].toDouble()
                    "mealArm" -> mealArmZyklen = kv[1].toInt()
                    else -> error("unbekannter Dosierkontext-Schluessel: ${kv[0]}")
                }
            }
            require(corrExpLimit != null && mealExpLimit != null && corrRatioCapZ != null && mealRatioCapZ != null) {
                "Dosierkontext-Kandidat unvollstaendig: corrExp/mealExp/corrRatio/mealRatio sind Pflicht ($text)"
            }
        }
        // Der Marker-Schalter steht NICHT in den alten Policy-Exporten -
        // Toni faehrt ihn konstant AN (Marker-Knopf ist sein Werkzeug).
        markerAuthorized = true
        politikAnwenden(zyklen.firstNotNullOfOrNull { it.policy })

        // Rohserie + IOB/Aktivitaets-Karte aus dem Trail.
        rohSerie = zyklen.map { it.ts to it.raw }
        iobProTs = java.util.TreeMap(zyklen.associate { it.ts to (it.iobU to it.act) })
        bolusIobProTs = java.util.TreeMap(zyklen.mapNotNull { z -> z.bolusIobU?.let { z.ts to it } }.toMap())

        // ISF je Tagesminute aus dem Trail (Toni faehrt ein Zeitprofil).
        val isfProMin = HashMap<Int, Double>()
        // DIESELBE FUNKTION AUF BEIDEN SEITEN (Toni 23.08. Abend): der Runner
        // fragt mit MidnightUtils.secondsFromMidnight - also fuellt die Karte
        // mit EXAKT dieser Funktion statt einer parallelen Calendar-Rechnung.
        // Konsistenz per Konstruktion; der fruehere UTC-Modulo verschob unter
        // CEST um zwei Stunden (Abend-ISF 60 statt 72, zu aggressiv).
        zyklen.forEach { z ->
            isfProMin[app.aaps.core.utils.MidnightUtils.secondsFromMidnight(z.ts) / 60] = z.isf
        }
        val replayProfil = org.mockito.kotlin.spy(validProfile)
        org.mockito.kotlin.doAnswer { inv ->
            val sec = inv.getArgument<Int>(0)
            isfProMin[(sec / 60) % 1440] ?: validProfile.getIsfMgdlTimeFromMidnight(sec)
        }.whenever(replayProfil).getIsfMgdlTimeFromMidnight(org.mockito.kotlin.any())
        whenever(profileFunction.getProfile()).thenReturn(replayProfil)
        whenever(profileFunction.getProfile(any())).thenReturn(replayProfil)

        // Der Spy bekommt die vom Runner errechneten LOKALEN Sekunden und
        // die Karte ist seit dem Zeitzonen-Fix oben ebenfalls lokal gefuellt
        // - eine Uhr fuer beide Seiten.

        // DIE WIRKSAMEN RUHEPARAMETER SICHTBAR MACHEN - und widersprechen,
        // wenn sie der aufgezeichneten Politik widersprechen. Genau dieser
        // stille Widerspruch hat die Abnahme vom 28.08. entwertet.
        fun ruheParameterPruefen(name: String, wirksam: app.aaps.fuse.core.controller.UpfrontRecovery.Params) {
            val ausPolitik = pol?.optBoolean("calmRecoveryEnabled", false) ?: false
            val modus = pol?.optString("calmTreatment") ?: "?"
            // KEIN toString() DES OBJEKTS - das druckt die Adresse. Was
            // geprueft werden muss, sind die WERTE gegen die Politik.
            println(
                "RUHE[$name]: wirksam=" + (wirksam != app.aaps.fuse.core.controller.UpfrontRecovery.Params.OFF) +
                    "  Politik: enabled=$ausPolitik modus=$modus" +
                    " zyklen=" + (pol?.optInt("calmRecoveryCycles", -1) ?: -1) +
                    " minUkf=" + (pol?.optDouble("calmRecoveryMinUkf", -1.0) ?: -1.0) +
                    " guard=" + (pol?.optDouble("calmRecoveryGuardDistanceMgdl", -1.0) ?: -1.0)
            )
            if (ausPolitik && wirksam == app.aaps.fuse.core.controller.UpfrontRecovery.Params.OFF) {
                error(
                    "REPLAY LIEFE OHNE RUHEFUNKTION, obwohl die aufgezeichnete " +
                        "Politik sie scharf hat ($modus). Ein Lauf in dieser " +
                        "Verfassung beweist nichts ueber den Ruhepfad - er hat " +
                        "am 28.08. eine ganze Abnahme entwertet."
                )
            }
        }

        fun lauf(name: String, fensterMs: Long?, trendRegel: String? = null, fenster: Int = 18, livenessStart: Boolean = true, upfrontStart: Double? = null, guardsStart: Boolean = false, reversalConfirm: Int = 2, gapBreakMs: Long? = null, reifeTag: String? = null, rejoin: Boolean = false, ruhe: app.aaps.fuse.core.controller.UpfrontRecovery.Params = app.aaps.fuse.core.controller.UpfrontRecovery.Params.OFF, dosingKandidat: String? = null): File {
            transportReset()
            boluses = emptyList()
            markerAt = 0L
            // HEBEL-LECK GESCHLOSSEN (23.08. spaet): v16-Trails tragen den
            // livenessChannelEnabled-Schluessel nicht - der Hebel behielt
            // dann den Stand des VORHERIGEN Laufs (der 22.08.-cap100-Lauf
            // fuhr dadurch ohne Kanal, die Folgelaeufe mit). Jeder Lauf
            // startet jetzt explizit; Zeilen MIT Schluessel ueberschreiben
            // wie gehabt per politikAnwenden.
            livenessAn = livenessStart
            // HEBEL-LECK TEIL 2 (24.08., Ermittler-Befund der 2,000-U-
            // Differenz): die vier Profil-Cap-Felder setzt politikAnwenden
            // NUR, wenn die Trail-Zeile den Schluessel traegt. Wechselt der
            // Trail mittags das Schema (alt livenessIobCapPercent -> v24-
            // Split), erbt Lauf 2 fuer die FRUEHEN Zyklen die SPAETEN Werte
            // von Lauf 1: (65-40)% x maxIob 8 = exakt 2,000 U weniger
            // Liveness-Headroom, 57 scheinbare SMB-Abweichungen im
            // Latch-Vergleich. Jeder Lauf startet ungesetzt; NUR die
            // Profil-Matrix (FUSE_REPLAY_PROFILE) behaelt ihre bewusst
            // gesetzten Caps.
            // Dieselbe Leck-Regel fuer den Sofortanteil (v28): alte Trails
            // tragen den Schluessel nicht, jeder Lauf startet explizit.
            // `null` heisst NICHT GESETZT - bei 0.0 waere "die Matrix will
            // ausdruecklich 0" von "der Aufrufer sagt nichts" nicht zu
            // unterscheiden, und die Politik duerfte nie mehr durchkommen.
            upfrontStart?.let { upfrontAnteil = it }
            // Dieselbe Leck-Regel fuer die Korrekturpfad-Riegel (v30);
            // politikAnwenden liest die Guard-Schluessel bewusst NICHT ein -
            // die Matrix steuert, nicht die aufgezeichnete Politik.
            reversalAn = guardsStart
            rearmAn = guardsStart
            reversalConfirmWert = reversalConfirm
            // DIE GAP-POLITIK IST KEIN RIG-FELD MEHR und auch kein
            // Prozesszustand: sie geht als Wert in den Runner dieses Laufs
            // (Review Toni 25.08. abends). Damit kann sie nicht mehr in
            // einen Folgelauf lecken - jeder Lauf traegt seine eigene.
            val gapPolitik = gapBreakMs
                ?.let { app.aaps.fuse.core.signal.GapPolicy.of(it) }
                ?: app.aaps.fuse.core.signal.GapPolicy.PRODUCTION
            // Dieselbe Regel fuer die Reifebedingung (25.08. abends): ein
            // Wert, der nur im Konstruktor dieses Laufs lebt. `parse(null)`
            // ergibt die Produktion - der Referenzlauf braucht also gar
            // nichts zu setzen und kann auch nichts vergessen.
            // VORLAUF: die Variante greift erst 20 min nach Fensterbeginn.
            // Ein Replay startet kalt; in seinen ersten Minuten ist die
            // Referenz blind, weil noch keine Reihe da ist - nicht, weil
            // das Geraet blind war. Ohne diese Sperre dosiert eine
            // aggressive Variante in den Kaltstart hinein, und ueber
            // Ledger und Deckel verseucht das den ganzen Lauf (gemessen
            // am Fall 25.08. 11:42: 3x1 dosierte dort 3 x 0,550 U im
            // Kaltstart, die Haelfte der scheinbaren Mehrmenge).
            // 20 min sind reichlich: der Observer ist nach ~8 Zyklen
            // scharf, und der Vorlauf muss nur den Kaltstart abdecken.
            val reifeAbTs = zyklen.first().ts + 20L * 60_000L
            val reifePolitik = app.aaps.fuse.core.signal.MaturityPolicy.parse(reifeTag, reifeAbTs)
            // Der PRODUKT-Rejoin - nicht die globale Reife. Er wirkt nur
            // nach einer Luecke IN DER REIHE; der Schalter torsteuert ihn
            // wie am Geraet.
            rejoinAn = rejoin
            val rejoinPolitik =
                if (rejoin) app.aaps.fuse.core.signal.RejoinPolicy.enabled()
                else app.aaps.fuse.core.signal.RejoinPolicy.OFF
            forecastShadowAn = false // Replay braucht die Matrizen nicht - Tempo
            theilSenFensterMin = fenster // W18-Trails tragen den Schluessel nicht - der Hebel gilt
            // CENTRAL-only-Leck-Regel: jeder Lauf startet auf den ECHTEN
            // Produkt-Defaults (Tonis Startsatz); traegt die Politik-Zeile
            // die Schluessel, ueberschreibt politikAnwenden sie. Alte
            // LEGACY-Trails werden damit ausdruecklich "wie CENTRAL-only
            // es gefahren haette" gerechnet - eine LEGACY-Nachbildung gibt
            // es seit dem Cleanup nicht mehr.
            corrExpLimit = 3.0; mealExpLimit = 7.0
            corrRatioCapZ = 0.20; mealRatioCapZ = 0.35
            mealBgMin = 110.0
            mealArmZyklen = 1
            politikAnwenden(zyklen.firstNotNullOfOrNull { it.policy })
            theilSenFensterMin = fenster // die erste Politik darf den Matrixwert nicht ueberschreiben (W10-Live-Trails tragen 10)
            upfrontStart?.let { upfrontAnteil = it }   // derselbe Vorrang fuer den Sofortanteil
            dosingKandidat?.let { dosingKandidatAnwenden(it) } // B3: die Matrix schlaegt die Aufzeichnung
            val adapter = FuseLedgerAdapter().also { it.loadOnce(File(dir, name).also(File::mkdirs), "test-epoch", zyklen.first().ts) }
            // EIN EXPLIZITER TREIBER-OVERRIDE SCHLAEGT DIE POLITIK, sonst gilt
            // die aufgezeichnete Einstellung. Vor dem 28.08. stand hier
            // unbedingt `ruhe` - und weil dessen Default AUS ist, lief jeder
            // Replay ohne Ruhefunktion, egal was das Geraet fuhr.
            val ruheWirksam = if (ruhe != app.aaps.fuse.core.controller.UpfrontRecovery.Params.OFF) ruhe
            else ruheAusPolitik ?: app.aaps.fuse.core.controller.UpfrontRecovery.Params.OFF
            ruheParameterPruefen(name, ruheWirksam)
            neuerRunner(adapter, fensterMs = fensterMs, trendRegel = trendRegel, gapPolitik = gapPolitik, reifePolitik = reifePolitik, wiedereinstieg = rejoinPolitik, ruheParams = ruheWirksam)
            val outFile = File(outDir, "replay_$name.csv")
            outFile.printWriter().use { w ->
                w.println("ts;smbU;block;binding;insulinReq;liftU;needU;abort;phase;fastD;slowD;trend;raw;recSmbU;recBlock;profil;restMin;tbr;latch;lvDenial;lvExit;lvStreak;lvHead;transC;revGrund;rearmGrund;ctxGrund;basis;gapBreakMs;samplesUsed;gapBeforeMin;r;bandN;matP;matS;iob;rejoin;rejoinGrund;gapMs;vollreifeTs;regimeGrund;regimeTs;regimeSegTs;vorReif;ruheModus;ruheStreak;ruheDenial;gefahr;guardAbst;grantU;vorFloor;nachFloor;nachRiegel;rtAngefordert;upfrontState;upfrontPendingU;riskAktiv;latchAktiv;latchGrund;iobAnkerFehlt;iobFehltAnkerKum;iobFehltHistKum;upfrontShare;q1;ukf;aktivitaet;bolusIobU;totalIobU;guardBoden;abstandBoden;minToFloor;ueberdeckung;fallrate;lowVerdikt;riskDenial;recoveryZyklen;horizontMin;aufschubGrund;dosingProfil;dosingGrund;expoSource;expoBind;expoBlock;expoBinding;expoHeadU;expoLimitU;bgMinQuelle;expoReqSource;smbState;smbStop;reqU;capU")
                // DER VORGEFUNDENE MARKER IST KEIN BEOBACHTETER DRUCK
                // (Toni 25.08. spaet). `prevMarker = 0` liess den ersten
                // Zyklus jeden schon laufenden Marker als frisch gedrueckt
                // sehen: am 25.08. wurde der alte 11-Uhr-Marker um 16:30
                // "gedrueckt", und damit entstanden Pinning, Batch und
                // Resetfolge auf einer Vorgeschichte, die es am Geraet nie
                // gab. Das Geraet kannte ihn seit Stunden - und
                // `markerPressObserved() == markerTs` war fuer ihn FALSCH.
                //
                // `markerAtIntern` statt `markerAt`: der Setter wuerde den
                // Druck mitsetzen, und genau den soll es hier nicht geben.
                val startMarker = zyklen.first().marker
                if (startMarker > 0L) {
                    markerAtIntern = startMarker
                    markerPress = 0L
                }
                var prevMarker = startMarker
                var polText = pol?.toString()
                var zyklusNr = 0
                for (z in zyklen) {
                    // MOCKITO-INVOCATION-HYGIENE: ohne das sammelt Mockito
                    // ueber 5 Laeufe x >1400 Zyklen zig Millionen
                    // Aufruf-Records und der Test-Executor stirbt (beobachtet
                    // am 21.08.-Tag nach ~20 min). Stubs bleiben erhalten,
                    // nur die Aufzeichnung wird geleert.
                    if (zyklusNr++ % 200 == 0) org.mockito.Mockito.clearInvocations(
                        preferences, profileFunction, iobCobCalculator,
                        persistenceLayer, dateUtil, replayProfil,
                    )
                    z.policy?.toString()?.takeIf { it != polText }?.let {
                        polText = it
                        politikAnwenden(z.policy)
                        // B3: der Matrix-Kandidat gilt fuer den GANZEN Lauf -
                        // sonst wuerde die erste Politik-Zeile ihn auf die
                        // aufgezeichnete LEGACY-Politik zuruecksetzen.
                        dosingKandidat?.let { k -> dosingKandidatAnwenden(k) }
                    }
                    if (z.marker != prevMarker && z.marker > 0L) markerAt = z.marker
                    prevMarker = z.marker
                    clock = z.ts
                    bolusIobAnkerFehltJetzt = false
                    val o = runner.run(false, testPumpe())
                    val klass = o.turnResponseShadow?.classification
                    w.println(listOf(
                        z.ts, "%.3f".format(java.util.Locale.US, o.decision.smbU), o.decision.block,
                        o.decision.bindingLimit,
                        o.decision.insulinReqU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        "%.3f".format(java.util.Locale.US, o.livenessLiftU),
                        o.livenessNeedU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.abortReason ?: "",
                        klass?.phase?.name ?: "",
                        klass?.fastDriveMgdlPerMin?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        klass?.slowDriveMgdlPerMin?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        if (o.trendRuleApplied) "1" else "0",
                        "%.1f".format(java.util.Locale.US, z.raw),
                        "%.3f".format(java.util.Locale.US, z.smbU), z.block ?: "",
                        o.livenessProfile ?: "",
                        if (o.markerPowerDeadlineTs > o.computeTs) ((o.markerPowerDeadlineTs - o.computeTs) / 60_000L).toString() else "",
                        o.decision.tbr.name,
                        if (o.zeroLatchActive) "1" else "0",
                        o.livenessDenial ?: "", o.livenessExit ?: "", o.livenessStreak.toString(),
                        o.livenessHeadroomU?.let { h -> "%.3f".format(java.util.Locale.US, h) } ?: "",
                        "",
                        // v30: die GERECHNETEN Riegel-Gruende - auch dort, wo
                        // der SMB schon aus anderem Grund 0 war (im Replay ist
                        // das die Doppelverteidigung mit dem Nachtband).
                        o.correctionReversal?.reason ?: "",
                        o.correctionRearm?.reason ?: "",
                        // Der AUTORITATIVE Kontextgrund (Review-P0.2) -
                        // damit im Replay pruefbar ist, dass in
                        // EVIDENCE_ACTIVE-Zyklen kein Riegel steht.
                        o.correctionContextReason ?: "",
                        o.correctionMealBasis ?: "",
                        o.rSegmentBreakMs,
                        o.signal?.samplesUsed ?: "",
                        o.signal?.gapBeforeMin?.let { "%.2f".format(java.util.Locale.US, it) } ?: "",
                        // REIFE-REPLAY (25.08. abends). `r` fehlte bisher
                        // ganz - eine Auswertung des Schaetzfehlers war damit
                        // gar nicht moeglich, und ein Leser, der die Spalte
                        // vermutete, bekam stillschweigend Nullen.
                        o.signal?.rSigned?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        o.band?.pairCount ?: "",
                        // Die WIRKSAME Reife dieses Zyklus - nach dem
                        // Wiedereinstieg ist das nicht mehr die Basis.
                        (o.signal?.rejoin?.maturity ?: o.maturity).minPointsAt(z.ts),
                        (o.signal?.rejoin?.maturity ?: o.maturity).minSlopesAt(z.ts),
                        o.iobU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        if (o.signal?.rejoin?.active == true) "1" else "0",
                        o.signal?.rejoin?.cause?.name ?: "",
                        o.signal?.rejoin?.gapMs ?: "",
                        o.signal?.fullMaturityTs ?: "",
                        o.signal?.rejoin?.regime?.bound?.name ?: "",
                        o.signal?.rejoin?.regime?.boundaryTs ?: "",
                        o.signal?.rejoin?.regime?.segmentStartTs ?: "",
                        if (o.signal?.rejoin?.preGapStrictReady == true) "1" else "0",
                        o.upfrontChain?.recoveryMode ?: "",
                        o.upfrontChain?.recoveryStreak ?: "",
                        o.upfrontChain?.recoveryDenial ?: "",
                        o.upfrontChain?.currentHazard ?: "",
                        o.upfrontChain?.guardDistanceMgdl?.let { "%.1f".format(java.util.Locale.US, it) } ?: "",
                        o.upfrontChain?.grantU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.upfrontChain?.beforeMarkerFloorU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.upfrontChain?.afterMarkerFloorU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.upfrontChain?.afterDescentGateU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.upfrontChain?.requestedRtU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        // DIE VERGLEICHSGROESSEN DES AEQUIVALENZTORS (Toni
                        // 25.08. spaet): "0x NO_INPUT" reicht nicht - der
                        // OFF-Lauf muss je Zyklus Grant, Aufschubzustand,
                        // Risk/Latch und Endanforderung treffen.
                        o.phaseAUpfrontState ?: "",
                        "%.3f".format(java.util.Locale.US, o.phaseAUpfrontPendingU),
                        if (o.descentRiskActive) "1" else "0",
                        if (o.descentLatchActive) "1" else "0",
                        o.descentLatchReason ?: "",
                        if (bolusIobAnkerFehltJetzt) "1" else "0",
                        bolusIobFehltAnker.toString(),
                        bolusIobFehltHistorisch.toString(),
                        // DER WIRKSAME SOFORTANTEIL DIESES LAUFS. Seine
                        // Unsichtbarkeit hat Referenz und Kandidat unbemerkt
                        // auseinanderlaufen lassen; er gehoert in jede Zeile.
                        "%.2f".format(java.util.Locale.US, upfrontAnteil),
                        // DIE EINGABEKETTE DES ABWAERTSRISIKOS (Toni 25.08.
                        // spaet). `FLOOR_BEYOND_HORIZON` kann auch aus einem
                        // falsch gestubbten HORIZONT entstehen, obwohl q1 und
                        // UKF identisch sind - deshalb steht der Horizont
                        // danebem.
                        o.signal?.q1?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.signal?.ukfRatePerMin?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        o.signal?.activityAtAnchor?.let { "%.6f".format(java.util.Locale.US, it) } ?: "",
                        o.lowThreat?.bolusIobU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.iobU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        "%.1f".format(java.util.Locale.US, guardBodenMgdl),
                        o.lowThreat?.distanceToFloorMgdl?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.descentMinutesToFloor?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.descentOvercoverageMgdl?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        o.descentFallRatePerMin?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        o.lowThreat?.verdict?.name ?: "",
                        o.descentRiskDenial ?: "",
                        o.descentRecoveryCycles.toString(),
                        aufschubHorizontMin.toString(),
                        o.upfrontChain?.recoveryDenial ?: "",
                        // B3-ATTRIBUTION: Kontext, Endpruefung, Quellen-
                        // Provenienz und M1-Schwellenquelle je Zyklus -
                        // getrennt lesbar, damit eine Divergenz dem
                        // richtigen Baustein zugeordnet werden kann
                        // (Kontext / Exposure / Ratio / Quellenberechtigung
                        // / pressureThreshold).
                        o.dosingContextProfile ?: "",
                        o.dosingContextReason ?: "",
                        o.exposureFinalSource ?: "",
                        o.exposureGateBindet?.let { if (it) "1" else "0" } ?: "",
                        o.exposureGateBlocked?.let { if (it) "1" else "0" } ?: "",
                        o.exposureGateBinding ?: "",
                        o.exposureGateHeadroomU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.exposureGateEffectiveLimitU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.livenessBgMinSource ?: "",
                        o.exposureRequestedSource ?: "",
                        o.smbState ?: "",
                        o.smbStopReason ?: "",
                        o.smbRequestedU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.smbCappedU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                    ).joinToString(";"))
                }
            }
            println("$name -> ${outFile.absolutePath}")
            return outFile
        }

        System.getenv("FUSE_REPLAY_ZEROLATCH")?.let {
            // Zero-Latch-Gegenrechnung: derselbe Tag einmal ohne und einmal
            // mit verriegelter Null (Bauauftrag Toni 24.08. abends).
            zeroLatchAn = false
            lauf("latchAus", null, fenster = 10)
            zeroLatchAn = true
            lauf("latchAn", null, fenster = 10)
            zeroLatchAn = false
            return
        }
        System.getenv("FUSE_REPLAY_GUARDS")?.let { modus ->
            // Korrekturpfad-Riegel-Gegenrechnung (v30): derselbe Tag einmal
            // ohne und einmal mit beiden Schutzlinien. Rueckkopplungsblind -
            // belastbar sind die Block-Zyklen (binding traegt REVERSAL_/
            // REARM_) und der Zeitpunkt der ersten Abweichung.
            //
            // FUSE_REPLAY_GUARDS=blind faehrt stattdessen ZWEIMAL DENSELBEN
            // Lauf. Jede Differenz daraus ist ein Rig-Artefakt der
            // Lauf-Reihenfolge und KEINE Schalterwirkung - die Probe
            // gehoert vor jede Aussage ueber gemessene Unterschiede.
            if (modus == "blind") {
                lauf("blindA", null, fenster = 10)
                lauf("blindB", null, fenster = 10)
                return
            }
            // CONFIRM-MATRIX: FUSE_REPLAY_GUARDS=confirm:2,3,4,6 faehrt
            // denselben Tag mit verschiedenen r-Bestaetigungszyklen des
            // V-Riegels. Erst seit die Kontextsperre korrigiert ist
            // (Mahlzeitenbasis statt jedes MEAL), kann dieser Parameter
            // ueberhaupt wirken - vorher band die Kontextgrenze frueher.
            // OHNE NACHTBAND: am Vorfallstag nullt das Nachtband dieselben
            // Zyklen, in denen der V-Riegel steht (06:25-06:28) - eine
            // Doppelverteidigung, die die Riegelwirkung im Replay
            // unsichtbar macht. Dieser Lauf schaltet NUR das Nachtband in
            // BEIDEN Laeufen ab und misst den Riegel allein.
            if (modus == "nonight") {
                nightDeadbandAus = true
                lauf("nonightAus", null, fenster = 10)
                lauf("nonightAn", null, fenster = 10, guardsStart = true)
                nightDeadbandAus = false
                return
            }
            if (modus.startsWith("confirm:")) {
                lauf("confirmAus", null, fenster = 10)
                modus.removePrefix("confirm:").split(",").forEach { c ->
                    val n = c.trim().toInt()
                    lauf("confirm%02d".format(n), null, fenster = 10, guardsStart = true, reversalConfirm = n)
                }
                return
            }
            lauf("guardsAus", null, fenster = 10)
            lauf("guardsAn", null, fenster = 10, guardsStart = true)
            return
        }
        // FUSE_REPLAY_PROFILE (Legacy-Cap-Matrix) ist mit dem
        // CENTRAL-only-Cleanup entfernt - Profilvarianten laufen ueber
        // FUSE_REPLAY_DOSING_CONTEXT.
        val upfrontEnv = System.getenv("FUSE_REPLAY_UPFRONT")
        if (upfrontEnv != null) {
            // Sofortanteil-Matrix (iLet, v28): je Wert ein Lauf ueber
            // dieselben historischen Mahlzeiten, alles andere konstant -
            // z.B. FUSE_REPLAY_UPFRONT=0,0.5,0.75,1.0 -> upf000..upf100.
            // Rueckkopplungsblind: nach der ersten Dosisdivergenz sind die
            // Summen nur Obergrenzen; belastbar sind Zeitpunkt und
            // Richtung der ersten Abweichung.
            upfrontEnv.split(",").forEach { u ->
                val anteil = u.trim().toDouble()
                lauf("upf%03d".format((anteil * 100).toInt()), null, fenster = 10, upfrontStart = anteil)
            }
            return
        }
        val ruheEnv = System.getenv("FUSE_REPLAY_UPFRONT_CALM")
        if (ruheEnv != null) {
            // RUHE-MATRIX (Auflage Toni 25.08. spaet). Jeder Kandidat
            // laeuft durch den VOLLSTAENDIGEN Endpfad; die CSV weist Grant,
            // vor/nach MarkerFloor, nach Endriegel und publizierte Menge
            // einzeln aus. Format: N:minUkf:minAbstand, mehrere durch Komma.
            //
            // Der Referenzlauf setzt NICHTS - `Params.OFF` ist der heutige
            // Vertrag, und die Matrix misst gegen ihn.
            lauf("calmRef", null, fenster = 10, upfrontStart = 1.0)
            ruheEnv.split(",").forEach { spec ->
                val t = spec.trim().split(":")
                // DIE BEHANDLUNG IST PFLICHT, kein vierter Wert mit Default:
                // "ruhig" allein sagt nicht, was mit der Menge geschieht, und
                // genau diese Verwechslung war die Sicherheitskante.
                // Format: N:minUkf:minAbstand:(demand|shift)
                require(t.size == 4) {
                    "Ruhe-Spezifikation braucht N:minUkf:minAbstand:(demand|shift), war '$spec'"
                }
                val behandlung = when (t[3].trim().lowercase()) {
                    "demand" ->
                        app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.DEMAND_LIMITED
                    "shift" ->
                        app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.SHIFT_TO_DEFERRED
                    // Der dosierwirksame Modus - der einzige, der im Replay
                    // andere ENDMENGEN erzeugen kann.
                    "batch" ->
                        app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.CALM_BATCH
                    else -> error(
                        "unbekannte Behandlung '${t[3]}' - erlaubt: demand, shift, batch",
                    )
                }
                val p = app.aaps.fuse.core.controller.UpfrontRecovery.Params.of(
                    calmCycles = t[0].toInt(),
                    minUkf = t[1].toDouble(),
                    minGuardDistanceMgdl = t[2].toDouble(),
                    calmTreatment = behandlung,
                    ruleSetVersion = app.aaps.fuse.plugin.export.FuseStateJson.RULE_SET_VERSION,
                )
                lauf("calm${t[0]}_${t[1].replace(".", "")}_${t[2].replace(".", "")}_${t[3].trim()}",
                     null, fenster = 10, upfrontStart = 1.0, ruhe = p)
            }
            lauf("calmRefNach", null, fenster = 10, upfrontStart = 1.0)
            return
        }
        if (System.getenv("FUSE_REPLAY_REJOIN") != null) {
            // PRODUKT-REJOIN gegen die Produktion, ueber denselben
            // Ausschnitt. Anders als FUSE_REPLAY_MATURITY aendert das NICHT
            // die globale Reife: die Lockerung wirkt nur nach einer Luecke
            // IN DER REIHE, mit allen Verweigerungsgruenden.
            //
            // RIG-VORBEHALT, der in jeden Befund gehoert: der Replay speist
            // die Reihe aus ZYKLEN. Eine Schleifenpause (Bolus laeuft, CGM
            // laeuft weiter) sieht darin aus wie Funkstille - am Geraet
            // nicht, dort haengt die Reihe an den CGM-Werten. Solche
            // Episoden muessen in der Auswertung ausgeschlossen werden.
            lauf("rejoinRef", null, fenster = 10)
            lauf("rejoinAn", null, fenster = 10, rejoin = true)
            lauf("rejoinRefNach", null, fenster = 10)
            return
        }
        val reifeEnv = System.getenv("FUSE_REPLAY_MATURITY")
        if (reifeEnv != null) {
            // REIFE-MATRIX (Bauauftrag Toni 25.08. abends, dosierneutral):
            // FUSE_REPLAY_MATURITY=5x6,4x3,3x1 - je Wert ein eigener
            // Runner ueber denselben Ausschnitt, geaendert wird NUR die
            // Theil-Sen-Reifebedingung.
            //
            // Die Frage dahinter: nach einer echten CGM-Luecke bleiben
            // heute ~5-6 min blind, UNABHAENGIG von der Lueckenlaenge -
            // das ist die Reifebedingung, nicht die Luecke. Bei 1-min-
            // Kadenz entsprechen 5x8/5x6/4x3/3x1 genau 6/5/4/3 Punkten,
            // also 5/4/3/2 Wartminuten.
            //
            // Der Referenzlauf setzt NICHTS: `parse(null)` ergibt die
            // Produktion. Er muss bitgleich zu einem Lauf ohne diesen
            // Hebel sein, sonst traegt die Matrix nicht.
            lauf("matRef", null, fenster = 10)
            reifeEnv.split(",").forEach { t ->
                val tag = t.trim()
                lauf("mat$tag", null, fenster = 10, reifeTag = tag)
            }
            // INTERLEAVING-PROBE (Toni): unmittelbar nach der Matrix noch
            // einmal die Referenz. Waere die Politik irgendwo prozessweit,
            // truege dieser Lauf den zuletzt gesetzten Wert - er muss
            // stattdessen bitgleich zu `matRef` sein.
            lauf("matRefNach", null, fenster = 10)
            return
        }
        val gapEnv = System.getenv("FUSE_REPLAY_GAP")
        if (gapEnv != null) {
            // CGM-GAP-MATRIX (Bauauftrag Toni 25.08. abends):
            // FUSE_REPLAY_GAP=180,195,210,240 - je Wert ein Lauf ueber
            // denselben Tag, geaendert wird NUR die eine Gap-Politik.
            // Der 180er-Lauf ist die Referenz und MUSS bitgleich zum
            // Lauf ohne Override sein; sonst traegt die Matrix nicht.
            lauf("gapRef", null, fenster = 10)
            gapEnv.split(",").forEach { g ->
                val sek = g.trim().toLong()
                lauf("gap%03d".format(sek), null, fenster = 10, gapBreakMs = sek * 1000L)
            }
            return
        }
        val tsEnv = System.getenv("FUSE_REPLAY_TS")
        if (tsEnv != null) {
            // THEIL-SEN-FENSTERMATRIX (Tonis Dauerfrage: passt W10, oder waere
            // W8/W12 der bessere Schritt?). FUSE_REPLAY_TS=10,8,12,10 faehrt
            // denselben Tag durch mehrere Hauptschaetzer-Fenster.
            //
            // BLINDPROBE EINGEBAUT: steht ein Fenster zweimal in der Liste,
            // sind seine beiden Spuren die Kontrolle. Weichen sie in einer
            // dosierrelevanten Spalte ab, ist JEDE Differenz der Matrix ein
            // Artefakt der Lauf-Reihenfolge und keine Fensterwirkung.
            //
            // DIE REBOUND-DAUER wird optional gepinnt, damit alle Spuren
            // denselben Schutz fahren. Ohne die Klammer laege sie auf dem
            // Wert der ERSTEN Trailzeile - bei einem Tag, der mitten im Lauf
            // umgestellt wurde, waere das nicht der aktuelle Stand.
            System.getenv("FUSE_REPLAY_TS_REBOUND")?.let { reboundFensterMin = it.trim().toInt() }
            tsEnv.split(",").forEachIndexed { idx, w ->
                val n = w.trim().toInt()
                lauf("ts%d-w%02d".format(idx, n), null, fenster = n)
            }
            return
        }
        val reboundEnv = System.getenv("FUSE_REPLAY_REBOUND")
        if (reboundEnv != null) {
            // REBOUND-DAUER-MATRIX (Toni 26.08.). Derselbe Tag mit
            // verschiedenen Fensterdauern - Band konstant aus der Politik
            // des Trails.
            //
            // WAS DIE DAUER VERLAENGERT, gehoert beim Lesen der Ausgabe
            // mitgedacht: nicht nur das Totband, sondern GLEICHZEITIG den
            // SMB-Ratio-Deckel auf smbRatioCorrection, die Liveness-Sperre
            // und die tau-Kuerzung. Eine Differenz zwischen zwei Spuren ist
            // deshalb keine Totband-Wirkung, sondern die Summe aus dreien.
            //
            // RUECKKOPPLUNGSBLIND: die BG-Reihe ist EINGANG und bleibt in
            // allen Spuren dieselbe. Belastbar sind Insulinmengen, Block-
            // Gruende und Zeitpunkte. Ein BG-Verlauf oder ein Peak steht
            // hier NICHT zur Verfuegung und darf aus diesen Dateien auch
            // nicht abgeleitet werden - er entstand unter der aufgezeichneten
            // 45-Minuten-Dosierung.
            //
            // FUSE_REPLAY_REBOUND=blind faehrt zweimal dieselbe Dauer. Jede
            // Differenz daraus ist ein Rig-Artefakt der Lauf-Reihenfolge und
            // KEINE Wirkung der Einstellung - die Probe gehoert vor jede
            // Aussage ueber gemessene Unterschiede.
            if (reboundEnv == "blind") {
                lauf("blindA", null, fenster = 10)
                lauf("blindB", null, fenster = 10)
                return
            }
            val dauern = reboundEnv.split(",").map { it.trim().toInt() }
            for (d in dauern) {
                reboundFensterMin = d
                lauf("reb%03d".format(d), null, fenster = 10)
            }
            reboundFensterMin = 45
            return
        }
        val ctxEnv = System.getenv("FUSE_REPLAY_DOSING_CONTEXT")
        if (ctxEnv != null) {
            // B3: ZENTRALE DOSIERPROFILE OFFLINE. ctxbase ist die
            // Aufzeichnung (LEGACY, wie am Geraet gefahren); je Variante
            // ein Lauf mit vollem Kandidatensatz, z.B.
            //   FUSE_REPLAY_DOSING_CONTEXT=corrExp=2.5,mealExp=6.0,corrRatio=1.0,mealRatio=1.0;corrExp=3.0,...
            // (mealBgMin=/mealArm= optional je Variante). Rueckkopplungs-
            // blind: nach der ersten Dosisdivergenz sind Summen nur
            // Obergrenzen; belastbar sind ZEITPUNKT, RICHTUNG und die
            // CSV-Attribution (dosingProfil/expo*/bgMinQuelle) der ersten
            // Abweichung.
            lauf("ctxbase", null, fenster = 10)
            ctxEnv.split(";").forEachIndexed { idx, variante ->
                lauf("ctx%02d".format(idx + 1), null, fenster = 10, dosingKandidat = variante.trim())
            }
            return
        }
        // FUSE_REPLAY_CAPS (v23-Ratio-Matrix des Alt-Deckels) ist mit dem
        // CENTRAL-only-Cleanup entfernt - Ratio-Kandidaten laufen ueber
        // FUSE_REPLAY_DOSING_CONTEXT (corrRatio=/mealRatio=).
        run {
            lauf("w18", null, livenessStart = false) // Tor: aufzeichnungstreu (22.08. hatte bis 21:50 keinen Kanal)
            lauf("w10ref", null, fenster = 10)
            lauf("w10up", null, "UP", fenster = 10)
            lauf("w10p2", null, "DOWN_P2", fenster = 10)
            lauf("w10p3", null, "DOWN_P3", fenster = 10)
        }
    }






    /** Fall-Lage des Zero-Latch: stetiger, langsamer Fall mit Bolus an
     *  Bord - das Low-Tor eroeffnet berechtigt, und die Frage ist, was
     *  mit der Null passiert, wenn das Verdikt zwischendurch wegfaellt. */
    private fun latchLage(dir: File, an: Boolean, knick2: Double? = null, knick2Ab: Int? = null): FuseLedgerAdapter {
        zeroLatchAn = an
        zeroLatchRuheZyklen = 60
        zeroLatchRuheAbstand = 40.0
        livenessAn = false
        markerAuthorized = false
        fundamentAn = false
        tailGuard = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        knick2AbMin = knick2Ab
        steigungNachKnick2 = knick2 ?: 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        return adapter
    }

    /**
     * ZERO-LATCH-PFLICHTTESTS 1/7/8 (Bauauftrag Toni 24.08. abends): eine
     * berechtigt eroeffnete Null bleibt durch die Fall-Episode verriegelt -
     * auch in Zyklen, in denen das Verdikt auf NONE faellt (NOT_FALLING im
     * flachen Zwischenstueck; der heutige Wegwerf-Moment). Ohne Schalter
     * bricht die Null dort wie bisher ab. Der SMB-Pfad ist in beiden
     * Laeufen bitgleich - der Latch fasst NUR die Basalachse an.
     */
    @Test
    fun `zero-latch haelt die null durch die fall-episode`(@TempDir dir: File) {
        latchLage(File(dir, "aus"), an = false, knick2 = -1.2, knick2Ab = 28)
        val ohne = (0 until 45).map { cycle() }
        latchLage(File(dir, "an"), an = true, knick2 = -1.2, knick2Ab = 28)
        val mit = (0 until 45).map { cycle() }

        val zuendung = mit.indexOfFirst { it.zeroLatchActive }
        assertTrue(zuendung > 0, "das Low-Tor muss den Latch zuenden")
        // Ab der Zuendung bleibt die TBR-Achse null - JEDER Zyklus.
        (zuendung until mit.size).forEach { i ->
            assertEquals(FuseController.TbrAction.ZERO_TEMP, mit[i].decision.tbr,
                "Zyklus $i muss verriegelt null bleiben (Grund=${mit[i].zeroLatchReason})")
        }
        // Ohne Latch wirft das flache Zwischenstueck die Null weg (heutiges
        // Verhalten): mindestens ein Nach-Zuendungs-Zyklus ohne ZERO_TEMP.
        val ohneWurf = (zuendung until ohne.size).any { ohne[it].decision.tbr != FuseController.TbrAction.ZERO_TEMP }
        assertTrue(ohneWurf, "ohne Latch muss die Null zwischenzeitlich fallen")
        // Pflicht 8: der positive Pfad ist bitgleich - nur die TBR differiert.
        mit.indices.forEach { i ->
            assertEquals(ohne[i].decision.smbU, mit[i].decision.smbU, 1e-9, "smb bitgleich (Zyklus $i)")
        }
        // Pflicht 7: am Ende ist die Lage ein gemessenes Tief - der Latch
        // haelt unabhaengig vom errechneten Nutzen.
        assertTrue(mit.takeLast(5).all { it.decision.tbr == FuseController.TbrAction.ZERO_TEMP })
    }

    /**
     * v29 (Tonis 21:58-Grenzfall): das Fall-Verdikt zuendet erst nach ZWEI
     * aufeinanderfolgenden qualifizierenden Zyklen - der EINZELNE
     * Verdikt-Zyklus verriegelt nicht mehr (Sensorzacken-Schutz). Die
     * Grenzfall-Messgroessen (Ueberdeckungsmarge, Horizontkanten-Abstand)
     * stehen im Verdikt-Zyklus im Export; eine Mindestmarge wird NICHT
     * geraten.
     */
    @Test
    fun `zero-latch zuendet erst nach zwei qualifizierenden zyklen`(@TempDir dir: File) {
        latchLage(dir, an = true, knick2 = -1.2, knick2Ab = 28)
        val laufe = (0 until 45).map { cycle() }
        val ersterVerdikt = laufe.indexOfFirst {
            it.lowThreat?.verdict ==
                app.aaps.fuse.core.controller.LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE
        }
        assertTrue(ersterVerdikt >= 0, "die Lage muss das Fall-Verdikt liefern")
        assertFalse(laufe[ersterVerdikt].zeroLatchActive, "der EINZELNE Verdikt-Zyklus verriegelt nicht")
        assertEquals(1, laufe[ersterVerdikt].zeroLatchArmStreak, "Ausloese-Zaehler 1/2")
        // Tonis Korrektur: auch die ERSTE Zero-TBR wartet - der einzelne
        // Grenzzyklus setzt noch gar keine Null.
        assertTrue(
            laufe[ersterVerdikt].decision.tbr != FuseController.TbrAction.ZERO_TEMP,
            "Zyklus 1: KEINE Zero-TBR (${laufe[ersterVerdikt].decision.tbr})",
        )
        val zuendung = laufe.indexOfFirst { it.zeroLatchActive }
        assertEquals(ersterVerdikt + 1, zuendung, "Zuendung genau einen Zyklus spaeter")
        assertTrue(laufe[zuendung].zeroLatchArmStreak >= 2, "und erst mit 2/2")
        assertEquals(
            FuseController.TbrAction.ZERO_TEMP, laufe[zuendung].decision.tbr,
            "Zyklus 2: Null und Latch gemeinsam",
        )
        // Die Messfelder des Grenzfalls stehen im Trail.
        val lt = laufe[ersterVerdikt].lowThreat!!
        assertTrue((lt.overcoverageMarginMgdl ?: -1.0) > 0.0, "Ueberdeckungsmarge exportiert")
        assertTrue(lt.horizonMarginMin != null, "Horizontkanten-Abstand exportiert")
    }

    /** v29: jeder Unterbrechungs- oder Lueckenzyklus nullt den
     *  Ausloese-Zaehler - zwei Verdikt-Zyklen MIT Luecke dazwischen
     *  zuenden nicht als Paar, sondern beginnen neu. */
    @Test
    fun `eine unterbrechung nullt den ausloese-zaehler`(@TempDir dir: File) {
        latchLage(dir, an = true, knick2 = -1.2, knick2Ab = 28)
        var erster: FuseCycleRunner.Outcome? = null
        repeat(45) {
            if (erster == null) {
                val o = cycle()
                if (o.lowThreat?.verdict ==
                    app.aaps.fuse.core.controller.LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE
                ) erster = o
            }
        }
        assertEquals(1, erster!!.zeroLatchArmStreak, "erster Verdikt-Zyklus: 1/2")
        assertFalse(erster!!.zeroLatchActive)
        // LUECKE MIT EINGEFRORENEM ZAEHLER: Abort-Zyklen (ungueltiges IOB)
        // erreichen die Latch-Stage nicht - der Zaehler friert bei 1, und
        // NUR die 90-s-Anschlussregel verhindert, dass der naechste
        // Verdikt-Zyklus Minuten spaeter als "zweiter in Folge" zaehlt.
        iobGueltig = false
        repeat(3) { cycle() }
        iobGueltig = true
        var nachLuecke: FuseCycleRunner.Outcome? = null
        repeat(15) {
            if (nachLuecke == null) {
                val o = cycle()
                if (o.lowThreat?.verdict ==
                    app.aaps.fuse.core.controller.LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE
                ) nachLuecke = o
            }
        }
        assertTrue(nachLuecke != null, "nach der Luecke muss das Verdikt wiederkommen")
        assertEquals(1, nachLuecke!!.zeroLatchArmStreak, "nach der Luecke beginnt der Zaehler neu")
        assertFalse(nachLuecke!!.zeroLatchActive, "kein Zuenden aus zwei Zyklen mit Luecke")
        assertTrue(
            nachLuecke!!.decision.tbr != FuseController.TbrAction.ZERO_TEMP,
            "und auch KEINE Zero-TBR aus dem Einzelzyklus nach der Luecke",
        )
    }

    /** v29: MEASURED_LOW verriegelt weiter SOFORT - die Zwei-Zyklen-Regel
     *  gilt nur fuer das Fall-Verdikt. Lage ohne Bolusdeckung: das
     *  Fall-Verdikt kann nie entstehen, nur das gemessene Tief. */
    @Test
    fun `measured_low verriegelt weiter sofort`(@TempDir dir: File) {
        latchLage(dir, an = true)
        flach = 92.0
        steigungProMin = -2.0
        knickAbMin = null
        bolusIobU = null // keine Ueberdeckung -> nie FALLING_WITH_BOLUS_OVERCOVERAGE
        val laufe = (0 until 25).map { cycle() }
        assertTrue(
            laufe.none {
                it.lowThreat?.verdict ==
                    app.aaps.fuse.core.controller.LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE
            },
            "die Lage darf das Fall-Verdikt nie tragen",
        )
        val low = laufe.indexOfFirst {
            it.lowThreat?.verdict == app.aaps.fuse.core.controller.LowThreatGate.Verdict.MEASURED_LOW
        }
        assertTrue(low >= 0, "die Lage muss das gemessene Tief erreichen")
        assertTrue(laufe[low].zeroLatchActive, "MEASURED_LOW verriegelt im SELBEN Zyklus")
        assertEquals(
            FuseController.TbrAction.ZERO_TEMP, laufe[low].decision.tbr,
            "und die Null steht sofort",
        )
    }

    /**
     * Pflichttests 2/3/5: eine Scheinwende (zwei positive Zyklen, dann
     * erneuter Fall) loest den Latch NICHT und nullt den Zaehler; erst die
     * anhaltende gemessene Erholung (drei lueckenlose Zyklen UKF >= +0,20
     * mit nicht weiter fallendem q1) gibt das Profilbasal frei.
     */
    @Test
    fun `zero-latch loest erst nach bestaetigter erholung`(@TempDir dir: File) {
        // Fall 25 min, dann DAUERHAFTER Anstieg +2: nach dem UKF-Umschwung
        // loest die Drei-Zyklen-Bestaetigung.
        latchLage(File(dir, "erholung"), an = true, knick2 = 2.0, knick2Ab = 26)
        val laufE = (0 until 45).map { cycle() }
        val zuendung = laufE.indexOfFirst { it.zeroLatchActive }
        assertTrue(zuendung > 0, "Zuendung noetig")
        val geloest = laufE.indexOfFirst { it.zeroLatchReason == "RECOVERED" }
        assertTrue(geloest > zuendung, "die Erholung muss den Latch loesen: " +
            laufE.mapIndexed { i, o -> "$i:${o.zeroLatchReason}" }.filterIndexed { i, _ -> i >= zuendung }.take(25).joinToString(" "))
        assertTrue(laufE.drop(geloest + 1).take(5).all { !it.zeroLatchActive }, "danach frei")

        // Scheinwende: Anstieg nur 2 Zyklen, dann weiter fallend -> haelt.
        transportReset()
        latchLage(File(dir, "schein"), an = true, knick2 = -1.0, knick2Ab = 28)
        // knick 25->28 = 3 min flach; dieses Fenster erzeugt einzelne nicht
        // fallende Zyklen, aber keine drei bestaetigten 0,20er.
        val laufS = (0 until 40).map { cycle() }
        val z2 = laufS.indexOfFirst { it.zeroLatchActive }
        assertTrue(z2 > 0)
        assertTrue((z2 until laufS.size).all { laufS[it].zeroLatchActive },
            "die Scheinwende darf nicht loesen: " + laufS.drop(z2).map { it.zeroLatchReason }.distinct())
    }

    /**
     * Pflichttest 4 (Zero-Falle): stabilisiert sich der BG weit genug ueber
     * dem Boden und ist keine Bolus-Ueberdeckung mehr da, loest der
     * Ruhe-Ausgang die Null auch ohne Anstieg - und mit zu kleinem Abstand
     * loest er NICHT (Gegenprobe der Abstandsbedingung).
     */
    @Test
    fun `zero-latch ruhe-ausgang loest ohne anstieg`(@TempDir dir: File) {
        latchLage(File(dir, "ruhe"), an = true)
        zeroLatchRuheZyklen = 6
        zeroLatchRuheAbstand = 30.0
        // Fall bis ~110 (Minute 25), dann exakt flach; die Ueberdeckung
        // verschwindet mit dem Bolus-IOB.
        var geloestBei = -1
        val outs = (0 until 45).map { i ->
            if (i == 26) bolusIobU = 0.0
            val o = cycle()
            if (geloestBei < 0 && o.zeroLatchReason == "CALM_RECOVERED") geloestBei = i
            o
        }
        assertTrue(outs.any { it.zeroLatchActive }, "Zuendung noetig")
        assertTrue(geloestBei > 0, "der Ruhe-Ausgang muss loesen: " +
            outs.takeLast(12).joinToString(" ") { "${it.zeroLatchReason}/${it.zeroLatchCalmStreak}" })
        assertTrue(outs.drop(geloestBei + 1).take(5).all { !it.zeroLatchActive })

        // Gegenprobe: derselbe Verlauf, aber der Abstand reicht nicht.
        transportReset()
        latchLage(File(dir, "ruheEng"), an = true)
        zeroLatchRuheZyklen = 6
        zeroLatchRuheAbstand = 55.0 // Plateau ~110 = Boden+40 < 55 -> zaehlt nie
        val outs2 = (0 until 45).map { i ->
            if (i == 26) bolusIobU = 0.0
            cycle()
        }
        assertTrue(outs2.any { it.zeroLatchActive })
        assertTrue(outs2.none { it.zeroLatchReason == "CALM_RECOVERED" },
            "mit zu kleinem Abstand darf der Ruhe-Ausgang nicht loesen")
    }

    /**
     * Pflichttest 6: der Riegel selbst ist restartfest, die Erholungsserie
     * bewusst nicht - nach einem Neustart bleibt die Null verriegelt und
     * die Bestaetigung beginnt von vorn (konservative Richtung).
     */
    @Test
    fun `zero-latch ist restartfest`(@TempDir dir: File) {
        val lage = File(dir, "a")
        val adapter = latchLage(lage, an = true)
        repeat(30) { cycle() }
        assertTrue(ledger.episodes.zeroLatch.active, "Zuendung noetig")
        assertTrue(adapter.persistVerified(lage), "versiegeln")
        assertEquals(true, nachNeustart(lage).zeroLatch.active, "der Riegel ueberlebt den Neustart")
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(lage, "test-epoch", clock) })
        val o = cycle()
        assertEquals(FuseController.TbrAction.ZERO_TEMP, o.decision.tbr, "verriegelt auch nach Neustart")
    }

    /**
     * Pflicht 8 strukturell: der latchZeroOnly-Weg des Translators laesst
     * den SMB-Anteil einer NUR vom Latch stammenden Null unberuehrt -
     * waehrend ein echter Sicherheits-Nullbefund ihn weiterhin sperrt.
     */
    @Test
    fun `latchZeroOnly laesst den smb-anteil frei`() {
        val d = FuseController.Decision(
            smbU = 0.30, tbr = FuseController.TbrAction.ZERO_TEMP,
            block = FuseController.Block.NONE, insulinReqU = 1.0,
            predAtReleaseMgdl = null, minLowerMgdl = null,
            bindingLimit = "test", desiredBeforeStepU = 0.30,
        )
        val cfgT = app.aaps.fuse.core.controller.TbrPolicy.Config(basalStepUPerH = 0.05)
        fun combineMit(latchOnly: Boolean) = FuseTbrTranslator.combine(
            decision = d, current = null, scheduledBasalUPerH = 0.6, cfg = cfgT,
            latchZeroOnly = latchOnly,
        )
        assertEquals(0.30, combineMit(true).decision.smbU, 1e-9, "Latch-Null laesst den SMB frei")
        assertEquals(0.0, combineMit(false).decision.smbU, 1e-9, "echter Sicherheitsbefund sperrt weiter")
    }

    /**
     * DIE GEMESSENE V-KURVE DES PFLICHTFALLS (25.08., 06:05-06:40), roh
     * aus dem Geraete-Trail uebernommen: flach 137-139, Sturz auf 101
     * (UKF-Minimum -2,81 um 06:16), steile Erholung auf 149, danach
     * FLACH. Synthetische Formen taugen hier nicht: ein DAUER-Anstieg
     * erfuellt die Kinematik-Bedingung des Mahlzeitenfensters (r und
     * UKF beide ueber der Rampen-Unterkante) und nimmt dem Riegel den
     * autoritativen Kontext - die echte Kurve traegt genau die Lage,
     * um die es geht: UKF +4,0 bei robustem r noch -0,82.
     */
    private val vKurveRoh = listOf(
        137.0, 139.0, 139.0, 136.0, 132.0, 126.0, 120.0, 114.0, 111.0, 109.0,
        108.0, 105.0, 103.0, 101.0, 105.0, 113.0, 125.0, 136.0, 142.0, 146.0,
        149.0, 149.0, 149.0, 148.0, 147.0, 147.0, 147.0, 147.0, 147.0, 147.0,
        147.0, 146.0, 144.0, 144.0, 143.0, 142.0,
    )

    /**
     * V-REVERSAL-LAGE: die gemessene Kurve, ohne Marker, ohne Fundament,
     * IOB 0 (die Ueberdeckung soll NICHT der Grund fuers Nichtdosieren
     * sein). Der Vorlauf haelt den Rohpuffer gefuellt.
     */
    private fun reversalLage(dir: File) {
        zeroLatchAn = false
        livenessAn = false
        markerAuthorized = false
        markerAt = 0L
        fundamentAn = false
        tailGuard = true
        // GERAETEPOLITIK des Pflichtfalls (aus dem Trail der Vorfallszeit):
        // die Rampen-Unterkante setzt die Kontextgrenze, das W10-Fenster
        // die Geschwindigkeit, mit der das robuste r der Wende folgt. Mit
        // dem Rig-Default W18 traegt der Sturz so lange nach, dass
        // Prognose-Boden und Kontextwechsel zusammenfallen und die zu
        // pruefende Lage gar nicht entsteht.
        riseRampLowRWert = 1.5
        theilSenFensterMin = 10
        // Den Guard-Boden ausdruecklich OEFFNEN: er ist eine ANDERE
        // Verteidigung und wuerde in den Riegel-Zyklen mitbinden - dann
        // waere die verhinderte Dosis nicht dem Riegel zuzuordnen. Am
        // Geraet lag an derselben Stelle das Nachtband davor; die erste
        // reale Dosis fiel 06:27 mit dessen Ende.
        guardBodenMgdl = 40.0
        // 20 min flacher Vorlauf auf dem Startwert, dann die Messkurve.
        rohSerie = (0 until 20).map { min -> (start + min * 60_000L) to 137.0 } +
            vKurveRoh.mapIndexed { i, v -> (start + (20 + i) * 60_000L) to v }
        bolusIobU = 0.0
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
    }

    /**
     * v30-PFLICHTFALL 1 (06:27-06:33): nach dem steilen Fall traegt die
     * schnelle Gegenbewegung keine Korrektur-SMBs, solange das robuste r
     * negativ oder unbestaetigt ist. NUR die Menge ohne Grant faellt; die
     * TBR-Achse ist in beiden Laeufen bitgleich. Nach der r-Bestaetigung
     * fliesst es wieder - kein Carry, keine globale r/UKF-Verschaerfung.
     */
    @Test
    fun `correction-reversal-guard blockt die v-erholung bis r bestaetigt`(@TempDir dir: File) {
        reversalLage(File(dir, "aus"))
        reversalAn = false
        val ohne = (0 until 56).map { cycle() }
        reversalLage(File(dir, "an"))
        reversalAn = true
        val mit = (0 until 56).map { cycle() }

        // Vorbedingung: die Lage dosiert ueberhaupt (sonst ist der Test leer).
        val dosierwunsch = ohne.indices.filter { ohne[it].decision.smbU > 0.0 }
        assertTrue(dosierwunsch.isNotEmpty(), "die Gegenbewegung muss ohne Guard SMBs ausloesen - " +
            ohne.mapIndexed { i, o -> "$i:${"%.2f".format(o.decision.smbU)}" }.joinToString(" "))

        // Der Riegel muss in dieser Lage RECHNEN und BLOCKEN: schneller
        // Gegenzug bei negativem/unbestaetigtem r - der 06:27-Kern.
        val blockZyklen = mit.indices.filter { mit[it].correctionReversal?.blocks == true }
        assertTrue(blockZyklen.isNotEmpty()) {
            "die V-Erholung muss den Riegel tragen - " + mit.indices.joinToString(" ") {
                "$it:${mit[it].correctionReversal?.reason}/${mit[it].correctionContextReason}"
            }
        }
        assertTrue(blockZyklen.any { mit[it].correctionReversal?.reason == "REVERSAL_R_NEGATIVE" },
            "mindestens ein Block bei noch NEGATIVEM r (der Vorfallskern)")
        blockZyklen.forEach { i ->
            assertEquals(0.0, mit[i].decision.smbU, 1e-9,
                "Block-Zyklus $i traegt keinen SMB (binding=${mit[i].decision.bindingLimit})")
            assertTrue(mit[i].correctionContext, "reiner Korrekturkontext (Zyklus $i)")
            assertEquals("PURE_CORRECTION", mit[i].correctionContextReason,
                "der Riegel greift nur im autoritativ reinen Korrekturkontext (Zyklus $i)")
        }
        // Die Wirkung ist real: mindestens ein Block-Zyklus, in dem der
        // ungeschuetzte Lauf dosiert haette - typisiert im Limit.
        val verhindert = blockZyklen.filter { ohne[it].decision.smbU > 0.0 }
        assertTrue(verhindert.isNotEmpty(), "der Riegel muss reale Dosen verhindern - ohne-Lauf: " +
            dosierwunsch.joinToString(" ") { "$it:${"%.2f".format(ohne[it].decision.smbU)}" })
        verhindert.forEach { i ->
            assertTrue(mit[i].decision.bindingLimit.contains("REVERSAL_"),
                "Block-Zyklus $i ohne typisierten Grund: ${mit[i].decision.bindingLimit}")
        }
        // Ungeblockte Zyklen sind bitgleich; die TBR-Achse IMMER.
        mit.indices.forEach { i ->
            if (i !in blockZyklen) assertEquals(ohne[i].decision.smbU, mit[i].decision.smbU, 1e-9, "smb bitgleich (Zyklus $i)")
            assertEquals(ohne[i].decision.tbr, mit[i].decision.tbr, "die TBR-Achse bleibt unberuehrt (Zyklus $i)")
        }

        // FREIGABE: danach fliesst es auch im Guard-Lauf - kein Carry.
        // Der Ausgang ist entweder die r-Bestaetigung oder der
        // Kontextwechsel (die gemessene Kurve nimmt den zweiten Weg:
        // r 1,11 bei UKF 3,52 erfuellt die Kinematik des
        // Mahlzeitenfensters, s. Replay-Bericht).
        val frei = mit.indices.filter { it > blockZyklen.last() && mit[it].decision.smbU > 0.0 }
        assertTrue(frei.isNotEmpty()) {
            "nach dem Riegel muss die Korrektur wieder fliessen - " +
                mit.indices.joinToString(" ") { "$it:${mit[it].correctionReversal?.reason}/${"%.2f".format(mit[it].decision.smbU)}" }
        }
    }

    /**
     * PFLICHTPRUEFUNG 5 (Tonis Nachforderung 25.08. abends): ein NUR
     * KINEMATISCH vermutetes Mahlzeitenfenster nimmt dem Riegel den
     * Schutz NICHT.
     *
     * DAS WAR DER ARCHITEKTURFEHLER: `ExpectationContext.MEAL` wirft die
     * belegte Mahlzeit (Marker, Evidenz) und die bloss vermutete (r/UKF
     * ueber der Rampenkante) zusammen. Genau als vermutete Mahlzeit wird
     * die Erholung eines Sensor-V eingestuft - ein Schutz, der jedes
     * `MEAL` ausnimmt, kann den Vorfall vom 25.08. konstruktiv nie
     * verhindern. Der Riegel wird hier absichtlich lang scharf gestellt
     * (sechs Bestaetigungszyklen), damit die Kurve ins Fenster laeuft,
     * WAEHREND er noch traegt.
     */
    @Test
    fun `der v-riegel traegt auch im nur kinematischen mahlzeitenfenster`(@TempDir dir: File) {
        reversalLage(dir)
        reversalAn = true
        reversalConfirmWert = 6 // laenger scharf als die Kurve braucht
        val outs = (0 until 56).map { cycle() }

        val kinematisch = outs.filter { it.correctionMealBasis == "KINEMATIC_ONLY" }
        assertTrue(kinematisch.isNotEmpty()) {
            "die Lage muss in ein kinematisches Fenster laufen - " +
                outs.mapNotNull { it.correctionMealBasis }.distinct().joinToString(" ")
        }
        // Der Kontext ist MEAL, die Basis aber nur Kinematik - hier MUSS
        // der Schutz weiterarbeiten duerfen.
        val imFenster = kinematisch.filter {
            it.correctionContextReason == "MEAL_WINDOW_OPEN" || it.correctionContextReason == "ONSET_ACTIVE"
        }
        assertTrue(imFenster.isNotEmpty(), "genau die Fenster-Zyklen sind gemeint")
        imFenster.forEach { o ->
            assertTrue(o.correctionContext) {
                "kinematische Mahlzeit ist KEIN Ausschluss fuer den Korrekturschutz " +
                    "(Grund=${o.correctionContextReason}, Basis=${o.correctionMealBasis})"
            }
        }
        // Und er traegt dort auch wirklich: mindestens ein Block-Zyklus
        // liegt im kinematischen Fenster.
        val blockImFenster = imFenster.filter { it.correctionReversal?.blocks == true }
        assertTrue(blockImFenster.isNotEmpty()) {
            "der Riegel muss im kinematischen Fenster tragen - " +
                imFenster.joinToString(" ") { "${it.correctionContextReason}/${it.correctionReversal?.reason}" }
        }
        blockImFenster.forEach { o ->
            assertEquals(0.0, o.decision.smbU, 1e-9, "und dort keine Dosis tragen")
            assertTrue(o.decision.bindingLimit.contains("REVERSAL_"))
        }
    }

    /**
     * PFLICHTPRUEFUNG 4 im vollen Pfad: eine BELEGTE Mahlzeit nimmt den
     * Schutz heraus, auch waehrend parallel Onset und Fenster offen sind.
     *
     * DEN REINEN MASKIERUNGSFALL - Grund nennt `ONSET_ACTIVE`, Basis
     * traegt `EVIDENCE_CONFIRMED` - prueft
     * `ExpectationLedgerTest.die Mahlzeitenbasis wird von Onset und
     * Fenster nicht verdeckt` an der Klassifikation selbst; im Rig laesst
     * er sich nicht herstellen, weil eine Evidenzepisode ohne Marker eine
     * gewachsene Absorptionsgeschichte braucht. An ECHTEN Zyklen deckt
     * ihn der Mahlzeiten-Gegenlauf ab (22.08.: 231 EVIDENCE_ACTIVE-Zyklen,
     * kein einziger Riegel-Tag).
     */
    @Test
    fun `eine belegte mahlzeit hinter der kinematik nimmt den schutz heraus`(@TempDir dir: File) {
        reversalLage(dir)
        reversalAn = true
        reversalConfirmWert = 6
        markerAuthorized = true
        // Der Marker faellt in die Erholung: ab dort ist die Mahlzeit
        // BELEGT, auch wenn parallel Onset/Fenster offen sind.
        val outs = (0 until 56).map { i ->
            if (i == 38) markerAt = clock + 60_000L
            cycle()
        }
        val belegt = outs.drop(39).filter {
            it.correctionMealBasis == "MARKER_CONFIRMED" || it.correctionMealBasis == "EVIDENCE_CONFIRMED"
        }
        assertTrue(belegt.isNotEmpty()) {
            "die Lage muss belegte Mahlzeit tragen - " +
                outs.drop(39).mapNotNull { it.correctionMealBasis }.distinct().joinToString(" ")
        }
        // Die Kinematik laeuft parallel weiter (das V klingt aus) - der
        // Beleg gewinnt trotzdem.
        belegt.forEach { o ->
            assertFalse(o.correctionContext) {
                "belegte Mahlzeit nimmt den Schutz heraus (Grund=${o.correctionContextReason}, " +
                    "Basis=${o.correctionMealBasis})"
            }
            assertTrue(o.correctionReversal?.blocks != true)
            assertFalse(o.decision.bindingLimit.contains("REVERSAL_"))
        }
    }

    /**
     * v30-PFLICHTFALL P0.1 (Review 25.08. abends): der Riegel ist
     * RESTARTFEST. Ein Neustart mitten in der V-Episode darf nicht mehr
     * Insulin erlauben - die Identitaet (Fall-Minimum, Zuendung) kommt
     * aus dem Ledger zurueck, nur die r-Bestaetigung beginnt neu
     * (konservative Richtung, wie beim Zero-Latch).
     */
    @Test
    fun `der reversal-riegel ueberlebt den neustart`(@TempDir dir: File) {
        val lage = File(dir, "restart")
        reversalLage(lage)
        reversalAn = true
        // Bis in die gezuendete Episode fahren.
        val vorher = (0 until 41).map { cycle() }
        val geblockt = vorher.indexOfLast { it.correctionReversal?.blocks == true }
        assertTrue(geblockt > 0, "die Episode muss stehen")
        assertTrue(ledger.episodes.correctionReversal.reboundSeenTs > 0L, "die Zuendung ist im Ledger")
        assertTrue(ledger.persistVerified(lage), "versiegeln")

        // Neustart: aus der Datei, nicht aus dem Speicher.
        val wieder = nachNeustart(lage)
        assertEquals(ledger.episodes.correctionReversal.minUkf, wieder.correctionReversal.minUkf, 1e-9,
            "das Fall-Minimum ueberlebt")
        assertEquals(ledger.episodes.correctionReversal.reboundSeenTs, wieder.correctionReversal.reboundSeenTs,
            "die Zuendung ueberlebt")
        assertEquals(0, wieder.correctionReversal.rPosStreak, "die r-Bestaetigung beginnt neu")

        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(lage, "test-epoch", clock) })
        val o = cycle()
        assertTrue(o.correctionReversal?.blocks == true,
            "nach dem Neustart traegt der Riegel weiter (Grund=${o.correctionReversal?.reason})")
        assertEquals(0.0, o.decision.smbU, 1e-9, "und laesst keine Dosis durch")
    }

    /**
     * v30-PFLICHTFALL P0.1 fuer den Nachlauf: auch der Freigabe-Anker
     * ueberlebt den Neustart - sonst oeffnete ein Neustart in den ersten
     * Minuten nach der Kante genau das, was der Nachlauf zuhaelt.
     */
    @Test
    fun `der freigabe-nachlauf ueberlebt den neustart`(@TempDir dir: File) {
        val lage = File(dir, "restartRearm")
        latchLage(lage, an = true, knick2 = 0.45, knick2Ab = 26)
        rearmUpUkfWert = 0.15
        rearmAn = true
        var geloest = -1
        (0 until 60).forEachIndexed { i, _ ->
            val o = cycle()
            if (geloest < 0 && o.zeroLatchReason == "RECOVERED") {
                geloest = i
                bolusIobU = 0.0
            }
            // Direkt nach der Loesung, IM Nachlauf, versiegeln.
            if (geloest > 0 && i == geloest + 1) {
                assertTrue(ledger.episodes.correctionRearm.ankerTs > 0L, "der Anker steht im Ledger")
                assertTrue(ledger.persistVerified(lage), "versiegeln")
                val wieder = nachNeustart(lage)
                assertEquals(ledger.episodes.correctionRearm.ankerTs, wieder.correctionRearm.ankerTs,
                    "der Anker ueberlebt")
                assertEquals(
                    PositiveCorrectionRearm.Source.ZERO_LATCH_RELEASED, wieder.correctionRearm.quelle,
                    "die Quelle ueberlebt",
                )
                assertEquals(0, wieder.correctionRearm.upStreak, "der Aufwaerts-Zaehler beginnt neu")
                neuerRunner(FuseLedgerAdapter().also { a -> a.loadOnce(lage, "test-epoch", clock) })
                val nach = cycle()
                assertTrue(nach.correctionRearm?.blocks == true,
                    "nach dem Neustart haelt der Nachlauf (Grund=${nach.correctionRearm?.reason})")
                assertEquals(0.0, nach.decision.smbU, 1e-9)
                return
            }
        }
        throw AssertionError("die Erholung muss den Latch loesen")
    }

    /**
     * v30-PFLICHTFALL P0.2 (Review 25.08. abends): der Riegel liest den
     * Kontext aus der AUTORITATIVEN Klassifikation. Geprueft wird die
     * Ableitung selbst - dieselbe Funktion, die den exportierten
     * ExpectationContext bestimmt: eine ACTIVE Evidenzepisode ohne
     * Marker/Onset/Fenster ist MEAL (EVIDENCE_ACTIVE), nicht Korrektur.
     * Genau das trennt die neue Ableitung von der alten, zweitgefuehrten
     * Rekonstruktion (Marker-Fenster + Marker-Frist + Fundament-Phase),
     * die diese Lage faelschlich als Korrektur gelesen haette.
     *
     * Der VOLLE Pfad ueber echte Zyklen liegt im Replay: der
     * Mahlzeitentag 22.08. traegt 10:50-11:45 EVIDENCE_ACTIVE nach
     * abgelaufener Markerfrist, und der Guards-Lauf setzt dort keinen
     * einzigen Riegel-Tag (s. guards_analyse).
     */
    @Test
    fun `aktive evidenz-mahlzeit ist kein korrekturkontext`() {
        fun lage(phase: EvidenceStock.Phase) = ExpectationLedger.situationOf(
            mealMarkerActive = false,
            evidenceEpisodeId = 42L,
            evidencePhase = phase,
            onsetActive = false,
            mealWindow = false,
            reboundWindow = false,
            signalHealthy = true,
            ledgerSealed = true,
        )
        // Die Markerfrist ist abgelaufen (kein Marker, kein Onset, kein
        // Fenster) - allein die lebende Evidenz traegt die Lage.
        val aktiv = ExpectationLedger.classify(lage(EvidenceStock.Phase.ACTIVE))
        assertEquals(ExpectationLedger.ExpectationContext.MEAL, aktiv.context,
            "eine aktive Evidenz-Mahlzeit ist KEIN Korrekturkontext")
        assertEquals(ExpectationLedger.ContextReason.EVIDENCE_ACTIVE, aktiv.reason)
        val versiegelnd = ExpectationLedger.classify(lage(EvidenceStock.Phase.PENDING_SEAL))
        assertEquals(ExpectationLedger.ExpectationContext.MEAL, versiegelnd.context)
        // Gegenprobe: ruht die Episode, ist es wieder reine Korrektur -
        // der Riegel darf dort arbeiten.
        val ruhend = ExpectationLedger.classify(lage(EvidenceStock.Phase.DORMANT))
        assertEquals(ExpectationLedger.ExpectationContext.CORRECTION, ruhend.context)
        assertEquals(ExpectationLedger.ContextReason.PURE_CORRECTION, ruhend.reason)
    }

    /**
     * v30-GEGENPROBE (Tonis Auflage): ein ECHTER frueher Mahlzeitenanstieg
     * darf nicht gebremst werden. Dieselbe V-Lage, aber der Marker kommt
     * an der Wende - der Kontext faellt, kein einziger Zyklus traegt einen
     * REVERSAL-Tag, und nach dem Marker fliessen Dosen.
     */
    @Test
    fun `der marker nimmt der v-erholung den korrekturkontext`(@TempDir dir: File) {
        reversalLage(dir)
        reversalAn = true
        val outs = (0 until 56).map { i ->
            // Druck an der Wende (Kurvenminimum 101 liegt auf Zyklus 33).
            if (i == 32) markerAt = clock + 60_000L
            cycle()
        }
        assertTrue(outs.none { it.decision.bindingLimit.contains("REVERSAL_") }) {
            "Mahlzeitenpfade bleiben ausdruecklich unberuehrt: " +
                outs.first { it.decision.bindingLimit.contains("REVERSAL_") }.decision.bindingLimit
        }
        assertTrue(outs.drop(34).none { it.correctionContext }, "der Marker beendet den Korrekturkontext")
        assertEquals("MARKER_ACTIVE", outs[34].correctionContextReason,
            "die autoritative Klassifikation benennt den Marker")
        assertTrue(outs.drop(34).any { it.decision.smbU > 0.0 },
            "der Schutz darf das FCL nicht bremsen - nach dem Marker muessen Dosen fliessen")
    }

    /**
     * v30-PFLICHTFALL P1.4 (Review 25.08. abends): die Nachtband-Kante
     * ankert den Nachlauf NUR, wenn der letzte Nachtzyklus bezifferten
     * positiven Bedarf AUSSCHLIESSLICH ueber das Nachtband unterdrueckt
     * hat. Ein ruhiger Morgen (kein Bedarf im Nachtband) darf nicht
     * jeden Tag pauschal fuenf Minuten blockieren.
     */
    @Test
    fun `die nachtkante ankert nur nach unterdruecktem bedarf`(@TempDir dir: File) {
        /** Faehrt ueber die Nachtband-Kante; liefert die Zyklen danach. */
        fun ueberDieKante(unterDir: String, hoch: Boolean): List<FuseCycleRunner.Outcome> {
            zeroLatchAn = false
            livenessAn = false
            markerAuthorized = false
            markerAt = 0L
            fundamentAn = false
            tailGuard = true
            nightDeadband = true
            rearmAn = true
            reversalAn = false
            rearmUpUkfWert = 0.15
            // Der Kantenzeitpunkt: NightEndMin liegt bei 480 (08:00). Der
            // Lauf startet 10 min davor, in der Nacht.
            val mitternacht = start - (app.aaps.core.utils.MidnightUtils.secondsFromMidnight(start) * 1000L)
            clock = mitternacht + (470L * 60_000L) - 60_000L
            val laufStart = clock + 60_000L
            // HOCH: BG ueber Ziel, aber INNERHALB des Totbands
            // (Ziel 98 + 45 = 143) - genau die Lage, in der das Nachtband
            // bezifferten Bedarf unterdrueckt. Darueber greift es gar
            // nicht. RUHIG: BG unter Ziel - gar kein Bedarf.
            val basis = if (hoch) 120.0 else 88.0
            rohSerie = (0 until 60).map { min -> (laufStart - 20 * 60_000L + min * 60_000L) to (basis + 0.25 * min) }
            bolusIobU = 0.0
            transportReset()
            neuerRunner(FuseLedgerAdapter().also { it.loadOnce(File(dir, unterDir).also(File::mkdirs), "test-epoch", clock) })
            return (0 until 25).map { cycle() }
        }

        val mitBedarf = ueberDieKante("hoch", hoch = true)
        assertTrue(mitBedarf.any { it.correctionRearm?.blocks == true }) {
            "nach unterdruecktem Nachtbedarf muss die Kante ankern - " +
                mitBedarf.joinToString(" ") { "${it.decision.bindingLimit}/${it.correctionRearm?.reason}" }
        }
        assertEquals(
            PositiveCorrectionRearm.Source.NIGHT_END,
            mitBedarf.first { it.correctionRearm?.blocks == true }.correctionRearm?.source,
        )

        val ruhig = ueberDieKante("ruhig", hoch = false)
        assertTrue(ruhig.none { it.correctionRearm?.blocks == true }) {
            "ein ruhiger Morgen darf NICHT pauschal blockieren - " +
                ruhig.joinToString(" ") { "${it.decision.bindingLimit}/${it.correctionRearm?.reason}" }
        }
    }

    /**
     * v30-PFLICHTFALL 2 (08:00-08:03): die Zero-Latch-Loesung oeffnet
     * positive Korrektur-SMBs erst nach der Mindestdauer UND bestaetigter
     * Aufwaertslage - nicht in der ersten Minute nach einer Stunde
     * verriegelter Null. Ohne Schalter fliesst es sofort (Vorbedingung).
     */
    @Test
    fun `positive-correction-rearm haelt die latch-loesung zurueck`(@TempDir dir: File) {
        fun lauf(an: Boolean, unterDir: String): Pair<List<FuseCycleRunner.Outcome>, Int> {
            // +0,45/min ist die 08:00-Form: genug fuer die Latch-Loesung,
            // aber UNTER der Rampen-Unterkante - der autoritative Kontext
            // bleibt Korrektur (eine steilere Erholung oeffnet das
            // Mahlzeitenfenster kinematisch und nimmt dem Riegel den
            // Kontext). Die Bestaetigungsschwelle liegt entsprechend
            // darunter; am Geraet trug 08:00 UKF 0,84 bei r < 0,5.
            latchLage(File(dir, unterDir), an = true, knick2 = 0.45, knick2Ab = 26)
            rearmUpUkfWert = 0.15
            rearmAn = an
            reversalAn = false
            var geloestBei = -1
            val outs = (0 until 60).map { i ->
                val o = cycle()
                if (geloestBei < 0 && o.zeroLatchReason == "RECOVERED") {
                    geloestBei = i
                    bolusIobU = 0.0 // die Ueberdeckung endet mit der Loesung
                }
                o
            }
            assertTrue(geloestBei > 0, "die Erholung muss den Latch loesen ($unterDir)")
            return outs to geloestBei
        }

        val (ohne, geloestOhne) = lauf(an = false, unterDir = "aus")
        // Vorbedingung: ohne Nachlauf will der Regler im 4-Zyklen-Fenster
        // dosieren (Anker = Loesezyklus; holdMin 5 min ab Anker deckt die
        // Zyklen +1..+4, der +5te ist die Fristkante selbst).
        val fenster = (geloestOhne + 1)..(geloestOhne + 4)
        val wunsch = fenster.filter { ohne[it].decision.smbU > 0.0 }
        assertTrue(wunsch.isNotEmpty(), "ohne Nachlauf muss im Fenster dosiert werden - " +
            fenster.joinToString(" ") { "$it:${"%.2f".format(ohne[it].decision.smbU)}" })

        val (mit, geloestMit) = lauf(an = true, unterDir = "an")
        assertEquals(geloestOhne, geloestMit, "die Loesung selbst ist schalterunabhaengig")
        // Der Nachlauf traegt: im Fenster keine einzige positive Dosis -
        // insbesondere NICHT in der ersten Minute nach der Kante (08:00-Kern).
        ((geloestMit + 1)..(geloestMit + 4)).forEach { i ->
            assertEquals(0.0, mit[i].decision.smbU, 1e-9,
                "Zyklus $i liegt im Nachlauf (binding=${mit[i].decision.bindingLimit})")
            assertTrue(mit[i].correctionRearm?.blocks == true, "Zyklus $i muss als Nachlauf gerechnet sein")
            assertEquals(PositiveCorrectionRearm.Source.ZERO_LATCH_RELEASED, mit[i].correctionRearm?.source)
        }
        val getagt = ((geloestMit + 1)..(geloestMit + 4)).filter { ohne[it].decision.smbU > 0.0 }
        getagt.forEach { i ->
            assertTrue(mit[i].decision.bindingLimit.contains("REARM_"),
                "Block-Zyklus $i ohne typisierten Grund: ${mit[i].decision.bindingLimit}")
        }
        // An der Fristkante gibt die laengst bestaetigte Aufwaertslage frei.
        assertTrue(mit.drop(geloestMit + 5).any { it.decision.smbU > 0.0 },
            "nach Frist und Bestaetigung muss es wieder fliessen - " +
                mit.drop(geloestMit + 5).take(12).joinToString(" ") { "${it.correctionRearm?.reason}/${"%.2f".format(it.decision.smbU)}" })
        // Die TBR-Achse bleibt in beiden Laeufen bitgleich.
        mit.indices.forEach { i ->
            assertEquals(ohne[i].decision.tbr, mit[i].decision.tbr, "TBR bitgleich (Zyklus $i)")
        }
    }

    /**
     * MEAL/CORRECTION-BAUAUFTRAG (Toni 23.08. nachts), Profilwahl: der
     * Marker ist eine ZEITLICH BEGRENZTE Leistungsautorisierung. Kein
     * Marker -> CORRECTION; beobachteter Marker -> MEAL mit gepinnter
     * Frist; exakt an der (halb offenen) Deadline -> CORRECTION mit
     * POWER_EXPIRED, obwohl der Marker (und damit die Evidenzepisode)
     * weiterlebt; eine Dauer-Aenderung oeffnet die gepinnte Frist nicht;
     * ein zweiter Marker erneuert sie.
     */
    @Test
    fun `profilwahl folgt der gepinnten markerfrist`(@TempDir dir: File) {
        livenessLage(dir)
        repeat(6) { cycle() } // Signal-Warm-up: die ersten Zyklen aborten
        val o1 = cycle()
        assertEquals("CORRECTION", o1.livenessProfile)
        assertEquals("NO_MARKER", o1.livenessProfileReason)

        markerAt = clock // beobachteter Wechsel
        // Der ERSTE Zyklus nach dem Druck ist die Evidenz-Rebase (EXCLUDED-
        // Blip, bestehendes v18-Verhalten) - danach gilt MEAL.
        repeat(2) { cycle() }
        val o2 = cycle()
        assertEquals("MEAL", o2.livenessProfile, "Grund=${o2.livenessProfileReason}")
        assertEquals(markerAt, o2.markerPowerPinnedFor)
        assertEquals(markerAt + 120 * 60_000L, o2.markerPowerDeadlineTs)

        // Dauer-Aenderung wirkt NICHT auf die gepinnte Frist (Test 9).
        mealPowerMin = 240
        val o3 = cycle()
        assertEquals(markerAt + 120 * 60_000L, o3.markerPowerDeadlineTs, "Frist ist gepinnt")
        mealPowerMin = 120

        // Exakt an der Deadline gilt CORRECTION (halb offen, Test 3+4).
        // HINLAUFEN statt springen: ein 110-min-Sprung risse einen
        // Segmentbruch-Blip (EXCLUDED) genau in den Messzyklus.
        clock = o2.markerPowerDeadlineTs - 6 * 60_000L
        repeat(5) { cycle() }
        val o4 = cycle()
        assertEquals(o2.markerPowerDeadlineTs, o4.computeTs, "Zyklus liegt exakt auf der Deadline")
        assertEquals("CORRECTION", o4.livenessProfile)
        assertEquals("POWER_EXPIRED", o4.livenessProfileReason)

        // Zweiter Marker eroeffnet eine neue Frist (Test 5).
        markerAt = clock
        val o5 = cycle()
        assertEquals("MEAL", o5.livenessProfile)
        assertEquals(markerAt + 120 * 60_000L, o5.markerPowerDeadlineTs)
    }

    /**
     * Restart-Vertraege (Tests 7/8/15): passende persistierte Identitaet
     * setzt die Restfrist fort; ein beim Warmstart nur VORGEFUNDENER Marker
     * ohne passende Identitaet eroeffnet kein MEAL; die Frist ueberlebt den
     * Codec-Roundtrip identisch.
     */
    @Test
    fun `markerfrist ist restartfest und warmstart pinnt nie rueckwirkend`(@TempDir dir: File) {
        val lage = File(dir, "a")
        val adapter = livenessLage(lage)
        repeat(6) { cycle() } // Signal-Warm-up
        markerAt = clock
        repeat(2) { cycle() } // Evidenz-Rebase-Blip nach dem Druck
        val o = cycle()
        assertEquals("MEAL", o.livenessProfile)
        assertTrue(adapter.persistVerified(lage), "versiegeln")

        // T7+T15: Identitaet passt -> Restfrist laeuft weiter.
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(lage, "test-epoch", clock) })
        val n = cycle()
        assertEquals(o.markerPowerPinnedFor, n.markerPowerPinnedFor, "Codec-Roundtrip identisch")
        assertEquals(o.markerPowerDeadlineTs, n.markerPowerDeadlineTs)
        assertEquals("MEAL", n.livenessProfile, "Restfrist wird fortgesetzt")

        // T8 + Identitaetsvergleich: anderer Marker, nur vorgefunden (kein
        // im Prozess beobachteter Wechsel) -> KEIN rueckwirkendes MEAL.
        markerAt = clock + 60_000L
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(lage, "test-epoch", clock) })
        clock += 120_000L
        val f = cycle()
        assertEquals("CORRECTION", f.livenessProfile, "vorgefundener fremder Marker pinnt nicht")
        assertEquals("MARKER_NOT_PINNED", f.livenessProfileReason)
    }

    /**
     * Cap-Aenderung waehrend eines Laufs (Test 10): CONFIG_CHANGED beendet
     * ihn ohne Sperre, die Bewaffnung beginnt unter den neuen Werten neu.
     * Relational ungueltige Werte (Test 11) fallen fail-closed als
     * Konfigurationsfehler aus - nichts wird getauscht oder geklemmt.
     */
    @Test
    fun `cap-aenderung beendet den lauf und ungueltige relation faellt aus`(@TempDir dir: File) {
        livenessLage(dir)
        corrRatioCapZ = 0.10; mealRatioCapZ = 0.30
        var aktiv = false
        repeat(25) { val o = cycle(); if (o.livenessActive) aktiv = true }
        assertTrue(aktiv, "der Lauf muss stehen")
        corrRatioCapZ = 0.05
        val o = cycle()
        assertEquals("CONFIG_CHANGED", o.livenessExit)
        assertEquals(0L, o.livenessReArmUntilTs, "Bedienhandlung: keine Sperre")

        // Test 11: CORRECTION offener als MEAL -> fail-closed.
        corrRatioCapZ = 0.40
        val kaputt = cycle()
        assertTrue(kaputt.abortReason?.contains("correctionDemandRatioCap") == true,
            "relationale Validierung muss ablehnen: ${kaputt.abortReason}")
    }

    /**
     * CENTRAL-only-Neufassung des frueheren Profil-IOB-Deckel-Tests: einen
     * NUR-Kanal-Deckel gibt es nicht mehr - die Kontextgrenze ist der
     * GEMEINSAME Raum von Kanal und Normalpfad (P1-Fix). Liegt sie unter
     * dem Bestand, stoppt ALLES als EXPOSURE (T12-neu); das globale iobTH
     * bleibt im selben min() eine harte Obergrenze, auch wenn die
     * Kontextgrenze offen ist (T13, unveraendert).
     */
    @Test
    fun `die kontextgrenze deckelt kanal und normalpfad gemeinsam und global iobth bleibt hart`(@TempDir dir: File) {
        // T12-neu: Kontextgrenze 1,6 U unter dem Lage-IOB 4,5 -> kein
        // positives Insulin aus KEINER Quelle; typisiert STOP/EXPOSURE.
        livenessLage(dir)
        corrExpLimit = 1.6; mealExpLimit = 4.8
        var geblockt: FuseCycleRunner.Outcome? = null
        repeat(90) {
            val o = cycle()
            assertEquals(0.0, o.decision.smbU, 1e-9, "unter der Kontextgrenze fliesst nichts")
            assertEquals(0.0, o.livenessLiftU, 1e-9, "auch der Kanal nicht")
            // Viele Zyklen stoppt schon der Schwanz (STOP/TAIL, ehrlich);
            // gesucht ist ein Zyklus, in dem ein POSITIVER Vorschlag erst
            // an der Endpruefung scheitert - dort heisst der Stop EXPOSURE.
            if (o.exposureGateBlocked == true && geblockt == null) geblockt = o
        }
        val g12 = geblockt ?: error("die Endpruefung muss mindestens einen positiven Vorschlag blocken")
        assertEquals("STOP", g12.smbState)
        assertEquals("EXPOSURE", g12.smbStopReason)

        // T13: MEAL-Grenze offen (7,2), aber das globale iobTH (40 % = 3,2 U
        // < bolusIobU 4,5) bleibt im min() hart.
        transportReset()
        iobThPct = 40
        livenessLage(File(dir, "global"))
        markerAt = clock
        var global: FuseCycleRunner.Outcome? = null
        repeat(25) { val o = cycle(); if (o.livenessDenial == "NO_HEADROOM" && global == null) global = o }
        assertTrue(global != null, "das globale iobTH muss trotz MEAL-Profil deckeln")
        iobThPct = 100
    }

    /**
     * RATIO-DECKEL-VERTRAG (Toni 23.08. spaet): liveRatio = min(eff. Ratio,
     * Cap) begrenzt die GESCHWINDIGKEIT des Kanals je Zyklus. Default 1.0
     * ist nicht bindend (die Fall-1-bis-5-Tests laufen unveraendert mit
     * dem Default - das ist der Neutralitaetsbeweis auf Suite-Ebene); ein
     * bindender Cap verkleinert den Hub und NENNT sich als Grenze. Der
     * normale Ratio-Pfad bleibt unberuehrt - der Vergleichslauf prueft
     * beides in derselben Lage.
     */
    @Test
    fun `liveness ratio-deckel kappt die geschwindigkeit und nennt sich als grenze`(@TempDir dir: File) {
        fun laufMit(cap: Double, name: String): List<FuseCycleRunner.Outcome> {
            livenessLage(File(dir, name))
            corrRatioCapZ = cap
            mealRatioCapZ = kotlin.math.max(cap, 0.35)
            return (0 until 30).map { cycle() }
        }
        val offen = laufMit(1.0, "offen")
        val eng = laufMit(0.05, "eng")

        val offenHub = offen.filter { it.livenessLiftU > 0 }
        val engHub = eng.filter { it.livenessLiftU > 0 }
        assertTrue(offenHub.isNotEmpty(), "die Lage muss den Kanal heben")
        assertTrue(engHub.isNotEmpty(), "auch gekappt hebt der Kanal - nur langsamer")
        // 1. Geschwindigkeit: die Summe der Huebe ist unter dem Cap KLEINER.
        val sOffen = offenHub.sumOf { it.livenessLiftU }
        val sEng = engHub.sumOf { it.livenessLiftU }
        assertTrue(sEng < sOffen - 0.049, "Cap muss die Lieferrate druecken: eng=$sEng offen=$sOffen")
        // 2. Der Cap NENNT sich als Grenze (Tonis Vertrag: Binding
        //    livenessRatioCap, wenn er begrenzt).
        assertTrue(engHub.any { it.decision.bindingLimit == "liveness:demandRatioCap" },
            "Binding muss den Cap nennen: " + engHub.map { it.decision.bindingLimit }.distinct())
        assertTrue(offen.none { it.decision.bindingLimit == "liveness:demandRatioCap" },
            "Cap 1.0 darf nie als Grenze auftauchen")
        // 3. Export: liveRatio ist im gekappten Lauf der Cap, im offenen die
        //    Basis-Ratio des Profils (v26: ohne Marker die Korrektur-Ratio).
        assertTrue(engHub.all { (it.livenessLiveRatio ?: 9.9) <= 0.05 + 1e-9 }, "liveRatio == Cap im gekappten Lauf")
        assertTrue(offenHub.all { (it.livenessLiveRatio ?: 0.0) > 0.05 }, "liveRatio == eff. Ratio im offenen Lauf")
        // 4. Der NORMALE Pfad ist unberuehrt: vor der ersten Hebung sind
        //    beide Laeufe bitgleich.
        val ersterHub = offen.indexOfFirst { it.livenessLiftU > 0 }
        (0 until ersterHub).forEach { i ->
            assertEquals(offen[i].decision.smbU, eng[i].decision.smbU, 1e-9, "Normalpfad bitgleich (Zyklus $i)")
        }
    }

    /**
     * DER 0,15-LIVEFALL (Bauauftrag Toni 24.08. abends): Marker +~60 min
     * (Sonderrechte nach 45 min abgelaufen -> mealWindow FALSE), r ueber
     * der Rampen-Unterkante, Tail-Deadlock. Vorher uebernahm der Kanal
     * blind state.effectiveSmbRatio - die faellt ausserhalb des
     * Normalpfad-Fensters auf die Korrektur-Ratio, und "Live M" dosierte
     * unsichtbar mit dem K-Tempo (Live-Trail: r 2,69, liveRatio 0,15).
     * Jetzt rampt der Kanal fensterunabhaengig (Pflichttests 5/9), der
     * Normalpfad bleibt bitgleich auf der Korrektur-Ratio (Pflichttest
     * 10), an der Deadline gilt sofort das K-Profil - seit Tonis
     * v27-Korrektur heisst das: GLEICHE Rampenbasis, aber der K-DECKEL
     * kappt (Pflichttest 11, halb offen) - und gemessenes Fallen bleibt
     * ein absoluter Riegel (Abnahme c).
     *
     * LAGE: UKF-Steigung 1,2 bleibt UNTER der Rampen-Unterkante 1,5
     * (kein Kinematik-Fenster, kein FALLING), waehrend die
     * Insulinaktivitaet r darueber hebt - genau die Livefall-Signatur.
     * Der Onset-Kanal ist AUS, sonst oeffnete sein Fenster-Zweig das
     * Normalpfad-Fenster und der Diskriminator waere blind.
     */
    @Test
    fun `liveness meal-profil traegt die r-rampe selbst - der 0,15-livefall`(@TempDir dir: File) {
        whenever(preferences.get(FuseDoubleKey.RiseRampLowR)).thenReturn(1.5)
        whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenReturn(3.0)
        whenever(preferences.get(FuseBooleanKey.OnsetChannelEnabled)).thenReturn(false)
        // K-Deckel eng, M-Deckel offen (Migration 1,0): der Fristuebergang
        // wird damit als DECKEL-Wechsel messbar - gleiche Rampenbasis,
        // engere Kappe (Tonis v27-Korrektur). maxSMB offen, sonst bindet es
        // vor dem Ratio-Deckel und traegt dessen Namen davon.
        maxSmbU = 1.0
        livenessLage(dir)
        corrRatioCapZ = 0.20
        steigungProMin = 0.9
        knickAbMin = 55
        steigungNachKnick = 1.2
        aktivitaet = 0.028
        repeat(6) { cycle() } // Signal-Warm-up
        markerAt = clock
        val deadline = markerAt + 120 * 60_000L

        // Bis kurz vor die Deadline laufen; ab Marker-Alter 60 min sind die
        // Sonderrechte (45) samt 10-min-Nachlauf sicher weg.
        val alle = ArrayList<FuseCycleRunner.Outcome>()
        while (clock < deadline - 60_000L) alle.add(cycle())
        val disk = alle.filter {
            it.computeTs - markerAt in (60 * 60_000L)..(75 * 60_000L) && it.livenessLiftU > 0.0
        }
        assertTrue(disk.size >= 8, "die Lage muss im Diskriminator-Fenster heben: ${disk.size}")
        disk.forEach { o ->
            assertEquals("MEAL", o.livenessProfile)
            // Pflichttest 10 + Mutationsprobe "Normalpfad nutzt Profilwahl":
            // das Fenster-Trio ist zu, der Normalpfad steht auf der
            // Korrektur-Ratio - bitgleich.
            assertEquals(0.15, o.state!!.effectiveSmbRatio, 1e-9, "Normalpfad bleibt Korrektur-Ratio")
            // Pflichttest 9 + Mutationsprobe "Rueckfall auf
            // effectiveSmbRatio": die Kanal-Basis ist die Rampe.
            val erwartet = FuseController.rampSmbRatio(
                0.15, 0.35, o.state!!.rSignedMgdlPerMin, 1.5, 3.0,
            )
            assertEquals(erwartet, o.livenessBaseRatio!!, 1e-9, "Basis == geteilte Rampe")
            assertTrue(o.livenessBaseRatio!! >= 0.20, "die Rampe muss real heben: ${o.livenessBaseRatio}")
            // Pflichttest 5: M-Cap Default 1,0 = kein zusaetzlicher Deckel.
            assertEquals(o.livenessBaseRatio!!, o.livenessLiveRatio!!, 1e-9)
            assertTrue(o.livenessBinding != "demandRatioCap", "Default 1,0 bindet nie")
            // Endmenge bleibt der rasterisierte Kanal-Kandidat.
            assertEquals(LivenessChannel.quantize(o.livenessCandidateU, 0.05), o.decision.smbU, 1e-9)
        }

        // Pflichttest 11 (halb offen): der Zyklus EXAKT auf der Deadline
        // traegt bereits das K-Profil - gleiche Rampenbasis, aber der
        // K-DECKEL 0,20 kappt (v27: der Deckel ist der einzige
        // Profilunterschied der Ratio).
        var anDeadline: FuseCycleRunner.Outcome? = null
        while (anDeadline == null) { val o = cycle(); if (o.computeTs >= deadline) anDeadline = o }
        assertEquals(deadline, anDeadline!!.computeTs, "Zyklus liegt exakt auf der Deadline")
        assertEquals("CORRECTION", anDeadline!!.livenessProfile)
        val nachDeadline = (0 until 3).map { cycle() }.filter { it.livenessBaseRatio != null }
        assertTrue(nachDeadline.isNotEmpty(), "auch nach der Frist rechnet der Kanal")
        nachDeadline.forEach {
            val erwartet = FuseController.rampSmbRatio(
                0.15, 0.35, it.state!!.rSignedMgdlPerMin, 1.5, 3.0,
            )
            assertEquals(erwartet, it.livenessBaseRatio!!, 1e-9, "K-Profil: dieselbe Rampenbasis")
            assertTrue(it.livenessBaseRatio!! > 0.20 + 1e-9, "die Basis steht ueber dem K-Deckel: ${it.livenessBaseRatio}")
            assertEquals(0.20, it.livenessLiveRatio!!, 1e-9, "der K-Deckel 0,20 kappt ab der Frist")
            assertEquals("demandRatioCap", it.livenessBinding, "und nennt sich als Grenze")
        }

        // Abnahme c: gemessenes Fallen bleibt im MEAL-Vertragssinn ein
        // absoluter Riegel - hier nach der Frist, der Riegel ist
        // profilunabhaengig HART (dieselbe hart-Kette wie im MEAL-Lauf).
        knick2AbMin = ((clock - start) / 60_000L).toInt()
        steigungNachKnick2 = -1.0
        repeat(6) { cycle() }
        val fallend = cycle()
        assertEquals(0.0, fallend.livenessLiftU, 1e-9, "im gemessenen Fall hebt nichts")
        assertEquals("EXCLUDED", fallend.livenessProfile, "harte Riegel setzen EXCLUDED")
    }

    /**
     * K-PROFIL SKALIERT UEBER DIE RAMPE BIS ZUM K-DECKEL (Tonis
     * v27-Korrektur, 24.08. spaet): die v26-Fassung liess CORRECTION fest
     * auf der Korrektur-Ratio stehen - der K-Deckel 0,20 wurde nur als
     * Obergrenze einer festen 0,15 gelesen und konnte nie skalieren.
     * Jetzt: Basis = Rampe in BEIDEN Profilen, der K-Deckel ist die
     * Skalierungsgrenze. Zwei Laeufe derselben Lage: ein enger Deckel
     * kappt die Rampenbasis mit Namen, ein offener laesst sie skalieren
     * (liveRatio == Basis > 0,15 - der scharfe Diskriminator gegen das
     * alte Verhalten UND gegen die Mutationsprobe "K-Basis faellt auf die
     * Korrektur-Ratio zurueck").
     */
    @Test
    fun `correction-profil skaliert ueber die rampe bis zum k-deckel`(@TempDir dir: File) {
        whenever(preferences.get(FuseDoubleKey.RiseRampLowR)).thenReturn(1.5)
        whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenReturn(3.0)
        whenever(preferences.get(FuseBooleanKey.OnsetChannelEnabled)).thenReturn(false)
        fun laufMit(kCap: Double, name: String): List<FuseCycleRunner.Outcome> {
            maxSmbU = 1.0 // sonst bindet maxSMB vor dem Ratio-Deckel
            livenessLage(File(dir, name))
            corrRatioCapZ = kCap; mealRatioCapZ = kotlin.math.max(kCap, 0.30)
            steigungProMin = 0.9
            knickAbMin = 55
            steigungNachKnick = 1.2
            aktivitaet = 0.028
            return (0 until 80).map { cycle() }
        }
        val eng = laufMit(0.10, "eng").filter { it.livenessLiftU > 0.0 }
        assertTrue(eng.size >= 8, "die Lage muss ohne Marker heben (CORRECTION): ${eng.size}")
        eng.forEach { o ->
            assertEquals("CORRECTION", o.livenessProfile)
            val erwartet = FuseController.rampSmbRatio(
                0.15, 0.35, o.state!!.rSignedMgdlPerMin, 1.5, 3.0,
            )
            assertEquals(erwartet, o.livenessBaseRatio!!, 1e-9, "Basis == Rampe auch im K-Profil")
            assertEquals(0.10, o.livenessLiveRatio!!, 1e-9, "der enge K-Deckel kappt")
            assertEquals("demandRatioCap", o.livenessBinding, "und nennt sich als Grenze")
        }
        // Der Gegenbeweis braucht ein r IN der Rampe - sonst prueft der
        // Test nichts (spaete Zyklen: Steigung 1,2 + Aktivitaetshub).
        assertTrue(
            eng.takeLast(5).all { (it.state!!.rSignedMgdlPerMin ?: 0.0) > 1.7 },
            "r muss in der Rampe stehen: " + eng.takeLast(5).map { it.state!!.rSignedMgdlPerMin },
        )

        // Offener K-Deckel 0,35 (= Rampenmaximum): die Korrektur-Ratio
        // SKALIERT - liveRatio folgt der Rampenbasis ueber 0,15 hinaus,
        // der Deckel bindet nie real.
        transportReset()
        val offen = laufMit(0.35, "offen").filter { it.livenessLiftU > 0.0 }
        assertTrue(offen.size >= 8, "auch der offene Lauf muss heben: ${offen.size}")
        val spaet = offen.filter { (it.state!!.rSignedMgdlPerMin ?: 0.0) > 1.7 }
        assertTrue(spaet.size >= 5, "es braucht Zyklen mit r in der Rampe: ${spaet.size}")
        spaet.forEach { o ->
            assertEquals(o.livenessBaseRatio!!, o.livenessLiveRatio!!, 1e-9, "unter dem Deckel folgt die Ratio der Rampe")
            assertTrue(o.livenessLiveRatio!! > 0.15 + 1e-9, "und skaliert ueber 0,15 hinaus: ${o.livenessLiveRatio}")
            assertTrue(o.livenessBinding != "demandRatioCap", "der offene Deckel bindet nicht")
        }
    }

    /**
     * M-RATIO-DECKEL AUF DER RAMPENBASIS (Abnahme e, Pflichttest 6): erst
     * die Basis (Rampe), DANN der Deckel. Cap 0,20 unter einer Basis
     * >= 0,25 -> liveRatio 0,20, Binding livenessRatioCap (Mutations-
     * proben "Profildeckel ignoriert" und "min durch max ersetzt").
     */
    @Test
    fun `m-ratio-deckel kappt die rampenbasis und nennt sich`(@TempDir dir: File) {
        whenever(preferences.get(FuseDoubleKey.RiseRampLowR)).thenReturn(1.5)
        whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenReturn(3.0)
        whenever(preferences.get(FuseBooleanKey.OnsetChannelEnabled)).thenReturn(false)
        // maxSMB offen, sonst bindet es VOR dem Ratio-Deckel (0,20 x
        // ~2,5 U Bedarf > 0,3) und der Test saehe nie den Cap als Grenze.
        maxSmbU = 1.0
        livenessLage(dir)
        mealRatioCapZ = 0.20; corrRatioCapZ = 0.15
        steigungProMin = 0.9
        knickAbMin = 55
        steigungNachKnick = 1.2
        aktivitaet = 0.028
        repeat(6) { cycle() } // Signal-Warm-up
        markerAt = clock
        val alle = (0 until 80).map { cycle() }
        val disk = alle.filter {
            it.computeTs - markerAt in (60 * 60_000L)..(75 * 60_000L) && it.livenessLiftU > 0.0
        }
        assertTrue(disk.size >= 8, "die Lage muss im Diskriminator-Fenster heben: ${disk.size}")
        disk.forEach { o ->
            assertEquals("MEAL", o.livenessProfile)
            assertTrue(o.livenessBaseRatio!! >= 0.20 + 1e-9, "die Basis liegt ueber dem Cap: ${o.livenessBaseRatio}")
            assertEquals(0.20, o.livenessLiveRatio!!, 1e-9, "der M-Cap kappt die Rampenbasis")
            assertEquals("demandRatioCap", o.livenessBinding, "und nennt sich als Grenze")
        }
    }

    /**
     * TRENDREGEL-VERTRAG (Toni 23.08. Abend), DOWN-Seite - MIT DEM
     * STRUKTURBEFUND, der beim Bau herauskam und den die Live-Daten
     * bestaetigen (1719 getriggerte Lane-Zyklen im Shadow-Trail,
     * avoidedSmbU exakt 0 in ALLEN): die Senkung min(mean, fast) ist
     * gegen die PRODUKTIVE BREMSBAHN redundant. FuseController bindet
     * den Bedarf ueber releaseMean = min(Hauptbahn, Bremsbahn), und die
     * gesenkte Hauptbahn (fast, gleicher Tau) IST die Bremsbahn -
     * Kandidat und Guard rechnen pessimistisch ueber beide. Der Vertrag
     * hier ist deshalb zweiseitig:
     *   1. der Ausloeser zieht nach der verlangten Persistenz (P2 nie
     *      spaeter als P3), davor ist der Lauf BITGLEICH (zugleich der
     *      Geraete-Inertheitsbeweis: null ist am Geraet der einzige Fall),
     *   2. die BAHN ist sichtbar gesenkt (prediction.bgAtHorizonMean),
     *      die ENTSCHEIDUNG aber identisch - genau die Redundanz.
     * Reisst Punkt 2 links, ist die Injektion tot; reisst er rechts, hat
     * sich die Bremsbahn-Bindung geaendert und der Befund ist zu pruefen.
     */
    @Test
    fun `trendregel DOWN senkt erst nach der verlangten Persistenz`(@TempDir dir: File) {
        fun laufMit(regel: String?, name: String): List<FuseCycleRunner.Outcome> {
            transportReset()
            // Steiler Anstieg, dann fast flach: der UKF-fastDrive faellt nach
            // dem Knick monoton, waehrend das Theil-Sen-Fenster noch den
            // steilen Teil mittelt -> fast < slow + wachsender Streak.
            flach = 95.0; steigungProMin = 2.0
            knickAbMin = 30; steigungNachKnick = 0.2
            markerAuthorized = false
            val l = FuseLedgerAdapter().also { it.loadOnce(File(dir, name).also(File::mkdirs), "test-epoch", start) }
            neuerRunner(l, trendRegel = regel)
            clock = start + 28 * 60_000L
            return (0 until 14).map { cycle() }
        }
        val ohne = laufMit(null, "ohne")
        val p3 = laufMit("DOWN_P3", "p3")
        val p2 = laufMit("DOWN_P2", "p2")

        assertTrue(ohne.all { !it.trendRuleApplied }, "ohne Regel darf das Flag nie stehen")
        val erster3 = p3.indexOfFirst { it.trendRuleApplied }
        val erster2 = p2.indexOfFirst { it.trendRuleApplied }
        assertTrue(erster3 > 0, "P3 muss in dieser Lage ziehen")
        assertTrue(erster2 in 1..erster3, "P2 zieht nie spaeter als P3: p2=$erster2 p3=$erster3")
        (0 until erster3).forEach { i ->
            assertEquals(ohne[i].decision.smbU, p3[i].decision.smbU, 1e-9, "vor dem Trigger bitgleich (Zyklus $i)")
        }
        // Die Bahn ist ab dem Trigger SICHTBAR gesenkt ...
        val bahnGesenkt = p3.indices.count { i ->
            p3[i].trendRuleApplied &&
                (p3[i].prediction?.bgAtHorizonMean ?: Double.MAX_VALUE) <
                (ohne[i].prediction?.bgAtHorizonMean ?: Double.MAX_VALUE) - 1.0
        }
        assertTrue(bahnGesenkt >= 3, "die Hauptbahn muss unter der ungesenkten liegen (gesenkt in $bahnGesenkt Zyklen)")
        // ... und die ENTSCHEIDUNG bleibt trotzdem die der Bremsbahn.
        p3.indices.forEach { i ->
            assertEquals(ohne[i].decision.smbU, p3[i].decision.smbU, 1e-9, "Bremsbahn-Redundanz smb (Zyklus $i)")
            assertEquals(ohne[i].decision.insulinReqU ?: -1.0, p3[i].decision.insulinReqU ?: -1.0, 1e-9, "Bremsbahn-Redundanz req (Zyklus $i)")
        }
    }

    /**
     * TRENDREGEL-VERTRAG, UP-Seite: die Regel hebt bei bestaetigter
     * Aufwaertswende NUR die Mittelbahn auf max(mean, upwardMeanDrive) -
     * der Bedarf steigt, waehrend die Zyklen vor dem Trigger bitgleich
     * bleiben. Die Unterkante bleibt produktiv (Shadow-Vertrag: TURNING_UP
     * hebt nie Guard/Tail-Zeugnisse).
     */
    @Test
    fun `trendregel UP hebt die Mittelbahn bei bestaetigter Aufwaertswende`(@TempDir dir: File) {
        fun laufMit(regel: String?, name: String): List<FuseCycleRunner.Outcome> {
            transportReset()
            // Flachbahn, dann steiler Knick: der fastDrive steigt monoton
            // ueber das noch traege Fenster -> TURNING_UP bestaetigt.
            flach = 110.0; steigungProMin = 0.0
            knickAbMin = 25; steigungNachKnick = 2.5
            markerAuthorized = false
            val l = FuseLedgerAdapter().also { it.loadOnce(File(dir, name).also(File::mkdirs), "test-epoch", start) }
            neuerRunner(l, trendRegel = regel)
            clock = start + 24 * 60_000L
            return (0 until 12).map { cycle() }
        }
        val ohne = laufMit(null, "ohne")
        val up = laufMit("UP", "up")

        assertTrue(ohne.all { !it.trendRuleApplied }, "ohne Regel darf das Flag nie stehen")
        val erster = up.indexOfFirst { it.trendRuleApplied }
        assertTrue(erster > 0, "UP muss in dieser Lage ziehen: " +
            up.mapIndexed { i, o -> "$i:${o.turnResponseShadow?.classification?.phase}" }.joinToString(" "))
        (0 until erster).forEach { i ->
            assertEquals(ohne[i].decision.smbU, up[i].decision.smbU, 1e-9, "vor dem Trigger bitgleich (Zyklus $i)")
        }
        val gehoben = up.indices.any { i ->
            up[i].trendRuleApplied &&
                (up[i].decision.insulinReqU ?: 0.0) > (ohne[i].decision.insulinReqU ?: 0.0) + 1e-6
        }
        assertTrue(gehoben, "die Anhebung muss den Bedarf heben: " +
            up.mapIndexed { i, o -> "$i:${o.trendRuleApplied}:${o.decision.insulinReqU} vs ${ohne[i].decision.insulinReqU}" }.joinToString(" "))
    }

    /**
     * V22-VERTRAGSTEST (Toni 23.08., Pkt. 1): das Theil-Sen-Fenster kommt am
     * Geraet aus der EINSTELLUNG. Gleiche geknickte Bahn, zwei Fenster: kurz
     * nach dem Knick MUSS W10 steiler sehen als W18, weil es weniger
     * Flachanteil mittelt. Die Mutation, gegen die dieser Test steht: der
     * Band-Aufruf faellt still auf die feste Konstante zurueck - dann sind
     * beide Laeufe identisch und die Differenz-Assertion reisst.
     */
    @Test
    fun `v22 - das Fenster wirkt ueber die Einstellung auf den Schaetzer`(@TempDir dir: File) {
        fun bandMean(fenster: Int): Double {
            transportReset()
            flach = 120.0; steigungProMin = 0.0
            knickAbMin = 30; steigungNachKnick = 2.0
            theilSenFensterMin = fenster
            val l = FuseLedgerAdapter().also { it.loadOnce(File(dir, "w$fenster").also(File::mkdirs), "test-epoch", start) }
            neuerRunner(l)
            clock = start + 39 * 60_000L
            val o = cycle() // Minute 40: W10 sieht nur Anstieg, W18 noch 8 min Flachbahn
            assertTrue(o.abortReason == null, "der Zyklus muss durchrechnen: ${o.abortReason}")
            return o.band!!.mean
        }
        val w18 = bandMean(18)
        val w10 = bandMean(10)
        assertTrue(w10 > w18 + 0.3, "W10 muss den Knick aktueller sehen: w10=$w10 w18=$w18")
    }

    /**
     * V22-VERTRAGSTEST (Pkt. 5): ein Fensterwechsel ist ein MODELLWECHSEL -
     * der unter dem alten Fenster verdiente Evidenz-Bestand wird geschnitten
     * (Bestand 0, Messbasis neu), waehrend der Kontrolllauf ohne Wechsel
     * denselben gesaeten Bestand durch den Zyklus traegt. Erstkontakt
     * (Altdatei ohne Feld, Stand 0) schneidet NICht, und der zuletzt gesehene
     * Stand ist restartfest. Die fallende Bahn ohne Autorisierung ist
     * Absicht: Episode ja, Abgabe nein - kein Abzug verfaelscht den Bestand.
     */
    @Test
    fun `v22 - der Fensterwechsel schneidet den Evidenz-Bestand`(@TempDir dir: File) {
        fun aufbau(name: String): FuseLedgerAdapter {
            transportReset()
            flach = 150.0; steigungProMin = -0.5
            theilSenFensterMin = 18
            markerAuthorized = false
            val l = FuseLedgerAdapter().also { it.loadOnce(File(dir, name).also(File::mkdirs), "test-epoch", start) }
            neuerRunner(l)
            clock = start + 24 * 60_000L
            markerAt = clock // frische Episode
            val o = cycle()
            assertTrue(o.abortReason == null, "Aufbauzyklus: ${o.abortReason}")
            // Bestand saeen, wie ihn eine laufende Episode truege - mit
            // gueltiger Messbasis, sonst nullt der Kern selbst (unmoeglich()).
            l.episodes.evidenceState = l.episodes.evidenceState.copy(
                stockMgdl = 5.0, rebaseRequired = false,
                lastAcceptedTs = clock, lastDecayTs = clock,
            )
            return l
        }

        // KONTROLLE zuerst: ohne Wechsel uebersteht der Bestand den Zyklus
        // (verfallen, aber deutlich > 0). Ohne diese Haelfte koennte der
        // Schnitt-Beweis ein Artefakt der Episodenmechanik sein.
        aufbau("kontrolle")
        val ok = cycle()
        assertTrue((ok.evidenceStockMgdl ?: 0.0) > 1.0, "Kontrolle traegt den Bestand: ${ok.evidenceStockMgdl} (${ok.evidencePhase})")

        // WECHSEL 18 -> 10: der naechste Zyklus schneidet.
        val w = aufbau("wechsel")
        theilSenFensterMin = 10
        val ow = cycle()
        assertEquals(0.0, ow.evidenceStockMgdl ?: -1.0, 1e-9, "der Bestand ist geschnitten (${ow.evidencePhase})")
        assertEquals(10L, w.episodes.theilSenWindowLastMin, "der neue Stand ist gemerkt")
        // RESTARTFEST: der Stand steht in der Datei - sonst verschluckte ein
        // Neustart mitten im Wechsel den Schnitt (Praeferenz schon neu, Feld
        // wieder 0 -> Erstkontakt-Pfad). Versiegeln ist im Rig Plugin-Arbeit,
        // deshalb hier ausdruecklich.
        assertTrue(w.persistVerified(File(dir, "wechsel")), "versiegeln")
        assertEquals(10L, nachNeustart(File(dir, "wechsel")).theilSenWindowLastMin, "restartfest")

        // ERSTKONTAKT: Altdatei ohne Feld (Stand 0) schneidet nicht - der
        // Bestand entstand unter dem bis dahin einzigen Fenster.
        val e = aufbau("erstkontakt")
        e.episodes.theilSenWindowLastMin = 0L
        val oe = cycle()
        assertTrue((oe.evidenceStockMgdl ?: 0.0) > 1.0, "Erstkontakt schneidet nicht: ${oe.evidenceStockMgdl} (${oe.evidencePhase})")
        assertEquals(18L, e.episodes.theilSenWindowLastMin, "der Stand ist danach gesetzt")
    }

    /**
     * Prognose-Shadow-Masterschalter (Toni/Codex 23.08.): AUS heisst leere
     * Matrizen und enabled:false im Export - und die DOSIERUNG ist bitgleich
     * zum AN-Lauf. Der Schalter wird nie von Dosierlogik gelesen; die
     * Wende-KLASSIFIKATION laeuft weiter und speist den Liveness-Exit
     * unveraendert (die Lage enthaelt absichtlich einen bestaetigten
     * Wende-Exit, damit genau dieser Pfad im Vergleich steckt).
     */
    @Test
    fun `Prognose-Shadow AUS laesst die Dosierung bitgleich und sammelt nichts`(@TempDir dir: File) {
        livenessLage(dir)
        knick2AbMin = 26
        steigungNachKnick2 = -1.5
        val an = ArrayList<Double>()
        var anVarianten = 0
        repeat(40) { val o = cycle()
            an.add(o.decision.smbU)
            anVarianten += (o.turnResponseShadow?.variants?.size ?: 0) +
                (o.turnResponseShadow?.downVariants?.size ?: 0)
        }
        assertTrue(anVarianten > 0, "der AN-Lauf muss an der Wende Varianten gesammelt haben")

        forecastShadowAn = false
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(File(dir, "aus").also(File::mkdirs), "test-epoch", start) })
        val aus = ArrayList<Double>()
        var ausVarianten = 0
        repeat(40) { val o = cycle()
            aus.add(o.decision.smbU)
            ausVarianten += (o.turnResponseShadow?.variants?.size ?: 0) +
                (o.turnResponseShadow?.downVariants?.size ?: 0)
            // Abbruchzyklen (Signalaufbau) tragen die Outcome-Defaults -
            // bewertet wird nur der Hauptpfad.
            if (o.abortReason == null) assertEquals(false, o.forecastShadowEnabled)
        }
        assertEquals(0, ausVarianten, "AUS sammelt nichts")
        assertEquals(an, aus, "die Dosierung haengt nicht am Sammler")
    }

    /** Jedes Umschalten eroeffnet eine neue, restartfeste Sammel-Epoche -
     *  Auswertungen duerfen keine Messluecke ueberbruecken. */
    @Test
    fun `Prognose-Shadow Umschalten eroeffnet eine neue restartfeste Epoche`(@TempDir dir: File) {
        val adapter = livenessLage(dir)
        // Signalaufbau: die ersten Zyklen brechen ab und tragen Defaults.
        repeat(6) { cycle() }
        val e1 = cycle().forecastShadowEpochTs
        assertTrue(e1 > 0L)
        repeat(3) { assertEquals(e1, cycle().forecastShadowEpochTs, "ohne Umschalten bleibt die Epoche") }
        forecastShadowAn = false
        val e2 = cycle().forecastShadowEpochTs
        assertTrue(e2 > e1, "AUS eroeffnet eine neue Epoche")
        forecastShadowAn = true
        val e3 = cycle().forecastShadowEpochTs
        assertTrue(e3 > e2, "AN eroeffnet wieder eine neue")
        repeat(2) { assertEquals(e3, cycle().forecastShadowEpochTs) }
        assertTrue(adapter.persistVerified(dir))
        assertEquals(e3, nachNeustart(dir).forecastShadowEpochTs, "die Epoche steht in der Datei")
    }

    /**
     * v20-Vertrag (Toni/Codex 22.08. spaet): getrennte Tag-/Nachtschwelle.
     * Der regulaere Tag/Nacht-Wechsel ist KEIN CONFIG_CHANGED; ein Wechsel
     * in die Nacht unter der neuen Schwelle beendet einen Lauf als
     * DRUCKVERLUST ohne Sperre; eine Aenderung des KONFIGURIERTEN
     * Nachtwerts beendet ihn dagegen sehr wohl (der Fingerprint traegt
     * beide konfigurierten Werte, nie den wirksamen).
     */
    @Test
    fun `Liveness v20 - Nachtschwelle wirkt im Nachtfenster ohne CONFIG_CHANGED`(@TempDir dir: File) {
        livenessLage(dir)
        // Erst NIE Nacht (Start == Ende), damit die Bewaffnung eindeutig
        // unter der Tagesschwelle laeuft - der Rig-Startzeitpunkt laege
        // sonst je nach Zeitzone mitten im Default-Nachtfenster.
        nachtStartMin = 0
        nachtEndeMin = 0
        var aktiv = false
        var quelleTag = false
        repeat(22) { val o = cycle()
            if (o.livenessActive) aktiv = true
            if (o.livenessBgMinSource == "DAY") quelleTag = true
        }
        assertTrue(aktiv, "der Lauf muss unter der Tagesschwelle stehen")
        assertTrue(quelleTag, "die Quelle DAY muss exportiert sein")
        // Die Nachtschwelle ist von Beginn an KONFIGURIERT (250 = Maximum
        // der Key-Grenzen; 400 wuerde die Migrations-Klammer als "nie
        // gesetzt" verwerfen) - erst dadurch ist der Fenster-Wechsel unten
        // eine reine Tag/Nacht-Frage ohne Fingerprint-Aenderung.
        livenessBgMinNacht = 250.0
        // Nacht AN (ganztags): der BG liegt unter der Nachtschwelle, der
        // Lauf endet als DRUCKVERLUST - ohne Sperre. Der Fingerprint traegt
        // beide KONFIGURIERTEN Werte und aendert sich beim Fensterwechsel
        // nicht mehr... doch: die Nachtschwelle wurde soeben erst gesetzt
        // (Fallback 160 -> 250). Deshalb EIN Verarbeitungszyklus dazwischen.
        val cfgZyklus = cycle()
        assertEquals("CONFIG_CHANGED", cfgZyklus.livenessExit, "das Setzen der Nachtschwelle ist ein Config-Wechsel")
        // Wieder bewaffnen lassen (drei frische Zyklen unter Tag 160).
        var wiederAktiv = false
        repeat(5) { if (cycle().livenessActive) wiederAktiv = true }
        assertTrue(wiederAktiv, "nach dem Config-Wechsel bewaffnet er neu")
        nachtStartMin = 0
        nachtEndeMin = 1439
        val o1 = cycle()
        assertEquals("PRESSURE_GONE", o1.livenessExit, "Nachteintritt unter der Schwelle = Druckverlust")
        assertEquals("NIGHT", o1.livenessBgMinSource)
        assertEquals(250.0, o1.livenessBgMinEffectiveMgdl ?: 0.0, 1e-9)
        assertEquals(0L, o1.livenessReArmUntilTs, "ohne Sperre")
        // Zurueck zum Tag: KEIN CONFIG_CHANGED, und die Bewaffnung beginnt
        // sauber mit drei frischen Zyklen unter der niedrigeren Schwelle.
        nachtStartMin = 0
        nachtEndeMin = 0
        var wiederAktivMin = -1
        repeat(6) { i ->
            val o = cycle()
            assertTrue(o.livenessExit != "CONFIG_CHANGED", "Tag/Nacht-Wechsel ist kein CONFIG_CHANGED")
            if (o.livenessActive && wiederAktivMin < 0) wiederAktivMin = i + 1
        }
        assertTrue(wiederAktivMin in 3..5, "drei frische Zyklen unter der Tagesschwelle: " + wiederAktivMin)
        // Die Aenderung des KONFIGURIERTEN Nachtwerts beendet den Lauf.
        livenessBgMinNacht = 170.0
        val o2 = cycle()
        assertEquals("CONFIG_CHANGED", o2.livenessExit)
        assertEquals(0L, o2.livenessReArmUntilTs, "Config-Wechsel bleibt sperrfrei")
    }

    /**
     * v19-Vertrag (Codex, Live-Trail 22:53-23:03): die Sperre NULLT den
     * Streak. Vorher zaehlte er waehrend der Pause weiter (live gemessen
     * 1->10), und der Kanal war nach Fristablauf SOFORT wieder scharf -
     * statt drei frische Druckzyklen zu verlangen. Der Exit laeuft hier
     * ueber einen Abbruchzyklus, weil nur dieser Exit KEINE stehende
     * Bedingung hinterlaesst (Manual-Exit nullt den Streak selbst per
     * Wanduhr, Modell-/Wende-Exits blocken auch nach der Pause weiter -
     * die Mutation waere dort unsichtbar).
     */
    @Test
    fun `Liveness v19 - die Sperre nullt den Streak und danach zaehlen drei frische Zyklen`(@TempDir dir: File) {
        livenessLage(dir)
        // Minimaler AUFWAERTS-Knick nach dem Abbruch: im rauschfreien Rig
        // faellt der Drive sonst asymptotisch ewig weiter und TURN_STANDING
        // blockte die Wiederbewaffnung dauerhaft (dieselbe Kante wie in der
        // Scheinwende-Gegenprobe, nur andersherum aufgeloest).
        knick2AbMin = 24
        steigungNachKnick2 = 1.45
        var aktiv = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktiv = true }
        assertTrue(aktiv, "der Lauf muss stehen")
        val kaputt = org.mockito.kotlin.spy(validProfile)
        org.mockito.kotlin.doReturn(5000.0).whenever(kaputt).getIsfMgdlTimeFromMidnight(org.mockito.kotlin.any())
        whenever(profileFunction.getProfile()).thenReturn(kaputt)
        whenever(profileFunction.getProfile(any())).thenReturn(kaputt)
        val abbruch = cycle()
        assertEquals("OBSERVATION_LOST", abbruch.livenessExit)
        val sperreBis = abbruch.livenessReArmUntilTs
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(profileFunction.getProfile(any())).thenReturn(validProfile)
        var ersterLiftTs = 0L
        repeat(24) {
            val o = cycle()
            if (o.computeTs < sperreBis) {
                assertEquals(0, o.livenessStreak, "waehrend der Sperre bleibt der Streak null")
            }
            if (ersterLiftTs == 0L && o.livenessLiftU > 0.0) ersterLiftTs = o.computeTs
        }
        assertTrue(ersterLiftTs > 0L, "nach Sperre und drei frischen Zyklen muss der Kanal wieder heben")
        // Drei frische Zyklen 1/3, 2/3, 3/3: der fruehestmoegliche Hub liegt
        // ZWEI Minuten nach dem ersten freien Zyklus - eine Sofort-Bewaffnung
        // aus einem waehrend der Pause gezaehlten Streak laege bei +0.
        assertTrue(
            ersterLiftTs >= sperreBis + 2 * 60_000L,
            "drei FRISCHE Druckzyklen nach Ablauf: Sperre bis $sperreBis, Hub $ersterLiftTs",
        )
    }

    /**
     * Gegenprobe 7 (Audit 22.08.): das ZWEITE unbeobachtete Loch neben dem
     * Abort - der predictorfreie Marker-Fallback-Zyklus. Er dosiert, laeuft
     * aber ohne die Kanalstufe; ein aktiver Lauf muss auch dort enden.
     * Ohne diese Probe ueberlebte das Loeschen genau dieses Aufrufs die
     * gesamte Suite.
     */
    @Test
    fun `Liveness Gegenprobe - auch der Marker-Fallback-Zyklus beendet den Lauf`(@TempDir dir: File) {
        livenessLage(dir)
        markerAuthorized = true
        var aktiv = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktiv = true }
        assertTrue(aktiv, "der Lauf muss stehen")
        // Druck UND ueberstimmbare Bahn-Ablehnung im SELBEN Zyklus: nur so
        // trifft ein Fallback-Zyklus auf einen noch aktiven Lauf. Zwei
        // Zyklen frueher ginge es nicht - unter offenem Markerfenster ist
        // der Normalpfad nie GUARD/TAIL-gedeckelt (der Kanal bewaffnet
        // dann korrekt nicht, NORMAL_PATH_OPEN), und ein Druck in einen
        // Hauptpfad-Zyklus beendet den Lauf schon selbst als
        // Segmentbruch-EXCLUDED - beides im Rig gesehen.
        markerAt = clock
        predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT
        val fb = cycle()
        assertTrue(fb.markerFallbackUsed, "der Aufbau muss wirklich den Fallback-Pfad treffen")
        assertEquals("OBSERVATION_LOST", fb.livenessExit, "der aktive Lauf endet im Fallback-Zyklus")
        assertTrue(fb.livenessReArmUntilTs > fb.computeTs, "mit Sperre")
        predictReject = null
        var liftInSperre = 0
        repeat(9) {
            val x = cycle()
            assertEquals(false, x.livenessActive, "kein Weiterlaufen nach dem Fallback-Zyklus")
            if (x.livenessLiftU > 0.0) liftInSperre++
        }
        assertEquals(0, liftInSperre)
    }

    /**
     * Gegenprobe 8 (Audit 22.08.): eine TAKTLUECKE - Minuten ohne Zyklus
     * bei lueckenlos weiterlaufender CGM-Reihe (Pumpe belegt, Prozess
     * pausiert) - ueberbrueckt den Lauf nicht. BEWUSST OHNE Sperre: die
     * Medtrum-Zyklen strecken sich real bis 854 s; eine Sperre je
     * Streckung entwertete den Kanal. Aber die Bewaffnung ist neu zu
     * verdienen.
     */
    @Test
    fun `Liveness Gegenprobe - eine Taktluecke ueberbrueckt den Lauf nicht`(@TempDir dir: File) {
        livenessLage(dir)
        var aktiv = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktiv = true }
        assertTrue(aktiv, "der Lauf muss stehen")
        clock += 4 * 60_000L
        val o1 = cycle()
        assertEquals("CONTINUITY_GAP", o1.livenessExit, "die Taktluecke beendet den Lauf")
        assertEquals(0.0, o1.livenessLiftU, 1e-9)
        var wiederAb = -1
        repeat(6) { i -> if (cycle().livenessLiftU > 0.0 && wiederAb < 0) wiederAb = i + 1 }
        assertTrue(wiederAb in 2..5, "drei frische Druckzyklen vor dem naechsten Hub: $wiederAb")
    }


    // ---- WIEDEREINSTIEG NACH FUNKLUECKE (Toni 25.08. abends) ------------

    /**
     * Baut eine Reihe mit einer echten Funkluecke im GERAETETAKT und
     * faehrt sie bis zum ersten Zyklus nach der Luecke.
     *
     * @return die Aussenzeitpunkte der Zyklen nach der Luecke.
     */
    private fun funklueckeAufbauen(dir: File, basisBg: Double, steigung: Double, kalibrierNachMin: Int? = null): List<Long> {
        val start = 1_700_000_000_000L
        val takt = listOf(58_000L, 61_000L, 59_000L, 60_000L, 62_000L, 59_000L)
        val vor = ArrayList<Pair<Long, Double>>()
        var t = start
        // 25 min Vorlauf, damit die Reihe VOR der Luecke nachweislich lief.
        repeat(25) { i -> vor.add(t to basisBg + steigung * i); t += takt[i % takt.size] }
        // 200 s Funkluecke - ueber der 180-s-Bruchgrenze, unter 10 min.
        t = vor.last().first + 200_000L
        val nach = ArrayList<Pair<Long, Double>>()
        repeat(10) { i ->
            nach.add(t to basisBg + steigung * (25 + 3 + i)); t += takt[i % takt.size]
        }
        rohSerie = vor + nach
        // Optional ein Kalibrierbeginn MITTEN in der Vorlaufreihe: dann ist
        // das Fenster durch die Kalibrierung begrenzt, waehrend die Luecke
        // DANACH liegt und in der Reihe erhalten bleibt. Genau die Lage, in
        // der die Regimeregel etwas zu entscheiden hat.
        // SIEBEN SEKUNDEN VOR dem Messwert - eine Kalibrierung faellt in
        // der Wirklichkeit nicht auf einen CGM-Zeitstempel. Genau dieser
        // Versatz macht Grenzzeitpunkt und ersten Segmentpunkt
        // unterscheidbar; laegen beide aufeinander, waere eine
        // Fehlverdrahtung der Segmentidentitaet nicht beobachtbar.
        kalibrierStart = kalibrierNachMin?.let { vor[it].first - 7_000L } ?: -1L
        clock = vor.last().first
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter, wiedereinstieg = app.aaps.fuse.core.signal.RejoinPolicy.enabled())
        return nach.map { it.first }
    }

    /**
     * PFLICHTPROBE: BEIDE Schaetzerverbraucher sehen DIESELBE Reife.
     *
     * `theilSen` in der Signalquelle und `PairSlopeBand.estimate` im
     * Runner treffen KEINE zwei Entscheidungen - die Auswahl faellt einmal
     * und reist im Signal mit. Liefe der Runner weiter auf 5x8, waere das
     * hier sichtbar: der Trail truege ein rSigned, waehrend die
     * Entscheidung mit "drive not estimable" abbricht. Genau diese
     * Kombination darf es nicht geben.
     */
    @org.junit.jupiter.api.Test
    fun `nach funkluecke sehen beide schaetzerverbraucher dieselbe reife`(@TempDir dir: File) {
        rejoinAn = true
        val nachTs = funklueckeAufbauen(dir, basisBg = 120.0, steigung = 1.0)
        var gesehen = 0
        for (ts in nachTs) {
            clock = ts
            val o = runner.run(false, testPumpe())
            val r = o.signal?.rSigned
            val blind = o.abortReason?.contains("drive not estimable") == true
            assertFalse(
                r != null && blind,
                "Zyklus $ts: rSigned=$r vorhanden, aber Band bricht ab - zwei Reifebegriffe",
            )
            if (r != null) {
                gesehen++
                // Und die Begruendung steht im Signal, nicht nur im Kopf.
                assertEquals(
                    app.aaps.fuse.core.signal.SignalRejoin.Cause.GAP,
                    o.signal?.rejoin?.cause,
                    "der Grund muss die Funkluecke sein",
                )
            }
        }
        assertTrue(gesehen > 0, "in dieser Reihe muss irgendwann ein r entstehen")
    }

    /**
     * PFLICHTPROBE: der Wiedereinstieg spart Blindzeit - sonst prueft die
     * Probe oben nichts. Derselbe Ausschnitt einmal mit und einmal ohne
     * Schalter; die Zahl der blinden Zyklen MUSS sinken, und die
     * TBR-Achse darf sich dabei nicht heimlich verschieben.
     */
    @org.junit.jupiter.api.Test
    fun `der wiedereinstieg spart blinde zyklen`(@TempDir dir: File) {
        fun blindeZyklen(an: Boolean): Int {
            rejoinAn = an
            val nachTs = funklueckeAufbauen(dir, basisBg = 120.0, steigung = 1.0)
            var blind = 0
            for (ts in nachTs) {
                clock = ts
                val o = runner.run(false, testPumpe())
                if (o.abortReason?.contains("drive not estimable") == true) blind++
            }
            return blind
        }
        val ohne = blindeZyklen(false)
        val mit = blindeZyklen(true)
        assertTrue(mit < ohne, "der Wiedereinstieg muss Blindzeit sparen (ohne=$ohne, mit=$mit)")
    }

    /**
     * PFLICHTPROBE: EINE FRISCHE KALIBRIERUNG SPERRT - und die
     * Regimegrenze wird bis zum Schaetzer durchgereicht.
     *
     * Die Kalibrierung liegt drei Zyklen vor der Funkluecke. Der
     * Abschnitt zwischen ihr und der Luecke traegt also nur drei Punkte
     * und war nie streng gereift - es gibt keine bekannte Kurve
     * fortzusetzen.
     *
     * DAS IST ZUGLEICH DIE VERDRAHTUNGSPROBE. Die reine Entscheidung
     * prueft SignalRejoinTest; was sie NICHT prueft, ist ob der AUFRUFER
     * die echte Grenze uebergibt. Eine Verdrahtung, die stur
     * `Bound.NONE`/Zeitpunkt 0 einsetzt, saehe hier 25 Punkte statt drei
     * und wuerde lockern - gemessen liefen ohne diese Probe 183 von 183
     * Tests gruen.
     */
    @org.junit.jupiter.api.Test
    fun `eine frische kalibrierung verhindert den wiedereinstieg`(@TempDir dir: File) {
        rejoinAn = true
        val nachTs = funklueckeAufbauen(dir, basisBg = 120.0, steigung = 1.0, kalibrierNachMin = 22)
        val gruende = ArrayList<app.aaps.fuse.core.signal.SignalRejoin.Cause>()
        for (ts in nachTs) {
            clock = ts
            val o = runner.run(false, testPumpe())
            o.signal?.rejoin?.let {
                gruende += it.cause
                assertFalse(it.active, "das neue Regime war noch nicht etabliert")
                assertFalse(it.preGapStrictReady)
            }
        }
        assertTrue(
            gruende.contains(app.aaps.fuse.core.signal.SignalRejoin.Cause.PRE_GAP_NOT_MATURE),
            "der Grund muss die fehlende Vor-Luecken-Reife sein, nicht bloss 'kein Bruch' - " +
                "sonst wuerde eine verschluckte Regimegrenze nicht auffallen (gesehen: " +
                gruende.distinct() + ")",
        )
    }

    /**
     * PFLICHTPROBE - TONIS BEISPIEL: eine ETABLIERTE Kalibrierung sperrt
     * NICHT mehr.
     *
     *     12:00 Kalibrierung
     *     12:05-12:06 neues Signal erreicht 5x8-Reife
     *     13:00 kurze Funkluecke
     *     13:04 4x3-Rejoin erlaubt
     *
     * Der erste Wurf haette hier bis etwa 15:00 gesperrt, weil die Grenze
     * noch im 180-min-Rueckblickpuffer lag. Das verwechselte die
     * historische Fenstergrenze mit der Ursache des aktuellen
     * Segmentbruchs.
     */
    @org.junit.jupiter.api.Test
    fun `eine etablierte kalibrierung sperrt den wiedereinstieg nicht mehr`(@TempDir dir: File) {
        rejoinAn = true
        val nachTs = funklueckeAufbauen(dir, basisBg = 120.0, steigung = 1.0, kalibrierNachMin = 3)
        var gelockert = 0
        for (ts in nachTs) {
            clock = ts
            val o = runner.run(false, testPumpe())
            if (o.signal?.rejoin?.active == true) {
                gelockert++
                assertEquals(
                    app.aaps.fuse.core.signal.SignalRejoin.Cause.GAP,
                    o.signal?.rejoin?.cause,
                    "nach der Etablierung ist die Luecke eine gewoehnliche Funkluecke",
                )
                assertTrue(o.signal?.rejoin?.preGapStrictReady == true)
                // Die Grenze ist nicht verschwunden - sie erklaert diesen
                // Segmentbeginn nur nicht.
                assertEquals(
                    app.aaps.fuse.core.signal.SignalWindow.Bound.CALIBRATION_START,
                    o.signal?.rejoin?.regime?.bound,
                )
            }
        }
        assertTrue(gelockert > 0, "nach der Etablierung MUSS der Wiedereinstieg wieder greifen")
    }

    /**
     * PFLICHTPROBE: KEIN SICHERHEITSGATE WIRD UMGANGEN.
     *
     * Dieselbe Funkluecke, aber im Tief und fallend - die Lage des
     * Pflichtfalls 24.08. 18:09 (BG 76 -> 74). Der Wiedereinstieg gibt
     * dort wieder eine ENTSCHEIDUNG frei, aber kein Insulin: die Menge
     * bleibt in jedem Zyklus null, und sie ist mit Schalter dieselbe wie
     * ohne.
     */
    @org.junit.jupiter.api.Test
    fun `im tief gibt der wiedereinstieg kein insulin frei`(@TempDir dir: File) {
        var gelockert = 0
        fun mengen(an: Boolean): List<Double> {
            rejoinAn = an
            gelockert = 0
            val nachTs = funklueckeAufbauen(dir, basisBg = 76.0, steigung = -0.3)
            return nachTs.map { ts ->
                clock = ts
                val o = runner.run(false, testPumpe())
                if (o.signal?.rejoin?.active == true) gelockert++
                o.decision.smbU
            }
        }
        val ohne = mengen(false)
        val mit = mengen(true)
        // OHNE DIESE ZEILE waere die Probe aussagelos: sie koennte gruen sein,
        // weil der Wiedereinstieg im Tief gar nicht erst griff. Er MUSS
        // gegriffen haben - und trotzdem faellt kein Insulin.
        assertTrue(gelockert > 0, "der Wiedereinstieg muss im Tief ueberhaupt gegriffen haben")
        assertEquals(0.0, mit.sum(), 1e-9, "im Tief darf der Wiedereinstieg nichts freigeben")
        assertEquals(ohne, mit, "die Mengenachse muss im Tief bitgleich bleiben")
    }


    // ---- P0-GEGENPROBEN ZUM RUHE-AUSGANG (Toni 25.08. spaet) ----------
    //
    // Die Einheitstests in UpfrontRecoveryTest pruefen die ENTSCHEIDUNG.
    // Was sie nicht pruefen koennen: was am ECHTEN MarkerFloor ankommt.
    // In Phase A existieren weitere Marker- und Prime-Autorisierungen; der
    // Vollbatch koennte ueber eine benachbarte Autorisierung
    // wiederhereinkommen. Diese Proben laufen deshalb mit der
    // vollstaendigen Geraetepolitik.

    private fun mahlzeitMitRuhe(dir: File, params: app.aaps.fuse.core.controller.UpfrontRecovery.Params) {
        fundamentAn = true
        flach = 180.0
        steigungProMin = 2.5
        markerAuthorized = true
        markerAt = start + 2 * 60_000L
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l, ruheParams = params)
    }

    /**
     * Treibt einen Sofortbatch in den Aufschub und danach in eine RUHIGE
     * Lage: die Fallgefahr endet und der Zucker steigt wieder - aber mit
     * +0,10/min LANGSAMER als die +0,20/min, die der allgemeine Latch fuer
     * seine schnelle Erholung verlangt. Genau dazwischen liegt
     * CALM_RECOVERED; darueber waere es FULL_BATCH_ELIGIBLE, darunter
     * bliebe es blockiert.
     */
    private fun ruheLauf(
        dir: File,
        behandlung: app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment?,
        zyklen: Int = 40,
        // Der Abstieg. Damit der HISTORISCHE Latch ueberhaupt scharf wird,
        // muss der Guard-Boden im harten Horizont liegen - BG 150 bei
        // -1,5/min sind 53 Minuten und reichen dafuer nicht.
        abstiegBg: Double = 150.0,
        abstiegRate: Double = -1.5,
        abstiegIob: Double = 2.0,
        wendeZyklus: Int = 8,
        // Die Ruhe: positiv, aber unter den +0,20/min der schnellen
        // Erholung - sonst FULL_BATCH_ELIGIBLE statt CALM_RECOVERED.
        ruheRate: Double = 0.10,
        ruheIob: Double = 0.5,
        // Ein MANUELLER Ersatzbolus - er zehrt an derselben gepinnten
        // Huelle und ist damit der einzige Weg, im Rig eine echte
        // Huellenklemmung zu erzeugen.
        manuellBeiZyklus: Int? = null,
        manuellU: Double = 4.0,
        huelleU: Double = 3.75,
    ): List<FuseCycleRunner.Outcome> {
        aufschubAn = true
        upfrontAnteil = 1.0
        primeHuelleU = huelleU
        fundamentAnteil = 0.8
        mahlzeitMitRuhe(dir, behandlung?.let {
            app.aaps.fuse.core.controller.UpfrontRecovery.Params.of(
                calmCycles = 3, minUkf = 0.05, minGuardDistanceMgdl = 5.0,
                calmTreatment = it, ruleSetVersion = app.aaps.fuse.plugin.export.FuseStateJson.RULE_SET_VERSION,
            )
        } ?: app.aaps.fuse.core.controller.UpfrontRecovery.Params.OFF)
        flach = abstiegBg
        steigungProMin = abstiegRate
        bolusIobU = abstiegIob
        val alle = mutableListOf<FuseCycleRunner.Outcome>()
        repeat(zyklen) { i ->
            if (i == wendeZyklus) {
                steigungProMin = ruheRate
                bolusIobU = ruheIob
            }
            if (i == manuellBeiZyklus)
                boluses = listOf(BS(timestamp = clock, amount = manuellU, type = BS.Type.NORMAL))
            alle += transport(dir)
        }
        return alle
    }

    /**
     * P0.1: KEIN GRANT AM TATSAECHLICHEN MarkerFloor - nicht bloss kein
     * MEAL_UPFRONT-Grant.
     *
     * `MarkerFloor.apply` bekommt `lifted.grant`, und `lifted` durchlaeuft
     * ausser `liftUpfront` auch `PrimeRelease.lift` und die Phase-B-Hebung.
     * Die Zusicherung "der ruhige Pfad stempelt keinen Grant" ist also erst
     * dann belegt, wenn sie an dieser Stelle geprueft wird.
     */
    @Test
    fun `der ruhige Pfad veraendert am MarkerFloor nichts`(@TempDir dir: File) {
        // DIE FRAGE IST NICHT "kommt ein Grant an", sondern "kommt EIN
        // ANDERER an als ohne Ruhe-Ausgang". Gemessen: waehrend
        // CALM_RECOVERED liegt am MarkerFloor sehr wohl ein Grant - 0,30 U
        // aus PRIME -, und der Boden hebt darauf 0,25 U an. Das ist der
        // gewoehnliche Prime-Pfad und existiert unabhaengig vom
        // Ruhe-Ausgang. Beweisen laesst sich das nur gegen die Referenz.
        val referenz = ruheLauf(File(dir, "ref"), null)
        val ruhig = ruheLauf(File(dir, "demand"),
                             app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.DEMAND_LIMITED)
        assertEquals(referenz.size, ruhig.size)

        // DIE GRENZE DIESES AUFBAUS - festgehalten statt uebergangen.
        //
        // Der bedarfsbegrenzte Kandidat feuert hier NIE, und der Grund ist
        // nicht der Kandidat, sondern der Verlauf: gemessen sechs
        // Ruhe-Zyklen, in keinem davon blockierte der Endriegel. Der
        // historische Latch war in diesem synthetischen Verlauf gar nicht
        // scharf - es gab also nichts zu entriegeln.
        //
        // Die ECHTE Abendlage des 25.08. sah anders aus: descentLatchActive
        // 23/23, alle 20 Phase-A-Zyklen 0 U mit MEASURED_DESCENT_RISK.
        // Genau dort waere DEMAND_LIMITED wirksam - und genau die bildet
        // dieser Aufbau nicht ab.
        //
        // WAS DER VERGLEICH UNTEN DAHER PRUEFT: die UNVERAENDERTHEIT des
        // Endpfades, nicht die Wirksamkeit von DEMAND_LIMITED. Wer diesen
        // Test als Beleg fuer die Wirksamkeit liest, liest ihn falsch. Der
        // Beleg kommt erst aus dem Replay des echten Verlaufs.
        val gefeuert = ruhig.count { (it.upfrontChain?.calmDemandU ?: 0.0) > 0.0 }
        assertEquals(0, gefeuert) {
            "Aufbau hat sich geaendert: der Kandidat feuert jetzt ($gefeuert " +
                "Zyklen). Dann muss dieser Test auf Wirksamkeit umgestellt " +
                "werden, statt Unveraendertheit zu behaupten."
        }
        val calmZyklen = ruhig.count { it.upfrontChain?.recoveryMode == "CALM_RECOVERED" }
        assertTrue(calmZyklen > 0) {
            "der Ruhe-Ausgang muss erreicht werden, gesehen: " +
                ruhig.mapNotNull { it.upfrontChain?.recoveryMode }.distinct()
        }

        // BEDARFSBEGRENZT HEISST: gar keine Aenderung. Der Zweig faellt mit
        // dem blockierten zusammen - er benennt nur, WARUM nicht gehoben
        // wurde. Weicht hier irgendetwas ab, gibt der ruhige Pfad Insulin
        // frei, das der heutige Vertrag nicht gibt.
        referenz.zip(ruhig).forEachIndexed { i, (r, q) ->
            // DIE PROVENIENZ BLEIBT STRIKT GLEICH. Der Ruhe-Kandidat ist
            // reiner Bedarf; er darf an keiner Autorisierung etwas aendern.
            assertEquals(r.upfrontChain?.markerFloorLiftU, q.upfrontChain?.markerFloorLiftU,
                         "Zyklus $i: MarkerFloor-Anhebung")
            assertEquals(r.upfrontChain?.grantU, q.upfrontChain?.grantU, "Zyklus $i: Grant")
            assertEquals(r.upfrontChain?.grantSource, q.upfrontChain?.grantSource,
                         "Zyklus $i: Grantquelle")
            assertEquals(r.phaseAUpfrontPendingU, q.phaseAUpfrontPendingU, 1e-9,
                         "Zyklus $i: offener Sofortanteil")
            // WO DER KANDIDAT NICHT GREIFT, muss alles gleich bleiben -
            // sonst waere der Ruhe-Ausgang an einer anderen Stelle wirksam
            // geworden, als er es sein darf.
            if ((q.upfrontChain?.calmDemandU ?: 0.0) <= 0.0) {
                assertEquals(r.decision.smbU, q.decision.smbU, 1e-9, "Zyklus $i: Menge")
            }
            // DER ENDPFAD - der eigentliche Gegenstand. Grant-Gleichheit am
            // MarkerFloor allein waere zu schwach: der Ruhe-Ausgang kann
            // einen vorhandenen PRIME-Grant nicht erzeugen, aber WIRKSAM
            // machen, indem er den historischen Riegel umgeht. Kausal waere
            // die Mehrmenge dann durch CALM_RECOVERED entstanden.
            assertEquals(r.upfrontChain?.normalNeedBeforeMarkerFloorU,
                         q.upfrontChain?.normalNeedBeforeMarkerFloorU,
                         "Zyklus $i: normaler Bedarf vor allen Lifts")
            if ((q.upfrontChain?.calmDemandU ?: 0.0) <= 0.0) {
                assertEquals(r.upfrontChain?.afterDescentGateU, q.upfrontChain?.afterDescentGateU,
                             "Zyklus $i: Menge nach dem Endriegel")
                assertEquals(r.upfrontChain?.requestedRtU, q.upfrontChain?.requestedRtU,
                             "Zyklus $i: requestedRtU")
            }

            // DIE INVARIANTE, die auch eine kuenftige Aenderung von
            // DEMAND_LIMITED ueberlebt: was gegenueber BLOCKED zusaetzlich
            // herauskommt, ist hoechstens der normale Bedarf - niemals ein
            // Anteil, der ausschliesslich aus einem Grant entstuende.
            val rq = r.upfrontChain
            val qq = q.upfrontChain
            if (rq != null && qq != null) {
                val zusatz = qq.requestedRtU - rq.requestedRtU
                assertTrue(zusatz <= qq.normalNeedBeforeMarkerFloorU + 1e-9) {
                    "Zyklus $i: Mehrmenge $zusatz ueber dem normalen Bedarf " +
                        "${qq.normalNeedBeforeMarkerFloorU} - das waere ein Grant-Anteil"
                }
                assertTrue(zusatz >= -1e-9) {
                    "Zyklus $i: der Ruhe-Ausgang darf nie WENIGER ergeben als " +
                        "der blockierte Zweig (Max, keine Ersetzung): $zusatz"
                }
                // Und die Mehrmenge ist genau der Kandidat - keine andere
                // Stelle darf sie erzeugt haben.
                assertEquals(qq.calmDemandU, if (zusatz > 1e-9) qq.requestedRtU else 0.0, 1e-9) {
                    "Zyklus $i: Mehrmenge $zusatz stammt nicht aus dem " +
                        "Ruhe-Kandidaten (${qq.calmDemandU})"
                }
            }
        }

        // BEI BEDARF NULL MUSS NULL HERAUSKOMMEN. Das ist die schaerfste
        // Form des Abendfalls: der Regler sieht keinen Bedarf, und trotzdem
        // liegt ein PRIME-Grant am MarkerFloor. Nach dem Endriegel darf
        // davon nichts uebrig sein.
        val ohneBedarf = ruhig.filter {
            it.upfrontChain?.recoveryMode == "CALM_RECOVERED" &&
                (it.upfrontChain?.normalNeedBeforeMarkerFloorU ?: 1.0) <= 1e-9
        }
        assertTrue(ohneBedarf.isNotEmpty()) {
            "der Fall 'ruhig, aber kein Bedarf' muss vorkommen, sonst prueft " +
                "die schaerfste Probe nichts"
        }
        // GEMESSEN, und die Zahl gehoert in den Test statt in einen Bericht:
        // bei normalem Bedarf 0 kommen hier 0,30 U heraus - vollstaendig aus
        // einem PRIME-Grant, den MarkerFloor um 0,30 U angehoben hat.
        //
        // DAS IST NICHT DER RUHE-AUSGANG: derselbe Zyklus im Referenzlauf
        // (Params.OFF, also BLOCKED) fordert dieselben 0,30 U an.
        //
        // WIE WEIT DAS TRAEGT - und weiter darf es nicht gelesen werden
        // (Toni 25.08. spaet): belegt ist ausschliesslich, dass
        // CALM_RECOVERED IN DIESEM TESTAUFBAU keine zusaetzliche Menge
        // gegenueber Params.OFF erzeugt. Ueber die allgemeine
        // Produktsemantik sagt der Aufbau nichts. Im ECHTEN Abendverlauf
        // des 25.08. forderte das Geraet in ALLEN 20 Phase-A-Zyklen 0 U
        // mit MEASURED_DESCENT_RISK - diese Endgate-/Latch-Lage trifft der
        // synthetische Fall gerade nicht. Ob "Bedarf 0 -> requestedRtU 0"
        // im Produkt gilt, entscheidet erst der Replay des echten
        // Verlaufs, nicht dieser Test.
        ohneBedarf.forEach { o ->
            val i = ruhig.indexOf(o)
            val k = o.upfrontChain!!
            val ref = referenz[i].upfrontChain!!
            assertEquals(0.0, ref.normalNeedBeforeMarkerFloorU, 1e-9,
                         "Zyklus $i: der Referenzlauf muss denselben Nullbedarf sehen")
            assertEquals(0.0, k.calmDemandU, 1e-9,
                         "Zyklus $i: bei Bedarf 0 ist der Ruhe-Kandidat zwingend 0")
            assertEquals(ref.requestedRtU, k.requestedRtU, 1e-9) {
                "Zyklus $i: bei Bedarf 0 darf der Ruhe-Ausgang die Endmenge " +
                    "nicht veraendern - Referenz ${ref.requestedRtU} U, " +
                    "ruhig ${k.requestedRtU} U (Grant ${k.grantU} aus " +
                    "${k.grantSource}, MarkerFloor hob ${k.markerFloorLiftU} U)"
            }
            // Und der Anteil stammt nachweislich aus dem Grant, nicht aus
            // Bedarf - genau das macht ihn zum Pruefgegenstand.
            assertTrue(k.markerFloorLiftU > 0.0 && k.grantSource != null) {
                "Zyklus $i: dieser Fall soll den Grant-finanzierten Anteil " +
                    "treffen; ohne Anhebung prueft er nichts"
            }
        }

        // Und in KEINEM ruhigen Zyklus darf der Sofortanteil-Grant am Boden
        // ankommen - das waere der Vollbatch ueber eine Nachbarautorisierung.
        ruhig.filter { it.upfrontChain?.recoveryMode == "CALM_RECOVERED" }.forEach { o ->
            val k = o.upfrontChain!!
            assertTrue(k.grantSource != "MEAL_UPFRONT") {
                "im ruhigen Pfad darf kein Sofortanteil-Grant am MarkerFloor " +
                    "ankommen, war ${k.grantU} U"
            }
        }
    }

    /**
     * DIE MUTATIONSPROBE ZUR OBIGEN: gaebe der ruhige Pfad den Vollbatch
     * frei, muesste die Referenzgleichheit brechen. Der Test hier faehrt
     * denselben Ausschnitt mit FULL_BATCH_ELIGIBLE-Semantik - erzwungen
     * ueber eine schnelle Erholung - und belegt, dass der Ausschnitt
     * ueberhaupt empfindlich ist.
     */
    @Test
    fun `die Referenzgleichheit ist empfindlich - der Vollbatchpfad bricht sie`(@TempDir dir: File) {
        val referenz = ruheLauf(File(dir, "ref"), null)
        // +0,30/min statt +0,10: ueber der Erholungsschwelle des allgemeinen
        // Latches, also FULL_BATCH_ELIGIBLE statt CALM_RECOVERED.
        val schnell = run {
            aufschubAn = true
            upfrontAnteil = 1.0
            primeHuelleU = 3.75
            fundamentAnteil = 0.8
            mahlzeitMitRuhe(File(dir, "rising"),
                            app.aaps.fuse.core.controller.UpfrontRecovery.Params.OFF)
            flach = 150.0
            steigungProMin = -1.5
            bolusIobU = 2.0
            val alle = mutableListOf<FuseCycleRunner.Outcome>()
            repeat(40) { i ->
                if (i == 8) {
                    steigungProMin = 0.30
                    bolusIobU = 0.5
                }
                alle += transport(File(dir, "rising"))
            }
            alle
        }
        val unterschiede = referenz.zip(schnell).count { (r, q) ->
            kotlin.math.abs(r.decision.smbU - q.decision.smbU) > 1e-9
        }
        assertTrue(unterschiede > 0) {
            "wenn der Vollbatchpfad denselben Verlauf erzeugt wie der " +
                "blockierte, prueft die Referenzgleichheit nichts"
        }
    }

    /**
     * P0.2: DIE VERSCHIEBUNG BUCHT GENAU EINMAL - und ob im selben Zyklus
     * schon ein Schritt herausgeht, steht ausdruecklich da.
     *
     * `DeferredPrime.releaseStep` laeuft NACH MarkerFloor und sieht den
     * frisch verschobenen Betrag. Das ist nicht zwingend falsch, darf aber
     * nicht stillschweigend geschehen: sonst heisst "verschoben" im Bericht,
     * waehrend tatsaechlich sofort ein Schritt angefordert wurde.
     */
    @Test
    fun `die Verschiebung bucht genau einmal und haelt die Bilanz`(@TempDir dir: File) {
        val alle = ruheLauf(dir, app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.SHIFT_TO_DEFERRED)
        val mitShift = alle.withIndex()
            .filter { (_, o) -> (o.upfrontChain?.calmShiftedU ?: 0.0) > 0.0 }
        assertEquals(1, mitShift.size) {
            "genau EINE Buchung, war: " + mitShift.map { it.index }
        }
        val (i, s) = mitShift.single()

        alle.drop(i + 1).forEachIndexed { k, o ->
            assertEquals(0.0, o.upfrontChain?.calmShiftedU ?: 0.0, 1e-9,
                         "kein zweiter Transfer im Folgezyklus ${i + 1 + k}")
        }

        // DIE GRUNDLINIE IST DER OFFENE BETRAG ZUR BUCHUNGSZEIT, nicht der
        // des Vorzyklus. Zwischen zwei Zyklen kann regulaer etwas geliefert
        // worden sein; die erste Fassung dieses Tests las diese Lieferung als
        // Buchungsloch (2,05 + 0,35 gegen 2,70 statt gegen 2,40).
        val offenVorher = s.upfrontChain!!.upfrontOpenU
        assertTrue(offenVorher > 0.0, "vor der Verschiebung muss etwas offen sein")
        assertEquals(offenVorher, s.phaseAUpfrontTransferredU + s.phaseAUpfrontLapsedU, 1e-6) {
            "transferred + lapsed muss dem offenen Betrag entsprechen: " +
                "${s.phaseAUpfrontTransferredU} + ${s.phaseAUpfrontLapsedU} vs $offenVorher"
        }
        assertEquals(0.0, s.phaseAUpfrontPendingU, 1e-9,
                     "nach der Verschiebung ist nichts mehr offen")

        assertEquals(0.0, s.deferredPrimeReleasedU, 1e-9) {
            "im Verschiebezyklus darf 'verschoben' nicht heimlich " +
                "'sofort angefordert' heissen - freigegeben wurden " +
                "${s.deferredPrimeReleasedU} U"
        }
    }


    /**
     * DER REALE ABENDFALL ALS NULLVERTRAG (Toni 25.08. spaet).
     *
     * Die Geometrie des 25.08., 18:07-18:27, nachgestellt:
     *
     *   Sofortanteil offen, aktuelles Abwaertsrisiko beendet,
     *   NUR der historische Latch blockiert, Ruhe-Streak erreicht,
     *   BG knapp ueber dem Guard-Boden, normaler Bedarf 0
     *
     * Er ist ausdruecklich KEINE Wirksamkeitsprobe - dafuer gibt es
     * `der Ruhe-Kandidat entriegelt genau den historischen Latch`. Hier
     * wird die andere Haelfte belegt: dass aus "der Riegel ist abgestanden"
     * eben NICHT die volle Autorisierung wird. Genau das waere passiert,
     * haette der Ruhe-Ausgang den Vollbatchpfad erreicht: 3,60 U bei BG 78
     * und acht mg/dl Abstand zum Boden, in einem Zyklus mit
     * `insulinReq <= 0`.
     */
    @Test
    fun `im Abendfall bleibt die Anforderung bei Bedarf 0 auch dann 0`(@TempDir dir: File) {
        // DIE ECHTE GEOMETRIE, nicht irgendeine fallende. Der erste Anlauf
        // dieses Tests fiel von 150 mit -3,0/min - zehnmal steiler als der
        // gemessene Abendverlauf (-0,29/min). Die Riegel-Ursachen stimmten
        // dabei sogar (20x CURRENT_DESCENT_RISK, dann 20x HISTORICAL_LATCH),
        // aber CALM_RECOVERED entstand nie: nach einem so steilen Sturz
        // braucht die UKF viel zu lange, um ueber die Ruheschwelle zu
        // kommen.
        //
        // Gemessen am echten Fall: BG 75-79, `lowThreat` durchgehend NONE,
        // 13 von 23 Zyklen voellig ohne aktuelle Gefahr, Latchgrund
        // WAITING_RATE. Ein SANFTER Abstieg dicht ueber dem Guard-Boden
        // stellt genau das her - der Boden liegt im Horizont (das Risiko
        // feuert), und die Erholung ist flach genug, dass der Latch
        // WAITING_RATE bleibt.
        val alle = ruheLauf(
            dir, app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
            zyklen = 45, abstiegBg = 82.0, abstiegRate = -0.5, abstiegIob = 2.0,
            wendeZyklus = 6, ruheRate = 0.10, ruheIob = 0.3,
        )
        val ruhig = alle.filter { it.upfrontChain?.recoveryMode == "CALM_RECOVERED" }
        assertTrue(ruhig.isNotEmpty()) {
            "die Abendlage muss erreicht werden. Riegel-Ursachen: " +
                alle.mapNotNull { it.upfrontChain?.descentGateCause }
                    .groupingBy { it }.eachCount() +
                "; Ruhe-Modi: " +
                alle.mapNotNull { it.upfrontChain?.recoveryMode }
                    .groupingBy { it }.eachCount() +
                "; Ablehnungsgruende: " +
                alle.mapNotNull { it.upfrontChain?.recoveryDenial }
                    .groupingBy { it }.eachCount() +
                "; Streaks: " +
                alle.mapNotNull { it.upfrontChain?.recoveryStreak }.distinct().sorted() +
                "; Batchzustaende: " +
                alle.map { it.phaseAUpfrontState ?: "-" }.groupingBy { it }.eachCount() +
                "; offen: " +
                alle.map { String.format("%.2f", it.phaseAUpfrontPendingU) }
                    .distinct().sorted() +
                "; Phasen: " +
                alle.map { it.mealFoundation.phase }.groupingBy { it }.eachCount()
        }

        // Der Fall ist nur dann der Abendfall, wenn wirklich etwas offen ist.
        val offen = ruhig.maxOf { it.phaseAUpfrontPendingU }
        assertTrue(offen >= 1.0) {
            "es muss ein nennenswerter Sofortanteil offen sein, war $offen U"
        }

        // DER NULLVERTRAG, Zyklus fuer Zyklus.
        var ohneBedarf = 0
        ruhig.forEach { o ->
            val k = o.upfrontChain!!
            if (k.normalNeedBeforeMarkerFloorU > 0.0) return@forEach
            ohneBedarf++
            assertEquals(0.0, k.calmDemandU, 1e-9,
                         "bei Bedarf 0 entsteht kein Ruhe-Kandidat")
            assertEquals(0.0, k.requestedRtU, 1e-9) {
                "bei Bedarf 0 bleibt die Anforderung 0 - war ${k.requestedRtU} U " +
                    "bei ${k.upfrontOpenU} U offen, Grant ${k.grantU} aus ${k.grantSource}"
            }
            assertTrue(k.grantSource != "MEAL_UPFRONT") {
                "und aus dem abgestandenen Riegel darf nie der Sofortanteil werden"
            }
            // HIER STAND `requestedRtU < upfrontOpenU`, und das war zu
            // schwach (Toni 25.08. spaet): ein fehlerhafter Vollbatch, den
            // irgendein Deckel auf einen Bruchteil kappt, haette die
            // Ungleichung bestanden.
            //
            // NICHT maxSMB - den umgeht der Sofortanteil ausdruecklich, das
            // ist gerade sein Zweck. Aber iobTH, maxIOB, die
            // Transporthaftung, die Resthuelle und die nachgelagerten
            // AAPS-/Pumpengrenzen koennen ihn sehr wohl verkleinern; jede
            // davon haette den Fehler unter der Schwelle versteckt.
            //
            // Die starke Fassung ist `requestedRtU == 0` oben - sie laesst
            // gar keinen Anteil zu, gedeckelt oder nicht.
            //
            // WAS HIER NICHT STEHEN DARF, und der erste Anlauf tat es:
            // `markerFloorLiftU == 0`. Gemessen hebt MarkerFloor in diesen
            // Zyklen sehr wohl 0,30 U an - den gewoehnlichen PRIME-Boden,
            // den es unabhaengig vom Ruhe-Ausgang gibt. Danach nullt der
            // historische Latch die Menge wieder, weshalb `requestedRtU`
            // trotzdem 0 ist. Eine Zusicherung "gar kein Lift" haette also
            // eine Eigenschaft verlangt, die das System nicht hat, und
            // waere an vorbestehendem Verhalten gescheitert statt an einem
            // Fehler des Ruhe-Ausgangs.
            //
            // Verlangt ist "kein Lift AUF DEN CALM-ANTEIL" - und der ist
            // hier 0, also kann keine Anhebung ihm zugerechnet werden. Die
            // Zurechenbarkeit selbst prueft der Referenzvergleich, der
            // markerFloorLiftU gegen den BLOCKED-Lauf stellt.
            assertEquals(0.0, k.calmDemandU, 1e-9,
                         "und der Calm-Anteil, auf den nichts gehoben werden darf, ist 0")
        }
        assertTrue(ohneBedarf > 0) {
            "der Fall 'ruhig, aber kein Bedarf' muss vorkommen - sonst prueft " +
                "der Nullvertrag nichts"
        }

        // UND DIE GEGENRICHTUNG: solange das AKTUELLE Risiko lief, war der
        // Riegel CURRENT_DESCENT_RISK und nichts wurde angefordert.
        val mitRisiko = alle.filter {
            it.upfrontChain?.descentGateCause == "CURRENT_DESCENT_RISK"
        }
        assertTrue(mitRisiko.isNotEmpty(), "der Abstieg muss echtes Risiko erzeugt haben")
        mitRisiko.forEach { o ->
            val k = o.upfrontChain!!
            assertEquals(0.0, k.calmDemandU, 1e-9,
                         "bei aktuellem Risiko gibt es keinen Ruhe-Kandidaten")
            assertEquals(0.0, k.requestedRtU, 1e-9,
                         "und es wird nichts angefordert")
            assertTrue(o.upfrontChain?.recoveryMode != "CALM_RECOVERED") {
                "aktuelles Risiko ist absolut - es kann keinen ruhigen Pfad geben"
            }
        }
    }

    /**
     * DER DOSIERWIRKSAME MODUS IM ABENDFALL (Bauauftrag Toni 25.08. spaet).
     *
     * Genau die Lage, in der die beiden anderen Behandlungen nichts
     * ausrichten: normaler Bedarf 0, also gibt `DEMAND_LIMITED` per Vertrag
     * nichts, und `SHIFT_TO_DEFERRED` verschiebt nur. `CALM_BATCH` muss
     * hier liefern - sonst loest der dritte Modus das Problem nicht, fuer
     * das er gebaut wurde.
     *
     * Gegen `Params.OFF` gestellt, damit die Mehrmenge nachweislich vom
     * Modus kommt und nicht vom Verlauf.
     */
    @Test
    fun `CALM_BATCH gibt den offenen Sofortanteil frei, wo Bedarf 0 ist`(@TempDir dir: File) {
        fun lauf(b: app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment?) = ruheLauf(
            File(dir, b?.name ?: "off"), b,
            zyklen = 45, abstiegBg = 82.0, abstiegRate = -0.5, abstiegIob = 2.0,
            wendeZyklus = 6, ruheRate = 0.10, ruheIob = 0.3,
        )
        val blockiert = lauf(null)
        val batch = lauf(app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.CALM_BATCH)

        val ruhig = batch.filter { it.upfrontChain?.recoveryMode == "CALM_RECOVERED" }
        assertTrue(ruhig.isNotEmpty()) {
            "die Ruhelage muss erreicht werden, Modi: " +
                batch.mapNotNull { it.upfrontChain?.recoveryMode }.groupingBy { it }.eachCount()
        }
        // Der Fall taugt nur, wenn es Ruhezyklen OHNE Normalbedarf gibt -
        // sonst haette auch DEMAND_LIMITED geliefert und der dritte Modus
        // bewiese nichts Eigenes.
        assertTrue(
            ruhig.any { (it.upfrontChain?.normalNeedBeforeMarkerFloorU ?: 1.0) <= 1e-9 },
        ) {
            "mindestens ein Ruhezyklus ohne Normalbedarf wird gebraucht, Bedarfe: " +
                ruhig.map { it.upfrontChain?.normalNeedBeforeMarkerFloorU }.distinct()
        }

        val ohne = blockiert.sumOf { (it.upfrontChain?.requestedRtU ?: 0.0).coerceAtLeast(0.0) }
        val mit = batch.sumOf { (it.upfrontChain?.requestedRtU ?: 0.0).coerceAtLeast(0.0) }
        assertTrue(mit > ohne + 1e-9) {
            "CALM_BATCH muss mehr freigeben als der blockierte Zweig: $mit vs $ohne"
        }

        // ---- EXACTLY ONCE ------------------------------------------------
        //
        // Die Summe allein beweist das NICHT: acht wiederholte 3-U-An-
        // forderungen haetten sie ebenso bestanden. Genau diese acht Zyklen
        // hat eine Zwischenfassung erzeugt, bevor der Endriegel sie nullte.
        val anfragen = batch.withIndex()
            .filter { (_, o) -> o.phaseAUpfrontRequestedU > 0.0 }
        assertEquals(1, anfragen.size) {
            "genau EINE Sofortanteil-Anforderung, war: " +
                anfragen.map { "Zyklus ${it.index}=${it.value.phaseAUpfrontRequestedU}" }
        }
        val (iAnf, anf) = anfragen.single()

        // Die angeforderte Menge ist genau der offene Sofortanteil des
        // Zyklus davor - nicht mehr, nicht weniger.
        val offenVorher = batch[iAnf - 1].phaseAUpfrontPendingU
        assertEquals(offenVorher, anf.phaseAUpfrontRequestedU, 1e-6) {
            "die Anforderung muss dem offenen Anteil entsprechen: " +
                "${anf.phaseAUpfrontRequestedU} vs $offenVorher"
        }

        // Alle spaeteren Ruhezyklen fordern nichts mehr an.
        batch.drop(iAnf + 1).forEachIndexed { k, o ->
            assertEquals(0.0, o.phaseAUpfrontRequestedU, 1e-9) {
                "Zyklus ${iAnf + 1 + k}: keine zweite Anforderung"
            }
        }

        // ---- PROVENIENZ AM ENDRIEGEL -------------------------------------
        //
        // Durchgelassen wird HOECHSTENS die autorisierte Grantmenge. Ein
        // gleichzeitig groesserer Normal- oder Liveness-Kandidat bleibt vom
        // historischen Latch blockiert.
        val k = anf.upfrontChain!!
        assertTrue(k.requestedRtU <= k.grantU + 1e-9) {
            "am Endriegel darf hoechstens der Grant vorbei: " +
                "${k.requestedRtU} vs Grant ${k.grantU}"
        }
        assertEquals("MEAL_UPFRONT", k.grantSource) {
            "und zwar ausschliesslich der Sofortanteil-Grant"
        }

        // KEIN Zyklus mit aktueller Gefahr darf dabei geliefert haben.
        batch.forEach { o ->
            val c = o.upfrontChain ?: return@forEach
            if (o.phaseAUpfrontRequestedU > 0.0)
                assertEquals("none", c.currentHazard) {
                    "bei aktueller Gefahr darf der Batch nicht heraus: ${c.currentHazard}"
                }
        }
    }

    /**
     * DIE POSITIVPROBE FUER DEMAND_LIMITED (Toni 25.08. spaet).
     *
     * Die Ziellage, und jede Zeile davon ist noetig:
     *
     *   historischer Latch aktiv, Grund WAITING_RATE
     *   aktuelles descentRisk false
     *   normaler Bedarf > 0
     *   PRIME-Grant vorhanden
     *   baseline requested = 0
     *   -> calm requested > 0, hoechstens der reine Normalbedarf,
     *      und der Calm-Anteil traegt KEINE Grantquelle
     *
     * Der reale Abendfall ist dafuer ausdruecklich NICHT geeignet: dort war
     * der normale Bedarf am Ende 0. Er prueft den Nullvertrag, nicht die
     * Wirksamkeit.
     */
    @Test
    fun `der Ruhe-Kandidat entriegelt genau den historischen Latch`(@TempDir dir: File) {
        // SCHNELLER ABSTIEG AUS DER HOEHE: der Boden liegt damit im harten
        // Horizont, das aktuelle Risiko feuert und der Latch wird scharf.
        // Danach ein langsamer Anstieg auf noch hohem BG - Bedarf > 0,
        // Guard-Abstand gross, Rate unter der Erholungsschwelle.
        val alle = ruheLauf(
            dir, app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
            zyklen = 45, abstiegBg = 180.0, abstiegRate = -4.0, abstiegIob = 2.5,
            wendeZyklus = 10, ruheRate = 0.15, ruheIob = 0.3,
        )
        val ruhig = alle.filter { it.upfrontChain?.recoveryMode == "CALM_RECOVERED" }
        val ziel = ruhig.filter {
            val k = it.upfrontChain!!
            k.descentGateCause == "HISTORICAL_LATCH" &&
                k.normalNeedBeforeMarkerFloorU > 0.0
        }
        assertTrue(ziel.isNotEmpty()) {
            "Ziellage nicht erreicht. Ruhe-Zyklen: ${ruhig.size}; " +
                "Riegel-Ursachen: " +
                ruhig.groupingBy { it.upfrontChain!!.descentGateCause }.eachCount() +
                "; davon mit normalem Bedarf > 0: " +
                ruhig.count { it.upfrontChain!!.normalNeedBeforeMarkerFloorU > 0.0 } +
                "; Riegel-Ursachen im GANZEN Lauf: " +
                alle.mapNotNull { it.upfrontChain?.descentGateCause }
                    .groupingBy { it }.eachCount()
        }
        ziel.forEach { o ->
            val k = o.upfrontChain!!
            assertTrue(k.calmDemandU > 0.0) {
                "in der Ziellage MUSS der Kandidat greifen - " +
                    "Bedarf ${k.normalNeedBeforeMarkerFloorU}, " +
                    "Endcheck ${k.calmDeniedByFinalVerify}"
            }
            assertTrue(k.calmDemandU <= k.normalNeedBeforeMarkerFloorU + 1e-9) {
                "hoechstens der reine Normalbedarf: ${k.calmDemandU} vs " +
                    "${k.normalNeedBeforeMarkerFloorU}"
            }
            assertTrue(k.requestedRtU > 0.0, "und er kommt am Ende auch an")
            // KEIN BODEN AUF DEM CALM-ANTEIL. Die Menge, die am Ende
            // herauskommt, IST der Kandidat - nicht der Kandidat plus eine
            // Anhebung. Der Kandidat entsteht konstruktiv nach MarkerFloor
            // und traegt `grant = null`; diese Zusicherung friert das ein,
            // statt es der Reihenfolge zu ueberlassen.
            assertEquals(k.calmDemandU, k.requestedRtU, 1e-9) {
                "der Calm-Anteil darf nicht angehoben werden: Kandidat " +
                    "${k.calmDemandU}, angefordert ${k.requestedRtU}, " +
                    "MarkerFloor hob ${k.markerFloorLiftU}"
            }
            assertTrue(k.grantSource != "MEAL_UPFRONT") {
                "und niemals ueber einen Sofortanteil-Grant finanziert"
            }
        }
    }

    /**
     * DER PROVENIENZNACHWEIS UEBER ALLE DREI GEOMETRIEN (Toni 25.08. spaet).
     *
     * Die drei Gleichheiten standen bisher nur im flachen Verlauf - also
     * ausgerechnet dort, wo der Ruhe-Kandidat nie feuert. Bewiesen war damit
     * "wenn nichts passiert, aendert sich nichts". Hier laufen sie gepaart
     * gegen den BLOCKED-Lauf in ALLEN drei Lagen, auch in denen, in denen
     * der Kandidat wirklich greift:
     *
     *   grantU, grantSource und markerFloorLiftU sind identisch
     *   -> CALM_RECOVERED aendert weder Autorisierung noch Boden
     *
     *   requestedRtU(CALM) - requestedRtU(BLOCKED) == calmDemandU
     *   -> die Mehrmenge IST der Kandidat, exakt und ausschliesslich.
     *      Keine andere Stelle darf sie erzeugt haben, und bei Bedarf 0
     *      sind beide Endanforderungen gleich (naemlich beide 0).
     */
    @Test
    fun `die Provenienz bleibt in allen drei Geometrien unveraendert`(@TempDir dir: File) {
        data class Lage(
            val name: String, val bg: Double, val rate: Double, val iob: Double,
            val wende: Int, val ruheRate: Double, val ruheIob: Double, val zyklen: Int,
        )
        val lagen = listOf(
            // flach: der Kandidat feuert nie - die Kontrolle
            Lage("flach", 150.0, -1.5, 2.0, 8, 0.10, 0.5, 40),
            // scharfer Latch bei echtem Bedarf: hier feuert er
            Lage("latch", 180.0, -4.0, 2.5, 10, 0.15, 0.3, 45),
            // die Abendgeometrie: scharfer Latch bei Bedarf 0
            Lage("abend", 82.0, -0.5, 2.0, 6, 0.10, 0.3, 45),
        )
        var gefeuert = 0
        lagen.forEach { l ->
            fun fahre(b: app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment?) =
                ruheLauf(File(dir, l.name + (b?.name ?: "ref")), b, zyklen = l.zyklen,
                         abstiegBg = l.bg, abstiegRate = l.rate, abstiegIob = l.iob,
                         wendeZyklus = l.wende, ruheRate = l.ruheRate, ruheIob = l.ruheIob)
            val blockiert = fahre(null)
            val ruhig = fahre(
                app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
            )
            assertEquals(blockiert.size, ruhig.size, "${l.name}: gleich viele Zyklen")

            blockiert.zip(ruhig).forEachIndexed { i, (b, q) ->
                val kb = b.upfrontChain
                val kq = q.upfrontChain
                if (kb == null || kq == null) return@forEachIndexed
                val wo = "${l.name} Zyklus $i"

                // DIE PROVENIENZ - unveraendert, ohne jede Bedingung.
                assertEquals(kb.grantU, kq.grantU, 1e-9, "$wo: Grantmenge")
                assertEquals(kb.grantSource, kq.grantSource, "$wo: Grantquelle")
                assertEquals(kb.markerFloorLiftU, kq.markerFloorLiftU, 1e-9,
                             "$wo: MarkerFloor-Anhebung")

                // DIE MENGE - die Mehrmenge ist exakt der Kandidat.
                val zusatz = kq.requestedRtU - kb.requestedRtU
                assertEquals(kq.calmDemandU, zusatz, 1e-9) {
                    "$wo: die Mehrmenge gegenueber BLOCKED muss exakt der " +
                        "Ruhe-Kandidat sein - Kandidat ${kq.calmDemandU}, " +
                        "Differenz $zusatz (BLOCKED ${kb.requestedRtU}, " +
                        "CALM ${kq.requestedRtU})"
                }
                if (kq.calmDemandU > 0.0) {
                    gefeuert++
                    assertTrue(kq.calmDemandU <= kq.normalNeedBeforeMarkerFloorU + 1e-9) {
                        "$wo: hoechstens der reine Normalbedarf"
                    }
                } else {
                    assertEquals(kb.requestedRtU, kq.requestedRtU, 1e-9,
                                 "$wo: ohne Kandidaten sind beide Endanforderungen gleich")
                }
            }
        }
        assertTrue(gefeuert > 0) {
            "in mindestens einer Lage muss der Kandidat feuern - sonst " +
                "belegt der Provenienznachweis nur den Leerlauf"
        }
    }


    /**
     * DER KANDIDAT DARF NUR IN DER ERLAUBTEN LAGE FEUERN.
     *
     * WARUM DIESER TEST NOETIG WAR, und der Grund ist ein Testfehler von
     * mir: die Referenzpruefung vergleicht Mengen nur in Zyklen mit
     * `calmDemandU == 0`. Feuert der Kandidat unter einer Mutation
     * HAEUFIGER, ueberspringt sie genau die neu entstandenen Zyklen - die
     * Zusicherung schaltet sich selbst ab. Gemessen: alle sechs
     * Mutationen der Kandidatenbedingung blieben gruen.
     *
     * Dieser Test prueft deshalb die Gegenrichtung: WO IMMER der Kandidat
     * gefeuert hat, muss die volle Lage vorgelegen haben.
     */
    @Test
    fun `der Ruhe-Kandidat feuert nur in der erlaubten Lage`(@TempDir dir: File) {
        // Beide Verlaeufe: der ohne scharfen Latch und der mit.
        val laeufe = listOf(
            ruheLauf(File(dir, "flach"), app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.DEMAND_LIMITED),
            ruheLauf(
                File(dir, "latch"), app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
                zyklen = 45, abstiegBg = 180.0, abstiegRate = -4.0, abstiegIob = 2.5,
                wendeZyklus = 10, ruheRate = 0.15, ruheIob = 0.3,
            ),
        )
        var gefeuert = 0
        laeufe.forEachIndexed { l, alle ->
            alle.forEachIndexed { i, o ->
                val k = o.upfrontChain ?: return@forEachIndexed
                if (k.calmDemandU <= 0.0) return@forEachIndexed
                gefeuert++
                val wo = "Lauf $l Zyklus $i"
                assertEquals("CALM_RECOVERED", k.recoveryMode,
                             "$wo: nur der ruhige Pfad darf den Kandidaten erzeugen")
                assertEquals("HISTORICAL_LATCH", k.descentGateCause) {
                    "$wo: NUR ein historischer Latch darf ueberstimmt werden, " +
                        "Ursache war ${k.descentGateCause}"
                }
                assertTrue(k.normalNeedBeforeMarkerFloorU > 0.0) {
                    "$wo: ohne echten Normalbedarf darf kein Kandidat entstehen"
                }
                assertTrue(k.calmDemandU <= k.normalNeedBeforeMarkerFloorU + 1e-9) {
                    "$wo: hoechstens der reine Normalbedarf - " +
                        "${k.calmDemandU} vs ${k.normalNeedBeforeMarkerFloorU}"
                }
                assertNull(k.calmDeniedByFinalVerify) {
                    "$wo: ein vom Endcheck verworfener Kandidat darf nicht " +
                        "als gefeuert gelten (${k.calmDeniedByFinalVerify})"
                }
                assertTrue(k.requestedRtU > 0.0) {
                    "$wo: was gefeuert hat, muss am Ende auch ankommen"
                }
            }
        }
        assertTrue(gefeuert > 0, "mindestens ein Lauf muss den Kandidaten ausloesen")
    }


    /**
     * DAS KLEMMEREIGNIS DARF NICHT KLEBEN (Toni 25.08. spaet, P1).
     *
     * Die erste Fassung hielt `openBeforeClamp`, `clampReduction` und
     * `clampReason` in Runnerfeldern und setzte sie nie zurueck. Derselbe
     * alte Clamp haette damit in JEDEM folgenden Trailzyklus erneut
     * gestanden - ohne Zeitstempel nicht als Wiederholung erkennbar, und
     * bei einer Summierung mehrfach gezaehlt.
     *
     * Die Gegenprobe ist genau die von Toni benannte: ein Zyklus klemmt,
     * der unmittelbar folgende ohne Lieferung muss `reduction == 0` und
     * `reason == null` zeigen.
     */
    @Test
    fun `das Klemmereignis gilt nur fuer seinen eigenen Zyklus`(@TempDir dir: File) {
        // DER ABENDFALL VOLLSTAENDIG: nach der Wende ein manueller
        // 4-U-Ersatzbolus. Er senkt den Huellenrest unter den offenen
        // Aufschub, und die naechste Lieferung klemmt - genau die Kette vom
        // 25.08. (18:29 Bolus, 18:36 erste Lieferung, defOpen 3,60 -> 0,45).
        val alle = ruheLauf(
            dir, app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.SHIFT_TO_DEFERRED,
            zyklen = 45, abstiegBg = 180.0, abstiegRate = -4.0, abstiegIob = 2.5,
            wendeZyklus = 10, ruheRate = 0.15, ruheIob = 0.3,
            manuellBeiZyklus = 16, manuellU = 4.0,
        )
        // (1) Die Felder sind untereinander konsistent.
        alle.forEachIndexed { i, o ->
            if (o.deferredClampReductionU > 0.0) {
                assertNotNull(o.deferredClampReason, "Zyklus $i: Kuerzung ohne Grund")
                assertTrue(o.deferredClampTs > 0L, "Zyklus $i: Kuerzung ohne Zeitstempel")
                assertTrue(o.deferredOpenBeforeClampU > 0.0,
                           "Zyklus $i: Kuerzung ohne Ausgangsbestand")
            } else {
                assertNull(o.deferredClampReason) {
                    "Zyklus $i: Grund ohne Kuerzung - das Ereignis klebt"
                }
                assertEquals(0L, o.deferredClampTs) {
                    "Zyklus $i: Zeitstempel ohne Kuerzung - das Ereignis klebt"
                }
            }
        }
        // (2) KEIN Zeitstempel darf zweimal auftauchen. Genau so saehe ein
        // klebendes Ereignis aus, und genau so wuerde es doppelt gezaehlt.
        val stempel = alle.map { it.deferredClampTs }.filter { it > 0L }
        // WARUM HIER NICHT GEKLEMMT WIRD, und die erste Erklaerung war
        // falsch: der eingespeiste manuelle Bolus erreicht
        // `manualBolusAfterMarkerU` sehr wohl - das Rig ist NICHT kaputt.
        // Der Grund ist die Huelle: in diesem Lauf ist `totalBudgetU` 3,75,
        // und 3,75 - 4,00 ergibt Huellenrest 0. Bei `openU == 0` kehrt
        // `clampToHull` sofort zurueck, es gibt nichts wegzunehmen.
        // Der ECHTE Klemmfall steht als eigene Pflichtprobe darunter.
        //
        // Die Punkte (1) und (2) pruefen deshalb den Leerlauf: dass ein
        // Zyklus OHNE Klemmung auch keine berichtet. Genau das war der
        // Fehler der ersten Fassung - die prozessweiten Felder hielten das
        // Ereignis fest -, und dagegen ist der Test wirksam.
        //
        // NICHT geprueft ist der Klemmfall selbst. Er ist konstruktiv
        // abgesichert: `klemmung` ist eine lokale Variable in `buche()` mit
        // Nullvorgabe, und die Felder reisen im zurueckgegebenen [Buchung]
        // mit. Ein Zyklus ohne Klemmung KANN keine berichten. Das ersetzt
        // keinen Test des Klemmfalls - solange das Rig ihn nicht herstellt,
        // steht diese Luecke hier und wird nicht zugebaut.
        assertEquals(0, stempel.size) {
            "Aufbau hat sich geaendert: es klemmt jetzt ($stempel). Dann muss " +
                "dieser Test auf den ECHTEN Klemmfall umgestellt werden, statt " +
                "nur den Leerlauf zu pruefen."
        }
        assertEquals(stempel.size, stempel.distinct().size) {
            "jeder Klemmzeitstempel darf nur EINMAL berichtet werden, war: " +
                stempel.groupingBy { it }.eachCount().filterValues { it > 1 }
        }
        // (3) Der Huellenrest wird dagegen JEDEN Zyklus berichtet - er ist
        // ein Zustand, kein Ereignis, und schliesst das Fenster zwischen
        // manuellem Bolus und naechster Lieferung.
        assertTrue(alle.any { it.deferredHullRemainingU > 0.0 }) {
            "der Huellenrest muss als laufender Zustand sichtbar sein"
        }
    }

    /**
     * DIE PFLICHTPROBE DES KLEMMFALLS (Toni 25.08. spaet).
     *
     * Der Leerlauftest darueber beweist nur "kein Ereignis ohne Clamp". Was
     * er nicht beweist, sind die WERTE beim Clamp. Hier steht die Kette des
     * echten Abendfalls mit denselben Groessen:
     *
     *   Huelle 4,50, manueller NORMAL-Bolus 4,00  -> Huellenrest 0,50
     *   offener Aufschub 3,60, erste Abgabe 0,05  -> Rest 0,45
     *   erwartet: vorher 3,60, Kuerzung 3,15, Grund MANUAL_BOLUS_COVERAGE,
     *             Zeitstempel dieses Zyklus
     *   Folgezyklus ohne Clamp: Kuerzung 0, Grund null, Zeitstempel 0
     *
     * WARUM DIE HUELLE 4,50 SEIN MUSS: bei 3,75 ergibt 3,75 - 4,00 einen
     * Rest von 0, `clampToHull` kehrt bei leerem Bestand sofort zurueck,
     * und es gaebe gar kein Ereignis. Genau daran ist die erste Fassung
     * gescheitert - nicht an der Behandlungssicht des Rigs.
     */
    @Test
    fun `der Klemmfall meldet Bestand, Kuerzung, Grund und Zeitstempel`(@TempDir dir: File) {
        val alle = ruheLauf(
            dir, app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.SHIFT_TO_DEFERRED,
            zyklen = 45, abstiegBg = 180.0, abstiegRate = -4.0, abstiegIob = 2.5,
            // +0,25/min: UEBER der Erholungsschwelle. Der historische
            // Latch loest, es wird wieder geliefert - und nur eine
            // Lieferung loest die Klemmung ueberhaupt aus
            // (`clampToHull` laeuft nur bei `actuatedU > 0`).
            wendeZyklus = 10, ruheRate = 0.25, ruheIob = 0.0,
            manuellBeiZyklus = 16, manuellU = 4.0, huelleU = 4.5,
        )
        // NUR die Klemmungen NACH dem manuellen Bolus. Davor gibt es
        // ebenfalls welche - dort ist der Grund korrekterweise
        // AUTOMATIC_DELIVERY, weil noch kein manueller Bolus wirkte. Die
        // erste Fassung griff blind die erste Klemmung und pruefte damit
        // den falschen Fall.
        val klemmen = alle.withIndex().filter { (_, o) ->
            o.deferredClampReductionU > 0.0 && (o.manualBolusAfterMarkerU ?: 0.0) >= 4.0
        }
        // Und die Gegenrichtung gehoert dazu: VOR dem Bolus muss der Grund
        // automatisch lauten, sonst waere die Unterscheidung wertlos.
        val vorherigeKlemmung = alle.firstOrNull {
            it.deferredClampReductionU > 0.0 && (it.manualBolusAfterMarkerU ?: 0.0) <= 0.0
        }
        if (vorherigeKlemmung != null)
            assertEquals("AUTOMATIC_DELIVERY", vorherigeKlemmung.deferredClampReason,
                         "ohne manuellen Bolus ist die Ursache automatisch")
        assertTrue(klemmen.isNotEmpty()) {
            "es muss geklemmt werden - sonst prueft diese Probe nichts. " +
                "Huellenreste: " +
                alle.map { String.format("%.2f", it.deferredHullRemainingU) }.distinct().sorted() +
                " | manuell: " + alle.map { it.manualBolusAfterMarkerU }.distinct() +
                " | defOpen: " +
                alle.map { String.format("%.2f", it.deferredPrimeOpenU) }.distinct().sorted() +
                " | Abgaben nach dem Bolus: " +
                alle.drop(17).count { it.decision.smbU > 0.0 } +
                " | Mengen: " +
                alle.drop(17).map { it.decision.smbU }.filter { it > 0.0 }.take(8)
        }
        val (i, k) = klemmen.first()

        // Die Werte des Ereignisses haengen zusammen und werden gemeinsam
        // geprueft - eine Kuerzung ohne passenden Ausgangsbestand waere
        // eine andere Geschichte als die erzaehlte.
        assertEquals(k.deferredOpenBeforeClampU - k.deferredClampHullAtClampU,
                     k.deferredClampReductionU, 1e-6) {
            "Kuerzung muss Bestand minus Huellenrest sein: " +
                "${k.deferredOpenBeforeClampU} - ${k.deferredClampHullAtClampU} " +
                "vs ${k.deferredClampReductionU}"
        }
        assertEquals("MANUAL_BOLUS_COVERAGE", k.deferredClampReason) {
            "der manuelle Bolus allein erklaert die Klemmung"
        }
        assertTrue(k.deferredClampTs > 0L, "mit Zeitstempel")
        assertEquals(k.deferredClampHullAtClampU, k.deferredHullRemainingU, 1e-6,
                     "Huellenrest zum Klemmzeitpunkt und laufender Zustand sind derselbe Wert")

        // DER FOLGEZYKLUS: kein Ereignis mehr. Genau hier haette die
        // klebende Fassung dasselbe Ereignis erneut berichtet.
        val danach = alle.drop(i + 1).firstOrNull { it.deferredClampReductionU <= 0.0 }
        assertNotNull(danach, "nach der Klemmung muss ein Zyklus ohne Klemmung folgen")
        assertNull(danach!!.deferredClampReason, "kein Grund ohne Kuerzung")
        assertEquals(0L, danach.deferredClampTs, "kein Zeitstempel ohne Kuerzung")
        assertEquals(0.0, danach.deferredOpenBeforeClampU, 1e-9, "kein Bestand ohne Kuerzung")

        // UND DAS PHASE-B-BUDGET BLEIBT UNBERUEHRT - der manuelle Bolus
        // ersetzte die ausgefallene Phase A, er kuerzt nicht den Nachlauf.
        val budgets = alle.mapNotNull { it.mealFoundation.phaseBBudgetU }.distinct()
        assertTrue(budgets.size <= 2) {
            "das Phase-B-Budget darf sich durch den manuellen Bolus nicht " +
                "veraendern, sah aber: $budgets"
        }
    }

}

package app.aaps.fuse.plugin

import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.extensions.round
import app.aaps.fuse.core.util.Sha
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.fuse.plugin.ledger.EpisodeBudgets
import app.aaps.fuse.core.observer.Health
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import app.aaps.fuse.core.controller.MarkerReauthorization
import app.aaps.fuse.core.controller.PartialRecoveryGate
import app.aaps.fuse.core.controller.PartialTbrOwnership
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.fuse.core.ledger.NotSentProof
import app.aaps.fuse.core.ledger.QueueRejectReason
import app.aaps.fuse.plugin.ledger.LedgerPublicationGate
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.predictor.PredictorReason
import app.aaps.fuse.core.predictor.PredictorOutcome
import app.aaps.fuse.core.predictor.TrajectoryCore
import app.aaps.fuse.core.controller.EvidenceStock
import app.aaps.fuse.core.controller.DescentRecoveryLatch
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

    /** v4: die Insulinkurve des Pruefstands - die des Fork-Rigs (Lyumjev, DIA des Testprofils). */
    private var insulin: ICfg = rigICfg()

    /** v4: das Profil traegt die Kurve (FUSE-Vertrag 3); getProfile liefert ein EffectiveProfile. */
    private lateinit var rigProfil: EffectiveProfile

    /** v4: ein verworfener Einheitskern (im Fork: ein Insulin-Plugin, das NaN liefert) - ueber die Sampler-Naht. */
    private var kaputterKern = false
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
     * SENSORZEIT VOR RECHENZEIT. Auf dem Geraet liegt der letzte CGM-Wert
     * einige Sekunden vor dem Rechenzeitpunkt; im Rig fielen beide bisher
     * exakt zusammen. Genau in dieser Luecke scheiterte die Teilratensuche
     * im Feld (Profil-Slots ab Rechenzeit, Fenster ab Sensorzeit).
     * Positiv: die Serie liegt um so viele ms VOR der Uhr.
     */
    private var quellVersatzMs = 0L

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

    /** Rebound-Evidenz-Ausnahme am Liveness-Tor - Default AUS wie in Produktion. */
    private var livenessAusnahmeAn = false

    /** Buchungsbedingter Ausgang ohne neue Sperre - Default AUS wie in Produktion. */
    private var buchungsAusgangAn = false

    /** Halte-Anhebung im Liveness-Bedarf - Default AUS wie in Produktion. */
    private var halteAnhebungAn = false
    /** Frueher adaptiver MEAL-Horizont (H8, Toni 18.09.) - Rig-Hebel, Default 0 = AUS. */
    private var fruehHorizont = 0

    /** Masterschalter der Prognose-Shadows (Default AN wie in Produktion). */
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
    /** Der Schalter der Teilbasal-Rueckkehr - Default AUS wie in Produktion. */
    private var teilbasalAn = false
    private var zeroLatchRuheZyklen = 20
    private var zeroLatchRuheAbstand = 30.0
    /** Variante 1 des Nullphasen-Vergleichs; 0 = AUS = Produktionsstand. */
    private var zeroLatchGrundWegZyklen = 0
    /** Variante 2; 0 = AUS = Produktionsstand. */
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
        } ?: generateSequence(start - quellVersatzMs) { it + (messTaktMs ?: taktMs) }
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
                val vf = v + (formZusatz?.invoke(min) ?: 0.0)
                val vr = if (rueckfuehrung) vf - abgabeGlukoseWirkung(ts) else vf
                GV(
                    timestamp = ts, value = vr, raw = vr, noise = 0.0,
                    sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT
                )
            }
            .toList()

    private fun iob(atTs: Long) = IobTotal(roundUp(atTs)).also {
        // Zeitpunkttreuer Replay: ausschliesslich Behandlungen, die bis zum
        // Rechenzeitpunkt bekannt waren - keine Trail-Karte (s. `asOf`).
        if (asOf != null) {
            val (bolus, basal, akt) = asOfIob(clock, atTs, nurBolus = false)
            val rueck = if (rueckfuehrung) abgabeWirkung(atTs) else null
            it.iob = bolus + basal + (rueck?.first ?: 0.0)
            it.basaliob = basal
            it.activity = akt + (rueck?.second ?: 0.0)
            it.valid = iobGueltig
            return@also
        }
        // BOLUS-IOB = iob - basaliob. Das Rig stellte beide auf 0, damit war
        // eine Bolus-Ueberdeckung nie darstellbar - der Riegel gegen
        // gemessenes Abwaertsrisiko haette hier nie greifen koennen.
        val karte = iobProTs?.let { k ->
            val unter = k.floorEntry(atTs)
            val ueber = k.ceilingEntry(atTs)
            val nah = listOfNotNull(unter, ueber).minByOrNull { e -> kotlin.math.abs(e.key - atTs) }
            nah?.takeIf { e -> kotlin.math.abs(e.key - atTs) <= 90_000L }?.value
        }
        val rueck = if (rueckfuehrung) abgabeWirkung(atTs) else null
        val gesamt = (karte?.first ?: bolusIobU ?: 0.0) + (rueck?.first ?: 0.0)
        // DIE BOLUS-IOB IST NICHT DIE GESAMT-IOB (Rig-Befund 25.08. spaet).
        // `basaliob = 0` machte aus jeder negativen Gesamt-IOB eine negative
        // BOLUS-IOB - und die bricht als Integritaetsbefund den ganzen
        // Zyklus ab. Im Replay waren das zahlreiche Zyklen im
        // Phase-A-Fenster: Gesamt-IOB leicht negativ (Basal
        // zurueckgehalten), Bolus-IOB am Geraet aber positiv. Traegt der
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
        // lehnte folgerichtig mit NON_FINITE_INPUT ab - fast alle Zyklen
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
        val bolusOverlayU = if (gegenfaktualBolusOverlay) rueck?.first ?: 0.0 else 0.0
        it.basaliob = bolusAusTrail?.let { historischBolus ->
            gesamt - (historischBolus + bolusOverlayU)
        } ?: 0.0
        it.activity = (karte?.second ?: aktivitaet) + (rueck?.second ?: 0.0); it.valid = iobGueltig
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

    /** Additiver Formzusatz der erzeugten Bahn [mg/dl] je Minute seit `start` -
     *  fuer Dip/Wiederanstieg und Sensorartefakte MIT Rueckfuehrung. null = keiner. */
    private var formZusatz: ((Double) -> Double)? = null

    /** Takt der erzeugten CGM-Werte, getrennt vom Loop-Takt; null = gleich [taktMs]. */
    private var messTaktMs: Long? = null

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

    // ---- AUFNAHMENACHWEIS (03.10.2026): AAPS-NAHES IOB -------------------
    /**
     * IOB wie `calculateFromTreatmentsAndTemps`, nur der Bolusanteil, aus
     * [boluses]: gueltige Boli in [Schluessel - DIA, Schluessel), Schluessel =
     * aufgerundete Minute, je mit dem aktiven Insulinmodell, auf 0,001
     * gerundet. Vergangene Schluessel (Testuhr) landen in [aapsCache] - nur so
     * laesst sich ein aelterer Eintrag ueberhaupt darstellen. Die Bolus-Lesung
     * liefert dann wie die DAO nur gueltige Datensaetze. Aus = die bisherigen
     * Hebel.
     */
    private var aapsNah = false
    private val aapsCache = HashMap<Long, IobTotal>()

    /** Laeuft vor JEDER IOB-Abfrage - der Ort fuer Aenderungen zwischen den
     *  beiden Bolus-Lesungen eines Zyklus. */
    private var vorIobAbfrage: (() -> Unit)? = null

    /** Der Basalanteil des AAPS-nahen IOB (Vorgabe: keiner). Dieselbe Funktion
     *  beantwortet `calculateIobToTimeFromTempBasalsIncludingConvertedExtended`
     *  - so muss die Nachrechnung des Aufnahmenachweises ihn treffen. */
    private var aapsNahBasal: (Long) -> IobTotal = { t -> IobTotal(t) }

    /** Verlaengerte Boli, wie die DAO sie nach Startzeit liefert (Vorgabe: keine). */
    private var verlaengerteBoli: List<app.aaps.core.data.model.EB> = emptyList()

    private fun aapsNahIob(atTs: Long, zwischenspeichern: Boolean = true): IobTotal {
        vorIobAbfrage?.invoke()
        val key = if (zwischenspeichern) roundUp(atTs) else atTs
        if (zwischenspeichern) aapsCache[key]?.let { return it }
        val dia = insulin.dia
        var iob = 0.0
        var akt = 0.0
        for (b in boluses) if (b.isValid && b.timestamp < key && b.timestamp >= key - (dia * 3_600_000.0).toLong()) {
            val r = insulin.iobCalcForTreatment(b, key)
            iob += r.iobContrib
            akt += r.activityContrib
        }
        // Wie calculateFromTreatmentsAndTemps: Bolus- und Basalanteil je
        // gerundet, kombiniert, noch einmal gerundet.
        val bolus = IobTotal(key).also { it.iob = iob; it.activity = akt }.round()
        val ergebnis = IobTotal.combine(bolus, aapsNahBasal(key).round()).round().also { it.valid = true }
        if (zwischenspeichern && key <= clock) aapsCache[key] = ergebnis
        return ergebnis
    }

    /** Behandlungen aendern wie AAPS: der Cache verliert die Eintraege nach dem
     *  fruehesten betroffenen Zeitpunkt (newHistoryData). [entwerten] = false
     *  bildet das Entprellfenster ab, in dem ein aelterer Eintrag noch gilt. */
    private fun boliSetzen(neu: List<BS>, entwerten: Boolean = true) {
        val betroffen = (boluses.filter { it !in neu } + neu.filter { it !in boluses }).minOfOrNull { it.timestamp }
        boluses = neu
        if (entwerten && betroffen != null) aapsCache.keys.removeAll { it > betroffen }
    }

    /**
     * RUECKFUEHRUNG DER ABGABEN (Tonis Review 14.09.). Aus heisst: das Rig
     * bleibt offen (statisches IOB, feste Messreihe) wie bisher.
     *
     * An heisst: jede FUSE-Abgabe wird eine Behandlung. Ihr IOB und ihre
     * Aktivitaet kommen aus dem aktiven Insulinmodell (Profil-DIA), und ihre
     * Glukosewirkung (abgeflossene Menge x ISF des Zyklus) wird von der
     * gemessenen Reihe abgezogen - damit sehen Guard, BGI-Bereinigung und
     * Evidenz dieselbe Insulinwirkung. Eine Abgabe mit `wirktAufReihe = false`
     * ist ein VORBOLUS, dessen Wirkung schon in der vorgegebenen Kurve steckt:
     * er traegt IOB und Aktivitaet, veraendert die Reihe aber nicht.
     *
     * GRENZE: es gibt kein Kohlenhydratmodell - die Kurve IST die Stoerung.
     * Das Rig quantifiziert damit weder reale Unterversorgung noch den
     * spaeteren Insulinbedarf.
     *
     * NUR BESTAETIGTE ABGABEN (Tonis Review 14.09. spaet): zurueckgefuehrt
     * wird erst in [transport], NACH dem echten Publikations-Gate, und nur
     * die Menge, die das Gate versiegelt hinausgelassen hat UND deren
     * simulierter Ausgang GESENDET ist. Entfernte, nie kommandierte, auf 0
     * gekappte oder unklare Mengen erzeugen keine Wirkung. Die Abgabe landet
     * zugleich als SMB in der Behandlungsliste. Ein reiner [cycle] fuehrt
     * nichts zurueck.
     *
     * GRENZE DIESER ANNAHME (Tonis Review 15.09.): UNKLAR und UNKORRELIERT
     * ohne Wirkung sind eine SZENARIOANNAHME dieses Rigs, kein
     * Sicherheitsvertrag. Unbekannte Lieferung ist keine bewiesene
     * Nichtlieferung; fuer Transport-Sicherheit waeren "nichts geliefert",
     * "geliefert, Bestaetigung verspaetet" und "teilweise geliefert" getrennte
     * Faelle, und die unbekannte Exposition bleibt bis zur Klaerung in der
     * Haftung (Produktion: Ledger/NotSentProof). Das Rig bildet nur den ersten ab.
     *
     * VERGANGENHEIT BLEIBT: jede Abgabe traegt den ISF ihres Zyklus. Ein
     * spaeterer ISF-Wechsel veraendert die bereits erzeugte Glukosewirkung
     * frueherer Messpunkte nicht.
     */
    private var rueckfuehrung = false

    /**
     * Nur fuer den historischen Entscheidungs-Replay (Codex 18.09.): [abgaben]
     * sind hier die Differenz zwischen gegenfaktualer und historisch
     * publizierter Bolusmenge. Sie muessen deshalb auch die exportierte
     * Bolus-IOB korrigieren; die Basal-IOB des Trails bleibt unveraendert.
     */
    private var gegenfaktualBolusOverlay = false

    private data class Abgabe(val ts: Long, val u: Double, val wirktAufReihe: Boolean, val isfMgdlPerU: Double)

    private val abgaben = mutableListOf<Abgabe>()

    /** ISF des zuletzt gerechneten Zyklus - wird der naechsten bestaetigten Abgabe mitgegeben. */
    private var rueckIsf = 61.0

    /**
     * ZEITPUNKTTREUE BEHANDLUNGEN FUER DEN REPLAY (Fruehstuecksrekonstruktion,
     * Tonis Auftrag 15.09.).
     *
     * Der Runner fragt fuer JEDEN historischen Zyklus IOB und Aktivitaet auch an
     * kuenftigen Prognosepunkten ab. Die bisherige Trail-Karte kannte Werte nur an
     * den Trail-Zeitpunkten - fuer kuenftige Punkte fehlte die Insulinwirkung.
     * Spaeter aufgezeichnete Werte duerfen sie NICHT ersetzen: sie enthalten
     * Insulin, das erst spaeter abgegeben wurde (Blick in die Zukunft).
     *
     * Deshalb die AAPS-Logik nachgebildet, mit "jetzt" = Rechenzeitpunkt des
     * Zyklus: Boli nur mit Pumpen-Zeitstempel <= jetzt; temporaere Basalraten nur,
     * wenn sie bis jetzt begonnen haben, beendet am geplanten Ende, am bis jetzt
     * bekannten Folgebeginn oder Abbruch und spaetestens JETZT (wie
     * `calculateIobToTimeFromTempBasalsIncludingConvertedExtended`: laufende Rate
     * wird auf now gekuerzt, danach Profilbasal); Abfragezeit auf die Minute
     * aufgerundet (`roundUpTime`); Stuecke von etwa 5 min gegen den Basalplan.
     * Lokal gegen die aufgezeichneten Anker validiert (Bolus-/Basal-IOB <= 0,0005 U,
     * Aktivitaet <= 0,0001 U/min, ueber gut zwei Stunden am Morgen).
     */
    private class AsOfBolus(val ts: Long, val u: Double)
    private class AsOfTbr(val start: Long, val dauerMs: Long, val rate: Double, val absolut: Boolean)
    private class AsOfBehandlungen(
        val boli: List<AsOfBolus>, val tbr: List<AsOfTbr>, val abbrueche: List<Long>,
        val basalSek: List<Pair<Int, Double>>, val diaStunden: Double,
    )

    private var asOf: AsOfBehandlungen? = null

    private fun asOfLaden(datei: File): AsOfBehandlungen {
        val o = org.json.JSONObject(datei.readText())
        fun arr(k: String) = o.getJSONArray(k)
        val boli = (0 until arr("boli").length()).map { i -> arr("boli").getJSONObject(i).let { AsOfBolus(it.getLong("ts"), it.getDouble("u")) } }
        val tbr = (0 until arr("tbr").length()).map { i ->
            arr("tbr").getJSONObject(i).let { AsOfTbr(it.getLong("start"), it.getLong("dauerMs"), it.getDouble("rate"), it.getBoolean("absolut")) }
        }.sortedBy { it.start }
        val abbrueche = (0 until arr("tbrAbbruchTs").length()).map { arr("tbrAbbruchTs").getLong(it) }.sorted()
        val basal = (0 until arr("basalPlan").length()).map { i -> arr("basalPlan").getJSONObject(i).let { it.getInt("sek") to it.getDouble("rate") } }.sortedBy { it.first }
        return AsOfBehandlungen(boli, tbr, abbrueche, basal, o.getDouble("diaStunden"))
    }

    private fun asOfBasal(b: AsOfBehandlungen, ts: Long): Double {
        val sek = app.aaps.core.utils.MidnightUtils.secondsFromMidnight(ts)
        return b.basalSek.lastOrNull { it.first <= sek }?.second ?: b.basalSek.first().second
    }

    /** (Bolus-IOB, Basal-IOB, Aktivitaet) zum Zeitpunkt [atTs], wie AAPS sie bei [jetzt] kannte. */
    private fun asOfIob(jetzt: Long, atTs: Long, nurBolus: Boolean, runden: Boolean = true): Triple<Double, Double, Double> {
        val b = asOf ?: return Triple(0.0, 0.0, 0.0)
        val q = if (runden) roundUp(atTs) else atTs
        val dia = b.diaStunden
        var iobBolus = 0.0
        var iobBasal = 0.0
        var akt = 0.0
        b.boli.forEach { x ->
            if (x.ts <= jetzt && x.ts <= q) {
                val r = insulin.iobCalcForTreatment(BS(timestamp = x.ts, amount = x.u, type = BS.Type.SMB, iCfg = insulin), q)
                iobBolus += r.iobContrib
                akt += r.activityContrib
            }
        }
        if (!nurBolus) b.tbr.forEachIndexed { i, t ->
            if (t.start > jetzt || t.start > q) return@forEachIndexed
            var ende = t.start + t.dauerMs
            b.tbr.getOrNull(i + 1)?.takeIf { it.start <= jetzt }?.let { ende = minOf(ende, it.start) }
            b.abbrueche.firstOrNull { it > t.start && it <= jetzt }?.let { ende = minOf(ende, it) }
            ende = minOf(ende, jetzt)
            val real = Math.round((minOf(q, ende) - t.start) / 60_000.0).toInt()
            if (real <= 0) return@forEachIndexed
            val n = kotlin.math.ceil(real / 5.0).toInt()
            val sp = real / n.toDouble()
            for (j in 0 until n) {
                val cd = (t.start + j * sp * 60_000 + 0.5 * sp * 60_000).toLong()
                val netto = if (t.absolut) t.rate - asOfBasal(b, cd) else (t.rate - 100) / 100.0 * asOfBasal(b, cd)
                if (cd > q - dia * 3_600_000 && cd <= q) {
                    val r = insulin.iobCalcForTreatment(BS(timestamp = cd, amount = netto * sp / 60.0, type = BS.Type.NORMAL, iCfg = insulin), q)
                    iobBasal += r.iobContrib
                    akt += r.activityContrib
                }
            }
        }
        return Triple(iobBolus, iobBasal, akt)
    }

    /** Vor der Abgabe liefert das Modell keine Null (t < td ohne t >= 0) - hier schon. */
    private fun einzelWirkung(a: Abgabe, atTs: Long): app.aaps.core.data.iob.Iob =
        if (atTs < a.ts) app.aaps.core.data.iob.Iob()
        else insulin.iobCalcForTreatment(BS(timestamp = a.ts, amount = a.u, type = BS.Type.SMB, iCfg = insulin), atTs)

    private fun abgabeWirkung(atTs: Long): Pair<Double, Double> =
        abgaben.fold(0.0 to 0.0) { (iob, akt), a ->
            einzelWirkung(a, atTs).let { (iob + it.iobContrib) to (akt + it.activityContrib) }
        }

    private fun abgabeGlukoseWirkung(ts: Long): Double =
        abgaben.filter { it.wirktAufReihe && it.ts <= ts }.sumOf { (it.u - einzelWirkung(it, ts).iobContrib) * it.isfMgdlPerU }

    private fun roundUp(t: Long) = if (t % 60_000L == 0L) t else (t / 60_000L + 1) * 60_000L

    @BeforeEach
    fun setup() = runBlocking<Unit> {
        rigProfil = ProfileSealed.EPS(effectiveProfileSwitch.copy(iCfg = insulin), activePlugin)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)
        testPumpPlugin.pumpDescription.bolusStep = 0.05

        whenever(dateUtil.now()).thenAnswer { clock }
        whenever(profileFunction.getProfile()).thenReturn(rigProfil)
        whenever(profileFunction.getProfile(any())).thenReturn(rigProfil)
        whenever(profileFunction.getProfileName()).thenReturn(TESTPROFILENAME)

        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.roundUpTime(any())).thenAnswer { inv -> roundUp(inv.getArgument(0)) }
        whenever(ads.getBgReadingsDataTableCopy()).thenAnswer { series(clock) }
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any()))
            .thenAnswer { inv -> if (aapsNah) aapsNahIob(inv.getArgument(0)) else iob(inv.getArgument(0)) }
        whenever(iobCobCalculator.calculateIobFromBolus()).thenAnswer {
            // Zeitpunkttreu: nur der Bolusanteil zum Rechenzeitpunkt (AAPS rechnet hier ohne Aufrunden).
            if (asOf != null) IobTotal(clock).also { t ->
                val (bolus, _, akt) = asOfIob(clock, clock, nurBolus = true, runden = false)
                t.iob = bolus
                t.activity = akt
                t.valid = iobGueltig
            } else if (aapsNah) aapsNahIob(clock, zwischenspeichern = false) else iob(clock)
        }
        // Basalanteil fuer die Nachrechnung des Aufnahmenachweises. Das Rig
        // fuehrt kein Basal-IOB (auch aapsNah setzt basaliob = 0).
        whenever(iobCobCalculator.calculateIobToTimeFromTempBasalsIncludingConvertedExtended(any()))
            .thenAnswer { inv -> aapsNahBasal(inv.getArgument(0)) }
        whenever(persistenceLayer.getExtendedBolusesStartingFromTimeToTime(any(), any(), any())).thenAnswer { inv ->
            val von = inv.getArgument<Long>(0)
            val bis = inv.getArgument<Long>(1)
            verlaengerteBoli.filter { it.timestamp in von..bis }
        }

        whenever(constraintsChecker.getMaxIOBAllowed()).thenAnswer { ConstraintObject(maxIobU, aapsLogger) }
        whenever(commandQueue.bolusInQueue()).thenReturn(false)
        whenever(commandQueue.isRunning(any())).thenReturn(false)

        whenever(persistenceLayer.getLastTherapyRecordUpToNow(any())).thenReturn(null)
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenReturn(null)
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenAnswer { inv ->
            val from = inv.getArgument<Long>(0)
            val to = inv.getArgument<Long>(1)
            boluses.filter { it.timestamp in from..to && (!aapsNah || it.isValid) }
        }
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenReturn(null)

        stubPolicy()
        neuerRunner(FuseLedgerAdapter())
    }

    private fun neuerRunner(l: FuseLedgerAdapter, evidenz: EvidenceStock.Config = EvidenceStock.Config(), fensterMs: Long? = null, trendRegel: String? = null, wiedereinstieg: app.aaps.fuse.core.signal.RejoinPolicy = app.aaps.fuse.core.signal.RejoinPolicy.OFF, gapPolitik: app.aaps.fuse.core.signal.GapPolicy = app.aaps.fuse.core.signal.GapPolicy.PRODUCTION, reifePolitik: app.aaps.fuse.core.signal.MaturityPolicy = app.aaps.fuse.core.signal.MaturityPolicy.PRODUCTION) {
        ledger = l
        runner = FuseCycleRunner(
            iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
            preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "test-epoch", { markerPress },
            einheitsSampler = { iCfg, ts -> if (kaputterKern) NaNSampler else AapsUnitInsulinSampler(iCfg, ts) },
            evidenceConfig = evidenz,
            theilSenWindowMsOverride = fensterMs,
            trendRuleOverride = trendRegel,
            gapPolicy = gapPolitik,
            maturityPolicy = reifePolitik,
            rejoinPolicy = wiedereinstieg,
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
        // KONSOLIDIERUNG: das Konto gilt mit gueltiger Kurve, also liest readConfig die Kurve in jedem Zyklus. Ohne
        // Stub liefert der Mock null statt des Standards "" - dieses Rig faehrt ohne Kurve, also ohne Konto.
        whenever(preferences.get(FuseStringKey.ExpectationCurve)).thenReturn("")
        whenever(preferences.get(FuseDoubleKey.SmbRatio)).thenReturn(0.15)
        whenever(preferences.get(FuseDoubleKey.SmbRatioRise)).thenReturn(0.35)
        whenever(preferences.get(DoubleKey.ApsSmbMaxIob)).thenAnswer { maxIobU }
        whenever(preferences.get(FuseDoubleKey.RiseRampLowR)).thenAnswer { riseRampLowRWert }
        whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenAnswer { riseRampHighRWert }
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
        whenever(preferences.get(FuseIntKey.LivenessMealPowerMin)).thenAnswer { mealPowerMin }
        whenever(preferences.get(FuseDoubleKey.CorrectionExposureLimitU)).thenAnswer { corrExpLimit }
        whenever(preferences.get(FuseDoubleKey.MealExposureLimitU)).thenAnswer { mealExpLimit }
        whenever(preferences.get(FuseDoubleKey.CorrectionDemandRatioCap)).thenAnswer { corrRatioCapZ }
        whenever(preferences.get(FuseDoubleKey.MealDemandRatioCap)).thenAnswer { mealRatioCapZ }
        whenever(preferences.get(FuseBooleanKey.ZeroLatchEnabled)).thenAnswer { zeroLatchAn }
        whenever(preferences.get(FuseBooleanKey.PartialRecoveryEnabled)).thenAnswer { teilbasalAn }
        whenever(preferences.get(FuseIntKey.ZeroLatchCalmExitMin)).thenAnswer { zeroLatchRuheZyklen }
        // Nullphasen-Varianten: Default AUS bzw. gueltiges Fenster -
        // ungemockt liefert Mockito 0, und die Config-Validierung
        // weist ein 0-Fenster zu Recht ab.
        whenever(preferences.get(FuseDoubleKey.ZeroLatchCalmDistanceMgdl)).thenAnswer { zeroLatchRuheAbstand }
        whenever(preferences.get(FuseIntKey.ZeroLatchReasonGoneExitCycles)).thenAnswer { zeroLatchGrundWegZyklen }
        whenever(preferences.get(FuseDoubleKey.LivenessBgMinDayMgdl)).thenAnswer { livenessBgMin }
        whenever(preferences.get(FuseDoubleKey.LivenessBgMinNightMgdl)).thenAnswer { livenessBgMinNacht ?: livenessBgMin }
        whenever(preferences.getIfExists(FuseDoubleKey.LivenessBgMinNightMgdl)).thenAnswer { livenessBgMinNacht }
        whenever(preferences.get(FuseIntKey.LivenessReArmMin)).thenAnswer { livenessReArmMin }
        whenever(preferences.get(FuseIntKey.AbsorptionCreditWindowMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.MarkerBoostMaxMin)).thenReturn(45)
        whenever(preferences.get(FuseIntKey.NightStartMin)).thenAnswer { nachtStartMin }
        whenever(preferences.get(FuseIntKey.NightEndMin)).thenAnswer { nachtEndeMin }
        whenever(preferences.get(FuseDoubleKey.NightDeadbandMgdl)).thenReturn(45.0)
        whenever(preferences.get(FuseDoubleKey.ReboundDeadbandMgdl)).thenReturn(25.0)
        whenever(preferences.get(FuseIntKey.DriveLowerQuantilePct)).thenAnswer { quantilePct }
        whenever(preferences.get(FuseIntKey.TheilSenWindowMin)).thenAnswer { theilSenFensterMin }
        whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenAnswer { reboundFensterMin }
        whenever(preferences.get(FuseIntKey.EvidenceReboundOverrideMaxMin)).thenAnswer { reboundOverrideMaxMin }
        whenever(preferences.get(FuseBooleanKey.TailGuardEnabled)).thenAnswer { tailGuard }
        whenever(preferences.get(FuseBooleanKey.MarkerAuthorisesRelease)).thenAnswer { markerAuthorized }
        whenever(preferences.get(FuseBooleanKey.SignalRejoinEnabled)).thenAnswer { rejoinAn }
        whenever(preferences.get(FuseDoubleKey.TailFloorMgdl)).thenReturn(70.0)
        whenever(preferences.get(FuseDoubleKey.TailRecoveryU)).thenReturn(0.0)
        whenever(preferences.get(FuseDoubleKey.BolusShareLambda)).thenReturn(1.0)
        whenever(preferences.get(FuseBooleanKey.OnsetChannelEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.OnsetEnvelopeU)).thenReturn(1.5)
        whenever(preferences.get(FuseDoubleKey.PrimeEnvelopeU)).thenAnswer { primeHuelleU }
        whenever(preferences.get(FuseBooleanKey.CorrectionReversalGuardEnabled)).thenAnswer { reversalAn }
        whenever(preferences.get(FuseDoubleKey.ReversalFallUkf)).thenReturn(2.0)
        whenever(preferences.get(FuseIntKey.ReversalLookbackMin)).thenReturn(20)
        whenever(preferences.get(FuseDoubleKey.ReversalReboundUkf)).thenReturn(1.0)
        whenever(preferences.get(FuseIntKey.ReversalConfirmCycles)).thenAnswer { reversalConfirmWert }
        whenever(preferences.get(FuseBooleanKey.PositiveCorrectionRearmEnabled)).thenAnswer { rearmAn }
        whenever(preferences.get(FuseIntKey.RearmHoldMin)).thenReturn(5)
        whenever(preferences.get(FuseIntKey.RearmConfirmCycles)).thenReturn(2)
        whenever(preferences.get(FuseDoubleKey.RearmUpUkf)).thenAnswer { rearmUpUkfWert }
        whenever(preferences.get(LongNonKey.FslCalibrationStart)).thenAnswer { kalibrierStart }
        whenever(preferences.get(FuseLongKey.MealMarkerStamp)).thenReturn(0L)
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenAnswer { markerAt }
    }

    /** Synthetische Kurve des Kontos im Rig: F(5k) = 0,125 * k. */
    private val rigKurve = (1..24).joinToString("; ") { (it * 0.125).toString() }

    /**
     * KONSOLIDIERUNG 2.0: DAS KONTO IM RIG. Seit Tonis Regel dosiert ein Marker ohne gueltiges Konto wie ohne Marker
     * (Rueckfall). Proben, die die Mahlzeitendosierung selbst pruefen, schalten deshalb das Konto ein: eine gueltige
     * synthetische Kurve, die Kontowerte wie ausgeliefert, die fruehe Versorgung aus (Budget 0, neutrale Pins) - es
     * fliesst nur der gemessene Anteil, der Normalweg traegt im Kontofenster.
     */
    private fun kontoAn() {
        whenever(preferences.get(FuseStringKey.ExpectationCurve)).thenReturn(rigKurve)
        for (k in listOf(
            FuseDoubleKey.ExpectationStabilityBgMgdl, FuseDoubleKey.ExpectationStabilityFallMgdlPerMin,
            FuseDoubleKey.ExpectationReleaseThresholdU,
        )) whenever(preferences.get(k)).thenReturn(k.defaultValue)
        for (k in listOf(FuseIntKey.ExpectationStabilityMin, FuseIntKey.ExpectationHorizonMin))
            whenever(preferences.get(k)).thenReturn(k.defaultValue)
    }

    /**
     * Das Konto MIT frueher Versorgung ("Nur Erwartung" am Marker): synthetisches Budget, Anfangsportion und Portionen,
     * G1/G2 (die Anfangsportion hebt Guard und Schwanz). Die Quelle eines Mahlzeitengrants seit der Konsolidierung.
     */
    private fun kontoMitFruehVersorgung(budgetU: Double = 3.0) {
        kontoAn()
        whenever(preferences.get(FuseBooleanKey.EarlySupplyEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyScaleStable)).thenReturn(1.0)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyScaleAfterLow)).thenReturn(1.0)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyBudgetMaxU)).thenReturn(budgetU)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyInitialShare)).thenReturn(0.5)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyMaxPortionU)).thenReturn(1.0)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyMaxPerPeriodU)).thenReturn(2.0)
        whenever(preferences.get(FuseIntKey.EarlySupplyPeriodMin)).thenReturn(FuseIntKey.EarlySupplyPeriodMin.defaultValue)
        whenever(preferences.get(FuseBooleanKey.EarlySupplyInitialLiftsGuard)).thenReturn(true)
        whenever(preferences.get(FuseBooleanKey.EarlySupplyInitialLiftsTail)).thenReturn(true)
    }

    private fun cycle(): FuseCycleRunner.Outcome {
        clock += taktMs
        val o = runner.run(false, testPumpe())
        // Nur den ISF merken - die Abgabe selbst fuehrt erst `transport` nach
        // dem Gate zurueck (nicht publizierte Mengen wirken nicht).
        if (rueckfuehrung) o.isfMgdlPerU?.takeIf { it.isFinite() && it > 0.0 }?.let { rueckIsf = it }
        return o
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
     *  kippt. Das Rig fuhr bisher 0,5; die Pflichtfall-Tests brauchen den
     *  Wert ihrer Lage (1,5), sonst liegt die
     *  Kontextgrenze eine Kurvenphase zu frueh. */
    private var riseRampLowRWert = 0.5
    /** Oberkante der Ratio-Rampe - Rig-Hebel, Default wie bisher 2,0. */
    private var riseRampHighRWert = 2.0

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

    private suspend fun quelleMeldet(tb: TB?) =
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())) doReturn tb

    /**
     * Eine TBR, die vor [vorMin] Minuten mit 30 min Dauer gestartet wurde.
     *
     * WANDUHR, nicht die injizierte Uhr: `TB.plannedRemainingMinutes`
     * rechnet gegen `System.currentTimeMillis()`. Ohne diesen Bezug meldet
     * jede gemockte TBR Restlaufzeit 0, und ein Test misst dann nur, dass
     * eine abgelaufene TBR nicht passt.
     */
    private fun laufendSeit(rate: Double, vorMin: Int) = TB(
        timestamp = System.currentTimeMillis() - vorMin * 60_000L,
        duration = 30 * 60_000L, rate = rate, isAbsolute = true, type = TB.Type.NORMAL,
    )

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
    fun `der Runner entscheidet aus dem gelesenen TBR-Zustand`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `FAKE_EXTENDED sperrt lesend und bleibt nicht gespeichert`(@TempDir dir: File) = runBlocking<Unit> {
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

        kaputterKern = true

        clock = start
        repeat(14) { i ->
            assertEquals(
                0.0, cycle().decision.smbU, 1e-9,
                "ein kaputtes Insulinmodell darf der Marker nicht ueberstimmen (Zyklus $i)",
            )
        }
    }

    // ---- DER PFLICHTFALL, im RUNNER ----------------------------------------

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
    fun `bei normalem BG nullen Pumpe Fault und FAKE_EXTENDED weiterhin`() = runBlocking<Unit> {
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

    /**
     * DIE BUCHUNG TRAEGT DIE KENNUNG IHRER AUTORISIERUNG - VERDRAHTET.
     *
     * `MarkerNeuautorisierungPersistenzTest` beweist, was eine Buchung OHNE
     * passende Kennung nicht mehr entlasten darf. Er baut seine Buchungen
     * aber selbst; ob der Runner die Kennung ueberhaupt mitgibt, stand dort
     * nicht drin - eine Mutation, die `authId = null` setzt, blieb gruen.
     *
     * Hier laeuft der echte Runner: nach einem Zyklus mit Abgabe muss die
     * Reservierung die Kennung der laufenden Autorisierung tragen. Ohne sie
     * gilt jede spaete Rueckbuchung als "gehoert zur laufenden" und
     * entlastet die neue Huelle.
     */
    @Test
    fun `die Reservierung traegt die Kennung der laufenden Autorisierung`(@TempDir dir: File) {
        flach = 140.0
        steigungProMin = 0.0

        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        l.episodes.markerAuth = app.aaps.fuse.core.controller.MarkerReauthorization.Authorization(
            id = "auth-7", markerTs = start + 2 * 60_000L,
        )

        markerAuthorized = true
        markerAt = start + 2 * 60_000L
        clock = start

        var gesehen: app.aaps.fuse.plugin.ledger.EpisodeBudgets.Reservation? = null
        repeat(12) {
            cycle()
            if (gesehen == null) gesehen = l.episodes.pendingReservation
        }
        assertNotNull(gesehen) {
            "Voraussetzung: es muss ueberhaupt eine Reservierung entstehen"
        }
        assertEquals("auth-7", gesehen!!.authId) {
            "ohne Kennung entlastet eine spaete Rueckbuchung die neue Huelle"
        }
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
    fun `eine nicht ueberstimmbare Ablehnung nennt den verweigerten Fallback`() = runBlocking<Unit> {
        flach = 62.0
        steigungProMin = 0.0
        val kaputt = org.mockito.kotlin.spy(rigProfil)
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
     * DAS VEHIKEL ist eine gewoehnliche Korrektur bei hohem, steigendem Zucker: ihre Menge kommt aus dem gemessenen
     * Bedarf, nicht aus einem Kredit - im Hold waere sie OHNE das Gate positiv, also prueft der Test das Gate scharf.
     * (Bis zur Konsolidierung war es die markerfinanzierte Direktdosis; die ist entfallen.)
     *
     * GEPAART: derselbe Aufbau zweimal. Der erste Lauf findet den Zyklus, in dem der Pfad wirklich etwas traegt
     * (Positivkontrolle); der zweite faehrt bis genau davor und laesst den Ledger in diesem Zyklus halten. Direkt nach
     * einer Abgabe zu pruefen waere nicht scharf - dann kappt schon die offene Haftung der eben publizierten Menge.
     */
    @Test
    fun `ein haltender Ledger nullt die Menge im Hauptpfad`(@TempDir dir: File) {
        tailGuard = false
        flach = 180.0
        steigungProMin = 2.5
        fun aufbau(d: File): FuseLedgerAdapter {
            val l = FuseLedgerAdapter().also { it.loadOnce(d.also(File::mkdirs), "test-epoch", start) }
            neuerRunner(l)
            clock = start
            return l
        }

        // (1) POSITIVKONTROLLE: ohne Hold traegt der Pfad im Zyklus k wirklich etwas.
        //     Ohne diesen Schritt pruefte der Test eine Null, die schon vorher
        //     eine war.
        aufbau(File(dir, "frei"))
        var k = -1
        var ohneHold: FuseCycleRunner.Outcome? = null
        for (i in 0 until 16) {
            val o = cycle()
            if (o.decision.smbU > 0.0) { k = i; ohneHold = o; break }
        }
        val frei = ohneHold ?: throw AssertionError("Aufbau traegt nichts - der Test wuerde nichts pruefen")
        assertFalse(frei.predictorRejected, "dieser Test gehoert dem HAUPTpfad")

        // (2) Derselbe Vorlauf, dann ECHTER Hold - kein Mock: der Sentinel-Name
        //     wird von einem Verzeichnis besetzt, der Persist scheitert nachweislich.
        val d = File(dir, "hold")
        val l = aufbau(d)
        repeat(k) { assertEquals(0.0, cycle().decision.smbU, 1e-9, "der Vorlauf muss dem ersten Lauf gleichen") }
        File(d, app.aaps.fuse.plugin.ledger.FuseLedgerStore.SENTINEL_NAME).delete()
        assertTrue(File(d, app.aaps.fuse.plugin.ledger.FuseLedgerStore.SENTINEL_NAME).mkdirs())
        assertFalse(l.persistVerified(d))
        assertTrue(l.view().hold, "Vorbedingung: der Ledger muss halten")

        // (3) DIE ZUSICHERUNG. Der Bedarf besteht weiter - und trotzdem fliesst
        //     nichts, mit dem Hold als typisiertem Grund. Seit der Konsolidierung
        //     haelt schon die Kandidatensuche (Reject LEDGER_HOLD); keine
        //     autorisierte Anhebung hebt diesen Grund, das LedgerHoldGate danach
        //     bleibt die zweite Schicht (Einheit: LedgerHoldGateTest).
        val imHold = cycle()
        assertEquals(0.0, imHold.decision.smbU, 1e-9, "im Hold darf nichts fliessen: ${imHold.decision}")
        val grund = imHold.decision.candidateReason
        assertTrue(
            imHold.decision.block == FuseController.Block.LEDGER_HOLD ||
                grund == app.aaps.fuse.core.controller.CandidateSearch.Reject.LEDGER_HOLD,
        ) { "und der Grund muss der Hold sein: ${imHold.decision.block}/$grund" }
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
        kaputterKern = true

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
     * KI-174: der praediktorfreie Markerpfad kehrt zurueck, BEVOR der Hauptpfad
     * die Sofortbatch-Kette setzt. Das Feld wurde nie geleert - ein
     * Fallbackzyklus exportierte deshalb die Kette des letzten
     * Hauptpfadzyklus, etwa dessen Grant. Keine Dosiswirkung, aber jede
     * Auswertung, die Kettenfelder liest, las dort einen fremden Zyklus.
     *
     * Die Vorbedingung ist Teil des Tests: ohne eine Kette aus dem Hauptpfad
     * gaebe es nichts, was der Fallback faelschlich weitertragen koennte.
     */
    @Test
    fun `KI-174 ein Fallbackzyklus traegt keine Sofortbatch-Kette eines frueheren Zyklus`() {
        tailGuard = false
        flach = 105.0
        steigungProMin = -0.9
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        val haupt = (0 until 6).map { cycle() }
        assertTrue(haupt.any { !it.markerFallbackUsed && it.upfrontChain != null }) {
            "Vorbedingung: der Hauptpfad muss eine Kette exportieren - sonst prueft der Test nichts"
        }

        predictReject = PredictorReason.PENDING_MODEL_TOO_SHORT
        val fallback = (0 until 6).map { cycle() }.filter { it.markerFallbackUsed }
        assertTrue(fallback.isNotEmpty(), "der Lauf muss den Fallbackpfad treffen")
        fallback.forEachIndexed { i, o ->
            assertNull(o.upfrontChain, "Fallbackzyklus $i traegt die Kette eines frueheren Zyklus")
        }
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
    fun `belegte Pumpe Fault und FAKE_EXTENDED nullen auch den autorisierten Anteil`() = runBlocking<Unit> {
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
        // Konsolidierung: ohne Direktdosis liefert der fallende Aufbau nichts - der Zaehler wird vorgeladen. Geprueft
        // wird, dass Ruecknahme und neuer Druck ihn nicht zuruecksetzen.
        l.episodes.evidenceCommittedU = 0.20
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
     * weiter - ein gemessener Lauf war nach 205 Minuten noch aktiv.
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
     * DER PFLICHTFALL - die Marker-Verlaengerung des Episodendeckels.
     *
     * Ein Fruehstuecks-Marker eroeffnete die Episode; der Marker
     * fuer eine ECHTE zweite Mahlzeit lag 305 Minuten spaeter, also INNERHALB
     * des 360-Minuten-Deckels, und erbte den alten Topf samt Uhr. Am
     * Deckelende lief er ab - mitten in der zweiten Mahlzeit, T+55 min, kurz vor der
     * Staerkewelle der Mahlzeit.
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
     * war und Kredit auswies (viele Live-Zyklen im Trail).
     *
     * Aufbau: Nachtfenster deckt die Rig-Uhr ab, Totband 45 mg/dl, BG unter
     * Ziel+45. OHNE Kredit muss das Totband sperren (Gegenprobe), MIT
     * aktivem Evidenzkredit war es entwaffnet - die Totbaender
     * schuetzen vor unangekuendigten Abweichungen, und eine markereroeffnete
     * Episode mit versiegelter unbezahlter Stoerung ist das Gegenteil davon.
     *
     * DIE GRENZE (Toni): das Nacht-Recht des Kredits gilt nur im
     * Mahlzeitfenster, dem Fenster des Kontos zum Marker. Dieses Rig faehrt
     * ohne Kurve, also ohne Konto und ohne Mahlzeitfenster: Kredit fliesst,
     * das Nacht-Totband bleibt trotzdem scharf - die Lage des Anlasses
     * (Kredit Stunden nach dem Marker, kein laufendes Fenster). Den Fall MIT
     * Fenster prueft das Konto-Rig (ErwartungskontoRigTest).
     *
     * BOOST-FENSTER AUS (Abschluss-Audit 15.08.): die erste Fassung liess
     * den Rig-Boost 45 Minuten laufen, und ALLE geprueften Zyklen lagen
     * darin - `markerBoost` entwaffnete die Totbaender kreditunabhaengig,
     * und der Test war gruen, obwohl der Kreditpfad im Runner gar nicht
     * verdrahtet war. Mit Boostfenster 0 prueft er den KREDIT, nicht die
     * Sonderrechte.
     */
    @Test
    fun `ohne Mahlzeitfenster entwaffnet Kredit das Nacht-Totband nicht`(@TempDir dir: File) {
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
        assertFalse(kredit.evidenceMayOverrideNight) { "ohne Mahlzeitfenster gibt es kein Nacht-Recht" }
        assertEquals("nightDeadband", kredit.decision.bindingLimit) { "das Nacht-Totband muss trotz Kredit sperren" }

        // KERN: Kappe oeffnen - auch dann dosiert kein Kreditzyklus unter der
        // Schwelle, das Nacht-Totband bindet.
        maxSmbU = 0.3
        val danach = (1..5).map { cycle() }
            .filter { (it.evidenceCreditMgdlPerMin ?: 0.0) > 0.0 && it.signal!!.q1 < 98.0 + 45.0 }
        assertTrue(danach.isNotEmpty()) { "Kredit muss weiterfliessen" }
        assertTrue(danach.all { it.decision.bindingLimit == "nightDeadband" && it.decision.smbU == 0.0 }) {
            "das Nacht-Totband muss trotz Kredit sperren: " +
                danach.map { "${it.decision.block}/${it.decision.bindingLimit}/${it.decision.smbU}" }
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
     * OPTION A AM TRAIL-FALL: ein zweiter Druck waehrend laufender
     * Episode, Vorgaenger laeuft an den Deckel - frueher eroeffnete
     * der geerbte Druck am Deckelende still eine neue Episode mit frischem
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

        // Zweiter Druck WAEHREND der laufenden Episode (der Trail-Fall).
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

    // ---- Die Armierung der Kredit-Huelle (Toni) -----------------------------
    //
    // Konsolidierung 2.0: das Mahlzeitenfundament ist entfallen. Am selben Armierungspunkt (neue Prime-Episode oder
    // Neuautorisierung, nur am in diesem Prozess beobachteten Druck) wird die Kredit-Huelle gepinnt
    // ([EpisodeBudgets.creditEnvelopeU]); die Tests unten pruefen diesen Punkt.

    /**
     * DIE ARMIERUNG HAENGT AN DER PRIME-EPISODE, NICHT AN DER EVIDENZEPISODE.
     *
     * Die Evidenzepisode laeuft bis zu 360 Minuten; die Huelle gehoert zum Prime-/Markerbudget, das mit
     * `startsNewEpisode` neu bewaffnet wird. Ein Abbruch nach dem Druck darf die Armierung nicht dauerhaft verlieren:
     * `primeArmedTs` bleibt im Abbruchzyklus unveraendert, also holt der naechste gesunde Zyklus sie nach.
     */
    @Test
    fun `ein Abbruch verliert die Armierung nicht - der naechste Zyklus holt sie nach`() = runBlocking<Unit> {
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        // Erster Zyklus nach dem Druck: kein Profil -> Abbruch.
        whenever(profileFunction.getProfile(any())).thenReturn(null)
        val abgebrochen = cycle()
        assertTrue(abgebrochen.abortReason != null, "der Aufbau MUSS abbrechen")
        assertNull(ledger.episodes.creditEnvelopeU, "im Abbruchzyklus wird keine Huelle gepinnt")

        // Naechster gesunder Zyklus. Mehrere, weil der Observer erst READY werden muss.
        whenever(profileFunction.getProfile(any())).thenReturn(rigProfil)
        repeat(8) { cycle() }
        assertEquals(
            primeHuelleU, ledger.episodes.creditEnvelopeU ?: Double.NaN, 1e-12,
            "die Armierung MUSS nachgeholt werden - sonst ist sie dauerhaft weg",
        )
        assertEquals(markerAtIntern, ledger.episodes.primeArmedTs, "und zwar fuer genau diesen Markerdruck")
    }

    /** UND GENAU EINMAL - nicht bei jedem Folgezyklus neu: eine geaenderte Einstellung trifft die laufende Mahlzeit nicht. */
    @Test
    fun `die Armierung geschieht genau einmal`() {
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(6) { cycle() }
        val ersteArmierung = ledger.episodes.primeArmedTs
        val gepinnt = primeHuelleU
        assertEquals(gepinnt, ledger.episodes.creditEnvelopeU ?: Double.NaN, 1e-12, "die Huelle MUSS gepinnt sein")
        // Eine geaenderte Einstellung, die eine erneute Armierung pinnen wuerde.
        primeHuelleU = gepinnt + 1.0
        repeat(4) { cycle() }

        assertEquals(ersteArmierung, ledger.episodes.primeArmedTs, "dieselbe Autorisierung")
        assertEquals(
            gepinnt, ledger.episodes.creditEnvelopeU ?: Double.NaN, 1e-12,
            "eine zweite Armierung haette die geaenderte Einstellung gepinnt",
        )
    }

    /**
     * EIN ZWEITER DRUCK NACH ABLAUF DES PRIMEFENSTERS ARMIERT NEU - auch wenn
     * dieselbe Evidenzepisode weiterlaeuft, und mit der Huelle, die an SEINEM
     * Druck eingestellt ist.
     */
    @Test
    fun `ein zweiter Druck erzeugt eine neue gepinnte Autorisierung`() {
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(5) { cycle() }
        val ersteArmierung = ledger.episodes.primeArmedTs
        assertTrue(ersteArmierung > 0L, "die erste Autorisierung MUSS stehen")
        assertEquals(primeHuelleU, ledger.episodes.creditEnvelopeU ?: Double.NaN, 1e-12)

        // Weit hinter das Markerfenster - die Prime-Episode ist abgelaufen,
        // die Evidenzepisode (360 min) laeuft weiter.
        clock += (OnsetChannel.MARKER_WINDOW_MIN + 5) * 60_000L
        primeHuelleU += 0.5
        markerAt = clock + 60_000L
        repeat(4) { cycle() }

        assertTrue(ledger.episodes.primeArmedTs > ersteArmierung, "der zweite Druck MUSS neu armieren")
        assertEquals(
            primeHuelleU, ledger.episodes.creditEnvelopeU ?: Double.NaN, 1e-12,
            "mit der Huelle, die an seinem Druck gilt",
        )
    }

    // ---- Die Ruecknahme ---------------------------------------------------

    /**
     * EINE RUECKNAHME BEENDET DIE GEPINNTE HUELLE SOFORT. Konsolidierung 2.0: mit dem Fundament sind Bezahlstand,
     * Sicherheitsaufschub und sein Export entfallen.
     */
    @Test
    fun `eine Ruecknahme beendet die gepinnte Huelle`() {
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(5) { cycle() }
        assertTrue(ledger.episodes.creditEnvelopeU != null, "die Huelle MUSS stehen")

        // DIE RUECKNAHME: der Marker wird zurueckgenommen.
        markerAt = 0L
        cycle()

        assertNull(ledger.episodes.creditEnvelopeU, "nach der Ruecknahme darf keine Huelle mehr gepinnt sein")
    }

    /** Und ein erneuter bewusster Druck armiert danach neu. */
    @Test
    fun `nach einer Ruecknahme armiert ein neuer Druck wieder`() {
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(5) { cycle() }
        markerAt = 0L
        repeat(2) { cycle() }
        assertTrue(ledger.episodes.creditEnvelopeU == null, "zurueckgenommen")

        // Hinter das Markerfenster - sonst gilt die Prime-Episode als noch
        // laufend und `startsNewEpisode` waere falsch.
        clock += (OnsetChannel.MARKER_WINDOW_MIN + 5) * 60_000L
        markerAt = clock + 60_000L
        repeat(6) { cycle() }
        assertTrue(
            ledger.episodes.creditEnvelopeU != null,
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
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L
        // Der Druck stammt aus einem FRUEHEREN Prozess.
        markerPress = 0L

        clock = start
        repeat(8) { cycle() }
        assertTrue(
            ledger.episodes.creditEnvelopeU == null,
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
            ledger.episodes.creditEnvelopeU != null,
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
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true

        // Druck A: beobachtet.
        markerAt = start + 2 * 60_000L
        val druckA = markerAtIntern
        clock = start
        repeat(6) { cycle() }
        assertTrue(ledger.episodes.creditEnvelopeU != null, "A wurde beobachtet und armiert")

        // Neue Prime-Episode, Druck B - aber beobachtet ist weiterhin nur A.
        clock += (OnsetChannel.MARKER_WINDOW_MIN + 5) * 60_000L
        markerAt = clock + 60_000L
        markerPress = druckA
        repeat(8) { cycle() }
        assertTrue(
            ledger.episodes.creditEnvelopeU == null,
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
        assertTrue(ledger.episodes.creditEnvelopeU != null, "und dann armiert er")
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
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(6) { cycle() }
        assertTrue(ledger.episodes.creditEnvelopeU != null, "armiert")

        markerAt = 0L
        repeat(2) { cycle() }
        assertTrue(ledger.episodes.creditEnvelopeU == null, "die Ruecknahme beendet sie")

        // Erneuter Druck INNERHALB des 90-Minuten-Fensters.
        markerAt = clock + 60_000L
        repeat(6) { cycle() }
        assertTrue(
            ledger.episodes.creditEnvelopeU == null,
            "in derselben Prime-Episode entsteht KEIN neues Fundament - sonst " +
                "gaebe dieselbe Huelle ein zweites Mal frei",
        )
    }

    /**
     * "NUR MARKER" (Toni): kein Vorgriff - es wird keine Huelle gepinnt (der Erklaerungskredit rechnet dann mit 0).
     * Konsolidierung 2.0: an die Stelle der Probe "Fundament aus" tritt die Wahl am Marker.
     */
    @Test
    fun `mit Nur Marker wird keine Huelle gepinnt`() {
        flach = 105.0
        steigungProMin = -0.9
        markerAuthorized = true
        whenever(preferences.get(FuseLongKey.MealMarkerNoPrime)).thenReturn(1L)
        markerAt = start + 2 * 60_000L

        clock = start
        repeat(8) { cycle() }
        assertTrue(ledger.episodes.primeArmedTs > 0L, "Vorbedingung: die Prime-Episode ist armiert")
        assertNull(ledger.episodes.creditEnvelopeU, "Nur Marker - keine Huelle")
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

        /** Menge positiv, Apply-Block nie betreten - Pflichtfall BOLUS_IN_QUEUE. */
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

        /**
         * v4 (Produktchat): die Warteschlange war gehalten (Einstellungs-Import), der Loop hat den Apply-Block
         * nicht betreten. Die Beobachtung entsteht hier NICHT von Hand, sondern aus einem echten `Loop.LastRun` in der
         * Gestalt, die der v4-Loop dabei hinterlaesst (`LoopPluginTest`: "a held queue leaves the trace..."), durch die
         * echte Lesung des Plugins ([FusePlugin.PriorActuation.aus]).
         */
        GEHALTEN,
    }

    private var letzterAusgang = Ausgang.GESENDET

    /** Die RT, die der letzte Zyklus publiziert hat - fuer [Ausgang.GEHALTEN]. */
    private var letzteRt: RT? = null

    /**
     * Die Spur des v4-Loops nach einem Lauf mit gehaltener Warteschlange: `request` ist das APS-Ergebnis dieses Laufs,
     * `constraintsProcessed` seine Kopie (dieselbe RT-Instanz), `smbSetByPump` blieb leer - der Apply-Block wurde nie
     * betreten. Dieselbe Gestalt haelt `LoopPluginTest` am echten Loop fest.
     */
    private fun gehaltenerLauf(rt: RT): Loop.LastRun {
        val request = apsResultProvider().with(rt)
        return Loop.LastRun().apply {
            this.request = request
            this.constraintsProcessed = request.newAndClone()
            this.smbSetByPump = null
            this.tbrSetByPump = null
        }
    }
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
        letzteRt = null
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
        /** Persist-Ort, gewaehlt NACH dem gerechneten Zyklus - fuer "Persist scheitert genau im Mengenzyklus". */
        persistNach: (FuseCycleRunner.Outcome) -> File = { dir },
    ): FuseCycleRunner.Outcome {
        // (1) DER BELEG UEBER DEN VORIGEN ZYKLUS - vor dem Lauf gebildet,
        // solange die published*-Felder noch den Vorgaenger beschreiben.
        // v4: fuer GEHALTEN liefert die echte Lesung des Plugins die drei Beobachtungswerte.
        val gehalten = letzteRt?.takeIf { letzterAusgang == Ausgang.GEHALTEN }
            ?.let { rt -> FusePlugin.PriorActuation.aus(gehaltenerLauf(rt), rt, ofComputeTs = 0L) }
        val claim = pPropId
            ?.takeIf { ledger.hasOpenProposal(it) }
            ?.let { id ->
                NotSentProof.reasonFor(
                    NotSentProof.Observation(
                        correlated = gehalten?.correlated ?: (letzterAusgang != Ausgang.UNKLAR &&
                            letzterAusgang != Ausgang.UNKORRELIERT),
                        ledgerPublishedU = ledger.publishedAmountOf(id),
                        gateStripped = pStripped,
                        gateSealed = pSealed,
                        gatePersistFailed = pPersistFailed,
                        aapsConstrainedU = if (gehalten != null) gehalten.aapsConstrainedU else when (letzterAusgang) {
                            Ausgang.CONSTRAINT_NULL -> 0.0
                            Ausgang.UNKLAR          -> null
                            else                    -> letzteMengeU   // auch UNKORRELIERT
                        },
                        smbSetByPumpPresent = if (gehalten != null) gehalten.smbSetByPumpPresent else when (letzterAusgang) {
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
            rt = rt, adapter = ledger, dir = persistNach(o), expected = expected,
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

        // (4b) RUECKFUEHRUNG NUR DER BESTAETIGTEN ABGABE (s. `rueckfuehrung`).
        val bestaetigtU = publication.rt.units?.takeIf { publication.sealed && ausgang == Ausgang.GESENDET && it > 0.0 }
        if (rueckfuehrung && bestaetigtU != null) {
            abgaben += Abgabe(o.computeTs, bestaetigtU, wirktAufReihe = true, isfMgdlPerU = rueckIsf)
            boluses = boluses + BS(timestamp = o.computeTs, amount = bestaetigtU, type = BS.Type.SMB, iCfg = insulin)
        }

        // (5) DEN ZUSTAND FUER DEN NAECHSTEN ZYKLUS FORTSCHREIBEN.
        pPropId = cycleId.takeIf { ledger.hasOpenProposal(it) }
        pStripped = !publication.allowed && rt.units != null
        pSealed = publication.sealed
        pPersistFailed = !publication.sealed
        letzterAusgang = ausgang
        letzteMengeU = publication.rt.units
        letzteRt = publication.rt
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

    // Konsolidierung 2.0: der Sicherheitsuebertrag der Phase A und sein Rebound-Test sind mit dem
    // Mahlzeitenfundament entfallen.

    // ---- WIEDERHERSTELLUNG NACH UNTERBROCHENEM SPEICHERN, DURCH DEN RUNNER --
    //
    // Die Dateiprotokoll-Nachweise stehen in FuseLedgerSealRecoveryTest. Hier
    // die Folge im Betrieb: unterbrochenes Versiegeln -> Hold -> Wiederherstellung
    // -> frischer Adapter und Runner (neuer Prozess, markerPress = 0) -> echtes
    // Publikations-Gate. Nachgestellt als Dateizustand: v2-Marker mit der
    // Pruefsumme des unterbrochenen Stands S, vollstaendige .tmp = S, Zielname
    // = zuletzt versiegelter Stand P (gleiche Revision).

    private fun unterbrochenesVersiegeln(dir: File, a: FuseLedgerAdapter): String {
        val s = app.aaps.fuse.plugin.ledger.LedgerCodec.encode(
            a.state, a.episodes, a.revision, a.interventionStamp, a.retiredBoundIds.toList(), a.proposalPumpEpochs.toMap(),
        ).toString()
        val store = app.aaps.fuse.plugin.ledger.FuseLedgerStore()
        assertTrue(store.markSealPending(dir, app.aaps.fuse.plugin.ledger.FuseLedgerStore.sealMarkerContent("t-int", a.revision, s)))
        File(dir, "${app.aaps.fuse.plugin.ledger.FuseLedgerStore.FILE_NAME}.tmp").writeText(s)
        return s
    }

    private fun wiederherstellen(dir: File) {
        val lage = app.aaps.fuse.plugin.ledger.FuseLedgerSealRecovery.inspect(dir)
        assertTrue(lage.recoverable) { "Vorbedingung: nachweisbar - ${lage.why}" }
        val r = app.aaps.fuse.plugin.ledger.FuseLedgerSealRecovery.perform(dir, clock, "test", "Integrationstest")
        assertTrue(r is app.aaps.fuse.plugin.ledger.FuseLedgerSealRecovery.Result.Done) { "$r" }
    }

    /**
     * PFLICHTFALL: DIE WIEDERHERGESTELLTE HAFTUNG BINDET die Dosis - gemessen an
     * der Sub-Step-Kante (s. `die Sub-Step-Freigabe faellt weg ...`): ohne
     * Haftung ist "subStep" frei, mit 0,08 U wiederhergestellter Haftung nicht.
     */
    @Test
    fun `die wiederhergestellte offene Haftung bindet die Dosis wie nach ungestoertem Neustart`(@TempDir dir: File) {
        tailGuard = false
        maxIobU = 0.25

        fun lauf(unter: File, haftungU: Double, unterbrechen: Boolean): FuseCycleRunner.Outcome? {
            unter.mkdirs()
            val a = FuseLedgerAdapter().also { it.loadOnce(unter, "test-epoch", start) }
            if (haftungU > 0.0) a.onPublished("vorlauf", haftungU, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
            assertTrue(a.persistVerified(unter))
            clock = start
            if (unterbrechen) {
                a.episodes.primeSpentU += 0.01
                unterbrochenesVersiegeln(unter, a)
                assertTrue(FuseLedgerAdapter().also { it.loadOnce(unter, "test-epoch", start) }.recoveryHold)
                wiederherstellen(unter)
            }
            val b = FuseLedgerAdapter().also { it.loadOnce(unter, "test-epoch-2", start) }
            assertFalse(b.recoveryHold)
            assertEquals(haftungU, b.view().transportCommitmentU, 1e-9)
            neuerRunner(b)
            clock = start
            var best: FuseCycleRunner.Outcome? = null
            repeat(60) { val o = cycle(); if (o.decision.smbU > (best?.decision?.smbU ?: 0.0)) best = o }
            return best
        }

        val ohne = lauf(File(dir, "ohne"), 0.0, unterbrechen = true)!!
        assertThat(ohne.decision.bindingLimit).contains("subStep")
        val mit = lauf(File(dir, "mit"), 0.08, unterbrechen = true)!!
        assertThat(mit.decision.bindingLimit).doesNotContain("subStep")
        assertThat(mit.decision.smbU).isAtMost(maxIobU - 0.08 + 1e-9)
    }

    // Konsolidierung 2.0: die Tests des Phase-A-Sofortanteils und seines Ruhe-Ausgangs (Uebertrag nach Phase A,
    // Verfall des Uebertrags, Huellen-Klemmung des Uebertrags, Batch-Zustaende, Endpfad- und Zero-Latch-Proben,
    // bedarfsbegrenzter Ruhekandidat) sind mit dem Mechanismus entfallen. Der Verfall des Aufschubs an der Frist
    // steht weiter in `P6 Fall 4`.


    // Konsolidierung 2.0: die Transport-E2E-Faelle der Phase A (Rueckbuchung, Uebertrag, fremde Kennung, Prime
    // holt nach) sind mit dem Mahlzeitenfundament entfallen; Entlastung und Widerruf im Ledger pruefen
    // ConstraintRevokeTest und MarkerNeuautorisierungPersistenzTest.

    // Konsolidierung 2.0: Runner-Replay ueber die Aufteilung und der Funktionsnachweis des Mahlzeitenfundaments
    // sind mit ihm entfallen.

    @Test
    fun `Wende-Klassifikation erkennt die Plateau-Wende`() {
        // Erst klarer Anstieg, dann ein flacherer positiver Nachlauf. Das ist
        // die Form der Plateau-Wende: fastDrive dreht bereits ab, r bleibt noch
        // hoch. Kein Marker, kein Fundament und kein Tail - die Lage bleibt
        // dieselbe wie vor 2.0 (Stufe 1, K1: die Tau-Matrix entfaellt).
        flach = 140.0
        steigungProMin = 2.0
        knickAbMin = 18
        steigungNachKnick = 0.35
        tailGuard = false
        markerAuthorized = false
        fundamentAn = false
        clock = start

        var wende: FuseCycleRunner.Outcome? = null
        var normaleZyklen = 0
        for (i in 0 until 55) {
            val o = cycle()
            if (o.turnResponseShadow?.classification?.phase == TurnResponseShadow.Phase.TURNING_DOWN) {
                wende = o
                break
            }
            if (o.turnResponseShadow?.classification?.phase == TurnResponseShadow.Phase.ALIGNED) normaleZyklen++
        }
        assertTrue(normaleZyklen > 0, "der Aufbau muss auch den Normalpfad durchlaufen")
        val o = wende ?: throw AssertionError("der Aufbau hat keine positive Abwaertswende erzeugt")
        val sh = o.turnResponseShadow!!
        assertEquals(50, sh.classification.adaptiveRestraintTauMin)
        assertTrue(sh.classification.fastDriveMgdlPerMin!! > 0.0, "negative Drives duerfen R50 nie oeffnen")
    }

    /**
     * DIE STABILE SIGNALEPOCHE (Toni 22.08.) - die Segment-Identitaet des
     * Erwartungs-Ledgers. Mit der gleitenden 18-min-Fensterkante als
     * Identitaet konnten sich Entry (Kante bei Ausstellung) und Probe (Kante
     * 120 min spaeter) per Konstruktion NIE treffen: saemtliche Outcomes des
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

    // Konsolidierung 2.0: die Risikolaeufe des Mahlzeitenfundaments (Guard, Tail, Fenster 25) sind mit ihm
    // entfallen.

    // ==== DER FINALE RIEGEL AM GEMEINSAMEN AUSGANG (Toni 19.08., P0) ======
    //
    // Konsolidierung 2.0: die drei Proben des Riegels gegen markerfinanzierte Mengen (Prime, Fundament) sind mit
    // diesen entfallen - ohne Konto dosiert ein Marker wie ohne Marker, im Konto pausiert das Fallen die fruehe
    // Versorgung vor dem Riegel (s. ErwartungskontoRigTest, spaetes Abwaertsrisiko).
    //
    // ER SITZT NACH Prime-/Fundament-Lift, `finalVerify` und `MarkerFloor`,
    // aber VOR der Publikation. Ein frueher gesetzter Riegel koennte von einem
    // spaeteren Wiederherstellungspfad umgangen werden - genau so ist der
    // Abendfall entstanden: die Abwaertslage stand bereits fest, und ueber
    // die Marker-Autorisierung gingen danach noch mehrere Einheiten hinaus.
    //
    // Diese Tests fahren den ECHTEN Runner. Ein Test auf `LowThreatGate`
    // allein wuerde nur die Rechnung pruefen, nicht ihre WIRKSAMKEIT am
    // Ausgang - und der Befund war ja gerade, dass eine richtige Rechnung
    // folgenlos blieb.

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




    // ==== PUNKT 6: DER MARKER-PRIME-AUFSCHUB (Tonis 7 Replay-Pflichtfaelle) =
    //
    // Schalter default AUS, kein Aktivierungs-GO - diese Tests schalten ihn
    // im Geruest bewusst ein und fahren den ECHTEN Runner. Die Form ist der
    // Replay-Fall 1: maessiger Fall, Boden ZWISCHEN dem 30er-Korrekturriegel
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

    /**
     * Replay-Fall 4: keine Erholung bis zur Frist -> der Pin verfaellt typisiert.
     *
     * Konsolidierung 2.0: Rueckhalt in den offenen Betrag und Freigabe sind entfallen, und mit Prime und
     * Fundament auch die markerfinanzierte Menge, die gekappt wurde (der Erwartungsanteil faellt unter dem Pin mit
     * eigenem Grund weg). Pin, Frist und Verfall bleiben - an der Frist verfaellt der Pin, ohne Menge.
     */
    @Test
    fun `P6 Fall 4 - ohne Erholung verfaellt der Pin sichtbar an der gepinnten Frist`(@TempDir dir: File) {
        punkt6Lage(dir, fristMin = 45)
        // Nach dem ersten Fall nur noch ein Drift: nie drei Erholungszyklen.
        knickAbMin = 20
        steigungNachKnick = -0.05
        var lapseReason: String? = null
        var lapseU = -1.0
        var gepinnt = false
        repeat(55) {
            val o = cycle()
            if (o.deferredPrimeLapseReason == "EXPIRED" && lapseReason == null) {
                lapseReason = o.deferredPrimeLapseReason
                lapseU = o.deferredPrimeLapseU
            }
            if (o.deferredPrimePinnedForTs == markerAt) gepinnt = true
            assertEquals(0.0, o.deferredPrimeWithheldU, 1e-9, "ohne markerfinanzierte Menge wird nichts gekappt")
            assertEquals(0.0, o.deferredPrimeOpenU, 1e-9, "es entsteht kein offener Rest mehr")
        }
        assertTrue(gepinnt, "Vorbedingung: der Druck ist gepinnt")
        assertEquals("EXPIRED", lapseReason, "der Verfall MUSS typisiert im Trail stehen")
        assertEquals(0.0, lapseU, 1e-9, "verfallen ist kein Rest - es wird keiner mehr gefuehrt")
    }


    // ==== DER LIVENESS-KANAL (Bauvertrag Toni + Codex 22.08.) ==============
    //
    // Schalter default AUS, kein Aktivierungs-GO - die Tests schalten ihn im
    // Geruest bewusst ein. Die Lage ist der Liveness-Deadlock: anhaltender
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
     * Fall 1b - der GUARD-Deadlock (die bekannte Fehlerklasse: Unterkante
     * im Median deutlich zu tief zertifiziert): die aktivitaetsgetriebene
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

    // Konsolidierung 2.0: die Halte-Anhebung im Kanalbedarf und ihre Messbestaetigung sind entfallen (sie wirkten nur
    // im Mahlzeitkontext ohne Konto).

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
        // ANHALTENDE Liveness-Deadlock, nicht nur ein kurzes Fenster.
        bolusIobU = 4.5
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
        return adapter
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
     * P1-Aufbau (Livefall nach einer Mahlzeit): Low-Dip unter 75 oeffnet das
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
        // Livefall: Marker BEWUSST ohne Direktdosis - und nur so bleibt der
        // Evidenz-Topf im Rig ueberhaupt positiv (jede fruehe Abgabe zehrt
        // als Abzug am Bestand, bevor der Kredit je fliessen kann).
        whenever(preferences.get(FuseLongKey.MealMarkerNoPrime)).thenReturn(1L)
        flach = 80.0
        steigungProMin = -2.0   // Tal 68 bei min 6: Dip auf VERARBEITETEN
        knickAbMin = 6          // Zyklen (Warmup-Zyklen setzen lastLowTs nie)
        steigungNachKnick = 2.5 // der steile Mahlzeitenanstieg
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

    // ---- P0 v46: Episodenstatistik bis zur MEAL-Deadline ------------------

    /**
     * Lage fuer den Episodenstatistik-P0: Vollmacht 120 min, Kanal
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
     * P0 v46 (gemessener Livefall): mealDeliveries buchte nur im
     * 90-min-Onset-Fenster, der MEAL-Kontext laeuft aber 120 min - die
     * zwischen T+90 und der Deadline publizierte Menge fehlte in der
     * Episodensumme. Jetzt bucht die Statistik im halb offenen Fenster bis
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
     * Schritt B: der beobachtete Markerdruck friert die
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
     * Verhaltensaenderung, die der Burst-Livefall verlangt hat (mehrere Einheiten in
     * wenigen Zyklen, waehrend iobTH/maxIOB noch reichlich Luft liessen). Die Mutation
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
     *  Correction-Grenze bindet dann nicht. Konsolidierung 2.0: mit Konto -
     *  ohne Konto gilt der Rueckfall (Probe darunter). */
    @Test
    fun `B1 - unter Vollmacht gilt die MEAL-Grenze im Gate`(@TempDir dir: File) {
        b1Lage(dir, corrLimit = 1.5)
        kontoAn()
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

    /**
     * RUECKFALL OHNE KONTO (Toni): dieselbe Lage, dieselbe Vollmacht, aber kein Konto - dann gilt die Korrekturgrenze
     * wie ohne Marker. Die Vollmacht bleibt sichtbar, der Rueckfall steht mit Grund im Block.
     */
    @Test
    fun `B1 - im Rueckfall ohne Konto gilt unter Vollmacht die Korrekturgrenze`(@TempDir dir: File) {
        b1Lage(dir, corrLimit = 1.5)
        markerAuthorized = true
        repeat(7) { cycle() }
        markerAt = clock + 60_000L
        var geblockt = false
        repeat(30) {
            val o = cycle()
            if (o.dosingContextProfile == "MEAL") {
                assertEquals("NO_CURVE", o.erwartungskonto?.rueckfall, "der Rueckfall MUSS ausgewiesen sein")
                if (o.exposureGateBindet == true) assertEquals(
                    "correctionExposureLimit", o.exposureGateBinding, "im Rueckfall bindet die Korrekturgrenze",
                )
                if (o.decision.block == FuseController.Block.EXPOSURE_LIMIT) geblockt = true
            }
        }
        assertTrue(geblockt, "die knappe Korrekturgrenze MUSS im Rueckfall unter der Vollmacht blocken")
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
     * Fall 1 - die Deadlock-Tagesform: im Schwanz-Deadlock liefert der Kanal
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
     * Fall 4 - der P2-Exit und die Gegen-Tagesform: bei Minute 26 knickt
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
        boluses = listOf(BS(timestamp = clock, amount = 1.5, type = BS.Type.NORMAL, iCfg = insulin))
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
        boluses = listOf(BS(timestamp = clock, amount = 1.0, type = BS.Type.NORMAL, iCfg = insulin))
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
    fun `Liveness Gegenprobe - Abbruchzyklus beendet den Lauf statt ihn zu ueberbruecken`(@TempDir dir: File) = runBlocking<Unit> {
        livenessLage(dir)
        var aktiv = false
        repeat(22) { val o = cycle(); if (o.livenessActive) aktiv = true }
        assertTrue(aktiv, "der Lauf muss stehen")
        val kaputt = org.mockito.kotlin.spy(rigProfil)
        org.mockito.kotlin.doReturn(5000.0).whenever(kaputt).getIsfMgdlTimeFromMidnight(org.mockito.kotlin.any())
        whenever(profileFunction.getProfile()).thenReturn(kaputt)
        whenever(profileFunction.getProfile(any())).thenReturn(kaputt)
        val abbruch = cycle()
        assertTrue(abbruch.abortReason != null, "der Aufbau muss wirklich abbrechen: ${abbruch.abortReason}")
        assertEquals("OBSERVATION_LOST", abbruch.livenessExit, "der aktive Lauf endet im unbeobachteten Zyklus")
        assertTrue(abbruch.livenessReArmUntilTs > abbruch.computeTs, "mit Sperre")
        whenever(profileFunction.getProfile()).thenReturn(rigProfil)
        whenever(profileFunction.getProfile(any())).thenReturn(rigProfil)
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
     * mehr. Genau diese Kante entwaffnete den Kanal live fuer zehn Minuten,
     * obwohl die Abflachung eines weiterhin starken Anstiegs
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
     * Ziel bleibt Rig-Profil (nicht das Geraeteziel). GRENZE, ehrlich benannt:
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
    fun `Phase 2 - Fenster-Replay der aufgezeichneten Tage`(@TempDir dir: File) = runBlocking<Unit> {
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
            /** Rechenzeitpunkt des Geraets - "jetzt" fuer den zeitpunkttreuen Replay. */
            val computeTs: Long = 0L,
            /** Die vom Geraet in diesem Zyklus publizierte SMB-Menge (rt.units), null = keine. */
            val publishedU: Double? = null,
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
                    o.optLong("computeTs"),
                    o.optJSONObject("rt")?.optDouble("units")?.takeIf { it.isFinite() && it > 0.0 },
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
            // v55: aeltere Trails tragen den Schluessel nicht - dann bleibt der
            // Laufwert (Start 0 = AUS, bzw. der Matrix-Override).
            fruehHorizont = i("earlyAdaptiveMealHorizonMin", fruehHorizont)
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
            // aufgezeichnete Politik umgebogen (Rampen-Oberkante, Rebound-
            // Totband, Prime-Fenster).
            whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenReturn(d("riseRampHighR", 2.0))
            // HEBEL-LECK (Fruehstuecksrekonstruktion): die UNTERE Rampe
            // fehlte - der Replay lief mit dem Rig-Wert 0,5, das Geraet mit 1,5.
            // Sie bestimmt Liveness-Ratio und Onset-Schwelle.
            riseRampLowRWert = d("riseRampLowR", riseRampLowRWert)
            whenever(preferences.get(FuseDoubleKey.ReboundDeadbandMgdl)).thenReturn(d("reboundDeadbandMgdl", 25.0))
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
        // am Geraet steht er konstant AN (der Marker-Knopf ist das Werkzeug des Nutzers).
        markerAuthorized = true
        politikAnwenden(zyklen.firstNotNullOfOrNull { it.policy })

        // Rohserie + IOB/Aktivitaets-Karte aus dem Trail.
        rohSerie = zyklen.map { it.ts to it.raw }
        iobProTs = java.util.TreeMap(zyklen.associate { it.ts to (it.iobU to it.act) })
        bolusIobProTs = java.util.TreeMap(zyklen.mapNotNull { z -> z.bolusIobU?.let { z.ts to it } }.toMap())

        // ISF je Tagesminute aus dem Trail (das Profil ist zeitabhaengig).
        val isfProMin = HashMap<Int, Double>()
        // DIESELBE FUNKTION AUF BEIDEN SEITEN (Toni 23.08. Abend): der Runner
        // fragt mit MidnightUtils.secondsFromMidnight - also fuellt die Karte
        // mit EXAKT dieser Funktion statt einer parallelen Calendar-Rechnung.
        // Konsistenz per Konstruktion; der fruehere UTC-Modulo verschob unter
        // CEST um zwei Stunden (falscher Abend-ISF, zu aggressiv).
        zyklen.forEach { z ->
            isfProMin[app.aaps.core.utils.MidnightUtils.secondsFromMidnight(z.ts) / 60] = z.isf
        }
        val replayProfil = org.mockito.kotlin.spy(rigProfil)
        org.mockito.kotlin.doAnswer { inv ->
            val sec = inv.getArgument<Int>(0)
            isfProMin[(sec / 60) % 1440] ?: rigProfil.getIsfMgdlTimeFromMidnight(sec)
        }.whenever(replayProfil).getIsfMgdlTimeFromMidnight(org.mockito.kotlin.any())
        whenever(profileFunction.getProfile()).thenReturn(replayProfil)
        whenever(profileFunction.getProfile(any())).thenReturn(replayProfil)

        // ZEITPUNKTTREUE BEHANDLUNGEN (15.09.): ersetzen die Trail-IOB-Karte ganz -
        // sie kannte kuenftige Prognosepunkte nicht und darf spaeter aufgezeichnete
        // Werte nicht in eine fruehere Prognose tragen. Datei lokal, nie im Repo.
        System.getenv("FUSE_REPLAY_BEHANDLUNGEN")?.let { pfad ->
            asOf = asOfLaden(File(pfad))
            iobProTs = null
            bolusIobProTs = null
            // v4: die DIA steckt in der Insulinkurve des Profils - und an jedem Bolus derselben Kurve.
            insulin = rigICfg().also { it.setDia(asOf!!.diaStunden) }
            whenever(replayProfil.iCfg).thenReturn(insulin)
            println("REPLAY zeitpunkttreu: ${asOf!!.boli.size} Boli, ${asOf!!.tbr.size} TBR, ${asOf!!.abbrueche.size} Abbrueche, DIA ${asOf!!.diaStunden}")
            // DAS PROFILZIEL DES GERAETS (Aequivalenzbefund 15.09.): das Rig-Profil
            // rechnete mit 103,5 statt mit dem Geraeteziel - Bedarf und Kandidat lagen dadurch
            // systematisch neben der Aufzeichnung.
            val zielPlan = org.json.JSONObject(File(pfad).readText()).optJSONArray("zielPlan")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getInt("sek") to it.getDouble("ziel") } }.sortedBy { it.first }
            }
            if (zielPlan != null && zielPlan.isNotEmpty()) whenever(replayProfil.getTargetMgdl()).thenAnswer {
                val sek = app.aaps.core.utils.MidnightUtils.secondsFromMidnight(clock)
                zielPlan.lastOrNull { it.first <= sek }?.second ?: zielPlan.first().second
            }
        }
        // VOLLSTAENDIGE CGM-REIHE (15.09.): der Trail traegt nur einen Rohwert je
        // Zyklus; waehrend der Direktdosis liefen keine Zyklen, dem Replay fehlten
        // dort Messwerte. Die AAPS-Logs tragen jeden empfangenen Wert.
        System.getenv("FUSE_REPLAY_CGM")?.let { pfad ->
            val a = org.json.JSONArray(File(pfad).readText())
            rohSerie = (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getLong("ts") to it.getDouble("bg") } }.sortedBy { it.first }
            println("REPLAY CGM aus Logs: ${rohSerie!!.size} Werte")
        }

        // Der Spy bekommt die vom Runner errechneten LOKALEN Sekunden und
        // die Karte ist seit dem Zeitzonen-Fix oben ebenfalls lokal gefuellt
        // - eine Uhr fuer beide Seiten.

        fun lauf(name: String, fensterMs: Long?, trendRegel: String? = null, fenster: Int = 18, livenessStart: Boolean = true, upfrontStart: Double? = null, guardsStart: Boolean = false, reversalConfirm: Int = 2, gapBreakMs: Long? = null, reifeTag: String? = null, rejoin: Boolean = false, dosingKandidat: String? = null, fruehOverride: Int? = null, counterfactualIobFeedback: Boolean = false): File {
            transportReset()
            boluses = emptyList()
            // Gegenfaktuelle Rueckfuehrung (Codex-Harness 18.09.): jeder Lauf
            // startet ohne Overlay; die Zaehler gelten je Lauf.
            abgaben.clear()
            rueckfuehrung = counterfactualIobFeedback
            gegenfaktualBolusOverlay = counterfactualIobFeedback
            bolusIobFehltAnker = 0
            bolusIobFehltHistorisch = 0
            bolusIobAnkerFehltJetzt = false
            // H8 (v55): jeder Lauf startet AUS; Politik oder Override setzen ihn.
            fruehHorizont = 0
            markerAt = 0L
            // HEBEL-LECK GESCHLOSSEN (23.08. spaet): v16-Trails tragen den
            // livenessChannelEnabled-Schluessel nicht - der Hebel behielt
            // dann den Stand des VORHERIGEN Laufs (der cap100-Lauf
            // fuhr dadurch ohne Kanal, die Folgelaeufe mit). Jeder Lauf
            // startet jetzt explizit; Zeilen MIT Schluessel ueberschreiben
            // wie gehabt per politikAnwenden.
            livenessAn = livenessStart
            // HEBEL-LECK TEIL 2 (24.08., Ermittler-Befund einer konstanten U-
            // Differenz): die vier Profil-Cap-Felder setzt politikAnwenden
            // NUR, wenn die Trail-Zeile den Schluessel traegt. Wechselt der
            // Trail mittags das Schema (alt livenessIobCapPercent -> v24-
            // Split), erbt Lauf 2 fuer die FRUEHEN Zyklen die SPAETEN Werte
            // von Lauf 1: die Cap-Differenz x maxIob = konstant weniger
            // Liveness-Headroom, zahlreiche scheinbare SMB-Abweichungen im
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
            // an einem Replay-Fall: eine aggressive Variante dosierte dort im
            // Kaltstart mehrfach, rund die Haelfte der scheinbaren Mehrmenge).
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
            fruehOverride?.let { fruehHorizont = it } // H8-Matrix schlaegt die Aufzeichnung
            val adapter = FuseLedgerAdapter().also { it.loadOnce(File(dir, name).also(File::mkdirs), "test-epoch", zyklen.first().ts) }
            // Konsolidierung 2.0: der Ruhe-Ausgang ist entfallen, mit ihm seine Treiber-Uebersteuerung.
            neuerRunner(adapter, fensterMs = fensterMs, trendRegel = trendRegel, gapPolitik = gapPolitik, reifePolitik = reifePolitik, wiedereinstieg = rejoinPolitik)
            val outFile = File(outDir, "replay_$name.csv")
            val h8Zeilen = mutableListOf<String>()
            outFile.printWriter().use { w ->
                w.println("ts;smbU;block;binding;insulinReq;liftU;needU;abort;phase;fastD;slowD;trend;raw;recSmbU;recBlock;profil;restMin;tbr;latch;lvDenial;lvExit;lvStreak;lvHead;transC;revGrund;rearmGrund;ctxGrund;basis;gapBreakMs;samplesUsed;gapBeforeMin;r;bandN;matP;matS;iob;rejoin;rejoinGrund;gapMs;vollreifeTs;regimeGrund;regimeTs;regimeSegTs;vorReif;ruheModus;ruheStreak;ruheDenial;gefahr;guardAbst;grantU;vorFloor;nachFloor;nachRiegel;rtAngefordert;upfrontState;upfrontPendingU;riskAktiv;latchAktiv;latchGrund;iobAnkerFehlt;iobFehltAnkerKum;iobFehltHistKum;upfrontShare;q1;ukf;aktivitaet;bolusIobU;totalIobU;guardBoden;abstandBoden;minToFloor;ueberdeckung;fallrate;lowVerdikt;riskDenial;recoveryZyklen;horizontMin;aufschubGrund;dosingProfil;dosingGrund;expoSource;expoBind;expoBlock;expoBinding;expoHeadU;expoLimitU;bgMinQuelle;expoReqSource;smbState;smbStop;reqU;capU;releaseMean;candU;shNeedU;shCandU;shHeadU;noLift;rSigned;ledgerTransportU;anker;mean60;drive60;bgi60;transport60;driveMin1;driveMin60;holdUp;holdStreak;holdDenial;fastDrive;cfIobBeforeU;cfDeltaU")
                // DER VORGEFUNDENE MARKER IST KEIN BEOBACHTETER DRUCK
                // (Toni 25.08. spaet). `prevMarker = 0` liess den ersten
                // Zyklus jeden schon laufenden Marker als frisch gedrueckt
                // sehen: so wurde ein Stunden alter Marker zum Ausschnittsbeginn
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
                    // bei einem Tages-Replay nach ~20 min). Stubs bleiben erhalten,
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
                        fruehOverride?.let { fruehHorizont = it }
                    }
                    if (z.marker != prevMarker && z.marker > 0L) markerAt = z.marker
                    prevMarker = z.marker
                    // Zeitpunkttreu: "jetzt" ist der Rechenzeitpunkt des Geraets, und
                    // die Behandlungssicht enthaelt nur bis dahin bekannte Boli.
                    clock = asOf?.let { _ -> z.computeTs.takeIf { it >= z.ts } } ?: z.ts
                    asOf?.let { b -> boluses = b.boli.filter { it.ts <= clock }.map { BS(timestamp = it.ts, amount = it.u, type = BS.Type.SMB, iCfg = insulin) } }
                    bolusIobAnkerFehltJetzt = false
                    val o = runner.run(false, testPumpe())
                    // Gegenfaktuelle Buchhaltung bei FESTER historischer CGM-Reihe
                    // (Codex-Harness 18.09.): nur die Abweichung zur tatsaechlich
                    // publizierten SMB wird ab dem Folgezyklus in IOB/Aktivitaet
                    // zurueckgefuehrt. Historische Lieferung wird weder doppelt
                    // gezaehlt, noch beeinflusst die Dosis ihre eigene Entscheidung.
                    val cfIobBeforeU = if (counterfactualIobFeedback) abgabeWirkung(o.computeTs).first else 0.0
                    val historicalPublishedU = z.publishedU ?: 0.0
                    val cfDeltaU = if (counterfactualIobFeedback) o.decision.smbU - historicalPublishedU else 0.0
                    if (counterfactualIobFeedback && kotlin.math.abs(cfDeltaU) > 1e-12) {
                        abgaben += Abgabe(ts = o.computeTs, u = cfDeltaU, wirktAufReihe = false, isfMgdlPerU = z.isf)
                    }
                    h8Zeilen += listOf(
                        z.ts, o.earlyAdaptiveMeal.configuredHorizonMin, o.earlyAdaptiveMeal.horizonMin,
                        if (o.earlyAdaptiveMeal.eligible) "1" else "0", if (o.earlyAdaptiveMeal.selected) "1" else "0",
                        o.earlyAdaptiveMeal.denial ?: "",
                        o.earlyAdaptiveMeal.markerAgeMin?.let { "%.2f".format(java.util.Locale.US, it) } ?: "",
                        o.earlyAdaptiveMeal.w10DriveMgdlPerMin?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        o.earlyAdaptiveMeal.ukfRatePerMin?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        o.earlyAdaptiveMeal.earlyReleaseMeanMgdl?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.earlyAdaptiveMeal.productionReleaseMeanMgdl?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.earlyAdaptiveMeal.effectiveReleaseMeanMgdl?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.livenessNeedU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        "%.3f".format(java.util.Locale.US, o.livenessCandidateU),
                        o.earlyAdaptiveMeal.candidateAfterHeadroomU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.livenessHeadroomU?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        "%.3f".format(java.util.Locale.US, o.decision.smbU),
                        "%.3f".format(java.util.Locale.US, o.livenessLiftU),
                        o.livenessBinding ?: "", o.decision.bindingLimit ?: "",
                        "%.1f".format(java.util.Locale.US, z.raw), o.earlyAdaptiveMeal.source ?: "",
                    ).joinToString(";")
                    // KEIN REFERENZFALL-LEDGER (Befund 15.09.): nachtraeglich gebuchte
                    // Geraetepublikationen werden im Replay nie gegen die Pumpen-Boli
                    // aufgeloest und liessen den Transport auf mehrere Einheiten
                    // anwachsen. Der Transportterm fehlt dem Replay deshalb; die
                    // Auswertung weist die Geraete-Transportmenge je Zyklus als Grenze aus.
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
                        // REKONSTRUKTION (15.09.): Mittelbahn und Kandidat, wo der
                        // Kanal rechnet; Schattenwerte vor den Toren auch dort, wo
                        // er nicht rechnet. Dosierneutral.
                        // Spalte releaseMean = die Bahn, aus der der Kanal rechnet: mit
                        // H8 die wirksame max-Bahn (wie im Codex-Replay 18.09., dort trug
                        // livenessReleaseMeanMgdl sie), ohne H8 identisch zur produktiven.
                        (o.earlyAdaptiveMeal.effectiveReleaseMeanMgdl ?: o.livenessReleaseMeanMgdl)
                            ?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        "%.4f".format(java.util.Locale.US, o.livenessCandidateU),
                        o.livenessShadowNeedU?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        o.livenessShadowCandidateU?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        o.livenessShadowHeadroomU?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        o.livenessNoLiftReason ?: "",
                        o.signal?.rSigned?.let { "%.4f".format(java.util.Locale.US, it) } ?: "",
                        runCatching { "%.3f".format(java.util.Locale.US, ledger.view().transportCommitmentU) }.getOrDefault(""),
                        // ZERLEGUNG AM 60-MINUTEN-HORIZONT (15.09.): der exportierte Hub
                        // gilt fuer 120 min. Aus den Minutenpunkten der Mittelbahn:
                        // mean60 ~= Anker + Stoerung(1..60) + Insulin(1..60) + Transport.
                        *run {
                            val p = o.prediction
                            val bis60 = p?.points?.filter { it.offsetMin in 1..60 }.orEmpty()
                            if (p == null || bis60.size < 60) Array(7) { "" } else {
                                val mean60 = bis60.last().meanBg
                                val drive60 = bis60.sumOf { it.driveMean }
                                val bgi60 = bis60.sumOf { it.bgiRate }
                                val anker = p.bgAtAnchor
                                arrayOf(anker, mean60, drive60, bgi60, mean60 - anker - drive60 - bgi60, bis60.first().driveMean, bis60.last().driveMean)
                                    .map { "%.3f".format(java.util.Locale.US, it) }.toTypedArray()
                            }
                        },
                        o.livenessHold.upliftMgdl?.let { "%.3f".format(java.util.Locale.US, it) } ?: "",
                        o.livenessHold.streak,
                        o.livenessHold.denial ?: "",
                        o.signal?.let { s -> "%.4f".format(java.util.Locale.US, s.ukfRatePerMin + s.activityAtAnchor * s.isfAtAnchor) } ?: "",
                        "%.4f".format(java.util.Locale.US, cfIobBeforeU),
                        "%.3f".format(java.util.Locale.US, cfDeltaU),
                    ).joinToString(";"))
                }
            }
            // H8-Diagnose getrennt, damit die Haupt-CSV zeichengleich zum
            // Codex-Format bleibt (Kontroll-SHA).
            File(outDir, "replay_${name}_h8.csv").writeText(
                "ts;h8Configured;h8Horizon;h8Eligible;h8Selected;h8Denial;markerAgeMin;w10;ukf;earlyRelease;prodRelease;effRelease;needU;candU;candAfterHeadroomU;headroomU;smbU;liftU;lvBinding;binding;raw;source\n" +
                    h8Zeilen.joinToString("\n") + "\n",
            )
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
            return@runBlocking
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
                return@runBlocking
            }
            // CONFIRM-MATRIX: FUSE_REPLAY_GUARDS=confirm:2,3,4,6 faehrt
            // denselben Tag mit verschiedenen r-Bestaetigungszyklen des
            // V-Riegels. Erst seit die Kontextsperre korrigiert ist
            // (Mahlzeitenbasis statt jedes MEAL), kann dieser Parameter
            // ueberhaupt wirken - vorher band die Kontextgrenze frueher.
            // OHNE NACHTBAND: am Vorfallstag nullt das Nachtband dieselben
            // Zyklen, in denen der V-Riegel steht (wenige Minuten) - eine
            // Doppelverteidigung, die die Riegelwirkung im Replay
            // unsichtbar macht. Dieser Lauf schaltet NUR das Nachtband in
            // BEIDEN Laeufen ab und misst den Riegel allein.
            if (modus == "nonight") {
                nightDeadbandAus = true
                lauf("nonightAus", null, fenster = 10)
                lauf("nonightAn", null, fenster = 10, guardsStart = true)
                nightDeadbandAus = false
                return@runBlocking
            }
            if (modus.startsWith("confirm:")) {
                lauf("confirmAus", null, fenster = 10)
                modus.removePrefix("confirm:").split(",").forEach { c ->
                    val n = c.trim().toInt()
                    lauf("confirm%02d".format(n), null, fenster = 10, guardsStart = true, reversalConfirm = n)
                }
                return@runBlocking
            }
            lauf("guardsAus", null, fenster = 10)
            lauf("guardsAn", null, fenster = 10, guardsStart = true)
            return@runBlocking
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
            return@runBlocking
        }
        // FUSE_REPLAY_UPFRONT_CALM (Ruhe-Matrix) ist mit dem Ruhe-Ausgang entfallen (Konsolidierung 2.0).
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
            return@runBlocking
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
            return@runBlocking
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
            return@runBlocking
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
            return@runBlocking
        }
        val earlyNeedIobEnv = System.getenv("FUSE_REPLAY_EARLY_NEED_IOB")
        if (earlyNeedIobEnv != null) {
            // H8-Matrix (Bauauftrag 18.09.) mit der PRODUKTIVEN Einstellung
            // fuse_early_adaptive_meal_horizon_min: 0 ist die Kontrollspur,
            // eine zweite 0 am Ende die Blindprobe gegen Reihenfolge und
            // Zustandsleck. Geschlossene Eigeninsulin-Buchhaltung wie im
            // Codex-Replay: Kandidat minus historische Publikation wirkt ab
            // dem Folgezyklus auf IOB und Aktivitaet; die CGM-Reihe bleibt fest.
            earlyNeedIobEnv.split(",").forEachIndexed { idx, value ->
                val n = value.trim().toInt()
                lauf(
                    "earlyIob%d-h%02d".format(idx, n), null, fenster = 10,
                    fruehOverride = n, counterfactualIobFeedback = true,
                )
            }
            return@runBlocking
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
                return@runBlocking
            }
            val dauern = reboundEnv.split(",").map { it.trim().toInt() }
            for (d in dauern) {
                reboundFensterMin = d
                lauf("reb%03d".format(d), null, fenster = 10)
            }
            reboundFensterMin = 45
            return@runBlocking
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
            return@runBlocking
        }
        // FUSE_REPLAY_CAPS (v23-Ratio-Matrix des Alt-Deckels) ist mit dem
        // CENTRAL-only-Cleanup entfernt - Ratio-Kandidaten laufen ueber
        // FUSE_REPLAY_DOSING_CONTEXT (corrRatio=/mealRatio=).
        if (System.getenv("FUSE_REPLAY_REKON") != null) {
            // FRUEHSTUECKSREKONSTRUKTION (Toni 15.09.): EIN Lauf mit der
            // aufgezeichneten Politik (W10, Korrekturpfad-Riegel wie am Geraet).
            // Zweck: die in gesperrten Zyklen nicht ausgefuehrte
            // Kandidatenrechnung auf identischen Eingaengen nachholen. Ergebnis
            // sind Entscheidungsgroessen, kein Glukoseverlauf.
            lauf("rekon", null, fenster = 10, guardsStart = true)
            // Halte-Anhebung (15.09.) OFFEN gegen den echten Verlauf: zeigt, was
            // der Kanal je Zyklus bei gleicher Vorgeschichte gerechnet haette.
            // Keine Rueckkopplung - Glukose und Behandlungen bleiben die echten.
            if (System.getenv("FUSE_REPLAY_HALTE") != null) {
                halteAnhebungAn = true
                try { lauf("rekon_halte", null, fenster = 10, guardsStart = true) } finally { halteAnhebungAn = false }
            }
            return@runBlocking
        }
        run {
            lauf("w18", null, livenessStart = false) // Tor: aufzeichnungstreu (der Tag fuhr lange ohne Kanal)
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
        zeroLatchGrundWegZyklen = 0
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
     * v29 (der Sensorzacken-Grenzfall): das Fall-Verdikt zuendet erst nach ZWEI
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

    // ==== VARIANTENVERGLEICH NULLPHASEN - VARIANTE 1: GRUND-WEG-AUSGANG ====
    //
    // WAS HIER VERGLICHEN WIRD und was ausdruecklich nicht: zwei ECHTE
    // Regelvarianten laufen nacheinander durch dieselbe Lage, und verglichen
    // werden ihre ENTSCHEIDUNGEN (wann endet die Verriegelung, welche
    // Mengen gehen hinaus). Der Zucker ist in beiden Laeufen derselbe feste
    // Verlauf - er reagiert nicht. Aus diesem Rig folgt deshalb KEINE
    // Aussage darueber, wie der Zucker mit der Variante verlaufen waere.
    //
    // Es gibt keinen mitlaufenden Beobachter: der Schalter waehlt EINE
    // Variante, und der Lauf ist ihre einzige Spur.

    /** Die gemessene Nachtlage in Kurzform: berechtigte Zuendung aus einer
     *  Bolus-Ueberdeckung, danach faellt der Grund weg, waehrend der Zucker
     *  flach bleibt und die Ueberdeckung NOCH BESTEHT - genau die
     *  Konstellation, in der der Ruhe-Ausgang nicht greift. */
    private fun grundWegLage(dir: File, variante: Int) {
        latchLage(dir, an = true)
        zeroLatchGrundWegZyklen = variante
        // Der Ruhe-Ausgang bleibt bewusst unerreichbar (60 Zyklen), damit
        // der Vergleich EINE Variable hat: nur der neue Ausgang wirkt.
        zeroLatchRuheZyklen = 60
    }

    @Test
    fun `Variante 1 - der Grund-Weg-Ausgang loest frueher, die Basislinie bleibt unveraendert`(@TempDir dir: File) {
        // BASISLINIE (Schalter aus = heutiger Produktionsstand).
        grundWegLage(File(dir, "basis"), variante = 0)
        val basis = (0 until 45).map { cycle() }
        val zuendungB = basis.indexOfFirst { it.zeroLatchActive }
        assertTrue(zuendungB >= 0, "die Lage muss zuenden")
        val endeB = basis.indexOfFirst { it.zeroLatchReason == "REASON_GONE_RECOVERED" }
        assertEquals(-1, endeB, "ohne Schalter darf der neue Ausgang NIE feuern")

        // KANDIDAT (Schalter an, 3 zusammenhaengende Zyklen).
        transportReset()
        grundWegLage(File(dir, "kandidat"), variante = 3)
        val kand = (0 until 45).map { cycle() }
        val zuendungK = kand.indexOfFirst { it.zeroLatchActive }
        assertEquals(zuendungB, zuendungK, "die ZUENDUNG ist unveraendert - die Variante fasst nur das Ende an")
        val endeK = kand.indexOfFirst { it.zeroLatchReason == "REASON_GONE_RECOVERED" }
        assertTrue(endeK > 0) {
            "der Grund-Weg-Ausgang muss loesen: " +
                kand.mapIndexed { i, o -> "$i:${o.zeroLatchReason}" }.drop(zuendungK).take(20).joinToString(" ")
        }
        // Die Nullzeit wird KUERZER, nicht laenger.
        val nullB = basis.count { it.zeroLatchActive }
        val nullK = kand.count { it.zeroLatchActive }
        assertTrue(nullK < nullB) { "Nullzyklen Kandidat $nullK muss unter Basislinie $nullB liegen" }
        // Und nach dem Loesen bleibt sie gelöst (kein Flattern in den
        // naechsten Zyklen derselben Lage).
        assertTrue(kand.drop(endeK + 1).take(5).none { it.zeroLatchActive }, "kein Wiederverriegeln direkt danach")
    }

    @Test
    fun `Variante 1 gibt kein Insulin - sie stellt hoechstens das Profilbasal wieder her`(@TempDir dir: File) {
        // DIE ZENTRALE SICHERHEITSZUSAGE. Das Ende der Verriegelung darf
        // NICHTS ausschuetten: keine SMB-Menge, keine positive Rate, kein
        // Nachholen der ausgelassenen Basalmenge. Geprueft wird das gegen
        // die Basislinie - bitgleich auf der Insulinachse.
        grundWegLage(File(dir, "basis2"), variante = 0)
        val basis = (0 until 45).map { cycle() }
        transportReset()
        grundWegLage(File(dir, "kand2"), variante = 3)
        val kand = (0 until 45).map { cycle() }

        assertEquals(
            basis.sumOf { it.decision.smbU }, kand.sumOf { it.decision.smbU }, 1e-9,
            "die SMB-Summe MUSS bitgleich bleiben - die Variante fasst nur die Basalachse an",
        )
        assertTrue(kand.all { it.decision.smbU <= 0.0 }, "in dieser Lage fliesst ohnehin kein SMB")
        // KEINE RATE UEBER DEM PROFIL. Der ganze Aktuationsraum kennt nur
        // Null und Abbruch; dieser Test friert das fuer die Variante ein.
        val raten = kand.mapNotNull { it.tbr?.rateUPerH }.filter { it > 0.0 }
        assertTrue(raten.isEmpty()) { "keine positive TBR erlaubt, gemessen: $raten" }
    }

    // ==== VARIANTE 2: DIE SERIENBUCHUNG ======================================
    // 2.0 (Stufe 1, K2): der Serien-Deckel ist entfernt; die Serienliste wird fuer
    // den Rueckweg auf 1.x weiter gebucht. Die Tests des Deckels selbst sind
    // entfallen, die Buchung bleibt geprueft.

    @Test
    fun `Variante 2 bucht nur den markerlosen Korrekturpfad`(@TempDir dir: File) {
        // DIE ZUSAGE AN DER QUELLE geprueft: die Buchung liest DIESELBE
        // Groesse wie das Gate (mealAuthorized), nicht die engere
        // Onset-Bedingung der Mahlzeitenliste.
        //
        // DER BAUFEHLER, DEN DAS GEFANGEN HAT: zuerst stand hier
        // `!mealGebucht`. Das verlangt zusaetzlich das Onset-Fenster - ein
        // Zyklus unter MEAL-Vollmacht ausserhalb davon waere als
        // markerlose Korrektur gebucht worden, und der Deckel haette eine
        // autorisierte Mahlzeit begrenzt (gemessen: die Mahlzeit lief
        // exakt auf den 0,05-Deckel). Die MEAL-Ausnahme selbst traegt
        // zusaetzlich das Gate - s. ExposureGateTest.
        val l = FuseLedgerAdapter().also {
            it.loadOnce(File(dir, "buchung").also(File::mkdirs), "test-epoch", start)
        }
        zeroLatchAn = false; livenessAn = false; markerAuthorized = false
        fundamentAn = false; markerAt = 0L
        flach = 150.0; steigungProMin = 2.0; knickAbMin = null; bolusIobU = 0.0
        clock = start
        transportReset()
        neuerRunner(l)
        val outs = (0 until 20).map { transport(dir) }
        val dosiert = outs.filter { it.decision.smbU > 0.0 }
        assertTrue(dosiert.isNotEmpty(), "der Aufbau MUSS dosieren")
        assertTrue(dosiert.all { it.dosingContextProfile == "CORRECTION" }) {
            "und zwar markerlos: " + dosiert.map { it.dosingContextProfile }.distinct()
        }
        assertTrue(l.episodes.correctionDeliveries.isNotEmpty()) {
            "die markerlose Serie MUSS gebucht werden (Rueckweg auf 1.x)"
        }
        assertEquals(
            dosiert.sumOf { it.decision.smbU },
            l.episodes.correctionDeliveries.sumOf { it.amountU }, 1e-9,
            "und zwar vollstaendig - Buchung und Dosis muessen uebereinstimmen",
        )
    }

    /** Wie [mahlzeit], gibt aber den Ledger zurueck - fuer Pruefungen an
     *  den Buchungslisten selbst. */
    private fun mahlzeitMitLedger(dir: File): FuseLedgerAdapter {
        fundamentAn = true
        flach = 180.0
        steigungProMin = 2.5
        markerAuthorized = true
        markerAt = start + 2 * 60_000L
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        return l
    }

    @Test
    fun `Variante 1 - eine neue Nullphase erbt keinen Streak der vorigen`(@TempDir dir: File) {
        // DER REVIEW-BEFUND: der `else`-Zweig setzte nur den Ruhe-Zaehler
        // zurueck. Endete eine Phase auf einem ANDEREN Pfad, stand der
        // Grund-Weg-Zaehler noch - und weil der Zeitanschluss nur 90 s
        // Abstand verlangt, konnte die naechste Phase ihn erben und im
        // ERSTEN geeigneten Zyklus sofort loesen.
        //
        // Aufbau: ein hoher Schalter (5), den die erste Phase nie
        // erreicht, aber Zyklen sammelt; danach endet der Latch, eine
        // neue Phase beginnt dicht dahinter. Faellt der Zaehler nicht,
        // loest die zweite Phase zu frueh.
        grundWegLage(File(dir, "erbe"), variante = 5)
        val outs = mutableListOf<FuseCycleRunner.Outcome>()
        // Phase 1 bis kurz vor die Schwelle.
        repeat(30) { outs += cycle() }
        val ersteZuendung = outs.indexOfFirst { it.zeroLatchActive }
        assertTrue(ersteZuendung >= 0, "die Lage muss zuenden")
        // Latch beenden: eine klare Aufwaertswende beendet ihn ueber den
        // regulaeren Erholungspfad, nicht ueber den neuen Ausgang.
        flach = 150.0
        steigungProMin = 1.5
        knickAbMin = null
        bolusIobU = 0.0
        repeat(12) { outs += cycle() }
        assertTrue(outs.last().zeroLatchActive.not()) {
            "die erste Phase muss enden: " + outs.takeLast(6).map { it.zeroLatchReason }
        }
        val vorZweiter = outs.size
        // Neue Abwaertslage DICHT dahinter - der Zeitanschluss (90 s)
        // waere erfuellt, wenn ein Zaehler stehengeblieben waere.
        flach = 140.0
        steigungProMin = -1.2
        bolusIobU = 2.5
        val zweite = (0 until 20).map { cycle() }
        outs += zweite
        val zweiteZuendung = zweite.indexOfFirst { it.zeroLatchActive }
        assertTrue(zweiteZuendung >= 0) {
            "die zweite Phase muss zuenden: " + zweite.map { it.zeroLatchReason }.take(12)
        }
        // DIE SCHARFE PRUEFUNG - am ZAEHLER, nicht an seinem Ergebnis.
        //
        // Ein Test, der nur "loest nicht zu frueh" prueft, ist gruen, sobald
        // der Aufbau die Loesung ohnehin nicht erreicht - er haette den
        // Fehler nicht gefangen (gemessen: die Mutation blieb gruen).
        // Entscheidend ist, dass der Zaehler die Phasengrenze NICHT
        // ueberschreitet.
        val vorPhase1Ende = outs.take(vorZweiter)
        val maxStreakPhase1 = vorPhase1Ende.maxOf { it.zeroLatchReasonGoneStreak }
        assertTrue(maxStreakPhase1 > 0) {
            "der Aufbau MUSS in Phase 1 Grund-Weg-Zyklen sammeln - sonst " +
                "prueft der Test die Vererbung gar nicht (max=$maxStreakPhase1)"
        }
        // Zwischen den Phasen muss er auf 0 gefallen sein ...
        val zwischen = outs.subList(ersteZuendung, vorZweiter).filter { !it.zeroLatchActive }
        assertTrue(zwischen.isNotEmpty(), "es muss latchfreie Zyklen zwischen den Phasen geben")
        assertTrue(zwischen.all { it.zeroLatchReasonGoneStreak == 0 }) {
            "ohne laufenden Latch MUSS der Zaehler 0 sein, gemessen: " +
                zwischen.map { it.zeroLatchReasonGoneStreak }.distinct()
        }
        // ... und der erste Zyklus der zweiten Phase beginnt bei hoechstens 1.
        val ersterZweiter = zweite[zweiteZuendung]
        assertTrue(ersterZweiter.zeroLatchReasonGoneStreak <= 1) {
            "die neue Phase beginnt bei 0/1, geerbt waere mehr: " +
                "${ersterZweiter.zeroLatchReasonGoneStreak}"
        }
        // Und als Folge davon loest sie nicht sofort.
        val fruehLoesung = zweite.drop(zweiteZuendung).take(4)
            .indexOfFirst { it.zeroLatchReason == "REASON_GONE_RECOVERED" }
        assertEquals(-1, fruehLoesung) {
            "kein Erben eines alten Streaks - Gruende: " +
                zweite.drop(zweiteZuendung).take(6).map { it.zeroLatchReason }
        }
    }

    @Test
    fun `Variante 1 loest NICHT, solange der Schutzgrund noch anliegt`(@TempDir dir: File) {
        // Die Gegenprobe zur Wirksamkeit: waehrend das Verdikt anliegt,
        // zaehlt der Streak nicht - der Ausgang kann die Massnahme selbst
        // nicht abkuerzen. Ein Ein-Zyklus-Schalter macht den Test scharf:
        // greift die Grund-Bedingung nicht, muesste er sofort loesen.
        grundWegLage(File(dir, "grundAn"), variante = 1)
        val outs = (0 until 12).map { cycle() }
        val zuendung = outs.indexOfFirst { it.zeroLatchActive }
        assertTrue(zuendung >= 0)
        // In den Zyklen unmittelbar nach der Zuendung liegt der Grund noch
        // an (die Bahn faellt weiter) - dort darf nichts loesen.
        val mitGrund = outs.drop(zuendung)
            .takeWhile { it.lowThreat?.verdict != null && it.lowThreat!!.verdict.name != "NONE" }
        assertTrue(mitGrund.isNotEmpty(), "der Aufbau muss Zyklen MIT Grund haben")
        assertTrue(mitGrund.none { it.zeroLatchReason == "REASON_GONE_RECOVERED" }) {
            "solange der Grund anliegt, darf der Ausgang nicht feuern: " +
                mitGrund.map { "${it.lowThreat?.verdict}/${it.zeroLatchReason}" }.joinToString(" ")
        }
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
     * DIE V-KURVE DES PFLICHTFALLS, als Rohreihe vorgegeben:
     * flach 137-139, Sturz auf 101
     * (UKF-Minimum deutlich negativ), steile Erholung auf 149, danach
     * FLACH. Glatte synthetische Formen taugen hier nicht: ein DAUER-Anstieg
     * erfuellt die Kinematik-Bedingung des Mahlzeitenfensters (r und
     * UKF beide ueber der Rampen-Unterkante) und nimmt dem Riegel den
     * autoritativen Kontext - erst diese Kurvenform traegt genau die Lage,
     * um die es geht: UKF stark positiv bei robustem r noch negativ.
     */
    private val vKurveRoh = listOf(
        137.0, 139.0, 139.0, 136.0, 132.0, 126.0, 120.0, 114.0, 111.0, 109.0,
        108.0, 105.0, 103.0, 101.0, 105.0, 113.0, 125.0, 136.0, 142.0, 146.0,
        149.0, 149.0, 149.0, 148.0, 147.0, 147.0, 147.0, 147.0, 147.0, 147.0,
        147.0, 146.0, 144.0, 144.0, 143.0, 142.0,
    )

    /**
     * V-REVERSAL-LAGE: die V-Kurve, ohne Marker, ohne Fundament,
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
        // POLITIK des Pflichtfalls:
        // die Rampen-Unterkante setzt die Kontextgrenze, das W10-Fenster
        // die Geschwindigkeit, mit der das robuste r der Wende folgt. Mit
        // dem Rig-Default W18 traegt der Sturz so lange nach, dass
        // Prognose-Boden und Kontextwechsel zusammenfallen und die zu
        // pruefende Lage gar nicht entsteht.
        riseRampLowRWert = 1.5
        theilSenFensterMin = 10
        // Den Guard-Boden ausdruecklich OEFFNEN: er ist eine ANDERE
        // Verteidigung und wuerde in den Riegel-Zyklen mitbinden - dann
        // waere die verhinderte Dosis nicht dem Riegel zuzuordnen. Im
        // Pflichtfall lag an derselben Stelle das Nachtband davor; die erste
        // Dosis fiel mit dessen Ende.
        guardBodenMgdl = 40.0
        // 20 min flacher Vorlauf auf dem Startwert, dann die V-Kurve.
        rohSerie = (0 until 20).map { min -> (start + min * 60_000L) to 137.0 } +
            vKurveRoh.mapIndexed { i, v -> (start + (20 + i) * 60_000L) to v }
        bolusIobU = 0.0
        clock = start
        transportReset()
        val adapter = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(adapter)
    }

    /**
     * v30-PFLICHTFALL 1: nach dem steilen Fall traegt die
     * schnelle Gegenbewegung keine Korrektur-SMBs, solange das robuste r
     * negativ oder unbestaetigt ist. NUR die Menge ohne Grant faellt; die
     * TBR-Achse ist in beiden Laeufen bitgleich. Nach der r-Bestaetigung
     * fliesst es wieder - kein Carry, keine globale r/UKF-Verschaerfung.
     */
    @Test
    fun `correction-reversal-guard blockt die v-erholung bis r bestaetigt`(@TempDir dir: File) {
        // 2.0 (Stufe 1, K3): das Nacht-Totband ist fest an. Diese Lage prueft den
        // Riegel OHNE Nachtband (bisher ueber die Rig-Vorgabe nightDeadband = false);
        // dafuer liegt das Nachtfenster jetzt nach der Rig-Uhr, die Lage selbst
        // bleibt unveraendert.
        nachtStartMin = 120
        nachtEndeMin = 480
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
        // Gegenzug bei negativem/unbestaetigtem r - der Kern des Pflichtfalls.
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
        // Kontextwechsel (die V-Kurve nimmt den zweiten Weg:
        // r und UKF ueber der Rampenkante erfuellen die Kinematik des
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
     * `MEAL` ausnimmt, kann diesen Vorfall konstruktiv nie
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
     * ihn der Mahlzeiten-Gegenlauf ab (ein Mahlzeitentag: viele EVIDENCE_ACTIVE-Zyklen,
     * kein einziger Riegel-Tag).
     */
    @Test
    fun `eine belegte mahlzeit hinter der kinematik nimmt den schutz heraus`(@TempDir dir: File) {
        reversalLage(dir)
        kontoAn()
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
     * aufgezeichnete Mahlzeitentag traegt knapp eine Stunde EVIDENCE_ACTIVE nach
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
        kontoAn()
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
     * v30-PFLICHTFALL 2: die Zero-Latch-Loesung oeffnet
     * positive Korrektur-SMBs erst nach der Mindestdauer UND bestaetigter
     * Aufwaertslage - nicht in der ersten Minute nach einer Stunde
     * verriegelter Null. Ohne Schalter fliesst es sofort (Vorbedingung).
     */
    @Test
    fun `positive-correction-rearm haelt die latch-loesung zurueck`(@TempDir dir: File) {
        // 2.0 (Stufe 1, K3): wie beim Reversal-Riegel - die Lage prueft den Nachlauf
        // OHNE Nachtband; das Nachtfenster liegt nach der Rig-Uhr.
        nachtStartMin = 120
        nachtEndeMin = 480
        fun lauf(an: Boolean, unterDir: String): Pair<List<FuseCycleRunner.Outcome>, Int> {
            // +0,45/min ist die Form des Pflichtfalls: genug fuer die Latch-Loesung,
            // aber UNTER der Rampen-Unterkante - der autoritative Kontext
            // bleibt Korrektur (eine steilere Erholung oeffnet das
            // Mahlzeitenfenster kinematisch und nimmt dem Riegel den
            // Kontext). Die Bestaetigungsschwelle liegt entsprechend
            // darunter; im Pflichtfall war die UKF deutlich positiv bei kleinem r.
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
        // insbesondere NICHT in der ersten Minute nach der Kante (Kern des Pflichtfalls).
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
     * Marker ist eine ZEITLICH BEGRENZTE Leistungsautorisierung (Konsolidierung
     * 2.0: geprueft am Dosierkontext - der Kanal kennt kein MEAL-Profil mehr). Kein
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
        assertEquals("CORRECTION", o1.dosingContextProfile)
        assertEquals("NO_MARKER", o1.dosingContextReason)

        markerAt = clock // beobachteter Wechsel
        // Der ERSTE Zyklus nach dem Druck ist die Evidenz-Rebase (EXCLUDED-
        // Blip, bestehendes v18-Verhalten) - danach gilt MEAL.
        repeat(2) { cycle() }
        val o2 = cycle()
        assertEquals("MEAL", o2.dosingContextProfile, "Grund=${o2.dosingContextReason}")
        // Konsolidierung 2.0: ohne Konto dosiert der Kanal dabei wie ohne Marker (Rueckfall).
        assertEquals("CORRECTION", o2.livenessProfile)
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
        assertEquals("CORRECTION", o4.dosingContextProfile)
        assertEquals("POWER_EXPIRED", o4.dosingContextReason)

        // Zweiter Marker eroeffnet eine neue Frist (Test 5).
        markerAt = clock
        val o5 = cycle()
        assertEquals("MEAL", o5.dosingContextProfile)
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
        assertEquals("MEAL", o.dosingContextProfile)
        assertTrue(adapter.persistVerified(lage), "versiegeln")

        // T7+T15: Identitaet passt -> Restfrist laeuft weiter.
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(lage, "test-epoch", clock) })
        val n = cycle()
        assertEquals(o.markerPowerPinnedFor, n.markerPowerPinnedFor, "Codec-Roundtrip identisch")
        assertEquals(o.markerPowerDeadlineTs, n.markerPowerDeadlineTs)
        assertEquals("MEAL", n.dosingContextProfile, "Restfrist wird fortgesetzt")

        // T8 + Identitaetsvergleich: anderer Marker, nur vorgefunden (kein
        // im Prozess beobachteter Wechsel) -> KEIN rueckwirkendes MEAL.
        markerAt = clock + 60_000L
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(lage, "test-epoch", clock) })
        clock += 120_000L
        val f = cycle()
        assertEquals("CORRECTION", f.dosingContextProfile, "vorgefundener fremder Marker pinnt nicht")
        assertEquals("MARKER_NOT_PINNED", f.dosingContextReason)
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
     * TRENDREGEL-VERTRAG (Toni 23.08. Abend), DOWN-Seite - MIT DEM
     * STRUKTURBEFUND, der beim Bau herauskam und den die Live-Daten
     * bestaetigen (weit ueber tausend getriggerte Lane-Zyklen im Shadow-Trail,
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

    /** 2.0 (Stufe 1, K1): ohne Prognose-Sammler steht der Stand fest auf AUS - die
     *  Sammel-Epoche bleibt stehen und restartfest, damit der Trail sie weiter fuehrt. */
    @Test
    fun `die Sammel-Epoche bleibt ohne Prognose-Sammler stehen und restartfest`(@TempDir dir: File) {
        val adapter = livenessLage(dir)
        // Signalaufbau: die ersten Zyklen brechen ab und tragen Defaults.
        repeat(6) { cycle() }
        val e1 = cycle().forecastShadowEpochTs
        assertTrue(e1 > 0L)
        repeat(5) { assertEquals(e1, cycle().forecastShadowEpochTs, "die Epoche bleibt stehen") }
        assertTrue(adapter.persistVerified(dir))
        assertEquals(e1, nachNeustart(dir).forecastShadowEpochTs, "die Epoche steht in der Datei")
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
     * v19-Vertrag (Codex, aus einem Live-Trail): die Sperre NULLT den
     * Streak. Vorher zaehlte er waehrend der Pause weiter (live gemessen
     * 1->10), und der Kanal war nach Fristablauf SOFORT wieder scharf -
     * statt drei frische Druckzyklen zu verlangen. Der Exit laeuft hier
     * ueber einen Abbruchzyklus, weil nur dieser Exit KEINE stehende
     * Bedingung hinterlaesst (Manual-Exit nullt den Streak selbst per
     * Wanduhr, Modell-/Wende-Exits blocken auch nach der Pause weiter -
     * die Mutation waere dort unsichtbar).
     */
    @Test
    fun `Liveness v19 - die Sperre nullt den Streak und danach zaehlen drei frische Zyklen`(@TempDir dir: File) = runBlocking<Unit> {
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
        val kaputt = org.mockito.kotlin.spy(rigProfil)
        org.mockito.kotlin.doReturn(5000.0).whenever(kaputt).getIsfMgdlTimeFromMidnight(org.mockito.kotlin.any())
        whenever(profileFunction.getProfile()).thenReturn(kaputt)
        whenever(profileFunction.getProfile(any())).thenReturn(kaputt)
        val abbruch = cycle()
        assertEquals("OBSERVATION_LOST", abbruch.livenessExit)
        val sperreBis = abbruch.livenessReArmUntilTs
        whenever(profileFunction.getProfile()).thenReturn(rigProfil)
        whenever(profileFunction.getProfile(any())).thenReturn(rigProfil)
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
     * Gegenprobe 8 (Audit 22.08.): eine TAKTLUECKE - Minuten ohne Zyklus
     * bei lueckenlos weiterlaufender CGM-Reihe (Pumpe belegt, Prozess
     * pausiert) - ueberbrueckt den Lauf nicht. BEWUSST OHNE Sperre: die
     * Pumpenzyklen strecken sich real bis 854 s; eine Sperre je
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
     *     T+0      Kalibrierung
     *     T+5..6   neues Signal erreicht 5x8-Reife
     *     T+60     kurze Funkluecke
     *     T+64     4x3-Rejoin erlaubt
     *
     * Der erste Wurf haette hier bis etwa T+180 gesperrt, weil die Grenze
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
     * Pflichtfalls (BG knapp ueber dem Tief, fallend). Der Wiedereinstieg gibt
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


    // Konsolidierung 2.0: die Klemmproben des Aufschubs (`das Klemmereignis gilt nur fuer seinen eigenen Zyklus`,
    // `der Klemmfall meldet Bestand, Kuerzung, Grund und Zeitstempel`) und ihr Rig `ruheLauf` sind mit Rueckhalt
    // und Klemmung des Aufschubs entfallen.

    // =====================================================================
    // RUNNER-AUSGABETEST: DER TBR-KOMMANDOSTROM DER TEILBASAL-STUFE
    // =====================================================================

    /**
     * WAS HIER GEZAEHLT WIRD, und was NICHT: `Outcome.tbr` ist die
     * TBR-Anforderung, die der Runner AUSGIBT und die das Plugin
     * unveraendert weiterreicht (`FusePlugin: tbr = outcome.tbr`). Das ist
     * ein RUNNER-AUSGABETEST, kein CommandQueue-E2E - was AAPS' Queue
     * daraus macht, liegt hinter dem Plugin und wird in
     * `PartialTbrOwnershipTest` ueber die Aktionsklassifikation geprueft
     * (`klassifiziere`, dieselbe Bedingung wie `LoopPlugin`).
     */
    private data class TbrKommando(val rateUPerH: Double, val durationMin: Int, val grund: String)

    private fun zyklusMitKommando(): Pair<FuseCycleRunner.Outcome, TbrKommando?> {
        val o = cycle()
        return o to o.tbr?.let { TbrKommando(it.rateUPerH, it.durationMin, o.reason ?: "") }
    }

    private fun besitz() = ledger.episodes.ownPartialTbr

    /**
     * KI-179: DER SCHALTER AUS BEENDET AUCH DIE TEILSTUFE.
     *
     * Die Felder der Teilstufe wurden nur im Zweig "Zero-Latch an" neu
     * berechnet. Schaltete man den Latch waehrend einer aktiven Teilstufe aus,
     * blieb ihr Flag stehen. Im flachen Abschnitt danach (Schwanz-Sperre,
     * NO_POSITIVE) beendete der Lebenszyklus der eigenen Teil-TBR die laufende
     * Schutz-Null ("zurueck aufs Profilbasal", KEEP_END_OWN_PARTIAL) - die
     * gefaehrliche Richtung. Das Szenario stammt aus dem Sondierungstest der
     * Forschung.
     *
     * Erwartet nach dem Ausschalten: kein Zyklus mit aktiver Teilstufe und im
     * flachen Abschnitt kein Abbruch der Null aus dem Teilstufen-Lebenszyklus.
     */
    @Test
    fun `KI-179 Zero-Latch aus beendet die Teilstufe und laesst die Schutz-Null stehen`(@TempDir dir: File) = runBlocking<Unit> {
        zeroLatchAn = true
        teilbasalAn = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))

        var aktiv = false
        for (i in 0 until 80) {
            if (cycle().partialRecoveryActive) { aktiv = true; break }
        }
        assertTrue(aktiv, "Vorbedingung: der Aufbau muss eine aktive Teilstufe erzeugen - sonst prueft der Test nichts")

        zeroLatchAn = false
        val flach6 = (0 until 6).map { zyklusMitKommando() }       // Schwanz-Sperre, NO_POSITIVE
        steigungNachKnick = -1.2
        val fall8 = (0 until 8).map { zyklusMitKommando() }        // erneuter Fall
        val bericht = (flach6 + fall8).joinToString("\n") { (o, k) ->
            "flag=${o.partialRecoveryActive} intent=${o.decision.tbr} block=${o.decision.block} " +
                "kmd=${k?.let { "${it.rateUPerH}/${it.durationMin}" }} grund=${o.reason}"
        }
        assertTrue((flach6 + fall8).none { it.first.partialRecoveryActive }) {
            "KI-179: die Teilstufe steht nach dem Ausschalten des Latch\n$bericht"
        }
        assertTrue(flach6.none { (o, k) ->
            k?.durationMin == 0 && o.reason == app.aaps.fuse.core.controller.TbrPolicy.KEEP_END_OWN_PARTIAL_REASON
        }) { "KI-179: der Teilstufen-Lebenszyklus beendet die Schutz-Null\n$bericht" }
    }

    /**
     * DIE VOM REVIEW GEFORDERTE SETZ-FOLGE.
     *
     * Der Vorgaengertest sah nur die Ledgerphasen und uebersah, dass die
     * Teil-TBR bei unsichtbarer Pumpe JEDE MINUTE neu angefordert wurde:
     * die Bestaetigungsfrist begann jedes Mal neu, CONFIRM_TIMEOUT kam nie
     * zum Zug, und eine verspaetet sichtbare Rate waere immer wieder auf
     * 30 min verlaengert worden.
     */
    @Test
    fun `E2E der Kommandostrom der Teilbasal-Stufe`(@TempDir dir: File) = runBlocking<Unit> {
        zeroLatchAn = true
        teilbasalAn = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))

        // ---- (1) Eintritt: GENAU EIN Teil-TBR-Setzkommando --------------
        //
        // ACHTUNG: `return@repeat` ist ein CONTINUE, kein break - eine
        // Vorfassung lief deshalb alle 80 Zyklen durch und zaehlte 4
        // statt 1. Hier ein echter `break`.
        var setz = 0
        var da = false
        val lauf = mutableListOf<FuseCycleRunner.Outcome>()
        for (i in 0 until 80) {
            val (o, k) = zyklusMitKommando()
            lauf += o
            // Eine ANHEBENDE TBR mit positiver Dauer kann in diesem Aufbau
            // nur die Teilrate sein; Schutz-Null ist Rate 0, Abbruch Dauer 0.
            if (k != null && k.rateUPerH > 0.0 && k.durationMin > 0) setz++
            if (o.partialRecoveryActive) { da = true; break }
        }
        assumeTrue(da, "Aufbau erzeugt in diesem Rig keine Teilstufe - Rest ungeprueft")
        assertEquals(1, setz) { "der Eintritt kostet genau EIN Setzkommando" }

        // ---- DER EXPORT BESCHREIBT DENSELBEN ZYKLUS ---------------------
        //
        // Vorher kam er aus dem Zustand VOR der Buchung: im Aktionszyklus
        // stand `Action=SET_PARTIAL` neben `Phase=NONE, Attempts=0`, beim
        // ersten Abbruch `Attempts=0` statt 1. Aktion, Phase, Ziel und
        // Zaehler muessen zusammenpassen.
        val eintritt = lauf.last()
        assertEquals("SET_PARTIAL", eintritt.partialRecoveryAction)
        assertEquals("PENDING", eintritt.partialRecoveryPhase) {
            "eine gerade erst angeforderte Rate ist PENDING, nicht NONE"
        }
        assertEquals(1, eintritt.partialRecoveryAttempts) { "der erste Versuch ist 1, nicht 0" }
        assertEquals(PartialTbrOwnership.SET_MAX_ATTEMPTS, eintritt.partialRecoveryMaxAttempts)
        assertNotNull(eintritt.partialRecoveryTargetUPerH) { "und die Zielrate steht" }
        // IST und ABSICHT getrennt: die laufende Null steht daneben.
        assertEquals("AUTHORITATIVE", eintritt.partialRecoveryView)
        assertEquals(0.0, eintritt.partialRecoveryObservedUPerH ?: -1.0, 1e-9) {
            "links die tatsaechlich laufende Rate - hier die Schutz-Null"
        }
        assertNotNull(besitz().pendingRequest) { "und er ist als OFFENE Anforderung gebucht" }
        assertNull(besitz().confirmedRunning) { "nicht als bestaetigter Besitz" }
        assertEquals(1, besitz().pendingAttempts)
        val setAtNachEintritt = besitz().pendingRequest!!.setAtTs

        // ---- (2) Fuenf autoritative Snapshots OHNE sichtbare TBR --------
        quelleMeldet(null)
        var weitereSetz = 0
        repeat(5) {
            val (_, k) = zyklusMitKommando()
            if (k != null && k.rateUPerH > 0.0 && k.durationMin > 0) weitereSetz++
        }
        assertEquals(0, weitereSetz) { "DAS war die Race: minuetliches Nachsetzen" }
        // Solange die Anforderung offen IST, darf ihre Frist nicht neu
        // beginnen. Erreicht die Guard-Rate zwischendurch das Profilbasal,
        // ist die richtige Antwort ein ABBRUCH und die Anforderung damit
        // erledigt - dann gibt es nichts mehr, dessen Frist laufen koennte.
        besitz().pendingRequest?.let {
            assertEquals(setAtNachEintritt, it.setAtTs) {
                "ein unterdruecktes Duplikat darf die Frist nicht neu starten"
            }
            assertEquals(1, besitz().pendingAttempts)
        }
        // ---- (3) BIS ZUR RUECKKEHR AUFS PROFILBASAL ---------------------
        //
        // Die Guard-Rate steigt mit der Bahn und laeuft in den
        // Profildeckel. DORT ist die richtige Antwort kein Setzen mehr,
        // sondern der ABBRUCH der laufenden Null - `LoopPlugin` wuerde
        // eine Rate auf Profilbasal ohnehin als `cancelTempBasal`
        // ausfuehren. Das ist der zentrale Fall der ganzen Stufe.
        //
        // Die Pumpe nimmt den Abbruch NICHT an: die Null bleibt sichtbar.
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))
        val abbrueche = mutableListOf<Long>()
        val lauf2 = mutableListOf<FuseCycleRunner.Outcome>()
        for (i in 0 until 30) {
            val (o, k) = zyklusMitKommando()
            lauf2 += o
            assertEquals(0.0, o.decision.smbU, 1e-12) { "waehrend des Abbruchs nie ein SMB" }
            if (k != null && k.rateUPerH == 0.0 && k.durationMin == 0) abbrueche += clock / 60_000L
        }

        // ---- (4) SOFORT EINER, DANN BACKOFF, DANN DECKEL ----------------
        assertEquals(PartialTbrOwnership.END_MAX_ATTEMPTS, abbrueche.size) {
            "genau END_MAX_ATTEMPTS Abbrueche, gezaehlt: $abbrueche"
        }
        assertTrue(abbrueche.zipWithNext().all { (a, c) -> c - a >= PartialTbrOwnership.END_BACKOFF_MIN }) {
            "jeder Wiederholversuch haelt den Backoff ein: $abbrueche"
        }
        assertNotNull(besitz().ending) { "und der Abbruchzustand steht" }

        // ---- ANZEIGEVERTRAG WAEHREND DES ABBRUCHS ----------------------
        //
        // Als ZIEL steht dort die RUECKKEHRBASIS, nicht die alte eigene
        // Teilrate - sonst zeigte die Zeile als Ziel genau die Rate, die
        // gerade beendet wird. Und die Quelle der beobachteten Rate ist
        // benannt: FUSE darf einen FAKE_EXTENDED gar nicht steuern.
        val letzter = lauf2.last { it.tbr != null }
        assertTrue(letzter.partialRecoveryPhase in listOf("ENDING", "BACKOFF", "GIVEN_UP")) {
            "Phase=${letzter.partialRecoveryPhase}"
        }
        assertEquals("TEMP_BASAL", letzter.partialRecoveryObservedSource)
        assertEquals(0.0, letzter.partialRecoveryObservedUPerH ?: -1.0, 1e-9) {
            "links die laufende Null"
        }
        letzter.partialRecoveryTargetUPerH?.let {
            assertTrue(it > 0.0) { "das Ziel ist die Rueckkehrbasis, nicht die beendete Rate" }
        }

        // ---- (5) AUTORITATIV KEINE TBR -> ZUSTAND GELOESCHT -------------
        quelleMeldet(null)
        for (i in 0 until 5) {
            cycle()
            if (besitz().leer && besitz().ending == null) break
        }
        assertTrue(besitz().leer) { "ein autoritativer Snapshot ohne TBR beendet den Zustand: ${besitz()}" }
    }

    // =====================================================================
    // LANGSAMER KORREKTURKANAL (Toni 20.10.2026): DER KOMMANDOSTROM
    // =====================================================================

    /** Was die Rig-Pumpe gerade fuehrt: Rate [U/h] und Start auf der Rig-Uhr; null = keine TBR. */
    private var rigTbr: Pair<Double, Long>? = null

    private fun langsamerKanal(an: Boolean, schwelle: Double = 180.0, faktor: Double = 2.0, einstieg: Int = 30) {
        whenever(preferences.get(FuseBooleanKey.SlowChannelEnabled)).thenReturn(an)
        whenever(preferences.get(FuseDoubleKey.SlowChannelFactor)).thenReturn(faktor)
        whenever(preferences.get(FuseDoubleKey.SlowChannelThresholdMgdl)).thenReturn(schwelle)
        whenever(preferences.get(FuseIntKey.SlowChannelEntryMin)).thenReturn(einstieg)
    }

    /**
     * EIN ZYKLUS MIT EINER PUMPE, DIE KOMMANDOS WIE `LoopPlugin` AUSFUEHRT: Dauer 0 ist ein Abbruch, eine Rate innerhalb
     * eines Basalschritts um das Profilbasal (1,0 U/h im Rig) auch; dieselbe Rate bei mehr als 5 min Rest laeuft weiter
     * (let_temp_basal_run). Die Restzeit meldet die Quelle gegen die Wanduhr, wie `TB.plannedRemainingMinutes` sie
     * rechnet.
     */
    private suspend fun zyklusMitPumpe(): Pair<FuseCycleRunner.Outcome, TbrKommando?> {
        clock += taktMs
        rigTbr?.let { (_, seit) -> if (clock - seit >= 30 * 60_000L) rigTbr = null }
        quelleMeldet(rigTbr?.let { (rate, seit) ->
            TB(timestamp = System.currentTimeMillis() - (clock - seit), duration = 30 * 60_000L, rate = rate,
               isAbsolute = true, type = TB.Type.NORMAL)
        })
        val o = runner.run(false, testPumpe())
        val k = o.tbr?.let { TbrKommando(it.rateUPerH, it.durationMin, o.reason) }
        if (k != null) {
            val laufend = rigTbr
            val rest = laufend?.let { (_, seit) -> 30 - (clock - seit) / 60_000L } ?: 0L
            rigTbr = when {
                k.durationMin == 0 -> null
                abs(k.rateUPerH - 1.0) < 0.05 -> null
                laufend != null && rest > 5 && abs(k.rateUPerH - laufend.first) < 0.05 -> laufend
                else -> k.rateUPerH to clock
            }
        }
        return o to k
    }

    private fun kanalBericht(lauf: List<Pair<FuseCycleRunner.Outcome, TbrKommando?>>) = lauf.joinToString("\n") { (o, k) ->
        "t=${(o.computeTs - start) / 60_000L} smb=${o.decision.smbU} kmd=${k?.let { "${it.rateUPerH}/${it.durationMin}" }} " +
            "kanal=${o.slowChannel?.let { "${it.reason} want=${it.want} phase=${it.phase} hoch=${it.highForMin} " +
                "abg=${"%.3f".format(it.deliveredU)} res=${"%.3f".format(it.reservedU)} verf=${"%.3f".format(it.cancelledU)}" }} " +
            "grund=${o.reason}"
    }

    /**
     * DER KANAL ALS KOMMANDOSTROM: Einstieg erst nach der Einstiegszeit, dann GENAU EIN Setzkommando je Block (keine
     * minuetliche Wiederholung, kein Abbruch durch den Normalpfad), Erneuerung kurz vor dem Ende, ein Abbruch beim Fall -
     * und die Buchung trennt abgegeben, reserviert und verfallen.
     */
    @Test
    fun `langsamer Kanal - Einstieg, Halten, Erneuern und Stopp als Kommandostrom`(@TempDir dir: File) = runBlocking<Unit> {
        flach = 220.0
        steigungProMin = 0.0
        // Der Normalpfad gibt hier nie einen SMB - so ist der Kanal allein messbar.
        maxSmbU = 0.0
        langsamerKanal(an = true)
        clock = start
        rigTbr = null
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })

        val lauf = (0 until 95).map { zyklusMitPumpe() }
        val bericht = kanalBericht(lauf)
        val setz = lauf.withIndex().filter { (_, p) -> p.second?.let { it.rateUPerH > 0.0 && it.durationMin > 0 } == true }
        assertTrue(setz.size >= 2) { "Einstieg und mindestens eine Erneuerung erwartet\n$bericht" }
        val (eintritt, eintrittKmd) = setz.first().value
        assertTrue(eintritt.slowChannel!!.highForMin >= 30.0) { "Einstieg vor der Einstiegszeit\n$bericht" }
        assertEquals(lauf.first { it.first.slowChannel?.reason == "START" }.first.computeTs, eintritt.computeTs)
        assertEquals(2.0, eintrittKmd!!.rateUPerH, 1e-9) { "Obergrenze Faktor 2 x Profilbasal 1,0" }
        // Der Bedarf, mit dem der Kanal rechnete, ist der gemeinsame Bedarf vor den Sperren: (Bahn - Ziel) / ISF.
        assertEquals(
            (eintritt.decision.predAtReleaseMgdl!! - eintritt.targetMgdl!!) / eintritt.isfMgdlPerU!!,
            eintritt.slowChannel!!.demandU!!, 1e-9,
        )
        assertEquals(30, eintrittKmd.durationMin)
        assertTrue(eintrittKmd.grund.startsWith(FuseTbrTranslator.SLOW_SET_REASON)) { eintrittKmd.grund }
        // Erneuert wird alle 25 min (bei 5 min Rest), dazwischen geht KEIN Kommando hinaus.
        val abstaende = setz.map { it.index }.zipWithNext { a, b -> b - a }
        assertTrue(abstaende.all { it == 25 }) { "Erneuerungsabstaende $abstaende\n$bericht" }
        val nachEintritt = lauf.drop(setz.first().index)
        assertEquals(setz.size, nachEintritt.count { it.second != null }) { "nur die Setzkommandos des Kanals\n$bericht" }
        assertTrue(nachEintritt.all { it.first.decision.smbU == 0.0 })
        // Die Buchung: waehrend der Block laeuft, waechst "abgegeben", "reserviert" ist der Rest des Blocks.
        val mitte = nachEintritt[10].first.slowChannel!!
        assertEquals("RUNNING", mitte.phase) { bericht }
        assertEquals(1.0 * 20 / 60.0, mitte.reservedU, 0.02) { "Rest ~20 min x 1,0 U/h Zusatz\n$bericht" }
        assertTrue(mitte.deliveredU > 0.1) { bericht }
        assertEquals(0.0, mitte.cancelledU, 1e-9)
        // Erneuerungen loesen den Block nur ab - verfallen ist bis zum Stopp nichts.
        assertEquals(0.0, lauf.last().first.slowChannel!!.cancelledU, 1e-9) { bericht }
        // GEPLANTER REST BLEIBT GEPLANT: in der Prognose steht nur der Zusatz ab jetzt (schon abgegebene Minuten fallen
        // heraus) und nie alter plus neuer Block - hoechstens ein ganzer Block, 1,0 U/h Zusatz x 30 min.
        assertTrue(lauf.all { (o, _) -> (o.slowChannel?.plannedRemainderU ?: 0.0) <= 0.5 + 1e-9 }) { bericht }
        assertEquals(1.0 * 20 / 60.0, mitte.plannedRemainderU, 0.02) { bericht }
        val nachErneuerung = lauf[setz[1].index + 1].first.slowChannel!!
        assertEquals(1.0 * 29 / 60.0, nachErneuerung.plannedRemainderU, 0.02) { "die alte Restplanung ist ersetzt, nicht addiert\n$bericht" }

        // ---- STOPP: der BG faellt steil -> genau ein Ende des Blocks, danach kein Setzen mehr ----
        knickAbMin = ((clock - start) / 60_000L).toInt()
        steigungNachKnick = -3.0
        val fall = (0 until 15).map { zyklusMitPumpe() }
        val fallBericht = kanalBericht(fall)
        val enden = fall.filter { (_, k) -> k != null && k.rateUPerH == 0.0 }
        assertTrue(enden.isNotEmpty()) { "der Fall beendet den Block\n$fallBericht" }
        assertTrue(enden.first().second!!.grund.startsWith("SLOW_CORRECTION_END")) { fallBericht }
        assertTrue(fall.none { (_, k) -> k != null && k.rateUPerH > 0.0 }) { "nach dem Stopp kein Setzen\n$fallBericht" }
        assertTrue(rigTbr == null || rigTbr!!.first == 0.0) { "die Kanal-TBR laeuft nicht mehr: $rigTbr\n$fallBericht" }
        val danach = fall.last().first.slowChannel!!
        assertEquals("NONE", danach.phase) { fallBericht }
        assertTrue(danach.cancelledU > 0.0) { "der abgebrochene Rest ist als verfallen gebucht\n$fallBericht" }
        assertEquals(0.0, danach.reservedU, 1e-9)
    }

    /**
     * DAS EINE SCHLIESST DAS ANDERE NICHT AUS (Toni): die positive TBR laeuft wie das Profilbasal nebenher und darf
     * die Versorgung ueber den schnellen Kanal weder bremsen noch verzoegern - beide bedienen einen gemeinsamen
     * Restbedarf. Derselbe dosierende Verlauf mit und ohne Kanal:
     *  - bis die geplante TBR in der Prognose steht, sind beide Laeufe Zyklus fuer Zyklus gleich - auch im Zyklus,
     *    in dem der Kanal setzt (er fasst den SMB nie an);
     *  - danach steht ihr geplanter Rest zeitabhaengig in der Bahn: die Prognose liegt tiefer als ohne Kanal;
     *  - SMB fliessen weiter, waehrend sie laeuft.
     */
    @Test
    fun `langsamer Kanal - SMB und Kanal laufen nebeneinander`(@TempDir dir: File) = runBlocking<Unit> {
        fun lauf(an: Boolean, unterordner: String): List<Pair<FuseCycleRunner.Outcome, TbrKommando?>> = runBlocking {
            flach = 220.0
            steigungProMin = 0.0
            maxSmbU = 0.3
            // Obergrenze 5 x 1,0 U/h: je Stufe genau 1,0 U/h Zusatz - so unterscheiden die Stufen den Restbedarf.
            langsamerKanal(an = an, faktor = 5.0)
            clock = start
            rigTbr = null
            transportReset()
            neuerRunner(FuseLedgerAdapter().also { it.loadOnce(File(dir, unterordner).also(File::mkdirs), "test-epoch", start) })
            (0 until 70).map { zyklusMitPumpe() }
        }
        val ohne = lauf(an = false, unterordner = "ohne")
        val mit = lauf(an = true, unterordner = "mit")
        val bericht = kanalBericht(mit)
        assertTrue(mit.any { (_, k) -> k != null && k.grund.startsWith(FuseTbrTranslator.SLOW_SET_REASON) }) {
            "der Kanal setzt trotz dosierendem Normalpfad\n$bericht"
        }
        assertTrue(mit.any { (o, _) -> o.slowChannel?.phase == "RUNNING" && o.decision.smbU > 0.0 }) {
            "SMB, waehrend die Kanal-TBR laeuft\n$bericht"
        }
        // KEIN DOPPELTER ABZUG, DER SMB DIESES ZYKLUS ABGESTIMMT: beim Einstieg traegt der Block 2 x (Bedarf - SMB dieses
        // Zyklus) je Stunde. Fruehere SMB stehen schon im Bedarf (IOB, Transport) und werden nicht noch einmal
        // abgezogen. Je Stufe 1,0 U/h, also Stufe = ganze Zahl davon, hoechstens 4.
        val start = mit.first { it.first.slowChannel?.reason == "START" }.first.slowChannel!!
        assertTrue(mit.indexOfFirst { it.first.slowChannel?.reason == "START" } > 0)
        val erwartet = minOf(4, floor(2.0 * (start.plannedRemainderU + start.demandU!! - start.smbThisCycleU) + 1e-9).toInt())
        assertEquals(erwartet, start.stage) { "Stufe aus Bedarf ${start.demandU}, SMB ${start.smbThisCycleU}\n$bericht" }
        assertTrue(start.smbThisCycleU > 0.0) { "der Einstieg faellt in diesem Verlauf auf einen SMB-Zyklus\n$bericht" }

        val ersterPlan = mit.indexOfFirst { (it.first.slowChannel?.plannedRemainderU ?: 0.0) > 0.0 }
        assertTrue(ersterPlan > 0) { "die geplante TBR steht nie in der Prognose\n$bericht" }
        assertEquals(ohne.take(ersterPlan).map { it.first.decision.smbU }, mit.take(ersterPlan).map { it.first.decision.smbU }) {
            "der Kanal bremst oder verzoegert keinen SMB\n$bericht"
        }
        val ohnePrognose = ohne[ersterPlan].first.decision.predAtReleaseMgdl!!
        val mitPrognose = mit[ersterPlan].first.decision.predAtReleaseMgdl!!
        assertTrue(mitPrognose < ohnePrognose - 0.1) {
            "der geplante Rest senkt die Prognose: ohne $ohnePrognose, mit $mitPrognose\n$bericht"
        }
    }

    /**
     * maxIOB erreicht den Kanal im echten Zyklus: bei 0,3 U Spielraum traegt ein ganzer Block nur noch die Stufe 2/4
     * des eingestellten Zusatzes (0,5 statt 1,0 U/h).
     */
    @Test
    fun `langsamer Kanal - maxIOB kappt die Rate im Runner`(@TempDir dir: File) = runBlocking<Unit> {
        flach = 220.0
        steigungProMin = 0.0
        maxSmbU = 0.0
        maxIobU = 0.3
        langsamerKanal(an = true)
        clock = start
        rigTbr = null
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        val lauf = (0 until 45).map { zyklusMitPumpe() }
        val setz = lauf.firstOrNull { (_, k) -> k != null && k.rateUPerH > 0.0 && k.durationMin > 0 }
        assertNotNull(setz) { kanalBericht(lauf) }
        assertEquals(1.5, setz!!.second!!.rateUPerH, 1e-9) { kanalBericht(lauf) }
        assertEquals("2/4 maxIob", setz.first.slowChannel!!.detail)
    }

    /** Eine harte Sperre erreicht den Kanal im echten Zyklus: bei geschlossenem Pumpentor startet er nie. */
    @Test
    fun `langsamer Kanal - geschlossenes Pumpentor startet nichts`(@TempDir dir: File) = runBlocking<Unit> {
        flach = 220.0
        steigungProMin = 0.0
        maxSmbU = 0.0
        pumpeBelegt = true
        langsamerKanal(an = true)
        clock = start
        rigTbr = null
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        val lauf = (0 until 45).map { zyklusMitPumpe() }
        assertTrue(lauf.none { it.first.slowChannel?.want == true }) { kanalBericht(lauf) }
        assertTrue(lauf.none { it.second != null }) { kanalBericht(lauf) }
        val letzter = lauf.last().first.slowChannel!!
        assertEquals("HARD_STOP", letzter.reason)
        assertEquals("PUMP_GATE_CLOSED", letzter.detail)
    }

    /**
     * AUS UND UNBETEILIGT IST BITGLEICH: derselbe Verlauf ohne Kanal und mit eingeschaltetem, aber nie einsteigendem
     * Kanal (Schwelle ueber dem BG) liefert in jedem Zyklus dieselben Kommandos, Mengen und Gruende. Ausgeschaltet
     * steht nichts vom Kanal im Ergebnis.
     */
    @Test
    fun `langsamer Kanal - aus und unbeteiligt aendert nichts`(@TempDir dir: File) = runBlocking<Unit> {
        fun lauf(an: Boolean, unterordner: String): List<Pair<FuseCycleRunner.Outcome, TbrKommando?>> = runBlocking {
            flach = 220.0
            steigungProMin = 0.0
            maxSmbU = 0.3
            langsamerKanal(an = an, schwelle = 300.0)
            clock = start
            rigTbr = null
            transportReset()
            neuerRunner(FuseLedgerAdapter().also { it.loadOnce(File(dir, unterordner).also(File::mkdirs), "test-epoch", start) })
            (0 until 70).map { zyklusMitPumpe() }
        }
        val aus = lauf(an = false, unterordner = "aus")
        val unbeteiligt = lauf(an = true, unterordner = "an")
        assertTrue(aus.any { it.first.decision.smbU > 0.0 }) { "Vorbedingung: der Normalpfad dosiert\n${kanalBericht(aus)}" }
        assertTrue(aus.all { it.first.slowChannel == null }) { "ausgeschaltet steht nichts vom Kanal im Ergebnis" }
        // Eingeschaltet steht der Block im Ergebnis (ab dem ersten Zyklus mit Hauptpfad), handelt aber nie.
        assertTrue(unbeteiligt.any { it.first.slowChannel != null }) { kanalBericht(unbeteiligt) }
        assertTrue(unbeteiligt.none { it.first.slowChannel?.acted == true }) { kanalBericht(unbeteiligt) }
        for ((a, b) in aus.zip(unbeteiligt)) {
            assertEquals(a.second, b.second) { "Kommando t=${(a.first.computeTs - start) / 60_000L}" }
            assertEquals(a.first.decision, b.first.decision) { "Entscheidung t=${(a.first.computeTs - start) / 60_000L}" }
            assertEquals(a.first.reason, b.first.reason)
        }
    }



    /**
     * Ein zurueckkehrendes Risiko verdraengt die offene Teilbasal-
     * Anforderung: danach darf KEIN Teilbasal-Kommando mehr folgen.
     */
    /**
     * DER ZEITACHSEN-FEHLER ALS TEST. Dieselbe Bahn wie im Kommandostrom-E2E,
     * aber die Sensorzeit liegt 5 s vor der Rechenzeit - wie auf dem Geraet.
     * Vor dem Fix: das Profil-Grid begann an der Rechenzeit, die Suche sah
     * vor dem ersten Slot eine Luecke, INVALID_INPUT in jedem Zyklus, die
     * Stufe wurde nie aktiv. Jetzt muss eine Teilrate den Ausgabepfad
     * erreichen, und kein eintrittsberechtigter Zyklus darf eine
     * Datenluecke melden.
     */
    @Test
    fun `E2E die Teilbasal-Stufe greift auch mit Sensorzeit vor Rechenzeit`(@TempDir dir: File) = runBlocking<Unit> {
        quellVersatzMs = 5_000L
        zeroLatchAn = true
        teilbasalAn = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))
        var setz: TbrKommando? = null
        var aktiv: FuseCycleRunner.Outcome? = null
        val luecken = mutableListOf<String>()
        var berechtigt = 0
        var versatzGesehen = false
        for (i in 0 until 80) {
            val (o, k) = zyklusMitKommando()
            // Der Versatz ist REAL, nicht nur eingestellt: Sensorzeit 5 s
            // vor der Uhr. Ohne diese Zeile koennte der Test den Fall
            // verfehlen, den er beweisen soll.
            if (o.sourceTs == clock - 5_000L) versatzGesehen = true
            if (o.partialRecoveryStreak >= PartialRecoveryGate.ENTRY_CYCLES) {
                berechtigt++
                o.partialRecoverySearchReject?.let { luecken += "${clock / 60_000L}: $it" }
            }
            if (k != null && k.rateUPerH > 0.0 && k.durationMin > 0 && setz == null) setz = k
            if (o.partialRecoveryActive) { aktiv = o; break }
        }
        assertTrue(versatzGesehen) { "die Serie muss tatsaechlich 5 s vor der Uhr liegen" }
        assertTrue(berechtigt > 0) { "der Aufbau muss ueberhaupt eintrittsberechtigte Zyklen erzeugen" }
        assertTrue(luecken.isEmpty()) {
            "kein eintrittsberechtigter Zyklus darf an einer Datenluecke scheitern: $luecken"
        }
        assertNotNull(aktiv) { "die Teilstufe muss trotz Versatz aktiv werden" }
        assertNotNull(setz) { "und eine Teilrate muss den Ausgabepfad erreichen" }
        assertTrue(setz!!.rateUPerH > 0.0)
        assertNull(aktiv!!.partialRecoverySearchReject)
        assertTrue((aktiv.partialRecoverySearchRateUPerH ?: 0.0) > 0.0) {
            "die Suche selbst traegt die Rate: ${aktiv.partialRecoverySearchRateUPerH}"
        }
    }

    /**
     * Fuehrt die Versatz-Bahn bis zur ersten ERFOLGREICHEN Suche (Rate > 0,
     * Begrenzung benannt) und gibt den Outcome dieses Zyklus zurueck.
     */
    private suspend fun bisZurErfolgreichenSuche(dir: File): FuseCycleRunner.Outcome {
        quellVersatzMs = 5_000L
        zeroLatchAn = true
        teilbasalAn = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))
        for (i in 0 until 80) {
            val o = cycle()
            if ((o.partialRecoverySearchRateUPerH ?: 0.0) > 0.0 && o.partialRecoverySearchLimit != null) return o
        }
        throw AssertionError("der Aufbau erreicht keine erfolgreiche Suche")
    }

    /**
     * SEQUENZ 1 (Review-P1): erfolgreiche Suche, dann faellt der Kern aus.
     * Bisher wurden je Zyklus nur Rate und Reject genullt - Begrenzung,
     * bindender Punkt und Basisbahn der VORIGEN Suche standen im naechsten
     * Trailzyklus weiter, als gehoerten sie zu ihm.
     */
    @Test
    fun `Suchdiagnosen der vorigen Suche ueberleben keinen Kernausfall`(@TempDir dir: File) = runBlocking<Unit> {
        val erfolg = bisZurErfolgreichenSuche(dir)
        assertNotNull(erfolg.partialRecoverySearchLimit)
        assertNotNull(erfolg.partialRecoveryBindingOffsetMin)
        assertNotNull(erfolg.partialRecoveryBaselineMinLowerMgdl)

        // Der Kern faellt aus: ein Insulinmodell, dessen IOB NaN liefert.
        kaputterKern = true

        val danach = cycle()
        assertEquals("KERNEL_MISSING", danach.partialRecoverySearchReject)
        assertNull(danach.partialRecoverySearchLimit) { "Begrenzung der vorigen Suche darf nicht stehenbleiben" }
        assertNull(danach.partialRecoveryBindingOffsetMin) { "bindender Punkt der vorigen Suche darf nicht stehenbleiben" }
        assertNull(danach.partialRecoveryBaselineMinLowerMgdl) { "Basisbahn der vorigen Suche darf nicht stehenbleiben" }
        assertEquals(0.0, danach.partialRecoverySearchRateUPerH ?: -1.0, 1e-12)
    }

    /** SEQUENZ 2 (Review-P1): erfolgreiche Suche, dann Schalter aus. */
    @Test
    fun `Suchdiagnosen der vorigen Suche ueberleben kein Ausschalten`(@TempDir dir: File) = runBlocking<Unit> {
        val erfolg = bisZurErfolgreichenSuche(dir)
        // ALLE drei Vorbedingungen - sonst bestuende die Nullpruefung unten
        // fuer einen Wert, der nie gesetzt war, ohne etwas zu beweisen.
        assertNotNull(erfolg.partialRecoverySearchLimit)
        assertNotNull(erfolg.partialRecoveryBindingOffsetMin)
        assertNotNull(erfolg.partialRecoveryBaselineMinLowerMgdl)

        teilbasalAn = false
        val danach = cycle()
        assertNull(danach.partialRecoverySearchRateUPerH) { "Stufe aus: keine Suche, keine Rate - auch keine 0" }
        assertNull(danach.partialRecoverySearchReject)
        assertNull(danach.partialRecoverySearchLimit)
        assertNull(danach.partialRecoveryBindingOffsetMin)
        assertNull(danach.partialRecoveryBaselineMinLowerMgdl)
    }

    /**
     * A) PROFIL BEREITS ERREICHT - DER BEOBACHTETE FEHLER.
     *
     * Alter Latch aktiv, die Suche traegt bis zum vollen Profilbasal,
     * autoritativ KEINE TBR, leerer Besitzzustand, gueltiger
     * Mahlzeitengrant, alle uebrigen Tore offen. Die Teilstufe fordert in
     * diesem Zyklus GAR NICHTS an (`PARTIAL_ALREADY_AT_PROFILE`) - der
     * Grant darf trotzdem nicht genullt werden.
     *
     * Vor dem Fix nullte das Teilstufen-Flag den Bolus, BEVOR feststand,
     * dass es nichts zu tun gibt; die Tabelle setzte danach zusaetzlich
     * den Blockgrund, und `applyBlock` nullte ein zweites Mal.
     */
    /**
     * Die Lage, in der der Fehler entstand: die Teilstufe laeuft SCHON,
     * dann kommt der Marker. Bahn wie im Kommandostrom-E2E (faellt, knickt
     * flach ab) - das erzeugt Latch und Stufe; die Mahlzeitenautorisierung
     * kommt erst danach dazu.
     */
    private fun teilstufeDannMarker(dir: File) {
        zeroLatchAn = true
        teilbasalAn = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        // Konsolidierung 2.0: der Mahlzeitengrant kommt aus dem Erwartungskonto (Anfangsportion mit G1/G2), nicht
        // mehr aus Prime und Fundament.
        kontoMitFruehVersorgung()
        aufschubAn = true
        markerAuthorized = true
        markerAt = 0L                      // noch KEIN Marker
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        for (i in 0 until 60) {
            val o = cycle()
            if (o.partialRecoveryActive) {
                // Jetzt der Markerdruck - wie am Geraet mitten in der Stufe.
                markerAt = clock + taktMs
                return
            }
        }
        // HART, NICHT UEBERSPRUNGEN. Ein `assumeTrue` haette diese feste
        // synthetische Regression stillgelegt, sobald eine spaetere
        // Aenderung den Aufbau nicht mehr in die Teilstufe fuehrt - und
        // genau dann braucht man sie.
        throw AssertionError("der Aufbau MUSS die Teilstufe erreichen")
    }

    /**
     * B) NACH ABBRUCH UND NEUEM MARKER MUSS DIE HUELLE NEU BEWAFFNET
     * WERDEN - innerhalb des Markerfensters, in dem die Fensterregel es
     * bisher verhindert hat (Konsolidierung 2.0: die Kredit-Huelle am Platz
     * des Mahlzeitenfundaments).
     *
     * Der Aufbau setzt den Autorisierungszustand so, wie ihn das
     * Bedienereignis hinterlaesst (Kennung + verbrauchte Widerrufsmarke);
     * die Folge Druck -> Abbruch -> Druck selbst liegt in `FusePlugin`
     * und ist dort durch die reinen Vertragstests abgedeckt.
     */
    @Test
    fun `B ein neuer Marker nach Abbruch bewaffnet die Huelle neu`(@TempDir dir: File) {
        primeHuelleU = 3.75
        markerAuthorized = true
        markerAt = start + 2 * 60_000L
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        repeat(6) { cycle() }
        assertTrue(l.episodes.creditEnvelopeU != null) { "Ausgangslage: die erste Huelle steht" }
        val ersteArmierung = l.episodes.primeArmedTs
        // Etwas ist bereits verbraucht - das darf der Abbruch NICHT loeschen.
        l.episodes.primeSpentU = 1.0

        // ---- Abbruch, dann NEUER Druck im selben Fenster ----------------
        // Genau das, was `toggleMealMarker` hinterlaesst.
        val alt = MarkerReauthorization.Authorization("auth-1", markerAt)
        val marke = MarkerReauthorization.widerrufe(alt, clock)!!
        val neuerDruck = clock + taktMs                       // weit INNERHALB der 90 min
        val neu = MarkerReauthorization.autorisiere(2L, neuerDruck, marke)
        l.episodes.markerAuthSeq = 2L
        l.episodes.markerAuth = neu.auth
        l.episodes.markerRevocation = neu.revocation
        markerAt = neuerDruck

        val vorher = l.episodes.foundationArmedByAuthId
        repeat(4) { cycle() }
        assertTrue(l.episodes.primeArmedTs != ersteArmierung) {
            "die Fensterregel haette hier NICHT neu bewaffnet - genau das war der Fehler"
        }
        assertEquals("auth-2", l.episodes.foundationArmedByAuthId) {
            "die Kennung muss mitgeschrieben sein, sonst greift die Wiederholungssperre nicht"
        }
        assertTrue(l.episodes.creditEnvelopeU != null) { "und die neue Autorisierung steht" }
        assertTrue(vorher != l.episodes.foundationArmedByAuthId)

        // ---- WIEDERHOLUNG: derselbe Druck, weitere Zyklen ---------------
        val nachErster = l.episodes.primeArmedTs
        repeat(8) { cycle() }
        assertEquals(nachErster, l.episodes.primeArmedTs) {
            "dieselbe Autorisierung darf nur EINMAL bewaffnen"
        }
        assertEquals("auth-2", l.episodes.foundationArmedByAuthId)
    }

    /**
     * B) LEDGER UND PREFERENCES GEMEINSAM - der Absturzfall.
     *
     * Der Widerruf wurde geschrieben, dann endete der Prozess VOR dem
     * Leeren der Preference. Dort steht also noch ein Marker, den es nicht
     * mehr gibt. Der Runner darf ihn nicht weiter lesen - sonst waere der
     * Widerruf folgenlos, und die alte Autorisierung liefe weiter.
     */
    @Test
    fun `B ein im Ledger widerrufener Marker wirkt trotz alter Preference nicht mehr`(@TempDir dir: File) {
        markerAuthorized = true
        markerAt = start + 2 * 60_000L
        clock = start
        transportReset()
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        repeat(6) { cycle() }
        assertTrue(l.episodes.creditEnvelopeU != null) { "Ausgangslage: die Autorisierung steht" }

        // Der Absturzzustand: Widerruf im Ledger, Preference unveraendert.
        l.episodes.markerRevocation = MarkerReauthorization.widerrufe(
            MarkerReauthorization.Authorization("auth-1", markerAt), clock,
        )
        l.episodes.markerAuth = null
        val verbrauchtVorher = l.episodes.primeSpentU

        val o = cycle()
        assertEquals(0L, o.state?.markerArmedTs ?: 0L) {
            "der Runner MUSS den widerrufenen Marker als abwesend lesen"
        }
        // UND DIE BUCHHALTUNG BLEIBT: ein Widerruf storniert nichts.
        assertEquals(verbrauchtVorher, l.episodes.primeSpentU, 1e-12) {
            "der Widerruf darf keinen Verbrauch loeschen"
        }
    }

    @Test
    fun `A eine autorisierte Mahlzeitendosis ueberlebt eine Teilstufe ohne Aktion`(@TempDir dir: File) = runBlocking<Unit> {
        quelleMeldet(null)                 // autoritativ KEINE laufende TBR
        teilstufeDannMarker(dir)
        var gesehen: FuseCycleRunner.Outcome? = null
        var grantGesehen = false
        var profilGesehen = false
        var leerGesehen = false
        for (i in 0 until 90) {
            // VOR dem Zyklus - genau die Sicht, mit der der Runner urteilt.
            val leer = besitz().leer && besitz().ending == null
            val o = cycle()
            if (o.decision.markerAuthorizedU > 0.0) grantGesehen = true
            if ((o.reason ?: "").contains("ALREADY_AT_PROFILE")) profilGesehen = true
            if (leer) leerGesehen = true
            // GENAU DER BEOBACHTETE ZUSTAND: Profil erreicht, autoritativ
            // keine TBR, LEERER Besitz - und ein gueltiger Grant. Ein noch
            // offener eigener Vorgang ist ein ANDERER Fall und behaelt
            // seinen Riegel (Gegenfaelle unten).
            if (o.partialRecoveryActive && o.tbr == null && leer &&
                (o.reason ?: "").contains("ALREADY_AT_PROFILE") &&
                o.decision.markerAuthorizedU > 0.0
            ) { gesehen = o; break }
        }
        // Jede Vorbedingung EINZELN und HART - sonst verschwindet der
        // Nachweis lautlos, wenn der Aufbau ihn nicht mehr erreicht.
        assertTrue(grantGesehen) { "der Aufbau MUSS einen Mahlzeitengrant erzeugen" }
        assertTrue(profilGesehen) { "der Aufbau MUSS den Profilzustand erreichen" }
        assertTrue(leerGesehen) { "der Aufbau MUSS einen leeren Besitzzustand erreichen" }
        assertNotNull(gesehen) {
            "der Aufbau MUSS alle drei im SELBEN Zyklus erreichen - das ist der Fall"
        }
        val o = gesehen!!
        assertTrue(o.decision.markerAuthorizedU > 0.0) {
            "der Aufbau muss ueberhaupt einen Mahlzeitengrant erzeugen"
        }
        assertTrue(o.decision.smbU > 0.0) {
            "DER FEHLER: der Grant wurde von einer Teilstufe genullt, die nichts anfordert"
        }
        // DIE FINALE MENGE, nicht die vor dem letzten Riegel.
        // `smbPublishedU` traegt den Stand VOR `applyBlock` - darauf zu
        // pruefen war eine Luecke: die Sperre der Tabelle haette danach
        // noch nullen koennen, und der Test haette es nicht gemerkt.
        assertTrue((o.smbActuatedU ?: 0.0) > 0.0) {
            "die Menge muss die FINALE Ausgabe erreichen (actuated), " +
                "published=${o.smbPublishedU} actuated=${o.smbActuatedU}"
        }
        assertNull(o.smbFinalBlock) { "und kein Riegel darf sie am Ende halten" }
    }

    /**
     * C) SCHUTZGEGENFAELLE - jeder behaelt seinen Riegel.
     *
     * Dieselbe Bahn, aber mit einer ECHT laufenden Schutz-Null: dann ist
     * die Stufe eine echte Aktion, und der Bolus bleibt gesperrt. Ohne
     * diesen Gegenfall waere der Fix eine "Mahlzeit darf immer"-Ausnahme.
     */
    @Test
    fun `C bei laufender Schutz-Null bleibt der Riegel trotz Mahlzeitengrant`(@TempDir dir: File) = runBlocking<Unit> {
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))
        teilstufeDannMarker(dir)
        var mitGrant = 0
        for (i in 0 until 40) {
            val o = cycle()
            if (o.partialRecoveryActive) {
                // NUR die Zyklen zaehlen, in denen ueberhaupt ein Grant
                // vorlag - "nichts abgegeben" ohne Grant beweist nichts.
                if (o.decision.markerAuthorizedU > 0.0) mitGrant++
                assertEquals(0.0, o.smbActuatedU ?: 0.0, 1e-12) {
                    "eine echte Teilstufe neben einer laufenden Null gibt nichts aus"
                }
            }
        }
        assertTrue(mitGrant > 0) {
            "der Gegenfall MUSS Zyklen mit aktiver Teilstufe UND gueltigem Grant erzeugen - " +
                "sonst zeigt er nur, dass ohne Grant nichts fliesst"
        }
    }

    /** C) Unbekannte Pumpensicht ist kein Beweis - der Riegel bleibt. */
    @Test
    fun `C bei unbekannter Pumpensicht bleibt der Riegel`(@TempDir dir: File) = runBlocking<Unit> {
        quelleMeldet(null)
        teilstufeDannMarker(dir)
        var mitGrant = 0
        for (i in 0 until 40) {
            // Ersatzquelle: `tempBasalFallback = true` macht die Sicht UNKNOWN.
            clock += taktMs
            val o = runner.run(true, testPumpe())
            if (o.partialRecoveryActive) {
                if (o.decision.markerAuthorizedU > 0.0) mitGrant++
                assertEquals(0.0, o.smbActuatedU ?: 0.0, 1e-12) {
                    "UNKNOWN traegt die Feststellung nicht - nichts geht hinaus"
                }
            }
        }
        assertTrue(mitGrant > 0) {
            "der Gegenfall MUSS Zyklen mit aktiver Teilstufe UND gueltigem Grant erzeugen"
        }
    }

    @Test
    fun `E2E ein zurueckkehrendes Risiko verdraengt die offene Teilbasal-Anforderung`(@TempDir dir: File) = runBlocking<Unit> {
        zeroLatchAn = true
        teilbasalAn = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))
        var da = false
        for (i in 0 until 80) if (cycle().partialRecoveryActive) { da = true; break }
        assumeTrue(da, "Aufbau erzeugt in diesem Rig keine Teilstufe - Rest ungeprueft")
        assertFalse(besitz().leer)

        steigungNachKnick = -1.2
        val kommandos = (0 until 6).map { zyklusMitKommando().second }
        val teilbasal = kommandos.filterNotNull().filter { it.rateUPerH > 0.0 && it.durationMin > 0 }
        assertTrue(teilbasal.isEmpty()) {
            "nach dem Risiko darf keine Teilrate mehr gesetzt werden: $teilbasal"
        }
        assertFalse(cycle().partialRecoveryActive)
    }

    /**
     * EINE ERSATZQUELLEN-SICHT DARF DEN BESITZ NIE LOESCHEN.
     * `run(tempBasalFallback = true)` ist genau dieser Fall.
     */
    @Test
    fun `E2E eine Ersatzquellen-Sicht loescht den Besitz nicht`(@TempDir dir: File) = runBlocking<Unit> {
        zeroLatchAn = true
        teilbasalAn = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))
        var da = false
        for (i in 0 until 80) if (cycle().partialRecoveryActive) { da = true; break }
        assumeTrue(da, "Aufbau erzeugt in diesem Rig keine Teilstufe - Rest ungeprueft")
        assertFalse(besitz().leer)

        quelleMeldet(null)
        repeat(3) {
            clock += taktMs
            runner.run(true, testPumpe())
        }
        assertFalse(besitz().leer) {
            "eine Ersatzquellen-Sicht ist KEIN Beweis, dass nichts laeuft"
        }
    }

    /** Der Setzversuchsdeckel: kein Kommando-Spam, wenn die Pumpe nie
     *  uebernimmt. */
    @Test
    fun `E2E der Versuchsdeckel begrenzt die Setzkommandos`(@TempDir dir: File) = runBlocking<Unit> {
        zeroLatchAn = true
        teilbasalAn = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))
        var da = false
        for (i in 0 until 80) if (cycle().partialRecoveryActive) { da = true; break }
        assumeTrue(da, "Aufbau erzeugt in diesem Rig keine Teilstufe - Rest ungeprueft")

        // Die Pumpe uebernimmt NIE. 40 Zyklen lang.
        quelleMeldet(null)
        var setz = 0
        repeat(40) {
            val (_, k) = zyklusMitKommando()
            if (k != null && k.rateUPerH > 0.0 && k.durationMin > 0) setz++
        }
        assertTrue(setz <= PartialTbrOwnership.SET_MAX_ATTEMPTS) {
            "hoechstens ${PartialTbrOwnership.SET_MAX_ATTEMPTS} Versuche, gezaehlt: $setz"
        }
        assertTrue(setz < 40) { "und ganz sicher kein Minutentakt" }
    }

    /**
     * DERSELBE P0 DURCH DEN RUNNER: laeuft der Zyklus mit
     * `tempBasalFallback = true`, ist die TBR-Sicht eine Ersatzquelle -
     * dann darf KEINE TBR-Anforderung den Zyklus verlassen, auch wenn die
     * Teilstufe gerade eine Rate wollte.
     */
    @Test
    fun `E2E bei Ersatzquellen-Sicht verlaesst keine TBR-Anforderung den Zyklus`(@TempDir dir: File) = runBlocking<Unit> {
        zeroLatchAn = true
        teilbasalAn = true
        flach = 140.0
        steigungProMin = -1.2
        knickAbMin = 25
        steigungNachKnick = 0.0
        bolusIobU = 2.5
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) })
        quelleMeldet(TB(timestamp = System.currentTimeMillis(), duration = 30 * 60_000L,
                        rate = 0.0, isAbsolute = true, type = TB.Type.NORMAL))

        // Bis kurz VOR den Eintritt fahren, damit die Stufe im naechsten
        // Zyklus eine Rate wollen wuerde.
        var streak = 0
        for (i in 0 until 80) {
            val o = cycle()
            streak = o.partialRecoveryStreak
            if (streak >= PartialRecoveryGate.ENTRY_CYCLES - 1) break
        }
        assumeTrue(streak >= PartialRecoveryGate.ENTRY_CYCLES - 1, "Eintritt kommt in diesem Rig nicht zustande")

        // Jetzt Ersatzquellen-Sicht: die TBR-Sicht sagt nichts.
        var kommandos = 0
        repeat(6) {
            clock += taktMs
            if (runner.run(true, testPumpe()).tbr != null) kommandos++
        }
        assertEquals(0, kommandos) {
            "ohne belastbare Sicht darf keine TBR gestellt werden - C7b kann nicht schuetzen, was es nicht sieht"
        }
        assertTrue(besitz().leer) { "und es wird auch nichts gebucht: ${besitz()}" }
    }

    // ==== DIE LAGE DER REBOUND-EVIDENZ-AUSNAHME (Toni) =======================
    //
    // Konsolidierung 2.0: die Rebound-Evidenz-Ausnahme, ihre Pflichtfaelle, die
    // Erkundungen und die Gefahrenproben sind entfallen. Ihre Lage bleibt - die
    // Rig-Vertraege der Rueckfuehrung unten fahren sie.

    /**
     * Die P1-Lage (Tief bis etwa Minute 11, Anstieg 2,5/min, Guard-Deadlock),
     * aber der Marker wird erst bei Minute 14 gedrueckt - NACH dem Tief.
     * maxSMB 0,05: die eigenen Lifts zehren den Bestand nicht in einem Zug auf,
     * die Lage erreicht PENDING_SEAL bei verbrauchtem Kredit (gemessen: der
     * frische Zufluss steht, der versiegelte Rest ist abgebucht).
     */
    private fun ausnahmeLage(dir: File, an: Boolean = true): FuseLedgerAdapter {
        markerAt = 0L
        val a = reboundOverrideLage(dir)
        livenessAusnahmeAn = an
        maxSmbU = 0.05
        // KORREKT GESTEMPELT (Tonis Review 15.09.): bis Minute 13 ohne Marker
        // laufen, dann auf den naechsten Zyklus druecken - wie produktiv. Die
        // fruehere Fassung stempelte Minute 14 bei Uhr Minute 7; der Marker lag
        // beim Armieren in der Zukunft, das Fundament wurde unautorisiert
        // armiert (s. `Rig-Falle - Zukunftsmarker`).
        markerAt = 0L
        lageVorlauf.clear()
        while (clock < start + 13 * 60_000L) lageVorlauf += cycle()
        markerAt = clock + 60_000L
        return a
    }

    /** Die Zyklen, die `ausnahmeLage` vor dem Druck faehrt (Tief, Rebound-Beginn) - fuer Auswertungen, die sie brauchen. */
    private val lageVorlauf = mutableListOf<FuseCycleRunner.Outcome>()

    // ==== NACHWEISE DRITTE RUNDE (Tonis Review 14.09. spaet) ==================

    /** Rueckfuehrung scharf: Vor-IOB als Vorbolus, dessen Wirkung schon in der Kurve steckt. */
    private fun rueckfuehrungAn(vorIobU: Double = 4.5) {
        abgaben += Abgabe(start - 30 * 60_000L, vorIobU / 0.9, wirktAufReihe = false, isfMgdlPerU = 61.0)
        bolusIobU = null
        rueckfuehrung = true
    }

    /** Die Kurve OHNE jede zurueckgefuehrte Abgabe - der Vergleichswert. */
    private fun reiheOhneAbgaben(untilTs: Long): List<Double> {
        val gemerkt = abgaben.toList()
        abgaben.removeAll { it.wirktAufReihe }
        return series(untilTs).map { it.value }.also { abgaben.clear(); abgaben += gemerkt }
    }

    /**
     * RIG-VERTRAG 1: nur die bestaetigte Abgabe wirkt. Publiziert, aber nie
     * kommandiert, auf 0 gekappt, unklar oder unkorreliert - und erst recht
     * vom Gate entfernt (gescheiterter Persist): keine Behandlung, kein IOB,
     * keine Glukosewirkung.
     */
    @Test
    fun `Rueckfuehrung 1 - nur bestaetigte Abgaben wirken`(@TempDir dir: File) {
        for (ausgang in listOf(Ausgang.NIE_KOMMANDIERT, Ausgang.CONSTRAINT_NULL, Ausgang.UNKLAR, Ausgang.UNKORRELIERT)) {
            val d = File(dir, ausgang.name).also(File::mkdirs)
            abgaben.clear(); boluses = emptyList()
            ausnahmeLage(d)
            maxSmbU = 0.3
            rueckfuehrungAn()
            val iobVorbolus = abgabeWirkung(start + 70 * 60_000L).first
            var publiziert = 0.0
            repeat(60) { transport(d, ausgang = ausgang); publiziert += letzteMengeU ?: 0.0 }
            assertTrue(publiziert > 0.0, "$ausgang: Vorbedingung - es muss publizierte Mengen geben")
            assertTrue(abgaben.none { it.wirktAufReihe }, "$ausgang: keine Rueckfuehrung ohne Bestaetigung")
            assertTrue(boluses.isEmpty(), "$ausgang: keine Behandlung ohne Bestaetigung")
            assertEquals(iobVorbolus, abgabeWirkung(start + 70 * 60_000L).first, 1e-12, "$ausgang: IOB unveraendert")
            assertEquals(reiheOhneAbgaben(clock), series(clock).map { it.value }, "$ausgang: Reihe unveraendert")
        }

        // Gescheiterter Persist: das Gate entfernt die Menge - keine Wirkung.
        val d = File(dir, "persist").also(File::mkdirs)
        abgaben.clear(); boluses = emptyList()
        ausnahmeLage(d)
        maxSmbU = 0.3
        rueckfuehrungAn()
        val blockiert = File(d, "datei-statt-verzeichnis").also { it.writeText("x") }
        var entfernt = 0
        var n = 0
        // Vorlaufende Zyklen versiegeln sauber; genau im ersten Mengenzyklus scheitert der Persist.
        while (entfernt == 0 && n < 60) {
            n++
            val o = transport(d, persistNach = { z -> if (z.decision.smbU > 0.0) File(blockiert, "unter") else d })
            if (o.decision.smbU > 0.0) { entfernt++; assertEquals(null, letzteMengeU, "ohne Siegel keine Ausgabe") }
        }
        assertTrue(entfernt > 0, "Vorbedingung: das Gate muss eine beschlossene Menge entfernt haben")
        assertTrue(abgaben.none { it.wirktAufReihe } && boluses.isEmpty(), "entfernte Mengen wirken nicht")

        // Gegenprobe GESENDET: genau die publizierten Mengen, als Behandlung und Wirkung.
        val g = File(dir, "gesendet").also(File::mkdirs)
        abgaben.clear(); boluses = emptyList()
        ausnahmeLage(g)
        maxSmbU = 0.3
        rueckfuehrungAn()
        val gesendet = mutableListOf<Pair<Long, Double>>()
        repeat(60) { val o = transport(g); letzteMengeU?.let { gesendet += o.computeTs to it } }
        assertTrue(gesendet.isNotEmpty())
        assertEquals(gesendet, abgaben.filter { it.wirktAufReihe }.map { it.ts to it.u })
        assertEquals(gesendet, boluses.map { it.timestamp to it.amount })
        assertTrue(series(clock).last().value < reiheOhneAbgaben(clock).last() - 1e-6, "bestaetigte Abgaben senken die Reihe")
    }

    /**
     * KI-171, REGEL C BEI GEHALTENER WARTESCHLANGE (v4, Produktchat). FUSE publiziert eine SMB, der Loop fuehrt
     * sie wegen `isHeld` (Einstellungs-Import) nicht aus. Danach darf im Ledger KEINE offene SMB-Haftung bleiben -
     * sonst haftete eine nie gegebene Menge bis DIA + 2 h (Phantom, Fehlerklasse KI-171).
     *
     * Die Spur des Loops ist die des v4-Loops (`LoopPluginTest`), gelesen von der echten Lesung des Plugins; Beweis,
     * Gate und Ledger laufen echt.
     */
    @Test
    fun `KI-171 - eine wegen isHeld nicht ausgefuehrte SMB hinterlaesst keine offene Haftung`(@TempDir dir: File) {
        ausnahmeLage(dir)
        maxSmbU = 0.3
        rueckfuehrungAn()
        var offen: String? = null
        var n = 0
        while (offen == null && n < 60) {
            n++
            transport(dir, ausgang = Ausgang.GEHALTEN)
            if ((letzteMengeU ?: 0.0) > 0.0) offen = pPropId
        }
        val id = requireNotNull(offen) { "Vorbedingung: FUSE muss eine SMB publiziert haben" }
        assertTrue(ledger.openTransportItems().any { it.proposalId == id }) { "Vorbedingung: die publizierte SMB haftet" }

        // Der Folgezyklus wertet den Lauf mit gehaltener Warteschlange aus.
        transport(dir)

        assertEquals(QueueRejectReason.BOLUS_IN_QUEUE, letzterGrund, "Regel C muss greifen")
        assertFalse(ledger.hasOpenProposal(id), "die Zeile ist geschlossen")
        assertTrue(ledger.openTransportItems().none { it.proposalId == id }) { "keine offene SMB-Haftung" }
    }

    /** RIG-VERTRAG 2: vergangene Messpunkte bleiben unveraendert - auch bei spaeteren Abgaben mit anderem ISF. */
    @Test
    fun `Rueckfuehrung 2 - vergangene Messpunkte bleiben unveraendert`(@TempDir dir: File) {
        ausnahmeLage(dir)
        maxSmbU = 0.3
        rueckfuehrungAn()
        var n = 0
        while (abgaben.none { it.wirktAufReihe } && n < 60) { transport(dir); n++ }
        assertTrue(abgaben.any { it.wirktAufReihe }, "Vorbedingung: eine bestaetigte Abgabe")
        // STICHTAG HINTER DEN ABGABEN (Mutationsbefund 15.09.): am Abgabezeitpunkt
        // selbst ist die Wirkung noch 0 - ein Stichtag dort enthielte keinen Punkt,
        // den ein ISF-Wechsel veraendern koennte, und die Rueckmutation blieb gruen.
        repeat(15) { transport(dir) }
        val stichtag = clock
        val vorher = series(stichtag).map { it.timestamp to it.value }
        assertTrue(vorher.zip(reiheOhneAbgaben(stichtag)).any { (p, ohne) -> kotlin.math.abs(p.second - ohne) > 0.5 }) {
            "Vorbedingung: die Historie traegt schon eine Wirkung der Abgaben"
        }
        repeat(20) { transport(dir) }
        assertTrue(abgaben.count { it.wirktAufReihe && it.ts <= stichtag } >= 1)
        // DER URSPRUENGLICHE FEHLER: der GLOBALE ISF aendert sich (Profilwechsel).
        // Die historischen Punkte werden neu berechnet und muessen gleich bleiben -
        // eine Rueckmutation auf den globalen ISF macht genau diese Zeile rot.
        val isfVorher = rueckIsf
        rueckIsf = isfVorher / 2.0
        assertEquals(vorher, series(stichtag).map { it.timestamp to it.value }, "Profil-ISF-Wechsel schreibt die Vergangenheit nicht um")
        rueckIsf = isfVorher
        // Eine spaetere Abgabe mit ANDEREM ISF - sie darf die Vergangenheit nicht umschreiben.
        abgaben += Abgabe(clock, 1.0, wirktAufReihe = true, isfMgdlPerU = 30.0)
        assertEquals(vorher, series(stichtag).map { it.timestamp to it.value }, "die Vergangenheit ist fest")
        val spaeter = clock + 30 * 60_000L
        val mitSpaeterer = series(spaeter).last().value
        abgaben.removeAt(abgaben.lastIndex)
        assertTrue(mitSpaeterer < series(spaeter).last().value - 1e-6, "die spaetere Abgabe wirkt nur nach vorn")
    }

    // Konsolidierung 2.0: der buchungsbedingte Ausgang ohne neue Sperre und seine Proben sind entfallen.

    // ---- BITGLEICH-AUFZEICHNUNG (nur mit FUSE_BITGLEICH_OUT) ----------------
    //
    // Schreibt je Szenario die Dosierentscheidungen Zyklus fuer Zyklus. Derselbe
    // Code laeuft gegen den Vorgaengerstand; der Dateivergleich belegt, dass der
    // Stand mit ausgeschaltetem Schalter dieselben Entscheidungen trifft. Ohne
    // die Umgebungsvariable wird nichts ausgefuehrt.

    // Konsolidierung 2.0: der fruehe adaptive MEAL-Bedarf (H8) und seine Proben sind entfallen.

    private fun bitgleichZeile(o: FuseCycleRunner.Outcome): String = listOf(
        (o.computeTs - start) / 1000L, o.abortReason, o.decision.smbU, o.decision.block, o.tbr,
        o.livenessActive, o.livenessStreak, o.livenessDenial, o.livenessExit, o.livenessLiftU,
        o.livenessCandidateU, o.livenessProfileReason, o.livenessReArmUntilTs, o.evidencePhase,
        o.evidenceCreditMgdlPerMin, o.evidenceMayOverrideRebound, o.exposureRequestedSource,
    ).joinToString("|")

    private fun bitgleich(name: String, zyklen: Int, aufbau: () -> Unit) {
        val out = System.getenv("FUSE_BITGLEICH_OUT")
        org.junit.jupiter.api.Assumptions.assumeTrue(out != null)
        aufbau()
        val zeilen = (1..zyklen).map { bitgleichZeile(cycle()) }
        File(out!!).also(File::mkdirs).resolve("$name.txt").writeText(zeilen.joinToString("\n"))
    }

    @Test
    fun `BITGLEICH rebound gedrueckt`(@TempDir dir: File) = bitgleich("rebound_gedrueckt", 60) { reboundOverrideLage(dir) }

    @Test
    fun `BITGLEICH rebound vorgefunden`(@TempDir dir: File) = bitgleich("rebound_vorgefunden", 60) { reboundOverrideLage(dir, druecken = false) }

    @Test
    fun `BITGLEICH rebound mit Fall`(@TempDir dir: File) = bitgleich("rebound_fall", 60) {
        reboundOverrideLage(dir)
        knick2AbMin = 26
        steigungNachKnick2 = -3.0
    }

    @Test
    fun `BITGLEICH liveness`(@TempDir dir: File) = bitgleich("liveness", 45) { livenessLage(dir) }

    @Test
    fun `BITGLEICH mahlzeit mit Kanal`(@TempDir dir: File) = bitgleich("mahlzeit_kanal", 60) {
        mahlzeit(dir)
        livenessAn = true
    }

    /**
     * KUERZEN STATT VERWERFEN: die Endpruefung prueft Stufe fuer
     * Stufe nach unten und nimmt die groesste, die besteht.
     */
    @Test
    fun `groessteBestehendeMenge nimmt die groesste bestehende Stufe unter dem Start`() {
        val gefragt = mutableListOf<Double>()
        val u = FuseCycleRunner.groessteBestehendeMenge(0.40, 0.05) { m -> gefragt += m; m <= 0.30 + 1e-9 }
        assertEquals(0.30, u, 1e-12)
        assertEquals(listOf(0.35, 0.30), gefragt, "von oben, und die verworfene Startmenge selbst nie")
        // Ein einziger Schritt war schon verworfen - darunter gibt es nichts.
        assertEquals(0.0, FuseCycleRunner.groessteBestehendeMenge(0.05, 0.05) { true }, 0.0)
        // Keine Stufe besteht.
        assertEquals(0.0, FuseCycleRunner.groessteBestehendeMenge(0.40, 0.05) { false }, 0.0)
        // Rasterung wie die Freigabe: 0,15 als Double knapp ueber 0,15.
        assertEquals(0.10, FuseCycleRunner.groessteBestehendeMenge(0.15000000000000002, 0.05) { true }, 1e-12)
        // Unbrauchbare Eingaben liefern nichts.
        assertEquals(0.0, FuseCycleRunner.groessteBestehendeMenge(Double.NaN, 0.05) { true }, 0.0)
        assertEquals(0.0, FuseCycleRunner.groessteBestehendeMenge(0.40, 0.0) { true }, 0.0)
    }

    /**
     * KUERZEN STATT VERWERFEN IM ZYKLUS.
     *
     * Die Kappe des Schwanzes rechnet "1 U kostet 1 U Spielraum", die
     * Endpruefung rechnet dieselbe Menge mit ihrer Wirkung bis zum Horizont
     * und ihrer Restwirkung danach. Liegt der ISF hinter dem Horizont tiefer
     * als davor (ein Zeitprofil mit tieferem ISF spaeter am Tag), kostet 1 U
     * dort mehr als 1 U Spielraum. Die zugeschnittene Menge fiel dann durch und
     * wurde GANZ verworfen, obwohl ein kleinerer Schritt dieselbe Pruefung
     * bestand: im Anstieg ein erzwungener Nullzyklus nach dem anderen. Das
     * gilt auch, wenn nicht die Schwanzkappe, sondern eine andere Kappe die
     * Menge zugeschnitten hat - die Endpruefung rechnet sie trotzdem teurer.
     *
     * Erwartet: jede gekuerzte Menge ist ein ganzer Pumpenschritt unter der
     * zugeschnittenen Menge, und der Zyklus gibt ab. Verworfen wird nur noch,
     * wo die zugeschnittene Menge ein einziger Schritt war. Ohne das Kuerzen
     * gibt es keinen einzigen gekuerzten Zyklus.
     */
    @Test
    fun `Schwanz-Endpruefung kuerzt statt zu verwerfen`() = runBlocking<Unit> {
        val startTod = app.aaps.core.utils.MidnightUtils.secondsFromMidnight(start) / 60
        val profil = org.mockito.kotlin.spy(rigProfil)
        org.mockito.kotlin.doAnswer { inv ->
            val min = (inv.getArgument<Int>(0) / 60) % 1440
            val nachStart = ((min - startTod) % 1440 + 1440) % 1440
            // synthetisch: vor dem Horizont hoeher als dahinter
            if (nachStart <= 100) 72.0 else 48.0
        }.whenever(profil).getIsfMgdlTimeFromMidnight(org.mockito.kotlin.any())
        whenever(profileFunction.getProfile()).thenReturn(profil)
        whenever(profileFunction.getProfile(any())).thenReturn(profil)
        tailGuard = true
        maxSmbU = 0.5
        flach = 140.0
        steigungProMin = 1.0
        bolusIobU = 2.5
        corrExpLimit = 3.0
        mealExpLimit = 7.0
        corrRatioCapZ = 0.2
        mealRatioCapZ = 0.35
        mealBgMin = 110.0
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter())
        markerAt = start + 2 * 60_000L

        val outs = (0 until 30).map { cycle() }
        val bericht = outs.withIndex().joinToString("\n") { (i, o) ->
            "$i smb=${o.decision.smbU} block=${o.decision.block} bind=${o.decision.bindingLimit} " +
                "kappen=${o.decision.caps.joinToString { c -> "${c.name}=${c.valueU}" }}"
        }
        fun zugeschnitten(d: FuseController.Decision): Double? =
            d.caps.minOfOrNull { it.valueU }?.let { kotlin.math.floor(it / 0.05 + 1e-9) * 0.05 }

        val gekuerzt = outs.filter { it.decision.bindingLimit.contains("|finalShortened:${FuseCycleRunner.TAIL_VETO}") }
        assertTrue(gekuerzt.isNotEmpty()) {
            "kein Zyklus gekuerzt - die Endpruefung verwirft die Menge wieder ganz\n$bericht"
        }
        gekuerzt.forEach { o ->
            val d = o.decision
            assertEquals(FuseController.Block.NONE, d.block, "der Zyklus gibt ab")
            assertEquals(0.0, d.smbU / 0.05 - kotlin.math.round(d.smbU / 0.05), 1e-6, "ganzer Pumpenschritt: ${d.smbU}")
            // Ohne Kappenliste (Rest-Zaehler-Stufe) gibt es keine Vergleichsmenge.
            val z = zugeschnitten(d) ?: return@forEach
            assertTrue(d.smbU > 0.0 && d.smbU < z - 1e-9) { "ein Schritt UNTER der zugeschnittenen Menge: $d" }
        }
        outs.filter { it.decision.bindingLimit == "finalVerify:${FuseCycleRunner.TAIL_VETO}" }.forEach { o ->
            val z = zugeschnitten(o.decision) ?: return@forEach
            assertTrue(z < 0.10 - 1e-9) { "verworfen, obwohl ein kleinerer Schritt moeglich war: ${o.decision}\n$bericht" }
        }
    }

    // ---- AUFNAHMENACHWEIS DER TRANSPORTMENGE (03.10.2026) -------------------
    //
    // Die Pflicht-Gegenproben der Codex-Pruefung, durch den echten Runner: ein
    // offener, ungebuchter Posten aus dem Vorzyklus, sein Bolusdatensatz, wie
    // AAPS ihn nach der Lieferung fuehrt, und ein IOB, das wie AAPS aus genau
    // diesen Datensaetzen rechnet und vergangene Schluessel zwischenspeichert.

    private val aufnahmeMengeU = 0.30

    /** Die Lage: Posten "vorzyklus" (ungebunden, ungebucht) und sein Bolus. */
    private fun aufnahmeLage(dir: File, mitBolus: Boolean = true, bolusId: Long = 7001L): BS {
        aapsNah = true
        aapsCache.clear()
        vorIobAbfrage = null
        boluses = emptyList()
        flach = 180.0
        steigungProMin = 1.0
        clock = start + 30 * 60_000L
        val l = FuseLedgerAdapter().also { it.loadOnce(dir, "test-epoch", start) }
        neuerRunner(l)
        val entscheidung = clock - 60_000L
        l.onPublished("vorzyklus", aufnahmeMengeU, entscheidung, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
        val bolus = BS(
            id = bolusId, version = 0, timestamp = entscheidung + 10_000L, amount = aufnahmeMengeU, type = BS.Type.SMB,
            ids = app.aaps.core.data.model.IDs(pumpType = PumpType.GENERIC_AAPS, pumpSerial = "vs", temporaryId = 424_242L),
            iCfg = insulin,
        )
        if (mitBolus) boliSetzen(listOf(bolus))
        return bolus
    }

    /** Einmal, an der ersten IOB-Abfrage des Zyklus - also NACH Lesung 1. */
    private fun nachLesung1(aktion: () -> Unit) {
        var offen = true
        vorIobAbfrage = { if (offen) { offen = false; aktion() } }
    }

    private fun bolusIob(o: FuseCycleRunner.Outcome) = o.iobTotal!!.let { it.iob - it.basaliob }

    @Test
    fun `Aufnahmenachweis - die gelieferte SMB zaehlt genau einmal, die Grenzen bleiben`(@TempDir dir: File) {
        // Mit Nachweis.
        aufnahmeLage(File(dir, "mit").also(File::mkdirs))
        val mit = cycle()
        assertNull(mit.abortReason, mit.abortReason)
        val a = mit.transportSicht!!.aufnahme
        assertEquals(1, a.kandidaten)
        assertEquals(mapOf("vorzyklus" to aufnahmeMengeU), a.aufgenommenU)
        assertNull(a.grund)
        assertEquals(0.0, mit.transportSicht!!.modelliertU, 1e-12)

        // Gegenprobe: derselbe Bolus ohne Datensatzkennung ist kein Beleg -
        // die Menge steht dann im IOB UND im Transport.
        aufnahmeLage(File(dir, "ohne").also(File::mkdirs), bolusId = 0L)
        val ohne = cycle()
        assertNull(ohne.abortReason, ohne.abortReason)
        assertEquals(aufnahmeMengeU, ohne.transportSicht!!.modelliertU, 1e-12)

        // In beiden Faellen traegt das IOB die Lieferung - mit Nachweis zaehlt
        // sie also genau einmal, ohne doppelt.
        assertTrue(bolusIob(mit) > 0.25) { "IOB ohne die Lieferung: ${mit.iobTotal}" }
        assertEquals(bolusIob(mit), bolusIob(ohne), 1e-9)

        // Die Grenzen wirken weiter: keine Abgabe ueber maxIOB minus Bestand
        // minus Transport, mit Nachweis wie ohne.
        listOf(mit, ohne).forEach { o ->
            assertTrue(o.decision.smbU <= maxIobU - o.state!!.capIobU - o.transportSicht!!.modelliertU + 1e-9) { o.decision.toString() }
        }
    }

    @Test
    fun `Aufnahmenachweis - nach Lesung 1 ungueltig geworden, der Transport bleibt`(@TempDir dir: File) {
        val bolus = aufnahmeLage(dir)
        nachLesung1 { boliSetzen(listOf(bolus.copy(isValid = false, version = 1))) }
        val o = cycle()
        assertNull(o.abortReason, o.abortReason)
        assertEquals(1, o.transportSicht!!.aufnahme.kandidaten, "Lesung 1 sah ihn noch")
        assertTrue(o.transportSicht!!.aufnahme.aufgenommenU.isEmpty())
        assertEquals("VERAENDERT", o.transportSicht!!.aufnahme.grund)
        assertEquals(aufnahmeMengeU, o.transportSicht!!.modelliertU, 1e-12)
        // Die IOB-Sicht traegt ihn nicht mehr - ohne Transport waere er in KEINER.
        assertTrue(bolusIob(o) < 1e-9) { "${o.iobTotal}" }
    }

    @Test
    fun `Aufnahmenachweis - Menge oder Zeit nach Lesung 1 geaendert, der Transport bleibt`(@TempDir dir: File) {
        listOf<(BS) -> BS>(
            { it.copy(amount = 0.25, version = 1) },
            { it.copy(timestamp = it.timestamp + 20_000L, version = 1) },
        ).forEachIndexed { i, aendern ->
            val bolus = aufnahmeLage(File(dir, "f$i").also(File::mkdirs))
            nachLesung1 { boliSetzen(listOf(aendern(bolus))) }
            val o = cycle()
            assertNull(o.abortReason, o.abortReason)
            assertTrue(o.transportSicht!!.aufnahme.aufgenommenU.isEmpty()) { "Fall $i" }
            assertEquals("VERAENDERT", o.transportSicht!!.aufnahme.grund, "Fall $i")
            assertEquals(aufnahmeMengeU, o.transportSicht!!.modelliertU, 1e-12)
        }
    }

    @Test
    fun `Aufnahmenachweis - ein aelterer Cacheeintrag ohne den Bolus, der Transport bleibt`(@TempDir dir: File) {
        val bolus = aufnahmeLage(dir, mitBolus = false)
        // Ein fremder Schreiber legte den Eintrag am Ankerschluessel ab, BEVOR
        // der Bolus gebucht war, und die Entwertung ist noch nicht gelaufen.
        val anker = clock + taktMs
        aapsCache[anker] = IobTotal(anker).also { it.iob = 0.0; it.basaliob = 0.0; it.activity = 0.0; it.valid = true }
        boliSetzen(listOf(bolus), entwerten = false)
        val o = cycle()
        assertNull(o.abortReason, o.abortReason)
        assertEquals(1, o.transportSicht!!.aufnahme.kandidaten, "Lesung 1 sah ihn")
        assertTrue(o.transportSicht!!.aufnahme.aufgenommenU.isEmpty())
        assertEquals("CACHE_ABWEICHUNG", o.transportSicht!!.aufnahme.grund)
        assertEquals(aufnahmeMengeU, o.transportSicht!!.modelliertU, 1e-12)
    }

    @Test
    fun `Aufnahmenachweis - erst nach Lesung 1 gebucht, der Transport bleibt`(@TempDir dir: File) {
        val bolus = aufnahmeLage(dir, mitBolus = false)
        nachLesung1 { boliSetzen(listOf(bolus)) }
        val o = cycle()
        assertNull(o.abortReason, o.abortReason)
        assertEquals(0, o.transportSicht!!.aufnahme.kandidaten)
        assertEquals(aufnahmeMengeU, o.transportSicht!!.modelliertU, 1e-12)
        // Die IOB-Sicht traegt ihn schon: konservativ doppelt, nie in keiner.
        assertTrue(bolusIob(o) > 0.25) { "${o.iobTotal}" }
    }

    /**
     * DER CODEX-GEGENZEUGE DURCH DEN RUNNER (03.10.2026): gleiche IOB-Summe,
     * andere Aktivitaet. Der Cacheeintrag am Ankerschluessel entstand mit einem
     * anderen Bolus B, so alt wie der Kandidat, ohne den Kandidaten. Noch vor
     * Lesung 1 bekam B einen korrigierten Zeitstempel (60 min frueher), und der
     * Kandidat wurde sichtbar. Die Menge von B ist so gewaehlt, dass die
     * IOB-Summe am Schluessel gleich bleibt - der fruehere Vergleich der Summe
     * allein haette entlastet, obwohl die Bahn eine veraltete Aktivitaet nutzt.
     */
    @Test
    fun `Aufnahmenachweis - gleiche IOB-Summe, andere Aktivitaet im Cache, der Transport bleibt`(@TempDir dir: File) {
        val kandidat = aufnahmeLage(dir, mitBolus = false)
        val anker = clock + taktMs
        val dia = insulin.dia
        fun proEinheit(ts: Long) = insulin.iobCalcForTreatment(BS(timestamp = ts, amount = 1.0, type = BS.Type.SMB, iCfg = insulin), anker)
        val jung = proEinheit(kandidat.timestamp)
        val alt = proEinheit(kandidat.timestamp - 60 * 60_000L)
        val mengeB = aufnahmeMengeU * jung.iobContrib / (jung.iobContrib - alt.iobContrib)
        val gerundet = { x: Double, s: Double -> app.aaps.core.interfaces.utils.Round.roundTo(x, s) }
        // Der Cacheeintrag aus der alten Liste: nur B, so alt wie der Kandidat.
        val cacheIob = gerundet(mengeB * jung.iobContrib, 0.001)
        val cacheAkt = gerundet(mengeB * jung.activityContrib, 0.0001)
        aapsCache[anker] = IobTotal(anker).also { it.iob = cacheIob; it.basaliob = 0.0; it.activity = cacheAkt; it.valid = true }
        // Der aktuelle Stand: B mit korrigiertem Zeitstempel (neue Version), dazu der Kandidat.
        val b = BS(
            id = 7002L, version = 1, timestamp = kandidat.timestamp - 60 * 60_000L, amount = mengeB, type = BS.Type.SMB,
            ids = app.aaps.core.data.model.IDs(pumpType = PumpType.GENERIC_AAPS, pumpSerial = "vs", temporaryId = 424_243L),
            iCfg = insulin,
        )
        boliSetzen(listOf(b, kandidat), entwerten = false)
        // Vorbedingung des Gegenzeugen: Summe gleich, Aktivitaet nicht.
        val frischIob = gerundet(mengeB * alt.iobContrib + aufnahmeMengeU * jung.iobContrib, 0.001)
        val frischAkt = gerundet(mengeB * alt.activityContrib + aufnahmeMengeU * jung.activityContrib, 0.0001)
        assertEquals(cacheIob, frischIob, 1e-9) { "die IOB-Summen muessen gleich sein" }
        assertTrue(kotlin.math.abs(cacheAkt - frischAkt) >= 0.0002) { "Aktivitaet $cacheAkt gegen $frischAkt" }

        val o = cycle()
        assertNull(o.abortReason, o.abortReason)
        val s = o.transportSicht!!
        assertEquals(1, s.aufnahme.kandidaten, "Lesung 1 sah den Kandidaten")
        assertTrue(s.aufnahme.aufgenommenU.isEmpty()) { "${s.aufnahme}" }
        assertEquals("CACHE_ABWEICHUNG", s.aufnahme.grund)
        assertEquals(aufnahmeMengeU, s.modelliertU, 1e-12)
    }

    /**
     * DIE NACHRECHNUNG TRIFFT AUCH DEN BASALANTEIL: mit einer (synthetischen)
     * laufenden Basalabweichung wird weiter genau einmal gezaehlt. Fehlte der
     * Basalanteil in der Nachrechnung, wiche jeder cachefaehige Wert ab, und
     * der Nachweis entlastete nie.
     */
    @Test
    fun `Aufnahmenachweis - mit Basalanteil wird weiter genau einmal gezaehlt`(@TempDir dir: File) {
        aapsNahBasal = { t -> IobTotal(t).also { it.basaliob = -0.05; it.activity = -0.0003 } }
        aufnahmeLage(dir)
        val o = cycle()
        assertNull(o.abortReason, o.abortReason)
        assertEquals(-0.05, o.iobTotal!!.basaliob, 1e-9)
        val s = o.transportSicht!!
        assertEquals(mapOf("vorzyklus" to aufnahmeMengeU), s.aufnahme.aufgenommenU)
        assertNull(s.aufnahme.grund)
        assertEquals(0.0, s.modelliertU, 1e-12)
    }

    /**
     * CODEX-NACHPRUEFUNG DURCH DEN RUNNER (03.10.2026): ein verlaengerter Bolus E
     * im Fenster. Der alte Cacheeintrag am Ankerschluessel enthaelt nur E, und E
     * wirkt dort genau wie die neue SMB A (AAPS zerlegt E in Anteile wie eine
     * SMB). Die Nachrechnung kennt E nicht und enthaelt A: alle drei Groessen
     * passen, die vollstaendige Rechnung haette aber A UND E. Ohne Schutz wuerde
     * A entlastet, obwohl der benutzte Cachewert A nicht enthaelt.
     */
    @Test
    fun `Aufnahmenachweis - verlaengerter Bolus im Fenster, der Transport bleibt`(@TempDir dir: File) {
        val a = aufnahmeLage(dir, mitBolus = false)
        val anker = clock + taktMs
        // Der alte Cacheeintrag: zahlenmaessig genau der Beitrag von A - so wirkt E dort.
        val r = insulin.iobCalcForTreatment(a, anker)
        aapsCache[anker] = IobTotal(anker).also {
            it.iob = app.aaps.core.interfaces.utils.Round.roundTo(r.iobContrib, 0.001)
            it.activity = app.aaps.core.interfaces.utils.Round.roundTo(r.activityContrib, 0.0001)
            it.valid = true
        }
        verlaengerteBoli = listOf(
            app.aaps.core.data.model.EB(timestamp = a.timestamp - 30_000L, duration = 60_000L, amount = aufnahmeMengeU),
        )
        boliSetzen(listOf(a), entwerten = false)

        val o = cycle()
        assertNull(o.abortReason, o.abortReason)
        val s = o.transportSicht!!
        assertEquals(1, s.aufnahme.kandidaten, "Lesung 1 sah den Kandidaten")
        assertTrue(s.aufnahme.aufgenommenU.isEmpty()) { "${s.aufnahme}" }
        assertEquals("NACHRECHNUNG_UNVOLLSTAENDIG", s.aufnahme.grund)
        assertEquals(aufnahmeMengeU, s.modelliertU, 1e-12)
    }

    // ==== KI-210: Versuchsdeckel des Null-Abbruchs ueber den Runner ========
    //
    // Bis hierher lief der Null-Ende-Pfad in keinem Runner-Test: das Rig liess
    // "Null-Basal sofort beenden" aus. Der Policy-Test (TbrEndZeroTest) prueft
    // den Deckel ohne Zaehler - der Zaehler lebt im Runner, und genau dort sass
    // der Fehler (Backoff-Zyklus setzte zurueck, belegte Pumpe zaehlte mit).

    /** Nachtfenster (Rig-Uhr) mit Nachtband, flacher BG unter Ziel + Band,
     *  keine Mahlzeit, Schalter an, und eine laufende Null, die die Quelle
     *  unveraendert weiter meldet: die Pumpe nimmt den Abbruch nicht an. */
    private suspend fun nullOhneAnnahme(dir: File) {
        whenever(preferences.get(FuseBooleanKey.TbrEndZeroWhenReasonGone)).thenReturn(true)
        nightDeadband = true
        flach = 110.0
        steigungProMin = 0.0
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start
        quelleMeldet(laufendSeit(0.0, vorMin = 1))
    }

    private fun istNullAbbruch(o: FuseCycleRunner.Outcome) = o.abortReason == null && o.tbr != null &&
        (o.reason ?: "").contains(app.aaps.fuse.core.controller.TbrPolicy.END_ZERO_REASON)

    private fun istNullBackoff(o: FuseCycleRunner.Outcome) = o.abortReason == null && o.tbr == null &&
        (o.reason ?: "").contains(app.aaps.fuse.core.controller.TbrPolicy.END_ZERO_BACKOFF_REASON)

    private fun volleZyklen(n: Int) = (1..n).map { cycle() }.filter { it.abortReason == null }

    /** KI-210, Gegenprobe 1: nach ausgeschoepften Versuchen bleibt die Pause
     *  bestehen - kein erneuter Abbruch, solange dieselbe Null laeuft. */
    @Test
    fun `KI-210 nach ausgeschoepften Abbruchversuchen bleibt die Null stehen`(@TempDir dir: File) = runBlocking<Unit> {
        nullOhneAnnahme(dir)
        val voll = volleZyklen(90)
        assertTrue(voll.size >= 20) { "zu wenige volle Zyklen: ${voll.size}" }
        val abbrueche = voll.count { istNullAbbruch(it) }
        assertTrue(abbrueche > 0) {
            "Vorbedingung: der Null-Ende-Pfad muss greifen, Gruende ${voll.map { it.reason }.distinct()}"
        }
        assertEquals(3, abbrueche) { "genau der Versuchsdeckel, Gruende ${voll.map { it.reason }}" }
        val nachDemDritten = voll.drop(voll.indexOfLast { istNullAbbruch(it) } + 1)
        assertTrue(nachDemDritten.size >= 5) { "zu kurze Beobachtung nach dem Deckel: ${nachDemDritten.size}" }
        assertTrue(nachDemDritten.all { istNullBackoff(it) }) {
            "nach dem Deckel nur noch Backoff, Gruende ${nachDemDritten.map { it.reason }}"
        }
    }

    /** KI-210, Gegenprobe 2: eine belegte Pumpe ist kein ausgefuehrter Versuch,
     *  auch wenn ihr Grundtext den Null-Abbruch traegt. */
    @Test
    fun `KI-210 eine belegte Pumpe zaehlt nicht als Abbruchversuch`(@TempDir dir: File) = runBlocking<Unit> {
        nullOhneAnnahme(dir)
        whenever(commandQueue.bolusInQueue()).thenReturn(true)
        val belegt = volleZyklen(90)
        val busyAbbruch = belegt.filter {
            (it.reason ?: "").startsWith("PUMP_BUSY|") &&
                (it.reason ?: "").contains(app.aaps.fuse.core.controller.TbrPolicy.END_ZERO_REASON)
        }
        assertTrue(busyAbbruch.size >= 4) { "Vorbedingung: belegte Zyklen mit Abbruchgrund, ${belegt.map { it.reason }}" }
        assertTrue(belegt.none { it.tbr != null }) { "bei belegter Pumpe geht kein Kommando hinaus" }
        assertTrue(belegt.none { (it.reason ?: "").contains(app.aaps.fuse.core.controller.TbrPolicy.END_ZERO_BACKOFF_REASON) }) {
            "belegte Zyklen duerfen den Deckel nicht fuellen: ${belegt.map { it.reason }}"
        }
        whenever(commandQueue.bolusInQueue()).thenReturn(false)
        val frei = volleZyklen(30)
        assertEquals(3, frei.count { istNullAbbruch(it) }) { "danach die vollen Versuche, ${frei.map { it.reason }}" }
        assertTrue(frei.drop(frei.indexOfLast { istNullAbbruch(it) } + 1).all { istNullBackoff(it) })
    }

    /** KI-210, Gegenprobe 3: ist die Null wirklich vorbei, beginnt eine neue
     *  Null wieder mit vollen Versuchen - der Deckel haelt nicht ueber sie hinaus. */
    @Test
    fun `KI-210 nach beendeter Null beginnt eine neue Versuchsreihe`(@TempDir dir: File) = runBlocking<Unit> {
        nullOhneAnnahme(dir)
        val erste = volleZyklen(90)
        assertEquals(3, erste.count { istNullAbbruch(it) })
        assertTrue(istNullBackoff(erste.last())) { "erste Null steht im Backoff: ${erste.last().reason}" }
        quelleMeldet(null)
        val ohne = volleZyklen(4)
        assertTrue(ohne.isNotEmpty() && ohne.none { istNullAbbruch(it) || istNullBackoff(it) }) {
            "ohne laufende Null weder Abbruch noch Backoff: ${ohne.map { it.reason }}"
        }
        quelleMeldet(laufendSeit(0.0, vorMin = 1))
        val zweite = volleZyklen(30)
        assertEquals(3, zweite.count { istNullAbbruch(it) }) { "neue Null, neue Reihe: ${zweite.map { it.reason }}" }
    }

    // ---- Stufe 2: Ruhe-Parameter im Produktionspfad -------------------------
    //
    // Konsolidierung 2.0: der Ruhe-Ausgang ist samt seinen Einstellungen entfallen; der Trail fuehrt
    // `recoveryRequired` neutral (0).

    @Test
    fun `Stufe 2 Ruhe-Ausgang ist entfallen - recoveryRequired steht neutral`(@TempDir dir: File) {
        flach = 110.0
        steigungProMin = 0.0
        val l = FuseLedgerAdapter().also { it.loadOnce(dir.also(File::mkdirs), "test-epoch", start) }
        neuerRunner(l)
        clock = start
        val lauf = volleZyklen(40).map { it.upfrontChain?.recoveryRequired }
        assertTrue(lauf.size >= 10) { "zu wenige volle Zyklen (${lauf.size})" }
        assertTrue(lauf.all { it == 0 }) { "$lauf" }
    }
}

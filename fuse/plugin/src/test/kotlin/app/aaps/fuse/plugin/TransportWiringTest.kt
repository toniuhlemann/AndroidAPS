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
import app.aaps.fuse.core.controller.OnsetChannel
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
    private var livenessCapPct = 50.0
    private var livenessBgMin = 160.0
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

    /** Der Marker autorisiert Insulin bei gemessenem Tief. */
    private var markerAuthorized = false

    /** Das Mahlzeitenfundament - im Test steuerbar, produktiv per Default aus. */
    private var fundamentAn = false
    private var fundamentAnteil = 0.75
    private var fundamentEndeMin = 60

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
        generateSequence(start) { it + 60_000L }
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
        it.iob = bolusIobU ?: 0.0; it.basaliob = 0.0
        it.activity = aktivitaet; it.valid = iobGueltig
    }

    /** Ungueltige IOB-Daten -> keine Aktivitaet -> ACTIVITY_MISSING, das
     *  Signal ist nicht READY. Der Hebel fuer den Nullfall
     *  "ungesundes Signal"; Default haelt das bisherige Verhalten. */
    /** Der Guard-Boden [mg/dl]. Als Variable, damit der TAIL-Lauf den
     *  Guard ausdruecklich OEFFNEN kann - sonst binden beide Grenzen und
     *  die Ursache ist nicht zuordenbar. */
    private var guardBodenMgdl = 70.0

    private var iobGueltig = true
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

    private fun neuerRunner(l: FuseLedgerAdapter, evidenz: EvidenceStock.Config = EvidenceStock.Config()) {
        ledger = l
        runner = FuseCycleRunner(
            iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
            preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "test-epoch", { markerPress },
            evidenceConfig = evidenz,
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
        whenever(preferences.get(FuseDoubleKey.RiseRampLowR)).thenReturn(0.5)
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
        whenever(preferences.get(FuseDoubleKey.LivenessIobCapPercent)).thenAnswer { livenessCapPct }
        whenever(preferences.get(FuseDoubleKey.LivenessBgMinMgdl)).thenAnswer { livenessBgMin }
        whenever(preferences.get(FuseIntKey.LivenessReArmMin)).thenAnswer { livenessReArmMin }
        whenever(preferences.get(FuseIntKey.AbsorptionCreditWindowMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.MarkerBoostMaxMin)).thenReturn(45)
        whenever(preferences.get(FuseIntKey.NightStartMin)).thenReturn(1380)
        whenever(preferences.get(FuseIntKey.NightEndMin)).thenReturn(480)
        whenever(preferences.get(FuseDoubleKey.NightDeadbandMgdl)).thenReturn(45.0)
        whenever(preferences.get(FuseBooleanKey.NightDeadbandEnabled)).thenAnswer { nightDeadband }
        whenever(preferences.get(FuseDoubleKey.ReboundDeadbandMgdl)).thenReturn(25.0)
        whenever(preferences.get(FuseBooleanKey.ReboundDeadbandEnabled)).thenReturn(true)
        whenever(preferences.get(FuseIntKey.DriveLowerQuantilePct)).thenAnswer { quantilePct }
        whenever(preferences.get(FuseBooleanKey.TailGuardEnabled)).thenAnswer { tailGuard }
        whenever(preferences.get(FuseBooleanKey.ConditionalTailEnabled)).thenAnswer { conditionalTail }
        whenever(preferences.get(FuseBooleanKey.MarkerAuthorisesRelease)).thenAnswer { markerAuthorized }
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
        whenever(preferences.get(FuseIntKey.MealFoundationEndMin)).thenAnswer { fundamentEndeMin }
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(-1L)
        whenever(preferences.get(FuseLongKey.MealMarkerStamp)).thenReturn(0L)
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenAnswer { markerAt }
    }

    private fun cycle(): FuseCycleRunner.Outcome {
        clock += 60_000L
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
    private fun testPumpe() = FuseActivePump(
        "GENERIC_AAPS", virtualPump = true, bolusStepU = 0.05, basalStepUPerH = 0.05,
        gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "TestPump"),
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
        livenessCapPct = 90.0
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
        livenessCapPct = 90.0
        var bindung = ""
        var menge = -1.0
        repeat(25) { val o = cycle(); if (o.livenessLiftU > 0.0) { bindung = o.decision.bindingLimit; menge = o.decision.smbU } }
        assertEquals("liveness:globalIobTh", bindung, "das globale iobTH MUSS den Kanal binden")
        assertTrue(menge in 0.05..0.10 + 1e-9, "und die Menge traegt die Grenze: $menge U")

        iobThPct = 100
        livenessCapPct = 50.0
        clock = start
        transportReset()
        neuerRunner(FuseLedgerAdapter().also { it.loadOnce(File(dir, "deckel").also(File::mkdirs), "test-epoch", start) })
        bindung = ""
        menge = -1.0
        repeat(25) { val o = cycle(); if (o.livenessLiftU > 0.0) { bindung = o.decision.bindingLimit; menge = o.decision.smbU } }
        assertEquals("liveness:livenessCap", bindung, "der eigene Kanaldeckel MUSS benannt binden")
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
     * Gegenprobe 5 - die MINIMALE P2-Scheinwende: der Drive knickt um nur
     * 0,15 mg/dl/min (1,4 -> 1,25), Druck und Anstieg bleiben klar
     * erhalten. Der magnitudenblinde P2-Exit beendet den Lauf trotzdem -
     * das ist die DOKUMENTIERTE konservative Kante des Vertrags: eine
     * Scheinwende kostet Verfuegbarkeit (Sperre), nie Sicherheit. Im
     * rauschfreien Rig konvergiert der Drive danach asymptotisch weiter
     * fallend, jede Wiederbewaffnung endet sofort wieder per P2 - deshalb
     * ist hier KEIN spaeterer Hub zu erwarten (live bricht Messrauschen
     * die strenge Monotonie): P2 wirkt symmetrisch auch als
     * Bewaffnungssperre (TURN_STANDING) - ohne sie lieferte jeder
     * Wiederanlauf genau einen Hub, bevor der Exit ihn wieder beendet
     * (im Rig gesehen).
     */
    @Test
    fun `Liveness Gegenprobe - die minimale P2-Scheinwende beendet den Lauf`(@TempDir dir: File) {
        livenessLage(dir)
        knick2AbMin = 24
        steigungNachKnick2 = 1.25
        var exit: String? = null
        var exitMin = -1
        var liftVor = 0
        var liftNachExit = 0
        var turnStanding = false
        repeat(40) { i ->
            val minute = i + 1
            val o = cycle()
            if (exitMin < 0 && o.livenessLiftU > 0.0) liftVor++
            if (exitMin > 0 && o.livenessLiftU > 0.0) liftNachExit++
            if (exitMin < 0 && o.livenessExit != null) { exit = o.livenessExit; exitMin = minute }
            if (exitMin > 0 && o.livenessDenial == "TURN_STANDING") turnStanding = true
        }
        assertTrue(liftVor >= 3, "vor der Scheinwende muss der Kanal geliefert haben: $liftVor")
        assertEquals("TURN_EXIT", exit, "auch die minimale Scheinwende beendet den Lauf per P2")
        // Obergrenze 30, nicht 31: TURN_STANDING ist erst nach Ablauf der
        // 10-min-Sperre beobachtbar und braucht Platz im 40er-Budget.
        assertTrue(exitMin in 25..30, "kurz hinter dem Knick: Minute $exitMin")
        assertEquals(0, liftNachExit, "kein einziger Hub in die stehende Wende")
        assertTrue(turnStanding, "die Bewaffnungssperre TURN_STANDING muss im Trail stehen")
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
        livenessCapPct = 85.0
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

}

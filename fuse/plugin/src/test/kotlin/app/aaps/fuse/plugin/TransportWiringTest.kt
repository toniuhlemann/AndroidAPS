package app.aaps.fuse.plugin

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
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.predictor.PredictorReason
import app.aaps.fuse.core.predictor.PredictorOutcome
import app.aaps.fuse.core.predictor.TrajectoryCore
import app.aaps.fuse.core.controller.EvidenceStock
import app.aaps.fuse.core.controller.OnsetChannel
import org.junit.jupiter.api.Assertions.assertEquals
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

    /** Hoehe der flachen Rohreihe - niedrig heisst "kein Bedarf". */
    private var flach = 180.0

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

    /** Insulinaktivitaet je Punkt. 0 heisst: der Bolus-Deckungs-Abschlag ist
     *  null, und damit ist die Bremsbahn-Untergrenze IHR EIGENES Mittel -
     *  auch dort passt dann keine Hebung hinein. */
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
    private var markerAt = 0L

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

    private fun series(untilTs: Long): List<GV> =
        generateSequence(start) { it + 60_000L }
            .takeWhile { it <= untilTs }
            .map { ts ->
                val v = flach + steigungProMin * ((ts - start) / 60_000.0)
                GV(
                    timestamp = ts, value = v, raw = v, noise = 0.0,
                    sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT
                )
            }
            .toList()

    private fun iob(atTs: Long) = IobTotal(roundUp(atTs)).also {
        it.iob = 0.0; it.basaliob = 0.0; it.activity = aktivitaet; it.valid = true
    }

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
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenReturn(null)

        stubPolicy()
        neuerRunner(FuseLedgerAdapter())
    }

    private fun neuerRunner(l: FuseLedgerAdapter) {
        ledger = l
        runner = FuseCycleRunner(
            iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
            preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "test-epoch",
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
        whenever(preferences.get(FuseDoubleKey.MaxSmbU)).thenReturn(0.3)
        whenever(preferences.get(FuseDoubleKey.GuardFloorMgdl)).thenReturn(70.0)
        whenever(preferences.get(FuseIntKey.IobThPercent)).thenAnswer { iobThPct }
        whenever(preferences.get(FuseIntKey.ReleaseHorizonMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.LiabilityHorizonMin)).thenReturn(120)
        whenever(preferences.get(FuseIntKey.DriveTauMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.AbsorptionCreditWindowMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.MarkerBoostMaxMin)).thenReturn(45)
        whenever(preferences.get(FuseIntKey.NightStartMin)).thenReturn(1380)
        whenever(preferences.get(FuseIntKey.NightEndMin)).thenReturn(480)
        whenever(preferences.get(FuseDoubleKey.NightDeadbandMgdl)).thenReturn(45.0)
        whenever(preferences.get(FuseBooleanKey.NightDeadbandEnabled)).thenReturn(false)
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
        whenever(preferences.get(FuseDoubleKey.PrimeEnvelopeU)).thenReturn(1.2)
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

        // (4) Der Schutz laeuft daneben weiter.
        assertEquals(FuseController.TbrAction.ZERO_TEMP, o.decision.tbr, "die Absenkung darf nicht wegfallen")
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
        flach = 62.0
        steigungProMin = 0.0
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

        // (5) Der Schutz laeuft daneben weiter.
        assertEquals(FuseController.TbrAction.ZERO_TEMP, o.decision.tbr)
        assertTrue(o.reason.contains("SAFETY_ZERO"), "die Basalabsenkung muss laufen: ${o.reason}")
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
     * DER ENTSCHEIDENDE TEST - und er verlangt FUENF Dinge GLEICHZEITIG.
     *
     * Vorgeschichte: `MarkerAuthorisesRelease` oeffnete vier Tore und war trotzdem
     * wirkungslos, weil ein FUENFTES die Menge nullte - der Schutz-Nullstrom im
     * Translator, der bei Tief zwangslaeufig mitkommt. Gemessen am Geraet:
     * `block=NONE bind=primeRelease prime=true/PRIME floor=0.10 smb=0.0`.
     *
     * Der Vorgaengertest blieb dabei GRUEN, weil er nur EINE Bedingung prueft
     * (kein Hold bei BG 200) - die war auch mit dem Fehler erfuellt. Deshalb
     * hier alles auf einmal, im ECHTEN Zyklus:
     *
     *  1. es liegt wirklich ein GEMESSENES Tief vor,
     *  2. das ENDGUELTIGE SMB - nach dem Translator - ist > 0,
     *  3. der Schutz-Nullstrom laeuft daneben WEITER,
     *  4. die Menge ist hoechstens der markerfinanzierte Anteil,
     *  5. und ihre Herkunft ist TYPISIERT, nicht aus `bindingLimit` geraten.
     *
     * WARUM DER SCHWANZ-WAECHTER HIER AUS IST, und das ist ein Befund und kein
     * Testkniff: bei BG 62 ist der Schwanz-Headroom <= 0, und er nullt die
     * Menge unabhaengig von allem hier. Das ist eine EIGENE Grenze - Haftung
     * ueber H, nicht Tiefschutz - und Tonis Entscheidung vom 11.08. hat sie
     * nicht geoeffnet. Sie steht als eigener Test unten.
     */
    @Test
    fun `Marker autorisiert Insulin am gemessenen Tief - ganze Kette`() {
        tailGuard = false
        flach = 62.0
        steigungProMin = 0.0
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        var frei: FuseCycleRunner.Outcome? = null
        repeat(12) { if (frei == null) cycle().let { o -> if (o.decision.smbU > 0.0) frei = o } }
        val o = frei ?: throw AssertionError("keine Freigabe am Tief - die Autorisierung ist wirkungslos")

        // (1) GEMESSENES Tief, nicht bloss ein prognostiziertes.
        assertTrue(o.state?.safetyHold == true, "ohne gemessenes Tief prueft dieser Test nichts")
        assertTrue(o.bgMgdl!! < 70.0, "BG muss wirklich tief sein: ${o.bgMgdl}")

        // (2)/(5) die Menge ist da UND ihre Herkunft steht als Zahl im Datensatz.
        assertTrue(o.decision.smbU > 0.0)
        assertTrue(
            o.decision.markerAuthorizedU > 0.0,
            "die Herkunft muss TYPISIERT sein, nicht aus dem Grundtext geraten",
        )

        // (4) NUR der autorisierte Anteil kommt durch.
        assertTrue(
            o.decision.smbU <= o.decision.markerAuthorizedU + 1e-9,
            "es darf nur der autorisierte Anteil durchkommen: ${o.decision.smbU}",
        )
        // DIE HERKUNFT, und sie hat ZWEI zulaessige Formen. Ohne Widerspruch
        // des Modells steht dort `primeRelease`. Widerspricht das Modell und
        // die Autorisierung traegt sie trotzdem, steht dort
        // `markerAuth|finalVerify:GUARD_FLOOR` - und das ist kein Makel,
        // sondern die ehrlichere Zeile: sie sagt, WAS ueberstimmt wurde.
        assertTrue(
            o.decision.bindingLimit == "primeRelease" ||
                o.decision.bindingLimit.startsWith("markerAuth|"),
            "die Menge muss als markerfinanziert erkennbar sein: ${o.decision.bindingLimit}",
        )

        // (3) DER SCHUTZ BLEIBT. Es wird nicht "LOW abgeschaltet" - die
        // Basalabsenkung laeuft parallel weiter, und der Grund sagt genau das.
        // Beides zugleich ist kein Widerspruch: das eine ist die erklaerte
        // Mahlzeit, das andere der Schutz gegen ihr Ausbleiben.
        assertEquals(FuseController.TbrAction.ZERO_TEMP, o.decision.tbr, "die Basalabsenkung geht verloren")
        assertTrue(o.reason.contains("SAFETY_ZERO"), "der Schutz-Nullstrom muss laufen: ${o.reason}")
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
        flach = 62.0
        steigungProMin = 0.0
        markerAt = start + 2 * 60_000L
        markerAuthorized = true

        clock = start
        var frei: FuseCycleRunner.Outcome? = null
        repeat(12) { if (frei == null) cycle().let { o -> if (o.decision.smbU > 0.0) frei = o } }
        val o = frei ?: throw AssertionError("der Schwanz nullt den autorisierten Anteil immer noch")

        assertTrue(o.state?.safetyHold == true, "der Aufbau muss dieselbe Tieflage erzeugen wie oben")
        assertTrue(o.decision.markerAuthorizedU > 0.0, "ohne Autorisierung prueft das nichts")
        // UND NICHT MEHR ALS DAS. Der Boden hebt auf die Grenze, nicht darueber -
        // sonst waere aus einem Boden ein Freibrief geworden.
        assertEquals(
            o.decision.markerAuthorizedU, o.decision.smbU, 1e-9,
            "genau der autorisierte Anteil, kein Zuschlag",
        )
        assertEquals(FuseController.TbrAction.ZERO_TEMP, o.decision.tbr, "der Schutz laeuft weiter")
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
}

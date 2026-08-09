package app.aaps.fuse.plugin

import app.aaps.core.data.model.GV
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.kotlin.any
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

    /** Zeitstempel eines Mahlzeiten-Markers, 0 = keiner. */
    private var markerAt = 0L

    private fun series(untilTs: Long): List<GV> =
        generateSequence(start) { it + 60_000L }
            .takeWhile { it <= untilTs }
            .map { ts ->
                GV(
                    timestamp = ts, value = flach, raw = flach, noise = 0.0,
                    sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT
                )
            }
            .toList()

    private fun iob(atTs: Long) = IobTotal(roundUp(atTs)).also {
        it.iob = 0.0; it.basaliob = 0.0; it.activity = 0.0; it.valid = true
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
            preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "test-epoch"
        )
    }

    private fun stubPolicy() {
        whenever(preferences.get(FuseDoubleKey.SmbRatio)).thenReturn(0.15)
        whenever(preferences.get(FuseDoubleKey.SmbRatioRise)).thenReturn(0.35)
        whenever(preferences.get(DoubleKey.ApsSmbMaxIob)).thenAnswer { maxIobU }
        whenever(preferences.get(FuseDoubleKey.RiseRampLowR)).thenReturn(0.5)
        whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenReturn(2.0)
        whenever(preferences.get(FuseDoubleKey.MaxSmbU)).thenReturn(0.3)
        whenever(preferences.get(FuseDoubleKey.GuardFloorMgdl)).thenReturn(70.0)
        whenever(preferences.get(FuseIntKey.IobThPercent)).thenReturn(100)
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
        whenever(preferences.get(FuseIntKey.DriveLowerQuantilePct)).thenReturn(50)
        whenever(preferences.get(FuseBooleanKey.TailGuardEnabled)).thenAnswer { tailGuard }
        whenever(preferences.get(FuseDoubleKey.TailFloorMgdl)).thenReturn(70.0)
        whenever(preferences.get(FuseDoubleKey.TailRecoveryU)).thenReturn(0.0)
        whenever(preferences.get(FuseBooleanKey.FastRestraintEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.BolusShareLambda)).thenReturn(1.0)
        whenever(preferences.get(FuseBooleanKey.OnsetChannelEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.OnsetEnvelopeU)).thenReturn(1.5)
        whenever(preferences.get(FuseBooleanKey.PrimeReleaseEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.PrimeEnvelopeU)).thenReturn(1.2)
        whenever(preferences.get(FuseDoubleKey.PrimeEnvelopeSmallU)).thenReturn(0.8)
        whenever(preferences.get(FuseDoubleKey.PrimeEnvelopeLargeU)).thenReturn(2.0)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(-1L)
        whenever(preferences.get(FuseLongKey.MealMarkerStamp)).thenAnswer { markerAt }
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenAnswer { markerAt }
        whenever(preferences.get(anyOrNull<BooleanKey>())).thenReturn(false)
    }

    private fun cycle(): FuseCycleRunner.Outcome {
        clock += 60_000L
        return runner.run(false)
    }

    /** Bis zur ersten positiven Dosis fahren - der Observer braucht Vorlauf. */
    private fun driveUntilDose(maxCycles: Int = 60): FuseCycleRunner.Outcome {
        clock = start
        repeat(maxCycles) {
            val o = cycle()
            if (o.decision.smbU > 0.0) return o
        }
        throw AssertionError("keine positive Dosis in $maxCycles Zyklen")
    }

    /** Eine offene Transportzeile anlegen - so, wie sie der Publikationspfad
     *  anlegen wuerde, aber ohne den Umweg ueber das Gate. */
    private fun offenePosten(u: Double, dir: File? = null) {
        val l = FuseLedgerAdapter()
        dir?.let { l.loadOnce(it, "test-epoch", start) } ?: l.loadOnce(File("."), "test-epoch", start)
        neuerRunner(l)
        l.onPublished("vorlauf", u, start, 0L, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))
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
}

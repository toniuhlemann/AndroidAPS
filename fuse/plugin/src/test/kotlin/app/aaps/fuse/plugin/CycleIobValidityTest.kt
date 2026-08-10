package app.aaps.fuse.plugin

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
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
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever

/**
 * ZYKLUS N LIEFERT U1 - ZYKLUS N+1 MIT UNGUELTIGEM IOB LIEFERT NICHTS.
 *
 * Der von Codex geforderte Pflichtbeweis zum P0 (Re-Audit 3b5cadbf, F2).
 *
 * DER FEHLERMODUS, gegen den dieser Test steht: die zyklusweise
 * Prime-Clearance und jede Mengenkappe setzen voraus, dass die Dosis des
 * Vorzyklus im naechsten IOB-Snapshot ERSCHEINT. Genau diese Annahme brach die
 * erfundene Null: ohne Profil gab der Rechner ein genulltes, aber ENDLICHES
 * `IobTotal` zurueck, jede Endlichkeitspruefung ging durch, und der Folgezyklus
 * rechnete mit "kein Insulin an Bord" - obwohl U1 gerade unterwegs war. U2 waere
 * ohne die Haftung von U1 freigegeben worden.
 *
 * WARUM DIE ABFOLGE UND NICHT EIN EINZELWERT: die erfundene Null ist numerisch
 * identisch mit einer echten. Ein Zyklus allein kann den Unterschied nicht
 * zeigen - erst die Folge "positive Dosis, dann ungueltige Lesung" macht
 * sichtbar, ob der zweite Zyklus die Haftung des ersten kennt oder sie
 * verloren hat.
 *
 * Der Pruefstand faehrt den ECHTEN Runner mit einer echten Zustandsmaschine
 * ueber eine synthetische 1-min-Rohreihe. Nichts an der Entscheidungskette ist
 * nachgebaut; einzige Steuergroesse des Tests ist [iobValid].
 */
class CycleIobValidityTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var ads: AutosensDataStore
    @Mock lateinit var insulinProfileFunction: ProfileFunction
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var insulin: Insulin
    private lateinit var ledger: FuseLedgerAdapter
    private lateinit var runner: FuseCycleRunner

    /** Testschalter: ist die IOB-Lesung dieses Zyklus gueltig? */
    private var iobValid = true

    /** Testschalter fuer den TOCTOU-Fall: nur die BOLUS-Aktivitaetslesung ist
     *  ungueltig, die Gesamt-IOB-Lesung nicht. Genau diese Kombination hat
     *  Codex als F1 gemeldet - das Profil ist bei der einen Lesung kurz weg
     *  und bei der anderen wieder da. */
    private var bolusIobValid = true

    /** Wandert je Zyklus um eine Minute weiter. */
    private var clock = 0L

    private val start = 1_700_000_000_000L / 60_000L * 60_000L

    /** Ruhige, hohe Reihe: 180 mg/dl flach. Hoch genug fuer echten Bedarf
     *  (Profilziel ~103 mg/dl), flach genug, dass der Observer nach
     *  `rearmMin` = 5 min Ruhe auf ARMED geht. Ein Mahlzeiten-Anstieg waere
     *  fuer diesen Beweis nur zusaetzliches Rauschen. */
    private fun series(untilTs: Long): List<GV> =
        generateSequence(start) { it + 60_000L }
            .takeWhile { it <= untilTs }
            .map { ts ->
                GV(
                    timestamp = ts, value = 180.0, raw = 180.0, noise = 0.0,
                    sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT
                )
            }
            .toList()

    /** Wie der echte Rechner: der Zeitstempel des Ergebnisses ist der auf die
     *  volle Minute AUFgerundete Anforderungszeitpunkt. Der Runner baut seine
     *  ISF-Slots ueber genau dieses `time` (nicht ueber die angeforderten
     *  Zeiten), und die muessen streng steigen. */
    private fun iob(atTs: Long, valid: Boolean) = IobTotal(roundUp(atTs)).also {
        it.iob = 0.0
        it.basaliob = 0.0
        it.activity = 0.0
        it.valid = valid
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
        // Geht in den Uebertrags-Fingerabdruck (SubStepAccumulator.context) und
        // ist dort als nicht-nullbar deklariert - ein unstubbed Mock gaebe null.
        whenever(profileFunction.getProfileName()).thenReturn(TESTPROFILENAME)

        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.roundUpTime(any())).thenAnswer { inv -> roundUp(inv.getArgument(0)) }
        whenever(ads.getBgReadingsDataTableCopy()).thenAnswer { series(clock) }
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any()))
            .thenAnswer { inv -> iob(inv.getArgument(0), iobValid) }
        whenever(iobCobCalculator.calculateIobFromBolus()).thenAnswer { iob(clock, iobValid && bolusIobValid) }

        whenever(constraintsChecker.getMaxIOBAllowed()).thenAnswer { ConstraintObject(8.0, aapsLogger) }
        whenever(commandQueue.bolusInQueue()).thenReturn(false)
        whenever(commandQueue.isRunning(any())).thenReturn(false)

        whenever(persistenceLayer.getLastTherapyRecordUpToNow(any())).thenReturn(null)
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenReturn(null)
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenReturn(null)

        stubPolicy()

        ledger = FuseLedgerAdapter()
        runner = FuseCycleRunner(
            iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
            preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "test-epoch"
        )
    }

    /** Die Politik des Test-Rigs vom 09.08. - bewusst die LIVE-Werte, damit
     *  der Beweis an derselben Konfiguration haengt wie die Messung. */
    private fun stubPolicy() {
        // AUFFANG ZUERST (Codex-Re-Review 10.08.). Mockito laesst den ZULETZT
        // passenden Stub gewinnen. Am Ende stehend ueberschrieb dieser Aufruf
        // JEDEN spezifischen FuseBooleanKey mit `false` - Tail-, Restraint-,
        // Onset- und Prime-Flag waren also aus, obwohl sie unten ausdruecklich
        // auf true gesetzt werden. Das Rig lief damit nicht in der
        // Konfiguration, die es zu fahren behauptet.
        whenever(preferences.get(anyOrNull<BooleanKey>())).thenReturn(false)
        whenever(preferences.get(FuseDoubleKey.SmbRatio)).thenReturn(0.15)
        whenever(preferences.get(FuseDoubleKey.SmbRatioRise)).thenReturn(0.35)
        whenever(preferences.get(DoubleKey.ApsSmbMaxIob)).thenReturn(8.0)
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
        whenever(preferences.get(FuseBooleanKey.TailGuardEnabled)).thenReturn(true)
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
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenReturn(0L)
    }

    /** Einen Zyklus fahren: Uhr eine Minute weiter, dann rechnen. */
    private fun cycle(): FuseCycleRunner.Outcome {
        clock += 60_000L
        return runner.run(false, FuseActivePump("GENERIC_AAPS", virtualPump = true, bolusStepU = 0.05, basalStepUPerH = 0.05))
    }

    /** Bis zur ersten positiven Dosis fahren. Der Observer braucht
     *  `rearmMin` = 5 min Ruhe, die Rohreihe ihren Vorlauf. */
    private fun driveUntilDose(maxCycles: Int = 60): FuseCycleRunner.Outcome {
        clock = start
        val trail = StringBuilder()
        repeat(maxCycles) {
            val o = cycle()
            if (o.decision.smbU > 0.0) return o
            trail.append("\n  #$it ").append(o.abortReason ?: "phase=${o.step?.phase} block=${o.decision.block} reason=${o.reason}")
        }
        throw AssertionError("keine positive Dosis in $maxCycles Zyklen:$trail")
    }

    @Test
    fun `Zyklus N dosiert, Zyklus N+1 mit ungueltigem IOB dosiert nicht`() {
        val u1 = driveUntilDose()
        assertThat(u1.decision.smbU).isGreaterThan(0.0)
        assertThat(u1.abortReason).isNull()

        // ZYKLUS N+1: der Provider kann das IOB nicht mehr rechnen. Frueher kam
        // hier eine erfundene Null - die Haftung von U1 verschwand, und die
        // naechste Menge waere aus einem scheinbar leeren Spielraum gekommen.
        iobValid = false
        val u2 = cycle()
        assertThat(u2.decision.smbU).isEqualTo(0.0)
        assertThat(u2.abortReason).isNotNull()

        // RECOVERY: derselbe Runner, dieselbe Reihe, nur wieder gueltig - der
        // Abbruch darf kein Dauerzustand sein, sonst waere der fail-closed-Pfad
        // eine Stilllegung statt einer Sperre.
        iobValid = true
        assertThat(cycle().abortReason).isNull()
    }

    @Test
    fun `nur die Bolus-Aktivitaet ungueltig reicht schon fuer den Abbruch`() {
        // F1 (Codex Re-Audit 3b5cadbf, P0): der TOCTOU-Fall. Die Gesamt-IOB-
        // Lesung ist GUELTIG - die Kappenpruefung greift also nicht -, nur die
        // Bolus-Aktivitaet ist unbekannt. Bis zu diesem Fix lief der Zyklus mit
        // einer erfundenen Aktivitaet von 0 weiter. Sie speist den Deckungs-
        // Abschlag, also ERHOEHT die Null den Bedarf: die Richtung ist nicht
        // konservativ, und keine Endlichkeitspruefung sieht daran etwas.
        driveUntilDose()
        bolusIobValid = false
        val o = cycle()
        assertThat(o.decision.smbU).isEqualTo(0.0)
        assertThat(o.abortReason).isEqualTo("bolus iob unknown (no profile)")

        bolusIobValid = true
        assertThat(cycle().abortReason).isNull()
    }

    @Test
    fun `der Abbruch nennt die Ursache und ist keine stille Null`() {
        // Eine fail-closed Entscheidung ohne Namen waere von einem
        // rechnerischen "kein Bedarf" nicht zu unterscheiden - im Trail
        // saehe beides gleich aus.
        driveUntilDose()
        iobValid = false
        val o = cycle()
        assertThat(o.abortReason).contains("iob")
        assertThat(o.decision.smbU).isEqualTo(0.0)
    }
}

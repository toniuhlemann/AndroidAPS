package app.aaps.implementation.queue

import android.content.Context
import android.os.PowerManager
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.androidPermissions.AndroidPermission
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.rx.events.EventQueueIdleRetryLoop
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.implementation.queue.commands.CommandReadStatus
import app.aaps.implementation.queue.commands.CommandTempBasalAbsolute
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class QueueWorkerTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var powerManager: PowerManager
    @Mock lateinit var androidPermission: AndroidPermission
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var jobName: CommandQueueName
    @Mock lateinit var workManager: WorkManager
    @Mock lateinit var loop: Loop
    @Mock lateinit var localAlertUtils: LocalAlertUtils

    init {
        addInjector {
            if (it is CommandTempBasalAbsolute) {
                it.aapsLogger = aapsLogger
                it.activePlugin = activePlugin
                it.rh = rh
                // Fuer cancel(): das Kommando baut sein Abbruchergebnis selbst.
                it.pumpEnactResultProvider = pumpEnactResultProvider
            }
            if (it is CommandReadStatus) {
                it.aapsLogger = aapsLogger
                it.activePlugin = activePlugin
                it.rh = rh
                it.localAlertUtils = localAlertUtils
                it.pumpEnactResultProvider = pumpEnactResultProvider
            }
            if (it is QueueWorker) {
                it.aapsLogger = aapsLogger
                it.queue = commandQueue
                it.context = context
                it.rxBus = rxBus
                it.activePlugin = activePlugin
                it.rh = rh
                it.preferences = preferences
                it.androidPermission = androidPermission
                it.config = config
                // Fehlte seit 1f8fb62d93 (Fork-Patch 0055 hat das Feld
                // eingefuehrt, die Testverdrahtung aber nicht nachgezogen).
                // Der Worker liest es im Leerlaufzweig, `commandIsPickedUp`
                // lief seither in eine UninitializedPropertyAccessException.
                it.loop = loop
            }
        }
    }

    private lateinit var commandQueue: CommandQueueImplementation
    private lateinit var sut: QueueWorker

    @BeforeEach
    fun prepare() {
        commandQueue = CommandQueueImplementation(
            injector, aapsLogger, rxBus, aapsSchedulers, rh, constraintChecker,
            profileFunction, activePlugin, context, config, dateUtil, fabricPrivacy,
            uiInteraction, persistenceLayer, decimalFormatter, pumpEnactResultProvider, jobName, workManager
        )

        val pumpDescription = PumpDescription()
        pumpDescription.basalMinimumRate = 0.1

        whenever(context.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)

        val bolusConstraint = ConstraintObject(0.0, aapsLogger)
        whenever(constraintChecker.applyBolusConstraints(anyOrNull())).thenReturn(bolusConstraint)
        whenever(constraintChecker.applyExtendedBolusConstraints(anyOrNull())).thenReturn(bolusConstraint)
        val carbsConstraint = ConstraintObject(0, aapsLogger)
        whenever(constraintChecker.applyCarbsConstraints(anyOrNull())).thenReturn(carbsConstraint)
        val rateConstraint = ConstraintObject(0.0, aapsLogger)
        whenever(constraintChecker.applyBasalConstraints(anyOrNull(), anyOrNull())).thenReturn(rateConstraint)
        val percentageConstraint = ConstraintObject(0, aapsLogger)
        whenever(constraintChecker.applyBasalPercentConstraints(anyOrNull(), anyOrNull()))
            .thenReturn(percentageConstraint)
        whenever(rh.gs(ArgumentMatchers.eq(app.aaps.core.ui.R.string.temp_basal_absolute), anyOrNull(), anyOrNull())).thenReturn("TEMP BASAL %1\$.2f U/h %2\$d min")
        // Der Abbruch-Callback der wartenden Kommandos baut daraus seinen
        // Kommentar; ohne Stub liefert der Mock null und PumpEnactResultObject
        // wirft beim Setzen.
        whenever(rh.gs(ArgumentMatchers.eq(app.aaps.core.ui.R.string.connectiontimedout))).thenReturn("connection timed out")
        whenever(rh.gs(ArgumentMatchers.eq(app.aaps.core.ui.R.string.read_status), anyOrNull())).thenReturn("READ STATUS %1\$s")

        sut = TestListenableWorkerBuilder<QueueWorker>(context).build()
    }

    @Test
    fun commandIsPickedUp() = runTest(timeout = 30.seconds) {
        commandQueue.tempBasalAbsolute(2.0, 60, true, validProfile, PumpSync.TemporaryBasalType.NORMAL, null)
        val result = sut.doWorkAndLog()
        assertIs<ListenableWorker.Result.Success>(result)
        assertThat(commandQueue.size()).isEqualTo(0)
        assertThat(commandQueue.performing()).isNull()
    }

    // ---- B0b: eine werfende Ausfuehrung darf die Queue nicht stilllegen ----

    /**
     * Laesst die aktive Pumpe beim Ausfuehren eine Ausnahme fliegen.
     *
     * Realer Auslöser im Zieltreiber: `require(insulin > 0)` in
     * `MedtrumPlugin.deliverTreatment` - ein Wurf aus dem Kommando ist also
     * kein konstruierter Fall.
     */
    private fun pumpThatThrows(e: Throwable) {
        val spyPump = spy(testPumpPlugin)
        // doThrow statt whenever(...): die whenever-Form ruft am Spy die ECHTE
        // Methode auf, und Kotlins Nichtnull-Pruefung des profile-Parameters
        // wirft dann schon beim Stubben.
        doThrow(e).whenever(spyPump)
            .setTempBasalAbsolute(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        whenever(activePlugin.activePump).thenReturn(spyPump)
    }

    private fun enqueueTbr(callback: Callback? = null) =
        commandQueue.tempBasalAbsolute(2.0, 60, true, validProfile, PumpSync.TemporaryBasalType.NORMAL, callback)

    /**
     * DER KERN VON B0b. `QueueWorker` ruft `execute()` ohne try/catch, und
     * `resetPerforming()` steht DAHINTER. Wirft das Kommando, bleibt
     * `performing` fuer immer gesetzt: `bolusInQueue()` meldet dauerhaft true,
     * jeder weitere SMB wird abgelehnt, und der Callback feuert nie. Die
     * einzige Rettung waere ein Prozessneustart.
     */
    @Test
    fun `eine werfende Ausfuehrung laesst die Queue nicht verklemmt zurueck`() = runTest(timeout = 30.seconds) {
        pumpThatThrows(IllegalStateException("driver exploded"))
        enqueueTbr()

        val result = sut.doWorkAndLog()

        // KEIN Success: das Pumpenergebnis bleibt zu Recht unbekannt, aber der
        // Drain war nicht erfolgreich - beides darf nicht vermischt werden.
        assertIs<ListenableWorker.Result.Failure>(result)
        assertThat(commandQueue.performing()).isNull()
        assertThat(commandQueue.size()).isEqualTo(0)
    }

    /** Die Folge fuer den Regler: nach dem Wurf muss wieder dosiert werden
     *  koennen. Ohne den Fix ist `bolusInQueue()` dauerhaft true. */
    @Test
    fun `nach einer werfenden Ausfuehrung ist die Queue wieder aufnahmefaehig`() = runTest(timeout = 30.seconds) {
        pumpThatThrows(IllegalStateException("driver exploded"))
        enqueueTbr()
        sut.doWorkAndLog()

        assertThat(commandQueue.bolusInQueue()).isFalse()
        assertThat(commandQueue.isRunning(app.aaps.core.interfaces.queue.Command.CommandType.TEMPBASAL)).isFalse()
    }

    /**
     * KEIN ERGEBNIS IST NICHT DASSELBE WIE "NICHT GESENDET".
     *
     * Nach einem Wurf ist der Ausgang UNBEKANNT - das Kommando kann die Pumpe
     * schon erreicht haben. Der Worker darf daraus deshalb kein Ergebnis
     * ableiten: weder `cancel()` (das meldet success=false) noch einen eigenen
     * Callback. Er raeumt auf und schweigt; die Bewertung gehoert an die
     * Stelle, die den Lieferzustand wirklich kennt.
     */
    @Test
    fun `eine werfende Ausfuehrung leitet KEIN Ergebnis an den Aufrufer ab`() = runTest(timeout = 30.seconds) {
        pumpThatThrows(IllegalStateException("driver exploded"))
        var gemeldet: PumpEnactResult? = null
        val callback = object : Callback() {
            override fun run() {
                gemeldet = result
            }
        }
        enqueueTbr(callback)

        sut.doWorkAndLog()

        assertThat(gemeldet).isNull()
        // ... und die Queue ist trotzdem frei.
        assertThat(commandQueue.performing()).isNull()
    }

    /**
     * Abbruch ist kein Fehler: eine `CancellationException` gehoert nach dem
     * Aufraeumen WEITERGEWORFEN, sonst wird ein abgebrochener Worker als
     * regulaer beendet gemeldet und die strukturierte Nebenlaeufigkeit bricht.
     */
    @Test
    fun `CancellationException wird nach dem Aufraeumen weitergeworfen`() = runTest(timeout = 30.seconds) {
        pumpThatThrows(CancellationException("worker abgebrochen"))
        enqueueTbr()

        assertFailsWith<CancellationException> { sut.doWorkAndLog() }
        assertThat(commandQueue.performing()).isNull()
    }

    /**
     * Abbruch raeumt auf, entscheidet aber NICHT ueber die wartenden
     * Kommandos - er sagt nichts ueber sie aus. Nur der Fehlerpfad leert die
     * Queue.
     */
    @Test
    fun `Cancellation leert die Queue nicht eigenmaechtig`() = runTest(timeout = 30.seconds) {
        pumpThatThrows(CancellationException("worker abgebrochen"))
        enqueueTbr()
        var wartendes: PumpEnactResult? = null
        commandQueue.readStatus("zweites Kommando", object : Callback() {
            override fun run() {
                wartendes = result
            }
        })

        assertFailsWith<CancellationException> { sut.doWorkAndLog() }

        assertThat(commandQueue.performing()).isNull()
        // Das aufgenommene Kommando ist per pickup() aus der Queue gepollt; das
        // WARTENDE muss stehenbleiben und darf keinen Abbruch gemeldet bekommen.
        assertThat(commandQueue.size()).isEqualTo(1)
        assertThat(wartendes).isNull()
    }

    // ---- B0b Runde 2: nach unbekanntem Ausgang wird NICHT weitergefahren ----

    /**
     * DER P0 AUS DER GEGENPRUEFUNG. Aufraeumen allein genuegt nicht: nach einem
     * Wurf ist der Ausgang UNBEKANNT - das Kommando kann die Pumpe erreicht
     * haben. Wer danach das naechste Kommando faehrt, kann auf einen
     * moeglicherweise abgegebenen SMB einen zweiten setzen.
     */
    @Test
    fun `nach einem Wurf wird kein weiteres Kommando mehr ausgefuehrt`() = runTest(timeout = 30.seconds) {
        val spyPump = spy(testPumpPlugin)
        doThrow(IllegalStateException("driver exploded")).whenever(spyPump)
            .setTempBasalAbsolute(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        whenever(activePlugin.activePump).thenReturn(spyPump)

        enqueueTbr()
        commandQueue.readStatus("darf nicht laufen", null)

        sut.doWorkAndLog()

        // getPumpStatus ist die einzige Wirkung von CommandReadStatus.execute().
        verify(spyPump, never()).getPumpStatus(anyOrNull())
        assertThat(commandQueue.size()).isEqualTo(0)
    }

    /**
     * Nur die NOCH NICHT aufgenommenen Kommandos duerfen ihren regulaeren
     * Cancel-Callback bekommen - fuer sie ist "nicht gesendet" korrekt. Das
     * bereits aufgenommene bleibt UNKNOWN und schweigt.
     */
    @Test
    fun `nur die wartenden Kommandos bekommen ihren Cancel-Callback`() = runTest(timeout = 30.seconds) {
        val spyPump = spy(testPumpPlugin)
        doThrow(IllegalStateException("driver exploded")).whenever(spyPump)
            .setTempBasalAbsolute(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        whenever(activePlugin.activePump).thenReturn(spyPump)

        var geworfenes: PumpEnactResult? = null
        var wartendes: PumpEnactResult? = null
        enqueueTbr(object : Callback() {
            override fun run() {
                geworfenes = result
            }
        })
        commandQueue.readStatus("wartend", object : Callback() {
            override fun run() {
                wartendes = result
            }
        })

        sut.doWorkAndLog()

        // Das AUFGENOMMENE Kommando schweigt - sein Ausgang ist unbekannt.
        assertThat(geworfenes).isNull()
        // Das WARTENDE meldet Abbruch. Die Verify-Zeile ist hier wesentlich:
        // ohne sie wuerde der Test auch dann gruen, wenn das Kommando statt
        // abgebrochen einfach AUSGEFUEHRT worden waere - CommandReadStatus
        // liefert in diesem Aufbau ebenfalls success=false.
        verify(spyPump, never()).getPumpStatus(anyOrNull())
        assertThat(wartendes).isNotNull()
        assertThat(wartendes!!.success).isFalse()
    }

    /**
     * Patch 0055 sendet im Leerlauf einen Loop-Retry. Nach einem unbekannten
     * Ausgang darf der NICHT feuern - er wuerde eine neue Berechnung und damit
     * moeglicherweise eine zweite Dosis anstossen.
     */
    @Test
    fun `nach einem Wurf entsteht kein Idle-Retry`() = runTest(timeout = 30.seconds) {
        whenever(loop.pendingRetryBgTs()).thenReturn(now)
        pumpThatThrows(IllegalStateException("driver exploded"))
        val retries = mutableListOf<EventQueueIdleRetryLoop>()
        val abo = rxBus.toObservable(EventQueueIdleRetryLoop::class.java).subscribe { retries.add(it) }
        enqueueTbr()

        sut.doWorkAndLog()
        abo.dispose()

        assertThat(retries).isEmpty()
    }
}

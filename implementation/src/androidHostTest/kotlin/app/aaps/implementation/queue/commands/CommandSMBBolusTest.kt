package app.aaps.implementation.queue.commands

import app.aaps.core.data.model.BS
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.cancel
import app.aaps.core.keys.IntKey
import app.aaps.implementation.pump.PumpEnactResultObject
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CommandSMBBolusTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var bolusProgressData: BolusProgressData

    private fun newCommand(info: DetailedBolusInfo) =
        CommandSMBBolus(
            aapsLogger, rh, dateUtil, activePlugin, persistenceLayer, preferences, bolusProgressData,
            pumpEnactResultProvider::invoke, info, BOLUS_GENERATION
        )

    private fun smbInfo(deliverAtTheLatest: Long = System.currentTimeMillis()) =
        DetailedBolusInfo().apply {
            insulin = 0.5
            bolusType = BS.Type.SMB
            this.deliverAtTheLatest = deliverAtTheLatest
        }

    @Test
    fun `execute rejects when within ApsMaxSmbFrequency interval`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        val recentBolus = BS(timestamp = dateUtil.now() - T.mins(1).msecs(), type = BS.Type.NORMAL, amount = 0.0, iCfg = someICfg)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(recentBolus)

        val result = newCommand(smbInfo()).execute()

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        verify(bolusProgressData).clear(BOLUS_GENERATION)
    }

    @Test
    fun `fork - the interval allows the shared tolerance before its end`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(1)
        // 10 s before the end of a one minute interval: inside the tolerance, so the pump is called
        val recentBolus = BS(timestamp = dateUtil.now() - T.secs(50).msecs(), type = BS.Type.SMB, amount = 0.1, iCfg = someICfg)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(recentBolus)
        val info = smbInfo()
        val pumpResult = PumpEnactResultObject(rh).success(true).enacted(true)
        val pump = mock<PumpWithConcentration> {
            on { deliverTreatment(info) } doReturn pumpResult
        }
        whenever(activePlugin.activePump).thenReturn(pump)

        val result = newCommand(info).execute()

        assertThat(result).isSameInstanceAs(pumpResult)
    }

    @Test
    fun `fork - earlier than the shared tolerance is still rejected`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(1)
        // 20 s before the end of a one minute interval: outside the tolerance
        val recentBolus = BS(timestamp = dateUtil.now() - T.secs(40).msecs(), type = BS.Type.SMB, amount = 0.1, iCfg = someICfg)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(recentBolus)

        val result = newCommand(smbInfo()).execute()

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
    }

    @Test
    fun `execute calls pump when deliverAtTheLatest is recent enough`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(null)
        val info = smbInfo()
        val pumpResult = PumpEnactResultObject(rh).success(true).enacted(true)
        val pump = mock<PumpWithConcentration> {
            on { deliverTreatment(info) } doReturn pumpResult
        }
        whenever(activePlugin.activePump).thenReturn(pump)

        val result = newCommand(info).execute()

        assertThat(result).isSameInstanceAs(pumpResult)
        verify(bolusProgressData).clear(BOLUS_GENERATION)
    }

    @Test
    fun `execute rejects when SMB request is too old`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(null)
        // deliverAtTheLatest is in the past beyond the 1-minute grace window
        val stale = smbInfo(deliverAtTheLatest = System.currentTimeMillis() - T.mins(5).msecs())

        val result = newCommand(stale).execute()

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        verify(bolusProgressData).clear(BOLUS_GENERATION)
    }

    @Test
    fun `execute rejects when deliverAtTheLatest is zero`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(null)
        val info = smbInfo(deliverAtTheLatest = 0L)

        val result = newCommand(info).execute()

        assertThat(result.success).isFalse()
    }

    @Test
    fun `executeAndComplete completes with execute result`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(null)
        val info = smbInfo()
        val pumpResult = PumpEnactResultObject(rh).success(true).enacted(true)
        val pump = mock<PumpWithConcentration> {
            on { deliverTreatment(info) } doReturn pumpResult
        }
        whenever(activePlugin.activePump).thenReturn(pump)
        val command = newCommand(info)

        command.executeAndComplete()

        val received = command.completion.await()
        assertThat(received).isSameInstanceAs(pumpResult)
    }

    @Test
    fun `cancel clears progress data and completes with success by default`() = runTest {
        whenever(rh.gs(app.aaps.core.ui.R.string.command_replaced)).thenReturn("replaced")
        val command = newCommand(smbInfo())

        command.cancel(app.aaps.core.ui.R.string.command_replaced)

        val received = command.completion.await()
        assertThat(received.success).isTrue()
        verify(bolusProgressData).clear(BOLUS_GENERATION)
    }

    @Test
    fun `cancel clears progress data and completes with failure when success=false`() = runTest {
        whenever(rh.gs(app.aaps.core.ui.R.string.command_replaced)).thenReturn("replaced")
        val command = newCommand(smbInfo())

        command.cancel(app.aaps.core.ui.R.string.command_replaced, success = false)

        val received = command.completion.await()
        assertThat(received.success).isFalse()
        verify(bolusProgressData).clear(BOLUS_GENERATION)
    }

    // ---- Fork (FUSE KI-171): notSentToPump only where the pump was provably never called ----

    @Test
    fun `fork - the interval rejection is marked as never sent`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        val recentBolus = BS(timestamp = dateUtil.now() - T.mins(1).msecs(), type = BS.Type.NORMAL, amount = 0.0, iCfg = someICfg)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(recentBolus)

        val result = newCommand(smbInfo()).execute()

        assertThat(result.notSentToPump).isTrue()
    }

    @Test
    fun `fork - the too old rejection is marked as never sent`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(null)

        val result = newCommand(smbInfo(deliverAtTheLatest = System.currentTimeMillis() - T.mins(5).msecs())).execute()

        assertThat(result.notSentToPump).isTrue()
    }

    @Test
    fun `fork - a pump result is never marked, also a failed one`() = runTest {
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(null)
        val info = smbInfo()
        val pumpResult = PumpEnactResultObject(rh).success(false).enacted(false)
        val pump = mock<PumpWithConcentration> {
            on { deliverTreatment(info) } doReturn pumpResult
        }
        whenever(activePlugin.activePump).thenReturn(pump)

        val result = newCommand(info).execute()

        assertThat(result.notSentToPump).isFalse()
    }

    @Test
    fun `fork - a waiting SMB the queue drops was never sent`() = runTest {
        whenever(rh.gs(app.aaps.core.ui.R.string.command_replaced)).thenReturn("replaced")
        val command = newCommand(smbInfo())

        command.cancel(app.aaps.core.ui.R.string.command_replaced, success = false)

        val received = command.completion.await()
        assertThat(received.success).isFalse()
        assertThat(received.notSentToPump).isTrue()
    }

    @Test
    fun `fork - a started SMB the executor cancels after a driver throw is not marked`() = runTest {
        whenever(rh.gs(app.aaps.core.ui.R.string.error)).thenReturn("error")
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(null)
        val info = smbInfo()
        val pump = mock<PumpWithConcentration> {
            on { deliverTreatment(info) } doThrow IllegalStateException("driver")
        }
        whenever(activePlugin.activePump).thenReturn(pump)
        val command = newCommand(info)

        // What CommandExecutor does: the driver throws, the executor cancels the running command.
        assertThat(runCatching { command.executeAndComplete() }.isFailure).isTrue()
        command.cancel(app.aaps.core.ui.R.string.error, success = false)

        val received = command.completion.await()
        assertThat(received.success).isFalse()
        // The pump may have delivered before the driver threw: nothing is proven.
        assertThat(received.notSentToPump).isFalse()
    }

    /**
     * Fork (FUSE KI-171, product chat): "started" is set BEFORE the first driver call, and a started SMB stays
     * liable also when the connection drops. Here the drop arrives while the driver is being called: the queue
     * cancels the command (connection timeout) and the driver then reports a failure. The pump may have delivered,
     * so nothing is marked - had `started` been set only after the driver call, the cancel would mark it.
     */
    @Test
    fun `fork - a started SMB stays liable when the connection drops during the driver call`() = runTest {
        whenever(rh.gs(app.aaps.core.ui.R.string.connectiontimedout)).thenReturn("Connection timed out")
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        whenever(persistenceLayer.getNewestBolus()).thenReturn(null)
        val info = smbInfo()
        val driverCalled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pump = mock<PumpWithConcentration>()
        whenever(pump.deliverTreatment(info)).doSuspendableAnswer {
            driverCalled.complete(Unit)
            release.await()
            PumpEnactResultObject(rh).success(false).enacted(false).comment("connection lost")
        }
        whenever(activePlugin.activePump).thenReturn(pump)
        val command = newCommand(info)

        val running = launch { command.executeAndComplete() }
        driverCalled.await()
        command.cancel(app.aaps.core.ui.R.string.connectiontimedout, success = false)
        release.complete(Unit)
        running.join()

        val received = command.completion.await()
        assertThat(received.success).isFalse()
        assertThat(received.notSentToPump).isFalse()
    }

    @Test
    fun `commandType is SMB_BOLUS`() {
        assertThat(newCommand(smbInfo()).commandType).isEqualTo(Command.CommandType.SMB_BOLUS)
    }

    private companion object {

        const val BOLUS_GENERATION = 7L
    }
}

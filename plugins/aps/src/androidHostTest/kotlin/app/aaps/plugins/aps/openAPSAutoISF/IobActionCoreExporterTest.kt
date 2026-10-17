package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Fork (IOB-Action viewer): the core file keeps the keys of the 3.4 fork, and a value that is not
 * there is a key that is not there - never a null and never a made-up zero. Synthetic values.
 */
class IobActionCoreExporterTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var loop: Loop
    @Mock lateinit var effectiveAutoIsfSettings: EffectiveAutoIsfSettingsProvider
    @Mock lateinit var sink: IobActionExportSink
    @Mock lateinit var pump: PumpWithConcentration
    // A profile without an APS behind it throws in getIc(); in the app the active APS is always there.
    @Mock lateinit var profile: EffectiveProfile

    private lateinit var sut: IobActionCoreExporter

    @BeforeEach fun setUp() {
        sut = IobActionCoreExporter(
            aapsLogger, iobCobCalculator, processedTbrEbData, persistenceLayer, profileFunction, activePlugin,
            preferences, dateUtil, glucoseStatusProvider, loop, effectiveAutoIsfSettings, ch, sink
        )
        whenever(effectiveAutoIsfSettings.snapshot()).thenReturn(
            EffectiveAutoIsfSettingsProvider.Snapshot(100, 100, AutoIsfOverrideState.NONE, null, null, null)
        )
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.reservoirLevel).thenReturn(MutableStateFlow(PumpInsulin(100.0)))
        whenever(pump.batteryLevel).thenReturn(MutableStateFlow(50))
        whenever(ch.fromPump(any<PumpInsulin>(), any())).thenReturn(100.0)
    }

    private suspend fun prepareLiveState() {
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(profile.getTargetMgdl()).thenReturn(100.0)
        whenever(profile.getProfileIsfMgdl()).thenReturn(50.0)
        whenever(profile.getIc()).thenReturn(10.0)
        whenever(profile.getBasal()).thenReturn(1.0)
        whenever(profileFunction.getProfileName()).thenReturn("Synthetic")
        whenever(profileFunction.getProfileNameWithRemainingTime()).thenReturn("Synthetic")
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any())).thenReturn(
            IobTotal(now).also { it.iob = 1.5; it.basaliob = 0.5; it.activity = 0.01 }
        )
        whenever(iobCobCalculator.getCobInfo(any())).thenReturn(CobInfo(now, 12.0, 0.0))
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenReturn(null)
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenReturn(null)
        whenever(loop.runningMode()).thenReturn(RM.Mode.CLOSED_LOOP)
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(
            listOf(
                BS(timestamp = now - 60_000L, amount = 0.2, type = BS.Type.SMB, iCfg = someICfg),
                BS(timestamp = now - 120_000L, amount = 1.0, type = BS.Type.NORMAL, iCfg = someICfg)
            )
        )
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(null)
    }

    private fun written(): JsonObject = argumentCaptor<JsonObject>().also { verify(sink).writeCore(it.capture()) }.firstValue

    @Test fun theCoreFileCarriesTheForkKeys() = runTest {
        prepareLiveState()

        sut.snapshot()

        val core = written()
        assertThat(core.keys).containsAtLeast("ts", "core", "aps", "iob", "profile", "device", "smbHistory")
        val aps = core["aps"]!!.jsonObject
        assertThat(aps["IOB"]!!.jsonPrimitive.double).isEqualTo(1.5)
        assertThat(aps["COB"]!!.jsonPrimitive.double).isEqualTo(12.0)
        assertThat(aps["loopMode"]!!.jsonPrimitive.content).isEqualTo("CLOSED_LOOP")
        assertThat(aps["loopClosedOrLgs"]!!.jsonPrimitive.content).isEqualTo("true")
        val iob = core["iob"]!!.jsonObject
        assertThat(iob["bolus"]!!.jsonPrimitive.double).isEqualTo(1.0)
        // Only the SMB, not the normal bolus.
        val smbs = core["smbHistory"] as JsonArray
        assertThat(smbs).hasSize(1)
        assertThat(smbs[0].jsonObject["units"]!!.jsonPrimitive.double).isEqualTo(0.2)
        assertThat(core["device"]!!.jsonObject["reservoir"]!!.jsonPrimitive.double).isEqualTo(100.0)
    }

    @Test fun missingValuesAreMissingKeys() = runTest {
        prepareLiveState()

        sut.snapshot()

        val core = written()
        // No temp target and no glucose status: no block for them, and no bg in the aps block.
        assertThat(core.keys).doesNotContain("tt")
        assertThat(core.keys).doesNotContain("glucose")
        assertThat(core["aps"]!!.jsonObject.keys).doesNotContain("bg")
        // No running temp basal: no rate and no duration.
        assertThat(core["aps"]!!.jsonObject.keys).containsNoneOf("rate", "duration")
    }

    @Test fun withoutAProfileNothingIsWritten() = runTest {
        whenever(profileFunction.getProfile()).thenReturn(null)

        sut.snapshot()

        verifyNoInteractions(sink)
    }
}

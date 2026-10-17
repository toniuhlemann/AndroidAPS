package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.TT
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryTarget
import app.aaps.core.nssdk.mapper.convertToRemoteAndBack
import app.aaps.plugins.sync.extensions.contentEqualsTo
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

@Suppress("SpellCheckingInspection")
internal class TemporaryTargetExtensionKtTest : TestBaseWithProfile() {

    @Test
    fun forkReasonsSurviveTheNightscoutRoundTrip() {
        // Fork (IOB-Action viewer): the viewer posts its own reason texts. They must come back as the
        // same reason, not as CUSTOM.
        listOf(TT.Reason.PEAK_STOP, TT.Reason.CORRECTION, TT.Reason.REBOUND, TT.Reason.BRAKE, TT.Reason.MEAL, TT.Reason.LOW_PROTECT).forEach { reason ->
            val temporaryTarget = TT(
                timestamp = 10000,
                isValid = true,
                reason = reason,
                highTarget = 120.0,
                lowTarget = 120.0,
                duration = 3600000,
                ids = IDs(nightscoutId = "nightscoutId")
            )
            val back = (temporaryTarget.toNSTemporaryTarget().convertToRemoteAndBack() as NSTemporaryTarget).toTemporaryTarget()
            assertThat(back.reason).isEqualTo(reason)
        }
    }

    @Test
    fun toTemporaryTarget() {
        var temporaryTarget = TT(
            timestamp = 10000,
            isValid = true,
            reason = TT.Reason.ACTIVITY,
            highTarget = 100.0,
            lowTarget = 99.0,
            duration = 3600000,
            ids = IDs(
                nightscoutId = "nightscoutId",
                pumpId = 11000,
                pumpType = PumpType.DANA_I,
                pumpSerial = "bbbb"
            )
        )

        var temporaryTarget2 = (temporaryTarget.toNSTemporaryTarget().convertToRemoteAndBack() as NSTemporaryTarget).toTemporaryTarget()
        assertThat(temporaryTarget.contentEqualsTo(temporaryTarget2)).isTrue()
        assertThat(temporaryTarget.ids.contentEqualsTo(temporaryTarget2.ids)).isTrue()

        temporaryTarget = TT(
            timestamp = 10000,
            isValid = true,
            reason = TT.Reason.CUSTOM,
            highTarget = 150.0,
            lowTarget = 150.0,
            duration = 30000,
            ids = IDs(
                nightscoutId = "nightscoutId",
                pumpId = 11000,
                pumpType = PumpType.DANA_I,
                pumpSerial = "bbbb"
            )
        )

        temporaryTarget2 = (temporaryTarget.toNSTemporaryTarget().convertToRemoteAndBack() as NSTemporaryTarget).toTemporaryTarget()
        assertThat(temporaryTarget.contentEqualsTo(temporaryTarget2)).isTrue()
        assertThat(temporaryTarget.ids.contentEqualsTo(temporaryTarget2.ids)).isTrue()
    }
}

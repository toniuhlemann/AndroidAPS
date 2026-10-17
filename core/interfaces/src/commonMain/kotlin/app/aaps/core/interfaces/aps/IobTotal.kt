package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Suppress("SpellCheckingInspection")
@Serializable
data class IobTotal(
    val time: Long,
    var iob: Double = 0.0,
    var activity: Double = 0.0,
    var bolussnooze: Double = 0.0,
    var basaliob: Double = 0.0,
    var netbasalinsulin: Double = 0.0,
    var hightempinsulin: Double = 0.0,

    // oref1
    var lastBolusTime: Long = 0,
    var iobWithZeroTemp: IobTotal? = null,
    var netInsulin: Double = 0.0, // for calculations from temp basals only
    var extendedBolusInsulin: Double = 0.0, // total insulin for extended bolus
    /**
     * Fork (FUSE contract 2): was the calculation POSSIBLE at all?
     *
     * `false` means UNKNOWN, not zero. Without a profile, or with an insulin definition that is not a
     * real one, the calculation still returns a finite object - and a consumer cannot tell a made-up
     * zero from a real one, so every check on the VALUE fails by design. The value has to say itself
     * whether it is a statement. True by default: every successful path and every caller that does
     * not know the difference behave exactly as before.
     *
     * Runtime only (@Transient): it never changes a stored or uploaded JSON.
     */
    @Transient var valid: Boolean = true,
) {

    companion object
}
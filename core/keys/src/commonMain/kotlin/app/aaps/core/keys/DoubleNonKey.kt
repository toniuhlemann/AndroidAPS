package app.aaps.core.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

enum class DoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
    override val exportable: Boolean = true
) : DoubleNonPreferenceKey {

    // autoISF 3.2.1: state, written by the plugins, never shown. Device runtime state → not exportable.
    FslLastRaw("fsl_last_raw", -1.0, exportable = false),
    FslLastSmooth("fsl_last_smooth", -1.0, exportable = false),
    ActivityMonitorRatio("activity_ratio", 1.0, exportable = false),
}
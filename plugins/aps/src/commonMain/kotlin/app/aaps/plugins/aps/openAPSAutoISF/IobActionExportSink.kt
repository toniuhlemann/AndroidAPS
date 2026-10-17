package app.aaps.plugins.aps.openAPSAutoISF

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fork (IOB-Action native viewer): where the export files go.
 *
 * The companion viewer reads small JSON files instead of tail-parsing AndroidAPS.log. The files and
 * their format are the viewer contract and must stay as the 3.4 fork wrote them:
 *  - iobaction_state.json: the full loop / autoISF state, from the determine-basal cycle of autoISF.
 *  - iobaction_core.json: a small live snapshot (IOB/COB/TBR/TT/device), every minute, independent of
 *    the active APS, so it stays fresh under FUSE too.
 *  - iobaction_config.json: filtered preferences, profile and automations, when they change.
 *
 * Write-only. Nothing here is ever read back into a dosing decision; every writer is guarded, so a
 * failing export never touches the loop. The files are written by the platform: on Android to
 * Documents/aapsLogs with temp file, rename and a fresh mtime, built with org.json so the bytes look
 * like the 3.4 fork wrote them. Platforms without the companion viewer write nothing.
 */
interface IobActionExportSink {

    /**
     * Writes iobaction_state.json. [smbs] and [factors] feed the SMB history with the autoISF factors
     * that drove each SMB; the platform keeps the remembered stamps in its side file.
     */
    fun writeState(state: JsonObject, smbs: List<SmbIn>, factors: Factors)

    /** Writes iobaction_core.json. */
    fun writeCore(core: JsonObject)

    /** Writes iobaction_config.json. */
    fun writeConfig(config: JsonObject)

    /** Tells the viewer that a new loop cycle was written, so it reads now and not at its next poll. */
    fun loopCycleWritten(cycleTs: Long)

    /** One delivered SMB from the bolus database (timestamp and units). */
    data class SmbIn(val tsMs: Long, val units: Double)

    /** The autoISF factors of the current cycle, stamped onto SMBs delivered in this cycle. */
    data class Factors(
        val acce: Double?,
        val bg: Double?,
        val pp: Double?,
        val dura: Double?,
        val final: Double?,
    )
}

/**
 * Puts [value] like org.json does: null means the key is absent. Doubles that are not finite are
 * left out too - org.json would throw on them and the whole file would stay old.
 */
fun JsonObjectBuilder.putOpt(key: String, value: Any?) {
    when (value) {
        null       -> Unit
        is Double  -> if (value.isFinite()) put(key, JsonPrimitive(value))
        is Float   -> if (value.isFinite()) put(key, JsonPrimitive(value.toDouble()))
        is Number  -> put(key, JsonPrimitive(value))
        is Boolean -> put(key, JsonPrimitive(value))
        is String  -> put(key, JsonPrimitive(value))
        is JsonElement -> put(key, value)
        else       -> put(key, JsonPrimitive(value.toString()))
    }
}

/** A finite value, or null. */
fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

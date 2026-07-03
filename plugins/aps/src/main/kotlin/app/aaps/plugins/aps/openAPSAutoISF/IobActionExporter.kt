package app.aaps.plugins.aps.openAPSAutoISF

import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * IOB Action native-viewer export.
 *
 * Writes the complete autoISF/loop state as a small JSON each loop cycle so the companion
 * "iob-action-native-viewer" app can read it DIRECTLY instead of tail-parsing the (huge,
 * 1-min-CGM) AndroidAPS.log — which intermittently misses the autoISF lines.
 *
 * SAFETY: this is write-only and fully wrapped in runCatching by the caller; it can never
 * influence determine-basal / dosing. The JSON is written atomically (temp + rename) so the
 * reader never sees a half-written file. Output: <ext>/Documents/aapsLogs/iobaction_state.json
 * (same dir AAPS already writes its logs to, so it is readable by the companion app).
 *
 * Two separate files, two separate writers (no shared-file read-modify-write race):
 *  - iobaction_state.json: the FULL autoISF/loop state, written by the determineBasal hook
 *    (loop-gated — only as fresh as the last loop cycle).
 *  - iobaction_core.json:  a small LIVE core snapshot (BG-independent: IOB/COB/TBR/TT/device),
 *    written every 60s from the live calculators by [IobActionCoreExporter], decoupled from the
 *    loop — mirrors how the AAPS-native widget stays fresh. The viewer merges both, preferring
 *    the (always-fresh) core for the live fields and falling back to the loop file for the
 *    autoISF-only fields (evBG/predBGs/bgAccel/dura/iobTH/…).
 */
object IobActionExporter {

    private const val FILE_NAME = "iobaction_state.json"
    private const val CORE_FILE_NAME = "iobaction_core.json"
    private const val CONFIG_FILE_NAME = "iobaction_config.json"

    /** Full loop/autoISF state — called from the determineBasal hook (loop-gated). */
    fun write(json: JSONObject) = writeFile(json, FILE_NAME)

    /** Live core snapshot — called every 60s from [IobActionCoreExporter] (loop-independent). */
    fun writeCore(json: JSONObject) = writeFile(json, CORE_FILE_NAME)

    /** Config snapshot (prefs + profile + automations) — change-triggered from [IobActionConfigExporter]. */
    fun writeConfig(json: JSONObject) = writeFile(json, CONFIG_FILE_NAME)

    private fun writeFile(json: JSONObject, fileName: String) {
        runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs")
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$fileName.tmp")
            tmp.writeText(json.toString())
            val target = File(dir, fileName)
            if (!tmp.renameTo(target)) {
                // Fallback if rename across the same dir failed for any reason.
                target.writeText(json.toString())
                tmp.delete()
            }
            // Bump the mtime explicitly. On Android's emulated storage the rename keeps a STALE
            // mtime, and a SAF/ContentResolver reader (the companion app) caches file content
            // keyed on that mtime — so without this it keeps reading OLD export content until it
            // restarts and clears its resolver cache (the "IOB lags / needs app restart" bug).
            // Forcing the mtime makes every write visible to readers immediately.
            target.setLastModified(System.currentTimeMillis())
        }
    }
}

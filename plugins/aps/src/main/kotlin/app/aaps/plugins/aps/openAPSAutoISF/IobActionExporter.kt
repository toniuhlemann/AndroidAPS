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
 */
object IobActionExporter {

    private const val FILE_NAME = "iobaction_state.json"

    fun write(json: JSONObject) {
        runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs")
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            val target = File(dir, FILE_NAME)
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

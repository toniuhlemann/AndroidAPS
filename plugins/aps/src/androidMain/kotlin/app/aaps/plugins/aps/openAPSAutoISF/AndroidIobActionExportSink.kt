package app.aaps.plugins.aps.openAPSAutoISF

import android.content.Context
import android.content.Intent
import android.os.Environment
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Android side of the viewer exports: Documents/aapsLogs, the folder the autoISF logging writes to,
 * which the companion viewer can read.
 *
 * The JSON is handed over as a kotlinx object and re-built with org.json here, so the files carry
 * exactly the bytes the 3.4 fork wrote (org.json writes a whole double as `1`, kotlinx as `1.0`).
 * Every call is guarded: a failing export is logged and never reaches the loop.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class AndroidIobActionExportSink(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) : IobActionExportSink {

    private val dir: File get() = File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs")

    override fun writeState(state: JsonObject, smbs: List<IobActionExportSink.SmbIn>, factors: IobActionExportSink.Factors) {
        runCatching {
            val json = JSONObject(state.toString())
            val now = json.optLong("ts", System.currentTimeMillis())
            // Own guard, so a side file problem can never break the export.
            val history = runCatching { IobActionSmbHistory.buildAndPersist(dir, now, smbs, factors) }.getOrNull() ?: JSONArray()
            json.put("smbHistory", history)
            IobActionFiles.writeAtomically(dir, STATE_FILE, json.toString(), bumpMtime = true)
        }.onFailure { aapsLogger.error(LTag.APS, "IobAction state export failed", it) }
    }

    override fun writeCore(core: JsonObject) {
        runCatching { IobActionFiles.writeAtomically(dir, CORE_FILE, JSONObject(core.toString()).toString(), bumpMtime = true) }
            .onFailure { aapsLogger.error(LTag.APS, "IobAction core export failed", it) }
    }

    override fun writeConfig(config: JsonObject) {
        runCatching { IobActionFiles.writeAtomically(dir, CONFIG_FILE, JSONObject(config.toString()).toString(), bumpMtime = true) }
            .onFailure { aapsLogger.error(LTag.APS, "IobAction config export failed", it) }
    }

    /**
     * An explicit broadcast to the viewer package only: "read now". No data in it except the cycle
     * time stamp - the data goes through the files. A broadcast faked by someone else can at most
     * cause one extra file read in the viewer.
     */
    override fun loopCycleWritten(cycleTs: Long) {
        runCatching {
            context.sendBroadcast(Intent(ACTION_LOOP_CYCLE_WRITTEN).apply {
                setPackage(VIEWER_PACKAGE)
                putExtra("cycleTs", cycleTs)
            })
        }.onFailure { aapsLogger.error(LTag.APS, "IobAction loop signal failed", it) }
    }

    private companion object {

        const val STATE_FILE = "iobaction_state.json"
        const val CORE_FILE = "iobaction_core.json"
        const val CONFIG_FILE = "iobaction_config.json"
        const val VIEWER_PACKAGE = "de.toniuhlemann.iobactionnativeviewer"
        const val ACTION_LOOP_CYCLE_WRITTEN = "de.toniuhlemann.iobactionnativeviewer.LOOP_CYCLE_WRITTEN"
    }
}

/** Atomic file writes for the exports: temp file + rename, so a reader never sees half a file. */
internal object IobActionFiles {

    fun writeAtomically(dir: File, fileName: String, text: String, bumpMtime: Boolean) {
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeText(text)
        val target = File(dir, fileName)
        if (!tmp.renameTo(target)) {
            // Fallback if the rename in the same folder failed for any reason.
            target.writeText(text)
            tmp.delete()
        }
        // On Android's emulated storage the rename keeps an OLD mtime, and the viewer's content
        // resolver caches the content keyed on that mtime - it would keep reading old content until it
        // restarts. A fresh mtime makes every write visible at once.
        if (bumpMtime) target.setLastModified(System.currentTimeMillis())
    }
}

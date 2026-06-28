package app.aaps.plugins.aps.openAPSAutoISF

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SMB attribution history for the IOB Action native viewer (Option b).
 *
 * The viewer used to read delivered SMBs by tail-parsing AndroidAPS.log and then attribute each
 * one to the *nearest* autoISF factor sample (±6 min) reconstructed from a separate log line.
 * That couples the viewer to the verbose glucose/events log and only ever yields an approximate
 * dominant-weight bucket.
 *
 * Here we do it at the source, exactly:
 *  - SMB timestamps + units come from the bolus DB (authoritative, no missed SMBs, survives
 *    reboots) — passed in by the caller.
 *  - The autoISF factors that DROVE each SMB are stamped at delivery: a brand-new SMB seen this
 *    cycle is stamped with THIS determineBasal cycle's factors (the deciding cycle). Already-known
 *    SMBs keep their original stamp. This is exact, not a nearest-time guess.
 *
 * The stamps are remembered in a small side file ([FILE_NAME], same dir as the exports) so the
 * factor attribution survives across cycles; only the rolling [WINDOW_MS] window is kept.
 *
 * SAFETY: write-only, fully guarded, never reads/affects dosing. Called from the determineBasal
 * export block (itself wrapped in runCatching). Returns a JSONArray to embed in the export; on any
 * failure it still returns whatever it could compute (never throws into the export block).
 */
object IobActionSmbHistory {

    private const val FILE_NAME = "iobaction_smb_history.json"
    private const val WINDOW_MS = 6 * 60 * 60 * 1000L

    /** Current-cycle autoISF factors to stamp onto newly seen SMBs. Non-finite values pass as null. */
    data class Factors(
        val acce: Double?,
        val bg: Double?,
        val pp: Double?,
        val dura: Double?,
        val final: Double?,
    )

    /** One delivered SMB from the bolus DB (authoritative timestamp + units). */
    data class SmbIn(val tsMs: Long, val units: Double)

    /**
     * Reconcile DB SMBs with remembered factor stamps, stamp new ones with [current], persist, and
     * return a JSONArray of {ts, units, acce, bg, pp, dura, final} for the last [WINDOW_MS], oldest
     * first.
     */
    fun buildAndPersist(nowMs: Long, dbSmbs: List<SmbIn>, current: Factors): JSONArray {
        val cutoff = nowMs - WINDOW_MS
        // Remembered stamps keyed by exact SMB timestamp (DB timestamps are stable → exact match).
        val remembered = readStamps()
        val merged = LinkedHashMap<Long, Stamp>()
        for (smb in dbSmbs.sortedBy { it.tsMs }) {
            if (smb.tsMs < cutoff) continue
            val prev = remembered[smb.tsMs]
            merged[smb.tsMs] = if (prev != null) {
                // Known SMB → keep its original (deciding-cycle) factor stamp, refresh units.
                prev.copy(units = smb.units)
            } else {
                // New SMB delivered this cycle → stamp with the current cycle's factors.
                Stamp(smb.tsMs, smb.units, current.acce, current.bg, current.pp, current.dura, current.final)
            }
        }
        persist(merged.values)
        return JSONArray().apply {
            merged.values.forEach { s ->
                put(JSONObject().apply {
                    put("ts", s.tsMs)
                    put("units", s.units)
                    put("acce", s.acce?.takeIf { it.isFinite() })
                    put("bg", s.bg?.takeIf { it.isFinite() })
                    put("pp", s.pp?.takeIf { it.isFinite() })
                    put("dura", s.dura?.takeIf { it.isFinite() })
                    put("final", s.final?.takeIf { it.isFinite() })
                })
            }
        }
    }

    private data class Stamp(
        val tsMs: Long,
        val units: Double,
        val acce: Double?,
        val bg: Double?,
        val pp: Double?,
        val dura: Double?,
        val final: Double?,
    )

    private fun file(): File = File(File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs"), FILE_NAME)

    private fun readStamps(): Map<Long, Stamp> = runCatching {
        val f = file()
        if (!f.exists()) return emptyMap()
        val arr = JSONObject(f.readText()).optJSONArray("samples") ?: return emptyMap()
        val out = HashMap<Long, Stamp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ts = o.optLong("ts", 0L)
            if (ts <= 0L) continue
            out[ts] = Stamp(
                tsMs = ts,
                units = o.optDouble("units", 0.0),
                acce = o.optDoubleOrNull("acce"),
                bg = o.optDoubleOrNull("bg"),
                pp = o.optDoubleOrNull("pp"),
                dura = o.optDoubleOrNull("dura"),
                final = o.optDoubleOrNull("final"),
            )
        }
        out
    }.getOrDefault(emptyMap())

    private fun persist(stamps: Collection<Stamp>) {
        runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs")
            if (!dir.exists()) dir.mkdirs()
            val json = JSONObject().apply {
                put("samples", JSONArray().apply {
                    stamps.forEach { s ->
                        put(JSONObject().apply {
                            put("ts", s.tsMs)
                            put("units", s.units)
                            put("acce", s.acce?.takeIf { it.isFinite() })
                            put("bg", s.bg?.takeIf { it.isFinite() })
                            put("pp", s.pp?.takeIf { it.isFinite() })
                            put("dura", s.dura?.takeIf { it.isFinite() })
                            put("final", s.final?.takeIf { it.isFinite() })
                        })
                    }
                })
            }
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            val target = File(dir, FILE_NAME)
            if (!tmp.renameTo(target)) {
                target.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
}

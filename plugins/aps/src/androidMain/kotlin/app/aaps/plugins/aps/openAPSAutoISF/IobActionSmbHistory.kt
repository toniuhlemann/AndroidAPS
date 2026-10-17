package app.aaps.plugins.aps.openAPSAutoISF

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fork (IOB-Action viewer): SMB history with the exact autoISF factors that drove each SMB.
 *
 *  - SMB timestamps and units come from the bolus database (authoritative, survives reboots).
 *  - A brand new SMB seen in this cycle is stamped with THIS cycle's factors (the deciding cycle).
 *    SMBs already known keep their original stamp. This is exact, not a nearest-time guess.
 *
 * The stamps are remembered in a small side file next to the exports, so the attribution survives
 * across cycles; only the rolling [WINDOW_MS] window is kept. Write-only, never affects dosing.
 * Same format as the 3.4 fork.
 */
internal object IobActionSmbHistory {

    private const val FILE_NAME = "iobaction_smb_history.json"
    private const val WINDOW_MS = 6 * 60 * 60 * 1000L

    // Only an SMB delivered within this window of `now` is "this cycle" and gets the current factors.
    // An older SMB seen for the FIRST time (empty side file after a fresh start) pre-dates the
    // tracking - its factors are unknown and stay null instead of faking the current ones.
    private const val STAMP_WINDOW_MS = 10 * 60 * 1000L

    /**
     * Reconciles the database SMBs with the remembered stamps, stamps new ones with [current],
     * persists, and returns {ts, units, acce, bg, pp, dura, final} for the last [WINDOW_MS], oldest first.
     */
    fun buildAndPersist(dir: File, nowMs: Long, dbSmbs: List<IobActionExportSink.SmbIn>, current: IobActionExportSink.Factors): JSONArray {
        val cutoff = nowMs - WINDOW_MS
        val remembered = readStamps(dir)
        val merged = LinkedHashMap<Long, Stamp>()
        for (smb in dbSmbs.sortedBy { it.tsMs }) {
            if (smb.tsMs < cutoff) continue
            val prev = remembered[smb.tsMs]
            merged[smb.tsMs] = when {
                // Known SMB: keep its original (deciding cycle) stamp, refresh the units.
                prev != null                         -> prev.copy(units = smb.units)
                // New SMB delivered this cycle: stamp it with the current cycle's factors.
                smb.tsMs >= nowMs - STAMP_WINDOW_MS -> Stamp(smb.tsMs, smb.units, current.acce, current.bg, current.pp, current.dura, current.final)
                // Older SMB first seen after a fresh start: the driver is unknown.
                else                                 -> Stamp(smb.tsMs, smb.units, null, null, null, null, null)
            }
        }
        persist(dir, merged.values)
        return JSONArray().apply { merged.values.forEach { put(it.toJson()) } }
    }

    private data class Stamp(
        val tsMs: Long,
        val units: Double,
        val acce: Double?,
        val bg: Double?,
        val pp: Double?,
        val dura: Double?,
        val final: Double?,
    ) {

        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", tsMs)
            put("units", units)
            put("acce", acce?.takeIf { it.isFinite() })
            put("bg", bg?.takeIf { it.isFinite() })
            put("pp", pp?.takeIf { it.isFinite() })
            put("dura", dura?.takeIf { it.isFinite() })
            put("final", final?.takeIf { it.isFinite() })
        }
    }

    private fun readStamps(dir: File): Map<Long, Stamp> = runCatching {
        val f = File(dir, FILE_NAME)
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

    private fun persist(dir: File, stamps: Collection<Stamp>) {
        runCatching {
            val json = JSONObject().apply { put("samples", JSONArray().apply { stamps.forEach { put(it.toJson()) } }) }
            IobActionFiles.writeAtomically(dir, FILE_NAME, json.toString(), bumpMtime = false)
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
}

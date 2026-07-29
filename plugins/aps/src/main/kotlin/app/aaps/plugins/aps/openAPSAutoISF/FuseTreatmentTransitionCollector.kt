package app.aaps.plugins.aps.openAPSAutoISF

import android.os.Environment
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * FUSE P-1.0: treatment TRANSITION collector (manifest v0.2 section 4.2).
 *
 * Records every observed state change of bolus rows (BS) as append-only JSONL so the offline
 * ledger audit can prove the temporaryId -> pumpId resolution path, partial deliveries and
 * retroactive amount/reference changes. A last-state snapshot alone cannot show these
 * transitions - that is exactly why this collector exists (Codex R7 section 3.3).
 *
 * Output: <ext>/Documents/aapsLogs/fuse_treatment_transition_history.jsonl
 *
 * SAFETY (Toni's flash condition: zero impact on loop calculations and SMB interval delivery):
 *  - NOT wired into DetermineBasal*, LoopPlugin, CommandQueue or any pump driver. Called only
 *    from MainApp's 60s widget heartbeat, which runs on its own HandlerThread - the same
 *    thread/pattern as [IobActionCoreExporter] (proven since 2026-06-26).
 *  - One bounded Room READ per minute (26h window, row cap). SQLite/Room runs in WAL mode:
 *    readers never block the writer, so concurrent treatment inserts by the loop are unaffected.
 *  - Write-only append of a few hundred bytes on the same background thread. No locks shared
 *    with dosing, no rxBus subscription, nothing is ever read back into the APS path.
 *  - The whole tick is runCatching-isolated: any failure affects only this tick.
 *
 * PRIVACY: `notes` is never exported; the pump serial only as a truncated SHA-256 hash.
 *
 * Semantics:
 *  - obs=baseline: first pass after process start (idempotent re-emission of current states;
 *    consumers dedupe on rowId+version+stateFingerprint).
 *  - obs=new:      row first seen after the baseline pass.
 *  - obs=change:   fingerprint of a known row changed (e.g. temporaryId resolved to pumpId,
 *                  amount corrected after partial delivery, invalidation, NS id arrival).
 *  - Known gap (documented, accepted): a row synced in with a timestamp older than the 26h
 *    window is never observed. Window >= 24h keeps that case rare.
 *  - A torn last line after a crash is possible (plain append); JSONL consumers must skip
 *    unparseable trailing lines.
 */
object FuseTreatmentTransitionCollector {

    private const val FILE_NAME = "fuse_treatment_transition_history.jsonl"
    private const val SCHEMA_VERSION = 1
    private const val WINDOW_MS = 26L * 60L * 60L * 1000L      // 26 h look-back
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L     // rotate to .1 at 16 MB
    private const val MAX_ROWS_PER_TICK = 2000                 // hard bound per query

    /** rowId -> last emitted state fingerprint. Only touched on the heartbeat thread. */
    private val lastSeen = HashMap<Long, String>()
    private var baselineEmitted = false
    private val serialHashCache = HashMap<String, String>()

    /** Called from MainApp's 60s heartbeat. Fully isolated; never throws to the caller. */
    fun tick(persistenceLayer: PersistenceLayer, nowMs: Long) {
        runCatching {
            val rows = persistenceLayer
                .getBolusesFromTimeIncludingInvalid(nowMs - WINDOW_MS, true)
                .blockingGet()
                .take(MAX_ROWS_PER_TICK)
            val firstPass = !baselineEmitted
            val seenIds = HashSet<Long>(rows.size * 2)
            val sb = StringBuilder()
            for (b in rows) {
                seenIds.add(b.id)
                val fp = fingerprint(b)
                if (lastSeen[b.id] == fp) continue
                val obs = when {
                    firstPass               -> "baseline"
                    lastSeen[b.id] == null  -> "new"
                    else                    -> "change"
                }
                lastSeen[b.id] = fp
                sb.append(line(b, fp, obs, nowMs)).append('\n')
            }
            // Bound the memory map: drop rows that left the 26h window.
            lastSeen.keys.retainAll(seenIds)
            baselineEmitted = true
            if (sb.isNotEmpty()) append(sb.toString())
        }
    }

    /** Content fingerprint. Includes `version` so ANY DB bump (even on fields we do not export,
     *  e.g. notes) surfaces as a transition - required by the manifest dedupe key. */
    private fun fingerprint(b: BS): String {
        val s = StringBuilder()
            .append(b.version).append('|')
            .append(b.timestamp).append('|')
            .append(b.amount).append('|')
            .append(b.type.name).append('|')
            .append(b.isValid).append('|')
            .append(b.isBasalInsulin).append('|')
            .append(b.referenceId ?: -1L).append('|')
            .append(b.ids.temporaryId ?: -1L).append('|')
            .append(b.ids.pumpId ?: -1L).append('|')
            .append(b.ids.pumpType?.name ?: "-").append('|')
            .append(b.ids.pumpSerial ?: "-").append('|')
            .append(b.ids.nightscoutId != null)
            .toString()
        return sha256(s).substring(0, 12)
    }

    private fun line(b: BS, fp: String, obs: String, nowMs: Long): String {
        val ids = JSONObject().apply {
            b.ids.temporaryId?.let { put("temporaryId", it) }
            b.ids.pumpId?.let { put("pumpId", it) }
            b.ids.pumpType?.let { put("pumpType", it.name) }
            b.ids.pumpSerial?.let { put("pumpSerialHash", serialHash(it)) }
            put("nightscoutIdPresent", b.ids.nightscoutId != null)
        }
        return JSONObject().apply {
            put("v", SCHEMA_VERSION)
            put("obs", obs)
            put("observedAtUtc", nowMs)
            put("rowId", b.id)
            put("version", b.version)
            put("dateCreated", b.dateCreated)
            b.referenceId?.let { put("referenceId", it) }
            put("timestamp", b.timestamp)
            // NaN would make JSONObject.put THROW and silently drop the whole line batch
            // (known exporter gotcha) - guard even though amounts should always be finite.
            if (b.amount.isFinite()) put("amount", b.amount)
            put("type", b.type.name)
            put("isValid", b.isValid)
            put("isBasalInsulin", b.isBasalInsulin)
            put("ids", ids)
            put("isPumpHistory", b.ids.isPumpHistory())
            put("stateFingerprint", fp)
        }.toString()
    }

    private fun serialHash(serial: String): String =
        serialHashCache.getOrPut(serial) { sha256(serial).substring(0, 16) }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun append(text: String) {
        val dir = File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, FILE_NAME)
        if (target.length() > MAX_FILE_BYTES) {
            val backup = File(dir, "$FILE_NAME.1")
            if (backup.exists()) backup.delete()
            target.renameTo(backup)
        }
        FileOutputStream(File(dir, FILE_NAME), true).use { it.write(text.toByteArray()) }
    }
}

package app.aaps.plugins.aps.openAPSAutoISF

import android.os.Environment
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * FUSE P-1.0: treatment TRANSITION collector, v2 (manifest v0.3 section 4.2, Codex R9 F1/F2).
 *
 * Records every state a bolus row (BS) ever had as append-only JSONL so the offline ledger
 * audit can prove the temporaryId -> pumpId resolution path, partial deliveries and
 * retroactive amount/reference changes.
 *
 * v2 source (R9/F1 fix): a monotone dateCreated cursor over
 * [PersistenceLayer.collectNewBolusEntriesSince], which INCLUDES historic rows
 * (referenceId != null). AAPS' TraceableDao re-stamps the current row's dateCreated on every
 * update and inserts the OLD state as a historic copy keeping its ORIGINAL dateCreated —
 * therefore every intermediate version v_i with dateCreated t_i is visible to the cursor
 * exactly once: as the current row (if still newest) or as a historic copy. Multiple versions
 * between two 60s ticks are all delivered; nothing is lost.
 *
 * v2 commit order (R9/F2 fix): lines are built first, appended to the file, and ONLY on a
 * successful append are dedupe keys, cursor and cursor file advanced. A failed append leaves
 * all state untouched, so the next tick retries the same transitions.
 *
 * Output:  <ext>/Documents/aapsLogs/fuse_treatment_transition_history.jsonl   (data, JSONL)
 *          <ext>/Documents/aapsLogs/fuse_transition_cursor.json               (cursor, atomic)
 *          <ext>/Documents/aapsLogs/fuse_transition_diag.json                 (R9/F5 diag, atomic)
 *
 * SAFETY (Toni's flash condition: zero impact on loop calculations and SMB interval delivery):
 *  - NOT wired into DetermineBasal*, LoopPlugin, CommandQueue or any pump driver. Called only
 *    from MainApp's 60s widget heartbeat on its own HandlerThread — the same thread/pattern as
 *    [IobActionCoreExporter] (proven since 2026-06-26).
 *  - Per tick: bounded, paginated Room READS on the bolus table only (page 200, hard cap 2000)
 *    plus one small file append. No shared APS/queue lock, no rxBus subscription, nothing is
 *    read back into the APS path. Observed isolation, not an absolute latency guarantee
 *    (R9/F5) — tickDurationMs in the diag file makes the real cost measurable.
 *  - The whole tick is runCatching-isolated: any failure affects only this tick.
 *
 * PRIVACY: `notes` is never exported. The pump serial appears only as an UNSALTED SHA-256
 * truncated to 16 hex chars — a correlation pseudonym (stable join key across lines), NOT
 * secrecy protection against brute force of a low-entropy serial space (R9/F4, documented).
 *
 * Line semantics (schemaVersion 2):
 *  - rowId          canonical treatment id = referenceId ?: id (stable across all versions)
 *  - physicalRowId  the physical DB row this state was read from
 *  - obs=baseline   first pass ever (no cursor file): current backlog states
 *  - obs=current    state read from the current row (referenceId == null)
 *  - obs=historic   state read from a historic copy (intermediate version)
 *  - stateFingerprint  FULL 64-hex SHA-256 over the exported content fields incl. version
 *  - Lines are emitted sorted by (rowId, version, physicalRowId).
 *  - After a process restart the overlap window may re-emit recent lines; consumers dedupe on
 *    (physicalRowId, version, stateFingerprint). A torn last line after a crash is possible
 *    (plain append); JSONL consumers must skip unparseable trailing lines.
 *  - Known gap (accepted, documented): wall-clock dateCreated. A clock jump backwards larger
 *    than OVERLAP_MS could hide states created in the jumped-over span.
 */
object FuseTreatmentTransitionCollector {

    private const val FILE_NAME = "fuse_treatment_transition_history.jsonl"
    private const val CURSOR_FILE_NAME = "fuse_transition_cursor.json"
    private const val DIAG_FILE_NAME = "fuse_transition_diag.json"
    private const val SCHEMA_VERSION = 2
    private const val BASELINE_LOOKBACK_MS = 26L * 60L * 60L * 1000L  // first run: 26 h
    private const val OVERLAP_MS = 10L * 60L * 1000L                  // clock-jump / restart overlap
    private const val PAGE_SIZE = 200
    private const val MAX_ROWS_PER_TICK = 2000                        // hard bound per tick
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L            // rotate to .1 at 16 MB
    private const val MAX_SEEN_KEYS = 4096                            // dedupe memory bound

    /** Dedupe keys "physId|version|fingerprint" — only touched on the heartbeat thread. */
    private val seenKeys = LinkedHashSet<String>()
    private var cursorMs: Long = -1L
    private var cursorLoaded = false

    /** Injectable sinks/sources so the F1/F2 behavior is unit-testable without Android. */
    internal var appendSink: (String) -> Unit = { text -> appendToFile(text) }
    internal var cursorStore: CursorStore = FileCursorStore

    internal interface CursorStore {

        fun load(): Long?
        fun save(cursorMs: Long)
    }

    /** Result of the pure planning step — nothing is committed until the append succeeded. */
    internal data class Plan(
        val lines: List<String>,
        val newKeys: List<String>,
        val newCursorMs: Long,
        val rowsScanned: Int,
        val truncated: Boolean
    )

    /** Called from MainApp's 60s heartbeat. Fully isolated; never throws to the caller. */
    fun tick(persistenceLayer: PersistenceLayer, nowMs: Long) {
        val started = System.nanoTime()
        var errorClass: String? = null
        var plan: Plan? = null
        runCatching {
            if (!cursorLoaded) {
                cursorMs = cursorStore.load() ?: -1L
                cursorLoaded = true
            }
            val firstPass = cursorMs < 0L
            val since = if (firstPass) nowMs - BASELINE_LOOKBACK_MS else cursorMs - OVERLAP_MS
            val rows = fetchPaged(persistenceLayer, since, nowMs)
            val p = plan(rows, seenKeys, cursorMs, firstPass, nowMs)
            plan = p
            if (p.lines.isNotEmpty()) appendSink(p.lines.joinToString("\n", postfix = "\n"))
            // R9/F2: commit ONLY after the append returned without throwing.
            p.newKeys.forEach { seenKeys.add(it) }
            while (seenKeys.size > MAX_SEEN_KEYS) seenKeys.remove(seenKeys.first())
            if (p.newCursorMs > cursorMs) {
                cursorMs = p.newCursorMs
                cursorStore.save(cursorMs)
            } else if (firstPass && !p.truncated) {
                // Empty, complete first window: persist a cursor so the baseline pass ends.
                // (A truncated first pass keeps cursor < 0 and re-sweeps next tick.)
                cursorMs = nowMs
                cursorStore.save(cursorMs)
            }
        }.onFailure { errorClass = it.javaClass.simpleName }
        writeDiag(nowMs, started, plan, errorClass)
    }

    private fun fetchPaged(persistenceLayer: PersistenceLayer, since: Long, until: Long): List<BS> {
        val out = ArrayList<BS>()
        var offset = 0
        while (out.size < MAX_ROWS_PER_TICK) {
            val page = persistenceLayer.collectNewBolusEntriesSince(since, until, PAGE_SIZE, offset)
            out.addAll(page)
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return out
    }

    /** Pure planning: which lines to write, which keys/cursor to commit on success.
     *
     *  Cursor safety with an ORDER-BY-less paginated source: the cursor advances to the max
     *  dateCreated ONLY when the sweep completed (last page short). On a truncated sweep the
     *  cursor stays — keys are still committed, so the next tick's identical scan skips the
     *  already-written lines and continues. For the bolus table the cap (2000) is far above
     *  any realistic 26h backlog; a truncation is surfaced via rowsScanned in the diag file. */
    internal fun plan(rows: List<BS>, alreadySeen: Set<String>, cursor: Long, firstPass: Boolean, nowMs: Long): Plan {
        val truncated = rows.size >= MAX_ROWS_PER_TICK
        val sorted = rows.sortedWith(
            compareBy({ it.referenceId ?: it.id }, { it.version }, { it.id })
        )
        val lines = ArrayList<String>()
        val newKeys = ArrayList<String>()
        val newKeySet = HashSet<String>()
        var maxDateCreated = cursor
        for (b in sorted) {
            if (b.dateCreated > maxDateCreated) maxDateCreated = b.dateCreated
            val fp = fingerprint(b)
            val key = "${b.id}|${b.version}|$fp"
            if (key in alreadySeen || !newKeySet.add(key)) continue
            val obs = when {
                firstPass             -> "baseline"
                b.referenceId != null -> "historic"
                else                  -> "current"
            }
            newKeys.add(key)
            lines.add(line(b, fp, obs, nowMs))
        }
        return Plan(lines, newKeys, if (truncated) cursor else maxDateCreated, rows.size, truncated)
    }

    /** FULL 64-hex SHA-256 (R9/F4) over the exported content fields, version included, so ANY
     *  DB version bump (even on fields we do not export, e.g. notes) surfaces as a new state. */
    internal fun fingerprint(b: BS): String {
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
        return sha256(s)
    }

    internal fun line(b: BS, fp: String, obs: String, nowMs: Long): String {
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
            put("rowId", b.referenceId ?: b.id)      // canonical treatment id (R9/F1)
            put("physicalRowId", b.id)
            put("version", b.version)
            put("dateCreated", b.dateCreated)
            b.referenceId?.let { put("referenceId", it) }
            put("timestamp", b.timestamp)
            // NaN would make JSONObject.put THROW and drop the whole batch (known exporter
            // gotcha) — guard even though amounts should always be finite.
            if (b.amount.isFinite()) put("amount", b.amount)
            put("type", b.type.name)
            put("isValid", b.isValid)
            put("isBasalInsulin", b.isBasalInsulin)
            put("ids", ids)
            put("isPumpHistory", b.ids.isPumpHistory())
            put("stateFingerprint", fp)
        }.toString()
    }

    private val serialHashCache = HashMap<String, String>()

    private fun serialHash(serial: String): String =
        serialHashCache.getOrPut(serial) { sha256(serial).substring(0, 16) }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // --- file sinks -----------------------------------------------------------------------

    private fun logsDir(): File =
        File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs").also { if (!it.exists()) it.mkdirs() }

    private fun appendToFile(text: String) {
        val dir = logsDir()
        val target = File(dir, FILE_NAME)
        if (target.length() > MAX_FILE_BYTES) {
            val backup = File(dir, "$FILE_NAME.1")
            if (backup.exists()) backup.delete()
            target.renameTo(backup)
        }
        FileOutputStream(File(dir, FILE_NAME), true).use { it.write(text.toByteArray()) }
    }

    private object FileCursorStore : CursorStore {

        override fun load(): Long? = runCatching {
            val f = File(File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs"), CURSOR_FILE_NAME)
            if (!f.exists()) null else JSONObject(f.readText()).optLong("cursorMs", -1L).takeIf { it > 0L }
        }.getOrNull()

        override fun save(cursorMs: Long) {
            val dir = File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs")
            val tmp = File(dir, "$CURSOR_FILE_NAME.tmp")
            tmp.writeText(JSONObject().put("cursorMs", cursorMs).toString())
            val target = File(dir, CURSOR_FILE_NAME)
            if (!tmp.renameTo(target)) {
                target.writeText(JSONObject().put("cursorMs", cursorMs).toString())
                tmp.delete()
            }
        }
    }

    /** R9/F5: small atomic diagnostics file — observability only, never read back anywhere. */
    private fun writeDiag(nowMs: Long, startedNanos: Long, plan: Plan?, errorClass: String?) {
        runCatching {
            val dir = logsDir()
            val json = JSONObject().apply {
                put("ts", nowMs)
                put("tickDurationMs", (System.nanoTime() - startedNanos) / 1_000_000)
                put("rowsScanned", plan?.rowsScanned ?: -1)
                put("rowsAppended", plan?.lines?.size ?: -1)
                put("cursorMs", cursorMs)
                put("seenKeys", seenKeys.size)
                errorClass?.let { put("lastErrorClass", it) }
            }
            val tmp = File(dir, "$DIAG_FILE_NAME.tmp")
            tmp.writeText(json.toString())
            val target = File(dir, DIAG_FILE_NAME)
            if (!tmp.renameTo(target)) {
                target.writeText(json.toString())
                tmp.delete()
            }
        }
    }
}

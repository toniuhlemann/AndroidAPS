package app.aaps.plugins.aps.openAPSAutoISF

import android.os.Environment
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * FUSE P-1.0: treatment TRANSITION collector, v4 (manifest v0.4 section 4.2; R9 F1/F2,
 * R11 F6/F7).
 *
 * Records every state a bolus row (BS) ever had as append-only JSONL so the offline ledger
 * audit can prove the temporaryId -> pumpId resolution path, partial deliveries and
 * retroactive amount/reference changes.
 *
 * TWO cursors (R9/F1 + dc=-1 quirk): AAPS' pump-sync insert path
 * (InsertBolusWithTempIdTransaction) uses the RAW insert() and leaves dateCreated = -1 on the
 * fresh row — and the historic copy created by the later temporaryId->pumpId update INHERITS
 * that -1. A dateCreated cursor alone can therefore never see the temporaryId-only
 * (TEMP_PENDING) state.
 *  - id cursor:  catches every NEW physical row (inserts AND historic copies, dc=-1 too)
 *  - dc cursor:  catches in-place updates of current rows (dateCreated is re-stamped)
 * Together every state change is one of the two.
 *
 * R11/F7: the dateCreated dimension uses deterministic KEYSET pagination
 * (ORDER BY dateCreated, id; continuation via the tuple). A sweep larger than the per-tick
 * row cap persists its progress (sweepUntil/lastDc/lastId) and RESUMES behind the last
 * processed row next tick; the main dc cursor advances only after the sweep completed.
 *
 * R11/F6 identity contract (manifest v0.4):
 *  - transportKey    = physicalRowId | version | stateFingerprint   (internal dedupe; a
 *                      current row and its later historic copy are both exported once)
 *  - logicalStateKey = rowId | version | stateFingerprint           (consumer folds both
 *                      rows of the same logical state into ONE ledger state)
 *  - stateFingerprint is SEMANTIC: it does NOT include physicalRowId, referenceId, obs or
 *    observedAtUtc — a current v0 and its historic v0 copy therefore share the fingerprint.
 *    referenceId/physicalRowId stay in the line as provenance.
 *
 * v2 commit order (R9/F2): lines are appended first; dedupe keys, cursors and the cursor
 * file advance only after a successful append. A failed append retries identically next tick.
 *
 * Output:  <ext>/Documents/aapsLogs/fuse_treatment_transition_history.jsonl   (data, JSONL)
 *          <ext>/Documents/aapsLogs/fuse_transition_cursor.json               (cursors, atomic)
 *          <ext>/Documents/aapsLogs/fuse_transition_diag.json                 (diag, atomic)
 *
 * SAFETY (Toni's flash condition: zero impact on loop calculations and SMB interval delivery):
 *  - NOT wired into DetermineBasal*, LoopPlugin, CommandQueue or any pump driver. Called only
 *    from MainApp's 60s widget heartbeat on its own HandlerThread — the same thread/pattern as
 *    [IobActionCoreExporter] (proven since 2026-06-26).
 *  - Per tick: bounded, keyset-paginated Room READS on the bolus table only plus one small
 *    file append. No shared APS/queue lock, no rxBus subscription, nothing is read back into
 *    the APS path. Observed isolation, not an absolute latency guarantee — tickDurationMs in
 *    the diag file makes the real cost measurable (change ticks are ~100-300 ms on this
 *    device, idle ticks single-digit).
 *  - The whole tick is runCatching-isolated: any failure affects only this tick.
 *
 * PRIVACY: `notes` is never exported. The pump serial appears only as an UNSALTED SHA-256
 * truncated to 16 hex chars — a correlation pseudonym (stable join key across lines), NOT
 * secrecy protection against brute force of a low-entropy serial space.
 *
 * Line semantics (schemaVersion 3 — fingerprint semantics changed vs v2, consumers must
 * branch on `v`):
 *  - rowId          canonical treatment id = referenceId ?: id (stable across all versions)
 *  - physicalRowId  the physical DB row this state was read from
 *  - obs=baseline   first pass ever (no cursor file): current backlog states
 *  - obs=current    state read from the current row (referenceId == null)
 *  - obs=historic   state read from a historic copy (intermediate version)
 *  - After a process restart the overlap window may re-emit recent lines; consumers dedupe on
 *    the transportKey and fold on the logicalStateKey. A torn last line after a crash is
 *    possible (plain append); JSONL consumers must skip unparseable trailing lines.
 *  - dateCreated is NOT an insert time for pump-sync rows (raw-insert quirk, may be -1).
 *  - Known gap (accepted, documented): wall-clock dateCreated. A clock jump backwards larger
 *    than OVERLAP_MS could hide in-place updates in the jumped-over span (new rows are still
 *    caught by the id cursor).
 */
object FuseTreatmentTransitionCollector {

    private const val FILE_NAME = "fuse_treatment_transition_history.jsonl"
    private const val CURSOR_FILE_NAME = "fuse_transition_cursor.json"
    private const val DIAG_FILE_NAME = "fuse_transition_diag.json"
    private const val SCHEMA_VERSION = 3
    private const val BASELINE_LOOKBACK_MS = 26L * 60L * 60L * 1000L  // first run: 26 h
    private const val OVERLAP_MS = 10L * 60L * 1000L                  // clock-jump / restart overlap
    private const val PAGE_SIZE = 200
    private const val MAX_ROWS_PER_TICK = 2000                        // hard bound per tick
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L            // rotate to .1 at 16 MB
    private const val MAX_SEEN_KEYS = 4096                            // dedupe memory bound

    /** Transport-dedupe keys "physId|version|fingerprint" — heartbeat thread only. */
    private val seenKeys = LinkedHashSet<String>()
    private var cursorMs: Long = -1L
    private var idCursor: Long = -1L
    private var sweep: Sweep? = null
    private var cursorLoaded = false

    internal data class Sweep(val until: Long, val lastDc: Long, val lastId: Long)
    internal data class Cursors(val cursorMs: Long, val afterId: Long, val sweep: Sweep? = null)

    /** Injectable sinks/sources so the F1/F2/F6/F7 behavior is unit-testable without Android.
     *  diagSink included: the Environment stub returns null under unit tests, which would
     *  otherwise turn the diag path RELATIVE and drop a stray file into the module dir. */
    internal var appendSink: (String) -> Unit = { text -> appendToFile(text) }
    internal var cursorStore: CursorStore = FileCursorStore
    internal var diagSink: (JSONObject) -> Unit = { json -> writeDiagFile(json) }

    internal interface CursorStore {

        fun load(): Cursors?
        fun save(cursors: Cursors)
    }

    /** Result of the pure planning step — nothing is committed until the append succeeded. */
    internal data class Plan(
        val lines: List<String>,
        val newKeys: List<String>,
        val rowsScanned: Int
    )

    /** Called from MainApp's 60s heartbeat. Fully isolated; never throws to the caller. */
    fun tick(persistenceLayer: PersistenceLayer, nowMs: Long) {
        val started = System.nanoTime()
        var errorClass: String? = null
        var plan: Plan? = null
        runCatching {
            if (!cursorLoaded) {
                val c = cursorStore.load()
                cursorMs = c?.cursorMs ?: -1L
                idCursor = c?.afterId ?: -1L
                sweep = c?.sweep
                cursorLoaded = true
            }
            val firstPass = cursorMs < 0L && sweep == null

            // --- dc dimension: keyset sweep (R11/F7) -----------------------------------
            val activeSweep = sweep
            val sweepUntil: Long
            var lastDc: Long
            var lastId: Long
            if (activeSweep != null) {
                sweepUntil = activeSweep.until
                lastDc = activeSweep.lastDc
                lastId = activeSweep.lastId
            } else {
                sweepUntil = nowMs
                lastDc = if (firstPass) nowMs - BASELINE_LOOKBACK_MS else cursorMs - OVERLAP_MS
                lastId = Long.MAX_VALUE   // sentinel: only dateCreated > lastDc matches
            }
            val dcRows = ArrayList<BS>()
            var sweepComplete = false
            while (dcRows.size < MAX_ROWS_PER_TICK) {
                val page = persistenceLayer.collectNewBolusEntriesKeyset(lastDc, lastId, sweepUntil, PAGE_SIZE)
                if (page.isEmpty()) { sweepComplete = true; break }
                dcRows.addAll(page)
                lastDc = page.last().dateCreated
                lastId = page.last().id
                if (page.size < PAGE_SIZE) { sweepComplete = true; break }
            }

            // --- id dimension ----------------------------------------------------------
            var idFetchLast = idCursor
            val idRows = ArrayList<BS>()
            if (idCursor >= 0L) {
                while (idRows.size < MAX_ROWS_PER_TICK) {
                    val page = persistenceLayer.collectBolusRowsAfterId(idFetchLast, PAGE_SIZE)
                    if (page.isEmpty()) break
                    idRows.addAll(page)
                    idFetchLast = page.last().id
                    if (page.size < PAGE_SIZE) break
                }
            }

            val p = plan(dcRows + idRows, seenKeys, firstPass, nowMs)
            plan = p
            if (p.lines.isNotEmpty()) appendSink(p.lines.joinToString("\n", postfix = "\n"))

            // R9/F2: commit ONLY after the append returned without throwing.
            p.newKeys.forEach { seenKeys.add(it) }
            while (seenKeys.size > MAX_SEEN_KEYS) seenKeys.remove(seenKeys.first())
            if (sweepComplete) {
                // The ordered keyset sweep provably covered everything with
                // dateCreated <= sweepUntil — advance the main dc cursor, close the sweep.
                cursorMs = sweepUntil
                sweep = null
            } else {
                // Cap hit: freeze the sweep and resume BEHIND the last processed row next
                // tick (R11/F7). Main dc cursor stays until the sweep completes.
                sweep = Sweep(sweepUntil, lastDc, lastId)
            }
            if (idCursor < 0L) {
                // One-time id-cursor initialization from whatever this tick saw. Historic
                // dc=-1 copies OLDER than this point stay unexported (documented one-time
                // gap; the backlog is Development/Calibration material anyway).
                val maxSeen = (dcRows + idRows).maxOfOrNull { it.id }
                if (maxSeen != null) idCursor = maxSeen
            } else if (idFetchLast > idCursor) {
                idCursor = idFetchLast
            }
            cursorStore.save(Cursors(cursorMs, idCursor, sweep))
        }.onFailure { errorClass = it.javaClass.simpleName }
        writeDiag(nowMs, started, plan, errorClass)
    }

    /** Pure planning: which lines to write, which transport keys to commit on success. */
    internal fun plan(rows: List<BS>, alreadySeen: Set<String>, firstPass: Boolean, nowMs: Long): Plan {
        val sorted = rows.sortedWith(
            compareBy({ it.referenceId ?: it.id }, { it.version }, { it.id })
        )
        val lines = ArrayList<String>()
        val newKeys = ArrayList<String>()
        val newKeySet = HashSet<String>()
        for (b in sorted) {
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
        return Plan(lines, newKeys, rows.size)
    }

    /** SEMANTIC state fingerprint (R11/F6): full 64-hex SHA-256 over the content fields plus
     *  version. Deliberately EXCLUDES physicalRowId and referenceId, so a current version and
     *  its later historic copy share the fingerprint and fold into one logical state via
     *  rowId|version|stateFingerprint. Version included so any DB bump (even on fields we do
     *  not export, e.g. notes) surfaces as a new state. */
    internal fun fingerprint(b: BS): String {
        val s = StringBuilder()
            .append(b.version).append('|')
            .append(b.timestamp).append('|')
            .append(b.amount).append('|')
            .append(b.type.name).append('|')
            .append(b.isValid).append('|')
            .append(b.isBasalInsulin).append('|')
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
            put("rowId", b.referenceId ?: b.id)      // canonical treatment id
            put("physicalRowId", b.id)
            put("version", b.version)
            put("dateCreated", b.dateCreated)
            b.referenceId?.let { put("referenceId", it) }
            put("timestamp", b.timestamp)
            // NaN would make JSONObject.put THROW and drop the whole line batch (known
            // exporter gotcha) — guard even though amounts should always be finite.
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

        override fun load(): Cursors? = runCatching {
            val f = File(File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs"), CURSOR_FILE_NAME)
            if (!f.exists()) null else JSONObject(f.readText()).let { json ->
                val ms = json.optLong("cursorMs", -1L)
                val sweepUntil = json.optLong("sweepUntil", -1L)
                val sw = if (sweepUntil > 0L) Sweep(sweepUntil, json.optLong("sweepDc", -1L), json.optLong("sweepId", -1L)) else null
                // A truncated FIRST sweep persists cursorMs = -1 with an active sweep — that
                // state must survive a restart, so only a file with NEITHER is treated as
                // absent. afterId tolerant: a v2-era file has no afterId -> -1 triggers the
                // one-time id-cursor initialization in tick().
                if (ms <= 0L && sw == null) null
                else Cursors(ms, json.optLong("afterId", -1L), sw)
            }
        }.getOrNull()

        override fun save(cursors: Cursors) {
            val dir = File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs")
            val json = JSONObject().put("cursorMs", cursors.cursorMs).put("afterId", cursors.afterId)
            cursors.sweep?.let {
                json.put("sweepUntil", it.until).put("sweepDc", it.lastDc).put("sweepId", it.lastId)
            }
            val payload = json.toString()
            val tmp = File(dir, "$CURSOR_FILE_NAME.tmp")
            tmp.writeText(payload)
            val target = File(dir, CURSOR_FILE_NAME)
            if (!tmp.renameTo(target)) {
                target.writeText(payload)
                tmp.delete()
            }
        }
    }

    /** Diagnostics file — observability only, never read back anywhere. */
    private fun writeDiag(nowMs: Long, startedNanos: Long, plan: Plan?, errorClass: String?) {
        runCatching {
            val json = JSONObject().apply {
                put("ts", nowMs)
                put("tickDurationMs", (System.nanoTime() - startedNanos) / 1_000_000)
                put("rowsScanned", plan?.rowsScanned ?: -1)
                put("rowsAppended", plan?.lines?.size ?: -1)
                put("cursorMs", cursorMs)
                put("afterId", idCursor)
                put("sweepActive", sweep != null)
                put("seenKeys", seenKeys.size)
                errorClass?.let { put("lastErrorClass", it) }
            }
            diagSink(json)
        }
    }

    private fun writeDiagFile(json: JSONObject) {
        val dir = logsDir()
        val tmp = File(dir, "$DIAG_FILE_NAME.tmp")
        tmp.writeText(json.toString())
        val target = File(dir, DIAG_FILE_NAME)
        if (!tmp.renameTo(target)) {
            target.writeText(json.toString())
            tmp.delete()
        }
    }
}

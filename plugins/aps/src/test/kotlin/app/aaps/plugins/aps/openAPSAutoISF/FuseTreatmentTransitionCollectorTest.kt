package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FUSE P-1.0 collector — R9 mandatory evidence as unit tests (F1 intermediate versions,
 * F2 append-before-state-commit, ordering, dedupe/idempotence, privacy, NaN guard).
 * Pure-logic tests: DB source and file sink are injected fakes.
 */
class FuseTreatmentTransitionCollectorTest {

    private fun bs(
        id: Long, version: Int, dateCreated: Long, referenceId: Long? = null,
        timestamp: Long = 1_000_000L, amount: Double = 0.05, isValid: Boolean = true,
        tempId: Long? = null, pumpId: Long? = null, serial: String? = null, nsId: String? = null,
        notes: String? = null
    ) = BS(
        id = id, version = version, dateCreated = dateCreated, isValid = isValid,
        referenceId = referenceId, timestamp = timestamp, amount = amount, type = BS.Type.SMB,
        notes = notes,
        ids = IDs(
            temporaryId = tempId, pumpId = pumpId,
            pumpType = if (serial != null) PumpType.MEDTRUM_NANO else null,
            pumpSerial = serial, nightscoutId = nsId
        )
    )

    // ---- R9 evidence 1+2: A->B->C entirely between two ticks, all states, in order --------

    @Test
    fun `intermediate versions between two ticks are all emitted in version order`() {
        // Current row id=10 carries v2 (state C, re-stamped dateCreated t3).
        // Historic copies id=21 (v0=A, t1) and id=22 (v1=B, t2) reference id=10.
        val rows = listOf(
            bs(id = 10, version = 2, dateCreated = 3000, tempId = 111, pumpId = 999, serial = "SN1", amount = 0.30),
            bs(id = 22, version = 1, dateCreated = 2000, referenceId = 10, tempId = 111, serial = "SN1", amount = 0.30),
            bs(id = 21, version = 0, dateCreated = 1000, referenceId = 10, tempId = 111, serial = "SN1", amount = 0.35)
        )
        val plan = FuseTreatmentTransitionCollector.plan(rows, emptySet(), cursor = 500, firstPass = false, nowMs = 9999)
        assertEquals(3, plan.lines.size)
        val versions = plan.lines.map { JSONObject(it).getInt("version") }
        assertEquals(listOf(0, 1, 2), versions)                       // v0 -> v1 -> v2 in order
        val rowIds = plan.lines.map { JSONObject(it).getLong("rowId") }.toSet()
        assertEquals(setOf(10L), rowIds)                              // canonical id for ALL versions
        // temporaryId->pumpId resolution + amount correction visible across the states:
        val first = JSONObject(plan.lines[0])
        val last = JSONObject(plan.lines[2])
        assertFalse(first.getJSONObject("ids").has("pumpId"))
        assertTrue(last.getJSONObject("ids").has("pumpId"))
        assertEquals(0.35, first.getDouble("amount"), 1e-9)
        assertEquals(0.30, last.getDouble("amount"), 1e-9)
        assertEquals(3000, plan.newCursorMs)
        assertEquals("historic", first.getString("obs"))
        assertEquals("current", last.getString("obs"))
    }

    // ---- dedupe / idempotence --------------------------------------------------------------

    @Test
    fun `already seen keys are not re-emitted (overlap idempotence)`() {
        val rows = listOf(bs(id = 10, version = 0, dateCreated = 1000, tempId = 111))
        val p1 = FuseTreatmentTransitionCollector.plan(rows, emptySet(), 500, firstPass = false, nowMs = 1)
        assertEquals(1, p1.lines.size)
        val p2 = FuseTreatmentTransitionCollector.plan(rows, p1.newKeys.toSet(), 1000, firstPass = false, nowMs = 2)
        assertEquals(0, p2.lines.size)
    }

    @Test
    fun `in-place update of current row (version bump) is emitted as change state`() {
        val v0 = bs(id = 10, version = 0, dateCreated = 1000, tempId = 111)
        val p1 = FuseTreatmentTransitionCollector.plan(listOf(v0), emptySet(), 500, false, 1)
        val v1 = bs(id = 10, version = 1, dateCreated = 2000, tempId = 111, pumpId = 999, serial = "SN1")
        val p2 = FuseTreatmentTransitionCollector.plan(listOf(v1), p1.newKeys.toSet(), 1000, false, 2)
        assertEquals(1, p2.lines.size)                                // new version = new key
        assertEquals(1, JSONObject(p2.lines[0]).getInt("version"))
    }

    // ---- truncation safety -----------------------------------------------------------------

    @Test
    fun `truncated sweep does not advance the cursor`() {
        val rows = (0 until 2000).map { bs(id = it.toLong() + 1, version = 0, dateCreated = 1000L + it) }
        val plan = FuseTreatmentTransitionCollector.plan(rows, emptySet(), cursor = 500, firstPass = false, nowMs = 1)
        assertTrue(plan.truncated)
        assertEquals(500, plan.newCursorMs)                           // unchanged
        assertEquals(2000, plan.lines.size)                           // lines still written once
    }

    // ---- privacy + robustness --------------------------------------------------------------

    @Test
    fun `notes never exported and serial only as 16-hex hash`() {
        val b = bs(id = 1, version = 0, dateCreated = 1, serial = "MD1234567", notes = "geheim")
        val line = FuseTreatmentTransitionCollector.line(b, FuseTreatmentTransitionCollector.fingerprint(b), "current", 1)
        assertFalse(line.contains("geheim"))
        assertFalse(line.contains("MD1234567"))
        val hash = JSONObject(line).getJSONObject("ids").getString("pumpSerialHash")
        assertEquals(16, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `fingerprint is full 64 hex and version-sensitive`() {
        val a = bs(id = 1, version = 0, dateCreated = 1)
        val b = bs(id = 1, version = 1, dateCreated = 2)
        val fa = FuseTreatmentTransitionCollector.fingerprint(a)
        assertEquals(64, fa.length)
        assertTrue(fa != FuseTreatmentTransitionCollector.fingerprint(b))
    }

    @Test
    fun `NaN amount does not throw and drops only the amount field`() {
        val b = bs(id = 1, version = 0, dateCreated = 1, amount = Double.NaN)
        val line = FuseTreatmentTransitionCollector.line(b, FuseTreatmentTransitionCollector.fingerprint(b), "current", 1)
        assertFalse(JSONObject(line).has("amount"))
    }

    // ---- R9/F2: failed append must not advance dedupe state or cursor ----------------------

    @Test
    fun `failed append leaves state untouched and next tick retries the same lines`() {
        val written = StringBuilder()
        var failNext = true
        val cursorSaves = ArrayList<Long>()
        val prevSink = FuseTreatmentTransitionCollector.appendSink
        val prevStore = FuseTreatmentTransitionCollector.cursorStore
        val prevDiag = FuseTreatmentTransitionCollector.diagSink
        FuseTreatmentTransitionCollector.diagSink = { }   // keep unit tests filesystem-free
        FuseTreatmentTransitionCollector.appendSink = { text ->
            if (failNext) throw java.io.IOException("disk full")
            written.append(text)
        }
        FuseTreatmentTransitionCollector.cursorStore = object : FuseTreatmentTransitionCollector.CursorStore {
            override fun load() = FuseTreatmentTransitionCollector.Cursors(500L, -1L)
            override fun save(cursors: FuseTreatmentTransitionCollector.Cursors) { cursorSaves.add(cursors.cursorMs) }
        }
        val rows = listOf(bs(id = 10, version = 0, dateCreated = 1000, tempId = 111))
        val fakeSource = FakePersistence(rows)
        try {
            FuseTreatmentTransitionCollector.tick(fakeSource.layer, nowMs = 2000)   // append fails
            assertEquals(0, cursorSaves.size)                                        // no cursor commit
            assertEquals(0, written.length)
            failNext = false
            FuseTreatmentTransitionCollector.tick(fakeSource.layer, nowMs = 2060)   // retry succeeds
            assertTrue(written.toString().contains("\"physicalRowId\":10"))
            assertEquals(1, written.toString().trim().lines().size)                  // exactly once
            assertEquals(listOf(1000L), cursorSaves)                                 // now committed
        } finally {
            FuseTreatmentTransitionCollector.appendSink = prevSink
            FuseTreatmentTransitionCollector.cursorStore = prevStore
            FuseTreatmentTransitionCollector.diagSink = prevDiag
            FuseTreatmentTransitionCollectorTestReset.reset()
        }
    }

    // ---- dc=-1 pump-sync rows (AAPS raw-insert quirk) are caught by the id cursor ----------

    @Test
    fun `tempId-only state with dateCreated -1 is exported via the id cursor`() {
        val written = StringBuilder()
        val saved = ArrayList<FuseTreatmentTransitionCollector.Cursors>()
        val prevSink = FuseTreatmentTransitionCollector.appendSink
        val prevStore = FuseTreatmentTransitionCollector.cursorStore
        val prevDiag = FuseTreatmentTransitionCollector.diagSink
        FuseTreatmentTransitionCollector.diagSink = { }
        FuseTreatmentTransitionCollector.appendSink = { text -> written.append(text) }
        FuseTreatmentTransitionCollector.cursorStore = object : FuseTreatmentTransitionCollector.CursorStore {
            override fun load() = FuseTreatmentTransitionCollector.Cursors(5000L, 100L)
            override fun save(cursors: FuseTreatmentTransitionCollector.Cursors) { saved.add(cursors) }
        }
        // After an SMB: current row 101 is already v1 (dc re-stamped, tempId+pumpId); the
        // historic v0 copy (row 102) kept dateCreated = -1 — invisible to any dc cursor.
        val currentV1 = bs(id = 101, version = 1, dateCreated = 6000, tempId = 111, pumpId = 999, serial = "SN1")
        val historicV0 = bs(id = 102, version = 0, dateCreated = -1, referenceId = 101, tempId = 111, serial = "SN1")
        val fake = FakePersistence(dcRows = listOf(currentV1), idRows = listOf(currentV1, historicV0))
        try {
            FuseTreatmentTransitionCollector.tick(fake.layer, nowMs = 7000)
            val lines = written.toString().trim().lines().map { JSONObject(it) }
            assertEquals(2, lines.size)                                    // dedupe across both paths
            val v0 = lines.first { it.getInt("version") == 0 }
            assertEquals(-1L, v0.getLong("dateCreated"))                   // the invisible state, now visible
            assertFalse(v0.getJSONObject("ids").has("pumpId"))             // TEMP_PENDING evidence
            assertEquals(101L, v0.getLong("rowId"))                        // canonical id
            assertEquals(FuseTreatmentTransitionCollector.Cursors(6000L, 102L), saved.last())
        } finally {
            FuseTreatmentTransitionCollector.appendSink = prevSink
            FuseTreatmentTransitionCollector.cursorStore = prevStore
            FuseTreatmentTransitionCollector.diagSink = prevDiag
            FuseTreatmentTransitionCollectorTestReset.reset()
        }
    }

    /** Minimal fake exposing only the PersistenceLayer methods the collector calls. */
    private class FakePersistence(val dcRows: List<BS>, val idRows: List<BS> = emptyList()) {

        val layer: app.aaps.core.interfaces.db.PersistenceLayer = java.lang.reflect.Proxy.newProxyInstance(
            app.aaps.core.interfaces.db.PersistenceLayer::class.java.classLoader,
            arrayOf(app.aaps.core.interfaces.db.PersistenceLayer::class.java)
        ) { _, method, args ->
            when (method.name) {
                "collectNewBolusEntriesSince" -> {
                    val offset = args!![3] as Int
                    if (offset == 0) dcRows else emptyList<BS>()
                }

                "collectBolusRowsAfterId"     -> {
                    val afterId = args!![0] as Long
                    idRows.filter { it.id > afterId }.sortedBy { it.id }
                }

                else                          -> throw UnsupportedOperationException(method.name)
            }
        } as app.aaps.core.interfaces.db.PersistenceLayer
    }
}

/** Test-only reset of the collector singleton (sinks + in-memory dedupe/cursor state). */
internal object FuseTreatmentTransitionCollectorTestReset {

    fun reset() {
        // Re-inject default no-op-safe sinks; in-memory state is reset via reflection because
        // the production object intentionally has no public reset (nothing should ever clear
        // the dedupe state at runtime).
        val cls = FuseTreatmentTransitionCollector::class.java
        cls.getDeclaredField("seenKeys").apply { isAccessible = true }
            .let { (it.get(FuseTreatmentTransitionCollector) as LinkedHashSet<*>).clear() }
        cls.getDeclaredField("cursorMs").apply { isAccessible = true }
            .setLong(FuseTreatmentTransitionCollector, -1L)
        cls.getDeclaredField("idCursor").apply { isAccessible = true }
            .setLong(FuseTreatmentTransitionCollector, -1L)
        cls.getDeclaredField("cursorLoaded").apply { isAccessible = true }
            .setBoolean(FuseTreatmentTransitionCollector, false)
    }
}

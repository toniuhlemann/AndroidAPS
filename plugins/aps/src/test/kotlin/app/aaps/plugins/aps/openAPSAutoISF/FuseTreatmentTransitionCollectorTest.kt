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
 * FUSE P-1.0 collector — R9 (F1/F2) and R11 (F6/F7) mandatory evidence as unit tests.
 * Pure-logic tests where possible; tick-level tests inject fake source and sinks.
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

    // ---- R9 evidence: A->B->C entirely between two ticks, all states, in order ------------

    @Test
    fun `intermediate versions between two ticks are all emitted in version order`() {
        val rows = listOf(
            bs(id = 10, version = 2, dateCreated = 3000, tempId = 111, pumpId = 999, serial = "SN1", amount = 0.30),
            bs(id = 22, version = 1, dateCreated = 2000, referenceId = 10, tempId = 111, serial = "SN1", amount = 0.30),
            bs(id = 21, version = 0, dateCreated = 1000, referenceId = 10, tempId = 111, serial = "SN1", amount = 0.35)
        )
        val plan = FuseTreatmentTransitionCollector.plan(rows, emptySet(), firstPass = false, nowMs = 9999)
        assertEquals(3, plan.lines.size)
        assertEquals(listOf(0, 1, 2), plan.lines.map { JSONObject(it).getInt("version") })
        assertEquals(setOf(10L), plan.lines.map { JSONObject(it).getLong("rowId") }.toSet())
        val first = JSONObject(plan.lines[0])
        val last = JSONObject(plan.lines[2])
        assertFalse(first.getJSONObject("ids").has("pumpId"))
        assertTrue(last.getJSONObject("ids").has("pumpId"))
        assertEquals("historic", first.getString("obs"))
        assertEquals("current", last.getString("obs"))
    }

    // ---- R11/F6: logical fold of current + historic copy of the SAME state -----------------

    @Test
    fun `current v0 and its later historic copy share the semantic fingerprint and fold to one logical state`() {
        // Tick A sees current v0 (phys 10). After the update, tick B sees the historic v0
        // copy (phys 21, referenceId=10) and current v1 (phys 10).
        val currentV0 = bs(id = 10, version = 0, dateCreated = 1000, tempId = 111, serial = "SN1")
        val planA = FuseTreatmentTransitionCollector.plan(listOf(currentV0), emptySet(), false, 1)
        val historicV0 = bs(id = 21, version = 0, dateCreated = 1000, referenceId = 10, tempId = 111, serial = "SN1")
        val currentV1 = bs(id = 10, version = 1, dateCreated = 2000, tempId = 111, pumpId = 999, serial = "SN1")
        val planB = FuseTreatmentTransitionCollector.plan(listOf(historicV0, currentV1), planA.newKeys.toSet(), false, 2)

        // Transport level: historic copy is a new physical row -> exported again (allowed).
        assertEquals(2, planB.lines.size)
        // Logical level: rowId|version|stateFingerprint folds both v0 rows into ONE state.
        val all = (planA.lines + planB.lines).map { JSONObject(it) }
        val logical = all.map { "${it.getLong("rowId")}|${it.getInt("version")}|${it.getString("stateFingerprint")}" }.toSet()
        assertEquals(2, logical.size)                                  // exactly {v0, v1}, not {v0, v0, v1}
        val v0Rows = all.filter { it.getInt("version") == 0 }
        assertEquals(2, v0Rows.size)
        assertEquals(v0Rows[0].getString("stateFingerprint"), v0Rows[1].getString("stateFingerprint"))
        assertEquals(v0Rows[0].getLong("rowId"), v0Rows[1].getLong("rowId"))
    }

    @Test
    fun `fingerprint is semantic - independent of referenceId and physicalRowId`() {
        val current = bs(id = 10, version = 0, dateCreated = 1000, tempId = 111, serial = "SN1")
        val historic = bs(id = 999, version = 0, dateCreated = -1, referenceId = 10, tempId = 111, serial = "SN1")
        assertEquals(
            FuseTreatmentTransitionCollector.fingerprint(current),
            FuseTreatmentTransitionCollector.fingerprint(historic)
        )
        assertEquals(64, FuseTreatmentTransitionCollector.fingerprint(current).length)
        // version stays fingerprint-relevant
        val v1 = bs(id = 10, version = 1, dateCreated = 2000, tempId = 111, serial = "SN1")
        assertTrue(FuseTreatmentTransitionCollector.fingerprint(current) != FuseTreatmentTransitionCollector.fingerprint(v1))
    }

    // ---- dedupe / idempotence --------------------------------------------------------------

    @Test
    fun `already seen transport keys are not re-emitted (overlap idempotence)`() {
        val rows = listOf(bs(id = 10, version = 0, dateCreated = 1000, tempId = 111))
        val p1 = FuseTreatmentTransitionCollector.plan(rows, emptySet(), firstPass = false, nowMs = 1)
        assertEquals(1, p1.lines.size)
        val p2 = FuseTreatmentTransitionCollector.plan(rows, p1.newKeys.toSet(), firstPass = false, nowMs = 2)
        assertEquals(0, p2.lines.size)
    }

    // ---- privacy + robustness --------------------------------------------------------------

    @Test
    fun `notes never exported and serial only as 16-hex hash`() {
        val b = bs(id = 1, version = 0, dateCreated = 1, serial = "MD1234567", notes = "geheim")
        val line = FuseTreatmentTransitionCollector.line(b, FuseTreatmentTransitionCollector.fingerprint(b), "current", 1)
        assertFalse(line.contains("geheim"))
        assertFalse(line.contains("MD1234567"))
        val hash = JSONObject(line).getJSONObject("ids").getString("pumpSerialHash")
        assertTrue(hash.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `NaN amount does not throw and drops only the amount field`() {
        val b = bs(id = 1, version = 0, dateCreated = 1, amount = Double.NaN)
        val line = FuseTreatmentTransitionCollector.line(b, FuseTreatmentTransitionCollector.fingerprint(b), "current", 1)
        assertFalse(JSONObject(line).has("amount"))
    }

    // ---- R9/F2: failed append must not advance dedupe state or cursors ---------------------

    @Test
    fun `failed append leaves state untouched and next tick retries the same lines`() {
        val written = StringBuilder()
        var failNext = true
        val saves = ArrayList<FuseTreatmentTransitionCollector.Cursors>()
        val prevSink = FuseTreatmentTransitionCollector.appendSink
        val prevStore = FuseTreatmentTransitionCollector.cursorStore
        val prevDiag = FuseTreatmentTransitionCollector.diagSink
        FuseTreatmentTransitionCollector.diagSink = { }
        FuseTreatmentTransitionCollector.appendSink = { text ->
            if (failNext) throw java.io.IOException("disk full")
            written.append(text)
        }
        FuseTreatmentTransitionCollector.cursorStore = object : FuseTreatmentTransitionCollector.CursorStore {
            override fun load() = FuseTreatmentTransitionCollector.Cursors(500L, -1L)
            override fun save(cursors: FuseTreatmentTransitionCollector.Cursors) { saves.add(cursors) }
        }
        val rows = listOf(bs(id = 10, version = 0, dateCreated = 1000, tempId = 111))
        val fake = FakePersistence(dcRows = rows)
        try {
            FuseTreatmentTransitionCollector.tick(fake.layer, nowMs = 2000)   // append fails
            assertEquals(0, saves.size)
            assertEquals(0, written.length)
            failNext = false
            FuseTreatmentTransitionCollector.tick(fake.layer, nowMs = 2060)   // retry succeeds
            assertTrue(written.toString().contains("\"physicalRowId\":10"))
            assertEquals(1, written.toString().trim().lines().size)            // exactly once
            assertEquals(2060L, saves.last().cursorMs)                         // sweep complete -> until
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
        val saves = ArrayList<FuseTreatmentTransitionCollector.Cursors>()
        val prevSink = FuseTreatmentTransitionCollector.appendSink
        val prevStore = FuseTreatmentTransitionCollector.cursorStore
        val prevDiag = FuseTreatmentTransitionCollector.diagSink
        FuseTreatmentTransitionCollector.diagSink = { }
        FuseTreatmentTransitionCollector.appendSink = { text -> written.append(text) }
        FuseTreatmentTransitionCollector.cursorStore = object : FuseTreatmentTransitionCollector.CursorStore {
            override fun load() = FuseTreatmentTransitionCollector.Cursors(5000L, 100L)
            override fun save(cursors: FuseTreatmentTransitionCollector.Cursors) { saves.add(cursors) }
        }
        val currentV1 = bs(id = 101, version = 1, dateCreated = 6000, tempId = 111, pumpId = 999, serial = "SN1")
        val historicV0 = bs(id = 102, version = 0, dateCreated = -1, referenceId = 101, tempId = 111, serial = "SN1")
        val fake = FakePersistence(dcRows = listOf(currentV1), idRows = listOf(currentV1, historicV0))
        try {
            FuseTreatmentTransitionCollector.tick(fake.layer, nowMs = 7000)
            val lines = written.toString().trim().lines().map { JSONObject(it) }
            assertEquals(2, lines.size)
            val v0 = lines.first { it.getInt("version") == 0 }
            assertEquals(-1L, v0.getLong("dateCreated"))
            assertFalse(v0.getJSONObject("ids").has("pumpId"))
            assertEquals(101L, v0.getLong("rowId"))
            assertEquals(102L, saves.last().afterId)
            assertEquals(7000L, saves.last().cursorMs)
        } finally {
            FuseTreatmentTransitionCollector.appendSink = prevSink
            FuseTreatmentTransitionCollector.cursorStore = prevStore
            FuseTreatmentTransitionCollector.diagSink = prevDiag
            FuseTreatmentTransitionCollectorTestReset.reset()
        }
    }

    // ---- R11/F7: a >cap dateCreated sweep resumes behind the last processed row ------------

    @Test
    fun `sweep larger than the row cap continues across ticks - every row exactly once`() {
        val written = StringBuilder()
        val saves = ArrayList<FuseTreatmentTransitionCollector.Cursors>()
        val prevSink = FuseTreatmentTransitionCollector.appendSink
        val prevStore = FuseTreatmentTransitionCollector.cursorStore
        val prevDiag = FuseTreatmentTransitionCollector.diagSink
        FuseTreatmentTransitionCollector.diagSink = { }
        FuseTreatmentTransitionCollector.appendSink = { text -> written.append(text) }
        FuseTreatmentTransitionCollector.cursorStore = object : FuseTreatmentTransitionCollector.CursorStore {
            override fun load() = FuseTreatmentTransitionCollector.Cursors(500L, -1L)
            override fun save(cursors: FuseTreatmentTransitionCollector.Cursors) { saves.add(cursors) }
        }
        // 2200 rows with dateCreated in (500, 8000] -> tick 1 caps at 2000, tick 2 finishes.
        val rows = (0 until 2200).map { bs(id = 1000L + it, version = 0, dateCreated = 1000L + it) }
        val fake = FakePersistence(dcRows = rows)
        try {
            FuseTreatmentTransitionCollector.tick(fake.layer, nowMs = 8000)
            val afterTick1 = saves.last()
            assertTrue(afterTick1.sweep != null)                            // sweep frozen
            assertEquals(500L, afterTick1.cursorMs)                         // main cursor NOT advanced
            assertEquals(2000, written.toString().trim().lines().size)

            FuseTreatmentTransitionCollector.tick(fake.layer, nowMs = 8060)
            val afterTick2 = saves.last()
            assertEquals(null, afterTick2.sweep)                            // sweep completed
            assertEquals(8000L, afterTick2.cursorMs)                        // advanced to sweepUntil
            val allLines = written.toString().trim().lines()
            assertEquals(2200, allLines.size)                               // every row exactly once
            assertEquals(2200, allLines.map { JSONObject(it).getLong("physicalRowId") }.toSet().size)
        } finally {
            FuseTreatmentTransitionCollector.appendSink = prevSink
            FuseTreatmentTransitionCollector.cursorStore = prevStore
            FuseTreatmentTransitionCollector.diagSink = prevDiag
            FuseTreatmentTransitionCollectorTestReset.reset()
        }
    }

    /** Fake honoring the keyset predicate + ordering of the real DAO query. */
    private class FakePersistence(val dcRows: List<BS>, val idRows: List<BS> = emptyList()) {

        val layer: app.aaps.core.interfaces.db.PersistenceLayer = java.lang.reflect.Proxy.newProxyInstance(
            app.aaps.core.interfaces.db.PersistenceLayer::class.java.classLoader,
            arrayOf(app.aaps.core.interfaces.db.PersistenceLayer::class.java)
        ) { _, method, args ->
            when (method.name) {
                "collectNewBolusEntriesKeyset" -> {
                    val sinceDc = args!![0] as Long
                    val sinceId = args[1] as Long
                    val until = args[2] as Long
                    val limit = args[3] as Int
                    dcRows.asSequence()
                        .filter { it.dateCreated <= until }
                        .filter { it.dateCreated > sinceDc || (it.dateCreated == sinceDc && it.id > sinceId) }
                        .sortedWith(compareBy({ it.dateCreated }, { it.id }))
                        .take(limit)
                        .toList()
                }

                "collectBolusRowsAfterId"      -> {
                    val afterId = args!![0] as Long
                    val limit = args[1] as Int
                    idRows.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
                }

                else                           -> throw UnsupportedOperationException(method.name)
            }
        } as app.aaps.core.interfaces.db.PersistenceLayer
    }
}

/** Test-only reset of the collector singleton (in-memory dedupe/cursor/sweep state). */
internal object FuseTreatmentTransitionCollectorTestReset {

    fun reset() {
        val cls = FuseTreatmentTransitionCollector::class.java
        cls.getDeclaredField("seenKeys").apply { isAccessible = true }
            .let { (it.get(FuseTreatmentTransitionCollector) as LinkedHashSet<*>).clear() }
        cls.getDeclaredField("cursorMs").apply { isAccessible = true }
            .setLong(FuseTreatmentTransitionCollector, -1L)
        cls.getDeclaredField("idCursor").apply { isAccessible = true }
            .setLong(FuseTreatmentTransitionCollector, -1L)
        cls.getDeclaredField("sweep").apply { isAccessible = true }
            .set(FuseTreatmentTransitionCollector, null)
        cls.getDeclaredField("cursorLoaded").apply { isAccessible = true }
            .setBoolean(FuseTreatmentTransitionCollector, false)
    }
}

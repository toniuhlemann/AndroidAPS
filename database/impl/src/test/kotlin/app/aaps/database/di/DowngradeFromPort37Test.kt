package app.aaps.database.di

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The way back from the v4 port: a database 37 as the port leaves it must become exactly this version's
 * schema 34, with every row and every id, and with no value this version cannot read.
 *
 * Real SQLite (the bundled driver). The database 37 is built from the port's own Room schema export
 * (port-schemas/37.json, copied from the port), with the custom indexes and triggers its onOpen adds, and
 * filled with one or more synthetic rows in every table - the values only v4 writes among them. The
 * expected schema is this version's own export (fork-34.json, a copy of schemas/.../34.json). They are
 * compared the way Room's schema check does: columns by name with affinity, NOT NULL and primary key,
 * indexes with their columns, and foreign keys.
 */
class DowngradeFromPort37Test {

    private val directory = createTempDirectory("aaps-downgrade-37-test").toFile()
    private val path = File(directory, "aaps.db").absolutePath
    private val driver = BundledSQLiteDriver()

    @AfterEach
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a port database 37 becomes schema 34 and keeps every row`() {
        withDb { c ->
            createFrom(c, statementsOf("port-schemas/37.json"), version = 37)
            createOnOpenObjects(c)
            fillPortRows(c)
        }
        val before = withDb { c -> rowCounts(c) }

        withDb { c -> asRoomDoes(c) { downgradeFromPort37(ConnectionSqlAccess(c)) } }

        withDb { c ->
            assertThat(userVersion(c)).isEqualTo(34)
            assertThat(schemaOf(c)).isEqualTo(forkSchema())
            assertThat(rowCounts(c)).isEqualTo(before - "calibrationEntries")
            // Ids and the curve: copied 1:1 (both milliseconds); a never stamped placeholder becomes "no curve".
            assertThat(longs(c, "SELECT id FROM boluses ORDER BY id")).containsExactly(1L, 2L).inOrder()
            assertThat(longs(c, "SELECT peak FROM boluses WHERE id = 1")).containsExactly(PEAK)
            assertThat(longs(c, "SELECT insulinEndTime FROM boluses WHERE id = 1")).containsExactly(END)
            assertThat(longs(c, "SELECT COUNT(*) FROM boluses WHERE id = 2 AND insulinLabel IS NULL AND insulinEndTime IS NULL AND peak IS NULL")).containsExactly(1L)
            assertThat(longs(c, "SELECT referenceId FROM boluses WHERE id = 2")).containsExactly(1L)
            assertThat(longs(c, "SELECT peak FROM effectiveProfileSwitches")).containsExactly(PEAK)
            assertThat(longs(c, "SELECT peak FROM profileSwitches")).containsExactly(PEAK)
            // Values this version cannot read
            assertThat(texts(c, "SELECT COALESCE(location, 'NULL') FROM therapyEvents WHERE id = 1")).containsExactly("NULL")
            assertThat(texts(c, "SELECT note FROM therapyEvents WHERE id = 1").single()).contains("v4 location BACK_RIGHT_LOWER_BACK")
            assertThat(texts(c, "SELECT action FROM userEntry")).containsExactly("UNKNOWN")
            assertThat(texts(c, "SELECT note FROM userEntry").single()).contains("NEW_INSULIN")
            assertThat(texts(c, "SELECT `values` FROM userEntry").single()).isEqualTo("""[{"wrapped":{"SimpleString":{"value":"BACK_LEFT_LOWER_BACK"}}}]""")
            assertThat(texts(c, "SELECT pumpType FROM totalDailyDoses")).containsExactly("DANA_I")
            assertThat(texts(c, "SELECT pumpType FROM temporaryBasals")).containsExactly("GENERIC_AAPS")
            assertThat(texts(c, "SELECT pumpType FROM boluses WHERE id = 1")).containsExactly("MEDTRUM")
            val result = texts(c, "SELECT resultJson FROM apsResults").single()
            assertThat(result).doesNotContain("\":NaN")
            assertThat(result).doesNotContain("\":Infinity")
            assertThat(result).doesNotContain("\":-Infinity")
            assertThat(result).contains("\"eventualBG\":null")
            assertThat(result).contains("\"insulinReq\":null")
            assertThat(result).contains("\"reason\":\"keeps a :NaN in text\"")
            assertThat(texts(c, "SELECT sourceSensor FROM glucoseValues ORDER BY id")).containsExactly("LIBRE_3", "DEXCOM_G7_NATIVE", "UNKNOWN").inOrder()
            // A v4 scene without an end ends now; a temporary target keeps at least one minute.
            assertThat(longs(c, "SELECT duration FROM temporaryTargets").single()).isIn(60_000L..CENTURY)
            assertThat(longs(c, "SELECT duration FROM therapyEvents WHERE id = 2").single()).isIn(0L..CENTURY)
            assertThat(longs(c, "SELECT duration FROM therapyEvents WHERE id = 1")).containsExactly(0L)
            // The fork tables are the same on both sides and come through untouched.
            assertThat(doubles(c, "SELECT finalIsf FROM autoIsfValues")).containsExactly(1.25)
            assertThat(texts(c, "SELECT requestId FROM localCommandOutcome")).containsExactly("req-1")
            assertThat(texts(c, "SELECT capability FROM localCommandValueLease")).containsExactly("IOBTH")
            // The custom indexes are gone (this version's onOpen creates them), the lease guards stay.
            assertThat(texts(c, "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE '%_end'")).isEmpty()
            assertThat(texts(c, "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name"))
                .containsExactly("lcvl_activeSlot_insert_guard", "lcvl_activeSlot_update_guard").inOrder()
        }
    }

    @Test
    fun `a database 37 without the onOpen objects also becomes schema 34`() {
        withDb { c ->
            createFrom(c, statementsOf("port-schemas/37.json"), version = 37)
            fillPortRows(c)
        }

        withDb { c -> asRoomDoes(c) { downgradeFromPort37(ConnectionSqlAccess(c)) } }

        withDb { c -> assertThat(schemaOf(c)).isEqualTo(forkSchema()) }
    }

    @Test
    fun `insulin with another concentration stops it and leaves the database as it was`() {
        withDb { c ->
            createFrom(c, statementsOf("port-schemas/37.json"), version = 37)
            createOnOpenObjects(c)
            fillPortRows(c)
            c.execSQL("UPDATE boluses SET concentration = 2.0 WHERE id = 1")
        }
        val before = withDb { c -> schemaOf(c) to rowCounts(c) }

        val failure = assertThrows<IllegalStateException> { withDb { c -> asRoomDoes(c) { downgradeFromPort37(ConnectionSqlAccess(c)) } } }

        assertThat(failure.message).contains("concentration")
        withDb { c ->
            assertThat(userVersion(c)).isEqualTo(37)
            assertThat(schemaOf(c) to rowCounts(c)).isEqualTo(before)
        }
    }

    @Test
    fun `a current profile switch without an insulin end time stops it`() {
        withDb { c ->
            createFrom(c, statementsOf("port-schemas/37.json"), version = 37)
            fillPortRows(c)
            c.execSQL("UPDATE effectiveProfileSwitches SET insulinEndTime = -1")
        }

        assertThrows<IllegalStateException> { withDb { c -> asRoomDoes(c) { downgradeFromPort37(ConnectionSqlAccess(c)) } } }

        withDb { c -> assertThat(userVersion(c)).isEqualTo(37) }
    }

    // ---- the database 37 ------------------------------------------------------------------------

    /** What the port's onOpen adds: the custom indexes Room cannot declare and the value lease guards. */
    private fun createOnOpenObjects(c: SQLiteConnection) {
        for (table in listOf("temporaryBasals", "extendedBoluses", "temporaryTargets", "carbs", "runningModes"))
            c.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_end` ON `$table` (`timestamp` + `duration`)")
        c.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `lcvl_activeSlot_insert_guard` BEFORE INSERT ON `localCommandValueLease` " +
                "WHEN NEW.activeSlot IS NOT NULL AND NEW.activeSlot != 1 BEGIN SELECT RAISE(ABORT, 'activeSlot must be NULL or 1'); END"
        )
        c.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `lcvl_activeSlot_update_guard` BEFORE UPDATE ON `localCommandValueLease` " +
                "WHEN NEW.activeSlot IS NOT NULL AND NEW.activeSlot != 1 BEGIN SELECT RAISE(ABORT, 'activeSlot must be NULL or 1'); END"
        )
    }

    /** Synthetic rows. Every table gets at least one; the columns that matter are set, the rest filled by type. */
    private fun fillPortRows(c: SQLiteConnection) {
        val curve = mapOf("insulinLabel" to "Rapid", "insulinEndTime" to END, "insulinPeakTime" to PEAK, "concentration" to 1.0)
        insert(c, "boluses", mapOf("id" to 1L, "type" to "NORMAL", "amount" to 1.0, "pumpType" to "MEDTRUM") + curve)
        // A history row with the placeholder curve of v4's upstream step 32 -> 33, never stamped.
        insert(c, "boluses", mapOf("id" to 2L, "referenceId" to 1L, "type" to "NORMAL", "amount" to 1.0, "insulinLabel" to "", "insulinEndTime" to -1L, "insulinPeakTime" to -1L, "concentration" to 1.0))
        val profile = mapOf("basalBlocks" to "[]", "isfBlocks" to "[]", "icBlocks" to "[]", "targetBlocks" to "[]", "glucoseUnit" to "MGDL")
        insert(c, "profileSwitches", profile + curve + mapOf("profileName" to "Test"))
        insert(c, "effectiveProfileSwitches", profile + curve + mapOf("originalProfileName" to "Test", "originalCustomizedName" to "Test", "originalPsId" to 1L))
        insert(c, "totalDailyDoses", mapOf("carbInsulin" to 2.0, "pumpType" to "DANA_I2"))
        insert(c, "temporaryBasals", mapOf("type" to "NORMAL", "isAbsolute" to 1L, "pumpType" to "CARELEVO"))
        // A fork reason and a scene without an end.
        insert(c, "temporaryTargets", mapOf("reason" to "PEAK_STOP", "duration" to Long.MAX_VALUE))
        insert(c, "therapyEvents", mapOf("id" to 1L, "type" to "CANNULA_CHANGE", "glucoseUnit" to "MGDL", "location" to "BACK_RIGHT_LOWER_BACK", "note" to "site", "duration" to 0L))
        insert(c, "therapyEvents", mapOf("id" to 2L, "type" to "NOTE", "glucoseUnit" to "MGDL", "duration" to Long.MAX_VALUE))
        insert(c, "glucoseValues", mapOf("id" to 1L, "sourceSensor" to "LIBRE_3", "trendArrow" to "FLAT"))
        insert(c, "glucoseValues", mapOf("id" to 2L, "sourceSensor" to "DEXCOM_G7", "trendArrow" to "FLAT"))
        insert(c, "glucoseValues", mapOf("id" to 3L, "sourceSensor" to "INSTARA", "trendArrow" to "FLAT"))
        insert(c, "userEntry", mapOf("action" to "NEW_INSULIN", "source" to "Insulin", "note" to "", "values" to """[{"wrapped":{"TherapyEventLocation":{"value":"BACK_LEFT_LOWER_BACK"}}}]"""))
        insert(c, "apsResults", mapOf("algorithm" to "SMB", "resultJson" to """{"eventualBG":NaN,"targetBG":Infinity,"insulinReq":-Infinity,"reason":"keeps a :NaN in text"}"""))
        insert(c, "carbs", mapOf("amount" to 10.0))
        insert(c, "extendedBoluses", emptyMap())
        insert(c, "bolusCalculatorResults", emptyMap())
        insert(c, "deviceStatus", emptyMap())
        insert(c, "runningModes", mapOf("mode" to "OPEN_LOOP"))
        insert(c, "foods", emptyMap())
        insert(c, "heartRate", emptyMap())
        insert(c, "stepsCount", emptyMap())
        insert(c, "preferenceChanges", emptyMap())
        insert(c, "versionChanges", emptyMap())
        insert(c, "calibrationEntries", emptyMap())
        insert(c, "autoIsfValues", mapOf("finalIsf" to 1.25))
        insert(c, "localCommandOutcome", mapOf("requestId" to "req-1"))
        insert(c, "localCommandOwnership", mapOf("requestId" to "req-1"))
        insert(c, "localCommandValueLease", mapOf("capability" to "IOBTH", "activeSlot" to 1L))
    }

    /** One row: [values] as given, every other NOT NULL column filled with a value of its type. */
    private fun insert(c: SQLiteConnection, table: String, values: Map<String, Any?>) {
        val row = LinkedHashMap<String, Any?>()
        c.prepare("PRAGMA table_info(`$table`)").use { st ->
            while (st.step()) {
                val name = st.getText(1)
                val type = st.getText(2).uppercase()
                val notNull = st.getLong(3) == 1L
                when {
                    name in values -> row[name] = values[name]
                    name == "id"   -> Unit
                    notNull        -> row[name] = if (type.contains("INT")) 1L else if (type.contains("REAL") || type.contains("DOUB") || type.contains("FLOA")) 1.0 else "x"
                }
            }
        }
        check(values.keys.all { it in row }) { "$table has no column ${values.keys - row.keys}" }
        c.prepare("INSERT INTO `$table` (${row.keys.joinToString { "`$it`" }}) VALUES (${row.keys.joinToString { "?" }})").use { st ->
            row.values.forEachIndexed { i, value ->
                when (value) {
                    null      -> st.bindNull(i + 1)
                    is Long   -> st.bindLong(i + 1, value)
                    is Double -> st.bindDouble(i + 1, value)
                    else      -> st.bindText(i + 1, value.toString())
                }
            }
            st.step()
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Like Room: one exclusive transaction, the new version written inside it, rolled back on failure. */
    private fun asRoomDoes(c: SQLiteConnection, step: () -> Unit) {
        c.execSQL("BEGIN EXCLUSIVE TRANSACTION")
        try {
            step()
            c.execSQL("PRAGMA user_version = 34")
            c.execSQL("END TRANSACTION")
        } catch (e: Throwable) {
            c.execSQL("ROLLBACK TRANSACTION")
            throw e
        }
    }

    private fun forkSchema(): Map<String, TableShape> {
        val file = File(directory, "fork.db").absolutePath
        val c = driver.open(file)
        try {
            statementsOf("port-schemas/fork-34.json").forEach { c.execSQL(it) }
            return schemaOf(c)
        } finally {
            c.close()
        }
    }

    private data class TableShape(val columns: Set<List<Any>>, val indexes: Set<List<Any>>, val foreignKeys: Set<List<Any?>>)

    /** The parts of a table Room's schema check compares. Column types as SQLite affinity, as Room does. */
    private fun schemaOf(c: SQLiteConnection): Map<String, TableShape> {
        val tables = texts(c, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')")
        return tables.associateWith { table ->
            val columns = c.prepare("PRAGMA table_info(`$table`)").use { st ->
                buildSet { while (st.step()) add(listOf(st.getText(1), affinity(st.getText(2)), st.getLong(3), st.getLong(5))) }
            }
            val indexes = c.prepare("PRAGMA index_list(`$table`)").use { st ->
                buildList { while (st.step()) if (st.getText(3) == "c") add(Triple(st.getText(1), st.getLong(2), 0)) }
            }.map { (name, unique, _) -> listOf(name, unique, texts(c, "SELECT name FROM pragma_index_info('$name') ORDER BY seqno")) }.toSet()
            val foreignKeys = c.prepare("PRAGMA foreign_key_list(`$table`)").use { st ->
                buildSet<List<Any?>> { while (st.step()) add(listOf(st.getText(2), st.getText(3), st.getText(4), st.getText(5), st.getText(6))) }
            }
            TableShape(columns, indexes, foreignKeys)
        }
    }

    private fun affinity(type: String): String = type.uppercase().let {
        when {
            it.contains("INT")                                               -> "INTEGER"
            it.contains("CHAR") || it.contains("CLOB") || it.contains("TEXT") -> "TEXT"
            it.isEmpty() || it.contains("BLOB")                              -> "BLOB"
            it.contains("REAL") || it.contains("FLOA") || it.contains("DOUB") -> "REAL"
            else                                                             -> "NUMERIC"
        }
    }

    private fun rowCounts(c: SQLiteConnection): Map<String, Long> =
        texts(c, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')")
            .associateWith { longs(c, "SELECT COUNT(*) FROM `$it`").single() }

    private fun statementsOf(resource: String): List<String> {
        val text = javaClass.classLoader!!.getResource(resource)!!.readText()
        val database = JsonParser.parseString(text).asJsonObject["database"].asJsonObject
        return buildList {
            for (element in database["entities"].asJsonArray) {
                val entity = element.asJsonObject
                val table = entity["tableName"].asString
                add(entity["createSql"].asString.replace("\${TABLE_NAME}", table))
                entity["indices"]?.asJsonArray?.forEach { add(it.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", table)) }
            }
        }
    }

    private fun createFrom(c: SQLiteConnection, statements: List<String>, version: Int) {
        statements.forEach { c.execSQL(it) }
        c.execSQL("PRAGMA user_version = $version")
    }

    private fun <T> withDb(block: (SQLiteConnection) -> T): T {
        val c = driver.open(path)
        try {
            return block(c)
        } finally {
            c.close()
        }
    }

    private fun userVersion(c: SQLiteConnection): Int = longs(c, "PRAGMA user_version").single().toInt()
    private fun longs(c: SQLiteConnection, sql: String): List<Long> = c.prepare(sql).use { st -> buildList { while (st.step()) add(st.getLong(0)) } }
    private fun doubles(c: SQLiteConnection, sql: String): List<Double> = c.prepare(sql).use { st -> buildList { while (st.step()) add(st.getDouble(0)) } }
    private fun texts(c: SQLiteConnection, sql: String): List<String> = c.prepare(sql).use { st -> buildList { while (st.step()) add(st.getText(0)) } }

    private companion object {

        const val END = 18_000_000L
        const val PEAK = 4_500_000L
        const val CENTURY = 3_153_600_000_000L
    }
}

package app.aaps.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.aaps.database.di.createCustomIndexes
import app.aaps.database.di.createValueLeaseGuards
import app.aaps.database.di.databaseMigrations
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The fork database (fork numbering 32-34, other content than upstream 32-34) and upstream databases
 * must reach the fork version 37 without losing a row. Real SQLite, real Room schema check.
 *
 * Each case builds the old database from the schema JSON Room exported for that version, fills a few
 * synthetic rows, then opens it with the real migration list.
 */
class ForkDatabaseMigrationTest {

    private val directory = createTempDirectory("aaps-fork-migration-test")
    private val path = "$directory/aaps.db"
    private val driver = BundledSQLiteDriver()

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `fork 34 keeps boluses, autoISF values and local command rows`() = runTest {
        createFrom(forkSchema34(), version = 34)
        insertLegacyBolus(id = 1, timestamp = T0, amount = 2.5)
        insertLegacyBolus(id = 2, timestamp = T0 + MIN, amount = 0.15)
        insertTdd()
        insertGlucose(T0, "LIBRE_2_NATIVE")
        insertGlucose(T0 + MIN, "LIBRE_3")
        insertAutoIsfValues(T0)
        insertLocalCommandRows()

        openWithRoom()

        withRaw { c ->
            assertThat(userVersion(c)).isEqualTo(DATABASE_VERSION)
            assertThat(longs(c, "SELECT id FROM boluses ORDER BY id")).containsExactly(1L, 2L).inOrder()
            assertThat(doubles(c, "SELECT amount FROM boluses ORDER BY id")).containsExactly(2.5, 0.15).inOrder()
            // Placeholder curve from the upstream step 32 -> 33. MainApp stamps the real one on start.
            assertThat(longs(c, "SELECT insulinEndTime FROM boluses")).containsExactly(-1L, -1L)
            assertThat(doubles(c, "SELECT concentration FROM boluses")).containsExactly(1.0, 1.0)
            // Upstream 31 -> 32 empties the TDD cache; it is recalculated.
            assertThat(longs(c, "SELECT COUNT(*) FROM totalDailyDoses")).containsExactly(0L)
            // Upstream 35 -> 36 renames old sensor names, a current one stays.
            assertThat(strings(c, "SELECT sourceSensor FROM glucoseValues ORDER BY timestamp")).containsExactly("LIBRE_2", "LIBRE_3").inOrder()
            assertThat(doubles(c, "SELECT finalIsf FROM autoIsfValues")).containsExactly(1.25)
            assertThat(strings(c, "SELECT requestId FROM localCommandOutcome")).containsExactly("req-1")
            assertThat(strings(c, "SELECT reasonKey FROM localCommandOwnership")).containsExactly("PEAK_STOP")
            assertThat(strings(c, "SELECT capability FROM localCommandValueLease")).containsExactly("IOBTH")
            assertThat(longs(c, "SELECT COUNT(*) FROM calibrationEntries")).containsExactly(0L)
            // The activeSlot guards of the lease table are back after the migration, as in the fork.
            assertThat(strings(c, "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name"))
                .containsExactly("lcvl_activeSlot_insert_guard", "lcvl_activeSlot_update_guard").inOrder()
        }
    }

    @Test
    fun `fork 33 and fork 32 reach the same schema`() = runTest {
        for (version in listOf(33, 32)) {
            File(path).delete()
            val dropped = if (version == 33) listOf("localCommandValueLease") else listOf("localCommandValueLease", "localCommandOutcome", "localCommandOwnership")
            createFrom(forkSchema34().filterNot { stmt -> dropped.any { stmt.contains("`$it`") } }, version = version)
            insertLegacyBolus(id = 7, timestamp = T0, amount = 1.0)
            insertAutoIsfValues(T0)

            openWithRoom()

            withRaw { c ->
                assertThat(userVersion(c)).isEqualTo(DATABASE_VERSION)
                assertThat(doubles(c, "SELECT amount FROM boluses")).containsExactly(1.0)
                assertThat(doubles(c, "SELECT finalIsf FROM autoIsfValues")).containsExactly(1.25)
                assertThat(longs(c, "SELECT COUNT(*) FROM localCommandValueLease")).containsExactly(0L)
            }
        }
    }

    @Test
    fun `upstream 36 gets the fork tables and keeps its rows`() = runTest {
        createFrom(upstreamSchema(36), version = 36)
        withRaw { c ->
            c.execSQL(
                "INSERT INTO boluses (id, version, dateCreated, isValid, timestamp, utcOffset, amount, type, isBasalInsulin, insulinLabel, insulinEndTime, insulinPeakTime, concentration) " +
                    "VALUES (3, 0, $T0, 1, $T0, 0, 3.0, 'NORMAL', 0, 'Rapid', 18000000, 4500000, 1.0)"
            )
        }

        openWithRoom()

        withRaw { c ->
            assertThat(userVersion(c)).isEqualTo(DATABASE_VERSION)
            assertThat(longs(c, "SELECT insulinEndTime FROM boluses")).containsExactly(18_000_000L)
            assertThat(longs(c, "SELECT COUNT(*) FROM autoIsfValues")).containsExactly(0L)
            assertThat(longs(c, "SELECT COUNT(*) FROM localCommandOwnership")).containsExactly(0L)
        }
    }

    @Test
    fun `upstream 34 runs the remaining upstream steps`() = runTest {
        createFrom(upstreamSchema(34), version = 34)
        withRaw { c -> c.execSQL("INSERT INTO glucoseValues (id, version, dateCreated, isValid, timestamp, utcOffset, raw, value, trendArrow, noise, sourceSensor) VALUES (1, 0, $T0, 1, $T0, 0, 100.0, 100.0, 'FLAT', NULL, 'LIBRE_2_NATIVE')") }

        openWithRoom()

        withRaw { c ->
            assertThat(userVersion(c)).isEqualTo(DATABASE_VERSION)
            assertThat(strings(c, "SELECT sourceSensor FROM glucoseValues")).containsExactly("LIBRE_2")
            assertThat(longs(c, "SELECT COUNT(*) FROM calibrationEntries")).containsExactly(0L)
            assertThat(longs(c, "SELECT COUNT(*) FROM autoIsfValues")).containsExactly(0L)
        }
    }

    @Test
    fun `contradicting content fails and leaves the database untouched`() = runTest {
        // Version 33 with the new bolus layout but without carbInsulin fits neither line.
        createFrom(upstreamSchema(33).map { it.replace(", `carbInsulin` REAL NOT NULL", "") }, version = 33)

        assertFailsWith<IllegalStateException> { openWithRoom() }

        withRaw { c -> assertThat(userVersion(c)).isEqualTo(33) }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private suspend fun openWithRoom() {
        val database = Room
            .databaseBuilder<AppDatabase>(name = path)
            .setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*databaseMigrations)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    super.onOpen(connection)
                    createCustomIndexes(connection)
                    createValueLeaseGuards(connection)
                }
            })
            .fallbackToDestructiveMigration(false)
            .build()
        try {
            // Any query opens the database, which runs the migrations and Room's schema check.
            database.bolusDao.findById(1)
        } finally {
            database.close()
        }
    }

    private fun createFrom(statements: List<String>, version: Int) = withRaw { c ->
        statements.forEach { c.execSQL(it) }
        // A database that an app has opened carries the custom indexes of its onOpen - the fork and
        // upstream create the same ones. Without them here the upstream 36 case passed while the real
        // database failed Room's schema check on `carbs` (test phone, RC1 -> this version).
        createCustomIndexes(c)
        c.execSQL("PRAGMA user_version = $version")
    }

    private fun <T> withRaw(block: (SQLiteConnection) -> T): T {
        val c = driver.open(path)
        try {
            return block(c)
        } finally {
            c.close()
        }
    }

    private fun insertLegacyBolus(id: Long, timestamp: Long, amount: Double) = withRaw { c ->
        c.execSQL(
            "INSERT INTO boluses (id, version, dateCreated, isValid, timestamp, utcOffset, amount, type, isBasalInsulin) " +
                "VALUES ($id, 0, $timestamp, 1, $timestamp, 0, $amount, 'SMB', 0)"
        )
    }

    private fun insertTdd() = withRaw { c ->
        c.execSQL("INSERT INTO totalDailyDoses (version, dateCreated, isValid, timestamp, utcOffset, basalAmount, bolusAmount, totalAmount, carbs) VALUES (0, $T0, 1, $T0, 0, 1.0, 1.0, 2.0, 0.0)")
    }

    private fun insertGlucose(timestamp: Long, sensor: String) = withRaw { c ->
        c.execSQL("INSERT INTO glucoseValues (version, dateCreated, isValid, timestamp, utcOffset, raw, value, trendArrow, noise, sourceSensor) VALUES (0, $timestamp, 1, $timestamp, 0, 100.0, 100.0, 'FLAT', NULL, '$sensor')")
    }

    private fun insertAutoIsfValues(timestamp: Long) = withRaw { c ->
        c.execSQL(
            "INSERT INTO autoIsfValues (timestamp, acceIsf, bgIsf, ppIsf, driftIsf, duraIsf, finalIsf, iobThEffective, utcOffset, version, dateCreated, isValid) " +
                "VALUES ($timestamp, 1.0, 1.0, 1.0, 1.0, 1.0, 1.25, 2.0, 0, 0, $timestamp, 1)"
        )
    }

    private fun insertLocalCommandRows() = withRaw { c ->
        c.execSQL("INSERT INTO localCommandOutcome (requestId, requestHash, cmd, outcome, validateOnly, createdAt, retainUntil) VALUES ('req-1', 'h', 'TT_SET', 'APPLIED', 0, $T0, ${T0 + DAY})")
        c.execSQL(
            "INSERT INTO localCommandOwnership (requestId, ttDbId, ttEntityVersion, ttTimestamp, lowTarget, highTarget, durationMs, reasonKey, ownerPolicyHash, createdAt) " +
                "VALUES ('req-1', 5, 0, $T0, 140.0, 140.0, 3600000, 'PEAK_STOP', 'p', $T0)"
        )
        c.execSQL(
            "INSERT INTO localCommandValueLease (capability, leaseId, leaseVersion, ownerPolicyHash, basePayload, baseGeneration, setPayload, gateGeneration, createdAt, expiresAtWallMs, activeSlot) " +
                "VALUES ('IOBTH', 'req-2', 1, 'p', 'b', 1, 's', 1, $T0, ${T0 + DAY}, 1)"
        )
    }

    private fun userVersion(c: SQLiteConnection): Int = c.prepare("PRAGMA user_version").use { it.step(); it.getLong(0).toInt() }

    private fun longs(c: SQLiteConnection, sql: String): List<Long> = c.prepare(sql).use { st -> buildList { while (st.step()) add(st.getLong(0)) } }
    private fun doubles(c: SQLiteConnection, sql: String): List<Double> = c.prepare(sql).use { st -> buildList { while (st.step()) add(st.getDouble(0)) } }
    private fun strings(c: SQLiteConnection, sql: String): List<String> = c.prepare(sql).use { st -> buildList { while (st.step()) add(st.getText(0)) } }

    /** The fork schema 34, exported by Room on the fork. */
    private fun forkSchema34(): List<String> =
        statementsOf(File(javaClass.getResource("/fork-schemas/34.json")!!.toURI()).readText())

    /** An upstream schema, exported by Room into the instrumented test assets of this module. */
    private fun upstreamSchema(version: Int): List<String> =
        statementsOf(File("src/androidDeviceTest/assets/app.aaps.database.AppDatabase/$version.json").readText())

    private fun statementsOf(json: String): List<String> {
        val database = Json.parseToJsonElement(json).jsonObject["database"]!!.jsonObject
        val result = mutableListOf<String>()
        database["entities"]!!.jsonArray.forEach { e ->
            val entity = e.jsonObject
            val table = entity["tableName"]!!.jsonPrimitive.content
            result += entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            entity["indices"]?.jsonArray?.forEach { i -> result += i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table) }
        }
        database["setupQueries"]!!.jsonArray.forEach { result += it.jsonPrimitive.content }
        return result
    }

    private companion object {

        const val T0 = 1_800_000_000_000L
        const val MIN = 60_000L
        const val DAY = 86_400_000L
    }
}

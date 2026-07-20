package app.aaps.database.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.database.AppDatabase
import app.aaps.database.di.DatabaseModule
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LocalCommandChannel R6-F3: ECHTE Room-Migration 32→33 gegen die exportierten Schemata.
 * runMigrationsAndValidate prueft nach der Migration den Identity-Hash von 33.json —
 * genau der Check, der auf dem Live-Handy sonst erst beim ersten Start laufen wuerde.
 * Zusaetzlich: Bestandsdaten (TT/BG) bleiben unveraendert lesbar, beide neuen Tabellen
 * sind leer und beschreibbar, und ein anschliessender regulaerer Room-Open auf Schema 33
 * laeuft ohne erneute Migration durch.
 */
@RunWith(AndroidJUnit4::class)
class Migration32to33Test {

    private val dbName = "migration-test-32-33"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate32to33_keepsDataAndCreatesChannelTables() {
        // v32-Datenbank mit Bestandsdaten (Spalten exakt aus 32.json).
        helper.createDatabase(dbName, 32).apply {
            execSQL(
                "INSERT INTO temporaryTargets (version, dateCreated, isValid, timestamp, utcOffset, reason, highTarget, lowTarget, duration) " +
                    "VALUES (0, 1784500000000, 1, 1784500000000, 7200000, 'CORRECTION', 90.0, 90.0, 1800000)"
            )
            execSQL(
                "INSERT INTO glucoseValues (version, dateCreated, isValid, timestamp, utcOffset, value, trendArrow, sourceSensor) " +
                    "VALUES (0, 1784500000000, 1, 1784500000000, 7200000, 112.0, 'FLAT', 'LIBRE_2_NATIVE')"
            )
            close()
        }

        // DIE Migration aus dem Produktionscode — validiert gegen den 33.json-Identity-Hash.
        val db = helper.runMigrationsAndValidate(dbName, 33, true, DatabaseModule().migration32to33)

        // Bestandsdaten unveraendert lesbar.
        db.query("SELECT lowTarget, duration, reason FROM temporaryTargets").use { c ->
            Assert.assertTrue(c.moveToFirst())
            Assert.assertEquals(90.0, c.getDouble(0), 0.0)
            Assert.assertEquals(1_800_000L, c.getLong(1))
            Assert.assertEquals("CORRECTION", c.getString(2))
            Assert.assertFalse(c.moveToNext())
        }
        db.query("SELECT value FROM glucoseValues").use { c ->
            Assert.assertTrue(c.moveToFirst())
            Assert.assertEquals(112.0, c.getDouble(0), 0.0)
        }

        // Neue Tabellen: leer und beschreibbar (inkl. Unique-Index auf requestId).
        db.query("SELECT COUNT(*) FROM localCommandOutcome").use { c -> c.moveToFirst(); Assert.assertEquals(0, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM localCommandOwnership").use { c -> c.moveToFirst(); Assert.assertEquals(0, c.getInt(0)) }
        db.execSQL(
            "INSERT INTO localCommandOutcome (requestId, requestHash, cmd, outcome, validateOnly, createdAt, retainUntil) " +
                "VALUES ('00112233445566778899aabbccddeeff', 'h', 'SET', 'VALIDATED', 1, 1784500000000, 1784586400000)"
        )
        db.execSQL(
            "INSERT INTO localCommandOwnership (requestId, ttDbId, ttEntityVersion, ttTimestamp, lowTarget, highTarget, durationMs, reasonKey, ownerPolicyHash, createdAt) " +
                "VALUES ('00112233445566778899aabbccddeeff', 1, 0, 1784500000000, 90.0, 90.0, 1800000, 'CORRECTION', 'p', 1784500000000)"
        )
        db.query("SELECT COUNT(*) FROM localCommandOutcome").use { c -> c.moveToFirst(); Assert.assertEquals(1, c.getInt(0)) }
        db.close()

        // "App-Neustart": regulaerer Room-Open auf Schema 33 mit der vollen Migrationskette —
        // darf ohne erneute Migration/destructive fallback durchlaufen und die Daten sehen.
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, dbName)
            .addMigrations(*DatabaseModule().migrations)
            .build()
        try {
            val outcome = room.localCommandDao.findOutcome("00112233445566778899aabbccddeeff")
            Assert.assertNotNull(outcome)
            Assert.assertEquals("VALIDATED", outcome!!.outcome)
            Assert.assertNotNull(room.localCommandDao.activeOwnership())
        } finally {
            room.close()
        }
    }
}

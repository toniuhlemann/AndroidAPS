package app.aaps.database.migrations

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.database.AppDatabase
import app.aaps.database.di.DatabaseModule
import app.aaps.database.entities.LocalCommandValueLease
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capability-Matrix A1 (R10-F4/F3): echte Room-Migration 33→34 gegen die exportierten
 * Schemata + die beiden DB-Invarianten auf BEIDEN Pfaden (Migration UND Fresh-DB):
 *  - unique(capability, activeSlot): hoechstens EINE aktive Lease je Capability
 *    (terminale Zeilen mit activeSlot=NULL kollidieren nicht);
 *  - unique(capability, leaseVersion): ein CAS-Token kann nie wiederverwendet werden.
 * Laeuft als Library-Test-APK — die installierte AAPS-App wird nicht angefasst.
 */
@RunWith(AndroidJUnit4::class)
class Migration33to34Test {

    private val dbName = "migration-test-33-34"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun leaseInsertSql(capability: String, version: Long, active: String) =
        "INSERT INTO localCommandValueLease (capability, leaseId, leaseVersion, ownerPolicyHash, basePayload, baseGeneration, setPayload, gateGeneration, createdAt, expiresAtWallMs, activeSlot) " +
            "VALUES ('$capability', 'rid$version', $version, 'p', '{\"p\":50}', 1, '{\"p\":80}', 1, 1784500000000, 1784503600000, $active)"

    private fun assertInvariants(db: SupportSQLiteDatabase) {
        db.execSQL(leaseInsertSql("IOBTH", 1, "1"))
        // Zweite AKTIVE Lease derselben Capability: MUSS am Unique-Index scheitern.
        Assert.assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(leaseInsertSql("IOBTH", 2, "1"))
        }
        // Terminalisieren (activeSlot=NULL) → neue aktive Lease erlaubt, alte bleibt Historie.
        db.execSQL("UPDATE localCommandValueLease SET activeSlot = NULL, terminatedAt = 1784500001000, terminalReason = 'CLEARED' WHERE leaseVersion = 1")
        db.execSQL(leaseInsertSql("IOBTH", 2, "1"))
        // Mehrere TERMINALE Zeilen kollidieren nicht (NULLs sind unique-neutral) ...
        db.execSQL("UPDATE localCommandValueLease SET activeSlot = NULL, terminatedAt = 1784500002000, terminalReason = 'EXPIRED' WHERE leaseVersion = 2")
        db.execSQL(leaseInsertSql("IOBTH", 3, "1"))
        // ... aber eine WIEDERVERWENDETE leaseVersion scheitert immer (CAS-Token-Schutz).
        Assert.assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(leaseInsertSql("IOBTH", 2, "NULL"))
        }
        db.query("SELECT COUNT(*) FROM localCommandValueLease WHERE capability='IOBTH' AND activeSlot IS NOT NULL").use { c ->
            c.moveToFirst(); Assert.assertEquals(1, c.getInt(0))
        }
    }

    private fun indexNames(db: SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='localCommandValueLease' AND name NOT LIKE 'sqlite_%'").use { c ->
            while (c.moveToNext()) names.add(c.getString(0))
        }
        return names
    }

    @Test
    fun migrate33to34_keepsDataInvariantsAndMatchesFreshDb() {
        // v33-Datenbank mit Bestands-Outcome (Spalten exakt aus 33.json).
        helper.createDatabase(dbName, 33).apply {
            execSQL(
                "INSERT INTO localCommandOutcome (requestId, requestHash, cmd, outcome, validateOnly, createdAt, retainUntil) " +
                    "VALUES ('00112233445566778899aabbccddeeff', 'h', 'SET', 'APPLIED', 0, 1784500000000, 1784586400000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 34, true, DatabaseModule().migration33to34)

        // Bestands-Outcome unveraendert lesbar; neue Spalte resultJson ist NULL.
        db.query("SELECT outcome, resultJson FROM localCommandOutcome").use { c ->
            Assert.assertTrue(c.moveToFirst())
            Assert.assertEquals("APPLIED", c.getString(0))
            Assert.assertTrue(c.isNull(1))
        }
        val migratedIndexes = indexNames(db)
        assertInvariants(db)
        db.close()

        // FRESH-DB-Pfad (R10-F4): dieselben Indizes + dieselben Invarianten wie der Migrationspfad.
        val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            fresh.openHelper.writableDatabase.let { fdb ->
                Assert.assertEquals(migratedIndexes, indexNames(fdb))
                assertInvariants(fdb)
            }
            // DAO-Roundtrip auf dem Fresh-Pfad: activeValueLease/maxLeaseVersion konsistent.
            val dao = fresh.localCommandDao
            dao.insertValueLease(
                LocalCommandValueLease(
                    capability = "WEIGHTS", leaseId = "ridW", leaseVersion = 7, ownerPolicyHash = "p",
                    basePayload = "{}", baseGeneration = 1, setPayload = "{}", gateGeneration = 1,
                    createdAt = 1L, expiresAtWallMs = 2L,
                )
            )
            Assert.assertEquals(7L, dao.maxLeaseVersion("WEIGHTS"))
            Assert.assertEquals("ridW", dao.activeValueLease("WEIGHTS")!!.leaseId)
            // assertInvariants hat auf dem Fresh-Pfad eine aktive IOBTH-Lease (v3) hinterlassen —
            // der DAO muss exakt DIE sehen (Capability-Isolation + activeSlot-Filter).
            Assert.assertEquals(3L, dao.activeValueLease("IOBTH")!!.leaseVersion)
        } finally {
            fresh.close()
        }
    }
}

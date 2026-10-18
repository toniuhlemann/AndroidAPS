package app.aaps.database.di

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.aaps.database.DATABASE_VERSION
import app.aaps.database.entities.TABLE_AUTOISF_VALUES
import app.aaps.database.entities.TABLE_BOLUSES
import app.aaps.database.entities.TABLE_EFFECTIVE_PROFILE_SWITCHES
import app.aaps.database.entities.TABLE_LOCAL_COMMAND_OUTCOME
import app.aaps.database.entities.TABLE_LOCAL_COMMAND_OWNERSHIP
import app.aaps.database.entities.TABLE_LOCAL_COMMAND_VALUE_LEASE
import app.aaps.database.entities.TABLE_PROFILE_SWITCHES
import app.aaps.database.entities.TABLE_TOTAL_DAILY_DOSES

/**
 * The two guards of the value lease table: `activeSlot` may only be NULL (ended) or 1 (active).
 *
 * The unique index (capability, activeSlot) allows only ONE active lease per capability only as long
 * as no other value than 1 is used for "active" - a second active row with 2 would pass the index.
 * These triggers make that mistake impossible. Room cannot declare a trigger and does not check
 * them in its schema check, so every builder creates them when the database is opened, next to the
 * custom indexes, the same way for a new database and after a migration (as in the fork).
 */
internal fun createValueLeaseGuards(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `lcvl_activeSlot_insert_guard` BEFORE INSERT ON `$TABLE_LOCAL_COMMAND_VALUE_LEASE` " +
            "WHEN NEW.activeSlot IS NOT NULL AND NEW.activeSlot != 1 " +
            "BEGIN SELECT RAISE(ABORT, 'activeSlot must be NULL or 1'); END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `lcvl_activeSlot_update_guard` BEFORE UPDATE ON `$TABLE_LOCAL_COMMAND_VALUE_LEASE` " +
            "WHEN NEW.activeSlot IS NOT NULL AND NEW.activeSlot != 1 " +
            "BEGIN SELECT RAISE(ABORT, 'activeSlot must be NULL or 1'); END"
    )
}

/*
 * Fork database: upstream 36 plus the fork tables (autoIsfValues and the three local command tables).
 *
 * The fork and upstream both used the numbers 32 to 34, for different content:
 *  - fork 32 = upstream 31 + autoIsfValues (autoISF 3.2.x)
 *  - fork 33 = fork 32 + localCommandOutcome + localCommandOwnership
 *  - fork 34 = fork 33 + localCommandValueLease
 *  - upstream 32 = 31 + totalDailyDoses.carbInsulin, 33 = new bolus and profile switch layout with the
 *    insulin curve on every row, 34 = index clean up, 35 = calibrationEntries, 36 = sensor names.
 * A fork database at 34 is therefore NOT an upstream 34. Room alone would run upstream 34 -> 35 -> 36
 * on it, the schema check would fail on `boluses`, and the app would not start (seen on a test phone).
 *
 * So every start version from 31 to 36 gets ONE direct migration to the fork version. Room always
 * takes the longest step it has, so these replace the upstream chain from 31 on. Each of them finds
 * out which line the database belongs to, runs the upstream steps that are still missing, and then
 * makes sure the fork tables exist. Fork tables that are already there are kept as they are, with
 * their data.
 *
 * Which line: upstream 32 and later always have `totalDailyDoses.carbInsulin`, the fork never added it.
 * A database at 32, 33 or 34 without that column is a fork database and sits on upstream 31.
 *
 * The insulin curve of old boluses and profile switches: the upstream step 32 -> 33 writes the
 * placeholder (-1) into every row, and MainApp stamps ONE curve onto all of them on the next start -
 * from the old preferences, else from the running profile, else a substitute (Fiasp).
 *
 * A fork database keeps the curve of each row instead (Toni): the fork stored it on every bolus
 * and profile switch (insulinLabel, insulinEndTime, peak), and that is the insulin the dose was really
 * given with. Only rows without a valid curve get the placeholder and are stamped as before. Seen on the
 * test phone: after port -> fork -> port the old preferences were gone and the profile not yet loaded,
 * so every row became Fiasp instead of the real insulin, and FUSE paused on "insulin definitions mixed".
 * An upstream database keeps the upstream behaviour.
 */

/** Upstream schema version the fork version builds on. */
internal const val UPSTREAM_DATABASE_VERSION = 36

/** The direct migrations 31..36 -> fork version. Pass the upstream steps; they are run inside. */
internal fun forkDatabaseMigrations(upstream: Array<Migration>): Array<Migration> =
    (31..UPSTREAM_DATABASE_VERSION).map { from -> ForkAwareMigration(from, upstream) }.toTypedArray()

private class ForkAwareMigration(from: Int, private val upstream: Array<Migration>) : Migration(from, DATABASE_VERSION) {

    override fun migrate(connection: SQLiteConnection) {
        var version = upstreamVersionOf(connection, startVersion)
        // A fork database (32..34) sits on upstream 31; an upstream one keeps its own version.
        val forkLine = version != startVersion
        while (version < UPSTREAM_DATABASE_VERSION) {
            val step = upstream.singleOrNull { it.startVersion == version && it.endVersion == version + 1 }
                ?: error("Fork migration: no upstream step $version -> ${version + 1}")
            // 32 -> 33 replaces the curve of every row with the placeholder; a fork database keeps its own.
            val kept = if (forkLine && version == 32) saveInsulinCurves(connection) else emptyList()
            step.migrate(connection)
            restoreInsulinCurves(connection, kept)
            version++
        }
        createForkTables(connection)
        // Like every upstream step: the custom indexes the last open created must go, or Room's schema
        // check fails on the first table that has one (`carbs`). onOpen creates them again. Without an
        // upstream step to run - an upstream 36 database - nothing else dropped them (seen on the test
        // phone, RC1 -> this version).
        dropCustomIndexes(connection)
    }
}

/**
 * The upstream version this database really has.
 *
 * Fails instead of guessing when the content fits neither line: a migration that guesses wrong can
 * lose treatment history, and a failed migration leaves the database untouched.
 */
internal fun upstreamVersionOf(connection: SQLiteConnection, startVersion: Int): Int {
    if (startVersion !in 32..34) return startVersion
    val hasCarbInsulin = hasColumn(connection, TABLE_TOTAL_DAILY_DOSES, "carbInsulin")
    if (hasCarbInsulin) return startVersion
    // Fork line. It never rebuilt the bolus table, so the upstream 33 columns must be missing too.
    check(!hasColumn(connection, TABLE_BOLUSES, "concentration")) {
        "Fork migration: database $startVersion has no carbInsulin but already the new bolus layout"
    }
    return 31
}

internal fun hasColumn(connection: SQLiteConnection, table: String, column: String): Boolean =
    connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
        var found = false
        // Column 1 of table_info is the column name.
        while (statement.step()) if (statement.getText(1) == column) found = true
        found
    }

/** The tables whose rows carry an insulin curve: fork insulinLabel/insulinEndTime/peak, upstream 33 insulinPeakTime. */
private val CURVE_TABLES = listOf(TABLE_BOLUSES, TABLE_EFFECTIVE_PROFILE_SWITCHES, TABLE_PROFILE_SWITCHES)

private fun keptCurves(table: String) = "fork_kept_curve_$table"

/**
 * Keeps the valid insulin curve of every row of a fork database before the upstream step 32 -> 33 rebuilds the
 * tables: duration and peak set, both above 0, peak before the end. A fork bolus may have none (the columns are
 * nullable there); such rows get the placeholder and MainApp stamps them. Returns the tables it kept rows of.
 */
internal fun saveInsulinCurves(connection: SQLiteConnection): List<String> =
    CURVE_TABLES.filter { table ->
        hasColumn(connection, table, "insulinEndTime") && hasColumn(connection, table, "peak")
    }.onEach { table ->
        val label = if (hasColumn(connection, table, "insulinLabel")) "COALESCE(insulinLabel, '')" else "''"
        connection.execSQL("DROP TABLE IF EXISTS `${keptCurves(table)}`")
        connection.execSQL(
            "CREATE TEMP TABLE `${keptCurves(table)}` AS SELECT id, $label AS insulinLabel, insulinEndTime, peak FROM `$table` " +
                "WHERE insulinEndTime IS NOT NULL AND peak IS NOT NULL AND insulinEndTime > 0 AND peak > 0 AND peak < insulinEndTime"
        )
    }

/** Writes the curves [saveInsulinCurves] kept back into the rebuilt tables (same ids), as insulinPeakTime. */
internal fun restoreInsulinCurves(connection: SQLiteConnection, tables: List<String>) {
    for (table in tables) {
        val kept = keptCurves(table)
        connection.execSQL(
            "UPDATE `$table` SET " +
                "insulinLabel = (SELECT k.insulinLabel FROM `$kept` k WHERE k.id = `$table`.id), " +
                "insulinEndTime = (SELECT k.insulinEndTime FROM `$kept` k WHERE k.id = `$table`.id), " +
                "insulinPeakTime = (SELECT k.peak FROM `$kept` k WHERE k.id = `$table`.id) " +
                "WHERE id IN (SELECT id FROM `$kept`)"
        )
        connection.execSQL("DROP TABLE `$kept`")
    }
}

/**
 * The fork tables, exactly as Room created them on the fork (fork schema 34). Kept when present.
 * The SQL must match the entities [app.aaps.database.entities.AutoIsfValues] and the three local
 * command entities, or Room rejects the database after the migration.
 */
internal fun createForkTables(connection: SQLiteConnection) {
    connection.execSQL("CREATE TABLE IF NOT EXISTS `$TABLE_AUTOISF_VALUES` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `acceIsf` REAL NOT NULL, `bgIsf` REAL NOT NULL, `ppIsf` REAL NOT NULL, `driftIsf` REAL NOT NULL, `duraIsf` REAL NOT NULL, `finalIsf` REAL NOT NULL, `iobThEffective` REAL NOT NULL, `utcOffset` INTEGER NOT NULL, `version` INTEGER NOT NULL, `dateCreated` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, `referenceId` INTEGER, `nightscoutSystemId` TEXT, `nightscoutId` TEXT, `pumpType` TEXT, `pumpSerial` TEXT, `temporaryId` INTEGER, `pumpId` INTEGER, `startId` INTEGER, `endId` INTEGER)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_autoIsfValues_id` ON `$TABLE_AUTOISF_VALUES` (`id`)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_autoIsfValues_timestamp` ON `$TABLE_AUTOISF_VALUES` (`timestamp`)")

    connection.execSQL("CREATE TABLE IF NOT EXISTS `$TABLE_LOCAL_COMMAND_OUTCOME` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `requestId` TEXT NOT NULL, `requestHash` TEXT NOT NULL, `cmd` TEXT NOT NULL, `outcome` TEXT NOT NULL, `errorCode` TEXT, `appliedAt` INTEGER, `ttDbId` INTEGER, `ttEntityVersion` INTEGER, `validateOnly` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `retainUntil` INTEGER NOT NULL, `resultJson` TEXT)")
    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_localCommandOutcome_requestId` ON `$TABLE_LOCAL_COMMAND_OUTCOME` (`requestId`)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_localCommandOutcome_createdAt` ON `$TABLE_LOCAL_COMMAND_OUTCOME` (`createdAt`)")

    connection.execSQL("CREATE TABLE IF NOT EXISTS `$TABLE_LOCAL_COMMAND_OWNERSHIP` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `requestId` TEXT NOT NULL, `ttDbId` INTEGER NOT NULL, `ttEntityVersion` INTEGER NOT NULL, `ttTimestamp` INTEGER NOT NULL, `lowTarget` REAL NOT NULL, `highTarget` REAL NOT NULL, `durationMs` INTEGER NOT NULL, `reasonKey` TEXT NOT NULL, `ownerPolicyHash` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `terminatedAt` INTEGER, `terminalReason` TEXT)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_localCommandOwnership_terminatedAt` ON `$TABLE_LOCAL_COMMAND_OWNERSHIP` (`terminatedAt`)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_localCommandOwnership_ttDbId` ON `$TABLE_LOCAL_COMMAND_OWNERSHIP` (`ttDbId`)")

    connection.execSQL("CREATE TABLE IF NOT EXISTS `$TABLE_LOCAL_COMMAND_VALUE_LEASE` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `capability` TEXT NOT NULL, `leaseId` TEXT NOT NULL, `leaseVersion` INTEGER NOT NULL, `ownerPolicyHash` TEXT NOT NULL, `basePayload` TEXT NOT NULL, `baseGeneration` INTEGER NOT NULL, `setPayload` TEXT NOT NULL, `gateGeneration` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `expiresAtWallMs` INTEGER NOT NULL, `activeSlot` INTEGER, `terminatedAt` INTEGER, `terminalReason` TEXT)")
    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_localCommandValueLease_capability_activeSlot` ON `$TABLE_LOCAL_COMMAND_VALUE_LEASE` (`capability`, `activeSlot`)")
    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_localCommandValueLease_capability_leaseVersion` ON `$TABLE_LOCAL_COMMAND_VALUE_LEASE` (`capability`, `leaseVersion`)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_localCommandValueLease_terminatedAt` ON `$TABLE_LOCAL_COMMAND_VALUE_LEASE` (`terminatedAt`)")
}

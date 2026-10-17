package app.aaps.database.di

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/*
 * The way back from the v4 port (4.0.0.0+fuse2.0.0-toni, database version 37) to this version (34),
 * without losing data.
 *
 * Without it Room finds no path from 37 down to 34, and because the builder allows destructive
 * migration it deletes every table and starts empty. With it Room runs this one step, checks the schema
 * against this version's entities and opens the database as usual.
 *
 * Generated from the Room schema exports of both versions (fork 34: 6cc3604ba621ba5f8755947fb43ee37d, port 37:
 * 83621bdd353034abed4f784bf0b7e945) - the table and index statements below are exactly the ones Room declares for this
 * version, so the schema check after the step passes. Regenerate them when either side changes its
 * schema; the rules in the functions are written by hand.
 *
 * What changes:
 *  - calibrationEntries exists only in v4. It is dropped; this version has no calibrations.
 *  - boluses, effectiveProfileSwitches, profileSwitches: v4 keeps the insulin curve as insulinPeakTime
 *    and concentration, this version as peak (the same milliseconds) without a concentration, and
 *    effectiveProfileSwitches has originalPsId only in v4. The three tables are rebuilt in this version's
 *    layout. Every row and every id stays: referenceId, the local command tables and the Nightscout
 *    sync positions point at ids. In boluses the curve is nullable here; a row whose curve v4 never
 *    stamped (the -1 placeholder of history rows) gets none.
 *  - totalDailyDoses loses carbInsulin, a cached value.
 *  - The indexes become exactly the set of this version: v4 dropped some and added others. The custom
 *    indexes go too; this version's onOpen creates them again.
 *  - Values this version cannot read are mapped, see mapValuesThisVersionCannotRead.
 *
 * What stops it: insulin with a concentration other than 1.0, and a current profile switch without an
 * insulin end time. This version cannot hold either, and guessing would change doses. The step then
 * throws, Room rolls the transaction back, and v4 can open the database again as it was.
 */
internal val migration37to34 = object : Migration(37, 34) {
    // The app opens the database through the framework helper.
    override fun migrate(db: SupportSQLiteDatabase) = downgradeFromPort37(SupportSqlAccess(db))

    // A SQLite driver (the tests) calls this one.
    override fun migrate(connection: SQLiteConnection) = downgradeFromPort37(ConnectionSqlAccess(connection))
}

/** The few database calls the step needs, so the same rules run on the framework database and on a driver. */
internal interface SqlAccess {

    fun exec(sql: String)
    fun texts(query: String): List<String>
    fun number(query: String): Long
}

private class SupportSqlAccess(private val db: SupportSQLiteDatabase) : SqlAccess {

    override fun exec(sql: String) = db.execSQL(sql)
    override fun texts(query: String): List<String> = db.query(query).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    override fun number(query: String): Long = db.query(query).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
}

internal class ConnectionSqlAccess(private val connection: SQLiteConnection) : SqlAccess {

    override fun exec(sql: String) = connection.execSQL(sql)
    override fun texts(query: String): List<String> = connection.prepare(query).use { st -> buildList { while (st.step()) add(st.getText(0)) } }
    override fun number(query: String): Long = connection.prepare(query).use { st -> if (st.step()) st.getLong(0) else 0L }
}

internal fun downgradeFromPort37(sql: SqlAccess) {
    refuseWhatThisVersionCannotHold(sql)
    mapValuesThisVersionCannotRead(sql)
    sql.exec("DROP TABLE IF EXISTS `calibrationEntries`")
    for (table in REBUILT_TABLES) {
        sql.exec(table.create)
        sql.exec(table.copy)
        sql.exec("DROP TABLE `${table.name}`")
        sql.exec("ALTER TABLE `new_${table.name}` RENAME TO `${table.name}`")
    }
    val keep = FORK_34_INDEXES.map { it.first }.toSet()
    for (index in sql.texts("SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL"))
        if (index !in keep) sql.exec("DROP INDEX IF EXISTS `$index`")
    for ((_, create) in FORK_34_INDEXES) sql.exec(create)
}

private fun refuseWhatThisVersionCannotHold(sql: SqlAccess) {
    for (table in listOf("boluses", "effectiveProfileSwitches", "profileSwitches")) {
        val count = sql.number("SELECT COUNT(*) FROM `$table` WHERE `concentration` <> 1.0")
        check(count == 0L) {
            "Way back from database 37: $count rows in $table hold insulin with a concentration other than 1.0. " +
                "This version has no concentration and would read their amounts wrong."
        }
    }
    for (table in listOf("effectiveProfileSwitches", "profileSwitches")) {
        val count = sql.number("SELECT COUNT(*) FROM `$table` WHERE `referenceId` IS NULL AND `insulinEndTime` <= 0")
        check(count == 0L) {
            "Way back from database 37: $count current rows in $table have no insulin end time. " +
                "This version takes the DIA from them."
        }
    }
}

/**
 * Values v4 writes that this version cannot read back. Every row, also history rows: the Nightscout sync
 * reads those too. The rest of v4's values are the same in both versions or read as a harmless default
 * (an unknown user entry source, an unknown value type inside a user entry).
 */
private fun mapValuesThisVersionCannotRead(sql: SqlAccess) {
    // v4's body map has two places this version does not know; reading them throws (valueOf).
    sql.exec(
        "UPDATE `therapyEvents` SET `note` = TRIM(COALESCE(`note`, '') || ' [v4 location ' || `location` || ']'), `location` = NULL " +
            "WHERE `location` IN ('BACK_RIGHT_LOWER_BACK', 'BACK_LEFT_LOWER_BACK')"
    )
    // The same places inside a user entry: Gson reads an unknown enum name as null, which then fails.
    sql.exec(
        "UPDATE `userEntry` SET `values` = REPLACE(REPLACE(`values`, " +
            "'\"TherapyEventLocation\":{\"value\":\"BACK_RIGHT_LOWER_BACK\"}', '\"SimpleString\":{\"value\":\"BACK_RIGHT_LOWER_BACK\"}'), " +
            "'\"TherapyEventLocation\":{\"value\":\"BACK_LEFT_LOWER_BACK\"}', '\"SimpleString\":{\"value\":\"BACK_LEFT_LOWER_BACK\"}') " +
            "WHERE `values` LIKE '%LOWER_BACK%'"
    )
    // User entry actions only v4 has; reading them throws (valueOf). The name stays in the note.
    sql.exec(
        "UPDATE `userEntry` SET `note` = TRIM(`note` || ' [v4 ' || `action` || ']'), `action` = 'UNKNOWN' " +
            "WHERE `action` IN (${V4_ONLY_USER_ENTRY_ACTIONS.joinToString { "'$it'" }})"
    )
    // Two pumps only v4 knows; every read of such a row throws (valueOf).
    for (table in PUMP_TYPE_TABLES)
        sql.exec("UPDATE `$table` SET `pumpType` = CASE `pumpType` WHEN 'DANA_I2' THEN 'DANA_I' ELSE 'GENERIC_AAPS' END WHERE `pumpType` IN ('DANA_I2', 'CARELEVO')")
    // v4 writes NaN and Infinity into the JSON of an APS result; this version's Json refuses them, and the
    // APS plugins read the results when they start. All number fields there are nullable.
    for (column in APS_RESULT_JSON_COLUMNS)
        sql.exec(
            "UPDATE `apsResults` SET `$column` = REPLACE(REPLACE(REPLACE(`$column`, '\":-Infinity', '\":null'), '\":Infinity', '\":null'), '\":NaN', '\":null') " +
                "WHERE `$column` LIKE '%\":NaN%' OR `$column` LIKE '%\":Infinity%' OR `$column` LIKE '%\":-Infinity%'"
        )
    // Sensor names only v4 has. This version reads them as UNKNOWN, and then misses its own readings when
    // the same value comes in again (the lookup compares the name), stores it twice, and BgQualityCheck
    // sees doubled readings. So they get the name this version writes for the same source.
    sql.exec(
        "UPDATE `glucoseValues` SET `sourceSensor` = CASE `sourceSensor` " +
            V4_SENSOR_NAMES.entries.joinToString(" ") { (v4, fork) -> "WHEN '$v4' THEN '$fork'" } +
            " ELSE `sourceSensor` END WHERE `sourceSensor` IN (${V4_SENSOR_NAMES.keys.joinToString { "'$it'" }})"
    )
    // A v4 scene without an end writes Long.MAX_VALUE as duration. This version has no scenes to end it, and
    // the end time overflows, so it ends now. A temporary target of duration 0 would mean "cancel", so at
    // least one minute.
    sql.exec("UPDATE `temporaryTargets` SET `duration` = MAX(CAST(strftime('%s', 'now') AS INTEGER) * 1000 - `timestamp`, 60000) WHERE `duration` > $CENTURY_MS")
    sql.exec("UPDATE `therapyEvents` SET `duration` = MAX(CAST(strftime('%s', 'now') AS INTEGER) * 1000 - `timestamp`, 0) WHERE `duration` > $CENTURY_MS")
}

private const val CENTURY_MS = 3_153_600_000_000L

private val V4_ONLY_USER_ENTRY_ACTIONS = listOf(
    "NEW_INSULIN", "STORE_INSULIN", "CHANGE_PUMP_INSULIN", "CHANGE_INSULIN_CONCENTRATION", "INSULIN_REMOVED",
    "CALIBRATION_REMOVED", "SCENE_ACTIVATED", "SCENE_DEACTIVATED", "REMOTE_CONFIG_CHANGED"
)

private val APS_RESULT_JSON_COLUMNS = listOf(
    "resultJson", "glucoseStatusJson", "currentTempJson", "iobDataJson", "profileJson", "autosensDataJson", "mealDataJson"
)

/** v4 name -> the name this version writes for the same source. The ones without a counterpart become UNKNOWN. */
private val V4_SENSOR_NAMES = linkedMapOf(
    "DEXCOM_UNKNOWN" to "DEXCOM_NATIVE_UNKNOWN",
    "DEXCOM_G6" to "DEXCOM_G6_NATIVE",
    "DEXCOM_G7" to "DEXCOM_G7_NATIVE",
    "LIBRE_1" to "LIBRE_1_OTHER",
    "SIBIONIC_UNKNOWN" to "SIBIONIC",
    "SINOCARE" to "SINO",
    "MEDTRUM_UNKNOWN" to "UNKNOWN",
    "MM_SIMPLERA" to "UNKNOWN",
    "MM_UNKNOWN" to "UNKNOWN",
    "SIBIONIC_GS1" to "UNKNOWN",
    "SIBIONIC_GS3" to "UNKNOWN",
    "ACCU_CHEK" to "UNKNOWN",
    "CARESENS_AIR" to "UNKNOWN",
    "AIDEX_X" to "UNKNOWN",
    "ANYTIME" to "UNKNOWN",
    "GLUTEC" to "UNKNOWN",
    "GLUPRO" to "UNKNOWN",
    "INSTARA" to "UNKNOWN"
)

private val PUMP_TYPE_TABLES = listOf("apsResults", "boluses", "bolusCalculatorResults", "carbs", "effectiveProfileSwitches", "extendedBoluses", "glucoseValues", "profileSwitches", "temporaryBasals", "temporaryTargets", "therapyEvents", "totalDailyDoses", "foods", "deviceStatus", "runningModes", "heartRate", "stepsCount", "autoIsfValues")

private class RebuiltTable(val name: String, val create: String, val copy: String)

// Generated: this version's table statements, and where each column comes from in v4.
private val REBUILT_TABLES = listOf(
    RebuiltTable(
        "boluses",
        "CREATE TABLE IF NOT EXISTS `new_boluses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `version` INTEGER NOT NULL, `dateCreated` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, `referenceId` INTEGER, `timestamp` INTEGER NOT NULL, `utcOffset` INTEGER NOT NULL, `amount` REAL NOT NULL, `type` TEXT NOT NULL, `notes` TEXT, `isBasalInsulin` INTEGER NOT NULL, `nightscoutSystemId` TEXT, `nightscoutId` TEXT, `pumpType` TEXT, `pumpSerial` TEXT, `temporaryId` INTEGER, `pumpId` INTEGER, `startId` INTEGER, `endId` INTEGER, `insulinLabel` TEXT, `insulinEndTime` INTEGER, `peak` INTEGER, FOREIGN KEY(`referenceId`) REFERENCES `boluses`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "INSERT INTO `new_boluses` (`id`, `version`, `dateCreated`, `isValid`, `referenceId`, `timestamp`, `utcOffset`, `amount`, `type`, `notes`, `isBasalInsulin`, `nightscoutSystemId`, `nightscoutId`, `pumpType`, `pumpSerial`, `temporaryId`, `pumpId`, `startId`, `endId`, `insulinLabel`, `insulinEndTime`, `peak`) SELECT `id`, `version`, `dateCreated`, `isValid`, `referenceId`, `timestamp`, `utcOffset`, `amount`, `type`, `notes`, `isBasalInsulin`, `nightscoutSystemId`, `nightscoutId`, `pumpType`, `pumpSerial`, `temporaryId`, `pumpId`, `startId`, `endId`, CASE WHEN `insulinEndTime` > 0 AND `insulinPeakTime` > 0 THEN `insulinLabel` ELSE NULL END, CASE WHEN `insulinEndTime` > 0 AND `insulinPeakTime` > 0 THEN `insulinEndTime` ELSE NULL END, CASE WHEN `insulinEndTime` > 0 AND `insulinPeakTime` > 0 THEN `insulinPeakTime` ELSE NULL END FROM `boluses`"
    ),
    RebuiltTable(
        "effectiveProfileSwitches",
        "CREATE TABLE IF NOT EXISTS `new_effectiveProfileSwitches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `version` INTEGER NOT NULL, `dateCreated` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, `referenceId` INTEGER, `timestamp` INTEGER NOT NULL, `utcOffset` INTEGER NOT NULL, `basalBlocks` TEXT NOT NULL, `isfBlocks` TEXT NOT NULL, `icBlocks` TEXT NOT NULL, `targetBlocks` TEXT NOT NULL, `glucoseUnit` TEXT NOT NULL, `originalProfileName` TEXT NOT NULL, `originalCustomizedName` TEXT NOT NULL, `originalTimeshift` INTEGER NOT NULL, `originalPercentage` INTEGER NOT NULL, `originalDuration` INTEGER NOT NULL, `originalEnd` INTEGER NOT NULL, `nightscoutSystemId` TEXT, `nightscoutId` TEXT, `pumpType` TEXT, `pumpSerial` TEXT, `temporaryId` INTEGER, `pumpId` INTEGER, `startId` INTEGER, `endId` INTEGER, `insulinLabel` TEXT NOT NULL, `insulinEndTime` INTEGER NOT NULL, `peak` INTEGER NOT NULL, FOREIGN KEY(`referenceId`) REFERENCES `effectiveProfileSwitches`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "INSERT INTO `new_effectiveProfileSwitches` (`id`, `version`, `dateCreated`, `isValid`, `referenceId`, `timestamp`, `utcOffset`, `basalBlocks`, `isfBlocks`, `icBlocks`, `targetBlocks`, `glucoseUnit`, `originalProfileName`, `originalCustomizedName`, `originalTimeshift`, `originalPercentage`, `originalDuration`, `originalEnd`, `nightscoutSystemId`, `nightscoutId`, `pumpType`, `pumpSerial`, `temporaryId`, `pumpId`, `startId`, `endId`, `insulinLabel`, `insulinEndTime`, `peak`) SELECT `id`, `version`, `dateCreated`, `isValid`, `referenceId`, `timestamp`, `utcOffset`, `basalBlocks`, `isfBlocks`, `icBlocks`, `targetBlocks`, `glucoseUnit`, `originalProfileName`, `originalCustomizedName`, `originalTimeshift`, `originalPercentage`, `originalDuration`, `originalEnd`, `nightscoutSystemId`, `nightscoutId`, `pumpType`, `pumpSerial`, `temporaryId`, `pumpId`, `startId`, `endId`, `insulinLabel`, `insulinEndTime`, `insulinPeakTime` FROM `effectiveProfileSwitches`"
    ),
    RebuiltTable(
        "profileSwitches",
        "CREATE TABLE IF NOT EXISTS `new_profileSwitches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `version` INTEGER NOT NULL, `dateCreated` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, `referenceId` INTEGER, `timestamp` INTEGER NOT NULL, `utcOffset` INTEGER NOT NULL, `basalBlocks` TEXT NOT NULL, `isfBlocks` TEXT NOT NULL, `icBlocks` TEXT NOT NULL, `targetBlocks` TEXT NOT NULL, `glucoseUnit` TEXT NOT NULL, `profileName` TEXT NOT NULL, `timeshift` INTEGER NOT NULL, `percentage` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `nightscoutSystemId` TEXT, `nightscoutId` TEXT, `pumpType` TEXT, `pumpSerial` TEXT, `temporaryId` INTEGER, `pumpId` INTEGER, `startId` INTEGER, `endId` INTEGER, `insulinLabel` TEXT NOT NULL, `insulinEndTime` INTEGER NOT NULL, `peak` INTEGER NOT NULL, FOREIGN KEY(`referenceId`) REFERENCES `profileSwitches`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "INSERT INTO `new_profileSwitches` (`id`, `version`, `dateCreated`, `isValid`, `referenceId`, `timestamp`, `utcOffset`, `basalBlocks`, `isfBlocks`, `icBlocks`, `targetBlocks`, `glucoseUnit`, `profileName`, `timeshift`, `percentage`, `duration`, `nightscoutSystemId`, `nightscoutId`, `pumpType`, `pumpSerial`, `temporaryId`, `pumpId`, `startId`, `endId`, `insulinLabel`, `insulinEndTime`, `peak`) SELECT `id`, `version`, `dateCreated`, `isValid`, `referenceId`, `timestamp`, `utcOffset`, `basalBlocks`, `isfBlocks`, `icBlocks`, `targetBlocks`, `glucoseUnit`, `profileName`, `timeshift`, `percentage`, `duration`, `nightscoutSystemId`, `nightscoutId`, `pumpType`, `pumpSerial`, `temporaryId`, `pumpId`, `startId`, `endId`, `insulinLabel`, `insulinEndTime`, `insulinPeakTime` FROM `profileSwitches`"
    ),
    RebuiltTable(
        "totalDailyDoses",
        "CREATE TABLE IF NOT EXISTS `new_totalDailyDoses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `version` INTEGER NOT NULL, `dateCreated` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, `referenceId` INTEGER, `timestamp` INTEGER NOT NULL, `utcOffset` INTEGER NOT NULL, `basalAmount` REAL NOT NULL, `bolusAmount` REAL NOT NULL, `totalAmount` REAL NOT NULL, `carbs` REAL NOT NULL, `nightscoutSystemId` TEXT, `nightscoutId` TEXT, `pumpType` TEXT, `pumpSerial` TEXT, `temporaryId` INTEGER, `pumpId` INTEGER, `startId` INTEGER, `endId` INTEGER, FOREIGN KEY(`referenceId`) REFERENCES `totalDailyDoses`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "INSERT INTO `new_totalDailyDoses` (`id`, `version`, `dateCreated`, `isValid`, `referenceId`, `timestamp`, `utcOffset`, `basalAmount`, `bolusAmount`, `totalAmount`, `carbs`, `nightscoutSystemId`, `nightscoutId`, `pumpType`, `pumpSerial`, `temporaryId`, `pumpId`, `startId`, `endId`) SELECT `id`, `version`, `dateCreated`, `isValid`, `referenceId`, `timestamp`, `utcOffset`, `basalAmount`, `bolusAmount`, `totalAmount`, `carbs`, `nightscoutSystemId`, `nightscoutId`, `pumpType`, `pumpSerial`, `temporaryId`, `pumpId`, `startId`, `endId` FROM `totalDailyDoses`"
    ),
)

// Generated: every index this version declares, name to statement.
internal val FORK_34_INDEXES: List<Pair<String, String>> = listOf(
    "index_apsResults_referenceId" to "CREATE INDEX IF NOT EXISTS `index_apsResults_referenceId` ON `apsResults` (`referenceId`)",
    "index_apsResults_timestamp" to "CREATE INDEX IF NOT EXISTS `index_apsResults_timestamp` ON `apsResults` (`timestamp`)",
    "index_boluses_id" to "CREATE INDEX IF NOT EXISTS `index_boluses_id` ON `boluses` (`id`)",
    "index_boluses_isValid" to "CREATE INDEX IF NOT EXISTS `index_boluses_isValid` ON `boluses` (`isValid`)",
    "index_boluses_temporaryId" to "CREATE INDEX IF NOT EXISTS `index_boluses_temporaryId` ON `boluses` (`temporaryId`)",
    "index_boluses_pumpId" to "CREATE INDEX IF NOT EXISTS `index_boluses_pumpId` ON `boluses` (`pumpId`)",
    "index_boluses_pumpSerial" to "CREATE INDEX IF NOT EXISTS `index_boluses_pumpSerial` ON `boluses` (`pumpSerial`)",
    "index_boluses_pumpType" to "CREATE INDEX IF NOT EXISTS `index_boluses_pumpType` ON `boluses` (`pumpType`)",
    "index_boluses_referenceId" to "CREATE INDEX IF NOT EXISTS `index_boluses_referenceId` ON `boluses` (`referenceId`)",
    "index_boluses_timestamp" to "CREATE INDEX IF NOT EXISTS `index_boluses_timestamp` ON `boluses` (`timestamp`)",
    "index_bolusCalculatorResults_referenceId" to "CREATE INDEX IF NOT EXISTS `index_bolusCalculatorResults_referenceId` ON `bolusCalculatorResults` (`referenceId`)",
    "index_bolusCalculatorResults_timestamp" to "CREATE INDEX IF NOT EXISTS `index_bolusCalculatorResults_timestamp` ON `bolusCalculatorResults` (`timestamp`)",
    "index_bolusCalculatorResults_id" to "CREATE INDEX IF NOT EXISTS `index_bolusCalculatorResults_id` ON `bolusCalculatorResults` (`id`)",
    "index_bolusCalculatorResults_isValid" to "CREATE INDEX IF NOT EXISTS `index_bolusCalculatorResults_isValid` ON `bolusCalculatorResults` (`isValid`)",
    "index_carbs_id" to "CREATE INDEX IF NOT EXISTS `index_carbs_id` ON `carbs` (`id`)",
    "index_carbs_isValid" to "CREATE INDEX IF NOT EXISTS `index_carbs_isValid` ON `carbs` (`isValid`)",
    "index_carbs_nightscoutId" to "CREATE INDEX IF NOT EXISTS `index_carbs_nightscoutId` ON `carbs` (`nightscoutId`)",
    "index_carbs_referenceId" to "CREATE INDEX IF NOT EXISTS `index_carbs_referenceId` ON `carbs` (`referenceId`)",
    "index_carbs_timestamp" to "CREATE INDEX IF NOT EXISTS `index_carbs_timestamp` ON `carbs` (`timestamp`)",
    "index_effectiveProfileSwitches_id" to "CREATE INDEX IF NOT EXISTS `index_effectiveProfileSwitches_id` ON `effectiveProfileSwitches` (`id`)",
    "index_effectiveProfileSwitches_referenceId" to "CREATE INDEX IF NOT EXISTS `index_effectiveProfileSwitches_referenceId` ON `effectiveProfileSwitches` (`referenceId`)",
    "index_effectiveProfileSwitches_timestamp" to "CREATE INDEX IF NOT EXISTS `index_effectiveProfileSwitches_timestamp` ON `effectiveProfileSwitches` (`timestamp`)",
    "index_effectiveProfileSwitches_isValid" to "CREATE INDEX IF NOT EXISTS `index_effectiveProfileSwitches_isValid` ON `effectiveProfileSwitches` (`isValid`)",
    "index_extendedBoluses_id" to "CREATE INDEX IF NOT EXISTS `index_extendedBoluses_id` ON `extendedBoluses` (`id`)",
    "index_extendedBoluses_isValid" to "CREATE INDEX IF NOT EXISTS `index_extendedBoluses_isValid` ON `extendedBoluses` (`isValid`)",
    "index_extendedBoluses_endId" to "CREATE INDEX IF NOT EXISTS `index_extendedBoluses_endId` ON `extendedBoluses` (`endId`)",
    "index_extendedBoluses_pumpSerial" to "CREATE INDEX IF NOT EXISTS `index_extendedBoluses_pumpSerial` ON `extendedBoluses` (`pumpSerial`)",
    "index_extendedBoluses_pumpId" to "CREATE INDEX IF NOT EXISTS `index_extendedBoluses_pumpId` ON `extendedBoluses` (`pumpId`)",
    "index_extendedBoluses_pumpType" to "CREATE INDEX IF NOT EXISTS `index_extendedBoluses_pumpType` ON `extendedBoluses` (`pumpType`)",
    "index_extendedBoluses_referenceId" to "CREATE INDEX IF NOT EXISTS `index_extendedBoluses_referenceId` ON `extendedBoluses` (`referenceId`)",
    "index_extendedBoluses_timestamp" to "CREATE INDEX IF NOT EXISTS `index_extendedBoluses_timestamp` ON `extendedBoluses` (`timestamp`)",
    "index_glucoseValues_id" to "CREATE INDEX IF NOT EXISTS `index_glucoseValues_id` ON `glucoseValues` (`id`)",
    "index_glucoseValues_nightscoutId" to "CREATE INDEX IF NOT EXISTS `index_glucoseValues_nightscoutId` ON `glucoseValues` (`nightscoutId`)",
    "index_glucoseValues_sourceSensor" to "CREATE INDEX IF NOT EXISTS `index_glucoseValues_sourceSensor` ON `glucoseValues` (`sourceSensor`)",
    "index_glucoseValues_referenceId" to "CREATE INDEX IF NOT EXISTS `index_glucoseValues_referenceId` ON `glucoseValues` (`referenceId`)",
    "index_glucoseValues_timestamp" to "CREATE INDEX IF NOT EXISTS `index_glucoseValues_timestamp` ON `glucoseValues` (`timestamp`)",
    "index_profileSwitches_referenceId" to "CREATE INDEX IF NOT EXISTS `index_profileSwitches_referenceId` ON `profileSwitches` (`referenceId`)",
    "index_profileSwitches_timestamp" to "CREATE INDEX IF NOT EXISTS `index_profileSwitches_timestamp` ON `profileSwitches` (`timestamp`)",
    "index_profileSwitches_isValid" to "CREATE INDEX IF NOT EXISTS `index_profileSwitches_isValid` ON `profileSwitches` (`isValid`)",
    "index_profileSwitches_id" to "CREATE INDEX IF NOT EXISTS `index_profileSwitches_id` ON `profileSwitches` (`id`)",
    "index_profileSwitches_nightscoutId" to "CREATE INDEX IF NOT EXISTS `index_profileSwitches_nightscoutId` ON `profileSwitches` (`nightscoutId`)",
    "index_temporaryBasals_id" to "CREATE INDEX IF NOT EXISTS `index_temporaryBasals_id` ON `temporaryBasals` (`id`)",
    "index_temporaryBasals_isValid" to "CREATE INDEX IF NOT EXISTS `index_temporaryBasals_isValid` ON `temporaryBasals` (`isValid`)",
    "index_temporaryBasals_nightscoutId" to "CREATE INDEX IF NOT EXISTS `index_temporaryBasals_nightscoutId` ON `temporaryBasals` (`nightscoutId`)",
    "index_temporaryBasals_pumpType" to "CREATE INDEX IF NOT EXISTS `index_temporaryBasals_pumpType` ON `temporaryBasals` (`pumpType`)",
    "index_temporaryBasals_endId" to "CREATE INDEX IF NOT EXISTS `index_temporaryBasals_endId` ON `temporaryBasals` (`endId`)",
    "index_temporaryBasals_pumpSerial" to "CREATE INDEX IF NOT EXISTS `index_temporaryBasals_pumpSerial` ON `temporaryBasals` (`pumpSerial`)",
    "index_temporaryBasals_temporaryId" to "CREATE INDEX IF NOT EXISTS `index_temporaryBasals_temporaryId` ON `temporaryBasals` (`temporaryId`)",
    "index_temporaryBasals_referenceId" to "CREATE INDEX IF NOT EXISTS `index_temporaryBasals_referenceId` ON `temporaryBasals` (`referenceId`)",
    "index_temporaryBasals_timestamp" to "CREATE INDEX IF NOT EXISTS `index_temporaryBasals_timestamp` ON `temporaryBasals` (`timestamp`)",
    "index_temporaryTargets_id" to "CREATE INDEX IF NOT EXISTS `index_temporaryTargets_id` ON `temporaryTargets` (`id`)",
    "index_temporaryTargets_isValid" to "CREATE INDEX IF NOT EXISTS `index_temporaryTargets_isValid` ON `temporaryTargets` (`isValid`)",
    "index_temporaryTargets_nightscoutId" to "CREATE INDEX IF NOT EXISTS `index_temporaryTargets_nightscoutId` ON `temporaryTargets` (`nightscoutId`)",
    "index_temporaryTargets_referenceId" to "CREATE INDEX IF NOT EXISTS `index_temporaryTargets_referenceId` ON `temporaryTargets` (`referenceId`)",
    "index_temporaryTargets_timestamp" to "CREATE INDEX IF NOT EXISTS `index_temporaryTargets_timestamp` ON `temporaryTargets` (`timestamp`)",
    "index_therapyEvents_id" to "CREATE INDEX IF NOT EXISTS `index_therapyEvents_id` ON `therapyEvents` (`id`)",
    "index_therapyEvents_type" to "CREATE INDEX IF NOT EXISTS `index_therapyEvents_type` ON `therapyEvents` (`type`)",
    "index_therapyEvents_nightscoutId" to "CREATE INDEX IF NOT EXISTS `index_therapyEvents_nightscoutId` ON `therapyEvents` (`nightscoutId`)",
    "index_therapyEvents_isValid" to "CREATE INDEX IF NOT EXISTS `index_therapyEvents_isValid` ON `therapyEvents` (`isValid`)",
    "index_therapyEvents_referenceId" to "CREATE INDEX IF NOT EXISTS `index_therapyEvents_referenceId` ON `therapyEvents` (`referenceId`)",
    "index_therapyEvents_timestamp" to "CREATE INDEX IF NOT EXISTS `index_therapyEvents_timestamp` ON `therapyEvents` (`timestamp`)",
    "index_totalDailyDoses_id" to "CREATE INDEX IF NOT EXISTS `index_totalDailyDoses_id` ON `totalDailyDoses` (`id`)",
    "index_totalDailyDoses_pumpId" to "CREATE INDEX IF NOT EXISTS `index_totalDailyDoses_pumpId` ON `totalDailyDoses` (`pumpId`)",
    "index_totalDailyDoses_pumpType" to "CREATE INDEX IF NOT EXISTS `index_totalDailyDoses_pumpType` ON `totalDailyDoses` (`pumpType`)",
    "index_totalDailyDoses_pumpSerial" to "CREATE INDEX IF NOT EXISTS `index_totalDailyDoses_pumpSerial` ON `totalDailyDoses` (`pumpSerial`)",
    "index_totalDailyDoses_isValid" to "CREATE INDEX IF NOT EXISTS `index_totalDailyDoses_isValid` ON `totalDailyDoses` (`isValid`)",
    "index_totalDailyDoses_referenceId" to "CREATE INDEX IF NOT EXISTS `index_totalDailyDoses_referenceId` ON `totalDailyDoses` (`referenceId`)",
    "index_totalDailyDoses_timestamp" to "CREATE INDEX IF NOT EXISTS `index_totalDailyDoses_timestamp` ON `totalDailyDoses` (`timestamp`)",
    "index_userEntry_source" to "CREATE INDEX IF NOT EXISTS `index_userEntry_source` ON `userEntry` (`source`)",
    "index_userEntry_timestamp" to "CREATE INDEX IF NOT EXISTS `index_userEntry_timestamp` ON `userEntry` (`timestamp`)",
    "index_foods_id" to "CREATE INDEX IF NOT EXISTS `index_foods_id` ON `foods` (`id`)",
    "index_foods_nightscoutId" to "CREATE INDEX IF NOT EXISTS `index_foods_nightscoutId` ON `foods` (`nightscoutId`)",
    "index_foods_referenceId" to "CREATE INDEX IF NOT EXISTS `index_foods_referenceId` ON `foods` (`referenceId`)",
    "index_foods_isValid" to "CREATE INDEX IF NOT EXISTS `index_foods_isValid` ON `foods` (`isValid`)",
    "index_deviceStatus_id" to "CREATE INDEX IF NOT EXISTS `index_deviceStatus_id` ON `deviceStatus` (`id`)",
    "index_deviceStatus_nightscoutId" to "CREATE INDEX IF NOT EXISTS `index_deviceStatus_nightscoutId` ON `deviceStatus` (`nightscoutId`)",
    "index_deviceStatus_timestamp" to "CREATE INDEX IF NOT EXISTS `index_deviceStatus_timestamp` ON `deviceStatus` (`timestamp`)",
    "index_runningModes_id" to "CREATE INDEX IF NOT EXISTS `index_runningModes_id` ON `runningModes` (`id`)",
    "index_runningModes_nightscoutId" to "CREATE INDEX IF NOT EXISTS `index_runningModes_nightscoutId` ON `runningModes` (`nightscoutId`)",
    "index_runningModes_referenceId" to "CREATE INDEX IF NOT EXISTS `index_runningModes_referenceId` ON `runningModes` (`referenceId`)",
    "index_runningModes_timestamp" to "CREATE INDEX IF NOT EXISTS `index_runningModes_timestamp` ON `runningModes` (`timestamp`)",
    "index_heartRate_id" to "CREATE INDEX IF NOT EXISTS `index_heartRate_id` ON `heartRate` (`id`)",
    "index_heartRate_timestamp" to "CREATE INDEX IF NOT EXISTS `index_heartRate_timestamp` ON `heartRate` (`timestamp`)",
    "index_stepsCount_id" to "CREATE INDEX IF NOT EXISTS `index_stepsCount_id` ON `stepsCount` (`id`)",
    "index_stepsCount_timestamp" to "CREATE INDEX IF NOT EXISTS `index_stepsCount_timestamp` ON `stepsCount` (`timestamp`)",
    "index_autoIsfValues_id" to "CREATE INDEX IF NOT EXISTS `index_autoIsfValues_id` ON `autoIsfValues` (`id`)",
    "index_autoIsfValues_timestamp" to "CREATE INDEX IF NOT EXISTS `index_autoIsfValues_timestamp` ON `autoIsfValues` (`timestamp`)",
    "index_localCommandOutcome_requestId" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_localCommandOutcome_requestId` ON `localCommandOutcome` (`requestId`)",
    "index_localCommandOutcome_createdAt" to "CREATE INDEX IF NOT EXISTS `index_localCommandOutcome_createdAt` ON `localCommandOutcome` (`createdAt`)",
    "index_localCommandOwnership_terminatedAt" to "CREATE INDEX IF NOT EXISTS `index_localCommandOwnership_terminatedAt` ON `localCommandOwnership` (`terminatedAt`)",
    "index_localCommandOwnership_ttDbId" to "CREATE INDEX IF NOT EXISTS `index_localCommandOwnership_ttDbId` ON `localCommandOwnership` (`ttDbId`)",
    "index_localCommandValueLease_capability_activeSlot" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_localCommandValueLease_capability_activeSlot` ON `localCommandValueLease` (`capability`, `activeSlot`)",
    "index_localCommandValueLease_capability_leaseVersion" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_localCommandValueLease_capability_leaseVersion` ON `localCommandValueLease` (`capability`, `leaseVersion`)",
    "index_localCommandValueLease_terminatedAt" to "CREATE INDEX IF NOT EXISTS `index_localCommandValueLease_terminatedAt` ON `localCommandValueLease` (`terminatedAt`)",
)

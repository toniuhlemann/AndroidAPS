package app.aaps.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.DBEntryWithTime
import app.aaps.database.entities.interfaces.TraceableDBEntry

/**
 * AutoISF key values for plotting in subgraph.
 *
 * Fork table (autoISF 3.2.x, fork database version 32). The column list and the two indices must stay
 * exactly as they are on the devices that run the fork: the migration from the fork schema keeps the
 * table as it is, and Room checks it against this entity. That is also why the index on `id` stays,
 * although upstream removed such indices from its own tables in version 34.
 */
@Entity(
    tableName = TABLE_AUTOISF_VALUES,
    indices = [Index("id"), Index("timestamp")]
)
data class AutoIsfValues(
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
    /** Milliseconds since the epoch. End of the sampling period, i.e. the value is
     *  sampled from timestamp-duration to timestamp. */
    override var timestamp: Long,
    var acceIsf: Double,
    var bgIsf: Double,
    var ppIsf: Double,
    var driftIsf: Double,       // place holder
    var duraIsf: Double,
    var finalIsf: Double,
    var iobThEffective: Double,
    override var utcOffset: Long = defaultUtcOffset(timestamp),
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    @Embedded
    override var interfaceIDs_backing: InterfaceIDs? = null
) : TraceableDBEntry, DBEntryWithTime

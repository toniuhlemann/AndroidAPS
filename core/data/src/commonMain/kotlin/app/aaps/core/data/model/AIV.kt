package app.aaps.core.data.model

import app.aaps.core.data.time.systemUtcOffsetAt

/** AutoISF key values for plotting in subgraph. */
data class AIV(
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
    var utcOffset: Long = systemUtcOffsetAt(timestamp),
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    override var ids: IDs = IDs()
) : HasIDs, TimeStamped {

    fun contentEqualsTo(other: AIV): Boolean {
        return this === other || (
            timestamp == other.timestamp &&
                acceIsf == other.acceIsf &&
                bgIsf == other.bgIsf &&
                ppIsf == other.ppIsf &&
                driftIsf == other.driftIsf &&
                duraIsf == other.duraIsf &&
                finalIsf == other.finalIsf &&
                iobThEffective == other.iobThEffective &&
                isValid == other.isValid)
    }
}

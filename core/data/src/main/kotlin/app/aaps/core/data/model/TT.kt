package app.aaps.core.data.model

import java.util.TimeZone

data class TT(
    override var id: Long = 0,
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    override var ids: IDs = IDs(),
    var timestamp: Long,
    var utcOffset: Long = TimeZone.getDefault().getOffset(timestamp).toLong(),
    var reason: Reason,
    var highTarget: Double, // in mgdl
    var lowTarget: Double, // in mgdl
    /** Duration in milliseconds */
    var duration: Long
) : HasIDs {

    fun contentEqualsTo(other: TT): Boolean =
        timestamp == other.timestamp &&
            utcOffset == other.utcOffset &&
            reason == other.reason &&
            highTarget == other.highTarget &&
            lowTarget == other.lowTarget &&
            duration == other.duration &&
            isValid == other.isValid

    fun onlyNsIdAdded(previous: TT): Boolean =
        previous.id != id &&
            contentEqualsTo(previous) &&
            previous.ids.nightscoutId == null &&
            ids.nightscoutId != null

    enum class Reason(val text: String) {
        CUSTOM("Custom"),
        HYPOGLYCEMIA("Hypo"),
        ACTIVITY("Activity"),
        EATING_SOON("Eating Soon"),
        AUTOMATION("Automation"),
        WEAR("Wear"),
        // IOB-Action native viewer — tool-specific TT reasons. Appended at the END so existing
        // ordinals/names are untouched (the DB stores reason as a String → no migration). Lets the
        // companion app's NS POST show the SPECIFIC reason ("Peak-Stop", "Rebound-Schutz", …) in the
        // AAPS Treatments tab instead of the generic "Automation".
        PEAK_STOP("Peak-Stop"),
        CORRECTION("Korrektur"),
        REBOUND("Rebound-Schutz"),
        BRAKE("Bremse"),
        MEAL("Mahlzeit"),
        LOW_PROTECT("Tief-Schutz")
        ;

        companion object {

            fun fromString(reason: String?) = entries.firstOrNull { it.text == reason }
                ?: CUSTOM
        }
    }

    val end
        get() = timestamp + duration

    companion object
}
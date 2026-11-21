package app.aaps.core.interfaces.pump

/**
 * Fork diagnosis (Toni 20.11.2026): a pump that measures how far its plunger moves, and can compare that with the
 * insulin it reports as delivered.
 *
 * The loop reads it only to word a warning and to write it into the record of each cycle. Nothing here changes a dose.
 */
interface PlungerTravel {

    /** The last plunger reading of an active patch and what it shows; `null` = no reading yet. */
    val plungerReading: PlungerReading?
}

/**
 * One plunger reading of an active patch.
 *
 * @property state what the comparison with the reported delivery shows
 * @property stepsPerUnit plunger travel per reported unit; `null` while [State.UNKNOWN]
 * @property percentOfNormal the same as a share of the usual travel, 0 to 100; `null` while [State.UNKNOWN]
 * @property position the measured plunger position [steps]
 * @property reservoirU the reservoir the pump counted at this reading [U]
 * @property timestamp when the pump sent this reading
 */
data class PlungerReading(
    val state: State,
    val stepsPerUnit: Double?,
    val percentOfNormal: Int?,
    val position: Int,
    val reservoirU: Double,
    val timestamp: Long,
) {

    enum class State {

        /** The pump has not reported enough delivery yet to compare. */
        UNKNOWN,

        /** The plunger follows the reported delivery. */
        MOVING,

        /** The plunger moves, but clearly less far than the reported delivery asks for. */
        SLOW,

        /** The pump reports delivery, but the plunger does not follow. */
        STALLED
    }

    /** The share of the usual travel while the plunger moves clearly too short; `null` in every other state. */
    val slowPercent: Int? get() = percentOfNormal.takeIf { state == State.SLOW }
}

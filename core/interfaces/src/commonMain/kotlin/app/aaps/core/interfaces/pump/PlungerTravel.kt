package app.aaps.core.interfaces.pump

/**
 * Fork diagnosis (Toni 20.11.2026): a pump that measures how far its plunger moves, and can compare that with the
 * insulin it reports as delivered.
 *
 * The loop reads it only to word a warning. Nothing here changes a dose.
 */
interface PlungerTravel {

    /**
     * The plunger travel over the last reported delivery as a share of the usual travel [percent, 0 to 100], when
     * the plunger moves but clearly less far than usual. `null` = it moves as usual, it stands (the pump raises its
     * own alarm then), or there is no statement yet.
     */
    val slowPlungerPercent: Int?
}

package app.aaps.fuse.core.controller

/**
 * DAS FREIGABEFENSTER AM MAHLZEITEN-MARKER - was von der Sofort-Freigabe bleibt.
 *
 * Konsolidierung 2.0: die Sofort-Freigabe (Anhebung, Plan samt Clearance, Verteilung der Huelle) ist samt ihren
 * Einstellungen entfallen. Es bleiben die beiden Fenster-Konstanten - in diesem Fenster bucht der Runner
 * `primeSpentU`, das der Ledger fuer den Rueckweg auf 1.x fuehrt - und der Typ [Plan], der im Trail als leeres Feld
 * `prime` steht.
 */
object PrimeRelease {

    /** Das Fenster ab Knopfdruck [min] - fest, seit die Einstellung entfallen ist. */
    const val WINDOW_MIN = 15

    /**
     * Absolute Wanduhr-Kappe ab Knopfdruck [min]: auch ein verschobener Fensterstart (aus einem Ledger von 1.x)
     * zaehlt nicht ueber sie hinaus. Dieselbe Groesse wie das Marker-Boost-Fenster.
     */
    const val WALL_CEILING_MIN = 45

    // Konsolidierung 2.0: MARKER_PRIOR_MGDL_PER_MIN ist nach [MarkerScope] umgezogen (Wert unveraendert); der
    // Clearance-Anteil und die Eingabe des Plans sind mit ihm entfallen.

    data class Plan(
        val active: Boolean,
        /** Mindest-Freigabe dieses Zyklus [U], auf Pumpenschritte gerundet. */
        val floorU: Double,
        val remainingU: Double,
        val reason: String,
    )

    // Konsolidierung 2.0: die Anhebung (`lift`, Quelle PRIME) samt Blocklisten und der Plan (`plan`) sind
    // entfallen.
}

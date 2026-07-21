package app.aaps.core.interfaces.aps

/**
 * Capability-Matrix A1 (Spec v1.1 §2 + R9-G1): READ-ONLY-Vertrag fuer den effektiven
 * autoISF-Einstellungs-Snapshot. Genau EIN Snapshot pro APS-Lauf; alle Leser (APS-Kern,
 * TriggerIobTH, State-/Shadow-Export, LocalCommand-Status) nutzen DIESELBE Quelle —
 * Split-Brain-Verbot (R8 §3). Bewusst OHNE Mutationsmethoden (R9-G1); der Writer-Pfad
 * laeuft ausschliesslich ueber [AutoIsfValueLeaseInvalidator] bzw. den Kanal selbst.
 *
 * Implementierung: reiner RAM-Read (AtomicReference) + Basis-Preferences — NIE Room/
 * Datei/Binder im APS-Hotpath (R9-F3). Die TTL-/Gate-/Generation-Regeln wirken am
 * READ-Pfad: ist keine gueltige Lease publiziert, ist effective == base.
 */
interface EffectiveAutoIsfSettingsProvider {

    /**
     * Unveraenderlicher Snapshot fuer genau einen APS-Lauf.
     * [iobThPercentBase] = aktuelle Basis-Preference; [iobThPercentEffective] = Basis
     * oder aktiver Lease-Wert. [overrideState] ∈ NONE | ACTIVE | EXPIRED |
     * FOREIGN_MODIFIED | DISABLED | VO_FORCED | REVOKED | CLOCK_ANOMALY | PROCESS_RESTART.
     */
    data class Snapshot(
        val iobThPercentBase: Int,
        val iobThPercentEffective: Int,
        val overrideState: String,
        val leaseId: String?,
        val leaseVersion: Long?,
        val expiresAtWallMs: Long?,
    )

    /** Ein Snapshot pro invoke() — der Aufrufer haelt ihn fuer den GESAMTEN Lauf. */
    fun snapshot(): Snapshot

    /** Fuer den Automation-Trigger LOCAL_IOBTH_LEASE_ACTIVE (R9-F4): read-only. */
    fun isIobThLeaseActive(): Boolean
}

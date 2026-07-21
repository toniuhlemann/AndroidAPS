package app.aaps.core.interfaces.aps

/** Capability-Matrix: geschlossene Capability-Liste (R11: Enum statt freier String im Code;
 *  DB/JSON speichern die kanonischen Namen). A1 exponiert NUR IOBTH. */
enum class AutoIsfCapability { IOBTH }

/** Lease-/Override-Lebenszyklus (R11: Enum). NONE = keine Lease publiziert. */
enum class AutoIsfOverrideState { NONE, ACTIVE, EXPIRED, FOREIGN_MODIFIED, DISABLED, VO_FORCED, CLOCK_ANOMALY, PROCESS_RESTART }

/**
 * Capability-Matrix A1 (Spec v1.1 §2 + R9-G1): READ-ONLY-Vertrag fuer den effektiven
 * autoISF-Einstellungs-Snapshot. Genau EIN Snapshot pro APS-Lauf; alle Leser (APS-Kern,
 * TriggerIobTH/LeaseActive, State-/Shadow-Export, LocalCommand-Status) nutzen DIESELBE
 * Quelle — Split-Brain-Verbot (R8 §3). Bewusst OHNE Mutationsmethoden (R9-G1); der
 * Writer-Pfad laeuft ausschliesslich ueber [AutoIsfValueLeaseInvalidator] bzw. den Kanal.
 *
 * Implementierung: reiner RAM-Read (AtomicReference) + Basis-Preference — NIE Room/
 * Datei/Binder im APS-Hotpath (R9-F3). TTL-/Gate-/Generation-Regeln wirken am READ-PfAD:
 * ist keine gueltige Lease publiziert, ist effective == base. Unsichere Gate-Zustaende
 * werden beim LESEN irreversibel gelatcht (R10-F1: schnelles Re-enable belebt nie).
 */
interface EffectiveAutoIsfSettingsProvider {

    /**
     * Unveraenderlicher Snapshot fuer genau einen APS-Lauf.
     * [iobThPercentBase] = aktuelle Basis-Preference; [iobThPercentEffective] = Basis
     * oder aktiver Lease-Wert (bei OFF/VO/keine-Lease IMMER == Basis → OFF-Diff-Garantie).
     */
    data class Snapshot(
        val iobThPercentBase: Int,
        val iobThPercentEffective: Int,
        val overrideState: AutoIsfOverrideState,
        val leaseId: String?,
        val leaseVersion: Long?,
        val expiresAtWallMs: Long?,
    )

    /** Ein Snapshot pro invoke() — der Aufrufer haelt ihn fuer den GESAMTEN Lauf. */
    fun snapshot(): Snapshot

    /** Fuer den Automation-Trigger LOCAL_IOBTH_LEASE_ACTIVE (R9-F4). R11: leitet sich
     *  intern aus DERSELBEN Snapshot-Quelle ab — nie eine zweite Wahrheit. */
    fun isIobThLeaseActive(): Boolean
}

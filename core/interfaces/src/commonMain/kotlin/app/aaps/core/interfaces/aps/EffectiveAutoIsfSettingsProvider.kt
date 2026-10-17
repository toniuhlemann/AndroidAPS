package app.aaps.core.interfaces.aps

/**
 * Fork (IOB-Action local command channel): the closed list of capabilities a channel lease can
 * cover. The database and the JSON store the canonical names.
 */
enum class AutoIsfCapability { IOBTH, SMBRATIO, WEIGHTS, AUTOSTATE }

/** Life cycle of a lease or override. NONE = no lease published. */
enum class AutoIsfOverrideState { NONE, ACTIVE, EXPIRED, FOREIGN_MODIFIED, DISABLED, VO_FORCED, CLOCK_ANOMALY, PROCESS_RESTART }

/**
 * Fork (capability matrix A1): READ-ONLY contract for the effective autoISF settings.
 *
 * Exactly one snapshot per APS run. Every reader (APS core, TriggerIobTH, the lease trigger, the
 * exports, the channel status) uses this one source, so there is never a second truth. There are
 * deliberately no write methods: writers go through [AutoIsfValueLeaseInvalidator] or the channel.
 *
 * An implementation reads RAM and the base preference only - never Room, a file or a binder in the
 * APS hot path. When no valid lease is published, effective == base.
 */
interface EffectiveAutoIsfSettingsProvider {

    /**
     * Immutable snapshot for one APS run.
     * [iobThPercentBase] = the current base preference; [iobThPercentEffective] = the base, or the
     * value of an active lease (with the channel off, or no lease, ALWAYS == base).
     */
    data class Snapshot(
        val iobThPercentBase: Int,
        val iobThPercentEffective: Int,
        val overrideState: AutoIsfOverrideState,
        val leaseId: String?,
        val leaseVersion: Long?,
        val expiresAtWallMs: Long?,
        /**
         * More value overlays. Additive with defaults, so the iobTH fields keep their meaning.
         * null or empty ALWAYS means "no lease, the base preference applies". Weights are a PART
         * map: only the named keys are overlaid, all others stay on their preference.
         */
        val smbRatioEffective: Double? = null,
        val smbRatioState: AutoIsfOverrideState = AutoIsfOverrideState.NONE,
        val weightOverrides: Map<String, Double> = emptyMap(),
        val weightsState: AutoIsfOverrideState = AutoIsfOverrideState.NONE,
    )

    /** One snapshot per invoke(). The caller keeps it for the WHOLE run. */
    fun snapshot(): Snapshot

    /** For the automation trigger "local iobTH lease active". Derived from the same snapshot source. */
    fun isIobThLeaseActive(): Boolean
}

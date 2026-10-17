package app.aaps.core.interfaces.aps

/**
 * Fork (capability matrix A1): narrow WRITER port for therapeutic writers (for example
 * ActionSetIobTH) that want to write a base preference while a channel lease could be active.
 *
 * Contract ("protection wins, provably"):
 *  - The writer calls [invalidateBeforeExternalWrite] SYNCHRONOUSLY BEFORE its preference write.
 *  - true  = invalidation done, or provably no active lease -> the write may run.
 *  - false = invalidation failed or unclear -> the writer stops fail-closed (a defined action
 *            error, NO silent write).
 *  - With the feature, the channel or the capability off, the call is a safe no-op (true).
 *  - ONLY invalidation - no set, no clear, no lease access.
 *  - Never call it in the APS / determine-basal hot path (actions are the writer path).
 */
interface AutoIsfValueLeaseInvalidator {

    fun invalidateBeforeExternalWrite(capability: AutoIsfCapability, reason: String): Boolean
}

package app.aaps.plugins.aps.iobaction

/**
 * LocalCommandChannel — testbarer SERVICE-KERN (R5-F3): die komplette Bundle→ACK-Logik
 * ohne Android-Abhaengigkeiten. Der Android-Service (LocalCommandService) liefert nur noch
 * die Umgebung (Caller-Vertrauen, Secret, Gates, Uhr) und konvertiert Map↔Bundle — damit
 * ist genau die exportierte Oberflaeche unit-testbar: Caller-Reject vor jedem Parse,
 * Bundle-Haertung, Gate-Reihenfolge, Status-ACKs, neutrale Fehler.
 * Beruehrt KEINE Persistenz/TT/Loop-Pfade (by construction: keine solchen Abhaengigkeiten).
 */
object LocalCommandServiceCore {

    val ALLOWED_REQUEST_KEYS = setOf("payloadJsonUtf8", "hmacHex")

    data class Env(
        val callerTrusted: Boolean,
        val secret: ByteArray?,
        val gates: LocalCommandProtocol.GateConfig,
        val nowMs: Long,
        val serviceInstanceId: String,
        val startedAt: Long,
        val serverPolicyHash: String,
        /** Pilot-Glue (app-Modul): fuehrt SET/CANCEL ueber die eine Room-Transaktion aus.
         *  validateOnly=true darf NIE mutieren. null = kein Mutationspfad verfuegbar. */
        val mutationExecutor: ((LocalCommandProtocol.Request, Boolean) -> Map<String, Any>)? = null,
        /** Status-Anreicherung (read-only DB): aktive Ownership-Tokens bzw. Outcome-Lookup. */
        val ownedTtProvider: (() -> Map<String, Any>?)? = null,
        val outcomeProvider: ((String) -> Map<String, Any>?)? = null,
    )

    /** Rohwerte direkt aus dem Bundle (Any? — Typpruefung passiert HIER, nie im Glue). */
    fun execute(requestKeys: Set<String>?, payloadRaw: Any?, hmacRaw: Any?, env: Env): Map<String, Any> {
        // 1. Caller zuerst — ein fremder Caller erreicht NIE den Parser (R5-F3-Pflichtfall 2).
        if (!env.callerTrusted) return neutral(LocalCommandProtocol.E_AUTH)
        // 2. Bundle-Haertung (R2-A3): exakt zwei String-Keys.
        if (requestKeys != ALLOWED_REQUEST_KEYS) return neutral(LocalCommandProtocol.E_MALFORMED)
        val payload = payloadRaw as? String ?: return neutral(LocalCommandProtocol.E_MALFORMED)
        val hmac = hmacRaw as? String ?: return neutral(LocalCommandProtocol.E_MALFORMED)
        // 3. Protokoll + HMAC (pure).
        val parsed = LocalCommandProtocol.parseAndVerify(payload, hmac, env.secret, env.nowMs)
        val req = parsed.request ?: return neutral(parsed.errorCode ?: LocalCommandProtocol.E_MALFORMED)
        // 4. Gate-Matrix mit explizitem Ausfuehrungsmodus (R5-F5).
        return when (val g = LocalCommandProtocol.gate(env.gates, req)) {
            is LocalCommandProtocol.GateResult.Reject -> ackBase(req.requestId) + mapOf(
                "outcome" to "REJECTED", "errorCode" to g.errorCode,
            )
            LocalCommandProtocol.GateResult.ReadOnly -> when (req.cmd) {
                LocalCommandProtocol.Cmd.GET_SERVICE_STATUS -> {
                    val owned = env.ownedTtProvider?.invoke()
                    ackBase(req.requestId) + mapOf(
                        "outcome" to "APPLIED",
                        "mutationBuildPresent" to LocalCommandProtocol.MUTATION_BUILD_PRESENT,
                        "channelEnabled" to env.gates.channelEnabled,
                        "ttCapabilityEnabled" to env.gates.ttCapabilityEnabled,
                        "forcedValidateOnly" to env.gates.forcedValidateOnly,
                        "serverPolicyHash" to env.serverPolicyHash,
                        "databaseSchemaReady" to (env.ownedTtProvider != null),
                        "serviceInstanceId" to env.serviceInstanceId,
                        "startedAt" to env.startedAt,
                    ) + (owned?.let { mapOf("ownedTt" to "OWNED") + it } ?: mapOf("ownedTt" to "NONE"))
                }
                LocalCommandProtocol.Cmd.GET_COMMAND_STATUS -> {
                    val original = env.outcomeProvider?.invoke(req.queryRequestId!!)
                    ackBase(req.requestId) + mapOf(
                        "outcome" to "APPLIED",
                        "queryRequestId" to (req.queryRequestId ?: ""),
                    ) + (original?.let { mapOf("queryStatus" to "FOUND") + it } ?: mapOf("queryStatus" to "NOT_FOUND"))
                }
                else -> neutral(LocalCommandProtocol.E_MALFORMED)   // unerreichbar
            }
            // Pilot: expliziter Ausfuehrungsmodus aus dem Gate (R5-F5) — validateOnly wird
            // strukturell VOR dem Executor entschieden und darf dort nie mutieren.
            LocalCommandProtocol.GateResult.ValidateOnly ->
                env.mutationExecutor?.invoke(req, true) ?: neutral(LocalCommandProtocol.E_MUTATION_UNAVAILABLE)
            LocalCommandProtocol.GateResult.Apply ->
                env.mutationExecutor?.invoke(req, false) ?: neutral(LocalCommandProtocol.E_MUTATION_UNAVAILABLE)
        }
    }

    private fun ackBase(requestId: String): Map<String, Any> = mapOf(
        "protocolVersion" to LocalCommandProtocol.PROTOCOL_VERSION,
        "requestId" to requestId,
        "replayed" to false,
        "fallbackEligible" to false,
    )

    private fun neutral(code: String): Map<String, Any> = mapOf(
        "protocolVersion" to LocalCommandProtocol.PROTOCOL_VERSION,
        "outcome" to "REJECTED",
        "replayed" to false,
        "errorCode" to code,
    )
}

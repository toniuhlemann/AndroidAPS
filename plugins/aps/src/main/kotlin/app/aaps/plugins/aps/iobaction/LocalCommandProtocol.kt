package app.aaps.plugins.aps.iobaction

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * LocalCommandChannel — PURES Protokoll (Spec v1.2 + v1.3, Codex R3 OFF/Auth-Build-GO).
 * Flaches, abschliessend typisiertes Schema; Kanonisierung IMMER aus dem geparsten
 * typisierten Objekt (nie aus rohem JSON-Text, R3-A1-Auflage); HMAC-SHA256 mit
 * konstantzeitigem Vergleich; strikte unknown-reject-Semantik ohne Clamping.
 *
 * PILOT-Build (R6): MUTATION_BUILD_PRESENT=true — der Mutationszweig existiert, wird aber
 * weiterhin von den default-AUS-Gates (Kanal/Capability, nur AAPS-UI) geschuetzt.
 */
object LocalCommandProtocol {

    const val PROTOCOL_VERSION = "v1"
    const val TARGET_PACKAGE = "info.nightscout.androidaps"
    const val MAX_PAYLOAD_BYTES = 4096
    // R5-F2: Name praezisiert — geprueft wird, wie weit issuedAt in der ZUKUNFT liegen darf.
    const val MAX_ISSUED_AT_FUTURE_SKEW_MS = 5_000L
    const val MAX_VALIDITY_WINDOW_MS = 30_000L
    /** Pilot-Build (Block 2): Mutationspfad vorhanden — erreichbar NUR hinter Channel- UND
     *  TT-Capability-Gate (beide default AUS) und nur via ExecuteLocalTtCommandTransaction. */
    const val MUTATION_BUILD_PRESENT = true

    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** v1.4 Variante B: Sentinel-Tripel fuer expectedState=NONE — als echte Werte verboten. */
    const val SENTINEL_REQUEST_ID = "00000000000000000000000000000000"
    const val SENTINEL_TT_DB_ID = 0L
    const val SENTINEL_ENTITY_VERSION = -1

    enum class Cmd { SET_OWNED_TEMP_TARGET, CANCEL_OWNED_TEMP_TARGET, SET_IOBTH, CLEAR_IOBTH, GET_COMMAND_STATUS, GET_SERVICE_STATUS }
    enum class ReasonKey { PEAK_STOP, CORRECTION, REBOUND, BRAKE, MEAL, LOW_PROTECT }

    /** Value-Lease-Sentinel (A1, Variante B analog TT): leaseVersion beginnt bei 1 → 0 ist
     *  als echte Version unmoeglich und dient als NONE-Sentinel. */
    const val SENTINEL_LEASE_VERSION = 0L

    // Fehler-Codes (abschliessend fuer OFF/Auth; neutral, kein Detail-Leak)
    const val E_MALFORMED = "REJECTED_MALFORMED"
    const val E_AUTH = "REJECTED_AUTH"
    const val E_AUTH_NOT_CONFIGURED = "REJECTED_AUTH_NOT_CONFIGURED"   // R4 §1: Secret fehlt (Caller war vertraut)
    const val E_TIME = "REJECTED_TIME_WINDOW"
    const val E_BOUNDS = "REJECTED_BOUNDS"
    const val E_CHANNEL_DISABLED = "REJECTED_CHANNEL_DISABLED"
    const val E_CAPABILITY_DISABLED = "REJECTED_CAPABILITY_DISABLED"
    const val E_MUTATION_UNAVAILABLE = "REJECTED_MUTATION_UNAVAILABLE"

    data class Request(
        val cmd: Cmd,
        val requestId: String,
        val issuedAt: Long,
        val expiresAt: Long,
        val validateOnly: Boolean,          // false fuer STATUS/SERVICE_STATUS
        // SET
        val targetMgdl: Int? = null,
        val durationMin: Int? = null,
        val reasonKey: ReasonKey? = null,
        val clientPolicyHash: String? = null,          // SET: aktuelle Server-Policy (R4 §2)
        val expectedOwnerPolicyHash: String? = null,   // CANCEL: Policy-Hash der ERSTELLUNG (R4 §2)
        val expectedState: String? = null,             // SET: "NONE" | "OWNED" (Variante B, immer vorhanden)
        val expectedOwnerRequestId: String? = null,
        val expectedTtDbId: Long? = null,
        val expectedTtEntityVersion: Int? = null,
        // STATUS
        val queryRequestId: String? = null,
        // VALUE-LEASE (A1: SET_IOBTH/CLEAR_IOBTH; Prozent/TTL als ganze Zahlen = R8-F7
        // "skalierte Integer", keine JSON-Doubles in HMAC/Policy/Room)
        val percent: Int? = null,
        val ttlMin: Int? = null,
        val expectedLeaseId: String? = null,
        val expectedLeaseVersion: Long? = null,
        val canonicalString: String,
    )

    data class ParseOutcome(val request: Request?, val errorCode: String?)

    /** Strikte JSON-Zahl → Int (kein Double, kein String). */
    private fun asInt(o: JSONObject, key: String): Int? = when (val v = o.opt(key)) {
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else null
        else -> null
    }

    private fun asLong(o: JSONObject, key: String): Long? = when (val v = o.opt(key)) {
        is Int -> v.toLong(); is Long -> v; else -> null
    }

    private fun asBool(o: JSONObject, key: String): Boolean? = o.opt(key) as? Boolean

    private fun asString(o: JSONObject, key: String): String? = o.opt(key) as? String

    /**
     * Parst + validiert Payload und prueft den HMAC. secret=null ⇒ E_AUTH (kein Secret
     * konfiguriert = default-deny). Reihenfolge: Groesse → Struktur/Typen → Zeitfenster →
     * HMAC (auf dem KANONISCH NEU ERZEUGTEN String, nie auf dem Rohtext).
     */
    fun parseAndVerify(payloadJson: String, hmacHex: String, secret: ByteArray?, nowMs: Long): ParseOutcome {
        if (payloadJson.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return ParseOutcome(null, E_MALFORMED)
        if (!HEX64.matches(hmacHex)) return ParseOutcome(null, E_MALFORMED)
        val root = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return ParseOutcome(null, E_MALFORMED)
        val allowedRoot = setOf("v", "cmd", "params", "requestId", "issuedAt", "expiresAt")
        for (k in root.keys()) if (k !in allowedRoot) return ParseOutcome(null, E_MALFORMED)
        if (asString(root, "v") != PROTOCOL_VERSION) return ParseOutcome(null, E_MALFORMED)
        val cmd = asString(root, "cmd")?.let { c -> Cmd.entries.firstOrNull { it.name == c } }
            ?: return ParseOutcome(null, E_MALFORMED)
        // R5-F1: die reservierte All-Zero-Sentinel-ID ist NIE eine echte Request-ID.
        val requestId = asString(root, "requestId")?.takeIf { HEX32.matches(it) && it != SENTINEL_REQUEST_ID }
            ?: return ParseOutcome(null, E_MALFORMED)
        val issuedAt = asLong(root, "issuedAt") ?: return ParseOutcome(null, E_MALFORMED)
        val expiresAt = asLong(root, "expiresAt") ?: return ParseOutcome(null, E_MALFORMED)
        val params = root.optJSONObject("params") ?: return ParseOutcome(null, E_MALFORMED)

        // R5-F2: Zeitfenster OVERFLOW-SICHER — signierter Long-Ueberlauf darf nie eine
        // Zeitregel umgehen (Math.subtractExact; Overflow = E_TIME).
        val validityMs = try { Math.subtractExact(expiresAt, issuedAt) } catch (_: ArithmeticException) {
            return ParseOutcome(null, E_TIME)
        }
        if (validityMs !in 1..MAX_VALIDITY_WINDOW_MS) return ParseOutcome(null, E_TIME)
        if (nowMs > expiresAt) return ParseOutcome(null, E_TIME)
        if (issuedAt > nowMs) {
            val futureSkewMs = try { Math.subtractExact(issuedAt, nowMs) } catch (_: ArithmeticException) {
                return ParseOutcome(null, E_TIME)
            }
            if (futureSkewMs > MAX_ISSUED_AT_FUTURE_SKEW_MS) return ParseOutcome(null, E_TIME)
        }

        val req = parseParams(cmd, params, requestId, issuedAt, expiresAt) ?: return ParseOutcome(null, E_MALFORMED)
        // Bounds (v1.1-Hard-Bounds; Policy-Matrix ist Server-Phase 2+)
        if (cmd == Cmd.SET_OWNED_TEMP_TARGET) {
            if (req.targetMgdl!! !in 70..161) return ParseOutcome(null, E_BOUNDS)
            if (req.durationMin!! !in 5..120) return ParseOutcome(null, E_BOUNDS)
        }
        if (cmd == Cmd.SET_IOBTH) {
            // Parser-Bounds strukturell (reject-not-clamp); die geschlossene Werteliste
            // {60,70,80,90} prueft die IOBTH-Policy serverseitig.
            if (req.percent!! !in 10..100) return ParseOutcome(null, E_BOUNDS)
            if (req.ttlMin!! !in LocalCommandIobthPolicy.TTL_MIN..LocalCommandIobthPolicy.TTL_MAX) return ParseOutcome(null, E_BOUNDS)
        }
        if (secret == null || secret.isEmpty()) return ParseOutcome(null, E_AUTH_NOT_CONFIGURED)
        val expected = hmacHex(secret, req.canonicalString)
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), hmacHex.toByteArray(Charsets.US_ASCII)))
            return ParseOutcome(null, E_AUTH)
        return ParseOutcome(req, null)
    }

    private fun parseParams(cmd: Cmd, p: JSONObject, requestId: String, issuedAt: Long, expiresAt: Long): Request? {
        fun keysExactly(vararg allowed: String): Boolean {
            val set = allowed.toSet()
            for (k in p.keys()) if (k !in set) return false
            return set.all { p.has(it) }
        }
        return when (cmd) {
            Cmd.SET_OWNED_TEMP_TARGET -> {
                // v1.4 Variante B: ALLE 9 Felder immer Pflicht; NONE verlangt EXAKT das
                // Sentinel-Tripel, OWNED verlangt echte Werte. Mischformen → reject.
                if (!keysExactly(
                        "targetMgdl", "durationMin", "reasonKey", "validateOnly", "clientPolicyHash",
                        "expectedState", "expectedOwnerRequestId", "expectedTtDbId", "expectedTtEntityVersion",
                    )
                ) return null
                val state = asString(p, "expectedState") ?: return null
                val reason = asString(p, "reasonKey")?.let { r -> ReasonKey.entries.firstOrNull { it.name == r } } ?: return null
                val hash = asString(p, "clientPolicyHash")?.takeIf { HEX64.matches(it) } ?: return null
                val target = asInt(p, "targetMgdl") ?: return null
                val duration = asInt(p, "durationMin") ?: return null
                val validateOnly = asBool(p, "validateOnly") ?: return null
                val ownerId = asString(p, "expectedOwnerRequestId")?.takeIf { HEX32.matches(it) } ?: return null
                val ttId = asLong(p, "expectedTtDbId") ?: return null
                val ttVer = asInt(p, "expectedTtEntityVersion") ?: return null
                val consistent = when (state) {
                    "NONE" -> ownerId == SENTINEL_REQUEST_ID && ttId == SENTINEL_TT_DB_ID && ttVer == SENTINEL_ENTITY_VERSION
                    "OWNED" -> ownerId != SENTINEL_REQUEST_ID && ttId >= 1L && ttVer >= 0
                    else -> false
                }
                if (!consistent) return null
                build(cmd, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly,
                    targetMgdl = target, durationMin = duration, reasonKey = reason,
                    clientPolicyHash = hash, expectedState = state,
                    expectedOwnerRequestId = ownerId, expectedTtDbId = ttId, expectedTtEntityVersion = ttVer,
                    canonicalString = "",
                ))
            }
            Cmd.CANCEL_OWNED_TEMP_TARGET -> {
                // v1.4/R4 §2: expectedOwnerPolicyHash (Erstellungs-Hash) statt aktuellem clientPolicyHash.
                if (!keysExactly("validateOnly", "expectedOwnerPolicyHash", "expectedOwnerRequestId", "expectedTtDbId", "expectedTtEntityVersion")) return null
                val validateOnly = asBool(p, "validateOnly") ?: return null
                val ownerHash = asString(p, "expectedOwnerPolicyHash")?.takeIf { HEX64.matches(it) } ?: return null
                val ownerId = asString(p, "expectedOwnerRequestId")?.takeIf { HEX32.matches(it) && it != SENTINEL_REQUEST_ID } ?: return null
                val ttId = asLong(p, "expectedTtDbId")?.takeIf { it >= 1L } ?: return null
                val ttVer = asInt(p, "expectedTtEntityVersion")?.takeIf { it >= 0 } ?: return null
                build(cmd, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly,
                    expectedOwnerPolicyHash = ownerHash,
                    expectedOwnerRequestId = ownerId, expectedTtDbId = ttId, expectedTtEntityVersion = ttVer,
                    canonicalString = "",
                ))
            }
            Cmd.SET_IOBTH -> {
                // A1 Variante B analog TT-SET: ALLE 6 Felder immer Pflicht; NONE verlangt
                // exakt das Sentinel-Paar, OWNED echte Werte. clientPolicyHash = IOBTH-Policy.
                if (!keysExactly("percent", "ttlMin", "validateOnly", "clientPolicyHash", "expectedState", "expectedLeaseId", "expectedLeaseVersion")) return null
                val state = asString(p, "expectedState") ?: return null
                val hash = asString(p, "clientPolicyHash")?.takeIf { HEX64.matches(it) } ?: return null
                val percent = asInt(p, "percent") ?: return null
                val ttl = asInt(p, "ttlMin") ?: return null
                val validateOnly = asBool(p, "validateOnly") ?: return null
                val leaseId = asString(p, "expectedLeaseId")?.takeIf { HEX32.matches(it) } ?: return null
                val leaseVer = asLong(p, "expectedLeaseVersion") ?: return null
                val consistent = when (state) {
                    "NONE" -> leaseId == SENTINEL_REQUEST_ID && leaseVer == SENTINEL_LEASE_VERSION
                    "OWNED" -> leaseId != SENTINEL_REQUEST_ID && leaseVer >= 1L
                    else -> false
                }
                if (!consistent) return null
                build(cmd, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly,
                    clientPolicyHash = hash, expectedState = state,
                    percent = percent, ttlMin = ttl,
                    expectedLeaseId = leaseId, expectedLeaseVersion = leaseVer,
                    canonicalString = "",
                ))
            }
            Cmd.CLEAR_IOBTH -> {
                // Analog TT-CANCEL: expectedOwnerPolicyHash = Hash der LEASE-ERSTELLUNG
                // (R11/F5: Policy-Upgrade blockiert das Beenden nie).
                if (!keysExactly("validateOnly", "expectedOwnerPolicyHash", "expectedLeaseId", "expectedLeaseVersion")) return null
                val validateOnly = asBool(p, "validateOnly") ?: return null
                val ownerHash = asString(p, "expectedOwnerPolicyHash")?.takeIf { HEX64.matches(it) } ?: return null
                val leaseId = asString(p, "expectedLeaseId")?.takeIf { HEX32.matches(it) && it != SENTINEL_REQUEST_ID } ?: return null
                val leaseVer = asLong(p, "expectedLeaseVersion")?.takeIf { it >= 1L } ?: return null
                build(cmd, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly,
                    expectedOwnerPolicyHash = ownerHash,
                    expectedLeaseId = leaseId, expectedLeaseVersion = leaseVer,
                    canonicalString = "",
                ))
            }
            Cmd.GET_COMMAND_STATUS -> {
                if (!keysExactly("queryRequestId")) return null
                // R5-F1: der Sentinel ist per Definition nie eine persistierte echte Request-ID.
                val q = asString(p, "queryRequestId")?.takeIf { HEX32.matches(it) && it != SENTINEL_REQUEST_ID } ?: return null
                build(cmd, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly = false,
                    queryRequestId = q, canonicalString = "",
                ))
            }
            Cmd.GET_SERVICE_STATUS -> {
                if (p.length() != 0) return null
                build(cmd, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly = false, canonicalString = "",
                ))
            }
        }
    }

    /** Kanonischer String wird aus dem TYPISIERTEN Request neu erzeugt (R3-A1). */
    private fun build(cmd: Cmd, requestId: String, issuedAt: Long, expiresAt: Long, r: Request): Request {
        val canonParams = canonicalParams(r)
        return r.copy(canonicalString = canonicalString(cmd.name, canonParams, issuedAt, expiresAt, requestId))
    }

    /** Sortierte Schluessel, keine Whitespaces, Int dezimal, Bool true/false, ASCII-Strings. */
    fun canonicalParams(r: Request): String {
        val fields = sortedMapOf<String, String>()
        fun putS(k: String, v: String) { fields[k] = "\"" + v + "\"" }   // ASCII-only per Schema
        fun putI(k: String, v: Long) { fields[k] = v.toString() }
        fun putB(k: String, v: Boolean) { fields[k] = if (v) "true" else "false" }
        when (r.cmd) {
            Cmd.SET_OWNED_TEMP_TARGET -> {
                // v1.4 Variante B: alle 9 Felder IMMER im kanonischen String (auch die Sentinels).
                putI("durationMin", r.durationMin!!.toLong()); putS("reasonKey", r.reasonKey!!.name)
                putI("targetMgdl", r.targetMgdl!!.toLong()); putB("validateOnly", r.validateOnly)
                putS("clientPolicyHash", r.clientPolicyHash!!); putS("expectedState", r.expectedState!!)
                putS("expectedOwnerRequestId", r.expectedOwnerRequestId!!)
                putI("expectedTtDbId", r.expectedTtDbId!!); putI("expectedTtEntityVersion", r.expectedTtEntityVersion!!.toLong())
            }
            Cmd.CANCEL_OWNED_TEMP_TARGET -> {
                putB("validateOnly", r.validateOnly)
                putS("expectedOwnerPolicyHash", r.expectedOwnerPolicyHash!!)
                putS("expectedOwnerRequestId", r.expectedOwnerRequestId!!)
                putI("expectedTtDbId", r.expectedTtDbId!!); putI("expectedTtEntityVersion", r.expectedTtEntityVersion!!.toLong())
            }
            Cmd.SET_IOBTH -> {
                putI("percent", r.percent!!.toLong()); putI("ttlMin", r.ttlMin!!.toLong())
                putB("validateOnly", r.validateOnly)
                putS("clientPolicyHash", r.clientPolicyHash!!); putS("expectedState", r.expectedState!!)
                putS("expectedLeaseId", r.expectedLeaseId!!); putI("expectedLeaseVersion", r.expectedLeaseVersion!!)
            }
            Cmd.CLEAR_IOBTH -> {
                putB("validateOnly", r.validateOnly)
                putS("expectedOwnerPolicyHash", r.expectedOwnerPolicyHash!!)
                putS("expectedLeaseId", r.expectedLeaseId!!); putI("expectedLeaseVersion", r.expectedLeaseVersion!!)
            }
            Cmd.GET_COMMAND_STATUS -> putS("queryRequestId", r.queryRequestId!!)
            Cmd.GET_SERVICE_STATUS -> {}
        }
        return fields.entries.joinToString(",", prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
    }

    fun canonicalString(cmd: String, canonParams: String, issuedAt: Long, expiresAt: Long, requestId: String): String =
        "$PROTOCOL_VERSION|$TARGET_PACKAGE|$cmd|$canonParams|$issuedAt|$expiresAt|$requestId"

    fun hmacHex(secret: ByteArray, canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // ---- Gate-Prioritaet (v1.2-A4 + R3-A4): Schalter reduzieren nur, nie erzeugen. ----
    // A1: iobthCapabilityEnabled default false — bestehende Aufrufer/Vektoren unveraendert (G3).
    data class GateConfig(val channelEnabled: Boolean, val ttCapabilityEnabled: Boolean, val forcedValidateOnly: Boolean, val iobthCapabilityEnabled: Boolean = false)

    /** R5-F5: expliziter Ausfuehrungsmodus statt "null = irgendwie erlaubt" — der spaetere
     *  Pilot-Bau kann validateOnly damit strukturell nicht uebersehen. */
    sealed class GateResult {
        data class Reject(val errorCode: String) : GateResult()
        object ReadOnly : GateResult()
        object ValidateOnly : GateResult()
        object Apply : GateResult()
    }

    /**
     * v1.4/R4-§1-Command-Gate-Matrix (einzige normative Quelle): read-only-Befehle stehen
     * AUSSERHALB der Channel-/Capability-Gates (sonst koennte der Preflight channelEnabled=false
     * nie berichten; GET_COMMAND_STATUS ist der Recovery-Pfad). Nur Mutationen sind gegated;
     * forcedValidateOnly/request.validateOnly reduzieren APPLY auf VALIDATE_ONLY.
     */
    fun gate(cfg: GateConfig, req: Request): GateResult = when (req.cmd) {
        Cmd.GET_SERVICE_STATUS, Cmd.GET_COMMAND_STATUS -> GateResult.ReadOnly
        Cmd.SET_OWNED_TEMP_TARGET, Cmd.CANCEL_OWNED_TEMP_TARGET -> when {
            !cfg.channelEnabled -> GateResult.Reject(E_CHANNEL_DISABLED)
            !cfg.ttCapabilityEnabled -> GateResult.Reject(E_CAPABILITY_DISABLED)
            // Build-Wachposten: in einem Build ohne Mutationszweig (historischer OFF/Auth-
            // Stand) enden auch validateOnly-Pfade hier; im Pilot-Build immer durchlaessig.
            !MUTATION_BUILD_PRESENT -> GateResult.Reject(E_MUTATION_UNAVAILABLE)
            cfg.forcedValidateOnly || req.validateOnly -> GateResult.ValidateOnly
            else -> GateResult.Apply
        }
        // A1: eigener Capability-Schalter je Wert-Hebel (Matrix-Prinzip); sonst identische Semantik.
        Cmd.SET_IOBTH, Cmd.CLEAR_IOBTH -> when {
            !cfg.channelEnabled -> GateResult.Reject(E_CHANNEL_DISABLED)
            !cfg.iobthCapabilityEnabled -> GateResult.Reject(E_CAPABILITY_DISABLED)
            !MUTATION_BUILD_PRESENT -> GateResult.Reject(E_MUTATION_UNAVAILABLE)
            cfg.forcedValidateOnly || req.validateOnly -> GateResult.ValidateOnly
            else -> GateResult.Apply
        }
    }
}

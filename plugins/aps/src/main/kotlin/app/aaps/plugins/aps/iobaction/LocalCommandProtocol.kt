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
 * OFF/Auth-Build: MUTATION_BUILD_PRESENT=false — es existiert kein Mutationszweig;
 * SET/CANCEL koennen dieses Build-Artefakt nie ueber die Gates hinaus erreichen.
 */
object LocalCommandProtocol {

    const val PROTOCOL_VERSION = "v1"
    const val TARGET_PACKAGE = "info.nightscout.androidaps"
    const val MAX_PAYLOAD_BYTES = 4096
    const val MAX_CLOCK_SKEW_PAST_MS = 5_000L
    const val MAX_VALIDITY_WINDOW_MS = 30_000L
    /** R3-Grenze: dieser Build enthaelt strukturell keinen Mutationspfad. */
    const val MUTATION_BUILD_PRESENT = false

    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    enum class Cmd { SET_OWNED_TEMP_TARGET, CANCEL_OWNED_TEMP_TARGET, GET_COMMAND_STATUS, GET_SERVICE_STATUS }
    enum class ReasonKey { PEAK_STOP, CORRECTION, REBOUND, BRAKE, MEAL, LOW_PROTECT }

    // Fehler-Codes (abschliessend fuer OFF/Auth; neutral, kein Detail-Leak)
    const val E_MALFORMED = "REJECTED_MALFORMED"
    const val E_AUTH = "REJECTED_AUTH"
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
        val clientPolicyHash: String? = null,
        val expectedState: String? = null,  // "NONE" | "OWNED" (SET); CANCEL: implizit OWNED
        val expectedOwnerRequestId: String? = null,
        val expectedTtDbId: Long? = null,
        val expectedTtEntityVersion: Int? = null,
        // STATUS
        val queryRequestId: String? = null,
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
        val requestId = asString(root, "requestId")?.takeIf { HEX32.matches(it) }
            ?: return ParseOutcome(null, E_MALFORMED)
        val issuedAt = asLong(root, "issuedAt") ?: return ParseOutcome(null, E_MALFORMED)
        val expiresAt = asLong(root, "expiresAt") ?: return ParseOutcome(null, E_MALFORMED)
        val params = root.optJSONObject("params") ?: return ParseOutcome(null, E_MALFORMED)

        // Zeitfenster: Gueltigkeit max. 30 s, now ∈ [issuedAt-5s, expiresAt]
        if (expiresAt <= issuedAt || expiresAt - issuedAt > MAX_VALIDITY_WINDOW_MS) return ParseOutcome(null, E_TIME)
        if (nowMs < issuedAt - MAX_CLOCK_SKEW_PAST_MS || nowMs > expiresAt) return ParseOutcome(null, E_TIME)

        val req = parseParams(cmd, params, requestId, issuedAt, expiresAt) ?: return ParseOutcome(null, E_MALFORMED)
        // Bounds (v1.1-Hard-Bounds; Policy-Matrix ist Server-Phase 2+)
        if (cmd == Cmd.SET_OWNED_TEMP_TARGET) {
            if (req.targetMgdl!! !in 70..161) return ParseOutcome(null, E_BOUNDS)
            if (req.durationMin!! !in 5..120) return ParseOutcome(null, E_BOUNDS)
        }
        if (secret == null || secret.isEmpty()) return ParseOutcome(null, E_AUTH)
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
                val base = arrayOf("targetMgdl", "durationMin", "reasonKey", "validateOnly", "clientPolicyHash", "expectedState")
                val owned = base + arrayOf("expectedOwnerRequestId", "expectedTtDbId", "expectedTtEntityVersion")
                val state = asString(p, "expectedState") ?: return null
                val ok = when (state) {
                    "NONE" -> keysExactly(*base)
                    "OWNED" -> keysExactly(*owned)
                    else -> false
                }
                if (!ok) return null
                val reason = asString(p, "reasonKey")?.let { r -> ReasonKey.entries.firstOrNull { it.name == r } } ?: return null
                val hash = asString(p, "clientPolicyHash")?.takeIf { HEX64.matches(it) } ?: return null
                val target = asInt(p, "targetMgdl") ?: return null
                val duration = asInt(p, "durationMin") ?: return null
                val validateOnly = asBool(p, "validateOnly") ?: return null
                val ownerId = if (state == "OWNED") asString(p, "expectedOwnerRequestId")?.takeIf { HEX32.matches(it) } ?: return null else null
                val ttId = if (state == "OWNED") asLong(p, "expectedTtDbId") ?: return null else null
                val ttVer = if (state == "OWNED") asInt(p, "expectedTtEntityVersion") ?: return null else null
                build(cmd, p, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly,
                    targetMgdl = target, durationMin = duration, reasonKey = reason,
                    clientPolicyHash = hash, expectedState = state,
                    expectedOwnerRequestId = ownerId, expectedTtDbId = ttId, expectedTtEntityVersion = ttVer,
                    canonicalString = "",
                ))
            }
            Cmd.CANCEL_OWNED_TEMP_TARGET -> {
                if (!keysExactly("validateOnly", "expectedOwnerRequestId", "expectedTtDbId", "expectedTtEntityVersion")) return null
                val validateOnly = asBool(p, "validateOnly") ?: return null
                val ownerId = asString(p, "expectedOwnerRequestId")?.takeIf { HEX32.matches(it) } ?: return null
                val ttId = asLong(p, "expectedTtDbId") ?: return null
                val ttVer = asInt(p, "expectedTtEntityVersion") ?: return null
                build(cmd, p, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly,
                    expectedOwnerRequestId = ownerId, expectedTtDbId = ttId, expectedTtEntityVersion = ttVer,
                    canonicalString = "",
                ))
            }
            Cmd.GET_COMMAND_STATUS -> {
                if (!keysExactly("queryRequestId")) return null
                val q = asString(p, "queryRequestId")?.takeIf { HEX32.matches(it) } ?: return null
                build(cmd, p, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly = false,
                    queryRequestId = q, canonicalString = "",
                ))
            }
            Cmd.GET_SERVICE_STATUS -> {
                if (p.length() != 0) return null
                build(cmd, p, requestId, issuedAt, expiresAt, Request(
                    cmd, requestId, issuedAt, expiresAt, validateOnly = false, canonicalString = "",
                ))
            }
        }
    }

    /** Kanonischer String wird aus dem TYPISIERTEN Request neu erzeugt (R3-A1). */
    private fun build(cmd: Cmd, p: JSONObject, requestId: String, issuedAt: Long, expiresAt: Long, r: Request): Request {
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
                putI("durationMin", r.durationMin!!.toLong()); putS("reasonKey", r.reasonKey!!.name)
                putI("targetMgdl", r.targetMgdl!!.toLong()); putB("validateOnly", r.validateOnly)
                putS("clientPolicyHash", r.clientPolicyHash!!); putS("expectedState", r.expectedState!!)
                if (r.expectedState == "OWNED") {
                    putS("expectedOwnerRequestId", r.expectedOwnerRequestId!!)
                    putI("expectedTtDbId", r.expectedTtDbId!!); putI("expectedTtEntityVersion", r.expectedTtEntityVersion!!.toLong())
                }
            }
            Cmd.CANCEL_OWNED_TEMP_TARGET -> {
                putB("validateOnly", r.validateOnly)
                putS("expectedOwnerRequestId", r.expectedOwnerRequestId!!)
                putI("expectedTtDbId", r.expectedTtDbId!!); putI("expectedTtEntityVersion", r.expectedTtEntityVersion!!.toLong())
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
    data class GateConfig(val channelEnabled: Boolean, val ttCapabilityEnabled: Boolean, val forcedValidateOnly: Boolean)

    /** Liefert errorCode oder null (= Request darf zur — hier nicht existenten — Ausfuehrung). */
    fun gateDecision(cfg: GateConfig, req: Request): String? {
        if (!cfg.channelEnabled) return E_CHANNEL_DISABLED
        return when (req.cmd) {
            Cmd.GET_SERVICE_STATUS, Cmd.GET_COMMAND_STATUS -> null   // read-only, nur Channel-Gate
            Cmd.SET_OWNED_TEMP_TARGET, Cmd.CANCEL_OWNED_TEMP_TARGET -> {
                if (!cfg.ttCapabilityEnabled) return E_CAPABILITY_DISABLED
                // OFF/Auth-Build: Mutationszweig existiert nicht — auch validateOnly-Pfade
                // enden hier, weil die Validierungs-Persistenz erst mit der Room-Migration kommt.
                if (!MUTATION_BUILD_PRESENT) return E_MUTATION_UNAVAILABLE
                null
            }
        }
    }
}

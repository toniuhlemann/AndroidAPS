package app.aaps.plugins.aps.iobaction

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Capability AUTOSTATE — Protokoll und Policy (Schritt 1: Draht + Umschlag, noch ohne Executor).
 *
 * Kernaussage der Policy: sie kennt KEINE Namensliste. Welche Automation-States es gibt und
 * welche Werte sie tragen duerfen, steht in Tonis AAPS-Konfiguration; hier wird nur die FORM
 * geprueft. Die Tests pinnen deshalb die Form und den Policy-Hash, nicht irgendwelche Namen.
 */
class LocalCommandAutoStateTest {

    private val secret = "test-secret-not-for-production".toByteArray(Charsets.US_ASCII)
    private val rid = "00112233445566778899aabbccddeeff"
    private val leaseId = "11223344556677889900aabbccddeeff"
    private val sentinel = LocalCommandProtocol.SENTINEL_REQUEST_ID
    private val t0 = 1_784_500_000_000L
    private val t1 = t0 + 30_000L

    private fun root(cmd: String, params: String, requestId: String = rid): String =
        """{"v":"v1","cmd":"$cmd","params":$params,"requestId":"$requestId","issuedAt":$t0,"expiresAt":$t1}"""

    private fun setParams(
        name: String = "MEAL_ACTIVE", value: String = "true", ttl: Int = 120,
        state: String = "NONE", lease: String = sentinel, ver: Long = 0L,
        hash: String = LocalCommandAutoStatePolicy.hash(), extra: String? = null,
    ): String {
        val e = extra?.let { ""","$it":1""" } ?: ""
        return """{"stateName":"$name","stateValue":"$value","ttlMin":$ttl,"validateOnly":false,""" +
            """"clientPolicyHash":"$hash","expectedState":"$state","expectedLeaseId":"$lease",""" +
            """"expectedLeaseVersion":$ver$e}"""
    }

    private fun canonSet(
        name: String = "MEAL_ACTIVE", value: String = "true", ttl: Int = 120,
        state: String = "NONE", lease: String = sentinel, ver: Long = 0L,
    ): String =
        """{"clientPolicyHash":"${LocalCommandAutoStatePolicy.hash()}","expectedLeaseId":"$lease",""" +
            """"expectedLeaseVersion":$ver,"expectedState":"$state","stateName":"$name",""" +
            """"stateValue":"$value","ttlMin":$ttl,"validateOnly":false}"""

    private fun sign(cmd: String, canonParams: String, requestId: String = rid): String =
        LocalCommandProtocol.hmacHex(secret, LocalCommandProtocol.canonicalString(cmd, canonParams, t0, t1, requestId))

    private fun parse(payload: String, hmac: String) =
        LocalCommandProtocol.parseAndVerify(payload, hmac, secret, t0 + 1000)

    @Test fun `SET_AUTOSTATE wird geparst und kanonisiert wie spezifiziert`() {
        val out = parse(root("SET_AUTOSTATE", setParams()), sign("SET_AUTOSTATE", canonSet()))
        assertThat(out.errorCode).isNull()
        val r = out.request!!
        assertThat(r.cmd).isEqualTo(LocalCommandProtocol.Cmd.SET_AUTOSTATE)
        assertThat(r.stateName).isEqualTo("MEAL_ACTIVE")
        assertThat(r.stateValue).isEqualTo("true")
        assertThat(r.ttlMin).isEqualTo(120)
        // Die Kanonisierung muss der unabhaengig gebauten Form entsprechen (sortierte Keys).
        assertThat(LocalCommandProtocol.canonicalParams(r)).isEqualTo(canonSet())
    }

    @Test fun `CLEAR_AUTOSTATE wird geparst`() {
        val params = """{"validateOnly":false,"expectedOwnerPolicyHash":"${"cd".repeat(32)}",""" +
            """"expectedLeaseId":"$leaseId","expectedLeaseVersion":7}"""
        val canon = """{"expectedLeaseId":"$leaseId","expectedLeaseVersion":7,""" +
            """"expectedOwnerPolicyHash":"${"cd".repeat(32)}","validateOnly":false}"""
        val out = parse(root("CLEAR_AUTOSTATE", params), sign("CLEAR_AUTOSTATE", canon))
        assertThat(out.errorCode).isNull()
        assertThat(out.request!!.cmd).isEqualTo(LocalCommandProtocol.Cmd.CLEAR_AUTOSTATE)
        assertThat(out.request!!.expectedLeaseVersion).isEqualTo(7L)
    }

    @Test fun `Sentinel-Konsistenz wie bei IOBTH - NONE verlangt das Sentinel-Paar`() {
        // OWNED mit Sentinel-Lease ist inkonsistent
        val bad = setParams(state = "OWNED", lease = sentinel, ver = 0L)
        assertThat(parse(root("SET_AUTOSTATE", bad), sign("SET_AUTOSTATE", canonSet(state = "OWNED"))).errorCode)
            .isEqualTo(LocalCommandProtocol.E_MALFORMED)
        // NONE mit echter Lease ebenso
        val bad2 = setParams(state = "NONE", lease = leaseId, ver = 3L)
        assertThat(parse(root("SET_AUTOSTATE", bad2), sign("SET_AUTOSTATE", canonSet(lease = leaseId, ver = 3L))).errorCode)
            .isEqualTo(LocalCommandProtocol.E_MALFORMED)
    }

    @Test fun `unbekannte Felder werden abgelehnt statt ignoriert`() {
        assertThat(parse(root("SET_AUTOSTATE", setParams(extra = "zusatz")), sign("SET_AUTOSTATE", canonSet())).errorCode)
            .isEqualTo(LocalCommandProtocol.E_MALFORMED)
    }

    @Test fun `Form-Verstoesse werden abgelehnt - kein Clamping`() {
        fun err(name: String = "MEAL_ACTIVE", value: String = "true", ttl: Int = 120): String? =
            parse(root("SET_AUTOSTATE", setParams(name = name, value = value, ttl = ttl)),
                sign("SET_AUTOSTATE", canonSet(name = name, value = value, ttl = ttl))).errorCode
        assertThat(err(name = "ab")).isNotNull()                       // zu kurz
        assertThat(err(name = "9START")).isNotNull()                   // beginnt mit Ziffer
        assertThat(err(name = "MEAL ACTIVE")).isNotNull()              // Leerzeichen
        assertThat(err(name = "MEAL-ACTIVE")).isNotNull()              // Bindestrich
        assertThat(err(name = "MEAL_ÄCTIVE")).isNotNull()              // nicht-ASCII bricht den HMAC
        assertThat(err(value = "")).isNotNull()                        // leerer Wert
        assertThat(err(ttl = LocalCommandAutoStatePolicy.TTL_MIN - 1)).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        assertThat(err(ttl = LocalCommandAutoStatePolicy.TTL_MAX + 1)).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        // Grenzen selbst sind gueltig
        assertThat(err(ttl = LocalCommandAutoStatePolicy.TTL_MIN)).isNull()
        assertThat(err(ttl = LocalCommandAutoStatePolicy.TTL_MAX)).isNull()
    }

    @Test fun `Policy kennt keine Namensliste, nur die Form`() {
        // Beliebige wohlgeformte Namen passieren die Policy — die Wertliste des States selbst
        // ist die inhaltliche Grenze, und die kennt erst der Service zur Laufzeit.
        assertThat(LocalCommandAutoStatePolicy.isWellFormed("MEAL_ACTIVE", "true", 120)).isTrue()
        assertThat(LocalCommandAutoStatePolicy.isWellFormed("MANUAL_TH", "false", 30)).isTrue()
        assertThat(LocalCommandAutoStatePolicy.isWellFormed("Irgendwas_Neues", "STUFE_3", 720)).isTrue()
        assertThat(LocalCommandAutoStatePolicy.isWellFormed("x", "true", 120)).isFalse()
        assertThat(LocalCommandAutoStatePolicy.isWellFormed("MEAL_ACTIVE", "true", 29)).isFalse()
    }

    @Test fun `Policy-Hash ist an die kanonische Form gepinnt`() {
        assertThat(LocalCommandAutoStatePolicy.canonical())
            .isEqualTo("[\"AUTOSTATE\",[3,32],[1,32],[30,720,1],\"runtime-valuelist\"]")
        // Aendert sich die Form, MUSS der Hash wandern (neue Kohorte) — der Pin macht es laut.
        assertThat(LocalCommandAutoStatePolicy.hash()).hasLength(64)
        assertThat(LocalCommandAutoStatePolicy.hash()).isNotEqualTo(LocalCommandIobthPolicy.hash())
    }

    @Test fun `TTL-Bereich ist von IOBTH uebernommen, nicht neu erfunden`() {
        assertThat(LocalCommandAutoStatePolicy.TTL_MIN).isEqualTo(LocalCommandIobthPolicy.TTL_MIN)
        assertThat(LocalCommandAutoStatePolicy.TTL_MAX).isEqualTo(LocalCommandIobthPolicy.TTL_MAX)
    }

    @Test fun `Gate - eigener Schalter, default AUS`() {
        val req = parse(root("SET_AUTOSTATE", setParams()), sign("SET_AUTOSTATE", canonSet())).request!!
        fun gate(channel: Boolean, autostate: Boolean, iobth: Boolean = true) =
            LocalCommandProtocol.gate(LocalCommandProtocol.GateConfig(
                channelEnabled = channel, ttCapabilityEnabled = true, forcedValidateOnly = false,
                iobthCapabilityEnabled = iobth, autoStateCapabilityEnabled = autostate), req)
        // Default der GateConfig ist AUS -> Reject, auch wenn Kanal und IOBTH an sind
        assertThat(gate(channel = true, autostate = false))
            .isEqualTo(LocalCommandProtocol.GateResult.Reject(LocalCommandProtocol.E_CAPABILITY_DISABLED))
        // Master-Schalter schlaegt die Capability
        assertThat(gate(channel = false, autostate = true))
            .isEqualTo(LocalCommandProtocol.GateResult.Reject(LocalCommandProtocol.E_CHANNEL_DISABLED))
        // IOBTH-Schalter ist unabhaengig
        assertThat(gate(channel = true, autostate = true, iobth = false))
            .isEqualTo(LocalCommandProtocol.GateResult.Apply)
    }

    @Test fun `Validate-only erzwingen wirkt auch auf AUTOSTATE`() {
        val req = parse(root("SET_AUTOSTATE", setParams()), sign("SET_AUTOSTATE", canonSet())).request!!
        val g = LocalCommandProtocol.gate(LocalCommandProtocol.GateConfig(
            channelEnabled = true, ttCapabilityEnabled = true, forcedValidateOnly = true,
            iobthCapabilityEnabled = true, autoStateCapabilityEnabled = true), req)
        assertThat(g).isEqualTo(LocalCommandProtocol.GateResult.ValidateOnly)
    }
}

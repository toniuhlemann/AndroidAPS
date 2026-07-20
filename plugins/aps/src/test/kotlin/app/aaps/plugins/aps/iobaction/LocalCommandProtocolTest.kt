package app.aaps.plugins.aps.iobaction

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * LocalCommandChannel OFF/Auth — Testvektoren + Haertung (Spec v1.2/v1.3, R3-Scope).
 * Test-Secret aus der Spec; ein reales Secret existiert in keinem Fixture.
 */
class LocalCommandProtocolTest {

    private val secret = "test-secret-not-for-production".toByteArray(Charsets.US_ASCII)
    private val rid = "00112233445566778899aabbccddeeff"
    private val policyHash = "ab".repeat(32)
    private val t0 = 1_784_500_000_000L

    // --- R3-verifizierter Normativ-Vektor (v1.2-Feldsatz): HMAC-Maschinerie + Stringformat ---
    @Test fun normativeVectorFromR3() {
        val canon = """{"durationMin":60,"reasonKey":"MEAL","targetMgdl":76,"validateOnly":false}"""
        val s = LocalCommandProtocol.canonicalString("SET_OWNED_TEMP_TARGET", canon, 1784500000000L, 1784500030000L, rid)
        assertThat(s).isEqualTo(
            "v1|info.nightscout.androidaps|SET_OWNED_TEMP_TARGET|" +
                """{"durationMin":60,"reasonKey":"MEAL","targetMgdl":76,"validateOnly":false}|1784500000000|1784500030000|""" + rid
        )
        assertThat(LocalCommandProtocol.hmacHex(secret, s))
            .isEqualTo("980e7012d44a15176bf20f4accb8492cc4c0e8bd0f68cef8e72834140acc7bc2")
    }

    // --- v1.3-SET (expectedState=NONE): Roundtrip + kanonischer String literal fixiert ---
    private fun setPayload(
        target: Int = 76, duration: Int = 5, reason: String = "MEAL", validateOnly: Boolean = false,
        state: String = "NONE", extraRoot: String? = null, extraParam: String? = null,
        requestId: String = rid, issuedAt: Long = t0, expiresAt: Long = t0 + 30_000,
    ): String {
        val params = StringBuilder()
        params.append("""{"targetMgdl":$target,"durationMin":$duration,"reasonKey":"$reason","validateOnly":$validateOnly,"clientPolicyHash":"$policyHash","expectedState":"$state"""")
        extraParam?.let { params.append(""","$it":1""") }
        params.append("}")
        val root = StringBuilder()
        root.append("""{"v":"v1","cmd":"SET_OWNED_TEMP_TARGET","params":$params,"requestId":"$requestId","issuedAt":$issuedAt,"expiresAt":$expiresAt""")
        extraRoot?.let { root.append(""","$it":1""") }
        root.append("}")
        return root.toString()
    }

    private fun signedHmac(payload: String, now: Long = t0 + 1000): String {
        // Kanonischen String wie der Parser erzeugen: erst parsen lassen (mit Dummy-HMAC scheitert
        // nur der HMAC-Schritt — wir bauen ihn hier direkt aus den bekannten Feldern).
        val canon = """{"clientPolicyHash":"$policyHash","durationMin":5,"expectedState":"NONE","reasonKey":"MEAL","targetMgdl":76,"validateOnly":false}"""
        return LocalCommandProtocol.hmacHex(secret, LocalCommandProtocol.canonicalString("SET_OWNED_TEMP_TARGET", canon, t0, t0 + 30_000, rid))
    }

    @Test fun v13SetRoundtripAndCanonicalLiteral() {
        val payload = setPayload()
        val hmac = signedHmac(payload)
        val out = LocalCommandProtocol.parseAndVerify(payload, hmac, secret, t0 + 1000)
        assertThat(out.errorCode).isNull()
        val r = out.request!!
        assertThat(r.cmd).isEqualTo(LocalCommandProtocol.Cmd.SET_OWNED_TEMP_TARGET)
        assertThat(r.targetMgdl).isEqualTo(76)
        // Kanonisierung aus dem TYPISIERTEN Objekt, literal fixiert (Format-Drift-Wache):
        assertThat(LocalCommandProtocol.canonicalParams(r)).isEqualTo(
            """{"clientPolicyHash":"$policyHash","durationMin":5,"expectedState":"NONE","reasonKey":"MEAL","targetMgdl":76,"validateOnly":false}"""
        )
    }

    // --- Haertung: unknown-reject auf Root- und Param-Ebene, Typ-Strenge, Formate ---
    @Test fun hardeningRejects() {
        val ok = setPayload(); val mac = signedHmac(ok)
        fun err(payload: String, hmac: String = mac, now: Long = t0 + 1000): String? =
            LocalCommandProtocol.parseAndVerify(payload, hmac, secret, now).errorCode
        assertThat(err(setPayload(extraRoot = "zusatz"))).isEqualTo(LocalCommandProtocol.E_MALFORMED)
        assertThat(err(setPayload(extraParam = "zusatz"))).isEqualTo(LocalCommandProtocol.E_MALFORMED)
        assertThat(err(ok.replace("\"targetMgdl\":76", "\"targetMgdl\":76.0"))).isEqualTo(LocalCommandProtocol.E_MALFORMED)
        assertThat(err(ok.replace("\"targetMgdl\":76", "\"targetMgdl\":\"76\""))).isEqualTo(LocalCommandProtocol.E_MALFORMED)
        assertThat(err(setPayload(requestId = rid.uppercase()))).isEqualTo(LocalCommandProtocol.E_MALFORMED)
        assertThat(err(setPayload(reason = "WAVE"))).isEqualTo(LocalCommandProtocol.E_MALFORMED)   // v1.3: WAVE existiert nicht
        assertThat(err(ok, hmac = "ff".repeat(31))).isEqualTo(LocalCommandProtocol.E_MALFORMED)     // HMAC-Laenge
        assertThat(err("x".repeat(5000))).isEqualTo(LocalCommandProtocol.E_MALFORMED)               // 4-KB-Limit
        // Zeitfenster
        assertThat(err(ok, now = t0 + 31_000)).isEqualTo(LocalCommandProtocol.E_TIME)               // abgelaufen
        assertThat(err(ok, now = t0 - 6_000)).isEqualTo(LocalCommandProtocol.E_TIME)                // zu weit vor issuedAt
        assertThat(err(setPayload(expiresAt = t0 + 31_000))).isEqualTo(LocalCommandProtocol.E_TIME) // Fenster > 30 s
        // Bounds: ablehnen, nie clampen
        assertThat(err(setPayload(target = 69), signedDynamic(target = 69))).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        assertThat(err(setPayload(target = 162), signedDynamic(target = 162))).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        assertThat(err(setPayload(duration = 4), signedDynamic(duration = 4))).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        assertThat(err(setPayload(duration = 121), signedDynamic(duration = 121))).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        // Auth
        assertThat(err(ok, hmac = "ff".repeat(32))).isEqualTo(LocalCommandProtocol.E_AUTH)          // falscher HMAC
        assertThat(LocalCommandProtocol.parseAndVerify(ok, mac, null, t0 + 1000).errorCode)
            .isEqualTo(LocalCommandProtocol.E_AUTH)                                                  // kein Secret = deny
    }

    private fun signedDynamic(target: Int = 76, duration: Int = 5): String {
        val canon = """{"clientPolicyHash":"$policyHash","durationMin":$duration,"expectedState":"NONE","reasonKey":"MEAL","targetMgdl":$target,"validateOnly":false}"""
        return LocalCommandProtocol.hmacHex(secret, LocalCommandProtocol.canonicalString("SET_OWNED_TEMP_TARGET", canon, t0, t0 + 30_000, rid))
    }

    // --- Gate-Prioritaet: Schalter reduzieren nur; Mutationszweig existiert nicht ---
    @Test fun gatePriorityAndMutationAbsence() {
        val setReq = LocalCommandProtocol.parseAndVerify(setPayload(), signedHmac(setPayload()), secret, t0 + 1000).request!!
        val statusPayload = """{"v":"v1","cmd":"GET_SERVICE_STATUS","params":{},"requestId":"$rid","issuedAt":$t0,"expiresAt":${t0 + 30_000}}"""
        val statusMac = LocalCommandProtocol.hmacHex(secret, LocalCommandProtocol.canonicalString("GET_SERVICE_STATUS", "{}", t0, t0 + 30_000, rid))
        val statusReq = LocalCommandProtocol.parseAndVerify(statusPayload, statusMac, secret, t0 + 1000).request!!
        fun cfg(ch: Boolean, tt: Boolean, vo: Boolean = false) = LocalCommandProtocol.GateConfig(ch, tt, vo)
        // Kanal aus blockt ALLES (auch read-only)
        assertThat(LocalCommandProtocol.gateDecision(cfg(false, true), setReq)).isEqualTo(LocalCommandProtocol.E_CHANNEL_DISABLED)
        assertThat(LocalCommandProtocol.gateDecision(cfg(false, true), statusReq)).isEqualTo(LocalCommandProtocol.E_CHANNEL_DISABLED)
        // Capability aus blockt Mutation, nicht Status
        assertThat(LocalCommandProtocol.gateDecision(cfg(true, false), setReq)).isEqualTo(LocalCommandProtocol.E_CAPABILITY_DISABLED)
        assertThat(LocalCommandProtocol.gateDecision(cfg(true, false), statusReq)).isNull()
        // Alles an: OFF/Auth-Build hat KEINEN Mutationszweig — validateOnly/forced aendern das nicht
        assertThat(LocalCommandProtocol.gateDecision(cfg(true, true), setReq)).isEqualTo(LocalCommandProtocol.E_MUTATION_UNAVAILABLE)
        assertThat(LocalCommandProtocol.gateDecision(cfg(true, true, vo = true), setReq)).isEqualTo(LocalCommandProtocol.E_MUTATION_UNAVAILABLE)
        assertThat(LocalCommandProtocol.MUTATION_BUILD_PRESENT).isFalse()
    }

    // --- Caller-Entscheidung: Shared-UID-Prinzip, default-deny ---
    @Test fun callerDecision() {
        val viewer = LocalCommandAuth.EXPECTED_VIEWER_PACKAGE
        val trusted = byteArrayOf(1, 2, 3); val evil = byteArrayOf(9, 9, 9)
        fun pkg(name: String, vararg s: ByteArray) = LocalCommandAuth.PackageSigners(name, s.toList())
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer, trusted)), emptyList())).isFalse()          // default-deny
        assertThat(LocalCommandAuth.decide(emptyList(), listOf(trusted))).isFalse()
        assertThat(LocalCommandAuth.decide(listOf(pkg("com.other", trusted)), listOf(trusted))).isFalse() // Viewer fehlt
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer, trusted)), listOf(trusted))).isTrue()
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer, trusted), pkg("com.extra", evil)), listOf(trusted))).isFalse() // fremde Zusatz-App
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer, trusted, evil)), listOf(trusted))).isFalse() // Multi-Signer teilweise fremd
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer)), listOf(trusted))).isFalse()               // keine Signatur lesbar
    }
}

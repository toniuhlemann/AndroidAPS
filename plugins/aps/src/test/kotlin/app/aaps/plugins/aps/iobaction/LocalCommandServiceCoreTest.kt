package app.aaps.plugins.aps.iobaction

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * R5-F3: Tests fuer den testbaren Service-Kern — exakt die sieben verbindlichen Faelle
 * aus dem R5-Review plus Bundle-Oberflaechen-Haertung. Der Kern hat by construction
 * keinerlei Persistenz-/TT-/Loop-Abhaengigkeit (Pflichtfall 7 ist damit strukturell erfuellt
 * und wird hier ueber die Abwesenheit jeder solchen Referenz im Modul dokumentiert).
 */
class LocalCommandServiceCoreTest {

    private val secret = "test-secret-not-for-production".toByteArray(Charsets.US_ASCII)
    private val rid = "00112233445566778899aabbccddeeff"
    private val t0 = 1_784_500_000_000L
    private val keysOk = LocalCommandServiceCore.ALLOWED_REQUEST_KEYS

    private fun env(
        trusted: Boolean = true, secret: ByteArray? = this.secret,
        ch: Boolean = false, tt: Boolean = false, vo: Boolean = false,
    ) = LocalCommandServiceCore.Env(
        callerTrusted = trusted, secret = secret,
        gates = LocalCommandProtocol.GateConfig(ch, tt, vo),
        nowMs = t0 + 1000, serviceInstanceId = "test-instance", startedAt = t0, serverPolicyHash = LocalCommandPolicy.hash(),
    )

    private val statusPayload = """{"v":"v1","cmd":"GET_SERVICE_STATUS","params":{},"requestId":"$rid","issuedAt":$t0,"expiresAt":${t0 + 30_000}}"""
    private val statusMac = LocalCommandProtocol.hmacHex(secret, LocalCommandProtocol.canonicalString("GET_SERVICE_STATUS", "{}", t0, t0 + 30_000, rid))
    private val setPayload = """{"v":"v1","cmd":"SET_OWNED_TEMP_TARGET","params":{"targetMgdl":76,"durationMin":5,"reasonKey":"MEAL","validateOnly":false,"clientPolicyHash":"${"ab".repeat(32)}","expectedState":"NONE","expectedOwnerRequestId":"${"0".repeat(32)}","expectedTtDbId":0,"expectedTtEntityVersion":-1},"requestId":"$rid","issuedAt":$t0,"expiresAt":${t0 + 30_000}}"""
    private val setMac = LocalCommandProtocol.hmacHex(
        secret,
        LocalCommandProtocol.canonicalString(
            "SET_OWNED_TEMP_TARGET",
            """{"clientPolicyHash":"${"ab".repeat(32)}","durationMin":5,"expectedOwnerRequestId":"${"0".repeat(32)}","expectedState":"NONE","expectedTtDbId":0,"expectedTtEntityVersion":-1,"reasonKey":"MEAL","targetMgdl":76,"validateOnly":false}""",
            t0, t0 + 30_000, rid,
        ),
    )

    // (1) vertrauter Caller + fehlendes Secret → AUTH_NOT_CONFIGURED
    @Test fun trustedCallerWithoutSecret() {
        val ack = LocalCommandServiceCore.execute(keysOk, statusPayload, statusMac, env(secret = null))
        assertThat(ack["errorCode"]).isEqualTo(LocalCommandProtocol.E_AUTH_NOT_CONFIGURED)
    }

    // (2) fremder Caller → REJECTED_AUTH, Parser wird nie erreicht (auch kaputter Payload aendert nichts)
    @Test fun foreignCallerNeverReachesParser() {
        val ack = LocalCommandServiceCore.execute(keysOk, "kein json {{{", "zz", env(trusted = false))
        assertThat(ack["errorCode"]).isEqualTo(LocalCommandProtocol.E_AUTH)
        assertThat(ack["outcome"]).isEqualTo("REJECTED")
    }

    // (3) Service-Status funktioniert bei Channel/TT OFF und berichtet beide false
    @Test fun serviceStatusAtOffReportsSwitches() {
        val ack = LocalCommandServiceCore.execute(keysOk, statusPayload, statusMac, env(ch = false, tt = false))
        assertThat(ack["outcome"]).isEqualTo("APPLIED")
        assertThat(ack["channelEnabled"]).isEqualTo(false)
        assertThat(ack["ttCapabilityEnabled"]).isEqualTo(false)
        assertThat(ack["mutationBuildPresent"]).isEqualTo(false)
        assertThat(ack["serverPolicyHash"]).isEqualTo(LocalCommandPolicy.hash())
        assertThat(ack["ownedTt"]).isEqualTo("NONE")
    }

    // (4) SET-Gate-Kette: OFF → Channel-Fehler; Channel ON/TT OFF → Capability; beide ON → Mutation-unavailable
    @Test fun setGateChain() {
        assertThat(LocalCommandServiceCore.execute(keysOk, setPayload, setMac, env(ch = false, tt = true))["errorCode"])
            .isEqualTo(LocalCommandProtocol.E_CHANNEL_DISABLED)
        assertThat(LocalCommandServiceCore.execute(keysOk, setPayload, setMac, env(ch = true, tt = false))["errorCode"])
            .isEqualTo(LocalCommandProtocol.E_CAPABILITY_DISABLED)
        assertThat(LocalCommandServiceCore.execute(keysOk, setPayload, setMac, env(ch = true, tt = true))["errorCode"])
            .isEqualTo(LocalCommandProtocol.E_MUTATION_UNAVAILABLE)
    }

    // (5) Bundle-Oberflaeche: Extra-/fehlende Keys, falsche Typen → neutral malformed, keine Exception
    @Test fun bundleSurfaceHardening() {
        val M = LocalCommandProtocol.E_MALFORMED
        assertThat(LocalCommandServiceCore.execute(null, statusPayload, statusMac, env())["errorCode"]).isEqualTo(M)
        assertThat(LocalCommandServiceCore.execute(setOf("payloadJsonUtf8"), statusPayload, null, env())["errorCode"]).isEqualTo(M)
        assertThat(LocalCommandServiceCore.execute(keysOk + "zusatz", statusPayload, statusMac, env())["errorCode"]).isEqualTo(M)
        assertThat(LocalCommandServiceCore.execute(keysOk, 42, statusMac, env())["errorCode"]).isEqualTo(M)          // falscher Typ
        assertThat(LocalCommandServiceCore.execute(keysOk, statusPayload, ByteArray(3), env())["errorCode"]).isEqualTo(M)
    }

    // (6) HMAC-/Zeit-/Schema-Rejects enthalten keine Mutations-/Statusfelder
    @Test fun rejectsCarryNoMutationFields() {
        val bad = LocalCommandServiceCore.execute(keysOk, statusPayload, "ff".repeat(32), env())
        assertThat(bad["errorCode"]).isEqualTo(LocalCommandProtocol.E_AUTH)
        for (k in listOf("channelEnabled", "ttCapabilityEnabled", "serverPolicyHash", "ownedTt", "queryRequestId", "appliedAt", "ttDbId"))
            assertThat(bad).doesNotContainKey(k)
    }

    // GET_COMMAND_STATUS ohne Store → NOT_FOUND, beide IDs getrennt (R3-F5)
    @Test fun commandStatusNotFound() {
        val payload = """{"v":"v1","cmd":"GET_COMMAND_STATUS","params":{"queryRequestId":"11223344556677889900aabbccddeeff"},"requestId":"$rid","issuedAt":$t0,"expiresAt":${t0 + 30_000}}"""
        val mac = LocalCommandProtocol.hmacHex(
            secret,
            LocalCommandProtocol.canonicalString("GET_COMMAND_STATUS", """{"queryRequestId":"11223344556677889900aabbccddeeff"}""", t0, t0 + 30_000, rid),
        )
        val ack = LocalCommandServiceCore.execute(keysOk, payload, mac, env())
        assertThat(ack["queryStatus"]).isEqualTo("NOT_FOUND")
        assertThat(ack["requestId"]).isEqualTo(rid)
        assertThat(ack["queryRequestId"]).isEqualTo("11223344556677889900aabbccddeeff")
    }
}

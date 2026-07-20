package app.aaps.plugins.aps.iobaction

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * LocalCommandChannel OFF/Auth — v1.4-Testvektoren + Haertung (Spec v1.4 §6, R3/R4-Scope).
 * Die HMAC-Vektoren sind die GEMEINSAMEN Fork-/Viewer-Fixtures aus der Spec (unabhaengig
 * mit Python vorberechnet); Test-Secret aus der Spec — ein reales Secret existiert in
 * keinem Fixture. Der Policy-Hash ist der von Codex R4 unabhaengig berechnete Wert.
 */
class LocalCommandProtocolTest {

    private val secret = "test-secret-not-for-production".toByteArray(Charsets.US_ASCII)
    private val rid = "00112233445566778899aabbccddeeff"
    private val ownerId = "11223344556677889900aabbccddeeff"
    private val policyHash = "ab".repeat(32)
    private val ownerPolicyHash = "cd".repeat(32)
    private val sentinel = LocalCommandProtocol.SENTINEL_REQUEST_ID
    private val t0 = 1_784_500_000_000L
    private val t1 = t0 + 30_000L

    private fun root(cmd: String, params: String, requestId: String = rid, issuedAt: Long = t0, expiresAt: Long = t1, extraRoot: String? = null): String {
        val extra = extraRoot?.let { ""","$it":1""" } ?: ""
        return """{"v":"v1","cmd":"$cmd","params":$params,"requestId":"$requestId","issuedAt":$issuedAt,"expiresAt":$expiresAt$extra}"""
    }

    private fun setParams(
        target: Int = 76, duration: Int = 5, reason: String = "MEAL", validateOnly: Boolean = false,
        state: String = "NONE", owner: String = sentinel, ttId: Long = 0, ver: Int = -1, extraParam: String? = null,
    ): String {
        val extra = extraParam?.let { ""","$it":1""" } ?: ""
        return """{"targetMgdl":$target,"durationMin":$duration,"reasonKey":"$reason","validateOnly":$validateOnly,""" +
            """"clientPolicyHash":"$policyHash","expectedState":"$state","expectedOwnerRequestId":"$owner",""" +
            """"expectedTtDbId":$ttId,"expectedTtEntityVersion":$ver$extra}"""
    }

    private fun cancelParams(owner: String = ownerId, ttId: Long = 42, ver: Int = 3): String =
        """{"validateOnly":false,"expectedOwnerPolicyHash":"$ownerPolicyHash","expectedOwnerRequestId":"$owner","expectedTtDbId":$ttId,"expectedTtEntityVersion":$ver}"""

    // --- Spec-v1.4-§6-Vektoren: Parser akzeptiert + erzeugt EXAKT die gepinnten HMACs ---
    @Test fun sharedVectorsV14() {
        fun accepted(payload: String, hmac: String): LocalCommandProtocol.Request {
            val out = LocalCommandProtocol.parseAndVerify(payload, hmac, secret, t0 + 1000)
            assertThat(out.errorCode).isNull()
            return out.request!!
        }
        // SET-NONE (76/5/MEAL + Sentinels)
        accepted(root("SET_OWNED_TEMP_TARGET", setParams()), "e85c4d858013664e4b3929e34616b8f4def6112c81ebc3b204d1427e3bdeed66")
        // SET-OWNED (88/40/MEAL, owner/42/3)
        accepted(
            root("SET_OWNED_TEMP_TARGET", setParams(target = 88, duration = 40, state = "OWNED", owner = ownerId, ttId = 42, ver = 3)),
            "6b9f2e6f4150d5501756fefc4db1190e4f299bf65e01c1a2c61720c333eb39dd",
        )
        // CANCEL (ownerPolicyHash, owner/42/3)
        accepted(root("CANCEL_OWNED_TEMP_TARGET", cancelParams()), "0a7ef503aeefd1e261871d28f02d9b698a54ee49fba4c542c711d7b2c37c0138")
        // STATUS + SERVICE_STATUS
        accepted(root("GET_COMMAND_STATUS", """{"queryRequestId":"$rid"}"""), "72bbb70bcaaa5d8eb1ab6ccbff65a6a3b4204e4dd31bc28d0cf3e19d7702071a")
        accepted(root("GET_SERVICE_STATUS", "{}"), "03c2c54b386ab68d6d5e5784ed86ca15f4d2018b5195a93dd00925ea59f57538")
        // R3-verifizierter Alt-Vektor bleibt gueltig fuer die HMAC-Maschinerie selbst:
        val legacy = LocalCommandProtocol.canonicalString(
            "SET_OWNED_TEMP_TARGET",
            """{"durationMin":60,"reasonKey":"MEAL","targetMgdl":76,"validateOnly":false}""", t0, t1, rid,
        )
        assertThat(LocalCommandProtocol.hmacHex(secret, legacy))
            .isEqualTo("980e7012d44a15176bf20f4accb8492cc4c0e8bd0f68cef8e72834140acc7bc2")
    }

    // --- Kanonisierung aus dem TYPISIERTEN Objekt, literal gepinnt (Format-Drift-Wache) ---
    @Test fun canonicalFromTypedObject() {
        val payload = root("SET_OWNED_TEMP_TARGET", setParams())
        val r = LocalCommandProtocol.parseAndVerify(payload, "e85c4d858013664e4b3929e34616b8f4def6112c81ebc3b204d1427e3bdeed66", secret, t0 + 1000).request!!
        assertThat(LocalCommandProtocol.canonicalParams(r)).isEqualTo(
            """{"clientPolicyHash":"$policyHash","durationMin":5,"expectedOwnerRequestId":"$sentinel","expectedState":"NONE","expectedTtDbId":0,"expectedTtEntityVersion":-1,"reasonKey":"MEAL","targetMgdl":76,"validateOnly":false}"""
        )
    }

    private fun sign(cmd: String, canonParams: String, requestId: String = rid): String =
        LocalCommandProtocol.hmacHex(secret, LocalCommandProtocol.canonicalString(cmd, canonParams, t0, t1, requestId))

    private fun canonSet(target: Int = 76, duration: Int = 5, state: String = "NONE", owner: String = sentinel, ttId: Long = 0, ver: Int = -1): String =
        """{"clientPolicyHash":"$policyHash","durationMin":$duration,"expectedOwnerRequestId":"$owner","expectedState":"$state","expectedTtDbId":$ttId,"expectedTtEntityVersion":$ver,"reasonKey":"MEAL","targetMgdl":$target,"validateOnly":false}"""

    // --- Haertung: unknown-reject, Typ-Strenge, Sentinel-Konsistenz (Variante B), Formate ---
    @Test fun hardeningRejects() {
        val okPayload = root("SET_OWNED_TEMP_TARGET", setParams())
        val okMac = sign("SET_OWNED_TEMP_TARGET", canonSet())
        fun err(payload: String, hmac: String = okMac, now: Long = t0 + 1000): String? =
            LocalCommandProtocol.parseAndVerify(payload, hmac, secret, now).errorCode
        val M = LocalCommandProtocol.E_MALFORMED
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(), extraRoot = "zusatz"))).isEqualTo(M)
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(extraParam = "zusatz")))).isEqualTo(M)
        assertThat(err(okPayload.replace("\"targetMgdl\":76", "\"targetMgdl\":76.0"))).isEqualTo(M)
        assertThat(err(okPayload.replace("\"targetMgdl\":76", "\"targetMgdl\":\"76\""))).isEqualTo(M)
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(), requestId = rid.uppercase()))).isEqualTo(M)
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(reason = "WAVE")))).isEqualTo(M)
        // Sentinel-Konsistenz: Mischformen sind Schema-Fehler
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(state = "NONE", owner = ownerId)))).isEqualTo(M)          // NONE + echte Owner-Id
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(state = "NONE", ttId = 42)))).isEqualTo(M)                 // NONE + echte DB-Id
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(state = "OWNED")))).isEqualTo(M)                           // OWNED + Sentinels
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(state = "OWNED", owner = ownerId, ttId = 0, ver = 3)))).isEqualTo(M)  // OWNED + dbId 0
        assertThat(err(root("CANCEL_OWNED_TEMP_TARGET", cancelParams(owner = sentinel)))).isEqualTo(M)                    // CANCEL mit Sentinel
        assertThat(err(okPayload, hmac = "ff".repeat(31))).isEqualTo(M)
        assertThat(err("x".repeat(5000))).isEqualTo(M)
        // Zeitfenster
        assertThat(err(okPayload, now = t1 + 1000)).isEqualTo(LocalCommandProtocol.E_TIME)
        assertThat(err(okPayload, now = t0 - 6_000)).isEqualTo(LocalCommandProtocol.E_TIME)
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(), expiresAt = t0 + 31_000))).isEqualTo(LocalCommandProtocol.E_TIME)
        // Bounds: ablehnen, nie clampen
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(target = 69)), sign("SET_OWNED_TEMP_TARGET", canonSet(target = 69)))).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(target = 162)), sign("SET_OWNED_TEMP_TARGET", canonSet(target = 162)))).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(duration = 4)), sign("SET_OWNED_TEMP_TARGET", canonSet(duration = 4)))).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        assertThat(err(root("SET_OWNED_TEMP_TARGET", setParams(duration = 121)), sign("SET_OWNED_TEMP_TARGET", canonSet(duration = 121)))).isEqualTo(LocalCommandProtocol.E_BOUNDS)
        // Auth: falscher HMAC vs fehlendes Secret (R4 §1: getrennte, grobe Codes)
        assertThat(err(okPayload, hmac = "ff".repeat(32))).isEqualTo(LocalCommandProtocol.E_AUTH)
        assertThat(LocalCommandProtocol.parseAndVerify(okPayload, okMac, null, t0 + 1000).errorCode)
            .isEqualTo(LocalCommandProtocol.E_AUTH_NOT_CONFIGURED)
    }

    // --- v1.4-Gate-Matrix: read-only AUSSERHALB der Gates; Mutationszweig existiert nicht ---
    @Test fun gateMatrixAndMutationAbsence() {
        val setReq = LocalCommandProtocol.parseAndVerify(
            root("SET_OWNED_TEMP_TARGET", setParams()),
            "e85c4d858013664e4b3929e34616b8f4def6112c81ebc3b204d1427e3bdeed66", secret, t0 + 1000,
        ).request!!
        val statusReq = LocalCommandProtocol.parseAndVerify(
            root("GET_SERVICE_STATUS", "{}"),
            "03c2c54b386ab68d6d5e5784ed86ca15f4d2018b5195a93dd00925ea59f57538", secret, t0 + 1000,
        ).request!!
        val cmdStatusReq = LocalCommandProtocol.parseAndVerify(
            root("GET_COMMAND_STATUS", """{"queryRequestId":"$rid"}"""),
            "72bbb70bcaaa5d8eb1ab6ccbff65a6a3b4204e4dd31bc28d0cf3e19d7702071a", secret, t0 + 1000,
        ).request!!
        fun cfg(ch: Boolean, tt: Boolean, vo: Boolean = false) = LocalCommandProtocol.GateConfig(ch, tt, vo)
        // Read-only geht IMMER durch die Gates (R4 §1: sonst kann der Preflight OFF nicht melden)
        assertThat(LocalCommandProtocol.gateDecision(cfg(false, false), statusReq)).isNull()
        assertThat(LocalCommandProtocol.gateDecision(cfg(false, false), cmdStatusReq)).isNull()
        // Mutation: Channel → Capability → Mutations-Abwesenheit
        assertThat(LocalCommandProtocol.gateDecision(cfg(false, true), setReq)).isEqualTo(LocalCommandProtocol.E_CHANNEL_DISABLED)
        assertThat(LocalCommandProtocol.gateDecision(cfg(true, false), setReq)).isEqualTo(LocalCommandProtocol.E_CAPABILITY_DISABLED)
        assertThat(LocalCommandProtocol.gateDecision(cfg(true, true), setReq)).isEqualTo(LocalCommandProtocol.E_MUTATION_UNAVAILABLE)
        assertThat(LocalCommandProtocol.gateDecision(cfg(true, true, vo = true), setReq)).isEqualTo(LocalCommandProtocol.E_MUTATION_UNAVAILABLE)
        assertThat(LocalCommandProtocol.MUTATION_BUILD_PRESENT).isFalse()
    }

    // --- Policy-Matrix: R4-verifizierter Hash + Tupel-Semantik ---
    @Test fun policyCanonicalAndHash() {
        assertThat(LocalCommandPolicy.canonical()).isEqualTo(
            """[["BRAKE",120,15],["BRAKE",130,15],["BRAKE",140,15],["CORRECTION",90,12],["LOW_PROTECT",101,30],["LOW_PROTECT",141,20],["LOW_PROTECT",161,20],["MEAL",76,5],["MEAL",88,40],["PEAK_STOP",101,30],["REBOUND",140,15]]"""
        )
        assertThat(LocalCommandPolicy.hash())
            .isEqualTo("5ec7c298695a7ac77174e283da0884b192423123944ab86041aaced60ba7b8a5")
        assertThat(LocalCommandPolicy.isAllowed(LocalCommandProtocol.ReasonKey.PEAK_STOP, 101, 30)).isTrue()
        assertThat(LocalCommandPolicy.isAllowed(LocalCommandProtocol.ReasonKey.PEAK_STOP, 90, 30)).isFalse()
        assertThat(LocalCommandPolicy.isAllowed(LocalCommandProtocol.ReasonKey.MEAL, 88, 41)).isFalse()
    }

    // --- Caller-Entscheidung: Shared-UID-Prinzip, default-deny ---
    @Test fun callerDecision() {
        val viewer = LocalCommandAuth.EXPECTED_VIEWER_PACKAGE
        val trusted = byteArrayOf(1, 2, 3); val evil = byteArrayOf(9, 9, 9)
        fun pkg(name: String, vararg s: ByteArray) = LocalCommandAuth.PackageSigners(name, s.toList())
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer, trusted)), emptyList())).isFalse()
        assertThat(LocalCommandAuth.decide(emptyList(), listOf(trusted))).isFalse()
        assertThat(LocalCommandAuth.decide(listOf(pkg("com.other", trusted)), listOf(trusted))).isFalse()
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer, trusted)), listOf(trusted))).isTrue()
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer, trusted), pkg("com.extra", evil)), listOf(trusted))).isFalse()
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer, trusted, evil)), listOf(trusted))).isFalse()
        assertThat(LocalCommandAuth.decide(listOf(pkg(viewer)), listOf(trusted))).isFalse()
    }
}

package app.aaps.iobaction

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import app.aaps.plugins.aps.iobaction.LocalCommandAuth
import app.aaps.plugins.aps.iobaction.LocalCommandPolicy
import app.aaps.plugins.aps.iobaction.LocalCommandProtocol
import app.aaps.plugins.aps.iobaction.LocalCommandServiceCore
import java.io.File
import java.security.MessageDigest

/**
 * LocalCommandChannel — Android-GLUE (R5-F3-Refactor): die gesamte Bundle→ACK-Logik lebt
 * unit-getestet in [LocalCommandServiceCore]; dieser Service sammelt nur die Umgebung
 * (Binder-Caller → Pakete → Signatur-Digests, Secret aus noBackupFilesDir, Gate-Schalter)
 * und konvertiert Map↔Bundle. OFF/Auth-Build: kein Mutationszweig, Schalter default AUS,
 * Signer-Allowlist default leer (default-deny). Deklariert NUR im full-Flavor (R5-F4).
 */
class LocalCommandService : Service() {

    private companion object {
        const val PREFS = "local_command_channel"           // eigener Namespace — NICHT im Config-Export
        const val KEY_CHANNEL = "channel_enabled"
        const val KEY_TT = "tt_capability_enabled"
        const val KEY_FORCED_VALIDATE = "forced_validate_only"
        const val KEY_TRUSTED = "trusted_signer_sha256"     // kommaseparierte Hex-Digests, default leer
        const val KEY_UNAUTH_COUNT = "unauth_count"
        const val KEY_UNAUTH_LAST = "unauth_last_ms"
        const val SECRET_FILE = "local_command_secret"      // hex, unter noBackupFilesDir
        // Validate-only-Startwert (R2-B8/v1.4): vor APPLIED per wouldRateLimit-Messung kalibrieren.
        const val RATE_CAP_PER_HOUR = 30
    }

    private val serviceInstanceId = java.util.UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()

    private val binder = object : ILocalCommandChannel.Stub() {
        override fun execute(request: Bundle?): Bundle = runCatching {
            handle(request, Binder.getCallingUid())
        }.getOrElse {
            toBundle(mapOf(
                "protocolVersion" to LocalCommandProtocol.PROTOCOL_VERSION,
                "outcome" to "REJECTED", "replayed" to false,
                "errorCode" to LocalCommandProtocol.E_MALFORMED,
            ))
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun handle(request: Bundle?, callingUid: Int): Bundle {
        val tStart = android.os.SystemClock.elapsedRealtime()
        val trusted = callerTrusted(callingUid)
        val tAuth = android.os.SystemClock.elapsedRealtime()
        if (!trusted) recordUnauthenticated()
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val repo = LocalCommandRuntime.repository
        val env = LocalCommandServiceCore.Env(
            callerTrusted = trusted,
            secret = readSecret(),
            gates = LocalCommandProtocol.GateConfig(
                channelEnabled = sp.getBoolean(KEY_CHANNEL, false),
                ttCapabilityEnabled = sp.getBoolean(KEY_TT, false),
                forcedValidateOnly = sp.getBoolean(KEY_FORCED_VALIDATE, false),
            ),
            nowMs = System.currentTimeMillis(),
            serviceInstanceId = serviceInstanceId,
            startedAt = startedAt,
            serverPolicyHash = LocalCommandPolicy.hash(),
            mutationExecutor = if (repo != null) { req, validateOnly -> executeMutation(req, validateOnly) } else null,
            ownedTtProvider = if (repo != null) ({ readOwnedTt() }) else null,
            outcomeProvider = if (repo != null) ({ q -> readOutcome(q) }) else null,
        )
        // Bundle-Rohwerte OHNE Typannahme extrahieren; Typpruefung macht der Core.
        val keys = runCatching { request?.keySet()?.toSet() }.getOrNull()
        val payload = runCatching { request?.getString("payloadJsonUtf8") }.getOrNull()
        val hmac = runCatching { request?.getString("hmacHex") }.getOrNull()
        val ack = LocalCommandServiceCore.execute(keys, payload, hmac, env)
        // R5-Punkt-7-Telemetrie: p50/p95 sammelt der Viewer aus diesen Zeilen (logcat LCC).
        android.util.Log.i("LCC", "authMs=${tAuth - tStart} totalMs=${android.os.SystemClock.elapsedRealtime() - tStart} outcome=${ack["outcome"]} err=${ack["errorCode"] ?: "-"}")
        return toBundle(ack)
    }

    /** SET/CANCEL ueber DIE eine Room-Transaktion; Audit NACH dem Commit (R2-C4). */
    private fun executeMutation(req: LocalCommandProtocol.Request, validateOnly: Boolean): Map<String, Any> {
        val repo = LocalCommandRuntime.repository
            ?: return mapOf("protocolVersion" to LocalCommandProtocol.PROTOCOL_VERSION, "outcome" to "REJECTED",
                "replayed" to false, "errorCode" to LocalCommandProtocol.E_MUTATION_UNAVAILABLE)
        // Statische Policy-Vorpruefung (Matrix + aktueller Hash) — terminal persistiert die Transaktion.
        val policyError = if (req.cmd == LocalCommandProtocol.Cmd.SET_OWNED_TEMP_TARGET) when {
            req.clientPolicyHash != LocalCommandPolicy.hash() -> "REJECTED_POLICY_VERSION"
            !LocalCommandPolicy.isAllowed(req.reasonKey!!, req.targetMgdl!!, req.durationMin!!) -> "REJECTED_POLICY"
            else -> null
        } else null
        val requestHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(req.canonicalString.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val tx = app.aaps.database.transactions.ExecuteLocalTtCommandTransaction(
            cmd = if (req.cmd == LocalCommandProtocol.Cmd.SET_OWNED_TEMP_TARGET)
                app.aaps.database.transactions.ExecuteLocalTtCommandTransaction.Cmd.SET
            else app.aaps.database.transactions.ExecuteLocalTtCommandTransaction.Cmd.CANCEL,
            requestId = req.requestId, requestHash = requestHash, nowMs = System.currentTimeMillis(),
            validateOnly = validateOnly,
            targetMgdl = req.targetMgdl, durationMin = req.durationMin, reasonKey = req.reasonKey?.name,
            ttReason = req.reasonKey?.let { app.aaps.database.entities.TemporaryTarget.Reason.valueOf(it.name) },
            currentPolicyHash = LocalCommandPolicy.hash(), expectedState = req.expectedState,
            expectedOwnerRequestId = req.expectedOwnerRequestId, expectedTtDbId = req.expectedTtDbId,
            expectedTtEntityVersion = req.expectedTtEntityVersion, expectedOwnerPolicyHash = req.expectedOwnerPolicyHash,
            policyErrorCode = policyError, rateCapPerHour = RATE_CAP_PER_HOUR,
        )
        val result = repo.runTransactionForResult(tx).blockingGet()
        // Audit nach erfolgreichem Commit; ein Audit-Fehler wiederholt NIE die Mutation.
        var auditStatus = "WRITTEN"
        if (!result.replayed) runCatching {
            LocalCommandRuntime.persistenceLayer?.insertUserEntries(listOf(
                app.aaps.core.data.model.UE(
                    timestamp = System.currentTimeMillis(),
                    action = app.aaps.core.data.ue.Action.TT,
                    source = app.aaps.core.data.ue.Sources.Automation,
                    note = "IOB-Action lokal: ${req.cmd.name} ${req.targetMgdl ?: ""} → ${result.outcome}${result.errorCode?.let { " ($it)" } ?: ""}",
                    values = listOf(),
                )
            ))?.blockingGet()
        }.onFailure { auditStatus = "FAILED" }
        return buildMap {
            put("protocolVersion", LocalCommandProtocol.PROTOCOL_VERSION)
            put("requestId", req.requestId)
            put("outcome", result.outcome)
            put("replayed", result.replayed)
            put("fallbackEligible", false)
            put("auditStatus", auditStatus)
            result.errorCode?.let { put("errorCode", it) }
            result.appliedAt?.let { put("appliedAt", it) }
            result.ttDbId?.let { put("ttDbId", it) }
            result.ttEntityVersion?.let { put("ttEntityVersion", it) }
        }
    }

    private fun readOwnedTt(): Map<String, Any>? = runCatching {
        val r = LocalCommandRuntime.repository!!
            .runTransactionForResult(app.aaps.database.transactions.ReadLocalCommandStateTransaction()).blockingGet()
        r.ownership?.let { o ->
            mapOf(
                "ownerRequestId" to o.requestId, "ttDbId" to o.ttDbId, "ttEntityVersion" to o.ttEntityVersion,
                "ownerPolicyHash" to o.ownerPolicyHash, "targetMgdl" to o.lowTarget.toInt(),
                "startMs" to o.ttTimestamp, "endMs" to (o.ttTimestamp + o.durationMs), "reasonKey" to o.reasonKey,
            )
        }
    }.getOrNull()

    private fun readOutcome(queryRequestId: String): Map<String, Any>? = runCatching {
        val r = LocalCommandRuntime.repository!!
            .runTransactionForResult(app.aaps.database.transactions.ReadLocalCommandStateTransaction(queryRequestId)).blockingGet()
        r.queriedOutcome?.let { o ->
            buildMap<String, Any> {
                put("originalOutcome", o.outcome)
                o.errorCode?.let { put("originalErrorCode", it) }
                o.appliedAt?.let { put("originalAppliedAt", it) }
                o.ttDbId?.let { put("originalTtDbId", it) }
                o.ttEntityVersion?.let { put("originalTtEntityVersion", it) }
            }
        }
    }.getOrNull()

    private fun toBundle(map: Map<String, Any>): Bundle = Bundle().apply {
        for ((k, v) in map) when (v) {
            is String -> putString(k, v)
            is Boolean -> putBoolean(k, v)
            is Long -> putLong(k, v)
            is Int -> putInt(k, v)
        }
    }

    private fun callerTrusted(uid: Int): Boolean = runCatching {
        val pkgNames = packageManager.getPackagesForUid(uid) ?: return false
        val trusted = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TRUSTED, "")!!.split(',').mapNotNull { hex ->
                hex.trim().lowercase().takeIf { it.length == 64 && it.matches(Regex("^[0-9a-f]+$")) }
                    ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            }
        val packages = pkgNames.map { name ->
            val info = packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
            }
            LocalCommandAuth.PackageSigners(name, signers)
        }
        LocalCommandAuth.decide(packages, trusted)
    }.getOrDefault(false)

    private fun readSecret(): ByteArray? = runCatching {
        val f = File(noBackupFilesDir, SECRET_FILE)
        if (!f.exists()) return null
        val hex = f.readText().trim().lowercase()
        if (hex.length != 64 || !hex.matches(Regex("^[0-9a-f]+$"))) return null
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }.getOrNull()

    /** Gedrosselter Security-Zaehler (R1-F8): kein UserEntry, kein Log-Flooding. */
    private fun recordUnauthenticated() = runCatching {
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_UNAUTH_LAST, 0L) > 1_000L) {
            sp.edit().putInt(KEY_UNAUTH_COUNT, sp.getInt(KEY_UNAUTH_COUNT, 0) + 1).putLong(KEY_UNAUTH_LAST, now).apply()
        }
    }
}

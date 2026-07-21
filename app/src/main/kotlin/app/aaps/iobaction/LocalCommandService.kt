package app.aaps.iobaction

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import app.aaps.plugins.aps.iobaction.AutoIsfValueLeaseCoordinator
import app.aaps.plugins.aps.iobaction.LocalCommandAuth
import app.aaps.plugins.aps.iobaction.LocalCommandIobthPolicy
import app.aaps.plugins.aps.iobaction.LocalCommandPolicy
import app.aaps.plugins.aps.iobaction.LocalCommandProtocol
import app.aaps.plugins.aps.iobaction.LocalCommandServiceCore
import java.io.File
import java.security.MessageDigest

/**
 * LocalCommandChannel — Android-GLUE (R5-F3-Refactor): die gesamte Bundle→ACK-Logik lebt
 * unit-getestet in [LocalCommandServiceCore]; dieser Service sammelt nur die Umgebung
 * (Binder-Caller → Pakete → Signatur-Digests, Secret aus noBackupFilesDir, Gate-Schalter)
 * und konvertiert Map↔Bundle. PILOT-Build (R6): Mutationszweig VORHANDEN, aber alle drei
 * Gates default AUS und nur per AAPS-UI schaltbar; Signer-Allowlist default leer
 * (default-deny). Deklariert NUR im full-Flavor (R5-F4).
 */
class LocalCommandService : Service() {

    private companion object {
        const val PREFS = "local_command_channel"           // eigener Namespace — NICHT im Config-Export
        const val KEY_CHANNEL = "channel_enabled"
        const val KEY_TT = "tt_capability_enabled"
        const val KEY_IOBTH = "iobth_capability_enabled"    // A1: eigener Schalter je Wert-Hebel
        const val KEY_FORCED_VALIDATE = "forced_validate_only"
        /** v1.1/R10: AAPS-Prozessneustart beendet aktive Value-Leases BEVOR APS sie je nutzt. */
        private val processRestartDone = java.util.concurrent.atomic.AtomicBoolean(false)
        const val KEY_TRUSTED = "trusted_signer_sha256"     // kommaseparierte Hex-Digests, default leer
        const val KEY_UNAUTH_COUNT = "unauth_count"
        const val KEY_UNAUTH_LAST = "unauth_last_ms"
        const val SECRET_FILE = "local_command_secret"      // hex, unter noBackupFilesDir
        // Validate-only-Startwert (R2-B8/v1.4). R6-F2 ehrlich: ein wouldRateLimit ist NICHT
        // implementiert — die VO-Phase kalibriert per LCC-Log (VALIDATED-Zaehlung pro Stunde).
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
        val coordinator = LocalCommandRuntime.valueLeaseCoordinator
        // v1.1/R10-Test 3: aktive Value-Lease eines frueheren Prozesses terminalisieren
        // (RAM startet leer; die Room-Zeile darf nie wieder als OWNED erscheinen).
        if (repo != null && processRestartDone.compareAndSet(false, true)) runCatching {
            repo.runTransactionForResult(app.aaps.database.transactions.TerminalizeValueLeaseTransaction(
                "IOBTH", "PROCESS_RESTART", System.currentTimeMillis())).blockingGet()
        }
        val env = LocalCommandServiceCore.Env(
            callerTrusted = trusted,
            secret = readSecret(),
            gates = LocalCommandProtocol.GateConfig(
                channelEnabled = sp.getBoolean(KEY_CHANNEL, false),
                ttCapabilityEnabled = sp.getBoolean(KEY_TT, false),
                forcedValidateOnly = sp.getBoolean(KEY_FORCED_VALIDATE, false),
                iobthCapabilityEnabled = sp.getBoolean(KEY_IOBTH, false),
            ),
            nowMs = System.currentTimeMillis(),
            serviceInstanceId = serviceInstanceId,
            startedAt = startedAt,
            serverPolicyHash = LocalCommandPolicy.hash(),
            mutationExecutor = if (repo != null) { req, validateOnly ->
                if (req.cmd == LocalCommandProtocol.Cmd.SET_IOBTH || req.cmd == LocalCommandProtocol.Cmd.CLEAR_IOBTH)
                    executeValueMutation(req, validateOnly)
                else executeMutation(req, validateOnly)
            } else null,
            ownedTtProvider = if (repo != null) ({ readOwnedTt() }) else null,
            outcomeProvider = if (repo != null) ({ q -> readOutcome(q) }) else null,
            iobthStatusProvider = if (coordinator != null) ({
                val snap = coordinator.snapshot()
                buildMap {
                    put("serverIobthPolicyHash", LocalCommandIobthPolicy.hash())
                    put("iobthLeaseState", snap.overrideState.name)
                    snap.leaseId?.let { put("iobthLeaseId", it) }
                    snap.leaseVersion?.let { put("iobthLeaseVersion", it) }
                    snap.expiresAtWallMs?.let { put("iobthLeaseExpiresAt", it) }
                }
            }) else null,
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
        // R6-F9: UserEntry NUR fuer echte Mutationen (APPLIED, nicht replayed) — VALIDATED/
        // REJECTED erzeugen kein Treatments-Rauschen (LCC-Log + Outcome-Tabelle genuegen),
        // und Replays melden ehrlich, dass kein neuer Audit geschrieben wurde.
        var auditStatus = when {
            result.replayed -> "SKIPPED_REPLAY"
            result.outcome != "APPLIED" -> "SKIPPED_NO_MUTATION"
            else -> "WRITTEN"
        }
        if (!result.replayed && result.outcome == "APPLIED") runCatching {
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

    /**
     * Capability-Matrix A1 — Wert-Mutation (SET_IOBTH/CLEAR_IOBTH) ueber DIE eine
     * Value-Lease-Transaktion + Coordinator-Publish (R10-F2: Room-Commit = historischer
     * Linearization Point; APPLIED-ACK erst NACH dem RAM-Publish; R11-P1: commandResult
     * historisch, currentLeaseState live). In A1 live nur als Forced-VO erreichbar.
     */
    private fun executeValueMutation(req: LocalCommandProtocol.Request, validateOnly: Boolean): Map<String, Any> {
        val repo = LocalCommandRuntime.repository
        val coordinator = LocalCommandRuntime.valueLeaseCoordinator
        if (repo == null || coordinator == null) return mapOf(
            "protocolVersion" to LocalCommandProtocol.PROTOCOL_VERSION, "outcome" to "REJECTED",
            "replayed" to false, "errorCode" to LocalCommandProtocol.E_MUTATION_UNAVAILABLE)
        val now = System.currentTimeMillis()
        // Nachlaufende Terminalisierung von RAM-Widerrufen — IDENTITAETSGEBUNDEN (R12-F1)
        // und VERLUSTFREI via peek/ack (R13-F2): der Auftrag verlaesst die Queue erst nach
        // ERFOLGREICHER Room-Transaktion; ein transienter DB-Fehler laesst ihn fuer den
        // naechsten Versuch liegen (die RAM-Lease ist laengst sicher revoked).
        while (true) {
            val pt = coordinator.peekPendingTerminal() ?: break
            val ok = runCatching {
                repo.runTransactionForResult(app.aaps.database.transactions.TerminalizeValueLeaseTransaction(
                    pt.capability, pt.reason, now, pt.leaseId, pt.leaseVersion)).blockingGet()
            }.isSuccess
            if (ok) coordinator.ackPendingTerminal(pt) else break
        }
        // Statische Policy-Vorpruefung (nur SET; CLEAR prueft den ERSTELLUNGS-Hash in der txn).
        val policyError = if (req.cmd == LocalCommandProtocol.Cmd.SET_IOBTH) when {
            req.clientPolicyHash != LocalCommandIobthPolicy.hash() -> "REJECTED_POLICY_VERSION"
            !LocalCommandIobthPolicy.isAllowed(req.percent!!, req.ttlMin!!) -> "REJECTED_POLICY"
            else -> null
        } else null
        val requestHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(req.canonicalString.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun runTxn(capture: AutoIsfValueLeaseCoordinator.BaseCapture?): AutoIsfValueLeaseCoordinator.RoomSetResult {
            val tx = app.aaps.database.transactions.ExecuteValueLeaseCommandTransaction(
                cmd = if (req.cmd == LocalCommandProtocol.Cmd.SET_IOBTH)
                    app.aaps.database.transactions.ExecuteValueLeaseCommandTransaction.Cmd.SET
                else app.aaps.database.transactions.ExecuteValueLeaseCommandTransaction.Cmd.CLEAR,
                capability = "IOBTH", requestId = req.requestId, requestHash = requestHash,
                nowMs = now, validateOnly = validateOnly,
                setPayload = req.percent?.let { """{"percent":$it}""" }, ttlMin = req.ttlMin,
                expiresAtWallMs = capture?.expiresAtWallMs,
                basePayload = capture?.let { """{"percent":${it.basePercent}}""" },
                baseGeneration = capture?.baseGeneration, gateGeneration = capture?.gateGeneration,
                currentPolicyHash = LocalCommandIobthPolicy.hash(), expectedState = req.expectedState,
                expectedLeaseId = req.expectedLeaseId, expectedLeaseVersion = req.expectedLeaseVersion,
                expectedOwnerPolicyHash = req.expectedOwnerPolicyHash, policyErrorCode = policyError,
            )
            val r = try {
                repo.runTransactionForResult(tx).blockingGet()
            } catch (_: app.aaps.database.transactions.ExecuteValueLeaseCommandTransaction.ValueLeaseConflictException) {
                // R12-F6: Mutations-Txn blieb mutationsneutral — terminales Reject SEPARAT
                // und idempotent persistieren, dann deterministisch melden.
                runCatching {
                    repo.runTransactionForResult(app.aaps.database.transactions.PersistValueLeaseRejectTransaction(
                        if (req.cmd == LocalCommandProtocol.Cmd.SET_IOBTH) "SET" else "CLEAR", "IOBTH",
                        req.requestId, requestHash, "REJECTED_STATE_CONFLICT", now)).blockingGet()
                }
                return AutoIsfValueLeaseCoordinator.RoomSetResult("REJECTED", "REJECTED_STATE_CONFLICT", false, null, null, null)
            }
            // R12-F5: die TATSAECHLICH persistierten Herkunftswerte an den Publish geben.
            val basePercentUsed = r.basePayloadUsed?.let { bp -> Regex("-?\\d+").find(bp)?.value?.toIntOrNull() }
            return AutoIsfValueLeaseCoordinator.RoomSetResult(
                r.outcome, r.errorCode, r.replayed, r.resultJson, r.leaseId, r.leaseVersion,
                basePercentUsed = basePercentUsed, baseGenerationUsed = r.baseGenerationUsed, gateGenerationUsed = r.gateGenerationUsed,
            )
        }

        val (room, leaseState) = if (validateOnly) {
            // VO: keine Lease, kein Publish — Gegenwart ist der aktuelle Snapshot (R10-F5).
            val r = runTxn(null)
            r to coordinator.snapshot().overrideState
        } else if (req.cmd == LocalCommandProtocol.Cmd.SET_IOBTH) {
            val a = coordinator.executeArmedSet(req.percent!!, req.ttlMin!!) { capture -> runTxn(capture) }
            a.room to a.currentLeaseState
        } else {
            val a = coordinator.executeArmedClear { runTxn(null) }
            a.room to a.currentLeaseState
        }

        // Audit (G2): genau ein UserEntry je neuem APPLIED nach Commit+Publish; nie VO/Reject/Replay.
        var auditStatus = when {
            room.replayed -> "SKIPPED_REPLAY"
            room.outcome != "APPLIED" -> "SKIPPED_NO_MUTATION"
            else -> "WRITTEN"
        }
        if (!room.replayed && room.outcome == "APPLIED") runCatching {
            LocalCommandRuntime.persistenceLayer?.insertUserEntries(listOf(
                app.aaps.core.data.model.UE(
                    timestamp = now,
                    action = app.aaps.core.data.ue.Action.IOB_TH_SET,
                    source = app.aaps.core.data.ue.Sources.Automation,
                    note = "IOB-Action lokal: ${req.cmd.name} ${req.percent ?: ""}% ttl=${req.ttlMin ?: ""} → ${room.outcome} · Lease=${leaseState.name}",
                    values = listOf(),
                )
            ))?.blockingGet()
        }.onFailure { auditStatus = "FAILED" }

        return buildMap {
            put("protocolVersion", LocalCommandProtocol.PROTOCOL_VERSION)
            put("requestId", req.requestId)
            put("outcome", room.outcome)
            put("replayed", room.replayed)
            put("fallbackEligible", false)
            put("auditStatus", auditStatus)
            put("currentLeaseState", leaseState.name)                 // R11-P1: LIVE, darf beim Replay abweichen
            room.resultJson?.let { put("commandResult", it) }         // R11-P1: HISTORISCH, replay-gleich
            room.errorCode?.let { put("errorCode", it) }
            room.leaseId?.let { put("leaseId", it) }
            room.leaseVersion?.let { put("leaseVersion", it) }
        }
    }

    private fun readOwnedTt(): Map<String, Any>? = runCatching {
        val r = LocalCommandRuntime.repository!!
            .runTransactionForResult(app.aaps.database.transactions.ReadLocalCommandStateTransaction(nowMs = System.currentTimeMillis())).blockingGet()
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
            .runTransactionForResult(app.aaps.database.transactions.ReadLocalCommandStateTransaction(nowMs = System.currentTimeMillis(), queryRequestId = queryRequestId)).blockingGet()
        r.queriedOutcome?.let { o ->
            buildMap<String, Any> {
                put("originalOutcome", o.outcome)
                // R12-F4: das historische commandResult MUSS auch ueber die Lost-ACK-
                // Recovery bytegleich verfuegbar sein (Replay == Status).
                o.resultJson?.let { put("commandResult", it) }
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

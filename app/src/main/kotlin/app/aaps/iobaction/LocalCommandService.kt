package app.aaps.iobaction

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import app.aaps.core.data.model.UE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.objects.workflow.MetroService
import app.aaps.database.AppRepository
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.transactions.ExecuteLocalTtCommandTransaction
import app.aaps.database.transactions.ExecuteValueLeaseCommandTransaction
import app.aaps.database.transactions.PersistValueLeaseRejectTransaction
import app.aaps.database.transactions.ReadActiveValueLeaseTransaction
import app.aaps.database.transactions.ReadLocalCommandStateTransaction
import app.aaps.database.transactions.TerminalizeValueLeaseTransaction
import app.aaps.database.transactions.Transaction
import app.aaps.plugins.aps.iobaction.AutoIsfValueLeaseCoordinator
import app.aaps.plugins.aps.iobaction.AutoStateLeaseCoordinator
import app.aaps.plugins.aps.iobaction.LocalCommandAuth
import app.aaps.plugins.aps.iobaction.LocalCommandAutoStatePolicy
import app.aaps.plugins.aps.iobaction.LocalCommandChannelPrefs
import app.aaps.plugins.aps.iobaction.LocalCommandIobthPolicy
import app.aaps.plugins.aps.iobaction.LocalCommandPolicy
import app.aaps.plugins.aps.iobaction.LocalCommandProtocol
import app.aaps.plugins.aps.iobaction.LocalCommandServiceCore
import app.aaps.plugins.aps.iobaction.ValueOverlayPolicy
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LocalCommandChannel — Android-GLUE (R5-F3-Refactor): die gesamte Bundle→ACK-Logik lebt
 * unit-getestet in [LocalCommandServiceCore]; dieser Service sammelt nur die Umgebung
 * (Binder-Caller → Pakete → Signatur-Digests, Secret aus noBackupFilesDir, Gate-Schalter)
 * und konvertiert Map↔Bundle. Mutationszweig VORHANDEN, aber alle Gates default AUS und nur
 * per AAPS-UI schaltbar; Signer-Allowlist default leer (default-deny). Deklariert NUR im
 * full-Flavor (R5-F4).
 *
 * v4: die Abhaengigkeiten kommen per Metro-Feldinjektion (der Fork reichte sie ueber das statische
 * LocalCommandRuntime durch). Die Room-Transaktionen sind suspend; der Binder-Aufruf ist synchron,
 * also laufen sie wie im Fork (dort blockingGet) blockierend auf dem Binder-Thread.
 */
class LocalCommandService : MetroService() {

    @Inject lateinit var repository: AppRepository
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var valueLeaseCoordinator: AutoIsfValueLeaseCoordinator
    @Inject lateinit var autoStateCoordinator: AutoStateLeaseCoordinator

    private companion object {

        /** v1.1/R10: AAPS-Prozessneustart beendet aktive Value-Leases BEVOR APS sie je nutzt. */
        val processRestartDone = AtomicBoolean(false)

        // Validate-only-Startwert (R2-B8/v1.4). R6-F2 ehrlich: ein wouldRateLimit ist NICHT
        // implementiert — die VO-Phase kalibriert per LCC-Log (VALIDATED-Zaehlung pro Stunde).
        const val RATE_CAP_PER_HOUR = 30
    }

    private val serviceInstanceId = UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()

    private val binder = object : ILocalCommandChannel.Stub() {
        override fun execute(request: Bundle?): Bundle = runCatching {
            handle(request, Binder.getCallingUid())
        }.getOrElse {
            toBundle(
                mapOf(
                    "protocolVersion" to LocalCommandProtocol.PROTOCOL_VERSION,
                    "outcome" to "REJECTED", "replayed" to false,
                    "errorCode" to LocalCommandProtocol.E_MALFORMED,
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun <T : Any> runTx(transaction: Transaction<T>): T = runBlocking { repository.runTransactionForResultSuspend(transaction) }

    private fun insertUserEntry(ue: UE) {
        runBlocking { persistenceLayer.insertUserEntries(listOf(ue)) }
    }

    private fun handle(request: Bundle?, callingUid: Int): Bundle {
        val tStart = SystemClock.elapsedRealtime()
        val trusted = callerTrusted(callingUid)
        val tAuth = SystemClock.elapsedRealtime()
        if (!trusted) recordUnauthenticated()
        val sp = getSharedPreferences(LocalCommandChannelPrefs.PREFS, Context.MODE_PRIVATE)
        // v1.1/R10-Test 3: aktive Value-Lease eines frueheren Prozesses terminalisieren
        // (RAM startet leer; die Room-Zeile darf nie wieder als OWNED erscheinen).
        // Befund 5: die Capability war hart auf "IOBTH" verdrahtet. Eine AUTOSTATE-Zeile blieb
        // nach einem Prozesstod aktiv, waehrend der RAM NONE meldete — der Viewer schickte dann
        // dauerhaft expectedState=NONE und bekam ebenso dauerhaft REJECTED_STATE_CONFLICT, die
        // Capability war unbrauchbar UND der Automation-State blieb ueberlagert.
        // Runde 3 / F3: das Flag wurde frueher VOR der Arbeit gesetzt — jeder Fehler im
        // runCatching (Room, JSON, Koordinator noch nicht da) verschwand und wurde in diesem
        // Prozessleben NIE wiederholt, waehrend die Terminalisierung trotzdem lief: der
        // ueberlagerte State blieb dauerhaft, die Basis nur noch in einer toten Zeile.
        // Jetzt wird erst gearbeitet und das Flag nur bei Erfolg gesetzt.
        if (!processRestartDone.get()) {
            val now0 = System.currentTimeMillis()
            // Re-Review B7: VOR dem Terminalisieren die Basis zurueckschreiben. Ein Prozesstod
            // mitten in einer AUTOSTATE-Lease liess den ueberlagerten Wert sonst DAUERHAFT
            // stehen — bei einem Automation-State steuert das die Automationen um. Nur wenn noch
            // unser Wert steht; hat inzwischen ein Automat geschrieben, gehoert ihm der State.
            // Review (Befund 5): das Ergebnis der Rueckstellung wurde verworfen — weder
            // die Exception aus runCatching noch der Rueckgabewert. Quittiert wurde der
            // Prozessneustart trotzdem, und zwar allein anhand der Terminalisierung. Damit war
            // der Ablauf "Neustart mitten in einer Lease → Rueckstellung scheitert → Zeile
            // trotzdem terminal → nie wieder versucht" moeglich: der ueberlagerte Wert bliebe
            // dauerhaft stehen, und weil die Zeile nicht mehr aktiv ist, findet auch kein
            // spaeterer Reparaturlauf sie noch. Jetzt haengt das Quittieren an BEIDEM.
            val restoreOk = runCatching {
                val row = runTx(ReadActiveValueLeaseTransaction(AutoStateLeaseCoordinator.CAPABILITY))
                if (!row.found) return@runCatching true
                val setJson = JSONObject(row.setPayload)
                val baseJson = JSONObject(row.basePayload)
                val name = setJson.optString("name", "")
                val ourValue = setJson.optString("value", "")
                // org.json: optString with a null fallback returns null only for a missing key
                val baseValue = if (baseJson.optBoolean("known", false) && baseJson.has("value")) baseJson.getString("value") else null
                if (name.isEmpty() || ourValue.isEmpty()) return@runCatching true
                autoStateCoordinator.restoreAfterProcessRestart(name, ourValue, baseValue) !=
                    AutoStateLeaseCoordinator.RestartRestore.FAILED
            }.getOrDefault(false)
            // Und die Terminalisierung haengt am selben Ergebnis: sie ist es, die die Zeile
            // unauffindbar macht. Liefe sie trotz gescheiterter Rueckstellung, waere der
            // Wiederholversuch wertlos — beim naechsten Kommando ist row.found false und der
            // ueberlagerte Wert bliebe fuer immer stehen. Die Zeilen der anderen Capabilities
            // koennen gefahrlos mitwarten: nach einem Neustart ist der RAM ohnehin leer, eine
            // aktive Room-Zeile ohne publizierte Lease wirkt nicht.
            val allTerminalized = restoreOk && AutoIsfCapability.entries.all { cap ->
                runCatching { runTx(TerminalizeValueLeaseTransaction(cap.name, "PROCESS_RESTART", now0)) }.isSuccess
            }
            if (allTerminalized) processRestartDone.set(true)
        }
        val env = LocalCommandServiceCore.Env(
            callerTrusted = trusted,
            secret = readSecret(),
            gates = LocalCommandProtocol.GateConfig(
                channelEnabled = sp.getBoolean(LocalCommandChannelPrefs.KEY_CHANNEL, false),
                ttCapabilityEnabled = sp.getBoolean(LocalCommandChannelPrefs.KEY_TT, false),
                forcedValidateOnly = sp.getBoolean(LocalCommandChannelPrefs.KEY_FORCED_VALIDATE, false),
                iobthCapabilityEnabled = sp.getBoolean(LocalCommandChannelPrefs.KEY_IOBTH, false),
                autoStateCapabilityEnabled = sp.getBoolean(LocalCommandChannelPrefs.KEY_AUTOSTATE, false),
                smbRatioCapabilityEnabled = sp.getBoolean(LocalCommandChannelPrefs.KEY_SMBRATIO, false),
                weightsCapabilityEnabled = sp.getBoolean(LocalCommandChannelPrefs.KEY_WEIGHTS, false),
            ),
            nowMs = System.currentTimeMillis(),
            serviceInstanceId = serviceInstanceId,
            startedAt = startedAt,
            serverPolicyHash = LocalCommandPolicy.hash(),
            mutationExecutor = { req, validateOnly ->
                when (req.cmd) {
                    LocalCommandProtocol.Cmd.SET_IOBTH, LocalCommandProtocol.Cmd.CLEAR_IOBTH         ->
                        executeValueMutation(req, validateOnly)

                    LocalCommandProtocol.Cmd.SET_AUTOSTATE, LocalCommandProtocol.Cmd.CLEAR_AUTOSTATE ->
                        executeAutoStateMutation(req, validateOnly)

                    LocalCommandProtocol.Cmd.SET_SMBRATIO, LocalCommandProtocol.Cmd.CLEAR_SMBRATIO   ->
                        executeOverlayMutation(req, validateOnly, AutoIsfCapability.SMBRATIO)

                    LocalCommandProtocol.Cmd.SET_WEIGHTS, LocalCommandProtocol.Cmd.CLEAR_WEIGHTS     ->
                        executeOverlayMutation(req, validateOnly, AutoIsfCapability.WEIGHTS)

                    else                                                                             -> executeMutation(req, validateOnly)
                }
            },
            ownedTtProvider = { readOwnedTt() },
            outcomeProvider = { q -> readOutcome(q) },
            iobthStatusProvider = {
                val snap = valueLeaseCoordinator.snapshot()
                buildMap {
                    put("serverIobthPolicyHash", LocalCommandIobthPolicy.hash())
                    put("iobthLeaseState", snap.overrideState.name)
                    snap.leaseId?.let { put("iobthLeaseId", it) }
                    snap.leaseVersion?.let { put("iobthLeaseVersion", it) }
                    snap.expiresAtWallMs?.let { put("iobthLeaseExpiresAt", it) }
                }
            },
            // AUTOSTATE: Lease-Status + Policy-Hash, damit der Viewer die Capability ueberhaupt
            // als vorhanden erkennt (der Transport gated auf autoStateCapabilityEnabled).
            overlayStatusProvider = {
                buildMap<String, Any> {
                    put("smbRatioLeaseState", valueLeaseCoordinator.valueLeaseState(AutoIsfCapability.SMBRATIO).name)
                    put("weightsLeaseState", valueLeaseCoordinator.valueLeaseState(AutoIsfCapability.WEIGHTS).name)
                    valueLeaseCoordinator.snapshot().smbRatioEffective?.let { put("smbRatioEffective", it) }
                    valueLeaseCoordinator.snapshot().weightOverrides.takeIf { it.isNotEmpty() }
                        ?.let { put("weightOverrides", JSONObject(it as Map<*, *>).toString()) }
                }
            },
            autoStateStatusProvider = {
                autoStateCoordinator.enforce(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                autoStateCoordinator.statusMap()
            },
        )
        // Bundle-Rohwerte OHNE Typannahme extrahieren; Typpruefung macht der Core.
        val keys = runCatching { request?.keySet()?.toSet() }.getOrNull()
        val payload = runCatching { request?.getString("payloadJsonUtf8") }.getOrNull()
        val hmac = runCatching { request?.getString("hmacHex") }.getOrNull()
        val ack = LocalCommandServiceCore.execute(keys, payload, hmac, env)
        // R5-Punkt-7-Telemetrie: p50/p95 sammelt der Viewer aus diesen Zeilen (logcat LCC).
        Log.i("LCC", "authMs=${tAuth - tStart} totalMs=${SystemClock.elapsedRealtime() - tStart} outcome=${ack["outcome"]} err=${ack["errorCode"] ?: "-"}")
        return toBundle(ack)
    }

    private fun requestHashOf(req: LocalCommandProtocol.Request): String =
        MessageDigest.getInstance("SHA-256").digest(req.canonicalString.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** SET/CANCEL ueber DIE eine Room-Transaktion; Audit NACH dem Commit (R2-C4). */
    private fun executeMutation(req: LocalCommandProtocol.Request, validateOnly: Boolean): Map<String, Any> {
        // Statische Policy-Vorpruefung (Matrix + aktueller Hash) — terminal persistiert die Transaktion.
        val policyError = if (req.cmd == LocalCommandProtocol.Cmd.SET_OWNED_TEMP_TARGET) when {
            req.clientPolicyHash != LocalCommandPolicy.hash()                                 -> "REJECTED_POLICY_VERSION"
            !LocalCommandPolicy.isAllowed(req.reasonKey!!, req.targetMgdl!!, req.durationMin!!) -> "REJECTED_POLICY"
            else                                                                              -> null
        } else null
        val tx = ExecuteLocalTtCommandTransaction(
            cmd = if (req.cmd == LocalCommandProtocol.Cmd.SET_OWNED_TEMP_TARGET) ExecuteLocalTtCommandTransaction.Cmd.SET
            else ExecuteLocalTtCommandTransaction.Cmd.CANCEL,
            requestId = req.requestId, requestHash = requestHashOf(req), nowMs = System.currentTimeMillis(),
            validateOnly = validateOnly,
            targetMgdl = req.targetMgdl, durationMin = req.durationMin, reasonKey = req.reasonKey?.name,
            ttReason = req.reasonKey?.let { TemporaryTarget.Reason.valueOf(it.name) },
            currentPolicyHash = LocalCommandPolicy.hash(), expectedState = req.expectedState,
            expectedOwnerRequestId = req.expectedOwnerRequestId, expectedTtDbId = req.expectedTtDbId,
            expectedTtEntityVersion = req.expectedTtEntityVersion, expectedOwnerPolicyHash = req.expectedOwnerPolicyHash,
            policyErrorCode = policyError, rateCapPerHour = RATE_CAP_PER_HOUR,
        )
        val result = runTx(tx)
        // Audit nach erfolgreichem Commit; ein Audit-Fehler wiederholt NIE die Mutation.
        // R6-F9: UserEntry NUR fuer echte Mutationen (APPLIED, nicht replayed) — VALIDATED/
        // REJECTED erzeugen kein Treatments-Rauschen (LCC-Log + Outcome-Tabelle genuegen),
        // und Replays melden ehrlich, dass kein neuer Audit geschrieben wurde.
        var auditStatus = when {
            result.replayed             -> "SKIPPED_REPLAY"
            result.outcome != "APPLIED" -> "SKIPPED_NO_MUTATION"
            else                        -> "WRITTEN"
        }
        if (!result.replayed && result.outcome == "APPLIED") runCatching {
            insertUserEntry(
                UE(
                    timestamp = System.currentTimeMillis(),
                    action = Action.TT,
                    source = Sources.Automation,
                    note = "IOB-Action lokal: ${req.cmd.name} ${req.targetMgdl ?: ""} → ${result.outcome}${result.errorCode?.let { " ($it)" } ?: ""}",
                    values = listOf(),
                )
            )
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
     * Nachlaufende Terminalisierung von RAM-Widerrufen — IDENTITAETSGEBUNDEN (R12-F1) und
     * VERLUSTFREI via peek/ack (R13-F2): der Auftrag verlaesst die Queue erst nach ERFOLGREICHER
     * Room-Transaktion; ein transienter DB-Fehler laesst ihn fuer den naechsten Versuch liegen
     * (die RAM-Lease ist laengst sicher revoked).
     */
    private fun drainPendingTerminals(now: Long, peek: () -> PendingTerminal?, ack: (PendingTerminal) -> Unit) {
        while (true) {
            val pt = peek() ?: break
            val ok = runCatching { runTx(TerminalizeValueLeaseTransaction(pt.capability, pt.reason, now, pt.leaseId, pt.leaseVersion)) }.isSuccess
            if (ok) ack(pt) else break
        }
    }

    /** The identity of a pending terminalization, the same for both coordinators. */
    private data class PendingTerminal(val capability: String, val reason: String, val leaseId: String, val leaseVersion: Long, val source: Any)

    private fun valueLeasePending(): PendingTerminal? =
        valueLeaseCoordinator.peekPendingTerminal()?.let { PendingTerminal(it.capability, it.reason, it.leaseId, it.leaseVersion, it) }

    private fun autoStatePending(): PendingTerminal? =
        autoStateCoordinator.peekPendingTerminal()?.let { PendingTerminal(it.capability, it.reason, it.leaseId, it.leaseVersion, it) }

    /**
     * Capability-Matrix A1 — Wert-Mutation (SET_IOBTH/CLEAR_IOBTH) ueber DIE eine
     * Value-Lease-Transaktion + Coordinator-Publish (R10-F2: Room-Commit = historischer
     * Linearization Point; APPLIED-ACK erst NACH dem RAM-Publish; R11-P1: commandResult
     * historisch, currentLeaseState live).
     */
    private fun executeValueMutation(req: LocalCommandProtocol.Request, validateOnly: Boolean): Map<String, Any> {
        val coordinator = valueLeaseCoordinator
        val now = System.currentTimeMillis()
        drainPendingTerminals(now, ::valueLeasePending) { coordinator.ackPendingTerminal(it.source as AutoIsfValueLeaseCoordinator.PendingTerminal) }
        // Statische Policy-Vorpruefung (nur SET; CLEAR prueft den ERSTELLUNGS-Hash in der txn).
        val policyError = if (req.cmd == LocalCommandProtocol.Cmd.SET_IOBTH) when {
            req.clientPolicyHash != LocalCommandIobthPolicy.hash()           -> "REJECTED_POLICY_VERSION"
            !LocalCommandIobthPolicy.isAllowed(req.percent!!, req.ttlMin!!) -> "REJECTED_POLICY"
            else                                                             -> null
        } else null
        val requestHash = requestHashOf(req)

        fun runTxn(capture: AutoIsfValueLeaseCoordinator.BaseCapture?): AutoIsfValueLeaseCoordinator.RoomSetResult {
            val tx = ExecuteValueLeaseCommandTransaction(
                cmd = if (req.cmd == LocalCommandProtocol.Cmd.SET_IOBTH) ExecuteValueLeaseCommandTransaction.Cmd.SET
                else ExecuteValueLeaseCommandTransaction.Cmd.CLEAR,
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
                runTx(tx)
            } catch (_: ExecuteValueLeaseCommandTransaction.ValueLeaseConflictException) {
                // R12-F6: Mutations-Txn blieb mutationsneutral — terminales Reject SEPARAT
                // und idempotent persistieren, dann deterministisch melden.
                runCatching {
                    runTx(
                        PersistValueLeaseRejectTransaction(
                            if (req.cmd == LocalCommandProtocol.Cmd.SET_IOBTH) "SET" else "CLEAR", "IOBTH",
                            req.requestId, requestHash, "REJECTED_STATE_CONFLICT", now
                        )
                    )
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
            room.replayed             -> "SKIPPED_REPLAY"
            room.outcome != "APPLIED" -> "SKIPPED_NO_MUTATION"
            else                      -> "WRITTEN"
        }
        if (!room.replayed && room.outcome == "APPLIED") runCatching {
            insertUserEntry(
                UE(
                    timestamp = now,
                    action = Action.IOB_TH_SET,
                    source = Sources.Automation,
                    note = "IOB-Action lokal: ${req.cmd.name} ${req.percent ?: ""}% ttl=${req.ttlMin ?: ""} → ${room.outcome} · Lease=${leaseState.name}",
                    values = listOf(),
                )
            )
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

    /**
     * AUTOSTATE-Mutation. Gleiche Reihenfolge wie beim Wert-Lease: Nachlauf-Terminalisierung,
     * statische Policy-Vorpruefung, Room-Transaktion als Linearization Point, State-Schreiben
     * erst nach APPLIED.
     */
    private fun executeAutoStateMutation(req: LocalCommandProtocol.Request, validateOnly: Boolean): Map<String, Any> {
        val coordinator = autoStateCoordinator
        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        coordinator.enforce(now, nowElapsed)
        drainPendingTerminals(now, ::autoStatePending) { coordinator.ackPendingTerminal(it.source as AutoStateLeaseCoordinator.PendingTerminal) }
        val isSet = req.cmd == LocalCommandProtocol.Cmd.SET_AUTOSTATE
        // Statische Vorpruefung. Die Wertliste kommt vom State SELBST, nicht aus der Policy —
        // ein unbekannter State oder ein Wert ausserhalb SEINER Liste ist REJECTED_POLICY.
        val policyError = if (isSet) when {
            req.clientPolicyHash != LocalCommandAutoStatePolicy.hash()                                         -> "REJECTED_POLICY_VERSION"
            !LocalCommandAutoStatePolicy.isWellFormed(req.stateName!!, req.stateValue!!, req.ttlMin!!) -> "REJECTED_POLICY"
            !coordinator.isValueAllowed(req.stateName!!, req.stateValue!!)                             -> "REJECTED_POLICY"
            else                                                                                      -> null
        } else null
        val requestHash = requestHashOf(req)

        fun runTxn(capture: AutoStateLeaseCoordinator.BaseCapture?): AutoStateLeaseCoordinator.RoomSetResult {
            val tx = ExecuteValueLeaseCommandTransaction(
                cmd = if (isSet) ExecuteValueLeaseCommandTransaction.Cmd.SET else ExecuteValueLeaseCommandTransaction.Cmd.CLEAR,
                capability = AutoStateLeaseCoordinator.CAPABILITY, requestId = req.requestId,
                requestHash = requestHash, nowMs = now, validateOnly = validateOnly,
                setPayload = if (isSet) statePayload(req.stateName!!, req.stateValue!!) else null,
                ttlMin = req.ttlMin, expiresAtWallMs = capture?.expiresAtWallMs,
                basePayload = capture?.let { basePayload(it) },
                // Befund 1: Room verlangt beim ERSTEN SET (expectedState NONE) nicht-nullable
                // Generationen — null fuehrte dort zu einer NPE, Rollback und einem ACK ohne
                // requestId. AUTOSTATE hat keine SP-Basis-Generation, also 0; die Gate-Generation
                // ist echt und kommt aus derselben Beobachtung wie beim Wert-Overlay.
                baseGeneration = 0L, gateGeneration = capture?.gateGeneration ?: 0L,
                currentPolicyHash = LocalCommandAutoStatePolicy.hash(), expectedState = req.expectedState,
                expectedLeaseId = req.expectedLeaseId, expectedLeaseVersion = req.expectedLeaseVersion,
                expectedOwnerPolicyHash = req.expectedOwnerPolicyHash, policyErrorCode = policyError,
            )
            val r = try {
                runTx(tx)
            } catch (_: ExecuteValueLeaseCommandTransaction.ValueLeaseConflictException) {
                runCatching {
                    runTx(
                        PersistValueLeaseRejectTransaction(
                            if (isSet) "SET" else "CLEAR", AutoStateLeaseCoordinator.CAPABILITY,
                            req.requestId, requestHash, "REJECTED_STATE_CONFLICT", now
                        )
                    )
                }
                return AutoStateLeaseCoordinator.RoomSetResult("REJECTED", "REJECTED_STATE_CONFLICT", false, null, null, null)
            }
            return AutoStateLeaseCoordinator.RoomSetResult(r.outcome, r.errorCode, r.replayed, r.resultJson, r.leaseId, r.leaseVersion)
        }

        val (room, leaseState) = when {
            validateOnly -> runTxn(null) to coordinator.currentState()
            isSet        -> {
                val a = coordinator.executeArmedSet(req.stateName!!, req.stateValue!!, req.ttlMin!!, now, nowElapsed) { runTxn(it) }
                a.room to a.currentLeaseState
            }

            else         -> {
                val a = coordinator.executeArmedClear(now, nowElapsed) { runTxn(null) }
                a.room to a.currentLeaseState
            }
        }

        var auditStatus = when {
            room.replayed             -> "SKIPPED_REPLAY"
            room.outcome != "APPLIED" -> "SKIPPED_NO_MUTATION"
            else                      -> "WRITTEN"
        }
        if (!room.replayed && room.outcome == "APPLIED") runCatching {
            insertUserEntry(
                UE(
                    timestamp = now,
                    action = Action.CAREPORTAL,
                    source = Sources.Automation,
                    note = "IOB-Action lokal: ${req.cmd.name} ${req.stateName ?: ""}=${req.stateValue ?: ""} ttl=${req.ttlMin ?: ""} -> ${room.outcome} - Lease=$leaseState",
                    values = listOf(),
                )
            )
        }.onFailure { auditStatus = "FAILED" }

        return buildMap {
            put("protocolVersion", LocalCommandProtocol.PROTOCOL_VERSION)
            put("requestId", req.requestId)
            put("outcome", room.outcome)
            put("replayed", room.replayed)
            put("fallbackEligible", false)
            put("auditStatus", auditStatus)
            put("currentLeaseState", leaseState)
            room.resultJson?.let { put("commandResult", it) }
            room.errorCode?.let { put("errorCode", it) }
            room.leaseId?.let { put("leaseId", it) }
            room.leaseVersion?.let { put("leaseVersion", it) }
        }
    }

    /** Kanonische Payloads (ASCII, keine Whitespaces) - die Policy garantiert die Zeichenmenge. */
    private fun statePayload(name: String, value: String) = """{"name":"$name","value":"$value"}"""
    private fun basePayload(c: AutoStateLeaseCoordinator.BaseCapture) =
        if (c.baseKnown && c.baseValue != null) """{"name":"${c.stateName}","known":true,"value":"${c.baseValue}"}"""
        else """{"name":"${c.stateName}","known":false}"""

    /**
     * SMBRATIO/WEIGHTS ueber den generischen Wert-Lease-Pfad. Gleiche Reihenfolge wie ueberall:
     * Nachlauf-Terminalisierung, statische Policy-Vorpruefung, Room als Linearization Point,
     * Publish erst nach APPLIED.
     */
    private fun executeOverlayMutation(req: LocalCommandProtocol.Request, validateOnly: Boolean, cap: AutoIsfCapability): Map<String, Any> {
        val coordinator = valueLeaseCoordinator
        val now = System.currentTimeMillis()
        val isRatio = cap == AutoIsfCapability.SMBRATIO
        val isSet = req.cmd == LocalCommandProtocol.Cmd.SET_SMBRATIO || req.cmd == LocalCommandProtocol.Cmd.SET_WEIGHTS
        drainPendingTerminals(now, ::valueLeasePending) { coordinator.ackPendingTerminal(it.source as AutoIsfValueLeaseCoordinator.PendingTerminal) }
        val serverHash = if (isRatio) ValueOverlayPolicy.ratioHash() else ValueOverlayPolicy.weightsHash()
        val scaled: Map<String, Long> =
            if (!isSet) emptyMap()
            else if (isRatio) mapOf(ValueOverlayPolicy.F_RATIO to req.ratioScaled!!)
            else req.weightsScaled!!
        val policyError = if (isSet) when {
            req.clientPolicyHash != serverHash                                     -> "REJECTED_POLICY_VERSION"
            isRatio && !ValueOverlayPolicy.isRatioAllowed(req.ratioScaled!!)        -> "REJECTED_POLICY"
            !isRatio && !ValueOverlayPolicy.areWeightsAllowed(req.weightsScaled!!) -> "REJECTED_POLICY"
            else                                                                   -> null
        } else null
        val requestHash = requestHashOf(req)

        fun runTxn(capture: AutoIsfValueLeaseCoordinator.ValueBaseCapture?): AutoIsfValueLeaseCoordinator.RoomSetResult {
            val tx = ExecuteValueLeaseCommandTransaction(
                cmd = if (isSet) ExecuteValueLeaseCommandTransaction.Cmd.SET else ExecuteValueLeaseCommandTransaction.Cmd.CLEAR,
                capability = cap.name, requestId = req.requestId, requestHash = requestHash,
                nowMs = now, validateOnly = validateOnly,
                setPayload = if (isSet) scaledJson(scaled) else null,
                ttlMin = req.ttlMin, expiresAtWallMs = capture?.expiresAtWallMs,
                basePayload = capture?.let { rawJson(it.baseRaw) },
                baseGeneration = capture?.baseGeneration, gateGeneration = capture?.gateGeneration,
                currentPolicyHash = serverHash, expectedState = req.expectedState,
                expectedLeaseId = req.expectedLeaseId, expectedLeaseVersion = req.expectedLeaseVersion,
                expectedOwnerPolicyHash = req.expectedOwnerPolicyHash, policyErrorCode = policyError,
            )
            val r = try {
                runTx(tx)
            } catch (_: ExecuteValueLeaseCommandTransaction.ValueLeaseConflictException) {
                runCatching {
                    runTx(
                        PersistValueLeaseRejectTransaction(
                            if (isSet) "SET" else "CLEAR", cap.name, req.requestId, requestHash,
                            "REJECTED_STATE_CONFLICT", now
                        )
                    )
                }
                return AutoIsfValueLeaseCoordinator.RoomSetResult("REJECTED", "REJECTED_STATE_CONFLICT", false, null, null, null)
            }
            return AutoIsfValueLeaseCoordinator.RoomSetResult(
                r.outcome, r.errorCode, r.replayed, r.resultJson, r.leaseId, r.leaseVersion,
                baseGenerationUsed = r.baseGenerationUsed, gateGenerationUsed = r.gateGenerationUsed,
            )
        }

        val (room, leaseState) = when {
            validateOnly -> runTxn(null) to coordinator.valueLeaseState(cap)
            isSet        -> {
                val a = coordinator.executeArmedValueSet(cap, scaled, req.ttlMin!!) { runTxn(it) }
                a.room to a.currentLeaseState
            }

            else         -> {
                val a = coordinator.executeArmedValueClear(cap) { runTxn(null) }
                a.room to a.currentLeaseState
            }
        }

        var auditStatus = when {
            room.replayed             -> "SKIPPED_REPLAY"
            room.outcome != "APPLIED" -> "SKIPPED_NO_MUTATION"
            else                      -> "WRITTEN"
        }
        if (!room.replayed && room.outcome == "APPLIED") runCatching {
            insertUserEntry(
                UE(
                    timestamp = now,
                    action = Action.CAREPORTAL,
                    source = Sources.Automation,
                    note = "IOB-Action lokal: ${req.cmd.name} ${scaledJson(scaled)} ttl=${req.ttlMin ?: ""} -> ${room.outcome}",
                    values = listOf(),
                )
            )
        }.onFailure { auditStatus = "FAILED" }

        return buildMap {
            put("protocolVersion", LocalCommandProtocol.PROTOCOL_VERSION)
            put("requestId", req.requestId)
            put("outcome", room.outcome)
            put("replayed", room.replayed)
            put("fallbackEligible", false)
            put("auditStatus", auditStatus)
            put("currentLeaseState", leaseState.name)
            room.resultJson?.let { put("commandResult", it) }
            room.errorCode?.let { put("errorCode", it) }
            room.leaseId?.let { put("leaseId", it) }
            room.leaseVersion?.let { put("leaseVersion", it) }
        }
    }

    /** Kanonische Payloads: sortierte Schluessel, damit Room-Zeilen vergleichbar bleiben. */
    private fun scaledJson(m: Map<String, Long>) =
        m.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }

    private fun rawJson(m: Map<String, Double>) =
        m.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }

    private fun readOwnedTt(): Map<String, Any>? = runCatching {
        val r = runTx(ReadLocalCommandStateTransaction(nowMs = System.currentTimeMillis()))
        r.ownership?.let { o ->
            mapOf(
                "ownerRequestId" to o.requestId, "ttDbId" to o.ttDbId, "ttEntityVersion" to o.ttEntityVersion,
                "ownerPolicyHash" to o.ownerPolicyHash, "targetMgdl" to o.lowTarget.toInt(),
                "startMs" to o.ttTimestamp, "endMs" to (o.ttTimestamp + o.durationMs), "reasonKey" to o.reasonKey,
            )
        }
    }.getOrNull()

    private fun readOutcome(queryRequestId: String): Map<String, Any>? = runCatching {
        val r = runTx(ReadLocalCommandStateTransaction(nowMs = System.currentTimeMillis(), queryRequestId = queryRequestId))
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
            is String  -> putString(k, v)
            is Boolean -> putBoolean(k, v)
            is Long    -> putLong(k, v)
            is Int     -> putInt(k, v)
        }
    }

    private fun callerTrusted(uid: Int): Boolean = runCatching {
        val pkgNames = packageManager.getPackagesForUid(uid) ?: return false
        val trusted = getSharedPreferences(LocalCommandChannelPrefs.PREFS, Context.MODE_PRIVATE)
            .getString(LocalCommandChannelPrefs.KEY_TRUSTED, "")!!.split(',').mapNotNull { hex ->
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
        val f = File(noBackupFilesDir, LocalCommandChannelPrefs.SECRET_FILE)
        if (!f.exists()) return null
        val hex = f.readText().trim().lowercase()
        if (hex.length != 64 || !hex.matches(Regex("^[0-9a-f]+$"))) return null
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }.getOrNull()

    /** Gedrosselter Security-Zaehler (R1-F8): kein UserEntry, kein Log-Flooding. */
    private fun recordUnauthenticated() = runCatching {
        val sp = getSharedPreferences(LocalCommandChannelPrefs.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(LocalCommandChannelPrefs.KEY_UNAUTH_LAST, 0L) > 1_000L) {
            sp.edit().putInt(LocalCommandChannelPrefs.KEY_UNAUTH_COUNT, sp.getInt(LocalCommandChannelPrefs.KEY_UNAUTH_COUNT, 0) + 1)
                .putLong(LocalCommandChannelPrefs.KEY_UNAUTH_LAST, now).apply()
        }
    }
}

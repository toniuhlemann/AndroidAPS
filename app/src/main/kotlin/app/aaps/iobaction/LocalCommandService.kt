package app.aaps.iobaction

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import app.aaps.plugins.aps.iobaction.LocalCommandAuth
import app.aaps.plugins.aps.iobaction.LocalCommandProtocol
import java.io.File
import java.security.MessageDigest

/**
 * LocalCommandChannel — OFF/Auth-Build (Spec v1.2/v1.3, Codex-R3-GO mit harten Grenzen):
 * kein kompilierter Mutationszweig (LocalCommandProtocol.MUTATION_BUILD_PRESENT=false),
 * keine AAPS-DB-Beruehrung, Kanal + TT-Capability default AUS (und in diesem Build ohne
 * jede UI zum Einschalten → praktisch dauerhaft OFF). Implementiert: Binder-Callerpruefung,
 * Secret/Auth, Parser/Kanonisierung via LocalCommandProtocol, ACK-Grundgeruest, Gate-Logik,
 * Security-Zaehler. Exported, weil die Autorisierung KOMPLETT selbst geprueft wird
 * (UID→Pakete→Signatur-Digests, default-deny bei leerer Allowlist).
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
        val ALLOWED_REQUEST_KEYS = setOf("payloadJsonUtf8", "hmacHex")
    }

    private val serviceInstanceId = java.util.UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()

    private val binder = object : ILocalCommandChannel.Stub() {
        override fun execute(request: Bundle?): Bundle = runCatching {
            handle(request, Binder.getCallingUid())
        }.getOrElse { neutralReject(LocalCommandProtocol.E_MALFORMED) }   // nie Exceptions ueber Binder leaken
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun handle(request: Bundle?, callingUid: Int): Bundle {
        // 1. Caller-Identitaet (R1-F1/R2-C1): UID → alle Pakete → jeder Signer vertraut.
        if (!callerTrusted(callingUid)) {
            recordUnauthenticated()
            return neutralReject(LocalCommandProtocol.E_AUTH)
        }
        // 2. Bundle-Haertung (R2-A3): exakt zwei String-Keys, nie Parcelables deserialisieren.
        if (request == null) return neutralReject(LocalCommandProtocol.E_MALFORMED)
        val keys = runCatching { request.keySet() }.getOrNull() ?: return neutralReject(LocalCommandProtocol.E_MALFORMED)
        if (keys != ALLOWED_REQUEST_KEYS) return neutralReject(LocalCommandProtocol.E_MALFORMED)
        val payload = request.getString("payloadJsonUtf8") ?: return neutralReject(LocalCommandProtocol.E_MALFORMED)
        val hmac = request.getString("hmacHex") ?: return neutralReject(LocalCommandProtocol.E_MALFORMED)
        // 3. Protokoll + HMAC (pure).
        val parsed = LocalCommandProtocol.parseAndVerify(payload, hmac, readSecret(), System.currentTimeMillis())
        val req = parsed.request ?: return neutralReject(parsed.errorCode ?: LocalCommandProtocol.E_MALFORMED)
        // 4. Gates (v1.2-A4-Prioritaet; Schalter existieren, sind default AUS).
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cfg = LocalCommandProtocol.GateConfig(
            channelEnabled = sp.getBoolean(KEY_CHANNEL, false),
            ttCapabilityEnabled = sp.getBoolean(KEY_TT, false),
            forcedValidateOnly = sp.getBoolean(KEY_FORCED_VALIDATE, false),
        )
        LocalCommandProtocol.gateDecision(cfg, req)?.let { return ack(req.requestId) { putString("errorCode", it); putString("outcome", "REJECTED") } }
        // 5. Read-only-Befehle (einzige in diesem Build erreichbare Pfade, nur bei Kanal AN):
        return when (req.cmd) {
            LocalCommandProtocol.Cmd.GET_SERVICE_STATUS -> ack(req.requestId) {
                putString("outcome", "APPLIED")   // read-only Antwort, keine Mutation
                putBoolean("mutationBuildPresent", LocalCommandProtocol.MUTATION_BUILD_PRESENT)
                putBoolean("channelEnabled", cfg.channelEnabled)
                putBoolean("ttCapabilityEnabled", cfg.ttCapabilityEnabled)
                putBoolean("forcedValidateOnly", cfg.forcedValidateOnly)
                putString("serverPolicyHash", "")           // Policy-Matrix kommt mit Validate-only-Stufe
                putBoolean("secretConfigured", readSecret() != null)
                putBoolean("databaseSchemaReady", false)    // Room-Migration kommt mit Pilot-Stufe
                putString("serviceInstanceId", serviceInstanceId)
                putLong("startedAt", startedAt)
            }
            LocalCommandProtocol.Cmd.GET_COMMAND_STATUS -> ack(req.requestId) {
                putString("outcome", "APPLIED")
                putString("queryRequestId", req.queryRequestId)
                putString("queryStatus", "NOT_FOUND")       // kein Outcome-Store in diesem Build
            }
            else -> neutralReject(LocalCommandProtocol.E_MUTATION_UNAVAILABLE)   // strukturell unerreichbar
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

    private fun neutralReject(code: String): Bundle = Bundle().apply {
        putString("protocolVersion", LocalCommandProtocol.PROTOCOL_VERSION)
        putString("outcome", "REJECTED")
        putBoolean("replayed", false)
        putString("errorCode", code)
    }

    private fun ack(requestId: String, fill: Bundle.() -> Unit): Bundle = Bundle().apply {
        putString("protocolVersion", LocalCommandProtocol.PROTOCOL_VERSION)
        putString("requestId", requestId)
        putBoolean("replayed", false)
        putBoolean("fallbackEligible", false)
        fill()
    }
}

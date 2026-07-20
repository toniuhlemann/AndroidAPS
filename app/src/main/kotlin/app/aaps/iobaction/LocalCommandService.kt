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
        val trusted = callerTrusted(callingUid)
        if (!trusted) recordUnauthenticated()
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        )
        // Bundle-Rohwerte OHNE Typannahme extrahieren; Typpruefung macht der Core.
        val keys = runCatching { request?.keySet()?.toSet() }.getOrNull()
        val payload = runCatching { request?.getString("payloadJsonUtf8") }.getOrNull()
        val hmac = runCatching { request?.getString("hmacHex") }.getOrNull()
        return toBundle(LocalCommandServiceCore.execute(keys, payload, hmac, env))
    }

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

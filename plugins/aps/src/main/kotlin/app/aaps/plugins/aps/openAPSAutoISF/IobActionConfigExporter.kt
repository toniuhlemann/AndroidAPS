package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import org.json.JSONArray
import org.json.JSONObject

/**
 * IOB Action viewer: CONFIG snapshot — the relevant AAPS/autoISF preference state, the active
 * profile blocks (basal/ISF/IC/target) and ALL automations (triggers + actions) as ONE JSON.
 * Replaces the manually maintained settings JSON: written on the 60s heartbeat but only when
 * the content actually CHANGED (hash), plus a 6h heartbeat rewrite.
 *
 * Write-only, fully runCatching-guarded, never affects dosing (same guarantees as the other
 * IOB Action exports). Secrets are excluded by a hard DENY list evaluated BEFORE the allow
 * match (passwords/tokens/URLs/phone numbers never leave the app).
 */
object IobActionConfigExporter {

    @Volatile private var lastHash: Int = 0
    @Volatile private var lastWriteMs: Long = 0L

    private val DENY = listOf(
        "password", "passwd", "secret", "token", "api", "url", "wifi", "sms", "phone",
        "nsclient", "tidepool", "garmin", "openhumans", "email", "user", "identification",
        "serial", "mac", "pair", "keystore", "certificate", "crash", "uuid", "fcm"
    )
    private val ALLOW = listOf(
        "autoisf", "auto_isf", "openaps", "smb", "uam", "iob", "dia", "insulin", "peak",
        "sens", "isf", "carb", "target", "basal", "bolus", "loop", "apsmode", "smooth",
        "bgacc", "brake", "pp_", "dura", "range", "ratio", "max", "min_5m", "safety",
        "autosens", "activity", "absorption", "cgm", "libre", "glucose", "delta"
    )

    fun snapshot(sp: SP, profileFunction: ProfileFunction, dateUtil: DateUtil) {
        runCatching {
            val prefs = JSONObject()
            sp.getAll().toSortedMap().forEach { (k, v) ->
                val lk = k.lowercase()
                if (DENY.any { lk.contains(it) }) return@forEach
                if (ALLOW.none { lk.contains(it) }) return@forEach
                val value = v ?: return@forEach
                when (value) {
                    is Boolean -> prefs.put(k, value)
                    is Int     -> prefs.put(k, value)
                    is Long    -> prefs.put(k, value)
                    is Float   -> if (value.isFinite()) prefs.put(k, value.toDouble())
                    else       -> prefs.put(k, value.toString().take(500))
                }
            }
            val automations = runCatching { JSONArray(sp.getString("AUTOMATION_EVENTS", "[]")) }
                .getOrDefault(JSONArray())
            val profile = runCatching { profileFunction.getProfile()?.toPureNsJson(dateUtil) }.getOrNull()

            val content = JSONObject()
                .put("v", 1)
                .put("prefs", prefs)
                .put("automations", automations)
                .put("profileName", runCatching { profileFunction.getProfileName() }.getOrDefault(""))
                .apply { profile?.let { put("profile", it) } }

            val hash = content.toString().hashCode()
            val now = System.currentTimeMillis()
            if (hash == lastHash && now - lastWriteMs < 6 * 3_600_000L) return
            lastHash = hash
            lastWriteMs = now
            content.put("ts", now)
            IobActionExporter.writeConfig(content)
        }
    }
}

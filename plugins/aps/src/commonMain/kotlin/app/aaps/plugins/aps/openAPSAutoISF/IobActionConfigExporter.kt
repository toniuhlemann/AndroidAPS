package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.concurrent.Volatile

/**
 * Fork (IOB-Action viewer): the CONFIG snapshot, iobaction_config.json - the relevant AAPS/autoISF
 * preferences, the active profile and ALL automations as one JSON. Checked on the minute heartbeat,
 * written only when the content CHANGED, plus a rewrite every 6 h.
 *
 * Secrets are kept out by a hard DENY list that is checked BEFORE the ALLOW match (passwords,
 * tokens, URLs, phone numbers never leave the app). Write-only, guarded, never affects dosing.
 * Same keys as the 3.4 fork; the profile carries `dia` again (v4 moved it into the insulin config).
 */
@SingleIn(AppScope::class)
@Inject
class IobActionConfigExporter(
    private val aapsLogger: AAPSLogger,
    private val keyValueStore: KeyValueStore,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val sink: IobActionExportSink
) {

    @Volatile private var lastHash: Int = 0
    @Volatile private var lastWriteMs: Long = 0L

    suspend fun snapshot() {
        try {
            val prefs = buildJsonObject {
                // Sorted by name like the 3.4 fork (toSortedMap is JVM only).
                for ((k, v) in keyValueStore.getAll().entries.sortedBy { it.key }) {
                    val lk = k.lowercase()
                    if (DENY.any { lk.contains(it) }) continue
                    if (ALLOW.none { lk.contains(it) }) continue
                    when (val value = v ?: continue) {
                        is Boolean -> put(k, JsonPrimitive(value))
                        is Int     -> put(k, JsonPrimitive(value))
                        is Long    -> put(k, JsonPrimitive(value))
                        is Float   -> if (value.isFinite()) put(k, JsonPrimitive(value.toDouble()))
                        else       -> put(k, JsonPrimitive(value.toString().take(500)))
                    }
                }
            }
            val automations = try {
                Json.parseToJsonElement(preferences.get(StringNonKey.AutomationEvents).ifBlank { "[]" }) as? JsonArray
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: JsonArray(emptyList())
            val profile = try {
                profileFunction.getProfile()?.let { p ->
                    // v4 keeps the DIA in the insulin config; the viewer reads it from the profile.
                    JsonObject(p.toPureNsJson(dateUtil) + ("dia" to JsonPrimitive(p.iCfg.dia)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val profileName = try {
                profileFunction.getProfileName()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }

            val content = buildJsonObject {
                putOpt("v", 1)
                put("prefs", prefs)
                put("automations", automations)
                putOpt("profileName", profileName)
                profile?.let { put("profile", it) }
            }
            val hash = content.toString().hashCode()
            val now = dateUtil.now()
            if (hash == lastHash && now - lastWriteMs < REWRITE_MS) return
            lastHash = hash
            lastWriteMs = now
            sink.writeConfig(JsonObject(content + ("ts" to JsonPrimitive(now))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "IobAction config export failed", e)
        }
    }

    private companion object {

        const val REWRITE_MS = 6 * 3_600_000L

        val DENY = listOf(
            "password", "passwd", "secret", "token", "api", "url", "wifi", "sms", "phone",
            "nsclient", "tidepool", "garmin", "openhumans", "email", "user", "identification",
            "serial", "mac", "pair", "keystore", "certificate", "crash", "uuid", "fcm"
        )
        val ALLOW = listOf(
            "autoisf", "auto_isf", "openaps", "smb", "uam", "iob", "dia", "insulin", "peak",
            "sens", "isf", "carb", "target", "basal", "bolus", "loop", "apsmode", "smooth",
            "bgacc", "brake", "pp_", "dura", "range", "ratio", "max", "min_5m", "safety",
            "autosens", "activity", "absorption", "cgm", "libre", "glucose", "delta",
            // fsl_exp1_factor (the Libre smoothing alpha) and the fslCal offsets match nothing above.
            "fsl",
            // automation_state_service / automation_state_values: the automation state machine.
            "automation_state"
        )
    }
}

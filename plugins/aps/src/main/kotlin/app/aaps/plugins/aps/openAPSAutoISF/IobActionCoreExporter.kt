package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import org.json.JSONObject

/**
 * Live CORE snapshot for the IOB Action native viewer — decoupled from the loop.
 *
 * The full export ([IobActionExporter.write]) is written only in the determineBasal hook, so it
 * is only as fresh as the last loop cycle: a 15-min idle loop freezes it and the viewer drops to
 * stale log-tail parsing (no TBR). This writes the loop-INDEPENDENT live state (IOB/COB/running
 * TBR/TT/device) every 60s straight from the same calculators the AAPS-native widget reads, so the
 * viewer's base state stays current regardless of whether the loop dosed. Output:
 * iobaction_core.json (own file → no read-modify-write race with the loop-gated full export).
 *
 * Plain object (NOT a @Inject type): the AppComponent can't provide a fresh plugins:aps
 * @Inject-constructor binding, so the live singletons are passed in by [app.aaps.MainApp], which
 * injects the (already-bound) core interfaces itself — same pattern the AAPS widget uses.
 *
 * SAFETY: write-only, fully runCatching-guarded, never touches dosing.
 */
object IobActionCoreExporter {

    /** Build + atomically write the live core JSON. Called from MainApp's 60s widget heartbeat. */
    fun snapshot(
        iobCobCalculator: IobCobCalculator,
        processedTbrEbData: ProcessedTbrEbData,
        persistenceLayer: PersistenceLayer,
        profileFunction: ProfileFunction,
        activePlugin: ActivePlugin,
        preferences: Preferences,
        dateUtil: DateUtil
    ) {
        runCatching {
            val now = dateUtil.now()
            val profile = profileFunction.getProfile() ?: return
            // Same calc the AAPS overview header / loop export use, so IOB matches exactly.
            val headerIob = runCatching { iobCobCalculator.calculateFromTreatmentsAndTemps(now, profile) }.getOrNull()
            val cob = runCatching { iobCobCalculator.getCobInfo("IobAction core").displayCob }.getOrNull()
            // CURRENTLY-RUNNING temp basal (what the pump is actually delivering now) — the value
            // the user was missing on the log fallback; more truthful than the loop's requested rate.
            val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
            val tbrRate = tb?.convertedToAbsolute(now, profile)
            val tbrRemaining = tb?.plannedRemainingMinutes
            val activeTt = persistenceLayer.getTemporaryTargetActiveAt(now)

            val json = JSONObject().apply {
                put("ts", now)
                put("core", true)
                // Same keys as the full export so the viewer reuses the exact same parser.
                put("aps", JSONObject().apply {
                    headerIob?.let { put("IOB", it.iob.takeIf { v -> v.isFinite() }) }
                    cob?.takeIf { it.isFinite() }?.let { put("COB", it) }
                    tbrRate?.takeIf { it.isFinite() }?.let { put("rate", it) }
                    tbrRemaining?.let { put("duration", it) }
                })
                headerIob?.let { iob ->
                    put("iob", JSONObject().apply {
                        put("net", iob.iob.takeIf { it.isFinite() })
                        put("basal", iob.basaliob.takeIf { it.isFinite() })
                        put("bolus", (iob.iob - iob.basaliob).takeIf { it.isFinite() })
                        put("activity", iob.activity.takeIf { it.isFinite() })
                    })
                }
                put("profile", JSONObject().apply {
                    // Profile target/ISF/IC are loop-INDEPENDENT (same getters the AAPS widget uses
                    // live) — so a day/night profile switch (98<->97) shows immediately even on an
                    // idle loop, instead of waiting for the next determineBasal to write state.json.
                    // (An oref-OVERRIDDEN target is genuinely loop-only → stays in the state file.)
                    put("target_bg", profile.getTargetMgdl().takeIf { it.isFinite() })
                    put("sens", profile.getProfileIsfMgdl().takeIf { it.isFinite() })
                    put("carb_ratio", profile.getIc().takeIf { it.isFinite() })
                    put("current_basal", profile.getBasal(now).takeIf { it.isFinite() })
                    put("name", profileFunction.getProfileName())
                    put("name_remaining", profileFunction.getProfileNameWithRemainingTime())
                    // autoISF SETTINGS (not outputs) — pure preferences that the per-TT automations
                    // flip on every target switch, so they belong live in the core. The COMPUTED
                    // factors (acce/pp/dura/final, iobThEffectiveU) stay loop-side (state.json).
                    put("iob_threshold_percent", preferences.get(IntKey.ApsAutoIsfIobThPercent))
                    put("bgAccel_ISF_weight", preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight))
                    put("pp_ISF_weight", preferences.get(DoubleKey.ApsAutoIsfPpWeight))
                    put("bgBrake_ISF_weight", preferences.get(DoubleKey.ApsAutoIsfBgBrakeWeight))
                    put("dura_ISF_weight", preferences.get(DoubleKey.ApsAutoIsfDuraWeight))
                })
                put("device", JSONObject().apply {
                    put("reservoir", activePlugin.activePump.reservoirLevel)
                    put("battery", activePlugin.activePump.batteryLevel)
                })
                activeTt?.let { tt ->
                    put("tt", JSONObject().apply {
                        put("target", tt.lowTarget)
                        put("remainingMin", (tt.timestamp + tt.duration - now) / 60000L)
                    })
                }
            }
            IobActionExporter.writeCore(json)
        }
    }
}

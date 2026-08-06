package app.aaps.fuse.plugin

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * FUSE als AAPS-APS-Plugin.
 *
 * Es ist ein VOLLWERTIGES APS, kein Beobachter: was hier installiert wird,
 * verhaelt sich genauso wie auf dem Produktivgeraet. Die einzige Grenze ist
 * [FusePumpGate] — und die ist eine Startverweigerung gegen echte Pumpen, kein
 * Schalter.
 *
 * Was dieses Plugin NICHT tut:
 *
 *  - kein `supportsDynamicIsf()`: FUSE liefert dem Rest der App kein variables
 *    ISF. Ein `true` hier wuerde Bolusrechner und Overview auf `getIsfMgdl()`
 *    umleiten, und das ist eine Zusage, die Alpha 1 nicht einloest.
 *  - keine `PluginConstraints`: FUSE verschaerft keine fremden Grenzen. Es liest
 *    maxIOB ueber den [ConstraintsChecker], statt es selbst zu setzen.
 *  - kein Persistieren als `AUTO_ISF`: [APSResult.Algorithm.FUSE] ist ein
 *    eigener Wert. Ein FUSE-Ergebnis unter fremdem Etikett waere im
 *    Nachhinein nicht mehr von autoISF zu trennen — genau die Sorte
 *    Kontamination, die jede spaetere Auswertung wertlos macht.
 */
@Singleton
class FusePlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    private val config: Config,
    private val rxBus: RxBus,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val constraintsChecker: ConstraintsChecker,
    private val commandQueue: CommandQueue,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val apsResultProvider: Provider<APSResult>,
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.fuse)
        .shortName(R.string.fuse_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .description(R.string.description_fuse),
    ownPreferences = listOf(FuseDoubleKey::class.java, FuseIntKey::class.java, FuseBooleanKey::class.java),
    aapsLogger, rh, preferences
), APS {

    override var lastAPSRun: Long = 0
    override var lastAPSResult: APSResult? = null
    override val algorithm = APSResult.Algorithm.FUSE

    /**
     * Der Observer-Zustand lebt IM Runner und ueberdauert die Zyklen — Phasen,
     * Peaks und die Aufwaermzeit waeren sonst in jedem Aufruf wieder auf Null.
     * Deshalb genau eine Instanz je Prozess, angelegt beim ersten Zyklus.
     */
    private var runner: FuseCycleRunner? = null

    /** Was der letzte Zyklus gesehen hat — Grundlage des spaeteren
     *  Zustandsexports und der Fragment-Anzeige. */
    var lastOutcome: FuseCycleRunner.Outcome? = null
        private set

    override fun specialEnableCondition(): Boolean =
        try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            // Kann waehrend der Initialisierung fehlschlagen, bevor ein
            // Pumpenplugin steht.
            true
        }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null

        // `LoopPlugin.invoke` hat try/finally OHNE catch: eine Ausnahme von hier
        // wuerde den gesamten Loop-Durchlauf abbrechen — inklusive der Schritte
        // nach dem APS-Aufruf. Deshalb faengt FUSE selbst und liefert ein
        // Ergebnis, das nichts anfordert, aber den Grund traegt.
        val outcome = try {
            cycleRunner().run(tempBasalFallback)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "FUSE cycle failed", e)
            null
        }
        lastOutcome = outcome

        val rt = if (outcome == null) {
            FuseRtBuilder.build(
                nowMs = dateUtil.now(),
                bgMgdl = null, targetMgdl = null, iobU = null, profileIsfMgdlPerU = null,
                decision = app.aaps.fuse.core.controller.FuseController.noInput("EXCEPTION"),
                tbr = null,
                gate = FusePumpGate.evaluate(runCatching { activePlugin.activePump }.getOrNull()),
            )
        } else {
            FuseRtBuilder.build(
                // deliverAt aus der WANDUHR, nicht aus dem Zyklusanfang: der Loop
                // verwirft einen Mikrobolus, dessen deliverAt mehr als etwa eine
                // Minute zurueckliegt. Ein langsamer Zyklus wuerde seinen eigenen
                // SMB verfallen lassen.
                nowMs = dateUtil.now(),
                bgMgdl = outcome.bgMgdl,
                targetMgdl = outcome.targetMgdl,
                iobU = outcome.iobU,
                decision = outcome.decision,
                tbr = outcome.tbr,
                gate = outcome.gate,
                profileIsfMgdlPerU = outcome.isfMgdlPerU,
                targetSource = outcome.targetSource,
                signal = outcome.signal,
                band = outcome.band,
                methodId = outcome.band?.let { app.aaps.fuse.core.signal.PairSlopeBand.methodId(preferences.get(FuseIntKey.DriveLowerQuantilePct)) },
                minMeanMgdl = outcome.prediction?.minMeanBg,
            )
        }
        outcome?.let { if (it.abortReason != null) rt.reason.append(" | abort=").append(it.abortReason) }

        lastAPSResult = apsResultProvider.get().with(rt)
        lastAPSRun = dateUtil.now()
        aapsLogger.debug(LTag.APS, "FUSE result: ${rt.reason}")
        rxBus.send(EventAPSCalculationFinished())
    }

    private fun cycleRunner(): FuseCycleRunner =
        runner ?: FuseCycleRunner(
            iobCobCalculator = iobCobCalculator,
            profileFunction = profileFunction,
            activePlugin = activePlugin,
            constraintsChecker = constraintsChecker,
            commandQueue = commandQueue,
            preferences = preferences,
            persistenceLayer = persistenceLayer,
            dateUtil = dateUtil,
            // Prozessgebundene Kennung: sie trennt die Ereignisse eines Laufs von
            // denen nach einem Neustart. `dateUtil.now()` und nicht ein Zufall,
            // damit sie in einem Export sortierbar bleibt.
            sessionId = "fuse-${dateUtil.now()}",
        ).also { runner = it }

    /**
     * Der app-weite Glukosestatus. Sobald FUSE aktives APS ist, haengen Overview,
     * Wear und Automations hieran — `null` waere kein "FUSE nutzt das nicht",
     * sondern ein stiller Ausfall in halb AAPS. Siehe [FuseGlucoseStatus].
     */
    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        FuseGlucoseStatus.of(
            readings = iobCobCalculator.ads.getBgReadingsDataTableCopy(),
            nowMs = dateUtil.now(),
            allowOldData = allowOldData,
        )

    override fun configuration(): JSONObject =
        JSONObject()
            .put(FuseDoubleKey.SmbRatio, preferences)
            .put(FuseDoubleKey.MaxSmbU, preferences)
            .put(FuseDoubleKey.GuardFloorMgdl, preferences)
            .put(FuseIntKey.IobThPercent, preferences)
            .put(FuseIntKey.ReleaseHorizonMin, preferences)
            .put(FuseIntKey.LiabilityHorizonMin, preferences)
            .put(FuseIntKey.DriveTauMin, preferences)
            .put(FuseIntKey.DriveLowerQuantilePct, preferences)
            .put(FuseBooleanKey.TailGuardEnabled, preferences)
            .put(FuseDoubleKey.TailFloorMgdl, preferences)
            .put(FuseDoubleKey.TailRecoveryU, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(FuseDoubleKey.SmbRatio, preferences)
            .store(FuseDoubleKey.MaxSmbU, preferences)
            .store(FuseDoubleKey.GuardFloorMgdl, preferences)
            .store(FuseIntKey.IobThPercent, preferences)
            .store(FuseIntKey.ReleaseHorizonMin, preferences)
            .store(FuseIntKey.LiabilityHorizonMin, preferences)
            .store(FuseIntKey.DriveTauMin, preferences)
            .store(FuseIntKey.DriveLowerQuantilePct, preferences)
            .store(FuseBooleanKey.TailGuardEnabled, preferences)
            .store(FuseDoubleKey.TailFloorMgdl, preferences)
            .store(FuseDoubleKey.TailRecoveryU, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        // Der Bildschirm wird auch fuer Unterbildschirme aufgerufen; FUSE hat
        // keine, also nur der Aufbau der obersten Ebene.
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "fuse_settings"
            title = rh.gs(R.string.fuse_settings)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = FuseDoubleKey.SmbRatio,
                    dialogMessage = R.string.fuse_smb_ratio_summary, title = R.string.fuse_smb_ratio_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = FuseDoubleKey.MaxSmbU,
                    dialogMessage = R.string.fuse_max_smb_u_summary, title = R.string.fuse_max_smb_u_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = FuseDoubleKey.GuardFloorMgdl,
                    dialogMessage = R.string.fuse_guard_floor_summary, title = R.string.fuse_guard_floor_title
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context, intKey = FuseIntKey.IobThPercent,
                    dialogMessage = R.string.fuse_iob_th_percent_summary, title = R.string.fuse_iob_th_percent_title
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context, intKey = FuseIntKey.ReleaseHorizonMin,
                    dialogMessage = R.string.fuse_release_horizon_summary, title = R.string.fuse_release_horizon_title
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context, intKey = FuseIntKey.LiabilityHorizonMin,
                    dialogMessage = R.string.fuse_liability_horizon_summary, title = R.string.fuse_liability_horizon_title
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context, intKey = FuseIntKey.DriveTauMin,
                    dialogMessage = R.string.fuse_drive_tau_summary, title = R.string.fuse_drive_tau_title
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context, intKey = FuseIntKey.DriveLowerQuantilePct,
                    dialogMessage = R.string.fuse_drive_quantile_summary, title = R.string.fuse_drive_quantile_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = FuseBooleanKey.TailGuardEnabled,
                    summary = R.string.fuse_tail_guard_summary, title = R.string.fuse_tail_guard_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = FuseDoubleKey.TailFloorMgdl,
                    dialogMessage = R.string.fuse_tail_floor_summary, title = R.string.fuse_tail_floor_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = FuseDoubleKey.TailRecoveryU,
                    dialogMessage = R.string.fuse_tail_recovery_summary, title = R.string.fuse_tail_recovery_title
                )
            )
        }
    }
}

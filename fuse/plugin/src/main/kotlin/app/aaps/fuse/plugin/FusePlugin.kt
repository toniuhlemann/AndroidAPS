package app.aaps.fuse.plugin

import android.content.Context
import android.os.Environment
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
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
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.fuse.plugin.export.FuseStateExporter
import app.aaps.fuse.plugin.export.FuseStateJson
import org.json.JSONObject
import java.io.File
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
 *  - `PluginConstraints` NUR fuer maxIOB, und das ist eine Korrektur: die erste
 *    Fassung hat bewusst darauf verzichtet ("FUSE verschaerft keine fremden
 *    Grenzen"). Der erste Geraetelauf hat gezeigt, wohin das fuehrt —
 *    `getMaxIOBAllowed()` lieferte `Double.MAX_VALUE`, weil der EINZIGE
 *    Anwender von `ApsSmbMaxIob` das autoISF-Plugin ist und das abgeschaltet
 *    wird, sobald FUSE aktiv ist. Damit konnten `MAX_IOB_REACHED` und
 *    `IOB_TH_REACHED` NIE feuern; bindend blieben nur `smbRatio` und `maxSmbU`.
 *    Es gab also gar keinen IOB-Deckel. Basal- und Bolusgrenzen bleiben
 *    unangetastet — die zieht `SafetyPlugin` ungeklammert.
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
    private val hardLimits: HardLimits,
    private val apsResultProvider: Provider<APSResult>,
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(FuseFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.fuse)
        .shortName(R.string.fuse_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .description(R.string.description_fuse),
    ownPreferences = listOf(FuseDoubleKey::class.java, FuseIntKey::class.java, FuseBooleanKey::class.java, FuseLongKey::class.java),
    aapsLogger, rh, preferences
), APS, PluginConstraints, app.aaps.core.interfaces.overview.FuseOverviewSource {

    override var lastAPSRun: Long = 0
    override var lastAPSResult: APSResult? = null
    override val algorithm = APSResult.Algorithm.FUSE

    /**
     * Der Observer-Zustand lebt IM Runner und ueberdauert die Zyklen — Phasen,
     * Peaks und die Aufwaermzeit waeren sonst in jedem Aufruf wieder auf Null.
     * Deshalb genau eine Instanz je Prozess, angelegt beim ersten Zyklus.
     */
    private var runner: FuseCycleRunner? = null

    /** Prozessgebundene Kennung. Sie trennt die Zyklen eines Laufs von denen
     *  nach einem Neustart und ist der erste Teil der Cycle-Id. */
    private val sessionId: String by lazy { "fuse-" + dateUtil.now() }

    private val exporter = FuseStateExporter()
    private var cycleCounter = 0L
    private var prevWrite: FuseStateJson.PrevWrite? = null

    /** Was der letzte Zyklus gesehen hat — Grundlage des spaeteren
     *  Zustandsexports und der Fragment-Anzeige. */
    @Volatile var lastOutcome: FuseCycleRunner.Outcome? = null
        private set

    /** Ring fuer die Overview-Untergraphen (~25 h bei 1-min-Takt). Bewusst im
     *  Prozess statt in der DB - nach Neustart beginnt der Graph leer, der
     *  Trail bleibt die vollstaendige Historie. */
    private val graphRing = ArrayDeque<app.aaps.core.interfaces.overview.FuseOverviewSource.Point>()

    /** Marker-Druecke des Prozesses + der letzte persistierte (uebersteht
     *  Neustarts via Preference). */
    private val markerPressRing = ArrayDeque<Long>()

    @Volatile private var graphRingWarmed = false

    override fun fuseMealMarkerTimes(fromTime: Long, endTime: Long): List<Long> {
        val fromPref = mealMarkerArmedTs()
        val all = synchronized(markerPressRing) { markerPressRing.toList() } + listOf(fromPref).filter { it > 0 }
        return all.distinct().filter { it in fromTime..endTime }.sorted()
    }

    /**
     * RING-WARMSTART (08.08.): die F.DRV/F.GRD-Linien leben im Prozess und
     * waren nach jedem Flash leer. Beim ersten Zyklus wird der Ring einmalig
     * aus dem Trail (letzte ~24 h) nachgefuellt - der Trail bleibt die
     * einzige Wahrheit, keine DB. Fehler sind still-tolerant: ein kaputter
     * Warmstart darf keinen Zyklus kosten.
     */
    private fun warmGraphRingOnce() {
        if (graphRingWarmed) return
        graphRingWarmed = true
        runCatching {
            val f = java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Documents/aapsLogs/fuse_state_history.jsonl"
            )
            if (!f.exists()) return
            val cutoff = System.currentTimeMillis() - 25L * 3600_000L
            val pts = ArrayList<app.aaps.core.interfaces.overview.FuseOverviewSource.Point>()
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val j = runCatching { org.json.JSONObject(line) }.getOrNull() ?: continue
                    val ts = j.optLong("sourceTs", 0L)
                    if (ts < cutoff) continue
                    val sig = j.optJSONObject("signal") ?: continue
                    val dec = j.optJSONObject("decision")
                    val pol = j.optJSONObject("policy")?.optJSONObject("values")
                    val r = sig.optDouble("rSigned", Double.NaN)
                    val ukf = sig.optDouble("ukfRatePerMin", Double.NaN)
                    val act = sig.optDouble("activityAtAnchor", Double.NaN)
                    val isf = sig.optDouble("isfAtAnchor", Double.NaN)
                    val fast = if (ukf.isFinite() && act.isFinite() && isf.isFinite()) ukf + act * isf else Double.NaN
                    val ml = dec?.optDouble("minLowerMgdl", Double.NaN) ?: Double.NaN
                    val gf = pol?.optDouble("guardFloorMgdl", 70.0) ?: 70.0
                    pts.add(
                        app.aaps.core.interfaces.overview.FuseOverviewSource.Point(
                            timestamp = ts,
                            driveMgdlPerMin = r.takeIf { it.isFinite() },
                            fastDriveMgdlPerMin = fast.takeIf { it.isFinite() },
                            guardMarginMgdl = (ml - gf).takeIf { it.isFinite() }?.coerceIn(-50.0, 150.0),
                        )
                    )
                }
            }
            synchronized(graphRing) {
                if (graphRing.isEmpty()) {
                    graphRing.addAll(pts.takeLast(1_500))
                }
            }
        }
    }

    override fun fuseRampLevels(): Pair<Double, Double> =
        Pair(preferences.get(FuseDoubleKey.RiseRampLowR), preferences.get(FuseDoubleKey.RiseRampHighR))

    override fun fuseGraphPoints(fromTime: Long, endTime: Long): List<app.aaps.core.interfaces.overview.FuseOverviewSource.Point> =
        synchronized(graphRing) { graphRing.filter { it.timestamp in fromTime..endTime } }

    /**
     * Derselbe Schluessel wie bei den OpenAPS-Plugins: `ApsSmbMaxIob` ist KEINE
     * autoISF-Groesse, sondern AAPS' eigene maxIOB-Einstellung. Eine
     * FUSE-eigene waere hier falsch — eine Sicherheitsgrenze in zwei Zahlen zu
     * spalten heisst, dass eine davon irgendwann vergessen wird.
     *
     * `setIfSmaller`: FUSE VERSCHAERFT nur. Die uebrigen Teilnehmer der Kette
     * (LGS, BG-Qualitaet, abgelaufene App) schaerfen weiter nach.
     */
    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val pref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(pref, rh.gs(app.aaps.core.ui.R.string.limiting_iob, pref, rh.gs(R.string.fuse_limit_pref)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(app.aaps.core.ui.R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.fuse_limit_hard)), this)
        }
        return maxIob
    }

    /**
     * Mahlzeiten-Marker: TT-UNABHAENGIG, bewusst. Ein TT wuerde Marker und
     * Zielverschiebung vermischen; dieser Knopf senkt ausschliesslich die
     * Evidenzschwelle des OnsetChannel und erzeugt selbst keine Dosis.
     * Zweiter Druck nimmt ihn zurueck; nach MARKER_WINDOW_MIN verfaellt er.
     */
    fun toggleMealMarker(now: Long): Boolean = toggleMealMarker(now, 1)

    /** Armen mit Stufe (0=S,1=M,2=L); erneuter Druck derselben ODER anderer
     *  Stufe bei aktivem Marker nimmt ihn zurueck bzw. wechselt die Stufe
     *  NICHT stillschweigend - Zuruecknehmen ist immer explizit. */
    fun toggleMealMarker(now: Long, tier: Int): Boolean {
        val armed = mealMarkerActive(now)
        if (armed) {
            preferences.put(FuseLongKey.MealMarkerArmedTs, 0L)
            return false
        }
        preferences.put(FuseLongKey.MealMarkerTier, tier.toLong().coerceIn(0L, 2L))
        preferences.put(FuseLongKey.MealMarkerArmedTs, now)
        synchronized(markerPressRing) {
            markerPressRing.addLast(now)
            while (markerPressRing.size > 20) markerPressRing.removeFirst()
        }
        return true
    }

    fun mealMarkerTier(): Int = preferences.get(FuseLongKey.MealMarkerTier).toInt().coerceIn(0, 2)

    fun mealMarkerArmedTs(): Long = preferences.get(FuseLongKey.MealMarkerArmedTs)

    fun mealMarkerActive(now: Long): Boolean {
        val ts = preferences.get(FuseLongKey.MealMarkerArmedTs)
        return ts > 0 && now - ts in 0..(app.aaps.fuse.core.controller.OnsetChannel.MARKER_WINDOW_MIN * 60_000L)
    }

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
        warmGraphRingOnce()
        lastOutcome = outcome
        outcome?.let { o ->
            val ts = o.sourceTs ?: o.computeTs
            val sig = o.signal
            synchronized(graphRing) {
                if (graphRing.lastOrNull()?.timestamp != ts) {
                    graphRing.addLast(
                        app.aaps.core.interfaces.overview.FuseOverviewSource.Point(
                            timestamp = ts,
                            driveMgdlPerMin = sig?.rSigned?.takeIf { it.isFinite() },
                            fastDriveMgdlPerMin = sig?.let { (it.ukfRatePerMin + it.activityAtAnchor * it.isfAtAnchor).takeIf { v -> v.isFinite() } },
                            guardMarginMgdl = o.decision.minLowerMgdl
                                ?.let { ml -> ml - (o.policy?.guardFloorMgdl ?: 70.0) }
                                ?.takeIf { it.isFinite() }?.coerceIn(-50.0, 150.0),
                        )
                    )
                    while (graphRing.size > 1_500) graphRing.removeFirst()
                }
            }
        }

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

        exportState(outcome, rt)
    }

    /**
     * Der Zustandsexport — R89 macht ihn zur Installationsvoraussetzung.
     *
     * Er steht am ENDE von invoke() und laeuft auf JEDEM Pfad, auch dem
     * Ausnahmepfad. Gerade dort will man ihn: ein Zyklus, der nichts
     * entschieden hat, ist die interessanteste Zeile im Trail.
     *
     * Vollstaendig in runCatching: der Export ist Beobachtung und darf den
     * Regler unter keinen Umstaenden anhalten. Selbst ein Fehler im
     * Datensatzbau bleibt hier.
     */
    private fun exportState(outcome: FuseCycleRunner.Outcome?, rt: RT) {
        runCatching {
            val start = System.nanoTime()
            val o = outcome ?: return
            val cycleId = sessionId + "#" + (++cycleCounter)
            val json = FuseStateJson.record(
                cycleId = cycleId,
                outcome = o,
                rt = rt,
                policy = o.policy,
                build = FuseStateJson.Build(config.VERSION_NAME, config.HEAD, config.COMMITTED),
                buildStartNs = start,
                prev = prevWrite,
                nowNs = System::nanoTime,
            )
            // Die Android-Aufloesung des Verzeichnisses passiert AUSSCHLIESSLICH
            // hier — der Schreiber selbst kennt kein Environment und bleibt
            // damit ohne Geraet pruefbar.
            val dir = File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs")
            when (val r = exporter.append(dir, json.toString())) {
                is FuseStateExporter.Result.Written -> {
                    prevWrite = FuseStateJson.PrevWrite(r.writeMs, r.bytes)
                    if (r.rotated) aapsLogger.debug(LTag.APS, "FUSE state trail rotated")
                }

                is FuseStateExporter.Result.Failed  -> {
                    // NICHT stumm: ein Export, der nicht schreibt, ist der Fall,
                    // in dem man spaeter vergeblich nach Daten sucht.
                    prevWrite = null
                    aapsLogger.error(LTag.APS, "FUSE state export failed: " + r.reason)
                }
            }
        }.onFailure { aapsLogger.error(LTag.APS, "FUSE state export threw", it) }
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
            .put(FuseDoubleKey.SmbRatioRise, preferences)
            .put(FuseDoubleKey.RiseRampLowR, preferences)
            .put(FuseDoubleKey.RiseRampHighR, preferences)
            .put(FuseDoubleKey.MaxSmbU, preferences)
            .put(FuseDoubleKey.GuardFloorMgdl, preferences)
            .put(FuseIntKey.IobThPercent, preferences)
            .put(FuseIntKey.ReleaseHorizonMin, preferences)
            .put(FuseIntKey.LiabilityHorizonMin, preferences)
            .put(FuseIntKey.DriveTauMin, preferences)
            .put(FuseIntKey.DriveLowerQuantilePct, preferences)
            .put(FuseBooleanKey.TailGuardEnabled, preferences)
            .put(FuseBooleanKey.FastRestraintEnabled, preferences)
            .put(FuseDoubleKey.TailFloorMgdl, preferences)
            .put(FuseDoubleKey.TailRecoveryU, preferences)
            .put(FuseDoubleKey.BolusShareLambda, preferences)
            .put(FuseDoubleKey.OnsetEnvelopeU, preferences)
            .put(FuseBooleanKey.OnsetChannelEnabled, preferences)
            .put(FuseBooleanKey.PrimeReleaseEnabled, preferences)
            .put(FuseDoubleKey.PrimeEnvelopeU, preferences)
            .put(FuseDoubleKey.PrimeEnvelopeSmallU, preferences)
            .put(FuseDoubleKey.PrimeEnvelopeLargeU, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(FuseDoubleKey.SmbRatio, preferences)
            .store(FuseDoubleKey.SmbRatioRise, preferences)
            .store(FuseDoubleKey.RiseRampLowR, preferences)
            .store(FuseDoubleKey.RiseRampHighR, preferences)
            .store(FuseDoubleKey.MaxSmbU, preferences)
            .store(FuseDoubleKey.GuardFloorMgdl, preferences)
            .store(FuseIntKey.IobThPercent, preferences)
            .store(FuseIntKey.ReleaseHorizonMin, preferences)
            .store(FuseIntKey.LiabilityHorizonMin, preferences)
            .store(FuseIntKey.DriveTauMin, preferences)
            .store(FuseIntKey.DriveLowerQuantilePct, preferences)
            .store(FuseBooleanKey.TailGuardEnabled, preferences)
            .store(FuseBooleanKey.FastRestraintEnabled, preferences)
            .store(FuseDoubleKey.TailFloorMgdl, preferences)
            .store(FuseDoubleKey.TailRecoveryU, preferences)
            .store(FuseDoubleKey.BolusShareLambda, preferences)
            .store(FuseDoubleKey.OnsetEnvelopeU, preferences)
            .store(FuseBooleanKey.OnsetChannelEnabled, preferences)
            .store(FuseBooleanKey.PrimeReleaseEnabled, preferences)
            .store(FuseDoubleKey.PrimeEnvelopeU, preferences)
            .store(FuseDoubleKey.PrimeEnvelopeSmallU, preferences)
            .store(FuseDoubleKey.PrimeEnvelopeLargeU, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        // Overview-Muster: der Aufruf kommt auch fuer Unterbildschirme -
        // dann bauen wir dieselbe Struktur und das Framework zieht den
        // passenden Sub-Screen heraus.
        val subKeys = setOf("fuse_safety", "fuse_control", "fuse_meal", "fuse_tail", "fuse_diag")
        if (requiredKey != null && requiredKey !in subKeys) return

        // GRUPPIERT statt flach (Toni 08.08., GPT-Review bestaetigt): die
        // wichtigste Aenderung ist das GETEILTE ApsSmbMaxIob - FUSE nutzt es
        // seit dem MAX_VALUE-Fund als Deckel, zeigte es aber nirgends. KEIN
        // eigener FUSE-Key: eine Sicherheitsgrenze, eine Zahl.
        // ECHTE Unterbildschirme statt aufgeklappter Kategorien (Toni 08.08.:
        // 27 Eintraege am Stueck = Scroll-Wueste; die Klappzeile der Lib ist
        // eine Einbahnstrasse). Oben fuenf Zeilen, "zuklappen" = Zurueck.
        val root = PreferenceCategory(context)
        parent.addPreference(root)
        root.title = rh.gs(R.string.fuse_settings)
        fun cat(key: String, titleText: String, block: PreferenceScreen.() -> Unit) {
            root.addPreference(preferenceManager.createPreferenceScreen(context).apply {
                this.key = key
                this.title = titleText
                block()
            })
        }
        fun PreferenceScreen.info(t: String, sum: String) {
            addPreference(Preference(context).apply { title = t; summary = sum; isSelectable = false; isPersistent = false })
        }

        cat("fuse_safety", "Allgemeine Sicherheitsgrenzen") {
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob,
                    dialogMessage = R.string.fuse_max_total_iob_summary, title = R.string.fuse_max_total_iob_title
                )
            )
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.MaxSmbU, dialogMessage = R.string.fuse_max_smb_u_summary, title = R.string.fuse_max_smb_u_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.IobThPercent, dialogMessage = R.string.fuse_iob_th_percent_summary, title = R.string.fuse_iob_th_percent_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.GuardFloorMgdl, dialogMessage = R.string.fuse_guard_floor_summary, title = R.string.fuse_guard_floor_title))
        }

        cat("fuse_control", "Regelung") {
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.SmbRatio, dialogMessage = R.string.fuse_smb_ratio_summary, title = R.string.fuse_smb_ratio_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.SmbRatioRise, dialogMessage = R.string.fuse_smb_ratio_rise_summary, title = R.string.fuse_smb_ratio_rise_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.RiseRampLowR, dialogMessage = R.string.fuse_ramp_summary, title = R.string.fuse_ramp_low_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.RiseRampHighR, dialogMessage = R.string.fuse_ramp_summary, title = R.string.fuse_ramp_high_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.BolusShareLambda, dialogMessage = R.string.fuse_bolus_share_lambda_summary, title = R.string.fuse_bolus_share_lambda_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.FastRestraintEnabled, summary = R.string.fuse_restraint_summary, title = R.string.fuse_restraint_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.ReleaseHorizonMin, dialogMessage = R.string.fuse_release_horizon_summary, title = R.string.fuse_release_horizon_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.DriveTauMin, dialogMessage = R.string.fuse_drive_tau_summary, title = R.string.fuse_drive_tau_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.DriveLowerQuantilePct, dialogMessage = R.string.fuse_drive_quantile_summary, title = R.string.fuse_drive_quantile_title))
        }

        cat("fuse_meal", "Mahlzeit / Onset") {
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.OnsetChannelEnabled, summary = R.string.fuse_onset_channel_summary, title = R.string.fuse_onset_channel_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.OnsetEnvelopeU, dialogMessage = R.string.fuse_onset_envelope_summary, title = R.string.fuse_onset_envelope_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.PrimeReleaseEnabled, summary = R.string.fuse_prime_release_summary, title = R.string.fuse_prime_release_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.PrimeEnvelopeSmallU, dialogMessage = R.string.fuse_prime_small_summary, title = R.string.fuse_prime_small_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.PrimeEnvelopeU, dialogMessage = R.string.fuse_prime_envelope_summary, title = R.string.fuse_prime_envelope_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.PrimeEnvelopeLargeU, dialogMessage = R.string.fuse_prime_large_summary, title = R.string.fuse_prime_large_title))
        }

        cat("fuse_tail", "Haftung / Schwanz") {
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.TailGuardEnabled, summary = R.string.fuse_tail_guard_summary, title = R.string.fuse_tail_guard_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.LiabilityHorizonMin, dialogMessage = R.string.fuse_liability_horizon_summary, title = R.string.fuse_liability_horizon_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.TailFloorMgdl, dialogMessage = R.string.fuse_tail_floor_summary, title = R.string.fuse_tail_floor_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.TailRecoveryU, dialogMessage = R.string.fuse_tail_recovery_summary, title = R.string.fuse_tail_recovery_title))
        }

        cat("fuse_diag", "Diagnose (fest in Alpha 1)") {
            info("Regler-Takt: 1 Minute", "Jeder neue 1-min-CGM-Wert ist ein Zyklus. Fest - kein Legacy-SMB-Intervall.")
            info("Positive TBR: nicht verwendet", "Der schnelle Kanal ist der 1-min-SMB; FUSE setzt nur Null-Temps oder bricht ab. Max-TBR/Basal-Multiplikatoren greifen deshalb nicht.")
            info("ISF: Profil, zeitabhaengig", "Keine Autosens-/DynISF-Modulation. Sensitivitaet ist als eigener langsamer Beobachter geplant (Shadow zuerst).")
            info("COB/UAM: nicht verwendet", "Mahlzeiten erscheinen im insulinbereinigten Stoerungssignal; eigener Onset-Pfad + Marker statt UAM.")
        }
    }
}

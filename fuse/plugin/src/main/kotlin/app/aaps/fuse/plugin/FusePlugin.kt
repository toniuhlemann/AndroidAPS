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
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.fuse.plugin.ledger.FuseActivePump
import app.aaps.fuse.plugin.ledger.FusePatchEpoch
import app.aaps.fuse.plugin.ledger.LedgerFacts
import app.aaps.fuse.plugin.ledger.FusePatchEpochSource
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
 * [FusePumpGate] — eine Startverweigerung gegen JEDE Pumpe ausser der
 * VirtualPump und dem belegten Medtrum Nano, kein Schalter.
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
    private val context: Context,
    private val config: Config,
    private val rxBus: RxBus,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val constraintsChecker: ConstraintsChecker,
    private val commandQueue: CommandQueue,
    private val persistenceLayer: PersistenceLayer,
    private val processedTbrEbData: app.aaps.core.interfaces.db.ProcessedTbrEbData,
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

    /** Commitment-Ledger (Audit R95, Fix 3): EINE Instanz je Prozess. Geladen
     *  VOR dem ersten Zyklus, nach jedem Zyklus synchron persistiert - die
     *  Episodenbudgets und offenen Commitments ueberleben damit Neustarts. */
    private val ledgerAdapter = app.aaps.fuse.plugin.ledger.FuseLedgerAdapter()

    /**
     * Fix 8 (Audit 2d273cb, NEU-BS-07): der Ledger ist ZUSTAND, kein Export -
     * er liegt deshalb APP-PRIVAT (filesDir), nicht mehr im geteilten
     * Documents/aapsLogs. Auf dem geteilten Speicher kann jede App mit
     * All-Files-Zugriff, MTP/PC-Sync oder ein versehentliches Aufraeumen die
     * Buchhaltung ersetzen oder loeschen - ein syntaktisch gueltiger
     * Fremdinhalt wuerde zu Buchhaltung. Der Trail bleibt bewusst geteilt
     * (den LIEST der Viewer); den Ledger liest nur FUSE selbst. Die
     * Android-Aufloesung passiert ausschliesslich hier, Store und Adapter
     * bleiben ohne Geraet pruefbar. mkdirs uebernimmt der Store beim
     * Schreiben bzw. [migrateLedgerDirOnce] beim Umzug.
     */
    private fun ledgerDir() = File(context.filesDir, "fuse_ledger")

    @Volatile private var ledgerMigrationDone = false

    /**
     * EINMALIGER Umzug vom alten geteilten Verzeichnis - die gesamte Logik
     * (Fruehausstieg, .migtmp-Kopie mit Rueckleseprobe, Sentinel, .migrated-
     * Rotation) liegt in [app.aaps.fuse.plugin.ledger.LedgerDirMigration],
     * damit die Kill-/Fehlerpfade ohne Android testbar sind (Codex R4, N.1).
     * Hier bleiben nur die Android-Verzeichnisaufloesung und das Prozessflag.
     *
     * FAIL-CLOSED (Fix 1a, Re-Audit c750169 REG-03): das Prozessflag wird
     * erst NACH verifiziertem Abschluss gesetzt - jeder Fehlschlag liefert
     * false (der naechste invoke versucht erneut), und der Aufrufer setzt
     * fuer diesen Lauf den Migrations-Hold am Adapter.
     *
     * @return true, wenn die Vorgeschichte sicher uebernommen ist oder es
     * nachweislich nichts zu uebernehmen gibt.
     */
    private fun migrateLedgerDirOnce(): Boolean {
        if (ledgerMigrationDone) return true
        val ok = runCatching {
            app.aaps.fuse.plugin.ledger.LedgerDirMigration.migrate(
                oldDir = File(Environment.getExternalStorageDirectory(), "Documents/aapsLogs"),
                newDir = ledgerDir(),
                logError = { aapsLogger.error(LTag.APS, it) },
                logDebug = { aapsLogger.debug(LTag.APS, it) },
            )
        }.getOrElse {
            aapsLogger.error(LTag.APS, "FUSE ledger migration failed", it)
            false
        }
        if (ok) ledgerMigrationDone = true
        return ok
    }

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
            preferences.put(FuseLongKey.MealMarkerStamp, 0L)
            return false
        }
        // Fix-Pass 4 Nr. 16 (Alt-Finding F-P1-03): Timestamp und Stufe als
        // EIN atomarer Stempel (ts*10+Stufe) - ein Zyklus kann nie mehr alten
        // Timestamp mit neuer Stufe mischen. Die Einzel-Keys bleiben fuer
        // Lesbarkeit/Altbestand erhalten; gelesen wird primaer der Stempel,
        // der deshalb ZULETZT geschrieben wird.
        preferences.put(FuseLongKey.MealMarkerTier, tier.toLong().coerceIn(0L, 2L))
        preferences.put(FuseLongKey.MealMarkerArmedTs, now)
        preferences.put(FuseLongKey.MealMarkerStamp, now * 10L + tier.toLong().coerceIn(0L, 2L))
        synchronized(markerPressRing) {
            markerPressRing.addLast(now)
            while (markerPressRing.size > 20) markerPressRing.removeFirst()
        }
        return true
    }

    fun mealMarkerTier(): Int {
        val s = preferences.get(FuseLongKey.MealMarkerStamp)
        return if (s > 0L) (s % 10L).toInt().coerceIn(0, 2)
        else preferences.get(FuseLongKey.MealMarkerTier).toInt().coerceIn(0, 2)
    }

    fun mealMarkerArmedTs(): Long {
        val s = preferences.get(FuseLongKey.MealMarkerStamp)
        return if (s > 0L) s / 10L else preferences.get(FuseLongKey.MealMarkerArmedTs)
    }

    /** Huelle der aktuell gewaehlten Stufe [U] - fuer den Lieferstand im Tab. */
    fun mealMarkerEnvelopeU(): Double = when (mealMarkerTier()) {
        0    -> preferences.get(FuseDoubleKey.PrimeEnvelopeSmallU)
        2    -> preferences.get(FuseDoubleKey.PrimeEnvelopeLargeU)
        else -> preferences.get(FuseDoubleKey.PrimeEnvelopeU)
    }

    fun mealMarkerActive(now: Long): Boolean {
        val ts = mealMarkerArmedTs()
        return ts > 0 && now - ts in 0..(app.aaps.fuse.core.controller.OnsetChannel.MARKER_WINDOW_MIN * 60_000L)
    }

    /** Fix-Pass 4 Nr. 5 (Codex R4-05): das letzte ERFOLGREICH gefaellte
     *  Urteil von [specialEnableCondition]. Startwert FALSE - "noch nie
     *  geprueft" ist kein Erlaubniszustand. Kostet beim App-Start schlimmsten-
     *  falls Sekunden bis zur ersten erfolgreichen Pumpen-Lesung (VPUMP
     *  initialisiert schnell); dafuer kann eine Exception nie Erlaubnis aus
     *  unbekanntem Zustand erzeugen. */
    @Volatile private var lastEnableVerdict = false

    override fun specialEnableCondition(): Boolean =
        try {
            // Audit R95 F-P0-09: STARTVERWEIGERUNG statt nur Per-Zyklus-Riegel.
            // FUSE laesst sich mit einer NICHT ERLAUBTEN Pumpe gar nicht erst
            // als APS aktivieren - das TOCTOU-Fenster des Gates setzt sonst
            // voraus, dass die Kombination ueberhaupt konfigurierbar ist.
            // Erlaubt sind VirtualPump und der belegte Medtrum Nano; die
            // Liste fuehrt ausschliesslich [FusePumpGate].
            val pump = activePlugin.activePump
            val verdict = pump.pumpDescription.isTempBasalCapable && FusePumpGate.evaluate(pump).allowed
            lastEnableVerdict = verdict
            verdict
        } catch (_: Exception) {
            // Kann waehrend der Initialisierung fehlschlagen, bevor ein
            // Pumpenplugin steht. Dann gilt das LETZTE bekannte Urteil statt
            // pauschal true: ein transienter Init-Fehler darf ein einmal
            // gefaelltes Realpumpen-NEIN nicht in ein JA verwandeln
            // (Re-Audit 6.6).
            lastEnableVerdict
        }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null

        // ---- EIN Lesen der aktiven Pumpe je Zyklus --------------------------
        //
        // Alles Weitere wird HIERAUS abgeleitet: Typ, Emulationsflag,
        // Epochenauswertung, Pinnung, Riegel. Frueher standen dafuer mehrere
        // getrennte `activePlugin.activePump`-Zugriffe im Zyklus, und die
        // koennten - bei einem Pumpenwechsel mitten im Durchlauf - Merkmale aus
        // verschiedenen Momenten paaren. Genau diese Falle ist beim Serial
        // schon einmal aufgetreten.
        //
        // Der SERIAL bleibt bewusst draussen (s. [FuseActivePump]): er wird
        // nach einem Prozessstart asynchron nachgeladen und darum so SPAET wie
        // moeglich gelesen - naemlich erst beim Pinnen.
        val pumpe = runCatching { activePlugin.activePump }.getOrNull()
        val aktivePumpe = FuseActivePump.of(pumpe)

        // Ledger VOR dem Lauf restaurieren - NICHT wie warmGraphRingOnce nach
        // dem Lauf: der Zyklus rechnet mit den restaurierten Commitments und
        // Episodenbudgets, ein nachtraegliches Laden kaeme eine Dosis zu spaet.
        // Davor der einmalige Umzug ins app-private Verzeichnis (Fix 8).
        // FAIL-CLOSED (Fix 1a, REG-03): schlaegt der Umzug fehl, wird NICHT
        // geladen (loadOnce bliebe sonst auf dem leeren Ziel haengen) und der
        // Adapter haelt diesen Lauf wie unter recoveryHold an - kein positiver
        // SMB, solange die Vorgeschichte nicht sicher uebernommen ist. Der
        // naechste invoke versucht den Umzug erneut.
        if (migrateLedgerDirOnce()) {
            ledgerAdapter.noteMigrationDone()
            // B3: der PUMPENKONTEXT gehoert zum Laden. Die Migration braucht
            // ihn, weil eine v1-Zeile gar keinen Pin hat - ob sie gefahrlos
            // als Altbestand weiterbinden darf, haengt daran, welche Pumpe
            // heute laeuft. Ohne ihn kaeme immer "unbekannt" an, und jede
            // offene Altzeile ginge auch auf der VirtualPump in den Hold:
            // fail-closed, aber unnoetig blockierend.
            runCatching {
                ledgerAdapter.loadOnce(ledgerDir(), sessionId, dateUtil.now(), aktivePumpe) {
                    aapsLogger.error(LTag.APS, it)
                }
            }.onFailure { aapsLogger.error(LTag.APS, "FUSE ledger load failed", it) }
        } else {
            ledgerAdapter.noteMigrationFailed()
        }

        // `LoopPlugin.invoke` hat try/finally OHNE catch: eine Ausnahme von hier
        // wuerde den gesamten Loop-Durchlauf abbrechen — inklusive der Schritte
        // nach dem APS-Aufruf. Deshalb faengt FUSE selbst und liefert ein
        // Ergebnis, das nichts anfordert, aber den Grund traegt.
        // B3: die aktuelle PATCH-EPOCHE fuer diesen Zyklus bestimmen, BEVOR
        // gebunden oder publiziert wird.
        //
        // GENAU EINE Abfrage, und KEIN Rueckfall auf einen aelteren
        // Datensatz: `getLastTherapyRecordUpToNow` liefert den neuesten
        // gueltigen Wechsel. Passt der nicht zur aktiven Pumpe, ist die Epoche
        // unbekannt - auf einen aelteren passenden auszuweichen waere die
        // Behauptung, seither sei nichts geschehen, und genau das ist
        // unbekannt.
        val patchEpoch = FusePatchEpochSource.current(
            persistenceLayer,
            pumpe,
            aktivePumpe,
            dateUtil.now(),
        )
        ledgerAdapter.observePatchEpoch(patchEpoch.epochTs)

        val outcome = try {
            cycleRunner().run(tempBasalFallback)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "FUSE cycle failed", e)
            // SUB-02 Rest (Codex Re-Review 603a15a): eine Ausnahme umgeht das
            // interne abort() und liesse den Puls-Uebertrag stehen - eine
            // Zusage aus einem Zyklus, der nie zu Ende gerechnet wurde.
            runCatching { cycleRunner().discardSubStepCarry() }
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
            // C7c (Codex-Adjudication D-Tabelle C7 / Frage 2, K2 Punkt 10):
            // hier stand `tbr = null` - eine bereits laufende POSITIVE TBR lief
            // damit ungehindert weiter, obwohl der Abbruch-Vertrag (F-P0-07)
            // genau das verhindern soll. Der Ausnahmepfad ist der Pfad, auf dem
            // FUSE am wenigsten weiss; er darf nicht der einzige sein, der die
            // Aktuatoren unberuehrt laesst. Jetzt gilt DERSELBE Vertrag wie in
            // runner.abort(), aus DERSELBEN Implementierung.
            // EINE Uhrlesung fuer Klassifikation und RT: zwei Lesungen waeren
            // zwei verschiedene Momentaufnahmen in derselben Entscheidung.
            val abortTs = dateUtil.now()
            val abortTbr = FuseAbortTbr.evaluate(processedTbrEbData, profileFunction, abortTs)
            if (abortTbr.alarm)
                aapsLogger.error(LTag.APS, "FUSE exception path: running TBR not classifiable - no intervention")
            FuseAbortTbr.abortRt(
                nowMs = abortTs,
                gate = FusePumpGate.evaluate(pumpe),
                outcome = abortTbr,
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

        // Die Cycle-Id ist zugleich die proposalId des Ledgers - sie entsteht
        // deshalb HIER und nicht erst im Export.
        val cycleId = sessionId + "#" + (++cycleCounter)

        // Ledger-Verdrahtung + PUBLIKATIONS-GATING (Audit R95 Fix 3; Audit
        // 2d273cb REG-01a): erst der NEUE Vorschlag (nur wenn das RT wirklich
        // units traegt - das Gate hat sie sonst schon gefiltert), dann
        // Identitaeten binden, Vollsicht abgleichen, aufraeumen - und dann
        // MUSS ein VERIFIZIERTER Persist liegen, BEVOR der Loop das RT sieht.
        // Schlaegt der Persist fehl oder wirft ein Ledger-Schritt, publiziert
        // das Gate das RT OHNE SMB (Safety-TBR bleibt): ein Commitment, das
        // nicht auf Platte steht, existiert nach einem Kill nicht mehr, und
        // die Huelle stuende ein zweites Mal zur Verfuegung. persistFailed
        // haelt zusaetzlich kuenftige Zyklen ueber view().hold zu.
        // B0a (Codex-Gegenpruefung F3): OHNE Behandlungs-Vollsicht wird nicht
        // dosiert - und dann auch nicht gebucht.
        //
        // `FuseCycleRunner` liefert `treatmentView = runCatching{…}.getOrNull()`;
        // eine scheiternde Datenbankabfrage soll den Zyklus nicht kosten. Fuer
        // eine MENGE reicht das aber nicht: ohne Vollsicht laufen Bindung,
        // Reconciliation und prune nicht, und der C5-Anker des Vorschlags
        // (`latestBolusTs`) waere unbekannt. Der bisherige Code hat an dieser
        // Stelle `?: 0L` eingesetzt - eine 0 ist aber kein unbekannter
        // Zeitstempel, sondern der aelteste denkbare, und sie hat den C5-Guard
        // der spaeteren Bindung fuer genau diese Zeile entwertet.
        //
        // WARUM NICHT EINFACH `onPublished` UNTER `treatmentView?.let`
        // VERSCHIEBEN: dann entstuende keine Zeile, das Gate sah aber weiterhin
        // fehlerfreie Ereignisse und einen gelungenen Persist - und haette die
        // Menge publiziert. Genau deshalb traegt das Gate seit dem Vorcommit
        // einen expliziten Commitment: hier wird ausgesprochen, dass NICHTS
        // gebucht wird, und das Gate entfernt daraufhin units und deliverAt.
        // Die Safety-TBR bleibt.
        val expected = app.aaps.fuse.plugin.ledger.LedgerPublicationGate.commitmentOf(
            units = rt.units,
            treatmentViewPresent = outcome?.treatmentView != null,
            proposalId = cycleId,
            // B3: nur bei einer PATCHPUMPE, nicht bei "realer Pumpe".
            //
            // Das ist die praezisere Bedingung UND die richtige Schichtung:
            // die Epoche ist eine Eigenschaft von PATCHPUMPEN, nicht von
            // realen Pumpen. Auf `FusePumpGate` abzustellen wuerde B3 an den
            // Pumpenriegel koppeln, obwohl beide verschiedene Fragen
            // beantworten - der Riegel sagt, WOGEGEN aktuiert werden darf, die
            // Epoche, OB ein Bolus derselben Patchgeneration angehoert. Eine
            // kuenftige nicht-Patch-Realpumpe braucht keine Epoche, und eine
            // Patchpumpe braucht sie auch dann, wenn der Riegel sich aendert.
            //
            // Gegen die VirtualPump gibt es keine Patches; dort waere die
            // Epoche immer unbekannt und die Sperre haette den
            // Entwicklungspfad stillgelegt.
            //
            // Und "VirtualPump" ist NICHT am Typnamen zu erkennen: dort ist er
            // eine Preference und steht auf dem Testgeraet auf MEDTRUM_NANO
            // (s. [FuseActivePump]). Deshalb steht das Klassenmerkmal in der
            // Bedingung VOR der Typpruefung - und die Bedingung selbst steht
            // drueben am Zustand, wo sie einzeln pruefbar ist.
            realPumpEpochUnknown = aktivePumpe.realPumpEpochUnknown(patchEpoch.known),
        )

        val publication = app.aaps.fuse.plugin.ledger.LedgerPublicationGate.publish(
            rt = rt,
            adapter = ledgerAdapter,
            dir = ledgerDir(),
            expected = expected,
            events = {
                outcome?.let { o ->
                    // Gebucht wird NUR, wenn das Gate diese Zeile auch
                    // ERWARTET - sonst entstuende eine Haftung fuer eine Menge,
                    // die nie hinausgeht (Phantom-Commitment). Die Bedingung
                    // wird deshalb aus `expected` abgeleitet und nicht ein
                    // zweites Mal unabhaengig formuliert.
                    if (expected is app.aaps.fuse.plugin.ledger.LedgerPublicationGate.Commitment.Proposal &&
                        rt.units != null && o.treatmentView != null
                    ) {
                        // Fix 3 (Re-Audit 6.3): die JETZT aktive Pumpe wird an
                        // den Vorschlag gepinnt - ein spaeter gleich grosser
                        // SMB einer ANDEREN (z.B. frisch gewechselten) Pumpe
                        // darf die Zeile nicht binden. Ableitung wie
                        // LedgerFacts aus dem BS-Datensatz: PumpType.name und
                        // Sha des Serials. runCatching je Teil: eine zickende
                        // Pumpen-API degradiert nur zur Alt-Bindung (ohne
                        // Pinnung), sie wirft den Ledger-Schritt nicht ab.
                        // EINMAL gelesen und an BEIDE Stellen gegeben: die
                        // kanonische Serialform haengt seit der Codex-
                        // Gegenpruefung (F7) vom Pumpentyp ab. Zwei getrennte
                        // Lesungen koennten einen Typ mit einem Serial aus
                        // einem anderen Moment paaren. Typ und Klassenmerkmal
                        // stammen jetzt aus dem Zyklusanfang - derselbe
                        // Lesevorgang, der auch die Epoche bestimmt hat.
                        val pumpTypeName = aktivePumpe.pumpTypeName
                        ledgerAdapter.onPublished(
                            proposalId = cycleId,
                            unitsU = rt.units!!,
                            decisionTs = o.computeTs,
                            // Kein `?: 0L` mehr: gebucht wird nur MIT
                            // Vollsicht, der C5-Anker ist also immer echt.
                            latestBolusTs = o.treatmentView.latestBolusTs,
                            bolusStepU = o.state?.pumpIncrementU ?: Double.NaN,
                            pumpTypeName = pumpTypeName,
                            // LEER IST NICHT "EIN ANDERES GERAET" (Live-Befund
                            // 09.08., s. LedgerFacts.serialHashOf): direkt nach
                            // einem Prozessstart liefert serialNumber() den
                            // leeren String, weil InstanceId auf die
                            // asynchrone Firebase-Antwort wartet. Ohne diese
                            // Regel wird der leere Serial als Identitaet
                            // gepinnt, und der Sekunden spaeter mit dem echten
                            // Serial gebuchte Bolus passt nie mehr auf die
                            // eigene Zeile.
                            pumpSerialHash = pumpe?.let {
                                runCatching {
                                    app.aaps.fuse.plugin.ledger.LedgerFacts.serialHashOf(it.serialNumber(), pumpTypeName)
                                }.getOrNull()
                            },
                            // Die EMULATION wird mitgepinnt, nicht spaeter aus
                            // dem Typnamen rekonstruiert: eine umgestellte
                            // VirtualPump-Preference wuerde sonst alte Zeilen
                            // rueckwirkend umdeuten.
                            virtualPump = aktivePumpe.virtualPump,
                        )
                    }
                    o.treatmentView?.let { v ->
                        ledgerAdapter.bindIdentities(v.boluses)
                        ledgerAdapter.onCycleSnapshot(v.facts, v.snapshotHash, o.computeTs)
                        ledgerAdapter.prune(o.computeTs, v.diaHours)
                    }
                }
            },
            onError = { aapsLogger.error(LTag.APS, "FUSE ledger update failed", it) },
        )
        val publishRt = publication.rt
        if (!publication.allowed && rt.units != null)
            aapsLogger.error(LTag.APS, "FUSE SMB stripped from published RT: ${publication.reason}")

        lastAPSResult = apsResultProvider.get().with(publishRt)
        lastAPSRun = dateUtil.now()
        aapsLogger.debug(LTag.APS, "FUSE result: ${publishRt.reason}")
        rxBus.send(EventAPSCalculationFinished())

        exportState(
            outcome, publishRt, cycleId,
            // B0c: der Befund des Gates als DATEN in den Trail, nicht als Text
            // im Grund. `treatmentViewPresent` kommt vom Zyklus selbst - das
            // Gate erfaehrt es nur mittelbar ueber den Commitment, und eine
            // zweite Ableitung waere eine zweite Wahrheit.
            FuseStateJson.PublicationGate(
                allowed = publication.allowed,
                reason = publication.reason,
                treatmentViewPresent = outcome?.treatmentView != null,
            ),
            // B3: die Diagnose neben dem Sperrgrund. Der Grund steht im
            // Publikationsgate, das WARUM steht hier - kein Datensatz,
            // Handeintrag, fremde Pumpe und unlesbare aktive Pumpe sind vier
            // Ursachen mit vier verschiedenen Massnahmen.
            FuseStateJson.PatchEpoch(
                epochTs = patchEpoch.epochTs,
                known = patchEpoch.known,
                reason = patchEpoch.reason.name,
                // Ohne diese Angabe liest sich `known=false / NO_EVENT` an der
                // VirtualPump wie ein Defekt - dort ist es der Normalzustand.
                applicable = aktivePumpe.realPatchPump,
            ),
        )
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
    private fun exportState(
        outcome: FuseCycleRunner.Outcome?,
        rt: RT,
        cycleId: String,
        publicationGate: FuseStateJson.PublicationGate,
        patchEpoch: FuseStateJson.PatchEpoch,
    ) {
        runCatching {
            val start = System.nanoTime()
            val o = outcome ?: return
            val json = FuseStateJson.record(
                cycleId = cycleId,
                outcome = o,
                rt = rt,
                policy = o.policy,
                build = FuseStateJson.Build(config.VERSION_NAME, config.HEAD, config.COMMITTED),
                buildStartNs = start,
                prev = prevWrite,
                nowNs = System::nanoTime,
                // Die Sicht NACH den Buchungen dieses Zyklus - genau der
                // Zustand, mit dem der naechste Zyklus rechnen wird.
                ledger = FuseStateJson.LedgerSnapshot(ledgerAdapter.revision, ledgerAdapter.state),
                publicationGate = publicationGate,
                // B3: die Diagnose neben dem Sperrgrund. Der Grund steht im
                // Publikationsgate, das WARUM steht hier.
                patchEpoch = patchEpoch,
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
            processedTbrEbData = processedTbrEbData,
            dateUtil = dateUtil,
            ledger = ledgerAdapter,
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

        /**
         * UHRZEIT statt Minuten-ab-Mitternacht (Toni 09.08.: "1380 - was ist
         * das denn?"). Gespeichert werden weiter MINUTEN - das ist die Groesse,
         * mit der der Regler rechnet, und sie bleibt exportierbar wie jeder
         * andere Int-Key. Nur die ANZEIGE ist menschenlesbar: die Zeile zeigt
         * "23:00" und oeffnet beim Antippen die Uhrzeit-Auswahl.
         *
         * Eigener Helfer statt AdaptiveListIntPreference: die Listen-Variante
         * legt ihren Wert als TEXT ab (sie erbt von ListPreference) - der
         * Int-Key des Reglers wuerde ihn beim Lesen nicht wiedererkennen.
         */
        fun PreferenceScreen.timeOfDay(intKey: FuseIntKey, titleText: String, sum: String) {
            addPreference(Preference(context).apply {
                key = intKey.key
                title = titleText
                fun show(v: Int) { summary = "%02d:%02d  -  %s".format(v / 60, v % 60, sum) }
                show(preferences.get(intKey))
                setOnPreferenceClickListener {
                    val cur = preferences.get(intKey)
                    runCatching {
                        android.app.TimePickerDialog(
                            context,
                            { _, h, m ->
                                val v = (h * 60 + m).coerceIn(intKey.min, intKey.max)
                                preferences.put(intKey, v)
                                show(v)
                            },
                            cur / 60, cur % 60, true,
                        ).show()
                    }
                    true
                }
            })
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
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.NightDeadbandEnabled, summary = R.string.fuse_night_deadband_enabled_summary, title = R.string.fuse_night_deadband_enabled_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.NightDeadbandMgdl, dialogMessage = R.string.fuse_night_deadband_summary, title = R.string.fuse_night_deadband_title))
            timeOfDay(FuseIntKey.NightStartMin, "Nacht Beginn", "Beginn des Nachtfensters; gleich dem Ende schaltet es aus")
            timeOfDay(FuseIntKey.NightEndMin, "Nacht Ende", "Ende des Nachtfensters (darf ueber Mitternacht gehen)")
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.ReboundDeadbandEnabled, summary = R.string.fuse_rebound_deadband_enabled_summary, title = R.string.fuse_rebound_deadband_enabled_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.ReboundDeadbandMgdl, dialogMessage = R.string.fuse_rebound_deadband_summary, title = R.string.fuse_rebound_deadband_title))
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
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.AbsorptionCreditWindowMin, dialogMessage = R.string.fuse_absorption_credit_summary, title = R.string.fuse_absorption_credit_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.MarkerBoostMaxMin, dialogMessage = R.string.fuse_marker_boost_summary, title = R.string.fuse_marker_boost_title))
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

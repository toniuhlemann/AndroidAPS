package app.aaps.fuse.plugin

import app.aaps.fuse.plugin.expectation.FuseExpectationStore
import app.aaps.fuse.plugin.expectation.FuseExpectationRecorder
import app.aaps.fuse.core.controller.ExpectationLedger
import android.content.Context
import android.os.Environment
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.core.controller.MarkerPrompt
import app.aaps.fuse.core.controller.MarkerTimeline
import app.aaps.fuse.core.controller.MealFoundation
import app.aaps.fuse.core.controller.PrimeRelease
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.Notification
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
import app.aaps.core.validators.preferences.AdaptiveListIntPreference
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
    /**
     * NUR ZUM MESSEN (Scheibe 1, 11.08.): was AAPS mit dem publizierten SMB
     * gemacht hat. `Lazy`, weil FUSE das aktive APS IST und LoopPlugin dieses
     * ueber `activePlugin` zur Laufzeit sucht - eine direkte Injektion waere
     * heute zwar zyklenfrei, aber die Richtung der Abhaengigkeit ist die
     * ungewoehnliche, und ein Dagger-Zyklus faellt erst beim App-Start auf.
     */
    private val loop: dagger.Lazy<app.aaps.core.interfaces.aps.Loop>,
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
    private val uiInteraction: app.aaps.core.interfaces.ui.UiInteraction,
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
    /**
     * `var`, damit die Reparatur ihn ERSETZEN kann statt ihn auszuraeumen.
     *
     * Ein frisch gebautes Objekt hat jedes Feld auf seinem Anfangswert - per
     * Konstruktion, ohne dass jemand eine Liste pflegt. Ein Hand-Reset
     * (`loaded = false; state = ...; recoveryHold = false; ...`) waere genau
     * die Sorte Aufzaehlung, bei der ein Feld vergessen wird - und das
     * vergessene waere hier ein gehaltener Zustand, der die Reparatur still
     * ueberlebt.
     */
    private var ledgerAdapter = app.aaps.fuse.plugin.ledger.FuseLedgerAdapter()

    /** Die letzte Ledger-Reparatur, einmal gelesen und danach im Speicher
     *  gehalten - der Export haengt sie an jeden Zyklus. */
    private var reparaturCache: app.aaps.fuse.plugin.ledger.FuseLedgerRepair.ResetRecord? = null
    private var reparaturGelesen = false

    /**
     * Der vorgemerkte Reparaturauftrag.
     *
     * NICHT nur der Objekttausch gehoert an die Zyklusgrenze, sondern auch die
     * DATEIREPARATUR - sonst schreibt ein gleichzeitig laufender Zyklus seinen
     * alten Ledger ueber die frisch reparierten Dateien zurueck und belebt
     * genau die Fehlerzeilen wieder, die den Hold ausgeloest haben
     * (s. [app.aaps.fuse.plugin.ledger.FuseRepairScheduler]).
     */
    private val reparaturAuftrag = app.aaps.fuse.plugin.ledger.FuseRepairScheduler()
    private val holdQuittung = app.aaps.fuse.plugin.ledger.FuseHoldQuittungScheduler()

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

    /** Eigenes Verzeichnis, getrennt von der Reparaturdomaene des
     *  Insulinledgers (s. FuseExpectationStore.DIR_NAME). */
    private fun expectationDir() = FuseExpectationStore.dirIn(context.filesDir)
        .also { runCatching { if (!it.isDirectory) it.mkdirs() } }

    /** Der Erwartungs-Ledger. REIN BEOBACHTEND - kein Regelpfad liest ihn. */
    private val expectationRecorder = FuseExpectationRecorder()

    /**
     * DEN ZYKLUS IN DEN ERWARTUNGS-LEDGER BUCHEN.
     *
     * Alles hier drin ist gekapselt und folgenlos fuer die Dosierung: der
     * Aufrufer prueft keinen Rueckgabewert, und jeder Fehler bleibt im
     * [FuseExpectationRecorder] stehen. Ein Messbaustein darf einen
     * Regelzyklus nicht kosten.
     */
    private fun buchereWartung(outcome: FuseCycleRunner.Outcome?, sealed: Boolean) {
        if (!preferences.get(FuseBooleanKey.ExpectationLedgerEnabled)) return
        // ALLES HIER MUSS SCHNELL SEIN. `submit` legt einen Schnappschuss in
        // eine begrenzte Schlange und kehrt sofort zurueck; geschrieben wird
        // auf einem eigenen Thread. Der Loop ruft diese Methode SYNCHRON
        // innerhalb von `invoke()` auf, und LoopPlugin aktuiert erst nach
        // dessen Rueckkehr - jede Millisekunde hier ist Verzoegerung an der
        // Pumpe.
        runCatching {
            val o = outcome ?: return@runCatching
            val stempel = ledgerAdapter.interventionStamp
            val dir = expectationDir()
            val bahn = o.prediction
            val lage = o.expectationSituation?.copy(ledgerSealed = sealed)
            val proben = o.signal?.let { sig ->
                o.bgMgdl?.let { bg ->
                    listOf(
                        ExpectationLedger.Sample(
                            // STABILE EPOCHE, nicht die gleitende Fensterkante
                            // (Toni 22.08.): mit `segmentStartTs` konnten sich
                            // Entry und Probe per Konstruktion NIE treffen -
                            // alle 1091 Outcomes des ersten Laufs waren
                            // UNVERIFIABLE.
                            ts = sig.sourceTs, mgdl = bg, segmentId = sig.signalEpochTs,
                            healthy = o.health == app.aaps.fuse.core.observer.Health.READY,
                            interventionStamp = stempel,
                            configGeneration = o.configGeneration,
                            // Die Lage zu DIESEM Messpunkt - sie entscheidet
                            // spaeter mit, ob die Beobachtung ueberhaupt zaehlt.
                            context = lage?.let { ExpectationLedger.classify(it).context }
                                ?: ExpectationLedger.ExpectationContext.EXCLUDED,
                        ),
                    )
                }
            } ?: emptyList()
            val angenommen = expectationRecorder.submit(
                FuseExpectationRecorder.Snapshot(
                    dir = dir, nowTs = o.computeTs, situation = lage, stamp = stempel,
                    configGeneration = o.configGeneration,
                    segmentId = o.signal?.signalEpochTs ?: 0L,
                    sourceTs = o.signal?.sourceTs ?: o.computeTs,
                    anchorMgdl = bahn?.bgAtAnchor,
                    meanPredictedMgdl = bahn?.bgAtHorizonMean,
                    horizonMin = o.policy?.liabilityHorizonMin ?: 0,
                    safetyLowerPredictedMgdl = bahn?.bgAtHorizonLower,
                    lambda = null, samples = proben,
                ),
            )
            if (!angenommen) aapsLogger.debug(
                LTag.APS, "FUSE expectation: Zyklus verworfen (Rueckstau) - Messluecke, keine Dosisfolge",
            )
        }
    }

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

    /**
     * Der in DIESEM Prozess beobachtete Markerdruck, 0 = keiner.
     *
     * ER IST BEWUSST NICHT PERSISTENT und darf es auch nicht werden: er ist
     * genau der Beweis, dass der Druck NACH dem letzten Prozessstart lag. Ein
     * persistierter Wert waere derselbe Preference-Wert unter anderem Namen
     * und beantwortete die Frage nicht mehr.
     *
     * NICHT AUS [markerPressRing] ableiten - der wird beim Warmstart aus dem
     * Trail nachgefuellt und kennt Druecke aus frueheren Prozessen.
     *
     * `@Volatile`, weil der Knopf im UI-Thread schreibt und der Zyklus in
     * seinem eigenen liest.
     */
    @Volatile private var markerPressObservedTs: Long = 0L

    /**
     * Versuche des Warmstarts. NICHT nur ein Bit, und der Grund ist der Fall,
     * den die beiden vorigen Anlaeufe uebrig gelassen haben: eine Trail-Datei,
     * die EXISTIERT, aber noch leer ist - direkt nach einer Neuinstallation,
     * bevor der erste Zyklus geschrieben hat. Der Lauf gelingt dann formal und
     * liest nichts, und mit einem Bit waere der Warmstart fuer die ganze
     * Prozesslebenszeit verbraucht.
     *
     * Begrenzt, weil die Datei bis zu 32 MB gross wird: sie in jedem Zyklus
     * neu zu lesen waere teurer als der Nutzen. Fuenf Versuche decken die
     * ersten Minuten nach einer Installation ab.
     */
    /**
     * SCHEIBE 1 - NUR MESSEN. Was AAPS mit dem SMB des VORIGEN Zyklus gemacht
     * hat. Keine Zeile davon aendert eine Dosis oder ein Budget.
     *
     * WARUM ES DEN VORIGEN ZYKLUS BETRIFFT: FUSEs `invoke()` kehrt bei
     * LoopPlugin.kt:484 zurueck, die Constraints laufen bei 504-524, und
     * `lastRun` wird erst bei 526-537 geschrieben. Waehrend des eigenen Zyklus
     * beschreibt `lastRun` also noch den Lauf davor. Das ist deterministisch
     * und keine Zufallsfrage - aber es MUSS im Export dranstehen, sonst liest
     * jemand die Zahlen als die dieses Zyklus.
     */
    @Volatile private var publishedRt: RT? = null
    @Volatile private var publishedTs: Long = 0L
    @Volatile private var priorActuation: PriorActuation? = null

    /**
     * DIE BRUECKE zwischen dem Zyklus, der eine Menge gebucht hat, und dem
     * naechsten, der prueft, ob sie je hinausging (s. [NotSentProof]).
     * Zusammen mit [publishedRt] gelesen und gesetzt - vier Felder, ein
     * Moment.
     */
    @Volatile private var publishedProposalId: String? = null
    @Volatile private var publishedGateStripped = false
    @Volatile private var publishedGateSealed = false
    @Volatile private var publishedGatePersistFailed = false

    /** Der im laufenden Zyklus gebildete Entlastungs-Beleg, gebucht im
     *  events-Block des Publikationsgates. */
    @Volatile private var notSentClaim: Pair<String, app.aaps.fuse.core.ledger.QueueRejectReason>? = null

    /**
     * Die drei Werte der beobachtbaren Stufe, plus die Herkunft.
     *
     * DIE ACHSE IST FUENFTEILIG, nicht vierteilig (Review 11.08.):
     *
     *   certifiedU -> fusePublishedU -> aapsConstrainedU -> queueRequestedU -> enactedU
     *
     * `constraintsProcessed` wird gespeichert, BEVOR Suspend-, Loop- und
     * Queue-Pruefungen, die TBR-Ausfuehrung, `applySMBRequest`, das ZWEITE
     * Intervalltor und `CommandSMBBolus` gelaufen sind. Es ist damit
     * `aapsConstrainedU` und ausdruecklich NICHT "an die Queue uebergeben".
     * `queueRequestedU` ist heute nicht beobachtbar.
     */
    class PriorActuation(
        /** `computeTs` des Zyklus, den diese Zahlen beschreiben. */
        val ofComputeTs: Long,
        /** Identitaetsprobe bestanden? Bei `false` sind die Zahlen null - ein
         *  fremder oder veralteter `lastRun` liefert lieber nichts als
         *  irgendetwas. */
        val correlated: Boolean,
        val fusePublishedU: Double?,
        /** Nach `applyBolusConstraints`, VOR dem ersten Intervalltor. */
        val afterBolusConstraintsU: Double?,
        /** Nach dem ersten Intervalltor - dieses nullt, die beiden spaeteren
         *  Tore lassen die Menge stehen und verweigern nur die Ausfuehrung. */
        val aapsConstrainedU: Double?,
        /**
         * Hat AAPS im Apply-Block seinen Platzhalter gesetzt? `false` heisst:
         * der Block wurde nie betreten, also ging kein Bolus-Kommando hinaus
         * (s. [app.aaps.fuse.core.ledger.NotSentProof]). `null` = die
         * Identitaetsprobe ist durchgefallen, dann gilt nichts als bekannt.
         */
        val smbSetByPumpPresent: Boolean?,
    )

    /**
     * Liest den Ausgang des VORIGEN Zyklus. Am Anfang des Zyklus, bevor der
     * eigene Lauf `publishedRt` ueberschreibt.
     *
     * Verglichen wird die identitaet der `RT`-INSTANZ aus `lastRun.request`,
     * nicht die der APSResult-Huelle: `newAndClone()` erzeugt eine neue Huelle,
     * haelt aber dieselbe `RT`. Ein Zyklus, den LoopPlugin nie verarbeitet hat
     * (z.B. ein Aufruf ausserhalb), faellt damit sofort durch die Probe.
     */
    private fun leseVorigenAusgang() {
        val erwartet = publishedRt ?: return
        val lr = runCatching { loop.get().lastRun }.getOrNull()
        val trifft = lr?.request?.rawData() === erwartet
        priorActuation = PriorActuation(
            ofComputeTs = publishedTs,
            correlated = trifft,
            fusePublishedU = if (trifft) lr?.request?.smb else null,
            afterBolusConstraintsU = if (trifft) lr?.constraintsProcessed?.smbConstraint?.value() else null,
            aapsConstrainedU = if (trifft) lr?.constraintsProcessed?.smb else null,
            // AUS DERSELBEN LESUNG wie die uebrigen Felder - zwei Lesungen
            // koennten Zahlen aus verschiedenen Momenten paaren, dieselbe
            // Falle wie beim Pumpen-Serial.
            smbSetByPumpPresent = if (trifft) (lr?.smbSetByPump != null) else null,
        )
    }

    @Volatile private var graphRingAttempts = 0

    private val GRAPH_RING_MAX_ATTEMPTS = 5

    /** Delegiert an [MarkerTimeline] - die Regel steht dort und ist dort
     *  auch geprueft; diese Kette war bis 11.08. an keiner Stelle getestet. */
    override fun fuseMealMarkerTimes(fromTime: Long, endTime: Long): List<Long> =
        MarkerTimeline.visible(
            pressRing = synchronized(markerPressRing) { markerPressRing.toList() },
            armedTs = mealMarkerArmedTs(),
            fromTime = fromTime,
            endTime = endTime,
        )

    /**
     * RING-WARMSTART (08.08.): die F.DRV/F.GRD-Linien leben im Prozess und
     * waren nach jedem Flash leer. Beim ersten Zyklus wird der Ring einmalig
     * aus dem Trail (letzte ~24 h) nachgefuellt - der Trail bleibt die
     * einzige Wahrheit, keine DB. Fehler sind still-tolerant: ein kaputter
     * Warmstart darf keinen Zyklus kosten.
     */
    /** Einmal je Prozess: juengstes Tief aus dem Trail in den Runner. */
    private fun warmLastLowOnce() {
        if (lastLowWarmed) return
        val f = java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "Documents/aapsLogs/fuse_state_history.jsonl"
        )
        if (!f.exists()) return          // s. warmGraphRingOnce: Flag erst bei Arbeit
        lastLowWarmed = true
        runCatching {
            val ts = f.bufferedReader().useLines { lines ->
                FuseLowMemory.lastLowTsFromTrail(
                    lines, System.currentTimeMillis(), preferences.get(FuseIntKey.ReboundWindowMin)
                )
            }
            if (ts > 0L) cycleRunner().primeLastLowTs(ts)
        }
    }

    private var lastLowWarmed = false

    private fun warmGraphRingOnce() {
        if (graphRingAttempts >= GRAPH_RING_MAX_ATTEMPTS) return
        graphRingAttempts++
        val f = java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "Documents/aapsLogs/fuse_state_history.jsonl"
        )
        // DAS FLAG ERST, WENN ES ETWAS ZU LESEN GAB (Sweep 11.08.).
        //
        // Vorher stand `graphRingWarmed = true` VOR der Arbeit, und der
        // Ausstieg bei fehlender Datei war ein nichtlokales `return` aus dem
        // `runCatching`. Beim allerersten Start - Neuinstallation, oder wenn
        // der erste Zyklus vor dem ersten Trail-Schreibvorgang laeuft - war
        // der Warmstart damit fuer die gesamte Prozesslebenszeit verbraucht,
        // ohne je eine Zeile gelesen zu haben. Ein `exists()` je Zyklus
        // kostet nichts gegen einen dauerhaft leeren Graphen.
        if (!f.exists()) return
        runCatching {
            val cutoff = System.currentTimeMillis() - 25L * 3600_000L
            val pts = ArrayList<app.aaps.core.interfaces.overview.FuseOverviewSource.Point>()
            // MARKERDRUCKE aus dem Trail (11.08.). Der Ring lebt im Prozess -
            // nach jedem Flash blieb genau EIN Marker uebrig, der aus der
            // Preference. Bei vier Flashes an einem Abend heisst das: der
            // Graph zerfaellt in Stuecke, obwohl der Trail alles weiss.
            //
            // Moeglich ist das erst, seit `state.markerArmedTs` im Datensatz
            // steht; vorher stand dort nur ein Boolean "Marker aktiv", und aus
            // dem laesst sich kein Zeitpunkt gewinnen.
            //
            // Zurueckgenommene Marker kommen dabei mit - genau wie beim Ring,
            // und aus demselben Grund: der Graph ist ein Protokoll, kein
            // Zustandsanzeiger (s. MarkerTimeline).
            val marks = LinkedHashSet<Long>()
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val j = runCatching { org.json.JSONObject(line) }.getOrNull() ?: continue
                    val ts = j.optLong("sourceTs", 0L)
                    if (ts < cutoff) continue
                    j.optJSONObject("state")?.optLong("markerArmedTs", 0L)
                        ?.takeIf { it > 0L && it >= cutoff }?.let { marks.add(it) }
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
                    val tl = j.optJSONObject("tail")
                    val tailMargin = tl?.let {
                        FuseGraphMargin.tailMarginMgdl(
                            headroomU = it.optDouble("headroomU", Double.NaN),
                            isfTailMgdlPerU = it.optDouble("isfTailMgdlPerU", Double.NaN),
                            // isNull() IST HIER PFLICHT: Androids optString gibt
                            // fuer ein JSON-null den String "null" zurueck, nicht
                            // den Default (JVM-org.json tut das Gegenteil - ein
                            // Unit-Test haette den Fehler nie gezeigt, das Geraet
                            // schon: die ganze Warmstart-Linie war weg).
                            invalidReason = if (it.isNull("invalidReason")) null
                            else it.optString("invalidReason", "").takeIf { r -> r.isNotEmpty() },
                        )
                    }
                    pts.add(
                        app.aaps.core.interfaces.overview.FuseOverviewSource.Point(
                            timestamp = ts,
                            driveMgdlPerMin = r.takeIf { it.isFinite() },
                            fastDriveMgdlPerMin = fast.takeIf { it.isFinite() },
                            guardMarginMgdl = (ml - gf).takeIf { it.isFinite() }?.coerceIn(-50.0, 150.0),
                            tailMarginMgdl = tailMargin,
                        )
                    )
                }
            }
            synchronized(graphRing) {
                if (graphRing.isEmpty()) {
                    graphRing.addAll(pts.takeLast(1_500))
                }
            }
            // Nur nachfuellen, nie ueberschreiben: ein Druck, der seit dem
            // Start passiert ist, steht schon drin und ist der aktuellere.
            synchronized(markerPressRing) {
                for (m in marks.sorted()) MarkerTimeline.add(markerPressRing, m)
            }
            // ERST WENN WIRKLICH ETWAS GELESEN WURDE (dritter Anlauf,
            // Review 11.08.). Anlauf eins setzte das Flag vor der Arbeit,
            // Anlauf zwei hinter `exists()` - beide liessen den Fall stehen,
            // dass die Datei EXISTIERT und leer ist. Der Lauf gelingt dann
            // formal, liest nichts, und der Warmstart ist verbraucht.
            //
            // Ein leerer Trail ist kein Fehler, sondern der Normalzustand in
            // den ersten Minuten nach einer Installation. Also: Erfolg heisst
            // "es kamen Punkte an", und sonst wird es erneut versucht - bis zu
            // GRAPH_RING_MAX_ATTEMPTS mal, denn die Datei wird bis 32 MB gross.
            if (pts.isNotEmpty()) graphRingAttempts = GRAPH_RING_MAX_ATTEMPTS
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
    /**
     * EIN Marker, keine Stufen mehr (11.08.).
     *
     * S/M/L waren eine Ankuendigung der MENGE. Genau die kann der Nutzer im
     * Moment des Knopfdrucks nicht verlaesslich abschaetzen - und FUSE soll
     * die Mahlzeit ohne Kohlenhydratangabe bedienen; eine dreistufige
     * Mengenschaetzung ist eine Kohlenhydratangabe mit anderem Namen. Der
     * Knopf sagt nur noch WANN, und das ist die Information, die der Nutzer
     * wirklich hat.
     *
     * Damit faellt auch der atomare Stempel weg: er band Zeitpunkt und Stufe
     * aneinander, und ohne Stufe gibt es nur noch einen Wert.
     */
    fun toggleMealMarker(now: Long, ohneVorschuss: Boolean = false): Boolean {
        val armed = mealMarkerActive(now)
        if (armed) {
            // Die Beobachtung stirbt mit der Ruecknahme. Bliebe sie stehen,
            // koennte ein spaeter aus den Preferences gelesener Marker sie
            // erben - und genau diese Erbschaft soll es nicht geben.
            markerPressObservedTs = 0L
            // DIE LINIE IM GRAPHEN FOLGT DEM INSULIN, nicht der Absicht: blieb
            // der Druck folgenlos, verschwindet er auch aus dem Graphen. Vorher
            // stand dort eine Mahlzeitenlinie ohne Mahlzeit und ohne Insulin.
            val geliefert = lastOutcome?.mealStats?.totalU ?: 0.0
            synchronized(markerPressRing) {
                MarkerTimeline.retract(markerPressRing, mealMarkerArmedTs(), geliefert)
            }
            preferences.put(FuseLongKey.MealMarkerArmedTs, 0L)
            // Die Episoden-Wahl stirbt mit dem Marker - ein spaeterer Druck
            // beginnt immer bei der vollen Huelle.
            preferences.put(FuseLongKey.MealMarkerNoPrime, 0L)
            // Der Altbestand-Stempel wird mitgeloescht: bliebe er stehen,
            // liesse der Lese-Ruecktausch unten einen zurueckgenommenen
            // Marker wieder auferstehen.
            preferences.put(FuseLongKey.MealMarkerStamp, 0L)
            return false
        }
        preferences.put(FuseLongKey.MealMarkerArmedTs, now)
        preferences.put(FuseLongKey.MealMarkerNoPrime, if (ohneVorschuss) 1L else 0L)
        // DER EINZIGE ORT, an dem ein Druck als BEOBACHTET gilt. Er steht
        // absichtlich hier und nicht beim Nachfuellen des Marker-Rings: der
        // liest den Trail und kennt Druecke von vor zwei Stunden.
        markerPressObservedTs = now
        // Auch beim ARMEN raeumen (Sweep 11.08.): bisher tat das nur der
        // Ruecknahme-Zweig. Ein Stempel aus der Zeit vor dem Umbau ueberlebte
        // damit beliebig lange und blieb als latenter Ruecktausch liegen -
        // wirkungslos, solange `armedTs` gesetzt ist, aber scharf in genau
        // dem Moment, in dem es das nicht ist. Nach dem ersten Druck auf
        // dieser Version ist der Schluessel endgueltig leer.
        preferences.put(FuseLongKey.MealMarkerStamp, 0L)
        synchronized(markerPressRing) { MarkerTimeline.add(markerPressRing, now) }
        return true
    }

    /**
     * Zeitpunkt des Knopfdrucks, 0 = kein Marker.
     *
     * `armedTs` ist die Quelle. Der ALTBESTAND-Stempel wird nur noch
     * herangezogen, wenn `armedTs` leer ist - der Fall tritt genau einmal
     * auf: ein Marker, der beim Update dieser Version gerade lief.
     * `stamp / 10` liefert den Zeitpunkt fuer jede der drei alten Stufen.
     */
    fun mealMarkerArmedTs(): Long {
        val ts = preferences.get(FuseLongKey.MealMarkerArmedTs)
        if (ts > 0L) return ts
        val legacy = preferences.get(FuseLongKey.MealMarkerStamp)
        return if (legacy > 0L) legacy / 10L else 0L
    }

    /** Die EINE Huelle [U] - fuer den Lieferstand im Tab. S. [toggleMealMarker]. */
    fun mealMarkerEnvelopeU(): Double = preferences.get(FuseDoubleKey.PrimeEnvelopeU)

    /**
     * Lebensdauer der FREIGABE [min] - DIESELBE Einstellung, die der Regler
     * liest (`FuseCycleRunner` holt sie ueber denselben Schluessel).
     *
     * Nicht zu verwechseln mit der Lebensdauer des MARKERS (90 min): das sind
     * zwei Uhren, und die Anzeige hat sie bis 17.08.2026 vermischt.
     */
    fun primeWindowMin(): Int? = preferences.get(FuseIntKey.PrimeWindowMin).takeIf { it > 0 }

    // ---- Der Knopf auf dem Uebersichtsschirm ------------------------------

override fun fuseMarkerArmed(now: Long): Boolean = mealMarkerActive(now)

    /**
     * Die Zahlen fuer die Rueckfrage - aus dem LETZTEN Zyklus, nicht neu
     * gerechnet. Ein zweiter Rechenweg fuer dieselbe Groesse waere eine zweite
     * Wahrheit, und der Dialog wuerde Zahlen zeigen, die der Regler so nie
     * gesehen hat.
     *
     * `firstStepU` ist deshalb der Plan-Boden des letzten Zyklus, wenn es ihn
     * gibt; sonst der rechnerische Zyklusanteil der ganzen Huelle. Beides ist
     * eine OBERGRENZE - was wirklich herauskommt, kappen maxSmb, iobTH, maxIOB
     * und die Pumpenschrittweite.
     */
    override fun fuseMarkerPrompt(now: Long): FuseOverviewSource.MarkerPromptFacts? {
        val huelle = mealMarkerEnvelopeU()
        val letzter = lastOutcome
        val geliefert = letzter?.mealStats?.totalU ?: 0.0
        // DIE MENGEN AUS DER AUTORISIERUNG, DIE DER DRUCK ERZEUGEN WUERDE
        // (Tonis UI-P0 vom 25.08. abends) - berechnet mit DERSELBEN
        // Funktion, die der Runner beim Armen aufruft, mit denselben
        // Einstellungen. Kein zweiter Rechenweg, kein `firstStepU`, kein
        // fest verdrahtetes 15-Minuten-Fenster mehr.
        //
        // WARUM DAS NOETIG WAR: der Dialog nannte den Zyklusanteil der
        // alten Prime-Schrittrechnung ("0,27 U"), waehrend bei
        // Sofortanteil 1,0 in Wahrheit der ganze Phase-A-Betrag sofort
        // angefordert wird (3,20 U bei Huelle 4,0 und Phase-A-Anteil 0,8).
        val fensterMin = preferences.get(FuseIntKey.PrimeWindowMin)
        val fundamentEndeMin = preferences.get(FuseIntKey.MealFoundationEndMin)
        val vorschau = MealFoundation.arm(
            markerTs = now,
            foundationEnabled = preferences.get(FuseBooleanKey.MealFoundationEnabled),
            totalBudgetU = huelle,
            phaseAShare = preferences.get(FuseDoubleKey.MealFoundationPhaseAShare),
            phaseAUpfrontShare = preferences.get(FuseDoubleKey.MealFoundationPhaseAUpfrontShare),
            primeWindowMin = fensterMin,
            wallCeilingMin = PrimeRelease.WALL_CEILING_MIN,
            phaseBUntilMin = fundamentEndeMin,
            markerAuthorized = preferences.get(FuseBooleanKey.MarkerAuthorisesRelease),
            // VORSCHAU: der Druck geschieht in diesem Moment, und "ohne
            // Vorschuss" waehlt der Nutzer erst IM Dialog. Beides hier
            // bejaht, sonst rechnete die Vorschau eine leere Autorisierung.
            pressObservedInThisProcess = true,
            primeDeclinedByUser = false,
        )
        // OHNE FUNDAMENT bleibt es beim reinen Prime-Verhalten: die ganze
        // (Rest-)Huelle laeuft verteilt ueber das Fenster, kein
        // Sofortanteil, kein Fundament-Budget.
        val restHuelle = (huelle - geliefert).coerceAtLeast(0.0)
        val sofortU = if (vorschau.valid) vorschau.phaseAUpfrontU else 0.0
        val verteiltU = if (vorschau.valid) vorschau.phaseARemainderU else restHuelle
        val fundamentU = if (vorschau.valid) vorschau.phaseBBudgetU else 0.0
        // FREMDES INSULIN - eigene Abfrage, und das ist hier KEIN Bruch der
        // Regel oben. Jene verbietet einen zweiten Rechenweg fuer eine
        // Groesse, die der Regler bereits fuehrt. Manuelles Insulin fuehrt er
        // gerade NICHT (Auditbefund P0-2) - es gibt keine erste Wahrheit, an
        // der sich eine zweite reiben koennte.
        //
        // Nur `Type.NORMAL`: SMB ist FUSEs eigener Kanal und steckt schon in
        // `geliefert`, PRIMING ist Schlauchfuellung und trifft keinen Koerper.
        // Fehlschlaegt die Abfrage, bleibt der Wert null = UNBEKANNT und der
        // Dialog behauptet nichts, statt 0,00 U zu zeigen.
        val fremd = runCatching {
            persistenceLayer.getBolusesFromTimeToTime(
                now - MarkerPrompt.FOREIGN_WINDOW_MIN * 60_000L, now, true
            ).filter { it.isValid && it.type == BS.Type.NORMAL }.sumOf { it.amount }
        }.getOrNull()

        val fakten = MarkerPrompt.Facts(
            upfrontPlannedU = sofortU,
            phaseARemainderU = verteiltU,
            phaseBBudgetU = fundamentU,
            foundationEndMin = fundamentEndeMin.takeIf { vorschau.valid && fundamentU > 0.0 },
            envelopeU = huelle,
            alreadyDeliveredU = geliefert,
            authorizesAgainstModel = preferences.get(FuseBooleanKey.MarkerAuthorisesRelease),
            measuredLow = letzter?.state?.safetyHold == true,
            foreignBolusU = fremd,
            // DIESELBE Einstellung, die der Regler liest (FuseCycleRunner
            // holt sie ueber denselben Schluessel) - keine zweite Quelle fuer
            // dieselbe Zahl, sonst laufen Text und Verhalten wieder
            // auseinander.
            windowMin = fensterMin.takeIf { it > 0 },
            // Steht JETZT schon ein Riegel, wird der Sofortanteil
            // aufgeschoben - dann fordert der Zyklus 0 U an. Der Zustand
            // kommt aus dem letzten Zyklus (eine eigene Rechnung waere
            // eine zweite Wahrheit).
            deferredReason = when (letzter?.phaseAUpfrontState) {
                "DEFERRED_UPFRONT_BATCH" -> "Sicherheitsriegel"
                "BLOCKED_ZERO_LATCH"     -> "Null-Basal verriegelt"
                "BLOCKED_FALLBACK"       -> "Modellausfall"
                "BLOCKED_NO_DEFERRED"    -> "Sicherheitsnetz aus"
                "TRANSFERRED_TO_DEFERRED" -> "laeuft schrittweise weiter"
                "BLOCKED_VIEW"           -> "Behandlungssicht unlesbar"
                else                     -> null
            },
            // Aus DEMSELBEN Zyklus wie die uebrigen Zahlen - kein zweiter
            // Rechenweg. Nur wenn die Uhr wirklich knapp wird (Fall 1 des
            // Audit-Nachtrags: die zweite Mahlzeit erbte den Topf der ersten
            // und verlor ihn 55 Minuten spaeter mitten im Anstieg).
            episodeRestMin = letzter?.let { o ->
                val alter = o.evidenceEpisodeMin ?: return@let null
                val deckel = o.evidenceEpisodeCapMin
                (deckel - alter).takeIf { it in 0..MarkerPrompt.EPISODE_WARN_MIN }
            },
        )
        return MarkerPrompt.required(armed = mealMarkerActive(now), facts = fakten)?.let {
            FuseOverviewSource.MarkerPromptFacts(
                // Die Zeilenauswahl kommt aus FUSE - die Bedienoberflaeche
                // uebersetzt sie nur noch.
                lines = MarkerPrompt.lines(it).map { z ->
                    when (z) {
                        is MarkerPrompt.Line.Upfront    -> FuseOverviewSource.MarkerPromptFacts.Line.Upfront(z.amountU)
                        is MarkerPrompt.Line.Spread     -> FuseOverviewSource.MarkerPromptFacts.Line.Spread(z.amountU, z.windowMin)
                        is MarkerPrompt.Line.Foundation -> FuseOverviewSource.MarkerPromptFacts.Line.Foundation(z.amountU, z.untilMin)
                        is MarkerPrompt.Line.Total      -> FuseOverviewSource.MarkerPromptFacts.Line.Total(z.amountU)
                        is MarkerPrompt.Line.Deferred   -> FuseOverviewSource.MarkerPromptFacts.Line.Deferred(z.reason)
                    }
                },
                upfrontPlannedU = it.upfrontPlannedU,
                phaseARemainderU = it.phaseARemainderU,
                phaseBBudgetU = it.phaseBBudgetU,
                foundationEndMin = it.foundationEndMin,
                envelopeU = it.envelopeU,
                alreadyDeliveredU = it.alreadyDeliveredU,
                authorizesAgainstModel = it.authorizesAgainstModel,
                measuredLow = it.measuredLow,
                foreignBolusU = it.foreignBolusU,
                episodeRestMin = it.episodeRestMin,
                windowMin = it.windowMin,
            )
        }
    }

    override fun fuseMarkerToggle(now: Long, ohneVorschuss: Boolean): Boolean = toggleMealMarker(now, ohneVorschuss)

    /** Die im Dialog getroffene Episoden-Wahl - nur mit stehendem Marker wahr. */
    fun mealMarkerNoPrime(now: Long): Boolean =
        mealMarkerActive(now) && preferences.get(FuseLongKey.MealMarkerNoPrime) != 0L

    fun mealMarkerActive(now: Long): Boolean {
        val ts = mealMarkerArmedTs()
        return ts > 0 && now - ts in 0..(app.aaps.fuse.core.controller.OnsetChannel.MARKER_WINDOW_MIN * 60_000L)
    }

    /**
     * KEINE PUMPENABHAENGIGE STARTVERWEIGERUNG MEHR (Auditbefund 10.08.2026).
     *
     * Hier stand ein `specialEnableCondition`-Override, das den Pumpen-Riegel
     * zur Aktivierungsbedingung machte: mit einer nicht erlaubten Pumpe liess
     * FUSE sich gar nicht erst als APS aktivieren. Das klingt sicherer, als es
     * ist - AAPS behandelt "Plugin nicht verfuegbar" naemlich mit einem
     * FALLBACK:
     *
     *   PluginStore.getTheOneEnabledInArray findet kein aktives APS
     *     -> getDefaultPlugin(APS) = OpenAPSSMBPlugin wird eingeschaltet
     *     -> ab da regelt OpenAPS SMB die ECHTE Pumpe, still
     *   und der naechste ConfigBuilder-Vorgang schreibt FUSE dauerhaft als
     *   deaktiviert fort (savePref ueber isEnabled()).
     *
     * Ausloeser genuegte ein Startzustand, in dem der Medtrum-Treiber sein
     * Modell noch nicht geladen hat (deviceType 0 -> MEDTRUM_UNTESTED, ueber
     * eine Coroutine nachgeladen). Aus einer voruebergehenden Unbekanntheit
     * wurde so ein dauerhafter Reglerwechsel, den niemand angeordnet hat.
     *
     * Deshalb: FUSE bleibt AUSGEWAEHLT, und die Pumpenfreigabe liegt
     * ausschliesslich im Laufzeit-Riegel. Bei geschlossenem Riegel gibt FUSE
     * keine positive Aktuation ab (FuseRtBuilder), sagt es im Tab und meldet
     * es mit eigener Dringlichkeit. Ein spaeter doch erkannter Nano wird im
     * naechsten Zyklus von selbst freigegeben - ohne Bedienhandlung.
     *
     * Das Override entfaellt ersatzlos; die Basisklasse liefert `true`.
     */

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        // Scheibe 1: den Ausgang des VORIGEN Zyklus lesen, BEVOR dieser Lauf
        // `publishedRt` ueberschreibt. Reine Messung.
        leseVorigenAusgang()
        // DEN ENTLASTUNGS-BELEG BILDEN, solange die published*-Felder noch den
        // VORIGEN Zyklus beschreiben (gesetzt werden sie erst nach publish).
        // Reine Messung - gebucht wird erst im events-Block, also VOR dem
        // verifizierten Persist: eine Freigabe wirkt nie, bevor sie durabel ist.
        notSentClaim = publishedProposalId
            ?.takeIf { ledgerAdapter.hasOpenProposal(it) }
            ?.let { id ->
                app.aaps.fuse.core.ledger.NotSentProof.reasonFor(
                    app.aaps.fuse.core.ledger.NotSentProof.Observation(
                        correlated = priorActuation?.correlated == true,
                        ledgerPublishedU = ledgerAdapter.publishedAmountOf(id),
                        gateStripped = publishedGateStripped,
                        gateSealed = publishedGateSealed,
                        gatePersistFailed = publishedGatePersistFailed,
                        aapsConstrainedU = priorActuation?.aapsConstrainedU,
                        smbSetByPumpPresent = priorActuation?.smbSetByPumpPresent,
                    )
                )?.let { grund -> id to grund }
            }

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
        val roherSnapshot = FuseActivePump.of(pumpe)


        // Ledger VOR dem Lauf restaurieren - NICHT wie warmGraphRingOnce nach
        // dem Lauf: der Zyklus rechnet mit den restaurierten Commitments und
        // Episodenbudgets, ein nachtraegliches Laden kaeme eine Dosis zu spaet.
        // Davor der einmalige Umzug ins app-private Verzeichnis (Fix 8).
        // FAIL-CLOSED (Fix 1a, REG-03): schlaegt der Umzug fehl, wird NICHT
        // geladen (loadOnce bliebe sonst auf dem leeren Ziel haengen) und der
        // Adapter haelt diesen Lauf wie unter recoveryHold an - kein positiver
        // SMB, solange die Vorgeschichte nicht sicher uebernommen ist. Der
        // naechste invoke versucht den Umzug erneut.
        // TIEF-GEDAECHTNIS VOR DEM LAUF (Vorfall 15.08., s. FuseLowMemory).
        // Hier und nicht bei warmGraphRingOnce: das laeuft NACH dem Zyklus,
        // und der erste Zyklus nach einem Flash ist genau der, in dem der
        // Rebound-Schutz fehlte. Ein Lesefehler bleibt folgenlos - dann gilt
        // wieder der alte Zustand, nie ein erfundener.
        warmLastLowOnce()

        if (migrateLedgerDirOnce()) {
            // ---- REPARATUR: hier und NUR hier ------------------------------
            //
            // Die Stelle ist genau gewaehlt. Davor steht die Verzeichnis-
            // umstellung, damit die Reparatur nicht in ein Verzeichnis greift,
            // das gleich noch Dateien bekommt. Danach kommt `loadOnce` - der
            // frische Adapter laedt also im SELBEN Zyklus die reparierte
            // (leere) Generation, ohne Zwischenzustand.
            //
            // Und vor allem: hier hat noch kein Zyklus dieses Prozesses
            // gerechnet oder geschrieben. Genau daran scheiterte der erste
            // Entwurf, der die Dateien vom UI-Thread aus umraeumte.
            fuehreReparaturAus(roherSnapshot)
            ledgerAdapter.noteMigrationDone()
            // B3: der PUMPENKONTEXT gehoert zum Laden. Die Migration braucht
            // ihn, weil eine v1-Zeile gar keinen Pin hat - ob sie gefahrlos
            // als Altbestand weiterbinden darf, haengt daran, welche Pumpe
            // heute laeuft. Ohne ihn kaeme immer "unbekannt" an, und jede
            // offene Altzeile ginge auch auf der VirtualPump in den Hold:
            // fail-closed, aber unnoetig blockierend.
            runCatching {
                ledgerAdapter.loadOnce(ledgerDir(), sessionId, dateUtil.now(), roherSnapshot) {
                    aapsLogger.error(LTag.APS, it)
                }
            }.onFailure { aapsLogger.error(LTag.APS, "FUSE ledger load failed", it) }
            // ---- HOLD-QUITTUNG: hier und NUR hier ---------------------------
            //
            // NACH `loadOnce`, anders als die Reparatur eine Handvoll Zeilen
            // weiter oben. Der Unterschied ist nicht Geschmack: die Reparatur
            // arbeitet auf DATEIEN und muss vor dem Laden laufen, die Quittung
            // arbeitet auf dem GELADENEN Zustand. Liefe sie davor, traefe sie
            // im ersten Zyklus eines Prozesses einen leeren Zustand, wuerde
            // mangels Zeile mit einem NEUEN `UNKNOWN_PROPOSAL` abgewiesen und
            // von `loadOnce` anschliessend ueberschrieben. Und das ist nicht
            // der Randfall: ein Hold ueberlebt den Neustart, die Quittung wird
            // also typischerweise NACH einem Neustart erteilt.
            fuehreHoldQuittungAus()
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
        // Die Epoche geht IN den Snapshot, statt daneben zu leben: danach
        // gibt es genau EINE Groesse, die den Pumpenzustand dieses Zyklus
        // beschreibt, und niemand kann versehentlich zwei Teile aus
        // verschiedenen Momenten paaren.
        val aktivePumpe = roherSnapshot.withPatchEpoch(
            FusePatchEpochSource.current(persistenceLayer, roherSnapshot, dateUtil.now())
        )
        val patchEpoch = aktivePumpe.patchEpoch!!
        // EIN Kontext aus EINEM Snapshot: Epoche, Emulationsnachweis und
        // Identitaet koennen so gar nicht mehr auseinanderlaufen.
        ledgerAdapter.observeBindingContext(
            app.aaps.fuse.plugin.ledger.LedgerPumpBindingContext(
                virtualPump = aktivePumpe.virtualPump,
                pumpTypeName = aktivePumpe.pumpTypeName,
                serialHash = aktivePumpe.serialHash,
                patchEpochTs = patchEpoch.epochTs,
            )
        )

        // DIE VORHER-KOPIE ENTSTEHT HIER, VOR DEM LAUF - nicht im Outcome.
        //
        // Der Runner setzt den Evidenzbestand auf den Folgestand, damit der
        // Persist ihn mitnimmt. Wirft er DANACH, ist `outcome` null: der
        // veraenderte Zustand steht im Ledger, und die Ruecknahme-Adresse waere
        // mit dem Outcome verlorengegangen (Toni 12.08.). Genau der Pfad, auf
        // dem FUSE am wenigsten weiss, haette dann als einziger keine
        // Ruecknahme gehabt.
        //
        // `EvidenceStock.State` ist eine Datenklasse ohne veraenderliche
        // Felder - die Referenz ist die Kopie.
        val evidenzVorLauf = ledgerAdapter.episodes.evidenceState
        // Bis das Publikationsgate den Persist bestaetigt hat, gilt der im
        // Runner geschriebene Folgestand als UNVERSIEGELT. Wirft irgendetwas
        // zwischen run() und sealCycleState (GraphRing, RT-Bau, commitmentOf),
        // liefe der naechste Zyklus sonst auf Evidenz, die nie auf Platte
        // stand - genau das Loch, das der Ein-Zyklus-Verzug schliessen soll
        // (Audit 15.08., P2). Das finally unten rollt dann zurueck.
        var sealEntschieden = false
        try {

        val outcome = try {
            cycleRunner().run(tempBasalFallback, aktivePumpe)
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
                            // Schwanz-Kante in mg/dl - Regeln s. FuseGraphMargin
                            // (der unphysiologische Ausgang hat KEINEN ISF-Nenner,
                            // sperrt aber; er gehoert auf den unteren Anschlag).
                            tailMarginMgdl = o.decision.tail?.let { t ->
                                FuseGraphMargin.tailMarginMgdl(t.headroomU, t.isfTailMgdlPerU, t.invalidReason)
                            },
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
                gate = aktivePumpe.gate,
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
                methodId = outcome.band?.let { app.aaps.fuse.core.signal.PairSlopeBand.methodId(preferences.get(FuseIntKey.DriveLowerQuantilePct), preferences.get(FuseIntKey.TheilSenWindowMin)) },
                minMeanMgdl = outcome.prediction?.minMeanBg,
                predictorRejected = outcome.predictorRejected,
                predictorReason = outcome.predictorReason,
                markerFallbackUsed = outcome.markerFallbackUsed,
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
            realPumpEpochUnknown = aktivePumpe.realPumpEpochUnknown,
            // Ohne Identitaet entstuende ein Pin, der als Wildcard jeden
            // typgleichen Bolus bindet. An der VirtualPump ist das noetig
            // (leerer Serial nach dem Prozessstart), an einer echten Pumpe
            // ein Freibrief.
            realPumpIdentityUnknown = aktivePumpe.realPumpIdentityUnknown,
        )

        val publication = app.aaps.fuse.plugin.ledger.LedgerPublicationGate.publish(
            rt = rt,
            adapter = ledgerAdapter,
            dir = ledgerDir(),
            expected = expected,
            // WAS DIESER ZYKLUS TATSAECHLICH HINAUSGIBT.
            //
            // Die Menge aus dem RT (nicht aus der Entscheidung): das Gate
            // stempelt, was PUBLIZIERT wird. Und `tbrChanged` aus dem Runner,
            // der als einziger beides kennt - die laufende Sicht und die neue
            // Anforderung. Fehlt ein Outcome (Abbruch vor dem Lauf), ist
            // beides unbekannt und der Stempel zaehlt es als Eingriff; das
            // ist die konservative Richtung.
            published = InterventionStamp.Published(
                smbU = rt.units,
                tbrChanged = outcome?.tbrChanged,
            ),
            events = {
                // ZUERST die Vorgaengerzeile entlasten (s. NotSentProof): auch
                // ein Abbruchzyklus ohne eigenen Vorschlag muss das koennen,
                // deshalb bewusst AUSSERHALB von outcome?.let und VOR der
                // Buchung der neuen Menge.
                notSentClaim?.let { (id, grund) ->
                    if (ledgerAdapter.hasOpenProposal(id)) ledgerAdapter.onProvenNotSent(id, grund)
                    // UND DIE EPISODENZAEHLER MIT (Toni 19.08., P0).
                    //
                    // Die Ledger-Zeile allein reicht nicht: primeSpentU,
                    // mealDeliveries, evidenceCommittedU, onsetSpentU und
                    // deliveredSinceHandoverU stehen daneben und zaehlten
                    // bisher die PUBLIZIERTE Menge - also die vor dem
                    // AAPS-Intervalltor. Am 19.08. hiess das: 3,00 U in der
                    // Buchfuehrung, 2,70 U in der Pumpendatenbank. FUSE hielt
                    // die Huelle fuer geliefert, meldete WINDOW_OVER und holte
                    // die fehlenden 0,30 U nie nach.
                    //
                    // Bewusst NEBEN onProvenNotSent und mit demselben Beweis:
                    // zwei Buecher ueber denselben Vorgang muessen gemeinsam
                    // korrigiert werden, sonst driften sie genau hier
                    // auseinander.
                    //
                    // UND DER UEBERTRAG FUER PHASE B ENTSTEHT DORT MIT, nicht
                    // hier (Toni 19.08.). Hier stuende sonst eine zweite
                    // Entscheidung neben der ersten: dieser Block weiss weder,
                    // ob die Buchung ueberhaupt gefunden wurde, noch in
                    // welcher Phase sie gebucht war - beides steht nur im
                    // Ledger, und beides muss zur zurueckgedrehten Menge
                    // passen. Deshalb liefert `revokeSettled` einen TYPISIERTEN
                    // Ausgang, und der Grund des Beweises spielt fuer die Menge
                    // keine Rolle.
                    val zurueck = ledgerAdapter.revokeSettled(id)
                    if (zurueck.amountU > 0.0) aapsLogger.debug(
                        LTag.APS,
                        "FUSE: Episodenzaehler um ${zurueck.amountU} U zurueckgedreht " +
                            "($grund, $id, Phase ${zurueck.foundationPhase}, " +
                            "Uebertrag jetzt ${ledgerAdapter.episodes.confirmedNotSentPhaseAU} U)",
                    )
                }
                notSentClaim = null
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
                            // AUS DEM SNAPSHOT, nicht aus einer zweiten Lesung
                            // (P0, Codex 10.08.): sonst koennten
                            // Publikationspruefung und Pin verschiedene
                            // Identitaeten verwenden - Snapshot sagt "Serial
                            // bekannt, SMB erlaubt", und der Pin bekommt
                            // Sekunden spaeter einen leeren oder anderen.
                            pumpSerialHash = aktivePumpe.serialHash,
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
        // ---- SEAL_CYCLE_STATE: der Zustand ist versiegelt - oder nicht ----
        //
        // `publish` fuehrt den Persist UNBEDINGT aus, auch ohne Vorschlag,
        // nach einem Wurf und in jedem Abbruchzyklus. Was bisher fehlte, war
        // die KONSEQUENZ auf der Zustandsseite: der Runner hat den
        // Evidenzbestand bereits auf den Folgestand gesetzt, damit der Persist
        // ihn mitnimmt. Ist der gescheitert, darf dieser Folgestand nicht im
        // Speicher weiterleben - sonst rechnete der naechste Zyklus auf
        // Evidenz, die nie auf Platte stand, und der Ein-Zyklus-Verzug waere
        // ausgerechnet im Fehlerfall wirkungslos.
        //
        // Zurueckrollen statt "UNKNOWN markieren": der vorige Stand IST
        // bekannt und belegt. Ihn zu verwerfen waere strenger als noetig und
        // wuerde eine gesicherte Episode wegen eines Schreibfehlers verlieren.
        sealEntschieden = true
        if (ledgerAdapter.sealCycleState(publication.sealed, evidenzVorLauf))
            aapsLogger.error(
                LTag.APS,
                "FUSE SEAL_CYCLE_STATE fehlgeschlagen - Evidenzbestand auf den versiegelten Stand zurueckgerollt",
            )

        val publishRt = publication.rt
        if (!publication.allowed && rt.units != null)
            aapsLogger.error(LTag.APS, "FUSE SMB stripped from published RT: ${publication.reason}")

        // DIE RESERVIERUNG AUFLOESEN (11.08.). Der Runner hat die Episodenbudgets
        // gegen das PUMPEN-Gate belastet, bevor dieses hier gelaufen ist. Erst
        // jetzt steht fest, was wirklich hinausgeht.
        //
        // `publishRt.units` ist die publizierte Menge NACH dem Gate: hat es die
        // Zeile entfernt, steht dort null, und die Reservierung wird freigegeben.
        // Wird dieser Punkt nie erreicht (Ausnahme davor, Prozessende), bleibt
        // die Belastung stehen - der gewollte UNKNOWN-Ausgang.
        // Die cycleId geht MIT: nur ueber sie kann ein Nicht-Sende-Beweis im
        // Folgezyklus genau diese Buchung wiederfinden (s.
        // EpisodeBudgets.Settled).
        outcome?.let { o ->
            ledgerAdapter.resolveReservation(o.computeTs, publishRt.units ?: 0.0, proposalId = cycleId)
        }


        // Fuer die Messung im NAECHSTEN Zyklus merken: die RT-Instanz selbst,
        // nicht ihre Zahlen - sie ist der Identitaetsschluessel.
        publishedRt = publishRt
        publishedTs = outcome?.computeTs ?: 0L
        // Im selben Atemzug: der Zustand, den der NAECHSTE Zyklus als Beleg
        // liest. `stripped` heisst, das Gate hat eine vorhandene Menge
        // entfernt; ohne Siegel gilt der Beschluss als nicht festgeschrieben.
        publishedProposalId = cycleId.takeIf { ledgerAdapter.hasOpenProposal(it) }
        publishedGateStripped = !publication.allowed && rt.units != null
        publishedGateSealed = publication.sealed
        publishedGatePersistFailed = !publication.sealed

        // MEALSTATS NACH der Aufloesung neu rechnen (Review 11.08.). Der Runner
        // hat sie VOR dem Publikationsgate gebildet; wurde die Reservierung
        // gerade zurueckgedreht, zeigte der eingefrorene Stand eine Menge, die
        // es nicht mehr gibt. Das trifft nicht den naechsten Regelzyklus - der
        // liest die Budgets ohnehin frisch -, aber Trail und Schirm GENAU
        // dieses Zyklus, also die Zahlen, an denen die Mahlzeit ausgewertet
        // wird. Dieselbe Funktion wie im Runner, keine zweite Fassung.
        val exportOutcome = outcome?.let { o ->
            o.copy(
                mealStats = FuseCycleRunner.mealStatsOf(
                    ledgerAdapter.episodes,
                    o.state?.markerArmedTs ?: 0L,
                    o.computeTs,
                )
            )
        }

        lastAPSResult = apsResultProvider.get().with(publishRt)
        lastAPSRun = dateUtil.now()
        aapsLogger.debug(LTag.APS, "FUSE result: ${publishRt.reason}")
        rxBus.send(EventAPSCalculationFinished())

        // DER ERWARTUNGS-LEDGER - REIN BEOBACHTEND, UND ERST JETZT.
        //
        // Er stand zunaechst VOR dieser Uebergabe (Toni 18.08.: "Die
        // SMB-Abgabezeit wird davon nicht negativ beeinflusst?"). Das war
        // falsch: er kann die Menge zwar nicht mehr aendern, haette ihre
        // ABGABE aber um die Dauer eines vollstaendigen Schreibvorgangs mit
        // fsync verzoegert - auf Android realistisch 100-300 ms. Ein
        // Beobachter darf nicht zwischen der Entscheidung und ihrer
        // Ausfuehrung stehen.
        //
        // HIER ist er gefahrlos: `lastAPSResult` steht, das Ereignis ist
        // gesendet, AAPS kann aktuieren. Der Erwartungs-Ledger ist vom
        // Publikationsvertrag unabhaengig - den Eingriffsstempel hat das Gate
        // laengst versiegelt, hier werden nur die eigenen Erwartungen
        // fortgeschrieben. Stirbt der Prozess dazwischen, fehlt EINE Messung;
        // kein Nachweis wird falsch, weil die Erwartung dann gar nicht erst
        // existiert.
        //
        // WARUM ER TROTZDEM NACH dem Publikations-Gate stehen muss: erst dort
        // steht der Stempel fest, unter dem die Prognose dieses Zyklus gilt.
        // Beide Bedingungen zusammen ergeben genau dieses Fenster.
        buchereWartung(outcome, publication.sealed)

        // Auch der Schirm bekommt den korrigierten Stand - er zeigt dieselben
        // Mahlzeitenzahlen wie der Trail.
        exportOutcome?.let { lastOutcome = it }

        // NACH den Buchungen dieses Zyklus - der Hold kann genau hier entstanden
        // sein. Davor gemeldet, waere die Meldung eine Generation zu spaet.
        meldeLedgerHold(aktivePumpe)
        meldePumpenRiegel(aktivePumpe)

        exportState(
            exportOutcome, publishRt, cycleId,
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
        } finally {
            // Der EINE Ausgang fuer jeden Wurf zwischen run() und dem
            // Publikationsgate: der Folgestand des Evidenzbestands hat den
            // Persist nie erreicht und darf im Speicher nicht weiterleben.
            // sealCycleState(false, ...) ist idempotent - im Normalfall ist
            // `sealEntschieden` laengst true und hier passiert nichts.
            if (!sealEntschieden && ledgerAdapter.sealCycleState(sealed = false, before = evidenzVorLauf))
                aapsLogger.error(
                    LTag.APS,
                    "FUSE: Ausnahme vor dem Versiegeln - Evidenzbestand auf den versiegelten Stand zurueckgerollt",
                )
        }
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
                ledger = FuseStateJson.LedgerSnapshot(ledgerAdapter.revision, ledgerAdapter.state, ledgerAdapter.lastPersistStats),
                publicationGate = publicationGate,
                // B3: die Diagnose neben dem Sperrgrund. Der Grund steht im
                // Publikationsgate, das WARUM steht hier.
                patchEpoch = patchEpoch,
                // Einmal von der Platte, danach aus dem Speicher: ein
                // Dateizugriff je Zyklus fuer eine Zeile, die sich fast nie
                // aendert, waere Verschwendung - aber weglassen darf man sie
                // nicht, sonst sieht ein reparierter Ledger wie ein
                // unbenutzter aus.
                ledgerReset = letzteReparatur(),
            priorActuation = priorActuation,
            // Der Erwartungs-Ledger. `runCatching`, weil ein Messbaustein den
            // Export eines Regelzyklus nicht kosten darf - fehlt der Block,
            // sagt das genau so viel wie eine Zahl darin.
            expectation = runCatching {
                expectationRecorder.exportSnapshot(
                    nowTs = outcome.computeTs,
                    stamp = ledgerAdapter.interventionStamp,
                    configGeneration = outcome.configGeneration,
                    segmentId = outcome.signal?.signalEpochTs ?: 0L,
                    situation = outcome.expectationSituation?.copy(ledgerSealed = publishedGateSealed),
                    minSafetyMarginMgdl = ExpectationLedger.EXPORT_SAFETY_MARGIN_MGDL,
                )
            }.getOrNull(),
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

    /**
     * Der Ledger-Zustand fuer den Schirm - eine eigene Groesse neben Health.
     *
     * LIEST `view()`, NICHT `state` (Audit-Befund S1, 10.08.2026). Gesperrt
     * wird ueber `LedgerView.hold`, und das ist eine ODER-Verknuepfung aus vier
     * Quellen. `state.holdActuation` ist nur EINE davon; die anderen drei -
     * `persistFailed`, `recoveryHold`, `migrationPending` - stoppten die Abgabe
     * lautlos, waehrend hier "Ledger frei" stand. Ausgerechnet `recoveryHold`
     * ueberlebt einen Neustart und ist der Zustand, fuer den es den
     * Reparaturweg ueberhaupt gibt.
     */
    fun ledgerInfo(): FuseScreenModel.LedgerInfo {
        val s = ledgerAdapter.state
        val v = ledgerAdapter.view()
        val offen = s.openEntries
        return FuseScreenModel.LedgerInfo(
            hold = v.hold,
            holdReason = v.holdReason,
            holdGeneration = s.holdGeneration,
            activeErrors = s.errors.filter { it.active }.groupingBy { it.error.name }.eachCount(),
            openEntries = offen.size,
            grossLiabilityU = offen.sumOf { it.grossLiabilityU },
            transportCommitmentU = v.transportCommitmentU,
            lastRepairTs = letzteReparatur()?.ts,
        )
    }

    /** Profilwert fuer die kompakte Betriebssicht. Die Reglerwerte Ziel und
     * ISF kommen weiter aus dem Outcome; nur das dort nicht enthaltene
     * planmaessige Basal wird aus demselben aktiven Profil gelesen. */
    fun dashboardProfileInfo(now: Long): FuseDashboardModel.ProfileInfo =
        FuseDashboardModel.ProfileInfo(
            scheduledBasalUPerH = runCatching { profileFunction.getProfile(now)?.getBasal(now) }.getOrNull(),
        )

    /**
     * DER LEDGER-HOLD MUSS SICH MELDEN.
     *
     * Er kappt den Kandidaten auf 0,00 U - FUSE rechnet weiter, zeigt
     * insulinReq und Bahn, und gibt nichts ab. Auf dem Schirm stand das bis zum
     * 10.08.2026 nur zwanzig Zeilen tief als `candidate:LEDGER_HOLD`, waehrend
     * die Kopfzeile `Health READY` sagte. Ein Regler, der still aufhoert zu
     * dosieren, ist die gefaehrlichste Sorte Fehler, weil nichts passiert -
     * und genau deshalb faellt er ohne Alarm tagelang nicht auf.
     *
     * EINMAL je Befund, nicht je Zyklus: eine Meldung pro Minute waere nach
     * einer Stunde Tapete, und Tapete liest niemand. Die REGEL dazu steht in
     * [FuseHoldAlarm], wo sie pruefbar ist - beide Fehler, die der Audit hier
     * gefunden hat, lagen genau in dieser ungepruften Ecke.
     */
    private val holdAlarm = FuseHoldAlarm.Zustand()

    /**
     * Der Riegel-Alarm - eigener Kanal, eigener Zustand.
     *
     * Seit die pumpenabhaengige Startverweigerung entfallen ist, bleibt FUSE
     * bei unzulaessiger Pumpe ausgewaehlt und regelt einfach nicht. Ohne
     * Meldung waere das von "FUSE haelt gerade nichts fuer noetig" nicht zu
     * unterscheiden.
     */
    private val gateAlarm = FuseHoldAlarm.Zustand()

    /**
     * DER GESCHLOSSENE RIEGEL MUSS SICH MELDEN.
     *
     * Frueher konnte FUSE mit unzulaessiger Pumpe gar nicht erst aktiviert
     * werden - dafuer wechselte AAPS still auf OpenAPS SMB. Jetzt bleibt FUSE
     * ausgewaehlt und gibt nichts ab; das ist die richtige Richtung, aber nur
     * mit Ansage.
     *
     * Der Schluessel ist der GRUND, nicht eine Generation: derselbe
     * Zustandsautomat wie beim Ledger-Hold, damit ein wechselnder Befund
     * (unbekanntes Modell -> Fremdpumpe) erneut meldet und eine Aufloesung die
     * Meldung zurueckzieht.
     */
    private fun meldePumpenRiegel(pumpe: FuseActivePump) {
        gateAlarm.verarbeite(
            hold = !pumpe.gate.allowed,
            // Die laufende TBR gehoert in den SCHLUESSEL: faengt waehrend
            // eines geschlossenen Riegels eine fremde TBR an zu laufen, ist das
            // ein NEUER Befund und muss sich erneut melden.
            kennung = FuseHoldAlarm.Kennung(
                if (runCatching {
                        FuseAbortTbr.evaluate(processedTbrEbData, profileFunction, dateUtil.now()).request
                    }.getOrNull() != null) 1L else 0L,
                pumpe.gate.verdict.name,
            ),
            ursachen = emptyMap(),
            textBauer = { _, _ ->
                // DIE LAUFENDE TBR GEHOERT IN DIE MELDUNG (Auditbefund 10.08.2026).
                //
                // Bei geschlossenem Riegel filtert FuseRtBuilder ALLE VIER
                // Aktuatorfelder - auch einen Abbruch. Laeuft in dem Moment eine
                // FREMDE positive TBR (Automation, Handeintrag), liefert sie bis
                // zu ihrem Ende weiter Insulin, und FUSE kann sie nicht mehr
                // beenden. "FUSE gibt nichts aus" liest sich dann wie "es
                // passiert nichts" - waehrend zusaetzliches Insulin laeuft.
                //
                // GEGEN eine unbewiesene Pumpe zu aktuieren waere der schlechtere
                // Tausch: das ist genau die Pumpe, der der Riegel nicht traut.
                // Also bleibt die Sperre, und die Meldung sagt, was sie kostet.
                val laufende = runCatching {
                    FuseAbortTbr.evaluate(processedTbrEbData, profileFunction, dateUtil.now())
                }.getOrNull()
                val zusatz = when {
                    laufende?.request != null ->
                        " ACHTUNG: eine laufende erhoehte TBR kann NICHT mehr abgebrochen werden " +
                            "und laeuft bis zu ihrem Ende weiter."

                    laufende?.alarm == true   ->
                        " ACHTUNG: eine laufende Abgabe ist nicht klassifizierbar und wird nicht angetastet."

                    else                      -> ""
                }
                "FUSE gibt keine Pumpenanforderung aus - weder SMB noch TBR: " +
                    "${pumpe.gate.reason}.$zusatz FUSE bleibt ausgewaehlt; sobald eine erlaubte " +
                    "Pumpe aktiv ist, regelt es von selbst weiter."
            },
            melden = { text ->
                runCatching {
                    uiInteraction.replaceNotification(
                        id = Notification.FUSE_PUMP_GATE_BLOCKED, text = text, level = Notification.URGENT,
                    )
                }.onFailure { aapsLogger.error(LTag.APS, "FUSE Riegel-Meldung fehlgeschlagen", it) }.isSuccess
            },
            zuruecknehmen = {
                runCatching { uiInteraction.dismissNotification(Notification.FUSE_PUMP_GATE_BLOCKED) }
                aapsLogger.info(LTag.APS, "FUSE Pumpen-Riegel wieder offen - Meldung zurueckgenommen")
            },
        )
    }

    /**
     * Eine vorgemerkte Hold-Quittung ausfuehren - NACH `loadOnce`.
     *
     * Das Gedaechtnis der Meldung wird nur vergessen, wenn der Hold
     * TATSAECHLICH gefallen ist. Eine Quittung kann abgewiesen werden (veraltete
     * Generation) oder nur einen von mehreren Fehlern loesen; und `view().hold`
     * ist zusammengesetzt - `persistFailed`, `recoveryHold` und
     * `migrationPending` sperren weiter, ganz ohne Fehlerzeile. Wer hier
     * blind vergaesse, naehme eine URGENT-Warnung zurueck, waehrend FUSE
     * weiterhin nichts abgibt.
     */
    private fun fuehreHoldQuittungAus() {
        val ergebnis = runCatching {
            holdQuittung.runIfDue { auftrag ->
                ledgerAdapter.quittiereHold(
                    proposalId = auftrag.proposalId,
                    by = auftrag.by,
                    reason = auftrag.reason,
                    errors = auftrag.errors,
                    expectedHoldGeneration = auftrag.expectedHoldGeneration,
                )
            }
        }.onFailure { aapsLogger.error(LTag.APS, "FUSE Hold-Quittung fehlgeschlagen", it) }.getOrNull()
        if (ergebnis == null) return
        val nochGesperrt = runCatching { ledgerAdapter.view().hold }.getOrDefault(true)
        aapsLogger.warn(
            LTag.APS,
            "FUSE Hold-Quittung ausgefuehrt: Zeile frei=$ergebnis, Sperre steht noch=$nochGesperrt",
        )
        if (!nochGesperrt) holdAlarm.vergessen()
    }

    /**
     * @param pumpe der Zyklus-Snapshot - KEINE zweite Pumpenlesung. Der
     *   Wegweiser im Meldungstext haengt daran, ob die Reparatur an dieser
     *   Pumpe ueberhaupt zulaessig waere.
     */
    private fun meldeLedgerHold(pumpe: FuseActivePump) {
        val s = ledgerAdapter.state
        // `view()` und nicht `state`: die Sperre ist zusammengesetzt (S1).
        val v = ledgerAdapter.view()
        val ursachen = s.errors.filter { it.active }.groupingBy { it.error.name }.eachCount()
        // DER WEGWEISER MUSS STIMMEN. Bis 16.08. nannte der Text pauschal die
        // Reparatur - die auf einer echten Pumpe verweigert wird. Hier ist der
        // einzige Ort, an dem beides bekannt ist: die anliegenden Fehler und
        // (ueber den Zyklus-Snapshot) die Pumpe.
        val quittierbar = runCatching { ledgerAdapter.quittierbareHoldFehler() }.getOrNull().orEmpty()
        val darfReparieren = pumpe.repairAllowed == true
        val ausweg = when {
            quittierbar.isNotEmpty() -> " Ausweg: Einstellungen -> FUSE -> Hold quittieren."
            darfReparieren           -> " Ausweg: Einstellungen -> FUSE -> Ledger reparieren."
            // KEIN Weg im Programm - und das gehoert gesagt statt verschwiegen.
            else                     ->
                " Kein Ausweg ueber die Bedienoberflaeche: die Fehler sind nicht quittierbar und die " +
                    "Reparatur ist an dieser Pumpe gesperrt."
        }
        val a = holdAlarm.verarbeite(
            hold = v.hold,
            kennung = FuseHoldAlarm.Kennung(s.holdGeneration, v.holdReason),
            ursachen = ursachen,
            textBauer = { k, u -> FuseHoldAlarm.rumpf(k, u) + ausweg },
            melden = { text ->
                // ATOMAR ersetzen statt Zuruecknehmen + Melden: die beiden
                // Einzelereignisse laufen ueber ZWEI Rx-Streams ohne
                // Reihenfolgegarantie. Wird das Melden zuerst verarbeitet,
                // scheitert es an der belegten Kennung, und das spaetere
                // Zuruecknehmen raeumt die alte Meldung weg - uebrig bleibt
                // GAR KEINE Warnung.
                runCatching {
                    uiInteraction.replaceNotification(
                        id = Notification.FUSE_LEDGER_HOLD, text = text, level = Notification.URGENT,
                    )
                }.onFailure { aapsLogger.error(LTag.APS, "FUSE Hold-Meldung fehlgeschlagen", it) }.isSuccess
            },
            zuruecknehmen = {
                // Reines Entfernen - hier ist nichts zu ordnen, es folgt kein
                // Hinzufuegen. Ohne dies bliebe die Meldung stehen und belegte
                // die Kennung; der naechste Hold faende sie besetzt (S2).
                runCatching { uiInteraction.dismissNotification(Notification.FUSE_LEDGER_HOLD) }
                aapsLogger.info(LTag.APS, "FUSE Ledger-Hold aufgeloest - Meldung zurueckgenommen")
            },
        )
        if (a is FuseHoldAlarm.Aktion.Melden)
            aapsLogger.warn(LTag.APS, "FUSE LEDGER HOLD ${a.kennung}: ${a.text}")
    }

    /**
     * Einen vorgemerkten Reparaturauftrag ausfuehren - am Zyklusanfang.
     *
     * Der Adapter wird NUR nach [FuseLedgerRepair.Result.Done] ersetzt. Nach
     * einer Verweigerung bleibt alles wie es war, inklusive Hold: die Lage
     * kann sich zwischen Zustimmung und Ausfuehrung geaendert haben, und dann
     * ist "nichts tun" die richtige Antwort, nicht "trotzdem tauschen".
     */
    private fun fuehreReparaturAus(pumpe: FuseActivePump) {
        val r = runCatching { reparaturAuftrag.runIfDue(
                ledgerDir(), dateUtil.now(),
                // NACHWEIS, nicht Abwesenheit des Gegenteils: `realPump`
                // ist auch bei unbekannter, untested- und Fremdpumpe false
                // und haette die Reparatur dort erlaubt (P0).
                provenVirtualPump = pumpe.repairAllowed,
            ) }
            .getOrElse {
                aapsLogger.error(LTag.APS, "FUSE Reparatur warf - Hold bleibt", it)
                null
            } ?: return
        when (r) {
            is app.aaps.fuse.plugin.ledger.FuseLedgerRepair.Result.Done   -> {
                // NEUBAU statt Ausraeumen: ein frisches Objekt hat jedes Feld
                // auf seinem Anfangswert, per Konstruktion. Ein Hand-Reset
                // waere die Sorte Aufzaehlung, bei der ein Feld vergessen wird
                // - und das vergessene waere hier der gehaltene Zustand.
                //
                // Der Runner faellt mit, weil er den Adapter bei seiner
                // Konstruktion eingefangen hat; ein alter Runner haette weiter
                // auf den alten Ledger gebucht.
                ledgerAdapter = app.aaps.fuse.plugin.ledger.FuseLedgerAdapter()
                runner = null
                // Der Befund ist weg - die alte Meldung darf nicht stehen
                // bleiben und die Kennung belegen (S2).
                holdAlarm.vergessen()
                runCatching { uiInteraction.dismissNotification(Notification.FUSE_LEDGER_HOLD) }
                reparaturGelesen = false
                aapsLogger.warn(
                    LTag.APS,
                    "FUSE LEDGER REPARIERT (${r.record.reason}): verworfen " +
                        "${r.record.discarded.grossLiabilityU} U brutto / " +
                        "${r.record.discarded.openEntries} offene Zeilen, " +
                        "Quarantaene ${r.quarantined.joinToString()}, " +
                        "frische Generation=${r.freshLedgerWritten}"
                )
                // Bei `false` liegt keine lesbare Generation mehr - der
                // Sentinel macht daraus gleich einen Verlust-Hold. Das ist
                // fail-closed und richtig, muss aber im Log stehen, sonst
                // wundert sich jemand ueber einen Hold direkt nach der
                // Reparatur.
                if (!r.freshLedgerWritten) aapsLogger.error(
                    LTag.APS,
                    "FUSE: leere Nachfolgegeneration NICHT geschrieben - der naechste Start haelt wieder"
                )
            }

            is app.aaps.fuse.plugin.ledger.FuseLedgerRepair.Result.Refused -> {
                aapsLogger.warn(LTag.APS, "FUSE Reparatur abgelehnt: ${r.why} - Zustand unveraendert")
                runCatching {
                    uiInteraction.addNotification(
                        // EIGENE Kennung: im Hold-Slot haette diese
                        // NORMAL-Meldung die naechste URGENT-Warnung
                        // verschluckt und den Hold unsichtbar gemacht (S2).
                        id = Notification.FUSE_REPAIR_REFUSED,
                        text = "FUSE-Reparatur nicht ausgefuehrt: ${r.why}. Der bisherige Zustand bleibt unveraendert.",
                        level = Notification.NORMAL,
                    )
                }
            }
        }
    }

    private fun letzteReparatur(): app.aaps.fuse.plugin.ledger.FuseLedgerRepair.ResetRecord? {
        if (!reparaturGelesen) {
            reparaturCache = runCatching {
                app.aaps.fuse.plugin.ledger.FuseLedgerRepair.lastReset(ledgerDir())
            }.getOrNull()
            reparaturGelesen = true
        }
        return reparaturCache
    }

    /**
     * DIE REPARATUR-BEDIENHANDLUNG.
     *
     * Sie steht hier und nicht im Reparaturweg selbst, weil nur das Plugin die
     * beiden Dinge tun kann, die danach noetig sind: den Adapter ERSETZEN und
     * den zwischengespeicherten Zyklus-Runner fallenlassen. Beides durch
     * Neubau, nicht durch Ausraeumen - ein neues Objekt hat jedes Feld auf
     * seinem Anfangswert, und niemand muss eine Liste pflegen.
     *
     * Der Dialog zeigt VORHER, was verworfen wird. "Wirklich?" allein waere
     * keine Zustimmung zu einer Menge Haftung.
     */
    private fun ledgerReparaturDialog(context: Context) {
        val repair = app.aaps.fuse.plugin.ledger.FuseLedgerRepair
        val dir = ledgerDir()
        val lage = runCatching { repair.inspect(dir) }.getOrNull()
        if (lage == null || !lage.repairable) {
            hinweis(
                context, "Ledger-Reparatur",
                "Nicht noetig: ${lage?.why ?: "Lage nicht feststellbar"}.\n\n" +
                    "Dieser Weg ist kein 'Ledger leeren' fuer den Alltag - er oeffnet nur einen " +
                    "Hold, aus dem es sonst keinen Ausgang gibt."
            )
            return
        }
        val d = lage.discarded
        fun u(v: Double?) = v?.let { "%.2f U".format(it) } ?: "unbekannt"
        val was = if (!d.stateReadable) "Der bisherige Ledger ist NICHT LESBAR - was offen war, laesst sich nicht beziffern."
        else buildString {
            append("Verworfen wird:\n")
            append("  offene Zeilen      ${d.openEntries ?: "unbekannt"}\n")
            append("  Bruttohaftung      ${u(d.grossLiabilityU)}\n")
            append("  Transportmenge     ${u(d.transportCommitmentU)}\n")
            append("  aktive Fehler      ")
            append(if (d.activeErrors.isEmpty()) "keine" else d.activeErrors.entries.joinToString { "${it.key}x${it.value}" })
        }
        app.aaps.core.ui.dialogs.AlertDialogHelper.Builder(context)
            .setCustomTitle(app.aaps.core.ui.dialogs.AlertDialogHelper.buildCustomTitle(context, "Ledger reparieren?"))
            .setMessage(
                "Grund: ${lage.why}\n\n$was\n\n" +
                    "Der bisherige Ledger wird NICHT geloescht, sondern in Quarantaene gelegt " +
                    "(.reset.<Zeitstempel>). Die Reparatur wird dauerhaft protokolliert und erscheint " +
                    "danach in jedem Export.\n\n" +
                    "NUR ausfuehren, wenn die offene Haftung nachweislich gegenstandslos ist."
            )
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Vormerken") { _, _ ->
                // HIER WIRD NICHTS AUSGEFUEHRT - nur vorgemerkt.
                //
                // Dieser Zweig laeuft auf dem UI-Thread, waehrend ein Zyklus
                // rechnen kann. Wuerde hier `perform()` laufen, raeumte es die
                // DATEIEN um (Hold-Marker, Quarantaene, neue Generation) -
                // und der laufende Zyklus schriebe danach seinen alten
                // In-Memory-Ledger mit `persistVerified()` genau dorthin
                // zurueck. Die Reparatur waere ueberschrieben und die
                // Fehlerzeilen WIEDERBELEBT; der naechste Zyklus haelt erneut.
                //
                // Es genuegt also nicht, den Objekttausch zu verzoegern: die
                // Dateireparatur gehoert an dieselbe Grenze.
                //
                // Der GRUND wandert im Auftrag mit. Ihn spaeter neu zu bilden
                // waere ein anderes Protokoll als die erteilte Zustimmung.
                val angenommen = reparaturAuftrag.request(
                    app.aaps.fuse.plugin.ledger.FuseLedgerRepair.RepairRequest(
                        by = "Bediener (FUSE-Einstellungen)",
                        reason = lage.why,
                    )
                )
                val text =
                    if (!angenommen) "Es steht bereits eine Reparatur aus - sie wird beim naechsten Zyklus ausgefuehrt."
                    else "Vorgemerkt. Ausgefuehrt wird sie zu Beginn des NAECHSTEN Zyklus (bis zu eine Minute) - " +
                        "bis dahin zeigt der Tab noch den Hold.\n\n" +
                        "Das ist Absicht: mitten in einem laufenden Zyklus an den Ledgerdateien zu " +
                        "arbeiten koennte den alten Zustand wieder herstellen.\n\n" +
                        "Die Lage wird vor der Ausfuehrung ERNEUT geprueft; hat sie sich inzwischen " +
                        "geaendert, passiert nichts."
                hinweis(context, "Ledger-Reparatur", text)
            }
            .show()
    }

    /**
     * DIE HOLD-QUITTUNG - der zweite Ausgang, neben der Reparatur.
     *
     * Er ist der SANFTE: er nimmt einer benannten Zeile ihre Fehler, statt eine
     * frische Generation zu schreiben. Haftung, Prime-Huelle, Mahlzeitenhistorie
     * und der Genau-einmal-Riegel bleiben unangetastet - genau die Dinge, die
     * die Reparatur verwirft und derentwegen sie an einer echten Pumpe
     * verweigert wird. Deshalb darf die Quittung dort laufen.
     *
     * ANGEBOTEN WIRD NUR, WAS WIRKLICH QUITTIERBAR IST (s.
     * [app.aaps.fuse.plugin.ledger.FuseLedgerAdapter.quittierbareHoldFehler]).
     * Eine Quittung auf eine Zeile, die es nicht gibt, erzeugt einen NEUEN
     * fail-closed-Fehler und erhoeht die Generation - der Bedienfehler machte
     * die Lage schlechter statt besser.
     */
    private fun holdQuittungDialog(context: Context) {
        val v = runCatching { ledgerAdapter.view() }.getOrNull()
        val offen = runCatching { ledgerAdapter.quittierbareHoldFehler() }.getOrNull().orEmpty()
        if (v?.hold != true) {
            hinweis(context, "Hold quittieren", "Es steht kein Ledger-Hold an - nichts zu quittieren.")
            return
        }
        if (offen.isEmpty()) {
            // WICHTIG ehrlich zu sein: die Sperre kann aus Gruenden kommen, die
            // gar keine Fehlerzeile haben (persistFailed, recoveryHold,
            // migrationPending) - dann ist die Quittung strukturell kein Weg.
            hinweis(
                context, "Hold quittieren",
                "Grund: ${v.holdReason ?: "unbekannt"}\n\n" +
                    "Zu diesem Hold gibt es KEINEN quittierbaren Fehler. Entweder sperrt ein " +
                    "Grund ohne Fehlerzeile (Persistenz, Wiederherstellung, Migration), oder die " +
                    "aktiven Fehler sind harte Widersprueche - die brauchen eine Reparatur, " +
                    "keine Unterschrift."
            )
            return
        }
        // Alle quittierbaren Fehler EINER Zeile: der Reducer loest den Latch
        // nur, wenn danach kein aktiver Fehler dieser Zeile mehr steht - eine
        // Teilquittung sieht aus wie Wirkungslosigkeit.
        val zeile = offen.first().proposalId!!
        val fehlerDerZeile = offen.filter { it.proposalId == zeile }
        val arten = fehlerDerZeile.map { it.error }.toSet()
        val gen = runCatching { ledgerAdapter.holdGeneration }.getOrDefault(-1L)
        val liste = fehlerDerZeile.joinToString("\n") { "  ${it.error.name}: ${it.lastDetail} (${it.occurrences}x)" }
        app.aaps.core.ui.dialogs.AlertDialogHelper.Builder(context)
            .setCustomTitle(app.aaps.core.ui.dialogs.AlertDialogHelper.buildCustomTitle(context, "Hold quittieren?"))
            .setMessage(
                "Grund der Sperre: ${v.holdReason ?: "unbekannt"}\n\n" +
                    "Quittiert werden diese Fehler der Zeile $zeile:\n$liste\n\n" +
                    "Die Quittung nimmt der Zeile ihre Fehler. Haftung, Mahlzeiten-Huelle und der " +
                    "Genau-einmal-Riegel bleiben erhalten - anders als bei der Reparatur.\n\n" +
                    "Sie wird dauerhaft protokolliert (mit Grund) und erscheint in jedem Export.\n\n" +
                    "NUR quittieren, wenn der Fehler nachweislich gegenstandslos ist - etwa weil " +
                    "eine geloeschte Behandlung ihn ausgeloest hat."
            )
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Vormerken") { _, _ ->
                val angenommen = holdQuittung.request(
                    app.aaps.fuse.plugin.ledger.FuseHoldQuittungScheduler.Auftrag(
                        proposalId = zeile,
                        by = "Bediener (FUSE-Einstellungen)",
                        reason = v.holdReason ?: "quittiert",
                        errors = arten,
                        // Der Stand JETZT - hat er sich bis zur Ausfuehrung
                        // geaendert, weist der Reducer die Quittung ab. Der
                        // Bediener hat dann eine andere Lage gesehen als die,
                        // die gilt.
                        expectedHoldGeneration = gen,
                    )
                )
                hinweis(
                    context, "Hold quittieren",
                    if (!angenommen) "Es steht bereits eine Quittung aus - sie wird beim naechsten Zyklus ausgefuehrt."
                    else "Vorgemerkt. Ausgefuehrt wird sie zu Beginn des NAECHSTEN Zyklus (bis zu eine Minute).\n\n" +
                        "Das ist Absicht: mitten in einem laufenden Zyklus am Ledger zu arbeiten wuerde " +
                        "die Quittung spurlos verlieren.\n\n" +
                        "Aendert sich die Lage bis dahin, wird sie abgewiesen und der Hold bleibt."
                )
            }
            .show()
    }

    /**
     * Ein Hinweisfenster im APP-THEMA.
     *
     * NICHT `android.app.AlertDialog`: der Plattform-Dialog erbt das Thema des
     * uebergebenen Contexts nicht so, wie man erwartet - im dunklen
     * AAPS-Einstellungsbildschirm kam ein WEISSES Fenster mit weisser Schrift
     * heraus, also ein leerer Kasten (Toni, 10.08. am Geraet). Der Dialog war
     * da, man konnte ihn nur nicht lesen.
     *
     * [AlertDialogHelper] legt genau dafuer einen `ContextThemeWrapper` unter
     * den Material-Builder; den benutzt AAPS ueberall sonst auch.
     */
    private fun hinweis(context: Context, titel: String, text: String) {
        app.aaps.core.ui.dialogs.AlertDialogHelper.Builder(context)
            .setCustomTitle(app.aaps.core.ui.dialogs.AlertDialogHelper.buildCustomTitle(context, titel))
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
            // Der Wiedereinstieg nach Funkluecke wird MIT PARAMETERN gebaut
            // und vom Schalter [FuseBooleanKey.SignalRejoinEnabled] je Zyklus
            // torgesteuert. Waere die Politik hier OFF, taete der Schalter
            // nichts; waere sie ohne Schalter scharf, gaebe es keinen
            // Rueckweg ohne Neubau. Beides ist Absicht so getrennt.
            rejoinPolicy = app.aaps.fuse.core.signal.RejoinPolicy.enabled(),
            markerPressObserved = { markerPressObservedTs },
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
            .put(FuseDoubleKey.PositiveDescentHorizonMin, preferences)
            .put(FuseIntKey.IobThPercent, preferences)
            .put(FuseIntKey.ReleaseHorizonMin, preferences)
            .put(FuseIntKey.LiabilityHorizonMin, preferences)
            .put(FuseIntKey.DriveTauMin, preferences)
            .put(FuseIntKey.EvidenceReboundOverrideMaxMin, preferences)
            .put(FuseIntKey.DriveLowerQuantilePct, preferences)
            .put(FuseIntKey.TheilSenWindowMin, preferences)
            .put(FuseIntKey.ReboundWindowMin, preferences)
            .put(FuseBooleanKey.TailGuardEnabled, preferences)
            .put(FuseBooleanKey.ConditionalTailEnabled, preferences)
            .put(FuseBooleanKey.MarkerAuthorisesRelease, preferences)
            .put(FuseBooleanKey.FastRestraintEnabled, preferences)
            .put(FuseDoubleKey.TailFloorMgdl, preferences)
            .put(FuseDoubleKey.TailRecoveryU, preferences)
            .put(FuseDoubleKey.BolusShareLambda, preferences)
            .put(FuseDoubleKey.OnsetEnvelopeU, preferences)
            .put(FuseBooleanKey.OnsetChannelEnabled, preferences)
            .put(FuseBooleanKey.PrimeReleaseEnabled, preferences)
            .put(FuseDoubleKey.PrimeEnvelopeU, preferences)
            .put(FuseBooleanKey.MealFoundationEnabled, preferences)
            .put(FuseDoubleKey.MealFoundationPhaseAShare, preferences)
            .put(FuseDoubleKey.MealFoundationPhaseAUpfrontShare, preferences)
            .put(FuseIntKey.MealFoundationEndMin, preferences)
            .put(FuseBooleanKey.DeferredPrimeEnabled, preferences)
            .put(FuseDoubleKey.MarkerPrimeDescentHorizonMin, preferences)
            .put(FuseIntKey.DeferredPrimeEndMin, preferences)
            .put(FuseBooleanKey.CalmRecoveryEnabled, preferences)
            .put(FuseIntKey.CalmRecoveryCycles, preferences)
            .put(FuseIntKey.CalmTreatmentMode, preferences)
            .put(FuseDoubleKey.CalmRecoveryMinUkf, preferences)
            .put(FuseDoubleKey.CalmRecoveryGuardDistanceMgdl, preferences)
            .put(FuseBooleanKey.LivenessChannelEnabled, preferences)
            .put(FuseBooleanKey.SignalRejoinEnabled, preferences)
            .put(FuseBooleanKey.ForecastShadowCollectionEnabled, preferences)
            .put(FuseDoubleKey.LivenessIobCapPercent, preferences)
            .put(FuseDoubleKey.LivenessRatioCap, preferences)
            .put(FuseIntKey.LivenessMealPowerMin, preferences)
            .put(FuseDoubleKey.LivenessMealRatioCap, preferences)
            .put(FuseDoubleKey.LivenessMealIobCapPercent, preferences)
            .put(FuseDoubleKey.LivenessCorrectionRatioCap, preferences)
            .put(FuseDoubleKey.LivenessCorrectionIobCapPercent, preferences)
            .put(FuseIntKey.MealArmCycles, preferences)
            .put(FuseBooleanKey.CentralProfilesEnabled, preferences)
            // A5-Abschluss: die vier Kandidaten NUR sichern, wenn sie
            // wirklich gesetzt sind - das generische put laese den
            // Bildschirm-Default und ein Restore machte aus
            // "unkonfiguriert" stillschweigend gesetzte 5/5/1/1.
            .also { json -> FuseCentralProfileBackup.schreibe(json, preferences) }
            .put(FuseBooleanKey.ZeroLatchEnabled, preferences)
            .put(FuseIntKey.ZeroLatchCalmExitMin, preferences)
            .put(FuseDoubleKey.ZeroLatchCalmDistanceMgdl, preferences)
            .put(FuseBooleanKey.CorrectionReversalGuardEnabled, preferences)
            .put(FuseDoubleKey.ReversalFallUkf, preferences)
            .put(FuseIntKey.ReversalLookbackMin, preferences)
            .put(FuseDoubleKey.ReversalReboundUkf, preferences)
            .put(FuseIntKey.ReversalConfirmCycles, preferences)
            .put(FuseBooleanKey.PositiveCorrectionRearmEnabled, preferences)
            .put(FuseIntKey.RearmHoldMin, preferences)
            .put(FuseIntKey.RearmConfirmCycles, preferences)
            .put(FuseDoubleKey.RearmUpUkf, preferences)
            .put(FuseDoubleKey.LivenessBgMinDayMgdl, preferences)
            .put(FuseDoubleKey.LivenessBgMinNightMgdl, preferences)
            .put(FuseIntKey.LivenessReArmMin, preferences)
            .put(FuseIntKey.PrimeWindowMin, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(FuseDoubleKey.SmbRatio, preferences)
            .store(FuseDoubleKey.SmbRatioRise, preferences)
            .store(FuseDoubleKey.RiseRampLowR, preferences)
            .store(FuseDoubleKey.RiseRampHighR, preferences)
            .store(FuseDoubleKey.MaxSmbU, preferences)
            .store(FuseDoubleKey.GuardFloorMgdl, preferences)
            .store(FuseDoubleKey.PositiveDescentHorizonMin, preferences)
            .store(FuseIntKey.IobThPercent, preferences)
            .store(FuseIntKey.ReleaseHorizonMin, preferences)
            .store(FuseIntKey.LiabilityHorizonMin, preferences)
            .store(FuseIntKey.DriveTauMin, preferences)
            .store(FuseIntKey.EvidenceReboundOverrideMaxMin, preferences)
            .store(FuseIntKey.DriveLowerQuantilePct, preferences)
            .store(FuseIntKey.TheilSenWindowMin, preferences)
            .store(FuseIntKey.ReboundWindowMin, preferences)
            .store(FuseBooleanKey.TailGuardEnabled, preferences)
            .store(FuseBooleanKey.ConditionalTailEnabled, preferences)
            .store(FuseBooleanKey.MarkerAuthorisesRelease, preferences)
            .store(FuseBooleanKey.FastRestraintEnabled, preferences)
            .store(FuseDoubleKey.TailFloorMgdl, preferences)
            .store(FuseDoubleKey.TailRecoveryU, preferences)
            .store(FuseDoubleKey.BolusShareLambda, preferences)
            .store(FuseDoubleKey.OnsetEnvelopeU, preferences)
            .store(FuseBooleanKey.OnsetChannelEnabled, preferences)
            .store(FuseBooleanKey.PrimeReleaseEnabled, preferences)
            .store(FuseDoubleKey.PrimeEnvelopeU, preferences)
            .store(FuseBooleanKey.MealFoundationEnabled, preferences)
            .store(FuseDoubleKey.MealFoundationPhaseAShare, preferences)
            .store(FuseDoubleKey.MealFoundationPhaseAUpfrontShare, preferences)
            .store(FuseIntKey.MealFoundationEndMin, preferences)
            .store(FuseBooleanKey.DeferredPrimeEnabled, preferences)
            .store(FuseDoubleKey.MarkerPrimeDescentHorizonMin, preferences)
            .store(FuseIntKey.DeferredPrimeEndMin, preferences)
            .store(FuseBooleanKey.CalmRecoveryEnabled, preferences)
            .store(FuseIntKey.CalmRecoveryCycles, preferences)
            .store(FuseIntKey.CalmTreatmentMode, preferences)
            .store(FuseDoubleKey.CalmRecoveryMinUkf, preferences)
            .store(FuseDoubleKey.CalmRecoveryGuardDistanceMgdl, preferences)
            .store(FuseBooleanKey.LivenessChannelEnabled, preferences)
            .store(FuseBooleanKey.SignalRejoinEnabled, preferences)
            .store(FuseBooleanKey.ForecastShadowCollectionEnabled, preferences)
            .store(FuseDoubleKey.LivenessIobCapPercent, preferences)
            .store(FuseDoubleKey.LivenessRatioCap, preferences)
            .store(FuseIntKey.LivenessMealPowerMin, preferences)
            .store(FuseDoubleKey.LivenessMealRatioCap, preferences)
            .store(FuseDoubleKey.LivenessMealIobCapPercent, preferences)
            .store(FuseDoubleKey.LivenessCorrectionRatioCap, preferences)
            .store(FuseDoubleKey.LivenessCorrectionIobCapPercent, preferences)
            // MealArmCycles laeuft im Helper mit (Alt-Backup ohne den
            // Schluessel stellt den neutralen Altwert 3 wieder her).
            // A5-Abschluss: fehlender policyMode im Backup fuehrt SICHER
            // zu LEGACY, fehlende Kandidaten werden ENTFERNT statt auf den
            // Default gesetzt - "unkonfiguriert" ueberlebt den Rundlauf.
            .also { json -> FuseCentralProfileBackup.lese(json, preferences) }
            .store(FuseBooleanKey.ZeroLatchEnabled, preferences)
            .store(FuseIntKey.ZeroLatchCalmExitMin, preferences)
            .store(FuseDoubleKey.ZeroLatchCalmDistanceMgdl, preferences)
            .store(FuseBooleanKey.CorrectionReversalGuardEnabled, preferences)
            .store(FuseDoubleKey.ReversalFallUkf, preferences)
            .store(FuseIntKey.ReversalLookbackMin, preferences)
            .store(FuseDoubleKey.ReversalReboundUkf, preferences)
            .store(FuseIntKey.ReversalConfirmCycles, preferences)
            .store(FuseBooleanKey.PositiveCorrectionRearmEnabled, preferences)
            .store(FuseIntKey.RearmHoldMin, preferences)
            .store(FuseIntKey.RearmConfirmCycles, preferences)
            .store(FuseDoubleKey.RearmUpUkf, preferences)
            .store(FuseDoubleKey.LivenessBgMinDayMgdl, preferences)
            .store(FuseDoubleKey.LivenessBgMinNightMgdl, preferences)
            .store(FuseIntKey.LivenessReArmMin, preferences)
            .store(FuseIntKey.PrimeWindowMin, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        // Overview-Muster: der Aufruf kommt auch fuer Unterbildschirme -
        // dann bauen wir dieselbe Struktur und das Framework zieht den
        // passenden Sub-Screen heraus.
        //
        // DIE SCHLUESSELMENGE WIRD ABGELEITET, NICHT GEPFLEGT.
        //
        // Hier stand eine handgeschriebene Liste neben den `cat()`-Aufrufen.
        // Beim Ergaenzen der Reparatur-Kategorie habe ich sie nicht mitgezogen:
        // die Wache brach fuer `fuse_repair` ab, es wurde nichts gebaut, und
        // der Unterbildschirm kam SCHWARZ (Toni, 10.08. am Geraet). Kein
        // Absturz, keine Meldung - genau die Sorte Fehler, die zwei Listen
        // erzeugen, sobald eine von beiden vergessen wird.
        //
        // Deshalb registrieren die Abschnitte sich jetzt selbst, und die Wache
        // fragt DIESE Registrierung. Der naechste Abschnitt kann nicht mehr
        // vergessen werden, weil es nichts mehr zu vergessen gibt.
        val abschnitte = LinkedHashMap<String, Pair<String, PreferenceScreen.() -> Unit>>()
        fun cat(key: String, titleText: String, block: PreferenceScreen.() -> Unit) {
            require(abschnitte.put(key, titleText to block) == null) { "doppelter Abschnitt: $key" }
        }

        // GRUPPIERT statt flach (Toni 08.08., GPT-Review bestaetigt): die
        // wichtigste Aenderung ist das GETEILTE ApsSmbMaxIob - FUSE nutzt es
        // seit dem MAX_VALUE-Fund als Deckel, zeigte es aber nirgends. KEIN
        // eigener FUSE-Key: eine Sicherheitsgrenze, eine Zahl.
        // ECHTE Unterbildschirme statt aufgeklappter Kategorien (Toni 08.08.:
        // 27 Eintraege am Stueck = Scroll-Wueste; die Klappzeile der Lib ist
        // eine Einbahnstrasse). Oben fuenf Zeilen, "zuklappen" = Zurueck.
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

        // FUENF EINSTIEGSPUNKTE statt elf (Toni 15.08.: "unuebersichtlich und
        // ueberfuellt ... max 4-5"). Die Buendel folgen der Frage, mit der man
        // den Schirm betritt: "Mahlzeit einstellen", "wie scharf dosiert es",
        // "was schuetzt mich", "was gilt nachts", "was ist das System".
        // INNERHALB einer Kategorie trennen info()-Zeilen die Sinnabschnitte -
        // eine Zwischenueberschrift kostet keine Navigationsebene.
        cat("fuse_meal", "Mahlzeit und Marker") {
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.OnsetChannelEnabled, summary = R.string.fuse_onset_channel_summary, title = R.string.fuse_onset_channel_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.OnsetEnvelopeU, dialogMessage = R.string.fuse_onset_envelope_summary, title = R.string.fuse_onset_envelope_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.PrimeReleaseEnabled, summary = R.string.fuse_prime_release_summary, title = R.string.fuse_prime_release_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.PrimeEnvelopeU, dialogMessage = R.string.fuse_prime_envelope_summary, title = R.string.fuse_prime_envelope_title))
            // Direkt unter der Huelle, weil beide nur ZUSAMMEN einen Sinn
            // ergeben: 4 U in 10 Minuten sind etwas anderes als 4 U in 30.
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.PrimeWindowMin, dialogMessage = R.string.fuse_prime_window_summary, title = R.string.fuse_prime_window_title))
            // DAS MAHLZEITENFUNDAMENT, direkt unter Huelle und Fenster: es
            // verteilt genau diese Huelle zeitlich. Drei Zeilen, weil alle
            // drei die Dosierung bestimmen - OB verteilt wird, WIE und BIS
            // WANN. Ein Schalter allein saehe harmlos aus.
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.MealFoundationEnabled, summary = R.string.fuse_meal_foundation_summary, title = R.string.fuse_meal_foundation_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.MealFoundationPhaseAShare, dialogMessage = R.string.fuse_meal_foundation_share_summary, title = R.string.fuse_meal_foundation_share_title))
            // Der Sofortanteil (iLet) direkt hinter dem A-Anteil: er verteilt
            // dieselbe Phase-A-Menge, nur frueher. Default 0,00 = bitgleich.
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.MealFoundationPhaseAUpfrontShare, dialogMessage = R.string.fuse_meal_foundation_upfront_summary, title = R.string.fuse_meal_foundation_upfront_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.MealFoundationEndMin, dialogMessage = R.string.fuse_meal_foundation_window_summary, title = R.string.fuse_meal_foundation_window_title))
            // DER MARKER-PRIME-AUFSCHUB direkt unter dem Fundament: er
            // arbeitet auf derselben gepinnten Huelle. Drei Zeilen aus
            // demselben Grund wie dort - OB aufgeschoben wird, mit welchem
            // Horizont und bis wann. Default AUS (Bau-GO 22.08., kein
            // Aktivierungs-GO).
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.DeferredPrimeEnabled, summary = R.string.fuse_deferred_prime_summary, title = R.string.fuse_deferred_prime_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.MarkerPrimeDescentHorizonMin, dialogMessage = R.string.fuse_marker_prime_horizon_summary, title = R.string.fuse_marker_prime_horizon_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.DeferredPrimeEndMin, dialogMessage = R.string.fuse_deferred_prime_end_summary, title = R.string.fuse_deferred_prime_end_title))
            // DER RUHE-AUSGANG AUS PHASE A. Default AUS - dosierwirksam nur
            // im Modus 2 (CALM_BATCH); die Schwellen sind noch nicht an
            // Kontrollverlaeufen kalibriert.
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.CalmRecoveryEnabled, summary = R.string.fuse_calm_recovery_summary, title = R.string.fuse_calm_recovery_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.CalmRecoveryCycles, dialogMessage = R.string.fuse_calm_cycles_summary, title = R.string.fuse_calm_cycles_title))
            // BESCHRIFTETE AUSWAHL statt freiem Zahlenfeld: eine "2" ohne
            // Beschriftung ist die dosierwirksame Stellung, und das darf
            // niemand aus einer Zahl erraten muessen.
            addPreference(
                AdaptiveListIntPreference(
                    ctx = context, intKey = FuseIntKey.CalmTreatmentMode,
                    title = R.string.fuse_calm_mode_title,
                    entries = arrayOf<CharSequence>(
                        rh.gs(R.string.fuse_calm_mode_demand),
                        rh.gs(R.string.fuse_calm_mode_shift),
                        rh.gs(R.string.fuse_calm_mode_batch),
                    ),
                    entryValues = arrayOf<CharSequence>("0", "1", "2"),
                ),
            )
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.CalmRecoveryMinUkf, dialogMessage = R.string.fuse_calm_min_ukf_summary, title = R.string.fuse_calm_min_ukf_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.CalmRecoveryGuardDistanceMgdl, dialogMessage = R.string.fuse_calm_guard_summary, title = R.string.fuse_calm_guard_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.AbsorptionCreditWindowMin, dialogMessage = R.string.fuse_absorption_credit_summary, title = R.string.fuse_absorption_credit_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.MarkerBoostMaxMin, dialogMessage = R.string.fuse_marker_boost_summary, title = R.string.fuse_marker_boost_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.EvidenceReboundOverrideMaxMin, dialogMessage = R.string.fuse_evidence_rebound_override_summary, title = R.string.fuse_evidence_rebound_override_title))
            // Die manuelle Autorisierung bleibt VOM REST ABGESETZT (Toni
            // 11.08.: der einzige Schalter, der eine SCHUTZgrenze aufhebt,
            // darf nicht wie einer von vielen aussehen). Frueher war das ein
            // eigener Einstiegspunkt; seit der Buendelung traegt die
            // info()-Zeile die Warnung an derselben Stelle.
            info(
                "Manuelle Insulin-Autorisierung",
                "Der folgende Schalter verschiebt Verantwortung von FUSE zu dir. Jede andere " +
                    "Einstellung sagt FUSE, WIE es entscheiden soll - dieser sagt, dass DU " +
                    "entschieden hast und FUSE sich darauf verlaesst. Im Zweifel aus lassen."
            )
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.MarkerAuthorisesRelease, summary = R.string.fuse_marker_low_summary, title = R.string.fuse_marker_low_title))
        }

        cat("fuse_dosing", "Dosierung und Grenzen") {
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.SmbRatio, dialogMessage = R.string.fuse_smb_ratio_summary, title = R.string.fuse_smb_ratio_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.SmbRatioRise, dialogMessage = R.string.fuse_smb_ratio_rise_summary, title = R.string.fuse_smb_ratio_rise_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.MaxSmbU, dialogMessage = R.string.fuse_max_smb_u_summary, title = R.string.fuse_max_smb_u_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.RiseRampLowR, dialogMessage = R.string.fuse_ramp_summary, title = R.string.fuse_ramp_low_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.RiseRampHighR, dialogMessage = R.string.fuse_ramp_summary, title = R.string.fuse_ramp_high_title))
            info(
                "Insulingrenzen",
                "Der FUSE-Reiter zeigt zu diesen Grenzen den aktuell verbleibenden Spielraum. " +
                    "Beide Werte begrenzen dieselbe Endsumme; iobTH ist der schnelle Reserve-Anker."
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob,
                    dialogMessage = R.string.fuse_max_total_iob_summary, title = R.string.fuse_max_total_iob_title
                )
            )
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.IobThPercent, dialogMessage = R.string.fuse_iob_th_percent_summary, title = R.string.fuse_iob_th_percent_title))
            // DER LIVENESS-KANAL steht HIER und nicht bei Mahlzeit/Marker
            // (Toni 22.08., Geraetefund): er ist marker- und mahlzeiten-
            // UNABHAENGIG - ein mengenbegrenzter Zusatzkanal gegen den
            // Deadlock der Modell-Vetos, mit eigener Druckbedingung. Die
            // urspruengliche Einsortierung folgte nur der Bau-Nachbarschaft
            // zum Aufschub und war sachlich falsch. Vier Zeilen: OB es den
            // Kanal gibt, sein EIGENER Deckel, die Druck-Schwelle und die
            // Sperre nach jedem Exit.
            info(
                "Liveness-Kanal",
                "Mengenbegrenzter Zusatzkanal bei anhaltend steigendem Zucker ueber der " +
                    "Druck-Schwelle, wenn Guard oder Schwanz den Normalpfad deckeln. " +
                    "Unabhaengig von Marker und Mahlzeitenfenster."
            )
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.LivenessChannelEnabled, summary = R.string.fuse_liveness_summary, title = R.string.fuse_liveness_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.LivenessMealPowerMin, dialogMessage = R.string.fuse_liveness_meal_power_summary, title = R.string.fuse_liveness_meal_power_title))
            // LEGACY-CLEANUP (Toni 29.08. spaet): im Zentralmodus sind die
            // vier Legacy-Kanaldeckel wirkungslos (P1-Fix) und deshalb
            // UNSICHTBAR - keine Anzeige darf eine ignorierte Grenze wie
            // eine wirksame erscheinen lassen. Die Keys bleiben im Screen
            // registriert (Inventar-Wache sammelt sichtbarkeits-agnostisch)
            // und im LEGACY-Modus voll bedienbar - das ist der bewusst
            // erhaltene Rueckweg fuer den ersten Produktivstand.
            val zentralAktiv = preferences.get(FuseBooleanKey.CentralProfilesEnabled)
            val legacyLivenessPrefs = mutableListOf<androidx.preference.Preference>()
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.LivenessMealRatioCap, dialogMessage = R.string.fuse_liveness_meal_ratio_summary, title = R.string.fuse_liveness_meal_ratio_title).also { it.isVisible = !zentralAktiv; legacyLivenessPrefs += it })
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.LivenessMealIobCapPercent, dialogMessage = R.string.fuse_liveness_meal_iob_summary, title = R.string.fuse_liveness_meal_iob_title).also { it.isVisible = !zentralAktiv; legacyLivenessPrefs += it })
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.LivenessCorrectionRatioCap, dialogMessage = R.string.fuse_liveness_corr_ratio_summary, title = R.string.fuse_liveness_corr_ratio_title).also { it.isVisible = !zentralAktiv; legacyLivenessPrefs += it })
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.LivenessCorrectionIobCapPercent, dialogMessage = R.string.fuse_liveness_corr_iob_summary, title = R.string.fuse_liveness_corr_iob_title).also { it.isVisible = !zentralAktiv; legacyLivenessPrefs += it })
            addPreference(
                AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.CentralProfilesEnabled, summary = R.string.fuse_central_profiles_summary, title = R.string.fuse_central_profiles_title).also { schalter ->
                    // AKTIVIERUNGSSPERRE (Toni 29.08.): der Wechsel auf
                    // CENTRAL_PROFILES ist nur mit vier vollstaendig
                    // gueltigen, relational korrekten Werten erlaubt -
                    // sonst braeche jeder Folgezyklus fail-closed ab. Die
                    // Laufzeitvalidierung bleibt als letzte Sicherung.
                    schalter.setOnPreferenceChangeListener { _, neu ->
                        if (neu != true) {
                            // Rueckweg auf LEGACY: die Legacy-Deckel werden
                            // sofort wieder sichtbar und bedienbar.
                            legacyLivenessPrefs.forEach { it.isVisible = true }
                            return@setOnPreferenceChangeListener true
                        }
                        val fehler = FuseCentralProfileBackup.aktivierungsFehler(preferences)
                        if (fehler != null) {
                            app.aaps.core.ui.toast.ToastUtils.warnToast(
                                context, "Zentrale Profile nicht aktivierbar: $fehler",
                            )
                            return@setOnPreferenceChangeListener false
                        }
                        // Sofort, nicht erst beim naechsten Screen-Aufbau:
                        // die ignorierten Legacy-Deckel verschwinden.
                        legacyLivenessPrefs.forEach { it.isVisible = false }
                        true
                    }
                }
            )
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.MealExposureLimitU, dialogMessage = R.string.fuse_meal_exposure_limit_summary, title = R.string.fuse_meal_exposure_limit_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.CorrectionExposureLimitU, dialogMessage = R.string.fuse_corr_exposure_limit_summary, title = R.string.fuse_corr_exposure_limit_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.MealDemandRatioCap, dialogMessage = R.string.fuse_meal_demand_ratio_summary, title = R.string.fuse_meal_demand_ratio_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.CorrectionDemandRatioCap, dialogMessage = R.string.fuse_corr_demand_ratio_summary, title = R.string.fuse_corr_demand_ratio_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.LivenessBgMinDayMgdl, dialogMessage = R.string.fuse_liveness_bg_min_summary, title = R.string.fuse_liveness_bg_min_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.LivenessBgMinNightMgdl, dialogMessage = R.string.fuse_liveness_bg_min_night_summary, title = R.string.fuse_liveness_bg_min_night_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.LivenessBgMinMealMgdl, dialogMessage = R.string.fuse_liveness_bg_min_meal_summary, title = R.string.fuse_liveness_bg_min_meal_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.MealArmCycles, dialogMessage = R.string.fuse_meal_arm_cycles_summary, title = R.string.fuse_meal_arm_cycles_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.LivenessReArmMin, dialogMessage = R.string.fuse_liveness_rearm_summary, title = R.string.fuse_liveness_rearm_title))
        }

        cat("fuse_guard", "Schutz und Prognose") {
            info(
                "Guard - die Nahzone",
                "Sperrt Dosen, deren Bahn in den naechsten Minuten unter den Boden liefe."
            )
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.GuardFloorMgdl, dialogMessage = R.string.fuse_guard_floor_summary, title = R.string.fuse_guard_floor_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.PositiveDescentHorizonMin, dialogMessage = R.string.fuse_positive_descent_horizon_summary, title = R.string.fuse_positive_descent_horizon_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.FastRestraintEnabled, summary = R.string.fuse_restraint_summary, title = R.string.fuse_restraint_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.BolusShareLambda, dialogMessage = R.string.fuse_bolus_share_lambda_summary, title = R.string.fuse_bolus_share_lambda_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.ReleaseHorizonMin, dialogMessage = R.string.fuse_release_horizon_summary, title = R.string.fuse_release_horizon_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.DriveTauMin, dialogMessage = R.string.fuse_drive_tau_summary, title = R.string.fuse_drive_tau_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.DriveLowerQuantilePct, dialogMessage = R.string.fuse_drive_quantile_summary, title = R.string.fuse_drive_quantile_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.TheilSenWindowMin, dialogMessage = R.string.fuse_theil_sen_window_summary, title = R.string.fuse_theil_sen_window_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.SignalRejoinEnabled, summary = R.string.fuse_signal_rejoin_summary, title = R.string.fuse_signal_rejoin_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.TbrEndZeroWhenReasonGone, summary = R.string.fuse_end_zero_summary, title = R.string.fuse_end_zero_title))
            // ZERO-TBR-SCHUTZ als gemeinsamer Unterblock (Toni 24.08.): der
            // Latch arbeitet ganztaegig (nicht "Nacht und Rebound") und
            // begrenzt keine positive Menge (nicht "Dosierung und Grenzen") -
            // er schuetzt die Basalachse, also gehoert er direkt hinter die
            // Null-Basal-Regel in Schutz und Prognose.
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.ZeroLatchEnabled, summary = R.string.fuse_zero_latch_enabled_summary, title = R.string.fuse_zero_latch_enabled_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.ZeroLatchCalmExitMin, dialogMessage = R.string.fuse_zero_latch_calm_min_summary, title = R.string.fuse_zero_latch_calm_min_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.ZeroLatchCalmDistanceMgdl, dialogMessage = R.string.fuse_zero_latch_calm_dist_summary, title = R.string.fuse_zero_latch_calm_dist_title))
            // Die Korrekturpfad-Riegel (25.08.) direkt dahinter - dieselbe
            // Schutzfamilie, beide nur im reinen Korrekturkontext. Eigene
            // Zwischenueberschrift (Tonis UI-Hinweis 25.08. abends): neun
            // Einzelwerte unmittelbar hinter dem Zero-Latch waeren sonst
            // nicht mehr lesbar.
            info(
                "Korrekturpfad-Schutz",
                // WOERTLICH nach Tonis Korrektur (25.08. abends): der fruehere
                // Text stammte aus der Zeit VOR der Mahlzeitenbasis-Achse und
                // behauptete "nur reiner Korrekturkontext". Seit v30 greifen
                // die Riegel auch bei bloss kinematisch vermuteter Mahlzeit -
                // ausgenommen sind nur BELEGTE Mahlzeiten.
                "Zwei Riegel fuer unbelegte Korrekturlagen. Sie greifen im " +
                    "Korrekturkontext sowie bei ausschliesslich kinematisch erkanntem " +
                    "Mahlzeitenverdacht. Bei marker- oder evidenzbestaetigter Mahlzeit " +
                    "greifen sie nie. Sie sperren positive Korrektur-SMBs einschliesslich " +
                    "Liveness-K, veraendern aber weder TBR noch Basalantwort.",
            )
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.CorrectionReversalGuardEnabled, summary = R.string.fuse_reversal_guard_summary, title = R.string.fuse_reversal_guard_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.ReversalFallUkf, dialogMessage = R.string.fuse_reversal_fall_summary, title = R.string.fuse_reversal_fall_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.ReversalLookbackMin, dialogMessage = R.string.fuse_reversal_lookback_summary, title = R.string.fuse_reversal_lookback_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.ReversalReboundUkf, dialogMessage = R.string.fuse_reversal_rebound_summary, title = R.string.fuse_reversal_rebound_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.ReversalConfirmCycles, dialogMessage = R.string.fuse_reversal_confirm_summary, title = R.string.fuse_reversal_confirm_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.PositiveCorrectionRearmEnabled, summary = R.string.fuse_rearm_summary, title = R.string.fuse_rearm_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.RearmHoldMin, dialogMessage = R.string.fuse_rearm_hold_summary, title = R.string.fuse_rearm_hold_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.RearmConfirmCycles, dialogMessage = R.string.fuse_rearm_confirm_summary, title = R.string.fuse_rearm_confirm_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.RearmUpUkf, dialogMessage = R.string.fuse_rearm_up_summary, title = R.string.fuse_rearm_up_title))
            info(
                "Schwanz - die spaete Wirkung",
                "Haftung fuer Insulin, das erst hinter dem Freigabe-Horizont wirkt."
            )
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.TailGuardEnabled, summary = R.string.fuse_tail_guard_summary, title = R.string.fuse_tail_guard_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.ConditionalTailEnabled, summary = R.string.fuse_conditional_tail_summary, title = R.string.fuse_conditional_tail_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.LiabilityHorizonMin, dialogMessage = R.string.fuse_liability_horizon_summary, title = R.string.fuse_liability_horizon_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.TailFloorMgdl, dialogMessage = R.string.fuse_tail_floor_summary, title = R.string.fuse_tail_floor_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.TailRecoveryU, dialogMessage = R.string.fuse_tail_recovery_summary, title = R.string.fuse_tail_recovery_title))
        }

        cat("fuse_night_rebound", "Nacht und Rebound") {
            info(
                "Welche Kanaele werden gesperrt?",
                "Beide Totbaender sperren den SMB-Kanal. Eine laufende Basalabsenkung bleibt als Schutz bestehen; " +
                    "positive TBR verwendet FUSE derzeit nicht. Ein erklaerter Mahlzeitenmarker kann die Markerregeln oeffnen."
            )
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.NightDeadbandEnabled, summary = R.string.fuse_night_deadband_enabled_summary, title = R.string.fuse_night_deadband_enabled_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.NightDeadbandMgdl, dialogMessage = R.string.fuse_night_deadband_summary, title = R.string.fuse_night_deadband_title))
            timeOfDay(FuseIntKey.NightStartMin, "Nacht Beginn", "Beginn des Nachtfensters; gleich dem Ende schaltet es aus")
            timeOfDay(FuseIntKey.NightEndMin, "Nacht Ende", "Ende des Nachtfensters (darf ueber Mitternacht gehen)")
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.ReboundDeadbandEnabled, summary = R.string.fuse_rebound_deadband_enabled_summary, title = R.string.fuse_rebound_deadband_enabled_title))
            // Der Wert DIREKT hinter seinem Schalter (Toni 23.08. spaet) -
            // vorher sassen Beobachter und Prognose-Shadow dazwischen, und
            // wer das Totband stellte, musste am Schalter vorbei scrollen.
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = FuseDoubleKey.ReboundDeadbandMgdl, dialogMessage = R.string.fuse_rebound_deadband_summary, title = R.string.fuse_rebound_deadband_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = FuseIntKey.ReboundWindowMin, dialogMessage = R.string.fuse_rebound_window_summary, title = R.string.fuse_rebound_window_title))
            // DER ERWARTUNGS-BEOBACHTER (Toni 19.08.). Er war verdrahtet, aber
            // der Schalter stand in KEINEM Screen - am Geraet also nicht
            // erreichbar. Ein Beobachter, den niemand einschalten kann, misst
            // nie etwas, und genau das faellt erst auf, wenn man die Daten
            // braucht.
            //
            // Der Inventar-Waechter hat es nicht gefunden, weil der Key auch
            // im Einstellungs-Vertrag fehlte: konsistent ueberall abwesend
            // statt inkonsistent halb vorhanden.
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.ExpectationLedgerEnabled, summary = R.string.fuse_expectation_ledger_summary, title = R.string.fuse_expectation_ledger_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = FuseBooleanKey.ForecastShadowCollectionEnabled, summary = R.string.fuse_forecast_shadow_summary, title = R.string.fuse_forecast_shadow_title))
        }

        // System-Wissen und der Reparatur-Eingriff teilen sich den letzten
        // Einstiegspunkt: beides braucht man selten, beides gehoert ans Ende
        // des Nutzerwegs. Die Reparatur bleibt GANZ unten - sie ist kein
        // Einstellwert, sondern ein Eingriff, und steht bewusst nicht
        // zwischen Zeilen, die man im Vorbeiscrollen antippt.
        cat("fuse_system", "System und Reparatur") {
            info("Regler-Takt: 1 Minute", "Jeder neue 1-min-CGM-Wert ist ein Zyklus. Fest - kein Legacy-SMB-Intervall. Geregelt wird mit der insulinbereinigten Stoerungsrate r; Q1 und Rohwert stehen im Reiter nebeneinander.")
            info("Positive TBR: nicht verwendet", "Der schnelle Kanal ist der 1-min-SMB; FUSE setzt nur Null-Temps oder bricht ab. Max-TBR/Basal-Multiplikatoren greifen deshalb nicht.")
            info("ISF: Profil, zeitabhaengig", "Keine Autosens-/DynISF-Modulation. Sensitivitaet ist als eigener langsamer Beobachter geplant (Shadow zuerst).")
            info("COB/UAM: nicht verwendet", "Mahlzeiten erscheinen im insulinbereinigten Stoerungssignal; eigener Onset-Pfad + Marker statt UAM.")
            info("Pumpen-Gate", "Aktuation ist nur fuer die VirtualPump und den belegten Medtrum Nano freigegeben. Der aktuelle Gate-Grund steht oben im FUSE-Reiter.")
            info("Ledger", "Offene Transporthaftung wird von iobTH- und maxIOB-Spielraum abgezogen. Hold, offene Zeilen und Haftung stehen im Reiter.")
            addPreference(Preference(context).apply {
                title = "Hold quittieren"
                summary =
                    "Der SANFTE Ausgang aus einem Ledger-Hold: nimmt einer benannten Zeile ihre " +
                        "Fehler, laesst Haftung, Mahlzeiten-Huelle und den Genau-einmal-Riegel " +
                        "stehen. Anders als die Reparatur auch an einer echten Pumpe zulaessig."
                isPersistent = false
                setOnPreferenceClickListener { runCatching { holdQuittungDialog(context) }; true }
            })
            addPreference(Preference(context).apply {
                title = "Ledger reparieren"
                summary =
                    "Oeffnet einen dauerhaften Hold, aus dem es sonst keinen Ausgang gibt " +
                        "(nicht quittierbare Fehler). Der bisherige Ledger wird nicht geloescht, " +
                        "sondern in Quarantaene gelegt und protokolliert. Kein 'Ledger leeren'."
                isPersistent = false
                setOnPreferenceClickListener { runCatching { ledgerReparaturDialog(context) }; true }
            })
            addPreference(Preference(context).apply {
                title = "Letzte Reparatur"
                summary = letzteReparatur()?.let {
                    "%s - %s".format(dateUtil.dateAndTimeString(it.ts), it.reason)
                } ?: "keine"
                isSelectable = false
                isPersistent = false
            })
        }

        // ---- Erst JETZT die Wache, gegen die ECHTE Abschnittsmenge ---------
        if (requiredKey != null && requiredKey !in abschnitte.keys) return

        // Die sichtbare Reihenfolge folgt dem Nutzerweg: Mahlzeit zuerst,
        // System/Reparatur ganz zuletzt.
        val reihenfolge = listOf(
            "fuse_meal", "fuse_dosing", "fuse_guard", "fuse_night_rebound", "fuse_system",
        )
        require(reihenfolge.toSet() == abschnitte.keys) {
            "FUSE-Einstellungsabschnitte und sichtbare Reihenfolge sind auseinandergelaufen"
        }

        // Vollstaendigkeitsvertrag: jeder vom Nutzer einstellbare FUSE-Wert
        // erscheint GENAU EINMAL. Interne Marker-/Ledger-Schluessel gehoeren
        // ausdruecklich nicht hierher. Die Menge lebt auf Dateiebene, weil
        // der Settings-Bericht des Reiters DENSELBEN Vertrag erfuellen muss -
        // zwei Listen waeren wieder die Schwarzer-Bildschirm-Falle.
        val erwarteteKeys = fuseEinstellbareKeys

        // ---- und erst jetzt bauen ------------------------------------------
        val root = PreferenceCategory(context)
        parent.addPreference(root)
        root.title = rh.gs(R.string.fuse_settings)
        val sichtbareKeys = mutableListOf<String>()
        for (key in reihenfolge) {
            val eintrag = checkNotNull(abschnitte[key])
            val (titleText, block) = eintrag
            val screen = preferenceManager.createPreferenceScreen(context).apply {
                this.key = key
                this.title = titleText
                block()
            }
            for (i in 0 until screen.preferenceCount) {
                screen.getPreference(i).key?.takeIf { it.isNotBlank() }?.let(sichtbareKeys::add)
            }
            root.addPreference(screen)
        }
        require(sichtbareKeys.size == sichtbareKeys.toSet().size) {
            "Ein FUSE-Einstellwert erscheint in mehreren Kategorien"
        }
        require(sichtbareKeys.toSet() == erwarteteKeys) {
            "FUSE-Einstellungsinventar unvollstaendig: fehlt=${erwarteteKeys - sichtbareKeys.toSet()}, " +
                "unerwartet=${sichtbareKeys.toSet() - erwarteteKeys}"
        }
    }
}

package app.aaps.fuse.plugin

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.target
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.data.model.BS
import app.aaps.fuse.core.adapter.CoreInputGuard
import app.aaps.fuse.core.adapter.CycleAssembly
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.LedgerHoldGate
import app.aaps.fuse.core.controller.MarkerScope
import app.aaps.fuse.core.controller.NightWindow
import app.aaps.fuse.core.controller.SubStepAccumulator
import app.aaps.fuse.core.ledger.AccountedTreatment
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.fuse.plugin.ledger.LedgerFacts
import app.aaps.fuse.plugin.ledger.OpenTransportItem
import app.aaps.fuse.plugin.ledger.TransportInclusion
import app.aaps.fuse.core.controller.IobThreshold
import app.aaps.fuse.core.controller.TailLiability
import app.aaps.fuse.core.controller.TbrPolicy
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.ObserverStateMachine
import app.aaps.fuse.core.observer.ObserverStep
import app.aaps.fuse.core.signal.PairSlopeBand
import app.aaps.fuse.core.predictor.ActualTrajectoryFactory
import app.aaps.fuse.core.predictor.DriveDecayModel
import app.aaps.fuse.core.controller.CandidateGate
import app.aaps.fuse.core.controller.CandidateSearch
import app.aaps.fuse.core.controller.OnsetChannel
import app.aaps.fuse.core.controller.PrimeRelease
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.predictor.DriveDiscount
import app.aaps.fuse.core.predictor.DriveEstimate
import app.aaps.fuse.core.predictor.InsulinLineage
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.core.predictor.PendingInsulinEffect
import app.aaps.fuse.core.predictor.PredictorInput
import app.aaps.fuse.core.predictor.PredictorOutcome
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryCore
import app.aaps.fuse.core.predictor.minSafetyHorizonLowerOf
import app.aaps.fuse.core.predictor.minSafetyLowerOf

/**
 * Der Zyklus: AAPS hinein, Entscheidung heraus. Er entscheidet NICHTS selbst.
 *
 * Reihenfolge ist Absicht — Zustand vor Zahlen, und alles aus EINER
 * Momentaufnahme:
 *
 *   1  Signal  (q1 + rSigned, kausal aus der Rohreihe)
 *   2  Zustand (Observer: darf ueberhaupt gerechnet werden?)
 *   3  Bahn    (Predictor, verankert auf sourceTs)
 *   4  Menge   (Controller)
 *   5  Kanal   (TBR-Tabelle, inkl. smbBlocked)
 *
 * DAS IOB-ARRAY WIRD HIER SELBST GEBAUT und nicht von
 * `calculateIobArrayForSMB` geholt. Grund: jenes Array beginnt bei `now`, der
 * Predictor verankert aber auf `sourceTs` (der BG-Zeit). Ist der BG-Wert beim
 * Rechnen aelter als eine Minute — genau in den langsamen Zyklen —, liegt der
 * Arrayanfang HINTER dem Anker, und `TrajectoryCore` verwirft die ganze Bahn:
 * kein SMB, ohne dass jemand merkt warum. Ab `sourceTs` gebaut ist das Gate
 * strukturell erfuellt, und es kostet keinen Aufruf mehr — jenes Array rechnet
 * intern dieselbe Schleife.
 *
 * KEIN Zyklus wirft. Jeder Ausstieg traegt einen Namen und eine fail-closed
 * Entscheidung ([FuseController.noInput]); `LoopPlugin.invoke` hat kein `catch`,
 * eine Ausnahme von hier wuerde also den ganzen Loop-Durchlauf abbrechen.
 */
class FuseCycleRunner(
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val constraintsChecker: ConstraintsChecker,
    private val commandQueue: CommandQueue,
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    /** Audit R95 NEU-05: TBR-Sicht INKLUSIVE konvertierter Extended-Boli -
     *  die rohe TB-Tabelle sieht auf EB-fakenden Pumpen (Dana) eine laufende
     *  Abgabe nicht, die der Loop als activeTemp fuehrt. */
    private val processedTbrEbData: app.aaps.core.interfaces.db.ProcessedTbrEbData,
    private val dateUtil: DateUtil,
    /** Commitment-Ledger (Audit R95, Fix 3): liefert Hold + Transportmenge in
     *  den Zyklus und traegt die restartfesten Episodenbudgets. Geladen und
     *  persistiert wird er im Plugin - der Runner LIEST und BELASTET nur. */
    private val ledger: FuseLedgerAdapter,
    sessionId: String,
) {

    private val observer = ObserverStateMachine(sessionId = sessionId)
    private val signalSource = FuseSignalSource(iobCobCalculator, profileFunction)

    companion object {

        /** Raster des selbst gebauten IOB-Arrays. 5 min wie in AAPS — feiner
         *  waere teurer, ohne dass der Predictor davon profitiert. */
        const val IOB_GRID_MS = 5 * 60_000L

        /** Reserve hinter dem Haftungshorizont, damit das Array-Ende-Gate nicht
         *  an einer Rundung scheitert. */
        const val IOB_MARGIN_MIN = 30

        /** Name des Schwanz-Vetos in `bindingLimit` (C4). */
        const val TAIL_VETO = "TAIL_LIABILITY"

        /** Rundungsluft des Schwanz-Vetos [U]. Ein Spielraum von exakt 0 ist
         *  KEIN Vertragsbruch - er ist die Grenze, und die Menge wurde genau an
         *  ihr gewaehlt. */
        const val TAIL_VETO_EPS_U = 1e-9

        /**
         * Wie weit der Lieferanker der Transportmenge zurueckreichen darf [min]
         * (C3).
         *
         * Anker ist der FRUEHESTE plausible Lieferzeitpunkt - konservativ ist
         * frueh, weil dann mehr Wirkung ins Bewertungsfenster faellt. Der
         * aelteste offene Vorschlag kann aber beliebig alt sein (eine Zeile
         * bleibt offen, bis sie bewiesen ist), und ein stundenalter Anker
         * verschoebe den Modelltraeger so weit, dass er das Fenster nicht mehr
         * deckt - der Zyklus fiele dann dauerhaft mit PENDING_MODEL_TOO_SHORT
         * aus.
         *
         * 30 min sind rund das Doppelte der GEMESSENEN Groesse: 765
         * Medtrum-SMBs, Sichtbarkeits-Latenz p99 175 s, MAX 854 s, maximaler
         * Pump-ID-Verzug 901 s.
         */
        const val TRANSPORT_ANCHOR_MAX_AGE_MIN = 30

        /** Lieferanker der synthetischen Dosis - s. [TRANSPORT_ANCHOR_MAX_AGE_MIN].
         *  Ohne offene Zeile ist `computeTs` die ehrliche Antwort: dann ist die
         *  Menge gerade erst publiziert worden. */
        internal fun transportAnchorTs(oldestOpenTs: Long?, computeTs: Long): Long =
            oldestOpenTs?.coerceIn(computeTs - TRANSPORT_ANCHOR_MAX_AGE_MIN * 60_000L, computeTs) ?: computeTs

        /**
         * DER SPAETESTE plausible Lieferzeitpunkt eines Postens (C3-01/G.1).
         *
         * Die reale Lieferzeit ist unbekannt; bekannt ist die SPANNE. Ihr
         * rechtes Ende ist `computeTs`: die Menge wurde kommandiert, und die
         * gemessene Sichtbarkeits-Latenz (765 Medtrum-SMBs: p99 175 s, MAX
         * 854 s) laeuft der Lieferung NACH, nicht voraus - eine Lieferung in
         * der Zukunft anzunehmen waere Spekulation, keine Konservativitaet.
         *
         * Fuer die RESTHAFTUNG am Horizont ist dieses Ende die pessimistische
         * Wahl: je spaeter geliefert, desto weniger Wirkung ist bis H
         * verbraucht, desto mehr haftet danach. Der FRUEHESTE Anker
         * ([transportAnchorTs]) bleibt der der BAHN - dort ist frueh
         * pessimistisch. Zwei Zwecke, zwei Enden derselben Spanne.
         */
        internal fun transportAnchorLatestTs(earliestTs: Long, computeTs: Long): Long =
            maxOf(earliestTs, computeTs)

        /**
         * Die Posten dieses Zyklus als EINZELNE Dosen (C3-01 + C3-02).
         *
         * Menge je Posten kommt aus dem Inclusion-Vertrag
         * ([TransportInclusion.modelledU]) und ist damit NIE kleiner als der
         * Ledgerwert; die beiden Anker spannen die plausible Lieferzeit auf.
         * Posten ohne Menge fallen weg - eine 0-U-Dosis in der Bahn haette
         * keine Wirkung, wuerde aber den Schwanz-Vermerk unnoetig auf
         * `transportBounded` ziehen.
         */
        internal fun transportDoses(
            items: List<OpenTransportItem>,
            witness: TransportInclusion.IobSnapshotWitness?,
            computeTs: Long,
        ): List<TransportDose> = items.mapNotNull { item ->
            val u = TransportInclusion.modelledU(item, witness)
            if (!(u > 0.0)) null else {
                val earliest = transportAnchorTs(item.bestKnownTs, computeTs)
                TransportDose(item.proposalId, u, earliest, transportAnchorLatestTs(earliest, computeTs))
            }
        }

        /**
         * Die Restwirkung EINES Postens am Haftungshorizont (C4a je Posten).
         *
         * Genommen wird der Anker mit der GROESSEREN Restwirkung. Bei jedem
         * fallenden Modell ist das der spaete; die Maximumbildung steht
         * trotzdem da, weil die Aussage dann an der RECHNUNG haengt und nicht
         * an der Annahme, das Modell sei monoton.
         *
         * Ohne Einheitskern ist die Restwirkung UNBEKANNT (`null`), nicht 0 -
         * dann rechnet [TailLiability.Dose] mit der vollen Menge und der
         * Vermerk sagt `transportBounded`.
         */
        internal fun tailTransportDose(
            dose: TransportDose,
            kernel: UnitInsulinKernel?,
            liabilityHorizonTs: Long,
        ): TailLiability.Dose = TailLiability.Dose(
            amountU = dose.amountU,
            residualAtHU = kernel?.let { k ->
                maxOf(
                    KernelPendingInsulin(k, dose.amountU, dose.earliestTs).iobAt(liabilityHorizonTs),
                    KernelPendingInsulin(k, dose.amountU, dose.latestTs).iobAt(liabilityHorizonTs),
                )
            },
        )
    }

    data class Outcome(
        val decision: FuseController.Decision,
        val tbr: FuseController.TbrRequest?,
        val prediction: PredictorResult?,
        val sourceTs: Long?,
        val computeTs: Long,
        val health: Health?,
        val gate: FusePumpGate.Result,
        val reason: String,
        val alarm: Boolean,
        /** Nullbar heisst UNBEKANNT, nicht 0 — ein Zyklus, der am fehlenden
         *  Profil endet, kennt weder Ziel noch ISF. */
        val bgMgdl: Double?,
        val targetMgdl: Double?,
        /** Woher das Ziel kam: `TT` oder `profile`. Steht im `RT.reason`, damit
         *  im Nachhinein nie geraten werden muss, gegen welches Ziel FUSE
         *  gerechnet hat. */
        val targetSource: String?,
        /** Das Signal des Zyklus - auch auf den Abbruchpfaden NACH Schritt 1.
         *  Genau dort wird es gebraucht: direkt nach einem Sensorwechsel oder
         *  einer Kalibrierung liefert Theil-Sen mangels Punkten `null`, der
         *  Zyklus bricht ab, und ohne dieses Feld fehlte im Export ausgerechnet
         *  dann die Fenstergrenze, die den Abbruch erklaert. */
        val signal: FuseSignalSource.Signal?,
        /** Antriebsschaetzung inkl. Spreizung und Paaranzahl. Genau diese beiden
         *  Zahlen entscheiden nach dem ersten Lauf, ob das Quantil brauchbar
         *  gewaehlt war — deshalb gehoeren sie in den Grund, nicht ins Log. */
        val band: PairSlopeBand.Estimate?,
        /** Der angewandte Bolus-Deckungs-Abschlag. `null` nur auf Abbruchpfaden
         *  vor dem Bahnbau - im Erfolgsfall steht er IMMER da, auch bei
         *  lambda 0, damit "aus" von "alter Build ohne Abschlag" unterscheidbar
         *  bleibt. */
        val discount: DriveDiscount.Applied?,
        /** Zustand des Onset-Kanals dieses Zyklus. `null` nur auf
         *  Abbruchpfaden vor der Signalstufe. */
        val onset: OnsetChannel.Result?,
        /** Plan der Sofort-Freigabe dieses Zyklus. `null` nur auf
         *  Abbruchpfaden vor der Bahn. */
        val prime: PrimeRelease.Plan?,
        /** Ergebnis der Kandidatensuche. `null` = kein Vorschlag > 0 oder
         *  Abbruchpfad. */
        val candidate: CandidateSearch.Result?,
        /** Pruefer-Ausfall (z.B. KERNEL_*): Basis galt unveraendert. */
        val candidateGap: String?,
        /** Reine Rechenzeit dieses Zyklus [ms] - die Messgrundlage VOR jeder
         *  Akku-Optimierung (Kernel-Cache, inkrementelles Q1, Activity-Cache). */
        val computeDurationMs: Long?,
        /** Abgabe-Bilanz seit Marker-Druck - null ohne Marker. */
        val mealStats: MealStats?,
        /** `null` = der Zyklus kam nicht bis zum Lesen der Einstellungen. Dann
         *  hat er auch keine Politik, und der Export sagt das statt eine zu
         *  erfinden. */
        val policy: Config?,
        /** Der REGLERZUSTAND dieses Zyklus, unveraendert wie er in `decide`
         *  ging. Als Referenz statt als neun einzelne Zahlen: so kann beim
         *  Erweitern kein Feld vergessen werden, und iobTH wird nicht ein
         *  zweites Mal gerechnet. */
        val state: FuseController.State?,
        /** Der Observer-Schritt. Traegt Phase, Health-Gruende und
         *  Safety-Gruende, die der Regler selbst nicht weitergibt. */
        val step: ObserverStep?,
        /** Die Regimegrenzen dieses Zyklus. Sie gehoeren in den Export, weil
         *  eine spaetere Nachrechnung sonst nicht weiss, welche Punkte
         *  ueberhaupt zu derselben Messreihe gehoeren. */
        val sensorEpoch: Long?,
        val calibrationEpoch: Long?,
        val isfMgdlPerU: Double?,
        val iobU: Double?,
        /** Basiswerte, die auch ein ABBRUCH-Zyklus kennt (Toni 08.08.: iobTH
         *  nie verstecken) - im Normalzyklus traegt sie der State, hier sind
         *  sie der Fallback fuer die Anzeige. */
        val iobThU: Double? = null,
        val maxIobU: Double? = null,
        /**
         * WELCHE Insulinkurve diese Zahlen erzeugt hat (Kontroll-Audit 09.08.).
         *
         * Ohne sie ist im Nachhinein nicht entscheidbar, ob zwei Zyklen
         * ueberhaupt vergleichbar sind: DIA und peak sind Profil-Eigenschaften
         * und koennen sich zwischen zwei Zeilen des Trails aendern, ohne dass
         * irgendetwas im Export das anzeigt. `null` auf Abbruchpfaden - dort
         * wurde keine Bahn gerechnet.
         */
        val insulinModel: app.aaps.fuse.core.predictor.InsulinModelProvenance? = null,
        /** Warum NICHT gerechnet wurde. `null` heisst: der Zyklus lief durch. */
        val abortReason: String?,
        /** Die Treatment-Vollsicht dieses Zyklus fuer den Ledger-Abgleich.
         *  `null` auf Abbruchpfaden UND wenn die Datenbankabfrage scheitert -
         *  dann gibt es diesen Zyklus keinen Abgleich, und offene Commitments
         *  bleiben konservativ stehen. Default, damit Abbruchpfade und Tests
         *  unveraendert konstruieren. */
        val treatmentView: TreatmentView? = null,
    )

    /**
     * Vollsicht-Vertrag (R93-F3): ALLE gueltigen Boli des IOB-Fensters PLUS
     * jeder Fakt, der an eine offene Ledger-Zeile gebunden ist - auch wenn er
     * aus dem DIA-Fenster herausgealtert ist. "Fehlt in der Sicht" muss
     * LOESCHUNG heissen, nicht Alterung - sonst meldet die Reconciliation
     * vertragsgemaess, aber sachlich falsch MISSING_ACCOUNTED_TREATMENT und
     * haelt FUSE dauerhaft an.
     */
    data class TreatmentView(
        val boluses: List<BS>,
        val facts: List<AccountedTreatment>,
        val snapshotHash: String,
        /** Juengster Bolus der Sicht - geht als latestBolusTimestamp in den
         *  Vorschlag (C5-Guard der Bindung). 0 = keiner bekannt. */
        val latestBolusTs: Long,
        val diaHours: Double,
    )

    fun run(tempBasalFallback: Boolean): Outcome {
        val computeTs = dateUtil.now()
        val gate = FusePumpGate.evaluate(runCatching { activePlugin.activePump }.getOrNull())

        // Audit R95 F-P0-07: ein Abort liess eine LAUFENDE POSITIVE TBR bis zu
        // ihrem Ende weiterlaufen (fail-silent, war nur fuer VPUMP akzeptiert).
        //
        // C7c (Codex-Adjudication, K2 Punkt 10): die Regel steht seit
        // Fix-Pass 5 in [FuseAbortTbr] und NICHT mehr hier - eine Ausnahme,
        // die aus run() entkommt, wird erst in FusePlugin.invoke() gefangen
        // und braucht DENSELBEN Vertrag. Genau EINE Implementierung, keine
        // Kopie.
        fun abortTbr(): Pair<FuseController.TbrRequest?, Boolean> =
            FuseAbortTbr.evaluate(processedTbrEbData, profileFunction, computeTs).let { it.request to it.alarm }

        fun abort(reason: String, signal: FuseSignalSource.Signal? = null, policy: Config? = null, step: ObserverStep? = null): Outcome {
            // SUB-02 (Codex Fix-Pass-5-Closure): der Rest-Zaehler ist
            // aufgeschobene ABSICHT, kein Guthaben. Jeder Abbruch - Signal,
            // Profil, Config, Epoch, Kernel, Zeitachse - beendet den Kontext,
            // in dem die Absicht entstand. abort() ist der EINE Ausgang, an
            // dem alle diese Pfade vorbeikommen; hier zu verwerfen, deckt sie
            // alle ab, statt sie einzeln nachzupflegen.
            subStepCarryU = 0.0
            val (cancelTbr, tbrAlarm) = abortTbr()
            // Auch ein Abbruch kennt die Basiswerte (Toni 08.08.: nie
            // verstecken) - jede Lesung einzeln tolerant, ein Abbruch darf an
            // der Anreicherung nicht scheitern.
            val maxIob = runCatching { constraintsChecker.getMaxIOBAllowed().value() }.getOrNull()
            val iobTh = if (policy != null && maxIob != null)
                runCatching { IobThreshold.fromPercent(policy.iobThPercent.toDouble(), maxIob) }.getOrNull() else null
            val iob = runCatching {
                profileFunction.getProfile(computeTs)?.let { p -> iobCobCalculator.calculateFromTreatmentsAndTemps(computeTs, p).iob }
            }.getOrNull()
            return Outcome(
                decision = FuseController.noInput(reason), tbr = cancelTbr, prediction = null,
                sourceTs = signal?.sourceTs, computeTs = computeTs, health = step?.health, gate = gate,
                reason = reason, alarm = tbrAlarm, bgMgdl = signal?.q1, targetMgdl = null, targetSource = null,
                signal = signal, band = null, discount = null, onset = null, prime = null, candidate = null, candidateGap = null, policy = policy, state = null, step = step,
                sensorEpoch = null, calibrationEpoch = null,
                isfMgdlPerU = null, iobU = iob, iobThU = iobTh, maxIobU = maxIob, computeDurationMs = null, mealStats = null, abortReason = reason,
            )
        }

        val profile = profileFunction.getProfile(computeTs) ?: return abort("no profile")

        // Beide Epochen EINMAL je Zyklus. Sie begrenzen die Signalreihe UND
        // steuern die Health-Gruende des Observers - zwei verschiedene Lesungen
        // waeren zwei verschiedene Zustaende in derselben Momentaufnahme.
        val sensorEpoch = sensorEpoch()
        val calibrationEpoch = calibrationEpoch()

        // ---- 1 Signal ------------------------------------------------------
        val signal = when (val s = signalSource.read(sensorEpoch, calibrationEpoch)) {
            is FuseSignalSource.Outcome.Ok          -> s.signal
            is FuseSignalSource.Outcome.Unavailable -> return abort("signal: ${s.reason}")
        }

        // Zeitachsen-Tor (Fix-Pass 2 Nr. 5, REG-02): VOR observer.step - ein
        // akzeptierter Zukunfts-Punkt zog sonst lastAcceptedSourceTs vor und
        // sperrte danach jede REALE Reihe als out-of-order (Lockout bis zum
        // Aufholen). Jetzt sieht der Observer den Punkt gar nicht erst.
        app.aaps.fuse.core.signal.SignalTimeGate.futureReason(signal.sourceTs, computeTs)?.let {
            return abort(it, signal)
        }

        // ---- 2 Zustand -----------------------------------------------------
        val step = observer.step(
            CycleAssembly.observerInput(
                sourceTs = signal.sourceTs,
                computeTs = computeTs,
                // Die Sprungerkennung laeuft auf dem ROHWERT, nicht auf q1: ein
                // Kalibriersprung soll erkannt werden, BEVOR der Filter ihn
                // glaettet. q1 steht daneben in seinem eigenen Feld.
                signalInputBg = signal.rawBg,
                q1 = signal.q1,
                rSigned = signal.rSigned,
                sensorEpoch = sensorEpoch,
                // Der Kalibrierbeginn IST lesbar: `LongKey.FslCalibrationStart`
                // wird vom Fork in XdripSourcePlugin geschrieben. Hier stand
                // vorher hart 0L mit der Begruendung, es gebe kein solches
                // Ereignis — das war falsch und hat CALIBRATION_RESET und
                // CALIBRATION_BLIND stillgelegt. Folge waere gewesen: nach jeder
                // Kalibrierung filtert Q1 zwei Messregime als eine Reihe, ohne
                // dass ein Health-Grund das anzeigt.
                //
                // Default ist -1, nicht 0 — deshalb `takeIf { it > 0L }` und
                // nicht `coerceAtLeast`.
                calibrationEpoch = calibrationEpoch,
                activity = signal.activity,
                profileIsfValid = true,
                inputGap = false,
            )
        )

        // Audit R95 F-P0-03: der Observer lehnt Duplikate/out-of-order ab
        // (accepted=false), der Runner hat das ignoriert und mit demselben
        // Messpunkt erneut dosiert. Erreichbar ueber Bypass-Invokes
        // (TT-Events, Loop-Swipe, Neustart). Jetzt fail-closed: derselbe
        // sourceTs finanziert nie eine zweite positive Aktuation; der Abort
        // haelt die TBR-Sicherheit aufrecht (abortTbr).
        if (!step.accepted) return abort("signal duplicate/out-of-order (accepted=false)", signal, step = step)

        // FIX-PASS 3 Nr. 5 (Re-Audit 6.5): genau-einmal je Glukose-Epoch auch
        // UEBER Prozessgrenzen - der Observer-Speicher stirbt mit dem Prozess,
        // die Ledger-Datei nicht. Konsumiert wird beim AKZEPTIERTEN Step,
        // nicht erst bei der Dosis: auch ein spaeter abgebrochener Zyklus darf
        // nach Restart nicht erneut dosierfaehig werden.
        if (signal.sourceTs <= ledger.episodes.lastAcceptedSourceTs)
            return abort("signal epoch already consumed (restart dedupe)", signal, step = step)
        ledger.episodes.lastAcceptedSourceTs = signal.sourceTs

        // ---- 3 Bahn --------------------------------------------------------
        val cfg = when (val c = CoreInputGuard.build { readConfig() }) {
            is CoreInputGuard.Outcome.Built  -> c.value
            is CoreInputGuard.Outcome.Failed -> return abort("config: ${c.failure.detail}", signal, step = step)
        }

        // LEDGER-SICHT dieses Zyklus (Audit R95, Fix 3): was publiziert, aber
        // noch nicht im IOB nachgewiesen ist, ist KEIN freier Spielraum. Die
        // Transportmenge geht von BEIDEN Headrooms ab; ein Hold sperrt die
        // Suche und - nach dem Lift - die gesamte Menge.
        //
        // C3 (Codex-Adjudication bae885f1): sie wird HIER gelesen und nicht
        // mehr erst in Schritt 4, weil dieselbe Zahl jetzt schon in die BAHN
        // eingeht. Der Ledger wird im Zyklus nur gelesen, die Sicht ist also
        // dieselbe wie vorher.
        val ledgerView = ledger.view()

        // DER EINHEITSKERN, EINMAL JE ZYKLUS und gemerkt.
        //
        // Drei Verbraucher brauchen ihn: die Transportmenge in der Bahn (C3),
        // die Kandidatensuche und die finale Wirkungspruefung. Ein zweiter Bau
        // kostete ~540 Modellabfragen und koennte - bei einem Profilwechsel
        // zwischen den Aufrufen - ein ANDERES Modell liefern; dann waere die
        // gepruefte Bahn eine andere als die dosierte. Gebaut wird traege: ohne
        // Transportmenge und ohne positive Basis faellt der Aufwand weiterhin
        // ganz weg.
        fun buildKernel(): KernelOutcome {
            val insulin = activePlugin.activeInsulin
            return UnitInsulinKernelBuilder.build(
                sampler = AapsUnitInsulinSampler(insulin, profile.dia, computeTs),
                deliveryTs = computeTs,
                model = InsulinModelProvenance(
                    insulinType = insulin.id.name,
                    diaHours = profile.dia,
                    peakMin = insulin.peak,
                    codeProvenance = "activePlugin.activeInsulin",
                ),
                insulinPluginId = insulin.id.name,
            )
        }

        var kernelTried = false
        var kernelCache: UnitInsulinKernel? = null
        var kernelReject: String? = null
        fun kernel(): UnitInsulinKernel? {
            if (!kernelTried) {
                kernelTried = true
                when (val k = buildKernel()) {
                    is KernelOutcome.Ok       -> kernelCache = k.kernel
                    is KernelOutcome.Rejected -> kernelReject = "KERNEL_" + k.reason.name
                }
            }
            return kernelCache
        }

        // C3: DIE TRANSPORTMENGE ALS SYNTHETISCHE DOSIS.
        //
        // Bisher wurde sie nur von den Headrooms abgezogen. Das begrenzt, was
        // NOCH angefordert werden darf - es macht die Bahn aber nicht wahr:
        // gemessen (765 Medtrum-SMBs) liegt die Sichtbarkeits-Latenz bei p50
        // 15 s, p90 56 s, p99 175 s, MAX 854 s. In diesen 1 bis ~15 Zyklen
        // glaubten Guard und Schwanz, die Menge habe keine Zukunftswirkung.
        //
        // Ohne Kern gibt es keine synthetische Dosis - die Bahn ist dann zu
        // optimistisch. Das bleibt folgenlos, weil OHNE Kern ohnehin keine
        // positive Menge den Zyklus verlaesst (finalVeto -> MODEL_HORIZON_TOO_
        // SHORT); der Schwanz rechnet in diesem Fall mit der vollen Menge
        // (TailLiability.Dose ohne Restwirkung), statt sie zu vergessen.
        //
        // C3-01 (P0, Codex Fix-Pass-5-Closure G.2): JEDER OFFENE POSTEN
        // EINZELN. Vorher stand hier die SUMME am AELTESTEN offenen
        // Zeitstempel - fuer die Resthaftung am Horizont die unterschaetzende
        // Wahl (Codex' Gegenprobe: 0,2925 U getrennt gegen 0,2800 U
        // aggregiert). Menge und Anker kommen jetzt je Zeile aus dem Ledger.
        //
        // C3-02 (P0, G.3): und die Menge je Posten entscheidet der
        // INCLUSION-VERTRAG, nicht der Ledgerwert allein. Der Zeuge ist eine
        // Bolus-Lesung, die dem Bau der IOB-Arrays VORAUSGEHT (die Arrays
        // entstehen erst in buildPredictorInput weiter unten). Was der Zeuge
        // sah, war beim Arraybau in der Datenbank; was er nicht sah, ist
        // UNENTSCHEIDBAR und bleibt deshalb voll als Transport modelliert -
        // konservativ doppelt statt in keiner der beiden Sichten.
        //
        // Der Zeuge wird nur gelesen, wenn ueberhaupt eine Zeile eine Buchung
        // traegt: ohne Buchung gibt es nichts zu entscheiden, und die
        // zusaetzliche Datenbankabfrage entfaellt.
        val transportItems = ledger.openTransportItems()
        val iobWitness =
            if (transportItems.none { it.accountedAmountU > 0.0 }) null
            else runCatching { iobSnapshotWitness(computeTs, profile.dia) }.getOrNull()
        val transport = transportDoses(transportItems, iobWitness, computeTs)
        // EINE Zahl fuer alle Verbraucher dieses Zyklus (Bahn, Headrooms,
        // Schwanz). Sie ist per Vertrag >= ledgerView.transportCommitmentU -
        // die Kappen koennen dadurch nur enger werden, nie weiter.
        val transportModelledU = transport.sumOf { it.amountU }
        // Der Kern wird weiterhin TRAEGE gebaut: ohne Posten faellt der Aufwand
        // (~540 Modellabfragen) ganz weg.
        val pending: List<PendingInsulinEffect> =
            if (transport.isEmpty()) emptyList()
            else kernel()?.let { k -> transport.map { KernelPendingInsulin(k, it.amountU, it.earliestTs) } }
                ?: emptyList()

        // Mittel- UND Untergrenze aus DEMSELBEN Aufruf. Es darf keinen Zustand
        // "Mittel da, Band fehlt" geben: ein Rueckfall auf lower = mean wuerde
        // den Null-Abstand ausgerechnet bei der schlechtesten Datenlage still
        // wiederherstellen.
        val band = PairSlopeBand.estimate(signal.adjusted, signal.sourceTs, cfg.driveLowerQuantilePct)
            ?: return abort("drive not estimable (${signal.samplesUsed} samples)", signal, cfg, step)

        // Bolus-Aktivitaet am Anker - Eingang des Deckungs-Abschlags.
        // `calculateIobFromBolus()` rechnet auf `dateUtil.now()`, bis zu ~1 min
        // neben `sourceTs`. Fuer einen ABSCHLAG (keinen Bahnpunkt) ist der
        // Versatz zweitrangig; die Zahl steht im Export und ist nachpruefbar.
        if (signal.q1 < FuseController.REBOUND_LOW_MGDL) lastLowTs = signal.sourceTs
        val reboundRaw = lastLowTs > 0 &&
            signal.sourceTs - lastLowTs < FuseController.REBOUND_WINDOW_MIN * 60_000L

        val bolusActivityUPerMin = iobCobCalculator.calculateIobFromBolus().activity

        // Onset-Kanal: Ring pflegen (gleicher sourceTs ersetzt statt doppelt),
        // dann bewerten. Der Antrieb des Kanals ist der BGI-bereinigte
        // fastDrive, das Gate die rohe UKF-Rate - s. OnsetChannel.
        fastDrive(signal)?.let { fd ->
            if (onsetRing.lastOrNull()?.tsMs == signal.sourceTs) onsetRing.removeLast()
            onsetRing.addLast(OnsetChannel.Sample(signal.sourceTs, signal.ukfRatePerMin, fd))
            while (onsetRing.size > 10) onsetRing.removeFirst()
        }
        // Marker: Knopf im FUSE-Tab, verfaellt nach MARKER_WINDOW_MIN von
        // selbst. Er ist ZUSTAND und wird je Zyklus frisch gelesen - nie
        // gecached, damit ein Druck sofort im naechsten Zyklus wirkt.
        // Fix-Pass 4 Nr. 16: primaer der ATOMARE Stempel (ts*10+Stufe), die
        // Einzel-Keys nur als Altbestand-Fallback - so sieht ein Zyklus nie
        // alten Timestamp mit neuer Stufe.
        val markerStamp = preferences.get(FuseLongKey.MealMarkerStamp)
        val markerTs = if (markerStamp > 0L) markerStamp / 10L else preferences.get(FuseLongKey.MealMarkerArmedTs)
        val markerTier = if (markerStamp > 0L) (markerStamp % 10L).toInt().coerceIn(0, 2)
        else preferences.get(FuseLongKey.MealMarkerTier).toInt().coerceIn(0, 2)
        val tierEnvelopeU = when (markerTier) {
            0    -> cfg.primeEnvelopeSmallU
            2    -> cfg.primeEnvelopeLargeU
            else -> cfg.primeEnvelopeU
        }
        // Episodenbudgets leben im Ledger-Adapter und ueberleben Neustarts
        // (Audit R95, Fix 3) - nur der Reset-ANLASS steht hier: ein neuer
        // armedTs ist eine neue Episode mit voller Huelle.
        val episodes = ledger.episodes
        if (markerTs != episodes.primeArmedTs) {
            episodes.primeArmedTs = markerTs
            episodes.primeSpentU = 0.0
            episodes.primeWindowStartTs = 0L
            // Fix 7: neue Marker-Episode -> Wende-Latch der Sonderrechte neu.
            episodes.markerTurnTs = 0L
            episodes.markerRiseSeen = false
        }
        if (markerTs != episodes.mealArmedTs) {
            episodes.mealArmedTs = markerTs
            episodes.mealDeliveries.clear()
        }
        val mealMarkerActive = markerTs > 0 &&
            computeTs - markerTs in 0..(OnsetChannel.MARKER_WINDOW_MIN * 60_000L)

        // GAS-VOR-BREMSE NUR FUER ERKLAERTES WISSEN (08.08., Fruehstueckstest):
        // das Rebound-Fenster schuetzt vor dem Jagen UNANGEKUENDIGTER Hypo-
        // Gegenesser. Ein gedrueckter Marker IST die Ankuendigung - er
        // entwaffnet die Heuristik-Bremse (Ratio-Deckel, Totband, tau-
        // Kuerzung). Guard-Floor, Freigabe-Tor und Huellen bleiben unberuehrt.
        // Fix 7 (Audit R95 NEU-01/02, Tonis Entscheid "bis zur Wende, max
        // 45 min"): die Marker-SONDERRECHTE (Rebound-Entwaffnung, Prior,
        // Marker-Zweig des Fensters) enden mit der nachhaltigen Wende oder
        // nach MarkerScope.BOOST_MAX_MIN - der Fruehstueckssturz vom 08.08.
        // fiel sonst noch in die entwaffnete Zone. KONTEXT (Stufen-Huelle,
        // Onset-Evidenz, Anzeige) behaelt die vollen 90 min.
        // Fix-Pass 2 Nr. 4 (NEU-BS-05): eine Wende zaehlt erst NACH einer
        // Anstiegsphase. Sonst verriegelte der Marker-Druck IM FALL (Essen
        // im Tief - der Fruehstuecksfall vom 08.08.!) die Sonderrechte
        // sofort, und Gas-vor-Bremse waere genau dort tot, wofuer es
        // gebaut wurde.
        if (mealMarkerActive && !episodes.markerRiseSeen &&
            signal.ukfRatePerMin.isFinite() && signal.ukfRatePerMin >= cfg.riseRampLowR
        ) episodes.markerRiseSeen = true
        if (mealMarkerActive && episodes.markerRiseSeen && episodes.markerTurnTs == 0L &&
            signal.ukfRatePerMin.isFinite() && signal.ukfRatePerMin <= -cfg.riseRampLowR
        ) episodes.markerTurnTs = signal.sourceTs
        val markerBoost = mealMarkerActive &&
            MarkerScope.boostActive(markerTs, computeTs, episodes.markerTurnTs, cfg.markerBoostMaxMin)

        val reboundWindow = reboundRaw && !markerBoost
        val reboundSuppressedByMarker = reboundRaw && markerBoost

        val onset = OnsetChannel.evaluate(
            OnsetChannel.Input(
                enabled = cfg.onsetChannelEnabled,
                samples = onsetRing.toList(),
                rSignedMgdlPerMin = band.mean,
                thresholdMgdlPerMin = cfg.riseRampLowR,
                q1Outlier = signal.q1Outlier,
                mealMarkerActive = mealMarkerActive,
                envelopeU = cfg.onsetEnvelopeU,
                spentU = episodes.onsetSpentU,
            )
        )

        // FENSTER-TRIO: Marker ODER offene Onset-Episode ODER Kinematik
        // (r und schnelle Rate beide ueber der Rampen-Unterkante) oeffnen das
        // Mahlzeit-Fenster fuer 10 min rollierend; nachhaltige Wende schliesst.
        run {
            val ukfNow = signal.ukfRatePerMin
            val kinematic = signal.rSigned?.let { it >= cfg.riseRampLowR } == true &&
                ukfNow.isFinite() && ukfNow >= cfg.riseRampLowR
            if (markerBoost || onset.active || kinematic)
                mealWindowHoldUntil = signal.sourceTs + 10 * 60_000L
            if (ukfNow.isFinite() && ukfNow <= -cfg.riseRampLowR)
                mealWindowHoldUntil = signal.sourceTs
        }
        val mealWindow = signal.sourceTs < mealWindowHoldUntil

        // Fix 7: der Marker-PRIOR auf der unteren Bahn haengt an den
        // Sonderrechten (markerBoost), nicht am 90-min-Kontextfenster.
        // ERKLAERTE ABSORPTION (Toni 09.08.): der Marker erzeugt BEDARF auf der
        // Mittelbahn, ab Knopfdruck und ohne auf den Anstieg zu warten - im FCL
        // ist Warten strukturell zu spaet (Insulin ~20 min Anlauf, Carbs nicht).
        // Das Guard-Veto der prior-freien Bahn bleibt: bei vollem Insulinbuch
        // gibt es trotz Ankuendigung nichts. Der Kredit rechnet mit dem REST
        // der Stufen-Huelle - was die Episode schon geliefert hat (Sofort-
        // Freigabe ODER Rampe), zieht ihn herunter: EINE Huelle fuer beide
        // Pfade, die Erklaerung verbraucht sich selbst.
        val mealDeliveredU = if (markerTs > 0) episodes.mealDeliveries.sumOf { it.second } else 0.0
        val declaredDrive = if (markerBoost) MarkerScope.declaredAbsorptionDriveMgdlPerMin(
            envelopeU = tierEnvelopeU,
            deliveredU = mealDeliveredU,
            isfMgdlPerU = profile.getIsfMgdlTimeFromMidnight(MidnightUtils.secondsFromMidnight(signal.sourceTs)),
            windowMin = cfg.absorptionCreditWindowMin.toDouble(),
        ) else 0.0

        val built = when (val b = CoreInputGuard.build { buildPredictorInput(signal, profile, cfg, band, bolusActivityUPerMin, if (onset.active) onset.driveMgdlPerMin else null, reboundWindow, markerBoost, declaredDrive, pending) }) {
            is CoreInputGuard.Outcome.Built  -> b.value ?: return abort("input incomplete", signal, cfg, step)
            is CoreInputGuard.Outcome.Failed -> return abort("input: ${b.failure.detail}", signal, cfg, step)
        }

        val prediction = when (val p = TrajectoryCore.predict(built.input)) {
            is PredictorOutcome.Ok       -> p.result
            is PredictorOutcome.Rejected -> return abort("predictor: ${p.reason} ${p.detail}", signal, cfg, step)
        }

        // ZWEITE Bahn aus der schnellen Rate. Sie nutzt DASSELBE IOB-Array und
        // dieselben ISF-Slots — nur der Antrieb ist ein anderer. Der Aufwand ist
        // eine reine Arithmetikschleife ueber die Punkte, kein zusaetzlicher
        // Datenbankzugriff.
        //
        // FIX-PASS 4 Nr. 14 (Alt-Finding F-P1-01): eine AKTIVIERTE, aber nicht
        // berechenbare Sicherheitsbremse ist jetzt ein ABBRUCH, kein stilles
        // "bremst nicht" - sonst koennte schlechtere Signalqualitaet mehr
        // Insulin erlauben als gute (die Bremse waere weg, die Dosis groesser).
        // Der Ausfall ist ein Signalqualitaets-Befund und kostet einen Zyklus.
        val restraint = if (!cfg.fastRestraintEnabled) null else
            (fastDrive(signal) ?: return abort("fast restraint enabled but drive not computable", signal, cfg, step)).let { fast ->
                val fi = built.input.copy(
                    // DERSELBE Abschlag wie auf der Hauptbahn: auch die schnelle
                    // Untergrenze darf den bolusgedeckten Stoerungsanteil nicht
                    // als gesichert fortschreiben.
                    drive = DriveEstimate(
                        fast, fast - built.discount.termMgdlPerMin, null,
                        // Das WIRKSAME Lambda, nicht das Grund-Lambda: sonst
                        // truege die Methodenkennung im Mahlzeitenfenster eine
                        // Zahl, die nicht gerechnet wurde.
                        DriveDiscount.methodId("UKF_RATE_RESTRAINT_V1", built.discount.lambda),
                    ),
                )
                (TrajectoryCore.predict(fi) as? PredictorOutcome.Ok)?.result
                    ?: return abort("fast restraint enabled but trajectory rejected", signal, cfg, step)
            }

        // ---- 4 Menge -------------------------------------------------------
        val pumpDescription = activePlugin.activePump.pumpDescription
        val bolusStep = pumpDescription.bolusStep
        // 0.0 waere GEFAEHRLICHER als ein Block: floor(x/0)*0 ergibt NaN, und
        // NaN < 0.0 ist false — der Zyklus fiele durch statt zu sperren.
        if (!bolusStep.isFinite() || bolusStep <= 0.0) return abort("bolusStep=$bolusStep", signal, cfg, step)

        val maxIobU = constraintsChecker.getMaxIOBAllowed().value()
        val iobTotal = iobCobCalculator.calculateFromTreatmentsAndTemps(computeTs, profile)
        val isf = profile.getIsfMgdlTimeFromMidnight(MidnightUtils.secondsFromMidnight(signal.sourceTs))
        val (target, targetSource) = activeTarget(profile, computeTs)

        val state = when (
            val s = CoreInputGuard.build {
                FuseController.State(
                    health = step.health,
                    safetyHold = step.safetyReasons.isNotEmpty(),
                    phase = step.phase,
                    netIobU = iobTotal.iob,
                    bolusIobU = iobTotal.iob - iobTotal.basaliob,
                    basalIobU = iobTotal.basaliob,
                    // Wirft bei unsinnigem Prozentsatz — deshalb im Guard.
                    iobThU = IobThreshold.fromPercent(cfg.iobThPercent.toDouble(), maxIobU),
                    maxIobU = maxIobU,
                    targetMgdl = target,
                    isfMgdlPerU = isf,
                    smbRatioCorrection = cfg.smbRatio,
                    smbRatioRise = cfg.smbRatioRise,
                    // Bei aktivem Onset-Kanal laeuft die Rampe auf dem
                    // gehobenen Antrieb - sonst haette der Kanal die Bahn
                    // gehoben, aber die Ratio stuende noch auf Korrektur.
                    reboundWindow = reboundWindow,
                    reboundDeadbandMgdl = if (cfg.reboundDeadbandEnabled) cfg.reboundDeadbandMgdl else 0.0,
                    nightWindow = cfg.nightDeadbandEnabled && NightWindow.isNight(
                        MidnightUtils.secondsFromMidnight(signal.sourceTs), cfg.nightStartMin, cfg.nightEndMin
                    ),
                    nightDeadbandMgdl = if (cfg.nightDeadbandEnabled) cfg.nightDeadbandMgdl else 0.0,
                    markerBoost = markerBoost,
                    reboundSuppressedByMarker = reboundSuppressedByMarker,
                    mealWindow = mealWindow,
                    rSignedMgdlPerMin = onset.driveMgdlPerMin?.takeIf { onset.active }
                        ?.let { d -> maxOf(signal.rSigned ?: d, d) } ?: signal.rSigned,
                    riseRampLowRPerMin = cfg.riseRampLowR,
                    riseRampHighRPerMin = cfg.riseRampHighR,
                    pumpIncrementU = bolusStep,
                    maxSmbU = cfg.maxSmbU,
                    // pumpBusy gehoert NICHT in den Regler, sondern
                    // ausschliesslich in die TBR-Tabelle: dort unterdrueckt es
                    // die ANFORDERUNG, ohne den Sicherheitsgrund und seinen
                    // Alarm zu loeschen. Im Regler wuerde es stattdessen die
                    // ganze Bewertung ersetzen.
                    pumpBusy = false,
                )
            }
        ) {
            is CoreInputGuard.Outcome.Built  -> s.value
            is CoreInputGuard.Outcome.Failed -> return abort("state: ${s.failure.detail}", signal, cfg, step)
        }

        // Schwanzhaftung. C1/C2: pessimistisch ueber Haupt- UND Bremsbahn und
        // PRIOR-FREI - ein Marker-Prior darf kein Schwanzbudget erzeugen
        // (Codex H1/H2). Die Bahn traegt seit C3 ausserdem die Wirkung der
        // Transportmenge BIS zum Horizont.
        //
        // C4a: was von der Transportmenge NACH dem Horizont noch kommt, ist der
        // zweite Haftungsterm (R79-F4). Ohne Einheitskern ist die Restwirkung
        // unbekannt - dann rechnet TailLiability mit der VOLLEN Menge statt mit
        // 0. Ist nichts unterwegs, ist der Term exakt 0 und der Vermerk sagt
        // "gerechnet", nicht "geschaetzt".
        //
        // C3-01: die Restwirkungen werden JE POSTEN gerechnet und erst dann
        // summiert (TailLiability.sumOf). Und je Posten gilt der SPAETESTE
        // plausible Anker, wo er die groessere Restwirkung ergibt - fuer die
        // Haftung NACH H ist spaet die pessimistische Seite, waehrend die BAHN
        // oben am fruehesten Anker haengt. Zwei Enden derselben plausiblen
        // Lieferspanne, jedes dort, wo es sperrt (s. tailTransportDose).
        val liabilityHorizonTs = signal.sourceTs + cfg.liabilityHorizonMin * 60_000L
        val tailBase = TailLiability.Input(
            lowerBgAtH = minSafetyHorizonLowerOf(prediction, restraint),
            existingIobAtH = built.iobAtH,
            isfTailMgdlPerU = built.isfTail,
            tailFloorMgdl = cfg.tailFloorMgdl,
            tailRecoveryU = cfg.tailRecoveryU,
            transport = TailLiability.sumOf(
                transport.map { tailTransportDose(it, kernel(), liabilityHorizonTs) }
            ),
        )
        // Der KAPPEN-Bericht: ohne Kandidat, denn der ist genau die Groesse, die
        // er begrenzen soll. Die finale Pruefung MIT beschlossener Menge steht
        // unten (C4b).
        val tail = if (!cfg.tailGuardEnabled) null else TailLiability.evaluate(tailBase)

        val baseDecision = FuseController.decide(
            state, prediction,
            FuseController.Limits(guardFloorMgdl = cfg.guardFloorMgdl, releaseHorizonMin = cfg.releaseHorizonMin),
            tail,
            restraint,
            onsetCapU = if (onset.active) onset.remainingU else null,
        )

        // KANDIDATENPRUEFUNG (Audit 07.08.: 0,30 U bei ISF 95 senken die Bahn
        // 4,3 mg/dl @30 min / 21,6 @120 min - der Baseline-Guard sieht das
        // strukturell nicht). Der Ratio-Pfad hat VORGESCHLAGEN; die Suche
        // prueft den Vorschlag MIT seiner Wirkung und darf ihn ueber
        // CandidateGate nur beschneiden. Kernel-/Technik-Ausfaelle lassen die
        // Basis hier zunaechst unveraendert (candidateGap im Export) - die
        // FINALE Wirkungspruefung nach dem Lift nullt sie dann fail-closed
        // (Fix-Pass 4 Nr. 4): unverifiziert verlaesst keine Menge den Zyklus.
        var candidateResult: CandidateSearch.Result? = null
        var candidateGap: String? = null
        // FIX 6b (Re-Audit c750169, 6.4): Band und Kernel-Bau stehen VOR der
        // Suche, weil auch die Sofort-Freigabe dieselbe Wirkungspruefung
        // braucht - gerade dann, wenn die Basis NO_DEMAND war und die Suche
        // deshalb nie lief.
        val candidateBand = CandidateSearch.Band(
            releaseTargetLowMgdl = target - CandidateGate.RELEASE_LOW_MARGIN_MGDL,
            releaseTargetHighMgdl = target,
            demandDeadbandMgdl = CandidateGate.DEMAND_DEADBAND_MGDL,
            guardFloorMgdl = cfg.guardFloorMgdl,
            releaseHorizonMin = cfg.releaseHorizonMin,
            liabilityHorizonMin = cfg.liabilityHorizonMin,
        )
        // Der Kern steht jetzt WEITER OBEN (C3) und wird hier nur abgerufen -
        // gebaut wird er beim ersten Verbraucher, gemerkt fuer alle weiteren.
        val vetted = if (baseDecision.smbU <= 0.0) baseDecision else {
            val k = kernel()
            if (k == null) {
                candidateGap = kernelReject
                baseDecision
            } else {
                candidateResult = CandidateSearch.search(
                    prediction = prediction,
                    kernel = k,
                    isfSlots = built.input.isfSlots,
                    band = candidateBand,
                    caps = CandidateSearch.Caps(
                        // Budgetpolicy bis KC2-53 offen: maxSmb als
                        // neutraler Platzhalter, bindet nie unterhalb der
                        // echten Kappen. Der Ledger-Anteil kommt ueber die
                        // Headrooms herein (Vertrag der Suche): die noch
                        // nicht im IOB nachgewiesene Transportmenge zaehlt
                        // wie bereits vorhandenes Insulin.
                        remainingReleaseBudgetU = cfg.maxSmbU,
                        // C3-02: die MODELLIERTE Transportmenge, nicht der
                        // Ledgerwert - sie ist per Vertrag nie kleiner, die
                        // Headrooms koennen dadurch nur enger werden.
                        effectiveIobThHeadroomU = state.iobThU - state.capIobU - transportModelledU,
                        // Tonis IOB-Referenz-Regel: Dosier-Grenzen auf
                        // capIob - negatives Basal-Delta ist kein Budget.
                        effectiveMaxIobHeadroomU = state.maxIobU - state.capIobU - transportModelledU,
                        pumpIncrementU = bolusStep,
                        maxSmbU = cfg.maxSmbU,
                    ),
                    ledgerHold = ledgerView.hold,
                    // C1 (Codex D/H1): die Bremsbahn wird MIT der
                    // Kandidatenwirkung geprueft, nicht nur ohne. Bisher
                    // sah nur der Baseline-Guard sie; Hauptbahn-lower 95 /
                    // Bremsbahn-lower 74 / Wirkung -5 / Boden 70 passierte.
                    restraint = restraint,
                )
                CandidateGate.apply(baseDecision, candidateResult)
            }
        }

        // Sofort-Freigabe: Plan aus derselben Momentaufnahme, Anhebung NUR
        // wenn der Basisentscheidung nichts als Bedarf fehlte. Sperren und
        // Deckel gewinnen in PrimeRelease.lift unveraendert.
        val primePlan = PrimeRelease.plan(
            PrimeRelease.Input(
                enabled = cfg.primeReleaseEnabled,
                mealMarkerActive = mealMarkerActive,
                armedTsMs = markerTs,
                windowStartTsMs = episodes.primeWindowStartTs,
                nowMs = computeTs,
                envelopeU = tierEnvelopeU,
                spentU = episodes.primeSpentU,
                // C1 + C2 (Codex H1/H2, K2 Punkte 6/8) ERSETZEN die frueher hier
                // stehende analytische Entzirkularisierung: statt den Prior-Hub
                // am RELEASE-Horizont (30 min) abzuziehen, rechnet die Clearance
                // jetzt gegen die punktweise prior-freie Bahn - und zusaetzlich
                // gegen die Bremsbahn. Der alte Abzug war unvollstaendig, weil
                // das Minimum typisch am HAFTUNGS-Horizont liegt: 16,53 mg/dl
                // (30 min) gegen 36,32 mg/dl (120 min) bei prior 0,7 / tau 60,
                // also bis ~19,8 mg/dl selbstlizenzierter Kredit.
                safetyMinLowerMgdl = minSafetyLowerOf(prediction, restraint),
                guardFloorMgdl = cfg.guardFloorMgdl,
                isfMgdlPerU = isf,
                pumpIncrementU = bolusStep,
            )
        )
        val lifted = PrimeRelease.lift(
            vetted, primePlan, state,
            tailHeadroomU = tail?.takeIf { it.usable }?.headroomU,
            onsetCapU = if (onset.active) onset.remainingU else null,
            // Fix-Pass 2 Nr. 2: dieselbe Ledger-Korrektur wie in den
            // Such-Headrooms - sonst finanziert der NO_DEMAND->Lift-Pfad
            // In-Flight-Mengen doppelt.
            transportCommitmentU = transportModelledU,
        )
        // FIX-PASS 4 Nr. 4 (Codex R4-04, Control-Audit-Invariante): KEINE
        // finale positive Dosis ohne erfolgreiche Wirkungspruefung. Das
        // verallgemeinert Fix 6b: nicht nur die Prime-Anhebung, JEDE finale
        // Menge > 0 muss verifyGuardFloor bestehen - damit ist auch der alte
        // Kernel-Ausfall-fail-open-Pfad (Basis passierte unverifiziert) tot.
        // Transiente Technik-Ausfaelle kosten genau einen 1-min-Zyklus.
        val kernelFinal = kernel()

        // C4b: DIE WIRKUNG DER BESCHLOSSENEN MENGE GEHOERT IN DIESELBE
        // GLEICHUNG WIE DIE HAFTUNG.
        //
        // Der Guard prueft bis liabilityHorizonMin (Default 120 min), Tonis
        // Insulin wirkt ueber DIA 9 h - die zweite Wirkhaelfte bewertet nur der
        // Schwanz. Der kannte den Kandidaten bisher nicht (`noCandidate`), weil
        // der Aufrufer ihn erst nach der Wahl kennt. Geloest wird das mit ZWEI
        // BEWERTUNGEN DESSELBEN SCHWANZES statt einer zweiten API: oben die
        // Kappe fuer die Suche, hier die finale Pruefung der beschlossenen
        // Menge. Der Kandidat ist damit schlicht ein weiterer Term - die
        // Kopplung bleibt "TailLiability rechnet mit Zahlen".
        //
        // Die Wirkung selbst kommt aus der Kandidatensuche, wo die
        // Integrationsregel ohnehin steht; zwei Rechnungen fuer dieselbe
        // Groesse waeren zwei Wahrheiten.
        val candidateEffectAtHPerU = kernelFinal?.let {
            CandidateSearch.effectPerUAtLiabilityHorizon(prediction, it, built.input.isfSlots, candidateBand)
        }
        fun tailWith(u: Double): TailLiability.Report? {
            if (tail == null) return null
            // Ohne berechenbare Wirkung ist die konservative Annahme, dass die
            // ganze Menge bis H auf die Bahn durchschlaegt UND am Horizont noch
            // haftet (Dose ohne Restwirkung) - NICHT, dass sie nichts tut.
            val drop = if (u <= 0.0) 0.0 else candidateEffectAtHPerU?.times(u) ?: (u * tailBase.isfTailMgdlPerU)
            return TailLiability.evaluate(
                tailBase.copy(
                    lowerBgAtH = tailBase.lowerBgAtH - drop,
                    candidate = TailLiability.Dose(
                        amountU = u,
                        residualAtHU = if (u > 0.0) kernelFinal?.iobAt(liabilityHorizonTs, u) else 0.0,
                    ),
                )
            )
        }

        // C1/C2: DASSELBE Zeugnis wie in der Suche - beide Bahnen, prior-frei.
        // Das ist der Riegel, an dem KEINE positive Menge vorbeikommt: auch der
        // Ratio-Pfad bei Kernel-Ausfall und die Sofort-Freigabe laufen hier
        // durch. Ein optimistischerer Baseline-Guard weiter oben kann deshalb
        // keine Dosis mehr autorisieren, die hier durchfaellt.
        // C4: und er endet nicht am Haftungshorizont - was danach noch wirkt,
        // muss der Schwanz MIT dieser Menge tragen.
        fun finalVeto(u: Double): String? {
            if (kernelFinal == null) return CandidateSearch.Reject.MODEL_HORIZON_TOO_SHORT.name
            CandidateSearch.verifyGuardFloor(
                prediction, kernelFinal, built.input.isfSlots, candidateBand, u, restraint = restraint,
            )?.let { return it.name }
            tailWith(u)?.takeIf { it.usable && it.headroomU < -TAIL_VETO_EPS_U }?.let { return TAIL_VETO }
            return null
        }
        // REST-ZAEHLER (Toni 09.08.): was die Pumpenschritt-Rasterung verwirft,
        // wird aufgeschoben statt vernichtet - sonst faellt jede Absicht unter
        // 0,05 U dauerhaft aus (insulinReq 0,24 x Ratio 0,15 = 0,036 U), und
        // FUSE hat keine positive TBR, die den Rest wie bei autoISF traegt.
        // Freigegeben wird immer nur EIN ganzer Schritt, und der geht unten
        // durch dieselbe Wirkungspruefung wie jede andere Menge.
        // (1) NUR RATIO-RESTE (Tonis Vertrag 09.08.): ein Rest, der aus einer
        // SICHERHEITSgrenze stammt (iobTH, maxIOB, Schwanz, Onset-Huelle,
        // Freigabebudget), ist kein aufgeschobener Bedarf, sondern eine
        // Absage - der darf nie gesammelt werden. Aufgeschoben wird
        // ausschliesslich, was die Ratio-Teilung selbst abgeschnitten hat.
        val ratioIsBinding = lifted.bindingLimit == "smbRatio"
        // (2) DER SCHRITT MUSS IN JEDE GRENZE PASSEN: `raw` lag unter dem
        // Pumpenschritt, also koennen andere Kappen zwischen `raw` und dem
        // Schritt liegen. Ein voller Schritt wird nur freigegeben, wenn ALLE
        // Mengengrenzen ihn tragen - sonst waere der Zaehler ein Weg an
        // Sicherheitskappen vorbei.
        val otherCapsU = minOf(
            cfg.maxSmbU,
            state.iobThU - state.capIobU - transportModelledU,
            state.maxIobU - state.capIobU - transportModelledU,
            tail?.takeIf { it.usable }?.headroomU ?: Double.MAX_VALUE,
            if (onset.active) onset.remainingU else Double.MAX_VALUE,
        )
        // SUB-01 (P0, Codex Fix-Pass-5-Closure): die Kappen muessen die
        // ENDSUMME tragen, nicht den Zusatzschritt allein. Vorher galt
        // "ein Schritt passt" - danach wurde aber lifted.smbU + Schritt
        // ausgegeben: bei Basis 0,05, Kappe 0,06 und Schritt 0,05 waren das
        // 0,10 U gegen eine 0,06er Kappe. finalVeto prueft nur die Bahn,
        // nicht die Mengenkappen - der Riegel muss hier sitzen.
        val subStepDiscard = ledgerView.hold || reboundWindow || !ratioIsBinding ||
            !otherCapsU.isFinite() || lifted.smbU + bolusStep > otherCapsU + 1e-12 ||
            (lifted.block != FuseController.Block.NONE && lifted.block != FuseController.Block.BELOW_PUMP_INCREMENT) ||
            (signal.ukfRatePerMin.isFinite() && signal.ukfRatePerMin < 0.0) ||
            !signal.ukfRatePerMin.isFinite() ||
            lifted.insulinReqU <= 0.0
        val subStep = SubStepAccumulator.step(
            carriedU = subStepCarryU,
            desiredU = lifted.desiredBeforeStepU,
            steppedU = lifted.smbU,
            pumpIncrementU = bolusStep,
            discard = subStepDiscard,
        )
        subStepCarryU = subStep.carryU
        val withCarry = if (subStep.releaseU <= 0.0) lifted else lifted.copy(
            smbU = lifted.smbU + subStep.releaseU,
            block = FuseController.Block.NONE,
            bindingLimit = lifted.bindingLimit + "|subStep",
        )

        val verifiedLift = if (withCarry.smbU <= 0.0) withCarry else {
            when {
                finalVeto(withCarry.smbU) == null -> withCarry
                // Anhebung fiel durch: zurueck auf die kleinere Basis, aber
                // nur, wenn AUCH sie das Zeugnis besteht.
                withCarry.smbU > vetted.smbU + 1e-9 && vetted.smbU > 0.0 && finalVeto(vetted.smbU) == null ->
                    vetted.copy(bindingLimit = vetted.bindingLimit + "|primeVeto:${finalVeto(withCarry.smbU)}")
                else -> {
                    // Die Wirkungspruefung hat die Menge verworfen - damit ist
                    // auch der aufgeschobene Rest nicht mehr gewollt (Tonis
                    // Vertrag: bei Nichtlieferung KEINE automatische Gutschrift).
                    subStepCarryU = 0.0
                    withCarry.copy(
                        smbU = 0.0,
                        block = FuseController.Block.CANDIDATE,
                        bindingLimit = "finalVerify:${finalVeto(withCarry.smbU)}",
                    )
                }
            }
        }
        // HART NACH dem Lift (Audit R95, Fix 3): Ratio-Pfad (Kernel-Ausfall)
        // und Sofort-Freigabe laufen am LEDGER_HOLD-Reject der Suche vorbei -
        // ohne diesen Riegel waere der Hold genau ueber die Pfade umgehbar,
        // die ohne Wirkungspruefung dosieren.
        val held = LedgerHoldGate.apply(verifiedLift, ledgerView.hold)
        // C4c: Anzeige, Export und RT-Grund bekommen den FINALEN Schwanzbericht -
        // den mit der Menge, die wirklich hinausgeht. Auch eine beschlossene
        // NULL ist eine Entscheidung und kein fehlender Term; erst damit steht
        // dort 3/3 statt 1/3. Er ersetzt nur den BERICHT, nie die Menge - die
        // hat der Riegel oben bereits entschieden.
        val decision = tailWith(held.smbU)?.let { held.copy(tail = it) } ?: held
        // LIEFERBARE Minuten (09.08.): solange nur die Clearance sperrt,
        // schiebt der Fensterstart nach - eine Freigabe, die nie erteilbar war,
        // darf nicht verfallen. Absolut gekappt in PrimeRelease selbst.
        // Nur bei CLEARANCE: DISABLED/NO_MARKER/ENVELOPE_SPENT/NOT_FINITE sind
        // keine "gesperrt, aber gewollt"-Zustaende, dort waere das Schieben
        // eine stille Verlaengerung ohne Grund.
        if (primePlan.reason == "CLEARANCE") episodes.primeWindowStartTs = computeTs

        val primeWindowOpen = mealMarkerActive && markerTs > 0 &&
            computeTs - maxOf(markerTs, episodes.primeWindowStartTs) < PrimeRelease.WINDOW_MIN * 60_000L &&
            computeTs - markerTs < PrimeRelease.WALL_CEILING_MIN * 60_000L

        // ---- 5 Kanal -------------------------------------------------------
        // Audit R95 NEU-05: die PROZESSIERTE Sicht inkl. konvertierter
        // Extended-Boli - erst damit ist der FAKE_EXTENDED-Vertrag der
        // TbrPolicy (nur lesen, nie ersetzen; C8-SMB-Sperre) erreichbar.
        val runningTbr = processedTbrEbData.getTempBasalIncludingConvertedExtended(computeTs)
        val current = runningTbr?.let {
            TbrPolicy.Current(
                // Prozent-TBR wird HIER absolut gemacht — der Kern sieht nie
                // beides in derselben Zahl.
                absoluteRateUPerH = it.convertedToAbsolute(computeTs, profile),
                remainingMin = it.plannedRemainingMinutes,
                sourceType = if (it.type == app.aaps.core.data.model.TB.Type.FAKE_EXTENDED) TbrPolicy.SourceType.FAKE_EXTENDED
                else TbrPolicy.SourceType.TEMP_BASAL,
            )
        }
        val combined = FuseTbrTranslator.combine(
            decision = decision,
            current = current,
            scheduledBasalUPerH = profile.getBasal(computeTs),
            cfg = TbrPolicy.Config(basalStepUPerH = pumpDescription.basalStep),
            fault = if (tempBasalFallback) TbrPolicy.FaultCode.TEMP_BASAL_FALLBACK else TbrPolicy.FaultCode.NONE,
            pumpBusy = pumpBusy(),
        )

        // GATE-WIRKSAME Menge (Audit R95, Fix 3): Huellen und Bilanz belasten
        // nur, was nach TBR-Tabelle (smbBlocked) UND Pumpen-Gate wirklich
        // hinausgeht. Vorher zaehlte der Vor-Combine-Wert - ein blockierter
        // Zyklus belastete die Huelle, ohne dass eine Einheit floss
        // (Zaehlfalle rowId, 06.08.).
        val actuatedU = if (gate.allowed) combined.decision.smbU else 0.0
        if (primeWindowOpen) episodes.primeSpentU += actuatedU

        // Huellen-Buchfuehrung: verbraucht wird nur, was der offene Kanal
        // freigegeben hat; nach REARM_QUIET_MIN geschlossenen Minuten wird die
        // Huelle neu bewaffnet.
        if (onset.active) {
            episodes.onsetSpentU += actuatedU
            episodes.onsetQuietMin = 0
        } else if (episodes.onsetSpentU > 0.0) {
            episodes.onsetQuietMin += 1
            if (episodes.onsetQuietMin >= OnsetChannel.REARM_QUIET_MIN) {
                episodes.onsetSpentU = 0.0
                episodes.onsetQuietMin = 0
            }
        }

        // FIX-PASS 4 Nr. 19: nur im AKTIVEN Marker-Fenster sammeln (ein
        // verwaister markerTs sammelte sonst unbegrenzt weiter) und hart bei
        // 400 kappen - der Lade-Validator lehnt Dateien > 500 ab, der
        // Schreiber muss strikt darunter bleiben, sonst sperrt FUSE sich
        // selbst per recoveryHold aus der eigenen Datei aus.
        if (mealMarkerActive && actuatedU > 0.0) {
            episodes.mealDeliveries.addLast(signal.sourceTs to actuatedU)
            while (episodes.mealDeliveries.size > 400) episodes.mealDeliveries.removeFirst()
        }
        val mealStats = if (markerTs > 0 &&
            computeTs - markerTs <= (OnsetChannel.MARKER_WINDOW_MIN + 120) * 60_000L
        ) MealStats(
            sinceMin = ((computeTs - markerTs) / 60_000L).toInt(),
            totalU = episodes.mealDeliveries.sumOf { it.second },
            // T0-ANKER statt rollierender Fenster (Toni 08.08.): interessant
            // ist "wie viel stand nach 30/60 min ab Essensbeginn", nicht
            // "letzte 30 min" - die Werte wachsen bis zur Marke und frieren
            // dann von selbst ein (Filter auf Abgabezeit relativ zum Marker).
            first30U = episodes.mealDeliveries.filter { it.first - markerTs <= 30 * 60_000L }.sumOf { it.second },
            first60U = episodes.mealDeliveries.filter { it.first - markerTs <= 60 * 60_000L }.sumOf { it.second },
        ) else null

        val computeDurationMs = dateUtil.now() - computeTs
        return Outcome(
            computeDurationMs = computeDurationMs,
            mealStats = mealStats,
            insulinModel = built.input.trajectory.model,
            decision = combined.decision,
            tbr = combined.request,
            prediction = prediction,
            sourceTs = signal.sourceTs,
            computeTs = computeTs,
            health = step.health,
            gate = gate,
            reason = combined.reason,
            alarm = combined.alarm,
            bgMgdl = signal.q1,
            targetMgdl = target,
            targetSource = targetSource,
            signal = signal,
            band = band,
            discount = built.discount,
            onset = onset,
            prime = primePlan,
            candidate = candidateResult,
            candidateGap = candidateGap,
            policy = cfg,
            state = state,
            step = step,
            sensorEpoch = sensorEpoch,
            calibrationEpoch = calibrationEpoch,
            isfMgdlPerU = isf,
            iobU = iobTotal.iob,
            abortReason = null,
            // runCatching: eine scheiternde DB-Abfrage darf den Zyklus nicht
            // kosten - dann faellt nur der Ledger-Abgleich dieses Zyklus aus
            // und offene Commitments bleiben konservativ stehen.
            treatmentView = runCatching { buildTreatmentView(computeTs, profile.dia) }.getOrNull(),
        )
    }

    /** Fensteranfang der Behandlungssicht: DIA + Marge zurueck, zusaetzlich
     *  verlaengert bis vor den aeltesten Fakt einer noch offenen Ledger-Zeile.
     *  EINE Definition fuer Vollsicht UND Snapshot-Zeuge - zwei verschiedene
     *  Fensteranfaenge waeren zwei verschiedene Aussagen ueber dieselbe
     *  Datenbankabfrage. */
    private fun treatmentWindowStart(computeTs: Long, diaHours: Double): Long {
        val windowStart = computeTs - (diaHours * 3600_000.0).toLong() - IOB_MARGIN_MIN * 60_000L
        return minOf(windowStart, (ledger.oldestOpenTs() ?: Long.MAX_VALUE - 60_000L) - 60_000L)
    }

    /**
     * DER ZEUGE DES INCLUSION-VERTRAGS (C3-02, Codex Fix-Pass-5-Closure G.3).
     *
     * Er wird gelesen, BEVOR dieser Zyklus seine IOB-/Activity-Arrays baut.
     * Genau daran haengt der Nachweis: die Behandlungstabelle waechst
     * innerhalb eines Zyklus nur, also war alles, was der Zeuge sah, beim
     * Arraybau in der Datenbank. Ein Fakt, den er NICHT sah, gilt als
     * unentscheidbar - und ein unentscheidbarer Posten bleibt in voller Hoehe
     * Transport, statt aus beiden Sichten zu verschwinden.
     *
     * BEWUSST EINE ZWEITE LESUNG statt der spaeteren [buildTreatmentView]:
     * jene traegt mit `latestBolusTs` den C5-Guard der Identitaetsbindung, und
     * ein frueher gelesener Wert wuerde diesen Guard aufweichen. Die Abfrage
     * laeuft nur, wenn ueberhaupt ein Posten eine Buchung traegt.
     */
    private fun iobSnapshotWitness(computeTs: Long, diaHours: Double): TransportInclusion.IobSnapshotWitness {
        val from = treatmentWindowStart(computeTs, diaHours)
        val boluses = persistenceLayer.getBolusesFromTimeToTime(from, computeTs, true)
            .filter { it.isValid && it.type != BS.Type.PRIMING }
        return TransportInclusion.witnessOf(boluses.map { LedgerFacts.fact(it) }, fromTs = from, readAtTs = computeTs)
    }

    /** Die Treatment-Vollsicht fuer den Ledger-Abgleich - s. [TreatmentView]. */
    private fun buildTreatmentView(computeTs: Long, diaHours: Double): TreatmentView {
        val from = treatmentWindowStart(computeTs, diaHours)
        val boluses = persistenceLayer.getBolusesFromTimeToTime(from, computeTs, true)
            .filter { it.isValid && it.type != BS.Type.PRIMING }
        return TreatmentView(
            boluses = boluses,
            facts = boluses.map { LedgerFacts.fact(it) },
            snapshotHash = LedgerFacts.snapshotHash(boluses),
            latestBolusTs = boluses.maxOfOrNull { it.timestamp } ?: 0L,
            diaHours = diaHours,
        )
    }

    /**
     * Die SCHNELLE Rate fuer die Bremsbahn.
     *
     * `ukfRatePerMin` und nicht die rohe Sekante: der Kalman-Zustand ist am
     * 06.08. an der Wende korrekt ins Negative gedreht (-1,65), waehrend
     * `rSigned` noch +5,41 sagte — er ist also richtungstreu. Die rohe Sekante
     * war am selben Tag zwischen +2,2 und +5,95 unruhig; ueber einen Horizont
     * mit Zerfallssumme ~51 wuerde dieses Rauschen um zwei Groessenordnungen
     * verstaerkt.
     *
     * `null` heisst: keine Bremse. Nie ein Ersatzwert.
     */
    /**
     * Episodenzustand des Onset-Kanals. IM PROZESS, bewusst nicht persistiert:
     * nach einem Neustart ist der Ring leer und der Kanal faellt geschlossen
     * aus (fail-closed) - die Persistenz baut sich in 3 Minuten neu auf.
     */
    private val onsetRing = ArrayDeque<OnsetChannel.Sample>()

    // Die Episodenbudgets (primeSpent/onsetSpent/mealDeliveries) lagen bis
    // Audit R95 Fix 3 HIER als Prozessfelder - jetzt restartfest im
    // Ledger-Adapter (s. EpisodeBudgets).

    /** Letztes q1 < REBOUND_LOW_MGDL. Im Prozess: nach Neustart fehlt bis zu
     *  45 min Tief-Gedaechtnis (fail-open, dokumentiert) - die uebrigen
     *  Wachen (Guard, Abschlag, Clearance) stehen davon unberuehrt. */
    private var lastLowTs = 0L

    /** Mahlzeit-Fenster-Gedaechtnis (Fenster-Trio): jede erfuellte
     *  Oeffnungsbedingung verlaengert um 10 min; eine nachhaltige Wende
     *  (schnelle Rate <= -Schwelle) schliesst sofort. */
    private var mealWindowHoldUntil = 0L

    /**
     * Rest-Zaehler gegen die Quantisierungs-Totzone (Toni 09.08.).
     * BEWUSST PROZESSLOKAL: ein Neustart verwirft ihn - das ist die
     * konservative Richtung (aufgeschobene Absicht geht verloren, nie Insulin)
     * und spart eine Persistenz-Kopplung. Sammeln, bei einem vollen
     * Pumpenschritt freigeben, und dieser Schritt laeuft durch dieselbe
     * Pruefkette wie jede andere Menge.
     */
    private var subStepCarryU = 0.0

    /**
     * Den Puls-Uebertrag von AUSSEN verwerfen.
     *
     * SUB-02 Rest (Codex Re-Review 603a15a): `abort()` deckt jeden Ausgang
     * INNERHALB von [run] ab - eine Ausnahme, die bis zum Plugin
     * durchschlaegt, umgeht ihn aber. Der Uebertrag ist eine Zusage an einen
     * Zyklus, der nicht zu Ende gerechnet wurde; er darf den naechsten nicht
     * finanzieren. Idempotent, damit der Aufrufer nicht wissen muss, ob
     * `abort()` schon gegriffen hat.
     */
    fun discardSubStepCarry() {
        subStepCarryU = 0.0
    }

    data class MealStats(val sinceMin: Int, val totalU: Double, val first30U: Double, val first60U: Double)

    private fun fastDrive(signal: FuseSignalSource.Signal): Double? {
        val raw = signal.ukfRatePerMin
        val a = signal.activityAtAnchor
        val isf = signal.isfAtAnchor
        if (!raw.isFinite() || !a.isFinite() || !isf.isFinite()) return null
        // BGI-BEREINIGUNG, und sie ist tragend: `rSigned` ist die Steigung der
        // um die Insulinwirkung bereinigten Reihe, `ukfRatePerMin` die der
        // ROHEN. Beide sind Antriebe im selben Sinn nur nach dieser Korrektur —
        // sonst zieht TrajectoryCore die Insulinwirkung ein zweites Mal ab
        // (es addiert bgiRate = -activity*isf selbst) und die Bremsbahn faellt
        // bei hohem IOB drastisch zu tief.
        //
        // Vorzeichen: bgiRate = -activity*isf, also
        //   d/dt(bereinigt) = d/dt(roh) - bgiRate = roh + activity*isf.
        return raw + a * isf
    }

    /** Sensorwechsel als Therapieereignis. Fehlt es, ist 0 die ehrliche
     *  Antwort: "kein Embargo bekannt" — nicht "gerade gewechselt". */
    private fun sensorEpoch(): Long =
        persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp ?: 0L

    /** Beginn der laufenden Kalibrierung. `-1` ist der Default des Keys und
     *  heisst "nie kalibriert" — er darf nicht als Zeitstempel durchgereicht
     *  werden, sonst laege der Kalibrierbeginn 1970 und die Blindzeit waere
     *  immer vorbei. */
    private fun calibrationEpoch(): Long =
        preferences.get(LongKey.FslCalibrationStart).takeIf { it > 0L } ?: 0L

    /**
     * Das AKTIVE Ziel: TempTarget, sonst Profilziel.
     *
     * Umfang bewusst eng (K2 v0.2 §3, K2-P v0.1, R67-Q6/R68): FUSE LIEST die TT
     * und veraendert sie nie — kein Setzen, kein Stoppen, keine Verlaengerung.
     * Manuelle TTs sind unantastbar.
     *
     * Ausdruecklich NICHT uebernommen wird autoISFs Zusatzmechanik
     * (`high_temptarget_raises_sensitivity`, `low_temptarget_lowers_sensitivity`,
     * `half_basal_exercise_target`). Eine TT ist hier eine Zielaenderung, keine
     * Empfindlichkeitsaenderung — das ist der Unterschied zwischen einer
     * Nutzerabsicht und einer geerbten Algorithmusregel.
     *
     * Ein Wert ausserhalb der AAPS-Grenzen faellt auf das Profilziel zurueck,
     * statt eine unsinnige Zahl in den Regler zu lassen: `TT.target()` mittelt
     * low/high, und ein kaputter Datensatz aus einem Nightscout-Import wuerde
     * sonst ungeprueft zum Sollwert.
     */
    private fun activeTarget(profile: Profile, nowMs: Long): Pair<Double, String> {
        val tt = persistenceLayer.getTemporaryTargetActiveAt(nowMs) ?: return profile.getTargetMgdl() to "profile"
        val t = tt.target()
        val ok = t.isFinite() && t >= HardLimits.LIMIT_TEMP_TARGET_BG[0] && t <= HardLimits.LIMIT_TEMP_TARGET_BG[1]
        return if (ok) t to "TT" else profile.getTargetMgdl() to "profile(TT ${fmt(t)} out of range)"
    }

    private fun fmt(d: Double) = String.format(java.util.Locale.ROOT, "%.0f", d)

    /**
     * Eine arbeitende Pumpe bekommt keine zweite Anweisung.
     *
     * Bewusst NUR die Bolus-Pfade und nicht `size() > 0`: eine anstehende
     * Statusabfrage ist keine laufende Abgabe, und sie zum Block zu machen
     * hiesse, den Regler in jedem Zyklus mit Pumpenverkehr stillzulegen.
     */
    private fun pumpBusy(): Boolean =
        commandQueue.bolusInQueue() ||
            commandQueue.isRunning(Command.CommandType.BOLUS) ||
            commandQueue.isRunning(Command.CommandType.SMB_BOLUS)

    /**
     * Die Stellgroessen, mit denen DIESER Zyklus gerechnet hat. Oeffentlich,
     * weil der Zustandsexport den Politik-Hash aus genau diesen Werten bildet —
     * ein Hash aus spaeter neu gelesenen Preferences waere die Politik eines
     * anderen Zeitpunkts.
     */
    data class Config(
        val smbRatio: Double,
        val smbRatioRise: Double,
        /** Geteilte AAPS-Preference ApsSmbMaxIob - Fix-Pass 4 Nr. 17: sie war
         *  therapieaktiv (Constraint-Kette), stand aber nicht im Policy-Hash;
         *  zwei Laeufe mit verschiedenem maxIOB bekamen denselben Fingerprint. */
        val sharedMaxIobU: Double,
        val riseRampLowR: Double,
        val riseRampHighR: Double,
        val maxSmbU: Double,
        val guardFloorMgdl: Double,
        val iobThPercent: Int,
        val releaseHorizonMin: Int,
        val liabilityHorizonMin: Int,
        val driveTauMin: Int,
        /** Fenster des erklaerten Absorptions-Kredits [min] - s. FuseKeys. */
        val absorptionCreditWindowMin: Int,
        /** Dauer der Marker-Sonderrechte ab Druck [min]; 0 = aus. */
        val markerBoostMaxMin: Int,
        /** Nachtfenster [min ab Mitternacht] + Totband; Schalter getrennt. */
        val nightStartMin: Int,
        val nightEndMin: Int,
        val nightDeadbandMgdl: Double,
        val nightDeadbandEnabled: Boolean,
        val reboundDeadbandMgdl: Double,
        val reboundDeadbandEnabled: Boolean,
        val driveLowerQuantilePct: Int,
        val tailGuardEnabled: Boolean,
        val tailFloorMgdl: Double,
        val tailRecoveryU: Double,
        val fastRestraintEnabled: Boolean,
        val bolusShareLambda: Double,
        val onsetChannelEnabled: Boolean,
        val onsetEnvelopeU: Double,
        val primeReleaseEnabled: Boolean,
        val primeEnvelopeU: Double,
        val primeEnvelopeSmallU: Double,
        val primeEnvelopeLargeU: Double,
    )

    /**
     * ALLE Stellgroessen kommen aus den Einstellungen — im Regelpfad steht keine
     * einzige Zahl. Gelesen wird EINMAL je Zyklus: ein Wert, der sich zwischen
     * Guard und Freigabe aendert, waere eine Entscheidung aus zwei verschiedenen
     * Konfigurationen.
     */
    private fun readConfig() = Config(
        smbRatio = preferences.get(FuseDoubleKey.SmbRatio),
        smbRatioRise = preferences.get(FuseDoubleKey.SmbRatioRise),
        sharedMaxIobU = preferences.get(app.aaps.core.keys.DoubleKey.ApsSmbMaxIob),
        riseRampLowR = preferences.get(FuseDoubleKey.RiseRampLowR),
        riseRampHighR = preferences.get(FuseDoubleKey.RiseRampHighR),
        maxSmbU = preferences.get(FuseDoubleKey.MaxSmbU),
        guardFloorMgdl = preferences.get(FuseDoubleKey.GuardFloorMgdl),
        iobThPercent = preferences.get(FuseIntKey.IobThPercent),
        releaseHorizonMin = preferences.get(FuseIntKey.ReleaseHorizonMin),
        liabilityHorizonMin = preferences.get(FuseIntKey.LiabilityHorizonMin),
        driveTauMin = preferences.get(FuseIntKey.DriveTauMin),
        absorptionCreditWindowMin = preferences.get(FuseIntKey.AbsorptionCreditWindowMin),
        markerBoostMaxMin = preferences.get(FuseIntKey.MarkerBoostMaxMin),
        nightStartMin = preferences.get(FuseIntKey.NightStartMin),
        nightEndMin = preferences.get(FuseIntKey.NightEndMin),
        nightDeadbandMgdl = preferences.get(FuseDoubleKey.NightDeadbandMgdl),
        nightDeadbandEnabled = preferences.get(FuseBooleanKey.NightDeadbandEnabled),
        reboundDeadbandMgdl = preferences.get(FuseDoubleKey.ReboundDeadbandMgdl),
        reboundDeadbandEnabled = preferences.get(FuseBooleanKey.ReboundDeadbandEnabled),
        driveLowerQuantilePct = preferences.get(FuseIntKey.DriveLowerQuantilePct),
        tailGuardEnabled = preferences.get(FuseBooleanKey.TailGuardEnabled),
        tailFloorMgdl = preferences.get(FuseDoubleKey.TailFloorMgdl),
        tailRecoveryU = preferences.get(FuseDoubleKey.TailRecoveryU),
        fastRestraintEnabled = preferences.get(FuseBooleanKey.FastRestraintEnabled),
        bolusShareLambda = preferences.get(FuseDoubleKey.BolusShareLambda),
        onsetChannelEnabled = preferences.get(FuseBooleanKey.OnsetChannelEnabled),
        onsetEnvelopeU = preferences.get(FuseDoubleKey.OnsetEnvelopeU),
        primeReleaseEnabled = preferences.get(FuseBooleanKey.PrimeReleaseEnabled),
        primeEnvelopeU = preferences.get(FuseDoubleKey.PrimeEnvelopeU),
        primeEnvelopeSmallU = preferences.get(FuseDoubleKey.PrimeEnvelopeSmallU),
        primeEnvelopeLargeU = preferences.get(FuseDoubleKey.PrimeEnvelopeLargeU),
    ).also {
        // Die Preference-Grenzen gelten nur im Einstellungsdialog. Ein Wert aus
        // einem alten Import geht daran vorbei — deshalb hier nochmal, und zwar
        // werfend, damit der Guard daraus einen benannten Abbruch macht.
        require(it.smbRatio.isFinite() && it.smbRatio in 0.0..1.0) { "smbRatio=${it.smbRatio}" }
        require(it.smbRatioRise.isFinite() && it.smbRatioRise in 0.0..1.0) { "smbRatioRise=${it.smbRatioRise}" }
        // Audit R95 F-P1-05: Runtime mindestens so streng wie die UI-Grenzen
        // der Keys - Import/Migration umgeht den Einstellungsdialog.
        require(it.riseRampLowR.isFinite() && it.riseRampLowR in 0.0..5.0) { "riseRampLow=${it.riseRampLowR}" }
        require(it.riseRampHighR.isFinite() && it.riseRampHighR in 0.1..10.0) { "riseRampHigh=${it.riseRampHighR}" }
        require(it.riseRampHighR > it.riseRampLowR) { "riseRamp ${it.riseRampLowR}..${it.riseRampHighR} invertiert" }
        require(it.maxSmbU.isFinite() && it.maxSmbU in 0.0..5.0) { "maxSmb=${it.maxSmbU}" }
        require(it.guardFloorMgdl.isFinite() && it.guardFloorMgdl in 40.0..120.0) { "guardFloor=${it.guardFloorMgdl}" }
        require(it.iobThPercent in 0..300) { "iobThPercent=${it.iobThPercent}" }
        require(it.releaseHorizonMin in 5..120) { "releaseHorizon=${it.releaseHorizonMin}" }
        // Gleiche Grenzen wie DriveDecayModel.ExponentialDecay - sonst wirft der
        // Kern bei einem Wert, den der Einstellungsdialog erlaubt hat.
        require(it.driveTauMin in 10..240) { "driveTau=${it.driveTauMin}" }
        require(it.absorptionCreditWindowMin in 20..180) { "absorptionCreditWindow=${it.absorptionCreditWindowMin}" }
        require(it.markerBoostMaxMin in 0..90) { "markerBoostMax=${it.markerBoostMaxMin}" }
        require(it.nightStartMin in 0..1439 && it.nightEndMin in 0..1439) { "nightWindow=${it.nightStartMin}..${it.nightEndMin}" }
        require(it.nightDeadbandMgdl.isFinite() && it.nightDeadbandMgdl in 0.0..100.0) { "nightDeadband=${it.nightDeadbandMgdl}" }
        require(it.reboundDeadbandMgdl.isFinite() && it.reboundDeadbandMgdl in 0.0..100.0) { "reboundDeadband=${it.reboundDeadbandMgdl}" }
        require(it.driveLowerQuantilePct in PairSlopeBand.MIN_PCT..PairSlopeBand.MAX_PCT) {
            "driveLowerQuantile=${it.driveLowerQuantilePct}"
        }
        require(it.tailFloorMgdl.isFinite() && it.tailFloorMgdl in 40.0..120.0) { "tailFloor=${it.tailFloorMgdl}" }
        require(it.tailRecoveryU.isFinite() && it.tailRecoveryU in 0.0..5.0) { "tailRecovery=${it.tailRecoveryU}" }
        require(it.bolusShareLambda.isFinite() && it.bolusShareLambda in 0.0..2.0) { "bolusShareLambda=${it.bolusShareLambda}" }
        require(it.onsetEnvelopeU.isFinite() && it.onsetEnvelopeU in 0.0..5.0) { "onsetEnvelope=${it.onsetEnvelopeU}" }
        require(it.primeEnvelopeU.isFinite() && it.primeEnvelopeU in 0.0..2.0) { "primeEnvelope=${it.primeEnvelopeU}" }
        require(it.primeEnvelopeSmallU.isFinite() && it.primeEnvelopeSmallU in 0.0..1.2) { "primeSmall=${it.primeEnvelopeSmallU}" }
        require(it.primeEnvelopeLargeU.isFinite() && it.primeEnvelopeLargeU in 0.0..3.0) { "primeLarge=${it.primeEnvelopeLargeU}" }
        require(it.liabilityHorizonMin >= 30 && it.liabilityHorizonMin >= it.releaseHorizonMin && it.liabilityHorizonMin <= 360) {
            "liabilityHorizon=${it.liabilityHorizonMin} (releaseHorizon=${it.releaseHorizonMin}, UI 30..360)"
        }
    }

    /**
     * Baut die Predictor-Eingabe. Die beiden Zeitachsen-Gates sind hier
     * strukturell erfuellt, nicht zufaellig: das Array beginnt AM Anker und
     * reicht ueber den Horizont hinaus.
     */
    /** Eingabe UND die beiden Groessen, die nur beim Bau der Arrays anfallen:
     *  das IOB am Haftungshorizont und der konservative ISF des Schwanzfensters.
     *  Sie hier mitzunehmen kostet keinen einzigen zusaetzlichen Aufruf — sie
     *  stehen ohnehin in den Arrays. */
    private class Built(val input: PredictorInput, val iobAtH: Double, val isfTail: Double, val discount: DriveDiscount.Applied)

    private fun buildPredictorInput(
        signal: FuseSignalSource.Signal,
        profile: Profile,
        cfg: Config,
        band: PairSlopeBand.Estimate,
        bolusActivityUPerMin: Double,
        onsetDriveMgdlPerMin: Double?,
        reboundWindow: Boolean,
        mealMarkerActive: Boolean,
        /** ERKLAERTE ABSORPTION (Toni 09.08.): erwarteter Anstieg aus der
         *  Marker-Stufe [mg/dl/min], 0 wenn kein Kredit gilt. Wirkt NUR auf
         *  der Mittelbahn - s. MarkerScope.declaredAbsorptionDriveMgdlPerMin. */
        declaredDriveMgdlPerMin: Double = 0.0,
        /** C3/C3-01: publizierte, im IOB noch nicht sichtbare Mengen als
         *  synthetische Dosen - EINE JE OFFENEM POSTEN, mit eigenem Anker.
         *  Leer = nichts unterwegs (oder kein Einheitskern). */
        pending: List<PendingInsulinEffect> = emptyList(),
    ): Built? {
        val liabilityHorizonMin = cfg.liabilityHorizonMin
        val anchor = signal.sourceTs
        val steps = ((liabilityHorizonMin + IOB_MARGIN_MIN) * 60_000L / IOB_GRID_MS).toInt()

        val times = LongArray(steps + 1)
        val iob = DoubleArray(steps + 1)
        val activity = DoubleArray(steps + 1)
        val basalIob = DoubleArray(steps + 1)
        val isfValues = DoubleArray(steps + 1)
        for (i in 0..steps) {
            val t = anchor + i * IOB_GRID_MS
            // Das EFFEKTIVE Profil zu diesem Zeitpunkt, nicht das von jetzt:
            // `getBasal(t)`/`getIsf...` rechnen mit Prozentsatz und Timeshift des
            // UEBERGEBENEN Objekts und wechseln nicht selbst, wenn ein Profile
            // Switch im Horizont ablaeuft.
            val slotProfile = profileFunction.getProfile(t) ?: return null
            val v = iobCobCalculator.calculateFromTreatmentsAndTemps(t, slotProfile)
            times[i] = v.time
            iob[i] = v.iob
            activity[i] = v.activity
            basalIob[i] = v.basaliob
            isfValues[i] = slotProfile.getIsfMgdlTimeFromMidnight(MidnightUtils.secondsFromMidnight(t))
        }

        // Die Slots laufen ueber `times`, nicht ueber die angeforderten
        // Zeitpunkte: `calculateFromTreatmentsAndTemps` rundet intern auf die
        // volle Minute AUF und liefert damit ein leicht verschobenes `time`.
        // Zwei verschiedene Zeitachsen im selben Snapshot waeren der Anfang
        // eines Off-by-one, den niemand mehr findet.
        val isfSlots = CycleAssembly.compressIsfSlots(times, isfValues, times.last() + IOB_GRID_MS)

        // Schwanzgroessen aus DENSELBEN Arrays - kein zusaetzlicher Aufruf.
        // Der Index des Haftungshorizonts im 5-min-Raster; ab dort beginnt das
        // Schwanzfenster.
        val hIndex = (liabilityHorizonMin * 60_000L / IOB_GRID_MS).toInt().coerceIn(0, steps)
        // C4-01 (P0, Codex Fix-Pass-5-Closure): die Schwanz-HAFTUNG ist die
        // UNVERMEIDBARE Wirkung am Horizont - und die haengt am Bolusanteil,
        // nicht am Netto. Zurueckgehaltenes Basal ist eine Referenzbuchung:
        // es kann die Wirkung eines bereits abgegebenen Bolus nicht
        // rueckgaengig machen. Beispiel: Bolus +0,40, Basal -0,30, netto
        // +0,10 - die Formel zaehlte 0,10 als Haftung und gab 0,30 U
        // Spielraum frei, die es physisch nicht gibt (bei ISF 90 bis zu
        // 27 mg/dl unberuecksichtigt). Dieselbe max(net, bolus)-Regel, die
        // fuer die aktuellen Mengenkappen schon gilt (Tonis IOB-Referenz).
        val iobAtH = maxOf(iob[hIndex], iob[hIndex] - basalIob[hIndex])
        // MAXIMUM der beruehrten ISF-Bloecke: ein hoeherer ISF macht das
        // Schwanzbudget KLEINER, ist also die konservative Wahl.
        var isfTail = isfValues[hIndex]
        for (i in hIndex..steps) if (isfValues[i] > isfTail) isfTail = isfValues[i]

        val insulin = activePlugin.activeInsulin
        val trajectory = ActualTrajectoryFactory.of(
            lineage = InsulinLineage.ActualTreatment(
                sourceDeviceIdHash = "local",
                treatmentSnapshotHash = "aaps-iobcobcalculator",
                modelHash = insulin.id.name,
            ),
            points = CycleAssembly.iobPoints(times, iob, activity, basalIob),
            arrayAsOfTs = anchor,
            model = InsulinModelProvenance(
                insulinType = insulin.id.name,
                diaHours = profile.dia,
                peakMin = insulin.peak,
                codeProvenance = "activePlugin.activeInsulin",
            ),
            iobCalculationHash = "calculateFromTreatmentsAndTemps",
        )

        val discount = DriveDiscount.apply(
            meanMgdlPerMin = band.mean,
            bandLowerMgdlPerMin = band.lower,
            bolusActivityUPerMin = bolusActivityUPerMin,
            isfMgdlPerU = signal.isfAtAnchor,
            // ZURUECKGENOMMEN 09.08. (Codex Re-Review 603a15a, P0 Nr. 4).
            // Ich hatte hier ein eigenes Mahlzeiten-Lambda verdrahtet mit der
            // Begruendung, der Abschlag sei eine MODELLANNAHME und der Marker
            // korrigiere sie. Das war falsch: der Abschlag wirkt
            // ausschliesslich auf die UNTERE Bahn, und die hat genau einen
            // Verbraucher - den Hypo-Guard (s. KDoc unten). Es gibt also gar
            // keinen Bedarfsanteil, den ein Mahlzeiten-Lambda korrigieren
            // koennte; seine gesamte Wirkung waere das Anheben der
            // PRIOR-FREIEN Sicherheitsbahn per Knopfdruck. Genau das verbietet
            // H2 ("a marker may create demand evidence, not protection").
            //
            // Der gemessene Anlass (09.08. 10:46, Guard sperrte die angesagte
            // Mahlzeit um 0,9 mg/dl) bleibt gueltig - aber seine Ursache ist
            // die COB-BLINDHEIT, nicht der Abschlag. Der Abschlag rechnet
            // korrekt: er kreditiert nur die Basal-Aktivitaet und laesst die
            // Bolus-Wirkung als real stehen. Das ist fuer einen Regler ohne
            // Kohlenhydrat-Wissen der ehrliche schlechteste Fall.
            lambda = cfg.bolusShareLambda,
        )

        return Built(
            iobAtH = iobAtH,
            isfTail = isfTail,
            discount = discount,
            input = PredictorInput(
            predictionAnchorTs = anchor,
            bgAtAnchor = signal.q1,
            // ALPHA 1: `lower = mean` heisst, die Guardbahn ist im Moment
            // identisch mit der Mittelbahn — der Sicherheitsabstand FEHLT also,
            // statt heimlich erfunden zu werden. Das ist eine offene
            // Entscheidung und steht unter diesem Namen im Export.
            // Die Untergrenze hat GENAU EINEN Verbraucher: den Hypo-Guard
            // (TrajectoryCore -> minLowerBg -> FuseController). Die DOSIS haengt
            // weiter an der Mittelbahn. Deshalb ist "pessimistisch" hier die
            // untere Kante der Steigungsverteilung, und eine obere Kante wird
            // gar nicht erst gebildet — sie haette null Verbraucher und waere
            // damit ein stiller Sammler.
            //
            // confidence bleibt null: NICHT KALIBRIERT. Was stattdessen
            // mitgefuehrt wird, ist das Gemessene (Spreizung, Paaranzahl) —
            // s. Outcome und RT.reason.
            // Onset-Kanal hebt NUR die Mittelbahn. Die untere Bahn bleibt die
            // abgeschlagene Band-Untergrenze - Guard und Schwanz rechnen also
            // weiter gegen die UNGEHOBENE, konservative Bahn.
            drive = DriveEstimate(
                // MITTELBAHN: gemessener Antrieb, Onset-Kanal ODER erklaerte
                // Absorption - was am groessten ist. MAX statt Summe: sobald
                // die Messung die Ankuendigung ueberholt, zaehlt nur noch die
                // Messung, und zwei Erklaerungen desselben Anstiegs koennen
                // sich nie addieren.
                maxOf(onsetDriveMgdlPerMin?.let { maxOf(band.mean, it) } ?: band.mean, declaredDriveMgdlPerMin),
                // MARKER-PRIOR: deklarierter Carb-Kredit NUR in der unteren
                // Bahn, gekappt an der Mittelbahn - s. PrimeRelease-Doku.
                if (mealMarkerActive)
                    minOf(band.mean, discount.lowerAfterMgdlPerMin + PrimeRelease.MARKER_PRIOR_MGDL_PER_MIN)
                else discount.lowerAfterMgdlPerMin,
                null,
                DriveDiscount.methodId(PairSlopeBand.methodId(cfg.driveLowerQuantilePct), discount.lambda) +
                    if (onsetDriveMgdlPerMin != null && onsetDriveMgdlPerMin > band.mean) "+ONSET" else "",
                // C2 (Codex H2): DERSELBE untere Antrieb ohne den Prior. Aus ihm
                // rechnet TrajectoryCore die prior-freie Zwillingsbahn, gegen die
                // ALLE Sicherheitszertifikate laufen. Der Prior bleibt in der
                // angezeigten unteren Bahn sichtbar, lizenziert aber keine Dosis
                // mehr.
                discount.lowerAfterMgdlPerMin,
            ),
            // Rebound v2: Erholungssteigungen sterben in ~15 min - im Fenster
            // wird tau hart gekuerzt, sonst schreibt tau 60 sie eine Stunde
            // fort (Treiber der Vorfaelle #5/#6).
            decay = DriveDecayModel.ExponentialDecay(
                if (reboundWindow) minOf(cfg.driveTauMin, FuseController.REBOUND_TAU_MIN).toDouble()
                else cfg.driveTauMin.toDouble()
            ),
            // C10 (Codex H5): die Kuerzung gilt NUR fuer positive Antriebs-
            // anteile. Ein negativer Antrieb (Bahn faellt) behaelt das lange tau
            // - sonst wuerde der schnellere Zerfall den negativen Beitrag
            // verkleinern und die untere Bahn ANHEBEN, also den Hypo-Schutz
            // ausgerechnet nach einem Tief schwaechen. Ausserhalb des
            // Rebound-Fensters sind beide Modelle identisch, deshalb null.
            decayNegativeDrive =
                if (reboundWindow) DriveDecayModel.ExponentialDecay(cfg.driveTauMin.toDouble()) else null,
            trajectory = trajectory,
            isfSlots = isfSlots,
            horizonMin = liabilityHorizonMin,
            // C3: die Transportmenge wirkt auf ALLE DREI Bahnen (mean, lower,
            // prior-frei). Senken ist in beide Richtungen konservativ: der Guard
            // sperrt eher UND der Bedarf auf der Mittelbahn sinkt.
            pending = pending,
            ),
        )
    }
}

/**
 * EIN modellierter Transportposten dieses Zyklus (C3-01, Codex
 * Fix-Pass-5-Closure G.1/G.2).
 *
 * Er traegt seine EIGENE Menge und BEIDE Enden seiner plausiblen Lieferspanne.
 * Das ist kein Luxus, sondern der Kern des Fixes:
 *
 *  - [earliestTs] geht in die BAHN. Frueh ist dort pessimistisch: mehr Wirkung
 *    faellt ins Bewertungsfenster, die Bahn laeuft tiefer, der Guard sperrt
 *    eher UND der Bedarf auf der Mittelbahn sinkt.
 *  - [latestTs] geht in die RESTHAFTUNG am Horizont. Dort ist SPAET
 *    pessimistisch: je spaeter geliefert, desto weniger Wirkung ist bis H
 *    verbraucht, desto mehr haftet danach.
 *
 * Ein einziger Anker kann nicht beides sein - genau das war der Fehler aus
 * Abschnitt G.1 ("aelter ist hier nicht pauschal konservativ"). Zwei Enden
 * derselben Spanne, jedes dort eingesetzt, wo es sperrt, ergeben eine
 * punktweise Worst-Case-Huelle, die in KEINER Richtung eine Dosis vergroessern
 * kann.
 */
internal data class TransportDose(
    val proposalId: String,
    val amountU: Double,
    /** Fruehester plausibler Lieferzeitpunkt - Anker der Bahn. */
    val earliestTs: Long,
    /** Spaetester plausibler Lieferzeitpunkt - Anker der Resthaftung. */
    val latestTs: Long,
)

/**
 * Die synthetische Dosis EINES Transportpostens (C3) - DERSELBE Einheitskern
 * wie in der Kandidatensuche, nur auf einen anderen Lieferzeitpunkt verschoben.
 *
 * WARUM VERSCHIEBEN STATT NEU SAMPELN: die Stuetzstellen des Kerns sind Offsets
 * AB DER LIEFERUNG - eine Verschiebung ist damit reine Zeitrechnung an der
 * Abfragestelle. Ein zweiter Bau kostete ~540 Modellabfragen je Zyklus und
 * koennte, bei einem Profilwechsel dazwischen, ein anderes Modell liefern.
 *
 * Die Nullregel vor der Lieferung kommt aus dem Kern selbst
 * ([UnitInsulinKernel.activityAt] ist vor `deliveryTs` exakt 0) und wird hier
 * nicht nachgebaut.
 */
internal class KernelPendingInsulin(
    private val kernel: UnitInsulinKernel,
    override val amountU: Double,
    override val deliveryTs: Long,
) : PendingInsulinEffect {

    /** Anfrage-Zeitpunkt in die Zeitachse des Kerns umgerechnet. */
    private fun shifted(tsMs: Long): Long = tsMs - deliveryTs + kernel.deliveryTs

    override fun covers(tsMs: Long): Boolean = kernel.covers(shifted(tsMs))
    override fun activityAt(tsMs: Long): Double = kernel.activityAt(shifted(tsMs), amountU)
    override fun iobAt(tsMs: Long): Double = kernel.iobAt(shifted(tsMs), amountU)
}

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
import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.TbrActuation
import app.aaps.fuse.core.controller.EpisodeDeadline
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.LedgerHoldGate
import app.aaps.fuse.core.controller.LowThreatGate
import app.aaps.fuse.core.controller.LivenessChannel
import app.aaps.fuse.core.controller.DeferredPrime
import app.aaps.fuse.core.controller.MarkerScope
import app.aaps.fuse.core.controller.NightWindow
import app.aaps.fuse.core.controller.SubStepAccumulator
import app.aaps.fuse.core.ledger.AccountedTreatment
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.fuse.plugin.ledger.LedgerFacts
import app.aaps.fuse.plugin.ledger.OpenTransportItem
import app.aaps.fuse.plugin.ledger.TransportInclusion
import app.aaps.fuse.core.controller.IobThreshold
import app.aaps.fuse.core.controller.MarkerEpisode
import app.aaps.fuse.core.controller.MarkerEpisodeGate
import app.aaps.fuse.core.controller.TailLiability
import app.aaps.fuse.core.controller.TbrPolicy
import app.aaps.fuse.core.controller.TurnResponseShadow
import app.aaps.fuse.core.observer.SafetyReason
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.ObserverStateMachine
import app.aaps.fuse.core.observer.ObserverStep
import app.aaps.fuse.core.signal.BgiAdjustedSeries
import app.aaps.fuse.core.signal.PairSlopeBand
import app.aaps.fuse.core.predictor.ActualTrajectoryFactory
import app.aaps.fuse.core.predictor.DriveDecayModel
import app.aaps.fuse.core.controller.CandidateGate
import app.aaps.fuse.core.controller.CandidateSearch
import app.aaps.fuse.core.controller.EvidenceStock
import app.aaps.fuse.core.controller.MarkerFallback
import app.aaps.fuse.core.controller.MealFoundation
import app.aaps.fuse.core.controller.MarkerFloor
import app.aaps.fuse.core.controller.CorrectionReversalGuard
import app.aaps.fuse.core.controller.DescentRecoveryLatch
import app.aaps.fuse.core.controller.PositiveCorrectionRearm
import app.aaps.fuse.core.controller.DescentDeferredCarry
import app.aaps.fuse.core.controller.MeasuredDescentGate
import app.aaps.fuse.core.controller.OnsetChannel
import app.aaps.fuse.core.controller.PrimeRelease
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.predictor.ConditionalDrive
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
import kotlin.math.roundToInt

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
    /**
     * DER IN DIESEM PROZESS BEOBACHTETE MARKERDRUCK, 0 = keiner.
     *
     * OHNE DEFAULT, und das ist Absicht: ein Default `{ 0L }` waere zwar
     * fail-closed, aber er wuerde einen vergessenen Anschluss in Produktion
     * lautlos in "nie eine Evidenz-Episode" verwandeln. Lieber ein
     * Kompilierfehler an jeder Konstruktionsstelle.
     *
     * NICHT aus dem Marker-Ring lesen: der fuellt sich beim Warmstart aus dem
     * Trail nach, ein Druck von vor zwei Stunden saehe damit aus wie eben
     * beobachtet. Einzige zulaessige Quelle ist das Umschalten selbst.
     */
    private val markerPressObserved: () -> Long,
    /**
     * NUR FUER DEN PHASE-2-FENSTER-REPLAY (Toni/Codex 23.08.): ersetzt das
     * Theil-Sen-Fenster der Antriebs-Schaetzung. Am Geraet ist der Wert
     * konstruktionsbedingt null - er ist KEIN Preference und die
     * DI-Konstruktion kennt den Parameter nicht. Nur der Offline-Treiber
     * im Test-Scope setzt ihn (W10/W8-Gegenlaeufe auf aufgezeichneten
     * Tagen); der W18-Referenzlauf laesst ihn null und muss die
     * aufgezeichneten Entscheidungen reproduzieren (Validierungstor).
     */
    private val theilSenWindowMsOverride: Long? = null,
    /**
     * NUR FUER DEN PHASE-2-REPLAY (Toni 23.08. Abend): schaltet EINE
     * Trendregel des Turn-Shadows als ECHTE Dosierbahn scharf.
     *   "UP"       Mittelbahn-Anhebung bei bestaetigter Aufwaertswende
     *              (nur mean; Unterkante/Guard/Tail bleiben produktiv)
     *   "DOWN_P2"  Bedarfssenkung min(mean, fastDrive) nach ZWEI
     *   "DOWN_P3"  bzw. DREI zusammenhaengenden fastDrive-Rueckgaengen -
     *              exakt die Lane-Mathematik des ADAPTIVE-DOWN-Schattens.
     * Am Geraet ist der Wert konstruktionsbedingt null (kein Preference-
     * Weg, die DI-Konstruktion kennt den Parameter nicht) - dann ist der
     * Injektionsblock toter Code und `built` dasselbe Objekt wie ohne ihn.
     * Klassifikation und Streaks entstehen im Lauf selbst aus dem jeweils
     * gefahrenen Fenster; nichts wird aus Exporten uebernommen.
     */
    private val trendRuleOverride: String? = null,
    /**
     * DIE EINE EVIDENZ-KONFIGURATION DES ZYKLUS.
     *
     * Sie wurde bis zum 12.08. an drei Stellen frisch erzeugt - Markertor,
     * Kern und Export. Solange die Defaults gelten, faellt das nicht auf;
     * sobald ein Replay einen anderen Deckel einspeist, laufen sie
     * auseinander, und der Export beschreibt eine andere Regel als die, die
     * gelaufen ist. Eine Instanz, ein Deckel, drei Verbraucher.
     */
    private val evidenceConfig: EvidenceStock.Config = EvidenceStock.Config(),
    /**
     * DER PREDICTOR ALS PARAMETER - default die echte Funktion.
     *
     * Nicht fuer Bequemlichkeit: die Ablehnungen von [TrajectoryCore] sind aus
     * dem Testaufbau praktisch nicht ausloesbar. Ein unendlicher Wert wird vom
     * SIGNAL-Waechter frueher gefangen, die Aktivitaets- und Antriebsgrenzen
     * sind in Produktion gar nicht gesetzt (PredictorBounds-Defaults null), und
     * das IOB-Array deckt den Horizont per Konstruktion (Spanne = Horizont plus
     * 30 min Marge).
     *
     * Der predictorfreie Markerpfad haette damit nur seine VERWEIGERTE Seite
     * belegen koennen - und "auf ein seltenes Live-Ereignis warten" ist bei
     * einem Insulinpfad keine Testmethode. Ein Parameter mit echtem Default ist
     * der kleinste Eingriff, der die positive Seite pruefbar macht; im Betrieb
     * ist er bitgleich zu vorher.
     */
    private val predict: (PredictorInput) -> PredictorOutcome = TrajectoryCore::predict,
) {

    private val observer = ObserverStateMachine(sessionId = sessionId)
    private val signalSource = FuseSignalSource(iobCobCalculator, profileFunction)

    companion object {

        /**
         * Der Mahlzeitenstand als ABLEITUNG aus den Episodenbudgets.
         *
         * Als Funktion und nicht als Schnappschuss im Runner, weil die Zahlen
         * sich nach dem Runner noch AENDERN koennen: das Publikationsgate
         * laeuft spaeter und kann die Reservierung dieses Zyklus zurueckdrehen
         * (s. `EpisodeBudgets.pendingReservation`). Ein im Runner eingefrorener
         * Stand zeigte dann eine Menge, die es nicht mehr gibt - nicht im
         * naechsten Regelzyklus, aber im Trail und auf dem Schirm genau dieses
         * Zyklus. Das Plugin rechnet sie nach der Aufloesung neu, mit
         * DERSELBEN Funktion.
         *
         * T0-ANKER statt rollierender Fenster (Toni 08.08.): interessant ist
         * "wie viel stand nach 30/60 min ab Essensbeginn", nicht "letzte
         * 30 min" - die Werte wachsen bis zur Marke und frieren dann von
         * selbst ein.
         */
        fun mealStatsOf(
            episodes: app.aaps.fuse.plugin.ledger.EpisodeBudgets,
            markerTs: Long,
            computeTs: Long,
        ): MealStats? = if (markerTs > 0 &&
            computeTs - markerTs <= (OnsetChannel.MARKER_WINDOW_MIN + 120) * 60_000L
        ) MealStats(
            sinceMin = ((computeTs - markerTs) / 60_000L).toInt(),
            totalU = episodes.mealDeliveries.sumOf { it.amountU },
            first30U = episodes.mealDeliveries.filter { it.ts - markerTs <= 30 * 60_000L }.sumOf { it.amountU },
            first60U = episodes.mealDeliveries.filter { it.ts - markerTs <= 60 * 60_000L }.sumOf { it.amountU },
        ) else null

        /**
         * Die Laufzeitpruefung der Konfiguration - EIGENE Funktion, damit sie
         * pruefbar ist.
         *
         * Sie stand als `.also { … }` in `readConfig()` und war damit nur ueber
         * einen vollstaendig gemockten Preference-Baum erreichbar. Ergebnis: sie
         * war ungetestet, und am 11.08. lief sie eine Stunde lang mit einer
         * Schranke, die legale Einstellungen abgewiesen haette (s. u.).
         */
        internal fun validate(it: Config) {
            // Die Preference-Grenzen gelten nur im Einstellungsdialog. Ein Wert aus
            // einem alten Import geht daran vorbei — deshalb hier nochmal, und zwar
            // werfend, damit der Guard daraus einen benannten Abbruch macht.
            require(it.smbRatio.isFinite() && it.smbRatio in FuseDoubleKey.SmbRatio.min..FuseDoubleKey.SmbRatio.max) { "smbRatio=${it.smbRatio}" }
            require(it.smbRatioRise.isFinite() && it.smbRatioRise in FuseDoubleKey.SmbRatioRise.min..FuseDoubleKey.SmbRatioRise.max) { "smbRatioRise=${it.smbRatioRise}" }
            // Audit R95 F-P1-05: der Zyklus prueft NOCHMAL, weil Import und
            // Migration am Einstellungsdialog vorbeikommen.
            //
            // ABER: die Schranke wird aus dem KEY abgeleitet, nicht danebengeschrieben.
            // Am 11.08. stand hier `primeEnvelopeU in 0.0..2.0` als Literal, waehrend
            // der Key auf 0.0..3.0 angehoben wurde. Folge: wer im Dialog 2,5 einstellt -
            // also genau das, wofuer die Anhebung gemacht war -, laesst `readConfig()`
            // in JEDEM Zyklus werfen. Der Guard macht daraus `abort("config: ...")`,
            // und FUSE gibt gar nichts mehr ab. Nicht nur keinen Prime: den ganzen
            // Regler, dauerhaft, ohne Dialogfehler und ohne Alarm - der einzige Hinweis
            // steht als `abortReason` im Trail.
            //
            // Eine Laufzeitgrenze, die STRENGER ist als der Dialog, verbietet legale
            // Einstellungen. Deshalb: wo ein Key die Grenze kennt, kommt sie von dort.
            require(it.riseRampLowR.isFinite() && it.riseRampLowR in FuseDoubleKey.RiseRampLowR.min..FuseDoubleKey.RiseRampLowR.max) { "riseRampLowR=${it.riseRampLowR}" }
            require(it.riseRampHighR.isFinite() && it.riseRampHighR in FuseDoubleKey.RiseRampHighR.min..FuseDoubleKey.RiseRampHighR.max) { "riseRampHighR=${it.riseRampHighR}" }
            require(it.riseRampHighR > it.riseRampLowR) { "riseRamp ${it.riseRampLowR}..${it.riseRampHighR} invertiert" }
            require(it.maxSmbU.isFinite() && it.maxSmbU in FuseDoubleKey.MaxSmbU.min..FuseDoubleKey.MaxSmbU.max) { "maxSmbU=${it.maxSmbU}" }
            require(it.guardFloorMgdl.isFinite() && it.guardFloorMgdl in FuseDoubleKey.GuardFloorMgdl.min..FuseDoubleKey.GuardFloorMgdl.max) { "guardFloorMgdl=${it.guardFloorMgdl}" }
            require(
                it.positiveDescentHorizonMin.isFinite() &&
                    it.positiveDescentHorizonMin in FuseDoubleKey.PositiveDescentHorizonMin.min..FuseDoubleKey.PositiveDescentHorizonMin.max
            ) { "positiveDescentHorizonMin=${it.positiveDescentHorizonMin}" }
            require(it.iobThPercent in FuseIntKey.IobThPercent.min..FuseIntKey.IobThPercent.max) { "iobThPercent=${it.iobThPercent}" }
            require(it.releaseHorizonMin in FuseIntKey.ReleaseHorizonMin.min..FuseIntKey.ReleaseHorizonMin.max) { "releaseHorizonMin=${it.releaseHorizonMin}" }
            // Gleiche Grenzen wie DriveDecayModel.ExponentialDecay - sonst wirft der
            // Kern bei einem Wert, den der Einstellungsdialog erlaubt hat.
            require(it.driveTauMin in FuseIntKey.DriveTauMin.min..FuseIntKey.DriveTauMin.max) { "driveTauMin=${it.driveTauMin}" }
            require(it.absorptionCreditWindowMin in FuseIntKey.AbsorptionCreditWindowMin.min..FuseIntKey.AbsorptionCreditWindowMin.max) { "absorptionCreditWindowMin=${it.absorptionCreditWindowMin}" }
            require(it.markerBoostMaxMin in FuseIntKey.MarkerBoostMaxMin.min..FuseIntKey.MarkerBoostMaxMin.max) { "markerBoostMaxMin=${it.markerBoostMaxMin}" }
            require(
                it.evidenceReboundOverrideMaxMin in
                    FuseIntKey.EvidenceReboundOverrideMaxMin.min..FuseIntKey.EvidenceReboundOverrideMaxMin.max
            ) { "evidenceReboundOverrideMaxMin=${it.evidenceReboundOverrideMaxMin}" }
            require(it.nightStartMin in 0..1439 && it.nightEndMin in 0..1439) { "nightWindow=${it.nightStartMin}..${it.nightEndMin}" }
            require(it.nightDeadbandMgdl.isFinite() && it.nightDeadbandMgdl in FuseDoubleKey.NightDeadbandMgdl.min..FuseDoubleKey.NightDeadbandMgdl.max) { "nightDeadbandMgdl=${it.nightDeadbandMgdl}" }
            require(it.reboundDeadbandMgdl.isFinite() && it.reboundDeadbandMgdl in FuseDoubleKey.ReboundDeadbandMgdl.min..FuseDoubleKey.ReboundDeadbandMgdl.max) { "reboundDeadbandMgdl=${it.reboundDeadbandMgdl}" }
            require(it.driveLowerQuantilePct in PairSlopeBand.MIN_PCT..PairSlopeBand.MAX_PCT) {
                "driveLowerQuantile=${it.driveLowerQuantilePct}"
            }
            require(it.theilSenWindowMin in FuseIntKey.TheilSenWindowMin.min..FuseIntKey.TheilSenWindowMin.max) {
                "theilSenWindowMin=${it.theilSenWindowMin}"
            }
            require(it.zeroLatchCalmExitMin in FuseIntKey.ZeroLatchCalmExitMin.min..FuseIntKey.ZeroLatchCalmExitMin.max) { "zeroLatchCalmExitMin=${it.zeroLatchCalmExitMin}" }
            require(it.zeroLatchCalmDistanceMgdl.isFinite() && it.zeroLatchCalmDistanceMgdl in FuseDoubleKey.ZeroLatchCalmDistanceMgdl.min..FuseDoubleKey.ZeroLatchCalmDistanceMgdl.max) { "zeroLatchCalmDistanceMgdl=${it.zeroLatchCalmDistanceMgdl}" }
            require(it.tailFloorMgdl.isFinite() && it.tailFloorMgdl in FuseDoubleKey.TailFloorMgdl.min..FuseDoubleKey.TailFloorMgdl.max) { "tailFloorMgdl=${it.tailFloorMgdl}" }
            require(it.tailRecoveryU.isFinite() && it.tailRecoveryU in FuseDoubleKey.TailRecoveryU.min..FuseDoubleKey.TailRecoveryU.max) { "tailRecoveryU=${it.tailRecoveryU}" }
            require(it.bolusShareLambda.isFinite() && it.bolusShareLambda in FuseDoubleKey.BolusShareLambda.min..FuseDoubleKey.BolusShareLambda.max) { "bolusShareLambda=${it.bolusShareLambda}" }
            require(it.onsetEnvelopeU.isFinite() && it.onsetEnvelopeU in FuseDoubleKey.OnsetEnvelopeU.min..FuseDoubleKey.OnsetEnvelopeU.max) { "onsetEnvelopeU=${it.onsetEnvelopeU}" }
            require(it.primeEnvelopeU.isFinite() && it.primeEnvelopeU in FuseDoubleKey.PrimeEnvelopeU.min..FuseDoubleKey.PrimeEnvelopeU.max) { "primeEnvelopeU=${it.primeEnvelopeU}" }
            require(it.primeWindowMin in FuseIntKey.PrimeWindowMin.min..FuseIntKey.PrimeWindowMin.max) { "primeWindowMin=${it.primeWindowMin}" }
            require(it.liabilityHorizonMin >= 30 && it.liabilityHorizonMin >= it.releaseHorizonMin && it.liabilityHorizonMin <= 360) {
                "liabilityHorizon=${it.liabilityHorizonMin} (releaseHorizon=${it.releaseHorizonMin}, UI 30..360)"
            }
            // P0 des Liveness-Bauvertrags: der Kanaldeckel ist eine tragende
            // Grenze - ein Unsinnswert (0 %, 500 %) darf nie stillschweigend
            // rechnen, sondern muss den Zyklus benannt abbrechen.
            require(it.livenessMealPowerMin in FuseIntKey.LivenessMealPowerMin.min..FuseIntKey.LivenessMealPowerMin.max) { "livenessMealPowerMin=${it.livenessMealPowerMin}" }
            require(it.livenessMealRatioCap.isFinite() && it.livenessMealRatioCap in FuseDoubleKey.LivenessMealRatioCap.min..FuseDoubleKey.LivenessMealRatioCap.max) { "livenessMealRatioCap=${it.livenessMealRatioCap}" }
            require(it.livenessMealIobCapPercent.isFinite() && it.livenessMealIobCapPercent in FuseDoubleKey.LivenessMealIobCapPercent.min..FuseDoubleKey.LivenessMealIobCapPercent.max) { "livenessMealIobCapPercent=${it.livenessMealIobCapPercent}" }
            require(it.livenessCorrectionRatioCap.isFinite() && it.livenessCorrectionRatioCap in FuseDoubleKey.LivenessCorrectionRatioCap.min..FuseDoubleKey.LivenessCorrectionRatioCap.max) { "livenessCorrectionRatioCap=${it.livenessCorrectionRatioCap}" }
            require(it.livenessCorrectionIobCapPercent.isFinite() && it.livenessCorrectionIobCapPercent in FuseDoubleKey.LivenessCorrectionIobCapPercent.min..FuseDoubleKey.LivenessCorrectionIobCapPercent.max) { "livenessCorrectionIobCapPercent=${it.livenessCorrectionIobCapPercent}" }
            // RELATIONAL, FAIL-CLOSED (Bauauftrag §1): CORRECTION darf nie
            // offener sein als MEAL - nicht tauschen, nicht klemmen, ablehnen.
            require(it.livenessCorrectionRatioCap <= it.livenessMealRatioCap) { "livenessCorrectionRatioCap=${it.livenessCorrectionRatioCap} > meal ${it.livenessMealRatioCap}" }
            require(it.livenessCorrectionIobCapPercent <= it.livenessMealIobCapPercent) { "livenessCorrectionIobCapPercent=${it.livenessCorrectionIobCapPercent} > meal ${it.livenessMealIobCapPercent}" }
            require(it.livenessReArmMin in FuseIntKey.LivenessReArmMin.min..FuseIntKey.LivenessReArmMin.max) { "livenessReArmMin=${it.livenessReArmMin}" }
            require(it.livenessBgMinDayMgdl.isFinite() && it.livenessBgMinDayMgdl in FuseDoubleKey.LivenessBgMinDayMgdl.min..FuseDoubleKey.LivenessBgMinDayMgdl.max) { "livenessBgMinDayMgdl=${it.livenessBgMinDayMgdl}" }
            require(it.livenessBgMinNightMgdl.isFinite() && it.livenessBgMinNightMgdl in FuseDoubleKey.LivenessBgMinNightMgdl.min..FuseDoubleKey.LivenessBgMinNightMgdl.max) { "livenessBgMinNightMgdl=${it.livenessBgMinNightMgdl}" }
        }

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
        /**
         * FAEHRT DIE PUMPE DANACH ANDERS ALS VORHER? `null` = nicht
         * beurteilbar; der Eingriffsstempel wertet das als Eingriff.
         *
         * Hier und nicht im Plugin gerechnet, weil hier BEIDES vorliegt: die
         * laufende Sicht (`currentTbr`) und die neue Anforderung. Ein zweiter
         * Lesevorgang im Plugin koennte sich mitten im Zyklus widersprechen -
         * davor warnt schon der Kommentar an `processedTbrEbData`.
         */
        val tbrChanged: Boolean?,
        /**
         * DIE LAGE DIESES ZYKLUS fuer den Erwartungs-Ledger.
         *
         * Hier gebildet und nicht im Plugin nachgebaut: alle sechs Groessen
         * entstehen waehrend des Laufs, und eine zweite Herleitung aus
         * Teilinformationen waere eine driftende Wahrheit. `ledgerSealed`
         * bleibt offen - ob sich die Generation versiegeln liess, weiss erst
         * das Plugin nach dem Publikations-Gate.
         *
         * `null` heisst "dieser Pfad hat keine gebildet" und ergibt beim
         * Klassifizieren EXCLUDED - die sichere Richtung.
         */
        val expectationSituation: ExpectationLedger.Situation? = null,
        /**
         * KENNUNG DES REGELWERKS fuer den Erwartungs-Ledger.
         *
         * Bewusst DIESELBE, die der Export schon fuehrt
         * ([FuseStateJson.hashOf]) - versioniert ueber RULE_SET_VERSION und
         * seit Monaten gepflegt. Ein zweiter, eigener Hash waere eine zweite
         * Wahrheit ueber dieselbe Frage; die beiden liefen mit dem naechsten
         * Parameter auseinander, und niemand saehe welcher recht hat.
         *
         * Leer heisst "nicht bestimmbar" (nicht-endliche Werte in der
         * Konfiguration). Dann reiht der Recorder nichts ein - eine
         * Behauptung ohne bekanntes Regelwerk ist spaeter nicht vergleichbar.
         */
        val configGeneration: String = "",
        val prediction: PredictorResult?,
        /**
         * Die BREMSBAHN, sofern gerechnet (S0).
         *
         * Sie steht hier, weil der Guard gegen das Minimum ueber BEIDE Bahnen
         * entscheidet: stammt das Minimum aus der Bremse, erklaert die
         * Hub-Zerlegung der Hauptbahn den falschen Ort. Ohne dieses Feld
         * wuerde der Export genau den Fehler wiederholen, den die getrennten
         * Zeitindizes gerade beheben.
         */
        val restraint: PredictorResult? = null,
        /**
         * DOSIERNEUTRALER Tau-/Wende-Shadow. Keine Entscheidung liest dieses
         * Feld; es wird ausschliesslich in den FUSE-Trail exportiert.
         */
        val turnResponseShadow: TurnResponseShadow.Report? = null,
        /** Hat die Replay-Trendregel in DIESEM Zyklus die Bahn veraendert?
         *  Am Geraet konstruktionsbedingt immer false (Override ist null). */
        val trendRuleApplied: Boolean = false,
        /**
         * Die SICHERHEITSKANTE am Haftungshorizont, aus der der Schwanz sein
         * Budget rechnet - EINMAL ohne und einmal mit der Ankuendigung.
         *
         * Beide nebeneinander, weil ihre DIFFERENZ die Groesse ist, um die es
         * geht: sie beziffert, was die Zirkularitaet kostet. `conditional`
         * ist null, wenn kein Kredit lief oder der Schalter aus ist.
         */
        val tailLowerUnconditionalMgdl: Double? = null,
        val tailLowerConditionalMgdl: Double? = null,
        /** Je Bahn EINZELN, bedingt und unbedingt. Ohne sie war am
         *  11.08. nicht zu sehen, dass die Hebung der Hauptbahn von der
         *  unbedingten Bremsbahn vollstaendig kassiert wurde - die
         *  kombinierten Werte sahen schlicht gleich aus. */
        val tailLowerMainUncondMgdl: Double? = null,
        val tailLowerMainCondMgdl: Double? = null,
        val tailLowerRestraintUncondMgdl: Double? = null,
        val tailLowerRestraintCondMgdl: Double? = null,
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
        /**
         * DIE SICHT AUFS MAHLZEITENFUNDAMENT (Punkt 12).
         *
         * Immer gesetzt, auch ohne Autorisierung - dann traegt sie
         * `armed = false` und lauter Nullen. Das ist Absicht: ein FEHLENDER
         * Abschnitt im Export waere von "der Zyklus kam nicht so weit" nicht
         * zu unterscheiden, und genau diese Verwechslung hat hier schon
         * einmal Arbeit gekostet.
         *
         * Solange `arm()` nicht verdrahtet ist, steht hier dauerhaft
         * [MealFoundation.Snapshot.none] - der Export zeigt dann, dass kein
         * Fundament laeuft, und das ist die richtige Aussage.
         */
        val mealFoundation: MealFoundation.Snapshot = MealFoundation.Snapshot.none(),
        /** Gueltige manuelle NORMAL-Boli strikt nach dem laufenden Marker.
         *  null = DB-Sicht unlesbar; der Sicherheitsuebertrag sperrt dann
         *  fail-closed. Die regulaere Phase B bleibt davon unberuehrt. */
        val manualBolusAfterMarkerU: Double? = null,
        /** Der SMB-Stand VOR der Fundament-Anhebung [U] - reine Messung. */
        val preFoundationSmbU: Double = 0.0,
        /**
         * DIE URSACHE DER LAGE, gemessen VOR dem Fundament (Codex 19.08.).
         *
         * WOZU: [MealFoundation.Snapshot.binding] ist die Bindung DES
         * FUNDAMENTS. Sie kann den urspruenglichen Guard oder Tail
         * ueberdecken - ein Test oder eine Auswertung, die daraus "Guard hat
         * gebunden" liest, behauptet dann nur, wodurch die Lage entstehen
         * SOLLTE, nicht was wirklich band.
         *
         * Beide Werte stammen aus DEMSELBEN Moment wie [preFoundationSmbU] -
         * Groessen aus verschiedenen Zeitpunkten zu paaren war in diesem
         * Projekt mehrfach die Fehlerquelle.
         */
        val preFoundationBlock: FuseController.Block = FuseController.Block.NONE,
        val preFoundationBindingLimit: String? = null,
        /** Was das Fundament ueber den normalen Vorschlag hinaus angehoben
         *  hat [U]. Zusammen mit [preFoundationSmbU] und der publizierten
         *  Menge beantwortet es, WER die Dosis wollte und wer sie gebremst
         *  hat - aus der Summe allein ist das nicht ablesbar. */
        val foundationLiftU: Double = 0.0,
        /** PHASE-A-SOFORTANTEIL (iLet, Bauauftrag Toni 24.08.): der offene
         *  Boden VOR diesem Zyklus [U] (upfrontFloorU-Bilanz), die in DIESEM
         *  Zyklus als MEAL_UPFRONT angeforderte Menge [U] und der abgeleitete
         *  Zustand fuer Export/Viewer. Angefordert heisst NICHT publiziert
         *  oder pumpenbestaetigt - die Publikation entscheidet das Gate. */
        val phaseAUpfrontPendingU: Double = 0.0,
        val phaseAUpfrontRequestedU: Double = 0.0,
        val phaseAUpfrontState: String? = null,
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
        /** Die Bahn wurde verworfen. GETRENNT von [abortReason] gefuehrt, weil
         *  der Zyklus deswegen seit dem 11.08. nicht mehr zwingend endet. */
        val predictorRejected: Boolean = false,
        /** Welcher PredictorReason, als Name. Nicht als Bit: ARRAY_TOO_SHORT
         *  und MISSING_ISF_SLOT sind zwei sehr verschiedene Lagen, und nur
         *  eine davon ist ueberstimmbar. */
        val predictorReason: String? = null,
        /** Ob der predictorfreie Markerpfad diesen Zyklus getragen hat. */
        val markerFallbackUsed: Boolean = false,
        /** Identitaet der laufenden Evidenz-Episode; 0 = keine. */
        val evidenceEpisodeId: Long = 0L,
        /**
         * WARUM keine eroeffnet wurde, als Name aus
         * [MarkerEpisodeGate.Denial]; `null` = es gibt eine.
         *
         * Er gehoert in den Export UND in den Tab, weil genau EIN Fall sonst
         * unerklaerlich waere: nach einem Absturz zwischen Knopfdruck und
         * Ledger-Persist steht der Marker weiter auf "aktiv", und es gibt
         * trotzdem keinen Kredit. Ohne benannten Grund saehe das aus wie ein
         * kaputter Kanal.
         */
        val evidenceEpisodeDenial: String? = null,
        /**
         * DIE RECHENSPUR DES LOW-TORS (Toni 17.08., Auflage vor dem
         * Produktiv-Flash). `null` = in diesem Zyklus nicht ausgewertet
         * (Abbruchpfad); [LowThreatGate.Verdict.NONE] mit `denial` = geprueft
         * und abgelehnt. Die beiden auseinanderhalten zu koennen ist der
         * ganze Zweck: eine Null, die NICHT kam, sieht sonst aus wie ein
         * Zyklus ohne Befund.
         */
        val lowThreat: LowThreatGate.Result? = null,
        /**
         * Der Zusatzkredit dieser Episode ist ausdruecklich zurueckgenommen.
         *
         * Die Episode LAEUFT dabei weiter - Anker, Deckel und bezahlte Menge
         * bleiben stehen. Ohne dieses Feld waere "Episode aktiv" im Tab die
         * halbe Wahrheit und saehe aus wie eine laufende Lizenz.
         */
        val evidenceCreditRevoked: Boolean = false,
        /** Darf die Evidenz in DIESEM Zyklus das Rebound-Totband
         *  entwaffnen? Das Zyklusergebnis - Anzeige und Trail lesen es,
         *  statt das Markeralter nachzurechnen. */
        val evidenceMayOverrideRebound: Boolean = false,
        /** Gepinnter Ablauf des Sonderrechts [ms], 0 = keines. */
        val reboundOverrideDeadlineTs: Long = 0L,
        /** Warum es NICHT gilt - typisiert. null = es gilt. */
        val reboundOverrideDenial: String? = null,
        /** DAS GEMESSENE ABWAERTSRISIKO dieses Zyklus - der finale Riegel
         *  gegen neues positives Insulin. Getrennt vom Basalnutzen. */
        val descentRiskActive: Boolean = false,
        val descentRiskDenial: String? = null,
        val descentFallRatePerMin: Double? = null,
        val descentOvercoverageMgdl: Double? = null,
        val descentMinutesToFloor: Double? = null,
        /** RESTARTFESTE HYSTERESE hinter dem Rohsignal. `active` ist die
         *  dosierwirksame Wahrheit; Rohsignal und Grund bleiben daneben
         *  sichtbar, damit ein Replay Schliessen und Wiederfreigabe trennt. */
        val descentLatchActive: Boolean = false,
        val descentLatchReason: String? = null,
        val descentRecoveryCycles: Int = 0,
        // ---- Punkt 6: der Marker-Prime-Aufschub, vollstaendig im Trail ----
        val deferredPrimeOpenU: Double = 0.0,
        val deferredPrimePinnedForTs: Long = 0L,
        val deferredPrimeDeadlineTs: Long = 0L,
        val deferredPrimeHorizonMin: Int = 0,
        /** In DIESEM Zyklus zurueckgehalten / freigegeben. */
        val deferredPrimeWithheldU: Double = 0.0,
        val deferredPrimeReleasedU: Double = 0.0,
        /** Warum KEINE Freigabe (typisiert) - null, wenn freigegeben wurde
         *  oder die Frage sich in diesem Zyklus nicht stellte. */
        val deferredPrimeDenial: String? = null,
        val deferredPrimeLapseReason: String? = null,
        val deferredPrimeLapseU: Double = 0.0,
        val deferredPrimeLapseTs: Long = 0L,
        /** Liveness-Kanal: Lauf-Zustand, Kandidat und Hub DIESES Zyklus.
         *  `binding` nennt die Grenze, die den Kandidaten beschnitten hat -
         *  die strengste Grenze ist IMMER benannt (P0 des Bauvertrags). */
        val livenessActive: Boolean = false,
        val livenessStreak: Int = 0,
        val livenessCandidateU: Double = 0.0,
        /** Der ROHE Kanalbedarf `max(0, (releaseMean-target)/ISF)` (Codex
         *  22.08. spaet). null = die Bedarfsrechnung lief in diesem Zyklus
         *  nicht (Kanal aus, Riegel, nicht bewaffnet); 0.0 = gelaufen, kein
         *  positiver Bedarf. Der Viewer darf ihn NIE aus candidateU/ratio
         *  zurueckrechnen (maxSMB-Bindung ist nicht invertierbar). */
        val livenessNeedU: Double? = null,
        /** Die Mittelbahn, gegen die der Kanal gerechnet hat - NICHT immer
         *  dieselbe wie decision.predAtReleaseMgdl (min mit Bremsbahn). */
        val livenessReleaseMeanMgdl: Double? = null,
        /** MEAL | CORRECTION | EXCLUDED - Identitaet der Liveness-Haftung
         *  aus der persistierten Markerfrist (§6: NIE aus state.context).
         *  null = Kanal aus oder Stufe nicht erreicht. */
        val livenessProfile: String? = null,
        val livenessProfileReason: String? = null,
        val livenessSelectedRatioCap: Double? = null,
        val livenessSelectedIobCapPercent: Double? = null,
        val livenessProfileIobLimitU: Double? = null,
        /** Der Normalpfad-Anteil VOR `final = max(normal, live)`. */
        val livenessNormalSmbU: Double? = null,
        /** COVERAGE-VORBEREITUNG (dosierneutral): statische Korrektur-
         *  distanz (q1-Ziel)/ISF, Zustand UNAVAILABLE solange keine
         *  horizontkonsistente Deckungsgroesse existiert, gemessene
         *  Druckbedingung. effectiveInsulinCoverageU/coverageMarginU
         *  bleiben bewusst null - nichts wird geschaetzt. */
        val livenessStaticCorrectionNeedU: Double? = null,
        val livenessCoverageState: String? = null,
        /** Die DRUCKBEDINGUNG des Kanals (BG ueber Schwelle UND r >= 1) -
         *  bewusst NICHT disturbanceActive: eine Stoerung wird erst
         *  behauptet, wenn es eine modellkonsistente Groesse gibt. */
        val livenessPressureActive: Boolean? = null,
        val markerPowerPinnedFor: Long = 0L,
        val markerPowerDeadlineTs: Long = 0L,
        /** ZERO-TBR-LATCH: verriegelte Null, Grund des Zyklus, Ruhe-
         *  Zaehler und ob der Latch die TBR dieses Zyklus uebersteuert hat
         *  (Basalachse; smbU bleibt per latchZeroOnly unberuehrt). */
        val zeroLatchActive: Boolean = false,
        val zeroLatchSinceTs: Long = 0L,
        val zeroLatchReason: String? = null,
        val zeroLatchCalmStreak: Int = 0,
        /** v29: Ausloese-Zaehler des Fall-Verdikts (2 aufeinanderfolgende
         *  qualifizierende Zyklen zuenden; Unterbrechung nullt). */
        val zeroLatchArmStreak: Int = 0,
        /** Korrekturpfad-Riegel (25.08.): die vollstaendigen Urteile beider
         *  Schutzlinien - null, wenn der Pfad sie nicht gerechnet hat
         *  (Fallback/Abort). */
        val correctionReversal: CorrectionReversalGuard.Result? = null,
        val correctionRearm: PositiveCorrectionRearm.Result? = null,
        /** Der ContextReason der autoritativen Klassifikation (Review-
         *  P0.2) - Diagnose, warum der Kontext (nicht) Korrektur war. */
        val correctionContextReason: String? = null,
        /** Die orthogonale MAHLZEITENBASIS derselben Klassifikation -
         *  worauf die Mahlzeit beruht (Beleg oder blosse Kinematik). Sie
         *  entscheidet zusammen mit dem Kontext ueber die Schutzfreigabe
         *  und ist NICHT aus dem Grund erschliessbar. */
        val correctionMealBasis: String? = null,
        /** Ob der Zyklus im REINEN Korrekturkontext lief (kein Marker,
         *  keine MEAL-Frist, keine Fundament-Phase). */
        val correctionContext: Boolean = false,
        val zeroLatchOverrode: Boolean = false,
        /** Die BASIS-Ratio des Kanals VOR dem Profildeckel (Toni 24.08.):
         *  im MEAL-Profil die R-Rampe, im CORRECTION-Profil die
         *  Korrektur-Ratio. Nur gesetzt, wenn die Kandidatenrechnung lief.
         *  Getrennt von state.smbRatioEffective, die das
         *  Normalpfad-Fenster gated - genau diese Differenz war der
         *  unsichtbare 0,15-Livefall. */
        val livenessBaseRatio: Double? = null,
        /** Die im Kanal WIRKSAME Ratio = min(baseRatio, Cap). Nur
         *  gesetzt, wenn die Kandidatenrechnung lief; zusammen mit
         *  baseRatio und policy.livenessRatioCap ist die
         *  Kappung offline vollstaendig nachrechenbar (Vertrag: Export von
         *  baseRatio, liveRatio und Cap). */
        val livenessLiveRatio: Double? = null,
        /** Die in DIESEM Zyklus wirksame Druck-Schwelle und ihre Quelle
         *  (DAY|NIGHT, v20) - fuer die Anzeige "Live wartet - BG 151/160". */
        val livenessBgMinEffectiveMgdl: Double? = null,
        val livenessBgMinSource: String? = null,
        /** Rest des STRENGSTEN Kanaldeckels [U] in diesem Zyklus - nur im
         *  aktiven Lauf gerechnet (null sonst). 0 = Deckel voll. */
        val livenessHeadroomU: Double? = null,
        val livenessLiftU: Double = 0.0,
        val livenessBinding: String? = null,
        val livenessDenial: String? = null,
        val livenessExit: String? = null,
        /** Der TYPISIERTE Grund des Modell-Tors (CandidateSearch.Reject)
         *  dieses Zyklus - null, wenn die Integritaetskette bestanden ist.
         *  Nur im Hauptpfad gefuellt. */
        val livenessModelReject: String? = null,
        val livenessReArmUntilTs: Long = 0L,
        /** Master-Schalter + Sammel-Epoche der Prognose-Shadows - damit
         *  "bewusst aus", "alter Build" und "neue Messreihe" im Trail
         *  unterscheidbar sind. */
        val forecastShadowEnabled: Boolean = true,
        val forecastShadowEpochTs: Long = 0L,
        val descentLatchedAtTs: Long = 0L,
        /** KUMULATIV in dieser Episode publiziertes Insulin [U] - die
         *  Bezahlseite des Stoerungsbestands. Laeuft bis EXPIRED weiter,
         *  auch waehrend DORMANT und waehrend eines Widerrufs. */
        val evidenceCommittedU: Double = 0.0,
        /** Alter der Episode [min]; `null` = keine. Mit dem Deckel zusammen
         *  ergibt es die Viewer-Zeile "Episode 287 min, Deckel 360". */
        val evidenceEpisodeMin: Int? = null,
        /** Der Deckel DIESES Zyklus [min] - aus derselben Config-Instanz, die
         *  auch Tor und Kern gespeist hat. Ein zweites `Config()` im Export
         *  koennte bei einem Replay etwas anderes behaupten. */
        /** OHNE DEFAULT (Toni 12.08.): ein `EvidenceStock.Config()` hier waere
         *  die zweite versteckte Konstruktion gewesen - der Export haette bei
         *  einem Replay den Default gezeigt statt des gelaufenen Deckels. */
        val evidenceEpisodeCapMin: Int,
        /**
         * Phase, Bestand und Grund aus [EvidenceStock] - `null`, solange der
         * Kern nicht im Zyklus rechnet (Stufe 4).
         *
         * Nullbar statt vorbelegt, und das ist der Punkt: "DORMANT" oder 0,0
         * einzutragen waere eine Aussage ueber einen Kern, der gar nicht
         * laeuft - im Export nicht von einer echten Messung unterscheidbar.
         */
        val evidencePhase: String? = null,
        val evidenceStockMgdl: Double? = null,
        val evidenceReason: String? = null,
        /** Der Kredit, der in DIESEM Zyklus die Sicherheitskante gehoben hat
         *  [mg/dl/min]. `null` = kein Evidenzkern gelaufen. */
        val evidenceCreditMgdlPerMin: Double? = null,
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

    /**
     * @param pumpe der EINMAL je Zyklus erhobene Pumpensnapshot.
     *
     * Der Runner liest die Pumpe NICHT mehr selbst. Vorher tat er es an
     * zwei Stellen (Riegel und Bolusschritt), zusaetzlich zum Plugin - drei
     * Lesungen je Zyklus, obwohl ein Kommentar ausdruecklich EINE behauptete.
     * Wechselt die Pumpe dazwischen, kann der Riegel eine ECHTE Medtrum
     * durchlassen, waehrend die Ledgerzeile als emuliert gepinnt wird - und
     * damit ohne Patchpruefung bindet (Auditbefund 10.08.2026).
     */
    fun run(tempBasalFallback: Boolean, pumpe: FuseActivePump): Outcome {
        val computeTs = dateUtil.now()
        val gate = pumpe.gate

        // ---- Marker und Evidenz-Episode: GANZ VORNE ------------------------
        //
        // Sie standen bis zum 12.08. mitten im Zyklus, hinter Profil, Signal
        // und Observer. Das war genau an der falschen Stelle: der
        // wahrscheinlichste Moment fuer `MARKER_EVENT_NOT_DURABLE` ist der
        // Neustart - und da bricht der Zyklus mangels Signalhistorie erst
        // einmal ab. Der Nutzer sah "ABBRUCH" und keinen Hinweis darauf, dass
        // sein Marker keine Episode hat und er zweimal druecken muss.
        //
        // Hier braucht es nur Uhr, Preference und Ledger; nichts davon haengt
        // an Signal oder Profil. Damit tragen ALLE Ausgaenge den Zustand,
        // auch die Abbrueche.
        val markerTs = preferences.get(FuseLongKey.MealMarkerArmedTs).takeIf { it > 0L }
            ?: (preferences.get(FuseLongKey.MealMarkerStamp).takeIf { it > 0L }?.div(10L) ?: 0L)
        // Die Episoden-Wahl "ohne Vorschuss" (s. FuseOverviewSource.fuseMarkerToggle):
        // sie unterdrueckt NUR das markerfinanzierte Insulin (Sofort-Freigabe
        // und Erklaerungs-Kredit). Fenster, Rampen, Onset-Kanal und die
        // Totband-Oeffnung bleiben - die Mahlzeit ist ja erklaert. Ein
        // Altbestand-Stempel kennt die Wahl nicht und laeuft mit voller Huelle.
        val markerNoPrime = markerTs > 0L && preferences.get(FuseLongKey.MealMarkerNoPrime) != 0L
        val episodes = ledger.episodes
        // DER WIRKSAME EPISODENDECKEL (Toni 16.08., Fall 1 des Audit-Nachtrags).
        //
        // Ein frischer Markerdruck haelt die laufende Episode am Leben, statt
        // sie mitten in der zweiten Mahlzeit ablaufen zu lassen. BEIDE
        // Deckelpruefungen benutzen denselben Wert - das Tor unten (erben vs.
        // eroeffnen) und der Bestand in EvidenceStock. Zwei Deckel, die
        // auseinanderdriften, waeren die naechste Falle dieser Art.
        //
        // `episodes.evidenceEpisodeId` IST der Episodenbeginn (der Anker ist
        // der eroeffnende Markerzeitpunkt), deshalb geht er hier als
        // episodeStartTs hinein.
        val evidenceCapMs = EpisodeDeadline.effectiveCapMs(
            baseCapMs = evidenceConfig.maxEpisodeMin * 60_000L,
            episodeStartTs = episodes.evidenceEpisodeId,
            lastMarkerTs = markerTs,
        )
        val episodeGate = MarkerEpisodeGate.decide(
            nowMs = computeTs,
            markerTs = markerTs,
            ledgerEpisodeId = episodes.evidenceEpisodeId,
            lastConsumedMarkerTs = episodes.lastConsumedMarkerTs,
            observedPressTs = markerPressObserved(),
            capMs = evidenceCapMs,
            revokedPersisted = episodes.evidenceRevoked,
        )
        val evidenceEpisodeId = episodeGate.episodeId
        // Fuer die spaetere Armierung des Fundaments gemerkt: dort ist `cfg`
        // verfuegbar, hier noch nicht (s. die Armierungsstelle).
        val episodeOpenedThisCycle = episodeGate.opened
        // OPTION A (Toni 15.08.): ein waehrend der laufenden Episode
        // gedrueckter Marker wird sofort verbraucht - er darf nach dem
        // Deckelende des Vorgaengers keine stille Folgeepisode mehr
        // eroeffnen. Der 360-Deckel ist damit hart.
        episodeGate.consumeInheritedPressTs?.let {
            episodes.lastConsumedMarkerTs = maxOf(episodes.lastConsumedMarkerTs, it)
        }
        if (episodeGate.opened) {
            // SOFORT VERBRAUCHEN, nicht erst bei der ersten Dosis: sonst
            // eroeffnete ein Zyklus ohne Abgabe die Episode und ein spaeterer
            // Neustart faende den Anker unberuehrt vor.
            episodes.lastConsumedMarkerTs = maxOf(episodes.lastConsumedMarkerTs, markerTs)
            episodes.evidenceEpisodeId = evidenceEpisodeId
            episodes.evidenceCommittedU = 0.0
        }
        // WIDERRUF ZUERST FESTSCHREIBEN, dann weiterrechnen. Die Reihenfolge
        // ist die Zusicherung: der Stand steht im Ledger, bevor irgendein
        // Kanal dieses Zyklus ihn lesen koennte. Das UNBEDINGTE Versiegeln
        // ist Stufe 3; bis dahin traegt der zustandslose Teil der Regel den
        // Absturzfall (Preference auf 0 = zurueckgenommen, auch nach Neustart).
        episodes.evidenceRevoked = episodeGate.creditRevoked
        // ---- DIE RUECKNAHME BEENDET AUCH DAS FUNDAMENT (Toni 19.08., P0) --
        //
        // Ohne das bliebe die gepinnte Autorisierung nach einer manuellen
        // Ruecknahme gueltig, und `MealFoundation.snapshot` lieferte weiter
        // PHASE_B mit `dueU > 0`. Dass `manualMarkerAuthorized` danach false
        // ist, hilft nicht: der Lift liest die GEPINNTE Autorisierung, genau
        // wie es der Pinning-Vertrag verlangt.
        //
        // NUR BEIM AUSDRUECKLICHEN WIDERRUF, nicht beim blossen Ablauf des
        // 90-Minuten-Fensters. Der Unterschied ist der ganze Punkt: ein
        // ausgelaufener Marker hat seine Mahlzeit erklaert und das Fundament
        // darf sie zu Ende versorgen; ein zurueckgenommener hat die Erklaerung
        // widerrufen. `episodeGate.creditRevoked` ist der typisierte Pfad
        // dafuer - der Ablauf laeuft ueber andere Gruende.
        if (episodeGate.creditRevoked) {
            // DER WIDERRUF LOESCHT AUCH DAS REBOUND-SONDERRECHT (Toni 19.08.).
            // Er ist ein ausdruecklicher menschlicher Widerruf der Mahlzeit -
            // dann darf die Evidenz erst recht kein Schutzband mehr
            // entwaffnen.
            episodes.markerReboundOverrideDeadlineTs = 0L
            episodes.markerReboundOverridePinnedFor = 0L
            episodes.foundation = MealFoundation.Authorization.none()
            episodes.deliveredSinceHandoverU = 0.0
            episodes.deliveredPhaseAU = 0.0
            // Aufschub-Merker und Uebertrag gehoeren zur Autorisierung -
            // neuer Marker, Widerruf und Ablauf loeschen beides (Punkt 4).
            episodes.upfrontBatchDeferredSince = 0L
            episodes.upfrontTransferredU = 0.0
            // UND DER UEBERTRAG MIT (Toni 19.08.). Er gehoert zu der
            // Autorisierung, die hier gerade endet. Bliebe er stehen, gaebe der
            // ausdrueckliche Widerruf der NAECHSTEN Mahlzeit zusaetzliches
            // Insulin fuer eine Luecke aus der widerrufenen - der Widerruf
            // haette dann mehr Insulin zur Folge als das Zulassen.
            episodes.confirmedNotSentPhaseAU = 0.0
            episodes.descentDeferredPhaseAU = 0.0
        }
        val evidenceEpisodeMin = evidenceEpisodeId.takeIf { it > 0L }
            ?.let { ((computeTs - it) / 60_000L).toInt() }

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

        // `prediction`/`restraint` mit Default null: drei Abbruchstellen liegen
        // NACH dem Bahnbau (bolusStep, iob unknown, state-Guard). Ohne sie
        // schrieb der Trail dort `hub: {main: null, restraint: null}`, obwohl
        // BEIDE Bahnen gerechnet waren - ein Verlust, der wie ein Nichtvorhandensein
        // aussieht.
        fun abort(
            reason: String,
            signal: FuseSignalSource.Signal? = null,
            policy: Config? = null,
            step: ObserverStep? = null,
            prediction: PredictorResult? = null,
            restraint: PredictorResult? = null,
            /**
             * Das EvidenceStock-Ergebnis, falls der Kern VOR diesem Abbruch
             * schon gerechnet hat. Ohne es behauptete der Trail auf den drei
             * Post-step-Abbruechen EVIDENCE_NOT_EVALUATED_THIS_CYCLE, obwohl
             * ausgewertet UND versiegelt wurde - und kontaminierte damit
             * genau die Zyklen, die eine Auswertung braucht (Befund 15.08.).
             */
            evidenz: EvidenceStock.Result? = null,
        ): Outcome {
            // SUB-02 (Codex Fix-Pass-5-Closure): der Rest-Zaehler ist
            // aufgeschobene ABSICHT, kein Guthaben. Jeder Abbruch - Signal,
            // Profil, Config, Epoch, Kernel, Zeitachse - beendet den Kontext,
            // in dem die Absicht entstand. abort() ist der EINE Ausgang, an
            // dem alle diese Pfade vorbeikommen; hier zu verwerfen, deckt sie
            // alle ab, statt sie einzeln nachzupflegen.
            subStepCarryU = 0.0
            // Codex 22.08.: ein Abbruchzyklus ist fuer den Liveness-Kanal
            // ein unbeobachteter Zyklus - ein aktiver Lauf endet hier.
            val livenessLostExit = livenessObservationLost(episodes, computeTs, policy?.livenessReArmMin)
            val (cancelTbr, tbrAlarm) = abortTbr()
            // Auch ein Abbruch kennt die Basiswerte (Toni 08.08.: nie
            // verstecken) - jede Lesung einzeln tolerant, ein Abbruch darf an
            // der Anreicherung nicht scheitern.
            val maxIob = runCatching { constraintsChecker.getMaxIOBAllowed().value() }.getOrNull()
            val iobTh = if (policy != null && maxIob != null)
                runCatching { IobThreshold.fromPercent(policy.iobThPercent.toDouble(), maxIob) }.getOrNull() else null
            val iob = runCatching {
                // Auch die reine ANZEIGE im Abbruchbericht darf keine
                // erfundene Null zeigen - null heisst hier "nicht bekannt".
                profileFunction.getProfile(computeTs)
                    ?.let { p -> iobCobCalculator.calculateFromTreatmentsAndTemps(computeTs, p) }
                    ?.takeIf { it.valid }?.iob
            }.getOrNull()
            return Outcome(
                decision = FuseController.noInput(reason), tbr = cancelTbr,
                // Der Abbruchpfad hat keinen Regellauf, aber sehr wohl eine
                // TBR-Wirkung (FuseAbortTbr). Sie muss gestempelt werden wie
                // jede andere - sonst waere ausgerechnet der Notausgang der
                // ungezaehlte Eingriff.
                tbrChanged = tbrAktuation(cancelTbr, computeTs, profileFunction.getProfile(computeTs), null, pumpe.basalStepUPerH),
                prediction = prediction, restraint = restraint,
                sourceTs = signal?.sourceTs, computeTs = computeTs, health = step?.health, gate = gate,
                reason = reason, alarm = tbrAlarm, bgMgdl = signal?.q1, targetMgdl = null, targetSource = null,
                signal = signal, band = null, discount = null, onset = null, prime = null, candidate = null, candidateGap = null, policy = policy, state = null, step = step,
                sensorEpoch = null, calibrationEpoch = null,
                isfMgdlPerU = null, iobU = iob, iobThU = iobTh, maxIobU = maxIob, computeDurationMs = null, mealStats = null, abortReason = reason,
                livenessExit = livenessLostExit,
                livenessReArmUntilTs = episodes.livenessReArmUntilTs,
                // AUCH IM ABBRUCH. Nach einem Neustart ist der Abbruch der
                // WAHRSCHEINLICHE Ausgang, und genau dort muss der Nutzer
                // erfahren, dass sein Marker keine Episode hat.
                evidenceEpisodeId = evidenceEpisodeId,
                evidenceEpisodeDenial = episodeGate.denial?.name,
                evidenceCreditRevoked = episodeGate.creditRevoked,
                evidenceCommittedU = episodes.evidenceCommittedU,
                evidenceEpisodeMin = evidenceEpisodeMin,
                evidenceEpisodeCapMin = (evidenceCapMs / 60_000L).toInt(),
                evidencePhase = evidenz?.phase?.name,
                evidenceStockMgdl = evidenz?.state?.stockMgdl,
                evidenceReason = evidenz?.noInflow?.name,
                evidenceCreditMgdlPerMin = evidenz?.creditMgdlPerMin,
                // Der Abbruch fuehrt keinen Erholungszyklus aus, darf einen
                // bereits geschlossenen Riegel im Trail aber nicht als offen
                // darstellen. Zustand unveraendert, Runtime nur angezeigt.
                descentLatchActive = episodes.descentRecoveryLatch.active,
                descentLatchReason = if (episodes.descentRecoveryLatch.active) "ABORT_UNCHANGED" else null,
                descentRecoveryCycles = episodes.descentRecoveryRuntime.consecutiveRecoveryCycles,
                descentLatchedAtTs = episodes.descentRecoveryLatch.latchedAtTs,
            )
        }

        val profile = profileFunction.getProfile(computeTs) ?: return abort("no profile")
        // EIN Datenbank-Snapshot fuer die manuelle Deckung UND den spaeteren
        // Ledger-Abgleich. Zwei Abfragen koennten im selben Zyklus zwei
        // verschiedene Welten sehen. Lazy, damit fruehe Abbruchpfade keine
        // unnoetige DB-Arbeit erzeugen.
        val treatmentView by lazy(LazyThreadSafetyMode.NONE) {
            runCatching { buildTreatmentView(computeTs, profile.dia) }.getOrNull()
        }

        // Beide Epochen EINMAL je Zyklus. Sie begrenzen die Signalreihe UND
        // steuern die Health-Gruende des Observers - zwei verschiedene Lesungen
        // waeren zwei verschiedene Zustaende in derselben Momentaufnahme.
        val sensorEpoch = sensorEpoch()
        val calibrationEpoch = calibrationEpoch()

        // ---- 0 Zeuge -------------------------------------------------------
        // C3-02 (P0): DER ZEUGE MUSS HIER STEHEN, VOR DER SIGNALSTUFE.
        //
        // Der Vertrag in TransportInclusion behauptete, der Zeuge werde vor
        // dem ERSTEN calculateFromTreatmentsAndTemps gelesen. Das war als
        // Tatsachenaussage FALSCH: er stand vor dem ARRAYBAU, aber nach rund
        // 19 IOB-Aufrufen der Signalstufe (FuseSignalSource ruft je Rohpunkt
        // des Fensters auf). Der LETZTE dieser Aufrufe laeuft auf sourceTs -
        // und schreibt damit den iobTable-Eintrag am Schluessel
        // roundUpTime(sourceTs), also genau den, den der Arraybau spaeter
        // fuer Punkt 0 wieder liest. roundUpTime rundet auf die volle MINUTE
        // (AutosensDataStoreObject: (t/60000+1)*60000), der Schluessel liegt
        // also haeufig schon in der Vergangenheit und ist cachefaehig.
        //
        // Die schaedliche Folge sah so aus:
        //   t1  Signalstufe rechnet auf sourceTs und SCHREIBT den Eintrag
        //   t2  ein Bolus wird gebucht
        //   t3  der Zeuge sieht ihn -> der Posten faellt auf commitmentU
        //   t4  der Arraybau liest den Eintrag von t1 - ohne den Bolus
        // Die Menge verliess die Transport-Modellierung und fehlte zugleich
        // in Punkt 0.
        //
        // Vor der Signalstufe gilt dagegen per Konstruktion:
        //   Zeugeninhalt <= DB-Stand beim Schreiben des Eintrags.
        // Der Nachweis haengt jetzt an der REIHENFOLGE statt an einer
        // Behauptung ueber sie, und die Richtung ist konservativ - ein frueher
        // gelesener Zeuge sieht WENIGER, also bleiben MEHR Posten Transport.
        //
        // Keine zusaetzliche Abfrage: dasselbe Buchungs-Tor wie vorher.
        val transportItems = ledger.openTransportItems()
        val iobWitness =
            if (transportItems.none { it.accountedAmountU > 0.0 }) null
            else runCatching { iobSnapshotWitness(computeTs, profile.dia) }.getOrNull()

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

        // ---- FENSTERWECHSEL = MODELLWECHSEL (Toni-Vertrag 23.08., Pkt. 5) --
        // Der Evidenz-Bestand wurde unter dem ALTEN Schaetzer verdient und
        // wird beim Wechsel nicht weiterverwendet: Schnitt wie bei SUSPENDED
        // (Bestand 0, Messbasis neu aufsetzen). Offene ERWARTUNGEN entwertet
        // der Ledger selbst - das Fenster steht ab v22 im Politik-Hash, der
        // die configGeneration jedes Eintrags ist (Denial.CONFIG_CHANGED).
        // Erstkontakt (Altdatei ohne Feld, Wert 0) schneidet NICHT: der
        // Bestand entstand unter dem bis dahin einzigen Fenster W18.
        if (episodes.theilSenWindowLastMin != cfg.theilSenWindowMin.toLong()) {
            if (episodes.theilSenWindowLastMin > 0L) {
                episodes.evidenceState = episodes.evidenceState.copy(stockMgdl = 0.0, rebaseRequired = true)
            }
            episodes.theilSenWindowLastMin = cfg.theilSenWindowMin.toLong()
        }

        // Mittel- UND Untergrenze aus DEMSELBEN Aufruf. Es darf keinen Zustand
        // "Mittel da, Band fehlt" geben: ein Rueckfall auf lower = mean wuerde
        // den Null-Abstand ausgerechnet bei der schlechtesten Datenlage still
        // wiederherstellen.
        val band = PairSlopeBand.estimate(
            signal.adjusted.points, signal.sourceTs, cfg.driveLowerQuantilePct,
            // Seit v22 kommt das Fenster aus der Einstellung (Toni-Vertrag
            // 23.08.); der Konstruktor-Override des Phase-2-Replay-Treibers
            // gewinnt weiterhin, am Geraet ist er konstruktionsbedingt null.
            windowMs = theilSenWindowMsOverride ?: (cfg.theilSenWindowMin * 60_000L),
        )
            ?: return abort("drive not estimable (${signal.samplesUsed} samples)", signal, cfg, step)

        // Bolus-Aktivitaet am Anker - Eingang des Deckungs-Abschlags.
        // `calculateIobFromBolus()` rechnet auf `dateUtil.now()`, bis zu ~1 min
        // neben `sourceTs`. Fuer einen ABSCHLAG (keinen Bahnpunkt) ist der
        // Versatz zweitrangig; die Zahl steht im Export und ist nachpruefbar.
        if (signal.q1 < FuseController.REBOUND_LOW_MGDL) lastLowTs = signal.sourceTs
        val reboundRaw = lastLowTs > 0 &&
            signal.sourceTs - lastLowTs < FuseController.REBOUND_WINDOW_MIN * 60_000L

        // UNBEKANNT IST NICHT NULL (Codex Re-Audit 3b5cadbf, F1 - P0). Auch
        // diese Lesung laeuft ueber `calculateIobFromBolusToTime` und liefert
        // ohne aktuelles Profil ein genulltes, aber ENDLICHES Objekt. Die
        // spaetere Kappenlesung (Z. 759) faengt den Dauerfall, NICHT aber das
        // TOCTOU-Fenster: Profil hier kurz weg, bei der Kappenlesung wieder da
        // -> der Zyklus liefe mit einer erfundenen Bolusaktivitaet von 0
        // weiter. Diese Zahl speist den Deckungs-Abschlag; eine Null darin
        // laesst den Drive zu negativ erscheinen und ERHOEHT den Bedarf.
        // Richtung also nicht konservativ - deshalb fail-closed statt Fallback.
        val bolusIob = iobCobCalculator.calculateIobFromBolus()
        if (!bolusIob.valid) return abort("bolus iob unknown (no profile)", signal, cfg, step)
        val bolusActivityUPerMin = bolusIob.activity

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
        // EIN Marker, EINE Huelle (11.08.): der Knopf sagt WANN, nicht wieviel.
        // Der Altbestand-Stempel wird nur noch gelesen, falls `armedTs` leer
        // ist - der Fall tritt genau einmal auf, bei einem Marker, der beim
        // Update dieser Version gerade lief.
        // `markerTs` und `episodes` stehen am Kopf von run() - dort haengt
        // auch das Evidenz-Episodentor, damit Abbrueche seinen Grund tragen.
        // Hier steht nur noch der PRIME-Reset: ein neuer armedTs ist eine
        // neue Episode mit voller Huelle.
        // AKTIVITAET UND BUDGET SIND ZWEI DINGE (11.08.).
        //
        // Vorher war die Episode am `markerTs` festgemacht: eine Ruecknahme
        // nullte ihn, der naechste Druck erzeugte einen neuen - und damit eine
        // NEUE Episode mit voller Huelle. "Versehentlich druecken,
        // zuruecknehmen, richtig druecken" gab die ganze Huelle ein zweites
        // Mal, obendrauf auf das bereits Abgegebene. Genau die
        // Doppelfinanzierung, gegen die die Episodenbudgets existieren - und
        // sie wiegt schwer, seit der Marker bei gemessenem Tief dosieren darf.
        //
        // Jetzt laeuft die EPISODE weiter, solange das Fenster der letzten
        // Armierung nicht abgelaufen ist - unabhaengig davon, ob der Marker
        // gerade aktiv ist. Die Ruecknahme beendet die Aktivitaet, nicht die
        // Buchfuehrung.
        //
        // `primeArmedTs` ist persistent, die Regel ueberlebt also einen
        // Neustart.
        // VORGEZOGEN (Toni 19.08.): die Armierung des Fundaments gehoert in
        // den Episodenblock unten, und dafuer muessen beide Groessen schon
        // stehen. Sie haengen nur an markerTs, computeTs und cfg - alle drei
        // sind hier verfuegbar.
        val mealMarkerActive = markerTs > 0 &&
            computeTs - markerTs in 0..(OnsetChannel.MARKER_WINDOW_MIN * 60_000L)
        val manualMarkerAuthorized = cfg.markerAuthorized && mealMarkerActive && markerTs > 0

        val neueEpisode = MarkerEpisode.startsNewEpisode(
            armedTs = markerTs,
            episodeTs = episodes.primeArmedTs,
            nowMs = computeTs,
            windowMin = OnsetChannel.MARKER_WINDOW_MIN,
        )
        if (neueEpisode) {
            // Wirklich eine neue Episode: die vorige ist abgelaufen.
            episodes.primeArmedTs = markerTs
            episodes.primeSpentU = 0.0
            episodes.primeWindowStartTs = 0L

            // ---- DAS MAHLZEITENFUNDAMENT ARMIEREN (Toni 19.08., P0) -------
            //
            // HIER UND NICHT AN `episodeGate.opened`. Das Gate gehoert zur
            // EVIDENZ-Episode, die bis zu 360 Minuten laufen kann; das
            // Fundament gehoert zum PRIME-/Markerbudget, das mit
            // `startsNewEpisode` neu bewaffnet wird. Ein zweiter bewusster
            // Druck nach Ablauf des Primefensters setzt Prime zurueck,
            // waehrend dieselbe Evidenzepisode weiterlaeuft - dann waere das
            // Fundament NICHT neu armiert, und Prime laese ueber
            // `primeBudgetU` weiter das ALTE gepinnte Phase-A-Budget.
            //
            // UND ES SCHLIESST DIE ABBRUCHLUECKE: bricht der erste Zyklus
            // nach dem Druck vorher ab, bleibt `primeArmedTs` unveraendert,
            // `neueEpisode` ist im naechsten gesunden Zyklus wieder wahr und
            // die Armierung wird nachgeholt. An `episodeGate.opened` waere
            // sie dauerhaft verloren gewesen.
            //
            // Das GEMEINSAME Budget ist die Prime-Huelle - Phase A und B
            // teilen sie, sie addieren sich nicht.
            episodes.foundation = MealFoundation.arm(
                markerTs = markerTs,
                foundationEnabled = cfg.mealFoundationEnabled,
                totalBudgetU = cfg.primeEnvelopeU,
                phaseAShare = cfg.mealFoundationPhaseAShare,
                phaseAUpfrontShare = cfg.mealFoundationPhaseAUpfrontShare,
                primeWindowMin = cfg.primeWindowMin,
                wallCeilingMin = PrimeRelease.WALL_CEILING_MIN,
                phaseBUntilMin = cfg.mealFoundationEndMin,
                markerAuthorized = manualMarkerAuthorized,
                // IDENTITAET, NICHT NUR VORHANDENSEIN (Toni 19.08.). Hier
                // stand `> 0L` - damit haette IRGENDEIN frueher beobachteter
                // Druck gereicht, auch einer aus einer laengst beendeten
                // Mahlzeit. Der Vertrag verlangt, dass GENAU DIESER Marker
                // beobachtet wurde; `MarkerEpisodeGate` erzwingt dieselbe
                // Identitaet bereits fuer die Evidenzepisode.
                pressObservedInThisProcess = markerPressObserved() == markerTs,
                primeDeclinedByUser = markerNoPrime,
            )
            // Eine neue Autorisierung beginnt mit unbezahlter Phase B.
            episodes.deliveredSinceHandoverU = 0.0
            episodes.deliveredPhaseAU = 0.0
            // Aufschub-Merker und Uebertrag gehoeren zur Autorisierung -
            // neuer Marker, Widerruf und Ablauf loeschen beides (Punkt 4).
            episodes.upfrontBatchDeferredSince = 0L
            episodes.upfrontTransferredU = 0.0
            // UND OHNE UEBERTRAG (Toni 19.08.). Ein neuer Markerdruck ist eine
            // neue Mahlzeit mit eigenem Budget; eine Luecke aus der vorigen
            // darf sie nicht erben. Das steht hier UND beim Widerruf, weil es
            // zwei verschiedene Wege sind, eine Episode zu beenden - ein
            // gemeinsamer Reset weiter unten wuerde nur einen von beiden
            // treffen.
            episodes.confirmedNotSentPhaseAU = 0.0
            episodes.descentDeferredPhaseAU = 0.0
            // Fix 7: neue Marker-Episode -> Wende-Latch der Sonderrechte neu.
            episodes.markerTurnTs = 0L
            episodes.markerRiseSeen = false
        }
        if (neueEpisode) {
            episodes.mealArmedTs = markerTs
            episodes.mealDeliveries.clear()
        }
        // DIE EPISODEN-IDENTITAET fuer den Stoerungsbestand (Stufe 1).
        //
        // Bewusst ENG gefasst: nur ein Markerdruck eroeffnet eine Episode. Ein
        // Anstieg ohne Ankuendigung bekommt vorerst keinen Bestand - ob er
        // einen bekommen soll, ist eine eigene Entscheidung und nicht
        // nebenbei zu treffen.
        //
        // LAENGER ALS `mealMarkerActive`: das Fenster der Sonderrechte endet
        // nach 90 Minuten, die Episode darf bis zum harten Deckel laufen. Der
        // gemessene Lauf vom 11.08. war nach 205 Minuten noch aktiv, mit einer
        // zweiten Welle ab T+120 - endete die Identitaet bei 90, begaenne dort
        // eine neue Episode mit frischem Deckel und frischem Zaehler.
        //
        // Es ist eine IDENTITAET, kein Zustand: der Wert bleibt derselbe,
        // auch wenn dazwischen nichts passiert - und auch, wenn der Marker
        // zwischendurch zurueckgenommen und neu gedrueckt wird.
        //
        // DER ANKER KOMMT AUS DEM LEDGER, nicht aus dem aktuellen
        // Markerzeitpunkt. Haenge er am aktuellen, erzeugte Ruecknahme plus
        // erneutes Druecken still eine neue Episode mit Zaehler 0, und
        // dieselbe Stoerung waere ein zweites Mal unbezahlt - genau die
        // Doppelfinanzierung, gegen die die Episodenbudgets ueberhaupt
        // existieren.
        //
        // GAS-VOR-BREMSE NUR FUER ERKLAERTES WISSEN (08.08., Fruehstueckstest):
        // das Rebound-Fenster schuetzt vor dem Jagen UNANGEKUENDIGTER Hypo-
        // Gegenesser. Ein gedrueckter Marker IST die Ankuendigung - er
        // entwaffnet die Heuristik-Bremse (Ratio-Deckel, Totband, tau-
        // Kuerzung). Guard-Floor, Freigabe-Tor und Huellen bleiben unberuehrt.
        // Fix 7 (Audit R95 NEU-01/02, Tonis Entscheid "bis zur Wende, max
        // 45 min"): die Marker-SONDERRECHTE (Rebound-Entwaffnung, Prior,
        // Marker-Zweig des Fensters) enden mit der nachhaltigen Wende oder
        // nach MarkerScope.BOOST_MAX_MIN - der Fruehstueckssturz vom 08.08.
        // fiel sonst noch in die entwaffnete Zone. KONTEXT (Freigabe-Huelle,
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

        // DOSIERNEUTRALER WENDE-SHADOW (Toni 20.08.). Dieselben Samples wie
        // der Onset-Kanal, aber eine andere Frage: dreht der schnelle,
        // BGI-bereinigte Antrieb bereits nachhaltig gegen den traegen
        // 18-min-Antrieb? Das Ergebnis wird unten nur fuer parallele Bahnen
        // und den Export verwendet. Weder `state` noch `decision` lesen es.
        // ---- PROGNOSE-SHADOW-MASTERSCHALTER (Toni/Codex 23.08.) ---------
        // EIN Schalter fuer beide Forschungs-Sammler (Tau-Matrix +
        // ADAPTIVE-DOWN-Lanes). Er wird NIE von Dosierlogik gelesen; die
        // Wende-KLASSIFIKATION unten ist Produktionseingang (Liveness-Exit)
        // und laeuft unabhaengig davon. Nicht im Policy-Hash. Jedes
        // Umschalten eroeffnet eine neue, restartfeste Sammel-Epoche -
        // Auswertungen duerfen keine Messluecke ueberbruecken.
        val forecastShadowEnabled = preferences.get(FuseBooleanKey.ForecastShadowCollectionEnabled)
        val forecastShadowEpochTs = run {
            val stand = if (forecastShadowEnabled) 1L else 0L
            if (episodes.forecastShadowLastState != stand || episodes.forecastShadowEpochTs <= 0L) {
                episodes.forecastShadowLastState = stand
                episodes.forecastShadowEpochTs = computeTs
            }
            episodes.forecastShadowEpochTs
        }
        val turnSamples = onsetRing.map {
            TurnResponseShadow.Sample(it.tsMs, it.ukfRatePerMin, it.fastDriveMgdlPerMin)
        }
        val turnClassification = TurnResponseShadow.classify(
            samples = turnSamples,
            slowDriveMgdlPerMin = band.mean,
            riseThresholdMgdlPerMin = cfg.riseRampLowR,
            signalHealthy = step.health == Health.READY,
            q1Outlier = signal.q1Outlier,
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
        // der Freigabe-Huelle - was die Episode schon geliefert hat (Sofort-
        // Freigabe ODER Rampe), zieht ihn herunter: EINE Huelle fuer beide
        // Pfade, die Erklaerung verbraucht sich selbst.
        val mealDeliveredU = if (markerTs > 0) episodes.mealDeliveries.sumOf { it.amountU } else 0.0
        val declaredDrive = if (markerBoost) MarkerScope.declaredAbsorptionDriveMgdlPerMin(
            // "Ohne Vorschuss" heisst auch: kein Erklaerungs-Kredit. Der Kredit
            // unterstellt kommende Absorption in Huellenhoehe - genau die
            // Unterstellung, die der Druck abgewaehlt hat.
            // DAS GEPINNTE Phase-A-Budget, nicht der Live-Wert (Toni 19.08.).
            // Der Kredit unterstellt kommende Absorption in HUELLENHOEHE -
            // liest er dabei eine spaeter geaenderte Einstellung, unterstellt er
            // eine andere Menge, als beim Markerdruck autorisiert wurde. Ohne
            // aktives Fundament gibt primeBudgetU unveraendert den Live-Wert
            // zurueck, das Verhalten bleibt also gleich.
            envelopeU = if (markerNoPrime) 0.0
            else MealFoundation.primeBudgetU(episodes.foundation, cfg.primeEnvelopeU),
            deliveredU = mealDeliveredU,
            isfMgdlPerU = profile.getIsfMgdlTimeFromMidnight(MidnightUtils.secondsFromMidnight(signal.sourceTs)),
            windowMin = cfg.absorptionCreditWindowMin.toDouble(),
        ) else 0.0

        val builtVorTrend = when (val b = CoreInputGuard.build { buildPredictorInput(signal, profile, cfg, band, bolusActivityUPerMin, if (onset.active) onset.driveMgdlPerMin else null, reboundWindow, markerBoost, declaredDrive, pending) }) {
            is CoreInputGuard.Outcome.Built  -> b.value ?: return abort("input incomplete", signal, cfg, step)
            is CoreInputGuard.Outcome.Failed -> return abort("input: ${b.failure.detail}", signal, cfg, step)
        }

        // ---- REPLAY-TRENDREGEL (Toni 23.08. Abend) ---------------------
        // Injiziert die Lane-Mathematik des Turn-Shadows als ECHTE Bahn:
        // UP hebt NUR die Mittelbahn auf max(mean, upwardMeanDrive) - die
        // Unterkante und damit Guard/Tail bleiben auf dem produktiven
        // Zeugnis, wie es der Shadow-Vertrag verlangt. DOWN_P2/P3 senken
        // die Mittelbahn auf min(mean, fastDrive) und ziehen untere und
        // prior-freie Kante mit (Bandordnung) - exakt die Formeln der
        // ADAPTIVE-DOWN-Lane inkl. ihres Tors (fast < slow, exponentieller
        // Produktiv-Tau vorhanden). Alles stromabwaerts - Prediction,
        // Guard, Tail, harte gemessene Riegel, Sub-Step, Wirkungspruefung,
        // Liveness - laeuft unveraendert auf der injizierten Bahn: die
        // Frage des Replays ist "was, WAERE das die Produktionsregel",
        // nicht die Schattenmessung mit produktivem Endriegel. Am Geraet
        // ist trendRuleOverride null und `built` referenzgleich.
        val trendDrive = when (trendRuleOverride) {
            null -> null
            "UP" -> {
                val up = turnClassification.upwardMeanDriveMgdlPerMin
                if (turnClassification.phase != TurnResponseShadow.Phase.TURNING_UP || up == null) null
                else builtVorTrend.input.drive.copy(
                    meanMgdlPerMin = maxOf(builtVorTrend.input.drive.meanMgdlPerMin, up),
                    uncertaintyMethodId = builtVorTrend.input.drive.uncertaintyMethodId + "+TURN_UP_LIVE",
                )
            }
            "DOWN_P2", "DOWN_P3" -> {
                val fast = fastDrive(signal)
                val tauDa = builtVorTrend.input.decay is DriveDecayModel.ExponentialDecay
                val streak = TurnResponseShadow.declineStreak(turnSamples)
                val noetig = if (trendRuleOverride == "DOWN_P3") 3 else 2
                if (fast == null || !tauDa || fast >= band.mean || streak < noetig) null
                else {
                    val gesenkt = minOf(builtVorTrend.input.drive.meanMgdlPerMin, fast)
                    builtVorTrend.input.drive.copy(
                        meanMgdlPerMin = gesenkt,
                        lowerMgdlPerMin = minOf(builtVorTrend.input.drive.lowerMgdlPerMin, gesenkt),
                        lowerPriorFreeMgdlPerMin = builtVorTrend.input.drive.lowerPriorFreeMgdlPerMin?.let { minOf(it, gesenkt) },
                        uncertaintyMethodId = builtVorTrend.input.drive.uncertaintyMethodId + "+TURN_DOWN_LIVE",
                    )
                }
            }
            else -> error("unbekannte Trendregel: $trendRuleOverride")
        }
        val built = trendDrive?.let {
            Built(builtVorTrend.input.copy(drive = it), builtVorTrend.iobAtH, builtVorTrend.isfTail, builtVorTrend.discount)
        } ?: builtVorTrend
        val trendAngewendet = built !== builtVorTrend

        // DER ABBRUCH IST AUFGESCHOBEN, NICHT AUFGEHOBEN (Tonis Auflage
        // 11.08.). Eine verworfene Trajektorie beendete den Zyklus hier -
        // also BEVOR `markerAuthorizedU` entstehen kann, und damit
        // ausserhalb der Reichweite des Bodens weiter unten. Dieselbe
        // Fehlerklasse wie der frueher bedingungslose Schwanz-Deckel, nur
        // eine Stufe frueher.
        //
        // KEIN pauschales Ueberspringen: die Ablehnung wird gemerkt, der
        // Zyklus laeuft bis hinter den Zustandsbau weiter (dort steht alles,
        // was ein Markerpfad braucht - Profil, ISF, IOB, Pumpenschritt,
        // Budgets), und DORT entscheidet [MarkerFallback], ob es einen
        // predictorfreien Pfad gibt. Gibt es keinen, wird derselbe Abbruch
        // nachgeholt.
        //
        // EIN Aufruf, zwei Sichten darauf: ein zweiter predict() waere eine
        // zweite Wahrheit ueber dieselbe Eingabe.
        val predicted = predict(built.input)
        val rejected = predicted as? PredictorOutcome.Rejected
        val predictionOrNull = (predicted as? PredictorOutcome.Ok)?.result

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
        // Ohne Hauptbahn keine Bremsbahn: sie ist DIESELBE Rechnung mit
        // einem anderen Antrieb und faellt aus demselben Grund. Ohne diese
        // Bedingung wuerde ihr eigener Abbruch-Zweig den aufgeschobenen
        // Markerpfad ueberholen und den Zyklus mit einem Grund beenden, der
        // die eigentliche Ursache verdeckt.
        val restraint = if (rejected != null || !cfg.fastRestraintEnabled) null else
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
                (predict(fi) as? PredictorOutcome.Ok)?.result
                    ?: return abort("fast restraint enabled but trajectory rejected", signal, cfg, step)
            }

        // ---- 4 Menge -------------------------------------------------------
        // Aus dem Zyklus-Snapshot, nicht aus einer zweiten Lesung: sonst
        // koennte der Schritt einer ANDEREN Pumpe stammen als der Riegel.
        val bolusStep = pumpe.bolusStepU
        // 0.0 waere GEFAEHRLICHER als ein Block: floor(x/0)*0 ergibt NaN, und
        // NaN < 0.0 ist false — der Zyklus fiele durch statt zu sperren.
        if (!bolusStep.isFinite() || bolusStep <= 0.0) return abort("bolusStep=$bolusStep", signal, cfg, step, predictionOrNull, restraint)

        val maxIobU = constraintsChecker.getMaxIOBAllowed().value()
        val iobTotal = iobCobCalculator.calculateFromTreatmentsAndTemps(computeTs, profile)
        // UNBEKANNT IST NICHT NULL (Codex Combined Closure b6dbb490, P0). Aus
        // dieser Lesung kommen capIob, netIob und der Bolusanteil - also die
        // Groessen, an denen JEDE Mengenkappe haengt. Eine erfundene Null
        // macht hier aus "8 U Spielraum belegt" ein "8 U frei".
        if (!iobTotal.valid) return abort("iob unknown (no profile)", signal, cfg, step, predictionOrNull, restraint)
        val isf = profile.getIsfMgdlTimeFromMidnight(MidnightUtils.secondsFromMidnight(signal.sourceTs))

        // FRUEH GERECHNET, WEIL BEIDE PFADE ES BRAUCHEN: der Fallback wird
        // weiter unten aufgerufen und muss denselben Riegel kennen.
        // DAS GEMESSENE ABWAERTSRISIKO - getrennt vom Basalnutzen
        // (Toni 19.08., P0). Es verbietet NEUES POSITIVES INSULIN und sagt
        // nichts ueber die TBR; die bleibt Sache des Nutzens weiter unten.
        //
        // Ein gemessenes Tief laeuft daran vorbei: es ist der schaerfere
        // Riegel (SAFETY_HOLD) und liegt vor dieser Frage.
        val descentRisk = LowThreatGate.measuredDescentRisk(
            signalHealthy = step.health == Health.READY,
            bgMgdl = signal.q1,
            fallRatePerMin = signal.ukfRatePerMin,
            bolusIobU = (iobTotal.iob - iobTotal.basaliob).takeIf { iobTotal.valid },
            isfMgdlPerU = isf,
            guardFloorMgdl = cfg.guardFloorMgdl,
            // NICHT das 120-minuetige TBR-Nutzenfenster. Ein Basalstopp kann
            // weit voraus sinnvoll sein; ein harter SMB-Endriegel muss eine
            // akute, gemessene Gefahr meinen. Die Kopplung hat am 21.08. die
            // komplette Phase A eines Fruehstuecks gesperrt.
            horizonMin = cfg.positiveDescentHorizonMin,
        )
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
                    // Rest des Fensters - reine Anzeigegroesse, siehe State.
                    // Sie haengt an `reboundRaw`, NICHT an `reboundWindow`: ein
                    // Marker entwaffnet die Bremse, laesst die Uhr aber laufen.
                    // Waere sie an `reboundWindow` gebunden, verschwaende die
                    // Restzeit beim Markerdruck und taeuschte ein beendetes
                    // Fenster vor.
                    reboundRestMin = if (reboundRaw)
                        ((lastLowTs + FuseController.REBOUND_WINDOW_MIN * 60_000L - signal.sourceTs) / 60_000L)
                            .toInt().coerceAtLeast(0)
                    else null,
                    reboundDeadbandMgdl = if (cfg.reboundDeadbandEnabled) cfg.reboundDeadbandMgdl else 0.0,
                    nightWindow = cfg.nightDeadbandEnabled && NightWindow.isNight(
                        MidnightUtils.secondsFromMidnight(signal.sourceTs), cfg.nightStartMin, cfg.nightEndMin
                    ),
                    nightDeadbandMgdl = if (cfg.nightDeadbandEnabled) cfg.nightDeadbandMgdl else 0.0,
                    markerBoost = markerBoost,
                    markerArmedTs = markerTs,
                    markerNoPrime = markerNoPrime,
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
            is CoreInputGuard.Outcome.Failed -> return abort("state: ${s.failure.detail}", signal, cfg, step, predictionOrNull, restraint)
        }

        // DER BEWUSSTE MARKERDRUCK IST DIE AUTORISIERUNG (Toni 11.08.,
        // nach einem Livefall). Zwei Groessen, und sie waren bis hierher
        // FALSCH VERSCHRAENKT:
        //
        //   manualMarkerAuthorized  Einstellung an UND Marker laeuft
        //   measuredLow             es liegt ein GEMESSENES Tief vor
        //
        // Ich hatte das gemessene Tief zur VORAUSSETZUNG der Autorisierung
        // gemacht. Es war aber nur der Anlass, an dem sie zuerst auffiel.
        // Gemessen am 11.08.: BG 105 fallend, Marker seit 3 min, alle
        // technischen Tore frei, iobTH/maxIOB je 8 U - und trotzdem 0 U,
        // weil `safetyReasons` leer war. Das ist der HAUPTFALL einer
        // Mahlzeit (normaler BG, Bahn faellt), nicht der Randfall.
        //
        // WAS DIE MENGE BEGRENZT, ist deshalb nicht mehr das Tief, sondern
        // die Huelle: der Lift kommt nie ueber `PrimeRelease.Plan.floorU`
        // hinaus, und der Boden weiter unten hebt nur auf genau diesen
        // Betrag. Der alte P0 (`all { it == LOW }` auf der leeren Menge)
        // ist damit gegenstandslos - er war gefaehrlich, WEIL der damalige
        // Sonderzweig den Guard fuer die GANZE Menge aufhob. Ein
        // mengenbegrenzter Boden kennt dieses Problem nicht.
        // (Die Definition steht jetzt weiter oben - sie wird schon fuer die
        // Armierung des Fundaments gebraucht.)


        // Nur noch fuer die parallele Schutz-Null und den Warntext - NICHT
        // mehr als Bedingung der Autorisierung.
        val measuredLow = step.safetyReasons == setOf(SafetyReason.LOW)

        // DER RESTARTFESTE WIEDERFREIGABE-RIEGEL. Das Rohsignal darf sofort
        // schliessen. Oeffnen darf erst eine lueckenlos bestaetigte Wende:
        // drei gesunde, nicht-tiefe Zyklen mit UKF >= +0,20 mg/dl/min.
        // Nur der Latch-Zustand wird persistiert; eine halbe Erholungsserie
        // beginnt nach Prozessabriss bewusst von vorn.
        val descentLatch = DescentRecoveryLatch.advance(
            state = episodes.descentRecoveryLatch,
            runtime = episodes.descentRecoveryRuntime,
            riskActive = descentRisk.active,
            signalHealthy = step.health == Health.READY,
            measuredLow = step.safetyReasons.isNotEmpty(),
            fallRatePerMin = signal.ukfRatePerMin,
            sourceTs = signal.sourceTs,
        )
        episodes.descentRecoveryLatch = descentLatch.state
        episodes.descentRecoveryRuntime = descentLatch.runtime

        // EIN Urteil fuer Haupt- und Fallbackpfad. Der gespeicherte
        // Sicherheitsaufschub darf erst in Phase B nach bestaetigter Erholung
        // wirken; ein echtes Tief, ungesundes Signal oder rohes
        // Rebound-Fenster sperrt ihn weiterhin.
        val foundationPhase = MealFoundation.phaseOf(
            episodes.foundation, computeTs, episodes.primeWindowStartTs,
        )
        val manualBolusAfterMarkerU = manualBolusAfterMarkerU(episodes.foundation, treatmentView)
        val descentCarryEligibility = DescentDeferredCarry.eligibility(
            deferredU = episodes.descentDeferredPhaseAU,
            phase = foundationPhase,
            latchBlocksPositive = descentLatch.blocksPositive,
            signalHealthy = step.health == Health.READY,
            measuredLow = step.safetyReasons.isNotEmpty(),
            reboundRaw = reboundRaw,
            manualBolusAfterMarkerU = manualBolusAfterMarkerU,
        )

        // ---- STUFE 3: DER EVIDENZBESTAND RECHNET, ABER ZAHLT NOCH NICHT ----
        //
        // Der Kern laeuft ab hier in jedem Zyklus mit. Sein Kredit ist
        // ABSICHTLICH noch an nichts angeschlossen - `ConditionalDrive` bekommt
        // ihn erst in Stufe 4. Das ist keine stille Parallelspur: Phase,
        // Bestand und Grund stehen in jedem Datensatz, und der Zustand wird
        // versiegelt wie jede andere Buchfuehrung.
        //
        // Der Zustand wird HIER auf den Folgestand gesetzt, damit ihn der
        // Persist dieses Zyklus mitnimmt. Scheitert der, rollt das Plugin ihn
        // zurueck - `LedgerPublicationGate.Outcome.sealed` sagt das aus, und
        // die Ruecknahme-Adresse liegt im Plugin VOR `run()`, nicht im
        // Outcome: eine Ausnahme hinter dieser Zeile liefert kein Outcome.
        //
        // WAS DIESE STELLE NICHT LEISTET, und das gehoert genannt: ein Zyklus,
        // der VOR der Signalstufe abbricht (kein Profil, kein Signal, Epoche
        // verbraucht), erreicht sie gar nicht und versiegelt daher auch keine
        // neue Messinformation. Das ist vertretbar, aber nicht folgenlos:
        // - Neue Evidenz gibt es dort ohnehin nicht - ohne Signal kein
        //   Messpunkt, ohne Messpunkt kein Zufluss.
        // - Der WANDUHR-VERFALL wird dadurch nicht verloren, sondern nur
        //   aufgeschoben: er rechnet beim naechsten erreichten Zyklus die
        //   ganze verstrichene Zeit auf einmal ab (`lastDecayTs`).
        // Die frueher hier stehende Aussage "jeder Abbruchzyklus versiegelt"
        // war falsch (Toni 12.08.); richtig ist: jeder Zyklus PERSISTIERT, aber
        // nur die mit Signal tragen neue Evidenz hinein.
        val evidenzVorher = episodes.evidenceState
        // DAS INTERVALL AUS EINER EINZIGEN `adjust()`-AUSGABE.
        //
        // Beide Punkte stammen aus derselben Liste und damit aus demselben
        // Bezugssystem - das ist der ganze Punkt. `cumulativeBgi` startet je
        // Aufruf am Fensteranfang bei 0, und der wandert jede Minute; zwei
        // Werte aus zwei Zyklen zu subtrahieren ergab keine Stoerung, sondern
        // den Unterschied zweier Nullpunkte (Befund 12.08.).
        //
        // Fehlt der Anker in dieser Ausgabe - aus dem Fenster gelaufen oder
        // vor einem Segmentbruch -, entsteht KEIN Intervall, und der Kern
        // setzt nur die Basis neu. Die Liste ist bereits auf das juengste
        // lueckenfreie Segment beschnitten (FuseSignalSource: `windowStart`),
        // die Segmentbedingung ist damit erfuellt, ohne sie mitzuschleppen.
        val intervall = app.aaps.fuse.core.signal.BgiAdjustedSeries.AdjustedInterval.of(
            serie = signal.adjusted,
            ankerTs = episodes.evidenceState.lastAcceptedTs,
        )

        val evidenz = signal.adjusted.points.lastOrNull()?.let { letzter ->
            EvidenceStock.step(
                prev = evidenzVorher,
                input = EvidenceStock.Input(
                    nowMs = computeTs,
                    sourceTs = signal.sourceTs,
                    // DERSELBE Deckel wie im Episodentor oben - nicht neu
                    // gerechnet, sondern derselbe Wert weitergereicht.
                    capMsOverride = evidenceCapMs,
                    interval = intervall,
                    driveLowerMgdlPerMin = band?.lower,
                    healthReady = step.health == Health.READY,
                    measuredLow = measuredLow,
                    creditRevoked = episodeGate.creditRevoked,
                    episodeId = evidenceEpisodeId,
                    episodeCommittedU = episodes.evidenceCommittedU,
                    isfMgdlPerU = isf,
                    // Der geladene Zustand gilt, solange der Ledger nicht
                    // haelt. Haelt er, ist die Buchfuehrung fraglich - und ein
                    // Bestand aus fraglicher Buchfuehrung darf nichts erlauben.
                    persistedStateKnown = !ledgerView.hold,
                ),
                cfg = evidenceConfig,
            )
        }
        evidenz?.let { episodes.evidenceState = it.state }

        // ---- DER PREDICTORFREIE MARKERPFAD (Toni 11.08.) -------------------
        //
        // Er steht GENAU HIER und keine Zeile frueher: alles, was er verlangt,
        // ist an dieser Stelle bereits geprueft und in Reichweite -
        //
        //   Signal frisch und monoton   Health.READY (in MarkerFallback)
        //   gemessenes Tief             step.safetyReasons == {LOW}
        //   gueltiges Profil            oben, sonst waere abgebrochen
        //   gueltige ISF und IOB        iobTotal.valid + CoreInputGuard
        //   gueltiger Pumpenschritt     bolusStep-Pruefung oben
        //   Transportmenge verbucht     transportModelledU, s. MarkerFallback
        //
        // Was DANACH kommt, kommt weiterhin: Huellen- und IOB-Deckel in
        // PrimeRelease.lift, Episodenbudget in plan, LedgerHoldGate, die
        // TBR-Tabelle, das Pumpen-Gate hier und das Publikations-Gate im
        // Plugin. Der Pfad ueberspringt die BAHN, keine Grenze.
        if (rejected != null) {
            val denial = MarkerFallback.denial(
                reason = rejected.reason,
                // BEIDE Bestandteile einzeln, obwohl `manualMarkerAuthorized`
                // ihr UND ist: die Ablehnungsgruende SETTING_OFF und NO_MARKER
                // sind im Export verschiedene Lagen, und ein zusammengefasstes
                // Bit haette sie ununterscheidbar gemacht.
                markerAuthorized = cfg.markerAuthorized,
                mealMarkerActive = mealMarkerActive && markerTs > 0,
                health = step.health,
                // DIE ZAHL, nicht ein Praedikat darueber. `isFinite()` stand hier
                // und war KEIN Beweis: 0,0 ist auch endlich, und ueber den Abzug
                // sagte es ohnehin nichts. Jetzt bekommt die Politik den Wert,
                // den auch der Lift bekommt - beide aus demselben Argument.
                transportCommitmentU = transportModelledU,
            )
            val warum = "predictor: ${rejected.reason} ${rejected.detail}"
            // Der Abbruch NENNT den verweigerten Fallback. Ohne diesen Zusatz
            // waere im Log ein nicht angebotener Pfad von einem abgelehnten
            // nicht zu unterscheiden.
            if (denial != null) return abort("$warum | noFallback=$denial", signal, cfg, step, evidenz = evidenz)

            // DER EINHEITSKERN MUSS TROTZDEM STEHEN (P0, 11.08.).
            //
            // Der Boden im Hauptpfad haengt an `kernelFinal != null` - aber
            // dieser Zweig kehrt VORHER zurueck und hatte `kernel()` nie
            // aufgerufen. Bei ARRAY_TOO_SHORT oder PENDING_MODEL_TOO_SHORT
            // haette der Marker also dosiert, ohne dass je geprueft wurde, ob
            // das aktive Insulinmodell ueberhaupt gueltig ist.
            //
            // Genau der Unterschied, auf dem der ganze Pfad beruht, war damit
            // unbewiesen: "die BAHN fehlt, das MODELL ist aber gueltig". Die
            // beiden ueberstimmbaren Gruende sagen etwas ueber die Reichweite
            // der Rechnung - nichts darueber, ob das Insulinmodell endliche,
            // lineare Werte liefert.
            if (kernel() == null)
                return abort("$warum | noFallback=${kernelReject ?: "KERNEL_UNAVAILABLE"}", signal, cfg, step, evidenz = evidenz)
            return markerFallbackCycle(
                rejected, warum, signal, step, cfg, state, profile, pumpe, tempBasalFallback,
                computeTs, markerTs, mealMarkerActive, measuredLow, descentRisk, descentLatch,
                descentCarryEligibility, manualBolusAfterMarkerU, treatmentView, evidenceEpisodeId,
                episodeGate.denial?.name, episodeGate.creditRevoked, evidenz,
                isf, target, targetSource, iobTotal,
                maxIobU, transportModelledU, ledgerView, episodes, onset, band,
                built.discount, built.input.trajectory.model, sensorEpoch, calibrationEpoch, gate,
            )
        }
        // AB HIER ist die Bahn vorhanden. Einmal ausgepackt statt an dreissig
        // Stellen `!!` - der Zweig oben endet in einem return, aber das traegt
        // der Kompiler nicht durch eine so lange Funktion.
        val prediction = predictionOrNull
            ?: return abort("internal: prediction lost", signal, cfg, step, evidenz = evidenz)

        // ---- DOSIERNEUTRALER TAU-/WENDE-SHADOW (Toni 20.08.) ------------
        //
        // Die produktive Bahn bleibt BITGENAU unangetastet. Diese parallelen
        // Resultate werden spaeter nur in [TurnResponseShadow.Report]
        // geschrieben. Kein Guard, keine Kappe und kein Gate liest sie.
        //
        // R60/R55/R50/R45 variieren ausschliesslich den positiven Anteil der
        // schnellen Bremsbahn. Negative Anteile behalten immer R60 - ihr
        // schnellerer Zerfall wuerde die Sicherheitsbahn ANHEBEN.
        data class ShadowPath(
            val name: String,
            val requestedTauMin: Int,
            val effectiveTauMin: Int,
            val adaptive: Boolean,
            val main: PredictorResult,
            val restraint: PredictorResult?,
        )
        val shadowFast = fastDrive(signal)
        // DIE PRODUKTIVE BEZUGSGROESSE WIRD ABGELESEN, NICHT NACHGEBAUT
        // (Review 22.08.). Die Produktion kuerzt im Rebound-Fenster den
        // positiven Tau auf min(driveTauMin, 15) und faehrt sonst
        // cfg.driveTauMin - beides steckt bereits in `built.input`. Die
        // fruehere zweite Kopie (hart 45-60) war im Rebound-Fenster KEINE
        // Baseline mehr: R60 ueberzeichnete die Kandidaten genau im
        // hypo-nahen Fenster, 25% der Wendezyklen des ersten Messlaufs.
        // Abgelesen kann die Regel nicht abdriften; die Varianten duerfen
        // den produktiven Tau nur KUERZEN, nie verlaengern.
        val produktivTauPos = (built.input.decay as? DriveDecayModel.ExponentialDecay)
            ?.tauMin?.roundToInt()
        val produktivTauNeg = (built.input.decayNegativeDrive as? DriveDecayModel.ExponentialDecay)
            ?.tauMin?.roundToInt() ?: produktivTauPos
        fun shadowRestraint(requestedTauMin: Int): Pair<Int, PredictorResult?> {
            val fast = shadowFast ?: return TurnResponseShadow.MAIN_TAU_MIN to null
            // Kein exponentieller produktiver Zerfall -> keine vergleichbare
            // Bremsbahn. Lieber eine benannte Luecke als eine falsche Zeile.
            val basePos = produktivTauPos ?: return TurnResponseShadow.MAIN_TAU_MIN to null
            val baseNeg = produktivTauNeg ?: basePos
            val effectiveTau = if (fast < 0.0) baseNeg else minOf(requestedTauMin, basePos)
            val drive = DriveEstimate(
                fast,
                fast - built.discount.termMgdlPerMin,
                null,
                DriveDiscount.methodId("UKF_RATE_RESTRAINT_SHADOW_R$requestedTauMin", built.discount.lambda),
            )
            val input = built.input.copy(
                drive = drive,
                decay = DriveDecayModel.ExponentialDecay(effectiveTau.toDouble()),
                // Auch wenn der Mittelantrieb positiv ist, kann die
                // abgeschlagene Unterkante negativ sein. Sie behaelt den
                // produktiven Negativ-Tau - hart 60 waere im Rebound erneut
                // eine fremde Baseline.
                decayNegativeDrive = DriveDecayModel.ExponentialDecay(baseNeg.toDouble()),
            )
            return effectiveTau to ((TrajectoryCore.predict(input) as? PredictorOutcome.Ok)?.result)
        }
        // Rechenzeit der MATRIX in zwei TEILSPANNEN akkumuliert: zwischen den
        // Shadow-Bloecken laeuft der produktive Regelpfad, und der gehoert
        // nicht in diese Zahl (Review 22.08. - die alte Einspann-Messung
        // enthielt ihn und machte die Kostenzusage der Matrix unpruefbar).
        var turnShadowNs = 0L
        val turnShadowBlock1Ns = System.nanoTime()
        val shadowPaths = mutableListOf<ShadowPath>()
        // Die Matrix ist nur an einem BESTAETIGTEN Wendepunkt relevant. Sie
        // in 1440 normalen Tageszyklen zu rechnen waere dosierneutral in der
        // Menge, aber nicht in der Zeit: die RT-Publikation wartet auf run().
        if (forecastShadowEnabled && (
                turnClassification.phase == TurnResponseShadow.Phase.TURNING_UP ||
                turnClassification.phase == TurnResponseShadow.Phase.TURNING_DOWN
            )
        ) {
            TurnResponseShadow.STATIC_RESTRAINT_TAUS_MIN.forEach { tau ->
                val (effective, path) = shadowRestraint(tau)
                shadowPaths += ShadowPath("R$tau", tau, effective, false, prediction, path)
            }
            val adaptiveMain = turnClassification.upwardMeanDriveMgdlPerMin?.let { up ->
                val raised = built.input.drive.copy(
                    meanMgdlPerMin = maxOf(built.input.drive.meanMgdlPerMin, up),
                    uncertaintyMethodId = built.input.drive.uncertaintyMethodId + "+TURN_UP_SHADOW",
                )
                (TrajectoryCore.predict(built.input.copy(drive = raised)) as? PredictorOutcome.Ok)?.result
            } ?: prediction
            val adaptiveRequestedTau = turnClassification.adaptiveRestraintTauMin
            val (adaptiveEffectiveTau, adaptiveRestraint) = shadowRestraint(adaptiveRequestedTau)
            shadowPaths += ShadowPath(
                "ADAPTIVE", adaptiveRequestedTau, adaptiveEffectiveTau, true,
                adaptiveMain, adaptiveRestraint,
            )
        }
        turnShadowNs += System.nanoTime() - turnShadowBlock1Ns

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

        // ---- DIE BEDINGTE BAHN (11.08.) --------------------------------
        //
        // Der Schwanz rechnet sein Budget aus der PRIOR-FREIEN Bahn, also aus
        // einem Verlauf ohne Kohlenhydrate. Er verbietet damit genau das
        // Insulin, das die angekuendigte Mahlzeit rechtfertigt - ein
        // Zirkelschluss, gemessen am 10.08. als 25 Minuten Sperre bei
        // steigendem BG.
        //
        // Die bedingte Bahn ist DIESELBE Rechnung mit EINER Aenderung: der
        // bereits vorhandene erklaerte Antrieb wirkt auch auf die
        // Sicherheitskante. Nichts sonst - Mittel- und Anzeigebahn bleiben
        // Punkt fuer Punkt unberuehrt (goldene Vektoren).
        //
        // DREI SCHRANKEN, alle schon da:
        //  1. der Kredit selbst schrumpft mit jeder Lieferung und ist bei
        //     voller Huelle null,
        //  2. er endet mit den Sonderrechten und frueher bei einer Wende,
        //  3. `priorFree <= lower` deckelt die Hebung auf die ANZEIGEBAHN -
        //     hoeher kommt die Sicherheitskante nie.
        //
        // Die UNBEDINGTE Bahn laeuft unveraendert weiter und bleibt die
        // Widerlegung: bleibt der Anstieg aus, faellt der Kredit, und der
        // Schwanz rechnet wieder gegen sie.
        // Die Hebung selbst liegt in `ConditionalDrive` - beide Bahnen
        // UNABHAENGIG, und dort auch begruendet. Zwei Anlaeufe sind hier an
        // if-Zweigen gescheitert: erst wurde nur die Hauptbahn gehoben (die
        // Bremse war die bindende, die Hebung verpuffte), dann haing die Bremse
        // an der Hauptbahn (stand die am Deckel, blieb die Bremse ungehoben).
        // ---- STUFE 4: DER EVIDENZKREDIT SPEIST DIE BAHNANNAHME ------------
        //
        // MAXIMUM, NICHT SUMME. Beide Groessen sind Aussagen ueber DIESELBE
        // Stoerung: die erklaerte Absorption ist die angekuendigte, der
        // Evidenzbestand die gemessene. Sie zu addieren hiesse, dieselbe
        // Mahlzeit zweimal zu unterstellen.
        //
        // ER GEHT NUR HIER EIN, nicht in `buildPredictorInput`. Dort wuerde er
        // den Antrieb der UNBEDINGTEN Bahn heben - und die ist die
        // Widerlegung: bleibt der Anstieg aus, faellt der Kredit, und der
        // Schwanz rechnet wieder gegen sie. Eine gehobene Widerlegung
        // widerlegt nichts.
        //
        // Was er NICHT tut: eine Dosis erzeugen. Er verschiebt die
        // Sicherheitskante; maxSMB, iobTH, maxIOB, Transportabzug,
        // Ledger-Hold, Modell-Health und Pumpengate greifen unveraendert
        // danach.
        val evidenzKredit = evidenz?.creditMgdlPerMin?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val bedingterKredit = maxOf(declaredDrive, evidenzKredit)

        val lift = if (!cfg.conditionalTailEnabled) ConditionalDrive.Lift(null, null)
        else ConditionalDrive.of(
            mainDrive = built.input.drive,
            restraintMean = if (restraint == null) null else fastDrive(signal),
            restraintLower = if (restraint == null) null else
                fastDrive(signal)?.minus(built.discount.termMgdlPerMin),
            restraintMethodId = DriveDiscount.methodId("UKF_RATE_RESTRAINT_V1", built.discount.lambda),
            declaredDriveMgdlPerMin = bedingterKredit,
        )
        fun bahn(d: DriveEstimate?): PredictorResult? = d?.let {
            (TrajectoryCore.predict(built.input.copy(drive = it)) as? PredictorOutcome.Ok)?.result
        }
        val conditional = bahn(lift.main)
        val conditionalRestraint = bahn(lift.restraint)

        val tailLowerMainUncond = prediction.bgAtHorizonSafetyLower
        val tailLowerMainCond = conditional?.bgAtHorizonSafetyLower
        val tailLowerRestraintUncond = restraint?.bgAtHorizonSafetyLower
        val tailLowerRestraintCond = conditionalRestraint?.bgAtHorizonSafetyLower
        val tailLowerUnconditional = minSafetyHorizonLowerOf(prediction, restraint)
        // Die kombinierte bedingte Kante gibt es, sobald EINE der beiden
        // Bahnen gehoben wurde - nicht erst, wenn beide es sind. Jede Bahn,
        // die nicht gehoben werden konnte, geht unveraendert ein.
        val tailLowerConditional =
            if (conditional == null && conditionalRestraint == null) null
            else minSafetyHorizonLowerOf(
                conditional ?: prediction,
                conditionalRestraint ?: restraint,
            )

        val tailBase = TailLiability.Input(
            lowerBgAtH = tailLowerConditional ?: tailLowerUnconditional,
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

        // DAS LOW-TOR, VOR der Entscheidung ausgewertet - damit die volle
        // Rechenspur auch dann im Trail steht, wenn das Tor ZU bleibt.
        val lowThreatResult = LowThreatGate.evaluate(
            measuredLow = measuredLow,
            // Ohne brauchbare Reihe gibt es keinen positiven Nachweis - und
            // ohne Nachweis keine Null. Das gemessene Tief laeuft ueber
            // `measuredLow` daran vorbei.
            signalHealthy = step.health == Health.READY,
            bgMgdl = signal.q1,
            // GEMESSEN, nicht gerechnet: der UKF-Zustand. Nicht `r` -
            // BGI-bereinigt, 18-min-Fenster, haengt an jedem Wendepunkt rund
            // sechs Minuten nach.
            fallRatePerMin = signal.ukfRatePerMin,
            // NUR der Bolusanteil. Das Netto-IOB traegt einen negativen
            // Basalanteil aus vorheriger Zurueckhaltung, und der wuerde die
            // Ueberdeckung genau dann verdecken, wenn ohnehin schon zu wenig
            // Basal lief - die Rueckkopplung, die dieses Tor beenden soll.
            bolusIobU = (iobTotal.iob - iobTotal.basaliob).takeIf { iobTotal.valid },
            isfMgdlPerU = isf,
            guardFloorMgdl = cfg.guardFloorMgdl,
            scheduledBasalUPerH = profile.getBasal(computeTs),
            // Der Wirkungsanteil kommt aus DEM Einheitskern dieses Zyklus,
            // nicht aus einer nachgebauten Formel - sonst driften die beiden
            // auseinander, sobald jemand das Insulinmodell wechselt. Ohne Kern
            // gibt es keine Nutzenrechnung und damit kein Tor.
            remainingEffect = kernel()?.let { k ->
                { min: Double -> 1.0 - k.iobAt(k.deliveryTs + (min * 60_000).toLong(), 1.0) }
            } ?: { 0.0 },
            minBenefitMgdl = cfg.lowGateMinBenefitMgdl,
            horizonMin = cfg.lowGateHorizonMin,
        )

        // ---- v29: ZWEI-ZYKLEN-ZUENDUNG DES FALL-VERDIKTS ------------------
        //
        // VOR decide, damit auch die ERSTE Zero-TBR wartet (Tonis Korrektur
        // 24.08. nacht: die erste Fassung verzoegerte nur den LATCH - der
        // einzelne Grenzzyklus setzte weiterhin sofort ZERO_TEMP, und der
        // Sensorzacken-Schutz war damit nur halb). Vertrag:
        //   1. Fallzyklus:  KEEP_CURRENT, Latch aus, Zaehler 1/2
        //   2. Fallzyklus:  ZERO_TEMP und Latch, 2/2
        //   MEASURED_LOW:   sofort beides
        // NUR bei eingeschaltetem Latch-Feature - ohne den Schalter bleibt
        // das bisherige Verhalten bitgleich (Zero sofort, Nutzenprobe
        // verwirft sie wie gehabt). Ein bereits AKTIVER Latch reicht das
        // Verdikt ungefiltert durch: Halten und "Null sofort beenden"
        // brauchen den echten Grund.
        val zeroFallVerdikt =
            lowThreatResult.verdict == LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE
        val zeroLowVerdikt = lowThreatResult.verdict == LowThreatGate.Verdict.MEASURED_LOW
        if (cfg.zeroLatchEnabled) {
            val anschluss = zeroArmLastTs > 0L && signal.sourceTs > zeroArmLastTs &&
                signal.sourceTs - zeroArmLastTs <= 90_000L
            zeroArmStreak = if (zeroFallVerdikt) (if (anschluss) zeroArmStreak + 1 else 1) else 0
            zeroArmLastTs = signal.sourceTs
        } else {
            zeroArmStreak = 0
            zeroArmLastTs = 0L
        }
        val zeroVerdiktScharf = !cfg.zeroLatchEnabled || zeroLowVerdikt ||
            episodes.zeroLatch.active || (zeroFallVerdikt && zeroArmStreak >= 2)
        // Das WIRKSAME Verdikt der Dosierung; das ROHE Result bleibt
        // unveraendert im Trail (lowThreat-Block + armStreak machen die
        // Daempfung offline ablesbar: Verdikt FALLING + Streak 1 + KEEP).
        val lowThreatWirksam =
            if (zeroVerdiktScharf) lowThreatResult.verdict else LowThreatGate.Verdict.NONE

        // DIE FRIST AM MARKERWECHSEL FESTSCHREIBEN (Codex 19.08.).
        //
        // NICHT an `neueEpisode` gehaengt: ein ZWEITER Druck innerhalb der
        // laufenden 90-Minuten-Prime-Episode haette den Ablauf sonst nicht
        // erneuert, obwohl der Vertrag "seit dem LETZTEN Marker" sagt.
        // Ausgeloest wird das Pinnen ausschliesslich vom MARKERWECHSEL -
        // eine blosse Einstellungsaenderung aendert den Marker nicht und
        // darf ein abgelaufenes Privileg nicht wieder oeffnen.
        //
        // FAIL-CLOSED: nur ein Marker, den DIESER Prozess beobachtet hat,
        // pinnt. Ein beim Warmstart vorgefundener erzeugt kein
        // rueckwirkendes Sonderrecht.
        if (markerTs > 0L && markerTs <= computeTs &&
            markerTs != episodes.markerReboundOverridePinnedFor &&
            markerPressObserved() == markerTs
        ) {
            episodes.markerReboundOverridePinnedFor = markerTs
            episodes.markerReboundOverrideDeadlineTs =
                if (cfg.evidenceReboundOverrideMaxMin > 0)
                    markerTs + cfg.evidenceReboundOverrideMaxMin * 60_000L
                else 0L
        }

        // ---- DAS REBOUND-SONDERRECHT DER EVIDENZ -------------------------
        //
        // NUR DAS REBOUND-BAND, NICHT DIE NACHT. Beide liefen bis zum
        // 19.08. ueber dasselbe Signal; die Frist haette sonst ungewollt
        // auch das Nacht-Totband getroffen.
        //
        // FAIL-CLOSED: fehlender oder zukuenftiger Marker ergibt keine
        // Frist und damit kein Privileg - das Band bleibt scharf.
        // DIE FRIST GILT NUR FUER DEN DRUCK, ZU DEM SIE GEPINNT WURDE
        // (Codex 19.08.). Ohne den Identitaetsvergleich koennte Marker B
        // nach einem Warmstart oder einer externen Aenderung die noch
        // laufende Frist von Marker A erben, obwohl B in diesem Prozess
        // nie beobachtet wurde. Dieselbe Falle wie bei der Ledger-Bindung.
        val reboundOverrideDeadlineTs =
            if (markerTs > 0L && markerTs <= computeTs &&
                markerTs == episodes.markerReboundOverridePinnedFor
            ) episodes.markerReboundOverrideDeadlineTs else 0L

        // ---- PUNKT 6: AUFSCHUB PINNEN / AUFRAEUMEN (Toni 22.08.) ---------
        //
        // DIESELBE Identitaetsdisziplin wie beim Rebound-Sonderrecht: gepinnt
        // wird nur ein in DIESEM Prozess beobachteter Druck. Horizont und
        // Frist frieren beim Pinnen ein (Vertrag 2). Ein neuer Marker laesst
        // einen offenen Rest des alten SICHTBAR verfallen; ein Widerruf und
        // das Ausschalten ebenso - nichts verschwindet stumm (Vertraege 8+10).
        if (!cfg.deferredPrimeEnabled) {
            if (episodes.deferredPrime.pinnedForMarkerTs > 0L)
                episodes.deferredPrime = DeferredPrime.lapse(
                    episodes.deferredPrime, DeferredPrime.LapseReason.DISABLED, computeTs,
                )
        } else if (markerTs > 0L && markerTs <= computeTs && markerPressObserved() == markerTs) {
            if (episodes.deferredPrime.pinnedForMarkerTs != markerTs) {
                episodes.deferredPrime = DeferredPrime.pin(
                    episodes.deferredPrime, markerTs,
                    horizonMin = cfg.markerPrimeDescentHorizonMin.toInt(),
                    endMin = cfg.deferredPrimeEndMin,
                )
                episodes.postFoundationDeliveredU = 0.0
            }
        } else if (episodes.deferredPrime.pinnedForMarkerTs > 0L && markerTs <= 0L) {
            // RUECKNAHME (Vertrag 8): der Marker selbst ist weg. Bewusst NICHT
            // an `creditRevoked` gekoppelt - das Flag entsteht auch als
            // Neustart-Artefakt und haette Budget und Frist entgegen
            // Replay-Fall 6 geraeumt.
            episodes.deferredPrime = DeferredPrime.lapse(
                episodes.deferredPrime, DeferredPrime.LapseReason.REVOKED, computeTs,
            )
        }
        val reboundOverrideErlaubt = NightWindow.evidenceMayOverrideRebound(
            evidenceCreditActive = evidenzKredit > 0.0,
            deadlineTs = reboundOverrideDeadlineTs,
            computeTs = computeTs,
        )
        val reboundOverrideDenial = NightWindow.reboundOverrideDenial(
            evidenceCreditActive = evidenzKredit > 0.0,
            deadlineTs = reboundOverrideDeadlineTs,
            computeTs = computeTs,
            markerTs = markerTs,
            pinnedForTs = episodes.markerReboundOverridePinnedFor,
            revoked = episodeGate.creditRevoked,
        )
        val baseDecision = FuseController.decide(
            state, prediction,
            FuseController.Limits(guardFloorMgdl = cfg.guardFloorMgdl, releaseHorizonMin = cfg.releaseHorizonMin),
            tail,
            restraint,
            // Fliessender Kredit entwaffnet die Totbaender - eine
            // markereroeffnete Episode mit versiegelter unbezahlter Stoerung
            // ist keine unangekuendigte Abweichung. Abschluss-Audit 15.08.:
            // diese Zeile FEHLTE, der Default false verdeckte das - 81
            // Zyklen im 2-Tage-Trail blieben trotz Kredit im Totband.
            evidenceCreditActive = evidenzKredit > 0.0,
            evidenceMayOverrideRebound = reboundOverrideErlaubt,
            // DAS LOW-TOR (Toni 17.08.): der einzige Weg zu einer Zero-TBR.
            // Ohne positiven Low-Nachweis sperrt der Guard die MENGE, nicht
            // die Basisversorgung. Die volle Rechenspur steht in
            // `lowThreatResult` und geht in den Trail - eine Null, die NICHT
            // kam, waere sonst von einem Zyklus ohne Befund nicht zu
            // unterscheiden (Tonis Auflage vor dem Produktiv-Flash).
            lowThreat = lowThreatWirksam,
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
                CandidateGate.apply(baseDecision, candidateResult, bolusStep)
            }
        }

        // DIE KOMPLETTE SHADOW-MATRIX, aber nur bis zur Kandidatensuche.
        // Prime, Fundament, finalVerify und Pumpengates bleiben absichtlich
        // ausserhalb: `candidateSmbU` heisst daher nicht "abgegeben", sondern
        // "unter dieser Bahn vor Autorisierungs-Lifts noch zulaessig".
        // Genau diese Stufe erzeugte im Fall #1 um 11:33 die 0,30 U.
        val turnShadowBlock2Ns = System.nanoTime()
        val shadowKernel = kernel()
        val shadowCaps = CandidateSearch.Caps(
            remainingReleaseBudgetU = cfg.maxSmbU,
            effectiveIobThHeadroomU = state.iobThU - state.capIobU - transportModelledU,
            effectiveMaxIobHeadroomU = state.maxIobU - state.capIobU - transportModelledU,
            pumpIncrementU = bolusStep,
            maxSmbU = cfg.maxSmbU,
        )
        fun conditionalRestraintFor(path: ShadowPath): PredictorResult? = lift.restraint?.let { d ->
            val input = built.input.copy(
                drive = d,
                decay = DriveDecayModel.ExponentialDecay(path.effectiveTauMin.toDouble()),
                // Produktiver Negativ-Tau, dieselbe Regel wie in
                // shadowRestraint - hart 60 waere im Rebound eine fremde
                // Baseline (Review 22.08.).
                decayNegativeDrive = DriveDecayModel.ExponentialDecay(
                    (produktivTauNeg ?: TurnResponseShadow.MAIN_TAU_MIN).toDouble()
                ),
            )
            (TrajectoryCore.predict(input) as? PredictorOutcome.Ok)?.result
        }
        val turnVariants = shadowPaths.map { path ->
            val conditionalShadowRestraint = conditionalRestraintFor(path)
            val shadowTailLower = minSafetyHorizonLowerOf(
                conditional ?: path.main,
                conditionalShadowRestraint ?: path.restraint,
            )
            val shadowTail = if (!cfg.tailGuardEnabled) null else
                TailLiability.evaluate(tailBase.copy(lowerBgAtH = shadowTailLower))
            val shadowBase = FuseController.decide(
                state,
                path.main,
                FuseController.Limits(
                    guardFloorMgdl = cfg.guardFloorMgdl,
                    releaseHorizonMin = cfg.releaseHorizonMin,
                ),
                shadowTail,
                path.restraint,
                evidenceCreditActive = evidenzKredit > 0.0,
                evidenceMayOverrideRebound = reboundOverrideErlaubt,
                lowThreat = lowThreatWirksam,
                onsetCapU = if (onset.active) onset.remainingU else null,
            )
            val shadowCandidate = if (shadowBase.smbU <= 0.0 || shadowKernel == null) null else
                CandidateSearch.search(
                    prediction = path.main,
                    kernel = shadowKernel,
                    isfSlots = built.input.isfSlots,
                    band = candidateBand,
                    caps = shadowCaps,
                    ledgerHold = ledgerView.hold,
                    restraint = path.restraint,
                )
            val shadowDecision = CandidateGate.apply(shadowBase, shadowCandidate, bolusStep)
            val mainAtRelease = path.main.points
                .firstOrNull { it.offsetMin == cfg.releaseHorizonMin }?.meanBg
            val restraintAtRelease = path.restraint?.points
                ?.firstOrNull { it.offsetMin == cfg.releaseHorizonMin }?.meanBg
            val mainLowerAtRelease = path.main.points
                .firstOrNull { it.offsetMin == cfg.releaseHorizonMin }?.safetyLowerBg
            val restraintLowerAtRelease = path.restraint?.points
                ?.firstOrNull { it.offsetMin == cfg.releaseHorizonMin }?.safetyLowerBg
            TurnResponseShadow.Variant(
                name = path.name,
                requestedRestraintTauMin = path.requestedTauMin,
                restraintTauMin = path.effectiveTauMin,
                adaptive = path.adaptive,
                predAtReleaseMgdl = listOfNotNull(mainAtRelease, restraintAtRelease).minOrNull(),
                safetyLowerAtReleaseMgdl = listOfNotNull(mainLowerAtRelease, restraintLowerAtRelease).minOrNull(),
                minSafetyLowerMgdl = minSafetyLowerOf(path.main, path.restraint),
                tailHeadroomU = shadowTail?.takeIf { it.usable }?.headroomU,
                insulinReqU = shadowBase.insulinReqU,
                ratioCapU = shadowBase.caps.firstOrNull { it.name == "smbRatio" }?.valueU,
                candidateSmbU = shadowDecision.smbU,
                candidateBinding = shadowDecision.bindingLimit,
                candidateReject = shadowCandidate?.reject?.name,
            )
        }
        // ---- ADAPTIVE-DOWN ALS SCHATTEN (Toni 22.08.) --------------------
        //
        // Einseitige BEDARFSSENKUNG: die Mittelbahn faellt auf min(r, fast).
        // Der PRODUKTIVE Pfad bleibt bitgenau unangetastet; INNERHALB der
        // Zeile ziehen untere und prior-freie Kante mit der Mittelbahn mit
        // (Bandordnung), ihr Guard rechnet also auf der gesenkten Bahn und
        // ist STRENGER als produktiv - Details und Messkonsequenz an der
        // Senkung unten und im KDoc von [TurnResponseShadow.DownVariant].
        //
        // `vetted` ist die stufengleiche Referenz (Kandidat VOR Prime,
        // Fundament und Endriegel). Die drei Ausloeser teilen sich EINE
        // gesenkte Bahn - sie unterscheiden sich nur im WANN, nicht im WIE;
        // mehr als ein zusaetzlicher Predict+Suche-Lauf entsteht pro Zyklus
        // also nicht, und auch der nur bei fast < slow.
        var shadowDownLaneDecision: FuseController.Decision? = null
        val downVariants: List<TurnResponseShadow.DownVariant> = run {
            if (!forecastShadowEnabled) return@run emptyList()
            val fast = shadowFast ?: return@run emptyList()
            if (produktivTauPos == null || fast >= band.mean) return@run emptyList()
            val streak = TurnResponseShadow.declineStreak(turnSamples)
            val refZeile = TurnResponseShadow.DownVariant(
                name = "BASE", triggered = false, declineStreak = streak,
                midDriveMgdlPerMin = built.input.drive.meanMgdlPerMin,
                predAtReleaseMgdl = prediction.points
                    .firstOrNull { it.offsetMin == cfg.releaseHorizonMin }?.meanBg,
                insulinReqU = vetted.insulinReqU,
                candidateSmbU = vetted.smbU,
                candidateBinding = vetted.bindingLimit,
                candidateReject = candidateResult?.reject?.name,
                avoidedSmbU = 0.0,
            )
            // Die Senkung erhaelt die BANDORDNUNG: faellt die Mittelbahn
            // unter die untere Kante (bei spreizungsfreiem Band ist
            // lower == mean, die Klemme an der Kante hatte die Senkung
            // komplett neutralisiert), ziehen lower und prior-freie Kante
            // mit. MESSKONSEQUENZ, ehrlich benannt: Guard/Tail der Zeile
            // rechnen damit auf der GESENKTEN Bahn und sind strenger als
            // produktiv - `avoidedSmbU` ist eine OBERGRENZE. Offline trennt
            // `candidateBinding` die Bedarfssenkung von einer Guard-Bindung,
            // und `insulinReqU` traegt die reine Bedarfsgroesse.
            val gesenkterMid = minOf(built.input.drive.meanMgdlPerMin, fast)
            val loweredMain = (TrajectoryCore.predict(
                built.input.copy(
                    drive = built.input.drive.copy(
                        meanMgdlPerMin = gesenkterMid,
                        lowerMgdlPerMin = minOf(built.input.drive.lowerMgdlPerMin, gesenkterMid),
                        lowerPriorFreeMgdlPerMin = built.input.drive.lowerPriorFreeMgdlPerMin
                            ?.let { minOf(it, gesenkterMid) },
                        uncertaintyMethodId = built.input.drive.uncertaintyMethodId + "+TURN_DOWN_SHADOW",
                    ),
                )
            ) as? PredictorOutcome.Ok)?.result
            val gesenkteZeile: TurnResponseShadow.DownVariant? = loweredMain?.let { lm ->
                val downBase = FuseController.decide(
                    state, lm,
                    FuseController.Limits(
                        guardFloorMgdl = cfg.guardFloorMgdl,
                        releaseHorizonMin = cfg.releaseHorizonMin,
                    ),
                    tail,
                    restraint,
                    evidenceCreditActive = evidenzKredit > 0.0,
                    evidenceMayOverrideRebound = reboundOverrideErlaubt,
                    lowThreat = lowThreatWirksam,
                    onsetCapU = if (onset.active) onset.remainingU else null,
                )
                val downCandidate = if (downBase.smbU <= 0.0 || shadowKernel == null) null else
                    CandidateSearch.search(
                        prediction = lm,
                        kernel = shadowKernel,
                        isfSlots = built.input.isfSlots,
                        band = candidateBand,
                        caps = shadowCaps,
                        ledgerHold = ledgerView.hold,
                        restraint = restraint,
                    )
                val downDecision = CandidateGate.apply(downBase, downCandidate, bolusStep)
                shadowDownLaneDecision = downDecision
                TurnResponseShadow.DownVariant(
                    name = "", triggered = true, declineStreak = streak,
                    midDriveMgdlPerMin = gesenkterMid,
                    predAtReleaseMgdl = lm.points
                        .firstOrNull { it.offsetMin == cfg.releaseHorizonMin }?.meanBg,
                    insulinReqU = downBase.insulinReqU,
                    candidateSmbU = downDecision.smbU,
                    candidateBinding = downDecision.bindingLimit,
                    candidateReject = downCandidate?.reject?.name,
                    avoidedSmbU = maxOf(0.0, vetted.smbU - downDecision.smbU),
                )
            }
            fun zeile(name: String, ausgeloest: Boolean) = when {
                !ausgeloest -> refZeile.copy(name = name)
                gesenkteZeile != null -> gesenkteZeile.copy(name = name)
                // Ausgeloest, aber Bahn nicht berechenbar: als benannte
                // Luecke exportieren, NICHT als stille Referenzzeile - sonst
                // saehe der Ausloeser offline aus, als haette er nie gezogen.
                else -> TurnResponseShadow.DownVariant(
                    name, true, streak, gesenkterMid,
                    null, null, null, null, "PREDICT_FAILED", null,
                )
            }
            listOf(refZeile, zeile("NOW", true), zeile("P2", streak >= 2), zeile("P3", streak >= 3))
        }
        turnShadowNs += System.nanoTime() - turnShadowBlock2Ns

        // Sofort-Freigabe: Plan aus derselben Momentaufnahme, Anhebung NUR
        // wenn der Basisentscheidung nichts als Bedarf fehlte. Sperren und
        // Deckel gewinnen in PrimeRelease.lift unveraendert.

        // DER HERKUNFTS-STEMPEL haengt dagegen NICHT am Basisblock, und genau
        // daran ist die Einstellung am 11.08. gescheitert. Gemessen am Geraet:
        // `block=NONE bind=primeRelease prime=true floor=0.10 smb=0.0` - der
        // Regler hatte gar nicht blockiert, der Prime-Kanal hatte 0,10 U
        // freigegeben, und die Menge starb erst am Schutz-Nullstrom im
        // Translator, weil ihr niemand ansah, dass sie autorisiert war.
        //
        // Fuer LIFTABLE macht die Weglassung keinen Unterschied: die
        // erweiterte Menge greift ohnehin nur, WENN der Block einer der
        // beiden ist. Sie stempelt nur zusaetzlich die Herkunft.

        val primePlan = PrimeRelease.plan(
            PrimeRelease.Input(
                enabled = cfg.primeReleaseEnabled,
                windowMin = cfg.primeWindowMin,
                declinedByUser = markerNoPrime,
                mealMarkerActive = mealMarkerActive,
                armedTsMs = markerTs,
                windowStartTsMs = episodes.primeWindowStartTs,
                nowMs = computeTs,
                // Bei aktivem Fundament sieht Prime NUR sein Phase-A-Budget -
                // sonst gaebe es die ganze Huelle aus und Phase B faende nichts
                // mehr vor. Ohne Fundament ist es unveraendert der Live-Wert.
                envelopeU = MealFoundation.primeBudgetU(episodes.foundation, cfg.primeEnvelopeU),
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
                markerAuthorized = manualMarkerAuthorized,
            )
        )

        // ---- LIEFERBARE MINUTEN: der Fensterstart schiebt nach ------------
        //
        // Solange nur die Clearance sperrt, schiebt der Fensterstart nach -
        // eine Freigabe, die nie erteilbar war, darf nicht verfallen. Absolut
        // gekappt in PrimeRelease selbst. Nur bei CLEARANCE:
        // DISABLED/NO_MARKER/ENVELOPE_SPENT/NOT_FINITE sind keine "gesperrt,
        // aber gewollt"-Zustaende, dort waere das Schieben eine stille
        // Verlaengerung ohne Grund.
        //
        // DIESE ZEILE STAND WEITER UNTEN, hinter der fertigen Entscheidung
        // (Toni 19.08.). Sie muss VOR den Entscheidungssnapshot des
        // Fundaments, denn der Uebergabeanker haengt an `primeWindowStartTs`:
        // ein Snapshot davor saehe einen anderen Anker als die spaetere
        // Buchung, und Phase B rechnete gegen ein Fenster, das gar nicht mehr
        // gilt.
        //
        // VERHALTENSNEUTRAL FUER PRIME, geprueft: zwischen dem `plan`-Aufruf
        // oben (der `primeWindowStartTs` liest) und dieser Stelle liest sie
        // niemand sonst.
        if (primePlan.reason == "CLEARANCE") episodes.primeWindowStartTs = computeTs

        // ---- DER ENTSCHEIDUNGSSNAPSHOT DES FUNDAMENTS --------------------
        //
        // Er entsteht NACH der moeglichen Clearance-Verschiebung und VOR jeder
        // Buchung dieses Zyklus - genau dazwischen ist der einzige Punkt, an
        // dem Uebergabeanker und Bezahlstand beide gelten.
        //
        // Er ist NICHT derselbe wie der Export-Snapshot am Ende des Zyklus:
        // dieser hier traegt den Stand VOR der eigenen Abgabe und beantwortet
        // "was fordert das Fundament jetzt", jener den Stand DANACH und
        // beantwortet "wo steht es am Ende".
        val foundationDecision = MealFoundation.snapshot(
            episodes.foundation, computeTs, episodes.primeWindowStartTs,
            deliveredFromBudgetU = episodes.deliveredPhaseAU + episodes.deliveredSinceHandoverU,
            deliveredPhaseAU = episodes.deliveredPhaseAU,
            deliveredSinceHandoverU = episodes.deliveredSinceHandoverU,
            confirmedNotSentPhaseAU = episodes.confirmedNotSentPhaseAU,
            descentDeferredPhaseAU = episodes.descentDeferredPhaseAU,
            descentCarryEligibility = descentCarryEligibility,
            bolusStepU =bolusStep,
        )

        // ---- PHASE-A-SOFORTANTEIL (iLet-Prinzip, Bauauftrag Toni 24.08.) --
        //
        // VOR dem Prime-Lift, auf derselben Basis: die Sofortdosis ist ein
        // BODEN auf der kumulierten Phase-A-Lieferung
        // (MealFoundation.upfrontFloorU), kein Einmal-Zustand - Lieferung,
        // unklarer Pumpenausgang, Nicht-Sende-Beweis und Sicherheitsaufschub
        // verrechnen sich ueber die persistierten, gate- und
        // beweiskorrigierten Zaehler (exactly-once als Bilanz). Der
        // Prime-Lift arbeitet auf dem Ergebnis und hebt per max-Semantik nur,
        // wenn sein eigener Boden hoeher laege - nie Addition.
        //
        // Ein AKTIVER ZERO-LATCH sperrt die Sofortdosis ausdruecklich
        // (Bauauftrag): er ist der einzige harte Riegel, den die spaeteren
        // Stufen nicht ohnehin tragen (der Latch laesst den SMB-Pfad ueber
        // latchZeroOnly frei). Gelesen wird der persistierte Stand VOR dem
        // Latch-Advance dieses Zyklus. Low (SAFETY_HOLD), Descent-Risk,
        // Descent-Latch, Health, Ledger-Hold und Pumpengates wirken
        // unveraendert ueber Blockpolitik, MeasuredDescentGate,
        // LedgerHoldGate und Publikationsgate; der gemessene Abwaertsriegel
        // des gepinnten Horizonts leitet die Menge unten in den
        // DeferredPrime-Aufschub um (sie steht oberhalb der reinen Basis).
        val upfrontDeferredOpenU =
            if (episodes.deferredPrime.pinnedForMarkerTs == episodes.foundation.armedTs)
                episodes.deferredPrime.openU else 0.0
        // ---- TONIS FREIGABETOR DER SOFORTDOSIS (Mindeststand vor GO) ------
        //
        // Die aussergewoehnlich grosse Dosis braucht ein STRENGERES Tor als
        // der normale Markerpfad - der Marker-Horizont selbst bleibt bei
        // 60 min, das bereits berechnete 120-min-LowThreat-Verdikt ist die
        // ZUSAETZLICHE Zulaessigkeitspruefung. Typisierte GEMESSENE
        // Risikogruende (Verdikt roh - auch der gedaempfte Grenzzyklus zaehlt
        // fuer die grosse Dosis -, aktiver oder gerade zuendender Zero-Latch,
        // Rebound) VERSCHIEBEN die komplette offene Sofortmenge in den
        // DeferredPrime-Aufschub: aufgeschoben, nie verworfen; Freigabe nach
        // bestaetigter Erholung in Pumpenschritten (eine schnellere
        // Upfront-Freigabe waere eine eigene Produktentscheidung). Eine bloss
        // noch LAUFENDE alte Zero-TBR blockiert nichts - gelesen werden
        // typisierte Gruende, nie die Pumpenrate. SICHERHEITSAUFLAGEN
        // ausserdem: nur mit aktivem DeferredPrime-Netz (sonst fail-closed,
        // BLOCKED_NO_DEFERRED) und nie bei Ledger-Hold (dort wird gewartet,
        // nicht gebucht).
        // P0 (Toni): TECHNISCHE INTEGRITAET wie im Liveness-Kanal. Ein Kern
        // kann existieren und trotzdem typisierte Modellfehler tragen
        // (ISF_SLOT_MISSING, DELIVERY_AFTER_RELEASE, NON_FINITE, ...) -
        // finalVeto saehe das, aber MarkerFloor stellte die grosse
        // autorisierte Dosis danach wieder her. Dieselbe geteilte Pruefung
        // VOR dem Lift; nur Guard-/Tail-URTEILE bleiben markerpolitisch
        // ueberstimmbar. Ein technischer Reject ist ein AUFSCHUB-Grund wie
        // die gemessenen Risiken: die Menge wandert in den Aufschub, damit
        // ein langer Modellfehler sie nicht ueber das Phasenende verliert.
        val upfrontKern = kernel()
        val upfrontTechReject = upfrontKern == null ||
            CandidateSearch.verifyTechnicalIntegrity(
                prediction, upfrontKern, built.input.isfSlots, candidateBand, bolusStep,
                restraint = restraint,
            ) != null
        // JEDER gemessene Sicherheitsriegel schiebt auf (Vertrag 5 des
        // Nachtrags: "Batch bleibt vollstaendig offen, keine Miniabgaben").
        // Das GEMESSENE ABWAERTSRISIKO und der Erholungsriegel gehoerten
        // frueher nicht dazu - der Lift forderte die volle Menge an, und
        // erst MeasuredDescentGate nullte sie danach. Ergebnis war zwar
        // keine Dosis, aber ein Zustand "REQUESTED" bei stehendem Riegel
        // und eine Anforderung, die jeder Zyklus wiederholte.
        val upfrontRisikoAufschub = lowThreatResult.verdict != LowThreatGate.Verdict.NONE ||
            (cfg.zeroLatchEnabled && episodes.zeroLatch.active) ||
            reboundRaw ||
            descentRisk.active ||
            descentLatch.blocksPositive ||
            upfrontTechReject
        // ---- DER SOFORT-BATCH (Nachtrag Toni 25.08. mittags) -------------
        //
        // WAS SICH GEAENDERT HAT: der zurueckgehaltene Sofortanteil wandert
        // NICHT mehr in den generischen `DeferredPrime`. Der gibt bauartbe-
        // dingt hoechstens EINEN Pumpenschritt je Zyklus frei - gemessen am
        // 25.08. kam der als 3,20 U geplante Sofortanteil danach als
        // 0,20/0,15/0,25 U heraus, und weil der Aufschub nur seine eigenen
        // 0,05er abzog, meldete er 3,10 U offen, obwohl nach 0,60 U
        // Phase-A-Lieferung hoechstens 2,60 U offen sein konnten.
        //
        // STATTDESSEN: der Rueckstand ergibt sich allein aus der Bilanz
        // (`remainingUpfrontU`), und ein persistenter MERKER haelt fest,
        // dass aufgeschoben wurde. Solange er steht, verlangt die Freigabe
        // die BESTAETIGTE Erholung - danach wird der GANZE zulaessige Rest
        // in einem Zug als MEAL_UPFRONT angefordert.
        val upfrontOhneNetz = !cfg.deferredPrimeEnabled
        // Der offene Batch VOR jeder Entscheidung dieses Zyklus - mit ALLEN
        // Abzuegen (Review-Punkt 3). `null` heisst UNBESTIMMBAR, nicht
        // gedeckt: die Behandlungssicht ist unlesbar.
        val upfrontOffenRoh = MealFoundation.remainingUpfrontU(
            auth = episodes.foundation,
            deliveredPhaseAU = episodes.deliveredPhaseAU,
            manualAfterMarkerU = manualBolusAfterMarkerU,
            deliveredSinceHandoverU = episodes.deliveredSinceHandoverU,
            postFoundationDeliveredU = episodes.postFoundationDeliveredU,
            transferredToDeferredU = episodes.upfrontTransferredU,
        )
        val upfrontSichtUnlesbar = upfrontOffenRoh == null
        val upfrontOffenU = upfrontOffenRoh ?: 0.0
        val upfrontInPhaseA = foundationDecision.phase == MealFoundation.Phase.PHASE_A

        // ---- ENDE VON PHASE A: UEBERFUEHRUNG STATT SPAETBATCH -----------
        //
        // KEIN spaeter Mehr-Einheiten-Batch (Review 25.08. abends, Punkt 2):
        // `liftUpfront` liefert ohnehin nur in Phase A, und ein spaeterer
        // Vollbatch waere eine eigene Produktentscheidung samt Frist. Was
        // beim Phasenwechsel noch offen ist, geht deshalb EINMAL in den
        // bestehenden schrittweisen Aufschub ueber - unter DESSEN gepinnter
        // Frist. Verlustfrei, aber gebremst.
        //
        // Frueher stand hier nichts: der Rest blieb nach T+20 sichtbar
        // offen und konnte nie mehr freigegeben werden (der Fallback-
        // Kommentar behauptete das Gegenteil).
        var upfrontTransferNowU = 0.0
        if (!upfrontInPhaseA && upfrontOffenU > 0.0 && !upfrontSichtUnlesbar &&
            episodes.foundation.valid &&
            episodes.deferredPrime.pinnedForMarkerTs == episodes.foundation.armedTs
        ) {
            val vorher = episodes.deferredPrime.openU
            episodes.deferredPrime = DeferredPrime.withhold(
                episodes.deferredPrime, upfrontOffenU,
                deferredHullRemainingU(episodes, manualBolusAfterMarkerU),
            )
            // Die REAL gebuchte Differenz - withhold kappt an der Huelle.
            upfrontTransferNowU = (episodes.deferredPrime.openU - vorher).coerceAtLeast(0.0)
            // Auch der NICHT gebuchte Teil ist erledigt: er passt nicht mehr
            // in die Huelle und darf nicht als Sofortmenge offen bleiben.
            episodes.upfrontTransferredU += upfrontOffenU
            episodes.upfrontBatchDeferredSince = 0L
        }
        // Aufgeschoben wird NUR, wenn es etwas aufzuschieben gibt.
        val upfrontAufschubJetzt = upfrontRisikoAufschub && upfrontOffenU > 0.0 && upfrontInPhaseA
        if (upfrontAufschubJetzt && episodes.upfrontBatchDeferredSince <= 0L)
            episodes.upfrontBatchDeferredSince = computeTs
        // Nach einem Aufschub oeffnet erst die BESTAETIGTE Erholung wieder -
        // dieselbe Schwelle, die auch der Aufschub des linearen Prime
        // verlangt (drei zusammenhaengende gesunde Erholungszyklen).
        val upfrontWartetAufErholung = episodes.upfrontBatchDeferredSince > 0L &&
            deferredRecoveryStreak < DescentRecoveryLatch.REQUIRED_CONSECUTIVE_CYCLES
        val liftedUpfront = when {
            upfrontOhneNetz || ledgerView.hold -> vetted
            // Sicherheitsriegel aktiv: der Batch bleibt VOLLSTAENDIG offen -
            // keine Miniabgaben aus diesem Bestand (Vertrag 5).
            upfrontRisikoAufschub -> vetted
            upfrontWartetAufErholung -> vetted
            else -> MealFoundation.liftUpfront(
                base = vetted,
                auth = episodes.foundation,
                phase = foundationDecision.phase,
                deliveredPhaseAU = episodes.deliveredPhaseAU,
                manualAfterMarkerU = manualBolusAfterMarkerU,
                state = state,
                tailHeadroomU = tail?.takeIf { it.usable }?.headroomU,
                transportCommitmentU = transportModelledU,
            )
        }
        // Der Rueckstand ist die BILANZ - kein zweiter Zaehler. Was dieser
        // Zyklus anfordert, ist noch nicht geliefert und steht deshalb
        // weiter offen; erst die Buchung senkt ihn.
        // Nach einer Ueberfuehrung ist die Sofortmenge nicht mehr offen -
        // sie liegt jetzt im schrittweisen Pfad.
        val upfrontPendingU = if (upfrontTransferNowU > 0.0) 0.0 else upfrontOffenU
        val upfrontRequestedU =
            if (liftedUpfront.bindingLimit == "mealUpfront") liftedUpfront.smbU else 0.0

        val liftedPrime = PrimeRelease.lift(
            liftedUpfront, primePlan, state,
            markerAuthorized = manualMarkerAuthorized,
            tailHeadroomU = tail?.takeIf { it.usable }?.headroomU,
            onsetCapU = if (onset.active) onset.remainingU else null,
            // Fix-Pass 2 Nr. 2: dieselbe Ledger-Korrektur wie in den
            // Such-Headrooms - sonst finanziert der NO_DEMAND->Lift-Pfad
            // In-Flight-Mengen doppelt.
            transportCommitmentU = transportModelledU,
        )

        // ---- PHASE B: die nachlaufende Mindestversorgung ------------------
        //
        // Sie steht NACH dem Prime-Lift und arbeitet auf dessen Ergebnis. Das
        // ist keine Kette zweier Aufschlaege: die beiden Phasen schliessen
        // sich zeitlich aus (MealFoundation.phaseOf), also greift immer
        // hoechstens eine. Waehrend Prime laeuft, ist `dueU` null; danach ist
        // die Prime-Huelle zu.
        //
        // DERSELBE MENGENKERN wie bei Prime (AuthorizedLift): maxSMB, iobTH,
        // maxIOB, Transporthaftung und Pumpenraster gelten unveraendert. Was
        // Phase B eigen ist, sind nur Soll, Restbudget und Fenster - und dass
        // die Onset-Huelle hier NICHT deckelt (s. MealFoundation.lift).
        //
        // Die Autorisierung kommt aus dem GEPINNTEN Snapshot, nicht aus der
        // aktuellen Einstellung.
        val lifted = MealFoundation.lift(
            base = liftedPrime,
            snapshot = foundationDecision,
            state = state,
            tailHeadroomU = tail?.takeIf { it.usable }?.headroomU,
            transportCommitmentU = transportModelledU,
        )
        // WAS DAS FUNDAMENT SELBST BEIGESTEUERT HAT (Toni 19.08.).
        //
        // WOZU DIE ZAHL GEBRAUCHT WIRD, und sie fehlt bisher auch im Feld:
        // aus der publizierten Menge allein ist nicht zu sehen, WER sie
        // wollte. Genau das ist aber die Frage bei einer grossen Mahlzeit -
        // laeuft das Fundament und wird nur der ZUSAETZLICHE Evidenzbedarf
        // von Guard/Tail gebremst (Mahlzeit bleibt hintenraus unterversorgt),
        // oder wird das Fundament SELBST bei gesundem, steigendem Zucker
        // regelmaessig blockiert (dann verfehlt die Bauform ihr Ziel)?
        //
        // Die beiden Faelle sehen in der Summe gleich aus und bedeuten das
        // Gegenteil voneinander. Deshalb wird der Stand VOR und die Anhebung
        // DURCH das Fundament getrennt gefuehrt.
        //
        // DOSIERNEUTRAL: reine Messung, sie geht nirgends in eine Entscheidung
        // ein.
        val preFoundationSmbU = liftedPrime.smbU
        val preFoundationBlock = liftedPrime.block
        val preFoundationBindingLimit = liftedPrime.bindingLimit
        val foundationLiftU = kotlin.math.max(0.0, lifted.smbU - liftedPrime.smbU)
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
            // KEIN SONDERZWEIG MEHR FUER DEN MARKER (11.08.). Der Riegel
            // prueft jede Menge, auch die autorisierte - geschuetzt wird
            // sie erst danach, durch den BODEN unten. Der Unterschied ist
            // nicht kosmetisch: der frueher hier stehende Sonderzweig hob
            // den Riegel fuer die GANZE Menge auf, also auch fuer den
            // Teil, der ueber die Autorisierung hinausgeht (Rest-Zaehler).
            // Autorisiert ist aber nur, was der Marker finanziert hat.
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
            // `null` = nicht gerechnet (Frueh-Ausstieg, P1-4). Es wird wie 0
            // behandelt, also VERWORFEN - verhaltensgleich zum Zustand vor der
            // Nullbarkeit, und die konservative Richtung: ohne gerechneten
            // Bedarf gibt es keinen Grund, einen Rest weiterzutragen. Der Fall
            // ist ohnehin fast immer schon durch die Blockpruefung oben
            // erfasst; die Zeile bleibt als eigenstaendiger Riegel stehen.
            (lifted.insulinReqU ?: 0.0) <= 0.0
        // SUB-02 Rest (Codex Re-Review 603a15a): der Uebertrag braucht eine
        // HERKUNFT. Er ist eine Zusage, die unter BESTIMMTEN Bedingungen
        // entstanden ist - Ziel, Profil-ISF, Pumpenschritt, Mahlzeitenfenster,
        // Ratio. Aendert sich eine davon, war die Zusage fuer eine andere Lage
        // gedacht und darf die neue nicht mitfinanzieren. Ein Profilwechsel
        // mitten in der Nacht ist genau der Fall, in dem ein stehengebliebener
        // Rest still zu einem zusaetzlichen Schritt wird.
        val subStepContext = SubStepAccumulator.context(
            targetMgdl = state.targetMgdl,
            isfMgdlPerU = state.isfMgdlPerU,
            pumpIncrementU = bolusStep,
            smbRatio = cfg.smbRatio,
            smbRatioRise = cfg.smbRatioRise,
            maxSmbU = cfg.maxSmbU,
            mealWindow = mealMarkerActive,
            maxIobU = state.maxIobU,
            iobThU = state.iobThU,
            markerTs = markerTs,
            profileName = runCatching { profileFunction.getProfileName() }.getOrDefault("?"),
        )
        if (subStepCarryContext != null && subStepCarryContext != subStepContext) subStepCarryU = 0.0
        subStepCarryContext = subStepContext

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
            // S0: der Rest-Zaehler HEBT die Menge ueber die Kappe, die sie
            // gerastert hat - eine mitgefuehrte Kappenliste behauptete hier
            // eine Grenze, die gerade ueberschritten wurde.
            caps = emptyList(),
            capsStage = FuseController.STAGE_SUBSTEP,
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
        // DER BODEN DER MANUELLEN AUTORISIERUNG (Tonis Vertrag 11.08.).
        //
        // Modellbasierte Vetos duerfen den markerfinanzierten Anteil senken,
        // aber nicht unter ihn fallen. Als BODEN und nicht als weitere
        // uebersprungene Pruefung, und das aus einem gemessenen Grund: bei
        // uebersprungenen Pruefungen musste jede einzelne Stelle die
        // Ausnahme kennen - vier Tore waren so geoeffnet und ein fuenftes
        // nullte die Menge trotzdem. Ein Boden am Ende der Modellkette
        // kennt keine Stelle, die ihn vergessen koennte.
        //
        // WARUM ER NICHTS ERFINDEN KANN: `lifted.smbU` IST
        // `markerAuthorizedU` (beide sind `stepped` aus PrimeRelease.lift),
        // und diese Menge hat maxSmb, iobTH, maxIOB, Onset- und
        // Prime-Huelle sowie die Pumpenschritt-Rasterung bereits passiert.
        // Der Boden stellt also nur wieder her, was das Modell genommen hat.
        //
        // WAS ER AUSDRUECKLICH NICHT AUFHEBT: der Ledger-Hold kommt
        // unmittelbar danach, das Pumpen-Gate spaeter, und der Translator
        // laesst ihn nur bei SAFETY_ZERO durch. Ein Transportfehler nullt
        // weiterhin.
        // KEIN BODEN OHNE EINHEITSKERN (Toni 11.08., Randfall 2). Die
        // Rechnung steht als reine Funktion in [MarkerFloor] - der
        // schwer konstruierbare Fall "Basis groesser, danach Veto" ist dort
        // in einer Zeile pruefbar, statt im Runner gejagt zu werden.
        //
        // `finalVeto` gibt bei fehlendem Kern MODEL_HORIZON_TOO_SHORT zurueck,
        // und der Boden haette das ueberstimmt. Ein verworfener Kern ist aber
        // kein Guard-Urteil ueber die Zukunft, sondern ein
        // INTEGRITAETSbefund ueber das Insulinmodell selbst:
        // NON_FINITE_SAMPLE, NON_LINEAR_MODEL, negative Aktivitaet, IOB
        // ausserhalb des gueltigen Bereichs, nicht ausgelaufener
        // Modellschwanz. Der Einstellungstext sagt ausdruecklich, dass
        // unglaubwuerdige Messwerte NICHT ueberstimmt werden - dann muss der
        // Code es auch tun.
        //
        // Der predictorfreie Markerpfad ist etwas anderes und bleibt: dort
        // fehlt die BAHN aus zwei Reichweiten-Gruenden, nicht das Modell.
        val autorisiert = MarkerFloor.apply(
            verified = verifiedLift,
            grant = lifted.grant,
            kernelValid = kernelFinal != null,
        )

        // DIE PROZESSIERTE TBR-SICHT, VOR der Grundregel gelesen: sie braucht
        // die FAKE_EXTENDED-Erkennung, und der Translator unten dieselbe
        // Sicht - ZWEI Lesevorgaenge koennten sich mitten im Zyklus
        // widersprechen.
        val runningTbr = processedTbrEbData.getTempBasalIncludingConvertedExtended(computeTs)
        val currentTbr = runningTbr?.let {
            TbrPolicy.Current(
                // Prozent-TBR wird HIER absolut gemacht — der Kern sieht nie
                // beides in derselben Zahl.
                absoluteRateUPerH = it.convertedToAbsolute(computeTs, profile),
                remainingMin = it.plannedRemainingMinutes,
                sourceType = if (it.type == app.aaps.core.data.model.TB.Type.FAKE_EXTENDED) TbrPolicy.SourceType.FAKE_EXTENDED
                else TbrPolicy.SourceType.TEMP_BASAL,
            )
        }

        // DIE BASAL-GRUNDREGEL SITZT SEIT DEM 17.08. IM REGELKERN, nicht mehr
        // hier: [LowThreatGate] entscheidet VOR der Kategorie, ob eine
        // Zero-TBR ueberhaupt zulaessig ist, statt eine gesetzte Null
        // nachtraeglich zu heben. Der frueher hier stehende BasalFloorGuard
        // ist damit ersatzlos entfallen - er hob eine Null, die gar nicht
        // mehr entsteht.

        // HART NACH dem Lift (Audit R95, Fix 3): Ratio-Pfad (Kernel-Ausfall)
        // und Sofort-Freigabe laufen am LEDGER_HOLD-Reject der Suche vorbei -
        // ohne diesen Riegel waere der Hold genau ueber die Pfade umgehbar,
        // die ohne Wirkungspruefung dosieren.
        val held = LedgerHoldGate.apply(autorisiert, ledgerView.hold)
        // C4c: Anzeige, Export und RT-Grund bekommen den FINALEN Schwanzbericht -
        // den mit der Menge, die wirklich hinausgeht. Auch eine beschlossene
        // NULL ist eine Entscheidung und kein fehlender Term; erst damit steht
        // dort 3/3 statt 1/3. Er ersetzt nur den BERICHT, nie die Menge - die
        // hat der Riegel oben bereits entschieden.
        val vorRiegel = tailWith(held.smbU)?.let { held.copy(tail = it) } ?: held
        // ---- DER GEMEINSAME ENDRIEGEL: GEMESSENES ABWAERTSRISIKO --------
        //
        // ER SITZT HIER UND NICHT FRUEHER (Codex 19.08.): nach Prime- und
        // Fundament-Lift, nach `finalVerify` und nach `MarkerFloor`, aber
        // VOR der Publikation. Jeder frueher gesetzte Riegel koennte von
        // einem spaeteren Wiederherstellungspfad umgangen werden - genau so
        // ist der Abendfall entstanden.
        //
        // Damit gelten Hauptpfad, Fallback, Prime, Fundament und der
        // normale SMB-Pfad automatisch gleich; es gibt keine Stelle, an der
        // eine Autorisierung ihn noch aufheben koennte.
        //
        // NUR DIE MENGE, NICHT DIE TBR. Die Basalantwort bleibt
        // ausschliesslich Ergebnis der Nutzenpruefung - "Basal zurueckhalten
        // hilft nicht mehr" und "mehr Bolus ist sicher" sind zwei
        // verschiedene Aussagen, und ihre Vermischung war der Befund.
        val nachDescent = MeasuredDescentGate.apply(vorRiegel, descentLatch.blocksPositive)

        // ---- KORREKTURPFAD-RIEGEL (Bauauftrag Toni 25.08., nachgebessert
        //      nach dem Review vom 25.08. abends) -------------------------
        //
        // ZWEI getrennte, Default-AUS-Schutzlinien fuer den REINEN
        // Korrekturkontext - Pflichtfall 25.08. frueh: (1) 1,75 U ab 06:27
        // auf die Erholung eines Sensor-V (UKF +4,0 bei robustem r -0,82);
        // (2) 0,35 U ab 08:00 in der ersten Minute nach der
        // Nachtband-Kante, direkt nach einer Stunde verriegelter Null.
        //
        // KONTEXT AUS DER AUTORITATIVEN FUNKTION (Review-P0.2): dieselbe
        // ExpectationLedger.classify, die den exportierten
        // ExpectationContext bestimmt - keine zweitgefuehrte
        // Rekonstruktion mehr. Das Ledger-Siegel kennt erst das Plugin
        // nach der Publikation; wie bei der Liveness-Kette deckt der
        // LEDGER_HOLD-Zustand dessen Ausfall (hold geht unten in die
        // Lage-Gesundheit ein), deshalb hier ledgerSealed = true.
        // NUR die SMB-Menge OHNE Marker-Grant wird genullt; TBR und alle
        // markerfinanzierten Anteile bleiben. Kein Carry. Der Zero-Latch
        // bleibt als zweite Schutzlinie unveraendert.
        val kontextLage = ExpectationLedger.classify(
            ExpectationLedger.situationOf(
                mealMarkerActive = mealMarkerActive,
                evidenceEpisodeId = episodes.evidenceEpisodeId,
                evidencePhase = evidenz?.phase,
                onsetActive = onset.active,
                mealWindow = mealWindow,
                reboundWindow = reboundWindow,
                signalHealthy = step.health == Health.READY,
                ledgerSealed = true,
            ),
        )
        // DIE SCHUTZFREIGABE (Tonis Nachforderung 25.08. abends) haengt an
        // ZWEI autoritativen Achsen, beide aus derselben Klassifikation -
        // hier wird NICHTS neu abgeleitet und NICHTS aus dem Grundtext
        // erschlossen:
        //
        //   Kontext CORRECTION            -> Schutz erlaubt
        //   Kontext MEAL, Basis KINEMATIC -> Schutz erlaubt (nur Verdacht:
        //                                    r/UKF ueber der Rampenkante,
        //                                    genau die V-Erholung)
        //   Basis MARKER_/EVIDENCE_CONF.  -> NIE (belegte Mahlzeit)
        //   Kontext EXCLUDED              -> NIE (keine auswertbare Lage)
        //
        // Der Unterschied zur Vorfassung ist der Vorfallskern: sie nahm
        // JEDES `MEAL` heraus und konnte den 25.08. deshalb konstruktiv
        // nicht verhindern. Die Basis wird SELBSTAENDIG bestimmt, damit
        // eine Evidenzepisode nicht hinter ONSET_ACTIVE/MEAL_WINDOW_OPEN
        // verschwindet (Maskierungsfall).
        val korrekturKontext = when {
            kontextLage.context == ExpectationLedger.ExpectationContext.EXCLUDED -> false
            kontextLage.mealBasis == ExpectationLedger.MealBasis.MARKER_CONFIRMED -> false
            kontextLage.mealBasis == ExpectationLedger.MealBasis.EVIDENCE_CONFIRMED -> false
            else -> true
        }
        val (revTrack, reversal) = CorrectionReversalGuard.advance(
            track = episodes.correctionReversal,
            enabled = cfg.reversalGuardEnabled,
            nowTs = signal.sourceTs,
            ukfNow = signal.ukfRatePerMin,
            rNow = signal.rSigned,
            korrekturKontext = korrekturKontext,
            fallThresholdUkf = cfg.reversalFallUkf,
            lookbackMin = cfg.reversalLookbackMin,
            reboundThresholdUkf = cfg.reversalReboundUkf,
            confirmCycles = cfg.reversalConfirmCycles,
        )
        episodes.correctionReversal = revTrack
        // Die NACHT-KANTE ankert im Uebergangszyklus selbst - aber NUR,
        // wenn der letzte Nachtzyklus bezifferten positiven Bedarf
        // AUSSCHLIESSLICH ueber das Nachtband unterdrueckt hat
        // (Review-P1.4): ein ruhiger Morgen traegt keinen pauschalen
        // Nachlauf. Die Zero-Latch-Loesung ankert beim Loesen in der
        // Latch-Stage (einen Zyklus versetzt - konservativ spaeter, nie
        // frueher). Der Merker ist prozesslokal: ein Neustart exakt auf
        // der Kante verliert den Anker - Fehlrichtung "Riegel fehlt".
        val nightJetzt = state.nightWindow
        if (lastNightWindow && !nightJetzt && lastNightSuppressedU > 0.0) {
            episodes.correctionRearm = PositiveCorrectionRearm.anker(
                episodes.correctionRearm, signal.sourceTs, PositiveCorrectionRearm.Source.NIGHT_END,
            )
        }
        lastNightWindow = nightJetzt
        // LAGE-GESUNDHEIT (Review-P0.3): der Aufwaertszaehler des
        // Nachlaufs zaehlt nur in gesunder, widerspruchsfreier Lage -
        // Signal READY, q1 nicht fallend, kein gemessenes Tief, kein
        // Abwaertsrisiko/-riegel, kein Rebound, kein Ledger-Hold. Der
        // q1-Vergleich hat einen EIGENEN Merker: zeroLatchLastQ1 gehoert
        // der Latch-Stage und traegt hier noch den Vorzykluswert.
        val korrQ1NichtFallend = korrLastQ1.isNaN() || signal.q1 >= korrLastQ1 - 0.01
        korrLastQ1 = signal.q1
        val korrLageGesund = step.health == Health.READY &&
            korrQ1NichtFallend &&
            !measuredLow &&
            !descentRisk.active &&
            !descentLatch.blocksPositive &&
            !reboundRaw &&
            !ledgerView.hold
        val (reTrack, rearm) = PositiveCorrectionRearm.advance(
            track = episodes.correctionRearm,
            enabled = cfg.correctionRearmEnabled,
            nowTs = signal.sourceTs,
            ukfNow = signal.ukfRatePerMin,
            korrekturKontext = korrekturKontext,
            holdMin = cfg.rearmHoldMin,
            confirmCycles = cfg.rearmConfirmCycles,
            upThresholdUkf = cfg.rearmUpUkf,
            lageGesund = korrLageGesund,
        )
        episodes.correctionRearm = reTrack
        val korrRiegelGrund = reversal.reason ?: rearm.reason
        val nachRiegel =
            if (korrRiegelGrund != null && nachDescent.smbU > 0.0 && nachDescent.grant == null)
                nachDescent.copy(
                    smbU = 0.0,
                    bindingLimit = nachDescent.bindingLimit + "|" + korrRiegelGrund,
                )
            else nachDescent

        // ---- PUNKT 6: DER MARKER-PRIME-AUFSCHUB (Toni 22.08.) ------------
        //
        // NACH dem 30er-Endriegel, VOR der Publikation - dieselbe Stelle,
        // an der keine Autorisierung mehr etwas heben kann. Zwei Seiten:
        //
        //   ZURUECKHALTEN: ist der gepinnte lange Horizont verletzt
        //   (gemessen fallend, ueberdeckt, Boden nah), geht der
        //   MARKERFINANZIERTE Anteil nicht hinaus, sondern in den offenen
        //   Aufschub. Die reine Korrekturbasis behaelt den 30er-Riegel und
        //   bleibt unangetastet (Vertrag 1).
        //
        //   FREIGEBEN: nach bestaetigter Erholung hoechstens EIN
        //   Pumpenschritt je Zyklus, und auch der nur mit bestandener
        //   Wirkungspruefung (finalVeto) - der Aufschub ist Autorisierung,
        //   keine Schuld (Vertraege 3-5).
        episodes.deferredPrime = DeferredPrime.expireIfDue(episodes.deferredPrime, computeTs)
        val deferredHullRestU = deferredHullRemainingU(episodes, manualBolusAfterMarkerU)
        val deferredPinnedActive = cfg.deferredPrimeEnabled &&
            episodes.deferredPrime.pinnedForMarkerTs > 0L &&
            episodes.deferredPrime.pinnedForMarkerTs == markerTs
        val risk60 = if (!deferredPinnedActive) null else LowThreatGate.measuredDescentRisk(
            signalHealthy = step.health == Health.READY,
            bgMgdl = signal.q1,
            fallRatePerMin = signal.ukfRatePerMin,
            bolusIobU = (iobTotal.iob - iobTotal.basaliob).takeIf { iobTotal.valid },
            isfMgdlPerU = isf,
            guardFloorMgdl = cfg.guardFloorMgdl,
            horizonMin = episodes.deferredPrime.horizonMin.toDouble(),
        )
        // NUR der lineare Prime-Anteil: der Sofort-Batch wandert seit dem
        // Nachtrag vom 25.08. nicht mehr in diesen Aufschub (er wuerde dort
        // in Pumpenschritten zerrieselt). Sein Rueckstand steht in der
        // Bilanz und im eigenen Zustand DEFERRED_UPFRONT_BATCH.
        var deferredWithheldU = 0.0
        var deferredReleaseU = 0.0
        var deferredDenial: String? = null
        // Bestaetigte Erholung (Vertrag 4): DIESELBE Rate wie die
        // Latch-Erholung, drei Zyklen in Folge, Risiko setzt zurueck.
        deferredRecoveryStreak = when {
            risk60?.active == true || descentRisk.active -> 0
            step.health != Health.READY -> 0
            !signal.ukfRatePerMin.isFinite() ||
                signal.ukfRatePerMin < DescentRecoveryLatch.RECOVERY_RATE_MGDL_PER_MIN -> 0
            else -> minOf(deferredRecoveryStreak + 1, DescentRecoveryLatch.REQUIRED_CONSECUTIVE_CYCLES)
        }
        val nachAufschub = if (deferredPinnedActive && risk60?.active == true && nachRiegel.smbU > 0.0) {
            // Der markerfinanzierte Anteil ist alles OBERHALB der reinen
            // Basis - und auch die Basis nur, wenn ihr Guard nicht selbst
            // vom Marker gehoben wurde (dann ist sie nicht "rein").
            val reineBasisU = if (vetted.smbU > 0.0 && !vetted.bindingLimit.contains("markerAuth"))
                kotlin.math.min(vetted.smbU, nachRiegel.smbU) else 0.0
            val markerFinanziertU = kotlin.math.max(0.0, nachRiegel.smbU - reineBasisU)
            if (markerFinanziertU <= 0.0) nachRiegel else {
                episodes.deferredPrime = DeferredPrime.withhold(
                    episodes.deferredPrime, markerFinanziertU, deferredHullRestU,
                )
                deferredWithheldU = markerFinanziertU
                if (reineBasisU > 0.0) nachRiegel.copy(
                    smbU = reineBasisU,
                    bindingLimit = nachRiegel.bindingLimit + "|deferredPrime",
                ) else nachRiegel.copy(
                    smbU = 0.0,
                    block = FuseController.Block.MARKER_PRIME_DEFERRED,
                    bindingLimit = "deferredPrime",
                    unsafeSituation = true,
                )
            }
        } else {
            val frei = DeferredPrime.releaseStep(
                state = episodes.deferredPrime,
                nowTs = computeTs,
                enabled = cfg.deferredPrimeEnabled,
                activeMarkerTs = markerTs,
                ledgerHold = ledgerView.hold,
                latchBlocksPositive = descentLatch.blocksPositive,
                recoveryConfirmed = deferredRecoveryStreak >= DescentRecoveryLatch.REQUIRED_CONSECUTIVE_CYCLES,
                signalHealthy = step.health == Health.READY,
                measuredLow = measuredLow,
                reboundRaw = reboundRaw,
                descentRiskActive = risk60?.active == true || descentRisk.active,
                manualBolusAfterMarkerU = manualBolusAfterMarkerU,
                pumpStepU = bolusStep,
                hullRemainingU = deferredHullRestU,
            )
            deferredDenial = frei.denial?.name
            when {
                frei.stepU <= 0.0 -> nachRiegel
                finalVeto(nachRiegel.smbU + frei.stepU) != null -> {
                    deferredDenial = "VERIFY_FAILED"
                    nachRiegel
                }
                else -> {
                    deferredReleaseU = frei.stepU
                    nachRiegel.copy(
                        smbU = nachRiegel.smbU + frei.stepU,
                        block = FuseController.Block.NONE,
                        bindingLimit = nachRiegel.bindingLimit + "|deferredPrimeRelease",
                        // Wie beim Sub-Step: die Anhebung geht ueber die
                        // Kappen, die die Basis gerastert haben.
                        caps = emptyList(),
                        capsStage = "deferredRelease",
                    )
                }
            }
        }
        // ---- DER LIVENESS-KANAL (Bauvertrag Toni + Codex 22.08.) ---------
        //
        // NACH dem Aufschub, VOR der Publikation - dieselbe Stelle wie die
        // beiden Endriegel. Der Kanal ist MENGENBASIERT (autoISF-Prinzip):
        // Guard-Unterkante und DIA-Schwanz sind hier weder Veto noch Kappe -
        // deren Fehlzertifikate SIND der gemessene Anlass (22.08.: 93/93
        // Deadlock-Zyklen mit Unterkante median +97 mg/dl unter dem real
        // eingetretenen 120-min-Minimum; 90 der 116 Minuten ueber 180 waren
        // blockierte Minuten mit ERKANNTEM Bedarf). Was stattdessen traegt:
        //
        //   GEMESSENE Riegel absolut: Signal, Sicht, Hold, Rebound, Low,
        //   30er- und Marker-Horizont-Risiko, Latch, fallender UKF - jeder
        //   beendet einen aktiven Lauf und setzt die Re-Arm-Sperre.
        //
        //   MENGE statt Bahn: Kandidat aus der Mittelbahn (die IOB-Wirkung
        //   steckt bereits in der Praediktion - kein zweites "- iob"), durch
        //   effektive Ratio, maxSMB und den STRENGSTEN von drei benannten
        //   Deckeln (globales iobTH, Kanaldeckel, maxIOB) abzueglich capIob
        //   und Transporthaftung (P0-Deckelvertrag).
        //
        //   `max`, NIE Addition: der Kanal ersetzt den Saegezahn, er stapelt
        //   nicht auf ihn. KEIN Carry: was das Raster abschneidet, verfaellt -
        //   kein Nachhol-Burst nach einer Sperrphase.
        //
        // BEWUSST KEIN `finalVeto` fuer die Kanalmenge: der enthaelt den
        // Schwanz-Veto (`tailWith`) und die Guard-Zertifikate - genau die
        // Instanzen, deren Fehlurteil der Kanal beheben soll. Ihre Aufgabe
        // uebernehmen hier die gemessenen Riegel plus der Mengendeckel.
        // Die TECHNISCHE Haelfte der Pruefung bleibt aber Pflicht (Codex
        // 22.08.): ohne gebauten Einheitskern, der das Bewertungsfenster
        // deckt, dosiert auch dieser Kanal nicht (MODEL_UNAVAILABLE) -
        // fachlicher Bypass ja, technischer Blindflug nein.
        var livenessDenial: String? = null
        var livenessExit: String? = null
        var livenessModelReject: String? = null
        var livenessNeedU: Double? = null
        var livenessReleaseMeanMgdl: Double? = null
        var livenessBaseRatio: Double? = null
        var livenessLiveRatio: Double? = null
        var livenessBgMinEffective: Double? = null
        var livenessBgMinSource: String? = null
        var livenessHeadroomU: Double? = null
        var livenessCandidateU = 0.0
        var livenessLiftU = 0.0
        var livenessBinding: String? = null
        var livenessProfil: String? = null
        var livenessProfilGrund: String? = null
        var livenessSelectedRatioCap: Double? = null
        var livenessSelectedIobCapPct: Double? = null
        var livenessProfileIobLimitU: Double? = null
        var livenessStaticCorrectionNeedU: Double? = null
        var livenessCoverageState: String? = null
        var livenessPressureActive: Boolean? = null

        // ---- MARKER-LEISTUNGSFRIST (Bauauftrag Toni 23.08. nachts) --------
        // Der Marker ist eine ZEITLICH BEGRENZTE Leistungsautorisierung des
        // Liveness-Kanals, keine Voraussetzung fuer Liveness insgesamt.
        // Gepinnt wird NUR ein im Prozess beobachteter Markerwechsel; ein
        // beim Warmstart bloss vorgefundener Marker ohne passende
        // persistierte Identitaet eroeffnet kein rueckwirkendes MEAL (§3).
        // Die Dauer wird beim Druck eingefroren.
        if (episodeGate.creditRevoked || markerTs <= 0L) {
            // Ruecknahme loescht Identitaet und Frist SOFORT.
            episodes.markerPowerPinnedFor = 0L
            episodes.markerPowerDeadlineTs = 0L
            markerPowerLastSeenTs = 0L
        } else if (markerPowerLastSeenTs == -1L) {
            markerPowerLastSeenTs = markerTs
        } else if (markerTs != markerPowerLastSeenTs) {
            episodes.markerPowerPinnedFor = markerTs
            episodes.markerPowerDeadlineTs = markerTs + cfg.livenessMealPowerMin * 60_000L
            markerPowerLastSeenTs = markerTs
        }
        // HALB OFFEN: exakt an der Deadline gilt bereits CORRECTION. Eine
        // laenger lebende Evidenzepisode verlaengert die Frist NICHT; die
        // persistierte Markerfrist ist autoritativ, nie state.context (§6 -
        // der Live-Trail zeigte state.context=CORRECTION bei aktivem Marker).
        val markerPowerActive = episodes.markerPowerPinnedFor > 0L &&
            episodes.markerPowerPinnedFor == markerTs &&
            computeTs >= episodes.markerPowerPinnedFor &&
            computeTs < episodes.markerPowerDeadlineTs
        val livenessNormalSmbU = nachAufschub.smbU
        val decisionVorZeroLatch: FuseController.Decision = run {
            if (!cfg.livenessChannelEnabled) {
                // AUS heisst aus: Lauf und Streak enden, aber eine bereits
                // gesetzte Sperre bleibt stehen (sie ist eine Zusage). Ein
                // dabei beendeter Lauf traegt sein Exit-Label (Audit 22.08.:
                // sonst endete er unbenannt).
                if (livenessActive) livenessExit = "DISABLED"
                livenessActive = false
                livenessStreak = 0
                livenessDenial = "DISABLED"
                return@run nachAufschub
            }
            // ---- PROFILWAHL (Bauauftrag §2): EIN Kanal, zwei Mengenprofile.
            // MEAL innerhalb der Markerfrist, CORRECTION sonst; EXCLUDED
            // setzt der harte Riegel unten. Alle uebrigen Schutzregeln
            // bleiben gemeinsam.
            val profilRatioCap = if (markerPowerActive) cfg.livenessMealRatioCap else cfg.livenessCorrectionRatioCap
            val profilIobCapPct = if (markerPowerActive) cfg.livenessMealIobCapPercent else cfg.livenessCorrectionIobCapPercent
            livenessProfil = if (markerPowerActive) "MEAL" else "CORRECTION"
            livenessProfilGrund = when {
                markerPowerActive -> "MARKER_POWER"
                episodes.markerPowerPinnedFor > 0L && episodes.markerPowerPinnedFor == markerTs -> "POWER_EXPIRED"
                markerTs > 0L -> "MARKER_NOT_PINNED"
                else -> "NO_MARKER"
            }
            livenessSelectedRatioCap = profilRatioCap
            livenessSelectedIobCapPct = profilIobCapPct
            // Toni + Codex 22.08.: JEDE Aenderung an den drei
            // Kanal-Stellgroessen waehrend eines Laufs beendet ihn, und der
            // Streak beginnt unter der neuen Regel neu - auch Deckel und
            // Sperrzeit veraendern sonst einen bereits laufenden Kanal.
            // OHNE Sperre: das ist eine Bedienhandlung, kein gemessenes
            // Risiko.
            // Beide KONFIGURIERTEN Schwellen, nie die wirksame: ein
            // regulaerer Tag/Nacht-Wechsel ist KEIN CONFIG_CHANGED (v20).
            val cfgJetzt = cfg.livenessBgMinDayMgdl.toString() + "|" +
                cfg.livenessBgMinNightMgdl + "|" +
                cfg.livenessMealRatioCap + "|" + cfg.livenessMealIobCapPercent + "|" +
                cfg.livenessCorrectionRatioCap + "|" + cfg.livenessCorrectionIobCapPercent + "|" +
                cfg.livenessReArmMin
            // ERST gemerkt, ANGEWENDET erst nach den harten Riegeln (Audit
            // 22.08.): faellt die Aenderung mit einem gemessenen Riegel
            // zusammen, gewinnt der Riegel-Exit MIT Sperre - der sperrfreie
            // CONFIG_CHANGED-Ausgang darf ihn nicht verdraengen.
            val cfgGeaendert = livenessCfgSeen != null && livenessCfgSeen != cfgJetzt
            livenessCfgSeen = cfgJetzt
            // v20: getrennte Tag-/Nachtschwelle. DIESELBE autoritative
            // Nachtrechnung wie beim Totband - aber OHNE dessen Schalter:
            // die Schwelle darf nicht am Totband-Schalter haengen. Rebound
            // und gemessene Riegel bleiben davon unberuehrt.
            val nachtFenster = NightWindow.isNight(
                MidnightUtils.secondsFromMidnight(signal.sourceTs), cfg.nightStartMin, cfg.nightEndMin,
            )
            val bgMinWirksam = if (nachtFenster) cfg.livenessBgMinNightMgdl else cfg.livenessBgMinDayMgdl
            livenessBgMinEffective = bgMinWirksam
            livenessBgMinSource = if (nachtFenster) "NIGHT" else "DAY"
            fun sperren(grund: String): FuseController.Decision {
                livenessExit = grund
                livenessActive = false
                livenessStreak = 0
                // maxOf: eine stehende Sperre ist eine Zusage - sie wird
                // verlaengert, nie verkuerzt (Audit 22.08.).
                episodes.livenessReArmUntilTs = maxOf(
                    episodes.livenessReArmUntilTs,
                    computeTs + cfg.livenessReArmMin * 60_000L,
                )
                return nachAufschub
            }
            // Taktluecke (Audit 22.08.): mehr als drei Minuten ohne Zyklus
            // sind so unbeobachtet wie ein Abbruchzyklus - der Lauf endet,
            // die Bewaffnung wird neu verdient. BEWUSST OHNE Sperre: die
            // Medtrum-Zyklen strecken sich real bis 854 s (Lifecycle-
            // Messung), eine Sperre je Streckung entwertete den Kanal.
            if (livenessLastCycleTs > 0L &&
                computeTs - livenessLastCycleTs > 3 * 60_000L &&
                (livenessActive || livenessStreak > 0)
            ) {
                if (livenessActive) livenessExit = "CONTINUITY_GAP"
                livenessActive = false
                livenessStreak = 0
            }
            livenessLastCycleTs = computeTs
            // Die TECHNISCHE Modellpruefung, TYPISIERT (Codex-P0 22.08.):
            // dieselbe Integritaetskette wie in `finalVeto`
            // (verifyTechnicalIntegrity == verifyGuardFloor OHNE das
            // semantische Urteil, geteilte Implementierung), geprueft an
            // EINER Pumpenstufe. Nur GUARD_FLOOR und Schwanz bleiben im
            // Kanal ueberstimmbar; der Grund steht typisiert im Trail.
            livenessModelReject = if (kernelFinal == null)
                CandidateSearch.Reject.MODEL_HORIZON_TOO_SHORT.name
            else CandidateSearch.verifyTechnicalIntegrity(
                prediction, kernelFinal, built.input.isfSlots, candidateBand, bolusStep,
                restraint = restraint,
            )?.name
            // §5: ein Markerwechsel tauscht die Caps nie STILL in einem
            // laufenden Lauf - Lauf und Streak gehoeren eindeutig zu dem
            // Pin, unter dem sie begannen. Ende OHNE Sperre; die frische
            // Bewaffnung (drei Druckzyklen) ist die Hysterese. Der
            // FristABLAUF wechselt dagegen NAHTLOS auf die engeren
            // Correction-Caps (Profilwahl oben, je Zyklus): sicher, weil
            // die Validierung garantiert, dass CORRECTION nie offener ist -
            // kein Carry, keine weitere MEAL-Haftung, keine Luecke.
            if (livenessRunPinnedFor != episodes.markerPowerPinnedFor) {
                if (livenessActive || livenessStreak > 0) {
                    if (livenessActive) livenessExit = "MARKER_CHANGED"
                    livenessActive = false
                    livenessStreak = 0
                }
                livenessRunPinnedFor = episodes.markerPowerPinnedFor
            }
            // VOR den gemessenen Riegeln, mit Absicht: der Druckzyklus
            // selbst traegt den Evidenz-Rebase-Blip (EXCLUDED_LAGE) - ein
            // Markerdruck waehrend eines Laufs endete sonst als Riegel-Exit
            // MIT 10-min-Sperre, und die Mahlzeit wartete auf den Kanal.
            // Die Riegel selbst bleiben absolut: sie verhindern unten
            // weiterhin Bewaffnung und Hub dieses Zyklus.
            // Die GEMESSENEN Riegel - fuer Lauf UND Bewaffnung. Waehrend
            // eines Laufs beenden sie ihn MIT Sperre; davor verhindern sie
            // die Bewaffnung und setzen den Streak zurueck.
            val hart = when {
                step.health != Health.READY -> "SIGNAL_UNHEALTHY"
                treatmentView == null -> "VIEW_UNREADABLE"
                livenessModelReject != null -> "MODEL_UNAVAILABLE"
                ledgerView.hold -> "LEDGER_HOLD"
                reboundRaw -> "REBOUND_ACTIVE"
                measuredLow -> "MEASURED_LOW"
                descentRisk.active -> "DESCENT_RISK"
                risk60?.active == true -> "DESCENT_RISK_MARKER"
                descentLatch.blocksPositive -> "LATCH_ACTIVE"
                // Korrekturpfad-Riegel (25.08.): im K-Profil sperren
                // V-Reversal und Freigabe-Nachlauf auch den Kanal - die
                // MEAL-Frist bleibt ausdruecklich frei (Mahlzeitenpfade
                // duerfen nicht pauschal betroffen sein).
                !markerPowerActive && reversal.blocks -> reversal.reason!!
                !markerPowerActive && rearm.blocks -> rearm.reason!!
                // Tonis Lagen-Vertrag: MEAL und CORRECTION duerfen, eine
                // EXCLUDED-Lage nie. Rebound und Signal decken die Riegel
                // oben; hier kommen die Evidenz-Ausschluesse dazu:
                // SUSPENDED (widerrufener Kredit, Segmentbruch, Tief) und
                // UNKNOWN (fragliche Buchfuehrung) sind weder Mahlzeit
                // noch Korrektur. `ledgerSealed` kennt erst das Plugin
                // nach der Publikation - dessen Ausfall deckt der
                // LEDGER_HOLD-Riegel.
                evidenz == null ||
                    evidenz.phase == EvidenceStock.Phase.SUSPENDED ||
                    evidenz.phase == EvidenceStock.Phase.UNKNOWN -> "EXCLUDED_LAGE"
                !signal.ukfRatePerMin.isFinite() ||
                    signal.ukfRatePerMin < LivenessChannel.UKF_FLOOR_MGDL_PER_MIN -> "FALLING"
                else -> null
            }
            if (hart != null) {
                livenessProfil = "EXCLUDED"
                livenessProfilGrund = hart
                if (livenessActive) return@run sperren(hart)
                livenessStreak = 0
                livenessDenial = hart
                return@run nachAufschub
            }
            if (cfgGeaendert) {
                if (livenessActive) livenessExit = "CONFIG_CHANGED"
                livenessActive = false
                livenessStreak = 0
            }
            // Manuelle Intervention beendet den Lauf (Vertrag): ein
            // NORMAL-Bolus nach der Bewaffnung heisst, der Nutzer hat
            // uebernommen - der Kanal draengelt nicht daneben weiter.
            // Safe-Call statt Smart-Cast: die Null ist oben bereits ein
            // harter Riegel (VIEW_UNREADABLE), hierher kommt nur eine Sicht.
            val manualTs = treatmentView?.boluses
                ?.filter { it.isValid && it.type == BS.Type.NORMAL }
                ?.maxOfOrNull { it.timestamp }
            // Aktiver Lauf: massgeblich ist der BEGINN des Bewaffnungs-
            // Streaks, nicht der Bewaffnungsmoment - ein Bolus aus dem
            // Bewaffnungsfenster, der erst NACH der Bewaffnung in der Sicht
            // auftaucht (DB-Latenz), beendet den Lauf trotzdem (Audit
            // 22.08., Sichtbarkeitsrennen).
            if (livenessActive && manualTs != null && manualTs >= livenessStreakStartTs) {
                return@run sperren("MANUAL_INTERVENTION")
            }
            // Codex 22.08.: auch VOR der Bewaffnung hat der Nutzer mit
            // einem NORMAL-Bolus uebernommen. WANDUHR statt Streak-Fenster
            // (Audit 22.08.): ein Streak-Reset durch Riegel, Druckflackern
            // oder Config-Wechsel stempelte das Fenster sonst am Bolus
            // vorbei, und die Bewaffnung liefe drei Zyklen spaeter doch.
            // Die Sperre rechnet ab dem BOLUS und verkuerzt eine stehende
            // nie; sie ist damit von selbst neustartfest (die Boluse kommen
            // aus der Behandlungssicht).
            if (!livenessActive && manualTs != null &&
                manualTs + cfg.livenessReArmMin * 60_000L > computeTs
            ) {
                livenessDenial = "MANUAL_INTERVENTION"
                livenessStreak = 0
                episodes.livenessReArmUntilTs = maxOf(
                    episodes.livenessReArmUntilTs,
                    manualTs + cfg.livenessReArmMin * 60_000L,
                )
                return@run nachAufschub
            }
            // P2, seit v21 magnitudenSENSITIV (Codex 22.08. spaet):
            // `declineStreak >= 2` beendete auch bei 3,000 -> 2,995 -> 2,990
            // - eine normale Abflachung eines weiterhin starken Anstiegs
            // entwaffnete den Kanal fuer zehn Minuten. Jetzt gilt die
            // AUTORITATIVE bestaetigte Wende der Schatten-Klassifikation:
            // drei monoton fallende Werte UND kumulierte Abnahme >= 0,20
            // mg/dl/min (dieselbe belegte Schwelle, die ADAPTIVE traegt).
            // Gemessenes Fallen (FALLING), Low, Rebound, Descent-Risk
            // bleiben sofortige harte Exits; r < 1 endet weiter ohne Sperre.
            if (livenessActive && turnClassification.phase == TurnResponseShadow.Phase.TURNING_DOWN) {
                return@run sperren("TURN_EXIT")
            }
            // Druckbedingung: BG ueber der WIRKSAMEN Schwelle (Tag/Nacht)
            // UND r >= 1,0 - drei Zyklen in Folge
            // bewaffnen. Faellt der Druck waehrend eines Laufs weg, endet er
            // OHNE Sperre: die Wiederbewaffnung braucht ohnehin drei neue
            // Druckzyklen, das ist die Hysterese.
            val rSig = signal.rSigned
            val druck = signal.q1 > bgMinWirksam &&
                rSig != null && rSig.isFinite() && rSig >= LivenessChannel.R_MIN_MGDL_PER_MIN
            if (livenessActive && !druck) {
                livenessExit = "PRESSURE_GONE"
                livenessActive = false
                livenessStreak = 0
                return@run nachAufschub
            }
            if (druck) {
                if (livenessStreak == 0) livenessStreakStartTs = computeTs
                livenessStreak = minOf(livenessStreak + 1, 99)
            } else livenessStreak = 0
            if (!livenessActive) {
                when {
                    computeTs < episodes.livenessReArmUntilTs -> {
                        // v19 (Codex-Befund im Live-Trail 22.08. 22:53-23:03):
                        // der Streak lief WAEHREND der Sperre weiter (1->10)
                        // und der Kanal war nach Fristablauf sofort wieder
                        // scharf. Vertrag: die Sperre nullt den Streak jeden
                        // Zyklus - erst NACH Ablauf zaehlen drei frische
                        // Druckzyklen (1/3, 2/3, 3/3), dann bewaffnet er.
                        livenessStreak = 0
                        livenessDenial = "REARM_BLOCKED"
                        return@run nachAufschub
                    }
                    livenessStreak < LivenessChannel.ARM_STREAK -> {
                        livenessDenial = "NOT_CONFIRMED"
                        return@run nachAufschub
                    }
                    // P2 symmetrisch, seit v21 mit derselben Magnitude wie
                    // der Exit: solange eine BESTAETIGTE Wende steht, wird
                    // nicht neu bewaffnet.
                    turnClassification.phase == TurnResponseShadow.Phase.TURNING_DOWN -> {
                        livenessDenial = "TURN_STANDING"
                        return@run nachAufschub
                    }
                    // Bewaffnet wird nur GEGEN den Deadlock: der Normalpfad
                    // muss von Unterkante oder Schwanz gedeckelt sein. Ist er
                    // offen, gibt es nichts zu heben - und ein spaeterer
                    // Saegezahn-Zyklus (kleine Menge, Block NONE) erbt den
                    // Lauf aus dem Deadlock-Zyklus, in dem er begann.
                    nachAufschub.block != FuseController.Block.GUARD_FLOOR &&
                        nachAufschub.block != FuseController.Block.TAIL -> {
                        livenessDenial = "NORMAL_PATH_OPEN"
                        return@run nachAufschub
                    }
                    else -> {
                        livenessActive = true
                        livenessArmTs = computeTs
                    }
                }
            }
            // AKTIV: Kandidat rechnen, `final = max(normal, live)`.
            val releaseMean = prediction.points
                .firstOrNull { it.offsetMin == cfg.releaseHorizonMin }?.meanBg
                ?: return@run sperren("NO_RELEASE_MEAN")
            // ---- BASIS-RATIO AUS DER RAMPE (Toni 24.08., v27-Korrektur) --
            // Nicht state.effectiveSmbRatio: die faellt ausserhalb des
            // Normalpfad-Mahlzeitfensters auf die Korrektur-Ratio zurueck,
            // und der Livefall (Marker +115 min, r 2,69, mealWindow false)
            // lief als "Live M" unsichtbar auf 0,15. BEIDE Profile rampen
            // (gleiche geteilte Mathematik, gleiche State-Eingaben); der
            // Unterschied MEAL/CORRECTION ist AUSSCHLIESSLICH der unten
            // angewendete Profildeckel - der K-Deckel ist die
            // Skalierungsgrenze der Korrektur, keine Obergrenze einer
            // festen 0,15 (v26-Irrtum, von Toni am selben Abend
            // korrigiert). Der Normalpfad liest weiter
            // state.effectiveSmbRatio und bleibt bitgleich.
            val baseRatio = LivenessChannel.baseRatio(
                smbRatioCorrection = state.smbRatioCorrection,
                smbRatioRise = state.smbRatioRise,
                rSignedMgdlPerMin = state.rSignedMgdlPerMin,
                riseRampLowRPerMin = state.riseRampLowRPerMin,
                riseRampHighRPerMin = state.riseRampHighRPerMin,
            )
            livenessBaseRatio = baseRatio
            // ---- RATIO-DECKEL, PROFILGEWAEHLT (Bauauftrag §4) -----------
            // liveRatio = min(Basis, Profil-Cap): der Deckel begrenzt NUR
            // die Geschwindigkeit dieses Kanals und wird ERST NACH der
            // Basisberechnung angewendet - 1,0 heisst weiterhin nur "kein
            // zusaetzlicher Deckel", nie Ratio 1,0.
            val liveRatio = kotlin.math.min(baseRatio, profilRatioCap)
            val bedarfU = kotlin.math.max(0.0, (releaseMean - target) / isf)
            // ---- COVERAGE-VORBEREITUNG (Toni 23.08. nachts) --------------
            // HIER ist der Anschlusspunkt des spaeteren, AUSSCHLIESSLICH im
            // CORRECTION-Profil wirkenden Coverage-Riegels:
            //   Profil -> Bedarf/Kandidat -> technische Integritaet ->
            //   [CorrectionCoverageAssessment] -> Profil-Caps -> max(...)
            // CORRECTION_COVERED darf dann NUR den Liveness-Lift nullen; der
            // Normalpfad bleibt verfuegbar, und im MEAL-Profil sperrt
            // Coverage nie hart. BEWUSST KEIN insulinReq - IOB: insulinReq
            // stammt aus einer IOB-beeinflussten Prognose, ein weiterer
            // Abzug waere eine Doppelanrechnung. Solange keine gemeinsame,
            // horizontkonsistente Insulinwirkung vorliegt, wird NICHTS
            // geschaetzt: coverageState = UNAVAILABLE, effektive Deckung
            // und Marge bleiben null - exportiert wird nur die STATISCHE
            // Korrekturdistanz und die gemessene Druckbedingung. Die
            // Regel-Semantik entscheidet ein eigener Commit nach Auswertung.
            livenessStaticCorrectionNeedU = kotlin.math.max(0.0, (signal.q1 - target) / isf)
            livenessCoverageState = "UNAVAILABLE"
            // Review Toni 24.08.: `druck` (BG ueber Schwelle UND r >= 1) ist
            // die DRUCKBEDINGUNG des Kanals, KEINE nachgewiesene Stoerung -
            // als disturbanceActive haette das Feld waehrend fast jedes
            // Laufs true getragen und die spaetere Ueberdeckungsbremse
            // strukturell entwertet. disturbanceActive bleibt null, bis
            // eine echte, modellkonsistente Stoerungsgroesse existiert.
            livenessPressureActive = druck
            // Codex 22.08. spaet: der ROHE Kanalbedarf gehoert in den Export.
            // Ohne ihn stand im Viewer "Bedarf -", waehrend der Kanal 0,10 U
            // anforderte (decision.insulinReqU ist im Deadlock null, und
            // candidateU/ratio ist bei maxSMB-Bindung nicht invertierbar).
            // null = Rechnung nicht ausgefuehrt; 0.0 = ausgefuehrt, kein
            // positiver Bedarf.
            livenessNeedU = bedarfU
            livenessReleaseMeanMgdl = releaseMean
            livenessLiveRatio = liveRatio
            livenessCandidateU = LivenessChannel.candidateU(
                releaseMeanMgdl = releaseMean,
                targetMgdl = target,
                isfMgdlPerU = isf,
                smbRatio = liveRatio,
                maxSmbU = cfg.maxSmbU,
            )
            livenessProfileIobLimitU = profilIobCapPct / 100.0 * state.maxIobU
            val head = LivenessChannel.headroomU(
                globalIobThU = state.iobThU,
                // PROFIL-IOB-Deckel (§4); globales iobTH und maxIOB bleiben
                // harte Obergrenzen im selben min().
                livenessCapU = profilIobCapPct / 100.0 * state.maxIobU,
                maxIobU = state.maxIobU,
                capIobU = state.capIobU,
                transportU = transportModelledU,
            )
            // Der REST des Kanaldeckels gehoert in den Export (Toni 23.08.:
            // "iobTH voll" war am Geraet unsichtbar, und die SMB-Zeile soll
            // zeigen, wieviel der Kanal noch darf). Nie im Viewer
            // nachrechnen - das waere eine zweite Wahrheit ueber dieselbe
            // Groesse.
            livenessHeadroomU = head.headroomU
            val liveU = LivenessChannel.quantize(
                kotlin.math.min(livenessCandidateU, head.headroomU), bolusStep,
            )
            // Die bindende Grenze wird IMMER benannt (P0): erst die Deckel,
            // dann maxSMB, sonst war die Ratio selbst das Mass.
            livenessBinding = when {
                head.headroomU < livenessCandidateU - 1e-9 -> head.binding
                cfg.maxSmbU < liveRatio * bedarfU - 1e-9 -> "maxSmb"
                // Der Cap war das Mass, wenn er die Basis real gekappt hat -
                // sonst bleibt "smbRatio" die ehrliche Antwort.
                liveRatio < baseRatio - 1e-9 -> "livenessRatioCap"
                else -> "smbRatio"
            }
            if (liveU <= nachAufschub.smbU + 1e-9) {
                // `max` heisst: der Kanal hebt nur, er ersetzt nie nach
                // unten - liefert der Normalpfad mehr, bleibt der Normalpfad.
                livenessDenial = if (liveU <= 0.0) "NO_HEADROOM" else "NORMAL_COVERS"
                return@run nachAufschub
            }
            livenessLiftU = liveU - nachAufschub.smbU
            nachAufschub.copy(
                smbU = liveU,
                block = FuseController.Block.NONE,
                bindingLimit = "liveness:" + livenessBinding,
                // Wie Sub-Step und Aufschub-Freigabe: die Kanalmenge ging
                // ueber die Kappen, die die Basis gerastert haben.
                caps = emptyList(),
                capsStage = "liveness",
                // Die gemessenen Riegel sind zu diesem Zeitpunkt nachweislich
                // frei; ersetzt wurden allein die Modell-Vetos - deren Urteil
                // der Kanal begruendet verwirft.
                unsafeSituation = false,
            )
        }
        // ---- ZERO-TBR-LATCH (Bauauftrag Toni 24.08. abends) ---------------
        // Befund desselben Tages: das Low-Tor eroeffnete 16:41-17:53 FUENF
        // berechtigte Zero-TBRs, und der punktuelle Nutzenwert (benefit < 5)
        // warf sie jeweils binnen Minuten weg - ~79 min Profilbasal liefen
        // in einen vorhersehbaren, langsamen Fall (Nadir 62). Der Latch
        // verriegelt eine EINMAL berechtigt eroeffnete Null fuer die Dauer
        // der Fall-Episode: riskActive ist das VERDIKT des Low-Tors (nicht
        // die Nutzenprobe); solange es positiv ist, haelt der Riegel
        // trivial, und faellt es auf NONE (BENEFIT_BELOW_THRESHOLD,
        // FLOOR_BEYOND_HORIZON, NOT_FALLING), beginnt die ERHOLUNGS-
        // pruefung statt des Abbruchs. Geloest wird ueber die GETEILTE
        // Descent-Erholungssemantik (UKF >= +0,20 UND roher q1 faellt
        // nicht weiter UND kein Descent-/Low-Risiko, drei lueckenlose
        // Zyklen, jeder Rueckfall nullt; nach bestaetigtem Observer-Tief
        // genuegt wie beim Descent-Latch EIN Zyklus) ODER den RUHE-AUSGANG
        // gegen die Zero-Falle (zeroLatchCalmExitMin Zyklen stabil nicht
        // fallend, q1 >= Boden + zeroLatchCalmDistanceMgdl, keine
        // Bolus-Ueberdeckung). NUR die Basalachse: der Override ersetzt
        // ausschliesslich die TbrAction; smbU/Block/Bindung bleiben
        // unangetastet, und der Translator laesst ueber latchZeroOnly den
        // SMB-Anteil ausdruecklich frei.
        var zeroLatchGrund: String? = null
        var zeroLatchUebersteuert = false
        val decision: FuseController.Decision = run {
            if (!cfg.zeroLatchEnabled) {
                // AUS heisst aus - auch ein persistierter Riegel wird
                // geleert, damit ein spaeteres Einschalten frisch beginnt.
                if (episodes.zeroLatch.active) episodes.zeroLatch = DescentRecoveryLatch.State()
                zeroLatchRuntime = DescentRecoveryLatch.Runtime()
                zeroCalmStreak = 0
                zeroArmStreak = 0
                zeroArmLastTs = 0L
                zeroLatchLastQ1 = Double.NaN
                return@run decisionVorZeroLatch
            }
            val q1NichtFallend = zeroLatchLastQ1.isNaN() || signal.q1 >= zeroLatchLastQ1 - 0.01
            zeroLatchLastQ1 = signal.q1
            // Der AUSLOESE-Zaehler (v29) laeuft VOR decide - hier gilt nur
            // noch sein Ergebnis: das Risiko steht, wenn das Verdikt
            // SCHARF ist (Low sofort, Fall erst 2/2, aktiver Latch haelt).
            val latch = DescentRecoveryLatch.advance(
                state = episodes.zeroLatch,
                runtime = zeroLatchRuntime,
                riskActive = zeroVerdiktScharf && (zeroLowVerdikt || zeroFallVerdikt),
                signalHealthy = step.health == Health.READY,
                measuredLow = measuredLow,
                fallRatePerMin = signal.ukfRatePerMin,
                sourceTs = signal.sourceTs,
                extraRiskWait = descentRisk.active,
                rawNotFalling = q1NichtFallend,
            )
            // FREIGABE-NACHLAUF (25.08.): eine geloeste Verriegelung ankert
            // den PositiveCorrectionRearm - die Kante wirkt ab dem
            // NAECHSTEN Zyklus (konservativ spaeter, nie frueher).
            if (episodes.zeroLatch.active && !latch.state.active) {
                episodes.correctionRearm = PositiveCorrectionRearm.anker(
                    episodes.correctionRearm, signal.sourceTs,
                    PositiveCorrectionRearm.Source.ZERO_LATCH_RELEASED,
                )
            }
            episodes.zeroLatch = latch.state
            zeroLatchRuntime = latch.runtime
            zeroLatchGrund = latch.reason.name
            if (latch.state.active) {
                // RUHE-AUSGANG (Pfad 2): stabil, weit genug ueber dem Boden
                // und ohne Bolus-Ueberdeckung - sonst hielte ein dauerhaft
                // flacher BG die Null unbegrenzt ("Zero-Falle").
                val abstand = lowThreatResult.distanceToFloorMgdl
                val ueberdeckung = lowThreatResult.bolusIobU?.let { b ->
                    abstand?.let { b * isf - it }
                }
                val ruhig = step.health == Health.READY && !measuredLow && !descentRisk.active &&
                    signal.ukfRatePerMin.isFinite() && signal.ukfRatePerMin >= -0.03 &&
                    q1NichtFallend &&
                    abstand != null && abstand >= cfg.zeroLatchCalmDistanceMgdl &&
                    ueberdeckung != null && ueberdeckung <= 0.0
                // ZUSAMMENHAENGENDE Zyklen wie beim Erholungszaehler: eine
                // Luecke > 90 s darf keine Ruhe belegen (Tonis Review 24.08.
                // - der Schluessel zaehlt Zyklen, keine Wanduhrminuten).
                val anschluss = zeroCalmLastTs > 0L &&
                    signal.sourceTs > zeroCalmLastTs &&
                    signal.sourceTs - zeroCalmLastTs <= 90_000L
                zeroCalmStreak = if (ruhig) (if (anschluss) zeroCalmStreak + 1 else 1) else 0
                zeroCalmLastTs = signal.sourceTs
                if (zeroCalmStreak >= cfg.zeroLatchCalmExitMin) {
                    // Auch der Ruhe-Ausgang ist eine Freigabe-Kante.
                    episodes.correctionRearm = PositiveCorrectionRearm.anker(
                        episodes.correctionRearm, signal.sourceTs,
                        PositiveCorrectionRearm.Source.ZERO_LATCH_RELEASED,
                    )
                    episodes.zeroLatch = DescentRecoveryLatch.State()
                    zeroLatchRuntime = DescentRecoveryLatch.Runtime()
                    zeroCalmStreak = 0
                    zeroLatchGrund = "CALM_RECOVERED"
                }
            } else zeroCalmStreak = 0
            if (episodes.zeroLatch.active &&
                decisionVorZeroLatch.tbr != FuseController.TbrAction.ZERO_TEMP
            ) {
                zeroLatchUebersteuert = true
                decisionVorZeroLatch.copy(tbr = FuseController.TbrAction.ZERO_TEMP)
            } else decisionVorZeroLatch
        }

        // Der Vorzyklus-Merker der Nachtband-Kante (Review-P1.4): nur ein
        // Nachtzyklus, dessen positiven Bedarf AUSSCHLIESSLICH das
        // Nachtband genullt hat (Binding exakt "nightDeadband",
        // insulinReq > 0), berechtigt die Kante zum Rearm-Anker.
        lastNightSuppressedU =
            if (state.nightWindow && decision.bindingLimit == "nightDeadband" &&
                (decision.insulinReqU ?: 0.0) > 0.0
            ) decision.insulinReqU ?: 0.0 else 0.0

        // ---- Pruefauftrag 2: die Down-Zeilen bis zur ENDMENGE -------------
        //
        // Der 14:10-Livefall: produktiv gingen 0,10 U hinaus (Kandidat +
        // Sub-Step-Uebertrag), die Zeile sah nur ihren 0,05er-Kandidaten und
        // meldete avoided = 0. Hier laeuft die geteilte gesenkte Lane durch
        // DENSELBEN Sub-Step (eigener Uebertrag, identische Verwerfensregeln)
        // und DIESELBE Wirkungspruefung - verglichen wird gegen die
        // tatsaechlich publizierte Menge dieses Zyklus.
        val turnShadowEnrichNs = System.nanoTime()
        val downVariantsFinal = run {
            if (downVariants.isEmpty()) return@run downVariants
            val produktivEndU = decision.smbU
            val lane = shadowDownLaneDecision
            val laneEndU = if (lane == null) null else {
                val laneStep = SubStepAccumulator.step(
                    carriedU = shadowDownCarryU,
                    desiredU = lane.desiredBeforeStepU,
                    steppedU = lane.smbU,
                    pumpIncrementU = bolusStep,
                    discard = subStepDiscard,
                )
                shadowDownCarryU = laneStep.carryU
                val roh = lane.smbU + laneStep.releaseU
                if (roh > 0.0 && finalVeto(roh) != null) 0.0 else roh
            }
            downVariants.map { v ->
                when {
                    !v.triggered -> v.copy(endU = produktivEndU, avoidedEndU = 0.0)
                    laneEndU == null -> v // PREDICT_FAILED: benannte Luecke bleibt
                    else -> v.copy(
                        endU = laneEndU,
                        avoidedEndU = kotlin.math.max(0.0, produktivEndU - laneEndU),
                    )
                }
            }
        }
        turnShadowNs += System.nanoTime() - turnShadowEnrichNs
        val turnResponseShadow = TurnResponseShadow.Report(
            turnClassification,
            turnVariants,
            turnShadowNs / 1_000_000.0,
            downVariantsFinal,
        )
        observeDescentDeferred(
            episodes = episodes,
            nowTs = computeTs,
            maxPositivePerCycleU = state.maxSmbU,
            finalDecision = decision,
        )
        // Die Clearance-Verschiebung stand hier und ist nach oben gewandert -
        // vor den Entscheidungssnapshot des Fundaments (s. dort).

        val primeWindowOpen = mealMarkerActive && markerTs > 0 &&
            computeTs - maxOf(markerTs, episodes.primeWindowStartTs) < cfg.primeWindowMin * 60_000L &&
            computeTs - markerTs < PrimeRelease.WALL_CEILING_MIN * 60_000L

        // ---- 5 Kanal -------------------------------------------------------
        // Audit R95 NEU-05: die PROZESSIERTE Sicht inkl. konvertierter
        // Extended-Boli - erst damit ist der FAKE_EXTENDED-Vertrag der
        // TbrPolicy (nur lesen, nie ersetzen; C8-SMB-Sperre) erreichbar.
        // Gelesen wird sie OBEN vor der Basal-Grundregel - eine Sicht, ein
        // Zyklus.
        val combined = FuseTbrTranslator.combine(
            decision = decision,
            current = currentTbr,
            latchZeroOnly = zeroLatchUebersteuert,
            scheduledBasalUPerH = profile.getBasal(computeTs),
            cfg = TbrPolicy.Config(
                basalStepUPerH = pumpe.basalStepUPerH,
                // Toni 15.08.: die Null sofort verlassen, sobald ihr Grund weg
                // ist - sonst baut sich zuviel negatives Basal auf, und das
                // finanziert ueber die Bedarfsseite die Morgen-SMBs.
                endZeroWhenReasonGone = cfg.endZeroWhenReasonGone,
            ),
            fault = if (tempBasalFallback) TbrPolicy.FaultCode.TEMP_BASAL_FALLBACK else TbrPolicy.FaultCode.NONE,
            pumpBusy = pumpBusy(),
            // Der Nachweis wird HIER gefuehrt, weil nur hier alles drei
            // bekannt ist: der Block dieses Zyklus, der Ledger-Hold (den der
            // Block nicht mittraegt) und das Rebound-Fenster.
            protectionCleared = FuseTbrTranslator.reasonGone(decision, ledgerView.hold, state.reboundWindow),
            endZeroAttempts = endZeroFehlversuche,
        )

        // BACKOFF FORTSCHREIBEN (Medtrum-Auflage). Der Erfolg eines Abbruchs
        // ist erst im NAECHSTEN Zyklus sichtbar - er steht in `current`, also
        // in der laufenden TBR, die AAPS uns liefert. Deshalb hier:
        //
        //  laeuft keine Null mehr  -> der Abbruch hat gewirkt, Zaehler zurueck
        //  Abbruch angefordert     -> hochzaehlen; wirkt er, faellt der
        //                             Zaehler im naechsten Zyklus von selbst
        //  sonst                   -> zuruecksetzen (die Lage hat sich
        //                             geaendert, ein alter Fehlversuch soll
        //                             den naechsten echten Anlauf nicht
        //                             blockieren)
        val laeuftNull = currentTbr?.let { TbrPolicy.isZeroRate(it.absoluteRateUPerH, pumpe.basalStepUPerH) } == true
        endZeroFehlversuche = when {
            !laeuftNull                                         -> 0
            combined.reason.contains(TbrPolicy.END_ZERO_REASON) -> endZeroFehlversuche + 1
            else                                                -> 0
        }

        // GATE-WIRKSAME Menge (Audit R95, Fix 3): Huellen und Bilanz belasten
        // nur, was nach TBR-Tabelle (smbBlocked) UND Pumpen-Gate wirklich
        // hinausgeht. Vorher zaehlte der Vor-Combine-Wert - ein blockierter
        // Zyklus belastete die Huelle, ohne dass eine Einheit floss
        // (Zaehlfalle rowId, 06.08.).
        val actuatedU = if (gate.allowed) combined.decision.smbU else 0.0
        val buchung = buche(
            episodes, actuatedU, primeWindowOpen, onset.active, mealMarkerActive, signal.sourceTs,
            evidenceEpisodeId = evidenceEpisodeId, computeTs = computeTs,
            deferredReleaseU = deferredReleaseU,
            manualBolusAfterMarkerU = manualBolusAfterMarkerU,
        )

        // RESERVIERT, NICHT ENDGUELTIG (11.08.). Oben ist gegen das PUMPEN-Gate
        // gebucht - das PUBLIKATIONS-Gate laeuft erst im Plugin und kann die
        // Menge noch entfernen (fehlende Vollsicht, Persistenzfehler,
        // Epochensperre). Bis dahin ist diese Belastung eine Reservierung.
        //
        // Sofort belasten und danach aufloesen, nicht umgekehrt: ein Absturz
        // zwischen hier und dem Gate laesst die Belastung stehen (konservativ),
        // waehrend eine nachgelagerte Buchung dann ganz entfiele - Budget frei,
        // Insulin draussen.
        episodes.pendingReservation =
            if (actuatedU > 0.0) app.aaps.fuse.plugin.ledger.EpisodeBudgets.Reservation(
                computeTs = computeTs,
                amountU = actuatedU,
                prime = primeWindowOpen,
                onset = onset.active,
                mealTs = if (buchung.mealGebucht) signal.sourceTs else 0L,
                foundationPhase = buchung.phase,
            ) else null
        val mealStats = mealStatsOf(episodes, markerTs, computeTs)
        // NACH `buche`, nicht davor: dort wird der Uebergang gelatcht und
        // Phase B belastet. Ein Snapshot davor zeigte den Stand VOR dem
        // eigenen Zyklus - im Export waere das eine stille Verschiebung um
        // eine Minute, und im Replay ein systematischer Versatz.
        val foundationSnapshot = MealFoundation.snapshot(
            episodes.foundation, computeTs, episodes.primeWindowStartTs,
            deliveredFromBudgetU = episodes.deliveredPhaseAU + episodes.deliveredSinceHandoverU,
            deliveredPhaseAU = episodes.deliveredPhaseAU,
            deliveredSinceHandoverU = episodes.deliveredSinceHandoverU,
            confirmedNotSentPhaseAU = episodes.confirmedNotSentPhaseAU,
            descentDeferredPhaseAU = episodes.descentDeferredPhaseAU,
            descentCarryEligibility = descentCarryEligibility,
            bolusStepU =pumpe.bolusStepU,
        )

        val computeDurationMs = dateUtil.now() - computeTs
        return Outcome(
            configGeneration = app.aaps.fuse.plugin.export.FuseStateJson.hashOf(cfg).orEmpty(),
            expectationSituation = ExpectationLedger.situationOf(
                mealMarkerActive = mealMarkerActive,
                evidenceEpisodeId = episodes.evidenceEpisodeId,
                // DIE PHASE, nicht die Episoden-ID (Toni 18.08.): eine offene
                // Episode in DORMANT ist Korrekturbetrieb, keine Mahlzeit.
                // `null` heisst "dieser Zyklus hat keine Phase gerechnet" und
                // ergibt EXCLUDED - die sichere Richtung.
                evidencePhase = evidenz?.phase,
                onsetActive = onset.active,
                mealWindow = mealWindow,
                reboundWindow = reboundWindow,
                signalHealthy = step.health == Health.READY,
                // Das Siegel kennt erst das Plugin - nach dem Gate.
                ledgerSealed = null,
            ),
            tbrChanged = tbrAktuation(combined.request, computeTs, profile, currentTbr, pumpe.basalStepUPerH),
            computeDurationMs = computeDurationMs,
            mealStats = mealStats,
            mealFoundation = foundationSnapshot,
            manualBolusAfterMarkerU = manualBolusAfterMarkerU,
            evidenceMayOverrideRebound = reboundOverrideErlaubt,
            reboundOverrideDeadlineTs = reboundOverrideDeadlineTs,
            reboundOverrideDenial = reboundOverrideDenial?.name,
            descentRiskActive = descentRisk.active,
            descentRiskDenial = descentRisk.denial,
            descentFallRatePerMin = descentRisk.fallRatePerMin,
            descentOvercoverageMgdl = descentRisk.overcoverageMgdl,
            descentMinutesToFloor = descentRisk.minutesToFloor,
            descentLatchActive = descentLatch.state.active,
            descentLatchReason = descentLatch.reason.name,
            descentRecoveryCycles = descentLatch.runtime.consecutiveRecoveryCycles,
            descentLatchedAtTs = descentLatch.state.latchedAtTs,
            deferredPrimeOpenU = episodes.deferredPrime.openU,
            deferredPrimePinnedForTs = episodes.deferredPrime.pinnedForMarkerTs,
            deferredPrimeDeadlineTs = episodes.deferredPrime.deadlineTs,
            deferredPrimeHorizonMin = episodes.deferredPrime.horizonMin,
            deferredPrimeWithheldU = deferredWithheldU,
            deferredPrimeReleasedU = deferredReleaseU,
            deferredPrimeDenial = deferredDenial,
            deferredPrimeLapseReason = episodes.deferredPrime.lastLapseReason?.name,
            deferredPrimeLapseU = episodes.deferredPrime.lastLapseU,
            deferredPrimeLapseTs = episodes.deferredPrime.lastLapseTs,
            livenessActive = livenessActive,
            livenessStreak = livenessStreak,
            livenessCandidateU = livenessCandidateU,
            livenessNeedU = livenessNeedU,
            livenessBaseRatio = livenessBaseRatio,
            livenessLiveRatio = livenessLiveRatio,
            livenessProfile = livenessProfil,
            livenessProfileReason = livenessProfilGrund,
            livenessSelectedRatioCap = livenessSelectedRatioCap,
            livenessSelectedIobCapPercent = livenessSelectedIobCapPct,
            livenessProfileIobLimitU = livenessProfileIobLimitU,
            livenessNormalSmbU = livenessNormalSmbU,
            livenessStaticCorrectionNeedU = livenessStaticCorrectionNeedU,
            livenessCoverageState = livenessCoverageState,
            livenessPressureActive = livenessPressureActive,
            markerPowerPinnedFor = episodes.markerPowerPinnedFor,
            markerPowerDeadlineTs = episodes.markerPowerDeadlineTs,
            zeroLatchActive = episodes.zeroLatch.active,
            zeroLatchSinceTs = episodes.zeroLatch.latchedAtTs,
            zeroLatchReason = zeroLatchGrund,
            zeroLatchCalmStreak = zeroCalmStreak,
            zeroLatchArmStreak = zeroArmStreak,
            correctionReversal = reversal,
            correctionRearm = rearm,
            correctionContext = korrekturKontext,
            correctionContextReason = kontextLage.reason.name,
            correctionMealBasis = kontextLage.mealBasis.name,
            zeroLatchOverrode = zeroLatchUebersteuert,
            livenessReleaseMeanMgdl = livenessReleaseMeanMgdl,
            livenessBgMinEffectiveMgdl = livenessBgMinEffective,
            livenessBgMinSource = livenessBgMinSource,
            livenessHeadroomU = livenessHeadroomU,
            livenessLiftU = livenessLiftU,
            livenessBinding = livenessBinding,
            livenessDenial = livenessDenial,
            livenessExit = livenessExit,
            livenessModelReject = livenessModelReject,
            livenessReArmUntilTs = episodes.livenessReArmUntilTs,
            preFoundationSmbU = preFoundationSmbU,
            preFoundationBlock = preFoundationBlock,
            preFoundationBindingLimit = preFoundationBindingLimit,
            foundationLiftU = foundationLiftU,
            phaseAUpfrontPendingU = upfrontPendingU,
            phaseAUpfrontRequestedU = upfrontRequestedU,
            phaseAUpfrontState = upfrontState(
                auth = episodes.foundation,
                phase = foundationDecision.phase,
                pendingU = upfrontPendingU,
                requestedU = upfrontRequestedU,
                batchDeferred = upfrontAufschubJetzt,
                awaitingRecovery = upfrontWartetAufErholung,
                zeroLatchBlocked = cfg.zeroLatchEnabled && episodes.zeroLatch.active,
                transferredNowU = upfrontTransferNowU,
                transferredTotalU = episodes.upfrontTransferredU,
                viewUnavailable = upfrontSichtUnlesbar,
                deferredPrimeEnabled = cfg.deferredPrimeEnabled,
            ),
            lowThreat = lowThreatResult,
            evidenceEpisodeId = evidenceEpisodeId,
            evidenceEpisodeDenial = episodeGate.denial?.name,
            evidenceCreditRevoked = episodeGate.creditRevoked,
            evidenceCommittedU = episodes.evidenceCommittedU,
            evidenceEpisodeMin = evidenceEpisodeMin,
            evidenceEpisodeCapMin = (evidenceCapMs / 60_000L).toInt(),

            evidencePhase = evidenz?.phase?.name,
            evidenceStockMgdl = evidenz?.state?.stockMgdl,
            evidenceReason = evidenz?.noInflow?.name,
            evidenceCreditMgdlPerMin = evidenz?.creditMgdlPerMin,
            insulinModel = built.input.trajectory.model,
            decision = combined.decision,
            tbr = combined.request,
            prediction = prediction,
            restraint = restraint,
            turnResponseShadow = turnResponseShadow,
            trendRuleApplied = trendAngewendet,
            forecastShadowEnabled = forecastShadowEnabled,
            forecastShadowEpochTs = forecastShadowEpochTs,
            tailLowerUnconditionalMgdl = tailLowerUnconditional,
            tailLowerConditionalMgdl = tailLowerConditional,
            tailLowerMainUncondMgdl = tailLowerMainUncond,
            tailLowerMainCondMgdl = tailLowerMainCond,
            tailLowerRestraintUncondMgdl = tailLowerRestraintUncond,
            tailLowerRestraintCondMgdl = tailLowerRestraintCond,
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
            treatmentView = treatmentView,
        )
    }

    /**
     * DIE EPISODEN-BUCHFUEHRUNG, fuer BEIDE Pfade.
     *
     * Sie stand zweimal da - einmal im Hauptpfad, einmal im predictorfreien
     * Markerpfad - und die zweite Fassung war schon unvollstaendig, keinen Tag
     * alt: ihr fehlte der Onset-ABLAUF. Deshalb hier einmal, mit einem Namen.
     *
     * @return ob eine Mahlzeitenlieferung gebucht wurde.
     */
    /**
     * OB DIESER ZYKLUS DIE PUMPE ANDERS FAHREN LAESST - fuer den
     * Eingriffsstempel.
     *
     * @param bereitsGelesen die Sicht, die dieser Pfad schon fuer die
     *   ENTSCHEIDUNG benutzt hat. Sie wird durchgereicht statt neu gelesen:
     *   zwei Lesevorgaenge koennten sich mitten im Zyklus widersprechen (s.
     *   den Hinweis an `processedTbrEbData`). Pfade ohne eigene Sicht -
     *   Abbruch und Marker-Rueckfall - uebergeben `null` und lesen hier
     *   ihren einzigen.
     */
    private fun tbrAktuation(
        request: FuseController.TbrRequest?,
        computeTs: Long,
        profile: app.aaps.core.interfaces.profile.Profile?,
        bereitsGelesen: TbrPolicy.Current?,
        basalStepUPerH: Double,
    ): Boolean? {
        // Ohne Profil ist der Bezugspunkt unbekannt - `null` heisst hier
        // "nicht beurteilbar", und der Stempel wertet das als Eingriff.
        if (profile == null) return null
        val laufend = bereitsGelesen ?: processedTbrEbData
            .getTempBasalIncludingConvertedExtended(computeTs)
            ?.let {
                TbrPolicy.Current(
                    absoluteRateUPerH = it.convertedToAbsolute(computeTs, profile),
                    remainingMin = it.plannedRemainingMinutes,
                    sourceType = if (it.type == app.aaps.core.data.model.TB.Type.FAKE_EXTENDED)
                        TbrPolicy.SourceType.FAKE_EXTENDED else TbrPolicy.SourceType.TEMP_BASAL,
                )
            }
        return TbrActuation.changed(
            current = laufend?.let { TbrActuation.Current(it.absoluteRateUPerH, it.remainingMin) },
            requestRateUPerH = request?.rateUPerH,
            requestDurationMin = request?.durationMin,
            profileBasalUPerH = profile.getBasal(computeTs),
            basalStepUPerH = basalStepUPerH,
        )
    }

    /**
     * Vertrag 6+7 des Marker-Prime-Aufschubs: die GEPINNTE Huelle minus
     * allem, was seit dem Marker floss - Fundamentphasen, Nachfenster UND
     * manuelle NORMAL-Boli. Eine unlesbare Behandlungssicht ergibt 0
     * (fail-closed: dann oeffnet und liefert der Aufschub nichts).
     */
    private fun deferredHullRemainingU(
        episodes: app.aaps.fuse.plugin.ledger.EpisodeBudgets,
        manualBolusAfterMarkerU: Double?,
    ): Double {
        val f = episodes.foundation
        if (!f.valid) return 0.0
        val manuell = manualBolusAfterMarkerU?.takeIf { it.isFinite() && it >= 0.0 } ?: return 0.0
        val geliefert = episodes.deliveredPhaseAU + episodes.deliveredSinceHandoverU +
            episodes.postFoundationDeliveredU + manuell
        return kotlin.math.max(0.0, f.totalBudgetU - geliefert)
    }

    /**
     * Der ABGELEITETE Zustand des Phase-A-Sofortanteils - reine
     * Berichtsgroesse fuer Export und Viewer, keine Dosierlogik (die haengt
     * ausschliesslich an der upfrontFloorU-Bilanz). null = kein Sofortanteil
     * konfiguriert oder keine Autorisierung.
     */
    private fun upfrontState(
        auth: MealFoundation.Authorization,
        phase: MealFoundation.Phase,
        pendingU: Double,
        requestedU: Double,
        /** Steht der Batch im EIGENEN Aufschub (Merker gesetzt)? */
        batchDeferred: Boolean,
        /** Wartet er nach dem Aufschub noch auf die bestaetigte Erholung? */
        awaitingRecovery: Boolean,
        zeroLatchBlocked: Boolean,
        /** In DIESEM Zyklus in den schrittweisen Pfad ueberfuehrt [U]. */
        transferredNowU: Double = 0.0,
        /** Bereits frueher ueberfuehrt [U] - der Batch ist dann erledigt. */
        transferredTotalU: Double = 0.0,
        /** Behandlungssicht unlesbar - dann ist NICHTS bestimmbar. */
        viewUnavailable: Boolean = false,
        deferredPrimeEnabled: Boolean = true,
        fallback: Boolean = false,
    ): String? = when {
        !auth.valid || auth.phaseAUpfrontU <= 0.0 -> null
        // UNBESTIMMBAR VOR ALLEM ANDEREN (Review-Punkt 6): eine unlesbare
        // Behandlungssicht darf NIE als "gedeckt" erscheinen - dort ist
        // pendingU rechnerisch 0, aber das ist keine Aussage.
        viewUnavailable -> "BLOCKED_VIEW"
        // Der Buchwechsel am Phasenende - EINMAL, typisiert benannt.
        transferredNowU > 0.0 -> "TRANSFERRED_TO_DEFERRED"
        !deferredPrimeEnabled && pendingU > 0.0 -> "BLOCKED_NO_DEFERRED"
        fallback && pendingU > 0.0 -> "BLOCKED_FALLBACK"
        zeroLatchBlocked && pendingU > 0.0 -> "BLOCKED_ZERO_LATCH"
        requestedU > 0.0 -> "REQUESTED"
        // Nach einer Ueberfuehrung ist der Sofortanteil erledigt - er ist
        // nicht "gedeckt", sondern liegt im schrittweisen Pfad.
        transferredTotalU > 0.0 -> "TRANSFERRED_TO_DEFERRED"
        pendingU <= 0.0 -> "COVERED"
        // DER EIGENE ZUSTAND DES SOFORT-BATCHES (Nachtrag Toni 25.08.):
        // frueher stand hier das generische "DEFERRED" des linearen
        // Prime-Aufschubs - und weil der Batch dort auch tatsaechlich
        // gebucht wurde, rieselte er in Pumpenschritten heraus. Jetzt ist
        // er ein eigener, typisierter Zustand: die Menge bleibt
        // vollstaendig offen und wird nach bestaetigter Erholung in EINEM
        // Zug angefordert.
        batchDeferred || awaitingRecovery -> "DEFERRED_UPFRONT_BATCH"
        // Ausserhalb Phase A und nichts mehr zu ueberfuehren: der
        // Sofortanteil ist abgelaufen.
        phase != MealFoundation.Phase.PHASE_A -> "EXPIRED"
        else -> "PLANNED"
    }

    private fun buche(
        episodes: app.aaps.fuse.plugin.ledger.EpisodeBudgets,
        actuatedU: Double,
        primeWindowOpen: Boolean,
        onsetActive: Boolean,
        mealMarkerActive: Boolean,
        sourceTs: Long,
        /** Identitaet der laufenden Mahlzeitenepisode; 0 = keine. */
        evidenceEpisodeId: Long,
        /** Der Zyklus - entscheidet ueber die Fundament-Phase. */
        computeTs: Long,
        /** In DIESEM Zyklus als Aufschub-Freigabe angehobene Menge. */
        deferredReleaseU: Double = 0.0,
        /** Fuer die Huellenrechnung des Aufschubs - s. [deferredHullRemainingU]. */
        manualBolusAfterMarkerU: Double? = null,
    ): Buchung {
        if (primeWindowOpen) episodes.primeSpentU += actuatedU

        // ---- Mahlzeitenfundament (Punkt 7) -------------------------------
        //
        // ZUERST LATCHEN, DANN EINORDNEN. Ist der Uebergabezeitpunkt erreicht,
        // wird er JETZT festgeschrieben - eine spaetere CLEARANCE darf ihn
        // nicht mehr verschieben, sonst wanderte er hinter bereits als Phase B
        // gebuchte Mengen zurueck.
        episodes.foundation = episodes.foundation.latchIfDue(computeTs, episodes.primeWindowStartTs)
        val phase = MealFoundation.phaseOf(
            episodes.foundation, computeTs, episodes.primeWindowStartTs,
        )
        // MINDESTVERSORGUNG: hier zaehlt ALLES, was seit der Uebergabe floss -
        // auch eine gewoehnliche Korrektur. Phase B legt nichts obendrauf, sie
        // fuellt nur auf. Wuerde hier nur das Fundament selbst gezaehlt,
        // entstuende genau der additive Bolus, den dieser Baustein vermeidet.
        if (phase == MealFoundation.Phase.PHASE_B)
            episodes.deliveredSinceHandoverU += actuatedU
        // UND DIE PHASE-A-SEITE, symmetrisch (Codex 19.08.). Auch hier zaehlt
        // ALLES, was in dieser Phase floss - dieselbe Mindestversorgungs-
        // Semantik. Zusammen ergeben die beiden, was aus DIESEM Budget
        // geflossen ist; `evidenceCommittedU` kann das nicht sagen, weil er
        // eine andere Lebensdauer hat (s. [EpisodeBudgets.deliveredPhaseAU]).
        if (phase == MealFoundation.Phase.PHASE_A)
            episodes.deliveredPhaseAU += actuatedU

        // ---- Punkt 6: derselbe offene Gesamtbetrag (Vertraege 6+7) -------
        // JEDE Lieferung unter einem gepinnten Aufschub zehrt von der
        // gepinnten Huelle: Nachfenster-Mengen werden gezaehlt, ein
        // publizierter Freigabeschritt wird abgebucht, und danach wird der
        // offene Rest an die geschrumpfte Huelle geklemmt - ein normaler SMB
        // verkleinert den Aufschub damit genauso wie die Freigabe selbst.
        if (episodes.deferredPrime.pinnedForMarkerTs > 0L && actuatedU > 0.0) {
            if (phase != MealFoundation.Phase.PHASE_A && phase != MealFoundation.Phase.PHASE_B)
                episodes.postFoundationDeliveredU += actuatedU
            episodes.deferredPrime = DeferredPrime.consume(
                episodes.deferredPrime, kotlin.math.min(actuatedU, deferredReleaseU),
            )
            episodes.deferredPrime = DeferredPrime.clampToHull(
                episodes.deferredPrime,
                deferredHullRemainingU(episodes, manualBolusAfterMarkerU),
            )
        }

        // DER EVIDENZ-ZAEHLER: kumulativ ueber die GANZE Episode, alle
        // Kanaele, und bei Episodenwechsel zurueck auf 0. Er ist die
        // Bezahlseite des Stoerungsbestands - was hier fehlt, laesst dort
        // Bestand stehen, der schon abgetragen ist.
        if (evidenceEpisodeId > 0L) {
            if (episodes.evidenceEpisodeId != evidenceEpisodeId) {
                episodes.evidenceEpisodeId = evidenceEpisodeId
                episodes.evidenceCommittedU = 0.0
            }
            episodes.evidenceCommittedU += actuatedU
        }

        // Verbraucht wird nur, was der offene Kanal freigegeben hat; nach
        // REARM_QUIET_MIN geschlossenen Minuten wird die Huelle neu bewaffnet.
        if (onsetActive) {
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
        val mealGebucht = mealMarkerActive && actuatedU > 0.0
        if (mealGebucht) {
            episodes.mealDeliveries.addLast(
                app.aaps.fuse.plugin.ledger.EpisodeBudgets.MealDelivery(sourceTs, actuatedU),
            )
            while (episodes.mealDeliveries.size > 400) episodes.mealDeliveries.removeFirst()
        }
        return Buchung(mealGebucht, phase)
    }

    /**
     * Was die Buchung dieses Zyklus festgehalten hat.
     *
     * Die Phase geht MIT in die Reservierung, statt beim Aufloesen neu
     * abgeleitet zu werden: der Uebergabeanker kann sich dazwischen bewegt
     * haben (eine CLEARANCE verschiebt ihn, bis er gelatcht ist), und eine
     * Neuableitung drehte dann womoeglich einen anderen Zaehler zurueck als
     * den belasteten.
     */
    private data class Buchung(
        val mealGebucht: Boolean,
        val phase: MealFoundation.Phase,
    )

    /**
     * Fortschreibung des Sicherheitsaufschubs an genau EINER Stelle fuer
     * Haupt- und Fallbackpfad. Entscheidend ist der finale Block nach allen
     * Lifts und Floors; ein frueher Zwischenstand koennte spaeter wieder
     * angehoben werden und wuerde dann eine nicht existente Luecke buchen.
     */
    private fun observeDescentDeferred(
        episodes: app.aaps.fuse.plugin.ledger.EpisodeBudgets,
        nowTs: Long,
        maxPositivePerCycleU: Double,
        finalDecision: FuseController.Decision,
    ) {
        val auth = episodes.foundation
        if (!auth.valid) return
        val phase = MealFoundation.phaseOf(auth, nowTs, episodes.primeWindowStartTs)
        episodes.descentDeferredPhaseAU = DescentDeferredCarry.observe(
            currentU = episodes.descentDeferredPhaseAU,
            phase = phase,
            blockedByMeasuredSafety = DescentDeferredCarry.isDeferrableBlock(finalDecision.block),
            phaseABudgetU = auth.phaseABudgetU,
            deliveredPhaseAU = episodes.deliveredPhaseAU,
            nowTs = nowTs,
            handoverTs = auth.effectiveHandoverTs(episodes.primeWindowStartTs),
            // Optimistische Restkapazitaet: damit wird nur gespeichert, was
            // selbst unter maximaler Kadenz nicht mehr vor Phase B passt.
            maxPositivePerCycleU = maxPositivePerCycleU,
        )
    }

    /**
     * DER PREDICTORFREIE MARKERPFAD.
     *
     * Er entsteht nur, wenn [MarkerFallback] ihn erlaubt - also bei genau zwei
     * Reichweiten-Gruenden, mit lebender Autorisierung, gemessenem Tief und
     * READY-Signal. Was er tut, ist bewusst wenig:
     *
     *   Plan (Huelle, Fenster, Pumpenschritt)  ->  PrimeRelease.plan
     *   Menge (maxSmb, iobTH, maxIOB, Onset,
     *          Transportmenge, Rasterung)      ->  PrimeRelease.lift
     *   Ledger-Hold                            ->  LedgerHoldGate
     *   TBR und SMB-Sperren                    ->  FuseTbrTranslator
     *   Pumpen-Gate                            ->  gate.allowed
     *   Publikations-Gate und AAPS-Constraints ->  spaeter, im Plugin
     *
     * ALLES DAVON SIND DIESELBEN AUFRUFE wie im Hauptpfad. Der Pfad
     * dupliziert keine einzige Mengenentscheidung - er laesst die Bahn weg
     * und nichts sonst. Das ist Absicht: eine zweite, eigene Rechnung waere
     * genau die Art von Parallelspur, die hier schon zweimal auseinander
     * gelaufen ist.
     *
     * WAS ER NICHT HAT und auch nicht haben darf: Kandidatensuche,
     * Guard-Riegel, Schwanzhaftung, Rest-Zaehler. Alle vier brauchen eine
     * Bahn. Sie fehlen hier nicht aus Bequemlichkeit, sondern weil es sie in
     * dieser Lage nicht gibt - und ein geschaetzter Ersatz waere eine
     * Behauptung ueber eine Zukunft, die FUSE gerade nicht berechnen kann.
     *
     * Der Rest-Zaehler wird dabei VERWORFEN, nicht mitgenommen: aufgeschobene
     * Absicht gehoert zu dem Kontext, in dem sie entstand.
     */
    @Suppress("LongParameterList")
    private fun markerFallbackCycle(
        rejected: PredictorOutcome.Rejected,
        warum: String,
        signal: FuseSignalSource.Signal,
        step: ObserverStep,
        cfg: Config,
        state: FuseController.State,
        profile: Profile,
        pumpe: FuseActivePump,
        tempBasalFallback: Boolean,
        computeTs: Long,
        markerTs: Long,
        mealMarkerActive: Boolean,
        /** Nur fuer die parallele Schutz-Null und den Grundtext. Die
         *  Autorisierung haengt NICHT daran. */
        measuredLow: Boolean,
        /**
         * DAS GEMESSENE ABWAERTSRISIKO DIESES ZYKLUS - DURCHGEREICHT, nicht
         * hier neu gerechnet (Toni 19.08.).
         *
         * Zwei Rechnungen mit denselben Eingaben driften, sobald eine von
         * beiden eine Quelle anders waehlt - dieselbe Falle wie bei der
         * SMB-Zuordnung und beim Fundament-Zaehler. EIN Ergebnis je Zyklus,
         * beide Pfade lesen es.
         */
        descentRisk: LowThreatGate.DescentRisk,
        /** Dasselbe Hysterese-Ergebnis wie im Hauptpfad; nie hier neu
         *  berechnen, sonst koennen Haupt- und Fallbackpfad anders oeffnen. */
        descentLatch: DescentRecoveryLatch.Result,
        descentCarryEligibility: DescentDeferredCarry.Eligibility,
        manualBolusAfterMarkerU: Double?,
        treatmentView: TreatmentView?,
        /** Identitaet der Mahlzeitenepisode fuer den Evidenz-Zaehler; 0 = keine. */
        evidenceEpisodeId: Long,
        /** Warum keine eroeffnet wurde - s. [Outcome.evidenceEpisodeDenial]. */
        evidenceEpisodeDenial: String?,
        /** Zusatzkredit zurueckgenommen - s. [Outcome.evidenceCreditRevoked]. */
        evidenceCreditRevoked: Boolean,
        /** Das fertige EvidenceStock-Ergebnis dieses Zyklus - der Kern ist an
         *  der Aufrufstelle bereits gelaufen und versiegelt worden. Ohne die
         *  Felder log der Trail: er behauptete NICHT_AUSGEWERTET genau in den
         *  Fallback-DOSIER-Zyklen (Befund 15.08.). */
        evidenz: EvidenceStock.Result?,
        isf: Double,
        target: Double,
        targetSource: String,
        iobTotal: app.aaps.core.interfaces.aps.IobTotal,
        maxIobU: Double,
        transportModelledU: Double,
        ledgerView: app.aaps.fuse.plugin.ledger.LedgerView,
        episodes: app.aaps.fuse.plugin.ledger.EpisodeBudgets,
        onset: OnsetChannel.Result,
        band: PairSlopeBand.Estimate?,
        discount: DriveDiscount.Applied?,
        insulinModel: InsulinModelProvenance,
        sensorEpoch: Long?,
        calibrationEpoch: Long?,
        gate: FusePumpGate.Result,
    ): Outcome {
        subStepCarryU = 0.0
        // Codex 22.08.: ein Fallback-Zyklus laeuft OHNE die Kanalstufe -
        // weder Riegel noch Druck sind geprueft. Ein aktiver Liveness-Lauf
        // endet deshalb hier wie an einem harten Riegel.
        val livenessLostExit = livenessObservationLost(episodes, computeTs, cfg.livenessReArmMin)

        // Dieselbe Episoden-Wahl wie im Hauptpfad - hier lokal gelesen, weil
        // dieser Pfad seine Eingaben als Parameter bekommt.
        val markerNoPrime = mealMarkerActive && preferences.get(FuseLongKey.MealMarkerNoPrime) != 0L

        val primePlan = PrimeRelease.plan(
            PrimeRelease.Input(
                enabled = cfg.primeReleaseEnabled,
                windowMin = cfg.primeWindowMin,
                declinedByUser = markerNoPrime,
                mealMarkerActive = mealMarkerActive,
                armedTsMs = markerTs,
                windowStartTsMs = episodes.primeWindowStartTs,
                nowMs = computeTs,
                // DIESELBE gepinnte Groesse wie im Hauptpfad - der Fallback
                // fuehrt keine eigene Rechnung.
                envelopeU = MealFoundation.primeBudgetU(episodes.foundation, cfg.primeEnvelopeU),
                spentU = episodes.primeSpentU,
                // KEINE BAHN, und das steht als `null` da statt als Zahl. Die
                // Freigangsprobe entfaellt hier ohnehin (markerAuthorized),
                // sie haette also nichts zu pruefen gehabt.
                safetyMinLowerMgdl = null,
                guardFloorMgdl = cfg.guardFloorMgdl,
                isfMgdlPerU = isf,
                pumpIncrementU = pumpe.bolusStepU,
                markerAuthorized = true,
            )
        )

        // DIE BASIS SAGT, WAS GEMESSEN IST - und nur das. Liegt ein Tief vor,
        // ist es SAFETY_HOLD mit ZERO_TEMP: die Basalabsenkung ist kein
        // Beiwerk, sondern der Schutz, der neben der erklaerten Mahlzeit
        // weiterlaeuft. Liegt KEINS vor, waere ein Schutz-Null eine
        // Zurueckhaltung ohne Anlass - dann bleibt die laufende Rate stehen.
        //
        // predAtRelease und minLower bleiben in beiden Faellen null: es gibt
        // keine Bahn, und eine erfundene Zahl waere im Export von einer
        // gerechneten nicht mehr zu unterscheiden.
        val basis = FuseController.Decision(
            smbU = 0.0,
            tbr = if (measuredLow || (cfg.zeroLatchEnabled && episodes.zeroLatch.active))
                FuseController.TbrAction.ZERO_TEMP
            else FuseController.TbrAction.KEEP_CURRENT,
            block = if (measuredLow) FuseController.Block.SAFETY_HOLD
            else FuseController.Block.NONE,
            insulinReqU = 0.0,
            predAtReleaseMgdl = null,
            minLowerMgdl = null,
            bindingLimit = "markerFallback",
        )
        // ---- PHASE-A-SOFORTANTEIL: im FALLBACK KEIN LIFT (Toni) -----------
        //
        // SICHERHEITSAUFLAGE: eine Mehr-Einheiten-Sofortdosis gehoert nicht
        // auf den predictorfreien Technik-Pfad - ohne Bahn gibt es keine
        // Wirkungspruefung, die eine 3-U-Dosis tragen koennte. Und WIRKLICH
        // VERLUSTFREI (Tonis Review): der offene Betrag wird HIER in den
        // Aufschub gebucht - dauert der Modellausfall bis nach Phase A,
        // waere der blosse Boden sonst als WINDOW_OVER verfallen. Der
        // kleine lineare Prime-Anteil laeuft wie bisher.
        val upfrontPhase = MealFoundation.phaseOf(
            episodes.foundation, computeTs, episodes.primeWindowStartTs,
        )
        val offenImFallbackRoh = MealFoundation.remainingUpfrontU(
            auth = episodes.foundation,
            deliveredPhaseAU = episodes.deliveredPhaseAU,
            manualAfterMarkerU = manualBolusAfterMarkerU,
            deliveredSinceHandoverU = episodes.deliveredSinceHandoverU,
            postFoundationDeliveredU = episodes.postFoundationDeliveredU,
            transferredToDeferredU = episodes.upfrontTransferredU,
        )
        val fallbackSichtUnlesbar = offenImFallbackRoh == null
        val offenImFallbackU = offenImFallbackRoh ?: 0.0
        // AUCH IM FALLBACK endet der Sofortanteil mit Phase A: was dann
        // offen ist, geht EINMAL in den schrittweisen Pfad ueber. Ein
        // Modellausfall bis hinter T+20 verliert die Menge damit nicht -
        // sie kommt nur gebremst (Review 25.08. abends, Punkt 2).
        var fallbackTransferNowU = 0.0
        if (upfrontPhase != MealFoundation.Phase.PHASE_A && offenImFallbackU > 0.0 &&
            !fallbackSichtUnlesbar && episodes.foundation.valid &&
            episodes.deferredPrime.pinnedForMarkerTs == episodes.foundation.armedTs
        ) {
            val vorher = episodes.deferredPrime.openU
            episodes.deferredPrime = DeferredPrime.withhold(
                episodes.deferredPrime, offenImFallbackU,
                deferredHullRemainingU(episodes, manualBolusAfterMarkerU),
            )
            fallbackTransferNowU = (episodes.deferredPrime.openU - vorher).coerceAtLeast(0.0)
            episodes.upfrontTransferredU += offenImFallbackU
            episodes.upfrontBatchDeferredSince = 0L
        }
        // Der Punkt-6-PIN liegt im Hauptpfad NACH der Fallback-Weiche - ein
        // Druck mitten im Modellausfall waere sonst nie gepinnt und die
        // Verschiebung unten ein No-op. DIESELBE Identitaetsdisziplin wie
        // dort: nur ein in DIESEM Prozess beobachteter Druck pinnt.
        if (cfg.deferredPrimeEnabled && markerTs > 0L && markerTs <= computeTs &&
            markerPressObserved() == markerTs &&
            episodes.deferredPrime.pinnedForMarkerTs != markerTs
        ) {
            episodes.deferredPrime = DeferredPrime.pin(
                episodes.deferredPrime, markerTs,
                horizonMin = cfg.markerPrimeDescentHorizonMin.toInt(),
                endMin = cfg.deferredPrimeEndMin,
            )
            episodes.postFoundationDeliveredU = 0.0
        }
        // VERLUSTFREI OHNE ZWEITES BUCH: der Modellausfall ist ein
        // Aufschubgrund wie jedes gemessene Risiko - er setzt den Merker,
        // die Menge bleibt ueber die Bilanz offen. Frueher wurde sie hier
        // in den DeferredPrime gebucht; genau diese zweite Buchfuehrung
        // lief mit der Bilanz auseinander.
        if (offenImFallbackU > 0.0 &&
            upfrontPhase == MealFoundation.Phase.PHASE_A &&
            episodes.upfrontBatchDeferredSince <= 0L
        ) episodes.upfrontBatchDeferredSince = computeTs
        val upfrontPendingU = if (fallbackTransferNowU > 0.0) 0.0 else offenImFallbackU
        val upfrontRequestedU = 0.0
        val liftedPrime = PrimeRelease.lift(
            basis, primePlan, state,
            markerAuthorized = true,
            // Kein Schwanz-Headroom: es gibt keine Bahn, aus der einer
            // entstehen koennte. Der Onset-Deckel und die Transportmenge sind
            // dagegen Mengen und gelten unveraendert - letztere ist Tonis
            // ausdrueckliche Auflage zu PENDING_MODEL_TOO_SHORT.
            tailHeadroomU = null,
            onsetCapU = if (onset.active) onset.remainingU else null,
            transportCommitmentU = transportModelledU,
        )

        // ---- PHASE B, auch hier - DIESELBE Logik (Toni 19.08.) ------------
        //
        // Der Fallback fuehrt keine eigene Rechnung: derselbe Aufruf, derselbe
        // Mengenkern. Genau so laufen zwei Pfade sonst auseinander, und bei
        // der Buchfuehrung ist das hier schon einmal passiert.
        //
        // Die CLEARANCE-Verschiebung gibt es auf diesem Pfad nicht - er
        // wertet `primePlan.reason` nirgends aus. Der Uebergabeanker sieht
        // damit den unveraenderten Fensterstart, und das ist richtig: was
        // nicht verschoben wurde, darf auch nicht als verschoben gelten.
        //
        // Kein Schwanz-Headroom, aus demselben Grund wie beim Prime-Lift
        // darueber: ohne Bahn gibt es keinen.
        val lifted = MealFoundation.lift(
            base = liftedPrime,
            snapshot = MealFoundation.snapshot(
                episodes.foundation, computeTs, episodes.primeWindowStartTs,
                deliveredFromBudgetU = episodes.deliveredPhaseAU + episodes.deliveredSinceHandoverU,
                deliveredPhaseAU = episodes.deliveredPhaseAU,
                deliveredSinceHandoverU = episodes.deliveredSinceHandoverU,
                confirmedNotSentPhaseAU = episodes.confirmedNotSentPhaseAU,
                descentDeferredPhaseAU = episodes.descentDeferredPhaseAU,
                descentCarryEligibility = descentCarryEligibility,
                bolusStepU = pumpe.bolusStepU,
            ),
            state = state,
            tailHeadroomU = null,
            transportCommitmentU = transportModelledU,
        )
        // UND DIE MESSUNG AUCH HIER (Codex 19.08.). Der Fallback ruft
        // denselben Lift, hat die beiden Groessen aber nicht gesetzt - der
        // Trail meldete auf diesem Pfad also 0/0, obwohl das Fundament sehr
        // wohl angehoben haben kann. Genau die Sorte stiller Luecke, die eine
        // spaetere Auswertung falsch macht, ohne dass etwas rot wird.
        val preFoundationSmbU = liftedPrime.smbU
        val preFoundationBlock = liftedPrime.block
        val preFoundationBindingLimit = liftedPrime.bindingLimit
        val foundationLiftU = kotlin.math.max(0.0, lifted.smbU - liftedPrime.smbU)
        val held = LedgerHoldGate.apply(lifted, ledgerView.hold)
        // DERSELBE ENDRIEGEL AUCH HIER. Der Fallback ist ein zweiter Weg
        // zur Menge und war beim Mahlzeitenfundament schon einmal die
        // Stelle, an der eine Messung fehlte - ein Riegel, den nur der
        // Hauptpfad kennt, ist kein Riegel.
        val heldMitRiegel = MeasuredDescentGate.apply(held, descentLatch.blocksPositive)
        observeDescentDeferred(
            episodes = episodes,
            nowTs = computeTs,
            maxPositivePerCycleU = state.maxSmbU,
            finalDecision = heldMitRiegel,
        )

        val runningTbr = processedTbrEbData.getTempBasalIncludingConvertedExtended(computeTs)
        val combined = FuseTbrTranslator.combine(
            decision = heldMitRiegel,
            current = runningTbr?.let {
                TbrPolicy.Current(
                    absoluteRateUPerH = it.convertedToAbsolute(computeTs, profile),
                    remainingMin = it.plannedRemainingMinutes,
                    sourceType = if (it.type == app.aaps.core.data.model.TB.Type.FAKE_EXTENDED) TbrPolicy.SourceType.FAKE_EXTENDED
                    else TbrPolicy.SourceType.TEMP_BASAL,
                )
            },
            scheduledBasalUPerH = profile.getBasal(computeTs),
            // KEIN Nachweis auf dem predictorfreien Markerpfad, und das ist
            // keine Vergesslichkeit: ohne Bahn ist "der Schutzgrund ist
            // nachweislich weg" nicht nachweisbar - er bliebe eine Behauptung.
            cfg = TbrPolicy.Config(basalStepUPerH = pumpe.basalStepUPerH),
            fault = if (tempBasalFallback) TbrPolicy.FaultCode.TEMP_BASAL_FALLBACK else TbrPolicy.FaultCode.NONE,
            pumpBusy = pumpBusy(),
        )

        // DIESELBE Buchfuehrung wie im Hauptpfad - und seit dem 11.08. ist das
        // keine Behauptung mehr, sondern DERSELBE Aufruf. Vorher stand hier eine
        // Kopie, der der Onset-ABLAUF fehlte (onsetQuietMin hochzaehlen und nach
        // REARM_QUIET_MIN neu bewaffnen). Die Fehlrichtung war konservativ - ein
        // Onset-Budget blieb laenger verbraucht -, aber "dieselbe Buchfuehrung"
        // war schlicht falsch, und genau so laufen zwei Pfade auseinander.
        val actuatedU = if (gate.allowed) combined.decision.smbU else 0.0
        val primeWindowOpen = mealMarkerActive && markerTs > 0 &&
            computeTs - maxOf(markerTs, episodes.primeWindowStartTs) < cfg.primeWindowMin * 60_000L &&
            computeTs - markerTs < PrimeRelease.WALL_CEILING_MIN * 60_000L
        val buchung = buche(
            episodes, actuatedU, primeWindowOpen, onset.active, mealMarkerActive, signal.sourceTs,
            evidenceEpisodeId = evidenceEpisodeId, computeTs = computeTs,
            // OHNE diesen Parameter rechnete die Aufschub-Huelle fail-closed
            // 0 und clampToHull klemmte einen frisch verschobenen
            // Sofortanteil im selben Fallback-Zyklus wieder auf 0 - die
            // Menge waere still verloren gewesen (Baubefund beim
            // verlustfreien Fallback, praeexistenter Mangel dieses Aufrufs).
            manualBolusAfterMarkerU = manualBolusAfterMarkerU,
        )
        episodes.pendingReservation =
            if (actuatedU > 0.0) app.aaps.fuse.plugin.ledger.EpisodeBudgets.Reservation(
                computeTs = computeTs,
                amountU = actuatedU,
                prime = primeWindowOpen,
                onset = onset.active,
                mealTs = if (buchung.mealGebucht) signal.sourceTs else 0L,
                foundationPhase = buchung.phase,
            ) else null

        return Outcome(
            decision = combined.decision,
            tbr = combined.request,
            tbrChanged = tbrAktuation(
                // Der Marker-Rueckfall hat keine eigene TBR-Sicht gelesen -
                // hier faellt der einzige Lesevorgang dieses Pfades an.
                combined.request, computeTs, profile, null, pumpe.basalStepUPerH,
            ),
            // KEINE BAHN im Export, auch keine leere: null heisst hier
            // "es gab keine", und genau das soll dort stehen.
            prediction = null,
            restraint = null,
            sourceTs = signal.sourceTs,
            computeTs = computeTs,
            health = step.health,
            gate = gate,
            reason = "$warum | MARKER_FALLBACK|${combined.reason}",
            evidenceEpisodeId = evidenceEpisodeId,
            evidenceEpisodeDenial = evidenceEpisodeDenial,
            evidenceCreditRevoked = evidenceCreditRevoked,
            evidenceCommittedU = episodes.evidenceCommittedU,
            evidenceEpisodeMin = evidenceEpisodeId.takeIf { it > 0L }?.let { ((computeTs - it) / 60_000L).toInt() },
            evidenceEpisodeCapMin = evidenceConfig.maxEpisodeMin,
            evidencePhase = evidenz?.phase?.name,
            evidenceStockMgdl = evidenz?.state?.stockMgdl,
            evidenceReason = evidenz?.noInflow?.name,
            evidenceCreditMgdlPerMin = evidenz?.creditMgdlPerMin,
            descentRiskActive = descentRisk.active,
            descentRiskDenial = descentRisk.denial,
            descentFallRatePerMin = descentRisk.fallRatePerMin,
            descentOvercoverageMgdl = descentRisk.overcoverageMgdl,
            descentMinutesToFloor = descentRisk.minutesToFloor,
            descentLatchActive = descentLatch.state.active,
            descentLatchReason = descentLatch.reason.name,
            descentRecoveryCycles = descentLatch.runtime.consecutiveRecoveryCycles,
            descentLatchedAtTs = descentLatch.state.latchedAtTs,
            // Punkt 6 laeuft nur im Hauptpfad; der Fallback exportiert den
            // ZUSTAND (restartfest), aber haelt und liefert selbst nichts.
            deferredPrimeOpenU = episodes.deferredPrime.openU,
            deferredPrimePinnedForTs = episodes.deferredPrime.pinnedForMarkerTs,
            deferredPrimeDeadlineTs = episodes.deferredPrime.deadlineTs,
            deferredPrimeHorizonMin = episodes.deferredPrime.horizonMin,
            deferredPrimeLapseReason = episodes.deferredPrime.lastLapseReason?.name,
            deferredPrimeLapseU = episodes.deferredPrime.lastLapseU,
            deferredPrimeLapseTs = episodes.deferredPrime.lastLapseTs,
            // Der Kanal laeuft nur im Hauptpfad - ein aktiver Lauf ist
            // oben beim Betreten dieses Pfads beendet worden; exportiert
            // werden der Exit und der restartfeste Stand.
            livenessExit = livenessLostExit,
            livenessReArmUntilTs = episodes.livenessReArmUntilTs,
            alarm = combined.alarm,
            bgMgdl = signal.q1,
            targetMgdl = target,
            targetSource = targetSource,
            signal = signal,
            band = band,
            discount = discount,
            onset = onset,
            prime = primePlan,
            candidate = null,
            candidateGap = null,
            policy = cfg,
            state = state,
            step = step,
            sensorEpoch = sensorEpoch,
            calibrationEpoch = calibrationEpoch,
            isfMgdlPerU = isf,
            iobU = iobTotal.iob,
            iobThU = state.iobThU,
            maxIobU = maxIobU,
            computeDurationMs = dateUtil.now() - computeTs,
            mealStats = mealStatsOf(episodes, markerTs, computeTs),
            // DIESELBE Sicht wie im Hauptpfad - der Fallback fuehrt keine
            // eigene Rechnung, sonst laufen die beiden Pfade genau so
            // auseinander wie schon einmal bei der Buchfuehrung.
            mealFoundation = MealFoundation.snapshot(
                episodes.foundation, computeTs, episodes.primeWindowStartTs,
                deliveredFromBudgetU = episodes.deliveredPhaseAU + episodes.deliveredSinceHandoverU,
                deliveredPhaseAU = episodes.deliveredPhaseAU,
                deliveredSinceHandoverU = episodes.deliveredSinceHandoverU,
                confirmedNotSentPhaseAU = episodes.confirmedNotSentPhaseAU,
                descentDeferredPhaseAU = episodes.descentDeferredPhaseAU,
                descentCarryEligibility = descentCarryEligibility,
                bolusStepU = pumpe.bolusStepU,
            ),
            manualBolusAfterMarkerU = manualBolusAfterMarkerU,
            preFoundationSmbU = preFoundationSmbU,
            preFoundationBlock = preFoundationBlock,
            preFoundationBindingLimit = preFoundationBindingLimit,
            foundationLiftU = foundationLiftU,
            phaseAUpfrontPendingU = upfrontPendingU,
            phaseAUpfrontRequestedU = upfrontRequestedU,
            phaseAUpfrontState = upfrontState(
                auth = episodes.foundation,
                phase = upfrontPhase,
                pendingU = upfrontPendingU,
                requestedU = upfrontRequestedU,
                batchDeferred = episodes.upfrontBatchDeferredSince > 0L,
                awaitingRecovery = false,
                zeroLatchBlocked = cfg.zeroLatchEnabled && episodes.zeroLatch.active,
                transferredNowU = fallbackTransferNowU,
                transferredTotalU = episodes.upfrontTransferredU,
                viewUnavailable = fallbackSichtUnlesbar,
                deferredPrimeEnabled = cfg.deferredPrimeEnabled,
                fallback = true,
            ),
            insulinModel = insulinModel,
            abortReason = null,
            predictorRejected = true,
            predictorReason = rejected.reason.name,
            markerFallbackUsed = true,
            treatmentView = treatmentView,
        )
    }

    /**
     * Die bestaetigte Nutzerentscheidung vom 21.08.: ein manueller
     * NORMAL-Bolus NACH dem Marker sperrt ausschliesslich den neu eingefuehrten
     * Sicherheitsuebertrag. Er wird nicht als Fundament-Lieferung umgedeutet
     * und veraendert das regulaere Phase-B-Teilbudget nicht.
     */
    internal fun manualBolusAfterMarkerU(
        auth: MealFoundation.Authorization,
        treatmentView: TreatmentView?,
    ): Double? {
        if (!auth.valid) return 0.0
        val view = treatmentView ?: return null
        val manual = view.boluses.filter {
            it.isValid && it.type == BS.Type.NORMAL && it.timestamp > auth.armedTs
        }
        if (manual.any { !it.amount.isFinite() || it.amount < 0.0 }) return null
        return manual.sumOf { it.amount }.takeIf { it.isFinite() && it >= 0.0 }
    }
    /** Fensteranfang der Behandlungssicht: DIA + Marge zurueck, zusaetzlich
     *  verlaengert bis vor den aeltesten Fakt einer noch offenen Ledger-Zeile.
     *  EINE Definition fuer Vollsicht UND Snapshot-Zeuge - zwei verschiedene
     *  Fensteranfaenge waeren zwei verschiedene Aussagen ueber dieselbe
     *  Datenbankabfrage. */
    private fun treatmentWindowStart(computeTs: Long, diaHours: Double): Long {
        val windowStart = computeTs - (diaHours * 3600_000.0).toLong() - IOB_MARGIN_MIN * 60_000L
        // L2: `oldestReconcilableTs`, NICHT `oldestOpenTs` - jede noch nicht
        // geprunte Zeile wird weiter abgeglichen und muss deshalb im Fenster
        // bleiben, auch eine bereits eingeloeste. Sonst meldet der Reducer den
        // Fakt als verschwunden, den nur die Abfrage nicht mehr liefert.
        return minOf(windowStart, (ledger.oldestReconcilableTs() ?: Long.MAX_VALUE - 60_000L) - 60_000L)
    }

    /**
     * DER ZEUGE DES INCLUSION-VERTRAGS (C3-02, Codex Fix-Pass-5-Closure G.3).
     *
     * Er wird gelesen, BEVOR dieser Zyklus IRGENDEINE IOB-Lesung macht -
     * insbesondere vor der Signalstufe, die den Eintrag fuer Punkt 0 selbst
     * schreibt (C3-02).
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

    /** Letztes q1 < REBOUND_LOW_MGDL. Wird nach einem Neustart aus dem Trail
     *  zurueckgeholt (s. [primeLastLowTs]) - der Verlust hat am 15.08. real
     *  zugeschlagen und den Rebound-Schutz elf Minuten nach einem Tief von 70
     *  geoeffnet. */
    private var lastLowTs = 0L

    /**
     * Das Tief-Gedaechtnis aus dem Trail uebernehmen - nur nach oben, nur
     * einmal wirksam.
     *
     * NUR NACH OBEN: ein rekonstruierter Zeitpunkt darf ein juengeres Tief aus
     * dem laufenden Prozess niemals ueberschreiben; das wuerde den Schutz
     * verkuerzen statt ihn zu retten. Ein aelterer Wert ist damit folgenlos,
     * und ein doppelter Aufruf ebenso.
     */
    fun primeLastLowTs(ts: Long) {
        if (ts > lastLowTs) lastLowTs = ts
    }

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
    /**
     * MEDTRUM-BACKOFF (Auflage aus dem V4b-Replay): wieviele Abbruchversuche
     * in Folge die Null NICHT beendet haben.
     *
     * Der Fall ist real, nicht theoretisch: scheitert das Pumpenkommando,
     * fordert FUSE es sonst in JEDEM Folgezyklus erneut an - im Rig 26 Mal in
     * 30 Minuten. Auf einer echten Medtrum ist das neuer Funkverkehr genau
     * dann, wenn die Pumpe ohnehin zickt. Nach `endZeroMaxAttempts` bleibt die
     * Null stehen und laeuft ab; die Fehlerrichtung ist damit die ALTE (zu
     * wenig Basal), nicht eine neue.
     *
     * PROZESSLOKAL wie der Sub-Step-Uebertrag: ein Neustart setzt zurueck, und
     * das ist die konservative Richtung (der naechste Versuch darf wieder).
     */
    private var endZeroFehlversuche = 0

    private var subStepCarryU = 0.0

    /** Punkt 6: zusammenhaengende gesunde Zyklen mit UKF >= der
     *  Latch-Erholungsrate seit dem letzten aktiven Marker-Horizont-Risiko.
     *  PROZESSLOKAL wie die Latch-Erholung: eine unbeobachtete Luecke oder
     *  ein Neustart belegt keine Erholung - die drei Zyklen werden neu
     *  verdient (konservativ: spaeter offen, nie frueher). */
    private var deferredRecoveryStreak = 0

    /** Pruefauftrag 2 (Toni 22.08.): eigener Sub-Step-Uebertrag der
     *  gesenkten Schatten-Lane. Prozesslokal wie der produktive Uebertrag;
     *  die Verwerfensregeln (subStepDiscard) gelten identisch. */
    private var shadowDownCarryU = 0.0

    /** Liveness-Kanal: PROZESSLOKALER Lauf-Zustand. Streak, aktiv und armTs
     *  sind bewusst NICHT restartfest - ein Neustart beendet den Lauf, und
     *  die Bewaffnung wird neu verdient (konservativ: spaeter offen, nie
     *  frueher). RESTARTFEST ist allein die Re-Arm-Sperre in
     *  `EpisodeBudgets.livenessReArmUntilTs` - "vorerst nicht wieder" ist
     *  eine Zusage, die ein Neustart nicht loeschen darf. */
    private var livenessStreak = 0
    private var livenessActive = false
    private var livenessArmTs = 0L

    /** Beginn des laufenden Bewaffnungs-Streaks - fuer die Frage, ob ein
     *  manueller Bolus WAEHREND der Bewaffnung fiel (Codex 22.08.). */
    private var livenessStreakStartTs = 0L

    /** Fingerprint ALLER drei Kanal-Stellgroessen (Schwelle, Kanaldeckel,
     *  Re-Arm-Zeit), unter denen Streak und Lauf gezaehlt wurden. null =
     *  noch nie gesehen (Prozessstart). JEDE Aenderung beendet einen
     *  aktiven Lauf und nullt den Streak (Toni + Codex 22.08.): ein Lauf,
     *  der unter einer anderen Regel bewaffnet wurde, traegt nicht weiter -
     *  das gilt fuer Deckel und Sperrzeit genauso wie fuer die Schwelle. */
    private var livenessCfgSeen: String? = null

    /** Zuletzt IM PROZESS gesehener Marker-Zeitstempel; -1 = noch nie
     *  gesehen (Warmstart-Anker: der erste Blick pinnt NICHT). */
    private var markerPowerLastSeenTs = -1L

    /** Der Pin, unter dem der aktuelle Streak/Lauf begann - ein
     *  Markerwechsel tauscht Caps nie still in einem alten Lauf (§5). */
    private var livenessRunPinnedFor = 0L

    /** ZERO-LATCH: Erholungsserie und Ruhe-Zaehler sind prozesslokal
     *  (Neustart = konservativ: Riegel bleibt, Zaehler neu); der Riegel
     *  selbst lebt restartfest in EpisodeBudgets.zeroLatch. */
    private var zeroLatchRuntime = DescentRecoveryLatch.Runtime()
    private var zeroCalmStreak = 0
    private var zeroCalmLastTs = 0L
    /** AUSLOESE-Zaehler des Fall-Verdikts (v29): zwei aufeinanderfolgende
     *  qualifizierende Zyklen zuenden, Unterbrechung nullt. Prozesslokal
     *  wie die Erholungs-Runtime - ein Neustart im Anlauf beginnt neu. */
    private var zeroArmStreak = 0
    private var zeroArmLastTs = 0L

    /** Korrekturpfad-Riegel (25.08.): prozesslokale Merker des
     *  V-Reversal-Schutzes und des Freigabe-Nachlaufs; die Fehlrichtung
     *  eines Neustarts ist "Riegel fehlt", nie "Riegel klemmt". */
    private var lastNightWindow = false

    /** Vorzyklus-Merker fuer die Nachtband-Kante (Review-P1.4): der
     *  bezifferten positive Bedarf, den AUSSCHLIESSLICH das Nachtband
     *  im letzten Nachtzyklus genullt hat. Prozesslokal - ein Neustart
     *  exakt auf der Kante verliert den Anker ("Riegel fehlt"). */
    private var lastNightSuppressedU = 0.0

    /** Eigener q1-Merker der Korrekturpfad-Riegel (Review-P0.3);
     *  zeroLatchLastQ1 gehoert der Latch-Stage und traegt an der
     *  Riegel-Stelle noch den Vorzykluswert. */
    private var korrLastQ1 = Double.NaN
    private var zeroLatchLastQ1 = Double.NaN

    /** Zeitstempel des letzten Zyklus, der die Kanalstufe erreicht hat -
     *  fuer die Taktluecken-Pruefung (Audit 22.08.): eine Luecke ohne
     *  Zyklus ist genauso unbeobachtet wie ein Abbruchzyklus. */
    private var livenessLastCycleTs = 0L

    /**
     * Ein Zyklus OHNE Kanalstufe (Abort, Marker-Fallback) kann weder die
     * gemessenen Riegel noch den Druck pruefen (Codex 22.08.). Ein aktiver
     * Lauf endet deshalb dort wie an einem harten Riegel - mit Sperre -,
     * und ein Bewaffnungs-Streak faellt auf null (eine unbeobachtete
     * Minute belegt keinen Druck; dieselbe Logik wie bei der
     * Latch-Erholung). Rueckgabe: der Exit-Grund fuer den Trail, sonst null.
     */
    private fun livenessObservationLost(
        episodes: app.aaps.fuse.plugin.ledger.EpisodeBudgets,
        nowTs: Long,
        reArmMin: Int?,
    ): String? {
        val lauf = livenessActive
        livenessActive = false
        livenessStreak = 0
        if (lauf) {
            // Fruehe Aborts kommen ohne geparste Config an (policy == null) -
            // dann gilt der KONFIGURIERTE Wert aus den Preferences, nicht
            // der Compile-Default (Audit 22.08.: sonst waere die Sperre bei
            // ReArmMin 60 stillschweigend sechsmal kuerzer als zugesagt).
            val minuten = reArmMin
                ?: runCatching { preferences.get(FuseIntKey.LivenessReArmMin) }.getOrNull()
                ?: FuseIntKey.LivenessReArmMin.defaultValue
            // maxOf: eine stehende Sperre ist eine Zusage - sie wird
            // verlaengert, nie verkuerzt.
            episodes.livenessReArmUntilTs = maxOf(
                episodes.livenessReArmUntilTs,
                nowTs + minuten * 60_000L,
            )
        }
        return if (lauf) "OBSERVATION_LOST" else null
    }

    /** Unter WELCHEN Bedingungen der Uebertrag entstanden ist - s. Aufrufstelle.
     *  `null` heisst: es gibt keinen Uebertrag, der eine Herkunft haette. */
    private var subStepCarryContext: String? = null

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
        subStepCarryContext = null
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
        /** Zulassungsschwelle des Low-Tors [mg/dl] - s. [FuseDoubleKey.LowGateMinBenefitMgdl]. */
        val lowGateMinBenefitMgdl: Double,
        /** Zero-TBR-Latch - s. FuseKeys.ZeroLatchEnabled. */
        val zeroLatchEnabled: Boolean,
        val zeroLatchCalmExitMin: Int,
        val zeroLatchCalmDistanceMgdl: Double,
        /** V-Reversal-Schutz im Korrekturkontext - s.
         *  [CorrectionReversalGuard] und FuseKeys (Default AUS). */
        val reversalGuardEnabled: Boolean,
        val reversalFallUkf: Double,
        val reversalLookbackMin: Int,
        val reversalReboundUkf: Double,
        val reversalConfirmCycles: Int,
        /** Freigabe-Nachlauf nach Zero-Latch-/Nachtende - s.
         *  [PositiveCorrectionRearm] (Default AUS). */
        val correctionRearmEnabled: Boolean,
        val rearmHoldMin: Int,
        val rearmConfirmCycles: Int,
        val rearmUpUkf: Double,
        /** Kurzfristfenster der Richtungsprobe [min] - s. [FuseDoubleKey.LowGateHorizonMin]. */
        val lowGateHorizonMin: Double,
        /** Akuter Horizont des harten positiven Endriegels [min]. */
        val positiveDescentHorizonMin: Double,
        val iobThPercent: Int,
        val releaseHorizonMin: Int,
        val liabilityHorizonMin: Int,
        val driveTauMin: Int,
        /** Fenster des erklaerten Absorptions-Kredits [min] - s. FuseKeys. */
        val absorptionCreditWindowMin: Int,
        /** Dauer der Marker-Sonderrechte ab Druck [min]; 0 = aus. */
        val markerBoostMaxMin: Int,
        val evidenceReboundOverrideMaxMin: Int,
        /** Nachtfenster [min ab Mitternacht] + Totband; Schalter getrennt. */
        val nightStartMin: Int,
        val nightEndMin: Int,
        val nightDeadbandMgdl: Double,
        val nightDeadbandEnabled: Boolean,
        val reboundDeadbandMgdl: Double,
        val reboundDeadbandEnabled: Boolean,
        val driveLowerQuantilePct: Int,
        /** Fenster des Theil-Sen-Hauptschaetzers [min] - s. FuseKeys. */
        val theilSenWindowMin: Int,
        val tailGuardEnabled: Boolean,
        val conditionalTailEnabled: Boolean,
        val markerAuthorized: Boolean,
        val tailFloorMgdl: Double,
        val tailRecoveryU: Double,
        val fastRestraintEnabled: Boolean,
        val bolusShareLambda: Double,
        val onsetChannelEnabled: Boolean,
        val onsetEnvelopeU: Double,
        val primeReleaseEnabled: Boolean,
        val primeEnvelopeU: Double,
        /** Ist das Mahlzeitenfundament eingeschaltet? Default aus. */
        val mealFoundationEnabled: Boolean,
        /** Anteil von Phase A am gepinnten Budget. 1,0 = heutiges Verhalten. */
        val mealFoundationPhaseAShare: Double,
        /** SOFORTANTEIL von Phase A (iLet-Prinzip). 0,0 = heutiges
         *  Verhalten, bitgleich. Gepinnt beim Armen - s. [MealFoundation]. */
        val mealFoundationPhaseAUpfrontShare: Double,
        /** Ende des Phase-B-Fensters [min ab Marker]. */
        val mealFoundationEndMin: Int,
        /** Punkt 6: Schalter (default aus), gepinnter Marker-Horizont und
         *  gepinnte Ablauffrist - s. [FuseBooleanKey.DeferredPrimeEnabled]. */
        val deferredPrimeEnabled: Boolean,
        val markerPrimeDescentHorizonMin: Double,
        val deferredPrimeEndMin: Int,
        /** Liveness-Kanal (Bauvertrag 22.08. nachts) - s.
         *  [FuseBooleanKey.LivenessChannelEnabled]. */
        val livenessChannelEnabled: Boolean,
        /** MEAL/CORRECTION (Bauauftrag 23.08. nachts) - s. FuseKeys.
         *  Werte sind bereits LESE-MIGRIERT (ungesetzt = alter Globalwert). */
        val livenessMealPowerMin: Int,
        val livenessMealRatioCap: Double,
        val livenessMealIobCapPercent: Double,
        val livenessCorrectionRatioCap: Double,
        val livenessCorrectionIobCapPercent: Double,
        val livenessBgMinDayMgdl: Double,
        val livenessBgMinNightMgdl: Double,
        val livenessReArmMin: Int,
        val primeWindowMin: Int,
        /** Die Null sofort verlassen, sobald ihr Schutzgrund weg ist. */
        val endZeroWhenReasonGone: Boolean,
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
        lowGateMinBenefitMgdl = preferences.get(FuseDoubleKey.LowGateMinBenefitMgdl),
        zeroLatchEnabled = preferences.get(FuseBooleanKey.ZeroLatchEnabled),
        zeroLatchCalmExitMin = preferences.get(FuseIntKey.ZeroLatchCalmExitMin),
        zeroLatchCalmDistanceMgdl = preferences.get(FuseDoubleKey.ZeroLatchCalmDistanceMgdl),
        reversalGuardEnabled = preferences.get(FuseBooleanKey.CorrectionReversalGuardEnabled),
        reversalFallUkf = preferences.get(FuseDoubleKey.ReversalFallUkf),
        reversalLookbackMin = preferences.get(FuseIntKey.ReversalLookbackMin),
        reversalReboundUkf = preferences.get(FuseDoubleKey.ReversalReboundUkf),
        reversalConfirmCycles = preferences.get(FuseIntKey.ReversalConfirmCycles),
        correctionRearmEnabled = preferences.get(FuseBooleanKey.PositiveCorrectionRearmEnabled),
        rearmHoldMin = preferences.get(FuseIntKey.RearmHoldMin),
        rearmConfirmCycles = preferences.get(FuseIntKey.RearmConfirmCycles),
        rearmUpUkf = preferences.get(FuseDoubleKey.RearmUpUkf),
        lowGateHorizonMin = preferences.get(FuseDoubleKey.LowGateHorizonMin),
        positiveDescentHorizonMin = preferences.get(FuseDoubleKey.PositiveDescentHorizonMin),
        iobThPercent = preferences.get(FuseIntKey.IobThPercent),
        releaseHorizonMin = preferences.get(FuseIntKey.ReleaseHorizonMin),
        liabilityHorizonMin = preferences.get(FuseIntKey.LiabilityHorizonMin),
        driveTauMin = preferences.get(FuseIntKey.DriveTauMin),
        absorptionCreditWindowMin = preferences.get(FuseIntKey.AbsorptionCreditWindowMin),
        markerBoostMaxMin = preferences.get(FuseIntKey.MarkerBoostMaxMin),
        evidenceReboundOverrideMaxMin = preferences.get(FuseIntKey.EvidenceReboundOverrideMaxMin),
        nightStartMin = preferences.get(FuseIntKey.NightStartMin),
        nightEndMin = preferences.get(FuseIntKey.NightEndMin),
        nightDeadbandMgdl = preferences.get(FuseDoubleKey.NightDeadbandMgdl),
        nightDeadbandEnabled = preferences.get(FuseBooleanKey.NightDeadbandEnabled),
        reboundDeadbandMgdl = preferences.get(FuseDoubleKey.ReboundDeadbandMgdl),
        reboundDeadbandEnabled = preferences.get(FuseBooleanKey.ReboundDeadbandEnabled),
        driveLowerQuantilePct = preferences.get(FuseIntKey.DriveLowerQuantilePct),
        theilSenWindowMin = preferences.get(FuseIntKey.TheilSenWindowMin),
        tailGuardEnabled = preferences.get(FuseBooleanKey.TailGuardEnabled),
        conditionalTailEnabled = preferences.get(FuseBooleanKey.ConditionalTailEnabled),
        markerAuthorized = preferences.get(FuseBooleanKey.MarkerAuthorisesRelease),
        tailFloorMgdl = preferences.get(FuseDoubleKey.TailFloorMgdl),
        tailRecoveryU = preferences.get(FuseDoubleKey.TailRecoveryU),
        fastRestraintEnabled = preferences.get(FuseBooleanKey.FastRestraintEnabled),
        bolusShareLambda = preferences.get(FuseDoubleKey.BolusShareLambda),
        onsetChannelEnabled = preferences.get(FuseBooleanKey.OnsetChannelEnabled),
        onsetEnvelopeU = preferences.get(FuseDoubleKey.OnsetEnvelopeU),
        primeReleaseEnabled = preferences.get(FuseBooleanKey.PrimeReleaseEnabled),
        primeEnvelopeU = preferences.get(FuseDoubleKey.PrimeEnvelopeU),
        mealFoundationEnabled = preferences.get(FuseBooleanKey.MealFoundationEnabled),
        mealFoundationPhaseAShare = preferences.get(FuseDoubleKey.MealFoundationPhaseAShare),
        mealFoundationPhaseAUpfrontShare = preferences.get(FuseDoubleKey.MealFoundationPhaseAUpfrontShare),
        mealFoundationEndMin = preferences.get(FuseIntKey.MealFoundationEndMin),
        deferredPrimeEnabled = preferences.get(FuseBooleanKey.DeferredPrimeEnabled),
        markerPrimeDescentHorizonMin = preferences.get(FuseDoubleKey.MarkerPrimeDescentHorizonMin),
        deferredPrimeEndMin = preferences.get(FuseIntKey.DeferredPrimeEndMin),
        livenessChannelEnabled = preferences.get(FuseBooleanKey.LivenessChannelEnabled),
        // MEAL/CORRECTION-LESE-MIGRATION (Bauauftrag §7): ungesetzte neue
        // Schluessel folgen dem bisherigen Globalwert - das Update ist
        // dosierneutral; die Grenzen-Klammer zaehlt Ausreisser als "nie
        // gesetzt" (dieselbe Regel wie bei der Nachtschwelle).
        livenessMealPowerMin = preferences.get(FuseIntKey.LivenessMealPowerMin),
        livenessMealRatioCap = preferences.getIfExists(FuseDoubleKey.LivenessMealRatioCap)
            ?.takeIf { it.isFinite() && it in FuseDoubleKey.LivenessMealRatioCap.min..FuseDoubleKey.LivenessMealRatioCap.max }
            ?: preferences.get(FuseDoubleKey.LivenessRatioCap),
        livenessMealIobCapPercent = preferences.getIfExists(FuseDoubleKey.LivenessMealIobCapPercent)
            ?.takeIf { it.isFinite() && it in FuseDoubleKey.LivenessMealIobCapPercent.min..FuseDoubleKey.LivenessMealIobCapPercent.max }
            ?: preferences.get(FuseDoubleKey.LivenessIobCapPercent),
        livenessCorrectionRatioCap = preferences.getIfExists(FuseDoubleKey.LivenessCorrectionRatioCap)
            ?.takeIf { it.isFinite() && it in FuseDoubleKey.LivenessCorrectionRatioCap.min..FuseDoubleKey.LivenessCorrectionRatioCap.max }
            ?: preferences.get(FuseDoubleKey.LivenessRatioCap),
        livenessCorrectionIobCapPercent = preferences.getIfExists(FuseDoubleKey.LivenessCorrectionIobCapPercent)
            ?.takeIf { it.isFinite() && it in FuseDoubleKey.LivenessCorrectionIobCapPercent.min..FuseDoubleKey.LivenessCorrectionIobCapPercent.max }
            ?: preferences.get(FuseDoubleKey.LivenessIobCapPercent),
        livenessBgMinDayMgdl = preferences.get(FuseDoubleKey.LivenessBgMinDayMgdl),
        // LESE-MIGRATION (v20): solange die Nachtschwelle nie gesetzt wurde,
        // folgt sie der Tagesschwelle - ein Update veraendert nichts still.
        // (Die Preferences-Schnittstelle bietet fuer PreferenceKeys kein
        // put; deshalb Fallback je Lesung statt einmaligem Seed.)
        // Die Grenzen-Klammer gehoert zur Migration: ein Wert ausserhalb
        // der Key-Grenzen kann nicht bewusst eingestellt worden sein (die
        // UI klemmt) - er zaehlt als "nie gesetzt". Ohne die Klammer brach
        // eine Test-Preferences-Implementierung, die 0.0 statt null liefert,
        // jeden Zyklus an der Validierung ab.
        livenessBgMinNightMgdl = preferences.getIfExists(FuseDoubleKey.LivenessBgMinNightMgdl)
            ?.takeIf { it.isFinite() && it in FuseDoubleKey.LivenessBgMinNightMgdl.min..FuseDoubleKey.LivenessBgMinNightMgdl.max }
            ?: preferences.get(FuseDoubleKey.LivenessBgMinDayMgdl),
        livenessReArmMin = preferences.get(FuseIntKey.LivenessReArmMin),
        // Ein ungesetzter Wert (0) ist kein Konfigurationsfehler, sondern ein
        // Speicher, der den Schluessel noch nicht kennt - dann gilt die
        // Vorgabe. Echte Fehlwerte faengt die Bereichspruefung darunter.
        primeWindowMin = preferences.get(FuseIntKey.PrimeWindowMin).takeIf { it > 0 }
            ?: PrimeRelease.WINDOW_MIN,
        endZeroWhenReasonGone = preferences.get(FuseBooleanKey.TbrEndZeroWhenReasonGone),
    ).also { validate(it) }

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
        /**
         * ERKLAERTE ABSORPTION (Toni 09.08.): erwarteter Anstieg aus der
         * Freigabe-Huelle [mg/dl/min], 0 wenn kein Kredit gilt.
         *
         * HIER wirkt sie nur auf der Mittelbahn - das bleibt so. Seit dem
         * 11.08. baut der Aufrufer daraus ZUSAETZLICH die bedingten Bahnen
         * (Haupt UND Bremse) fuer den Schwanz-Guard; die gehen NICHT ueber
         * diesen Parameter, sondern ueber eine eigene Kopie des Eingangs.
         * Der alte Kommentar "wirkt NUR auf der Mittelbahn" las sich sonst
         * wie eine Zusicherung ueber das Gesamtsystem.
         */
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
            // UNBEKANNT IST NICHT NULL: ein genullter Punkt im Array traegt
            // weder IOB noch Aktivitaet und macht die Bahn optimistischer,
            // ohne dass irgendeine Endlichkeitspruefung anschlaegt. Der Bau
            // schlaegt fehl, der Zyklus bricht benannt ab.
            if (!v.valid) return null
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
                DriveDiscount.methodId(PairSlopeBand.methodId(cfg.driveLowerQuantilePct, cfg.theilSenWindowMin), discount.lambda) +
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

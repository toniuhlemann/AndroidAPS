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
import app.aaps.fuse.core.adapter.CoreInputGuard
import app.aaps.fuse.core.adapter.CycleAssembly
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.IobThreshold
import app.aaps.fuse.core.controller.TailLiability
import app.aaps.fuse.core.controller.TbrPolicy
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.ObserverStateMachine
import app.aaps.fuse.core.observer.ObserverStep
import app.aaps.fuse.core.signal.PairSlopeBand
import app.aaps.fuse.core.predictor.ActualTrajectoryFactory
import app.aaps.fuse.core.predictor.DriveDecayModel
import app.aaps.fuse.core.predictor.DriveDiscount
import app.aaps.fuse.core.predictor.DriveEstimate
import app.aaps.fuse.core.predictor.InsulinLineage
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.core.predictor.PredictorInput
import app.aaps.fuse.core.predictor.PredictorOutcome
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryCore

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
    private val dateUtil: DateUtil,
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
        /** Warum NICHT gerechnet wurde. `null` heisst: der Zyklus lief durch. */
        val abortReason: String?,
    )

    fun run(tempBasalFallback: Boolean): Outcome {
        val computeTs = dateUtil.now()
        val gate = FusePumpGate.evaluate(runCatching { activePlugin.activePump }.getOrNull())

        fun abort(reason: String, signal: FuseSignalSource.Signal? = null, policy: Config? = null) = Outcome(
            decision = FuseController.noInput(reason), tbr = null, prediction = null,
            sourceTs = signal?.sourceTs, computeTs = computeTs, health = null, gate = gate,
            reason = reason, alarm = false, bgMgdl = signal?.q1, targetMgdl = null, targetSource = null,
            signal = signal, band = null, discount = null, policy = policy, state = null, step = null,
            sensorEpoch = null, calibrationEpoch = null,
            isfMgdlPerU = null, iobU = null, abortReason = reason,
        )

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

        // ---- 3 Bahn --------------------------------------------------------
        val cfg = when (val c = CoreInputGuard.build { readConfig() }) {
            is CoreInputGuard.Outcome.Built  -> c.value
            is CoreInputGuard.Outcome.Failed -> return abort("config: ${c.failure.detail}", signal)
        }

        // Mittel- UND Untergrenze aus DEMSELBEN Aufruf. Es darf keinen Zustand
        // "Mittel da, Band fehlt" geben: ein Rueckfall auf lower = mean wuerde
        // den Null-Abstand ausgerechnet bei der schlechtesten Datenlage still
        // wiederherstellen.
        val band = PairSlopeBand.estimate(signal.adjusted, signal.sourceTs, cfg.driveLowerQuantilePct)
            ?: return abort("drive not estimable (${signal.samplesUsed} samples)", signal, cfg)

        // Bolus-Aktivitaet am Anker - Eingang des Deckungs-Abschlags.
        // `calculateIobFromBolus()` rechnet auf `dateUtil.now()`, bis zu ~1 min
        // neben `sourceTs`. Fuer einen ABSCHLAG (keinen Bahnpunkt) ist der
        // Versatz zweitrangig; die Zahl steht im Export und ist nachpruefbar.
        val bolusActivityUPerMin = iobCobCalculator.calculateIobFromBolus().activity

        val built = when (val b = CoreInputGuard.build { buildPredictorInput(signal, profile, cfg, band, bolusActivityUPerMin) }) {
            is CoreInputGuard.Outcome.Built  -> b.value ?: return abort("input incomplete", signal, cfg)
            is CoreInputGuard.Outcome.Failed -> return abort("input: ${b.failure.detail}", signal, cfg)
        }

        val prediction = when (val p = TrajectoryCore.predict(built.input)) {
            is PredictorOutcome.Ok       -> p.result
            is PredictorOutcome.Rejected -> return abort("predictor: ${p.reason} ${p.detail}", signal, cfg)
        }

        // ZWEITE Bahn aus der schnellen Rate. Sie nutzt DASSELBE IOB-Array und
        // dieselben ISF-Slots — nur der Antrieb ist ein anderer. Der Aufwand ist
        // eine reine Arithmetikschleife ueber die Punkte, kein zusaetzlicher
        // Datenbankzugriff.
        //
        // Eine abgelehnte schnelle Bahn ist KEIN Abbruchgrund: sie darf nur
        // bremsen, also ist ihr Fehlen gleichbedeutend mit "bremst nicht".
        val restraint = if (!cfg.fastRestraintEnabled) null else
            fastDrive(signal)?.let { fast ->
                val fi = built.input.copy(
                    // DERSELBE Abschlag wie auf der Hauptbahn: auch die schnelle
                    // Untergrenze darf den bolusgedeckten Stoerungsanteil nicht
                    // als gesichert fortschreiben.
                    drive = DriveEstimate(
                        fast, fast - built.discount.termMgdlPerMin, null,
                        DriveDiscount.methodId("UKF_RATE_RESTRAINT_V1", cfg.bolusShareLambda),
                    ),
                )
                (TrajectoryCore.predict(fi) as? PredictorOutcome.Ok)?.result
            }

        // ---- 4 Menge -------------------------------------------------------
        val pumpDescription = activePlugin.activePump.pumpDescription
        val bolusStep = pumpDescription.bolusStep
        // 0.0 waere GEFAEHRLICHER als ein Block: floor(x/0)*0 ergibt NaN, und
        // NaN < 0.0 ist false — der Zyklus fiele durch statt zu sperren.
        if (!bolusStep.isFinite() || bolusStep <= 0.0) return abort("bolusStep=$bolusStep", signal, cfg)

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
                    rSignedMgdlPerMin = signal.rSigned,
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
            is CoreInputGuard.Outcome.Failed -> return abort("state: ${s.failure.detail}", signal, cfg)
        }

        // Schwanzhaftung. `bgAtHorizonLower` ist die BASELINE-Bahn ohne
        // Kandidat - der Vermerk dazu steht in TailLiability und wandert in den
        // Grund, damit der Guard keine Deckung behauptet, die er nicht hat.
        val tail = if (!cfg.tailGuardEnabled) null else TailLiability.evaluate(
            TailLiability.Input(
                lowerBgAtH = prediction.bgAtHorizonLower,
                existingIobAtH = built.iobAtH,
                isfTailMgdlPerU = built.isfTail,
                tailFloorMgdl = cfg.tailFloorMgdl,
                tailRecoveryU = cfg.tailRecoveryU,
            )
        )

        val decision = FuseController.decide(
            state, prediction,
            FuseController.Limits(guardFloorMgdl = cfg.guardFloorMgdl, releaseHorizonMin = cfg.releaseHorizonMin),
            tail,
            restraint,
        )

        // ---- 5 Kanal -------------------------------------------------------
        val runningTbr = persistenceLayer.getTemporaryBasalActiveAt(computeTs)
        val current = runningTbr?.let {
            TbrPolicy.Current(
                // Prozent-TBR wird HIER absolut gemacht — der Kern sieht nie
                // beides in derselben Zahl.
                absoluteRateUPerH = it.convertedToAbsolute(computeTs, profile),
                remainingMin = it.plannedRemainingMinutes,
                sourceType = TbrPolicy.SourceType.TEMP_BASAL,
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

        return Outcome(
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
            policy = cfg,
            state = state,
            step = step,
            sensorEpoch = sensorEpoch,
            calibrationEpoch = calibrationEpoch,
            isfMgdlPerU = isf,
            iobU = iobTotal.iob,
            abortReason = null,
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
        val riseRampLowR: Double,
        val riseRampHighR: Double,
        val maxSmbU: Double,
        val guardFloorMgdl: Double,
        val iobThPercent: Int,
        val releaseHorizonMin: Int,
        val liabilityHorizonMin: Int,
        val driveTauMin: Int,
        val driveLowerQuantilePct: Int,
        val tailGuardEnabled: Boolean,
        val tailFloorMgdl: Double,
        val tailRecoveryU: Double,
        val fastRestraintEnabled: Boolean,
        val bolusShareLambda: Double,
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
        riseRampLowR = preferences.get(FuseDoubleKey.RiseRampLowR),
        riseRampHighR = preferences.get(FuseDoubleKey.RiseRampHighR),
        maxSmbU = preferences.get(FuseDoubleKey.MaxSmbU),
        guardFloorMgdl = preferences.get(FuseDoubleKey.GuardFloorMgdl),
        iobThPercent = preferences.get(FuseIntKey.IobThPercent),
        releaseHorizonMin = preferences.get(FuseIntKey.ReleaseHorizonMin),
        liabilityHorizonMin = preferences.get(FuseIntKey.LiabilityHorizonMin),
        driveTauMin = preferences.get(FuseIntKey.DriveTauMin),
        driveLowerQuantilePct = preferences.get(FuseIntKey.DriveLowerQuantilePct),
        tailGuardEnabled = preferences.get(FuseBooleanKey.TailGuardEnabled),
        tailFloorMgdl = preferences.get(FuseDoubleKey.TailFloorMgdl),
        tailRecoveryU = preferences.get(FuseDoubleKey.TailRecoveryU),
        fastRestraintEnabled = preferences.get(FuseBooleanKey.FastRestraintEnabled),
        bolusShareLambda = preferences.get(FuseDoubleKey.BolusShareLambda),
    ).also {
        // Die Preference-Grenzen gelten nur im Einstellungsdialog. Ein Wert aus
        // einem alten Import geht daran vorbei — deshalb hier nochmal, und zwar
        // werfend, damit der Guard daraus einen benannten Abbruch macht.
        require(it.smbRatio.isFinite() && it.smbRatio in 0.0..1.0) { "smbRatio=${it.smbRatio}" }
        require(it.smbRatioRise.isFinite() && it.smbRatioRise in 0.0..1.0) { "smbRatioRise=${it.smbRatioRise}" }
        require(it.riseRampLowR.isFinite() && it.riseRampHighR.isFinite()) { "riseRamp not finite" }
        require(it.riseRampHighR > it.riseRampLowR) { "riseRamp ${it.riseRampLowR}..${it.riseRampHighR} invertiert" }
        require(it.maxSmbU.isFinite() && it.maxSmbU >= 0.0) { "maxSmb=${it.maxSmbU}" }
        require(it.guardFloorMgdl.isFinite() && it.guardFloorMgdl > 0.0) { "guardFloor=${it.guardFloorMgdl}" }
        require(it.iobThPercent >= 0) { "iobThPercent=${it.iobThPercent}" }
        require(it.releaseHorizonMin > 0) { "releaseHorizon=${it.releaseHorizonMin}" }
        // Gleiche Grenzen wie DriveDecayModel.ExponentialDecay - sonst wirft der
        // Kern bei einem Wert, den der Einstellungsdialog erlaubt hat.
        require(it.driveTauMin in 10..240) { "driveTau=${it.driveTauMin}" }
        require(it.driveLowerQuantilePct in PairSlopeBand.MIN_PCT..PairSlopeBand.MAX_PCT) {
            "driveLowerQuantile=${it.driveLowerQuantilePct}"
        }
        require(it.tailFloorMgdl.isFinite() && it.tailFloorMgdl > 0.0) { "tailFloor=${it.tailFloorMgdl}" }
        require(it.tailRecoveryU.isFinite() && it.tailRecoveryU >= 0.0) { "tailRecovery=${it.tailRecoveryU}" }
        require(it.bolusShareLambda.isFinite() && it.bolusShareLambda in 0.0..2.0) { "bolusShareLambda=${it.bolusShareLambda}" }
        require(it.liabilityHorizonMin >= it.releaseHorizonMin) {
            "liabilityHorizon=${it.liabilityHorizonMin} < releaseHorizon=${it.releaseHorizonMin}"
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
        val iobAtH = iob[hIndex]
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
            drive = DriveEstimate(
                band.mean, discount.lowerAfterMgdlPerMin, null,
                DriveDiscount.methodId(PairSlopeBand.methodId(cfg.driveLowerQuantilePct), cfg.bolusShareLambda),
            ),
            decay = DriveDecayModel.ExponentialDecay(cfg.driveTauMin.toDouble()),
            trajectory = trajectory,
            isfSlots = isfSlots,
            horizonMin = liabilityHorizonMin,
            ),
        )
    }
}

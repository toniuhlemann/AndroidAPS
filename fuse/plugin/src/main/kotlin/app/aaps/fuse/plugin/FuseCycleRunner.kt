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
import app.aaps.fuse.core.controller.TbrPolicy
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.ObserverStateMachine
import app.aaps.fuse.core.predictor.ActualTrajectoryFactory
import app.aaps.fuse.core.predictor.DriveDecayModel
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

        /** Alpha 1: es gibt noch KEIN Unsicherheitsband. Der Bezeichner steht im
         *  Export, damit eine spaetere Auswertung nicht raten muss, ob die
         *  Guardbahn eine echte Untergrenze war. */
        const val UNCERTAINTY_METHOD_ALPHA1 = "IDENTITY_NO_BAND_ALPHA1"
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
        val isfMgdlPerU: Double?,
        val iobU: Double?,
        /** Warum NICHT gerechnet wurde. `null` heisst: der Zyklus lief durch. */
        val abortReason: String?,
    )

    fun run(tempBasalFallback: Boolean): Outcome {
        val computeTs = dateUtil.now()
        val gate = FusePumpGate.evaluate(runCatching { activePlugin.activePump }.getOrNull())

        fun abort(reason: String, signal: FuseSignalSource.Signal? = null) = Outcome(
            decision = FuseController.noInput(reason), tbr = null, prediction = null,
            sourceTs = signal?.sourceTs, computeTs = computeTs, health = null, gate = gate,
            reason = reason, alarm = false, bgMgdl = signal?.q1, targetMgdl = null, targetSource = null,
            signal = signal, isfMgdlPerU = null, iobU = null, abortReason = reason,
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

        val input = when (val b = CoreInputGuard.build { buildPredictorInput(signal, profile, cfg) }) {
            is CoreInputGuard.Outcome.Built  -> b.value ?: return abort("input incomplete", signal)
            is CoreInputGuard.Outcome.Failed -> return abort("input: ${b.failure.detail}", signal)
        }

        val prediction = when (val p = TrajectoryCore.predict(input)) {
            is PredictorOutcome.Ok       -> p.result
            is PredictorOutcome.Rejected -> return abort("predictor: ${p.reason} ${p.detail}", signal)
        }

        // ---- 4 Menge -------------------------------------------------------
        val pumpDescription = activePlugin.activePump.pumpDescription
        val bolusStep = pumpDescription.bolusStep
        // 0.0 waere GEFAEHRLICHER als ein Block: floor(x/0)*0 ergibt NaN, und
        // NaN < 0.0 ist false — der Zyklus fiele durch statt zu sperren.
        if (!bolusStep.isFinite() || bolusStep <= 0.0) return abort("bolusStep=$bolusStep", signal)

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
                    smbRatio = cfg.smbRatio,
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
            is CoreInputGuard.Outcome.Failed -> return abort("state: ${s.failure.detail}", signal)
        }

        val decision = FuseController.decide(
            state, prediction,
            FuseController.Limits(guardFloorMgdl = cfg.guardFloorMgdl, releaseHorizonMin = cfg.releaseHorizonMin),
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
            isfMgdlPerU = isf,
            iobU = iobTotal.iob,
            abortReason = null,
        )
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

    private data class Config(
        val smbRatio: Double,
        val maxSmbU: Double,
        val guardFloorMgdl: Double,
        val iobThPercent: Int,
        val releaseHorizonMin: Int,
        val liabilityHorizonMin: Int,
        val driveTauMin: Int,
    )

    /**
     * ALLE Stellgroessen kommen aus den Einstellungen — im Regelpfad steht keine
     * einzige Zahl. Gelesen wird EINMAL je Zyklus: ein Wert, der sich zwischen
     * Guard und Freigabe aendert, waere eine Entscheidung aus zwei verschiedenen
     * Konfigurationen.
     */
    private fun readConfig() = Config(
        smbRatio = preferences.get(FuseDoubleKey.SmbRatio),
        maxSmbU = preferences.get(FuseDoubleKey.MaxSmbU),
        guardFloorMgdl = preferences.get(FuseDoubleKey.GuardFloorMgdl),
        iobThPercent = preferences.get(FuseIntKey.IobThPercent),
        releaseHorizonMin = preferences.get(FuseIntKey.ReleaseHorizonMin),
        liabilityHorizonMin = preferences.get(FuseIntKey.LiabilityHorizonMin),
        driveTauMin = preferences.get(FuseIntKey.DriveTauMin),
    ).also {
        // Die Preference-Grenzen gelten nur im Einstellungsdialog. Ein Wert aus
        // einem alten Import geht daran vorbei — deshalb hier nochmal, und zwar
        // werfend, damit der Guard daraus einen benannten Abbruch macht.
        require(it.smbRatio.isFinite() && it.smbRatio in 0.0..1.0) { "smbRatio=${it.smbRatio}" }
        require(it.maxSmbU.isFinite() && it.maxSmbU >= 0.0) { "maxSmb=${it.maxSmbU}" }
        require(it.guardFloorMgdl.isFinite() && it.guardFloorMgdl > 0.0) { "guardFloor=${it.guardFloorMgdl}" }
        require(it.iobThPercent >= 0) { "iobThPercent=${it.iobThPercent}" }
        require(it.releaseHorizonMin > 0) { "releaseHorizon=${it.releaseHorizonMin}" }
        // Gleiche Grenzen wie DriveDecayModel.ExponentialDecay - sonst wirft der
        // Kern bei einem Wert, den der Einstellungsdialog erlaubt hat.
        require(it.driveTauMin in 10..240) { "driveTau=${it.driveTauMin}" }
        require(it.liabilityHorizonMin >= it.releaseHorizonMin) {
            "liabilityHorizon=${it.liabilityHorizonMin} < releaseHorizon=${it.releaseHorizonMin}"
        }
    }

    /**
     * Baut die Predictor-Eingabe. Die beiden Zeitachsen-Gates sind hier
     * strukturell erfuellt, nicht zufaellig: das Array beginnt AM Anker und
     * reicht ueber den Horizont hinaus.
     */
    private fun buildPredictorInput(
        signal: FuseSignalSource.Signal,
        profile: Profile,
        cfg: Config,
    ): PredictorInput? {
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

        val rSigned = signal.rSigned ?: return null
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

        return PredictorInput(
            predictionAnchorTs = anchor,
            bgAtAnchor = signal.q1,
            // ALPHA 1: `lower = mean` heisst, die Guardbahn ist im Moment
            // identisch mit der Mittelbahn — der Sicherheitsabstand FEHLT also,
            // statt heimlich erfunden zu werden. Das ist eine offene
            // Entscheidung und steht unter diesem Namen im Export.
            drive = DriveEstimate(rSigned, rSigned, 0.5, UNCERTAINTY_METHOD_ALPHA1),
            decay = DriveDecayModel.ExponentialDecay(cfg.driveTauMin.toDouble()),
            trajectory = trajectory,
            isfSlots = isfSlots,
            horizonMin = liabilityHorizonMin,
        )
    }
}

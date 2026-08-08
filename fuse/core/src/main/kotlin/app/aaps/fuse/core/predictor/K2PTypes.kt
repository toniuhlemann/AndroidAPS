package app.aaps.fuse.core.predictor

/**
 * K2-P Datentypen (Spec v0.1 + Fixblöcke v0.1.1/v0.1.2, Auflagen R71-A..D).
 *
 * REIN BEOBACHTEND: nichts hier liefert eine SMB-, TBR-, Raten- oder
 * Dauerentscheidung. Der Predictor rechnet Bahnen, er dosiert nicht.
 */

/** Warum eine Bahn NICHT erzeugt wurde. Es gibt keinen Ersatzwert und keine
 *  stille Naeherung — fehlende Eingabe ergibt null plus Grund (Spec §12 U1-U5). */
enum class PredictorReason {
    ARRAY_TOO_SHORT,          // Horizont ueberschreitet die reale Arrayspanne
    SKEW_BEFORE_ARRAY_START,  // erstes Abfrageziel liegt vor arrayFirstTs
    NON_FINITE_INPUT,         // NaN/Infinity irgendwo im Pfad
    ACTIVITY_OUT_OF_BOUNDS,   // |activity| ueber der POLICY-Grenze (nicht Vorzeichen!)
    ISF_OUT_OF_BOUNDS,
    DRIVE_OUT_OF_BOUNDS,
    NON_MONOTONIC_TIMESTAMPS,
    GRID_MISMATCH,
    MISSING_ISF_SLOT,
}

/**
 * Plausibilitaetsgrenzen als VERSIONIERTE POLICY, nicht als Algorithmus-Literal
 * (R71-A1). AAPS erlaubt laut HardLimits ISF von 2 bis 1000 mg/dl/U — eine im
 * Kern verdrahtete engere Grenze wuerde gueltige Profile verwerfen.
 *
 * `maxAbsActivity`/`maxAbsDrive` sind NULLBAR: null bedeutet KEINE zusaetzliche
 * Betragsgrenze, ausdruecklich nicht den Wert 0.
 */
data class PredictorInputBounds(
    val minIsfMgdlPerU: Double = 2.0,      // = HardLimits.MIN_ISF
    val maxIsfMgdlPerU: Double = 1000.0,   // = HardLimits.MAX_ISF
    val maxAbsActivityUPerMin: Double? = null,
    val maxAbsDriveMgdlPerMin: Double? = null,
    val policyId: String = "aaps-hardlimits-v1",
)

/**
 * Antriebsschaetzung. `lower` wird HINEINGEREICHT — der pure Kern erfindet
 * keine statistische Konvention (R70-F4). Nicht nullbar: ein Snapshot ohne
 * Untergrenze wird bereits beim Bau abgewiesen, statt spaeter still
 * `lower = mean` zu setzen (R71 §7.1).
 */
data class DriveEstimate(
    val meanMgdlPerMin: Double,
    val lowerMgdlPerMin: Double,
    /**
     * NULLBAR = NICHT KALIBRIERT.
     *
     * Vorher stand hier fest 0.5 — eine erfundene Zahl, die im Export wie "halb
     * sicher" aussieht und keinen einzigen Verbraucher hat. Was stattdessen
     * mitgefuehrt wird, ist das GEMESSENE: Paaranzahl und Spreizung, beides im
     * Adapter. Erst eine Kalibrierung darf daraus eine Konfidenz machen.
     */
    val confidence: Double?,
    val uncertaintyMethodId: String,
    /**
     * DERSELBE untere Antrieb OHNE ERKLAERUNGSBASIERTE ZUSCHLAEGE (C2, Codex
     * H2 "a marker may create demand evidence, not protection").
     *
     * Der Marker-Prior schreibt der unteren Bahn einen deklarierten
     * Carb-Antrieb gut. Genau diese gehobene Bahn diente bisher den
     * Sicherheitszertifikaten als Deckung — die Erklaerung "Carbs kommen"
     * lizenzierte ihre eigene Dosis. Der Prior darf BEDARF erzeugen, aber
     * keine SICHERHEIT vortaeuschen.
     *
     * `null` heisst: es gab keinen Zuschlag, [lowerMgdlPerMin] IST bereits
     * prior-frei. Ein Wert MUSS <= [lowerMgdlPerMin] sein — ein Zuschlag kann
     * die untere Bahn nur heben, nie senken.
     */
    val lowerPriorFreeMgdlPerMin: Double? = null,
) {
    init {
        require(meanMgdlPerMin.isFinite() && lowerMgdlPerMin.isFinite()) { "drive not finite" }
        require(lowerMgdlPerMin <= meanMgdlPerMin) { "lower > mean" }
        require(confidence == null || confidence in 0.0..1.0) { "confidence out of range" }
        require(uncertaintyMethodId.isNotBlank()) { "uncertaintyMethodId blank" }
        require(lowerPriorFreeMgdlPerMin == null || lowerPriorFreeMgdlPerMin.isFinite()) { "priorFree not finite" }
        require(lowerPriorFreeMgdlPerMin == null || lowerPriorFreeMgdlPerMin <= lowerMgdlPerMin) {
            "priorFree > lower: ein Zuschlag kann nur heben"
        }
    }

    /** Der Antrieb, gegen den SICHERHEIT gerechnet wird. */
    val safetyLowerMgdlPerMin: Double get() = lowerPriorFreeMgdlPerMin ?: lowerMgdlPerMin
}

/** Insulinherkunft. Actual darf gegen die reale BG benotet werden, Virtual nie. */
sealed interface InsulinLineage {
    val modelHash: String
    val lineageKind: String

    data class ActualTreatment(
        val sourceDeviceIdHash: String,
        val treatmentSnapshotHash: String,   // KEIN einzelner Long-Cursor (R70-F5)
        override val modelHash: String,
    ) : InsulinLineage {
        override val lineageKind get() = "ACTUAL"
    }

    data class VirtualController(
        val vpumpSessionId: String,
        val ledgerRevision: Long,
        override val modelHash: String,
    ) : InsulinLineage {
        override val lineageKind get() = "VIRTUAL"
    }
}

/** Ein Punkt des AAPS-Zukunftsarrays. `activity` darf NEGATIV sein — nach einer
 *  Zero-/Low-TBR ist netBasalRate = rate - basalRate negativ, und
 *  bgi = -activity * isf ergibt dann korrekt POSITIVES BGI (R70-F2). */
data class IobPoint(val timeMs: Long, val iob: Double, val activity: Double, val basalIob: Double)

/** Absolut aufgeloester ISF-Block; Uhrzeitbloecke allein genuegen nicht, weil
 *  ein Profile-Switch im Horizont ablaufen kann (R69-F6.1).
 *
 *  Geprueft wird hier NUR die Intervall-Invariante. Der WERT bleibt bewusst
 *  ungeprueft: seine Grenzen sind versionierte Policy
 *  ([PredictorInputBounds]), und ein unzulaessiger ISF ergibt im Kern eine
 *  benannte Ablehnung (`ISF_OUT_OF_BOUNDS`) statt einer Ausnahme. Eine
 *  Wertpruefung an dieser Stelle wuerde diese Ablehnung in einen Wurf
 *  verwandeln (R81-F6). */
data class IsfSlot(val startTsInclusive: Long, val endTsExclusive: Long, val isfMgdlPerU: Double) {

    init {
        require(endTsExclusive > startTsInclusive) { "empty isf slot: $startTsInclusive..$endTsExclusive" }
    }
}

data class InsulinModelProvenance(
    val insulinType: String,
    val diaHours: Double,
    val peakMin: Int,
    val codeProvenance: String,
) {
    val modelSupportMin: Int get() = (diaHours * 60).toInt()
}

/**
 * Lineage und Punkte GEBUENDELT (R69-F7). Der Konstruktor ist modul-privat;
 * erzeugt wird ausschliesslich ueber die beiden Factories, damit niemand
 * VirtualPump-Punkte als Actual etikettieren kann. Ein Hash allein wuerde das
 * nicht verhindern — er sichert nur gegen Aenderung NACH dem Etikettieren.
 */
class InsulinTrajectoryInput internal constructor(
    val lineage: InsulinLineage,
    val points: List<IobPoint>,
    val arrayAsOfTs: Long,
    val model: InsulinModelProvenance,
    val iobCalculationHash: String,
) {
    val firstTs: Long get() = points.first().timeMs
    val lastTs: Long get() = points.last().timeMs
    val gridMin: Double get() = if (points.size < 2) 0.0 else (points[1].timeMs - points[0].timeMs) / 60_000.0
    val spanMin: Double get() = (lastTs - firstTs) / 60_000.0

    /** > 0 bedeutet: der Modellschwanz reicht ueber das Array hinaus. Das ist
     *  eine EIGENSCHAFT der Quelle, kein Fehler — aber es muss sichtbar sein,
     *  damit ein Prognosefehler nicht dem Antriebsmodell zugeschrieben wird. */
    val modelTailBeyondArrayMin: Double get() = maxOf(0.0, model.modelSupportMin - spanMin)
    val arrayPaddingBeyondModelMin: Double get() = maxOf(0.0, spanMin - model.modelSupportMin)

    val contentHash: String by lazy { Canonical.trajectoryContentHash(this) }
}

object ActualTrajectoryFactory {
    fun of(
        lineage: InsulinLineage.ActualTreatment,
        points: List<IobPoint>,
        arrayAsOfTs: Long,
        model: InsulinModelProvenance,
        iobCalculationHash: String,
    ) = InsulinTrajectoryInput(lineage, points, arrayAsOfTs, model, iobCalculationHash)
}

object VirtualTrajectoryFactory {
    fun of(
        lineage: InsulinLineage.VirtualController,
        points: List<IobPoint>,
        arrayAsOfTs: Long,
        model: InsulinModelProvenance,
        iobCalculationHash: String,
    ) = InsulinTrajectoryInput(lineage, points, arrayAsOfTs, model, iobCalculationHash)
}

/** Eingabe einer Trajektorienrechnung. Unveraenderlich; der Kern fragt nichts nach. */
data class PredictorInput(
    val predictionAnchorTs: Long,     // = K1-sourceTs, NICHT computeTs
    val bgAtAnchor: Double,           // q1 auf derselben Lineage wie das Outcome
    val drive: DriveEstimate,
    val decay: DriveDecayModel,
    val trajectory: InsulinTrajectoryInput,
    val isfSlots: List<IsfSlot>,
    val horizonMin: Int,
    val bounds: PredictorInputBounds = PredictorInputBounds(),
    /**
     * VORZEICHENBEWUSSTER ZERFALL (C10, Codex H5): Zerfallsmodell fuer die
     * NEGATIVEN Antriebsanteile.
     *
     * Das Rebound-Fenster kuerzt tau (60 -> 15 min), weil eine
     * Erholungssteigung nach einem Tief in ~15 min stirbt. Vorzeichenblind
     * angewandt verkleinert derselbe schnellere Zerfall aber auch einen
     * NEGATIVEN Antrieb — und HEBT damit die untere Bahn: der Hypo-Schutz
     * wuerde ausgerechnet nach einem Tief schwaecher.
     *
     * `null` = vorzeichenblind wie bisher. Ist ein Modell gesetzt, bekommt ein
     * negativer Antrieb den LANGSAMEREN der beiden Faktoren (s.
     * [TrajectoryCore]) — die Einseitigkeit haengt damit nicht an der
     * Disziplin des Aufrufers.
     */
    val decayNegativeDrive: DriveDecayModel? = null,
)

/** Ein Minutenpunkt beider Bahnen. */
data class TrajectoryPoint(
    val offsetMin: Int,
    val tsMs: Long,
    val meanBg: Double,
    val lowerBg: Double,
    val bgiRate: Double,
    val driveMean: Double,
    val driveLower: Double,
    /**
     * Die untere Bahn OHNE erklaerungsbasierten Zuschlag (C2). `null` heisst:
     * es gab keinen Zuschlag, [lowerBg] ist bereits prior-frei — nicht "0".
     * [TrajectoryCore] setzt das Feld IMMER; null tritt nur bei handgebauten
     * Punkten auf.
     */
    val lowerBgPriorFree: Double? = null,
) {

    /** Die Bahn, gegen die SICHERHEIT gerechnet wird (Guard, Clearance, Tail). */
    val safetyLowerBg: Double get() = lowerBgPriorFree ?: lowerBg
}

/**
 * Ergebnis. Heisst `meanTrajectory`/`lowerTrajectory` und NICHT "guard" —
 * eine Sicherheitsbezeichnung ohne Sicherheitsband waere irrefuehrend
 * (R69-F8). Der spaetere Guard in K2-C verwendet `lowerTrajectory`.
 */
data class PredictorResult(
    val points: List<TrajectoryPoint>,
    /**
     * Der Anker, auf den sich `offsetMin` bezieht (R81-F1).
     *
     * Der erste Punkt liegt bei offsetMin = 1, NICHT bei 0 — das Zukunftsarray
     * beginnt eine Minute nach dem Anker. Wer den Anker aus `points.first()`
     * ableitet, verliert genau diese erste Minute und weist eine Lieferung am
     * Anker faelschlich als "vor dem Bahnanfang" ab. Deshalb steht er hier
     * explizit statt implizit.
     */
    val predictionAnchorTs: Long,
    /**
     * BG am Anker selbst (R83-F1). Mittel- und Untergrenze sind dort identisch —
     * die Baender oeffnen sich erst mit der ersten Zukunftsminute.
     *
     * Er steht hier, weil `points` bei Minute 1 beginnt: `minLowerBg` des
     * Predictors ENTHAELT den Anker (er wird damit initialisiert), ein
     * Verbraucher, der nur ueber `points` laeuft, aber nicht. Ohne dieses Feld
     * gaebe es zwei verschiedene Definitionen von "Minimum ueber [0..H]".
     */
    val bgAtAnchor: Double,
    val minMeanBg: Double,
    val minLowerBg: Double,
    val timeToMinLowerMin: Int,
    val bgAtHorizonMean: Double,
    val bgAtHorizonLower: Double,
    val baselineSemantics: String = BASELINE_SEMANTICS,
    val lineageKind: String,
    val trajectoryContentHash: String,
    val iobArraySpanMin: Double,
    val iobArrayGridMin: Double,
    val modelTailBeyondArrayMin: Double,
    val inputSkewMs: Long,
    /** Minimum der PRIOR-FREIEN unteren Bahn ueber [0..H], Anker eingeschlossen
     *  (C2). `null` = kein Zuschlag im Spiel, [minLowerBg] ist prior-frei. */
    val minLowerBgPriorFree: Double? = null,
    /** Prior-freie untere Bahn AM Haftungshorizont (C2) — Eingang des
     *  Schwanz-Guards. `null` wie oben. */
    val bgAtHorizonLowerPriorFree: Double? = null,
) {

    /**
     * Das Minimum, gegen das SICHERHEIT gerechnet wird.
     *
     * Bewusst ein eigener Name statt einer Umdeutung von [minLowerBg]:
     * [minLowerBg] bleibt die Bahn, die im Export/Schirm steht (dort ist der
     * Prior-Hub eine gewollte Information), [minSafetyLowerBg] ist die, die
     * eine Dosis autorisieren darf.
     */
    val minSafetyLowerBg: Double get() = minLowerBgPriorFree ?: minLowerBg

    /** Prior-freie Untergrenze am Horizont — s. [minSafetyLowerBg]. */
    val bgAtHorizonSafetyLower: Double get() = bgAtHorizonLowerPriorFree ?: bgAtHorizonLower

    companion object {
        /** Rueckkehr zum GEPLANTEN PROFILBASAL, nicht Pumpenstopp: AAPS rechnet
         *  Temp-Basal als Nettoabweichung (netBasalRate = rate - basalRate), das
         *  Profilbasal ist die implizite Basis (R70-F1). */
        const val BASELINE_SEMANTICS = "predNoFurtherNetDeviation"
    }
}

sealed interface PredictorOutcome {
    data class Ok(val result: PredictorResult) : PredictorOutcome
    data class Rejected(val reason: PredictorReason, val detail: String) : PredictorOutcome
}

/**
 * Die pessimistischste Untergrenze ueber ALLE glaubwuerdigen Bahnen
 * (C1 + C2, Codex H1/H2).
 *
 * H1 sagt: "the same set of trajectories must flow through baseline, candidate
 * search, Prime and final verification". Wer nur EINE Bahn prueft, laesst eine
 * gleichzeitig gerechnete, pessimistischere Schaetzung unbewertet - der
 * Baseline-Guard nahm das Minimum aus Haupt- und Bremsbahn, die Mit-Dosis-
 * Pruefungen sahen die Bremsbahn nie.
 *
 * Verwendet je Bahn [PredictorResult.minSafetyLowerBg], also die PRIOR-FREIE
 * Variante. `null`-Bahnen (Bremse abgeschaltet) zaehlen nicht mit; ist gar
 * keine Bahn da, ist das Ergebnis NaN - fail-closed, weil jeder Verbraucher
 * auf Endlichkeit prueft.
 */
fun minSafetyLowerOf(vararg trajectories: PredictorResult?): Double =
    trajectories.filterNotNull().minOfOrNull { it.minSafetyLowerBg } ?: Double.NaN

/** Wie [minSafetyLowerOf], aber AM Haftungshorizont - Eingang des
 *  Schwanz-Guards (TailLiability). */
fun minSafetyHorizonLowerOf(vararg trajectories: PredictorResult?): Double =
    trajectories.filterNotNull().minOfOrNull { it.bgAtHorizonSafetyLower } ?: Double.NaN

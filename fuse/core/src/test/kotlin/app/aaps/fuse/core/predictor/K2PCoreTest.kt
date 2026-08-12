package app.aaps.fuse.core.predictor

import app.aaps.fuse.core.signal.BgiAdjustedSeries
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/**
 * K2PCoreTest — die nach R71-C freigegebene Suite. Outcome-/Evaluator- und
 * Sammler-Tests sind hier ausdruecklich NICHT enthalten; sie brauchen
 * Komponenten, die vom ersten Kotlin-GO ausgenommen sind.
 */
class K2PCoreTest {

    private val t0 = 1_700_000_000_000L

    private fun points(activity: Double, n: Int = 48, gridMin: Int = 5, start: Long = t0) =
        (0 until n).map { IobPoint(start + it * gridMin * 60_000L, 1.0, activity, 0.0) }

    private fun model() = InsulinModelProvenance("rapid", 9.0, 75, "test")

    private fun traj(
        activity: Double = 0.0,
        n: Int = 48,
        start: Long = t0,
        pts: List<IobPoint>? = null,
    ) = ActualTrajectoryFactory.of(
        InsulinLineage.ActualTreatment("dev", "snap", "m1"),
        pts ?: points(activity, n, 5, start),
        start, model(), "iobcalc1",
    )

    private fun isf(v: Double = 90.0) = listOf(IsfSlot(t0 - 86_400_000L, t0 + 86_400_000L, v))

    private fun input(
        driveMean: Double = 0.0,
        driveLower: Double = 0.0,
        activity: Double = 0.0,
        horizon: Int = 60,
        bg: Double = 120.0,
        bounds: PredictorInputBounds = PredictorInputBounds(),
        isfSlots: List<IsfSlot> = isf(),
        trajectory: InsulinTrajectoryInput = traj(activity),
        /** Prior-freier Zwilling des unteren Antriebs (C2); null = kein Prior. */
        driveLowerPriorFree: Double? = null,
    ) = PredictorInput(
        predictionAnchorTs = t0,
        bgAtAnchor = bg,
        drive = DriveEstimate(driveMean, driveLower, 0.8, "test-v1", driveLowerPriorFree),
        decay = DriveDecayModel.ExponentialDecay(60.0),
        trajectory = trajectory,
        isfSlots = isfSlots,
        horizonMin = horizon,
        bounds = bounds,
    )

    private fun ok(i: PredictorInput) = (TrajectoryCore.predict(i) as PredictorOutcome.Ok).result
    private fun rejected(i: PredictorInput) = (TrajectoryCore.predict(i) as PredictorOutcome.Rejected)

    // ---- Arrayvertrag ----------------------------------------------------

    /** KP-15: 48 Punkte im 5-min-Raster spannen 47 Intervalle = 235 min. */
    @Test
    fun `KP-15 48 Punkte ergeben 235 Minuten, nicht 240`() {
        assertEquals(235.0, traj().spanMin)
        assertEquals(5.0, traj().gridMin)
    }

    /** KP-5: jenseits der Arrayspanne wird NICHT extrapoliert. */
    @Test
    fun `KP-5 Horizont jenseits der Arrayspanne wird abgewiesen`() {
        assertEquals(PredictorReason.ARRAY_TOO_SHORT, rejected(input(horizon = 236)).reason)
        assertTrue(TrajectoryCore.predict(input(horizon = 235)) is PredictorOutcome.Ok)
    }

    /** KP-19: der Modellschwanz wird gegen das MODELLENDE gemessen, nicht dia+30. */
    @Test
    fun `KP-19 modelTailBeyondArrayMin nutzt das Modellende`() {
        // DIA 9 h = 540 min Modellstuetze, Array 235 min -> 305 min fehlen.
        assertEquals(305.0, traj().modelTailBeyondArrayMin)
        assertEquals(0.0, traj().arrayPaddingBeyondModelMin)
    }

    /** KP-24: liegt das Glukosesample vor dem Arrayanfang, gibt es keine Bahn. */
    @Test
    fun `KP-24 Skew vor Arrayanfang ergibt Ablehnung statt Rueckwaerts-Extrapolation`() {
        val late = traj(start = t0 + 10 * 60_000L)
        assertEquals(PredictorReason.SKEW_BEFORE_ARRAY_START, rejected(input(trajectory = late)).reason)
    }

    // ---- Vorzeichentreue Aktivitaet --------------------------------------

    /**
     * KP-31: Negative Aktivitaet nach Zero-/Low-TBR ist gueltig und hebt die Bahn.
     * Ein Verwerfen haette im FCL genau die Phasen entwertet, in denen der Loop
     * am staerksten eingreift.
     */
    @Test
    fun `KP-31 negative Aktivitaet ist gueltig und erzeugt positives BGI`() {
        val r = ok(input(activity = -0.01))     // Zero-TBR: Basal fehlt gegenueber Plan
        assertTrue(r.points.first().bgiRate > 0.0) { "negative activity muss BGI anheben" }
        assertTrue(r.bgAtHorizonMean > 120.0)
    }

    /** KP-41: dieselbe (activity, isf) muss in K2-P und im gelockten K1-Kern
     *  dasselbe vorzeichentreue bgiRate ergeben — sonst waere es ein anderer
     *  Kandidat. */
    @Test
    fun `KP-41 bgiRate ist identisch zum gelockten K1-Kern`() {
        val activity = -0.013
        val isfV = 87.0
        val k2p = ok(input(activity = activity, isfSlots = isf(isfV))).points.first().bgiRate
        val k1 = BgiAdjustedSeries.adjust(
            listOf(
                BgiAdjustedSeries.Sample(t0, 100.0, activity, isfV),
                BgiAdjustedSeries.Sample(t0 + 60_000L, 100.0, activity, isfV),
            )
        ).let { it.points[0].adjusted - it.points[1].adjusted }   // = bgiIncrement ueber 1 min
        assertEquals(k1, k2p, 1e-9)
    }

    /** KP-43: eine offene (null) Betragsgrenze akzeptiert jede finite Aktivitaet. */
    @Test
    fun `KP-43 offene Activity-Grenze akzeptiert jede finite Aktivitaet`() {
        assertTrue(TrajectoryCore.predict(input(activity = -5.0)) is PredictorOutcome.Ok)
    }

    @Test
    fun `Activity-Grenze aus der Policy greift auf den BETRAG`() {
        val b = PredictorInputBounds(maxAbsActivityUPerMin = 0.5)
        assertEquals(PredictorReason.ACTIVITY_OUT_OF_BOUNDS, rejected(input(activity = -0.6, bounds = b)).reason)
        assertTrue(TrajectoryCore.predict(input(activity = -0.4, bounds = b)) is PredictorOutcome.Ok)
    }

    // ---- Bounds als Policy (R71-A) ---------------------------------------

    /** KP-42: AAPS erlaubt ISF 2..1000; der Kern darf gueltige Profile nicht
     *  mit einer eigenen engeren Grenze verwerfen. */
    @Test
    fun `KP-42 ISF bis 1000 wird nicht verworfen`() {
        assertTrue(TrajectoryCore.predict(input(isfSlots = isf(900.0))) is PredictorOutcome.Ok)
        assertEquals(PredictorReason.ISF_OUT_OF_BOUNDS, rejected(input(isfSlots = isf(1500.0))).reason)
    }

    // ---- Mean und Lower ---------------------------------------------------

    /** KP-32: eine Untergrenze ueber dem Mittelwert ist kein gueltiger Zustand. */
    @Test
    fun `KP-32 DriveEstimate mit lower groesser mean wird abgewiesen`() {
        assertThrows<IllegalArgumentException> { DriveEstimate(0.5, 0.9, 0.8, "m") }
    }

    /** KP-27/KP-33: beide Bahnen nutzen dieselbe decay-Form; lower liegt nie darueber. */
    @Test
    fun `KP-33 lower liegt nie ueber mean und nutzt dieselbe Zerfallsform`() {
        val r = ok(input(driveMean = 1.0, driveLower = 0.2))
        r.points.forEach { assertTrue(it.lowerBg <= it.meanBg) }
        val ratio = r.points.map { it.driveLower / it.driveMean }
        ratio.forEach { assertEquals(0.2, it, 1e-9) }   // konstantes Verhaeltnis = gleiche Form
    }

    // ---- Trajektorienminimum ---------------------------------------------

    /** KP-8: ein Zwischentief muss sichtbar sein, auch wenn der Endwert harmlos ist. */
    @Test
    fun `KP-8 Zwischentief erscheint in minLowerBg und timeToMin`() {
        // Insulin wirkt zuerst (positive activity), Antrieb traegt spaeter.
        val pts = (0 until 48).map {
            val m = it * 5
            IobPoint(t0 + m * 60_000L, 1.0, if (m < 30) 0.02 else 0.0, 0.0)
        }
        val r = ok(input(driveMean = 0.6, driveLower = 0.6, trajectory = traj(pts = pts), horizon = 120))
        assertTrue(r.minLowerBg < r.bgAtHorizonLower) { "Tief muss unter dem Endwert liegen" }
        assertTrue(r.timeToMinLowerMin in 1..119)
    }

    // ---- Zerfallsmodelle --------------------------------------------------

    @Test
    fun `M2 ist an der Haltegrenze stetig`() {
        val m = DriveDecayModel.HoldThenExponentialDecay(20.0, 60.0)
        assertEquals(1.0, m.factorAt(19.999), 1e-6)
        assertEquals(1.0, m.factorAt(20.0), 1e-12)
        assertTrue(m.factorAt(20.001) < 1.0)
    }

    /** KP-40: Randgewichte ergeben kanonisch M1, nie ein entartetes M3. */
    @Test
    fun `KP-40 M3-Randdegeneration wird auf M1 reduziert`() {
        val a = DriveDecayModel.TwoPhaseExponentialDecay.of(0.0, 20.0, 120.0)
        val b = DriveDecayModel.TwoPhaseExponentialDecay.of(1.0, 20.0, 120.0)
        assertEquals("M1_EXPONENTIAL_DECAY", a.modelId)
        assertEquals("M1_EXPONENTIAL_DECAY", b.modelId)
        assertEquals(120.0, (a as DriveDecayModel.ExponentialDecay).tauMin)
        assertEquals(20.0, (b as DriveDecayModel.ExponentialDecay).tauMin)
        assertThrows<IllegalArgumentException> { DriveDecayModel.TwoPhaseExponentialDecay(0.0, 20.0, 120.0) }
    }

    // ---- Interpolation ----------------------------------------------------

    /** KP-3: lineare Interpolation zwischen 5-min-Stuetzstellen, reproduzierbar. */
    @Test
    fun `KP-3 Aktivitaet wird linear zwischen Stuetzstellen interpoliert`() {
        val pts = listOf(
            IobPoint(t0, 1.0, 0.0, 0.0),
            IobPoint(t0 + 300_000L, 1.0, 0.10, 0.0),
        )
        assertEquals(0.0, TrajectoryCore.interpolateActivity(pts, t0)!!, 1e-12)
        assertEquals(0.04, TrajectoryCore.interpolateActivity(pts, t0 + 120_000L)!!, 1e-12)
        assertEquals(0.10, TrajectoryCore.interpolateActivity(pts, t0 + 300_000L)!!, 1e-12)
        assertNull(TrajectoryCore.interpolateActivity(pts, t0 + 300_001L))
    }

    /** KP-25: ein ISF-Wechsel im Horizont wird absolut aufgeloest. */
    @Test
    fun `KP-25 ISF-Slotwechsel im Horizont wird beruecksichtigt`() {
        val slots = listOf(
            IsfSlot(t0 - 3_600_000L, t0 + 30 * 60_000L, 90.0),
            IsfSlot(t0 + 30 * 60_000L, t0 + 86_400_000L, 45.0),
        )
        val r = ok(input(activity = 0.01, isfSlots = slots, horizon = 60))
        assertEquals(-0.9, r.points[0].bgiRate, 1e-9)     // 0.01 * 90
        assertEquals(-0.45, r.points[59].bgiRate, 1e-9)   // 0.01 * 45
    }

    @Test
    fun `fehlender ISF-Slot ergibt Ablehnung statt Ersatzwert`() {
        val slots = listOf(IsfSlot(t0 - 3_600_000L, t0 + 10 * 60_000L, 90.0))
        assertEquals(PredictorReason.MISSING_ISF_SLOT, rejected(input(isfSlots = slots)).reason)
    }

    // ---- Hashes (R71-B) ---------------------------------------------------

    /** KP-45: der Provenienzhash ist VERLUSTFREI — Unterschiede unter 1e-6
     *  duerfen nicht verschwinden. */
    @Test
    fun `KP-45 Input-Hash unterscheidet Werte unterhalb der Exportrundung`() {
        val a = traj(pts = points(0.0).mapIndexed { i, p -> if (i == 3) p.copy(activity = 0.0100000) else p })
        val b = traj(pts = points(0.0).mapIndexed { i, p -> if (i == 3) p.copy(activity = 0.0100001) else p })
        assertNotEquals(a.contentHash, b.contentHash)
    }

    /** KP-46: der Golden-Hash darf nach Exportquantisierung gleich sein. */
    @Test
    fun `KP-46 Golden-Hash ist gegen winzige Unterschiede robust`() {
        val r1 = ok(input(driveMean = 1.0, driveLower = 1.0))
        val r2 = ok(input(driveMean = 1.0 + 1e-12, driveLower = 1.0 + 1e-12))
        assertEquals(Canonical.goldenOutputHash(r1), Canonical.goldenOutputHash(r2))
    }

    /** KP-47: -0.0 und +0.0 sind in beiden kanonischen Formen gleich. */
    @Test
    fun `KP-47 negative Null ist gleich positiver Null`() {
        val a = traj(pts = points(0.0).map { it.copy(activity = 0.0) })
        val b = traj(pts = points(0.0).map { it.copy(activity = -0.0) })
        assertEquals(a.contentHash, b.contentHash)
    }

    /** KP-26: eine als Actual etikettierte Virtual-Reihe ist NICHT hashgleich. */
    @Test
    fun `KP-26 Lineage-Art ist Teil des gehashten Payloads`() {
        val actual = traj()
        val virtual = VirtualTrajectoryFactory.of(
            InsulinLineage.VirtualController("vp", 1L, "m1"), points(0.0), t0, model(), "iobcalc1",
        )
        assertNotEquals(actual.contentHash, virtual.contentHash)
        assertEquals("ACTUAL", actual.lineage.lineageKind)
        assertEquals("VIRTUAL", virtual.lineage.lineageKind)
    }

    // ---- Determinismus und Semantik ---------------------------------------

    /** KP-1: gleiche Eingabe -> gleiche Bahn. */
    @Test
    fun `KP-1 Integration ist deterministisch`() {
        val a = ok(input(driveMean = 0.7, driveLower = 0.3, activity = 0.008))
        val b = ok(input(driveMean = 0.7, driveLower = 0.3, activity = 0.008))
        assertEquals(Canonical.goldenOutputHash(a), Canonical.goldenOutputHash(b))
    }

    /** KP-35: die Basislinie ist Rueckkehr zum Profilbasal, nicht Pumpenstopp. */
    @Test
    fun `KP-35 Basislinie ist als Profilbasal-Rueckkehr benannt`() {
        assertEquals("predNoFurtherNetDeviation", ok(input()).baselineSemantics)
    }

    @Test
    fun `KP-30 nicht finite Eingabe ergibt Ablehnung`() {
        val bad = traj(pts = points(0.0).mapIndexed { i, p -> if (i == 5) p.copy(activity = Double.NaN) else p })
        assertEquals(PredictorReason.NON_FINITE_INPUT, rejected(input(trajectory = bad)).reason)
    }

    @Test
    fun `nicht monotone Zeitstempel ergeben Ablehnung`() {
        val bad = traj(pts = points(0.0).toMutableList().also { it[7] = it[7].copy(timeMs = it[6].timeMs) })
        assertEquals(PredictorReason.NON_MONOTONIC_TIMESTAMPS, rejected(input(trajectory = bad)).reason)
    }

    /** G4: die oeffentliche API liefert keinerlei Dosierentscheidung. */
    @Test
    fun `G4 Ergebnis enthaelt keine Dosis-, Raten- oder Dauerfelder`() {
        val fields = PredictorResult::class.java.declaredFields.map { it.name.lowercase() }
        listOf("smb", "bolus", "tbr", "rate", "duration", "insulinreq", "dose").forEach { forbidden ->
            assertTrue(fields.none { it.contains(forbidden) }) { "verbotenes Feld: $forbidden" }
        }
    }

    @Test
    fun `Bahn beginnt bei anchor plus 1 min und endet am Horizont`() {
        val r = ok(input(horizon = 90))
        assertEquals(90, r.points.size)
        assertEquals(1, r.points.first().offsetMin)
        assertEquals(t0 + 60_000L, r.points.first().tsMs)
        assertEquals(90, r.points.last().offsetMin)
        assertTrue(abs(r.inputSkewMs) < 1L)
    }

    // ---- Fail-closed (R74-F5/F6) -----------------------------------------

    @Test
    fun `leeres Array wirft nicht, sondern lehnt ab`() {
        val empty = ActualTrajectoryFactory.of(
            InsulinLineage.ActualTreatment("d", "s", "m"), emptyList(), t0, model(), "c",
        )
        assertEquals(PredictorReason.ARRAY_TOO_SHORT, rejected(input(trajectory = empty)).reason)
    }

    @Test
    fun `Horizont 0 oder negativ wird abgelehnt`() {
        assertEquals(PredictorReason.ARRAY_TOO_SHORT, rejected(input(horizon = 0)).reason)
        assertEquals(PredictorReason.ARRAY_TOO_SHORT, rejected(input(horizon = -5)).reason)
    }

    @Test
    fun `nicht finiter Anker-BG wird abgelehnt statt NaN zu erzeugen`() {
        assertEquals(PredictorReason.NON_FINITE_INPUT, rejected(input(bg = Double.NaN)).reason)
        assertEquals(PredictorReason.NON_FINITE_INPUT, rejected(input(bg = Double.POSITIVE_INFINITY)).reason)
    }

    @Test
    fun `nicht finites basalIob wird abgelehnt`() {
        val bad = traj(pts = points(0.0).mapIndexed { i, p -> if (i == 2) p.copy(basalIob = Double.NaN) else p })
        assertEquals(PredictorReason.NON_FINITE_INPUT, rejected(input(trajectory = bad)).reason)
    }

    /** NaN entkommt jedem Bereichsvergleich: `<` und `>` sind beide false.
     *  Ohne eigene Endlichkeitspruefung landete NaN in der Bahn. */
    @Test
    fun `NaN-ISF rutscht nicht durch den Bereichsvergleich`() {
        assertEquals(
            PredictorReason.ISF_OUT_OF_BOUNDS,
            rejected(input(isfSlots = isf(Double.NaN))).reason,
        )
    }

    /** Ein spaeterer 6-min-Abstand blieb bisher unerkannt, weil nur Monotonie
     *  geprueft wurde. Verglichen wird gegen den ERSTEN Abstand, damit der Kern
     *  keine AAPS-Konstante festschreibt. */
    @Test
    fun `spaetere Rasterabweichung wird erkannt`() {
        val pts = points(0.0).toMutableList()
        for (i in 20 until pts.size) pts[i] = pts[i].copy(timeMs = pts[i].timeMs + 60_000L)
        val bad = traj(pts = pts)
        val r = rejected(input(trajectory = bad))
        assertEquals(PredictorReason.GRID_MISMATCH, r.reason)
        assertTrue(r.detail.contains("360000")) { "erste Abweichung soll benannt sein: ${r.detail}" }
    }

    @Test
    fun `sauberes Raster wird nicht faelschlich abgelehnt`() {
        assertTrue(TrajectoryCore.predict(input()) is PredictorOutcome.Ok)
    }

    // ---- C10: vorzeichenbewusster Zerfall (Codex H5) -----------------------

    private fun tau(min: Double) = DriveDecayModel.ExponentialDecay(min)

    /**
     * DER FEHLER, den C10 beschreibt: im Rebound-Fenster wird tau von 60 auf 15
     * gekuerzt. Vorzeichenblind angewandt verkleinert die Kuerzung auch einen
     * NEGATIVEN Antrieb - die untere Bahn steigt von ~68 auf ~105, der
     * Hypo-Schutz wird ausgerechnet NACH einem Tief schwaecher.
     *
     * Der erste assert haelt den Fehler fest, der zweite den Fix.
     */
    @Test
    fun `C10 die Rebound-Kuerzung darf eine negative Bahn nicht anheben`() {
        val long = input(driveMean = -1.0, driveLower = -1.0, horizon = 120)
        val blind = long.copy(decay = tau(15.0))
        val signAware = blind.copy(decayNegativeDrive = tau(60.0))

        assertTrue(ok(blind).minLowerBg > ok(long).minLowerBg + 10.0) {
            "vorzeichenblind muesste die Bahn deutlich anheben"
        }
        assertEquals(ok(long).minLowerBg, ok(signAware).minLowerBg, 1e-9)
        assertTrue(ok(signAware).minLowerBg <= ok(long).minLowerBg + 1e-9)
    }

    /** Die Kuerzung bleibt fuer POSITIVEN Antrieb voll wirksam - sie ist dort
     *  konservativ und genau dafuer gebaut (Vorfaelle #5/#6). */
    @Test
    fun `C10 fuer positiven Antrieb bleibt die Kuerzung wirksam`() {
        val long = input(driveMean = 1.0, driveLower = 1.0, horizon = 120)
        val shortened = long.copy(decay = tau(15.0), decayNegativeDrive = tau(60.0))
        assertTrue(ok(shortened).bgAtHorizonMean < ok(long).bgAtHorizonMean - 10.0)
    }

    /** Die Einseitigkeit haengt nicht an der Disziplin des Aufrufers: ein
     *  SCHNELLERES Negativ-Modell kann den Schutz nicht verkuerzen. */
    @Test
    fun `C10 ein schnelleres Negativ-Modell kann den Schutz nicht verkuerzen`() {
        val base = input(driveMean = -1.0, driveLower = -1.0, horizon = 120)
        val bad = base.copy(decayNegativeDrive = tau(10.0))
        assertEquals(ok(base).minLowerBg, ok(bad).minLowerBg, 1e-9)
    }

    /** KP-33 bleibt gueltig, auch wenn beide Bahnen verschiedene Faktoren
     *  bekommen: lower darf mean nie ueberholen. */
    @Test
    fun `C10 lower bleibt auch bei gemischten Vorzeichen unter mean`() {
        val r = ok(input(driveMean = 0.5, driveLower = -0.5).copy(decay = tau(15.0), decayNegativeDrive = tau(60.0)))
        r.points.forEach { assertTrue(it.lowerBg <= it.meanBg) }
    }

    /** Ohne Negativ-Modell ist die Rechnung unveraendert vorzeichenblind. */
    @Test
    fun `C10 ohne Negativ-Modell aendert sich nichts`() {
        val a = ok(input(driveMean = -1.0, driveLower = -1.0).copy(decay = tau(15.0)))
        val b = ok(input(driveMean = -1.0, driveLower = -1.0).copy(decay = tau(15.0), decayNegativeDrive = null))
        assertEquals(Canonical.goldenOutputHash(a), Canonical.goldenOutputHash(b))
    }

    // ---- C2: prior-freie Zwillingsbahn (Codex H2) --------------------------

    @Test
    fun `C2 die prior-freie Bahn liegt nie ueber der gehobenen und ist ohne Prior identisch`() {
        val plain = ok(input(driveMean = 1.0, driveLower = -0.2))
        plain.points.forEach { assertEquals(it.lowerBg, it.safetyLowerBg, 0.0) }
        assertEquals(plain.minLowerBg, plain.minSafetyLowerBg, 0.0)
        assertEquals(plain.bgAtHorizonLower, plain.bgAtHorizonSafetyLower, 0.0)

        val lifted = ok(input(driveMean = 1.0, driveLower = 0.5, driveLowerPriorFree = -0.2))
        lifted.points.forEach { assertTrue(it.safetyLowerBg <= it.lowerBg) }
        assertTrue(lifted.minLowerBg > plain.minLowerBg)
        // Das Zeugnis ist Punkt fuer Punkt das der Bahn OHNE Prior.
        lifted.points.forEachIndexed { i, p -> assertEquals(plain.points[i].lowerBg, p.safetyLowerBg, 1e-9) }
        assertEquals(plain.minLowerBg, lifted.minSafetyLowerBg, 1e-9)
        assertEquals(plain.bgAtHorizonLower, lifted.bgAtHorizonSafetyLower, 1e-9)
    }

    /** Ein "Prior", der die untere Bahn SENKEN wuerde, ist kein Prior - er
     *  waere eine zweite, unkontrollierte Bahn. */
    @Test
    fun `C2 ein prior-freier Antrieb ueber der unteren Bahn wird abgewiesen`() {
        assertThrows<IllegalArgumentException> { DriveEstimate(1.0, 0.2, 0.8, "m", 0.5) }
        assertThrows<IllegalArgumentException> { DriveEstimate(1.0, 0.2, 0.8, "m", Double.NaN) }
    }
}

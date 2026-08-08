package app.aaps.fuse.core.predictor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C3 (Codex-Adjudication bae885f1, Abschnitt "C3 interpretation", K4 Punkt 17):
 * die TRANSPORTMENGE GEHOERT IN DIE BAHN, nicht nur ins Budget.
 *
 * Gemessen an Tonis Medtrum (765 SMBs): Sichtbarkeits-Latenz p50 15 s, p90 56 s,
 * p99 175 s, MAX 854 s. In diesen 1 bis ~15 Zyklen glaubten Guard und Schwanz,
 * die bereits publizierte Menge habe KEINE Zukunftswirkung - abgezogen war sie
 * nur von den Headrooms (Bestandskappe), nicht von der Bahn (Glukosezeugnis).
 *
 * Der Uebergang ist ATOMAR ueber EINE Zahl geregelt: sobald die Behandlung im
 * IOB sichtbar ist, faellt `transportCommitmentU` im Ledger auf 0 und die Bahn
 * darf sie nicht mehr mitrechnen - genau das prueft
 * [`Commitment 0 ergibt bitgleich die alte Bahn`].
 */
class PendingTransportTrajectoryTest {

    private val t0 = 1_700_000_000_000L
    private val isfV = 90.0

    /**
     * Absichtlich EINFACH: konstante Aktivitaet ueber [supportMin] Minuten, also
     * Gesamtwirkung `amountU * isf` mg/dl. Die echte Wirkungskurve ist im
     * Einheitskern geprueft; hier geht es um die EINRECHNUNG.
     */
    private class FlatPending(
        override val amountU: Double,
        override val deliveryTs: Long,
        val supportMin: Int = 240,
        val sign: Double = 1.0,
    ) : PendingInsulinEffect {

        val supportEndTs get() = deliveryTs + supportMin * 60_000L
        override fun covers(tsMs: Long) = tsMs <= supportEndTs
        override fun activityAt(tsMs: Long): Double =
            if (tsMs < deliveryTs || tsMs > supportEndTs) 0.0 else sign * amountU / supportMin
        override fun iobAt(tsMs: Long): Double = when {
            tsMs < deliveryTs  -> amountU
            tsMs > supportEndTs -> 0.0
            else               -> amountU * (1.0 - (tsMs - deliveryTs) / (supportMin * 60_000.0))
        }
    }

    private fun traj() = ActualTrajectoryFactory.of(
        InsulinLineage.ActualTreatment("dev", "snap", "m1"),
        (0 until 60).map { IobPoint(t0 + it * 5 * 60_000L, 1.0, 0.0, 0.0) },
        t0, InsulinModelProvenance("rapid", 9.0, 75, "test"), "iobcalc1",
    )

    private fun input(pending: PendingInsulinEffect?, horizon: Int = 120) = PredictorInput(
        predictionAnchorTs = t0,
        bgAtAnchor = 150.0,
        // Prior-Hub im Spiel: die synthetische Dosis muss ALLE DREI Bahnen
        // senken, auch die prior-freie Sicherheitsbahn.
        drive = DriveEstimate(0.5, 0.2, null, "test-v1", 0.1),
        decay = DriveDecayModel.ExponentialDecay(60.0),
        trajectory = traj(),
        isfSlots = listOf(IsfSlot(t0 - 86_400_000L, t0 + 86_400_000L, isfV)),
        horizonMin = horizon,
        pending = pending,
    )

    private fun ok(pending: PendingInsulinEffect?, horizon: Int = 120): PredictorResult =
        (TrajectoryCore.predict(input(pending, horizon)) as PredictorOutcome.Ok).result

    // ---- Die Wirkung steht in der Bahn -----------------------------------

    @Test
    fun `die Transportmenge senkt Mittelbahn, Unterbahn UND die prior-freie Bahn`() {
        val ohne = ok(null)
        val mit = ok(FlatPending(0.2, t0))
        assertTrue(mit.bgAtHorizonMean < ohne.bgAtHorizonMean) { "Mittelbahn unveraendert" }
        assertTrue(mit.bgAtHorizonLower < ohne.bgAtHorizonLower) { "Unterbahn unveraendert" }
        assertTrue(mit.bgAtHorizonSafetyLower < ohne.bgAtHorizonSafetyLower) { "prior-freie Bahn unveraendert" }
        assertTrue(mit.minSafetyLowerBg < ohne.minSafetyLowerBg)
        // 0,2 U auf 240 min flach, ISF 90: in 120 min die Haelfte -> 9 mg/dl.
        assertEquals(9.0, ohne.bgAtHorizonMean - mit.bgAtHorizonMean, 1e-9)
    }

    /** DIE DOPPELZAEHLUNGS-PROBE. Sobald die Behandlung im IOB sichtbar ist,
     *  faellt das Commitment auf 0 - und dann muss die Bahn BITGLEICH die alte
     *  sein, sonst wuerde dieselbe Einheit zweimal wirken. */
    @Test
    fun `Commitment 0 ergibt bitgleich die alte Bahn`() {
        val ohne = ok(null)
        val nullMenge = ok(FlatPending(0.0, t0))
        assertEquals(ohne.points.size, nullMenge.points.size)
        for (i in ohne.points.indices) {
            assertEquals(ohne.points[i].meanBg, nullMenge.points[i].meanBg, 0.0)
            assertEquals(ohne.points[i].lowerBg, nullMenge.points[i].lowerBg, 0.0)
            assertEquals(ohne.points[i].safetyLowerBg, nullMenge.points[i].safetyLowerBg, 0.0)
        }
        assertEquals(0.0, nullMenge.pendingTransportU, 0.0)
        assertEquals(0.0, nullMenge.pendingDropAtHorizonMgdl, 0.0)
    }

    /** EINSEITIGKEIT: mehr unterwegs kann die Bahn nur senken, nie heben. */
    @Test
    fun `mehr Transportmenge kann die Bahn nie anheben`() {
        var vorher = ok(null).minSafetyLowerBg
        for (u in listOf(0.05, 0.10, 0.20, 0.50, 1.0)) {
            val jetzt = ok(FlatPending(u, t0)).minSafetyLowerBg
            assertTrue(jetzt <= vorher + 1e-12) { "u=$u hob die Sicherheitsbahn" }
            vorher = jetzt
        }
    }

    @Test
    fun `vor der Lieferung ist die Wirkung exakt null`() {
        // Lieferung 20 min nach dem Anker. points[i] traegt die Minute i+1;
        // alles VOR der Lieferminute muss unveraendert sein.
        val ohne = ok(null)
        val spaet = ok(FlatPending(0.3, t0 + 20 * 60_000L))
        for (i in 0 until 19) assertEquals(ohne.points[i].meanBg, spaet.points[i].meanBg, 1e-12)
        // Die LIEFERMINUTE selbst rechnet nach der rechten Regel voll mit -
        // eine Ueberschaetzung um Sekundenbruchteile, also die konservative
        // Seite. Bei den echten Modellen ist die Aktivitaet dort ohnehin 0.
        assertTrue(spaet.points[19].meanBg < ohne.points[19].meanBg)
        assertTrue(spaet.points[119].meanBg < ohne.points[119].meanBg)
    }

    @Test
    fun `die eingerechnete Menge und ihr Hub am Horizont stehen im Ergebnis`() {
        val r = ok(FlatPending(0.2, t0))
        assertEquals(0.2, r.pendingTransportU, 1e-12)
        assertEquals(9.0, r.pendingDropAtHorizonMgdl, 1e-9)
    }

    // ---- Fail-closed statt stiller Null ----------------------------------

    /** Codex Abschnitt 10: eine fehlende Groesse darf NICHT wie 0 behandelt
     *  werden, wenn 0 weniger konservativ waere. Ein Kern, der das
     *  Bewertungsfenster nicht deckt, wuerde genau das tun. */
    @Test
    fun `ein Modell, das den Horizont nicht deckt, wird benannt abgelehnt`() {
        val r = TrajectoryCore.predict(input(FlatPending(0.2, t0, supportMin = 60)))
        assertTrue(r is PredictorOutcome.Rejected)
        assertEquals(PredictorReason.PENDING_MODEL_TOO_SHORT, (r as PredictorOutcome.Rejected).reason)
    }

    /** Eine negative Aktivitaet wuerde die Bahn ANHEBEN und die Menge sich
     *  selbst rechtfertigen lassen - der Kern nimmt sie nicht an. */
    @Test
    fun `eine anhebende Pending-Wirkung wird abgelehnt`() {
        val r = TrajectoryCore.predict(input(FlatPending(0.2, t0, sign = -1.0)))
        assertTrue(r is PredictorOutcome.Rejected)
        assertEquals(PredictorReason.NON_FINITE_INPUT, (r as PredictorOutcome.Rejected).reason)
    }

    @Test
    fun `eine nicht endliche Menge wird abgelehnt`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.5)) {
            val r = TrajectoryCore.predict(input(FlatPending(bad, t0)))
            assertTrue(r is PredictorOutcome.Rejected) { "amount=$bad wurde angenommen" }
        }
    }
}

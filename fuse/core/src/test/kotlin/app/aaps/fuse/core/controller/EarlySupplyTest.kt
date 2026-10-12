package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Die fruehe Versorgung (E-034 v2): reine Rechnung. Synthetische Kurve und Modellwerte. */
class EarlySupplyTest {

    private val gipfel = 45.0
    private val wirkdauer = 540.0
    private val wirkung: (Double) -> Double = { t -> EarlySupply.orefActionFraction(t, gipfel, wirkdauer) }

    /** Synthetische Kurve: gleichmaessig 1 U je 30 min bis 120 min. */
    private val kurve = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0))

    @Test
    fun `die Insulinwirkung beginnt bei 0, steigt monoton und ist am Ende vollstaendig`() {
        assertEquals(0.0, wirkung(0.0), 0.0)
        var vorher = 0.0
        for (t in 5..540 step 5) {
            val c = wirkung(t.toDouble())
            assertTrue(c + 1e-12 >= vorher, "nicht monoton bei $t")
            vorher = c
        }
        assertEquals(1.0, wirkung(540.0), 1e-9)
        // Bei Gipfel 45 und neun Stunden Wirkdauer wirkt nach einer Stunde etwa zwei Fuenftel.
        assertEquals(0.39, wirkung(60.0), 0.02)
    }

    @Test
    fun `das Budget ist die fruehe Gabe, die die Kurve ab 30 min rechtzeitig deckt - skaliert und gedeckelt`() {
        val roh = (30..120 step 5).maxOf { t -> kurve.at(t.toDouble()) / wirkung(t.toDouble()) }
        assertEquals(roh, EarlySupply.budgetU(kurve, 120, 1.0, 20.0, wirkung), 1e-9)
        assertEquals(1.5 * roh, EarlySupply.budgetU(kurve, 120, 1.5, 20.0, wirkung), 1e-9)
        assertEquals(3.0, EarlySupply.budgetU(kurve, 120, 1.0, 3.0, wirkung), 1e-9)
        assertEquals(0.0, EarlySupply.budgetU(kurve, 120, 0.0, 20.0, wirkung), 0.0)
        // Die fruehe Gabe ist groesser als die Wirkung bis 120 min, weil Insulin verzoegert wirkt.
        assertTrue(roh > kurve.endU)
    }

    @Test
    fun `am Druck ohne vorhandenes Insulin ist die Anforderung das ungedeckelte Budget`() {
        val x = EarlySupply.demandU(kurve, 120, 0.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung)
        assertEquals(EarlySupply.budgetU(kurve, 120, 1.0, 20.0, wirkung), x, 1e-9)
    }

    @Test
    fun `freies vorhandenes Insulin senkt die Anforderung, Insulin fuer den Zielabstand nicht`() {
        val ohne = EarlySupply.demandU(kurve, 120, 0.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung)
        // 2 U an Bord, deren Wirkung wie eine Gabe jetzt verlaeuft.
        val vorhanden: (Double) -> Double = { t -> 2.0 * wirkung(t) }
        val frei = EarlySupply.freeIobU(2.0, 100.0, 100.0, 50.0)
        assertEquals(2.0, frei, 1e-12)
        val mitFrei = EarlySupply.demandU(kurve, 120, 0.0, 1.0, 2.0, frei, vorhanden, wirkung)
        assertEquals(ohne - 2.0, mitFrei, 1e-9)
        // Liegt der Zucker 100 mg/dl ueber Ziel (ISF 50 = 2 U), gehoert das ganze IOB dem Zielabstand.
        val gebunden = EarlySupply.freeIobU(2.0, 200.0, 100.0, 50.0)
        assertEquals(0.0, gebunden, 0.0)
        assertEquals(ohne, EarlySupply.demandU(kurve, 120, 0.0, 1.0, 2.0, gebunden, vorhanden, wirkung), 1e-9)
    }

    @Test
    fun `weniger als 30 Minuten bis zum Ende gibt es nichts mehr rechtzeitig abzudecken`() {
        assertEquals(0.0, EarlySupply.demandU(kurve, 120, 95.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung), 0.0)
        assertTrue(EarlySupply.demandU(kurve, 120, 85.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung) > 0.0)
    }

    @Test
    fun `die Anpassung verkleinert nur, nie vor 20 Minuten und nie ueber den Skalierer`() {
        assertEquals(1.5, EarlySupply.effectiveScale(1.5, 10.0, 0.0, true, kurve), 0.0)
        // Bei 30 min erwartet 1 U, beobachtet 0,5 U: halber Skalierer.
        assertEquals(0.75, EarlySupply.effectiveScale(1.5, 30.0, 0.5, true, kurve), 1e-9)
        // Mehr als erwartet: bleibt beim Skalierer.
        assertEquals(1.5, EarlySupply.effectiveScale(1.5, 30.0, 3.0, true, kurve), 0.0)
        // Ungueltige Beobachtung: unveraendert (die Ruecknahme bei +30 ist eigens zustaendig).
        assertEquals(1.5, EarlySupply.effectiveScale(1.5, 30.0, 0.0, false, kurve), 0.0)
    }

    @Test
    fun `Portionen - Anfangsanteil und jede weitere Portion nehmen das Kleinste, nie negativ`() {
        assertEquals(3.0, EarlySupply.initialPortionU(6.0, 0.5, 10.0), 1e-12)
        assertEquals(2.0, EarlySupply.initialPortionU(6.0, 0.5, 2.0), 1e-12)
        assertEquals(0.0, EarlySupply.initialPortionU(6.0, 0.5, -1.0), 0.0)
        assertEquals(1.0, EarlySupply.portionU(5.0, 4.0, 1.0, 3.0), 1e-12)
        assertEquals(0.5, EarlySupply.portionU(5.0, 0.5, 1.0, 3.0), 1e-12)
        assertEquals(0.0, EarlySupply.portionU(5.0, 4.0, 1.0, 0.0), 0.0)
    }

    @Test
    fun `die Kante mit Erwartung zaehlt positiven Antrieb nicht doppelt und behaelt das Fallen`() {
        // Ohne Antrieb: die ganze Erwartung kommt hinzu.
        assertEquals(130.0, EarlySupply.liftedEdge(100.0, 100.0, 30.0), 1e-12)
        // Positiver Antrieb schon in der Kante: nur, was die Erwartung darueber hinaus bringt.
        assertEquals(130.0, EarlySupply.liftedEdge(120.0, 100.0, 30.0), 1e-12)
        assertEquals(140.0, EarlySupply.liftedEdge(140.0, 100.0, 30.0), 1e-12)
        // Gemessenes Fallen bleibt voll erhalten.
        assertEquals(110.0, EarlySupply.liftedEdge(80.0, 100.0, 30.0), 1e-12)
        // Keine Erwartung: unveraendert.
        assertEquals(80.0, EarlySupply.liftedEdge(80.0, 100.0, 0.0), 0.0)
    }

    @Test
    fun `Guard und Schwanz sehen die Mahlzeit hoechstens wie eingestellt`() {
        assertEquals(1.0, EarlySupply.guardScale(1.8), 0.0)
        assertEquals(0.6, EarlySupply.guardScale(0.6), 0.0)
        assertEquals(0.0, EarlySupply.guardScale(-1.0), 0.0)
        assertEquals(0.0, EarlySupply.guardScale(Double.NaN), 0.0)
        // Anstieg nach der Kurve endet am Ende der Vorschau.
        val bis120 = EarlySupply.expectedRiseMgdl(kurve, 120, 100.0, 60.0, 1.0, 50.0)
        assertEquals((4.0 - kurve.at(100.0)) * 50.0, bis120, 1e-9)
        assertEquals(0.0, EarlySupply.expectedRiseMgdl(kurve, 60, 70.0, 30.0, 1.0, 50.0), 0.0)
    }

    @Test
    fun `die Bahn mit Erwartung hebt jede Punktkante, der Anker bleibt`() {
        val anker = 1_700_000_000_000L
        // Kein Antrieb, Insulin senkt 0,5 mg/dl je Minute; die untere Kante liegt 0,2 mg/dl je Minute tiefer.
        val punkte = (1..120).map { i ->
            app.aaps.fuse.core.predictor.TrajectoryPoint(
                offsetMin = i, tsMs = anker + i * 60_000L, meanBg = 120.0 - 0.5 * i, lowerBg = 120.0 - 0.7 * i,
                bgiRate = -0.5, driveMean = 0.0, driveLower = -0.2, lowerBgPriorFree = 120.0 - 0.7 * i,
            )
        }
        val bahn = app.aaps.fuse.core.predictor.PredictorResult(
            points = punkte, predictionAnchorTs = anker, bgAtAnchor = 120.0,
            minMeanBg = punkte.last().meanBg, minLowerBg = punkte.last().lowerBg, timeToMinLowerMin = 120,
            bgAtHorizonMean = punkte.last().meanBg, bgAtHorizonLower = punkte.last().lowerBg,
            lineageKind = "VIRTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 240.0, iobArrayGridMin = 1.0, modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
            minLowerBgPriorFree = punkte.last().lowerBg, bgAtHorizonLowerPriorFree = punkte.last().lowerBg,
        )
        val mit = EarlySupply.withExpectation(bahn, 0.0, kurve, 120, 1.0, 50.0)
        assertEquals(120.0, mit.bgAtAnchor, 0.0)
        for (p in mit.points) {
            // Kurve 1 U je 30 min, ISF 50: Anstieg 5/3 mg/dl je Minute; die Kante faellt um 0,7.
            val erwartet = 120.0 - 0.7 * p.offsetMin + 50.0 * kurve.at(p.offsetMin.toDouble())
            assertEquals(erwartet, p.safetyLowerBg, 1e-9)
            assertTrue(p.lowerBg >= p.safetyLowerBg && p.meanBg >= p.lowerBg)
        }
        assertEquals(120.0, mit.minSafetyLowerBg, 1e-9)
        assertEquals(mit.points.last().safetyLowerBg, mit.bgAtHorizonSafetyLower, 0.0)
        // Schwanzkante am Horizont: dieselbe Regel.
        assertEquals(
            mit.points.last().safetyLowerBg,
            EarlySupply.tailLowerWithExpectation(bahn.bgAtHorizonSafetyLower, bahn, 0.0, kurve, 120, 1.0, 50.0), 1e-9,
        )
    }

    @Test
    fun `Einstellungen ausserhalb der Grenzen schalten die fruehe Versorgung aus`() {
        assertNotNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.5, 4.0, 10))
        assertNull(EarlySupply.Settings.of(2.5, 0.5, 8.0, 0.5, 1.5, 4.0, 10))
        assertNull(EarlySupply.Settings.of(1.0, 0.5, 25.0, 0.5, 1.5, 4.0, 10))
        assertNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 1.2, 1.5, 4.0, 10))
        assertNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.5, 4.0, 2))
        assertNull(EarlySupply.Settings.of(Double.NaN, 0.5, 8.0, 0.5, 1.5, 4.0, 10))
    }
}

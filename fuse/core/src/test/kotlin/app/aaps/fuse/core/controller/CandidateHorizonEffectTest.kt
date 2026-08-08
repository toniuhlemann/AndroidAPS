package app.aaps.fuse.core.controller

import app.aaps.fuse.core.insulin.InsulinSample
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.insulin.UnitInsulinSampler
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.core.predictor.IsfSlot
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C4b: der Schwanz braucht die Wirkung der GERADE BESCHLOSSENEN Menge AM
 * HAFTUNGSHORIZONT - dieselbe Integration wie die Kandidatensuche, nur an
 * einem anderen Punkt abgelesen.
 *
 * Warum nicht im Schwanz selbst gerechnet: [TailLiability] ist eine reine
 * Formel ueber Zahlen und kennt weder Kern noch Bahn. Die Wirkung gehoert dahin,
 * wo die Integrationsregel schon steht - sonst gaebe es zwei Regeln fuer
 * dieselbe Groesse.
 */
class CandidateHorizonEffectTest {

    private val anchor = 1_700_000_000_000L
    private val isf = 100.0
    private val model = InsulinModelProvenance("TEST_FLAT", 2.0, 60, "test")

    /** Konstante Aktivitaet ueber 120 min: 1 U -> 100 mg/dl, von Hand nachrechenbar. */
    private fun kernel(deliveryTs: Long = anchor): UnitInsulinKernel {
        val sampler = UnitInsulinSampler { doseU, offsetMin ->
            if (offsetMin >= 120) InsulinSample(0.0, 0.0)
            else InsulinSample(doseU * (1.0 - offsetMin / 120.0), doseU / 120.0)
        }
        return (UnitInsulinKernelBuilder.build(sampler, deliveryTs, model, "flat") as KernelOutcome.Ok).kernel
    }

    private fun prediction(horizonMin: Int = 120): PredictorResult {
        val pts = (1..horizonMin).map { TrajectoryPoint(it, anchor + it * 60_000L, 200.0, 200.0, 0.0, 0.0, 0.0) }
        return PredictorResult(
            points = pts, predictionAnchorTs = anchor, bgAtAnchor = 200.0,
            minMeanBg = 200.0, minLowerBg = 200.0, timeToMinLowerMin = 0,
            bgAtHorizonMean = 200.0, bgAtHorizonLower = 200.0,
            lineageKind = "VIRTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 240.0, iobArrayGridMin = 1.0, modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    private val isfSlots = listOf(IsfSlot(anchor - 3_600_000L, anchor + 10 * 3_600_000L, isf))

    private val band = CandidateSearch.Band(
        releaseTargetLowMgdl = 100.0, releaseTargetHighMgdl = 140.0, demandDeadbandMgdl = 10.0,
        guardFloorMgdl = 70.0, releaseHorizonMin = 30, liabilityHorizonMin = 120,
    )

    @Test
    fun `die Wirkung je Einheit am Haftungshorizont ist die aufsummierte Kernwirkung`() {
        val e = CandidateSearch.effectPerUAtLiabilityHorizon(prediction(), kernel(), isfSlots, band)
        assertNotNull(e)
        // Minuten 1..119 tragen je (1/120)*100*1 = 0,8333 mg/dl; bei Minute 120
        // ist der Kern ausgelaufen und traegt 0.
        assertEquals(119 * (100.0 / 120.0), e!!, 1e-9)
    }

    /** Sie muss zur Suche passen: was am Freigabehorizont steht, ist der
     *  Teilweg derselben Summe. */
    @Test
    fun `sie ist konsistent zur Wirkung am Freigabehorizont`() {
        val caps = CandidateSearch.Caps(5.0, 5.0, 5.0, 0.05, 1.0)
        val r = CandidateSearch.search(prediction(), kernel(), isfSlots, band, caps)
        val h = CandidateSearch.effectPerUAtLiabilityHorizon(prediction(), kernel(), isfSlots, band)!!
        assertTrue(h > r.effectPerUAtReleaseMgdl!!) { "Haftungshorizont muss weiter sein als Freigabe" }
        assertEquals(30 * (100.0 / 120.0), r.effectPerUAtReleaseMgdl!!, 1e-9)
    }

    /** Nicht berechenbar heisst `null`, NICHT 0 - eine 0 waere die Behauptung
     *  "die Dosis wirkt am Horizont nicht" (Codex Abschnitt 10). */
    @Test
    fun `nicht berechenbar ergibt null statt einer stillen Null`() {
        // Kern deckt das Fenster nicht (Lieferung 60 min vor dem Anker ->
        // Traeger endet bei Anker+60).
        assertNull(
            CandidateSearch.effectPerUAtLiabilityHorizon(prediction(), kernel(anchor - 60 * 60_000L), isfSlots, band)
        )
        // Bahn zu kurz
        assertNull(CandidateSearch.effectPerUAtLiabilityHorizon(prediction(60), kernel(), isfSlots, band))
        // ISF-Luecke
        assertNull(CandidateSearch.effectPerUAtLiabilityHorizon(prediction(), kernel(), emptyList(), band))
    }
}

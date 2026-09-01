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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE HOECHSTE TRAGBARE BASALRATE - Vertrag und Grenzen.
 *
 * Der wichtigste Test ist [der gewaehlte Tick ist der groesste tragbare]:
 * er haelt fest, dass der NAECHSTE Pumpenschritt den Guard verletzt.
 * Ohne ihn koennte die Suche beliebig zu vorsichtig sein und niemand
 * wuerde es bemerken.
 */
class BasalRecoverySearchTest {

    private val anchor = 1_700_000_000_000L
    private val horizonMin = 120
    private val isf = 100.0

    /** Flaches Modell: 1 U wirkt ueber 240 min linear. Die Stuetzweite
     *  muss den Haftungshorizont AUCH FUER DIE SPAETESTE Minutenmenge
     *  decken - bei 120 min Horizont und 60 min TBR sind das 180 min. */
    private val model = InsulinModelProvenance("TEST_FLAT", 4.0, 60, "test")

    private fun kernel(deliveryTs: Long = anchor): UnitInsulinKernel {
        val sampler = UnitInsulinSampler { doseU, offsetMin ->
            if (offsetMin >= 240) InsulinSample(0.0, 0.0)
            else InsulinSample(doseU * (1.0 - offsetMin / 240.0), doseU / 240.0)
        }
        return (UnitInsulinKernelBuilder.build(sampler, deliveryTs, model, "flat") as KernelOutcome.Ok).kernel
    }

    private fun prediction(lowerAt: (Int) -> Double): PredictorResult {
        val pts = (1..horizonMin).map {
            TrajectoryPoint(it, anchor + it * 60_000L, lowerAt(it), lowerAt(it), 0.0, 0.0, 0.0)
        }
        return PredictorResult(
            points = pts, predictionAnchorTs = anchor, bgAtAnchor = lowerAt(0),
            minMeanBg = minOf(lowerAt(0), pts.minOf { it.meanBg }),
            minLowerBg = minOf(lowerAt(0), pts.minOf { it.lowerBg }),
            timeToMinLowerMin = 0, bgAtHorizonMean = pts.last().meanBg, bgAtHorizonLower = pts.last().lowerBg,
            lineageKind = "VIRTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 240.0, iobArrayGridMin = 1.0, modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )
    }

    private val isfSlots = listOf(IsfSlot(anchor - 3_600_000L, anchor + 10 * 3_600_000L, isf))

    private val band = CandidateSearch.Band(
        releaseTargetLowMgdl = 100.0, releaseTargetHighMgdl = 140.0,
        demandDeadbandMgdl = 10.0, guardFloorMgdl = 70.0,
        releaseHorizonMin = 30, liabilityHorizonMin = 120,
    )

    private fun suche(
        bahn: PredictorResult,
        profil: Double = 0.60,
        schritt: Double = 0.05,
        dauer: Int = 30,
        k: UnitInsulinKernel = kernel(),
    ) = BasalRecoverySearch.hoechsteSichereRate(
        prediction = bahn, kernel = k, isfSlots = isfSlots, band = band,
        scheduledBasalUPerH = profil, basalStepUPerH = schritt, tbrDurationMin = dauer,
    )

    // ---- DER VERTRAG -----------------------------------------------------

    @Test
    fun `der gewaehlte Tick ist der groesste tragbare - der naechste verletzt den Guard`() {
        // Eine Bahn, die knapp ueber dem Boden verlaeuft: hier MUSS die
        // Suche irgendwo zwischen 0 und Profil abschneiden.
        val r = suche(prediction { 78.0 })
        assertNull(r.reject)
        assertTrue(r.rateUPerH > 0.0) { "die Lage traegt etwas: ${r.rateUPerH}" }
        assertTrue(r.rateUPerH <= 0.60 + 1e-9)
        assertTrue(r.minLowerBeiRate >= band.guardFloorMgdl) {
            "die gewaehlte Rate MUSS die Bahn ueber dem Boden lassen: ${r.minLowerBeiRate}"
        }
        // DAS IST DER VERTRAG: der naechste Tick faellt durch.
        assertTrue(r.minLowerNaechsterTick < band.guardFloorMgdl) {
            "der naechsthoehere Tick MUSS den Guard verletzen, sonst war die Suche zu " +
                "vorsichtig: ${r.minLowerNaechsterTick}"
        }
    }

    @Test
    fun `eine grosszuegige Bahn wird durch das Profilbasal begrenzt, nicht durch den Guard`() {
        val r = suche(prediction { 300.0 })
        assertNull(r.reject)
        assertEquals(0.60, r.rateUPerH, 1e-9, "das Profil ist die Obergrenze")
        assertTrue(r.durchProfilBegrenzt) { "und das MUSS als solches ausgewiesen sein" }
    }

    @Test
    fun `eine Bahn am Boden traegt keine Rate - dann bleibt die Schutz-Null`() {
        val r = suche(prediction { 70.5 })
        assertNull(r.reject)
        assertEquals(0.0, r.rateUPerH, 1e-12)
    }

    @Test
    fun `eine Bahn UNTER dem Boden traegt erst recht nichts`() {
        val r = suche(prediction { 60.0 })
        assertEquals(0.0, r.rateUPerH, 1e-12)
    }

    // ---- DIE ZEITVERTEILUNG (Review-P0) ----------------------------------

    @Test
    fun `die verteilte Menge wird gegen eine SOFORTDOSIS geprueft - unabhaengige Referenz`() {
        // DER VORZEICHENTEST gegen eine Referenz, die diese Datei nicht
        // selbst gebaut hat: CandidateSearch.verifyGuardFloor prueft eine
        // EINZELDOSIS am Anker. Eine Sofortdosis wirkt im Fenster staerker
        // als dieselbe Menge ueber 30 min verteilt - also muss die
        // Gesamtmenge, die die verteilte Suche freigibt, GROESSER sein als
        // die groesste zulaessige Sofortdosis.
        //
        // Waere die Suche dagegen bei "alles am Fensterende" (der
        // fail-open-Fall), laege ihre Gesamtmenge noch weiter darueber -
        // die zweite Schranke unten faengt das.
        val bahn = prediction { 78.0 }
        val k = kernel()
        val r = suche(bahn, dauer = 30)
        val gesamtVerteilt = r.rateUPerH * 30.0 / 60.0

        // Groesste Sofortdosis, die der Guard traegt (auf demselben Raster).
        var sofortMax = 0.0
        var t = 1
        while (t <= 200) {
            val d = t * 0.05
            if (CandidateSearch.verifyGuardFloor(bahn, k, isfSlots, band, d) == null) sofortMax = d else break
            t++
        }
        assertTrue(sofortMax > 0.0) { "die Referenz muss ueberhaupt etwas zulassen" }
        assertTrue(gesamtVerteilt > sofortMax) {
            "verteilt wirkt schwaecher, also darf mehr Menge fliessen: " +
                "verteilt=$gesamtVerteilt sofort=$sofortMax"
        }
        // OBERE SCHRANKE: waere die ganze Menge erst am Fensterende
        // geliefert, wirkte sie im 30-min-Fenster gar nicht und die
        // Freigabe waere um ein Vielfaches groesser. Die verteilte Menge
        // muss deutlich darunter bleiben - hier: hoechstens das Doppelte
        // der Sofortdosis-Grenze.
        assertTrue(gesamtVerteilt <= 2.0 * sofortMax) {
            "die Verteilung darf die Wirkung nicht wegdefinieren: " +
                "verteilt=$gesamtVerteilt sofort=$sofortMax"
        }
    }

    @Test
    fun `eine laengere TBR-Dauer verteilt dieselbe Rate auf mehr Minuten`() {
        // Bei gleicher RATE bringt eine laengere Dauer mehr Gesamtmenge ins
        // Fenster - die tragbare Rate sinkt also (oder bleibt gleich).
        val bahn = prediction { 85.0 }
        val kurz = suche(bahn, dauer = 15).rateUPerH
        val lang = suche(bahn, dauer = 60).rateUPerH
        assertTrue(lang <= kurz) { "laenger = mehr Menge = weniger Rate: kurz=$kurz lang=$lang" }
    }

    // ---- FAIL-CLOSED ------------------------------------------------------

    @Test
    fun `unbrauchbare Eingaben ergeben eine benannte Ablehnung und Rate 0`() {
        val bahn = prediction { 200.0 }
        for ((was, r) in listOf(
            "Profil 0" to suche(bahn, profil = 0.0),
            "Profil NaN" to suche(bahn, profil = Double.NaN),
            "Schritt 0" to suche(bahn, schritt = 0.0),
            "Dauer 0" to suche(bahn, dauer = 0),
        )) {
            assertEquals(0.0, r.rateUPerH, 1e-12, was)
            assertEquals(BasalRecoverySearch.Reject.INVALID_INPUT, r.reject, was)
        }
    }

    @Test
    fun `ein zu kurzer Kern wird benannt, nicht stillschweigend gerechnet`() {
        // Eigenes Modell mit KURZER DIA - sonst baut der Builder die
        // Stuetzweite aus der DIA und fuellt mit Nullen auf; der Kern
        // waere dann formal lang genug.
        val kurzesModell = InsulinModelProvenance("TEST_SHORT", 0.5, 15, "test")
        val kurz = (UnitInsulinKernelBuilder.build(
            UnitInsulinSampler { doseU, offsetMin ->
                if (offsetMin >= 30) InsulinSample(0.0, 0.0)
                else InsulinSample(doseU * (1.0 - offsetMin / 30.0), doseU / 30.0)
            },
            anchor, kurzesModell, "kurz",
        ) as KernelOutcome.Ok).kernel
        val r = suche(prediction { 200.0 }, k = kurz)
        assertEquals(BasalRecoverySearch.Reject.MODEL_HORIZON_TOO_SHORT, r.reject)
        assertEquals(0.0, r.rateUPerH, 1e-12)
    }

    @Test
    fun `ein fehlender Haftungshorizont ergibt HORIZON_MISSING`() {
        val engesBand = band.copy(liabilityHorizonMin = 999)
        val r = BasalRecoverySearch.hoechsteSichereRate(
            prediction = prediction { 200.0 }, kernel = kernel(), isfSlots = isfSlots,
            band = engesBand, scheduledBasalUPerH = 0.6, basalStepUPerH = 0.05, tbrDurationMin = 30,
        )
        assertEquals(BasalRecoverySearch.Reject.HORIZON_MISSING, r.reject)
    }

    @Test
    fun `fehlende ISF-Slots ergeben ISF_SLOT_MISSING`() {
        val r = BasalRecoverySearch.hoechsteSichereRate(
            prediction = prediction { 200.0 }, kernel = kernel(), isfSlots = emptyList(),
            band = band, scheduledBasalUPerH = 0.6, basalStepUPerH = 0.05, tbrDurationMin = 30,
        )
        assertEquals(BasalRecoverySearch.Reject.ISF_SLOT_MISSING, r.reject)
    }

    // ---- MONOTONIE --------------------------------------------------------

    @Test
    fun `je tiefer die Bahn, desto kleiner die tragbare Rate`() {
        val hoch = suche(prediction { 200.0 }).rateUPerH
        val mittel = suche(prediction { 90.0 }).rateUPerH
        val tief = suche(prediction { 75.0 }).rateUPerH
        assertTrue(hoch >= mittel) { "hoch=$hoch mittel=$mittel" }
        assertTrue(mittel >= tief) { "mittel=$mittel tief=$tief" }
    }

    @Test
    fun `die Rate liegt immer auf dem Pumpenraster und nie ueber dem Profil`() {
        for (bg in listOf(72.0, 78.0, 85.0, 95.0, 140.0, 300.0)) {
            val r = suche(prediction { bg })
            val ticks = r.rateUPerH / 0.05
            assertEquals(Math.round(ticks).toDouble(), ticks, 1e-9) { "BG $bg: ${r.rateUPerH} nicht auf dem Raster" }
            assertTrue(r.rateUPerH <= 0.60 + 1e-9) { "BG $bg: ${r.rateUPerH} ueber dem Profil" }
        }
    }
}

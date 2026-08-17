package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TailLiabilityTest {

    private fun input(
        lowerBgAtH: Double = 120.0,
        existingIob: Double = 0.5,
        isfTail: Double = 50.0,
        floor: Double = 70.0,
        recovery: Double = 0.0,
    ) = TailLiability.Input(lowerBgAtH, existingIob, isfTail, floor, recovery)

    // ---- Formel ----------------------------------------------------------

    @Test
    fun `Budget ist der Abstand zur Untergrenze in Einheiten`() {
        // (120 - 70) / 50 = 1.0 U
        val r = TailLiability.evaluate(input())
        assertEquals(1.0, r.budgetU, 1e-12)
        assertEquals(0.5, r.headroomU, 1e-12)
        assertTrue(r.usable)
    }

    @Test
    fun `die Erholung vergroessert das Budget genau um ihren Betrag`() {
        val ohne = TailLiability.evaluate(input(recovery = 0.0)).budgetU
        val mit = TailLiability.evaluate(input(recovery = 0.3)).budgetU
        assertEquals(0.3, mit - ohne, 1e-12)
    }

    /** Ein hoeherer ISF macht das Budget KLEINER — deshalb ist das Maximum der
     *  beruehrten Bloecke die konservative Wahl, nicht der Mittelwert. */
    @Test
    fun `hoeherer ISF verkleinert das Budget`() {
        assertTrue(TailLiability.evaluate(input(isfTail = 80.0)).budgetU < TailLiability.evaluate(input(isfTail = 50.0)).budgetU)
    }

    @Test
    fun `unter der Untergrenze wird das Budget negativ`() {
        val r = TailLiability.evaluate(input(lowerBgAtH = 60.0))
        assertTrue(r.budgetU < 0.0)
        assertTrue(r.headroomU < 0.0)
    }

    // ---- Fail-closed ohne Wurf -------------------------------------------

    @Test
    fun `eine ungueltige Eingabe wirft nicht, sondern meldet sich unbrauchbar`() {
        for (bad in listOf(
            input(isfTail = 0.0), input(isfTail = -3.0), input(isfTail = Double.NaN),
            input(lowerBgAtH = Double.NaN), input(existingIob = Double.POSITIVE_INFINITY),
            input(recovery = Double.NaN),
        )) {
            val r = TailLiability.evaluate(bad)
            assertFalse(r.usable)
            assertNotNull(r.invalidReason)
        }
    }

    /** Der Vermerk MUSS an jedem Bericht haengen — auch am unbrauchbaren. */
    @Test
    fun `jeder Bericht traegt seinen Unvollstaendigkeitsvermerk`() {
        assertEquals(TailLiability.COMPLETENESS_STAGE1, TailLiability.evaluate(input()).completeness)
        assertEquals(TailLiability.COMPLETENESS_STAGE1, TailLiability.evaluate(input(isfTail = 0.0)).completeness)
        assertEquals(TailLiability.SOURCE_BASELINE, TailLiability.evaluate(input()).lowerBgAtHSource)
    }

    // ---- Zusammenspiel mit dem Regler ------------------------------------

    private fun pred(bgAt30: Double, minLower: Double = 120.0, atHorizonLower: Double = 120.0) =
        PredictorResult(
            points = (1..60).map { TrajectoryPoint(it, it * 60_000L, bgAt30, minLower, 0.0, 0.0, 0.0) },
            predictionAnchorTs = 0L, bgAtAnchor = bgAt30,
            minMeanBg = bgAt30, minLowerBg = minOf(bgAt30, minLower), timeToMinLowerMin = 30,
            bgAtHorizonMean = bgAt30, bgAtHorizonLower = atHorizonLower,
            lineageKind = "ACTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 235.0, iobArrayGridMin = 5.0,
            modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        )

    private fun state() = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = 1.0, bolusIobU = 1.0, basalIobU = 0.0,
        iobThU = 4.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 50.0,
        smbRatioCorrection = 0.5, smbRatioRise = 0.5,
        rSignedMgdlPerMin = null, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = 0.75, pumpBusy = false,
    )

    @Test
    fun `erschoepfter Schwanz blockt - aber ohne Zero-Temp`() {
        val tail = TailLiability.evaluate(input(lowerBgAtH = 120.0, existingIob = 2.0))
        val d = FuseController.decide(state(), pred(250.0), tail = tail, evidenceCreditActive = false, lowThreat = LowThreatGate.Verdict.NONE)
        assertEquals(0.0, d.smbU)
        assertEquals(FuseController.Block.TAIL, d.block)
        // NICHT ZERO_TEMP: ein Zero-Temp kann die bereits gelieferte Wirkung,
        // um die es hier geht, gar nicht zurueckholen.
        assertEquals(FuseController.TbrAction.NO_NEW_POSITIVE, d.tbr)
    }

    @Test
    fun `verbleibender Schwanzspielraum deckelt die Menge und wird beziffert`() {
        // Budget 1.0, IOB 0.9 -> Spielraum 0.1 U. Ohne Schwanz waere maxSmb 0.75
        // die bindende Grenze.
        val tail = TailLiability.evaluate(input(existingIob = 0.9))
        val d = FuseController.decide(state(), pred(400.0), tail = tail, evidenceCreditActive = false, lowThreat = LowThreatGate.Verdict.NONE)
        assertEquals("tailHeadroom", d.bindingLimit)
        assertTrue(d.smbU <= 0.1 + 1e-9)
        assertTrue(d.tailCostU > 0.0) { "der Onset-Verlust muss beziffert sein" }
    }

    /** Ein unbrauchbarer Bericht darf NICHT blocken — der Guard gruendet keine
     *  Entscheidung auf eine Zahl, die er selbst verworfen hat. */
    @Test
    fun `ein unbrauchbarer Bericht greift nicht in die Entscheidung ein`() {
        val kaputt = TailLiability.evaluate(input(isfTail = 0.0))
        val d = FuseController.decide(state(), pred(400.0), tail = kaputt, evidenceCreditActive = false, lowThreat = LowThreatGate.Verdict.NONE)
        assertEquals(FuseController.Block.NONE, d.block)
        assertTrue(d.smbU > 0.0)
        assertEquals(0.0, d.tailCostU, 0.0)
    }

    /** Guard aus = tail null: alles genau wie vorher, und kein Bericht im
     *  Ergebnis, der eine Bewertung vortaeuschen wuerde. */
    @Test
    fun `ohne Schwanz-Guard bleibt die Entscheidung unveraendert`() {
        val mit = FuseController.decide(state(), pred(400.0), tail = null, evidenceCreditActive = false, lowThreat = LowThreatGate.Verdict.NONE)
        assertNull(mit.tail)
        assertEquals(0.0, mit.tailCostU, 0.0)
        assertTrue(mit.smbU > 0.0)
    }

    /** Reihenfolge: der NAHZONEN-Guard schlaegt den Schwanz. Beide sperren, aber
     *  nur der Nahzonenbefund rechtfertigt eine echte Null. */
    @Test
    fun `der Nahzonen-Guard hat Vorrang vor dem Schwanz`() {
        val tail = TailLiability.evaluate(input(existingIob = 99.0))
        val d = FuseController.decide(state(), pred(250.0, minLower = 60.0), tail = tail, evidenceCreditActive = false, lowThreat = LowThreatGate.Verdict.NONE)
        assertEquals(FuseController.Block.GUARD_FLOOR, d.block, "der Nahzonenbefund wird zuerst genannt")
        // VERTRAGSAENDERUNG 17.08.: die Reihenfolge bleibt, die TBR-Antwort
        // nicht. Beide Bloecke lassen jetzt das Fundament stehen - die Null
        // entsteht nur noch aus dem LowThreatGate. Der Unterschied zwischen
        // Nahzone und Schwanz ist damit nur noch der GRUND, nicht die Aktion.
        assertEquals(FuseController.TbrAction.KEEP_CURRENT, d.tbr)
        assertEquals(0.0, d.smbU, 1e-12, "gesperrt bleibt die Menge")
    }

    /**
     * GEMESSEN am 06.08.: bei 5,92 U an Bord projizierte die Bahn auf -31 mg/dl.
     * Sperren ist richtig - aber (-31 - 70)/95 waere ein Budget, das jemand
     * spaeter als Zahl liest.
     */
    @Test
    fun `eine unphysiologische Bahn sperrt, liefert aber keine auswertbare Zahl`() {
        val r = TailLiability.evaluate(input(lowerBgAtH = -31.0, existingIob = 5.92))
        assertTrue(r.usable)                 // die Eingabe war gueltig
        assertTrue(r.unphysiological)
        assertFalse(r.budgetMeaningful)      // die ZAHL nicht
        assertTrue(r.headroomU < 0.0)        // sperrt trotzdem
        assertEquals(0.0, r.budgetU, 0.0)
    }

    @Test
    fun `knapp ueber dem physiologischen Boden wird normal gerechnet`() {
        val r = TailLiability.evaluate(input(lowerBgAtH = 25.0, existingIob = 0.1))
        assertFalse(r.unphysiological)
        assertTrue(r.budgetMeaningful)
        assertEquals((25.0 - 70.0) / 50.0, r.budgetU, 1e-12)
    }
}

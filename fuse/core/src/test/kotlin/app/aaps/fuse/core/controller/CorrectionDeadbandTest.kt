package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DAS KORREKTUR-TOTBAND AUF DECIDE-EBENE (Kandidat, Toni 11.11.2026). Die Formel prueft [NightWindowTest], die
 * Verdrahtung das Gestell (`TransportWiringTest`); hier steht die mittlere Schicht: der Zustand traegt den Wert,
 * [FuseController.decide] sperrt den SMB des Normalwegs und nennt die Grenze. Synthetische Werte, keine Messdaten.
 */
class CorrectionDeadbandTest {

    private fun state(band: Double, r: Double? = 1.5) = FuseController.State(
        health = Health.READY, safetyHold = false, phase = Phase.REARMING,
        netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
        iobThU = 8.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 80.0,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = r, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = 0.05, maxSmbU = 0.3, pumpBusy = false,
        mealWindow = true, correctionDeadbandMgdl = band,
    )

    private fun pred(anchor: Double, mean: Double = 180.0) = PredictorResult(
        points = (1..60).map { TrajectoryPoint(it, it * 60_000L, mean, mean, 0.0, 0.0, 0.0) },
        predictionAnchorTs = 0L, bgAtAnchor = anchor,
        minMeanBg = mean, minLowerBg = mean, timeToMinLowerMin = 30,
        bgAtHorizonMean = mean, bgAtHorizonLower = mean,
        lineageKind = "ACTUAL", trajectoryContentHash = "h",
        iobArraySpanMin = 235.0, iobArrayGridMin = 5.0,
        modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
    )

    private fun decide(s: FuseController.State, anchor: Double, kredit: Boolean = false) = FuseController.decide(
        s, pred(anchor), evidenceCreditActive = kredit, evidenceMayOverrideRebound = kredit, lowThreat = LowThreatGate.Verdict.NONE,
    )

    @Test
    fun `unter Ziel plus Totband gibt der Normalweg keinen SMB und nennt die Grenze`() {
        // Gegenprobe zuerst: ohne Totband dosiert dieselbe Lage - sonst prueft der Test nichts.
        assertTrue(decide(state(0.0), anchor = 110.0).smbU > 0.0)
        val d = decide(state(30.0), anchor = 110.0)
        assertEquals(0.0, d.smbU, 0.0)
        assertEquals(FuseController.Block.NO_DEMAND, d.block)
        assertEquals(FuseController.CORRECTION_DEADBAND_LIMIT, d.bindingLimit)
        // Kein Eingriff ins Basal: weder eine Null noch eine neue positive TBR.
        assertEquals(FuseController.TbrAction.NO_NEW_POSITIVE, d.tbr)
        // Der Bedarf bleibt sichtbar - der langsame Kanal und der Trail lesen ihn.
        assertTrue((d.insulinReqU ?: 0.0) > 0.0) { "Bedarf ${d.insulinReqU}" }
    }

    /** Die Schwelle ist Ziel + Totband, strikt darunter gesperrt: 100 + 30 = 130. */
    @Test
    fun `auf und ueber der Schwelle dosiert er wie ohne Totband`() {
        for (anchor in listOf(130.0, 130.5, 150.0, 200.0)) {
            val mit = decide(state(30.0), anchor)
            val ohne = decide(state(0.0), anchor)
            assertTrue(ohne.smbU > 0.0) { "Vorbedingung bei $anchor" }
            assertEquals(ohne.smbU, mit.smbU, 0.0) { "Menge bei $anchor" }
            assertEquals(ohne.block, mit.block) { "Block bei $anchor" }
            assertEquals(ohne.bindingLimit, mit.bindingLimit) { "Grenze bei $anchor" }
            assertEquals(ohne.tbr, mit.tbr) { "TBR bei $anchor" }
        }
        assertEquals(FuseController.CORRECTION_DEADBAND_LIMIT, decide(state(30.0), 129.9).bindingLimit)
    }

    /** Anders als Nacht- und Rebound-Totband kennt es keine Ausnahme fuer den Evidenzkredit. */
    @Test
    fun `Evidenzkredit entwaffnet das Korrektur-Totband nicht`() {
        assertTrue(decide(state(0.0), anchor = 110.0, kredit = true).smbU > 0.0) { "Vorbedingung: ohne Totband dosiert der Kreditzyklus" }
        val d = decide(state(30.0), anchor = 110.0, kredit = true)
        assertEquals(FuseController.CORRECTION_DEADBAND_LIMIT, d.bindingLimit)
        assertEquals(0.0, d.smbU, 0.0)
    }

    /** AUS: 0, negativ und nicht endlich sperren nie und nennen die Grenze nie. */
    @Test
    fun `ohne Totband sperrt es nicht`() {
        val vorher = decide(state(0.0), anchor = 110.0)
        for (aus in listOf(-5.0, Double.NaN, Double.NEGATIVE_INFINITY)) {
            val d = decide(state(aus), anchor = 110.0)
            assertEquals(vorher.smbU, d.smbU, 0.0) { "bei $aus" }
            assertEquals(vorher.bindingLimit, d.bindingLimit) { "bei $aus" }
        }
    }

    /**
     * DIE NAMEN VON NACHT UND REBOUND BLEIBEN. Die Nachtkante im Runner (Nachlauf nach dem Nachtende) liest genau
     * "nightDeadband" - bei gleichem Wert gewinnt deshalb der Name der Nacht.
     */
    @Test
    fun `der groesste Wert bindet und traegt seinen Namen`() {
        val nacht = state(30.0, r = 0.2).copy(nightWindow = true, nightDeadbandMgdl = 45.0)
        assertEquals("nightDeadband", decide(nacht, 110.0).bindingLimit)
        assertEquals("nightDeadband", decide(nacht.copy(nightDeadbandMgdl = 30.0), 110.0).bindingLimit)
        // Ein kleineres Nacht-Totband: zwischen beiden Schwellen bindet das Korrektur-Totband.
        assertEquals(FuseController.CORRECTION_DEADBAND_LIMIT, decide(nacht.copy(nightDeadbandMgdl = 10.0), 120.0).bindingLimit)
        // Im Rebound-Fenster: das groessere Rebound-Totband traegt seinen Namen, das kleinere weicht.
        val rebound = state(30.0).copy(reboundWindow = true)
        assertEquals("reboundDeadband", decide(rebound.copy(reboundDeadbandMgdl = 50.0), 110.0).bindingLimit)
        assertEquals(FuseController.CORRECTION_DEADBAND_LIMIT, decide(rebound.copy(reboundDeadbandMgdl = 25.0), 127.0).bindingLimit)
    }
}

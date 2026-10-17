package app.aaps.fuse.core.predictor

import app.aaps.fuse.core.signal.PairSlopeBand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * S0-Telemetrie: die Zerlegung muss zur Bahn PASSEN, sonst erklaert sie nichts.
 *
 * Der Zweck dieser Datei ist nicht, neue Regeln zu pruefen - es gibt keine. Sie
 * prueft ZUSAMMENHAENGE: dass die getrennt gefuehrten Summen dieselbe Bahn
 * ergeben wie die gruppierte Addition, und dass `hubOfConstantDrive` das
 * Vorzeichen-Praedikat aus `TrajectoryCore.decayed` wirklich wiederholt.
 *
 * Genau daran scheitert eine Zerlegung sonst still: sie sieht plausibel aus und
 * beschreibt eine andere Rechnung als die, die entschieden hat.
 */
class TrajectoryTelemetryTest {

    private val anchor = 1_700_000_000_000L
    private val horizonMin = 120
    private val isf = 60.0

    private fun predict(
        drive: Double,
        driveLower: Double = drive,
        driveLowerPriorFree: Double? = null,
        activity: Double = 0.0,
        negativeDecay: DriveDecayModel? = null,
    ): PredictorResult {
        val pts = (0..(horizonMin + 60)).map {
            IobPoint(anchor + it * 60_000L, iob = 1.0, activity = activity, basalIob = 0.0)
        }
        val outcome = TrajectoryCore.predict(
            PredictorInput(
                predictionAnchorTs = anchor,
                bgAtAnchor = 150.0,
                drive = DriveEstimate(drive, driveLower, 0.8, "test", driveLowerPriorFree),
                decay = DriveDecayModel.ExponentialDecay(60.0),
                decayNegativeDrive = negativeDecay,
                trajectory = VirtualTrajectoryFactory.of(
                    lineage = InsulinLineage.VirtualController("session", 1L, "modelhash"),
                    points = pts,
                    arrayAsOfTs = anchor,
                    model = InsulinModelProvenance("TEST_FLAT", 3.0, 60, "test"),
                    iobCalculationHash = "hash",
                ),
                isfSlots = listOf(IsfSlot(anchor - 3_600_000L, anchor + 10 * 3_600_000L, isf)),
                horizonMin = horizonMin,
            )
        )
        assertTrue(outcome is PredictorOutcome.Ok, "predictor rejected: $outcome")
        return (outcome as PredictorOutcome.Ok).result
    }

    /**
     * HUB-1: die Zerlegung rekonstruiert die Bahn.
     *
     * Toleranz 1e-9 und nicht 0: die Schleife addiert GRUPPIERT
     * (`meanBg += (dMean + bgiRate + pendingBgi)`), die Summen getrennt. Der
     * Unterschied ist Gleitkomma-Assoziativitaet.
     */
    @Test
    fun `der Bahnhub rekonstruiert den Horizontwert`() {
        val r = predict(drive = 2.0, driveLower = 1.2, driveLowerPriorFree = 0.9, activity = 0.004)
        val h = r.hubAtHorizon ?: fail("kein Hub am Horizont")

        assertEquals(r.bgAtHorizonMean, r.bgAtAnchor + h.driveMeanMgdl + h.bgiMgdl + h.transportMgdl, 1e-9)
        assertEquals(r.bgAtHorizonLower, r.bgAtAnchor + h.driveLowerMgdl + h.bgiMgdl + h.transportMgdl, 1e-9)
        assertEquals(
            r.bgAtHorizonSafetyLower,
            r.bgAtAnchor + h.driveSafetyLowerMgdl + h.bgiMgdl + h.transportMgdl, 1e-9
        )
    }

    /**
     * HUB-2: `hubOfConstantDrive` rechnet dieselbe Regel wie der Kern.
     *
     * Der zweite Lauf ist tragend und nicht Zierde: ohne einen NEGATIVEN
     * Antrieb UND ein gesetztes `decayNegativeDrive` wird der andere Zweig des
     * Vorzeichen-Praedikats nie ausgefuehrt, und eine Abweichung bliebe
     * unentdeckt (C10).
     *
     * Die 240 sind kein beliebiger Wert: `TrajectoryCore` nimmt
     * `maxOf(f, fNegative)`, also den LANGSAMEREN Zerfall. Ein schnelleres
     * zweites Modell (z. B. 15 min) wuerde weggekuerzt, beide Summen waeren
     * gleich, und der Test pruefte zweimal dieselbe Zahl - deshalb steht die
     * Ungleichheit der Summen unten ausdruecklich als Zusicherung drin.
     */
    @Test
    fun `hubOfConstantDrive trifft den gerechneten Antriebshub in beiden Vorzeichen`() {
        val pos = predict(drive = 1.7)
        val hp = pos.hubAtHorizon ?: fail("kein Hub am Horizont")
        assertEquals(hp.driveMeanMgdl, hp.hubOfConstantDrive(1.7), 1e-9)

        val neg = predict(drive = -1.7, negativeDecay = DriveDecayModel.ExponentialDecay(240.0))
        val hn = neg.hubAtHorizon ?: fail("kein Hub am Horizont")
        assertEquals(hn.driveMeanMgdl, hn.hubOfConstantDrive(-1.7), 1e-9)
        // Und die beiden Gewichtssummen sind wirklich verschieden - sonst
        // pruefte der negative Lauf dieselbe Zahl zweimal.
        assertTrue(
            hn.decayWeightSumNegative != hn.decayWeightSumPositive,
            "negativer Zerfall wurde nicht ausgeuebt"
        )
    }

    /** Der Transportterm senkt die Bahn, er hebt sie nie. */
    @Test
    fun `der Transporthub ist nie positiv`() {
        val r = predict(drive = 1.0)
        val h = r.hubAtHorizon ?: fail("kein Hub am Horizont")
        assertTrue(h.transportMgdl <= 0.0)
    }

    /**
     * I16: die prior-freie Bahn hat einen EIGENEN Zeitindex.
     *
     * Bei fallender Bahn liegt ihr Minimum am Horizont - und der Index muss das
     * sagen, nicht der der Anzeigebahn.
     */
    @Test
    fun `die prior-freie Bahn fuehrt ihren eigenen Zeitindex`() {
        val r = predict(drive = 0.0, driveLower = 0.0, driveLowerPriorFree = -1.0)
        assertEquals(horizonMin, r.timeToMinSafetyLowerMin)
        assertEquals(r.timeToMinLowerPriorFreeMin, r.timeToMinSafetyLowerMin)
        // Die Zerlegung am Minimum gehoert zu genau diesem Punkt.
        assertNotNull(r.hubAtMinSafetyLower)
    }

    /**
     * PFLICHTTEST zum Anker-Hub (Review 10.08.).
     *
     * Auf einer STEIGENDEN Bahn unterschreitet keine Zukunftsminute den Anker,
     * das Minimum liegt also bei Minute 0. Der frueher hier stehende Rueckfall
     * `hubAtMin ?: hubNow()` hat in genau diesem Fall den HORIZONT-Hub als "Hub
     * am Minimum" ausgegeben - stillschweigend die Zerlegung des entferntesten
     * Punktes an der Stelle des naechsten.
     *
     * Der Test prueft deshalb nicht nur "nicht null", sondern dass ALLE sieben
     * Komponenten dort 0 sind, UND dass der Horizont-Hub im selben Lauf
     * ausdruecklich von null verschieden ist - sonst waere die Zusicherung auch
     * mit dem alten Rueckfall erfuellbar.
     */
    @Test
    fun `liegt das Minimum am Anker ist der Hub dort null`() {
        val r = predict(drive = 3.0)
        assertEquals(0, r.timeToMinSafetyLowerMin, "Minimum sollte am Anker liegen")
        assertEquals(r.bgAtAnchor, r.minSafetyLowerBg, 1e-12)

        val h = r.hubAtMinSafetyLower ?: fail("kein Hub am Minimum")
        assertEquals(0.0, h.driveMeanMgdl)
        assertEquals(0.0, h.driveLowerMgdl)
        assertEquals(0.0, h.driveSafetyLowerMgdl)
        assertEquals(0.0, h.bgiMgdl)
        assertEquals(0.0, h.transportMgdl)
        assertEquals(0.0, h.decayWeightSumPositive)
        assertEquals(0.0, h.decayWeightSumNegative)

        // Gegenprobe: der Horizont-Hub im SELBEN Lauf ist deutlich != 0. Ohne
        // sie waere der Test auch dann gruen, wenn gar nichts akkumuliert wird.
        val hh = r.hubAtHorizon ?: fail("kein Hub am Horizont")
        assertTrue(hh.driveMeanMgdl > 1.0, "Horizont-Hub war ${hh.driveMeanMgdl}")
    }

    /** WIE TIEF und WIE BALD - und `null` heisst "nie", nicht "sofort". */
    @Test
    fun `Bodenabfragen liefern Defizit und Zeitpunkt`() {
        // -2,0 und nicht -1,0: bei tau=60 ueber 120 min traegt der Zerfall nur
        // ~52 Minutenaequivalente, ein Antrieb von -1,0 endet also bei ~98 und
        // erreicht den Boden nie. Der Test pruefte dann nur sich selbst.
        val faellt = predict(drive = -2.0, driveLower = -2.0, driveLowerPriorFree = -2.0)
        assertTrue(TrajectoryQuery.floorDeficitMgdl(faellt, 70.0) > 0.0)
        val t = TrajectoryQuery.timeToFloorMin(faellt, 70.0)
        assertNotNull(t)
        assertTrue(t!! in 1..horizonMin)

        val flach = predict(drive = 0.0)
        assertEquals(0.0, TrajectoryQuery.floorDeficitMgdl(flach, 70.0))
        assertNull(TrajectoryQuery.timeToFloorMin(flach, 70.0), "flache Bahn bei 150 faellt nie unter 70")
    }

    /** Das Bandflag kommt AUS dem Zweig, nicht daneben (Audit E.5). */
    @Test
    fun `bandActive folgt dem tatsaechlichen Zweig`() {
        assertFalse(PairSlopeBand.bandApplies(50))
        assertTrue(PairSlopeBand.bandApplies(25))
        // Und die Umstellung von `>= MAX_PCT` auf `!bandApplies` bleibt
        // zeichengleich: bei 50 liefert quantile weiterhin den Median selbst.
        val sorted = listOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(2.5, PairSlopeBand.quantile(sorted, 2.5, 50))
        assertEquals(1.0, PairSlopeBand.quantile(sorted, 2.5, 25))
    }
}

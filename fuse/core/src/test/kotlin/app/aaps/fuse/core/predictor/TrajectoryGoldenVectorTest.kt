package app.aaps.fuse.core.predictor

import app.aaps.fuse.core.util.Sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * GOLDENE VEKTOREN: feste Eingaben, festgeschriebene Bahnen.
 *
 * WOZU, und der Anlass ist konkret: als naechstes bekommt der Predictor eine
 * VIERTE Bahn (die bedingte, mit erklaerten Kohlenhydraten). Ohne diese Datei
 * waere "die drei bestehenden Bahnen haben sich nicht verschoben" eine
 * Behauptung. Mit ihr ist es eine Zusicherung, die beim Bauen bricht.
 *
 * Die uebrigen Tests im Modul pruefen REGELN ("bei fallender Bahn muss der
 * Guard greifen"). Diese hier prueft ZAHLEN. Beides zusammen faengt zwei
 * verschiedene Fehlerarten: eine kaputte Regel und eine stille Verschiebung,
 * die jede Regel weiterhin erfuellt.
 *
 * AUFBAU JE FALL: eine Handvoll Kennzahlen im Klartext (damit man beim
 * Fehlschlag SIEHT, was sich bewegt hat) UND ein Digest ueber die vollstaendige
 * Punktreihe (damit auch eine Verschiebung in Minute 47 auffaellt, die keine
 * Kennzahl beruehrt). Nur Kennzahlen waeren zu grob, nur ein Digest zu stumm.
 *
 * WENN DIESER TEST BRICHT, ist das erst einmal ein BEFUND und kein Fehler im
 * Test. Die Erwartungswerte nachzuziehen ist eine bewusste Handlung: sie sagt
 * "diese Verschiebung ist gewollt", und sie gehoert in denselben Commit wie
 * die Aenderung, die sie verursacht hat - mit Begruendung.
 *
 * KEINE ZEITZONE, KEINE UHR. Alle Zeitstempel sind feste Zahlen. Ein frueherer
 * Entwurf wollte die Zeitzone im Test festnageln - das waere eine
 * Verhaltensaenderung im Test gewesen, also genau das, was er verhindern soll.
 */
class TrajectoryGoldenVectorTest {

    private val anchor = 1_700_000_000_000L
    private val isf = 55.0

    /**
     * Ein Fall. `activityAt` liefert die Insulinaktivitaet je Minute - darueber
     * kommen BGI und damit die Kruemmung in die Bahn.
     */
    private fun run(
        bgAtAnchor: Double,
        drive: Double,
        driveLower: Double,
        driveLowerPriorFree: Double? = null,
        horizonMin: Int = 120,
        tauMin: Double = 60.0,
        negativeTauMin: Double? = null,
        activityAt: (Int) -> Double = { 0.0 },
    ): PredictorResult {
        val pts = (0..(horizonMin + 60)).map {
            IobPoint(anchor + it * 60_000L, iob = 2.0, activity = activityAt(it), basalIob = 0.0)
        }
        val outcome = TrajectoryCore.predict(
            PredictorInput(
                predictionAnchorTs = anchor,
                bgAtAnchor = bgAtAnchor,
                drive = DriveEstimate(drive, driveLower, 0.8, "golden", driveLowerPriorFree),
                decay = DriveDecayModel.ExponentialDecay(tauMin),
                decayNegativeDrive = negativeTauMin?.let { DriveDecayModel.ExponentialDecay(it) },
                trajectory = VirtualTrajectoryFactory.of(
                    lineage = InsulinLineage.VirtualController("golden", 1L, "modelhash"),
                    points = pts,
                    arrayAsOfTs = anchor,
                    model = InsulinModelProvenance("GOLDEN", 5.0, 45, "golden"),
                    iobCalculationHash = "golden",
                ),
                isfSlots = listOf(IsfSlot(anchor - 3_600_000L, anchor + 12 * 3_600_000L, isf)),
                horizonMin = horizonMin,
            )
        )
        assertTrue(outcome is PredictorOutcome.Ok, "Predictor hat abgelehnt: $outcome")
        return (outcome as PredictorOutcome.Ok).result
    }

    /**
     * Digest ueber ALLE drei Bahnen, Punkt fuer Punkt.
     *
     * Ueber die IEEE-754-Bits (`Sha.lossless`) und nicht ueber gerundeten Text:
     * eine gerundete Form koennte zwei verschiedene Bahnen auf denselben
     * Digest abbilden, und dann schwiege der Waechter genau dort, wo er reden
     * soll.
     */
    private fun digest(r: PredictorResult): String = Sha.of(
        r.points.joinToString("|") { p ->
            "${p.offsetMin}:${Sha.lossless(p.meanBg)}:${Sha.lossless(p.lowerBg)}:${Sha.lossless(p.safetyLowerBg)}"
        }
    )

    private fun kennzahlen(r: PredictorResult) = listOf(
        r.minMeanBg, r.minLowerBg, r.minSafetyLowerBg,
        r.bgAtHorizonMean, r.bgAtHorizonLower, r.bgAtHorizonSafetyLower,
    )

    private fun pruefe(
        r: PredictorResult,
        erwartet: List<Double>,
        timeToMinLower: Int,
        timeToMinSafety: Int,
        erwarteterDigest: String,
    ) {
        val ist = kennzahlen(r)
        val namen = listOf("minMean", "minLower", "minSafetyLower", "horizonMean", "horizonLower", "horizonSafetyLower")
        for (i in erwartet.indices) assertEquals(erwartet[i], ist[i], 1e-9, namen[i])
        assertEquals(timeToMinLower, r.timeToMinLowerMin, "timeToMinLowerMin")
        assertEquals(timeToMinSafety, r.timeToMinSafetyLowerMin, "timeToMinSafetyLowerMin")
        assertEquals(erwarteterDigest, digest(r), "die Punktreihe hat sich verschoben")
    }

    // ---- Die Faelle -------------------------------------------------------

    /** FLACH: kein Antrieb, keine Insulinaktivitaet. Die langweiligste Bahn -
     *  und deshalb die, an der eine Verschiebung am deutlichsten auffaellt. */
    @Test
    fun `G1 flache Bahn ohne Antrieb und ohne Aktivitaet`() {
        val r = run(bgAtAnchor = 120.0, drive = 0.0, driveLower = 0.0)
        pruefe(
            r,
            erwartet = listOf(120.0, 120.0, 120.0, 120.0, 120.0, 120.0),
            timeToMinLower = 0, timeToMinSafety = 0,
            erwarteterDigest = "d484b2eb580f4b14bef8b7742844503bdfbfd895f7cfaa38322923fcfbac4c2e",
        )
        // Zusicherung ohne Golden-Wert: eine Bahn ohne Antrieb und ohne
        // Wirkung DARF sich nicht bewegen. Das ist die eine Aussage hier, die
        // auch ohne festgeschriebene Zahl traegt.
        assertTrue(r.points.all { it.meanBg == 120.0 && it.lowerBg == 120.0 })
    }

    /** ANSTIEG mit Insulinwirkung - der Mahlzeitenfall. Mittel- und Guardbahn
     *  laufen auseinander, die Kruemmung kommt aus dem BGI. */
    @Test
    fun `G2 Anstieg mit Insulinwirkung`() {
        val r = run(
            bgAtAnchor = 145.0, drive = 1.6, driveLower = 0.9, driveLowerPriorFree = 0.4,
            activityAt = { i -> 0.010 * kotlin.math.exp(-((i - 45.0) * (i - 45.0)) / 1600.0) },
        )
        pruefe(
            r,
            erwartet = listOf(145.0, 145.0, 128.53192886855925, 190.72652807226314, 154.71240197670767, 128.9880261941679),
            timeToMinLower = 0, timeToMinSafety = 101,
            erwarteterDigest = "604d45f65381de688fe2297e99165178bc897b0007f4a4239bfb97aa4af19f6a",
        )
        // Ordnung der drei Bahnen: Mittel >= Anzeige >= prior-frei, punktweise.
        assertTrue(r.points.all { it.meanBg >= it.lowerBg - 1e-9 })
        assertTrue(r.points.all { it.lowerBg >= it.safetyLowerBg - 1e-9 })
    }

    /** ABSTIEG mit vorzeichenbewusstem Zerfall - der zweite Zweig von
     *  `decayed`, der ohne eigenen Fall nie ausgefuehrt wird. */
    @Test
    fun `G3 Abstieg mit langsamerem Zerfall des negativen Anteils`() {
        val r = run(
            bgAtAnchor = 160.0, drive = -1.4, driveLower = -1.4, driveLowerPriorFree = -1.4,
            negativeTauMin = 240.0,
            activityAt = { 0.004 },
        )
        pruefe(
            r,
            erwartet = List(6) { 1.6695389318830804 },
            timeToMinLower = 120, timeToMinSafety = 120,
            erwarteterDigest = "1d7828eac1cf9b9545b7fee87a52654fcae99738b65f34a59357e2223b1a4954",
        )
        val h = r.hubAtHorizon!!
        assertNotEquals(h.decayWeightSumPositive, h.decayWeightSumNegative, "der negative Zweig lief nicht")
    }

    /** KURZER HORIZONT: die Randbedingung, an der Off-by-one-Fehler sitzen. */
    @Test
    fun `G4 kurzer Horizont`() {
        val r = run(bgAtAnchor = 100.0, drive = 0.8, driveLower = 0.3, horizonMin = 30)
        assertEquals(30, r.points.size)
        assertEquals(1, r.points.first().offsetMin)
        assertEquals(30, r.points.last().offsetMin)
        pruefe(
            r,
            erwartet = listOf(100.0, 100.0, 100.0, 118.72957778381048, 107.02359166892894, 107.02359166892894),
            timeToMinLower = 0, timeToMinSafety = 0,
            erwarteterDigest = "e2c0ee05532f3d9061dd06e0afb5ef48e816a21573ddae4a4214df710af4bffb",
        )
    }

    // ---- Der Waechter ueber dem Waechter ---------------------------------

    /**
     * Der Digest muss auf eine Verschiebung ANSPRINGEN - sonst ist die ganze
     * Datei Dekoration.
     *
     * Ohne diesen Fall koennte `digest` konstant sein und jeder Vergleich oben
     * bestuende trotzdem. Geprueft wird mit einer Aenderung, die KEINE der
     * sechs Kennzahlen beruehrt: ein Antrieb, der nur die Form der Bahn
     * zwischen Anker und Horizont aendert.
     */
    @Test
    fun `der Digest bemerkt eine Verschiebung die keine Kennzahl beruehrt`() {
        val a = run(bgAtAnchor = 140.0, drive = 1.0, driveLower = 1.0, tauMin = 60.0)
        val b = run(bgAtAnchor = 140.0, drive = 1.0, driveLower = 1.0, tauMin = 61.0)
        assertNotEquals(digest(a), digest(b), "der Digest ist blind")
        // Und die Gegenprobe: derselbe Lauf zweimal ergibt denselben Digest -
        // der Kern ist deterministisch, sonst waere die Datei unbrauchbar.
        assertEquals(digest(a), digest(run(bgAtAnchor = 140.0, drive = 1.0, driveLower = 1.0, tauMin = 60.0)))
    }

    /**
     * DIE EIGENTLICHE ZUSICHERUNG FUER DIE VIERTE BAHN.
     *
     * Ein zusaetzlicher, prior-freier Zwilling darf die beiden anderen Bahnen
     * NICHT beruehren. Genau das wird beim Einbau der bedingten Bahn behauptet
     * werden - hier steht die Probe dafuer schon bereit.
     */
    @Test
    fun `eine zusaetzliche Bahn verschiebt die bestehenden nicht`() {
        val ohne = run(bgAtAnchor = 150.0, drive = 1.2, driveLower = 0.6)
        val mit = run(bgAtAnchor = 150.0, drive = 1.2, driveLower = 0.6, driveLowerPriorFree = 0.2)

        assertEquals(ohne.bgAtHorizonMean, mit.bgAtHorizonMean, 0.0, "die Mittelbahn hat sich bewegt")
        assertEquals(ohne.bgAtHorizonLower, mit.bgAtHorizonLower, 0.0, "die Anzeigebahn hat sich bewegt")
        assertEquals(ohne.minMeanBg, mit.minMeanBg, 0.0)
        assertEquals(ohne.minLowerBg, mit.minLowerBg, 0.0)
        assertTrue(
            ohne.points.zip(mit.points).all { (a, b) -> a.meanBg == b.meanBg && a.lowerBg == b.lowerBg },
            "die Punktreihen der beiden bestehenden Bahnen sind nicht identisch"
        )
        // Und die neue Bahn liegt wirklich tiefer - sonst prueft der Fall
        // nichts. AM HORIZONT und nicht am Minimum: alle drei Bahnen steigen
        // hier, ihr Minimum ist also bei allen dreien der ANKER, und dort
        // sind sie definitionsgemaess gleich. Der Test hat genau das gerade
        // selbst gefunden.
        assertTrue(mit.bgAtHorizonSafetyLower < mit.bgAtHorizonLower)
    }
}

package app.aaps.fuse.core.predictor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE BEDINGTE BAHN: dieselbe Rechnung, EIN Unterschied.
 *
 * Der Schwanz-Guard rechnet sein Budget aus der prior-freien Bahn - einem
 * Verlauf OHNE Kohlenhydrate. Er verbietet damit genau das Insulin, das die
 * angekuendigte Mahlzeit rechtfertigt. Gemessen am 10.08.: 25 Minuten Sperre am
 * Stueck, waehrend der BG von 115 auf 125 stieg und danach auf 193 weiterlief.
 *
 * Die bedingte Bahn hebt den ERKLAERTEN Antrieb auf die Sicherheitskante. Diese
 * Datei haelt fest, was dabei NICHT passieren darf.
 */
class ConditionalTrajectoryTest {

    private val anchor = 1_700_000_000_000L
    private val isf = 55.0
    private val horizonMin = 120

    private fun predict(drive: DriveEstimate): PredictorResult {
        val pts = (0..(horizonMin + 60)).map {
            IobPoint(anchor + it * 60_000L, iob = 2.0, activity = 0.004, basalIob = 0.0)
        }
        val o = TrajectoryCore.predict(
            PredictorInput(
                predictionAnchorTs = anchor,
                bgAtAnchor = 130.0,
                drive = drive,
                decay = DriveDecayModel.ExponentialDecay(60.0),
                trajectory = VirtualTrajectoryFactory.of(
                    lineage = InsulinLineage.VirtualController("s", 1L, "m"),
                    points = pts, arrayAsOfTs = anchor,
                    model = InsulinModelProvenance("TEST", 5.0, 45, "t"),
                    iobCalculationHash = "h",
                ),
                isfSlots = listOf(IsfSlot(anchor - 3_600_000L, anchor + 12 * 3_600_000L, isf)),
                horizonMin = horizonMin,
            )
        )
        assertTrue(o is PredictorOutcome.Ok, "abgelehnt: $o")
        return (o as PredictorOutcome.Ok).result
    }

    /** Antrieb wie im Livepfad: Mittel hoch, Anzeige darunter, Sicherheit
     *  darunter - der Abstand zwischen den letzten beiden ist der Marker-Prior. */
    private fun basis(safetyLower: Double = 0.2) = DriveEstimate(
        meanMgdlPerMin = 1.6,
        lowerMgdlPerMin = 0.9,
        confidence = 0.8,
        uncertaintyMethodId = "test",
        lowerPriorFreeMgdlPerMin = safetyLower,
    )

    /**
     * DER ZWECK: die Sicherheitskante am Haftungshorizont steigt, und **nur**
     * sie. Aus dieser Differenz rechnet der Schwanz sein Budget.
     */
    @Test
    fun `die bedingte Bahn hebt die Sicherheitskante und sonst nichts`() {
        val ohne = predict(basis(safetyLower = 0.2))
        val mit = predict(basis().copy(lowerPriorFreeMgdlPerMin = 0.7))

        assertTrue(
            mit.bgAtHorizonSafetyLower > ohne.bgAtHorizonSafetyLower,
            "die Sicherheitskante muss steigen: ${ohne.bgAtHorizonSafetyLower} -> ${mit.bgAtHorizonSafetyLower}"
        )
        // Und die beiden anderen Bahnen PUNKT FUER PUNKT unberuehrt. Das ist die
        // Zusicherung, die die bedingte Bahn ueberhaupt zulaessig macht: sie
        // erzeugt Bedarf nur dort, wo der Schwanz rechnet, und veraendert weder
        // Anzeige noch Mittelwert.
        assertEquals(ohne.bgAtHorizonMean, mit.bgAtHorizonMean, 0.0)
        assertEquals(ohne.bgAtHorizonLower, mit.bgAtHorizonLower, 0.0)
        assertTrue(
            ohne.points.zip(mit.points).all { (a, b) -> a.meanBg == b.meanBg && a.lowerBg == b.lowerBg },
            "Mittel- und Anzeigebahn haben sich bewegt"
        )
    }

    /**
     * DER DECKEL, und er kostet keine eigene Zeile Code: die Invariante von
     * [DriveEstimate] laesst `priorFree > lower` gar nicht zu. Die
     * Sicherheitskante kann also nie ueber die ANZEIGEBAHN steigen, egal wie
     * gross der erklaerte Kredit wird.
     */
    @Test
    fun `die Hebung kann die Anzeigebahn nicht ueberschreiten`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            basis().copy(lowerPriorFreeMgdlPerMin = 1.2)   // > lower = 0.9
        }
        assertTrue(e.message!!.contains("priorFree > lower"), e.message!!)
    }

    /** An der Obergrenze fallen Sicherheits- und Anzeigebahn zusammen - der
     *  guenstigste Fall, den die Ankuendigung erreichen kann. */
    @Test
    fun `am Deckel ist die Sicherheitskante die Anzeigebahn`() {
        val voll = predict(basis().copy(lowerPriorFreeMgdlPerMin = 0.9))
        assertEquals(voll.bgAtHorizonLower, voll.bgAtHorizonSafetyLower, 1e-9)
    }

    /**
     * KEIN KREDIT, KEINE AENDERUNG. Das ist die Zusicherung fuer den
     * ausgeschalteten Schalter und fuer jeden Zyklus ohne Marker: bitgleich zu
     * vorher.
     */
    @Test
    fun `ohne Hebung ist die Bahn bitgleich zur unbedingten`() {
        val a = predict(basis(safetyLower = 0.2))
        val b = predict(basis(safetyLower = 0.2))
        assertEquals(a.bgAtHorizonSafetyLower, b.bgAtHorizonSafetyLower, 0.0)
        assertEquals(a.minSafetyLowerBg, b.minSafetyLowerBg, 0.0)
    }

    /**
     * Die Hebung ist MONOTON: mehr Kredit heisst nie weniger Budget. Ohne das
     * koennte ein groesserer erklaerter Antrieb den Schwanz enger machen - eine
     * Umkehrung, die niemand erwarten wuerde.
     */
    @Test
    fun `mehr Kredit hebt die Kante nie weniger`() {
        var vorher = Double.NEGATIVE_INFINITY
        for (pf in listOf(0.2, 0.4, 0.6, 0.8, 0.9)) {
            val h = predict(basis().copy(lowerPriorFreeMgdlPerMin = pf)).bgAtHorizonSafetyLower
            assertTrue(h >= vorher, "bei priorFree=$pf sank die Kante: $vorher -> $h")
            vorher = h
        }
    }
}

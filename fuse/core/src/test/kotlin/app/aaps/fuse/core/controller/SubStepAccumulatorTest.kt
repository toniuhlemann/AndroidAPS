package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Quantisierungs-Totzone (Toni 09.08.): die gewuenschte Rate wird zum Takt. */
class SubStepAccumulatorTest {

    private val step = 0.05

    @Test
    fun `Tonis Fall - 0,036 U je Zyklus werden zu einem Tropfen alle zwei Zyklen`() {
        // insulinReq 0,24 * Ratio 0,15 = 0,036; gerastert 0,00.
        var carry = 0.0
        val releases = ArrayList<Double>()
        repeat(6) {
            val r = SubStepAccumulator.step(carry, desiredU = 0.036, steppedU = 0.0, pumpIncrementU = step, discard = false)
            carry = r.carryU
            releases += r.releaseU
        }
        // Ohne Zaehler waeren alle sechs Zyklen 0,00 U geblieben.
        assertTrue(releases.count { it > 0.0 } >= 2) { "kein Tropfen freigegeben: $releases" }
        assertTrue(releases.all { it == 0.0 || it == step }) { "nur ganze Pumpenschritte: $releases" }
    }

    @Test
    fun `der Zaehler haelt nie mehr als einen Pumpenschritt vor`() {
        var carry = 0.0
        repeat(50) {
            val r = SubStepAccumulator.step(carry, 0.049, 0.0, step, discard = false)
            carry = r.carryU
            assertTrue(carry <= step + 1e-12) { "Zaehler ueber dem Deckel: $carry" }
        }
    }

    @Test
    fun `jeder Zweifel verwirft den Zaehler vollstaendig`() {
        val r = SubStepAccumulator.step(carriedU = 0.049, desiredU = 0.04, steppedU = 0.0, pumpIncrementU = step, discard = true)
        assertEquals(0.0, r.releaseU, 0.0)
        assertEquals(0.0, r.carryU, 0.0)
    }

    @Test
    fun `ohne Bedarf wird nichts gesammelt und nichts freigegeben`() {
        val r = SubStepAccumulator.step(0.04, desiredU = 0.0, steppedU = 0.0, pumpIncrementU = step, discard = false)
        assertEquals(0.0, r.releaseU, 0.0)
        assertEquals(0.0, r.carryU, 0.0)
    }

    @Test
    fun `bereits vorgeschlagene Menge wird nicht doppelt aufgeschoben`() {
        // Gewuenscht 0,08, gerastert 0,05 -> offener Rest ist NUR 0,03.
        val r = SubStepAccumulator.step(0.0, desiredU = 0.08, steppedU = 0.05, pumpIncrementU = step, discard = false)
        assertEquals(0.0, r.releaseU, 0.0)
        assertEquals(0.03, r.carryU, 1e-12)
    }

    @Test
    fun `unbrauchbare Eingaben geben nichts frei`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.0, SubStepAccumulator.step(bad, 0.04, 0.0, step, false).releaseU, 0.0)
            assertEquals(0.0, SubStepAccumulator.step(0.0, bad, 0.0, step, false).releaseU, 0.0)
            assertEquals(0.0, SubStepAccumulator.step(0.0, 0.04, 0.0, bad, false).releaseU, 0.0)
        }
        assertEquals(0.0, SubStepAccumulator.step(0.0, 0.04, 0.0, 0.0, false).releaseU, 0.0)
    }

    @Test
    fun `die Rate bleibt erhalten - Summe der Tropfen folgt der Absicht`() {
        // 0,01 U/min ueber 60 Zyklen = 0,6 U Absicht; erwartet ~12 Tropfen.
        var carry = 0.0
        var total = 0.0
        repeat(60) {
            val r = SubStepAccumulator.step(carry, 0.01, 0.0, step, discard = false)
            carry = r.carryU
            total += r.releaseU
        }
        assertTrue(total in 0.5..0.6) { "Summe $total weicht von der Absicht 0,6 ab" }
    }
}

/** SUB-02 Rest: der Uebertrag darf keine Entscheidung aus einer anderen Lage
 *  mitfinanzieren. */
class SubStepContextTest {

    private fun ctx(
        target: Double = 98.0, isf: Double = 90.0, step: Double = 0.05,
        ratio: Double = 0.15, ratioRise: Double = 0.35, maxSmb: Double = 0.3,
        meal: Boolean = false,
        // D5-Nachruestung (Codex Combined Closure b6dbb490). Defaults wie im
        // Livepfad, damit die alten Faelle unveraendert bleiben.
        maxIob: Double = 8.0, iobTh: Double = 8.0,
        markerTs: Long = 0L, markerTier: String = "0", profil: String = "A",
    ) = SubStepAccumulator.context(
        target, isf, step, ratio, ratioRise, maxSmb, meal,
        maxIob, iobTh, markerTs, markerTier, profil
    )

    @Test
    fun `gleiche Lage - gleicher Abdruck`() {
        assertEquals(ctx(), ctx())
    }

    @Test
    fun `jede der sieben Groessen aendert den Abdruck`() {
        val basis = ctx()
        assertNotEquals(basis, ctx(target = 100.0)) { "Zielwechsel" }
        assertNotEquals(basis, ctx(isf = 95.0)) { "Profil-ISF" }
        assertNotEquals(basis, ctx(step = 0.1)) { "Pumpenschritt" }
        assertNotEquals(basis, ctx(ratio = 0.2)) { "Korrektur-Ratio" }
        assertNotEquals(basis, ctx(ratioRise = 0.4)) { "Anstiegs-Ratio" }
        assertNotEquals(basis, ctx(maxSmb = 0.4)) { "maxSmb" }
        assertNotEquals(basis, ctx(meal = true)) { "Mahlzeitenfenster" }
    }

    /** Codex Re-Audit 3b5cadbf, F4: die fuenf mit D5 NACHGERUESTETEN Groessen
     *  standen bis hier nur im Quelltext. Ein Feld in den Abdruck aufzunehmen
     *  und es nie zu variieren ist die teuerste Art, sich sicher zu fuehlen -
     *  der Abdruck sieht vollstaendig aus, und ein Fehler in der Verkettung
     *  faellt nie auf. */
    @Test
    fun `jede der fuenf nachgeruesteten Groessen aendert den Abdruck`() {
        val basis = ctx()
        assertNotEquals(basis, ctx(maxIob = 7.0)) { "maxIOB" }
        assertNotEquals(basis, ctx(iobTh = 5.0)) { "iobTH" }
        assertNotEquals(basis, ctx(markerTs = 1_700_000_000_000L)) { "Markerzeitpunkt" }
        assertNotEquals(basis, ctx(markerTier = "2")) { "Marker-Stufe" }
        assertNotEquals(basis, ctx(profil = "B")) { "Profilname" }
    }

    @Test
    fun `ein NEUER Marker bei durchgehend offenem Fenster ist eine andere Lage`() {
        // Der Fall aus der D5-Begruendung: `meal` bleibt wahr, es ist aber eine
        // andere Mahlzeit. Vorher unterschied nichts die beiden Lagen.
        assertNotEquals(
            ctx(meal = true, markerTs = 1_700_000_000_000L),
            ctx(meal = true, markerTs = 1_700_003_600_000L)
        )
    }

    @Test
    fun `ein Profilwechsel mit identischen Zahlen wird erkannt`() {
        // Der zweite Fall aus der Begruendung - gleiches Ziel, gleicher ISF,
        // gleiche Ratios, nur ein anderes Profil.
        assertNotEquals(ctx(profil = "Nacht"), ctx(profil = "Tag"))
    }

    @Test
    fun `angrenzende Textfelder koennen sich nicht gegenseitig aufheben`() {
        // Trennzeichen-Probe: markerTier und profileName grenzen aneinander.
        // Ohne Trenner waere ("12","B") von ("1","2B") ununterscheidbar - eine
        // stille Kollision ausgerechnet im Sicherheitsvergleich.
        assertNotEquals(ctx(markerTier = "12", profil = "B"), ctx(markerTier = "1", profil = "2B"))
    }

    @Test
    fun `winzige Aenderungen bleiben unterscheidbar - keine stille Kollision`() {
        // Sechs Nachkommastellen: ein Ziel von 98,0 gegen 98,000001 ist ein
        // anderer Abdruck. Lieber einmal zu oft verwerfen als einmal zu wenig.
        assertNotEquals(ctx(target = 98.0), ctx(target = 98.000001))
    }
}

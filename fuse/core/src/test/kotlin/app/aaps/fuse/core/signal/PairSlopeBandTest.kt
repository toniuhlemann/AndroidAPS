package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PairSlopeBandTest {

    private val t0 = 1_700_000_000_000L

    /** Eine Reihe mit konstanter Steigung plus optionalem Rauschen. */
    private fun series(n: Int, slopePerMin: Double, noise: (Int) -> Double = { 0.0 }) =
        (0 until n).map { i ->
            BgiAdjustedSeries.AdjustedPoint(t0 + i * 60_000L, 100.0 + i * slopePerMin + noise(i))
        }

    private fun nowOf(n: Int) = t0 + (n - 1) * 60_000L

    // ---- Refactor-Nachweis ------------------------------------------------

    /**
     * Der Median aus [PairSlopeBand] ist BITGLEICH zu
     * [BgiAdjustedSeries.theilSen]. Das ist der Nachweis, dass der Kandidat
     * durch das Herausziehen von `pairSlopes`/`median` nicht angetastet wurde.
     */
    @Test
    fun `mean ist bitgleich zum bisherigen Theil-Sen`() {
        val rnd = Random(4711)
        repeat(20) {
            val n = 8 + rnd.nextInt(20)
            val pts = series(n, rnd.nextDouble(-2.0, 2.0)) { rnd.nextDouble(-3.0, 3.0) }
            val now = nowOf(n)
            val alt = BgiAdjustedSeries.theilSen(pts, now)
            val neu = PairSlopeBand.estimate(pts, now, 50)
            if (alt == null) assertNull(neu)
            else assertEquals(alt, neu!!.mean, 0.0) { "Median darf sich nicht aendern" }
        }
    }

    /** q = 50 ist der AUS-Zustand und muss exakt die Mittelbahn liefern —
     *  sonst waere der Schalter keine Identitaet, sondern eine dritte Variante. */
    @Test
    fun `Quantil 50 ergibt exakt den Median - der Aus-Zustand ist bitgleich`() {
        val pts = series(19, 1.2) { if (it % 3 == 0) 4.0 else -2.0 }
        val e = PairSlopeBand.estimate(pts, nowOf(19), 50)!!
        assertEquals(e.mean, e.lower, 0.0)
        assertEquals(0.0, e.spread, 0.0)
    }

    // ---- Struktur ---------------------------------------------------------

    /** Die Invariante, auf die sich `DriveEstimate` verlaesst: aus diesem Pfad
     *  kann `lower > mean` nie entstehen, fuer KEIN zulaessiges Quantil und
     *  keine Paaranzahl. */
    @Test
    fun `lower ist nie groesser als mean`() {
        val rnd = Random(99)
        repeat(200) {
            val n = 5 + rnd.nextInt(25)
            val pts = series(n, rnd.nextDouble(-3.0, 3.0)) { rnd.nextDouble(-8.0, 8.0) }
            val q = PairSlopeBand.MIN_PCT + rnd.nextInt(PairSlopeBand.MAX_PCT - PairSlopeBand.MIN_PCT + 1)
            val e = PairSlopeBand.estimate(pts, nowOf(n), q) ?: return@repeat
            assertTrue(e.lower <= e.mean) { "q=$q n=$n: lower ${e.lower} > mean ${e.mean}" }
            assertTrue(e.spread >= 0.0)
        }
    }

    /** Kleineres Quantil heisst pessimistischer — monoton, ohne Ausreisser. */
    @Test
    fun `kleineres Quantil ergibt eine nie hoehere Untergrenze`() {
        val pts = series(19, 0.8) { if (it % 2 == 0) 6.0 else -6.0 }
        val now = nowOf(19)
        var vorher = Double.NEGATIVE_INFINITY
        for (q in PairSlopeBand.MIN_PCT..PairSlopeBand.MAX_PCT) {
            val l = PairSlopeBand.estimate(pts, now, q)!!.lower
            assertTrue(l >= vorher - 1e-12) { "q=$q bricht die Monotonie" }
            vorher = l
        }
    }

    /**
     * Auf einer exakt geraden Linie ist die Verteilung entartet: alle
     * Paarsteigungen sind gleich, das Band ist null breit. Genau so gehoert es
     * sich — ein Band, das auf der flachen Linie Abstand erfindet, waere
     * Rauschen, kein Schutz.
     */
    @Test
    fun `ohne Kruemmung gibt es keine Spreizung`() {
        val e = PairSlopeBand.estimate(series(19, 1.0), nowOf(19), 10)!!
        assertEquals(1.0, e.mean, 1e-9)
        assertEquals(0.0, e.spread, 1e-9)
    }

    /**
     * Und die Gegenprobe zur Eigenschaft, die man beim Deuten der ersten Daten
     * kennen muss: eine gekruemmte Reihe (Mahlzeiten-Onset) spreizt das Band
     * auf. Das Band ist damit am breitesten, wenn der Guard am ehesten greift.
     */
    @Test
    fun `Kruemmung spreizt das Band auf`() {
        // quadratischer Anstieg = wachsende Steigung ueber das Fenster
        val pts = (0 until 19).map { i ->
            BgiAdjustedSeries.AdjustedPoint(t0 + i * 60_000L, 100.0 + 0.02 * i * i)
        }
        val e = PairSlopeBand.estimate(pts, nowOf(19), 10)!!
        assertTrue(e.spread > 0.05) { "erwartete deutliche Spreizung, war ${e.spread}" }
    }

    // ---- Datenlage --------------------------------------------------------

    /** Zu wenige Paare ergeben NULL — und ausdruecklich nicht "Mittel ohne
     *  Band". Sonst waere der Null-Abstand ausgerechnet bei der schlechtesten
     *  Datenlage still wieder da. */
    @Test
    fun `zu wenige Paare ergeben null, nicht ein Band ohne Breite`() {
        assertNull(PairSlopeBand.estimate(series(3, 1.0), nowOf(3), 25))
        assertNotNull(PairSlopeBand.estimate(series(19, 1.0), nowOf(19), 25))
    }

    @Test
    fun `die Paaranzahl wird mitgefuehrt`() {
        // 19 Punkte im 18-min-Fenster: C(19,2) = 171 minus 18 Nachbarpaare
        // unter der 2-min-Grenze = 153.
        assertEquals(153, PairSlopeBand.estimate(series(19, 1.0), nowOf(19), 25)!!.pairCount)
    }

    @Test
    fun `der Methodenbezeichner traegt Quantil UND Fenster`() {
        // W18 MUSS die historische Kennung ergeben - sonst waeren alle
        // bisherigen Trail-Zeilen rueckwirkend einem fremden Verfahren
        // zugeordnet.
        assertEquals("TS-PS-Q25-W18-DT2-RF1", PairSlopeBand.methodId(25, 18))
        assertEquals("TS-PS-Q50-W10-DT2-RF1", PairSlopeBand.methodId(50, 10))
        assertTrue(PairSlopeBand.methodId(10, 18) != PairSlopeBand.methodId(25, 18))
        // Vertragspunkt 3 (Toni 23.08.): zwei Fenster = zwei Kennungen.
        assertTrue(PairSlopeBand.methodId(50, 18) != PairSlopeBand.methodId(50, 10))
    }
}

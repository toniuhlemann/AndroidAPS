package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE eCARBS DES BOLUSRECHNERS (Entwurf B) - die Vertraege als Kern-Tests: die Teile wie AAPS, die Ankunft, die
 * Aufnahmezuordnung wie COB mit Verfall und die Ruecknahme w. Alle Zahlen synthetisch. Wie die Bilanz damit rechnet,
 * pruefen `MealBalanceTest` und `ECarbsHclClosedLoopTest`.
 */
class ECarbsTest {

    private val eps = 1e-9
    private val t0 = 1_000_000_000L
    private val min = 60_000L

    // ---- Teile und Ankunft wie AAPS --------------------------------------------------------------------------------

    @Test
    fun `die Teile folgen der AAPS-Rundung und ergeben genau die Menge`() {
        val teile = ECarbs.parts(17.0, t0, 180)
        assertEquals(12, teile.size)
        assertEquals(17.0, teile.sumOf { it.grams }, 0.0)
        teile.forEachIndexed { i, p -> assertEquals(t0 + i * 15 * min, p.ts) }
        assertEquals(listOf(1.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0), teile.map { it.grams })
        // Kurze Dauer: ein Teil.
        assertEquals(listOf(ECarbs.Part(t0, 10.0)), ECarbs.parts(10.0, t0, 10))
        // Wenige Gramm auf viele Teile: Nullteile entfallen, die Summe bleibt.
        val wenig = ECarbs.parts(2.0, t0, 120)
        assertEquals(2.0, wenig.sumOf { it.grams }, 0.0)
        assertTrue(wenig.all { it.grams > 0.0 })
        assertTrue(ECarbs.parts(0.0, t0, 120).isEmpty())
        assertTrue(ECarbs.parts(Double.NaN, t0, 120).isEmpty())
        // Halbe Gramm wie AAPS: der Rest bleibt eine Kommazahl, jeder Teil wird gerundet (17,5 g ueber 30 min: 9 + 9 g).
        assertEquals(listOf(9.0, 9.0), ECarbs.parts(17.5, t0, 30).map { it.grams })
    }

    @Test
    fun `ein Teil kommt erst zu seinem Zeitpunkt an und wirkt dann linear ueber 30 min`() {
        val teile = ECarbs.parts(17.0, t0, 180)
        val ic = 10.0
        // Eine Minute vor dem Beginn: nichts angekommen, keine Wirkung.
        assertEquals(0.0, ECarbs.arrivedU(teile, ic, t0 - min), 0.0)
        assertEquals(0.0, ECarbs.arrivedEffectU(teile, ic, t0 - min), 0.0)
        // Zum Beginn ist der erste Teil (1 g = 0,1 U) da, seine Wirkung beginnt erst.
        assertEquals(0.1, ECarbs.arrivedU(teile, ic, t0), eps)
        assertEquals(0.0, ECarbs.arrivedEffectU(teile, ic, t0), eps)
        // Nach 15 min kommt der zweite Teil an; der erste wirkt zur Haelfte.
        assertEquals(0.2, ECarbs.arrivedU(teile, ic, t0 + 15 * min), eps)
        assertEquals(0.05, ECarbs.arrivedEffectU(teile, ic, t0 + 15 * min), eps)
        // Nach 30 min: der erste wirkt ganz, der zweite halb, der dritte (2 g) kommt an.
        assertEquals(0.4, ECarbs.arrivedU(teile, ic, t0 + 30 * min), eps)
        assertEquals(0.15, ECarbs.arrivedEffectU(teile, ic, t0 + 30 * min), eps)
        // Ungueltiger IC: nichts.
        assertEquals(0.0, ECarbs.arrivedU(teile, 0.0, t0 + 30 * min), 0.0)
        assertEquals(0.0, ECarbs.arrivedEffectU(teile, Double.NaN, t0 + 30 * min), 0.0)
    }

    // ---- Bestaetigung und Ruecknahme w -----------------------------------------------------------------------------

    @Test
    fun `steigt nie heisst hoechstens die Ankuendigung - und die Ruecknahme ist umkehrbar`() {
        assertFalse(ECarbs.judgeable(19.9, 1.0))
        assertFalse(ECarbs.judgeable(30.0, 0.1))
        assertFalse(ECarbs.judgeable(Double.NaN, 1.0))
        assertTrue(ECarbs.judgeable(20.0, 0.2))
        // Solange sich ein Ausbleiben nicht beurteilen laesst, gilt die Ankuendigung voll.
        assertEquals(1.0, ECarbs.withdrawal(0.0, 10.0, 0.5), 0.0)
        // Nichts zugeordnet: c_e = 0 und w = 0.
        val nichts = ECarbs.CobAttribution().confirmation(0.5, 40.0)
        assertEquals(0.0, nichts, 0.0)
        assertEquals(0.0, ECarbs.withdrawal(nichts, 40.0, 0.5), 0.0)
        // Spaeter zeigt sich die Wirkung doch: w steigt wieder.
        val spaeter = ECarbs.CobAttribution(absorbedU = 0.4).confirmation(0.5, 60.0)
        assertEquals(0.8, spaeter, eps)
        assertEquals(0.8, ECarbs.withdrawal(spaeter, 60.0, 0.5), eps)
        // Mehr Wirkung als erwartet: w = 1, nicht mehr - hoechstens der angekuendigte Umfang.
        val mehr = ECarbs.CobAttribution(absorbedU = 2.0).confirmation(0.5, 60.0)
        assertEquals(1.0, mehr, 0.0)
        assertEquals(1.0, ECarbs.withdrawal(mehr, 60.0, 0.5), 0.0)
    }

    // ---- Die Aufnahmezuordnung (Toni 05.11.) -----------------------------------------------------------------------

    /**
     * DIE GEGENPROBE (Toni 05.11.): ein frueherer Aufnahmeueberschuss darf spaeter ankommende eCarbs nicht vorab
     * verbrauchen. Die Sofort-KH laufen schneller als die Kurve: bis t_s steht schon 0,5 U Ueberschuss - und danach
     * kommt keine weitere Aufnahme. Der Rest muss dann die volle angekommene Menge sein.
     */
    @Test
    fun `ein frueherer Aufnahmeueberschuss verbraucht spaeter ankommende eCarbs nicht vorab`() {
        val teile = ECarbs.parts(17.0, t0, 180)
        val ic = 10.0
        fun angekommen(m: Int) = ECarbs.arrivedU(teile, ic, t0 + m * min)
        // Ohne Verfall, damit hier nur die Zuordnung zaehlt.
        fun rest(z: ECarbs.CobAttribution, m: Int) = z.remainingWithLapseU(teile, ic, t0 + m * min, Double.POSITIVE_INFINITY)
        // Von t0 - 60 min an im 5-min-Raster; der Ueberschuss waechst bis t_s auf 0,5 U und bleibt dann stehen.
        var z = ECarbs.CobAttribution()
        for (m in -60..60 step 5) {
            val ueberschuss = if (m <= 0) 0.5 * (m + 60) / 60.0 else 0.5
            z = z.step(ueberschuss, angekommen(m))
        }
        assertEquals(0.0, z.absorbedU, 0.0)
        assertEquals(0.7, rest(z, 60), eps)
        // Waechst der Ueberschuss NACH der Ankunft, wird er zugeordnet - hoechstens bis zur angekommenen Menge ...
        z = z.step(0.5 + 2.0, angekommen(60))
        assertEquals(0.7, z.absorbedU, eps)
        assertEquals(0.0, rest(z, 60), eps)
        // ... und was darueber lag, verfaellt: der naechste Teil (t_s + 75, 1 g) kommt voll in den Rest.
        z = z.step(0.5 + 2.0, angekommen(75))
        assertEquals(0.1, rest(z, 75), eps)
        // Die Zuordnung sinkt nie, auch wenn der Ueberschuss wieder faellt.
        assertEquals(0.7, z.step(0.0, angekommen(75)).absorbedU, eps)
        // Ungueltige Beobachtung: nichts zugeordnet, der Stand bleibt.
        assertEquals(z.absorbedU, z.step(Double.NaN, angekommen(90)).absorbedU, 0.0)
    }

    /**
     * GEGENPROBE 2 (Toni 05.11.): fruehere Sofort-KH-Abweichungen bestaetigen keine spaeteren eCarbs. Bis t_s steht
     * 0,5 U Ueberschuss (schnellere Sofort-KH), danach keine weitere Aufnahme. Die Zuordnung bestaetigt dann nichts.
     */
    @Test
    fun `fruehere Sofort-KH-Abweichungen bestaetigen keine spaeteren eCarbs`() {
        val teile = ECarbs.parts(17.0, t0, 180)
        val ic = 10.0
        var z = ECarbs.CobAttribution()
        for (m in -60..40 step 5) z = z.step(if (m <= 0) 0.5 * (m + 60) / 60.0 else 0.5, ECarbs.arrivedU(teile, ic, t0 + m * min))
        val eArr = ECarbs.arrivedEffectU(teile, ic, t0 + 40 * min)
        assertTrue(ECarbs.judgeable(40.0, eArr))
        // Keine zugeordnete Aufnahme, keine Bestaetigung - und der Rest ist die volle angekommene Menge.
        assertEquals(0.0, z.confirmation(eArr, 40.0), 0.0)
        assertEquals(ECarbs.arrivedU(teile, ic, t0 + 40 * min), z.remainingWithLapseU(teile, ic, t0 + 40 * min), eps)
        // Zeigt sich danach echte Aufnahme ueber der Marke, bestaetigt sie: +0,2 U -> c_e = 0,2 / E_arr.
        val eArr45 = ECarbs.arrivedEffectU(teile, ic, t0 + 45 * min)
        z = z.step(0.7, ECarbs.arrivedU(teile, ic, t0 + 45 * min))
        assertEquals(minOf(1.0, 0.2 / eArr45), z.confirmation(eArr45, 45.0), eps)
    }

    /**
     * GEGENPROBE 3 (Toni 05.11.): eine Messdelle mit anschliessender Rueckkehr verbucht dieselbe Aufnahme nicht noch
     * einmal. Zugeordnet sind 0,3 U; die Delle drueckt den Ueberschuss auf 0, die Rueckkehr hebt ihn wieder auf 0,3 -
     * zugeordnet bleiben 0,3 U. Erst was darueber steigt, ist neue Aufnahme.
     */
    @Test
    fun `eine Messdelle mit Rueckkehr verbucht dieselbe Aufnahme nicht doppelt`() {
        val teile = ECarbs.parts(17.0, t0, 180)
        val ic = 10.0
        fun a(m: Int) = ECarbs.arrivedU(teile, ic, t0 + m * min)
        var z = ECarbs.CobAttribution().step(0.0, a(0))
        z = z.step(0.0, a(30))
        z = z.step(0.3, a(45))
        assertEquals(0.3, z.absorbedU, eps)
        val vorDelle = z.confirmation(ECarbs.arrivedEffectU(teile, ic, t0 + 45 * min), 45.0)
        // Die Delle: der Ueberschuss faellt auf 0 und kehrt auf 0,3 zurueck.
        z = z.step(0.0, a(50))
        z = z.step(0.3, a(55))
        assertEquals(0.3, z.absorbedU, eps)
        // Dieselbe Bestaetigung wie vor der Delle (die Erwartung ist gewachsen, die Zuordnung nicht).
        assertTrue(z.confirmation(ECarbs.arrivedEffectU(teile, ic, t0 + 55 * min), 55.0) <= vorDelle + 1e-12)
        // Neue Aufnahme ueber der Marke zaehlt.
        z = z.step(0.45, a(60))
        assertEquals(0.45, z.absorbedU, eps)
    }

    // ---- Verfall des Rests (Entwurf B 17.8) ------------------------------------------------------------------------

    @Test
    fun `Verfall - ein nicht aufgenommener Teil zaehlt 50 min nach dem Eintreffen nicht mehr`() {
        val t = 1_800_000_000_000L
        val teile = listOf(ECarbs.Part(t, 9.0), ECarbs.Part(t + 15 * 60_000L, 9.0))
        val nichts = ECarbs.CobAttribution()
        assertEquals(50.0, ECarbs.REST_LAPSE_MIN, 0.0)
        // Bei IC 9 ist jeder Teil 1 U. Vor dem Verfall zaehlen beide.
        assertEquals(2.0, nichts.remainingWithLapseU(teile, 9.0, t + 49 * 60_000L), eps)
        // 50 min nach dem ersten Teil verfaellt er, der zweite zaehlt noch; 50 min nach dem zweiten nichts mehr.
        assertEquals(1.0, nichts.remainingWithLapseU(teile, 9.0, t + 50 * 60_000L), eps)
        assertEquals(0.0, nichts.remainingWithLapseU(teile, 9.0, t + 65 * 60_000L), eps)
        // Noch nicht angekommene Teile zaehlen hier nie.
        assertEquals(1.0, nichts.remainingWithLapseU(teile, 9.0, t + 5 * 60_000L), eps)
    }

    @Test
    fun `Verfall - die Aufnahme fuellt zuerst die aelteren Teile, und ohne Frist zaehlt alles Angekommene`() {
        val t = 1_800_000_000_000L
        val teile = listOf(ECarbs.Part(t, 9.0), ECarbs.Part(t + 15 * 60_000L, 9.0))
        // 1,5 U zugeordnet: der erste Teil voll, der zweite halb. Nach dem Verfall des ersten bleibt 0,5 U.
        val halb = ECarbs.CobAttribution(absorbedU = 1.5)
        assertEquals(0.5, halb.remainingWithLapseU(teile, 9.0, t + 55 * 60_000L), eps)
        // 0,5 U zugeordnet: der erste Teil halb - seine offene Haelfte verfaellt, der zweite bleibt ganz.
        val wenig = ECarbs.CobAttribution(absorbedU = 0.5)
        assertEquals(1.0, wenig.remainingWithLapseU(teile, 9.0, t + 55 * 60_000L), eps)
        // Ohne Frist: angekommen minus zugeordnet, wie COB.
        for (now in listOf(t, t + 20 * 60_000L, t + 200 * 60_000L)) {
            val angekommen = ECarbs.arrivedU(teile, 9.0, now)
            assertEquals(maxOf(0.0, angekommen - wenig.absorbedU), wenig.remainingWithLapseU(teile, 9.0, now, Double.POSITIVE_INFINITY), 1e-12)
        }
    }
}

package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Erwartungskonto: Kurve, Budget, Buchung, Pause, Stabilitaet, Ruecknahme und
 * Fensterende. Alle Zahlen sind synthetisch.
 */
class ExpectationAccountTest {

    private val eps = 1e-9
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    /** Synthetische, konvexe Kurve: F(0) = 0, F(120) = 2,0 U. */
    private val kurve: List<Double> = (0 until ExpectationAccount.CURVE_POINTS).map { k ->
        val x = k / (ExpectationAccount.CURVE_POINTS - 1).toDouble()
        2.0 * x * x
    }

    private fun params(
        phiStabil: Double = 0.5,
        phiTief: Double = 0.0,
        schwelle: Double = 0.2,
        ausnahme: Boolean = true,
        h: Int = 120,
    ) = ExpectationAccount.Params.of(kurve, h, phiStabil, phiTief, 100.0, 10, schwelle, ausnahme)!!

    private fun inputs(
        nach: Long,
        bg: Double = 100.0,
        ziel: Double = 100.0,
        isf: Double = 50.0,
        iob: Double = 0.0,
        aktivitaet: Double = 0.0,
        pause: Boolean = false,
        stabil: Boolean = true,
    ) = ExpectationAccount.Inputs(
        nowTs = t0 + nach * min, ageMin = nach.toDouble(), bgMgdl = bg, targetMgdl = ziel, isfMgdlPerU = isf,
        iobTotalU = iob, activityUPerMin = aktivitaet, paused = pause, stableNow = stabil,
    )

    private fun arm(stabil: Boolean = true, huelle: Double = 5.0, p: ExpectationAccount.Params = params()) =
        ExpectationAccount.arm(t0, t0 + 120 * min, huelle, p, stabil, t0, 100.0, 50.0)

    // ---- Kurve ----------------------------------------------------------------

    @Test
    fun `nur eine vollstaendige monotone Kurve ab null ist gueltig`() {
        assertNotNull(ExpectationAccount.Curve.of(kurve))
        assertNull(ExpectationAccount.Curve.of(kurve.dropLast(1)), "falsche Laenge")
        assertNull(ExpectationAccount.Curve.of(kurve.toMutableList().also { it[0] = 0.1 }), "beginnt nicht bei 0")
        assertNull(ExpectationAccount.Curve.of(kurve.toMutableList().also { it[10] = it[9] - 0.01 }), "faellt")
        assertNull(ExpectationAccount.Curve.of(kurve.toMutableList().also { it[5] = Double.NaN }), "nicht endlich")
        assertNull(ExpectationAccount.Curve.of(List(ExpectationAccount.CURVE_POINTS) { 0.0 }), "leer")
    }

    @Test
    fun `die Kurve interpoliert linear und der Rest endet am Kurvenende`() {
        val c = ExpectationAccount.Curve.of(kurve)!!
        assertEquals((kurve[0] + kurve[1]) / 2, c.at(2.5), eps)
        assertEquals(kurve.last(), c.remaining(0.0, 120), eps)
        assertEquals(kurve.last() - kurve[12], c.remaining(60.0, 120), eps)
        assertEquals(kurve[6], c.remaining(0.0, 30), eps)
        assertEquals(0.0, c.remaining(130.0, 120), eps)
    }

    @Test
    fun `ungueltige Einstellungen schalten die Erwartung ab`() {
        assertNull(ExpectationAccount.Params.of(kurve, 120, 1.5, 0.0, 100.0, 10, 0.2, true))
        assertNull(ExpectationAccount.Params.of(kurve, 20, 0.5, 0.0, 100.0, 10, 0.2, true))
        assertNull(ExpectationAccount.Params.of(kurve, 120, 0.5, 0.0, 100.0, 3, 0.2, true))
        assertNull(ExpectationAccount.Params.of(kurve, 120, 0.5, 0.0, 100.0, 10, 3.0, true))
        assertNull(ExpectationAccount.Params.of(kurve, 120, 0.5, 0.0, 40.0, 10, 0.2, true))
    }

    // ---- Buchung am Marker ------------------------------------------------------

    @Test
    fun `das Budget folgt dem Ausgangszustand und ist durch die Huelle begrenzt`() {
        val stabil = arm(stabil = true)
        assertEquals(0.5 * kurve.last(), stabil.a0U, eps)
        assertTrue(stabil.released)
        val tief = arm(stabil = false, p = params(phiTief = 0.25))
        assertEquals(0.25 * kurve.last(), tief.a0U, eps)
        assertFalse(tief.released)
        assertEquals(0.4, arm(huelle = 0.4).a0U, eps)
        assertEquals(0.0, arm(huelle = 0.0).a0U, eps, "ohne Vorschuss: kein Budget")
    }

    // ---- Zyklus -----------------------------------------------------------------

    @Test
    fun `R_mess und R_roh folgen der Formel`() {
        val (_, e) = ExpectationAccount.step(arm(), inputs(nach = 1, bg = 150.0, iob = 0.4))
        val zErwartet = 0.5 * ExpectationAccount.Curve.of(kurve)!!.remaining(1.0, 120)
        assertEquals(50.0 / 50.0 - 0.4, e.rMessU, eps)
        assertEquals(zErwartet, e.phiZU, eps)
        assertEquals(e.rMessU + zErwartet, e.rRohU, eps)
        assertEquals(arm().a0U, e.restEffU, eps)
        assertEquals("ACTIVE", e.status)
    }

    @Test
    fun `in der Pause und vor der Stabilitaet fliesst keine Erwartung`() {
        assertEquals(0.0, ExpectationAccount.step(arm(), inputs(nach = 1, pause = true)).second.restEffU, eps)
        val wartend = arm(stabil = false, p = params(phiTief = 0.25))
        val (s1, e1) = ExpectationAccount.step(wartend, inputs(nach = 1, stabil = false))
        assertEquals(0.0, e1.restEffU, eps)
        assertEquals("WAITING_FOR_STABILITY", e1.status)
        val (_, e2) = ExpectationAccount.step(s1, inputs(nach = 2, stabil = true))
        assertTrue(e2.released)
        assertEquals(wartend.a0U, e2.restEffU, eps)
    }

    @Test
    fun `nach dem Fensterende gibt es weder Erwartung noch Rest`() {
        val (_, e) = ExpectationAccount.step(arm(), inputs(nach = 120))
        assertTrue(e.expired)
        assertEquals(0.0, e.phiZU, eps)
        assertEquals(0.0, e.restEffU, eps)
    }

    @Test
    fun `die Ruecknahme friert bei +30 ein und gibt bei erreichter Schwelle wieder frei`() {
        var s = arm()
        // Bis +30 kaum Stoerung: BG steigt nur um 2 mg/dl (0,04 U bei ISF 50).
        for (m in 1L..30L) s = ExpectationAccount.step(s, inputs(nach = m, bg = 100.0 + m * 2.0 / 30)).first
        assertTrue(s.releaseChecked)
        assertTrue(s.frozen)
        // Danach steigt BG deutlich: die Schwelle 0,2 U (10 mg/dl) wird erreicht.
        var e: ExpectationAccount.Evaluation? = null
        for (m in 31L..45L) ExpectationAccount.step(s, inputs(nach = m, bg = 102.0 + (m - 30) * 2.0)).also { s = it.first; e = it.second }
        assertFalse(s.frozen)
        assertEquals(s.restU, e!!.restEffU, eps)
    }

    @Test
    fun `eine lange Messluecke macht die Stoerung ungueltig und haelt den Rest eingefroren`() {
        var s = ExpectationAccount.step(arm(), inputs(nach = 1)).first
        s = ExpectationAccount.step(s, inputs(nach = 20, bg = 160.0)).first
        assertFalse(s.disturbanceValid)
        s = ExpectationAccount.step(s, inputs(nach = 30, bg = 170.0)).first
        assertTrue(s.frozen)
        s = ExpectationAccount.step(s, inputs(nach = 40, bg = 200.0)).first
        assertTrue(s.frozen)
    }

    @Test
    fun `die Stoerung wird aus BG-Aenderung und Insulinwirkung fortgeschrieben`() {
        // 1 min, BG +5 mg/dl, Aktivitaet 0,02 U/min bei ISF 50: (5 + 1) / 50 = 0,12 U.
        val s = ExpectationAccount.step(arm(), inputs(nach = 1, bg = 105.0, aktivitaet = 0.02)).first
        assertEquals(0.12, s.disturbanceU, 1e-9)
    }

    // ---- Lieferung und Buchung ---------------------------------------------------

    @Test
    fun `liefern darf der Zyklus hoechstens den gemessenen Anteil plus den Rest`() {
        val (_, e) = ExpectationAccount.step(arm(), inputs(nach = 1, bg = 125.0))
        // R_mess = 0,5 U; Rest = 1,0 U; R_roh = 0,5 + phi*Z.
        assertEquals(minOf(e.requestU, 0.5 + e.restEffU), ExpectationAccount.deliverableU(e), eps)
        assertEquals(minOf(e.restEffU, e.requestU - 0.5), ExpectationAccount.expectationShareU(e), eps)
    }

    @Test
    fun `ohne Anforderung ist auch nichts aus der Erwartung lieferbar`() {
        // R_mess stark negativ: R_roh <= 0.
        val (_, e) = ExpectationAccount.step(arm(), inputs(nach = 1, bg = 80.0, iob = 3.0))
        assertTrue(e.rRohU <= 0.0)
        assertEquals(0.0, ExpectationAccount.deliverableU(e), eps)
        assertEquals(0.0, ExpectationAccount.expectationShareU(e), eps)
    }

    @Test
    fun `die Buchung teilt nach Normalpfad und verbraucht nur d_erw`() {
        val (s0, e) = ExpectationAccount.step(arm(), inputs(nach = 1, bg = 125.0))
        val (s1, split) = ExpectationAccount.book(s0, e, finalU = 0.8, normalPassedU = 0.5)
        assertEquals(0.5, split.dMessU, eps)
        assertEquals(0.3, split.dErwU, eps)
        assertEquals(0.0, split.violationU, eps)
        assertEquals(0.3, s1.bookedU, eps)
        // Guard sperrte den Normalpfad: alles ist d_erw.
        val (s2, split2) = ExpectationAccount.book(s1, e, finalU = 0.2, normalPassedU = 0.0)
        assertEquals(0.2, split2.dErwU, eps)
        assertEquals(0.5, s2.bookedU, eps)
    }

    @Test
    fun `das Budget wird nie aufgefuellt und Ueberschreitung wird gemeldet`() {
        var (s, e) = ExpectationAccount.step(arm(), inputs(nach = 1, bg = 125.0))
        s = ExpectationAccount.book(s, e, finalU = e.restEffU, normalPassedU = 0.0).first
        assertEquals(0.0, s.restU, eps)
        val (s2, e2) = ExpectationAccount.step(s, inputs(nach = 2, bg = 125.0))
        assertEquals(0.0, e2.restEffU, eps)
        val split = ExpectationAccount.book(s2, e2, finalU = 0.3, normalPassedU = 0.0).second
        assertEquals(0.3, split.violationU, eps)
    }

    @Test
    fun `die Direktdosis bucht voll und der Ueberschuss ist durch die Markerautorisierung gedeckt`() {
        val s = ExpectationAccount.bookDirectDose(arm(), 1.6)
        assertEquals(1.6, s.bookedU, eps)
        assertEquals(1.6, s.directDoseU, eps)
        assertEquals(0.6, s.coveredByMarkerAuthU, eps)
        assertEquals(0.0, s.restU, eps)
    }

    // ---- Stabilitaet ----------------------------------------------------------------

    @Test
    fun `die Stabilitaetsstrecke laeuft nur ohne Fallen, ohne Sperre und ueber der Grenze`() {
        val st = ExpectationAccount.Stability
        val s1 = st.since(null, t0, 110.0, 100.0, falling = false, measuredLock = false)
        assertEquals(t0, s1)
        assertEquals(t0, st.since(s1, t0 + min, 110.0, 100.0, falling = false, measuredLock = false))
        assertNull(st.since(s1, t0 + min, 95.0, 100.0, falling = false, measuredLock = false))
        assertNull(st.since(s1, t0 + min, 110.0, 100.0, falling = true, measuredLock = false))
        assertNull(st.since(s1, t0 + min, 110.0, 100.0, falling = false, measuredLock = true))
        assertFalse(st.stable(t0, t0 + 9 * min, 10))
        assertTrue(st.stable(t0, t0 + 10 * min, 10))
    }
}

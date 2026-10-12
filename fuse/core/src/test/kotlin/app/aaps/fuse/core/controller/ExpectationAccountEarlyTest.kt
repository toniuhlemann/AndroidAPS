package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Das Konto mit frueher Versorgung (E-034 v2): Buchung, Anfangsportion, weitere Portionen. Synthetische Werte. */
class ExpectationAccountEarlyTest {

    private val t0 = 1_700_000_000_000L
    private val wirkung: (Double) -> Double = { t -> EarlySupply.orefActionFraction(t, 45.0, 540.0) }

    /** Synthetisch: gleichmaessig 1 U je 30 min bis 120 min. */
    private val werte = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0)).values()
    private val params = requireNotNull(ExpectationAccount.Params.of(werte, 120, 0.5, 0.5, 90.0, 15, 0.3, true))
    private val einstellung = requireNotNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.0, 2.0, 10))
    private val alle = EarlySupply.Switches(true, true, true, true)

    private fun konto(stabil: Boolean = true) = ExpectationAccount.armEarly(
        authId = t0, windowEndTs = t0 + 120 * 60_000L, params = params, settings = einstellung, switches = alle,
        startStable = stabil, nowTs = t0, bgMgdl = 100.0, isfMgdlPerU = 50.0, unitAction = wirkung,
    )

    private fun eingaben(iobU: Double = 0.0) = ExpectationAccount.EarlyInputs(
        iobU = iobU, freeIobU = iobU, existingActionU = { t -> iobU * wirkung(t) }, unitAction = wirkung,
    )

    private fun schritt(
        s: ExpectationAccount.State, min: Int, bg: Double = 100.0, iobU: Double = 0.0, pause: Boolean = false,
        early: ExpectationAccount.EarlyInputs? = eingaben(iobU),
    ) = ExpectationAccount.step(
        s,
        ExpectationAccount.Inputs(
            nowTs = t0 + min * 60_000L, ageMin = min.toDouble(), bgMgdl = bg, targetMgdl = 100.0, isfMgdlPerU = 50.0,
            iobTotalU = iobU, activityUPerMin = 0.0, paused = pause, stableNow = true, early = early,
        ),
    )

    @Test
    fun `am Marker wird das Gesamtbudget gebucht, nicht die Huelle - ohne v1-Ausnahme`() {
        val s = konto()
        val kurve = requireNotNull(ExpectationAccount.Curve.of(werte))
        assertEquals(EarlySupply.budgetU(kurve, 120, 1.0, 8.0, wirkung), s.a0U, 1e-12)
        assertEquals(1.0, s.phi0, 0.0)
        assertFalse(s.guardException)
        assertNotNull(s.early)
        // Nach Tief: der eigene Skalierer.
        assertEquals(EarlySupply.budgetU(kurve, 120, 0.5, 8.0, wirkung), konto(stabil = false).a0U, 1e-12)
    }

    @Test
    fun `die Anfangsportion ist hoechstens alpha mal B und hoechstens die Anforderung`() {
        val (s, e) = schritt(konto(), 0)
        val f = requireNotNull(e.early)
        assertTrue(f.initial)
        assertEquals(0.5 * s.a0U, e.restEffU, 1e-12)
        assertEquals(minOf(f.demandU, 0.5 * s.a0U), ExpectationAccount.deliverableU(e), 1e-12)
        // Vorhandenes freies Insulin senkt die Anforderung - und damit die Anfangsportion.
        val (_, mitIob) = schritt(konto(), 0, iobU = 3.0)
        assertTrue(mitIob.early!!.demandU < f.demandU)
    }

    @Test
    fun `nach der Anfangsportion gelten maximale Portion und Menge je Zeitraum - ohne Ansparen`() {
        var (s, e) = schritt(konto(), 0)
        s = ExpectationAccount.book(s, e, e.restEffU).first
        assertTrue(s.initialDone)
        // Zwei weitere Portionen zu je 1,0 U fuellen den Zeitraum (2,0 U je 10 min).
        for (m in 1..2) {
            val (s2, e2) = schritt(s, m)
            assertFalse(e2.early!!.initial)
            assertEquals(1.0, e2.restEffU, 1e-12)
            s = ExpectationAccount.book(s2, e2, 1.0).first
        }
        val (s3, e3) = schritt(s, 3)
        assertEquals(0.0, e3.restEffU, 0.0)
        assertEquals(0.0, e3.early!!.periodLeftU, 0.0)
        // Lange Pause: danach wieder hoechstens EINE Portion, nichts aus den verpassten Zyklen.
        var t = s3
        for (m in 4..9) t = schritt(t, m, pause = true).first
        val (_, e4) = schritt(t, 11)
        assertEquals(1.0, e4.restEffU, 1e-12)
        val (_, e5) = schritt(t, 12)
        assertTrue(e5.restEffU <= 1.0 + 1e-12)
    }

    @Test
    fun `die Anfangsportion verfaellt nach ihrem Fenster, auch ungeliefert`() {
        var s = konto()
        for (m in 0 until EarlySupply.INITIAL_WINDOW_MIN.toInt()) s = schritt(s, m, pause = true).first
        val (danach, e) = schritt(s, EarlySupply.INITIAL_WINDOW_MIN.toInt())
        assertTrue(danach.initialDone)
        assertFalse(e.early!!.initial)
        assertTrue(e.restEffU <= 1.0 + 1e-12)
    }

    @Test
    fun `Budget wird nie ueberschritten, und d_mess zaehlt hoechstens, was der Normalpfad freigab`() {
        var s = konto()
        var summe = 0.0
        for (m in 0..119) {
            val (s2, e) = schritt(s, m, bg = 160.0)
            // Der Normalpfad gab vom gemessenen Anteil nur die Haelfte frei.
            val gemessen = 0.5 * ExpectationAccount.measuredShareU(e)
            val (s3, split) = ExpectationAccount.book(s2, e, gemessen + ExpectationAccount.expectationShareU(e), gemessen)
            assertEquals(0.0, split.violationU, 0.0)
            assertTrue(split.dMessU <= gemessen + 1e-12)
            summe += split.dErwU
            s = s3
        }
        assertTrue(summe <= s.a0U + 1e-9)
        assertEquals(summe, s.bookedU, 1e-9)
    }

    @Test
    fun `ohne Insulinmodell gibt es keinen Erwartungsanteil`() {
        val (_, e) = schritt(konto(), 0, early = null)
        assertEquals(0.0, e.restEffU, 0.0)
        assertEquals("NO_MODEL", e.status)
    }

    @Test
    fun `bleibt die Mahlzeit aus, friert die Ruecknahme bei +30 die fruehe Versorgung ein`() {
        var s = konto()
        for (m in 0..30) s = schritt(s, m).first
        assertTrue(s.frozen)
        val (_, e) = schritt(s, 31)
        assertEquals(0.0, e.restEffU, 0.0)
        assertEquals("FROZEN", e.status)
    }

    @Test
    fun `die v1-Ausnahme gilt in v2 nie`() {
        val (_, e) = schritt(konto(), 0)
        assertEquals(0.0, ExpectationAccount.guardExceptionFloorU(e, normalPassedU = 10.0), 0.0)
    }
}

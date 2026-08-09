package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Der gemessene Fall 09.08.: Knopfdruck 10:46, Clearance sperrt 15 min, die
 * 2,00-U-Huelle verfaellt ungenutzt. Das Fenster zaehlt jetzt LIEFERBARE
 * Minuten, mit absoluter Wanduhr-Kappe.
 */
class PrimeWindowDeliverableTest {

    private val t0 = 1_786_265_160_000L

    private fun input(nowMin: Long, windowStartMin: Long?, minLower: Double) = PrimeRelease.Input(
        enabled = true,
        mealMarkerActive = true,
        armedTsMs = t0,
        windowStartTsMs = windowStartMin?.let { t0 + it * 60_000L } ?: 0L,
        nowMs = t0 + nowMin * 60_000L,
        envelopeU = 1.2,
        spentU = 0.0,
        safetyMinLowerMgdl = minLower,
        guardFloorMgdl = 70.0,
        isfMgdlPerU = 90.0,
        pumpIncrementU = 0.05,
    )

    /** minLower 80 gegen Clearance 0,2*1,2*90 = 21,6 -> 58,4 < 70 = gesperrt. */
    private val blockt = 80.0

    /** minLower 100 -> 78,4 >= 70 = frei. */
    private val frei = 100.0

    @Test
    fun `gesperrte Minuten verbrauchen das Fenster NICHT`() {
        // Ohne Nachschieben waere Minute 16 vorbei.
        assertEquals("WINDOW_OVER", PrimeRelease.plan(input(16, null, frei)).reason)
        // Mit nachgeschobenem Start (Aufrufer hat 15 min lang gesperrt) laeuft es.
        val p = PrimeRelease.plan(input(16, 15, frei))
        assertTrue(p.active) { "nach gesperrten Minuten muss die Freigabe noch leben, war ${p.reason}" }
        assertEquals("PRIME", p.reason)
    }

    @Test
    fun `die Sperre selbst bleibt eine Sperre`() {
        assertEquals("CLEARANCE", PrimeRelease.plan(input(3, null, blockt)).reason)
    }

    @Test
    fun `die Wanduhr kappt absolut - die Wette gilt dem blinden Kopf`() {
        // Selbst mit staendig nachgeschobenem Start ist bei 45 min Schluss.
        assertEquals("WINDOW_OVER_WALL", PrimeRelease.plan(input(45, 44, frei)).reason)
        assertEquals("WINDOW_OVER_WALL", PrimeRelease.plan(input(60, 59, frei)).reason)
        // Eine Minute davor lebt sie noch.
        assertEquals("PRIME", PrimeRelease.plan(input(44, 43, frei)).reason)
    }

    @Test
    fun `ein zurueckgefallener Stempel verlaengert nichts`() {
        // windowStart VOR dem Knopfdruck darf das Fenster nicht aufziehen.
        val p = PrimeRelease.plan(
            input(16, null, frei).copy(windowStartTsMs = t0 - 30 * 60_000L)
        )
        assertEquals("WINDOW_OVER", p.reason)
    }

    @Test
    fun `die Verteilung rechnet mit dem lieferbaren Rest, nicht der Wanduhr`() {
        // Start bei Minute 15, jetzt Minute 20 -> 5 verbraucht, 10 uebrig.
        val p = PrimeRelease.plan(input(20, 15, frei))
        assertEquals("PRIME", p.reason)
        // 1,2 U / 10 min = 0,12 -> auf 0,05er Schritte abgerundet = 0,10
        assertEquals(0.10, p.floorU, 1e-9)
    }
}

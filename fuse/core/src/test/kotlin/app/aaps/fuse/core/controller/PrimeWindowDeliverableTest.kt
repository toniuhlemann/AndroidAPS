package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Der Anlassfall: ab dem Knopfdruck sperrt die Clearance 15 min, die
 * Huelle verfaellt ungenutzt. Das Fenster zaehlt jetzt LIEFERBARE
 * Minuten, mit absoluter Wanduhr-Kappe.
 */
class PrimeWindowDeliverableTest {

    private val t0 = 1_700_000_000_000L

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
        isfMgdlPerU = 80.0,
        pumpIncrementU = 0.05,
    )

    /** Clearance rechnet seit 09.08. gegen den ZYKLUS-ANTEIL: Huelle 1,2 auf
     *  15 min -> 0,05 U, Bedarf 0,2*0,05*80 = 0,8 mg/dl. 70,5 - 0,8 < 70. */
    private val blockt = 70.5

    /** minLower 100 -> 80,8 >= 70 = frei. */
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

/**
 * Clearance gegen den ZYKLUS-ANTEIL (Tonis Entscheidung 09.08.).
 * Testlage: L-Huelle 2,5 U, ISF 80, Boden 70.
 */
class PrimeClearancePerCycleTest {

    private val t0 = 1_700_000_000_000L

    private fun input(minLower: Double, envelope: Double = 2.5, nowMin: Long = 0) = PrimeRelease.Input(
        enabled = true, mealMarkerActive = true, armedTsMs = t0, windowStartTsMs = 0L,
        nowMs = t0 + nowMin * 60_000L, envelopeU = envelope, spentU = 0.0,
        safetyMinLowerMgdl = minLower, guardFloorMgdl = 70.0, isfMgdlPerU = 80.0,
        pumpIncrementU = 0.05,
    )

    @Test
    fun `der Anlassfall mit grosser Huelle oeffnet jetzt`() {
        // Alt: 0,2*2,5*80 = 40 -> minLower >= 110 noetig, 105 fiel durch.
        // Neu: Zyklusanteil 2,5/15 = 0,167 -> auf 0,05 abgerundet 0,15 -> 0,2*0,15*80 = 2,4.
        val p = PrimeRelease.plan(input(105.0))
        assertEquals("PRIME", p.reason)
        assertEquals(0.15, p.floorU, 1e-9)
    }

    @Test
    fun `nahe am Boden sperrt es weiterhin`() {
        // minLower 71, Zyklusanteil 0,15 -> 71 - 2,4 = 68,6 < 70.
        assertEquals("CLEARANCE", PrimeRelease.plan(input(71.0)).reason)
    }

    @Test
    fun `die grosse Huelle bestraft den Start nicht mehr`() {
        // Kleine und grosse Huelle haben am Fensteranfang denselben Bedarf je
        // Zyklus-Schritt, nur die Schrittgroesse unterscheidet sich.
        val klein = PrimeRelease.plan(input(105.0, envelope = 1.2))
        val gross = PrimeRelease.plan(input(105.0, envelope = 2.5))
        assertEquals("PRIME", klein.reason)
        assertEquals("PRIME", gross.reason)
        assertTrue(gross.floorU >= klein.floorU) { "die groessere Huelle darf nicht WENIGER liefern" }
    }
}

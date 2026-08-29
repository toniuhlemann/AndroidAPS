package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER B1-VERTRAG DER VERBINDLICHEN ENDPRUEFUNG (Bauauftrag 5.1) auf
 * Komponentenebene: Kontextwahl, min der drei Grenzen mit Namen,
 * Belegungs-Semantik, Rasterung nach unten, Teilkappung vs. Vollblock.
 */
class ExposureGateTest {

    /** Anlassfall 27.08. in Zahlen: iobTH/maxIOB liessen 4-6 U Luft, die
     *  Kontextgrenze haette gebunden - sie ist das min und traegt ihren
     *  Namen. */
    @Test
    fun `die Kontextgrenze bindet und traegt ihren Namen`() {
        val r = ExposureGate.pruefe(
            requestedU = 0.55, mealAuthorized = false,
            correctionLimitU = 2.5, mealLimitU = 6.0,
            iobThU = 8.0, maxIobU = 8.0,
            capIobU = 2.2, transportU = 0.0, pumpIncrementU = 0.05,
        )
        assertTrue(r.bindet)
        assertFalse(r.blocked)
        assertEquals(0.30, r.cappedU, 1e-9)
        assertEquals("correctionExposureLimit", r.binding)
        assertEquals(2.5, r.effectiveLimitU, 1e-12)
    }

    /** Unter MEAL-Vollmacht gilt die MEAL-Grenze - dieselbe Lage bindet
     *  nicht mehr. */
    @Test
    fun `unter Vollmacht gilt die MEAL-Grenze`() {
        val r = ExposureGate.pruefe(
            requestedU = 0.55, mealAuthorized = true,
            correctionLimitU = 2.5, mealLimitU = 6.0,
            iobThU = 8.0, maxIobU = 8.0,
            capIobU = 2.2, transportU = 0.0, pumpIncrementU = 0.05,
        )
        assertFalse(r.bindet)
        assertEquals(0.55, r.cappedU, 1e-12)
    }

    /** Erschoepfter Raum: Anforderung > 0 wird vollstaendig genullt -
     *  Block-Signal fuer EXPOSURE_LIMIT. */
    @Test
    fun `ein erschoepfter Raum blockt vollstaendig`() {
        val r = ExposureGate.pruefe(
            requestedU = 0.30, mealAuthorized = false,
            correctionLimitU = 2.0, mealLimitU = 6.0,
            iobThU = 8.0, maxIobU = 8.0,
            capIobU = 2.1, transportU = 0.0, pumpIncrementU = 0.05,
        )
        assertTrue(r.blocked)
        assertEquals(0.0, r.cappedU, 1e-12)
        assertEquals(0.0, r.headroomU, 1e-12)
    }

    /** Transporthaftung belegt wie Bestand (C3-02-Richtung). */
    @Test
    fun `Transporthaftung verengt den Raum`() {
        val ohne = ExposureGate.pruefe(0.55, false, 2.5, 6.0, 8.0, 8.0, 2.0, 0.0, 0.05)
        val mit = ExposureGate.pruefe(0.55, false, 2.5, 6.0, 8.0, 8.0, 2.0, 0.30, 0.05)
        assertTrue(mit.cappedU < ohne.cappedU)
    }

    /** Rasterung NACH UNTEN: die Endpruefung rundet nie auf. */
    @Test
    fun `die Rasterung rundet nie auf`() {
        val r = ExposureGate.pruefe(
            requestedU = 0.55, mealAuthorized = false,
            correctionLimitU = 2.5, mealLimitU = 6.0,
            iobThU = 8.0, maxIobU = 8.0,
            capIobU = 2.21, transportU = 0.0, pumpIncrementU = 0.05,
        )
        // headroom 0,29 -> Raster 0,25, nie 0,30.
        assertEquals(0.25, r.cappedU, 1e-9)
    }

    /** iobTH/maxIOB bleiben harte Obergrenzen im selben min - ist eine von
     *  ihnen die engste, traegt SIE den Namen. */
    @Test
    fun `die engste Grenze gewinnt und wird benannt`() {
        val r = ExposureGate.pruefe(
            requestedU = 0.55, mealAuthorized = true,
            correctionLimitU = 2.5, mealLimitU = 9.0,
            iobThU = 4.8, maxIobU = 8.0,
            capIobU = 4.6, transportU = 0.0, pumpIncrementU = 0.05,
        )
        assertEquals("iobThHeadroom", r.binding)
        assertTrue(r.bindet)
        assertEquals(0.20, r.cappedU, 1e-9)
    }

    /** Anforderung 0 bindet nie - ein leerer Zyklus ist kein Block. */
    @Test
    fun `eine Null-Anforderung bindet nie`() {
        val r = ExposureGate.pruefe(0.0, false, 2.0, 6.0, 8.0, 8.0, 5.0, 0.0, 0.05)
        assertFalse(r.bindet)
        assertFalse(r.blocked)
        assertEquals(0.0, r.cappedU, 1e-12)
    }
}

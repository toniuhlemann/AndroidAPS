package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnsetChannelTest {

    private fun s(minute: Int, ukf: Double, fast: Double = ukf) =
        OnsetChannel.Sample(1_700_000_000_000L + minute * 60_000L, ukf, fast)

    private fun input(
        samples: List<OnsetChannel.Sample>,
        r: Double = 0.2,
        enabled: Boolean = true,
        threshold: Double = 0.5,
        outlier: Boolean = false,
        marker: Boolean = false,
        envelope: Double = 1.5,
        spent: Double = 0.0,
    ) = OnsetChannel.Input(enabled, samples, r, threshold, outlier, marker, envelope, spent)

    // ---- Die drei gemessenen Lagen ----------------------------------------

    /** Fruehstueck 07.08., 09:11-09:13: ukfRate 0,94 / 1,59 / 1,84 bei r 0,43.
     *  Genau hier soll der Kanal oeffnen - zwei bis sechs Minuten vor der
     *  Rampe. */
    @Test
    fun `der gemessene Fruehstuecks-Onset oeffnet den Kanal`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.94, 1.4), s(1, 1.59, 2.0), s(2, 1.84, 2.3)), r = 0.43))
        assertTrue(res.active)
        assertEquals(1.4, res.driveMgdlPerMin!!, 1e-12) // MIN der drei, nicht der juengste
        assertEquals("OPEN", res.reason)
    }

    /** Die Nacht 06./07.08.: fastDrive ~3 durch eigene Aktivitaet, rohe Rate
     *  um NULL. Das kinematische Gate laesst die Rueckkopplung nicht durch -
     *  Insulin kann die rohe Rate nicht heben. */
    @Test
    fun `die Nacht-Rueckkopplung kann den Kanal nicht oeffnen`() {
        val res = OnsetChannel.evaluate(input(
            listOf(s(0, -0.17, 3.0), s(1, -0.24, 3.1), s(2, -0.28, 3.0)),
            r = 0.3, // Uebergabe zieht hier nicht, das Gate muss es tun
        ))
        assertFalse(res.active)
        assertEquals("UKF_BELOW_THR", res.reason)
    }

    /** 16:08 am 06.08. (Wiederanstieg bei IOB): r war laengst ueber der
     *  Schwelle - der Kanal ist SCHLAFEND, die normale Rampe traegt. Die
     *  Huelle wird fuer bestaetigte Anstiege nicht verbraucht. */
    @Test
    fun `nach Theil-Sen-Bestaetigung ist der Kanal schlafend`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.59), s(1, 0.93), s(2, 1.20)), r = 2.34))
        assertFalse(res.active)
        assertEquals("R_CONFIRMED", res.reason)
    }

    // ---- Persistenz und Signalqualitaet -----------------------------------

    @Test
    fun `ein einzelner Ausschlag oeffnet nichts`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.1), s(1, 0.2), s(2, 2.5))))
        assertFalse(res.active)
        assertEquals("UKF_BELOW_THR", res.reason)
    }

    @Test
    fun `eine Messluecke bricht die Persistenz`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.9), s(2, 1.0), s(5, 1.1))))
        assertEquals("GAP", res.reason)
    }

    @Test
    fun `ein Ausreisser-Zyklus oeffnet nicht`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.9), s(1, 1.0), s(2, 1.1)), outlier = true))
        assertEquals("OUTLIER", res.reason)
    }

    @Test
    fun `weniger als drei Werte reichen nicht`() {
        assertEquals("TOO_FEW_SAMPLES", OnsetChannel.evaluate(input(listOf(s(0, 0.9), s(1, 1.0)))).reason)
    }

    @Test
    fun `abgeschaltet heisst abgeschaltet`() {
        assertEquals("DISABLED", OnsetChannel.evaluate(input(listOf(s(0, 2.0), s(1, 2.0), s(2, 2.0)), enabled = false)).reason)
    }

    // ---- Haftungshuelle ---------------------------------------------------

    @Test
    fun `die verbrauchte Huelle schliesst den Kanal`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.9), s(1, 1.0), s(2, 1.1)), spent = 1.5))
        assertFalse(res.active)
        assertEquals("ENVELOPE_SPENT", res.reason)
        assertEquals(0.0, res.remainingU, 0.0)
        assertNull(res.driveMgdlPerMin)
    }

    @Test
    fun `der Rest der Huelle wird beziffert`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.9), s(1, 1.0), s(2, 1.1)), spent = 0.9))
        assertTrue(res.active)
        assertEquals(0.6, res.remainingU, 1e-12)
    }

    /** Der Kanal traegt seinen Antrieb aus dem MINIMUM der letzten drei -
     *  der juengste Einzelwert ist der rauschanfaelligste. */
    @Test
    fun `der Antrieb ist das Minimum der letzten drei`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.8, 2.9), s(1, 1.2, 1.1), s(2, 2.0, 3.5))))
        assertEquals(1.1, res.driveMgdlPerMin!!, 1e-12)
    }

    // ---- Mahlzeiten-Marker ------------------------------------------------

    /** Gemessen 07.08.: Marker 08:50, erste CGM-Regung 08:51 - mit Marker
     *  reicht EIN Wert ueber der Schwelle. */
    @Test
    fun `mit Marker reicht ein einziger Wert ueber der Schwelle`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.9, 1.2)), marker = true))
        assertTrue(res.active)
        assertTrue(res.mealMarker)
        assertEquals("OPEN_MARKER", res.reason)
        assertEquals(1.2, res.driveMgdlPerMin!!, 1e-12)
    }

    /** Der Marker senkt die Schwelle NICHT - ein flacher Verlauf bleibt auch
     *  mit Marker geschlossen. Der Knopf allein erzeugt keine Dosis. */
    @Test
    fun `der Marker allein oeffnet nichts`() {
        val res = OnsetChannel.evaluate(input(listOf(s(0, 0.1, 0.1)), marker = true))
        assertFalse(res.active)
        assertEquals("UKF_BELOW_THR", res.reason)
    }

    /** Auch mit Marker gelten Ausreisser-Gate und Uebergabe an die Rampe. */
    @Test
    fun `Marker hebelt weder Ausreisser noch Uebergabe aus`() {
        assertEquals("OUTLIER", OnsetChannel.evaluate(input(listOf(s(0, 0.9)), marker = true, outlier = true)).reason)
        assertEquals("R_CONFIRMED", OnsetChannel.evaluate(input(listOf(s(0, 0.9)), marker = true, r = 2.0)).reason)
    }
}

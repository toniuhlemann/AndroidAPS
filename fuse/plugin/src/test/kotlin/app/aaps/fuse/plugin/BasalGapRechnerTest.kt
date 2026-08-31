package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Schritt B: die pure Nullphasen-Rechnung der Basalluecke.
 * Synthetischer Referenzfall: Null seit 84 min beim Marker,
 * Profil 0,46 U/h -> ausgelassenes Basal ~0,64 U.
 */
class BasalGapRechnerTest {

    private val stepMs = 60_000L

    private fun slices(
        n: Int,
        tbr: (Int) -> Double?,
        profil: (Int) -> Double? = { 0.46 },
    ): List<BasalGapRechner.Slice> =
        (0 until n).map { BasalGapRechner.Slice(it * stepMs, tbr(it), profil(it)) }

    @Test
    fun `Referenzfall - 84 Minuten Null ergeben rund 0,64 U Luecke`() {
        // 360-min-Fenster: vorher Profil (keine TBR), letzte 84 Slices Null.
        val s = slices(360, tbr = { if (it >= 276) 0.0 else null })
        val phase = BasalGapRechner.nullphase(s, basalStepUph = 0.05, stepMs = stepMs)!!
        assertEquals(84, phase.ageMin)
        assertEquals(0.46 * 84 / 60.0, phase.omittedU!!, 0.01)
    }

    @Test
    fun `eine positive Zwischenrate beendet die Kette`() {
        val s = slices(60, tbr = { if (it >= 50) 0.0 else if (it == 49) 1.2 else 0.0 })
        assertEquals(10, BasalGapRechner.nullphase(s, 0.05, stepMs)!!.ageMin)
    }

    @Test
    fun `fehlendes Profil in der Phase macht nur die Menge unbekannt`() {
        val s = slices(30, tbr = { if (it >= 10) 0.0 else null }, profil = { if (it == 15) null else 0.5 })
        val phase = BasalGapRechner.nullphase(s, 0.05, stepMs)!!
        assertEquals(20, phase.ageMin)
        assertNull(phase.omittedU, "lieber typisiert null als eine Schaetzung")
    }

    @Test
    fun `ohne laufende Null am Marker gibt es keine Phase`() {
        assertNull(BasalGapRechner.nullphase(slices(30, tbr = { null }), 0.05, stepMs))
        assertNull(BasalGapRechner.nullphase(slices(30, tbr = { 0.8 }), 0.05, stepMs))
        assertNull(BasalGapRechner.nullphase(emptyList(), 0.05, stepMs))
    }

    @Test
    fun `eine Restrate ueber der Toleranz ist keine Null`() {
        // TbrPolicy.isZeroRate: Toleranz min(step/2, 0.025) - 0,05 U/h > 0,025.
        assertNull(BasalGapRechner.nullphase(slices(30, tbr = { 0.05 }), 0.05, stepMs))
    }
}

package app.aaps.fuse.core.profile

import app.aaps.fuse.core.predictor.IsfSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** KC2-49 und die Luecken-Regel der aufgeloesten Slots. */
class ProfileSlotsTest {

    private val t0 = 1_700_000_000_000L
    private fun min(m: Int) = t0 + m * 60_000L

    @Test
    fun `gleiche Nachbarn werden zusammengefasst`() {
        val ts = LongArray(6) { min(it * 10) }
        val rate = doubleArrayOf(0.7, 0.7, 0.7, 1.1, 1.1, 0.7)
        val slots = ProfileSlots.compressBasal(ts, rate, min(60))
        assertEquals(3, slots.size)
        assertEquals(0.7, slots[0].rateUPerH)
        assertEquals(min(30), slots[1].startTsInclusive)
        assertEquals(min(60), slots.last().endTsExclusive)
    }

    @Test
    fun `KC2-49 endet ein Profile Switch im Release-Fenster, folgt das Integral dem dann gueltigen Profil`() {
        // Der Adapter loest je Stuetzstelle zuerst profileFunction.getProfile(ts)
        // auf: 0..10 min laeuft der Switch mit 1,40 U/h, danach greift wieder
        // das Basisprofil mit 0,70 U/h.
        val ts = LongArray(21) { min(it) }
        val rate = DoubleArray(21) { if (it < 10) 1.40 else 0.70 }
        val slots = ProfileSlots.compressBasal(ts, rate, min(21))

        val integral = ProfileSlots.basalIntegralU(slots, min(0), min(20))
        // 10 min * 1,40 U/h + 10 min * 0,70 U/h
        assertEquals(1.40 / 6.0 + 0.70 / 6.0, integral!!, 1e-12)

        // Der Fehler, den die Regel verhindert: mit dem MOMENTANWERT 1,40 U/h
        // hochgerechnet waere das Fenster fast ein Drittel zu gross.
        val naive = 1.40 * 20.0 / 60.0
        assertTrue(naive > integral * 1.3)
    }

    @Test
    fun `eine Luecke ergibt kein Teilintegral, sondern null`() {
        val slots = listOf(
            BasalSlot(min(0), min(10), 0.7),
            BasalSlot(min(15), min(30), 0.7),   // Luecke 10..15
        )
        assertNull(ProfileSlots.basalIntegralU(slots, min(0), min(20)))
        assertEquals(0.7 / 6.0, ProfileSlots.basalIntegralU(slots, min(0), min(10))!!, 1e-12)
        assertTrue(!ProfileSlots.basalCovers(slots, min(0), min(20)))
    }

    @Test
    fun `hinter dem letzten Slot gibt es kein Integral`() {
        val slots = listOf(BasalSlot(min(0), min(20), 0.7))
        assertNull(ProfileSlots.basalIntegralU(slots, min(0), min(25)))
        assertEquals(0.0, ProfileSlots.basalIntegralU(slots, min(5), min(5))!!)
    }

    @Test
    fun `Basal- und ISF-Abfrage sind halboffen`() {
        val basal = listOf(BasalSlot(min(0), min(10), 0.7))
        assertEquals(0.7, ProfileSlots.basalAt(basal, min(0)))
        assertEquals(0.7, ProfileSlots.basalAt(basal, min(9)))
        assertNull(ProfileSlots.basalAt(basal, min(10)))

        val isf = listOf(IsfSlot(min(0), min(10), 55.0))
        assertEquals(55.0, ProfileSlots.isfAt(isf, min(0)))
        assertNull(ProfileSlots.isfAt(isf, min(10)))
    }
}

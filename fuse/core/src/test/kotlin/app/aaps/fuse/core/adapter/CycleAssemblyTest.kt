package app.aaps.fuse.core.adapter

import app.aaps.fuse.core.observer.ActivityValidity
import app.aaps.fuse.core.predictor.PredictorReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CycleAssemblyTest {

    private val t0 = 1_700_000_000_000L

    // ---- Activity-LOCF ---------------------------------------------------

    @Test
    fun `Punkt aus der Zukunft ist kein gueltiges LOCF`() {
        assertEquals(
            ActivityValidity.FUTURE,
            CycleAssembly.activityValidity(t0 + 1000, t0, t0, t0 + 500),
        )
    }

    @Test
    fun `zu alter Punkt ist STALE, frischer ist VALID`() {
        assertEquals(ActivityValidity.STALE, CycleAssembly.activityValidity(t0 - 181_000, t0, t0, t0))
        assertEquals(ActivityValidity.VALID, CycleAssembly.activityValidity(t0 - 179_000, t0, t0, t0))
        // Genau an der Grenze: 180 s sind noch gueltig.
        assertEquals(ActivityValidity.VALID, CycleAssembly.activityValidity(t0 - 180_000, t0, t0, t0))
    }

    @Test
    fun `noch nicht verfuegbarer Wert gilt als fehlend`() {
        assertEquals(
            ActivityValidity.MISSING,
            CycleAssembly.activityValidity(t0 - 1000, t0, availableAt = t0 + 5000, computeTs = t0),
        )
        assertEquals(ActivityValidity.MISSING, CycleAssembly.activityValidity(null, t0, t0, t0))
    }

    // ---- IOB-Punkte ------------------------------------------------------

    @Test
    fun `negative Aktivitaet wird unveraendert durchgereicht`() {
        val p = CycleAssembly.iobPoints(
            longArrayOf(t0, t0 + 300_000),
            doubleArrayOf(1.0, 0.9),
            doubleArrayOf(-0.01, 0.02),   // Zero-TBR -> negativ, gueltig
            doubleArrayOf(0.1, 0.1),
        )
        assertEquals(-0.01, p[0].activity)
        assertEquals(0.02, p[1].activity)
    }

    @Test
    fun `unterschiedlich lange Spalten werden abgewiesen`() {
        assertThrows<IllegalArgumentException> {
            CycleAssembly.iobPoints(longArrayOf(t0), doubleArrayOf(1.0, 2.0), doubleArrayOf(0.0), doubleArrayOf(0.0))
        }
    }

    // ---- ISF-Slots -------------------------------------------------------

    @Test
    fun `gleiche Nachbarn werden zu einem Slot verdichtet`() {
        val ts = longArrayOf(t0, t0 + 60_000, t0 + 120_000, t0 + 180_000)
        val isf = doubleArrayOf(90.0, 90.0, 45.0, 45.0)
        val slots = CycleAssembly.compressIsfSlots(ts, isf, t0 + 240_000)
        assertEquals(2, slots.size)
        assertEquals(90.0, slots[0].isfMgdlPerU)
        assertEquals(t0 + 120_000, slots[0].endTsExclusive)
        assertEquals(45.0, slots[1].isfMgdlPerU)
        assertEquals(t0 + 240_000, slots[1].endTsExclusive)
    }

    @Test
    fun `ein Profilwechsel im Fenster erzeugt zwei Slots mit exakter Grenze`() {
        val ts = LongArray(120) { t0 + it * 60_000L }
        val isf = DoubleArray(120) { if (it < 30) 90.0 else 45.0 }
        val slots = CycleAssembly.compressIsfSlots(ts, isf, t0 + 120 * 60_000L)
        assertEquals(2, slots.size)
        assertEquals(t0 + 30 * 60_000L, slots[0].endTsExclusive)
        assertEquals(t0 + 30 * 60_000L, slots[1].startTsInclusive)
    }

    @Test
    fun `Luecke in der Slotfolge wird gemeldet statt gefuellt`() {
        val ok = CycleAssembly.compressIsfSlots(
            longArrayOf(t0, t0 + 60_000), doubleArrayOf(90.0, 90.0), t0 + 3_600_000,
        )
        assertNull(CycleAssembly.isfCoverageGap(ok, t0, t0 + 1_800_000))
        assertEquals(
            PredictorReason.MISSING_ISF_SLOT,
            CycleAssembly.isfCoverageGap(ok, t0, t0 + 7_200_000),
        )
        assertEquals(
            PredictorReason.MISSING_ISF_SLOT,
            CycleAssembly.isfCoverageGap(emptyList(), t0, t0 + 60_000),
        )
    }

    @Test
    fun `R81-F6 ISF-Stuetzstellen werden wie Basal-Stuetzstellen geprueft`() {
        // unsortiert
        assertTrue(
            runCatching {
                CycleAssembly.compressIsfSlots(longArrayOf(t0 + 60_000, t0), doubleArrayOf(90.0, 45.0), t0 + 120_000)
            }.exceptionOrNull() is IllegalArgumentException
        )
        // endExclusive nicht hinter der letzten Stuetzstelle
        assertTrue(
            runCatching {
                CycleAssembly.compressIsfSlots(longArrayOf(t0, t0 + 60_000), doubleArrayOf(90.0, 45.0), t0 + 60_000)
            }.exceptionOrNull() is IllegalArgumentException
        )
        // NaN ist kein Profilwert
        assertTrue(
            runCatching {
                CycleAssembly.compressIsfSlots(longArrayOf(t0, t0 + 60_000), doubleArrayOf(90.0, Double.NaN), t0 + 120_000)
            }.exceptionOrNull() is IllegalArgumentException
        )
        // Der WERTEBEREICH bleibt dagegen Sache der versionierten Policy im
        // Kern: ein zu grosser ISF ist dort eine Ablehnung, kein Wurf.
        val hugeButBuildable = CycleAssembly.compressIsfSlots(
            longArrayOf(t0, t0 + 60_000), doubleArrayOf(1500.0, 1500.0), t0 + 120_000
        )
        assertEquals(1, hugeButBuildable.size)
    }

    @Test
    fun `beginnt die Folge zu spaet, ist es eine Luecke`() {
        val late = CycleAssembly.compressIsfSlots(
            longArrayOf(t0 + 600_000), doubleArrayOf(90.0), t0 + 3_600_000,
        )
        assertEquals(
            PredictorReason.MISSING_ISF_SLOT,
            CycleAssembly.isfCoverageGap(late, t0, t0 + 1_800_000),
        )
    }
}

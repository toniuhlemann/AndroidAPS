package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Audit R95 NEU-03: r darf CGM-Luecken (dt > 3 min) nie ueberspannen. */
class SegmentStartTest {

    private val m = 60_000L
    private val t0 = 1_786_000_000_000L

    @Test
    fun `bruchfreies fenster bleibt beim fensterstart`() {
        val ts = (0..17).map { t0 + it * m }
        assertEquals(t0 + 2 * m, BgiAdjustedSeries.segmentStart(ts, t0 + 2 * m))
    }

    @Test
    fun `zehn-minuten-luecke schneidet auf das neue segment`() {
        // 8 Punkte, 10-min-Luecke, 2 neue Punkte - der NEU-03-Fall.
        val ts = (0..7).map { t0 + it * m } + listOf(t0 + 17 * m, t0 + 18 * m)
        assertEquals(t0 + 17 * m, BgiAdjustedSeries.segmentStart(ts, t0))
    }

    @Test
    fun `drei-minuten-abstand ist noch KEIN bruch - dt darueber schon`() {
        val ok = listOf(t0, t0 + 3 * m, t0 + 4 * m)
        assertEquals(t0, BgiAdjustedSeries.segmentStart(ok, t0))
        val broken = listOf(t0, t0 + 3 * m + 1, t0 + 4 * m)
        assertEquals(t0 + 3 * m + 1, BgiAdjustedSeries.segmentStart(broken, t0))
    }

    @Test
    fun `bruch VOR dem fenster ist egal`() {
        // Luecke liegt komplett vor windowStart - im Fenster ist alles dicht.
        val ts = listOf(t0 - 30 * m, t0 - 10 * m) + (0..10).map { t0 + it * m }
        assertEquals(t0, BgiAdjustedSeries.segmentStart(ts, t0))
    }

    @Test
    fun `der juengste bruch gewinnt bei mehreren`() {
        val ts = listOf(t0, t0 + 10 * m, t0 + 11 * m, t0 + 20 * m, t0 + 21 * m)
        assertEquals(t0 + 20 * m, BgiAdjustedSeries.segmentStart(ts, t0))
    }
}

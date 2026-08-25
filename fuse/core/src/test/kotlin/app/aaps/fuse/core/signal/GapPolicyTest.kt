package app.aaps.fuse.core.signal

import app.aaps.fuse.core.observer.ObserverParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE EINE WAHRHEIT ueber die Segmentgrenze (Bauauftrag Toni 25.08.).
 *
 * Der Anlass war ein Beinahe-Messfehler: die Grenze stand an drei Orten,
 * davon einer als unabhaengiges Literal im Observer. Ein Replay haette
 * die r-Reihe verbunden, waehrend der Observer weiter bricht - und die
 * Matrix haette "bringt nichts" gemeldet, ohne dass das etwas ueber die
 * Grenze ausgesagt haette.
 */
class GapPolicyTest {

    @AfterEach
    fun aufraeumen() {
        GapPolicy.overrideForReplay(null)
    }

    @Test
    fun `der produktionswert ist drei minuten und beide verbraucher lesen ihn`() {
        assertEquals(180_000L, GapPolicy.DEFAULT_R_SEGMENT_BREAK_MS)
        assertEquals(180_000L, GapPolicy.rSegmentBreakMs)
        assertEquals(3.0, GapPolicy.rSegmentBreakMin, 1e-9)
        // Verbraucher 1: die r-Reihe.
        assertEquals(180_000L, BgiAdjustedSeries.SEGMENT_BREAK_MS)
        // Verbraucher 2: der Observer.
        assertEquals(3.0, ObserverParams().rSegmentBreakMin, 1e-9)
    }

    /**
     * DER KERNBEWEIS: EIN Override bewegt BEIDE Verbraucher. Genau das
     * konnte die alte Fassung nicht - dort waere nur die Reihe gefolgt.
     */
    @Test
    fun `ein override bewegt beide verbraucher gemeinsam`() {
        assertEquals(195_000L, GapPolicy.overrideForReplay(195_000L))
        assertEquals(195_000L, BgiAdjustedSeries.SEGMENT_BREAK_MS, )
        assertEquals(3.25, GapPolicy.rSegmentBreakMin, 1e-9)
        assertEquals(3.25, ObserverParams().rSegmentBreakMin, 1e-9)

        // Und die Rueckkehr ist vollstaendig.
        assertEquals(180_000L, GapPolicy.overrideForReplay(null))
        assertEquals(180_000L, BgiAdjustedSeries.SEGMENT_BREAK_MS)
        assertEquals(3.0, ObserverParams().rSegmentBreakMin, 1e-9)
    }

    /**
     * DIE SEGMENTGRENZE WIRKT: dieselbe Punktfolge mit einer 3,04-min-
     * Luecke ergibt unter 180 s einen Schnitt und unter 195 s keinen.
     * Das ist die Frage, um die es in der Matrix geht - hier an der
     * Funktion selbst, ohne Rig.
     */
    @Test
    fun `eine 3-04-minuten-luecke schneidet unter 180s und nicht unter 195s`() {
        val t0 = 1_700_000_000_000L
        // Vier Punkte im Minutentakt, dann 3,04 min Luecke, dann drei.
        val ts = listOf(
            t0, t0 + 60_000L, t0 + 120_000L, t0 + 180_000L,
            t0 + 180_000L + 182_400L,              // +3,04 min
            t0 + 180_000L + 182_400L + 60_000L,
            t0 + 180_000L + 182_400L + 120_000L,
        )
        val fensterStart = t0
        // Produktion: der Schnitt liegt AUF dem ersten Punkt nach der Luecke.
        assertEquals(
            ts[4], BgiAdjustedSeries.segmentStart(ts, fensterStart),
            "180 s: die Luecke bricht das Segment",
        )
        // 195 s: kein Bruch mehr - das Fenster bleibt ganz.
        GapPolicy.overrideForReplay(195_000L)
        assertEquals(
            fensterStart, BgiAdjustedSeries.segmentStart(ts, fensterStart),
            "195 s: dieselbe Luecke ist kein Bruch mehr",
        )
        // Eine WIRKLICH grosse Luecke bricht auch unter 195 s weiter.
        val gross = listOf(t0, t0 + 60_000L, t0 + 60_000L + 600_000L, t0 + 60_000L + 660_000L)
        assertEquals(
            gross[2], BgiAdjustedSeries.segmentStart(gross, t0),
            "10 min bleiben ein Bruch - die Grenze verschiebt sich, sie verschwindet nicht",
        )
    }

    @Test
    fun `unbrauchbare overrides fallen auf die produktion zurueck`() {
        assertEquals(180_000L, GapPolicy.overrideForReplay(GapPolicy.MAX_OVERRIDE_MS + 1))
        assertEquals(180_000L, GapPolicy.overrideForReplay(GapPolicy.MIN_OVERRIDE_MS - 1))
        assertEquals(180_000L, GapPolicy.overrideForReplay(0L))
        assertEquals(180_000L, GapPolicy.overrideForReplay(-5L))
        // Die Raender selbst sind gueltig.
        assertEquals(GapPolicy.MAX_OVERRIDE_MS, GapPolicy.overrideForReplay(GapPolicy.MAX_OVERRIDE_MS))
        assertEquals(GapPolicy.MIN_OVERRIDE_MS, GapPolicy.overrideForReplay(GapPolicy.MIN_OVERRIDE_MS))
    }

    /**
     * DER REIFESTAND kommt aus DERSELBEN Schleife wie der Schaetzer -
     * er kann also nie Reife behaupten, wo der Schaetzer abbricht.
     */
    @Test
    fun `der reifestand nennt beide schranken getrennt`() {
        val t0 = 1_700_000_000_000L
        fun punkte(n: Int) = (0 until n).map {
            BgiAdjustedSeries.AdjustedPoint(t0 + it * 60_000L, 100.0 + it)
        }
        val jetzt = t0 + 20 * 60_000L

        // Vier Punkte: die PUNKT-Schranke bindet.
        val wenig = BgiAdjustedSeries.readiness(punkte(4), punkte(4).last().sourceTs)
        assertEquals(SignalReadiness.Reason.TOO_FEW_POINTS, wenig.reason)
        assertEquals(4, wenig.points)
        assertEquals(5, wenig.pointsRequired)
        assertFalse(wenig.ready)
        assertTrue(wenig.shortText()!!.contains("4/5P"))

        // Fuenf Punkte im Minutentakt: Punkte reichen, PAARE nicht
        // (nur 6 Paare mit dt >= 2 min, noetig sind 8). Genau der Fall,
        // den eine einzelne Zahl "x/6" verschwiegen haette.
        val f = punkte(5)
        val paare = BgiAdjustedSeries.readiness(f, f.last().sourceTs)
        assertEquals(SignalReadiness.Reason.TOO_FEW_SLOPES, paare.reason)
        assertEquals(5, paare.points)
        assertEquals(6, paare.slopes)
        assertEquals(8, paare.slopesRequired)
        assertTrue(paare.shortText()!!.contains("6/8S"))

        // Sechs Punkte: 10 Paare - reif, und die Zeile verschwindet.
        val sechs = punkte(6)
        val reif = BgiAdjustedSeries.readiness(sechs, sechs.last().sourceTs)
        assertEquals(SignalReadiness.Reason.READY, reif.reason)
        assertTrue(reif.ready)
        assertEquals(null, reif.shortText(), "reif heisst: keine Zeile")
        // Gegenprobe zur Konsistenz: genau ab hier liefert auch der
        // Schaetzer selbst.
        assertEquals(null, BgiAdjustedSeries.theilSen(punkte(5), f.last().sourceTs))
        assertTrue(BgiAdjustedSeries.theilSen(sechs, sechs.last().sourceTs) != null)
        assertTrue(jetzt > t0)
    }

    @Test
    fun `eine frische luecke wird als segmentbruch benannt`() {
        val t0 = 1_700_000_000_000L
        val zwei = listOf(
            BgiAdjustedSeries.AdjustedPoint(t0, 100.0),
            BgiAdjustedSeries.AdjustedPoint(t0 + 60_000L, 101.0),
        )
        val r = BgiAdjustedSeries.readiness(zwei, t0 + 60_000L, lastGapMs = 188_000L)
        assertEquals(SignalReadiness.Reason.GAP_RESET, r.reason)
        assertEquals(188_000L, r.lastGapMs)
        assertEquals(180_000L, r.breakMs)
        assertEquals("Eingang fehlt - Luecke 188s > 180s", r.shortText())
        // Unter 195 s waere dieselbe Luecke kein Bruch - dann baut das
        // Signal nur auf, statt neu zu beginnen.
        GapPolicy.overrideForReplay(195_000L)
        val r2 = BgiAdjustedSeries.readiness(zwei, t0 + 60_000L, lastGapMs = 188_000L)
        assertEquals(SignalReadiness.Reason.TOO_FEW_POINTS, r2.reason)
    }
}

package app.aaps.fuse.core.signal

import app.aaps.fuse.core.observer.ActivityValidity
import app.aaps.fuse.core.observer.ObserverInput
import app.aaps.fuse.core.observer.ObserverParams
import app.aaps.fuse.core.observer.ObserverStateMachine
import app.aaps.fuse.core.observer.ResetCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE EINE WAHRHEIT ueber die Segmentgrenze (Bauauftrag Toni 25.08.),
 * jetzt UNVERAENDERLICH und je Runner injiziert.
 *
 * Der erste Anlass war ein Beinahe-Messfehler: die Grenze stand an drei
 * Orten, davon einer als unabhaengiges Literal im Observer. Ein Replay
 * haette die r-Reihe verbunden, waehrend der Observer weiter bricht - und
 * die Matrix haette "bringt nichts" gemeldet, ohne dass das etwas ueber
 * die Grenze ausgesagt haette.
 *
 * Der zweite Anlass war die erste Reparatur selbst: sie trug einen
 * prozessweiten, veraenderlichen Override. Zwei Matrixlaeufe im selben
 * Prozess haetten sich denselben Wert geteilt. Die Pflichtprobe dazu
 * steht unten: `zwei observer mit verschiedenen grenzen stoeren sich
 * nicht`.
 */
class GapPolicyTest {

    @Test
    fun `der produktionswert ist drei minuten und beide verbraucher lesen ihn`() {
        assertEquals(180_000L, GapPolicy.DEFAULT_R_SEGMENT_BREAK_MS)
        assertEquals(180_000L, GapPolicy.PRODUCTION.rSegmentBreakMs)
        assertEquals(3.0, GapPolicy.PRODUCTION.rSegmentBreakMin, 1e-9)
        // Verbraucher 1: die r-Reihe (Vorgabewert).
        assertEquals(180_000L, BgiAdjustedSeries.SEGMENT_BREAK_MS)
        // Verbraucher 2: der Observer - er traegt KEIN eigenes Literal
        // mehr, sondern erbt aus derselben Quelle.
        assertEquals(3.0, ObserverParams().rSegmentBreakMin, 1e-9)
    }

    /**
     * DIE PFLICHTPROBE (Review Toni 25.08. abends): zwei Observer mit
     * verschiedenen Grenzen laufen ABWECHSELND im selben Prozess und
     * beeinflussen sich nicht. Genau das konnte der prozessweite Schalter
     * nicht - dort haette der zuletzt gesetzte Wert beide bestimmt, und
     * die Reihenfolge der Laeufe waere bedeutungstragend geworden.
     */
    @Test
    fun `zwei observer mit verschiedenen grenzen stoeren sich nicht`() {
        val a = ObserverStateMachine(
            p = ObserverParams(rSegmentBreakMin = GapPolicy.PRODUCTION.rSegmentBreakMin),
            sessionId = "a180",
        )
        val b = ObserverStateMachine(
            p = ObserverParams(rSegmentBreakMin = GapPolicy.of(195_000L).rSegmentBreakMin),
            sessionId = "b195",
        )
        val t0 = 1_700_000_000_000L
        fun eingabe(ts: Long) = ObserverInput(
            sourceTs = ts, computeTs = ts + 500L, signalInputBg = 120.0, q1 = 120.0,
            rSigned = 0.0, sensorEpoch = 0L, calibrationEpoch = 0L,
            activity = ActivityValidity.VALID, profileIsfValid = true, inputGap = false,
        )
        // Zwei Minutenschritte, dann DIESELBE 3,04-min-Luecke - jeweils
        // abwechselnd in beide Maschinen, damit eine Beeinflussung
        // ueberhaupt auftreten koennte.
        val folge = listOf(t0, t0 + 60_000L, t0 + 60_000L + 182_400L)
        var letzteA: Set<ResetCause> = emptySet()
        var letzteB: Set<ResetCause> = emptySet()
        for (ts in folge) {
            letzteA = a.step(eingabe(ts)).resetCauses
            letzteB = b.step(eingabe(ts)).resetCauses
        }
        assertTrue(
            ResetCause.R_SEGMENT_BREAK in letzteA,
            "der 180er MUSS bei 3,04 min brechen - sonst hat ihn der 195er angesteckt",
        )
        assertFalse(
            ResetCause.R_SEGMENT_BREAK in letzteB,
            "der 195er darf bei 3,04 min NICHT brechen - sonst hat ihn der 180er angesteckt",
        )
        // Und die umgekehrte Reihenfolge im selben Prozess aendert nichts:
        // waere irgendwo noch ein geteilter Zustand, kippte es hier.
        val c = ObserverStateMachine(
            p = ObserverParams(rSegmentBreakMin = GapPolicy.of(195_000L).rSegmentBreakMin),
            sessionId = "c195",
        )
        val d = ObserverStateMachine(
            p = ObserverParams(rSegmentBreakMin = GapPolicy.PRODUCTION.rSegmentBreakMin),
            sessionId = "d180",
        )
        var letzteC: Set<ResetCause> = emptySet()
        var letzteD: Set<ResetCause> = emptySet()
        for (ts in folge) {
            letzteC = c.step(eingabe(ts)).resetCauses
            letzteD = d.step(eingabe(ts)).resetCauses
        }
        assertFalse(ResetCause.R_SEGMENT_BREAK in letzteC, "195er bleibt 195er, egal wer zuerst laeuft")
        assertTrue(ResetCause.R_SEGMENT_BREAK in letzteD, "180er bleibt 180er, egal wer zuerst laeuft")
    }

    /**
     * DIE SEGMENTGRENZE WIRKT: dieselbe Punktfolge mit einer 3,04-min-
     * Luecke ergibt unter 180 s einen Schnitt und unter 195 s keinen.
     * Das ist die Frage, um die es in der Matrix geht - hier an der
     * Funktion selbst, ohne Rig. Die Politik kommt als ARGUMENT, nicht
     * aus einem Prozesszustand.
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
        assertEquals(
            fensterStart, BgiAdjustedSeries.segmentStart(ts, fensterStart, GapPolicy.of(195_000L)),
            "195 s: dieselbe Luecke ist kein Bruch mehr",
        )
        // Der Aufruf mit 195 hat die Produktion NICHT veraendert.
        assertEquals(
            ts[4], BgiAdjustedSeries.segmentStart(ts, fensterStart, GapPolicy.PRODUCTION),
            "die Produktionspolitik ist nach dem 195er-Aufruf unveraendert",
        )
        // Eine WIRKLICH grosse Luecke bricht auch unter 195 s weiter.
        val gross = listOf(t0, t0 + 60_000L, t0 + 60_000L + 600_000L, t0 + 60_000L + 660_000L)
        assertEquals(
            gross[2], BgiAdjustedSeries.segmentStart(gross, t0, GapPolicy.of(195_000L)),
            "10 min bleiben ein Bruch - die Grenze verschiebt sich, sie verschwindet nicht",
        )
    }

    @Test
    fun `unbrauchbare werte ergeben die produktion`() {
        assertEquals(180_000L, GapPolicy.of(GapPolicy.MAX_MS + 1).rSegmentBreakMs)
        assertEquals(180_000L, GapPolicy.of(GapPolicy.MIN_MS - 1).rSegmentBreakMs)
        assertEquals(180_000L, GapPolicy.of(0L).rSegmentBreakMs)
        assertEquals(180_000L, GapPolicy.of(-5L).rSegmentBreakMs)
        // Die Raender selbst sind gueltig.
        assertEquals(GapPolicy.MAX_MS, GapPolicy.of(GapPolicy.MAX_MS).rSegmentBreakMs)
        assertEquals(GapPolicy.MIN_MS, GapPolicy.of(GapPolicy.MIN_MS).rSegmentBreakMs)
        // Und ein Wert ist gleich einem gleichen Wert - Wertsemantik,
        // damit ein Vergleich im Trail nicht auf Identitaet hereinfaellt.
        assertEquals(GapPolicy.of(195_000L), GapPolicy.of(195_000L))
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
        // Signal nur auf, statt neu zu beginnen. Und die Zeile nennt
        // dann auch die 195, nicht die Produktionsgrenze.
        val r2 = BgiAdjustedSeries.readiness(
            zwei, t0 + 60_000L, lastGapMs = 188_000L, policy = GapPolicy.of(195_000L),
        )
        assertEquals(SignalReadiness.Reason.TOO_FEW_POINTS, r2.reason)
        assertEquals(195_000L, r2.breakMs)
    }
}

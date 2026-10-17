package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** KANDIDAT W (Pruefstand): die Wendeerkennung - reine Rechnung auf synthetischen Steigungsverlaeufen. */
class TurnDetectorTest {

    private val p = TurnDetector.Params()
    private val min = 60_000L

    /** Ein Lauf ueber Minuten-Steigungen [ukf] mit r je Minute; liefert das Ergebnis jedes Zyklus. */
    private fun lauf(
        ukf: List<Double>, r: (Int) -> Double, upAllowed: (Int) -> Boolean = { true }, stopAllowed: (Int) -> Boolean = { true },
    ): List<TurnDetector.Result> {
        val verlauf = mutableListOf<TurnDetector.Point>()
        var s = TurnDetector.State()
        return ukf.indices.map { i ->
            verlauf += TurnDetector.Point(i * min, ukf[i], r(i))
            TurnDetector.step(s, verlauf, i * min, upAllowed(i), stopAllowed(i), p).also { s = it.state }
        }
    }

    /** Plateau, dann eine zweite Welle: 15 min flach, danach steigt die Steigung je Minute um 0,25. */
    private val zweiteWelle = List(15) { 0.0 } + List(12) { (it + 1) * 0.25 }

    @Test
    fun `nach einem Plateau erkennt er die Wende nach N Zyklen ueber der Aufwaertsgrenze - klein beginnend`() {
        val e = lauf(zweiteWelle, r = { i -> if (i < 15) 0.0 else 0.3 + (i - 15) * 0.2 })
        val i = e.indexOfFirst { it.event == "TURN_UP" }
        // Steigung 0,5 bei Minute 16 und 0,75 bei 17: zwei Zyklen ueber der Aufwaertsgrenze ab 17 (0,25 bei 15 liegt nicht
        // darueber); der letzte flache Punkt (Minute 14) liegt 3 min zurueck, davor 10 min flach; r dort >= 0,5 und steigend.
        assertEquals(17, i)
        assertTrue(e[i].active)
        assertEquals(0.75, e[i].state.ukfAtTurnMgdlPerMin, 1e-9)
        // An der Wende die kleine Gabe, mit dem Zuwachs der Steigung seit der Wende mehr, ab +1,0 die volle.
        assertEquals(p.f0, e[i].rampFactor, 1e-9)
        assertEquals(p.f0 + (1.0 - p.f0) * 0.25 / p.duVoll, e[i + 1].rampFactor, 1e-9)
        assertEquals(1.0, e[i + 4].rampFactor, 1e-9)
        assertTrue((0 until i).none { e[it].active })
    }

    @Test
    fun `ein gleichmaessig starker Anstieg nach der Wende bekommt die volle Gabe, eine Abflachung nimmt sie zurueck`() {
        val ukf = zweiteWelle.take(19) + List(6) { 2.2 } + List(4) { 1.4 }
        val e = lauf(ukf, r = { i -> if (i < 15) 0.0 else 1.5 })
        val i = e.indexOfFirst { it.event == "TURN_UP" }
        assertEquals(17, i)
        assertTrue((19..24).all { e[it].active && e[it].rampFactor == 1.0 })
        val nachher = e.drop(25).filter { it.active }
        assertTrue(nachher.all { it.rampFactor < 1.0 }, "Abflachung: " + nachher.map { it.rampFactor })
    }

    @Test
    fun `wird r negativ, endet die Phase`() {
        val e = lauf(zweiteWelle + List(5) { 3.0 }, r = { i -> if (i < 15) 0.0 else if (i <= 20) 1.5 else -0.1 })
        assertEquals(17, e.indexOfFirst { it.event == "TURN_UP" })
        assertEquals(21, e.indexOfFirst { it.event == "END_R_NEGATIVE" })
    }

    @Test
    fun `der Stopp endet, sobald der Zucker nach der Delle wieder steigt - er haelt nicht in die naechste Welle`() {
        // Anstieg, Abbremsen (Stopp), Fallen, dann eine langsame zweite Welle (0,3 bis 0,45) ohne flache Vorlage von 10 min.
        val ukf = List(8) { 2.0 } + listOf(1.4, 0.8, 0.3, 0.0, -0.5, -1.0, -1.0, -0.5, 0.0, 0.3, 0.35, 0.4, 0.45, 0.45)
        val e = lauf(ukf, r = { 1.5 }, upAllowed = { false })
        val unten = e.indexOfFirst { it.event == "TURN_DOWN" }
        val frei = e.indexOfFirst { it.event == "STOP_LIFTED" }
        assertTrue(unten in 0 until frei, "unten=$unten frei=$frei")
        // Zwei Zyklen ueber der Flachgrenze mit wachsender Steigung (Minute 17 und 18) heben ihn auf.
        assertEquals(18, frei)
        assertTrue(e.drop(frei).none { it.stop })
        // Waehrend des Fallens bleibt er.
        assertTrue((unten until frei).all { e[it].stop })
    }

    @Test
    fun `ohne flache Vorlage gibt es keine Wende - der Anstieg der ersten Welle laeuft schon`() {
        val e = lauf(List(30) { 1.0 + it * 0.05 }, r = { 1.0 + it * 0.05 })
        assertTrue(e.none { it.event == "TURN_UP" })
    }

    @Test
    fun `ohne positives und steigendes r keine Wende - nachlassendes Insulin ist keine Mahlzeit`() {
        assertTrue(lauf(zweiteWelle, r = { -0.2 }).none { it.event == "TURN_UP" })
        assertTrue(lauf(zweiteWelle, r = { 0.4 }).none { it.event == "TURN_UP" })
        assertTrue(lauf(zweiteWelle, r = { 0.8 }).none { it.event == "TURN_UP" })
    }

    @Test
    fun `ohne Erlaubnis keine Wende, und die laufende Phase endet mit Sperre`() {
        assertTrue(lauf(zweiteWelle, r = { 0.3 + it * 0.1 }, upAllowed = { false }).none { it.active })
        val e = lauf(zweiteWelle + List(5) { 3.0 }, r = { 0.3 + it * 0.1 }, upAllowed = { it < 20 })
        val ende = e.indexOfFirst { it.event == "END_NOT_ALLOWED" }
        assertEquals(20, ende)
        assertFalse(e[ende].active)
        assertEquals(20 * min + p.sperreMin * min, e[ende].state.lockUntilTs)
    }

    @Test
    fun `flacht der Anstieg ab, endet die Phase, stoppt die weiteren Portionen und sperrt eine neue Wende`() {
        // Wende, Anstieg bis 2,0, dann Abbremsen auf 0.
        val ukf = zweiteWelle.take(23) + listOf(2.0, 2.0, 1.6, 1.2, 0.8, 0.4, 0.1, 0.0, 0.0, 0.0)
        val e = lauf(ukf, r = { i -> if (i < 15) 0.0 else 1.5 })
        val wende = e.indexOfFirst { it.event == "TURN_UP" }
        val unten = e.indexOfFirst { it.event == "TURN_DOWN" }
        assertTrue(wende in 0 until unten)
        // Zwei Zyklen deutlich abgebremst (Zuwachs ueber 5 min <= -0,5): die Wende nach unten VOR dem Gipfel.
        assertTrue(ukf[unten] > 0.0, "Stopp erst bei Steigung ${ukf[unten]}")
        assertFalse(e[unten].active)
        assertTrue(e[unten].stop)
        assertEquals("TURN_DOWN", e[unten].state.endReason)
        assertTrue(e.drop(unten).all { it.stop && !it.active })
        assertEquals(unten * min + p.sperreMin * min, e[unten].state.lockUntilTs)
    }

    @Test
    fun `bremst ein grosser Anstieg nur kurz ab, faellt der Stopp wieder weg`() {
        val ukf = List(10) { 2.0 } + listOf(1.7, 1.4, 1.2, 1.2) + List(12) { 1.3 }
        val e = lauf(ukf, r = { 1.5 })
        val unten = e.indexOfFirst { it.event == "TURN_DOWN" }
        val frei = e.indexOfFirst { it.event == "STOP_LIFTED" }
        assertTrue(unten in 0 until frei, "unten=$unten frei=$frei")
        assertTrue(e[unten].stop)
        assertFalse(e[frei].stop)
        assertTrue(e.drop(frei).none { it.stop })
    }

    @Test
    fun `der Stopp gilt nur, wo er erlaubt ist, und nie ohne vorherigen Anstieg`() {
        val ukf = List(10) { 2.0 } + List(10) { 0.0 }
        assertTrue(lauf(ukf, r = { 1.0 }, stopAllowed = { false }).none { it.stop })
        assertTrue(lauf(List(20) { 0.1 }, r = { 0.0 }).none { it.stop })
    }

    @Test
    fun `die Phase endet nach der Hoechstdauer`() {
        val e = lauf(zweiteWelle + List(60) { 3.0 }, r = { i -> if (i < 15) 0.0 else 2.0 })
        val wende = e.indexOfFirst { it.event == "TURN_UP" }
        val ende = e.indexOfFirst { it.event == "END_MAX_DURATION" }
        assertEquals(wende + p.maxMin, ende)
    }

    @Test
    fun `ohne Signal endet die Phase ohne Sperre, ein geltender Stopp bleibt`() {
        val s = TurnDetector.State(sinceTs = 5 * min, lockUntilTs = 0L, stop = false)
        val r = TurnDetector.step(s, listOf(TurnDetector.Point(9 * min, Double.NaN, 1.0)), 9 * min, upAllowed = true, stopAllowed = true)
        assertFalse(r.active)
        assertEquals("END_NO_SIGNAL", r.event)
        assertEquals(0L, r.state.lockUntilTs)
        val gestoppt = TurnDetector.step(TurnDetector.State(stop = true), emptyList(), 9 * min, upAllowed = true, stopAllowed = true)
        assertTrue(gestoppt.stop)
        assertNull(gestoppt.event)
    }
}

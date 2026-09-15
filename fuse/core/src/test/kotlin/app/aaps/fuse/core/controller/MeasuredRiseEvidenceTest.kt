package app.aaps.fuse.core.controller

import app.aaps.fuse.core.signal.GlucosePoint
import app.aaps.fuse.core.signal.MeasuredGlucose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Die Messbestaetigung als reine Rechnung - synthetische Reihen, 1-min-Takt. */
class MeasuredRiseEvidenceTest {

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private val auth = t0 + 5 * min
    private val epoch = t0

    private fun reihe(werte: List<Double>, epochTs: Long = epoch, startMin: Int = 0) = MeasuredGlucose(
        points = werte.mapIndexed { k, v -> GlucosePoint(t0 + (startMin + k) * min, v, v) },
        segmentStartTs = epochTs, signalEpochTs = epochTs,
    )

    private fun input(m: MeasuredGlucose, need: Boolean = true, meal: Boolean = true, a: Long = auth) =
        MeasuredRiseEvidence.Input(enabled = true, mealAuthorized = meal, authorizationId = a, measured = m, needMet = need)

    /** Minute der Autorisierung - der Loop rechnet ab hier mit voller Vorgeschichte im Fenster. */
    private val ab = 5

    /** Laeuft Minute fuer Minute ab [ab] ueber die Reihe; Index = Minute - [ab]. */
    private fun lauf(werte: List<Double>, need: (Int) -> Boolean = { true }, bis: Int = werte.size): List<MeasuredRiseEvidence.Result> {
        var s = MeasuredRiseEvidence.State.EMPTY
        return (ab until bis).map { k ->
            val r = MeasuredRiseEvidence.step(s, input(reihe(werte.take(k + 1)), need(k)))
            s = r.state
            r
        }
    }

    private fun anstieg(n: Int, basis: Double = 150.0, rate: Double = 2.0) = (0 until n).map { basis + rate * it }

    @Test
    fun `anhaltender Anstieg bestaetigt nach zwei frischen Bloecken`() {
        val r = lauf(anstieg(20))
        // Bezug: Werte min 3-5 (bis zur Autorisierung). Bloecke: 6-8, 9-11 -> bestaetigt bei min 11.
        val erste = r.indexOfFirst { it.confirmed }
        assertEquals(11, erste + ab)
        assertTrue(r.drop(erste).all { it.confirmed })
    }

    @Test
    fun `ein weiterer Lauf ueber dieselben Daten bringt nichts`() {
        val werte = anstieg(10)
        var s = MeasuredRiseEvidence.State.EMPTY
        val m = reihe(werte)
        val a = MeasuredRiseEvidence.step(s, input(m)); s = a.state
        val b = MeasuredRiseEvidence.step(s, input(m))
        assertEquals(a.state, b.state)
        assertEquals(0, b.newBlocks)
    }

    @Test
    fun `ein einzelner Ausreisser im Block veraendert den Median nicht`() {
        val werte = MutableList(20) { 150.0 }.also { it[7] = 190.0 }
        assertTrue(lauf(werte).none { it.confirmed })
    }

    /** Je Block GENAU ein abweichender Wert, von Block zu Block hoeher: der Median
     *  bleibt flach und bestaetigt nie - ein Maximum wuerde zwei neue Hochs sehen. */
    @Test
    fun `je Block ein einzelner abweichender Wert bestaetigt nicht`() {
        val werte = MutableList(20) { 150.0 }.also { it[7] = 160.0; it[10] = 170.0; it[13] = 180.0; it[16] = 190.0 }
        val r = lauf(werte)
        assertTrue(r.none { it.confirmed }, "${r.map { it.state.confirmations }}")
        assertTrue(r.maxOf { it.state.confirmations } == 0)
    }

    @Test
    fun `ein gehaltener Sprung bestaetigt hoechstens einen Block`() {
        val werte = (0 until 24).map { if (it >= 7) 158.0 else 150.0 }
        val r = lauf(werte)
        assertTrue(r.none { it.confirmed })
        assertTrue(r.maxOf { it.state.confirmations } <= 1)
    }

    @Test
    fun `zwei erhoehte Werte mit Rueckkehr bestaetigen nicht`() {
        val werte = (0 until 24).map { if (it == 7 || it == 8) 158.0 else 150.0 + 0.0 }
        assertTrue(lauf(werte).none { it.confirmed })
    }

    @Test
    fun `ohne Bedarf im Abschlusszyklus kein Fortschritt - Bedarf zaehlt je Block einmal`() {
        // Bedarf nur zwischen den Blockabschluessen erfuellt: nie bestaetigt.
        val r = lauf(anstieg(24), need = { k -> (k - 5) % 3 != 0 })
        assertTrue(r.none { it.confirmed })
        assertTrue(r.any { it.denial == MeasuredRiseEvidence.Denial.NEED_NOT_MET })
    }

    @Test
    fun `Epochenwechsel beginnt neu - ohne Bezug aus der alten Epoche`() {
        val werte = anstieg(30)
        var s = MeasuredRiseEvidence.State.EMPTY
        for (k in 0 until 15) s = MeasuredRiseEvidence.step(s, input(reihe(werte.take(k + 1)))).state
        assertTrue(s.confirmations >= 2)
        // Neue Epoche ab min 15 (Luecke/Sensor/Kalibrierung): nur Punkte ab dort.
        val neueEpoche = t0 + 15 * min
        val r = MeasuredRiseEvidence.step(s, input(reihe(werte.subList(15, 16), neueEpoche, startMin = 15)))
        assertTrue(r.restarted)
        assertFalse(r.confirmed)
        assertEquals(0, r.state.confirmations)
        assertTrue(r.state.blocks.isEmpty(), "kein Bezug aus der alten Epoche")
    }

    @Test
    fun `Verlust und Wechsel der MEAL-Autorisierung beginnen neu`() {
        val werte = anstieg(20)
        var s = MeasuredRiseEvidence.State.EMPTY
        for (k in 0 until 15) s = MeasuredRiseEvidence.step(s, input(reihe(werte.take(k + 1)))).state
        assertTrue(s.confirmations >= 2)
        val verlust = MeasuredRiseEvidence.step(s, input(reihe(werte.take(16)), meal = false))
        assertEquals(MeasuredRiseEvidence.Denial.NOT_MEAL_AUTHORIZED, verlust.denial)
        assertEquals(MeasuredRiseEvidence.State.EMPTY, verlust.state)
        val wechsel = MeasuredRiseEvidence.step(s, input(reihe(werte.take(16)), a = t0 + 15 * min))
        assertTrue(wechsel.restarted)
        assertFalse(wechsel.confirmed)
        assertEquals(0, wechsel.state.confirmations)
    }

    @Test
    fun `mehrere Bloecke in einem Zyklus - nur der juengste kann bestaetigen`() {
        val werte = anstieg(20)
        var s = MeasuredRiseEvidence.State.EMPTY
        s = MeasuredRiseEvidence.step(s, input(reihe(werte.take(6)))).state
        // Neun neue Werte auf einmal: drei Bloecke, die aelteren zaehlen nicht.
        val r = MeasuredRiseEvidence.step(s, input(reihe(werte.take(15))))
        assertEquals(3, r.newBlocks)
        assertEquals(1, r.state.confirmations)
        assertFalse(r.confirmed)
    }

    @Test
    fun `Schalter aus haelt keinen Zustand`() {
        val r = MeasuredRiseEvidence.step(
            MeasuredRiseEvidence.State(epoch, auth, t0, emptyList(), 5),
            MeasuredRiseEvidence.Input(false, true, auth, reihe(anstieg(10)), true),
        )
        assertEquals(MeasuredRiseEvidence.State.EMPTY, r.state)
        assertEquals(MeasuredRiseEvidence.Denial.DISABLED, r.denial)
        assertNull(r.state.blocks.firstOrNull())
    }

    @Test
    fun `nicht unterscheidbar - eine langsame Artefaktrampe bestaetigt wie ein echter Anstieg`() {
        // Dokumentierte Grenze: die Kurvenform allein trennt das nicht.
        assertTrue(lauf(anstieg(20, rate = 1.0)).any { it.confirmed })
    }
}

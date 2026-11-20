package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.NoInputWatch.Result
import app.aaps.fuse.core.controller.NoInputWatch.Sample
import app.aaps.fuse.core.controller.NoInputWatch.Stage
import app.aaps.fuse.core.controller.NoInputWatch.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Toni 20.11.2026: der Eingangs-Waechter sagt, wenn FUSE bei hohem oder steigendem Zucker nicht rechnet. Ausgedachte Werte. */
class NoInputWatchTest {

    private val minute = 60_000L
    private val start = 1_000_000_000_000L

    private fun sample(m: Int, eingang: Boolean, bg: Double = 250.0, steigung: Double = 0.0) = Sample(start + m * minute, eingang, bg, steigung)

    private fun lauf(minuten: Iterable<Int>, anfang: State = State(), werte: (Int) -> Sample): List<Result> {
        var s = anfang
        return minuten.map { m -> NoInputWatch.step(s, werte(m)).also { s = it.state } }
    }

    /** Fuenf Zyklen mit Eingang: der letzte Eingang liegt in Minute 5. */
    private fun nachEingang(): State = lauf(0..5) { m -> sample(m, eingang = true) }.last().state

    @Test
    fun `mit Eingang gibt es nichts zu melden`() {
        val r = lauf(0..30) { m -> sample(m, eingang = true) }
        assertTrue(r.all { it.stage == Stage.NONE })
        assertNull(r.last().state.sinceTs)
        assertEquals(start + 30 * minute, r.last().state.lastInputTs)
        assertEquals(0, r.last().minutesWithoutInput)
    }

    /** Der Anlass: der letzte Zyklus mit Eingang, dann eine Sensorpause, danach nur noch Abbrueche bei hohem Zucker. */
    @Test
    fun `nach zehn Minuten ohne Eingang bei hohem Zucker wird die Meldung sichtbar, ohne Ton`() {
        // Minute 6 bis 16 kommt kein Messwert, also laeuft kein Zyklus; der erste danach bricht ab.
        val r = NoInputWatch.step(nachEingang(), sample(17, eingang = false))
        assertEquals(Stage.VISIBLE, r.stage)
        assertEquals(12, r.minutesWithoutInput)
        assertNull(r.state.alarmKeyTs, "die sichtbare Stufe hat keinen Alarmton")
        assertEquals(start + 5 * minute, r.state.sinceTs)
    }

    @Test
    fun `vor Ablauf der zehn Minuten kommt nichts`() {
        val r = lauf(6..14, nachEingang()) { m -> sample(m, eingang = false) }
        assertTrue(r.all { it.stage == Stage.NONE })
        assertEquals(9, r.last().minutesWithoutInput)
        // In Minute 15 sind es zehn.
        assertEquals(Stage.VISIBLE, NoInputWatch.step(r.last().state, sample(15, eingang = false)).stage)
    }

    @Test
    fun `haelt die Lage eine halbe Stunde an, wird die Meldung laut`() {
        val r = lauf(6..40, nachEingang()) { m -> sample(m, eingang = false) }
        // Letzter Eingang in Minute 5: sichtbar ab Minute 15, laut ab Minute 35.
        assertEquals(Stage.NONE, r[14 - 6].stage)
        assertEquals(Stage.VISIBLE, r[15 - 6].stage)
        assertEquals(Stage.VISIBLE, r[34 - 6].stage)
        assertEquals(Stage.LOUD, r[35 - 6].stage)
        assertEquals(start + 35 * minute, r[35 - 6].state.alarmKeyTs)
        assertTrue(r.subList(0, 35 - 6).all { it.state.alarmKeyTs == null })
    }

    @Test
    fun `im Zielbereich bei ruhigem Verlauf schweigt der Waechter, bei steigendem Zucker nicht`() {
        val vorher = nachEingang()
        val ruhig = lauf(6..60, vorher) { m -> sample(m, eingang = false, bg = 110.0, steigung = 0.2) }
        assertTrue(ruhig.all { it.stage == Stage.NONE })
        // Noch nicht hoch, aber deutlich steigend.
        val steigend = lauf(6..20, vorher) { m -> sample(m, eingang = false, bg = 140.0, steigung = 2.5) }
        assertEquals(9, steigend.indexOfFirst { it.stage == Stage.VISIBLE })
        // Unbekannte Werte sind kein Grund fuer eine Meldung.
        val unbekannt = lauf(6..60, vorher) { m -> sample(m, eingang = false, bg = Double.NaN, steigung = Double.NaN) }
        assertTrue(unbekannt.all { it.stage == Stage.NONE })
    }

    @Test
    fun `in der lauten Stufe kommt nach einer Viertelstunde eine neue Kennung`() {
        val r = lauf(6..70, nachEingang()) { m -> sample(m, eingang = false) }
        val kennungen = r.mapNotNull { it.state.alarmKeyTs }.distinct()
        // Laut ab Minute 35, dann alle 15 Minuten neu: 35, 50, 65.
        assertEquals(listOf(35, 50, 65).map { start + it * minute }, kennungen)
    }

    @Test
    fun `der erste Zyklus mit Eingang nimmt die Meldung zurueck, und die Zaehlung beginnt neu`() {
        val laut = lauf(6..40, nachEingang()) { m -> sample(m, eingang = false) }.last()
        assertEquals(Stage.LOUD, laut.stage)

        val wieder = NoInputWatch.step(laut.state, sample(41, eingang = true))
        assertEquals(Stage.NONE, wieder.stage)
        assertNull(wieder.state.alarmKeyTs)
        assertNull(wieder.state.sinceTs)

        val danach = lauf(42..50, wieder.state) { m -> sample(m, eingang = false) }
        assertTrue(danach.all { it.stage == Stage.NONE }, "neun Minuten nach dem letzten Eingang ist noch nichts zu melden")
    }

    @Test
    fun `faellt der Zucker unter die Grenze und steigt nicht, verstummt die Meldung ohne Eingang`() {
        val laut = lauf(6..40, nachEingang()) { m -> sample(m, eingang = false) }.last()
        val r = NoInputWatch.step(laut.state, sample(41, eingang = false, bg = 150.0, steigung = -1.0))
        assertEquals(Stage.NONE, r.stage)
        assertNull(r.state.alarmKeyTs)
        // Die Zeit ohne Eingang laeuft weiter.
        assertEquals(36, r.minutesWithoutInput)
        // Steigt er wieder ueber die Grenze, ist die Meldung sofort wieder laut - mit neuer Kennung.
        val wieder = NoInputWatch.step(r.state, sample(42, eingang = false))
        assertEquals(Stage.LOUD, wieder.stage)
        assertEquals(start + 42 * minute, wieder.state.alarmKeyTs)
    }

    /** Nach einem Neustart gibt es noch keinen Zyklus mit Eingang: gezaehlt wird ab dem ersten ohne. */
    @Test
    fun `ohne frueheren Eingang zaehlt die Zeit ab dem ersten Abbruch`() {
        val r = lauf(0..32) { m -> sample(m, eingang = false) }
        assertEquals(10, r.indexOfFirst { it.stage == Stage.VISIBLE })
        assertEquals(30, r.indexOfFirst { it.stage == Stage.LOUD })
        assertEquals(start, r.last().state.sinceTs)
    }
}

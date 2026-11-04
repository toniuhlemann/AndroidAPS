package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.InsulinEffectWatch.Result
import app.aaps.fuse.core.controller.InsulinEffectWatch.Sample
import app.aaps.fuse.core.controller.InsulinEffectWatch.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Toni 04.11.2026: der Wirkungs-Waechter meldet einen moeglichen Patch-Ausfall - nur Meldung, keine Dosierung. */
class InsulinEffectWatchTest {

    private val minute = 60_000L
    private val start = 1_000_000_000_000L

    /** Ein Zyklus je Minute; [werte] liefert je Minute (beobachtet, erwartet, Bolus-IOB, Marker). */
    private fun lauf(
        minuten: IntRange,
        anfang: State = State(),
        werte: (Int) -> Sample = { m -> sample(m) },
    ): List<Result> {
        var s = anfang
        return minuten.map { m -> InsulinEffectWatch.step(s, werte(m)).also { s = it.state } }
    }

    private fun sample(
        m: Int,
        beobachtet: Double = 2.0,
        erwartet: Double = 5.0,
        bolus: Double = 5.0,
        marker: Long = 0L,
    ) = Sample(start + m * minute, beobachtet, erwartet, bolus, marker)

    /** Die erste Minute, in der der Alarm steht; -1 = nie. */
    private fun ersterAlarm(r: List<Result>) = r.indexOfFirst { it.alarm }

    @Test
    fun `steigt der Zucker trotz Insulin eine Stunde lang, kommt der Alarm`() {
        val r = lauf(0..70)
        // 30 min "steigt trotz Insulin", dann haelt die Regel 30 min - also in Minute 60.
        assertEquals(60, ersterAlarm(r))
        assertTrue(r[60].state.episodeOpen)
        assertEquals(start + 60 * minute, r[60].state.alarmKeyTs)
        assertFalse(r[59].state.episodeOpen, "vorher ist keine Episode offen")
    }

    @Test
    fun `nach einem Mahlzeiten-Marker zaehlt die Regel erst nach einer Stunde`() {
        val r = lauf(0..100) { m -> sample(m, marker = start) }
        // Regel ab Minute 60 (Marker + 60), Alarm 30 min spaeter.
        assertEquals(90, ersterAlarm(r))
    }

    @Test
    fun `ein spaeter geleerter Marker verkuerzt die Wartezeit nicht`() {
        val r = lauf(0..100) { m -> sample(m, marker = if (m < 5) start else 0L) }
        assertEquals(90, ersterAlarm(r))
    }

    @Test
    fun `eine Mahlzeit, deren Anstieg abbremst, loest nichts aus`() {
        // Ab Minute 45 steigt der Zucker kaum noch - wirkendes Insulin holt die Mahlzeit ein.
        val r = lauf(0..120) { m -> sample(m, beobachtet = if (m < 45) 2.0 else 0.3) }
        assertEquals(-1, ersterAlarm(r))
        assertTrue(r.none { it.state.episodeOpen })
    }

    @Test
    fun `ohne genug Bolus-IOB oder ohne erwarteten Abfall kein Alarm`() {
        assertEquals(-1, ersterAlarm(lauf(0..120) { m -> sample(m, bolus = 2.9) }))
        assertEquals(-1, ersterAlarm(lauf(0..120) { m -> sample(m, erwartet = 2.9) }))
    }

    @Test
    fun `unbekannte Werte zaehlen nicht und beginnen die Zaehlung neu`() {
        val r = lauf(0..100) { m -> sample(m, beobachtet = if (m == 40) Double.NaN else 2.0) }
        // Neubeginn in Minute 41: Regel ab 71, Alarm ab 101 - also nicht mehr im Lauf.
        assertEquals(-1, ersterAlarm(r))
    }

    @Test
    fun `eine Pause von mehr als zehn Minuten beginnt von vorn, eine kurze nicht`() {
        val lang = lauf(0..90) { m -> sample(if (m < 40) m else m + 11) }
        assertEquals(-1, ersterAlarm(lang), "nach elf Minuten Pause zaehlt alles neu")
        val kurz = lauf(0..100) { m -> sample(if (m < 40) m else m + 9) }
        // Die Pause zaehlt als Zeit: Alarm in Minute 60 der Uhr, das ist Index 51.
        assertEquals(51, ersterAlarm(kurz))
    }

    @Test
    fun `laeuft der Alarm weiter, kommt nach einer Stunde eine neue Kennung`() {
        val r = lauf(0..130)
        assertEquals(start + 60 * minute, r[119].state.alarmKeyTs)
        assertEquals(start + 120 * minute, r[120].state.alarmKeyTs)
    }

    @Test
    fun `die Episode endet erst nach zehn Minuten Fallen`() {
        val vorher = lauf(0..60).last().state
        assertTrue(vorher.episodeOpen)
        val neun = lauf(61..69, anfang = vorher) { m -> sample(m, beobachtet = -1.0) }
        assertTrue(neun.last().state.episodeOpen, "acht Minuten Fallen reichen nicht")
        val zehn = lauf(61..71, anfang = vorher) { m -> sample(m, beobachtet = -1.0) }
        assertFalse(zehn.last().state.episodeOpen)
        assertNull(zehn.last().state.alarmKeyTs)
    }

    @Test
    fun `faellt nur die Regel weg, bleibt die Meldung stehen, klingelt aber nicht neu`() {
        val vorher = lauf(0..60).last().state
        // Das Modell-IOB klingt ab, der Zucker steigt weiter: keine Regel, aber auch kein Fallen.
        val r = lauf(61..200, anfang = vorher) { m -> sample(m, erwartet = 1.0, bolus = 1.0) }
        assertTrue(r.all { it.state.episodeOpen })
        assertTrue(r.all { it.state.alarmKeyTs == vorher.alarmKeyTs })
        assertTrue(r.none { it.alarm })
    }

    @Test
    fun `derselbe Messwert zweimal zaehlt nicht doppelt`() {
        val vorher = lauf(0..30).last().state
        val doppelt = InsulinEffectWatch.step(vorher, sample(30))
        assertEquals(vorher, doppelt.state)
        assertFalse(doppelt.alarm)
    }

    @Test
    fun `eine neue Episode bekommt eine neue Kennung`() {
        val erste = lauf(0..60).last().state
        val zu = lauf(61..75, anfang = erste) { m -> sample(m, beobachtet = -1.0) }.last().state
        assertFalse(zu.episodeOpen)
        val zweite = lauf(76..140, anfang = zu)
        val key = zweite.first { it.alarm }.state.alarmKeyTs
        assertNotEquals(erste.alarmKeyTs, key)
    }
}

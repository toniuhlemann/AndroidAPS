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

    // ---- Die Sensorpause im schnellen Anstieg (Toni 20.11.2026) ----------------------------------------------------

    /** Ein Zyklus mit Messwert: der Zucker steigt gleichmaessig um [jeMinute] mg/dl. */
    private fun mitWert(m: Int, jeMinute: Double = 3.0, marker: Long = 0L) =
        Sample(start + m * minute, 2.0, 5.0, 5.0, marker, bgMgdl = 150.0 + jeMinute * m)

    /**
     * Der Anlass: bei schnellem Anstieg liefert der Sensor immer wieder gut zehn Minuten keinen Aktualwert. Steigt der
     * Zucker ueber die Pause hinweg weiter, laeuft die Zaehlung durch - der Alarm kommt trotz der Pausen.
     */
    @Test
    fun `eine Sensorpause, ueber die der Zucker weiter steigt, unterbricht die Zaehlung nicht`() {
        // Zyklen in den Minuten 0-20, 33-40, 55-70: zwei Pausen von 13 und 15 Minuten.
        val minuten = (0..20) + (33..40) + (55..70)
        val r = lauf2(minuten) { m -> mitWert(m) }
        val alarmMinute = minuten[r.indexOfFirst { it.alarm }]
        // "Steigt trotz Insulin" gilt seit Minute 0. Die Regel kann erst im ersten Zyklus nach 30 Minuten halten - das
        // ist Minute 33, weil in der Pause kein Zyklus lief - und 30 Minuten spaeter kommt der Alarm.
        assertEquals(63, alarmMinute)
    }

    @Test
    fun `ohne Messwerte oder bei gleichem Zucker bleibt die Pause eine Luecke`() {
        val minuten = (0..20) + (33..80)
        // Ohne Messwert (wie vor dem 20.11.): die Zaehlung beginnt nach der Pause neu - Alarm 60 Minuten nach Minute 33.
        val ohne = lauf2(minuten) { m -> sample(m) }
        assertEquals(-1, ohne.indexOfFirst { it.alarm })
        // Mit Messwert, aber ohne Anstieg ueber die Pause: ebenso.
        val flach = lauf2(minuten) { m -> Sample(start + m * minute, 2.0, 5.0, 5.0, 0L, bgMgdl = 200.0) }
        assertEquals(-1, flach.indexOfFirst { it.alarm })
    }

    @Test
    fun `eine Pause ueber dreissig Minuten bleibt eine Luecke, auch wenn der Zucker gestiegen ist`() {
        val minuten = (0..20) + (52..100)
        val r = lauf2(minuten) { m -> mitWert(m) }
        // Neu gezaehlt ab Minute 52: Alarm fruehestens 60 Minuten spaeter - im Lauf bis Minute 100 nicht.
        assertEquals(-1, r.indexOfFirst { it.alarm })
        assertEquals(start + 52 * minute, r[minuten.indexOf(52)].state.riseSinceTs)
    }

    /** Wie [lauf], aber mit Luecken in den Minuten. */
    private fun lauf2(minuten: List<Int>, werte: (Int) -> Sample): List<Result> {
        var s = State()
        return minuten.map { m -> InsulinEffectWatch.step(s, werte(m)).also { s = it.state } }
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

    // ---- Der fruehe Hinweis mit dem Kolben (Toni 20.11.2026) ------------------------------------------------------

    @Test
    fun `die Minuten des Anstiegs zaehlen ab seinem Beginn, und ohne Anstieg sind es null`() {
        assertEquals(0, InsulinEffectWatch.risingMinutes(State()))
        val r = lauf(0..12)
        assertEquals(0, InsulinEffectWatch.risingMinutes(r[0].state))
        assertEquals(7, InsulinEffectWatch.risingMinutes(r[7].state))
        assertEquals(12, InsulinEffectWatch.risingMinutes(r[12].state))
        // Faellt der Zucker, ist der Anstieg vorbei.
        val gefallen = InsulinEffectWatch.step(r[12].state, sample(13, beobachtet = -1.0)).state
        assertEquals(0, InsulinEffectWatch.risingMinutes(gefallen))
    }

    /** Beide Zeichen zusammen: nach zehn Minuten Anstieg trotz Insulin, wenn die Pumpe einen zu kurzen Kolbenweg meldet. */
    @Test
    fun `steigt der Zucker zehn Minuten trotz Insulin und laeuft der Kolben zu kurz, gilt der fruehe Hinweis`() {
        val r = lauf(0..20)
        assertFalse(InsulinEffectWatch.riseWithSlowPlunger(r[9].state, 80), "neun Minuten reichen nicht")
        assertTrue(InsulinEffectWatch.riseWithSlowPlunger(r[10].state, 80))
        assertTrue(InsulinEffectWatch.riseWithSlowPlunger(r[20].state, 80))
    }

    @Test
    fun `ohne Kolbenbefund gibt es keinen fruehen Hinweis, wie lange der Zucker auch steigt`() {
        val r = lauf(0..50)
        assertTrue(r.none { InsulinEffectWatch.riseWithSlowPlunger(it.state, null) })
    }

    /** Der Anlass: der Anstieg in der Stunde nach einem Snack. Die Wartezeit nach dem Marker gilt fuer den Hinweis nicht. */
    @Test
    fun `der fruehe Hinweis wartet nicht auf die Stunde nach dem Marker`() {
        val r = lauf(0..20) { m -> sample(m, marker = start) }
        assertTrue(InsulinEffectWatch.riseWithSlowPlunger(r[10].state, 80))
        assertEquals(-1, ersterAlarm(r), "der laute Alarm wartet weiter")
    }

    @Test
    fun `ist die Episode des lauten Alarms offen, schweigt der fruehe Hinweis`() {
        val r = lauf(0..70)
        assertTrue(InsulinEffectWatch.riseWithSlowPlunger(r[59].state, 80))
        assertTrue(r[60].state.episodeOpen)
        assertFalse(InsulinEffectWatch.riseWithSlowPlunger(r[60].state, 80))
        assertFalse(InsulinEffectWatch.riseWithSlowPlunger(r[70].state, 80))
    }

    @Test
    fun `steigt der Zucker nicht mehr trotz Insulin, ist der fruehe Hinweis vorbei`() {
        val vorher = lauf(0..15).last().state
        assertTrue(InsulinEffectWatch.riseWithSlowPlunger(vorher, 80))
        // Das Insulin wirkt wieder: der Zucker faellt.
        val danach = InsulinEffectWatch.step(vorher, sample(16, beobachtet = -0.5)).state
        assertFalse(InsulinEffectWatch.riseWithSlowPlunger(danach, 80))
        // Und ein neuer Anstieg beginnt seine zehn Minuten von vorn.
        val neu = lauf(17..27, anfang = danach)
        assertFalse(InsulinEffectWatch.riseWithSlowPlunger(neu[9].state, 80), "neun Minuten nach dem neuen Beginn")
        assertTrue(InsulinEffectWatch.riseWithSlowPlunger(neu[10].state, 80))
    }
}

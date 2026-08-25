package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Der Freigabe-Nachlauf als reine Rechnung - Pflichtfall 25.08.: die
 * Nachtband-Kante um 08:00 oeffnete in der ERSTEN Minute 0,10 U und bis
 * 08:03 insgesamt 0,35 U, direkt nach einer Stunde verriegelter Null.
 */
class PositiveCorrectionRearmTest {

    private val t0 = 1_700_000_000_000L

    private fun schritt(
        track: PositiveCorrectionRearm.Track,
        minute: Int,
        ukf: Double,
        kontext: Boolean = true,
        enabled: Boolean = true,
        holdMin: Int = 5,
        confirm: Int = 2,
        gesund: Boolean = true,
    ) = PositiveCorrectionRearm.advance(
        track = track, enabled = enabled, nowTs = t0 + minute * 60_000L,
        ukfNow = ukf, korrekturKontext = kontext,
        holdMin = holdMin, confirmCycles = confirm, upThresholdUkf = 0.3,
        lageGesund = gesund,
    )

    @Test
    fun `der pflichtfall - die kante oeffnet nicht in der naechsten minute`() {
        var track = PositiveCorrectionRearm.anker(
            PositiveCorrectionRearm.Track(), t0, PositiveCorrectionRearm.Source.NIGHT_END,
        )
        // 08:00-08:03-artig: Aufwaertslage steht, aber die Frist traegt.
        for ((min, ukf) in listOf(0 to 0.84, 1 to 0.89, 2 to 0.83, 3 to 0.65, 4 to 0.45)) {
            val (t, res) = schritt(track, min, ukf)
            track = t
            assertTrue(res.blocks, "Minute $min liegt im Nachlauf")
            assertEquals(PositiveCorrectionRearm.REASON_HOLD, res.reason)
            assertEquals(PositiveCorrectionRearm.Source.NIGHT_END, res.source)
        }
        // Frist um UND Aufwaertslage laengst bestaetigt: Freigabe, Anker weg.
        val (leer, frei) = schritt(track, 5, 0.5)
        assertFalse(frei.blocks)
        assertEquals(0L, leer.ankerTs, "die Freigabe beendet den Anker ganz")
    }

    @Test
    fun `nach der frist braucht es die bestaetigte aufwaertslage`() {
        var track = PositiveCorrectionRearm.anker(
            PositiveCorrectionRearm.Track(), t0, PositiveCorrectionRearm.Source.ZERO_LATCH_RELEASED,
        )
        // Waehrend der Frist faellt es weiter - kein Aufwaerts-Streak.
        for (min in 0..5) track = schritt(track, min, -0.2).first
        val (t1, r1) = schritt(track, 6, -0.1)
        track = t1
        assertTrue(r1.blocks, "ohne Aufwaertslage bleibt es zu")
        assertEquals(PositiveCorrectionRearm.REASON_UNCONFIRMED, r1.reason)
        // Zwei zusammenhaengende Aufwaertszyklen geben frei.
        track = schritt(track, 7, 0.4).first
        val (_, r3) = schritt(track, 8, 0.5)
        assertFalse(r3.blocks, "bestaetigte Aufwaertslage nach der Frist gibt frei")
    }

    @Test
    fun `eine luecke nullt den aufwaerts-zaehler`() {
        var track = PositiveCorrectionRearm.anker(
            PositiveCorrectionRearm.Track(), t0, PositiveCorrectionRearm.Source.NIGHT_END,
        )
        for (min in 0..5) track = schritt(track, min, -0.2).first
        track = schritt(track, 6, 0.4).first // 1/2
        val (_, res) = schritt(track, 9, 0.5) // Luecke > 90 s
        assertTrue(res.blocks, "nach der Luecke beginnt die Bestaetigung neu")
        assertEquals(1, res.upConfirmStreak)
    }

    @Test
    fun `eine ungesunde lage zaehlt keine aufwaertszyklen`() {
        // Tonis Review-P0.3: Signalstoerung, fallendes q1, Low, Descent,
        // Rebound oder Hold duerfen kein fruehes Oeffnen vorbereiten -
        // der Aufrufer meldet die Lage, der Zaehler nullt.
        var track = PositiveCorrectionRearm.anker(
            PositiveCorrectionRearm.Track(), t0, PositiveCorrectionRearm.Source.ZERO_LATCH_RELEASED,
        )
        for (min in 0..5) track = schritt(track, min, -0.2).first
        // Aufwaerts-UKF, aber die Lage ist ungesund: es zaehlt NICHT.
        val (t1, r1) = schritt(track, 6, 0.8, gesund = false)
        track = t1
        assertTrue(r1.blocks)
        assertEquals(0, r1.upConfirmStreak, "ungesund zaehlt nicht")
        val (t2, r2) = schritt(track, 7, 0.8)
        track = t2
        assertTrue(r2.blocks, "ein gesunder Zyklus allein reicht nicht")
        assertEquals(1, r2.upConfirmStreak)
        // Ein ungesunder Zyklus MITTEN in der Bestaetigung nullt.
        track = schritt(track, 8, 0.8, gesund = false).first
        val (t4, r4) = schritt(track, 9, 0.8)
        track = t4
        assertTrue(r4.blocks, "nach der Stoerung beginnt die Bestaetigung neu")
        assertEquals(1, r4.upConfirmStreak)
        val (_, frei) = schritt(track, 10, 0.8)
        assertFalse(frei.blocks, "zwei gesunde Aufwaertszyklen geben frei")
    }

    @Test
    fun `ein nie bestaetigter anker haengt nicht unbegrenzt nach`() {
        // Gemessen am 25.08.: die Kante lag 08:00, die Lage war danach
        // sechs Minuten lang Mahlzeit (kinematisches Fenster), und der
        // nie freigegebene Anker riegelte erst 08:23-08:26 - in einer
        // voellig anderen Lage. Nach Ablauf der Frist beendet ein
        // Nicht-Korrektur-Zyklus den Anker.
        var track = PositiveCorrectionRearm.anker(
            PositiveCorrectionRearm.Track(), t0, PositiveCorrectionRearm.Source.NIGHT_END,
        )
        // Waehrend der Frist aendert ein Mahlzeitenzyklus nichts.
        val (t1, inFrist) = schritt(track, 2, 0.8, kontext = false)
        track = t1
        assertFalse(inFrist.blocks, "ausserhalb des Korrekturkontexts blockt nie")
        assertTrue(track.ankerTs > 0L, "waehrend der Frist bleibt der Anker stehen")
        // Zurueck im Korrekturkontext INNERHALB der Frist: er traegt.
        val (_, wiederKorrektur) = schritt(track, 3, -0.2)
        assertTrue(wiederKorrektur.blocks)
        // NACH der Frist beendet ein Mahlzeitenzyklus den Anker.
        val (leer, nachFrist) = schritt(track, 6, 0.8, kontext = false)
        assertFalse(nachFrist.blocks)
        assertEquals(PositiveCorrectionRearm.Track(), leer, "der Anker verfaellt")
        val (_, danach) = schritt(leer, 7, -0.2)
        assertFalse(danach.blocks, "spaetere Korrekturzyklen sind frei")
    }

    @Test
    fun `der nachlauf endet spaetestens nach der hoechstdauer`() {
        // Gemessen am 25.08.: die Kante lag 08:00, die Aufwaertslage
        // blieb unbestaetigt, und der Anker riegelte noch 08:23-08:26.
        // Nach dem Dreifachen der Frist ist ein Kanteneffekt vorbei.
        var track = PositiveCorrectionRearm.anker(
            PositiveCorrectionRearm.Track(), t0, PositiveCorrectionRearm.Source.NIGHT_END,
        )
        // Dauerhaft fallend: nie bestaetigt, Kontext durchgehend Korrektur.
        for (min in 0..14) {
            val (t, res) = schritt(track, min, -0.2)
            track = t
            assertTrue(res.blocks, "Minute $min liegt noch im Nachlauf")
        }
        // Minute 15 = 3 x holdMin(5): der Anker verfaellt.
        val (leer, frei) = schritt(track, 15, -0.2)
        assertFalse(frei.blocks, "nach der Hoechstdauer ist Schluss")
        assertEquals(PositiveCorrectionRearm.Track(), leer, "der Anker ist weg")
        // Und bleibt weg.
        val (_, danach) = schritt(leer, 16, -0.2)
        assertFalse(danach.blocks)
    }

    @Test
    fun `restored erhaelt den anker und nullt den zaehler`() {
        // Tonis Review-P0.1: der Nachlauf ueberlebt den Neustart.
        val wieder = PositiveCorrectionRearm.restored(
            t0, PositiveCorrectionRearm.Source.NIGHT_END,
        )
        assertEquals(t0, wieder.ankerTs)
        assertEquals(PositiveCorrectionRearm.Source.NIGHT_END, wieder.quelle)
        assertEquals(0, wieder.upStreak)
        val (_, res) = schritt(wieder, 1, 0.8)
        assertTrue(res.blocks, "der restaurierte Anker traegt")
        assertEquals(PositiveCorrectionRearm.REASON_HOLD, res.reason)
        assertEquals(PositiveCorrectionRearm.Track(), PositiveCorrectionRearm.restored(0L, PositiveCorrectionRearm.Source.NIGHT_END))
    }

    @Test
    fun `kontext und schalter`() {
        var track = PositiveCorrectionRearm.anker(
            PositiveCorrectionRearm.Track(), t0, PositiveCorrectionRearm.Source.NIGHT_END,
        )
        val (_, mahlzeit) = schritt(track, 1, 0.8, kontext = false)
        assertFalse(mahlzeit.blocks, "Mahlzeitenpfade bleiben frei")
        val (leer, aus) = schritt(track, 1, 0.8, enabled = false)
        assertFalse(aus.blocks)
        assertEquals(PositiveCorrectionRearm.Track(), leer, "AUS leert den Zustand")
        val (_, ohneAnker) = schritt(PositiveCorrectionRearm.Track(), 1, 0.8)
        assertFalse(ohneAnker.blocks, "ohne Kante gibt es keinen Nachlauf")
    }
}

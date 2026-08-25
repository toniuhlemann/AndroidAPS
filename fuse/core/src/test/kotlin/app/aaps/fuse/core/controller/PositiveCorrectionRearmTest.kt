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
    ) = PositiveCorrectionRearm.advance(
        track = track, enabled = enabled, nowTs = t0 + minute * 60_000L,
        ukfNow = ukf, korrekturKontext = kontext,
        holdMin = holdMin, confirmCycles = confirm, upThresholdUkf = 0.3,
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

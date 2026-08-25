package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Der V-Reversal-Schutz als reine Rechnung - die Zahlen sind der
 * PFLICHTFALL vom 25.08. frueh (06:12-06:33): Fall auf UKF -2,81, dann
 * Erholung UKF +4,0 bei robustem r -0,82, erst danach bestaetigt sich r.
 */
class CorrectionReversalGuardTest {

    private val t0 = 1_700_000_000_000L

    private fun schritt(
        track: CorrectionReversalGuard.Track,
        minute: Int,
        ukf: Double,
        r: Double?,
        kontext: Boolean = true,
        enabled: Boolean = true,
        confirm: Int = 2,
    ) = CorrectionReversalGuard.advance(
        track = track, enabled = enabled, nowTs = t0 + minute * 60_000L,
        ukfNow = ukf, rNow = r, korrekturKontext = kontext,
        fallThresholdUkf = 2.0, lookbackMin = 20, reboundThresholdUkf = 1.0,
        confirmCycles = confirm,
    )

    @Test
    fun `der pflichtfall - block bei negativem und unbestaetigtem r, frei nach bestaetigung`() {
        var track = CorrectionReversalGuard.Track()
        // Der Fall: 06:14-06:18-artig, Minimum -2,81.
        for ((min, ukf, r) in listOf(
            Triple(0, -1.28, -0.22), Triple(1, -2.44, -0.26),
            Triple(2, -2.81, -0.94), Triple(3, -2.68, -1.93),
        )) {
            val (t, res) = schritt(track, min, ukf, r)
            track = t
            assertFalse(res.blocks, "im Fall selbst blockt nichts (min $min)")
        }
        // Die Gegenbewegung: 06:27-artig, UKF +4,0 bei r -0,82.
        val (t1, r1) = schritt(track, 11, 4.00, -0.82)
        track = t1
        assertTrue(r1.blocks, "die V-Erholung traegt keinen Korrektur-SMB")
        assertEquals(CorrectionReversalGuard.REASON_R_NEGATIVE, r1.reason)
        assertEquals(-2.81, r1.fallMinUkf!!, 1e-9, "das Fall-Minimum steht im Urteil")
        // r wird positiv, aber erst EIN Zyklus: weiter zu.
        val (t2, r2) = schritt(track, 12, 3.94, 0.22)
        track = t2
        assertTrue(r2.blocks)
        assertEquals(CorrectionReversalGuard.REASON_R_UNCONFIRMED, r2.reason)
        assertEquals(1, r2.rConfirmStreak)
        // Zweiter zusammenhaengender positiver r-Zyklus: bestaetigt, frei.
        val (t3, r3) = schritt(track, 13, 3.52, 1.11)
        track = t3
        assertFalse(r3.blocks, "bestaetigtes r gibt die Korrektur frei")
        assertEquals(2, r3.rConfirmStreak)
    }

    @Test
    fun `die episode haelt auch nach abgeflachter gegenbewegung`() {
        // Der 06:30-Kern des Vorfalls: die UKF-Spitze war vorbei (BG flach
        // bei 147-149), aber die Prognose trug die Erholung noch und r war
        // weiter negativ - dort flossen real 06:30-06:33 weitere ~1,0 U.
        var track = CorrectionReversalGuard.Track()
        track = schritt(track, 0, -2.8, -0.9).first
        val (t1, zuend) = schritt(track, 4, 4.0, -0.8)
        track = t1
        assertTrue(zuend.blocks, "die Zuendung selbst blockt")
        // Abgeflachtes UKF, r weiter negativ: die Episode haelt.
        val (t2, flach1) = schritt(track, 5, 0.1, -0.4)
        track = t2
        assertTrue(flach1.blocks, "abgeflachtes UKF beendet die Episode nicht")
        assertEquals(CorrectionReversalGuard.REASON_R_NEGATIVE, flach1.reason)
        // Erst die r-Bestaetigung beendet sie - auch bei flachem UKF.
        track = schritt(track, 6, 0.2, 0.3).first
        val (_, frei) = schritt(track, 7, 0.1, 0.5)
        assertFalse(frei.blocks, "die r-Bestaetigung beendet die Episode")
        // Gegenprobe: faellt es nach der Zuendung ERNEUT tiefer, ersetzt
        // das neue Minimum und loescht die Zuendung - wieder fallend ist
        // keine Gegenbewegung.
        var t = CorrectionReversalGuard.Track()
        t = schritt(t, 0, -2.5, -0.9).first
        t = schritt(t, 1, 3.0, -0.8).first // Zuendung
        t = schritt(t, 2, -2.9, -1.2).first // tieferer Fall ersetzt
        val (_, wiederFallend) = schritt(t, 3, 0.2, -0.9)
        assertFalse(wiederFallend.blocks, "ein neuer Fall traegt keine alte Zuendung")
    }

    @Test
    fun `ohne steilen fall oder ohne gegenbewegung blockt nichts`() {
        var track = CorrectionReversalGuard.Track()
        // Flacher Verlauf, dann Anstieg: kein Fall-Minimum unter -2,0.
        val (t1, _) = schritt(track, 0, -0.5, -0.2)
        track = t1
        val (t2, r2) = schritt(track, 1, 4.0, -0.8)
        track = t2
        assertFalse(r2.blocks, "ohne steilen Fall ist ein Anstieg kein V")
        // Steiler Fall, aber die Erholung bleibt unter der Schwelle.
        var t = CorrectionReversalGuard.Track()
        t = schritt(t, 0, -2.8, -0.9).first
        val (_, langsam) = schritt(t, 2, 0.6, -0.5)
        assertFalse(langsam.blocks, "eine langsame Erholung ist keine Gegenbewegung")
    }

    @Test
    fun `vorbestaetigtes r zaehlt nicht ueber die zuendung`() {
        // Tonis Review-P1.5: laeuft r schon VOR der V-Zuendung positiv
        // (langsame Erholung unter der Gegenzug-Schwelle), darf der
        // Zaehler bei der Zuendung nicht bereits erfuellt sein - sonst
        // greift der Riegel bei dieser Kurvenform NIE.
        var track = CorrectionReversalGuard.Track()
        track = schritt(track, 0, -2.8, -0.9).first
        track = schritt(track, 1, 0.5, 0.5).first  // r positiv, keine Zuendung
        track = schritt(track, 2, 0.8, 0.8).first  // alter Code: streak 2
        val (t3, zuend) = schritt(track, 3, 3.0, 0.6)
        track = t3
        assertTrue(zuend.blocks, "die Zuendung beginnt die Bestaetigung NEU")
        assertEquals(CorrectionReversalGuard.REASON_R_UNCONFIRMED, zuend.reason)
        assertEquals(1, zuend.rConfirmStreak, "der Zuendungszyklus zaehlt als erster")
        val (_, frei) = schritt(track, 4, 2.5, 0.9)
        assertFalse(frei.blocks, "ab der Zuendung gezaehlt: zwei Zyklen geben frei")
    }

    @Test
    fun `restored erhaelt die identitaet und nullt die zaehler`() {
        // Tonis Review-P0.1: der Riegel ueberlebt den Neustart, die
        // r-Bestaetigung beginnt von vorn (konservative Richtung).
        var track = CorrectionReversalGuard.Track()
        track = schritt(track, 0, -2.8, -0.9).first
        track = schritt(track, 1, 3.0, 0.5).first // Zuendung + streak 1
        val wieder = CorrectionReversalGuard.restored(
            track.minUkf, track.minUkfTs, track.reboundSeenTs,
        )
        assertEquals(track.minUkf, wieder.minUkf, 1e-12)
        assertEquals(track.minUkfTs, wieder.minUkfTs)
        assertEquals(track.reboundSeenTs, wieder.reboundSeenTs, "die Zuendung bleibt")
        assertEquals(0, wieder.rPosStreak, "der Zaehler beginnt neu")
        // Direkt nach dem Neustart blockt der Riegel weiter.
        val (_, res) = schritt(wieder, 2, 0.4, 0.9)
        assertTrue(res.blocks, "die restaurierte Episode traegt")
        assertEquals(1, res.rConfirmStreak)
        // Leere Identitaet restauriert leer.
        assertEquals(CorrectionReversalGuard.Track(), CorrectionReversalGuard.restored(Double.NaN, 0L, 0L))
    }

    @Test
    fun `das fall-minimum verfaellt nach dem rueckblick`() {
        var track = CorrectionReversalGuard.Track()
        track = schritt(track, 0, -2.8, -0.9).first
        // 21 Minuten spaeter ist das Minimum verfallen.
        val (_, res) = schritt(track, 21, 4.0, -0.5)
        assertFalse(res.blocks, "ein verfallener Fall traegt den Riegel nicht mehr")
    }

    @Test
    fun `kontext und schalter - mahlzeit bleibt frei, aus ist aus`() {
        var track = CorrectionReversalGuard.Track()
        track = schritt(track, 0, -2.8, -0.9).first
        val (_, mahlzeit) = schritt(track, 2, 4.0, -0.8, kontext = false)
        assertFalse(mahlzeit.blocks, "ausserhalb des Korrekturkontexts NIE")
        val (leer, aus) = schritt(track, 2, 4.0, -0.8, enabled = false)
        assertFalse(aus.blocks)
        assertEquals(CorrectionReversalGuard.Track(), leer, "AUS leert den Zustand")
    }

    @Test
    fun `eine luecke nullt die r-bestaetigung`() {
        var track = CorrectionReversalGuard.Track()
        track = schritt(track, 0, -2.8, -0.9).first
        track = schritt(track, 1, 3.0, 0.5).first  // 1/2
        // Luecke > 90 s: der naechste positive Zyklus beginnt bei 1.
        val (_, res) = schritt(track, 4, 3.0, 0.8)
        assertTrue(res.blocks, "nach der Luecke ist r wieder unbestaetigt")
        assertEquals(1, res.rConfirmStreak)
    }

    @Test
    fun `unbrauchbares r blockt wie negatives`() {
        var track = CorrectionReversalGuard.Track()
        track = schritt(track, 0, -2.8, -0.9).first
        val (_, res) = schritt(track, 2, 4.0, null)
        assertTrue(res.blocks)
        assertEquals(CorrectionReversalGuard.REASON_R_NEGATIVE, res.reason)
        assertNull(res.fallMinAgeMin?.takeIf { it < 0 }, "Alter nie negativ")
    }
}

package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Der Marker-Prime-Aufschub als reine Zustandsmaschine - jede Zusicherung
 * hier ist ein Satz aus Tonis Vertragsliste vom 22.08.
 */
class DeferredPrimeTest {

    private val marker = 1_000_000L
    private fun gepinnt(openU: Double = 0.0) = DeferredPrime.State(
        openU = openU, pinnedForMarkerTs = marker,
        deadlineTs = marker + 120 * 60_000L, horizonMin = 60,
    )

    // ---- Pinnen (Vertraege 2 und 8) ---------------------------------------

    @Test
    fun `die Frist wird beim Marker gepinnt und eine Einstellungsaenderung verschiebt sie nicht`() {
        val s = DeferredPrime.pin(DeferredPrime.State(), marker, horizonMin = 60, endMin = 120)
        assertEquals(marker + 120 * 60_000L, s.deadlineTs)
        assertEquals(60, s.horizonMin)
        // Derselbe Marker mit INZWISCHEN anderer Einstellung: idempotent.
        val unveraendert = DeferredPrime.pin(s.copy(openU = 0.4), marker, horizonMin = 45, endMin = 60)
        assertEquals(s.deadlineTs, unveraendert.deadlineTs)
        assertEquals(60, unveraendert.horizonMin)
        assertEquals(0.4, unveraendert.openU, 1e-9)
    }

    @Test
    fun `ein neuer Marker laesst den alten Rest sichtbar verfallen und erbt nichts`() {
        val alt = gepinnt(openU = 0.85)
        val neu = DeferredPrime.pin(alt, marker + 3_600_000L, horizonMin = 60, endMin = 120)
        assertEquals(0.0, neu.openU, 1e-9)
        assertEquals(DeferredPrime.LapseReason.NEW_MARKER, neu.lastLapseReason)
        assertEquals(0.85, neu.lastLapseU, 1e-9)
        assertEquals(marker + 3_600_000L, neu.pinnedForMarkerTs)
    }

    // ---- Ablauf (Vertraege 9 und 10) --------------------------------------

    @Test
    fun `nach der gepinnten Frist verfaellt der Rest mit typisiertem Grund`() {
        val s = gepinnt(openU = 0.6)
        val vorher = DeferredPrime.expireIfDue(s, s.deadlineTs - 1L)
        assertEquals(0.6, vorher.openU, 1e-9)
        val danach = DeferredPrime.expireIfDue(s, s.deadlineTs)
        assertEquals(0.0, danach.openU, 1e-9)
        assertEquals(DeferredPrime.LapseReason.EXPIRED, danach.lastLapseReason)
        assertEquals(0.6, danach.lastLapseU, 1e-9)
        assertTrue(danach.valid)
    }

    // Konsolidierung 2.0: Rueckhalt, Klemmung, Freigabe und Abbuchung sind entfallen - ihre Tests ebenso.

    @Test
    fun `ungueltige Kombinationen sind als solche erkennbar`() {
        assertTrue(DeferredPrime.State().valid)
        assertTrue(gepinnt(0.3).valid)
        // Offen ohne Pin: das darf ein Restore nie akzeptieren.
        assertTrue(!DeferredPrime.State(openU = 0.3).valid)
        // Pin ohne Frist ebensowenig.
        assertTrue(!DeferredPrime.State(pinnedForMarkerTs = 5L, deadlineTs = 0L, horizonMin = 60).valid)
    }
}

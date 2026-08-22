package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    // ---- Zurueckhalten (Vertraege 3 und 7) --------------------------------

    @Test
    fun `zurueckgehaltene Fluesse summieren sich aber nie ueber die Huelle`() {
        var s = gepinnt()
        s = DeferredPrime.withhold(s, 0.15, hullRemainingU = 1.0)
        s = DeferredPrime.withhold(s, 0.15, hullRemainingU = 1.0)
        assertEquals(0.30, s.openU, 1e-9)
        s = DeferredPrime.withhold(s, 0.90, hullRemainingU = 1.0)
        assertEquals(1.0, s.openU, 1e-9, "die gepinnte Huelle deckelt")
        // Ohne Pin wird nichts gebucht.
        assertEquals(0.0, DeferredPrime.withhold(DeferredPrime.State(), 0.15, 1.0).openU, 1e-9)
    }

    @Test
    fun `jede Lieferung verkleinert denselben offenen Betrag`() {
        // Nach einem normalen SMB / Fundament-Lift / manuellen Bolus sinkt die
        // Resthuelle - der offene Aufschub folgt ihr nach unten (Vertrag 6).
        val s = DeferredPrime.clampToHull(gepinnt(openU = 0.8), hullRemainingU = 0.35)
        assertEquals(0.35, s.openU, 1e-9)
        assertEquals(0.35, DeferredPrime.clampToHull(s, 0.9).openU, 1e-9, "nach oben waechst nichts")
    }

    // ---- Freigabe (Vertraege 4 und 5) -------------------------------------

    private fun frei(
        s: DeferredPrime.State = gepinnt(openU = 0.4),
        enabled: Boolean = true,
        markerTs: Long = marker,
        hold: Boolean = false,
        latch: Boolean = false,
        erholt: Boolean = true,
        healthy: Boolean = true,
        low: Boolean = false,
        rebound: Boolean = false,
        risk: Boolean = false,
        manual: Double? = 0.0,
        step: Double = 0.05,
        hull: Double = 2.0,
    ) = DeferredPrime.releaseStep(s, marker + 60 * 60_000L, enabled, markerTs, hold, latch, erholt, healthy, low, rebound, risk, manual, step, hull)

    @Test
    fun `die Freigabe ist hoechstens ein Pumpenschritt - kein Aufhol-Burst`() {
        val r = frei()
        assertNull(r.denial)
        assertEquals(0.05, r.stepU, 1e-9, "0,4 U offen, aber genau EIN Schritt")
    }

    @Test
    fun `jede Verweigerung ist typisiert und die Reihenfolge ist fest`() {
        assertEquals(DeferredPrime.Denial.DISABLED, frei(enabled = false).denial)
        assertEquals(DeferredPrime.Denial.NOT_PINNED, frei(s = DeferredPrime.State()).denial)
        assertEquals(DeferredPrime.Denial.MARKER_MISMATCH, frei(markerTs = marker + 1).denial)
        assertEquals(DeferredPrime.Denial.NOTHING_OPEN, frei(s = gepinnt(openU = 0.0)).denial)
        assertEquals(DeferredPrime.Denial.LEDGER_HOLD, frei(hold = true).denial)
        assertEquals(DeferredPrime.Denial.LATCH_ACTIVE, frei(latch = true).denial)
        assertEquals(DeferredPrime.Denial.RECOVERY_UNCONFIRMED, frei(erholt = false).denial)
        assertEquals(DeferredPrime.Denial.SIGNAL_UNHEALTHY, frei(healthy = false).denial)
        assertEquals(DeferredPrime.Denial.MEASURED_LOW, frei(low = true).denial)
        assertEquals(DeferredPrime.Denial.REBOUND_ACTIVE, frei(rebound = true).denial)
        assertEquals(DeferredPrime.Denial.DESCENT_RISK, frei(risk = true).denial)
        assertEquals(DeferredPrime.Denial.MANUAL_BOLUS_UNKNOWN, frei(manual = null).denial)
        assertEquals(DeferredPrime.Denial.HULL_EXHAUSTED, frei(hull = 0.0).denial)
        assertEquals(DeferredPrime.Denial.BELOW_STEP, frei(s = gepinnt(openU = 0.02)).denial)
    }

    @Test
    fun `consume bucht nur Positives und nie unter null`() {
        val s = DeferredPrime.consume(gepinnt(openU = 0.10), 0.05)
        assertEquals(0.05, s.openU, 1e-9)
        assertEquals(0.0, DeferredPrime.consume(s, 0.10).openU, 1e-9)
        assertEquals(0.05, DeferredPrime.consume(s, -1.0).openU, 1e-9)
    }

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

package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER §4-VERTRAG AUF KONTEXTEBENE (Bauauftrag, Schritt A1). Jede Zusicherung
 * entspricht einem Pflichtfall der §10-Tabelle bzw. einem Livefall.
 */
class DosingContextTest {

    private val T0 = 1_700_000_000_000L
    private val MIN = 60_000L

    /** Pflichtfall "Hoher Rise ohne gueltige Autorisierung -> keine
     *  Hochstufung": der Kontext kennt strukturell nur den Pin - ohne Pin
     *  gibt es kein MEAL, egal was Kinematik oder Evidenz behaupten
     *  (Livefall 27.08.: KINEMATIC_ONLY-Fenster bei leerer Lage). */
    @Test
    fun `ohne Marker gilt CORRECTION`() {
        val d = DosingContext.decide(nowMs = T0, markerTs = 0L, pinnedFor = 0L, deadlineTs = 0L)
        assertEquals(DosingContext.Profile.CORRECTION, d.profile)
        assertEquals(DosingContext.Reason.NO_MARKER, d.reason)
        assertTrue(!d.mealAuthorized)
    }

    /** Innerhalb der gepinnten Frist gilt MEAL mit Identitaet und Ablauf. */
    @Test
    fun `innerhalb der gepinnten Frist gilt MEAL`() {
        val marker = T0
        val d = DosingContext.decide(
            nowMs = T0 + 30 * MIN, markerTs = marker,
            pinnedFor = marker, deadlineTs = marker + 120 * MIN,
        )
        assertEquals(DosingContext.Profile.MEAL, d.profile)
        assertEquals(DosingContext.Reason.MARKER_POWER, d.reason)
        assertEquals(marker, d.authorizationId)
        assertEquals(marker + 120 * MIN, d.authorizationExpiresAt)
    }

    /** Pflichtfall "Marker-Power abgelaufen, Episode noch vorhanden ->
     *  CORRECTION, kein Wiederaufleben": HALB OFFEN - exakt an der Deadline
     *  gilt bereits CORRECTION (Livefall 28.08.: Power-Ablauf 20:37 mitten
     *  im Anstieg schaltete korrekt auf die K-Caps). */
    @Test
    fun `exakt an der Deadline gilt bereits CORRECTION`() {
        val marker = T0
        val deadline = marker + 120 * MIN
        val d = DosingContext.decide(nowMs = deadline, markerTs = marker, pinnedFor = marker, deadlineTs = deadline)
        assertEquals(DosingContext.Profile.CORRECTION, d.profile)
        assertEquals(DosingContext.Reason.POWER_EXPIRED, d.reason)
        // Identitaet und Frist bleiben als BERICHT stehen - die Rechte nicht.
        assertEquals(marker, d.authorizationId)
        val davor = DosingContext.decide(nowMs = deadline - 1, markerTs = marker, pinnedFor = marker, deadlineTs = deadline)
        assertEquals(DosingContext.Profile.MEAL, davor.profile)
    }

    /** Pflichtfall "Neustart / ungueltige Daten -> keine neuen Rechte": ein
     *  beim Warmstart bloss VORGEFUNDENER Marker traegt keinen passenden
     *  Pin - kein rueckwirkendes MEAL. */
    @Test
    fun `ein vorgefundener Marker ohne Pin bleibt CORRECTION`() {
        val d = DosingContext.decide(
            nowMs = T0 + 10 * MIN, markerTs = T0,
            pinnedFor = 0L, deadlineTs = 0L,
        )
        assertEquals(DosingContext.Profile.CORRECTION, d.profile)
        assertEquals(DosingContext.Reason.MARKER_NOT_PINNED, d.reason)
    }

    /** Ein Pin einer ANDEREN Markeridentitaet zaehlt nicht - die
     *  Autorisierung gehoert genau einem Druck. */
    @Test
    fun `ein fremder Pin autorisiert nicht`() {
        val d = DosingContext.decide(
            nowMs = T0 + 10 * MIN, markerTs = T0 + 5 * MIN,
            pinnedFor = T0, deadlineTs = T0 + 120 * MIN,
        )
        assertEquals(DosingContext.Profile.CORRECTION, d.profile)
        assertEquals(DosingContext.Reason.MARKER_NOT_PINNED, d.reason)
    }

    /** Ein Pin in der ZUKUNFT autorisiert noch nicht (Rig-Falle "geteilte
     *  Testinstanz traegt markerAt weiter" - ein Zukunfts-Marker darf nie
     *  wirken). */
    @Test
    fun `ein Pin in der Zukunft autorisiert noch nicht`() {
        val marker = T0 + 5 * MIN
        val d = DosingContext.decide(nowMs = T0, markerTs = marker, pinnedFor = marker, deadlineTs = marker + 120 * MIN)
        assertEquals(DosingContext.Profile.CORRECTION, d.profile)
        assertEquals(DosingContext.Reason.POWER_EXPIRED, d.reason)
    }
}

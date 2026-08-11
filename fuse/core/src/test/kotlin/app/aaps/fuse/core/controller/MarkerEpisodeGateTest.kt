package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.MarkerEpisodeGate.Denial
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER DREIZEILIGE VERTRAG, ohne Runner und ohne Ledger.
 *
 *   bestehende Episode + gleicher/neuer Marker      -> unveraendert
 *   keine Episode + frischer, beobachteter, nicht
 *   verbrauchter Druck                              -> eroeffnen, sofort verbrauchen
 *   alter, wiederholter oder nur beim Start
 *   vorgefundener Marker                            -> keine Episode
 */
class MarkerEpisodeGateTest {

    private val now = 1_786_000_000_000L
    private val cap = 240 * 60_000L

    private fun entscheide(
        markerTs: Long = 0L,
        ledgerEpisodeId: Long = 0L,
        lastConsumed: Long = 0L,
        observed: Long = 0L,
        nowMs: Long = now,
    ) = MarkerEpisodeGate.decide(nowMs, markerTs, ledgerEpisodeId, lastConsumed, observed, cap)

    // ---- Zeile 1: eine laufende Episode bleibt ----------------------------

    @Test
    fun `laufende Episode bleibt bei gleichem Marker`() {
        val ts = now - 30 * 60_000L
        val r = entscheide(markerTs = ts, ledgerEpisodeId = ts, lastConsumed = ts, observed = ts)
        assertEquals(ts, r.episodeId)
        assertFalse(r.opened, "kein zweites Eroeffnen")
        assertNull(r.denial)
    }

    /**
     * DER FALL, DER DIE DOPPELFINANZIERUNG VERHINDERT: zuruecknehmen, neu
     * druecken. Der neue Druck ist frisch UND beobachtet UND unverbraucht -
     * er darf trotzdem keine zweite Episode aufmachen, weil die erste noch
     * laeuft. Sonst begaenne der Zaehler bei 0 und dieselbe Mahlzeit waere
     * ein zweites Mal unbezahlt.
     */
    @Test
    fun `neuer Druck waehrend laufender Episode erbt sie`() {
        val alt = now - 30 * 60_000L
        val neu = now - 60_000L
        val r = entscheide(markerTs = neu, ledgerEpisodeId = alt, lastConsumed = alt, observed = neu)
        assertEquals(alt, r.episodeId, "der ALTE Anker")
        assertFalse(r.opened)
    }

    /** Und nach dem Deckel ist es wirklich eine neue Mahlzeit. */
    @Test
    fun `nach dem Deckel eroeffnet ein neuer Druck`() {
        val alt = now - cap - 60_000L
        val neu = now - 60_000L
        val r = entscheide(markerTs = neu, ledgerEpisodeId = alt, lastConsumed = alt, observed = neu)
        assertEquals(neu, r.episodeId)
        assertTrue(r.opened)
    }

    // ---- Zeile 2: eroeffnen ------------------------------------------------

    @Test
    fun `frischer beobachteter Druck eroeffnet`() {
        val ts = now - 60_000L
        val r = entscheide(markerTs = ts, observed = ts)
        assertEquals(ts, r.episodeId)
        assertTrue(r.opened, "der Aufrufer muss jetzt lastConsumedMarkerTs setzen")
        assertNull(r.denial)
    }

    // ---- Zeile 3: keine Episode -------------------------------------------

    @Test
    fun `ohne Marker keine Episode`() {
        assertEquals(Denial.NO_MARKER, entscheide().denial)
    }

    /**
     * DER WARMSTART-FALL, und der Grund, warum die Beobachtung nicht aus dem
     * Marker-Ring kommen darf: nach einem Neustart steht der Druck von vor
     * einer Stunde noch in den Preferences. Er ist frisch genug fuer den
     * Deckel, er ist nicht verbraucht - und er ist trotzdem kein Ereignis
     * dieses Prozesses.
     */
    @Test
    fun `Marker aus einem frueheren Prozess eroeffnet nicht`() {
        val ts = now - 60 * 60_000L
        val r = entscheide(markerTs = ts, observed = 0L)
        assertEquals(0L, r.episodeId)
        assertFalse(r.opened)
        assertEquals(Denial.MARKER_EVENT_NOT_DURABLE, r.denial)
    }

    /** Ein FREMDER beobachteter Druck rettet ihn auch nicht - die Zeitpunkte
     *  muessen derselbe sein, nicht bloss beide vorhanden. */
    @Test
    fun `fremde Beobachtung rettet einen alten Marker nicht`() {
        val alt = now - 60 * 60_000L
        val r = entscheide(markerTs = alt, observed = now - 60_000L)
        assertEquals(Denial.MARKER_EVENT_NOT_DURABLE, r.denial)
    }

    /**
     * DER ANKER: derselbe Druck, ein zweites Mal gesehen. Nach dem Deckel
     * verfaellt die Episode, der Preference-Wert nicht - ohne den
     * verbrauchten Anker saehe er wieder aus wie neu.
     */
    @Test
    fun `verbrauchter Marker eroeffnet kein zweites Mal`() {
        val ts = now - 60_000L
        val r = entscheide(markerTs = ts, lastConsumed = ts, observed = ts)
        assertEquals(0L, r.episodeId)
        assertEquals(Denial.MARKER_ALREADY_CONSUMED, r.denial)
    }

    /** Auch AELTER als der Anker zaehlt als verbraucht - der Anker ist eine
     *  Schranke, keine Gleichheit. Bei einer rueckwaerts springenden Uhr ist
     *  das die fail-closed Richtung. */
    @Test
    fun `aelterer Marker als der Anker eroeffnet nicht`() {
        val r = entscheide(markerTs = now - 120 * 60_000L, lastConsumed = now - 60_000L, observed = now - 120 * 60_000L)
        assertEquals(Denial.MARKER_ALREADY_CONSUMED, r.denial)
    }

    @Test
    fun `Marker aelter als der Deckel eroeffnet nicht`() {
        val ts = now - cap - 60_000L
        assertEquals(Denial.MARKER_STALE, entscheide(markerTs = ts, observed = ts).denial)
    }

    @Test
    fun `Marker aus der Zukunft eroeffnet nicht`() {
        val ts = now + 5 * 60_000L
        assertEquals(Denial.MARKER_IN_FUTURE, entscheide(markerTs = ts, observed = ts).denial)
    }

    /** Die Toleranz selbst ist noch kein Uhrensprung - sonst waere jede
     *  Millisekunde Versatz zwischen Knopf und Zyklus ein Denial. */
    @Test
    fun `kleiner Vorlauf innerhalb der Toleranz eroeffnet`() {
        val ts = now + MarkerEpisodeGate.FUTURE_TOLERANCE_MS
        assertTrue(entscheide(markerTs = ts, observed = ts).opened)
    }

    /**
     * DIE REIHENFOLGE DER GRUENDE ist selbst eine Zusicherung: ein Marker,
     * der ZUGLEICH verbraucht und unbeobachtet ist, muss "verbraucht"
     * melden. Sonst schickt der Tab den Nutzer zum erneuten Druecken, obwohl
     * genau das nichts aendern wuerde.
     */
    @Test
    fun `verbraucht schlaegt unbeobachtet`() {
        val ts = now - 60_000L
        assertEquals(Denial.MARKER_ALREADY_CONSUMED, entscheide(markerTs = ts, lastConsumed = ts, observed = 0L).denial)
    }
}

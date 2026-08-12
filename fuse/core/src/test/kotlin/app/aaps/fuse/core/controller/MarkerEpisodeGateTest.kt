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
        revokedPersisted: Boolean = false,
    ) = MarkerEpisodeGate.decide(nowMs, markerTs, ledgerEpisodeId, lastConsumed, observed, cap, revokedPersisted)

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

    /**
     * AELTER ALS DER ANKER ist ebenfalls gesperrt - aber unter EIGENEM Namen.
     *
     * Die Sperre ist dieselbe, die Handlungsanweisung nicht: gegen
     * `ALREADY_CONSUMED` hilft ein neuer Druck, gegen einen Uhrenruecksprung
     * nicht. Beides gleich zu benennen hiesse, den Nutzer in eine
     * wirkungslose Handlung zu schicken.
     */
    @Test
    fun `Marker vor dem Anker meldet einen Uhrenruecksprung`() {
        val r = entscheide(markerTs = now - 120 * 60_000L, lastConsumed = now - 60_000L, observed = now - 120 * 60_000L)
        assertEquals(Denial.MARKER_CLOCK_ROLLBACK, r.denial)
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
    // ---- DER WIDERRUFSVERTRAG ---------------------------------------------

    /**
     * PFLICHTTEST 1: die Ruecknahme stoppt den Kredit SOFORT, Episode und
     * Bezahlung bleiben.
     *
     * Ohne das hiesse "laufende Episode bleibt unabhaengig vom Marker"
     * woertlich: der Nutzer nimmt zurueck, und der Zusatzkredit dosiert
     * weiter. Die Episode zu loeschen waere die andere falsche Richtung -
     * dann begaenne die naechste Armierung mit Zaehler 0.
     */
    @Test
    fun `Ruecknahme widerruft den Kredit und laesst die Episode stehen`() {
        val ep = now - 30 * 60_000L
        val r = entscheide(markerTs = 0L, ledgerEpisodeId = ep, lastConsumed = ep, observed = 0L)

        assertEquals(ep, r.episodeId, "die Episode bleibt")
        assertTrue(r.creditRevoked, "der Kredit ist weg")
        assertTrue(r.revocationChanged, "und der Stand muss festgeschrieben werden")
    }

    /**
     * PFLICHTTEST 2: nach einem Neustart bleibt er gesperrt.
     *
     * Zwei Wege halten ihn zu, und beide sind hier geprueft: der
     * persistierte Stand UND die zustandslose Regel (in den Preferences steht
     * 0, weil nur die Ruecknahme das schreibt).
     */
    @Test
    fun `Neustart nach Ruecknahme laesst den Kredit gesperrt`() {
        val ep = now - 30 * 60_000L
        // Nach einem Neustart ist die Beobachtung leer und der Widerruf steht
        // in der geladenen Datei - das allein darf ihn nicht wieder freigeben.
        val r = entscheide(
            markerTs = 0L, ledgerEpisodeId = ep, lastConsumed = ep,
            observed = 0L, revokedPersisted = true,
        )
        assertTrue(r.creditRevoked)
        assertFalse(r.revocationChanged, "unveraendert - kein erneutes Festschreiben noetig")
    }

    /** Und auch dann, wenn der Markerzeitpunkt spaeter wieder auftaucht, ohne
     *  dass jemand gedrueckt hat - dafuer ist der persistierte Stand da. */
    @Test
    fun `ein vorgefundener Markerzeitpunkt gibt den Kredit nicht frei`() {
        val ep = now - 30 * 60_000L
        val r = entscheide(
            markerTs = ep, ledgerEpisodeId = ep, lastConsumed = ep,
            observed = 0L, revokedPersisted = true,
        )
        assertTrue(r.creditRevoked, "ohne Willenserklaerung bleibt es gesperrt")
    }

    /**
     * PFLICHTTEST 3: bewusstes erneutes Armen im Deckel reaktiviert DIESELBE
     * Episode - ohne neues Budget.
     */
    @Test
    fun `erneutes Armen gibt den Kredit frei ohne neue Episode`() {
        val ep = now - 30 * 60_000L
        val zweiter = now - 60_000L
        val r = entscheide(
            markerTs = zweiter, ledgerEpisodeId = ep, lastConsumed = ep,
            observed = zweiter, revokedPersisted = true,
        )
        assertEquals(ep, r.episodeId, "derselbe Anker, kein neues Budget")
        assertFalse(r.opened, "keine neue Episode")
        assertFalse(r.creditRevoked, "der Kredit ist wieder frei")
        assertTrue(r.revocationChanged, "die Freigabe muss festgeschrieben werden")
    }

    /**
     * PFLICHTTEST 4: der NATUERLICHE Ablauf nach 90 Minuten widerruft nicht.
     *
     * Der Unterschied steht im Zeitpunkt selbst: bei Ablauf bleibt er stehen
     * und nur `active` wird falsch; bei Ruecknahme wird er genullt. Genau
     * dafuer wurde die Episode vom 90-Minuten-Fenster geloest - der gemessene
     * Lauf vom 11.08. war nach 205 Minuten noch aktiv.
     */
    @Test
    fun `natuerlicher Ablauf widerruft den Kredit nicht`() {
        val ep = now - 120 * 60_000L
        val r = entscheide(markerTs = ep, ledgerEpisodeId = ep, lastConsumed = ep, observed = ep)

        assertEquals(ep, r.episodeId)
        assertFalse(r.creditRevoked, "abgelaufen ist nicht zurueckgenommen")
    }

    /** Auch ohne Beobachtung - ein Neustart mitten in der Mahlzeit darf den
     *  Kredit einer nicht zurueckgenommenen Episode nicht kassieren. */
    @Test
    fun `Neustart mitten in der Episode behaelt den Kredit`() {
        val ep = now - 120 * 60_000L
        val r = entscheide(markerTs = ep, ledgerEpisodeId = ep, lastConsumed = ep, observed = 0L)
        assertFalse(r.creditRevoked)
    }

    /** Eine FRISCHE Episode ist nie widerrufen - und ein alter true-Stand
     *  muss dabei ausdruecklich fallen, sonst startete sie gesperrt. */
    @Test
    fun `eine neue Episode raeumt einen alten Widerruf ab`() {
        val ts = now - 60_000L
        val r = entscheide(markerTs = ts, observed = ts, revokedPersisted = true)
        assertTrue(r.opened)
        assertFalse(r.creditRevoked)
        assertTrue(r.revocationChanged, "der alte Stand muss ueberschrieben werden")
    }
}

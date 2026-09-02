package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.MarkerReauthorization.Authorization
import app.aaps.fuse.core.controller.MarkerReauthorization.Revocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER VERTRAG "ABBRUCH -> NEUER MARKER = NEUE VOLLE HUELLE".
 *
 * Alle Werte synthetisch. Geprueft wird ausdruecklich AUCH, was NICHT
 * passieren darf: doppelte Huellen aus wiederholten Ereignissen, aus
 * einem App-Neustart oder aus einem Zeitsprung.
 */
class MarkerReauthorizationTest {

    private val t0 = 1_000_000L

    @Test
    fun `Abbruch dann neuer Druck eroeffnet genau eine neue Huelle`() {
        val alt = Authorization(id = "auth-1", markerTs = t0)
        val marke = MarkerReauthorization.widerrufe(alt, t0 + 60_000L)!!
        assertTrue(marke.offen)
        assertEquals("auth-1", marke.authId)

        val neu = MarkerReauthorization.autorisiere(2L, t0 + 120_000L, marke)
        assertEquals("auth-2", neu.auth.id)
        assertTrue(neu.auth.nachWiderruf)
        // Die Zuordnung ist persistent, nicht ein Loeschen.
        assertEquals("auth-2", neu.revocation!!.consumedByAuthId)
        assertFalse(neu.revocation!!.offen)

        assertTrue(MarkerReauthorization.neueHuelle(neu.auth, fundamentBewaffnetFuer = null))
    }

    @Test
    fun `dieselbe Autorisierung ein zweites Mal verarbeitet oeffnet nichts`() {
        val neu = MarkerReauthorization.autorisiere(
            2L, t0, Revocation("auth-1", t0 - 60_000L, t0 - 30_000L),
        )
        // Erste Verarbeitung bewaffnet.
        assertTrue(MarkerReauthorization.neueHuelle(neu.auth, null))
        // Zweite - erneuter Zyklus, doppelter Rueckruf, App-Neustart -
        // findet dieselbe Kennung vor.
        assertFalse(MarkerReauthorization.neueHuelle(neu.auth, neu.auth.id)) {
            "eine zweite volle Huelle aus demselben Druck waere eine zusaetzliche Dosis"
        }
    }

    @Test
    fun `ein Druck ohne vorherigen Abbruch eroeffnet keine neue Huelle`() {
        val neu = MarkerReauthorization.autorisiere(2L, t0, offeneMarke = null)
        assertFalse(neu.auth.nachWiderruf)
        assertNull(neu.revocation)
        assertFalse(MarkerReauthorization.neueHuelle(neu.auth, null)) {
            "hier gilt weiter die Fensterregel - der Normalfall bleibt unveraendert"
        }
    }

    @Test
    fun `eine bereits verbrauchte Marke eroeffnet keine zweite Huelle`() {
        val verbraucht = Revocation("auth-1", t0, t0, consumedByAuthId = "auth-2")
        val neu = MarkerReauthorization.autorisiere(3L, t0 + 60_000L, verbraucht)
        assertFalse(neu.auth.nachWiderruf) { "die Marke war schon zugeordnet" }
        assertNull(neu.revocation)
        assertFalse(MarkerReauthorization.neueHuelle(neu.auth, null))
    }

    @Test
    fun `ein Abbruch ohne laufende Autorisierung erzeugt keine Marke`() {
        assertNull(MarkerReauthorization.widerrufe(null, t0)) {
            "sonst koennte ein Abbruch ins Leere spaeter eine Huelle eroeffnen"
        }
    }

    /**
     * ZEITSPRUNG. Die Kennung ist eine Folge, kein Zeitstempel - eine
     * zurueckspringende Uhr aendert daran nichts.
     */
    @Test
    fun `eine zurueckspringende Uhr aendert die Kennungsfolge nicht`() {
        val alt = Authorization(id = "auth-7", markerTs = t0)
        val marke = MarkerReauthorization.widerrufe(alt, t0 + 1_000L)!!
        // Der neue Druck traegt einen FRUEHEREN Zeitstempel als der alte.
        val neu = MarkerReauthorization.autorisiere(8L, t0 - 500_000L, marke)
        assertEquals("auth-8", neu.auth.id)
        assertNotEquals(neu.auth.id, alt.id)
        assertTrue(MarkerReauthorization.neueHuelle(neu.auth, null)) {
            "die Folge entscheidet, nicht die Uhr"
        }
    }

    @Test
    fun `ein wiederholtes Bedienereignis wird erkannt`() {
        assertTrue(MarkerReauthorization.schonVerarbeitet("ev-1", "ev-1"))
        assertFalse(MarkerReauthorization.schonVerarbeitet("ev-2", "ev-1"))
        assertFalse(MarkerReauthorization.schonVerarbeitet(null, "ev-1")) {
            "ohne Kennung gibt es keine Wiederholungserkennung - dann gilt der Toggle"
        }
    }
}

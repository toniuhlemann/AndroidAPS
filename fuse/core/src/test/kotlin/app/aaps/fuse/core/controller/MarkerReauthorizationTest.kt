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

    /**
     * DIE FOLGE, DIE EIN LETZTVERGLEICH DURCHGELASSEN HAETTE:
     * Start E1 -> Abbruch E2 -> verspaeteter Rueckruf E1. E1 ist nicht E2,
     * also haette ein Vergleich mit dem zuletzt verarbeiteten Ereignis
     * erneut umgeschaltet - nach dem Abbruch moeglicherweise mit einer
     * weiteren vollen Huelle.
     */
    @Test
    fun `ein verspaeteter Rueckruf nach einem neueren Ereignis wirkt nicht`() {
        val e1 = MarkerReauthorization.ordnungVon(MarkerReauthorization.ereignisKennung(1L))!!
        val e2 = MarkerReauthorization.ordnungVon(MarkerReauthorization.ereignisKennung(2L))!!
        assertTrue(MarkerReauthorization.ereignisWirkt(e1, zuletztAngewandt = 0L))
        assertTrue(MarkerReauthorization.ereignisWirkt(e2, zuletztAngewandt = e1))
        assertFalse(MarkerReauthorization.ereignisWirkt(e1, zuletztAngewandt = e2)) {
            "der verspaetete E1 darf nach E2 nicht mehr umschalten"
        }
    }

    @Test
    fun `dasselbe Ereignis zweimal wirkt nur einmal`() {
        assertTrue(MarkerReauthorization.ereignisWirkt(1L, 0L))
        assertFalse(MarkerReauthorization.ereignisWirkt(1L, 1L))
    }

    /**
     * DER ABGLEICH ZWISCHEN LEDGER UND PREFERENCES.
     *
     * Endet der Prozess nach dem Widerruf im Ledger, aber vor dem Leeren
     * der Preference, steht dort ein Marker, den es nicht mehr gibt.
     * Beide Leser - `mealMarkerArmedTs()` und der Runner - rufen diese
     * Funktion, damit sie nicht zu verschiedenen Antworten kommen.
     */
    @Test
    fun `ein widerrufener Marker bleibt widerrufen, auch wenn die Preference ihn noch traegt`() {
        val marke = Revocation(authId = "auth-1", markerTs = t0, atTs = t0 + 1_000L)
        assertTrue(MarkerReauthorization.widerrufen(t0, auth = null, revocation = marke)) {
            "sonst waere der Widerruf folgenlos"
        }
    }

    @Test
    fun `eine neue Autorisierung fuer denselben Zeitpunkt hebt den Widerruf auf`() {
        val marke = Revocation("auth-1", t0, t0 + 1_000L, consumedByAuthId = "auth-2")
        val neu = Authorization("auth-2", markerTs = t0)
        assertFalse(MarkerReauthorization.widerrufen(t0, neu, marke)) {
            "nach dem Abbruch wurde neu autorisiert - der Marker gilt wieder"
        }
    }

    @Test
    fun `ein anderer Marker ist von einem fremden Widerruf nicht betroffen`() {
        val marke = Revocation("auth-1", markerTs = t0, atTs = t0)
        assertFalse(MarkerReauthorization.widerrufen(t0 + 999_999L, null, marke))
        assertFalse(MarkerReauthorization.widerrufen(t0, null, revocation = null))
        assertFalse(MarkerReauthorization.widerrufen(0L, null, marke))
    }

    @Test
    fun `ohne Kennung bleibt das bisherige Umschalten`() {
        assertTrue(MarkerReauthorization.ereignisWirkt(null, 42L)) {
            "ein Aufrufer ohne Kennung soll nicht stillschweigend wirkungslos werden"
        }
        assertNull(MarkerReauthorization.ordnungVon(null))
        assertNull(MarkerReauthorization.ordnungVon("kaputt"))
    }
}

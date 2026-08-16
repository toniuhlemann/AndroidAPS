package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkerScopeTest {

    private val m = 60_000L
    private val t0 = 1_786_000_000_000L

    @Test
    fun `sonderrechte gelten ab druck bis 45 min`() {
        assertTrue(MarkerScope.boostActive(t0, t0, 0L))
        assertTrue(MarkerScope.boostActive(t0, t0 + 44 * m, 0L))
        assertTrue(MarkerScope.boostActive(t0, t0 + 45 * m, 0L))
        assertFalse(MarkerScope.boostActive(t0, t0 + 45 * m + 1, 0L))
        // Der Fruehstuecks-Fall: Sturz ~75 min nach Druck lag im alten
        // 90-min-Fenster - jetzt nicht mehr.
        assertFalse(MarkerScope.boostActive(t0, t0 + 75 * m, 0L))
    }

    @Test
    fun `nachhaltige wende beendet die sonderrechte sofort`() {
        val turn = t0 + 20 * m
        assertTrue(MarkerScope.boostActive(t0, t0 + 19 * m, 0L))
        assertFalse(MarkerScope.boostActive(t0, turn, turn))
        assertFalse(MarkerScope.boostActive(t0, t0 + 21 * m, turn))
    }

    @Test
    fun `kein marker oder uhrsprung heisst keine sonderrechte`() {
        assertFalse(MarkerScope.boostActive(0L, t0, 0L))
        assertFalse(MarkerScope.boostActive(t0 + m, t0, 0L)) // Druck "in der Zukunft"
    }

    /**
     * DER UHREN-RUECKSPRUNG - Auditbefund P1-5, Invariante 16.
     *
     * Hier stand `turnLatchedTs in 1..nowTs`. Springt die Systemuhr zurueck -
     * eine NTP-Korrektur um wenige Minuten genuegt -, liegt der gelatchte
     * Zeitpunkt in der "Zukunft", die Bedingung wird falsch, und die
     * Sonderrechte LEBEN WIEDER AUF: Rebound- und Nacht-Totband waeren erneut
     * entwaffnet, ausgerechnet in der fallenden Phase nach dem Gipfel.
     *
     * Getestet war bisher nur der VORWAERTS-Skew (der Test darueber). Diese
     * Richtung fehlte, und sie ist die gefaehrliche: vorwaerts endet der Boost
     * frueher, rueckwaerts kommt er zurueck.
     */
    @Test
    fun `ein Uhren-Ruecksprung laesst die Sonderrechte nicht wieder aufleben`() {
        val turn = t0 + 20 * m

        // Vorbedingung: nach der Wende sind sie aus.
        assertFalse(MarkerScope.boostActive(t0, t0 + 21 * m, turn))

        // Die Uhr springt hinter die Wende zurueck - der Latch liegt jetzt
        // "in der Zukunft". Er muss trotzdem halten.
        assertFalse(
            MarkerScope.boostActive(t0, t0 + 15 * m, turn),
            "Ruecksprung um 6 min: eine stattgefundene Wende findet nicht dadurch nicht statt",
        )
        assertFalse(
            MarkerScope.boostActive(t0, t0 + 1 * m, turn),
            "auch ein grosser Ruecksprung bis kurz nach den Druck darf sie nicht zurueckholen",
        )
        // Und exakt auf dem Latch-Zeitpunkt.
        assertFalse(MarkerScope.boostActive(t0, turn, turn))
    }

    /**
     * DIE GEGENPROBE, damit der Test oben nicht bloss "alles false" behauptet:
     * ohne Latch gelten die Sonderrechte in genau denselben Lagen weiter.
     * Ohne diese Zeilen waere die Behauptung wertlos - sie waere auch dann
     * gruen, wenn `boostActive` immer false liefert.
     */
    @Test
    fun `ohne Wende gelten die Sonderrechte in denselben Lagen weiter`() {
        assertTrue(MarkerScope.boostActive(t0, t0 + 15 * m, 0L))
        assertTrue(MarkerScope.boostActive(t0, t0 + 1 * m, 0L))
        assertTrue(MarkerScope.boostActive(t0, t0 + 20 * m, 0L))
    }

    @Test
    fun `prior-hub am horizont - entzirkularisierung`() {
        // 0,7 mg/dl/min, tau 60, H 30: 0,7*60*(1-e^-0,5) ~ 16,5 mg/dl.
        assertEquals(16.5, MarkerScope.priorLiftAtHorizonMgdl(0.7, 60.0, 30), 0.1)
        assertEquals(0.0, MarkerScope.priorLiftAtHorizonMgdl(0.0, 60.0, 30), 0.0)
        assertEquals(0.0, MarkerScope.priorLiftAtHorizonMgdl(0.7, 60.0, 0), 0.0)
        // Monotonie: laengerer Horizont, groesserer Hub, gedeckelt bei prior*tau.
        val h30 = MarkerScope.priorLiftAtHorizonMgdl(0.7, 60.0, 30)
        val h120 = MarkerScope.priorLiftAtHorizonMgdl(0.7, 60.0, 120)
        assertTrue(h120 > h30 && h120 < 0.7 * 60.0)
    }
}

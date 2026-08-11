package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Die Kette Knopfdruck -> Symbol im AAPS-Uebersichtsgraphen.
 *
 * Sie laeuft ueber vier Module und war bis 11.08. an KEINER Stelle getestet.
 * Beim Umbau auf einen einzigen Marker ist sie um ein Haar unbemerkt
 * zerbrochen - aufgefallen ist es nur, weil Toni nach dem Symbol gefragt hat.
 * Ein Test, der zu spaet kommt, ist trotzdem der richtige Ort fuer diese Regel.
 */
class MarkerTimelineTest {

    private val t0 = 1_786_000_000_000L
    private fun min(n: Int) = t0 + n * 60_000L

    @Test
    fun `ein Druck ist sichtbar`() {
        val ring = ArrayDeque<Long>()
        MarkerTimeline.add(ring, min(10))
        assertEquals(listOf(min(10)), MarkerTimeline.visible(ring, min(10), t0, min(60)))
    }

    /**
     * Der aktuelle Marker steht in BEIDEN Quellen - Ring und Preference. Ohne
     * Entdopplung zeichnete AAPS zwei Symbole auf dieselbe Minute, und das
     * waere ein Artefakt der Datenhaltung, keine Information.
     */
    @Test
    fun `derselbe Zeitpunkt aus Ring und Preference ergibt EIN Symbol`() {
        val ring = ArrayDeque<Long>()
        MarkerTimeline.add(ring, min(10))
        assertEquals(1, MarkerTimeline.visible(ring, min(10), t0, min(60)).size)
    }

    /**
     * DER FALL, DER DEN NEUSTART UEBERLEBT. Der Ring lebt im Prozess und ist
     * nach einem Flash leer; `armedTs` ueberlebt in der Preference. Ohne die
     * Vereinigung beider Quellen ueberlebte KEIN einziger Marker.
     */
    @Test
    fun `nach einem Neustart traegt die Preference den letzten Marker allein`() {
        assertEquals(listOf(min(10)), MarkerTimeline.visible(emptyList(), min(10), t0, min(60)))
    }

    /**
     * EIN ZURUECKGENOMMENER MARKER BLEIBT SICHTBAR - Absicht, keine Panne.
     *
     * Der zweite Druck setzt `armedTs` auf 0, entfernt den Druck aber nicht aus
     * dem Ring. Der Graph ist ein Protokoll dessen, was passiert ist: wer um
     * 18:55 gedrueckt hat, hat um 18:55 gedrueckt. Wuerde man die Spur loeschen,
     * waere eine Ruecknahme im Nachhinein nicht mehr von "nie gedrueckt" zu
     * unterscheiden - die schlechtere Fehlerrichtung.
     */
    @Test
    fun `eine Ruecknahme loescht die Spur nicht`() {
        val ring = ArrayDeque<Long>()
        MarkerTimeline.add(ring, min(10))
        assertEquals(listOf(min(10)), MarkerTimeline.visible(ring, armedTs = 0L, t0, min(60)))
    }

    /** 0 heisst "kein Marker" und ist kein Zeitpunkt am 1.1.1970. */
    @Test
    fun `null und negative Zeitpunkte zeichnen nichts`() {
        assertTrue(MarkerTimeline.visible(listOf(0L, -1L), 0L, 0L, min(60)).isEmpty())
    }

    /** Das Fenster des Graphen schneidet beidseitig - sonst zeichnet AAPS
     *  Symbole ausserhalb der sichtbaren Achse. */
    @Test
    fun `nur Druecke im Fenster werden gezeichnet`() {
        val ring = ArrayDeque<Long>()
        listOf(min(-30), min(10), min(120)).forEach { MarkerTimeline.add(ring, it) }
        assertEquals(listOf(min(10)), MarkerTimeline.visible(ring, 0L, t0, min(60)))
    }

    /** Aufsteigend, unabhaengig von der Einfuegereihenfolge - der Zeichner
     *  verlaesst sich darauf. */
    @Test
    fun `die Zeitpunkte kommen aufsteigend`() {
        val ring = ArrayDeque<Long>()
        listOf(min(30), min(5), min(20)).forEach { MarkerTimeline.add(ring, it) }
        assertEquals(listOf(min(5), min(20), min(30)), MarkerTimeline.visible(ring, 0L, t0, min(60)))
    }

    // ---- Der Ring selbst --------------------------------------------------

    /** Der Warmstart aus dem Trail und der Knopfdruck benutzen DIESELBE
     *  Einfuegeregel - zwei Fassungen von "anhaengen und vorne abschneiden"
     *  laufen sonst auseinander. Doppelte kommen aus dem Trail zwangslaeufig:
     *  derselbe `markerArmedTs` steht in jedem Zyklus, in dem er galt. */
    @Test
    fun `derselbe Zeitpunkt landet nur einmal im Ring`() {
        val ring = ArrayDeque<Long>()
        repeat(5) { MarkerTimeline.add(ring, min(10)) }
        assertEquals(1, ring.size)
    }

    @Test
    fun `der Ring wirft die aeltesten Druecke ab`() {
        val ring = ArrayDeque<Long>()
        for (i in 1..(MarkerTimeline.RING_CAPACITY + 5)) MarkerTimeline.add(ring, min(i))
        assertEquals(MarkerTimeline.RING_CAPACITY, ring.size)
        assertEquals(min(MarkerTimeline.RING_CAPACITY + 5), ring.last())
        assertEquals(min(6), ring.first())
    }

    /**
     * DER ECHTE ABLAUF VOM 10.08., als Test.
     *
     * Marker um 18:55 gedrueckt, danach vier Flashes an einem Abend. Vor dem
     * Trail-Warmstart blieb nach jedem Flash genau ein Symbol uebrig; mit ihm
     * sind alle Druecke der letzten 24 h wieder da.
     */
    @Test
    fun `der Warmstart stellt die Druecke mehrerer Flashes wieder her`() {
        // Nach dem Flash: Ring leer, nur der letzte Marker in der Preference.
        val nachFlash = ArrayDeque<Long>()
        assertEquals(listOf(min(200)), MarkerTimeline.visible(nachFlash, min(200), t0, min(300)))

        // Warmstart liest aus dem Trail - jeder Zeitpunkt steht dort vielfach,
        // einmal je Zyklus, in dem er galt.
        val ausTrail = listOf(min(20), min(20), min(20), min(120), min(120), min(200))
        for (m in ausTrail) MarkerTimeline.add(nachFlash, m)

        assertEquals(
            listOf(min(20), min(120), min(200)),
            MarkerTimeline.visible(nachFlash, min(200), t0, min(300))
        )
    }

    // ---- Die Ruecknahme (11.08.) -----------------------------------------

    /**
     * EIN FOLGENLOSER DRUCK VERSCHWINDET AUS DEM GRAPHEN.
     *
     * Frueher blieb er stehen, mit ausdruecklicher Begruendung ("der Graph ist
     * ein Protokoll"). Die trug, solange der Knopf nur die Evidenzschwelle
     * senkte. Seit er Insulin autorisiert, lautet die Frage am Graphen "wann
     * kam hier Mahlzeiteninsulin?" - und eine Linie ohne Insulin beantwortet
     * sie falsch.
     */
    @Test
    fun `ein folgenloser Druck wird zurueckgenommen`() {
        val ring = ArrayDeque<Long>()
        MarkerTimeline.add(ring, 1_000L)
        assertTrue(MarkerTimeline.retract(ring, 1_000L, deliveredU = 0.0))
        assertTrue(ring.isEmpty())
    }

    /**
     * MIT ABGEGEBENEM INSULIN BLEIBT ER. Die Ruecknahme stoppt kuenftige
     * Abgaben, sie macht vergangene nicht ungeschehen - dieses Insulin wirkt
     * weiter und muss am Graphen zuzuordnen sein. Genau das sagt auch der
     * Dialog zu.
     */
    @Test
    fun `ein Druck mit abgegebenem Insulin bleibt stehen`() {
        val ring = ArrayDeque<Long>()
        MarkerTimeline.add(ring, 1_000L)
        assertTrue(!MarkerTimeline.retract(ring, 1_000L, deliveredU = 0.05))
        assertEquals(listOf(1_000L), ring.toList())
    }

    /** Andere Drucke bleiben unberuehrt - die Ruecknahme gilt EINEM. */
    @Test
    fun `die Ruecknahme trifft nur den eigenen Druck`() {
        val ring = ArrayDeque<Long>()
        MarkerTimeline.add(ring, 1_000L)
        MarkerTimeline.add(ring, 2_000L)
        MarkerTimeline.retract(ring, 2_000L, deliveredU = 0.0)
        assertEquals(listOf(1_000L), ring.toList())
    }

    /** Ohne Marker gibt es nichts zurueckzunehmen. */
    @Test
    fun `ohne Zeitpunkt passiert nichts`() {
        val ring = ArrayDeque<Long>()
        MarkerTimeline.add(ring, 1_000L)
        assertTrue(!MarkerTimeline.retract(ring, 0L, deliveredU = 0.0))
        assertEquals(listOf(1_000L), ring.toList())
    }
}

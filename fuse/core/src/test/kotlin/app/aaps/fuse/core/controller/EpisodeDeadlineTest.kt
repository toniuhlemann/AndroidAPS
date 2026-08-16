package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Die Zusicherungen der Deckel-Verlaengerung. Sie darf genau eines tun -
 * eine laufende Episode ueber eine ERKLAERTE zweite Mahlzeit tragen - und
 * sonst nichts.
 */
class EpisodeDeadlineTest {

    private val m = 60_000L
    private val basis = 360L * m          // der harte Deckel: 6 h
    private val start = 1_786_000_000_000L

    /**
     * DER ANLASSFALL VOM 16.08. Fruehstuecks-Marker 09:33 eroeffnet die
     * Episode, der Marker fuer die zweite Mahlzeit faellt 305 min spaeter.
     * Ohne Verlaengerung lief der Topf 55 min nach diesem Druck ab - mitten
     * in der Mahlzeit, kurz vor der Staerkewelle.
     */
    @Test
    fun `ein frischer Marker traegt die Episode ueber den Basisdeckel hinaus`() {
        val zweiterDruck = start + 305 * m
        val deckel = EpisodeDeadline.effectiveCapMs(basis, start, zweiterDruck)

        // 305 + 180 = 485 min statt 360.
        assertEquals(485 * m, deckel)
        assertTrue(deckel > basis, "der Deckel muss wachsen, sonst aendert sich nichts")

        // Der kritische Zeitpunkt: 55 min nach dem Druck war frueher Schluss.
        val alterBeimAlten = 360 * m
        assertTrue(alterBeimAlten < deckel, "genau hier lief die Episode vorher ab")
    }

    /** OHNE Marker bleibt alles beim Alten - der Notaus ist unangetastet. */
    @Test
    fun `ohne Marker gilt der Basisdeckel`() {
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, start, 0L))
    }

    /**
     * EIN MARKER VOR DEM EPISODENBEGINN verlaengert nichts - er gehoert zu
     * einer frueheren Lage. Ohne diese Regel koennte ein alter Druck eine
     * ganz neue Episode aufblaehen.
     */
    @Test
    fun `ein Marker vor dem Episodenbeginn verlaengert nicht`() {
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, start, start - 10 * m))
    }

    /**
     * NIE VERKUERZEN. Ein Marker direkt zum Episodenbeginn ergaebe rechnerisch
     * 180 min - das waere die HAELFTE des Basisdeckels. Der Basiswert ist die
     * Untergrenze, nicht die Alternative.
     */
    @Test
    fun `der Basisdeckel ist eine Untergrenze`() {
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, start, start))
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, start, start + 60 * m))
        // Erst ab Druck bei 180 min waechst er ueberhaupt.
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, start, start + 180 * m))
        assertTrue(EpisodeDeadline.effectiveCapMs(basis, start, start + 181 * m) > basis)
    }

    /**
     * SIE FOLGT DEM JUENGSTEN DRUCK - solange er innerhalb des Basisdeckels
     * liegt. Ein spaeterer Druck traegt die Episode weiter als ein frueherer.
     */
    @Test
    fun `sie folgt dem juengsten Druck innerhalb des Basisdeckels`() {
        val frueh = EpisodeDeadline.effectiveCapMs(basis, start, start + 200 * m)
        val spaet = EpisodeDeadline.effectiveCapMs(basis, start, start + 305 * m)
        assertTrue(spaet > frueh, "der spaetere Druck muss weiter tragen: $frueh vs $spaet")
        assertEquals(380 * m, frueh)
        assertEquals(485 * m, spaet)
    }

    /**
     * DIE GRENZE, DIE MEINE ERSTE FASSUNG UEBERSAH - drei bestehende Tests in
     * TransportWiringTest haben sie gefangen.
     *
     * Ein Druck NACH dem Basisdeckel eroeffnet eine neue Episode; er darf die
     * alte nicht verlaengern. Sonst verlaengert ausgerechnet der Druck, der
     * neu anfangen will, den Vorgaenger - eine Episode koennte nie enden,
     * solange jemand drueckt, und "der 360-Deckel ist damit hart" (Option A,
     * Toni 15.08.) waere aufgehoben.
     */
    @Test
    fun `ein Druck nach dem Basisdeckel verlaengert nicht sondern eroeffnet`() {
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, start, start + 360 * m))
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, start, start + 371 * m))
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, start, start + 600 * m))
        // Direkt davor gilt sie noch - die Grenze liegt wirklich am Deckel.
        assertTrue(EpisodeDeadline.effectiveCapMs(basis, start, start + 359 * m) > basis)
    }

    /**
     * DIE GESAMTDAUER IST DAMIT BEGRENZT: Basis + Verlaengerung, nicht
     * beliebig fortschreibbar. Der spaetestmoegliche verlaengernde Druck liegt
     * knapp vor dem Basisdeckel.
     */
    @Test
    fun `die Episode kann nicht unbegrenzt wachsen`() {
        val maximal = EpisodeDeadline.effectiveCapMs(basis, start, start + 359 * m)
        assertEquals((359 + 180) * m, maximal)
        assertTrue(maximal <= basis + EpisodeDeadline.MARKER_EXTENSION_MIN * m)
    }

    /** Abgeschaltet (0 min) heisst: Basisdeckel, keine Sonderbehandlung. */
    @Test
    fun `ohne Verlaengerungsfenster bleibt der Basisdeckel`() {
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, start, start + 305 * m, extensionMin = 0))
    }

    /** Unsinnige Eingaben duerfen den Deckel nicht vergroessern. */
    @Test
    fun `kaputte Eingaben aendern nichts`() {
        assertEquals(basis, EpisodeDeadline.effectiveCapMs(basis, 0L, start + 305 * m))
        assertEquals(0L, EpisodeDeadline.effectiveCapMs(0L, start, start + 305 * m))
        assertEquals(-1L, EpisodeDeadline.effectiveCapMs(-1L, start, start + 305 * m))
    }
}

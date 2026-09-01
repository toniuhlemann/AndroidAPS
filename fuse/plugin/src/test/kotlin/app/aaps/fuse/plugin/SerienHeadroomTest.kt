package app.aaps.fuse.plugin

import app.aaps.fuse.plugin.ledger.EpisodeBudgets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * DER SERIEN-HEADROOM (Variante 2) - und die Vereinigungsmenge, an der
 * er zuerst falsch war.
 *
 * `correctionDeliveries` wird beim PUBLIZIEREN angelegt, nicht erst bei
 * der Pumpenbestaetigung. Eine publizierte, noch offene Dosis steht damit
 * GLEICHZEITIG in der Liste und in der offenen Transportmenge. Ein
 * pauschaler skalarer Abzug zieht sie zweimal ab: bei Deckel 1,00 und
 * einer offenen Korrektur von 0,30 blieben 0,40 statt 0,70. Die Richtung
 * ist konservativ - aber sie verfaelscht den Variantenvergleich und
 * schliesst den Kanal zu frueh.
 *
 * Jede Proposal-Menge darf den Deckel zu jedem Zeitpunkt EXAKT EINMAL
 * belasten. Das ist die Eigenschaft, die diese Tests festhalten.
 */
class SerienHeadroomTest {

    private val t0 = 1_700_000_000_000L
    private val deckel = 1.0

    private fun gebucht(u: Double, id: String?, minutenHer: Double = 1.0) =
        EpisodeBudgets.CorrectionDelivery(t0 - (minutenHer * 60_000).toLong(), u, id)

    private fun offen(u: Double, id: String) =
        TransportDose(id, u, t0 - 60_000L, t0)

    private fun rest(
        gebuchtes: List<EpisodeBudgets.CorrectionDelivery>,
        transport: List<TransportDose>,
    ) = FuseCycleRunner.serienHeadroom(
        capU = deckel, fensterMin = 30, gebucht = gebuchtes, transport = transport, nowTs = t0,
    )

    // ---- DIE PFLICHTSEQUENZ ---------------------------------------------

    @Test
    fun `Schritt 1 - publiziert und offen belastet genau einmal`() {
        // Die Dosis steht in BEIDEN Sichten: gebucht (mit Kennung) und
        // offen im Transport. Abgezogen werden darf sie nur einmal.
        val r = rest(listOf(gebucht(0.30, "p1")), listOf(offen(0.30, "p1")))
        assertEquals(0.70, r!!, 1e-9, "0,30 einmal abgezogen, nicht zweimal (0,40 waere der Fehler)")
    }

    @Test
    fun `Schritt 2 - die Pumpenbestaetigung aendert den Rest nicht`() {
        // Bestaetigt heisst: der Transportposten verschwindet, die Buchung
        // bleibt. Der Rest MUSS derselbe sein - sonst haette der Uebergang
        // von "offen" nach "bestaetigt" die Mengenrechnung verschoben.
        val offenNoch = rest(listOf(gebucht(0.30, "p1")), listOf(offen(0.30, "p1")))
        val bestaetigt = rest(listOf(gebucht(0.30, "p1")), emptyList())
        assertEquals(0.70, bestaetigt!!, 1e-9)
        assertEquals(offenNoch!!, bestaetigt, 1e-12, "der Rest darf sich beim Bestaetigen nicht bewegen")
    }

    @Test
    fun `Schritt 3 - bewiesenes Nicht-Senden gibt den Deckel voll zurueck`() {
        // Der Rollback entfernt die Zeile UND der Transportposten faellt
        // weg. Danach ist der Deckel wieder unberuehrt.
        assertEquals(1.00, rest(emptyList(), emptyList())!!, 1e-9)
    }

    @Test
    fun `Schritt 4 - bestaetigte und fremde offene Menge addieren sich`() {
        // 0,30 bestaetigt (nur Liste) + 0,20 offen unter ANDERER Kennung
        // (nur Transport) = 0,50 belegt.
        val r = rest(listOf(gebucht(0.30, "p1")), listOf(offen(0.20, "p2")))
        assertEquals(0.50, r!!, 1e-9)
    }

    @Test
    fun `Schritt 5 - der Doppelabzug ist als Fehler erkennbar`() {
        // Die Mutationsprobe in Testform: die fehlerhafte Rechnung
        // (skalarer Abzug ohne ID-Abgleich) ergibt einen ANDEREN Wert.
        // Bricht der ID-Abgleich, faellt Schritt 1 auf genau diesen Wert.
        val gebuchtes = listOf(gebucht(0.30, "p1"))
        val transport = listOf(offen(0.30, "p1"))
        val richtig = rest(gebuchtes, transport)!!
        val falsch = (deckel - gebuchtes.sumOf { it.amountU } - transport.sumOf { it.amountU })
        assertEquals(0.40, falsch, 1e-9, "so rechnete die erste Fassung")
        assertEquals(0.70, richtig, 1e-9)
    }

    // ---- RANDFAELLE ------------------------------------------------------

    @Test
    fun `ohne Deckel gibt es keinen Headroom`() {
        assertNull(
            FuseCycleRunner.serienHeadroom(0.0, 30, listOf(gebucht(0.30, "p1")), emptyList(), t0),
            "0 = aus, und dann rechnet das Gate bitgleich zum bisherigen Stand",
        )
    }

    @Test
    fun `eine Zeile OHNE Kennung wird konservativ doppelt gezaehlt`() {
        // Ohne nachgetragene Kennung ist die Zuordnung nicht moeglich.
        // Dann bleibt es beim doppelten Abzug - die sichere Richtung, und
        // genau deshalb ist das Nachtragen der Kennung Pflicht
        // (resolveReservation legt sonst gar keine Ablage an).
        val r = rest(listOf(gebucht(0.30, null)), listOf(offen(0.30, "p1")))
        assertEquals(0.40, r!!, 1e-9, "unzuordenbar -> lieber zu streng als zu grosszuegig")
    }

    @Test
    fun `alte Buchungen fallen aus dem Fenster, offene Transportmengen nicht`() {
        // Das Fenster gilt fuer die BUCHUNGEN. Eine offene Transportmenge
        // ist unabhaengig davon noch nicht geflossen und haftet weiter.
        val r = rest(listOf(gebucht(0.30, "alt", minutenHer = 45.0)), listOf(offen(0.20, "p2")))
        assertEquals(0.80, r!!, 1e-9, "die 45 min alte Buchung zaehlt nicht mehr mit")
    }

    @Test
    fun `der Rest faellt nie unter null`() {
        val r = rest(listOf(gebucht(0.90, "p1")), listOf(offen(0.50, "p2")))
        assertEquals(0.0, r!!, 1e-12)
    }
}

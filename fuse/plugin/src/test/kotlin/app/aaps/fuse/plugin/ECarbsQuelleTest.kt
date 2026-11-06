package app.aaps.fuse.plugin

import app.aaps.core.data.model.CA
import app.aaps.fuse.core.controller.ECarbs
import app.aaps.fuse.core.controller.MealBalance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Entwurf B, Toni 05.11.2026: die angekuendigten eCarbs der Rechner-Mahlzeit ([ECarbsQuelle]) - nur gueltige
 * KH-Eintraege MIT Dauer, deren Beginn zur Mahlzeit am Marker gehoert, zerlegt wie AAPS. Dazu die von einem frueheren
 * Marker uebernommenen, mit ihrer Herkunft (Codex 05.11.).
 */
class ECarbsQuelleTest {

    private val marker = 1_800_000_000_000L
    private val min = 60_000L

    private fun ca(id: Long, ts: Long, g: Double, dauerMin: Long, gueltig: Boolean = true) =
        CA(id = id, timestamp = ts, duration = dauerMin * min, amount = g, isValid = gueltig)

    @Test
    fun `nur gueltige Eintraege mit Dauer im Fenster um den Marker, zerlegt wie AAPS`() {
        val eintraege = ECarbsQuelle.zuordnen(
            listOf(
                // Die eCarbs des Rechners: 17 g ab +60 min ueber 3 h.
                ca(1, marker + 60 * min, 17.0, 180),
                // Die Sofort-KH ohne Dauer zaehlen hier nicht - sie stehen in der Rechnung.
                ca(2, marker, 40.0, 0),
                // Geloescht.
                ca(3, marker + 30 * min, 10.0, 60, gueltig = false),
                // Vor dem Fenster und danach.
                ca(4, marker - ECarbsQuelle.VOR_MS - min, 5.0, 30),
                ca(5, marker + ECarbsQuelle.NACH_MS + min, 5.0, 30),
            ),
            marker,
            emptyList(),
        )
        assertEquals(
            listOf(ECarbsQuelle.Eintrag(MealBalance.ECarbsRef(1, marker), 17.0, ECarbs.parts(17.0, marker + 60 * min, 180))),
            eintraege,
        )
    }

    /**
     * Ein uebernommener Eintrag behaelt seine Herkunft, wo immer er liegt: weit vor dem Fenster des neuen Markers zaehlt er
     * weiter, und in dessen Fenster zaehlt er nicht ein zweites Mal als eigener.
     */
    @Test
    fun `ein uebernommener Eintrag behaelt seine Herkunft, vor dem Fenster und im Fenster des neuen Markers`() {
        val frueher = marker - 2 * 3600_000L
        val alt = ca(11, frueher, 36.0, 240)
        val spaet = ca(12, marker - 10 * min, 9.0, 60)
        val eigen = ca(21, marker, 18.0, 120)
        // Vor dem Fenster und nicht uebernommen: gehoert nicht zur Episode.
        val fremd = ca(13, frueher, 5.0, 30)
        val uebernommen = listOf(MealBalance.ECarbsRef(11, frueher), MealBalance.ECarbsRef(12, frueher))
        val eintraege = ECarbsQuelle.zuordnen(listOf(alt, spaet, eigen, fremd), marker, uebernommen)
        assertEquals(
            listOf(MealBalance.ECarbsRef(11, frueher), MealBalance.ECarbsRef(12, frueher), MealBalance.ECarbsRef(21, marker)),
            eintraege.map { it.ref },
        )
        assertEquals(ECarbs.parts(36.0, frueher, 240), eintraege.first().teile)
        // Geloescht zaehlt er nicht mehr - auch nicht uebernommen.
        assertTrue(ECarbsQuelle.zuordnen(listOf(ca(11, frueher, 36.0, 240, gueltig = false)), marker, uebernommen).isEmpty())
    }

    @Test
    fun `ohne Kennung aus der Datenbank wird nichts uebernommen`() {
        val frueher = marker - 2 * 3600_000L
        assertTrue(ECarbsQuelle.zuordnen(listOf(ca(0, frueher, 36.0, 240)), marker, listOf(MealBalance.ECarbsRef(0, frueher))).isEmpty())
    }
}

package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Der KH-Eintrag zur Mahlzeit (Toni 26.11.2026): geschrieben nur mit KH-Menge; bei der Ruecknahme faellt er nur im
 * Versehen-Fenster, danach bleibt er (Kodex 26.11.2026: gegessene KH werden nicht automatisch geloescht). Synthetische Zeiten.
 */
class MahlzeitKhEintragTest {

    private val marker = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `geschrieben wird nur mit KH-Menge`() {
        assertEquals(MahlzeitKhEintrag.Aktion.Schreiben(marker, 55, 150), MahlzeitKhEintrag.beimSetzen(marker, 55, 150))
        assertEquals(MahlzeitKhEintrag.Aktion.Schreiben(marker, 55, 0), MahlzeitKhEintrag.beimSetzen(marker, 55, 0))
        // Nur eine Absorptionszeit, oder gar keine Eingabe: kein Eintrag.
        assertNull(MahlzeitKhEintrag.beimSetzen(marker, 0, 150))
        assertNull(MahlzeitKhEintrag.beimSetzen(marker, 0, 0))
    }

    @Test
    fun `eine Ruecknahme im Versehen-Fenster nimmt den Eintrag mit`() {
        for (alter in listOf(0L, minute, 10 * minute, MahlzeitKhEintrag.VERSEHEN_MS)) {
            assertEquals(MahlzeitKhEintrag.Aktion.Loeschen(7L, marker, 55), MahlzeitKhEintrag.beiRuecknahme(7L, marker, marker + alter, 55)) {
                "Alter $alter ms"
            }
        }
    }

    @Test
    fun `eine spaetere Ruecknahme stoppt nur das Konto - die gegessenen KH bleiben`() {
        val spaet = marker + MahlzeitKhEintrag.VERSEHEN_MS + 1
        assertEquals(MahlzeitKhEintrag.Aktion.Behalten(7L, marker, 15L), MahlzeitKhEintrag.beiRuecknahme(7L, marker, spaet, 55))
        assertEquals(MahlzeitKhEintrag.Aktion.Behalten(7L, marker, 60L), MahlzeitKhEintrag.beiRuecknahme(7L, marker, marker + 60 * minute, 55))
    }

    @Test
    fun `ohne Eintrag gibt es bei der Ruecknahme nichts zu tun`() {
        assertNull(MahlzeitKhEintrag.beiRuecknahme(0L, marker, marker + minute, 55))
        assertNull(MahlzeitKhEintrag.beiRuecknahme(-1L, marker, marker + minute, 55))
    }
}

package app.aaps.fuse.plugin

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.keys.interfaces.Preferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * HCL Teil 1 (Toni 25.10.2026): die Rechnungen aus dem Bolusrechner - genau einmal festgehalten, ueber einen Neustart
 * erhalten, im Trail Rechnerwerte und Abgabe getrennt. Alle Zahlen synthetisch.
 */
class HclMahlzeitenTest {

    private var gespeichert = ""
    private val preferences: Preferences = mock()

    init {
        whenever(preferences.get(FuseStringNonKey.HclMeals)).thenAnswer { gespeichert }
        doAnswer { gespeichert = it.getArgument(1); Unit }.whenever(preferences).put(eq(FuseStringNonKey.HclMeals), any<String>())
    }

    private val jetzt = 1_800_000_000_000L
    private val stunde = 3_600_000L

    private fun rechnung(
        calcTs: Long = jetzt - stunde,
        mealTs: Long = calcTs + 15 * 60_000L,
        carbsG: Double = 60.0,
        icGPerU: Double = 9.0,
        deliveredU: Double = 5.0,
    ) = FuseOverviewSource.WizardBolus(
        calcTs = calcTs, mealTs = mealTs, carbsG = carbsG, icGPerU = icGPerU, isfMgdlPerU = 90.0,
        carbsInsulinU = carbsG / icGPerU, glucoseInsulinU = 0.3, trendInsulinU = null, cobInsulinU = null, iobU = 1.0,
        superbolusU = null, directCorrectionU = 0.0, percentage = 75, calcTotalU = 4.5, requestedU = 5.0,
        deliveredU = deliveredU, success = true,
    )

    @Test
    fun `eine Rechnung wird genau einmal festgehalten und ueberlebt einen Neustart`() {
        val r = rechnung()
        assertTrue(HclMahlzeiten(preferences).aufnehmen(r, jetzt))
        assertFalse(HclMahlzeiten(preferences).aufnehmen(r, jetzt)) { "dieselbe Rechnung zaehlt nicht zweimal" }
        // Neue Instanz = Neustart: sie liest den gespeicherten Stand.
        assertEquals(listOf(r), HclMahlzeiten(preferences).juengste(jetzt))
    }

    @Test
    fun `ungueltige Rechnungen werden nicht festgehalten`() {
        val m = HclMahlzeiten(preferences)
        assertFalse(m.aufnehmen(rechnung(carbsG = 0.0), jetzt))
        assertFalse(m.aufnehmen(rechnung(icGPerU = 0.0), jetzt))
        assertFalse(m.aufnehmen(rechnung(deliveredU = Double.NaN), jetzt))
        assertTrue(m.juengste(jetzt).isEmpty())
    }

    @Test
    fun `eine gescheiterte Abgabe wird mit 0 festgehalten`() {
        val m = HclMahlzeiten(preferences)
        assertTrue(m.aufnehmen(rechnung(deliveredU = 0.0).copy(success = false), jetzt))
        assertEquals(0.0, m.juengste(jetzt).single().deliveredU, 0.0)
    }

    @Test
    fun `im Trail stehen nur Mahlzeiten im Fenster - auch eine geplante - und Alte fallen weg`() {
        val m = HclMahlzeiten(preferences)
        val alt = rechnung(calcTs = jetzt - 9 * stunde)
        val geplant = rechnung(calcTs = jetzt - 60_000L, mealTs = jetzt + 30 * 60_000L)
        val sehrAlt = rechnung(calcTs = jetzt - 30 * stunde)
        assertTrue(m.aufnehmen(sehrAlt, jetzt - 29 * stunde))
        assertTrue(m.aufnehmen(alt, jetzt))
        assertTrue(m.aufnehmen(geplant, jetzt))
        assertEquals(listOf(geplant), m.juengste(jetzt)) { "nur was im Trail-Fenster liegt" }
        // Die Rechnung von vor ueber 24 h wurde beim naechsten Aufnehmen verworfen.
        assertEquals(listOf(alt, geplant), m.juengste(jetzt, fensterMs = 48 * stunde))
    }

    @Test
    fun `der Trail-Block fuehrt Rechnerwerte und Abgabe getrennt`() {
        val r = rechnung()
        val o = HclMahlzeiten.zuJson(r)
        assertEquals(60.0 / 9.0, o.getDouble("carbsInsulinU"), 1e-12)
        assertEquals(4.5, o.getDouble("calcTotalU"), 0.0)
        assertEquals(5.0, o.getDouble("requestedU"), 0.0)
        assertEquals(5.0, o.getDouble("deliveredU"), 0.0)
        assertEquals(75, o.getInt("percentage"))
        // Ein ungenutzter Teil der Rechnung steht als null, nicht als 0.
        assertTrue(o.isNull("trendInsulinU"))
        assertEquals(r, HclMahlzeiten.vonJson(o))
        // Ein kaputter Eintrag wird verworfen, nicht geraten.
        assertNull(HclMahlzeiten.vonJson(o.put("carbsG", 0.0)))
    }

    // ---- HCL Stufe 2: die Uebernahme am Marker ------------------------------------------------

    private val minute = 60_000L

    private fun uebernahme(
        schalter: Boolean = true,
        abgegeben: Double = 5.0,
        essen: Long = jetzt - 4 * minute,
        aktiv: (Long) -> Boolean = { false },
        hcl: Boolean = false,
    ) = HclMahlzeiten.uebernahme(schalter, abgegeben, essen, jetzt, aktiv, hcl)

    @Test
    fun `Gegenprobe - Schalter aus oder kein abgegebener Bolus setzt keinen Marker`() {
        assertEquals(HclMahlzeiten.Uebernahme.Keine, uebernahme(schalter = false))
        // Abgebrochen ohne Abgabe: kein Marker, kein Nachholauftrag.
        assertEquals(HclMahlzeiten.Uebernahme.Keine, uebernahme(abgegeben = 0.0))
        assertEquals(HclMahlzeiten.Uebernahme.Keine, uebernahme(abgegeben = Double.NaN))
        // Abgebrochen mit Teilmenge: die Mahlzeit gilt - das Insulin ist die Teilmenge (IOB), nie die Rechnung.
        assertEquals(HclMahlzeiten.Uebernahme.NeuerMarker(jetzt - 4 * minute), uebernahme(abgegeben = 1.2))
    }

    @Test
    fun `der Marker steht am Essenszeitpunkt - nie in der Zukunft, nie weit zurueck`() {
        // Die Mahlzeit beginnt mit der Rechnung, der Bolus endet spaeter: der Marker steht am Essen.
        assertEquals(HclMahlzeiten.Uebernahme.NeuerMarker(jetzt - 10 * minute), uebernahme(essen = jetzt - 10 * minute))
        assertEquals(HclMahlzeiten.Uebernahme.NeuerMarker(jetzt - 30 * minute), uebernahme(essen = jetzt - 30 * minute))
        // KH-Zeit voraus: der Marker steht jetzt - die Mahlzeit zeigt sich spaeter, angerechnet wird nur, was sich zeigt.
        assertEquals(HclMahlzeiten.Uebernahme.NeuerMarker(jetzt), uebernahme(essen = jetzt + 20 * minute))
        // Weiter zurueck: kein Marker - eine spaet beginnende Kurve rechnete mehr kommende Mahlzeit an, als noch kommt.
        assertEquals(HclMahlzeiten.Uebernahme.ZuAlt, uebernahme(essen = jetzt - 31 * minute))
    }

    @Test
    fun `Gegenprobe - laeuft ein Marker, wird zusammengefuehrt, eine zweite Rechnung nur aufgezeichnet`() {
        // Ein Marker laeuft jetzt: dieselbe Mahlzeit - zusammenfuehren.
        assertEquals(HclMahlzeiten.Uebernahme.Zusammenfuehren, uebernahme(aktiv = { it == jetzt }))
        // Er lief zum Essenszeitpunkt (und ist inzwischen abgelaufen): ebenso.
        assertEquals(HclMahlzeiten.Uebernahme.Zusammenfuehren, uebernahme(aktiv = { it == jetzt - 4 * minute }))
        // Der laufende Marker gehoert schon zu einer Rechnung: die zweite wird nur aufgezeichnet.
        assertEquals(HclMahlzeiten.Uebernahme.SchonHcl, uebernahme(aktiv = { true }, hcl = true))
        // Nach dem Fenster: eine zweite Mahlzeit bekommt ihren eigenen Marker.
        assertEquals(HclMahlzeiten.Uebernahme.NeuerMarker(jetzt - 4 * minute), uebernahme(aktiv = { false }, hcl = true))
    }

    @Test
    fun `der Runner findet die Rechnung des Markers - auch nach einem Neustart`() {
        val r = rechnung()
        assertTrue(HclMahlzeiten(preferences).aufnehmen(r, jetzt))
        assertEquals(r, HclMahlzeiten(preferences).rechnung(r.calcTs))
        assertNull(HclMahlzeiten(preferences).rechnung(r.calcTs + 1))
        assertNull(HclMahlzeiten(preferences).rechnung(0L))
    }
}

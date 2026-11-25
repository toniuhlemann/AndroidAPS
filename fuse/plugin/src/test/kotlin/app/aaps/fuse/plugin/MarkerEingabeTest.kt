package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.fuse.core.controller.EarlySupply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * MAHLZEIT-EINGABE (Toni 25.11.2026): was der Druck festhaelt und der Regler wieder liest - geprueft, gerundet wie
 * gespeichert, und ohne Angabe genau "keine". Synthetische Werte, keine Messdaten.
 */
class MarkerEingabeTest {

    private val preferences: Preferences = mock()

    private fun gespeichert(khG: Long, khMilliU: Long, zeitMin: Long) {
        whenever(preferences.get(FuseLongKey.MealMarkerCarbsG)).thenReturn(khG)
        whenever(preferences.get(FuseLongKey.MealMarkerCarbsInsulinMilliU)).thenReturn(khMilliU)
        whenever(preferences.get(FuseLongKey.MealMarkerAbsorptionMin)).thenReturn(zeitMin)
    }

    @Test
    fun `der Druck rechnet KH durch IC und haelt beide Angaben fest`() {
        val stand = MarkerEingabe.von(carbsG = 60, absorptionMin = 240, icGPerU = 10.0)
        assertEquals(MarkerEingabe.Stand(60, EarlySupply.MealInput(6.0, 240)), stand)
        // Gerundet, wie es gespeichert wird - Anzeige und Buchung rechnen mit demselben Wert.
        assertEquals(7.143, MarkerEingabe.von(50, 0, 7.0).eingabe.carbsInsulinU, 0.0)
    }

    @Test
    fun `eine KH-Angabe ohne IC oder ausserhalb der Grenzen gilt als nicht gemacht`() {
        assertEquals(MarkerEingabe.Stand(0, EarlySupply.MealInput(0.0, 240)), MarkerEingabe.von(60, 240, null))
        assertEquals(MarkerEingabe.Stand(0, EarlySupply.MealInput(0.0, 240)), MarkerEingabe.von(EarlySupply.MAX_INPUT_CARBS_G + 1, 240, 10.0))
        assertEquals(MarkerEingabe.Stand(60, EarlySupply.MealInput(6.0, 0)), MarkerEingabe.von(60, EarlySupply.MAX_INPUT_ABSORPTION_MIN + 1, 10.0))
        assertEquals(MarkerEingabe.KEINE, MarkerEingabe.von(0, 0, 10.0))
        assertTrue(MarkerEingabe.KEINE.eingabe.isEmpty)
    }

    /** Geschrieben wird immer alles - auch "keine": ein Marker traegt nie die Eingabe eines frueheren. */
    @Test
    fun `schreiben setzt alle drei Werte`() {
        MarkerEingabe.schreiben(preferences, MarkerEingabe.von(60, 240, 10.0))
        MarkerEingabe.schreiben(preferences, MarkerEingabe.KEINE)
        inOrder(preferences) {
            verify(preferences).put(FuseLongKey.MealMarkerCarbsG, 60L)
            verify(preferences).put(FuseLongKey.MealMarkerCarbsInsulinMilliU, 6000L)
            verify(preferences).put(FuseLongKey.MealMarkerAbsorptionMin, 240L)
            verify(preferences).put(FuseLongKey.MealMarkerCarbsG, 0L)
            verify(preferences).put(FuseLongKey.MealMarkerCarbsInsulinMilliU, 0L)
            verify(preferences).put(FuseLongKey.MealMarkerAbsorptionMin, 0L)
        }
    }

    @Test
    fun `lesen gibt wieder, was der Druck geschrieben hat`() {
        gespeichert(khG = 60, khMilliU = 6000, zeitMin = 240)
        assertEquals(MarkerEingabe.von(60, 240, 10.0), MarkerEingabe.lesen(preferences))
        gespeichert(khG = 0, khMilliU = 0, zeitMin = 150)
        assertEquals(MarkerEingabe.Stand(0, EarlySupply.MealInput(0.0, 150)), MarkerEingabe.lesen(preferences))
        gespeichert(khG = 50, khMilliU = 7143, zeitMin = 0)
        assertEquals(MarkerEingabe.von(50, 0, 7.0), MarkerEingabe.lesen(preferences))
    }

    @Test
    fun `ohne Angabe und bei Werten ausserhalb der Grenzen liest der Regler keine Eingabe`() {
        gespeichert(0, 0, 0)
        assertSame(MarkerEingabe.KEINE, MarkerEingabe.lesen(preferences))
        gespeichert(khG = 0, khMilliU = -5, zeitMin = -1)
        assertSame(MarkerEingabe.KEINE, MarkerEingabe.lesen(preferences))
        gespeichert(khG = 0, khMilliU = 0, zeitMin = EarlySupply.MAX_INPUT_ABSORPTION_MIN + 1L)
        assertSame(MarkerEingabe.KEINE, MarkerEingabe.lesen(preferences))
        // Die KH-Menge ist nur Anzeige: ohne KH-Insulin zaehlt sie nicht, und das KH-Insulin gilt auch ohne sie.
        gespeichert(khG = 60, khMilliU = 0, zeitMin = 0)
        assertSame(MarkerEingabe.KEINE, MarkerEingabe.lesen(preferences))
        gespeichert(khG = 0, khMilliU = 6000, zeitMin = 0)
        assertEquals(MarkerEingabe.Stand(0, EarlySupply.MealInput(6.0, 0)), MarkerEingabe.lesen(preferences))
    }
}

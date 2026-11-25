package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.fuse.core.controller.EarlySupply
import kotlin.math.roundToLong

/**
 * MAHLZEIT-EINGABE (Toni 25.11.2026): KH-Menge und Absorptionszeit am Mahlzeit-Knopf - der Episodenzustand dazu, an
 * EINER Stelle geprueft, geschrieben und gelesen.
 *
 * Die Angaben kommen aus dem Dialog des Knopfdrucks, nicht aus den Einstellungen. Der Druck haelt sie fest
 * ([schreiben]); der Regler liest sie ([lesen]) und bucht damit das Konto dieses Markers. Sie gelten nur fuer die Wahl
 * "Mahlzeit" - "Snack", "Nur Marker" und ein Marker aus dem Bolusrechner tragen keine ([KEINE]). Ohne Angabe rechnet
 * alles wie bisher.
 */
internal object MarkerEingabe {

    /** Was ein Marker festhaelt. [carbsG] ist nur fuer Trail und Anzeige; gerechnet wird mit [eingabe]. */
    data class Stand(val carbsG: Int, val eingabe: EarlySupply.MealInput)

    val KEINE = Stand(0, EarlySupply.MealInput.NONE)

    /**
     * Die Eingabe eines Drucks, geprueft ([EarlySupply.MealInput.of]). [icGPerU] ist das IC des Profils zur Druckzeit;
     * ohne es gibt es kein KH-Insulin, und die KH-Angabe gilt als nicht gemacht. Das KH-Insulin steht hier schon so
     * gerundet, wie es gespeichert wird - Anzeige am Knopf und Buchung rechnen mit demselben Wert.
     */
    fun von(carbsG: Int, absorptionMin: Int, icGPerU: Double?): Stand {
        val roh = EarlySupply.MealInput.of(carbsG, icGPerU, absorptionMin)
        val khInsulin = milliU(roh.carbsInsulinU) / 1000.0
        return Stand(if (khInsulin > 0.0) carbsG else 0, EarlySupply.MealInput(khInsulin, roh.absorptionMin))
    }

    fun schreiben(preferences: Preferences, stand: Stand) {
        preferences.put(FuseLongKey.MealMarkerCarbsG, stand.carbsG.toLong())
        preferences.put(FuseLongKey.MealMarkerCarbsInsulinMilliU, milliU(stand.eingabe.carbsInsulinU))
        preferences.put(FuseLongKey.MealMarkerAbsorptionMin, stand.eingabe.absorptionMin.toLong())
    }

    /** Was der laufende Marker traegt. Ein Wert ausserhalb seiner Grenzen gilt als keine Angabe. */
    fun lesen(preferences: Preferences): Stand {
        val khInsulin = preferences.get(FuseLongKey.MealMarkerCarbsInsulinMilliU).takeIf { it > 0L }?.let { it / 1000.0 } ?: 0.0
        val zeit = preferences.get(FuseLongKey.MealMarkerAbsorptionMin)
            .takeIf { it in 1L..EarlySupply.MAX_INPUT_ABSORPTION_MIN.toLong() }?.toInt() ?: 0
        val kh = preferences.get(FuseLongKey.MealMarkerCarbsG)
            .takeIf { khInsulin > 0.0 && it in 1L..EarlySupply.MAX_INPUT_CARBS_G.toLong() }?.toInt() ?: 0
        return if (khInsulin <= 0.0 && zeit == 0) KEINE else Stand(kh, EarlySupply.MealInput(khInsulin, zeit))
    }

    private fun milliU(u: Double): Long = if (u.isFinite() && u > 0.0) (u * 1000.0).roundToLong() else 0L
}

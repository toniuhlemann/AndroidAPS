package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject

/**
 * BACKUP-RUNDLAUF DER ZENTRALEN PROFILWERTE (A5-Abschluss, Toni 29.08.).
 *
 * Das generische `put(key, preferences)` liest fuer ungesetzte Schluessel den
 * BILDSCHIRM-Default - ein Backup/Restore verwandelte "unkonfiguriert" damit
 * still in gesetzte 5/5/1/1, und die Aktivierungssperre (vier Werte muessen
 * AUSDRUECKLICH gesetzt sein) waere ausgehebelt. Deshalb:
 *
 *  - [schreibe] sichert einen Kandidaten NUR, wenn er wirklich gesetzt und
 *    im Rahmen ist (dieselbe Grenzen-Klammer wie im Config-Bau).
 *  - [lese] setzt den policyMode IMMER (fehlt er im Backup - jedes Backup
 *    vor A4 -, gilt sicher LEGACY) und ENTFERNT fehlende Kandidaten, statt
 *    sie auf den Default zu setzen - der Zustand "unkonfiguriert" ueberlebt
 *    den Rundlauf in beide Richtungen.
 */
object FuseCentralProfileBackup {

    private val kandidaten = listOf(
        FuseDoubleKey.CorrectionExposureLimitU,
        FuseDoubleKey.MealExposureLimitU,
        FuseDoubleKey.CorrectionDemandRatioCap,
        FuseDoubleKey.MealDemandRatioCap,
        // M1: dieselbe Rundlauf-Regel - eine unkonfigurierte MEAL-Schwelle
        // darf ein Restore nicht in eine gesetzte 140 verwandeln.
        FuseDoubleKey.LivenessBgMinMealMgdl,
    )

    fun schreibe(json: JSONObject, preferences: Preferences) {
        for (k in kandidaten) {
            preferences.getIfExists(k)
                ?.takeIf { it.isFinite() && it in k.min..k.max }
                ?.let { json.put(k.key, it) }
        }
    }

    fun lese(json: JSONObject, preferences: Preferences) {
        val werte = kandidaten.associateWith { k ->
            (if (json.has(k.key)) json.optDouble(k.key) else Double.NaN)
                .takeIf { it.isFinite() && it in k.min..k.max }
        }
        for ((k, wert) in werte) {
            if (wert != null) preferences.put(k, wert) else preferences.remove(k)
        }
        // AKTIVIERUNGSSPERRE AUCH BEIM RESTORE (Toni 29.08.): ein Backup
        // darf NIE CENTRAL_PROFILES mit unvollstaendigen oder relational
        // falschen Werten hinterlassen - sonst braeche jeder Folgezyklus
        // fail-closed ab. Derselbe Validator wie am UI-Schalter.
        val gewuenscht = json.optBoolean(FuseBooleanKey.CentralProfilesEnabled.key, false)
        val fehler = aktivierungsFehler(
            werte[FuseDoubleKey.CorrectionExposureLimitU],
            werte[FuseDoubleKey.MealExposureLimitU],
            werte[FuseDoubleKey.CorrectionDemandRatioCap],
            werte[FuseDoubleKey.MealDemandRatioCap],
        )
        preferences.put(FuseBooleanKey.CentralProfilesEnabled, gewuenscht && fehler == null)
        // M3-MIGRATION (Toni 29.08.): ein ALTES Backup ohne MealArmCycles
        // stellt den neutralen Altwert wieder her - store() liess sonst
        // einen bereits gespeicherten Wert 1 stehen, und das Backup
        // behauptete einen Regler, den es nie enthielt. Entfernen = unset,
        // der Config-Bau liefert dann die Vorgabe 3; ein Ausreisser im
        // Backup zaehlt als nie gesetzt.
        val zyklen = if (json.has(FuseIntKey.MealArmCycles.key))
            json.optInt(FuseIntKey.MealArmCycles.key) else 0
        if (zyklen in FuseIntKey.MealArmCycles.min..FuseIntKey.MealArmCycles.max)
            preferences.put(FuseIntKey.MealArmCycles, zyklen)
        else preferences.remove(FuseIntKey.MealArmCycles)
    }

    /**
     * DIE AKTIVIERUNGSREGEL ALS REINE FUNKTION - EIN Validator fuer den
     * UI-Schalter und den Restore (Toni 29.08.: die Laufzeitvalidierung
     * ist nur die letzte Sicherung, keine Aktivierungssperre). null =
     * vollstaendig gueltig; sonst der verstaendliche Grund.
     */
    fun aktivierungsFehler(
        corrExposureU: Double?,
        mealExposureU: Double?,
        corrRatio: Double?,
        mealRatio: Double?,
    ): String? = when {
        corrExposureU == null -> "CORR Exposure-Limit nicht gesetzt"
        mealExposureU == null -> "MEAL Exposure-Limit nicht gesetzt"
        corrRatio == null -> "CORR Demand-Ratio-Cap nicht gesetzt"
        mealRatio == null -> "MEAL Demand-Ratio-Cap nicht gesetzt"
        corrExposureU > mealExposureU -> "CORR Exposure-Limit groesser als MEAL"
        corrRatio > mealRatio -> "CORR Demand-Ratio-Cap groesser als MEAL"
        else -> null
    }

    /** Derselbe Validator auf dem aktuellen Preferences-Stand - fuer den
     *  UI-Schalter (dieselbe Grenzen-Klammer wie im Config-Bau). */
    fun aktivierungsFehler(preferences: Preferences): String? = aktivierungsFehler(
        gesetzt(preferences, FuseDoubleKey.CorrectionExposureLimitU),
        gesetzt(preferences, FuseDoubleKey.MealExposureLimitU),
        gesetzt(preferences, FuseDoubleKey.CorrectionDemandRatioCap),
        gesetzt(preferences, FuseDoubleKey.MealDemandRatioCap),
    )

    private fun gesetzt(preferences: Preferences, k: FuseDoubleKey): Double? =
        preferences.getIfExists(k)?.takeIf { it.isFinite() && it in k.min..k.max }
}

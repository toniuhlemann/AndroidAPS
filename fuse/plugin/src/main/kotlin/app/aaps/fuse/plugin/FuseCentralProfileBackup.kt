package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject

/**
 * BACKUP-RUNDLAUF DER ZENTRALEN PROFILWERTE (CENTRAL-only; Review-P1
 * 30.08.: das Backup schreibt die EFFEKTIVEN Werte IMMER aus).
 *
 * Ein Geraet, das nur mit den Startsatz-Defaults faehrt, faehrt trotzdem
 * eine konkrete Konfiguration - ein Backup ohne diese Zahlen wuerde nach
 * einer spaeteren Default-Aenderung NICHT mehr die tatsaechlich gefahrene
 * Politik wiederherstellen. Deshalb sichert [schreibe] fuer alle fuenf
 * Double-Regler den WIRKSAMEN Wert (get() = gesetzter Wert oder Default);
 * MealArmCycles laeuft ueber den generischen put() der Exportkette und
 * steht damit ebenfalls immer im Backup.
 *
 * [lese] bleibt migrationsfest: ein ALTES Backup ohne diese Felder
 * entfernt die Schluessel - danach gelten die AKTUELLEN Defaults, nie ein
 * erfundener Wert; Ausreisser zaehlen als nie gesetzt. Ein neues Backup
 * traegt die Felder immer und stellt exakt die gefahrene Politik her.
 */
object FuseCentralProfileBackup {

    private val effektiveWerte = listOf(
        FuseDoubleKey.CorrectionExposureLimitU,
        FuseDoubleKey.MealExposureLimitU,
        FuseDoubleKey.CorrectionDemandRatioCap,
        FuseDoubleKey.MealDemandRatioCap,
        FuseDoubleKey.LivenessBgMinMealMgdl,
    )

    fun schreibe(json: JSONObject, preferences: Preferences) {
        for (k in effektiveWerte) {
            // get() liefert den WIRKSAMEN Wert (gesetzt oder Default) -
            // genau das faehrt das Geraet, genau das gehoert ins Backup.
            json.put(k.key, preferences.get(k))
        }
    }

    fun lese(json: JSONObject, preferences: Preferences) {
        for (k in effektiveWerte) {
            val wert = (if (json.has(k.key)) json.optDouble(k.key) else Double.NaN)
                .takeIf { it.isFinite() && it in k.min..k.max }
            if (wert != null) preferences.put(k, wert) else preferences.remove(k)
        }
        // M3-MIGRATION (Toni 29.08.): ein ALTES Backup ohne MealArmCycles
        // stellt den Default-Zustand wieder her - store() liess sonst
        // einen bereits gespeicherten Wert stehen, und das Backup
        // behauptete einen Regler, den es nie enthielt. Entfernen = unset,
        // der Config-Bau liefert dann den Startsatz-Default (1); ein
        // Ausreisser im Backup zaehlt als nie gesetzt.
        val zyklen = if (json.has(FuseIntKey.MealArmCycles.key))
            json.optInt(FuseIntKey.MealArmCycles.key) else 0
        if (zyklen in FuseIntKey.MealArmCycles.min..FuseIntKey.MealArmCycles.max)
            preferences.put(FuseIntKey.MealArmCycles, zyklen)
        else preferences.remove(FuseIntKey.MealArmCycles)
    }
}

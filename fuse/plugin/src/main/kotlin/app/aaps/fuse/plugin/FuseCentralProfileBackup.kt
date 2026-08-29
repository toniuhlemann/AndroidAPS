package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject

/**
 * BACKUP-RUNDLAUF DER ZENTRALEN PROFILWERTE (CENTRAL-only seit dem
 * Legacy-Cleanup 29.08. nachts).
 *
 * Die vier Profilwerte tragen ECHTE Runtime-Defaults (Tonis Startsatz);
 * ein Backup sichert nur AUSDRUECKLICH gesetzte, gueltige Werte, und ein
 * Restore entfernt fehlende Schluessel, statt sie zu erfinden - danach
 * gilt der Default (Migrationsregel: alte Backups ohne die Keys erhalten
 * den Startsatz, ausdruecklich gesetzte Werte ueberschreibt kein Update).
 * Die MEAL-Druckschwelle bleibt echt optional (unkonfiguriert =
 * Tag-/Nachtschwelle); MealArmCycles behaelt seine Migrationsregel.
 * Der fruehere Modusschalter und seine Aktivierungssperre sind mit dem
 * LEGACY-Pfad entfernt - die Laufzeitvalidierung (validate) bleibt die
 * fail-closed-Sicherung gegen relational falsche Werte.
 */
object FuseCentralProfileBackup {

    private val kandidaten = listOf(
        FuseDoubleKey.CorrectionExposureLimitU,
        FuseDoubleKey.MealExposureLimitU,
        FuseDoubleKey.CorrectionDemandRatioCap,
        FuseDoubleKey.MealDemandRatioCap,
        // M1: eine unkonfigurierte MEAL-Schwelle darf ein Restore nicht in
        // eine gesetzte 140 verwandeln.
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
        for (k in kandidaten) {
            val wert = (if (json.has(k.key)) json.optDouble(k.key) else Double.NaN)
                .takeIf { it.isFinite() && it in k.min..k.max }
            if (wert != null) preferences.put(k, wert) else preferences.remove(k)
        }
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
}

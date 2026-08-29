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
        preferences.put(
            FuseBooleanKey.CentralProfilesEnabled,
            json.optBoolean(FuseBooleanKey.CentralProfilesEnabled.key, false),
        )
        for (k in kandidaten) {
            val wert = if (json.has(k.key)) json.optDouble(k.key) else Double.NaN
            if (wert.isFinite() && wert in k.min..k.max) preferences.put(k, wert)
            else preferences.remove(k)
        }
    }
}

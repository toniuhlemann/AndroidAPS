package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Der Backup-Rundlauf der zentralen Profilwerte (Review-P1 30.08.): ein
 * Backup schreibt die EFFEKTIVEN Werte IMMER aus - auch ein reines
 * Default-Geraet sichert 3/7/0,20/0,35/110, damit eine spaetere
 * Default-Aenderung die gefahrene Politik nicht umdeutet. Alte Backups
 * ohne die Felder migrieren auf die aktuellen Defaults (remove).
 */
class FuseCentralProfileBackupTest {

    private fun prefs(vararg gesetzt: Pair<FuseDoubleKey, Double>): Preferences =
        mock<Preferences>().also { p ->
            // get() liefert den WIRKSAMEN Wert: gesetzt oder Key-Default -
            // exakt die Laufzeitsemantik.
            whenever(p.get(any<FuseDoubleKey>())).thenAnswer { inv ->
                val k = inv.arguments[0] as FuseDoubleKey
                gesetzt.firstOrNull { it.first == k }?.second ?: k.defaultValue
            }
        }

    @Test
    fun `ein Default-Geraet sichert den kompletten Startsatz`() {
        val json = JSONObject()
        FuseCentralProfileBackup.schreibe(json, prefs())
        assertEquals(3.0, json.getDouble(FuseDoubleKey.CorrectionExposureLimitU.key), 1e-12)
        assertEquals(7.0, json.getDouble(FuseDoubleKey.MealExposureLimitU.key), 1e-12)
        assertEquals(0.20, json.getDouble(FuseDoubleKey.CorrectionDemandRatioCap.key), 1e-12)
        assertEquals(0.35, json.getDouble(FuseDoubleKey.MealDemandRatioCap.key), 1e-12)
        assertEquals(110.0, json.getDouble(FuseDoubleKey.LivenessBgMinMealMgdl.key), 1e-12)
    }

    @Test
    fun `gesetzte Werte reisen unveraendert mit`() {
        val json = JSONObject()
        FuseCentralProfileBackup.schreibe(
            json,
            prefs(
                FuseDoubleKey.CorrectionExposureLimitU to 2.5,
                FuseDoubleKey.MealDemandRatioCap to 0.5,
            ),
        )
        assertEquals(2.5, json.getDouble(FuseDoubleKey.CorrectionExposureLimitU.key), 1e-12)
        assertEquals(0.5, json.getDouble(FuseDoubleKey.MealDemandRatioCap.key), 1e-12)
        // Die uebrigen stehen als wirksame Defaults ebenfalls drin.
        assertEquals(7.0, json.getDouble(FuseDoubleKey.MealExposureLimitU.key), 1e-12)

        val ziel = mock<Preferences>()
        FuseCentralProfileBackup.lese(json, ziel)
        verify(ziel).put(FuseDoubleKey.CorrectionExposureLimitU, 2.5)
        verify(ziel).put(FuseDoubleKey.MealDemandRatioCap, 0.5)
        verify(ziel).put(FuseDoubleKey.MealExposureLimitU, 7.0)
    }

    @Test
    fun `ein altes Backup ohne die Felder migriert auf die aktuellen Defaults`() {
        val ziel = mock<Preferences>()
        FuseCentralProfileBackup.lese(JSONObject(), ziel)
        // Entfernen = unset -> der Config-Bau liefert die AKTUELLEN
        // Defaults; nichts wird aus dem leeren Backup erfunden.
        verify(ziel).remove(FuseDoubleKey.CorrectionExposureLimitU)
        verify(ziel).remove(FuseDoubleKey.MealExposureLimitU)
        verify(ziel).remove(FuseDoubleKey.CorrectionDemandRatioCap)
        verify(ziel).remove(FuseDoubleKey.MealDemandRatioCap)
        verify(ziel).remove(FuseDoubleKey.LivenessBgMinMealMgdl)
        verify(ziel, never()).put(any<FuseDoubleKey>(), any<Double>())
    }

    @Test
    fun `ein Ausreisser im Backup zaehlt als nie gesetzt`() {
        val json = JSONObject().put(FuseDoubleKey.MealExposureLimitU.key, 99.0)
        val ziel = mock<Preferences>()
        FuseCentralProfileBackup.lese(json, ziel)
        verify(ziel, never()).put(eq(FuseDoubleKey.MealExposureLimitU), any<Double>())
        verify(ziel).remove(FuseDoubleKey.MealExposureLimitU)
    }

    @Test
    fun `ein altes Backup ohne MealArmCycles stellt den Default her`() {
        val ziel = mock<Preferences>()
        FuseCentralProfileBackup.lese(JSONObject(), ziel)
        verify(ziel).remove(FuseIntKey.MealArmCycles)
        verify(ziel, never()).put(eq(FuseIntKey.MealArmCycles), any<Int>())
    }

    @Test
    fun `ein Backup mit MealArmCycles 3 bleibt 3`() {
        val json = JSONObject().put(FuseIntKey.MealArmCycles.key, 3)
        val ziel = mock<Preferences>()
        FuseCentralProfileBackup.lese(json, ziel)
        verify(ziel).put(FuseIntKey.MealArmCycles, 3)
    }

    @Test
    fun `ein MealArmCycles-Ausreisser im Backup wird entfernt`() {
        val json = JSONObject().put(FuseIntKey.MealArmCycles.key, 99)
        val ziel = mock<Preferences>()
        FuseCentralProfileBackup.lese(json, ziel)
        verify(ziel).remove(FuseIntKey.MealArmCycles)
        assertTrue(FuseIntKey.MealArmCycles.max < 99)
    }
}

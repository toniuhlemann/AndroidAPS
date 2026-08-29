package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Der Backup-Rundlauf der zentralen Profilwerte (CENTRAL-only): gesichert
 * wird nur, was ausdruecklich gesetzt ist; ein Restore entfernt fehlende
 * Schluessel, statt Werte zu erfinden - danach gelten die ECHTEN Defaults
 * (Tonis Startsatz). MealArmCycles behaelt seine Migrationsregel.
 */
class FuseCentralProfileBackupTest {

    private fun prefs(vararg gesetzt: Pair<FuseDoubleKey, Double>): Preferences =
        mock<Preferences>().also { p ->
            whenever(p.getIfExists(any<FuseDoubleKey>())).thenAnswer { inv ->
                gesetzt.firstOrNull { it.first == inv.arguments[0] }?.second
            }
        }

    @Test
    fun `ungesetzte Werte werden nicht gesichert und nicht erfunden`() {
        val json = JSONObject()
        FuseCentralProfileBackup.schreibe(json, prefs())
        assertFalse(json.has(FuseDoubleKey.CorrectionExposureLimitU.key))
        assertFalse(json.has(FuseDoubleKey.MealExposureLimitU.key))
        assertFalse(json.has(FuseDoubleKey.LivenessBgMinMealMgdl.key))

        val ziel = mock<Preferences>()
        FuseCentralProfileBackup.lese(JSONObject(), ziel)
        // Fehlende Schluessel werden ENTFERNT - danach gilt der echte
        // Default, nie ein erfundener Wert aus dem Restore.
        verify(ziel).remove(FuseDoubleKey.CorrectionExposureLimitU)
        verify(ziel).remove(FuseDoubleKey.MealExposureLimitU)
        verify(ziel).remove(FuseDoubleKey.CorrectionDemandRatioCap)
        verify(ziel).remove(FuseDoubleKey.MealDemandRatioCap)
        verify(ziel).remove(FuseDoubleKey.LivenessBgMinMealMgdl)
        verify(ziel, never()).put(any<FuseDoubleKey>(), any<Double>())
    }

    @Test
    fun `gesetzte Werte reisen unveraendert mit`() {
        val json = JSONObject()
        FuseCentralProfileBackup.schreibe(
            json,
            prefs(
                FuseDoubleKey.CorrectionExposureLimitU to 2.5,
                FuseDoubleKey.MealDemandRatioCap to 0.35,
            ),
        )
        assertEquals(2.5, json.getDouble(FuseDoubleKey.CorrectionExposureLimitU.key), 1e-12)
        assertEquals(0.35, json.getDouble(FuseDoubleKey.MealDemandRatioCap.key), 1e-12)

        val ziel = mock<Preferences>()
        FuseCentralProfileBackup.lese(json, ziel)
        verify(ziel).put(FuseDoubleKey.CorrectionExposureLimitU, 2.5)
        verify(ziel).put(FuseDoubleKey.MealDemandRatioCap, 0.35)
        verify(ziel).remove(FuseDoubleKey.MealExposureLimitU)
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

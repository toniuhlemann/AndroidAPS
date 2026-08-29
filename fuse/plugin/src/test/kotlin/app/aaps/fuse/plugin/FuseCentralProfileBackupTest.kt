package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * DER A5-ABSCHLUSS-VERTRAG (Toni 29.08.): "unkonfiguriert" ueberlebt den
 * Backup-Rundlauf in BEIDE Richtungen, und ein altes Backup ohne policyMode
 * fuehrt sicher zu LEGACY. Das generische put/store haette aus fehlenden
 * Kandidaten stillschweigend gesetzte Bildschirm-Defaults gemacht - und
 * damit die Aktivierungssperre (vier Werte muessen AUSDRUECKLICH gesetzt
 * sein) ausgehebelt.
 */
class FuseCentralProfileBackupTest {

    @Test
    fun `unkonfigurierte Kandidaten werden nicht gesichert und nicht erfunden`() {
        val prefs = mock<Preferences>()
        val json = JSONObject()
        FuseCentralProfileBackup.schreibe(json, prefs)
        assertFalse(json.has(FuseDoubleKey.CorrectionExposureLimitU.key))
        assertFalse(json.has(FuseDoubleKey.MealExposureLimitU.key))
        assertFalse(json.has(FuseDoubleKey.CorrectionDemandRatioCap.key))
        assertFalse(json.has(FuseDoubleKey.MealDemandRatioCap.key))

        FuseCentralProfileBackup.lese(json, prefs)
        // Fehlende Kandidaten werden ENTFERNT, nie auf den Default gesetzt.
        verify(prefs).remove(FuseDoubleKey.CorrectionExposureLimitU)
        verify(prefs).remove(FuseDoubleKey.MealExposureLimitU)
        verify(prefs).remove(FuseDoubleKey.CorrectionDemandRatioCap)
        verify(prefs).remove(FuseDoubleKey.MealDemandRatioCap)
        verify(prefs, never()).put(FuseDoubleKey.MealExposureLimitU, FuseDoubleKey.MealExposureLimitU.defaultValue)
    }

    @Test
    fun `gesetzte Kandidaten reisen unveraendert mit`() {
        val prefs = mock<Preferences>()
        whenever(prefs.getIfExists(FuseDoubleKey.MealExposureLimitU)).thenReturn(6.0)
        whenever(prefs.getIfExists(FuseDoubleKey.CorrectionExposureLimitU)).thenReturn(3.0)
        val json = JSONObject()
        FuseCentralProfileBackup.schreibe(json, prefs)
        assertEquals(6.0, json.getDouble(FuseDoubleKey.MealExposureLimitU.key), 1e-12)
        assertEquals(3.0, json.getDouble(FuseDoubleKey.CorrectionExposureLimitU.key), 1e-12)
        assertFalse(json.has(FuseDoubleKey.MealDemandRatioCap.key), "ungesetzt bleibt draussen")

        val ziel = mock<Preferences>()
        FuseCentralProfileBackup.lese(json, ziel)
        verify(ziel).put(FuseDoubleKey.MealExposureLimitU, 6.0)
        verify(ziel).put(FuseDoubleKey.CorrectionExposureLimitU, 3.0)
        verify(ziel).remove(FuseDoubleKey.MealDemandRatioCap)
    }

    @Test
    fun `ein altes Backup ohne policyMode fuehrt sicher zu LEGACY`() {
        val prefs = mock<Preferences>()
        // Altes Backup (vor A4): kennt weder Modus noch Kandidaten.
        FuseCentralProfileBackup.lese(JSONObject(), prefs)
        verify(prefs).put(FuseBooleanKey.CentralProfilesEnabled, false)
    }

    @Test
    fun `ein Ausreisser im Backup zaehlt als nie gesetzt`() {
        val prefs = mock<Preferences>()
        val json = JSONObject().put(FuseDoubleKey.MealExposureLimitU.key, 99.0)
        FuseCentralProfileBackup.lese(json, prefs)
        verify(prefs).remove(FuseDoubleKey.MealExposureLimitU)
        assertTrue(true)
    }

    /** M3-Migration (Toni 29.08.): ein ALTES Backup ohne MealArmCycles
     *  stellt den neutralen Altwert wieder her - ein bereits gespeicherter
     *  Wert 1 darf den Restore nicht ueberleben. */
    @Test
    fun `ein altes Backup ohne MealArmCycles stellt den Altwert her`() {
        val prefs = mock<Preferences>()
        // Geraetestand: Wert 1 gespeichert - das Backup kennt den Key nicht.
        FuseCentralProfileBackup.lese(JSONObject(), prefs)
        verify(prefs).remove(FuseIntKey.MealArmCycles)
    }

    /** Und der Rundlauf eines NEUEN Backups mit Wert 1 bleibt 1. */
    @Test
    fun `ein neues Backup mit MealArmCycles 1 bleibt 1`() {
        val prefs = mock<Preferences>()
        val json = JSONObject().put(FuseIntKey.MealArmCycles.key, 1)
        FuseCentralProfileBackup.lese(json, prefs)
        verify(prefs).put(FuseIntKey.MealArmCycles, 1)
        verify(prefs, never()).remove(FuseIntKey.MealArmCycles)
    }

    /** Ein Ausreisser (99) zaehlt als nie gesetzt. */
    @Test
    fun `ein MealArmCycles-Ausreisser im Backup wird entfernt`() {
        val prefs = mock<Preferences>()
        val json = JSONObject().put(FuseIntKey.MealArmCycles.key, 99)
        FuseCentralProfileBackup.lese(json, prefs)
        verify(prefs).remove(FuseIntKey.MealArmCycles)
    }

    /** AKTIVIERUNGSSPERRE beim Restore: ein Backup mit CENTRAL_PROFILES
     *  aber unvollstaendigen Werten darf den Modus NICHT hinterlassen. */
    @Test
    fun `ein unvollstaendiges CENTRAL-Backup faellt auf LEGACY zurueck`() {
        val prefs = mock<Preferences>()
        val json = JSONObject()
            .put(FuseBooleanKey.CentralProfilesEnabled.key, true)
            .put(FuseDoubleKey.MealExposureLimitU.key, 6.0)
        FuseCentralProfileBackup.lese(json, prefs)
        verify(prefs).put(FuseBooleanKey.CentralProfilesEnabled, false)
    }

    /** Ein VOLLSTAENDIGES CENTRAL-Backup darf den Modus behalten. */
    @Test
    fun `ein vollstaendiges CENTRAL-Backup bleibt CENTRAL`() {
        val prefs = mock<Preferences>()
        val json = JSONObject()
            .put(FuseBooleanKey.CentralProfilesEnabled.key, true)
            .put(FuseDoubleKey.CorrectionExposureLimitU.key, 3.0)
            .put(FuseDoubleKey.MealExposureLimitU.key, 6.0)
            .put(FuseDoubleKey.CorrectionDemandRatioCap.key, 0.15)
            .put(FuseDoubleKey.MealDemandRatioCap.key, 0.35)
        FuseCentralProfileBackup.lese(json, prefs)
        verify(prefs).put(FuseBooleanKey.CentralProfilesEnabled, true)
    }

    /** DER GEMEINSAME VALIDATOR: abgelehnte und erfolgreiche Aktivierung
     *  (UI-Schalter und Restore teilen exakt diese Regel). */
    @Test
    fun `der Aktivierungs-Validator nennt Fehlen und Relation beim Namen`() {
        assertEquals(null, FuseCentralProfileBackup.aktivierungsFehler(3.0, 6.0, 0.15, 0.35))
        assertTrue(FuseCentralProfileBackup.aktivierungsFehler(null, 6.0, 0.15, 0.35)!!.contains("CORR Exposure"))
        assertTrue(FuseCentralProfileBackup.aktivierungsFehler(3.0, 6.0, 0.15, null)!!.contains("MEAL Demand"))
        assertTrue(FuseCentralProfileBackup.aktivierungsFehler(7.0, 6.0, 0.15, 0.35)!!.contains("groesser als MEAL"))
        assertTrue(FuseCentralProfileBackup.aktivierungsFehler(3.0, 6.0, 0.5, 0.35)!!.contains("groesser als MEAL"))
    }
}

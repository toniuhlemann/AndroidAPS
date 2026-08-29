package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * DER SETTINGS-BERICHT ERFUELLT DENSELBEN VERTRAG WIE DER
 * EINSTELLUNGSBILDSCHIRM ([fuseEinstellbareKeys]).
 *
 * Die Inventar-Wache des Bildschirms feuert erst beim Bau am Geraet - der
 * Bericht hat so eine Wache nicht, also traegt sie dieser Test. Ohne ihn
 * waere ein neuer Key im Bildschirm sichtbar, im Bericht aber still
 * abwesend, und der Bericht saehe weiter "vollstaendig" aus.
 */
class FuseSettingsReportTest {

    private fun standardPreferences(): Preferences = mock<Preferences>().also { p ->
        whenever(p.get(any<DoublePreferenceKey>())).thenAnswer { (it.arguments[0] as DoublePreferenceKey).defaultValue }
        whenever(p.get(any<IntPreferenceKey>())).thenAnswer { (it.arguments[0] as IntPreferenceKey).defaultValue }
        whenever(p.get(any<BooleanPreferenceKey>())).thenAnswer { (it.arguments[0] as BooleanPreferenceKey).defaultValue }
    }

    @Test
    fun `der Bericht deckt exakt die einstellbaren Keys`() {
        val report = FuseSettingsReport.build(standardPreferences())
        val keys = report.gruppen.flatMap { it.second }.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "kein Key doppelt")
        assertEquals(fuseEinstellbareKeys, keys.toSet())
    }

    /** LEGACY-CLEANUP: im Zentralmodus verschwinden exakt die vier
     *  Legacy-Kanaldeckel aus dem Bericht - nicht mehr, nicht weniger.
     *  (Der Aktivierungs-Validator ist hier nicht im Spiel: der Bericht
     *  beschreibt den gespeicherten Zustand.) */
    @Test
    fun `im Zentralmodus verschwinden exakt die Legacy-Deckel-Zeilen`() {
        val p = standardPreferences()
        whenever(p.get(FuseBooleanKey.CentralProfilesEnabled)).thenReturn(true)
        val report = FuseSettingsReport.build(p)
        val keys = report.gruppen.flatMap { it.second }.map { it.key }.toSet()
        val legacy = setOf(
            FuseDoubleKey.LivenessMealRatioCap.key,
            FuseDoubleKey.LivenessMealIobCapPercent.key,
            FuseDoubleKey.LivenessCorrectionRatioCap.key,
            FuseDoubleKey.LivenessCorrectionIobCapPercent.key,
        )
        assertEquals(fuseEinstellbareKeys - legacy, keys)
        assertTrue(
            report.gruppen.flatMap { it.second }.none { it.value.contains("ignoriert") },
            "keine Anzeige darf eine ignorierte Grenze zeigen",
        )
    }

    @Test
    fun `auf Standardwerten traegt keine Zeile eine Abweichungsmarke`() {
        val report = FuseSettingsReport.build(standardPreferences())
        val abweichend = report.gruppen.flatMap { it.second }.filter { it.standard != null }
        assertTrue(abweichend.isEmpty()) { "faelschlich markiert: ${abweichend.map { it.label }}" }
    }

    /** Der eigentliche Zweck: ein nach einem Testlauf stehen gebliebener
     *  Wert (maxSmb 0,55 statt 0,3) muss markiert sein - MIT Standardwert. */
    @Test
    fun `eine Abweichung traegt Marke und Standardwert`() {
        val p = standardPreferences()
        whenever(p.get(FuseDoubleKey.MaxSmbU)).thenReturn(0.55)
        whenever(p.get(FuseBooleanKey.NightDeadbandEnabled)).thenReturn(false)
        val rows = FuseSettingsReport.build(p).gruppen.flatMap { it.second }

        val maxSmb = rows.single { it.key == FuseDoubleKey.MaxSmbU.key }
        assertEquals("0.55 U", maxSmb.value)
        assertEquals("0.30 U", maxSmb.standard)

        val nacht = rows.single { it.key == FuseBooleanKey.NightDeadbandEnabled.key }
        assertEquals("aus", nacht.value)
        assertEquals("an", nacht.standard)
    }

    @Test
    fun `Uhrzeiten erscheinen als Uhrzeit nicht als Minutenzahl`() {
        val rows = FuseSettingsReport.build(standardPreferences()).gruppen.flatMap { it.second }
        assertEquals("23:00", rows.single { it.key == FuseIntKey.NightStartMin.key }.value)
        assertEquals("07:00", rows.single { it.key == FuseIntKey.NightEndMin.key }.value)
    }
    /** Der Geraetefund vom 15.08.: die Preference kam mit winzigem
     *  Konvertierungsrest zurueck und "0.15 [Standard 0.15]" stand auf dem
     *  Schirm. Rundungsrauschen ist KEINE Abweichung. */
    @Test
    fun `Rundungsrauschen zaehlt nicht als Abweichung`() {
        val p = standardPreferences()
        whenever(p.get(FuseDoubleKey.SmbRatio)).thenReturn(FuseDoubleKey.SmbRatio.defaultValue + 1e-9)
        val row = FuseSettingsReport.build(p).gruppen.flatMap { it.second }
            .single { it.key == FuseDoubleKey.SmbRatio.key }
        assertEquals(null, row.standard) { "Rundungsrest als Abweichung markiert" }
    }
}

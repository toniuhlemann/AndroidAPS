package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
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
        whenever(p.get(any<StringPreferenceKey>())).thenAnswer { (it.arguments[0] as StringPreferenceKey).defaultValue }
    }

    @Test
    fun `der Bericht deckt exakt die einstellbaren Keys`() {
        val report = FuseSettingsReport.build(standardPreferences())
        val keys = report.gruppen.flatMap { it.second }.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "kein Key doppelt")
        assertEquals(fuseEinstellbareKeys, keys.toSet())
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
        // 2.0 (Stufe 1, K3): das Nacht-Totband ist kein Schalter mehr; als Schalter
        // mit Standard "an" dient jetzt das Null-Ende.
        whenever(p.get(FuseBooleanKey.TbrEndZeroWhenReasonGone)).thenReturn(false)
        val rows = FuseSettingsReport.build(p).gruppen.flatMap { it.second }

        val maxSmb = rows.single { it.key == FuseDoubleKey.MaxSmbU.key }
        assertEquals("0.55 U", maxSmb.value)
        assertEquals("0.30 U", maxSmb.standard)

        val nullEnde = rows.single { it.key == FuseBooleanKey.TbrEndZeroWhenReasonGone.key }
        assertEquals("aus", nullEnde.value)
        assertEquals("an", nullEnde.standard)
    }

    /** Erwartungskonto (E-034): die Kurve steht als Wirkung im Bericht, nie
     *  als Zahlenkette - leer und ungueltig heissen "Erwartung aus". */
    @Test
    fun `die Erwartungskurve zeigt ihre Wirkung`() {
        fun zeile(text: String): FuseScreenModel.SettingRow {
            val p = standardPreferences()
            whenever(p.get(FuseStringKey.ExpectationCurve)).thenReturn(text)
            return FuseSettingsReport.build(p).gruppen.flatMap { it.second }
                .single { it.key == FuseStringKey.ExpectationCurve.key }
        }
        assertEquals("leer - Erwartung aus (nur Ersetzung)", zeile("").value)
        assertEquals(null, zeile("").standard)
        val ungueltig = zeile("0.1; 0.2")
        assertEquals("ungueltig - Erwartung aus (nur Ersetzung)", ungueltig.value)
        assertEquals("leer - Erwartung aus (nur Ersetzung)", ungueltig.standard)
        val gueltig = zeile((1..24).joinToString(";") { (it * 0.1).toString() })
        assertTrue(gueltig.value.startsWith("gueltig - 24 Stuetzwerte")) { gueltig.value }
    }

    /** H8: nicht mehr einstellbar (Konsolidierung) - die Texthilfe nennt den Horizont weiter wie der Regler. */
    @Test
    fun `frueher MEAL-Horizont nennt AUS und unzulaessige Werte als wirkt als AUS`() {
        // Die Auswahl des Dialogs ist genau die wirksame Menge, AUS zuerst.
        assertEquals(listOf(0, 6, 8, 10), EarlyAdaptiveMealHorizonText.auswahl)
        assertEquals(app.aaps.fuse.core.controller.EarlyAdaptiveMealNeed.ALLOWED_HORIZONS_MIN, EarlyAdaptiveMealHorizonText.auswahl.toSet())
        assertEquals(listOf("AUS", "6 min", "8 min", "10 min"), EarlyAdaptiveMealHorizonText.auswahl.map(EarlyAdaptiveMealHorizonText::eintrag))
        // Die Zeile im Bildschirm (updatePreferenceSummary) sagt dasselbe wie der Bericht.
        assertEquals("Aktuell: AUS\nErklaerung", EarlyAdaptiveMealHorizonText.zusammenfassung(0, "Erklaerung"))
        assertEquals("Aktuell: 7 min - unzulaessig, wirkt als AUS\nErklaerung", EarlyAdaptiveMealHorizonText.zusammenfassung(7, "Erklaerung"))
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

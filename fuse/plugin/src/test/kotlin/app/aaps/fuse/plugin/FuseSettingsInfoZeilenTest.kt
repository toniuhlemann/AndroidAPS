package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.preference.PreferenceInfoDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * v4-Port (Toni): die FUSE-Einstellungen naeher an v3 - die Info-Zeilen stehen wieder an ihren Plaetzen zwischen
 * den Werten (nicht gesammelt auf einer eigenen Seite), und die Live-Zeilen am Feld folgen jeder ihrer Eingaben.
 */
class FuseSettingsInfoZeilenTest {

    private val bereiche = FuseSettings.abschnitte().associateBy { it.key }

    /** Die Reihenfolge eines Bereichs: Schluessel als Key, Info-Zeilen als "[Titel]" oder "[Absatz]". */
    private fun folge(bereich: String): List<String> = bereiche.getValue(bereich).items.map { it.name() }

    private fun PreferenceItem.name(): String = when (this) {
        is PreferenceKey     -> key
        is PreferenceInfoDef -> "[" + ((title as? TextRef.Literal)?.text ?: "Absatz") + "]"
        else                 -> "?"
    }

    @Test
    fun `die Kontoseite zeigt Konto an, Fenster und Freigabe und Schutz an ihren v3-Plaetzen`() {
        val f = folge("fuse_expectation")
        assertEquals("[Absatz]", f.first(), "die Beschreibung des Kontos steht ueber den Feldern")
        assertEquals(f.indexOf(FuseDoubleKey.ExpectationCurveAt30U.key) - 1, f.indexOf("[Konto an]"))
        assertEquals(f.indexOf(FuseIntKey.ExpectationHorizonMin.key) + 1, f.indexOf("[Fenster]"))
        assertEquals(f.indexOf("[Fenster]") + 1, f.indexOf("[Freigabe und Schutz]"))
        assertEquals(f.indexOf(FuseDoubleKey.ExpectationStabilityBgMgdl.key) - 1, f.indexOf("[Freigabe und Schutz]"))
    }

    @Test
    fun `die fruehe Versorgung beschreibt sich oben und nennt die Anrechnung vor den Schaltern`() {
        val f = folge("fuse_expectation_early")
        assertEquals("[Absatz]", f.first())
        assertEquals(f.indexOf(FuseBooleanKey.EarlySupplyInitialLiftsGuard.key) - 1, f.indexOf("[Anrechnung bei Guard und Schwanzpruefung]"))
    }

    @Test
    fun `jede Info-Zeile steht genau einmal und jeder Bereich hat weiter einen Wert`() {
        val infos = bereiche.values.flatMap { b -> b.items.filterIsInstance<PreferenceInfoDef>().map { it.text } }
        assertEquals(infos.size, infos.toSet().size, "eine Info-Zeile doppelt")
        // 24 = die Info-Zeilen des Forks, die bisher gesammelt auf der Seite "Hinweise" standen
        assertEquals(24, infos.size, "Info-Zeilen verloren oder dazugekommen")
        for (b in bereiche.values) assertTrue(b.items.any { it is PreferenceKey }) { "Bereich ${b.key} zeigt keinen Wert" }
    }

    /** Toni: in ALLEN Bereichen am Feld nur die kurze Zeile, die ganze Erklaerung erst beim Antippen. */
    @Test
    fun `alle Bereiche zeigen nur kurze Zeilen`() {
        val lang = bereiche.values.filterNot { it.shortSummaries }.map { it.key }
        assertTrue(lang.isEmpty()) { "Bereiche mit voller Erklaerung am Feld: $lang" }
    }

    /** Ein Eingabe-Typ ausserhalb der vier beobachteten liesse seine Zeile nach einer Aenderung stehen. */
    @Test
    fun `jede Eingabe der Live-Zeilen wird beobachtet`() {
        val eingaben = FuseSettings.liveZeilenEingaben()
        val unbeobachtet = eingaben.filterNot { it is DoublePreferenceKey || it is IntPreferenceKey || it is BooleanPreferenceKey || it is StringPreferenceKey }
        assertTrue(unbeobachtet.isEmpty()) { "nicht beobachtet: ${unbeobachtet.map { it.key }}" }
        assertTrue(FuseDoubleKey.MealExposureLimitU in eingaben && FuseDoubleKey.RiseRampLowR in eingaben)
        assertTrue(FuseStringKey.ExpectationCurve in eingaben && FuseBooleanKey.EarlySupplyEnabled in eingaben)
    }
}

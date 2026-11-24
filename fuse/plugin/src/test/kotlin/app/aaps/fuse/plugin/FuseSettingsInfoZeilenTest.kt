package app.aaps.fuse.plugin

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.preference.PreferenceInfoDef
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.preference.PreferenceSummaryMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE GLIEDERUNG DER FUSE-EINSTELLUNGEN (Toni 24.11.2026): fuenf Hauptbereiche als Ueberschriften, darunter die
 * Bereiche mit ihrem Satz; die Info-Zeilen gliedern einen Bereich als Zwischenueberschriften; die Live-Zeilen am Feld
 * haengen an den Schluesseln und folgen jeder ihrer Eingaben.
 */
class FuseSettingsInfoZeilenTest {

    private val abschnitte = FuseSettings.abschnitte()
    private val bereiche = abschnitte.associateBy { it.key }

    /** Die Reihenfolge eines Bereichs: Schluessel als Key, Info-Zeilen als "[Titel]" oder "[Absatz]". */
    private fun folge(bereich: String): List<String> = bereiche.getValue(bereich).items.map { it.name() }

    private fun PreferenceItem.name(): String = when (this) {
        is PreferenceKey     -> key
        is PreferenceInfoDef -> "[" + ((title as? TextRef.Literal)?.text ?: "Absatz") + "]"
        else                 -> "?"
    }

    @Test
    fun `die Menge beschreibt sich oben und nennt Kurve und Skalierer vor ihren Werten`() {
        val f = folge("fuse_meal_amount")
        assertEquals("[Absatz]", f.first(), "die Beschreibung steht ueber den Feldern")
        assertTrue(f.indexOf("[Erwartete Mahlzeit (Kurve)]") < f.indexOf(FuseDoubleKey.ExpectationCurveAt30U.key))
        assertTrue(f.indexOf(FuseDoubleKey.ExpectationCurveAt120U.key) < f.indexOf("[Skalierer]"))
        assertEquals(f.indexOf(FuseDoubleKey.EarlySupplyScaleStable.key) - 1, f.indexOf("[Skalierer]"))
        assertEquals(f.indexOf(FuseDoubleKey.EarlySupplyBudgetMaxU.key) - 1, f.indexOf("[Budget]"))
    }

    @Test
    fun `der Ablauf trennt Anfangsportion, weitere Portionen und Mahlzeitfenster`() {
        val f = folge("fuse_meal_flow")
        assertEquals("[Anfangsportion]", f.first())
        assertEquals(1, f.indexOf(FuseDoubleKey.EarlySupplyInitialShare.key))
        assertTrue(f.indexOf(FuseBooleanKey.EarlySupplyInitialLiftsTail.key) < f.indexOf("[Weitere Portionen]"))
        assertEquals(f.indexOf(FuseDoubleKey.EarlySupplyMaxPortionU.key) - 1, f.indexOf("[Weitere Portionen]"))
        assertEquals(f.indexOf(FuseIntKey.LivenessMealPowerMin.key) - 1, f.indexOf("[Mahlzeitfenster]"))
    }

    /** Kodex 24.11.2026: Schwelle, Einstiegszeit und Faktor bestimmen zusammen, wann und wie stark der Kanal eingreift. */
    @Test
    fun `der langsame Kanal zeigt Schwelle, Einstiegszeit und Faktor zusammen`() {
        val f = folge("fuse_corr_slow")
        val schwelle = f.indexOf(FuseDoubleKey.SlowChannelThresholdMgdl.key)
        assertEquals(schwelle + 1, f.indexOf(FuseIntKey.SlowChannelEntryMin.key))
        assertEquals(schwelle + 2, f.indexOf(FuseDoubleKey.SlowChannelFactor.key))
        assertEquals(schwelle - 1, f.indexOf("[Wann und wie stark]"))
    }

    @Test
    fun `jede Info-Zeile steht genau einmal und jeder Bereich hat weiter einen Wert`() {
        val infos = abschnitte.flatMap { b -> b.items.filterIsInstance<PreferenceInfoDef>() }
        val texte = infos.map { it.text }
        assertEquals(texte.size, texte.toSet().size, "eine Info-Zeile doppelt")
        for (i in infos) assertTrue((i.text as TextRef.Literal).text.isNotBlank()) { "Info-Zeile ohne Text: ${i.title}" }
        assertTrue(infos.none { it.groupHeading }) { "eine Hauptueberschrift steht in einem Bereich statt in der Karte" }
        for (b in abschnitte) assertTrue(b.items.any { it is PreferenceKey }) { "Bereich ${b.key} zeigt keinen Wert" }
    }

    /** Toni: in ALLEN Bereichen am Feld nur die Live-Zeile, die Erklaerung erst beim Hineingehen oder Antippen. */
    @Test
    fun `alle Bereiche zeigen nur Live-Zeilen`() {
        val mitErklaerung = abschnitte.filterNot { it.summaryMode == PreferenceSummaryMode.LIVE_ONLY }.map { it.key }
        assertTrue(mitErklaerung.isEmpty()) { "Bereiche mit Erklaerung am Feld: $mitErklaerung" }
    }

    /** Statt der abgeschnittenen Aufzaehlung der Optionsnamen: ein Satz, der sagt, was im Bereich ist. */
    @Test
    fun `jeder Bereich traegt seinen Satz unter dem Titel`() {
        val ohne = abschnitte.filter { it.summary == null || !it.summaryInHeader }.map { it.key }
        assertTrue(ohne.isEmpty()) { "Bereiche ohne eigenen Satz: $ohne" }
    }

    @Test
    fun `jeder Bereich gehoert zu genau einem Hauptbereich, in derselben Reihenfolge`() {
        val gegliedert = FuseSettings.hauptbereiche.flatMap { it.bereiche }
        assertEquals(abschnitte.map { it.key }, gegliedert, "die Hauptbereiche decken die Bereiche nicht genau einmal und in Reihenfolge")
        val titel = FuseSettings.hauptbereiche.map { it.titel }
        assertEquals(listOf("Mahlzeiten", "Korrekturen", "Schutz und Insulingrenzen", "Signal und Prognose", "Anzeige und Diagnose"), titel)
    }

    /** v4 zeichnet in der Karte genau eine Ebene von Bereichen - die Hauptbereiche sind deshalb Ueberschrift-Zeilen. */
    @Test
    fun `die Karte beginnt jeden Hauptbereich mit einer Ueberschrift, darunter seine Bereiche`() {
        val karte = FuseSettings.gliederung()
        var i = 0
        for (h in FuseSettings.hauptbereiche) {
            val kopf = karte[i++] as PreferenceInfoDef
            assertTrue(kopf.groupHeading) { "keine Hauptueberschrift vor ${h.titel}" }
            assertEquals(h.titel, (kopf.title as TextRef.Literal).text)
            assertTrue((kopf.text as TextRef.Literal).text.isNotBlank()) { "Hauptbereich ${h.titel} ohne Satz" }
            for (key in h.bereiche) assertEquals(key, (karte[i++] as PreferenceSubScreenDef).key)
        }
        assertEquals(karte.size, i, "die Karte hat Zeilen ausserhalb der Hauptbereiche")
        // Kein Bereich im Bereich: den wuerde der Bildschirm nicht zeichnen.
        val verschachtelt = abschnitte.filter { b -> b.items.any { it is PreferenceSubScreenDef } }.map { it.key }
        assertTrue(verschachtelt.isEmpty()) { "Bereich im Bereich: $verschachtelt" }
    }

    /** Die Wartung ist kein Einstellwert: ihre drei Seiten stehen in keinem Wertebereich, auch nicht bei der Anzeige. */
    @Test
    fun `kein Wertebereich traegt eine Ledger-Handlung`() {
        val mitHandlung = abschnitte.filter { b -> b.items.any { it is IntentPreferenceKey } }.map { it.key }
        assertTrue(mitHandlung.isEmpty()) { "Ledger-Handlung in einem Wertebereich: $mitHandlung" }
    }

    /**
     * Die Live-Zeilen haengen an den Schluesseln, nicht an einem Bereich: jeder Wert mit eigener Zeile steht genau
     * einmal im Baum, sein Bereich bekommt die Zeilen, und er ist eine Eingabe der gemeinsamen Rechnung.
     */
    @Test
    fun `jeder Wert mit Live-Zeile behaelt sie nach dem Umordnen`() {
        val eingaben = FuseSettings.liveZeilenEingaben().map { it.key }
        for (key in fuseLiveZeilenSchluessel) {
            val orte = abschnitte.filter { b -> b.items.any { it is PreferenceKey && it.key == key } }
            assertEquals(1, orte.size, "$key steht nicht genau einmal im Baum")
            assertTrue(FuseSettings.hatLiveZeilen(orte.single())) { "der Bereich von $key bekommt keine Live-Zeilen" }
            assertTrue(key in eingaben) { "$key ist keine Eingabe der Live-Zeilen" }
        }
        assertEquals(fuseLiveZeilenSchluessel.size, fuseLiveZeilenSchluessel.toSet().size, "ein Schluessel doppelt")
        val mit = abschnitte.filter { FuseSettings.hatLiveZeilen(it) }.map { it.key }
        assertEquals(listOf("fuse_meal_amount", "fuse_meal_flow", "fuse_meal_special", "fuse_meal_advanced"), mit)
    }

    /** Ein Eingabe-Typ ausserhalb der vier beobachteten liesse seine Zeile nach einer Aenderung stehen. */
    @Test
    fun `jede Eingabe der Live-Zeilen wird beobachtet`() {
        val eingaben = FuseSettings.liveZeilenEingaben()
        val unbeobachtet = eingaben.filterNot { it is DoublePreferenceKey || it is IntPreferenceKey || it is BooleanPreferenceKey || it is StringPreferenceKey }
        assertTrue(unbeobachtet.isEmpty()) { "nicht beobachtet: ${unbeobachtet.map { it.key }}" }
        assertTrue(FuseDoubleKey.MealExposureLimitU in eingaben && FuseDoubleKey.RiseRampLowR in eingaben)
        assertTrue(FuseStringKey.ExpectationCurve in eingaben && FuseBooleanKey.EarlySupplyEnabled in eingaben)
        assertEquals(fuseLiveZeilenSchluessel.size + 2, eingaben.size, "Eingaben verloren oder dazugekommen")
    }
}

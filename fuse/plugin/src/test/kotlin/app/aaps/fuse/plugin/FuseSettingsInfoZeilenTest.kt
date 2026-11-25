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
 * DIE GLIEDERUNG DER FUSE-EINSTELLUNGEN (Toni 24./25.11.2026): drei Ebenen eigener Seiten - die Startseite mit den
 * fuenf Hauptbereichen, die Seite eines Hauptbereichs mit je einer Zeile je Bereich, die Seite eines Bereichs mit
 * seinen Werten. Die Info-Zeilen gliedern eine Seite als Zwischenueberschriften; die Live-Zeilen am Feld haengen an
 * den Schluesseln und folgen jeder ihrer Eingaben.
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
        for (b in abschnitte) assertTrue(b.items.any { it is PreferenceKey }) { "Bereich ${b.key} zeigt keinen Wert" }
    }

    /** Toni: in ALLEN Bereichen am Feld nur die Live-Zeile, die Erklaerung erst beim Hineingehen oder Antippen. */
    @Test
    fun `alle Bereiche zeigen nur Live-Zeilen`() {
        val mitErklaerung = abschnitte.filterNot { it.summaryMode == PreferenceSummaryMode.LIVE_ONLY }.map { it.key }
        assertTrue(mitErklaerung.isEmpty()) { "Bereiche mit Erklaerung am Feld: $mitErklaerung" }
    }

    /** Jeder Bereich ist eine eigene Seite; sein Satz steht auf der Zeile, die ihn oeffnet. */
    @Test
    fun `jeder Bereich ist eine eigene Seite mit seinem Satz`() {
        val ohne = abschnitte.filter { it.summary == null || !it.pages }.map { it.key }
        assertTrue(ohne.isEmpty()) { "Bereiche ohne Seite oder ohne Satz: $ohne" }
    }

    @Test
    fun `jeder Bereich gehoert zu genau einem Hauptbereich, in derselben Reihenfolge`() {
        val gegliedert = FuseSettings.hauptbereiche.flatMap { it.bereiche }
        assertEquals(abschnitte.map { it.key }, gegliedert, "die Hauptbereiche decken die Bereiche nicht genau einmal und in Reihenfolge")
        val titel = FuseSettings.hauptbereiche.map { it.titel }
        assertEquals(listOf("Mahlzeiten", "Korrekturen", "Schutz und Insulingrenzen", "Signal und Prognose", "Anzeige und Diagnose"), titel)
    }

    /**
     * Toni 25.11.2026: eine aufgeklappte Karte ist eine lange Liste. Die Startseite zeigt deshalb nur die fuenf
     * Hauptbereiche - je eine Zeile mit Symbol und Satz, die ihre Seite oeffnet.
     */
    @Test
    fun `die Startseite zeigt die fuenf Hauptbereiche als Zeilen mit Symbol und Satz`() {
        val seiten = FuseSettings.gliederung()
        assertEquals(FuseSettings.hauptbereiche.map { it.titel }, seiten.map { (it.title as TextRef.Literal).text })
        for ((h, s) in FuseSettings.hauptbereiche.zip(seiten)) {
            assertTrue(s.pages) { "${h.titel} ist keine eigene Seite" }
            assertEquals(h.kurz, (s.summary as TextRef.Literal).text, "Zeile von ${h.titel}")
            assertEquals(h.symbol, s.icon, "Symbol von ${h.titel}")
        }
        val keys = seiten.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "Seitenschluessel doppelt: $keys")
    }

    /** Jeder Bereich ist von der Startseite aus erreichbar: als Seite eines Hauptbereichs oder als Zeile darauf. */
    @Test
    fun `jeder Bereich ist in hoechstens zwei Schritten erreichbar, in seiner Reihenfolge`() {
        val erreichbar = FuseSettings.gliederung().flatMap { seite ->
            val zeilen = seite.items.filterIsInstance<PreferenceSubScreenDef>()
            if (zeilen.isEmpty()) listOf(seite.key) else {
                assertTrue(seite.items.none { it is PreferenceKey }) { "${seite.key} mischt Werte und Seiten" }
                zeilen.map { it.key }
            }
        }
        assertEquals(abschnitte.map { it.key }.sorted(), erreichbar.sorted(), "Bereiche fehlen oder stehen doppelt")
        // Keine vierte Ebene: die Seite eines Bereichs traegt keine weitere Seite.
        val verschachtelt = abschnitte.filter { b -> b.items.any { it is PreferenceSubScreenDef } }.map { it.key }
        assertTrue(verschachtelt.isEmpty()) { "Seite in der Seite eines Bereichs: $verschachtelt" }
    }

    /** Ein Hauptbereich mit nur einem Bereich IST dieser Bereich - eine Seite mit einer einzigen Zeile waere ein Tipp mehr. */
    @Test
    fun `Signal und Prognose zeigt seine Werte direkt`() {
        val seite = FuseSettings.gliederung().single { (it.title as TextRef.Literal).text == "Signal und Prognose" }
        assertEquals("fuse_signal", seite.key)
        assertEquals(bereiche.getValue("fuse_signal").items.filterIsInstance<PreferenceKey>(), seite.items.filterIsInstance<PreferenceKey>())
        assertTrue(seite.items.first() is PreferenceInfoDef) { "der Satz des Hauptbereichs steht oben auf seiner Seite" }
    }

    /** Kodex 24.11.2026: "Erweitert" gehoert zum Thema - auf der Seite seines Hauptbereichs, abgesetzt am Ende. */
    @Test
    fun `Erweitert steht abgesetzt am Ende der Seite seines Hauptbereichs`() {
        val mit = FuseSettings.gliederung().filter { s -> s.items.any { it is PreferenceSubScreenDef && it.key.endsWith("_advanced") } }
        assertEquals(listOf("fuse_area_meals", "fuse_area_corrections", "fuse_area_protection"), mit.map { it.key })
        for (seite in mit) {
            val letzte = seite.items.last() as PreferenceSubScreenDef
            assertTrue(letzte.key.endsWith("_advanced")) { "${seite.key}: Erweitert steht nicht am Ende" }
            val davor = seite.items[seite.items.size - 2] as PreferenceInfoDef
            assertEquals("Weitere Einstellungen", (davor.title as TextRef.Literal).text)
            assertEquals(1, seite.items.count { it is PreferenceSubScreenDef && it.key.endsWith("_advanced") })
        }
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

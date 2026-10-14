package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DER WAECHTER GEGEN FESTE ZAHLEN IN TEXTEN, DIE VON EINSTELLUNGEN ABHAENGEN.
 *
 * ANLASS (ein Geraetefund): der Marker-Dialog nannte die Freigabemenge "in 15 min",
 * waehrend das Freigabe-Fenster deutlich laenger eingestellt war.
 * Die "15" stand fest im Ressourcen-String.
 *
 * WARUM DAS MEHR IST ALS EIN SCHOENHEITSFEHLER: der Satz nennt eine MENGE und
 * eine ZEIT. Stimmt die Zeit nicht, ist die genannte Menge im falschen Zeitraum
 * gedacht - eine Menge in 15 Minuten ist etwas anderes als in einem laengeren Fenster. Und auf
 * genau diesen Satz gruendet der Nutzer eine INSULIN-AUTORISIERUNG, die
 * Modell-Einwaende ueberstimmt.
 *
 * WARUM STATISCH: der Dialog braucht `FragmentActivity` und `ResourceHelper` -
 * im JVM-Test nicht herstellbar, und Robolectric ist fuer diese Frage bewusst
 * nicht eingefuehrt worden. Die Frage ist ohnehin keine Laufzeitfrage: STEHT
 * EINE FESTE ZAHL IM TEXT?
 */
class DialogDauerWaechterTest {

    private fun datei(vararg kandidaten: String): File {
        val f = kandidaten.map { File(it) }.firstOrNull { it.exists() }
        requireNotNull(f) { "keine der Dateien gefunden: ${kandidaten.joinToString()}" }
        return f
    }

    private fun uiStrings(): String = datei(
        "../../core/ui/src/main/res/values/strings.xml",
        "core/ui/src/main/res/values/strings.xml",
        "../core/ui/src/main/res/values/strings.xml",
    ).readText()

    private fun pluginStrings(): String = datei(
        "src/main/res/values/strings.xml",
        "fuse/plugin/src/main/res/values/strings.xml",
    ).readText()

    /**
     * Der Dialog nennt die Groessenordnung des Kontos als PARAMETER, nicht als Ziffer - die Menge haengt an Kurve,
     * Skalierer und Budgetgrenze, ein fester Wert im Text waere nach der naechsten Einstellung falsch.
     */
    @Test
    fun `der Marker-Dialog nennt die Kontomenge als Parameter`() {
        val zeile = uiStrings().lines().first { it.contains("overview_fuse_meal_confirm_expectation_only\"") }
        assertTrue(zeile.contains("%1\$.2f")) { "die Menge fehlt als Parameter: $zeile" }
        assertFalse(Regex("""\d+([.,]\d+)?\s*U\b""").containsMatchIn(zeile.replace("%1\$.2f U", ""))) {
            "eine feste Menge im Dialogtext: $zeile"
        }
    }

    /**
     * Seit der Konsolidierung zwei Wege (Toni): "Nur Erwartung" und "Nur Marker - 0 U". Die Nullwahl nennt 0 U
     * unmissverstaendlich, und ein Knopf, der Insulin direkt freigibt, kommt nicht zurueck.
     */
    @Test
    fun `der Dialog kennt nur Konto und Nullwahl`() {
        val s = uiStrings()
        val null0 = s.lines().first { it.contains("fuse_meal_confirm_no_prime\"") }
        assertTrue(null0.contains("0 U")) { "die Nullwahl muss unmissverstaendlich 0 U nennen: $null0" }
        assertTrue(s.contains("fuse_meal_confirm_expectation_only\"")) { "der Knopf fuer das Konto fehlt" }
        assertFalse(s.contains("fuse_meal_confirm_release\"")) { "der Freigabe-Knopf der Direktdosis ist zurueck" }
        assertFalse(s.contains("overview_fuse_meal_confirm_upfront\"")) { "eine Direktdosis-Zeile ist zurueck" }
    }

    /**
     * Ohne Konto (keine Kurve, kein Profil oder Modell) sagt der Dialog es ausdruecklich - die Ankuendigung bleibt
     * moeglich, still umgeschaltet wird nichts (Toni).
     */
    @Test
    fun `ohne Konto nennt der Dialog den Grund`() {
        val zeile = uiStrings().lines().first { it.contains("overview_fuse_meal_confirm_no_account\"") }
        assertTrue(zeile.contains("Nur Marker")) { "der Ausweg fehlt im Text: $zeile" }
    }

    /**
     * KEINE ANZEIGE DARF GEGEN DIE VORGABE-KONSTANTE RECHNEN.
     *
     * Zweiter Geraetefund am selben Tag: der FUSE-Reiter zeigte "15/15 min
     * Freigabe" bei laenger eingestelltem Fenster - und darunter in
     * derselben Karte eine noch offene Prime-Menge. Die Zeile widersprach sich selbst,
     * weil sie `PrimeRelease.WINDOW_MIN` (die VORGABE) las, waehrend der
     * Regler die EINSTELLUNG benutzt.
     *
     * `PrimeRelease.WINDOW_MIN` ist als Vorgabe voellig richtig - aber nur
     * dort, wo eine Einstellung FEHLT (der Rueckfall in `FuseCycleRunner`).
     * In einer Anzeige ist sie immer falsch, sobald der Nutzer etwas anderes
     * eingestellt hat.
     */
    @Test
    fun `die Anzeige rechnet nicht gegen die Vorgabe-Konstante`() {
        val src = datei(
            "src/main/kotlin/app/aaps/fuse/plugin/FuseDashboardModel.kt",
            "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FuseDashboardModel.kt",
        ).readText()
        // KOMMENTARE RAUS, bevor gesucht wird: die Begruendung der Aenderung
        // nennt den alten Namen zwangslaeufig, und ein Waechter, der an der
        // Dokumentation seines eigenen Anlasses scheitert, ist unbrauchbar.
        val code = src.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString(" ")
        assertFalse(Regex("""PrimeRelease\.WINDOW_MIN""").containsMatchIn(code)) {
            "FuseDashboardModel rechnet gegen die Vorgabe statt gegen die Einstellung"
        }
        assertTrue(src.contains("primeWindowMin")) {
            "die eingestellte Fensterdauer wird nicht gelesen"
        }
    }

    /**
     * Die Beschreibung der Freigabe-Einstellung darf ihre eigene Fensterdauer
     * nicht als Zahl behaupten - sie ist ein statischer Text ohne Parameter und
     * kann der Einstellung darunter nie folgen.
     */
    @Test
    fun `die Freigabe-Beschreibung behauptet keine feste Dauer`() {
        val zeile = pluginStrings().lines()
            .first { it.contains("fuse_prime_release_summary\"") }
        assertFalse(Regex("""nur \d+ Minuten""").containsMatchIn(zeile)) {
            "feste Minutenangabe in einer statischen Beschreibung: $zeile"
        }
    }
}

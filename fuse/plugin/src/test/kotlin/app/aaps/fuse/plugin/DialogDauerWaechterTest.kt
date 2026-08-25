package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DER WAECHTER GEGEN FESTE ZAHLEN IN TEXTEN, DIE VON EINSTELLUNGEN ABHAENGEN.
 *
 * ANLASS (Toni am Geraet, 17.08.2026): der Marker-Dialog sagte "0,23 U sofort,
 * bis 3,50 U in 15 min", waehrend das Freigabe-Fenster auf 25 Minuten stand.
 * Die "15" stand fest im Ressourcen-String.
 *
 * WARUM DAS MEHR IST ALS EIN SCHOENHEITSFEHLER: der Satz nennt eine MENGE und
 * eine ZEIT. Stimmt die Zeit nicht, ist die genannte Menge im falschen Zeitraum
 * gedacht - 3,50 U in 15 Minuten sind etwas anderes als 3,50 U in 25. Und auf
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
     * Der Dialogtext muss die Dauer als PARAMETER fuehren, nicht als Ziffer.
     */
    @Test
    fun `der Marker-Dialog nennt die Dauer als Parameter`() {
        // Seit dem UI-P0 vom 25.08. steht eine Zeile JE ANTEIL. Beide
        // Zeilen mit Zeitangabe muessen die Dauer als PARAMETER fuehren -
        // die verteilte Phase A (Freigabe-Fenster) und das Fundament
        // (Fundament-Ende).
        listOf(
            "overview_fuse_meal_confirm_spread\"" to "%2\$d",
            "overview_fuse_meal_confirm_foundation\"" to "%2\$d",
        ).forEach { (schluessel, parameter) ->
            val zeile = uiStrings().lines().first { it.contains(schluessel) }
            assertTrue(zeile.contains(parameter)) {
                "die Dauer fehlt als Parameter - der Text kann die Einstellung nicht abbilden: $zeile"
            }
            assertFalse(Regex("""\d+\s*min""").containsMatchIn(zeile)) {
                "eine feste Minutenzahl im Dialogtext: $zeile"
            }
        }
    }

    /**
     * DIE MENGEN SIND ANFORDERUNGEN, keine Zusagen (UI-P0 25.08.).
     *
     * Der Dialog nannte "0,27 U" aus der alten Prime-Schrittrechnung,
     * waehrend bei Sofortanteil 1,0 in Wahrheit der ganze Phase-A-Betrag
     * unmittelbar angefordert wird. Seither zeigt er die Anteile der
     * gepinnten Autorisierung - und der positive Knopf muss benennen,
     * dass hier INSULIN freigegeben wird.
     */
    @Test
    fun `der Dialog benennt Anforderung und Insulinfreigabe`() {
        val s = uiStrings()
        assertTrue(s.contains("overview_fuse_meal_confirm_upfront")) { "die Sofort-Zeile fehlt" }
        assertTrue(s.contains("overview_fuse_meal_confirm_total")) { "das Gesamtlimit fehlt" }
        val sofort = s.lines().first { it.contains("overview_fuse_meal_confirm_upfront\"") }
        // "VORGESEHEN" oder "angefordert" - nie eine Zusage. Steht beim
        // Druck ein Abwaertsriegel, wird der Sofortanteil AUFGESCHOBEN und
        // in diesem Zyklus 0 U angefordert; der Dialog darf die Anforderung
        // deshalb nicht behaupten (Tonis Korrektur 25.08. abends).
        assertTrue(sofort.contains("vorgesehen") || sofort.contains("angefordert")) {
            "die Menge darf nicht als sichere Abgabe benannt sein: $sofort"
        }
        assertTrue(s.contains("overview_fuse_meal_confirm_deferred")) {
            "der Zustandshinweis fuer den aufgeschobenen Batch fehlt"
        }
        val freigabe = s.lines().first { it.contains("fuse_meal_confirm_release\"") }
        assertTrue(freigabe.contains("Insulin")) {
            "der positive Knopf muss die Insulinfreigabe benennen: $freigabe"
        }
        val null0 = s.lines().first { it.contains("fuse_meal_confirm_no_prime\"") }
        assertTrue(null0.contains("0 U")) {
            "die Nullwahl muss unmissverstaendlich 0 U nennen: $null0"
        }
    }

    /**
     * Und es muss eine Fassung OHNE Dauer geben. Ist das Fenster unbekannt,
     * waere eine erfundene Zahl schlimmer als keine - dieselbe Regel wie
     * ueberall im Projekt: fehlende Daten heissen UNBEKANNT, nie ein Wert.
     */
    @Test
    fun `es gibt eine Fassung ohne Dauer`() {
        assertTrue(uiStrings().contains("overview_fuse_meal_confirm_spread_no_window")) {
            "die Fassung ohne bekanntes Fenster fehlt"
        }
    }

    /**
     * KEINE ANZEIGE DARF GEGEN DIE VORGABE-KONSTANTE RECHNEN.
     *
     * Zweiter Geraetefund am selben Tag: der FUSE-Reiter zeigte "15/15 min
     * Freigabe" bei eingestelltem 25-Minuten-Fenster - und darunter in
     * derselben Karte "Prime 1,15 U offen". Die Zeile widersprach sich selbst,
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

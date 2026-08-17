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
        val zeile = uiStrings().lines()
            .first { it.contains("overview_fuse_meal_confirm_body\"") }
        assertTrue(zeile.contains("%3\$d")) {
            "die Dauer fehlt als Parameter - der Text kann die Einstellung nicht abbilden: $zeile"
        }
        assertFalse(Regex("""\d+\s*min""").containsMatchIn(zeile)) {
            "eine feste Minutenzahl im Dialogtext: $zeile"
        }
    }

    /**
     * Und es muss eine Fassung OHNE Dauer geben. Ist das Fenster unbekannt,
     * waere eine erfundene Zahl schlimmer als keine - dieselbe Regel wie
     * ueberall im Projekt: fehlende Daten heissen UNBEKANNT, nie ein Wert.
     */
    @Test
    fun `es gibt eine Fassung ohne Dauer`() {
        assertTrue(uiStrings().contains("overview_fuse_meal_confirm_body_no_window")) {
            "die Fassung ohne bekanntes Fenster fehlt"
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

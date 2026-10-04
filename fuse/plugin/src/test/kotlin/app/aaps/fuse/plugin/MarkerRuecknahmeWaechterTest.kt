package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * WAECHTER UEBER DIE RUECKGAENGIG-FRIST DER MARKER-RUECKNAHME (Toni 04.10.).
 *
 * Ein einzelner Tipp nahm den laufenden Marker bisher sofort zurueck, auch
 * mitten in einer Mahlzeit. Die Fix-Logik selbst prueft
 * `MarkerRuecknahmeTest` (fuse/core). Hier steht die Verdrahtung, die ein
 * Umbau oder Merge still verlieren koennte:
 *
 * 1. Beide Knoepfe (Uebersicht und FUSE-Tab) merken die Ruecknahme nur VOR -
 *    im Ruecknahme-Zweig steht kein direktes Umschalten.
 * 2. Der Zyklus holt eine faellige Vormerkung nach; sonst verschluckte ein
 *    Prozessende in der Frist eine gewollte Ruecknahme.
 *
 * Statisch wie [FusePluginLaufSperreWaechterTest]: ein Laufzeittest
 * braeuchte Fragment, Activity und das ganze Plugin.
 */
class MarkerRuecknahmeWaechterTest {

    private fun quelle(vararg kandidaten: String): String {
        val f = kandidaten.map { File(it) }.firstOrNull { it.exists() }
        requireNotNull(f) { "Datei nicht gefunden: ${kandidaten.toList()}" }
        // Kommentare raus, damit eine blosse Erwaehnung nicht als Aufruf zaehlt.
        return f.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }
    }

    private val plugin get() = quelle(
        "src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
        "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
    )

    private val knoepfe = mapOf(
        "FUSE-Tab" to arrayOf(
            "src/main/kotlin/app/aaps/fuse/plugin/FuseFragment.kt",
            "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FuseFragment.kt",
        ),
        "Uebersicht" to arrayOf(
            "../../plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewFragment.kt",
            "plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewFragment.kt",
        ),
    )

    @Test
    fun `beide Marker-Knoepfe merken die Ruecknahme nur vor`() {
        knoepfe.forEach { (name, pfade) ->
            val code = quelle(*pfade)
            val start = code.indexOf("if (fakten == null)")
            assertTrue(start >= 0) { "$name: Ruecknahme-Zweig (fakten == null) nicht gefunden - greift der Waechter?" }
            val ende = code.indexOf("FuseMarkerRuecknahmeLeiste.zeige(", start)
            assertTrue(ende > start) { "$name: keine Rueckgaengig-Leiste im Ruecknahme-Zweig" }
            val zweig = code.substring(start, ende)
            assertTrue(zweig.contains("RuecknahmeVormerken(")) { "$name: die Ruecknahme wird nicht vorgemerkt" }
            assertFalse(zweig.contains("umschalten.run()")) {
                "$name: der Ruecknahme-Zweig schaltet direkt um - ohne laufenden Marker setzte das einen neuen"
            }
        }
    }

    @Test
    fun `der Zyklus holt eine faellige Ruecknahme nach`() {
        val code = plugin
        val start = Regex("""private\s+fun\s+invokeUnterSperre\s*\(""").find(code)?.range?.first ?: -1
        assertTrue(start >= 0) { "invokeUnterSperre nicht gefunden" }
        val ende = Regex("""\n {4}(?:(?:private|override|internal|public)\s+)?fun\s""").find(code, start + 1)?.range?.first
            ?: code.length
        assertTrue(code.substring(start, ende).contains("faelligeRuecknahme(")) {
            "Der Zyklus prueft keine faellige Marker-Ruecknahme - ein Prozessende in der Frist verschluckte sie."
        }
    }
}

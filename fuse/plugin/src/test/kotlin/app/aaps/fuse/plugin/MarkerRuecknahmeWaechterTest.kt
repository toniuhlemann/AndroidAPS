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
 * 1. Jeder Marker-Knopf merkt die Ruecknahme nur VOR - im Ruecknahme-Zweig
 *    steht kein direktes Umschalten.
 * 2. Der Zyklus holt eine faellige Vormerkung nach; sonst verschluckte ein
 *    Prozessende in der Frist eine gewollte Ruecknahme.
 *
 * v4: der FUSE-Reiter ist Compose ([FuseComposeContent]). Den Knopf der
 * Uebersicht gibt es in v4 noch nicht; sobald eine Datei ausserhalb von FUSE
 * `fuseMarkerToggle(` ruft, prueft dieser Waechter sie mit (siehe
 * [weitere Knoepfe werden mitgeprueft]).
 *
 * Statisch wie [FusePluginLaufSperreWaechterTest]: ein Laufzeittest
 * braeuchte die Compose-Oberflaeche und das ganze Plugin.
 */
class MarkerRuecknahmeWaechterTest {

    private fun ohneKommentare(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    private fun quelle(vararg kandidaten: String): String {
        val f = kandidaten.map { File(it) }.firstOrNull { it.exists() }
        requireNotNull(f) { "Datei nicht gefunden: ${kandidaten.toList()}" }
        // Kommentare raus, damit eine blosse Erwaehnung nicht als Aufruf zaehlt.
        return ohneKommentare(f.readText())
    }

    private val plugin get() = quelle(
        "src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
        "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
    )

    private val reiter get() = quelle(
        "src/main/kotlin/app/aaps/fuse/plugin/FuseComposeContent.kt",
        "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FuseComposeContent.kt",
    )

    /** Der Ruecknahme-Zweig eines Knopfs: ab `if (fakten == null)` bis zum `else`, das die Rueckfrage zeigt. */
    private fun ruecknahmeZweig(name: String, code: String): String {
        val start = Regex("""if\s*\(\s*fakten\s*==\s*null\s*\)""").find(code)?.range?.first ?: -1
        assertTrue(start >= 0) { "$name: Ruecknahme-Zweig (fakten == null) nicht gefunden - greift der Waechter?" }
        val ende = Regex("""}\s*else\b""").find(code, start)?.range?.first ?: -1
        assertTrue(ende > start) { "$name: kein else-Zweig nach dem Ruecknahme-Zweig" }
        return code.substring(start, ende)
    }

    private fun pruefeKnopf(name: String, code: String) {
        val zweig = ruecknahmeZweig(name, code)
        assertTrue(zweig.contains("RuecknahmeVormerken(")) { "$name: die Ruecknahme wird nicht vorgemerkt" }
        assertFalse(Regex("""(toggleMealMarker|fuseMarkerToggle)\s*\(""").containsMatchIn(zweig)) {
            "$name: der Ruecknahme-Zweig schaltet direkt um - ohne laufenden Marker setzte das einen neuen"
        }
    }

    @Test
    fun `der Marker-Knopf des Reiters merkt die Ruecknahme nur vor`() {
        pruefeKnopf("FUSE-Reiter", reiter)
    }

    /**
     * Knoepfe ausserhalb von FUSE (in v4 bisher keiner): jede Datei, die
     * `fuseMarkerToggle(` ruft, muss die Ruecknahme genauso nur vormerken.
     */
    @Test
    fun `weitere Knoepfe werden mitgeprueft`() {
        val wurzel = listOf(File("../.."), File(".")).firstOrNull { File(it, "settings.gradle").exists() }
        requireNotNull(wurzel) { "Projektwurzel nicht gefunden" }
        val knoepfe = listOf("ui/src", "plugins/main/src", "app/src")
            .map { File(wurzel, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("${File.separator}build${File.separator}") }.toList() }
            .map { it to ohneKommentare(it.readText()) }
            .filter { (_, code) -> Regex("""\bfuseMarkerToggle\s*\(""").containsMatchIn(code) }
        knoepfe.forEach { (datei, code) -> pruefeKnopf(datei.name, code) }
    }

    @Test
    fun `der Zyklus holt eine faellige Ruecknahme nach`() {
        val code = plugin
        val start = Regex("""private\s+suspend\s+fun\s+invokeUnterSperre\s*\(""").find(code)?.range?.first ?: -1
        assertTrue(start >= 0) { "invokeUnterSperre nicht gefunden" }
        val ende = Regex("""\n {4}(?:(?:private|override|internal|public)\s+)?(?:suspend\s+)?fun\s""").find(code, start + 1)?.range?.first
            ?: code.length
        val lauf = code.substring(start, ende)
        assertTrue(lauf.contains("faelligeRuecknahmeUnterSperre(")) {
            "Der Zyklus prueft keine faellige Marker-Ruecknahme - ein Prozessende in der Frist verschluckte sie."
        }
        // v4: die Sperre ist nicht wiedereintrittsfaehig - die sperrende Fassung haengte den Zyklus auf.
        assertFalse(Regex("""\bfaelligeRuecknahme\s*\(""").containsMatchIn(lauf)) {
            "Der Zyklus ruft die SPERRENDE Fassung - er haelt die Sperre schon, das waere ein Deadlock."
        }
    }
}

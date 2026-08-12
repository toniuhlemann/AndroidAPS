package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * WAECHTER: ein `AdjustedInterval` entsteht NUR ueber die Fabrik.
 *
 * Die Zusicherung, die daran haengt, ist die teuerste der ganzen Stufe:
 * beide Punkte muessen aus DERSELBEN `adjust()`-Ausgabe stammen. `adjust()`
 * setzt `cumulativeBgi` am Fensteranfang auf 0, und der wandert jede Minute -
 * zwei Werte aus zwei Zyklen zu subtrahieren ergibt keine Stoerung, sondern
 * den Unterschied zweier Nullpunkte. Genau dieser Fehler steckte bis zum
 * 12.08. im Produktionspfad, und er sah plausibel aus.
 *
 * `AdjustedInterval.of(punkte, ankerTs)` macht ihn strukturell unmoeglich: sie
 * bekommt EINE Liste und sucht beide Punkte darin. Der KONSTRUKTOR kann das
 * nicht - er nimmt drei lose Zahlen.
 *
 * WARUM ALS QUELLTEXT-WAECHTER: die Zusicherung gilt fuer JEDEN kuenftigen
 * Aufrufer, nicht nur fuer den heutigen. Ein Laufzeittest kann nur pruefen,
 * was schon aufgerufen wird; dieser hier faellt auf, sobald jemand einen
 * zweiten Weg baut - auch in einer Lage, an die niemand gedacht hat.
 */
class AdjustedIntervalSingleWriterTest {

    /**
     * DIE SICHTBARKEITEN SIND DER VERTRAG - nicht dieser Test.
     *
     * Seit dem 12.08. traegt ihn der Typ: `AdjustedInterval` hat einen
     * PRIVATEN Konstruktor, `AdjustedSeries` einen INTERNAL. Ausserhalb von
     * `fuse:core` kann niemand eine Serie bauen, und niemand ueberhaupt ein
     * Intervall ausser der Fabrik. Dieser Test bewacht nur, dass die
     * Sichtbarkeiten nicht wieder aufgeweicht werden - er ersetzt sie nicht.
     */
    @Test
    fun `die Sichtbarkeiten tragen den Vertrag`() {
        val quelle = kernQuellen().first { it.name == "BgiAdjustedSeries.kt" }
        val text = quelle.readText()
        assertTrue(text.contains("class AdjustedInterval private constructor(")) {
            "der Konstruktor muss privat bleiben - sonst ist die Fabrik nur eine Empfehlung"
        }
        assertTrue(text.contains("class AdjustedSeries internal constructor(")) {
            "die Serie darf ausserhalb des Moduls nicht baubar sein - sonst laesst sie sich " +
                "aus zwei adjust()-Ausgaben zusammensetzen"
        }
        assertFalse(text.contains("data class AdjustedInterval")) {
            "keine data class: ihr copy() waere ein zweiter Bauweg am privaten Konstruktor vorbei"
        }
    }

    @Test
    fun `nur die Fabrik baut Intervalle im Produktionscode`() {
        val quellen = mainSources()
        // Ohne Untergrenze waere ein leerer Scan still gruen.
        assertTrue(quellen.size >= 20) { "nur ${quellen.size} Quelldateien - der Scan greift ins Leere" }

        val direkt = buildList {
            for (f in quellen) {
                ohneKommentare(f.readText()).lines().forEachIndexed { i, zeile ->
                    // Der KONSTRUKTORAUFRUF. Die Fabrik heisst `.of(` und
                    // wird davon nicht erfasst.
                    if (Regex("""AdjustedInterval\s*\(""").containsMatchIn(zeile))
                        add("${f.name}:${i + 1}: ${zeile.trim()}")
                }
            }
        }

        assertTrue(direkt.isEmpty()) {
            "AdjustedInterval darf im Produktionscode nur ueber die Fabrik entstehen - " +
                "sie erzwingt, dass beide Punkte aus derselben adjust()-Ausgabe kommen. Gefunden: $direkt"
        }
    }

    /** Und die Fabrik wird auch wirklich benutzt - sonst waere die Zusicherung
     *  oben dadurch erfuellt, dass es gar keine Intervalle gibt. */
    @Test
    fun `die Fabrik hat genau einen Aufrufer im Produktionscode`() {
        val treffer = mainSources().flatMap { f ->
            ohneKommentare(f.readText()).lines().withIndex()
                .filter { (_, z) -> z.contains("AdjustedInterval.of(") }
                .map { (i, _) -> "${f.name}:${i + 1}" }
        }
        assertEquals(1, treffer.size) { "erwartet: genau der Zyklus. Gefunden: $treffer" }
    }

    private fun kernQuellen(): List<File> {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").exists() && !File(dir, "settings.gradle.kts").exists())
            dir = dir.parentFile
        if (dir == null) fail<Unit>("Projektwurzel nicht gefunden")
        val src = File(dir, "fuse/core/src/main/kotlin")
        if (!src.isDirectory) fail<Unit>("Quellverzeichnis fehlt: $src")
        return src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun mainSources(): List<File> {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").exists() && !File(dir, "settings.gradle.kts").exists())
            dir = dir.parentFile
        if (dir == null) fail<Unit>("Projektwurzel nicht gefunden")
        return listOf(File(dir, "fuse/plugin/src/main/kotlin"), File(dir, "fuse/core/src/main/kotlin"))
            .flatMap { wurzel ->
                if (!wurzel.isDirectory) fail<List<File>>("Quellverzeichnis fehlt: $wurzel")
                wurzel.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            // Die Definition selbst enthaelt den Konstruktor naturgemaess.
            .filterNot { it.name == "BgiAdjustedSeries.kt" }
    }

    private fun ohneKommentare(src: String): String {
        var imBlock = false
        return src.lineSequence().map { zeile ->
            val t = zeile.trim()
            when {
                imBlock                                -> { if (t.contains("*/")) imBlock = false; "" }
                t.startsWith("/*")                     -> { if (!t.contains("*/")) imBlock = true; "" }
                t.startsWith("//") || t.startsWith("*") -> ""
                else                                   -> zeile
            }
        }.joinToString("\n")
    }
}

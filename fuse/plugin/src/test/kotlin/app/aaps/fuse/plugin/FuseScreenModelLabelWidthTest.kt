package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * WAECHTER: keine Beschriftung des FUSE-Schirms erreicht die Spaltenbreite.
 *
 * `row()` setzt die Beschriftung mit `padEnd(16)` und haengt den Wert direkt
 * an. Bei genau 16 Zeichen ist das Ergebnis KEIN Abstand - am 12.08.2026
 * stand deshalb "Evidence-EpisodeNICHT eroeffnet" auf dem Testgeraet.
 *
 * WARUM ALS QUELLTEXT-WAECHTER UND NICHT ALS RENDER-TEST: die Zusicherung
 * gilt fuer JEDE Beschriftung, auch fuer die, die nur in einer seltenen Lage
 * erscheint. Ein Render-Test muesste jede dieser Lagen herstellen, und die
 * naechste zu lange Beschriftung waere genau die, an die niemand gedacht hat.
 *
 * DER ERSTE ANLAUF WAR EIN PLACEBO, und das gehoert hierher: er rendert einen
 * Schirm und suchte klebende Zeilen. Nach dem Kuerzen der Beschriftung auf 15
 * Zeichen konnte er nicht mehr fehlschlagen - die Mutationsprobe (Abstand
 * wieder entfernen) blieb gruen. Eine Zusicherung, die ihre eigene Verletzung
 * nicht mehr herstellen kann, ist keine.
 */
class FuseScreenModelLabelWidthTest {

    /** Muss zu `padEnd(16)` in `FuseScreenModel.row` passen. */
    private val spaltenbreite = 16

    @Test
    fun `keine Beschriftung erreicht die Spaltenbreite`() {
        val quelle = quelldatei()
        val text = quelle.readText()
        assertTrue(text.contains("padEnd($spaltenbreite)")) {
            "die Spaltenbreite hat sich geaendert - diesen Waechter mitziehen"
        }

        val beschriftungen = Regex("""row\(b,\s*"([^"]*)"""").findAll(text).map { it.groupValues[1] }.toList()
        // Ohne Untergrenze waere "nichts gefunden" still gruen.
        assertTrue(beschriftungen.size >= 20) { "nur ${beschriftungen.size} Beschriftungen gefunden" }

        val zulang = beschriftungen.filter { it.length >= spaltenbreite }
        assertTrue(zulang.isEmpty()) {
            "diese Beschriftungen kleben am Wert (>= $spaltenbreite Zeichen): $zulang"
        }
    }

    private fun quelldatei(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").exists() && !File(dir, "settings.gradle.kts").exists())
            dir = dir.parentFile
        if (dir == null) fail<Unit>("Projektwurzel nicht gefunden")
        val f = File(dir, "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FuseScreenModel.kt")
        if (!f.isFile) fail<Unit>("Quelldatei fehlt: $f")
        return f
    }
}

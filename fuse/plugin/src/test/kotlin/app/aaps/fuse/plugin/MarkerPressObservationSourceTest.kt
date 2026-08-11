package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * WAECHTER: die Prozess-Beobachtung des Markerdrucks hat GENAU EINE Quelle.
 *
 * `markerPressObservedTs` beantwortet eine einzige Frage - lag der Druck NACH
 * dem letzten Prozessstart? Nur wenn ja, darf er eine Evidenz-Episode
 * eroeffnen (s. `MarkerEpisodeGate`). Genau EIN Ort weiss das mit Sicherheit:
 * `toggleMealMarker`.
 *
 * DIE FALLE, gegen die dieser Test steht, ist der Warmstart des
 * Marker-Rings. Er fuellt sich nach einem Neustart aus dem Trail nach; wer
 * dort - gut gemeint, weil "der Ring soll vollstaendig sein" - auch die
 * Beobachtung mitsetzt, macht einen Druck von vor zwei Stunden zu einem
 * Ereignis dieses Prozesses. Der Anker im Ledger faengt das NICHT auf: er
 * kennt nur Druecke, die schon einmal eine Episode eroeffnet haben, und
 * genau dieser hatte noch keine.
 *
 * WARUM STATISCH UND NICHT ALS LAUFZEITTEST: der Warmstart haengt an
 * Trail-Dateien, Threads und Preferences; ein Laufzeittest dafuer waere
 * teuer und traege die Zusicherung trotzdem nur fuer den einen nachgebauten
 * Pfad. Die Zusicherung ist aber eine ueber den QUELLTEXT: es gibt keinen
 * zweiten Schreiber. Das ist statisch pruefbar und bleibt es auch, wenn der
 * Warmstart einmal umgebaut wird.
 */
class MarkerPressObservationSourceTest {

    private val feld = "markerPressObservedTs"

    @Test
    fun `nur toggleMealMarker setzt die Beobachtung`() {
        val quelle = File(pluginSrc(), "FusePlugin.kt")
        assertTrue(quelle.isFile) { "FusePlugin.kt nicht gefunden: $quelle" }

        val zeilen = ohneKommentare(quelle.readText()).lines()
        // Ohne diese Untergrenze waere ein leerer Scan still gruen.
        assertTrue(zeilen.size >= 200) { "nur ${zeilen.size} Zeilen - der Scan greift ins Leere" }

        // Deklaration und Lesezugriffe sind keine Schreiber.
        val zuweisung = Regex("""(^|[^.\w])$feld\s*=[^=]""")
        val schreiber = zeilen.withIndex().filter { (_, z) ->
            zuweisung.containsMatchIn(z) && !z.contains("private var $feld")
        }

        val inToggle = schreiber.filter { (i, _) -> imBlock(zeilen, i, "fun toggleMealMarker(") }
        val fremde = schreiber - inToggle.toSet()

        assertTrue(fremde.isEmpty()) {
            "$feld darf nur in toggleMealMarker geschrieben werden - sonst kann " +
                "ein Druck aus einem frueheren Prozess als beobachtet gelten. " +
                "Gefunden ausserhalb: " + fremde.joinToString { "Zeile ${it.index + 1}: ${it.value.trim()}" }
        }
        // Beide Zweige: armen setzt, zuruecknehmen loescht.
        assertEquals(2, inToggle.size, "erwartet: armen setzt, Ruecknahme loescht")
    }

    /** Und er darf nicht persistiert werden - dann waere er nur ein zweiter
     *  Name fuer den Preference-Wert und beantwortete die Frage nicht mehr. */
    @Test
    fun `die Beobachtung wird nirgends persistiert`() {
        val treffer = pluginSrc().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                ohneKommentare(f.readText()).lines().withIndex()
                    .filter { (_, z) -> z.contains(feld) && (z.contains("preferences.put") || z.contains(".put(")) }
                    .map { (i, z) -> "${f.name}:${i + 1}: ${z.trim()}" }
            }.toList()

        assertTrue(treffer.isEmpty()) { "$feld darf nicht persistiert werden: $treffer" }
    }

    /** Grobe, aber ausreichende Blockerkennung: von der Signaturzeile bis zur
     *  Zeile, die auf derselben Einrueckung mit `}` schliesst. */
    private fun imBlock(zeilen: List<String>, index: Int, signatur: String): Boolean {
        val start = zeilen.indexOfFirst { it.contains(signatur) }
        if (start < 0 || index < start) return false
        val einrueckung = zeilen[start].takeWhile { it == ' ' }.length
        for (i in start + 1..index) {
            val z = zeilen[i]
            if (z.trim() == "}" && z.takeWhile { it == ' ' }.length == einrueckung) return false
        }
        return true
    }

    private fun pluginSrc(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").exists() && !File(dir, "settings.gradle.kts").exists())
            dir = dir.parentFile
        if (dir == null) fail<Unit>("Projektwurzel nicht gefunden")
        val src = File(dir, "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin")
        if (!src.isDirectory) fail<Unit>("Quellverzeichnis fehlt: $src")
        return src
    }

    private fun ohneKommentare(src: String): String {
        var imBlock = false
        // ZEILENZAHL ERHALTEN - die Meldung nennt Zeilennummern, und eine
        // gefilterte Liste haette sie stillschweigend verschoben.
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

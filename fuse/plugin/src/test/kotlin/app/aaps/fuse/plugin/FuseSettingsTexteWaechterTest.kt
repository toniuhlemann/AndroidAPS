package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * WAECHTER UEBER DIE ERKLAERTEXTE DER EINSTELLUNGEN (Toni 24.11.2026).
 *
 * Die erste Zeile jeder Erklaerung sagt, wofuer der Wert gilt; danach folgt der ausfuehrliche Text, bei einem
 * umbenannten Wert am Ende "Bisher: ..." fuer die Suche. Mehrere Werte koennen sich eine Erklaerung teilen (die vier
 * Kurvenpunkte, die beiden Rampenkanten) - beim Erzeugen der Texte stand die Geltungszeile dort zuerst mehrfach. Dieser
 * Test liest die Textdatei selbst und haelt fest: kein Absatz steht doppelt, und "Bisher" steht hoechstens einmal und
 * am Ende.
 */
class FuseSettingsTexteWaechterTest {

    private fun texte(): Map<String, String>? {
        val kandidaten = listOf(
            "src/main/res/values/strings.xml",
            "fuse/plugin/src/main/res/values/strings.xml",
        )
        val f = kandidaten.map { File(it) }.firstOrNull { it.exists() } ?: return null
        val muster = Regex("<string name=\"(\\w+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        return muster.findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun erklaerungen(): Map<String, String> {
        val alle = texte()
        assumeTrue(alle != null, "strings.xml von hier aus nicht erreichbar")
        return alle!!.filterKeys { it.endsWith("_summary") }
    }

    /** Die Absaetze einer Erklaerung, wie sie im Text getrennt sind. */
    private fun absaetze(text: String): List<String> = text.split("\\n\\n").map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `der Waechter liest die Texte ueberhaupt`() {
        val e = erklaerungen()
        assertTrue(e.size >= 100) { "nur ${e.size} Erklaerungen gelesen - der Waechter waere blind" }
        assertTrue(e.keys.any { it == "fuse_ramp_summary" }) { "die geteilte Erklaerung der Rampe fehlt" }
    }

    @Test
    fun `kein Absatz einer Erklaerung steht doppelt`() {
        val doppelt = erklaerungen().filterValues { t -> absaetze(t).let { it.size != it.toSet().size } }.keys
        assertTrue(doppelt.isEmpty()) { "doppelter Absatz in: $doppelt" }
    }

    @Test
    fun `der alte Titel steht hoechstens einmal und am Ende`() {
        val falsch = erklaerungen().filterValues { t ->
            val a = absaetze(t)
            val mit = a.filter { it.startsWith("Bisher: ") }
            mit.size > 1 || (mit.size == 1 && a.last() != mit.single())
        }.keys
        assertTrue(falsch.isEmpty()) { "\"Bisher\" nicht am Ende oder mehrfach in: $falsch" }
    }
}

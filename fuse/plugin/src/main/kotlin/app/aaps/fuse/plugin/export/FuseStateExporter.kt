package app.aaps.fuse.plugin.export

import java.io.File

/**
 * Schreibt den Zyklus-Trail. Eine Datei, append-only, eine Zeile je Zyklus.
 *
 * SYNCHRON, und das ist eine Entscheidung mit Begruendung: derselbe Mechanismus
 * laeuft im Fork seit dem 26.06. je Loop-Lauf auf demselben 1-min-Geraet im
 * selben Prozess. Einen Schreiberthread mit Queue, Verwurfspolitik und einer
 * Verlustluecke beim Prozess-Kill gegen eine UNGEMESSENE Latenzfurcht zu bauen,
 * waere genau die Sorte Vorbau, die dieses Projekt vermeiden will. Stattdessen
 * misst der Export sich selbst — `buildMs` im eigenen Datensatz, `writeMs` im
 * naechsten. Wenn die Zahlen es rechtfertigen, kommt der Thread danach.
 *
 * Das VERZEICHNIS wird hereingereicht und nicht aus `Environment` geholt. Im
 * Unit-Test liefert `Environment.getExternalStorageDirectory()` null, und
 * `File(null, "...")` waere ein RELATIVER Pfad — die Gegenprobe liefe dann am
 * Modulverzeichnis vorbei statt im Testverzeichnis.
 *
 * Kein Wurf nach aussen: jeder Fehler wird gemeldet, nie geworfen. Der Export
 * ist Beobachtung, er darf den Regler nicht anhalten.
 */
class FuseStateExporter(
    private val maxBytes: Long = MAX_BYTES,
    private val generations: Int = GENERATIONS,
) {

    companion object {

        const val FILE_NAME = "fuse_state_history.jsonl"

        /**
         * VORLAEUFIG. Die Groesse eines Datensatzes haengt am
         * Haftungshorizont (bis 360 Punkte moeglich) und ist noch nicht
         * gemessen — `export.prevBytes` im Trail liefert sie ab dem zweiten
         * Zyklus. Erst danach ist diese Zahl mehr als eine Schaetzung.
         */
        const val MAX_BYTES = 8L * 1024 * 1024
        const val GENERATIONS = 3
    }

    sealed interface Result {
        data class Written(val writeMs: Long, val bytes: Int, val rotated: Boolean) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * @param line EINE Zeile ohne Zeilenumbruch.
     * @param nowNs Zeitquelle, damit die Messung im Test bestimmbar bleibt.
     */
    fun append(dir: File, line: String, nowNs: () -> Long = System::nanoTime): Result {
        val start = nowNs()
        return try {
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return Result.Failed("mkdirs failed: $dir")
            val target = File(dir, FILE_NAME)
            val rotated = rotateIfNeeded(target)
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            target.appendBytes(bytes)
            // Der Viewer liest ueber SAF und cached auf der mtime. Ein Anhaengen
            // aktualisiert sie auf emulated storage nicht zuverlaessig — deshalb
            // ausdruecklich setzen, sonst sieht der Leser eine eingefrorene
            // Datei mit frischem Inhalt.
            target.setLastModified(System.currentTimeMillis())
            Result.Written((nowNs() - start) / 1_000_000, bytes.size, rotated)
        } catch (e: Exception) {
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Rotiert VOR dem Anhaengen: so bleibt die aktive Datei immer unter der
     *  Grenze, statt sie einmal zu ueberschreiten. */
    private fun rotateIfNeeded(target: File): Boolean {
        if (!target.exists() || target.length() < maxBytes) return false
        // Von hinten nach vorn, sonst ueberschreibt jede Generation die naechste.
        File(target.parentFile, "$FILE_NAME.$generations").delete()
        for (i in generations - 1 downTo 1) {
            val from = File(target.parentFile, "$FILE_NAME.$i")
            if (from.exists()) from.renameTo(File(target.parentFile, "$FILE_NAME.${i + 1}"))
        }
        target.renameTo(File(target.parentFile, "$FILE_NAME.1"))
        return true
    }
}

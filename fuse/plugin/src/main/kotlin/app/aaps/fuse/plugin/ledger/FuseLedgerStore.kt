package app.aaps.fuse.plugin.ledger

import java.io.File

/**
 * Persistenz des Commitment-Ledgers: EINE Datei, atomar ersetzt, eine
 * .bak-Generation (Audit R95, Fix 3).
 *
 * ATOMAR heisst hier: erst vollstaendig in `.tmp` schreiben, dann die alte
 * Datei nach `.bak` wegdrehen, dann `.tmp` an den Zielnamen umbenennen. Ein
 * Prozess-Kill mitten im Schreiben hinterlaesst so nie eine halb
 * geschriebene Hauptdatei - schlimmstenfalls eine verwaiste `.tmp`, und die
 * naechste Lesung faellt auf die letzte vollstaendige Generation zurueck.
 *
 * SYNCHRON und NIE WERFEND, aus denselben Gruenden wie der Trail-Exporter
 * (s. export/FuseStateExporter): derselbe Mechanismus laeuft seit Wochen je
 * Loop-Lauf auf demselben Geraet; ein Schreiberthread waere Vorbau. Das
 * VERZEICHNIS wird hereingereicht - im Unit-Test liefert `Environment` null,
 * und ein relativer Pfad liefe am Testverzeichnis vorbei.
 */
class FuseLedgerStore {

    companion object {

        const val FILE_NAME = "fuse_ledger.json"
    }

    /** @return true, wenn die Hauptdatei nach dem Aufruf den neuen Inhalt
     *  traegt. false heisst: alte Generation steht unveraendert. */
    fun write(dir: File, content: String): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return@runCatching false
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        val bak = File(dir, "$FILE_NAME.bak")
        tmp.writeText(content, Charsets.UTF_8)
        if (target.exists()) {
            // Genau EINE Vorgenerationen-Kopie: die letzte vollstaendige.
            bak.delete()
            if (!target.renameTo(bak)) return@runCatching false
        }
        tmp.renameTo(target)
    }.getOrDefault(false)

    /**
     * Lesekandidaten in Vertrauensreihenfolge: Hauptdatei, dann `.bak`.
     * Der Aufrufer versucht das Decodieren der Reihe nach - eine korrupt
     * geschriebene Hauptdatei (Stromausfall zwischen den beiden Renames ist
     * unmoeglich, aber ein kaputtes Dateisystem nicht) faellt so auf die
     * letzte vollstaendige Generation zurueck statt auf einen leeren Start.
     */
    fun read(dir: File): List<String> = runCatching {
        listOf(File(dir, FILE_NAME), File(dir, "$FILE_NAME.bak"))
            .filter { it.exists() }
            .mapNotNull { f -> runCatching { f.readText(Charsets.UTF_8) }.getOrNull() }
    }.getOrDefault(emptyList())
}

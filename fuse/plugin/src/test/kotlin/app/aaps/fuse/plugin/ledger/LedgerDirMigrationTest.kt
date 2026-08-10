package app.aaps.fuse.plugin.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Migrations-Fault-Matrix (Codex R4-01 / Fault-Matrix F und J): der Sentinel
 * ist VERTRAGSBESTANDTEIL des Migrationsabschlusses. Ein "fertig" ohne
 * verifizierten Marker liesse bekannte Vorgeschichte nach einem zweiten
 * Dateiverlust als Erststart erscheinen - genau der REG-03-Pfad.
 *
 * TEST-FIRST dokumentiert: die Faelle "Kill nach Ziel-Rename",
 * "blockierter Sentinel" und "Sentinel-Nachzug beim Fruehausstieg" waren
 * ROT gegen den extrahierten Altstand (writeSentinelTolerant ohne
 * Erfolgswert, Fruehausstieg ohne Nachzug) und wurden erst danach gefixt.
 */
class LedgerDirMigrationTest {

    private val content = """{"revision":7}"""

    private fun target(dir: File) = File(dir, FuseLedgerStore.FILE_NAME)
    private fun bak(dir: File) = File(dir, FuseLedgerStore.FILE_NAME + ".bak")
    private fun sentinel(dir: File) = File(dir, FuseLedgerStore.SENTINEL_NAME)
    private fun migrated(dir: File) = File(dir, FuseLedgerStore.FILE_NAME + ".migrated")

    private fun dirs(root: File): Pair<File, File> {
        val old = File(root, "alt").also { it.mkdirs() }
        val neu = File(root, "neu")
        return old to neu
    }

    private fun tmp(dir: File) = File(dir, FuseLedgerStore.FILE_NAME + ".tmp")

    // ---- Die dritte Generation (Auditbefund 10.08.2026) --------------------

    /**
     * `.tmp` IST EINE VOLLWERTIGE GENERATION - und war von der Migration
     * ausgenommen.
     *
     * Nach einem Kill zwischen `target->bak` und `tmp->target` liegt die
     * NEUESTE Generation ausschliesslich als `.tmp` vor; genau deshalb
     * bewertet [FuseLedgerStore.readNewestValid] sie als Kandidaten (REG-01b).
     * Die Verzeichnismigration kannte aber nur zwei Namen.
     *
     * Die Richtung ist die schlimmstmoegliche: im Ziel laege danach eine
     * LESBARE, gueltige (aber AELTERE) Generation, also greift keine der vier
     * Hold-Quellen. Die juengste Zeile - typisch der zuletzt publizierte,
     * moeglicherweise schon abgegebene SMB - verschwaende still aus Haftung,
     * Headroom und Schwanz. Doppelfinanzierung ohne jede Meldung.
     */
    @Test
    fun `die tmp-Generation wandert mit`(@TempDir root: File) {
        val (old, neu) = dirs(root)
        // Der Kill-Zustand: KEIN target, nur die aeltere .bak und die juengere .tmp.
        bak(old).writeText("""{"revision":7}""")
        tmp(old).writeText("""{"revision":8}""")

        assertTrue(LedgerDirMigration.migrate(old, neu))

        assertTrue(tmp(neu).isFile) { "die juengste Generation darf nicht zurueckbleiben" }
        assertEquals("""{"revision":8}""", tmp(neu).readText())
        assertTrue(bak(neu).isFile)
        assertTrue(sentinel(neu).isFile)
        // Und im alten Verzeichnis ist sie aus dem Weg geraeumt.
        assertFalse(tmp(old).isFile) { "sonst wandert sie beim naechsten Lauf ein zweites Mal" }
    }

    /** Liegt NUR die .tmp vor, ist das kein Erststart - der Sentinel muss
     *  gesetzt werden, sonst saehe ein spaeterer Verlust wie ein Erststart aus. */
    @Test
    fun `auch eine alleinstehende tmp-Generation gilt als Vorgeschichte`(@TempDir root: File) {
        val (old, neu) = dirs(root)
        tmp(old).writeText("""{"revision":9}""")

        assertTrue(LedgerDirMigration.migrate(old, neu))

        assertTrue(tmp(neu).isFile)
        assertTrue(sentinel(neu).isFile) { "Vorgeschichte vorhanden - der Marker gehoert gesetzt" }
    }

    // ---- Normalpfad (Regression) ------------------------------------------

    @Test
    fun `normale Migration kopiert verifiziert, schreibt den Sentinel und rotiert das Alte`(@TempDir root: File) {
        val (old, neu) = dirs(root)
        target(old).writeText(content)
        bak(old).writeText("""{"revision":6}""")

        assertTrue(LedgerDirMigration.migrate(old, neu))
        assertEquals(content, target(neu).readText())
        assertEquals("""{"revision":6}""", bak(neu).readText())
        // Der Sentinel ist Teil des Abschlusses, keine Nebensache.
        assertTrue(sentinel(neu).isFile)
        // Das Alte ist rotiert, nicht geloescht.
        assertFalse(target(old).exists())
        assertTrue(migrated(old).exists())
    }

    /** Echter Erststart: nichts zu migrieren ist Erfolg - und darf KEINEN
     *  Sentinel hinterlassen, denn der wuerde beim naechsten Start einen
     *  Datenverlust behaupten, den es nie gab. */
    @Test
    fun `nichts zu migrieren ist Erfolg ohne Artefakte`(@TempDir root: File) {
        val (old, neu) = dirs(root)
        assertTrue(LedgerDirMigration.migrate(old, neu))
        assertFalse(sentinel(neu).exists())
        assertFalse(target(neu).exists())
    }

    /** Kopierfehler (Quelle unlesbar, hier: Verzeichnis statt Datei) meldet
     *  false und laesst das Alte unangetastet - der naechste invoke darf es
     *  erneut versuchen. */
    @Test
    fun `Kopierfehler meldet false und laesst das Alte stehen`(@TempDir root: File) {
        val (old, neu) = dirs(root)
        target(old).mkdirs() // Verzeichnis unter dem Dateinamen: readText wirft
        assertFalse(LedgerDirMigration.migrate(old, neu))
        assertTrue(target(old).exists())
        assertFalse(migrated(old).exists())
    }

    // ---- R4-01 (b): Kill nach Ziel-Rename, vor Sentinel -------------------

    /** DER Codex-Repro (Fault-Matrix F): das Ziel traegt die Generation,
     *  der Sentinel fehlt (Kill genau dazwischen). Der Neustart darf die
     *  Migration NICHT ohne Marker als fertig erklaeren - er zieht ihn
     *  verifiziert nach. */
    @Test
    fun `Kill nach Ziel-Rename vor Sentinel wird beim Neustart nachgezogen`(@TempDir root: File) {
        val (old, neu) = dirs(root)
        // Zustand nach dem Kill: Ziel kopiert, Sentinel fehlt, Altbestand
        // noch nicht rotiert.
        neu.mkdirs()
        target(neu).writeText(content)
        target(old).writeText(content)

        assertTrue(LedgerDirMigration.migrate(old, neu))
        // Der Fruehausstieg hat den fehlenden Marker VERIFIZIERT nachgezogen.
        assertTrue(sentinel(neu).isFile)
        // Die Zielgeneration blieb unangetastet.
        assertEquals(content, target(neu).readText())
    }

    /** Kann der Marker beim Fruehausstieg NICHT entstehen (hier: Verzeichnis
     *  blockiert den Namen), gilt die Migration weiter als ausstehend -
     *  der Aufrufer haelt konservativ an (migrationPending). */
    @Test
    fun `blockierter Sentinel beim Fruehausstieg meldet false`(@TempDir root: File) {
        val (old, neu) = dirs(root)
        neu.mkdirs()
        target(neu).writeText(content)
        assertTrue(sentinel(neu).mkdirs()) // Blockade: Verzeichnis statt Datei

        assertFalse(LedgerDirMigration.migrate(old, neu))
    }

    // ---- R4-01 (a): Sentinel-Fehlschlag der frischen Migration ------------

    /** Schlaegt das Sentinel-Schreiben WAEHREND der Migration fehl, ist die
     *  Migration NICHT fertig: false, und das Alte bleibt fuer den naechsten
     *  Versuch stehen (vorher: Erfolg trotz ignoriertem Schreibfehler). */
    @Test
    fun `Sentinel-Fehlschlag der frischen Migration meldet false und laesst das Alte stehen`(@TempDir root: File) {
        val (old, neu) = dirs(root)
        target(old).writeText(content)
        neu.mkdirs()
        assertTrue(sentinel(neu).mkdirs()) // Blockade: Verzeichnis statt Datei

        assertFalse(LedgerDirMigration.migrate(old, neu))
        // Das Original ist NICHT nach .migrated rotiert - Wiederholung moeglich.
        assertTrue(target(old).exists())
        assertFalse(migrated(old).exists())
    }

    // ---- R4-01 (d): Idempotenz mit Sentinel-Nachzug -----------------------

    @Test
    fun `wiederholte Migration ist idempotent und zieht einen geloeschten Sentinel nach`(@TempDir root: File) {
        val (old, neu) = dirs(root)
        target(old).writeText(content)

        assertTrue(LedgerDirMigration.migrate(old, neu))
        assertTrue(sentinel(neu).isFile)
        // Zweiter Lauf: Fruehausstieg, nichts veraendert sich.
        assertTrue(LedgerDirMigration.migrate(old, neu))
        assertEquals(content, target(neu).readText())
        // Sentinel verschwindet (Aufraeumen, Bug) - der naechste Lauf zieht
        // ihn nach, statt "fertig" ohne Marker zu melden.
        assertTrue(sentinel(neu).delete())
        assertTrue(LedgerDirMigration.migrate(old, neu))
        assertTrue(sentinel(neu).isFile)
    }
}

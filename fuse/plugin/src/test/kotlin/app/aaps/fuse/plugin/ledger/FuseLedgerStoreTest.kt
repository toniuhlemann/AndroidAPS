package app.aaps.fuse.plugin.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Atomik und Generationen des Ledger-Stores (tmp + rename, eine .bak). */
class FuseLedgerStoreTest {

    private val store = FuseLedgerStore()

    @Test
    fun `erstes Schreiben legt die Datei an und laesst kein tmp zurueck`(@TempDir dir: File) {
        assertTrue(store.write(dir, """{"a":1}"""))
        assertEquals("""{"a":1}""", File(dir, FuseLedgerStore.FILE_NAME).readText())
        assertFalse(File(dir, FuseLedgerStore.FILE_NAME + ".tmp").exists())
        assertFalse(File(dir, FuseLedgerStore.FILE_NAME + ".bak").exists())
    }

    @Test
    fun `zweites Schreiben dreht die Vorgeneration nach bak`(@TempDir dir: File) {
        store.write(dir, "alt")
        assertTrue(store.write(dir, "neu"))
        assertEquals("neu", File(dir, FuseLedgerStore.FILE_NAME).readText())
        assertEquals("alt", File(dir, FuseLedgerStore.FILE_NAME + ".bak").readText())
        assertFalse(File(dir, FuseLedgerStore.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun `drittes Schreiben behaelt genau EINE bak-Generation`(@TempDir dir: File) {
        store.write(dir, "eins")
        store.write(dir, "zwei")
        store.write(dir, "drei")
        assertEquals("drei", File(dir, FuseLedgerStore.FILE_NAME).readText())
        assertEquals("zwei", File(dir, FuseLedgerStore.FILE_NAME + ".bak").readText())
    }

    @Test
    fun `read liefert Haupt- vor bak-Generation`(@TempDir dir: File) {
        store.write(dir, "alt")
        store.write(dir, "neu")
        assertEquals(listOf("neu", "alt"), store.read(dir))
    }

    /** Der Rueckfall-Fall: Hauptdatei kaputt (hier: von aussen zerschossen),
     *  die bak-Generation traegt noch den letzten vollstaendigen Stand -
     *  der Adapter probiert die Kandidaten der Reihe nach. */
    @Test
    fun `bak bleibt lesbar wenn die Hauptdatei zerschossen wurde`(@TempDir dir: File) {
        store.write(dir, """{"ok":true}""")
        store.write(dir, """{"ok":2}""")
        File(dir, FuseLedgerStore.FILE_NAME).writeText("{kaputt")
        val candidates = store.read(dir)
        assertEquals(2, candidates.size)
        assertEquals("""{"ok":true}""", candidates[1])
    }

    @Test
    fun `ein unbeschreibbares Ziel meldet false statt zu werfen`(@TempDir dir: File) {
        val blockiert = File(dir, "datei-statt-verzeichnis")
        blockiert.writeText("x")
        assertFalse(store.write(File(blockiert, "unter"), "{}"))
    }

    @Test
    fun `read auf leerem Verzeichnis ist leer, nie ein Wurf`(@TempDir dir: File) {
        assertTrue(store.read(dir).isEmpty())
    }
}

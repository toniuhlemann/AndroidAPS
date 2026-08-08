package app.aaps.fuse.plugin.ledger

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Atomik, Generationen und der PERSISTENZVERTRAG des Ledger-Stores
 * (tmp + rename, eine .bak; Audit 2d273cb REG-01a/b: writeVerified mit
 * Rueckleseprobe, readNewestValid ueber alle DREI Kandidaten).
 */
class FuseLedgerStoreTest {

    private val store = FuseLedgerStore()

    /** Minimale gueltige Testform: die revision entscheidet die Auswahl. */
    private fun json(revision: Long) = """{"revision":$revision}"""

    private val validate: (String) -> Long? = { text ->
        runCatching { JSONObject(text).getLong("revision") }.getOrNull()
    }

    // ---- Schreiben --------------------------------------------------------

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
    fun `ein unbeschreibbares Ziel meldet false statt zu werfen`(@TempDir dir: File) {
        val blockiert = File(dir, "datei-statt-verzeichnis")
        blockiert.writeText("x")
        assertFalse(store.write(File(blockiert, "unter"), "{}"))
    }

    // ---- writeVerified: der Vertrag ist die Rueckleseprobe ----------------

    @Test
    fun `writeVerified meldet Erfolg erst nach bestandener Rueckleseprobe`(@TempDir dir: File) {
        assertTrue(store.writeVerified(dir, json(7)))
        assertEquals(json(7), File(dir, FuseLedgerStore.FILE_NAME).readText())
    }

    @Test
    fun `writeVerified meldet false auf unbeschreibbarem Ziel statt zu werfen`(@TempDir dir: File) {
        val blockiert = File(dir, "datei-statt-verzeichnis")
        blockiert.writeText("x")
        assertFalse(store.writeVerified(File(blockiert, "unter"), json(1)))
    }

    // ---- Sentinel (R4-01): Erfolg heisst existierende Markerdatei ---------

    @Test
    fun `writeSentinel meldet Erfolg nur bei existierender Markerdatei und ist idempotent`(@TempDir dir: File) {
        assertTrue(FuseLedgerStore.writeSentinel(dir))
        assertTrue(FuseLedgerStore.sentinelExists(dir))
        // Idempotent: ein vorhandener Marker bleibt Erfolg.
        assertTrue(FuseLedgerStore.writeSentinel(dir))
    }

    /** Ein VERZEICHNIS unter dem Markernamen ist weder ein Marker noch ein
     *  gelungener Write - beides false, kein Wurf. Vorher verschluckte
     *  writeSentinelTolerant genau diesen Fehler. */
    @Test
    fun `writeSentinel meldet Blockade als false statt zu werfen`(@TempDir dir: File) {
        assertTrue(File(dir, FuseLedgerStore.SENTINEL_NAME).mkdirs())
        assertFalse(FuseLedgerStore.writeSentinel(dir))
        assertFalse(FuseLedgerStore.sentinelExists(dir))
    }

    // ---- readNewestValid: juengste GUELTIGE Generation --------------------

    /** REG-01b: nach Kill zwischen den Renames traegt NUR die tmp die
     *  neueste Generation - sie muss gewinnen, nicht ignoriert werden. */
    @Test
    fun `eine vollstaendige tmp mit juengster Revision gewinnt`(@TempDir dir: File) {
        store.write(dir, json(1))
        File(dir, FuseLedgerStore.FILE_NAME + ".tmp").writeText(json(2))
        val r = store.readNewestValid(dir, validate)
        assertEquals(json(2), r.content)
        assertTrue(r.anyCandidateExisted)
        assertFalse(r.anyCandidateInvalid)
    }

    /** Die Kehrseite: eine VERWAISTE ALTE tmp (frueherer abgebrochener
     *  Schreibversuch) darf eine juengere Hauptdatei nicht schlagen -
     *  gewaehlt wird nach revision, nicht nach Dateinamen. */
    @Test
    fun `eine verwaiste alte tmp schlaegt die juengere Hauptdatei nicht`(@TempDir dir: File) {
        store.write(dir, json(5))
        File(dir, FuseLedgerStore.FILE_NAME + ".tmp").writeText(json(2))
        val r = store.readNewestValid(dir, validate)
        assertEquals(json(5), r.content)
        assertFalse(r.anyCandidateInvalid)
    }

    /** REG-01c-Rueckfall: Hauptdatei zerschossen, bak traegt den letzten
     *  vollstaendigen Stand - der wird gewaehlt, aber der Verlust der
     *  juengeren Generation wird als invalid GEMELDET, nicht verschluckt. */
    @Test
    fun `korruptes target faellt auf gueltige bak zurueck und meldet invalid`(@TempDir dir: File) {
        store.write(dir, json(1))
        store.write(dir, json(2))
        File(dir, FuseLedgerStore.FILE_NAME).writeText("{kaputt")
        val r = store.readNewestValid(dir, validate)
        assertEquals(json(1), r.content)
        assertTrue(r.anyCandidateExisted)
        assertTrue(r.anyCandidateInvalid)
    }

    @Test
    fun `alle Kandidaten korrupt liefert null mit beiden Flags`(@TempDir dir: File) {
        File(dir, FuseLedgerStore.FILE_NAME).writeText("{kaputt")
        File(dir, FuseLedgerStore.FILE_NAME + ".bak").writeText("auch kaputt")
        File(dir, FuseLedgerStore.FILE_NAME + ".tmp").writeText("ebenso")
        val r = store.readNewestValid(dir, validate)
        assertNull(r.content)
        assertTrue(r.anyCandidateExisted)
        assertTrue(r.anyCandidateInvalid)
    }

    /** Der ECHTE Erststart: kein Kandidat existiert - beide Flags false,
     *  daran unterscheidet der Adapter Erststart von Datenverlust. */
    @Test
    fun `leeres Verzeichnis ist ein Erststart ohne Flags`(@TempDir dir: File) {
        val r = store.readNewestValid(dir, validate)
        assertNull(r.content)
        assertFalse(r.anyCandidateExisted)
        assertFalse(r.anyCandidateInvalid)
    }

    /** Ein Wurf aus validate zaehlt als invalid - nie als Absturz. */
    @Test
    fun `werfendes validate zaehlt als invalid`(@TempDir dir: File) {
        store.write(dir, json(1))
        val r = store.readNewestValid(dir) { throw IllegalStateException("decode explodiert") }
        assertNull(r.content)
        assertTrue(r.anyCandidateExisted)
        assertTrue(r.anyCandidateInvalid)
    }
}

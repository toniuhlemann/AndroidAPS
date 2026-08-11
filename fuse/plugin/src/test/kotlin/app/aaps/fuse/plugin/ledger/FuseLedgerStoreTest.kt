package app.aaps.fuse.plugin.ledger

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    // ---- C8d: Quarantaene der ungueltigen Generationen --------------------

    /** Der Leser meldet nicht nur DASS, sondern WELCHE Kandidaten ungueltig
     *  waren - ohne die Dateiliste kann der Adapter sie nicht der Rotation
     *  entziehen (Codex C8d). */
    @Test
    fun `readNewestValid benennt die ungueltigen Kandidaten`(@TempDir dir: File) {
        store.write(dir, json(1))
        store.write(dir, json(2))
        File(dir, FuseLedgerStore.FILE_NAME).writeText("{kaputt")
        val r = store.readNewestValid(dir, validate)
        assertEquals(listOf(FuseLedgerStore.FILE_NAME), r.invalidFiles.map { it.name })
    }

    /** QUARANTAENE: die unlesbare Generation wird umbenannt, nicht geloescht -
     *  danach kann die Rotation sie nicht mehr ueberschreiben, und der Inhalt
     *  bleibt als Beweis liegen. */
    @Test
    fun `quarantineInvalid benennt um und erhaelt den Inhalt`(@TempDir dir: File) {
        val target = File(dir, FuseLedgerStore.FILE_NAME).also { it.writeText("{kaputt") }
        val bak = File(dir, FuseLedgerStore.FILE_NAME + ".bak").also { it.writeText("auch kaputt") }

        val names = FuseLedgerStore.quarantineInvalid(listOf(target, bak), 4711L)
        assertEquals(2, names.size)
        assertFalse(target.exists())
        assertFalse(bak.exists())
        val inhalte = names.map { File(dir, it).readText() }.toSet()
        assertEquals(setOf("{kaputt", "auch kaputt"), inhalte)
        assertTrue(names.all { it.contains(FuseLedgerStore.CORRUPT_SUFFIX) }, "$names")
    }

    /** Zweimal derselbe Stempel darf den ersten Beweis nicht ueberschreiben. */
    @Test
    fun `quarantineInvalid ueberschreibt keinen aelteren Beweis`(@TempDir dir: File) {
        val f = File(dir, FuseLedgerStore.FILE_NAME)
        f.writeText("erster")
        val first = FuseLedgerStore.quarantineInvalid(listOf(f), 4711L)
        f.writeText("zweiter")
        val second = FuseLedgerStore.quarantineInvalid(listOf(f), 4711L)
        assertNotEquals(first.single(), second.single())
        assertEquals("erster", File(dir, first.single()).readText())
        assertEquals("zweiter", File(dir, second.single()).readText())
    }

    @Test
    fun `quarantineInvalid vertraegt fehlende Dateien ohne Wurf`(@TempDir dir: File) {
        assertTrue(FuseLedgerStore.quarantineInvalid(listOf(File(dir, "gibt-es-nicht")), 1L).isEmpty())
    }

    // ---- C8d: der dauerhafte Hold-Marker ----------------------------------

    @Test
    fun `writeHoldVerified legt den Marker an und meldet ihn`(@TempDir dir: File) {
        assertFalse(FuseLedgerStore.holdExists(dir))
        assertTrue(FuseLedgerStore.writeHoldVerified(dir, """{"reason":"TEST"}"""))
        assertTrue(FuseLedgerStore.holdExists(dir))
        assertEquals("""{"reason":"TEST"}""", File(dir, FuseLedgerStore.HOLD_NAME).readText())
    }

    /** Ein vorhandener Marker wird NICHT ueberschrieben: der aelteste Befund
     *  ist der, der den Hold ausgeloest hat. */
    @Test
    fun `writeHoldVerified laesst einen vorhandenen Marker stehen`(@TempDir dir: File) {
        assertTrue(FuseLedgerStore.writeHoldVerified(dir, "erster"))
        assertTrue(FuseLedgerStore.writeHoldVerified(dir, "zweiter"))
        assertEquals("erster", File(dir, FuseLedgerStore.HOLD_NAME).readText())
    }

    /** Blockade (Verzeichnis unter dem Markernamen): false statt Wurf - der
     *  Aufrufer haelt daraufhin fail-closed an. */
    @Test
    fun `writeHoldVerified meldet Blockade als false statt zu werfen`(@TempDir dir: File) {
        assertTrue(File(dir, FuseLedgerStore.HOLD_NAME).mkdirs())
        assertFalse(FuseLedgerStore.writeHoldVerified(dir, "x"))
        assertFalse(FuseLedgerStore.holdExists(dir))
    }

    /** Der Marker ist KEIN Ledger-Kandidat: er darf die Generationenwahl
     *  nicht beeinflussen. */
    @Test
    fun `der Hold-Marker ist kein Generationskandidat`(@TempDir dir: File) {
        assertTrue(FuseLedgerStore.writeHoldVerified(dir, "hold"))
        val r = store.readNewestValid(dir, validate)
        assertNull(r.content)
        assertFalse(r.anyCandidateExisted)
        assertFalse(r.anyCandidateInvalid)
    }

    // ---- Durabilitaet (12.08.) --------------------------------------------

    /**
     * EIN FSYNC-FEHLER IST EIN SCHREIBFEHLER.
     *
     * Bis zum 12.08. schrieb der Store mit `writeText` - Bytes in den Page
     * Cache, kein fsync. Gegen Prozesstod traegt das, gegen Stromausfall oder
     * Kernel-Panik nicht. Und die Rueckleseprobe merkte NICHTS davon: sie
     * liest denselben Cache und meldete Erfolg fuer eine Datei, die auf der
     * Platte leer oder halb gewesen waere.
     *
     * Der Fehler wird eingespeist statt ein Dateisystem zu manipulieren -
     * anders ist er nicht reproduzierbar herstellbar.
     */
    @Test
    fun `ein fehlgeschlagener fsync macht den Persist ungueltig`(@TempDir dir: File) {
        val store = FuseLedgerStore()
        assertTrue(
            !store.writeVerified(dir, "inhalt") { throw java.io.SyncFailedException("Platte weg") },
            "ein Sync-Fehler muss als Fehlschlag durchschlagen",
        )
    }

    /** Und mit funktionierendem Sync liegt der Inhalt wirklich da - sonst
     *  waere die Zusicherung oben nur "es schlaegt immer fehl". */
    @Test
    fun `mit funktionierendem Sync steht der Inhalt in der Zieldatei`(@TempDir dir: File) {
        val store = FuseLedgerStore()
        assertTrue(store.writeVerified(dir, "inhalt"))
        assertEquals("inhalt", File(dir, FuseLedgerStore.FILE_NAME).readText())
    }

    /**
     * DIE REIHENFOLGE: fsync VOR dem Rename.
     *
     * Bricht der Sync ab, darf die Rotation noch nicht gelaufen sein - sonst
     * zeigte der Zielname auf einen Inhalt, den es nach einem Stromverlust
     * nicht gibt, waehrend die letzte gute Generation schon nach `.bak`
     * gedreht wurde. Hier steht eine gueltige Vorgeneration; nach dem
     * gescheiterten Schreiben muss sie UNVERAENDERT dort stehen.
     */
    @Test
    fun `ein Sync-Fehler laesst die alte Generation unangetastet`(@TempDir dir: File) {
        val store = FuseLedgerStore()
        assertTrue(store.writeVerified(dir, "alt"))

        store.writeVerified(dir, "neu") { throw java.io.SyncFailedException("Platte weg") }

        assertEquals(
            "alt", File(dir, FuseLedgerStore.FILE_NAME).readText(),
            "die alte Generation darf durch einen gescheiterten Schreibversuch nicht verschwinden",
        )
    }
}

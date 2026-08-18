package app.aaps.fuse.plugin.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * DER UMZUG IST EINE VOLLSTAENDIGKEITSPRUEFUNG, KEINE EXISTENZPRUEFUNG
 * (Toni 18.08.).
 *
 * DER BEFUND. Der Fruehausstieg fragte `names.any { newDir/it.exists() }` -
 * also ob im Ziel IRGENDEINE Generation liegt. Die Kopierschleife laeuft
 * aber target, .bak, .tmp und kann an jeder Iteration abbrechen; `.tmp` -
 * der Traeger der NEUESTEN Generation - kommt zuletzt.
 *
 * Ein Abbruch nach der ersten Kopie liess den naechsten Lauf also melden
 * "die Vorgeschichte ist sicher uebernommen", waehrend `.tmp` fuer immer im
 * alten Verzeichnis blieb. Im Ziel lag dann eine LESBARE, gueltige, aber
 * AELTERE Generation: keine Hold-Quelle greift, und die Abwesenheit der
 * juengsten Zeile wird als Beweis gelesen, dass es sie nie gab. Die
 * verlorene Zeile ist offene Haftung fuer bereits abgegebenes Insulin -
 * ohne sie waechst der Headroom und der naechste Zyklus dosiert obendrauf.
 *
 * WARUM DIE TESTS DEN CRASH NICHT INJIZIEREN. Sie stellen stattdessen den
 * ZUSTAND her, den ein Crash hinterlaesst - das Ziel traegt eine Teilmenge
 * der Dateien. Das ist die staerkere Probe: sie prueft, was der naechste
 * Lauf VORFINDET, statt einen simulierten Kontrollfluss.
 */
class LedgerMigrationCompletenessTest {

    private val namen = listOf(
        FuseLedgerStore.FILE_NAME,
        FuseLedgerStore.FILE_NAME + ".bak",
        FuseLedgerStore.FILE_NAME + ".tmp",
    )

    /** Je Generation ein eigener Inhalt - sonst faellt ein vertauschter
     *  oder fehlender Nachzug nicht auf. */
    private fun inhalt(name: String) = """{"revision":${namen.indexOf(name) + 1},"gen":"$name"}"""

    private fun dirs(root: File): Pair<File, File> {
        val alt = File(root, "alt").also { it.mkdirs() }
        val neu = File(root, "neu")
        return alt to neu
    }

    private fun sentinel(dir: File) = File(dir, FuseLedgerStore.SENTINEL_NAME)

    /** Alle drei Generationen im Altverzeichnis. */
    private fun altBefuellen(alt: File) {
        for (n in namen) File(alt, n).writeText(inhalt(n), Charsets.UTF_8)
    }

    /** Der Zustand nach einem Crash: [fertig] ist schon im Ziel. */
    private fun zielTeilweise(neu: File, fertig: List<String>) {
        neu.mkdirs()
        for (n in fertig) File(neu, n).writeText(inhalt(n), Charsets.UTF_8)
    }

    private fun alleAngekommen(neu: File) {
        for (n in namen) {
            val f = File(neu, n)
            assertTrue(f.exists(), "$n fehlt im Ziel")
            assertEquals(inhalt(n), f.readText(Charsets.UTF_8), "$n hat den falschen Inhalt")
        }
    }

    // ---- Punkt 5: Crash nach JEDER einzelnen Kopie -------------------------

    /**
     * DIE KERNPROBE. Fuer jede der acht moeglichen Teilmengen, die ein
     * abgebrochener Lauf im Ziel hinterlassen haben kann, muss der naechste
     * Lauf die restlichen nachziehen.
     *
     * Der alte Code bestand hiervon genau EINEN Fall - die leere Teilmenge.
     * Jede andere traf den Fruehausstieg und meldete faelschlich Erfolg.
     */
    @Test
    fun `jede Teilmenge im Ziel wird vollstaendig nachgezogen`(@TempDir root: File) {
        val teilmengen = listOf(
            emptyList(),
            listOf(namen[0]),
            listOf(namen[1]),
            listOf(namen[2]),
            listOf(namen[0], namen[1]),
            listOf(namen[0], namen[2]),
            listOf(namen[1], namen[2]),
            namen,
        )
        for ((i, fertig) in teilmengen.withIndex()) {
            val (alt, neu) = dirs(File(root, "fall$i").also { it.mkdirs() })
            altBefuellen(alt)
            zielTeilweise(neu, fertig)

            assertTrue(
                LedgerDirMigration.migrate(alt, neu),
                "Teilmenge $fertig - der Umzug MUSS gelingen",
            )
            alleAngekommen(neu)
            assertTrue(sentinel(neu).exists(), "Teilmenge $fertig - Sentinel fehlt")
        }
    }

    /**
     * DER FALL AUS DEM BEFUND, ausdruecklich einzeln: target ist schon
     * drueben, `.tmp` traegt die neueste Generation und fehlt noch.
     */
    @Test
    fun `eine liegengebliebene tmp wird nachgezogen`(@TempDir root: File) {
        val (alt, neu) = dirs(root)
        altBefuellen(alt)
        zielTeilweise(neu, listOf(FuseLedgerStore.FILE_NAME))

        assertTrue(LedgerDirMigration.migrate(alt, neu))
        val tmpZiel = File(neu, FuseLedgerStore.FILE_NAME + ".tmp")
        assertTrue(tmpZiel.exists(), "die NEUESTE Generation MUSS mitkommen")
        assertEquals(inhalt(FuseLedgerStore.FILE_NAME + ".tmp"), tmpZiel.readText(Charsets.UTF_8))
    }

    // ---- Punkt 2: schon Kopiertes wird uebersprungen -----------------------

    /**
     * IDEMPOTENZ: ein zweiter Lauf auf demselben Stand aendert nichts und
     * meldet weiter Erfolg. Ohne diese Zusicherung waere die
     * Vollstaendigkeitspruefung ein Dauerbetrieb, der bei jedem invoke
     * dieselben Dateien neu schreibt.
     */
    @Test
    fun `ein zweiter Lauf ist wirkungslos und meldet Erfolg`(@TempDir root: File) {
        val (alt, neu) = dirs(root)
        altBefuellen(alt)
        assertTrue(LedgerDirMigration.migrate(alt, neu))
        val stempel = namen.associateWith { File(neu, it).lastModified() }

        assertTrue(LedgerDirMigration.migrate(alt, neu), "der zweite Lauf MUSS Erfolg melden")
        alleAngekommen(neu)
        for (n in namen)
            assertEquals(stempel[n], File(neu, n).lastModified(), "$n wurde unnoetig neu geschrieben")
    }

    /** Und die Originale sind danach stillgelegt, nicht geloescht. */
    @Test
    fun `nach dem Umzug liegen die Originale als migrated daneben`(@TempDir root: File) {
        val (alt, neu) = dirs(root)
        altBefuellen(alt)
        assertTrue(LedgerDirMigration.migrate(alt, neu))
        for (n in namen) {
            assertFalse(File(alt, n).exists(), "$n muss als Kandidat verschwunden sein")
            assertTrue(File(alt, "$n.migrated").exists(), "$n.migrated fehlt")
        }
    }

    // ---- Punkt 3: abweichender Inhalt -> Hold ------------------------------

    /**
     * EIN ZIEL MIT ABWEICHENDEM INHALT WIRD NICHT UEBERSCHRIEBEN.
     *
     * Hier stehen zwei verschiedene Generationen unter demselben Namen, und
     * der Umzug kann nicht wissen, welche gilt: das Ziel koennte eine
     * NEUERE tragen, weil FUSE dort schon lief. Ueberschreiben hiesse, eine
     * moeglicherweise juengere Haftung durch eine aeltere zu ersetzen.
     */
    @Test
    fun `ein abweichendes Ziel fuehrt zum Hold statt zum Ueberschreiben`(@TempDir root: File) {
        val (alt, neu) = dirs(root)
        altBefuellen(alt)
        neu.mkdirs()
        val fremd = """{"revision":99,"gen":"fremde neuere Generation"}"""
        File(neu, FuseLedgerStore.FILE_NAME).writeText(fremd, Charsets.UTF_8)

        assertFalse(LedgerDirMigration.migrate(alt, neu), "MUSS scheitern - Hold beim Aufrufer")
        assertEquals(
            fremd, File(neu, FuseLedgerStore.FILE_NAME).readText(Charsets.UTF_8),
            "das Ziel MUSS unangetastet bleiben",
        )
        assertTrue(
            File(alt, FuseLedgerStore.FILE_NAME).exists(),
            "und das Original bleibt Kandidat - nichts wurde stillgelegt",
        )
    }

    // ---- Punkt 4: Sentinel erst nach vollstaendigem Nachweis ---------------

    /**
     * KEIN SENTINEL AUF HALBEM WEG.
     *
     * Der Sentinel behauptet "es gab hier einen Ledger, er ist uebernommen".
     * Steht er nach einem gescheiterten Umzug da, sieht ein spaeterer
     * Dateiverlust wie ein Erststart aus - und die Vorgeschichte ist
     * unwiederbringlich als "gab es nie" verbucht.
     */
    @Test
    fun `ein gescheiterter Umzug hinterlaesst keinen Sentinel`(@TempDir root: File) {
        val (alt, neu) = dirs(root)
        altBefuellen(alt)
        neu.mkdirs()
        File(neu, FuseLedgerStore.FILE_NAME).writeText("""{"revision":99}""", Charsets.UTF_8)

        assertFalse(LedgerDirMigration.migrate(alt, neu))
        assertFalse(sentinel(neu).exists(), "der Sentinel darf erst nach VOLLSTAENDIGEM Nachweis stehen")
    }

    /**
     * Der Nachzug fuer den Kill zwischen Ziel-Rename und Sentinel bleibt
     * erhalten: keine Kandidaten mehr im Alt, aber Generationen im Ziel.
     */
    @Test
    fun `ein fehlender Sentinel wird nachgezogen wenn nichts mehr offen ist`(@TempDir root: File) {
        val (alt, neu) = dirs(root)
        zielTeilweise(neu, namen)
        assertFalse(sentinel(neu).exists())

        assertTrue(LedgerDirMigration.migrate(alt, neu))
        assertTrue(sentinel(neu).exists(), "der Marker MUSS nachgezogen werden")
    }

    /**
     * DER ECHTE ERSTSTART legt weiterhin KEINEN Sentinel an - er wuerde
     * spaeter einen Datenverlust behaupten, den es nie gab.
     */
    @Test
    fun `ein echter Erststart legt keinen Sentinel an`(@TempDir root: File) {
        val (alt, neu) = dirs(root)
        assertTrue(LedgerDirMigration.migrate(alt, neu))
        assertFalse(sentinel(neu).exists())
    }
}

package app.aaps.fuse.plugin.ledger

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * Persistenz des Commitment-Ledgers: EINE Datei, atomar ersetzt, eine
 * .bak-Generation (Audit R95, Fix 3; Persistenzvertrag Audit 2d273cb REG-01).
 *
 * SCHREIBEN heisst hier: erst vollstaendig in `.tmp` schreiben, dann die alte
 * Datei nach `.bak` wegdrehen, dann `.tmp` an den Zielnamen umbenennen. Die
 * Sequenz ist NICHT als Gesamtoperation atomar (REG-01b): ein Kill zwischen
 * den beiden Renames hinterlaesst KEIN target, aber eine vollstaendige `.tmp`
 * - deshalb betrachtet [readNewestValid] alle DREI Generationen und waehlt
 * die juengste GUELTIGE, statt `.tmp` blind zu ignorieren.
 *
 * VERIFIZIERT heisst: [writeVerified] meldet Erfolg erst nach Rueckleseprobe
 * (target existiert und traegt exakt den geschriebenen Inhalt). Der Aufrufer
 * (FusePlugin) publiziert einen SMB NUR nach diesem Erfolg - ein verworfener
 * write-Boolean war der Kern von REG-01a (fail-open Store).
 *
 * SYNCHRON und NIE WERFEND, aus denselben Gruenden wie der Trail-Exporter
 * (s. export/FuseStateExporter): derselbe Mechanismus laeuft seit Wochen je
 * Loop-Lauf auf demselben Geraet; ein Schreiberthread waere Vorbau. Das
 * VERZEICHNIS wird hereingereicht - im Unit-Test gibt es kein Android, und
 * ein relativer Pfad liefe am Testverzeichnis vorbei.
 */
/**
 * @param durability der Durabilitaets-Nachweis. Default ist die
 *   Android-Fassung; JVM-Tests speisen einen Fake ein. Ein Default, der
 *   Fehler schluckt, waere die bequeme und falsche Loesung gewesen -
 *   Produktion faellt dann still auf "best effort" zurueck.
 */
class FuseLedgerStore(private val durability: Durability = Durability.ANDROID) {

    companion object {

        const val FILE_NAME = "fuse_ledger.json"

        /**
         * Fix 1b (Re-Audit c750169, 6.1): persistenter "es gab schon einen
         * Ledger"-Marker. Er wird beim ersten erfolgreichen persistVerified
         * und beim verifizierten Migrationsabschluss geschrieben und NIE
         * geloescht. Findet loadOnce den Sentinel, aber keine lesbare
         * Generation, ist das DATENVERLUST - ohne den Marker waere er vom
         * echten Erststart nicht unterscheidbar, und verlorene offene
         * Haftung wuerde still als "nie passiert" verbucht.
         */
        const val SENTINEL_NAME = "fuse_ledger.exists"

        /** isFile statt exists (R4-01): ein VERZEICHNIS unter dem Namen ist
         *  kein Marker - "existiert irgendwas" darf weder als Verlustbeweis
         *  noch als erfolgreicher Write durchgehen. */
        fun sentinelExists(dir: File): Boolean =
            runCatching { File(dir, SENTINEL_NAME).isFile }.getOrDefault(false)

        /**
         * VERTRAGSBESTANDTEIL, nicht Zusatzsicherung (Codex R4-01): der
         * fruehere `writeSentinelTolerant` verschluckte seinen Fehler - damit
         * konnte ein Persist/eine Migration "fertig" melden, obwohl der
         * Verlustmarker nie entstand, und ein spaeterer Dateiverlust sah wie
         * ein Erststart aus (genau der REG-03-Pfad). Jetzt gilt: Erfolg
         * heisst, der Marker EXISTIERT nach dem Aufruf als Datei - verifiziert
         * wie beim writeVerified-Rueckleseprinzip, nur dass hier die Existenz
         * genuegt (der Inhalt traegt keine Semantik). Nie werfend.
         *
         * @return true nur, wenn der Marker nach dem Aufruf als DATEI liegt.
         */
        fun writeSentinel(dir: File): Boolean = runCatching {
            val f = File(dir, SENTINEL_NAME)
            if (f.isFile) return@runCatching true
            if (!dir.exists()) dir.mkdirs()
            f.writeText("v1", Charsets.UTF_8)
            f.isFile
        }.getOrDefault(false)

        /**
         * QUARANTAENE-Suffix fuer ungueltige Generationen (Codex C8d).
         *
         * Der vollstaendige Name ist `<generation>.corrupt.<ts>`; er ist
         * bewusst KEIN Kandidat von [readNewestValid] - eine quarantaenierte
         * Datei nimmt nie wieder an der Generationswahl teil.
         */
        const val CORRUPT_SUFFIX = ".corrupt."

        /**
         * DAUERHAFTER Hold-Marker (Codex C8d, G4).
         *
         * WARUM eine eigene Datei: der Recovery-Hold lebte als RAM-Feld im
         * Adapter, und die Rotation ([write]) haelt nur EINE Vorgeneration -
         * zwei gehaltene Zyklen ersetzten also die korrupten Beweis-
         * Generationen durch saubere leere. Nach dem Neustart existierte kein
         * invalider Kandidat mehr, der Hold fiel weg, und eine unbekannte
         * moegliche Abgabe war refinanzierbar. Diese Datei liegt AUSSERHALB
         * der rotierenden Generationen und wird von keinem normalen Persist
         * angefasst.
         *
         * Der Aufloesungsweg ist AUSSCHLIESSLICH [FuseLedgerRepair] - eine
         * ausdrueckliche Bedienhandlung mit benanntem Grund, die den
         * bisherigen Ledger nicht loescht, sondern in Quarantaene legt und
         * den Befund dauerhaft protokolliert. Kein automatischer Pfad raeumt
         * diesen Marker weg (Adjudication K1.1/G4).
         */
        const val HOLD_NAME = "fuse_ledger.hold"

        /** isFile wie beim Sentinel: ein VERZEICHNIS unter dem Namen ist kein
         *  Marker (und [writeHoldVerified] meldet dann false). */
        fun holdExists(dir: File): Boolean =
            runCatching { File(dir, HOLD_NAME).isFile }.getOrDefault(false)

        /**
         * Den Hold-Marker VERIFIZIERT schreiben - Erfolg heisst, die Datei
         * liegt danach mit genau diesem Inhalt (Rueckleseprinzip wie bei
         * [writeVerified]). Nie werfend.
         *
         * IDEMPOTENT und NICHT ueberschreibend: ein bereits vorhandener
         * Marker bleibt unveraendert stehen und gilt als Erfolg - der
         * AELTESTE Befund ist der, der den Hold ausgeloest hat, und der darf
         * von einem spaeteren Vorfall nicht ueberschrieben werden.
         */
        fun writeHoldVerified(dir: File, content: String): Boolean = runCatching {
            val f = File(dir, HOLD_NAME)
            if (f.isFile) return@runCatching true
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return@runCatching false
            f.writeText(content, Charsets.UTF_8)
            f.isFile && f.readText(Charsets.UTF_8) == content
        }.getOrDefault(false)

        /**
         * Ungueltige Generationen der Rotation ENTZIEHEN (Codex C8d).
         *
         * Umbenennen statt loeschen: der Inhalt ist der einzige Anhaltspunkt
         * dafuer, WAS verloren ging - und solange die Datei unter ihrem
         * Generationsnamen liegt, ueberschreibt sie der naechste [write]
         * (bak.delete()/rename) einfach.
         *
         * Kollisionsfrei: ein schon belegter Zielname bekommt einen Zaehler -
         * ein zweiter Vorfall mit demselben Stempel darf den ersten Beweis
         * nicht ueberschreiben. Nie werfend; zurueck kommen nur die
         * TATSAECHLICH umbenannten Dateien.
         */
        fun quarantineInvalid(files: List<File>, nowTs: Long): List<String> =
            quarantine(files, nowTs, CORRUPT_SUFFIX)

        /**
         * Dieselbe Mechanik mit frei gewaehltem Suffix - der Reparaturweg
         * ([FuseLedgerRepair]) legt seine Generationen unter `.reset.` ab.
         *
         * Der Suffix ist ABSICHTLICH ein Parameter und kein zweites,
         * kopiertes Verfahren: Kollisionsfreiheit, "umbenennen statt
         * loeschen" und "nie werfend" sind die Eigenschaften, auf die es
         * ankommt, und die duerfen nicht an zwei Stellen getrennt gepflegt
         * werden.
         */
        fun quarantine(files: List<File>, nowTs: Long, suffix: String): List<String> = files.mapNotNull { f ->
            runCatching {
                if (!f.isFile) return@runCatching null
                val dir = f.parentFile ?: return@runCatching null
                var target = File(dir, "${f.name}$suffix$nowTs")
                var n = 1
                while (target.exists() && n <= 1_000) {
                    target = File(dir, "${f.name}$suffix$nowTs-$n")
                    n++
                }
                if (!target.exists() && f.renameTo(target)) target.name else null
            }.getOrNull()
        }

        /**
         * Den Hold-Marker ENTFERNEN - der einzige Weg dorthin fuehrt ueber
         * [FuseLedgerRepair].
         *
         * Verifiziert wie alles andere hier: Erfolg heisst, die Datei liegt
         * danach NICHT mehr. Ein "delete() sagte true, die Datei ist aber noch
         * da" darf nicht als geloester Hold durchgehen - der naechste
         * Prozessstart wuerde ihn sonst kommentarlos wieder aufziehen und der
         * Bediener saehe eine Reparatur, die nichts bewirkt hat.
         */
        fun clearHoldVerified(dir: File): Boolean = runCatching {
            val f = File(dir, HOLD_NAME)
            if (!f.exists()) return@runCatching true
            f.delete()
            !f.exists()
        }.getOrDefault(false)
    }

    /**
     * Ergebnis von [readNewestValid]. Die beiden Flags sind fuer den Aufrufer
     * KEINE Kosmetik: `anyCandidateExisted` unterscheidet den echten Erststart
     * (kein Hold) vom Datenverlust (Hold), `anyCandidateInvalid` meldet einen
     * STILLEN Generationsverlust - mindestens eine existierende Generation war
     * unlesbar, auch wenn eine andere noch gewaehlt werden konnte.
     */
    data class ReadResult(
        val content: String?,
        val anyCandidateExisted: Boolean,
        val anyCandidateInvalid: Boolean,
        /**
         * WELCHE Kandidaten ungueltig waren (Codex C8d). Ohne die Dateiliste
         * kann der Aufrufer sie nicht [quarantineInvalid] uebergeben - und
         * genau ihre Ueberschreibung durch die Rotation war der Weg, auf dem
         * der Recovery-Hold verschwand.
         */
        val invalidFiles: List<File> = emptyList(),
    )

    /** @return true, wenn die Hauptdatei nach dem Aufruf den neuen Inhalt
     *  traegt. false heisst: alte Generation steht unveraendert. */
    /**
     * Was der letzte [write] gekostet hat - fuer den Test-Build.
     *
     * KEINE GESUNDHEITSDATEN und kein privater Pfad: nur Bytes, Dauern und
     * das Sync-Ergebnis. Damit ist die Frage "wie teuer sind zwei fsyncs pro
     * Minute auf diesem Geraet" beantwortbar, ohne an die Ledger-Datei selbst
     * heranzukommen - die liegt im App-privaten Speicher und ist ohne
     * debuggable-Build nicht lesbar.
     */
    /**
     * WO es gescheitert ist, als geschlossene Menge.
     *
     * Ein Boolean sagte nur "nicht durabel" - und genau die Unterscheidung
     * ist die diagnostische: ein Datei-Sync-Fehler ist ein Medium, ein
     * Rename-Fehler ein Dateisystem oder eine Rechtefrage, ein
     * Rueckleseversager eine stille Inhaltsvernichtung. Als Enum, weil ein
     * neuer Fehlerpfad beim Kompilieren auffallen soll.
     *
     * Bewusst OHNE Exceptiontext und OHNE Pfad: das hier geht in den Export,
     * und ein Pfad traegt den Nutzernamen.
     */
    enum class PersistOutcome { OK, FILE_SYNC_FAILED, RENAME_FAILED, DIR_SYNC_FAILED, READBACK_FAILED }

    data class PersistStats(
        /** Tatsaechliche UTF-8-Bytezahl des Inhalts. */
        val bytes: Int,
        /** Alle Dauern aus `System.nanoTime` - MONOTON. Eine Wanduhr kann
         *  waehrend der Messung springen (NTP, Zeitzone, Nutzer) und liefert
         *  dann negative oder absurde Latenzen. */
        val totalMs: Long,
        val fileSyncMs: Long,
        val dirSyncMs: Long,
        val outcome: PersistOutcome,
    ) {

        val ok: Boolean get() = outcome == PersistOutcome.OK
    }

    @Volatile
    var lastPersistStats: PersistStats? = null
        private set

    fun write(dir: File, content: String): Boolean = writeWithOutcome(dir, content).ok

    /**
     * Wie [write], meldet aber WO es gescheitert ist.
     *
     * Die Statistik wird IMMER gesetzt, auch im Fehlerfall - sonst haette
     * ausgerechnet der interessante Fall keine Zahlen, und die Telemetrie
     * haenge an dem Erfolg, den sie messen soll.
     */
    fun writeWithOutcome(dir: File, content: String): PersistStats {
        val t0 = System.nanoTime()
        var fileSyncNs = 0L
        var dirSyncNs = 0L
        var wo = PersistOutcome.OK
        runCatching {
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                wo = PersistOutcome.RENAME_FAILED
                return@runCatching
            }
            val target = File(dir, FILE_NAME)
            val tmp = File(dir, "$FILE_NAME.tmp")
            val bak = File(dir, "$FILE_NAME.bak")

            // BYTES, FLUSH, FSYNC - UND ERST DANN DER RENAME. Ein Rename VOR
            // dem fsync waere die schlimmste Reihenfolge: dann zeigt der
            // Zielname auf einen Inhalt, den es nach einem Stromverlust nicht
            // gibt, und die Rotation hat die letzte gute Generation schon nach
            // .bak gedreht.
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                    val a = System.nanoTime()
                    durability.syncFile(out.fd)
                    fileSyncNs = System.nanoTime() - a
                }
            } catch (e: Exception) {
                wo = PersistOutcome.FILE_SYNC_FAILED
                return@runCatching
            }

            if (target.exists()) {
                // Genau EINE Vorgenerationen-Kopie: die letzte vollstaendige.
                bak.delete()
                if (!target.renameTo(bak)) {
                    wo = PersistOutcome.RENAME_FAILED
                    return@runCatching
                }
            }
            if (!tmp.renameTo(target)) {
                wo = PersistOutcome.RENAME_FAILED
                return@runCatching
            }

            // DAS VERZEICHNIS, HART. Nach dem Rename ist der INHALT durabel,
            // der Verzeichniseintrag noch nicht zwingend. Fail-closed ist hier
            // vertretbar, obwohl der Rename schon lief: es kostet hoechstens
            // eine unterlassene Dosis, und die Revisionsauswahl beim naechsten
            // Start rettet weiterhin aus target, .tmp oder .bak.
            try {
                val a = System.nanoTime()
                durability.syncDirectory(dir)
                dirSyncNs = System.nanoTime() - a
            } catch (e: Exception) {
                wo = PersistOutcome.DIR_SYNC_FAILED
            }
        }.onFailure { wo = PersistOutcome.RENAME_FAILED }

        return PersistStats(
            bytes = content.toByteArray(Charsets.UTF_8).size,
            totalMs = (System.nanoTime() - t0) / 1_000_000,
            fileSyncMs = fileSyncNs / 1_000_000,
            dirSyncMs = dirSyncNs / 1_000_000,
            outcome = wo,
        ).also { lastPersistStats = it }
    }

    /**
     * [write] plus RUECKLESEPROBE (Audit 2d273cb, 6.1): Erfolg heisst, das
     * Ziel existiert und traegt exakt den geschriebenen Inhalt. Ein
     * Dateisystem, das den Rename meldet, aber den Inhalt verliert, faellt
     * damit als Fehlschlag auf - und der Aufrufer publiziert nicht. Nie
     * werfend.
     *
     * WAS SIE ALLEIN NICHT BEWEIST, und das war bis zum 12.08. nicht
     * aufgeschrieben: sie liest denselben Page Cache, in den geschrieben
     * wurde. Ohne den fsync in [write] meldet sie Erfolg fuer eine Datei,
     * die nach einem Stromverlust leer oder halb waere. Die Probe belegt den
     * RENAME und den INHALT - die DURABILITAET belegt der fsync.
     */
    fun writeVerified(dir: File, content: String): Boolean {
        val stats = writeWithOutcome(dir, content)
        if (!stats.ok) return false
        val gelesen = runCatching {
            val target = File(dir, FILE_NAME)
            target.exists() && target.readText(Charsets.UTF_8) == content
        }.getOrDefault(false)
        if (!gelesen) lastPersistStats = stats.copy(outcome = PersistOutcome.READBACK_FAILED)
        return gelesen
    }

    /**
     * Juengste GUELTIGE Generation aus allen DREI Kandidaten (`.tmp`,
     * Hauptdatei, `.bak`).
     *
     * WARUM `.tmp` mitzaehlt (REG-01b): ein Kill zwischen `target->bak` und
     * `tmp->target` hinterlaesst die NEUESTE Generation ausschliesslich als
     * `.tmp` - ein Leser, der sie ignoriert, verliert genau den Vorschlag,
     * der moeglicherweise schon geliefert wurde. Gewaehlt wird nach der vom
     * Aufrufer decodierten `revision` (hoechste gewinnt; bei Gleichstand die
     * juengere Generation in Kandidatenreihenfolge), nicht nach Dateinamen -
     * eine verwaiste ALTE `.tmp` darf eine juengere Hauptdatei nicht schlagen.
     *
     * `validate` decodiert tolerant: revision bei gueltigem Inhalt, null bei
     * unlesbar/invalid. Ein Wurf aus `validate` zaehlt als invalid. Nie
     * werfend.
     */
    fun readNewestValid(dir: File, validate: (String) -> Long?): ReadResult = runCatching {
        val candidates = listOf(File(dir, "$FILE_NAME.tmp"), File(dir, FILE_NAME), File(dir, "$FILE_NAME.bak"))
        var anyExisted = false
        var anyInvalid = false
        val invalid = mutableListOf<File>()
        var best: String? = null
        var bestRevision = Long.MIN_VALUE
        for (f in candidates) {
            if (!runCatching { f.exists() }.getOrDefault(false)) continue
            anyExisted = true
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
            val revision = text?.let { t -> runCatching { validate(t) }.getOrNull() }
            if (text == null || revision == null) {
                anyInvalid = true
                invalid += f
                continue
            }
            // Striktes '>' laesst bei Revisionsgleichstand den FRUEHEREN
            // Kandidaten stehen - und der ist in der Liste der juengere
            // (tmp vor target vor bak).
            if (best == null || revision > bestRevision) {
                best = text
                bestRevision = revision
            }
        }
        ReadResult(best, anyExisted, anyInvalid, invalid.toList())
    }.getOrDefault(ReadResult(null, false, false))
}

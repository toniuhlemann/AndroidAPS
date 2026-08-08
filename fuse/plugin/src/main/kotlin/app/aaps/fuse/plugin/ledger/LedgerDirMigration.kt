package app.aaps.fuse.plugin.ledger

import java.io.File

/**
 * EINMALIGER Umzug des Ledgers vom alten geteilten Verzeichnis ins
 * app-private Ziel (Fix 8 / Fix 1a, Re-Audit c750169 REG-03).
 *
 * Als reines Datei-auf-Datei-Objekt aus FusePlugin herausgezogen (Codex R4,
 * N.1): die Kill-/Fehlerpfade der Migration (Ziel-Rename ohne Sentinel,
 * blockierter Sentinel, Kopierfehler) brauchen rote Tests gegen ECHTE
 * Dateien in TempDirs - und die gehen nur ohne Android-Abhaengigkeiten.
 * FusePlugin.migrateLedgerDirOnce liefert nur noch die beiden Verzeichnisse
 * und haelt das Prozessflag; JEDE Entscheidung faellt hier.
 *
 * Die alten Dateien werden nach `.migrated` umbenannt, nicht geloescht -
 * ein Rueckbau/Vergleich bleibt moeglich, aber ein zweiter Lauf findet sie
 * nicht mehr als Kandidaten.
 *
 * FAIL-CLOSED: jeder Fehlschlag liefert false (der naechste invoke versucht
 * erneut), und der Aufrufer setzt fuer diesen Lauf den Migrations-Hold am
 * Adapter. Kopiert wird ueber eine `.migtmp`-Zwischendatei mit
 * Rueckleseprobe und erst dann umbenannt: ein halb geschriebenes Ziel darf
 * nie wie eine fertige Generation aussehen.
 */
object LedgerDirMigration {

    /**
     * @return true, wenn die Vorgeschichte sicher uebernommen ist oder es
     * nachweislich nichts zu uebernehmen gibt. Nie werfend.
     */
    fun migrate(
        oldDir: File,
        newDir: File,
        logError: (String) -> Unit = {},
        logDebug: (String) -> Unit = {},
    ): Boolean = runCatching {
        val names = listOf(
            FuseLedgerStore.FILE_NAME,
            FuseLedgerStore.FILE_NAME + ".bak",
        )
        if (names.any { File(newDir, it).exists() }) {
            // R4-01 (b/d): der Fruehausstieg "Ziel traegt schon eine
            // Generation" zieht einen FEHLENDEN Sentinel verifiziert nach.
            // Genau hier landet der Neustart nach einem Kill zwischen
            // Ziel-Rename und Sentinel-Schreiben - ein "fertig" ohne Marker
            // liesse einen spaeteren Dateiverlust wie einen Erststart
            // aussehen. Scheitert der Nachzug, gilt die Migration weiter als
            // ausstehend (migrationPending, konservativer Hold beim Aufrufer).
            val ok = FuseLedgerStore.writeSentinel(newDir)
            if (!ok) logError("FUSE ledger migration: Sentinel-Nachzug fehlgeschlagen (dir=$newDir)")
            return@runCatching ok
        }
        val oldFiles = names.map { File(oldDir, it) }.filter { it.exists() }
        // Echter Erststart: nichts zu uebernehmen ist Erfolg - und darf
        // KEINEN Sentinel anlegen, der wuerde spaeter einen Datenverlust
        // behaupten, den es nie gab. Den ersten Marker schreibt der erste
        // erfolgreiche persistVerified.
        if (oldFiles.isEmpty()) return@runCatching true
        if (!newDir.mkdirs() && !newDir.exists()) return@runCatching false
        for (old in oldFiles) {
            val content = old.readText(Charsets.UTF_8)
            val tmp = File(newDir, old.name + ".migtmp")
            tmp.writeText(content, Charsets.UTF_8)
            // Rueckleseprobe wie beim Store: "geschrieben" heisst erst
            // dann etwas, wenn der Inhalt wieder herauskommt.
            if (tmp.readText(Charsets.UTF_8) != content) return@runCatching false
            if (!tmp.renameTo(File(newDir, old.name))) return@runCatching false
        }
        // R4-01 (a): der Sentinel haelt persistent fest, DASS es einen Ledger
        // gab - und er ist VERTRAGSBESTANDTEIL des Abschlusses, kein
        // Achselzucken: schlaegt er fehl, ist die Migration NICHT fertig
        // (false -> migrationPending), und das Alte bleibt unrotiert fuer
        // den naechsten Versuch stehen.
        if (!FuseLedgerStore.writeSentinel(newDir)) {
            logError("FUSE ledger migration: Sentinel konnte nicht geschrieben werden (dir=$newDir)")
            return@runCatching false
        }
        for (old in oldFiles) {
            // Tolerant: das Ziel traegt die Generation bereits; ein
            // haengengebliebenes Original wird beim naechsten Lauf durch
            // den "Ziel existiert schon"-Fruehausstieg ignoriert.
            if (!old.renameTo(File(oldDir, old.name + ".migrated")))
                logError("FUSE ledger migration: rename to .migrated failed for ${old.name}")
        }
        logDebug("FUSE ledger migrated to app-private storage (${oldFiles.size} file(s))")
        true
    }.getOrElse {
        logError("FUSE ledger migration failed: $it")
        false
    }
}

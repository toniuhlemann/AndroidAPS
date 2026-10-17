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
        // ALLE DREI Generationen, nicht zwei (Auditbefund 10.08.2026).
        //
        // `.tmp` ist laut [FuseLedgerStore.readNewestValid] (REG-01b) der
        // Traeger der NEUESTEN Generation nach einem Kill zwischen
        // `target->bak` und `tmp->target`. Stand im alten Verzeichnis nur
        // `.bak` + `.tmp`, wanderte bisher ausschliesslich die AELTERE
        // `.bak` mit - und die Migration meldete Erfolg.
        //
        // Das ist die schlimmste Richtung: im Ziel liegt dann eine LESBARE,
        // gueltige Generation, also greift keine der vier Hold-Quellen. Die
        // juengste Zeile - typisch der zuletzt publizierte, moeglicherweise
        // schon abgegebene SMB - verschwindet still aus Haftung, Headroom
        // und Schwanz. Die Abwesenheit der Datei im Ziel wird als Nachweis
        // gelesen, dass es sie nie gab.
        //
        // Der Reparaturweg quarantaeniert laengst alle drei Namen; nur
        // diese Stelle kannte zwei.
        val names = listOf(
            FuseLedgerStore.FILE_NAME,
            FuseLedgerStore.FILE_NAME + ".bak",
            FuseLedgerStore.FILE_NAME + ".tmp",
        )
        // ---- Vollstaendigkeitspruefung statt Existenzpruefung -------------
        //
        // DER BEFUND (Toni 18.08.). Hier stand ein Fruehausstieg
        // `if (names.any { File(newDir, it).exists() }) { Sentinel; return }`
        // - er entschied ueber EXISTENZ, nicht ueber VOLLSTAENDIGKEIT. Die
        // Kopierschleife darunter laeuft aber in der Reihenfolge target,
        // .bak, .tmp und kann an JEDER Iteration mit `false` abbrechen
        // (ENOSPC, Prozesstod, Rechte). `.tmp` - der Traeger der NEUESTEN
        // Generation - wird ZULETZT kopiert.
        //
        // DER ABLAUF, DER DABEI HERAUSKAM:
        //
        //   Kill zwischen `target->bak` und `tmp->target` im alten
        //   Verzeichnis. Dort liegen target (rev R), .bak (R-1) und .tmp
        //   (R+1, die zuletzt publizierte, moeglicherweise schon abgegebene
        //   SMB-Zeile).
        //
        //   Umzug startet, kopiert target - und scheitert bei .bak oder
        //   .tmp. false, migrationPending, naechster Versuch.
        //
        //   Zweiter Anlauf: `File(newDir, FILE_NAME).exists()` ist true, der
        //   Fruehausstieg schrieb den Sentinel und meldete "die Vorgeschichte
        //   ist sicher uebernommen". `.tmp` blieb fuer immer im alten
        //   Verzeichnis.
        //
        // Im Ziel lag damit eine LESBARE, im Codec GUELTIGE Generation R:
        // keine der vier Hold-Quellen greift, `readNewestValid` waehlt sie
        // ohne Beanstandung. Eine intakte AELTERE verdraengt die neuere, und
        // die Abwesenheit der `.tmp` im Ziel wird als Beweis gelesen, dass es
        // sie nie gab - genau die Wirkung, die der Kommentar oben als "die
        // schlimmste Richtung" beschreibt. Er war gegen das Weglassen von
        // `.tmp` in der NAMENSLISTE gebaut und deckte den Abbruch MITTEN in
        // der Schleife nicht ab.
        //
        // Der Umzug ist deshalb jetzt eine IDEMPOTENTE Vollstaendigkeits-
        // pruefung: fuer jede im Altverzeichnis vorhandene Generation muss
        // das Ziel vorhanden UND inhaltlich identisch sein. Bereits korrekt
        // kopierte Dateien werden uebersprungen, ein Crash nach jeder
        // einzelnen Kopie zieht die restlichen beim naechsten Lauf nach.
        val oldFiles = names.map { File(oldDir, it) }.filter { it.exists() }
        if (oldFiles.isEmpty()) {
            // KEIN Kandidat mehr im Alt-Verzeichnis. Zwei Lagen, die sich
            // genau am Ziel unterscheiden:
            if (names.any { File(newDir, it).exists() }) {
                // R4-01 (b/d): die Migration ist durch - ihre Originale
                // liegen als `.migrated` daneben -, aber der Sentinel fehlt.
                // Genau hier landet der Neustart nach einem Kill zwischen
                // Ziel-Rename und Sentinel-Schreiben; ein "fertig" ohne
                // Marker liesse einen spaeteren Dateiverlust wie einen
                // Erststart aussehen. Scheitert der Nachzug, gilt die
                // Migration weiter als ausstehend (migrationPending,
                // konservativer Hold beim Aufrufer).
                val ok = FuseLedgerStore.writeSentinel(newDir)
                if (!ok) logError("FUSE ledger migration: Sentinel-Nachzug fehlgeschlagen (dir=$newDir)")
                return@runCatching ok
            }
            // Echter Erststart: nichts zu uebernehmen ist Erfolg - und darf
            // KEINEN Sentinel anlegen, der wuerde spaeter einen Datenverlust
            // behaupten, den es nie gab. Den ersten Marker schreibt der erste
            // erfolgreiche persistVerified.
            return@runCatching true
        }
        if (!newDir.mkdirs() && !newDir.exists()) return@runCatching false
        var nachgezogen = 0
        for (old in oldFiles) {
            val content = old.readText(Charsets.UTF_8)
            val ziel = File(newDir, old.name)
            if (ziel.exists()) {
                // SCHON KORREKT KOPIERT - ueberspringen. Das ist der Fall,
                // der den Umzug idempotent macht: ein abgebrochener Lauf
                // wiederholt die fertigen Schritte nicht, sondern zieht nur
                // die fehlenden nach.
                if (ziel.readText(Charsets.UTF_8) == content) continue
                // ABWEICHENDER INHALT - NICHT UEBERSCHREIBEN.
                //
                // Hier stehen zwei verschiedene Generationen unter demselben
                // Namen, und dieser Code kann nicht wissen, welche gilt: das
                // Ziel koennte eine NEUERE tragen, weil FUSE dort schon lief.
                // Ueberschreiben hiesse, eine moeglicherweise juengere Haftung
                // durch eine aeltere zu ersetzen - die Fehlerrichtung, gegen
                // die dieser ganze Block gebaut ist. Also Hold, und ein Mensch
                // schaut nach.
                logError(
                    "FUSE ledger migration: Ziel ${old.name} existiert mit ABWEICHENDEM Inhalt " +
                        "(alt=${content.length} B, neu=${ziel.length()} B) - kein Ueberschreiben, Hold"
                )
                return@runCatching false
            }
            val tmp = File(newDir, old.name + ".migtmp")
            tmp.writeText(content, Charsets.UTF_8)
            // Rueckleseprobe wie beim Store: "geschrieben" heisst erst
            // dann etwas, wenn der Inhalt wieder herauskommt.
            if (tmp.readText(Charsets.UTF_8) != content) return@runCatching false
            if (!tmp.renameTo(ziel)) return@runCatching false
            nachgezogen++
        }
        // ---- Erst NACH dem vollstaendigen Nachweis aller Kandidaten -------
        //
        // R4-01 (a): der Sentinel haelt persistent fest, DASS es einen Ledger
        // gab - und er ist VERTRAGSBESTANDTEIL des Abschlusses, kein
        // Achselzucken: schlaegt er fehl, ist die Migration NICHT fertig
        // (false -> migrationPending), und das Alte bleibt unrotiert fuer
        // den naechsten Versuch stehen.
        //
        // Seine Stelle ist jetzt hinter der Schleife und NUR dort: er darf
        // erst behaupten "uebernommen", wenn JEDER Kandidat nachgewiesen ist.
        if (!FuseLedgerStore.writeSentinel(newDir)) {
            logError("FUSE ledger migration: Sentinel konnte nicht geschrieben werden (dir=$newDir)")
            return@runCatching false
        }
        for (old in oldFiles) {
            // Tolerant: das Ziel traegt die Generation bereits; ein
            // haengengebliebenes Original wird beim naechsten Lauf ueber die
            // Inhaltsgleichheit uebersprungen.
            if (!old.renameTo(File(oldDir, old.name + ".migrated")))
                logError("FUSE ledger migration: rename to .migrated failed for ${old.name}")
        }
        logDebug("FUSE ledger migration: ${oldFiles.size} Kandidat(en), $nachgezogen nachgezogen")
        true
    }.getOrElse {
        logError("FUSE ledger migration failed: $it")
        false
    }
}

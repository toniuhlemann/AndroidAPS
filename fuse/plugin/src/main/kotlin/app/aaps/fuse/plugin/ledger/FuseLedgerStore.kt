package app.aaps.fuse.plugin.ledger

import java.io.File

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
class FuseLedgerStore {

    companion object {

        const val FILE_NAME = "fuse_ledger.json"
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
    )

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
     * [write] plus RUECKLESEPROBE (Audit 2d273cb, 6.1): Erfolg heisst, das
     * Ziel existiert und traegt exakt den geschriebenen Inhalt. Ein
     * Dateisystem, das den Rename meldet, aber den Inhalt verliert, faellt
     * damit als Fehlschlag auf - und der Aufrufer publiziert nicht. Nie
     * werfend.
     */
    fun writeVerified(dir: File, content: String): Boolean = runCatching {
        if (!write(dir, content)) return@runCatching false
        val target = File(dir, FILE_NAME)
        target.exists() && target.readText(Charsets.UTF_8) == content
    }.getOrDefault(false)

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
        var best: String? = null
        var bestRevision = Long.MIN_VALUE
        for (f in candidates) {
            if (!runCatching { f.exists() }.getOrDefault(false)) continue
            anyExisted = true
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
            val revision = text?.let { t -> runCatching { validate(t) }.getOrNull() }
            if (text == null || revision == null) {
                anyInvalid = true
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
        ReadResult(best, anyExisted, anyInvalid)
    }.getOrDefault(ReadResult(null, false, false))
}

package app.aaps.fuse.plugin.ledger

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * ZUSTANDSERHALTENDE WIEDERHERSTELLUNG NACH UNTERBROCHENEM SPEICHERN
 * (Tonis Auftrag 15.09.).
 *
 * ## Anlass
 *
 * Bleibt [FuseLedgerStore.SEAL_PENDING_NAME] liegen, haelt der Ledger beim naechsten
 * Start an (RECOVERY_HOLD). Der einzige bisherige Ausgang war [FuseLedgerRepair] -
 * sie verwirft Haftung, Mahlzeiten-Huelle und den Genau-einmal-Riegel und ist an
 * einer echten Pumpe deshalb gesperrt. Dieser Weg VERWIRFT NICHTS: er uebernimmt
 * genau den Stand, der versiegelt werden sollte - aber NUR, wenn er technisch
 * nachgewiesen vorliegt. Zustimmung ersetzt keinen Nachweis: bei mehrdeutigen
 * Generationen bleibt der Hold.
 *
 * ## Was als Nachweis gilt
 *
 * Das Speicherprotokoll ist: Marker -> `.tmp` schreiben + fsync -> target->`.bak`
 * -> `.tmp`->target -> Verzeichnis-Sync + Rueckleseprobe -> Sentinel -> Marker weg.
 *
 *  - MARKER v2 (`SEAL_PENDING v2 tx=.. rev=R sha256=H bytes=N`): eine lesbare
 *    Generation (`.tmp`, target oder `.bak`) mit Pruefsumme H und Revision R traegt
 *    exakt den unterbrochenen Stand. Keine solche Generation -> Hold. Eine
 *    Generation mit GLEICHER Revision, aber anderem Inhalt (Episoden,
 *    Eingriffsstempel) ist KEIN Nachweis - die Revision steigt nicht bei jeder
 *    Aenderung.
 *  - ALTMARKER (`SEAL_PENDING rev=R`, vor 15.09. geschrieben, ohne Pruefsumme):
 *    nachweisbar ist nur eine vollstaendig lesbare `.tmp` mit Revision R. Sie
 *    kann nur aus DIESEM Vorgang stammen: `.tmp` wird beim Oeffnen geleert, ein
 *    frueherer gelungener Vorgang benennt sie um, ein frueherer gescheiterter
 *    hinterliesse seinen eigenen Marker (und dann gaebe es keinen spaeteren
 *    Vorgang), eine unterbrochene Reparatur hinterlaesst
 *    [FuseLedgerStore.REPAIR_PENDING_NAME] (-> Hold), und die Schema-Migration
 *    schreibt unter einem Marker nicht (Adapter, und keine Generation darf eine
 *    Migration verlangen). Ohne `.tmp` ist der Zielname MEHRDEUTIG - er kann den
 *    aelteren oder den neuen Stand tragen -> Hold.
 *
 * ## Absturzfester Ablauf ([perform])
 *
 *   1. Belegkopie des nachgewiesenen Inhalts durabel schreiben (neue Datei).
 *   2. Transaktionsmarker [FuseLedgerStore.RECOVERY_PENDING_NAME] mit Beleg,
 *      Pruefsumme, Ausloeser und Grund durabel setzen.
 *   3. Den Stand als Generation schreiben (normale Rotation) und die
 *      Generationswahl gegen die Pruefsumme gegenpruefen.
 *   4. Sentinel und Protokollzeile (idempotent je Transaktion).
 *   5. Versiegelungsmarker entfernen, ganz zuletzt den Transaktionsmarker.
 *
 * Solange einer der beiden Marker liegt, haelt der Ledger beim Laden an und
 * schreibt nicht (Adapter). Nach JEDEM Abbruch bleibt mindestens ein Marker; eine
 * erneute Vormerkung setzt am Beleg fort. Einen ungesperrten Zwischenzustand gibt
 * es nicht.
 *
 * ## Was dieser Weg ausdruecklich NICHT tut
 *
 * Er sendet nichts. Der Ledger ist Buchhaltung, kein Sendeweg: Pumpenauftraege
 * entstehen ausschliesslich im Zyklus aus der aktuellen Entscheidung. Er setzt
 * keine Zeile zurueck, quittiert nichts und schreibt nichts ab - offene
 * Buchungen (auch eine im unterbrochenen Zyklus gebuchte, nie gesendete) bleiben
 * Haftung und werden regulaer abgeglichen. Belegt ist nur: der unterbrochene
 * Gate-Aufruf hat bei fehlgeschlagener Persistenz keinen NEUEN SMB veroeffentlicht;
 * frueher veroeffentlichte oder laufende Auftraege sind damit nicht ausgeschlossen
 * und stehen weiter als Haftung im Ledger.
 *
 * Er laeuft nie von selbst - nur aus einer ausdruecklich vorgemerkten
 * Bedienhandlung mit Grund.
 */
object FuseLedgerSealRecovery {

    const val LOG_NAME = "fuse_ledger.recovery.jsonl"
    const val EVIDENCE_SUFFIX = ".evidence."

    private const val TMP = "${FuseLedgerStore.FILE_NAME}.tmp"
    private const val BAK = "${FuseLedgerStore.FILE_NAME}.bak"

    /** Geparster Versiegelungsmarker. [version] 1 = Altmarker ohne Pruefsumme. */
    data class SealMarker(val version: Int, val tx: String?, val revision: Long, val sha256: String?, val bytes: Int?) {
        companion object {
            private val ALT = Regex("""^SEAL_PENDING rev=(\d+)$""")

            fun parse(text: String?): SealMarker? {
                val t = text?.trim() ?: return null
                ALT.matchEntire(t)?.let { return SealMarker(1, null, it.groupValues[1].toLong(), null, null) }
                if (!t.startsWith("SEAL_PENDING v2 ")) return null
                val felder = t.removePrefix("SEAL_PENDING v2 ").split(' ')
                    .mapNotNull { f -> f.indexOf('=').takeIf { it > 0 }?.let { f.substring(0, it) to f.substring(it + 1) } }
                    .toMap()
                val rev = felder["rev"]?.toLongOrNull() ?: return null
                val sha = felder["sha256"]?.takeIf { it == "none" || Regex("^[0-9a-f]{64}$").matches(it) } ?: return null
                return SealMarker(2, felder["tx"], rev, sha.takeIf { it != "none" }, felder["bytes"]?.toIntOrNull())
            }
        }
    }

    /** Eine vorgefundene Generation. */
    data class Candidate(
        val name: String,
        val exists: Boolean,
        val content: String?,
        val sha256: String?,
        val revision: Long?,
        val decodable: Boolean,
        val migrationRequired: String?,
    )

    data class Proof(val sourceName: String, val content: String, val sha256: String, val revision: Long, val basis: String)

    /**
     * @param resume eine unterbrochene Wiederherstellung wird am Beleg fortgesetzt.
     * @param openEntries / [grossLiabilityU] des nachgewiesenen Stands - sie BLEIBEN.
     */
    data class Inspection(
        val recoverable: Boolean,
        val why: String,
        val proof: Proof?,
        val resume: Boolean,
        val openEntries: Int?,
        val grossLiabilityU: Double?,
        val candidates: List<String>,
        /** Rein lesender Vergleich fuer den Altmarker-Fall. Enthaelt keine
         *  Freigabeentscheidung und veraendert keine Datei. */
        val diagnostics: String,
    )

    data class RecoveryRequest(val by: String, val reason: String)

    /** Der Transaktionsmarker - traegt, was eine Fortsetzung braucht. */
    data class PendingRecord(
        val tx: String,
        val sha256: String,
        val revision: Long,
        val evidence: String,
        val source: String,
        val by: String,
        val reason: String,
        val ts: Long,
        val marker: String,
    ) {
        fun encode(): String = JSONObject()
            .put("tx", tx).put("sha256", sha256).put("revision", revision).put("evidence", evidence)
            .put("source", source).put("by", by).put("reason", reason).put("ts", ts).put("marker", marker)
            .toString()

        companion object {
            fun parse(text: String?): PendingRecord? = runCatching {
                val o = JSONObject(text ?: return null)
                PendingRecord(
                    o.getString("tx"), o.getString("sha256"), o.getLong("revision"), o.getString("evidence"),
                    o.getString("source"), o.getString("by"), o.getString("reason"), o.getLong("ts"), o.getString("marker"),
                )
            }.getOrNull()
        }
    }

    /** Protokollierter Vorgang (anhaengend in [LOG_NAME]). */
    data class RecoveryRecord(
        val ts: Long,
        val tx: String,
        val by: String,
        val reason: String,
        val source: String,
        val revision: Long,
        val sha256: String,
        val openEntries: Int?,
        val grossLiabilityU: Double?,
        val marker: String,
    )

    /** Schritte des Ablaufs - fuer Absturztests nach jeder Stufe. */
    enum class Step { EVIDENCE_WRITTEN, PENDING_MARKED, STATE_WRITTEN, LOGGED, SEAL_CLEARED }

    sealed interface Result {
        data class Refused(val why: String) : Result
        data class Done(val record: RecoveryRecord, val resumed: Boolean) : Result
    }

    fun inspect(dir: File, store: FuseLedgerStore = FuseLedgerStore()): Inspection {
        val sealPending = FuseLedgerStore.sealPendingExists(dir)
        val recoveryPending = FuseLedgerStore.recoveryPendingExists(dir)
        val kandidaten = lese(dir)
        val liste = kandidaten.filter { it.exists }.map { k ->
            if (k.decodable) "${k.name}: rev=${k.revision}, bytes=${k.content!!.toByteArray(Charsets.UTF_8).size}, sha256=${k.sha256!!.take(16)}...${k.migrationRequired?.let { m -> ", migration=$m" } ?: ""}"
            else "${k.name}: unlesbar"
        }
        val diagnose = diagnose(dir, kandidaten)
        fun held(why: String) = Inspection(false, why, null, false, null, null, liste, diagnose)

        if (!sealPending && !recoveryPending) return held("kein unterbrochenes Speichern - nichts wiederherzustellen")
        if (FuseLedgerStore.repairPendingExists(dir)) return held("eine Reparatur wurde unterbrochen - dafuer ist dieser Weg nicht zustaendig")
        if (FuseLedgerStore.holdExists(dir)) return held("dauerhafter Hold-Marker (Verlustbefund) liegt vor - dafuer ist dieser Weg nicht zustaendig")
        if (!FuseLedgerStore.sentinelExists(dir)) return held("Sentinel fehlt - die Vorgeschichte ist nicht belegt")

        fun proven(content: String, source: String, basis: String, resume: Boolean): Inspection {
            val d = runCatching { LedgerCodec.decode(JSONObject(content)) }.getOrNull()
                ?: return held("der nachgewiesene Inhalt ist nicht decodierbar")
            val offen = d.state.openEntries
            return Inspection(
                true, basis, Proof(source, content, FuseLedgerStore.sha256(content), d.revision, basis), resume,
                offen.size, offen.sumOf { it.grossLiabilityU }, liste, diagnose,
            )
        }

        if (recoveryPending) {
            val rec = PendingRecord.parse(FuseLedgerStore.readRecoveryPending(dir))
                ?: return held("unterbrochene Wiederherstellung mit unlesbarem Transaktionsmarker")
            val beleg = runCatching { File(dir, rec.evidence).takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()
                ?: return held("der Beleg der unterbrochenen Wiederherstellung fehlt (${rec.evidence})")
            if (FuseLedgerStore.sha256(beleg) != rec.sha256)
                return held("der Beleg der unterbrochenen Wiederherstellung weicht von seiner Pruefsumme ab")
            val d = runCatching { LedgerCodec.decode(JSONObject(beleg)) }.getOrNull()
            if (d == null || d.revision != rec.revision || d.migrationRequired != null)
                return held("der Beleg der unterbrochenen Wiederherstellung ist nicht uebernehmbar")
            return proven(beleg, rec.source, "Fortsetzung der unterbrochenen Wiederherstellung (Beleg ${rec.evidence})", resume = true)
        }

        if (kandidaten.any { it.exists && !it.decodable })
            return held("mindestens eine vorhandene Generation ist unlesbar - der unterbrochene Stand liegt nicht nachgewiesen vor")
        if (kandidaten.any { it.migrationRequired != null })
            return held("eine Generation verlangt eine Schema-Migration - die Herkunft der Generationen ist nicht eindeutig")

        val markerText = FuseLedgerStore.readSealPending(dir)
        val marker = SealMarker.parse(markerText)
            ?: return held("der Versiegelungsmarker ist nicht lesbar")

        return when (marker.version) {
            2 -> {
                val sha = marker.sha256
                    ?: return held("der Marker traegt keine Pruefsumme (der Inhalt war nicht bildbar)")
                val treffer = kandidaten.firstOrNull { it.decodable && it.sha256 == sha && it.revision == marker.revision }
                    ?: return held(
                        "keine Generation traegt den unterbrochenen Stand (Pruefsumme rev=${marker.revision}) - " +
                            "uebernommen wuerde ein aelterer Stand"
                    )
                proven(treffer.content!!, treffer.name, "Pruefsumme des unterbrochenen Stands stimmt mit ${treffer.name} ueberein (rev=${marker.revision})", resume = false)
            }

            else -> {
                val tmp = kandidaten.first { it.name == TMP }
                when {
                    !tmp.exists -> held(
                        "Altmarker ohne Pruefsumme und ohne .tmp: der Zielname kann den aelteren oder den " +
                            "unterbrochenen Stand tragen - nicht unterscheidbar"
                    )
                    tmp.revision != marker.revision -> held(".tmp traegt rev=${tmp.revision}, der Altmarker rev=${marker.revision} - nicht zuzuordnen")
                    else -> proven(
                        tmp.content!!, tmp.name,
                        "Altmarker: .tmp ist vollstaendig lesbar und kann nur aus diesem Versiegelungsvorgang stammen (rev=${marker.revision})",
                        resume = false,
                    )
                }
            }
        }
    }

    /**
     * @param absturz nur fuer Tests: wird nach jeder Stufe aufgerufen; ein Wurf
     *   simuliert den Prozesstod an dieser Stelle.
     */
    fun perform(
        dir: File,
        nowTs: Long,
        by: String,
        reason: String,
        store: FuseLedgerStore = FuseLedgerStore(),
        absturz: (Step) -> Unit = {},
    ): Result {
        if (by.isBlank()) return Result.Refused("kein Ausloeser genannt")
        if (reason.isBlank()) return Result.Refused("kein Grund genannt")
        val lage = inspect(dir, store)
        if (!lage.recoverable) return Result.Refused(lage.why)
        val p = lage.proof!!

        val rec: PendingRecord
        if (lage.resume) {
            rec = PendingRecord.parse(FuseLedgerStore.readRecoveryPending(dir))
                ?: return Result.Refused("Transaktionsmarker nicht lesbar")
        } else {
            val tx = "rec-$nowTs-" + java.util.UUID.randomUUID().toString().take(8)
            val beleg = freierName(dir, "${FuseLedgerStore.FILE_NAME}$EVIDENCE_SUFFIX$nowTs")
            if (!store.writeDurableNewFile(dir, beleg, p.content))
                return Result.Refused("die Belegkopie liess sich nicht sichern - nichts veraendert")
            absturz(Step.EVIDENCE_WRITTEN)
            rec = PendingRecord(tx, p.sha256, p.revision, beleg, p.sourceName, by, reason, nowTs, FuseLedgerStore.readSealPending(dir) ?: "")
            if (!store.markRecoveryPending(dir, rec.encode()))
                return Result.Refused("der Transaktionsmarker liess sich nicht setzen - nur die Belegkopie liegt, sonst nichts veraendert")
            absturz(Step.PENDING_MARKED)
        }

        val zielSha = runCatching { FuseLedgerStore.sha256(File(dir, FuseLedgerStore.FILE_NAME).readText(Charsets.UTF_8)) }.getOrNull()
        if (zielSha != rec.sha256 && !store.writeVerified(dir, p.content))
            return Result.Refused("der Stand liess sich nicht schreiben - die Marker bleiben, die Wiederherstellung ist wiederholbar")
        // GEGENPROBE: die Generationswahl beim Laden muss GENAU diesen Stand treffen.
        val gewaehlt = store.readNewestValid(dir) { runCatching { LedgerCodec.decode(JSONObject(it)).revision }.getOrNull() }
        if (gewaehlt.content == null || FuseLedgerStore.sha256(gewaehlt.content) != rec.sha256)
            return Result.Refused("die Generationswahl trifft den wiederhergestellten Stand nicht - die Marker bleiben")
        absturz(Step.STATE_WRITTEN)

        if (!FuseLedgerStore.writeSentinel(dir))
            return Result.Refused("der Sentinel liess sich nicht setzen - die Marker bleiben, wiederholbar")
        val record = RecoveryRecord(
            nowTs, rec.tx, rec.by, rec.reason, rec.source, rec.revision, rec.sha256,
            lage.openEntries, lage.grossLiabilityU, rec.marker,
        )
        if (!appendLog(dir, record, store))
            return Result.Refused("das Protokoll liess sich nicht schreiben - die Marker bleiben, wiederholbar")
        absturz(Step.LOGGED)

        if (!store.clearSealPending(dir))
            return Result.Refused("${FuseLedgerStore.SEAL_PENDING_NAME} liess sich nicht entfernen - wiederholbar")
        absturz(Step.SEAL_CLEARED)
        if (!store.clearRecoveryPending(dir))
            return Result.Refused("${FuseLedgerStore.RECOVERY_PENDING_NAME} liess sich nicht entfernen - wiederholbar")
        return Result.Done(record, lage.resume)
    }

    fun lastRecovery(dir: File): RecoveryRecord? = runCatching {
        val f = File(dir, LOG_NAME)
        if (!f.isFile) return null
        val o = JSONObject(f.readLines(Charsets.UTF_8).lastOrNull { it.isNotBlank() } ?: return null)
        RecoveryRecord(
            o.getLong("ts"), o.getString("tx"), o.getString("by"), o.getString("reason"), o.getString("source"),
            o.getLong("revision"), o.getString("sha256"),
            if (o.isNull("openEntries")) null else o.getInt("openEntries"),
            if (o.isNull("grossLiabilityU")) null else o.getDouble("grossLiabilityU"),
            o.getString("marker"),
        )
    }.getOrNull()

    private fun lese(dir: File): List<Candidate> = listOf(TMP, FuseLedgerStore.FILE_NAME, BAK).map { name ->
        val f = File(dir, name)
        val exists = runCatching { f.isFile }.getOrDefault(false)
        val text = if (exists) runCatching { f.readText(Charsets.UTF_8) }.getOrNull() else null
        val d = text?.let { t -> runCatching { LedgerCodec.decode(JSONObject(t)) }.getOrNull() }
        Candidate(name, exists, text, text?.let { FuseLedgerStore.sha256(it) }, d?.revision, d != null, d?.migrationRequired)
    }

    /**
     * Diagnose fuer Altmarker ohne Pruefsumme. Sie zeigt die sicherheitsrelevanten
     * Unterschiede der Generationen, entscheidet aber absichtlich NICHT ueber eine
     * Wiederherstellung. Auch eine plausibel neuere Generation bleibt ohne weiteren
     * Vertrag gesperrt.
     */
    private fun diagnose(dir: File, kandidaten: List<Candidate>): String {
        val marker = FuseLedgerStore.readSealPending(dir)?.trim() ?: "fehlt"
        val gueltig = kandidaten.filter { it.decodable && it.content != null }
        return buildString {
            append("Marker: ").append(marker).append('\n')
            for (k in gueltig) {
                val d = LedgerCodec.decode(JSONObject(k.content!!))
                val e = d.episodes
                append(k.name).append(": rev=").append(d.revision)
                    .append(", bytes=").append(k.content.toByteArray(Charsets.UTF_8).size)
                    .append(", sha256=").append(k.sha256!!.take(16)).append("...\n")
                append("  offen=").append(d.state.openEntries.size)
                    .append(", bruttoU=").append(f3(d.state.openEntries.sumOf { it.grossLiabilityU }))
                    .append(", transportU=").append(f3(d.state.transportCommitmentU))
                    .append(", retiredIds=").append(d.retiredBoundIds.size)
                    .append(", pumpEpochs=").append(d.pumpEpochs.size).append('\n')
                append("  stamp=").append(d.interventionStamp?.epochId ?: "fehlt")
                    .append('#').append(d.interventionStamp?.sequence ?: -1L)
                    .append(", auth=").append(e.markerAuth?.id ?: "keine")
                    .append(", foundationAuth=").append(e.foundationArmedByAuthId ?: "keine").append('\n')
                append("  foundation=").append(e.foundation.armedTs)
                    .append("/").append(f3(e.foundation.totalBudgetU)).append("U")
                    .append(", phaseA=").append(f3(e.deliveredPhaseAU))
                    .append(", seitUebergabe=").append(f3(e.deliveredSinceHandoverU))
                    .append(", evidenz=").append(f3(e.evidenceCommittedU))
                    .append('@').append(e.evidenceCommitmentRevision).append('\n')
                append("  sourceTs=").append(e.lastAcceptedSourceTs)
                    .append(", turnTs=").append(e.markerTurnTs)
                    .append(", riseSeen=").append(e.markerRiseSeen)
                    .append(", rearmUntil=").append(e.livenessReArmUntilTs).append('\n')
            }
            val target = gueltig.firstOrNull { it.name == FuseLedgerStore.FILE_NAME }
            val bak = gueltig.firstOrNull { it.name == BAK }
            if (target != null && bak != null) {
                val diffs = mutableListOf<String>()
                jsonDiff("$", JSONObject(bak.content!!), JSONObject(target.content!!), diffs, 80)
                append("Vergleich bak -> json: ").append(diffs.size)
                    .append(if (diffs.size >= 80) " oder mehr" else "").append(" Unterschied(e)\n")
                diffs.forEach { append("  ").append(it).append('\n') }
            }
        }.trimEnd()
    }

    private fun f3(v: Double): String = "%.3f".format(java.util.Locale.US, v)

    private fun jsonDiff(path: String, alt: Any?, neu: Any?, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        if (alt is JSONObject && neu is JSONObject) {
            val keys = (alt.keys().asSequence().toSet() + neu.keys().asSequence().toSet()).sorted()
            for (key in keys) {
                if (out.size >= limit) return
                val a = if (alt.has(key)) alt.opt(key) else FEHLT
                val n = if (neu.has(key)) neu.opt(key) else FEHLT
                jsonDiff("$path.$key", a, n, out, limit)
            }
            return
        }
        if (alt is JSONArray && neu is JSONArray) {
            val max = maxOf(alt.length(), neu.length())
            for (i in 0 until max) {
                if (out.size >= limit) return
                jsonDiff("$path[$i]", if (i < alt.length()) alt.opt(i) else FEHLT, if (i < neu.length()) neu.opt(i) else FEHLT, out, limit)
            }
            return
        }
        val a = normalisiere(alt)
        val n = normalisiere(neu)
        if (a != n) out += "$path: ${kurz(a)} -> ${kurz(n)}"
    }

    private fun normalisiere(v: Any?): Any? = if (v == JSONObject.NULL) null else v
    private fun kurz(v: Any?): String {
        if (v === FEHLT) return "<fehlt>"
        val s = v?.toString() ?: "null"
        return if (s.length <= 80) s else s.take(77) + "..."
    }

    private val FEHLT = Any()

    private fun freierName(dir: File, basis: String): String {
        var name = basis
        var n = 1
        while (File(dir, name).exists() && n <= 1_000) {
            name = "$basis-$n"; n++
        }
        return name
    }

    /** Anhaengend und durabel; IDEMPOTENT je Transaktion (eine Fortsetzung schreibt keine zweite Zeile). */
    private fun appendLog(dir: File, r: RecoveryRecord, store: FuseLedgerStore): Boolean = runCatching {
        val f = File(dir, LOG_NAME)
        val zeile = JSONObject()
            .put("ts", r.ts).put("tx", r.tx).put("by", r.by).put("reason", r.reason).put("source", r.source)
            .put("revision", r.revision).put("sha256", r.sha256)
            .put("openEntries", r.openEntries ?: JSONObject.NULL)
            .put("grossLiabilityU", r.grossLiabilityU ?: JSONObject.NULL)
            .put("marker", r.marker)
            .toString()
        if (f.isFile && f.readLines(Charsets.UTF_8).any { l -> runCatching { JSONObject(l).getString("tx") == r.tx }.getOrDefault(false) })
            return@runCatching true
        java.io.FileOutputStream(f, true).use { out ->
            out.write((zeile + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            store.syncFile(out.fd)
        }
        f.isFile && f.readLines(Charsets.UTF_8).any { l -> runCatching { JSONObject(l).getString("tx") == r.tx }.getOrDefault(false) }
    }.getOrDefault(false)
}

/**
 * Vormerken statt ausfuehren - aus demselben Grund wie [FuseRepairScheduler]:
 * ausgefuehrt wird am Anfang des naechsten Zyklus, bevor irgendetwas gelesen,
 * gerechnet oder geschrieben wird. Keine Pumpensperre: dieser Weg verwirft nichts.
 */
class FuseSealRecoveryScheduler {

    private val pending = AtomicReference<FuseLedgerSealRecovery.RecoveryRequest?>(null)

    fun request(r: FuseLedgerSealRecovery.RecoveryRequest): Boolean = pending.compareAndSet(null, r)

    val isPending: Boolean get() = pending.get() != null

    fun runIfDue(dir: File, nowTs: Long): FuseLedgerSealRecovery.Result? {
        val auftrag = pending.getAndSet(null) ?: return null
        return FuseLedgerSealRecovery.perform(dir, nowTs, auftrag.by, auftrag.reason)
    }
}

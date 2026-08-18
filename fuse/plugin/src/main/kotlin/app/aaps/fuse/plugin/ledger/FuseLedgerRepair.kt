package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.InterventionStamp
import org.json.JSONObject
import java.io.File

/**
 * DER REPARATURWEG - der einzige Ausgang aus einem nicht quittierbaren Hold.
 *
 * ## Warum es ihn geben muss
 *
 * `IDENTITY_CONFLICT`, `IMPOSSIBLE_STATE_CONFLICT` und ihre Geschwister stehen
 * in `FAIL_CLOSED_ERRORS`, aber NICHT in `RECOVERABLE_ERRORS` - sie sind
 * bewusst nicht quittierbar. Das ist richtig: ein Widerspruch zwischen dem, was
 * der Ledger gebucht hat, und dem, was die Datenbank zeigt, darf sich nicht per
 * Knopfdruck wegdruecken lassen.
 *
 * Nur hatte dieser Zustand bisher ueberhaupt keinen Ausgang. Der Kommentar am
 * Hold-Marker verwies auf einen "noch nicht gebauten Reparatur-Workflow" - und
 * solange der fehlte, war ein einmal gehaltener Ledger dauerhaft tot. Genau das
 * ist am 10.08.2026 auf dem Testtraeger eingetreten: die Kanonisierung der
 * Seriennummer (F7) aenderte die Berechnung der Identitaets-Hashes, jede vorher
 * gespeicherte Identitaet passte nicht mehr zu ihrem eigenen Fakt, und 47
 * Konflikte legten den Ledger still.
 *
 * ## Was dieser Weg TUT - und was ausdruecklich nicht
 *
 * Er LOESCHT NICHTS. Der bisherige Ledger wandert vollstaendig in Quarantaene
 * (`.reset.<ts>`), genau wie eine korrupte Generation. Der Inhalt ist der
 * einzige Anhaltspunkt dafuer, WAS verworfen wurde; wer ihn wegwirft, macht die
 * Reparatur unpruefbar.
 *
 * Er VERSCHWEIGT NICHTS. Was verworfen wurde - Haftung, offene Zeilen, aktive
 * Fehlerarten - wird in `fuse_ledger.reset.jsonl` festgehalten, und zwar
 * ANHAENGEND: jede Reparatur bleibt sichtbar, auch die dritte. Der Export zeigt
 * die letzte Reparatur danach in JEDEM Zyklus. Ohne das saehe ein frisch
 * zurueckgesetzter Ledger exakt aus wie ein unbenutzter, und "keine Haftung"
 * waere von "hier wurde Haftung verworfen" nicht zu unterscheiden - dieselbe
 * Verwechslung, gegen die der Sentinel gebaut ist.
 *
 * Er LAESST DEN SENTINEL STEHEN. Der Ledger HAT existiert. Wuerde die Reparatur
 * ihn mitnehmen, saehe ein spaeterer echter Dateiverlust wie ein Erststart aus.
 *
 * Er IST KEINE ROUTINE. Gibt es nichts zu reparieren - kein Hold-Marker, kein
 * gehaltener Zustand, kein Verlust -, verweigert er. Ein "Ledger leeren"-Knopf
 * fuer den Alltag waere die Umgehung des gesamten Haftungsgedankens.
 *
 * Er LAEUFT NIE VON SELBST. Aufgerufen wird er ausschliesslich aus einer
 * ausdruecklichen Bedienhandlung mit benanntem Grund.
 */
object FuseLedgerRepair {

    /** Quarantaene-Suffix der Reparatur - getrennt von `.corrupt.`, damit im
     *  Verzeichnis unterscheidbar bleibt, ob eine Generation ungueltig WAR
     *  oder ob sie bewusst zurueckgestellt wurde. */
    const val RESET_SUFFIX = ".reset."

    /** Anhaengendes Protokoll. Eine Zeile je Reparatur, nie ueberschrieben. */
    const val LOG_NAME = "fuse_ledger.reset.jsonl"

    /**
     * Was die Reparatur verworfen hat.
     *
     * [stateReadable] `false` heisst: es gab etwas, aber es war nicht mehr
     * lesbar. Dann sind die Zahlen unbekannt und werden NICHT als 0 gemeldet -
     * 0 waere die Aussage "es war nichts offen", und genau die ist es nicht.
     */
    data class Discarded(
        val stateReadable: Boolean,
        val revision: Long?,
        val grossLiabilityU: Double?,
        val transportCommitmentU: Double?,
        val openEntries: Int?,
        val activeErrors: Map<String, Int>,
    )

    /**
     * EIN VORGEMERKTER AUFTRAG - unveraenderlich, traegt Ausloeser und Grund
     * bis zur Ausfuehrung.
     *
     * Ein blosses Boolean-Flag reichte nicht: der Grund entsteht beim Bediener
     * (im Dialog, aus der damaligen Lage) und wird erst eine Runde spaeter
     * gebraucht. Ihn zur Ausfuehrungszeit neu zu bilden hiesse, eine andere
     * Lage zu protokollieren als die, der zugestimmt wurde.
     */
    data class RepairRequest(val by: String, val reason: String)

    /** Ein protokollierter Reparaturvorgang, so wie er im Export erscheint. */
    data class ResetRecord(
        val ts: Long,
        val by: String,
        val reason: String,
        val discarded: Discarded,
    )

    sealed interface Result {

        /** Nichts veraendert. [why] ist fuer den Bediener, nicht fuers Log. */
        data class Refused(val why: String) : Result

        /**
         * @param freshLedgerWritten konnte die leere Nachfolgegeneration
         *   geschrieben werden? `false` heisst: Quarantaene und Hold sind weg,
         *   aber es liegt keine lesbare Generation mehr - der Sentinel macht
         *   daraus beim naechsten Start einen Verlust-Hold. Das ist
         *   fail-closed und richtig, der Bediener muss es aber ERFAHREN,
         *   sonst wundert er sich ueber einen Hold direkt nach der Reparatur.
         */
        data class Done(
            val record: ResetRecord,
            val quarantined: List<String>,
            val freshLedgerWritten: Boolean,
        ) : Result
    }

    /**
     * Was eine Reparatur JETZT vorfaende - ohne irgendetwas zu veraendern.
     *
     * Es gibt sie getrennt von [perform], weil der Bediener VOR der Zusage
     * sehen muss, was er verwirft. Ein Bestaetigungsdialog, der nur "wirklich?"
     * fragt, ist keine Zustimmung zu einer Menge Insulin-Haftung.
     */
    data class Inspection(val repairable: Boolean, val why: String, val discarded: Discarded)

    fun inspect(dir: File, store: FuseLedgerStore = FuseLedgerStore()): Inspection {
        val read = runCatching {
            store.readNewestValid(dir) { runCatching { LedgerCodec.decode(JSONObject(it)).revision }.getOrNull() }
        }.getOrNull()
        val decoded = read?.content?.let { runCatching { LedgerCodec.decode(JSONObject(it)) }.getOrNull() }

        val holdMarker = FuseLedgerStore.holdExists(dir)
        // WRITE-AHEAD-MARKER: seit er klebt, ist er ein eigener Reparaturgrund
        // (Toni 12.08.). Ohne diese Zeile waere der Hold dauerhaft, aber nicht
        // bedienbar - ein Ausgang, den man nicht gehen kann, ist keiner.
        val sealPending = FuseLedgerStore.sealPendingExists(dir)
        // Eine ABGEBROCHENE Reparatur ist selbst ein Reparaturgrund - sonst
        // waere der eine Weg, der aus dem Hold fuehrt, nach seinem eigenen
        // Abbruch versperrt.
        val repairPending = FuseLedgerStore.repairPendingExists(dir)
        val gehalten = decoded?.state?.holdActuation == true
        // Sentinel ohne lesbare Generation = Verlust. Auch das ist ein
        // Reparaturfall, sonst bliebe genau der Zustand ohne Ausgang, fuer den
        // der Sentinel ueberhaupt erfunden wurde.
        val verlust = FuseLedgerStore.sentinelExists(dir) && decoded == null

        val discarded = if (decoded == null) Discarded(false, null, null, null, null, emptyMap())
        else {
            // Dieselbe Bilanz wie im Export (`FuseStateJson`): die Bruttohaftung
            // ist eine Eigenschaft der OFFENEN Zeilen, nicht des Zustands -
            // geschlossene tragen null. Wer hier anders summiert, meldet dem
            // Bediener eine andere Zahl, als er im Trail sieht.
            val offen = decoded.state.openEntries
            Discarded(
                stateReadable = true,
                revision = decoded.revision,
                grossLiabilityU = offen.sumOf { it.grossLiabilityU },
                transportCommitmentU = decoded.state.transportCommitmentU,
                openEntries = offen.size,
                activeErrors = decoded.state.errors.filter { it.active }
                    .groupingBy { it.error.name }.eachCount(),
            )
        }

        val why = when {
            verlust     -> "Vorgeschichte vorhanden, aber keine lesbare Generation (Verlust)"
            // VOR dem Hold-Marker genannt: er beschreibt den unklareren
            // Zustand. Beim Hold wissen wir, WAS verloren ging; hier wissen
            // wir nur, dass ein Versiegelungsvorgang unterbrochen wurde und
            // eine der drei Generationen einen nicht bestaetigten Stand
            // tragen kann.
            repairPending -> "eine Reparatur wurde unterbrochen (${FuseLedgerStore.REPAIR_PENDING_NAME})"
            sealPending -> "ein Versiegelungsvorgang wurde unterbrochen (${FuseLedgerStore.SEAL_PENDING_NAME})"
            holdMarker  -> "dauerhafter Hold-Marker liegt vor"
            gehalten    -> "der Zustand haelt die Aktuation (nicht quittierbarer Fehler)"
            else        -> "nichts zu reparieren - kein Hold, kein Verlust"
        }
        return Inspection(holdMarker || gehalten || verlust || sealPending || repairPending, why, discarded)
    }

    /**
     * @param by wer die Reparatur ausgeloest hat, [reason] warum. Beide
     *   PFLICHT und nicht leer - dieselbe Anforderung wie an eine Quittung
     *   (`LedgerReducer.onHoldAcknowledged`). Eine Reparatur ohne Begruendung
     *   waere in einem halben Jahr nicht mehr nachvollziehbar.
     */
    fun perform(
        dir: File,
        nowTs: Long,
        by: String,
        reason: String,
        store: FuseLedgerStore = FuseLedgerStore(),
    ): Result {
        if (by.isBlank()) return Result.Refused("kein Ausloeser genannt")
        if (reason.isBlank()) return Result.Refused("kein Grund genannt")

        val lage = inspect(dir, store)
        if (!lage.repairable) return Result.Refused(lage.why)
        val discarded = lage.discarded

        // ---- DIE REPARATUR IST SELBST EINE TRANSAKTION (Toni 12.08.) -----
        //
        // Vorher raeumte sie BEIDE Schutzmarker ab und tat danach das
        // Riskante. Ein Absturz direkt nach dem Abraeumen liess die
        // unbestaetigten Generationen ohne jeden Marker zurueck; ein Fehler
        // beim frischen Schreiben konnte sogar ein neues `.tmp` hinterlassen,
        // ebenfalls ungeschuetzt. Der eine Weg aus dem Hold heraus war damit
        // die einzige Stelle ohne Write-ahead-Schutz.
        //
        // Reihenfolge jetzt:
        //   1. Transaktionsmarker durabel setzen
        //   2. Kandidaten in Quarantaene
        //   3. frische Generation, Sentinel und Protokoll NACHWEISLICH
        //   4. Hold-Marker entfernen
        //   5. ganz zuletzt die Transaktionsmarker
        //
        // Bei jedem Fehler bleibt mindestens ein Marker stehen, der Start
        // haelt weiter an, und eine erneute Reparatur ist moeglich.
        if (!store.markRepairPending(dir, "REPAIR_PENDING by=$by ts=$nowTs"))
            return Result.Refused("Transaktionsmarker liess sich nicht setzen - nichts veraendert")

        val quarantined = FuseLedgerStore.quarantine(
            listOf(
                File(dir, "${FuseLedgerStore.FILE_NAME}.tmp"),
                File(dir, FuseLedgerStore.FILE_NAME),
                File(dir, "${FuseLedgerStore.FILE_NAME}.bak"),
            ),
            nowTs, RESET_SUFFIX,
        )

        // EINE LEERE, GUELTIGE NACHFOLGEGENERATION - nicht einfach ein leeres
        // Verzeichnis.
        //
        // Das folgt zwingend aus dem Sentinel: er bleibt stehen, und "Sentinel
        // ohne lesbare Generation" IST die Verlustdefinition. Ohne diese Zeile
        // haette die Reparatur den Hold geoeffnet und beim naechsten Start
        // sofort einen neuen ausgeloest.
        //
        // Die Revision zaehlt weiter statt bei 0 anzufangen: sie ist die
        // Auswahlgroesse zwischen den Generationen, und eine ruecklaufende
        // Nummer waere die einzige Stelle, an der eine wiederauftauchende
        // Altdatei die neue schlagen koennte.
        val freshWritten = runCatching {
            store.writeVerified(
                dir,
                LedgerCodec.encode(
                    app.aaps.fuse.core.ledger.LedgerState(),
                    EpisodeBudgets(),
                    (discarded.revision ?: 0L) + 1L,
                    // EINE REPARATUR EROEFFNET IMMER EINE NEUE EPOCHE (Toni
                    // 18.08.). Anders als die `revision`, die als
                    // Auswahlgroesse zwischen den Generationen weiterzaehlen
                    // MUSS, waere ein fortgeschriebener Eingriffsstempel hier
                    // eine Luege: die verworfene Generation kann Eingriffe
                    // getragen haben, die nie gezaehlt wurden. Der frische
                    // Epochenname macht jeden Eintrag von davor automatisch
                    // INTERVENED - ohne dass die Erwartungsdatei angefasst
                    // werden muesste.
                    InterventionStamp(
                        "repair-$nowTs-" + java.util.UUID.randomUUID().toString().take(8),
                        0L,
                    ),
                    emptyList(),
                    emptyMap(),
                ).toString(),
            )
        }.getOrDefault(false)
        if (!freshWritten)
            return Result.Refused("die frische Generation liess sich nicht schreiben - Marker bleibt, Reparatur wiederholbar")

        if (!FuseLedgerStore.writeSentinel(dir))
            return Result.Refused("der Sentinel liess sich nicht setzen - Marker bleibt, Reparatur wiederholbar")

        // DAS PROTOKOLL IST TEIL DER ZUSAGE, nicht Zierrat: es traegt die
        // Provenienz des Zuruecksetzens. Verschluckte es seinen Fehler, saehe
        // die Reparatur gelungen aus, waehrend genau das fehlte, was sie
        // nachvollziehbar macht.
        val record = ResetRecord(nowTs, by, reason, discarded)
        if (!appendLog(dir, record, store))
            return Result.Refused("das Reparaturprotokoll liess sich nicht schreiben - Marker bleibt, Reparatur wiederholbar")

        // ERST JETZT die Schutzmarker - in dieser Reihenfolge, damit ein
        // Absturz dazwischen den Hold behaelt statt ihn halb zu oeffnen.
        if (!FuseLedgerStore.clearHoldVerified(dir))
            return Result.Refused("Hold-Marker liess sich nicht entfernen - Reparatur wiederholbar")
        if (!store.clearSealPending(dir))
            return Result.Refused("${FuseLedgerStore.SEAL_PENDING_NAME} liess sich nicht entfernen - Reparatur wiederholbar")
        if (!store.clearRepairPending(dir))
            return Result.Refused("${FuseLedgerStore.REPAIR_PENDING_NAME} liess sich nicht entfernen - Reparatur wiederholbar")

        return Result.Done(record, quarantined, freshWritten)
    }

    /**
     * Die LETZTE Reparatur, oder `null`. Der Export haengt sie an jeden Zyklus,
     * damit ein zurueckgesetzter Ledger nie wie ein unbenutzter aussieht.
     *
     * Liest die letzte Zeile des Protokolls. Nie werfend: ein unlesbares
     * Protokoll darf den Zyklus nicht anfassen - dann fehlt die Anzeige, und
     * das ist immer noch besser als ein Ausfall des Reglers.
     */
    fun lastReset(dir: File): ResetRecord? = runCatching {
        val f = File(dir, LOG_NAME)
        if (!f.isFile) return null
        val letzte = f.readLines(Charsets.UTF_8).lastOrNull { it.isNotBlank() } ?: return null
        val o = JSONObject(letzte)
        val d = o.getJSONObject("discarded")
        ResetRecord(
            ts = o.getLong("ts"),
            by = o.getString("by"),
            reason = o.getString("reason"),
            discarded = Discarded(
                stateReadable = d.getBoolean("stateReadable"),
                revision = if (d.isNull("revision")) null else d.getLong("revision"),
                grossLiabilityU = if (d.isNull("grossLiabilityU")) null else d.getDouble("grossLiabilityU"),
                transportCommitmentU =
                    if (d.isNull("transportCommitmentU")) null else d.getDouble("transportCommitmentU"),
                openEntries = if (d.isNull("openEntries")) null else d.getInt("openEntries"),
                activeErrors = d.getJSONObject("activeErrors").let { e ->
                    e.keys().asSequence().associateWith { k -> e.getInt(k) }
                },
            ),
        )
    }.getOrNull()

    fun encode(r: ResetRecord): JSONObject = JSONObject()
        .put("ts", r.ts)
        .put("by", r.by)
        .put("reason", r.reason)
        .put(
            "discarded", JSONObject()
                .put("stateReadable", r.discarded.stateReadable)
                .put("revision", r.discarded.revision ?: JSONObject.NULL)
                .put("grossLiabilityU", r.discarded.grossLiabilityU ?: JSONObject.NULL)
                .put("transportCommitmentU", r.discarded.transportCommitmentU ?: JSONObject.NULL)
                .put("openEntries", r.discarded.openEntries ?: JSONObject.NULL)
                .put(
                    "activeErrors",
                    JSONObject().also { j -> r.discarded.activeErrors.forEach { (k, v) -> j.put(k, v) } })
        )

    /** ANHAENGEND. Ein Ueberschreiben wuerde die vorige Reparatur verschwinden
     *  lassen, und mehrere Reparaturen hintereinander sind genau das Muster,
     *  das jemand spaeter sehen muss. */
    /**
     * Das Reparaturprotokoll fortschreiben - DURABEL und mit Rueckmeldung.
     *
     * Es verschluckte bis zum 12.08. jeden Fehler und meldete nichts. Damit
     * konnte die Reparatur gelungen aussehen, waehrend die versprochene
     * Provenienz fehlte - und niemand haette es gemerkt, denn die einzige
     * Stelle, die davon erzaehlt, ist genau dieses Protokoll.
     *
     * @return ob die Zeile nachweislich auf dem Medium steht.
     */
    private fun appendLog(dir: File, r: ResetRecord, store: FuseLedgerStore): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return@runCatching false
        val f = File(dir, LOG_NAME)
        val zeile = encode(r).toString()
        java.io.FileOutputStream(f, true).use { out ->
            out.write((zeile + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            store.syncFile(out.fd)
        }
        f.isFile && f.readLines(Charsets.UTF_8).lastOrNull { it.isNotBlank() } == zeile
    }.getOrDefault(false)
}

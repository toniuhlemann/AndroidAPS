package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.ledger.AccountingState
import app.aaps.fuse.core.ledger.AmountAxis
import app.aaps.fuse.core.ledger.DeliveryState
import app.aaps.fuse.core.ledger.LedgerError
import app.aaps.fuse.core.ledger.LedgerErrorRecord
import app.aaps.fuse.core.ledger.LedgerPhase
import app.aaps.fuse.core.ledger.LedgerState
import app.aaps.fuse.core.ledger.ProposalEntry
import app.aaps.fuse.core.ledger.PumpTreatmentIdentity
import app.aaps.fuse.core.ledger.QueueRejectReason
import app.aaps.fuse.core.ledger.SnapshotOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * [LedgerState] <-> JSON, VERLUSTFREI (Audit R95, Fix 3).
 *
 * Verlustfrei heisst hier: `decode(encode(s)) == s` als Datenklassen-
 * Gleichheit, fuer JEDES Feld - auch die je Zeile GEPINNTEN Policies
 * (amountEpsU/bolusStepU/conservativeFloorU), die Fehlerhistorie mit
 * Quittungsfeldern und die Snapshot-Ordnung. Ein Feld, das die Persistenz
 * verliert, waere nach dem Neustart eine ANDERE Buchhaltung unter demselben
 * Namen - genau der Zustand, den der Ledger verhindern soll.
 *
 * Doubles gehen als plain JSON-Zahlen: `Double.toString` erzeugt die
 * kuerzeste eindeutig rueckparsbare Form (Java-Garantie), und org.json
 * schneidet nur wertneutrale Nachkomma-Nullen. NaN/Inf koennen nicht
 * auftreten - LedgerRules.isStorableAmount haelt sie aus dem Zustand,
 * und org.json wuerfe beim Schreiben (der Wurf bliebe im runCatching des
 * Aufrufers, die Vorgaengerdatei bleibt stehen).
 *
 * Unbekannte Enum-Namen (Datei aus einer NEUEREN Version) werfen beim
 * Decode - der Adapter behandelt das als "nicht lesbar" und startet leer,
 * statt einen halb geratenen Zustand zu uebernehmen.
 */
object LedgerCodec {

    const val VERSION = 1

    // ---- Gesamtdatei ------------------------------------------------------

    data class Decoded(val state: LedgerState, val episodes: EpisodeBudgets, val revision: Long)

    fun encode(state: LedgerState, episodes: EpisodeBudgets, revision: Long): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("revision", revision)
        .put("state", encodeState(state))
        .put("episodes", encodeEpisodes(episodes))

    fun decode(o: JSONObject): Decoded {
        require(o.getInt("v") == VERSION) { "ledger file version ${o.getInt("v")}" }
        return Decoded(
            state = decodeState(o.getJSONObject("state")),
            episodes = decodeEpisodes(o.getJSONObject("episodes")),
            revision = o.getLong("revision"),
        )
    }

    // ---- LedgerState ------------------------------------------------------

    fun encodeState(s: LedgerState): JSONObject = JSONObject()
        .put("entries", JSONArray(s.entries.values.map { encodeEntry(it) }))
        .put("errors", JSONArray(s.errors.map { encodeError(it) }))
        .putNullable("lastSnapshotOrder", s.lastSnapshotOrder?.let { encodeOrder(it) })
        .putNullable("lastSnapshotViewHash", s.lastSnapshotViewHash)
        .put("holdGeneration", s.holdGeneration)
        .put("seenEpochs", JSONArray(s.seenEpochs.toList()))
        .putNullable("announcedEpochId", s.announcedEpochId)

    fun decodeState(o: JSONObject): LedgerState {
        val entries = o.getJSONArray("entries").objects().map { decodeEntry(it) }
        val seen = o.getJSONArray("seenEpochs")
        return LedgerState(
            entries = entries.associateBy { it.proposalId },
            errors = o.getJSONArray("errors").objects().map { decodeError(it) },
            lastSnapshotOrder = o.objOrNull("lastSnapshotOrder")?.let { decodeOrder(it) },
            lastSnapshotViewHash = o.strOrNull("lastSnapshotViewHash"),
            holdGeneration = o.getLong("holdGeneration"),
            seenEpochs = (0 until seen.length()).map { seen.getString(it) }.toSet(),
            announcedEpochId = o.strOrNull("announcedEpochId"),
        )
    }

    // ---- Episodenbudgets --------------------------------------------------

    fun encodeEpisodes(e: EpisodeBudgets): JSONObject = JSONObject()
        .put("primeSpentU", e.primeSpentU)
        .put("primeArmedTs", e.primeArmedTs)
        .put("onsetSpentU", e.onsetSpentU)
        .put("onsetQuietMin", e.onsetQuietMin)
        .put("mealArmedTs", e.mealArmedTs)
        .put("markerTurnTs", e.markerTurnTs)
        .put("mealDeliveries", JSONArray(e.mealDeliveries.map { (ts, u) -> JSONArray(listOf(ts, u)) }))

    fun decodeEpisodes(o: JSONObject): EpisodeBudgets {
        val e = EpisodeBudgets()
        e.primeSpentU = o.getDouble("primeSpentU")
        e.primeArmedTs = o.getLong("primeArmedTs")
        e.onsetSpentU = o.getDouble("onsetSpentU")
        e.onsetQuietMin = o.getInt("onsetQuietMin")
        e.mealArmedTs = o.getLong("mealArmedTs")
        e.markerTurnTs = o.optLong("markerTurnTs", 0L)
        val md = o.getJSONArray("mealDeliveries")
        for (i in 0 until md.length()) {
            val pair = md.getJSONArray(i)
            e.mealDeliveries.addLast(pair.getLong(0) to pair.getDouble(1))
        }
        return e
    }

    // ---- Einzelteile ------------------------------------------------------

    private fun encodeEntry(e: ProposalEntry): JSONObject = JSONObject()
        .put("proposalId", e.proposalId)
        .put("phase", e.phase.name)
        .put("amounts", encodeAmounts(e.amounts))
        .put("accounting", e.accounting.name)
        .put("delivery", e.delivery.name)
        .putNullable("identity", e.identity?.let { encodeIdentity(it) })
        .putNullable("queueReject", e.queueReject?.name)
        .put("withdrawnProven", e.withdrawnProven)
        .put("contradicted", e.contradicted)
        .putNullable("conservativeFloorU", e.conservativeFloorU)
        .putNullable("accountedAmountU", e.accountedAmountU)
        .put("amountEpsU", e.amountEpsU)
        .put("bolusStepU", e.bolusStepU)
        .putNullable("firstAccountedSnapshotHash", e.firstAccountedSnapshotHash)
        .putNullable("lastReconciledViewHash", e.lastReconciledViewHash)
        .putNullable("lastReconciledAtTs", e.lastReconciledAtTs)
        .put("terminalSeen", e.terminalSeen)
        .put("failClosed", e.failClosed)
        .put("corrections", e.corrections)
        .put("decisionTs", e.decisionTs)
        .put("latestBolusTimestampAtDecision", e.latestBolusTimestampAtDecision)
        .put("errors", JSONArray(e.errors.map { it.name }))

    private fun decodeEntry(o: JSONObject): ProposalEntry {
        val errs = o.getJSONArray("errors")
        return ProposalEntry(
            proposalId = o.getString("proposalId"),
            phase = LedgerPhase.valueOf(o.getString("phase")),
            amounts = decodeAmounts(o.getJSONObject("amounts")),
            accounting = AccountingState.valueOf(o.getString("accounting")),
            delivery = DeliveryState.valueOf(o.getString("delivery")),
            identity = o.objOrNull("identity")?.let { decodeIdentity(it) },
            queueReject = o.strOrNull("queueReject")?.let { QueueRejectReason.valueOf(it) },
            withdrawnProven = o.getBoolean("withdrawnProven"),
            contradicted = o.getBoolean("contradicted"),
            conservativeFloorU = o.dblOrNull("conservativeFloorU"),
            accountedAmountU = o.dblOrNull("accountedAmountU"),
            amountEpsU = o.getDouble("amountEpsU"),
            bolusStepU = o.getDouble("bolusStepU"),
            firstAccountedSnapshotHash = o.strOrNull("firstAccountedSnapshotHash"),
            lastReconciledViewHash = o.strOrNull("lastReconciledViewHash"),
            lastReconciledAtTs = o.lngOrNull("lastReconciledAtTs"),
            terminalSeen = o.getBoolean("terminalSeen"),
            failClosed = o.getBoolean("failClosed"),
            corrections = o.getInt("corrections"),
            decisionTs = o.getLong("decisionTs"),
            latestBolusTimestampAtDecision = o.getLong("latestBolusTimestampAtDecision"),
            errors = (0 until errs.length()).map { LedgerError.valueOf(errs.getString(it)) },
        )
    }

    private fun encodeAmounts(a: AmountAxis): JSONObject = JSONObject()
        .put("proposedU", a.proposedU)
        .putNullable("rtPublishedU", a.rtPublishedU)
        .putNullable("loopConstrainedU", a.loopConstrainedU)
        .putNullable("queueConstrainedU", a.queueConstrainedU)
        .putNullable("pumpCommandU", a.pumpCommandU)
        .putNullable("reportedDeliveredU", a.reportedDeliveredU)
        .putNullable("provenDeliveredU", a.provenDeliveredU)
        .putNullable("dbAccountedU", a.dbAccountedU)

    private fun decodeAmounts(o: JSONObject): AmountAxis = AmountAxis(
        proposedU = o.getDouble("proposedU"),
        rtPublishedU = o.dblOrNull("rtPublishedU"),
        loopConstrainedU = o.dblOrNull("loopConstrainedU"),
        queueConstrainedU = o.dblOrNull("queueConstrainedU"),
        pumpCommandU = o.dblOrNull("pumpCommandU"),
        reportedDeliveredU = o.dblOrNull("reportedDeliveredU"),
        provenDeliveredU = o.dblOrNull("provenDeliveredU"),
        dbAccountedU = o.dblOrNull("dbAccountedU"),
    )

    private fun encodeIdentity(i: PumpTreatmentIdentity): JSONObject = JSONObject()
        .put("proposalId", i.proposalId)
        .putNullable("temporaryId", i.temporaryId)
        .putNullable("pumpId", i.pumpId)
        .put("pumpType", i.pumpType)
        .put("pumpSerialHash", i.pumpSerialHash)
        .put("treatmentTimestamp", i.treatmentTimestamp)

    private fun decodeIdentity(o: JSONObject): PumpTreatmentIdentity = PumpTreatmentIdentity(
        proposalId = o.getString("proposalId"),
        temporaryId = o.lngOrNull("temporaryId"),
        pumpId = o.lngOrNull("pumpId"),
        pumpType = o.getString("pumpType"),
        pumpSerialHash = o.getString("pumpSerialHash"),
        treatmentTimestamp = o.getLong("treatmentTimestamp"),
    )

    private fun encodeError(r: LedgerErrorRecord): JSONObject = JSONObject()
        .putNullable("proposalId", r.proposalId)
        .put("error", r.error.name)
        .put("firstDetail", r.firstDetail)
        .put("lastDetail", r.lastDetail)
        .put("occurrences", r.occurrences)
        .put("active", r.active)
        .put("activeGeneration", r.activeGeneration)
        .putNullable("resolvedBy", r.resolvedBy)
        .putNullable("resolvedReason", r.resolvedReason)
        .putNullable("resolvedGeneration", r.resolvedGeneration)

    private fun decodeError(o: JSONObject): LedgerErrorRecord = LedgerErrorRecord(
        proposalId = o.strOrNull("proposalId"),
        error = LedgerError.valueOf(o.getString("error")),
        firstDetail = o.getString("firstDetail"),
        lastDetail = o.getString("lastDetail"),
        occurrences = o.getInt("occurrences"),
        active = o.getBoolean("active"),
        activeGeneration = o.getLong("activeGeneration"),
        resolvedBy = o.strOrNull("resolvedBy"),
        resolvedReason = o.strOrNull("resolvedReason"),
        resolvedGeneration = o.lngOrNull("resolvedGeneration"),
    )

    private fun encodeOrder(s: SnapshotOrder): JSONObject = JSONObject()
        .put("sourceEpochId", s.sourceEpochId)
        .put("calculatorGeneration", s.calculatorGeneration)
        .put("calculatedAt", s.calculatedAt)

    private fun decodeOrder(o: JSONObject): SnapshotOrder = SnapshotOrder(
        sourceEpochId = o.getString("sourceEpochId"),
        calculatorGeneration = o.getLong("calculatorGeneration"),
        calculatedAt = o.getLong("calculatedAt"),
    )

    // ---- JSON-Helfer ------------------------------------------------------

    private fun JSONObject.putNullable(key: String, v: Any?): JSONObject = put(key, v ?: JSONObject.NULL)

    private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.dblOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.lngOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.objOrNull(key: String): JSONObject? = if (isNull(key)) null else getJSONObject(key)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}

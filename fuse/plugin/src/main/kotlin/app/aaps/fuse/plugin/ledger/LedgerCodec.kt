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
 * Decode - der Adapter behandelt das als "nicht lesbar" und haelt an
 * (RECOVERY_HOLD), statt einen halb geratenen Zustand zu uebernehmen.
 *
 * SEMANTISCHE VALIDIERUNG (Audit 2d273cb, REG-01d/NEU-BS-07): decode nimmt
 * nur Werte an, die eine selbstgeschriebene Datei ueberhaupt tragen kann -
 * Mengen finite/>=0/<=50 U, Episodenbudgets nicht negativ, mealDeliveries
 * begrenzt. Die Datei liegt zwar app-privat, aber ein Bug ODER eine
 * manipulierte Generation darf nie als Buchhaltung durchgehen: Verletzung
 * wirft, der Wurf zaehlt beim Laden als invalid (Hold), nicht als Leerstart.
 */
object LedgerCodec {

    /**
     * SCHEMAVERSION der Datei (R4-02). Version 2 bringt: strikte
     * Lade-Invarianten (Timestamps, failClosed-Befundpflicht, s.
     * [LedgerStateValidator]) und die PIN-PFLICHT je Zeile
     * (proposalPumpEpochs muss jede proposalId abdecken - auch UNPINNED/
     * legacyOpen sind explizite Eintraege). Dateien MIT `v=1` oder OHNE
     * Versionsfeld gelten als Legacy und behalten ihre Toleranzen - heutige
     * Bestandsdateien bleiben dadurch ladbar. Unbekannte ZUKUNFTSVERSIONEN
     * werfen weiterhin (Hold statt raten).
     */
    const val VERSION = 2

    /** Aelteste akzeptierte Version; zugleich der Default fuer Dateien ohne
     *  Versionsfeld. */
    const val LEGACY_VERSION = 1

    /** Ab dieser Version gelten die strikten Invarianten + Pin-Pflicht. */
    const val STRICT_VERSION = 2

    /** Obergrenze jeder Einzelmenge [U]. Weit ueber jedem realen SMB/Budget
     *  (maxSmbU-Hardlimit liegt darunter) - der Zweck ist, absurde Werte als
     *  Korruption zu erkennen, nicht Dosen zu begrenzen. */
    private const val MAX_AMOUNT_U = 50.0

    /** Obergrenze eines einzelnen Mahlzeit-Lieferpostens [U]. */
    private const val MAX_MEAL_DELIVERY_U = 25.0

    /** Obergrenze der mealDeliveries-Liste - eine groessere Datei ist kein
     *  plausibler Eigenzustand (Sammlung ist episodisch), sondern Befund. */
    private const val MAX_MEAL_DELIVERIES = 500

    // ---- Gesamtdatei ------------------------------------------------------

    data class Decoded(
        val state: LedgerState,
        val episodes: EpisodeBudgets,
        val revision: Long,
        val retiredBoundIds: List<RetiredBoundId> = emptyList(),
        val pumpEpochs: Map<String, ProposalPumpEpoch> = emptyMap(),
    )

    fun encode(
        state: LedgerState,
        episodes: EpisodeBudgets,
        revision: Long,
        retiredBoundIds: List<RetiredBoundId> = emptyList(),
        pumpEpochs: Map<String, ProposalPumpEpoch> = emptyMap(),
    ): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("revision", revision)
        .put("state", encodeState(state))
        .put("episodes", encodeEpisodes(episodes))
        // Fix 6 (NEU-BS-02): Identitaeten geprunter gebundener Zeilen bleiben
        // persistent "verbraucht" - sonst wuerde ein prune die Bindungs-
        // Ausschlussmenge leeren und ein fremder Bolus koennte neu binden.
        .put("retiredBoundIds", JSONArray(retiredBoundIds.map { encodeRetired(it) }))
        // Fix 3 (Re-Audit 6.3): die je Vorschlag gepinnte Pumpen-Epoch - sie
        // liegt am Adapter, weil der Kern-ProposalEntry kein Feld traegt.
        // R4-03: JEDE Zeile bekommt einen Eintrag - Zeilen ohne Pin in der
        // Map (Altbestand aus einer v1-Datei) werden als EXPLIZITER
        // legacyOpen-Marker mitgeschrieben, damit die v2-Pin-Pflicht ihr
        // altes Bindungsverhalten nicht rueckwirkend umdeutet.
        .put("proposalPumpEpochs", JSONArray(coveredEpochs(state, pumpEpochs).map { (id, ep) -> encodePumpEpoch(id, ep) }))

    private fun coveredEpochs(state: LedgerState, pumpEpochs: Map<String, ProposalPumpEpoch>): Map<String, ProposalPumpEpoch> {
        val full = LinkedHashMap(pumpEpochs)
        for (id in state.entries.keys) if (id !in full) full[id] = ProposalPumpEpoch.LEGACY_OPEN
        return full
    }

    fun decode(o: JSONObject): Decoded {
        // R4-02: Schemaherkunft explizit - fehlendes Feld heisst Legacy 1.
        val v = if (o.has("v")) o.getInt("v") else LEGACY_VERSION
        require(v in LEGACY_VERSION..VERSION) { "ledger file version $v" }
        val revision = o.getLong("revision")
        require(revision >= 0L) { "negative ledger revision $revision" }
        val state = decodeState(o.getJSONObject("state"), v)
        val pumpEpochs = decodePumpEpochs(o)
        // R4-03: ab v2 muss jede Zeile einen Pin-Eintrag tragen - ein
        // ENTFERNTER Pin wurde vorher still als Legacy gedeutet und band
        // wieder alles.
        if (v >= STRICT_VERSION) LedgerStateValidator.requirePinCoverage(state, pumpEpochs.keys)
        return Decoded(
            state = state,
            episodes = decodeEpisodes(o.getJSONObject("episodes")),
            revision = revision,
            retiredBoundIds = decodeRetiredList(o),
            pumpEpochs = pumpEpochs,
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

    fun decodeState(o: JSONObject, schemaVersion: Int = VERSION): LedgerState {
        val entries = o.getJSONArray("entries").objects().map { decodeEntry(it) }
        // Fix 2 (Re-Audit REG-04): die LISTE pruefen, BEVOR associateBy
        // Duplikate still last-win zusammenfaltet - danach waere der
        // Verstoss unsichtbar.
        LedgerStateValidator.requireUniqueIds(entries)
        val seen = o.getJSONArray("seenEpochs")
        val state = LedgerState(
            entries = entries.associateBy { it.proposalId },
            errors = o.getJSONArray("errors").objects().map { decodeError(it) },
            lastSnapshotOrder = o.objOrNull("lastSnapshotOrder")?.let { decodeOrder(it) },
            lastSnapshotViewHash = o.strOrNull("lastSnapshotViewHash"),
            holdGeneration = o.getLong("holdGeneration"),
            seenEpochs = (0 until seen.length()).map { seen.getString(it) }.toSet(),
            announcedEpochId = o.strOrNull("announcedEpochId"),
        )
        // Fix 2 (Re-Audit 6.2) + R4-02: die GANZE Generation gegen die
        // Zustandsinvarianten UND die Reducer-Zustandsmaschine pruefen -
        // jeder Verstoss wirft und zaehlt beim Laden als invalid
        // (readNewestValid-Fallback bzw. recoveryHold). Die Herkunftsversion
        // steuert die strikten Zusatzpruefungen.
        LedgerStateValidator.validate(state, schemaVersion)
        return state
    }

    // ---- Episodenbudgets --------------------------------------------------

    fun encodeEpisodes(e: EpisodeBudgets): JSONObject = JSONObject()
        .put("primeSpentU", e.primeSpentU)
        .put("primeArmedTs", e.primeArmedTs)
        .put("onsetSpentU", e.onsetSpentU)
        .put("onsetQuietMin", e.onsetQuietMin)
        .put("mealArmedTs", e.mealArmedTs)
        .put("markerTurnTs", e.markerTurnTs)
        .put("markerRiseSeen", e.markerRiseSeen)
        .put("lastAcceptedSourceTs", e.lastAcceptedSourceTs)
        .put("mealDeliveries", JSONArray(e.mealDeliveries.map { (ts, u) -> JSONArray(listOf(ts, u)) }))

    fun decodeEpisodes(o: JSONObject): EpisodeBudgets {
        val e = EpisodeBudgets()
        // Budgets sind VERBRAUCH: negativ hiesse "Huelle groesser als
        // konfiguriert" - genau der Angriffs-/Korruptionspfad aus REG-01d.
        e.primeSpentU = requireAmount("primeSpentU", o.getDouble("primeSpentU"))
        e.primeArmedTs = requireTs("primeArmedTs", o.getLong("primeArmedTs"))
        e.onsetSpentU = requireAmount("onsetSpentU", o.getDouble("onsetSpentU"))
        e.onsetQuietMin = o.getInt("onsetQuietMin").also { require(it >= 0) { "negative onsetQuietMin $it" } }
        e.mealArmedTs = requireTs("mealArmedTs", o.getLong("mealArmedTs"))
        e.markerTurnTs = requireTs("markerTurnTs", o.optLong("markerTurnTs", 0L))
        // optBoolean: Dateien vor diesem Feld lesen sich als "keine
        // Anstiegsphase gesehen" - die konservative Richtung.
        e.markerRiseSeen = o.optBoolean("markerRiseSeen", false)
        // Fix 5 (Re-Audit 6.5): optLong, Default 0 - Altdateien lesen sich
        // als "noch kein Punkt akzeptiert", der naechste akzeptierte Punkt
        // setzt die Epoch neu (konservativ genug: 0 blockiert nie faelschlich).
        e.lastAcceptedSourceTs = requireTs("lastAcceptedSourceTs", o.optLong("lastAcceptedSourceTs", 0L))
        val md = o.getJSONArray("mealDeliveries")
        require(md.length() <= MAX_MEAL_DELIVERIES) { "mealDeliveries size ${md.length()}" }
        for (i in 0 until md.length()) {
            val pair = md.getJSONArray(i)
            val ts = requireTs("mealDeliveries[$i].ts", pair.getLong(0))
            val u = pair.getDouble(1)
            require(u.isFinite() && u > 0.0 && u <= MAX_MEAL_DELIVERY_U) { "mealDeliveries[$i].u out of range: $u" }
            e.mealDeliveries.addLast(ts to u)
        }
        return e
    }

    // ---- Verbrauchte Bindungs-Identitaeten (Fix 6, NEU-BS-02) -------------

    private fun encodeRetired(r: RetiredBoundId): JSONObject = JSONObject()
        .putNullable("temporaryId", r.temporaryId)
        .putNullable("pumpId", r.pumpId)

    private fun decodeRetiredList(o: JSONObject): List<RetiredBoundId> {
        // opt statt get: Dateien vor Fix 6 tragen das Feld nicht - fuer sie
        // ist die leere Menge der ehrliche Zustand, kein Fehler.
        val arr = o.optJSONArray("retiredBoundIds") ?: return emptyList()
        val list = arr.objects().map { r ->
            RetiredBoundId(temporaryId = r.lngOrNull("temporaryId"), pumpId = r.lngOrNull("pumpId"))
                .also { require(it.temporaryId != null || it.pumpId != null) { "retiredBoundIds entry without id" } }
        }
        // Defensiv auf die juengsten Eintraege kappen - der Schreiber haelt
        // dieselbe Grenze, eine groessere Datei ist Fremdinhalt.
        return list.takeLast(FuseLedgerAdapter.MAX_RETIRED_BOUND_IDS)
    }

    // ---- Gepinnte Pumpen-Epochs (Fix 3, Re-Audit 6.3) ---------------------

    private fun encodePumpEpoch(proposalId: String, ep: ProposalPumpEpoch): JSONObject = JSONObject()
        .put("proposalId", proposalId)
        .putNullable("pumpType", ep.pumpTypeName)
        .putNullable("pumpSerialHash", ep.pumpSerialHash)
        // R4-03: die beiden Marker nur schreiben, wenn sie gelten - eine
        // normale Pinnung bleibt in der Altform lesbar.
        .also {
            if (ep.unpinned) it.put("unpinned", true)
            if (ep.legacyOpen) it.put("legacyOpen", true)
        }

    private fun decodePumpEpochs(o: JSONObject): Map<String, ProposalPumpEpoch> {
        // opt statt get: Dateien vor Fix 3 tragen das Feld nicht - fuer sie
        // ist "keine Pinnung" der ehrliche Zustand (Altbestand bindet wie
        // bisher), kein Fehler. Ab Schemaversion 2 erzwingt decode() danach
        // die Abdeckung jeder Zeile (requirePinCoverage).
        val arr = o.optJSONArray("proposalPumpEpochs") ?: return emptyMap()
        val map = LinkedHashMap<String, ProposalPumpEpoch>()
        for (obj in arr.objects()) {
            val id = obj.getString("proposalId")
            require(id.isNotBlank()) { "pump epoch with blank proposalId" }
            val unpinned = obj.optBoolean("unpinned", false)
            val legacyOpen = obj.optBoolean("legacyOpen", false)
            // Der ProposalPumpEpoch-init prueft die Markerkonsistenz
            // (nie beide, Marker nie mit Inhalt) - ein Wurf von dort zaehlt
            // beim Laden wie jeder andere als invalid.
            val ep = ProposalPumpEpoch(obj.strOrNull("pumpType"), obj.strOrNull("pumpSerialHash"), unpinned, legacyOpen)
            // Ein Eintrag ohne jede Aussage wird nie geschrieben - er waere
            // eine Pinnung, die nichts pinnt (Fremdinhalt/Korruption).
            // UNPINNED/legacyOpen SIND Aussagen (R4-03).
            require(ep.pumpTypeName != null || ep.pumpSerialHash != null || ep.unpinned || ep.legacyOpen) {
                "pump epoch without content for $id"
            }
            require(map.put(id, ep) == null) { "duplicate pump epoch for $id" }
        }
        return map
    }

    // ---- Validierungs-Helfer ---------------------------------------------

    private fun requireAmount(name: String, v: Double): Double {
        require(v.isFinite() && v >= 0.0 && v <= MAX_AMOUNT_U) { "$name out of range: $v" }
        return v
    }

    private fun requireAmountOrNull(name: String, v: Double?): Double? = v?.let { requireAmount(name, it) }

    private fun requireTs(name: String, v: Long): Long {
        require(v >= 0L) { "$name negative timestamp: $v" }
        return v
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
            conservativeFloorU = requireAmountOrNull("conservativeFloorU", o.dblOrNull("conservativeFloorU")),
            accountedAmountU = requireAmountOrNull("accountedAmountU", o.dblOrNull("accountedAmountU")),
            amountEpsU = requireAmount("amountEpsU", o.getDouble("amountEpsU")),
            bolusStepU = requireAmount("bolusStepU", o.getDouble("bolusStepU")),
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

    // Jede Stufe der Mengenachse ist eine Insulinmenge - finite/>=0/<=50
    // (REG-01d: eine negative oder absurde Menge in der Datei darf nie
    // Buchhaltung werden, sie ist Korruptions-Befund und wirft).
    private fun decodeAmounts(o: JSONObject): AmountAxis = AmountAxis(
        proposedU = requireAmount("proposedU", o.getDouble("proposedU")),
        rtPublishedU = requireAmountOrNull("rtPublishedU", o.dblOrNull("rtPublishedU")),
        loopConstrainedU = requireAmountOrNull("loopConstrainedU", o.dblOrNull("loopConstrainedU")),
        queueConstrainedU = requireAmountOrNull("queueConstrainedU", o.dblOrNull("queueConstrainedU")),
        pumpCommandU = requireAmountOrNull("pumpCommandU", o.dblOrNull("pumpCommandU")),
        reportedDeliveredU = requireAmountOrNull("reportedDeliveredU", o.dblOrNull("reportedDeliveredU")),
        provenDeliveredU = requireAmountOrNull("provenDeliveredU", o.dblOrNull("provenDeliveredU")),
        dbAccountedU = requireAmountOrNull("dbAccountedU", o.dblOrNull("dbAccountedU")),
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

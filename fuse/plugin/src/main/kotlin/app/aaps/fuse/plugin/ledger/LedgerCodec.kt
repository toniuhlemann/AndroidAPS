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
import app.aaps.fuse.plugin.FuseActivePump

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
    const val VERSION = 3

    /** Aelteste akzeptierte Version; zugleich der Default fuer Dateien ohne
     *  Versionsfeld. */
    const val LEGACY_VERSION = 1

    /** Ab dieser Version gelten die strikten Invarianten + Pin-Pflicht. */
    const val STRICT_VERSION = 2

    /**
     * Ab dieser Version traegt jede Zeile `lastPositiveFactTs`.
     *
     * Warum das eine eigene Version braucht und nicht `optLong(..., null)`:
     * ein FEHLENDES Feld und "es gab nie einen positiven Fakt" sehen beide als
     * `null` aus - und sie bedeuten das Gegenteil voneinander. Beim Fehlen
     * wuerde die Wirkfrist ab `decisionTs` laufen, obwohl vielleicht ein
     * spaeterer Fakt existierte; die Haftung liefe dann ZU FRUEH aus, und das
     * ist die einzige Richtung, die dieses Modul nicht raten darf.
     *
     * Deshalb wird eine aeltere Generation NICHT stillschweigend uebernommen,
     * sondern loest einen Migrations-Hold aus (s. [Decoded.migrationRequired]).
     * Genau dieser Schutz wurde beim ersten Anlauf nur im Kommentar behauptet
     * und nicht gebaut.
     */
    const val RECONCILIATION_VERSION = 3

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
        /**
         * Nicht-null heisst: diese Generation ist LESBAR, darf aber NICHT als
         * Laufzeitzustand uebernommen werden.
         *
         * Sie stammt aus einem aelteren Schema, dem ein Feld fehlt, dessen
         * Fehlen sich nicht von einem gueltigen Wert unterscheiden laesst. Der
         * Aufrufer haelt an, statt zu raten - und der Text sagt, WAS fehlt,
         * damit der Hold auflösbar ist und nicht nur ein Symptom meldet.
         */
        val migrationRequired: String? = null,
    )

    /**
     * MIGRATION nach [RECONCILIATION_VERSION] - konservativ und beweisbar.
     *
     * Zu fuellen ist genau ein Feld: `lastPositiveFactTs`. Die Frage ist, ob
     * es JEMALS einen positiven Fakt gab - nicht, ob gerade einer da ist.
     *
     * DIESE UNTERSCHEIDUNG WAR IM ERSTEN ANLAUF FALSCH (Codex-Re-Review
     * 10.08., P0). Sie las `accountedAmountU`, also den AKTUELLEN Stand. Der
     * Reducer setzt den aber auf 0 zurueck, sobald ein zuvor nachgewiesener
     * Fakt aus der Vollsicht verschwindet (R91-F1). Eine solche Zeile HATTE
     * einen Fakt und haette bei der Migration trotzdem `null` bekommen - ihre
     * Wirkfrist waere ab `decisionTs` gelaufen und damit moeglicherweise zu
     * frueh abgelaufen. Genau die Richtung, gegen die die ganze B1-Arbeit
     * steht.
     *
     * Massgeblich sind deshalb die HISTORISCHEN Spuren, die der Reducer
     * ausdruecklich stehen laesst:
     *
     *  - `firstAccountedSnapshotHash` - der ERSTE Nachweis, reine Provenienz;
     *    er ueberlebt das Zuruecknehmen der Buchung.
     *  - `accountedAmountU` - der aktuelle Stand.
     *  - `amounts.dbAccountedU` - die einmal im Datensatz gesehene Menge.
     *
     * Eine davon genuegt: der Fakt existierte, unbekannt ist nur WANN. Der
     * spaetestmoegliche Zeitpunkt ist jetzt; ihn zu waehlen verlaengert die
     * Wirkfrist maximal und macht die Haftung nie kleiner.
     *
     * Fehlen alle drei, gab es nie einen positiven Fakt. `null` ist dann die
     * WAHRE Aussage und die Frist laeuft ab `decisionTs` - wie ohne Migration.
     *
     * IDEMPOTENZ, genau: auf einer bereits MIGRIERTEN Generation ist die
     * Funktion die Identitaet (das Feld ist gesetzt und wird nicht angefasst).
     * Zweimal auf DERSELBEN ALTEN Generation mit verschiedenem `nowTs`
     * ausgefuehrt liefert sie dagegen verschiedene Zeitstempel - das ist kein
     * Widerspruch, sondern die Folge davon, dass "jetzt" der einzige
     * konservative Ersatz fuer eine unbekannte Zeit ist. In der Praxis kann
     * das nicht auftreten: nach dem ersten gelungenen Lauf liegt die
     * migrierte Generation auf Platte.
     */
    /**
     * B3: darf der Altbestand seine PATCH-Epoche nachtragen? NEIN.
     *
     * Fuer eine Zeile aus v1/v2 ist die damalige Patch-Epoche schlicht nicht
     * mehr feststellbar. Die AKTUELL gelesene rueckwirkend anzuheften waere
     * die Behauptung, seit der Entscheidung sei kein Patch gewechselt worden -
     * und genau das ist unbekannt. Eine so migrierte Zeile duerfte einen
     * Bolus des NEUEN Patches binden, also exakt das, wogegen B3 gebaut ist.
     *
     * Deshalb gibt es hier keine Nachfuellung, sondern eine ENTSCHEIDUNG:
     *
     *  - Zeile einer NICHT-Patch-Pumpe (VirtualPump): die Kategorie gilt fuer
     *    sie nicht. Sie bindet unveraendert weiter - nichts zu migrieren.
     *  - Zeile einer PATCHPUMPE ohne persistierte Epoche: nicht migrierbar.
     *    Der Aufrufer haelt an (Hold), statt zu raten.
     *
     * Rueckgabe: die proposalIds, die NICHT migrierbar sind. Leer heisst
     * migrierbar.
     */
    fun unmigratablePatchRows(
        state: LedgerState,
        pumpEpochs: Map<String, ProposalPumpEpoch>,
        activePump: FuseActivePump,
    ): List<String> {
        // EINMAL ausgewertet, nicht je Zeile: der Dreiwert ist eine Eigenschaft
        // des Zyklus, nicht der Zeile.
        //   true  -> echte Patchpumpe laeuft
        //   false -> keine Patchpumpe ODER Emulation
        //   null  -> unbekannt, und unbekannt haelt an
        val echtePatchpumpe = activePump.realPatchPump
        // "Nicht nachweislich harmlos" - `null` faellt bewusst auf dieselbe
        // Seite wie `true`. Eine VirtualPump anzunehmen waere geraten, und zwar
        // in die gefaehrliche Richtung.
        val altbestandGefaehrlich = echtePatchpumpe != false
        return state.entries.keys.filter { id ->
            val ep = pumpEpochs[id]
            if (ep == null) {
                // GAR KEIN PIN - eine v1-Zeile. Sie wuerde spaeter ueber
                // `coveredEpochs` zu LEGACY_OPEN und danach OHNE Typ-, Serial-
                // und Patchpruefung binden. Auf einer echten Patchpumpe ist das
                // genau das Loch, gegen das B3 gebaut ist.
                //
                // Ob sie gefahrlos weiterbinden darf, haengt daran, WELCHE
                // Pumpe heute laeuft:
                //   - Patchpumpe  -> nein, Hold.
                //   - Nicht-Patch -> ja, unveraendertes Altbestandsverhalten.
                //   - Emulation   -> ja. Sie fuehrt zwar einen Patchpumpen-
                //     NAMEN, hat aber keine Patches; das entscheidet
                //     [FuseActivePump.realPatchPump], nicht der Name.
                //   - UNBEKANNT   -> Hold.
                //
                // Eine bereits GESCHLOSSENE Zeile bindet nichts mehr - sie ist
                // kein Grund anzuhalten.
                val offen = state.entries[id]?.closed == false
                return@filter offen && altbestandGefaehrlich
            }
            // Marker-Pins tragen keinen Typ - sie binden ohnehin nie (UNPINNED)
            // oder sind ausdruecklicher Altbestand (LEGACY_OPEN).
            if (ep.unpinned) return@filter false
            if (ep.legacyOpen) {
                // Ausdruecklicher Altbestand bindet "wie bisher" - dieselbe
                // Frage wie beim fehlenden Pin, also dieselbe Antwort.
                val offen = state.entries[id]?.closed == false
                return@filter offen && altbestandGefaehrlich
            }
            // Eine Patchpumpe OHNE gesetzte Epoche ist der Fall - und die Frage
            // "ist das eine Patchpumpe?" beantwortet das GEPINNTE
            // `patchEpochApplicable`, NICHT der Typname.
            //
            // Der Unterschied ist genau die Emulation: ein `MEDTRUM_NANO`-Pin
            // der VirtualPump traegt keine Epoche und wuerde ueber den Typnamen
            // hier ewig haengen bleiben. Ueber die Anwendbarkeit faellt er
            // heraus, denn der Konstruktor garantiert BEIDE Richtungen -
            // Emulation impliziert "nicht anwendbar", echte Patchpumpe
            // impliziert "anwendbar".
            //
            // Und auch hier entscheidet am Ende die AKTIVE Pumpe mit. Ein
            // Altbestands-Pin traegt einen Patchpumpen-NAMEN, aber keine
            // Aussage darueber, ob er von einem physischen Geraet stammte -
            // vor B3 gab es das Feld nicht. Laeuft heute die Emulation, kam er
            // von ihr, und die Zeile darf migrieren; laeuft eine echte
            // Patchpumpe oder ist die Lage unklar, bleibt es beim Hold.
            //
            // Der Zaun dahinter haelt trotzdem: eine so migrierte Zeile traegt
            // `patchEpochTs = null`, und eine UNBEKANNTE gepinnte Epoche
            // bindet nie (s. `FusePatchEpoch.sameEpoch`). Die Migration
            // entscheidet ueber den Hold, nicht ueber die Bindung.
            ep.patchEpochApplicable && ep.patchEpochTs == null && altbestandGefaehrlich
        }
    }

    fun migrateToCurrent(state: LedgerState, nowTs: Long): LedgerState = state.copy(
        entries = state.entries.mapValues { (_, e) ->
            val gabEsJeEinenFakt = e.firstAccountedSnapshotHash != null ||
                (e.accountedAmountU ?: 0.0) > e.amountEpsU ||
                (e.amounts.dbAccountedU ?: 0.0) > e.amountEpsU
            if (e.lastPositiveFactTs != null) e
            else if (gabEsJeEinenFakt) e.copy(lastPositiveFactTs = nowTs)
            else e
        }
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
        val pumpEpochs = decodePumpEpochs(o, v)
        // R4-03: ab v2 muss jede Zeile einen Pin-Eintrag tragen - ein
        // ENTFERNTER Pin wurde vorher still als Legacy gedeutet und band
        // wieder alles.
        if (v >= STRICT_VERSION) LedgerStateValidator.requirePinCoverage(state, pumpEpochs.keys)
        // MIGRATIONS-HOLD statt stiller Uebernahme (P0-B).
        //
        // Unter v3 fehlt `lastPositiveFactTs`. Beim Lesen ist das `null` - und
        // `null` heisst dort "es gab nie einen positiven Fakt". Das ist eine
        // ANDERE Aussage als "wir wissen es nicht", und sie ist die
        // gefaehrliche Richtung: die Wirkfrist liefe ab `decisionTs` statt ab
        // einer moeglicherweise spaeteren Lieferzeit, die Haftung also ZU
        // FRUEH aus.
        //
        // Eine Generation OHNE Zeilen hat nichts zu verlieren und migriert
        // still - genau so kann vor einem Realpump-Lauf eine frische, belegte
        // Generation starten, ohne dass jemand eine Datei loeschen muss.
        val migration =
            if (v < RECONCILIATION_VERSION && state.entries.isNotEmpty())
                "SCHEMA_v${v}_WITHOUT_lastPositiveFactTs (${state.entries.size} offene Zeilen)"
            else null
        return Decoded(
            state = state,
            episodes = decodeEpisodes(o.getJSONObject("episodes"), v),
            revision = revision,
            retiredBoundIds = decodeRetiredList(o, v),
            pumpEpochs = pumpEpochs,
            migrationRequired = migration,
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
        val entries = o.getJSONArray("entries").objects().map { decodeEntry(it, schemaVersion) }
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
        .put("primeWindowStartTs", e.primeWindowStartTs)
        .put("primeArmedTs", e.primeArmedTs)
        .put("onsetSpentU", e.onsetSpentU)
        .put("onsetQuietMin", e.onsetQuietMin)
        .put("mealArmedTs", e.mealArmedTs)
        .put("markerTurnTs", e.markerTurnTs)
        .put("markerRiseSeen", e.markerRiseSeen)
        .put("lastAcceptedSourceTs", e.lastAcceptedSourceTs)
        .put("mealDeliveries", JSONArray(e.mealDeliveries.map { (ts, u) -> JSONArray(listOf(ts, u)) }))

    fun decodeEpisodes(o: JSONObject, schemaVersion: Int = VERSION): EpisodeBudgets {
        if (schemaVersion >= RECONCILIATION_VERSION)
            require(o.has("lastAcceptedSourceTs")) { "v$schemaVersion episodes without lastAcceptedSourceTs" }
        val e = EpisodeBudgets()
        // Budgets sind VERBRAUCH: negativ hiesse "Huelle groesser als
        // konfiguriert" - genau der Angriffs-/Korruptionspfad aus REG-01d.
        e.primeSpentU = requireAmount("primeSpentU", o.getDouble("primeSpentU"))
        // optional: aeltere Staende kennen das Feld nicht, 0 = "nie gesperrt".
        e.primeWindowStartTs = o.optLong("primeWindowStartTs", 0L).coerceAtLeast(0L)
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

    private fun decodeRetiredList(o: JSONObject, schemaVersion: Int = VERSION): List<RetiredBoundId> {
        // Ab v3 PRAESENZPFLICHTIG: der Encoder schreibt die Liste immer, notfalls
        // leer. Fehlt sie in einer v3-Datei, ist das keine "leere Menge", sondern
        // eine beschaedigte Generation - und die leere Menge waere hier die
        // gefaehrliche Deutung: eine verbrauchte Bindungsidentitaet duerfte
        // wieder binden.
        if (schemaVersion >= RECONCILIATION_VERSION)
            require(o.has("retiredBoundIds")) { "v$schemaVersion file without retiredBoundIds" }
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
            // Emulationsflag an JEDER normalen Pinnung, auch wenn es `false`
            // ist. Anders als `patchEpochApplicable` ist das KEIN Feld, das
            // man weglassen darf, wenn es nicht zutrifft: gerade die Aussage
            // "das war eine ECHTE Pumpe" muss geschrieben dastehen, sonst ist
            // sie beim Lesen nicht von einem verlorenen Feld zu unterscheiden.
            // Marker tragen es nicht - sie tragen ueberhaupt keinen Inhalt.
            if (!ep.unpinned && !ep.legacyOpen) it.put("virtualPump", ep.virtualPump)
            // B3: nur bei ANWENDBARKEIT schreiben. Bei einer Nicht-Patch-Pumpe
            // gibt es die Kategorie nicht - ein Feld dafuer waere eine Aussage
            // ueber etwas, das es nicht gibt.
            if (ep.patchEpochApplicable) {
                it.put("patchEpochApplicable", true)
                it.putNullable("patchEpochTs", ep.patchEpochTs)
            }
        }

    private fun decodePumpEpochs(o: JSONObject, schemaVersion: Int = VERSION): Map<String, ProposalPumpEpoch> {
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
            // B3: `patchEpochApplicable` sagt, ob die Kategorie ueberhaupt
            // gilt. Ein FEHLENDES Feld heisst je nach Schemaversion etwas
            // anderes, und diese drei Faelle muessen auseinandergehalten
            // werden:
            //
            //  - ab v3, Nicht-Patch-Pin: es gibt die Kategorie nicht. Richtig
            //    so, nichts zu tun.
            //  - ab v3, Patchpumpen-Pin: KORRUPTION. v3 ist nie ohne die
            //    Felder ausgeliefert worden (nichts installiert), der
            //    Schreiber setzt sie dort immer.
            //  - VOR v3: UNBEKANNT. Das Feld gab es damals nicht. Fuer einen
            //    Patchpumpen-NAMEN wird deshalb weiter unten die strengere
            //    Lesart genommen (anwendbar, Epoche unbekannt); ueber die
            //    Migration entscheidet dann die AKTIVE Pumpe.
            //
            // P0 (Codex 10.08.) betrifft den zweiten Fall: eine BESCHAEDIGTE
            // v3-Pinnung einer Patchpumpe wuerde sonst als "nicht anwendbar"
            // gelesen, und `matchesPinnedEpoch` uebersaehe die Patchpruefung
            // VOLLSTAENDIG. Dieselbe Falle wie ueberall in diesem Projekt: ein
            // fehlendes Feld sieht aus wie eine gueltige Aussage, und zwar wie
            // die harmloseste.
            val pinTyp = obj.strOrNull("pumpType")
            // Das Emulationsflag ZUERST, denn es entscheidet, ob die
            // Patch-Pflicht darunter ueberhaupt gilt.
            //
            // Ab v3 ist es an jeder normalen Pinnung PFLICHT - nicht nur an
            // Medtrum-Pins. Ein fehlendes Feld darf nicht still als "war eine
            // echte Pumpe" gelten, und die Praesenzpflicht unbedingt zu
            // stellen erspart eine zweite Bedingung, die man beim naechsten
            // Pumpentyp wieder nachziehen muesste. v3 ist nie ohne dieses Feld
            // ausgeliefert worden (nichts installiert), also kann die Regel
            // hart sein.
            val normalerPin = !unpinned && !legacyOpen
            if (schemaVersion >= RECONCILIATION_VERSION && normalerPin) {
                require(obj.has("virtualPump")) { "v$schemaVersion pin $id without virtualPump" }
            }
            val emuliert = obj.optBoolean("virtualPump", false)
            // Gegen die EMULIERTE Pumpe gibt es keine Patch-Epoche - dort ist
            // ihr Fehlen der richtige Zustand und keine Korruption. Nur fuer
            // eine als ECHT gepinnte Patchpumpe bleibt die Pflicht bestehen.
            if (schemaVersion >= RECONCILIATION_VERSION && normalerPin && !emuliert &&
                ProposalPumpEpoch.appliesTo(pinTyp)
            ) {
                require(obj.has("patchEpochApplicable")) { "v$schemaVersion patch pin $id without patchEpochApplicable" }
                require(obj.has("patchEpochTs")) { "v$schemaVersion patch pin $id without patchEpochTs" }
                // ...und der WERT muss stimmen. Die blosse Praesenz genuegt
                // nicht: `"patchEpochApplicable": false` an einem Medtrum-Pin
                // passiert jede Anwesenheitspruefung, wird danach als "nicht
                // anwendbar" dekodiert und umgeht die Patchpruefung erneut.
                // Der Schreiber setzt an einem Patchpumpen-Pin IMMER true -
                // alles andere ist Korruption.
                require(obj.getBoolean("patchEpochApplicable")) {
                    "v$schemaVersion patch pin $id declares patchEpochApplicable=false"
                }
            }
            // ALTBESTAND: eine Pinnung aus einer Generation VOR B3 kennt das
            // Feld gar nicht. Fuer einen Patchpumpen-NAMEN heisst sein Fehlen
            // dort nicht "nicht anwendbar", sondern UNBEKANNT - und unbekannt
            // wird als die STRENGERE Lesart genommen: anwendbar, Epoche
            // unbekannt. Ob die Zeile trotzdem migrieren darf, entscheidet
            // danach die AKTIVE Pumpe (s. [unmigratablePatchRows]).
            //
            // Ohne diese Zeile waere so ein Pin unlesbar: der Konstruktor
            // verlangt von einer nicht-emulierten Patchpumpe die
            // Anwendbarkeit, die Datei traegt sie nicht, `decode` wuerde
            // werfen - und eine bestehende v2-Datei waere dauerhaft weder
            // lesbar noch migrierbar.
            val anwendbar = obj.optBoolean("patchEpochApplicable", false) ||
                (schemaVersion < RECONCILIATION_VERSION && normalerPin && ProposalPumpEpoch.appliesTo(pinTyp))
            // Die Invarianten stehen im Konstruktor, nicht hier: eine Datei
            // mit `virtualPump: true` UND `patchEpochApplicable: true` ist
            // widerspruechlich, und ein Wurf von dort zaehlt beim Laden wie
            // jede andere Korruption.
            val ep = ProposalPumpEpoch(
                obj.strOrNull("pumpType"), obj.strOrNull("pumpSerialHash"), unpinned, legacyOpen,
                patchEpochTs = if (anwendbar) obj.lngOrNull("patchEpochTs") else null,
                patchEpochApplicable = anwendbar,
                virtualPump = emuliert,
            )
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
        .putNullable("lastPositiveFactTs", e.lastPositiveFactTs)
        .put("terminalSeen", e.terminalSeen)
        .put("failClosed", e.failClosed)
        .put("corrections", e.corrections)
        .put("decisionTs", e.decisionTs)
        .put("expiredBeyondAction", e.expiredBeyondAction)
        .put("latestBolusTimestampAtDecision", e.latestBolusTimestampAtDecision)
        .put("errors", JSONArray(e.errors.map { it.name }))

    /**
     * Ab [RECONCILIATION_VERSION] sind die Pflichtfelder PRAESENZPFLICHTIG.
     *
     * Der Encoder schreibt sie immer - auch als `JSONObject.NULL`. Fehlt der
     * SCHLUESSEL in einer Datei, die sich als v3 ausgibt, ist das kein
     * Altbestand, sondern eine beschaedigte oder fremde Generation. Sie still
     * als "kein Wert" zu lesen waere derselbe Fehler wie beim Altbestand, nur
     * ohne die Entschuldigung des Alters.
     */
    private fun decodeEntry(o: JSONObject, schemaVersion: Int = VERSION): ProposalEntry {
        if (schemaVersion >= RECONCILIATION_VERSION)
            require(o.has("lastPositiveFactTs")) {
                "v$schemaVersion entry ${o.optString("proposalId")} without lastPositiveFactTs"
            }
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
            // Fehlt das Feld, ist die Antwort NICHT "es gab nie einen Fakt" -
            // sie ist "wir wissen es nicht". Beide sehen als `null` gleich aus,
            // deshalb faengt der Versionsvertrag den Fall: eine Datei unter
            // RECONCILIATION_VERSION geht in den Migrations-Hold und wird gar
            // nicht erst als Laufzeitzustand uebernommen (s. decode).
            lastPositiveFactTs = o.lngOrNull("lastPositiveFactTs"),
            terminalSeen = o.getBoolean("terminalSeen"),
            failClosed = o.getBoolean("failClosed"),
            corrections = o.getInt("corrections"),
            // optional: aeltere Staende kennen das Feld nicht.
            expiredBeyondAction = o.optBoolean("expiredBeyondAction", false),
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

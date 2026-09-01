package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.InterventionStamp
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
import app.aaps.fuse.plugin.FuseIntKey

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
    const val VERSION = 4

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

    /**
     * Ab dieser Version traegt jede Datei den PERSISTENTEN EVIDENZBESTAND
     * (`episodes.evidenceState`).
     *
     * WARUM EINE EIGENE VERSION UND KEIN `optJSONObject` (Toni 12.08.):
     * v3 ist bereits IM FELD. Den Bestand nachtraeglich zum Pflichtbestandteil
     * von v3 zu erklaeren hiesse, jede bestehende Datei zur korrupten zu
     * machen; ihn dagegen als optional zu lesen hiesse, ein FEHLENDES Feld
     * nicht mehr von einem echten Leerbestand unterscheiden zu koennen - und
     * genau diese Unterscheidung entscheidet, ob nach einem Neustart Kredit
     * fliessen darf.
     *
     * Also: v3 bleibt lesbar und loest eine MIGRATION aus, die den Bestand
     * ausdruecklich auf null setzt (Anker, `evidenceCommittedU` und Haftung
     * bleiben erhalten). Eine v4-Datei OHNE das Feld ist dagegen Korruption -
     * keine zweite Migration.
     */
    const val EVIDENCE_VERSION = 4

    /** Obergrenze jeder Einzelmenge [U]. Weit ueber jedem realen SMB/Budget
     *  (maxSmbU-Hardlimit liegt darunter) - der Zweck ist, absurde Werte als
     *  Korruption zu erkennen, nicht Dosen zu begrenzen. */
    private const val MAX_AMOUNT_U = 50.0

    /** Obergrenze eines einzelnen Mahlzeit-Lieferpostens [U]. */
    private const val MAX_MEAL_DELIVERY_U = 25.0

    /** Obergrenze der mealDeliveries-Liste - eine groessere Datei ist kein
     *  plausibler Eigenzustand (Sammlung ist episodisch), sondern Befund. */
    private const val MAX_MEAL_DELIVERIES = 500

    /**
     * Laengengrenze der Buchungskennung in `mealDeliveries` (Toni 19.08.).
     *
     * Die Kennungen dieses Codes sind Zyklus-IDs der Form "s#<zahl>" - weit
     * darunter. Die Grenze faengt Fremdinhalt ab, bevor er in eine Datei
     * wandert, die pro Episode bis zu 500 Eintraege tragen darf.
     */
    private const val MAX_PROPOSAL_ID_LEN = 64

    // ---- Gesamtdatei ------------------------------------------------------

    data class Decoded(
        val state: LedgerState,
        val episodes: EpisodeBudgets,
        val revision: Long,
        val retiredBoundIds: List<RetiredBoundId> = emptyList(),
        val pumpEpochs: Map<String, ProposalPumpEpoch> = emptyMap(),
        /**
         * DER EINGRIFFSSTEMPEL DIESER GENERATION - `null` heisst "diese Datei
         * kennt ihn nicht".
         *
         * BEWUSST OHNE VERSIONSSPRUNG eingefuehrt (Toni 18.08.): ein Bump auf
         * v5 wuerde `require(v in LEGACY_VERSION..VERSION)` in einer aelteren
         * APK werfen - nach zwei Zyklen sind Ziel UND Sicherung v5, und ein
         * Rollback endete im dauerhaften Hold auf einem produktiven Loop. Als
         * optionales Feld ignoriert die alte APK ihn einfach.
         *
         * Der Preis: schreibt die alte APK zwischendurch eine Generation, ist
         * das Feld danach weg. Genau dann eroeffnet die neue APK beim
         * naechsten Start eine NEUE Epoche - die sichere Aussage, weil
         * niemand mehr weiss, was in der Zwischenzeit dosiert wurde.
         */
        val interventionStamp: InterventionStamp? = null,
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
        // OHNE DEFAULT, und ABSICHTLICH vor den beiden Defaultparametern:
        // `encode` hat bereits zwei: ein dritter Default liesse alle drei
        // Aufrufstellen unveraendert durchkompilieren und schriebe still
        // einen erfundenen Stempel. So bricht der Compiler an jeder Stelle,
        // die sich nicht erklaert hat.
        interventionStamp: InterventionStamp,
        retiredBoundIds: List<RetiredBoundId> = emptyList(),
        pumpEpochs: Map<String, ProposalPumpEpoch> = emptyMap(),
    ): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("revision", revision)
        .put("interventionEpoch", interventionStamp.epochId)
        .put("interventionSequence", interventionStamp.sequence)
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

    /**
     * Der Stempel aus der Datei - `null`, sobald irgendetwas daran fehlt.
     *
     * KEIN DEFAULT AUF SEQUENZ 0. Eine erfundene Null saehe aus wie ein
     * echter Anfang und wuerde spaeter gegen Eintraege verglichen, die einen
     * echten Stand tragen. `null` dagegen zwingt den Aufrufer, eine frische
     * Epoche zu eroeffnen.
     */
    private fun decodeStamp(o: JSONObject): InterventionStamp? {
        if (!o.has("interventionEpoch") || !o.has("interventionSequence")) return null
        // optString liefert auf Android bei JSON-null den String "null"
        // (Live-Befund, s. android-json-optstring-falle) - deshalb getString
        // im runCatching und eine ausdrueckliche Gueltigkeitsprobe.
        val stamp = runCatching {
            InterventionStamp(o.getString("interventionEpoch"), o.getLong("interventionSequence"))
        }.getOrNull() ?: return null
        return stamp.takeIf { it.valid }
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
        val migration = when {
            v < RECONCILIATION_VERSION && state.entries.isNotEmpty() ->
                "SCHEMA_v${v}_WITHOUT_lastPositiveFactTs (${state.entries.size} offene Zeilen)"

            // Der Evidenzbestand fehlt. ANDERS als oben haengt das NICHT an
            // offenen Zeilen: der Bestand gehoert zur Episode, nicht zur
            // Haftung - eine Datei ohne ihn kann auch bei leerem Ledger nicht
            // sagen, ob er 0 ist oder unbekannt.
            v < EVIDENCE_VERSION -> "SCHEMA_v${v}_WITHOUT_evidenceState"

            else                 -> null
        }
        return Decoded(
            state = state,
            episodes = decodeEpisodes(o.getJSONObject("episodes"), v),
            revision = revision,
            retiredBoundIds = decodeRetiredList(o, v),
            pumpEpochs = pumpEpochs,
            migrationRequired = migration,
            // OPTIONAL LESEN, aber ohne jede Nachsicht: nur ein VOLLSTAENDIGER
            // Stempel zaehlt. Eine halbe Angabe (Epoche ohne Sequenz oder
            // umgekehrt) ist keine Herkunft, sondern eine Vermutung ueber eine -
            // und die traegt hier keine Auswertung.
            interventionStamp = decodeStamp(o),
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
            errors = o.getJSONArray("errors").objects().map { decodeError(it, schemaVersion) },
            lastSnapshotOrder = o.objOrNull("lastSnapshotOrder", schemaVersion)?.let { decodeOrder(it) },
            lastSnapshotViewHash = o.strOrNull("lastSnapshotViewHash", schemaVersion),
            holdGeneration = o.getLong("holdGeneration"),
            seenEpochs = (0 until seen.length()).map { seen.getString(it) }.toSet(),
            announcedEpochId = o.strOrNull("announcedEpochId", schemaVersion),
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
        .put("evidenceCommittedU", e.evidenceCommittedU)
        .put("evidenceCommitmentRevision", e.evidenceCommitmentRevision)
        .put("evidenceEpisodeId", e.evidenceEpisodeId)
        .put("lastConsumedMarkerTs", e.lastConsumedMarkerTs)
        .put("evidenceRevoked", e.evidenceRevoked)
        .put("evidenceState", encodeEvidence(e.evidenceState))
        .put("primeArmedTs", e.primeArmedTs)
        .put("onsetSpentU", e.onsetSpentU)
        .put("onsetQuietMin", e.onsetQuietMin)
        .put("mealArmedTs", e.mealArmedTs)
        .put("markerTurnTs", e.markerTurnTs)
        .put("markerRiseSeen", e.markerRiseSeen)
        .put("lastAcceptedSourceTs", e.lastAcceptedSourceTs)
        // Die gepinnte Frist des Rebound-Sonderrechts. Additiv: eine
        // Altdatei ohne den Schluessel liest 0 = kein Privileg, also
        // die konservative Richtung.
        .put("markerReboundOverrideDeadlineTs", e.markerReboundOverrideDeadlineTs)
        .put("markerReboundOverridePinnedFor", e.markerReboundOverridePinnedFor)
        // Eigenes Unterobjekt: ist es vorhanden, sind beide Felder Pflicht.
        // Es bleibt additiv ohne Schema-Bump, damit ein Rollback die v4-Datei
        // weiter lesen kann; eine Altdatei ohne Objekt bedeutet "vor dem
        // ersten Einsatz nicht gelatcht". Nach einem Neustart wird nur der
        // persistierte Riegel, nie eine halbe Erholungsserie, wiedergefunden.
        .put(
            "descentRecoveryLatch",
            JSONObject()
                .put("active", e.descentRecoveryLatch.active)
                .put("latchedAtTs", e.descentRecoveryLatch.latchedAtTs)
                .put("sawMeasuredLow", e.descentRecoveryLatch.sawMeasuredLow),
        )
        // DER NACHWEIS DER EIGENEN TEIL-TBR - restartfest, s.
        // PartialTbrOwnership. Altdatei ohne Feld heisst "kein Nachweis",
        // und das ist die Richtung, die nichts Fremdes anfasst.
        .put(
            "ownPartialTbr",
            e.ownPartialTbr?.let {
                JSONObject()
                    .put("rateUPerH", it.rateUPerH)
                    .put("setAtTs", it.setAtTs)
                    .put("durationMin", it.durationMin)
            } ?: JSONObject.NULL,
        )
        .put(
            "zeroLatch",
            JSONObject()
                .put("active", e.zeroLatch.active)
                .put("latchedAtTs", e.zeroLatch.latchedAtTs)
                .put("sawMeasuredLow", e.zeroLatch.sawMeasuredLow),
        )
        // Die beim Markerdruck eingefrorene Basalluecken-Lage (Schritt B)
        // - additiv ohne Schema-Bump, Altdatei ohne Objekt heisst
        // "nie gelatcht"; die nullbaren Felder bleiben null-treu (nicht 0).
        .apply {
            e.basalGap?.let { g ->
                put(
                    "basalGap",
                    JSONObject()
                        .put("pinnedFor", g.pinnedFor)
                        .put("preMarkerBasalIobU", g.preMarkerBasalIobU)
                        .put("zeroTbrActive", g.zeroTbrActive)
                        .put("zeroTbrAgeMin", g.zeroTbrAgeMin ?: JSONObject.NULL)
                        .put("scheduledBasalUph", g.scheduledBasalUph)
                        .put("omittedBasalU", g.omittedBasalU ?: JSONObject.NULL),
                )
            }
        }
        // Die LAUFENDE Nullphasen-Bilanz muss den Neustart ueberleben -
        // sonst begaenne sie mitten in einer Phase bei 0 und wiese die
        // Haltezeit systematisch zu kurz aus. Die zuletzt abgeschlossene
        // faehrt mit, weil der Vergleich erst nach dem Ende moeglich ist.
        .apply {
            e.zeroTally?.let { put("zeroTally", tallyJson(it)) }
            e.lastZeroTally?.let { put("lastZeroTally", tallyJson(it)) }
        }
        // DER RUHE-BEOBACHTUNGSZUSTAND des Phase-A-Sofortbatches (Toni
        // 25.08. spaet). Persistiert wird AUSSCHLIESSLICH die Beobachtung -
        // niemals ein bereits gefaelltes CALM_RECOVERED- oder
        // FULL_BATCH_ELIGIBLE-Urteil. Nach einem Neustart wird nur dieser
        // Track geladen; aktuelle Gefahren und Kandidat werden vollstaendig
        // neu berechnet.
        //
        // Alle sechs Identitaeten muessen mit, sonst waere der Zaehler nach
        // dem Laden nicht pruefbar: Marker, Signalanschluss, Zeitkontinuitaet,
        // Modus, Streak - und der Fingerprint der Regel-/Konfigurations-
        // generation, damit zwei Beobachtungen unter alten und eine dritte
        // unter gelockerten Schwellen nicht gemeinsam freigeben.
        //
        // Additiv: eine Altdatei ohne das Objekt ergibt den leeren Track,
        // also einen sauberen Neustart bei 0 - die konservative Richtung.
        .put(
            "upfrontRecovery",
            JSONObject()
                .put("markerIdentity", e.upfrontRecovery.markerIdentity)
                .put("streak", e.upfrontRecovery.streak)
                .put("lastAcceptedSourceTs", e.upfrontRecovery.lastAcceptedSourceTs)
                .put("lastEvaluationTs", e.upfrontRecovery.lastEvaluationTs)
                .put("mode", e.upfrontRecovery.mode.name)
                .put("fingerprint", e.upfrontRecovery.fingerprint),
        )
        // v30-Korrekturpfad-Riegel (Review-P0.1): NUR die Identitaet -
        // Fall-Minimum/Zuendung bzw. Anker/Quelle ueberleben den Neustart,
        // die Bestaetigungszaehler sind prozesslokal (restored nullt).
        // Additiv und NUR BEI BESTAND geschrieben: eine Altdatei oder eine
        // Lage ohne Fall/Anker liest sich als leerer Track; JSON kennt
        // zudem kein NaN, ein leeres minUkf ist gar nicht abbildbar.
        .apply {
            val rev = e.correctionReversal
            if (rev.minUkfTs > 0L && !rev.minUkf.isNaN()) put(
                "correctionReversal",
                JSONObject()
                    .put("minUkf", rev.minUkf)
                    .put("minUkfTs", rev.minUkfTs)
                    .put("reboundSeenTs", rev.reboundSeenTs),
            )
            val re = e.correctionRearm
            if (re.ankerTs > 0L) put(
                "correctionRearm",
                JSONObject()
                    .put("ankerTs", re.ankerTs)
                    .put("quelle", re.quelle.name),
            )
        }
        // Punkt 6: der Marker-Prime-Aufschub - Budget UND Frist muessen den
        // Neustart identisch ueberleben (Vertrag/Replay-Fall 6). Additiv wie
        // der Riegel: eine Altdatei ohne Objekt heisst "kein Aufschub".
        .put(
            "deferredPrime",
            JSONObject()
                .put("openU", e.deferredPrime.openU)
                .put("pinnedForMarkerTs", e.deferredPrime.pinnedForMarkerTs)
                .put("deadlineTs", e.deferredPrime.deadlineTs)
                .put("horizonMin", e.deferredPrime.horizonMin)
                .put("postFoundationDeliveredU", e.postFoundationDeliveredU),
        )
        // Liveness-Kanal: nur die restartfeste Sperre. Der aktive Zustand
        // und der Bewaffnungs-Streak sind bewusst prozesslokal - ein
        // Neustart bewaffnet neu, oeffnet aber nie eine laufende Sperre.
        .put("livenessReArmUntilTs", e.livenessReArmUntilTs)
        .put("forecastShadowEpochTs", e.forecastShadowEpochTs)
        .put("forecastShadowLastState", e.forecastShadowLastState)
        .put("theilSenWindowLastMin", e.theilSenWindowLastMin)
        .put("markerPowerPinnedFor", e.markerPowerPinnedFor)
        .put("markerPowerDeadlineTs", e.markerPowerDeadlineTs)
        // DREI Elemente statt zwei: [ts, menge, proposalId] (Toni 19.08.).
        // Die Kennung MUSS mit - ohne sie findet ein Nicht-Sende-Beweis nach
        // einem Neustart den Eintrag nicht mehr und laesst eine nie geflossene
        // Menge stehen. Der dritte Platz ist additiv: eine Altdatei mit zwei
        // Elementen liest sich als "keine Kennung", also keine spaetere
        // Entlastung - der konservative Ausgang.
        .put(
            "mealDeliveries",
            JSONArray(
                e.mealDeliveries.map { d ->
                    JSONArray(listOf(d.ts, d.amountU, d.proposalId ?: JSONObject.NULL))
                }
            )
        )
        // Die Serienliste des markerlosen Korrekturpfads (Variante 2) -
        // gleiche Tripel-Form, damit der Rollback nach einem Neustart
        // dieselbe Zeile findet. Ohne sie begaenne der Deckel nach jedem
        // Neustart wieder bei voll, und die Serie liefe erneut an.
        // Additiv: eine Altdatei ohne das Feld ergibt eine leere Liste.
        .put(
            "correctionDeliveries",
            JSONArray(
                e.correctionDeliveries.map { d ->
                    JSONArray(listOf(d.ts, d.amountU, d.proposalId ?: JSONObject.NULL))
                }
            )
        )
        .apply { encodeFoundation(e)?.let { put("foundation", it) } }

    /**
     * DIE MAHLZEITEN-AUTORISIERUNG UND IHRE BEZAHLUNG - ein Objekt, nicht
     * neun Felder nebeneinander (Punkt 7, Toni 18.08.).
     *
     * ZUSAMMEN ODER GAR NICHT. Der Zaehler `deliveredSinceHandoverU` ist ohne
     * die Autorisierung bedeutungslos, und die Autorisierung ohne Zaehler
     * gefaehrlich: Phase B faende dann eine unbezahlte Mahlzeit vor und
     * lieferte ihr Budget ein zweites Mal. Sie stehen deshalb in EINEM
     * Unterobjekt, das nur ganz oder gar nicht gelesen wird.
     *
     * OHNE VERSIONSSPRUNG, und das ist eine bewusste Abweichung von "neues
     * Schema" - aus demselben Grund wie beim Interventionsstempel: ein Bump
     * auf v5 laesst `require(v in LEGACY_VERSION..VERSION)` in einer AELTEREN
     * Fassung werfen. Ein Rollback nach einem Feldlauf faende die Datei dann
     * unlesbar und ginge in den Repair-Hold - schlimmer als das, was ohne
     * Bump passiert.
     *
     * Ohne Bump liest eine aeltere Fassung das Feld schlicht nicht. Sie kennt
     * kein Fundament, gibt also das volle Budget wie heute frei: das ist
     * exakt die Verhaltensparitaet von "Schalter aus", nicht mehr Insulin.
     *
     * STRIKT NACH INNEN: ist das Objekt DA, sind alle Felder Pflicht
     * (`getLong`/`getDouble`, kein `opt`). Eine halb geschriebene
     * Autorisierung ist Korruption und keine faellige Migration.
     *
     * `null` heisst "keine laufende Autorisierung" - dann steht das Feld gar
     * nicht in der Datei, statt als Leerobjekt Platz zu belegen.
     */
    fun encodeFoundation(e: EpisodeBudgets): JSONObject? {
        val a = e.foundation
        if (!a.valid) return null
        return JSONObject()
            .put("armedTs", a.armedTs)
            .put("totalBudgetU", a.totalBudgetU)
            .put("phaseAShare", a.phaseAShare)
            // Der gepinnte Sofortanteil (iLet, v27). Die Sofort-MENGE ist
            // abgeleitet und steht bewusst nicht hier - eine Wahrheit.
            .put("phaseAUpfrontShare", a.phaseAUpfrontShare)
            .put("pinnedPrimeWindowMin", a.pinnedPrimeWindowMin)
            .put("pinnedWallCeilingMin", a.pinnedWallCeilingMin)
            .put("endTs", a.endTs)
            .put("latchedHandoverTs", a.latchedHandoverTs)
            // Die beim Armen gepinnte Marker-Autorisierung. Sie MUSS mit:
            // ohne sie laese ein Neustart den aktuellen Preference-Wert, und
            // genau das soll das Pinning verhindern.
            .put("pinnedMarkerAuthorized", a.pinnedMarkerAuthorized)
            .put("deliveredSinceHandoverU", e.deliveredSinceHandoverU)
            // DER UEBERTRAG GEHOERT IN DIESES OBJEKT, nicht daneben.
            //
            // Er ist ohne die Autorisierung ebenso bedeutungslos wie die
            // Bezahlung - und gefaehrlicher: allein wiedergefunden erlaubte er
            // der NAECHSTEN Mahlzeit zusaetzliches Insulin fuer eine Luecke,
            // die zu einer laengst beendeten gehoert. "Zusammen oder gar
            // nicht" ist hier also nicht nur Ordnung, sondern die Zusicherung.
            //
            // Ein Feld MEHR in einem bestehenden Objekt: eine aeltere Fassung
            // liest es schlicht nicht und faellt damit auf das Verhalten ohne
            // Uebertrag zurueck - weniger Insulin, nicht mehr. Die
            // Gegenrichtung (neue Fassung, alte Datei) kann es nicht geben:
            // das Fundament ist nie geflasht worden, es existiert keine Datei
            // mit einem `foundation`-Objekt ohne dieses Feld.
            .put("confirmedNotSentPhaseAU", e.confirmedNotSentPhaseAU)
            // Sicherheitsaufschub aus dem gemessenen Abwaertsriegel. Das Feld
            // ist ab RULE_SET_VERSION 14 vorhanden; beim ersten Upgrade einer
            // bereits laufenden v13-Autorisierung fehlt es und bedeutet
            // konservativ 0, nicht Korruption.
            .put("descentDeferredPhaseAU", e.descentDeferredPhaseAU)
            // Der Phase-A-Bezahlstand gehoert aus demselben Grund hierher wie
            // die anderen beiden: er hat die Lebensdauer der Autorisierung,
            // nicht die der Evidenzepisode. Ginge er verloren, saehe ein
            // Neustart einen Phase-A-Rueckstand in voller Hoehe - und der
            // Uebertrag wuerde wirken, obwohl Prime laengst geliefert hat.
            .put("deliveredPhaseAU", e.deliveredPhaseAU)
            // Der Aufschub-Merker des Sofort-Batches: ohne ihn faellt ein
            // Neustart im Aufschub in eine sofortige Freigabe - die
            // Fehlrichtung waere "mehr Insulin". Additiv; eine Altdatei
            // ohne Feld heisst "nie aufgeschoben".
            .put("upfrontBatchDeferredSince", e.upfrontBatchDeferredSince)
            .put("upfrontTransferredU", e.upfrontTransferredU)
            .put("upfrontLapsedU", e.upfrontLapsedU)
    }

    /**
     * Der Evidenzbestand, Feld fuer Feld. KEIN Reflexions- oder
     * Bequemlichkeitsserialisierer: jedes Feld steht hier namentlich, damit
     * ein neues Feld beim Kompilieren auffaellt statt still zu verschwinden.
     */
    fun encodeEvidence(s: app.aaps.fuse.core.controller.EvidenceStock.State): JSONObject = JSONObject()
        .put("stockMgdl", s.stockMgdl)
        .put("episodeId", s.episodeId)
        .put("episodeStartTs", s.episodeStartTs)
        .put("lastAcceptedTs", s.lastAcceptedTs)
        .put("lastDecayTs", s.lastDecayTs)
        .put("lastCommittedU", s.lastCommittedU)
        .put("rebaseRequired", s.rebaseRequired)
        .put("lastCommitmentRevision", s.lastCommitmentRevision)

    fun decodeEvidence(o: JSONObject): app.aaps.fuse.core.controller.EvidenceStock.State {
        val stock = o.getDouble("stockMgdl")
        // Ein negativer oder unendlicher Bestand ist kein Zustand, den dieser
        // Kern je schreibt - also Korruption.
        require(stock.isFinite() && stock >= 0.0) { "invalid evidence stock $stock" }
        return app.aaps.fuse.core.controller.EvidenceStock.State(
            stockMgdl = stock,
            episodeId = requireTs("evidence.episodeId", o.getLong("episodeId")),
            episodeStartTs = requireTs("evidence.episodeStartTs", o.getLong("episodeStartTs")),
            lastAcceptedTs = requireTs("evidence.lastAcceptedTs", o.getLong("lastAcceptedTs")),
            lastDecayTs = requireTs("evidence.lastDecayTs", o.getLong("lastDecayTs")),
            lastCommittedU = requireAmount("evidence.lastCommittedU", o.getDouble("lastCommittedU")),
            rebaseRequired = o.getBoolean("rebaseRequired"),
            // Altdatei: fehlendes Feld = 0, zusammen mit dem 0-Default des
            // Episodenzaehlers konsistent (beide stehen in DERSELBEN Datei).
            // Negative Werte sind Korruption -> Recovery-Hold (Toni 29.08.).
            lastCommitmentRevision = requireRevision(
                "evidence.lastCommitmentRevision", o.optLong("lastCommitmentRevision", 0L),
            ),
        )
    }

    fun decodeEpisodes(o: JSONObject, schemaVersion: Int = VERSION): EpisodeBudgets {
        if (schemaVersion >= RECONCILIATION_VERSION)
            require(o.has("lastAcceptedSourceTs")) { "v$schemaVersion episodes without lastAcceptedSourceTs" }
        val e = EpisodeBudgets()
        // Budgets sind VERBRAUCH: negativ hiesse "Huelle groesser als
        // konfiguriert" - genau der Angriffs-/Korruptionspfad aus REG-01d.
        e.primeSpentU = requireAmount("primeSpentU", o.getDouble("primeSpentU"))
        // optional: aeltere Staende kennen das Feld nicht, 0 = "nie gesperrt".
        e.primeWindowStartTs = o.optLong("primeWindowStartTs", 0L).coerceAtLeast(0L)
        // optional wie die anderen Nachzuegler: eine Altdatei liest sich als
        // "nichts bezahlt, keine Episode". Das ist die konservative Richtung -
        // ohne episodeId gibt es keinen Kredit (EvidenceStock.NO_EPISODE),
        // und der Zaehler startet mit der naechsten Episode bei 0.
        e.evidenceCommittedU = requireAmount("evidenceCommittedU", o.optDouble("evidenceCommittedU", 0.0))
        // Altdatei: FEHLENDES Feld ist 0 (konsistent mit dem Evidence-State-
        // Default). Ein VORHANDENES negatives Feld ist dagegen Korruption -
        // ablehnen statt klemmen (Toni 29.08.): eine geklemmte 0 saehe wie
        // eine frische Episode aus und machte jeden spaeteren Widerruf zum
        // fail-closed UNKNOWN mit scheinbar gesunder Vorgeschichte. Der Wurf
        // fuehrt in den Recovery-Hold, wie jede andere kaputte Generation.
        e.evidenceCommitmentRevision = requireRevision(
            "evidenceCommitmentRevision", o.optLong("evidenceCommitmentRevision", 0L),
        )
        e.evidenceEpisodeId = requireTs("evidenceEpisodeId", o.optLong("evidenceEpisodeId", 0L))
        // Der verbrauchte Markeranker: fehlt er (Altdatei), gilt 0 - dann ist
        // noch nichts verbraucht. Das ist hier die WENIGER konservative
        // Richtung, aber die einzig moegliche: eine Datei, die den Anker nicht
        // kennt, kann auch nicht sagen, welcher Druck schon gezaehlt hat. Der
        // Prozess-Beobachtungspunkt haelt den Fall trotzdem zu - ein Marker
        // aus der Zeit vor dem Update wurde in DIESEM Prozess nicht gedrueckt.
        e.lastConsumedMarkerTs = requireTs("lastConsumedMarkerTs", o.optLong("lastConsumedMarkerTs", 0L))
        // Altdatei: nicht widerrufen. Der zustandslose Teil der Regel
        // (Preference auf 0 = zurueckgenommen) traegt den Fall trotzdem.
        e.evidenceRevoked = o.optBoolean("evidenceRevoked", false)
        // AB v4 PFLICHT. Fehlt das Feld in einer v4-Datei, ist das KORRUPTION
        // und keine faellige Migration - der Wurf macht die Generation
        // ungueltig, statt einen Leerbestand zu erfinden.
        if (schemaVersion >= EVIDENCE_VERSION) {
            require(o.has("evidenceState")) { "v$schemaVersion episodes without evidenceState" }
            e.evidenceState = decodeEvidence(o.getJSONObject("evidenceState"))
        }
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
        e.markerReboundOverrideDeadlineTs =
            requireTs("markerReboundOverrideDeadlineTs", o.optLong("markerReboundOverrideDeadlineTs"))
        e.markerReboundOverridePinnedFor =
            requireTs("markerReboundOverridePinnedFor", o.optLong("markerReboundOverridePinnedFor"))
        // DIE FRIST MUSS ZU IHREM DRUCK PASSEN (Codex 19.08.). Eine Frist
        // ohne Pinnung, eine vor ihrem Marker oder eine laenger als das
        // groesste zulaessige TTL kann dieser Schreiber nicht erzeugen -
        // sie waere ein Sonderrecht unbekannter Herkunft, und zwar in
        // Richtung MEHR Insulin.
        if (e.markerReboundOverrideDeadlineTs > 0L) {
            val pin = e.markerReboundOverridePinnedFor
            val frist = e.markerReboundOverrideDeadlineTs
            val maxMs = app.aaps.fuse.plugin.FuseIntKey.EvidenceReboundOverrideMaxMin.max.toLong() * 60_000L
            require(pin > 0L) { "rebound override deadline without pinned marker" }
            require(frist > pin) { "rebound override deadline $frist not after marker $pin" }
            require(frist - pin <= maxMs) { "rebound override ttl ${frist - pin} exceeds $maxMs" }
        }
        if (o.has("descentRecoveryLatch")) {
            val latch = o.getJSONObject("descentRecoveryLatch")
            e.descentRecoveryLatch =
                app.aaps.fuse.core.controller.DescentRecoveryLatch.State.restore(
                    active = latch.getBoolean("active"),
                    latchedAtTs = requireTs("descentRecoveryLatch.latchedAtTs", latch.getLong("latchedAtTs")),
                    // Additive Migration: alte Generationen wissen nur, dass
                    // der Riegel aktiv war. `false` verlangt dann weiter die
                    // volle Drei-Zyklen-Bestaetigung und ist konservativ.
                    sawMeasuredLow = latch.optBoolean("sawMeasuredLow", false),
                ) ?: error("invalid descent recovery latch")
        }
        if (o.has("ownPartialTbr") && !o.isNull("ownPartialTbr")) {
            val own = o.getJSONObject("ownPartialTbr")
            val kandidat = app.aaps.fuse.core.controller.PartialTbrOwnership.Own(
                rateUPerH = own.getDouble("rateUPerH"),
                setAtTs = requireTs("ownPartialTbr.setAtTs", own.getLong("setAtTs")),
                durationMin = own.getInt("durationMin"),
            )
            // Ein unbrauchbarer Nachweis wird VERWORFEN, nicht repariert:
            // dann gilt die laufende Absenkung als fremd, und das ist die
            // Richtung, die nichts Fremdes anfasst.
            e.ownPartialTbr = kandidat.takeIf { it.valid }
        }
        if (o.has("zeroLatch")) {
            val latch = o.getJSONObject("zeroLatch")
            e.zeroLatch =
                app.aaps.fuse.core.controller.DescentRecoveryLatch.State.restore(
                    active = latch.getBoolean("active"),
                    latchedAtTs = requireTs("zeroLatch.latchedAtTs", latch.getLong("latchedAtTs")),
                    sawMeasuredLow = latch.optBoolean("sawMeasuredLow", false),
                ) ?: error("invalid zero latch")
        }
        if (o.has("basalGap")) {
            val g = o.getJSONObject("basalGap")
            e.basalGap = EpisodeBudgets.BasalGapLatch(
                pinnedFor = requireTs("basalGap.pinnedFor", g.getLong("pinnedFor")),
                preMarkerBasalIobU = g.getDouble("preMarkerBasalIobU")
                    .also { require(it.isFinite()) { "basalGap.preMarkerBasalIobU not finite" } },
                zeroTbrActive = g.getBoolean("zeroTbrActive"),
                zeroTbrAgeMin = if (g.isNull("zeroTbrAgeMin")) null
                else g.getInt("zeroTbrAgeMin").also { require(it in 0..24 * 60) { "basalGap.zeroTbrAgeMin out of range" } },
                scheduledBasalUph = g.getDouble("scheduledBasalUph")
                    .also { require(it.isFinite() && it >= 0.0) { "basalGap.scheduledBasalUph invalid" } },
                omittedBasalU = if (g.isNull("omittedBasalU")) null
                else g.getDouble("omittedBasalU").also { require(it.isFinite() && it in 0.0..25.0) { "basalGap.omittedBasalU invalid" } },
            )
        }
        if (o.has("zeroTally")) e.zeroTally = tallyOf(o.getJSONObject("zeroTally"), "zeroTally")
        if (o.has("lastZeroTally")) e.lastZeroTally = tallyOf(o.getJSONObject("lastZeroTally"), "lastZeroTally")
        // Fail-closed: `ofPersisted` prueft die Identitaeten und liefert bei
        // jeder unvollstaendigen oder widerspruechlichen Kombination den
        // leeren Track. Ein fortgesetzter Zaehler kann mehr Insulin
        // freigeben als ein neu begonnener - deshalb wird hier geprueft,
        // nicht geglaubt.
        //
        // `isNull` VOR `optString`: auf Android liefert `optString` fuer ein
        // JSON-null den String "null", nicht den Defaultwert. Ein so
        // gelesener Fingerprint waere ein gueltig aussehender Fremdwert.
        if (o.has("upfrontRecovery")) {
            val t = o.getJSONObject("upfrontRecovery")
            val modus = if (t.isNull("mode")) "NONE" else t.optString("mode", "NONE")
            e.upfrontRecovery = app.aaps.fuse.core.controller.UpfrontRecovery.Track.ofPersisted(
                markerIdentity = t.optLong("markerIdentity", 0L),
                streak = t.optInt("streak", 0),
                lastAcceptedSourceTs = t.optLong("lastAcceptedSourceTs", 0L),
                lastEvaluationTs = t.optLong("lastEvaluationTs", 0L),
                mode = runCatching { app.aaps.fuse.core.controller.UpfrontRecovery.TrackMode.valueOf(modus) }
                    .getOrDefault(app.aaps.fuse.core.controller.UpfrontRecovery.TrackMode.NONE),
                fingerprint = if (t.isNull("fingerprint")) "" else t.optString("fingerprint", ""),
            )
        }
        if (o.has("correctionReversal")) {
            val rev = o.getJSONObject("correctionReversal")
            val minUkf = rev.getDouble("minUkf")
            require(minUkf.isFinite()) { "correctionReversal.minUkf not finite" }
            val reboundSeen = rev.getLong("reboundSeenTs")
            require(reboundSeen >= 0L) { "correctionReversal.reboundSeenTs out of range: $reboundSeen" }
            // restored erhaelt die Identitaet und NULLT die r-Bestaetigung
            // (konservative Richtung - wie die Erholungsserie des Latch).
            e.correctionReversal = app.aaps.fuse.core.controller.CorrectionReversalGuard.restored(
                minUkf = minUkf,
                minUkfTs = requireTs("correctionReversal.minUkfTs", rev.getLong("minUkfTs")),
                reboundSeenTs = reboundSeen,
            )
        }
        if (o.has("correctionRearm")) {
            val re = o.getJSONObject("correctionRearm")
            // Ein unbekannter Quellen-Name wirft (valueOf) und macht die
            // Generation ungueltig - Raten waere die falsche Richtung.
            e.correctionRearm = app.aaps.fuse.core.controller.PositiveCorrectionRearm.restored(
                ankerTs = requireTs("correctionRearm.ankerTs", re.getLong("ankerTs")),
                quelle = app.aaps.fuse.core.controller.PositiveCorrectionRearm.Source.valueOf(re.getString("quelle")),
            )
        }
        if (o.has("deferredPrime")) {
            val dp = o.getJSONObject("deferredPrime")
            val restored = app.aaps.fuse.core.controller.DeferredPrime.State(
                openU = dp.getDouble("openU"),
                pinnedForMarkerTs = dp.getLong("pinnedForMarkerTs"),
                deadlineTs = dp.getLong("deadlineTs"),
                horizonMin = dp.getInt("horizonMin"),
            )
            // Dieselbe Strenge wie beim Riegel: eine inkonsistente Datei wird
            // ABGEWIESEN statt geraten. Der Verfalls-Vermerk ist bewusst
            // NICHT persistiert - er ist Trail-Anzeige, kein Zustand.
            require(restored.valid) { "invalid deferred prime state" }
            require(restored.openU <= MAX_MEAL_DELIVERY_U * 10) { "deferred prime open out of range" }
            val post = dp.getDouble("postFoundationDeliveredU")
            require(post.isFinite() && post >= 0.0) { "postFoundationDeliveredU out of range: $post" }
            e.deferredPrime = restored
            e.postFoundationDeliveredU = post
        }
        if (o.has("livenessReArmUntilTs")) {
            val sperre = o.getLong("livenessReArmUntilTs")
            require(sperre >= 0L) { "livenessReArmUntilTs out of range: $sperre" }
            e.livenessReArmUntilTs = sperre
        }
        if (o.has("forecastShadowEpochTs")) {
            val epoche = o.getLong("forecastShadowEpochTs")
            require(epoche >= 0L) { "forecastShadowEpochTs out of range: $epoche" }
            e.forecastShadowEpochTs = epoche
        }
        if (o.has("forecastShadowLastState")) {
            val stand = o.getLong("forecastShadowLastState")
            require(stand in -1L..1L) { "forecastShadowLastState out of range: $stand" }
            e.forecastShadowLastState = stand
        }
        if (o.has("theilSenWindowLastMin")) {
            val fenster = o.getLong("theilSenWindowLastMin")
            require(fenster in 0L..1440L) { "theilSenWindowLastMin out of range: $fenster" }
            e.theilSenWindowLastMin = fenster
        }
        // GEMEINSAM validiert (Bauauftrag §3 + Review 24.08.): Pin und
        // Deadline sind EINE Identitaet. Beide fehlen = Altgeneration,
        // zulaessig; beide 0 = keine Autorisierung; beide positiv =
        // Beziehungen pruefen; NUR EINES vorhanden oder halb null =
        // beschaedigte Generation, ablehnen statt mit 0 ergaenzen.
        run {
            val hatPin = o.has("markerPowerPinnedFor")
            val hatFrist = o.has("markerPowerDeadlineTs")
            require(hatPin == hatFrist) { "markerPower half identity: pin=$hatPin deadline=$hatFrist" }
            if (!hatPin) return@run
            val pin = o.getLong("markerPowerPinnedFor")
            val frist = o.getLong("markerPowerDeadlineTs")
            require(pin >= 0L && frist >= 0L) { "markerPower out of range: $pin/$frist" }
            require((pin == 0L) == (frist == 0L)) { "markerPower half zero: $pin/$frist" }
            if (pin > 0L) {
                require(frist > pin) { "markerPowerDeadline not after pin: $pin/$frist" }
                require(frist - pin <= FuseIntKey.LivenessMealPowerMin.max * 60_000L) {
                    "markerPower window too long: ${frist - pin}"
                }
            }
            e.markerPowerPinnedFor = pin
            e.markerPowerDeadlineTs = frist
        }
        // KEINE MIGRATION, die ein Fundament ERFINDET: fehlt das Objekt, gibt
        // es keine laufende Autorisierung. Eine Altdatei mitten in einer
        // Mahlzeit liest sich damit als "kein Fundament" - Prime finanziert
        // wie bisher weiter, also heutiges Verhalten. Wuerde hier stattdessen
        // aus Budget und Anteil eine Autorisierung nachgebildet, entstuende
        // eine Insulinfreigabe, die niemand erteilt hat.
        if (o.has("foundation")) decodeFoundation(o.getJSONObject("foundation"), e)
        val md = o.getJSONArray("mealDeliveries")
        require(md.length() <= MAX_MEAL_DELIVERIES) { "mealDeliveries size ${md.length()}" }
        for (i in 0 until md.length()) {
            val pair = md.getJSONArray(i)
            val ts = requireTs("mealDeliveries[$i].ts", pair.getLong(0))
            val u = pair.getDouble(1)
            require(u.isFinite() && u > 0.0 && u <= MAX_MEAL_DELIVERY_U) { "mealDeliveries[$i].u out of range: $u" }
            // Der dritte Platz ist optional: Dateien vor dem 19.08. tragen ihn
            // nicht, und dann gibt es fuer diesen Eintrag keine Entlastung mehr.
            //
            // IST ER DA, IST ER EINE INVARIANTE (Toni 19.08.). Eine "stabile
            // Identitaet", die leer, unbegrenzt lang oder doppelt sein darf,
            // ist keine - und der Rueckdreher sucht mit `indexOfFirst`, also
            // traefe er bei einer doppelten Kennung die falsche Zeile.
            val id = if (pair.length() > 2 && !pair.isNull(2)) pair.getString(2) else null
            if (id != null) {
                require(id.isNotBlank()) { "mealDeliveries[$i].proposalId leer" }
                require(id.length <= MAX_PROPOSAL_ID_LEN) {
                    "mealDeliveries[$i].proposalId zu lang: ${id.length}"
                }
                require(e.mealDeliveries.none { it.proposalId == id }) {
                    "mealDeliveries[$i].proposalId doppelt: $id"
                }
            }
            e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts, u, id))
        }
        if (o.has("correctionDeliveries")) {
            val cd = o.getJSONArray("correctionDeliveries")
            require(cd.length() <= MAX_MEAL_DELIVERIES) { "correctionDeliveries size ${cd.length()}" }
            for (i in 0 until cd.length()) {
                val t = cd.getJSONArray(i)
                val ts = requireTs("correctionDeliveries[$i].ts", t.getLong(0))
                val u = t.getDouble(1)
                require(u.isFinite() && u > 0.0 && u <= MAX_MEAL_DELIVERY_U) {
                    "correctionDeliveries[$i].u out of range: $u"
                }
                val id = if (t.length() > 2 && !t.isNull(2)) t.getString(2) else null
                if (id != null) {
                    require(id.isNotBlank()) { "correctionDeliveries[$i].proposalId leer" }
                    require(id.length <= MAX_PROPOSAL_ID_LEN) {
                        "correctionDeliveries[$i].proposalId zu lang: ${id.length}"
                    }
                    require(e.correctionDeliveries.none { it.proposalId == id }) {
                        "correctionDeliveries[$i].proposalId doppelt: $id"
                    }
                }
                e.correctionDeliveries.addLast(EpisodeBudgets.CorrectionDelivery(ts, u, id))
            }
        }
        return e
    }

    /**
     * Die Autorisierung zurueckholen - die Generation faellt, wenn sie kaputt
     * ist.
     *
     * FEHLEN UND KAPUTT SIND NICHT DASSELBE (Toni 18.08., P0). Der erste Wurf
     * stufte eine vorhandene, aber widerspruechliche Autorisierung still auf
     * `none()` herab - also auf genau die Lesart, die fuer eine ALTDATEI
     * richtig ist. Drei Dinge liefen damit schief:
     *
     *   Prime fiel auf das aktuelle volle LIVE-Budget zurueck, statt auf das
     *   gepinnte Teilbudget - mehr Insulin, nicht weniger;
     *
     *   der Decoder meldete keinen Fehler, also durfte die BESCHAEDIGTE
     *   neuere Generation gegen eine intakte aeltere gewinnen;
     *
     *   und der Ausfall war unsichtbar: kein Hold, kein Log, nichts, was
     *   spaeter erklaert haette, warum das Fundament ploetzlich schwieg.
     *
     * Ist das Objekt DA, ist ein Widerspruch darin Korruption. Der Wurf macht
     * die Generation ungueltig; die Wahl faellt dann auf eine aeltere oder in
     * den Repair-Hold - beides sichtbar. Nur das FEHLENDE Objekt heisst
     * "Legacy / kein Fundament" (s. [decodeEpisodes]).
     */
    fun decodeFoundation(o: JSONObject, e: EpisodeBudgets) {
        val a = app.aaps.fuse.core.controller.MealFoundation.Authorization.restore(
            armedTs = requireTs("foundation.armedTs", o.getLong("armedTs")),
            totalBudgetU = requireAmount("foundation.totalBudgetU", o.getDouble("totalBudgetU")),
            phaseAShare = o.getDouble("phaseAShare"),
            // Der Sofortanteil (iLet, ab RULE_SET_VERSION 27) folgt dem
            // descentDeferredPhaseAU-Migrationsmuster: eine bereits laufende
            // Alt-Autorisierung ohne das Feld hat KONSERVATIV keinen
            // Sofortanteil (0), das ist keine Korruption.
            phaseAUpfrontShare = if (o.has("phaseAUpfrontShare")) o.getDouble("phaseAUpfrontShare") else 0.0,
            pinnedPrimeWindowMin = o.getInt("pinnedPrimeWindowMin"),
            pinnedWallCeilingMin = o.getInt("pinnedWallCeilingMin"),
            endTs = requireTs("foundation.endTs", o.getLong("endTs")),
            latchedHandoverTs = requireTs("foundation.latchedHandoverTs", o.getLong("latchedHandoverTs")),
            pinnedMarkerAuthorized = o.getBoolean("pinnedMarkerAuthorized"),
        )
        val bezahlt = requireAmount("foundation.deliveredSinceHandoverU", o.getDouble("deliveredSinceHandoverU"))
        val uebertrag =
            requireAmount("foundation.confirmedNotSentPhaseAU", o.getDouble("confirmedNotSentPhaseAU"))
        val abwaertsAufschub = if (o.has("descentDeferredPhaseAU"))
            requireAmount("foundation.descentDeferredPhaseAU", o.getDouble("descentDeferredPhaseAU"))
        else 0.0
        val phaseA = requireAmount("foundation.deliveredPhaseAU", o.getDouble("deliveredPhaseAU"))
        // KEIN stilles none(): s. den Blockkommentar. Die Felder waren alle da
        // und einzeln plausibel - erst ihre Beziehung ist kaputt, und das kann
        // keine faellige Migration sein.
        require(a.valid) { "corrupt foundation authorization" }
        // DIESELBE ART VON BEZIEHUNGSPRUEFUNG, eine Ebene hoeher: ein
        // Uebertrag oberhalb des autorisierten Gesamtbudgets kann von diesem
        // Schreiber nicht stammen ([FuseLedgerAdapter.revokeSettled] deckelt
        // dort). Ihn beim Lesen still zu kappen hiesse, eine beschaedigte
        // Generation gueltig zu machen - und zwar in Richtung MEHR Insulin.
        require(uebertrag <= a.totalBudgetU + 1e-9) {
            "foundation carry $uebertrag exceeds total budget ${a.totalBudgetU}"
        }
        require(abwaertsAufschub <= a.totalBudgetU + 1e-9) {
            "foundation descent defer $abwaertsAufschub exceeds total budget ${a.totalBudgetU}"
        }
        // HIER STEHT BEWUSST KEIN BEZIEHUNGSRIEGEL - und das ist eine
        // Feststellung, keine Auslassung (Codex 19.08.).
        //
        // Vorgeschlagen war `bezahlt <= evidenceCommittedU`. Das waere falsch:
        // die beiden haben verschiedene Lebensdauern, und eine gesunde zweite
        // Mahlzeit im 360-Minuten-Deckel verletzt es regelmaessig - Test
        // `ohne Evidenzepisode waechst nur der Bezahlstand`.
        //
        // Der naheliegende Ersatz `phaseA + bezahlt <= totalBudget` ist
        // GENAUSO falsch, nur unauffaelliger: beide Zaehler zaehlen ALLES,
        // was in ihrer Phase floss, auch gewoehnliche Korrektur. Und
        // Korrektur- und Evidenzinsulin duerfen ausdruecklich ZUSAETZLICH zum
        // Mahlzeitenbudget entstehen (bestaetigter Vertrag, s.
        // MealFoundationReplayTest). Ein Riegel darauf schickte jede
        // Mahlzeit mit Nachkorrektur in den RECOVERY_HOLD.
        //
        // Bleibt die FELDWEISE Pruefung: `requireAmount` verlangt endlich,
        // nicht negativ, im Mengenrahmen. Mehr ist ueber diese Felder ehrlich
        // nicht zu sagen - und ein Riegel, der gesunde Zustaende abweist, ist
        // teurer als gar keiner.
        e.foundation = a
        e.deliveredSinceHandoverU = bezahlt
        e.confirmedNotSentPhaseAU = uebertrag
        e.descentDeferredPhaseAU = abwaertsAufschub
        e.deliveredPhaseAU = phaseA
        if (o.has("upfrontBatchDeferredSince"))
            e.upfrontBatchDeferredSince =
                requireTs("foundation.upfrontBatchDeferredSince", o.getLong("upfrontBatchDeferredSince"))
        if (o.has("upfrontTransferredU"))
            e.upfrontTransferredU =
                requireAmount("foundation.upfrontTransferredU", o.getDouble("upfrontTransferredU"))
        if (o.has("upfrontLapsedU"))
            e.upfrontLapsedU =
                requireAmount("foundation.upfrontLapsedU", o.getDouble("upfrontLapsedU"))
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
        // AB v3 ZWINGEND EIN ARRAY (Toni 18.08., Codec-Fund 2).
        //
        // Die Praesenzpflicht allein war wirkungslos gegen KAPUTTEN Inhalt:
        // `has()` ist gemessen `true` fuer `"retiredBoundIds": null` und fuer
        // jeden Nicht-Array-Wert, `optJSONArray` liefert darauf `null`, und das
        // fruehe `?: return emptyList()` machte daraus stillschweigend die
        // leere Menge - also genau die Deutung, die der Kommentar oben als die
        // gefaehrliche benennt. Der Encoder schreibt an dieser Stelle immer ein
        // echtes Array (notfalls leer); alles andere ist Fremdinhalt.
        //
        // Der Schaden waere nicht abstrakt: die Menge ist die PERSISTENTE
        // Ausschlussmenge der Bindung. Leer heisst, ein bereits verbuchter
        // fremder Bolus darf eine offene Zeile erneut binden und ihre Haftung
        // ausbuchen, ohne dass je Insulin nachgewiesen wurde - und ausgebuchte
        // Haftung ist freie Kapazitaet fuer die naechste Dosis.
        if (schemaVersion >= RECONCILIATION_VERSION) {
            require(o.has("retiredBoundIds")) { "v$schemaVersion file without retiredBoundIds" }
            require(o.optJSONArray("retiredBoundIds") != null) {
                "v$schemaVersion retiredBoundIds is not an array"
            }
        }
        // opt statt get: Dateien vor Fix 6 tragen das Feld nicht - fuer sie
        // ist die leere Menge der ehrliche Zustand, kein Fehler. Ab v3 kann
        // dieser Zweig nicht mehr greifen, dort hat der require schon geworfen.
        val arr = o.optJSONArray("retiredBoundIds") ?: return emptyList()
        val list = arr.objects().map { r ->
            RetiredBoundId(temporaryId = r.lngOrNull("temporaryId", schemaVersion), pumpId = r.lngOrNull("pumpId", schemaVersion))
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
            val pinTyp = obj.strOrNull("pumpType", schemaVersion)
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
                obj.strOrNull("pumpType", schemaVersion), obj.strOrNull("pumpSerialHash", schemaVersion), unpinned, legacyOpen,
                // KEIN requireWritten: dieses Feld schreibt der Encoder NUR bei
                // `patchEpochApplicable` (s. encodePumpEpoch) - bei einer
                // Nicht-Patch-Pumpe gibt es die Kategorie gar nicht. Seine
                // Praesenz ist oben unter genau den passenden Bedingungen
                // gefordert (v3 + normaler Pin + nicht emuliert + anwendbar);
                // ein zweiter, unbedingter Riegel hier wuerde eine korrekte
                // Medtrum-freie Datei abweisen.
                patchEpochTs = if (anwendbar) {
                    if (obj.isNull("patchEpochTs")) null else obj.getLong("patchEpochTs")
                } else null,
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

    private fun requireRevision(name: String, v: Long): Long {
        require(v >= 0L) { "$name negative revision: $v" }
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
            amounts = decodeAmounts(o.getJSONObject("amounts"), schemaVersion),
            accounting = AccountingState.valueOf(o.getString("accounting")),
            delivery = DeliveryState.valueOf(o.getString("delivery")),
            identity = o.objOrNull("identity", schemaVersion)?.let { decodeIdentity(it, schemaVersion) },
            queueReject = o.strOrNull("queueReject", schemaVersion)?.let { QueueRejectReason.valueOf(it) },
            withdrawnProven = o.getBoolean("withdrawnProven"),
            contradicted = o.getBoolean("contradicted"),
            conservativeFloorU = requireAmountOrNull("conservativeFloorU", o.dblOrNull("conservativeFloorU", schemaVersion)),
            accountedAmountU = requireAmountOrNull("accountedAmountU", o.dblOrNull("accountedAmountU", schemaVersion)),
            amountEpsU = requireAmount("amountEpsU", o.getDouble("amountEpsU")),
            bolusStepU = requireAmount("bolusStepU", o.getDouble("bolusStepU")),
            firstAccountedSnapshotHash = o.strOrNull("firstAccountedSnapshotHash", schemaVersion),
            lastReconciledViewHash = o.strOrNull("lastReconciledViewHash", schemaVersion),
            lastReconciledAtTs = o.lngOrNull("lastReconciledAtTs", schemaVersion),
            // Fehlt das Feld, ist die Antwort NICHT "es gab nie einen Fakt" -
            // sie ist "wir wissen es nicht". Beide sehen als `null` gleich aus,
            // deshalb faengt der Versionsvertrag den Fall: eine Datei unter
            // RECONCILIATION_VERSION geht in den Migrations-Hold und wird gar
            // nicht erst als Laufzeitzustand uebernommen (s. decode).
            lastPositiveFactTs = o.lngOrNull("lastPositiveFactTs", schemaVersion, RECONCILIATION_VERSION),
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
    private fun decodeAmounts(o: JSONObject, schemaVersion: Int): AmountAxis = AmountAxis(
        proposedU = requireAmount("proposedU", o.getDouble("proposedU")),
        rtPublishedU = requireAmountOrNull("rtPublishedU", o.dblOrNull("rtPublishedU", schemaVersion)),
        loopConstrainedU = requireAmountOrNull("loopConstrainedU", o.dblOrNull("loopConstrainedU", schemaVersion)),
        queueConstrainedU = requireAmountOrNull("queueConstrainedU", o.dblOrNull("queueConstrainedU", schemaVersion)),
        pumpCommandU = requireAmountOrNull("pumpCommandU", o.dblOrNull("pumpCommandU", schemaVersion)),
        reportedDeliveredU = requireAmountOrNull("reportedDeliveredU", o.dblOrNull("reportedDeliveredU", schemaVersion)),
        provenDeliveredU = requireAmountOrNull("provenDeliveredU", o.dblOrNull("provenDeliveredU", schemaVersion)),
        dbAccountedU = requireAmountOrNull("dbAccountedU", o.dblOrNull("dbAccountedU", schemaVersion)),
    )

    private fun encodeIdentity(i: PumpTreatmentIdentity): JSONObject = JSONObject()
        .put("proposalId", i.proposalId)
        .putNullable("temporaryId", i.temporaryId)
        .putNullable("pumpId", i.pumpId)
        .put("pumpType", i.pumpType)
        .put("pumpSerialHash", i.pumpSerialHash)
        .put("treatmentTimestamp", i.treatmentTimestamp)

    private fun decodeIdentity(o: JSONObject, schemaVersion: Int): PumpTreatmentIdentity = PumpTreatmentIdentity(
        proposalId = o.getString("proposalId"),
        temporaryId = o.lngOrNull("temporaryId", schemaVersion),
        pumpId = o.lngOrNull("pumpId", schemaVersion),
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

    private fun decodeError(o: JSONObject, schemaVersion: Int): LedgerErrorRecord = LedgerErrorRecord(
        proposalId = o.strOrNull("proposalId", schemaVersion),
        error = LedgerError.valueOf(o.getString("error")),
        firstDetail = o.getString("firstDetail"),
        lastDetail = o.getString("lastDetail"),
        occurrences = o.getInt("occurrences"),
        active = o.getBoolean("active"),
        activeGeneration = o.getLong("activeGeneration"),
        resolvedBy = o.strOrNull("resolvedBy", schemaVersion),
        resolvedReason = o.strOrNull("resolvedReason", schemaVersion),
        resolvedGeneration = o.lngOrNull("resolvedGeneration", schemaVersion),
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

    /**
     * PRAESENZPFLICHT FUER EIN IMMER GESCHRIEBENES NULLABLE-FELD
     * (Toni 18.08., Codec-Fund 1).
     *
     * DER BEFUND. `JSONObject.isNull(key)` liefert `true` auch fuer einen
     * FEHLENDEN Schluessel - gemessen, nicht vermutet. Die Leser unten
     * konnten damit "Schluessel weg" nicht von "ausdruecklich null"
     * unterscheiden. Der Encoder schreibt den Schluessel aber IMMER:
     * `putNullable(key, v) = put(key, v ?: JSONObject.NULL)`. Ein fehlender
     * Schluessel kann also gar nicht vom eigenen Schreiber stammen - er ist
     * Korruption und wurde als "kein Wert" gelesen.
     *
     * Bei `conservativeFloorU` heisst das konkret: die Untergrenze der
     * Haftung faellt weg, die Schuld der Zeile sinkt auf die kleinere
     * widersprechende Stufe - genau das, was der Reducer verhindern soll -,
     * und die beschaedigte NEUERE Generation gewinnt gegen eine intakte
     * aeltere, weil der Decoder nicht gemeckert hat. Der realistische Pfad
     * dahin ist kein abgeschnittener Schreibvorgang (der parst nicht),
     * sondern ein Bit-Flip IM SCHLUESSELNAMEN: das JSON bleibt gueltig, der
     * Schluessel ist weg.
     *
     * KEINE GLOBALE VERSCHAERFUNG, sondern feld- und versionsbezogen (Tonis
     * Auflage): aeltere Schemastaende duerfen Felder tatsaechlich noch nicht
     * besitzen. [since] sagt, ab welcher Schemaversion der zugehoerige
     * Encoder den Schluessel schreibt.
     *
     * DIE BELEGE ZU [STRICT_VERSION] ALS DEFAULT, aus der Historie dieser
     * Datei erhoben statt geschaetzt:
     *
     *   26 der 27 Nullable-Felder standen schon VOR dem v2-Commit
     *   (bae885f1f6) im Encoder - in jeder v2-Datei sind sie also da.
     *
     *   Genau EINES kam zwischen v2 und v3 dazu, `lastPositiveFactTs`.
     *   Es traegt deshalb [RECONCILIATION_VERSION], und diese Ableitung
     *   trifft die Praesenzpflicht, die dort schon von Hand stand.
     *
     *   Mit v3, v4 und danach kam KEIN weiteres Nullable-Feld hinzu.
     *
     * v1 bleibt nachsichtig: das ist der Altbestand unbekannter Herkunft,
     * fuer den "Feld fehlt" eine ehrliche Aussage sein kann.
     */
    private fun JSONObject.requireWritten(key: String, schemaVersion: Int, since: Int) {
        if (schemaVersion >= since)
            require(has(key)) { "v$schemaVersion missing always-written field '$key'" }
    }

    private fun JSONObject.strOrNull(key: String, schemaVersion: Int, since: Int = STRICT_VERSION): String? {
        requireWritten(key, schemaVersion, since)
        return if (isNull(key)) null else getString(key)
    }

    private fun JSONObject.dblOrNull(key: String, schemaVersion: Int, since: Int = STRICT_VERSION): Double? {
        requireWritten(key, schemaVersion, since)
        return if (isNull(key)) null else getDouble(key)
    }

    private fun JSONObject.lngOrNull(key: String, schemaVersion: Int, since: Int = STRICT_VERSION): Long? {
        requireWritten(key, schemaVersion, since)
        return if (isNull(key)) null else getLong(key)
    }

    private fun JSONObject.objOrNull(key: String, schemaVersion: Int, since: Int = STRICT_VERSION): JSONObject? {
        requireWritten(key, schemaVersion, since)
        return if (isNull(key)) null else getJSONObject(key)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun tallyJson(t: EpisodeBudgets.ZeroPhaseTally): JSONObject = JSONObject()
        .put("sinceTs", t.sinceTs)
        .put("lastTickTs", t.lastTickTs)
        .put("minutes", t.minutes)
        .put("omittedU", t.omittedU)
        .put("reasonAbsentMin", t.reasonAbsentMin)
        .put("flatAbsentMin", t.flatAbsentMin)
        .put("gapCappedMin", t.gapCappedMin)

    /**
     * FAIL-CLOSED wie die anderen Ledger-Bloecke: eine unplausible Bilanz
     * wird nicht geradegebogen, sondern verworfen. Die Obergrenzen sind
     * grosszuegig (48 h, 50 U) - sie fangen kaputte Dateien, nicht
     * ungewoehnliche Naechte.
     */
    private fun tallyOf(o: JSONObject, pfad: String): EpisodeBudgets.ZeroPhaseTally {
        fun zahl(k: String, max: Double): Double = o.getDouble(k)
            .also { require(it.isFinite() && it in 0.0..max) { "$pfad.$k invalid" } }
        return EpisodeBudgets.ZeroPhaseTally(
            sinceTs = requireTs("$pfad.sinceTs", o.getLong("sinceTs")),
            lastTickTs = requireTs("$pfad.lastTickTs", o.getLong("lastTickTs")),
            minutes = zahl("minutes", 48 * 60.0),
            omittedU = zahl("omittedU", 50.0),
            reasonAbsentMin = zahl("reasonAbsentMin", 48 * 60.0),
            flatAbsentMin = zahl("flatAbsentMin", 48 * 60.0),
            gapCappedMin = zahl("gapCappedMin", 48 * 60.0),
        )
    }
}

package app.aaps.fuse.core.journal

/**
 * Die REGELN des Lieferjournals - pur, ohne Uhr, ohne Datei, ohne AAPS.
 *
 * Jede Aenderung ist ein Ereignis, und jedes Ereignis ist idempotent: dasselbe
 * Ereignis zweimal angewandt ergibt denselben Zustand. Das ist keine
 * Eleganz-Uebung, sondern Voraussetzung fuer die Wiederaufnahme - nach einem
 * Prozessende ist nicht sicher entscheidbar, ob ein Ereignis noch geschrieben
 * wurde, und der Wiederholungsversuch darf keinen Schaden anrichten.
 *
 * "Idempotent" heisst hier STRENG: dasselbe Ereignis mit demselben Inhalt.
 * Ein Ereignis desselben Typs mit ANDEREM Zeitstempel oder anderer Quelle ist
 * ein zweites Ereignis und damit ein Widerspruch - es still zu schlucken hiesse,
 * zwei verschiedene Beweise als einen zu fuehren.
 *
 * ZWEI DINGE, DIE HIER STRENG GETRENNT SIND und deren Vermischung der teuerste
 * Fehler des ersten Entwurfs war:
 *
 *   BUCHEN  ([reduce]) haelt fest, was GESCHEHEN ist. Auch ein Sendeversuch,
 *           den niemand autorisiert hat, muss gebucht werden - der Treiber
 *           wiederholt automatisch, und ein Journal, das diese Wiederholung
 *           nicht kennt, ist fuer genau den gefaehrlichsten Fall blind.
 *
 *   ERLAUBEN ([mayAttempt]) beantwortet, ob JETZT gesendet werden DARF. Hier
 *           gilt die Hauptinvariante ohne Ausnahme - auch fuer den eigenen
 *           Retry.
 */
object DeliveryJournal {

    sealed interface Event {

        /** Ein Auftrag entsteht. MUSS vor dem ersten Sendeversuch durabel sein. */
        data class Created(
            val requestId: String,
            val proposalId: String,
            val requestedU: Double,
            val atTs: Long,
            val pumpEpoch: String?,
        ) : Event

        /**
         * Ein Sendeversuch beginnt - gebucht UNMITTELBAR VOR dem Aufruf, der
         * ihn ausloest, einschliesslich jeder automatischen Wiederholung.
         *
         * Die Reihenfolge ist der ganze Punkt: wer erst nach dem Aufruf bucht,
         * verliert genau den Fall, fuer den das Journal existiert.
         */
        data class SendAttemptStarted(
            val requestId: String,
            val attempt: Int,
            val atTs: Long,
            val transportSequence: Long? = null,
        ) : Event

        /** Der Schreibauftrag wurde vom Betriebssystem angenommen - ab hier
         *  ist der Ausgang unbekannt (s. [AmbiguityBoundary]). */
        data class WriteAccepted(val requestId: String, val attempt: Int, val atTs: Long) : Event

        /**
         * BEWIESEN: dieser Versuch hat nichts geschrieben.
         *
         * Der einzige entlastende Fakt des Moduls, und deshalb der einzige,
         * der ausdruecklich gemeldet werden MUSS. Ein Versuch entlastet sich
         * nie von selbst - weder durch Vorbelegung noch durch Zeitablauf.
         * Wer dieses Ereignis bucht, behauptet Wissen und muss es haben.
         */
        data class WriteRefused(val requestId: String, val attempt: Int, val atTs: Long, val source: String) : Event

        /** Erste Phase der Identitaet. */
        data class TemporaryIdObserved(val requestId: String, val temporaryId: Long) : Event

        /** Zweite Phase - ein UPDATE derselben Lieferung. */
        data class PumpIdObserved(val requestId: String, val pumpId: Long) : Event

        /** Beweisbar nichts abgegeben. Nur gueltig, solange KEIN Versuch die
         *  Pumpe erreicht haben kann - also alle Versuche ausdruecklich
         *  verweigert wurden oder es gar keinen gab. */
        data class ProvenNotSent(val requestId: String, val atTs: Long, val source: String) : Event

        /** Abgabe nachgewiesen. */
        data class DeliveryConfirmed(val requestId: String, val atTs: Long, val source: String) : Event

        /**
         * Der Ledger/IOB hat die Menge uebernommen - das Journal ist mit dieser
         * Zeile fertig.
         *
         * Ohne dieses Ereignis bliebe eine bestaetigte Abgabe unbegrenzt offen
         * und wuerde neben derselben Menge im Ledger stehen. Genau das ist der
         * Uebergang, den der Zustaendigkeitsvertrag verlangt.
         */
        data class LiabilityAccounted(val requestId: String, val atTs: Long, val source: String) : Event
    }

    /** Warum ein Ereignis NICHT angewandt wurde. */
    enum class Rejection {

        UNKNOWN_REQUEST,

        /** Ein zweiter Auftrag unter derselben Id mit anderem Inhalt. */
        DUPLICATE_REQUEST_CONFLICT,

        /** Versuchsnummern muessen lueckenlos ab 1 wachsen - eine Luecke hiesse,
         *  dass ein Versuch nicht gebucht wurde. */
        ATTEMPT_OUT_OF_ORDER,

        /** Nach einem Terminalzustand gibt es keine neuen Versuche mehr. */
        ATTEMPT_AFTER_TERMINAL,

        /** Eine Grenzueberschreitung fuer einen Versuch, den es nicht gibt. */
        UNKNOWN_ATTEMPT,

        /** Ein Terminalfakt widerspricht einem bereits gesetzten anderen. */
        TERMINAL_CONFLICT,

        /** Eine Identitaet widerspricht der bereits gebundenen. */
        IDENTITY_CONFLICT,

        /** Uebernahme ohne nachgewiesene Abgabe. */
        ACCOUNTED_WITHOUT_DELIVERY,

        /** Ein Nullbeweis, obwohl mindestens ein Versuch die Pumpe erreicht
         *  haben kann. Frueher hiess dieser Fall NOT_SENT_AFTER_BOUNDARY und
         *  deckte nur die OS-Quittung. */
        NOT_SENT_WHILE_ATTEMPT_UNRESOLVED,

        /** Der Datensatz waere ungueltig geworden - z.B. ruecklaeufige
         *  Versuchszeiten. Wird als Ablehnung gemeldet, NICHT geworfen. */
        INVALID_STATE,
    }

    data class Result(val state: DeliveryJournalState, val rejection: Rejection? = null) {

        val applied: Boolean get() = rejection == null
    }

    /** Eine abgelehnte Stelle im Strom - MIT Index, damit sie im Trail
     *  auffindbar ist und nicht nur als Zahl auftaucht. */
    data class IndexedRejection(val index: Int, val event: Event, val rejection: Rejection)

    data class BatchResult(val state: DeliveryJournalState, val rejections: List<IndexedRejection> = emptyList()) {

        val applied: Boolean get() = rejections.isEmpty()
    }

    /**
     * Einen ganzen Ereignisstrom anwenden - typischerweise beim REPLAY.
     *
     * EINE ABLEHNUNG BEENDET DEN STROM NICHT (Codex-Gegenpruefung, P0). Die
     * erste Fassung hielt nach der ersten Ablehnung an und verwarf alles
     * Folgende. Dabei ist genau die Reihenfolge realistisch, in der das
     * schadet:
     *
     *     PROVEN_NOT_SENT
     *     zweiter, widersprechender Nullbeweis   -> Rejection
     *     spaetere Pumpenhistorie: DELIVERY_CONFIRMED
     *
     * Beim Replay waere die Bestaetigung nie verarbeitet worden, und der
     * Zustand bliebe faelschlich geschlossen - eine abgegebene Menge ohne
     * Haftung. Ein widersprechendes Ereignis ist ein Befund ueber SICH
     * SELBST, kein Grund, spaeteren Fakten die Anwendung zu verweigern.
     *
     * Fortgefahren wird mit dem letzten GUELTIGEN Zustand; jede Ablehnung
     * wird mit ihrem Index gesammelt.
     */
    fun reduceAll(state: DeliveryJournalState, events: List<Event>): BatchResult {
        var s = state
        val abgelehnt = mutableListOf<IndexedRejection>()
        events.forEachIndexed { i, e ->
            val r = reduce(s, e)
            // IMMER den zurueckgegebenen Zustand uebernehmen, auch bei einer
            // Ablehnung: manche Ablehnungen halten den belastenden Fakt als
            // BEFUND an der Zeile fest (unverankerte Quittung,
            // Identitaetskonflikt). Wer hier nur den Erfolgsfall uebernimmt,
            // wirft genau die Spur weg, die die Ablehnung hinterlassen sollte.
            s = r.state
            if (!r.applied) abgelehnt += IndexedRejection(i, e, r.rejection!!)
        }
        return BatchResult(s, abgelehnt)
    }

    /**
     * Ein Ereignis anwenden.
     *
     * WIRFT NIE. Die Invarianten der Datentypen sind scharf (ruecklaeufige
     * Zeiten, unmoegliche Kombinationen) - eine Ausnahme daraus wuerde den
     * ganzen Strom mitnehmen und ausgerechnet die spaeteren, konservativeren
     * Fakten verschlucken. Genau der Schaden, gegen den [reduceAll] gehaertet
     * wurde, nur ueber den Ausnahmekanal. Deshalb wird hier gekapselt und als
     * [Rejection.INVALID_STATE] gemeldet.
     */
    fun reduce(state: DeliveryJournalState, event: Event): Result =
        try {
            reduceOrThrow(state, event)
        } catch (e: IllegalArgumentException) {
            Result(state, Rejection.INVALID_STATE)
        }

    private fun reduceOrThrow(state: DeliveryJournalState, event: Event): Result = when (event) {
        is Event.Created             -> onCreated(state, event)
        is Event.SendAttemptStarted  -> onAttempt(state, event)
        is Event.WriteAccepted       -> onWriteAccepted(state, event)
        is Event.WriteRefused        -> onWriteRefused(state, event)
        is Event.TemporaryIdObserved -> onTemporaryId(state, event)
        is Event.PumpIdObserved      -> onPumpId(state, event)
        is Event.ProvenNotSent       -> onProvenNotSent(state, event)
        is Event.DeliveryConfirmed   -> onConfirmed(state, event)
        is Event.LiabilityAccounted  -> onAccounted(state, event)
    }

    // ---- einzelne Ereignisse ---------------------------------------------

    private fun onCreated(state: DeliveryJournalState, e: Event.Created): Result {
        // Leer ist keine Epoch - hier normalisiert, damit sie gar nicht erst
        // in den Zustand kommt.
        val epoch = e.pumpEpoch?.takeIf { it.isNotBlank() }
        val vorhanden = state.requests[e.requestId]
        if (vorhanden != null) {
            val gleich = vorhanden.proposalId == e.proposalId &&
                vorhanden.requestedU == e.requestedU &&
                vorhanden.createdAtTs == e.atTs &&
                vorhanden.pumpEpoch == epoch
            return if (gleich) Result(state) else Result(state, Rejection.DUPLICATE_REQUEST_CONFLICT)
        }
        return put(
            state,
            DeliveryRequest(
                requestId = e.requestId, proposalId = e.proposalId, requestedU = e.requestedU,
                createdAtTs = e.atTs, pumpEpoch = epoch,
            )
        )
    }

    private fun onAttempt(state: DeliveryJournalState, e: Event.SendAttemptStarted): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        r.attempts.firstOrNull { it.attempt == e.attempt }?.let { vorhanden ->
            val gleich = vorhanden.startedAtTs == e.atTs && vorhanden.transportSequence == e.transportSequence
            return if (gleich) Result(state) else Result(state, Rejection.ATTEMPT_OUT_OF_ORDER)
        }
        // Nach einer BESTAETIGTEN Abgabe oder einer Uebernahme ist die Zeile
        // abgeschlossen. Nach einem NULLBEWEIS dagegen ist ein neuer Versuch
        // eine Tatsache, die den Nullbeweis widerlegt - sie wird gebucht und
        // hebt ihn auf (adversariale Runde, F4).
        if (r.terminal?.kind == TerminalKind.DELIVERY_CONFIRMED || r.accounting != null)
            return Result(state, Rejection.ATTEMPT_AFTER_TERMINAL)
        if (e.attempt != r.nextAttemptNumber) return Result(state, Rejection.ATTEMPT_OUT_OF_ORDER)
        // GEBUCHT WIRD AUCH DER UNAUTORISIERTE VERSUCH. Der Treiber wiederholt
        // von sich aus; diese Wiederholung ist eine Tatsache und gehoert ins
        // Journal - sie wird als BEFUND markiert, nicht verschwiegen.
        var befunde = r.findings
        if (r.mayHaveReachedPump) befunde = befunde + DeliveryFinding.ATTEMPT_AFTER_BOUNDARY
        val verfruehterNullbeweis = r.terminal?.kind == TerminalKind.PROVEN_NOT_SENT
        if (verfruehterNullbeweis) befunde = befunde + DeliveryFinding.WRITE_ACCEPTED_AFTER_PROVEN_NOT_SENT
        return put(
            state,
            r.copy(
                attempts = r.attempts + SendAttempt(e.attempt, e.atTs, e.transportSequence),
                terminal = if (verfruehterNullbeweis) null else r.terminal,
                findings = befunde,
            )
        )
    }

    /**
     * Der einzige ENTLASTENDE Fakt - und deshalb der einzige, der ausdruecklich
     * gemeldet werden muss.
     */
    private fun onWriteRefused(state: DeliveryJournalState, e: Event.WriteRefused): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        val a = r.attempts.firstOrNull { it.attempt == e.attempt } ?: return Result(state, Rejection.UNKNOWN_ATTEMPT)
        if (a.boundary == AmbiguityBoundary.REFUSED)
            return if (a.resolvedAtTs == e.atTs && a.resolvedSource == e.source) Result(state)
            else Result(state, Rejection.TERMINAL_CONFLICT)
        // Eine schon angenommene Quittung kann nicht nachtraeglich verweigert
        // werden - das waere die Entlastung nach der Belastung.
        if (a.boundary == AmbiguityBoundary.OS_WRITE_ACCEPTED) return Result(state, Rejection.TERMINAL_CONFLICT)
        return put(
            state,
            r.copy(attempts = r.attempts.map {
                if (it.attempt == e.attempt)
                    it.copy(boundary = AmbiguityBoundary.REFUSED, resolvedAtTs = e.atTs, resolvedSource = e.source)
                else it
            })
        )
    }

    /**
     * DER VERSPAETETE RUECKRUF (Codex-Gegenpruefung, P0).
     *
     * Der Schreibauftrag wird asynchron quittiert. Ein Vor-Send-Abbruch kann
     * deshalb GEBUCHT worden sein, waehrend die Quittung schon unterwegs war:
     *
     *     AttemptStarted -> ProvenNotSent -> verspaeteter WriteAccepted
     *
     * Die erste Fassung setzte hier zwar die Grenze, liess aber
     * `terminal = PROVEN_NOT_SENT` stehen - und weil der Terminalzustand im
     * `outcome` vor der Grenze kam, blieb die Zeile GESCHLOSSEN, obwohl
     * moeglicherweise Insulin geflossen ist.
     *
     * Jetzt hebt die Grenze den Nullbeweis AUF: er war verfrueht. Die Zeile
     * geht nach AMBIGUOUS zurueck, und der Widerspruch bleibt als Befund
     * stehen. Das ist die konservative Richtung - die einzige, die hier
     * zulaessig ist.
     */
    private fun onWriteAccepted(state: DeliveryJournalState, e: Event.WriteAccepted): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        val a = r.attempts.firstOrNull { it.attempt == e.attempt }
            // EIN BELASTENDER FAKT DARF NICHT MIT SEINER ABLEHNUNG VERSCHWINDEN
            // (adversariale Runde, F7): das Ereignis bleibt abgelehnt, aber die
            // Zeile traegt danach einen Befund - sonst ginge ein spaeterer
            // Nullbeweis anstandslos durch.
            ?: return Result(
                put(state, r.copy(findings = r.findings + DeliveryFinding.UNANCHORED_WRITE_ACCEPTED)).state,
                Rejection.UNKNOWN_ATTEMPT,
            )
        if (a.boundary == AmbiguityBoundary.OS_WRITE_ACCEPTED) {
            // Streng idempotent: derselbe Beweis ja, ein zweiter mit anderem
            // Zeitpunkt waere ein zweites Ereignis.
            return if (a.resolvedAtTs == e.atTs) Result(state) else Result(state, Rejection.TERMINAL_CONFLICT)
        }
        // BELASTEND SCHLAEGT ENTLASTEND, IMMER. Auch eine bereits gebuchte
        // Verweigerung wird von einer OS-Quittung ueberstimmt: dann war die
        // Verweigerung falsch. Sie abzulehnen wuerde den belastenden Fakt
        // verwerfen - die Richtung, die dieses Modul nie nehmen darf.
        val falscheVerweigerung = a.boundary == AmbiguityBoundary.REFUSED
        val verfruehterNullbeweis = r.terminal?.kind == TerminalKind.PROVEN_NOT_SENT
        var befunde = r.findings
        if (verfruehterNullbeweis || falscheVerweigerung)
            befunde = befunde + DeliveryFinding.WRITE_ACCEPTED_AFTER_PROVEN_NOT_SENT
        // NACHTRAG (adversariale Runde, F6): traegt die Zeile bereits einen
        // SPAETEREN Versuch, dann sind zwei Schreibauftraege im Spiel - und der
        // Befund darf nicht davon abhaengen, in welcher Reihenfolge die
        // Quittungen eintrudeln. Der verspaetete Rueckruf ist der Normalfall,
        // nicht die Ausnahme.
        if (r.attempts.any { it.attempt > e.attempt }) befunde = befunde + DeliveryFinding.ATTEMPT_AFTER_BOUNDARY
        return put(
            state,
            r.copy(
                attempts = r.attempts.map {
                    if (it.attempt == e.attempt)
                        it.copy(
                            boundary = AmbiguityBoundary.OS_WRITE_ACCEPTED, resolvedAtTs = e.atTs,
                            // Die Quelle gehoerte zur Verweigerung, die sich
                            // gerade als falsch erwiesen hat.
                            resolvedSource = null,
                        )
                    else it
                },
                // Ein bestaetigter Terminalzustand bleibt - eine Abgabe SETZT
                // einen Schreibauftrag voraus, da widerspricht nichts.
                terminal = if (verfruehterNullbeweis) null else r.terminal,
                findings = befunde,
            )
        )
    }

    /**
     * EINE PUMPEN-IDENTITAET IST DER STAERKSTE BELEG DES MODULS (adversariale
     * Runde, F1).
     *
     * Wer eine temporaryId oder pumpId traegt, hat die Pumpe erreicht - das
     * ist mehr, als eine OS-Quittung je beweist. Die erste Fassung schrieb
     * diese Fakten blind in die Zeile und liess einen bestehenden Nullbeweis
     * stehen: die Zeile trug danach eine Pumpen-Identitaet UND das Etikett
     * "beweisbar nichts abgegeben", war geschlossen und hinterliess keine
     * Spur. Der sauberste Weg in den verbotenen Zustand, ganz ohne Ablehnung.
     *
     * Jetzt hebt jeder Identitaetsfakt einen Nullbeweis auf, mit Befund.
     */
    private fun onTemporaryId(state: DeliveryJournalState, e: Event.TemporaryIdObserved): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        r.temporaryId?.let { return identitaetsKonflikt(state, r, it == e.temporaryId) }
        return put(state, mitIdentitaet(r).copy(temporaryId = e.temporaryId))
    }

    private fun onPumpId(state: DeliveryJournalState, e: Event.PumpIdObserved): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        r.pumpId?.let { return identitaetsKonflikt(state, r, it == e.pumpId) }
        // Die zweite Phase ist ein UPDATE derselben Lieferung, keine zweite -
        // die temporaryId bleibt stehen.
        return put(state, mitIdentitaet(r).copy(pumpId = e.pumpId))
    }

    /** Der Nullbeweis faellt, der Widerspruch bleibt sichtbar. */
    private fun mitIdentitaet(r: DeliveryRequest): DeliveryRequest =
        if (r.terminal?.kind == TerminalKind.PROVEN_NOT_SENT)
            r.copy(terminal = null, findings = r.findings + DeliveryFinding.IDENTITY_AFTER_PROVEN_NOT_SENT)
        else r

    /**
     * Zwei verschiedene Identitaeten fuer denselben Auftrag sind der
     * direkteste denkbare Hinweis auf ZWEI Lieferungen. Die Ablehnung allein
     * liesse ihn nur im fluechtigen Ergebnis stehen (F8) - er gehoert an die
     * Zeile.
     */
    private fun identitaetsKonflikt(state: DeliveryJournalState, r: DeliveryRequest, gleich: Boolean): Result =
        if (gleich) Result(state)
        else Result(
            put(state, r.copy(findings = r.findings + DeliveryFinding.IDENTITY_CONFLICT_SEEN)).state,
            Rejection.IDENTITY_CONFLICT,
        )

    /**
     * DIE ZENTRALE ABLEHNUNG. Ein Nullbeweis nach ueberschrittener Grenze ist
     * keine Information, sondern eine Behauptung - und die teuerste, die es
     * hier gibt: sie wuerde eine moeglicherweise abgegebene Menge aus der
     * Haftung nehmen.
     *
     * Ebenso darf er eine BESTAETIGTE Abgabe nie ueberschreiben. Die Rangfolge
     * ist asymmetrisch, und zwar in die konservative Richtung.
     */
    private fun onProvenNotSent(state: DeliveryJournalState, e: Event.ProvenNotSent): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        // Jeder Versuch, dessen Ausgang nicht ausdruecklich verweigert wurde,
        // kann die Pumpe erreicht haben - einschliesslich des frisch gebuchten,
        // noch unquittierten. Das war die Luecke F2.
        if (r.hasUnresolvedPossibleDelivery) return Result(state, Rejection.NOT_SENT_WHILE_ATTEMPT_UNRESOLVED)
        r.terminal?.let {
            if (it.kind != TerminalKind.PROVEN_NOT_SENT) return Result(state, Rejection.TERMINAL_CONFLICT)
            return if (it.atTs == e.atTs && it.source == e.source) Result(state)
            else Result(state, Rejection.TERMINAL_CONFLICT)
        }
        return put(state, r.copy(terminal = TerminalFact(TerminalKind.PROVEN_NOT_SENT, e.atTs, e.source)))
    }

    /**
     * BESTAETIGTE ABGABE SCHLAEGT IMMER DEN NULLBEWEIS (Codex-Gegenpruefung,
     * P0). Ein frueherer "nicht gesendet" kann falsch gewesen sein - die
     * Pumpenhistorie kann Insulin nachweisen, das ein Vor-Send-Abbruch fuer
     * unmoeglich hielt. Dann gewinnt die Abgabe, weil nur diese Richtung
     * konservativ ist.
     *
     * Der Widerspruch wird NICHT geglaettet: er bleibt als
     * [DeliveryFinding.CONFIRMED_AFTER_PROVEN_NOT_SENT] stehen und sperrt die
     * Zeile, bis ihn jemand ausdruecklich aufloest.
     */
    private fun onConfirmed(state: DeliveryJournalState, e: Event.DeliveryConfirmed): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        val t = r.terminal
        if (t?.kind == TerminalKind.DELIVERY_CONFIRMED) {
            return if (t.atTs == e.atTs && t.source == e.source) Result(state)
            else Result(state, Rejection.TERMINAL_CONFLICT)
        }
        val befunde =
            if (t?.kind == TerminalKind.PROVEN_NOT_SENT) r.findings + DeliveryFinding.CONFIRMED_AFTER_PROVEN_NOT_SENT
            else r.findings
        return put(
            state,
            r.copy(terminal = TerminalFact(TerminalKind.DELIVERY_CONFIRMED, e.atTs, e.source), findings = befunde)
        )
    }

    private fun onAccounted(state: DeliveryJournalState, e: Event.LiabilityAccounted): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        if (r.terminal?.kind != TerminalKind.DELIVERY_CONFIRMED)
            return Result(state, Rejection.ACCOUNTED_WITHOUT_DELIVERY)
        // Streng wie bei den Terminalfakten: dieselbe Uebernahme ja, eine
        // zweite mit anderem Zeitpunkt ODER anderer Quelle ist ein zweites
        // Ereignis - und eine Uebernahme ist der Punkt, an dem eine Menge die
        // Zustaendigkeit wechselt.
        r.accounting?.let {
            return if (it.atTs == e.atTs && it.source == e.source) Result(state)
            else Result(state, Rejection.TERMINAL_CONFLICT)
        }
        return put(state, r.copy(accounting = AccountingFact(e.atTs, e.source)))
    }

    private fun put(state: DeliveryJournalState, r: DeliveryRequest) =
        Result(state.copy(requests = state.requests + (r.requestId to r), revision = state.revision + 1))

    // ---- die Frage, die vor jedem Send steht -----------------------------

    /**
     * Darf fuer diesen Auftrag JETZT ein Sendeversuch beginnen?
     *
     * `null` = ja. Sonst der Grund, und der ist immer eine Sperre, nie ein
     * Hinweis.
     *
     * DIE HAUPTINVARIANTE GILT AUCH FUER DEN EIGENEN AUFTRAG (Codex-
     * Gegenpruefung, P0). Der erste Entwurf liess den eigenen Retry trotz
     * ueberschrittener Grenze zu - mit der Begruendung, der Treiber muesse
     * seine Wiederholung buchen koennen. Das verwechselte BUCHEN mit ERLAUBEN:
     * die Wiederholung wird weiterhin gebucht (s. [onAttempt]), aber sie wird
     * hier nie autorisiert. Beim Medtrum kann ein Retry nach einem
     * `onCharacteristicWrite`-Fehler laufen, obwohl `writeCharacteristic()`
     * zuvor `true` geliefert hat; der neue Versuch bekommt eine neue
     * BLE-Sequenz, und der Bolus-Payload traegt keine idempotente Kennung.
     * Eine Doppelabgabe ist damit nicht ausgeschlossen.
     */
    fun mayAttempt(state: DeliveryJournalState, requestId: String): Denial? {
        val r = state.requests[requestId] ?: return Denial.NOT_JOURNALED
        // Ohne gepinnte Pumpen-Epoch weiss niemand, WOHIN gesendet wird.
        if (r.pumpEpoch.isNullOrBlank()) return Denial.NO_PUMP_EPOCH
        if (r.terminal != null) return Denial.ALREADY_TERMINAL
        // Ein offener Widerspruch ist keine Grundlage fuer eine Abgabe.
        if (r.findings.isNotEmpty()) return Denial.UNRESOLVED_FINDING
        // Der EIGENE unbekannte Ausgang sperrt - Hauptinvariante. Das gilt
        // auch fuer einen gebuchten, noch unquittierten Versuch: die Buchung
        // steht unmittelbar vor dem Schreibaufruf (F2).
        if (r.hasUnresolvedPossibleDelivery) return Denial.SELF_AMBIGUOUS
        // Und ein FREMDER ebenso: solange irgendwo Insulin unterwegs sein
        // koennte, wird nichts Neues hinterhergeschickt. Weil das aus dem
        // durablen Zustand faellt, ueberlebt es Prozessende und neuen Worker.
        // Fremdsperre auf DEMSELBEN Praedikat (F3): eine Zeile mit gebuchtem,
        // unquittiertem Versuch war vorher nur ATTEMPTED und sperrte nicht -
        // obwohl fuer sie etwas unterwegs sein kann. Verglichen wird ueber den
        // Map-SCHLUESSEL, nicht ueber das Feld (F9).
        // DASSELBE Praedikat wie bei der Eigensperre. Vorher stand hier
        // `mayHaveReachedPump`, und damit sperrte eine unverankerte
        // Schreibquittung zwar ihren eigenen Auftrag, aber keinen neuen -
        // obwohl ein Hinweis auf einen nicht zugeordneten Schreibauftrag
        // GLOBAL wirken muss (Codex-Gegenpruefung).
        if (state.requests.any { (key, other) ->
                key != requestId && other.open && other.hasUnresolvedPossibleDelivery
            }
        ) return Denial.OTHER_REQUEST_AMBIGUOUS
        return null
    }

    enum class Denial {

        /** Der Auftrag steht nicht durabel im Journal - dann darf er nicht
         *  gesendet werden, egal wie gut die Absicht ist. */
        NOT_JOURNALED,
        NO_PUMP_EPOCH,
        ALREADY_TERMINAL,
        UNRESOLVED_FINDING,

        /** Der eigene Auftrag hat die Grenze schon ueberschritten. */
        SELF_AMBIGUOUS,
        OTHER_REQUEST_AMBIGUOUS,
    }
}

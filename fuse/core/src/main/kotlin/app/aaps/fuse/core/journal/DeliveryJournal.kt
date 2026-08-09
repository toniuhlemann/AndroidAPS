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

        /** Erste Phase der Identitaet. */
        data class TemporaryIdObserved(val requestId: String, val temporaryId: Long) : Event

        /** Zweite Phase - ein UPDATE derselben Lieferung. */
        data class PumpIdObserved(val requestId: String, val pumpId: Long) : Event

        /** Beweisbar nichts abgegeben. Nur gueltig, solange KEIN Versuch die
         *  Grenze ueberschritten hat. */
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

        /** DER wichtigste: "nicht gesendet" nach ueberschrittener Grenze. */
        NOT_SENT_AFTER_BOUNDARY,

        /** Ein Terminalfakt widerspricht einem bereits gesetzten anderen. */
        TERMINAL_CONFLICT,

        /** Eine Identitaet widerspricht der bereits gebundenen. */
        IDENTITY_CONFLICT,

        /** Uebernahme ohne nachgewiesene Abgabe. */
        ACCOUNTED_WITHOUT_DELIVERY,
    }

    data class Result(val state: DeliveryJournalState, val rejection: Rejection? = null) {

        val applied: Boolean get() = rejection == null
    }

    fun reduceAll(state: DeliveryJournalState, events: List<Event>): Result =
        events.fold(Result(state)) { acc, e -> if (acc.applied) reduce(acc.state, e) else acc }

    fun reduce(state: DeliveryJournalState, event: Event): Result = when (event) {
        is Event.Created             -> onCreated(state, event)
        is Event.SendAttemptStarted  -> onAttempt(state, event)
        is Event.WriteAccepted       -> onWriteAccepted(state, event)
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
        // Nach einem Terminalzustand ist ein neuer Versuch ein Widerspruch -
        // die Zeile ist abgeschlossen.
        if (r.terminal != null) return Result(state, Rejection.ATTEMPT_AFTER_TERMINAL)
        if (e.attempt != r.nextAttemptNumber) return Result(state, Rejection.ATTEMPT_OUT_OF_ORDER)
        // GEBUCHT WIRD AUCH DER UNAUTORISIERTE VERSUCH. Der Treiber wiederholt
        // von sich aus; diese Wiederholung ist eine Tatsache und gehoert ins
        // Journal - sie wird als BEFUND markiert, nicht verschwiegen.
        val befunde =
            if (r.boundaryCrossed) r.findings + DeliveryFinding.ATTEMPT_AFTER_BOUNDARY else r.findings
        return put(
            state,
            r.copy(
                attempts = r.attempts + SendAttempt(e.attempt, e.atTs, e.transportSequence),
                findings = befunde,
            )
        )
    }

    private fun onWriteAccepted(state: DeliveryJournalState, e: Event.WriteAccepted): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        val a = r.attempts.firstOrNull { it.attempt == e.attempt } ?: return Result(state, Rejection.UNKNOWN_ATTEMPT)
        if (a.boundary == AmbiguityBoundary.OS_WRITE_ACCEPTED) {
            // Streng idempotent: derselbe Beweis ja, ein zweiter mit anderem
            // Zeitpunkt waere ein zweites Ereignis.
            return if (a.acceptedAtTs == e.atTs) Result(state) else Result(state, Rejection.TERMINAL_CONFLICT)
        }
        return put(
            state,
            r.copy(attempts = r.attempts.map {
                if (it.attempt == e.attempt)
                    it.copy(boundary = AmbiguityBoundary.OS_WRITE_ACCEPTED, acceptedAtTs = e.atTs)
                else it
            })
        )
    }

    private fun onTemporaryId(state: DeliveryJournalState, e: Event.TemporaryIdObserved): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        r.temporaryId?.let { return if (it == e.temporaryId) Result(state) else Result(state, Rejection.IDENTITY_CONFLICT) }
        return put(state, r.copy(temporaryId = e.temporaryId))
    }

    private fun onPumpId(state: DeliveryJournalState, e: Event.PumpIdObserved): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        r.pumpId?.let { return if (it == e.pumpId) Result(state) else Result(state, Rejection.IDENTITY_CONFLICT) }
        // Die zweite Phase ist ein UPDATE derselben Lieferung, keine zweite -
        // die temporaryId bleibt stehen.
        return put(state, r.copy(pumpId = e.pumpId))
    }

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
        if (r.boundaryCrossed) return Result(state, Rejection.NOT_SENT_AFTER_BOUNDARY)
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
        r.accountedAtTs?.let { return if (it == e.atTs) Result(state) else Result(state, Rejection.TERMINAL_CONFLICT) }
        return put(state, r.copy(accountedAtTs = e.atTs))
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
        // Der EIGENE unbekannte Ausgang sperrt - Hauptinvariante.
        if (r.boundaryCrossed) return Denial.SELF_AMBIGUOUS
        // Und ein FREMDER ebenso: solange irgendwo Insulin unterwegs sein
        // koennte, wird nichts Neues hinterhergeschickt. Weil das aus dem
        // durablen Zustand faellt, ueberlebt es Prozessende und neuen Worker.
        if (state.ambiguous.any { it.requestId != requestId }) return Denial.OTHER_REQUEST_AMBIGUOUS
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

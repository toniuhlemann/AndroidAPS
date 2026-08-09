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
 * Ereignisse, die einer bestehenden Tatsache WIDERSPRECHEN, werden nicht still
 * verworfen und nicht still angewandt - sie sind ein [Rejection]. Der Aufrufer
 * entscheidet, was er damit tut; das Journal erfindet nichts.
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
    }

    /** Warum ein Ereignis NICHT angewandt wurde. */
    enum class Rejection {

        UNKNOWN_REQUEST,

        /** Ein zweiter Auftrag unter derselben Id mit anderem Inhalt. */
        DUPLICATE_REQUEST_CONFLICT,

        /** Versuchsnummern muessen lueckenlos ab 1 wachsen - eine Luecke hiesse,
         *  dass ein Versuch nicht gebucht wurde. */
        ATTEMPT_OUT_OF_ORDER,

        /** Eine Grenzueberschreitung fuer einen Versuch, den es nicht gibt. */
        UNKNOWN_ATTEMPT,

        /** DER wichtigste: "nicht gesendet" nach ueberschrittener Grenze. */
        NOT_SENT_AFTER_BOUNDARY,

        /** Ein Terminalfakt widerspricht einem bereits gesetzten anderen. */
        TERMINAL_CONFLICT,

        /** Eine Identitaet widerspricht der bereits gebundenen. */
        IDENTITY_CONFLICT,
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
    }

    // ---- einzelne Ereignisse ---------------------------------------------

    private fun onCreated(state: DeliveryJournalState, e: Event.Created): Result {
        val vorhanden = state.requests[e.requestId]
        if (vorhanden != null) {
            // Idempotenz: derselbe Auftrag nochmal ist kein Fehler.
            val gleich = vorhanden.proposalId == e.proposalId &&
                vorhanden.requestedU == e.requestedU &&
                vorhanden.createdAtTs == e.atTs &&
                vorhanden.pumpEpoch == e.pumpEpoch
            return if (gleich) Result(state) else Result(state, Rejection.DUPLICATE_REQUEST_CONFLICT)
        }
        return put(
            state,
            DeliveryRequest(
                requestId = e.requestId, proposalId = e.proposalId, requestedU = e.requestedU,
                createdAtTs = e.atTs, pumpEpoch = e.pumpEpoch,
            )
        )
    }

    private fun onAttempt(state: DeliveryJournalState, e: Event.SendAttemptStarted): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        r.attempts.firstOrNull { it.attempt == e.attempt }?.let { vorhanden ->
            // Idempotenz: derselbe Versuch nochmal gebucht.
            val gleich = vorhanden.startedAtTs == e.atTs && vorhanden.transportSequence == e.transportSequence
            return if (gleich) Result(state) else Result(state, Rejection.ATTEMPT_OUT_OF_ORDER)
        }
        // Lueckenlos ab 1: eine Luecke hiesse, dass ein Versuch stattfand, ohne
        // gebucht zu sein - genau der Zustand, den das Journal ausschliessen soll.
        if (e.attempt != r.nextAttemptNumber) return Result(state, Rejection.ATTEMPT_OUT_OF_ORDER)
        return put(
            state,
            r.copy(attempts = r.attempts + SendAttempt(e.attempt, e.atTs, e.transportSequence))
        )
    }

    private fun onWriteAccepted(state: DeliveryJournalState, e: Event.WriteAccepted): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        val a = r.attempts.firstOrNull { it.attempt == e.attempt } ?: return Result(state, Rejection.UNKNOWN_ATTEMPT)
        if (a.boundary == AmbiguityBoundary.OS_WRITE_ACCEPTED) return Result(state)   // idempotent
        return put(
            state,
            r.copy(attempts = r.attempts.map {
                if (it.attempt == e.attempt) it.copy(boundary = AmbiguityBoundary.OS_WRITE_ACCEPTED) else it
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
     */
    private fun onProvenNotSent(state: DeliveryJournalState, e: Event.ProvenNotSent): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        if (r.boundaryCrossed) return Result(state, Rejection.NOT_SENT_AFTER_BOUNDARY)
        r.terminal?.let {
            return if (it.kind == TerminalKind.PROVEN_NOT_SENT) Result(state)
            else Result(state, Rejection.TERMINAL_CONFLICT)
        }
        return put(state, r.copy(terminal = TerminalFact(TerminalKind.PROVEN_NOT_SENT, e.atTs, e.source)))
    }

    private fun onConfirmed(state: DeliveryJournalState, e: Event.DeliveryConfirmed): Result {
        val r = state.requests[e.requestId] ?: return Result(state, Rejection.UNKNOWN_REQUEST)
        r.terminal?.let {
            return if (it.kind == TerminalKind.DELIVERY_CONFIRMED) Result(state)
            else Result(state, Rejection.TERMINAL_CONFLICT)
        }
        return put(state, r.copy(terminal = TerminalFact(TerminalKind.DELIVERY_CONFIRMED, e.atTs, e.source)))
    }

    private fun put(state: DeliveryJournalState, r: DeliveryRequest) =
        Result(state.copy(requests = state.requests + (r.requestId to r), revision = state.revision + 1))

    // ---- die Frage, die vor jedem Send steht -----------------------------

    /**
     * Darf fuer diesen Auftrag JETZT ein Sendeversuch beginnen?
     *
     * `null` = ja. Sonst der Grund, und der ist immer eine Sperre, nie ein
     * Hinweis. Die Regel ist bewusst eng: sie fragt nur nach dem, was VOR dem
     * Senden durabel bekannt sein MUSS.
     */
    fun mayAttempt(state: DeliveryJournalState, requestId: String): Denial? {
        val r = state.requests[requestId] ?: return Denial.NOT_JOURNALED
        // Ohne gepinnte Pumpen-Epoch weiss niemand, WOHIN gesendet wird.
        if (r.pumpEpoch == null) return Denial.NO_PUMP_EPOCH
        if (r.terminal != null) return Denial.ALREADY_TERMINAL
        // Ein ANDERER Auftrag mit unbekanntem Ausgang blockiert: solange
        // irgendwo Insulin unterwegs sein koennte, wird nichts Neues
        // hinterhergeschickt (persistenter Ambiguity-Hold).
        if (state.ambiguous.any { it.requestId != requestId }) return Denial.OTHER_REQUEST_AMBIGUOUS
        return null
    }

    enum class Denial {

        /** Der Auftrag steht nicht durabel im Journal - dann darf er nicht
         *  gesendet werden, egal wie gut die Absicht ist. */
        NOT_JOURNALED,
        NO_PUMP_EPOCH,
        ALREADY_TERMINAL,
        OTHER_REQUEST_AMBIGUOUS,
    }
}

package app.aaps.fuse.core.journal

/**
 * DAS LIEFERJOURNAL (B1.1) - was ueber einen Sendeauftrag DURABEL bekannt ist.
 *
 * WOZU ES DA IST, in einem Satz: der Ledger weiss, welche MENGE offen ist; das
 * Journal weiss, ob ein konkreter Sendeversuch stattgefunden haben KANN. Das
 * sind zwei verschiedene Fragen, und die zweite ist die, an der jede
 * Wiederaufnahme nach einem Prozessende haengt.
 *
 * WARUM NICHT DIE BESTEHENDE LEDGER-ZEILE. Der Ledger fuehrt Buch ueber
 * Vorschlaege und ihre Mengen. Ein Vorschlag kann aber MEHRERE physische
 * Sendeversuche nach sich ziehen - der Medtrum-Treiber wiederholt ein
 * fehlgeschlagenes Paket bis zu dreimal, jeweils mit neuer BLE-Sequenz und
 * ohne idempotente Kennung im Nutzdatenteil. Ein Feld je Vorschlag koennte
 * das nicht abbilden, und genau diese Wiederholungen sind der gefaehrlichste
 * Teil: sie umgehen jede Pruefung, die nur am ersten Sendeaufruf haengt.
 *
 * DIE EINE INVARIANTE, die dieses Modul traegt:
 *
 *     Sobald EIN Versuch die Ambiguitaetsgrenze ueberschritten hat, kann der
 *     Auftrag NIE MEHR als "nicht gesendet" gelten - auch nicht, wenn ein
 *     spaeterer Versuch nachweislich scheitert, auch nicht nach einem
 *     Prozessende, auch nicht nach Zeitablauf.
 *
 * Die Umkehrung ist erlaubt und noetig: solange KEIN Versuch die Grenze
 * erreicht hat, ist ein Abbruch ein echter Nullbeweis - dann darf die Haftung
 * fallen, sonst staute sich mit jedem abgelehnten Kommando unaufloesbare
 * Schuld an.
 *
 * REIN: keine AAPS-Abhaengigkeit, keine Pumpentypen, kein Android. Dieselbe
 * Regel muss auf der JVM replaybar sein.
 */

/**
 * Wo ein Sendeversuch steht - die einzige Achse, auf der "nicht gesendet"
 * ueberhaupt beweisbar ist.
 *
 * Der Name ist mit Absicht nicht "gesendet"/"nicht gesendet":
 * [OS_WRITE_ACCEPTED] heisst, dass das Betriebssystem den Schreibauftrag
 * angenommen hat. Das beweist WEDER den Empfang durch die Pumpe NOCH eine
 * Insulinabgabe - es beweist nur, dass die Software ab hier nicht mehr
 * behaupten kann, es sei nichts geflossen (Codex-Gegenpruefung F9).
 */
enum class AmbiguityBoundary {

    /** Der Versuch wurde vorbereitet, aber der Schreibauftrag ist NICHT
     *  abgesetzt worden - z.B. weil ein Gate davor abgelehnt hat. Nur in
     *  diesem Zustand ist "nicht gesendet" beweisbar. */
    NOT_REACHED,

    /** Der Schreibauftrag wurde abgesetzt und vom Betriebssystem angenommen.
     *  Ab hier ist der Ausgang UNBEKANNT und bleibt es, bis ein Fakt von der
     *  Pumpe ihn aufloest. */
    OS_WRITE_ACCEPTED,
}

/**
 * EIN physischer Sendeversuch.
 *
 * [attempt] zaehlt ab 1 und deckt ausdruecklich auch die automatischen
 * Wiederholungen des Treibers ab - jede von ihnen ist ein eigener Versuch mit
 * eigener Transportsequenz, und jede muss vor ihrer Ausfuehrung durabel
 * gebucht sein.
 */
data class SendAttempt(
    val attempt: Int,
    val startedAtTs: Long,
    /** Die Transportsequenz des Versuchs, soweit der Treiber sie kennt.
     *  `null` = nicht ermittelbar - das ist eine Luecke in der Beweiskette
     *  und kein Grund, den Versuch nicht zu buchen. */
    val transportSequence: Long? = null,
    val boundary: AmbiguityBoundary = AmbiguityBoundary.NOT_REACHED,
) {

    init {
        require(attempt >= 1) { "attempt counts from 1, was $attempt" }
        require(startedAtTs > 0L) { "startedAtTs must be a real timestamp" }
    }
}

/**
 * Wie ein Auftrag ENDET - und zwar nur dort, wo das Ende beweisbar ist.
 *
 * Es gibt bewusst keinen Zustand "fehlgeschlagen": ein Fehler nach einem
 * moeglichen Send ist kein Ende, sondern Unwissen. Er fuehrt zu
 * [DeliveryOutcome.AMBIGUOUS] und bleibt dort, bis ein Fakt ihn aufloest.
 */
enum class TerminalKind {

    /** Vor JEDEM Schreibauftrag abgelehnt - der einzige echte Nullbeweis. */
    PROVEN_NOT_SENT,

    /** Die Pumpe hat den Auftrag bestaetigt bzw. ein Behandlungsfakt hat ihn
     *  nachgewiesen. */
    DELIVERY_CONFIRMED,
}

data class TerminalFact(
    val kind: TerminalKind,
    val atTs: Long,
    /** Woher der Beweis stammt - Freitext fuer den Trail, nie fuer eine
     *  Fallunterscheidung. */
    val source: String,
)

/** Die abgeleitete Gesamtlage eines Auftrags. Nie gespeichert, immer gerechnet
 *  - ein gespeicherter Zustand koennte von seinen Fakten abweichen. */
enum class DeliveryOutcome {

    /** Angelegt, noch kein Versuch. */
    CREATED,

    /** Mindestens ein Versuch vorbereitet, keiner hat die Grenze erreicht. */
    ATTEMPTED,

    /** Mindestens ein Versuch hat die Grenze ueberschritten und nichts hat ihn
     *  aufgeloest. HAFTET. */
    AMBIGUOUS,

    /** Beweisbar nichts abgegeben. Haftet NICHT. */
    PROVEN_NOT_SENT,

    /** Abgabe nachgewiesen. Haftet, bis der IOB-Abgleich sie uebernimmt. */
    CONFIRMED,
}

/**
 * Die durable Zeile eines Sendeauftrags.
 *
 * [pumpEpoch] wird beim ANLEGEN gepinnt und nie nachgetragen. `null` heisst
 * "war nicht ermittelbar" - und ein Auftrag ohne Epoch darf nicht gesendet
 * werden (s. [DeliveryJournal.mayAttempt]). Ein spaeteres Nachtragen waere
 * genau die Etikettierung mit der DANN aktuellen Epoch, die
 * [[medtrum-lifecycle-messdaten]] verbietet.
 */
data class DeliveryRequest(
    val requestId: String,
    val proposalId: String,
    val requestedU: Double,
    val createdAtTs: Long,
    val pumpEpoch: String? = null,
    val attempts: List<SendAttempt> = emptyList(),
    /** Erste Phase der zweiphasigen Medtrum-Identitaet. */
    val temporaryId: Long? = null,
    /** Zweite Phase - ein UPDATE derselben Lieferung, keine zweite. */
    val pumpId: Long? = null,
    val terminal: TerminalFact? = null,
) {

    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(proposalId.isNotBlank()) { "proposalId must not be blank" }
        require(requestedU.isFinite() && requestedU > 0.0) { "requestedU=$requestedU" }
        val nummern = attempts.map { it.attempt }
        require(nummern == nummern.sorted() && nummern.distinct() == nummern) {
            "attempts must be strictly increasing and unique, was $nummern"
        }
    }

    /** Hat IRGENDEIN Versuch die Ambiguitaetsgrenze ueberschritten? Die
     *  tragende Frage des ganzen Moduls. */
    val boundaryCrossed: Boolean
        get() = attempts.any { it.boundary == AmbiguityBoundary.OS_WRITE_ACCEPTED }

    val nextAttemptNumber: Int get() = (attempts.maxOfOrNull { it.attempt } ?: 0) + 1

    val outcome: DeliveryOutcome
        get() = when {
            terminal?.kind == TerminalKind.DELIVERY_CONFIRMED -> DeliveryOutcome.CONFIRMED
            terminal?.kind == TerminalKind.PROVEN_NOT_SENT    -> DeliveryOutcome.PROVEN_NOT_SENT
            boundaryCrossed                                   -> DeliveryOutcome.AMBIGUOUS
            attempts.isNotEmpty()                             -> DeliveryOutcome.ATTEMPTED
            else                                              -> DeliveryOutcome.CREATED
        }

    /**
     * Haftet dieser Auftrag noch?
     *
     * Alles ausser dem bewiesenen Nullfall haftet - einschliesslich CREATED und
     * ATTEMPTED. Ein angelegter, aber noch nicht ausgefuehrter Auftrag ist eine
     * BEABSICHTIGTE Menge; sie faellt erst, wenn ein Abbruch sie beweisbar
     * beendet, nicht schon dadurch, dass noch nichts passiert ist.
     */
    val liable: Boolean get() = outcome != DeliveryOutcome.PROVEN_NOT_SENT
}

/** Der durable Gesamtzustand: Auftraege nach requestId. */
data class DeliveryJournalState(
    val requests: Map<String, DeliveryRequest> = emptyMap(),
    /** Monoton je Aenderung - dieselbe Rolle wie die Ledger-Revision. */
    val revision: Long = 0L,
) {

    /** Alle Auftraege, die noch haften. Das ist die Zahl, die eine neue Dosis
     *  gegen sich gelten lassen muss. */
    val liableU: Double get() = requests.values.filter { it.liable }.sumOf { it.requestedU }

    /** Gibt es einen Auftrag mit unbekanntem Ausgang? Solange ja, darf nichts
     *  Neues gesendet werden (der persistente Ambiguity-Hold aus B1.6). */
    val ambiguous: List<DeliveryRequest> get() = requests.values.filter { it.outcome == DeliveryOutcome.AMBIGUOUS }
}

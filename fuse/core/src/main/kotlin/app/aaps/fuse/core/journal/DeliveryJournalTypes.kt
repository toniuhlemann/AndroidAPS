package app.aaps.fuse.core.journal

/**
 * DAS LIEFERJOURNAL (B1.1) - was ueber einen Sendeauftrag DURABEL bekannt ist.
 *
 * WOZU ES DA IST, in einem Satz: der Ledger weiss, welche MENGE offen ist; das
 * Journal weiss, ob ein konkreter Sendeversuch stattgefunden haben KANN. Das
 * sind zwei verschiedene Fragen, und die zweite ist die, an der jede
 * Wiederaufnahme nach einem Prozessende haengt.
 *
 * ==========================================================================
 * DER ZUSTAENDIGKEITSVERTRAG - hier steht er einmal, und er ist bindend
 * ==========================================================================
 *
 *   Der bestehende FUSE-Ledger ist die EINZIGE Quelle der Insulinmenge.
 *   Das Journal liefert Zustands- und Transportbeweise, KEINE Menge.
 *   Die beiden Betraege duerfen NIEMALS addiert werden.
 *
 * Deshalb gibt es in diesem Modul bewusst KEINE Summe ueber [DeliveryRequest.requestedU].
 * Eine solche Summe waere die Einladung, sie neben die Ledger-Haftung zu
 * stellen - und dieselbe Menge zweimal zu belegen. `requestedU` steht in der
 * Zeile, damit ein Trail-Eintrag ohne Ledger lesbar bleibt; als Rechengroesse
 * ist er hier tabu.
 *
 * Was das Journal STATTDESSEN beantwortet: [DeliveryJournalState.ambiguous] -
 * kann gerade etwas unterwegs sein? Das ist eine Ja/Nein-Frage, keine Menge,
 * und genau die braucht der Ambiguity-Hold.
 *
 * ==========================================================================
 *
 * WARUM NICHT DIE BESTEHENDE LEDGER-ZEILE. Ein Vorschlag kann MEHRERE
 * physische Sendeversuche nach sich ziehen - der Medtrum-Treiber wiederholt
 * ein fehlgeschlagenes Paket bis zu dreimal, jeweils mit neuer BLE-Sequenz und
 * ohne idempotente Kennung im Nutzdatenteil. Ein Feld je Vorschlag koennte das
 * nicht abbilden, und genau diese Wiederholungen sind der gefaehrlichste Teil:
 * sie umgehen jede Pruefung, die nur am ersten Sendeaufruf haengt.
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
 * eigener Transportsequenz.
 */
data class SendAttempt(
    val attempt: Int,
    val startedAtTs: Long,
    /** Die Transportsequenz des Versuchs, soweit der Treiber sie kennt.
     *  `null` = nicht ermittelbar - das ist eine Luecke in der Beweiskette
     *  und kein Grund, den Versuch nicht zu buchen. */
    val transportSequence: Long? = null,
    val boundary: AmbiguityBoundary = AmbiguityBoundary.NOT_REACHED,
    /** WANN die Grenze ueberschritten wurde. Ohne diesen Zeitstempel liesse
     *  sich beim Replay nicht pruefen, ob zwei Beweise dasselbe Ereignis
     *  meinen oder zwei verschiedene. */
    val acceptedAtTs: Long? = null,
) {

    init {
        require(attempt >= 1) { "attempt counts from 1, was $attempt" }
        require(startedAtTs > 0L) { "startedAtTs must be a real timestamp" }
        // Grenze und Zeitstempel muessen sich einig sein - eine Ueberschreitung
        // ohne Zeitpunkt waere ein halber Beweis, ein Zeitpunkt ohne
        // Ueberschreitung eine Behauptung ueber ein Ereignis, das nicht
        // verbucht ist.
        require((boundary == AmbiguityBoundary.OS_WRITE_ACCEPTED) == (acceptedAtTs != null)) {
            "boundary=$boundary and acceptedAtTs=$acceptedAtTs disagree"
        }
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

/**
 * Ein WIDERSPRUCH in der Beweislage, der nicht verschwinden darf.
 *
 * Ein Befund loest den Zustand nicht auf - er haelt fest, dass die Fakten
 * einander widersprochen haben. Solange einer offen ist, wird fuer diese
 * Zeile nichts mehr autorisiert; die Aufloesung gehoert an einen
 * Reparatur-Workflow und nicht in eine stille Regel.
 */
enum class DeliveryFinding {

    /** Erst "nicht gesendet" bewiesen, dann doch eine Abgabe nachgewiesen.
     *  Die Abgabe gewinnt (konservativ), der Widerspruch bleibt sichtbar. */
    CONFIRMED_AFTER_PROVEN_NOT_SENT,

    /** Ein Sendeversuch wurde NACH ueberschrittener Grenze gebucht - der
     *  Treiber hat also wiederholt, obwohl schon etwas unterwegs sein konnte.
     *  Die Buchung ist richtig (es ist eine Tatsache), die Lage ist es nicht. */
    ATTEMPT_AFTER_BOUNDARY,
}

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

    /** Abgabe nachgewiesen, aber vom Ledger noch nicht uebernommen. */
    CONFIRMED,

    /** Die Menge ist im Ledger/IOB angekommen - das Journal ist fertig damit.
     *  ERST hier endet die Zustaendigkeit, und erst hier faellt die Sperre. */
    ACCOUNTED,
}

/**
 * Die durable Zeile eines Sendeauftrags.
 *
 * [pumpEpoch] wird beim ANLEGEN gepinnt und nie nachgetragen. `null` oder leer
 * heisst "war nicht ermittelbar" - und ein Auftrag ohne Epoch darf nicht
 * gesendet werden (s. [DeliveryJournal.mayAttempt]). Ein spaeteres Nachtragen
 * waere genau die Etikettierung mit der DANN aktuellen Epoch, die ein
 * Treatment nie erfahren darf.
 */
data class DeliveryRequest(
    val requestId: String,
    val proposalId: String,
    /** NUR fuer den Trail. Als Rechengroesse tabu - s. Zustaendigkeitsvertrag
     *  im Kopf dieser Datei. */
    val requestedU: Double,
    val createdAtTs: Long,
    val pumpEpoch: String? = null,
    val attempts: List<SendAttempt> = emptyList(),
    /** Erste Phase der zweiphasigen Medtrum-Identitaet. */
    val temporaryId: Long? = null,
    /** Zweite Phase - ein UPDATE derselben Lieferung, keine zweite. */
    val pumpId: Long? = null,
    val terminal: TerminalFact? = null,
    val accountedAtTs: Long? = null,
    val findings: Set<DeliveryFinding> = emptySet(),
) {

    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(proposalId.isNotBlank()) { "proposalId must not be blank" }
        require(requestedU.isFinite() && requestedU > 0.0) { "requestedU=$requestedU" }
        // Leer ist keine Epoch. Wer sie so speichert, haette eine Identitaet
        // behauptet, die keine ist - dieselbe Fehlerklasse wie Sha("").
        require(pumpEpoch == null || pumpEpoch.isNotBlank()) { "pumpEpoch must be null or non-blank" }
        // GENAU 1..n, nicht nur aufsteigend: eine Zeile, die mit Versuch 2
        // beginnt, behauptet, Versuch 1 habe es nie gegeben. Nach einer
        // Deserialisierung ist das der Unterschied zwischen "wir kennen alle
        // Versuche" und "wir kennen die, die uebrig blieben".
        attempts.forEachIndexed { i, a ->
            require(a.attempt == i + 1) { "attempts must be exactly 1..n, was ${attempts.map { it.attempt }}" }
        }
        require(accountedAtTs == null || terminal?.kind == TerminalKind.DELIVERY_CONFIRMED) {
            "only a confirmed delivery can be accounted"
        }
    }

    /** Hat IRGENDEIN Versuch die Ambiguitaetsgrenze ueberschritten? Die
     *  tragende Frage des ganzen Moduls. */
    val boundaryCrossed: Boolean
        get() = attempts.any { it.boundary == AmbiguityBoundary.OS_WRITE_ACCEPTED }

    val nextAttemptNumber: Int get() = attempts.size + 1

    val outcome: DeliveryOutcome
        get() = when {
            accountedAtTs != null                             -> DeliveryOutcome.ACCOUNTED
            terminal?.kind == TerminalKind.DELIVERY_CONFIRMED -> DeliveryOutcome.CONFIRMED
            terminal?.kind == TerminalKind.PROVEN_NOT_SENT    -> DeliveryOutcome.PROVEN_NOT_SENT
            boundaryCrossed                                   -> DeliveryOutcome.AMBIGUOUS
            attempts.isNotEmpty()                             -> DeliveryOutcome.ATTEMPTED
            else                                              -> DeliveryOutcome.CREATED
        }

    /**
     * Ist der Zustand dieser Zeile noch OFFEN?
     *
     * BEWUSST KEINE MENGE. Die Menge fuehrt der Ledger; hier steht nur, ob das
     * Journal die Zeile noch als unerledigt fuehrt. Offen sind alle Zustaende
     * ausser dem bewiesenen Nullfall und der abgeschlossenen Uebernahme.
     */
    val open: Boolean
        get() = outcome != DeliveryOutcome.PROVEN_NOT_SENT && outcome != DeliveryOutcome.ACCOUNTED
}

/** Der durable Gesamtzustand: Auftraege nach requestId. */
data class DeliveryJournalState(
    val requests: Map<String, DeliveryRequest> = emptyMap(),
    /** Monoton je Aenderung - dieselbe Rolle wie die Ledger-Revision. */
    val revision: Long = 0L,
) {

    /**
     * Auftraege mit UNBEKANNTEM Ausgang - die Grundlage des Ambiguity-Holds.
     *
     * Das ist die einzige Aggregation, die dieses Modul anbietet, und sie ist
     * eine ANZAHL, keine Menge. Eine Summe ueber `requestedU` gibt es hier
     * absichtlich nicht (s. Zustaendigkeitsvertrag im Kopf der Datei).
     */
    val ambiguous: List<DeliveryRequest> get() = requests.values.filter { it.outcome == DeliveryOutcome.AMBIGUOUS }

    /** Zeilen mit offenem Widerspruch. Sie sperren und brauchen eine
     *  ausdrueckliche Aufloesung. */
    val withFindings: List<DeliveryRequest> get() = requests.values.filter { it.findings.isNotEmpty() }
}

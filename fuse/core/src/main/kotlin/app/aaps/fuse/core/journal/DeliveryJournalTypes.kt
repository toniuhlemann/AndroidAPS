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

    /**
     * GEBUCHT, AUSGANG UNBEKANNT - der Vorgabewert.
     *
     * Ein Versuch wird UNMITTELBAR VOR dem Schreibaufruf gebucht. In dem
     * Moment, in dem diese Zeile entsteht, ist der Aufruf also unmittelbar
     * bevorstehend oder laeuft schon. Nichts an diesem Zustand beweist, dass
     * nichts geschrieben wurde.
     *
     * DAS WAR DER FEHLER DER ERSTEN FASSUNG (adversariale Runde, F2): dort
     * hiess der Vorgabewert NOT_REACHED und war zugleich als "nur hier ist
     * nicht-gesendet beweisbar" definiert. Damit behauptete JEDER frisch
     * gebuchte Versuch seine eigene Ungefaehrlichkeit - und ein Prozessende
     * zwischen Buchung und Quittung hinterliess einen Zustand, der
     * autorisierte und einen Nullbeweis annahm.
     */
    ISSUED,

    /** Der Schreibauftrag wurde abgesetzt und vom Betriebssystem angenommen.
     *  Der Ausgang ist unbekannt - staerker belastend als [ISSUED], aber in
     *  der Wirkung dieselbe Klasse. */
    OS_WRITE_ACCEPTED,

    /**
     * BEWIESEN NICHT GESCHRIEBEN - der einzige entlastende Zustand.
     *
     * Er entsteht NUR durch ein ausdrueckliches Ereignis
     * ([DeliveryJournal.Event.WriteRefused]), nie durch Vorbelegung und nie
     * durch Zeitablauf. Wer ihn setzt, behauptet Wissen und muss es haben.
     */
    REFUSED,
    ;

    /** Kann dieser Versuch die Pumpe erreicht haben? Die Frage, an der jede
     *  Sicherheitsentscheidung des Moduls haengt. */
    val mayHaveReachedPump: Boolean get() = this == ISSUED || this == OS_WRITE_ACCEPTED
}

/**
 * WER die Aussage "nicht geschrieben" verantwortet.
 *
 * GESCHLOSSEN, und zwar aus einem Grund: je ein Wert je Treiberpfad, an dem
 * das Ausbleiben des Schreibvorgangs BELEGBAR ist. Der Integrationspass B1.1
 * hat den Medtrum-Sendeweg dafuer vollstaendig ausgelesen und genau zwei
 * solche Pfade gefunden. Ein Freitextfeld haette jederzeit einen dritten
 * erlaubt, den niemand am Code geprueft hat - und eine unbelegte Entlastung
 * nimmt Insulin aus der Haftung.
 *
 * Deshalb ist diese Liste kurz UND soll kurz bleiben: ein neuer Wert ist die
 * Behauptung, einen weiteren beweisbaren Pfad gefunden zu haben, und gehoert
 * mit Fundstelle belegt.
 */
enum class RefusalSource {

    /**
     * `BLEComm.kt:430-431`: Adapter oder Gatt-Objekt war beim Ausfuehren des
     * Write-Runnables null, also lief `handleNotInitialized()`.
     *
     * Beweisbar, weil der einzige Aufruf an das Betriebssystem (`:436`) im
     * else-Zweig desselben `if` steht. Der Zweig schliesst ihn strukturell
     * aus - kein Byte verlaesst den Prozess.
     */
    GATT_NOT_INITIALIZED_BEFORE_CALL,

    /**
     * `BLEComm.kt:436`: der Safe-Call `mBluetoothGatt?.writeCharacteristic(..)`
     * lieferte `null`, das Gatt-Objekt wurde also zwischen der Pruefung (`:430`)
     * und dem Aufruf (`:436`) von `close()` genullt.
     *
     * Beweisbar aus einer SPRACHGARANTIE, nicht aus einer Plattformzusage: bei
     * null-Empfaenger wird die Methode nicht betreten. Genau deshalb steht der
     * Fall `false` NICHT in dieser Liste - der waere ein Versprechen von AOSP,
     * und ein Versprechen ist kein Beweis.
     */
    GATT_HANDLE_NULL_AT_CALL,
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
    val boundary: AmbiguityBoundary = AmbiguityBoundary.ISSUED,
    /** WANN der Zustand sich entschieden hat (Annahme oder Verweigerung).
     *  Ohne diesen Zeitstempel liesse sich beim Replay nicht pruefen, ob zwei
     *  Beweise dasselbe Ereignis meinen oder zwei verschiedene. */
    val resolvedAtTs: Long? = null,
    /**
     * WER die Entscheidung autorisiert hat - nur beim entlastenden Fall
     * [AmbiguityBoundary.REFUSED] gesetzt.
     *
     * Fuer den EINZIGEN entlastenden Transportbeweis des Moduls muss spaeter
     * nachweisbar sein, welcher Treiberpfad die Aussage "nicht geschrieben"
     * verantwortet hat. Ohne die Quelle waere die Entlastung anonym - und eine
     * anonyme Entlastung ist in diesem Modul das teuerste Datum ueberhaupt.
     *
     * Die Annahme ([AmbiguityBoundary.OS_WRITE_ACCEPTED]) traegt bewusst keine
     * Quelle: sie kommt vom Betriebssystem, das keine nennt - und sie ist
     * belastend, also die unkritische Richtung.
     */
    val resolvedSource: RefusalSource? = null,
) {

    init {
        require((boundary == AmbiguityBoundary.REFUSED) == (resolvedSource != null)) {
            "only a refusal carries a source, and it must: boundary=$boundary source=$resolvedSource"
        }
        // Eine Blank-Pruefung braucht es hier nicht mehr: seit dem
        // Integrationspass ist die Quelle ein geschlossenes Enum, und ein
        // unbelegter Wert laesst sich gar nicht mehr hinschreiben. Das ist der
        // Unterschied zwischen "wir pruefen den Wert" und "der falsche Wert
        // existiert nicht" - dieselbe Lehre wie beim leeren Serial.
        require(attempt >= 1) { "attempt counts from 1, was $attempt" }
        require(startedAtTs > 0L) { "startedAtTs must be a real timestamp" }
        // Zustand und Zeitstempel muessen sich einig sein - eine Entscheidung
        // ohne Zeitpunkt waere ein halber Beweis, ein Zeitpunkt ohne
        // Entscheidung eine Behauptung ueber ein Ereignis, das nicht verbucht
        // ist.
        require((boundary != AmbiguityBoundary.ISSUED) == (resolvedAtTs != null)) {
            "boundary=$boundary and resolvedAtTs=$resolvedAtTs disagree"
        }
        // Nichts kann sich vor seinem eigenen Versuch entschieden haben. Eine
        // solche Zeile waere nicht "unwahrscheinlich", sondern unmoeglich -
        // und beim Replay ein stiller Hinweis darauf, dass zwei Versuche
        // vermischt wurden.
        require(resolvedAtTs == null || resolvedAtTs >= startedAtTs) {
            "resolvedAtTs=$resolvedAtTs before startedAtTs=$startedAtTs"
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
enum class DeliveryFinding(
    /**
     * Ist dieser Befund BELASTEND - also ein Hinweis darauf, dass eine Abgabe
     * stattgefunden haben koennte, ohne sauber zugeordnet zu sein?
     *
     * Die Eigenschaft steht ausdruecklich an jedem Wert und nicht als
     * Sammelregel ("alle Befunde sperren"), damit ein kuenftiger, wirklich
     * harmloser Befund eine ENTSCHEIDUNG erzwingt statt stillschweigend in die
     * eine oder andere Menge zu rutschen. Heute sind alle belastend.
     */
    val incriminating: Boolean,
) {

    /** Erst "nicht gesendet" bewiesen, dann doch eine Abgabe nachgewiesen.
     *  Die Abgabe gewinnt (konservativ), der Widerspruch bleibt sichtbar. */
    CONFIRMED_AFTER_PROVEN_NOT_SENT(incriminating = true),

    /** Ein Sendeversuch wurde NACH ueberschrittener Grenze gebucht - der
     *  Treiber hat also wiederholt, obwohl schon etwas unterwegs sein konnte.
     *  Die Buchung ist richtig (es ist eine Tatsache), die Lage ist es nicht. */
    ATTEMPT_AFTER_BOUNDARY(incriminating = true),

    /**
     * Der Beweis "nicht gesendet" war VERFRUEHT: eine verspaetete Rueckmeldung
     * hat danach die Ambiguitaetsgrenze belegt.
     *
     * Der Ablauf ist real und nicht konstruiert - der Schreibauftrag wird
     * asynchron quittiert, und ein Vor-Send-Abbruch kann in genau diesem
     * Fenster gebucht worden sein. Der Nullbeweis wird dann AUFGEHOBEN, die
     * Zeile geht nach AMBIGUOUS zurueck, und der Widerspruch bleibt sichtbar.
     */
    WRITE_ACCEPTED_AFTER_PROVEN_NOT_SENT(incriminating = true),

    /** Ein Identitaetsfakt der Pumpe (temporaryId/pumpId) traf eine Zeile, die
     *  als "nicht gesendet" galt. Eine Pumpen-Identitaet ist ein STAERKERER
     *  Beleg als eine OS-Quittung - der Nullbeweis war falsch. */
    IDENTITY_AFTER_PROVEN_NOT_SENT(incriminating = true),

    /** Zwei verschiedene Pumpen-Identitaeten fuer denselben Auftrag. Der
     *  direkteste denkbare Hinweis auf ZWEI Lieferungen; die Ablehnung allein
     *  wuerde ihn nur im fluechtigen Ergebnis hinterlassen. */
    IDENTITY_CONFLICT_SEEN(incriminating = true),

    /** Eine Grenzueberschreitung wurde gemeldet, konnte aber keinem gebuchten
     *  Versuch zugeordnet werden. Der belastende Fakt darf nicht mit der
     *  Ablehnung verschwinden. */
    UNANCHORED_WRITE_ACCEPTED(incriminating = true),
}

/**
 * Die Uebergabe an den Ledger als FAKT, nicht als blosser Zeitstempel.
 *
 * Ohne die Quelle liesse sich beim Replay nicht unterscheiden, ob zwei
 * Uebernahmen dasselbe Ereignis meinen oder zwei verschiedene - und eine
 * Uebernahme ist der Punkt, an dem eine Menge die Zustaendigkeit wechselt.
 */
data class AccountingFact(val atTs: Long, val source: String)

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
    val accounting: AccountingFact? = null,
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
        // Versuche laufen vorwaerts. Eine ruecklaeufige Startzeit hiesse, dass
        // die Reihenfolge nicht die ist, die die Nummern behaupten.
        attempts.zipWithNext().forEach { (a, b) ->
            require(b.startedAtTs >= a.startedAtTs) {
                "attempt timestamps must not go backwards: ${a.attempt}@${a.startedAtTs} -> ${b.attempt}@${b.startedAtTs}"
            }
        }
        require(accounting == null || terminal?.kind == TerminalKind.DELIVERY_CONFIRMED) {
            "only a confirmed delivery can be accounted"
        }
        // DER VERBOTENE ZUSTAND, konstruktiv ausgeschlossen: ein Versuch, der
        // die Pumpe erreicht haben kann, UND ein Nullbeweis koennen nicht
        // beide wahr sein. Waere diese Kombination deserialisierbar, truege
        // eine moeglicherweise abgegebene Menge das Etikett "nicht gesendet" -
        // und `open` waere false. Genau der Zustand, den das ganze Modul
        // ausschliessen soll.
        require(!(attempts.any { it.boundary.mayHaveReachedPump } &&
            terminal?.kind == TerminalKind.PROVEN_NOT_SENT)) {
            "an attempt that may have reached the pump and PROVEN_NOT_SENT cannot both hold"
        }
        // Dieselbe Regel fuer die Identitaet: wer eine Pumpen-Id traegt, hat
        // die Pumpe erreicht.
        require(!((temporaryId != null || pumpId != null) && terminal?.kind == TerminalKind.PROVEN_NOT_SENT)) {
            "a pump identity and PROVEN_NOT_SENT cannot both hold"
        }
        // Und fuer den belastenden BEFUND: die Outcome-Reihenfolge macht die
        // Kombination heute schon ungefaehrlich (AMBIGUOUS gewinnt), aber sie
        // waere deserialisierbar - und ein Zustand, den es nicht geben darf,
        // gehoert konstruktiv verboten, nicht nur folgenlos gemacht.
        require(!(findings.any { it.incriminating } && terminal?.kind == TerminalKind.PROVEN_NOT_SENT)) {
            "an incriminating finding and PROVEN_NOT_SENT cannot both hold"
        }
    }

    /**
     * KANN fuer diesen Auftrag etwas bei der Pumpe angekommen sein?
     *
     * Die tragende Frage des ganzen Moduls - und bewusst NICHT "wurde
     * geschrieben". Ein gebuchter, noch unquittierter Versuch zaehlt dazu:
     * die Buchung steht unmittelbar vor dem Schreibaufruf.
     */
    val mayHaveReachedPump: Boolean
        get() = attempts.any { it.boundary.mayHaveReachedPump } || temporaryId != null || pumpId != null

    /**
     * DAS PRAEDIKAT. Kann fuer diese Zeile eine Abgabe unterwegs ODER ungeklaert
     * sein?
     *
     * Es gibt genau EINE Stelle, an der diese Frage beantwortet wird, und alle
     * Verwender benutzen sie: Eigensperre, Fremdsperre, Nullbeweis und
     * [outcome]. Vorher gab es drei Schreibweisen desselben Gedankens -
     * `mayHaveReachedPump`, ein Findings-Test im Nullbeweis und
     * `findings.isNotEmpty()` in der Eigensperre - und sie sind
     * auseinandergelaufen: eine unverankerte Schreibquittung sperrte ihren
     * eigenen Auftrag, aber keinen neuen (Codex-Gegenpruefung).
     *
     * Der Unterschied zu [mayHaveReachedPump] ist genau dieser Fall: ein
     * BELASTENDER BEFUND ohne zugeordneten Versuch. Er ist ein Hinweis auf
     * einen Schreibauftrag, den dieses Journal nicht sauber kennt - und
     * Unwissen ueber einen moeglichen Schreibauftrag wiegt hier schwerer als
     * Wissen ueber einen bekannten.
     */
    val hasUnresolvedPossibleDelivery: Boolean
        get() = mayHaveReachedPump || findings.any { it.incriminating }

    val nextAttemptNumber: Int get() = attempts.size + 1

    /**
     * Die Reihenfolge der Zweige ist Sicherheitslogik, keine Kosmetik.
     *
     * [mayHaveReachedPump] steht VOR dem Nullbeweis - ein moeglicher Kontakt
     * mit der Pumpe schlaegt ihn immer. Die Kombination ist zwar konstruktiv
     * ausgeschlossen (s. init), aber die Reihenfolge macht die Absicht auch
     * dann eindeutig, wenn jemand die Invariante spaeter lockert.
     */
    val outcome: DeliveryOutcome
        get() = when {
            accounting != null                                -> DeliveryOutcome.ACCOUNTED
            terminal?.kind == TerminalKind.DELIVERY_CONFIRMED -> DeliveryOutcome.CONFIRMED
            hasUnresolvedPossibleDelivery                     -> DeliveryOutcome.AMBIGUOUS
            terminal?.kind == TerminalKind.PROVEN_NOT_SENT    -> DeliveryOutcome.PROVEN_NOT_SENT
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

    init {
        // Zweite Verteidigungslinie gegen eine halb geschriebene oder
        // manipulierte Datei: der Schluessel MUSS die Zeile sein, die er
        // benennt. Sonst koennte die Fremdsperre die eigene Zeile ueber das
        // Feld ausschliessen und dabei eine andere meinen.
        requests.forEach { (key, r) ->
            require(key == r.requestId) { "journal key '$key' does not match requestId '${r.requestId}'" }
        }
    }

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

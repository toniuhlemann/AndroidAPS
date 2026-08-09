package app.aaps.fuse.core.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DAS LIEFERJOURNAL (B1.1).
 *
 * Der Kern jedes Tests hier ist dieselbe Frage in verschiedenen Verkleidungen:
 * KANN gesendet worden sein? Nicht "wurde" - das weiss die Software nie
 * sicher, und jeder Test, der so tut, prueft eine Behauptung statt eines
 * Zustands.
 */
class DeliveryJournalTest {

    private val t0 = 1_700_000_000_000L
    private val leer = DeliveryJournalState()

    private fun angelegt(
        id: String = "r1",
        epoch: String? = "MEDTRUM_NANO/patch-7",
        u: Double = 0.30,
    ) = DeliveryJournal.reduce(leer, DeliveryJournal.Event.Created(id, "p1", u, t0, epoch)).state

    private fun DeliveryJournalState.dann(vararg e: DeliveryJournal.Event) = DeliveryJournal.reduceAll(this, e.toList())

    private fun DeliveryJournalState.r(id: String = "r1") = requests.getValue(id)

    // ---- Anlegen ----------------------------------------------------------

    @Test
    fun `ein frisch angelegter Auftrag bleibt offen, obwohl noch nichts passiert ist`() {
        val s = angelegt()
        assertEquals(DeliveryOutcome.CREATED, s.r().outcome)
        // Eine beabsichtigte Abgabe ist offen. Sie schliesst erst durch einen
        // Beweis - nicht dadurch, dass noch nichts geschehen ist.
        assertTrue(s.r().open)
    }

    /**
     * DER ZUSTAENDIGKEITSVERTRAG als Test (Codex-Gegenpruefung P1).
     *
     * Das Journal darf keine Mengensumme anbieten. Der bestehende Ledger
     * fuehrt dieselbe Menge bereits als Haftung; eine zweite Summe hier waere
     * die Einladung, beide zu addieren und dieselbe Menge doppelt zu belegen.
     * Das Journal beantwortet eine JA/NEIN-Frage, keine Menge.
     */
    @Test
    fun `das Journal bietet keine Mengensumme an - die Menge fuehrt der Ledger`() {
        // Java-Reflexion, nicht kotlin-reflect: das laeuft ohne zusaetzliche
        // Abhaengigkeit im Testpfad.
        val methoden = DeliveryJournalState::class.java.methods.map { it.name }
        assertTrue(methoden.none { it.contains("liable", ignoreCase = true) }) {
            "eine Mengensumme im Journal waere die Einladung zur Doppelzaehlung mit dem Ledger"
        }
        // Was es GIBT, ist die Liste der offenen Ausgaenge - eine Anzahl,
        // keine Menge.
        assertTrue(methoden.any { it == "getAmbiguous" })
    }

    @Test
    fun `derselbe Auftrag zweimal ist idempotent, ein abweichender ist ein Widerspruch`() {
        val s = angelegt()
        val nochmal = DeliveryJournal.reduce(s, DeliveryJournal.Event.Created("r1", "p1", 0.30, t0, "MEDTRUM_NANO/patch-7"))
        assertTrue(nochmal.applied)
        assertSame(s, nochmal.state)

        val anders = DeliveryJournal.reduce(s, DeliveryJournal.Event.Created("r1", "p1", 0.35, t0, "MEDTRUM_NANO/patch-7"))
        assertFalse(anders.applied)
        assertEquals(DeliveryJournal.Rejection.DUPLICATE_REQUEST_CONFLICT, anders.rejection)
    }

    // ---- Versuche ---------------------------------------------------------

    @Test
    fun `Versuche muessen lueckenlos ab eins wachsen`() {
        val s = angelegt()
        val luecke = DeliveryJournal.reduce(s, DeliveryJournal.Event.SendAttemptStarted("r1", attempt = 2, atTs = t0))
        assertEquals(DeliveryJournal.Rejection.ATTEMPT_OUT_OF_ORDER, luecke.rejection) {
            "eine Luecke hiesse, dass ein Versuch stattfand, ohne gebucht zu sein"
        }
        val ok = s.dann(DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0))
        assertTrue(ok.applied)
        // Schon die Buchung macht den Ausgang unbekannt - ATTEMPTED gibt es
        // erst wieder, wenn eine Verweigerung den Versuch entlastet hat.
        assertEquals(DeliveryOutcome.AMBIGUOUS, ok.state.r().outcome)
    }

    /**
     * Die automatischen Wiederholungen des Treibers sind EIGENE Versuche.
     * Ein Journal, das nur den ersten kennt, waere fuer genau den Fall blind,
     * fuer den es gebaut wurde.
     */
    @Test
    fun `jede automatische Wiederholung ist ein eigener Versuch`() {
        var s = angelegt()
        repeat(4) { i ->
            val r = s.dann(DeliveryJournal.Event.SendAttemptStarted("r1", i + 1, t0 + i * 1000L, transportSequence = 100L + i))
            assertTrue(r.applied)
            s = r.state
        }
        assertEquals(4, s.r().attempts.size)
        assertEquals(listOf(100L, 101L, 102L, 103L), s.r().attempts.map { it.transportSequence })
    }

    @Test
    fun `derselbe Versuch nochmal gebucht ist idempotent`() {
        val s = angelegt().dann(DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0, 100L)).state
        val nochmal = DeliveryJournal.reduce(s, DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0, 100L))
        assertTrue(nochmal.applied)
        assertEquals(1, nochmal.state.r().attempts.size)
    }

    // ---- die Ambiguitaetsgrenze -------------------------------------------

    /**
     * SCHON DIE BUCHUNG MACHT DEN AUSGANG UNBEKANNT (adversariale Runde, F2).
     *
     * Ein Versuch wird UNMITTELBAR VOR dem Schreibaufruf gebucht - in dem
     * Moment ist der Aufruf bevorstehend oder laeuft schon. Die erste Fassung
     * gab dem frisch gebuchten Versuch den Vorgabewert NOT_REACHED, der
     * zugleich als "nicht gesendet beweisbar" definiert war: jeder Versuch
     * behauptete seine eigene Ungefaehrlichkeit, und ein Prozessende zwischen
     * Buchung und Quittung hinterliess einen Zustand, der autorisierte.
     */
    @Test
    fun `schon der gebuchte Versuch macht den Ausgang unbekannt`() {
        val s = angelegt().dann(DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0)).state
        assertTrue(s.r().mayHaveReachedPump)
        assertEquals(DeliveryOutcome.AMBIGUOUS, s.r().outcome)
        assertTrue(s.r().open)
        assertEquals(DeliveryJournal.Denial.SELF_AMBIGUOUS, DeliveryJournal.mayAttempt(s, "r1"))
    }

    /** Erst die ausdrueckliche Verweigerung entlastet - nie eine Vorbelegung. */
    @Test
    fun `erst die ausdrueckliche Verweigerung entlastet einen Versuch`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 2, "gate reject before write"),
            ).state
        assertFalse(s.r().mayHaveReachedPump)
        assertEquals(DeliveryOutcome.ATTEMPTED, s.r().outcome)
        assertNull(DeliveryJournal.mayAttempt(s, "r1"))
    }

    /** Belastend schlaegt entlastend auch hier: eine OS-Quittung ueberstimmt
     *  eine gebuchte Verweigerung, mit Befund. */
    @Test
    fun `eine OS-Quittung ueberstimmt eine gebuchte Verweigerung`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 2, "driver said false"),
                DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 3),
            ).state
        assertTrue(s.r().mayHaveReachedPump)
        assertEquals(DeliveryOutcome.AMBIGUOUS, s.r().outcome)
        assertTrue(s.r().findings.isNotEmpty())
    }

    /**
     * DIE TRAGENDE INVARIANTE. Sobald EIN Versuch die Grenze ueberschritten
     * hat, kann der Auftrag nie mehr als "nicht gesendet" gelten - auch nicht,
     * wenn ein SPAETERER Versuch nachweislich scheitert.
     */
    @Test
    fun `nach ueberschrittener Grenze ist kein Nullbeweis mehr moeglich`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 10),
                // Der Treiber wiederholt - dieser Versuch kommt nicht mal los.
                DeliveryJournal.Event.SendAttemptStarted("r1", 2, t0 + 20),
            ).state

        val versuch = DeliveryJournal.reduce(s, DeliveryJournal.Event.ProvenNotSent("r1", t0 + 30, "gate reject"))
        assertFalse(versuch.applied)
        assertEquals(DeliveryJournal.Rejection.NOT_SENT_WHILE_ATTEMPT_UNRESOLVED, versuch.rejection)
        assertEquals(DeliveryOutcome.AMBIGUOUS, versuch.state.r().outcome)
        assertTrue(versuch.state.r().open)
    }

    /**
     * Der zweite Versuch NACH der Grenze wird gebucht - er ist eine Tatsache -,
     * aber er ist ein BEFUND. Verschweigen waere schlimmer als melden.
     */
    @Test
    fun `ein Versuch nach der Grenze wird gebucht und als Befund markiert`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 10),
                DeliveryJournal.Event.SendAttemptStarted("r1", 2, t0 + 20),
            ).state
        assertEquals(2, s.r().attempts.size)
        assertTrue(s.r().findings.contains(DeliveryFinding.ATTEMPT_AFTER_BOUNDARY))
        assertEquals(1, s.withFindings.size)
    }

    /** Die Umkehrung MUSS gelten, sonst staut sich mit jedem abgelehnten
     *  Kommando unaufloesbare Schuld an. */
    @Test
    fun `vor der Grenze ist der Nullbeweis echt und die Haftung faellt`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 3, "gate reject before write"),
                DeliveryJournal.Event.ProvenNotSent("r1", t0 + 5, "pre-send gate reject"),
            ).state
        assertEquals(DeliveryOutcome.PROVEN_NOT_SENT, s.r().outcome)
        assertFalse(s.r().open)
    }

    /**
     * ASYMMETRISCHE RANGFOLGE (Codex-Gegenpruefung, P0). Ein frueherer
     * "nicht gesendet" kann falsch gewesen sein - die Pumpenhistorie kann
     * Insulin nachweisen, das ein Vor-Send-Abbruch fuer unmoeglich hielt.
     * Dann gewinnt die Abgabe, weil nur diese Richtung konservativ ist. Der
     * Widerspruch bleibt als Befund stehen und sperrt.
     */
    @Test
    fun `eine bestaetigte Abgabe schlaegt den frueheren Nullbeweis`() {
        val s = angelegt().dann(DeliveryJournal.Event.ProvenNotSent("r1", t0, "reject")).state
        assertEquals(DeliveryOutcome.PROVEN_NOT_SENT, s.r().outcome)

        val danach = DeliveryJournal.reduce(s, DeliveryJournal.Event.DeliveryConfirmed("r1", t0 + 1, "pump history"))
        assertTrue(danach.applied)
        assertEquals(DeliveryOutcome.CONFIRMED, danach.state.r().outcome)
        assertTrue(danach.state.r().open)
        assertTrue(danach.state.r().findings.contains(DeliveryFinding.CONFIRMED_AFTER_PROVEN_NOT_SENT)) {
            "der Widerspruch darf nicht geglaettet werden"
        }
    }

    /** Der umgekehrte Weg bleibt verboten: eine bestaetigte Abgabe wird nie
     *  durch einen Nullbeweis ersetzt. */
    @Test
    fun `ein Nullbeweis ueberschreibt eine bestaetigte Abgabe nicht`() {
        val s = angelegt().dann(DeliveryJournal.Event.DeliveryConfirmed("r1", t0, "history")).state
        val zurueck = DeliveryJournal.reduce(s, DeliveryJournal.Event.ProvenNotSent("r1", t0 + 1, "reject"))
        assertFalse(zurueck.applied)
        assertEquals(DeliveryJournal.Rejection.TERMINAL_CONFLICT, zurueck.rejection)
        assertEquals(DeliveryOutcome.CONFIRMED, zurueck.state.r().outcome)
    }

    /** Derselbe Terminaltyp mit ANDEREM Zeitstempel ist ein zweites Ereignis,
     *  kein Wiederholungsversuch - und wird nicht still geschluckt. */
    @Test
    fun `zwei Beweise desselben Typs mit verschiedenen Zeiten widersprechen sich`() {
        val s = angelegt().dann(DeliveryJournal.Event.DeliveryConfirmed("r1", t0, "history")).state
        val zweiter = DeliveryJournal.reduce(s, DeliveryJournal.Event.DeliveryConfirmed("r1", t0 + 5, "history"))
        assertEquals(DeliveryJournal.Rejection.TERMINAL_CONFLICT, zweiter.rejection)
    }

    // ---- Uebergabe an den Ledger -----------------------------------------

    /**
     * Ohne diesen Uebergang bliebe eine bestaetigte Abgabe unbegrenzt offen -
     * neben derselben Menge im Ledger. Erst die Uebernahme schliesst sie.
     */
    @Test
    fun `erst die Uebernahme durch den Ledger schliesst eine bestaetigte Abgabe`() {
        val s = angelegt().dann(DeliveryJournal.Event.DeliveryConfirmed("r1", t0, "history")).state
        assertTrue(s.r().open)

        val fertig = s.dann(DeliveryJournal.Event.LiabilityAccounted("r1", t0 + 60_000L, "ledger")).state
        assertEquals(DeliveryOutcome.ACCOUNTED, fertig.r().outcome)
        assertFalse(fertig.r().open)
    }

    @Test
    fun `ohne nachgewiesene Abgabe gibt es keine Uebernahme`() {
        val s = angelegt()
        val r = DeliveryJournal.reduce(s, DeliveryJournal.Event.LiabilityAccounted("r1", t0, "ledger"))
        assertEquals(DeliveryJournal.Rejection.ACCOUNTED_WITHOUT_DELIVERY, r.rejection)
    }

    // ---- zweiphasige Identitaet -------------------------------------------

    @Test
    fun `temporaryId und spaetere pumpId sind dieselbe Lieferung`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.TemporaryIdObserved("r1", 4711L),
                DeliveryJournal.Event.PumpIdObserved("r1", 99L),
            ).state
        assertEquals(4711L, s.r().temporaryId)
        assertEquals(99L, s.r().pumpId)
        assertEquals(1, s.requests.size) { "der pumpId-Nachtrag darf keine zweite Lieferung erzeugen" }
    }

    @Test
    fun `eine widersprechende Identitaet wird abgelehnt`() {
        val s = angelegt().dann(DeliveryJournal.Event.TemporaryIdObserved("r1", 4711L)).state
        val andere = DeliveryJournal.reduce(s, DeliveryJournal.Event.TemporaryIdObserved("r1", 4712L))
        assertEquals(DeliveryJournal.Rejection.IDENTITY_CONFLICT, andere.rejection)
        assertEquals(4711L, andere.state.r().temporaryId)
    }

    @Test
    fun `Ereignisse zu einem unbekannten Auftrag werden nicht angewandt`() {
        val r = DeliveryJournal.reduce(leer, DeliveryJournal.Event.SendAttemptStarted("gibts-nicht", 1, t0))
        assertEquals(DeliveryJournal.Rejection.UNKNOWN_REQUEST, r.rejection)
        assertSame(leer, r.state)
    }

    // ---- die Frage vor jedem Send -----------------------------------------

    @Test
    fun `ohne durable Buchung darf nicht gesendet werden`() {
        assertEquals(DeliveryJournal.Denial.NOT_JOURNALED, DeliveryJournal.mayAttempt(leer, "r1"))
    }

    @Test
    fun `ohne gepinnte Pumpen-Epoch darf nicht gesendet werden`() {
        val s = angelegt(epoch = null)
        assertEquals(DeliveryJournal.Denial.NO_PUMP_EPOCH, DeliveryJournal.mayAttempt(s, "r1"))
    }

    @Test
    fun `ein sauber angelegter Auftrag mit Epoch darf senden`() {
        assertNull(DeliveryJournal.mayAttempt(angelegt(), "r1"))
    }

    @Test
    fun `ein bereits terminaler Auftrag sendet nicht noch einmal`() {
        val s = angelegt().dann(DeliveryJournal.Event.ProvenNotSent("r1", t0, "reject")).state
        assertEquals(DeliveryJournal.Denial.ALREADY_TERMINAL, DeliveryJournal.mayAttempt(s, "r1"))
    }

    /**
     * DER PERSISTENTE AMBIGUITY-HOLD (Vorgriff auf B1.6). Solange irgendwo
     * Insulin unterwegs sein KOENNTE, wird nichts Neues hinterhergeschickt -
     * und weil das aus dem durablen Zustand faellt, ueberlebt es Prozessende
     * und neuen Worker.
     */
    @Test
    fun `ein anderer Auftrag mit unbekanntem Ausgang sperrt den neuen`() {
        var s = angelegt(id = "alt")
        s = s.dann(
            DeliveryJournal.Event.SendAttemptStarted("alt", 1, t0),
            DeliveryJournal.Event.WriteAccepted("alt", 1, t0 + 10),
        ).state
        s = DeliveryJournal.reduce(
            s, DeliveryJournal.Event.Created("neu", "p2", 0.15, t0 + 60_000L, "MEDTRUM_NANO/patch-7")
        ).state

        assertEquals(1, s.ambiguous.size)
        assertEquals(DeliveryJournal.Denial.OTHER_REQUEST_AMBIGUOUS, DeliveryJournal.mayAttempt(s, "neu"))
        // Beide Zeilen sind offen - die alte, weil ihr Ausgang unbekannt ist,
        // die neue, weil sie beabsichtigt ist. Eine MENGE steht hier bewusst
        // nicht; die fuehrt der Ledger.
        assertTrue(s.requests.values.all { it.open })
    }

    /**
     * DIE HAUPTINVARIANTE GILT AUCH FUER DEN EIGENEN AUFTRAG (Codex-
     * Gegenpruefung, P0). Der erste Entwurf liess den eigenen Retry zu und
     * verwechselte dabei BUCHEN mit ERLAUBEN. Beim Medtrum kann ein Retry
     * nach einem `onCharacteristicWrite`-Fehler laufen, obwohl
     * `writeCharacteristic()` zuvor true geliefert hat - mit neuer BLE-Sequenz
     * und ohne idempotente Kennung im Payload. Eine Doppelabgabe ist damit
     * nicht ausgeschlossen.
     */
    @Test
    fun `nach ueberschrittener Grenze ist auch der eigene Retry gesperrt`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 10),
            ).state
        assertEquals(DeliveryJournal.Denial.SELF_AMBIGUOUS, DeliveryJournal.mayAttempt(s, "r1"))
    }

    /** Vor der Grenze bleibt der Retry moeglich - sonst waere ein sauber
     *  abgewiesener Versuch das Ende jeder Wiederholung. */
    @Test
    fun `nach einem sicheren Vor-Send-Abbruch ist der Retry weiter moeglich`() {
        // Der Name behauptet Sicherheit - jetzt steht sie auch im ZUSTAND.
        // Vorher enthielt das Fixture nur SendAttemptStarted, also gar keinen
        // Abbruch: der Test hat seine eigene Voraussetzung nie hergestellt
        // (adversariale Runde, F2).
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 1, "gate reject before write"),
            ).state
        assertFalse(s.r().mayHaveReachedPump)
        assertNull(DeliveryJournal.mayAttempt(s, "r1"))
    }

    @Test
    fun `ein offener Widerspruch sperrt die Zeile`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.ProvenNotSent("r1", t0, "reject"),
                DeliveryJournal.Event.DeliveryConfirmed("r1", t0 + 1, "history"),
            ).state
        assertTrue(s.r().findings.isNotEmpty())
        // ALREADY_TERMINAL greift hier zuerst - beide sperren, und das ist der
        // Punkt: eine widerspruechliche Zeile autorisiert nichts.
        assertNotNull(DeliveryJournal.mayAttempt(s, "r1"))
    }

    // ---- Schema-Haertung --------------------------------------------------

    @Test
    fun `eine leere Epoch sperrt wie eine fehlende`() {
        val s = angelegt(epoch = "   ")
        assertNull(s.r().pumpEpoch) { "leer wird beim Anlegen zu null normalisiert" }
        assertEquals(DeliveryJournal.Denial.NO_PUMP_EPOCH, DeliveryJournal.mayAttempt(s, "r1"))
    }

    /**
     * Ein deserialisierter Zustand, dessen Versuche mit 2 beginnen, behauptet,
     * Versuch 1 habe es nie gegeben. Der Unterschied zwischen "wir kennen alle
     * Versuche" und "wir kennen die, die uebrig blieben" ist genau der, an dem
     * dieses Journal haengt.
     */
    @Test
    fun `eine Zeile die mit Versuch zwei beginnt ist ungueltig`() {
        val fehler = assertThrows(IllegalArgumentException::class.java) {
            DeliveryRequest(
                requestId = "r1", proposalId = "p1", requestedU = 0.30, createdAtTs = t0,
                pumpEpoch = "e", attempts = listOf(SendAttempt(attempt = 2, startedAtTs = t0)),
            )
        }
        assertTrue(fehler.message!!.contains("exactly 1..n"))
    }

    @Test
    fun `eine Grenzueberschreitung ohne Zeitstempel ist ein halber Beweis`() {
        assertThrows(IllegalArgumentException::class.java) {
            SendAttempt(1, t0, boundary = AmbiguityBoundary.OS_WRITE_ACCEPTED, resolvedAtTs = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SendAttempt(1, t0, boundary = AmbiguityBoundary.ISSUED, resolvedAtTs = t0)
        }
    }

    @Test
    fun `der Zeitpunkt der Grenzueberschreitung wird gespeichert und beim Replay geprueft`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 10),
            ).state
        assertEquals(t0 + 10, s.r().attempts.first().resolvedAtTs)
        // Derselbe Beweis nochmal: idempotent. Ein anderer Zeitpunkt: Widerspruch.
        assertTrue(DeliveryJournal.reduce(s, DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 10)).applied)
        assertEquals(
            DeliveryJournal.Rejection.TERMINAL_CONFLICT,
            DeliveryJournal.reduce(s, DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 99)).rejection,
        )
    }

    /**
     * Nach einer BESTAETIGTEN Abgabe ist die Zeile abgeschlossen - ein neuer
     * Versuch dort waere ein Widerspruch.
     */
    @Test
    fun `nach einer bestaetigten Abgabe gibt es keine neuen Versuche`() {
        val s = angelegt().dann(DeliveryJournal.Event.DeliveryConfirmed("r1", t0, "history")).state
        val r = DeliveryJournal.reduce(s, DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0 + 5))
        assertEquals(DeliveryJournal.Rejection.ATTEMPT_AFTER_TERMINAL, r.rejection)
    }

    /**
     * Nach einem NULLBEWEIS dagegen ist ein neuer Versuch eine Tatsache, die
     * ihn widerlegt (adversariale Runde, F4). Ihn abzulehnen hiesse, dass ein
     * spaeterer Schreibbeweis keinen Anker mehr haette - die Zeile bliebe
     * geschlossen, obwohl das Betriebssystem einen zweiten Schreibauftrag
     * angenommen hat.
     */
    @Test
    fun `ein neuer Versuch nach einem Nullbeweis wird gebucht und hebt ihn auf`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 1, "driver said false"),
                DeliveryJournal.Event.ProvenNotSent("r1", t0 + 2, "reject"),
            ).state
        assertEquals(DeliveryOutcome.PROVEN_NOT_SENT, s.r().outcome)

        val wieder = s.dann(DeliveryJournal.Event.SendAttemptStarted("r1", 2, t0 + 20)).state
        assertEquals(2, wieder.r().attempts.size)
        assertNull(wieder.r().terminal)
        assertEquals(DeliveryOutcome.AMBIGUOUS, wieder.r().outcome)
        assertTrue(wieder.r().findings.contains(DeliveryFinding.WRITE_ACCEPTED_AFTER_PROVEN_NOT_SENT))
    }

    // ---- der verspaetete Rueckruf ----------------------------------------

    /**
     * DER ASYNCHRONE FALL (Codex-Gegenpruefung, P0). Der Schreibauftrag wird
     * asynchron quittiert - ein Vor-Send-Abbruch kann gebucht worden sein,
     * waehrend die Quittung schon unterwegs war.
     *
     * Vorher blieb die Zeile in diesem Fall GESCHLOSSEN (der Terminalzustand
     * gewann im outcome vor der Grenze), obwohl moeglicherweise Insulin
     * geflossen ist. Genau der verbotene Zustand.
     */
    @Test
    fun `ein verspaeteter Schreibbeweis hebt den Nullbeweis auf`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 3, "driver said false"),
                DeliveryJournal.Event.ProvenNotSent("r1", t0 + 5, "pre-send gate reject"),
            ).state
        assertEquals(DeliveryOutcome.PROVEN_NOT_SENT, s.r().outcome)
        assertFalse(s.r().open)

        val spaet = s.dann(DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 8)).state

        assertEquals(DeliveryOutcome.AMBIGUOUS, spaet.r().outcome)
        assertTrue(spaet.r().open) { "eine moeglicherweise abgegebene Menge muss haften" }
        assertNull(spaet.r().terminal) { "der Nullbeweis war verfrueht und ist aufgehoben" }
        assertTrue(spaet.r().findings.contains(DeliveryFinding.WRITE_ACCEPTED_AFTER_PROVEN_NOT_SENT))
        assertEquals(DeliveryJournal.Denial.UNRESOLVED_FINDING, DeliveryJournal.mayAttempt(spaet, "r1"))
    }

    /** Eine BESTAETIGTE Abgabe wird davon nicht angetastet - sie setzt einen
     *  Schreibauftrag ohnehin voraus, da widerspricht nichts. */
    @Test
    fun `ein spaeter Schreibbeweis laesst eine bestaetigte Abgabe unberuehrt`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.DeliveryConfirmed("r1", t0 + 5, "history"),
                DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 8),
            ).state
        assertEquals(DeliveryOutcome.CONFIRMED, s.r().outcome)
        assertTrue(s.r().findings.isEmpty())
    }

    /**
     * Und der Zustand, den es nicht geben darf, laesst sich auch nicht
     * DIREKT bauen - sonst waere er ueber eine Deserialisierung erreichbar.
     */
    @Test
    fun `ueberschrittene Grenze und Nullbeweis lassen sich nicht konstruieren`() {
        val fehler = assertThrows(IllegalArgumentException::class.java) {
            DeliveryRequest(
                requestId = "r1", proposalId = "p1", requestedU = 0.30, createdAtTs = t0, pumpEpoch = "e",
                attempts = listOf(
                    SendAttempt(1, t0, boundary = AmbiguityBoundary.OS_WRITE_ACCEPTED, resolvedAtTs = t0 + 1)
                ),
                terminal = TerminalFact(TerminalKind.PROVEN_NOT_SENT, t0 + 2, "reject"),
            )
        }
        assertTrue(fehler.message!!.contains("cannot both hold"))
    }

    // ---- Replay mit Widerspruch ------------------------------------------

    /**
     * EIN WIDERSPRUCH MITTEN IM STROM DARF SPAETERE FAKTEN NICHT VERSCHLUCKEN
     * (Codex-Gegenpruefung, P0).
     *
     * Die erste Fassung hielt nach der ersten Ablehnung an. Beim Replay waere
     * die spaetere Bestaetigung aus der Pumpenhistorie nie verarbeitet worden -
     * die Zeile bliebe faelschlich geschlossen, eine abgegebene Menge ohne
     * Haftung.
     */
    @Test
    fun `ein widerspruechliches Ereignis verhindert die spaetere Bestaetigung nicht`() {
        val r = DeliveryJournal.reduceAll(
            leer,
            listOf(
                DeliveryJournal.Event.Created("r1", "p1", 0.30, t0, "epoch-a"),
                DeliveryJournal.Event.ProvenNotSent("r1", t0 + 1, "reject"),
                // Widerspruch: zweiter Nullbeweis mit anderer Zeit -> Rejection
                DeliveryJournal.Event.ProvenNotSent("r1", t0 + 2, "reject"),
                // ... und DANACH die Wahrheit aus der Pumpenhistorie.
                DeliveryJournal.Event.DeliveryConfirmed("r1", t0 + 3, "pump history"),
            ),
        )

        assertEquals(1, r.rejections.size)
        assertEquals(2, r.rejections.first().index) { "die Ablehnung muss mit ihrem Index auffindbar sein" }
        assertEquals(DeliveryJournal.Rejection.TERMINAL_CONFLICT, r.rejections.first().rejection)
        // Entscheidend: die Bestaetigung ist trotzdem angekommen.
        assertEquals(DeliveryOutcome.CONFIRMED, r.state.r().outcome)
        assertTrue(r.state.r().open)
        assertTrue(r.state.r().findings.contains(DeliveryFinding.CONFIRMED_AFTER_PROVEN_NOT_SENT))
    }

    @Test
    fun `nach einer Ablehnung laeuft der Strom auf dem letzten gueltigen Zustand weiter`() {
        val r = DeliveryJournal.reduceAll(
            leer,
            listOf(
                DeliveryJournal.Event.Created("r1", "p1", 0.30, t0, "epoch-a"),
                // Luecke: Versuch 2 ohne Versuch 1 -> Rejection, Zustand bleibt
                DeliveryJournal.Event.SendAttemptStarted("r1", 2, t0 + 1),
                // Der richtige Versuch danach greift trotzdem.
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0 + 2),
                DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 3),
            ),
        )
        assertEquals(1, r.rejections.size)
        assertEquals(DeliveryJournal.Rejection.ATTEMPT_OUT_OF_ORDER, r.rejections.first().rejection)
        assertEquals(DeliveryOutcome.AMBIGUOUS, r.state.r().outcome)
        assertEquals(1, r.state.r().attempts.size)
    }

    // ---- weitere Haertung -------------------------------------------------

    @Test
    fun `die Quelle der Uebernahme wird gespeichert und streng replayt`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.DeliveryConfirmed("r1", t0, "history"),
                DeliveryJournal.Event.LiabilityAccounted("r1", t0 + 60_000L, "ledger"),
            ).state
        assertEquals("ledger", s.r().accounting!!.source)
        // Dieselbe Uebernahme nochmal: idempotent.
        assertTrue(DeliveryJournal.reduce(s, DeliveryJournal.Event.LiabilityAccounted("r1", t0 + 60_000L, "ledger")).applied)
        // Andere Quelle bei gleicher Zeit: zweites Ereignis, also Widerspruch.
        assertEquals(
            DeliveryJournal.Rejection.TERMINAL_CONFLICT,
            DeliveryJournal.reduce(s, DeliveryJournal.Event.LiabilityAccounted("r1", t0 + 60_000L, "woanders")).rejection,
        )
    }

    @Test
    fun `ein Schreibbeweis vor dem eigenen Versuch ist unmoeglich`() {
        assertThrows(IllegalArgumentException::class.java) {
            SendAttempt(1, t0, boundary = AmbiguityBoundary.OS_WRITE_ACCEPTED, resolvedAtTs = t0 - 1)
        }
    }

    // ---- Funde der adversarialen Runde ------------------------------------

    /**
     * F1: EINE PUMPEN-IDENTITAET IST DER STAERKSTE BELEG DES MODULS. Sie darf
     * nie in eine Zeile laufen, die als "nicht gesendet" gilt - die erste
     * Fassung liess genau das zu, ohne Ablehnung und ohne Spur.
     */
    @Test
    fun `ein Identitaetsfakt hebt einen Nullbeweis auf`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 1, "gate reject"),
                DeliveryJournal.Event.ProvenNotSent("r1", t0 + 2, "pre-send gate reject"),
            ).state
        assertEquals(DeliveryOutcome.PROVEN_NOT_SENT, s.r().outcome)

        val mitId = s.dann(DeliveryJournal.Event.PumpIdObserved("r1", 99L)).state
        assertEquals(DeliveryOutcome.AMBIGUOUS, mitId.r().outcome)
        assertTrue(mitId.r().open)
        assertNull(mitId.r().terminal)
        assertTrue(mitId.r().findings.contains(DeliveryFinding.IDENTITY_AFTER_PROVEN_NOT_SENT))
    }

    @Test
    fun `Pumpen-Identitaet und Nullbeweis lassen sich nicht konstruieren`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeliveryRequest(
                requestId = "r1", proposalId = "p1", requestedU = 0.30, createdAtTs = t0, pumpEpoch = "e",
                pumpId = 99L, terminal = TerminalFact(TerminalKind.PROVEN_NOT_SENT, t0, "reject"),
            )
        }
    }

    /** F8: ein Identitaetskonflikt - der direkteste Hinweis auf ZWEI
     *  Lieferungen - gehoert an die Zeile, nicht ins fluechtige Ergebnis. */
    @Test
    fun `ein Identitaetskonflikt hinterlaesst einen Befund`() {
        val s = angelegt().dann(DeliveryJournal.Event.PumpIdObserved("r1", 99L)).state
        val konflikt = DeliveryJournal.reduce(s, DeliveryJournal.Event.PumpIdObserved("r1", 100L))
        assertEquals(DeliveryJournal.Rejection.IDENTITY_CONFLICT, konflikt.rejection)
        assertTrue(konflikt.state.r().findings.contains(DeliveryFinding.IDENTITY_CONFLICT_SEEN))
        assertEquals(99L, konflikt.state.r().pumpId)
    }

    /** F7: eine unverankerte Quittung darf nicht spurlos abgelehnt werden -
     *  sonst geht der spaetere Nullbeweis anstandslos durch. */
    @Test
    fun `eine unverankerte Quittung hinterlaesst einen Befund`() {
        val r = DeliveryJournal.reduceAll(
            leer,
            listOf(
                DeliveryJournal.Event.Created("r1", "p1", 0.30, t0, "e"),
                DeliveryJournal.Event.WriteAccepted("r1", 3, t0 + 11),
                DeliveryJournal.Event.ProvenNotSent("r1", t0 + 30, "queue aborted"),
            ),
        )
        assertTrue(r.state.r().findings.contains(DeliveryFinding.UNANCHORED_WRITE_ACCEPTED))
        assertNotEquals(DeliveryOutcome.PROVEN_NOT_SENT, r.state.r().outcome)
    }

    /**
     * PFLICHTTEST der Gegenpruefung: eine unverankerte Quittung sperrte nur
     * IHREN eigenen Auftrag.
     *
     * Ursache war, dass es drei Schreibweisen desselben Gedankens gab -
     * mayHaveReachedPump, ein Findings-Test im Nullbeweis und
     * findings.isNotEmpty() in der Eigensperre - und die Fremdsperre nur die
     * erste benutzte. Ein Hinweis auf einen nicht zugeordneten Schreibauftrag
     * muss GLOBAL wirken; jetzt fragen alle vier Verwender dasselbe Praedikat.
     */
    @Test
    fun `eine unverankerte Quittung sperrt auch einen neuen Auftrag`() {
        var s = angelegt(id = "alt")
        s = DeliveryJournal.reduce(s, DeliveryJournal.Event.WriteAccepted("alt", 7, t0 + 10)).state
        assertTrue(s.r("alt").findings.contains(DeliveryFinding.UNANCHORED_WRITE_ACCEPTED))
        assertFalse(s.r("alt").mayHaveReachedPump) { "kein zugeordneter Versuch - genau das war die Luecke" }
        assertTrue(s.r("alt").hasUnresolvedPossibleDelivery)

        s = DeliveryJournal.reduce(
            s, DeliveryJournal.Event.Created("neu", "p2", 0.15, t0 + 120, "MEDTRUM_NANO/patch-7")
        ).state
        assertEquals(DeliveryJournal.Denial.OTHER_REQUEST_AMBIGUOUS, DeliveryJournal.mayAttempt(s, "neu"))
    }

    /** Die Quelle der Verweigerung bleibt erhalten und wird streng replayt -
     *  eine anonyme Entlastung waere das teuerste Datum des Moduls. */
    @Test
    fun `die Quelle einer Verweigerung ist persistent und replayfest`() {
        val s = angelegt()
            .dann(
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0),
                DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 2, "BLEComm returned false"),
            ).state
        assertEquals("BLEComm returned false", s.r().attempts.first().resolvedSource)
        assertTrue(
            DeliveryJournal.reduce(s, DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 2, "BLEComm returned false")).applied
        )
        assertEquals(
            DeliveryJournal.Rejection.TERMINAL_CONFLICT,
            DeliveryJournal.reduce(s, DeliveryJournal.Event.WriteRefused("r1", 1, t0 + 2, "woanders her")).rejection,
        )
    }

    /** F5: eine Invariantenverletzung wird als Ablehnung gemeldet, nicht
     *  geworfen - sonst nimmt sie den ganzen Strom mit. */
    @Test
    fun `ein ungueltiger Datensatz wird abgelehnt statt geworfen`() {
        val r = DeliveryJournal.reduceAll(
            leer,
            listOf(
                DeliveryJournal.Event.Created("r1", "p1", 0.30, t0, "e"),
                DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0 + 100),
                DeliveryJournal.Event.SendAttemptStarted("r1", 2, t0 + 50),
                DeliveryJournal.Event.DeliveryConfirmed("r1", t0 + 300, "history"),
            ),
        )
        assertEquals(1, r.rejections.size)
        assertEquals(DeliveryJournal.Rejection.INVALID_STATE, r.rejections.first().rejection)
        assertEquals(DeliveryOutcome.CONFIRMED, r.state.r().outcome)
    }

    /** F9: Schluessel und Zeile muessen dieselbe Id nennen. */
    @Test
    fun `ein Journal mit falsch geschluesselter Zeile ist ungueltig`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeliveryJournalState(requests = mapOf("anderer" to DeliveryRequest("r1", "p1", 0.30, t0, "e")))
        }
    }

    /** F3: eine Zeile mit gebuchtem, unquittiertem Versuch sperrt die
     *  anderen - vorher war sie nur ATTEMPTED und sperrte nicht. */
    @Test
    fun `ein fremder gebuchter Versuch sperrt bereits ohne Quittung`() {
        var s = angelegt(id = "alt")
        s = s.dann(DeliveryJournal.Event.SendAttemptStarted("alt", 1, t0)).state
        s = DeliveryJournal.reduce(
            s, DeliveryJournal.Event.Created("neu", "p2", 0.15, t0 + 120, "MEDTRUM_NANO/patch-7")
        ).state
        assertEquals(DeliveryJournal.Denial.OTHER_REQUEST_AMBIGUOUS, DeliveryJournal.mayAttempt(s, "neu"))
    }

    @Test
    fun `Versuchszeiten duerfen nicht rueckwaerts laufen`() {
        val fehler = assertThrows(IllegalArgumentException::class.java) {
            DeliveryRequest(
                requestId = "r1", proposalId = "p1", requestedU = 0.30, createdAtTs = t0, pumpEpoch = "e",
                attempts = listOf(SendAttempt(1, t0 + 100), SendAttempt(2, t0 + 50)),
            )
        }
        assertTrue(fehler.message!!.contains("backwards"))
    }

    // ---- Wiederaufnahme ---------------------------------------------------

    /**
     * Nach einem Prozessende ist nicht entscheidbar, ob das letzte Ereignis
     * noch geschrieben wurde. Der Wiederholungsversuch derselben Folge muss
     * deshalb denselben Zustand ergeben - sonst waere jede Wiederaufnahme ein
     * Risiko.
     */
    @Test
    fun `dieselbe Ereignisfolge zweimal ergibt denselben Zustand`() {
        val folge = listOf(
            DeliveryJournal.Event.Created("r1", "p1", 0.30, t0, "epoch-a"),
            DeliveryJournal.Event.SendAttemptStarted("r1", 1, t0 + 1),
            DeliveryJournal.Event.WriteAccepted("r1", 1, t0 + 2),
            DeliveryJournal.Event.TemporaryIdObserved("r1", 4711L),
            DeliveryJournal.Event.PumpIdObserved("r1", 99L),
            DeliveryJournal.Event.DeliveryConfirmed("r1", t0 + 3, "history"),
        )
        val einmal = DeliveryJournal.reduceAll(leer, folge)
        val zweimal = DeliveryJournal.reduceAll(einmal.state, folge)
        assertTrue(zweimal.applied)
        assertEquals(einmal.state.requests, zweimal.state.requests)
        assertEquals(DeliveryOutcome.CONFIRMED, zweimal.state.r().outcome)
        assertNotNull(zweimal.state.r().terminal)
    }
}

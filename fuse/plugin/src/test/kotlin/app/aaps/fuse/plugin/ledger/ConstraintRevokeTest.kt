package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.MealFoundation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * DIE BUCHFUEHRUNG ZAEHLT NUR, WAS DIE PUMPE AUCH BEKOMMEN HAT
 * (Toni 19.08., P0 - am Geraet gemessen).
 *
 * DER BEFUND. FUSE forderte 20 x 0,15 U an, die Pumpendatenbank zeigte
 * 2,70 U. AAPS hatte zwei Schritte am Intervalltor verworfen ("SMB requested
 * but still in 1 min interval"), weil eine verspaetet fertig gewordene
 * Medtrum-Abgabe den Abstand unter die erlaubten 45 s drueckte.
 *
 * Die Episodenzaehler standen trotzdem auf 3,00 U. FUSE hielt die Huelle
 * fuer vollstaendig geliefert, meldete ab T+20 `WINDOW_OVER` und holte die
 * fehlenden 0,30 U nie nach - der Evidenzbestand galt als mit 3 U bezahlt,
 * obwohl 2,70 U flossen.
 *
 * WARUM DIE RESERVIERUNG DAS NICHT AUFFANGEN KONNTE. Sie wird aufgeloest,
 * sobald die PUBLIKATION feststeht - und das ist VOR dem AAPS-Constraint.
 * Der wird erst im naechsten Zyklus sichtbar, ueber `priorActuation` und
 * [app.aaps.fuse.core.ledger.NotSentProof].
 *
 * DIE FEHLERRICHTUNG BLEIBT KONSERVATIV. Zurueckgedreht wird ausschliesslich
 * mit BEWEIS; ein unklarer Pumpenausgang bleibt als geliefert gebucht. "Zu
 * viel gebucht" laesst FUSE spaeter zu wenig nachliefern, die umgekehrte
 * Richtung liesse es zu viel geben.
 */
class ConstraintRevokeTest {

    private val ts = 1_786_000_000_000L
    private val ID = "s#42"

    /** Ein Zyklus, dessen 0,15 U durch das Publikationsgate gingen. */
    private fun nachPublikation(
        menge: Double = 0.15,
        prime: Boolean = true,
        onset: Boolean = true,
        meal: Boolean = true,
        phase: MealFoundation.Phase = MealFoundation.Phase.PHASE_B,
        schonGebucht: Double = 0.45,
    ): FuseLedgerAdapter {
        val a = FuseLedgerAdapter()
        val e = a.episodes
        e.primeSpentU = schonGebucht + menge
        e.onsetSpentU = schonGebucht + menge
        e.evidenceCommittedU = schonGebucht + menge
        e.deliveredSinceHandoverU = schonGebucht + menge
        if (meal) e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts, menge))
        e.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts, amountU = menge, prime = prime, onset = onset,
            mealTs = if (meal) ts else 0L, foundationPhase = phase,
        )
        // Das Publikationsgate hat die Menge durchgelassen.
        a.resolveReservation(ts, publishedU = menge, proposalId = ID)
        return a
    }

    // ---- Der gemessene Fall ------------------------------------------------

    /**
     * DAS AAPS-INTERVALLTOR HAT DIE MENGE VERWORFEN - alle fuenf Zaehler
     * gehen zurueck.
     */
    @Test
    fun `ein bewiesenes Nicht-Senden dreht alle Episodenzaehler zurueck`() {
        val a = nachPublikation()
        assertNotNull(a.episodes.settled, "die Buchung MUSS einen Zyklus ueberleben")

        val zurueck = a.revokeSettled(ID)

        assertEquals(0.15, zurueck, 1e-9, "genau die publizierte Menge")
        assertEquals(0.45, a.episodes.primeSpentU, 1e-9)
        assertEquals(0.45, a.episodes.onsetSpentU, 1e-9)
        assertEquals(0.45, a.episodes.evidenceCommittedU, 1e-9)
        assertEquals(0.45, a.episodes.deliveredSinceHandoverU, 1e-9)
        assertEquals(0, a.episodes.mealDeliveries.size, "und der Eintrag verschwindet")
        assertNull(a.episodes.settled, "danach gibt es nichts mehr zurueckzudrehen")
    }

    /**
     * DER ECHTE ZYKLUSABLAUF - und er sieht anders aus als mein erster Test
     * (Toni 19.08.).
     *
     * MEINE ERSTE FASSUNG WAR EINE ATTRAPPE. Sie rief `resolveReservation`
     * und `revokeSettled` unmittelbar nacheinander im selben
     * Schleifendurchlauf. Produktiv laeuft es umgekehrt herum:
     *
     *   Zyklus n    buche -> resolveReservation(n)
     *   Zyklus n+1  buche (NEUE Zeile!) -> DANN revokeSettled(n)
     *
     * Der Unterschied ist der ganze Punkt: zum Zeitpunkt des Zurueckdrehens
     * steht in `mealDeliveries` bereits ein NEUER Eintrag. Ein Test, der das
     * nicht nachstellt, kann den Fehler nicht finden, gegen den er gebaut ist.
     *
     * DESHALB WIEDERHOLT SICH HIER DER sourceTs. Genau dann trifft ein
     * `indexOfLast { it.ts == mealTs }` die falsche - die neue - Zeile. Die
     * Mengen sind unterschiedlich, damit eine Verwechslung im Ergebnis
     * sichtbar wird statt sich wegzukuerzen.
     */
    @Test
    fun `im echten Folgezyklus-Ablauf trifft das Zurueckdrehen die richtige Zeile`() {
        val a = FuseLedgerAdapter()
        val e = a.episodes

        // --- Zyklus 1: 0,15 U, sourceTs = ts. Publikation geht durch.
        e.primeSpentU += 0.15
        e.evidenceCommittedU += 0.15
        e.deliveredSinceHandoverU += 0.15
        e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts, 0.15))
        e.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts, amountU = 0.15, prime = true, onset = false,
            mealTs = ts, foundationPhase = MealFoundation.Phase.PHASE_B,
        )
        a.resolveReservation(ts, publishedU = 0.15, proposalId = "s#1")

        // --- Zyklus 2: DERSELBE sourceTs (wiederholter Punkt), aber 0,05 U.
        // Der Runner bucht ZUERST, der Beweis fuer Zyklus 1 kommt DANACH.
        e.primeSpentU += 0.05
        e.evidenceCommittedU += 0.05
        e.deliveredSinceHandoverU += 0.05
        e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts, 0.05))
        e.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts + 60_000L, amountU = 0.05, prime = true, onset = false,
            mealTs = ts, foundationPhase = MealFoundation.Phase.PHASE_B,
        )

        // JETZT der Beweis fuer Zyklus 1 - waehrend die neue Zeile schon steht.
        val zurueck = a.revokeSettled("s#1")

        assertEquals(0.15, zurueck, 1e-9, "es MUSS die Menge aus Zyklus 1 sein")
        assertEquals(
            1, e.mealDeliveries.size,
            "genau eine Zeile bleibt - die aus Zyklus 2",
        )
        assertEquals(
            0.05, e.mealDeliveries.single().amountU, 1e-9,
            "und es MUSS die NEUE sein (0,05), nicht die verworfene alte (0,15). " +
                "Mit indexOfLast ueber den Zeitstempel stuende hier 0,15.",
        )
        assertEquals(0.05, e.primeSpentU, 1e-9)
        assertEquals(0.05, e.deliveredSinceHandoverU, 1e-9)
    }

    /**
     * DER GANZE GEMESSENE ABLAUF: 20 Schritte, zwei davon verworfen - jetzt
     * in der ECHTEN Reihenfolge.
     *
     * Am Ende muessen 2,70 U in den Buechern stehen, dieselbe Zahl wie in der
     * Pumpendatenbank. Vor dem Fix standen dort 3,00 U.
     */
    @Test
    fun `nach zwei verworfenen von zwanzig Schritten stehen 2,70 U`() {
        val a = FuseLedgerAdapter()
        val e = a.episodes
        val verworfen = setOf(7, 13)
        // Der Beweis eines Zyklus kommt IM NAECHSTEN an.
        var offenerBeweis: String? = null

        for (i in 0 until 20) {
            val id = "s#$i"
            val zyklus = ts + i * 60_000L

            // (1) Der Runner bucht die neue Menge.
            e.primeSpentU += 0.15
            e.evidenceCommittedU += 0.15
            e.deliveredSinceHandoverU += 0.15
            e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(zyklus, 0.15))
            e.pendingReservation = EpisodeBudgets.Reservation(
                computeTs = zyklus, amountU = 0.15, prime = true, onset = false,
                mealTs = zyklus, foundationPhase = MealFoundation.Phase.PHASE_B,
            )

            // (2) DANN erst wird der Vorgaenger entlastet - so wie im Plugin,
            // wo notSentClaim VOR der neuen Buchung gebildet, aber im
            // events-Block gebucht wird.
            offenerBeweis?.let { a.revokeSettled(it) }
            offenerBeweis = null

            // (3) Und die Publikation dieses Zyklus wird aufgeloest.
            a.resolveReservation(zyklus, publishedU = 0.15, proposalId = id)
            if (i in verworfen) offenerBeweis = id
        }
        // Der Beweis des letzten verworfenen Zyklus kommt noch an.
        offenerBeweis?.let { a.revokeSettled(it) }

        assertEquals(2.70, e.primeSpentU, 1e-9, "die Huelle ist NICHT ausgeschoepft")
        assertEquals(2.70, e.evidenceCommittedU, 1e-9, "und der Bestand nicht bezahlt")
        assertEquals(2.70, e.deliveredSinceHandoverU, 1e-9)
        assertEquals(18, e.mealDeliveries.size, "zwei Eintraege sind verschwunden")
        assertEquals(2.70, e.mealDeliveries.sumOf { it.amountU }, 1e-9)
    }

    /**
     * DIE KENNUNG UEBERLEBT DEN CODEC - und NUR das.
     *
     * MEIN ERSTER KOMMENTAR HIER WAR SACHLICH FALSCH (Toni 19.08.). Er
     * behauptete, ohne die Kennung in der Datei faende ein Beweis "nach einem
     * Neustart" den Eintrag nicht mehr - und suggerierte damit, MIT ihr ginge
     * es. Das stimmt nicht, aus zwei unabhaengigen Gruenden:
     *
     *   (1) DIE REIHENFOLGE. Produktiv wird ZUERST persistiert
     *       (LedgerPublicationGate.persistVerified) und erst DANACH in
     *       resolveReservation die Kennung nachgetragen. Zum Zeitpunkt des
     *       Persists steht sie also noch gar nicht im Eintrag - sie erreicht
     *       die Datei erst mit dem NAECHSTEN Persist.
     *
     *   (2) `settled` IST NICHT PERSISTENT, ausdruecklich. Nach einem
     *       Neustart gibt es also gar nichts, was einen Beweis noch
     *       zuordnen koennte - unabhaengig davon, was in der Datei steht.
     *
     * NACH EINEM ECHTEN NEUSTART GEHT DIE ENTLASTUNG VERLOREN, und das ist
     * der gewollte Ausgang: die Buchung bleibt stehen, FUSE liefert spaeter
     * zu wenig statt zu viel. Der Test unten haelt genau das fest.
     *
     * WOZU DIE KENNUNG IN DER DATEI DANN TAUGT - und hier stand schon wieder
     * eine zu starke Behauptung (Toni 19.08.): "ein Beweis findet den Eintrag
     * dann auch nach einem zwischenzeitlichen Laden". Auch das stimmt nicht.
     * Ein Laden IST ein Prozessstart, und danach ist `settled` weg - die
     * Kennung wird gar nicht mehr gesucht.
     *
     * Fuer die ENTLASTUNG taugt sie in der Datei also nicht. Sie taugt fuer
     * zweierlei anderes, und beides ist real:
     *
     *   die EINDEUTIGKEIT bleibt ueber Ladevorgaenge erzwungen, sodass eine
     *   spaeter nachgetragene Kennung nicht mit einer alten kollidieren kann;
     *
     *   und im Trail steht, welche Mahlzeitenzeile zu welchem Zyklus gehoerte -
     *   ohne das waere eine Abweichung zwischen Buchfuehrung und
     *   Pumpendatenbank spaeter nicht mehr aufzuloesen. Genau diese Frage hat
     *   den ganzen Block ausgeloest.
     */
    @Test
    fun `die Buchungskennung ueberlebt den Codec-Rundlauf`() {
        val a = nachPublikation()
        assertEquals(ID, a.episodes.mealDeliveries.single().proposalId, "nachgetragen")

        val zurueck = LedgerCodec.decodeEpisodes(
            org.json.JSONObject(LedgerCodec.encodeEpisodes(a.episodes).toString())
        )
        assertEquals(ID, zurueck.mealDeliveries.single().proposalId, "und im Codec erhalten")
    }

    /**
     * NACH EINEM NEUSTART GIBT ES KEINE ENTLASTUNG MEHR - konservativ.
     *
     * `settled` ist nicht persistent. Ein Beweis, der nach dem Neustart
     * eintrifft, findet nichts mehr zuzuordnen und darf auch nichts finden:
     * er koennte sonst eine Menge entlasten, deren Umstaende dieser Prozess
     * gar nicht kennt.
     *
     * Die Fehlrichtung ist die richtige - die Buchung bleibt stehen, FUSE
     * liefert spaeter zu wenig statt zu viel.
     */
    @Test
    fun `nach einem Neustart bleibt die Buchung stehen`(@TempDir dir: File) {
        // MEINE ERSTE FASSUNG WAR EINE ATTRAPPE (Toni 19.08.): sie kopierte
        // den Zustand von Hand in einen neuen Adapter - und vergass dabei
        // onsetSpentU. Ein Test, der die Einigkeit der fuenf Buecher pruefen
        // soll, machte sie in seinem eigenen Aufbau uneinig.
        //
        // Jetzt der ECHTE Weg: persistVerified -> neuer Adapter -> loadOnce.
        val vor = FuseLedgerAdapter()
        vor.loadOnce(dir, "s1", ts)
        // EINE ECHTE AUTORISIERUNG, und der echte Weg hat sofort gezeigt,
        // warum das noetig ist: `deliveredSinceHandoverU` wird NUR gemeinsam
        // mit der Autorisierung persistiert (s. LedgerCodec.encodeFoundation).
        // Ohne sie ging der Zaehler beim Laden verloren, und der Test schlug
        // mit 0,0 statt 0,60 fehl. Die Attrappe konnte das nicht zeigen - sie
        // kopierte den Zaehler ja von Hand.
        //
        // Das ist kein Mangel, sondern der Vertrag: ein Bezahlstand ohne
        // Autorisierung ist bedeutungslos, und die beiden duerfen nicht
        // auseinanderlaufen.
        vor.episodes.foundation = MealFoundation.arm(
            markerTs = ts - 30 * 60_000L, foundationEnabled = true, totalBudgetU = 3.0,
            phaseAShare = 0.75, primeWindowMin = 15, wallCeilingMin = 45,
            pressObservedInThisProcess = true, primeDeclinedByUser = false,
            markerAuthorized = true, phaseBUntilMin = 60,
        )
        vor.episodes.primeSpentU = 0.60
        vor.episodes.onsetSpentU = 0.60
        vor.episodes.evidenceCommittedU = 0.60
        vor.episodes.deliveredSinceHandoverU = 0.60
        vor.episodes.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts, 0.15))
        vor.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts, amountU = 0.15, prime = true, onset = true,
            mealTs = ts, foundationPhase = MealFoundation.Phase.PHASE_B,
        )
        vor.resolveReservation(ts, publishedU = 0.15, proposalId = ID)
        assertNotNull(vor.episodes.settled, "vor dem Neustart ist die Ablage da")
        assertTrue(vor.persistVerified(dir), "schreiben muss gelingen")

        // Der Neustart.
        val nach = FuseLedgerAdapter()
        nach.loadOnce(dir, "s2", ts + 60_000L)

        assertNull(nach.episodes.settled, "ein Neustart hat keine offene Ablage")
        assertEquals(0.0, nach.revokeSettled(ID), 1e-9, "und damit nichts zurueckzudrehen")

        // ALLE FUENF Buecher, einzeln geprueft.
        assertEquals(0.60, nach.episodes.primeSpentU, 1e-9, "primeSpentU")
        assertEquals(0.60, nach.episodes.onsetSpentU, 1e-9, "onsetSpentU")
        assertEquals(0.60, nach.episodes.evidenceCommittedU, 1e-9, "evidenceCommittedU")
        assertEquals(0.60, nach.episodes.deliveredSinceHandoverU, 1e-9, "deliveredSinceHandoverU")
        assertEquals(
            0.15, nach.episodes.mealDeliveries.single().amountU, 1e-9,
            "mealDeliveries - die fuenf Buecher bleiben einig",
        )
    }

    /**
     * OHNE AUFFINDBARE MAHLZEITENZEILE WIRD GAR NICHTS ENTLASTET
     * (Toni 19.08.).
     *
     * Wuerde erst am Ende gesucht, stuenden vier Zaehler bereits gesenkt da -
     * vier Buecher korrigiert, eines nicht. Genau das Auseinanderlaufen,
     * gegen das dieser Block gebaut ist.
     */
    @Test
    fun `ohne auffindbare Mahlzeitenzeile bleibt alles stehen`() {
        val a = nachPublikation()
        // Der Widerspruch: die Ablage nennt eine Zeile, die es nicht mehr gibt.
        a.episodes.mealDeliveries.clear()

        assertEquals(0.0, a.revokeSettled(ID), 1e-9, "keine Teilentlastung")
        assertEquals(0.60, a.episodes.primeSpentU, 1e-9)
        assertEquals(0.60, a.episodes.onsetSpentU, 1e-9)
        assertEquals(0.60, a.episodes.evidenceCommittedU, 1e-9)
        assertEquals(
            0.60, a.episodes.deliveredSinceHandoverU, 1e-9,
            "ALLE vier Zaehler MUESSEN unveraendert bleiben",
        )
    }

    /**
     * UND SCHON DIE ABLAGE ENTSTEHT NICHT, wenn die Kennung nicht nachgetragen
     * werden konnte. Der Fall oben ist damit die zweite Verteidigungslinie -
     * die erste steht in `resolveReservation`.
     */
    @Test
    fun `ohne nachtragbare Zeile entsteht keine Ablage`() {
        val a = FuseLedgerAdapter()
        a.episodes.primeSpentU = 0.15
        // Die Reservierung nennt eine Mahlzeitenzeile - aber es gibt keine.
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts, amountU = 0.15, prime = true, onset = false,
            mealTs = ts, foundationPhase = MealFoundation.Phase.PHASE_B,
        )
        a.resolveReservation(ts, publishedU = 0.15, proposalId = ID)

        assertNull(a.episodes.settled, "ohne nachgetragene Kennung keine Ablage")
        assertEquals(0.0, a.revokeSettled(ID), 1e-9)
        assertEquals(0.15, a.episodes.primeSpentU, 1e-9, "die Belastung bleibt stehen")
    }

    // ---- Die Grenzen des Zurueckdrehens ------------------------------------

    /** Eine fremde Zeile fasst nichts an. */
    @Test
    fun `ein fremder Vorschlag dreht nichts zurueck`() {
        val a = nachPublikation()
        assertEquals(0.0, a.revokeSettled("s#fremd"), 1e-9)
        assertEquals(0.60, a.episodes.primeSpentU, 1e-9, "unveraendert")
        assertNotNull(a.episodes.settled, "und die Buchung bleibt zuordenbar")
    }

    /** Zweimal zurueckdrehen gibt nicht zweimal frei. */
    @Test
    fun `das Zurueckdrehen ist idempotent`() {
        val a = nachPublikation()
        a.revokeSettled(ID)
        assertEquals(0.0, a.revokeSettled(ID), 1e-9)
        assertEquals(0.45, a.episodes.primeSpentU, 1e-9)
    }

    /**
     * NUR EIN ZYKLUS WEIT. Die naechste Aufloesung ueberschreibt die Ablage -
     * mehr braucht es nicht, weil `priorActuation` genau den vorigen Zyklus
     * beschreibt. Ein aelterer Beweis findet nichts mehr und darf auch nichts
     * finden: er koennte sonst eine Menge entlasten, die inzwischen von einem
     * anderen Zyklus stammt.
     */
    @Test
    fun `eine neue Buchung ueberschreibt die vorige Ablage`() {
        val a = nachPublikation()
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts + 60_000L, amountU = 0.10, prime = true, onset = false,
            mealTs = 0L, foundationPhase = MealFoundation.Phase.PHASE_B,
        )
        a.resolveReservation(ts + 60_000L, publishedU = 0.10, proposalId = "s#43")

        assertEquals(0.0, a.revokeSettled(ID), 1e-9, "die alte Zeile ist nicht mehr zuordenbar")
        assertEquals(0.10, a.revokeSettled("s#43"), 1e-9, "die neue schon")
    }

    /**
     * OHNE proposalId ENTSTEHT KEINE ABLAGE.
     *
     * Dann gibt es keine Zuordnung und damit auch keine spaetere Entlastung.
     * Das ist der konservative Ausgang: die Buchung bleibt stehen.
     */
    @Test
    fun `ohne Vorschlagskennung gibt es nichts zurueckzudrehen`() {
        val a = FuseLedgerAdapter()
        a.episodes.primeSpentU = 0.15
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts, amountU = 0.15, prime = true, onset = false, mealTs = 0L,
            foundationPhase = MealFoundation.Phase.NONE,
        )
        a.resolveReservation(ts, publishedU = 0.15)
        assertNull(a.episodes.settled)
        assertEquals(0.0, a.revokeSettled(ID), 1e-9)
        assertEquals(0.15, a.episodes.primeSpentU, 1e-9, "die Belastung bleibt stehen")
    }

    /**
     * EINE VOM GATE ENTFERNTE MENGE LEGT KEINE ABLAGE AN.
     *
     * Sie wurde schon von `resolveReservation` zurueckgedreht - eine zweite
     * Entlastung ueber denselben Vorgang wuerde doppelt abziehen.
     */
    @Test
    fun `eine vom Gate entfernte Menge hinterlaesst keine Ablage`() {
        val a = nachPublikation(menge = 0.15).also { adapter ->
            adapter.episodes.settled = null
        }
        // Neuer Zyklus, diesmal vom Publikationsgate entfernt.
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts + 60_000L, amountU = 0.15, prime = true, onset = false,
            mealTs = 0L, foundationPhase = MealFoundation.Phase.PHASE_B,
        )
        a.episodes.primeSpentU += 0.15
        val vorher = a.episodes.primeSpentU
        a.resolveReservation(ts + 60_000L, publishedU = 0.0, proposalId = "s#44")

        assertNull(a.episodes.settled, "vom Gate entfernt = nichts mehr zurueckzudrehen")
        assertEquals(vorher - 0.15, a.episodes.primeSpentU, 1e-9, "schon hier zurueckgedreht")
        assertEquals(0.0, a.revokeSettled("s#44"), 1e-9, "und kein zweites Mal")
    }

    /**
     * NUR PHASE B BEWEGT DEN FUNDAMENT-ZAEHLER - auch beim spaeten
     * Zurueckdrehen.
     */
    @Test
    fun `nur Phase B dreht den Fundament-Zaehler zurueck`() {
        for (phase in listOf(
            MealFoundation.Phase.NONE,
            MealFoundation.Phase.PHASE_A,
            MealFoundation.Phase.AFTER_WINDOW,
        )) {
            val a = nachPublikation(phase = phase)
            a.revokeSettled(ID)
            assertEquals(
                0.60, a.episodes.deliveredSinceHandoverU, 1e-9,
                "$phase darf den Phase-B-Zaehler nicht senken",
            )
            assertEquals(0.45, a.episodes.primeSpentU, 1e-9, "$phase: Prime aber schon")
        }
    }

    /**
     * DIE GEFUNDENE ZEILE MUSS DIE MENGE AUCH TRAGEN (Toni 19.08.).
     *
     * Die Zeile zu FINDEN reicht nicht: traegt sie weniger als die Ablage
     * behauptet, zoege der Aufruf global `menge` ab und entfernte lokal nur
     * den kleineren Betrag - dieselbe Uneinigkeit der Buecher wie bei einer
     * fehlenden Zeile, nur mit einer gefundenen.
     */
    @Test
    fun `eine zu kleine Mahlzeitenzeile entlastet gar nicht`() {
        val a = nachPublikation()
        // Widerspruch im RAM: die Ablage nennt 0,15 U, die Zeile traegt 0,05.
        val z = a.episodes.mealDeliveries.single()
        a.episodes.mealDeliveries.clear()
        a.episodes.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(z.ts, 0.05, z.proposalId))

        assertEquals(0.0, a.revokeSettled(ID), 1e-9, "keine Teilentlastung")
        assertEquals(0.60, a.episodes.primeSpentU, 1e-9)
        assertEquals(0.60, a.episodes.onsetSpentU, 1e-9)
        assertEquals(0.60, a.episodes.evidenceCommittedU, 1e-9)
        assertEquals(0.60, a.episodes.deliveredSinceHandoverU, 1e-9)
        assertEquals(0.05, a.episodes.mealDeliveries.single().amountU, 1e-9, "und die Zeile bleibt")
    }

    /** Eine GROESSERE Zeile ist dagegen zulaessig - sie wird gekuerzt. */
    @Test
    fun `eine groessere Mahlzeitenzeile wird gekuerzt`() {
        val a = nachPublikation()
        val z = a.episodes.mealDeliveries.single()
        a.episodes.mealDeliveries.clear()
        a.episodes.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(z.ts, 0.25, z.proposalId))

        assertEquals(0.15, a.revokeSettled(ID), 1e-9)
        assertEquals(
            0.10, a.episodes.mealDeliveries.single().amountU, 1e-9,
            "der Rest bleibt stehen - er stammt aus einem anderen Vorgang",
        )
    }

    // ---- Die Kennung ist eine erzwungene Invariante (Toni 19.08.) ---------

    /**
     * EINE "STABILE IDENTITAET", DIE LEER, UNBEGRENZT LANG ODER DOPPELT SEIN
     * DARF, IST KEINE.
     *
     * Besonders die Eindeutigkeit ist keine Kosmetik: [revokeSettled] sucht
     * mit `indexOfFirst { it.proposalId == ... }`. Bei einer doppelten
     * Kennung traefe es die erste - also moeglicherweise die falsche Zeile,
     * und damit genau der Fehler, gegen den die Identitaet eingefuehrt wurde.
     */
    @Test
    fun `eine unbrauchbare Buchungskennung macht die Generation ungueltig`() {
        val faelle = listOf<Pair<String, (org.json.JSONArray) -> Unit>>(
            "leer" to { arr -> arr.getJSONArray(0).put(2, "") },
            "nur Leerzeichen" to { arr -> arr.getJSONArray(0).put(2, "   ") },
            "zu lang" to { arr -> arr.getJSONArray(0).put(2, "x".repeat(65)) },
            "doppelt" to { arr -> arr.getJSONArray(1).put(2, arr.getJSONArray(0).getString(2)) },
        )
        for ((name, brich) in faelle) {
            val e = EpisodeBudgets()
            e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts, 0.15, "s#1"))
            e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts + 60_000L, 0.10, "s#2"))
            val o = LedgerCodec.encodeEpisodes(e)
            brich(o.getJSONArray("mealDeliveries"))
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException::class.java,
                { LedgerCodec.decodeEpisodes(org.json.JSONObject(o.toString())) },
                "$name MUSS die Generation verwerfen",
            )
        }
    }

    /** Und die Gegenprobe: zwei verschiedene Kennungen sind der Normalfall. */
    @Test
    fun `zwei verschiedene Buchungskennungen sind gueltig`() {
        val e = EpisodeBudgets()
        e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts, 0.15, "s#1"))
        e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts + 60_000L, 0.10, "s#2"))
        val zurueck = LedgerCodec.decodeEpisodes(
            org.json.JSONObject(LedgerCodec.encodeEpisodes(e).toString())
        )
        assertEquals(listOf("s#1", "s#2"), zurueck.mealDeliveries.map { it.proposalId })
    }

    /**
     * MEHRERE EINTRAEGE OHNE KENNUNG bleiben zulaessig - `null` ist keine
     * Identitaet, sondern ihr Fehlen. Altbestand aus Dateien vor dem 19.08.
     * besteht genau daraus.
     */
    @Test
    fun `mehrere Eintraege ohne Kennung sind zulaessig`() {
        val e = EpisodeBudgets()
        repeat(3) { e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts + it * 60_000L, 0.10)) }
        val zurueck = LedgerCodec.decodeEpisodes(
            org.json.JSONObject(LedgerCodec.encodeEpisodes(e).toString())
        )
        assertEquals(3, zurueck.mealDeliveries.size)
        assertTrue(zurueck.mealDeliveries.all { it.proposalId == null })
    }
}

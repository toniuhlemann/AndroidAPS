package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.MealFoundation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
        if (meal) e.mealDeliveries.addLast(ts to menge)
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
     * DER GANZE GEMESSENE ABLAUF: 20 Schritte, zwei davon verworfen.
     *
     * Am Ende muessen 2,70 U in den Buechern stehen - dieselbe Zahl wie in
     * der Pumpendatenbank. Vor dem Fix standen dort 3,00 U.
     */
    @Test
    fun `nach zwei verworfenen von zwanzig Schritten stehen 2,70 U`() {
        val a = FuseLedgerAdapter()
        val e = a.episodes
        val verworfen = setOf(7, 13)

        for (i in 0 until 20) {
            val id = "s#$i"
            val zyklus = ts + i * 60_000L
            e.primeSpentU += 0.15
            e.evidenceCommittedU += 0.15
            e.deliveredSinceHandoverU += 0.15
            e.mealDeliveries.addLast(zyklus to 0.15)
            e.pendingReservation = EpisodeBudgets.Reservation(
                computeTs = zyklus, amountU = 0.15, prime = true, onset = false,
                mealTs = zyklus, foundationPhase = MealFoundation.Phase.PHASE_B,
            )
            // Das Publikationsgate laesst durch - AAPS greift erst danach.
            a.resolveReservation(zyklus, publishedU = 0.15, proposalId = id)
            // Und im FOLGEZYKLUS kommt der Beweis.
            if (i in verworfen) a.revokeSettled(id)
        }

        assertEquals(2.70, e.primeSpentU, 1e-9, "die Huelle ist NICHT ausgeschoepft")
        assertEquals(2.70, e.evidenceCommittedU, 1e-9, "und der Bestand nicht bezahlt")
        assertEquals(2.70, e.deliveredSinceHandoverU, 1e-9)
        assertEquals(18, e.mealDeliveries.size, "zwei Eintraege sind verschwunden")
        assertEquals(2.70, e.mealDeliveries.sumOf { it.second }, 1e-9)
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
}

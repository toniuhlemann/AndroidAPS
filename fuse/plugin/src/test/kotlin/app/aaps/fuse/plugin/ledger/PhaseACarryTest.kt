package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.MealFoundation
import app.aaps.fuse.core.ledger.NotSentProof
import app.aaps.fuse.core.ledger.QueueRejectReason
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WORAUS DER PHASE-B-UEBERTRAG ENTSTEHT - UND WORAUS NICHT (Toni 19.08.).
 *
 * DER VERTRAG, in genau dieser Reihenfolge:
 *
 *     [NotSentProof] liefert IRGENDEINEN sicheren Beweis
 *     UND [FuseLedgerAdapter.revokeSettled] findet EXAKT die Buchung
 *     UND deren GESPEICHERTE Phase ist PHASE_A
 *     -> `confirmedNotSentPhaseAU` waechst um die exakt zurueckgedrehte Menge
 *
 * KEINE GRUNDLISTE, und das ist eine Korrektur am ersten Bauauftrag. Der
 * nannte `CONSTRAINT_ZERO` und `GATE_BLOCKED` - und haette damit ausgerechnet
 * den GEMESSENEN Anlass verfehlt: Tonis 19:07-Fall ist `BOLUS_IN_QUEUE`
 * (Menge nach Constraints positiv, Apply-Block nie betreten). Der Mechanismus
 * haette seinen eigenen Ausloeser nicht geloest.
 *
 * Deshalb entscheidet der GRUND hier gar nichts. Er kommt in `revokeSettled`
 * nicht einmal vor - dass ein sicherer Beweis vorliegt, ist die
 * Voraussetzung, unter der die Methode ueberhaupt gerufen wird.
 *
 * DIE ZWEITE HAELFTE IST EBENSO WICHTIG: nichts entsteht ohne Gegenbuchung.
 * Scheitert das Zurueckdrehen - fehlende Zeile, abweichende Menge, fremde
 * Kennung, zweiter Aufruf -, dann entsteht auch kein Uebertrag. Sonst gaebe
 * es zusaetzliches Insulin fuer eine Menge, die in den Buechern weiter als
 * geliefert steht.
 */
class PhaseACarryTest {

    private val ts = 1_786_000_000_000L
    private val ID = "s#42"
    private val BUDGET = 3.0

    private fun autorisierung(budget: Double = BUDGET) = MealFoundation.arm(
        markerTs = ts, foundationEnabled = true, totalBudgetU = budget, phaseAShare = 0.75, phaseAUpfrontShare = 0.0,
        primeWindowMin = 15, wallCeilingMin = 45, phaseBUntilMin = 60,
        pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true,
    )

    /**
     * EIN ZYKLUS, DESSEN MENGE DURCH DAS PUBLIKATIONSGATE GING - der Zustand,
     * in dem der Beweis des Folgezyklus ihn vorfindet.
     *
     * Gebaut ueber `resolveReservation`, also ueber den ECHTEN Weg: eine von
     * Hand gesetzte `settled`-Ablage haette die Kennung nicht nachgetragen und
     * damit einen Zustand geprueft, den der Produktivcode nie erzeugt. Diese
     * Attrappe ist in dieser Baustelle schon dreimal passiert.
     */
    private fun nachPublikation(
        menge: Double = 0.15,
        phase: MealFoundation.Phase = MealFoundation.Phase.PHASE_A,
        schonGebucht: Double = 1.80,
        auth: MealFoundation.Authorization? = autorisierung(),
    ): FuseLedgerAdapter {
        val a = FuseLedgerAdapter()
        val e = a.episodes
        auth?.let { e.foundation = it }
        e.primeSpentU = schonGebucht + menge
        e.evidenceCommittedU = schonGebucht + menge
        if (phase == MealFoundation.Phase.PHASE_B) e.deliveredSinceHandoverU = menge
        e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts, menge))
        e.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts, amountU = menge, prime = true, onset = false,
            mealTs = ts, foundationPhase = phase,
        )
        a.resolveReservation(ts, publishedU = menge, proposalId = ID)
        return a
    }

    // ---- Der gemessene Fall, ganze Kette -----------------------------------

    /**
     * DER 19:07-FALL, von der Beobachtung bis zum Uebertrag.
     *
     * Die Beobachtung ist die gemessene: AAPS liess nach seinen Constraints
     * eine positive Menge stehen, hat den Apply-Block aber nie betreten.
     * [NotSentProof] nennt das `BOLUS_IN_QUEUE` - der Grund, den die urspruengliche
     * Grundliste NICHT enthielt.
     */
    @Test
    fun `der gemessene 19-07-Fall erzeugt den Uebertrag`() {
        val grund = NotSentProof.reasonFor(
            NotSentProof.Observation(
                correlated = true,
                ledgerPublishedU = 0.15,
                gateStripped = false,
                gateSealed = false,
                gatePersistFailed = false,
                aapsConstrainedU = 0.15,
                smbSetByPumpPresent = false,
            )
        )
        assertEquals(
            QueueRejectReason.BOLUS_IN_QUEUE, grund,
            "genau der Grund, den die erste Grundliste ausgelassen haette",
        )

        val a = nachPublikation()
        assertNotNull(a.episodes.settled, "die Buchung MUSS einen Zyklus ueberleben")

        val zurueck = a.revokeSettled(ID)

        assertEquals(0.15, zurueck.amountU, 1e-9)
        assertEquals(MealFoundation.Phase.PHASE_A, zurueck.foundationPhase, "die GESPEICHERTE Phase")
        assertEquals(
            0.15, a.episodes.confirmedNotSentPhaseAU, 1e-9,
            "der Uebertrag ist die exakt zurueckgedrehte Menge",
        )
        assertEquals(1.80, a.episodes.primeSpentU, 1e-9, "und die Buecher stehen zurueckgedreht daneben")
        assertEquals(1.80, a.episodes.evidenceCommittedU, 1e-9)
    }

    /** Mehrere belegte Luecken summieren sich - jede Minute einzeln bewiesen. */
    @Test
    fun `zwei belegte Luecken summieren sich`() {
        val a = nachPublikation()
        a.revokeSettled(ID)

        // Zweiter Zyklus, zweite verworfene Menge.
        val e = a.episodes
        e.primeSpentU += 0.15
        e.evidenceCommittedU += 0.15
        e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(ts + 60_000L, 0.15))
        e.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts + 60_000L, amountU = 0.15, prime = true, onset = false,
            mealTs = ts + 60_000L, foundationPhase = MealFoundation.Phase.PHASE_A,
        )
        a.resolveReservation(ts + 60_000L, publishedU = 0.15, proposalId = "s#43")
        a.revokeSettled("s#43")

        assertEquals(0.30, e.confirmedNotSentPhaseAU, 1e-9, "die gemessenen 0,30 U vom 19.08.")
    }

    // ---- Was KEINEN Uebertrag erzeugt --------------------------------------

    /**
     * PHASE B BRAUCHT KEINEN UEBERTRAG - und darf keinen bekommen.
     *
     * `deliveredSinceHandoverU` ist beim Zurueckdrehen schon gesunken, das
     * Soll steht damit von selbst wieder offen. Ein Uebertrag obendrauf waere
     * dieselbe Menge ZWEIMAL.
     *
     * MUTATIONSPROBE: derselbe Aufbau, nur die gespeicherte Phase ist eine
     * andere - und das Ergebnis muss sich unterscheiden.
     */
    @Test
    fun `eine Phase-B-Menge erzeugt keinen Uebertrag`() {
        val a = nachPublikation(phase = MealFoundation.Phase.PHASE_B)
        val zurueck = a.revokeSettled(ID)

        assertEquals(0.15, zurueck.amountU, 1e-9, "zurueckgedreht wird sie sehr wohl")
        assertEquals(MealFoundation.Phase.PHASE_B, zurueck.foundationPhase)
        assertEquals(0.0, a.episodes.confirmedNotSentPhaseAU, 1e-9, "aber ohne Uebertrag")
        assertEquals(
            0.0, a.episodes.deliveredSinceHandoverU, 1e-9,
            "die Wiedereroeffnung geschieht ueber den Bezahlstand, nicht ueber den Uebertrag",
        )
    }

    /**
     * ALLES ODER NICHTS GILT AUCH FUER DEN UEBERTRAG.
     *
     * Scheitert das Zurueckdrehen, bleibt die Menge in den Buechern als
     * geliefert stehen. Ein Uebertrag daneben waere zusaetzliches Insulin
     * OHNE Gegenbuchung - schlimmer als der Ausfall, den er heilen soll.
     */
    @Test
    fun `ein gescheitertes Zurueckdrehen erzeugt keinen Uebertrag`() {
        // (a) DIE MENGE STIMMT NICHT. Die Mahlzeitenzeile traegt weniger, als
        // die Ablage behauptet - ein Widerspruch im Zustand.
        val abweichend = nachPublikation()
        abweichend.episodes.mealDeliveries[0] =
            EpisodeBudgets.MealDelivery(ts, 0.10, ID)
        assertEquals(0.0, abweichend.revokeSettled(ID).amountU, 1e-9)
        assertEquals(0.0, abweichend.episodes.confirmedNotSentPhaseAU, 1e-9, "keine Teilentlastung, kein Uebertrag")

        // (b) DIE ZEILE FEHLT GANZ.
        val ohneZeile = nachPublikation()
        ohneZeile.episodes.mealDeliveries.clear()
        assertEquals(0.0, ohneZeile.revokeSettled(ID).amountU, 1e-9)
        assertEquals(0.0, ohneZeile.episodes.confirmedNotSentPhaseAU, 1e-9)

        // (c) FREMDE KENNUNG.
        val fremd = nachPublikation()
        assertEquals(0.0, fremd.revokeSettled("s#fremd").amountU, 1e-9)
        assertEquals(0.0, fremd.episodes.confirmedNotSentPhaseAU, 1e-9)

        // (d) ZWEITER AUFRUF. Der erste hat die Ablage geleert; ein zweiter
        // duerfte denselben Betrag nicht ein zweites Mal uebertragen.
        val doppelt = nachPublikation()
        doppelt.revokeSettled(ID)
        val nachErstem = doppelt.episodes.confirmedNotSentPhaseAU
        doppelt.revokeSettled(ID)
        assertEquals(nachErstem, doppelt.episodes.confirmedNotSentPhaseAU, 1e-9, "kein zweites Mal")
        assertEquals(0.15, nachErstem, 1e-9, "und der erste hat gewirkt - sonst prueft (d) nichts")
    }

    /**
     * OHNE LAUFENDE AUTORISIERUNG ENTSTEHT NICHTS.
     *
     * Es gibt dann kein Phase B, das ihn ausgeben koennte - und aufbewahrt
     * wird er nicht: die naechste Mahlzeit hat ihr eigenes Budget und darf
     * keine fremde Luecke erben.
     */
    @Test
    fun `ohne Autorisierung entsteht kein Uebertrag`() {
        val a = nachPublikation(auth = null)
        assertEquals(0.15, a.revokeSettled(ID).amountU, 1e-9, "zurueckgedreht wird trotzdem")
        assertEquals(0.0, a.episodes.confirmedNotSentPhaseAU, 1e-9)
    }

    /**
     * DER UEBERTRAG IST AM GESAMTBUDGET GEDECKELT.
     *
     * Praktisch kann er es kaum erreichen - Phase A hat nie mehr als ihr
     * Teilbudget. Der Deckel steht trotzdem, weil er die Zusicherung ist, die
     * der Codec beim Lesen prueft: waechst er hier ungedeckelt, waere jede
     * spaetere Generation unlesbar.
     */
    @Test
    fun `der Uebertrag ueberschreitet das Gesamtbudget nicht`() {
        val a = nachPublikation(menge = 2.0, schonGebucht = 0.0, auth = autorisierung(budget = 1.5))
        a.revokeSettled(ID)
        assertEquals(1.5, a.episodes.confirmedNotSentPhaseAU, 1e-9, "gedeckelt am gepinnten Gesamtbudget")
    }

    // ---- Persistenz --------------------------------------------------------

    private fun rundlauf(e: EpisodeBudgets): EpisodeBudgets =
        LedgerCodec.decodeEpisodes(JSONObject(LedgerCodec.encodeEpisodes(e).toString()))

    @Test
    fun `der Uebertrag ueberlebt den Rundlauf`() {
        val a = nachPublikation()
        a.revokeSettled(ID)
        assertEquals(0.15, rundlauf(a.episodes).confirmedNotSentPhaseAU, 1e-9)
    }

    /**
     * DAS ZURUECKDREHEN TRIFFT AUCH DEN PHASE-A-BEZAHLSTAND (Codex 19.08.).
     *
     * Beides gehoert in denselben Zug: der Uebertrag entsteht, UND die
     * gebuchte Phase-A-Menge faellt. Nur so oeffnet sich der Rueckstand, aus
     * dem der Uebertrag ueberhaupt seine Wirkung bezieht - der effektive Rest
     * ist das Minimum aus beidem. Bliebe der Bezahlstand stehen, waere der
     * Uebertrag auf der Stelle wirkungslos und der ganze Mechanismus tot.
     */
    @Test
    fun `das Zurueckdrehen senkt den Phase-A-Bezahlstand mit`() {
        val a = nachPublikation()
        a.episodes.deliveredPhaseAU = 1.95      // inklusive der gleich verworfenen 0,15
        a.revokeSettled(ID)

        assertEquals(1.80, a.episodes.deliveredPhaseAU, 1e-9, "die Menge faellt aus Phase A heraus")
        assertEquals(0.15, a.episodes.confirmedNotSentPhaseAU, 1e-9, "und steht als Uebertrag da")
    }

    /** Und er ueberlebt den Rundlauf - sonst saehe ein Neustart einen
     *  Rueckstand in voller Hoehe und liesse den Uebertrag wirken, obwohl
     *  Prime laengst geliefert hat. */
    @Test
    fun `der Phase-A-Bezahlstand ueberlebt den Rundlauf`() {
        val a = nachPublikation()
        a.episodes.deliveredPhaseAU = 1.80
        assertEquals(1.80, rundlauf(a.episodes).deliveredPhaseAU, 1e-9)
    }

    @Test
    fun `der Abwaertsaufschub ueberlebt den Rundlauf`() {
        val a = nachPublikation()
        a.episodes.descentDeferredPhaseAU = 1.65
        assertEquals(1.65, rundlauf(a.episodes).descentDeferredPhaseAU, 1e-9)
    }

    @Test
    fun `v13 Autorisierung ohne Abwaertsaufschub startet konservativ bei null`() {
        val a = nachPublikation()
        val json = LedgerCodec.encodeEpisodes(a.episodes)
        json.getJSONObject("foundation").remove("descentDeferredPhaseAU")
        assertEquals(
            0.0,
            LedgerCodec.decodeEpisodes(JSONObject(json.toString())).descentDeferredPhaseAU,
            1e-9,
        )
    }

    @Test
    fun `unmoeglicher Abwaertsaufschub verwirft die Generation`() {
        val a = nachPublikation()
        for (bad in listOf(-0.05, BUDGET + 0.05)) {
            val json = LedgerCodec.encodeEpisodes(a.episodes)
            json.getJSONObject("foundation").put("descentDeferredPhaseAU", bad)
            assertThrows(IllegalArgumentException::class.java) {
                LedgerCodec.decodeEpisodes(JSONObject(json.toString()))
            }
        }
    }

    /**
     * KEIN BEZIEHUNGSRIEGEL GEGEN `evidenceCommittedU` (Codex-Rueckfrage,
     * hier als Regressionsschutz).
     *
     * Der Vorschlag war `deliveredSinceHandoverU <= evidenceCommittedU`. Der
     * Runner-Test `ohne Evidenzepisode waechst nur der Bezahlstand` zeigt, dass
     * das im Normalbetrieb bricht. Diese Datei haelt fest, dass der Codec eine
     * solche Datei ANNIMMT - damit der Riegel nicht spaeter aus guten
     * Absichten nachgereicht wird und eine gesunde zweite Mahlzeit in den
     * RECOVERY_HOLD schickt.
     */
    @Test
    fun `ein Bezahlstand ueber der Evidenzmenge ist ladbar`() {
        val a = nachPublikation()
        a.episodes.evidenceCommittedU = 0.0     // keine Evidenzepisode
        a.episodes.deliveredSinceHandoverU = 0.40
        val zurueck = rundlauf(a.episodes)
        assertEquals(0.40, zurueck.deliveredSinceHandoverU, 1e-9, "ladbar, nicht Korruption")
    }

    /**
     * ZUSAMMEN ODER GAR NICHT - der Uebertrag steht IM Autorisierungsobjekt.
     *
     * Ohne die Autorisierung wird er gar nicht erst geschrieben. Das ist die
     * sichere Richtung: ein wiedergefundener Uebertrag ohne die Episode, aus
     * der er stammt, waere ein Freibrief fuer die naechste Mahlzeit.
     */
    @Test
    fun `ohne Autorisierung wird kein Uebertrag geschrieben`() {
        val e = EpisodeBudgets()
        e.confirmedNotSentPhaseAU = 0.30       // kann so gar nicht entstehen
        val json = LedgerCodec.encodeEpisodes(e)
        assertTrue(!json.has("foundation"), "kein Fundament, also kein Unterobjekt")
        assertEquals(0.0, rundlauf(e).confirmedNotSentPhaseAU, 1e-9, "und damit kein Uebertrag")
    }

    /**
     * STRIKT NACH INNEN: fehlt das Feld im vorhandenen Objekt, ist die
     * Generation KAPUTT - nicht "alt".
     *
     * Das Fundament ist nie geflasht worden; eine Datei mit `foundation`-
     * Objekt ohne dieses Feld kann es also nicht geben. Sie stillschweigend
     * als 0 zu lesen hiesse, einer beschaedigten NEUEREN Generation den Sieg
     * ueber eine intakte aeltere zu erlauben.
     */
    @Test
    fun `ein fehlender Uebertrag im vorhandenen Objekt wirft`() {
        val a = nachPublikation()
        a.revokeSettled(ID)
        val json = LedgerCodec.encodeEpisodes(a.episodes)
        assertTrue(
            json.getJSONObject("foundation").has("confirmedNotSentPhaseAU"),
            "der Encoder MUSS das Feld schreiben, sonst prueft dieser Test nichts",
        )
        json.getJSONObject("foundation").remove("confirmedNotSentPhaseAU")
        assertThrows(org.json.JSONException::class.java) {
            LedgerCodec.decodeEpisodes(JSONObject(json.toString()))
        }
    }

    /** Ein Uebertrag oberhalb des Gesamtbudgets kann von diesem Schreiber
     *  nicht stammen - und wird nicht still gekappt, sondern abgewiesen. */
    @Test
    fun `ein Uebertrag ueber dem Budget macht die Generation ungueltig`() {
        val a = nachPublikation()
        a.revokeSettled(ID)
        val json = LedgerCodec.encodeEpisodes(a.episodes)
        json.getJSONObject("foundation").put("confirmedNotSentPhaseAU", BUDGET + 0.5)
        assertThrows(IllegalArgumentException::class.java) {
            LedgerCodec.decodeEpisodes(JSONObject(json.toString()))
        }
    }

    /** Und ein negativer ebenso wenig. */
    @Test
    fun `ein negativer Uebertrag macht die Generation ungueltig`() {
        val a = nachPublikation()
        a.revokeSettled(ID)
        val json = LedgerCodec.encodeEpisodes(a.episodes)
        json.getJSONObject("foundation").put("confirmedNotSentPhaseAU", -0.05)
        assertThrows(IllegalArgumentException::class.java) {
            LedgerCodec.decodeEpisodes(JSONObject(json.toString()))
        }
    }
}

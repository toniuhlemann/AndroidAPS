package app.aaps.fuse.plugin.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Die Episodenbudgets duerfen nur belastet bleiben, wenn wirklich etwas
 * hinausgegangen ist.
 *
 * BEFUND, DER DAZU GEFUEHRT HAT (Review 11.08.): der Runner bucht
 * `primeSpentU`, `onsetSpentU` und `mealDeliveries` gegen das PUMPEN-Gate. Das
 * PUBLIKATIONS-Gate laeuft erst danach im Plugin und kann die Menge noch
 * entfernen - fehlende Behandlungs-Vollsicht, Persistenzfehler, Epochensperre.
 * Bis dahin war jede solche Zeile ein Budgetverbrauch ohne Insulin.
 *
 * DIE RICHTUNG IST DER GANZE PUNKT. Reserviert wird SOFORT, aufgeloest wird
 * danach - nicht umgekehrt. Eine nachgelagerte Buchung entfiele bei einem
 * Absturz zwischen beiden Schritten ganz, und das ist die gefaehrliche
 * Richtung: Budget frei, Insulin draussen. So bleibt jeder ungeklaerte Ausgang
 * auf "belastet".
 */
class EpisodeReservationTest {

    private val ts = 1_786_000_000_000L

    /** Ein Zyklus, der 0,30 U durch das Pumpen-Gate gebracht hat. */
    private fun adapterMitReservierung(
        prime: Boolean = true,
        onset: Boolean = true,
        meal: Boolean = true,
        menge: Double = 0.30,
    ): FuseLedgerAdapter {
        val a = FuseLedgerAdapter()
        val e = a.episodes
        if (prime) e.primeSpentU = menge
        if (onset) e.onsetSpentU = menge
        if (meal) e.mealDeliveries.addLast(ts to menge)
        e.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts, amountU = menge, prime = prime, onset = onset,
            mealTs = if (meal) ts else 0L,
        )
        return a
    }

    @Test
    fun `publiziert wie reserviert - die Belastung bleibt`() {
        val a = adapterMitReservierung()
        a.resolveReservation(ts, publishedU = 0.30)
        assertEquals(0.30, a.episodes.primeSpentU, 1e-12)
        assertEquals(0.30, a.episodes.onsetSpentU, 1e-12)
        assertEquals(1, a.episodes.mealDeliveries.size)
        assertNull(a.episodes.pendingReservation, "die Reservierung ist erledigt")
    }

    /**
     * DER FALL, UM DEN ES GEHT: das Publikationsgate hat die Menge entfernt.
     * Ohne Aufloesung waere das Budget verbraucht, obwohl nichts geflossen ist.
     */
    @Test
    fun `vom Gate entfernt - die Belastung wird freigegeben`() {
        val a = adapterMitReservierung()
        a.resolveReservation(ts, publishedU = 0.0)
        assertEquals(0.0, a.episodes.primeSpentU, 1e-12)
        assertEquals(0.0, a.episodes.onsetSpentU, 1e-12)
        assertTrue(a.episodes.mealDeliveries.isEmpty(), "der Eintrag muss weg sein")
    }

    /** Teilweise publiziert: nur die Differenz wird frei. */
    @Test
    fun `teilweise publiziert - nur die Differenz wird frei`() {
        val a = adapterMitReservierung(menge = 0.30)
        a.resolveReservation(ts, publishedU = 0.20)
        assertEquals(0.20, a.episodes.primeSpentU, 1e-12)
        assertEquals(0.20, a.episodes.mealDeliveries.single().second, 1e-12)
    }

    /**
     * NIE AUFGELOEST = BELASTET. Der Absturzfall, und der gewollte Ausgang:
     * lieber ein Budget zu viel verbraucht als eine Menge unbezahlt draussen.
     */
    @Test
    fun `ohne Aufloesung bleibt die Belastung stehen`() {
        val a = adapterMitReservierung()
        // kein resolveReservation - Prozess weg
        assertEquals(0.30, a.episodes.primeSpentU, 1e-12)
        assertEquals(1, a.episodes.mealDeliveries.size)
    }

    /**
     * Ein spaeterer Zyklus darf die Reservierung eines anderen nicht anfassen.
     * Ohne die Identitaetspruefung koennte ein verspaeteter Aufruf fremdes
     * Budget freigeben - und DAS waere die gefaehrliche Richtung.
     */
    @Test
    fun `ein fremder Zyklus loest nichts auf`() {
        val a = adapterMitReservierung()
        a.resolveReservation(ts + 60_000L, publishedU = 0.0)
        assertEquals(0.30, a.episodes.primeSpentU, 1e-12)
        assertEquals(1, a.episodes.mealDeliveries.size)
        assertTrue(a.episodes.pendingReservation != null, "sie gehoert weiter dem anderen Zyklus")
    }

    /** Zweimal aufloesen darf nicht zweimal freigeben. */
    @Test
    fun `zweimal aufloesen gibt nur einmal frei`() {
        val a = adapterMitReservierung()
        a.episodes.primeSpentU = 0.50   // Vorepisode plus dieser Zyklus
        a.resolveReservation(ts, publishedU = 0.0)
        assertEquals(0.20, a.episodes.primeSpentU, 1e-12)
        a.resolveReservation(ts, publishedU = 0.0)
        assertEquals(0.20, a.episodes.primeSpentU, 1e-12, "der zweite Aufruf darf nichts tun")
    }

    /** Kein Kanal offen, nur die Mahlzeitenliste - dann auch nur die. */
    @Test
    fun `nur die betroffenen Toepfe werden angefasst`() {
        val a = adapterMitReservierung(prime = false, onset = false, meal = true)
        a.episodes.primeSpentU = 0.75
        a.resolveReservation(ts, publishedU = 0.0)
        assertEquals(0.75, a.episodes.primeSpentU, 1e-12, "prime war nicht Teil der Reservierung")
        assertTrue(a.episodes.mealDeliveries.isEmpty())
    }

    /** Keine Belastung unter null, auch wenn die Zahlen nicht zusammenpassen. */
    @Test
    fun `die Freigabe treibt kein Budget unter null`() {
        val a = adapterMitReservierung(menge = 0.30)
        a.episodes.primeSpentU = 0.10   // weniger, als die Reservierung behauptet
        a.resolveReservation(ts, publishedU = 0.0)
        assertEquals(0.0, a.episodes.primeSpentU, 1e-12)
    }

    // ---- Der Evidenz-Zaehler (Stufe 1 der Verdrahtung) --------------------

    /**
     * EINE ABGELEHNTE ABGABE GILT NICHT ALS BEZAHLT.
     *
     * Der Evidenz-Zaehler ist die Bezahlseite des Stoerungsbestands. Bliebe er
     * nach einem Publikations-Reject stehen, waere der Bestand dauerhaft zu
     * klein - eine nie geflossene Dosis gaelte als Abtrag, und der Empfaenger
     * wuerde genau um diesen Betrag zu wenig freigeben. Die Fehlerrichtung ist
     * hier also NICHT die konservative: sie kostet Insulin, das gebraucht wird.
     */
    @Test
    fun `ein Publikations-Reject gibt den Evidenz-Zaehler zurueck`() {
        val a = adapterMitReservierung()
        a.episodes.evidenceEpisodeId = ts
        a.episodes.evidenceCommittedU = 0.30

        a.resolveReservation(ts, publishedU = 0.0)

        assertEquals(0.0, a.episodes.evidenceCommittedU, 1e-9)
    }

    /** Eine TEILweise publizierte Menge gibt auch nur den Rest zurueck. */
    @Test
    fun `eine Teilpublikation gibt nur den Rest zurueck`() {
        val a = adapterMitReservierung(menge = 0.30)
        a.episodes.evidenceEpisodeId = ts
        a.episodes.evidenceCommittedU = 0.30

        a.resolveReservation(ts, publishedU = 0.20)

        assertEquals(0.20, a.episodes.evidenceCommittedU, 1e-9)
    }

    /** Und eine vollstaendig publizierte Menge bleibt bezahlt - sonst waere
     *  die Zusicherung oben nur "es wird immer zurueckgegeben". */
    @Test
    fun `eine vollstaendige Publikation bleibt gebucht`() {
        val a = adapterMitReservierung(menge = 0.30)
        a.episodes.evidenceEpisodeId = ts
        a.episodes.evidenceCommittedU = 0.30

        a.resolveReservation(ts, publishedU = 0.30)

        assertEquals(0.30, a.episodes.evidenceCommittedU, 1e-9)
    }

    /**
     * DER ZAEHLER HAENGT AN KEINEM KANAL. Prime und Onset werden nur
     * zurueckgedreht, wenn die Reservierung sie trug - der Evidenz-Zaehler
     * IMMER, weil er jede Abgabe der Episode zaehlt.
     */
    @Test
    fun `der Evidenz-Zaehler wird auch ohne Kanal-Flags zurueckgedreht`() {
        val a = adapterMitReservierung(prime = false, onset = false, meal = false, menge = 0.15)
        a.episodes.evidenceEpisodeId = ts
        a.episodes.evidenceCommittedU = 0.15

        a.resolveReservation(ts, publishedU = 0.0)

        assertEquals(0.0, a.episodes.evidenceCommittedU, 1e-9)
    }

    /** Er wird nie negativ - eine doppelte Aufloesung darf keinen Kredit
     *  erzeugen. */
    @Test
    fun `der Evidenz-Zaehler wird nicht negativ`() {
        val a = adapterMitReservierung(menge = 0.30)
        a.episodes.evidenceEpisodeId = ts
        a.episodes.evidenceCommittedU = 0.10

        a.resolveReservation(ts, publishedU = 0.0)

        assertEquals(0.0, a.episodes.evidenceCommittedU, 1e-9)
    }

    /**
     * ZWEIMAL ABLEHNEN DREHT NUR EINMAL ZURUECK.
     *
     * Die Aufloesung nullt `pendingReservation` als ERSTES; ein zweiter Aufruf
     * findet nichts mehr. Ohne das wuerde ein wiederholtes REJECTED - Retry,
     * doppelter Callback, Wiederaufnahme nach Absturz - den Zaehler unter den
     * wahren Stand druecken und Kredit erzeugen, der nie bezahlt wurde.
     */
    @Test
    fun `eine doppelte Aufloesung dreht nur einmal zurueck`() {
        val a = adapterMitReservierung(menge = 0.30)
        a.episodes.evidenceEpisodeId = ts
        a.episodes.evidenceCommittedU = 0.90        // drei Abgaben in dieser Episode

        a.resolveReservation(ts, publishedU = 0.0)
        a.resolveReservation(ts, publishedU = 0.0)

        assertEquals(0.60, a.episodes.evidenceCommittedU, 1e-9, "nur EINE Reservierung darf zurueck")
        assertNull(a.episodes.pendingReservation)
    }
}

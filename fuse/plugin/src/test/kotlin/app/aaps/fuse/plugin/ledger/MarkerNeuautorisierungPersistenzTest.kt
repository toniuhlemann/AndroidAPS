package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.MarkerReauthorization
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * ABBRUCH UND NEUAUTORISIERUNG UEBER EINEN ECHTEN NEUSTART.
 *
 * Kennung und Widerrufsmarke muessen den Prozess ueberleben - sonst
 * eroeffnet ein App-Neustart eine zweite volle Huelle. Geprueft wird
 * deshalb gegen den ECHTEN Store: schreiben, neu laden, weiterarbeiten.
 *
 * Alle Werte synthetisch.
 */
class MarkerNeuautorisierungPersistenzTest {

    private val t0 = 1_700_000_000_000L

    private fun geladen(dir: File, epoch: String = "epoch-a") =
        FuseLedgerAdapter().also { it.loadOnce(dir, epoch, t0) }

    /** Ein Neustart: schreiben, wegwerfen, aus derselben Datei neu laden. */
    private fun neustart(a: FuseLedgerAdapter, dir: File): FuseLedgerAdapter {
        assertTrue(a.persistVerified(dir)) { "der Zustand muss schreibbar sein" }
        return geladen(dir)
    }

    @Test
    fun `Kennung und Widerrufsmarke ueberleben einen Neustart`(@TempDir dir: File) {
        val a = geladen(dir)
        // Erster Druck.
        a.episodes.markerAuthSeq = 1L
        a.episodes.markerAuth = MarkerReauthorization.Authorization("auth-1", t0)
        val nachErstem = neustart(a, dir)
        assertEquals("auth-1", nachErstem.episodes.markerAuth?.id)
        assertEquals(1L, nachErstem.episodes.markerAuthSeq)

        // Abbruch.
        nachErstem.episodes.markerRevocation =
            MarkerReauthorization.widerrufe(nachErstem.episodes.markerAuth, t0 + 60_000L)
        nachErstem.episodes.markerAuth = null
        val nachAbbruch = neustart(nachErstem, dir)
        assertNull(nachAbbruch.episodes.markerAuth)
        assertNotNull(nachAbbruch.episodes.markerRevocation)
        assertTrue(nachAbbruch.episodes.markerRevocation!!.offen) {
            "die Marke muss OFFEN wieder auftauchen - sonst gibt es keine neue Huelle"
        }
    }

    @Test
    fun `nach dem Verbrauch bleibt die Zuordnung erhalten und oeffnet nichts mehr`(@TempDir dir: File) {
        val a = geladen(dir)
        a.episodes.markerRevocation = MarkerReauthorization.Revocation("auth-1", t0, t0 + 60_000L)
        val neu = MarkerReauthorization.autorisiere(2L, t0 + 120_000L, a.episodes.markerRevocation)
        a.episodes.markerAuthSeq = 2L
        a.episodes.markerAuth = neu.auth
        a.episodes.markerRevocation = neu.revocation
        a.episodes.foundationArmedByAuthId = neu.auth.id

        val nach = neustart(a, dir)
        // Die Zuordnung ist da - nicht bloss geloescht.
        assertEquals("auth-2", nach.episodes.markerRevocation?.consumedByAuthId)
        assertFalse(nach.episodes.markerRevocation!!.offen)
        // Und nach dem Neustart oeffnet dieselbe Autorisierung KEINE zweite.
        assertFalse(
            MarkerReauthorization.neueHuelle(
                nach.episodes.markerAuth, nach.episodes.foundationArmedByAuthId,
            ),
        ) { "ein App-Neustart darf keine zusaetzliche Huelle erzeugen" }
    }

    @Test
    fun `ein Absturz zwischen Ledger und Preferences oeffnet keine zweite Huelle`(@TempDir dir: File) {
        // Die Reihenfolge ist Ledger ZUERST. Bricht der Prozess danach ab,
        // ist die Marke verbraucht, aber kein Marker aktiv.
        val a = geladen(dir)
        a.episodes.markerRevocation = MarkerReauthorization.Revocation("auth-1", t0, t0)
        val neu = MarkerReauthorization.autorisiere(2L, t0 + 60_000L, a.episodes.markerRevocation)
        a.episodes.markerAuthSeq = 2L
        a.episodes.markerAuth = neu.auth
        a.episodes.markerRevocation = neu.revocation
        val nach = neustart(a, dir)   // <- hier endet der Prozess

        // Der naechste Druck findet KEINE offene Marke mehr.
        val danach = MarkerReauthorization.autorisiere(
            nach.episodes.markerAuthSeq + 1L, t0 + 120_000L, nach.episodes.markerRevocation,
        )
        assertFalse(danach.auth.nachWiderruf) {
            "die konservative Richtung: lieber keine Huelle als zwei"
        }
    }

    /**
     * DER MENGENFEHLER, DEN ERST DIE NEUAUTORISIERUNG MOEGLICH MACHT.
     *
     * Alter Auftrag 0,30 U -> Abbruch -> neue Autorisierung, darin 0,40 U
     * verbucht -> erst JETZT kommt der Nicht-Sende-Beweis des alten
     * Auftrags. Ohne Zuordnung zieht die Rueckbuchung vom AKTUELLEN
     * `primeSpentU` ab: aus 0,40 wuerden 0,10, und die neue Huelle waere um
     * eine fremde Menge entlastet.
     *
     * Die alte GLOBALE Belastung muss trotzdem aufgeloest werden.
     */
    @Test
    fun `eine alte Rueckbuchung entlastet die neue Huelle nicht`(@TempDir dir: File) {
        val a = geladen(dir)
        val e = a.episodes
        // Die alte Autorisierung hat 0,30 gebucht und festgeschrieben.
        e.markerAuthSeq = 1L
        e.markerAuth = MarkerReauthorization.Authorization("auth-1", t0)
        e.settled = EpisodeBudgets.Settled(
            proposalId = "p-alt", amountU = 0.30, prime = true, onset = false,
            // mealTs 0: dieser Test prueft die ZUORDNUNG, nicht die Lieferliste.
            mealTs = 0L, foundationPhase = app.aaps.fuse.core.controller.MealFoundation.Phase.PHASE_B,
            authId = "auth-1",
        )
        e.evidenceCommittedU = 0.70
        // Abbruch, neuer Marker, neue Autorisierung - und darin 0,40 verbucht.
        val marke = MarkerReauthorization.widerrufe(e.markerAuth, t0 + 60_000L)!!
        val neu = MarkerReauthorization.autorisiere(2L, t0 + 120_000L, marke)
        e.markerAuthSeq = 2L
        e.markerAuth = neu.auth
        e.markerRevocation = neu.revocation
        e.primeSpentU = 0.40
        e.deliveredSinceHandoverU = 0.40

        val ergebnis = a.revokeSettled("p-alt")

        assertEquals(0.40, e.primeSpentU, 1e-9) {
            "die NEUE Autorisierung muss bei 0,40 bleiben - die 0,30 gehoerten der alten"
        }
        assertEquals(0.40, e.deliveredSinceHandoverU, 1e-9) {
            "und aus einem alten Auftrag entsteht in der neuen Huelle kein Phase-B-Uebertrag"
        }
        // Die GLOBALE Belastung wird trotzdem aufgeloest.
        assertEquals(0.40, e.evidenceCommittedU, 1e-9) {
            "die alte Belastung war echt und muss verschwinden: $ergebnis"
        }
    }

    /**
     * DERSELBE MENGENFEHLER MIT ECHTER LIEFERZEILE, BEIDE PHASEN.
     *
     * Der erste Anlauf setzte `mealTs = 0` und prueft damit ausdruecklich
     * KEINE Lieferzeile - genau deshalb entging ihm, dass die Rueckbuchung
     * bei fremder Autorisierung mitten im Vorgang abbrach: `settled` war
     * entfernt, die globalen Zaehler gesenkt, die Mahlzeitenzeile aber blieb
     * stehen, und der Rueckgabewert meldete "nichts geschehen".
     *
     * Hier laeuft der volle Vorgang. Geprueft wird in BEIDEN Phasen:
     *   - die neue Huelle und ihr Phase-B-Uebertrag bleiben unveraendert,
     *   - die alte Menge verschwindet vollstaendig aus den globalen Buechern
     *     UND aus der Lieferliste,
     *   - der Rueckgabewert entspricht dem, was wirklich geschah.
     *
     * Alle Werte sind synthetisch.
     */
    @Test
    fun `fremde Phase-A-Buchung - global vollstaendig, Huelle unberuehrt`(@TempDir dir: File) {
        val a = geladen(dir)
        val e = a.episodes
        val altTs = t0 - 600_000L
        e.markerAuth = MarkerReauthorization.Authorization("auth-2", t0)
        e.settled = EpisodeBudgets.Settled(
            proposalId = "p-alt", amountU = 0.30, prime = true, onset = true,
            mealTs = altTs,
            foundationPhase = app.aaps.fuse.core.controller.MealFoundation.Phase.PHASE_A,
            authId = "auth-1",
        )
        e.mealDeliveries.add(EpisodeBudgets.MealDelivery(altTs, 0.30, "p-alt"))
        e.mealDeliveries.add(EpisodeBudgets.MealDelivery(t0 + 60_000L, 0.40, "p-neu"))
        // Die NEUE Autorisierung: 0,40 verbucht, Phase-A-Seite belastet.
        e.primeSpentU = 0.40
        e.deliveredPhaseAU = 0.40
        e.deliveredSinceHandoverU = 0.0
        e.confirmedNotSentPhaseAU = 0.0
        e.onsetSpentU = 0.70
        e.evidenceCommittedU = 0.70

        val ergebnis = a.revokeSettled("p-alt")

        // ---- DIE NEUE HUELLE BLEIBT, WIE SIE WAR ------------------------
        assertEquals(0.40, e.primeSpentU, 1e-9) { "fremde Menge darf nicht entlasten" }
        assertEquals(0.40, e.deliveredPhaseAU, 1e-9)
        assertEquals(0.0, e.confirmedNotSentPhaseAU, 1e-9) {
            "aus einem fremden Auftrag entsteht KEIN Phase-B-Uebertrag"
        }

        // ---- GLOBAL VOLLSTAENDIG, EINSCHLIESSLICH LIEFERZEILE -----------
        assertEquals(0.40, e.evidenceCommittedU, 1e-9)
        assertEquals(0.40, e.onsetSpentU, 1e-9)
        assertNull(e.settled)
        assertEquals(1, e.mealDeliveries.size) {
            "die alte Lieferzeile muss verschwinden - sonst widersprechen sich " +
                "globale Zaehler und Mahlzeitensumme: " +
                e.mealDeliveries.joinToString { "${it.proposalId}=${it.amountU}" }
        }
        assertEquals("p-neu", e.mealDeliveries.first().proposalId)

        // ---- DER RUECKGABEWERT SAGT, WAS GESCHAH ------------------------
        assertEquals(0.30, ergebnis.amountU, 1e-9) {
            "es WURDE zurueckgedreht - NONE waere eine falsche Auskunft"
        }
        assertFalse(ergebnis.fundamentEntlastet) {
            "und zwar nur global, nicht in der laufenden Huelle"
        }
    }

    /** Dieselbe Folge in Phase B - dort darf der Uebergabezaehler nicht sinken. */
    @Test
    fun `fremde Phase-B-Buchung - global vollstaendig, Huelle unberuehrt`(@TempDir dir: File) {
        val a = geladen(dir)
        val e = a.episodes
        val altTs = t0 - 600_000L
        e.markerAuth = MarkerReauthorization.Authorization("auth-2", t0)
        e.settled = EpisodeBudgets.Settled(
            proposalId = "p-alt", amountU = 0.30, prime = true, onset = false,
            mealTs = altTs,
            foundationPhase = app.aaps.fuse.core.controller.MealFoundation.Phase.PHASE_B,
            authId = "auth-1",
        )
        e.mealDeliveries.add(EpisodeBudgets.MealDelivery(altTs, 0.30, "p-alt"))
        e.primeSpentU = 0.40
        e.deliveredSinceHandoverU = 0.40
        e.evidenceCommittedU = 0.70

        val ergebnis = a.revokeSettled("p-alt")

        assertEquals(0.40, e.primeSpentU, 1e-9)
        assertEquals(0.40, e.deliveredSinceHandoverU, 1e-9)
        assertEquals(0.40, e.evidenceCommittedU, 1e-9)
        assertTrue(e.mealDeliveries.isEmpty()) { "die Lieferzeile muss auch hier fallen" }
        assertEquals(0.30, ergebnis.amountU, 1e-9)
        assertFalse(ergebnis.fundamentEntlastet)
    }

    /**
     * FEHLENDE KENNUNG IST KEINE FREIGABE.
     *
     * Altbestand aus einer Datei vor der Zuordnung traegt keine Kennung.
     * Solange eine Autorisierung laeuft, ist unbewiesen, ob die Buchung zu
     * ihr gehoert - und unbewiesen heisst: nicht entlasten. Global wird
     * trotzdem vollstaendig zurueckgedreht.
     */
    @Test
    fun `eine Buchung ohne Kennung entlastet die laufende Huelle nicht`(@TempDir dir: File) {
        val a = geladen(dir)
        val e = a.episodes
        val altTs = t0 - 600_000L
        e.markerAuth = MarkerReauthorization.Authorization("auth-2", t0)
        e.settled = EpisodeBudgets.Settled(
            proposalId = "p-alt", amountU = 0.30, prime = true, onset = false,
            mealTs = altTs,
            foundationPhase = app.aaps.fuse.core.controller.MealFoundation.Phase.PHASE_A,
            authId = null,
        )
        e.mealDeliveries.add(EpisodeBudgets.MealDelivery(altTs, 0.30, "p-alt"))
        e.primeSpentU = 0.40
        e.deliveredPhaseAU = 0.40
        e.evidenceCommittedU = 0.70

        val ergebnis = a.revokeSettled("p-alt")

        assertEquals(0.40, e.primeSpentU, 1e-9) {
            "ohne Kennung ist die Zugehoerigkeit unbewiesen"
        }
        assertEquals(0.40, e.deliveredPhaseAU, 1e-9)
        assertEquals(0.0, e.confirmedNotSentPhaseAU, 1e-9)
        assertEquals(0.40, e.evidenceCommittedU, 1e-9) { "global trotzdem vollstaendig" }
        assertTrue(e.mealDeliveries.isEmpty())
        assertFalse(ergebnis.fundamentEntlastet)
    }

    /**
     * DER EINE BELEGBARE FALL OHNE KENNUNG: es laeuft gar keine
     * Autorisierung. Dann gibt es keine fremde Huelle, die entlastet werden
     * koennte - die Zaehler gehoeren niemandem, und das Zurueckdrehen stellt
     * den Stand vor der Buchung wieder her.
     */
    @Test
    fun `ohne laufende Autorisierung entlastet auch eine Buchung ohne Kennung`(@TempDir dir: File) {
        val a = geladen(dir)
        val e = a.episodes
        e.markerAuth = null
        e.settled = EpisodeBudgets.Settled(
            proposalId = "p-alt", amountU = 0.30, prime = true, onset = false,
            mealTs = 0L,
            foundationPhase = app.aaps.fuse.core.controller.MealFoundation.Phase.PHASE_B,
            authId = null,
        )
        e.primeSpentU = 0.40
        e.deliveredSinceHandoverU = 0.40
        e.evidenceCommittedU = 0.70

        val ergebnis = a.revokeSettled("p-alt")

        assertEquals(0.10, e.primeSpentU, 1e-9)
        assertEquals(0.10, e.deliveredSinceHandoverU, 1e-9)
        assertTrue(ergebnis.fundamentEntlastet)
    }

    /**
     * UND DIE PASSENDE ZUORDNUNG IN PHASE A FUNKTIONIERT WEITERHIN - mit
     * echter Lieferzeile und dem Uebertrag, um den es dort geht.
     */
    @Test
    fun `die eigene Phase-A-Buchung entlastet und erzeugt den Uebertrag`(@TempDir dir: File) {
        val a = geladen(dir)
        val e = a.episodes
        val ts = t0 - 300_000L
        e.markerAuth = MarkerReauthorization.Authorization("auth-2", t0)
        e.foundation = app.aaps.fuse.core.controller.MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = 4.0, phaseAShare = 0.75,
            phaseAUpfrontShare = 0.0, primeWindowMin = 15, wallCeilingMin = 45,
            phaseBUntilMin = 60, pressObservedInThisProcess = true,
            primeDeclinedByUser = false, markerAuthorized = true,
        )
        e.settled = EpisodeBudgets.Settled(
            proposalId = "p-eigen", amountU = 0.30, prime = true, onset = false,
            mealTs = ts,
            foundationPhase = app.aaps.fuse.core.controller.MealFoundation.Phase.PHASE_A,
            authId = "auth-2",
        )
        e.mealDeliveries.add(EpisodeBudgets.MealDelivery(ts, 0.30, "p-eigen"))
        e.primeSpentU = 0.40
        e.deliveredPhaseAU = 0.40
        e.evidenceCommittedU = 0.70

        val ergebnis = a.revokeSettled("p-eigen")

        assertEquals(0.10, e.primeSpentU, 1e-9)
        assertEquals(0.10, e.deliveredPhaseAU, 1e-9)
        assertEquals(0.30, e.confirmedNotSentPhaseAU, 1e-9) {
            "der Phase-A-Uebertrag entsteht genau hier"
        }
        assertEquals(0.40, e.evidenceCommittedU, 1e-9)
        assertTrue(e.mealDeliveries.isEmpty())
        assertEquals(0.30, ergebnis.amountU, 1e-9)
        assertTrue(ergebnis.fundamentEntlastet)
    }

    @Test
    fun `eine Rueckbuchung der LAUFENDEN Autorisierung entlastet weiterhin`(@TempDir dir: File) {
        val a = geladen(dir)
        val e = a.episodes
        e.markerAuthSeq = 2L
        e.markerAuth = MarkerReauthorization.Authorization("auth-2", t0)
        e.settled = EpisodeBudgets.Settled(
            proposalId = "p-neu", amountU = 0.30, prime = true, onset = false,
            // mealTs 0: dieser Test prueft die ZUORDNUNG, nicht die Lieferliste.
            mealTs = 0L, foundationPhase = app.aaps.fuse.core.controller.MealFoundation.Phase.PHASE_B,
            authId = "auth-2",
        )
        e.primeSpentU = 0.40
        e.deliveredSinceHandoverU = 0.40
        e.evidenceCommittedU = 0.70

        a.revokeSettled("p-neu")

        assertEquals(0.10, e.primeSpentU, 1e-9) {
            "hier gehoert die Buchung zur laufenden Autorisierung - sie entlastet"
        }
        assertEquals(0.10, e.deliveredSinceHandoverU, 1e-9)
    }

    @Test
    fun `Altbestand ohne die neuen Felder liest sich als keine Autorisierung`(@TempDir dir: File) {
        // Eine Datei aus der Zeit vor diesem Vertrag: die Felder fehlen.
        val a = geladen(dir)
        assertTrue(a.persistVerified(dir))
        val datei = File(dir, FuseLedgerStore.FILE_NAME)
        val roh = datei.readText()
        assertTrue(roh.contains("markerAuthSeq")) { "der Schreiber muss die Felder fuehren" }

        val ohne = roh
            .replace("\"markerAuthSeq\"", "\"markerAuthSeqAlt\"")
            .replace("\"markerAuth\"", "\"markerAuthAlt\"")
            .replace("\"markerRevocation\"", "\"markerRevocationAlt\"")
        datei.writeText(ohne)
        val gelesen = geladen(dir)
        assertNull(gelesen.episodes.markerAuth)
        assertNull(gelesen.episodes.markerRevocation)
        assertEquals(0L, gelesen.episodes.markerAuthSeq)
        assertFalse(MarkerReauthorization.neueHuelle(gelesen.episodes.markerAuth, null)) {
            "ohne Autorisierung keine zusaetzliche Huelle - die konservative Richtung"
        }
    }
}

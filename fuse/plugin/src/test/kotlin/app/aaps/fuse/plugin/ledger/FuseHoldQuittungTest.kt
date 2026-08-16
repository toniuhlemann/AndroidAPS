package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.ledger.LedgerError
import app.aaps.fuse.core.ledger.LedgerErrorRecord
import app.aaps.fuse.core.ledger.LedgerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER ZWEITE AUSGANG AUS EINEM LEDGER-HOLD (Auditbefund P0-1a, 16.08.2026).
 *
 * Der gesamte Quittungspfad war fertig gebaut, geprueft und ueber den Codec
 * sogar neustartfest - es fehlte ausschliesslich ein ERZEUGER. `reduce` ist
 * privat, also konnte keine Oberflaeche das Ereignis einspeisen; die einzigen
 * Aufrufer von `HoldAcknowledged` im ganzen Repo standen in Tests. Der Hold war
 * damit an Tonis Medtrum eine Sackgasse, denn die Reparatur, auf die Meldung
 * und Tab verwiesen, verweigert ohne nachgewiesene VirtualPump.
 *
 * DIE GEFAEHRLICHE SEITE ist nicht die Quittung, sondern WAS ANGEBOTEN WIRD.
 * Eine Quittung auf eine Zeile, die es nicht gibt, laeuft in
 * `LedgerReducer.kt:147-151` und erzeugt dort einen NEUEN fail-closed
 * `UNKNOWN_PROPOSAL` samt erhoehter `holdGeneration` - der Bedienfehler machte
 * die Lage schlechter und entwertete zugleich jede parallel vorbereitete
 * Quittung.
 */
class FuseHoldQuittungTest {

    private fun rec(
        id: String?,
        error: LedgerError,
        active: Boolean = true,
    ) = LedgerErrorRecord(id, error, "d", "d", 1, active = active)

    /** Immer wahr - fuer Faelle, in denen die Zeilenfrage nicht der Prueffall ist. */
    private val jedeZeileExistiert: (String) -> Boolean = { true }

    // ---- Was angeboten werden darf ---------------------------------------

    /**
     * DIE KERNZUSICHERUNG. `SNAPSHOT_ORDER_CONFLICT` steht in
     * `RECOVERABLE_ERRORS`, wird aber ausschliesslich GLOBAL erhoben
     * (`proposalId = null`). Wuerde er angeboten, liefe die Quittung ins Leere.
     */
    @Test
    fun `ein globaler Fehler ohne Zeile wird nie angeboten`() {
        val offen = HoldQuittungAuswahl.quittierbar(
            listOf(rec(null, LedgerError.SNAPSHOT_ORDER_CONFLICT)),
            jedeZeileExistiert,
        )
        assertTrue(offen.isEmpty(), "ein Fehler ohne proposalId darf nicht angeboten werden: $offen")
    }

    /**
     * Und derselbe Riegel, wenn die Id zwar dasteht, die ZEILE aber fehlt -
     * genau die Lage bei `UNKNOWN_PROPOSAL` und bei `NON_FINITE_AMOUNT` aus
     * `onProposed`.
     */
    @Test
    fun `eine Id ohne Zeile wird nie angeboten`() {
        val fehler = listOf(
            rec("weg", LedgerError.UNKNOWN_PROPOSAL),
            rec("weg", LedgerError.NON_FINITE_AMOUNT),
        )
        val offen = HoldQuittungAuswahl.quittierbar(fehler) { false }
        assertTrue(offen.isEmpty(), "ohne Zeile darf nichts angeboten werden: $offen")
    }

    /**
     * Der Gegenbeweis, dass der Filter nicht einfach alles wegwirft - sonst
     * waere der neue Ausgang wieder zu, nur leiser. Das ist die
     * Positivkontrolle, ohne die die Tests darueber nichts wert waeren.
     */
    @Test
    fun `ein quittierbarer Fehler mit Zeile wird angeboten`() {
        val offen = HoldQuittungAuswahl.quittierbar(
            listOf(rec("p1", LedgerError.MISSING_ACCOUNTED_TREATMENT)),
            jedeZeileExistiert,
        )
        assertEquals(1, offen.size, "der Regelfall muss durchkommen")
        assertEquals("p1", offen.first().proposalId)
    }

    /** Ein erledigter Fehler ist kein Grund mehr - er darf nicht auftauchen. */
    @Test
    fun `ein bereits quittierter Fehler wird nicht erneut angeboten`() {
        val offen = HoldQuittungAuswahl.quittierbar(
            listOf(rec("p1", LedgerError.MISSING_ACCOUNTED_TREATMENT, active = false)),
            jedeZeileExistiert,
        )
        assertTrue(offen.isEmpty(), "inaktive Fehler gehoeren nicht ins Angebot: $offen")
    }

    /**
     * Die harten Widersprueche brauchen eine Reparatur, keine Unterschrift -
     * der Reducer weist sie ohnehin ab (`LedgerReducer.kt:372-373`), aber
     * anzubieten, was sicher scheitert, waere ein falsches Versprechen.
     */
    @Test
    fun `nicht quittierbare Fehlerarten bleiben draussen`() {
        val hart = listOf(
            LedgerError.IMPOSSIBLE_STATE_CONFLICT,
            LedgerError.IDENTITY_CONFLICT,
            LedgerError.CONSTRAINT_CHAIN_INVALID,
            LedgerError.OVERDELIVERY_ANOMALY,
            LedgerError.ACCOUNTING_WITHOUT_IDENTITY,
        )
        hart.forEach { e ->
            assertTrue(
                e !in LedgerState.RECOVERABLE_ERRORS,
                "$e gilt im Kern als quittierbar - dann stimmt dieser Test nicht mehr",
            )
            val offen = HoldQuittungAuswahl.quittierbar(listOf(rec("p1", e)), jedeZeileExistiert)
            assertTrue(offen.isEmpty(), "$e darf nicht angeboten werden")
        }
    }

    /** Aus mehreren gemischten Zeilen darf nur das Zulaessige uebrig bleiben. */
    @Test
    fun `aus einer gemischten Lage bleibt nur das Zulaessige`() {
        val fehler = listOf(
            rec(null, LedgerError.SNAPSHOT_ORDER_CONFLICT),          // global
            rec("weg", LedgerError.UNKNOWN_PROPOSAL),                // Zeile fehlt
            rec("p1", LedgerError.IMPOSSIBLE_STATE_CONFLICT),        // nicht quittierbar
            rec("p1", LedgerError.MISSING_ACCOUNTED_TREATMENT, false), // erledigt
            rec("p1", LedgerError.PHASE_VIOLATION),                  // <- nur dieser
        )
        val offen = HoldQuittungAuswahl.quittierbar(fehler) { it == "p1" }
        assertEquals(1, offen.size, "erwartet genau einen: $offen")
        assertEquals(LedgerError.PHASE_VIOLATION, offen.first().error)
    }

    // ---- Der Vormerk-Halter ----------------------------------------------

    private fun auftrag(id: String = "p1", gen: Long = 0L) =
        FuseHoldQuittungScheduler.Auftrag(
            proposalId = id, by = "Bediener", reason = "geloeschte Behandlung",
            errors = setOf(LedgerError.MISSING_ACCOUNTED_TREATMENT),
            expectedHoldGeneration = gen,
        )

    @Test
    fun `zweimal tippen ergibt nur eine Quittung`() {
        val s = FuseHoldQuittungScheduler()
        assertTrue(s.request(auftrag()), "die erste Zustimmung muss angenommen werden")
        assertFalse(s.request(auftrag("p2")), "die zweite darf NICHT eingereiht werden")
        var gesehen: String? = null
        s.runIfDue { gesehen = it.proposalId; true }
        assertEquals("p1", gesehen, "ausgefuehrt wird die ERSTE Zustimmung")
    }

    @Test
    fun `ohne Auftrag passiert nichts`() {
        val s = FuseHoldQuittungScheduler()
        var gelaufen = false
        val r = s.runIfDue { gelaufen = true; true }
        assertEquals(null, r, "ohne Auftrag gibt es kein Ergebnis")
        assertFalse(gelaufen, "ohne Auftrag darf nichts ausgefuehrt werden")
    }

    /**
     * Eine Zustimmung gilt EINMAL. Wirft die Ausfuehrung, wird sie nicht
     * automatisch wiederholt - das waere eine Zustimmung, die der Bediener nur
     * einmal gegeben hat. Der Hold bleibt stehen, er entscheidet neu.
     */
    @Test
    fun `ein geworfener Auftrag ist trotzdem verbraucht`() {
        val s = FuseHoldQuittungScheduler()
        s.request(auftrag())
        runCatching { s.runIfDue { throw IllegalStateException("Ledger kaputt") } }
        assertFalse(s.isPending, "der Auftrag darf nach dem Wurf nicht erneut anstehen")
        var zweiterLauf = false
        s.runIfDue { zweiterLauf = true; true }
        assertFalse(zweiterLauf, "er darf sich nicht von selbst wiederholen")
    }

    @Test
    fun `nach der Ausfuehrung steht wieder nichts an`() {
        val s = FuseHoldQuittungScheduler()
        s.request(auftrag())
        assertTrue(s.isPending)
        s.runIfDue { true }
        assertFalse(s.isPending, "ein ausgefuehrter Auftrag muss verbraucht sein")
    }

    /** Der Grund und die benannten Fehler wandern MIT - sie spaeter neu zu
     *  bilden waere ein anderes Protokoll als die erteilte Zustimmung. */
    @Test
    fun `der Auftrag traegt Grund Fehler und Generation bis zur Ausfuehrung`() {
        val s = FuseHoldQuittungScheduler()
        s.request(auftrag(gen = 7L))
        var gesehen: FuseHoldQuittungScheduler.Auftrag? = null
        s.runIfDue { gesehen = it; true }
        assertEquals("geloeschte Behandlung", gesehen?.reason)
        assertEquals(setOf(LedgerError.MISSING_ACCOUNTED_TREATMENT), gesehen?.errors)
        assertEquals(7L, gesehen?.expectedHoldGeneration, "die Generation der Zustimmung muss mitwandern")
    }
}

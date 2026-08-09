package app.aaps.fuse.plugin.ledger

import app.aaps.core.interfaces.aps.RT
import java.io.File

/**
 * PUBLIKATIONS-GATING (Audit 2d273cb, REG-01a / 6.1): zwischen den
 * Ledger-Ereignissen eines Zyklus und der Publikation des RT MUSS ein
 * VERIFIZIERTER Persist liegen. Ein SMB, dessen Commitment nicht auf Platte
 * steht, existiert nach einem Prozess-Tod in keiner Buchhaltung mehr - die
 * Huelle stuende ein zweites Mal zur Verfuegung (Doppelfinanzierung, genau
 * der REG-01-Pfad).
 *
 * Verhalten bei nicht-durablem Ledger (Persist-Fehlschlag ODER Wurf in einem
 * Ledger-Schritt): das RT wird OHNE SMB publiziert (units/deliverAt
 * entfernt), die TBR-Felder bleiben erhalten - die Safety-TBR (Null-Temp)
 * ist die konservative Richtung und darf nicht mit verloren gehen. Der Grund
 * wird an reason angehaengt, damit Trail und Nightscout den Eingriff tragen.
 *
 * Eigene Datei statt Inline-Code in FusePlugin.invoke, damit das Gating ohne
 * Android gegen einen echten Store (unbeschreibbares Verzeichnis) pruefbar
 * ist - der Store-Test allein beweist nur `false`, nicht die gesperrte
 * Publikation (Codex 4.4).
 */
object LedgerPublicationGate {

    /**
     * G2 (Codex-Adjudication bae885f1): ein Hold, den erst die Ereignisse
     * DIESES Zyklus erzeugt haben (z.B. MISSING_ACCOUNTED_TREATMENT aus der
     * Reconciliation). Eigener Grund, weil es KEIN Persist-Fehlschlag ist -
     * die Buchhaltung steht durabel, sie sagt nur "nicht dosieren".
     */
    const val REASON_LATE_HOLD = "LEDGER_HOLD"

    /**
     * Der Zyklus hat eine Menge gerechnet, aber die Behandlungs-Vollsicht
     * dieses Zyklus fehlt (B0a).
     *
     * `FuseCycleRunner` liefert `treatmentView = runCatching { … }.getOrNull()`;
     * eine scheiternde Datenbankabfrage darf den Zyklus nicht kosten. Ohne
     * Vollsicht laufen aber Bindung, Reconciliation und prune nicht - und der
     * C5-Anker des Vorschlags waere unbekannt. Eine Menge in diesem Zustand
     * hinauszugeben hiesse, Haftung einzugehen, deren Gegenbuchung dieser
     * Zyklus nicht leisten kann.
     */
    const val REASON_TREATMENT_VIEW_UNAVAILABLE = "TREATMENT_VIEW_UNAVAILABLE"

    /**
     * Die erwartete Zeile steht nach den Ereignissen NICHT im Ledger (B0a).
     *
     * Reine Rueckfallsicherung: sie sollte nie feuern, wenn der Aufrufer
     * seinen eigenen Vertrag einhaelt. Genau deshalb steht sie hier - ein
     * Vertrag, den niemand nachprueft, ist eine Behauptung.
     */
    const val REASON_PROPOSAL_MISSING = "LEDGER_PROPOSAL_MISSING"

    /**
     * Fuehrt die Ledger-Ereignisse des Zyklus aus, persistiert verifiziert
     * und liefert das publikationsfaehige RT.
     *
     * KEIN Pfad publiziert positive units bei nicht-durablem Ledger:
     *  - [events] wirft -> units werden entfernt (der Zustand im Speicher
     *    kann dann Verbindlichkeiten tragen, die das RT nie ausliefert -
     *    konservative Richtung, die Zeile bleibt offen).
     *  - [FuseLedgerAdapter.persistVerified] schlaegt fehl -> units werden
     *    entfernt UND persistFailed haelt kuenftige Zyklen ueber view().hold
     *    zu (den konsumiert der LedgerHoldGate im Runner).
     *  - die Ereignisse selbst erzeugen einen HOLD (G2) -> units werden
     *    entfernt, obwohl Events und Persist gelungen sind. Der Hold des
     *    Zyklus wirkt damit IM Zyklus, nicht erst im naechsten.
     *
     * Der Persist laeuft auch nach einem Wurf in [events]: der Reducer
     * arbeitet immutabel, nach einem Wurf steht der letzte konsistente
     * Zwischenstand - und der ist auf Platte mehr wert als im Speicher.
     */
    /**
     * WAS DIESER ZYKLUS BUCHT (B0a).
     *
     * Vorher war das eine unausgesprochene Annahme: das Gate schaute auf
     * Ereignis-Erfolg und Persist-Erfolg und schloss daraus, dass eine Haftung
     * entstanden sei. Beide koennen aber fehlerfrei durchlaufen, OHNE dass
     * eine Zeile entstanden ist. Der Aufrufer sagt deshalb jetzt ausdruecklich,
     * was er bucht - und das Gate prueft es nach.
     */
    sealed interface Commitment {

        /** Dieser Zyklus bucht genau diese Zeile. */
        data class Proposal(val proposalId: String) : Commitment

        /** Dieser Zyklus bucht KEINE Zeile - dann duerfen auch keine units
         *  hinausgehen. [reason] steht im Trail und benennt, warum. */
        data class None(val reason: String) : Commitment
    }

    /**
     * @param expected was dieser Zyklus zu buchen behauptet. Traegt das RT
     *   positive units, MUSS es [Commitment.Proposal] sein - und die Zeile muss
     *   danach wirklich offen im Ledger stehen.
     */
    fun publish(
        rt: RT,
        adapter: FuseLedgerAdapter,
        dir: File,
        expected: Commitment,
        events: () -> Unit,
        onError: (Throwable) -> Unit = {},
    ): RT {
        val eventsOk = try {
            events()
            true
        } catch (e: Exception) {
            onError(e)
            false
        }
        // Der Persist laeuft IMMER, auch ohne Vorschlag und nach einem Wurf:
        // die Reconciliation dieses Zyklus gehoert auf Platte, und ein
        // Fehlschlag muss ueber persistFailed sticky werden.
        val persisted = adapter.persistVerified(dir)
        if (rt.units == null) return rt

        // Ab hier traegt das RT eine Menge - jeder Ausgang ausser dem letzten
        // entfernt sie.
        val proposalId = when (expected) {
            is Commitment.None     -> return strip(rt, expected.reason)
            is Commitment.Proposal -> expected.proposalId
        }
        if (!eventsOk || !persisted) return strip(rt, FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED)
        // B0a: erst JETZT ist belegt, dass die Haftung nicht nur beabsichtigt,
        // sondern gebucht UND durabel ist - der Persist oben lief nach den
        // Ereignissen, die Zeile war also in der geschriebenen Generation.
        if (!adapter.hasOpenProposal(proposalId)) return strip(rt, "$REASON_PROPOSAL_MISSING:$proposalId")
        // G2 (Codex-Adjudication bae885f1): FRISCHE Hold-Pruefung NACH der
        // Reconciliation. Vorher kam das positive RT unveraendert zurueck,
        // sobald Events und Persist gelungen waren - ein waehrend der
        // Ereignisse entdeckter Hold (z.B. MISSING_ACCOUNTED_TREATMENT)
        // griff erst im NAECHSTEN Zyklus, also eine Dosis zu spaet.
        val view = adapter.view()
        if (!view.hold) return rt
        return strip(rt, REASON_LATE_HOLD + (view.holdReason?.let { ":$it" } ?: ""))
    }

    /** Der SMB faellt weg, die TBR-Felder bleiben - die Safety-TBR (Null-Temp)
     *  ist die konservative Richtung und darf nicht mit verloren gehen. */
    private fun strip(rt: RT, reason: String): RT {
        // data-class copy teilt reason (StringBuilder) mit dem Original -
        // das Original wird verworfen, der Anhang landet also genau einmal
        // in der publizierten Zeile.
        val stripped = rt.copy(units = null, deliverAt = null)
        stripped.reason.append(" | ").append(reason)
        return stripped
    }
}

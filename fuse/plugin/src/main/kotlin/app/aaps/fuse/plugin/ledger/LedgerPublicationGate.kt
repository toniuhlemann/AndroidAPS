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
    fun publish(
        rt: RT,
        adapter: FuseLedgerAdapter,
        dir: File,
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
        val persisted = adapter.persistVerified(dir)
        if (rt.units == null) return rt
        if (eventsOk && persisted) {
            // G2 (Codex-Adjudication bae885f1): FRISCHE Hold-Pruefung NACH der
            // Reconciliation. Vorher kam das positive RT unveraendert zurueck,
            // sobald Events und Persist gelungen waren - ein waehrend der
            // Ereignisse entdeckter Hold (z.B. MISSING_ACCOUNTED_TREATMENT)
            // griff erst im NAECHSTEN Zyklus, also eine Dosis zu spaet.
            val view = adapter.view()
            if (!view.hold) return rt
            return strip(rt, REASON_LATE_HOLD + (view.holdReason?.let { ":$it" } ?: ""))
        }
        return strip(rt, FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED)
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

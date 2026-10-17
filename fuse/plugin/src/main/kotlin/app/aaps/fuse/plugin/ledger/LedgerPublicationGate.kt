package app.aaps.fuse.plugin.ledger

import app.aaps.core.interfaces.aps.RT
import app.aaps.fuse.core.controller.InterventionStamp
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
     * Die Buchungsentscheidung EINES Zyklus - als eine pruefbare Funktion
     * statt als Bedingung mitten in `invoke()` (B0a).
     *
     * Sie steht hier und nicht im Plugin, weil sie mit [Commitment] dieselbe
     * Vokabel spricht und weil der Aufrufer den events-Block DARAUS ableiten
     * soll: zwei unabhaengig formulierte Bedingungen ("darf publizieren" und
     * "soll buchen") koennen abdriften, und beide Driftrichtungen sind
     * schaedlich - die eine publiziert ohne Haftung, die andere bucht eine
     * Haftung fuer eine Menge, die nie hinausgeht (Phantom-Commitment).
     *
     * @param units die Menge des RT, `null` = keine.
     * @param treatmentViewPresent traegt der Zyklus eine Behandlungs-Vollsicht?
     */
    /**
     * B3: bei einer REALEN Pumpe mit unbekannter Patch-Epoche wird kein
     * positiver SMB publiziert.
     *
     * Ohne bekannte Epoche liesse sich der Bolus hinterher keiner Zeile
     * zuordnen - er wuerde also weder gebunden noch ausgebucht und bliebe als
     * Haftung stehen, waehrend sein Insulin wirkt. Publizieren waere hier
     * dosieren ohne Buchfuehrung.
     *
     * NUR DER SMB. Der Weg laeuft ueber [Commitment.None] und damit ueber
     * `strip`, das ausdruecklich nur `units`/`deliverAt` entfernt und die
     * TBR-Felder stehen laesst: eine Schutz-Null darf nicht wegen eines
     * BOLUS-Provenienzproblems verschwinden. Das ist dieselbe Trennung wie
     * beim fehlenden Vollsicht-Fall.
     */
    const val REASON_REAL_PUMP_EPOCH_UNKNOWN = "REAL_PUMP_EPOCH_UNKNOWN"

    /**
     * ECHTE, ERLAUBTE PUMPE - ABER OHNE IDENTITAET.
     *
     * Ohne Serial entsteht ein Pin ohne Identitaet, und den behandelt
     * `matchesPinnedEpoch` als WILDCARD: die Zeile bindet jeden typgleichen
     * Bolus. An der VirtualPump ist das noetig (der leere Serial nach dem
     * Prozessstart), an einer echten Pumpe ist es ein Freibrief - eine
     * Haftung koennte ueber einen fremden Fakt ausgebucht werden.
     *
     * EIGENER Grund neben der unbekannten Epoche: im Trail muss
     * unterscheidbar bleiben, ob die IDENTITAET oder die EPOCHE fehlt - das
     * sind zwei verschiedene Ursachen mit zwei verschiedenen Massnahmen.
     * Wie dort bleiben die TBR-Felder stehen: ein Provenienzproblem des
     * BOLUS darf keine Schutz-Null verhindern.
     */
    const val REASON_REAL_PUMP_IDENTITY_UNKNOWN = "REAL_PUMP_IDENTITY_UNKNOWN"

    fun commitmentOf(
        units: Double?,
        treatmentViewPresent: Boolean,
        proposalId: String,
        realPumpEpochUnknown: Boolean = false,
        realPumpIdentityUnknown: Boolean = false,
    ): Commitment = when {
        units == null                  -> Commitment.Proposal(proposalId)
        // REIHENFOLGE IST DIAGNOSE, wie schon bei PERSIST_FAILED: der laenger
        // wirkende Befund zuerst. Eine unbekannte Patch-Epoche bleibt ueber
        // Zyklen bestehen, bis ein Wechsel gelesen wird; eine fehlende
        // Vollsicht betrifft nur diesen einen. Stuende sie vorn, truege der
        // Trail bei gleichzeitigem Auftreten den kurzlebigeren Grund.
        // Die IDENTITAET zuerst: ohne sie ist die Epochenfrage gar nicht
        // zu stellen - eine Epoche gehoert immer zu einem bestimmten
        // Geraet. Stuende die Epoche vorn, truege der Trail bei
        // gleichzeitigem Auftreten den abgeleiteten statt des
        // urspruenglichen Grundes.
        realPumpIdentityUnknown        -> Commitment.None(REASON_REAL_PUMP_IDENTITY_UNKNOWN)
        realPumpEpochUnknown           -> Commitment.None(REASON_REAL_PUMP_EPOCH_UNKNOWN)
        !treatmentViewPresent          -> Commitment.None(REASON_TREATMENT_VIEW_UNAVAILABLE)
        else                           -> Commitment.Proposal(proposalId)
    }

    /**
     * DAS ERGEBNIS DES GATES ALS DATEN (B0c).
     *
     * Vorher gab `publish` nur das RT zurueck, und der Grund einer
     * Zurueckhaltung stand ausschliesslich als angehaengter Text im
     * `rt.reason`-StringBuilder. Der Trail konnte ihn nicht sehen, ohne den
     * Grundtext nachtraeglich zu zerlegen - und ein Text, den eine Auswertung
     * parsen muss, ist kein Zustand, sondern eine Vermutung ueber einen.
     *
     * @param allowed hat das RT das Gate UNVERAENDERT verlassen? Bei einem RT
     *   ohne units ist das trivialerweise true - es gab nichts zu schuetzen;
     *   [reason] unterscheidet die beiden Faelle.
     * @param reason `null`, wenn nichts entfernt wurde.
     */
    data class Outcome(
        val rt: RT,
        val allowed: Boolean,
        val reason: String?,
        /**
         * OB DAS VERSIEGELN GELUNGEN IST - unabhaengig davon, ob dieser Zyklus
         * ueberhaupt etwas publizieren wollte.
         *
         * Bisher war das nur mittelbar sichtbar: ohne `units` gab das Gate
         * `allowed = true` zurueck, auch wenn der Persist gescheitert war. Fuer
         * die Menge war das richtig - es gab keine. Fuer den ZUSTAND ist es
         * falsch: der Aufrufer muss den berechneten Folgezustand dann
         * zurueckrollen, und dazu braucht er die Tatsache und nicht ihren
         * Nebeneffekt.
         */
        val sealed: Boolean,
    )

    private fun passed(rt: RT, sealed: Boolean) = Outcome(rt, allowed = true, reason = null, sealed = sealed)

    private fun stripped(rt: RT, reason: String, sealed: Boolean) =
        Outcome(strip(rt, reason), allowed = false, reason = reason, sealed = sealed)

    /**
     * @param expected was dieser Zyklus zu buchen behauptet. Traegt das RT
     *   positive units, MUSS es [Commitment.Proposal] sein - und die Zeile muss
     *   danach wirklich offen im Ledger stehen.
     */
    /**
     * @param published was dieser Zyklus tatsaechlich hinausgibt - Bolusmenge
     *   und ob die Pumpe danach ANDERS faehrt als zuvor. Ohne Default: eine
     *   vergessene Angabe waere ein stiller Nachweisfehler.
     */
    fun publish(
        rt: RT,
        adapter: FuseLedgerAdapter,
        dir: File,
        expected: Commitment,
        published: InterventionStamp.Published,
        events: () -> Unit,
        onError: (Throwable) -> Unit = {},
    ): Outcome {
        val eventsOk = try {
            events()
            true
        } catch (e: Exception) {
            onError(e)
            false
        }

        // DAS VORAB-URTEIL (Toni 18.08., Punkt 4 des Publikationsvertrags).
        //
        // Drei der vier Gruende, aus denen die Menge spaeter wegfaellt, stehen
        // schon JETZT fest - vor dem Versiegeln. Sie hier zu bilden kostet
        // nichts und erspart es, den Eingriffsstempel fuer Zyklen
        // fortzuschreiben, in denen nachweislich nichts hinausgeht. Jeder
        // solche Schritt kostet lambda-Nachweis, und bei einem laenger
        // stehenden Hold waere das jeder Zyklus.
        //
        // Der vierte Grund - der Persist selbst - laesst sich nicht vorab
        // wissen. Dort wird ueberzaehlt, und genau dann ist der Schritt
        // ohnehin nicht durabel: die Fehlerrichtung faellt mit sich selbst
        // zusammen.
        val vorabVeto: String? = when {
            !eventsOk                                                   -> null // erst nach dem Persist einordnen
            expected is Commitment.None                                 -> expected.reason
            expected is Commitment.Proposal &&
                !adapter.hasOpenProposal(expected.proposalId)            -> "$REASON_PROPOSAL_MISSING:${expected.proposalId}"
            else                                                        -> adapter.view()
                .takeIf { it.hold }
                ?.let { REASON_LATE_HOLD + (it.holdReason?.let { r -> ":$r" } ?: "") }
        }
        // GEHT ETWAS HINAUS, WIRD VORHER GESTEMPELT. Die Reihenfolge ist der
        // ganze Punkt: erst der neue Stand, dann das Siegel, dann das RT.
        // Ein Bolus, dessen Stempel nur im Speicher stand, waere nach einem
        // Prozesstod ein Eingriff, den niemand mehr sieht - und die offene
        // Prognose wuerde als MISSED abgerechnet, obwohl Insulin lief.
        //
        // ZWEI ACHSEN, GETRENNT BEURTEILT. Der SMB faellt bei jedem Veto weg;
        // die TBR ueberlebt JEDEN Strip-Pfad (`strip` entfernt ausdruecklich
        // nur units/deliverAt, damit eine Schutz-Null nicht an einem
        // Buchungsproblem verlorengeht). Also zaehlt die TBR immer, sobald
        // sie sich wirklich aendert - auch nach einem Wurf in `events`, denn
        // die Pumpe faehrt danach trotzdem anders.
        val smbGehtHinaus = eventsOk && vorabVeto == null && rt.units != null
        adapter.merkeIntervention(
            InterventionStamp.Published(
                smbU = if (smbGehtHinaus) published.smbU else 0.0,
                tbrChanged = published.tbrChanged,
            ),
        )

        // Der Persist laeuft IMMER, auch ohne Vorschlag und nach einem Wurf:
        // die Reconciliation dieses Zyklus gehoert auf Platte, und ein
        // Fehlschlag muss ueber persistFailed sticky werden.
        val persisted = adapter.persistVerified(dir)
        if (rt.units == null) return passed(rt, persisted)

        // Ab hier traegt das RT eine Menge - jeder Ausgang ausser dem letzten
        // entfernt sie.
        //
        // REIHENFOLGE IST DIAGNOSE (B0c): der Persist-Fehlschlag steht VOR dem
        // fehlenden Commitment. Beide Wege entfernen dieselbe Menge, die
        // Dosierung ist also unberuehrt - aber ein nicht durabler Ledger ist
        // der schwerwiegendere und laenger wirkende Befund (er sperrt ueber
        // `persistFailed` auch die FOLGEZYKLEN), waehrend eine fehlende
        // Vollsicht nur diesen einen Zyklus betrifft. Stuende sie vorn, truege
        // der Trail bei gleichzeitigem Auftreten den harmloseren Grund.
        if (!eventsOk || !persisted) return stripped(rt, FuseLedgerAdapter.HOLD_REASON_PERSIST_FAILED, persisted)
        // AB HIER NUR NOCH DAS VORAB-URTEIL. Die Bedingungen zweimal zu
        // formulieren waere genau die Drift, vor der `commitmentOf` schon
        // einmal gewarnt hat: zwei Stellen mit eigener Vorstellung davon, was
        // hinausgehen darf, laufen mit dem naechsten Feld auseinander.
        vorabVeto?.let { return stripped(rt, it, persisted) }
        return passed(rt, persisted)
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

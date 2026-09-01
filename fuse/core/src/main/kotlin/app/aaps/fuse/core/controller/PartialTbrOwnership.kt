package app.aaps.fuse.core.controller

import kotlin.math.abs
import kotlin.math.min

/**
 * DER LEBENSZYKLUS DER EIGENEN TEIL-TBR.
 *
 * ===================================================================
 * WARUM EIN EINZELNER NACHWEIS NICHT REICHTE (Review-Race)
 * ===================================================================
 * Die erste Fassung schrieb den Nachweis beim ANFORDERN und loeschte
 * ihn, sobald die aktuelle Pumpensicht nicht dazu passte. Damit gab es
 * ein Zeitfenster, in dem alles schieflaeuft:
 *
 *     Zyklus N   Teilrate angefordert, Nachweis geschrieben
 *     Zyklus N+1 Pumpe hat sie noch nicht uebernommen, der Riegel
 *                loest -> `current` passt nicht -> Nachweis GELOESCHT
 *     Zyklus N+2 Pumpe uebernimmt verspaetet -> die eigene Teilrate
 *                gilt als FREMD und laeuft bis zum Ablauf weiter,
 *                waehrend FUSE die normale Freigabe meldet
 *
 * Dazu kam eine zweite Verwechslung: `current == null` hiess sowohl
 * "nachgewiesen laeuft keine TBR" als auch "ich habe gerade keine
 * brauchbare Sicht". NUR das erste darf einen Nachweis bestaetigen
 * oder loeschen - deshalb ist die Sicht jetzt TYPISIERT ([View]).
 *
 * ===================================================================
 * DIE PHASEN
 * ===================================================================
 *     REQUESTED --(autoritativ bestaetigt)--> RUNNING
 *         |                                      |
 *         |                                      | Stufe vorbei / Schalter aus
 *         +--------------> ENDING <--------------+
 *                             |
 *          (autoritativ: laeuft nicht mehr) -> geloescht
 *
 *  - [Phase.REQUESTED] Setzen angefordert, Bestaetigung offen. Eine
 *    begrenzte Frist ([CONFIRM_WINDOW_MIN]) haelt den Nachweis, damit
 *    verspaetetes Auftauchen noch als EIGEN erkannt wird.
 *  - [Phase.RUNNING] Rate und erwartete Restlaufzeit sind AUTORITATIV
 *    bestaetigt.
 *  - [Phase.ENDING] Abbruch gewollt. Der SMB bleibt gesperrt, und das
 *    Kommando geht mit BACKOFF raus, nicht jeden Zyklus.
 *  - Geloescht wird ausschliesslich, wenn ein AUTORITATIVER Snapshot
 *    zeigt, dass nichts Passendes mehr laeuft - oder wenn die
 *    Bestaetigungsfrist ohne jedes Auftauchen verstrichen ist, und das
 *    dann mit benanntem Grund ([Reason.CONFIRM_TIMEOUT]), nicht still.
 *
 * ===================================================================
 * DIE FEHLERRICHTUNGEN SIND NICHT GLEICH SCHLIMM
 * ===================================================================
 * Eine FREMDE Absenkung faelschlich beenden hiesse, ungefragt Insulin
 * zu erhoehen - das darf nie passieren, und deshalb wird bei unklarer
 * Sicht NIE abgebrochen. Eine EIGENE faelschlich halten heisst, laenger
 * weniger zu geben, bei gesperrtem SMB. Unschoen, aber sicher.
 */
object PartialTbrOwnership {

    enum class Phase { REQUESTED, RUNNING, ENDING }

    /**
     * Was FUSE zuletzt als eigene Teil-TBR angefordert hat, samt Phase.
     * Restartfest zu halten - ginge er beim Neustart verloren, waere die
     * eigene laufende Absenkung danach "fremd".
     */
    data class Own(
        val rateUPerH: Double,
        /** Zeitpunkt der ANFORDERUNG, nicht der Bestaetigung. */
        val setAtTs: Long,
        val durationMin: Int,
        val phase: Phase,
        /** Beginn der aktuellen Phase - Grundlage von Frist und Backoff. */
        val phaseSinceTs: Long,
        /** Gesendete Abbruchkommandos in [Phase.ENDING]. */
        val endAttempts: Int = 0,
        /** Zeitpunkt des letzten gesendeten Abbruchs. */
        val lastEndRequestTs: Long = 0L,
        /**
         * WURDE SIE JE AUTORITATIV BESTAETIGT?
         *
         * Ohne dieses Feld ging die Information beim Uebergang nach
         * [Phase.ENDING] verloren, und ein autoritatives "laeuft nicht
         * mehr" wurde faelschlich als "noch nie aufgetaucht, Frist laeuft"
         * gelesen - der Nachweis blieb dann bis zum Fristende haengen und
         * der SMB mit ihm. Vom E2E-Runnertest gefunden.
         *
         * Die Bestaetigungsfrist gilt AUSSCHLIESSLICH fuer eine Anforderung,
         * die noch NIE sichtbar war.
         */
        val everRunning: Boolean = false,
        /** Gesendete SETZ-Kommandos fuer diese Anforderung. */
        val setAttempts: Int = 0,
        /** Zeitpunkt des letzten gesendeten Setzkommandos. */
        val lastSetRequestTs: Long = 0L,
    ) {

        val valid: Boolean
            get() = rateUPerH.isFinite() && rateUPerH > 0.0 &&
                setAtTs > 0L && durationMin > 0 && phaseSinceTs > 0L &&
                endAttempts >= 0 && lastEndRequestTs >= 0L &&
                setAttempts >= 0 && lastSetRequestTs >= 0L
    }

    /**
     * DIE PUMPENSICHT, TYPISIERT.
     *
     * Der Unterschied traegt die halbe Sicherheit dieses Zustandsautomaten:
     * "ich sehe nachweislich keine TBR" und "ich sehe gerade nichts
     * Brauchbares" sind NICHT dasselbe.
     */
    sealed interface View {

        /** Belastbare Sicht. `current == null` heisst NACHGEWIESEN keine TBR. */
        data class Authoritative(val current: TbrPolicy.Current?) : View

        /** Snapshot fehlt, stammt aus einer Ersatzquelle oder ist unbrauchbar. */
        data object Unknown : View
    }

    enum class Reason {
        /** Kein Nachweis vorhanden. */
        NONE,

        /** In diesem Zyklus neu angefordert. */
        REQUESTED_NEW,

        /** Angefordert, noch nicht sichtbar - Frist laeuft. */
        WAITING_CONFIRM,

        /** Autoritativ bestaetigt: unsere Rate laeuft. */
        CONFIRMED_RUNNING,

        /** Sicht unbrauchbar - Zustand GEHALTEN, nichts abgebrochen. */
        VIEW_UNKNOWN_HELD,

        /** Abbruch in diesem Zyklus gesendet. */
        END_REQUESTED,

        /** Wiederholter Abbruch nach Backoff. */
        END_RETRY,

        /** Abbruch gewollt, aber der Backoff laeuft noch. */
        END_BACKOFF_WAIT,

        /** Deckel erreicht - kein weiteres Kommando, Nachweis bleibt. */
        END_GIVEN_UP,

        /** Autoritativ bestaetigt: laeuft nicht mehr. Erst hier faellt die SMB-Sperre. */
        CLEARED_CONFIRMED,

        /** Frist ohne jedes Auftauchen verstrichen - benannt, nicht still. */
        CONFIRM_TIMEOUT,

        /** Dieselbe Rate ist schon angefordert und die Frist laeuft -
         *  kein zweites Kommando, und die Frist beginnt NICHT neu. */
        SET_SUPPRESSED_DUPLICATE,

        /** Eine NIEDRIGERE Rate ersetzt die offene Anforderung sofort. */
        SET_LOWERED,

        /** Eine HOEHERE Rate wartet, bis die offene Anforderung geklaert ist. */
        SET_HELD_HIGHER,

        /** Frist abgelaufen: benannter Neuversuch nach Backoff. */
        SET_RETRY,

        /** Frist abgelaufen, aber der Backoff laeuft noch. */
        SET_BACKOFF_WAIT,

        /** Versuchsdeckel erreicht - kein weiteres Setzkommando. */
        SET_GIVEN_UP,
    }

    /** Das Ergebnis eines Zyklus - der einzige Weg, den Zustand fortzuschreiben. */
    data class Step(
        val own: Own?,
        /**
         * Solange ein Nachweis lebt, bleibt der SMB gesperrt. Waehrend
         * REQUESTED/RUNNING ueber `PARTIAL_RECOVERY`, waehrend ENDING ueber
         * `PARTIAL_ENDING` - der Abbruch HEBT die Rate aufs Profilbasal, und
         * "anheben plus SMB" darf denselben Zyklus nicht verlassen.
         */
        val smbBlocked: Boolean,
        /** In diesem Zyklus ein Abbruchkommando senden? */
        val sendCancel: Boolean,
        /**
         * DARF IN DIESEM ZYKLUS EIN SETZKOMMANDO RAUS?
         *
         * `false` heisst NICHT "keine Teilstufe", sondern "die laufende
         * Anforderung ist noch offen". Ohne diese Berechtigung forderte
         * `TbrPolicy.partialBasal` bei unsichtbarer TBR jede Minute erneut
         * an: die Bestaetigungsfrist begann immer neu, CONFIRM_TIMEOUT kam
         * nie zum Zug, das Kommando ging minuetlich raus, und eine
         * verspaetet sichtbare Rate wurde immer wieder auf 30 min
         * verlaengert.
         */
        val allowSet: Boolean,
        val reason: Reason,
    )

    /**
     * Wieviele Minuten eine Anforderung ohne Sichtbarkeit gehalten wird.
     * Danach wird sie mit benanntem Grund verworfen - nicht still, und
     * ohne je eine fremde Absenkung anzufassen.
     */
    const val CONFIRM_WINDOW_MIN = 5

    /** Mindestabstand zwischen zwei Abbruchkommandos [min]. */
    const val END_BACKOFF_MIN = 3

    /** Mindestabstand zwischen zwei Setzversuchen NACH Fristablauf [min]. */
    const val SET_BACKOFF_MIN = 3

    /** Deckel der Setzversuche fuer dieselbe Anforderung. */
    const val SET_MAX_ATTEMPTS = 3

    /** Deckel der Abbruchversuche - dieselbe Idee wie beim Medtrum-Backoff
     *  der Null: ohne ihn wiederholt FUSE das Kommando minuetlich. */
    const val END_MAX_ATTEMPTS = 3

    /**
     * Toleranz der Restlaufzeit nach UNTEN [min] - Rundung und
     * Zyklusversatz. Nach OBEN gilt statt dessen die bisher verstrichene
     * Wartezeit (gedeckelt auf [CONFIRM_WINDOW_MIN]): die Pumpe kann nur
     * SPAETER starten als angefordert, nie frueher, und je kuerzer wir
     * warten, desto enger ist das Band.
     */
    const val REMAINING_TOLERANCE_MIN = 3

    /** Passen Rate UND Restlaufzeit zu unserer Anforderung? */
    fun matches(
        own: Own?,
        current: TbrPolicy.Current?,
        nowTs: Long,
        basalStepUPerH: Double,
    ): Boolean {
        if (own == null || !own.valid) return false
        if (current == null || current.violation() != null) return false
        // Ein als TBR gelesener Extended Bolus ist nie unsere Teilrate.
        if (current.sourceType != TbrPolicy.SourceType.TEMP_BASAL) return false
        if (!basalStepUPerH.isFinite() || basalStepUPerH <= 0.0) return false
        if (nowTs < own.setAtTs) return false
        if (abs(current.absoluteRateUPerH - own.rateUPerH) > basalStepUPerH / 2.0) return false
        val wartenMin = (nowTs - own.setAtTs) / 60_000.0
        val erwartetRest = own.durationMin - wartenMin
        if (erwartetRest <= 0.0) return false
        val verspaetungMax = min(wartenMin, CONFIRM_WINDOW_MIN.toDouble())
        return current.remainingMin >= erwartetRest - REMAINING_TOLERANCE_MIN &&
            current.remainingMin <= erwartetRest + verspaetungMax
    }

    /**
     * DIE EINZIGE FORTSCHREIBUNG DES ZUSTANDS.
     *
     * Gibt BERECHTIGUNGEN zurueck, keine vollzogenen Tatsachen: was
     * tatsaechlich in die Queue ging, buchen erst [registerSet] und
     * [registerCancel]. Nur so aendert sich `setAtTs` ausschliesslich bei
     * einem wirklich zugelassenen und wirklich gesendeten Versuch - und
     * nicht schon dadurch, dass jemand einen Wunsch geaeussert hat.
     *
     * @param wunschRate die in diesem Zyklus gewuenschte Teilrate [U/h],
     *                   `null` = keine Teilstufe
     * @param wantEnd    die Teilstufe ist vorbei oder der Schalter ist aus
     */
    fun advance(
        own: Own?,
        view: View,
        nowTs: Long,
        basalStepUPerH: Double,
        wunschRate: Double? = null,
        wantEnd: Boolean = false,
    ): Step {
        if (own == null || !own.valid) {
            // Ohne Nachweis ist ein Setzwunsch immer zulaessig - erst das
            // tatsaechliche Kommando legt den Nachweis an.
            val neu = wunschRate != null && wunschRate.isFinite() && wunschRate > 0.0 && !wantEnd
            return Step(null, smbBlocked = false, sendCancel = false, allowSet = neu, reason = Reason.NONE)
        }

        // (1) UNBRAUCHBARE SICHT SAGT NICHTS. Zustand halten, nichts
        //     abbrechen, nichts neu setzen, SMB gesperrt lassen. Genau hier
        //     stuerzte die erste Fassung ab, weil sie `current == null` als
        //     Beweis las.
        val current = when (view) {
            is View.Unknown       -> return Step(own, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.VIEW_UNKNOWN_HELD)
            is View.Authoritative -> view.current
        }
        val passt = matches(own, current, nowTs, basalStepUPerH)

        // (2) BEENDEN GEWOLLT ODER SCHON IM GANG - schlaegt jeden Setzwunsch.
        if (wantEnd || own.phase == Phase.ENDING) {
            val e = if (own.phase == Phase.ENDING) own
            else own.copy(phase = Phase.ENDING, phaseSinceTs = nowTs, endAttempts = 0, lastEndRequestTs = 0L)
            if (!passt) {
                // Nur eine NIE bestaetigte Anforderung geniesst die Frist.
                // War sie schon einmal sichtbar, ist ein autoritatives
                // "nicht mehr da" die Bestaetigung des Endes.
                return if (!own.everRunning && nowTs - own.setAtTs <= CONFIRM_WINDOW_MIN * 60_000L)
                    Step(e, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.WAITING_CONFIRM)
                else Step(null, smbBlocked = false, sendCancel = false, allowSet = false, reason = Reason.CLEARED_CONFIRMED)
            }
            if (e.endAttempts >= END_MAX_ATTEMPTS)
                return Step(e, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.END_GIVEN_UP)
            val darfAbbrechen = e.lastEndRequestTs == 0L ||
                nowTs - e.lastEndRequestTs >= END_BACKOFF_MIN * 60_000L
            return if (darfAbbrechen)
                Step(
                    e, smbBlocked = true, sendCancel = true, allowSet = false,
                    reason = if (e.endAttempts == 0) Reason.END_REQUESTED else Reason.END_RETRY,
                )
            else Step(e, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.END_BACKOFF_WAIT)
        }

        // (3) AUTORITATIV BESTAETIGT
        if (passt) {
            val r = if (own.phase == Phase.RUNNING) own.copy(everRunning = true)
            else own.copy(phase = Phase.RUNNING, phaseSinceTs = nowTs, everRunning = true)
            // Eine bestaetigt LAUFENDE Rate darf erneuert, gesenkt und
            // angehoben werden - die Tabelle entscheidet ueber die
            // Erneuerung selbst (ALREADY_RUNNING/RENEW), und gebucht wird
            // erst, was sie tatsaechlich sendet.
            val setzen = wunschRate != null && wunschRate.isFinite() && wunschRate > 0.0
            return Step(r, smbBlocked = true, sendCancel = false, allowSet = setzen, reason = Reason.CONFIRMED_RUNNING)
        }

        // (4) PASST NICHT - der Fall, in dem die zweite Race sass.
        return when (own.phase) {
            Phase.REQUESTED -> {
                val fristOffen = nowTs - own.setAtTs <= CONFIRM_WINDOW_MIN * 60_000L
                if (!fristOffen && !own.everRunning) {
                    // Frist verstrichen. KEIN stiller Neubeginn: entweder ein
                    // benannter Neuversuch nach Backoff, oder Schluss.
                    val gleich = wunschRate != null && abs(wunschRate - own.rateUPerH) <= basalStepUPerH / 2.0
                    return when {
                        wunschRate == null || !wunschRate.isFinite() || wunschRate <= 0.0 ->
                            Step(null, smbBlocked = false, sendCancel = false, allowSet = false, reason = Reason.CONFIRM_TIMEOUT)
                        !gleich && wunschRate < own.rateUPerH ->
                            Step(own, smbBlocked = true, sendCancel = false, allowSet = true, reason = Reason.SET_LOWERED)
                        own.setAttempts >= SET_MAX_ATTEMPTS ->
                            Step(own, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.SET_GIVEN_UP)
                        own.lastSetRequestTs != 0L && nowTs - own.lastSetRequestTs < SET_BACKOFF_MIN * 60_000L ->
                            Step(own, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.SET_BACKOFF_WAIT)
                        else ->
                            Step(own, smbBlocked = true, sendCancel = false, allowSet = true, reason = Reason.SET_RETRY)
                    }
                }
                // Frist offen: die Anforderung steht. Ein Setzwunsch wird
                // NUR dann durchgelassen, wenn er SICHERER ist.
                when {
                    wunschRate == null || !wunschRate.isFinite() || wunschRate <= 0.0 ->
                        Step(own, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.WAITING_CONFIRM)
                    wunschRate < own.rateUPerH - basalStepUPerH / 2.0 ->
                        Step(own, smbBlocked = true, sendCancel = false, allowSet = true, reason = Reason.SET_LOWERED)
                    wunschRate > own.rateUPerH + basalStepUPerH / 2.0 ->
                        Step(own, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.SET_HELD_HIGHER)
                    else ->
                        // DIE ZWEITE RACE: hier ging bisher jede Minute ein
                        // neues Kommando raus und die Frist begann neu.
                        Step(own, smbBlocked = true, sendCancel = false, allowSet = false, reason = Reason.SET_SUPPRESSED_DUPLICATE)
                }
            }
            // Lief bestaetigt und ist autoritativ weg.
            Phase.RUNNING   -> Step(null, smbBlocked = false, sendCancel = false, allowSet = false, reason = Reason.CLEARED_CONFIRMED)
            Phase.ENDING    -> error("in (2) behandelt")
        }
    }

    /**
     * BUCHT EIN TATSAECHLICH GESENDETES SETZKOMMANDO.
     *
     * Nur hier bewegt sich `setAtTs` - nicht schon beim Wunsch und nicht
     * bei einem unterdrueckten Duplikat.
     */
    fun registerSet(
        own: Own?,
        rateUPerH: Double,
        durationMin: Int,
        nowTs: Long,
        basalStepUPerH: Double,
    ): Own {
        val gleicheRate = own != null && own.valid &&
            abs(own.rateUPerH - rateUPerH) <= basalStepUPerH / 2.0
        // Eine ERNEUERUNG derselben bestaetigt laufenden Rate bleibt RUNNING -
        // sonst faellt eine laufende Stufe bei jeder Erneuerung in die Frist
        // zurueck.
        if (gleicheRate && own!!.phase == Phase.RUNNING)
            return own.copy(setAtTs = nowTs, durationMin = durationMin, lastSetRequestTs = nowTs)
        // Derselbe Neuversuch fuer dieselbe Anforderung zaehlt hoch.
        if (gleicheRate && own!!.phase == Phase.REQUESTED)
            return own.copy(
                setAtTs = nowTs, durationMin = durationMin, phaseSinceTs = nowTs,
                setAttempts = own.setAttempts + 1, lastSetRequestTs = nowTs,
            )
        // Andere Rate oder gar kein Nachweis: eine NEUE Anforderung.
        //
        // `everRunning` WANDERT MIT, wenn schon eine eigene Rate bestaetigt
        // lief. Sonst ginge beim Anheben (der Guard gibt mehr frei, sobald
        // die Bahn steigt) das Wissen verloren, dass ueberhaupt etwas von
        // uns laeuft - und ein autoritatives "nichts da" fiele danach in
        // die Bestaetigungsfrist statt den Besitz zu beenden.
        return Own(
            rateUPerH = rateUPerH, setAtTs = nowTs, durationMin = durationMin,
            phase = Phase.REQUESTED, phaseSinceTs = nowTs,
            setAttempts = 1, lastSetRequestTs = nowTs,
            everRunning = own?.everRunning == true,
        )
    }

    /** Bucht ein tatsaechlich gesendetes Abbruchkommando. */
    fun registerCancel(own: Own?, nowTs: Long): Own? {
        if (own == null || !own.valid) return own
        return own.copy(
            phase = Phase.ENDING,
            phaseSinceTs = if (own.phase == Phase.ENDING) own.phaseSinceTs else nowTs,
            endAttempts = own.endAttempts + 1,
            lastEndRequestTs = nowTs,
        )
    }
}

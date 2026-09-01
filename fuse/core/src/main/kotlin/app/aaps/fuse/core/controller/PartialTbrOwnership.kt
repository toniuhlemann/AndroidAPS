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
    ) {

        val valid: Boolean
            get() = rateUPerH.isFinite() && rateUPerH > 0.0 &&
                setAtTs > 0L && durationMin > 0 && phaseSinceTs > 0L &&
                endAttempts >= 0 && lastEndRequestTs >= 0L
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
     * @param setRequest in diesem Zyklus angeforderte Teilrate (`null` = keine)
     * @param wantEnd    die Teilstufe ist vorbei oder der Schalter ist aus
     */
    fun advance(
        own: Own?,
        view: View,
        nowTs: Long,
        basalStepUPerH: Double,
        setRequest: Own? = null,
        wantEnd: Boolean = false,
    ): Step {
        // (1) EINE NEUE ANFORDERUNG setzt den Nachweis - aber sie behauptet
        //     nur REQUESTED, nicht bestaetigten Besitz.
        if (setRequest != null && setRequest.valid) {
            val gleicheRate = own != null && own.phase == Phase.RUNNING &&
                abs(own.rateUPerH - setRequest.rateUPerH) <= basalStepUPerH / 2.0
            // Eine ERNEUERUNG derselben laufenden Rate darf den bestaetigten
            // Zustand nicht auf REQUESTED zurueckstufen - sonst faellt eine
            // laufende Stufe bei jeder Erneuerung in die Frist zurueck.
            val neu = if (gleicheRate)
                own!!.copy(setAtTs = setRequest.setAtTs, durationMin = setRequest.durationMin)
            else setRequest.copy(phase = Phase.REQUESTED, phaseSinceTs = nowTs, endAttempts = 0, lastEndRequestTs = 0L)
            return Step(neu, smbBlocked = true, sendCancel = false, reason = Reason.REQUESTED_NEW)
        }
        if (own == null || !own.valid) return Step(null, smbBlocked = false, sendCancel = false, reason = Reason.NONE)

        // (2) UNBRAUCHBARE SICHT SAGT NICHTS. Zustand halten, nichts
        //     abbrechen, SMB gesperrt lassen. Genau hier stuerzte die alte
        //     Fassung ab, weil sie `current == null` als Beweis las.
        val current = when (view) {
            is View.Unknown       -> return Step(own, smbBlocked = true, sendCancel = false, reason = Reason.VIEW_UNKNOWN_HELD)
            is View.Authoritative -> view.current
        }
        val passt = matches(own, current, nowTs, basalStepUPerH)

        // (3) BEENDEN GEWOLLT ODER SCHON IM GANG
        if (wantEnd || own.phase == Phase.ENDING) {
            val e = if (own.phase == Phase.ENDING) own
            else own.copy(phase = Phase.ENDING, phaseSinceTs = nowTs, endAttempts = 0, lastEndRequestTs = 0L)
            if (!passt) {
                // Noch nie sichtbar gewesen und die Frist laeuft: halten -
                // eine verspaetet uebernommene Rate muss noch als unsere
                // erkannt und dann abgebrochen werden.
                // Nur eine NIE bestaetigte Anforderung geniesst die Frist.
                // War sie schon einmal sichtbar, ist ein autoritatives
                // "nicht mehr da" die Bestaetigung des Endes.
                return if (!own.everRunning && nowTs - own.setAtTs <= CONFIRM_WINDOW_MIN * 60_000L)
                    Step(e, smbBlocked = true, sendCancel = false, reason = Reason.WAITING_CONFIRM)
                // Autoritativ nicht mehr da: DAS ist die Bestaetigung, und
                // erst sie oeffnet den SMB.
                else Step(null, smbBlocked = false, sendCancel = false, reason = Reason.CLEARED_CONFIRMED)
            }
            if (e.endAttempts >= END_MAX_ATTEMPTS)
                return Step(e, smbBlocked = true, sendCancel = false, reason = Reason.END_GIVEN_UP)
            val darfSenden = e.lastEndRequestTs == 0L ||
                nowTs - e.lastEndRequestTs >= END_BACKOFF_MIN * 60_000L
            return if (darfSenden)
                Step(
                    e.copy(endAttempts = e.endAttempts + 1, lastEndRequestTs = nowTs),
                    smbBlocked = true, sendCancel = true,
                    reason = if (e.endAttempts == 0) Reason.END_REQUESTED else Reason.END_RETRY,
                )
            else Step(e, smbBlocked = true, sendCancel = false, reason = Reason.END_BACKOFF_WAIT)
        }

        // (4) AUTORITATIV BESTAETIGT
        if (passt) {
            val r = if (own.phase == Phase.RUNNING) own.copy(everRunning = true)
            else own.copy(phase = Phase.RUNNING, phaseSinceTs = nowTs, everRunning = true)
            return Step(r, smbBlocked = true, sendCancel = false, reason = Reason.CONFIRMED_RUNNING)
        }

        // (5) PASST NICHT
        return when (own.phase) {
            // Noch in der Frist: verspaetetes Auftauchen bleibt moeglich.
            Phase.REQUESTED ->
                if (!own.everRunning && nowTs - own.setAtTs <= CONFIRM_WINDOW_MIN * 60_000L)
                    Step(own, smbBlocked = true, sendCancel = false, reason = Reason.WAITING_CONFIRM)
                // Frist verstrichen: verwerfen, aber BENANNT - und ohne je
                // eine fremde Absenkung anzufassen.
                else Step(null, smbBlocked = false, sendCancel = false, reason = Reason.CONFIRM_TIMEOUT)
            // Lief bestaetigt und ist autoritativ weg.
            Phase.RUNNING   -> Step(null, smbBlocked = false, sendCancel = false, reason = Reason.CLEARED_CONFIRMED)
            Phase.ENDING    -> error("in (3) behandelt")
        }
    }
}

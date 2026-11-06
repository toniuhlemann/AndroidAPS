package app.aaps.fuse.core.controller

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * DIE GEMEINSAME BILANZ DER RECHNER-MAHLZEIT UND DIE BASAL-RUECKNAHME (GV-R) - Entwurf B, Abschnitte 16 bis 24
 * (ein Entwurfsdokument ausserhalb des Repos). GRUNDFUNKTION ohne Schalter und ohne Varianten (Toni 05.11.2026).
 *
 * Fuer jede Mahlzeit aus dem Bolusrechner (HCL-Marker), mit oder ohne eCarbs:
 *
 *     G = (BG - Ziel + max(angekuendigter Rest x ISF, r x tau)) / ISF - (IOB + Transport)    [U]
 *
 *  - Der ANGEKUENDIGTE REST: der Sofort-KH-Rest nach der Erwartungskurve (Rechner-KH/IC minus phi0 x Kurve) plus der
 *    eCarbs-Rest wie COB mit Verfall ([ECarbs.CobAttribution.remainingWithLapseU]) mal der Ruecknahme w. Er zaehlt, solange
 *    das Kontofenster offen ist oder angekuendigte eCarbs laufen.
 *  - Danach gilt G OHNE angekuendigten Rest weiter, solange Insulin der Mahlzeit wirkt ([EPISODE_IOB_MIN_U]).
 *  - Die gemessene Fortsetzung r x tau haelt G bei unterschaetzten Mahlzeiten offen; die gemeldete Menge ist keine Grenze.
 *  - Jede Insulinmenge genau einmal: G enthaelt IOB und Transport, NICHT den SMB dieses Zyklus und NICHT den Kanalplan.
 *    Der SMB-Weg bekommt hoechstens [smbCapU] (G - Kanalplan); der Kanal bekommt G als Luecke
 *    ([SlowCorrectionChannel.Input.coverageGapU]) und zieht den SMB selbst ab.
 *
 * DIE RUECKNAHME ([withdrawal]): ist G < 0 und liegt r unter der Rampen-Unterkante, nimmt FUSE Basal zurueck - ein Befehl
 * fuer [COMMAND_MIN] min, der hoechstens die Ueberdeckung -G nimmt (abzueglich dessen, was ein laufender Befehl bis zur
 * Ausfuehrung des neuen noch nimmt), auch wenn kein Folgezyklus kommt. Die Rate wird dafuer auf den Pumpenschritt
 * AUFgerundet und geht nie unter einen Pumpenschritt: die echte Null bleibt der Schutz-Null vorbehalten. "r klein" ist ein
 * KANDIDAT fuer "Anstieg vorbei", kein belegtes Mahlzeitende.
 *
 * UEBERLAPPENDE RECHNER-MAHLZEITEN (Codex 05.11.2026: laufende eCarbs behalten ihre Herkunft und ihren verbuchten
 * Aufnahmezustand; ein neuer Marker darf ihren Rest weder verlieren noch erneut ansetzen): der neue Marker uebernimmt die
 * eCarbs der laufenden Episode ([inherits], [inherit]). Jede Mahlzeit behaelt ihre eigene Bestaetigung und Ruecknahme
 * ([Input.earlierECarbs]).
 *
 * Reine Rechnung ohne Nebenwirkung: den Zustand der eCarbs-Zuordnung ([ECarbs.CobAttribution]) fuehrt der Aufrufer von
 * Zyklus zu Zyklus.
 */
object MealBalance {

    /** Ein Ruecknahme-Befehl laeuft so lange [min]. */
    const val COMMAND_MIN = 30

    /**
     * DER TAKT DER BEFEHLE [min]: eine laufende Ruecknahme aendert ihre Rate hoechstens so oft - der Takt, in dem die
     * Regel geprueft ist (Pruefstand mit 5-min-Zyklen). Bei 1-min-Zyklen folgte sie sonst jeder kleinen Aenderung von G
     * mit einem eigenen Pumpenbefehl. Sofort gehen der erste Befehl, das Ende und eine Anhebung, die die Grenze je Befehl
     * verlangt ([holdsLimit]).
     */
    const val COMMAND_STEP_MIN = 5

    /**
     * Haelt ein laufender Befehl mit [runningRateUPerH] und [remainingMin] Restzeit die Grenze [limitU] noch ein? Sein
     * restlicher Entzug darf nicht ueber der Ueberdeckung liegen - sonst muss er sofort angehoben oder beendet werden.
     */
    fun holdsLimit(runningRateUPerH: Double, remainingMin: Double, scheduledBasalUPerH: Double, limitU: Double): Boolean =
        max(0.0, scheduledBasalUPerH - runningRateUPerH) * max(0.0, remainingMin) / 60.0 <= limitU + EPS

    /** Nach dem Kontofenster bleibt G aktiv, solange so viel Insulin der Mahlzeit wirkt [U] - ein Pumpenschritt. */
    const val EPISODE_IOB_MIN_U = 0.05

    private const val EPS = 1e-9

    /**
     * Ein eCarbs-Eintrag der Episode: seine Kennung in der KH-Tabelle und seine HERKUNFT - der Marker der Episode, die
     * ihn zuerst gezaehlt hat.
     */
    data class ECarbsRef(val entryId: Long, val originAuthId: Long)

    /**
     * DER RESTARTFESTE ZUSTAND EINER EPISODE, vom Aufrufer gefuehrt: der Marker der Rechner-Mahlzeit ([authId]), die
     * beobachtete Mahlzeitwirkung seit dem Marker ([ExpectationAccount.observe] - sie lebt laenger als das Kontofenster)
     * und die Aufnahmezuordnung der eCarbs.
     */
    data class Episode(
        val authId: Long,
        val observation: ExpectationAccount.Observation,
        /** Die verbuchte Aufnahme aller eCarbs der Episode, der eigenen und der uebernommenen. */
        val attribution: ECarbs.CobAttribution = ECarbs.CobAttribution(),
        /** Vorbei: nichts mehr angekuendigt und kein Insulin der Mahlzeit mehr - fuer diesen Marker kommt sie nicht zurueck. */
        val done: Boolean = false,
        /** Die eCarbs-Eintraege, die die Episode zuletzt gezaehlt hat, mit Herkunft - die eigenen und die uebernommenen. */
        val eCarbs: List<ECarbsRef> = emptyList(),
    )

    /**
     * UEBERNIMMT der neue Marker [authId] die eCarbs der Episode [previous]? Ja fuer eine aeltere, nicht beendete Episode
     * mit eCarbs, die zuletzt ohne Beobachtungsluecke gerechnet wurde ([ExpectationAccount.MAX_OBSERVATION_GAP_MIN]).
     * Eine laenger nicht gerechnete gaebe Teile als ausstehend weiter, die inzwischen aufgenommen sein koennen - dann gilt
     * der neue Marker allein: weniger, nie mehr.
     */
    fun inherits(previous: Episode, authId: Long, nowTs: Long): Boolean =
        !previous.done && previous.authId < authId && previous.eCarbs.isNotEmpty() &&
            nowTs - previous.observation.lastObsTs <= ExpectationAccount.MAX_OBSERVATION_GAP_MIN * 60_000.0

    /**
     * DIE UEBERNAHME: die Episode des neuen Markers [authId] mit dessen Beobachtung [observation] fuehrt die eCarbs von
     * [previous] weiter, mit ihrer Herkunft und ihrer verbuchten Aufnahme - ihr Rest geht weder verloren, noch beginnt er
     * neu. Nur die Hochwassermarke beginnt neu: sie gehoert zur Beobachtung, und die beginnt beim neuen Marker bei null.
     */
    fun inherit(previous: Episode, authId: Long, observation: ExpectationAccount.Observation): Episode =
        Episode(authId, observation, previous.attribution.copy(maxExcessU = null), eCarbs = previous.eCarbs)

    /** Die eCarbs einer frueheren Rechner-Mahlzeit in der laufenden Episode: ihre Herkunft und ihre Teile wie AAPS. */
    data class EarlierECarbs(val originAuthId: Long, val parts: List<ECarbs.Part>)

    /** Eine fruehere Rechner-Mahlzeit in diesem Zyklus: Herkunft, Menge [g], Rest wie COB mit Verfall [U] vor w, c_e, w. */
    data class EarlierResult(
        val originAuthId: Long,
        val eCarbsG: Double,
        val restU: Double,
        val confirmation: Double,
        val weight: Double,
    )

    /** Die Eingaben eines Zyklus. */
    data class Input(
        val nowTs: Long,
        /** Minuten seit dem Marker der Rechner-Mahlzeit. */
        val minutesSinceMarker: Double,
        /** Die Laenge des Kontofensters [min]. */
        val windowMin: Double,
        /** Die Rechner-KH als Insulin (KH / IC) [U]. */
        val immediateCarbsU: Double,
        /** Der Skalierer der Erwartungskurve fuer diese Mahlzeit ([EarlySupply.hclScale]). */
        val phi0: Double,
        val curve: ExpectationAccount.Curve,
        /** Die beobachtete Mahlzeitwirkung seit dem Marker [U-Aeq]; `null` = nicht gueltig (dann keine neue Zuordnung). */
        val observedU: Double?,
        /** Die eigenen angekuendigten eCarbs (Herkunft: dieser Marker) wie AAPS zerlegt ([ECarbs.parts]); leer = keine. */
        val eCarbs: List<ECarbs.Part>,
        val icGPerU: Double,
        val bgMgdl: Double,
        val targetMgdl: Double,
        val isfMgdlPerU: Double,
        /** Der gemessene Antrieb r [mg/dl/min] und seine Abklingzeit tau [min]. */
        val driveMgdlPerMin: Double,
        val driveTauMin: Double,
        /** IOB netto (eine Absenkung mindert es) und Transport (abgegeben, noch nicht im IOB) [U]. */
        val iobU: Double,
        val transportU: Double,
        /**
         * Das noch wirkende Insulin der Mahlzeit seit dem Marker [U]: Boli und SMB. Der Zyklus zaehlt den Zusatz des
         * Kanals nicht mit; die Episode verlaengert sich dadurch nur um Minuten.
         */
        val episodeIobU: Double,
        /** Die vom neuen Marker uebernommenen eCarbs frueherer Rechner-Mahlzeiten ([inherit]); leer = keine. */
        val earlierECarbs: List<EarlierECarbs> = emptyList(),
    )

    /** Das Ergebnis eines Zyklus. */
    data class Result(
        /** G [U]; `null` = nicht aktiv oder nicht berechenbar - dann gilt die Rechnung ohne Bilanz. */
        val balanceU: Double?,
        /** Zaehlt der angekuendigte Rest noch (Kontofenster offen oder eCarbs laufen)? */
        val announcedActive: Boolean,
        /** Der Sofort-KH-Rest nach der Kurve [U]. */
        val immediateRestU: Double,
        /** Der Rest der eigenen eCarbs wie COB mit Verfall [U], vor der Ruecknahme w. */
        val eCarbsRestU: Double,
        /** Die Bestaetigung c_e und die Ruecknahme w der eigenen eCarbs (0..1). */
        val confirmation: Double,
        val weight: Double,
        /** Der naechste Stand der eCarbs-Zuordnung (aller eCarbs der Episode). */
        val attribution: ECarbs.CobAttribution,
        /** Die uebernommenen eCarbs frueherer Rechner-Mahlzeiten, die aelteste zuerst. */
        val earlier: List<EarlierResult> = emptyList(),
    )

    /** Ein Zyklus der Bilanz: eCarbs-Zuordnung weiterfuehren, angekuendigten Rest bilden, G rechnen. */
    fun cycle(input: Input, attribution: ECarbs.CobAttribution): Result {
        val parts = input.eCarbs
        val ic = input.icGPerU
        val now = input.nowTs
        val frueher = input.earlierECarbs.sortedBy { it.originAuthId }
        val alle = if (frueher.isEmpty()) parts else (frueher.flatMap { it.parts } + parts).sortedBy { it.ts }
        val sofortErwartet = input.phi0 * input.curve.at(input.minutesSinceMarker)
        val angekommenU = ECarbs.arrivedU(alle, ic, now)
        val observed = input.observedU
        val zuordnung = if (observed != null && observed.isFinite()) attribution.step(observed - sofortErwartet, angekommenU) else attribution
        // DIE HERKUNFT: die verbuchte Aufnahme fuellt zuerst die aelteren Mahlzeiten, je hoechstens ihre angekommene Menge;
        // den Rest bekommt die eigene. Jede Mahlzeit hat ihre eigene Bestaetigung und Ruecknahme - eine bestaetigte
        // fruehere stuetzt keine ausbleibende neue. Gegenueber einer Zuordnung nach der Zeit ist der Rest so nie groesser.
        var offenU = zuordnung.absorbedU
        val earlier = frueher.map { m ->
            val anteilU = max(0.0, min(offenU, ECarbs.arrivedU(m.parts, ic, now)))
            offenU -= anteilU
            val z = ECarbs.CobAttribution(absorbedU = anteilU)
            val seit = m.parts.minOfOrNull { it.ts }?.let { (now - it) / 60_000.0 } ?: Double.NaN
            val erwartetU = ECarbs.arrivedEffectU(m.parts, ic, now)
            val cm = z.confirmation(erwartetU, seit)
            EarlierResult(
                m.originAuthId, m.parts.sumOf { it.grams }, z.remainingWithLapseU(m.parts, ic, now), cm,
                ECarbs.withdrawal(cm, seit, erwartetU),
            )
        }
        val eigene = if (frueher.isEmpty()) zuordnung else zuordnung.copy(absorbedU = max(0.0, offenU))
        val seitStart = parts.minOfOrNull { it.ts }?.let { (now - it) / 60_000.0 } ?: Double.NaN
        val eArr = ECarbs.arrivedEffectU(parts, ic, now)
        val c = eigene.confirmation(eArr, seitStart)
        val w = ECarbs.withdrawal(c, seitStart, eArr)
        val eRest = eigene.remainingWithLapseU(parts, ic, now)
        val sofortRest = max(0.0, input.immediateCarbsU - sofortErwartet)
        // Der angekuendigte Rest zaehlt im Kontofenster und solange eCarbs laufen: bis zum Ende der Aufnahme des letzten
        // Teils (sein Zeitpunkt plus ein Teilabstand plus die Aufnahmezeit, wie der Pruefstand).
        val eCarbsEnde = alle.maxOfOrNull { it.ts }?.let { it + ((ECarbs.PART_MIN + ECarbs.ABSORPTION_MIN) * 60_000.0).toLong() }
        val announcedActive = input.minutesSinceMarker < input.windowMin || (eCarbsEnde != null && now < eCarbsEnde)
        val aktiv = announcedActive || input.episodeIobU > EPISODE_IOB_MIN_U
        val frueherU = earlier.sumOf { it.weight * it.restU }
        val g = if (!aktiv) null else balanceU(
            input.bgMgdl, input.targetMgdl, input.isfMgdlPerU,
            if (announcedActive) sofortRest + w * eRest + frueherU else 0.0,
            input.driveMgdlPerMin, input.driveTauMin, input.iobU, input.transportU,
        )
        return Result(g, announcedActive, sofortRest, eRest, c, w, zuordnung, earlier)
    }

    /**
     * G [U] - Formel oben. Nicht berechenbar = `null`.
     */
    fun balanceU(
        bgMgdl: Double,
        targetMgdl: Double,
        isfMgdlPerU: Double,
        announcedRemainingU: Double,
        driveMgdlPerMin: Double,
        driveTauMin: Double,
        iobU: Double,
        transportU: Double,
    ): Double? {
        if (!driveMgdlPerMin.isFinite() || !driveTauMin.isFinite() || driveTauMin <= 0.0) return null
        if (!bgMgdl.isFinite() || !targetMgdl.isFinite() || !isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0 ||
            !announcedRemainingU.isFinite() || !iobU.isFinite() || !transportU.isFinite()
        ) return null
        val fortsetzung = max(0.0, driveMgdlPerMin) * driveTauMin
        val mRest = max(max(0.0, announcedRemainingU) * isfMgdlPerU, max(0.0, fortsetzung))
        return (bgMgdl - targetMgdl + mRest) / isfMgdlPerU - (iobU + transportU)
    }

    /**
     * Die Grenze des SMB-Wegs [U]: G minus der geplante Rest des Kanals; `null` = keine Bilanz oder nicht berechenbar -
     * dann gilt die Rechnung ohne Bilanz, wie fuer G selbst.
     */
    fun smbCapU(balanceU: Double?, plannedChannelU: Double): Double? =
        balanceU?.let { it - max(0.0, plannedChannelU) }?.takeIf { it.isFinite() }

    // ---- DIE RUECKNAHME -------------------------------------------------------------------------------------------------

    /** Warum es in diesem Zyklus keine Ruecknahme gibt. */
    enum class NoWithdrawal {
        /** Keine Bilanz (keine Rechner-Mahlzeit, nicht aktiv oder nicht berechenbar). */
        INACTIVE,

        /** G >= 0: das Insulin deckt hoechstens, was ansteht. */
        NOT_OVERCOVERED,

        /** r liegt nicht unter der Rampen-Unterkante: der Anstieg laeuft noch. */
        RISING,

        /** Der langsame Kanal laeuft - er hat Vorrang. */
        CHANNEL,

        /** Die Schutz-Null oder der Null-Riegel nimmt das Basal ohnehin - sie haben Vorrang. */
        PROTECTIVE_ZERO,

        /** Die Ueberdeckung ist kleiner als ein Pumpenschritt ueber die Befehlsdauer. */
        TOO_SMALL,
    }

    data class WithdrawalInput(
        val balanceU: Double?,
        val driveMgdlPerMin: Double,
        val rampLowMgdlPerMin: Double,
        val channelActive: Boolean,
        val protectiveZeroActive: Boolean,
        val scheduledBasalUPerH: Double,
        val basalStepUPerH: Double,
        /** Was ein laufender Ruecknahme-Befehl noch nimmt, bis ein neuer ausgefuehrt ist [U] (0 ohne Ausfuehrungsverzug). */
        val withdrawnBeforeExecutionU: Double = 0.0,
    )

    /**
     * Der Ruecknahme-Wunsch dieses Zyklus: [rateUPerH] fuer [COMMAND_MIN] min mit der Grenze [limitU], oder keine
     * ([reason]). [overcoverageU] ist -G (0 ohne Ueberdeckung).
     */
    data class Withdrawal(val rateUPerH: Double?, val limitU: Double, val overcoverageU: Double, val reason: NoWithdrawal?) {
        val wanted: Boolean get() = rateUPerH != null
    }

    fun withdrawal(input: WithdrawalInput): Withdrawal {
        val g = input.balanceU
        val ueber = g?.let { max(0.0, -it) } ?: 0.0
        fun keine(r: NoWithdrawal) = Withdrawal(null, 0.0, ueber, r)
        val basal = input.scheduledBasalUPerH
        val step = input.basalStepUPerH
        when {
            g == null || !basal.isFinite() || basal <= 0.0 || !step.isFinite() || step <= 0.0 -> return keine(NoWithdrawal.INACTIVE)
            g >= 0.0 -> return keine(NoWithdrawal.NOT_OVERCOVERED)
            !input.driveMgdlPerMin.isFinite() || input.driveMgdlPerMin >= input.rampLowMgdlPerMin -> return keine(NoWithdrawal.RISING)
            input.channelActive -> return keine(NoWithdrawal.CHANNEL)
            input.protectiveZeroActive -> return keine(NoWithdrawal.PROTECTIVE_ZERO)
        }
        val dauer = COMMAND_MIN.toDouble()
        val vorher = if (input.withdrawnBeforeExecutionU.isFinite()) max(0.0, input.withdrawnBeforeExecutionU) else 0.0
        val grenze = min(max(0.0, ueber - vorher), basal * dauer / 60.0)
        // AUFgerundet: nie mehr Ruecknahme als die Grenze; nie unter einen Pumpenschritt.
        val rate = max(step, ceil((basal - grenze * 60.0 / dauer) / step - EPS) * step)
        if (rate >= basal - EPS) return keine(NoWithdrawal.TOO_SMALL)
        return Withdrawal(rate, grenze, ueber, null)
    }
}

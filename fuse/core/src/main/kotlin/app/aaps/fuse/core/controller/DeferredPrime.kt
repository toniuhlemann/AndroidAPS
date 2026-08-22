package app.aaps.fuse.core.controller

import kotlin.math.max
import kotlin.math.min

/**
 * DER MARKER-PRIME-AUFSCHUB (Punkt 6, Tonis Bau-GO 22.08.).
 *
 * DER GEMESSENE FALL DAHINTER: 21.08. 18:19 - Abendessen-Marker bei fallendem,
 * bolus-ueberdecktem BG. Der Boden pendelte bei 36-61 min, also JENSEITS des
 * 30-min-Endriegels; markerautorisiert flossen 8x0,15 = 1,20 U in den Fall,
 * die Ueberdeckung wuchs von 51 auf 123 mg/dl, Nadir 58. Die Mahlzeit kam 67
 * Minuten nach dem Marker - das Insulin war RICHTIG autorisiert und nur zur
 * FALSCHEN ZEIT unterwegs. Der Replay: ein 60-min-Horizont NUR fuer
 * markerautorisiertes Insulin haette alle 1,20 U aufgeschoben, bei null
 * Fehlaufschub in beiden Gutfaellen (14:21, 08:59) - n=3, Marge 3-5 min,
 * darum bleibt die Grenze konfigurierbar und der Schalter default AUS.
 *
 * WAS DER AUFSCHUB IST - UND WAS NICHT (Tonis Vertragsliste, woertlich):
 *  - Zurueckgehaltenes Insulin bleibt AUTORISIERUNG, keine garantierte
 *    spaetere Dosis.
 *  - H_prime gilt AUSSCHLIESSLICH fuer markerautorisiertes Insulin; reine
 *    Korrekturen behalten den separaten 30-min-Riegel.
 *  - Horizont und Ablauffrist werden BEIM MARKER GEPINNT - eine spaetere
 *    Einstellungsaenderung verschiebt eine laufende Frist nicht.
 *  - Freigabe nur nach bestaetigter Erholung, gesundem Signal, ohne Tief und
 *    ohne Rebound; hoechstens EIN Pumpenschritt je Zyklus, kein Aufhol-Burst.
 *  - Normale SMBs, Fundament und manuelle Boli reduzieren DENSELBEN offenen
 *    Gesamtbetrag; die Gesamtversorgung bleibt immer unter der gepinnten
 *    Huelle.
 *  - "Ueberlebt das Fundamentfenster" heisst NICHT "unbegrenzt offen": nach
 *    der gepinnten Frist verfaellt der Rest SICHTBAR mit typisiertem Grund.
 *
 * GRENZE, ehrlich benannt (Toni): der Aufschub schuetzt nur vor
 * Ueberversorgung bei bereits GEMESSENEM Fallen. Eine zu grosse Huelle bei
 * flachem oder steigendem BG bleibt moeglich - das ist Sache der
 * Huellen-Reihe, nicht dieses Bausteins.
 */
object DeferredPrime {

    enum class LapseReason { EXPIRED, NEW_MARKER, REVOKED, DISABLED }

    enum class Denial {
        DISABLED,
        NOT_PINNED,
        MARKER_MISMATCH,
        NOTHING_OPEN,
        LEDGER_HOLD,
        LATCH_ACTIVE,
        RECOVERY_UNCONFIRMED,
        SIGNAL_UNHEALTHY,
        MEASURED_LOW,
        REBOUND_ACTIVE,
        DESCENT_RISK,
        MANUAL_BOLUS_UNKNOWN,
        HULL_EXHAUSTED,
        BELOW_STEP,
    }

    /**
     * Restartfester Zustand. [openU] ist der offene AUFSCHUB - autorisiert,
     * nicht versprochen. Die letzte Verfallsbuchung bleibt stehen, damit der
     * Trail einen verfallenen Rest ZEIGEN kann statt ihn verschwinden zu
     * lassen.
     */
    data class State(
        val openU: Double = 0.0,
        val pinnedForMarkerTs: Long = 0L,
        val deadlineTs: Long = 0L,
        val horizonMin: Int = 0,
        val lastLapseReason: LapseReason? = null,
        val lastLapseU: Double = 0.0,
        val lastLapseTs: Long = 0L,
    ) {
        val valid: Boolean
            get() = openU.isFinite() && openU >= 0.0 &&
                lastLapseU.isFinite() && lastLapseU >= 0.0 &&
                (pinnedForMarkerTs > 0L || (openU == 0.0 && deadlineTs == 0L && horizonMin == 0)) &&
                (pinnedForMarkerTs <= 0L || (deadlineTs > pinnedForMarkerTs && horizonMin > 0))
    }

    /**
     * Pinnt Horizont und Frist an EINEN Marker. Ein neuer Marker laesst einen
     * offenen Rest des alten SICHTBAR verfallen (Vertrag 8) - er erbt nichts.
     * Derselbe Marker pinnt idempotent: die laufende Frist bleibt stehen,
     * auch wenn sich die Einstellung inzwischen geaendert hat (Vertrag 2).
     */
    fun pin(state: State, markerTs: Long, horizonMin: Int, endMin: Int): State {
        require(markerTs > 0L) { "pin needs a marker" }
        require(horizonMin > 0 && endMin > 0) { "pin needs positive bounds" }
        if (state.pinnedForMarkerTs == markerTs) return state
        val abgeraeumt = if (state.openU > 0.0) lapse(state, LapseReason.NEW_MARKER, markerTs) else state
        return abgeraeumt.copy(
            openU = 0.0,
            pinnedForMarkerTs = markerTs,
            deadlineTs = markerTs + endMin * 60_000L,
            horizonMin = horizonMin,
        )
    }

    /** Typisierter Verfall - der Rest verschwindet nie stumm (Vertrag 10). */
    fun lapse(state: State, reason: LapseReason, nowTs: Long): State = State(
        openU = 0.0,
        pinnedForMarkerTs = 0L,
        deadlineTs = 0L,
        horizonMin = 0,
        lastLapseReason = reason,
        lastLapseU = state.openU,
        lastLapseTs = nowTs,
    )

    fun expireIfDue(state: State, nowTs: Long): State =
        if (state.pinnedForMarkerTs > 0L && state.deadlineTs in 1..nowTs)
            lapse(state, LapseReason.EXPIRED, nowTs)
        else state

    /**
     * Bucht eine in DIESEM Zyklus zurueckgehaltene markerautorisierte Menge.
     * Anders als der Phase-A-Bestand ist das ein FLUSS: jeder blockierte
     * Zyklus verliert real genau die Menge, die er geliefert haette - die
     * Summe zaehlt nicht doppelt. Die Huelle deckelt trotzdem (Vertrag 7).
     */
    fun withhold(state: State, amountU: Double, hullRemainingU: Double): State {
        if (!amountU.isFinite() || amountU <= 0.0) return state
        if (state.pinnedForMarkerTs <= 0L) return state
        val deckel = if (hullRemainingU.isFinite()) max(0.0, hullRemainingU) else 0.0
        return state.copy(openU = min(state.openU + amountU, deckel))
    }

    /**
     * Vertrag 6+7 nach JEDER Lieferung: normale SMBs, Fundament und manuelle
     * Boli verkleinern denselben offenen Betrag, weil alle aus derselben
     * gepinnten Huelle zehren. `hullRemainingU` ist Huelle minus allem, was
     * seit dem Marker floss (inkl. manueller NORMAL-Boli).
     */
    fun clampToHull(state: State, hullRemainingU: Double): State {
        if (state.openU <= 0.0) return state
        val deckel = if (hullRemainingU.isFinite()) max(0.0, hullRemainingU) else 0.0
        return if (state.openU <= deckel) state else state.copy(openU = deckel)
    }

    /** Ergebnis der Freigabepruefung: entweder genau ein Pumpenschritt oder
     *  ein typisierter Grund, warum nicht. */
    data class Release(val stepU: Double, val denial: Denial?)

    /**
     * Hoechstens EIN Pumpenschritt je Zyklus (Vertrag 5) - die Reihenfolge
     * der Verweigerungsgruende ist typisiert und exportierbar. Der Aufrufer
     * prueft die WIRKUNG des Schritts zusaetzlich (finalVeto) - erst danach
     * darf gebucht werden.
     */
    fun releaseStep(
        state: State,
        nowTs: Long,
        enabled: Boolean,
        activeMarkerTs: Long,
        ledgerHold: Boolean,
        latchBlocksPositive: Boolean,
        /** Drei zusammenhaengende gesunde Zyklen mit Erholungsrate seit dem
         *  letzten aktiven Risiko - dieselbe Schwelle wie beim Riegel
         *  ([DescentRecoveryLatch.RECOVERY_RATE_MGDL_PER_MIN]). Eine
         *  Freigabe OHNE bestaetigte Erholung waere Vertragsbruch 4. */
        recoveryConfirmed: Boolean,
        signalHealthy: Boolean,
        measuredLow: Boolean,
        reboundRaw: Boolean,
        descentRiskActive: Boolean,
        manualBolusAfterMarkerU: Double?,
        pumpStepU: Double,
        hullRemainingU: Double,
    ): Release {
        fun nein(d: Denial) = Release(0.0, d)
        if (!enabled) return nein(Denial.DISABLED)
        if (state.pinnedForMarkerTs <= 0L) return nein(Denial.NOT_PINNED)
        if (activeMarkerTs != state.pinnedForMarkerTs) return nein(Denial.MARKER_MISMATCH)
        if (state.openU <= 0.0) return nein(Denial.NOTHING_OPEN)
        // Ablauf prueft der Aufrufer VOR dieser Funktion (expireIfDue) - ein
        // abgelaufener Zustand traegt hier bereits openU == 0.
        if (ledgerHold) return nein(Denial.LEDGER_HOLD)
        if (latchBlocksPositive) return nein(Denial.LATCH_ACTIVE)
        if (!recoveryConfirmed) return nein(Denial.RECOVERY_UNCONFIRMED)
        if (!signalHealthy) return nein(Denial.SIGNAL_UNHEALTHY)
        if (measuredLow) return nein(Denial.MEASURED_LOW)
        if (reboundRaw) return nein(Denial.REBOUND_ACTIVE)
        if (descentRiskActive) return nein(Denial.DESCENT_RISK)
        // Unlesbare Behandlungssicht: fail-closed, wie ueberall (Vertrag 8).
        if (manualBolusAfterMarkerU == null || !manualBolusAfterMarkerU.isFinite() ||
            manualBolusAfterMarkerU < 0.0
        ) return nein(Denial.MANUAL_BOLUS_UNKNOWN)
        if (!pumpStepU.isFinite() || pumpStepU <= 0.0) return nein(Denial.BELOW_STEP)
        val deckel = if (hullRemainingU.isFinite()) max(0.0, hullRemainingU) else 0.0
        val stepU = min(min(state.openU, deckel), pumpStepU)
        if (deckel <= 0.0) return nein(Denial.HULL_EXHAUSTED)
        if (stepU < pumpStepU) return nein(Denial.BELOW_STEP)
        return Release(stepU, null)
    }

    /** Bucht einen tatsaechlich publizierten Freigabeschritt ab. */
    fun consume(state: State, releasedU: Double): State =
        if (!releasedU.isFinite() || releasedU <= 0.0) state
        else state.copy(openU = max(0.0, state.openU - releasedU))
}

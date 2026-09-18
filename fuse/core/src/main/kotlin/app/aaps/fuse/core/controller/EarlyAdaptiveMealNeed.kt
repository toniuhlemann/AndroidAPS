package app.aaps.fuse.core.controller

/**
 * FRUEHER ADAPTIVER MEAL-BEDARF (H8) - Bauauftrag Toni 18.09., Default AUS.
 *
 * ANLASS (Replay-Matrix 18.09., feste CGM-Reihe mit Eigeninsulin-Rueckfuehrung):
 * zwischen Marker +10 und +45 min bleibt FUSE im autorisierten Mahlzeitenfenster
 * oft bei 0,05-U-Foundation-Schritten, obwohl der BGI-bereinigte W10-Antrieb
 * einen Anstieg bereits robust zeigt. Der 60-min-Freigabepunkt der Mittelbahn
 * gewichtet den Antrieb dort zu schwach.
 *
 * MECHANIK: ein kurzer Bruttobedarf auf eigenem Horizont
 *
 *     earlyReleaseMean = target + W10 * horizonMin
 *
 * wird mit der produktiven Freigabe-Mittelbahn per `max` zusammengefuehrt -
 * nie addiert. Aus der gewaehlten Bahn leitet der bestehende Liveness-/MEAL-
 * Bedarfsweg den Kandidaten ab; der Kanal selbst fuehrt `max(normal, live)`
 * zusammen, die Foundation steckt im Normalpfad. Es entsteht keine zweite
 * Insulinbuchhaltung und kein Zustand.
 *
 * BEWUSST OHNE EIGENABGABE IM BEDARF (Tonis Entscheidung 18.09.): W10 ist
 * insulinbereinigt und beschreibt den fortbestehenden Stoerungsdruck; der
 * Bruttobedarf darf nach einer Abgabe weiter bestehen. Fruehere eigene Abgaben
 * wirken ueber die vorhandenen IOB-/Aktivitaets-, Exposure-/Headroom-, Ratio-,
 * maxSMB- und Seriengrenzen auf den freigegebenen Kandidaten.
 *
 * EINTRITTSSCHWELLE (Tonis Korrektur 18.09.): der W10-Antrieb muss die
 * bestehende Druckschwelle des Liveness-Kanals erreichen,
 * [LivenessChannel.R_MIN_MGDL_PER_MIN] = 1,0 mg/dl/min - genau die Bedingung
 * des bewerteten Codex-Replays. `riseRampLowR` (live 1,5) ist davon getrennt
 * und bleibt ausschliesslich Unterkante der SMB-Ratio-Rampe: zwischen 1,0 und
 * riseRampLowR darf H8 aktiv sein, die Ratio bleibt am unteren Rampenwert.
 *
 * WAS UNVERAENDERT BLEIBT: das Theil-Sen-Fenster (W10), der globale
 * Release-Horizont (60), der Safety-/Liability-Horizont, die W18-Bahn des
 * Reversal-Schutzes und jedes Tor dahinter. Diese Komponente entscheidet nur,
 * OB der Bruttobedarf in diesem Zyklus gebildet wird, und bildet ihn.
 */
object EarlyAdaptiveMealNeed {

    /** Zulaessige Horizonte [min]; 0 = AUS (Produktionsdefault). */
    val ALLOWED_HORIZONS_MIN: Set<Int> = setOf(0, 6, 8, 10)

    /** Untere Markeraltersgrenze, inklusiv. */
    const val MIN_MARKER_AGE_MS = 10 * 60_000L

    /** Obere Markeraltersgrenze, inklusiv. */
    const val MAX_MARKER_AGE_MS = 45 * 60_000L

    /** Quellenkennung im Trail, z. B. `EARLY_ADAPTIVE_MEAL_H8`. */
    fun sourceId(horizonMin: Int): String = "EARLY_ADAPTIVE_MEAL_H$horizonMin"

    /**
     * Wirksamer Horizont: nur die zulaessigen Werte, alles andere ist AUS.
     * Ein fehlender oder unbekannter Schluessel (alte Settings, alte Backups)
     * faellt damit sicher auf 0.
     */
    fun effectiveHorizonMin(configuredMin: Int): Int =
        if (configuredMin in ALLOWED_HORIZONS_MIN) configuredMin else 0

    enum class Denial {
        DISABLED,
        NOT_MEAL_AUTHORIZED,
        AUTHORIZATION_MISMATCH,
        AUTHORIZATION_EXPIRED,
        MARKER_TOO_YOUNG,
        MARKER_TOO_OLD,
        SIGNAL_NOT_READY,
        SIGNAL_NOT_MATURE,
        DRIVE_UNAVAILABLE,
        DRIVE_BELOW_THRESHOLD,
        UKF_UNAVAILABLE,
        UKF_NEGATIVE,
        TARGET_INVALID,
    }

    data class Input(
        /** Wirksamer Horizont ([effectiveHorizonMin]); 0 = AUS. */
        val horizonMin: Int,
        /** [DosingContext.Decision.mealAuthorized] dieses Zyklus. */
        val mealAuthorized: Boolean,
        /** [DosingContext.Decision.authorizationId]: gepinnte Markeridentitaet. */
        val authorizationId: Long,
        /** Der aktuell wirksame Marker. */
        val markerTs: Long,
        /** [DosingContext.Decision.authorizationExpiresAt]. */
        val authorizationExpiresAtMs: Long,
        val nowMs: Long,
        /** Signalgesundheit READY. */
        val signalReady: Boolean,
        /** Strenge Reife: keine gelockerte Wiedereinstiegsreife, Vollreife erreicht. */
        val signalMature: Boolean,
        /** Robuster adaptiver W10-Antrieb (BGI-bereinigt) [mg/dl/min]. */
        val w10DriveMgdlPerMin: Double?,
        val ukfRatePerMin: Double,
        /**
         * Eintrittsschwelle fuer den W10-Antrieb [mg/dl/min]. Der Runner uebergibt
         * die bestehende Liveness-Druckschwelle [LivenessChannel.R_MIN_MGDL_PER_MIN]
         * - NIE riseRampLowR (Unterkante der Ratio-Rampe, eine andere Rolle).
         */
        val eligibilityThresholdMgdlPerMin: Double,
        val targetMgdl: Double,
    )

    /**
     * [active] true = Bruttobedarf gebildet ([earlyReleaseMeanMgdl] gesetzt).
     * [markerAgeMin] ist gesetzt, sobald ein Markeralter bestimmbar war.
     */
    data class Decision(
        val active: Boolean,
        val denial: Denial?,
        val markerAgeMin: Double?,
        val earlyReleaseMeanMgdl: Double?,
    )

    fun decide(i: Input): Decision {
        fun no(d: Denial, age: Double? = null) = Decision(false, d, age, null)
        if (i.horizonMin !in ALLOWED_HORIZONS_MIN || i.horizonMin == 0) return no(Denial.DISABLED)
        if (!i.mealAuthorized) return no(Denial.NOT_MEAL_AUTHORIZED)
        if (i.authorizationId <= 0L || i.authorizationId != i.markerTs) return no(Denial.AUTHORIZATION_MISMATCH)
        if (i.nowMs >= i.authorizationExpiresAtMs) return no(Denial.AUTHORIZATION_EXPIRED)
        val ageMs = i.nowMs - i.authorizationId
        val ageMin = ageMs / 60_000.0
        if (ageMs < MIN_MARKER_AGE_MS) return no(Denial.MARKER_TOO_YOUNG, ageMin)
        if (ageMs > MAX_MARKER_AGE_MS) return no(Denial.MARKER_TOO_OLD, ageMin)
        if (!i.signalReady) return no(Denial.SIGNAL_NOT_READY, ageMin)
        if (!i.signalMature) return no(Denial.SIGNAL_NOT_MATURE, ageMin)
        val drive = i.w10DriveMgdlPerMin
        if (drive == null || !drive.isFinite()) return no(Denial.DRIVE_UNAVAILABLE, ageMin)
        if (!i.eligibilityThresholdMgdlPerMin.isFinite() || drive < i.eligibilityThresholdMgdlPerMin)
            return no(Denial.DRIVE_BELOW_THRESHOLD, ageMin)
        if (!i.ukfRatePerMin.isFinite()) return no(Denial.UKF_UNAVAILABLE, ageMin)
        if (i.ukfRatePerMin < 0.0) return no(Denial.UKF_NEGATIVE, ageMin)
        if (!i.targetMgdl.isFinite() || i.targetMgdl <= 0.0) return no(Denial.TARGET_INVALID, ageMin)
        return Decision(
            active = true,
            denial = null,
            markerAgeMin = ageMin,
            earlyReleaseMeanMgdl = i.targetMgdl + drive * i.horizonMin,
        )
    }

    /** `max`, nie Addition: ohne aktiven Bedarf bleibt die produktive Bahn bitgleich. */
    fun effectiveReleaseMean(productionReleaseMeanMgdl: Double, d: Decision): Double =
        d.earlyReleaseMeanMgdl?.takeIf { d.active }?.let { maxOf(productionReleaseMeanMgdl, it) }
            ?: productionReleaseMeanMgdl
}

package app.aaps.fuse.core.controller

/**
 * WANN DARF DER PHASE-A-SOFORTBATCH NACH EINEM ABFALL WIEDER RAUS?
 * (Bauauftrag Toni 25.08. spaet.)
 *
 * DER ANLASS. Der Sofortbatch wird korrekt zurueckgehalten, solange
 * `MEASURED_DESCENT_RISK`, Low-Threat, Zero-Latch oder Rebound aktiv
 * sind. Ist das AKTUELLE Risiko vorbei, haengt er aber weiter am
 * allgemeinen [DescentRecoveryLatch], und der verlangt drei Zyklen mit
 * mindestens +0,20 mg/dl/min. Am Abendessen des 25.08. blieb der volle
 * Sofortanteil dadurch die GANZE Phase A blockiert, obwohl der gemessene
 * Abfall um 18:16 zu Ende war: die UKF-Rate erreichte in der Erholung
 * maximal +0,196 - vier Tausendstel zu wenig -, und `descentRecoveryCycles`
 * stand durchgehend auf 0. Am Ende von Phase A ging der Anteil in den
 * schrittweisen Aufschub. Das verfehlt die Funktion "Sofortanteil".
 *
 * ZWEI GETRENNTE FRAGEN, und ihre Vermischung war der Fehler:
 *
 *   1. IST GERADE NOCH GEFAHR? -> [Hazards]. Bleibt absolut. Kein
 *      Marker, keine Ruhe und kein Zeitablauf ueberstimmt sie.
 *   2. IST DIE LAGE NACH DEM ENDE DER GEFAHR STABIL GENUG? -> diese
 *      Klasse. Sie ersetzt NICHT die erste Frage, sondern kommt nach ihr.
 *
 * ZWEI WEGE HERAUS, und der zweite ist neu:
 *   [Mode.RISING]  die bestehende schnelle Erholung des allgemeinen
 *                  Latches (UKF >= +0,20 ueber drei Zyklen).
 *   [Mode.CALM]    eine bestaetigte RUHIGE Lage: UKF nicht mehr materiell
 *                  negativ, q1 faellt nicht weiter, genug Abstand zum
 *                  Guard-Boden - und das LUECKENLOS ueber mehrere echte
 *                  Zyklen. Ein einzelner flacher Messwert genuegt nicht;
 *                  jeder erneut negative Zyklus setzt den Zaehler auf 0.
 *
 * DER GELTUNGSBEREICH IST ENG: ausschliesslich `MEAL_UPFRONT` innerhalb
 * Phase A. Normal-SMB, Liveness, Prime-Rest und Phase B bleiben unter dem
 * allgemeinen Descent-Latch. Diese Klasse trifft daher gar keine
 * Dosierentscheidung - sie beantwortet nur, ob der Batch-Aufschub endet.
 *
 * DIE PARAMETER SIND INJIZIERT UND HABEN BEWUSST KEINE PRODUKTIONS-
 * DEFAULTS: sie werden am echten Abendfall und an Kontrollverlaeufen
 * replay-kalibriert, und zwar durch den VOLLSTAENDIGEN Endpfad
 * (liftUpfront -> finalVerify -> MarkerFloor -> MeasuredDescentGate ->
 * Publikation). Der Grund steht im Review: [MarkerFloor] hebt einen
 * typisierten Grant nach dem `finalVerify` wieder auf die autorisierte
 * Menge an - eine Freigabe bei knappem Guard-Abstand ist deshalb NICHT
 * durch den Guard-Boden begrenzt, wie es auf den ersten Blick aussieht.
 */
object UpfrontRecovery {

    enum class Mode { NONE, RISING, CALM }

    enum class Denial {
        /** Kein Aufschub offen - die Frage stellt sich nicht. */
        NOTHING_DEFERRED,

        /** Aktuelle Gefahr. Absolut. */
        CURRENT_HAZARD,

        /** Ausserhalb Phase A. */
        NOT_PHASE_A,

        /** Kein Marker / keine Batch-Identitaet. */
        NO_AUTHORITY,

        /** Ruhe noch nicht lange genug bestaetigt. */
        CALM_STREAK_SHORT,

        /** UKF noch materiell negativ. */
        STILL_FALLING,

        /** q1 faellt weiter. */
        Q1_FALLING,

        /** Zu nah am Guard-Boden. */
        GUARD_DISTANCE,

        /** Ruhe-Ausgang ausgeschaltet. */
        DISABLED,
    }

    /**
     * Die AKTUELLEN harten Blocker. Alle sechs sind Ausschlusskriterien;
     * der Marker ueberstimmt keinen davon.
     */
    class Hazards(
        val descentRisk: Boolean,
        val lowThreat: Boolean,
        val zeroLatch: Boolean,
        val rebound: Boolean,
        val signalUnhealthy: Boolean,
        val technical: Boolean,
        val ledgerHold: Boolean,
    ) {

        val any: Boolean
            get() = descentRisk || lowThreat || zeroLatch || rebound ||
                signalUnhealthy || technical || ledgerHold

        /** Fuer den Export: welche genau. */
        val names: String
            get() = listOfNotNull(
                "descentRisk".takeIf { descentRisk },
                "lowThreat".takeIf { lowThreat },
                "zeroLatch".takeIf { zeroLatch },
                "rebound".takeIf { rebound },
                "signal".takeIf { signalUnhealthy },
                "technical".takeIf { technical },
                "ledgerHold".takeIf { ledgerHold },
            ).joinToString("+").ifEmpty { "none" }
    }

    /**
     * Die Ruheparameter. KEIN Produktionsdefault - der Aufrufer muss sie
     * nennen, und bis zur Replay-Kalibrierung nennt sie nur der Replay.
     */
    class Params private constructor(
        val enabled: Boolean,
        /** Wieviele lueckenlose Ruhezyklen. */
        val calmCycles: Int,
        /** Kleinste zulaessige UKF-Rate [mg/dl/min]. */
        val minUkf: Double,
        /** Mindestabstand von q1 zum Guard-Boden [mg/dl]. */
        val minGuardDistanceMgdl: Double,
    ) {

        companion object {

            val OFF = Params(false, 0, 0.0, 0.0)

            /**
             * Unbrauchbare Werte ergeben [OFF] - kein stiller Rueckfall auf
             * eine erfundene Kalibrierung.
             */
            fun of(calmCycles: Int, minUkf: Double, minGuardDistanceMgdl: Double): Params =
                if (calmCycles in 1..20 && minUkf.isFinite() && minUkf >= -1.0 && minUkf <= 1.0 &&
                    minGuardDistanceMgdl.isFinite() && minGuardDistanceMgdl >= 0.0 &&
                    minGuardDistanceMgdl <= 100.0
                ) Params(true, calmCycles, minUkf, minGuardDistanceMgdl)
                else OFF
        }
    }

    /** Der fortgeschriebene Zaehler - restartfest im Ledger zu halten. */
    class Track(val calmStreak: Int = 0, val lastTs: Long = 0L)

    class Result(
        val mode: Mode,
        val track: Track,
        val denial: Denial?,
        val required: Int,
        val guardDistanceMgdl: Double?,
        val hazards: String,
    ) {

        val releases: Boolean get() = mode != Mode.NONE
    }

    /**
     * @param params die injizierten Ruheparameter.
     * @param prior der Zaehlerstand aus dem Ledger.
     * @param deferredOpen ist ueberhaupt ein Batch aufgeschoben?
     * @param inPhaseA laeuft Phase A noch?
     * @param hasAuthority Marker- und Batchidentitaet vorhanden?
     * @param hazards die AKTUELLEN harten Blocker.
     * @param risingConfirmed hat der allgemeine Latch seine schnelle
     *   Erholung bestaetigt? Dieser Weg bleibt unveraendert bestehen.
     * @param ukfRatePerMin aktuelle UKF-Rate.
     * @param q1Falling faellt q1 gegenueber dem Vorzyklus?
     * @param guardDistanceMgdl Abstand von q1 zum Guard-Boden.
     * @param nowTs Anker dieses Zyklus - fuer den Restart-Schutz.
     */
    fun evaluate(
        params: Params,
        prior: Track,
        deferredOpen: Boolean,
        inPhaseA: Boolean,
        hasAuthority: Boolean,
        hazards: Hazards,
        risingConfirmed: Boolean,
        ukfRatePerMin: Double?,
        q1Falling: Boolean,
        guardDistanceMgdl: Double?,
        nowTs: Long,
    ): Result {
        fun nein(d: Denial, t: Track = Track()) =
            Result(Mode.NONE, t, d, params.calmCycles, guardDistanceMgdl, hazards.names)

        // Die aktuelle Gefahr steht VOR allem anderen und loescht den
        // Ruhezaehler - eine Ruhe, die von einer Gefahr unterbrochen wurde,
        // war keine.
        if (hazards.any) return nein(Denial.CURRENT_HAZARD)
        if (!deferredOpen) return nein(Denial.NOTHING_DEFERRED, prior)
        if (!inPhaseA) return nein(Denial.NOT_PHASE_A, prior)
        if (!hasAuthority) return nein(Denial.NO_AUTHORITY)

        // WEG 1 - unveraendert: die bestaetigte schnelle Erholung des
        // allgemeinen Latches. Sie brauchte nie eine Ruhezaehlung.
        if (risingConfirmed)
            return Result(Mode.RISING, prior, null, params.calmCycles,
                          guardDistanceMgdl, hazards.names)

        if (!params.enabled) return nein(Denial.DISABLED, prior)

        // WEG 2 - die bestaetigte ruhige Lage. Jede verletzte Bedingung
        // setzt den Zaehler auf 0; ein einzelner flacher Zyklus genuegt
        // ausdruecklich nicht.
        if (ukfRatePerMin == null || !ukfRatePerMin.isFinite() || ukfRatePerMin < params.minUkf)
            return nein(Denial.STILL_FALLING)
        if (q1Falling) return nein(Denial.Q1_FALLING)
        if (guardDistanceMgdl == null || !guardDistanceMgdl.isFinite() ||
            guardDistanceMgdl < params.minGuardDistanceMgdl
        ) return nein(Denial.GUARD_DISTANCE)

        // LUECKENLOS heisst: der vorige Ruhezyklus muss der VORIGE ZYKLUS
        // gewesen sein. Nach einem Neustart oder einer Zykluspause faengt
        // die Zaehlung neu an, statt erfundene Zyklen zu erben.
        val anschluss = prior.lastTs > 0L && nowTs > prior.lastTs &&
            nowTs - prior.lastTs <= LUECKENLOS_MAX_MS
        val streak = if (anschluss) prior.calmStreak + 1 else 1
        val track = Track(streak, nowTs)
        if (streak < params.calmCycles)
            return Result(Mode.NONE, track, Denial.CALM_STREAK_SHORT,
                          params.calmCycles, guardDistanceMgdl, hazards.names)
        return Result(Mode.CALM, track, null, params.calmCycles,
                      guardDistanceMgdl, hazards.names)
    }

    /**
     * Groesster Abstand zweier Zyklen, der noch als "lueckenlos" gilt.
     * Zwei Minuten decken die gemessene Kadenz (58-62 s) samt einer
     * ausgefallenen Runde; darueber ist die Reihe unterbrochen und die
     * Ruhe nicht mehr bestaetigt.
     */
    const val LUECKENLOS_MAX_MS = 2 * 60_000L
}

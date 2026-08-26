package app.aaps.fuse.core.controller

/**
 * WANN DARF DER PHASE-A-SOFORTBATCH NACH EINEM ABFALL WIEDER RAUS?
 * (Bauauftrag Toni 25.08. spaet, neu geschnitten nach der Messung des
 * Abendfalls.)
 *
 * DER ANLASS. Der Sofortbatch wird korrekt zurueckgehalten, solange
 * `MEASURED_DESCENT_RISK`, Low-Threat, Zero-Latch oder Rebound aktiv sind.
 * Ist das AKTUELLE Risiko vorbei, haengt er aber weiter am allgemeinen
 * [DescentRecoveryLatch], und der verlangt drei Zyklen mit mindestens
 * +0,20 mg/dl/min.
 *
 * DIE MESSUNG am Abendessen des 25.08., 18:07-18:27 (20 Zyklen Phase A):
 *
 *   descentLatchActive   23 von 23 Zyklen
 *   descentRiskActive    10 von 23 - die letzten NEUN durchgehend false,
 *                        `NOT_FALLING`, Rate STEIGEND (+0,070 .. +0,196)
 *   Batch                3,60 U geplant, 0 angefordert, 0 publiziert
 *
 * Der blockierende Grund war also abgestanden. ABER: BG 76-78 bei einem
 * Guard-Boden von 70 - sechs bis acht mg/dl Abstand -, und unmittelbar
 * nach Phase A meldete der Regler selbst `NO_DEMAND` mit
 * `insulinReq <= 0`. Ein Ruhe-Ausgang, der nur den Latch loest, haette
 * also nichts freigegeben - aber [MarkerFloor] liest keinen Bedarf,
 * sondern eine Autorisierung. Aus "Block entfaellt" waeren 3,60 U bei
 * BG 78 geworden.
 *
 * ZWEI GETRENNTE FRAGEN, und ihre Vermischung war der Fehler:
 *
 *   1. IST GERADE NOCH GEFAHR? -> [Hazards]. Bleibt absolut. Kein Marker,
 *      keine Ruhe und kein Zeitablauf ueberstimmt sie.
 *   2. IST DIE LAGE NACH DEM ENDE DER GEFAHR STABIL GENUG - UND WOFUER?
 *      -> diese Klasse. Sie kommt nach der ersten Frage, nicht statt ihr.
 *
 * WARUM [Decision] EIN SEALED INTERFACE IST UND KEIN BOOLEAN.
 * Die Vorgaengerfassung gab `releases: Boolean` heraus. Ein Aufrufer mit
 * `if (result.releases)` erreicht damit denselben Vollbatchpfad wie die
 * bestaetigte schnelle Erholung - eine versteckte Vollbatch-Autorisierung.
 * Der Typ zwingt den Aufrufer jetzt zur exhaustiven Fallunterscheidung.
 *
 * UND [Decision.CalmRecovered] TRAEGT ABSICHTLICH KEINE MENGE. Nicht
 * `authCapU`, nicht "freigegeben bis". Der Grund steht in [MarkerFloor]:
 * dort wird angehoben, sobald ein `grant` ankommt, und `grant == null`
 * bedeutet "kein Boden". Die Sicherheitskante ist also nicht ein Deckel
 * IN MarkerFloor, sondern dass im ruhigen Pfad gar kein
 * `MEAL_UPFRONT`-Grant entsteht. Ein Mengenfeld an dieser Stelle waere
 * genau der Weg, auf dem die volle Autorisierung doch wieder
 * durchgereicht wird.
 *
 * DER GELTUNGSBEREICH IST ENG: ausschliesslich `MEAL_UPFRONT` innerhalb
 * Phase A. Normal-SMB, Liveness, Prime-Rest und Phase B bleiben unter dem
 * allgemeinen Descent-Latch. Diese Klasse trifft daher gar keine
 * Dosierentscheidung - sie beantwortet nur, ob und WOFUER der
 * Batch-Aufschub endet.
 *
 * DIE PARAMETER SIND INJIZIERT UND HABEN BEWUSST KEINE PRODUKTIONS-
 * DEFAULTS: sie werden am echten Abendfall und an Kontrollverlaeufen
 * replay-kalibriert, und zwar durch den VOLLSTAENDIGEN Endpfad
 * (liftUpfront -> finalVerify -> MarkerFloor -> MeasuredDescentGate ->
 * Publikation).
 */
object UpfrontRecovery {

    /**
     * WIE EIN BESTAETIGT RUHIGER BATCH BEHANDELT WIRD. Beide Wege sind
     * architektonisch sauber und werden im Replay VERGLICHEN - keiner ist
     * voreingestellt.
     */
    enum class CalmTreatment {

        /**
         * BEDARFSBEGRENZT: hoechstens das, was der normale Pfad VOR
         * [MarkerFloor] tatsaechlich verlangt. Kein `MEAL_UPFRONT`-Grant
         * wird gestempelt, also kann der Boden nichts wiederherstellen.
         * Im Abendfall des 25.08. liefert dieser Weg NICHTS, weil der
         * Regler dort `insulinReq <= 0` sah - das ist kein Mangel des
         * Weges, sondern sein Zweck.
         */
        DEMAND_LIMITED,

        /**
         * KONTROLLIERT VERSCHOBEN: der offene Sofortanteil geht in den
         * schrittweisen [DeferredPrime]-Pfad, statt zu verfallen oder als
         * Vollbatch auszuzahlen. Bewahrt die Menge, ohne 3,60 U bei BG 78
         * auf einmal freizugeben.
         */
        SHIFT_TO_DEFERRED,
    }

    /** Welcher Weg den letzten Trackeintrag erzeugt hat. */
    enum class TrackMode { NONE, RISING, CALM }

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

        /** Ruhe-Ausgang ausgeschaltet oder ohne Behandlungswahl. */
        DISABLED,
    }

    /**
     * Die AKTUELLEN harten Blocker. Alle sieben sind Ausschlusskriterien;
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
        /** Wie ein bestaetigt ruhiger Batch behandelt wird. */
        val calmTreatment: CalmTreatment?,
        /** Die Regel-/Konfigurationsgeneration, unter der beobachtet wird. */
        val ruleSetVersion: Int,
    ) {

        /**
         * DIE SECHSTE IDENTITAET (Toni 25.08. spaet). Ein Streak darf nach
         * einem Settingswechsel oder Flash nicht unter ANDEREN Schwellen
         * fortgesetzt werden - sonst loesen zwei Beobachtungen unter alten
         * und eine dritte unter gelockerten Parametern gemeinsam eine
         * Freigabe aus. Der Fingerprint deckt alles ab, was die Bedeutung
         * eines Ruhezyklus bestimmt.
         */
        val fingerprint: String
            get() = if (!enabled) "off"
            else "rs$ruleSetVersion|c$calmCycles|u$minUkf|g$minGuardDistanceMgdl|" +
                "t${calmTreatment?.name}"

        companion object {

            val OFF = Params(false, 0, 0.0, 0.0, null, 0)

            /**
             * Unbrauchbare Werte ergeben [OFF] - kein stiller Rueckfall auf
             * eine erfundene Kalibrierung. Die Behandlungswahl ist Pflicht:
             * ohne sie gibt es keinen ruhigen Pfad, nur den alten Vertrag.
             */
            fun of(
                calmCycles: Int,
                minUkf: Double,
                minGuardDistanceMgdl: Double,
                calmTreatment: CalmTreatment,
                ruleSetVersion: Int,
            ): Params =
                if (calmCycles in 1..20 && minUkf.isFinite() && minUkf >= -1.0 && minUkf <= 1.0 &&
                    minGuardDistanceMgdl.isFinite() && minGuardDistanceMgdl >= 0.0 &&
                    minGuardDistanceMgdl <= 100.0 && ruleSetVersion > 0
                ) Params(true, calmCycles, minUkf, minGuardDistanceMgdl, calmTreatment,
                         ruleSetVersion)
                else OFF
        }
    }

    /**
     * DER FORTGESCHRIEBENE RUHEZAEHLER - und alles, was ihn nach einem
     * Prozessneustart ueberhaupt fortsetzbar macht (Toni 25.08. spaet).
     *
     * Nur den Streak zu speichern genuegt nicht. Ein wiederaufgenommener
     * Zaehler gibt potenziell MEHR Insulin frei; er darf deshalb nur
     * fortgesetzt werden, wenn Markeridentitaet, Signalanschluss und
     * Zeitkontinuitaet stimmen - und wenn saemtliche aktuellen Gefahren im
     * aufnehmenden Zyklus erneut negativ geprueft wurden. Letzteres
     * garantiert [evaluate] dadurch, dass [Hazards.any] VOR jeder
     * Fortschreibung zurueckkehrt.
     *
     * ZUM CODEC-BEFUND: heute ist dieser Zustand faktisch NICHT restartfest
     * - `FuseLedgerAdapter` deklariert das Feld, der Codec fuehrt es weder
     * in `encodeEpisodes` noch in `encodeFoundation`. Der Typ ist hier
     * bereits so geschnitten, dass eine spaetere Persistierung die
     * Identitaeten mitnehmen MUSS; [ofPersisted] lehnt unvollstaendige oder
     * widerspruechliche Kombinationen fail-closed ab.
     */
    class Track(
        /** `armedTs` des Markers, zu dem dieser Zaehler gehoert. */
        val markerIdentity: Long = 0L,
        val streak: Int = 0,
        /** Der Signalpunkt, auf dem der letzte Ruhezyklus beruhte. */
        val lastAcceptedSourceTs: Long = 0L,
        /** Wann zuletzt ausgewertet wurde. */
        val lastEvaluationTs: Long = 0L,
        val mode: TrackMode = TrackMode.NONE,
        /**
         * Die Regel-/Konfigurationsgeneration, unter der dieser Zaehler
         * entstanden ist. Ein Wechsel verwirft ihn - er wird niemals
         * umgedeutet.
         */
        val fingerprint: String = "",
    ) {

        /**
         * Ein Zaehler ohne vollstaendige Identitaet ist kein Zaehler.
         * Entweder alles leer, oder alles gesetzt.
         */
        val consistent: Boolean
            get() = if (streak <= 0) markerIdentity == 0L && lastAcceptedSourceTs == 0L &&
                lastEvaluationTs == 0L && mode == TrackMode.NONE && fingerprint.isEmpty()
            else markerIdentity > 0L && lastAcceptedSourceTs > 0L &&
                lastEvaluationTs > 0L && mode != TrackMode.NONE && streak <= MAX_STREAK &&
                fingerprint.isNotEmpty()

        companion object {

            val EMPTY = Track()

            /** Obergrenze gegen einen davongelaufenen Zaehler. */
            const val MAX_STREAK = 1000

            /**
             * FAIL-CLOSED: was der Codec liefert, wird geprueft, nicht
             * geglaubt. Jede unvollstaendige oder widerspruechliche
             * Kombination ergibt [EMPTY] - der konservative Zustand, weil
             * ein fortgesetzter Zaehler mehr Insulin freigeben kann als ein
             * neu begonnener.
             */
            fun ofPersisted(
                markerIdentity: Long,
                streak: Int,
                lastAcceptedSourceTs: Long,
                lastEvaluationTs: Long,
                mode: TrackMode,
                fingerprint: String,
            ): Track {
                val t = Track(markerIdentity, streak, lastAcceptedSourceTs, lastEvaluationTs,
                              mode, fingerprint)
                return if (t.consistent) t else EMPTY
            }
        }
    }

    /**
     * DAS ERGEBNIS - als sealed interface, damit der Aufrufer die drei
     * Faelle nicht zusammenfassen KANN.
     */
    sealed interface Decision {

        val track: Track
        val hazards: String
        val guardDistanceMgdl: Double?

        /**
         * NUR FUER DEN EXPORT. Steuerfluss laeuft ueber `when` auf den Typ -
         * ein String liesse sich wieder zu `== "CALM_RECOVERED" || ...`
         * zusammenfassen, und genau das soll der Typ verhindern.
         */
        val modeName: String
            get() = when (this) {
                is Blocked -> "BLOCKED"
                is FullBatchEligible -> "FULL_BATCH_ELIGIBLE"
                is CalmRecovered -> "CALM_RECOVERED"
            }

        /** Aktuelle Gefahr, fehlende Vorbedingung oder Ruhe noch nicht reif. */
        class Blocked(
            override val track: Track,
            override val hazards: String,
            override val guardDistanceMgdl: Double?,
            val denial: Denial,
            val requiredCycles: Int,
        ) : Decision

        /**
         * Die bestehende, streng bestaetigte RISING-Erholung des
         * allgemeinen Latches. Fuehrt auf den unveraenderten
         * Vollbatchpfad - hier DARF ein `MEAL_UPFRONT`-Grant entstehen.
         */
        class FullBatchEligible(
            override val track: Track,
            override val hazards: String,
            override val guardDistanceMgdl: Double?,
        ) : Decision

        /**
         * Gefahr vorbei, aber keine starke Erholung.
         *
         * HIER STEHT ABSICHTLICH KEINE MENGE. Kein `authCapU`, kein
         * "freigegeben bis", kein `eligibleU`. Wer hier ein Mengenfeld
         * ergaenzt, oeffnet den Weg, auf dem [MarkerFloor] die volle
         * Autorisierung wiederherstellt - genau die Kante, die der
         * Abendfall des 25.08. sichtbar gemacht hat. Der Aufrufer muss
         * ueber [treatment] entscheiden, was geschieht, und darf dabei
         * KEINEN `MEAL_UPFRONT`-Grant stempeln.
         */
        class CalmRecovered(
            override val track: Track,
            override val hazards: String,
            /** Nicht-null: der Abstand wurde geprueft, sonst waere es [Blocked]. */
            override val guardDistanceMgdl: Double,
            val calmStreak: Int,
            val treatment: CalmTreatment,
        ) : Decision
    }

    /**
     * @param params die injizierten Ruheparameter.
     * @param prior der Zaehlerstand aus dem Ledger.
     * @param deferredOpen ist ueberhaupt ein Batch aufgeschoben?
     * @param inPhaseA laeuft Phase A noch?
     * @param markerIdentity `armedTs` des gepinnten Markers; 0 = keine
     *   Autorisierung. Ein Markerwechsel bricht den Ruhezaehler ab - der
     *   Zaehler gehoert zu EINER Autorisierung, nicht zum Geraet.
     * @param hazards die AKTUELLEN harten Blocker.
     * @param risingConfirmed hat der allgemeine Latch seine schnelle
     *   Erholung bestaetigt? Dieser Weg bleibt unveraendert bestehen.
     * @param ukfRatePerMin aktuelle UKF-Rate.
     * @param q1Falling faellt q1 gegenueber dem Vorzyklus?
     * @param guardDistanceMgdl Abstand von q1 zum Guard-Boden.
     * @param sourceTs der Signalpunkt dieses Zyklus - Anschlussnachweis.
     * @param nowTs Anker dieses Zyklus - fuer den Restart-Schutz.
     */
    fun evaluate(
        params: Params,
        prior: Track,
        deferredOpen: Boolean,
        inPhaseA: Boolean,
        markerIdentity: Long,
        hazards: Hazards,
        risingConfirmed: Boolean,
        ukfRatePerMin: Double?,
        q1Falling: Boolean,
        guardDistanceMgdl: Double?,
        sourceTs: Long,
        nowTs: Long,
    ): Decision {
        fun nein(d: Denial, t: Track = Track.EMPTY) =
            Decision.Blocked(t, hazards.names, guardDistanceMgdl, d, params.calmCycles)

        // Die aktuelle Gefahr steht VOR allem anderen und loescht den
        // Ruhezaehler - eine Ruhe, die von einer Gefahr unterbrochen wurde,
        // war keine. Dass dieser Zweig zuerst kommt, ist zugleich die
        // Zusicherung aus dem Track-KDoc: ein fortgesetzter Zaehler hat die
        // Gefahren in DIESEM Zyklus erneut negativ geprueft.
        if (hazards.any) return nein(Denial.CURRENT_HAZARD)
        if (!deferredOpen) return nein(Denial.NOTHING_DEFERRED, geerbt(prior, markerIdentity, params.fingerprint))
        if (!inPhaseA) return nein(Denial.NOT_PHASE_A, geerbt(prior, markerIdentity, params.fingerprint))
        if (markerIdentity <= 0L) return nein(Denial.NO_AUTHORITY)

        // WEG 1 - unveraendert: die bestaetigte schnelle Erholung des
        // allgemeinen Latches. Sie brauchte nie eine Ruhezaehlung.
        if (risingConfirmed) return Decision.FullBatchEligible(
            Track(markerIdentity,
                  maxOf(1, geerbt(prior, markerIdentity, params.fingerprint).streak),
                  sourceTs, nowTs, TrackMode.RISING, params.fingerprint),
            hazards.names, guardDistanceMgdl,
        )

        val treatment = params.calmTreatment
        if (!params.enabled || treatment == null)
            return nein(Denial.DISABLED, geerbt(prior, markerIdentity, params.fingerprint))

        // WEG 2 - die bestaetigte ruhige Lage. Jede verletzte Bedingung
        // setzt den Zaehler auf 0; ein einzelner flacher Zyklus genuegt
        // ausdruecklich nicht.
        if (ukfRatePerMin == null || !ukfRatePerMin.isFinite() || ukfRatePerMin < params.minUkf)
            return nein(Denial.STILL_FALLING)
        if (q1Falling) return nein(Denial.Q1_FALLING)
        if (guardDistanceMgdl == null || !guardDistanceMgdl.isFinite() ||
            guardDistanceMgdl < params.minGuardDistanceMgdl
        ) return nein(Denial.GUARD_DISTANCE)

        // LUECKENLOS heisst dreierlei: dieselbe Autorisierung, ein
        // Signalanschluss und Zeitkontinuitaet. Nach einem Neustart, einem
        // Markerwechsel oder einer Zykluspause faengt die Zaehlung neu an,
        // statt erfundene Zyklen zu erben.
        val basis = geerbt(prior, markerIdentity, params.fingerprint)
        val anschluss = basis.streak > 0 &&
            nowTs > basis.lastEvaluationTs &&
            nowTs - basis.lastEvaluationTs <= LUECKENLOS_MAX_MS &&
            sourceTs > basis.lastAcceptedSourceTs &&
            sourceTs - basis.lastAcceptedSourceTs <= LUECKENLOS_MAX_MS
        val streak = if (anschluss) basis.streak + 1 else 1
        val track = Track(markerIdentity, streak, sourceTs, nowTs, TrackMode.CALM,
                          params.fingerprint)
        if (streak < params.calmCycles)
            return Decision.Blocked(track, hazards.names, guardDistanceMgdl,
                                    Denial.CALM_STREAK_SHORT, params.calmCycles)
        return Decision.CalmRecovered(track, hazards.names, guardDistanceMgdl, streak, treatment)
    }

    /**
     * Ein Zaehler gehoert zu EINER Autorisierung. Passt die Markeridentitaet
     * nicht - oder ist der geladene Zustand inkonsistent -, wird er
     * verworfen statt uminterpretiert.
     */
    private fun geerbt(prior: Track, markerIdentity: Long, fingerprint: String): Track =
        if (prior.consistent && prior.streak > 0 && prior.markerIdentity == markerIdentity &&
            prior.fingerprint == fingerprint
        ) prior
        else Track.EMPTY

    /**
     * Groesster Abstand zweier Zyklen, der noch als "lueckenlos" gilt.
     * Zwei Minuten decken die gemessene Kadenz (58-62 s) samt einer
     * ausgefallenen Runde; darueber ist die Reihe unterbrochen und die
     * Ruhe nicht mehr bestaetigt.
     */
    const val LUECKENLOS_MAX_MS = 2 * 60_000L
}

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

        /**
         * DER DOSIERWIRKSAME MODUS (Bauauftrag Toni 25.08. spaet).
         *
         * WARUM ER NOETIG IST. Die beiden anderen Wege loesen das
         * eigentliche Problem nicht: [DEMAND_LIMITED] laesst nur
         * vorhandenen Normalbedarf durch - am Abendfall des 25.08. war der
         * 0 -, und [SHIFT_TO_DEFERRED] bewahrt die Menge, gibt sie aber
         * schrittweise. Der volle Batch blieb bisher allein
         * [Decision.FullBatchEligible] vorbehalten, also der schnellen
         * Erholung, die genau dann nicht eintritt, wenn man sie braucht.
         *
         * WAS ER TUT: nach N lueckenlos bestaetigten ruhigen Zyklen darf der
         * NOCH OFFENE Sofortanteil als Batch heraus - innerhalb Phase A,
         * nur bei gueltigem gepinntem Marker und offenem Anteil.
         *
         * WAS IHN BEGRENZT, und das ist keine neue Rechnung: er laeuft
         * durch denselben `MealFoundation.liftUpfront`-Pfad wie der
         * Vollbatch. Dort verrechnet `remainingUpfrontU` den manuellen
         * Bolus (keine Doppelgabe), und `AuthorizedLift.lift` traegt
         * Huelle, iobTH, maxIOB, Transporthaftung und Pumpenraster.
         *
         * WAS ABSOLUT BLEIBT: saemtliche aktuellen Gefahren. Eine
         * [Decision.CalmRecovered] entsteht nur, wenn [Hazards.any] in
         * DIESEM Zyklus falsch war - das ist am Konstruktor geprueft.
         *
         * Endet Phase A vorher, bleibt es beim bestehenden Uebertragspfad.
         */
        CALM_BATCH,
        ;

        companion object {

            /**
             * AUS DER EINSTELLUNG, fail-closed. Es gibt keinen
             * `FuseStringKey`, die Wahl liegt also als Zahl vor. Ein
             * unbekannter Wert ergibt den harmlosesten Modus - nicht den
             * dosierwirksamen. Wer die Reihenfolge hier aendert, aendert
             * die Bedeutung gespeicherter Einstellungen; deshalb sind die
             * Zahlen ausgeschrieben statt `values()[i]`.
             */
            fun ofSetting(v: Int): CalmTreatment = when (v) {
                1 -> SHIFT_TO_DEFERRED
                2 -> CALM_BATCH
                else -> DEMAND_LIMITED
            }
        }
    }

    /** Welcher Weg den letzten Trackeintrag erzeugt hat. */
    enum class TrackMode { NONE, RISING, CALM }

    /**
     * WARUM EIN GELADENER ZAEHLER VERWORFEN WURDE (Toni 25.08. spaet).
     *
     * Ein stiller leerer Track ist nicht dasselbe wie ein absichtlich
     * verworfener: im Trail waere beides "streak 0", und niemand koennte
     * unterscheiden, ob nie beobachtet wurde oder ob eine
     * Konfigurationsaenderung die Beobachtung entwertet hat.
     */
    enum class TrackReset {
        /** Nichts verworfen - fortgesetzt, oder es gab nichts zu erben. */
        NONE,

        /** Der geladene Stand war unvollstaendig oder widerspruechlich. */
        INCONSISTENT,

        /** Anderer Marker: der Zaehler gehoerte zu einer anderen Mahlzeit. */
        MARKER_CHANGED,

        /**
         * Andere Regel- oder Konfigurationsgeneration. Zwei Beobachtungen
         * unter alten und eine dritte unter gelockerten Schwellen duerfen
         * nicht gemeinsam freigeben.
         */
        CONFIG_CHANGED,
    }

    /**
     * SCHEMA DES TRACKS SELBST - getrennt von der RuleSet-Version.
     *
     * Die RuleSet-Version wandert mit jedem Export-Schema; sie sagt nichts
     * darueber, ob sich die BEDEUTUNG eines Ruhezyklus geaendert hat. Wer
     * hier etwas umbaut - andere Anschlussregel, anderer Modusbegriff,
     * anderer Streak-Vertrag -, erhoeht diese Zahl und entwertet damit
     * jeden gespeicherten Zaehler. Das ist der ausdrueckliche Hebel dafuer.
     */
    const val TRACK_SCHEMA = 1

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

        /** Die GEMESSENE Reihe faellt - s. [app.aaps.fuse.core.signal.GlucoseStability]. */
        STILL_FALLING,

        /**
         * Die Stabilitaet ist NICHT BEURTEILBAR - zu wenige Punkte, ein Loch,
         * ein Segmentwechsel, unbrauchbare Zahlen oder veraltete Daten.
         *
         * EIGENER GRUND, nicht mit STILL_FALLING gebuendelt: "faellt" und
         * "weiss ich nicht" sind zwei verschiedene Auskuenfte, und nur die
         * zweite ist behebbar, indem man wartet. Fehlende Daten bleiben
         * ausdruecklich eine Sperre.
         */
        SIGNAL_UNDETERMINED,

        /** Zu nah am Guard-Boden. */
        GUARD_DISTANCE,

        /** Ruhe-Ausgang ausgeschaltet oder ohne Behandlungswahl. */
        DISABLED,
    }

    /**
     * Die AKTUELLEN harten Blocker. Alle sechs sind Ausschlusskriterien;
     * der Marker ueberstimmt keinen davon.
     *
     * DER ZERO-LATCH STEHT HIER NICHT MEHR (Toni 28.08.). Er war der
     * einzige Eintrag, der KEINE aktuelle Gefahr beschreibt, sondern einen
     * historisch gehaltenen Basalschutz - gemessen am Fruehstueck des
     * 28.08.: von 09:22 bis 09:36 meldete die Kette `currentHazard
     * zeroLatch` als EINZIGEN Blocker, bei `descentRiskActive=false`,
     * `lowThreat=NONE`, `rebound=false` und gesundem Signal. Vier
     * autorisierte Einheiten blieben liegen, weil das Basal verriegelt war.
     * Basalschutz und Mahlzeitenfreigabe sind zwei Entscheidungen.
     *
     * WO ER STATTDESSEN WIRKT, damit die Entkopplung eng bleibt: der
     * bedarfsbegrenzte Ruhekandidat im Runner (`ruheKandidatRohU`) fuehrt
     * ihn als EIGENE Bedingung weiter. Dieser Pfad gibt reinen
     * NORMAL-Bedarf frei, keinen autorisierten Mahlzeitenanteil - fuer ihn
     * bleibt der verriegelte Basalzustand ein Ausschluss. Faellt die
     * Bedingung dort weg, oeffnet dieselbe Aenderung still die
     * Korrekturbahn; das ist der Grund, warum sie dort steht und nicht
     * hier.
     */
    class Hazards(
        val descentRisk: Boolean,
        /**
         * GEMESSENES TIEF - eigener Eintrag, absolut (Toni 28.08.).
         *
         * Frueher steckte es im gebuendelten `lowThreat`. Das war gefaehrlich,
         * sobald jenes Buendel faellt: [Decision.FullBatchEligible] kehrt
         * FUENFZEHN ZEILEN VOR der Bodenabstandspruefung zurueck, und ein
         * MEASURED_LOW liefert `distanceToFloorMgdl == null` - der schnelle
         * Erholungspfad haette also an einem gemessenen Tief vorbeigehen
         * koennen. Deshalb steht es hier fuer sich.
         */
        val measuredLow: Boolean,
        /**
         * DER AM MARKER GEPINNTE ABWAERTSRISIKO-VERTRAG (Toni 28.08.).
         *
         * Hier stand `lowThreat = verdict != NONE`, also das 120-Minuten-
         * BASALVERDIKT des Low-Tors. Das ist die Frage "lohnt sich ein
         * Basalstopp?" - gebaut als einziger Weg zu einer Zero-TBR - und nicht
         * die Frage "ist diese Mahlzeitendosis gefaehrlich?". Der 120er ist
         * VIERMAL so lang wie der SMB-Riegel (30) und DOPPELT so lang wie der
         * markerbezogene (60); ein Bodenkontakt zwischen 60 und 120 Minuten
         * fiel nur in das Basalfenster. Genau so lag der 28.08.
         *
         * FEHLENDER ODER UNGUELTIGER PIN IST KEINE ENTWARNUNG: der Aufrufer
         * muss dann `true` uebergeben. Das steht hier, weil man es an einem
         * Boolean nicht mehr sieht.
         */
        val pinnedMealRisk: Boolean,
        val rebound: Boolean,
        val signalUnhealthy: Boolean,
        val technical: Boolean,
        val ledgerHold: Boolean,
    ) {

        val any: Boolean
            get() = descentRisk || measuredLow || pinnedMealRisk || rebound ||
                signalUnhealthy || technical || ledgerHold

        /** Fuer den Export: welche genau. */
        val names: String
            get() = listOfNotNull(
                "descentRisk".takeIf { descentRisk },
                "measuredLow".takeIf { measuredLow },
                "pinnedMealRisk".takeIf { pinnedMealRisk },
                "rebound".takeIf { rebound },
                "signal".takeIf { signalUnhealthy },
                "technical".takeIf { technical },
                "ledgerHold".takeIf { ledgerHold },
            ).joinToString("+").ifEmpty { KEINE_GEFAHR }
    }

    /** Der Exportwert von [Hazards.names], wenn keine Gefahr besteht. */
    const val KEINE_GEFAHR = "none"

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
            else "s$TRACK_SCHEMA|rs$ruleSetVersion|c$calmCycles" +
                // BITMUSTER STATT TEXT: `toString` einer Double haengt zwar
                // nicht an der Locale, aber an der kuerzesten
                // Rundtrip-Darstellung - 0.05 und ein um ein Ulp anderer
                // Wert koennten denselben Text ergeben, und ein anderer
                // JDK-Stand koennte denselben Wert anders schreiben. Das
                // Bitmuster ist exakt, kanonisch und sprachunabhaengig.
                "|u${java.lang.Double.doubleToLongBits(minUkf)}" +
                "|g${java.lang.Double.doubleToLongBits(minGuardDistanceMgdl)}" +
                "|t${calmTreatment?.name}"

        companion object {

            val OFF = Params(false, 0, 0.0, 0.0, null, 0)

            /** s. [FuseIntKey.CalmRecoveryCycles] - der Vertrag der Klasse. */
            const val MIN_CALM_CYCLES = 2
            const val MAX_CALM_CYCLES = 20

            /** Kein Batch auf einer noch fallenden Kurve. */
            const val MIN_CALM_UKF = 0.0

            /** Keine Freigabe unmittelbar am Guard-Boden. */
            const val MIN_GUARD_DISTANCE_MGDL = 5.0

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
                // DIESELBEN GRENZEN WIE DIE EINSTELLUNG - und hier ist es
                // die letzte Verteidigung: ein Wert aus einem Backup, einer
                // Altdatei oder einem Replay-Override kommt hier ebenso an
                // wie einer aus dem Bildschirm.
                //   calmCycles >= 2  - ein einzelner ruhiger Zyklus genuegt
                //                      ausdruecklich nicht
                //   minUkf >= 0.0    - FLOOR_BEYOND_HORIZON hebt das aktuelle
                //                      Risiko auf, waehrend die Rate noch
                //                      negativ ist
                //   Abstand >= 5.0   - keine Freigabe am Guard-Boden
                if (calmCycles in MIN_CALM_CYCLES..MAX_CALM_CYCLES &&
                    minUkf.isFinite() && minUkf >= MIN_CALM_UKF && minUkf <= 1.0 &&
                    minGuardDistanceMgdl.isFinite() &&
                    minGuardDistanceMgdl >= MIN_GUARD_DISTANCE_MGDL &&
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
         * Warum ein geladener Zaehler verworfen wurde. Sichtbar, nicht
         * still: ein Konfigurationswechsel entwertet Beobachtungen, und das
         * muss im Trail stehen.
         */
        val trackReset: TrackReset

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
            override val trackReset: TrackReset,
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
            override val trackReset: TrackReset,
        ) : Decision {

            init {
                // DIE IMPLIKATION AM TYP, nicht als Kommentar (Toni 25.08.
                // spaet). Beide Freigabetypen entstehen ausschliesslich
                // NACH der Gefahrenpruefung. Ein Aufrufer darf sich darauf
                // verlassen, ohne die Reihenfolge in `evaluate` zu kennen -
                // und wer sie umsortiert, laeuft hier auf.
                require(hazards == KEINE_GEFAHR) {
                    "FullBatchEligible bei aktueller Gefahr: $hazards"
                }
            }
        }

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
            override val trackReset: TrackReset,
        ) : Decision {

            init {
                require(hazards == KEINE_GEFAHR) {
                    "CalmRecovered bei aktueller Gefahr: $hazards"
                }
            }
        }
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
    /**
     * DIE RESTART-ASYMMETRIE IST MIT DEM STABILITAETSTOR ENTFALLEN (Toni
     * 28.08.). Der alte `q1Falling`-Riegel verglich mit EINEM prozesslokalen
     * Vorzykluswert; nach einem Neustart war der `null`, also galt "nicht
     * gefallen" - das Tor stand fuer einen Zyklus still offen, waehrend der
     * Ruhezaehler aus dem Ledger geerbt wurde. Ein fehlender Vorwert ist aber
     * "Vergleich unbekannt", nicht "kein Abfall".
     *
     * Die gemessene Reihe kennt diesen Fall nicht: fehlt sie oder ist sie zu
     * kurz, lautet das Urteil UNDETERMINED und sperrt. Ein geerbter Zaehler
     * ersetzt damit keinen aktuellen Nachweis mehr.
     */
    fun evaluate(
        params: Params,
        prior: Track,
        deferredOpen: Boolean,
        inPhaseA: Boolean,
        markerIdentity: Long,
        hazards: Hazards,
        risingConfirmed: Boolean,
        /**
         * Das Urteil ueber die GEMESSENE Reihe. `null` heisst "gar nicht
         * gerechnet" und wird wie UNDETERMINED behandelt - nie wie stabil.
         */
        stability: app.aaps.fuse.core.signal.GlucoseStability.Result?,
        guardDistanceMgdl: Double?,
        sourceTs: Long,
        nowTs: Long,
    ): Decision {
        // EINMAL erben, nicht fuenfmal: der Grund muss in JEDEM Ausgang
        // sichtbar sein, auch in den blockierten - sonst laesst sich "nie
        // beobachtet" nicht von "durch Konfigurationswechsel entwertet"
        // unterscheiden.
        val (basis, resetGrund) = geerbt(prior, markerIdentity, params.fingerprint)

        fun nein(d: Denial, t: Track = Track.EMPTY) =
            Decision.Blocked(t, hazards.names, guardDistanceMgdl, d, params.calmCycles,
                             resetGrund)

        // Die aktuelle Gefahr steht VOR allem anderen und loescht den
        // Ruhezaehler - eine Ruhe, die von einer Gefahr unterbrochen wurde,
        // war keine. Dass dieser Zweig zuerst kommt, ist zugleich die
        // Zusicherung aus dem Track-KDoc: ein fortgesetzter Zaehler hat die
        // Gefahren in DIESEM Zyklus erneut negativ geprueft.
        if (hazards.any) return nein(Denial.CURRENT_HAZARD)
        if (!deferredOpen) return nein(Denial.NOTHING_DEFERRED, basis)
        if (!inPhaseA) return nein(Denial.NOT_PHASE_A, basis)
        if (markerIdentity <= 0L) return nein(Denial.NO_AUTHORITY)

        // WEG 1 - unveraendert: die bestaetigte schnelle Erholung des
        // allgemeinen Latches. Sie brauchte nie eine Ruhezaehlung.
        if (risingConfirmed) return Decision.FullBatchEligible(
            Track(markerIdentity, maxOf(1, basis.streak),
                  sourceTs, nowTs, TrackMode.RISING, params.fingerprint),
            hazards.names, guardDistanceMgdl, resetGrund,
        )

        val treatment = params.calmTreatment
        if (!params.enabled || treatment == null)
            return nein(Denial.DISABLED, basis)

        // WEG 2 - die bestaetigte ruhige Lage. Jede verletzte Bedingung
        // setzt den Zaehler auf 0; ein einzelner flacher Zyklus genuegt
        // ausdruecklich nicht.
        // HIER STANDEN ZWEI NULLTOLERANZEN (bis 28.08.):
        //   ukfRatePerMin < params.minUkf  ->  STILL_FALLING
        //   q1Falling                      ->  Q1_FALLING
        // Am Fruehstueck des 28.08. hielten sie vier autorisierte Einheiten
        // minutenlang fest, obwohl q1 zwischen 94,3 und 95,5 lag: die
        // Filterrate blieb knapp negativ (zuletzt -0,0133) und q1 wackelte um
        // 0,1 bis 0,3. Ein nachlaufender Filter und ein einzelner Wackler
        // waren damit staerker als die gemessene Lage.
        //
        // Der Nachweis laeuft jetzt ueber die GEMESSENE Reihe. Er ist
        // dreiwertig, und "nicht beurteilbar" bleibt eine Sperre.
        when (stability?.verdict) {
            app.aaps.fuse.core.signal.GlucoseStability.Verdict.STABLE -> Unit
            app.aaps.fuse.core.signal.GlucoseStability.Verdict.FALLING ->
                return nein(Denial.STILL_FALLING)
            else -> return nein(Denial.SIGNAL_UNDETERMINED)
        }
        if (guardDistanceMgdl == null || !guardDistanceMgdl.isFinite() ||
            guardDistanceMgdl < params.minGuardDistanceMgdl
        ) return nein(Denial.GUARD_DISTANCE)

        // LUECKENLOS heisst dreierlei: dieselbe Autorisierung, ein
        // Signalanschluss und Zeitkontinuitaet. Nach einem Neustart, einem
        // Markerwechsel oder einer Zykluspause faengt die Zaehlung neu an,
        // statt erfundene Zyklen zu erben.
        val anschluss = basis.streak > 0 &&
            nowTs > basis.lastEvaluationTs &&
            nowTs - basis.lastEvaluationTs <= LUECKENLOS_MAX_MS &&
            sourceTs > basis.lastAcceptedSourceTs &&
            sourceTs - basis.lastAcceptedSourceTs <= LUECKENLOS_MAX_MS
        // DIE VORGESCHICHTE ZAEHLT AUCH ZEITLICH (Toni 28.08.).
        //
        // Hier stand `else 1`: nach jedem Markerwechsel begann die Zaehlung
        // wieder bei eins, obwohl die gemessene Reihe laengst belegte, dass
        // die Lage seit mehreren Zyklen ruhig ist. Am Fruehstueck des 28.08.
        // kostete das allein rund vier Minuten - eine Wartezeit aus
        // unvollstaendiger Nutzung der Historie, nicht aus einer
        // Sicherheitsbedingung.
        //
        // WAS DAMIT NICHT WANDERT: Autorisierung, Budget und
        // Lieferidentitaet kommen unveraendert aus dem NEUEN Marker - der
        // Zaehler belegt Signalruhe, nicht Berechtigung. Und die AKTUELLEN
        // Gefahren stehen weiter ganz oben in dieser Funktion und werden in
        // DIESEM Zyklus geprueft; eine ruhige Vorgeschichte ueberstimmt
        // keine davon.
        val ausHistorie = stability?.confirmedCycles ?: 0
        val streak = if (anschluss) basis.streak + 1
        else maxOf(1, minOf(ausHistorie, params.calmCycles))
        val track = Track(markerIdentity, streak, sourceTs, nowTs, TrackMode.CALM,
                          params.fingerprint)
        if (streak < params.calmCycles)
            return Decision.Blocked(track, hazards.names, guardDistanceMgdl,
                                    Denial.CALM_STREAK_SHORT, params.calmCycles, resetGrund)
        return Decision.CalmRecovered(track, hazards.names, guardDistanceMgdl, streak, treatment,
                                      resetGrund)
    }

    /**
     * Ein Zaehler gehoert zu EINER Autorisierung und EINER Generation. Passt
     * eines von beidem nicht - oder ist der geladene Stand inkonsistent -,
     * wird er verworfen statt uminterpretiert, und der GRUND faehrt mit.
     */
    private fun geerbt(
        prior: Track,
        markerIdentity: Long,
        fingerprint: String,
    ): Pair<Track, TrackReset> = when {
        prior.streak <= 0 -> Track.EMPTY to TrackReset.NONE
        !prior.consistent -> Track.EMPTY to TrackReset.INCONSISTENT
        prior.markerIdentity != markerIdentity -> Track.EMPTY to TrackReset.MARKER_CHANGED
        prior.fingerprint != fingerprint -> Track.EMPTY to TrackReset.CONFIG_CHANGED
        else -> prior to TrackReset.NONE
    }

    /**
     * Groesster Abstand zweier Zyklen, der noch als "lueckenlos" gilt.
     * Zwei Minuten decken die gemessene Kadenz (58-62 s) samt einer
     * ausgefallenen Runde; darueber ist die Reihe unterbrochen und die
     * Ruhe nicht mehr bestaetigt.
     */
    const val LUECKENLOS_MAX_MS = 2 * 60_000L
}

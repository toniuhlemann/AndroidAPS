package app.aaps.fuse.core.controller

/**
 * Was NACH dem Haftungshorizont noch unvermeidbar wirkt — die Groesse, die der
 * Nahzonen-Guard strukturell nicht sieht.
 *
 * Der Guard in [FuseController] prueft das Minimum der Guardbahn ueber
 * `[Anker..H]`. Bei DIA 9 h liegt darin nur ein Bruchteil der Wirkung, die
 * bereits an Bord ist: 60 min ~20 %, 120 min ~50 %, 180 min ~73 %. Alles
 * danach wird heute gar nicht bewertet, und ein Zero-Temp kann bereits
 * gelieferte Wirkung ohnehin nicht zurueckholen.
 *
 * GELTUNGSANSPRUCH, ausdruecklich: Guard v0.3 §8 sagt zu Formel und Defaults
 * "VORSCHLAG, nicht gelockt". Was hier steht, ist also eine Umsetzung dieses
 * Vorschlags und keine beschlossene Norm. Deshalb ist der Schalter eine
 * Preference und der Erholungsterm ein einstellbarer Wert mit Default 0.
 *
 * DIE DREI TERME. R79-F4 verlangt:
 *
 *   existingUnavoidableIobAtH + openTransportCommitmentResidualAtH + candidateResidualAtH
 *
 * Bis zur Codex-Adjudication bae885f1 war NUR der erste gerechnet
 * (`INCOMPLETE(1of3,noLedger,noCandidate)`). C4 schliesst die beiden anderen:
 *
 *  - [Input.transport] ist die publizierte, im IOB noch nicht sichtbare Menge
 *    aus dem Commitment-Ledger. Ihre Restwirkung am Horizont haftet genauso wie
 *    vorhandenes IOB - sie ist nur noch nicht sichtbar.
 *  - [Input.candidate] ist die GERADE BESCHLOSSENE Menge. Der Aufrufer kennt
 *    sie erst nach der Kandidatenwahl, deshalb wird der Schwanz zweimal
 *    bewertet: einmal OHNE (als Kappe fuer die Suche, `noCandidate`) und einmal
 *    MIT der beschlossenen Menge (als finale Pruefung, 3/3).
 *
 * KOPPLUNG BEWUSST NIEDRIG: hier steht eine Formel ueber ZAHLEN. Kein Kern,
 * keine Bahn, kein Ledger. Die Wirkung einer Dosis rechnet dort, wo die
 * Integrationsregel ohnehin steht ([CandidateSearch]); der Schwanz bekommt das
 * Ergebnis. Deshalb auch KEIN zweites `evaluateWithCandidate` - der Kandidat ist
 * schlicht ein weiterer Term derselben Gleichung, und "zweimal bewerten" bleibt
 * eine Entscheidung des Aufrufers statt einer zweiten API.
 *
 * WICHTIG (Codex Abschnitt 10): eine unvollstaendige Komponente wird NIE still
 * wie 0 behandelt, wenn 0 weniger konservativ waere. Fehlt der Einheitskern,
 * zaehlt die VOLLE Menge (die obere Schranke jeder Restwirkung), und der
 * Vermerk sagt es - s. [Dose].
 *
 * WAS WEITERHIN FEHLT: die kuenftige Basal-/TBR-Entscheidung und eine
 * Langhorizont-Unsicherheit. Beides ist NICHT Teil der drei Terme und bleibt
 * offen; der Vermerk bezieht sich ausdruecklich auf die Formel, nicht auf
 * "alles Denkbare".
 */
object TailLiability {

    /** Vollstaendigkeitsvermerk. Steht in jedem Export und in jedem Grund; ein
     *  Guard, der eine Deckung behauptet, die er strukturell nicht hat, waere
     *  schlimmer als gar keiner.
     *
     *  Dieser Wert ist KEINE Konstante der Rechnung mehr, sondern der Vermerk
     *  des Falls "weder Ledger- noch Kandidatenterm" - er entsteht aus
     *  [completenessOf] und bleibt damit exakt der alte Text. */
    const val COMPLETENESS_STAGE1 = "INCOMPLETE(1of3,noLedger,noCandidate)"

    /** Alle drei Terme in der Gleichung (C4). */
    const val COMPLETE_3OF3 = "COMPLETE(3of3)"

    /**
     * Unterhalb dieses Werts ist die Bahn keine Blutglukose mehr, sondern eine
     * Extrapolation ins Unphysiologische.
     *
     * Beobachtet am 06.08.: bei 5,92 U an Bord und ISF 95 projizierte die Bahn
     * auf `minLower = -31 mg/dl`. Die ENTSCHEIDUNG war richtig (sperren), aber
     * die ZAHL bedeutet nichts — und `budgetU = (lowerBgAtH - floor)/isf`
     * haette daraus ein ebenso bedeutungsloses Budget gerechnet, das spaeter
     * jemand als Messwert liest.
     *
     * Die Bahn selbst wird NICHT geklammert: das waere eine Aenderung am
     * Predictor, und eine geklammerte Bahn wuerde die Groesse des Ueberschusses
     * verstecken. Stattdessen wird sie hier als solche BENANNT.
     */
    const val PHYSIOLOGICAL_FLOOR_MGDL = 20.0

    const val REASON_UNPHYSIOLOGICAL = "lowerBgAtH below physiological floor"

    /** Woher die Bahn am Horizont stammt. Kein Schmuck: sobald Stufe 2 den
     *  Kandidaten propagiert, aendert sich dieser Name — und damit ist im
     *  Nachhinein unterscheidbar, welche Zahlen womit gerechnet wurden.
     *
     *  Dieser Name gilt fuer den KAPPEN-Aufruf (vor der Kandidatenwahl). Die
     *  finale Pruefung mit beschlossener Menge traegt [SOURCE_WITH_CANDIDATE] —
     *  s. [Input.lowerBgAtH]. */
    const val SOURCE_BASELINE = "BASELINE_NO_CANDIDATE"

    /** Die Bahn am Horizont enthaelt die BESCHLOSSENE Menge (C4b) - das ist die
     *  Zahl, gegen die das finale Zeugnis laeuft. */
    const val SOURCE_WITH_CANDIDATE = "SAFETY_WITH_CANDIDATE"

    /**
     * Eine Dosis, deren Wirkung am Haftungshorizont noch nicht ausgelaufen ist
     * (C4). Zwei Verwender: die Transportmenge und die beschlossene Menge.
     *
     * [residualAtHU] `null` heisst NICHT 0, sondern UNBEKANNT - typisch: der
     * Einheitskern liess sich in diesem Zyklus nicht bauen. Dann rechnet der
     * Schwanz mit [amountU], der OBEREN SCHRANKE jeder Restwirkung (mehr als die
     * ganze Menge kann am Horizont nicht mehr an Bord sein), und meldet den Fall
     * als `...Bounded`. 0 waere die optimistische Lesart und genau der Fehler,
     * den Codex Abschnitt 10 verbietet.
     */
    data class Dose(val amountU: Double, val residualAtHU: Double? = null) {

        /** Ist die Restwirkung GERECHNET (Kern vorhanden) oder nur beschraenkt? */
        val modelled: Boolean get() = residualAtHU != null && residualAtHU.isFinite() && residualAtHU >= 0.0

        /**
         * Was am Horizont haftet [U].
         *
         * Der Rueckfall auf die volle Menge ist die Schranke; ist auch die
         * unbrauchbar (NaN/negativ/unendlich - ein Programmierfehler, kein
         * Betriebsfall), bleibt UNENDLICH: die einzige Zahl, die keine Dosis
         * autorisiert. Ein `usable = false` waere hier falsch - es wuerde den
         * Schwanz-Guard ABSCHALTEN und damit ausgerechnet im Fehlerfall
         * durchlaessiger machen.
         */
        val liabilityAtHU: Double
            get() {
                val v = if (modelled) residualAtHU!! else amountU
                return if (v.isFinite() && v >= 0.0) v else Double.POSITIVE_INFINITY
            }
    }

    /**
     * Der Vermerk entsteht aus den TATSAECHLICH uebergebenen Termen - er wird
     * nicht gepflegt, sondern abgeleitet. Damit kann Anzeige und Export nie
     * etwas anderes behaupten als die Rechnung.
     */
    fun completenessOf(transport: Dose?, candidate: Dose?): String {
        val missing = buildList {
            if (transport == null) add("noLedger")
            if (candidate == null) add("noCandidate")
        }
        val bounded = buildList {
            if (transport != null && !transport.modelled) add("transportBounded")
            if (candidate != null && !candidate.modelled) add("candidateBounded")
        }
        val tokens = missing + bounded
        return when {
            missing.isEmpty() && bounded.isEmpty() -> COMPLETE_3OF3
            missing.isEmpty()                      -> "COMPLETE(3of3," + tokens.joinToString(",") + ")"
            else                                   -> "INCOMPLETE(${3 - missing.size}of3," + tokens.joinToString(",") + ")"
        }
    }

    data class Input(
        /**
         * Untere Bahn am Haftungshorizont [mg/dl].
         *
         * SEIT C2/C1 nicht mehr `bgAtHorizonLower` der Hauptbahn, sondern
         * `minSafetyHorizonLowerOf(prediction, restraint)`: die PRIOR-FREIE
         * Kante, punktweise pessimistisch ueber alle glaubwuerdigen Bahnen.
         * Grund (Codex H2): der Marker-Prior hob auch diese Zahl - eine
         * Erklaerung "Carbs kommen" verlaengerte damit das Schwanzbudget, das
         * ihre eigene Dosis finanziert.
         *
         * SEIT C3 enthaelt diese Bahn ausserdem die Wirkung der TRANSPORTMENGE
         * bis zum Horizont (der Predictor rechnet sie als synthetische Dosis
         * ein). SEIT C4b muss der Aufrufer bei gesetztem [candidate]
         * zusaetzlich dessen Wirkung bis H abgezogen haben.
         */
        val lowerBgAtH: Double,
        /**
         * Was an Insulin am Horizont noch an Bord ist [U].
         *
         * BEWUSST das GESAMT-IOB und nicht nur der Bolusanteil: die saubere
         * Trennung "vermeidbar / unvermeidbar" braucht ein Modell fuer
         * zurueckgehaltenes Basal, das es hier nicht gibt. Das Gesamt-IOB ist
         * die konservative Seite dieser Unschaerfe — es ueberschaetzt die
         * Haftung eher, als sie zu unterschaetzen.
         */
        val existingIobAtH: Double,
        /** ISF im Schwanzfenster [mg/dl/U]. Konservativ das MAXIMUM der
         *  beruehrten Bloecke: ein hoeherer ISF macht das Budget kleiner. */
        val isfTailMgdlPerU: Double,
        /** Untergrenze, die der Schwanz nicht unterschreiten darf [mg/dl]. */
        val tailFloorMgdl: Double,
        /**
         * Erholung, die im Schwanzfenster noch zulaessig eingeplant werden darf
         * [U]. Default 0 — Guard v0.4 §221 setzt ihn ausdruecklich auf 0,0 mit
         * dem Zusatz "Aenderung NUR mit Messung".
         */
        val tailRecoveryU: Double,
        /**
         * Publizierte, im IOB noch nicht sichtbare Menge (C4a). `null` = der
         * Aufrufer hat keine Ledger-Sicht -> Vermerk `noLedger`.
         *
         * DOPPELZAEHLUNG: dieselbe Zahl, die hier haftet, senkt ueber C3 bereits
         * [lowerBgAtH] um ihre Wirkung VOR dem Horizont. Das ist kein doppelter
         * Ansatz, sondern die Aufteilung derselben Dosis: was bis H gewirkt hat,
         * steckt in der Bahn; was danach noch kommt, steht hier.
         */
        val transport: Dose? = null,
        /**
         * Die BESCHLOSSENE Menge dieses Zyklus (C4b). `null` = noch nicht
         * entschieden (Kappen-Aufruf vor der Suche) -> Vermerk `noCandidate`.
         *
         * Ist sie gesetzt, MUSS [lowerBgAtH] ihre Wirkung bis zum Horizont
         * bereits enthalten - sonst waere die Bahn eine andere Zukunft als die
         * Haftung. Der Aufrufer sagt das ueber [Report.lowerBgAtHSource] an.
         */
        val candidate: Dose? = null,
    ) {

        fun violation(): String? = when {
            !lowerBgAtH.isFinite()                                   -> "lowerBgAtH=$lowerBgAtH"
            !existingIobAtH.isFinite()                               -> "existingIobAtH=$existingIobAtH"
            !isfTailMgdlPerU.isFinite() || isfTailMgdlPerU <= 0.0     -> "isfTail=$isfTailMgdlPerU"
            !tailFloorMgdl.isFinite()                                -> "tailFloor=$tailFloorMgdl"
            !tailRecoveryU.isFinite()                                -> "tailRecovery=$tailRecoveryU"
            else                                                     -> null
        }
    }

    data class Report(
        /** Wieviel Insulin der Schwanz noch vertraegt [U]. Kann negativ sein —
         *  dann ist bereits mehr an Bord, als die Bahn traegt. */
        val budgetU: Double,
        /** GESAMTE Haftung am Horizont: IOB + Transport + beschlossene Menge.
         *  Die drei Anteile stehen einzeln daneben - eine Summe ohne Herkunft
         *  waere die Zahl, die spaeter niemand mehr auseinandernimmt. */
        val existingU: Double,
        /** `budget - existing`. `<= 0` heisst: kein zusaetzliches Insulin. */
        val headroomU: Double,
        val completeness: String,
        val lowerBgAtHSource: String,
        /** `null` = nicht auswertbar (ungueltige Eingabe); dann greift der
         *  Schwanz-Guard NICHT — er darf keine Entscheidung auf einer Zahl
         *  gruenden, die er selbst verworfen hat. */
        val invalidReason: String? = null,
        /** Die Bahn lag unter dem physiologischen Boden. Der Bericht SPERRT
         *  trotzdem, aber `budgetU` ist dann keine auswertbare Groesse. */
        val unphysiological: Boolean = false,
        /** Term 1: sichtbares IOB am Horizont [U]. */
        val existingIobAtHU: Double = 0.0,
        /** Term 2: Restwirkung der Transportmenge am Horizont [U] (C4a). */
        val transportLiabilityU: Double = 0.0,
        /** Term 3: Restwirkung der beschlossenen Menge am Horizont [U] (C4b). */
        val candidateLiabilityU: Double = 0.0,
    ) {

        val usable: Boolean get() = invalidReason == null
        /** Darf `budgetU` als Messwert gelesen werden? */
        val budgetMeaningful: Boolean get() = usable && !unphysiological
    }

    /**
     * Geschlossene Aufloesung, wie in Guard v0.3 vorgeschlagen:
     *
     *     budgetU = (lowerBgAtH - tailFloor) / isfTail + tailRecoveryU
     *
     * Wirft nicht. Eine ungueltige Eingabe ergibt einen Bericht, der sich
     * selbst als unbrauchbar meldet — eine Ausnahme aus dem Regelpfad landet
     * sonst im Loop.
     */
    fun evaluate(input: Input): Report {
        val completeness = completenessOf(input.transport, input.candidate)
        val source = if (input.candidate != null) SOURCE_WITH_CANDIDATE else SOURCE_BASELINE
        input.violation()?.let {
            return Report(0.0, 0.0, 0.0, completeness, source, invalidReason = it)
        }
        // C4: die drei Terme der Haftung. Die beiden neuen sind IMMER dabei,
        // sobald der Aufrufer sie kennt - fehlt eine Restwirkung, zaehlt die
        // volle Menge (s. [Dose.liabilityAtHU]), nie 0.
        val transport = input.transport?.liabilityAtHU ?: 0.0
        val candidate = input.candidate?.liabilityAtHU ?: 0.0
        val existing = input.existingIobAtH + transport + candidate
        // Eine Bahn unter dem physiologischen Boden ist kein Messwert mehr.
        // Sie SPERRT weiterhin - das ist unstrittig richtig -, aber sie liefert
        // keine Budgetzahl, die irgendjemand auswerten duerfte.
        if (input.lowerBgAtH < PHYSIOLOGICAL_FLOOR_MGDL) {
            return Report(
                budgetU = 0.0, existingU = existing,
                headroomU = -existing,                  // sperrt, solange Haftung > 0
                completeness = completeness, lowerBgAtHSource = source,
                invalidReason = null,
                unphysiological = true,
                existingIobAtHU = input.existingIobAtH,
                transportLiabilityU = transport,
                candidateLiabilityU = candidate,
            )
        }
        val budget = (input.lowerBgAtH - input.tailFloorMgdl) / input.isfTailMgdlPerU + input.tailRecoveryU
        return Report(
            budgetU = budget,
            existingU = existing,
            headroomU = budget - existing,
            completeness = completeness,
            lowerBgAtHSource = source,
            existingIobAtHU = input.existingIobAtH,
            transportLiabilityU = transport,
            candidateLiabilityU = candidate,
        )
    }
}

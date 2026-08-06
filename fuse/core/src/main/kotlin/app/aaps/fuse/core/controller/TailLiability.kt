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
 * WAS DIESE STUFE NICHT KANN — und warum das im Export steht statt im
 * Hinterkopf. R79-F4 verlangt drei Terme:
 *
 *   existingUnavoidableIobAtH + openTransportCommitmentResidualAtH + candidateResidualAtH
 *
 * Gerechnet wird hier NUR der erste. Die anderen beiden fehlen, und zwar in
 * ungleicher Richtung:
 *
 *  - `openTransportCommitmentResidual` braucht den Commitment-Ledger. Der ist
 *    gebaut und getestet, hat aber keine Aufrufstelle im Livepfad.
 *  - `candidateResidual` braucht den Einheitskern am Kandidaten. Ebenfalls
 *    gebaut, ebenfalls nicht verdrahtet (Stufe 2).
 *
 * Und eine dritte Luecke, die leicht uebersehen wird, weil sie nicht in der
 * Formel steht: die uebergebene Bahn am Horizont ist die BASELINE-Bahn OHNE
 * Kandidat. Guard v0.3 §2 meint die Bahn MIT Kandidat. Term (1) faengt damit
 * nur den Rest NACH H; die Kandidatenwirkung VOR H fehlt vollstaendig.
 * Richtung: ZU WENIG Haftung — also nicht konservativ. Genau deshalb traegt
 * jeder Bericht seinen Unvollstaendigkeitsvermerk mit.
 */
object TailLiability {

    /** Vollstaendigkeitsvermerk. Steht in jedem Export und in jedem Grund; ein
     *  Guard, der eine Deckung behauptet, die er strukturell nicht hat, waere
     *  schlimmer als gar keiner. */
    const val COMPLETENESS_STAGE1 = "INCOMPLETE(1of3,noLedger,noCandidate)"

    /** Woher die Bahn am Horizont stammt. Kein Schmuck: sobald Stufe 2 den
     *  Kandidaten propagiert, aendert sich dieser Name — und damit ist im
     *  Nachhinein unterscheidbar, welche Zahlen womit gerechnet wurden. */
    const val SOURCE_BASELINE = "BASELINE_NO_CANDIDATE"

    data class Input(
        /** Untere Bahn am Haftungshorizont [mg/dl] — aus `bgAtHorizonLower`. */
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
        val existingU: Double,
        /** `budget - existing`. `<= 0` heisst: kein zusaetzliches Insulin. */
        val headroomU: Double,
        val completeness: String,
        val lowerBgAtHSource: String,
        /** `null` = nicht auswertbar (ungueltige Eingabe); dann greift der
         *  Schwanz-Guard NICHT — er darf keine Entscheidung auf einer Zahl
         *  gruenden, die er selbst verworfen hat. */
        val invalidReason: String? = null,
    ) {

        val usable: Boolean get() = invalidReason == null
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
        input.violation()?.let {
            return Report(0.0, 0.0, 0.0, COMPLETENESS_STAGE1, SOURCE_BASELINE, invalidReason = it)
        }
        val budget = (input.lowerBgAtH - input.tailFloorMgdl) / input.isfTailMgdlPerU + input.tailRecoveryU
        return Report(
            budgetU = budget,
            existingU = input.existingIobAtH,
            headroomU = budget - input.existingIobAtH,
            completeness = COMPLETENESS_STAGE1,
            lowerBgAtHSource = SOURCE_BASELINE,
        )
    }
}

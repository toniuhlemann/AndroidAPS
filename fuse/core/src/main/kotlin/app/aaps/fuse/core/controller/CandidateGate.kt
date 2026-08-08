package app.aaps.fuse.core.controller

/**
 * Die Bruecke zwischen dem Ratio-Pfad und [CandidateSearch]: der Ratio-Pfad
 * SCHLAEGT eine Menge VOR, die Kandidatensuche prueft sie MIT ihrer eigenen
 * Wirkung in der Bahn - und darf sie ausschliesslich BESCHNEIDEN.
 *
 * Warum beschneiden statt ersetzen: die Suche waehlt den GROESSTEN sicheren
 * Kandidaten - als alleiniger Mengenpfad wuerde sie aggressiver dosieren als
 * der Ratio-Pfad (der bewusst nur einen Anteil je Minute gibt). `min(beide)`
 * ist die beweisbar einseitige Form: kein Zyklus kann mit Gate mehr liefern
 * als ohne.
 *
 * ZWEI KLASSEN von Ablehnungen, und die Trennung ist tragend:
 *  - INHALTLICH (Guard risse MIT Kandidat, kein Bedarf, Band, Headroom,
 *    Pumpenschritt): wird DURCHGESETZT - genau dafuer ist die Suche da.
 *    Quantitativ (Audit 07.08.): 0,30 U bei ISF 95 senken die Bahn um
 *    4,3 mg/dl @30 min und 21,6 @120 min - das prueft der Baseline-Guard
 *    strukturell nicht.
 *  - TECHNISCH (Raster, Horizont, Kernel, ISF-Slot): der PRUEFER
 *    ist ausgefallen, nicht die Dosis unsicher. Dann gilt die Basis
 *    unveraendert und der Ausfall steht als Luecke im Export. Ein Prüfer-
 *    Ausfall, der Dosen nullt, waere ein neuer Ausfallmodus des Reglers.
 *
 * DRITTE KLASSE seit dem Audit R95 (F-P0-05/F-P0-08-Gegenpruefung): KORRUPTE
 * EINGABEN (LEDGER_HOLD, INVALID_BAND, INVALID_CAPS, NON_FINITE). Das ist
 * kein Pruefer-Ausfall - die Suche hat erkannt, dass ihre EINGABEN kaputt
 * sind, und dieselben Eingaben haben auch die Basisdosis gebaut (NaN-Werte
 * schalten die IOB-Headroom-Gates still aus, Kotlin: NaN <= x ist false).
 * Eine Basis aus korrupten Eingaben durchzulassen war der fail-open-Pfad
 * des Audits; diese Kategorien setzen jetzt fail-closed auf null.
 *
 * Die Sofort-Freigabe (PrimeRelease) hebt NACH diesem Gate: sie ist bewusst
 * evidenzfrei und traegt ihre eigene Kandidaten-Naeherung (Clearance-Gate).
 * Das Eintrittstor der Suche (baseline <= Ziel -> NO_DEMAND) wuerde sie sonst
 * strukturell toeten.
 */
object CandidateGate {

    /** Untere Kante des Freigabe-Zielbands: Ziel minus dieser Marge. Der
     *  Ratio-Pfad dosiert konstruktionsbedingt nie unter das Ziel - die Kante
     *  faengt nur grobe Ueberdosis-Kandidaten. PROVISORISCH, unvermessen. */
    const val RELEASE_LOW_MARGIN_MGDL = 20.0

    /** Eintrittstor-Totband: 0 = identisch zur NO_DEMAND-Kante des Reglers
     *  (insulinReq <= 0), damit Gate und Regler nicht zwei verschiedene
     *  "kein Bedarf" kennen. */
    const val DEMAND_DEADBAND_MGDL = 0.0

    enum class Kind { ENFORCE, UNAVAILABLE }

    fun kindOf(reject: CandidateSearch.Reject?): Kind = when (reject) {
        null,
        CandidateSearch.Reject.NO_DEMAND,
        CandidateSearch.Reject.GUARD_FLOOR,
        CandidateSearch.Reject.BELOW_TARGET_BAND,
        CandidateSearch.Reject.NO_HEADROOM,
        CandidateSearch.Reject.BELOW_PUMP_INCREMENT,
        // Audit R95: korrupte Eingaben/Vertragsbruch sind fail-closed -
        // dieselben kaputten Werte haben auch die Basisdosis gebaut.
        CandidateSearch.Reject.LEDGER_HOLD,
        CandidateSearch.Reject.INVALID_BAND,
        CandidateSearch.Reject.INVALID_CAPS,
        CandidateSearch.Reject.NON_FINITE           -> Kind.ENFORCE
        else                                        -> Kind.UNAVAILABLE
    }

    /**
     * @return die ggf. beschnittene Entscheidung. `result == null` (kein
     *  Vorschlag > 0, Suche nicht gerechnet) und UNAVAILABLE lassen die Basis
     *  unveraendert - der Aufrufer exportiert den Ausfall als Luecke.
     */
    fun apply(base: FuseController.Decision, result: CandidateSearch.Result?): FuseController.Decision {
        if (result == null) return base
        if (kindOf(result.reject) == Kind.UNAVAILABLE) return base
        if (result.smbU >= base.smbU - 1e-9) return base
        return base.copy(
            smbU = result.smbU,
            block = if (result.smbU <= 0.0) FuseController.Block.CANDIDATE else base.block,
            bindingLimit = "candidate:" + (result.reject?.name ?: result.bindingLimit),
        )
    }
}

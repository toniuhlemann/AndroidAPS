package app.aaps.fuse.core.controller

/**
 * WAS DER MARKERDRUCK UEBERSTIMMEN DARF - fuer die fruehe Versorgung (E-034 v2, [liftsEarlySupply]).
 *
 * Der bewusste Markerdruck autorisiert Insulin. Diese Datei sagt, WAS er damit ueberstimmen darf - und was nicht:
 *
 *     MODELL UEBERSTIMMBAR, WIRKLICHKEIT NICHT.
 *
 *   MODELLURTEIL    beruht auf einer VORHERSAGE. Bei einer Mahlzeit kann es systematisch danebenliegen, weil die
 *                   Bahn per Konstruktion kohlenhydratfrei ist. Ueberstimmbar - je nach Portion nur mit G1..G4.
 *
 *   WIRKLICHKEIT    beruht auf einer MESSUNG oder einer harten Grenze. Nicht ueberstimmbar - keine Autorisierung
 *                   macht einen gemessenen Wert ungeschehen. Tonis Entscheidung: "Der Marker autorisiert eine
 *                   Mahlzeit, aber kein Insulin bei aktuell gemessenem Tief" - SAFETY_HOLD beruht auf dem rohen
 *                   Messwert und bleibt geschlossen.
 *
 * Konsolidierung 2.0: die gemeinsame Tabelle von Prime (Phase A) und Mahlzeitenfundament (Phase B) ist mit beiden
 * entfallen; die Trennlinie gilt fuer die fruehe Versorgung unveraendert.
 */
object MarkerAuthorization {

    // Konsolidierung 2.0: die allgemeine Hebungstabelle (`lifts` samt der beiden abgeleiteten Mengen) hatte nach
    // Prime nur noch das Mahlzeitenfundament als Leser und ist mit ihm entfallen; ebenso die Tabelle der
    // v1-Guard-Ausnahme des Erwartungsanteils (`liftsExpectation`).

    /**
     * DIE FRUEHE VERSORGUNG (E-034 v2) - eine eigene Tabelle fuer Anfangsportion und weitere Portionen.
     *
     * [guardLift] und [tailLift] sind die fuer DIESE Portion geltenden Schalter:
     *  - Anfangsportion: G1/G2. Gehoben wird durch die Markerautorisierung, wie heute bei der Direktdosis.
     *  - weitere Portion: G3/G4. Gehoben wird das Urteil OHNE Erwartung - zulaessig nur, weil die Endpruefung dieselbe
     *    Menge MIT der angekuendigten Mahlzeit prueft (Guard bzw. Schwanz mit Erwartung). Ohne G3/G4 bleibt das
     *    Urteil ohne Erwartung hart.
     *
     * NONE und BELOW_PUMP_INCREMENT sind hebbar, weil die Portion ueber maxSMB hinaus bis zur maximalen Portion
     * reichen darf; die Endpruefung prueft auch dann jede Menge. HART bleiben alle gemessenen Befunde, alle
     * Mengengrenzen, NO_DEMAND und CANDIDATE mit anderem oder unbekanntem Grund - wie in der entfallenen
     * allgemeinen Tabelle.
     *
     * Ein `when` ohne `else`: ein neuer Enumwert laesst dieses Modul nicht mehr uebersetzen, bis jemand ihn
     * eingeordnet hat - der Riegel steht im Compiler, nicht in einem Test.
     */
    fun liftsEarlySupply(
        block: FuseController.Block,
        candidateReason: CandidateSearch.Reject?,
        authorized: Boolean,
        guardLift: Boolean,
        tailLift: Boolean,
    ): Boolean {
        if (!authorized) return false
        val guardGrund = candidateReason == CandidateSearch.Reject.GUARD_FLOOR
        return when (block) {
            FuseController.Block.NONE                  -> true
            FuseController.Block.BELOW_PUMP_INCREMENT  -> true
            FuseController.Block.GUARD_FLOOR           -> guardLift
            FuseController.Block.CANDIDATE             -> guardLift && guardGrund
            FuseController.Block.TAIL                  -> tailLift

            FuseController.Block.NO_DEMAND             -> false
            FuseController.Block.SAFETY_HOLD           -> false
            FuseController.Block.MEASURED_DESCENT_RISK -> false
            FuseController.Block.MARKER_PRIME_DEFERRED -> false
            FuseController.Block.HEALTH_NOT_READY      -> false
            FuseController.Block.HORIZON_MISSING       -> false
            FuseController.Block.NO_INPUT              -> false
            FuseController.Block.IOB_TH_REACHED        -> false
            FuseController.Block.MAX_IOB_REACHED       -> false
            FuseController.Block.EXPOSURE_LIMIT        -> false
            FuseController.Block.LEDGER_HOLD           -> false
            FuseController.Block.PUMP_BUSY             -> false
        }
    }
}

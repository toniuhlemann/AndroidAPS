package app.aaps.fuse.core.controller

/**
 * DIE GEMEINSAME EXPOSITIONSSICHT (Bauauftrag §5, Schritt A2).
 *
 * Bis A2 stand dieselbe Rechnung an sechs Stellen inline (CandidateSearch-
 * Caps zweifach, Ratio-Pfad-Caps, SubStep-otherCaps, AuthorizedLift):
 *
 *     headroom = Grenze − capIob − Transporthaftung
 *
 * mit capIob = max(BolusIOB, NettoIOB) (Tonis IOB-Referenz-Regel: stark
 * negatives Basal-Delta ist keine physische Substanz und erzeugt nie
 * SMB-Budget - die Regel steckt im capIob-EINGANG, nicht hier) und der
 * MODELLIERTEN Transportmenge (C3-02: nie kleiner als der Ledgerwert,
 * Headrooms koennen dadurch nur enger werden).
 *
 * A2 ist ein DOSIERNEUTRALES Refactoring: die Ausdrucksreihenfolge
 * `Grenze - capIob - transport` bleibt exakt erhalten (Gleitkomma!), damit
 * jede Aufrufstelle bitgleiche Werte sieht. Nachweis: volle Suiten plus
 * Base-vs-Kandidat-Replay ohne abweichende Zyklen.
 *
 * ZWEI DOKUMENTIERTE ABWEICHUNGEN, die A2 ausdruecklich NICHT vereinheitlicht
 * (beides waere eine Verhaltensaenderung und gehoert attributiert in
 * Schritt B):
 *
 *  1. [LivenessChannel.headroomU] klemmt Belegung und Ergebnis auf >= 0 und
 *     BENENNT die bindende Grenze (min aus iobTH/Kanaldeckel/maxIOB) - die
 *     geklemmte Variante bleibt dort.
 *  2. Die BASIS-Tore des Reglers (FuseController.decide, fast-/
 *     maxIobHeadroom) rechnen OHNE Transporthaftung; die transportkorrigierte
 *     Bindung passiert erst eine Stufe spaeter in der Kandidatensuche. Eine
 *     Vereinheitlichung VERSCHAERFT den Basispfad.
 *
 * Der spaetere zentrale Rahmen (Schritt B) erweitert diese Sicht um
 * `contextExposureLimit` im selben min - die Struktur dafuer ist diese
 * Klasse; neue Grenzen kommen als weitere Headroom-Felder hinzu, nie als
 * zweite parallele Buchhaltung.
 */
data class ExposureView(
    val iobThU: Double,
    val maxIobU: Double,
    /** capIob = max(BolusIOB, NettoIOB) - die Dosier-Referenz. */
    val capIobU: Double,
    /** Modellierte, noch nicht im IOB nachgewiesene Transporthaftung. */
    val transportU: Double,
) {

    /** §5 occupiedExposure: belegter Mengenraum einschliesslich der noch
     *  nicht im IOB erfassten Transporthaftung. REINE Diagnosegroesse (A3) -
     *  die Dosier-Headrooms unten behalten ihre eigene, bitgleiche
     *  Ausdrucksreihenfolge und lesen NICHT ueber diese Summe. */
    val occupiedU: Double = capIobU + transportU

    /** Rest unter der iobTH-Grenze (Grenze des schnellen Kanals) -
     *  UNGEKLEMMT: ein negativer Wert IST die Information "schon drueber". */
    val iobThHeadroomU: Double = iobThU - capIobU - transportU

    /** Rest unter maxIOB (der Gesamtdeckel) - ungeklemmt wie oben. */
    val maxIobHeadroomU: Double = maxIobU - capIobU - transportU

    /**
     * §8-COVERAGE-DIAGNOSE (A3) - reine Beobachtung, KEINE Dosieranweisung,
     * und ausdruecklich noch keine aktive Regel (Coverage bleibt offen).
     *
     * Bezugsgroessen, eindeutig benannt (§8): BG = gefilterter q1 am
     * Signalanker; Ziel = aktuelles targetMgdl des Zyklus; ISF = isfMgdlPerU
     * am Anker; Einheiten mg/dl bzw. U; Zeitbezug = computeTs des Zyklus.
     *
     * Regeln: fehlende oder unbrauchbare Eingaben -> UNBEKANNT (null), nie
     * eine erfundene 0. Bedarf 0 -> Prozentwert NICHT definiert (null, kein
     * Ersatznenner); excessU bleibt definiert (= occupied). KEIN erneuter
     * IOB-Abzug von prognosebasiertem insulinReq - der statische Bedarf ist
     * bewusst die rohe Distanzrechnung.
     */
    data class Coverage(
        val staticCorrectionNeedU: Double?,
        val coveragePct: Double?,
        val excessU: Double?,
    )

    fun coverage(q1Mgdl: Double?, targetMgdl: Double?, isfMgdlPerU: Double?): Coverage {
        if (q1Mgdl == null || targetMgdl == null || isfMgdlPerU == null ||
            !q1Mgdl.isFinite() || !targetMgdl.isFinite() ||
            !isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0 || !occupiedU.isFinite()
        ) return Coverage(null, null, null)
        val needU = kotlin.math.max(0.0, (q1Mgdl - targetMgdl) / isfMgdlPerU)
        return Coverage(
            staticCorrectionNeedU = needU,
            coveragePct = if (needU > 0.0) 100.0 * occupiedU / needU else null,
            excessU = occupiedU - needU,
        )
    }

    companion object {
        fun of(iobThU: Double, maxIobU: Double, capIobU: Double, transportU: Double) =
            ExposureView(iobThU = iobThU, maxIobU = maxIobU, capIobU = capIobU, transportU = transportU)
    }
}

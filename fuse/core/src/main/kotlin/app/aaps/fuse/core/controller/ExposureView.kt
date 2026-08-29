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

    /** Rest unter der iobTH-Grenze (Grenze des schnellen Kanals) -
     *  UNGEKLEMMT: ein negativer Wert IST die Information "schon drueber". */
    val iobThHeadroomU: Double = iobThU - capIobU - transportU

    /** Rest unter maxIOB (der Gesamtdeckel) - ungeklemmt wie oben. */
    val maxIobHeadroomU: Double = maxIobU - capIobU - transportU

    companion object {
        fun of(iobThU: Double, maxIobU: Double, capIobU: Double, transportU: Double) =
            ExposureView(iobThU = iobThU, maxIobU = maxIobU, capIobU = capIobU, transportU = transportU)
    }
}

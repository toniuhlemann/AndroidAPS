package app.aaps.fuse.core.controller

/**
 * iobTH in EINHEITEN (K2-C v0.2 §13).
 *
 * FUSE erbt die autoISF-Legacyformel `percent * 130% * maxIOB * Reduktion`
 * ausdruecklich NICHT. Sie rechnet den Prozentsatz durch mehrere Faktoren, von
 * denen einer (die 130 %) ein Profilartefakt ist — und sie wird an mehreren
 * Stellen angewandt, was schon zweimal zu doppelter Skalierung gefuehrt hat.
 *
 * Variante B: der Prozentsatz ist DIREKT der Anteil von `maxIobU`, die
 * Umrechnung passiert GENAU EINMAL, und [FORMULA_ID] steht im Export — damit
 * eine spaetere Auswertung nie raten muss, welche Formel gerechnet wurde.
 *
 * iobTH ist die Grenze zwischen schnellem und langsamem Kanal, NICHT der
 * Gesamtdeckel: oberhalb laeuft Basal weiter, nur der SMB-Kanal schliesst.
 */
object IobThreshold {

    const val FORMULA_ID = "fuse-iobth-v1-variantB:iobThU=percent/100*maxIobU"

    fun fromPercent(percent: Double, maxIobU: Double): Double {
        require(percent.isFinite() && percent >= 0.0) { "iobTh percent invalid: $percent" }
        require(maxIobU.isFinite() && maxIobU >= 0.0) { "maxIob invalid: $maxIobU" }
        return percent / 100.0 * maxIobU
    }

    /** Bindungsgroesse ist `capIobU` (max(netIob, bolusIob)): zurueckgehaltenes
     *  Basal waehrend einer Zero-TBR darf kein zusaetzliches SMB-Budget
     *  erzeugen. */
    fun headroomU(iobThU: Double, capIobU: Double): Double = iobThU - capIobU
}

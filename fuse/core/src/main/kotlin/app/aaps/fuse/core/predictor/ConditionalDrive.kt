package app.aaps.fuse.core.predictor

/**
 * Die Hebung der Sicherheitskanten durch einen erklaerten Kredit.
 *
 * WOZU: der Schwanz-Guard rechnet sein Budget aus
 * `minSafetyHorizonLowerOf(haupt, bremse)` - also aus einem Verlauf OHNE
 * Kohlenhydrate. Er verbietet damit genau das Insulin, das die angekuendigte
 * Mahlzeit rechtfertigt. Die bedingten Bahnen heben diese Kanten um den bereits
 * begrenzten, bereits widerrufbaren Kredit.
 *
 * WARUM DAS HIER STEHT UND NICHT IM RUNNER - und der Grund ist ein Fehler, der
 * genau so passiert ist:
 *
 * Der erste Anlauf hob nur die HAUPTbahn. Waehrend einer echten Mahlzeit war
 * aber die BREMSbahn die bindende (gemessen 11.08.: Haupt 114,5, kombiniert
 * 91,8), und das Minimum ueber beide blieb unveraendert. Die Hebung existierte
 * und erreichte nie den Wert, der entscheidet. Fuenf gruene Tests standen
 * daneben - sie prueften EINZELNE Bahnen, der Fehler sass in der
 * ZUSAMMENSETZUNG.
 *
 * Der zweite Anlauf koppelte dann die Bremsbahn an die Hauptbahn
 * (`if (conditional == null) ... null`): stand die Hauptbahn schon an ihrem
 * Deckel, wurde die Bremsbahn gar nicht mehr gerechnet, obwohl sie hebbar war.
 *
 * Beide Fehler haben dieselbe Wurzel: die Hebung war eine Folge von
 * `if`-Zweigen im Zyklus statt einer Rechnung mit einem Namen. Hier ist sie
 * eine, sie behandelt beide Bahnen UNABHAENGIG, und sie ist ohne Mock-Apparat
 * pruefbar.
 */
object ConditionalDrive {

    /**
     * @param main die gehobene Hauptbahn, `null` = nicht hebbar (steht schon am
     *   Deckel oder es gibt keinen Kredit).
     * @param restraint dasselbe fuer die Bremsbahn. UNABHAENGIG von [main] -
     *   eine der beiden kann hebbar sein und die andere nicht.
     */
    data class Lift(val main: DriveEstimate?, val restraint: DriveEstimate?) {
        val any: Boolean get() = main != null || restraint != null
    }

    /**
     * @param mainDrive der Antrieb der Hauptbahn. Gehoben wird seine
     *   PRIOR-FREIE Kante, gedeckelt an seiner ANZEIGE-Kante - die Invariante
     *   `priorFree <= lower` laesst nichts anderes zu, und sie ist damit der
     *   Deckel, ohne dass ihn jemand hinschreiben muesste.
     * @param restraintMean die schnelle Rate. Deckel der Bremsbahn: weiter als
     *   bis zu ihrem eigenen Mittel kommt auch sie nie.
     * @param restraintLower ihre untere Kante. Sie wird gehoben, nicht ihre
     *   prior-freie: die Bremsbahn wird ohne Prior gebaut, ihre
     *   Sicherheitskante IST ihre untere Kante, es gibt keinen Zwischenraum.
     * @param declaredDriveMgdlPerMin der Kredit. <= 0 heisst: keine Hebung,
     *   und beide Bahnen bleiben bitgleich zu vorher.
     */
    fun of(
        mainDrive: DriveEstimate,
        restraintMean: Double?,
        restraintLower: Double?,
        restraintMethodId: String,
        declaredDriveMgdlPerMin: Double,
    ): Lift {
        if (!declaredDriveMgdlPerMin.isFinite() || declaredDriveMgdlPerMin <= 0.0) return Lift(null, null)

        val mainGehoben = minOf(
            mainDrive.lowerMgdlPerMin,
            mainDrive.safetyLowerMgdlPerMin + declaredDriveMgdlPerMin,
        )
        val main =
            if (mainGehoben > mainDrive.safetyLowerMgdlPerMin)
                mainDrive.copy(lowerPriorFreeMgdlPerMin = mainGehoben)
            else null

        val restraint =
            if (restraintMean == null || restraintLower == null ||
                !restraintMean.isFinite() || !restraintLower.isFinite()
            ) null
            else {
                val gehoben = minOf(restraintMean, restraintLower + declaredDriveMgdlPerMin)
                if (gehoben > restraintLower)
                    DriveEstimate(restraintMean, gehoben, null, "$restraintMethodId+COND")
                else null
            }

        return Lift(main, restraint)
    }
}

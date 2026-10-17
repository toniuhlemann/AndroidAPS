package app.aaps.fuse.core.controller

/**
 * QUANTISIERUNGS-TOTZONE (Toni 09.08., am Geraet gefunden).
 *
 * `insulinReq 0,24 U x Ratio 0,15 = 0,036 U` faellt unter den Pumpenschritt
 * (0,05 U) und wird auf NULL abgerundet - dauerhaft, solange der Bedarf unter
 * ~0,334 U bleibt. autoISF verliert diesen Rest nicht, weil dort die TBR den
 * langsamen Kanal stellt; FUSE hat per Design keine positive TBR. Ohne Zaehler
 * verfaellt die feine Dosierung also am Pumpenschritt - und ein Plateau bei
 * 130-140 kann stundenlang stehen bleiben, obwohl der Regler jede Minute
 * dosieren WILL.
 *
 * DIE KORREKTUR IST EIN TAKT, KEINE MENGE: der Pumpenschritt ist ein
 * physikalisches Atom, aber FUSE entscheidet jede Minute. Die gewuenschte Rate
 * wird deshalb in eine Tropfen-FREQUENZ uebersetzt - 0,036 U/min werden zu
 * einem 0,05er alle ~1,4 min, 0,005 U/min zu einem alle 10 min.
 *
 * WARUM DAS KEIN "MEHR INSULIN" IST: aufgeschobene Absicht, kein Guthaben.
 *  - Gedeckelt auf EINEN Pumpenschritt: der Zaehler kann strukturell keinen
 *    Schub erzeugen, nur die Quantisierung glaetten.
 *  - Der freigegebene Schritt ist eine ganz normale Menge und laeuft durch
 *    dieselbe Kette (Guard auf prior-freier Bahn + Bremsbahn, Schwanz,
 *    finale Wirkungspruefung, Ledger). Der Zaehler sitzt DAVOR, nicht daneben.
 *  - Jeder Zweifel verwirft: Block, Wende, Rebound-Fenster, kein Bedarf,
 *    Abbruch, Epochwechsel. Was aufgeschoben war, ist dann nicht mehr gewollt.
 */
object SubStepAccumulator {

    /**
     * @param carriedU bisher aufgeschobene Menge [U] (Zustand des Aufrufers)
     * @param desiredU in DIESEM Zyklus gewuenschte Menge VOR der Rasterung [U]
     * @param steppedU die daraus gerasterte, tatsaechlich vorgeschlagene Menge [U]
     * @param pumpIncrementU Pumpenschritt [U]
     * @param discard true = jeder Zweifel (Block/Wende/Rebound/Abbruch/Epoch)
     * @return [Result.releaseU] zusaetzlich freizugebende Menge (0 oder genau
     *   ein Pumpenschritt) und [Result.carryU] der neue Zaehlerstand
     */
    fun step(
        carriedU: Double,
        desiredU: Double,
        steppedU: Double,
        pumpIncrementU: Double,
        discard: Boolean,
    ): Result {
        if (discard) return Result(0.0, 0.0)
        if (!carriedU.isFinite() || !desiredU.isFinite() || !steppedU.isFinite() || !pumpIncrementU.isFinite()) return Result(0.0, 0.0)
        if (pumpIncrementU <= 0.0 || desiredU <= 0.0) return Result(0.0, 0.0)
        // Nur der VERWORFENE Rest wird aufgeschoben - was schon vorgeschlagen
        // wurde, ist keine offene Absicht mehr.
        val remainder = (desiredU - steppedU).coerceAtLeast(0.0)
        val pot = carriedU.coerceAtLeast(0.0) + remainder
        if (pot + 1e-12 < pumpIncrementU) {
            // Deckel: nie mehr als ein Schritt vorhalten (s. KDoc).
            return Result(0.0, pot.coerceAtMost(pumpIncrementU))
        }
        return Result(pumpIncrementU, (pot - pumpIncrementU).coerceIn(0.0, pumpIncrementU))
    }

    data class Result(val releaseU: Double, val carryU: Double)

    /**
     * Unter WELCHEN Bedingungen ein Uebertrag entstanden ist (SUB-02 Rest,
     * Codex Re-Review 603a15a).
     *
     * Der Uebertrag ist eine ZUSAGE: "diesen Rest gebe ich, sobald ein ganzer
     * Pumpenschritt zusammenkommt". Sie gilt fuer die Lage, in der sie
     * entstanden ist. Aendert sich das Ziel, der Profil-ISF, der
     * Pumpenschritt, eine der Ratios oder der Mahlzeiten-Zustand, dann wuerde
     * ein stehengebliebener Rest eine ANDERE Entscheidung mitfinanzieren, als
     * die, aus der er stammt - ein Profilwechsel mitten in der Nacht ist genau
     * der Fall.
     *
     * Bewusst eine Zeichenkette und kein Hash: sie steht so im Vergleich, ist
     * im Fehlerfall lesbar, und eine Kollision waere hier ein stiller
     * Sicherheitsverlust.
     */
    fun context(
        targetMgdl: Double,
        isfMgdlPerU: Double,
        pumpIncrementU: Double,
        smbRatio: Double,
        smbRatioRise: Double,
        maxSmbU: Double,
        mealWindow: Boolean,
        // ERGAENZT nach Codex Combined Closure b6dbb490 (D5): der Abdruck
        // deckte nur Zahlen ab, die sich SELTEN aendern. Ein Profilwechsel mit
        // gleichem ISF und Ziel, ein neuer Marker bei laufendem Fenster oder
        // ein geaendertes iobTH liessen den Uebertrag stehen - obwohl er aus
        // einer anderen Lage stammte.
        maxIobU: Double = Double.NaN,
        iobThU: Double = Double.NaN,
        /** Zeitpunkt des Markerdrucks - ein NEUER Marker ist eine neue Lage,
         *  auch wenn "Marker aktiv" durchgehend wahr blieb. */
        markerTs: Long = 0L,
        /** Die Identitaet des Profils, nicht nur seine Zahlen. */
        profileName: String = "",
    ): String = listOf(targetMgdl, isfMgdlPerU, pumpIncrementU, smbRatio, smbRatioRise, maxSmbU, maxIobU, iobThU)
        .joinToString("|") { String.format(java.util.Locale.ROOT, "%.6f", it) } +
        (if (mealWindow) "|meal" else "|-") + "|" + markerTs + "|" + profileName
}
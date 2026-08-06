package app.aaps.fuse.core.signal

/**
 * Wo die Eingangsreihe des Filters ANFAENGT — die Portierung von R60-F1.
 *
 * Warum es diese Regel ueberhaupt braucht: [UkfQ1.leadingEdge] trennt von sich
 * aus nur bei einer Luecke > 60 min, bei Doppelstempeln und am 38-Sentinel. Zwei
 * Faelle liegen genau darunter und sind die gefaehrlichen:
 *
 *  SENSORWECHSEL OHNE LUECKE — Libre 3 / Juggluco liefern nach dem Wechsel
 *      unmittelbar weiter. Es entsteht keine 60-min-Luecke, aber ein anderes
 *      Messregime; der Filter sieht eine lueckenlose Reihe.
 *
 *  KALIBRIERUNG — hier gibt es GAR KEINE Luecke: ab dem naechsten Punkt gilt
 *      neue Slope/Offset, waehrend die historischen Werte die alte Skala
 *      tragen. Fuer den UKF ist der Sprung ein Messfehler, also steigt sein
 *      effektives R und der Zustand wird LANGSAM nachgezogen — exakt das
 *      Gegenteil dessen, was man will.
 *
 * Der Observer faengt das NICHT auf: `SENSOR_EMBARGO` (30 min) und
 * `CALIBRATION_BLIND` (10 min) blockieren die Dosis, die Kontamination der Reihe
 * dauert aber bis zum Fensterende. Danach ist health wieder READY und es wird
 * bis zu 150 bzw. 170 Minuten lang mit zwei Messregimen in einer Reihe
 * gerechnet — ohne Grund, ohne Marke.
 *
 * REIN: keine AAPS-Abhaengigkeit, damit dieselbe Regel auf der JVM replaybar
 * bleibt.
 */
object SignalWindow {

    /**
     * Wieviel Vorlauf die Reihe braucht.
     *
     * NICHT nur [UkfQ1.WINDOW_SAMPLES] Minuten. Der Aufrufer rechnet q1 KAUSAL
     * je Punkt des R-Fensters, jeder Punkt bekommt sein eigenes Praefix von bis
     * zu [UkfQ1.WINDOW_SAMPLES] Proben — der AELTESTE Punkt des R-Fensters
     * reicht damit um zusaetzlich [BgiAdjustedSeries.WINDOW_MS] weiter zurueck.
     * Wer die Reihe bei `anker - 180 min` abschneidet, nimmt genau diesem Punkt
     * rund 18 Proben weg: anderer UKF-Startzustand, anderes gelerntes R, anderes
     * q1 — und damit ein anderes rSigned, obwohl gar keine Grenze gegriffen hat.
     * Das waere eine stille Verletzung der Kandidatenidentitaet.
     */
    const val LOOKBACK_MS = UkfQ1.WINDOW_SAMPLES * 60_000L + BgiAdjustedSeries.WINDOW_MS

    /**
     * Was die Untergrenze gesetzt hat.
     *
     * `NONE` heisst: nur der normale Vorlauf, KEIN Regimewechsel. Der Fork
     * nennt diesen Fall `"window"`, weil er einen einzigen ankerbezogenen
     * 180-min-Fensterrand hat. Hier waere das eine Aussage ueber eine Regel, die
     * es nicht gibt — die Fensterkappe ist proben-, nicht zeitbasiert und sitzt
     * je Praefix.
     */
    enum class Bound { NONE, SENSOR_CHANGE, CALIBRATION_START }

    data class Result(val fromTs: Long, val bound: Bound) {

        /** Kurzform fuer Grund und Export. */
        val label: String get() = bound.name
    }

    /**
     * @param anchorTs juengster Zeitpunkt, auf den sich das Fenster bezieht.
     * @param sensorStartTs Beginn der laufenden Sensorlaufzeit, `<= 0` = unbekannt.
     * @param calibrationStartTs Beginn der laufenden Kalibrierung, `<= 0` = unbekannt.
     */
    fun of(anchorTs: Long, sensorStartTs: Long, calibrationStartTs: Long): Result {
        val sensor = sensorStartTs.coerceAtLeast(0L)
        val calibration = calibrationStartTs.coerceAtLeast(0L)
        val resetBoundary = maxOf(sensor, calibration)
        val lookbackStart = anchorTs - LOOKBACK_MS
        // Eine Grenze, die in der ZUKUNFT des Ankers liegt, ist keine Grenze fuer
        // diese Reihe — sie wuerde das Fenster leeren, statt es zu trennen.
        val effective = if (resetBoundary in (lookbackStart + 1)..anchorTs) resetBoundary else lookbackStart
        val bound = when {
            effective == lookbackStart -> Bound.NONE
            // Tie-Regel 1:1 vom Fork: bei Gleichstand gewinnt der Sensorwechsel.
            sensor >= calibration      -> Bound.SENSOR_CHANGE
            else                       -> Bound.CALIBRATION_START
        }
        return Result(effective, bound)
    }
}

package app.aaps.fuse.core.signal

/**
 * WARUM BEGINNT DIESES SEGMENT HIER - und darf deshalb frueher gereift
 * werden? (Bauauftrag Toni 25.08. abends.)
 *
 * Diese Entscheidung ist bewusst eine REINE FUNKTION auf Zeitstempeln.
 * In [app.aaps.fuse.plugin.FuseSignalSource] waere sie an
 * `iobCobCalculator` und `profileFunction` gefesselt und praktisch nicht
 * pruefbar; hier braucht sie nichts als die aufsteigende Zeitreihe, den
 * Segmentbeginn und die Fenstergrenze.
 *
 * DER KERN: eine Lockerung ist nur dann zulaessig, wenn das Segment
 * ausschliesslich deshalb hier beginnt, weil der FUNK fehlte - die Reihe
 * davor also nachweislich existierte und dieselbe Kurve beschrieb. Jeder
 * andere Grund beschreibt ein NEUES Messregime:
 *
 *   COLD_START      kein Punkt vor dem Segmentbeginn - der Puffer faengt
 *                   hier an. Nichts belegt, dass es eine Kurve gab.
 *   SENSOR_CHANGE   neuer Sensor, neue Kennlinie.
 *   CALIBRATION     neue Kalibrierung, verschobene Kennlinie.
 *   INPUT_STEP      unklassifizierter Sprung; die Reihe davor gehoert zu
 *                   einer anderen Groesse.
 *   NO_BREAK        gar kein Bruch - die Reihe laeuft durch, die Frage
 *                   stellt sich nicht. Das ist auch der Fall bei einer
 *                   SCHLEIFENPAUSE: stand nur der Regler und lief das CGM
 *                   weiter, hat die Reihe keine Luecke.
 *   GAP_TOO_LONG    Funkluecke, aber zu lang: zu viel Kurve unbeobachtet.
 *   TOO_OLD         Funkluecke, aber das Segment ist zu alt - dann ist
 *                   die Kadenz das Problem, nicht die Luecke.
 */
object SignalRejoin {

    enum class Cause {
        GAP, NO_BREAK, COLD_START, SENSOR_CHANGE, CALIBRATION, INPUT_STEP,
        GAP_TOO_LONG, TOO_OLD, DISABLED
    }

    /**
     * Das Ergebnis - und zugleich die eine Wahrheit, die BEIDE
     * Schaetzerverbraucher lesen (`theilSen` in der Signalquelle und
     * `PairSlopeBand.estimate` im Runner). Eine zweite, unabhaengig
     * getroffene Auswahl waere genau der Fehler, den die Gap-Grenze schon
     * einmal hatte: der Trail truege dann ein r, das der Regler nicht
     * hatte.
     */
    class Selection internal constructor(
        /** Die WIRKSAME Reife dieses Zyklus. */
        val maturity: MaturityPolicy,
        /** Wurde tatsaechlich gelockert? */
        val active: Boolean,
        val cause: Cause,
        /** Dauer der Funkluecke [ms], 0 wenn keine erkannt. */
        val gapMs: Long,
        /** Alter des Segments [ms]. */
        val ageMs: Long,
    ) {

        /** Kurzform fuer Trail und Anzeige. */
        val label: String
            get() = if (active) "REJOIN ${maturity.tag()}" else "STRICT ${cause.name}"
    }

    /**
     * DER "NICHTS PASSIERT"-WERT - fuer Pfade, die gar keinen
     * Wiedereinstieg kennen (Anzeige-Attrappen, Exporttests). Bewusst
     * eine benannte Funktion und KEIN Default am Signalfeld: ein Default
     * liesse eine fehlende Verdrahtung still durchrutschen.
     */
    fun strict(base: MaturityPolicy = MaturityPolicy.PRODUCTION): Selection =
        Selection(base, false, Cause.DISABLED, 0L, 0L)

    /**
     * @param policy der unveraenderliche Wiedereinstieg dieses Runners.
     * @param base die sonst geltende Reife (Produktion, im Replay auch eine
     *   Variante). Sie ist das Ergebnis, wann immer nicht gelockert wird.
     * @param ascendingTs Zeitstempel der Reihe, aufsteigend, bereits an
     *   Epochen- und Sprunggrenze beschnitten - also genau die Reihe, auf
     *   der auch der Schaetzer rechnet.
     * @param segmentStartTs Beginn des juengsten lueckenfreien Segments.
     * @param bound warum das FENSTER begrenzt ist (Sensor, Kalibrierung,
     *   Sprung oder nichts davon).
     * @param nowTs Anker dieses Zyklus.
     * @param breakMs die wirksame Segmentgrenze, s. [GapPolicy].
     */
    fun select(
        policy: RejoinPolicy,
        base: MaturityPolicy,
        ascendingTs: List<Long>,
        segmentStartTs: Long,
        bound: SignalWindow.Bound,
        nowTs: Long,
        breakMs: Long,
    ): Selection {
        val alter = nowTs - segmentStartTs
        if (!policy.enabled) return Selection(base, false, Cause.DISABLED, 0L, alter)

        // Ein begrenztes Fenster hat IMMER Vorrang: Sensorwechsel,
        // Kalibrierung und Eingangssprung beschreiben ein anderes
        // Messregime, gleichgueltig ob zusaetzlich eine Luecke vorliegt.
        when (bound) {
            SignalWindow.Bound.SENSOR_CHANGE     -> return Selection(base, false, Cause.SENSOR_CHANGE, 0L, alter)
            SignalWindow.Bound.CALIBRATION_START -> return Selection(base, false, Cause.CALIBRATION, 0L, alter)
            SignalWindow.Bound.INPUT_STEP        -> return Selection(base, false, Cause.INPUT_STEP, 0L, alter)
            SignalWindow.Bound.NONE              -> Unit
        }

        // Beginnt das Segment auf einem Punkt, dem ein Punkt VORAUSGEHT,
        // und ist der Abstand groesser als die Bruchgrenze? Nur dann ist
        // es eine Funkluecke - und nur dann ist belegt, dass es die Kurve
        // davor gab.
        val i = ascendingTs.indexOfFirst { it == segmentStartTs }
        if (i <= 0) {
            // i < 0: der Segmentbeginn ist die rollende Fensterkante, kein
            // Punkt - die Reihe laeuft durch. i == 0: kein Vorgaenger, der
            // Puffer faengt hier an.
            return Selection(base, false,
                             if (i < 0) Cause.NO_BREAK else Cause.COLD_START, 0L, alter)
        }
        val luecke = ascendingTs[i] - ascendingTs[i - 1]
        if (luecke <= breakMs) return Selection(base, false, Cause.NO_BREAK, luecke, alter)
        if (luecke > policy.maxGapMs) return Selection(base, false, Cause.GAP_TOO_LONG, luecke, alter)
        if (alter > policy.maxAgeMs) return Selection(base, false, Cause.TOO_OLD, luecke, alter)

        // Und selbst dann wird nur GELOCKERT, nie verschaerft: ist die
        // Basis ohnehin lockerer (Reife-Replay), bleibt sie.
        val gewaehlt =
            if (base.minPoints <= policy.maturity.minPoints && base.minSlopes <= policy.maturity.minSlopes) base
            else policy.maturity
        return Selection(gewaehlt, gewaehlt !== base, Cause.GAP, luecke, alter)
    }
}

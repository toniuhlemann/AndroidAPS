package app.aaps.fuse.core.signal

/**
 * WARUM BEGINNT DIESES SEGMENT HIER - und darf deshalb frueher gereift
 * werden? (Bauauftrag Toni 25.08. abends, korrigiert nach seinem Review
 * am selben Abend.)
 *
 * Diese Entscheidung ist bewusst eine REINE FUNKTION auf Zeitstempeln.
 * In [app.aaps.fuse.plugin.FuseSignalSource] waere sie an
 * `iobCobCalculator` und `profileFunction` gefesselt und praktisch nicht
 * pruefbar; hier braucht sie nichts als die aufsteigende Zeitreihe, den
 * Segmentbeginn und die Regimegrenze.
 *
 * DIE KORREKTUR, DIE TONI ANGEMAHNT HAT - zwei verschiedene Fragen, die
 * der erste Wurf verwechselte:
 *
 *   `SignalWindow.Bound` beantwortet "warum beginnt das 180-min-FENSTER?"
 *   Dieser Code braucht  "warum beginnt das aktuelle SEGMENT?"
 *
 * Der erste Wurf sperrte deshalb nach einer Kalibrierung DREI STUNDEN
 * lang jeden spaeteren, voellig eigenstaendigen Funkabriss - solange die
 * Grenze im Rueckblickpuffer lag. Das ist nicht bloss konservativ,
 * sondern die falsche Identitaet. Richtig ist:
 *
 *   Ein Regimewechsel sperrt, bis das NEUE Regime einmal vollstaendig
 *   streng (5x8) gereift war. Danach ist es etabliert, und eine spaetere
 *   eigenstaendige Funkluecke ist wieder ein gewoehnlicher [Cause.GAP].
 *
 * Das ist zugleich SICHERER als eine pauschale Zeitspanne: es verhindert,
 * dass ein noch unreifes neues Sensorsignal ueber 4x3 vorzeitig
 * freigegeben wird, auch wenn die drei Stunden laengst um waeren.
 *
 * DIE ZWEITE LUECKE desselben Reviews: ein EINZIGER Messpunkt vor der
 * Luecke galt als Nachweis einer bekannten Kurve. Nach einem Kaltstart
 * haetten also zwei neue Werte, dann Funkverlust, dann 4x3-Reife den
 * Wiedereinstieg geoeffnet - obwohl vor der Luecke nie ein streng
 * gereiftes Signal existierte. Deshalb [Cause.PRE_GAP_NOT_MATURE]: der
 * Abschnitt zwischen letzter Regimegrenze und dem Punkt VOR der Luecke
 * muss selbst streng reif gewesen sein.
 *
 * DIE GRUENDE:
 *
 *   GAP                 echte Funkluecke in einem etablierten Regime.
 *                       Der einzige Grund, der lockert.
 *   NO_BREAK            gar kein Bruch - die Reihe laeuft durch. Das ist
 *                       auch der Fall bei einer SCHLEIFENPAUSE: stand nur
 *                       der Regler und lief das CGM weiter, hat die Reihe
 *                       keine Luecke.
 *   COLD_START          der Segmentbeginn ist der erste Punkt ueberhaupt.
 *   SENSOR_CHANGE       neuer Sensor - und er erklaert DIESEN
 *   CALIBRATION         Segmentbeginn, nicht bloss die Fenstergrenze.
 *   INPUT_STEP          unklassifizierter Sprung, ebenso.
 *   PRE_GAP_NOT_MATURE  Funkluecke, aber der Abschnitt davor war nie
 *                       streng gereift - es gibt keine bekannte Kurve
 *                       fortzusetzen.
 *   GAP_TOO_LONG        Funkluecke, aber zu lang: zu viel Kurve
 *                       unbeobachtet.
 *   TOO_OLD             Funkluecke, aber das Segment ist zu alt - dann
 *                       ist die Kadenz das Problem, nicht die Luecke.
 */
object SignalRejoin {

    enum class Cause {
        GAP, NO_BREAK, COLD_START, SENSOR_CHANGE, CALIBRATION, INPUT_STEP,
        PRE_GAP_NOT_MATURE, GAP_TOO_LONG, TOO_OLD, DISABLED
    }

    /**
     * DIE REGIMEGRENZE ALS (URSACHE, ZEITPUNKT) - nicht als blosses Enum.
     *
     * Ohne den Zeitpunkt laesst sich nicht entscheiden, ob die Grenze den
     * AKTUELLEN Segmentbeginn erklaert oder bloss irgendwo im
     * Rueckblickpuffer liegt. Genau diese Verwechslung war der Befund.
     *
     * @param ts Zeitpunkt der Grenze; 0 wenn [bound] == NONE.
     */
    class Regime(val bound: SignalWindow.Bound, val ts: Long) {

        override fun toString() = "Regime(${bound.name}@$ts)"

        companion object {

            /** Kein Regimewechsel im Puffer. */
            val NONE = Regime(SignalWindow.Bound.NONE, 0L)
        }
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
        /**
         * War der Abschnitt zwischen letzter Regimegrenze und dem Punkt
         * VOR der Luecke bereits streng (5x8) reif? Nur dann gibt es eine
         * bekannte Kurve fortzusetzen. `false` auch dann, wenn gar keine
         * Luecke vorlag - die Frage stellt sich dann nicht.
         */
        val preGapStrictReady: Boolean,
        /** Die Regimegrenze im Puffer - unabhaengig davon, ob sie den
         *  Segmentbeginn erklaert. */
        val regime: Regime,
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
        Selection(base, false, Cause.DISABLED, 0L, 0L, false, Regime.NONE)

    /**
     * @param policy der unveraenderliche Wiedereinstieg dieses Runners.
     * @param base die sonst geltende Reife (Produktion, im Replay auch eine
     *   Variante). Sie ist das Ergebnis, wann immer nicht gelockert wird.
     * @param ascendingTs Zeitstempel der Reihe, aufsteigend, bereits an
     *   Epochen- und Sprunggrenze beschnitten - also genau die Reihe, auf
     *   der auch der Schaetzer rechnet.
     * @param segmentStartTs Beginn des juengsten lueckenfreien Segments.
     * @param regime die Regimegrenze samt Zeitpunkt.
     * @param nowTs Anker dieses Zyklus.
     * @param gapPolicy die wirksame Segmentgrenze, s. [GapPolicy].
     * @param windowMs Fensterbreite fuer die Reifepruefung vor der Luecke.
     * @param strict die STRENGE Reife, gegen die der Abschnitt vor der
     *   Luecke geprueft wird. Ausdruecklich nicht [base]: sonst waere die
     *   Pruefung in einem Reife-Replay zirkulaer.
     */
    fun select(
        policy: RejoinPolicy,
        base: MaturityPolicy,
        ascendingTs: List<Long>,
        segmentStartTs: Long,
        regime: Regime,
        nowTs: Long,
        gapPolicy: GapPolicy,
        windowMs: Long = BgiAdjustedSeries.WINDOW_MS,
        strict: MaturityPolicy = MaturityPolicy.PRODUCTION,
    ): Selection {
        val alter = nowTs - segmentStartTs
        fun nein(c: Cause, gap: Long = 0L, reif: Boolean = false) =
            Selection(base, false, c, gap, alter, reif, regime)

        if (!policy.enabled) return nein(Cause.DISABLED)

        // Beginnt das Segment auf einem Punkt, dem ein Punkt VORAUSGEHT?
        // Nur dann gab es ueberhaupt eine Kurve davor.
        val i = ascendingTs.indexOfFirst { it == segmentStartTs }
        if (i < 0) {
            // Der Segmentbeginn ist die rollende Fensterkante, kein Punkt -
            // die Reihe laeuft durch.
            return nein(Cause.NO_BREAK)
        }
        if (i == 0) {
            // Kein Vorgaenger. WARUM nicht? Wenn die Reihe an einer
            // Regimegrenze beschnitten wurde, ERKLAERT diese Grenze den
            // Segmentbeginn - und nur dann sperrt sie.
            return nein(
                when (regime.bound) {
                    SignalWindow.Bound.SENSOR_CHANGE     -> Cause.SENSOR_CHANGE
                    SignalWindow.Bound.CALIBRATION_START -> Cause.CALIBRATION
                    SignalWindow.Bound.INPUT_STEP        -> Cause.INPUT_STEP
                    SignalWindow.Bound.NONE              -> Cause.COLD_START
                }
            )
        }

        val luecke = ascendingTs[i] - ascendingTs[i - 1]
        if (luecke <= gapPolicy.rSegmentBreakMs) return nein(Cause.NO_BREAK, luecke)
        if (luecke > policy.maxGapMs) return nein(Cause.GAP_TOO_LONG, luecke)
        if (alter > policy.maxAgeMs) return nein(Cause.TOO_OLD, luecke)

        // DIE ETABLIERUNGSPRUEFUNG. Der Abschnitt zwischen der letzten
        // Regimegrenze und dem Punkt VOR der Luecke muss selbst streng
        // gereift gewesen sein. Damit sperrt ein Regimewechsel genau so
        // lange, wie das neue Regime braucht, um sich zu etablieren - und
        // keine Minute laenger.
        val reif = strengReifVorLuecke(ascendingTs, i, regime.ts, gapPolicy, windowMs, strict)
        if (!reif) return nein(Cause.PRE_GAP_NOT_MATURE, luecke)

        // Und selbst dann wird nur GELOCKERT, nie verschaerft: ist die
        // Basis ohnehin lockerer (Reife-Replay), bleibt sie.
        val gewaehlt =
            if (base.minPoints <= policy.maturity.minPoints && base.minSlopes <= policy.maturity.minSlopes) base
            else policy.maturity
        return Selection(gewaehlt, gewaehlt !== base, Cause.GAP, luecke, alter, true, regime)
    }

    /**
     * War die Reihe UNMITTELBAR VOR der Luecke streng reif?
     *
     * Gerechnet auf demselben Fenster und mit derselben Paarschranke wie
     * der Schaetzer, aber ausschliesslich auf Zeitstempeln - Reife haengt
     * nicht von den Werten ab. Beruecksichtigt werden nur Punkte, die
     *
     *   - im Fenster vor dem letzten Punkt liegen,
     *   - NACH der Regimegrenze liegen (ein aelteres Regime belegt
     *     nichts ueber das aktuelle) und
     *   - im selben lueckenfreien Abschnitt liegen (eine frueher Luecke
     *     trennt ebenso).
     */
    private fun strengReifVorLuecke(
        ascendingTs: List<Long>,
        gapIndex: Int,
        regimeTs: Long,
        gapPolicy: GapPolicy,
        windowMs: Long,
        strict: MaturityPolicy,
    ): Boolean {
        val letzter = ascendingTs[gapIndex - 1]
        val vor = ascendingTs.subList(0, gapIndex)
        // HIER klemmt die Regimegrenze, und nur hier: `segmentStart`
        // liefert entweder `fensterStart` selbst oder einen Punkt darueber,
        // also gilt immer `abschnittStart >= regimeTs`. Ein zusaetzlicher
        // Filter `it >= regimeTs` waere beweisbar tot - und toter Code an
        // einer Sicherheitspruefung ist eine Last, keine Absicherung: er
        // liesse sich nicht durch eine Mutation widerlegen und weckte
        // trotzdem den Eindruck einer zweiten Verteidigung.
        val fensterStart = maxOf(letzter - windowMs, regimeTs)
        val abschnittStart = BgiAdjustedSeries.segmentStart(vor, fensterStart, gapPolicy)
        val punkte = vor.filter { it >= abschnittStart }
        if (punkte.size < strict.minPoints) return false
        var paare = 0
        for (a in punkte.indices) for (b in a + 1 until punkte.size) {
            if (punkte[b] - punkte[a] >= BgiAdjustedSeries.PAIR_DT_MIN_MS) paare++
        }
        return paare >= strict.minSlopes
    }
}

package app.aaps.fuse.core.signal

/**
 * BGI-bereinigte Reihe und Theil-Sen-Steigung — der R(t)-Kern des K1-Observers
 * (Spec v0.5 §1.1, GELOCKT gegen den v6-Holdout-Evaluator).
 *
 * Kandidatenidentitaet — jede Abweichung hier waere ein NEUER, ungelockter
 * Kandidat:
 *  - bgiRate = -activity * profileIsf, RECHTER (aktueller) Wert fuer (t-1, t]
 *    (v6: cum_bgi += bgi * dt mit dem bgi des AKTUELLEN Samples)
 *  - adjusted = q1 - cumulativeBgi, Reset je R-Segment
 *  - Theil-Sen: Paare nur mit dt >= PAIR_DT_MIN_MS, mindestens MIN_POINTS
 *    Punkte und MIN_SLOPES Paare, sonst KEIN R (null — nie 0, Spec §12 U1-U5)
 *
 * UNKNOWN ist ein Zustand, kein Wert: fehlende activity/profileIsf erzeugen
 * hier keinen Ersatzwert — der Aufrufer (Adapter/State-Machine) bricht das
 * Segment und meldet DEGRADED. Diese Klasse rechnet nur auf VOLLSTAENDIGEN
 * Samples.
 */
object BgiAdjustedSeries {

    const val PAIR_DT_MIN_MS = 120_000L   // THEIL_SEN_PAIR_DT_MIN 2.0 min (v6 DT_MIN_PAIR)
    const val MIN_POINTS = 5              // THEIL_SEN_MIN_POINTS (v6)
    const val MIN_SLOPES = 8              // THEIL_SEN_MIN_SLOPES (v6)
    const val WINDOW_MS = 18 * 60_000L    // W = 18 min (Candidate-Lock R58)

    /** Ein vollstaendiges Sample: q1 aus [UkfQ1], activity [U/min] und
     *  profileIsf [mg/dl/U] bereits validiert (kausales LOCF, Slot bei sourceTs). */
    data class Sample(
        val sourceTs: Long,
        val q1: Double,
        val activity: Double,
        val profileIsf: Double,
    )

    /** Punkt der bereinigten Reihe innerhalb EINES R-Segments. */
    data class AdjustedPoint(val sourceTs: Long, val adjusted: Double)

    /**
     * DIE AUSGABE EINES EINZELNEN [adjust]-AUFRUFS - opak.
     *
     * Der Konstruktor ist `private`; eine Instanz entsteht nur aus rohen
     * [Sample]s ueber [adjust]. Damit ist die
     * Zusicherung, auf der der ganze Evidenzbestand ruht, eine
     * TYPEIGENSCHAFT und keine Konvention mehr (Toni 12.08.):
     *
     *   Zwei Punkte, deren Differenz gebildet wird, stammen aus DEMSELBEN
     *   Aufruf - also aus demselben Bezugssystem.
     *
     * WARUM DAS NOETIG IST: [adjust] setzt `cumulativeBgi` am Anfang des
     * uebergebenen Ausschnitts auf 0, und dieser Anfang wandert mit dem
     * Fenster. Zwei `adjusted`-Werte aus zwei Aufrufen zu subtrahieren ergibt
     * keine Stoerung, sondern den Unterschied zweier Nullpunkte - eine Zahl,
     * die plausibel aussieht und falsch ist. Genau dieser Fehler stand bis zum
     * 12.08. im Produktionspfad.
     *
     * Eine gewoehnliche `List<AdjustedPoint>` konnte man aus zwei Ausgaben
     * zusammensetzen. Diese Klasse nicht.
     */
    class AdjustedSeries private constructor(val points: List<AdjustedPoint>) {

        companion object {

            /** Einziger Bauweg: berechnet die ganze Reihe aus EINEM Segment.
             * Eine bereits berechnete Punktliste kann hier nicht eingeschleust
             * oder aus zwei `adjust()`-Ausgaben zusammengesetzt werden. */
            internal fun fromSegment(segment: List<Sample>): AdjustedSeries {
                if (segment.isEmpty()) return AdjustedSeries(emptyList())
                val out = ArrayList<AdjustedPoint>(segment.size)
                var cumulativeBgi = 0.0
                var prevTs = segment.first().sourceTs
                for (s in segment) {
                    val dtMin = (s.sourceTs - prevTs) / 60_000.0
                    require(dtMin >= 0.0) { "segment not ascending at ${s.sourceTs}" }
                    val bgiRate = -s.activity * s.profileIsf
                    cumulativeBgi += bgiRate * dtMin
                    out.add(AdjustedPoint(s.sourceTs, s.q1 - cumulativeBgi))
                    prevTs = s.sourceTs
                }
                return AdjustedSeries(out)
            }
        }
    }

    /**
     * EIN ZUWACHS DER BEREINIGTEN REIHE, MIT SEINEM BEWEIS.
     *
     * Privater Konstruktor: die einzige Quelle ist [of], und die verlangt eine
     * [AdjustedSeries]. Ein nacktes Delta traegt nicht, WELCHE Messpunkte es
     * umfasst - es liesse sich versehentlich zweimal einreichen, einmal je
     * Reglerzyklus auf demselben CGM-Punkt. Mit den beiden Zeitpunkten kann
     * der Verbraucher die Exactly-once-Regel PRUEFEN.
     *
     * KEINE `data class`, und das ist Absicht: deren `copy()` waere trotz
     * privaten Konstruktors oeffentlich und damit ein zweiter Bauweg.
     */
    class AdjustedInterval private constructor(
        val fromSourceTs: Long,
        val toSourceTs: Long,
        val deltaMgdl: Double,
    ) {

        override fun toString() = "AdjustedInterval($fromSourceTs->$toSourceTs, $deltaMgdl)"

        companion object {

            /**
             * @param serie die Ausgabe EINES [adjust]-Aufrufs.
             * @param ankerTs `lastAcceptedTs` des Bestands.
             * @return `null`, wenn der Anker nicht in dieser Ausgabe liegt oder
             *   kein juengerer Punkt existiert - dann gibt es kein Intervall,
             *   und der Verbraucher setzt nur die Basis neu.
             */
            fun of(serie: AdjustedSeries, ankerTs: Long): AdjustedInterval? {
                val letzter = serie.points.lastOrNull() ?: return null
                val anker = serie.points.firstOrNull { it.sourceTs == ankerTs } ?: return null
                if (letzter.sourceTs <= anker.sourceTs) return null
                val delta = letzter.adjusted - anker.adjusted
                if (!delta.isFinite()) return null
                return AdjustedInterval(anker.sourceTs, letzter.sourceTs, delta)
            }
        }
    }

    /**
     * Kontraktgrenze aus dem KDoc von [adjust]: dt darueber ist ein
     * Segmentbruch und beendet das R-Segment.
     *
     * NUR NOCH DER VORGABEWERT. Die wirksame Grenze kommt seit 25.08. aus
     * einer INJIZIERTEN [GapPolicy] - sie stand vorher an drei Orten
     * (einer davon ein unabhaengiges Literal im Observer), und ein
     * globaler Schalter haette Runner und Tests im selben Prozess
     * denselben Wert teilen lassen.
     */
    const val SEGMENT_BREAK_MS: Long = GapPolicy.DEFAULT_R_SEGMENT_BREAK_MS

    /**
     * Audit R95 NEU-03: die Brucherkennung war "Sache des Aufrufers" - und der
     * Live-Aufrufer hat sie nicht geleistet. Nach einer CGM-Luecke ueberspannte
     * der Theil-Sen die Luecke (Rechteck-BGI ueber die volle Fehlzeit,
     * verschmolzene Messregime) und war eine Minute spaeter wieder dosierfaehig.
     *
     * Liefert den Beginn des JUENGSTEN lueckenfreien Segments: den Zeitstempel
     * des ersten Punkts nach dem letzten Bruch mit dt > [SEGMENT_BREAK_MS]
     * innerhalb des betrachteten Fensters - oder [windowStartTs], wenn das
     * Fenster bruchfrei ist. r existiert damit nach einem Bruch erst wieder,
     * wenn das NEUE Segment genug Punkte traegt (Spec v0.5 Abs. 4.2).
     */
    fun segmentStart(
        ascendingTs: List<Long>,
        windowStartTs: Long,
        policy: GapPolicy = GapPolicy.PRODUCTION,
    ): Long {
        for (i in ascendingTs.size - 1 downTo 1) {
            if (ascendingTs[i - 1] < windowStartTs && ascendingTs[i] < windowStartTs) break
            if (ascendingTs[i] - ascendingTs[i - 1] > policy.rSegmentBreakMs) {
                return maxOf(windowStartTs, ascendingTs[i])
            }
        }
        return windowStartTs
    }

    /**
     * Baut die BGI-bereinigte Reihe fuer EIN R-Segment. Die Eingabe muss streng
     * aufsteigend und frei von Segmentbruechen sein — Bruecherkennung (dt > 3 min,
     * Epochen, Input-Step) ist Sache des Aufrufers, nicht dieser Rechnung.
     *
     * Integrationsregel (gelockt): bgiIncrement = bgiRate(t) * dtMinutes mit dem
     * RECHTEN Wert; das erste Sample des Segments traegt cumulativeBgi = 0.
     */
    fun adjust(segment: List<Sample>): AdjustedSeries = AdjustedSeries.fromSegment(segment)

    /**
     * Theil-Sen-Steigung [mg/dl/min] ueber die letzten [WINDOW_MS] der Reihe,
     * mit den drei gelockten Mindestbedingungen. null = R NICHT BERECHENBAR
     * (Spec: rSigned = null + DEGRADED(INSUFFICIENT_SIGNAL_HISTORY)) —
     * ausdruecklich KEIN 0.0, das waere die Behauptung "flach".
     */
    fun theilSen(
        points: List<AdjustedPoint>,
        nowTs: Long,
        maturity: MaturityPolicy = MaturityPolicy.PRODUCTION,
    ): Double? = pairSlopes(points, nowTs, maturity = maturity)?.let { median(it) }

    /**
     * Die SORTIERTE Liste der paarweisen Steigungen [mg/dl/min] — die
     * Zwischengroesse, aus der der Median oben ein einzelner Rang ist.
     *
     * Herausgezogen, NICHT veraendert: gleiche Schleife, gleiche
     * Mindestbedingungen, gleicher Container (`ArrayList<Double>`, damit auch
     * dieselbe Sortierung greift). Der Kandidat bleibt damit woertlich
     * derselbe.
     *
     * `internal`, weil sie ein Zwischenschritt ist und kein Ergebnis: eine
     * Untergrenze DARAUS zu bilden ist Policy und gehoert nach
     * [PairSlopeBand], nicht in diese gelockte Datei.
     */
    internal fun pairSlopes(
        points: List<AdjustedPoint>,
        nowTs: Long,
        windowMs: Long = WINDOW_MS,
        maturity: MaturityPolicy = MaturityPolicy.PRODUCTION,
    ): ArrayList<Double>? {
        // `windowMs` mit Default = WINDOW_MS: die Produktion ruft ohne
        // Argument und bleibt bitgleich (der gelockte Kandidat aendert sich
        // nicht). Der Parameter existiert fuer den OFFLINE-Fenster-Replay
        // (Phase 2, Toni/Codex 23.08.) - kein Produktionspfad setzt ihn.
        //
        // `maturity` ebenso, seit 25.08. abends (Reife-Replay): die
        // GELOCKTEN Konstanten [MIN_POINTS]/[MIN_SLOPES] bleiben stehen und
        // sind der Vorgabewert. Der Replay injiziert eine andere Politik JE
        // RUNNER; ein prozessweiter Schalter haette Laeufe im selben
        // Prozess vermischt (dieselbe Lehre wie bei [GapPolicy]).
        //
        // WICHTIG FUER DIE AUSWERTUNG: die Politik entscheidet nur, OB ein
        // Wert herauskommt - nicht, aus welchen Punkten. Sobald beide
        // Politiken reif sind, rechnen sie auf demselben Fenster und
        // liefern denselben Median. Unterschiede sind damit strikt auf die
        // Zyklen beschraenkt, in denen die Produktion noch blind ist.
        val window = points.filter { nowTs - it.sourceTs <= windowMs && it.sourceTs <= nowTs }
        if (window.size < maturity.minPointsAt(nowTs)) return null
        val slopes = ArrayList<Double>()
        for (i in window.indices) for (j in i + 1 until window.size) {
            val dtMs = window[j].sourceTs - window[i].sourceTs
            if (dtMs >= PAIR_DT_MIN_MS) {
                slopes.add((window[j].adjusted - window[i].adjusted) / (dtMs / 60_000.0))
            }
        }
        if (slopes.size < maturity.minSlopesAt(nowTs)) return null
        slopes.sort()
        return slopes
    }

    /**
     * DER REIFESTAND - dieselbe Fensterwahl und dieselbe Paarschleife wie
     * [pairSlopes], nur ohne Abbruch (Bauauftrag Toni 25.08. abends).
     *
     * ES MUSS DIESELBE RECHNUNG SEIN. Eine zweite, nachgebaute Zaehlung
     * waere genau der Fehler, den die Anzeige heute schon macht: sie
     * zaehlt `samplesUsed` im festen 18-min-Fenster, waehrend der Regler
     * auf das eingestellte Fenster filtert - und zeigt damit Reife an,
     * wo der Regler weiter abbricht.
     *
     * @param lastGapMs juengste Luecke im betrachteten Fenster, fuer die
     *   Unterscheidung "baut gerade auf" gegen "gerade gebrochen".
     */
    fun readiness(
        points: List<AdjustedPoint>,
        nowTs: Long,
        windowMs: Long = WINDOW_MS,
        lastGapMs: Long? = null,
        policy: GapPolicy = GapPolicy.PRODUCTION,
        maturity: MaturityPolicy = MaturityPolicy.PRODUCTION,
    ): SignalReadiness {
        val window = points.filter { nowTs - it.sourceTs <= windowMs && it.sourceTs <= nowTs }
        var slopes = 0
        for (i in window.indices) for (j in i + 1 until window.size) {
            if (window[j].sourceTs - window[i].sourceTs >= PAIR_DT_MIN_MS) slopes++
        }
        val grund = when {
            lastGapMs != null && lastGapMs > policy.rSegmentBreakMs &&
                window.size < maturity.minPointsAt(nowTs) -> SignalReadiness.Reason.GAP_RESET

            window.size < maturity.minPointsAt(nowTs) -> SignalReadiness.Reason.TOO_FEW_POINTS
            slopes < maturity.minSlopesAt(nowTs)      -> SignalReadiness.Reason.TOO_FEW_SLOPES
            else                                      -> SignalReadiness.Reason.READY
        }
        return SignalReadiness(
            points = window.size, pointsRequired = maturity.minPointsAt(nowTs),
            slopes = slopes, slopesRequired = maturity.minSlopesAt(nowTs),
            windowMin = (windowMs / 60_000L).toInt(),
            lastGapMs = lastGapMs,
            breakMs = policy.rSegmentBreakMs,
            reason = grund,
        )
    }

    /** Median einer bereits SORTIERTEN Liste — exakt die Zeilen, die vorher am
     *  Ende von [theilSen] standen. */
    internal fun median(sorted: List<Double>): Double {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /**
     * Minutenbin fuer die Outcome-Coverage (Addendum A6): half-up ueber
     * floor(x + 0.5) — bewusst NICHT round(), dessen .5-Verhalten
     * sprachabhaengig ist. Exakt +30 s faellt in den SPAETEREN Bin.
     * null bei negativer Distanz oder Bin ausserhalb 0..maxBin.
     */
    fun minuteBin(sourceTs: Long, anchorTs: Long, maxBin: Int): Int? {
        val delta = sourceTs - anchorTs
        if (delta < 0) return null
        val bin = Math.floor(delta / 60_000.0 + 0.5).toInt()
        return if (bin in 0..maxBin) bin else null
    }
}

package app.aaps.fuse.plugin

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.utils.MidnightUtils
import app.aaps.fuse.core.observer.ActivityValidity
import app.aaps.fuse.core.signal.BgiAdjustedSeries
import app.aaps.fuse.core.signal.SignalWindow
import app.aaps.fuse.core.signal.UkfQ1

/**
 * Die beiden Signalgroessen, die der Observer bereits GERECHNET erwartet:
 * `q1` und `rSigned`.
 *
 * Zwei Fallen stecken hier, beide im Quellcode nachgeprueft:
 *
 * 1. DIE AKTIVITAET KOMMT NICHT AUS DEM IOB-ARRAY.
 *    `calculateIobArrayForSMB` liefert ausschliesslich ZUKUNFT
 *    (`val t = now + i * 5 * 60000`), [BgiAdjustedSeries] braucht dagegen die
 *    letzten 18 Minuten. Wer das Array nimmt, bekommt entweder keine Werte oder
 *    `ActivityValidity.FUTURE` — und der Observer verlaesst WARMUP nie.
 *    Die einzige oeffentliche Methode mit freiem Zeitstempel ist
 *    `calculateFromTreatmentsAndTemps(toTime, profile)`; ihr internes Raster ist
 *    bereits die Minute (`roundUpTime` rundet auf 60_000 ms auf).
 *
 * 2. DIE ROHREIHE IST NEUESTE-ZUERST.
 *    `getBgReadingsDataTableCopy()` liefert absteigend, `BgiAdjustedSeries.adjust`
 *    WIRFT bei absteigender Reihenfolge (`require(dtMin >= 0.0)`). Deshalb wird
 *    hier einmal explizit aufsteigend sortiert statt sich auf eine Annahme zu
 *    verlassen.
 *
 * KOSTEN, ehrlich benannt: ein Cache-Miss in `calculateFromTreatmentsAndTemps`
 * kostet drei blockierende Room-Abfragen ueber ein DIA-breites Fenster. Bei
 * 1-min-CGM entwertet praktisch jede Minute den Cache der letzten Minuten, es
 * bleiben also einige echte Misses je Zyklus. Das Fenster ist deshalb genau
 * [BgiAdjustedSeries.WINDOW_MS] breit und keine Minute mehr.
 */
class FuseSignalSource(
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction,
    /** Die Segmentgrenze dieses Laufs - injiziert, nicht global. Ein
     *  Replay erzeugt je Variante eine eigene Quelle. */
    private val gapPolicy: app.aaps.fuse.core.signal.GapPolicy =
        app.aaps.fuse.core.signal.GapPolicy.PRODUCTION,
    /** Die Reifebedingung dieses Laufs - ebenfalls injiziert. Sie muss
     *  DIESELBE sein wie die des Runners, sonst traegt der Trail ein
     *  rSigned, das der Regler nicht hatte (oder umgekehrt). */
    private val maturity: app.aaps.fuse.core.signal.MaturityPolicy =
        app.aaps.fuse.core.signal.MaturityPolicy.PRODUCTION,
) {

    /**
     * Zustand der stabilen Signalepoche - MONOTON: `maxOf` mit jedem neuen
     * Bruchkandidaten. Die Monotonie traegt zwei Robustheiten: (a) faellt ein
     * alter Bruch aus dem rollenden Puffer, bleibt die Epoche stehen statt
     * zurueckzuspringen; (b) eine Grenze, die mehrere Zyklen lang sichtbar
     * ist (Input-Sprung im Puffer), setzt die Epoche genau einmal.
     * Prozesslokal mit Absicht - s. [Signal.signalEpochTs].
     */
    private var signalEpochTs = 0L

    /** Segment, auf das sich [vollreifeTs] bezieht - wechselt es, faellt
     *  die Vollreife zurueck. */
    private var vollreifeSegmentTs = 0L

    /** Wann DIESES Segment erstmals die STRENGE Reife trug (0 = noch
     *  nicht). Exportauflage Toni 25.08.: nur daran ist ablesbar, wie
     *  lange der Wiedereinstieg ueberhaupt etwas geaendert hat. */
    private var vollreifeTs = 0L

    data class Signal(
        val sourceTs: Long,
        /** Der KALIBRIERTE ROHWERT am Anker, ungefiltert. Die Sprungerkennung
         *  des Observers laeuft darauf: ein Kalibriersprung soll gesehen werden,
         *  bevor q1 ihn glaettet. */
        val rawBg: Double,
        val q1: Double,
        /** `null` heisst: nicht berechenbar (zu wenige Punkte/Paare) — NICHT 0.
         *
         *  TRAEGHEIT, gemessen: nach einem Steigungssprung folgt der Median mit
         *  0 % bis Minute 5, 50 % bei Minute 9, 100 % ab Minute 13. Theil-Sen
         *  ist robust gegen Rauschen — am Mahlzeiten-Onset heisst Robustheit
         *  Langsamkeit. Deshalb stehen daneben zwei schnellere Maasse. */
        val rSigned: Double?,
        /**
         * Die EIGENE Ratenschaetzung des Filters (`UkfQ1.Result.ratePerMin`).
         *
         * Sie wurde bisher weggeworfen, obwohl der Filter sie in jedem Zyklus
         * mitrechnet — ein Kalman-Zustand, kein Batch-Median, und damit
         * konstruktionsbedingt schneller als [rSigned]. Kostet nichts.
         */
        val ukfRatePerMin: Double,
        /**
         * Steigung der ROHEN Reihe ueber die letzten Minuten — das naivste und
         * schnellste Maass, und ungefaehr das, womit autoISF ueber `delta`
         * arbeitet. Es ist hier NICHT im Regelpfad; es steht da, damit
         * messbar wird, wieviel Vorsprung ein kurzes Fenster wirklich hat.
         */
        val rawSlopePerMin: Double?,
        /** Das vom UKF GELERNTE Messrauschen R - die einzige eingebaute
         *  Signalqualitaetszahl. Bisher nirgends exportiert; eine spaetere
         *  Qualitaets-Schranke braucht genau diese Reihe zum Kalibrieren. */
        val ukfLearnedR: Double,
        /**
         * Insulinaktivitaet und ISF AM ANKER — sie werden gebraucht, um eine
         * rohe Rate BGI-zu-bereinigen:
         *
         *     bereinigt = roh + activity * isf
         *
         * `rSigned` ist bereinigt, [ukfRatePerMin] und [rawSlopePerMin] sind es
         * NICHT. Wer eine der beiden ungefiltert als Antrieb einsetzt, laesst
         * `TrajectoryCore` die Insulinwirkung ein ZWEITES Mal abziehen.
         */
        val activityAtAnchor: Double,
        val isfAtAnchor: Double,
        /** Die BGI-korrigierte Reihe des Fensters. Sie wird durchgereicht, damit
         *  der Zyklus die Untergrenze mit dem eingestellten Quantil bilden kann,
         *  OHNE dass die Signalquelle Preferences liest — die Fensterbildung
         *  bleibt so eine Sache, die Bandpolitik eine andere. */
        val adjusted: BgiAdjustedSeries.AdjustedSeries,
        val activity: ActivityValidity,
        val samplesUsed: Int,
        val rawSeriesSize: Int,
        /**
         * POST-GAP-TELEMETRIE (11.08.) - sechs Zahlen, die zusammen eine
         * Frage beantworten, die keine von ihnen allein beantwortet:
         * "war der erste Punkt nach einer Luecke fragwuerdig, und wurde er
         * kurz darauf stark revidiert?"
         *
         * ANLASS: am 10.08. stand nach einer 37-min-Luecke ein Wert von 90
         * mit FRISCHEM Zeitstempel im Datensatz, drei Minuten spaeter 105.
         * FUSE las daraus +4,21 mg/dl/min und gab 0,85 U in ein Ereignis,
         * das es nicht gab.
         *
         * WARUM NICHT EINFACH EINE RATE: der erste Punkt nach der Luecke
         * hat gar keine auffaellige Rate - (90-105)/35 min = -0,43. Der
         * SPRUNG kommt drei Minuten spaeter, und dann ist die Luecke schon
         * nicht mehr frisch. Wer nur Rate ODER Luecke misst, sieht den Fall
         * nie. Es braucht den ABSTAND zur Luecke (postGapIndex) zusammen
         * mit dem SCHRITT.
         *
         * KEINE REGEL, KEINE SCHWELLE. Tonis eigener Messwert steht dagegen:
         * 4,85 mg/dl/min im Mahlzeitenkopf - ein Plausibilitaetszaun bei 5
         * haette keinen Abstand. Ob daraus je etwas wird, entscheiden Daten.
         */
        /** Minuten zwischen dem vorletzten und dem letzten Rohwert. 0 = kein
         *  Abstand messbar (nur ein Punkt). */
        val gapBeforeMin: Double,
        /** Absoluter Schritt zum Vorgaenger [mg/dl] - VORZEICHENBEHAFTET, weil
         *  ein Ruecksprung nach oben etwas anderes ist als einer nach unten. */
        val stepFromLastMgdl: Double,
        /** Derselbe Schritt als Rate ueber den TATSAECHLICHEN Zeitabstand.
         *  Bei einer Luecke ist das etwas ganz anderes als "pro Minute". */
        val stepRateActualMgdlPerMin: Double,
        /** Der wievielte Punkt seit dem letzten Segmentbruch. 1 = der erste
         *  nach der Luecke, also der verdaechtige. */
        val postGapIndex: Int,
        // sourceAgeMin gibt es hier NICHT: die Signalquelle hat keine Uhr, und
        // eine hineinzureichen waere eine Zeitquelle mehr im Kern. Das Alter
        // rechnet der Export aus `computeTs - sourceTs` - beide stehen schon
        // in jedem Datensatz.
        val q1Outlier: Boolean,
        /** Was den Reihenanfang gesetzt hat: NONE / SENSOR_CHANGE /
         *  CALIBRATION_START. Gehoert in den Export - ein q1 aus einem frisch
         *  begrenzten Fenster ist eine andere Zahl als eines aus voller
         *  Historie, und das darf man hinterher nicht raten muessen. */
        val boundedBy: SignalWindow.Bound,
        val windowFromTs: Long,
        /** Beginn des juengsten LUECKENFREIEN Segments - Bezugspunkt der
         *  BGI-Bereinigung (die Rechnung selbst nutzt die lokale Variable in
         *  [read]; seit dem 22.08. hat dieses FELD keinen Produktions-Leser
         *  mehr - der fruehere Evidenzkern-Bezug ist am 12.08. entfallen,
         *  die Ledger-Identitaet liest [signalEpochTs]). Es bleibt fuer
         *  Tests und als dokumentierte GEGENGROESSE: wer hier je wieder eine
         *  IDENTITAET anschliessen will, lese zuerst das KDoc von
         *  [signalEpochTs] - diese Kante wandert mit jedem CGM-Wert. */
        val segmentStartTs: Long,
        /**
         * STABILE SIGNALEPOCHE (Toni 22.08.) - die Segment-IDENTITAET des
         * Erwartungs-Ledgers. Sie wechselt NUR bei einem echten Bruch:
         * CGM-Luecke > 3 min, Sensor-/Kalibrierepoche oder Input-Sprung.
         *
         * NEUSTART IST EINE HEURISTIK, KEINE GARANTIE (Review + Toni
         * 22.08.): der frische Prozess leitet die Epoche aus dem CGM-Puffer
         * neu ab. DIESELBE Epoche entsteht nur, solange der urspruengliche
         * Epochenanfang (Bruchzeitpunkt oder Reihenbeginn) noch im rollenden
         * Puffer liegt - dann koennen Eintraege von davor gegen Proben von
         * danach abgerechnet werden, und das ist gewollt: die lueckenlose
         * Reihe BEWEIST die Vergleichbarkeit (q1/Theil-Sen sind reine
         * Funktionen des Puffers). Ist der Anfang dagegen bereits
         * herausgerollt, beginnt der neue Prozess mit dem aeltesten
         * VERBLIEBENEN Wert - eine NEUE Epoche, obwohl die Reihe lueckenlos
         * ist; alte Erwartungen enden dann konservativ UNVERIFIABLE. Ein
         * Neustart mit > 3 min Ausfall muenzt immer eine neue Epoche, weil
         * die Wiederaufnahme selbst der Bruch ist. Wer offline "Neustart =
         * immer neue Epoche" ODER "lueckenlos = immer dieselbe" annimmt,
         * liest den Trail falsch. Monotonie gilt JE INSTANZ, nicht global.
         *
         * AUSDRUECKLICH NICHT [segmentStartTs]: der ist im lueckenfreien
         * Normalfall die GLEITENDE Unterkante des 18-min-Fensters und wandert
         * mit jedem CGM-Wert. Als Identitaet ueber den 120-min-Horizont kann
         * er sich per Konstruktion nie selbst wiedertreffen - alle 1091
         * Outcomes des ersten Messlaufs waren deshalb UNVERIFIABLE. Fuer die
         * BGI-Bereinigung bleibt er richtig; fuer Identitaet gilt diese
         * Epoche.
         */
        val signalEpochTs: Long,
        /**
         * DIE GEWAEHLTE REIFE DIESES ZYKLUS samt Begruendung. Der Runner
         * gibt genau dieses Objekt an `PairSlopeBand.estimate` weiter -
         * es gibt keine zweite Auswahl.
         */
        val rejoin: app.aaps.fuse.core.signal.SignalRejoin.Selection,
        /** Wann dieses Segment erstmals die STRENGE Reife trug, 0 = noch nicht. */
        val fullMaturityTs: Long,
    )

    sealed interface Outcome {

        data class Ok(val signal: Signal) : Outcome
        /** Kein Ersatzwert, kein Nullwert — ein benannter Grund. */
        data class Unavailable(val reason: String) : Outcome
    }

    /**
     * @param sensorStartTs Beginn der Sensorlaufzeit, `<= 0` = unbekannt.
     * @param calibrationStartTs Beginn der Kalibrierung, `<= 0` = unbekannt.
     */
    companion object {

        /** Fenster des Rohvergleichsmaasses. 5 min, weil das die Groesse ist,
         *  mit der AAPS' `delta` arbeitet — der Vergleich soll fair sein. */
        const val RAW_SLOPE_WINDOW_MS = 5 * 60_000L
    }

    /** Einfache Sekante ueber das Rohfenster. `null` bei zu wenig Abstand —
     *  kein Ersatzwert. */
    private fun rawSlope(points: List<UkfQ1.Point>, nowTs: Long): Double? {
        val from = nowTs - RAW_SLOPE_WINDOW_MS
        val first = points.firstOrNull { it.tsMs >= from } ?: return null
        val dtMin = (nowTs - first.tsMs) / 60_000.0
        if (dtMin < 2.0) return null
        return (points.last().value - first.value) / dtMin
    }

    /**
     * @param rejoin der Wiedereinstieg nach Funkluecke. Wird JE ZYKLUS
     *   uebergeben, damit der Schalter am Geraet wirkt, ohne dass irgendwo
     *   ein veraenderlicher Zustand entsteht - der Wert selbst ist
     *   unveraenderlich, und `OFF` ist der Vorgabewert jedes Pfades.
     */
    fun read(
        sensorStartTs: Long,
        calibrationStartTs: Long,
        rejoin: app.aaps.fuse.core.signal.RejoinPolicy = app.aaps.fuse.core.signal.RejoinPolicy.OFF,
    ): Outcome {
        // Aufsteigend, und nur kalibrierte Rohwerte: `raw` ist der Eingang, den
        // auch der Fork-Q1 nutzt. `value` waere der (moeglicherweise schon
        // geglaettete) Anzeigewert, `noise` ist entgegen dem Namen der
        // huckepack transportierte UNKALIBRIERTE Sensorwert — beides waere hier
        // falsch.
        val readings = iobCobCalculator.ads.getBgReadingsDataTableCopy()
            .asSequence()
            .mapNotNull { gv -> gv.raw?.takeIf { it > 39.0 }?.let { UkfQ1.Point(gv.timestamp, it) } }
            .sortedBy { it.tsMs }
            .toList()
        if (readings.isEmpty()) return Outcome.Unavailable("no raw glucose values")

        val newest = readings.last()
        val sourceTs = newest.tsMs

        // R60-F1: die Reihe beginnt am Regimewechsel, nicht am Datenanfang.
        // EINMAL vorne angewandt und nicht in der Praefixschleife - das ist
        // wirkungsgleich und kann nicht in einem der ~19 Durchlaeufe vergessen
        // werden: jedes Praefix ist ein Suffix der aufsteigenden Reihe, also gilt
        //   praefix(beschnitten) = praefix(voll) geschnitten mit {ts >= fromTs}.
        val window = SignalWindow.of(sourceTs, sensorStartTs, calibrationStartTs)
        val epochTrimmed = readings.filter { it.tsMs >= window.fromTs }
        if (epochTrimmed.isEmpty()) return Outcome.Unavailable("window empty after ${window.label} @${window.fromTs}")

        // C9-01 (Codex Fix-Pass-5-Closure): der Sprungzaun gilt fuer die GANZE
        // Reihe, nicht nur fuer die Zustandsmaschine. Auf der bereits an
        // Sensor-/Kalibrierepoche beschnittenen Reihe gesucht - was eine
        // gemeldete Epoche erklaert, ist hier schon weg und wird nicht ein
        // zweites Mal als "unklassifiziert" gezaehlt.
        //
        // Der Schnitt sitzt VOR q1: haenge ich ihn nur an das r-Fenster, sieht
        // der UKF den Sprung weiterhin, zieht sein gelerntes R hoch und liefert
        // ein langsam nachgefuehrtes q1 - aus dem dann jedes spaetere r gebaut
        // wird. Kuerzere Reihe heisst hier: q1 faellt bis zur Reife benannt aus
        // (fail-closed), statt eine erfundene Steigung zu tragen.
        val stepTs = SignalWindow.stepBoundaryTs(epochTrimmed)
        val series = if (stepTs != null) epochTrimmed.filter { it.tsMs >= stepTs } else epochTrimmed
        val bound = if (stepTs != null) SignalWindow.Bound.INPUT_STEP else window.bound
        val boundLabel = bound.name
        if (series.isEmpty()) return Outcome.Unavailable("window empty after $boundLabel @${stepTs ?: window.fromTs}")

        val leading = UkfQ1.leadingEdge(series.takeLast(UkfQ1.WINDOW_SAMPLES))
            ?: return Outcome.Unavailable("q1 not computable from ${series.size} points ($boundLabel)")

        // Ein Sample je Rohpunkt im 18-min-Fenster. q1 wird KAUSAL je Punkt
        // gerechnet: jeder Punkt sieht nur seine eigene Vergangenheit.
        // Audit R95 NEU-03: die r-Reihe beginnt im juengsten LUECKENFREIEN
        // Segment (dt > 3 min = Bruch) - vorher ueberspannte der Theil-Sen
        // CGM-Luecken und war eine Minute nach der Luecke wieder dosierfaehig.
        // q1/UKF bleiben unbeschnitten (eigene, dt-bewusste Filterung); nur r
        // faellt bis zur Segmentreife benannt aus ("drive not estimable").
        val windowStart = BgiAdjustedSeries.segmentStart(
            series.map { it.tsMs }, sourceTs - BgiAdjustedSeries.WINDOW_MS, gapPolicy
        )
        val samples = ArrayList<BgiAdjustedSeries.Sample>()
        for ((index, point) in series.withIndex()) {
            if (point.tsMs < windowStart) continue
            val prefix = series.subList(maxOf(0, index + 1 - UkfQ1.WINDOW_SAMPLES), index + 1)
            val q1 = UkfQ1.leadingEdge(prefix)?.glucose ?: continue
            val profile = profileFunction.getProfile(point.tsMs)
                ?: return Outcome.Unavailable("no profile at ${point.tsMs}")
            val isf = profile.getIsfMgdlTimeFromMidnight(MidnightUtils.secondsFromMidnight(point.tsMs))
            if (!isf.isFinite() || isf <= 0.0) return Outcome.Unavailable("isf=$isf at ${point.tsMs}")
            // NUR lesen: der Rueckgabewert kann eine Cache-REFERENZ sein
            // (IobTotal ist eine data class mit var-Feldern). Wer daran
            // schreibt, veraendert den Cache der ganzen App.
            val iobAt = iobCobCalculator.calculateFromTreatmentsAndTemps(point.tsMs, profile)
            // UNBEKANNT IST NICHT NULL (Codex Combined Closure b6dbb490, P0):
            // ohne aktuelles Profil liefert der Rechner ein genulltes, aber
            // ENDLICHES Objekt. Eine erfundene Aktivitaet von 0 macht die
            // BGI-Bereinigung falsch - und zwar in beide Richtungen, je
            // nachdem ob Bolus- oder Basalanteil fehlt. Der Wert selbst
            // verraet das nie; nur das Gueltigkeitsmerkmal tut es.
            if (!iobAt.valid) return Outcome.Unavailable("iob unknown at ${point.tsMs} (no profile)")
            val activity = iobAt.activity
            if (!activity.isFinite()) return Outcome.Unavailable("activity not finite at ${point.tsMs}")
            samples.add(BgiAdjustedSeries.Sample(point.tsMs, q1, activity, isf))
        }
        if (samples.isEmpty()) return Outcome.Unavailable("no samples in window ($boundLabel)")

        // POST-GAP-TELEMETRIE. Gerechnet auf der ROHREIHE (`series`), nicht
        // auf den Samples: die Samples sind bereits auf das lueckenfreie
        // Segment beschnitten, und genau der Punkt VOR dem Schnitt ist der,
        // um den es geht.
        // Die Rechnung liegt in `PostGapMetrics` - hier ist sie an
        // profileFunction/iobCobCalculator gefesselt und praktisch nicht
        // pruefbar, dort braucht sie nichts als Zeitstempel und Werte.
        val postGap = app.aaps.fuse.core.signal.PostGapMetrics.of(
            ts = series.map { it.tsMs },
            values = series.map { it.value },
            segmentStartTs = windowStart,
        )

        val adjusted = BgiAdjustedSeries.adjust(samples)

        // DIE EINE AUSWAHL. Sie faellt hier, weil nur hier bekannt ist,
        // WARUM das Segment beginnt - und sie reist im Signal weiter, damit
        // der zweite Verbraucher (PairSlopeBand im Runner) exakt dieselbe
        // liest. Zwei unabhaengige Auswahlen waeren der Fehler, den die
        // Gap-Grenze schon einmal hatte: der Trail truege ein r, das der
        // Regler nicht hatte.
        //
        // DIE REGIMEGRENZE ALS (URSACHE, ZEITPUNKT), nicht als blosses
        // Enum (Review Toni 25.08. abends): `bound` allein beantwortet
        // "warum beginnt das 180-min-Fenster?", der Wiedereinstieg
        // braucht "warum beginnt das aktuelle Segment?". Ohne den
        // Zeitpunkt sperrte eine zwei Stunden alte Kalibrierung auch
        // einen spaeteren, voellig eigenstaendigen Funkabriss.
        //
        // ZUM ZEITSTEMPEL, EHRLICH GEMESSEN: auf DIESEM Pfad klemmt er
        // nichts, denn `series` ist oben bereits an genau dieser Grenze
        // beschnitten (epochTrimmed bzw. der Schnitt bei `stepTs`). Eine
        // Mutation, die ihn auf 0 setzt, bleibt hier folgerichtig gruen.
        // Er steht trotzdem hier, aus zwei Gruenden: er GEHT IN DEN
        // EXPORT (ohne ihn liesse sich im Trail nicht unterscheiden, ob
        // eine Kalibrierung den aktuellen Segmentbeginn erklaert oder
        // bloss im Puffer liegt), und `SignalRejoin.select` verlaesst
        // sich NICHT auf die Beschneidung des Aufrufers - dort ist die
        // Klemme geprueft und mutationsfest (s. SignalRejoinTest,
        // "punkte vor der regimegrenze zaehlen nicht als etablierung").
        val regime = if (stepTs != null)
            app.aaps.fuse.core.signal.SignalRejoin.Regime.of(
                app.aaps.fuse.core.signal.SignalWindow.Bound.INPUT_STEP,
                boundaryTs = stepTs,
                segmentStartTs = series.first().tsMs,
            )
        else if (window.bound != app.aaps.fuse.core.signal.SignalWindow.Bound.NONE)
            app.aaps.fuse.core.signal.SignalRejoin.Regime.of(
                window.bound,
                boundaryTs = window.fromTs,
                segmentStartTs = series.first().tsMs,
            )
        else app.aaps.fuse.core.signal.SignalRejoin.Regime.NONE
        val auswahl = app.aaps.fuse.core.signal.SignalRejoin.select(
            policy = rejoin,
            base = maturity,
            ascendingTs = series.map { it.tsMs },
            segmentStartTs = windowStart,
            regime = regime,
            nowTs = sourceTs,
            gapPolicy = gapPolicy,
        )
        val rSigned = BgiAdjustedSeries.theilSen(adjusted.points, sourceTs, auswahl.maturity)

        // ZEITPUNKT DER VOLLREIFE (Exportauflage Toni): wann traegt DIESES
        // Segment die strenge Reife? Solange die Lockerung wirkt, steht hier
        // 0 - und genau daran ist im Trail ablesbar, wie lange der
        // Wiedereinstieg ueberhaupt etwas geaendert hat.
        if (windowStart != vollreifeSegmentTs) {
            vollreifeSegmentTs = windowStart
            vollreifeTs = 0L
        }
        if (vollreifeTs == 0L &&
            BgiAdjustedSeries.theilSen(adjusted.points, sourceTs) != null
        ) vollreifeTs = sourceTs

        // ---- STABILE SIGNALEPOCHE (Toni 22.08.) -------------------------
        // Bruchkandidaten dieses Zyklus: eine explizite Fenstergrenze
        // (Sensor-/Kalibrierepoche oder Input-Sprung: dann BEGINNT die Reihe
        // an der Grenze) und die juengste CGM-Luecke > 3 min im Puffer. Die
        // rollende Pufferkante (bound == NONE) ist ausdruecklich KEIN Bruch.
        val epochBoundaryTs = if (bound != SignalWindow.Bound.NONE) series.first().tsMs else 0L
        val gapBreakTs = run {
            val ts = series.map { it.tsMs }
            var found = 0L
            for (i in ts.size - 1 downTo 1) {
                if (ts[i] - ts[i - 1] > gapPolicy.rSegmentBreakMs) { found = ts[i]; break }
            }
            found
        }
        if (signalEpochTs == 0L) signalEpochTs = series.first().tsMs
        signalEpochTs = maxOf(signalEpochTs, epochBoundaryTs, gapBreakTs)

        return Outcome.Ok(
            Signal(
                sourceTs = sourceTs,
                rawBg = newest.value,
                q1 = leading.glucose,
                rSigned = rSigned,
                // C1b (Codex-Adjudication): nach einem Segmentbruch traegt der
                // erste Punkt KEINE Rate. NaN statt der frueheren 0.0 - alle
                // Konsumenten pruefen bereits isFinite() und werden dadurch
                // fail-closed (Onset-Sample entfaellt, aktivierte Bremsbahn
                // bricht den Zyklus ab), statt eine erfundene Null zu glauben.
                ukfRatePerMin = if (leading.rateUnavailable) Double.NaN else leading.ratePerMin,
                ukfLearnedR = leading.learnedR,
                activityAtAnchor = samples.last().activity,
                isfAtAnchor = samples.last().profileIsf,
                rawSlopePerMin = rawSlope(readings, sourceTs),
                adjusted = adjusted,
                // Die Aktivitaet wurde AM Zeitpunkt selbst gerechnet, nicht per
                // LOCF uebernommen — sie ist damit definitionsgemaess kausal
                // und aktuell.
                activity = ActivityValidity.VALID,
                samplesUsed = samples.size,
                rawSeriesSize = series.size,
                gapBeforeMin = postGap.gapBeforeMin,
                stepFromLastMgdl = postGap.stepFromLastMgdl,
                stepRateActualMgdlPerMin = postGap.stepRateActualMgdlPerMin,
                postGapIndex = postGap.postGapIndex,
                q1Outlier = leading.outlier,
                boundedBy = bound,
                windowFromTs = window.fromTs,
                segmentStartTs = windowStart,
                signalEpochTs = signalEpochTs,
                rejoin = auswahl,
                fullMaturityTs = vollreifeTs,
            )
        )
    }
}

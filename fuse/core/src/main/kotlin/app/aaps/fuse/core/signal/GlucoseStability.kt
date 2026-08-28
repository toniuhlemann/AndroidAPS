package app.aaps.fuse.core.signal

import kotlin.math.abs

/**
 * IST DIE GEMESSENE GLUKOSELAGE GERADE STABIL?
 *
 * DER ANLASS (Toni 28.08.). Der autorisierte Mahlzeiten-Sofortanteil wartete
 * bei praktisch flachem Zucker minutenlang, weil zwei NULLTOLERANZEN ihn
 * hielten: `ukfRatePerMin < 0` und "q1 ist gegenueber dem Vorzyklus gefallen".
 * Am Fruehstueck des 28.08. lag q1 von 09:22 bis 09:32 zwischen 94,3 und 95,5
 * mg/dl - flacher geht kaum -, aber die Filterrate blieb knapp negativ
 * (zuletzt -0,0133) und q1 wackelte um 0,1 bis 0,3. Vier autorisierte
 * Einheiten lagen still.
 *
 * DIE TOLERANZ WAECHST MIT DER INTERVALLAENGE. Das ist der Kern, und er ist
 * aus ZWEI widerlegten Entwuerfen entstanden - beide Male derselbe Fehler,
 * die Intervallaenge als SCHALTER statt als MASSSTAB zu behandeln:
 *
 *   Entwurf 1 verbot jedem Abschnitt ab 2 Minuten eine Rate unter
 *   -0,1 mg/dl/min. Toni rechnete nach: der Schritt 95 -> 94 zwischen 09:27
 *   und 09:29 ergibt ueber zwei Minuten -0,49 und haette bis 09:37 gesperrt,
 *   also LAENGER als der Zustand vorher. Zu streng.
 *
 *   Entwurf 2 ueberging Abschnitte unter 5 Minuten ganz. Toni rechnete
 *   wieder nach: die Reihe 100..116 mit anschliessendem Sturz 116 -> 108 in
 *   zwei Minuten meldete STABLE, weil der schlechteste BERUECKSICHTIGTE
 *   Vergleich nur 110 -> 108 war. Ein frisch begonnener Abfall von 8 mg/dl
 *   blieb unsichtbar. Zu lax - und ein falscher Gegenwartsnachweis.
 *
 * Die Loesung braucht keinen Schalter: ein Abschnitt der Dauer dt darf um
 * [Params.noiseAllowanceMgdl] plus [Params.driftMgdlPerMin] mal dt fallen.
 * Der erste Summand deckt Quantisierung und Zucken - er ist von der Dauer
 * UNABHAENGIG, weil ein Quantisierungsschritt immer 1 mg/dl kostet, egal
 * ueber welche Zeit. Der zweite deckt langsames Driften. Kein Abschnitt wird
 * uebersprungen, keine Nulltoleranz kehrt zurueck.
 *
 * DREI WERTE, NICHT ZWEI (Tonis Auflage). [Verdict.UNDETERMINED] ist ein
 * eigener Ausgang und ausdruecklich KEINE Entwarnung: zu wenige Punkte, eine
 * Luecke, ein Segmentbruch, unbrauchbare Zahlen, veraltete Daten oder ein
 * Parametersatz, unter dem kein einziges Paar beurteilbar waere - alles das
 * heisst "nicht beurteilbar" und muss den Batch genauso halten wie ein
 * gemessener Abfall.
 *
 * WAS DIESE KLASSE IST UND WAS NICHT:
 *  - Sie BESCHREIBT DIE GEGENWART: faellt die gemessene Reihe im Fenster?
 *  - Sie sagt NICHTS ueber die Zukunft voraus. Eine Breitenmessung ueber
 *    fuenf Tage (256 nicht ueberlappende Proben ohne DOKUMENTIERTEN Eingriff)
 *    trennte die gewaehlten Zukunftsklassen nicht ausreichend (65-70 % / 61-69
 *    %). Das ist ein Negativbefund ueber JENE Kennzahlen auf JENEM Material -
 *    keine Aussage, so etwas sei prinzipiell nicht vorhersagbar.
 *  - Sie ist TEIL DER SICHERHEITSENTSCHEIDUNG, nicht bloss eine Vorstufe:
 *    sobald "stabil" einen Mehr-Einheiten-Batch ermoeglicht, traegt sie
 *    Verantwortung mit. Mengenlimits und Risikohorizonte daneben sind
 *    Schutzbedingungen, kein Ersatznachweis (Toni 28.08.).
 *
 * DIE PARAMETER SIND BEGRUENDETE WAHLEN, KEINE ABLEITUNGEN. Aus der
 * Sensoraufloesung folgt KEINE eindeutige zulaessige Abfalltoleranz - sie sagt
 * nur, welche Unterschiede die Messung ueberhaupt aufloest.
 */
object GlucoseStability {

    enum class Verdict {
        /** Kein Abschnitt faellt staerker als die laengenabhaengige Toleranz. */
        STABLE,

        /** Ein Rueckgang ueber der Toleranz - gemessen, nicht gefiltert. */
        FALLING,

        /** NICHT BEURTEILBAR. Keine Entwarnung: der Aufrufer muss halten. */
        UNDETERMINED,
    }

    enum class Reason {
        OK,

        /** Der Rueckgang ueberschreitet die laengenabhaengige Toleranz. */
        DROP_EXCEEDS,

        /** Weniger Punkte im Fenster als [Params.minPoints]. */
        TOO_FEW_POINTS,

        /** Die Punkte decken das Fenster zeitlich nicht ab. */
        SPAN_TOO_SHORT,

        /** Ein Loch INNERHALB des Fensters, groesser als [Params.maxGapMin].
         *  Sechs Punkte bei Minute 0,1,2,3,9,10 sind kein Zehn-Minuten-Beleg,
         *  auch wenn Anzahl und Spanne stimmen. */
        GAP_IN_WINDOW,

        /** Der juengste Punkt ist aelter als [Params.maxAgeMin] - die Reihe
         *  beschreibt nicht mehr die Gegenwart. */
        STALE,

        /** Ein Wert ist nicht endlich oder ein Zeitstempel laeuft rueckwaerts. */
        INVALID_INPUT,

        /** Der Parametersatz laesst kein Urteil zu (z. B. Toleranzen negativ). */
        INVALID_PARAMS,

        /** Der Segmentanker hat gewechselt - andere Reihe als im Vorzyklus. */
        SEGMENT_CHANGED,
    }

    /**
     * @param windowMin Laenge des Beurteilungsfensters. WAHL: bei 5 Minuten
     *   ueberlappten in der Vormessung flache und fallende Verlaeufe, ab 8
     *   trennten sie knapp, bei 10 lag Faktor 2,5 dazwischen. Laenger waere
     *   trennschaerfer und traeger.
     * @param noiseAllowanceMgdl Von der Dauer UNABHAENGIGE Zugabe [mg/dl].
     *   WAHL, KANDIDAT: sie deckt Quantisierung und Zucken. Der Rohwert ist
     *   ganzzahlig (gemessen: 155 von 155 Werten am 28.08.), ein Schritt
     *   kostet also immer genau 1 - unabhaengig davon, ueber welche Zeit er
     *   auftritt. Zwei Schritte sind die Zugabe; das ist eine
     *   Sicherheitsentscheidung und gehoert abgenommen, nicht hergeleitet.
     * @param driftMgdlPerMin Zusaetzlich erlaubter Rueckgang JE MINUTE.
     *   WAHL, KANDIDAT: deckt langsames Driften, das ueber ein langes
     *   Intervall zulaessig bleiben soll, ohne dass ein kurzer Sturz
     *   durchrutscht.
     * @param minPoints Mindestzahl gueltiger Punkte im Fenster.
     * @param maxGapMin Groesstes zugelassenes Loch INNERHALB des Fensters.
     *   Angelehnt an die Segmentbruchgrenze der Reihe (3 min): was dort die
     *   Reihe zerschneidet, darf hier nicht stillschweigend ueberbrueckt
     *   werden.
     * @param maxAgeMin Wie alt der juengste Punkt hoechstens sein darf.
     */
    data class Params(
        val windowMin: Int = 10,
        val noiseAllowanceMgdl: Double = 2.0,
        val driftMgdlPerMin: Double = 0.1,
        val minPoints: Int = 6,
        val maxGapMin: Double = 3.0,
        val maxAgeMin: Double = 3.0,
    ) {

        /** Ein Parametersatz, unter dem kein Urteil moeglich waere, ist selbst
         *  ein Ablehnungsgrund - nicht ein stilles STABLE. */
        val usable: Boolean
            get() = windowMin > 0 && minPoints >= 2 && maxGapMin > 0.0 && maxAgeMin > 0.0 &&
                noiseAllowanceMgdl >= 0.0 && driftMgdlPerMin >= 0.0 &&
                noiseAllowanceMgdl.isFinite() && driftMgdlPerMin.isFinite()

        /** Der zugelassene Rueckgang [mg/dl] fuer ein Intervall der Dauer
         *  [dtMin]. WAECHST mit der Dauer - genau darin liegt der Unterschied
         *  zu beiden widerlegten Entwuerfen. */
        fun allowedDropMgdl(dtMin: Double): Double =
            noiseAllowanceMgdl + driftMgdlPerMin * dtMin
    }

    /**
     * @param worstDropMgdl der groesste gefundene Rueckgang [mg/dl] (<= 0).
     * @param worstDropSpanMin ueber welche Dauer er lief - dieselbe Hoehe ueber
     *   2 oder 10 Minuten beschreibt verschiedene Lagen (Toni 28.08.).
     * @param worstDropEndsTs wann der Punkt, der DIESEN Rueckgang traegt, aus
     *   dem Fenster faellt.
     *
     *   KEIN FREIGABE-COUNTDOWN (Tonis Hinweis 28.08.): andere fallende Paare
     *   koennen weiter sperren. Der Wert beschreibt ausschliesslich das
     *   ausgewaehlte Paar. Wer daraus im Viewer eine Restzeit bis zur Freigabe
     *   macht, behauptet mehr, als hier steht.
     * @param evaluatedPairs wie viele Paare tatsaechlich beurteilt wurden. 0
     *   ist ein Ablehnungsgrund, kein stilles STABLE.
     */
    data class Result(
        val verdict: Verdict,
        val reason: Reason,
        val worstDropMgdl: Double,
        val worstDropSpanMin: Double,
        val worstDropEndsTs: Long,
        val points: Int,
        val spanMin: Double,
        val evaluatedPairs: Int,
    )

    private fun nein(reason: Reason, punkte: Int = 0, span: Double = 0.0) =
        Result(Verdict.UNDETERMINED, reason, 0.0, 0.0, 0L, punkte, span, 0)

    /**
     * @param priorEpochTs Segmentidentitaet des Vorzyklus, 0 = keine. Wechselt
     *   sie, faellt das Urteil auf UNDETERMINED - eine geerbte Beurteilung
     *   ueber einen Bruch hinweg waere erfunden.
     */
    fun evaluate(
        series: MeasuredGlucose,
        nowTs: Long,
        params: Params,
        priorEpochTs: Long = 0L,
    ): Result {
        if (!params.usable) return nein(Reason.INVALID_PARAMS)
        if (priorEpochTs != 0L && series.signalEpochTs != priorEpochTs)
            return nein(Reason.SEGMENT_CHANGED)

        // VERALTET IST NICHT RUHIG - und die Pruefung gehoert VOR den
        // Fensterfilter. Stuende sie dahinter, haette der Filter die alten
        // Punkte laengst entfernt, STALE waere unerreichbar und die Diagnose
        // hiesse TOO_FEW_POINTS. Der Aufrufer suchte dann nach Luecken,
        // waehrend in Wahrheit die ganze Reihe alt ist.
        val juengster = series.points.lastOrNull()
            ?: return nein(Reason.TOO_FEW_POINTS)
        if ((nowTs - juengster.sourceTs) / 60_000.0 > params.maxAgeMin)
            return nein(Reason.STALE, series.size)

        val punkte = series.lastMinutes(nowTs, params.windowMin)
        if (punkte.size < params.minPoints) return nein(Reason.TOO_FEW_POINTS, punkte.size)

        // UNBRAUCHBARE ZAHLEN SIND KEIN URTEIL. Ein NaN im Rohwert wuerde
        // jeden Vergleich still zu false machen und damit als "faellt nicht"
        // durchgehen - fail-open an der schlechtesten Stelle.
        for (i in punkte.indices) {
            if (!punkte[i].rawBg.isFinite()) return nein(Reason.INVALID_INPUT, punkte.size)
            if (i > 0 && punkte[i].sourceTs <= punkte[i - 1].sourceTs)
                return nein(Reason.INVALID_INPUT, punkte.size)
        }

        val spanMin = (punkte.last().sourceTs - punkte.first().sourceTs) / 60_000.0
        if (spanMin < params.windowMin * 0.6)
            return nein(Reason.SPAN_TOO_SHORT, punkte.size, spanMin)

        // EIN LOCH IM FENSTER IST KEIN BELEG. Sechs Punkte bei Minute
        // 0,1,2,3,9,10 erfuellen Anzahl UND Spanne und sagen trotzdem nichts
        // ueber die sechs Minuten dazwischen.
        for (i in 1 until punkte.size) {
            val luecke = (punkte[i].sourceTs - punkte[i - 1].sourceTs) / 60_000.0
            if (luecke > params.maxGapMin) return nein(Reason.GAP_IN_WINDOW, punkte.size, spanMin)
        }

        // JEDES Paar wird beurteilt - keines wird uebersprungen. Verglichen
        // wird der Rueckgang gegen die zur Dauer passende Toleranz.
        var schlimmste = 0.0          // Ueberschreitung (negativ = ueber der Toleranz)
        var schlechtDrop = 0.0
        var schlechtSpan = 0.0
        var schlechtStartTs = 0L
        var paare = 0
        for (a in punkte.indices) {
            for (b in a + 1 until punkte.size) {
                val dt = (punkte[b].sourceTs - punkte[a].sourceTs) / 60_000.0
                if (dt <= 0.0) continue
                paare++
                val d = punkte[b].rawBg - punkte[a].rawBg
                // Wie weit REISST der Abschnitt die Toleranz - nicht wie tief
                // er faellt. Sonst gewaenne immer das laengste Intervall.
                val ueberschuss = d + params.allowedDropMgdl(dt)
                if (ueberschuss < schlimmste) {
                    schlimmste = ueberschuss
                    schlechtDrop = d
                    schlechtSpan = dt
                    schlechtStartTs = punkte[a].sourceTs
                }
            }
        }
        // KEIN PAAR HEISST KEIN URTEIL, nicht "stabil".
        if (paare == 0) return nein(Reason.TOO_FEW_POINTS, punkte.size, spanMin)

        val faellt = schlimmste < 0.0
        return Result(
            verdict = if (faellt) Verdict.FALLING else Verdict.STABLE,
            reason = if (faellt) Reason.DROP_EXCEEDS else Reason.OK,
            worstDropMgdl = if (faellt) schlechtDrop else groesserRueckgang(punkte),
            worstDropSpanMin = schlechtSpan,
            worstDropEndsTs = if (schlechtStartTs == 0L) 0L
            else schlechtStartTs + params.windowMin * 60_000L,
            points = punkte.size,
            spanMin = spanMin,
            evaluatedPairs = paare,
        )
    }

    /** Der groesste Rueckgang im Fenster - fuer den Bericht, wenn nichts die
     *  Toleranz reisst. Ohne ihn stuende im stabilen Fall eine Null, und man
     *  saehe nicht, wie nah die Lage an der Grenze war. */
    private fun groesserRueckgang(punkte: List<GlucosePoint>): Double {
        var schlecht = 0.0
        for (a in punkte.indices) for (b in a + 1 until punkte.size) {
            val d = punkte[b].rawBg - punkte[a].rawBg
            if (d < schlecht) schlecht = d
        }
        return schlecht
    }

    /** Nur fuer Tests und Anzeige: reisst dieser Abschnitt die Toleranz? */
    fun exceedsTolerance(dropMgdl: Double, dtMin: Double, params: Params): Boolean =
        dropMgdl + params.allowedDropMgdl(dtMin) < 0.0 && abs(dtMin) > 0.0
}

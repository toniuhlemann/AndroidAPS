package app.aaps.fuse.core.signal

/**
 * IST DIE GEMESSENE GLUKOSELAGE GERADE STABIL?
 *
 * DER ANLASS (Toni 28.08.). Der autorisierte Mahlzeiten-Sofortanteil wartete
 * bei praktisch flachem Zucker minutenlang, weil zwei NULLTOLERANZEN ihn
 * hielten: `ukfRatePerMin < 0` und "q1 ist gegenueber dem Vorzyklus gefallen".
 * Gemessen am Fruehstueck des 28.08.: q1 lag von 09:22 bis 09:32 zwischen 94,3
 * und 95,5 mg/dl - flacher geht kaum -, aber die Filterrate blieb knapp
 * negativ (zuletzt -0,0133 mg/dl/min) und q1 wackelte um 0,1 bis 0,3. Vier
 * autorisierte Einheiten lagen still.
 *
 * DREI WERTE, NICHT ZWEI (Tonis Auflage). [Verdict.UNDETERMINED] ist ein
 * eigener Ausgang und ausdruecklich KEINE Entwarnung: zu wenige Punkte, ein
 * Segmentbruch oder eine Luecke heissen "nicht beurteilbar" und muessen den
 * Batch genauso halten wie ein gemessener Abfall. Fehlende Daten sind kein
 * Beleg fuer Ruhe.
 *
 * WAS DIESE KLASSE IST UND WAS NICHT:
 *  - Sie BESCHREIBT DIE GEGENWART: faellt die gemessene Reihe im Fenster?
 *  - Sie sagt NICHTS ueber die Zukunft voraus. Eine Breitenmessung ueber
 *    fuenf Tage (256 nicht ueberlappende Proben ohne dokumentierten Eingriff)
 *    hat gezeigt, dass keine dieser Fensterstatistiken die naechsten 20
 *    Minuten ausreichend trennt - FLACH reichte bis -19,7 mg/dl schlechtesten
 *    Abfall, der Median der FALLENDEN lag bei -5,8. Das ist ein Negativbefund
 *    ueber DIESE Kennzahlen auf DIESEM Material, keine Aussage darueber, dass
 *    so etwas prinzipiell nicht vorhersagbar waere.
 *  - Sie ist TEIL DER SICHERHEITSENTSCHEIDUNG, nicht bloss eine Vorstufe:
 *    sobald "stabil" einen Mehr-Einheiten-Batch ermoeglicht, traegt sie
 *    Verantwortung mit. Mengenlimits und Risikohorizonte daneben sind
 *    Schutzbedingungen, kein Ersatznachweis (Toni 28.08.).
 *
 * DIE PARAMETER SIND BEGRUENDETE WAHLEN, KEINE ABLEITUNGEN. Aus der
 * Sensoraufloesung folgt KEINE eindeutige zulaessige Abfalltoleranz - sie sagt
 * nur, welche Unterschiede die Messung ueberhaupt aufloesen kann. Die
 * Begruendung steht bei jedem Feld von [Params]; die Zahlen sind Kandidaten
 * und ausdruecklich noch nicht abgenommen.
 *
 * WARUM DIE TOLERANZ IN mg/dl STEHT UND NICHT IN mg/dl/min: ein erster
 * Entwurf verbot jedem Teilintervall ab 2 Minuten eine Rate unter -0,1. Toni
 * hat ihn an den eigenen Daten widerlegt - der Schritt 95 -> 94 zwischen 09:27
 * und 09:29 ergibt ueber zwei Minuten -0,49 mg/dl/min und haette bis 09:37
 * jeden Nachweis blockiert, also LAENGER gesperrt als der Zustand vorher.
 * Ein einzelner Quantisierungsschritt kostet in mg/dl immer 1, als Rate ueber
 * zwei Minuten aber 0,5. Nicht die Einheit war der Fehler, sondern die
 * Anwendung auf beliebig kurze Abschnitte.
 */
object GlucoseStability {

    enum class Verdict {
        /** Die gemessene Reihe faellt im Fenster nicht mehr als zugelassen. */
        STABLE,

        /** Ein Rueckgang ueber der Toleranz - gemessen, nicht gefiltert. */
        FALLING,

        /** NICHT BEURTEILBAR. Keine Entwarnung: der Aufrufer muss halten. */
        UNDETERMINED,
    }

    enum class Reason {
        OK,

        /** Weniger Punkte im Fenster als [Params.minPoints]. */
        TOO_FEW_POINTS,

        /** Die Punkte decken das Fenster zeitlich nicht ab. */
        SPAN_TOO_SHORT,

        /** Der Rueckgang ueberschreitet [Params.maxDropMgdl]. */
        DROP_EXCEEDS,

        /** Der Segmentanker hat gewechselt - die Reihe gehoert zu einem
         *  anderen Signalsegment als der Vorzyklus. */
        SEGMENT_CHANGED,
    }

    /**
     * @param windowMin Laenge des Beurteilungsfensters.
     *   WAHL, nicht Ableitung: bei 5 Minuten ueberlappten in der Vormessung
     *   flache und fallende Verlaeufe (flach bis 0,715 / fallend ab 0,318
     *   mg/dl/min), ab 8 trennten sie knapp, bei 10 lag Faktor 2,5 dazwischen.
     *   Laenger waere trennschaerfer und traeger - 10 ist der Kompromiss, den
     *   die Messung stuetzt, nicht erzwingt.
     * @param minSubSpanMin Kuerzestes Teilintervall, das ueberhaupt beurteilt
     *   wird. WAHL: unterhalb davon dominiert die Quantisierung. Ein Schritt
     *   von 1 mg/dl entspricht ueber 2 Minuten 0,5 mg/dl/min, ueber 5 Minuten
     *   0,2. Ausdruecklich NICHT von `BgiAdjustedSeries.PAIR_DT_MIN_MS`
     *   uebernommen: jene 2 Minuten stammen aus dem Steigungsschaetzer, und
     *   ihre Existenz begruendet keine Sicherheitsregel fuer Mahlzeiten.
     * @param maxDropMgdl Groesster zugelassener Rueckgang im Fenster [mg/dl].
     *   WAHL, KANDIDAT: zwei Quantisierungsschritte plus einer Reserve. Die
     *   Aufloesung sagt, dass 1 mg/dl nicht unterscheidbar ist; sie sagt
     *   NICHT, welcher Rueckgang zulaessig ist. Diese Zahl ist eine
     *   Sicherheitsentscheidung und gehoert abgenommen, nicht hergeleitet.
     * @param minPoints Mindestzahl gueltiger Punkte im Fenster.
     */
    data class Params(
        val windowMin: Int = 10,
        val minSubSpanMin: Double = 5.0,
        val maxDropMgdl: Double = 3.0,
        val minPoints: Int = 6,
    )

    /**
     * @param verdict das Urteil.
     * @param worstDropMgdl der groesste gefundene Rueckgang [mg/dl] (<= 0).
     * @param worstDropSpanMin ueber welche Dauer er lief - dieselbe Hoehe ueber
     *   2 oder 10 Minuten beschreibt verschiedene Lagen (Toni 28.08.), deshalb
     *   steht die Dauer daneben und nicht nur die Hoehe.
     * @param worstDropEndsTs wann der Punkt, der den Rueckgang traegt, aus dem
     *   Fenster faellt. Ohne diese Angabe entsteht eine verdeckte Sperre:
     *   der Aufrufer kann sonst nicht sagen, WANN sein Einfluss endet.
     */
    data class Result(
        val verdict: Verdict,
        val reason: Reason,
        val worstDropMgdl: Double,
        val worstDropSpanMin: Double,
        val worstDropEndsTs: Long,
        val points: Int,
        val spanMin: Double,
    )

    private val UNBEURTEILBAR = Result(
        Verdict.UNDETERMINED, Reason.TOO_FEW_POINTS, 0.0, 0.0, 0L, 0, 0.0,
    )

    /**
     * @param priorEpochTs Segmentidentitaet des Vorzyklus, 0 = keine.
     *   Wechselt sie, ist die Reihe nicht mehr dieselbe und das Urteil faellt
     *   auf UNDETERMINED - eine geerbte Beurteilung ueber einen Bruch hinweg
     *   waere erfunden.
     */
    fun evaluate(
        series: MeasuredGlucose,
        nowTs: Long,
        params: Params,
        priorEpochTs: Long = 0L,
    ): Result {
        if (priorEpochTs != 0L && series.signalEpochTs != priorEpochTs)
            return UNBEURTEILBAR.copy(reason = Reason.SEGMENT_CHANGED)

        val punkte = series.lastMinutes(nowTs, params.windowMin)
        if (punkte.size < params.minPoints) return UNBEURTEILBAR
        val spanMin = (punkte.last().sourceTs - punkte.first().sourceTs) / 60_000.0
        // Das Fenster muss zeitlich auch GEFUELLT sein. Sechs Punkte in zwei
        // Minuten sind kein Zehn-Minuten-Nachweis.
        if (spanMin < params.windowMin * 0.6)
            return UNBEURTEILBAR.copy(reason = Reason.SPAN_TOO_SHORT, points = punkte.size, spanMin = spanMin)

        // Der groesste Rueckgang ueber ein Teilintervall von mindestens
        // minSubSpanMin. Beurteilt wird der ROHWERT: q1 ist bereits
        // UKF-Ausgabe und traegt dessen Nachlauf, und genau der Nachlauf war
        // der Grund, warum eine reale Stabilisierung nicht durchkam.
        var schlecht = 0.0
        var schlechtSpan = 0.0
        var schlechtStartTs = 0L
        for (a in punkte.indices) {
            for (b in a + 1 until punkte.size) {
                val dt = (punkte[b].sourceTs - punkte[a].sourceTs) / 60_000.0
                if (dt < params.minSubSpanMin) continue
                val d = punkte[b].rawBg - punkte[a].rawBg
                if (d < schlecht) {
                    schlecht = d
                    schlechtSpan = dt
                    schlechtStartTs = punkte[a].sourceTs
                }
            }
        }
        val faellt = schlecht < -params.maxDropMgdl
        return Result(
            verdict = if (faellt) Verdict.FALLING else Verdict.STABLE,
            reason = if (faellt) Reason.DROP_EXCEEDS else Reason.OK,
            worstDropMgdl = schlecht,
            worstDropSpanMin = schlechtSpan,
            // Wann der tragende Punkt aus dem Fenster faellt - damit der
            // Aufrufer den Einfluss benennen kann, statt ihn zu erleiden.
            worstDropEndsTs = if (schlechtStartTs == 0L) 0L
            else schlechtStartTs + params.windowMin * 60_000L,
            points = punkte.size,
            spanMin = spanMin,
        )
    }
}

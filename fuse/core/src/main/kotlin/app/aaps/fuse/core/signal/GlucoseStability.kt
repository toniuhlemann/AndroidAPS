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

    /**
     * HAT SICH DIE LAGE IM JUENGSTEN ZUSAMMENHAENGENDEN ABSCHNITT BERUHIGT?
     *
     * DIES IST DER NACHWEIS, AUF DEN DER RUHE-AUSGANG HOERT - nicht das
     * Fensterurteil [Verdict] und schon gar nicht eines der Diagnoseflags.
     *
     * WARUM ES IHN BRAUCHT (Toni 28.08.). Weder [Result.dropReachesNow] noch
     * [Result.bindingEndsAtNewest] belegt eine LAUFENDE Fallbewegung. Beide
     * sagen nur etwas ueber den Vergleich mit frueheren Werten: die Reihe
     * 110,100,100,100,100,100,100,100 ist seit sechs Minuten unveraendert und
     * meldet trotzdem beides. Ein historischer Rueckgang BIS AUF das heutige
     * Niveau ist etwas anderes als ein Abfall, der jetzt noch laeuft - und
     * genau diese zwei Dinge waren vermengt.
     *
     * Die urspruengliche Aufgabe lautet: eine BELEGTE STABILISIERUNG soll
     * einen historischen Erholungsriegel loesen koennen. Dafuer reicht es
     * nicht, alte und aktuelle Messpunkte anders zu vergleichen.
     */
    enum class Stabilisation {
        /**
         * Der juengste Abschnitt bleibt INNERHALB DER ZUGELASSENEN TOLERANZ.
         *
         * DAS HIESS EINMAL "STABILISED" - zu stark (Toni 28.08.). Bewiesen ist
         * nicht, dass der Abfall aufgehoert hat, sondern nur, dass er die
         * vereinbarte Grenze nicht reisst. Wie gross der dabei akzeptierte
         * DAUERABFALL ist, steht ausrechenbar in
         * [Params.acceptedSustainedFallMgdlPerMin] - und muss dort stehen,
         * weil er sonst als "beendet" missverstanden wird.
         */
        WITHIN_TOLERANCE,

        /** Er reisst die Toleranz - eine laufende Bewegung. */
        FALLING_BEYOND_TOLERANCE,

        /** Zu wenige Punkte, Luecke oder zu kurze Spanne im juengsten
         *  Abschnitt. Keine Entwarnung. */
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
        /**
         * Der JUENGSTE zusammenhaengende Abschnitt, in dem die Stabilisierung
         * belegt werden muss [min]. WAHL, KANDIDAT: kurz genug, dass ein
         * abgeschlossener Rueckgang herausfaellt, lang genug, dass eine
         * Pause zwischen zwei Abfaellen ihn nicht vortaeuscht.
         */
        val recentWindowMin: Int = 5,
        /** Mindestpunkte im juengsten Abschnitt. */
        val recentMinPoints: Int = 4,
        /**
         * Die TATSAECHLICH BEOBACHTETE Mindestspanne des juengsten Abschnitts
         * [min] - Teil des Vertrags, nicht der Implementierung.
         *
         * Hier stand eine 60-Prozent-Regel auf [recentWindowMin], also drei
         * Minuten. Toni hat gezeigt, wohin das fuehrt: bei 58-62-s-Takt faellt
         * der aelteste Punkt knapp aus dem Fenster, es bleiben etwa 3:59 - und
         * ein Abfall von -0,5 mg/dl/min ergibt darueber nur -1,99 und
         * passierte. Der akzeptierte Dauerabfall haengt also unmittelbar an
         * dieser Spanne, und deshalb ist sie eine eigene, benannte Groesse
         * statt eines Faktors im Code.
         *
         * 4,5 statt 5,0 laesst Taktjitter zu, ohne das Loch wieder zu oeffnen.
         */
        val recentMinObservedSpanMin: Double = 4.5,
    ) {

        /**
         * DER VERTRAG IN EINER ZAHL: welcher UNUNTERBROCHENE Abfall
         * [mg/dl/min] passiert diese Fassung hoechstens?
         *
         * Er folgt zwingend aus Zugabe und beobachteter Mindestspanne. Mit den
         * Kandidatenwerten sind das 2,0 / 4,5 = 0,444 mg/dl/min. Zum
         * Vergleich: die frueheren 60 Prozent von 5 Minuten liessen
         * 2,0 / 3,0 = 0,667 durch, ohne dass es irgendwo stand.
         *
         * DIESE ZAHL GEHOERT IN DEN BERICHT. Wer "innerhalb der Toleranz" als
         * "hat aufgehoert" liest, soll hier sehen, was das kostet.
         */
        val acceptedSustainedFallMgdlPerMin: Double
            get() = if (recentMinObservedSpanMin > 0.0)
                noiseAllowanceMgdl / recentMinObservedSpanMin else Double.NaN

        /** Ein Parametersatz, unter dem kein Urteil moeglich waere, ist selbst
         *  ein Ablehnungsgrund - nicht ein stilles STABLE. */
        val usable: Boolean
            get() = windowMin > 0 && minPoints >= 2 &&
                maxGapMin > 0.0 && maxGapMin.isFinite() &&
                maxAgeMin > 0.0 && maxAgeMin.isFinite() &&
                recentWindowMin > 0 && recentMinPoints >= 2 &&
                recentMinObservedSpanMin > 0.0 && recentMinObservedSpanMin.isFinite() &&
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
        /** Was dieser Abschnitt haette fallen duerfen - damit im stabilen
         *  Grenzfall nachvollziehbar ist, WARUM er bestanden hat. */
        val worstDropAllowedMgdl: Double,
        val worstDropEndsTs: Long,
        val points: Int,
        val spanMin: Double,
        val evaluatedPairs: Int,
        /**
         * ENDET DER BINDENDE ABSCHNITT AM JUENGSTEN PUNKT?
         *
         * `true` = ein FRISCHER Abfall: der Rueckgang laeuft bis jetzt.
         * `false` = ein AELTERER Rueckgang, der das Fenster noch nicht
         * verlassen hat - gerade faellt nichts mehr.
         *
         * Getrennt auszuweisen (Toni 28.08.), weil es zwei verschiedene Lagen
         * sind: am Fruehstueck des 28.08. hielt von 09:24 bis 09:26 ein
         * Rueckgang, der um 09:23 bereits beendet war. Diese Klasse handelt
         * NICHT unterschiedlich danach - sie sagt nur, welcher Fall vorliegt.
         */
        val bindingEndsAtNewest: Boolean,
        /**
         * FAELLT GERADE ETWAS? - der ehrliche Gegenwartsbefund.
         *
         * `true`, wenn IRGENDEIN die Toleranz reissender Abschnitt bis zum
         * juengsten Punkt laeuft. Nicht zu verwechseln mit
         * [bindingEndsAtNewest], das nur das STAERKSTE Paar beschreibt.
         *
         * Toni hat den Unterschied am Gegenfall gezeigt (28.08.):
         * 120,120,110,100,100,110,112,114,116,114,111 - das staerkste Paar ist
         * 120 -> 100 (-20 ueber 2 min) und liegt frueh, also meldet
         * bindingEndsAtNewest ALT. Gleichzeitig laufen FUENF verletzende
         * Abschnitte bis zum juengsten Punkt, darunter -3,0 ueber 3 min. Wer
         * aus "staerkstes Paar ist alt" auf "jetzt faellt nichts" schliesst,
         * uebersieht einen frischen Abfall.
         *
         * Umgekehrt gilt ebenso wenig: ein Paar, das am juengsten Punkt endet,
         * beweist keinen FORTDAUERNDEN Abfall - sein Endwert kann seit
         * Minuten unveraendert sein.
         *
         * Diese Klasse HANDELT nicht danach. Wer eine Lockerung darauf bauen
         * will, braucht diesen Wert, nicht den anderen.
         */
        /**
         * Liegt der AKTUELLE Wert gegenueber irgendeinem frueheren staerker
         * unter der erlaubten Differenz?
         *
         * HIESS BIS 28.08. `freshDropExists` - ein irrefuehrender Name, den
         * Toni an einem eigenen Testfall entlarvt hat: die Reihe
         * 110,100,100,100,100,100,100,100 ist seit sechs Minuten unveraendert
         * und meldet trotzdem `true`. Das Flag beschreibt einen HISTORISCHEN
         * RUECKGANG BIS AUF DAS HEUTIGE NIVEAU, keine laufende Bewegung.
         *
         * Es beweist damit NICHTS ueber die Gegenwart. Wer wissen will, ob
         * gerade noch gefallen wird, liest [stabilisation].
         */
        val dropReachesNow: Boolean,
        /** Der eigentliche Gegenwartsnachweis - s. [Stabilisation]. */
        val stabilisation: Stabilisation,
        /** Groesster Rueckgang im juengsten Abschnitt [mg/dl], fuer den Bericht. */
        val recentWorstDropMgdl: Double,
        /** Und ueber welche Dauer - dieselbe Hoehe heisst nicht dasselbe. */
        val recentWorstDropSpanMin: Double,
        /**
         * Die TATSAECHLICH beobachtete Spanne DES JUENGSTEN ABSCHNITTS [min].
         *
         * Nicht [spanMin] - das ist die Spanne des GROSSEN Fensters. Die
         * beiden waren im Export vertauscht (Toni 28.08.), und weil der
         * Exporttest seine Werte von Hand zusammenbaute, fiel es dort nicht
         * auf. Deshalb tragen sie jetzt beide ihren eigenen Wert, und der
         * Test setzt sie ABSICHTLICH verschieden.
         */
        val recentSpanMin: Double,
        /** NETTO ueber den juengsten Abschnitt [mg/dl] - erster gegen letzter
         *  Punkt. Die Groesse, an der "hat es aufgehoert" haengt; sie ist
         *  NICHT der groesste Teilrueckgang. */
        val recentNetMgdl: Double,
        /** Was der bindende Abschnitt DORT haette fallen duerfen [mg/dl]. */
        val recentAllowedDropMgdl: Double,
        /**
         * Wie viele AUFEINANDERFOLGENDE Zyklen bis hierher bereits stabil
         * waren - rueckwirkend aus der vorhandenen Reihe gerechnet.
         *
         * Jede dieser Rueckrechnungen benutzt AUSSCHLIESSLICH Punkte bis zu
         * ihrem eigenen Zeitpunkt. Es fliesst keine Zukunftsinformation in
         * eine Vergangenheitsbewertung; es ist dieselbe Rechnung, nur frueher
         * angesetzt.
         */
        val confirmedCycles: Int,
    )

    private fun nein(reason: Reason, punkte: Int = 0, span: Double = 0.0) =
        Result(
            Verdict.UNDETERMINED, reason, 0.0, 0.0, 0.0, 0L, punkte, span, 0, false,
            false, Stabilisation.UNDETERMINED, 0.0, 0.0, 0.0, 0.0, 0.0, 0,
        )

    /** Wie weit die Rueckrechnung hoechstens geht. Mehr als das braucht kein
     *  Bestaetigungszaehler, und die Kosten waeren quadratisch je Schritt. */
    const val MAX_CONFIRM_LOOKBACK = 12

    /** Ein Zeitstempel darf `nowTs` um diese Spanne ueberschreiten, ohne als
     *  Uhrenproblem zu gelten - Rechenzeit innerhalb eines Zyklus. */
    const val FUTURE_TOLERANCE_MS = 60_000L

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
        // ZUKUNFTSPUNKTE AUSDRUECKLICH BEHANDELN (Toni 28.08.). Ein Punkt
        // hinter `nowTs` ist ein Uhrenproblem, kein Messwert.
        //
        // DIE PRUEFUNG STEHT HIER, NICHT IM KERN: die Rueckschau bewertet
        // frueheres Zeitpunkte DERSELBEN Reihe, und dort liegen die spaeteren
        // Punkte natuerlich hinter ihrem `nowTs`. Stuende sie im Kern, fiele
        // jede Rueckrechnung mehr als eine Minute zurueck als INVALID_INPUT
        // durch - gemessen: eine durchgehend ruhige Reihe belegte nur zwei
        // statt vieler Zyklen.
        val neuester = series.points.lastOrNull() ?: return nein(Reason.TOO_FEW_POINTS)
        if (neuester.sourceTs - nowTs > FUTURE_TOLERANCE_MS)
            return nein(Reason.INVALID_INPUT, series.size)

        val jetzt = kern(series, nowTs, params)
        if (jetzt.stabilisation != Stabilisation.WITHIN_TOLERANCE) return jetzt
        return jetzt.copy(confirmedCycles = bestaetigteZyklen(series, nowTs, params))
    }

    /**
     * DIE VOLLSTAENDIGE BEWERTUNG EINES ZEITPUNKTES.
     *
     * SIE GILT FUER HISTORIE UND GEGENWART GLEICHERMASSEN (Toni 28.08.).
     * Vorher rechnete die Rueckschau mit einer abgespeckten Variante, die
     * Zahlen, Reihenfolge und Toleranzen prueft - aber KEINE Luecke. Tonis
     * Gegenfall: konstante Werte bei Minute -1,3,4,...,10. Das aktuelle
     * Fenster ist sauber, die historischen enthalten eine Vierminutenluecke
     * und waeren vollstaendig bewertet UNDETERMINED - die Rueckschau zaehlte
     * sie trotzdem als vier bestaetigte Zyklen. Zwei Bewertungsfunktionen
     * heissen zwei Wahrheiten; jetzt gibt es nur diese eine.
     */
    private fun kern(
        series: MeasuredGlucose,
        nowTs: Long,
        params: Params,
    ): Result {

        // VERALTET IST NICHT RUHIG. Diese Vorpruefung faengt die vollstaendig
        // alte Reihe; die eigentliche Zusicherung steht weiter unten auf dem
        // juengsten TATSAECHLICH BENUTZTEN Punkt.
        val juengsterEcht = series.points.lastOrNull { it.sourceTs <= nowTs }
            ?: return nein(Reason.TOO_FEW_POINTS)
        if ((nowTs - juengsterEcht.sourceTs) / 60_000.0 > params.maxAgeMin)
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

        // DIE FRISCHE GILT FUER DIE PUNKTE, DIE WIRKLICH BEURTEILT WERDEN.
        // Die Vorpruefung oben kann das nicht leisten: sie sieht auch Punkte,
        // die der Fensterfilter danach entfernt.
        if ((nowTs - punkte.last().sourceTs) / 60_000.0 > params.maxAgeMin)
            return nein(Reason.STALE, punkte.size, spanMin)

        // JEDES Paar wird beurteilt - keines wird uebersprungen. Verglichen
        // wird der Rueckgang gegen die zur Dauer passende Toleranz.
        //
        // ES GEWINNT IMMER DASSELBE PAAR, in beiden Ausgaengen (Toni 28.08.):
        // das mit dem KLEINSTEN Abstand zu seiner Toleranz. Vorher kam der
        // gemeldete Rueckgang im stabilen Fall aus einem zweiten,
        // nachtraeglichen Scan, waehrend Dauer und Zeitstempel nur bei einer
        // Verletzung gesetzt wurden - herauskam "-2 mg/dl ueber 0 Minuten",
        // ein Tripel, das kein Paar beschreibt. Gerade im stabilen Grenzfall
        // war damit nicht nachvollziehbar, WARUM er bestanden hat.
        var engster = Double.MAX_VALUE   // kleinster Abstand zur Toleranz
        var engDrop = 0.0
        var engSpan = 0.0
        var engStartTs = 0L
        var engErlaubt = 0.0
        var paare = 0
        // Faellt IRGENDEIN Abschnitt, der bis zum juengsten Punkt laeuft, aus
        // der Toleranz? Das ist die Gegenwartsfrage - unabhaengig davon,
        // welches Paar am staerksten reisst.
        var frischerAbfall = false
        for (a in punkte.indices) {
            for (b in a + 1 until punkte.size) {
                val dt = (punkte[b].sourceTs - punkte[a].sourceTs) / 60_000.0
                if (dt <= 0.0) continue
                paare++
                val d = punkte[b].rawBg - punkte[a].rawBg
                val erlaubt = params.allowedDropMgdl(dt)
                // Wie weit REISST der Abschnitt seine Toleranz - nicht wie
                // tief er faellt. Sonst gewaenne immer das laengste Intervall.
                val abstand = d + erlaubt
                if (abstand < 0.0 && b == punkte.size - 1) frischerAbfall = true
                if (abstand < engster) {
                    engster = abstand
                    engDrop = d
                    engSpan = dt
                    engStartTs = punkte[a].sourceTs
                    engErlaubt = erlaubt
                }
            }
        }
        // KEIN PAAR HEISST KEIN URTEIL, nicht "stabil".
        if (paare == 0) return nein(Reason.TOO_FEW_POINTS, punkte.size, spanMin)

        val beruhigt = juengsterAbschnitt(series, nowTs, params)
        val faellt = engster < 0.0
        // FRISCH ODER ALT: endet der bindende Abschnitt am juengsten Punkt?
        val bindetBisJetzt = engStartTs != 0L &&
            engStartTs + (engSpan * 60_000L).toLong() >= punkte.last().sourceTs - 1_000L
        return Result(
            verdict = if (faellt) Verdict.FALLING else Verdict.STABLE,
            reason = if (faellt) Reason.DROP_EXCEEDS else Reason.OK,
            worstDropMgdl = engDrop,
            worstDropSpanMin = engSpan,
            worstDropAllowedMgdl = engErlaubt,
            worstDropEndsTs = engStartTs + params.windowMin * 60_000L,
            points = punkte.size,
            spanMin = spanMin,
            evaluatedPairs = paare,
            bindingEndsAtNewest = bindetBisJetzt,
            dropReachesNow = frischerAbfall,
            stabilisation = beruhigt.verdict,
            recentWorstDropMgdl = beruhigt.worstDropMgdl,
            recentWorstDropSpanMin = beruhigt.worstDropSpanMin,
            recentSpanMin = beruhigt.spanMin,
            recentNetMgdl = beruhigt.netMgdl,
            recentAllowedDropMgdl = beruhigt.allowedDropMgdl,
            // Der Kern zaehlt NICHT zurueck - das taete er sonst rekursiv.
            confirmedCycles = 0,
        )
    }

    /**
     * WIE VIELE ZYKLEN WAR ES SCHON STABIL - aus der vorhandenen Reihe.
     *
     * DER ANLASS (Toni 28.08.): der Nachweis las zwar die Vorgeschichte,
     * aber der BESTAETIGUNGSZAEHLER begann nach dem Marker wieder bei 1. Am
     * Fruehstueck hiess das: 09:22 stabil (1/3), 09:23-09:26 ein alter
     * Rueckgang setzt zurueck, 09:27 wieder 1/3, Freigabe erst 09:29:22 -
     * also 7 min 25 s nach dem Marker. Die Wartezeit kam aus der
     * unvollstaendigen Nutzung der Historie, nicht aus einer
     * Sicherheitsbedingung.
     *
     * KEINE ZUKUNFTSINFORMATION: jede Rueckrechnung sieht ausschliesslich
     * Punkte bis zu IHREM eigenen Zeitpunkt. Es ist dieselbe Rechnung, nur
     * frueher angesetzt.
     *
     * WAS DAS AUSDRUECKLICH NICHT TUT: es ersetzt keine aktuelle
     * Gefahrenpruefung. Die laeuft im Aufrufer in DIESEM Zyklus und
     * unmittelbar vor der Anforderung - Historie belegt Signalruhe, nicht
     * Ungefaehrlichkeit. Autorisierung und Mengenbilanz haengen unveraendert
     * am neuen Marker.
     */
    /**
     * DER JUENGSTE ZUSAMMENHAENGENDE ABSCHNITT - faellt dort noch etwas?
     *
     * Dieselbe laengenabhaengige Toleranz wie im grossen Fenster, aber nur
     * auf die letzten [Params.recentWindowMin] Minuten angewandt. Ein
     * abgeschlossener Rueckgang faellt damit heraus, sobald er alt genug ist;
     * eine Pause zwischen zwei Abfaellen taeuscht keine Beruhigung vor,
     * solange der zweite Abfall im Abschnitt liegt.
     *
     * @return Urteil, groesster Rueckgang [mg/dl], dessen Dauer [min]
     */
    /** Was der juengste Abschnitt hergibt - benannt statt als Triple. */
    private data class Recent(
        val verdict: Stabilisation,
        val worstDropMgdl: Double,
        val worstDropSpanMin: Double,
        val spanMin: Double,
        val netMgdl: Double,
        val allowedDropMgdl: Double,
    )

    /**
     * DER JUENGSTE ZUSAMMENHAENGENDE ABSCHNITT - faellt dort noch etwas?
     *
     * Zwei Bedingungen, und sie messen Verschiedenes:
     *  (1) kein SCHARFER Einbruch - laengenabhaengig, damit ein
     *      Quantisierungsschritt nicht zaehlt;
     *  (2) das NETTO >= -noiseAllowanceMgdl - dauerUNabhaengig, weil "hat es
     *      aufgehoert" nichts mit der Dauer zu tun hat. Mit einer
     *      mitwachsenden Schranke waere jeder hinreichend langsame
     *      Dauerabfall per Konstruktion "stabil".
     *
     * DAS FENSTER SCHLIESST DEN KLAMMERPUNKT EIN - s.
     * [MeasuredGlucose.windowCovering]. Ohne ihn erreichte es bei 61- oder
     * 62-Sekunden-Takt seine eigene Laenge nie und lieferte dauerhaft
     * UNDETERMINED, auch bei vollkommen konstantem Zucker.
     */
    private fun juengsterAbschnitt(
        series: MeasuredGlucose,
        nowTs: Long,
        params: Params,
    ): Recent {
        val leer = Recent(Stabilisation.UNDETERMINED, 0.0, 0.0, 0.0, 0.0, 0.0)
        val letzte = series.windowCovering(nowTs, params.recentWindowMin, params.recentMinObservedSpanMin)
        if (letzte.size < params.recentMinPoints) return leer
        val spanne = (letzte.last().sourceTs - letzte.first().sourceTs) / 60_000.0
        if (spanne < params.recentMinObservedSpanMin) return leer.copy(spanMin = spanne)
        for (i in 1 until letzte.size) {
            val luecke = (letzte[i].sourceTs - letzte[i - 1].sourceTs) / 60_000.0
            if (luecke > params.maxGapMin) return leer.copy(spanMin = spanne)
        }
        var schlecht = 0.0
        var schlechtSpan = 0.0
        var schlechtErlaubt = 0.0
        var reisst = false
        for (i in letzte.indices) {
            for (j in i + 1 until letzte.size) {
                val dt = (letzte[j].sourceTs - letzte[i].sourceTs) / 60_000.0
                if (dt <= 0.0) continue
                val d = letzte[j].rawBg - letzte[i].rawBg
                val erlaubt = params.allowedDropMgdl(dt)
                if (d + erlaubt < 0.0) reisst = true
                if (d < schlecht) {
                    schlecht = d
                    schlechtSpan = dt
                    schlechtErlaubt = erlaubt
                }
            }
        }
        val netto = letzte.last().rawBg - letzte.first().rawBg
        val hatAufgehoert = netto >= -params.noiseAllowanceMgdl
        return Recent(
            verdict = if (reisst || !hatAufgehoert) Stabilisation.FALLING_BEYOND_TOLERANCE
            else Stabilisation.WITHIN_TOLERANCE,
            worstDropMgdl = schlecht,
            worstDropSpanMin = schlechtSpan,
            spanMin = spanne,
            netMgdl = netto,
            allowedDropMgdl = schlechtErlaubt,
        )
    }

    private fun bestaetigteZyklen(
        series: MeasuredGlucose,
        nowTs: Long,
        params: Params,
    ): Int {
        var zaehler = 1                       // dieser Zyklus ist stabil
        val zeiten = series.points.map { it.sourceTs }.filter { it <= nowTs }
        var i = zeiten.size - 2               // der Punkt VOR dem juengsten
        while (i >= 0 && zaehler < MAX_CONFIRM_LOOKBACK) {
            // DIESELBE vollstaendige Bewertung wie fuer die Gegenwart -
            // inklusive Luecken-, Frische-, Zahlen- und Spannenpruefung.
            // Gezaehlt wird die STABILISIERUNG, nicht das Fensterurteil: ein
            // historischer Rueckgang darf die Bestaetigung nicht abreissen,
            // wenn der juengste Abschnitt laengst ruhig ist.
            if (kern(series, zeiten[i], params).stabilisation != Stabilisation.WITHIN_TOLERANCE) break
            zaehler++
            i--
        }
        return zaehler
    }

    /** Nur fuer Tests und Anzeige: reisst dieser Abschnitt die Toleranz? */
    fun exceedsTolerance(dropMgdl: Double, dtMin: Double, params: Params): Boolean =
        dropMgdl + params.allowedDropMgdl(dtMin) < 0.0 && abs(dtMin) > 0.0
}

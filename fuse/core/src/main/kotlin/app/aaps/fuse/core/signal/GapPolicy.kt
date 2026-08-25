package app.aaps.fuse.core.signal

/**
 * DIE EINE WAHRHEIT ueber die CGM-Luecke, die ein r-Segment beendet
 * (Bauauftrag Toni 25.08. abends).
 *
 * DER ANLASS - zwei Wahrheiten, die auseinanderliefen: die Grenze stand
 * an DREI Orten. `BgiAdjustedSeries.SEGMENT_BREAK_MS` beschnitt die
 * r-Reihe, `FuseSignalSource` fuehrte eine eigene Rueckwaertsschleife
 * mit derselben Konstante - und `ObserverTypes.rSegmentBreakMin` trug
 * ein DAVON UNABHAENGIGES Literal `3.0`. Ein Replay, der nur die
 * Konstante verstellt haette, haette die Reihe verbunden, waehrend der
 * Observer weiter bricht: eine Matrix, die den Messfehler misst statt
 * die Grenze.
 *
 * Deshalb gibt es hier GENAU EINE Groesse. Beide Verbraucher leiten
 * daraus ab, der Export nennt den wirksamen Wert, und ein Replay
 * ueberschreibt nur diese eine Stelle.
 *
 * WAS HIER NICHT HINEINGEHOERT: die uebrigen Bruchgruende haben eigene,
 * fachlich andere Schwellen und bleiben unberuehrt - Sensorwechsel und
 * Kalibrierung (Epochenkante in [SignalWindow]), der unklassifizierte
 * Input-Step, die Zustands-Kontinuitaet (1,5 min) und der volle
 * Neuaufbau (60 min). Diese Policy beantwortet ausschliesslich die
 * Frage: *ab welchem Abstand zweier Messwerte beginnt ein neues
 * r-Segment?*
 */
object GapPolicy {

    /**
     * DER GELOCKTE PRODUKTIONSWERT [ms]. 3 Minuten - unveraendert seit
     * Audit R95 NEU-03.
     *
     * Er ist bewusst eine eigene Konstante und nicht der Default eines
     * Parameters: so bleibt im Code lesbar, WELCHER Wert der
     * produktive ist, auch wenn ein Replay einen anderen fuehrt.
     */
    const val DEFAULT_R_SEGMENT_BREAK_MS: Long = 3 * 60_000L

    /**
     * Der WIRKSAME Wert dieses Laufs. Prozesslokal und ausschliesslich
     * fuer den Offline-Replay gedacht; die Produktion setzt ihn nie.
     *
     * FAIL-CLOSED: ein unbrauchbarer Wert faellt auf den Produktionswert
     * zurueck, statt eine beliebige Luecke zu verbinden. Und nach oben
     * hart gedeckelt - eine Politik, die Stunden verbinden koennte,
     * waere keine Politik mehr, sondern ein Loch.
     */
    @Volatile
    private var overrideMs: Long? = null

    /** Obergrenze fuer den Replay-Override [ms]. Darueber gibt es keine
     *  vertretbare Frage mehr: eine Viertelstunde Funkstille ist ein
     *  anderer Messregime-Abschnitt, kein fortgesetztes Segment. */
    const val MAX_OVERRIDE_MS: Long = 15 * 60_000L

    /** Untergrenze [ms]. Unterhalb einer Minute waere JEDE normale
     *  1-min-Kadenz ein Bruch - das waere kein strengerer Schnitt,
     *  sondern ein dauerhaft totes r. */
    const val MIN_OVERRIDE_MS: Long = 60_000L

    /** Der wirksame Wert - das, wonach BEIDE Verbraucher schneiden. */
    val rSegmentBreakMs: Long
        get() = overrideMs ?: DEFAULT_R_SEGMENT_BREAK_MS

    /** Derselbe Wert in Minuten - fuer den Observer, der in Minuten rechnet. */
    val rSegmentBreakMin: Double
        get() = rSegmentBreakMs / 60_000.0

    /**
     * NUR FUER DEN OFFLINE-REPLAY. `null` stellt den Produktionswert
     * wieder her; jeder Lauf setzt ihn ausdruecklich, damit kein Wert
     * zwischen zwei Laeufen leckt (dieselbe Regel wie fuer die
     * Rig-Hebel).
     *
     * @return der jetzt wirksame Wert [ms] - der Aufrufer kann damit
     *   pruefen, ob sein Wunsch angenommen wurde.
     */
    fun overrideForReplay(ms: Long?): Long {
        // Strengere Werte sind ausdruecklich erlaubt (eine Gegenprobe darf
        // auch nach unten fragen); unbrauchbare fallen auf die Produktion
        // zurueck, statt beliebige Luecken zu verbinden.
        overrideMs = ms?.takeIf { it in MIN_OVERRIDE_MS..MAX_OVERRIDE_MS }
        return rSegmentBreakMs
    }
}

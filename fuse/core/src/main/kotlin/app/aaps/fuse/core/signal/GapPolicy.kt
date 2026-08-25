package app.aaps.fuse.core.signal

/**
 * DIE EINE WAHRHEIT ueber die CGM-Luecke, die ein r-Segment beendet -
 * UNVERAENDERLICH und je Runner injiziert (Bauauftrag Toni 25.08.,
 * korrigiert nach dem Review am selben Abend).
 *
 * DER ANLASS - zwei Wahrheiten, die auseinanderliefen: die Grenze stand
 * an DREI Orten. `BgiAdjustedSeries.SEGMENT_BREAK_MS` beschnitt die
 * r-Reihe, `FuseSignalSource` fuehrte eine eigene Rueckwaertsschleife
 * mit derselben Konstante - und `ObserverTypes.rSegmentBreakMin` trug
 * ein DAVON UNABHAENGIGES Literal `3.0`. Ein Replay-Override nur auf der
 * Konstante haette die Reihe verbunden, waehrend der Observer weiter
 * bricht: eine Matrix, die den Messfehler misst statt die Grenze.
 *
 * DER ZWEITE ANLASS - warum es KEIN globaler Schalter sein darf: der
 * erste Wurf trug einen prozessweiten, veraenderlichen Override. Damit
 * teilen sich alle Runner und alle Tests im selben Prozess EINEN Wert -
 * zwei Matrixlaeufe koennen sich vermischen, und die Reihenfolge der
 * Tests wird bedeutungstragend. Deshalb ist die Politik jetzt ein
 * WERTOBJEKT: der Runner bekommt sie einmal, gibt sie an seine
 * Verbraucher weiter, und ein Replay erzeugt je Variante einen eigenen
 * Runner.
 *
 * WAS HIER NICHT HINEINGEHOERT: die uebrigen Bruchgruende haben eigene,
 * fachlich andere Schwellen und bleiben unberuehrt - Sensorwechsel und
 * Kalibrierung (Epochenkante in [SignalWindow]), der unklassifizierte
 * Input-Step, die Zustands-Kontinuitaet (1,5 min) und der volle
 * Neuaufbau (60 min). Diese Politik beantwortet ausschliesslich die
 * Frage: *ab welchem Abstand zweier Messwerte beginnt ein neues
 * r-Segment?*
 */
@JvmInline
value class GapPolicy private constructor(val rSegmentBreakMs: Long) {

    /** Dieselbe Groesse in Minuten - der Observer rechnet in Minuten. */
    val rSegmentBreakMin: Double get() = rSegmentBreakMs / 60_000.0

    companion object {

        /**
         * DER GELOCKTE PRODUKTIONSWERT [ms]. 3 Minuten - unveraendert
         * seit Audit R95 NEU-03.
         */
        const val DEFAULT_R_SEGMENT_BREAK_MS: Long = 3 * 60_000L

        /** Obergrenze fuer einen Replay-Wert [ms]. Darueber gibt es keine
         *  vertretbare Frage mehr: eine Viertelstunde Funkstille ist ein
         *  anderer Messregime-Abschnitt, kein fortgesetztes Segment. */
        const val MAX_MS: Long = 15 * 60_000L

        /** Untergrenze [ms]. Unterhalb einer Minute waere JEDE normale
         *  1-min-Kadenz ein Bruch - kein strengerer Schnitt, sondern ein
         *  dauerhaft totes r. */
        const val MIN_MS: Long = 60_000L

        /** Die Politik des Geraets. Jeder Pfad ohne ausdrueckliche
         *  Injektion bekommt genau diese. */
        val PRODUCTION = GapPolicy(DEFAULT_R_SEGMENT_BREAK_MS)

        /**
         * Eine Politik fuer den OFFLINE-Replay. Unbrauchbare Werte
         * ergeben die Produktion, statt beliebige Luecken zu verbinden;
         * strengere Werte sind ausdruecklich erlaubt (eine Gegenprobe
         * darf auch nach unten fragen).
         */
        fun of(ms: Long): GapPolicy =
            if (ms in MIN_MS..MAX_MS) GapPolicy(ms) else PRODUCTION
    }
}

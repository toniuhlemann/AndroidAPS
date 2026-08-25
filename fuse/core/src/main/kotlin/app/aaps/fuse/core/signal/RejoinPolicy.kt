package app.aaps.fuse.core.signal

/**
 * DER WIEDEREINSTIEG NACH EINER CGM-FUNKLUECKE - unveraenderlich und je
 * Runner injiziert (Bauauftrag Toni 25.08. abends, nach dem
 * dosierneutralen Reife-Replay).
 *
 * WAS GEMESSEN WURDE: nach einer echten Funkluecke bleibt FUSE ~5-6 min
 * blind, und diese Zeit haengt NICHT von der Lueckenlaenge ab - sie ist
 * die Theil-Sen-Reifebedingung [MaturityPolicy.PRODUCTION] (5 Punkte /
 * 8 Paare, real 6 Punkte). Der Replay ueber 9 echte Luecken ergab fuer
 * 4x3 rund zwei gesparte Minuten bei einem Medianfehler von 0,19 und
 * +0,05 U ueber einen ganzen Tag.
 *
 * WARUM DAS NICHT DIE GLOBALE REIFE AENDERT (Tonis Auflage): 5x8 gilt
 * weiterhin nach Kaltstart, Sensorwechsel, Kalibrierung und
 * Eingangssprung. In genau diesen Faellen ist die kurze Reihe NICHT die
 * Fortsetzung einer bekannten Kurve, sondern ein neues Messregime - eine
 * fruehe Steigung darauf waere eine Behauptung ueber Daten, die es nicht
 * gibt. Die Lockerung gilt ausschliesslich dort, wo die Reihe VOR der
 * Luecke nachweislich existierte und nur der Funk fehlte.
 *
 * DER BODEN, DER NICHT VERHANDELBAR IST: 3x1 waere eine EINZIGE
 * Paarsteigung. Ein Median ueber ein Element ist kein robuster
 * Theil-Sen mehr, sondern der Wert selbst - ohne jede Ausreisserfestigkeit,
 * die den Schaetzer ueberhaupt rechtfertigt. [enabled] weist alles
 * unterhalb 4 Punkten / 3 Paaren ab und liefert [OFF], statt still eine
 * schwaechere Statistik zu erlauben.
 *
 * KEIN `data class`: deren `copy()` waere ein zweiter Bauweg am privaten
 * Konstruktor vorbei und koennte den Boden umgehen.
 */
class RejoinPolicy private constructor(
    /** Ist der Wiedereinstieg ueberhaupt scharf? */
    val enabled: Boolean,
    /** Die gelockerte Reife, die NUR nach einer echten Funkluecke gilt. */
    val maturity: MaturityPolicy,
    /** Laengste Luecke, nach der noch gelockert wird [ms]. Darueber ist zu
     *  viel Kurve unbeobachtet vergangen; dann gilt wieder 5x8. */
    val maxGapMs: Long,
    /** Laengstes Alter des Segments, in dem gelockert wird [ms]. Danach
     *  ist die Reihe entweder ohnehin voll reif, oder es liegt ein
     *  anderes Problem vor als die Luecke. */
    val maxAgeMs: Long,
) {

    override fun toString(): String =
        if (!enabled) "RejoinPolicy(OFF)"
        else "RejoinPolicy(${maturity.tag()}, gap<=${maxGapMs / 60_000}min, age<=${maxAgeMs / 60_000}min)"

    companion object {

        /** Der Boden fuer die gelockerte Reife - s. Klassenkommentar. */
        const val FLOOR_POINTS = 4
        const val FLOOR_SLOPES = 3

        /** Die Kandidatenwahl aus dem Replay vom 25.08.: 4 Punkte, 3 Paare. */
        val DEFAULT_MATURITY: MaturityPolicy = MaturityPolicy.of(FLOOR_POINTS, FLOOR_SLOPES)

        /**
         * Bis 10 min Luecke wird gelockert. Die Grenze stammt aus Tonis
         * Architekturvorgabe und deckt das gemessene Inventar: alle 21
         * echten Signalluecken der Messwoche lagen unter 10 min.
         */
        const val DEFAULT_MAX_GAP_MS: Long = 10 * 60_000L

        /**
         * Und nur innerhalb von 10 min nach dem Segmentbeginn. Danach
         * haette eine normale Kadenz laengst 6 Punkte geliefert; wenn
         * nicht, ist die Kadenz das Problem und nicht die Luecke.
         */
        const val DEFAULT_MAX_AGE_MS: Long = 10 * 60_000L

        /** Absolute Obergrenzen, damit eine Konfiguration die Frage nicht
         *  in ein anderes Regime verschieben kann. */
        const val MAX_GAP_CEILING_MS: Long = 20 * 60_000L
        const val MAX_AGE_CEILING_MS: Long = 20 * 60_000L

        /** Aus. Der Vorgabewert jedes Pfades, der nichts sagt. */
        val OFF = RejoinPolicy(false, MaturityPolicy.PRODUCTION, 0L, 0L)

        /**
         * Scharfer Wiedereinstieg. Alles, was den Boden unterschreitet
         * oder die Deckel ueberschreitet, ergibt [OFF] - ausdruecklich
         * NICHT eine stillschweigend zurechtgebogene Politik.
         */
        fun enabled(
            maturity: MaturityPolicy = DEFAULT_MATURITY,
            maxGapMs: Long = DEFAULT_MAX_GAP_MS,
            maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        ): RejoinPolicy {
            if (maturity.minPoints < FLOOR_POINTS || maturity.minSlopes < FLOOR_SLOPES) return OFF
            // Eine Lockerung, die strenger als die Produktion waere, ist
            // keine - sie gehoerte in die Produktionskonstanten.
            if (maturity.minPoints > MaturityPolicy.PRODUCTION.minPoints &&
                maturity.minSlopes > MaturityPolicy.PRODUCTION.minSlopes
            ) return OFF
            // Ein Vorlauf-Fenster gehoert in den Replay, nicht ins Produkt:
            // hier wuerde es die Lockerung zeitabhaengig machen, ohne dass
            // es jemand am Geraet setzen koennte.
            if (maturity.activeFromTs != 0L) return OFF
            if (maxGapMs !in 60_000L..MAX_GAP_CEILING_MS) return OFF
            if (maxAgeMs !in 60_000L..MAX_AGE_CEILING_MS) return OFF
            return RejoinPolicy(true, maturity, maxGapMs, maxAgeMs)
        }
    }
}

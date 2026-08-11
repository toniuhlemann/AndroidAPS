package app.aaps.fuse.core.controller

import kotlin.math.max
import kotlin.math.min

/**
 * DER STOERUNGSBESTAND - die Empfangsseite nach dem Markerprivileg.
 *
 * WAS ER SCHLIESST, gemessen am 11.08.2026: nach dem 15-Minuten-Prime-Fenster
 * gab es 41 Zyklen am Stueck NICHTS, waehrend der Zucker von 102 auf 168 stieg
 * und `r` bis 3,3 mg/dl/min zeigte. Nicht weil die Mahlzeit unerkannt war -
 * `mealWindow` stand auf true, die Ratio auf vollen 0,35 -, sondern weil die
 * Sicherheitsbahn unter dem Guard-Boden lag. Sie lag dort, WEIL gerade 3,0 U
 * hineingegangen waren. Die Fruehdosis macht FUSE blind fuer genau den
 * Anstieg, den sie vorwegnehmen sollte.
 *
 * WARUM KEINE DRITTE HUELLE, und das ist Tonis Einwand, der die Richtung
 * bestimmt hat: ein neuer Mengentopf hinter denselben Gates wuerde genauso
 * blockieren - in 45 der geblockten Zyklen lag auch die HAUPTbahn unter 70.
 * Was fehlt, ist kein Budget, sondern eine andere Stoerungsannahme. Deshalb
 * speist dieser Bestand [ConditionalDrive], also die BAHN, und laeuft danach
 * unveraendert durch alle Mengengrenzen.
 *
 * `r` IST DER NACHWEIS, NICHT DIE GELDQUELLE. Das ist der tragende Satz, und
 * er kommt aus zwei Messungen:
 *
 *  - `r` haengt an einem 18-min-Theil-Sen und bleibt nach einer Wende im
 *    MEDIAN 10 Minuten positiv (ueber 160 Wenden im Trail gemessen, Maximum
 *    36 min). Ein r-gespeister Bestand finanzierte also zehn Minuten lang eine
 *    Absorption, die schon vorbei ist.
 *  - Bei 60 Zyklen pro Stunde wuerde dieselbe beobachtete Stoerung sechzigmal
 *    voll verbucht. Der Bahn-Deckel in [ConditionalDrive] begrenzt die Hebung
 *    pro Schnappschuss, ueber die Zeit begrenzt er nichts.
 *
 * Geldquelle sind deshalb ausschliesslich NEUE Messinformationen: der Zuwachs
 * der BGI-bereinigten Reihe seit dem zuletzt verbuchten `sourceTs`, jeder
 * Messpunkt genau einmal. Das schliesst beide Fehler zugleich aus.
 *
 * WARUM DAS ALGEBRAISCH SAUBER IST (Tonis Herleitung, nachdem ich sie zuerst
 * falsch hatte): naeherungsweise gilt `dq1 = Stoerung - Insulinwirkung` und
 * `dadjusted = dq1 + Insulinwirkung = Stoerung`. Eine eigene Dosis erzeugt
 * also KEINEN eigenen Zufluss - ihr positiver BGI-Term hebt genau den Abfall
 * auf, den sie selbst in q1 verursacht. Nur eine weiterhin vorhandene Stoerung
 * laesst die bereinigte Reihe steigen.
 *
 * ACHTUNG, GILT NUR IM GESCHLOSSENEN KREIS: auf dem Zwei-Geraete-Testaufbau
 * fehlt das real wirkende Insulin des Produktivgeraets in der Bereinigung,
 * waehrend FUSEs eigene virtuelle Dosen darin stehen, ohne zu wirken. Dort
 * kuerzt sich nichts, und der Bestand ist quantitativ unbrauchbar (im Lauf vom
 * 11.08. um grob zwei Einheiten Wirkung zu niedrig). Struktur ja, Zahlen nein.
 */
object EvidenceStock {

    /**
     * HARTES MAXIMALENDE der Episode [min].
     *
     * Der Bestand ersetzt den Timer NICHT, er ergaenzt ihn - Tonis Auflage,
     * und sie ist noetig: ein Bestand, der sich aus `Δadjusted` speist, kann
     * bei dauerhaft steigender Bahn unbegrenzt nachwachsen. Gegenregulation,
     * Sensordrift, eine schlechte Infusionsstelle - alles drei sieht wie
     * Stoerung aus und ist keine Mahlzeit. Der Deckel ist der Notaus, nicht
     * die Regel.
     *
     * 4 Stunden, weil der gemessene Lauf vom 11.08. nach 205 Minuten noch
     * lief. Kuerzer waere gegen die Messung, laenger ohne Beleg.
     */
    const val MAX_EPISODE_MIN = 240

    /**
     * Verfall des Bestands [min] - die "begrenzte zeitliche Nachwirkung".
     *
     * KUERZER ALS DER SCHAETZERVERZUG, und das ist kein Zufall: bliebe ein
     * Bestand laenger stehen als die 10 Minuten, die `r` nach einer Wende
     * nachlaeuft, waere er keine Begrenzung, sondern eine Verlaengerung genau
     * der Phase, in der die Absorption schon vorbei ist.
     */
    const val DECAY_MIN = 8

    /** Ueber welchen Horizont der Restbestand als Antrieb ausgeschuettet wird
     *  [min]. Dieselbe Form wie [MarkerScope.declaredAbsorptionDriveMgdlPerMin]
     *  - Bestand in mg/dl geteilt durch ein Fenster ergibt mg/dl/min. */
    const val RELEASE_WINDOW_MIN = 30

    /** Ein Segmentbruch macht die Differenz zweier bereinigter Punkte
     *  bedeutungslos: `cumulativeBgi` startet je Segment neu bei 0. Der
     *  Zufluss muss dann aussetzen, nicht schaetzen. */
    data class State(
        /** Verbleibende, noch nicht mit Insulin bezahlte Stoerung [mg/dl]. */
        val stockMgdl: Double = 0.0,
        /** `sourceTs` des zuletzt VERBUCHTEN Messpunkts. 0 = noch keiner. */
        val lastAcceptedTs: Long = 0L,
        /** Wert der bereinigten Reihe an [lastAcceptedTs] - der Bezugspunkt
         *  fuer den naechsten Zuwachs. */
        val lastAdjusted: Double = 0.0,
        /** Beginn der Episode fuer [MAX_EPISODE_MIN]. 0 = keine Episode. */
        val episodeStartTs: Long = 0L,
        /** Segment, in dem [lastAcceptedTs] liegt. Wechselt es, ist die
         *  Differenz ueber die Grenze hinweg wertlos. */
        val segmentStartTs: Long = 0L,
    )

    /** Warum in diesem Zyklus KEIN Zufluss stattfand - fuer den Export, damit
     *  ein ausbleibender Kredit von einem nicht angeforderten unterscheidbar
     *  ist. */
    enum class NoInflow {
        NO_EPISODE,
        EPISODE_EXPIRED,
        HEALTH_NOT_READY,
        MEASURED_LOW,
        SEGMENT_BREAK,
        NO_NEW_SAMPLE,
        DRIVE_NOT_POSITIVE,
        NO_RISE,
    }

    data class Input(
        val nowMs: Long,
        /** Zeitstempel des juengsten Messpunkts. */
        val sourceTs: Long,
        /** Wert der BGI-bereinigten Reihe an [sourceTs]. */
        val adjusted: Double,
        /** Beginn des juengsten lueckenfreien Segments. */
        val segmentStartTs: Long,
        /**
         * KONSERVATIVE UNTERGRENZE des Antriebs, nicht sein Mittelwert -
         * Tonis Auflage. `DriveEstimate.lowerMgdlPerMin`. Sie ist das
         * EVIDENZ-TOR: darf ueberhaupt Bestand entstehen? Der BETRAG des
         * Zuflusses kommt trotzdem aus dem Messzuwachs, nicht aus ihr.
         */
        val driveLowerMgdlPerMin: Double?,
        val healthReady: Boolean,
        val measuredLow: Boolean,
        /** Ob eine Mahlzeitenepisode laeuft (Marker oder bestaetigter
         *  Anstieg). Ohne Episode kein Bestand. */
        val episodeActive: Boolean,
        /**
         * In DIESEM Zyklus verbindlich zugesagtes Insulin [U] - publiziert
         * oder transportverbindlich, NICHT erst als sichtbares Treatment.
         * Sofort abgezogen: ein Abzug, der auf die Sichtbarkeit wartet,
         * finanziert in der Zwischenzeit dieselbe Stoerung weiter (gemessene
         * Sichtbarkeitslatenz p90 56 s, max 854 s).
         */
        val committedU: Double,
        val isfMgdlPerU: Double,
    )

    data class Result(
        val state: State,
        /** Antrieb fuer [ConditionalDrive] [mg/dl/min]. 0 = kein Kredit. */
        val creditMgdlPerMin: Double,
        /** Was in diesem Zyklus zugeflossen ist [mg/dl] - fuer den Export. */
        val inflowMgdl: Double,
        /** Warum nichts zufloss; `null` = es floss etwas zu. */
        val noInflow: NoInflow?,
    )

    fun step(prev: State, input: Input): Result {
        val isf = input.isfMgdlPerU

        // ---- Episode: Ende bedeutet Bestand WEG, nicht eingefroren --------
        if (!input.episodeActive)
            return Result(State(), 0.0, 0.0, NoInflow.NO_EPISODE)

        val start = if (prev.episodeStartTs > 0L) prev.episodeStartTs else input.nowMs
        if ((input.nowMs - start) / 60_000L >= MAX_EPISODE_MIN)
            return Result(State(), 0.0, 0.0, NoInflow.EPISODE_EXPIRED)

        // ---- Widerruf VOR allem anderen ------------------------------------
        // Ein gemessenes Tief oder ein kaputtes Signal beendet den Kredit
        // sofort und vollstaendig. Nicht abklingen lassen: der Bestand ist
        // eine Behauptung ueber die naechsten Minuten, und beide Faelle sagen,
        // dass diese Behauptung gerade nicht traegt.
        if (input.measuredLow)
            return Result(prev.copy(stockMgdl = 0.0), 0.0, 0.0, NoInflow.MEASURED_LOW)
        if (!input.healthReady)
            return Result(prev.copy(stockMgdl = 0.0), 0.0, 0.0, NoInflow.HEALTH_NOT_READY)

        // ---- Verfall auf dem Bestand des letzten Zyklus --------------------
        val dtMin = if (prev.lastAcceptedTs > 0L)
            max(0.0, (input.sourceTs - prev.lastAcceptedTs) / 60_000.0) else 0.0
        val nachVerfall = max(0.0, prev.stockMgdl - prev.stockMgdl * (dtMin / DECAY_MIN))

        // ---- Zufluss: NUR neue, nicht ueberlappende Messinformation --------
        val neuesSegment = prev.segmentStartTs != input.segmentStartTs
        val neuerPunkt = input.sourceTs > prev.lastAcceptedTs
        val torOffen = (input.driveLowerMgdlPerMin ?: 0.0) > 0.0

        var grund: NoInflow? = null
        var zufluss = 0.0
        when {
            prev.lastAcceptedTs <= 0L || neuesSegment -> grund = NoInflow.SEGMENT_BREAK
            !neuerPunkt                               -> grund = NoInflow.NO_NEW_SAMPLE
            !torOffen                                 -> grund = NoInflow.DRIVE_NOT_POSITIVE
            else                                      -> {
                val delta = input.adjusted - prev.lastAdjusted
                if (delta > 0.0) zufluss = delta else grund = NoInflow.NO_RISE
            }
        }

        // EIN NEGATIVER SCHRITT NIMMT SCHNELLER WEG, ALS EIN POSITIVER GIBT.
        // Die bereinigte Reihe faellt, wenn die Stoerung kleiner ist als
        // angenommen - dann war der Bestand zu hoch, und ihn nur nicht weiter
        // zu fuellen genuegt nicht. Faktor 2, weil die Fehlerrichtung
        // eindeutig ist: zu viel Bestand kostet Insulin, zu wenig kostet
        // Wartezeit.
        val rueckgang = if (grund == NoInflow.NO_RISE)
            2.0 * (prev.lastAdjusted - input.adjusted) else 0.0

        // ---- Abzug: zugesagtes Insulin, sofort ------------------------------
        val abzug = max(0.0, input.committedU) * max(0.0, isf)

        val neu = max(0.0, nachVerfall + zufluss - rueckgang - abzug)

        val zustand = State(
            stockMgdl = neu,
            lastAcceptedTs = if (neuerPunkt) input.sourceTs else prev.lastAcceptedTs,
            lastAdjusted = if (neuerPunkt) input.adjusted else prev.lastAdjusted,
            episodeStartTs = start,
            segmentStartTs = input.segmentStartTs,
        )
        return Result(
            state = zustand,
            // Der Restbestand wird ueber ein begrenztes Fenster ausgeschuettet -
            // nicht auf einmal. Sonst waere ein Bestand von 30 mg/dl ein
            // Antrieb von 30 mg/dl/min, also physiologisch absurd.
            creditMgdlPerMin = if (neu <= 0.0) 0.0 else min(neu / RELEASE_WINDOW_MIN, neu),
            inflowMgdl = zufluss,
            noInflow = grund,
        )
    }
}

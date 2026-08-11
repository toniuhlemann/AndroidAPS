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
 * WARUM KEINE DRITTE HUELLE: ein neuer Mengentopf hinter denselben Gates
 * wuerde genauso blockieren - in 45 der geblockten Zyklen lag auch die
 * HAUPTbahn unter 70. Was fehlt, ist kein Budget, sondern eine andere
 * Stoerungsannahme. Dieser Bestand speist deshalb [ConditionalDrive], also die
 * BAHN, und laeuft danach unveraendert durch alle Mengengrenzen.
 *
 * `r` IST DER NACHWEIS, NICHT DIE GELDQUELLE. Zwei Messungen tragen das:
 *
 *  - `r` haengt an einem 18-min-Theil-Sen und bleibt nach einer Wende im
 *    MEDIAN 10 Minuten positiv (160 Wenden im Trail, Maximum 36 min). Ein
 *    r-gespeister Bestand finanzierte also zehn Minuten lang eine Absorption,
 *    die schon vorbei ist.
 *  - Bei 60 Zyklen pro Stunde wuerde dieselbe beobachtete Stoerung sechzigmal
 *    voll verbucht. Der Bahn-Deckel in [ConditionalDrive] begrenzt die Hebung
 *    pro Schnappschuss, ueber die Zeit begrenzt er nichts.
 *
 * Geldquelle sind deshalb ausschliesslich NEUE Messinformationen: der Zuwachs
 * der BGI-bereinigten Reihe seit dem zuletzt verbuchten `sourceTs`, jeder
 * Messpunkt genau einmal.
 *
 * WARUM DAS ALGEBRAISCH SAUBER IST (Tonis Herleitung, nachdem ich sie zuerst
 * falsch hatte): naeherungsweise gilt `dq1 = Stoerung - Insulinwirkung` und
 * `dadjusted = dq1 + Insulinwirkung = Stoerung`. Eine eigene Dosis erzeugt
 * KEINEN eigenen Zufluss - ihr positiver BGI-Term hebt genau den Abfall auf,
 * den sie selbst in q1 verursacht.
 *
 * ACHTUNG, GILT NUR IM GESCHLOSSENEN KREIS: auf dem Zwei-Geraete-Testaufbau
 * fehlt das real wirkende Insulin des Produktivgeraets in der Bereinigung,
 * waehrend FUSEs eigene virtuelle Dosen darin stehen, ohne zu wirken. Dort
 * kuerzt sich nichts, und der Bestand ist quantitativ unbrauchbar (im Lauf vom
 * 11.08. um grob zwei Einheiten Wirkung zu niedrig). Struktur ja, Zahlen nein.
 *
 * DREI VERTRAEGE SIND HIER STRUKTURELL ERZWUNGEN statt kommentiert, weil ein
 * Aufrufer sie sonst still falsch bedienen kann - und solche Fehler findet
 * kein Test, der die Absicht des Aufrufers teilt:
 *
 *  1. [Input.episodeCommittedU] ist KUMULATIV. Der Abzug ist der Zuwachs
 *     gegenueber dem zuletzt gesehenen Stand, also genau einmal - ein
 *     kumulativer Wert kann nicht versehentlich jeden Zyklus neu abgezogen
 *     werden, und ein Zuwachs nicht versehentlich verlorengehen.
 *  2. [Input.episodeId] traegt die Episoden-IDENTITAET. Der Deckel laeuft ab
 *     dem ersten Zyklus DIESER Episode; eine zweite oder dritte Welle
 *     reaktiviert sie, startet die Uhr aber nicht neu.
 *  3. Der Verfall haengt an der WANDUHR, nicht an Messpunkten. Bei einer
 *     Signalluecke baut der Bestand weiter ab, waehrend Zufluss UND Ausgabe
 *     gesperrt sind; das neue Segment setzt nur die Messbasis neu.
 */
object EvidenceStock {

    /**
     * DIE VIER STELLSCHRAUBEN - benannt und einzeln uebergebbar, damit ein
     * Replay sie durchfahren kann. Als `const` waeren sie unpruefbar gewesen
     * und haetten wie Herleitungen ausgesehen; drei von ihnen sind das nicht.
     *
     * ALPHA-HYPOTHESEN, ausdruecklich: die Defaults sind plausibel begruendet,
     * aber nicht optimiert. Sie gehoeren in einen Sweep, bevor sie in einem
     * geschlossenen Kreis laufen.
     */
    data class Config(
        /**
         * HARTER SICHERHEITSDECKEL der Episode [min] - ausdruecklich KEIN
         * Modell einer typischen Mahlzeitendauer.
         *
         * Ein Bestand, der sich aus `Δadjusted` speist, kann bei dauerhaft
         * steigender Bahn unbegrenzt nachwachsen. Gegenregulation,
         * Sensordrift, eine schlechte Infusionsstelle - alles drei sieht wie
         * Stoerung aus und ist keine Mahlzeit. Der Deckel ist der Notaus.
         *
         * 240 als Startwert, weil EIN gemessener Lauf (11.08.) nach 205
         * Minuten noch lief. Aus einer Mahlzeit laesst sich keine Dauer
         * verallgemeinern - der Wert sagt "so lange darf es hoechstens
         * gehen", nicht "so lange dauert eine Mahlzeit".
         */
        val maxEpisodeMin: Int = 240,
        /**
         * Verfall des Bestands [min] - die begrenzte zeitliche Nachwirkung.
         *
         * Die einzige der vier Zahlen mit einer echten Herleitung: `r` bleibt
         * nach einer Wende im Median 10 Minuten positiv (160 Wenden im Trail).
         * Ein laenger stehender Bestand waere keine Begrenzung, sondern eine
         * Verlaengerung genau der Phase, in der die Absorption vorbei ist.
         */
        val decayMin: Int = 8,
        /** Ueber welchen Horizont der Restbestand als Antrieb ausgeschuettet
         *  wird [min]. Bestand in mg/dl geteilt durch ein Fenster ergibt
         *  mg/dl/min - dieselbe Form wie die erklaerte Absorption. */
        val releaseWindowMin: Int = 30,
        /**
         * Wie stark ein Rueckgang der bereinigten Reihe den Bestand abraeumt.
         *
         * GESETZT, NICHT HERGELEITET. Die Richtung ist begruendet - faellt die
         * Reihe, war die Stoerung kleiner als angenommen, und nur nicht weiter
         * zu fuellen genuegt dann nicht; zu viel Bestand kostet Insulin, zu
         * wenig kostet Wartezeit. Der BETRAG 2,0 ist eine Alpha-Hypothese und
         * gehoert in den Sweep.
         */
        val declineFactor: Double = 2.0,
    )

    data class State(
        /** Verbleibende, noch nicht mit Insulin bezahlte Stoerung [mg/dl]. */
        val stockMgdl: Double = 0.0,
        /** Identitaet der Episode, zu der dieser Bestand gehoert. */
        val episodeId: Long = 0L,
        /** Beginn DIESER Episode - Bezug fuer [Config.maxEpisodeMin]. Wird bei
         *  einer zweiten Welle NICHT neu gesetzt. */
        val episodeStartTs: Long = 0L,
        /** `sourceTs` des zuletzt VERBUCHTEN Messpunkts. */
        val lastAcceptedTs: Long = 0L,
        /** Wert der bereinigten Reihe an [lastAcceptedTs] - Bezug fuer den
         *  naechsten Zuwachs. */
        val lastAdjusted: Double = 0.0,
        /** Segment, in dem [lastAcceptedTs] liegt. Wechselt es, ist die
         *  Differenz ueber die Grenze hinweg wertlos - `cumulativeBgi` startet
         *  je Segment neu bei 0. */
        val segmentStartTs: Long = 0L,
        /** Wanduhr-Bezug des Verfalls. Getrennt von [lastAcceptedTs], damit
         *  eine Signalluecke den Bestand weiter abbaut statt ihn
         *  einzufrieren. */
        val lastDecayTs: Long = 0L,
        /** Zuletzt gesehener KUMULATIVER Abgabestand der Episode [U]. Die
         *  Differenz ist der Abzug - dadurch genau einmal. */
        val lastCommittedU: Double = 0.0,
    )

    /** Warum in diesem Zyklus KEIN Zufluss stattfand - fuer den Export, damit
     *  ein ausbleibender Kredit von einem nicht angeforderten unterscheidbar
     *  ist und ein spaeteres Nullfenster nicht faelschlich dem Guard
     *  zugeschrieben wird. */
    enum class NoInflow {
        NO_EPISODE,
        EPISODE_EXPIRED,
        /** Der persistierte Zustand war beim Start unklar. Fail-closed, weil
         *  der Bestand ausschliesslich eine ZUSAETZLICHE Erlaubnis ist - der
         *  gewoehnliche, evidenzfreie Korrekturpfad laeuft unveraendert
         *  weiter. Eigener Grund, damit das Nullfenster zuordenbar bleibt. */
        EVIDENCE_STATE_UNKNOWN,
        HEALTH_NOT_READY,
        MEASURED_LOW,
        SEGMENT_BREAK,
        NO_NEW_SAMPLE,
        DRIVE_NOT_POSITIVE,
        NO_RISE,
    }

    data class Input(
        val nowMs: Long,
        val sourceTs: Long,
        /** Wert der BGI-bereinigten Reihe an [sourceTs]. */
        val adjusted: Double,
        /** Beginn des juengsten lueckenfreien Segments. */
        val segmentStartTs: Long,
        /**
         * KONSERVATIVE UNTERGRENZE des Antriebs, nicht sein Mittelwert.
         * `DriveEstimate.lowerMgdlPerMin`. Sie ist das EVIDENZ-TOR: darf
         * ueberhaupt Bestand entstehen? Der BETRAG kommt trotzdem aus dem
         * Messzuwachs, nicht aus ihr.
         */
        val driveLowerMgdlPerMin: Double?,
        val healthReady: Boolean,
        val measuredLow: Boolean,
        /**
         * Identitaet der laufenden Mahlzeitenepisode; 0 = keine.
         *
         * NICHT ein Bit "aktiv": ein Wellental darf die Episode nicht beenden
         * und die naechste Welle nicht als neue Episode starten - sonst
         * begaenne der Deckel von vorn. Bleibt der Wert gleich, ist es
         * dieselbe Episode, auch nach einer Ruhephase.
         */
        val episodeId: Long,
        /**
         * KUMULATIV in dieser Episode verbindlich zugesagtes Insulin [U] -
         * publiziert oder transportverbindlich, NICHT erst als sichtbares
         * Treatment (gemessene Sichtbarkeitslatenz p90 56 s, max 854 s).
         *
         * JEDE FUSE-DOSIS DER EPISODE, nicht nur die des Empfaengers: Prime,
         * Onset, Rest-Zaehler und die gewoehnliche Korrektur wirken alle gegen
         * DIESELBE Stoerung. Keine Doppelanrechnung - die Huellen begrenzen,
         * WIEVIEL ein Kanal geben darf, der Bestand misst, WIEVIEL Stoerung
         * noch unbezahlt ist.
         *
         * KUMULATIV und nicht als Zuwachs, damit der Abzug HIER gebildet wird:
         * ein kumulativer Wert kann nicht versehentlich jeden Zyklus erneut
         * abgezogen werden, und ein Zuwachs nicht versehentlich verlorengehen.
         */
        val episodeCommittedU: Double,
        val isfMgdlPerU: Double,
        /** Ob der persistierte Bestand nach einem Neustart als gueltig gelten
         *  darf. `false` sperrt den Kredit - s.
         *  [NoInflow.EVIDENCE_STATE_UNKNOWN]. */
        val persistedStateKnown: Boolean = true,
    )

    data class Result(
        val state: State,
        /** Antrieb fuer [ConditionalDrive] [mg/dl/min]. 0 = kein Kredit. */
        val creditMgdlPerMin: Double,
        /** Was in diesem Zyklus zugeflossen ist [mg/dl]. */
        val inflowMgdl: Double,
        /** Warum nichts zufloss; `null` = es floss etwas zu. */
        val noInflow: NoInflow?,
    )

    fun step(prev: State, input: Input, cfg: Config = Config()): Result {
        if (input.episodeId <= 0L)
            return Result(State(), 0.0, 0.0, NoInflow.NO_EPISODE)
        if (!input.persistedStateKnown)
            return Result(State(), 0.0, 0.0, NoInflow.EVIDENCE_STATE_UNKNOWN)

        // Ein Episodenwechsel setzt alles zurueck - eine ANDERE Mahlzeit erbt
        // weder Bestand noch Uhr noch Abgabestand.
        val gleiche = prev.episodeId == input.episodeId
        val basis = if (gleiche) prev else State(episodeId = input.episodeId)
        val start = if (basis.episodeStartTs > 0L) basis.episodeStartTs else input.nowMs

        // DER DECKEL LAEUFT AB DEM URSPRUNG. Eine zweite oder dritte Welle
        // reaktiviert die Episode, sie startet die Uhr nicht neu.
        if ((input.nowMs - start) / 60_000L >= cfg.maxEpisodeMin)
            return Result(
                State(episodeId = input.episodeId, episodeStartTs = start),
                0.0, 0.0, NoInflow.EPISODE_EXPIRED,
            )

        // ---- Verfall auf der WANDUHR, vor jeder Fallunterscheidung ---------
        // Auch waehrend einer Luecke oder eines Widerrufs: ein Bestand, der
        // stehenbleibt, waere eine Behauptung, die nicht altert.
        val dtMin = if (basis.lastDecayTs > 0L)
            max(0.0, (input.nowMs - basis.lastDecayTs) / 60_000.0) else 0.0
        val nachVerfall = max(0.0, basis.stockMgdl * (1.0 - dtMin / cfg.decayMin))

        // ---- Abzug: kumulativer Zuwachs, also genau einmal -----------------
        val zuwachsU = max(0.0, input.episodeCommittedU - basis.lastCommittedU)
        val abzug = zuwachsU * max(0.0, input.isfMgdlPerU)

        // Buchhaltung IMMER fortschreiben - auch bei Widerruf. Sonst wuerde
        // derselbe kumulative Abgabestand danach ein zweites Mal abgezogen.
        val gemerkt = basis.copy(
            episodeId = input.episodeId,
            episodeStartTs = start,
            lastDecayTs = input.nowMs,
            lastCommittedU = max(basis.lastCommittedU, input.episodeCommittedU),
        )

        if (input.measuredLow)
            return Result(gemerkt.copy(stockMgdl = 0.0), 0.0, 0.0, NoInflow.MEASURED_LOW)
        if (!input.healthReady)
            return Result(gemerkt.copy(stockMgdl = 0.0), 0.0, 0.0, NoInflow.HEALTH_NOT_READY)

        // ---- Segmentbruch: Ausgabe UND Zufluss gesperrt, Verfall laeuft ----
        // Das neue Segment setzt nur die Messbasis; ueber die Luecke wird
        // keine Differenz gebildet - `cumulativeBgi` startet dort neu bei 0.
        if (basis.segmentStartTs != input.segmentStartTs || basis.lastAcceptedTs <= 0L)
            return Result(
                gemerkt.copy(
                    stockMgdl = max(0.0, nachVerfall - abzug),
                    lastAcceptedTs = input.sourceTs,
                    lastAdjusted = input.adjusted,
                    segmentStartTs = input.segmentStartTs,
                ),
                creditMgdlPerMin = 0.0,          // gesperrt, nicht bloss leer
                inflowMgdl = 0.0,
                noInflow = NoInflow.SEGMENT_BREAK,
            )

        // ---- Zufluss: nur NEUE, nicht ueberlappende Messinformation --------
        val neuerPunkt = input.sourceTs > basis.lastAcceptedTs
        val torOffen = (input.driveLowerMgdlPerMin ?: 0.0) > 0.0
        var grund: NoInflow? = null
        var zufluss = 0.0
        when {
            !neuerPunkt -> grund = NoInflow.NO_NEW_SAMPLE
            !torOffen   -> grund = NoInflow.DRIVE_NOT_POSITIVE
            else        -> {
                val delta = input.adjusted - basis.lastAdjusted
                if (delta > 0.0) zufluss = delta else grund = NoInflow.NO_RISE
            }
        }

        // Ein negativer Schritt nimmt schneller weg, als ein positiver gibt -
        // der FAKTOR ist gesetzt, nicht hergeleitet (s. Config.declineFactor).
        val rueckgang = if (grund == NoInflow.NO_RISE)
            cfg.declineFactor * (basis.lastAdjusted - input.adjusted) else 0.0

        val neu = max(0.0, nachVerfall + zufluss - rueckgang - abzug)
        return Result(
            state = gemerkt.copy(
                stockMgdl = neu,
                lastAcceptedTs = if (neuerPunkt) input.sourceTs else basis.lastAcceptedTs,
                lastAdjusted = if (neuerPunkt) input.adjusted else basis.lastAdjusted,
                segmentStartTs = input.segmentStartTs,
            ),
            // Der Restbestand wird ueber ein begrenztes Fenster ausgeschuettet,
            // nicht auf einmal: 30 mg/dl Bestand duerfen kein Antrieb von
            // 30 mg/dl/min werden.
            creditMgdlPerMin = if (neu <= 0.0) 0.0 else min(neu / cfg.releaseWindowMin, neu),
            inflowMgdl = zufluss,
            noInflow = grund,
        )
    }
}

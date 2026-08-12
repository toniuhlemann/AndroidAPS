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
         * ABSOLUTER SICHERHEITSDECKEL der Episode [min] - der NOTAUS, und
         * ausdruecklich kein Modell einer Mahlzeitendauer.
         *
         * Ein Bestand, der sich aus `Δadjusted` speist, kann bei dauerhaft
         * steigender Bahn unbegrenzt nachwachsen. Gegenregulation,
         * Sensordrift, eine schlechte Infusionsstelle - alles drei sieht wie
         * Stoerung aus und ist keine Mahlzeit.
         *
         * WARUM 360 UND NICHT 240 (Tonis Einwand 12.08.): die 240 trugen
         * ZWEI Rollen zugleich - Notaus UND faktisches Mahlzeitenende. Als
         * Ende sind sie falsch in beide Richtungen. Ein Snack ist nach ein
         * bis zwei Stunden durch und haette bis 240 weiterlizenziert; eine
         * fett- oder proteinreiche Mahlzeit kann zwischen der dritten und
         * fuenften Stunde eine zweite Welle tragen, und die faellt aus dem
         * 240er-Fenster heraus. Die Literatur zu Fett/Protein nennt
         * zusaetzlichen Bedarf ueber drei bis fuenf Stunden mit
         * Auslaeufern darueber hinaus, und vor allem: die Streuung zwischen
         * Personen und Mahlzeiten ist gross. Eine feste Zahl kann das nicht
         * treffen.
         *
         * Die DAUER regelt deshalb nicht dieser Wert, sondern der Bestand
         * selbst ueber [Phase]: ohne Evidenz faellt die Episode von allein
         * auf DORMANT und gibt keinen Kredit mehr. Der Deckel sagt nur noch,
         * wie lange sie ueberhaupt WIEDERERKENNBAR bleiben darf.
         */
        val maxEpisodeMin: Int = 360,
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

        /**
         * UNTER DIESEM BESTAND IST DIE EPISODE SCHLAFEND [mg/dl].
         *
         * Ohne diese Schwelle gaebe es [Phase.DORMANT] gar nicht: der Verfall
         * ist MULTIPLIKATIV (`stock * (1 - dt/decayMin)`) und erreicht die
         * Null nie. Nach einer halben Stunde Flaute blieben rechnerisch 0,5
         * mg/dl stehen, die Episode hiesse weiter ACTIVE, und der Snack waere
         * genau nicht erledigt - der Fehler, den die drei Zustaende beheben
         * sollen, waere in anderer Gestalt zurueck.
         *
         * 1,0 mg/dl entspricht bei ISF 90 rund 0,011 U - also etwa 22 % eines
         * 0,05-U-Pumpenschritts. (Tonis Korrektur 12.08.: ich hatte zuvor durch
         * den Pumpenschritt statt durch ISF geteilt und "ein Zwanzigstel"
         * geschrieben.) Die Schwelle bleibt damit unter einem Schritt, ist aber
         * KEINE vernachlaessigbare Groesse - sie ist gewaehlt, damit DORMANT
         * ueberhaupt erreichbar ist, und gehoert in den Sweep.
         *
         * Es ist ein ABSCHNEIDEN, keine Rundung: der Rest wird verworfen, nicht
         * aufgehoben.
         */
        val stockFloorMgdl: Double = 1.0,

        /**
         * HARTE PLAUSIBILITAETSGRENZE des persistierten Bestands [mg/dl].
         *
         * "endlich und >= 0" genuegt nicht, seit der Bestand PERSISTENT ist und
         * damit eine Insulinfreigabe ueber Prozessgrenzen traegt (Toni 12.08.):
         * ein Wert von 5.000 waere formal gueltig und ergaebe nach dem Neustart
         * sofort einen Kredit von 166 mg/dl/min.
         *
         * EMPIRISCH BEGRUENDETE PLAUSIBILITAETSGRENZE, nicht hergeleitet
         * (Tonis Praezisierung 12.08. - ich hatte "hergeleitet" geschrieben,
         * und das stimmt nur fuer einen Teil): HERGELEITET ist allein die
         * Gleichgewichtsform `Zuflussrate x decayMin`. Die 5 mg/dl/min als
         * Obergrenze der Stoerung und der Faktor fuenf sind Alpha-Annahmen aus
         * einem einzigen Lauf (11.08., r bis 3,3).
         *
         * Mit decayMin 8 ergibt das rund 40 mg/dl; 200 ist das Fuenffache -
         * hoch genug, dass keine echte Mahlzeit dagegen laeuft, niedrig genug,
         * dass ein korrupter Wert auffaellt. Vertretbar ist die Unschaerfe,
         * weil eine Ueberschreitung fail-closed wirkt: sie fuehrt zu WENIGER
         * Kredit, nie zu mehr. Gehoert in den Sweep.
         *
         * Ueberschreitung wird NICHT geklemmt, sondern gilt als unmoeglicher
         * Zustand: klemmen hiesse, eine korrupte Datei stillschweigend in eine
         * gueltige Freigabe zu verwandeln.
         */
        val maxStockMgdl: Double = 200.0,
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
        /**
         * NACH EINER SPERRE MUSS DIE MESSBASIS NEU GESETZT WERDEN.
         *
         * Der Befund (Toni 12.08.): bei Tief, Signalfehler oder Widerruf wurde
         * zwar der Bestand geloescht, `lastAcceptedTs`/`lastAdjusted` blieben
         * aber alt. Der erste gesunde Zyklus danach haette die GANZE Differenz
         * ueber die Sperrzeit als frischen Zufluss verbucht - also genau die
         * Evidenz nachgeholt, die wir gerade fuer ungueltig erklaert hatten.
         * Bei einem Tief waere das die teuerste Richtung ueberhaupt.
         *
         * Solange dieser Merker steht, setzt der naechste gesunde Messpunkt
         * ausschliesslich die Basis: kein Zufluss, kein Kredit. Erst der
         * DARAUFFOLGENDE Punkt erzeugt wieder Evidenz.
         */
        val rebaseRequired: Boolean = false,
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
        /** Der erste gesunde Punkt nach einer Sperre - er setzt nur die Basis
         *  (s. [State.rebaseRequired]). */
        REBASE_AFTER_SUSPEND,
        /** Der Nutzer hat den Marker zurueckgenommen. Die Episode laeuft
         *  weiter, der Zusatzkredit ist widerrufen. */
        CREDIT_REVOKED,
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
        /**
         * Der Zusatzkredit dieser Episode ist AUSDRUECKLICH zurueckgenommen.
         *
         * OHNE DEFAULT und als eigener Eingang, weil er sonst gar nicht
         * ankaeme: `evidenceRevoked` wurde persistiert und exportiert, war
         * aber kein Eingang des Kerns - bei positivem Bestand haette der
         * ACTIVE gemeldet und Kredit geliefert, obwohl der Nutzer den Marker
         * zurueckgenommen hat (Toni 12.08.).
         */
        val creditRevoked: Boolean,
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

    /**
     * WAS DIE EPISODE GERADE DARF - die dynamische Laufzeit (Toni 12.08.).
     *
     * Sie ersetzt die Gleichsetzung "Episode laeuft = Episode gibt Kredit".
     * Eine Mahlzeit ist keine Uhr: der Snack ist nach neunzig Minuten durch,
     * die Fettmahlzeit kann in der vierten Stunde eine zweite Welle tragen.
     * Beides mit EINER Laufzeit zu bedienen geht nicht - also entscheidet die
     * EVIDENZ, und die Uhr ist nur noch der Notaus.
     *
     * WICHTIG, und der Grund fuer die Bauform: die Laufzeit darf sich NICHT
     * selbst durch positives `r` verlaengern. Sonst hielte eine schlechte
     * Infusionsstelle oder eine Gegenregulation die Mahlzeitenepisode
     * beliebig offen. Deshalb haengt [ACTIVE] am BESTAND, der Bestand am
     * Zufluss neuer BGI-bereinigter Messinformation, und der Deckel laeuft
     * unbeirrt ab dem Ursprung weiter.
     */
    enum class Phase {
        /** Es gibt keine Episode. Kein Marker, kein Anker, nichts zu zeigen. */
        NONE,

        /** Bestand vorhanden, versiegelt und alle Tore offen -
         *  `ConditionalDrive` darf Kredit bekommen. */
        ACTIVE,

        /**
         * EVIDENZ IST DA UND WARTET AUF DIE VERSIEGELUNG.
         *
         * Der Zufluss dieses Zyklus steht im Bestand, aber noch nicht auf
         * Platte; kreditfaehig wird er erst nach erfolgreichem Persist, also
         * im naechsten Zyklus.
         *
         * EIGENER NAME, weil DORMANT hier gelogen haette (Toni 12.08.):
         * DORMANT heisst "die Mahlzeit ist gerade durch". Genau das Gegenteil
         * ist der Fall - sie hat eben Evidenz geliefert. Wer im Tab DORMANT
         * liest, waehrend eine Welle anlaeuft, zieht den falschen Schluss.
         */
        PENDING_SEAL,

        /**
         * Kein Kredit, aber die Episode bleibt ERINNERBAR: Anker, Uhr und
         * kumulative Bezahlung laufen weiter.
         *
         * Das ist der Normalzustand zwischen zwei Wellen. Trifft neue,
         * einmalig verbuchte Evidenz ein, wird dieselbe `episodeId` wieder
         * ACTIVE - ohne Uhr oder Budget neu zu starten. Ohne neue Evidenz
         * bleibt sie identifizierbar und therapeutisch wirkungslos.
         */
        DORMANT,

        /**
         * Die Episode ist da, der Kredit aber von aussen gesperrt: gemessenes
         * Tief, ungesundes Signal oder ein Segmentbruch in der Messreihe.
         *
         * GETRENNT VON [DORMANT], weil die Anzeige sonst luege (Toni 12.08.):
         * DORMANT heisst "die Mahlzeit ist gerade durch", SUSPENDED heisst
         * "wir duerfen bzw. wissen gerade nicht". Wer das erste liest, waehrend
         * das zweite gilt, zieht den falschen Schluss ueber die Mahlzeit.
         */
        SUSPENDED,

        /**
         * Persistenz oder Bilanz sind unklar - der Bestand gilt als nicht
         * vertrauenswuerdig und wird auf 0 gesetzt.
         *
         * Auch das ist kein DORMANT: hier ist nicht die Mahlzeit vorbei,
         * sondern die Buchfuehrung fraglich (Neustart ohne gueltigen Zustand,
         * rueckwaerts springende Episodenidentitaet, sinkender kumulativer
         * Abgabestand).
         */
        UNKNOWN,

        /** Der Deckel ist erreicht. Kein Wiederaufleben - jeder weitere
         *  Zyklus faellt erneut hierher. */
        EXPIRED,
    }

    data class Result(
        val state: State,
        /** Antrieb fuer [ConditionalDrive] [mg/dl/min]. 0 = kein Kredit. */
        val creditMgdlPerMin: Double,
        /** Was in diesem Zyklus zugeflossen ist [mg/dl]. */
        val inflowMgdl: Double,
        /** Warum nichts zufloss; `null` = es floss etwas zu. */
        val noInflow: NoInflow?,
        /**
         * Was die Episode gerade darf. ABGELEITET, kein eigener Zustand -
         * ein zweiter Zustand koennte von Bestand und Uhr abweichen, und
         * dann gaebe es zwei Wahrheiten ueber dieselbe Episode.
         */
        val phase: Phase,
    )

    /**
     * @param cfg OHNE DEFAULT (Toni 12.08.): ein `Config()` an der Signatur
     *   waere eine zweite, stille Konfiguration neben der des Zyklus. Bei
     *   einem Replay mit abweichendem Deckel wuerde der Kern dann etwas
     *   anderes rechnen, als Tor und Export behaupten.
     */
    fun step(prev: State, input: Input, cfg: Config): Result {
        if (input.episodeId <= 0L)
            return Result(State(), 0.0, 0.0, NoInflow.NO_EPISODE, Phase.NONE)
        if (!input.persistedStateKnown)
            return Result(State(), 0.0, 0.0, NoInflow.EVIDENCE_STATE_UNKNOWN, Phase.UNKNOWN)

        // EINE ALTE EPISODE DARF NICHT WIEDERAUFLEBEN. Die Identitaet ist
        // monoton (in der Praxis der Markerzeitpunkt); ein RUECKWAERTS
        // springender Wert heisst, dass der persistierte Zustand nicht der
        // ist, den wir zu haben glauben - und dann waere ein frischer
        // Vier-Stunden-Deckel auf einer alten Episode das Gegenteil eines
        // Deckels.
        if (prev.episodeId > 0L && input.episodeId < prev.episodeId)
            return Result(prev.copy(stockMgdl = 0.0), 0.0, 0.0, NoInflow.EVIDENCE_STATE_UNKNOWN, Phase.UNKNOWN)

        // ---- SEMANTISCH UNMOEGLICHE ZUSTAENDE (Toni 12.08.) ---------------
        //
        // Der Codec prueft Felder EINZELN - endlich, nicht negativ. Das laesst
        // Kombinationen durch, die dieser Kern nie schreibt und die nach einem
        // Neustart teuer werden:
        //
        //   Bestand > 0 ohne Episodenstart -> der Start wird unten auf `now`
        //     gesetzt, und ein alter Bestand bekaeme einen frischen
        //     Sicherheitsdeckel geschenkt.
        //   lastDecayTs in der Zukunft -> `dtMin` klemmt auf 0, der Bestand
        //     verfaellt bis dahin nicht.
        //   Bestand > 0 ohne Messbasis -> er erzeugt sofort Kredit, ohne dass
        //     je ein Punkt verbucht wurde.
        //
        // Fail-closed und mit eigenem Grund: nicht reparieren, nicht klemmen -
        // ein unmoeglicher Zustand ist keine Zahl, die man zurechtbiegt.
        if (unmoeglich(prev, input, cfg))
            return Result(
                State(episodeId = input.episodeId, rebaseRequired = true),
                0.0, 0.0, NoInflow.EVIDENCE_STATE_UNKNOWN, Phase.UNKNOWN,
            )

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
                0.0, 0.0, NoInflow.EPISODE_EXPIRED, Phase.EXPIRED,
            )

        // ---- Verfall auf der WANDUHR, vor jeder Fallunterscheidung ---------
        // Auch waehrend einer Luecke oder eines Widerrufs: ein Bestand, der
        // stehenbleibt, waere eine Behauptung, die nicht altert.
        val dtMin = if (basis.lastDecayTs > 0L)
            max(0.0, (input.nowMs - basis.lastDecayTs) / 60_000.0) else 0.0
        val nachVerfall = max(0.0, basis.stockMgdl * (1.0 - dtMin / cfg.decayMin))

        // ---- Abzug: kumulativer Zuwachs, also genau einmal -----------------
        //
        // MONOTON INNERHALB DER EPISODE. Gleicher Wert ist idempotent (Replay,
        // zweiter Reglerzyklus auf demselben Stand) - ein KLEINERER ist es
        // nicht: er kann nur aus einem verlorenen oder vertauschten Zustand
        // kommen. Ihn als "kein Abzug" zu schlucken waere die falsche
        // Richtung, denn dann steht Bestand zur Verfuegung, dessen Bezahlung
        // wir gerade vergessen haben. Fail-closed mit eigenem Grund.
        if (input.episodeCommittedU < basis.lastCommittedU - 1e-9)
            return Result(basis.copy(stockMgdl = 0.0), 0.0, 0.0, NoInflow.EVIDENCE_STATE_UNKNOWN, Phase.UNKNOWN)
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

        // DREI SPERREN, EIN VERHALTEN: Bestand auf 0, Verfall und Buchfuehrung
        // laufen weiter, und die MESSBASIS wird als ungueltig markiert. Ohne
        // das Letzte holte der erste gesunde Punkt danach die ganze Sperrzeit
        // als Zufluss nach - bei einem Tief die teuerste Richtung ueberhaupt.
        if (input.measuredLow)
            return Result(gemerkt.copy(stockMgdl = 0.0, rebaseRequired = true), 0.0, 0.0, NoInflow.MEASURED_LOW, Phase.SUSPENDED)
        if (!input.healthReady)
            return Result(gemerkt.copy(stockMgdl = 0.0, rebaseRequired = true), 0.0, 0.0, NoInflow.HEALTH_NOT_READY, Phase.SUSPENDED)
        // WIDERRUF: die Episode bleibt, der Kredit ist weg. Er zaehlt zu den
        // Sperren und nicht zu DORMANT - DORMANT hiesse "die Mahlzeit ist
        // durch", hier hat der Nutzer die Erlaubnis entzogen.
        if (input.creditRevoked)
            return Result(gemerkt.copy(stockMgdl = 0.0, rebaseRequired = true), 0.0, 0.0, NoInflow.CREDIT_REVOKED, Phase.SUSPENDED)

        // ---- Segmentbruch: Ausgabe UND Zufluss gesperrt, Verfall laeuft ----
        // Das neue Segment setzt nur die Messbasis; ueber die Luecke wird
        // keine Differenz gebildet - `cumulativeBgi` startet dort neu bei 0.
        // ---- Erster gesunder Punkt nach einer Sperre: NUR Basis ----------
        if (basis.rebaseRequired)
            return Result(
                state = gemerkt.copy(
                    stockMgdl = 0.0,
                    lastAcceptedTs = input.sourceTs,
                    lastAdjusted = input.adjusted,
                    segmentStartTs = input.segmentStartTs,
                    rebaseRequired = false,
                ),
                creditMgdlPerMin = 0.0,
                inflowMgdl = 0.0,
                noInflow = NoInflow.REBASE_AFTER_SUSPEND,
                // Noch nicht DORMANT: es ist kein Urteil ueber die Mahlzeit,
                // sondern der eine Zyklus, der die Messbasis wiederherstellt.
                phase = Phase.SUSPENDED,
            )

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
                phase = Phase.SUSPENDED,
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

        // Unter der Schwelle wird abgeschnitten - sonst schliefe die Episode
        // nie ein (s. Config.stockFloorMgdl).
        val neu = max(0.0, nachVerfall + zufluss - rueckgang - abzug)
            .let { if (it < cfg.stockFloorMgdl) 0.0 else it }

        // ---- KREDIT NUR AUS DEM VERSIEGELTEN ANTEIL (Stufe 3) --------------
        //
        // Der Zufluss DIESES Zyklus steht noch nicht auf Platte. Ihn sofort
        // auszuschuetten hiesse, Insulin auf eine Messinformation zu geben,
        // die ein Stromausfall eine Sekunde spaeter spurlos verschwinden
        // liesse - danach waere Insulin unterwegs, das keine Buchung mehr hat.
        //
        // Also: der Kredit dieses Zyklus stammt aus dem Bestand, der bereits
        // versiegelt WAR - gealtert, um die Abgabe verringert, aber ohne den
        // frischen Zufluss. Der wird erst kreditfaehig, wenn der Zyklus
        // erfolgreich versiegelt hat, also im naechsten.
        //
        // Der Preis ist EINE Minute Verzug. Er ist gering gegen die
        // Fehlerrichtung, die er ausschliesst.
        val versiegelt = max(0.0, neu - zufluss)
            .let { if (it < cfg.stockFloorMgdl) 0.0 else it }
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
            creditMgdlPerMin = if (versiegelt <= 0.0) 0.0 else min(versiegelt / cfg.releaseWindowMin, versiegelt),
            inflowMgdl = zufluss,
            noInflow = grund,
            // ACTIVE HEISST "DARF JETZT KREDIT LIEFERN" - haengt also am
            // VERSIEGELTEN Bestand, nicht am eben zugeflossenen. Der erste
            // Zyklus einer frischen Welle ist deshalb noch DORMANT, und das
            // ist keine Ungenauigkeit, sondern genau der Ein-Zyklus-Verzug.
            //
            // Weiterhin gilt: kein Bezug zur Uhr und keiner zu `r`. Ohne
            // Zufluss traegt der Verfall den Bestand in wenigen Minuten auf 0,
            // und die Episode faellt von selbst auf DORMANT - das erledigt den
            // Snack, ohne dass jemand eine Dauer schaetzen muss.
            phase = when {
                versiegelt > 0.0 -> Phase.ACTIVE
                // Bestand da, aber noch nicht versiegelt: der Ein-Zyklus-
                // Verzug, nicht das Ende der Mahlzeit.
                neu > 0.0        -> Phase.PENDING_SEAL
                else             -> Phase.DORMANT
            },
        )
    }

    /**
     * KANN DIESER ZUSTAND SO ENTSTANDEN SEIN?
     *
     * Nur Kombinationen, die dieser Kern NIE schreibt - keine Heuristik. Jede
     * einzelne davon waere nach einem Neustart eine unverdiente Freigabe.
     */
    private fun unmoeglich(prev: State, input: Input, cfg: Config): Boolean {
        // Zeitstempel aus der Zukunft: die Datei ist juenger als die Uhr, oder
        // die Uhr ist gesprungen. Beides macht jede Alterung sinnlos.
        val zukunft = MarkerEpisodeGate.FUTURE_TOLERANCE_MS
        if (listOf(prev.episodeStartTs, prev.lastAcceptedTs, prev.lastDecayTs, prev.segmentStartTs)
                .any { it > 0L && it - input.nowMs > zukunft }
        ) return true

        // Die Messbasis muss in sich stimmen.
        if (prev.segmentStartTs > 0L && prev.lastAcceptedTs > 0L && prev.segmentStartTs > prev.lastAcceptedTs)
            return true

        if (prev.stockMgdl <= 0.0) return false

        // Ein POSITIVER Bestand verlangt die vollstaendige Herkunft: zu welcher
        // Episode er gehoert, seit wann sie laeuft, auf welchem Messpunkt er
        // beruht und wann er zuletzt gealtert ist. Fehlt eines davon, ist er
        // nicht zuordenbar - und ein nicht zuordenbarer Bestand darf kein
        // Insulin freigeben.
        if (prev.episodeId <= 0L || prev.episodeStartTs <= 0L) return true
        if (prev.lastAcceptedTs <= 0L || prev.segmentStartTs <= 0L) return true
        if (prev.lastDecayTs <= 0L) return true
        return prev.stockMgdl > cfg.maxStockMgdl
    }
}

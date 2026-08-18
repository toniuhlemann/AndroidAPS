package app.aaps.fuse.core.controller

import kotlin.math.abs

/**
 * WAS FUSE VERSPROCHEN HAT, UND WAS DAVON EINGETROFFEN IST.
 *
 * Erster Baustein des Sackgassenwaechters (Tonis Vertrag 17.08. nachts).
 * DIESE KLASSE ENTSCHEIDET NICHTS - sie misst nur. Die spaetere
 * lambda-Adaption haengt an ihr, aber sie selbst kann keine Dosis aendern.
 *
 * DER ANLASS: FUSE stand am 17.08. 89 Zyklen lang bei 0,00 U und BG 169-216.
 * Ursache ist keine falsche Arithmetik, sondern eine pessimistische ANNAHME
 * ohne Korrekturschleife - `r` ist bereits BGI-bereinigt (r = D), der
 * Bolus-Deckungs-Abschlag zieht die Insulinwirkung mit lambda = 1 ein
 * zweites Mal ab, die Bahn ein drittes Mal. Bei FRISCHER Stoerung richtig,
 * bei einem seit Stunden bestaetigten Plateau hundertfach widerlegt - und
 * FUSE trifft die Annahme trotzdem jede Minute neu.
 *
 * WAS DIESER LEDGER LEISTEN MUSS, damit die Adaption ihn benutzen darf: ein
 * Nachweis, der nur dann MISSED sagt, wenn die MITTELBAHN-Senkung ausblieb
 * UND kein Eingriff dazwischenkam UND die Strecke wirklich beobachtet war.
 * Jede dieser Bedingungen wurde in einem eigenen Durchgang nachgetragen
 * (Tonis Durchsichten 17./18.08.) - jedesmal, bevor der Baustein einen
 * Aufrufer hatte.
 */
object ExpectationLedger {

    /**
     * WOFUER eine Prognose abgegeben wurde - und ob sie den lambda-Nachweis
     * tragen darf (Toni 18.08., vor der Runner-Anbindung).
     *
     * Der Erwartungs-Ledger dient dem HOCHPLATEAU-/lambda-Nachweis. Das
     * geplante Mahlzeitenfundament ist eine eigene Regelungsschicht mit
     * eigener Physik: dort ist eine ausbleibende Senkung der Normalfall,
     * weil Kohlenhydrate gegen das Insulin laufen. Solche Eintraege in
     * dieselbe Strecke zu zaehlen hiesse, den lambda-Nachweis mit
     * Mahlzeitenverlaeufen zu fuellen - und lambda wuerde ausgerechnet dann
     * gelockert, wenn das Modell recht hatte.
     *
     * Als PFLICHTFELD und nicht als Vorgabewert: ein Default waere die
     * bequeme Loesung und wuerde jede vergessene Aufrufstelle still zu
     * CORRECTION machen - also zur lambda-tauglichen Sorte.
     */
    enum class ExpectationContext {

        /** Gewoehnlicher Korrekturbetrieb - die einzige Sorte, die
         *  lambda-Evidenz tragen darf. */
        CORRECTION,

        /**
         * Marker, Onset, laufende Evidenzepisode oder Mahlzeitenfenster ist
         * massgeblich. Auswertbar und exportiert - Grundlage der spaeteren
         * Mahlzeitenanalyse -, aber NIE Teil der lambda-Strecke.
         */
        MEAL,

        /**
         * WEDER KORREKTUR NOCH MAHLZEIT - fuer keine Analyse brauchbar
         * (Toni 18.08.).
         *
         * Der erste Wurf kannte nur zwei Werte und liess Rebound,
         * Signalfehler und unbekannte Eingaben als MEAL enden. Die
         * Dosierseite war damit sicher (nicht lambda-tauglich), aber die
         * spaetere Mahlzeitenanalyse haette Nicht-Mahlzeiten als Mahlzeiten
         * gezaehlt - ein stiller Fehler, der erst beim Auswerten auffiele
         * und dann nicht mehr trennbar waere.
         */
        EXCLUDED,
    }

    /**
     * WARUM diese Einordnung - typisiert, nicht als Text.
     *
     * Ohne ihn zeigte der Export spaeter nur, DASS etwas ausgeschlossen
     * wurde, nicht weshalb. Und an einem Grundtext darf keine Auswertung
     * haengen; dieselbe Regel, aus der schon `markerAuthorizedU` und
     * `unsafeSituation` typisierte Herkunft bekommen haben.
     */
    enum class ContextReason {

        /** Alle Lage-Groessen ausdruecklich belegt, keine greift. */
        PURE_CORRECTION,

        // ---- MEAL --------------------------------------------------------
        MARKER_ACTIVE,
        EVIDENCE_EPISODE_ACTIVE,
        ONSET_ACTIVE,
        MEAL_WINDOW_OPEN,

        // ---- EXCLUDED ----------------------------------------------------
        /** Erholung nach einem Tief - die Steigung ist kein Korrekturbefund
         *  und die Lage keine Mahlzeit. */
        REBOUND,

        /** Ohne brauchbares Signal ist gar keine Lage belegt. */
        SIGNAL_UNHEALTHY,

        /** Mindestens eine Lage-Groesse war unbekannt. */
        UNKNOWN_INPUT,
    }

    /** Einordnung mit Begruendung. */
    data class Classification(val context: ExpectationContext, val reason: ContextReason)

    /**
     * DIE LAGE, aus der sich der [ExpectationContext] ergibt.
     *
     * Als eigener Typ und nicht als Parameterliste: der Klassifikator
     * bekommt damit ein VOLLSTAENDIGES Bild, und eine spaeter ergaenzte
     * Lage-Groesse faellt beim Kompilieren auf, statt still zu fehlen.
     *
     * `null` heisst ueberall UNBEKANNT und fuehrt zu MEAL - nicht zu
     * CORRECTION. Eine Lage, die man nicht kennt, ist keine belegte reine
     * Korrekturlage.
     */
    data class Situation(
        /** Marker-Freigabe laeuft. */
        val mealMarkerActive: Boolean?,
        /**
         * Eine Evidenzepisode ist noch massgeblich (ACTIVE, PENDING_SEAL
         * oder ein Bestand > 0).
         *
         * DER WICHTIGSTE EINZELNE PUNKT (Toni 18.08.): der MARKER endet
         * frueher als eine langsame Absorption. Nur `mealMarkerActive` zu
         * pruefen wuerde die Nachlaufphase einer Mahlzeit als reine
         * Korrektur verbuchen - und genau dort ist eine ausbleibende Senkung
         * der Normalfall.
         */
        val evidenceEpisodeActive: Boolean?,
        /** Der Onset-Kanal hat die Mittelbahn gehoben. */
        val onsetActive: Boolean?,
        /** Das Mahlzeitenfenster (Marker, Onset oder kinematische Persistenz). */
        val mealWindow: Boolean?,
        /** Das Rebound-Fenster nach einem Tief - dort ist die Erholungs-
         *  steigung kein Korrekturbefund. */
        val reboundWindow: Boolean?,
        /** Signalgesundheit. Ohne sie ist gar keine Lage belegt. */
        val signalHealthy: Boolean?,
    )

    /**
     * WELCHE SORTE PROGNOSE IST DAS?
     *
     * Tonis Grenze, woertlich: "CORRECTION nur, wenn ausdruecklich reine
     * Korrekturlage belegt ist. Marker, Onset, laufende Evidenzepisode,
     * Mahlzeitenfenster, Rebound oder unklare Lage -> keine
     * CORRECTION-lambda-Evidenz."
     *
     * Die Funktion ist bewusst so gebaut, dass CORRECTION der SCHWERE Fall
     * ist: jede einzelne Bedingung muss ausdruecklich mit `false` belegt
     * sein, die Gesundheit ausdruecklich mit `true`. Alles andere - auch ein
     * einziges `null` - ergibt MEAL. Ein vergessenes Merkmal kann damit
     * hoechstens Nachweis KOSTEN, nie welchen erfinden.
     */
    fun classify(situation: Situation): Classification {
        val s = situation
        // REIHENFOLGE IST BEDEUTUNG. Die Ausschlussgruende kommen ZUERST:
        // ein Rebound oder ein ungesundes Signal macht die Lage auch als
        // MAHLZEIT unbrauchbar, nicht nur als Korrektur. Stuende die
        // Mahlzeitenpruefung davor, landete ein Rebound waehrend eines
        // Markers in der Mahlzeitenanalyse.
        if (s.mealMarkerActive == null || s.evidenceEpisodeActive == null ||
            s.onsetActive == null || s.mealWindow == null ||
            s.reboundWindow == null || s.signalHealthy == null
        ) return Classification(ExpectationContext.EXCLUDED, ContextReason.UNKNOWN_INPUT)
        if (!s.signalHealthy) return Classification(ExpectationContext.EXCLUDED, ContextReason.SIGNAL_UNHEALTHY)
        if (s.reboundWindow) return Classification(ExpectationContext.EXCLUDED, ContextReason.REBOUND)

        // Dann die Mahlzeitengruende - der erste zutreffende benennt sie.
        if (s.mealMarkerActive) return Classification(ExpectationContext.MEAL, ContextReason.MARKER_ACTIVE)
        if (s.evidenceEpisodeActive)
            return Classification(ExpectationContext.MEAL, ContextReason.EVIDENCE_EPISODE_ACTIVE)
        if (s.onsetActive) return Classification(ExpectationContext.MEAL, ContextReason.ONSET_ACTIVE)
        if (s.mealWindow) return Classification(ExpectationContext.MEAL, ContextReason.MEAL_WINDOW_OPEN)

        return Classification(ExpectationContext.CORRECTION, ContextReason.PURE_CORRECTION)
    }

    /**
     * Kennung eines VERBRAUCHTEN Messwerts - Teil des persistierbaren
     * Zustands.
     *
     * WARUM PERSISTENT (Toni, P0): der Verbrauch lebte nur innerhalb EINES
     * `settle`-Aufrufs. Bei minuetlichen Zyklen und 150 s Toleranz konnte
     * derselbe Messwert in fuenf aufeinanderfolgenden Aufrufen je eine neue
     * Faelligkeit bedienen - aus EINEM Punkt waere eine vier Minuten lange
     * MISSED-Strecke entstanden. Eine Persistenz ohne diese Sperre haette
     * einen mehrfach gezaehlten Nachweis dauerhaft beglaubigt.
     */
    data class SampleId(val segmentId: Long, val ts: Long)

    /**
     * Stabile, eindeutige Kennung eines Eintrags.
     *
     * WARUM NICHT `dueTs` (Tonis Befund): die Zuordnung war danach indiziert,
     * und zwei Eintraege mit demselben `dueTs` haetten beim Auslesen BEIDE
     * denselben Messwert bekommen, obwohl er nur einmal als verbraucht galt.
     * Wiederholte Zyklen mit gleichem `sourceTs` koennen genau das erzeugen.
     *
     * Aus Quelle, Faelligkeit und Segment - drei Groessen, die einen Eintrag
     * zusammen eindeutig machen und ueber einen Neustart hinweg gleich
     * bleiben. Ein Zaehler waere nach dem Wiederanlauf nicht mehr derselbe.
     */
    data class EntryId(val sourceTs: Long, val dueTs: Long, val segmentId: Long)

    /**
     * Ein Messwert mit Zeitpunkt, Segment und Gesundheit.
     *
     * SEGMENT UND GESUNDHEIT GEHOEREN AN DEN MESSWERT, nicht an den
     * Abrechnungszeitpunkt: geprueft wird ein HISTORISCHER Punkt, und ob das
     * Signal JETZT gesund ist, sagt nichts darueber, ob es DAMALS gesund
     * war. Der erste Wurf hatte genau diese Verwechslung.
     */
    data class Sample(
        val ts: Long,
        val mgdl: Double,
        val segmentId: Long,
        val healthy: Boolean,
        /**
         * Der Eingriffsstand ZU DIESEM Zeitpunkt - nicht der heutige.
         *
         * Dieselbe Verwechslung wie bei [healthy], eine Zeile weiter: der
         * erste Wurf verglich gegen die GEGENWART. Ein Eingriff, der erst
         * NACH der Faelligkeit erfolgte (aber vor einem verspaeteten
         * `settle`), machte damit eine zum Faelligkeitszeitpunkt saubere
         * Prognose faelschlich zu INTERVENED.
         */
        val interventionStamp: InterventionStamp,
        /** Der Konfigurationsstand zu diesem Zeitpunkt - aus demselben Grund. */
        val configGeneration: String,
    ) {

        val id: SampleId get() = SampleId(segmentId, ts)
    }

    /**
     * Eine abgegebene Prognose, die spaeter geprueft werden kann.
     *
     * @param sourceTs der Messzeitpunkt, auf dem die Prognose beruht.
     * @param dueTs wann sie faellig ist.
     * @param segmentId die Signalsegment-Kennung bei Abgabe.
     * @param anchorMgdl der BG, von dem aus prognostiziert wurde.
     * @param meanPredictedMgdl die MITTELBAHN - die Erwartung, die eintreten
     *   soll. Nur sie taugt als Versprechen.
     * @param configGeneration Kennung von Regelwerk und Einstellungen.
     *   PFLICHT: sie soll Vergleichbarkeit garantieren, und eine Garantie,
     *   die man weglassen darf, ist keine.
     * @param interventionRevision der Stand aller Eingriffe (SMBs, manuelle
     *   Boli, Kohlenhydrate, TBR, Profil) bei Abgabe. Aendert er sich bis
     *   zur Faelligkeit, wurde nicht mehr dieselbe Ausgangsannahme geprueft -
     *   s. [Verdict.INTERVENED].
     * @param safetyLowerPredictedMgdl die Sicherheitsuntergrenze zum selben
     *   Zeitpunkt. REINER KONTEXT: ein Risikoszenario, das nicht eintreten
     *   soll, ist kein Versprechen und geht nie in ein MET/MISSED ein. Die
     *   spaetere Adaption braucht es trotzdem - sie darf nur lockern, wenn
     *   die reale Bahn DEUTLICH ueber der damaligen Untergrenze blieb.
     */
    data class Entry(
        val sourceTs: Long,
        val dueTs: Long,
        val segmentId: Long,
        val anchorMgdl: Double,
        val meanPredictedMgdl: Double,
        val configGeneration: String,
        val interventionStamp: InterventionStamp,
        val context: ExpectationContext,
        val contextReason: ContextReason,
        val safetyLowerPredictedMgdl: Double? = null,
        val lambda: Double? = null,
        val discountMgdl: Double? = null,
        val bgiMgdl: Double? = null,
    ) {

        /** s. [EntryId] - stabil ueber Neustarts, eindeutig auch bei
         *  gleichem `dueTs`. */
        val id: EntryId get() = EntryId(sourceTs, dueTs, segmentId)

        /** Die behauptete Senkung der MITTELBAHN [mg/dl], immer positiv. */
        val promisedDropMgdl: Double get() = anchorMgdl - meanPredictedMgdl
    }

    /** Wie eine faellige Prognose ausgegangen ist. */
    enum class Verdict {
        /** Die Mittelbahn ist mindestens so weit gefallen wie versprochen. */
        MET,

        /** Die versprochene Senkung ist ausgeblieben - ohne Eingriff, der das
         *  erklaeren wuerde. Der einzige Fall, der den Sackgassenwaechter
         *  naehrt. */
        MISSED,

        /**
         * ZWISCHEN AUSGABE UND FAELLIGKEIT WURDE EINGEGRIFFEN - weiterer SMB,
         * manueller Bolus, Kohlenhydrate, TBR- oder Profilwechsel.
         *
         * Dann wurde nicht mehr dieselbe Ausgangsannahme geprueft, und das
         * Ergebnis waere in BEIDE Richtungen gefaehrlich: ein manueller Bolus
         * kann ein MET erzeugen und einen echten Nachweis loeschen;
         * Kohlenhydrate koennen ein MISSED erzeugen und spaeter lambda
         * lockern, obwohl das Modell recht hatte.
         */
        INTERVENED,

        /**
         * Nicht bewertbar - KEIN halbes MET und kein halbes MISSED. Zum
         * Faelligkeitszeitpunkt fehlte ein Messwert in der Naehe, er stammte
         * aus einem anderen Segment, er war ungesund, oder er wurde bereits
         * von einer anderen Faelligkeit verbraucht.
         */
        UNVERIFIABLE,
    }

    /** Das Ergebnis einer faellig gewordenen Prognose. */
    data class Outcome(
        val entry: Entry,
        val verdict: Verdict,
        val actualTs: Long? = null,
        val actualMgdl: Double? = null,
    ) {

        /** Abweichung von der MITTELBAHN [mg/dl]. Positiv = Senkung blieb aus. */
        val meanErrorMgdl: Double?
            get() = actualMgdl?.let { it - entry.meanPredictedMgdl }

        /**
         * Abstand des gemessenen Werts zur damaligen SICHERHEITSUNTERGRENZE
         * [mg/dl]. Positiv = darueber geblieben. Diagnose - und zugleich die
         * Groesse, die die spaetere Adaption ZUSAETZLICH verlangen muss.
         */
        val distanceFromSafetyLowerMgdl: Double?
            get() = entry.safetyLowerPredictedMgdl?.let { sl -> actualMgdl?.let { it - sl } }

        /**
         * Zaehlt dieses Ergebnis als Beleg gegen die pessimistische
         * lambda-Annahme?
         *
         * NICHT JEDES MISSED TAUGT DAFUER (Toni, P1). Ein ausgebliebener
         * Senkungsschritt allein widerlegt lambda noch nicht - er koennte
         * auch bedeuten, dass die Lage tatsaechlich nahe an der
         * Sicherheitsuntergrenze lag und der Abschlag recht hatte. Als Beleg
         * taugt er nur, wenn die reale Bahn DEUTLICH darueber blieb.
         *
         * Der Schwellwert hat KEINEN Vorgabewert: mit einem Default 0 waere
         * das hier wieder "jedes MISSED", und genau diese Verwechslung soll
         * der eigene Name verhindern. Fehlt die damalige Untergrenze, ist
         * das kein Beleg - `null` heisst nicht "war weit genug weg".
         */
        fun isLambdaEvidence(minSafetyMarginMgdl: Double): Boolean {
            // EINE UNBRAUCHBARE MARGE IST KEIN FREIBRIEF. Negativ hiesse,
            // dass sogar Werte UNTERHALB der damaligen Sicherheitsuntergrenze
            // als Beleg fuer "mehr Insulin" durchgingen - die Umkehrung des
            // Begriffs. Nicht-endlich ebenso: fail-closed statt NaN-Vergleich.
            if (!minSafetyMarginMgdl.isFinite() || minSafetyMarginMgdl <= 0.0) return false
            // NUR DER KORREKTURBETRIEB TRAEGT DEN lambda-NACHWEIS. In einer
            // Mahlzeitenepisode ist eine ausbleibende Senkung der Normalfall -
            // sie mitzuzaehlen hiesse, lambda ausgerechnet dann zu lockern,
            // wenn das Modell recht hatte.
            if (entry.context != ExpectationContext.CORRECTION) return false
            if (verdict != Verdict.MISSED) return false
            val abstand = distanceFromSafetyLowerMgdl ?: return false
            return abstand >= minSafetyMarginMgdl
        }
    }

    /**
     * Eine Prognose einreihen - oder `null`, wenn sie nichts behauptet, das
     * spaeter widerlegbar waere.
     */
    fun issue(
        sourceTs: Long,
        segmentId: Long,
        anchorMgdl: Double?,
        meanPredictedMgdl: Double?,
        horizonMin: Int,
        configGeneration: String,
        interventionStamp: InterventionStamp,
        classification: Classification,
        safetyLowerPredictedMgdl: Double? = null,
        lambda: Double? = null,
        discountMgdl: Double? = null,
        bgiMgdl: Double? = null,
        minDropMgdl: Double = MIN_PROMISED_DROP_MGDL,
    ): Entry? {
        if (anchorMgdl == null || !anchorMgdl.isFinite()) return null
        if (meanPredictedMgdl == null || !meanPredictedMgdl.isFinite()) return null
        if (horizonMin <= 0) return null
        // Ohne Vergleichbarkeitskennung kein Eintrag - sonst steht spaeter
        // ein Ergebnis in der Datei, das mit nichts vergleichbar ist.
        if (configGeneration.isBlank()) return null
        // NUR SENKUNGEN DER MITTELBAHN sind widerlegbar.
        if (anchorMgdl - meanPredictedMgdl < minDropMgdl) return null
        return Entry(
            sourceTs = sourceTs,
            dueTs = sourceTs + horizonMin * 60_000L,
            segmentId = segmentId,
            anchorMgdl = anchorMgdl,
            meanPredictedMgdl = meanPredictedMgdl,
            configGeneration = configGeneration,
            interventionStamp = interventionStamp,
            context = classification.context,
            contextReason = classification.reason,
            safetyLowerPredictedMgdl = safetyLowerPredictedMgdl?.takeIf { it.isFinite() },
            lambda = lambda?.takeIf { it.isFinite() },
            discountMgdl = discountMgdl?.takeIf { it.isFinite() },
            bgiMgdl = bgiMgdl?.takeIf { it.isFinite() },
        )
    }

    /**
     * Einen Eintrag in die Liste aufnehmen - DUPLIKATE WERDEN VERWORFEN.
     *
     * Toni: "Duplikate bereits beim Einreihen verhindern". Zwei Eintraege
     * mit derselben [EntryId] beschreiben dieselbe Prognose; sie doppelt zu
     * fuehren hiesse, denselben Nachweis zweimal zu zaehlen. Der spaetere
     * Aufrufer ruft das je Zyklus, auch nach einem Wiederanlauf mit
     * geladener Liste.
     */
    internal fun add(entries: List<Entry>, neu: Entry?): List<Entry> {
        if (neu == null) return entries
        val vorhanden = entries.firstOrNull { it.id == neu.id } ?: return entries + neu
        // GLEICHE BASIS-ID, ABER NEUER STAND -> ERSETZEN (Toni, P1).
        // Nach einem Eingriff beschreibt die neue Prognose die jetzt gueltige
        // Lage; die alte zu behalten hiesse, gegen eine ueberholte
        // Ausgangsannahme zu pruefen. Die Kennung allein kann das nicht
        // unterscheiden - sie soll ueber Neustarts stabil bleiben und darf
        // deshalb Revision und Konfiguration gerade NICHT enthalten.
        // Aendert sich bei wiederholtem `sourceTs` die PROGNOSE selbst -
        // Mittelbahn, Untergrenze, lambda -, blieb der alte Eintrag im ersten
        // Anlauf stehen, und der Nachweis liefe gegen eine ueberholte
        // Behauptung. Jetzt entscheidet die vollstaendige Gleichheit: nur ein
        // in JEDEM Feld identischer Eintrag ist ein echtes Duplikat.
        if (vorhanden != neu) return entries.filter { it.id != neu.id } + neu
        return entries
    }

    /**
     * Faellige Prognosen abrechnen - JEDE gegen einen EIGENEN Messwert.
     *
     * ZWEI REGELN, beide aus Tonis Durchsichten:
     *
     * EINS ZU EINS. Ein Messwert wird hoechstens einmal verbraucht - sonst
     * erzeugt ein einziger Punkt bei 1-min-Prognosen bis zu fuenf
     * "unabhaengige" Widerlegungen, die es nicht gibt.
     *
     * UND ZWAR OPTIMAL. Der zweite Wurf vergab gierig das kuerzeste Paar
     * zuerst und verlor dabei verwertbare Zuordnungen. Tonis Gegenbeispiel,
     * nachgerechnet: Faelligkeiten 0 und 4, Messwerte 3 und 7, Toleranz 4.
     * Gierig gewinnt 4->3 (Abstand 1), danach findet 0 nichts mehr - EINE
     * Zuordnung statt zweier. Richtig ist 0->3 und 4->7.
     *
     * Beide Reihen sind zeitlich geordnet, also ist die beste Zuordnung
     * MONOTON (sie kreuzt sich nicht). Das erlaubt eine exakte Loesung per
     * Dynamischer Programmierung ueber O(n*m) statt einer Naeherung: erst
     * die ANZAHL gueltiger Paarungen maximieren, bei Gleichstand den
     * Gesamtabstand minimieren.
     *
     * VERGLICHEN WIRD GEGEN DEN MESSWERT, NICHT GEGEN DIE GEGENWART. Ein
     * Eingriff nach der Faelligkeit, aber vor einem verspaeteten `settle`,
     * darf eine damals saubere Prognose nicht nachtraeglich entwerten -
     * dasselbe gilt fuer den Konfigurationsstand.
     */
    internal fun settle(
        entries: List<Entry>,
        nowTs: Long,
        samples: List<Sample>,
        consumed: Set<SampleId>,
        toleranceMgdl: Double = SETTLE_TOLERANCE_MGDL,
        matchToleranceMs: Long = MATCH_TOLERANCE_MS,
    ): Settlement {
        val faellig = entries.filter { it.dueTs <= nowTs }.sortedBy { it.dueTs }
        if (faellig.isEmpty()) return Settlement(emptyList(), entries, consumed)
        val offen = entries.filter { it.dueTs > nowTs }
        val brauchbar = samples
            // BEREITS VERBRAUCHTE MESSWERTE SIND AUS DEM SPIEL - auch aus
            // frueheren Aufrufen. Ohne diese Zeile bedient ein Punkt bei
            // 1-min-Zyklen bis zu fuenf Faelligkeiten nacheinander.
            .filter { it.id !in consumed }
            // DANN JEDE KENNUNG NUR EINMAL, schon VOR der Zuordnung. Zwei
            // identische Exportzeilen sind zwei Listenpositionen und koennten
            // zwei Faelligkeiten bedienen; dass `consumed` sie hinterher zu
            // einer zusammenzieht, kommt zu spaet.
            //
            // DIE REIHENFOLGE IST DER PUNKT (Toni, P0): Gesundheit und
            // Endlichkeit werden ERST DANACH geprueft. Andersherum
            // verschwaende eine ungesunde Zeile, BEVOR der Widerspruch
            // sichtbar wird - aus
            //     205 mg/dl healthy=true  /  140 mg/dl healthy=false
            // bei gleicher Kennung wuerde ein sauberes MISSED, obwohl der
            // Export sich selbst widerspricht. Der Vergleich muss den GANZEN
            // Inhalt sehen, auch die unbrauchbaren Felder.
            .let(::dedupe)
            .filter { it.healthy && it.mgdl.isFinite() }
            .sortedBy { it.ts }

        val zuteilung = matchOneToOne(faellig, brauchbar, matchToleranceMs)

        val abgerechnet = faellig.map { e ->
            val treffer = zuteilung[e.id]
                ?: return@map Outcome(e, Verdict.UNVERIFIABLE)
            // DER EINGRIFFSSTAND AM MESSWERT entscheidet, nicht der heutige.
            // BEIDE FELDER ODER NICHTS. Eine gleiche Sequenz aus einer
            // ANDEREN Epoche ist kein Beleg dafuer, dass nichts dazwischen
            // lag - im Gegenteil, der Epochenwechsel IST der Beleg, dass der
            // Persistenzpfad abgerissen ist. Deshalb `same` und nicht `==`.
            if (!InterventionStamp.same(treffer.interventionStamp, e.interventionStamp) ||
                treffer.configGeneration != e.configGeneration
            ) return@map Outcome(e, Verdict.INTERVENED)
            // Die Toleranz sitzt auf der Seite des MODELLS.
            if (treffer.mgdl > e.meanPredictedMgdl + toleranceMgdl)
                Outcome(e, Verdict.MISSED, treffer.ts, treffer.mgdl)
            else Outcome(e, Verdict.MET, treffer.ts, treffer.mgdl)
        }
        // Der Verbrauch wandert in den Rueckgabewert und von dort in die
        // Persistenz. Beschnitten auf das, was noch gebraucht wird: aelter
        // als die aelteste offene Faelligkeit minus Toleranz kann nichts
        // mehr zugeordnet werden. Diese Grenze IST wirksam (anders als das
        // frueher entfernte MAX_AGE_MIN), weil sie an einer Groesse haengt,
        // die tatsaechlich waechst.
        val untergrenze = (offen.minOfOrNull { it.dueTs } ?: nowTs) - matchToleranceMs
        val neuVerbraucht = (consumed + zuteilung.values.map { it.id })
            .filterTo(HashSet()) { it.ts >= untergrenze }
        return Settlement(abgerechnet, offen, neuVerbraucht)
    }

    /** Ergebnis einer Abrechnung - die drei Teile des fortzuschreibenden
     *  Zustands. */
    internal data class Settlement(
        val outcomes: List<Outcome>,
        val remaining: List<Entry>,
        val consumed: Set<SampleId>,
    )

    /**
     * Messwerte mit gleicher [SampleId] zusammenfassen.
     *
     * IDENTISCHE Duplikate fallen zu einem zusammen - dieselbe Zeile zweimal
     * exportiert ist derselbe Messwert, nicht zwei.
     *
     * WIDERSPRUECHLICHE Duplikate (gleiche Kennung, anderer Inhalt) fallen
     * RAUS, nicht auf einen davon zurueck. Welcher der richtige waere, ist
     * nicht entscheidbar; eine Auswahl zu treffen hiesse raten, und geraten
     * wird an einer Beweisgrundlage nicht. Fail-closed heisst hier: der
     * Zeitpunkt bleibt unbewertet.
     */
    private fun dedupe(samples: List<Sample>): List<Sample> =
        samples.groupBy { it.id }.values.mapNotNull { gruppe ->
            val erste = gruppe.first()
            if (gruppe.all { it == erste }) erste else null
        }

    /**
     * Die monotone Eins-zu-eins-Zuordnung, exakt geloest.
     *
     * Zielfunktion in dieser Reihenfolge: (1) moeglichst VIELE Paarungen,
     * (2) bei Gleichstand moeglichst KLEINER Gesamtabstand. Die erste Stufe
     * ist die wichtigere - eine verlorene Zuordnung ist ein verlorener
     * Nachweis, ein paar Sekunden mehr Abstand sind es nicht.
     *
     * Zulaessig ist ein Paar nur bei gleichem Segment und innerhalb der
     * Toleranz; ueber einen Segmentbruch hinweg sind Werte nicht
     * vergleichbar.
     */
    private fun matchOneToOne(
        faellig: List<Entry>,
        samples: List<Sample>,
        matchToleranceMs: Long,
    ): Map<EntryId, Sample> {
        val n = faellig.size
        val m = samples.size
        if (n == 0 || m == 0) return emptyMap()

        fun zulaessig(i: Int, j: Int): Boolean {
            val e = faellig[i]
            val s = samples[j]
            return s.segmentId == e.segmentId && abs(s.ts - e.dueTs) <= matchToleranceMs
        }

        // dp[i][j] = beste Loesung fuer Faelligkeiten ab i und Messwerte ab j,
        // kodiert als (Anzahl, Gesamtabstand). Mehr Paare schlaegt kleineren
        // Abstand - deshalb zwei getrennte Tafeln statt einer gewichteten Summe,
        // die die Rangfolge bei grossen Abstaenden kippen koennte.
        val anzahl = Array(n + 1) { IntArray(m + 1) }
        val kosten = Array(n + 1) { LongArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                // Ohne Paarung an dieser Stelle: den jeweils besseren Zweig.
                var bestA = anzahl[i + 1][j]
                var bestK = kosten[i + 1][j]
                if (anzahl[i][j + 1] > bestA ||
                    (anzahl[i][j + 1] == bestA && kosten[i][j + 1] < bestK)
                ) {
                    bestA = anzahl[i][j + 1]
                    bestK = kosten[i][j + 1]
                }
                if (zulaessig(i, j)) {
                    val a = anzahl[i + 1][j + 1] + 1
                    val k = kosten[i + 1][j + 1] + abs(samples[j].ts - faellig[i].dueTs)
                    if (a > bestA || (a == bestA && k < bestK)) {
                        bestA = a
                        bestK = k
                    }
                }
                anzahl[i][j] = bestA
                kosten[i][j] = bestK
            }
        }

        // Rueckwaerts denselben Pfad ablaufen und die Paare einsammeln.
        val out = HashMap<EntryId, Sample>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            val ohneI = anzahl[i + 1][j] to kosten[i + 1][j]
            val ohneJ = anzahl[i][j + 1] to kosten[i][j + 1]
            val mitPaar = if (zulaessig(i, j))
                (anzahl[i + 1][j + 1] + 1) to (kosten[i + 1][j + 1] + abs(samples[j].ts - faellig[i].dueTs))
            else null
            val besser = { a: Pair<Int, Long>, b: Pair<Int, Long> ->
                a.first > b.first || (a.first == b.first && a.second < b.second)
            }
            var wahl = ohneI
            var art = 0
            if (besser(ohneJ, wahl)) { wahl = ohneJ; art = 1 }
            if (mitPaar != null && besser(mitPaar, wahl)) { wahl = mitPaar; art = 2 }
            when (art) {
                0    -> i++
                1    -> j++
                else -> { out[faellig[i].id] = samples[j]; i++; j++ }
            }
        }
        return out
    }

    /**
     * Wie lange die versprochene Senkung ununterbrochen und BELEGT ausbleibt
     * [min] - und zwar als BELEG GEGEN LAMBDA, nicht als blosse
     * MISSED-Strecke.
     *
     * Der Unterschied ist der Zweck der Funktion: gezaehlt wird nur, was
     * [Outcome.isLambdaEvidence] besteht, also zusaetzlich deutlich ueber der
     * damaligen Sicherheitsuntergrenze lag. Eine reine MISSED-Strecke gibt es
     * hier bewusst NICHT als oeffentliche Groesse - sonst greift der spaetere
     * Verbraucher versehentlich zur schwaecheren Aussage.
     *
     * VIER ABBRUCHGRUENDE. Ein MET (das Modell hatte recht), ein
     * Segmentwechsel (ueber eine Signalluecke gibt es keinen
     * zusammenhaengenden Nachweis), ein INTERVENED oder UNVERIFIABLE - und
     * jede LUECKE IN DER BEOBACHTUNG.
     *
     * Der Vorgaenger uebersprang UNVERIFIABLE und rechnete dann schlicht
     * "juengste minus aelteste Faelligkeit". Zwei MISSED mit 58 Minuten
     * unbeobachteter Zeit dazwischen ergaben so eine 60-Minuten-Strecke - und
     * der zugehoerige Test schrieb das als "staerkeren Beleg" fest.
     * Unbeobachtete Zeit ist aber kein Beleg, sondern ihr Gegenteil.
     *
     * Jetzt zaehlt nur, was LUECKENLOS belegt ist: zwei aufeinanderfolgende
     * MISSED duerfen hoechstens [maxGapMs] auseinanderliegen.
     *
     * @param currentSegmentId das Segment, in dem JETZT gerechnet wird.
     */
    fun lambdaEvidenceStreakMin(
        outcomes: List<Outcome>,
        currentSegmentId: Long,
        minSafetyMarginMgdl: Double,
        maxGapMs: Long = MAX_EVIDENCE_GAP_MS,
    ): Int {
        val sortiert = outcomes.sortedByDescending { it.entry.dueTs }
        var juengste: Long? = null
        var letzte: Long? = null
        for (o in sortiert) {
            if (o.entry.segmentId != currentSegmentId) break
            if (!o.isLambdaEvidence(minSafetyMarginMgdl)) break
            if (juengste == null) juengste = o.entry.dueTs
            else if (letzte!! - o.entry.dueTs > maxGapMs) break
            letzte = o.entry.dueTs
        }
        if (juengste == null || letzte == null) return 0
        return ((juengste - letzte) / 60_000L).toInt()
    }

    /**
     * DER GESAMTE ZUSTAND ALS EINE GENERATION (Tonis Persistenzauflage).
     *
     * Die drei Teile gehoeren zusammen und duerfen NIE einzeln geschrieben
     * oder geladen werden: `entries` sagt, was noch offen ist, `consumed`,
     * welche Messwerte schon vergeben sind, und `outcomes`, was der Streak
     * bisher belegt. Eine halb geladene Generation koennte offene Prognosen
     * gegen bereits verbrauchte Messwerte pruefen oder eine Strecke
     * fortschreiben, deren Anfang fehlt - in beiden Faellen entstuende ein
     * Nachweis, den es nie gab.
     *
     * FAIL-CLOSED HEISST HIER: LEER. Bei Schema- oder Ladefehlern ist der
     * richtige Ersatz der leere Zustand, nicht ein teilweise gelesener. Ein
     * leerer Zustand verzoegert den Nachweis; ein halber erfindet ihn.
     */
    class State internal constructor(
        val entries: List<Entry>,
        val consumed: Set<SampleId>,
        val outcomes: List<Outcome>,
    ) {

        val isEmpty: Boolean get() = entries.isEmpty() && consumed.isEmpty() && outcomes.isEmpty()

        /**
         * KEINE `data class`, und das ist Absicht (Toni, P1).
         *
         * Eine data class haette `copy()` mitgebracht - und damit die
         * Moeglichkeit, aus einer gueltigen Generation eine Teilgeneration zu
         * bauen: `state.copy(consumed = emptySet())` sieht harmlos aus und
         * gibt jeden bereits verbrauchten Messwert wieder frei. Der
         * Konstruktor ist `internal`, die einzigen oeffentlichen Wege in
         * einen Zustand sind [empty] und [restore].
         */
        companion object {

            fun empty() = State(emptyList(), emptySet(), emptyList())
        }
    }

    /**
     * Einen Zyklus auf dem Gesamtzustand abrechnen.
     *
     * Die Klammer um [add] und [settle], damit der Aufrufer die drei Teile
     * nicht von Hand zusammenhalten muss - genau dabei entstuende die
     * halbe Generation, gegen die die Persistenz gebaut ist.
     *
     * Die Ergebnisliste wird auf [OUTCOME_RETENTION_MS] beschnitten: laenger
     * zurueck braucht der Streak nichts, weil ihn jedes MET und jeder
     * Segmentwechsel ohnehin beendet. Ohne Grenze waechst die Datei
     * unbegrenzt - hier ist eine Altersgrenze wirksam, anders als beim
     * frueher entfernten MAX_AGE_MIN.
     */
    fun advance(
        state: State,
        nowTs: Long,
        neu: Entry?,
        samples: List<Sample>,
        toleranceMgdl: Double = SETTLE_TOLERANCE_MGDL,
        matchToleranceMs: Long = MATCH_TOLERANCE_MS,
    ): State {
        // NICHTS EINREIHEN, WAS SCHON ABGERECHNET IST (Toni, P0). Sonst
        // liefe dieselbe Prognose ein zweites Mal durch `settle` und
        // erzeugte doppelte Evidenz - etwa wenn der Aufrufer nach einem
        // Wiederanlauf denselben Zyklus noch einmal anbietet.
        val bereitsAbgerechnet = state.outcomes.map { it.entry.id }.toSet()
        val zulaessig = neu?.takeIf { it.id !in bereitsAbgerechnet }
        val mitNeuem = add(state.entries, zulaessig)
        val abrechnung = settle(mitNeuem, nowTs, samples, state.consumed, toleranceMgdl, matchToleranceMs)
        val alle = (state.outcomes + abrechnung.outcomes)
            .filter { nowTs - it.entry.dueTs <= OUTCOME_RETENTION_MS }
            .sortedBy { it.entry.dueTs }
        return State(abrechnung.remaining, abrechnung.consumed, alle)
    }

    /**
     * Das Ergebnis einer WIEDERHERSTELLUNG aus der Persistenz.
     *
     * Ein geladener Zustand ist kein gerechneter: er kommt aus einer Datei,
     * die beschaedigt, veraltet oder manipuliert sein kann. Syntaktisch
     * gueltig heisst nicht semantisch moeglich - ein frei eingetragenes
     * MISSED mit plausiblen Zahlen erzeugt unmittelbar lambda-Evidenz.
     * Deshalb prueft [restore] die Bedeutung, nicht nur die Form.
     */
    sealed interface Restored {

        data class Valid(val state: State) : Restored

        /** Semantisch unmoeglich - der Grund ist fuer den Trail benannt, die
         *  Entscheidung trifft der Aufrufer (Store). */
        data class Invalid(val reason: String) : Restored
    }

    /**
     * DIE EINZIGE TUER IN EINEN GELADENEN ZUSTAND.
     *
     * Sie liegt im Kern und nicht im Codec, damit es nur EINE Wahrheit
     * darueber gibt, was ein moeglicher Zustand ist. Ein Codec, der selbst
     * pruefte, waere eine zweite - und die beiden liefen mit dem naechsten
     * Feld auseinander.
     *
     * Geprueft wird, was aus dem Zustand selbst folgt: dass eine Faelligkeit
     * nach ihrer Quelle liegt, dass eine behauptete Senkung gross genug war,
     * um eingereiht worden zu sein, dass ein Messurteil einen Messwert hat
     * und ein Nicht-Urteil keinen, und dass der verwendete Messwert im
     * Zuordnungsfenster lag. Jede dieser Bedingungen kann eine echte
     * Rechnung gar nicht verletzen - wer sie verletzt, kommt nicht aus einer
     * Rechnung.
     */
    fun restore(
        entries: List<Entry>,
        consumed: Set<SampleId>,
        outcomes: List<Outcome>,
        kopfstand: InterventionStamp,
        matchToleranceMs: Long = MATCH_TOLERANCE_MS,
        minDropMgdl: Double = MIN_PROMISED_DROP_MGDL,
    ): Restored {
        fun pruefeEintrag(e: Entry, wo: String): String? = when {
            !e.anchorMgdl.isFinite() || !e.meanPredictedMgdl.isFinite() -> "$wo: Zahl nicht endlich"
            e.configGeneration.isBlank()                                -> "$wo: leere Konfigurationskennung"
            // OHNE GUELTIGE HERKUNFT IST EIN EINTRAG UNBRAUCHBAR. Ein leerer
            // Epochenname waere eine Wildcard, die auf jede fremde Epoche
            // passt - genau der Freibrief, den der Ledger schon einmal beim
            // leeren Pumpen-Serial hatte (Live-Befund 09.08.). Die Pruefung
            // steht HIER und nicht im Codec, weil `restore` mehr als einen
            // Aufrufer hat.
            !e.interventionStamp.valid                                  ->
                "$wo: ungueltiger Eingriffsstempel ${e.interventionStamp}"
            e.dueTs <= e.sourceTs                                       -> "$wo: dueTs <= sourceTs"
            e.promisedDropMgdl < minDropMgdl                            ->
                "$wo: behauptete Senkung ${e.promisedDropMgdl} unter $minDropMgdl"

            e.safetyLowerPredictedMgdl?.isFinite() == false             -> "$wo: safetyLower nicht endlich"
            e.lambda?.isFinite() == false                               -> "$wo: lambda nicht endlich"
            else                                                        -> null
        }

        // KEINE BEHAUPTUNG AUS DER ZUKUNFT - aber NUR innerhalb der Epoche.
        //
        // Ein Eintrag mit einer hoeheren Sequenz als der Kopfstand DERSELBEN
        // Epoche ist ein Widerspruch: Kopf und Eintraege sind auseinander-
        // gelaufen, und zwar in die gefaehrliche Richtung (der Vergleich bei
        // der Abrechnung saehe "gleich geblieben" fuer eine Strecke, in der
        // eingegriffen wurde).
        //
        // Eintraege einer ANDEREN Epoche sind dagegen KEIN Widerspruch,
        // sondern der Normalfall nach Reparatur, Quarantaene oder Rollback -
        // und sie brauchen keinen Riegel, weil `settle` sie ohnehin als
        // INTERVENED abrechnet (s. InterventionStamp.same). Sie zu verwerfen
        // wuerde den gesamten `consumed`-Bestand mitreissen und damit
        // Nachweis kosten, den niemand gewinnt.
        // KEIN NULLABLE KOPFSTAND. Faende sich beim Laden keiner, muesste
        // die Persistenzschicht eine FRISCHE Epoche eroeffnen - das ist die
        // sichere Aussage ("wir wissen nicht, was seither geschah"). Ein
        // schweigender Riegel waere die unsichere.
        if (!kopfstand.valid) return Restored.Invalid("ungueltiger Kopfstempel $kopfstand")
        (entries.map { it.interventionStamp } + outcomes.map { it.entry.interventionStamp })
            .filter { it.epochId == kopfstand.epochId }
            .maxOfOrNull { it.sequence }
            ?.let { hoechste ->
                if (hoechste > kopfstand.sequence) return Restored.Invalid(
                    "Eintrag mit Sequenz $hoechste ueber dem Kopfstand ${kopfstand.sequence} " +
                        "in Epoche ${kopfstand.epochId}",
                )
            }

        entries.forEach { e -> pruefeEintrag(e, "entry")?.let { return Restored.Invalid(it) } }
        if (entries.map { it.id }.toSet().size != entries.size)
            return Restored.Invalid("doppelte Entry-Kennung")

        outcomes.forEach { o ->
            pruefeEintrag(o.entry, "outcome")?.let { return Restored.Invalid(it) }
            val hatUrteil = o.verdict == Verdict.MET || o.verdict == Verdict.MISSED
            // BEIDE Felder oder KEINES - ein halbes Messurteil gibt es nicht.
            // Der erste Wurf pruefte `actualTs != null && actualMgdl != null`;
            // damit rutschte ein INTERVENED mit NUR einem der beiden Felder
            // durch, weil `hatMesswert` dann false war und zu "kein Urteil"
            // passte (Toni, P1).
            val vollstaendig = o.actualTs != null && o.actualMgdl != null
            val teilweise = o.actualTs != null || o.actualMgdl != null
            if (hatUrteil && !vollstaendig) return Restored.Invalid(
                // Ein MISSED ohne Messwert waere ein Nachweis aus dem Nichts.
                "${o.verdict}: Messurteil ohne vollstaendigen Messwert",
            )
            if (!hatUrteil && teilweise) return Restored.Invalid(
                // Ein UNVERIFIABLE MIT Messwert behauptete, man haette
                // gemessen und trotzdem nicht geurteilt.
                "${o.verdict}: Nicht-Urteil traegt einen Messwert",
            )
            if (o.actualMgdl?.isFinite() == false) return Restored.Invalid("actualMgdl nicht endlich")
            val ts = o.actualTs
            if (ts != null && abs(ts - o.entry.dueTs) > matchToleranceMs)
                return Restored.Invalid("actualTs ausserhalb der Zuordnungstoleranz")
        }
        if (outcomes.map { it.entry.id }.toSet().size != outcomes.size)
            return Restored.Invalid("doppelte Outcome-Kennung")

        // LISTUEBERGREIFEND EINDEUTIG (Toni, P0). Beide Listen waren nur
        // INTERN duplikatfrei - dieselbe Kennung konnte gleichzeitig offen
        // UND abgerechnet sein. Die offene Fassung wuerde dann ein zweites
        // Mal abgerechnet und erzeugte doppelte Evidenz aus einer einzigen
        // Prognose. Eine echte Rechnung kann das nicht herstellen: `settle`
        // nimmt jeden abgerechneten Eintrag aus `remaining` heraus.
        val ueberschneidung = entries.map { it.id }.toSet() intersect outcomes.map { it.entry.id }.toSet()
        if (ueberschneidung.isNotEmpty())
            return Restored.Invalid("Kennung zugleich offen und abgerechnet: ${ueberschneidung.first()}")

        return Restored.Valid(State(entries, consumed, outcomes))
    }

    /** Wie weit die Ergebnisliste zurueckreicht [ms]. Vier Stunden - deutlich
     *  mehr als jede Strecke, die ein MET oder Segmentwechsel ueberlebt. */
    const val OUTCOME_RETENTION_MS = 4L * 60 * 60 * 1000

    /**
     * Mindesthoehe einer behaupteten Senkung [mg/dl], damit sie ueberhaupt
     * eingereiht wird - in der Groessenordnung des Sensorrauschens.
     */
    const val MIN_PROMISED_DROP_MGDL = 10.0

    /** Toleranz beim Abrechnen [mg/dl]; s. [settle]. */
    const val SETTLE_TOLERANCE_MGDL = 5.0

    /**
     * Wie nah ein Messwert an der Faelligkeit liegen muss [ms]. Bei
     * 1-min-CGM sind das zwei Punkte in jede Richtung - genug gegen einzelne
     * Aussetzer, zu wenig fuer eine echte Luecke.
     */
    const val MATCH_TOLERANCE_MS = 150_000L

    /**
     * Groesster Abstand zwischen zwei belegten Ausbleibern, damit die Strecke
     * als zusammenhaengend gilt [ms]. Darueber ist die Zwischenzeit
     * unbeobachtet und traegt keinen Nachweis.
     */
    const val MAX_EVIDENCE_GAP_MS = 300_000L

    // KEINE ALTERSGRENZE HIER, und das ist ein Befund aus der eigenen
    // Mutationsprobe: der erste Wurf trug ein MAX_AGE_MIN = 240, das nie
    // greifen konnte. Die Filterung sass auf den NICHT faelligen Eintraegen,
    // und die sind per Konstruktion hoechstens einen Horizont alt. Faellige
    // verschwinden ohnehin. Eine Begrenzung gehoert, wenn ueberhaupt, in die
    // Persistenzschicht, wo die Dateigroesse das Problem ist - eine
    // Konstante, die nichts tut, behauptet einen Schutz, den es nicht gibt.
}

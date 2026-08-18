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
        val interventionRevision: Long,
        /** Der Konfigurationsstand zu diesem Zeitpunkt - aus demselben Grund. */
        val configGeneration: String,
    )

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
        val interventionRevision: Long,
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

        /** Zaehlt dieses Ergebnis als Beleg gegen die pessimistische Annahme? */
        val isEvidence: Boolean get() = verdict == Verdict.MISSED
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
        interventionRevision: Long,
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
            interventionRevision = interventionRevision,
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
    fun add(entries: List<Entry>, neu: Entry?): List<Entry> {
        if (neu == null) return entries
        if (entries.any { it.id == neu.id }) return entries
        return entries + neu
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
    fun settle(
        entries: List<Entry>,
        nowTs: Long,
        samples: List<Sample>,
        toleranceMgdl: Double = SETTLE_TOLERANCE_MGDL,
        matchToleranceMs: Long = MATCH_TOLERANCE_MS,
    ): Pair<List<Outcome>, List<Entry>> {
        val faellig = entries.filter { it.dueTs <= nowTs }.sortedBy { it.dueTs }
        if (faellig.isEmpty()) return emptyList<Outcome>() to entries
        val offen = entries.filter { it.dueTs > nowTs }
        val brauchbar = samples
            .filter { it.healthy && it.mgdl.isFinite() }
            .sortedBy { it.ts }

        val zuteilung = matchOneToOne(faellig, brauchbar, matchToleranceMs)

        val abgerechnet = faellig.map { e ->
            val treffer = zuteilung[e.id]
                ?: return@map Outcome(e, Verdict.UNVERIFIABLE)
            // DER EINGRIFFSSTAND AM MESSWERT entscheidet, nicht der heutige.
            if (treffer.interventionRevision != e.interventionRevision ||
                treffer.configGeneration != e.configGeneration
            ) return@map Outcome(e, Verdict.INTERVENED)
            // Die Toleranz sitzt auf der Seite des MODELLS.
            if (treffer.mgdl > e.meanPredictedMgdl + toleranceMgdl)
                Outcome(e, Verdict.MISSED, treffer.ts, treffer.mgdl)
            else Outcome(e, Verdict.MET, treffer.ts, treffer.mgdl)
        }
        return abgerechnet to offen
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
     * [min].
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
    fun missedStreakMin(
        outcomes: List<Outcome>,
        currentSegmentId: Long,
        maxGapMs: Long = MAX_EVIDENCE_GAP_MS,
    ): Int {
        val sortiert = outcomes.sortedByDescending { it.entry.dueTs }
        var juengste: Long? = null
        var letzte: Long? = null
        for (o in sortiert) {
            if (o.entry.segmentId != currentSegmentId) break
            if (o.verdict != Verdict.MISSED) break
            if (juengste == null) juengste = o.entry.dueTs
            else if (letzte!! - o.entry.dueTs > maxGapMs) break
            letzte = o.entry.dueTs
        }
        if (juengste == null || letzte == null) return 0
        return ((juengste - letzte) / 60_000L).toInt()
    }

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

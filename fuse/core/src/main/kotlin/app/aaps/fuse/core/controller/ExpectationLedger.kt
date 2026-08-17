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
 * DREI FEHLER DES ERSTEN WURFS, alle von Toni gefunden, bevor der Baustein
 * einen Aufrufer hatte:
 *
 * (1) ZEITLICHE ZUORDNUNG. `settle` bewertete ALLE ueberfaelligen Prognosen
 *     gegen den AKTUELLEN Messwert. Nach einer 20-min-Luecke waeren mehrere
 *     verschiedene Faelligkeiten gegen denselben spaeteren Wert geprueft
 *     worden - und der Test "mehrere Faelligkeiten gemeinsam abrechnen"
 *     schrieb dieses falsche Verhalten sogar fest. Jetzt braucht jede
 *     Faelligkeit einen Messwert NAHE ihrem eigenen `dueTs`; fehlt er, ist
 *     sie UNVERIFIABLE. Ein spaeterer Wert wird NIE rueckwirkend fuer
 *     mehrere Faelligkeiten verwendet.
 *
 * (2) WELCHE BAHN. `predictedMgdl` sagte nicht, was gemeint ist - und der
 *     Unterschied ist der ganze Punkt: die MITTELBAHN ist eine Erwartung,
 *     die eintreten soll. Die SICHERHEITSUNTERGRENZE ist ein pessimistisches
 *     Risikoszenario, das gerade NICHT eintreten soll. Sagt die Untergrenze
 *     70 und der reale BG bleibt bei 180, ist kein Versprechen ausgeblieben -
 *     ein Risiko hat sich nicht realisiert. Der Nachweis fuer die
 *     lambda-Adaption laeuft deshalb ausschliesslich ueber die Mittelbahn;
 *     die Untergrenze faehrt als Kontext mit.
 *
 * (3) UNABHAENGIGKEIT. Bei einer Prognose je Minute und 30 min Horizont
 *     beschreiben 30 aufeinanderfolgende MISSED weitgehend DASSELBE Plateau -
 *     das sind keine 30 unabhaengigen Widerlegungen. Gemessen wird deshalb
 *     die DAUER der ununterbrochenen Ausbleib-Strecke, nicht ihre Anzahl.
 *     Und eine Signalluecke darf die Strecke nicht ueberbruecken: nach einem
 *     Segmentbruch beginnt der aktive Nachweis neu, auch wenn das Archiv die
 *     alten Ergebnisse behaelt.
 */
object ExpectationLedger {

    /**
     * Eine abgegebene Prognose, die spaeter geprueft werden kann.
     *
     * @param sourceTs der Messzeitpunkt, auf dem die Prognose beruht (NICHT
     *   der Rechenzeitpunkt - die Faelligkeit haengt an der Messuhr).
     * @param dueTs wann sie faellig ist.
     * @param segmentId die Signalsegment-Kennung bei Abgabe. Wechselt sie,
     *   liegt ein Bruch dazwischen und die Strecke beginnt neu.
     * @param anchorMgdl der BG, von dem aus prognostiziert wurde.
     * @param meanPredictedMgdl die MITTELBAHN - die Erwartung, die eintreten
     *   soll. Nur sie taugt als Versprechen.
     * @param safetyLowerPredictedMgdl die Sicherheitsuntergrenze zum selben
     *   Zeitpunkt. REINER KONTEXT: ein Risikoszenario, das nicht eintreten
     *   soll, ist kein Versprechen und geht nie in ein MET/MISSED ein.
     * @param lambda der wirksame Bolus-Deckungs-Abschlag bei Abgabe - die
     *   Groesse, um die es spaeter geht.
     * @param discountMgdl / bgiMgdl die beiden Terme der Bahn bei Abgabe.
     *   Ohne sie ist hinterher nicht rekonstruierbar, WARUM die Bahn lag,
     *   wo sie lag.
     * @param configGeneration Kennung der Regelwerks-/Konfigurationsstand.
     *   Aendert sich etwas an Modell oder Einstellungen, sind aeltere
     *   Eintraege nicht mehr mit neueren vergleichbar.
     */
    data class Entry(
        val sourceTs: Long,
        val dueTs: Long,
        val segmentId: Long,
        val anchorMgdl: Double,
        val meanPredictedMgdl: Double,
        val safetyLowerPredictedMgdl: Double? = null,
        val lambda: Double? = null,
        val discountMgdl: Double? = null,
        val bgiMgdl: Double? = null,
        val configGeneration: String? = null,
    ) {

        /** Die behauptete Senkung der MITTELBAHN [mg/dl], immer positiv. */
        val promisedDropMgdl: Double get() = anchorMgdl - meanPredictedMgdl
    }

    /** Wie eine faellige Prognose ausgegangen ist. */
    enum class Verdict {
        /** Die Mittelbahn ist mindestens so weit gefallen wie versprochen. */
        MET,

        /** Die versprochene Senkung ist ausgeblieben. Der Fall, der den
         *  Sackgassenwaechter naehrt. */
        MISSED,

        /**
         * Nicht bewertbar - KEIN halbes MET und kein halbes MISSED.
         *
         * Zwei Ursachen, beide gleich behandelt: zum Faelligkeitszeitpunkt
         * fehlte ein Messwert IN DER NAEHE (s. (1) im Klassenkopf), oder die
         * Signalgesundheit fehlte. Als MET zu zaehlen verwaessert den
         * Nachweis; als MISSED zu zaehlen macht aus einem Sensorausfall einen
         * Freibrief fuer mehr Insulin.
         */
        UNVERIFIABLE,
    }

    /** Das Ergebnis einer faellig gewordenen Prognose. */
    data class Outcome(
        val entry: Entry,
        val verdict: Verdict,
        /** Zeitpunkt des verwendeten Messwerts; `null` bei UNVERIFIABLE.
         *  Steht getrennt von `dueTs`, damit die Zuordnung pruefbar ist. */
        val actualTs: Long? = null,
        val actualMgdl: Double? = null,
    ) {

        /**
         * Abweichung von der MITTELBAHN [mg/dl]. Positiv = die Senkung blieb
         * aus. `null`, wenn nicht bewertbar.
         */
        val meanErrorMgdl: Double?
            get() = actualMgdl?.let { it - entry.meanPredictedMgdl }

        /**
         * Abstand des gemessenen Werts zur damaligen SICHERHEITSUNTERGRENZE
         * [mg/dl]. Positiv = darueber geblieben. REINE DIAGNOSE - hieraus
         * wird kein Urteil abgeleitet, s. (2) im Klassenkopf.
         */
        val distanceFromSafetyLowerMgdl: Double?
            get() = entry.safetyLowerPredictedMgdl?.let { sl -> actualMgdl?.let { it - sl } }
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
        safetyLowerPredictedMgdl: Double? = null,
        lambda: Double? = null,
        discountMgdl: Double? = null,
        bgiMgdl: Double? = null,
        configGeneration: String? = null,
        minDropMgdl: Double = MIN_PROMISED_DROP_MGDL,
    ): Entry? {
        if (anchorMgdl == null || !anchorMgdl.isFinite()) return null
        if (meanPredictedMgdl == null || !meanPredictedMgdl.isFinite()) return null
        if (horizonMin <= 0) return null
        // NUR SENKUNGEN DER MITTELBAHN: eine Prognose "bleibt gleich" oder
        // "steigt" kann nicht ausbleiben.
        if (anchorMgdl - meanPredictedMgdl < minDropMgdl) return null
        return Entry(
            sourceTs = sourceTs,
            dueTs = sourceTs + horizonMin * 60_000L,
            segmentId = segmentId,
            anchorMgdl = anchorMgdl,
            meanPredictedMgdl = meanPredictedMgdl,
            safetyLowerPredictedMgdl = safetyLowerPredictedMgdl?.takeIf { it.isFinite() },
            lambda = lambda?.takeIf { it.isFinite() },
            discountMgdl = discountMgdl?.takeIf { it.isFinite() },
            bgiMgdl = bgiMgdl?.takeIf { it.isFinite() },
            configGeneration = configGeneration,
        )
    }

    /** Ein Messwert mit seinem Zeitpunkt - die Reihe, gegen die abgerechnet wird. */
    data class Sample(val ts: Long, val mgdl: Double)

    /**
     * Faellige Prognosen abrechnen - JEDE gegen einen Messwert in der Naehe
     * IHRER EIGENEN Faelligkeit.
     *
     * @param samples die verfuegbaren Messwerte. Es wird der zeitlich
     *   naechste zu `dueTs` gesucht; liegt er weiter als [matchToleranceMs]
     *   entfernt, ist die Prognose UNVERIFIABLE.
     * @param signalHealthy Gesundheit ZUM ABRECHNUNGSZEITPUNKT.
     * @return die abgerechneten Ergebnisse und die verbleibenden Eintraege.
     *   Ueberalterte Eintraege (s. [MAX_AGE_MIN]) verfallen dabei als
     *   UNVERIFIABLE, statt die Liste unbegrenzt wachsen zu lassen.
     */
    fun settle(
        entries: List<Entry>,
        nowTs: Long,
        samples: List<Sample>,
        signalHealthy: Boolean,
        toleranceMgdl: Double = SETTLE_TOLERANCE_MGDL,
        matchToleranceMs: Long = MATCH_TOLERANCE_MS,
    ): Pair<List<Outcome>, List<Entry>> {
        val faellig = entries.filter { it.dueTs <= nowTs }
        if (faellig.isEmpty()) return emptyList<Outcome>() to entries
        val offen = entries.filter { it.dueTs > nowTs }
        val abgerechnet = faellig.map { e ->
            // DER MESSWERT MUSS ZUR FAELLIGKEIT PASSEN, nicht zur Gegenwart.
            // Ohne diese Zuordnung wuerden nach einer Luecke mehrere
            // Faelligkeiten gegen denselben spaeten Wert geprueft.
            val treffer = samples
                .filter { abs(it.ts - e.dueTs) <= matchToleranceMs && it.mgdl.isFinite() }
                .minByOrNull { abs(it.ts - e.dueTs) }
            when {
                !signalHealthy || treffer == null                            -> Outcome(e, Verdict.UNVERIFIABLE)
                // Die Toleranz sitzt auf der Seite des MODELLS: erst wenn der
                // gemessene Wert die Mittelbahn um mehr als das Messrauschen
                // ueberschreitet, gilt die Senkung als ausgeblieben.
                treffer.mgdl > e.meanPredictedMgdl + toleranceMgdl           ->
                    Outcome(e, Verdict.MISSED, treffer.ts, treffer.mgdl)

                else                                                         ->
                    Outcome(e, Verdict.MET, treffer.ts, treffer.mgdl)
            }
        }
        return abgerechnet to offen
    }

    /**
     * WIE LANGE die versprochene Senkung ununterbrochen ausbleibt [min].
     *
     * DAUER STATT ANZAHL, und das ist Tonis dritter Befund: bei einer
     * Prognose je Minute und 30 min Horizont beschreiben 30 aufeinander-
     * folgende MISSED weitgehend dasselbe Plateau. Sie zu zaehlen erzeugt
     * einen Nachweis, den es nicht gibt; ihre zeitliche Ausdehnung zu messen
     * beschreibt genau das, was gemeint ist.
     *
     * EIN SEGMENTBRUCH BEENDET DIE STRECKE. Ueber eine Signalluecke hinweg
     * gibt es keinen zusammenhaengenden Nachweis - das Archiv behaelt die
     * alten Ergebnisse, der aktive Nachweis beginnt neu.
     *
     * Ein MET beendet sie ebenfalls: wirkt das Insulin auch nur einmal wie
     * versprochen, ist die pessimistische Annahme nicht widerlegt.
     * UNVERIFIABLE zaehlt weder mit noch bricht es - solange das Segment
     * dasselbe bleibt.
     *
     * @param currentSegmentId das Segment, in dem JETZT gerechnet wird.
     */
    fun missedStreakMin(outcomes: List<Outcome>, currentSegmentId: Long): Int {
        val sortiert = outcomes.sortedByDescending { it.entry.dueTs }
        var juengste: Long? = null
        var aelteste: Long? = null
        for (o in sortiert) {
            if (o.entry.segmentId != currentSegmentId) break
            when (o.verdict) {
                Verdict.MET          -> break
                Verdict.MISSED       -> {
                    if (juengste == null) juengste = o.entry.dueTs
                    aelteste = o.entry.dueTs
                }

                Verdict.UNVERIFIABLE -> Unit
            }
        }
        if (juengste == null || aelteste == null) return 0
        return ((juengste - aelteste) / 60_000L).toInt()
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
     * 1-min-CGM ist das der uebernaechste Punkt - genug gegen einzelne
     * Aussetzer, zu wenig fuer eine echte Luecke.
     */
    const val MATCH_TOLERANCE_MS = 150_000L

    /** Nach dieser Zeit verfaellt ein nicht abgerechneter Eintrag [min]. */
    const val MAX_AGE_MIN = 240
}

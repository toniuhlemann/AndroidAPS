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
     * Faellige Prognosen abrechnen - JEDE gegen einen EIGENEN Messwert.
     *
     * EIN MESSWERT WIRD HOECHSTENS EINMAL VERBRAUCHT. Ohne diese Regel kann
     * derselbe Punkt fuer mehrere benachbarte Faelligkeiten der naechste
     * Treffer sein (bei 1-min-Prognosen und 150 s Toleranz bis zu fuenfmal) -
     * ein einziger Messwert wuerde fuenf voneinander unabhaengige
     * Widerlegungen erzeugen, die es nicht gibt.
     *
     * @param currentIntervention der aktuelle Eingriffsstand. Weicht er vom
     *   Stand bei Abgabe ab, ist das Ergebnis [Verdict.INTERVENED].
     */
    fun settle(
        entries: List<Entry>,
        nowTs: Long,
        samples: List<Sample>,
        currentIntervention: Long,
        toleranceMgdl: Double = SETTLE_TOLERANCE_MGDL,
        matchToleranceMs: Long = MATCH_TOLERANCE_MS,
    ): Pair<List<Outcome>, List<Entry>> {
        val faellig = entries.filter { it.dueTs <= nowTs }.sortedBy { it.dueTs }
        if (faellig.isEmpty()) return emptyList<Outcome>() to entries
        val offen = entries.filter { it.dueTs > nowTs }

        // (1) EIN EINGRIFF SCHLAEGT ALLES: ohne unveraenderte Ausgangslage ist
        //     das Ergebnis kein Urteil ueber das Modell. Diese Faelligkeiten
        //     nehmen auch an der Zuordnung nicht teil - sie duerfen keinem
        //     bewertbaren Fall den Messwert wegnehmen.
        val (bewertbar, eingegriffen) = faellig.partition { it.interventionRevision == currentIntervention }

        // (2) ZUORDNUNG NACH ABSTAND, nicht nach Reihenfolge. Der erste Wurf
        //     ging die Faelligkeiten chronologisch durch - dabei griff sich
        //     die FRUEHESTE den Wert, auch wenn eine spaetere ihn exakt
        //     getroffen haette. Jetzt werden alle zulaessigen Paare nach
        //     Abstand sortiert und gierig vergeben: das beste Paar zuerst,
        //     und jeder Messwert wie jede Faelligkeit nur EINMAL.
        val paare = bewertbar.flatMap { e ->
            samples
                .filter {
                    it.healthy && it.mgdl.isFinite() && it.segmentId == e.segmentId &&
                        abs(it.ts - e.dueTs) <= matchToleranceMs
                }
                .map { s -> Triple(abs(s.ts - e.dueTs), e, s) }
        }.sortedWith(compareBy({ it.first }, { it.second.dueTs }, { it.third.ts }))

        val zuteilung = HashMap<Long, Sample>()
        val verbraucht = HashSet<Long>()
        for ((_, e, s) in paare) {
            if (e.dueTs in zuteilung || s.ts in verbraucht) continue
            zuteilung[e.dueTs] = s
            verbraucht += s.ts
        }

        val abgerechnet = faellig.map { e ->
            if (e in eingegriffen) return@map Outcome(e, Verdict.INTERVENED)
            val treffer = zuteilung[e.dueTs] ?: return@map Outcome(e, Verdict.UNVERIFIABLE)
            // (3) Die Toleranz sitzt auf der Seite des MODELLS.
            if (treffer.mgdl > e.meanPredictedMgdl + toleranceMgdl)
                Outcome(e, Verdict.MISSED, treffer.ts, treffer.mgdl)
            else Outcome(e, Verdict.MET, treffer.ts, treffer.mgdl)
        }
        return abgerechnet to offen
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

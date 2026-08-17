package app.aaps.fuse.core.controller

/**
 * WAS FUSE VERSPROCHEN HAT, UND WAS DAVON EINGETROFFEN IST.
 *
 * Erster Baustein des Sackgassenwaechters (Tonis Vertrag 17.08. nachts).
 * DIESE KLASSE ENTSCHEIDET NICHTS - sie misst nur. Die spaetere
 * lambda-Adaption haengt an ihr, aber sie selbst kann keine Dosis aendern.
 * Das ist Absicht: der Messteil muss laufen und Daten sammeln, bevor
 * irgendetwas am Hypo-Schutz haengt.
 *
 * DER ANLASS: FUSE stand am 17.08. 89 Zyklen lang bei 0,00 U und BG 169-216.
 * Ursache ist keine falsche Arithmetik, sondern eine pessimistische ANNAHME
 * ohne Korrekturschleife - `r` ist bereits BGI-bereinigt (r = D), der
 * Bolus-Deckungs-Abschlag zieht die Insulinwirkung mit lambda = 1 ein
 * zweites Mal ab, die Bahn ein drittes Mal. Ergebnis D - 2I statt D - I.
 * Bei einer FRISCHEN Stoerung ist das richtig: der insulinverdeckte Anteil
 * koennte im naechsten Moment verschwinden. Bei einem seit Stunden
 * bestaetigten Plateau ist es hundertfach widerlegt - und FUSE trifft die
 * Annahme trotzdem jede Minute neu.
 *
 * Toni: "nicht unbedingt dreimal arithmetisch abgezogen, sondern eine
 * pessimistische Annahme, die trotz wiederholter Gegenbeobachtung niemals
 * korrigiert wird."
 *
 * UM DIESE ANNAHME ZU WIDERLEGEN, braucht es einen NACHWEIS, und der
 * verlangt zwei Zeitpunkte: was wurde wann versprochen, und was ist zu
 * diesem Zeitpunkt tatsaechlich eingetreten. Ein Momentanwert kann das
 * nicht - "BG ist hoch" und "r ist positiv" sind ausdruecklich KEIN
 * Nachweis (Tonis Abnahmekriterium 3).
 *
 * WARUM NUR SENKUNGEN EINGEREIHT WERDEN: eine Prognose, die gar keine
 * Absenkung behauptet, kann auch nicht ausbleiben. Nur wo FUSE gesagt hat
 * "es geht runter", ist ein Ausbleiben eine Aussage ueber das Modell.
 */
object ExpectationLedger {

    /**
     * Eine abgegebene Prognose, die spaeter geprueft werden kann.
     *
     * @param issuedTs wann sie abgegeben wurde.
     * @param dueTs wann sie faellig ist (issuedTs + Freigabehorizont).
     * @param anchorMgdl der BG, von dem aus prognostiziert wurde.
     * @param predictedMgdl der prognostizierte Wert zum Faelligkeitszeitpunkt.
     */
    data class Entry(
        val issuedTs: Long,
        val dueTs: Long,
        val anchorMgdl: Double,
        val predictedMgdl: Double,
    ) {

        /** Die BEHAUPTETE Senkung [mg/dl], immer positiv (sonst gaebe es den
         *  Eintrag nicht). */
        val promisedDropMgdl: Double get() = anchorMgdl - predictedMgdl
    }

    /** Wie eine faellige Prognose ausgegangen ist. */
    enum class Verdict {
        /** Der BG ist mindestens so weit gefallen wie versprochen. */
        MET,

        /** Die Senkung ist ausgeblieben - der gemessene Wert liegt ueber der
         *  Prognose. Das ist der Fall, der den Sackgassenwaechter naehrt. */
        MISSED,

        /**
         * Nicht bewertbar: zum Faelligkeitszeitpunkt fehlte ein brauchbarer
         * Messwert oder die Signalgesundheit. AUSDRUECKLICH KEIN "MET" - ein
         * unbeobachteter Zeitpunkt ist kein eingehaltenes Versprechen, und
         * ihn als solches zu zaehlen wuerde den Nachweis verwaessern.
         */
        UNVERIFIABLE,
    }

    /** Das Ergebnis einer faellig gewordenen Prognose. */
    data class Outcome(
        val entry: Entry,
        val verdict: Verdict,
        /** Der gemessene Wert bei Faelligkeit; `null` bei UNVERIFIABLE. */
        val actualMgdl: Double? = null,
    ) {

        /**
         * Um wieviel die Senkung hinter dem Versprechen zurueckblieb [mg/dl].
         * Positiv = ausgeblieben. `null`, wenn nicht bewertbar.
         */
        val shortfallMgdl: Double?
            get() = actualMgdl?.let { it - entry.predictedMgdl }
    }

    /**
     * Eine Prognose einreihen - oder `null`, wenn sie nichts behauptet, das
     * spaeter widerlegbar waere.
     *
     * @param minDropMgdl Mindesthoehe der behaupteten Senkung. Darunter ist
     *   der Unterschied zwischen Prognose und Messrauschen nicht mehr
     *   feststellbar, und ein "ausgeblieben" waere nicht belastbar.
     */
    fun issue(
        issuedTs: Long,
        anchorMgdl: Double?,
        predictedMgdl: Double?,
        horizonMin: Int,
        minDropMgdl: Double = MIN_PROMISED_DROP_MGDL,
    ): Entry? {
        if (anchorMgdl == null || !anchorMgdl.isFinite()) return null
        if (predictedMgdl == null || !predictedMgdl.isFinite()) return null
        if (horizonMin <= 0) return null
        // NUR SENKUNGEN: eine Prognose "es bleibt gleich" oder "es steigt"
        // kann nicht ausbleiben.
        if (anchorMgdl - predictedMgdl < minDropMgdl) return null
        return Entry(
            issuedTs = issuedTs,
            dueTs = issuedTs + horizonMin * 60_000L,
            anchorMgdl = anchorMgdl,
            predictedMgdl = predictedMgdl,
        )
    }

    /**
     * Faellige Prognosen abrechnen.
     *
     * @return die abgerechneten Ergebnisse und die verbleibenden, noch nicht
     *   faelligen Eintraege. Der Aufrufer haelt Letztere weiter vor.
     */
    fun settle(
        entries: List<Entry>,
        nowTs: Long,
        actualMgdl: Double?,
        signalHealthy: Boolean,
        toleranceMgdl: Double = SETTLE_TOLERANCE_MGDL,
    ): Pair<List<Outcome>, List<Entry>> {
        val faellig = entries.filter { it.dueTs <= nowTs }
        if (faellig.isEmpty()) return emptyList<Outcome>() to entries
        val offen = entries.filter { it.dueTs > nowTs }
        val brauchbar = signalHealthy && actualMgdl != null && actualMgdl.isFinite()
        val abgerechnet = faellig.map { e ->
            if (!brauchbar) Outcome(e, Verdict.UNVERIFIABLE)
            // Die Toleranz sitzt auf der Seite des MODELLS: erst wenn der
            // gemessene Wert die Prognose um mehr als das Messrauschen
            // ueberschreitet, gilt die Senkung als ausgeblieben. Ein
            // knappes Verfehlen ist kein Nachweis.
            else if (actualMgdl!! > e.predictedMgdl + toleranceMgdl) Outcome(e, Verdict.MISSED, actualMgdl)
            else Outcome(e, Verdict.MET, actualMgdl)
        }
        return abgerechnet to offen
    }

    /**
     * WIE VIELE FAELLIGE PROGNOSEN IN FOLGE ZULETZT AUSGEBLIEBEN SIND.
     *
     * IN FOLGE, und das ist der Punkt: ein einzelnes MET beendet die Serie.
     * Wirkt das Insulin auch nur einmal wie versprochen, ist die
     * pessimistische Annahme in diesem Zyklus nicht widerlegt, und der
     * Nachweis beginnt von vorn. Tonis Abnahmekriterium 5 ("Richtungswende
     * setzt den Discount sofort zurueck") faengt den akuten Fall ab; diese
     * Serie ist das langsamere Gegenstueck.
     *
     * UNVERIFIABLE zaehlt WEDER mit NOCH bricht es die Serie: ein
     * unbeobachteter Zeitpunkt ist kein Gegenbeweis, aber auch kein Beleg.
     * Er darf den Nachweis weder tragen noch zerstoeren.
     */
    fun consecutiveMissed(outcomes: List<Outcome>): Int {
        var n = 0
        for (o in outcomes.sortedByDescending { it.entry.dueTs }) {
            when (o.verdict) {
                Verdict.MISSED       -> n++
                Verdict.MET          -> return n
                Verdict.UNVERIFIABLE -> Unit
            }
        }
        return n
    }

    /**
     * Mindesthoehe einer behaupteten Senkung [mg/dl], damit sie ueberhaupt
     * eingereiht wird. In der Groessenordnung des Sensorrauschens - darunter
     * ist "ausgeblieben" nicht von Messrauschen zu unterscheiden.
     */
    const val MIN_PROMISED_DROP_MGDL = 10.0

    /** Toleranz beim Abrechnen [mg/dl]; s. [settle]. */
    const val SETTLE_TOLERANCE_MGDL = 5.0

    /**
     * Wie lange ein Eintrag hoechstens vorgehalten wird [min], bevor er ohne
     * Abrechnung verfaellt. Schuetzt die Datei gegen unbegrenztes Wachsen,
     * wenn Faelligkeiten wegen Ausfaellen nie erreicht werden.
     */
    const val MAX_AGE_MIN = 240
}

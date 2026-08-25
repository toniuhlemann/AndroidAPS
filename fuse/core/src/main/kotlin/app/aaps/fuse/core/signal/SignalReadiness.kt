package app.aaps.fuse.core.signal

/**
 * WIE WEIT DAS SIGNAL GEREIFT IST - typisiert, auch (und gerade) wenn
 * der Schaetzer noch NICHTS liefert (Bauauftrag Toni 25.08. abends).
 *
 * DER ANLASS: nach einer CGM-Luecke stand auf dem Schirm nur "Eingang
 * fehlt" - fuenf bis sechs Minuten lang, ohne jeden Hinweis darauf, dass
 * das Signal gerade wieder aufbaut und wie lange es noch dauert. Und die
 * einzige Zahl, die es gab (`samplesUsed`), zaehlte im FESTEN
 * 18-min-Fenster, waehrend der Regler auf das eingestellte Fenster
 * filtert: eine Anzeige "7/5", waehrend der Regler weiter abbricht.
 *
 * WARUM NICHT EINE ZAHL: der Schaetzer faellt an ZWEI Schranken aus -
 * zu wenige PUNKTE (`MIN_POINTS`) oder zu wenige gueltige
 * PAARSTEIGUNGEN (`MIN_SLOPES`). Bei 1-min-Kadenz bindet fast immer die
 * zweite, weil `PAIR_DT_MIN_MS` die kurzen Nachbarpaare wegwirft. Ein
 * erfundener gemeinsamer Nenner ("3/6") waere in der einen Haelfte der
 * Faelle schlicht falsch; deshalb stehen hier BEIDE Zaehler mit ihrer
 * eigenen Schwelle.
 */
data class SignalReadiness(
    /** Punkte im WIRKSAMEN Fenster (nicht im festen 18-min-Fenster). */
    val points: Int,
    /** Wieviele es braucht - [BgiAdjustedSeries.MIN_POINTS]. */
    val pointsRequired: Int,
    /** Gueltige Paarsteigungen (dt >= [BgiAdjustedSeries.PAIR_DT_MIN_MS]). */
    val slopes: Int,
    /** Wieviele es braucht - [BgiAdjustedSeries.MIN_SLOPES]. */
    val slopesRequired: Int,
    /** Das WIRKSAME Theil-Sen-Fenster [min] - die Einstellung, nie die
     *  Vorgabe. Ohne sie ist keine der Zahlen einzuordnen. */
    val windowMin: Int,
    /** Laenge der juengsten Luecke im betrachteten Fenster [ms];
     *  `null` = keine Luecke gefunden. */
    val lastGapMs: Long?,
    /** Die wirksame Bruchgrenze [ms] dieses Laufs - s. [GapPolicy]. */
    val breakMs: Long,
    /** Warum der Schaetzer (nicht) liefert - typisiert. */
    val reason: Reason,
) {

    enum class Reason {
        /** Der Schaetzer liefert; nichts zu melden. */
        READY,

        /** Zu wenige Punkte im wirksamen Fenster. */
        TOO_FEW_POINTS,

        /** Punkte genug, aber zu wenige Paare mit dem Mindestabstand.
         *  Der haeufige Fall unter 1-min-Kadenz. */
        TOO_FEW_SLOPES,

        /** Eine Luecke groesser als die Bruchgrenze hat das Segment
         *  gerade beendet - der Aufbau beginnt von vorn. */
        GAP_RESET,
    }

    val ready: Boolean get() = reason == Reason.READY

    /**
     * Die kompakte Zeile fuer Widget und Tab - Tonis Form.
     *
     * `Signal reift - 5P - 6/8S` waehrend des Aufbaus,
     * `Eingang fehlt - Luecke 188s > 180s` unmittelbar nach dem Bruch.
     * Im reifen Fall `null`: die Zeile verschwindet, statt Nullen zu
     * zeigen.
     */
    fun shortText(): String? = when (reason) {
        Reason.READY          -> null
        Reason.GAP_RESET      -> lastGapMs?.let {
            "Eingang fehlt - Luecke ${it / 1000}s > ${breakMs / 1000}s"
        } ?: "Eingang fehlt - Segmentbruch"

        Reason.TOO_FEW_POINTS -> "Signal reift - $points/${pointsRequired}P"
        Reason.TOO_FEW_SLOPES -> "Signal reift - ${points}P - $slopes/${slopesRequired}S"
    }
}

package app.aaps.fuse.core.controller

/**
 * V-REVERSAL-SCHUTZ, NUR IM KORREKTURKONTEXT (Bauauftrag Toni 25.08.).
 *
 * DER GEMESSENE ANLASS (Pflicht-Replay, 25.08. frueh): 06:12-06:20 fiel q1
 * von 135 auf 101 (UKF-Minimum -2,81 um 06:16) - ein schmales Sensor-/
 * Kompressions-V. Die Erholung sah die schnelle Bahn als Anstieg (06:27:
 * UKF +4,0), waehrend das ROBUSTE r noch -0,82 trug. Die Prognose lag
 * damit ~171 mg/dl zu hoch, und der reine Korrekturpfad gab ab 06:27
 * insgesamt 1,75 U auf eine Erholung statt auf echten Glukosedruck
 * (real: BG 132/121/111 nach 30/60/90 min).
 *
 * DIE REGEL (Tonis Spezifikation, woertlich umgesetzt): nach einem steilen
 * Fall darf eine schnelle Gegenbewegung KEINEN Korrektur-SMB ausloesen,
 * solange die robuste Hauptbahn noch negativ beziehungsweise unbestaetigt
 * ist. "Bestaetigt" heisst: r ist ZUSAMMENHAENGEND (90-s-Anschluss wie
 * die uebrigen Zaehler) ueber [confirmCycles] Zyklen positiv. Kein Carry,
 * kein Nachholbolus - was der Riegel nimmt, ist weg. Der Mahlzeitenkontext
 * bleibt AUSDRUECKLICH unberuehrt (der Aufrufer prueft ihn); r und UKF
 * werden NICHT global haerter gemacht - genau das wuerde fruehe
 * Mahlzeitenanstiege treffen.
 *
 * ZUSTAND prozesslokal wie die Erholungs-Runtimes: ein Neustart vergisst
 * den Fall - die Fehlrichtung ist "Riegel fehlt", nie "Riegel klemmt".
 */
object CorrectionReversalGuard {

    /** Prozesslokaler Merker: das TIEFSTE UKF im Rueckblick (Zeitverfall
     *  statt Ringpuffer - der Onset-Ring traegt nur ~10 Minuten), die
     *  GEZUENDETE Gegenbewegung und der r-Bestaetigungszaehler. */
    data class Track(
        val minUkf: Double = Double.NaN,
        val minUkfTs: Long = 0L,
        /** Zeitpunkt, zu dem NACH diesem Fall-Minimum die schnelle
         *  Gegenbewegung beobachtet wurde; 0 = nicht gezuendet. Die
         *  Zuendung macht den Riegel zur EPISODE: er haelt auch, wenn das
         *  momentane UKF wieder abflacht (der Vorfall dosierte 06:30-06:33
         *  bei laengst flachem BG - die Prognose trug die Erholung noch). */
        val reboundSeenTs: Long = 0L,
        val rPosStreak: Int = 0,
        val rPosLastTs: Long = 0L,
    )

    data class Result(
        val blocks: Boolean,
        /** Typisierter Grund; null wenn der Riegel nicht traegt. */
        val reason: String? = null,
        /** Das massgebliche Fall-Minimum [mg/dl/min], fuer Export/Replay. */
        val fallMinUkf: Double? = null,
        val fallMinAgeMin: Double? = null,
        val rConfirmStreak: Int = 0,
    )

    const val REASON_R_NEGATIVE = "REVERSAL_R_NEGATIVE"
    const val REASON_R_UNCONFIRMED = "REVERSAL_R_UNCONFIRMED"

    /**
     * Ein Zyklus. Der Aufrufer reicht [korrekturKontext] - der Riegel
     * selbst entscheidet NIE ueber Mahlzeiten (Tonis Auflage: Mahlzeit-
     * pfade nicht pauschal betroffen); ausserhalb des Kontexts wird der
     * Zustand trotzdem fortgeschrieben, damit ein Kontextwechsel keinen
     * frischen, blinden Merker vorfindet.
     */
    @Suppress("LongParameterList")
    fun advance(
        track: Track,
        enabled: Boolean,
        nowTs: Long,
        ukfNow: Double,
        rNow: Double?,
        korrekturKontext: Boolean,
        fallThresholdUkf: Double,
        lookbackMin: Int,
        reboundThresholdUkf: Double,
        confirmCycles: Int,
    ): Pair<Track, Result> {
        if (!enabled) return Track() to Result(false)
        val lookbackMs = lookbackMin * 60_000L

        // Fall-Minimum mit Zeitverfall: ein tieferer Wert ersetzt, ein
        // verfallenes Minimum wird durch den aktuellen Wert ersetzt (danach
        // kann ein zweittiefster Wert im Fenster verloren sein - die
        // Fehlrichtung ist "Riegel endet zu frueh", nie "klemmt zu lange").
        // Jede ERSETZUNG loescht die Zuendung: sie gehoert zum alten Fall.
        val neuesMin =
            if (!ukfNow.isFinite()) track
            else if (track.minUkf.isNaN() || ukfNow <= track.minUkf ||
                nowTs - track.minUkfTs > lookbackMs
            ) track.copy(minUkf = ukfNow, minUkfTs = nowTs, reboundSeenTs = 0L)
            else track

        // r-Bestaetigung: zusammenhaengend positive robuste Zyklen.
        val anschluss = neuesMin.rPosLastTs > 0L && nowTs > neuesMin.rPosLastTs &&
            nowTs - neuesMin.rPosLastTs <= 90_000L
        val rPositiv = rNow != null && rNow.isFinite() && rNow > 0.0
        val streak = if (rPositiv) (if (anschluss) neuesMin.rPosStreak + 1 else 1) else 0

        val fallSteht = !neuesMin.minUkf.isNaN() &&
            neuesMin.minUkf <= -fallThresholdUkf &&
            nowTs - neuesMin.minUkfTs <= lookbackMs
        // Die Gegenbewegung ZUENDET die Episode (nur auf einen stehenden
        // Fall) - und die Episode haelt danach auch bei abgeflachtem UKF:
        // der Vorfall dosierte 06:30-06:33 bei flachem BG, die Prognose
        // trug die Erholung noch. Ende NUR durch r-Bestaetigung oder den
        // Verfall des Fall-Minimums (die Ersetzung oben loescht mit).
        val zuendung = fallSteht && ukfNow.isFinite() && ukfNow >= reboundThresholdUkf
        val fertig = neuesMin.copy(
            rPosStreak = streak, rPosLastTs = nowTs,
            reboundSeenTs = if (zuendung && neuesMin.reboundSeenTs == 0L) nowTs else neuesMin.reboundSeenTs,
        )
        val episodeAktiv = fallSteht && fertig.reboundSeenTs > 0L
        val alterMin = if (fertig.minUkfTs > 0L) (nowTs - fertig.minUkfTs) / 60_000.0 else null

        if (!korrekturKontext || !episodeAktiv) {
            return fertig to Result(false, fallMinUkf = fertig.minUkf.takeIf { !it.isNaN() }, fallMinAgeMin = alterMin, rConfirmStreak = streak)
        }
        return when {
            !rPositiv -> fertig to Result(
                true, REASON_R_NEGATIVE, fertig.minUkf, alterMin, streak,
            )
            streak < confirmCycles -> fertig to Result(
                true, REASON_R_UNCONFIRMED, fertig.minUkf, alterMin, streak,
            )
            else -> fertig to Result(
                false, fallMinUkf = fertig.minUkf, fallMinAgeMin = alterMin, rConfirmStreak = streak,
            )
        }
    }
}

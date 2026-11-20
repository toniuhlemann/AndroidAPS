package app.aaps.fuse.core.controller

/**
 * DER EINGANGS-WAECHTER (Toni 20.11.2026): rechnet FUSE laenger nicht, waehrend der Zucker hoch ist oder steigt,
 * sagt es das. Reine Meldung - er gibt kein Insulin und haelt keines zurueck.
 *
 * ANLASS: nach einem Snack stieg der Zucker in gut einer Stunde sehr stark. Der Sensor lieferte bei dem schnellen
 * Anstieg dreimal hintereinander gut zehn Minuten keinen Aktualwert; nach jeder Pause reifte das Signal neu, und
 * jeder Zyklus brach ab ("ohne Eingang"). FUSE gab eine Dreiviertelstunde lang nichts ausser dem Basal - und niemand
 * erfuhr es, ausser wer auf den Chip sah.
 *
 * DIE REGEL: seit dem letzten Zyklus MIT Eingang sind mindestens [AFTER_MIN] vergangen, und der Zucker liegt bei
 * mindestens [HIGH_MGDL] oder steigt mit mindestens [RISE_MGDL_PER_MIN]. Im Zielbereich bei ruhigem Verlauf schweigt
 * der Waechter - dort ist ein Zyklus ohne Eingang kein Risiko, das eine Meldung verdient.
 *
 * ZWEI STUFEN (Toni: "es muss nicht immer gleich Feuerwehralarm losgehen, aber sichtbar sollte sowas sein"): ab
 * [AFTER_MIN] ist die Meldung SICHTBAR, ohne Ton ([Stage.VISIBLE]). Haelt die Lage bis [LOUD_AFTER_MIN] an, wird
 * sie LAUT ([Stage.LOUD]); solange sie laut bleibt, kommt nach [REMIND_MIN] eine neue Kennung ("noch einmal
 * melden"). Der erste Zyklus mit Eingang nimmt die Meldung zurueck.
 *
 * Kommt ueberhaupt kein Messwert, laeuft kein Zyklus und damit auch dieser Waechter nicht - dafuer ist die Meldung
 * von AAPS ueber fehlende Messwerte da. Der Zustand ist prozesslokal: ein Neustart beginnt von vorn.
 */
object NoInputWatch {

    /** So lange [min] muss FUSE ohne Eingang sein, bevor die Meldung sichtbar wird. */
    const val AFTER_MIN = 10

    /** So lange [min] muss FUSE ohne Eingang sein, bevor die Meldung laut wird. */
    const val LOUD_AFTER_MIN = 30

    /** Ab diesem Messwert [mg/dl] gilt der Zucker als hoch. */
    const val HIGH_MGDL = 180.0

    /** Ab dieser Steigung [mg/dl/min] gilt der Zucker als steigend. */
    const val RISE_MGDL_PER_MIN = 1.0

    /** Nach dieser Zeit [min] kommt in der lauten Stufe eine neue Kennung - die Meldung klingelt erneut. */
    const val REMIND_MIN = 15

    private const val MIN_MS = 60_000L

    enum class Stage {

        /** Nichts zu melden. */
        NONE,

        /** Sichtbar, ohne Ton. */
        VISIBLE,

        /** Laut, mit Erinnerung. */
        LOUD,
    }

    /**
     * Ein Zyklus.
     *
     * @param hasInput der Zyklus hat gerechnet (kein Abbruch)
     * @param bgMgdl der Messwert [mg/dl]; nicht endlich = unbekannt
     * @param rateMgdlPerMin seine Steigung [mg/dl/min]; nicht endlich = unbekannt
     */
    data class Sample(
        val ts: Long,
        val hasInput: Boolean,
        val bgMgdl: Double,
        val rateMgdlPerMin: Double,
    )

    data class State(
        /** Der letzte Zyklus mit Eingang; `null` = in diesem Prozess noch keiner. */
        val lastInputTs: Long? = null,
        /** Seit wann FUSE ohne Eingang ist: der letzte Zyklus mit Eingang, sonst der erste ohne; `null` = mit Eingang. */
        val sinceTs: Long? = null,
        /** Kennung des laufenden Alarmtons der lauten Stufe; ein neuer Wert heisst "noch einmal melden", `null` = nicht laut. */
        val alarmKeyTs: Long? = null,
    )

    data class Result(
        val state: State,
        val stage: Stage,
        /** Volle Minuten ohne Eingang; 0 in einem Zyklus mit Eingang. */
        val minutesWithoutInput: Int,
    )

    fun step(s: State, x: Sample): Result {
        if (x.hasInput) return Result(State(lastInputTs = x.ts), Stage.NONE, minutesWithoutInput = 0)
        val since = s.sinceTs ?: s.lastInputTs ?: x.ts
        val minutes = ((x.ts - since) / MIN_MS).toInt().coerceAtLeast(0)
        val hochOderSteigt = (x.bgMgdl.isFinite() && x.bgMgdl >= HIGH_MGDL) ||
            (x.rateMgdlPerMin.isFinite() && x.rateMgdlPerMin >= RISE_MGDL_PER_MIN)
        val stage = when {
            !hochOderSteigt || minutes < AFTER_MIN -> Stage.NONE
            minutes < LOUD_AFTER_MIN               -> Stage.VISIBLE
            else                                   -> Stage.LOUD
        }
        val key = when {
            stage != Stage.LOUD                                                 -> null
            s.alarmKeyTs == null || x.ts - s.alarmKeyTs >= REMIND_MIN * MIN_MS -> x.ts
            else                                                                -> s.alarmKeyTs
        }
        return Result(State(lastInputTs = s.lastInputTs, sinceTs = since, alarmKeyTs = key), stage, minutes)
    }
}

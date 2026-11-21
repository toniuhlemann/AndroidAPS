package app.aaps.pump.medtrum.diagnosis

import kotlin.math.roundToInt

/**
 * FORK DIAGNOSIS (Toni 20.11.2026) - not part of the upstream driver: does the plunger of the patch move?
 *
 * The patch sends the measured position of its plunger with its status messages ("magneto placement"). The
 * reservoir value of the pump is only counted: it starts from the plunger position and then goes down by the doses
 * the pump believes it gave. When the plunger is blocked or has reached its end, the count goes on and the plunger
 * stands still. The pump reports a patch fault hours later, or never (end of the reservoir).
 *
 * The rule compares the two over the last [WINDOW_U] of reported delivery. A healthy patch moves the plunger about
 * 20 steps per unit (19.5 to 22 on eleven patches); below [MIN_STEPS_PER_U] it does not move. Checked on 30 days
 * of logs: six hits, all real (two blocked patches, four reservoirs at their end), no false alarm. Other windows
 * (0.5 to 1.0 U) and limits (8 to 14 steps) give the same six hits.
 *
 * THE HINT BELOW THE ALARM (Toni: "early visible, not always the fire alarm"): a plunger that moves, but clearly
 * less far than the reported delivery asks for - below [HINT_STEPS_PER_U] over the last [HINT_WINDOW_U]. Then less
 * insulin left the reservoir than the pump counts, and less than AAPS books as IOB. One of the two blocked patches
 * showed this well before it stood. In the 30 days the hint also came about once a week without a known cause, and
 * a few times only for a minute or two right at the limit, so it is shown quietly and never rings.
 *
 * Display and alarm only. Nothing here changes a dose.
 */
class PlungerWatch {

    enum class State {

        /** The pump has not reported enough delivery yet to compare. */
        UNKNOWN,

        /** The plunger follows the reported delivery. */
        MOVING,

        /** The plunger moves, but clearly less far than the reported delivery asks for: probably less insulin than counted. */
        SLOW,

        /** The pump reports delivery, but the plunger does not follow: no insulin leaves the reservoir. */
        STALLED
    }

    /**
     * @property state what the last comparison showed
     * @property stepsPerUnit plunger travel per reported unit - over the long window where the pump has reported that
     *   much, else over the short one; in [State.STALLED] always over the short one; `null` while [State.UNKNOWN]
     * @property stalledSince time of the sample the first stalled window started at; `null` unless [State.STALLED]
     * @property position the last plunger position in steps; `null` before the first sample
     * @property slowSince time of the sample the first slow window started at; `null` unless [State.SLOW]
     * @property sampleTs when the pump sent the last reading; `null` before the first sample
     * @property reservoir the reservoir the pump counted at the last reading [U]; `null` before the first sample
     */
    data class Status(
        val state: State = State.UNKNOWN,
        val stepsPerUnit: Double? = null,
        val stalledSince: Long? = null,
        val position: Int? = null,
        val slowSince: Long? = null,
        val sampleTs: Long? = null,
        val reservoir: Double? = null
    ) {

        /** The travel as a share of the usual travel, 0 to 100 percent; `null` while [State.UNKNOWN]. */
        val percentOfNormal: Int?
            get() = stepsPerUnit?.let { (it / NORMAL_STEPS_PER_U * 100.0).roundToInt().coerceIn(0, 100) }
    }

    private data class Sample(val timestamp: Long, val position: Int, val reservoir: Double)

    private val samples = ArrayDeque<Sample>()

    var status = Status()
        private set

    /** A new patch, or a patch that is not active: nothing of the old series counts. */
    fun reset() {
        samples.clear()
        status = Status()
    }

    /**
     * One status message of an active patch: the plunger [position] in steps and the [reservoir] the pump counts
     * [U]. Returns the status after this sample.
     */
    fun onSample(timestamp: Long, position: Int, reservoir: Double): Status {
        if (!reservoir.isFinite() || reservoir < 0.0) return status
        val last = samples.lastOrNull()
        // A reservoir that went up, or a plunger far back, is a new patch that was not announced here.
        if (last != null && (reservoir > last.reservoir + NEW_PATCH_RESERVOIR_U || position > last.position + NEW_PATCH_STEPS)) reset()
        // The same reading again adds nothing; the first one keeps its time, so a stall keeps its start.
        if (last == null || last.position != position || last.reservoir != reservoir || samples.isEmpty()) {
            samples.addLast(Sample(timestamp, position, reservoir))
        }
        // Keep the newest sample that lies at least one long window of delivery back, and everything after it.
        while (samples.size > 1 && samples[1].reservoir - reservoir >= HINT_WINDOW_U) samples.removeFirst()
        // Without delivery the series only grows by sensor noise; it must not grow without end.
        while (samples.size > MAX_SAMPLES) samples.removeAt(1)
        val shortBase = base(WINDOW_U, reservoir)
        val longBase = base(HINT_WINDOW_U, reservoir)
        val shortSteps = shortBase?.let { (it.position - position) / (it.reservoir - reservoir) }
        val longSteps = longBase?.let { (it.position - position) / (it.reservoir - reservoir) }
        val verdict = when {
            shortBase == null || shortSteps == null               -> Status(State.UNKNOWN, null, null, position)

            shortSteps < MIN_STEPS_PER_U                          ->
                Status(State.STALLED, shortSteps, status.stalledSince ?: shortBase.timestamp, position)

            longBase != null && longSteps != null && longSteps < HINT_STEPS_PER_U ->
                Status(State.SLOW, longSteps, null, position, slowSince = status.slowSince ?: longBase.timestamp)

            else                                                  -> Status(State.MOVING, longSteps ?: shortSteps, null, position)
        }
        status = verdict.copy(sampleTs = timestamp, reservoir = reservoir)
        return status
    }

    /** The newest sample that lies at least [window] of reported delivery before [reservoir]; `null` = not that much yet. */
    private fun base(window: Double, reservoir: Double): Sample? = samples.lastOrNull { it.reservoir - reservoir >= window }

    companion object {

        /** The reported delivery the comparison looks back over [U]. */
        const val WINDOW_U = 0.7

        /** Below this travel per reported unit the plunger counts as standing; a healthy patch shows about 20. */
        const val MIN_STEPS_PER_U = 10.0

        /** The reported delivery the hint looks back over [U]: longer than [WINDOW_U], so that noise does not show. */
        const val HINT_WINDOW_U = 1.5

        /** Below this travel per reported unit over [HINT_WINDOW_U] the plunger moves clearly less far than usual. */
        const val HINT_STEPS_PER_U = 17.0

        /** Travel per unit of a healthy patch (the median of eleven patches was 20.0 to 22.0), for the percent shown. */
        const val NORMAL_STEPS_PER_U = 20.5

        private const val NEW_PATCH_RESERVOIR_U = 5.0
        private const val NEW_PATCH_STEPS = 200
        private const val MAX_SAMPLES = 2000
    }
}

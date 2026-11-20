package app.aaps.pump.medtrum.diagnosis

/**
 * FORK DIAGNOSIS (Toni 20.11.2026): when a standing plunger is told to the user - at once, and again every
 * [REMIND_MS] for as long as it stands. A plunger that moves again, or a new patch, takes the alarm away.
 */
class PlungerAlarm {

    enum class Action { NONE, ALARM, DISMISS }

    private var alarmedAt: Long? = null

    fun onStatus(status: PlungerWatch.Status, now: Long): Action {
        val last = alarmedAt
        return if (status.state == PlungerWatch.State.STALLED) {
            if (last == null || now - last >= REMIND_MS) {
                alarmedAt = now
                Action.ALARM
            } else Action.NONE
        } else if (last != null) {
            alarmedAt = null
            Action.DISMISS
        } else Action.NONE
    }

    companion object {

        const val REMIND_MS = 15 * 60 * 1000L
    }
}

/**
 * FORK DIAGNOSIS (Toni 20.11.2026): when a slow plunger is told to the user - once when it starts, without sound.
 * It is not said again for the same stretch: a hint the user has put away stays away. A plunger that moves as usual
 * again, one that stands (then [PlungerAlarm] speaks) or a new patch takes the hint away.
 */
class PlungerHint {

    enum class Action { NONE, HINT, DISMISS }

    private var shown = false

    fun onStatus(status: PlungerWatch.Status): Action {
        val slow = status.state == PlungerWatch.State.SLOW
        return when {
            slow && !shown -> {
                shown = true
                Action.HINT
            }

            !slow && shown -> {
                shown = false
                Action.DISMISS
            }

            else           -> Action.NONE
        }
    }
}

/**
 * FORK DIAGNOSIS: the 16 alarm bits of the pump. The driver reads bits 1 to 3 (low battery, low reservoir, expires
 * soon). In 30 days of logs two more bits showed up, both next to something the driver shows anyway: bit 10 with
 * the state "patch fault" and bit 14 when a patch is stopped. Any other bit is news, and the user is told.
 */
object PumpAlarmBits {

    private val READ_BY_DRIVER = setOf(0, 1, 2, 3)
    private val SEEN_WITH_A_KNOWN_STATE = setOf(10, 14)

    /** The numbers of the bits that are set, lowest first. */
    fun bits(flags: Int): List<Int> = (0..15).filter { (flags shr it) and 1 != 0 }

    /** The driver reads this bit, or the logs explain it. */
    fun isKnown(bit: Int): Boolean = bit in READ_BY_DRIVER || bit in SEEN_WITH_A_KNOWN_STATE

    /** The set bits that neither the driver reads nor the logs explain. */
    fun unknown(flags: Int): List<Int> = bits(flags).filterNot(::isKnown)
}

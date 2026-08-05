package app.aaps.fuse.plugin

import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump

/**
 * Harter Riegel: FUSE aktuiert ausschliesslich gegen eine VirtualPump.
 *
 * Das ist KEIN Modus und kein Schalter, sondern eine Startverweigerung. Ein
 * Alpha-Regelkern darf nicht versehentlich auf dem Produktivgeraet dosieren —
 * und eine UI-Beschriftung reicht dafuer nicht, weil sie niemand liest, wenn
 * ein APK auf dem falschen Telefon landet.
 *
 * Geprueft wird das TATSAECHLICH aktive Pumpenplugin zur Laufzeit, nicht eine
 * Preference: ein alter Einstellungswert darf den Riegel nicht umgehen koennen.
 */
object FusePumpGate {

    enum class Verdict { ALLOWED, BLOCKED_REAL_PUMP, BLOCKED_UNKNOWN_PUMP }

    data class Result(val verdict: Verdict, val pumpDescription: String) {
        val allowed: Boolean get() = verdict == Verdict.ALLOWED
        /** Kurzform fuer Export und UI — der Grund muss sichtbar sein, sonst
         *  wirkt ein blockiertes FUSE wie ein defektes FUSE. */
        val reason: String
            get() = when (verdict) {
                Verdict.ALLOWED             -> "VPUMP_ALPHA"
                Verdict.BLOCKED_REAL_PUMP   -> "BLOCKED: real pump ($pumpDescription)"
                Verdict.BLOCKED_UNKNOWN_PUMP -> "BLOCKED: pump not identifiable"
            }
    }

    fun evaluate(pump: Pump?): Result = when (pump) {
        null          -> Result(Verdict.BLOCKED_UNKNOWN_PUMP, "none")
        is VirtualPump -> Result(Verdict.ALLOWED, pump.javaClass.simpleName)
        else          -> Result(Verdict.BLOCKED_REAL_PUMP, pump.javaClass.simpleName)
    }
}

package app.aaps.fuse.core.controller

import kotlin.math.max
import kotlin.math.min

/**
 * KANDIDAT W (Toni, Pruefstand): die WENDE einer laufenden Mahlzeit zur naechsten Welle erkennen und eine Wende nach
 * unten frueh melden.
 *
 * Toni: "idealerweise beginnt FUSE kleine Dosen schon am erkennbaren Wendepunkt zur naechsten Welle und steigert dann
 * etwas nach Beschleunigung" - und "genauso sollte eine Wende nach unten fruehzeitig Insulin stoppen".
 *
 *  - Zeitpunkt aus der UKF-Steigung (laeuft kaum nach), Bestaetigung aus der insulinbereinigten Stoerung r (die Mahlzeit
 *    ist die Ursache, nicht nachlassendes Insulin). Die Schwelle "Mahlzeit laeuft" muss an der Wende noch NICHT erreicht
 *    sein - sonst waere W so spaet wie ohne.
 *  - Vorlage: der Anstieg begann vor hoechstens [Params.vorMin] Minuten, und davor lag die Steigung [Params.vorMin]
 *    Minuten lang flach oder fallend (Plateau oder Delle nach der ersten Welle).
 *  - In der Wendephase ein Rampenfaktor aus dem Zuwachs der Steigung SEIT der Wende: klein beginnen, nach
 *    Beschleunigung steigern; ein gleichmaessig starker Anstieg bekommt die volle Gabe, eine Abflachung nimmt sie
 *    zurueck (kein Nachholen).
 *  - Wende nach unten: nach einem Anstieg bremst er [Params.zyklen] Zyklen lang deutlich ab (oder die Steigung liegt so
 *    lange unter der Flachgrenze) - der Stopp gilt, bis der Zucker wieder steigt und nicht mehr abbremst (dieselbe Zahl
 *    Zyklen ueber der Flachgrenze ohne Abbremsen, r positiv) oder eine neue Wende nach oben erkannt ist. Er haelt nie in
 *    die naechste Welle hinein, und ein grosser Anstieg, der nur kurz abbremst, bleibt nicht gestoppt.
 *  - Gegen Gespraechigkeit: die Wendephase dauert hoechstens [Params.maxMin], danach eine Sperre [Params.sperreMin].
 *
 * Rein, ohne Geraet. Im Produkt nicht verdrahtet - nur der Pruefarm W ruft sie.
 */
object TurnDetector {

    /**
     * Ein Startsatz, keine Parameterreihe (Kandidat W). Steigungen in mg/dl/min, Zeiten in min. [aufMgdlPerMin]: ab hier
     * zaehlt eine Wende nach oben (zweite Wellen laufen langsam an); [stiegMgdlPerMin]: so steil muss es vorher gestiegen
     * sein, damit ein Abbremsen eine Wende nach unten ist.
     */
    data class Params(
        val vorMin: Int = 10,
        val epsMgdlPerMin: Double = 0.2,
        val aufMgdlPerMin: Double = 0.25,
        val stiegMgdlPerMin: Double = 0.5,
        val zyklen: Int = 2,
        val rWendeMgdlPerMin: Double = 0.5,
        val f0: Double = 0.25,
        /** Zuwachs der Steigung seit der Wende fuer die volle Gabe [mg/dl/min]. */
        val duVoll: Double = 1.0,
        /** Abbremsen fuer die Wende nach unten: Zuwachs der Steigung ueber 5 min hoechstens minus so viel [mg/dl/min]. */
        val duAb: Double = 0.5,
        val maxMin: Int = 45,
        val sperreMin: Int = 20,
    )

    /** Ein Zyklus der Vorgeschichte: Zeit, UKF-Steigung, Stoerung r (beide mg/dl/min; r NaN = nicht rechenbar). */
    data class Point(val ts: Long, val ukf: Double, val r: Double)

    /**
     * [sinceTs] > 0 = Wendephase seit; [ukfAtTurnMgdlPerMin] = Steigung an der Wende; [stop] = Abwaertsstopp gilt;
     * [endReason] = warum die letzte Phase endete.
     */
    data class State(
        val sinceTs: Long = 0L,
        val ukfAtTurnMgdlPerMin: Double = Double.NaN,
        val lockUntilTs: Long = 0L,
        val stop: Boolean = false,
        val endReason: String? = null,
    )

    data class Result(
        val state: State,
        /** In diesem Zyklus gilt die Wendephase. */
        val active: Boolean,
        /** Rampenfaktor der Wendephase (f0..1); ausserhalb 1. */
        val rampFactor: Double,
        /** Abwaertsstopp: keine weiteren Portionen des Kontos. */
        val stop: Boolean,
        /** Zuwachs der UKF-Steigung ueber 5 min [mg/dl/min]; NaN = nicht rechenbar. */
        val accel5: Double,
        /** Ereignis dieses Zyklus: TURN_UP, TURN_DOWN, STOP_LIFTED oder das Ende der Phase (END_...); `null` = keins. */
        val event: String?,
    )

    private const val MIN_MS = 60_000L

    /** Hoechstens so weit darf der Vergleichspunkt der Beschleunigung zurueckliegen [min] - sonst ist sie nicht rechenbar. */
    private const val ACCEL_MAX_BACK_MIN = 10L

    /** Zuwachs der UKF-Steigung am Punkt [i] gegenueber dem letzten Punkt mindestens 5 min davor; NaN = nicht rechenbar. */
    fun accel5At(history: List<Point>, i: Int): Double {
        val p = history.getOrNull(i)?.takeIf { it.ukf.isFinite() } ?: return Double.NaN
        val vor = (i - 1 downTo 0).asSequence().map { history[it] }
            .firstOrNull { it.ts <= p.ts - 5 * MIN_MS && it.ukf.isFinite() }
            ?.takeIf { it.ts >= p.ts - ACCEL_MAX_BACK_MIN * MIN_MS }
            ?: return Double.NaN
        return p.ukf - vor.ukf
    }

    /**
     * Ein Zyklus. [history] aufsteigend nach Zeit, der letzte Punkt ist JETZT ([nowTs]). [upAllowed]: eine Wendephase
     * darf bestehen und beginnen (Konto gilt, Anfang vorbei, kein Tief - entscheidet der Aufrufer). [stopAllowed]: die
     * Wende nach unten darf stoppen.
     */
    fun step(state: State, history: List<Point>, nowTs: Long, upAllowed: Boolean, stopAllowed: Boolean, p: Params = Params()): Result {
        val n = history.size
        val jetzt = history.lastOrNull()?.takeIf { it.ts == nowTs && it.ukf.isFinite() }
        if (jetzt == null) {
            // Ohne Signal endet eine Phase (ohne Sperre); ein geltender Stopp bleibt - im Zweifel kein Nachschub.
            val s = (if (state.sinceTs > 0L) state.copy(sinceTs = 0L, endReason = "NO_SIGNAL") else state)
                .let { if (stopAllowed) it else it.copy(stop = false) }
            return Result(
                s, active = false, rampFactor = 1.0, stop = s.stop, accel5 = Double.NaN,
                event = if (state.sinceTs > 0L) "END_NO_SIGNAL" else null,
            )
        }
        val accel5 = accel5At(history, n - 1)
        val letzte = history.takeLast(p.zyklen)
        val nZyklen = letzte.size == p.zyklen && letzte.all { it.ukf.isFinite() }
        val accelLetzte = (max(0, n - p.zyklen) until n).map { accel5At(history, it) }
        val stiegKuerzlich = history.any { it.ts >= nowTs - p.vorMin * MIN_MS && it.ukf.isFinite() && it.ukf > p.stiegMgdlPerMin }

        // Wende nach unten, N Zyklen bestaetigt: nach einem Anstieg deutlich abgebremst oder unter der Flachgrenze -
        // frueh, vor dem Fallen.
        val abgebremst = nZyklen && accelLetzte.all { it.isFinite() && it <= -p.duAb }
        val flach = nZyklen && letzte.all { it.ukf < p.epsMgdlPerMin }
        val abwaertsErkannt = stiegKuerzlich && (abgebremst || flach)
        // Der Zucker steigt wieder und bremst nicht mehr: N Zyklen ueber der Flachgrenze ohne Abbremsen, r positiv.
        val weiterAuf = nZyklen && letzte.all { it.ukf > p.epsMgdlPerMin } &&
            accelLetzte.all { it.isFinite() && it >= 0.0 } && jetzt.r.isFinite() && jetzt.r > 0.0

        var s = state
        var event: String? = null
        if (!stopAllowed) s = s.copy(stop = false)
        else if (abwaertsErkannt && !s.stop) {
            event = "TURN_DOWN"
            s = s.copy(stop = true)
        } else if (s.stop && weiterAuf) {
            event = "STOP_LIFTED"
            s = s.copy(stop = false)
        }

        // Laufende Phase pruefen: Ende bei Wegfall der Erlaubnis, Abwaertswende, Abflachung, negativem r, Hoechstdauer.
        if (s.sinceTs > 0L) {
            val ende = when {
                !upAllowed -> "NOT_ALLOWED"
                abwaertsErkannt -> "TURN_DOWN"
                nZyklen && letzte.all { it.ukf <= 0.0 } -> "FLATTENED"
                jetzt.r.isFinite() && jetzt.r < 0.0 -> "R_NEGATIVE"
                nowTs - s.sinceTs >= p.maxMin * MIN_MS -> "MAX_DURATION"
                else -> null
            }
            if (ende != null) {
                s = s.copy(sinceTs = 0L, lockUntilTs = nowTs + p.sperreMin * MIN_MS, endReason = ende)
                if (event == null) event = "END_$ende"
            }
        }

        // Neue Wende nach oben: der Anstieg begann eben erst (letzter flacher Punkt hoechstens vorMin zurueck), davor
        // vorMin Minuten flach/fallend, dann N Zyklen ueber der Aufwaertsgrenze, r positiv und steigend.
        if (s.sinceTs == 0L && upAllowed && nowTs >= s.lockUntilTs && nZyklen) {
            val ersterBestaetigt = letzte.first().ts
            val letzterFlach = history.lastOrNull { it.ts < ersterBestaetigt && it.ukf.isFinite() && it.ukf <= p.epsMgdlPerMin }
            val vorlage = letzterFlach?.let { f -> history.filter { it.ts <= f.ts && it.ts >= f.ts - p.vorMin * MIN_MS } }.orEmpty()
            val vorlageFlach = letzterFlach != null && nowTs - letzterFlach.ts <= p.vorMin * MIN_MS &&
                vorlage.size >= p.vorMin / 2 && vorlage.all { it.ukf.isFinite() && it.ukf <= p.epsMgdlPerMin }
            val rVor5 = history.lastOrNull { it.ts <= nowTs - 5 * MIN_MS && it.r.isFinite() }?.r
            val rBestaetigt = jetzt.r.isFinite() && jetzt.r > 0.0 && jetzt.r >= p.rWendeMgdlPerMin &&
                (rVor5 == null || jetzt.r > rVor5)
            if (vorlageFlach && letzte.all { it.ukf > p.aufMgdlPerMin } && rBestaetigt) {
                s = s.copy(sinceTs = nowTs, ukfAtTurnMgdlPerMin = jetzt.ukf, stop = false, endReason = null)
                event = "TURN_UP"
            }
        }

        val aktiv = s.sinceTs > 0L
        val zuwachs = jetzt.ukf - s.ukfAtTurnMgdlPerMin
        val rampe = if (!aktiv) 1.0
        else if (!zuwachs.isFinite()) p.f0
        else min(1.0, max(p.f0, p.f0 + (1.0 - p.f0) * zuwachs / p.duVoll))
        return Result(s, active = aktiv, rampFactor = rampe, stop = s.stop && !aktiv, accel5 = accel5, event = event)
    }
}

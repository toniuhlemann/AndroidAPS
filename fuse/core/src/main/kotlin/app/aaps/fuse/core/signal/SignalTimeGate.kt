package app.aaps.fuse.core.signal

/**
 * Zeitachsen-Tor fuer den Glukose-Anker (Audit R95, F-P0-04).
 *
 * Das Problem hat zwei Gesichter, EIN Tor deckt beide:
 *  1. Ein einzelner Zukunfts-Zeitstempel (Sensor-/Telefonuhr verstellt,
 *     Uhr-Rueckstellung nach geladenem Fenster) laesst Prediction und
 *     deliverAt auf verschiedenen Zeitachsen rechnen.
 *  2. Eine DAUERHAFT vorgehende Quelle klebt mit ihrem Reihenkopf an der
 *     now+2min-Ladefenster-Kappe des LoadBgDataWorker: jede Minute wirkt
 *     "neu", der STALE-Zweig des Observers wird UNERREICHBAR, und der Regler
 *     laeuft unbegrenzt auf Werten, die in Wahrheit Uhrversatz-alt sind -
 *     ohne jeden Health-Grund. (Gegenpruefung R95: die Entlastung "STALE
 *     faengt das" war genau hierfuer falsch.)
 *
 * Toleranz 60 s: echte Quellen stempeln <= jetzt (kleiner Jitter erlaubt);
 * die Ladefenster-Kappe liegt bei +2 min - ein angeklebter Kopf faellt also
 * IMMER durch dieses Tor. Fail-closed heisst hier: benannter Abort, kein
 * neuer SMB; die TBR-Behandlung des Aborts regelt der Runner (F-P0-07).
 */
object SignalTimeGate {

    const val FUTURE_TOLERANCE_MS: Long = 60_000L

    /** @return benannter Ablehnungsgrund oder null wenn die Zeitachse konsistent ist. */
    fun futureReason(sourceTs: Long, computeTs: Long, toleranceMs: Long = FUTURE_TOLERANCE_MS): String? {
        val aheadMs = sourceTs - computeTs
        if (aheadMs <= toleranceMs) return null
        return "FUTURE_GLUCOSE: sourceTs ${aheadMs / 1000}s vor computeTs (Uhrversatz/Sensoruhr?)"
    }
}

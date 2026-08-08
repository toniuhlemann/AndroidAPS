package app.aaps.fuse.core.controller

/**
 * Der HARTE Durchgriff des Commitment-Ledgers auf die Menge - als LETZTE
 * Stufe nach [PrimeRelease.lift] (Audit R95, Fix 3).
 *
 * Warum eine eigene Stufe, obwohl [CandidateSearch] bereits `ledgerHold`
 * kennt: die Suche prueft nur den KANDIDATEN-Pfad. Zwei mengenerzeugende
 * Wege laufen an ihrem Reject vorbei -
 *
 *  1. der Ratio-Pfad, wenn der Einheitskern ausfaellt (candidateGap: die
 *     Basis gilt dann unveraendert, s. CandidateGate "TECHNISCH"), und
 *  2. die Sofort-Freigabe, die ausdruecklich NACH dem CandidateGate hebt.
 *
 * Ein Hold, der nur in der Suche greift, waere also genau ueber die Pfade
 * umgehbar, die ohne Wirkungspruefung dosieren. Deshalb sitzt der Riegel
 * hier, hinter ALLEN Stufen, und nullt kompromisslos.
 *
 * TBR-Achse (Fix-Pass 2 Nr. 3, Gegenpruefungs-Befund LEDGERHOLD-TBR-
 * STALE-ZERO-CANCEL): KEIN blindes Zero-Temp - aber auch kein KEEP_CURRENT.
 * KEEP_CURRENT liess ueber KEEP_CANCEL_STALE_ZERO eine laufende
 * Sicherheits-Null abbrechen: Profilbasal lief wieder an, waehrend der
 * Ledger per Definition nicht weiss, was draussen unterwegs ist. Unter Hold
 * gilt deshalb NO_NEW_POSITIVE: laufende Null-/Negativ-TBRs BLEIBEN,
 * nur eine positive TBR wird beendet. Das ist die konservative Mitte
 * zwischen blindem Bremsen und blindem Loslassen.
 */
object LedgerHoldGate {

    fun apply(decision: FuseController.Decision, hold: Boolean): FuseController.Decision =
        when {
            !hold                 -> decision
            decision.smbU <= 0.0  -> decision.copy(tbr = holdTbr(decision.tbr))
            else                  -> decision.copy(
                smbU = 0.0,
                tbr = holdTbr(decision.tbr),
                block = FuseController.Block.LEDGER_HOLD,
                bindingLimit = "ledgerHold",
            )
        }

    /** Sicherheits-Aktionen (ZERO_TEMP/CANCEL) bleiben; nur das lockere
     *  KEEP_CURRENT wird unter Hold zu NO_NEW_POSITIVE verschaerft. */
    private fun holdTbr(tbr: FuseController.TbrAction): FuseController.TbrAction =
        if (tbr == FuseController.TbrAction.KEEP_CURRENT) FuseController.TbrAction.NO_NEW_POSITIVE else tbr
}

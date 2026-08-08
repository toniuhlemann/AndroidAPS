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
 * Die TBR-Achse bleibt unangetastet: ein Ledger-Hold ist ein "keine NEUE
 * Dosis", kein Sicherheitsbefund der Bahn - ein blindes Zero-Temp waere
 * eine eigene Fehldosis mit umgekehrtem Vorzeichen (s. FuseController,
 * NO_DEMAND-Begruendung).
 */
object LedgerHoldGate {

    fun apply(decision: FuseController.Decision, hold: Boolean): FuseController.Decision =
        if (!hold || decision.smbU <= 0.0) decision
        else decision.copy(
            smbU = 0.0,
            block = FuseController.Block.LEDGER_HOLD,
            bindingLimit = "ledgerHold",
        )
}

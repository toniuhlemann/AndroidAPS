package app.aaps.fuse.core.controller

/**
 * DAS MAHLZEITENFUNDAMENT - Konsolidierung 2.0: entfallen.
 *
 * Phase A/B (Armierung, Plan, Lift, Phasen, Uebertraege, Snapshot) ist mit dem alten Mahlzeitenweg entfernt; die
 * Mahlzeit traegt das Erwartungskonto. Geblieben ist nur, was ein ALTER Ledger noch enthaelt: der
 * `foundation`-Block wird beim Laden weiter vollstaendig geprueft (eine kaputte Generation faellt wie bisher), und
 * aus einer gueltigen Autorisierung wird die Kredit-Huelle abgeleitet, die das Fundament fuer den Erklaerungskredit
 * gepinnt hatte (s. `LedgerCodec.decodeEpisodes`). Geschrieben wird der Block nicht mehr.
 */
object MealFoundation {

    /**
     * DIE BEIM ARMEN EINGEFRORENEN REGELPARAMETER eines aelteren Standes - nur noch zum Lesen.
     *
     * KEINE data class UND KEIN OEFFENTLICHER KONSTRUKTOR (Toni, P0-1): die Teilbudgets sind ABGELEITET,
     * nicht gespeichert, und [restore] ist die eine Stelle, die ueber Gueltigkeit entscheidet - `valid` prueft die
     * Beziehungen ZWISCHEN den Feldern, die eine feldweise Pruefung nicht sieht.
     */
    class Authorization private constructor(
        /** Wann diese Autorisierung entstand - der Markerdruck. */
        val armedTs: Long,
        val totalBudgetU: Double,
        val phaseAShare: Double,
        val phaseAUpfrontShare: Double,
        val pinnedPrimeWindowMin: Int,
        val pinnedWallCeilingMin: Int,
        val endTs: Long,
        val pinnedMarkerAuthorized: Boolean,
        val latchedHandoverTs: Long,
    ) {

        /** ABGELEITET, nicht gespeichert - es gibt nur eine Wahrheit. Bis zur Konsolidierung die Kredit-Huelle. */
        val phaseABudgetU: Double get() = totalBudgetU * phaseAShare

        val valid: Boolean
            get() = armedTs > 0L && totalBudgetU.isFinite() && totalBudgetU > 0.0 &&
                phaseAShare.isFinite() && phaseAShare in 0.0..1.0 &&
                phaseAUpfrontShare.isFinite() && phaseAUpfrontShare in 0.0..1.0 &&
                pinnedPrimeWindowMin >= 0 && pinnedWallCeilingMin >= 0 &&
                endTs > armedTs && latchedHandoverTs >= 0L &&
                (latchedHandoverTs == 0L || latchedHandoverTs >= armedTs)

        companion object {

            /** Keine Autorisierung. */
            fun none() = Authorization(0L, 0.0, 0.0, 0.0, 0, 0, 0L, false, 0L)

            /**
             * AUS DER PERSISTENZ WIEDERHERSTELLEN - mit Pruefung.
             *
             * Fail-closed: eine widerspruechliche Generation ergibt KEINE
             * Autorisierung, nicht eine halbe.
             */
            fun restore(
                armedTs: Long,
                totalBudgetU: Double,
                phaseAShare: Double,
                phaseAUpfrontShare: Double,
                pinnedPrimeWindowMin: Int,
                pinnedWallCeilingMin: Int,
                endTs: Long,
                pinnedMarkerAuthorized: Boolean,
                latchedHandoverTs: Long,
            ): Authorization {
                val a = Authorization(
                    armedTs, totalBudgetU, phaseAShare, phaseAUpfrontShare, pinnedPrimeWindowMin,
                    pinnedWallCeilingMin, endTs, pinnedMarkerAuthorized, latchedHandoverTs,
                )
                return if (a.valid) a else none()
            }
        }
    }
}

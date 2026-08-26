package app.aaps.fuse.core.controller

/**
 * DER FINALE RIEGEL GEGEN NEUES POSITIVES INSULIN (Toni 19.08., P0).
 *
 * WO ER SITZT UND WARUM DORT. Am GEMEINSAMEN Ausgang des Zyklus: nach dem
 * Prime- und Fundament-Lift, nach `finalVerify` und nach [MarkerFloor], aber
 * VOR der Publikation. Jeder frueher gesetzte Riegel koennte von einem
 * spaeteren Wiederherstellungspfad wieder geoeffnet werden - und genau so ist
 * der Abendfall vom 19.08. entstanden.
 *
 * WARUM ER EINE EIGENE FUNKTION IST (Codex 19.08.). Er stand zuerst zweimal
 * im Runner - einmal im Hauptpfad, einmal im Fallback; geteilt war nur die
 * RISIKOBERECHNUNG. Zwei Kopien laufen bei der naechsten Ergaenzung
 * auseinander: ein Feld hier gesetzt, dort vergessen, und der Fallback traegt
 * einen anderen Zustand als der Hauptpfad. Dieselbe Falle wie bei der
 * Fundament-Telemetrie, wo genau das passiert ist.
 *
 * WAS ER TUT UND WAS NICHT:
 *
 *   Menge  -> 0, Block -> [FuseController.Block.MEASURED_DESCENT_RISK],
 *             Bindung benannt, und [FuseController.Decision.unsafeSituation]
 *             gesetzt - es IST eine gemessene unsichere Lage, und keine
 *             nachgelagerte oder kuenftige Sicherheitslogik darf sie als
 *             sicher lesen.
 *
 *   TBR    -> UNVERAENDERT. Ob eine Zero-TBR noch nuetzt, ist eine andere
 *             Frage und bleibt beim Basalnutzen. "Basal zurueckhalten hilft
 *             nicht mehr" und "mehr Bolus ist sicher" sind zwei vollstaendig
 *             verschiedene Aussagen - ihre Vermischung war der Befund.
 *
 *   grant  -> BLEIBT STEHEN. Er zeigt im Trail, dass eine Autorisierung
 *             vorhanden war und vom gemessenen Riegel gestoppt wurde. Kein
 *             Leser darf daraus ohne `smbU > 0` eine Aktuation ableiten; die
 *             Buchfuehrung haengt ausschliesslich an der publizierten Menge.
 */
object MeasuredDescentGate {

    /**
     * WARUM DIE MENGE GENULLT WURDE - typisiert (Toni 25.08. spaet).
     *
     * `blocksPositive` allein beantwortet nur DASS geriegelt wurde. Wer
     * spaeter einen eng umrissenen Ausnahmepfad bauen will - etwa den
     * bedarfsbegrenzten Ruhe-Kandidaten, der AUSSCHLIESSLICH einen
     * historischen Latch ueberstimmen darf -, braucht das WARUM. Ohne
     * diese Unterscheidung liesse sich "Menge ist 0" nicht von "Menge ist 0
     * WEGEN aktueller Gefahr" trennen, und der Ausnahmepfad wuerde beides
     * gleich behandeln.
     */
    enum class Cause {
        /** Nicht geriegelt. */
        NONE,

        /** AKTUELL gemessenes Abwaertsrisiko. Niemals umgehbar. */
        CURRENT_DESCENT_RISK,

        /**
         * Der Riegel steht noch, weil die Erholung nicht belegt ist -
         * WAITING_RATE oder WAITING_RAW. Nur DIESE Ursache ist historisch:
         * die Gefahr ist vorbei, die Hysterese laeuft noch.
         */
        HISTORICAL_LATCH,

        /** Ein bestaetigtes Tief. Niemals umgehbar. */
        MEASURED_LOW,

        /**
         * ALLES UEBRIGE - und ausdruecklich NIEMALS umgehbar.
         *
         * Tonis Vorgabe nannte vier Werte. Dieser fuenfte ist noetig, weil
         * `WAITING_UNHEALTHY` (Signalfehler) und `WAITING_RISK`
         * (zusaetzlicher Risiko-Wartegrund des Aufrufers) AKTUELLE
         * Stoerungen sind, keine abgelaufene Hysterese. Sie unter
         * [HISTORICAL_LATCH] zu fuehren waere genau der Bypass unter
         * laufendem Fehler, den die Unterscheidung verhindern soll.
         */
        OTHER,
    }

    /**
     * Die Ursache aus Riegelzustand und Latch-Grund. Reine Abbildung, kein
     * Zustand - der Aufrufer haelt beides ohnehin.
     */
    fun causeOf(
        blocksPositive: Boolean,
        reason: DescentRecoveryLatch.Reason,
    ): Cause = when {
        !blocksPositive -> Cause.NONE
        reason == DescentRecoveryLatch.Reason.RISK_ACTIVE -> Cause.CURRENT_DESCENT_RISK
        reason == DescentRecoveryLatch.Reason.WAITING_MEASURED_LOW -> Cause.MEASURED_LOW
        reason == DescentRecoveryLatch.Reason.WAITING_RATE ||
            reason == DescentRecoveryLatch.Reason.WAITING_RAW -> Cause.HISTORICAL_LATCH
        else -> Cause.OTHER
    }

    /**
     * Setzt die Menge auf 0, wenn ein gemessenes Abwaertsrisiko vorliegt.
     *
     * IDEMPOTENT UND FOLGENLOS OHNE RISIKO: ohne aktives Risiko oder ohne
     * positive Menge kommt die Entscheidung unveraendert zurueck. Der Riegel
     * darf keine Lage verschlechtern, die er gar nicht betrifft.
     */
    fun apply(
        decision: FuseController.Decision,
        blocksPositive: Boolean,
    ): FuseController.Decision =
        if (!blocksPositive || decision.smbU <= 0.0) decision
        else decision.copy(
            smbU = 0.0,
            block = FuseController.Block.MEASURED_DESCENT_RISK,
            bindingLimit = "measuredDescentRisk",
            unsafeSituation = true,
        )
}

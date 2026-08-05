package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.TbrPolicy

/**
 * EIN TBR-Pfad, nicht zwei.
 *
 * Der Regelkern liefert eine [FuseController.TbrAction], die TBR-Tabelle eine
 * [TbrPolicy.Outcome], und der RT-Bauer erwartet eine
 * [FuseController.TbrRequest]. Drei Sprachen fuer dieselbe Sache — und beide
 * Wege beantworten sie unterschiedlich:
 *
 *   FuseController.tbrRequest(CANCEL_TO_SCHEDULED, ...) -> Rate = Profilbasal
 *   TbrPolicy.decide(NO_POSITIVE, laufend positiv)      -> Rate 0, Dauer 0
 *
 * Die zweite Antwort ist die richtige (v0.2 §6): "zurueck auf Profilbasal" als
 * absolute Rate ist als Cancel nicht eindeutig, weil der Loop sie ueber die
 * Naehe zu `pump.baseBasalRate` interpretiert. Deshalb laeuft der TBR-Pfad
 * AUSSCHLIESSLICH ueber [TbrPolicy]; `FuseController.tbrRequest` wird im
 * Adapter nicht aufgerufen.
 */
object FuseTbrTranslator {

    /** Die Kategorie des Reglers wird zur Absicht der Tabelle. */
    fun intentOf(action: FuseController.TbrAction): TbrPolicy.Intent = when (action) {
        FuseController.TbrAction.ZERO_TEMP -> TbrPolicy.Intent.SAFETY_ZERO
        // Beide bedeuten "nichts Positives mehr" — der Unterschied lag nur in
        // der v0.1-Kodierung des Abbruchs, die v0.2 ersetzt hat.
        FuseController.TbrAction.NO_NEW_POSITIVE,
        FuseController.TbrAction.CANCEL_TO_SCHEDULED -> TbrPolicy.Intent.NO_POSITIVE
        FuseController.TbrAction.KEEP_CURRENT -> TbrPolicy.Intent.KEEP
    }

    /**
     * Nur eine echte Anforderung wird zur TbrRequest.
     *
     * `NoRequest` und `ReadOnlyHold` ergeben beide `null` — aber sie sind NICHT
     * dasselbe: `ReadOnlyHold` traegt zusaetzlich Alarm und SMB-Sperre, und sein
     * Grund muss in `RT.reason` landen. Sonst wirkt ein blockiertes FUSE wie ein
     * defektes FUSE.
     */
    fun requestOf(outcome: TbrPolicy.Outcome): FuseController.TbrRequest? =
        (outcome as? TbrPolicy.Outcome.Request)?.let { FuseController.TbrRequest(it.rateUPerH, it.durationMin) }

    data class Result(
        val request: FuseController.TbrRequest?,
        /** Der Regler kennt `smbBlocked` nicht — ohne diese Uebertragung gaebe
         *  FUSE bei FAKE_EXTENDED, CORE_INPUT_INVALID, fehlendem Snapshot oder
         *  arbeitender Pumpe trotzdem einen SMB frei. */
        val decision: FuseController.Decision,
        val reason: String,
        val alarm: Boolean,
    )

    /**
     * Fuehrt beide Achsen zusammen: die Mengenentscheidung des Reglers und die
     * TBR-Entscheidung der Tabelle.
     */
    fun combine(
        decision: FuseController.Decision,
        current: TbrPolicy.Current?,
        scheduledBasalUPerH: Double,
        cfg: TbrPolicy.Config,
        fault: TbrPolicy.FaultCode = TbrPolicy.FaultCode.NONE,
        pumpBusy: Boolean = false,
    ): Result {
        val tbr = TbrPolicy.decide(intentOf(decision.tbr), current, scheduledBasalUPerH, cfg, fault, pumpBusy)
        val effective = if (tbr.smbBlocked) decision.copy(smbU = 0.0) else decision
        return Result(
            request = requestOf(tbr.outcome),
            decision = effective,
            reason = tbr.reason,
            alarm = tbr.alarm,
        )
    }
}

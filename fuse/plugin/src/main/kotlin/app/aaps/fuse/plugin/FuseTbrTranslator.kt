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
        // C7a — GEMEINSAMES ZERTIFIKAT (Codex-Adjudication H3, D-Tabelle C7).
        val jointVeto = effective.smbU > 0.0 && endsWithholding(tbr.outcome, current, scheduledBasalUPerH, cfg)
        return Result(
            request = if (jointVeto) null else requestOf(tbr.outcome),
            decision = effective,
            reason = if (jointVeto) "$C7A_REASON|${tbr.reason}" else tbr.reason,
            alarm = tbr.alarm,
        )
    }

    /** Grund-Praefix des C7a-Vetos. Als Konstante, weil er im Trail gesucht
     *  wird: ohne ihn saehe der unterdrueckte Abbruch aus wie "es lief nichts". */
    const val C7A_REASON = "C7A_SMB_KEEPS_WITHHOLD"

    /**
     * Wuerde diese TBR-Anforderung eine laufende ZURUECKHALTUNG beenden?
     *
     * DER BEFUND (Codex, "C7 combined-action counterexample"): der SMB wird
     * gegen eine Bahn geprueft, in der die aktuelle - oft FUSE-eigene -
     * Null-TBR steckt. Beendet die TBR-Achse diese Null im SELBEN Zyklus
     * (KEEP_CANCEL_STALE_ZERO), laeuft Profilbasal wieder an, das im Zeugnis
     * nicht enthalten war: zurueckgehaltene 0,30 U sind bei ISF 80 bis zu
     * 24 mg/dl zusaetzliche modellierte Wirkung. Zeugnis und ausgefuehrte
     * Aktion beschreiben dann zwei verschiedene Zukuenfte.
     *
     * AUFLOESUNG (konservativ, nicht symmetrisch): beides darf nicht gelten,
     * also faellt der ABBRUCH weg und die Zurueckhaltung BLEIBT - der SMB
     * bleibt. Begruendung: der SMB ist die geprueftere Groesse (er hat die
     * Kandidaten- UND die finale Wirkungspruefung bestanden) und die
     * granularere (Pumpenschritt statt 30 min Profilbasal); das Freigeben von
     * Basal dagegen war in keiner Pruefung enthalten. Und die Richtung
     * stimmt: eine bestehende Zurueckhaltung stehen zu lassen kann Insulin
     * nur senken, nie erhoehen.
     *
     * Gleiche Bauart wie [app.aaps.fuse.core.controller.LedgerHoldGate]: die
     * lockere Achse wird an der Stelle verschaerft, an der beide Groessen
     * zusammenkommen - nicht in der TBR-Tabelle, die den SMB gar nicht kennt.
     *
     * Ein Abbruch OBERHALB des Profilbasals ist ausdruecklich nicht betroffen:
     * er senkt Insulin und gehoert zum selben konservativen Vertrag.
     */
    private fun endsWithholding(
        outcome: TbrPolicy.Outcome,
        current: TbrPolicy.Current?,
        scheduledBasalUPerH: Double,
        cfg: TbrPolicy.Config,
    ): Boolean {
        if (current == null) return false
        val req = outcome as? TbrPolicy.Outcome.Request ?: return false
        // Nur eine laufende Zurueckhaltung ist zu schuetzen.
        if (TbrPolicy.classify(current.absoluteRateUPerH, scheduledBasalUPerH, cfg.basalStepUPerH) == TbrPolicy.Direction.POSITIVE)
            return false
        // Beendet wird sie durch den Abbruch (Dauer 0) ODER durch eine
        // ANHEBUNG der laufenden Rate. Die Erneuerung derselben Null
        // (SAFETY_ZERO_RENEW: Rate 0, Dauer 30) ist keines von beidem.
        return req.durationMin <= 0 ||
            req.rateUPerH > current.absoluteRateUPerH + cfg.basalStepUPerH / 2.0
    }
}

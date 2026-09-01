package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.TbrPolicy
import kotlin.math.min

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
        FuseController.TbrAction.PARTIAL_BASAL -> TbrPolicy.Intent.PARTIAL_BASAL
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
     * DAS FUENFTE TOR - und der Grund, warum es einen Grund braucht.
     *
     * Bis zum 11.08. stand hier `if (tbr.smbBlocked) smbU = 0.0`, und das eine
     * Bit trug SECHS verschiedene Ursachen. `MarkerAuthorisesRelease` oeffnete
     * darueber vier Tore im Regler - und diese Zeile nullte die Menge trotzdem,
     * weil der Schutz-Nullstrom bei Tief zwangslaeufig mitkommt. Die Einstellung
     * war wirkungslos, gemessen am Geraet: `block=NONE bind=primeRelease
     * floor=0.10 smb=0.0`.
     *
     * `smbBlocked = false` waere die falsche Reparatur gewesen: dasselbe Bit
     * schuetzt bei belegter Pumpe, fehlendem Sicherheits-Schnappschuss,
     * Kern-/Eingangsfehlern und `FAKE_EXTENDED`. Also drei Faelle statt zwei:
     *
     * 1. KEIN Block -> die ganze zertifizierte Menge.
     * 2. Block AUSSCHLIESSLICH wegen `SAFETY_ZERO`, und die Menge traegt eine
     *    ausdrueckliche manuelle Autorisierung -> nur DEREN Anteil.
     * 3. Jeder andere Grund -> 0 U. Ohne Ausnahme, auch mit Marker.
     *
     * Die Herkunft kommt aus [FuseController.Decision.markerAuthorizedU],
     * nicht aus `bindingLimit` und nicht aus einem Grundtext.
     */
    private fun applyBlock(
        decision: FuseController.Decision,
        cause: TbrPolicy.SmbBlockCause,
    ): FuseController.Decision = when {
        cause == TbrPolicy.SmbBlockCause.NONE                                    -> decision
        cause == TbrPolicy.SmbBlockCause.SAFETY_ZERO &&
            decision.markerAuthorizedU > 0.0                                  ->
            decision.copy(smbU = min(decision.smbU, decision.markerAuthorizedU))

        else                                                                     -> decision.copy(smbU = 0.0)
    }

    /**
     * Fuehrt beide Achsen zusammen: die Mengenentscheidung des Reglers und die
     * TBR-Entscheidung der Tabelle.
     */
    /**
     * DER NACHWEIS, dass der SCHUTZGRUND einer laufenden Null in DIESEM Zyklus
     * weg ist. Als reine Funktion, weil er sonst an zwei Aufrufstellen im
     * Runner auseinanderliefe.
     *
     * KEIN Kommentarbeweis, sondern ein STRUKTURARGUMENT ueber
     * [FuseController.decide]: die drei Bloecke unten sind ausschliesslich
     * UNTERHALB der Schutzpruefungen erreichbar - Health, SafetyHold,
     * PumpBusy, fehlende Bahn, Guard-Boden und Schwanz kehren alle VORHER
     * zurueck. Wer einen dieser drei Bloecke traegt, hat sie in DIESEM Zyklus
     * alle bestanden.
     *
     * DREI DINGE STEHEN BEWUSST NICHT IM NACHWEIS:
     *
     *  - der LEDGER-HOLD ist aus dem Block nicht ablesbar (LedgerHoldGate
     *    ueberschreibt ihn bei smbU <= 0 nicht) und kommt deshalb getrennt
     *    herein.
     *  - das REBOUND-FENSTER (Auflage aus dem Replay 15.08.): dort ist der
     *    Guard formal frei, inhaltlich aber nicht - das aufgeblaehte r nach
     *    einem Tief ist genau der Grund, warum das Fenster existiert. Ein
     *    Abbruch faellt sonst in den ersten Zyklus nach dem SafetyHold,
     *    waehrend das Rebound-Totband noch jede Menge blockt.
     *  - `Block.NONE` (dosierender Zyklus - dort gilt der KEEP-Pfad und
     *    darueber C7a), CANDIDATE, BELOW_PUMP_INCREMENT, LEDGER_HOLD,
     *    NO_INPUT, HORIZON_MISSING.
     *
     * Das NACHT-Totband ist ausdruecklich NICHT ausgeschlossen: es traegt
     * NO_DEMAND, und genau dieser Fall ist der gemessene Anlass (Null
     * ueberdauert ihren Grund ~100 min je Nacht).
     */
    fun reasonGone(
        decision: FuseController.Decision,
        ledgerHold: Boolean,
        reboundWindow: Boolean,
    ): Boolean = !ledgerHold && !reboundWindow && decision.block in GUARD_CHAIN_PASSED

    private val GUARD_CHAIN_PASSED = setOf(
        // B1 (Tonis Entscheid 29.08.): ein erschoepfter Exposure-Raum ist
        // kein Gefahrensignal - er beendet eine stehende Null wie
        // IOB_TH_REACHED, sofern Ledger/Rebound frei sind (dieselben
        // Bedingungen im Aufrufer).
        FuseController.Block.EXPOSURE_LIMIT,
        FuseController.Block.NO_DEMAND,
        FuseController.Block.MAX_IOB_REACHED,
        FuseController.Block.IOB_TH_REACHED,
    )


    fun combine(
        decision: FuseController.Decision,
        current: TbrPolicy.Current?,
        scheduledBasalUPerH: Double,
        cfg: TbrPolicy.Config,
        fault: TbrPolicy.FaultCode = TbrPolicy.FaultCode.NONE,
        pumpBusy: Boolean = false,
        /** Die fertige Teilbasal-Rate [U/h] aus BasalRecoverySearch;
         *  0 = keine Teilstufe (Default = bisheriges Verhalten). */
        partialRateUPerH: Double = 0.0,
        /** s. [reasonGone]. Default false = Verhalten wie vor dem 15.08. */
        protectionCleared: Boolean = false,
        /** Bisher erfolglose Abbruchversuche in Folge (Medtrum-Backoff). */
        endZeroAttempts: Int = 0,
        /** ZERO-LATCH (Toni 24.08. abends): die Null kommt NUR vom Latch,
         *  nicht aus einem Sicherheitsbefund DIESES Zyklus. Die Rate wird
         *  wie SAFETY_ZERO gesetzt/erneuert, aber der SMB-Anteil bleibt
         *  UNBERUEHRT - der Latch haelt ausschliesslich die Basalachse,
         *  der positive Insulinpfad ist strukturell unbeeinflusst. */
        latchZeroOnly: Boolean = false,
    ): Result {
        val tbr = TbrPolicy.decide(
            intentOf(decision.tbr), current, scheduledBasalUPerH, cfg, fault, pumpBusy,
            protectionCleared = protectionCleared,
            partialRateUPerH = partialRateUPerH,
            endZeroAttempts = endZeroAttempts,
            // C8 UNABHAENGIG VOM INTENT (Toni 17.08.): seit das Fundament auch
            // in unsicherer Lage stehen bleibt, traegt der Intent die
            // Unsicherheit nicht mehr. Ohne diese Zeile gab ein Guard-Zyklus
            // unter FAKE_EXTENDED wieder Insulin frei (gemessen: 0,1 U statt 0).
            //
            // Gelesen wird das TYPISIERTE Feld, nicht `decision.block`: der
            // Block ist hier laengst ueberschrieben - `finalVeto` setzt
            // CANDIDATE, MarkerFloor setzt NONE. Genau daran ist der erste
            // Anlauf dieses Fixes gescheitert.
            unsafeSituation = decision.unsafeSituation,
        )
        val effective = applyBlock(
            decision,
            if (latchZeroOnly && tbr.smbBlockCause == TbrPolicy.SmbBlockCause.SAFETY_ZERO)
                TbrPolicy.SmbBlockCause.NONE
            else tbr.smbBlockCause,
        )
        // C7a — GEMEINSAMES ZERTIFIKAT (Codex-Adjudication H3, D-Tabelle C7).
        val ends = effective.smbU > 0.0 && endsWithholding(tbr.outcome, current, scheduledBasalUPerH, cfg)
        // C7c — DIE AUTORISIERUNG ZERTIFIZIERT BEIDE GEMEINSAM (Toni 17.08.).
        //
        // C7a hielt die laufende Null mit dem Argument, das Freigeben von
        // Basal sei "in keiner Pruefung enthalten". Unter einer aktiven
        // Mahlzeiten-/Absorptionslage kehrt sich das um: das Profilbasal IST
        // die autorisierte Grundlinie des Vertrags ("Profilbasal bleibt
        // erhalten"), und die Huelle wurde unter der Annahme bemessen, dass es
        // laeuft. Gemessen am 17.08.: 3,45 U Huelle geliefert, 0,35 U Basal
        // per Null einbehalten - vorne Gas, hinten Bremse, netto 3,10 U.
        //
        // Abbruch der Null und SMB verlassen den Zyklus deshalb als EINE
        // gemeinsam autorisierte Entscheidung. Der Traeger ist TYPISIERT -
        // die Marker-Menge ([FuseController.Decision.markerAuthorizedU] > 0)
        // oder die Basal-Grundregel ([FuseController.Decision
        // .basalFloorProtected], gestempelt vom BasalFloorGuard, der Nah-Tief,
        // gemessenes Tief und FAKE_EXTENDED bereits ausgeschlossen hat) -
        // nie ein Grundtext.
        //
        // WARUM DAS KEIN GEMESSENES TIEF beruehren kann, als
        // Strukturargument: einen Abbruch (Dauer 0) erzeugt die TBR-Tabelle
        // nur unter KEEP oder NO_POSITIVE - ein gemessenes Tief traegt
        // ZERO_TEMP, laeuft als SAFETY_ZERO und stellt nie einen Abbruch.
        // `endsWithholding` schuetzt zudem nur ZURUECKHALTUNGEN; fremde
        // Absenkungen bleiben unangetastet wie bisher (C7b).
        val authorized = effective.markerAuthorizedU > 0.0 || effective.basalFloorProtected
        val jointVeto = ends && !authorized
        val markerRelease = ends && authorized
        return Result(
            request = if (jointVeto) null else requestOf(tbr.outcome),
            decision = effective,
            reason = when {
                jointVeto     -> "$C7A_REASON|${tbr.reason}"
                markerRelease -> "$C7C_REASON|${tbr.reason}"
                else          -> tbr.reason
            },
            alarm = tbr.alarm,
        )
    }

    /** Grund-Praefix des C7a-Vetos. Als Konstante, weil er im Trail gesucht
     *  wird: ohne ihn saehe der unterdrueckte Abbruch aus wie "es lief nichts". */
    const val C7A_REASON = "C7A_SMB_KEEPS_WITHHOLD"

    /** Grund-Praefix der marker-autorisierten gemeinsamen Freigabe: die Null
     *  endet UND der SMB laeuft, beides unter derselben Autorisierung. Im
     *  Trail unterscheidbar von einem Abbruch ohne parallelen SMB. */
    const val C7C_REASON = "C7C_MARKER_JOINT_RELEASE"

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

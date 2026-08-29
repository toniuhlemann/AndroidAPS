package app.aaps.fuse.core.controller

/**
 * DER TYPISIERTE SMB-STATUS (Toni 29.08. spaet, Viewer-/Widget-Vertrag).
 *
 * Der Fork exportiert den Zustand TYPISIERT; kein Anzeiger (Widget,
 * Dashboard, FUSE-Tab, Viewer) raet ihn aus Reason-Texten. Vier Zustaende:
 *
 *  - FREE:      eine positive SMB duerfte alle finalen Tore passieren und
 *               es besteht mindestens ein Pumpenschritt Raum.
 *  - STOP:      ein KONKRETES finales Tor verhindert positives Insulin -
 *               der Grund steht typisiert daneben.
 *  - NO_DEMAND: Technik und Grenzen waeren offen, aber es besteht kein
 *               positiver Bedarf. AUSDRUECKLICH kein Alarmzustand - ein
 *               ruhiger Zielverlauf darf nicht wie eine Stoerung aussehen.
 *  - UNKNOWN:   der Zyklus ist nicht auswertbar (kein Input/Abbruch); die
 *               Anzeigeseite nutzt UNKNOWN zusaetzlich fuer fehlenden oder
 *               veralteten Export.
 *
 * REINE ABLEITUNG aus typisierten Fakten des Zyklus - Block, Endmenge,
 * Bedarf, freier Raum. Die when-Zweige ueber [FuseController.Block] sind
 * OHNE else: ein neuer Block zwingt hier zu einer bewussten Einordnung
 * (dasselbe Prinzip wie MarkerAuthorization).
 */
object SmbStatus {

    enum class State { FREE, STOP, NO_DEMAND, UNKNOWN }

    /** Typisierte Stop-Gruende - dieselben Klassen, die die Tore benennen. */
    enum class StopReason {
        EXPOSURE, IOB_TH, MAX_IOB, GUARD, TAIL, HEALTH, LEDGER, PUMP,
        SAFETY, DESCENT, DEFERRED,
    }

    data class Verdict(val state: State, val stopReason: StopReason?)

    /**
     * @param freeHeadroomU der freie Zusatzdosier-Raum dieses Zyklus
     *   (Gate-Headroom im Zentralmodus, sonst min der Dosier-Headrooms);
     *   `null` = nicht bestimmbar, dann entscheidet allein der Block.
     * @param headroomBinding Name der engsten Mengengrenze (fuer den Fall,
     *   dass der Raum unter einem Pumpenschritt liegt, ohne dass ein Block
     *   ihn benannt hat).
     */
    fun of(
        block: FuseController.Block,
        smbU: Double,
        insulinReqU: Double?,
        pumpIncrementU: Double,
        freeHeadroomU: Double?,
        headroomBinding: String?,
    ): Verdict = when (block) {
        FuseController.Block.NO_INPUT -> Verdict(State.UNKNOWN, null)
        FuseController.Block.HEALTH_NOT_READY -> stop(StopReason.HEALTH)
        // Der fehlende Bewertungshorizont ist ein technisches Tor derselben
        // Klasse wie ein unreifes Signal.
        FuseController.Block.HORIZON_MISSING -> stop(StopReason.HEALTH)
        FuseController.Block.SAFETY_HOLD -> stop(StopReason.SAFETY)
        FuseController.Block.PUMP_BUSY -> stop(StopReason.PUMP)
        FuseController.Block.GUARD_FLOOR -> stop(StopReason.GUARD)
        // Die Kandidatensuche hat inhaltlich genullt (Guard risse MIT der
        // Dosis) - dieselbe Schutzklasse wie der Guard-Boden.
        FuseController.Block.CANDIDATE -> stop(StopReason.GUARD)
        FuseController.Block.IOB_TH_REACHED -> stop(StopReason.IOB_TH)
        FuseController.Block.MAX_IOB_REACHED -> stop(StopReason.MAX_IOB)
        FuseController.Block.MEASURED_DESCENT_RISK -> stop(StopReason.DESCENT)
        FuseController.Block.EXPOSURE_LIMIT -> stop(StopReason.EXPOSURE)
        FuseController.Block.MARKER_PRIME_DEFERRED -> stop(StopReason.DEFERRED)
        FuseController.Block.LEDGER_HOLD -> stop(StopReason.LEDGER)
        FuseController.Block.TAIL -> stop(StopReason.TAIL)
        FuseController.Block.NONE,
        FuseController.Block.BELOW_PUMP_INCREMENT,
        FuseController.Block.NO_DEMAND,
        -> offen(smbU, insulinReqU, pumpIncrementU, freeHeadroomU, headroomBinding)
    }

    private fun stop(grund: StopReason) = Verdict(State.STOP, grund)

    /** Kein Block hat gesprochen - jetzt zaehlen Raum und Bedarf. */
    private fun offen(
        smbU: Double,
        insulinReqU: Double?,
        pumpIncrementU: Double,
        freeHeadroomU: Double?,
        headroomBinding: String?,
    ): Verdict {
        // Ein Raum unter einem Pumpenschritt IST ein Stop - auch wenn kein
        // Block ihn benannt hat (z.B. bedarfsloser Zyklus an voller Grenze).
        // FREI wuerde hier behaupten, eine positive SMB kaeme durch.
        if (freeHeadroomU != null && freeHeadroomU < pumpIncrementU - 1e-9) {
            return stop(reasonOfBinding(headroomBinding))
        }
        return if (smbU > 0.0 || (insulinReqU ?: 0.0) > 1e-12) Verdict(State.FREE, null)
        else Verdict(State.NO_DEMAND, null)
    }

    /** Der Name der engsten Grenze, wie ihn Gate/ExposureView vergeben. */
    private fun reasonOfBinding(binding: String?): StopReason = when (binding) {
        "iobThHeadroom" -> StopReason.IOB_TH
        "maxIobHeadroom" -> StopReason.MAX_IOB
        // Beide Kontextgrenzen tragen am Gate ihren Profilnamen.
        "mealExposureLimit", "correctionExposureLimit" -> StopReason.EXPOSURE
        else -> StopReason.EXPOSURE
    }
}

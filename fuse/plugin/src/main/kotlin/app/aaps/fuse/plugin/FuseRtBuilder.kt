package app.aaps.fuse.plugin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.fuse.core.controller.FuseController

/**
 * Baut das `RT`, das der Loop erwartet. Reine Uebersetzung — hier faellt keine
 * Entscheidung mehr, die steht schon im [FuseController.Decision].
 *
 * Drei Eigenheiten des Loops, im Quellcode nachgeprueft (DetermineBasalResult.kt:90-112,
 * LoopPlugin.kt:504/639/904) und leicht zu uebersehen:
 *
 *  1. `isTempBasalRequested` laesst sich NICHT setzen. Es entsteht ausschliesslich
 *     daraus, dass `rate` UND `duration` beide non-null sind. Nur eines von beiden
 *     zu fuellen erzeugt stillschweigend gar keine TBR-Anforderung.
 *  2. Der SMB kommt aus `units`, nicht aus einem Feld namens `smb`.
 *  3. `deliverAt` ist tragend: der Loop verwirft einen Mikrobolus, dessen
 *     `deliverAt` mehr als etwa eine Minute zurueckliegt.
 */
object FuseRtBuilder {

    fun build(
        nowMs: Long,
        bgMgdl: Double,
        targetMgdl: Double,
        iobU: Double,
        decision: FuseController.Decision,
        tbr: FuseController.TbrRequest?,
        gate: FusePumpGate.Result,
        profileIsfMgdlPerU: Double,
    ): RT {
        val reason = StringBuilder()
        reason.append("FUSE ").append(gate.reason)
        reason.append(" | phase=").append(decision.block.name)
        reason.append(" | insulinReq=").append(fmt(decision.insulinReqU))
        decision.predAtReleaseMgdl?.let { reason.append(" | predRelease=").append(fmt(it)) }
        decision.minLowerMgdl?.let { reason.append(" | minLower=").append(fmt(it)) }
        reason.append(" | limit=").append(decision.bindingLimit)
        if (decision.smbU > 0.0) reason.append(" | SMB=").append(fmt(decision.smbU))
        tbr?.let { reason.append(" | TBR=").append(fmt(it.rateUPerH)).append("U/h/").append(it.durationMin).append("min") }

        // R74-F1: Der Riegel sperrt ALLE Aktuatoren gemeinsam, nicht nur den SMB.
        // Die erste Fassung filterte units/deliverAt und liess rate/duration
        // ungehindert durch — die dokumentierte Invariante "FUSE aktuiert nur
        // gegen VirtualPump" war damit nur zur Haelfte umgesetzt, und der Test
        // dazu prueft genau diese Haelfte. Deshalb wird hier EIN Schalter fuer
        // alles verwendet, statt die Bedingung je Feld zu wiederholen.
        val mayActuate = gate.allowed
        val emitSmb = mayActuate && decision.smbU > 0.0
        val emitTbr = mayActuate && tbr != null

        return RT(
            algorithm = APSResult.Algorithm.FUSE,
            timestamp = nowMs,
            bg = bgMgdl,
            targetBG = targetMgdl,
            insulinReq = decision.insulinReqU,
            eventualBG = decision.predAtReleaseMgdl,
            IOB = iobU,
            reason = reason,
            // Beide oder keines — s. Punkt 1 oben.
            rate = if (emitTbr) tbr!!.rateUPerH else null,
            duration = if (emitTbr) tbr!!.durationMin else null,
            units = if (emitSmb) decision.smbU else null,
            deliverAt = if (emitSmb) nowMs else null,
            variable_sens = profileIsfMgdlPerU,
            consoleLog = mutableListOf(reason.toString()),
        )
    }

    private fun fmt(d: Double) = String.format(java.util.Locale.ROOT, "%.2f", d)
}

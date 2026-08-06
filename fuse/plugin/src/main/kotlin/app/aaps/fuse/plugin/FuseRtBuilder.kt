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

    /**
     * `bgMgdl`, `targetMgdl`, `iobU` und `profileIsfMgdlPerU` sind NULLBAR: ein
     * Zyklus, der schon am fehlenden Profil oder am fehlenden Signal endet,
     * kennt sie nicht. `0.0` einzutragen waere kein Platzhalter, sondern eine
     * Behauptung — und im Nightscout-Verlauf spaeter nicht mehr von einem echten
     * Messwert zu unterscheiden.
     */
    fun build(
        nowMs: Long,
        bgMgdl: Double?,
        targetMgdl: Double?,
        iobU: Double?,
        decision: FuseController.Decision,
        tbr: FuseController.TbrRequest?,
        gate: FusePumpGate.Result,
        profileIsfMgdlPerU: Double?,
        /** `TT` oder `profile`. Steht im Klartext im Grund, weil ein Ziel ohne
         *  Herkunft im Nachhinein nicht mehr zuzuordnen ist: 100 mg/dl koennen
         *  das Profilziel ODER eine gesetzte TT sein. */
        targetSource: String? = null,
        /** Das Signal des Zyklus. Nullbar, weil ein Abbruch vor Schritt 1 keines
         *  hat — dann fehlt der Abschnitt, statt Nullen zu behaupten. */
        signal: FuseSignalSource.Signal? = null,
        band: app.aaps.fuse.core.signal.PairSlopeBand.Estimate? = null,
        methodId: String? = null,
        /** Das Minimum der MITTELbahn. Ohne diesen Wert ist ab dem Moment, in
         *  dem lower < mean gilt, nicht mehr entscheidbar, ob ein GUARD_FLOOR
         *  vom Band kommt oder von der Lage — und genau diese Unterscheidung
         *  ist der Zweck des ersten Laufs. */
        minMeanMgdl: Double? = null,
    ): RT {
        val reason = StringBuilder()
        reason.append("FUSE ").append(gate.reason)
        // "block=", nicht "phase=": das hier ist FuseController.Block, die
        // Observer-PHASE ist etwas anderes. Die alte Beschriftung hat zwei
        // Groessen unter einem Namen gefuehrt.
        reason.append(" | block=").append(decision.block.name)
        signal?.let {
            reason.append(" | q1=").append(fmt(it.q1))
            it.rSigned?.let { r -> reason.append(" r=").append(String.format(java.util.Locale.ROOT, "%.3f", r)) }
            reason.append(" n=").append(it.samplesUsed).append('/').append(it.rawSeriesSize)
            // Die Fenstergrenze steht NUR dann im Grund, wenn sie wirklich
            // gegriffen hat. Ein "bound=NONE" in jeder Zeile waere Rauschen und
            // wuerde die Faelle verdecken, auf die es ankommt.
            if (it.boundedBy != app.aaps.fuse.core.signal.SignalWindow.Bound.NONE)
                reason.append(" bound=").append(it.boundedBy.name).append('@').append(it.windowFromTs)
            if (it.q1Outlier) reason.append(" OUTLIER")
        }
        targetMgdl?.let { reason.append(" | target=").append(fmt(it)).append('(').append(targetSource ?: "?").append(')') }
        reason.append(" | insulinReq=").append(fmt(decision.insulinReqU))
        decision.predAtReleaseMgdl?.let { reason.append(" | predRelease=").append(fmt(it)) }
        decision.minLowerMgdl?.let { reason.append(" | minLower=").append(fmt(it)) }
        minMeanMgdl?.let { reason.append(" minMean=").append(fmt(it)) }
        band?.let {
            reason.append(" | drive=").append(f3(it.mean)).append('/').append(f3(it.lower))
                .append(" spread=").append(f3(it.spread)).append(" pairs=").append(it.pairCount)
            methodId?.let { m -> reason.append(" m=").append(m) }
        }
        decision.tail?.let {
            reason.append(" | tail=")
            if (it.usable) reason.append(f3(it.headroomU)).append("U(budget=").append(f3(it.budgetU))
                .append(" iobH=").append(f3(it.existingU)).append(')')
            else reason.append("INVALID(").append(it.invalidReason).append(')')
            // Der Unvollstaendigkeitsvermerk steht in JEDER Zeile, in der der
            // Schwanz bewertet wurde. Er ist der Unterschied zwischen "geprueft"
            // und "so weit geprueft, wie es heute geht".
            reason.append(' ').append(it.completeness).append(' ').append(it.lowerBgAtHSource)
            if (decision.tailCostU > 0.0) reason.append(" cost=").append(f3(decision.tailCostU))
        }
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

    private fun f3(d: Double) = String.format(java.util.Locale.ROOT, "%.3f", d)

    private fun fmt(d: Double) = String.format(java.util.Locale.ROOT, "%.2f", d)
}

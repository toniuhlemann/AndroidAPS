package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import app.aaps.fuse.core.predictor.PredictorResult
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * FUSE-Regelkern: Trajektorie hinein, Kanalentscheidung heraus.
 *
 * Kein zweiter IOB-Abzug: die Wirkung des vorhandenen Insulins steckt bereits
 * in der Trajektorie (K2 v0.3 §0.1). Wer hier nochmal `- iob` rechnet, zieht
 * denselben Bestand zweimal ab.
 *
 * Rein und deterministisch — die VirtualPump-Sperre sitzt bewusst NICHT hier,
 * sondern im Android-Adapter, wo der tatsaechlich gewaehlte Pumpentyp bekannt
 * ist. Ein reiner Kern kann eine Pumpe nicht pruefen und soll es nicht
 * vortaeuschen.
 */
object FuseController {

    data class State(
        val health: Health,
        val safetyHold: Boolean,
        val phase: Phase,
        val netIobU: Double,
        val bolusIobU: Double,
        val basalIobU: Double,
        val iobThU: Double,
        val maxIobU: Double,
        val targetMgdl: Double,
        val isfMgdlPerU: Double,
        val smbRatio: Double,
        val pumpIncrementU: Double,
        val maxSmbU: Double,
        val pumpBusy: Boolean,
    ) {
        /** Bindungsgroesse fuer iobTH: zurueckgehaltenes Basal waehrend Zero-TBR
         *  darf KEIN zusaetzliches SMB-Budget erzeugen (Fork-Praxis). */
        val capIobU: Double get() = max(netIobU, bolusIobU)
    }

    data class Limits(
        /** Untergrenze, die die pessimistische Bahn nicht unterschreiten darf. */
        val guardFloorMgdl: Double = 70.0,
        /** Horizont, auf dem der Bedarf abgelesen wird (Index in points). */
        val releaseHorizonMin: Int = 30,
    )

    enum class TbrAction { KEEP_CURRENT, CANCEL_TO_SCHEDULED, ZERO_TEMP, NO_NEW_POSITIVE }

    enum class Block {
        NONE, HEALTH_NOT_READY, SAFETY_HOLD, PUMP_BUSY, GUARD_FLOOR,
        NO_DEMAND, IOB_TH_REACHED, MAX_IOB_REACHED, BELOW_PUMP_INCREMENT, HORIZON_MISSING,
    }

    data class Decision(
        val smbU: Double,
        val tbr: TbrAction,
        val block: Block,
        val insulinReqU: Double,
        val predAtReleaseMgdl: Double?,
        val minLowerMgdl: Double?,
        val bindingLimit: String,
    )

    fun decide(state: State, prediction: PredictorResult?, limits: Limits = Limits()): Decision {
        fun none(block: Block, tbr: TbrAction = TbrAction.NO_NEW_POSITIVE) =
            Decision(0.0, tbr, block, 0.0, null, null, block.name)

        // Reihenfolge ist Absicht: Zustand vor Zahlen. Eine Dosis aus einer
        // Trajektorie, die gar nicht gelten darf, waere der teuerste Fehler.
        if (state.health != Health.READY) return none(Block.HEALTH_NOT_READY)
        if (state.safetyHold) return none(Block.SAFETY_HOLD, TbrAction.ZERO_TEMP)
        if (state.pumpBusy) return none(Block.PUMP_BUSY, TbrAction.KEEP_CURRENT)
        if (prediction == null) return none(Block.HORIZON_MISSING)

        val release = prediction.points.firstOrNull { it.offsetMin == limits.releaseHorizonMin }
            ?: return none(Block.HORIZON_MISSING)

        // Guard: bewertet wird das MINIMUM der pessimistischen Bahn, nicht ihr
        // Endwert — eine Bahn kann harmlos enden und zwischendurch tief gehen.
        if (prediction.minLowerBg < limits.guardFloorMgdl) {
            return Decision(
                0.0, TbrAction.ZERO_TEMP, Block.GUARD_FLOOR, 0.0,
                release.meanBg, prediction.minLowerBg, "guardFloor=${limits.guardFloorMgdl}",
            )
        }

        // Kein zweites "- iob": die IOB-Wirkung ist in predBG bereits enthalten.
        val insulinReq = (release.meanBg - state.targetMgdl) / state.isfMgdlPerU
        if (insulinReq <= 0.0) {
            return Decision(
                0.0, TbrAction.ZERO_TEMP, Block.NO_DEMAND, insulinReq,
                release.meanBg, prediction.minLowerBg, "insulinReq<=0",
            )
        }

        val maxIobHeadroom = state.maxIobU - state.netIobU
        if (maxIobHeadroom <= 0.0) {
            return Decision(
                0.0, TbrAction.NO_NEW_POSITIVE, Block.MAX_IOB_REACHED, insulinReq,
                release.meanBg, prediction.minLowerBg, "maxIOB=${state.maxIobU}",
            )
        }

        // iobTH ist die Grenze zwischen schnellem und langsamem Kanal — NICHT
        // der Gesamtdeckel. Oberhalb laeuft nur noch Basal weiter.
        val fastHeadroom = state.iobThU - state.capIobU
        if (fastHeadroom <= 0.0) {
            return Decision(
                0.0, TbrAction.NO_NEW_POSITIVE, Block.IOB_TH_REACHED, insulinReq,
                release.meanBg, prediction.minLowerBg, "iobTH=${state.iobThU}",
            )
        }

        val candidates = listOf(
            "smbRatio" to insulinReq * state.smbRatio,
            "iobThHeadroom" to fastHeadroom,
            "maxIobHeadroom" to maxIobHeadroom,
            "maxSmb" to state.maxSmbU,
        )
        val binding = candidates.minByOrNull { it.second }!!
        val raw = binding.second

        // AUSSCHLIESSLICH abwaerts runden: eine Freigabe darf durch Rundung nie
        // groesser werden. Unter dem Pumpeninkrement gibt es keinen SMB — es
        // wird NICHT auf die Mindestmenge aufgerundet.
        val deliverable = floor(raw / state.pumpIncrementU) * state.pumpIncrementU
        if (deliverable < state.pumpIncrementU) {
            return Decision(
                0.0, TbrAction.KEEP_CURRENT, Block.BELOW_PUMP_INCREMENT, insulinReq,
                release.meanBg, prediction.minLowerBg, binding.first,
            )
        }

        return Decision(
            smbU = min(deliverable, raw),
            tbr = TbrAction.KEEP_CURRENT,
            block = Block.NONE,
            insulinReqU = insulinReq,
            predAtReleaseMgdl = release.meanBg,
            minLowerMgdl = prediction.minLowerBg,
            bindingLimit = binding.first,
        )
    }
}

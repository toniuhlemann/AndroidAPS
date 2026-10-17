package app.aaps.fuse.plugin.replay

import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.insulin.InsulinType
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.plugin.AapsUnitInsulinSampler
import app.aaps.fuse.plugin.FuseCycleRunner

/**
 * Der Einheitskern zu einem Trail-Eintrag - ueber denselben Weg wie im Geraet ([AapsUnitInsulinSampler]),
 * nicht nachgebaut.
 *
 * Zwei Trail-Formen: Fork-Trails nennen das Insulin-Plugin (`OREF_LYUMJEV` ...) und daneben die DIA des
 * Profils; v4-Trails tragen die Kurve selbst (`ICFG:<Ende ms>:<Peak ms>:<Konzentration>`, siehe
 * `FuseCycleRunner.insulinId`). Was keines von beiden ist, liefert keinen Kern.
 */
internal object ReplayInsulin {

    private const val ICFG_PREFIX = "ICFG:"

    /** Die Plugins des Forks mit fester Kurve; ihr Peak steht in [InsulinType]. */
    private val forkPlugins = setOf(InsulinType.OREF_LYUMJEV, InsulinType.OREF_ULTRA_RAPID_ACTING, InsulinType.OREF_RAPID_ACTING)

    fun iCfg(typ: String, dia: Double): ICfg? {
        if (typ.startsWith(ICFG_PREFIX)) {
            val teile = typ.removePrefix(ICFG_PREFIX).split(":")
            val ende = teile.getOrNull(0)?.toLongOrNull() ?: return null
            val peak = teile.getOrNull(1)?.toLongOrNull() ?: return null
            val konzentration = teile.getOrNull(2)?.toDoubleOrNull() ?: return null
            return ICfg(typ, ende, peak, konzentration).takeIf { it.isUsable }
        }
        val plugin = forkPlugins.firstOrNull { it.name == typ } ?: return null
        if (!(dia > 0.0)) return null
        return ICfg(typ, insulinEndTime = (dia * 3600 * 1000).toLong(), insulinPeakTime = plugin.insulinPeakTime)
    }

    fun kernelBauer(typ: String, dia: Double): (Long) -> UnitInsulinKernel? {
        val iCfg = iCfg(typ, dia) ?: return { null }
        val model = InsulinModelProvenance(typ, iCfg.dia, iCfg.peak, FuseCycleRunner.INSULIN_SOURCE)
        return { ts ->
            (UnitInsulinKernelBuilder.build(AapsUnitInsulinSampler(iCfg, ts), ts, model, typ) as? KernelOutcome.Ok)?.kernel
        }
    }
}

package app.aaps.fuse.plugin

import app.aaps.core.data.model.ICfg
import app.aaps.fuse.core.insulin.InsulinSample
import app.aaps.fuse.core.insulin.UnitInsulinSampler

/**
 * v4: every bolus row carries its insulin definition. Tests that only need "some real insulin" use this one.
 * A new instance per call, because [ICfg] is mutable.
 */
internal fun testICfg(): ICfg = ICfg("Test", peak = 75, dia = 5.0, concentration = 1.0)

/**
 * The insulin of the FUSE runner rigs: the fork rigs used the Lyumjev plugin with the DIA of the test profile (5 h).
 * Same curve, so the rigs compute the same numbers as on the fork base (GN). A new instance per call.
 */
internal fun rigICfg(): ICfg = ICfg("Lyumjev", peak = 45, dia = 5.0, concentration = 1.0)

/** A broken insulin model for the runner's sampler seam: the IOB is not finite (NON_FINITE_SAMPLE). */
internal object NaNSampler : UnitInsulinSampler {

    override fun sampleAfterDelivery(doseU: Double, offsetMin: Int): InsulinSample = InsulinSample(iobU = Double.NaN, activityUPerMin = 0.0)
}

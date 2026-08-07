package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CandidateGateTest {

    private fun base(smb: Double = 0.30) =
        FuseController.Decision(smb, FuseController.TbrAction.KEEP_CURRENT, FuseController.Block.NONE, 1.0, 150.0, 110.0, "smbRatio")

    private fun result(smb: Double, reject: CandidateSearch.Reject? = null, binding: String = "guardFloor") =
        CandidateSearch.Result(smb, reject, binding, 150.0, 140.0, 90.0, 20.0, 5)

    @Test
    fun `die Suche kann nur beschneiden - nie erhoehen`() {
        // Suche wuerde 0,60 erlauben, Vorschlag ist 0,30 -> 0,30 bleibt.
        assertEquals(0.30, CandidateGate.apply(base(0.30), result(0.60)).smbU, 0.0)
        // Suche erlaubt nur 0,15 -> beschnitten, Herkunft benannt.
        val d = CandidateGate.apply(base(0.30), result(0.15))
        assertEquals(0.15, d.smbU, 0.0)
        assertEquals("candidate:guardFloor", d.bindingLimit)
        assertEquals(FuseController.Block.NONE, d.block) // Rest > 0: kein Block
    }

    @Test
    fun `inhaltliche Ablehnung setzt auf null und benennt den Grund`() {
        val d = CandidateGate.apply(base(0.30), result(0.0, CandidateSearch.Reject.GUARD_FLOOR))
        assertEquals(0.0, d.smbU, 0.0)
        assertEquals(FuseController.Block.CANDIDATE, d.block)
        assertEquals("candidate:GUARD_FLOOR", d.bindingLimit)
    }

    /** Ein Pruefer-Ausfall darf keine Dosis nullen - die Basis gilt, der
     *  Ausfall gehoert in den Export, nicht in die Menge. */
    @Test
    fun `technische Ablehnungen lassen die Basis unveraendert`() {
        for (r in listOf(
            CandidateSearch.Reject.GRID_INCONSISTENT, CandidateSearch.Reject.ISF_SLOT_MISSING,
            CandidateSearch.Reject.MODEL_HORIZON_TOO_SHORT, CandidateSearch.Reject.NON_FINITE,
            CandidateSearch.Reject.DELIVERY_AFTER_RELEASE, CandidateSearch.Reject.NO_EFFECT_IN_WINDOW,
            CandidateSearch.Reject.LEDGER_HOLD, CandidateSearch.Reject.INVALID_BAND,
        )) {
            val d = CandidateGate.apply(base(0.30), result(0.0, r))
            assertEquals(0.30, d.smbU, 0.0) { "genullt trotz technischem Reject $r" }
            assertEquals(CandidateGate.Kind.UNAVAILABLE, CandidateGate.kindOf(r))
        }
    }

    @Test
    fun `ohne Suchergebnis bleibt alles wie es war`() {
        val d = CandidateGate.apply(base(0.30), null)
        assertEquals(0.30, d.smbU, 0.0)
        assertEquals("smbRatio", d.bindingLimit)
    }

    /** Eigenschaft ueber einen Raster: nie mehr als die Basis, nie negativ. */
    @Test
    fun `Einseitigkeit ueber den Raster`() {
        var n = 0
        for (b in listOf(0.05, 0.15, 0.30, 0.75))
            for (c in listOf(0.0, 0.05, 0.10, 0.30, 0.60, 1.0))
                for (r in listOf(null, CandidateSearch.Reject.GUARD_FLOOR, CandidateSearch.Reject.GRID_INCONSISTENT)) {
                    val d = CandidateGate.apply(base(b), result(c, r))
                    assertTrue(d.smbU <= b + 1e-12 && d.smbU >= 0.0)
                    n++
                }
        assertTrue(n >= 70)
    }
}

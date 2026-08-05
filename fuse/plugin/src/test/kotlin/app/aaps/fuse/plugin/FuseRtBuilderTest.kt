package app.aaps.fuse.plugin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.fuse.core.controller.FuseController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump

/**
 * Sichert die drei Loop-Eigenheiten ab, die im Quellcode nachgeprueft wurden
 * und sich sonst still falsch verhalten.
 */
class FuseRtBuilderTest {

    private interface FakeVirtual : Pump, VirtualPump

    private val allowed = FusePumpGate.evaluate(mock(FakeVirtual::class.java))
    private val blocked = FusePumpGate.evaluate(mock(Pump::class.java))
    private val now = 1_700_000_000_000L

    private fun decision(smb: Double, block: FuseController.Block = FuseController.Block.NONE) =
        FuseController.Decision(smb, FuseController.TbrAction.KEEP_CURRENT, block, 1.5, 180.0, 110.0, "smbRatio")

    private fun build(
        smb: Double,
        tbr: FuseController.TbrRequest? = null,
        gate: FusePumpGate.Result = allowed,
    ) = FuseRtBuilder.build(now, 160.0, 100.0, 2.0, decision(smb), tbr, gate, 50.0)

    @Test
    fun `Algorithmus ist FUSE, damit der Viewer umschalten kann`() {
        assertEquals(APSResult.Algorithm.FUSE, build(0.2).algorithm)
    }

    /** rate und duration wirken NUR gemeinsam — der Loop leitet
     *  isTempBasalRequested daraus ab. */
    @Test
    fun `TBR setzt rate und duration gemeinsam oder gar nicht`() {
        val ohne = build(0.0)
        assertNull(ohne.rate); assertNull(ohne.duration)

        val mit = build(0.0, FuseController.TbrRequest(0.0, 30))
        assertNotNull(mit.rate); assertNotNull(mit.duration)
        assertEquals(0.0, mit.rate); assertEquals(30, mit.duration)
    }

    /** Der Mikrobolus steht in `units`, nicht in einem Feld namens smb. */
    @Test
    fun `SMB landet in units mit gesetztem deliverAt`() {
        val rt = build(0.25)
        assertEquals(0.25, rt.units)
        assertEquals(now, rt.deliverAt)
    }

    @Test
    fun `ohne SMB bleiben units und deliverAt null`() {
        val rt = build(0.0)
        assertNull(rt.units); assertNull(rt.deliverAt)
    }

    /** Der Riegel wirkt AUCH hier: ein Pumpenwechsel zur Laufzeit darf keinen
     *  bereits berechneten SMB durchrutschen lassen. */
    @Test
    fun `blockierte Pumpe laesst keinen SMB durch`() {
        val rt = build(0.5, gate = blocked)
        assertNull(rt.units)
        assertNull(rt.deliverAt)
        assertTrue(rt.reason.toString().contains("BLOCKED"))
    }

    @Test
    fun `die Begruendung nennt Grenze und Kennzahlen`() {
        val r = build(0.25).reason.toString()
        listOf("FUSE", "insulinReq", "predRelease", "minLower", "limit=smbRatio").forEach {
            assertTrue(r.contains(it)) { "fehlt in reason: $it -> $r" }
        }
    }
}

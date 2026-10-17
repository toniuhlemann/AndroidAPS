package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Der Viewer-Vertrag des typisierten SMB-Status (Toni 29.08. spaet). */
class SmbStatusTest {

    private fun v(
        block: FuseController.Block,
        smbU: Double = 0.0,
        req: Double? = null,
        frei: Double? = 4.0,
        binding: String? = null,
    ) = SmbStatus.of(
        block = block, smbU = smbU, insulinReqU = req,
        pumpIncrementU = 0.05, freeHeadroomU = frei, headroomBinding = binding,
    )

    /** JEDER Block ist bewusst eingeordnet - die Tabelle ist der Vertrag.
     *  Ein neuer Block faellt schon im Compiler auf (when ohne else); hier
     *  faellt eine falsche Einordnung auf. */
    @Test
    fun `jeder Stop-Block traegt seinen typisierten Grund`() {
        val erwartet = mapOf(
            FuseController.Block.HEALTH_NOT_READY to SmbStatus.StopReason.HEALTH,
            FuseController.Block.HORIZON_MISSING to SmbStatus.StopReason.HEALTH,
            FuseController.Block.SAFETY_HOLD to SmbStatus.StopReason.SAFETY,
            FuseController.Block.PUMP_BUSY to SmbStatus.StopReason.PUMP,
            FuseController.Block.GUARD_FLOOR to SmbStatus.StopReason.GUARD,
            FuseController.Block.CANDIDATE to SmbStatus.StopReason.GUARD,
            FuseController.Block.IOB_TH_REACHED to SmbStatus.StopReason.IOB_TH,
            FuseController.Block.MAX_IOB_REACHED to SmbStatus.StopReason.MAX_IOB,
            FuseController.Block.MEASURED_DESCENT_RISK to SmbStatus.StopReason.DESCENT,
            FuseController.Block.EXPOSURE_LIMIT to SmbStatus.StopReason.EXPOSURE,
            FuseController.Block.MARKER_PRIME_DEFERRED to SmbStatus.StopReason.DEFERRED,
            FuseController.Block.LEDGER_HOLD to SmbStatus.StopReason.LEDGER,
            FuseController.Block.TAIL to SmbStatus.StopReason.TAIL,
        )
        erwartet.forEach { (block, grund) ->
            assertEquals(SmbStatus.Verdict(SmbStatus.State.STOP, grund), v(block)) { "$block" }
        }
        // Und die Aufteilung deckt jeden Block genau einmal ab: die
        // uebrigen vier sind die Nicht-Stop-Seite.
        val offen = setOf(
            FuseController.Block.NONE, FuseController.Block.BELOW_PUMP_INCREMENT,
            FuseController.Block.NO_DEMAND, FuseController.Block.NO_INPUT,
        )
        assertEquals(
            FuseController.Block.entries.toSet(), erwartet.keys + offen,
            "jeder Block ist genau einmal eingeordnet",
        )
    }

    @Test
    fun `kein Input ist UNKNOWN - nicht STOP und nicht ruhig`() {
        assertEquals(SmbStatus.Verdict(SmbStatus.State.UNKNOWN, null), v(FuseController.Block.NO_INPUT))
    }

    /** Lieferung oder positiver Bedarf bei offenen Toren = FREI - auch wenn
     *  die Menge unterm Raster blieb (BELOW_PUMP_INCREMENT ist Quantisierung,
     *  kein Tor). */
    @Test
    fun `offene Tore mit Bedarf sind FREI`() {
        assertEquals(SmbStatus.State.FREE, v(FuseController.Block.NONE, smbU = 0.3, req = 1.0).state)
        assertEquals(SmbStatus.State.FREE, v(FuseController.Block.BELOW_PUMP_INCREMENT, req = 0.04).state)
    }

    /** Ohne positiven Bedarf ist die Lage RUHIG, nie rot: ein Zielverlauf
     *  darf nicht wie eine Stoerung aussehen (Tonis Auflage). */
    @Test
    fun `ohne Bedarf ist die Lage NO_DEMAND`() {
        assertEquals(SmbStatus.Verdict(SmbStatus.State.NO_DEMAND, null), v(FuseController.Block.NO_DEMAND))
        assertEquals(SmbStatus.Verdict(SmbStatus.State.NO_DEMAND, null), v(FuseController.Block.NONE, req = -0.4))
        assertEquals(SmbStatus.Verdict(SmbStatus.State.NO_DEMAND, null), v(FuseController.Block.NONE, req = null))
    }

    /** Ein Raum unter einem Pumpenschritt IST ein Stop, auch ohne
     *  benennenden Block - und der Grund kommt aus der engsten Grenze. */
    @Test
    fun `erschoepfter Raum stoppt auch ohne Block`() {
        assertEquals(
            SmbStatus.Verdict(SmbStatus.State.STOP, SmbStatus.StopReason.EXPOSURE),
            v(FuseController.Block.NO_DEMAND, frei = 0.02, binding = "mealExposureLimit"),
        )
        assertEquals(
            SmbStatus.Verdict(SmbStatus.State.STOP, SmbStatus.StopReason.IOB_TH),
            v(FuseController.Block.NONE, frei = 0.0, binding = "iobThHeadroom"),
        )
        // Unbestimmbarer Raum entscheidet nicht mit - dann zaehlt der Block.
        assertEquals(SmbStatus.State.NO_DEMAND, v(FuseController.Block.NO_DEMAND, frei = null).state)
    }
}

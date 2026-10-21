package app.aaps.fuse.plugin

import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import app.aaps.core.interfaces.overview.FuseOverviewSource.SmbSource
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * DER ZYKLUS FUER DEN HAUPTGRAPHEN (Toni 21.10.2026): dieselben Regeln wie im Viewer, fuer Live-Ring und Warmstart.
 * Alle Werte sind ausgedacht.
 */
class FuseGraphZyklusTest {

    @Test
    fun `der Zustand folgt dem Vorrang Null vor Kanal vor Guard vor Sperre vor frei`() {
        fun z(block: String? = "NONE", tbr: String? = "KEEP_CURRENT", nullAktiv: Boolean? = false, kanal: String? = null, smb: String? = "FREE", stop: String? = null) =
            FuseGraphZyklus.zustand(block, tbr, nullAktiv, kanal, smb, stop)

        assertEquals(CycleState.FREE, z())
        assertEquals(CycleState.FREE, z(smb = "NO_DEMAND"))
        assertEquals(CycleState.GUARD, z(block = "GUARD_FLOOR", smb = "STOP", stop = "GUARD"))
        assertEquals(CycleState.BLOCKED, z(block = "TAIL", smb = "STOP", stop = "TAIL"))
        assertEquals(CycleState.CHANNEL, z(smb = "STOP", stop = "GUARD", kanal = "RUNNING"))
        assertEquals(CycleState.CHANNEL, z(kanal = "PENDING"))
        // ENDING faerbt nicht mehr tuerkis: der Kanal will nicht mehr.
        assertEquals(CycleState.FREE, z(kanal = "ENDING"))
        assertEquals(CycleState.ZERO, z(tbr = "ZERO_TEMP", kanal = "RUNNING"))
        assertEquals(CycleState.ZERO, z(nullAktiv = true, smb = "STOP", stop = "GUARD"))
        assertEquals(CycleState.UNKNOWN, z(block = "NO_INPUT"))
        assertEquals(CycleState.UNKNOWN, z(block = null))
    }

    @Test
    fun `die Quelle kennt Konto, Normalweg samt Teilschritt und sonst andere`() {
        assertEquals(SmbSource.EXPECTATION, FuseGraphZyklus.quelle("EXPECTATION"))
        assertEquals(SmbSource.NORMAL, FuseGraphZyklus.quelle("NORMAL"))
        assertEquals(SmbSource.NORMAL, FuseGraphZyklus.quelle("NORMAL_SUBSTEP"))
        assertEquals(SmbSource.OTHER, FuseGraphZyklus.quelle("LIVENESS"))
        assertEquals(SmbSource.OTHER, FuseGraphZyklus.quelle(null))
    }

    @Test
    fun `eine Trail-Zeile liefert dieselben Felder wie der Live-Zyklus`() {
        val zeile = JSONObject()
            .put("computeTs", 1_800_000_060_000L)
            .put("decision", JSONObject().put("block", "GUARD_FLOOR").put("tbr", "KEEP_CURRENT").put("bindingLimit", "guardFloor=70.0").put("insulinReqU", 0.45))
            .put("basalGap", JSONObject().put("currentZeroTbrActive", false))
            .put("smb", JSONObject().put("state", "STOP").put("stopReason", "GUARD"))
            .put("rt", JSONObject().put("units", 0.2))
            .put("exposure", JSONObject().put("source", "EXPECTATION"))
            .put("slowChannel", JSONObject().put("phase", "RUNNING").put("reason", "CONTINUE").put("detail", JSONObject.NULL)
                .put("rateUPerH", 2.0).put("extraUPerH", 1.5).put("deliveredU", 0.3))
        val f = FuseGraphZyklus.felder(zeile)

        assertEquals(CycleState.CHANNEL, f.zustand)
        assertEquals(0.2, f.smbU)
        assertEquals(SmbSource.EXPECTATION, f.quelle)
        assertEquals("RUNNING", f.kanal?.phase)
        assertNull(f.kanal?.detail)
        assertEquals(4.0, f.kanal?.factor!!, 1e-9)
        assertEquals(0.3, f.kanal?.deliveredU)
        assertEquals("guardFloor=70.0", f.bindung)
        assertEquals(0.45, f.bedarfU)
    }

    @Test
    fun `ohne SMB keine Quelle, ohne Entscheidung unbekannt, ohne Kanalblock kein Kanal`() {
        val f = FuseGraphZyklus.felder(JSONObject().put("computeTs", 1L).put("rt", JSONObject().put("units", 0.0)).put("exposure", JSONObject().put("source", "NONE")))

        assertEquals(CycleState.UNKNOWN, f.zustand)
        assertNull(f.smbU)
        assertNull(f.quelle)
        assertNull(f.kanal)
        assertNull(f.bindung)
    }

    @Test
    fun `computeTs wird ohne Parsen gefunden, ofComputeTs zaehlt nicht`() {
        assertEquals(1_800_000_000_000L, FuseGraphZyklus.schnellerComputeTs("""{"a":1,"computeTs": 1800000000000,"b":2}"""))
        assertNull(FuseGraphZyklus.schnellerComputeTs("""{"priorActuation":{"ofComputeTs":5}}"""))
        assertNull(FuseGraphZyklus.schnellerComputeTs("kaputt"))
    }
}

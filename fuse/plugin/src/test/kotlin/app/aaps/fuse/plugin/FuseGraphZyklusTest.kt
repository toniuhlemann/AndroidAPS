package app.aaps.fuse.plugin

import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import app.aaps.core.interfaces.overview.FuseOverviewSource.SmbSource
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertFalse(f.bedarfVomKonto)
    }

    // ---- Bedarf des Kontos beim Warmstart (Toni 15.11.2026) ----------------------------------------------------------

    /** Eine Trail-Zeile mit Entscheidung, auf Wunsch mit Konto und gemeinsamer Bilanz. */
    private fun zeile(reglerBedarf: Any = JSONObject.NULL, konto: JSONObject? = null, bilanz: JSONObject? = null): JSONObject {
        val j = JSONObject()
            .put("computeTs", 1_800_000_060_000L)
            .put("decision", JSONObject().put("block", "NONE").put("tbr", "KEEP_CURRENT").put("insulinReqU", reglerBedarf))
        konto?.let { j.put("expectationAccount", it) }
        bilanz?.let { j.put("mealBalance", it) }
        return j
    }

    private fun konto(gilt: Boolean = true, lieferbar: Any? = 0.7): JSONObject {
        val k = JSONObject().put("applies", gilt)
        lieferbar?.let { k.put("deliverableU", it) }
        return k
    }

    /**
     * Stoppt der Normalweg am Guard, steht in der Zeile kein Bedarf des Reglers - die Portion kommt aus dem Konto. Der
     * Warmstart liefert dann den Bedarf des Kontos, wie der Live-Zyklus, damit die Halte-Pille nach einem Neustart
     * dasselbe zeigt wie davor.
     */
    @Test
    fun `eine Trail-Zeile ohne Bedarf des Reglers liefert den des Kontos`() {
        val f = FuseGraphZyklus.felder(zeile(konto = konto()))

        assertEquals(0.7, f.bedarfU)
        assertTrue(f.bedarfVomKonto)
        // Nicht berechenbar steht als JSON-null in der Zeile und zaehlt als 0, wie im Regler.
        val nichtBerechenbar = FuseGraphZyklus.felder(zeile(konto = konto(lieferbar = JSONObject.NULL)))
        assertEquals(0.0, nichtBerechenbar.bedarfU)
        assertTrue(nichtBerechenbar.bedarfVomKonto)
    }

    @Test
    fun `in der Trail-Zeile geht der Bedarf des Reglers vor dem des Kontos`() {
        val f = FuseGraphZyklus.felder(zeile(reglerBedarf = 0.45, konto = konto()))

        assertEquals(0.45, f.bedarfU)
        assertFalse(f.bedarfVomKonto)
        // Auch ein gerechneter Bedarf von null ist ein Bedarf des Reglers.
        val nullBedarf = FuseGraphZyklus.felder(zeile(reglerBedarf = 0.0, konto = konto()))
        assertEquals(0.0, nullBedarf.bedarfU)
        assertFalse(nullBedarf.bedarfVomKonto)
    }

    /** Kein Kontobedarf ohne geltendes Konto, ohne Auswertung in der Zeile (auch aeltere Zeilen) und ohne Konto. */
    @Test
    fun `ohne geltendes Konto oder ohne lieferbare Menge liefert die Trail-Zeile keinen Bedarf`() {
        for (j in listOf(zeile(), zeile(konto = konto(gilt = false)), zeile(konto = konto(lieferbar = null)))) {
            val f = FuseGraphZyklus.felder(j)
            assertNull(f.bedarfU)
            assertFalse(f.bedarfVomKonto)
            assertNull(FuseGraphZyklus.kontoBedarfU(j))
        }
    }

    /** Wie live: bei einer Rechner-Mahlzeit deckelt die Grenze der gemeinsamen Bilanz den Kontobedarf. */
    @Test
    fun `die Grenze der Bilanz deckelt den Kontobedarf der Trail-Zeile`() {
        fun bedarf(grenze: Any) = FuseGraphZyklus.kontoBedarfU(zeile(konto = konto(), bilanz = JSONObject().put("smbCapU", grenze)))

        assertEquals(0.5, bedarf(0.5))
        // Eine weitere Grenze aendert nichts, eine nicht endliche steht als JSON-null in der Zeile und zaehlt nicht.
        assertEquals(0.7, bedarf(3.0))
        assertEquals(0.7, bedarf(JSONObject.NULL))
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

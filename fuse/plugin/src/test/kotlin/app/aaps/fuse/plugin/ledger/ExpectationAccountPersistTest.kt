package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.ExpectationAccount
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.core.ledger.LedgerState
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Das Erwartungskonto im Ledger (Testkandidat E-034): optional, ohne
 * Schemasprung, restartfest - und ein ungueltiges Konto verwirft NUR das
 * Konto, nicht die Generation. Alle Zahlen sind synthetisch.
 */
class ExpectationAccountPersistTest {

    private val t0 = 1_786_000_000_000L
    private val min = 60_000L

    private val kurve: List<Double> = (0 until ExpectationAccount.CURVE_POINTS).map { k -> k * 0.1 }

    private fun konto(): ExpectationAccount.State {
        val p = ExpectationAccount.Params.of(kurve, 120, 0.5, 0.0, 90.0, 15, 0.3, true)!!
        var s = ExpectationAccount.arm(t0, t0 + 120 * min, 2.0, p, true, t0, 110.0, 50.0)
        s = ExpectationAccount.step(
            s,
            ExpectationAccount.Inputs(t0 + min, 1.0, 112.0, 100.0, 50.0, 0.3, 0.01, paused = false, stableNow = true),
        ).first
        s = ExpectationAccount.bookDirectDose(s, 0.4)
        return ExpectationAccount.cancel(s, erwU = 0.0, directU = 0.1)
    }

    private fun episoden(k: ExpectationAccount.State?) = EpisodeBudgets().also { it.expectation = k }

    @Test
    fun `das Konto ueberlebt den Rundlauf`() {
        val k = konto()
        val zurueck = LedgerCodec.decodeEpisodes(LedgerCodec.encodeEpisodes(episoden(k)))
        assertEquals(k, zurueck.expectation)
        assertFalse(zurueck.expectationDropped)
    }

    @Test
    fun `ohne Konto steht nichts in der Datei und nichts wird verworfen`() {
        val json = LedgerCodec.encodeEpisodes(episoden(null))
        assertFalse(json.has("expectation"))
        val zurueck = LedgerCodec.decodeEpisodes(json)
        assertNull(zurueck.expectation)
        assertFalse(zurueck.expectationDropped)
    }

    @Test
    fun `ein Konto ohne Erwartung ueberlebt den Rundlauf`() {
        val ohne = ExpectationAccount.arm(t0, t0 + 120 * min, 2.0, null, false, t0, 110.0, 50.0)
        val zurueck = LedgerCodec.decodeEpisodes(LedgerCodec.encodeEpisodes(episoden(ohne)))
        assertEquals(ohne, zurueck.expectation)
    }

    @Test
    fun `nicht endliche Messwerte vom Marker stehen als null und kommen als NaN zurueck`() {
        val k = konto().copy(lastBgMgdl = Double.NaN, isfAtMarkerMgdlPerU = Double.NaN, disturbanceValid = false)
        val json = LedgerCodec.encodeEpisodes(episoden(k))
        assertTrue(json.getJSONObject("expectation").isNull("lastBgMgdl"))
        val zurueck = LedgerCodec.decodeEpisodes(json).expectation
        assertNotNull(zurueck)
        assertTrue(zurueck!!.lastBgMgdl.isNaN())
        assertTrue(zurueck.isfAtMarkerMgdlPerU.isNaN())
    }

    /** Die bewusste Abweichung: verworfen wird das Konto, nicht die Generation. */
    @Test
    fun `ein ungueltiges Konto wird verworfen und die Generation bleibt gueltig`() {
        val faelle = listOf<(JSONObject) -> Unit>(
            { it.put("bookedU", -1.0) },
            { it.put("horizonMin", 500) },
            { it.put("phi0", 1.5) },
            { it.remove("cancelledU") },
            { it.put("a0U", JSONObject.NULL) },
            { it.getJSONArray("curveValues").put(3, 9.9) },
            { it.put("windowEndTs", t0 - 1) },
        )
        for ((i, kaputt) in faelle.withIndex()) {
            val json = LedgerCodec.encodeEpisodes(episoden(konto()))
            kaputt(json.getJSONObject("expectation"))
            val zurueck = LedgerCodec.decodeEpisodes(json)
            assertNull(zurueck.expectation, "Fall $i")
            assertTrue(zurueck.expectationDropped, "Fall $i")
        }
        // Kein Objekt an der Stelle: ebenso verworfen, Autorisierung unbekannt.
        val json = LedgerCodec.encodeEpisodes(episoden(konto())).put("expectation", 42)
        assertTrue(LedgerCodec.decodeEpisodes(json).expectationDropped)
        assertEquals(0L, LedgerCodec.decodeEpisodes(json).expectationDroppedAuthId)
    }

    /** Codex 11.10., Punkt 8: die Autorisierung des verworfenen Kontos bleibt fuer den Rueckfall-Ausweis lesbar. */
    @Test
    fun `das verworfene Konto nennt seine Autorisierung`() {
        val json = LedgerCodec.encodeEpisodes(episoden(konto()))
        json.getJSONObject("expectation").put("bookedU", -1.0)
        val zurueck = LedgerCodec.decodeEpisodes(json)
        assertNull(zurueck.expectation)
        assertEquals(t0, zurueck.expectationDroppedAuthId)
    }

    /** E-034 v2: das Konto der fruehen Versorgung - synthetische Einstellung, eine weitere Portion im Buch. */
    private fun fruehesKonto(): ExpectationAccount.State {
        val p = ExpectationAccount.Params.of(kurve, 120, 0.5, 0.0, 90.0, 15, 0.3, true)!!
        val e = app.aaps.fuse.core.controller.EarlySupply.Settings.of(1.5, 0.5, 8.0, 0.5, 1.0, 2.0, 10)!!
        val wirkung: (Double) -> Double = { t -> app.aaps.fuse.core.controller.EarlySupply.orefActionFraction(t, 45.0, 540.0) }
        val g = app.aaps.fuse.core.controller.EarlySupply.Switches(true, false, true, false)
        return ExpectationAccount.armEarly(t0, t0 + 120 * min, p, e, g, true, t0, 110.0, 50.0, wirkung).copy(
            bookedU = 1.0, initialDone = true,
            portions = listOf(app.aaps.fuse.core.controller.EarlySupply.Portion(t0 + 5 * min, 0.5)),
        )
    }

    @Test
    fun `das v2-Konto steht unter eigenem Schluessel und ueberlebt den Rundlauf`() {
        val k = fruehesKonto()
        val json = LedgerCodec.encodeEpisodes(episoden(k))
        // Ein Stand ohne v2 liest nur "expectation" - er sieht gar kein Konto, statt das Gesamtbudget als
        // Huellenbudget zu lesen.
        assertFalse(json.has("expectation"))
        assertTrue(json.has("earlySupply"))
        val zurueck = LedgerCodec.decodeEpisodes(json)
        assertEquals(k, zurueck.expectation)
        assertFalse(zurueck.expectationDropped)
    }

    @Test
    fun `ein ungueltiges v2-Konto wird verworfen und die Generation bleibt gueltig`() {
        val faelle = listOf<(JSONObject) -> Unit>(
            { it.put("phi0", 2.5) },
            { it.getJSONObject("early").put("initialShare", 1.5) },
            { it.getJSONObject("early").put("periodMin", 2) },
            { it.getJSONObject("early").remove("g3") },
            { it.getJSONObject("early").remove("withdrawn") },
            { it.remove("portions") },
            { it.getJSONArray("portions").put(org.json.JSONArray(listOf(t0 - 1, 0.5))) },
            { it.put("guardException", true) },
        )
        for ((i, kaputt) in faelle.withIndex()) {
            val json = LedgerCodec.encodeEpisodes(episoden(fruehesKonto()))
            kaputt(json.getJSONObject("earlySupply"))
            val zurueck = LedgerCodec.decodeEpisodes(json)
            assertNull(zurueck.expectation, "Fall $i")
            assertTrue(zurueck.expectationDropped, "Fall $i")
            assertEquals(t0, zurueck.expectationDroppedAuthId, "Fall $i")
        }
    }

    @Test
    fun `das Konto geht durch die ganze Datei`() {
        val e = episoden(konto())
        val datei = LedgerCodec.encode(LedgerState(), e, 3L, InterventionStamp("test-epoche", 1L))
        val zurueck = LedgerCodec.decode(JSONObject(datei.toString()))
        assertEquals(e.expectation, zurueck.episodes.expectation)
    }
}

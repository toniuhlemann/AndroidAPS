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
        val p = ExpectationAccount.Params.of(kurve, 120, 0.5, 0.0, 90.0, 15, 0.3)!!
        val s = ExpectationAccount.arm(t0, t0 + 120 * min, 2.0, p, true, t0, 110.0, 50.0)
        val (s1, e1) = ExpectationAccount.step(
            s,
            ExpectationAccount.Inputs(t0 + min, 1.0, 112.0, 100.0, 50.0, 0.3, 0.01, paused = false, stableNow = true),
        )
        // Konsolidierung 2.0: statt der entfallenen Direktdosis eine d_erw-Buchung mit Storno - gebucht und storniert
        // stehen damit weiter von 0 verschieden in der Datei.
        val gebucht = ExpectationAccount.book(s1, e1, finalU = 0.4).first
        return ExpectationAccount.cancel(gebucht, erwU = 0.1)
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

    /**
     * Konsolidierung 2.0: ein Stand aus einer frueheren Fassung mit Direktdosis und v1-Guard-Ausnahme laedt weiter. Die
     * drei Felder werden wie bisher geprueft, aber nicht mehr gefuehrt; geschrieben werden sie neutral (0 bzw. false).
     */
    @Test
    fun `ein aelteres Konto mit Direktdosis und Ausnahme laedt weiter`() {
        val k = konto()
        val json = LedgerCodec.encodeEpisodes(episoden(k))
        val o = json.getJSONObject("expectation")
        assertEquals(0.0, o.getDouble("directDoseU"), 0.0)
        assertEquals(0.0, o.getDouble("coveredByMarkerAuthU"), 0.0)
        assertFalse(o.getBoolean("guardException"))
        o.put("directDoseU", 0.3).put("coveredByMarkerAuthU", 0.1).put("guardException", true)
        val zurueck = LedgerCodec.decodeEpisodes(json)
        assertEquals(k, zurueck.expectation)
        assertFalse(zurueck.expectationDropped)
        // Ungueltig bleibt ungueltig - dieselbe Pruefung wie bisher.
        o.put("directDoseU", -1.0)
        assertTrue(LedgerCodec.decodeEpisodes(json).expectationDropped)
    }

    /** E-034 v2: das Konto der fruehen Versorgung - synthetische Einstellung, eine weitere Portion im Buch. */
    private fun fruehesKonto(): ExpectationAccount.State {
        val p = ExpectationAccount.Params.of(kurve, 120, 0.5, 0.0, 90.0, 15, 0.3)!!
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
    fun `die Tief-Sperre ueberlebt den Rundlauf, fehlt sie, gilt nicht gesperrt, ein altes Ende wird zur Sperre`() {
        val k = fruehesKonto()
        val gesperrt = k.copy(earlyLowLocked = true, earlyLowLockSinceTs = k.authId + 300_000L, earlyAfterLow = true)
        val json = LedgerCodec.encodeEpisodes(episoden(gesperrt))
        assertEquals(gesperrt, LedgerCodec.decodeEpisodes(json).expectation)
        // Wieder frei nach Bestaetigung: das "nach Tief" bleibt.
        val frei = gesperrt.copy(earlyLowLocked = false)
        assertEquals(frei, LedgerCodec.decodeEpisodes(LedgerCodec.encodeEpisodes(episoden(frei))).expectation)
        val f = json.getJSONObject("earlySupply")
        listOf("lowLocked", "lowLockSinceTs", "afterLow").forEach { f.remove(it) }
        val ohne = LedgerCodec.decodeEpisodes(json).expectation!!
        assertEquals(false, ohne.earlyLowLocked)
        assertEquals(false, ohne.earlyAfterLow)
        // Ein v2-Stand mit dem frueheren Ende fuer immer: gesperrt seit dem Marker, nur eine Bestaetigung hebt es auf.
        f.put("endedByLow", true)
        val alt = LedgerCodec.decodeEpisodes(json).expectation!!
        assertEquals(true, alt.earlyLowLocked)
        assertEquals(true, alt.earlyAfterLow)
        assertEquals(alt.authId, alt.earlyLowLockSinceTs)
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
    fun `die Fallgrenze steht nur abweichend in der Datei und fehlt sie, gilt der Standard`() {
        val standard = konto()
        assertFalse(LedgerCodec.encodeEpisodes(episoden(standard)).getJSONObject("expectation").has("stabilityFallMgdlPerMin"))
        val abweichend = standard.copy(stabilityFallMgdlPerMin = 1.0)
        val json = LedgerCodec.encodeEpisodes(episoden(abweichend))
        assertTrue(json.getJSONObject("expectation").has("stabilityFallMgdlPerMin"))
        assertEquals(abweichend, LedgerCodec.decodeEpisodes(json).expectation)
        json.getJSONObject("expectation").put("stabilityFallMgdlPerMin", 9.0)
        assertNull(LedgerCodec.decodeEpisodes(json).expectation)
    }

    @Test
    fun `das Konto geht durch die ganze Datei`() {
        val e = episoden(konto())
        val datei = LedgerCodec.encode(LedgerState(), e, 3L, InterventionStamp("test-epoche", 1L))
        val zurueck = LedgerCodec.decode(JSONObject(datei.toString()))
        assertEquals(e.expectation, zurueck.episodes.expectation)
    }
}

package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.EarlySupply
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

    /**
     * Konsolidierung 2.0: das Konto, wie der Runner es ohne fruehe Versorgung bucht - Budget 0, neutrale Pins (das
     * Konto v1 ist entfallen).
     */
    private fun konto(): ExpectationAccount.State {
        val p = ExpectationAccount.Params.of(kurve, 120, 90.0, 15, 0.3)!!
        val s = ExpectationAccount.armEarly(
            t0, t0 + 120 * min, p, EarlySupply.Settings.NEUTRAL, EarlySupply.Switches.NONE, true, t0, 110.0, 50.0, { 0.0 },
        )
        val (s1, e1) = ExpectationAccount.step(
            s,
            ExpectationAccount.Inputs(t0 + min, 1.0, 112.0, 100.0, 50.0, 0.3, 0.01, paused = false, stableNow = true),
        )
        // Konsolidierung 2.0: statt der entfallenen Direktdosis eine d_erw-Buchung mit Storno - gebucht und storniert
        // stehen damit weiter von 0 verschieden in der Datei.
        val gebucht = ExpectationAccount.book(s1, e1, finalU = 0.4).first
        return ExpectationAccount.cancel(gebucht, erwU = 0.1)
    }

    /** Ein Konto v1 eines aelteren Standes (ohne Pins) - so steht es unter "expectation" in der Datei. */
    private fun altKonto(): ExpectationAccount.State =
        konto().copy(phi0 = 0.5, a0U = 1.0, early = null, initialDone = false, portions = emptyList())

    private fun episoden(k: ExpectationAccount.State?) = EpisodeBudgets().also { it.expectation = k }

    @Test
    fun `das Konto ueberlebt den Rundlauf`() {
        val k = konto()
        val json = LedgerCodec.encodeEpisodes(episoden(k))
        assertTrue(json.has("earlySupply"))
        assertFalse(json.has("expectation"))
        val zurueck = LedgerCodec.decodeEpisodes(json)
        assertEquals(k, zurueck.expectation)
        assertFalse(zurueck.expectationDropped)
    }

    /**
     * Konsolidierung 2.0: ein Konto v1 aus einem aelteren Stand laedt als Konto ohne fruehe Versorgung - Verbrauch,
     * Freigabe und Strecke bleiben, sein Vorgriff fliesst nicht mehr. Geschrieben wird es danach unter dem v2-Schluessel.
     */
    @Test
    fun `ein Konto v1 eines aelteren Standes laedt mit neutralen Pins`() {
        val alt = altKonto()
        val json = LedgerCodec.encodeEpisodes(episoden(alt))
        assertTrue(json.has("expectation"))
        val zurueck = LedgerCodec.decodeEpisodes(json)
        assertEquals(alt.copy(early = EarlySupply.Pins.NEUTRAL), zurueck.expectation)
        assertFalse(zurueck.expectationDropped)
        assertTrue(LedgerCodec.encodeEpisodes(zurueck).has("earlySupply"))
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
        val ohne = ExpectationAccount.armWithoutExpectation(t0, t0 + 120 * min, false, t0, 110.0, 50.0)
        val zurueck = LedgerCodec.decodeEpisodes(LedgerCodec.encodeEpisodes(episoden(ohne)))
        assertEquals(ohne, zurueck.expectation)
    }

    @Test
    fun `nicht endliche Messwerte vom Marker stehen als null und kommen als NaN zurueck`() {
        val k = konto().copy(lastBgMgdl = Double.NaN, isfAtMarkerMgdlPerU = Double.NaN, disturbanceValid = false)
        val json = LedgerCodec.encodeEpisodes(episoden(k))
        assertTrue(json.getJSONObject("earlySupply").isNull("lastBgMgdl"))
        val zurueck = LedgerCodec.decodeEpisodes(json).expectation
        assertNotNull(zurueck)
        assertTrue(zurueck!!.lastBgMgdl.isNaN())
        assertTrue(zurueck.isfAtMarkerMgdlPerU.isNaN())
    }

    /** Die bewusste Abweichung: verworfen wird das Konto, nicht die Generation (hier ein Konto v1 eines aelteren Standes). */
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
            val json = LedgerCodec.encodeEpisodes(episoden(altKonto()))
            kaputt(json.getJSONObject("expectation"))
            val zurueck = LedgerCodec.decodeEpisodes(json)
            assertNull(zurueck.expectation, "Fall $i")
            assertTrue(zurueck.expectationDropped, "Fall $i")
        }
        // Kein Objekt an der Stelle: ebenso verworfen, Autorisierung unbekannt.
        val json = LedgerCodec.encodeEpisodes(episoden(altKonto())).put("expectation", 42)
        assertTrue(LedgerCodec.decodeEpisodes(json).expectationDropped)
        assertEquals(0L, LedgerCodec.decodeEpisodes(json).expectationDroppedAuthId)
    }

    /** Codex 11.10., Punkt 8: die Autorisierung des verworfenen Kontos bleibt fuer den Rueckfall-Ausweis lesbar. */
    @Test
    fun `das verworfene Konto nennt seine Autorisierung`() {
        val json = LedgerCodec.encodeEpisodes(episoden(konto()))
        json.getJSONObject("earlySupply").put("bookedU", -1.0)
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
        val k = altKonto()
        val json = LedgerCodec.encodeEpisodes(episoden(k))
        val o = json.getJSONObject("expectation")
        assertEquals(0.0, o.getDouble("directDoseU"), 0.0)
        assertEquals(0.0, o.getDouble("coveredByMarkerAuthU"), 0.0)
        assertFalse(o.getBoolean("guardException"))
        o.put("directDoseU", 0.3).put("coveredByMarkerAuthU", 0.1).put("guardException", true)
        val zurueck = LedgerCodec.decodeEpisodes(json)
        assertEquals(k.copy(early = EarlySupply.Pins.NEUTRAL), zurueck.expectation)
        assertFalse(zurueck.expectationDropped)
        // Ungueltig bleibt ungueltig - dieselbe Pruefung wie bisher.
        o.put("directDoseU", -1.0)
        assertTrue(LedgerCodec.decodeEpisodes(json).expectationDropped)
    }

    /** E-034 v2: das Konto der fruehen Versorgung - synthetische Einstellung, eine weitere Portion im Buch. */
    private fun fruehesKonto(): ExpectationAccount.State {
        val p = ExpectationAccount.Params.of(kurve, 120, 90.0, 15, 0.3)!!
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
        // Wieder frei (Toni 22.10.: von selbst, nach dem Tief): das "nach Tief" bleibt.
        val frei = gesperrt.copy(earlyLowLocked = false)
        assertEquals(frei, LedgerCodec.decodeEpisodes(LedgerCodec.encodeEpisodes(episoden(frei))).expectation)
        val f = json.getJSONObject("earlySupply")
        listOf("lowLocked", "lowLockSinceTs", "afterLow").forEach { f.remove(it) }
        val ohne = LedgerCodec.decodeEpisodes(json).expectation!!
        assertEquals(false, ohne.earlyLowLocked)
        assertEquals(false, ohne.earlyAfterLow)
        // Ein v2-Stand mit dem frueheren Ende fuer immer: gesperrt seit dem Marker, bis die Freigabe wieder erreicht ist.
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
        assertFalse(LedgerCodec.encodeEpisodes(episoden(standard)).getJSONObject("earlySupply").has("stabilityFallMgdlPerMin"))
        val abweichend = standard.copy(stabilityFallMgdlPerMin = 1.0)
        val json = LedgerCodec.encodeEpisodes(episoden(abweichend))
        assertTrue(json.getJSONObject("earlySupply").has("stabilityFallMgdlPerMin"))
        assertEquals(abweichend, LedgerCodec.decodeEpisodes(json).expectation)
        // Ueber der Obergrenze (10, Toni: mindestens bis 8 einstellbar) wird das Konto verworfen.
        json.getJSONObject("earlySupply").put("stabilityFallMgdlPerMin", 10.5)
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

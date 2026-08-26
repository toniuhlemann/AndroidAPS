package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.core.controller.UpfrontRecovery
import app.aaps.fuse.core.ledger.LedgerState
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE PERSISTENZ DES RUHE-BEOBACHTUNGSZUSTANDS (Pflichtproben Toni 25.08.).
 *
 * DER ANLASS: `EpisodeBudgets.upfrontRecovery` trug im KDoc das Wort
 * "restartfest", waehrend [LedgerCodec] das Feld nirgends fuehrte - weder in
 * `encodeEpisodes` noch in `encodeFoundation`. Ein Codec-Nachtrag ohne
 * Proben haette denselben Zustand nur eine Ebene weiter verschoben.
 *
 * WAS PERSISTIERT WIRD, und die Grenze ist der Punkt: ausschliesslich die
 * BEOBACHTUNG samt ihren sechs Identitaeten. Niemals ein gefaelltes
 * `CALM_RECOVERED`- oder `FULL_BATCH_ELIGIBLE`-Urteil - dafuer gibt es im
 * [UpfrontRecovery.Track] gar kein Feld, und genau das ist die Zusicherung.
 */
class UpfrontRecoveryPersistTest {

    private val stamp = InterventionStamp("test-epoche", 7L)
    private val marker = 1_700_000_000_000L

    private fun parameter(
        zyklen: Int = 3,
        minUkf: Double = 0.05,
        abstand: Double = 5.0,
        behandlung: UpfrontRecovery.CalmTreatment =
            UpfrontRecovery.CalmTreatment.DEMAND_LIMITED,
        version: Int = 31,
    ) = UpfrontRecovery.Params.of(zyklen, minUkf, abstand, behandlung, version)

    private fun voll(fingerprint: String = parameter().fingerprint) = UpfrontRecovery.Track(
        markerIdentity = marker,
        streak = 2,
        lastAcceptedSourceTs = 1_700_000_060_000L,
        lastEvaluationTs = 1_700_000_065_000L,
        mode = UpfrontRecovery.TrackMode.CALM,
        fingerprint = fingerprint,
    )

    private fun datei(t: UpfrontRecovery.Track): JSONObject =
        LedgerCodec.encode(
            LedgerState(), EpisodeBudgets().also { it.upfrontRecovery = t }, 5L, stamp,
        )

    private fun zurueck(o: JSONObject): UpfrontRecovery.Track =
        LedgerCodec.decodeEpisodes(o.getJSONObject("episodes")).upfrontRecovery

    // ---- PROBE 1: vollstaendiger Round-trip ---------------------------

    @Test
    fun `alle Trackfelder ueberstehen den Round-trip unveraendert`() {
        val vorher = voll()
        val nachher = zurueck(datei(vorher))
        assertEquals(vorher.markerIdentity, nachher.markerIdentity, "markerIdentity")
        assertEquals(vorher.streak, nachher.streak, "streak")
        assertEquals(vorher.lastAcceptedSourceTs, nachher.lastAcceptedSourceTs,
                     "lastAcceptedSourceTs")
        assertEquals(vorher.lastEvaluationTs, nachher.lastEvaluationTs, "lastEvaluationTs")
        assertEquals(vorher.mode, nachher.mode, "mode")
        assertEquals(vorher.fingerprint, nachher.fingerprint, "fingerprint")
        assertTrue(nachher.consistent, "und der geladene Stand ist konsistent")
    }

    /**
     * DER FINGERPRINT MUSS DIE FUENF GROESSEN WIRKLICH TRENNEN - sonst
     * traegt der Round-trip zwar einen String, aber keinen Vertrag.
     */
    @Test
    fun `jede Parameteraenderung erzeugt einen anderen Fingerprint`() {
        val basis = parameter().fingerprint
        assertTrue(basis.isNotEmpty())
        listOf(
            "Zyklen" to parameter(zyklen = 4).fingerprint,
            "minUkf" to parameter(minUkf = 0.06).fingerprint,
            "Abstand" to parameter(abstand = 6.0).fingerprint,
            "Behandlung" to parameter(
                behandlung = UpfrontRecovery.CalmTreatment.SHIFT_TO_DEFERRED,
            ).fingerprint,
            "RuleSet" to parameter(version = 32).fingerprint,
        ).forEach { (was, anderer) ->
            assertTrue(anderer != basis, "$was muss den Fingerprint aendern")
        }
        assertEquals(basis, parameter().fingerprint, "und er ist stabil")
        assertEquals("off", UpfrontRecovery.Params.OFF.fingerprint)
    }

    // ---- PROBE 2: Altdatei ohne Track ---------------------------------

    @Test
    fun `eine Altdatei ohne Trackobjekt ergibt den leeren Track`() {
        val ep = datei(voll()).getJSONObject("episodes")
        assertTrue(ep.has("upfrontRecovery"), "sonst prueft dieser Test nichts")
        ep.remove("upfrontRecovery")
        val nachher = LedgerCodec.decodeEpisodes(ep).upfrontRecovery
        assertEquals(0, nachher.streak, "ein sauberer Neustart bei 0")
        assertEquals("", nachher.fingerprint)
        assertTrue(nachher.consistent)
    }

    // ---- PROBE 3: JSON-null -------------------------------------------

    @Test
    fun `ein JSON-null-Fingerprint wird abgelehnt, nicht als Text gelesen`() {
        // DIE ANDROID-FALLE: dort liefert `optString` fuer ein JSON-null den
        // String "null" statt des Defaults. Ein so gelesener Fingerprint
        // saehe gueltig aus und passte nie zu einem echten - der Zaehler
        // liefe unter falscher Generation weiter, statt zu fallen.
        val ep = datei(voll()).getJSONObject("episodes")
        ep.getJSONObject("upfrontRecovery").put("fingerprint", JSONObject.NULL)
        val nachher = LedgerCodec.decodeEpisodes(ep).upfrontRecovery
        assertSame(UpfrontRecovery.Track.EMPTY, nachher,
                   "ohne Generation ist der Zaehler wertlos")
        assertTrue(nachher.fingerprint != "null", "und ganz sicher nicht der Text null")
    }

    @Test
    fun `ein JSON-null-Modus wird nicht zum Text null`() {
        val ep = datei(voll()).getJSONObject("episodes")
        ep.getJSONObject("upfrontRecovery").put("mode", JSONObject.NULL)
        assertSame(UpfrontRecovery.Track.EMPTY,
                   LedgerCodec.decodeEpisodes(ep).upfrontRecovery)
    }

    // ---- PROBE 4: jede Teilidentitaet fail-closed ---------------------

    @Test
    fun `jede einzelne fehlende Identitaet verwirft den ganzen Zaehler`() {
        val faelle = listOf<Pair<String, (JSONObject) -> Unit>>(
            "markerIdentity" to { t -> t.put("markerIdentity", 0L) },
            "lastAcceptedSourceTs" to { t -> t.put("lastAcceptedSourceTs", 0L) },
            "lastEvaluationTs" to { t -> t.put("lastEvaluationTs", 0L) },
            "mode" to { t -> t.put("mode", UpfrontRecovery.TrackMode.NONE.name) },
            "fingerprint" to { t -> t.put("fingerprint", "") },
            "streak negativ" to { t -> t.put("streak", -1) },
        )
        faelle.forEach { (was, kaputt) ->
            val ep = datei(voll()).getJSONObject("episodes")
            kaputt(ep.getJSONObject("upfrontRecovery"))
            assertSame(UpfrontRecovery.Track.EMPTY,
                       LedgerCodec.decodeEpisodes(ep).upfrontRecovery,
                       "$was entwertet: der ganze Zaehler muss fallen")
        }
    }

    /**
     * STREAK 0 ERLAUBT AUSSCHLIESSLICH DEN VOLLSTAENDIG LEEREN TRACK
     * (Toni 25.08. spaet). Sonst waere eine Identitaet mit Streak 0, aber
     * alten Zeitstempeln oder altem Modus, eine Halbidentitaet in anderer
     * Gestalt - und der naechste Zyklus koennte darauf aufsetzen.
     */
    @Test
    fun `Streak 0 mit alten Zeitstempeln ist keine gueltige Ruhelage`() {
        val ep = datei(voll()).getJSONObject("episodes")
        ep.getJSONObject("upfrontRecovery").put("streak", 0)
        val nachher = LedgerCodec.decodeEpisodes(ep).upfrontRecovery
        assertSame(UpfrontRecovery.Track.EMPTY, nachher)
        assertEquals(0L, nachher.lastEvaluationTs)
        assertEquals(0L, nachher.lastAcceptedSourceTs)
        assertEquals(UpfrontRecovery.TrackMode.NONE, nachher.mode)
        assertEquals("", nachher.fingerprint)
    }

    // ---- PROBE: kein Urteil in der Persistenz --------------------------

    /**
     * Der Track traegt KEIN Urteil - weder `CalmRecovered` noch
     * `FullBatchEligible`. Nach einem Neustart gibt es also gar nichts zu
     * uebernehmen; die Entscheidung entsteht neu.
     */
    @Test
    fun `die Persistenz kennt keine Entscheidung, nur die Beobachtung`() {
        val geschrieben = datei(voll())
            .getJSONObject("episodes").getJSONObject("upfrontRecovery")
        val erlaubt = setOf(
            "markerIdentity", "streak", "lastAcceptedSourceTs",
            "lastEvaluationTs", "mode", "fingerprint",
        )
        assertEquals(erlaubt, geschrieben.keys().asSequence().toSet(),
                     "genau die sechs Beobachtungsfelder - kein Urteil, keine Menge")
    }
}

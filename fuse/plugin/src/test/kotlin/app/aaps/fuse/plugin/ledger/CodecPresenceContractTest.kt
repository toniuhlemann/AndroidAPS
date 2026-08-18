package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.core.ledger.LedgerState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * FEHLEND IST NICHT KAPUTT - der Praesenzvertrag des Codecs (Toni 18.08.).
 *
 * DER BEFUND, DER DAZU GEFUEHRT HAT. `JSONObject.isNull(key)` liefert `true`
 * auch fuer einen FEHLENDEN Schluessel - gemessen, nicht vermutet. Die
 * Nullable-Leser des Codecs konnten "Schluessel weg" damit nicht von
 * "ausdruecklich null" unterscheiden. Der Encoder schreibt den Schluessel
 * aber IMMER (`putNullable` faellt auf `JSONObject.NULL` zurueck), ein
 * fehlender Schluessel kann also gar nicht vom eigenen Schreiber stammen.
 *
 * Dasselbe Muster ein zweites Mal bei `retiredBoundIds`: die Praesenzpflicht
 * dort war wirkungslos gegen kaputten INHALT, weil `has()` auch fuer
 * `null` und fuer jeden Nicht-Array-Wert `true` ist und `optJSONArray`
 * darauf still die leere Menge ergab.
 *
 * WARUM DAS INSULIN KOSTET. Beide Wege enden nicht bei "ungenau", sondern
 * bei "die beschaedigte NEUERE Generation gewinnt": der Decoder meldete
 * keinen Fehler, also galt sie als gueltig, und `readNewestValid` nimmt die
 * mit der hoechsten Revision. Eine intakte aeltere Generation wurde damit
 * verdraengt - samt der Haftung, die sie trug.
 *
 * DER VERTRAG IST FELD- UND VERSIONSBEZOGEN, nicht global: aeltere
 * Schemastaende duerfen Felder tatsaechlich noch nicht besitzen.
 */
class CodecPresenceContractTest {

    private val stamp = InterventionStamp("test-epoche", 7L)

    private fun datei(revision: Long = 5L): JSONObject =
        LedgerCodec.encode(LedgerState(), EpisodeBudgets(), revision, stamp)

    // ---- Immer geschriebene Nullable-Felder --------------------------------

    /**
     * Diese beiden schreibt der Zustands-Encoder auch bei einem LEEREN Ledger
     * - sie sind damit der einfachste Nachweis des Vertrags.
     */
    @Test
    fun `ein fehlender Schluessel eines immer geschriebenen Feldes wirft`() {
        for (feld in listOf("lastSnapshotViewHash", "announcedEpochId", "lastSnapshotOrder")) {
            val o = datei()
            assertTrue(
                o.getJSONObject("state").has(feld),
                "$feld - der Encoder MUSS den Schluessel schreiben, sonst prueft dieser Test nichts",
            )
            o.getJSONObject("state").remove(feld)
            assertThrows(
                IllegalArgumentException::class.java,
                { LedgerCodec.decode(JSONObject(o.toString())) },
                "$feld fehlt - das kann keine selbstgeschriebene Datei sein",
            )
        }
    }

    /**
     * UND DIE GEGENPROBE: ein AUSDRUECKLICHES null bleibt ein gueltiger Wert.
     *
     * Ohne sie waere der Vertrag mit einem pauschalen `getString()` erfuellbar
     * - und der wuerde jede Datei abweisen, in der eines dieser Felder
     * berechtigterweise leer ist. Das ist der Normalfall, nicht der Randfall.
     */
    @Test
    fun `ein ausdrueckliches null bleibt ein gueltiger Wert`() {
        val o = datei()
        o.getJSONObject("state").put("lastSnapshotViewHash", JSONObject.NULL)
        o.getJSONObject("state").put("announcedEpochId", JSONObject.NULL)
        val gelesen = LedgerCodec.decode(JSONObject(o.toString()))
        assertNull(gelesen.state.lastSnapshotViewHash)
        assertNull(gelesen.state.announcedEpochId)
    }

    /**
     * v1 BLEIBT NACHSICHTIG - das ist der Altbestand unbekannter Herkunft.
     *
     * Die Praesenzpflicht haengt an [LedgerCodec.STRICT_VERSION], weil 26 der
     * 27 Nullable-Felder schon vor dem v2-Commit im Encoder standen. Fuer eine
     * v1-Datei kann "Feld fehlt" dagegen eine ehrliche Aussage sein, und sie
     * abzuweisen hiesse, echten Altbestand in den Hold zu schicken.
     */
    @Test
    fun `unter dem Legacy-Schema ist ein fehlendes Feld zulaessig`() {
        val o = datei()
        o.put("v", LedgerCodec.LEGACY_VERSION)
        o.getJSONObject("state").remove("announcedEpochId")
        val gelesen = runCatching { LedgerCodec.decode(JSONObject(o.toString())) }.getOrNull()
        assertNotNull(gelesen, "eine v1-Datei ohne das Feld MUSS ladbar bleiben")
        assertNull(gelesen!!.state.announcedEpochId)
    }

    // ---- retiredBoundIds ---------------------------------------------------

    /**
     * AB v3 ZWINGEND EIN ARRAY. Fehlend, JSON-null und falscher Typ sind drei
     * Wege zu derselben stillen leeren Menge - und die leere Menge ist hier
     * die gefaehrliche Deutung: eine verbrauchte Bindungsidentitaet duerfte
     * wieder binden, also koennte ein bereits verbuchter fremder Bolus eine
     * offene Zeile schliessen, ohne dass je Insulin nachgewiesen wurde.
     */
    @Test
    fun `retiredBoundIds ab v3 muss ein Array sein`() {
        val kaputt = listOf<Pair<String, (JSONObject) -> Unit>>(
            "fehlend" to { o -> o.remove("retiredBoundIds") },
            "JSON-null" to { o -> o.put("retiredBoundIds", JSONObject.NULL) },
            "Objekt statt Array" to { o -> o.put("retiredBoundIds", JSONObject()) },
            "Zahl statt Array" to { o -> o.put("retiredBoundIds", 42) },
            "String statt Array" to { o -> o.put("retiredBoundIds", "[]") },
        )
        for ((name, brich) in kaputt) {
            val o = datei()
            brich(o)
            assertThrows(
                IllegalArgumentException::class.java,
                { LedgerCodec.decode(JSONObject(o.toString())) },
                "$name - MUSS die Generation verwerfen",
            )
        }
    }

    /** Ein leeres Array bleibt der gueltige Normalfall. */
    @Test
    fun `ein leeres retiredBoundIds-Array ist gueltig`() {
        val o = datei()
        o.put("retiredBoundIds", JSONArray())
        assertTrue(LedgerCodec.decode(JSONObject(o.toString())).retiredBoundIds.isEmpty())
    }

    // ---- Die Pflichtprobe: wer gewinnt? ------------------------------------

    /**
     * EINE BESCHAEDIGTE NEUESTE GENERATION DARF NICHT GEWINNEN (Tonis
     * Pflichtprobe).
     *
     * Das ist der Punkt, an dem die beiden Fixes ihren Zweck erfuellen - und
     * der Test, der VOR ihnen fehlgeschlagen waere: `readNewestValid` waehlt
     * nach Revision und ueberspringt nur, was `validate` als ungueltig meldet.
     * Solange der Codec bei kaputtem Inhalt schwieg, war die beschaedigte
     * .tmp mit der hoeheren Revision schlicht der Sieger.
     *
     * Geprueft wird der ganze Weg: Store waehlt, Codec urteilt.
     */
    @Test
    fun `eine beschaedigte neueste Generation verliert gegen eine intakte aeltere`(@TempDir dir: File) {
        val gueltig = datei(revision = 5L).toString()
        val beschaedigt = datei(revision = 9L).also { it.put("retiredBoundIds", JSONObject()) }.toString()
        File(dir, FuseLedgerStore.FILE_NAME).writeText(gueltig, Charsets.UTF_8)
        File(dir, FuseLedgerStore.FILE_NAME + ".tmp").writeText(beschaedigt, Charsets.UTF_8)

        val gelesen = FuseLedgerStore().readNewestValid(dir) { text ->
            runCatching { LedgerCodec.decode(JSONObject(text)).revision }.getOrNull()
        }

        assertNotNull(gelesen.content, "die intakte aeltere Generation MUSS uebrig bleiben")
        assertEquals(
            5L, LedgerCodec.decode(JSONObject(gelesen.content!!)).revision,
            "die kaputte Revision 9 darf die intakte 5 nicht verdraengen",
        )
        assertTrue(
            gelesen.anyCandidateInvalid,
            "und der Schaden MUSS gemeldet werden - sonst laeuft er unbemerkt weiter",
        )
    }

    /**
     * IST KEINE GUELTIGE GENERATION UEBRIG, entsteht kein Notbehelf.
     *
     * Der Store meldet dann "nichts Lesbares, aber es gab Kandidaten" - genau
     * die Kombination, aus der der Aufrufer den Hold baut. Ein stiller
     * Leerzustand waere hier das Schlimmste: er saehe aus wie ein Erststart
     * und liesse die gesamte offene Haftung verschwinden.
     */
    @Test
    fun `sind alle Generationen beschaedigt, bleibt nichts uebrig`(@TempDir dir: File) {
        for (name in listOf(FuseLedgerStore.FILE_NAME, FuseLedgerStore.FILE_NAME + ".tmp")) {
            val o = datei(revision = 3L)
            o.put("retiredBoundIds", JSONObject.NULL)
            File(dir, name).writeText(o.toString(), Charsets.UTF_8)
        }

        val gelesen = FuseLedgerStore().readNewestValid(dir) { text ->
            runCatching { LedgerCodec.decode(JSONObject(text)).revision }.getOrNull()
        }

        assertNull(gelesen.content, "kein Notbehelf")
        assertTrue(gelesen.anyCandidateExisted, "es gab Kandidaten - das ist KEIN Erststart")
        assertTrue(gelesen.anyCandidateInvalid, "und sie waren beschaedigt")
    }
}

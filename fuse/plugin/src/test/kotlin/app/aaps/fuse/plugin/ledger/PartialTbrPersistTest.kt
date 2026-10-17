package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.PartialTbrOwnership
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER TEIL-TBR-BESITZ UEBER EINEN NEUSTART - ueber den ECHTEN Codec.
 *
 * WARUM EIGENS (Review): der bisherige "Neustarttest" verglich nur
 * `state` mit `state.copy()`. Das beweist, dass eine Data-Klasse sich
 * kopieren laesst - nicht, dass der Zustand eine Datei ueberlebt. Und
 * genau daran haengt alles: geht der Besitz beim Prozessstart verloren,
 * ist die eigene laufende Absenkung danach "fremd", laeuft bis zum
 * Ablauf weiter und der SMB steht offen, waehrend FUSE die normale
 * Freigabe meldet.
 *
 * Geprueft wird deshalb `encodeEpisodes` -> JSON -> `decodeEpisodes`,
 * also derselbe Weg, den der Ledger auf der Platte geht.
 */
class PartialTbrPersistTest {

    private val t0 = 1_700_000_000_000L

    private fun rund(s: PartialTbrOwnership.State): PartialTbrOwnership.State {
        val e = EpisodeBudgets().also { it.ownPartialTbr = s }
        val json = LedgerCodec.encodeEpisodes(e)
        // Ueber die TEXTFORM, nicht ueber dasselbe Objekt - sonst waere es
        // wieder nur eine Kopie.
        return LedgerCodec.decodeEpisodes(org.json.JSONObject(json.toString())).ownPartialTbr
    }

    @Test
    fun `eine bestaetigte laufende Rate ueberlebt den Neustart`() {
        val s = PartialTbrOwnership.State(
            confirmedRunning = PartialTbrOwnership.Identity(0.30, t0, 30),
        )
        val nach = rund(s)
        assertEquals(s, nach)
        assertNotNull(nach.confirmedRunning)
        assertTrue(nach.smbBlocked) { "und der Kanal bleibt danach zu" }
    }

    @Test
    fun `bestaetigte Rate UND offene Anforderung ueberleben getrennt`() {
        // Genau die Lage des gemeldeten P0: 0,85 laeuft, 1,00 ist offen.
        val s = PartialTbrOwnership.State(
            confirmedRunning = PartialTbrOwnership.Identity(0.85, t0, 30),
            pendingRequest = PartialTbrOwnership.Identity(1.00, t0 + 600_000L, 30),
            pendingAttempts = 2,
        )
        val nach = rund(s)
        assertEquals(s, nach)
        assertEquals(0.85, nach.confirmedRunning!!.rateUPerH, 1e-12)
        assertEquals(1.00, nach.pendingRequest!!.rateUPerH, 1e-12)
        assertEquals(2, nach.pendingAttempts) {
            "der Versuchszaehler muss mit - sonst beginnt der Deckel nach jedem Neustart neu"
        }
    }

    @Test
    fun `der Abbruchzustand ueberlebt samt Versuchen und Backoff-Uhr`() {
        val s = PartialTbrOwnership.State(
            confirmedRunning = PartialTbrOwnership.Identity(0.30, t0, 30),
            ending = PartialTbrOwnership.Ending(sinceTs = t0 + 300_000L, attempts = 2, lastRequestTs = t0 + 480_000L),
        )
        val nach = rund(s)
        assertEquals(s, nach)
        assertEquals(2, nach.ending!!.attempts) { "sonst faengt der Abbruch nach jedem Neustart von vorn an" }
        assertEquals(t0 + 480_000L, nach.ending!!.lastRequestTs) { "und der Backoff waere wirkungslos" }
    }

    @Test
    fun `ein leerer Zustand bleibt leer und schreibt nichts`() {
        val leer = PartialTbrOwnership.State()
        assertEquals(leer, rund(leer))
        val json = LedgerCodec.encodeEpisodes(EpisodeBudgets().also { it.ownPartialTbr = leer })
        assertTrue(json.isNull("ownPartialTbr")) { "ein leerer Besitz erzeugt keinen Eintrag" }
    }

    @Test
    fun `eine Altdatei ohne das Feld ergibt einen leeren Besitz`() {
        // Rueckwaertskompatibilitaet: ein Trail von vor dieser Aenderung
        // darf nicht als "es laeuft etwas von uns" gelesen werden.
        val ohne = LedgerCodec.encodeEpisodes(EpisodeBudgets()).also { it.remove("ownPartialTbr") }
        val nach = LedgerCodec.decodeEpisodes(org.json.JSONObject(ohne.toString())).ownPartialTbr
        assertTrue(nach.leer)
        assertNull(nach.ending)
    }

    @Test
    fun `ein unbrauchbarer Eintrag wird verworfen, nicht repariert`() {
        // Rate 0 ist keine gueltige Teilrate. Fail-closed heisst hier:
        // der Eintrag verfaellt, die laufende Absenkung gilt als FREMD -
        // und Fremdes wird nie angefasst.
        val s = PartialTbrOwnership.State(
            confirmedRunning = PartialTbrOwnership.Identity(0.30, t0, 30),
            pendingRequest = PartialTbrOwnership.Identity(0.0, t0, 30),
        )
        val json = LedgerCodec.encodeEpisodes(EpisodeBudgets().also { it.ownPartialTbr = s })
        val nach = LedgerCodec.decodeEpisodes(org.json.JSONObject(json.toString())).ownPartialTbr
        assertNotNull(nach.confirmedRunning)
        assertNull(nach.pendingRequest) { "die unbrauchbare Kennung faellt weg" }
    }
}

package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.core.util.Sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * DER AUFNAHMENACHWEIS FUER UNGEBUCHTE POSTEN (03.10.2026, Codex-Pruefung des
 * Entwurfs zur Transport-Doppelzaehlung).
 *
 * Drei Ebenen, getrennt geprueft:
 * - ZUORDNUNG ([FuseLedgerAdapter.vorlaeufigeZuordnung]): genau die spaetere
 *   Bindung, Einmalvergabe ueber alle Zeilen und beide Kennungen (P2-1).
 * - AUFNAHME ([TransportAufnahme.pruefe]): der Bolus steht in Lesung 2
 *   unveraendert, jeder cachefaehige Wert ist nachgerechnet (P1).
 * - MENGE ([TransportInclusion.modelledU]): nie mehr entlasten als belegt, ein
 *   Rest bleibt, Fehler, Hold und settledZero gehen vor (P2-2).
 *
 * Die Verdrahtung im Runner (Lesung 1, IOB-Abfragen, Lesung 2, Transport)
 * pruefen die Faelle "Aufnahmenachweis" in TransportWiringTest. Alle Mengen
 * synthetisch.
 */
class TransportAufnahmeTest {

    private val t0 = 1_700_000_000_000L

    private fun smb(dbId: Long, ts: Long, u: Double, pumpId: Long? = null, tempId: Long? = null, version: Int = 0) = BS(
        id = dbId, version = version, timestamp = ts, amount = u, type = BS.Type.SMB,
        ids = IDs(pumpType = PumpType.GENERIC_AAPS, pumpSerial = "vs", pumpId = pumpId, temporaryId = tempId),
    )

    private fun FuseLedgerAdapter.publishVs(id: String, u: Double, ts: Long, latest: Long = 0L) =
        onPublished(id, u, ts, latest, 0.05, PumpType.GENERIC_AAPS.name, Sha.of("vs"))

    private fun adapter(dir: File) = FuseLedgerAdapter().also { it.loadOnce(dir, "epoch-a", t0) }

    // ---- ZUORDNUNG (P2-1) ----------------------------------------------------

    @Test
    fun `die vorlaeufige Zuordnung ist genau die spaetere Bindung`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("p1", 0.30, t0)
        a.publishVs("p2", 0.20, t0 + 60_000L)
        a.publishVs("p3", 0.10, t0 + 120_000L)
        val boli = listOf(
            smb(11, t0 + 20_000L, 0.30, pumpId = 501),                 // nur Pumpenkennung
            smb(12, t0 + 80_000L, 0.20, tempId = 9001),                // nur temporaere Kennung
            smb(13, t0 + 140_000L, 0.10, pumpId = 503, tempId = 9003), // beide
        )
        val vorher = a.openTransportItems()
        val haftungVorher = a.view().transportCommitmentU

        val zuordnung = a.vorlaeufigeZuordnung(boli)

        // Rein lesend: nichts gebunden, nichts gebucht.
        assertEquals(vorher, a.openTransportItems())
        assertEquals(haftungVorher, a.view().transportCommitmentU, 0.0)
        assertEquals(mapOf("p1" to 11L, "p2" to 12L, "p3" to 13L), zuordnung.mapValues { it.value.id })

        // Die Bindung nimmt GENAU diese Paare.
        a.bindIdentities(boli)
        val gebunden = a.openTransportItems().associateBy { it.proposalId }
        for ((id, b) in zuordnung) {
            assertEquals(b.ids.pumpId, gebunden.getValue(id).pumpId, id)
            assertEquals(b.ids.temporaryId, gebunden.getValue(id).temporaryId, id)
        }
        // Gebundene Zeilen sind keine Kandidaten mehr.
        assertTrue(a.vorlaeufigeZuordnung(boli).isEmpty())
    }

    @Test
    fun `ein Fakt geht hoechstens an einen Posten`(@TempDir dir: File) {
        val a = adapter(dir)
        // Gleiche Entscheidungszeit, gleiche Menge: beide Fenster decken den Fakt.
        a.publishVs("p1", 0.30, t0)
        a.publishVs("p2", 0.30, t0)
        val z = a.vorlaeufigeZuordnung(listOf(smb(21, t0 + 30_000L, 0.30, pumpId = 601, tempId = 9101)))
        assertEquals(1, z.size, "ein Fakt, ein Posten: $z")
    }

    @Test
    fun `eine Lieferung in zwei Zeilen mit verschiedenen Kennungen wird nicht geraten`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("p1", 0.30, t0)
        val boli = listOf(
            smb(51, t0 + 20_000L, 0.30, tempId = 9201),
            smb(52, t0 + 21_000L, 0.30, pumpId = 801),
        )
        assertTrue(a.vorlaeufigeZuordnung(boli).isEmpty())
        a.bindIdentities(boli)
        assertNull(a.openTransportItems().single().pumpId, "auch die Bindung raet nicht")
    }

    @Test
    fun `eine schon gebundene Kennung bleibt ausgeschlossen`(@TempDir dir: File) {
        val a = adapter(dir)
        a.publishVs("p0", 0.30, t0)
        val b0 = smb(41, t0 + 10_000L, 0.30, pumpId = 701)
        a.bindIdentities(listOf(b0))
        a.publishVs("p1", 0.30, t0 + 60_000L)
        // Dieselbe Pumpenkennung, neu datiert (eine Sync-Aenderung):
        val b1 = smb(42, t0 + 70_000L, 0.30, pumpId = 701)
        assertTrue(a.vorlaeufigeZuordnung(listOf(b0, b1)).isEmpty())
    }

    // ---- AUFNAHME (P1) ---------------------------------------------------------

    private fun posten(
        id: String, offenU: Double, fehlerfrei: Boolean = true, gebuchtU: Double = 0.0,
        pumpId: Long? = null, tempId: Long? = null,
    ) = OpenTransportItem(
        proposalId = id, commitmentU = offenU, grossLiabilityU = offenU + gebuchtU, accountedAmountU = gebuchtU,
        bestKnownTs = t0, temporaryId = tempId, pumpId = pumpId, settledZero = false, fehlerfrei = fehlerfrei,
    )

    private val frisch = listOf(TransportAufnahme.BenutzterWert(t0 + 60_000L, 0.30, ausCacheMoeglich = false))
    private val b = smb(11, t0 + 20_000L, 0.30, pumpId = 501, tempId = 9001)

    private fun pruefe(
        lesung2: List<BS>?,
        kandidaten: Map<String, BS> = mapOf("p1" to b),
        posten: List<OpenTransportItem> = listOf(posten("p1", 0.30)),
        werte: List<TransportAufnahme.BenutzterWert> = frisch,
        nachrechnen: (Long) -> Double? = { null },
    ) = TransportAufnahme.pruefe(kandidaten, posten, lesung2, werte, nachrechnen)

    @Test
    fun `unveraendert in Lesung 2 und frisch gerechnet - die Menge ist belegt`() {
        val e = pruefe(listOf(b.copy()))
        assertEquals(mapOf("p1" to 0.30), e.aufgenommenU)
        assertNull(e.grund)
        assertEquals(0.30, e.belegtU, 1e-12)
    }

    @Test
    fun `ungueltig geworden - die DAO liefert ihn nicht mehr, nichts belegt`() {
        val e = pruefe(emptyList())
        assertTrue(e.aufgenommenU.isEmpty())
        assertEquals("VERAENDERT", e.grund)
        // Auch eine Lesung, die Ungueltige mitliefert, belegt nichts.
        assertTrue(pruefe(listOf(b.copy(isValid = false, version = 1))).aufgenommenU.isEmpty())
    }

    @Test
    fun `Menge, Zeit, Typ, Kennung oder Version geaendert - nichts belegt`() {
        val geaendert = listOf(
            b.copy(amount = 0.25, version = 1),
            b.copy(timestamp = b.timestamp + 20_000L, version = 1),
            b.copy(type = BS.Type.NORMAL, version = 1),
            b.copy(ids = b.ids.copy(pumpId = 502), version = 1),
            b.copy(ids = b.ids.copy(temporaryId = null), version = 1),
            // Nur die Version: eine Aenderung, die von hier nicht zu sehen ist.
            b.copy(version = 1),
            // Und ohne Versionssprung trotzdem eine andere Menge (Verteidigung).
            b.copy(amount = 0.25),
        )
        geaendert.forEach { b2 ->
            val e = pruefe(listOf(b2))
            assertTrue(e.aufgenommenU.isEmpty()) { "belegt trotz Aenderung: $b2" }
            assertEquals("VERAENDERT", e.grund)
        }
    }

    @Test
    fun `ein cachefaehiger Wert wird nachgerechnet - stimmt er, ist die Menge belegt`() {
        val werte = listOf(TransportAufnahme.BenutzterWert(t0 + 60_000L, 0.297, ausCacheMoeglich = true))
        assertEquals(mapOf("p1" to 0.30), pruefe(listOf(b), werte = werte, nachrechnen = { 0.297 }).aufgenommenU)
        // Rundung von AAPS (je 0,001 fuer Bolus- und Basalanteil) ist kein Befund.
        assertEquals(mapOf("p1" to 0.30), pruefe(listOf(b), werte = werte, nachrechnen = { 0.2985 }).aufgenommenU)
    }

    @Test
    fun `ein aelterer Cacheeintrag ohne den Bolus - nichts belegt, auch fuer andere Posten`() {
        val b2 = smb(12, t0 + 80_000L, 0.20, tempId = 9002)
        val werte = listOf(
            TransportAufnahme.BenutzterWert(t0 + 60_000L, 0.0, ausCacheMoeglich = true), // ohne den Bolus
            TransportAufnahme.BenutzterWert(t0 + 120_000L, 0.49, ausCacheMoeglich = false),
        )
        val e = pruefe(
            listOf(b, b2), kandidaten = mapOf("p1" to b, "p2" to b2),
            posten = listOf(posten("p1", 0.30), posten("p2", 0.20)),
            werte = werte, nachrechnen = { 0.297 },
        )
        assertTrue(e.aufgenommenU.isEmpty())
        assertEquals("CACHE_ABWEICHUNG", e.grund)
        assertEquals(2, e.kandidaten)
    }

    @Test
    fun `ohne Nachrechnung oder mit unendlichem Wert - nichts belegt`() {
        val werte = listOf(TransportAufnahme.BenutzterWert(t0 + 60_000L, 0.297, ausCacheMoeglich = true))
        assertEquals("NACHRECHNUNG_UNMOEGLICH", pruefe(listOf(b), werte = werte, nachrechnen = { null }).grund)
        val nan = listOf(TransportAufnahme.BenutzterWert(t0 + 60_000L, Double.NaN, ausCacheMoeglich = true))
        val e = pruefe(listOf(b), werte = nan, nachrechnen = { 0.297 })
        assertTrue(e.aufgenommenU.isEmpty())
        assertEquals("CACHE_ABWEICHUNG", e.grund)
    }

    @Test
    fun `ohne Lesung 2 - nichts belegt`() {
        val e = pruefe(null)
        assertTrue(e.aufgenommenU.isEmpty())
        assertEquals("LESUNG2_FEHLT", e.grund)
    }

    @Test
    fun `nur fehlerfreie, ungebuchte Posten ohne Identitaet mit offener Menge kommen in Frage`() {
        val ungeeignet = listOf(
            posten("p1", 0.30, fehlerfrei = false),
            posten("p1", 0.0, gebuchtU = 0.30),
            posten("p1", 0.30, pumpId = 501),
            posten("p1", 0.30, tempId = 9001),
            posten("p1", 0.0),
        )
        ungeeignet.forEach { p ->
            val e = pruefe(listOf(b), posten = listOf(p))
            assertTrue(e.aufgenommenU.isEmpty()) { "belegt fuer $p" }
            assertEquals("NICHT_GEEIGNET", e.grund)
        }
        // Ein Datensatz ohne Kennung (id 0) ist nie ein Beleg.
        val ohneId = b.copy(id = 0)
        assertTrue(pruefe(listOf(ohneId), kandidaten = mapOf("p1" to ohneId)).aufgenommenU.isEmpty())
    }

    @Test
    fun `ein Datensatz oder eine Kennung fuer zwei Posten - keiner wird entlastet`() {
        val zweiPosten = listOf(posten("p1", 0.30), posten("p2", 0.30))
        val gleich = pruefe(listOf(b), kandidaten = mapOf("p1" to b, "p2" to b), posten = zweiPosten)
        assertTrue(gleich.aufgenommenU.isEmpty())
        assertEquals("MEHRFACH", gleich.grund)
        // Zwei Zeilen, dieselbe Pumpenkennung.
        val alias = smb(12, t0 + 21_000L, 0.30, pumpId = 501)
        val e = pruefe(listOf(b, alias), kandidaten = mapOf("p1" to b, "p2" to alias), posten = zweiPosten)
        assertTrue(e.aufgenommenU.isEmpty())
        assertEquals("MEHRFACH", e.grund)
    }

    @Test
    fun `belegt wird je Posten - ein veraenderter haelt die anderen nicht auf`() {
        val b2 = smb(12, t0 + 80_000L, 0.20, tempId = 9002)
        val e = pruefe(
            listOf(b, b2.copy(amount = 0.15, version = 1)),
            kandidaten = mapOf("p1" to b, "p2" to b2),
            posten = listOf(posten("p1", 0.30), posten("p2", 0.20)),
        )
        assertEquals(mapOf("p1" to 0.30), e.aufgenommenU)
        assertEquals("VERAENDERT", e.grund)
    }

    @Test
    fun `ohne Kandidaten ist das Ergebnis leer`() {
        assertEquals(TransportAufnahme.Ergebnis.LEER, pruefe(listOf(b), kandidaten = emptyMap()))
    }

    // ---- MENGE (P2-2) ------------------------------------------------------------

    @Test
    fun `entlastet wird hoechstens die belegte Menge - ein Rest bleibt Transport`() {
        assertEquals(0.0, TransportInclusion.modelledU(posten("p1", 0.30), null, 0.30), 1e-12)
        // Die Haftung ist groesser als die belegte Lieferung: der Rest bleibt.
        assertEquals(0.05, TransportInclusion.modelledU(posten("p1", 0.35), null, 0.30), 1e-12)
        // Nie unter null.
        assertEquals(0.0, TransportInclusion.modelledU(posten("p1", 0.20), null, 0.30), 1e-12)
    }

    @Test
    fun `ohne Beleg, mit Fehler, gebucht oder gebunden - die bisherige Regel`() {
        assertEquals(0.30, TransportInclusion.modelledU(posten("p1", 0.30), null, null), 1e-12)
        assertEquals(0.30, TransportInclusion.modelledU(posten("p1", 0.30), null, 0.0), 1e-12)
        assertEquals(0.30, TransportInclusion.modelledU(posten("p1", 0.30, fehlerfrei = false), null, 0.30), 1e-12)
        assertEquals(0.30, TransportInclusion.modelledU(posten("p1", 0.30, pumpId = 501), null, 0.30), 1e-12)
        // Gebucht, Zeuge fehlt: C3-02 bleibt bei der vollen Menge.
        val gebucht = posten("p1", 0.0, gebuchtU = 0.30, pumpId = 501)
        assertEquals(0.30, TransportInclusion.modelledU(gebucht, null, 0.30), 1e-12)
    }

    @Test
    fun `settledZero geht vor`() {
        val p = posten("p1", 0.30).copy(settledZero = true)
        assertEquals(0.0, TransportInclusion.modelledU(p, null, 0.10), 0.0)
    }
}

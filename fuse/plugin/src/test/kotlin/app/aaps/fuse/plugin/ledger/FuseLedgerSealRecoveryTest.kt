package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.plugin.FuseActivePump
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * WIEDERHERSTELLUNG NACH UNTERBROCHENEM SPEICHERN - die Nachweise.
 *
 * Absturzlagen werden als Dateizustand nachgestellt, genau nach dem
 * Speicherprotokoll: Marker -> .tmp + fsync -> target->.bak -> .tmp->target.
 * P = zuletzt sauber versiegelter Stand, S = der unterbrochene Stand. S und P
 * tragen in den Kernfaellen DIESELBE Revision und unterscheiden sich nur in einer
 * Episode (Wiederanlaufsperre) - der Pflichtgegenfall.
 */
class FuseLedgerSealRecoveryTest {

    private val t0 = 1_700_000_000_000L
    private val medtrum = PumpType.MEDTRUM_NANO
    private val echt = FuseActivePump(medtrum.name, virtualPump = false)
    private val sperreP = t0 + 5 * 60_000L
    private val sperreS = t0 + 20 * 60_000L

    private val target = FuseLedgerStore.FILE_NAME
    private val tmp = "${FuseLedgerStore.FILE_NAME}.tmp"
    private val bak = "${FuseLedgerStore.FILE_NAME}.bak"

    private fun inhalt(a: FuseLedgerAdapter) =
        LedgerCodec.encode(a.state, a.episodes, a.revision, a.interventionStamp, a.retiredBoundIds.toList(), a.proposalPumpEpochs.toMap()).toString()

    /** Legt P sauber versiegelt an (eine offene Zeile) und liefert P- und S-Inhalt (gleiche Revision, andere Sperre). */
    private fun lage(dir: File): Pair<String, String> {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "r-a", t0, echt)
            it.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        }
        a.onPublished("p1", 0.30, t0, 0L, 0.05, medtrum.name, LedgerFacts.serialHashOf("abc", medtrum.name), virtualPump = true)
        a.episodes.livenessReArmUntilTs = sperreP
        assertTrue(a.persistVerified(dir))
        val p = File(dir, target).readText()
        a.episodes.livenessReArmUntilTs = sperreS
        val s = inhalt(a)
        assertEquals(LedgerCodec.decode(org.json.JSONObject(p)).revision, LedgerCodec.decode(org.json.JSONObject(s)).revision, "Vorbedingung: gleiche Revision")
        assertTrue(p != s, "Vorbedingung: anderer Inhalt")
        return p to s
    }

    private fun rev(content: String) = LedgerCodec.decode(org.json.JSONObject(content)).revision
    private fun markerV2(dir: File, s: String) = assertTrue(FuseLedgerStore().markSealPending(dir, FuseLedgerStore.sealMarkerContent("t1", rev(s), s)))
    private fun markerAlt(dir: File, s: String) = assertTrue(FuseLedgerStore().markSealPending(dir, "SEAL_PENDING rev=${rev(s)}"))

    /** Absturz nach fsync von .tmp, vor der Rotation. */
    private fun absturzNachTmp(dir: File, s: String) = File(dir, tmp).writeText(s)

    /** Absturz nach tmp->target: target = S, .bak = P, keine .tmp. */
    private fun absturzNachUmbenennen(dir: File, p: String, s: String) {
        File(dir, bak).writeText(p)
        File(dir, target).writeText(s)
    }

    private fun geladen(dir: File) = FuseLedgerAdapter().also { it.loadOnce(dir, "r-b", t0 + 60_000L, echt) }

    private fun schnappschuss(dir: File) = dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readText() }

    /**
     * KONTROLLE: derselbe Stand S nach einem UNGESTOERTEN Neustart (sauber
     * versiegelt, kein Marker). Jeder Start bucht beim Laden eigene Ereignisse
     * (Revision, Epochenabgleich) - verglichen wird deshalb mit dieser Kontrolle,
     * nicht mit dem rohen Dateiinhalt.
     */
    private fun kontrollLadung(s: String): FuseLedgerAdapter {
        val k = kotlin.io.path.createTempDirectory("kontrolle").toFile()
        File(k, target).writeText(s)
        assertTrue(FuseLedgerStore.writeSentinel(k))
        return geladen(k)
    }

    private fun pruefeWiederhergestellt(dir: File, s: String) {
        assertFalse(FuseLedgerStore.sealPendingExists(dir))
        assertFalse(FuseLedgerStore.recoveryPendingExists(dir))
        val gewaehlt = FuseLedgerStore().readNewestValid(dir) { runCatching { LedgerCodec.decode(org.json.JSONObject(it)).revision }.getOrNull() }
        assertEquals(FuseLedgerStore.sha256(s), gewaehlt.content?.let { FuseLedgerStore.sha256(it) }, "die Generationswahl trifft exakt den unterbrochenen Stand")
        val a = geladen(dir)
        val k = kontrollLadung(s)
        assertFalse(a.recoveryHold, "nach der Wiederherstellung kein Recovery-Hold")
        assertEquals(sperreS, a.episodes.livenessReArmUntilTs, "der UNTERBROCHENE Stand ist uebernommen, nicht der aeltere")
        assertEquals(k.revision, a.revision, "Revision wie nach ungestoertem Neustart")
        assertEquals(k.state, a.state, "Buchungsstand wie nach ungestoertem Neustart")
        assertEquals(k.retiredBoundIds.toList(), a.retiredBoundIds.toList(), "Genau-einmal-Riegel wie nach ungestoertem Neustart")
        assertTrue(a.hasOpenProposal("p1"), "die offene Buchung bleibt erhalten")
        assertTrue(a.persistVerified(dir), "danach wird wieder normal gespeichert")
    }

    // ---- Nachweise --------------------------------------------------------

    @Test
    fun `v2 - tmp mit Pruefsumme wird uebernommen, nichts verworfen`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        markerV2(dir, s)
        absturzNachTmp(dir, s)
        val lage = FuseLedgerSealRecovery.inspect(dir)
        assertTrue(lage.recoverable, lage.why)
        assertEquals(tmp, lage.proof!!.sourceName)
        assertEquals(1, lage.openEntries)
        assertTrue(geladen(dir).recoveryHold, "vor der Wiederherstellung haelt der Ledger")
        val r = FuseLedgerSealRecovery.perform(dir, t0 + 120_000L, "test", "unterbrochenes Speichern")
        assertTrue(r is FuseLedgerSealRecovery.Result.Done, "$r")
        pruefeWiederhergestellt(dir, s)
        assertNotNull(FuseLedgerSealRecovery.lastRecovery(dir))
        assertTrue(dir.listFiles()!!.any { it.name.contains(FuseLedgerSealRecovery.EVIDENCE_SUFFIX) }, "die Belegkopie bleibt liegen")
    }

    @Test
    fun `v2 - bereits umbenannter Zielname mit Pruefsumme wird uebernommen`(@TempDir dir: File) {
        val (p, s) = lage(dir)
        markerV2(dir, s)
        absturzNachUmbenennen(dir, p, s)
        val lage = FuseLedgerSealRecovery.inspect(dir)
        assertTrue(lage.recoverable, lage.why)
        assertEquals(target, lage.proof!!.sourceName)
        assertTrue(FuseLedgerSealRecovery.perform(dir, t0 + 120_000L, "test", "grund") is FuseLedgerSealRecovery.Result.Done)
        pruefeWiederhergestellt(dir, s)
    }

    // ---- Pflichtgegenfall: gleiche Revision, anderer Inhalt --------------

    @Test
    fun `gleiche Revision, andere Episode - v2 ohne passenden Inhalt bleibt gehalten`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        markerV2(dir, s)   // Absturz vor .tmp: target = P, gleiche Revision
        val vorher = schnappschuss(dir)
        val lage = FuseLedgerSealRecovery.inspect(dir)
        assertFalse(lage.recoverable, lage.why)
        assertTrue(FuseLedgerSealRecovery.perform(dir, t0, "test", "grund") is FuseLedgerSealRecovery.Result.Refused)
        assertEquals(vorher, schnappschuss(dir), "nichts veraendert")
        assertTrue(geladen(dir).recoveryHold)
    }

    @Test
    fun `gleiche Revision, andere Episode - Altmarker ohne tmp bleibt gehalten, auch wenn der Zielname S traegt`(@TempDir dir: File) {
        val (p, s) = lage(dir)
        markerAlt(dir, s)
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable, "Zielname = P: aelterer Stand nicht still uebernehmen")
        absturzNachUmbenennen(dir, p, s)
        val lage = FuseLedgerSealRecovery.inspect(dir)
        assertFalse(lage.recoverable, "Zielname = S ist ohne Pruefsumme nicht von P unterscheidbar: ${lage.why}")
    }

    // ---- Altmarker --------------------------------------------------------

    @Test
    fun `Altmarker - vollstaendige tmp mit passender Revision ist Nachweis`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        markerAlt(dir, s)
        absturzNachTmp(dir, s)
        val lage = FuseLedgerSealRecovery.inspect(dir)
        assertTrue(lage.recoverable, lage.why)
        assertTrue(FuseLedgerSealRecovery.perform(dir, t0 + 120_000L, "test", "grund") is FuseLedgerSealRecovery.Result.Done)
        pruefeWiederhergestellt(dir, s)
    }

    @Test
    fun `Altmarker - tmp mit anderer Revision oder abgeschnitten bleibt gehalten`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        FuseLedgerStore().markSealPending(dir, "SEAL_PENDING rev=${rev(s) + 1}")
        absturzNachTmp(dir, s)
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable)
        File(dir, FuseLedgerStore.SEAL_PENDING_NAME).delete()
        markerAlt(dir, s)
        File(dir, tmp).writeText(s.take(s.length / 2))
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable, "abgeschnittene .tmp ist kein Nachweis")
    }

    // ---- Verweigerungen --------------------------------------------------

    @Test
    fun `ohne Marker, unter Reparaturmarker, Hold-Marker oder ohne Grund wird verweigert`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable, "ohne Marker nichts zu tun")
        markerV2(dir, s)
        absturzNachTmp(dir, s)
        assertTrue(FuseLedgerSealRecovery.perform(dir, t0, "", "grund") is FuseLedgerSealRecovery.Result.Refused)
        assertTrue(FuseLedgerSealRecovery.perform(dir, t0, "wer", " ") is FuseLedgerSealRecovery.Result.Refused)
        assertTrue(FuseLedgerStore().markRepairPending(dir, "REPAIR_PENDING"))
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable)
        FuseLedgerStore().clearRepairPending(dir)
        assertTrue(FuseLedgerStore.writeHoldVerified(dir, "Befund"))
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable)
    }

    @Test
    fun `eine unlesbare Generation neben dem Nachweis haelt`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        markerV2(dir, s)
        absturzNachTmp(dir, s)
        File(dir, bak).writeText("{kaputt")
        val vorher = schnappschuss(dir)
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable, "unlesbare .bak: der Stand ist nicht eindeutig belegt")
        assertTrue(FuseLedgerSealRecovery.perform(dir, t0 + 1, "test", "grund") is FuseLedgerSealRecovery.Result.Refused)
        assertEquals(vorher, schnappschuss(dir), "nichts veraendert")
    }

    @Test
    fun `ohne Sentinel keine Wiederherstellung`(@TempDir root: File) {
        val (p, s) = lage(File(root, "quelle").also { it.mkdirs() })
        val dir = File(root, "ohne").also { it.mkdirs() }
        File(dir, target).writeText(p)
        markerV2(dir, s)
        absturzNachTmp(dir, s)
        assertFalse(FuseLedgerStore.sentinelExists(dir), "Vorbedingung")
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable, "ohne Sentinel ist die Vorgeschichte nicht belegt")
        assertTrue(FuseLedgerSealRecovery.perform(dir, t0 + 1, "test", "grund") is FuseLedgerSealRecovery.Result.Refused)
    }

    /**
     * GEGENPROBE NACH DEM SCHREIBEN: eine fremde Generation mit hoeherer Revision
     * im Zielnamen wandert beim Schreiben nach .bak und gewaenne beim Laden die
     * Generationswahl. Dann darf die Wiederherstellung nicht als erledigt gelten.
     */
    @Test
    fun `gewinnt beim Laden eine andere Generation, bleibt es gehalten`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        markerV2(dir, s)
        absturzNachTmp(dir, s)
        val fremd = org.json.JSONObject(s).also { it.put("revision", rev(s) + 1) }.toString()
        assertEquals(rev(s) + 1, rev(fremd), "Vorbedingung: decodierbar mit hoeherer Revision")
        File(dir, target).writeText(fremd)
        assertTrue(FuseLedgerSealRecovery.inspect(dir).recoverable, "Vorbedingung: die Pruefsumme der .tmp belegt S")
        val r = FuseLedgerSealRecovery.perform(dir, t0 + 1, "test", "grund")
        assertTrue(r is FuseLedgerSealRecovery.Result.Refused, "$r")
        assertTrue(FuseLedgerStore.sealPendingExists(dir) && FuseLedgerStore.recoveryPendingExists(dir), "die Marker bleiben")
        assertTrue(geladen(dir).recoveryHold)
        assertTrue(FuseLedgerSealRecovery.lastRecovery(dir) == null, "kein Protokolleintrag")
    }

    // ---- Absturzfestigkeit ------------------------------------------------

    private class Absturz : RuntimeException()

    @Test
    fun `Absturz nach jeder Stufe - nie ungesperrt, Fortsetzung fuehrt zum unterbrochenen Stand`(@TempDir root: File) {
        for (stufe in FuseLedgerSealRecovery.Step.entries) {
            val dir = File(root, stufe.name).also { it.mkdirs() }
            val (_, s) = lage(dir)
            markerV2(dir, s)
            absturzNachTmp(dir, s)
            runCatching {
                FuseLedgerSealRecovery.perform(dir, t0 + 120_000L, "test", "grund", absturz = { if (it == stufe) throw Absturz() })
            }
            val zwischen = geladen(dir)
            assertTrue(zwischen.recoveryHold, "nach Absturz bei $stufe haelt der Ledger")
            assertFalse(zwischen.persistVerified(dir), "nach Absturz bei $stufe wird nicht gespeichert")
            val fortsetzung = FuseLedgerSealRecovery.perform(dir, t0 + 180_000L, "test", "erneut")
            assertTrue(fortsetzung is FuseLedgerSealRecovery.Result.Done, "Fortsetzung nach $stufe: $fortsetzung")
            pruefeWiederhergestellt(dir, s)
            val zeilen = File(dir, FuseLedgerSealRecovery.LOG_NAME).readLines().filter { it.isNotBlank() }
            assertEquals(1, zeilen.size, "genau eine Protokollzeile nach $stufe")
        }
    }

    @Test
    fun `doppelter Absturz waehrend der Wiederherstellung`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        markerAlt(dir, s)
        absturzNachTmp(dir, s)
        runCatching { FuseLedgerSealRecovery.perform(dir, t0 + 1, "test", "g", absturz = { if (it == FuseLedgerSealRecovery.Step.STATE_WRITTEN) throw Absturz() }) }
        assertTrue(geladen(dir).recoveryHold)
        runCatching { FuseLedgerSealRecovery.perform(dir, t0 + 2, "test", "g", absturz = { if (it == FuseLedgerSealRecovery.Step.SEAL_CLEARED) throw Absturz() }) }
        assertFalse(FuseLedgerStore.sealPendingExists(dir))
        assertTrue(FuseLedgerStore.recoveryPendingExists(dir))
        assertTrue(geladen(dir).recoveryHold, "nur noch der Transaktionsmarker - trotzdem gesperrt")
        assertTrue(FuseLedgerSealRecovery.perform(dir, t0 + 3, "test", "g") is FuseLedgerSealRecovery.Result.Done)
        pruefeWiederhergestellt(dir, s)
    }

    @Test
    fun `Fortsetzung verweigert, wenn der Beleg fehlt oder abweicht`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        markerV2(dir, s)
        absturzNachTmp(dir, s)
        runCatching { FuseLedgerSealRecovery.perform(dir, t0 + 1, "test", "g", absturz = { if (it == FuseLedgerSealRecovery.Step.PENDING_MARKED) throw Absturz() }) }
        val beleg = dir.listFiles()!!.first { it.name.contains(FuseLedgerSealRecovery.EVIDENCE_SUFFIX) }
        beleg.writeText(s.replace("\"revision\"", "\"revision\" "))
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable)
        beleg.delete()
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable)
        assertTrue(geladen(dir).recoveryHold)
    }

    // ---- Keine erneute Veroeffentlichung, keine Freigabe alter Mengen ----

    @Test
    fun `die Wiederherstellung setzt keine Buchung zurueck und gibt keine alte Menge frei`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        markerV2(dir, s)
        absturzNachTmp(dir, s)
        assertTrue(FuseLedgerSealRecovery.perform(dir, t0 + 120_000L, "test", "grund") is FuseLedgerSealRecovery.Result.Done)
        val a = geladen(dir)
        val k = kontrollLadung(s)
        assertEquals(k.state.entries, a.state.entries, "jede Zeile wie nach ungestoertem Neustart - keine zurueckgesetzt, keine quittiert")
        assertEquals(k.state.transportCommitmentU, a.state.transportCommitmentU, 1e-12, "Transporthaftung unveraendert")
        assertEquals(k.retiredBoundIds.toList(), a.retiredBoundIds.toList(), "Genau-einmal-Riegel unveraendert")
        assertEquals(k.state.openEntries.sumOf { it.grossLiabilityU }, a.state.openEntries.sumOf { it.grossLiabilityU }, 1e-12, "Bruttohaftung unveraendert")
        // Eine neue Buchung bekommt ihre eigene Zeile; die alte bleibt daneben offen.
        a.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        a.onPublished("p2", 0.10, t0 + 180_000L, 0L, 0.05, medtrum.name, LedgerFacts.serialHashOf("abc", medtrum.name), virtualPump = true)
        assertTrue(a.hasOpenProposal("p1") && a.hasOpenProposal("p2"))
        assertTrue(a.persistVerified(dir))
    }

    // ---- Adapter ------------------------------------------------------------

    @Test
    fun `der neue Marker traegt die Pruefsumme des geschriebenen Inhalts`(@TempDir dir: File) {
        val (_, s) = lage(dir)
        val m = FuseLedgerSealRecovery.SealMarker.parse(FuseLedgerStore.sealMarkerContent("abc", rev(s), s))!!
        assertEquals(2, m.version)
        assertEquals(FuseLedgerStore.sha256(s), m.sha256)
        assertEquals(rev(s), m.revision)
        assertEquals(null, FuseLedgerSealRecovery.SealMarker.parse(FuseLedgerStore.sealMarkerContent("abc", 1, null))!!.sha256)
        assertEquals(1, FuseLedgerSealRecovery.SealMarker.parse("SEAL_PENDING rev=12")!!.version)
        assertEquals(null, FuseLedgerSealRecovery.SealMarker.parse("SEAL_PENDING kaputt"))
    }

    /**
     * DER ADAPTER SELBST schreibt den v2-Marker mit der Pruefsumme des Inhalts:
     * der Verzeichnis-Sync der Rotation scheitert (zweiter syncDirectory-Aufruf,
     * nach dem Marker), der Zielname traegt schon den neuen Stand, der Marker bleibt.
     */
    @Test
    fun `persistVerified schreibt den v2-Marker mit der Pruefsumme des geschriebenen Inhalts`(@TempDir dir: File) {
        lage(dir)
        var n = 0
        val stoerung = object : Durability {
            override fun syncFile(fd: java.io.FileDescriptor) = fd.sync()
            override fun syncDirectory(dir: File) {
                n++
                if (n == 2) throw java.io.IOException("Teststoerung")
            }
        }
        val a = FuseLedgerAdapter(FuseLedgerStore(stoerung)).also { it.loadOnce(dir, "r-c", t0 + 1, echt) }
        a.episodes.livenessReArmUntilTs = sperreS
        assertFalse(a.persistVerified(dir), "Verzeichnis-Sync gescheitert -> nicht versiegelt")
        val marker = FuseLedgerSealRecovery.SealMarker.parse(FuseLedgerStore.readSealPending(dir))
        assertNotNull(marker)
        assertEquals(2, marker!!.version)
        val ziel = File(dir, target).readText()
        assertEquals(FuseLedgerStore.sha256(ziel), marker.sha256, "der Marker traegt die Pruefsumme genau des geschriebenen Inhalts")
        val lage = FuseLedgerSealRecovery.inspect(dir)
        assertTrue(lage.recoverable, lage.why)
        assertEquals(target, lage.proof!!.sourceName)
    }

    // ---- Keine Migration unter Marker ---------------------------------------

    /**
     * Aufbau wie die Migrationstests des Adapters: sauber versiegelte Generation
     * mit offener Zeile, Zielname auf v1 zurueckgesetzt. Ohne Marker migriert das
     * Laden still (Kontrolle); unter einem Seal- oder Transaktionsmarker darf es
     * nichts umschreiben - die Pruefsumme des Markers verweist auf genau diese
     * Bytes, eine Migration zerstoerte den Nachweis.
     */
    private fun altGeneration(dir: File): String {
        lage(dir)
        val ziel = File(dir, target)
        val v1 = org.json.JSONObject(ziel.readText()).also { it.put("v", 1); it.remove("proposalPumpEpochs") }.toString()
        ziel.writeText(v1)
        return v1
    }

    /** Dieselbe (virtuelle) Pumpe wie die Buchung - sonst haelt schon die Migration selbst. */
    private fun geladenVirtuell(dir: File) =
        FuseLedgerAdapter().also { it.loadOnce(dir, "r-m", t0 + 60_000L, FuseActivePump(medtrum.name, virtualPump = true)) }

    /** Die Generationsdateien - der Migrations-Hold-Marker (`fuse_ledger.hold`) ist bewusst
     *  ausgenommen: er ist der dokumentierte zusaetzliche Hold, keine Migration. */
    private fun generationen(dir: File) = schnappschuss(dir).filterKeys { it == target || it == tmp || it == bak }

    @Test
    fun `ohne Marker migriert die Altgeneration - Kontrolle`(@TempDir dir: File) {
        val v1 = altGeneration(dir)
        val a = geladenVirtuell(dir)
        assertFalse(a.recoveryHold, "Vorbedingung: die Migration selbst haelt nicht")
        assertTrue(File(dir, target).readText() != v1, "Vorbedingung: ohne Marker wird die Datei migriert")
        assertEquals(LedgerCodec.VERSION, org.json.JSONObject(File(dir, target).readText()).getInt("v"))
    }

    @Test
    fun `keine Migration unter dem Seal-Marker`(@TempDir dir: File) {
        val v1 = altGeneration(dir)
        markerV2(dir, v1)
        val vorher = generationen(dir)
        val a = geladenVirtuell(dir)
        assertTrue(a.recoveryHold)
        assertEquals(vorher, generationen(dir), "unter dem Seal-Marker: kein Umschreiben, keine .tmp, keine .bak")
        assertEquals(1, org.json.JSONObject(File(dir, target).readText()).getInt("v"))
        assertTrue(FuseLedgerStore.sealPendingExists(dir), "der Marker bleibt")
        assertFalse(FuseLedgerSealRecovery.inspect(dir).recoverable, "eine migrationsbeduerftige Generation wird nicht wiederhergestellt")
    }

    @Test
    fun `keine Migration unter dem Transaktionsmarker`(@TempDir dir: File) {
        val v1 = altGeneration(dir)
        assertTrue(FuseLedgerStore().markRecoveryPending(dir, "{}"))
        val vorher = generationen(dir)
        val a = geladenVirtuell(dir)
        assertTrue(a.recoveryHold)
        assertEquals(vorher, generationen(dir), "unter dem Transaktionsmarker: kein Umschreiben, keine .tmp, keine .bak")
        assertEquals(v1, File(dir, target).readText())
    }

    @Test
    fun `unter dem Transaktionsmarker haelt der Ledger und speichert nicht`(@TempDir dir: File) {
        lage(dir)
        assertTrue(FuseLedgerStore().markRecoveryPending(dir, "{}"))
        val a = geladen(dir)
        assertTrue(a.recoveryHold)
        assertFalse(a.persistVerified(dir))
    }
}

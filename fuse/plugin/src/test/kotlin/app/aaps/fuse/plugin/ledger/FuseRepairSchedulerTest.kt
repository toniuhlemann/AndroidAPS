package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.pump.defs.PumpType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import app.aaps.fuse.plugin.FuseActivePump

/**
 * P0 (10.08.2026): DIE DATEIREPARATUR GEHOERT AN DIE ZYKLUSGRENZE, NICHT NUR
 * DER OBJEKTTAUSCH.
 *
 * Der erste Entwurf verschob nur den Adaptertausch und liess `perform()` sofort
 * auf dem UI-Thread laufen. Der raeumt aber DATEIEN um. Ein gleichzeitig
 * laufender Zyklus haelt seinen alten Ledger im Speicher und schreibt ihn
 * danach mit `persistVerified()` in dieselben Dateien zurueck - die Reparatur
 * ist ueberschrieben, die Fehlerzeilen sind WIEDERBELEBT, und der naechste
 * Zyklus haelt erneut. Die Reparatur haette scheinbar funktioniert.
 *
 * Geprueft wird deshalb die REIHENFOLGE, nicht das Ergebnis allein.
 */
class FuseRepairSchedulerTest {

    private val t0 = 1_700_000_000_000L
    private val medtrum = PumpType.MEDTRUM_NANO
    private val emuliert = FuseActivePump(medtrum.name, virtualPump = true)

    private fun auftrag(grund: String = "Schemawechsel") =
        FuseLedgerRepair.RepairRequest(by = "Bediener", reason = grund)

    /** Ein gehaltener Ledger mit einer offenen Zeile - die Lage vom Testgeraet. */
    private fun gehaltenerLedger(dir: File): FuseLedgerAdapter {
        val a = FuseLedgerAdapter().also {
            it.loadOnce(dir, "s-a", t0, emuliert)
            it.observeBindingContext(LedgerPumpBindingContext.emulation(null))
        }
        a.onPublished(
            "p1", 0.30, t0, 0L, 0.05,
            medtrum.name, LedgerFacts.serialHashOf("abc", medtrum.name), virtualPump = true,
        )
        assertTrue(a.persistVerified(dir))
        assertTrue(FuseLedgerStore.writeHoldVerified(dir, "Testbefund"))
        return a
    }

    private fun zustand(dir: File) = dir.listFiles().orEmpty()
        .map { it.name to it.readText() }.sortedBy { it.first }

    // ---- Der P0 selbst ---------------------------------------------------

    /**
     * DIE VORMERKUNG FASST KEINE DATEI AN.
     *
     * Das ist der Kern: solange der Auftrag nur aussteht, ist das Verzeichnis
     * bitgleich. Ein laufender Zyklus kann also nichts ueberschreiben, was es
     * noch gar nicht gibt.
     */
    @Test
    fun `eine vorgemerkte Reparatur veraendert nichts`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        val vorher = zustand(dir)

        val s = FuseRepairScheduler()
        assertTrue(s.request(auftrag()))
        assertTrue(s.isPending)

        assertEquals(vorher, zustand(dir)) { "die Vormerkung darf keine Datei anfassen" }
        assertTrue(FuseLedgerStore.holdExists(dir)) { "und den Hold erst recht nicht loesen" }
    }

    /**
     * DER GANZE ABLAUF, IN DER REIHENFOLGE DES FEHLERS.
     *
     * 1. Zyklus laeuft mit dem alten Ledger (noch nicht persistiert).
     * 2. Der Bediener fordert die Reparatur an.
     * 3. NACHWEIS: die Dateien sind unveraendert.
     * 4. Der alte Zyklus laeuft zu Ende UND persistiert - genau der Schreiber,
     *    der die Reparatur frueher zunichte gemacht haette.
     * 5. Erst der naechste Zyklus fuehrt die Reparatur aus.
     * 6. Danach ist nur die frische Generation aktiv, der Altbestand liegt in
     *    Quarantaene, und der Hold ist weg.
     */
    @Test
    fun `ein laufender Zyklus kann die Reparatur nicht mehr zunichte machen`(@TempDir dir: File) {
        // 1.
        val alterZyklus = gehaltenerLedger(dir)
        alterZyklus.onPublished(
            "p2", 0.20, t0 + 1_000L, 0L, 0.05,
            medtrum.name, LedgerFacts.serialHashOf("abc", medtrum.name), virtualPump = true,
        )
        val vorher = zustand(dir)

        // 2. + 3.
        val s = FuseRepairScheduler()
        assertTrue(s.request(auftrag()))
        assertEquals(vorher, zustand(dir)) { "zwischen Zustimmung und Ausfuehrung passiert NICHTS" }

        // 4. Der alte Zyklus schreibt seinen In-Memory-Zustand zurueck.
        assertTrue(alterZyklus.persistVerified(dir))
        val nachAltemPersist = File(dir, FuseLedgerStore.FILE_NAME).readText()
        assertTrue(nachAltemPersist.contains("p2")) { "der alte Zyklus hat wirklich geschrieben" }

        // 5. Zyklusgrenze.
        val r = s.runIfDue(dir, t0 + 2_000L, provenVirtualPump = true)
        assertTrue(r is FuseLedgerRepair.Result.Done)
        assertTrue((r as FuseLedgerRepair.Result.Done).freshLedgerWritten)

        // 6.
        assertFalse(FuseLedgerStore.holdExists(dir))
        val neu = FuseLedgerAdapter().also { it.loadOnce(dir, "s-b", t0 + 3_000L, emuliert) }
        assertFalse(neu.recoveryHold) { "der Hold darf nicht wieder auferstehen" }
        assertTrue(neu.state.entries.isEmpty()) { "und die alten Zeilen auch nicht" }

        val quarantaene = dir.listFiles()!!.filter { FuseLedgerRepair.RESET_SUFFIX in it.name }
        assertTrue(quarantaene.any { it.readText() == nachAltemPersist }) {
            "genau der Stand, den der alte Zyklus zuletzt geschrieben hat, muss in Quarantaene liegen"
        }
    }

    /**
     * P0 (Codex 10.08.): NUR BEI NACHGEWIESENER VIRTUALPUMP.
     *
     * Die erste Fassung fragte `realPump` und erlaubte bei `false`. Aber
     * `realPump` ist auch `false` bei unbekannter Pumpe, bei einer Medtrum mit
     * noch nicht geladenem Modell (MEDTRUM_UNTESTED), bei einer fremden
     * physischen Pumpe und bei fehlgeschlagener Pumpenabfrage. In allen vier
     * Faellen haette die destruktive Reparatur laufen duerfen.
     *
     * Geprueft wird deshalb der NACHWEIS - und zwar bei der AUSFUEHRUNG:
     * zwischen Zustimmung und Ausfuehrung liegt ein Zyklus, in dem die Pumpe
     * wechseln kann.
     *
     * Die Faelle stammen aus ECHTEN Snapshots, nicht aus einem handgesetzten
     * Flag: sonst pruefte der Test meine Annahme darueber, was `of()` liefert,
     * statt das, was es liefert.
     */
    @Test
    fun `nur eine nachgewiesene VirtualPump erlaubt die Reparatur`(@TempDir dir: File) {
        data class Fall(val name: String, val snapshot: FuseActivePump, val erlaubt: Boolean)

        val faelle = listOf(
            Fall("nachgewiesene Emulation", FuseActivePump(medtrum.name, virtualPump = true), true),
            Fall("echte Medtrum", FuseActivePump(medtrum.name, virtualPump = false), false),
            Fall("Pumpe unbekannt", FuseActivePump.UNKNOWN, false),
            Fall("Modell noch nicht geladen", FuseActivePump(PumpType.MEDTRUM_UNTESTED.name, virtualPump = false), false),
            Fall("fremde physische Pumpe", FuseActivePump(PumpType.DANA_RS.name, virtualPump = false), false),
        )

        for (f in faelle) {
            val unter = File(dir, f.name.replace(" ", "_")).also(File::mkdirs)
            gehaltenerLedger(unter)
            val vorher = zustand(unter)
            val s = FuseRepairScheduler()
            assertTrue(s.request(auftrag()))

            val r = s.runIfDue(unter, t0 + 1_000L, provenVirtualPump = f.snapshot.repairAllowed)

            if (f.erlaubt) {
                assertTrue(r is FuseLedgerRepair.Result.Done) { "${f.name}: haette laufen muessen" }
                assertFalse(FuseLedgerStore.holdExists(unter)) { "${f.name}: Hold sollte offen sein" }
            } else {
                assertTrue(r is FuseLedgerRepair.Result.Refused) { "${f.name}: haette verweigert werden muessen" }
                assertEquals(vorher, zustand(unter)) { "${f.name}: keine Datei darf angefasst worden sein" }
                assertTrue(FuseLedgerStore.holdExists(unter)) { "${f.name}: der Hold muss stehen bleiben" }
            }
            assertFalse(s.isPending) { "${f.name}: der Auftrag ist verbraucht" }
        }

        // UND der Unterschied zur alten, falschen Bedingung wird
        // ausdruecklich festgehalten: waere `repairAllowed` wieder als
        // `!realPump` definiert, faellt genau das hier auf.
        for (f in faelle.filter { !it.erlaubt && !it.snapshot.realPump }) {
            assertFalse(f.snapshot.repairAllowed) { "${f.name}: kein Nachweis" }
            assertFalse(f.snapshot.realPump) { "${f.name}: und auch nicht 'real' - genau hier lag der P0" }
        }
    }

    // ---- Der Auftrag ------------------------------------------------------

    /** Zweimal tippen ist eine Reparatur, nicht zwei. */
    @Test
    fun `ein zweiter Auftrag wird abgewiesen`() {
        val s = FuseRepairScheduler()
        assertTrue(s.request(auftrag("erster")))
        assertFalse(s.request(auftrag("zweiter"))) { "der zweite Tipp darf nichts zusaetzliches ausloesen" }

        var gesehen: FuseLedgerRepair.RepairRequest? = null
        s.runIfDue { gesehen = it; FuseLedgerRepair.Result.Refused("Test") }
        assertEquals("erster", gesehen?.reason) { "der ERSTE Auftrag gilt, nicht der letzte" }
    }

    /** Und das haelt auch unter echter Gleichzeitigkeit - genau ein Gewinner. */
    @Test
    fun `bei gleichzeitigen Auftraegen gewinnt genau einer`() {
        val s = FuseRepairScheduler()
        val start = CountDownLatch(1)
        val fertig = CountDownLatch(8)
        val gewonnen = AtomicInteger(0)
        repeat(8) { i ->
            Thread {
                start.await()
                if (s.request(auftrag("t$i"))) gewonnen.incrementAndGet()
                fertig.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(fertig.await(10, TimeUnit.SECONDS))
        assertEquals(1, gewonnen.get()) { "compareAndSet laesst genau einen durch" }
    }

    /** Der Grund reist MIT. Ihn zur Ausfuehrungszeit neu zu bilden waere ein
     *  anderes Protokoll als die erteilte Zustimmung. */
    @Test
    fun `der Auftrag traegt Ausloeser und Grund bis zur Ausfuehrung`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        val s = FuseRepairScheduler()
        s.request(FuseLedgerRepair.RepairRequest("Bediener X", "Grund Y"))

        val r = s.runIfDue(dir, t0 + 1_000L, provenVirtualPump = true) as FuseLedgerRepair.Result.Done
        assertEquals("Bediener X", r.record.by)
        assertEquals("Grund Y", r.record.reason)
        assertEquals("Grund Y", FuseLedgerRepair.lastReset(dir)!!.reason)
    }

    /** Ohne Auftrag passiert an der Zyklusgrenze gar nichts - der Aufruf steht
     *  in JEDEM Zyklus. */
    @Test
    fun `ohne Auftrag tut die Zyklusgrenze nichts`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        val vorher = zustand(dir)
        assertNull(FuseRepairScheduler().runIfDue(dir, t0 + 1_000L, provenVirtualPump = true))
        assertEquals(vorher, zustand(dir))
    }

    /**
     * ZWISCHEN ZUSTIMMUNG UND AUSFUEHRUNG LIEGT EIN ZYKLUS.
     *
     * Loest sich der Hold in der Zwischenzeit auf, wird NICHT trotzdem
     * repariert - dann waere es genau der "Ledger leeren"-Knopf, den es nicht
     * geben soll.
     */
    @Test
    fun `entfaellt der Grund vor der Ausfuehrung, wird nicht repariert`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        val s = FuseRepairScheduler()
        s.request(auftrag())

        // Die Lage aendert sich: kein Hold mehr, sauberer Zustand.
        val frisch = FuseLedgerAdapter().also { it.loadOnce(dir, "s-c", t0 + 500L, emuliert) }
        assertTrue(FuseLedgerStore.clearHoldVerified(dir))
        assertTrue(frisch.persistVerified(dir))

        val r = s.runIfDue(dir, t0 + 1_000L, provenVirtualPump = true)
        assertTrue(r is FuseLedgerRepair.Result.Refused) { "kein Hold, kein Verlust - also nichts zu tun" }
        assertNotNull(File(dir, FuseLedgerStore.FILE_NAME).takeIf { it.isFile })
        assertNull(FuseLedgerRepair.lastReset(dir)) { "und kein Protokolleintrag fuer einen Vorgang ohne Vorgang" }
    }

    /** Ein verbrauchter Auftrag wiederholt sich nicht. */
    @Test
    fun `ein ausgefuehrter Auftrag ist verbraucht`(@TempDir dir: File) {
        gehaltenerLedger(dir)
        val s = FuseRepairScheduler()
        s.request(auftrag())
        assertTrue(s.runIfDue(dir, t0 + 1_000L, provenVirtualPump = true) is FuseLedgerRepair.Result.Done)
        assertFalse(s.isPending)
        assertNull(s.runIfDue(dir, t0 + 2_000L, provenVirtualPump = true))
    }
}

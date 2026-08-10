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
            it.observePatchEpoch(null)
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
        val r = s.runIfDue(dir, t0 + 2_000L)
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

        val r = s.runIfDue(dir, t0 + 1_000L) as FuseLedgerRepair.Result.Done
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
        assertNull(FuseRepairScheduler().runIfDue(dir, t0 + 1_000L))
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

        val r = s.runIfDue(dir, t0 + 1_000L)
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
        assertTrue(s.runIfDue(dir, t0 + 1_000L) is FuseLedgerRepair.Result.Done)
        assertFalse(s.isPending)
        assertNull(s.runIfDue(dir, t0 + 2_000L))
    }
}

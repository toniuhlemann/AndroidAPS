package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.fuse.plugin.FuseActivePump
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileDescriptor
import java.io.SyncFailedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * KI-177: ZWEI GLEICHZEITIGE SCHREIBER AUF DENSELBEN LEDGER.
 *
 * Am Geraet sind das der Regelzyklus und der Marker-Druck aus der Bedienung.
 * Das Siegel des Write-ahead-Protokolls trennte sie nicht: beide konnten die
 * Pruefung "kein Siegel" passieren, oder der zweite scheiterte am Siegel des
 * ersten und hinterliess einen gescheiterten Persist.
 *
 * DIE UEBERLAPPUNG WIRD ERZWUNGEN, nicht erhofft. Der erste Schreiber haelt in
 * seinem ersten Datei-Sync an - dann liegt sein Siegel schon - und wird erst
 * freigegeben, wenn der zweite nachweislich auf die Schreibsperre wartet (oder,
 * ohne Sperre, schon fertig ist). Ein zufaelliger Thread-Treffer ist dafuer
 * nicht noetig.
 */
class FuseLedgerSchreibSperreTest {

    private val t0 = 1_700_000_000_000L
    private val echt = FuseActivePump(PumpType.MEDTRUM_NANO.name, virtualPump = false)

    /**
     * Haelt den ersten Datei-Sync NACH dem Scharfschalten an, bis [freigabe]
     * faellt. Optional scheitert der Sync ab dem n-ten Aufruf nach dem
     * Scharfschalten (1-basiert) - so laesst sich ein Fehler mitten im
     * Schreibvorgang des ersten Schreibers herstellen.
     */
    private class HaltendeDurability(private val scheitertAbSync: Int = Int.MAX_VALUE) : Durability {

        @Volatile var scharf = false
        val angehalten = CountDownLatch(1)
        val freigabe = CountDownLatch(1)
        private var syncs = 0

        override fun syncFile(fd: FileDescriptor) {
            if (!scharf) return
            val n = synchronized(this) { ++syncs }
            if (n == 1) {
                angehalten.countDown()
                check(freigabe.await(10, TimeUnit.SECONDS)) { "Freigabe kam nicht" }
            }
            if (n >= scheitertAbSync) throw SyncFailedException("eingespeist: Datei-Sync #$n")
        }

        override fun syncDirectory(dir: File) {}
    }

    private fun geladen(dir: File, d: Durability) =
        FuseLedgerAdapter(FuseLedgerStore(d)).also { it.loadOnce(dir, "r-test", t0, echt) }

    private fun siegelLiegt(dir: File) = FuseLedgerStore.sealPendingExists(dir)

    /**
     * Startet zwei Schreiber. Der erste haelt im Sync an; der zweite startet,
     * und erst wenn er wartet oder fertig ist, wird der erste freigegeben.
     * @return die Ergebnisse beider persistVerified-Aufrufe.
     */
    private fun ueberlappt(dir: File, d: HaltendeDurability, erster: FuseLedgerAdapter, zweiter: FuseLedgerAdapter): Pair<Boolean, Boolean> {
        val ergebnisErster = AtomicReference<Boolean>()
        val ergebnisZweiter = AtomicReference<Boolean>()
        d.scharf = true
        val a = Thread { ergebnisErster.set(erster.persistVerified(dir)) }.also { it.start() }
        assertTrue(d.angehalten.await(10, TimeUnit.SECONDS), "der erste Schreiber muss im Sync anhalten")
        assertTrue(siegelLiegt(dir), "Vorbedingung: das Siegel des ersten Schreibers liegt")

        val b = Thread { ergebnisZweiter.set(zweiter.persistVerified(dir)) }.also { it.start() }
        val frist = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (b.state != Thread.State.BLOCKED && b.state != Thread.State.TERMINATED && System.nanoTime() < frist)
            Thread.onSpinWait()

        d.freigabe.countDown()
        a.join(10_000)
        b.join(10_000)
        assertFalse(a.isAlive || b.isAlive, "beide Schreiber muessen enden")
        return ergebnisErster.get() to ergebnisZweiter.get()
    }

    @Test
    fun `derselbe Adapter - der zweite Schreiber wartet, statt am Siegel des ersten zu scheitern`(@TempDir dir: File) {
        val d = HaltendeDurability()
        val adapter = geladen(dir, d)
        val (erster, zweiter) = ueberlappt(dir, d, adapter, adapter)
        assertTrue(erster, "der erste Schreiber gelingt")
        assertTrue(zweiter, "der zweite gelingt nach dem ersten, statt am fremden Siegel zu scheitern")
        assertFalse(siegelLiegt(dir), "kein Siegel bleibt liegen")
        assertFalse(adapter.persistFailed, "kein gescheiterter Persist bleibt stehen")
    }

    /** Reparatur und Wiederherstellung tauschen den Adapter aus - die Sperre muss auch zwei Instanzen trennen. */
    @Test
    fun `zwei Adapter auf demselben Verzeichnis - der zweite wartet ebenso`(@TempDir dir: File) {
        val d = HaltendeDurability()
        val alt = geladen(dir, d)
        val neu = geladen(dir, d)
        val (erster, zweiter) = ueberlappt(dir, d, alt, neu)
        assertTrue(erster && zweiter, "beide gelingen nacheinander: erster=$erster zweiter=$zweiter")
        assertFalse(siegelLiegt(dir), "kein Siegel bleibt liegen")
    }

    /**
     * Scheitert der erste Schreiber MITTEN im Vorgang (Siegel liegt, Zustand
     * nicht geschrieben), bleibt sein Siegel als Beweis liegen - und der zweite
     * Schreiber faellt geschlossen aus, statt die Spur zu ueberschreiben.
     */
    @Test
    fun `scheitert der erste Schreiber, bleibt das Siegel und der zweite schreibt nicht darueber`(@TempDir dir: File) {
        // Sync 1 = Siegel (haelt an, gelingt), Sync 2 = Zustand (scheitert).
        val d = HaltendeDurability(scheitertAbSync = 2)
        val adapter = geladen(dir, d)
        val (erster, zweiter) = ueberlappt(dir, d, adapter, adapter)
        assertFalse(erster, "der erste Schreiber scheitert eingespeist")
        assertFalse(zweiter, "der zweite schreibt nicht ueber das liegende Siegel")
        assertTrue(siegelLiegt(dir), "das Siegel bleibt als Beweis fuer den naechsten Start")
        assertTrue(adapter.persistFailed, "der Persist gilt als gescheitert (Hold-Vertrag)")
    }

    /**
     * Das Siegel selbst wird atomar angelegt: von zwei gleichzeitigen Anlegern
     * gewinnt in jeder Runde genau einer. Ein Stresstest - der Riegel ist
     * `createNewFile()`, ein gruener Lauf allein beweist ihn nicht.
     */
    @Test
    fun `von zwei gleichzeitigen Siegel-Anlegern gewinnt genau einer`(@TempDir root: File) {
        val store = FuseLedgerStore(FakeDurability())
        repeat(RUNDEN) { runde ->
            val dir = File(root, "r$runde").also { it.mkdirs() }
            val start = CyclicBarrier(2)
            val ergebnisse = Array(2) { AtomicReference<Boolean>() }
            val threads = (0 until 2).map { i ->
                Thread {
                    start.await(10, TimeUnit.SECONDS)
                    ergebnisse[i].set(store.markSealPending(dir, "SEAL_PENDING runde=$runde von=$i"))
                }.also { it.start() }
            }
            threads.forEach { it.join(10_000) }
            assertEquals(1, ergebnisse.count { it.get() == true }, "Runde $runde: genau ein Anleger darf gewinnen")
        }
    }

    private companion object {
        const val RUNDEN = 200
    }
}

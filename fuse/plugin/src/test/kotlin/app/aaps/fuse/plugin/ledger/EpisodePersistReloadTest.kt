package app.aaps.fuse.plugin.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Was ein NEUSTART von der Reservierung uebrig laesst - ueber die Datei, nicht
 * ueber dasselbe Speicherobjekt.
 *
 * WARUM DAS EIGENS GEPRUEFT WIRD (Review 11.08.): der bestehende
 * [EpisodeReservationTest] arbeitet auf EINEM Adapter im Speicher. Er beweist,
 * dass `resolveReservation` rechnet - aber nicht, was nach einem Prozessende
 * auf der Platte steht. Und genau dort sitzt die Frage: der Ledger wird
 * geschrieben, BEVOR die Reservierung aufgeloest wird.
 *
 * Daraus folgen vier Faelle, und dieser Test schreibt sie fest:
 *
 *   Absturz VOR dem Schreiben     nichts publiziert, nichts belastet
 *   Absturz NACH dem Schreiben    Belastung dauerhaft - konservativ
 *   REJECTED, dann weiterlaufen   im Speicher frei, beim naechsten
 *                                 Schreiben auch auf der Platte
 *   REJECTED, dann sofort Absturz ALTE Belastung kommt zurueck
 *
 * Der letzte Fall ist der unangenehme, und er ist AKZEPTIERT: die Freigabe geht
 * verloren, die Belastung bleibt. Das ist "zu wenig Insulin spaeter", nie "zu
 * viel jetzt". Er steht hier als ZUSICHERUNG und nicht als Fehler - wer ihn
 * eines Tages beheben will, muss die Reservierung persistieren und findet hier,
 * was das kosten wuerde.
 */
class EpisodePersistReloadTest {

    private val ts = 1_786_000_000_000L

    private fun frischerAdapter(dir: File, sitzung: String): FuseLedgerAdapter {
        val a = FuseLedgerAdapter()
        a.loadOnce(dir, sitzung, ts)
        return a
    }

    private fun belaste(a: FuseLedgerAdapter, menge: Double = 0.30) {
        a.episodes.primeSpentU = menge
        a.episodes.mealDeliveries.addLast(ts to menge)
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = ts, amountU = menge, prime = true, onset = false, mealTs = ts,
        )
    }

    @Test
    fun `Absturz VOR dem Schreiben - der Neustart sieht keine Belastung`(@TempDir dir: File) {
        val a = frischerAdapter(dir, "s1")
        a.persistVerified(dir)            // sauberer Ausgangsstand
        belaste(a)
        // kein zweites persistVerified - Prozess weg

        val b = frischerAdapter(dir, "s2")
        assertEquals(0.0, b.episodes.primeSpentU, 1e-12)
        assertTrue(b.episodes.mealDeliveries.isEmpty())
    }

    @Test
    fun `Absturz NACH dem Schreiben - die Belastung ist dauerhaft`(@TempDir dir: File) {
        val a = frischerAdapter(dir, "s1")
        belaste(a)
        assertTrue(a.persistVerified(dir), "schreiben muss gelingen")

        val b = frischerAdapter(dir, "s2")
        assertEquals(0.30, b.episodes.primeSpentU, 1e-12)
        assertEquals(1, b.episodes.mealDeliveries.size)
        // Die Reservierung selbst ueberlebt NICHT - sie ist bewusst fluechtig.
        // Der Neustart kann sie also nicht mehr aufloesen, und die Belastung
        // bleibt: der gewollte UNKNOWN-Ausgang.
        assertTrue(b.episodes.pendingReservation == null)
    }

    @Test
    fun `REJECTED und weiterlaufen - die Freigabe erreicht die Platte`(@TempDir dir: File) {
        val a = frischerAdapter(dir, "s1")
        belaste(a)
        a.persistVerified(dir)                       // Stand MIT Belastung
        a.resolveReservation(ts, publishedU = 0.0)   // Gate hat entfernt
        assertEquals(0.0, a.episodes.primeSpentU, 1e-12)
        assertTrue(a.persistVerified(dir), "der naechste Zyklus schreibt")

        val b = frischerAdapter(dir, "s2")
        assertEquals(0.0, b.episodes.primeSpentU, 1e-12)
        assertTrue(b.episodes.mealDeliveries.isEmpty())
    }

    /**
     * DER FALL AUS DEM REVIEW, wortwoertlich: freigegeben, dann sofort weg.
     *
     * Er ist NICHT behoben, und das ist die Entscheidung. Die alte Belastung
     * kommt zurueck - eine Menge gilt als verbraucht, die nie geflossen ist.
     * Die Folge ist weniger Insulin in der laufenden Episode, nie mehr.
     */
    @Test
    fun `REJECTED und sofort Absturz - die alte Belastung kommt zurueck`(@TempDir dir: File) {
        val a = frischerAdapter(dir, "s1")
        belaste(a)
        a.persistVerified(dir)                       // Stand MIT Belastung
        a.resolveReservation(ts, publishedU = 0.0)   // nur im Speicher frei
        // kein persistVerified mehr - Prozess weg

        val b = frischerAdapter(dir, "s2")
        assertEquals(0.30, b.episodes.primeSpentU, 1e-12, "die Freigabe war fluechtig")
        assertEquals(1, b.episodes.mealDeliveries.size)
    }

    /**
     * Und die Richtung, auf die es ankommt: KEIN Pfad laesst die Belastung
     * verschwinden, waehrend die Menge publiziert war. Geprueft ueber alle vier
     * Faelle hinweg - das ist die eigentliche Zusicherung dieser Datei.
     */
    @Test
    fun `kein Pfad gibt Budget frei das publiziert wurde`(@TempDir dir: File) {
        val a = frischerAdapter(dir, "s1")
        belaste(a)
        a.persistVerified(dir)
        // Publiziert wie reserviert - hier darf NICHTS freigegeben werden.
        a.resolveReservation(ts, publishedU = 0.30)
        assertEquals(0.30, a.episodes.primeSpentU, 1e-12)
        a.persistVerified(dir)

        val b = frischerAdapter(dir, "s2")
        assertEquals(0.30, b.episodes.primeSpentU, 1e-12)
    }
}

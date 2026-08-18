package app.aaps.fuse.plugin.expectation

import org.junit.jupiter.api.Assertions.assertEquals
import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.plugin.ledger.Durability
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * WAS DER GROESSTMOEGLICHE ZUSTAND KOSTET - Bytes und Kodierzeit.
 *
 * Toni 18.08., als Auflage vor dem Feldlauf: der Recorder schreibt in JEDEM
 * Zyklus, bei Ein-Minuten-Takt 1440 mal am Tag, und zwar SYNCHRON im
 * Loop-Aufruf. Er kann die bereits bestimmte Dosis nicht mehr aendern, wohl
 * aber den Zyklusabschluss verzoegern. Da nur noch das produktive Geraet zum
 * Messen zur Verfuegung steht, muss die Groessenordnung VORHER bekannt sein.
 *
 * DIESER TEST MISST NICHT DAS GERAET. Eine JVM auf einem Entwicklungsrechner
 * sagt nichts ueber Flash-Latenzen unter Android; der eigentliche Beleg sind
 * `writeMs`/`writeBytes` im Zyklusexport. Was er leistet: eine harte
 * Obergrenze fuer die DATENMENGE und den Nachweis, dass die Kodierung selbst
 * nicht in einer ungeahnten Groessenordnung liegt. Bricht eine der Schranken,
 * ist die Frage vor dem Flash geklaert - und nicht danach.
 */
class FuseExpectationLoadTest {

    private val t0 = 1_700_000_000_000L
    private val STAMP = InterventionStamp("lauf-A", 7L)
    private val CFG = "cfg#1"

    private class FakeDurability : Durability {

        override fun syncFile(fd: java.io.FileDescriptor) = Unit
        override fun syncDirectory(dir: File) = Unit
    }

    /** Der groesstmoegliche Zustand: alle drei Obergrenzen ausgereizt. */
    private fun maximalerZustand(): ExpectationLedger.State {
        val entries = (0 until FuseExpectationStore.MAX_ENTRIES).map { i ->
            ExpectationLedger.Entry(
                sourceTs = t0 + i * 60_000L,
                dueTs = t0 + (i + 30) * 60_000L,
                segmentId = 1L,
                anchorMgdl = 200.0 + i % 50,
                meanPredictedMgdl = 150.0 + i % 30,
                configGeneration = CFG,
                interventionStamp = STAMP,
                context = ExpectationLedger.ExpectationContext.CORRECTION,
                contextReason = ExpectationLedger.ContextReason.PURE_CORRECTION,
                safetyLowerPredictedMgdl = 40.0 + i % 20,
                lambda = 1.0,
                discountMgdl = -110.8,
                bgiMgdl = -127.7,
            )
        }
        // AUSDRUECKLICH IN DER VERGANGENHEIT und ohne Ueberschneidung mit den
        // offenen Eintraegen: dieselbe Kennung darf nicht zugleich offen und
        // abgerechnet sein. Der erste Wurf dieser Fixture verletzte genau das
        // - und `restore` hat es gemeldet, bevor der Test etwas Falsches
        // gemessen hat.
        val vergangen = t0 - 10L * 24 * 3600_000L
        val outcomes = (0 until FuseExpectationStore.MAX_OUTCOMES).map { i ->
            ExpectationLedger.Outcome(
                entries[i % entries.size].copy(
                    sourceTs = vergangen - i * 60_000L,
                    dueTs = vergangen - i * 60_000L + 30 * 60_000L,
                ),
                ExpectationLedger.Verdict.MISSED,
                vergangen - i * 60_000L + 30 * 60_000L, 205.0 + i % 40,
            )
        }
        val vergangenConsumed = t0 - 10L * 24 * 3600_000L
        val consumed = (0 until FuseExpectationStore.MAX_CONSUMED)
            .map { ExpectationLedger.SampleId(1L, vergangenConsumed - it * 60_000L) }.toSet()
        return when (val r = ExpectationLedger.restore(entries, consumed, outcomes, kopfstand = STAMP)) {
            is ExpectationLedger.Restored.Valid   -> r.state
            is ExpectationLedger.Restored.Invalid -> error("Fixture ungueltig: ${r.reason}")
        }
    }

    /**
     * DIE OBERGRENZE DER DATENMENGE.
     *
     * 200 Eintraege, 500 Ergebnisse, 500 verbrauchte Kennungen - mehr kann
     * nicht entstehen, [FuseExpectationStore.kappen] schneidet darueber ab.
     * Die Schranke ist bewusst grosszuegig gesetzt: sie soll eine
     * Groessenordnung festhalten, nicht eine Byte-Zahl einfrieren.
     */
    @Test
    fun `der groesste Zustand bleibt unter einem Megabyte`(@TempDir dir: File) {
        val store = FuseExpectationStore(FakeDurability())
        val stats = store.saveWithStats(dir, maximalerZustand(), revision = 1L, kopfstand = STAMP)
        assertTrue(stats.ok, "der Maximalzustand muss schreibbar sein")
        println("FUSE expectation Maximalzustand: ${stats.bytes} Bytes, ${stats.durationMs} ms (JVM)")
        assertTrue(
            stats.bytes in 1..1_000_000,
            "Groessenordnung: ${stats.bytes} Bytes - darueber gehoert die Kappung nachgerechnet",
        )
    }

    /**
     * DIE KODIERUNG SELBST DARF KEINE UEBERRASCHUNG SEIN.
     *
     * Gemessen wird der reine Encode-Pfad ohne Datei-Ein-/Ausgabe, gemittelt
     * ueber mehrere Durchlaeufe. Auf dem Geraet zaehlt am Ende `writeMs` aus
     * dem Zyklusexport - hier geht es nur darum, eine quadratische oder sonst
     * unerwartete Kostenfunktion auszuschliessen, bevor irgendetwas auf das
     * produktive Telefon kommt.
     */
    @Test
    fun `die Kodierung des Maximalzustands ist nicht ueberraschend teuer`() {
        val zustand = maximalerZustand()
        // Zwei Aufwaermrunden, damit die JIT-Kompilierung nicht als Kosten
        // erscheint.
        repeat(2) { FuseExpectationCodec.encode(zustand, 1L, lastObservationGapTs = 0L) }
        val start = System.nanoTime()
        val runden = 20
        repeat(runden) { FuseExpectationCodec.encode(zustand, 1L, lastObservationGapTs = 0L) }
        val jeRundeMs = (System.nanoTime() - start) / 1_000_000.0 / runden
        println("FUSE expectation encode: %.2f ms je Runde (JVM)".format(jeRundeMs))
        assertTrue(
            jeRundeMs < 250.0,
            "Kodierung %.1f ms - bei 1440 Zyklen am Tag gehoert das nachgerechnet".format(jeRundeMs),
        )
    }

    /**
     * UND DER NORMALFALL, nicht nur das Maximum.
     *
     * Nach einer ruhigen Nacht stehen vielleicht 60 Ergebnisse im Buch. Diese
     * Zahl beschreibt den Alltag; das Maximum oben ist der Riegel.
     */
    @Test
    fun `ein alltaeglicher Zustand ist deutlich kleiner`(@TempDir dir: File) {
        val voll = maximalerZustand()
        val alltag = when (
            val r = ExpectationLedger.restore(
                voll.entries.take(5), voll.consumed.take(60).toSet(), voll.outcomes.take(60),
                kopfstand = STAMP,
            )
        ) {
            is ExpectationLedger.Restored.Valid   -> r.state
            is ExpectationLedger.Restored.Invalid -> error(r.reason)
        }
        val stats = FuseExpectationStore(FakeDurability())
            .saveWithStats(dir, alltag, revision = 1L, kopfstand = STAMP)
        println("FUSE expectation Alltag: ${stats.bytes} Bytes, ${stats.durationMs} ms (JVM)")
        assertTrue(stats.ok)
        assertTrue(stats.bytes < 100_000, "Alltag: ${stats.bytes} Bytes")
    }

    /**
     * DER STORE MELDET, WAS ER GESCHRIEBEN HAT - nicht, was man ihm gab
     * (Toni 18.08., P0).
     *
     * Der erste Wurf gab nur `ok` zurueck, und der Recorder uebernahm seinen
     * eigenen, UNGEKAPPTEN Kandidaten als "persistiert". Ab der
     * Kappungsgrenze wertete die Auswertung damit Ergebnisse aus, die nie
     * versiegelt wurden - zwei Wahrheiten ueber denselben Zustand.
     */
    @Test
    fun `saveWithStats meldet den gekappten Zustand und die Zahl der Verluste`(@TempDir dir: File) {
        val voll = maximalerZustand()
        assertEquals(FuseExpectationStore.MAX_OUTCOMES, voll.outcomes.size, "Fixture ist an der Grenze")
        val weitFrueher = t0 - 100L * 24 * 3600_000L

        // Einen Zustand DARUEBER bauen: 20 Ergebnisse mehr, als gehalten wird.
        val zuViel = when (
            val r = ExpectationLedger.restore(
                voll.entries, voll.consumed,
                // ZUSAETZLICHE ERGEBNISSE MIT GARANTIERT EIGENEN KENNUNGEN.
                //
                // Zwei Anlaeufe scheiterten hier an der Semantikpruefung:
                // erst lag `actualTs` ausserhalb der Zuordnungstoleranz, dann
                // kollidierten die Kennungen mit dem Bestand. Beides hat
                // `restore` gemeldet, bevor der Test etwas Falsches messen
                // konnte - deshalb jetzt ein Zeitraum, der sicher neben allem
                // anderen liegt.
                voll.outcomes + (0 until 20).map { i ->
                    val quelle = weitFrueher - i * 60_000L
                    ExpectationLedger.Outcome(
                        voll.outcomes.first().entry.copy(
                            sourceTs = quelle,
                            dueTs = quelle + 30 * 60_000L,
                        ),
                        ExpectationLedger.Verdict.MISSED, quelle + 30 * 60_000L, 205.0,
                    )
                },
                kopfstand = STAMP,
            )
        ) {
            is ExpectationLedger.Restored.Valid   -> r.state
            is ExpectationLedger.Restored.Invalid -> error(r.reason)
        }
        assertEquals(FuseExpectationStore.MAX_OUTCOMES + 20, zuViel.outcomes.size)

        val stats = FuseExpectationStore(FakeDurability())
            .saveWithStats(dir, zuViel, revision = 1L, kopfstand = STAMP)
        assertTrue(stats.ok)
        assertEquals(
            FuseExpectationStore.MAX_OUTCOMES, stats.written!!.outcomes.size,
            "gemeldet wird der GEKAPPTE Stand",
        )
        assertEquals(20, stats.droppedOutcomes, "und wie viele dabei verloren gingen")

        // Gegenprobe: was zurueckgelesen wird, ist genau das Gemeldete.
        val geladen = FuseExpectationStore(FakeDurability())
            .load(dir, STAMP) as FuseExpectationStore.Loaded.Ok
        assertEquals(
            stats.written!!.outcomes.size, geladen.state.outcomes.size,
            "Platte und Meldung muessen uebereinstimmen",
        )
    }

    /** Ohne Ueberschreitung wird nichts als verloren gemeldet. */
    @Test
    fun `ohne Kappung meldet der Store keine Verluste`(@TempDir dir: File) {
        val stats = FuseExpectationStore(FakeDurability())
            .saveWithStats(dir, maximalerZustand(), revision = 1L, kopfstand = STAMP)
        assertEquals(0, stats.droppedOutcomes, "exakt voll ist nicht gekappt")
    }
}

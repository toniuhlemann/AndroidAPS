package app.aaps.fuse.plugin.expectation

import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.plugin.ledger.Durability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * DIE BUCHFUEHRUNG ALS GANZES - einreihen, abrechnen, fortschreiben.
 *
 * Die Einzelteile sind anderswo geprueft; hier geht es um die KETTE. Sie ist
 * der Teil, an dem in dieser Sitzung schon zweimal etwas nur scheinbar
 * verdrahtet war (decodeStamp, MAX_AGE_MIN) - beides fiel erst auf, als ein
 * Test den ganzen Weg gegangen ist.
 */
class FuseExpectationRecorderTest {

    private val t0 = 1_700_000_000_000L
    private val CFG = "cfg#1"
    private val SEG = 1L
    private val H = 30
    private val MARGE = ExpectationLedger.EXPORT_SAFETY_MARGIN_MGDL
    private val STAMP = InterventionStamp("lauf-A", 7L)

    private class FakeDurability : Durability {

        override fun syncFile(fd: java.io.FileDescriptor) = Unit
        override fun syncDirectory(dir: File) = Unit
    }

    private fun recorder() = FuseExpectationRecorder(FuseExpectationStore(FakeDurability()))

    /** Reine Korrekturlage - alles ausdruecklich belegt. */
    private fun korrektur(sealed: Boolean = true) = ExpectationLedger.Situation(
        mealMarkerActive = false, evidenceEpisodeActive = false, onsetActive = false,
        mealWindow = false, reboundWindow = false, signalHealthy = true, ledgerSealed = sealed,
    )

    private fun probe(ts: Long, mgdl: Double, stamp: InterventionStamp = STAMP) =
        ExpectationLedger.Sample(ts, mgdl, SEG, healthy = true, interventionStamp = stamp, configGeneration = CFG)

    private fun FuseExpectationRecorder.buche(
        dir: File,
        nowTs: Long,
        sourceTs: Long,
        situation: ExpectationLedger.Situation? = korrektur(),
        stamp: InterventionStamp = STAMP,
        anchor: Double? = 200.0,
        mean: Double? = 150.0,
        samples: List<ExpectationLedger.Sample> = emptyList(),
    ) = record(
        dir = dir, nowTs = nowTs, situation = situation, stamp = stamp, configGeneration = CFG,
        segmentId = SEG, sourceTs = sourceTs, anchorMgdl = anchor, meanPredictedMgdl = mean,
        horizonMin = H, safetyLowerPredictedMgdl = 40.0, lambda = null, samples = samples,
    )

    // ---- Die Kette ------------------------------------------------------

    /**
     * EINREIHEN, ABRECHNEN, FORTSCHREIBEN - und der Zustand ueberlebt den
     * Prozess.
     */
    @Test
    fun `eine Prognose wird eingereiht, faellig abgerechnet und persistiert`(@TempDir dir: File) {
        val r = recorder()
        assertTrue(r.loadOnce(dir, STAMP))

        val erg = r.buche(dir, nowTs = t0, sourceTs = t0)
        assertTrue(erg is FuseExpectationRecorder.Result.Recorded, "$erg")
        assertTrue((erg as FuseExpectationRecorder.Result.Recorded).issued, "eingereiht")
        assertTrue(erg.persisted, "und geschrieben")
        assertEquals(1, r.state.entries.size)

        // Faelligkeit: die Senkung ist ausgeblieben.
        val faellig = t0 + H * 60_000L
        val erg2 = r.buche(
            dir, nowTs = faellig + 1000L, sourceTs = faellig,
            samples = listOf(probe(faellig, 205.0)),
        )
        assertEquals(1, (erg2 as FuseExpectationRecorder.Result.Recorded).settled, "abgerechnet")
        assertEquals(
            ExpectationLedger.Verdict.MISSED, r.state.outcomes.single().verdict,
            "205 statt 150 - die behauptete Senkung blieb aus",
        )

        // Ein frischer Prozess liest denselben Stand.
        val neu = recorder()
        assertTrue(neu.loadOnce(dir, STAMP))
        assertEquals(1, neu.state.outcomes.size, "der Zustand hat den Prozess ueberlebt")
    }

    /**
     * OHNE SIEGEL ENTSTEHT KEINE lambda-FAEHIGE ERWARTUNG.
     *
     * Eingereiht wird trotzdem - der Eintrag ist als EXCLUDED erkennbar und
     * zeigt im Export, dass in dieser Zeit gemessen, aber nichts belegt wurde.
     * Nur eben kein Nachweis.
     */
    @Test
    fun `bei unversiegelbarem Ledger entsteht nur ein ausgeschlossener Eintrag`(@TempDir dir: File) {
        val r = recorder()
        r.loadOnce(dir, STAMP)
        r.buche(dir, nowTs = t0, sourceTs = t0, situation = korrektur(sealed = false))
        assertEquals(
            ExpectationLedger.ExpectationContext.EXCLUDED, r.state.entries.single().context,
        )
        assertEquals(
            ExpectationLedger.ContextReason.LEDGER_UNSEALED, r.state.entries.single().contextReason,
        )
    }

    /** OHNE LAGE WIRD NICHTS EINGEREIHT - `null` heisst unbekannt, nicht egal. */
    @Test
    fun `ohne Lage wird nichts eingereiht`(@TempDir dir: File) {
        val r = recorder()
        r.loadOnce(dir, STAMP)
        val erg = r.buche(dir, nowTs = t0, sourceTs = t0, situation = null)
        assertFalse((erg as FuseExpectationRecorder.Result.Recorded).issued)
        assertTrue(r.state.entries.isEmpty())
    }

    /** Ohne Bahn ebenso - abgerechnet wird trotzdem. */
    @Test
    fun `ohne Prognose wird nur abgerechnet`(@TempDir dir: File) {
        val r = recorder()
        r.loadOnce(dir, STAMP)
        r.buche(dir, nowTs = t0, sourceTs = t0)
        val faellig = t0 + H * 60_000L
        val erg = r.buche(
            dir, nowTs = faellig + 1000L, sourceTs = faellig, mean = null,
            samples = listOf(probe(faellig, 205.0)),
        )
        assertFalse((erg as FuseExpectationRecorder.Result.Recorded).issued, "nichts eingereiht")
        assertEquals(1, erg.settled, "aber abgerechnet")
    }

    /** Ein ungueltiger Stempel oder eine fehlende Kennung sperrt - mit Grund. */
    @Test
    fun `ohne gueltige Herkunft wird uebersprungen`(@TempDir dir: File) {
        val r = recorder()
        r.loadOnce(dir, STAMP)
        assertTrue(
            r.buche(dir, t0, t0, stamp = InterventionStamp("", 0L)) is FuseExpectationRecorder.Result.Skipped,
        )
        val ohneCfg = r.record(
            dir = dir, nowTs = t0, situation = korrektur(), stamp = STAMP, configGeneration = "",
            segmentId = SEG, sourceTs = t0, anchorMgdl = 200.0, meanPredictedMgdl = 150.0,
            horizonMin = H, safetyLowerPredictedMgdl = 40.0, lambda = null, samples = emptyList(),
        )
        assertTrue(ohneCfg is FuseExpectationRecorder.Result.Skipped, "$ohneCfg")
    }

    /**
     * DER RECORDER WIRFT NIE.
     *
     * Er laeuft im Zyklus hinter der Publikation. Ein Wurf dort koennte den
     * Rest des Zyklus kosten - fuer eine reine Messung ein unvertretbarer
     * Preis.
     */
    @Test
    fun `ein unbeschreibbares Verzeichnis wirft nicht`(@TempDir parent: File) {
        val blockiert = File(parent, "datei-statt-verzeichnis").also { it.writeText("x") }
        val dir = File(blockiert, "unter")
        val r = recorder()
        r.loadOnce(dir, STAMP)
        val erg = r.buche(dir, t0, t0)
        // Gebucht im Speicher, nur nicht geschrieben - und das steht dran.
        assertFalse((erg as FuseExpectationRecorder.Result.Recorded).persisted)
    }

    // ---- Der Exportschnappschuss ----------------------------------------

    /**
     * DER EXPORT TRENNT DIE DREI KONTEXTE - das ist sein ganzer Zweck.
     */
    @Test
    fun `der Schnappschuss zaehlt nach Kontext getrennt`(@TempDir dir: File) {
        val r = recorder()
        r.loadOnce(dir, STAMP)
        // Eine Korrektur- und eine Mahlzeitenerwartung, beide faellig.
        r.buche(dir, nowTs = t0, sourceTs = t0)
        r.buche(
            dir, nowTs = t0 + 60_000L, sourceTs = t0 + 60_000L,
            situation = korrektur().copy(mealMarkerActive = true),
        )
        val faellig = t0 + H * 60_000L
        r.buche(
            dir, nowTs = faellig + 61_000L, sourceTs = faellig + 60_000L, mean = null,
            samples = listOf(probe(faellig, 205.0), probe(faellig + 60_000L, 206.0)),
        )

        val snap = r.exportSnapshot(faellig + 61_000L, STAMP, CFG, SEG, korrektur(), MARGE)
        assertEquals(1, snap.byContext["CORRECTION"], "eine Korrektur")
        assertEquals(1, snap.byContext["MEAL"], "eine Mahlzeit")
        assertEquals(2, snap.byVerdict["MISSED"])
    }

    /**
     * NUR `eligible` DARF JE EINE ADAPTION TRAGEN - und es ist false, sobald
     * die Lage nicht mehr passt.
     */
    @Test
    fun `der Schnappschuss meldet die aktuelle Strecke als unzulaessig, wenn die Lage kippt`(@TempDir dir: File) {
        val r = recorder()
        r.loadOnce(dir, STAMP)
        val snap = r.exportSnapshot(
            t0, STAMP, CFG, SEG, korrektur().copy(mealMarkerActive = true), MARGE,
        )
        assertFalse(snap.current.eligible)
        assertEquals(ExpectationLedger.Denial.CONTEXT_NOT_CORRECTION, snap.current.denialReason)
        assertEquals(ExpectationLedger.ContextReason.MARKER_ACTIVE, snap.current.currentContextReason)
    }

    /**
     * Und ohne bekannte Lage ebenso - nicht etwa "zulaessig, weil nichts
     * dagegen spricht".
     *
     * GEPRUEFT WIRD DER GRUND, nicht nur `eligible`: bei leerem Zustand waere
     * `eligible` ohnehin false (es gibt gar keine Strecke), und der Test
     * bliebe gruen, auch wenn die fehlende Lage stillschweigend als reine
     * Korrektur durchginge. Genau diese Stumpfheit hat eine Mutationsprobe
     * gezeigt.
     */
    @Test
    fun `ohne Lage ist die aktuelle Strecke unzulaessig`(@TempDir dir: File) {
        val r = recorder()
        r.loadOnce(dir, STAMP)
        val current = r.exportSnapshot(t0, STAMP, CFG, SEG, null, MARGE).current
        assertFalse(current.eligible)
        assertEquals(
            ExpectationLedger.Denial.CONTEXT_NOT_CORRECTION, current.denialReason,
            "die unbekannte Lage ist der Grund - nicht das Fehlen von Ergebnissen",
        )
    }
}

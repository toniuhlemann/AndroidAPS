package app.aaps.fuse.plugin.expectation

import app.aaps.fuse.core.controller.EvidenceStock
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
        mealMarkerActive = false, evidencePhase = EvidenceStock.Phase.DORMANT, onsetActive = false,
        mealWindow = false, reboundWindow = false, signalHealthy = true, ledgerSealed = sealed,
    )

    private fun probe(
        ts: Long,
        mgdl: Double,
        stamp: InterventionStamp = STAMP,
        ktx: ExpectationLedger.ExpectationContext = ExpectationLedger.ExpectationContext.CORRECTION,
    ) = ExpectationLedger.Sample(
        ts, mgdl, SEG, healthy = true, interventionStamp = stamp,
        configGeneration = CFG, context = ktx,
    )

    private fun FuseExpectationRecorder.buche(
        dir: File,
        nowTs: Long,
        sourceTs: Long,
        situation: ExpectationLedger.Situation? = korrektur(),
        stamp: InterventionStamp = STAMP,
        anchor: Double? = 200.0,
        mean: Double? = 150.0,
        samples: List<ExpectationLedger.Sample> = emptyList(),
    ): FuseExpectationRecorder.Telemetry {
        // submit ist asynchron - im Test wird ausdruecklich gewartet, im
        // Betrieb NIE. Genau das ist der Unterschied, um den es geht.
        submit(
            FuseExpectationRecorder.Snapshot(
                dir = dir, nowTs = nowTs, situation = situation, stamp = stamp,
                configGeneration = CFG, segmentId = SEG, sourceTs = sourceTs,
                anchorMgdl = anchor, meanPredictedMgdl = mean, horizonMin = H,
                safetyLowerPredictedMgdl = 40.0, lambda = null, samples = samples,
            ),
        )
        awaitIdleForTest()
        return telemetry
    }

    // ---- Die Kette ------------------------------------------------------

    /**
     * EINREIHEN, ABRECHNEN, FORTSCHREIBEN - und der Zustand ueberlebt den
     * Prozess.
     */
    @Test
    fun `eine Prognose wird eingereiht, faellig abgerechnet und persistiert`(@TempDir dir: File) {
        val r = recorder()

        val erg = r.buche(dir, nowTs = t0, sourceTs = t0)
        assertTrue(erg.lastResult.startsWith("RECORDED"), erg.lastResult)
        assertTrue(erg.lastResult.contains("issued=true"), erg.lastResult)
        assertTrue(erg.lastResult.contains("persisted=true"), erg.lastResult)
        assertEquals(1, r.persistedState.entries.size)

        // Faelligkeit: die Senkung ist ausgeblieben.
        val faellig = t0 + H * 60_000L
        val erg2 = r.buche(
            dir, nowTs = faellig + 1000L, sourceTs = faellig,
            samples = listOf(probe(faellig, 205.0)),
        )
        assertTrue(erg2.lastResult.contains("settled=1"), erg2.lastResult)
        assertEquals(
            ExpectationLedger.Verdict.MISSED, r.persistedState.outcomes.single().verdict,
            "205 statt 150 - die behauptete Senkung blieb aus",
        )

        // Ein frischer Prozess liest denselben Stand. Geladen wird beim ERSTEN
        // Schnappschuss - der Recorder beruehrt die Platte nicht, bevor er
        // etwas zu tun hat.
        val neu = recorder()
        neu.buche(dir, nowTs = faellig + 120_000L, sourceTs = faellig + 120_000L, mean = null)
        assertEquals(1, neu.persistedState.outcomes.size, "der Zustand hat den Prozess ueberlebt")
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
        r.buche(dir, nowTs = t0, sourceTs = t0, situation = korrektur(sealed = false))
        assertEquals(
            ExpectationLedger.ExpectationContext.EXCLUDED, r.persistedState.entries.single().context,
        )
        assertEquals(
            ExpectationLedger.ContextReason.LEDGER_UNSEALED, r.persistedState.entries.single().contextReason,
        )
    }

    /** OHNE LAGE WIRD NICHTS EINGEREIHT - `null` heisst unbekannt, nicht egal. */
    @Test
    fun `ohne Lage wird nichts eingereiht`(@TempDir dir: File) {
        val r = recorder()
        val erg = r.buche(dir, nowTs = t0, sourceTs = t0, situation = null)
        assertTrue(erg.lastResult.contains("issued=false"), erg.lastResult)
        assertTrue(r.persistedState.entries.isEmpty())
    }

    /** Ohne Bahn ebenso - abgerechnet wird trotzdem. */
    @Test
    fun `ohne Prognose wird nur abgerechnet`(@TempDir dir: File) {
        val r = recorder()
        r.buche(dir, nowTs = t0, sourceTs = t0)
        val faellig = t0 + H * 60_000L
        val erg = r.buche(
            dir, nowTs = faellig + 1000L, sourceTs = faellig, mean = null,
            samples = listOf(probe(faellig, 205.0)),
        )
        assertTrue(erg.lastResult.contains("issued=false"), erg.lastResult)
        assertTrue(erg.lastResult.contains("settled=1"), erg.lastResult)
    }

    /** Ein ungueltiger Stempel oder eine fehlende Kennung sperrt - mit Grund. */
    @Test
    fun `ohne gueltige Herkunft wird uebersprungen`(@TempDir dir: File) {
        val r = recorder()
        assertTrue(
            r.buche(dir, t0, t0, stamp = InterventionStamp("", 0L)).lastResult.startsWith("SKIPPED"),
        )
        r.submit(
            FuseExpectationRecorder.Snapshot(
                dir = dir, nowTs = t0, situation = korrektur(), stamp = STAMP,
                configGeneration = "", segmentId = SEG, sourceTs = t0,
                anchorMgdl = 200.0, meanPredictedMgdl = 150.0, horizonMin = H,
                safetyLowerPredictedMgdl = 40.0, lambda = null, samples = emptyList(),
            ),
        )
        r.awaitIdleForTest()
        assertTrue(r.telemetry.lastResult.startsWith("SKIPPED"), r.telemetry.lastResult)
    }

    // ---- Die Entkopplung vom Loop-Thread --------------------------------

    /**
     * DER LOOP WARTET NIE - auch nicht bei vollem Rueckstau.
     *
     * Das ist die Zusicherung, um die es beim ganzen Umbau geht (Toni 18.08.:
     * "niemals den Loop warten lassen"). Geprueft wird sie an der Grenze: mehr
     * Zyklen als die Schlange fasst, alle nacheinander uebergeben, ohne dass
     * der Worker Zeit zum Abarbeiten hat.
     */
    @Test
    fun `submit kehrt auch bei vollem Rueckstau sofort zurueck`(@TempDir dir: File) {
        val r = FuseExpectationRecorder(FuseExpectationStore(FakeDurability()), queueCapacity = 2)
        val start = System.nanoTime()
        var verworfen = 0
        repeat(50) { i ->
            val ok = r.submit(
                FuseExpectationRecorder.Snapshot(
                    dir = dir, nowTs = t0 + i * 60_000L, situation = korrektur(), stamp = STAMP,
                    configGeneration = CFG, segmentId = SEG, sourceTs = t0 + i * 60_000L,
                    anchorMgdl = 200.0, meanPredictedMgdl = 150.0, horizonMin = H,
                    safetyLowerPredictedMgdl = 40.0, lambda = null, samples = emptyList(),
                ),
            )
            if (!ok) verworfen++
        }
        val dauerMs = (System.nanoTime() - start) / 1_000_000L
        // 50 Uebergaben muessen zusammen unter einem einzigen Schreibvorgang
        // bleiben. Die Schranke ist grosszuegig; sie faellt sofort, wenn hier
        // wieder synchron geschrieben wuerde.
        assertTrue(dauerMs < 200, "50 submits brauchten $dauerMs ms - das darf nie blockieren")
        assertTrue(verworfen > 0, "bei Kapazitaet 2 MUESSEN Zyklen verworfen werden")
        assertEquals(
            verworfen.toLong(), r.telemetry.dropped,
            "und jeder verworfene Zyklus wird gezaehlt - sonst waere die Luecke unsichtbar",
        )
    }

    /**
     * DIE AUSWERTUNG LIEST NUR GESCHRIEBENES.
     *
     * Vor dem ersten erfolgreichen Schreibvorgang ist der ausgewertete Zustand
     * leer - nicht etwa der Zwischenstand des Workers. Ein Nachweis aus
     * ungeschriebenen Ergebnissen saehe nach einem Prozesstod anders aus als
     * vorher.
     */
    @Test
    fun `vor dem ersten Schreibvorgang ist der ausgewertete Zustand leer`(@TempDir dir: File) {
        val r = recorder()
        assertTrue(r.persistedState.isEmpty, "noch nichts geschrieben")
        assertEquals(0L, r.telemetry.asOfTs, "und kein Stand ausgewiesen")
        r.buche(dir, nowTs = t0, sourceTs = t0)
        assertEquals(t0, r.telemetry.asOfTs, "nach dem Schreiben steht der Stand")
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
        val erg = r.buche(dir, t0, t0)
        // Gebucht im Speicher, nur nicht geschrieben - und das steht dran.
        assertTrue(erg.lastResult.contains("persisted=false"), erg.lastResult)
    }

    /**
     * EIN GESCHEITERTER SCHREIBVORGANG DARF NICHTS AUSWERTBAR MACHEN.
     *
     * Der Worker hat den Eintrag im Zwischenstand - aber auf Platte steht er
     * nicht. Wuerde die Auswertung ihn trotzdem sehen, entstuende eine
     * Strecke, die nach einem Prozesstod anders aussieht als vorher. Genau
     * das ist die Bauform, die Toni am 18.08. verlangt hat: "aktuelle
     * Lambda-Evidenz ausschliesslich aus dem zuletzt erfolgreich
     * persistierten Zustand bilden."
     */
    @Test
    fun `ein gescheiterter Schreibvorgang macht nichts auswertbar`(@TempDir parent: File) {
        val blockiert = File(parent, "datei-statt-verzeichnis").also { it.writeText("x") }
        val dir = File(blockiert, "unter")
        val r = recorder()
        r.buche(dir, t0, t0)
        assertTrue(
            r.persistedState.isEmpty,
            "der Zwischenstand des Workers darf NICHT in die Auswertung gelangen",
        )
        assertEquals(0L, r.telemetry.asOfTs, "und es wird kein Stand ausgewiesen")
    }

    // ---- Der Exportschnappschuss ----------------------------------------

    /**
     * DER EXPORT TRENNT DIE DREI KONTEXTE - das ist sein ganzer Zweck.
     */
    @Test
    fun `der Schnappschuss zaehlt nach Kontext getrennt`(@TempDir dir: File) {
        val r = recorder()
        // Eine Korrektur- und eine Mahlzeitenerwartung.
        r.buche(dir, nowTs = t0, sourceTs = t0)
        r.buche(
            dir, nowTs = t0 + 60_000L, sourceTs = t0 + 60_000L,
            situation = korrektur().copy(mealMarkerActive = true),
        )
        // JEDE wird gegen einen Messpunkt IHRER Lage abgerechnet. Zwei
        // Zyklen, weil in EINEM Zyklus nur EINE Lage gelten kann - genau
        // deshalb traegt der Messpunkt jetzt seinen eigenen Kontext.
        val faellig = t0 + H * 60_000L
        r.buche(
            dir, nowTs = faellig + 1_000L, sourceTs = faellig, mean = null,
            samples = listOf(probe(faellig, 205.0)),
        )
        r.buche(
            dir, nowTs = faellig + 61_000L, sourceTs = faellig + 60_000L, mean = null,
            samples = listOf(
                probe(faellig + 60_000L, 206.0, ktx = ExpectationLedger.ExpectationContext.MEAL),
            ),
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
        val current = r.exportSnapshot(t0, STAMP, CFG, SEG, null, MARGE).current
        assertFalse(current.eligible)
        assertEquals(
            ExpectationLedger.Denial.CONTEXT_NOT_CORRECTION, current.denialReason,
            "die unbekannte Lage ist der Grund - nicht das Fehlen von Ergebnissen",
        )
    }

    // ---- Kontextwechsel zwischen Ausgabe und Faelligkeit -----------------

    /**
     * DAS LOCH, DAS DER STEMPEL NICHT SCHLIESST (Toni 18.08.).
     *
     * Beginnt zwischen einer Korrekturprognose und ihrer Faelligkeit eine
     * Mahlzeit, ist der Interventionsstempel unveraendert - solange nichts
     * publiziert wurde. Ohne den Kontext am Messpunkt ginge der ausgebliebene
     * Rueckgang als MISSED durch und spaeter als Beleg gegen das Modell,
     * obwohl in Wahrheit Kohlenhydrate wirkten.
     */
    @Test
    fun `eine Mahlzeit zwischen Ausgabe und Faelligkeit erzeugt keinen MISSED`(@TempDir dir: File) {
        val r = recorder()
        r.buche(dir, nowTs = t0, sourceTs = t0)
        val faellig = t0 + H * 60_000L
        r.buche(
            dir, nowTs = faellig + 1000L, sourceTs = faellig, mean = null,
            // DERSELBE Stempel - es wurde nichts publiziert. Nur die Lage ist
            // eine andere.
            samples = listOf(probe(faellig, 205.0, ktx = ExpectationLedger.ExpectationContext.MEAL)),
        )
        assertEquals(
            ExpectationLedger.Verdict.CONTEXT_CHANGED, r.persistedState.outcomes.single().verdict,
            "kein Beleg - weder dafuer noch dagegen",
        )
    }

    /** Und dasselbe fuer eine ausgeschlossene Lage am Messpunkt. */
    @Test
    fun `ein Signalbruch zwischen Ausgabe und Faelligkeit erzeugt keinen MISSED`(@TempDir dir: File) {
        val r = recorder()
        r.buche(dir, nowTs = t0, sourceTs = t0)
        val faellig = t0 + H * 60_000L
        r.buche(
            dir, nowTs = faellig + 1000L, sourceTs = faellig, mean = null,
            samples = listOf(probe(faellig, 205.0, ktx = ExpectationLedger.ExpectationContext.EXCLUDED)),
        )
        assertEquals(ExpectationLedger.Verdict.CONTEXT_CHANGED, r.persistedState.outcomes.single().verdict)
    }

    // ---- Die Evidenzphase entscheidet, nicht die Episode -----------------

    /**
     * EINE OFFENE EPISODE IN DORMANT IST KORREKTURBETRIEB (Toni 18.08.).
     *
     * Das ist der Kernbefund der Spezifikation: DORMANT ist der Normalzustand
     * ZWISCHEN zwei Wellen. Aus der blossen Episodenidentitaet sechs Stunden
     * Mahlzeit abzuleiten hiesse, genau den haeufigsten Korrekturzustand nie
     * zu messen.
     */
    @Test
    fun `DORMANT bei offener Episode ergibt CORRECTION`(@TempDir dir: File) {
        val r = recorder()
        r.buche(
            dir, nowTs = t0, sourceTs = t0,
            situation = korrektur().copy(evidencePhase = EvidenceStock.Phase.DORMANT),
        )
        assertEquals(
            ExpectationLedger.ExpectationContext.CORRECTION, r.persistedState.entries.single().context,
        )
    }

    /** Die zweite Welle kippt im SELBEN Zyklus zurueck auf MEAL - ohne neue
     *  Episode und ohne neues Budget. */
    @Test
    fun `neue Evidenz kippt DORMANT sofort zurueck auf MEAL`(@TempDir dir: File) {
        val r = recorder()
        for ((phase, erwartet) in listOf(
            EvidenceStock.Phase.PENDING_SEAL to ExpectationLedger.ExpectationContext.MEAL,
            EvidenceStock.Phase.ACTIVE to ExpectationLedger.ExpectationContext.MEAL,
            EvidenceStock.Phase.NONE to ExpectationLedger.ExpectationContext.CORRECTION,
            EvidenceStock.Phase.EXPIRED to ExpectationLedger.ExpectationContext.CORRECTION,
            EvidenceStock.Phase.SUSPENDED to ExpectationLedger.ExpectationContext.EXCLUDED,
            EvidenceStock.Phase.UNKNOWN to ExpectationLedger.ExpectationContext.EXCLUDED,
        )) {
            val lage = korrektur().copy(evidencePhase = phase)
            assertEquals(erwartet, ExpectationLedger.classify(lage).context, "$phase")
        }
    }
}

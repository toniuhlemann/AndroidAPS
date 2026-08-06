package app.aaps.fuse.plugin.export

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.TailLiability
import app.aaps.fuse.core.observer.ActivityValidity
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.signal.PairSlopeBand
import app.aaps.fuse.core.signal.SignalWindow
import app.aaps.fuse.plugin.FuseCycleRunner
import app.aaps.fuse.plugin.FusePumpGate
import app.aaps.fuse.plugin.FuseSignalSource
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FuseStateExportTest {

    private val BUILD = FuseStateJson.Build("3.4.2.5+fuse0.1.0-toni", "abc1234", true)

    private val cfg = FuseCycleRunner.Config(
        smbRatio = 0.2, smbRatioRise = 0.35, riseRampLowR = 0.5, riseRampHighR = 2.0, maxSmbU = 0.3, guardFloorMgdl = 70.0, iobThPercent = 100,
        releaseHorizonMin = 30, liabilityHorizonMin = 120, driveTauMin = 60,
        driveLowerQuantilePct = 50, tailGuardEnabled = false, tailFloorMgdl = 70.0, tailRecoveryU = 0.0, fastRestraintEnabled = true,
    )

    private fun signal() = FuseSignalSource.Signal(
        sourceTs = 1_700_000_000_000L, rawBg = 132.0, q1 = 130.0, rSigned = 0.8,
        ukfRatePerMin = 1.1, rawSlopePerMin = 1.4,
        adjusted = emptyList(), activity = ActivityValidity.VALID,
        samplesUsed = 19, rawSeriesSize = 200, q1Outlier = false,
        boundedBy = SignalWindow.Bound.NONE, windowFromTs = 1_699_988_120_000L,
    )

    private fun step() = app.aaps.fuse.core.observer.ObserverStep(
        accepted = true, health = Health.READY, healthReasons = emptySet(),
        safetyReasons = emptySet(), phase = app.aaps.fuse.core.observer.Phase.RISE_ACTIVE,
        transition = app.aaps.fuse.core.observer.Transition(
            type = app.aaps.fuse.core.observer.TransitionType.RISE_CONFIRMED,
            from = app.aaps.fuse.core.observer.Phase.CANDIDATE,
            to = app.aaps.fuse.core.observer.Phase.RISE_ACTIVE,
            reasons = setOf("confirmed"), triggerSourceTs = 1_700_000_000_000L,
            triggerComputeTs = 1_700_000_030_000L, candidateId = "c1", eventId = "e1",
        ),
        candidateId = "c1", eventId = "e1",
        livePeak = app.aaps.fuse.core.observer.Peak(1_700_000_000_000L, 150.0),
        quietAccumMin = 0.0, confirmCount = 2, carryDurMin = 0.0, resetCauses = emptySet(),
    )

    private fun outcome(
        abort: String? = null,
        policy: FuseCycleRunner.Config? = cfg,
        signal: FuseSignalSource.Signal? = signal(),
        tail: TailLiability.Report? = null,
        step: app.aaps.fuse.core.observer.ObserverStep? = step(),
    ) = FuseCycleRunner.Outcome(
        decision = FuseController.Decision(
            0.15, FuseController.TbrAction.KEEP_CURRENT, FuseController.Block.NONE,
            1.4, 180.0, 95.0, "smbRatio", tail, if (tail != null) 0.05 else 0.0,
        ),
        tbr = null, prediction = null, sourceTs = signal?.sourceTs, computeTs = 1_700_000_030_000L,
        health = Health.READY, gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "VirtualPumpPlugin"),
        reason = "KEEP", alarm = false, bgMgdl = 130.0, targetMgdl = 97.0, targetSource = "profile",
        signal = signal, band = PairSlopeBand.Estimate(0.8, 0.4, 153), policy = policy,
        state = null, step = step, sensorEpoch = 1_699_000_000_000L, calibrationEpoch = 0L,
        isfMgdlPerU = 85.0, iobU = 1.2, abortReason = abort,
    )

    private fun rt(units: Double? = 0.15) = RT(
        algorithm = APSResult.Algorithm.FUSE, timestamp = 1_700_000_030_000L,
        rate = null, duration = null, units = units, deliverAt = units?.let { 1_700_000_030_000L },
    )

    private fun record(o: FuseCycleRunner.Outcome = outcome(), r: RT = rt()) =
        FuseStateJson.record("s#1", o, r, o.policy, BUILD, 0L, null) { 5_000_000L }

    // ---- Der wichtigste Test: nichts wird vorgetaeuscht -------------------

    /**
     * Ein Datensatz, der vollstaendig AUSSIEHT, wuerde als Freigabe gelesen.
     * Solange der Ledger keine Aufrufstelle hat, muss das Gegenteil in den
     * Daten stehen — nicht in einer Fussnote.
     */
    @Test
    fun `der Datensatz erklaert sich selbst fuer unvollstaendig`() {
        val j = record()
        assertFalse(j.getBoolean("r89Complete"))
        assertEquals(JSONObject.NULL, j.get("ledger"))
        val gaps = j.getJSONArray("gaps")
        val fields = (0 until gaps.length()).map { gaps.getJSONObject(it).getString("field") }
        val reasons = (0 until gaps.length()).map { gaps.getJSONObject(it).getString("reason") }
        assertTrue(fields.contains("ledger.grossLiabilityU"))
        assertTrue(fields.contains("ledger.accountedU"))
        assertTrue(fields.contains("ledger.residualU"))
        assertTrue(reasons.contains(FuseStateJson.GAP_NO_LEDGER))
    }

    /** Die vier Felder, ueber die AAPS ueberhaupt aktuiert (R89). */
    @Test
    fun `die vier Aktuatorfelder stehen vollstaendig im Datensatz`() {
        val j = record().getJSONObject("rt")
        for (k in listOf("rate", "duration", "units", "deliverAt")) assertTrue(j.has(k)) { "$k fehlt" }
        assertEquals(0.15, j.getDouble("units"), 1e-12)
        // null heisst "nichts angefordert" und nicht "unbekannt".
        assertEquals(JSONObject.NULL, j.get("rate"))
    }

    @Test
    fun `Entscheidung, Blockgrund und bindende Grenze sind benannt`() {
        val d = record().getJSONObject("decision")
        assertEquals("NONE", d.getString("block"))
        assertEquals("smbRatio", d.getString("bindingLimit"))
        assertEquals("KEEP_CURRENT", d.getString("tbr"))
        assertEquals(0.15, d.getDouble("smbU"), 1e-12)
    }

    @Test
    fun `das Gate-Verdikt steht mit Pumpenklasse im Datensatz`() {
        val g = record().getJSONObject("gate")
        assertEquals("ALLOWED", g.getString("verdict"))
        assertTrue(g.getBoolean("allowed"))
        assertEquals("VirtualPumpPlugin", g.getString("pumpClass"))
    }

    /** Fehlt die Pumpe, ist `pumpClass` der Sentinel "none" und KEIN
     *  Klassenname — wer danach sucht, sucht sonst vergeblich. */
    @Test
    fun `ohne Pumpe ist pumpClass der Sentinel none`() {
        val o = outcome().copy(gate = FusePumpGate.evaluate(null))
        assertEquals("none", record(o).getJSONObject("gate").getString("pumpClass"))
    }

    // ---- Politik ---------------------------------------------------------

    @Test
    fun `der Politik-Hash aendert sich mit jeder Stellgroesse`() {
        val h = FuseStateJson.hashOf(cfg)!!
        assertTrue(FuseStateJson.hashOf(cfg.copy(smbRatio = 0.21)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(driveTauMin = 61)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(tailGuardEnabled = true)) != h)
        assertEquals(h, FuseStateJson.hashOf(cfg.copy()))
    }

    /** `Sha.lossless` WIRFT bei NaN. Der Wurf laege im runCatching des Exports
     *  — der Hash waere danach dauerhaft still weg. Also: kein Hash, aber ein
     *  benannter Grund. */
    @Test
    fun `ein nicht-endlicher Wert ergibt keinen Hash und einen Grund`() {
        val kaputt = cfg.copy(smbRatio = Double.NaN)
        org.junit.jupiter.api.Assertions.assertNull(FuseStateJson.hashOf(kaputt))
        val j = record(outcome(policy = kaputt))
        assertEquals(JSONObject.NULL, j.getJSONObject("policy").get("hash"))
        assertTrue(gapReasons(j).contains(FuseStateJson.GAP_HASH_NOT_FINITE))
    }

    /** Bricht der Zyklus vor dem Lesen der Einstellungen ab, gibt es keine
     *  Politik. Ein still nachgelesener Hash waere die Politik eines anderen
     *  Zeitpunkts. */
    @Test
    fun `ohne gelesene Politik steht der Grund im Datensatz`() {
        val j = record(outcome(abort = "no profile", policy = null, signal = null))
        assertEquals("none", j.getJSONObject("policy").getString("source"))
        assertTrue(gapReasons(j).contains(FuseStateJson.GAP_POLICY_NOT_READ))
    }

    /** Die handgepflegte Regelwerksversion muss sich als solche zu erkennen
     *  geben, sonst liest eine Auswertung einen unveraenderten Wert als Beweis. */
    @Test
    fun `die Regelwerksversion ist als handgepflegt markiert`() {
        assertTrue(record().getJSONObject("policy").getBoolean("ruleSetVersionIsManual"))
    }

    // ---- Abbruchpfade ----------------------------------------------------

    @Test
    fun `ein Abbruchzyklus wird geschrieben und traegt seinen Grund`() {
        val j = record(outcome(abort = "signal: no raw glucose values", signal = null))
        assertEquals("signal: no raw glucose values", j.getString("abortReason"))
        assertEquals(JSONObject.NULL, j.get("sourceTs"))
        assertTrue(gapReasons(j).contains("NO_SIGNAL_THIS_CYCLE"))
    }

    @Test
    fun `Signal und Fenstergrenze stehen im Datensatz`() {
        val s = record().getJSONObject("signal")
        assertEquals(130.0, s.getDouble("q1"), 1e-12)
        assertEquals(19, s.getInt("samplesUsed"))
        assertEquals("NONE", s.getString("boundedBy"))
    }

    @Test
    fun `der Schwanzbericht traegt seinen Unvollstaendigkeitsvermerk`() {
        val t = TailLiability.evaluate(TailLiability.Input(120.0, 0.5, 85.0, 70.0, 0.0))
        val j = record(outcome(tail = t)).getJSONObject("tail")
        assertEquals(TailLiability.COMPLETENESS_STAGE1, j.getString("completeness"))
        assertEquals(TailLiability.SOURCE_BASELINE, j.getString("lowerBgAtHSource"))
    }

    /** Die Schreibdauer ist erst NACH dem Schreiben bekannt — sie steht
     *  zwangslaeufig im naechsten Datensatz. Der erste hat sie nie, und das
     *  darf nicht als 0 gelesen werden. */
    @Test
    fun `die Schreibmetrik hinkt um eins und sagt das`() {
        val erster = record()
        assertEquals(JSONObject.NULL, erster.getJSONObject("export").get("prevWriteMs"))
        assertTrue(gapReasons(erster).contains(FuseStateJson.GAP_METRICS_LAG))

        val zweiter = FuseStateJson.record(
            "s#2", outcome(), rt(), cfg, BUILD, 0L, FuseStateJson.PrevWrite(3L, 1800)
        ) { 5_000_000L }
        assertEquals(3L, zweiter.getJSONObject("export").getLong("prevWriteMs"))
        assertFalse(gapReasons(zweiter).contains(FuseStateJson.GAP_METRICS_LAG))
    }

    // ---- Schreiben -------------------------------------------------------

    @Test
    fun `jeder Zyklus haengt genau eine Zeile an`(@TempDir dir: File) {
        val ex = FuseStateExporter()
        repeat(3) { i -> assertTrue(ex.append(dir, """{"n":$i}""") is FuseStateExporter.Result.Written) }
        val f = File(dir, FuseStateExporter.FILE_NAME)
        assertEquals(3, f.readLines().size)
        assertEquals("""{"n":2}""", f.readLines().last())
    }

    @Test
    fun `die Schreibdauer und die Groesse werden gemessen`(@TempDir dir: File) {
        var t = 0L
        val r = FuseStateExporter().append(dir, "{}") { t += 7_000_000L; t }
        r as FuseStateExporter.Result.Written
        assertEquals(7L, r.writeMs)
        assertEquals(3, r.bytes)   // "{}" + \n
    }

    @Test
    fun `bei Ueberschreiten der Grenze wird rotiert, nicht geloescht`(@TempDir dir: File) {
        val ex = FuseStateExporter(maxBytes = 20L, generations = 2)
        ex.append(dir, "aaaaaaaaaaaaaaaaaaaaaaaa")     // > 20 Byte
        val zweite = ex.append(dir, "b") as FuseStateExporter.Result.Written
        assertTrue(zweite.rotated)
        assertTrue(File(dir, "${FuseStateExporter.FILE_NAME}.1").exists())
        assertEquals("b", File(dir, FuseStateExporter.FILE_NAME).readText().trim())
    }

    /** Der Export darf niemals werfen — er ist Beobachtung, nicht Regelung. */
    @Test
    fun `ein unbeschreibbares Ziel meldet sich, statt zu werfen`(@TempDir dir: File) {
        val blockiert = File(dir, "datei-statt-verzeichnis")
        blockiert.writeText("x")
        val r = FuseStateExporter().append(File(blockiert, "unter"), "{}")
        assertTrue(r is FuseStateExporter.Result.Failed)
        assertNotNull((r as FuseStateExporter.Result.Failed).reason)
    }

    /** Drei Ratenmaasse muessen nebeneinander im Trail stehen - sonst laesst
     *  sich die Traegheit des 18-min-Medians nie beziffern. */
    @Test
    fun `alle drei Ratenmaasse stehen im Datensatz`() {
        val s = record().getJSONObject("signal")
        assertEquals(0.8, s.getDouble("rSigned"), 1e-12)
        assertEquals(1.1, s.getDouble("ukfRatePerMin"), 1e-12)
        assertEquals(1.4, s.getDouble("rawSlopePerMin"), 1e-12)
    }

    // ---- Observer: die Grundlage der spaeteren Nachrechnung ---------------

    @Test
    fun `der Observer-Zustand steht vollstaendig im Datensatz`() {
        val obs = record().getJSONObject("observer")
        assertEquals("RISE_ACTIVE", obs.getString("phase"))
        assertEquals("e1", obs.getString("eventId"))
        assertEquals("c1", obs.getString("candidateId"))
        assertEquals(150.0, obs.getDouble("livePeakValue"), 1e-12)
        assertEquals(2, obs.getInt("confirmCount"))
        assertEquals(1_699_000_000_000L, obs.getLong("sensorEpoch"))
    }

    /** Der ANKER einer Episode: `triggerSourceTs` des Confirms. Ohne ihn ist
     *  spaeter nicht bestimmbar, wogegen bewertet werden soll. */
    @Test
    fun `der Uebergang traegt den Ankerzeitpunkt`() {
        val tr = record().getJSONObject("observer").getJSONObject("transition")
        assertEquals("RISE_CONFIRMED", tr.getString("type"))
        assertEquals(1_700_000_000_000L, tr.getLong("triggerSourceTs"))
        assertEquals("e1", tr.getString("eventId"))
    }

    @Test
    fun `ohne Observer-Schritt steht der Grund statt eines leeren Geruests`() {
        val j = record(outcome(abort = "no profile", policy = null, signal = null, step = null))
        assertFalse(j.has("observer"))
        assertTrue(gapReasons(j).contains("NO_STEP_THIS_CYCLE"))
    }

    /** Ohne Build-Hash ist ein Geraetelauf keinem Commit zuzuordnen. */
    @Test
    fun `der Build steht mit Hash und Sauberkeitsflag im Datensatz`() {
        val b = record().getJSONObject("build")
        assertEquals("3.4.2.5+fuse0.1.0-toni", b.getString("versionName"))
        assertEquals("abc1234", b.getString("head"))
        assertTrue(b.getBoolean("committed"))
    }

    private fun gapReasons(j: JSONObject): List<String> {
        val g = j.getJSONArray("gaps")
        return (0 until g.length()).map { g.getJSONObject(it).getString("reason") }
    }
}

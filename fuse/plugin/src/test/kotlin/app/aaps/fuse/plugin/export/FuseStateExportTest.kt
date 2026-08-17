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

    private val BUILD = FuseStateJson.Build("3.4.2.5+fuse1.0.2-toni", "abc1234", true)

    private val cfg = FuseCycleRunner.Config(
        smbRatio = 0.2, smbRatioRise = 0.35, sharedMaxIobU = 7.0, riseRampLowR = 0.5, riseRampHighR = 2.0, bolusShareLambda = 1.0, onsetChannelEnabled = true, onsetEnvelopeU = 1.5, primeReleaseEnabled = true, primeWindowMin = 15, primeEnvelopeU = 1.2, maxSmbU = 0.3, guardFloorMgdl = 70.0, lowGateMinBenefitMgdl = 5.0, lowGateHorizonMin = 120.0, iobThPercent = 100,
        releaseHorizonMin = 30, liabilityHorizonMin = 120, driveTauMin = 60, absorptionCreditWindowMin = 60, markerBoostMaxMin = 45, nightStartMin = 1380, nightEndMin = 420, nightDeadbandMgdl = 45.0, nightDeadbandEnabled = true, reboundDeadbandMgdl = 25.0, reboundDeadbandEnabled = true,
        driveLowerQuantilePct = 50, tailGuardEnabled = false, conditionalTailEnabled = true, markerAuthorized = false, tailFloorMgdl = 70.0, tailRecoveryU = 0.0, fastRestraintEnabled = true, endZeroWhenReasonGone = true,
    )

    private fun signal() = FuseSignalSource.Signal(
        sourceTs = 1_700_000_000_000L, rawBg = 132.0, q1 = 130.0, rSigned = 0.8,
        ukfRatePerMin = 1.1, ukfLearnedR = 2.2, rawSlopePerMin = 1.4, activityAtAnchor = 0.01, isfAtAnchor = 90.0,
        adjusted = app.aaps.fuse.core.signal.BgiAdjustedSeries.adjust(emptyList()), activity = ActivityValidity.VALID,
        samplesUsed = 19, rawSeriesSize = 200, gapBeforeMin = 1.0, stepFromLastMgdl = -1.0, stepRateActualMgdlPerMin = -1.0, postGapIndex = 18, q1Outlier = false,
        boundedBy = SignalWindow.Bound.NONE, windowFromTs = 1_699_988_120_000L, segmentStartTs = 1_699_988_120_000L,
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
        episodeId: Long = 0L,
        committedU: Double = 0.0,
        episodeMin: Int? = null,
        phase: String? = null,
        stockMgdl: Double? = null,
        reason: String? = null,
        capMin: Int = 360,
        denial: String? = null,
    ) = FuseCycleRunner.Outcome(
        decision = FuseController.Decision(
            0.15, FuseController.TbrAction.KEEP_CURRENT, FuseController.Block.NONE,
            1.4, 180.0, 95.0, "smbRatio", tail, if (tail != null) 0.05 else 0.0,
        ),
        tbr = null, prediction = null, sourceTs = signal?.sourceTs, computeTs = 1_700_000_030_000L,
        health = Health.READY, gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "VirtualPumpPlugin"),
        reason = "KEEP", alarm = false, bgMgdl = 130.0, targetMgdl = 97.0, targetSource = "profile",
        signal = signal, band = PairSlopeBand.Estimate(0.8, 0.4, 153),
        discount = app.aaps.fuse.core.predictor.DriveDiscount.apply(0.8, 0.4, 0.01, 85.0, 1.0),
        onset = app.aaps.fuse.core.controller.OnsetChannel.Result(false, false, null, 1.5, "R_CONFIRMED"),
        prime = app.aaps.fuse.core.controller.PrimeRelease.Plan(false, 0.0, 1.2, "NO_MARKER"),
        candidate = null, candidateGap = null, computeDurationMs = 7L, mealStats = null, policy = policy,
        state = null, step = step, sensorEpoch = 1_699_000_000_000L, calibrationEpoch = 0L,
        isfMgdlPerU = 85.0, iobU = 1.2, abortReason = abort,
        evidenceEpisodeId = episodeId, evidenceCommittedU = committedU, evidenceEpisodeMin = episodeMin,
        evidenceEpisodeCapMin = capMin,
        evidencePhase = phase, evidenceStockMgdl = stockMgdl, evidenceReason = reason,
        evidenceEpisodeDenial = denial,
    )

    private fun rt(units: Double? = 0.15) = RT(
        algorithm = APSResult.Algorithm.FUSE, timestamp = 1_700_000_030_000L,
        rate = null, duration = null, units = units, deliverAt = units?.let { 1_700_000_030_000L },
    )

    private fun record(
        o: FuseCycleRunner.Outcome = outcome(),
        r: RT = rt(),
        gate: FuseStateJson.PublicationGate? = null,
    ) = FuseStateJson.record("s#1", o, r, o.policy, BUILD, 0L, null, publicationGate = gate) { 5_000_000L }

    /**
     * IOBTH NIE VERSTECKEN - AUCH IM ABBRUCHZYKLUS (Toni 17.08.).
     *
     * abort() setzt `state = null`, kennt die beiden Grenzen aber und legt
     * sie in outcome.iobThU/maxIobU. Der AAPS-Tab hatte den Rueckfall
     * (FuseDashboardModel), der Export nicht - am Geraet standen die Grenzen,
     * in der Datei, die der Viewer liest, fehlten sie. Der uebrige Testaufbau
     * verdeckte das strukturell: er baut state=null MIT gesetztem
     * isfMgdlPerU, eine Kombination, die abort() nie erzeugt, und prueft die
     * Grenzen nicht.
     */
    @Test
    fun `die IOB-Grenzen ueberleben den Abbruchzyklus im Export`() {
        val o = outcome(abort = "drive not estimable (1 samples)")
            .copy(iobThU = 8.0, maxIobU = 8.0)
        val state = record(o).getJSONObject("state")
        assertEquals(8.0, state.getDouble("iobThU"), 1e-9, "iobTH nie verstecken - auch nicht im Export")
        assertEquals(8.0, state.getDouble("maxIobU"), 1e-9)
    }

    // ---- B0c: das Publikationsgate im Trail ------------------------------

    /**
     * Der Befund des Publikationsgates muss als DATEN im Datensatz stehen.
     * Vorher lebte der Grund einer Zurueckhaltung nur im `rt.reason`-Text, den
     * der Export gar nicht ausgibt: ein Zyklus, in dem eine gerechnete Menge
     * NICHT hinausging, sah aus wie einer, der keine gerechnet hat.
     */
    @Test
    fun `der Befund des Publikationsgates steht im Datensatz`() {
        val j = record(
            r = rt(units = null),
            gate = FuseStateJson.PublicationGate(
                allowed = false,
                reason = app.aaps.fuse.plugin.ledger.LedgerPublicationGate.REASON_TREATMENT_VIEW_UNAVAILABLE,
                treatmentViewPresent = false,
            ),
        )
        val g = j.getJSONObject("publicationGate")
        assertFalse(g.getBoolean("allowed"))
        assertEquals(
            app.aaps.fuse.plugin.ledger.LedgerPublicationGate.REASON_TREATMENT_VIEW_UNAVAILABLE,
            g.getString("reason"),
        )
        assertFalse(g.getBoolean("treatmentViewPresent"))
    }

    /** Der Regelfall: freigegeben, kein Grund, Vollsicht vorhanden. */
    @Test
    fun `ein freigegebener Zyklus nennt keinen Grund und hat die Vollsicht`() {
        val j = record(gate = FuseStateJson.PublicationGate(allowed = true, reason = null, treatmentViewPresent = true))
        val g = j.getJSONObject("publicationGate")
        assertTrue(g.getBoolean("allowed"))
        assertEquals(JSONObject.NULL, g.get("reason"))
        assertTrue(g.getBoolean("treatmentViewPresent"))
    }

    /** Und wer den Befund nicht meldet, bekommt eine Luecke - keine erfundene
     *  Freigabe. */
    @Test
    fun `ohne gemeldetes Publikationsgate steht eine Luecke im Datensatz`() {
        val j = record()
        assertFalse(j.has("publicationGate"))
        val gaps = j.getJSONArray("gaps")
        val fields = (0 until gaps.length()).map { gaps.getJSONObject(it).getString("field") }
        assertTrue(fields.contains("publicationGate"))
    }

    // ---- Der wichtigste Test: nichts wird vorgetaeuscht -------------------

    /**
     * Ein Datensatz, der vollstaendig AUSSIEHT, wuerde als Freigabe gelesen.
     * Fehlt die Ledger-Sicht (alter Aufrufer, Adapterfehler), muss das
     * Gegenteil in den Daten stehen — nicht in einer Fussnote.
     */
    @Test
    fun `ohne Ledger-Sicht erklaert sich der Datensatz fuer unvollstaendig`() {
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

    /** Seit v7 (R95 Fix 3): MIT Ledger-Sicht steht die R89-Mengenbilanz im
     *  Datensatz, die Luecken entfallen, r89Complete kippt auf true. */
    @Test
    fun `mit Ledger-Sicht steht die Mengenbilanz im Datensatz`() {
        val lcfg = app.aaps.fuse.core.ledger.LedgerConfig(bolusStepU = 0.05)
        val state = app.aaps.fuse.core.ledger.LedgerReducer.reduceAll(
            app.aaps.fuse.core.ledger.LedgerState(),
            listOf(
                app.aaps.fuse.core.ledger.LedgerEvent.Proposed("s#1", 0.30, 1_700_000_000_000L, 0L),
                app.aaps.fuse.core.ledger.LedgerEvent.AmountObserved(
                    "s#1", app.aaps.fuse.core.ledger.AmountStage.RT_PUBLISHED, 0.30
                ),
            ),
            lcfg,
        )
        val j = FuseStateJson.record(
            "s#1", outcome(), rt(), cfg, BUILD, 0L, null,
            ledger = FuseStateJson.LedgerSnapshot(revision = 2L, state = state),
        ) { 5_000_000L }

        assertTrue(j.getBoolean("r89Complete"))
        val l = j.getJSONObject("ledger")
        assertEquals(2L, l.getLong("revision"))
        assertEquals(0.30, l.getDouble("transportCommitmentU"), 1e-12)
        assertEquals(0.30, l.getDouble("grossLiabilityU"), 1e-12)
        assertEquals(0.0, l.getDouble("accountedU"), 1e-12)
        assertEquals(0.30, l.getDouble("residualU"), 1e-12)
        assertFalse(l.getBoolean("hold"))
        val open = l.getJSONArray("openEntries")
        assertEquals(1, open.length())
        assertEquals("s#1", open.getJSONObject(0).getString("proposalId"))
        assertEquals("PUBLISHED", open.getJSONObject(0).getString("phase"))
        assertFalse(gapReasons(j).contains(FuseStateJson.GAP_NO_LEDGER))
    }

    /** Aktive Fehler muessen im Trail stehen - ein Hold ohne sichtbaren Grund
     *  wirkt wie ein defektes FUSE. */
    @Test
    fun `ein aktiver Ledger-Fehler steht mit Hold im Datensatz`() {
        val lcfg = app.aaps.fuse.core.ledger.LedgerConfig(bolusStepU = 0.05)
        val state = app.aaps.fuse.core.ledger.LedgerReducer.reduce(
            app.aaps.fuse.core.ledger.LedgerState(),
            // Ereignis zu einer nie vorgeschlagenen Id -> UNKNOWN_PROPOSAL.
            app.aaps.fuse.core.ledger.LedgerEvent.AmountObserved(
                "ghost", app.aaps.fuse.core.ledger.AmountStage.RT_PUBLISHED, 0.10
            ),
            lcfg,
        )
        val l = FuseStateJson.record(
            "s#1", outcome(), rt(), cfg, BUILD, 0L, null,
            ledger = FuseStateJson.LedgerSnapshot(1L, state),
        ) { 5_000_000L }.getJSONObject("ledger")

        assertTrue(l.getBoolean("hold"))
        assertEquals(1L, l.getLong("holdGeneration"))
        val errs = l.getJSONArray("activeErrors")
        assertEquals(1, errs.length())
        assertEquals("UNKNOWN_PROPOSAL", errs.getJSONObject(0).getString("error"))
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

    /**
     * B3: die DIAGNOSE der Patch-Epoche steht neben dem Sperrgrund.
     *
     * Der Sperrgrund allein (`REAL_PUMP_EPOCH_UNKNOWN` im publicationGate)
     * sagt nur DASS gesperrt wurde. Warum die Epoche fehlt - kein Datensatz,
     * Handeintrag, fremde Pumpe, aktive Pumpe unlesbar - sind vier Ursachen
     * mit vier verschiedenen Massnahmen.
     */
    @Test
    fun `die Patch-Epoche steht mit Grund im Datensatz`() {
        val j = FuseStateJson.record(
            "s#1", outcome(), rt(), cfg, BUILD, 0L, null,
            patchEpoch = FuseStateJson.PatchEpoch(epochTs = null, known = false, reason = "NO_EVENT"),
        ) { 5_000_000L }
        val p = j.getJSONObject("patchEpoch")
        assertFalse(p.getBoolean("known"))
        assertEquals("NO_EVENT", p.getString("reason"))
        assertFalse(p.has("epochTs")) { "unbekannt heisst KEIN Zeitstempel, nicht 0" }

        val bekannt = FuseStateJson.record(
            "s#2", outcome(), rt(), cfg, BUILD, 0L, null,
            patchEpoch = FuseStateJson.PatchEpoch(1_700_000_000_000L, true, "MATCHING_PUMP_IDENTITY"),
        ) { 5_000_000L }
        assertEquals(1_700_000_000_000L, bekannt.getJSONObject("patchEpoch").getLong("epochTs"))
    }

    /**
     * OHNE `applicable` IST DER DATENSATZ IRREFUEHREND.
     *
     * An der VirtualPump steht dort `known=false, reason=NO_EVENT` - und das
     * SIEHT aus wie eine fehlende Epoche, obwohl es dort gar keine geben kann
     * (kein CANNULA_CHANGE, keine Patches). Wer den Trail liest, wuerde einen
     * Defekt suchen, den es nicht gibt; schlimmer noch, er koennte die Sperre
     * fuer wirksam halten, wo sie gar nicht greift.
     *
     * Der unbekannte Fall bekommt ausdruecklich `null` und nicht "weggelassen":
     * "aktive Pumpe nicht lesbar" ist eine Aussage und muss von "dieser Build
     * kennt das Feld noch nicht" unterscheidbar bleiben.
     */
    @Test
    fun `die Zustaendigkeit der Epoche steht im Datensatz`() {
        fun feld(applicable: Boolean?) = FuseStateJson.record(
            "s#1", outcome(), rt(), cfg, BUILD, 0L, null,
            patchEpoch = FuseStateJson.PatchEpoch(null, false, "NO_EVENT", applicable),
        ) { 5_000_000L }.getJSONObject("patchEpoch")

        assertEquals(false, feld(false).getBoolean("applicable")) {
            "auf der Emulation ist die Epoche keine Kategorie - genau das muss dastehen"
        }
        assertEquals(true, feld(true).getBoolean("applicable"))
        val unbekannt = feld(null)
        assertTrue(unbekannt.has("applicable")) { "unbekannt ist eine Aussage, kein fehlendes Feld" }
        assertTrue(unbekannt.isNull("applicable"))
    }

    /** Wer sie nicht meldet, bekommt eine LUECKE - keine erfundene Epoche. */
    @Test
    fun `ohne gemeldete Patch-Epoche steht eine Luecke im Datensatz`() {
        val j = record()
        assertFalse(j.has("patchEpoch"))
        val gaps = j.getJSONArray("gaps")
        val fields = (0 until gaps.length()).map { gaps.getJSONObject(it).getString("field") }
        assertTrue(fields.contains("patchEpoch"))
    }

    /**
     * `allowed` und `realPump` sind die Felder, an denen sich ein Auswerter
     * festmachen soll - nicht am Namen des Verdikts.
     *
     * Das ist kein Stilhinweis, sondern eine Lehre aus diesem Wechsel: der
     * frueher einzige Wert "ALLOWED" heisst jetzt "ALLOWED", weil eine
     * zweite Erlaubnis dazugekommen ist. Jeder Leser, der auf die Zeichenkette
     * geprueft hat, war in dem Moment still falsch. Die booleschen Felder
     * ueberstehen solche Erweiterungen.
     */
    @Test
    fun `Auswerter koennen sich auf allowed und realPump stuetzen`() {
        val g = record().getJSONObject("gate")
        assertTrue(g.has("allowed"))
        assertTrue(g.has("realPump"))
        assertFalse(g.getBoolean("realPump"))
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

    /**
     * Die Luecke aus dem Audit 07.08.: bis v1 standen genau diese Knoepfe NICHT
     * im Hash - zwei Laeufe mit voellig verschiedenen Rampen bekamen dieselbe
     * Politik-Signatur. Der Test prueft die Knoepfe EINZELN, damit ein
     * kuenftiges Config-Feld ohne Hash-Eintrag hier wieder auffaellt.
     */
    @Test
    fun `Rampe und Abschlag stehen im Politik-Hash`() {
        val h = FuseStateJson.hashOf(cfg)!!
        assertTrue(FuseStateJson.hashOf(cfg.copy(riseRampLowR = 0.6)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(riseRampHighR = 2.5)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(bolusShareLambda = 0.0)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(onsetEnvelopeU = 2.0)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(onsetChannelEnabled = false)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(primeWindowMin = 15, primeEnvelopeU = 0.8)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(primeReleaseEnabled = false)) != h)
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
        assertEquals("3.4.2.5+fuse1.0.2-toni", b.getString("versionName"))
        assertEquals("abc1234", b.getString("head"))
        assertTrue(b.getBoolean("committed"))
    }

    private fun gapReasons(j: JSONObject): List<String> {
        val g = j.getJSONArray("gaps")
        return (0 until g.length()).map { g.getJSONObject(it).getString("reason") }
    }
    /**
     * DIE PERSIST-TELEMETRIE MUSS IM TRAIL STEHEN.
     *
     * Bis zum 12.08. las sie ausschliesslich der Store-Test - am Geraet war
     * nicht feststellbar, ob der fsync ueberhaupt laeuft, was er kostet oder
     * ob ein Schreibvorgang GESCHEITERT ist. Genau der Fehlerfall ist der
     * teure: der Ledger meldet dann keine Durabilitaet, und niemand sieht es.
     */
    @Test
    fun `die Persist-Telemetrie steht im Ledgerblock`() {
        val stats = app.aaps.fuse.plugin.ledger.FuseLedgerStore.PersistStats(
            bytes = 53_676, totalMs = 12, fileSyncMs = 7, dirSyncMs = 3,
            outcome = app.aaps.fuse.plugin.ledger.FuseLedgerStore.PersistOutcome.OK,
        )
        val l = FuseStateJson.record(
            "s#1", outcome(), rt(), cfg, BUILD, 0L, null,
            ledger = FuseStateJson.LedgerSnapshot(1L, app.aaps.fuse.core.ledger.LedgerState(), stats),
        ) { 5_000_000L }.getJSONObject("ledger").getJSONObject("persist")

        assertEquals("OK", l.getString("outcome"))
        assertEquals(53_676, l.getInt("bytes"))
        assertEquals(12L, l.getLong("totalMs"))
        assertEquals(7L, l.getLong("fileSyncMs"))
        assertEquals(3L, l.getLong("dirSyncMs"))
    }

    /** Ein GESCHEITERTER Schreibvorgang muss ebenso exportieren - sonst haette
     *  ausgerechnet der interessante Fall keine Zahlen. */
    @Test
    fun `auch ein gescheiterter Persist steht im Trail`() {
        val stats = app.aaps.fuse.plugin.ledger.FuseLedgerStore.PersistStats(
            bytes = 100, totalMs = 4, fileSyncMs = 0, dirSyncMs = 0,
            outcome = app.aaps.fuse.plugin.ledger.FuseLedgerStore.PersistOutcome.DIR_SYNC_FAILED,
        )
        val l = FuseStateJson.record(
            "s#1", outcome(), rt(), cfg, BUILD, 0L, null,
            ledger = FuseStateJson.LedgerSnapshot(1L, app.aaps.fuse.core.ledger.LedgerState(), stats),
        ) { 5_000_000L }.getJSONObject("ledger").getJSONObject("persist")

        assertEquals("DIR_SYNC_FAILED", l.getString("outcome"))
    }

    /** Ohne Schreibvorgang in diesem Prozess: `null`, nicht Nullen. Eine 0 ms
     *  saehe aus wie ein blitzschneller fsync. */
    @Test
    fun `ohne Schreibvorgang steht null statt Nullen`() {
        val l = FuseStateJson.record(
            "s#1", outcome(), rt(), cfg, BUILD, 0L, null,
            ledger = FuseStateJson.LedgerSnapshot(1L, app.aaps.fuse.core.ledger.LedgerState()),
        ) { 5_000_000L }.getJSONObject("ledger")
        assertTrue(l.isNull("persist"))
    }
    /**
     * DIE EPISODENZAHLEN FUER DEN VIEWER (Toni 12.08.).
     *
     * Ohne sie kann der Viewer die Zeile "Episode 287 min - DORMANT - FUSE
     * gesamt 5,10 U - Deckel 360" nicht bauen, ohne selbst zu rechnen - und
     * jede zweite Rechnung ueber dieselbe Groesse ist eine zweite Wahrheit.
     * Der DECKEL wandert deshalb mit: staende er im Viewer, veraltete er dort
     * beim naechsten Umbau still.
     */
    @Test
    fun `Episodenalter Bezahlung und Deckel stehen im Datensatz`() {
        val j = FuseStateJson.record(
            "s#1", outcome(episodeId = 1_700_000_000_000L, committedU = 5.10, episodeMin = 287),
            rt(), cfg, BUILD, 0L, null,
        ) { 5_000_000L }

        val e = j.getJSONObject("evidenceEpisode")
        assertEquals(1_700_000_000_000L, e.getLong("id"))
        assertEquals(287, e.getInt("ageMin"))
        assertEquals(5.10, e.getDouble("committedU"), 1e-9)
        assertEquals(
            app.aaps.fuse.core.controller.EvidenceStock.Config().maxEpisodeMin,
            e.getInt("capMin"),
        )
    }

    /**
     * OHNE EPISODE IST DER BLOCK NULL - nicht eine alte Menge neben einem
     * fehlenden Anker.
     *
     * Genau das stand vorher da: nach dem Ablauf blieb der Ledger-Zaehler
     * erhalten, waehrend `evidenceEpisodeId=0` und das Alter `null` waren. Wer
     * nur auf die Menge sieht, liest eine laufende Episode.
     */
    @Test
    fun `ohne Episode ist der Block null`() {
        val j = FuseStateJson.record("s#1", outcome(committedU = 5.10), rt(), cfg, BUILD, 0L, null) { 5_000_000L }
        assertTrue(j.isNull("evidenceEpisode")) { "kein Anker, kein Block" }
    }

    /**
     * PHASE UND GRUND SIND EINE BENANNTE LUECKE, wenn ein frueher Abbruch den
     * verdrahteten Kern in diesem Zyklus nicht erreicht - keine erfundene
     * Angabe. "DORMANT" einzutragen waere im Export nicht von einer echten
     * Messung unterscheidbar.
     */
    @Test
    fun `ohne Auswertung in diesem Zyklus stehen Phase und Grund als Luecke`() {
        val j = FuseStateJson.record(
            "s#1", outcome(episodeId = 1_700_000_000_000L, episodeMin = 5), rt(), cfg, BUILD, 0L, null,
        ) { 5_000_000L }

        val e = j.getJSONObject("evidenceEpisode")
        assertTrue(e.isNull("phase"))
        assertTrue(e.isNull("stockMgdl"))
        assertTrue(gapReasons(j).contains(FuseStateJson.GAP_EVIDENCE_NOT_EVALUATED)) {
            "die Luecke muss BENANNT sein, nicht bloss leer"
        }
    }

    /** Und mit laufendem Kern steht sie da. */
    @Test
    fun `mit Phase steht sie im Block und die Luecke entfaellt`() {
        val j = FuseStateJson.record(
            "s#1",
            outcome(
                episodeId = 1_700_000_000_000L, episodeMin = 42,
                phase = "DORMANT", stockMgdl = 0.0, reason = "NO_RISE",
            ),
            rt(), cfg, BUILD, 0L, null,
        ) { 5_000_000L }

        val e = j.getJSONObject("evidenceEpisode")
        assertEquals("DORMANT", e.getString("phase"))
        assertEquals("NO_RISE", e.getString("reason"))
        assertEquals(0.0, e.getDouble("stockMgdl"), 1e-12)
        assertFalse(gapReasons(j).contains(FuseStateJson.GAP_EVIDENCE_NOT_EVALUATED))
    }
    /**
     * DER DECKEL DES ZYKLUS, NICHT DER DEFAULT.
     *
     * Der Export erzeugte ihn frueher selbst ueber `EvidenceStock.Config()`.
     * Bei einem Replay mit abweichendem Deckel haette der Datensatz 360
     * behauptet, waehrend 17 gelaufen ist - der Export beschriebe eine andere
     * Regel als die gepruefte.
     */
    @Test
    fun `der Deckel im Datensatz ist der des Zyklus`() {
        val j = FuseStateJson.record(
            "s#1", outcome(episodeId = 1_700_000_000_000L, episodeMin = 3, capMin = 17),
            rt(), cfg, BUILD, 0L, null,
        ) { 5_000_000L }
        assertEquals(17, j.getJSONObject("evidenceEpisode").getInt("capMin"))
    }

    /** Id und Widerruf stehen NUR im Block - sonst gaebe es zwei Wahrheiten
     *  ueber dieselbe Episode. */
    @Test
    fun `Episodenidentitaet steht nicht mehr doppelt im Datensatz`() {
        val j = FuseStateJson.record(
            "s#1", outcome(episodeId = 1_700_000_000_000L, episodeMin = 3), rt(), cfg, BUILD, 0L, null,
        ) { 5_000_000L }
        assertFalse(j.has("evidenceEpisodeId")) { "gehoert in den Block" }
        assertFalse(j.has("evidenceCreditRevoked")) { "gehoert in den Block" }
        assertTrue(j.getJSONObject("evidenceEpisode").has("creditRevoked"))
    }

    /** Der GRUND bleibt aussen - im Block waere er unerreichbar, weil es dann
     *  keine Episode gibt. */
    @Test
    fun `der Ablehnungsgrund steht ausserhalb des Blocks`() {
        val j = FuseStateJson.record("s#1", outcome(denial = "MARKER_STALE"), rt(), cfg, BUILD, 0L, null) { 5_000_000L }
        assertTrue(j.isNull("evidenceEpisode"))
        assertEquals("MARKER_STALE", j.getString("evidenceEpisodeDenial"))
    }
}

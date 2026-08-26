package app.aaps.fuse.plugin.export

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.TailLiability
import app.aaps.fuse.core.controller.TurnResponseShadow
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
        smbRatio = 0.2, smbRatioRise = 0.35, sharedMaxIobU = 7.0, riseRampLowR = 0.5, riseRampHighR = 2.0, bolusShareLambda = 1.0, onsetChannelEnabled = true, onsetEnvelopeU = 1.5, primeReleaseEnabled = true, primeWindowMin = 15, primeEnvelopeU = 1.2, maxSmbU = 0.3, guardFloorMgdl = 70.0, lowGateMinBenefitMgdl = 5.0, zeroLatchEnabled = false, zeroLatchCalmExitMin = 20, zeroLatchCalmDistanceMgdl = 30.0, reversalGuardEnabled = false, reversalFallUkf = 2.0, reversalLookbackMin = 20, reversalReboundUkf = 1.0, reversalConfirmCycles = 2, correctionRearmEnabled = false, rearmHoldMin = 5, rearmConfirmCycles = 2, rearmUpUkf = 0.3, lowGateHorizonMin = 120.0, positiveDescentHorizonMin = 30.0, deferredPrimeEnabled = false, markerPrimeDescentHorizonMin = 60.0, deferredPrimeEndMin = 120, livenessChannelEnabled = false, livenessMealPowerMin = 120, livenessMealRatioCap = 1.0, livenessMealIobCapPercent = 50.0, livenessCorrectionRatioCap = 1.0, livenessCorrectionIobCapPercent = 50.0, livenessBgMinDayMgdl = 160.0, livenessBgMinNightMgdl = 160.0, livenessReArmMin = 10, iobThPercent = 100,
        releaseHorizonMin = 30, liabilityHorizonMin = 120, driveTauMin = 60, signalRejoinEnabled = false, theilSenWindowMin = 18, absorptionCreditWindowMin = 60, markerBoostMaxMin = 45, evidenceReboundOverrideMaxMin = 120, nightStartMin = 1380, nightEndMin = 420, nightDeadbandMgdl = 45.0, nightDeadbandEnabled = true, reboundDeadbandMgdl = 25.0, reboundDeadbandEnabled = true,
        driveLowerQuantilePct = 50, tailGuardEnabled = false, conditionalTailEnabled = true, markerAuthorized = false, mealFoundationEnabled = false, mealFoundationPhaseAShare = 1.0, mealFoundationPhaseAUpfrontShare = 0.0, mealFoundationEndMin = 60, tailFloorMgdl = 70.0, tailRecoveryU = 0.0, fastRestraintEnabled = true, endZeroWhenReasonGone = true,
    )

    private fun signal() = FuseSignalSource.Signal(
        sourceTs = 1_700_000_000_000L, rawBg = 132.0, q1 = 130.0, rSigned = 0.8,
        ukfRatePerMin = 1.1, ukfLearnedR = 2.2, rawSlopePerMin = 1.4, activityAtAnchor = 0.01, isfAtAnchor = 90.0,
        adjusted = app.aaps.fuse.core.signal.BgiAdjustedSeries.adjust(emptyList()), activity = ActivityValidity.VALID,
        samplesUsed = 19, rawSeriesSize = 200, gapBeforeMin = 1.0, stepFromLastMgdl = -1.0, stepRateActualMgdlPerMin = -1.0, postGapIndex = 18, q1Outlier = false,
        boundedBy = SignalWindow.Bound.NONE, windowFromTs = 1_699_988_120_000L, segmentStartTs = 1_699_988_120_000L, rejoin = app.aaps.fuse.core.signal.SignalRejoin.strict(), fullMaturityTs = 0L, signalEpochTs = 1_699_900_000_000L,
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
        foundation: app.aaps.fuse.core.controller.MealFoundation.Snapshot =
            app.aaps.fuse.core.controller.MealFoundation.Snapshot.none(),
        manualBolusAfterMarkerU: Double? = null,
        shadow: TurnResponseShadow.Report? = null,
    ) = FuseCycleRunner.Outcome(
        tbrChanged = false,
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
        mealFoundation = foundation,
        manualBolusAfterMarkerU = manualBolusAfterMarkerU,
        turnResponseShadow = shadow,
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

    @Test
    fun `Wende-Shadow exportiert Klassifikation und die vollstaendige Tau-Matrix`() {
        val classification = TurnResponseShadow.Classification(
            phase = TurnResponseShadow.Phase.TURNING_DOWN,
            reason = TurnResponseShadow.Reason.DOWN_CONFIRMED,
            slowDriveMgdlPerMin = 2.81,
            fastDriveMgdlPerMin = 2.69,
            delta1MgdlPerMin = -0.19,
            delta2MgdlPerMin = -0.25,
            delta3MgdlPerMin = -0.30,
            upwardMeanDriveMgdlPerMin = null,
            adaptiveRestraintTauMin = 50,
        )
        fun variant(name: String, tau: Int, adaptive: Boolean = false) = TurnResponseShadow.Variant(
            name = name,
            requestedRestraintTauMin = tau,
            restraintTauMin = tau,
            adaptive = adaptive,
            predAtReleaseMgdl = 182.2,
            safetyLowerAtReleaseMgdl = 75.0,
            minSafetyLowerMgdl = 75.0,
            tailHeadroomU = 0.209,
            insulinReqU = 1.5,
            ratioCapU = 0.383,
            candidateSmbU = 0.20,
            candidateBinding = "candidate:guardFloor",
            candidateReject = null,
        )
        val report = TurnResponseShadow.Report(
            classification,
            listOf(variant("R60", 60), variant("R55", 55), variant("R50", 50), variant("R45", 45), variant("ADAPTIVE", 50, true)),
            computeDurationMs = 2.4,
        )

        val j = record(outcome(shadow = report)).getJSONObject("turnResponseShadow")
        assertTrue(j.getBoolean("dosageNeutral"))
        assertEquals("TURNING_DOWN", j.getString("phase"))
        assertEquals(50, j.getInt("adaptiveRestraintTauMin"))
        assertEquals(-0.25, j.getDouble("delta2MgdlPerMin"), 1e-9)
        assertEquals(2.4, j.getDouble("computeDurationMs"), 1e-9)
        val variants = j.getJSONArray("variants")
        assertEquals(listOf("R60", "R55", "R50", "R45", "ADAPTIVE"),
            (0 until variants.length()).map { variants.getJSONObject(it).getString("name") })
        val r50 = variants.getJSONObject(2)
        assertEquals(75.0, r50.getDouble("safetyLowerAtReleaseMgdl"), 1e-9)
        assertEquals(0.209, r50.getDouble("tailHeadroomU"), 1e-9)
        assertEquals(0.20, r50.getDouble("candidateSmbU"), 1e-9)
    }

    /**
     * ADAPTIVE-DOWN im Export (Toni 22.08.): vier Zeilen, jede vollstaendig
     * - inklusive der PREDICT_FAILED-Luecke mit NaN, die ueber [fin] zu NULL
     * werden MUSS statt den ganzen Datensatz zu reissen (org.json wirft bei
     * NaN, und der Wurf laege im runCatching des Exports).
     */
    @Test
    fun `die Down-Zeilen stehen vollstaendig und NaN-fest im Export`() {
        val down = listOf(
            TurnResponseShadow.DownVariant(
                "BASE", false, 2, 2.5, 180.0, 0.9, 0.30, "smbRatio", null, 0.0,
            ),
            TurnResponseShadow.DownVariant(
                "NOW", true, 2, 1.4, 165.0, 0.5, 0.15, "candidate:guardFloor", null, 0.15,
                endU = 0.20, avoidedEndU = 0.10,
            ),
            TurnResponseShadow.DownVariant(
                "P2", true, 2, 1.4, Double.NaN, null, null, null, "PREDICT_FAILED", null,
            ),
            TurnResponseShadow.DownVariant(
                "P3", false, 2, 2.5, 180.0, 0.9, 0.30, "smbRatio", null, 0.0,
            ),
        )
        val report = TurnResponseShadow.Report(
            TurnResponseShadow.Classification(
                TurnResponseShadow.Phase.ALIGNED, TurnResponseShadow.Reason.NO_CONFIRMED_TURN,
                2.8, 1.4, null, null, null, null, 60,
            ),
            emptyList(), computeDurationMs = 1.1, downVariants = down,
        )
        val j = record(outcome(shadow = report)).getJSONObject("turnResponseShadow")
        val dv = j.getJSONArray("downVariants")
        assertEquals(
            listOf("BASE", "NOW", "P2", "P3"),
            (0 until dv.length()).map { dv.getJSONObject(it).getString("name") },
        )
        val now = dv.getJSONObject(1)
        assertTrue(now.getBoolean("triggered"))
        assertEquals(2, now.getInt("declineStreak"))
        assertEquals(1.4, now.getDouble("midDriveMgdlPerMin"), 1e-9)
        assertEquals(0.15, now.getDouble("avoidedSmbU"), 1e-9)
        assertEquals(0.20, now.getDouble("endU"), 1e-9)
        assertEquals(0.10, now.getDouble("avoidedEndU"), 1e-9)
        assertEquals("candidate:guardFloor", now.getString("candidateBinding"))
        val p2 = dv.getJSONObject(2)
        assertTrue(p2.isNull("predAtReleaseMgdl"), "NaN wird zur benannten Luecke, nicht zum Absturz")
        assertEquals("PREDICT_FAILED", p2.getString("candidateReject"))
    }

    @Test
    fun `Abbruch vor der Bahn benennt den fehlenden Shadow statt einen zu erfinden`() {
        val j = record(outcome(shadow = null))
        val gaps = j.getJSONArray("gaps")
        assertTrue((0 until gaps.length()).map { gaps.getJSONObject(it) }
            .any { it.getString("field") == "turnResponseShadow" && it.getString("reason") == "NO_SHADOW_THIS_CYCLE" })
    }

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
        // v22: W18 und W10 sind verschiedene Schaetzer - MUTATIONSPROBE fuer
        // das Fenster im Fingerprint (Vertragspunkt 4).
        assertTrue(FuseStateJson.hashOf(cfg.copy(signalRejoinEnabled = false, theilSenWindowMin = 10)) != h)
        // v23: der Ratio-Deckel des Liveness-Kanals - MUTATIONSPROBE fuer
        // den Fingerprint (Tonis Vertrag: Aufnahme in Policy-Hash).
        // v24: die vier Profil-Caps und die gepinnte Frist - jede einzeln
        // eine Mutationsprobe fuer den Fingerprint (Bauauftrag §8).
        assertTrue(FuseStateJson.hashOf(cfg.copy(livenessMealRatioCap = 0.2, livenessCorrectionRatioCap = 0.2)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(livenessCorrectionRatioCap = 0.2)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(livenessMealIobCapPercent = 60.0)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(livenessCorrectionIobCapPercent = 40.0)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(livenessMealPowerMin = 60)) != h)
        // v25: der Zero-Latch - Schalter, Ruhe-Zyklen und Ruhe-Abstand.
        assertTrue(FuseStateJson.hashOf(cfg.copy(zeroLatchEnabled = true)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(zeroLatchCalmExitMin = 30)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(zeroLatchCalmDistanceMgdl = 40.0)) != h)
        assertTrue(FuseStateJson.hashOf(cfg.copy(tailGuardEnabled = true)) != h)
        assertEquals(h, FuseStateJson.hashOf(cfg.copy()))
    }

    /**
     * DIE REGELSTANDS-IDENTITAET DES MAHLZEITENFUNDAMENTS (Codex 19.08.,
     * Flash-Blocker).
     *
     * DER BEFUND. Die drei Einstellungen wurden EXPORTIERT, gingen aber nicht
     * in `hashOf()` ein. Damit trugen Laeufe mit sichtbar verschiedenem
     * Regelverhalten denselben `configGeneration`:
     *
     *     Fundament AUS  gegen  EIN
     *     80/20          gegen  75/25
     *     Phase-B-Ende 60       gegen 45
     *
     * WARUM DAS MEHR IST ALS UNORDNUNG: der Erwartungs-Ledger trennt seine
     * Strecken nach genau diesem Hash. Zwei verschieden dosierende Regler
     * unter einer Kennung heisst, dass ihre Ergebnisse in denselben Topf
     * fallen - und die Feldauswertung danach eine Mischung misst, die es als
     * Regler nie gab. Ein Schema-Bump behebt das NICHT; er beschreibt die
     * Datei, nicht den Regler.
     *
     * JEDES FELD EINZELN, nicht die drei zusammen: nur so faellt auf, wenn
     * genau eines wieder aus dem Hash herausfaellt.
     */
    /**
     * DIESELBE REGEL FUER DEN WIEDEREINSTIEG NACH FUNKLUECKE (Review
     * 25.08. abends, P1).
     *
     * Er war zuerst nur eine Einstellung, die im Zyklus gelesen wurde -
     * exportiert je Zyklus, aber nicht in der Kennung. Damit trugen zwei
     * messbar verschiedene Regler denselben `configGeneration`: mit
     * Schalter entstehen Entscheidungen, die ohne ihn als "drive not
     * estimable" gestorben waeren (gemessen 26 -> 21 blinde Zyklen und
     * +0,050 U ueber einen Tag). Der Erwartungs-Ledger trennt seine
     * Strecken nach genau diesem Hash - ihre Ergebnisse waeren in
     * denselben Topf gefallen.
     */
    @Test
    fun `der Wiedereinstiegs-Schalter aendert den Politik-Hash`() {
        val aus = FuseStateJson.hashOf(cfg.copy(signalRejoinEnabled = false))!!
        val ein = FuseStateJson.hashOf(cfg.copy(signalRejoinEnabled = true))!!
        assertTrue(aus != ein, "AUS und EIN sind zwei verschiedene Regler")
        // Und er steht auch in den ausgeschriebenen Politikwerten - der
        // Hash allein sagt nicht, WAS sich unterschied.
        val werte = FuseStateJson.policyValues(cfg.copy(signalRejoinEnabled = true))
        assertTrue(werte.toString().contains("signalRejoinEnabled"))
    }

    @Test
    fun `der Fundament-Schalter aendert den Politik-Hash`() {
        val aus = FuseStateJson.hashOf(cfg.copy(mealFoundationEnabled = false))!!
        val ein = FuseStateJson.hashOf(cfg.copy(mealFoundationEnabled = true))!!
        assertTrue(aus != ein, "AUS und EIN sind zwei verschiedene Regler")
    }

    @Test
    fun `die Phasenaufteilung aendert den Politik-Hash`() {
        val a80 = FuseStateJson.hashOf(cfg.copy(mealFoundationPhaseAShare = 0.80))!!
        val a75 = FuseStateJson.hashOf(cfg.copy(mealFoundationPhaseAShare = 0.75))!!
        assertTrue(a80 != a75, "80/20 und 75/25 verteilen dieselbe Huelle verschieden")
        // UND GEGEN DEN HEUTIGEN STAND: 100/0 ist der Vergleichsfall im
        // Replay und muss sich von beiden unterscheiden.
        val a100 = FuseStateJson.hashOf(cfg.copy(mealFoundationPhaseAShare = 1.00))!!
        assertTrue(a100 != a80 && a100 != a75, "und 100/0 von beiden")
    }

    /** v28: der Sofortanteil - 0/0,5/0,75/1,0 verteilen dieselbe
     *  Phase-A-Menge voellig verschieden ueber die Zeit (Replay-Matrix des
     *  Bauauftrags), jede Stufe braucht ihren eigenen Fingerprint. */
    @Test
    fun `der phase-a-sofortanteil aendert den politik-hash`() {
        val stufen = listOf(0.0, 0.5, 0.75, 1.0)
            .map { FuseStateJson.hashOf(cfg.copy(mealFoundationPhaseAUpfrontShare = it))!! }
        assertEquals(stufen.size, stufen.toSet().size, "jede Stufe ein eigener Hash: $stufen")
    }

    @Test
    fun `das Phase-B-Fensterende aendert den Politik-Hash`() {
        val e60 = FuseStateJson.hashOf(cfg.copy(mealFoundationEndMin = 60))!!
        val e45 = FuseStateJson.hashOf(cfg.copy(mealFoundationEndMin = 45))!!
        assertTrue(e60 != e45, "ein anderes Ende presst dasselbe Teilbudget in eine andere Zeit")
    }

    @Test
    fun `der positive Abwaerts-Horizont aendert Politik und Export`() {
        val h30 = FuseStateJson.hashOf(cfg.copy(positiveDescentHorizonMin = 30.0))!!
        val h45 = FuseStateJson.hashOf(cfg.copy(positiveDescentHorizonMin = 45.0))!!
        assertTrue(h30 != h45, "30 und 45 Minuten sind verschiedene Endriegel")
        assertEquals(30.0, FuseStateJson.policyValues(cfg).getDouble("positiveDescentHorizonMin"), 1e-9)
    }

    /**
     * DIE ZWEI STELLGROESSEN DES LOW-TORS (v16, Review 22.08.).
     *
     * Beide entscheiden, ab wann eine Zero-TBR als nutzlos gilt - also WANN
     * der Basalpfad wieder freigibt. Bis v15 fehlten sie in Hash UND
     * policyValues: zwei Laeufe mit 5 und 20 mg/dl Schwelle trugen denselben
     * Regelstand, und der Trail konnte nicht einmal ZEIGEN, welche Werte
     * galten - genau die Luecke, gegen die der Fingerprint gebaut ist.
     */
    @Test
    fun `Schwelle und Fenster des Low-Tors aendern Politik und Export`() {
        val h = FuseStateJson.hashOf(cfg)!!
        assertTrue(
            FuseStateJson.hashOf(cfg.copy(lowGateMinBenefitMgdl = 20.0)) != h,
            "5 und 20 mg/dl Nutzenschwelle sind verschiedene Regler",
        )
        assertTrue(
            FuseStateJson.hashOf(cfg.copy(lowGateHorizonMin = 60.0)) != h,
            "120 und 60 Minuten Nutzenfenster sind verschiedene Regler",
        )
        assertEquals(5.0, FuseStateJson.policyValues(cfg).getDouble("lowGateMinBenefitMgdl"), 1e-9)
        assertEquals(120.0, FuseStateJson.policyValues(cfg).getDouble("lowGateHorizonMin"), 1e-9)
    }

    /**
     * DIE DREI STELLGROESSEN DES MARKER-PRIME-AUFSCHUBS (v17, Punkt 6).
     * Schalter, gepinnter Horizont und gepinnte Frist sind dosierwirksam in
     * BEIDE Richtungen (zurueckhalten UND nachliefern) - Laeufe mit
     * verschiedenen Stellungen duerfen nie denselben Regelstand tragen.
     */
    @Test
    fun `die Aufschub-Stellgroessen aendern Politik und Export`() {
        val h = FuseStateJson.hashOf(cfg)!!
        assertTrue(
            FuseStateJson.hashOf(cfg.copy(deferredPrimeEnabled = true)) != h,
            "AUS und EIN sind zwei verschiedene Regler",
        )
        assertTrue(
            FuseStateJson.hashOf(cfg.copy(markerPrimeDescentHorizonMin = 90.0)) != h,
            "60 und 90 Minuten Marker-Horizont sind verschiedene Riegel",
        )
        assertTrue(
            FuseStateJson.hashOf(cfg.copy(deferredPrimeEndMin = 60)) != h,
            "120 und 60 Minuten Frist verfallen verschieden",
        )
        val pv = FuseStateJson.policyValues(cfg)
        assertEquals(false, pv.getBoolean("deferredPrimeEnabled"))
        assertEquals(60.0, pv.getDouble("markerPrimeDescentHorizonMin"), 1e-9)
        assertEquals(120, pv.getInt("deferredPrimeEndMin"))
    }

    /** Der Aufschub steht IMMER als Objekt im Trail - auch der Verfall. */
    @Test
    fun `der Aufschub-Block traegt Zustand Freigabe und Verfall`() {
        val j = record(
            outcome().copy(
                deferredPrimeOpenU = 0.85,
                deferredPrimePinnedForTs = 111L,
                deferredPrimeDeadlineTs = 222L,
                deferredPrimeHorizonMin = 60,
                deferredPrimeWithheldU = 0.15,
                deferredPrimeReleasedU = 0.05,
                deferredPrimeDenial = null,
                deferredPrimeLapseReason = "EXPIRED",
                deferredPrimeLapseU = 0.40,
                deferredPrimeLapseTs = 333L,
            ),
        ).getJSONObject("deferredPrime")
        assertEquals(0.85, j.getDouble("openU"), 1e-9)
        assertEquals(111L, j.getLong("pinnedForTs"))
        assertEquals(222L, j.getLong("deadlineTs"))
        assertEquals(60, j.getInt("horizonMin"))
        assertEquals(0.15, j.getDouble("withheldU"), 1e-9)
        assertEquals(0.05, j.getDouble("releasedU"), 1e-9)
        assertTrue(j.isNull("denial"))
        assertEquals("EXPIRED", j.getString("lapseReason"))
        assertEquals(0.40, j.getDouble("lapseU"), 1e-9)
        assertEquals(333L, j.getLong("lapseTs"))
    }

    /**
     * UND DIE VERSION SELBST: der Bump auf 11 gehoert zur Aenderung.
     *
     * Ohne ihn traegt ein Lauf VOR dem Fundament-Umbau denselben Regelstand
     * wie einer danach, obwohl beide verschieden dosieren - die drei Felder
     * allein trennen nur Stellungen INNERHALB der neuen Bauform, nicht die
     * Bauform gegen die alte.
     */
    @Test
    fun `die Regelstandsversion traegt jede dosierwirksame Aenderung`() {
        // v11 Mahlzeitenfundament, v12 die Frist des Rebound-Sonderrechts,
        // v13 der restartfeste Wiederfreigabe-Riegel nach gemessenem Fallen,
        // v14 der Sicherheitsaufschub, v15 der getrennte positive Horizont,
        // v16 die zwei Low-Tor-Stellgroessen im Fingerprint, v17 der
        // Marker-Prime-Aufschub (Punkt 6), v18 der Liveness-Kanal
        // (mengenbasierter Zusatzkanal gegen den Tail-Deadlock, 22.08.),
        // v19 die streaknullende Re-Arm-Sperre (drei frische Druckzyklen
        // nach der Pause - live zaehlte er waehrend der Sperre weiter),
        // v20 die getrennte Tag-/Nacht-Druckschwelle des Liveness-Kanals,
        // v21 der magnitudensensitive Wende-Exit (bestaetigte Wende statt
        // zweier beliebig kleiner Rueckgaenge), v26 die Liveness-Basis-Ratio
        // nach Profil (MEAL traegt die R-Rampe selbst statt der
        // fenster-gegateten effectiveSmbRatio - der unsichtbare
        // 0,15-Livefall bei Marker +115 min), v27 Tonis Korrektur dazu:
        // BEIDE Profile rampen, der Profilunterschied ist allein der
        // M-/K-Deckel (v26 liess den K-Deckel nie skalieren), v28 der
        // Phase-A-Sofortanteil nach iLet-Prinzip (Default 0,00 bitgleich;
        // typisierte Quelle MEAL_UPFRONT, nicht maxSmb-zerteilt,
        // exactly-once als Bilanz auf den beweiskorrigierten Zaehlern),
        // v29 die Zwei-Zyklen-Zuendung des Zero-Latch-Fall-Verdikts (ein
        // einzelner Grenzzyklus/Sensorzacken verriegelt keine lange Null
        // mehr; MEASURED_LOW weiter sofort).
        // v30 die Korrekturpfad-Riegel (V-Reversal-Schutz + Freigabe-
        // Nachlauf nach Zero-Latch-/Nachtende, beide Default AUS, nur im
        // reinen Korrekturkontext - Pflichtfall 25.08. frueh).
        // v31 der Wiedereinstieg nach Funkluecke (4x3-Rejoin, Default AUS).
        // v32 DER RUHE-AUSGANG AUS PHASE A. Dosierwirksam im Modus
        // CALM_BATCH: der zurueckgehaltene Sofortanteil verlaesst dann nach
        // N bestaetigten Ruhezyklen den HISTORISCHEN Latch - aktuelle
        // Gefahren bleiben absolut. Anlass ist der Abendfall 25.08.:
        // 3,60 U blieben die ganze Phase A blockiert, obwohl das gemessene
        // Abwaertsrisiko seit neun Zyklen vorbei war. Default AUS.
        // DIESER TEST IST ABSICHTLICH STUR: er faellt bei jedem Bump um und
        // zwingt damit zu der Frage, ob die Aenderung wirklich dosierwirksam
        // war - ein stiller Bump waere so wertlos wie ein vergessener.
        assertEquals(32, FuseStateJson.RULE_SET_VERSION)
        assertTrue(
            FuseStateJson.hashOf(cfg)!!.isNotEmpty(),
            "und der Hash bleibt berechenbar",
        )
    }

    @Test
    fun `Rohgefahr und dosierwirksamer Abwaertsriegel bleiben im Trail getrennt`() {
        val j = record(
            outcome().copy(
                descentRiskActive = false,
                descentRiskDenial = "NOT_FALLING",
                descentLatchActive = true,
                descentLatchReason = "WAITING_CONFIRMATION",
                descentRecoveryCycles = 2,
                descentLatchedAtTs = 1_700_000_000_000L,
            ),
        )

        assertFalse(j.getBoolean("descentRiskActive"), "das Rohsignal ist bereits frei")
        assertTrue(j.getBoolean("descentLatchActive"), "der wirksame Riegel bleibt noch zu")
        assertEquals("WAITING_CONFIRMATION", j.getString("descentLatchReason"))
        assertEquals(2, j.getInt("descentRecoveryCycles"))
        assertEquals(1_700_000_000_000L, j.getLong("descentLatchedAtTs"))
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

    // ---- Mahlzeitenfundament (Punkt 12, Toni 18.08.) ----------------------

    private val fT0 = 1_786_000_000_000L

    private fun fAuth(anteil: Double = 0.75) = app.aaps.fuse.core.controller.MealFoundation.arm(
        markerTs = fT0, foundationEnabled = true, totalBudgetU = 3.0, phaseAShare = anteil, phaseAUpfrontShare = 0.0,
        primeWindowMin = 15, wallCeilingMin = 45, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = true, phaseBUntilMin = 60,
    )

    private fun fSnapshot(
        minuten: Double = 30.0,
        ausBudgetU: Double = 2.25,
        seitUebergabeU: Double = 0.10,
        uebertragU: Double = 0.0,
        abwaertsU: Double = 0.0,
        abwaertsEligibility: app.aaps.fuse.core.controller.DescentDeferredCarry.Eligibility =
            app.aaps.fuse.core.controller.DescentDeferredCarry.Eligibility.NO_DEFERRED,
    ) = app.aaps.fuse.core.controller.MealFoundation.snapshot(
        fAuth(), fT0 + (minuten * 60_000).toLong(), 0L,
        deliveredFromBudgetU = ausBudgetU, deliveredSinceHandoverU = seitUebergabeU,
        deliveredPhaseAU = ausBudgetU - seitUebergabeU,
        confirmedNotSentPhaseAU = uebertragU,
        descentDeferredPhaseAU = abwaertsU,
        descentCarryEligibility = abwaertsEligibility,
        bolusStepU = 0.05,
    )

    /**
     * Der Abschnitt aus dem ECHTEN Export - nicht aus einer nachgebauten
     * Serialisierung. Der erste Wurf dieses Tests baute die JSON-Form selbst
     * nach und haette damit nichts geprueft: waere im Zyklusexport ein Feld
     * weggefallen, waere er gruen geblieben. Ein Test, der eine Kopie der zu
     * pruefenden Logik enthaelt, sichert nur sich selbst ab.
     */
    private fun fundament(f: app.aaps.fuse.core.controller.MealFoundation.Snapshot) =
        record(outcome(foundation = f)).getJSONObject("mealFoundation")

    /**
     * UEBERTRAGEN UND VERFALLEN SIND ZWEI GROESSEN (Review 25.08. spaet,
     * Punkt 3). Mit UNTERSCHIEDLICHEN Werten geprueft - stuenden beide
     * auf demselben Wert, waere eine Verwechslung im Export unsichtbar.
     */
    @Test
    fun `Uebertrag und Verfall stehen getrennt im Export`() {
        val o = record(
            outcome().copy(
                phaseAUpfrontTransferredU = 2.40,
                phaseAUpfrontLapsedU = 0.20,
            )
        ).getJSONObject("mealFoundation")
        assertEquals(2.40, o.getDouble("phaseAUpfrontTransferredU"), 1e-9)
        assertEquals(0.20, o.getDouble("phaseAUpfrontLapsedU"), 1e-9)
        // Und andersherum, damit keine Vertauschung durchrutscht.
        val v = record(
            outcome().copy(
                phaseAUpfrontTransferredU = 0.20,
                phaseAUpfrontLapsedU = 2.40,
            )
        ).getJSONObject("mealFoundation")
        assertEquals(0.20, v.getDouble("phaseAUpfrontTransferredU"), 1e-9)
        assertEquals(2.40, v.getDouble("phaseAUpfrontLapsedU"), 1e-9)
    }

    /**
     * TONIS FELDLISTE, Feld fuer Feld (Punkt 12).
     *
     * Nicht weil ein fehlendes Feld den Regler kaputt macht, sondern weil das
     * Offline-Replay und jede spaetere Feldauswertung nur so gut sind wie
     * das, was exportiert wird. Ein stilles Wegfallen beim naechsten
     * Refactoring wuerde erst auffallen, wenn jemand Wochen spaeter eine
     * Frage nicht mehr beantworten kann.
     */
    @Test
    fun `der Fundament-Export traegt jedes geforderte Feld`() {
        val o = fundament(fSnapshot())
        for (feld in listOf(
            "totalBudgetU", "phaseABudgetU", "phaseBBudgetU",
            "armedTs", "effectiveHandoverTs", "latchedHandoverTs", "endTs",
            "phase",
            "deliveredSinceHandoverU",
            // Der Uebertrags-Block (19.08.): Rohzaehler, autoritative
            // Phase-A-Lieferung, effektiver Rest und die daraus folgende
            // Erlaubnis. Ohne ALLE VIER ist die Ableitung aus dem Trail
            // nicht nachrechenbar - dann sieht man Ergebnis und Rohwert,
            // aber nicht die Groesse dazwischen.
            "confirmedNotSentPhaseAU", "deliveredPhaseAU",
            "effectiveCarryU", "descentDeferredPhaseAU", "descentCarryEligibility",
            "manualBolusAfterMarkerU", "effectiveDescentCarryU", "phaseBAllowanceU",
            "plannedTotalU", "backlogU", "dueU", "remainingInWindowU", "binding",
            "effectiveWindowMin", "effectiveRateUPerMin",
            // v28: der Sofortanteil - angefordert, publiziert und
            // pumpenbestaetigt bleiben getrennte Begriffe.
            "phaseAUpfrontShare", "phaseAUpfrontPlannedU", "phaseARemainderU",
            "phaseAUpfrontRequestedU", "phaseAUpfrontPublishedU",
            "phaseAUpfrontConfirmedU", "phaseAUpfrontPendingU",
            "phaseAUpfrontState", "phaseAUpfrontProposalId",
            // Review 25.08. spaet: uebertragen und verfallen sind ZWEI
            // Groessen - "uebertragen" darf nur heissen, was der
            // schrittweise Pfad wirklich aufgenommen hat.
            "phaseAUpfrontTransferredU", "phaseAUpfrontLapsedU",
        )) {
            assertTrue(o.has(feld), "$feld fehlt im Export")
        }
        // confirmed wird NICHT behauptet: FUSE fuehrt keine
        // Pumpenbestaetigungs-Buchfuehrung je Menge.
        assertTrue(fundament(fSnapshot()).isNull("phaseAUpfrontConfirmedU"))
    }

    /** Die Werte muessen stimmen, nicht nur dastehen - ein Export voller
     *  Nullen bestuende den Feldtest oben. */
    @Test
    fun `der Fundament-Export traegt die richtigen Werte`() {
        val o = fundament(fSnapshot())
        assertTrue(o.getBoolean("armed"))
        assertEquals(fT0, o.getLong("armedTs"))
        assertEquals(3.0, o.getDouble("totalBudgetU"), 1e-9)
        assertEquals(2.25, o.getDouble("phaseABudgetU"), 1e-9)
        assertEquals(0.75, o.getDouble("phaseBBudgetU"), 1e-9)
        assertEquals(fT0 + 15 * 60_000L, o.getLong("effectiveHandoverTs"))
        assertEquals(0L, o.getLong("latchedHandoverTs"), "noch nicht gelatcht")
        assertEquals(fT0 + 60 * 60_000L, o.getLong("endTs"))
        assertEquals("PHASE_B", o.getString("phase"))
        assertEquals(0.10, o.getDouble("deliveredSinceHandoverU"), 1e-9)
        assertEquals(45, o.getInt("effectiveWindowMin"), "T+15 bis T+60")
        assertEquals(0.75 / 45.0, o.getDouble("effectiveRateUPerMin"), 1e-9)
    }

    @Test
    fun `manuelle Deckung nach Marker steht mit dem Sperrgrund im Trail`() {
        val foundation = fSnapshot(
            ausBudgetU = 1.35,
            seitUebergabeU = 0.0,
            abwaertsU = 1.65,
            abwaertsEligibility = app.aaps.fuse.core.controller.DescentDeferredCarry.Eligibility.MANUAL_BOLUS_AFTER_MARKER,
        )
        val o = record(outcome(foundation = foundation, manualBolusAfterMarkerU = 3.0))
            .getJSONObject("mealFoundation")

        assertEquals(3.0, o.getDouble("manualBolusAfterMarkerU"), 1e-9)
        assertEquals("MANUAL_BOLUS_AFTER_MARKER", o.getString("descentCarryEligibility"))
        assertEquals(0.0, o.getDouble("effectiveDescentCarryU"), 1e-9)
        assertEquals(0.75, o.getDouble("phaseBAllowanceU"), 1e-9)
    }

    /**
     * DER UEBERTRAG IST AUS DEM TRAIL NACHRECHENBAR (Codex 19.08., P1).
     *
     * DER BEFUND. Exportiert waren Rohzaehler, effektiver Rest und Erlaubnis -
     * aber nicht, WIEVIEL in Phase A tatsaechlich floss. Genau diese Groesse
     * steht zwischen Roh und Effektiv:
     *
     *     effectiveCarry = min(rawCarry, max(0, phaseABudget - deliveredPhaseA))
     *
     * Ohne sie liesse sich im Replay nicht entscheiden, ob ein kleiner
     * effektiver Uebertrag daher kommt, dass Prime nachgeholt hat, oder aus
     * einem Rechenfehler.
     *
     * Der Aufbau: 2,25 U Phase-A-Budget, 1,80 U davon geflossen, Rohzaehler
     * 0,30 U. Rueckstand 0,45 U, also gilt der volle Rohzaehler.
     */
    @Test
    fun `der Export macht die Uebertrags-Ableitung nachrechenbar`() {
        val o = fundament(fSnapshot(ausBudgetU = 1.90, seitUebergabeU = 0.10, uebertragU = 0.30))

        assertEquals(0.30, o.getDouble("confirmedNotSentPhaseAU"), 1e-9, "der rohe Beweiszaehler")
        assertEquals(1.80, o.getDouble("deliveredPhaseAU"), 1e-9, "was in Phase A wirklich floss")
        assertEquals(0.10, o.getDouble("deliveredSinceHandoverU"), 1e-9)

        // UND DIE ABLEITUNG GEHT AUF - allein aus den exportierten Zahlen.
        val phaseABudget = o.getDouble("phaseABudgetU")
        val rueckstand = maxOf(0.0, phaseABudget - o.getDouble("deliveredPhaseAU"))
        val effektiv = minOf(o.getDouble("confirmedNotSentPhaseAU"), rueckstand)
        assertEquals(
            effektiv, o.getDouble("effectiveCarryU"), 1e-9,
            "der Trail MUSS die eigene Rechnung tragen",
        )
        assertEquals(
            minOf(o.getDouble("phaseBBudgetU") + effektiv, o.getDouble("totalBudgetU")),
            o.getDouble("phaseBAllowanceU"), 1e-9,
            "und die Erlaubnis daraus",
        )
    }

    /**
     * DIE GEGENPROBE: hat Prime nachgeholt, faellt der effektive Rest auf 0 -
     * der Rohzaehler bleibt stehen. Erst BEIDE Zahlen zusammen sagen, was
     * passiert ist.
     */
    @Test
    fun `ein nachgeholter Uebertrag ist im Trail als solcher erkennbar`() {
        val o = fundament(fSnapshot(ausBudgetU = 2.35, seitUebergabeU = 0.10, uebertragU = 0.30))
        assertEquals(2.25, o.getDouble("deliveredPhaseAU"), 1e-9, "Phase A ist voll geliefert")
        assertEquals(0.30, o.getDouble("confirmedNotSentPhaseAU"), 1e-9, "der Beweis steht weiter da")
        assertEquals(0.0, o.getDouble("effectiveCarryU"), 1e-9, "wirkt aber nicht mehr")
        assertEquals(
            o.getDouble("phaseBBudgetU"), o.getDouble("phaseBAllowanceU"), 1e-9,
            "Phase B rechnet wieder mit ihrem Teilbudget",
        )
    }

    @Test
    fun `der Sicherheitsaufschub steht roh mit Urteil und wirksamem Anteil im Trail`() {
        val o = fundament(
            fSnapshot(
                ausBudgetU = 1.35,
                seitUebergabeU = 0.0,
                abwaertsU = 1.65,
                abwaertsEligibility = app.aaps.fuse.core.controller.DescentDeferredCarry.Eligibility.ELIGIBLE,
            ),
        )
        assertEquals(1.65, o.getDouble("descentDeferredPhaseAU"), 1e-9)
        assertEquals("ELIGIBLE", o.getString("descentCarryEligibility"))
        assertEquals(0.90, o.getDouble("effectiveDescentCarryU"), 1e-9)
        assertEquals(1.65, o.getDouble("phaseBAllowanceU"), 1e-9)
    }

    /**
     * OHNE AUTORISIERUNG STEHT DER ABSCHNITT TROTZDEM DA.
     *
     * `armed: false` ist eine Aussage; ein fehlender Abschnitt waere von "der
     * Zyklus kam nicht so weit" nicht zu unterscheiden. Solange arm() nicht
     * verdrahtet ist, ist das der Dauerzustand im Feld - also genau der Fall,
     * den man beim Draufschauen richtig lesen koennen muss.
     */
    @Test
    fun `ohne Autorisierung steht armed false statt nichts`() {
        val o = fundament(app.aaps.fuse.core.controller.MealFoundation.Snapshot.none())
        assertFalse(o.getBoolean("armed"))
        assertEquals("NONE", o.getString("phase"))
        assertEquals(0.0, o.getDouble("totalBudgetU"), 1e-9)
        assertTrue(o.isNull("binding"))
    }

    /**
     * SOLL, RUECKSTAND UND dueU SIND DREI GROESSEN, nicht eine in drei Formen.
     *
     * `dueU` ist gerastert (ein Pumpenschritt) und gedeckelt. Aus ihm allein
     * ist nicht ablesbar, ob das Fundament knapp daneben oder weit hinterher
     * liegt - beides ergibt einen Schritt.
     */
    @Test
    fun `Soll Rueckstand und dueU sind im Export unterscheidbar`() {
        val o = fundament(fSnapshot(minuten = 60.0, seitUebergabeU = 0.0))
        assertEquals(0.75, o.getDouble("plannedTotalU"), 1e-9, "Soll: das volle Teilbudget")
        assertEquals(0.75, o.getDouble("backlogU"), 1e-9, "Rueckstand: alles davon offen")
        assertEquals(0.05, o.getDouble("dueU"), 1e-9, "dueU: EIN Schritt, kein Aufhol-Burst")
        assertEquals("ONE_STEP_PER_CYCLE", o.getString("binding"), "und die Bindung sagt, warum")
    }

    /**
     * EIN VORSPRUNG IST EIN NEGATIVER RUECKSTAND, keine Null.
     *
     * Hat eine gewoehnliche Korrektur die Mindestversorgung uebererfuellt,
     * muss das sichtbar sein - sonst sieht dieselbe Lage aus wie "gerade
     * genau erfuellt", und im Replay waere nicht unterscheidbar, ob eine
     * Aufteilung passt oder ob der normale Pfad sie ueberholt hat.
     */
    @Test
    fun `ein Vorsprung erscheint im Export als negativer Rueckstand`() {
        val o = fundament(fSnapshot(minuten = 30.0, ausBudgetU = 2.65, seitUebergabeU = 0.40))
        assertTrue(o.getDouble("backlogU") < 0.0, "Vorsprung MUSS als negativer Rueckstand erscheinen")
        assertEquals(0.0, o.getDouble("dueU"), 1e-9, "und nichts wird gefordert")
        assertEquals("COVERED_BY_DELIVERY", o.getString("binding"))
    }
}

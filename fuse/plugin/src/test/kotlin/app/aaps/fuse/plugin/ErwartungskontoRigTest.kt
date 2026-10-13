package app.aaps.fuse.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.extensions.round
import app.aaps.fuse.core.controller.ExpectationAccount
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.core.controller.MarkerReauthorization
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.fuse.plugin.ledger.LedgerFacts
import app.aaps.fuse.plugin.ledger.LedgerPublicationGate
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.io.File

/**
 * RIG-PROBE DES ERWARTUNGSKONTOS (Testkandidat E-034, Bauphase P6).
 *
 * Derselbe Pruefstand wie GN-A ([Fuse20GleichwertigkeitTest]): Runner,
 * Ledger, Publikation, Bolus-Rueckkopplung und ideale Pumpe. Die
 * Konfiguration ist SYNTHETISCH (die Standardwerte der Schluessel, Konto an,
 * Marker-Autorisierung an, Liveness-Kanal an) mit einer synthetischen
 * Erwartungskurve. Geprueft wird der MECHANISMUS - Buchung am Druck,
 * Ersetzung im Fenster, Budget nie aufgefuellt, kein Erwartungsanteil vor
 * Stabilitaet, ohne Vorschuss kein Budget -, nicht die Wirkung. Die Wirkung
 * bewertet der vorregistrierte Pruefplan.
 */
class ErwartungskontoRigTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var ads: AutosensDataStore
    @Mock lateinit var insulinProfileFunction: ProfileFunction
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var insulin: Insulin
    private lateinit var ledger: FuseLedgerAdapter
    private lateinit var runner: FuseCycleRunner

    private var folge: Fuse20Folgen.Folge = Fuse20Folgen.F1
    private var clock = 0L
    private var markerPress = 0L
    private var markerAt = 0L
    private var ohneVorschuss = false
    private var nurErwartung = false

    /** Erzwungene Ablehnung der Bahn (predictorfreier Markerpfad), `null` = echte Bahn. */
    private var predictReject: app.aaps.fuse.core.predictor.PredictorReason? = null

    private var boluses: List<BS> = emptyList()
    private var bolusId = 0L
    private var pumpe = Fuse20Folgen.Pumpe(Fuse20Folgen.BASAL_SCHRITT)
    private val aapsCache = HashMap<Long, IobTotal>()

    /** Synthetisch: F(5k) = 0,125 * k, also F(120) = 3,0 U. */
    private val kurve = (1..24).joinToString("; ") { (it * 0.125).toString() }

    /**
     * Mahlzeit mit STABILEM Start: BG ueber eine Stunde deutlich ueber der
     * Stabilitaetsgrenze, Marker in Minute 0. (In F1 liegt BG vor dem Marker
     * genau AUF der Grenze - dort bucht das Konto richtig als "nach Tief".)
     */
    private val fStabil = Fuse20Folgen.Folge(
        "STABIL", 1_700_301_600_000L, -90, 300,
        listOf(-90 to 118.0, -10 to 118.0, 0 to 120.0, 10 to 128.0, 55 to 225.0, 135 to 108.0, 150 to 100.0, 300 to 112.0),
        markerMin = 0,
    )

    /** Start nach Tief: BG um Minute 0 unter der Stabilitaetsgrenze, danach Anstieg. */
    private val fTief = Fuse20Folgen.Folge(
        "TIEF", 1_700_215_200_000L, -90, 240,
        listOf(-90 to 120.0, -40 to 100.0, -15 to 66.0, 0 to 72.0, 20 to 104.0, 60 to 185.0, 120 to 150.0, 240 to 118.0),
        markerMin = 0,
    )

    private fun roundUp(t: Long) = if (t % 60_000L == 0L) t else (t / 60_000L + 1) * 60_000L

    private fun aapsNahIob(atTs: Long, zwischenspeichern: Boolean = true): IobTotal {
        val key = if (zwischenspeichern) roundUp(atTs) else atTs
        if (zwischenspeichern) aapsCache[key]?.let { return it }
        val dia = validProfile.dia
        var iob = 0.0
        var akt = 0.0
        for (b in boluses) if (b.isValid && b.timestamp < key && b.timestamp >= key - (dia * 3_600_000.0).toLong()) {
            val r = insulin.iobCalcForTreatment(b, key, dia)
            iob += r.iobContrib
            akt += r.activityContrib
        }
        val bolus = IobTotal(key).also { it.iob = iob; it.activity = akt }.round()
        val ergebnis = IobTotal.combine(bolus, IobTotal(key).round()).round().also { it.valid = true }
        if (zwischenspeichern && key <= clock) aapsCache[key] = ergebnis
        return ergebnis
    }

    private fun boliSetzen(neu: List<BS>) {
        val betroffen = (boluses.filter { it !in neu } + neu.filter { it !in boluses }).minOfOrNull { it.timestamp }
        boluses = neu
        if (betroffen != null) aapsCache.keys.removeAll { it > betroffen }
    }

    private fun reihe(bis: Long): List<GV> = folge.cgm.filter { it.first <= bis }.map { (t, v) ->
        GV(timestamp = t, value = v, raw = v, noise = 0.0, sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT)
    }

    private fun testPumpe() = FuseActivePump(
        "GENERIC_AAPS", virtualPump = true, bolusStepU = 0.05, basalStepUPerH = Fuse20Folgen.BASAL_SCHRITT,
        gate = FusePumpGate.Result(FusePumpGate.Verdict.ALLOWED, "TestPump"),
    )

    @BeforeEach
    fun setup() {
        insulin = InsulinLyumjevPlugin(rh, insulinProfileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
        whenever(activePlugin.activeInsulin).thenReturn(insulin)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)
        testPumpPlugin.pumpDescription.bolusStep = 0.05

        whenever(dateUtil.now()).thenAnswer { clock }
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(profileFunction.getProfile(any())).thenReturn(validProfile)
        whenever(profileFunction.getProfileName()).thenReturn(TESTPROFILENAME)

        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.roundUpTime(any())).thenAnswer { inv -> roundUp(inv.getArgument(0)) }
        whenever(ads.getBgReadingsDataTableCopy()).thenAnswer { reihe(clock) }
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any())).thenAnswer { inv -> aapsNahIob(inv.getArgument(0)) }
        whenever(iobCobCalculator.calculateIobFromBolus()).thenAnswer { aapsNahIob(clock, zwischenspeichern = false) }
        whenever(iobCobCalculator.calculateIobToTimeFromTempBasalsIncludingConvertedExtended(any()))
            .thenAnswer { inv -> IobTotal(inv.getArgument(0)) }
        whenever(persistenceLayer.getExtendedBolusesStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())

        whenever(commandQueue.bolusInQueue()).thenReturn(false)
        whenever(commandQueue.isRunning(any())).thenReturn(false)

        whenever(persistenceLayer.getLastTherapyRecordUpToNow(any())).thenReturn(null)
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenReturn(null)
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenAnswer { inv ->
            val from = inv.getArgument<Long>(0)
            val to = inv.getArgument<Long>(1)
            boluses.filter { it.timestamp in from..to && it.isValid }
        }
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenAnswer { inv -> pumpe.sicht(inv.getArgument(0), clock) }
    }

    /** Synthetische Konfiguration: Standardwerte aller Schluessel, dazu die Ueberschreibungen dieser Probe. */
    private fun konfiguration(kontoAn: Boolean, phi0Stabil: Double = 0.5, phi0Tief: Double = 0.25) {
        whenever(preferences.get(anyOrNull<BooleanKey>())).thenReturn(false)
        FuseDoubleKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(k.defaultValue) }
        FuseIntKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(k.defaultValue) }
        FuseBooleanKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(k.defaultValue) }
        FuseStringKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(k.defaultValue) }
        whenever(preferences.get(FuseBooleanKey.MarkerAuthorisesRelease)).thenReturn(true)
        whenever(preferences.get(FuseBooleanKey.LivenessChannelEnabled)).thenReturn(true)
        whenever(preferences.get(FuseBooleanKey.ExpectationAccountEnabled)).thenReturn(kontoAn)
        whenever(preferences.get(FuseDoubleKey.ExpectationPhi0Stable)).thenReturn(phi0Stabil)
        whenever(preferences.get(FuseDoubleKey.ExpectationPhi0AfterLow)).thenReturn(phi0Tief)
        whenever(preferences.get(FuseStringKey.ExpectationCurve)).thenReturn(kurve)
        whenever(preferences.get(DoubleKey.ApsSmbMaxIob)).thenReturn(MAX_IOB)
        whenever(constraintsChecker.getMaxIOBAllowed()).thenAnswer { ConstraintObject(MAX_IOB, aapsLogger) }
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(-1L)
        FuseLongKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(0L) }
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenAnswer { markerAt }
        whenever(preferences.get(FuseLongKey.MealMarkerNoPrime)).thenAnswer { if (ohneVorschuss) 1L else 0L }
        whenever(preferences.get(FuseLongKey.MealMarkerExpectationOnly)).thenAnswer { if (nurErwartung) 1L else 0L }
    }

    private fun druecken(t: Long) {
        markerAt = t
        markerPress = t
        ledger.episodes.let { e ->
            val seq = e.markerAuthSeq + 1L
            val neu = MarkerReauthorization.autorisiere(seq, t, e.markerRevocation)
            e.markerAuthSeq = seq
            e.markerAuth = neu.auth
            neu.revocation?.let { e.markerRevocation = it }
        }
    }

    /** Ein Zyklus der Probe: das Ergebnis, das Konto danach und die gelieferte Menge. */
    private class Zyklus(val minute: Int, val o: FuseCycleRunner.Outcome, val konto: ExpectationAccount.State?, val geliefertU: Double)

    private fun neuerRunner(arm: FuseCycleRunner.Pruefarm?) = FuseCycleRunner(
        iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
        preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "ek-session", { markerPress },
        pruefarm = arm,
        rejoinPolicy = app.aaps.fuse.core.signal.RejoinPolicy.enabled(),
        predict = { input ->
            predictReject?.let { app.aaps.fuse.core.predictor.PredictorOutcome.Rejected(it, "erzwungen") }
                ?: app.aaps.fuse.core.predictor.TrajectoryCore.predict(input)
        },
    )

    /** Prozess-Neustart mitten in der Folge: Ledger von der Platte, neuer Runner - der Speicherzustand ist weg. */
    private fun neustart(dir: File, f: Fuse20Folgen.Folge, arm: FuseCycleRunner.Pruefarm?) {
        ledger = FuseLedgerAdapter().also { it.loadOnce(File(dir, "ledger_${f.name}"), "ek-epoch", clock) }
        runner = neuerRunner(arm)
    }

    private fun lauf(
        f: Fuse20Folgen.Folge,
        dir: File,
        arm: FuseCycleRunner.Pruefarm? = null,
        /** Vor jedem Zyklus, mit seiner Minute - fuer Eingriffe mitten in der Folge. */
        vorZyklus: (Int) -> Unit = {},
    ): List<Zyklus> {
        folge = f
        clock = f.ts(f.vonMin)
        boluses = emptyList()
        aapsCache.clear()
        bolusId = 0L
        pumpe = Fuse20Folgen.Pumpe(Fuse20Folgen.BASAL_SCHRITT)
        markerAt = 0L
        markerPress = 0L
        val ledgerDir = File(dir, "ledger_${f.name}").also(File::mkdirs)
        ledger = FuseLedgerAdapter().also { it.loadOnce(ledgerDir, "ek-epoch", clock) }
        runner = neuerRunner(arm)
        val serialHash = LedgerFacts.serialHashOf(Fuse20Folgen.PUMPE_SERIAL, PumpType.GENERIC_AAPS.name)
        val ergebnis = ArrayList<Zyklus>()
        while (clock + 60_000L <= f.ts(f.bisMin)) {
            if (f.markerMin != null && clock + 60_000L == f.ts(f.markerMin)) druecken(clock + 60_000L)
            clock += 60_000L
            vorZyklus(f.minute(clock))
            val evidenzVorLauf = ledger.episodes.evidenceState
            val o = runner.run(false, testPumpe())
            val units = o.decision.smbU.takeIf { it > 0.0 }
            val cycleId = "ek#${o.computeTs}"
            val rt = RT(
                algorithm = APSResult.Algorithm.FUSE, timestamp = o.computeTs,
                rate = null, duration = null, units = units, deliverAt = units?.let { o.computeTs },
            )
            val expected = LedgerPublicationGate.commitmentOf(
                units = rt.units, treatmentViewPresent = o.treatmentView != null, proposalId = cycleId,
            )
            val publication = LedgerPublicationGate.publish(
                rt = rt, adapter = ledger, dir = ledgerDir, expected = expected,
                published = InterventionStamp.Published(smbU = rt.units, tbrChanged = o.tbrChanged),
                events = {
                    val v = o.treatmentView
                    if (expected is LedgerPublicationGate.Commitment.Proposal && rt.units != null && v != null) {
                        ledger.onPublished(
                            proposalId = cycleId, unitsU = rt.units!!, decisionTs = o.computeTs,
                            latestBolusTs = v.latestBolusTs, bolusStepU = o.state?.pumpIncrementU ?: Double.NaN,
                            pumpTypeName = PumpType.GENERIC_AAPS.name, pumpSerialHash = serialHash, virtualPump = false,
                        )
                    }
                    if (v != null) {
                        ledger.bindIdentities(v.boluses)
                        ledger.onCycleSnapshot(v.facts, v.snapshotHash, o.computeTs)
                        ledger.prune(o.computeTs, v.diaHours)
                    }
                },
            )
            ledger.sealCycleState(publication.sealed, evidenzVorLauf)
            ledger.resolveReservation(o.computeTs, publication.rt.units ?: 0.0, proposalId = cycleId)
            val geliefert = publication.rt.units?.takeIf { publication.sealed && it > 0.0 }
            if (geliefert != null) {
                boliSetzen(
                    boluses + BS(
                        id = ++bolusId, timestamp = o.computeTs, amount = geliefert, type = BS.Type.SMB,
                        ids = IDs(pumpId = o.computeTs, pumpType = PumpType.GENERIC_AAPS, pumpSerial = Fuse20Folgen.PUMPE_SERIAL),
                    ),
                )
            }
            o.tbr?.takeIf { o.gate.allowed }?.let { req ->
                pumpe.anwenden(o.computeTs, req.rateUPerH, req.durationMin, testPumpe().baseBasalRateUPerH, validProfile.getBasal(o.computeTs))
            }
            ergebnis += Zyklus(f.minute(o.computeTs), o, ledger.episodes.expectation, geliefert ?: 0.0)
        }
        return ergebnis
    }

    // ---- Die Proben ------------------------------------------------------------

    @Test
    fun `ohne Konto gibt es weder Block noch Kontozustand`(@TempDir dir: File) {
        konfiguration(kontoAn = false)
        val zyklen = lauf(Fuse20Folgen.F1, dir)
        assertTrue(zyklen.size > 300)
        assertTrue(zyklen.all { it.o.erwartungskonto == null }) { "Konto aus: kein Exportblock" }
        assertTrue(zyklen.all { it.konto == null }) { "Konto aus: kein Kontozustand" }
        assertTrue(zyklen.none { it.o.livenessDenial == "EXPECTATION_ACCOUNT" })
    }

    @Test
    fun `mit Konto - Buchung am Druck, Ersetzung im Fenster, Budget nie aufgefuellt`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        val f = fStabil
        val alle = lauf(f, dir)
        // Abbruchzyklen (Anlauf ohne Historie) tragen keinen Block.
        val zyklen = alle.filter { it.o.abortReason == null }
        assertTrue(zyklen.size > 300)
        assertTrue(zyklen.all { it.o.erwartungskonto != null }) { "Konto an: Block in jedem vollen Zyklus" }

        // Vor dem Druck gilt das Konto nicht.
        assertTrue(zyklen.filter { it.minute < 0 }.none { it.o.erwartungskonto!!.gilt })

        // Genau ein Buchungszyklus, am Druck, mit der gepinnten Autorisierung.
        val gebucht = zyklen.filter { it.o.erwartungskonto!!.zyklus?.armiertInDiesemZyklus == true }
        assertEquals(listOf(0), gebucht.map { it.minute })
        val konto0 = gebucht.single().o.erwartungskonto!!.zyklus!!.state
        assertEquals(f.ts(0), konto0.authId)
        assertTrue(konto0.expectationOn)
        assertTrue(konto0.startStable, "stabiler Start")
        // A_0 = min(Huelle, phi_0 * F(120)) = min(Standard-Huelle, 0,5 * 3,0).
        assertEquals(minOf(FuseDoubleKey.PrimeEnvelopeU.defaultValue, 0.5 * 3.0), konto0.a0U, 1e-9)

        // Im Fenster: Ersetzung, Onset-Kanal, H8 und Liveness-Kanal aus.
        val imFenster = zyklen.filter { it.o.erwartungskonto!!.gilt }
        assertTrue(imFenster.isNotEmpty())
        assertTrue(imFenster.all { it.minute in 0 until 120 }) { "Fenster 0..120: ${imFenster.map { it.minute }}" }
        for (z in imFenster) {
            val x = z.o.erwartungskonto!!
            assertNotNull(x.zyklus, "Minute ${z.minute}: gilt, aber kein Zyklus")
            assertFalse(z.o.onset?.active == true, "Minute ${z.minute}: Onset-Kanal aktiv")
            assertFalse(z.o.earlyAdaptiveMeal.eligible, "Minute ${z.minute}: H8 geeignet")
            assertEquals("EXPECTATION_ACCOUNT", z.o.livenessDenial, "Minute ${z.minute}: Kanal nicht aus")
            z.o.decision.insulinReqU?.let { req ->
                assertEquals(ExpectationAccount.deliverableU(x.zyklus!!.eval), req, 1e-9, "Minute ${z.minute}: Bedarf nicht ersetzt")
            }
        }

        // Mechanismus: d_erw nur im Rahmen des Rests, nie Ueberschreitung,
        // Summe nie ueber A_0, gebucht nur steigend (Storno erklaert jede Senkung).
        var erwSumme = 0.0
        var vorher: ExpectationAccount.State? = null
        for (z in zyklen) {
            val x = z.o.erwartungskonto ?: continue
            x.buchung?.split?.let { s ->
                assertEquals(0.0, s.violationU, 1e-9, "Minute ${z.minute}: Ueberschreitung")
                assertTrue(s.dErwU <= x.zyklus!!.eval.restEffU + 1e-9) { "Minute ${z.minute}: d_erw ueber dem Rest" }
                erwSumme += s.dErwU
            }
            val k = z.konto
            if (k != null && vorher != null && k.authId == vorher!!.authId) {
                assertEquals(vorher!!.a0U, k.a0U, 1e-12, "A_0 aendert sich nie")
                assertTrue(k.bookedU >= vorher!!.bookedU - 1e-9 || k.cancelledU > vorher!!.cancelledU) {
                    "Minute ${z.minute}: gebucht sinkt ohne Storno"
                }
            }
            vorher = k
        }
        assertTrue(erwSumme <= konto0.a0U + 1e-9) { "d_erw-Summe $erwSumme ueber A_0" }

        // NICHT LEER: in dieser Mahlzeit fliesst Erwartung, die Direktdosis
        // bucht voll, und die Guard-Ausnahme hebt nur bei typisiertem
        // Guard-Grund. Die Buecher gehen auf: gebucht = Direktdosis + d_erw.
        val direktSumme = zyklen.sumOf { it.o.erwartungskonto?.buchung?.direktdosisU ?: 0.0 }
        assertTrue(erwSumme > 0.0) { "kein Erwartungsanteil geflossen" }
        assertTrue(direktSumme > 0.0) { "keine Direktdosis gebucht" }
        val hebungen = zyklen.filter { (it.o.erwartungskonto?.angehobenU ?: 0.0) > 0.0 }
        assertTrue(hebungen.isNotEmpty()) { "keine Guard-Ausnahme" }
        val guardGrund = app.aaps.fuse.core.controller.CandidateSearch.Reject.GUARD_FLOOR
        for (z in hebungen) {
            val x = z.o.erwartungskonto!!
            assertTrue(x.zyklus!!.guardAusnahme)
            // Gehoben wird nur das Guard-Urteil: Baseline-Guard oder typisierter Guard-Grund der Suche.
            assertTrue(x.blockNormalpfad == app.aaps.fuse.core.controller.FuseController.Block.GUARD_FLOOR || x.grundNormalpfad == guardGrund) {
                "Minute ${z.minute}: Hebung ohne Guard-Urteil (${x.blockNormalpfad}/${x.grundNormalpfad})"
            }
            assertTrue(x.angehobenU <= x.zyklus.eval.restEffU + 1e-9) { "Minute ${z.minute}: Hebung ueber dem Rest" }
        }
        // Jeder CANDIDATE-Zyklus traegt einen typisierten Grund (Mechanismuskriterium des Plans).
        for (z in zyklen) {
            val x = z.o.erwartungskonto ?: continue
            if (x.blockNormalpfad == app.aaps.fuse.core.controller.FuseController.Block.CANDIDATE)
                assertNotNull(x.grundNormalpfad, "Minute ${z.minute}: CANDIDATE ohne typisierten Grund")
        }
        val kontoEnde = zyklen.mapNotNull { it.konto }.last()
        assertEquals(direktSumme + erwSumme - kontoEnde.cancelledU, kontoEnde.bookedU, 1e-9, "Buecher gehen nicht auf")

        // Nach dem Fenster gilt das Konto nicht mehr, der Kanal laeuft wieder normal.
        val danach = zyklen.filter { it.minute >= 125 }
        assertTrue(danach.none { it.o.erwartungskonto!!.gilt })
        assertTrue(danach.none { it.o.livenessDenial == "EXPECTATION_ACCOUNT" })
    }

    @Test
    fun `ohne Vorschuss gibt es kein Budget und keinen Erwartungsanteil`(@TempDir dir: File) {
        ohneVorschuss = true
        try {
            konfiguration(kontoAn = true)
            val zyklen = lauf(fStabil, dir).filter { it.o.abortReason == null }
            val konto = zyklen.mapNotNull { it.o.erwartungskonto?.zyklus?.state }.first()
            assertEquals(0.0, konto.a0U, 0.0)
            assertTrue(zyklen.all { (it.o.erwartungskonto?.buchung?.split?.dErwU ?: 0.0) == 0.0 })
            // Die Ersetzung gilt trotzdem.
            assertTrue(zyklen.any { it.o.erwartungskonto!!.gilt && it.o.livenessDenial == "EXPECTATION_ACCOUNT" })
        } finally {
            ohneVorschuss = false
        }
    }

    @Test
    fun `nach Tief fliesst Erwartung erst nach der Stabilitaet`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        val zyklen = lauf(fTief, dir).filter { it.o.abortReason == null }
        val start = zyklen.first { it.o.erwartungskonto!!.zyklus?.armiertInDiesemZyklus == true }
        val konto = start.o.erwartungskonto!!.zyklus!!.state
        assertFalse(konto.startStable, "BG am Druck unter der Stabilitaetsgrenze")
        assertEquals(minOf(FuseDoubleKey.PrimeEnvelopeU.defaultValue, 0.25 * 3.0), konto.a0U, 1e-9)
        for (z in zyklen) {
            val zy = z.o.erwartungskonto?.zyklus ?: continue
            if (!zy.state.released || zy.pausiert) {
                assertEquals(0.0, zy.eval.restEffU, 0.0, "Minute ${z.minute}: Rest vor Stabilitaet oder in Pause")
                assertEquals(0.0, z.o.erwartungskonto!!.buchung?.split?.dErwU ?: 0.0, 0.0, "Minute ${z.minute}: d_erw")
            }
        }
        // Die Stabilitaet stellt sich im Anstieg ein - danach ist der Rest frei.
        val frei = zyklen.firstOrNull { it.o.erwartungskonto?.zyklus?.state?.released == true }
        assertNotNull(frei, "keine Freigabe nach Stabilitaet")
        assertTrue(frei!!.minute >= 15) { "Freigabe zu frueh: Minute ${frei.minute}" }
    }

    @Test
    fun `Arm REF ist der Kandidat mit Schalter aus`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        val zyklen = lauf(fStabil, dir, FuseCycleRunner.Pruefarm.REF)
        assertTrue(zyklen.all { it.o.erwartungskonto == null && it.konto == null })
        assertTrue(zyklen.none { it.o.livenessDenial == "EXPECTATION_ACCOUNT" })
    }

    @Test
    fun `Arm EK-S liefert das Budget ohne Direktdosis-Familie`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        val zyklen = lauf(fStabil, dir, FuseCycleRunner.Pruefarm.EK_S).filter { it.o.abortReason == null }
        val konto = zyklen.first { it.o.erwartungskonto!!.zyklus?.armiertInDiesemZyklus == true }.o.erwartungskonto!!.zyklus!!.state
        // phi_0 = 1,0: A_0 = min(Huelle, F(120)).
        assertEquals(minOf(FuseDoubleKey.PrimeEnvelopeU.defaultValue, 3.0), konto.a0U, 1e-9)
        val imFenster = zyklen.filter { it.o.erwartungskonto!!.gilt }
        assertTrue(imFenster.isNotEmpty())
        // Keine Direktdosis im Fenster: weder Sofortanteil noch Prime, Aufschub oder Ruhe.
        assertTrue(imFenster.all { (it.o.erwartungskonto!!.buchung?.direktdosisU ?: 0.0) == 0.0 }) { "Direktdosis im Fenster" }
        assertTrue(imFenster.all { it.o.decision.grant?.source != app.aaps.fuse.core.controller.AuthorizedLift.Source.PRIME })
        // Der Erwartungsanteil fliesst - in Schritten, nie ueber dem Rest.
        val erw = imFenster.sumOf { it.o.erwartungskonto!!.buchung?.split?.dErwU ?: 0.0 }
        assertTrue(erw > 0.0) { "kein Erwartungsanteil" }
        assertTrue(erw <= konto.a0U + 1e-9)
        assertTrue(imFenster.all { it.geliefertU <= FuseDoubleKey.MaxSmbU.defaultValue + 1e-9 }) { "Schritt ueber maxSMB" }
    }

    /** Die Direktdosis-Familie, wie sie in der Entscheidung als Quelle erscheint. */
    private val direktQuellen = setOf(
        app.aaps.fuse.core.controller.AuthorizedLift.Source.PRIME,
        app.aaps.fuse.core.controller.AuthorizedLift.Source.MEAL_UPFRONT,
    )

    /**
     * TONIS DRITTER WEG: die Marker-Wahl "nur Erwartung" macht im
     * Betrieb, was der Pruefstandsarm EK-S macht - keine Direktdosis, das
     * Budget traegt die Mahlzeit, in Schritten und nie ueber dem Rest.
     */
    @Test
    fun `nur Erwartung per Marker-Wahl liefert das Budget ohne Direktdosis`(@TempDir dir: File) {
        nurErwartung = true
        try {
            konfiguration(kontoAn = true, phi0Stabil = 1.0, phi0Tief = 1.0)
            val zyklen = lauf(fStabil, dir).filter { it.o.abortReason == null }
            val konto = zyklen.first { it.o.erwartungskonto!!.zyklus?.armiertInDiesemZyklus == true }.o.erwartungskonto!!.zyklus!!.state
            // Die Huelle bleibt fuer das Konto: A_0 = min(Huelle, F(120)), anders als "ohne Vorschuss".
            assertEquals(minOf(FuseDoubleKey.PrimeEnvelopeU.defaultValue, 3.0), konto.a0U, 1e-9)
            val imFenster = zyklen.filter { it.o.erwartungskonto!!.gilt }
            assertTrue(imFenster.isNotEmpty())
            assertTrue(imFenster.all { (it.o.erwartungskonto!!.buchung?.direktdosisU ?: 0.0) == 0.0 }) { "Direktdosis im Fenster" }
            assertTrue(zyklen.none { it.o.decision.grant?.source in direktQuellen }) { "Direktdosis-Quelle trotz nur Erwartung" }
            val erw = imFenster.sumOf { it.o.erwartungskonto!!.buchung?.split?.dErwU ?: 0.0 }
            assertTrue(erw > 0.0) { "kein Erwartungsanteil" }
            assertTrue(erw <= konto.a0U + 1e-9)
            assertTrue(imFenster.all { it.geliefertU <= FuseDoubleKey.MaxSmbU.defaultValue + 1e-9 }) { "Schritt ueber maxSMB" }
            // Die Wahl steht im Zustand (und damit im Trail).
            assertTrue(imFenster.all { it.o.state?.markerExpectationOnly == true })
        } finally {
            nurErwartung = false
        }
    }

    /**
     * Die Wahl gilt die GANZE Mahlzeit, auch ohne Konto: faellt das Konto aus
     * (hier: Schalter aus), gibt es trotzdem keine ueberraschende Direktdosis.
     * Gegenprobe: ohne die Wahl liefert derselbe Lauf eine Direktdosis.
     */
    @Test
    fun `nur Erwartung haelt die Direktdosis auch ohne Konto zurueck`(@TempDir dir: File) {
        konfiguration(kontoAn = false)
        val gegenprobe = lauf(fStabil, File(dir, "ohne")).filter { it.o.abortReason == null }
        assertTrue(gegenprobe.any { it.o.decision.grant?.source in direktQuellen }) { "Gegenprobe ohne Direktdosis - Probe zeigt nichts" }
        nurErwartung = true
        try {
            markerAt = 0L
            markerPress = 0L
            konfiguration(kontoAn = false)
            val zyklen = lauf(fStabil, File(dir, "mit")).filter { it.o.abortReason == null }
            assertTrue(zyklen.isNotEmpty())
            assertTrue(zyklen.none { it.o.decision.grant?.source in direktQuellen }) { "Direktdosis trotz nur Erwartung ohne Konto" }
        } finally {
            nurErwartung = false
        }
    }

    /** Fallen nach dem Marker mit Prime-Insulin an Bord: das spaete Abwaertsrisiko des Aufschubs greift. */
    private val fFall = Fuse20Folgen.Folge(
        "FALL", 1_700_388_000_000L, -90, 240,
        listOf(-90 to 150.0, -10 to 150.0, 0 to 152.0, 12 to 160.0, 45 to 98.0, 70 to 84.0, 120 to 95.0, 240 to 120.0),
        markerMin = 0,
    )

    /** Codex 11.10., Punkt 9: einmal aus, bleibt die Ausnahme fuer diese Episode entzogen. */
    @Test
    fun `Abschalten der Guard-Ausnahme entzieht das Recht fuer die ganze Episode`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        val zyklen = lauf(fStabil, dir, vorZyklus = { m ->
            if (m == 2) whenever(preferences.get(FuseBooleanKey.ExpectationGuardExceptionEnabled)).thenReturn(false)
            if (m == 4) whenever(preferences.get(FuseBooleanKey.ExpectationGuardExceptionEnabled)).thenReturn(true)
        }).filter { it.o.abortReason == null }
        val mitKonto = zyklen.filter { it.o.erwartungskonto?.zyklus != null }
        assertTrue(mitKonto.filter { it.minute < 2 }.all { it.o.erwartungskonto!!.zyklus!!.guardAusnahme })
        for (z in mitKonto.filter { it.minute >= 2 }) {
            assertFalse(z.o.erwartungskonto!!.zyklus!!.guardAusnahme, "Minute ${z.minute}: Ausnahme wieder an")
            assertEquals(0.0, z.o.erwartungskonto!!.angehobenU, 0.0, "Minute ${z.minute}: Hebung ohne Recht")
        }
        assertFalse(zyklen.mapNotNull { it.konto }.last().guardException, "Entzug im Kontozustand")
    }

    /** Codex 11.10., Punkt 8: ein verlorenes Konto wird nicht neu eroeffnet, der Rueckfall ist ausgewiesen. */
    @Test
    fun `ein verlorenes Konto wird nicht neu eroeffnet und der Rueckfall ist ausgewiesen`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        val zyklen = lauf(fStabil, dir, vorZyklus = { m -> if (m == 10) ledger.episodes.expectation = null })
            .filter { it.o.abortReason == null }
        assertEquals(listOf(0), zyklen.filter { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }.map { it.minute })
        val danach = zyklen.filter { it.minute in 10 until 120 }
        assertTrue(danach.isNotEmpty())
        for (z in danach) {
            val x = z.o.erwartungskonto!!
            assertFalse(x.gilt, "Minute ${z.minute}: Konto gilt wieder")
            assertEquals("ACCOUNT_LOST", x.rueckfall, "Minute ${z.minute}: Rueckfall nicht ausgewiesen")
            assertTrue(z.o.livenessDenial != "EXPECTATION_ACCOUNT", "Minute ${z.minute}: heutiger Pfad")
        }
        // Ausserhalb des Mahlzeitkontexts gibt es keinen Rueckfall.
        assertTrue(zyklen.filter { it.minute < 0 || it.minute >= 125 }.all { it.o.erwartungskonto!!.rueckfall == null })
    }

    /** Codex 11.10., Punkt 5: das spaete Abwaertsrisiko pausiert den Erwartungsanteil nach ANTEIL, auf allen Wegen. */
    @Test
    fun `das spaete Abwaertsrisiko pausiert den Erwartungsanteil`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        whenever(preferences.get(FuseBooleanKey.DeferredPrimeEnabled)).thenReturn(true)
        val zyklen = lauf(fFall, dir).filter { it.o.abortReason == null }
        val gesperrt = zyklen.filter { it.o.erwartungskonto?.zyklus?.pausenGruende?.contains("DESCENT_RISK_MARKER") == true }
        assertTrue(gesperrt.isNotEmpty()) { "die Probe erreicht das spaete Abwaertsrisiko nicht" }
        for (z in gesperrt) {
            val x = z.o.erwartungskonto!!
            assertTrue(x.zyklus!!.pausiert)
            assertEquals(0.0, x.zyklus.eval.restEffU, 0.0, "Minute ${z.minute}: Rest trotz Sperre")
            assertEquals(0.0, x.buchung?.split?.dErwU ?: 0.0, 0.0, "Minute ${z.minute}: d_erw trotz Sperre")
            assertEquals(0.0, x.angehobenU, 0.0, "Minute ${z.minute}: Hebung trotz Sperre")
        }
    }

    /**
     * Tief kurz nach dem Druck, danach langer Anstieg ohne Wende: die
     * Marker-Sonderrechte enden nach markerBoostMaxMin, das Tief liegt dann
     * noch im Rebound-Fenster. Heute greift die Bremse dort wieder; im
     * Mahlzeitkontext des Kontos nie (Tonis Regel).
     */
    private val fReboundNachDruck = Fuse20Folgen.Folge(
        "REBOUND", 1_700_474_400_000L, -90, 180,
        listOf(-90 to 110.0, -10 to 96.0, 0 to 88.0, 6 to 62.0, 14 to 62.0, 25 to 92.0, 110 to 196.0, 180 to 170.0),
        markerMin = 0,
    )

    @Test
    fun `das Rebound-Fenster blockiert die Mahlzeit im Kontokontext nie`(@TempDir dir: File) {
        val nachSonderrechten = 50..90
        fun laufMit(arm: FuseCycleRunner.Pruefarm): List<Zyklus> {
            konfiguration(kontoAn = true)
            whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(REBOUND_FENSTER_MIN)
            return lauf(fReboundNachDruck, File(dir, arm.name).also(File::mkdirs), arm)
                .filter { it.o.abortReason == null && it.o.state != null }
        }
        // Heute (Schalter aus): nach dem Ende der Sonderrechte greift das Fenster wieder.
        val heute = laufMit(FuseCycleRunner.Pruefarm.REF).filter { it.minute in nachSonderrechten }
        assertTrue(heute.isNotEmpty())
        assertTrue(heute.none { it.o.state!!.markerBoost }) { "die Sonderrechte enden in der Probe nicht" }
        assertTrue(heute.any { it.o.state!!.reboundWindow }) { "die Probe erreicht das Rebound-Fenster nicht" }
        // Konto an: im ganzen gepinnten Fenster entwaffnet - die Bedingung lag an, gebremst wird nicht.
        val imFenster = laufMit(FuseCycleRunner.Pruefarm.EK).filter { it.o.erwartungskonto?.gilt == true }
        assertTrue(imFenster.any { it.minute in nachSonderrechten }) { "das Konto gilt dort nicht" }
        assertTrue(imFenster.none { it.o.state!!.reboundWindow }) { "das Rebound-Fenster bremst im Kontokontext" }
        assertTrue(
            imFenster.filter { it.minute in nachSonderrechten }.any { it.o.state!!.reboundSuppressedByMarker },
        ) { "die Rebound-Bedingung lag nicht an" }
        // Zuordnungsarm EK-RBheute: wie heute.
        val rbHeute = laufMit(FuseCycleRunner.Pruefarm.EK_RB_HEUTE).filter { it.minute in nachSonderrechten }
        assertTrue(rbHeute.any { it.o.state!!.reboundWindow }) { "EK-RBheute weicht von heute ab" }
    }

    /**
     * Frage der Forschung: nach einem Rueckfall steuert der heutige
     * Pfad - dann darf auch die Rebound-Bremse nicht mehr am Konto haengen.
     * Das Konto geht mitten in der Episode verloren; nach den Sonderrechten
     * greift das Fenster wie heute.
     */
    @Test
    fun `nach einem Rueckfall bremst das Rebound-Fenster wie heute`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(REBOUND_FENSTER_MIN)
        val zyklen = lauf(fReboundNachDruck, dir, FuseCycleRunner.Pruefarm.EK, vorZyklus = { m ->
            if (m == 20) ledger.episodes.expectation = null
        }).filter { it.o.abortReason == null && it.o.state != null }
        val danach = zyklen.filter { it.minute in 50..90 && !it.o.state!!.markerBoost }
        assertTrue(danach.isNotEmpty())
        assertTrue(danach.all { it.o.erwartungskonto?.rueckfall == "ACCOUNT_LOST" }) { "der Rueckfall ist nicht ausgewiesen" }
        assertTrue(danach.none { it.o.state!!.reboundSuppressedByMarker }) { "die Bremse haengt nach dem Rueckfall am Konto" }
        assertTrue(danach.any { it.o.state!!.reboundWindow }) { "die Probe erreicht das Rebound-Fenster nicht" }
    }

    /**
     * K5b (Toni; Codex-Befunde P0/P2): stabiler Start, sofort ein Fall ins Tief
     * (der Vorgriff pausiert im Fallen, das Budget bleibt), dann ein schneller
     * stetiger Anstieg wie nach Rescue-KH - er besteht die Ruecknahme bei +30
     * und erfuellt eine neue Strecke.
     */
    private val fTiefNachDruck = Fuse20Folgen.Folge(
        "TIEFNACHDRUCK", 1_700_647_200_000L, -90, 240,
        listOf(-90 to 120.0, 0 to 120.0, 6 to 60.0, 16 to 160.0, 90 to 175.0, 240 to 150.0),
        markerMin = 0,
    )

    /**
     * Gemeinsamer Ablauf der Tief-Proben: freigegeben vor dem Tief, Ruecknahme
     * mit dem erwarteten Grund, PFLICHT-Wiederfreigabe erst nach einer vollen
     * Strecke, die nach dem letzten Tief (und nach einem Neustart) beginnt, mit
     * verfuegbarem Rest; dazwischen kein Rest und kein d_erw.
     */
    private fun pruefeTief(dir: File, abbruch: IntRange?, neustartMin: Int?, grund: String) {
        konfiguration(kontoAn = true)
        whenever(preferences.get(FuseDoubleKey.PrimeEnvelopeU)).thenReturn(HUELLE_PROBE)
        val arm = FuseCycleRunner.Pruefarm.EK_S
        val alle = lauf(fTiefNachDruck, dir, arm, vorZyklus = { m ->
            whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(
                if (abbruch != null && m in abbruch) UNGUELTIGE_REBOUND_DAUER else FuseIntKey.ReboundWindowMin.defaultValue,
            )
            if (m == neustartMin) neustart(dir, fTiefNachDruck, arm)
        })
        val tief = alle.filter { it.o.step?.safetyReasons?.isNotEmpty() == true }
        assertTrue(tief.isNotEmpty()) { "die Probe erreicht kein gemessenes Tief" }
        if (abbruch != null) {
            // Der Fehlerpfad des Befunds: JEDES Tief faellt in einen Abbruchzyklus vor dem Kontoschritt.
            assertTrue(tief.all { it.o.abortReason != null }) { "ein Tief-Zyklus lief bis zum Konto" }
        }
        val voll = alle.filter { it.o.abortReason == null && it.o.erwartungskonto?.zyklus != null }
        assertTrue(voll.first().o.erwartungskonto!!.zyklus!!.state.startStable) { "die Probe startet nicht stabil" }
        val ersterTiefTs = tief.minOf { it.o.computeTs }
        assertTrue(voll.last { it.o.computeTs < ersterTiefTs }.o.erwartungskonto!!.zyklus!!.eval.released) {
            "vor dem Tief nicht freigegeben - die Probe prueft keine Ruecknahme"
        }
        val ruecknahme = voll.first { it.o.erwartungskonto!!.zyklus!!.eval.releaseRevoked }
        assertEquals(grund, ruecknahme.o.erwartungskonto!!.zyklus!!.ruecknahmeGrund)
        val letztesTiefTs = tief.maxOf { it.o.computeTs }
        val neustartTs = neustartMin?.let { fTiefNachDruck.ts(it) } ?: Long.MIN_VALUE
        val wieder = voll.firstOrNull {
            it.o.computeTs > ruecknahme.o.computeTs && it.o.erwartungskonto!!.zyklus!!.eval.released
        } ?: throw AssertionError("keine Wiederfreigabe im Fenster")
        val strecke = wieder.o.erwartungskonto!!.stabilSeitTs ?: throw AssertionError("Strecke unbekannt")
        assertTrue(strecke > letztesTiefTs && strecke >= neustartTs) { "die Strecke beginnt vor dem Tief oder dem Neustart" }
        assertTrue(wieder.o.computeTs - strecke >= FuseIntKey.ExpectationStabilityMin.defaultValue * 60_000L) {
            "Wiederfreigabe ohne volle Strecke"
        }
        assertTrue(wieder.o.erwartungskonto!!.zyklus!!.eval.restEffU > 0.0) { "kein Rest bei der Wiederfreigabe" }
        for (z in voll.filter { it.o.computeTs >= ruecknahme.o.computeTs && it.o.computeTs < wieder.o.computeTs }) {
            val x = z.o.erwartungskonto!!
            assertFalse(x.zyklus!!.eval.released, "Minute ${z.minute}: frei ohne Strecke")
            assertEquals(0.0, x.zyklus.eval.restEffU, 0.0, "Minute ${z.minute}: Rest ohne Freigabe")
            assertEquals(0.0, x.buchung?.split?.dErwU ?: 0.0, 0.0, "Minute ${z.minute}: d_erw ohne Freigabe")
        }
    }

    @Test
    fun `ein gemessenes Tief nimmt die Freigabe zurueck und verlangt eine volle neue Strecke`(@TempDir dir: File) =
        pruefeTief(dir, abbruch = null, neustartMin = null, grund = "LOW")

    /** Codex-Befund P0: das Tief faellt nur in Abbruchzyklen vor dem Kontoschritt. */
    @Test
    fun `ein Tief in Abbruchzyklen nimmt die Freigabe ebenso zurueck`(@TempDir dir: File) =
        pruefeTief(dir, abbruch = 5..13, neustartMin = null, grund = "LOW_ON_ABORT")

    /** Codex-Befund P0: Tief in Abbruchzyklen, danach ein Neustart - die Ruecknahme geht nicht verloren. */
    @Test
    fun `nach Tief im Abbruch und Neustart braucht die Freigabe eine Strecke im neuen Prozess`(@TempDir dir: File) =
        pruefeTief(dir, abbruch = 5..13, neustartMin = 16, grund = "LOW_ON_ABORT")

    /** Codex-Befund P0: auch ohne Tief verlangt eine nach dem Neustart noch freigegebene Episode die Strecke neu. */
    @Test
    fun `nach einem Neustart braucht eine freigegebene Episode die Strecke neu`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        val neustartMin = 30
        val arm = FuseCycleRunner.Pruefarm.EK_S
        val voll = lauf(fStabil, dir, arm, vorZyklus = { m -> if (m == neustartMin) neustart(dir, fStabil, arm) })
            .filter { it.o.abortReason == null && it.o.erwartungskonto?.zyklus != null }
        assertTrue(voll.last { it.minute < neustartMin }.o.erwartungskonto!!.zyklus!!.eval.released)
        val erster = voll.first { it.minute >= neustartMin }
        assertTrue(erster.o.erwartungskonto!!.zyklus!!.eval.releaseRevoked) { "nach dem Neustart nicht zurueckgenommen" }
        assertEquals("RESTART", erster.o.erwartungskonto!!.zyklus!!.ruecknahmeGrund)
        val wieder = voll.firstOrNull { it.minute > neustartMin && it.o.erwartungskonto!!.zyklus!!.eval.released }
            ?: throw AssertionError("keine Wiederfreigabe nach dem Neustart")
        assertTrue(wieder.o.computeTs - fStabil.ts(neustartMin) >= FuseIntKey.ExpectationStabilityMin.defaultValue * 60_000L) {
            "Freigabe ohne volle Strecke nach dem Neustart"
        }
        for (z in voll.filter { it.minute >= neustartMin && it.o.computeTs < wieder.o.computeTs }) {
            assertEquals(0.0, z.o.erwartungskonto!!.buchung?.split?.dErwU ?: 0.0, 0.0, "Minute ${z.minute}: d_erw ohne Freigabe")
        }
    }

    /** Codex-Befund P1: auch im predictorfreien Pfad liefert EK-S keinen Prime-Anteil. */
    @Test
    fun `EK-S liefert auch im predictorfreien Pfad keinen Prime-Anteil`(@TempDir dir: File) {
        fun laufMit(arm: FuseCycleRunner.Pruefarm): List<Zyklus> {
            konfiguration(kontoAn = true)
            return lauf(fStabil, File(dir, arm.name).also(File::mkdirs), arm, vorZyklus = { m ->
                predictReject = if (m in 0..10) app.aaps.fuse.core.predictor.PredictorReason.PENDING_MODEL_TOO_SHORT else null
            }).filter { it.o.abortReason == null && it.o.markerFallbackUsed }
        }
        val prime = app.aaps.fuse.core.controller.AuthorizedLift.Source.PRIME
        // Gegenprobe: im Hauptarm liefert der predictorfreie Pfad hier wirklich Prime.
        val ek = laufMit(FuseCycleRunner.Pruefarm.EK)
        assertTrue(ek.isNotEmpty()) { "kein predictorfreier Zyklus" }
        assertTrue(ek.any { it.o.decision.grant?.source == prime && it.geliefertU > 0.0 }) {
            "der Prime-Plan ist in der Probe nicht lieferfaehig - sonst prueft der Fall nichts"
        }
        val s = laufMit(FuseCycleRunner.Pruefarm.EK_S)
        assertTrue(s.isNotEmpty()) { "kein predictorfreier Zyklus in EK-S" }
        assertTrue(s.none { it.o.decision.grant?.source == prime }) { "EK-S liefert Prime im predictorfreien Pfad" }
    }

    // ---- E-034 v2: FRUEHE VERSORGUNG - N1 (Mechanik), je mit Gegenprobe --------------

    /**
     * Synthetische v2-Einstellung: Konto an, "Nur Erwartung", Kurve wie oben, dazu die fruehe Versorgung. Die vier
     * Schalter G1..G4 einzeln, damit jede Probe ihre Gegenprobe ohne genau diesen Schalter hat.
     */
    private fun fruehKonfiguration(
        g1: Boolean = false, g2: Boolean = false, g3: Boolean = false, g4: Boolean = false,
        anteil: Double = 0.5, portionU: Double = 1.0, zeitraumU: Double = 2.0, budgetU: Double = FRUEH_BUDGET_U,
        skalaTief: Double = 1.0,
    ) {
        konfiguration(kontoAn = true, phi0Stabil = 1.0, phi0Tief = 1.0)
        whenever(preferences.get(FuseBooleanKey.EarlySupplyEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyScaleStable)).thenReturn(1.0)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyScaleAfterLow)).thenReturn(skalaTief)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyBudgetMaxU)).thenReturn(budgetU)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyInitialShare)).thenReturn(anteil)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyMaxPortionU)).thenReturn(portionU)
        whenever(preferences.get(FuseDoubleKey.EarlySupplyMaxPerPeriodU)).thenReturn(zeitraumU)
        whenever(preferences.get(FuseIntKey.EarlySupplyPeriodMin)).thenReturn(ZEITRAUM_MIN)
        whenever(preferences.get(FuseBooleanKey.EarlySupplyInitialLiftsGuard)).thenReturn(g1)
        whenever(preferences.get(FuseBooleanKey.EarlySupplyInitialLiftsTail)).thenReturn(g2)
        whenever(preferences.get(FuseBooleanKey.EarlySupplyGuardWithExpectation)).thenReturn(g3)
        whenever(preferences.get(FuseBooleanKey.EarlySupplyTailWithExpectation)).thenReturn(g4)
    }

    /** Ein v2-Lauf: nur volle Zyklen; "Nur Erwartung" ist waehrend des Laufs gewaehlt. */
    private fun fruehLauf(
        f: Fuse20Folgen.Folge, dir: File, vorZyklus: (Int) -> Unit = {}, einstellen: () -> Unit,
    ): List<Zyklus> {
        nurErwartung = true
        try {
            markerAt = 0L
            markerPress = 0L
            einstellen()
            return lauf(f, dir, vorZyklus = vorZyklus).filter { it.o.abortReason == null }
        } finally {
            nurErwartung = false
        }
    }

    private fun Zyklus.dErw() = o.erwartungskonto?.buchung?.split?.dErwU ?: 0.0
    private fun Zyklus.frueh() = o.erwartungskonto?.zyklus?.eval?.early

    /** Die Pflichten, die fuer JEDE Einstellung gelten (N1). */
    private fun pruefeMechanik(zyklen: List<Zyklus>, anteil: Double, portionU: Double, zeitraumU: Double) {
        val konto = zyklen.mapNotNull { it.konto }.last()
        assertNotNull(konto.early) { "kein v2-Konto" }
        assertFalse(konto.guardException, "v1-Ausnahme in v2")
        assertTrue(zyklen.none { it.o.decision.grant?.source in direktQuellen }) { "Direktdosis-Quelle in v2" }
        // Budget nie ueberschritten.
        val summe = zyklen.sumOf { it.dErw() }
        assertTrue(summe <= konto.a0U + 1e-9) { "Budget ueberschritten: $summe > ${konto.a0U}" }
        assertTrue(zyklen.all { (it.o.erwartungskonto?.buchung?.split?.violationU ?: 0.0) == 0.0 }) { "Verstoss gebucht" }
        // Anfangsportion hoechstens einmal, hoechstens alpha * B, nur im Fenster.
        val anfang = zyklen.filter { it.frueh()?.initial == true && it.dErw() > 0.0 }
        assertTrue(anfang.size <= 1) { "mehr als eine Anfangsportion: ${anfang.map { it.minute }}" }
        anfang.forEach {
            assertTrue(it.dErw() <= anteil * konto.a0U + 1e-9) { "Anfangsportion ueber dem Anteil" }
            assertTrue(it.minute < app.aaps.fuse.core.controller.EarlySupply.INITIAL_WINDOW_MIN) { "Anfangsportion spaet" }
        }
        // Jede weitere Portion hoechstens p_max; je Zeitraum hoechstens q (gleitend).
        val weitere = zyklen.filter { it.frueh()?.initial == false && it.dErw() > 0.0 }
        weitere.forEach { assertTrue(it.dErw() <= portionU + 1e-9) { "Minute ${it.minute}: Portion ${it.dErw()} > $portionU" } }
        for (z in weitere) {
            val imZeitraum = weitere.filter { it.minute in (z.minute - ZEITRAUM_MIN + 1)..z.minute }.sumOf { it.dErw() }
            assertTrue(imZeitraum <= zeitraumU + 1e-9) { "Minute ${z.minute}: $imZeitraum je Zeitraum > $zeitraumU" }
        }
        // Erwartungsanteil nur, wenn das Konto ihn erlaubt (nicht pausiert, freigegeben, nicht eingefroren).
        for (z in zyklen.filter { it.dErw() > 0.0 }) {
            val k = z.o.erwartungskonto!!.zyklus!!
            assertFalse(k.pausiert, "Minute ${z.minute}: Portion in Pause ${k.pausenGruende}")
            assertTrue(k.eval.released && !k.eval.frozen && !k.eval.expired) { "Minute ${z.minute}: Portion ohne Freigabe" }
        }
    }

    @Test
    fun `fruehe Versorgung - Budget aus Kurve und Insulinwirkung, Mechanik haelt fuer jede Probe`(@TempDir dir: File) {
        val zyklen = fruehLauf(fStabil, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        val konto = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        assertEquals(0, konto.minute)
        val s = konto.o.erwartungskonto!!.zyklus!!.state
        // Insulin wirkt verzoegert: die fruehe Gabe ist groesser als die Kurve am Ende (3,0 U), gedeckelt bei B_max.
        assertTrue(s.a0U > 3.0) { "Budget ${s.a0U} nicht ueber der Kurve" }
        assertTrue(s.a0U <= FRUEH_BUDGET_U + 1e-9)
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        assertTrue(zyklen.sumOf { it.dErw() } > 0.0) { "kein Erwartungsanteil" }
        // Export: der v2-Block traegt Budget und Anforderung.
        assertTrue(zyklen.filter { it.o.erwartungskonto?.gilt == true }.all { it.frueh() != null })
    }

    @Test
    fun `G1 und G2 heben die Anfangsportion - Gegenprobe ohne sie`(@TempDir dir: File) {
        val mit = fruehLauf(fStabil, File(dir, "mit")) { fruehKonfiguration(g1 = true, g2 = true) }
        pruefeMechanik(mit, 0.5, 1.0, 2.0)
        val anfangMit = mit.single { it.frueh()?.initial == true && it.dErw() > 0.0 }
        val x = anfangMit.o.erwartungskonto!!
        assertEquals(true, x.fruehHebung?.guardLift)
        assertEquals(true, x.fruehHebung?.tailLift)
        assertTrue(anfangMit.geliefertU > FuseDoubleKey.MaxSmbU.defaultValue + 1e-9) { "Anfangsportion nur maxSMB" }

        val ohne = fruehLauf(fStabil, File(dir, "ohne")) { fruehKonfiguration(g1 = false, g2 = false) }
        pruefeMechanik(ohne, 0.5, 1.0, 2.0)
        val gleicheMinute = ohne.single { it.minute == anfangMit.minute }
        assertTrue(gleicheMinute.geliefertU < anfangMit.geliefertU - 1e-9) {
            "ohne G1/G2 dieselbe Anfangsportion (${gleicheMinute.geliefertU}) - Probe zeigt nichts"
        }
        assertNotNull(gleicheMinute.o.erwartungskonto!!.fruehEndgrund) { "ohne G1/G2 ohne Prueferurteil" }
    }

    @Test
    fun `weitere Portionen - G3 und G4 pruefen mit Erwartung, Gegenprobe ohne sie`(@TempDir dir: File) {
        val mit = fruehLauf(fStabil, File(dir, "mit")) { fruehKonfiguration(anteil = 0.0, g3 = true, g4 = true) }
        pruefeMechanik(mit, 0.0, 1.0, 2.0)
        val weitereMit = mit.filter { it.frueh()?.initial == false && it.dErw() > 0.0 }
        assertTrue(weitereMit.isNotEmpty()) { "keine weitere Portion mit G3/G4" }
        assertTrue(weitereMit.all { it.o.erwartungskonto!!.fruehHebung?.guardLift == true })

        val ohne = fruehLauf(fStabil, File(dir, "ohne")) { fruehKonfiguration(anteil = 0.0) }
        pruefeMechanik(ohne, 0.0, 1.0, 2.0)
        // Bis zur ersten Abweichung laufen beide Laeufe gleich; dort liefert der Lauf mit Erwartung mehr, und der
        // Lauf ohne traegt das Urteil, das ihn begrenzt hat.
        val erste = mit.zip(ohne).first { (a, b) -> kotlin.math.abs(a.geliefertU - b.geliefertU) > 1e-9 }
        assertTrue(erste.first.geliefertU > erste.second.geliefertU) {
            "Minute ${erste.first.minute}: mit G3/G4 ${erste.first.geliefertU}, ohne ${erste.second.geliefertU}"
        }
        assertNotNull(erste.second.o.erwartungskonto!!.fruehEndgrund) { "ohne G3/G4 ohne Prueferurteil" }
    }

    @Test
    fun `harte Sperren - in der Pause keine Portion, nach der Pause kein Nachholstoss`(@TempDir dir: File) {
        val zyklen = fruehLauf(fStabil, dir, vorZyklus = { m ->
            whenever(commandQueue.bolusInQueue()).thenReturn(m in 3..17)
        }) { fruehKonfiguration(anteil = 0.0, g3 = true, g4 = true) }
        pruefeMechanik(zyklen, 0.0, 1.0, 2.0)
        val pause = zyklen.filter { it.minute in 3..17 && it.o.erwartungskonto?.zyklus != null }
        assertTrue(pause.isNotEmpty())
        for (z in pause) {
            val k = z.o.erwartungskonto!!.zyklus!!
            assertTrue("PUMP_BUSY" in k.pausenGruende) { "Minute ${z.minute}: belegte Pumpe ohne Pause" }
            assertEquals(0.0, k.eval.restEffU, 0.0)
            assertEquals(0.0, z.dErw(), 0.0, "Minute ${z.minute}: Portion trotz Pause")
        }
        // Erste Portion nach der Pause: hoechstens eine Portion, kein Nachholen der verpassten Zyklen.
        val danach = zyklen.firstOrNull { it.minute > 17 && it.dErw() > 0.0 }
        assertNotNull(danach) { "nach der Pause keine Portion - Probe zeigt nichts" }
        assertTrue(danach!!.dErw() <= 1.0 + 1e-9)
    }

    @Test
    fun `der Hauptschalter entzieht die fruehe Versorgung fuer den Rest der Mahlzeit`(@TempDir dir: File) {
        val zyklen = fruehLauf(fStabil, dir, vorZyklus = { m ->
            whenever(preferences.get(FuseBooleanKey.EarlySupplyEnabled)).thenReturn(m !in 5..7)
        }) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val danach = zyklen.filter { it.minute >= 5 && it.o.erwartungskonto?.zyklus != null }
        assertTrue(danach.isNotEmpty())
        for (z in danach) {
            val k = z.o.erwartungskonto!!.zyklus!!
            assertTrue("EARLY_SUPPLY_OFF" in k.pausenGruende) { "Minute ${z.minute}: wieder an nach Abschalten" }
            assertEquals(0.0, z.dErw(), 0.0, "Minute ${z.minute}: Portion nach Abschalten")
        }
        assertTrue(zyklen.mapNotNull { it.konto }.last().early!!.withdrawn)
    }

    /** Bank N2, A1, Befund 2: auf die unbestaetigte Erwartung baut nur die Anfangsportion. */
    @Test
    fun `vor +20 rechnen Guard und Schwanz keine Erwartung an`(@TempDir dir: File) {
        val zyklen = fruehLauf(fStabil, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val frueh = zyklen.filter { it.minute in 0..19 && it.frueh() != null }
        assertTrue(frueh.isNotEmpty())
        assertTrue(frueh.all { it.frueh()!!.guardScale == 0.0 }) { "Anrechnung vor +20" }
        // In der Probe steigt BG deutlich: danach wird die Mahlzeit bestaetigt.
        assertTrue(zyklen.any { it.minute >= 20 && (it.frueh()?.guardScale ?: 0.0) > 0.0 }) { "nie bestaetigt" }
    }

    /** Tief 50 min vor dem Druck, danach schneller Anstieg ueber die Stabilitaetsgrenze. */
    private val fTiefDavor = Fuse20Folgen.Folge(
        "TIEF_DAVOR", 1_700_647_200_000L, -90, 180,
        listOf(-90 to 110.0, -55 to 70.0, -50 to 60.0, -40 to 88.0, -25 to 112.0, 0 to 118.0, 60 to 190.0, 180 to 120.0),
        markerMin = 0,
    )

    /** Bank N2, A1, Befund 3: ein Tief im Rebound-Fenster setzt die Startlage auf "nach Tief". */
    @Test
    fun `ein Tief im Rebound-Fenster vor dem Druck gilt als Start nach Tief`(@TempDir dir: File) {
        val zyklen = fruehLauf(fTiefDavor, dir) {
            fruehKonfiguration(g1 = true, g2 = true, skalaTief = 0.0)
            whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(REBOUND_FENSTER_MIN)
        }
        val gebucht = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        val k = gebucht.o.erwartungskonto!!.zyklus!!
        assertTrue(k.stabil) { "Probe zeigt nichts: die Strecke ist nicht erfuellt" }
        assertFalse(k.state.startStable)
        assertEquals(0.0, k.state.a0U, 0.0)
        assertEquals(0.0, zyklen.sumOf { it.dErw() }, 0.0)
    }

    /** Leichtes Abdriften vor dem Druck (unter einem halben mg/dl je Minute), BG weit ueber der Grenze. */
    private val fDrift = Fuse20Folgen.Folge(
        "DRIFT", 1_700_733_600_000L, -90, 180,
        listOf(-90 to 140.0, -40 to 132.0, 0 to 120.0, 60 to 200.0, 180 to 130.0),
        markerMin = 0,
    )

    /** Bank N2, A1, Befund 1: die Fallgrenze der Strecke ist einstellbar - Standard unveraendert, Gegenprobe mit 1. */
    @Test
    fun `die Fallgrenze der Strecke entscheidet ueber die Startlage bei leichtem Abdriften`(@TempDir dir: File) {
        val standard = fruehLauf(fDrift, File(dir, "standard")) { fruehKonfiguration(g1 = true, g2 = true, skalaTief = 0.0) }
        val kStandard = standard.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }.o.erwartungskonto!!.zyklus!!
        assertFalse(kStandard.state.startStable) { "Probe zeigt nichts: leichtes Abdriften gilt schon mit Standard nicht als Fallen" }
        val locker = fruehLauf(fDrift, File(dir, "locker")) {
            fruehKonfiguration(g1 = true, g2 = true, skalaTief = 0.0)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(1.0)
        }
        val kLocker = locker.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }.o.erwartungskonto!!.zyklus!!
        assertTrue(kLocker.state.startStable)
        assertEquals(1.0, kLocker.state.stabilityFallMgdlPerMin, 0.0)
        assertTrue(kLocker.state.a0U > 0.0)
        pruefeMechanik(locker, 0.5, 1.0, 2.0)
    }

    /** Bank N2, W3: Rescue-KH nach einem Tief sind keine bestaetigte Mahlzeit - das Tief beendet die fruehe Versorgung. */
    @Test
    fun `ein gemessenes Tief nach der Freigabe beendet die fruehe Versorgung der Mahlzeit`(@TempDir dir: File) {
        val zyklen = fruehLauf(fTiefNachDruck, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val tief = zyklen.firstOrNull { it.o.erwartungskonto?.zyklus?.ruecknahmeGrund == "LOW" }
        assertNotNull(tief) { "die Probe erreicht kein gemessenes Tief nach der Freigabe" }
        val danach = zyklen.filter { it.minute >= tief!!.minute && it.o.erwartungskonto?.zyklus != null }
        assertTrue(danach.all { it.konto?.earlyEndedByLow == true }) { "nicht beendet" }
        assertEquals(0.0, danach.sumOf { it.dErw() }, 0.0)
        assertTrue(danach.filter { it.o.erwartungskonto!!.zyklus!!.eval.status != "EXPIRED" }
            .all { it.o.erwartungskonto!!.zyklus!!.eval.status == "ENDED_BY_LOW" })
        // Gegenprobe: die Strecke gibt die Freigabe zurueck - ohne die Regel floessen wieder Portionen.
        assertTrue(danach.any { it.o.erwartungskonto!!.zyklus!!.eval.released }) { "keine Wiederfreigabe - Probe zeigt nichts" }
    }

    @Test
    fun `das spaete Abwaertsrisiko sperrt jede Portion der fruehen Versorgung`(@TempDir dir: File) {
        val zyklen = fruehLauf(fFall, dir) {
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
            whenever(preferences.get(FuseBooleanKey.DeferredPrimeEnabled)).thenReturn(true)
        }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val gesperrt = zyklen.filter { it.o.erwartungskonto?.zyklus?.pausiert == true }
        assertTrue(gesperrt.isNotEmpty()) { "die Probe erreicht keine Sperre" }
        for (z in gesperrt) {
            assertEquals(0.0, z.dErw(), 0.0, "Minute ${z.minute}: d_erw trotz Sperre")
            assertEquals(0.0, z.o.erwartungskonto!!.fruehBodenU, 0.0, "Minute ${z.minute}: Boden trotz Sperre")
        }
    }

    /**
     * Die Mahlzeit bleibt aus: BG flach am Ziel. Das Gestell rechnet offen (BG reagiert nicht auf Insulin), also
     * liefert diese Probe KEIN v2-Insulin - sonst saehe die Beobachtung das gelieferte Insulin als Mahlzeit. Dass
     * eine eingefrorene Episode keine Portion liefert, prueft [pruefeMechanik] in jeder Probe.
     */
    private val fFlach = Fuse20Folgen.Folge(
        "FLACH", 1_700_560_800_000L, -90, 180,
        listOf(-90 to 100.0, 0 to 100.0, 180 to 100.0),
        markerMin = 0,
    )

    @Test
    fun `bleibt die Mahlzeit aus, friert die Ruecknahme bei +30 die fruehe Versorgung ein`(@TempDir dir: File) {
        val zyklen = fruehLauf(fFlach, dir) { fruehKonfiguration(anteil = 0.0, portionU = 0.0, zeitraumU = 0.0) }
        pruefeMechanik(zyklen, 0.0, 0.0, 0.0)
        assertTrue(zyklen.filter { it.minute in 0..29 }.none { it.o.erwartungskonto?.zyklus?.eval?.frozen == true })
        val nach30 = zyklen.filter { it.minute in 30..120 && it.o.erwartungskonto?.zyklus != null }
        assertTrue(nach30.isNotEmpty())
        assertTrue(nach30.all { it.o.erwartungskonto!!.zyklus!!.eval.frozen }) { "nicht eingefroren" }
        assertTrue(nach30.all { it.o.erwartungskonto!!.zyklus!!.eval.restEffU == 0.0 })
        // Die Anpassung nimmt den Skalierer ab +20 zurueck, nie ueber den eingestellten.
        assertTrue(zyklen.filter { it.minute in 21..30 }.all { (it.frueh()?.scaleEff ?: 0.0) <= 1.0 + 1e-12 })
    }

    @Test
    fun `nach Tief gibt es keine Anfangsportion ausserhalb ihres Fensters`(@TempDir dir: File) {
        val zyklen = fruehLauf(fTief, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val konto = zyklen.mapNotNull { it.konto }.last()
        assertFalse(konto.startStable)
        val ersteFreigabe = zyklen.first { it.o.erwartungskonto?.zyklus?.eval?.released == true }.minute
        if (ersteFreigabe >= app.aaps.fuse.core.controller.EarlySupply.INITIAL_WINDOW_MIN)
            assertTrue(zyklen.none { it.frueh()?.initial == true && it.dErw() > 0.0 }) { "spaete Anfangsportion" }
        assertTrue(zyklen.filter { it.minute < ersteFreigabe }.all { it.dErw() == 0.0 }) { "Portion vor der Freigabe" }
    }

    @Test
    fun `Abschalten von G3 wirkt sofort, Einschalten erst ab dem naechsten Marker`(@TempDir dir: File) {
        val zyklen = fruehLauf(fStabil, dir, vorZyklus = { m ->
            if (m == 8) whenever(preferences.get(FuseBooleanKey.EarlySupplyGuardWithExpectation)).thenReturn(false)
            if (m == 12) whenever(preferences.get(FuseBooleanKey.EarlySupplyGuardWithExpectation)).thenReturn(true)
        }) { fruehKonfiguration(anteil = 0.0, g3 = true, g4 = true) }
        for (z in zyklen.filter { it.minute >= 8 && it.o.erwartungskonto?.zyklus != null }) {
            assertEquals(false, z.o.erwartungskonto!!.zyklus!!.fruehSchalter?.guardWithExpectation, "Minute ${z.minute}")
            assertTrue(z.o.erwartungskonto!!.fruehHebung?.guardLift != true) { "Minute ${z.minute}: G3 nach Abschalten" }
        }
    }

    @Test
    fun `ohne Nur Erwartung bleibt das Konto v1, auch mit eingeschalteter frueher Versorgung`(@TempDir dir: File) {
        fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
        val zyklen = lauf(fStabil, dir).filter { it.o.abortReason == null }
        val konto = zyklen.mapNotNull { it.konto }.last()
        assertNull(konto.early)
        assertTrue(zyklen.all { it.o.erwartungskonto?.zyklus?.eval?.early == null })
    }

    private companion object {

        /** Synthetisches maxIOB des Gestells. */
        const val MAX_IOB = 7.0

        /** Synthetische Rebound-Dauer der Probe [min]: das Tief reicht ueber das Ende der Sonderrechte. */
        const val REBOUND_FENSTER_MIN = 90

        /** Unter der Kerngrenze: die Konfiguration scheitert, der Zyklus bricht NACH dem Observer-Schritt ab. */
        const val UNGUELTIGE_REBOUND_DAUER = 10

        /** Synthetische Huelle der Tief-Proben [U]: genug Budget, dass nach der Wiederfreigabe ein Rest bleibt. */
        const val HUELLE_PROBE = 3.0

        /** Synthetische Obergrenze des v2-Budgets [U]. */
        const val FRUEH_BUDGET_U = 5.0

        /** Synthetischer Zeitraum der v2-Proben [min]. */
        const val ZEITRAUM_MIN = 10
    }
}

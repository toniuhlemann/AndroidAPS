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

    private companion object {

        /** Synthetisches maxIOB des Gestells. */
        const val MAX_IOB = 7.0

        /** Synthetische Rebound-Dauer der Probe [min]: das Tief reicht ueber das Ende der Sonderrechte. */
        const val REBOUND_FENSTER_MIN = 90

        /** Unter der Kerngrenze: die Konfiguration scheitert, der Zyklus bricht NACH dem Observer-Schritt ab. */
        const val UNGUELTIGE_REBOUND_DAUER = 10

        /** Synthetische Huelle der Tief-Proben [U]: genug Budget, dass nach der Wiederfreigabe ein Rest bleibt. */
        const val HUELLE_PROBE = 3.0
    }
}

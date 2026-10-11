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

    private fun lauf(f: Fuse20Folgen.Folge, dir: File): List<Zyklus> {
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
        runner = FuseCycleRunner(
            iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
            preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "ek-session", { markerPress },
            rejoinPolicy = app.aaps.fuse.core.signal.RejoinPolicy.enabled(),
        )
        val serialHash = LedgerFacts.serialHashOf(Fuse20Folgen.PUMPE_SERIAL, PumpType.GENERIC_AAPS.name)
        val ergebnis = ArrayList<Zyklus>()
        while (clock + 60_000L <= f.ts(f.bisMin)) {
            if (f.markerMin != null && clock + 60_000L == f.ts(f.markerMin)) druecken(clock + 60_000L)
            clock += 60_000L
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

    private companion object {

        /** Synthetisches maxIOB des Gestells. */
        const val MAX_IOB = 7.0
    }
}

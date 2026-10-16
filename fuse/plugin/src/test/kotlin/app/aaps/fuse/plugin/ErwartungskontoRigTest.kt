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
import app.aaps.fuse.core.controller.LivenessChannel
import app.aaps.fuse.core.controller.LowThreatGate
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
    /** E-034 v2: die Bestaetigung "Mahlzeit nach Tief" [ms]; 0 = keine. */
    private var bestaetigtNachTief = 0L

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

    /**
     * A (Toni): eine Mahlzeit laenger als die Kurve - der steile Anstieg kommt erst nach Minute 90, wenn die
     * Kontoanforderung per Konstruktion 0 ist (Kurve endet bei 120, Deckung ab 30 min).
     */
    private val fSpaet = Fuse20Folgen.Folge(
        "SPAET", 1_700_388_000_000L, -90, 240,
        listOf(
            -90 to 118.0, -10 to 118.0, 0 to 120.0, 30 to 135.0, 60 to 140.0, 90 to 175.0, 120 to 260.0, 180 to 230.0,
            240 to 160.0,
        ),
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

    /**
     * Synthetische Konfiguration: Standardwerte aller Schluessel, dazu die Ueberschreibungen dieser Probe. Seit der
     * Konsolidierung gilt das Konto mit gueltiger Kurve - "Konto aus" heisst hier: keine Kurve.
     */
    private fun konfiguration(
        kontoAn: Boolean,
        markerAutorisiert: Boolean = true,
    ) {
        whenever(preferences.get(anyOrNull<BooleanKey>())).thenReturn(false)
        FuseDoubleKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(k.defaultValue) }
        FuseIntKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(k.defaultValue) }
        FuseBooleanKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(k.defaultValue) }
        FuseStringKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(k.defaultValue) }
        whenever(preferences.get(FuseBooleanKey.MarkerAuthorisesRelease)).thenReturn(markerAutorisiert)
        whenever(preferences.get(FuseBooleanKey.LivenessChannelEnabled)).thenReturn(true)
        whenever(preferences.get(FuseStringKey.ExpectationCurve)).thenReturn(if (kontoAn) kurve else "")
        whenever(preferences.get(DoubleKey.ApsSmbMaxIob)).thenReturn(MAX_IOB)
        whenever(constraintsChecker.getMaxIOBAllowed()).thenAnswer { ConstraintObject(MAX_IOB, aapsLogger) }
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(-1L)
        FuseLongKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(0L) }
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenAnswer { markerAt }
        whenever(preferences.get(FuseLongKey.MealMarkerNoPrime)).thenAnswer { if (ohneVorschuss) 1L else 0L }
        whenever(preferences.get(FuseLongKey.MealMarkerExpectationOnly)).thenAnswer { if (nurErwartung) 1L else 0L }
        whenever(preferences.get(FuseLongKey.EarlySupplyLowConfirmTs)).thenAnswer { bestaetigtNachTief }
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

    private fun neuerRunner(arm: FuseCycleRunner.Pruefarm?, uebergabe: FuseCycleRunner.MahlzeitUebergabe? = null) = FuseCycleRunner(
        iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
        preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "ek-session", { markerPress },
        pruefarm = arm,
        mahlzeitVorNeubau = uebergabe,
        rejoinPolicy = app.aaps.fuse.core.signal.RejoinPolicy.enabled(),
        predict = { input ->
            predictReject?.let { app.aaps.fuse.core.predictor.PredictorOutcome.Rejected(it, "erzwungen") }
                ?: app.aaps.fuse.core.predictor.TrajectoryCore.predict(input)
        },
    )

    /**
     * Prozess-Neustart mitten in der Folge: Ledger von der Platte, neuer Runner - der Speicherzustand ist weg. Dazu
     * gehoert die Beobachtung des Drucks: sie lebt im Plugin nur im Speicher, ein Marker gilt nach dem Neustart als
     * vorgefunden.
     */
    private fun neustart(dir: File, f: Fuse20Folgen.Folge, arm: FuseCycleRunner.Pruefarm?) {
        ledger = FuseLedgerAdapter().also { it.loadOnce(File(dir, "ledger_${f.name}"), "ek-epoch", clock) }
        runner = neuerRunner(arm)
        markerPress = 0L
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

    /**
     * Ohne gueltige Kurve gibt es keinen Kontozustand. Konsolidierung 2.0 (Toni): im Mahlzeitkontext steht der
     * Rueckfall ausgewiesen im Block (NO_CURVE) - sonst gibt es keinen.
     */
    @Test
    fun `ohne Konto gibt es keinen Kontozustand und im Mahlzeitkontext nur den Rueckfall`(@TempDir dir: File) {
        konfiguration(kontoAn = false)
        val zyklen = lauf(Fuse20Folgen.F1, dir)
        assertTrue(zyklen.size > 300)
        assertTrue(zyklen.all { it.konto == null }) { "Konto aus: kein Kontozustand" }
        for (z in zyklen) {
            val x = z.o.erwartungskonto ?: continue
            assertEquals("NO_CURVE", x.rueckfall, "Minute ${z.minute}: Block ohne Rueckfall")
            assertFalse(x.gilt, "Minute ${z.minute}")
            assertNull(x.zyklus, "Minute ${z.minute}")
        }
        assertTrue(zyklen.any { it.o.erwartungskonto?.rueckfall == "NO_CURVE" }) { "Rueckfall nicht ausgewiesen" }
        assertTrue(zyklen.none { it.o.livenessDenial == "EXPECTATION_ACCOUNT" })
    }

    /**
     * KONSOLIDIERUNG (Toni: "Druck = Freigabe"): der Druck selbst oeffnet das Konto. Der Schalter
     * Marker-Autorisierung entscheidet nur noch ueber die Modell-Einwaende der Direktdosis.
     */
    @Test
    fun `das Konto oeffnet auch ohne Marker-Autorisierung`(@TempDir dir: File) {
        konfiguration(kontoAn = true, markerAutorisiert = false)
        val zyklen = lauf(fStabil, dir).filter { it.o.abortReason == null }
        assertTrue(zyklen.filter { it.minute < 0 }.none { it.o.erwartungskonto!!.gilt }) { "Konto vor dem Druck" }
        assertTrue(zyklen.any { it.minute >= 0 && it.o.erwartungskonto!!.gilt }) { "Konto ohne Autorisierung nicht eroeffnet" }
        assertTrue(zyklen.any { it.o.erwartungskonto!!.zyklus?.armiertInDiesemZyklus == true }) { "keine Buchung am Druck" }
    }

    /** Ohne gueltige Kurve gilt kein Konto - der alte Schalter ist entfernt (FuseRetiredKey) und wird nie gelesen. */
    @Test
    fun `ohne gueltige Kurve gilt kein Konto, gleich was der alte Schalter sagt`(@TempDir dir: File) {
        konfiguration(kontoAn = false)
        val zyklen = lauf(Fuse20Folgen.F1, dir)
        assertTrue(zyklen.all { it.o.erwartungskonto?.gilt != true && it.konto == null }) { "Konto ohne Kurve" }
    }

    // ---- RUECKFALL OHNE KONTO (Tonis Regel) --------------------------------------------
    //
    // "Wie ohne Marker" nur als begrenzter Rueckfall bei tatsaechlich fehlendem oder ungueltigem Konto. Nach einem
    // Neustart zuerst ein gueltig gespeichertes Konto einschliesslich Verbrauch und Sperren wiederherstellen. Keine
    // Neubuchung unbekannten Budgets. Die Mahlzeitenankuendigung sichtbar behalten und den Ausfall deutlich melden.

    /** Dieselbe Folge ohne Druck - das Gegenstueck der gepaarten Probe. */
    private fun ohneDruck(f: Fuse20Folgen.Folge) =
        Fuse20Folgen.Folge(f.name + "_OHNE", f.nullMs, f.vonMin, f.bisMin, f.stuetzen, markerMin = null)

    /**
     * (a) KONTO FEHLT IM MAHLZEITKONTEXT: die Mahlzeit ist angekuendigt und autorisiert, ein Konto gilt nicht (keine
     * gueltige Kurve). Gepaart mit derselben Folge ohne Druck trifft FUSE in JEDEM Zyklus dieselbe Dosierentscheidung.
     * Nicht still: der Rueckfall steht mit Grund im Block, Marker und Vollmacht bleiben im Trail sichtbar.
     */
    @Test
    fun `Rueckfall ohne Konto - dieselbe Dosierung wie ohne Marker, gemeldet, Ankuendigung sichtbar`(@TempDir dir: File) {
        konfiguration(kontoAn = false)
        val mit = lauf(fStabil, File(dir, "mit").also(File::mkdirs))
        konfiguration(kontoAn = false)
        val ohne = lauf(ohneDruck(fStabil), File(dir, "ohne").also(File::mkdirs))
        assertEquals(ohne.map { it.minute }, mit.map { it.minute })
        // Die eine Ausnahme (Toni): im Rueckfall bremst das Rebound-Fenster die Mahlzeit nicht - eigene Probe unten. Diese
        // Folge hat kein Tief, also kein Rebound-Fenster; die Paarung gilt deshalb fuer jeden Zyklus.
        assertTrue(mit.none { it.o.state?.reboundRestMin != null }) { "die Folge erreicht ein Rebound-Fenster" }
        for ((a, b) in mit.zip(ohne)) {
            val k = "Minute ${a.minute}"
            assertEquals(b.o.abortReason, a.o.abortReason, k)
            assertEquals(b.o.decision.smbU, a.o.decision.smbU, 0.0, k)
            assertEquals(b.o.decision.block, a.o.decision.block, k)
            assertEquals(b.o.decision.insulinReqU, a.o.decision.insulinReqU, k)
            assertEquals(b.o.tbr?.rateUPerH, a.o.tbr?.rateUPerH, k)
            assertEquals(b.o.tbr?.durationMin, a.o.tbr?.durationMin, k)
            assertEquals(b.geliefertU, a.geliefertU, 0.0, k)
            // Der Kanal ebenso: mit dem Profil des Laufs ohne Marker, im Rueckfall auch mit dessen Grund (danach
            // nennt der Grund die abgelaufene Vollmacht).
            assertEquals(b.o.livenessProfile, a.o.livenessProfile, k)
            if (a.o.erwartungskonto?.rueckfall != null) assertEquals(b.o.livenessProfileReason, a.o.livenessProfileReason, k)
        }
        // Nicht leer: der Lauf ohne Druck dosiert - die Paarung vergleicht echte Entscheidungen.
        assertTrue(ohne.sumOf { it.geliefertU } > 0.0) { "die Folge dosiert nicht" }
        val rueckfall = mit.filter { it.o.erwartungskonto?.rueckfall != null }
        assertTrue(rueckfall.isNotEmpty()) { "Vorbedingung: Mahlzeitkontext ohne Konto" }
        assertTrue(rueckfall.any { it.geliefertU > 0.0 }) { "im Rueckfall fliesst nichts - die Probe prueft keine Menge" }
        for (z in rueckfall) {
            val x = z.o.erwartungskonto!!
            assertEquals("NO_CURVE", x.rueckfall, "Minute ${z.minute}")
            assertFalse(x.gilt, "Minute ${z.minute}")
            // Die Ankuendigung bleibt sichtbar: Marker und Vollmacht stehen unveraendert im Trail.
            assertEquals(fStabil.ts(0), z.o.state?.markerArmedTs, "Minute ${z.minute}: Marker")
            assertEquals("MEAL", z.o.dosingContextProfile, "Minute ${z.minute}: Vollmacht")
            assertEquals(fStabil.ts(0), z.o.dosingContextAuthorizationId, "Minute ${z.minute}: Autorisierung")
            // Und wie ohne Marker: kein Marker-Sonderrecht.
            assertFalse(z.o.state?.markerBoost == true, "Minute ${z.minute}: Sonderrechte im Rueckfall")
        }
        // Ohne Druck gibt es keinen Rueckfall.
        assertTrue(ohne.all { it.o.erwartungskonto == null })
    }

    /**
     * (b) NEUSTART MIT GUELTIG GESPEICHERTEM KONTO: das Konto kommt samt Verbrauch, Portionen und Tief-Sperre zurueck -
     * kein Rueckfall, keine Neubuchung. Die Freigabe verlangt danach die Strecke neu (s. `nach einem Neustart braucht
     * eine freigegebene Episode die Strecke neu`).
     */
    @Test
    fun `Neustart mit gueltigem Konto - Konto samt Verbrauch und Sperren zurueck, kein Rueckfall`(@TempDir dir: File) {
        val neustartMin = 30
        for (f in listOf(fStabil, fTiefNachDruck)) {
            var vorher: ExpectationAccount.State? = null
            var nachher: ExpectationAccount.State? = null
            val zyklen = fruehLauf(f, File(dir, f.name).also(File::mkdirs), vorZyklus = { m ->
                if (m == neustartMin) {
                    vorher = ledger.episodes.expectation
                    neustart(File(dir, f.name), f, null)
                    nachher = ledger.episodes.expectation
                }
            }) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
            val k = vorher ?: throw AssertionError("${f.name}: Vorbedingung - vor dem Neustart ein Konto")
            assertEquals(k, nachher, "${f.name}: das gespeicherte Konto kommt unveraendert zurueck")
            if (f === fStabil) assertTrue(k.bookedU > 0.0 && k.initialDone) { "${f.name}: Vorbedingung - Verbrauch" }
            if (f === fTiefNachDruck) assertTrue(k.earlyLowLocked && k.earlyAfterLow) { "${f.name}: Vorbedingung - Sperre" }
            val danach = zyklen.filter { it.minute in neustartMin until 120 }
            assertTrue(danach.isNotEmpty())
            for (z in danach) {
                val x = z.o.erwartungskonto!!
                assertTrue(x.gilt, "${f.name} Minute ${z.minute}: Konto gilt nicht")
                assertNull(x.rueckfall, "${f.name} Minute ${z.minute}: Rueckfall")
                assertFalse(x.zyklus!!.armiertInDiesemZyklus, "${f.name} Minute ${z.minute}: Neubuchung")
                val n = z.konto!!
                assertEquals(k.authId, n.authId)
                assertEquals(k.a0U, n.a0U, 0.0, "${f.name} Minute ${z.minute}: Budget")
                assertTrue(n.bookedU >= k.bookedU - 1e-9) { "${f.name} Minute ${z.minute}: Verbrauch verloren" }
                assertTrue(n.portions.containsAll(k.portions.filter { it.ts > z.o.computeTs - 30 * 60_000L })) {
                    "${f.name} Minute ${z.minute}: Portionen verloren"
                }
                if (k.earlyLowLocked) assertTrue(n.earlyLowLocked) { "${f.name} Minute ${z.minute}: Sperre verloren" }
            }
        }
    }

    /**
     * (c) NEUSTART MIT UNBRAUCHBAREM GESPEICHERTEM KONTO: der Codec verwirft es, die Mahlzeit bleibt angekuendigt -
     * Rueckfall mit Grund, und es wird KEIN neues Budget gebucht (die Beobachtung des Drucks ist mit dem Prozess weg).
     */
    @Test
    fun `Neustart mit unbrauchbarem Konto - Rueckfall ausgewiesen, keine Neubuchung`(@TempDir dir: File) {
        val neustartMin = 30
        val zyklen = fruehLauf(fStabil, dir, vorZyklus = { m ->
            if (m == neustartMin) {
                // Ein gespeichertes Konto, das der Codec verwirft (negativer Verbrauch).
                val k = ledger.episodes.expectation ?: throw AssertionError("Vorbedingung: ein Konto")
                ledger.episodes.expectation = k.copy(bookedU = -1.0)
                assertTrue(ledger.persistVerified(File(dir, "ledger_${fStabil.name}")))
                neustart(dir, fStabil, null)
                assertNull(ledger.episodes.expectation, "der Codec verwirft das Konto")
                assertTrue(ledger.episodes.expectationDropped)
            }
        }) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        val danach = zyklen.filter { it.minute in neustartMin until 120 }
        assertTrue(danach.isNotEmpty())
        for (z in danach) {
            val x = z.o.erwartungskonto!!
            assertFalse(x.gilt, "Minute ${z.minute}: Konto gilt")
            assertEquals("DROPPED_ON_LOAD", x.rueckfall, "Minute ${z.minute}: Rueckfall nicht ausgewiesen")
            assertNull(x.zyklus, "Minute ${z.minute}: Kontozyklus")
            assertNull(z.konto, "Minute ${z.minute}: neues Konto gebucht")
            assertEquals(0.0, z.dErw(), 0.0, "Minute ${z.minute}")
            // Die Ankuendigung bleibt: die Vollmacht steht weiter im Trail.
            assertEquals(fStabil.ts(0), z.o.dosingContextAuthorizationId, "Minute ${z.minute}: Vollmacht")
        }
    }

    /**
     * TONI: eine LEDGER-REPARATUR mitten in der Mahlzeit bucht kein neues Konto. Die Reparatur legt eine leere Generation
     * an, und der Runner wird im selben Prozess neu gebaut - der Druck bleibt beobachtet. Mit der Uebergabe des alten
     * Runners bleibt die Vollmacht sichtbar, und die Mahlzeit laeuft ausgewiesen im Rueckfall (ACCOUNT_LOST). Gegenprobe
     * ohne Uebergabe: die Vollmacht ist weg - genau die Luecke, die die Uebergabe schliesst.
     */
    @Test
    fun `Reparatur mitten in der Mahlzeit - keine Neubuchung, Rueckfall mit Meldung, Vollmacht sichtbar`(@TempDir dir: File) {
        val reparaturMin = 30
        for (mitUebergabe in listOf(true, false)) {
            val d = File(dir, if (mitUebergabe) "mit" else "ohne").also(File::mkdirs)
            val zyklen = fruehLauf(fStabil, d, vorZyklus = { m ->
                if (m == reparaturMin) {
                    val u = runner.mahlzeitUebergabe()
                    assertNotNull(u) { "Vorbedingung: eine laufende Vollmacht" }
                    assertEquals(fStabil.ts(0), u!!.kontoGebuchtFuerAuth) { "Vorbedingung: das Konto war gebucht" }
                    // Die Reparatur: eine leere Generation (hier ihr Mahlzeitteil - Vollmacht, Konto, Autorisierung) und
                    // ein neuer Runner im selben Prozess.
                    ledger.episodes.apply {
                        markerPowerPinnedFor = 0L
                        markerPowerDeadlineTs = 0L
                        expectation = null
                        markerAuth = null
                    }
                    runner = neuerRunner(null, if (mitUebergabe) u else null)
                }
            }) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
            val danach = zyklen.filter { it.minute in reparaturMin until 120 }
            assertTrue(danach.isNotEmpty())
            for (z in danach) {
                val k = "${if (mitUebergabe) "mit" else "ohne"} Minute ${z.minute}"
                assertNull(z.konto, "$k: neues Konto gebucht")
                assertEquals(0.0, z.dErw(), 0.0, k)
                if (mitUebergabe) {
                    assertEquals("ACCOUNT_LOST", z.o.erwartungskonto?.rueckfall, "$k: Rueckfall nicht ausgewiesen")
                    assertEquals("MEAL", z.o.dosingContextProfile, "$k: Vollmacht")
                    assertEquals(fStabil.ts(0), z.o.dosingContextAuthorizationId, "$k: Autorisierung")
                } else {
                    assertEquals("CORRECTION", z.o.dosingContextProfile, "$k: ohne Uebergabe bliebe die Vollmacht")
                }
            }
        }
    }

    /**
     * Konsolidierung 2.0 (lebender v1-Weg): ist die fruehe Versorgung aus oder ungueltig eingestellt, bucht der Druck ein
     * v2-Konto mit Budget 0 und neutralen Pins - es fliesst nur der gemessene Anteil, das Konto ersetzt den Bedarf.
     */
    @Test
    fun `ohne gueltige fruehe Versorgung bucht der Druck ein v2-Konto mit Budget 0`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        val aus = lauf(fStabil, File(dir, "aus").also(File::mkdirs)).filter { it.o.abortReason == null }
        konfiguration(kontoAn = true)
        whenever(preferences.get(FuseBooleanKey.EarlySupplyEnabled)).thenReturn(true)
        whenever(preferences.get(FuseIntKey.EarlySupplyPeriodMin)).thenReturn(0)
        val ungueltig = lauf(fStabil, File(dir, "ungueltig").also(File::mkdirs)).filter { it.o.abortReason == null }
        for ((name, zyklen) in listOf("aus" to aus, "ungueltig" to ungueltig)) {
            val k = zyklen.mapNotNull { it.konto }.lastOrNull() ?: throw AssertionError("$name: kein Konto")
            // Neutrale Pins; der ausgeschaltete Hauptschalter entzieht sie zudem fuer die Mahlzeit (withdrawn).
            assertEquals(app.aaps.fuse.core.controller.EarlySupply.Pins.NEUTRAL, k.early?.copy(withdrawn = false), name)
            assertEquals(name == "aus", k.early?.withdrawn, name)
            assertEquals(0.0, k.a0U, 0.0, name)
            assertEquals(0.0, zyklen.sumOf { it.dErw() }, 0.0, name)
            assertTrue(zyklen.any { it.o.erwartungskonto!!.gilt && it.o.livenessDenial == "EXPECTATION_ACCOUNT" }) {
                "$name: das Konto ersetzt den Bedarf nicht"
            }
        }
    }

    @Test
    fun `mit Konto - Buchung am Druck, Kontobedarf im Fenster, Budget nie aufgefuellt`(@TempDir dir: File) {
        val f = fStabil
        // Konsolidierung: jeder Druck bucht ein v2-Konto (fruehe Versorgung); v1 und die Direktdosis sind entfallen.
        val zyklen = fruehLauf(f, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
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
        assertNotNull(konto0.early) { "kein v2-Konto" }
        assertTrue(konto0.a0U > 0.0 && konto0.a0U <= FRUEH_BUDGET_U + 1e-9) { "Budget ${konto0.a0U}" }

        // Im Fenster: Kontobedarf neben dem Normalbedarf (A), Onset-Kanal, H8 und Liveness-Kanal aus.
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
                // A: der Bedarf liegt nie unter dem Kontobedarf; wo der Kontoweg die Menge traegt, ist er genau er.
                val konto = ExpectationAccount.deliverableU(x.zyklus!!.eval)
                assertTrue(req >= konto - 1e-9) { "Minute ${z.minute}: Bedarf $req unter dem Kontobedarf $konto" }
                if (z.o.decision.block == app.aaps.fuse.core.controller.FuseController.Block.NONE &&
                    !z.o.decision.normalDemandInAccountWindow
                ) assertEquals(konto, req, 1e-9, "Minute ${z.minute}: Kontoweg ohne Kontobedarf")
            }
        }

        // Mechanismus: d_erw nur im Rahmen des Rests, nie Ueberschreitung,
        // Summe nie ueber A_0, gebucht nur steigend (Storno erklaert jede Senkung).
        var erwSumme = 0.0
        var vorher: ExpectationAccount.State? = null
        for (z in zyklen) {
            val x = z.o.erwartungskonto ?: continue
            x.buchung?.split?.let { b ->
                assertEquals(0.0, b.violationU, 1e-9, "Minute ${z.minute}: Ueberschreitung")
                assertTrue(b.dErwU <= x.zyklus!!.eval.restEffU + 1e-9) { "Minute ${z.minute}: d_erw ueber dem Rest" }
                erwSumme += b.dErwU
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

        // NICHT LEER: in dieser Mahlzeit fliesst Erwartung - ohne Direktdosis und ohne die v1-Ausnahme.
        val direktSumme = zyklen.sumOf { it.o.erwartungskonto?.buchung?.direktdosisU ?: 0.0 }
        assertTrue(erwSumme > 0.0) { "kein Erwartungsanteil geflossen" }
        assertEquals(0.0, direktSumme, 0.0, "Direktdosis gebucht")
        // Konsolidierung 2.0: die v1-Ausnahme gibt es nicht mehr - ihre Abwesenheitsprobe ist entfallen.
        // Jeder CANDIDATE-Zyklus traegt einen typisierten Grund (Mechanismuskriterium des Plans).
        for (z in zyklen) {
            val x = z.o.erwartungskonto ?: continue
            if (x.blockNormalpfad == app.aaps.fuse.core.controller.FuseController.Block.CANDIDATE)
                assertNotNull(x.grundNormalpfad, "Minute ${z.minute}: CANDIDATE ohne typisierten Grund")
        }
        val kontoEnde = zyklen.mapNotNull { it.konto }.last()
        assertEquals(erwSumme - kontoEnde.cancelledU, kontoEnde.bookedU, 1e-9, "Buecher gehen nicht auf")

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
    fun `Arm REF ist der Kandidat mit Schalter aus`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        val zyklen = lauf(fStabil, dir, FuseCycleRunner.Pruefarm.REF)
        assertTrue(zyklen.all { it.konto == null && it.o.erwartungskonto?.gilt != true })
        // Konsolidierung 2.0: im Mahlzeitkontext steht der Rueckfall ausgewiesen (Konto aus heisst keine Kurve).
        assertTrue(zyklen.all { it.o.erwartungskonto == null || it.o.erwartungskonto!!.rueckfall == "NO_CURVE" })
        assertTrue(zyklen.none { it.o.livenessDenial == "EXPECTATION_ACCOUNT" })
    }

    // Konsolidierung 2.0: `ohne Konto gibt es keine Direktdosis` ist entfallen - die Direktdosis-Quellen (Sofortanteil,
    // PRIME) gibt es im Code nicht mehr, die Pruefung waere leer.

    /** Fallen nach dem Marker mit Prime-Insulin an Bord: das spaete Abwaertsrisiko des Aufschubs greift. */
    private val fFall = Fuse20Folgen.Folge(
        "FALL", 1_700_388_000_000L, -90, 240,
        listOf(-90 to 150.0, -10 to 150.0, 0 to 152.0, 12 to 160.0, 45 to 98.0, 70 to 84.0, 120 to 95.0, 240 to 120.0),
        markerMin = 0,
    )

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

    /**
     * Tief kurz nach dem Druck, danach langer Anstieg ohne Wende: die
     * Marker-Sonderrechte enden nach markerBoostMaxMin, das Tief liegt dann
     * noch im Rebound-Fenster. Ohne Vollmacht greift die Bremse dort; im
     * Mahlzeitkontext nie (Tonis Regel) - weder im Konto noch im Rueckfall.
     */
    private val fReboundNachDruck = Fuse20Folgen.Folge(
        "REBOUND", 1_700_474_400_000L, -90, 180,
        listOf(-90 to 110.0, -10 to 96.0, 0 to 88.0, 6 to 62.0, 14 to 62.0, 25 to 92.0, 110 to 196.0, 180 to 170.0),
        markerMin = 0,
    )

    @Test
    fun `das Rebound-Fenster blockiert die Mahlzeit im Kontokontext nie`(@TempDir dir: File) {
        val nachSonderrechten = 50..90
        fun laufMit(f: Fuse20Folgen.Folge, arm: FuseCycleRunner.Pruefarm?, name: String): List<Zyklus> {
            konfiguration(kontoAn = true)
            whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(REBOUND_FENSTER_MIN)
            return lauf(f, File(dir, name).also(File::mkdirs), arm)
                .filter { it.o.abortReason == null && it.o.state != null }
        }
        // Ohne Vollmacht (dieselbe Folge ohne Druck) greift das Fenster in diesen Minuten - die Probe erreicht es.
        val ohneVollmacht = laufMit(ohneDruck(fReboundNachDruck), null, "ohne").filter { it.minute in nachSonderrechten }
        assertTrue(ohneVollmacht.isNotEmpty())
        assertTrue(ohneVollmacht.any { it.o.state!!.reboundWindow }) { "die Probe erreicht das Rebound-Fenster nicht" }
        // Schalter aus (Arm REF): die Sonderrechte enden, die Mahlzeit laeuft im Rueckfall. TONI (Konsolidierung 2.0):
        // auch dort bremst das Fenster nicht, solange die Vollmacht laeuft.
        val ref = laufMit(fReboundNachDruck, FuseCycleRunner.Pruefarm.REF, "REF").filter { it.minute in nachSonderrechten }
        assertTrue(ref.isNotEmpty())
        assertTrue(ref.none { it.o.state!!.markerBoost }) { "die Sonderrechte enden in der Probe nicht" }
        assertTrue(ref.all { it.o.erwartungskonto?.rueckfall == "NO_CURVE" }) { "der Rueckfall ist nicht ausgewiesen" }
        assertTrue(ref.none { it.o.state!!.reboundWindow }) { "das Rebound-Fenster bremst im Rueckfall" }
        assertTrue(ref.any { it.o.state!!.reboundSuppressedByMarker }) { "die Rebound-Bedingung lag im Rueckfall nicht an" }
        // Konto an: im ganzen gepinnten Fenster entwaffnet - die Bedingung lag an, gebremst wird nicht.
        // (Konsolidierung 2.0: wie konfiguriert - der v1-Arm EK ist entfallen.)
        val imFenster = laufMit(fReboundNachDruck, null, "konto").filter { it.o.erwartungskonto?.gilt == true }
        assertTrue(imFenster.any { it.minute in nachSonderrechten }) { "das Konto gilt dort nicht" }
        assertTrue(imFenster.none { it.o.state!!.reboundWindow }) { "das Rebound-Fenster bremst im Kontokontext" }
        assertTrue(
            imFenster.filter { it.minute in nachSonderrechten }.any { it.o.state!!.reboundSuppressedByMarker },
        ) { "die Rebound-Bedingung lag nicht an" }
        // Konsolidierung 2.0: der Zuordnungsarm EK-RBheute (Rebound wie heute im Kontokontext) ist entfallen.
    }

    /**
     * TONI (Konsolidierung 2.0): "Rebound blockiert keine Mahlzeit" gilt auch im Rueckfall ohne Konto. Das Konto geht
     * mitten in der Episode verloren; solange die Mahlzeit-Vollmacht laeuft, bremst das Fenster trotzdem nicht - es ist
     * entwaffnet wie im Kontofenster, der Rueckfall steht ausgewiesen daneben.
     */
    @Test
    fun `im Rueckfall bremst das Rebound-Fenster die Mahlzeit nicht, solange die Vollmacht laeuft`(@TempDir dir: File) {
        konfiguration(kontoAn = true)
        whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(REBOUND_FENSTER_MIN)
        val zyklen = lauf(fReboundNachDruck, dir, vorZyklus = { m ->
            if (m == 20) ledger.episodes.expectation = null
        }).filter { it.o.abortReason == null && it.o.state != null }
        val danach = zyklen.filter { it.minute in 50..90 && !it.o.state!!.markerBoost }
        assertTrue(danach.isNotEmpty())
        assertTrue(danach.all { it.o.erwartungskonto?.rueckfall == "ACCOUNT_LOST" }) { "der Rueckfall ist nicht ausgewiesen" }
        assertTrue(danach.any { it.o.state!!.reboundSuppressedByMarker }) { "die Probe erreicht das Rebound-Fenster nicht" }
        assertTrue(danach.none { it.o.state!!.reboundWindow }) { "das Rebound-Fenster bremst im Rueckfall" }
    }

    /**
     * Derselbe Unterschied als Menge: Rueckfall ohne Konto (keine Kurve) gegen dieselbe Folge ohne Druck. Ohne Vollmacht
     * haelt das Rebound-Totband den Bedarf zurueck; im Rueckfall dosiert FUSE in denselben Zyklen.
     */
    @Test
    fun `Rueckfall im Rebound-Fenster dosiert, derselbe Lauf ohne Vollmacht nicht`(@TempDir dir: File) {
        konfiguration(kontoAn = false)
        whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(REBOUND_FENSTER_MIN)
        val mit = lauf(fReboundNachDruck, File(dir, "mit").also(File::mkdirs))
        konfiguration(kontoAn = false)
        whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(REBOUND_FENSTER_MIN)
        val ohne = lauf(ohneDruck(fReboundNachDruck), File(dir, "ohne").also(File::mkdirs))
        val ohneJeMinute = ohne.associateBy { it.minute }
        // Die Zyklen, in denen ohne Vollmacht das Rebound-Totband haelt und im Rueckfall eine Menge hinausgeht.
        val gebremst = mit.filter { z ->
            val b = ohneJeMinute[z.minute] ?: return@filter false
            z.o.erwartungskonto?.rueckfall != null && z.geliefertU > 0.0 &&
                b.geliefertU == 0.0 && b.o.decision.bindingLimit == "reboundDeadband"
        }
        assertTrue(gebremst.isNotEmpty()) {
            "Vorbedingung: ohne Vollmacht haelt das Totband, im Rueckfall fliesst Insulin - " +
                mit.filter { it.o.erwartungskonto?.rueckfall != null }.joinToString { "${it.minute}:${it.geliefertU}" }
        }
        // Im Rueckfall nennt kein Zyklus das Rebound-Totband, ohne Vollmacht bremst das Fenster.
        assertTrue(mit.filter { it.o.erwartungskonto?.rueckfall != null }.none { it.o.decision.bindingLimit == "reboundDeadband" })
        assertTrue(ohne.any { it.o.state?.reboundWindow == true })
        // Gemessene Tiefs bleiben hart: im gemessenen Tief geht auch im Rueckfall nichts hinaus.
        assertTrue(mit.filter { it.o.step?.safetyReasons?.isNotEmpty() == true }.all { it.geliefertU == 0.0 })
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
     * Gemeinsamer Ablauf der Tief-Proben (v2, Toni): freigegeben vor dem Tief, Ruecknahme mit dem erwarteten Grund,
     * und ab da SPERRT das Tief den Erwartungsanteil der Mahlzeit - auch wenn die Strecke die Freigabe zurueckgibt,
     * auch ueber einen Neustart. Aufheben koennte nur eine ausdrueckliche Bestaetigung (hier keine).
     */
    private fun pruefeTief(dir: File, abbruch: IntRange?, neustartMin: Int?, grund: String) {
        nurErwartung = true
        val alle = try {
            markerAt = 0L
            markerPress = 0L
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
            lauf(fTiefNachDruck, dir, vorZyklus = { m ->
                whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(
                    if (abbruch != null && m in abbruch) UNGUELTIGE_REBOUND_DAUER else FuseIntKey.ReboundWindowMin.defaultValue,
                )
                if (m == neustartMin) neustart(dir, fTiefNachDruck, null)
            })
        } finally {
            nurErwartung = false
        }
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
        val ruecknahme = voll.first { it.o.computeTs >= ersterTiefTs && it.o.erwartungskonto!!.zyklus!!.eval.releaseRevoked }
        assertEquals(grund, ruecknahme.o.erwartungskonto!!.zyklus!!.ruecknahmeGrund)
        val danach = voll.filter { it.o.computeTs >= ruecknahme.o.computeTs }
        assertTrue(danach.all { it.konto?.earlyLowLocked == true && it.konto?.earlyAfterLow == true }) { "nicht gesperrt" }
        assertEquals(0.0, danach.sumOf { it.dErw() }, 0.0)
        // Gegenprobe: die Strecke gibt die Freigabe zurueck - nur die Sperre haelt den Erwartungsanteil.
        assertTrue(danach.any { it.o.erwartungskonto!!.zyklus!!.eval.released }) { "keine Wiederfreigabe - Probe zeigt nichts" }
    }

    @Test
    fun `ein gemessenes Tief nimmt die Freigabe zurueck und sperrt den Erwartungsanteil`(@TempDir dir: File) =
        pruefeTief(dir, abbruch = null, neustartMin = null, grund = "LOW")

    /** Codex-Befund P0: das Tief faellt nur in Abbruchzyklen vor dem Kontoschritt. */
    @Test
    fun `ein Tief in Abbruchzyklen sperrt ebenso`(@TempDir dir: File) =
        pruefeTief(dir, abbruch = 5..13, neustartMin = null, grund = "LOW_ON_ABORT")

    /** Codex-Befund P0: Tief in Abbruchzyklen, danach ein Neustart - die Sperre geht nicht verloren. */
    @Test
    fun `nach Tief im Abbruch und Neustart bleibt die Sperre`(@TempDir dir: File) =
        pruefeTief(dir, abbruch = 5..13, neustartMin = 16, grund = "LOW_ON_ABORT")

    /** Codex-Befund P0: auch ohne Tief verlangt eine nach dem Neustart noch freigegebene Episode die Strecke neu. */
    @Test
    fun `nach einem Neustart braucht eine freigegebene Episode die Strecke neu`(@TempDir dir: File) {
        val neustartMin = 30
        val voll = fruehLauf(fStabil, dir, vorZyklus = { m -> if (m == neustartMin) neustart(dir, fStabil, null) }) {
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
        }.filter { it.o.erwartungskonto?.zyklus != null }
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
            assertEquals(0.0, z.dErw(), 0.0, "Minute ${z.minute}: d_erw ohne Freigabe")
        }
        // Nicht leer: vor dem Neustart floss Erwartung - die Probe prueft eine echte Freigabe.
        assertTrue(voll.filter { it.minute < neustartMin }.sumOf { it.dErw() } > 0.0) { "vor dem Neustart kein Erwartungsanteil" }
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
        konfiguration(kontoAn = true)
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
        bestaetigtNachTief = 0L
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
        // Konsolidierung 2.0: die Proben auf v1-Ausnahme und Direktdosis-Quellen sind entfallen - es gibt beide nicht mehr.
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
        // Diagnose: die Pruefung mit Erwartung steht bei jeder Portion im Export (wer haette gesperrt?). Die Schwanzwerte
        // gibt es nur mit Schwanzpruefung - im Gestell ist sie aus, am Geraet an.
        assertTrue(weitereMit.all {
            val x = it.o.erwartungskonto!!
            val schwanz = it.o.decision.tail?.usable == true
            x.fruehUntenMitErwartungMgdl != null &&
                (!schwanz || (x.fruehSchwanzMitErwartungU != null && x.fruehSchwanzKappeU != null))
        }) {
            "Diagnose der Pruefung mit Erwartung fehlt: " + weitereMit.joinToString { z ->
                val x = z.o.erwartungskonto!!
                "${z.minute}:${x.fruehUntenMitErwartungMgdl}/${x.fruehSchwanzMitErwartungU}/${x.fruehSchwanzKappeU}"
            }
        }

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

    /**
     * Toni: die Skalierung "nach Tief" nur, wenn er BEIM DRUCK im Tief ist. Ein Tief davor - hier mitten im
     * Rebound-Fenster - aendert an der Mahlzeit nichts: stabiler Start, Skalierer stabil, Anfangsportion beim Druck.
     * (Frueher, Bank N2 A1 Befund 3, machte es den Start zu "nach Tief".)
     */
    @Test
    fun `ein Tief vor dem Druck macht den Start nicht zu nach Tief`(@TempDir dir: File) {
        val zyklen = fruehLauf(fTiefDavor, dir) {
            fruehKonfiguration(g1 = true, g2 = true, skalaTief = 0.0)
            whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(REBOUND_FENSTER_MIN)
        }
        val gebucht = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        val k = gebucht.o.erwartungskonto!!.zyklus!!
        assertTrue(gebucht.o.state?.reboundRestMin != null) { "Probe zeigt nichts: der Druck liegt nicht im Rebound-Fenster" }
        assertTrue(k.stabil) { "Probe zeigt nichts: die Strecke ist nicht erfuellt" }
        assertTrue(k.state.startStable) { "das Tief vor dem Druck haelt den Start auf" }
        assertEquals(1.0, k.state.phi0, 0.0)
        assertTrue(k.state.a0U > 0.0) { "das Tief vor dem Druck kuerzt das Budget" }
        assertTrue(zyklen.any { it.frueh()?.initial == true && it.dErw() > 0.0 }) { "keine Anfangsportion" }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
    }

    /** Das Tief laeuft am Druck noch (gemessen), der Zucker liegt aber schon ueber der Stabilitaetsgrenze. */
    private val fTiefAmDruck = Fuse20Folgen.Folge(
        "TIEF_AM_DRUCK", 1_701_684_000_000L, -90, 180,
        listOf(-90 to 110.0, -20 to 90.0, -9 to 66.0, -6 to 70.0, -2 to 80.0, 0 to 84.0, 10 to 100.0, 60 to 190.0, 180 to 130.0),
        markerMin = 0,
    )

    /** Toni: ist er beim Druck im Tief - hier ein gemessenes Tief knapp ueber der Grenze -, gilt der Skalierer nach Tief. */
    @Test
    fun `ein gemessenes Tief am Druck gilt als Start nach Tief, auch knapp ueber der Grenze`(@TempDir dir: File) {
        val skalaTief = 0.5
        val zyklen = fruehLauf(fTiefAmDruck, dir) {
            fruehKonfiguration(g1 = true, g2 = true, skalaTief = skalaTief)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityBgMgdl)).thenReturn(GRENZE_AM_TIEF_MGDL)
        }
        val gebucht = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        val k = gebucht.o.erwartungskonto!!.zyklus!!
        assertTrue((gebucht.o.signal?.q1 ?: 0.0) > GRENZE_AM_TIEF_MGDL) {
            "Probe zeigt nichts: der Zucker liegt am Druck (Minute ${gebucht.minute}, q1 ${gebucht.o.signal?.q1}) unter der Grenze"
        }
        assertTrue(gebucht.o.step?.safetyReasons?.isNotEmpty() == true) {
            "Probe zeigt nichts: am Druck (Minute ${gebucht.minute}, q1 ${gebucht.o.signal?.q1}) ist kein Tief gemessen"
        }
        assertFalse(k.state.startStable)
        assertEquals(skalaTief, k.state.phi0, 0.0)
        assertTrue(zyklen.none { it.frueh()?.initial == true && it.dErw() > 0.0 }) { "Anfangsportion trotz Tief am Druck" }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
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

    /** Bank N2, W3: Rescue-KH nach einem Tief sind keine bestaetigte Mahlzeit - das Tief sperrt die fruehe Versorgung. */
    @Test
    fun `ein gemessenes Tief nach der Freigabe sperrt die fruehe Versorgung der Mahlzeit`(@TempDir dir: File) {
        val zyklen = fruehLauf(fTiefNachDruck, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val tief = zyklen.firstOrNull { it.o.erwartungskonto?.zyklus?.ruecknahmeGrund == "LOW" }
        assertNotNull(tief) { "die Probe erreicht kein gemessenes Tief nach der Freigabe" }
        val danach = zyklen.filter { it.minute >= tief!!.minute && it.o.erwartungskonto?.zyklus != null }
        assertTrue(danach.all { it.konto?.earlyLowLocked == true }) { "nicht gesperrt" }
        assertEquals(0.0, danach.sumOf { it.dErw() }, 0.0)
        assertTrue(danach.filter { it.o.erwartungskonto!!.zyklus!!.eval.status != "EXPIRED" }
            .all { it.o.erwartungskonto!!.zyklus!!.eval.status == "LOCKED_BY_LOW" })
        // Gegenprobe: die Strecke gibt die Freigabe zurueck - ohne die Regel floessen wieder Portionen.
        assertTrue(danach.any { it.o.erwartungskonto!!.zyklus!!.eval.released }) { "keine Wiederfreigabe - Probe zeigt nichts" }
        // Schritt 2 gilt nach dem Tief nicht: keine laufende Mahlzeit, keine Fortsetzung - auch wenn r ueber der Rampe liegt.
        val spaeter = danach.filter { it.minute > tief!!.minute }
        assertTrue(spaeter.any { (it.o.signal?.rSigned ?: 0.0) >= FuseDoubleKey.RiseRampLowR.defaultValue }) {
            "Probe zeigt nichts: nach dem Tief kein r ueber der Rampe"
        }
        assertTrue(spaeter.none { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }) { "Schritt 2 nach einem Tief" }
        assertTrue(spaeter.all { (it.frueh()?.continuationUPerMin ?: 0.0) == 0.0 }) { "Fortsetzung nach einem Tief" }
    }

    /** Leichtes Fallen am Druck, weit ueber der Grenze: unter der Kanalkonstante, aber langsamer als die Fallgrenze. */
    private val fLeichtFallend = Fuse20Folgen.Folge(
        "LEICHTFALLEND", 1_700_992_800_000L, -90, 180,
        listOf(-90 to 165.0, 0 to 135.0, 20 to 128.0, 60 to 200.0, 180 to 130.0),
        markerMin = 0,
    )

    /** Deutliches Fallen am Druck, schneller als die Fallgrenze. */
    private val fDeutlichFallend = Fuse20Folgen.Folge(
        "DEUTLICHFALLEND", 1_701_079_200_000L, -90, 180,
        listOf(-90 to 220.0, -30 to 200.0, 0 to 164.0, 15 to 146.0, 60 to 200.0, 180 to 130.0),
        markerMin = 0,
    )

    /**
     * Toni: der Blutzucker ist praktisch nie flach - zu Beginn der Mahlzeit ist ein kleines negatives Delta kein Grund zu
     * warten. Die Anfangsportion pausiert deshalb nur bei Fallen ueber die (gepinnte) Fallgrenze. Danach, mit Insulin an
     * Bord, stoppt schon leichtes Fallen die weiteren Portionen (kein Stacking), solange die Mahlzeit nicht laeuft -
     * hier liegt die Rampen-Unterkante ueber r (laeuft sie, s. Schritt 2). Synthetische Fallgrenze.
     */
    @Test
    fun `leichtes Fallen haelt die Anfangsportion nicht auf, danach und bei deutlichem Fallen bleibt es streng`(@TempDir dir: File) {
        val fallGrenze = 0.8
        val skalaTief = 0.5
        fun v2(f: Fuse20Folgen.Folge, name: String) = fruehLauf(f, File(dir, name)) {
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true, skalaTief = skalaTief)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(fallGrenze)
            rampeUeberR()
        }
        fun fruehe(z: List<Zyklus>) = z.filter { it.minute in 0..5 && it.o.erwartungskonto?.zyklus != null }
        fun anfangMinute(z: List<Zyklus>) = z.firstOrNull { it.frueh()?.initial == true && it.dErw() > 0.0 }?.minute ?: Int.MAX_VALUE

        // Leicht fallend: stabiler Start, die Anfangsportion fliesst sofort, keine Pause wegen Fallens.
        val leicht = v2(fLeichtFallend, "leicht")
        pruefeMechanik(leicht, 0.5, 1.0, 2.0)
        val raten = fruehe(leicht).mapNotNull { it.o.signal?.ukfRatePerMin }
        assertTrue(raten.isNotEmpty() && raten.all { it < LivenessChannel.UKF_FLOOR_MGDL_PER_MIN && it > -fallGrenze }) {
            "Probe zeigt nichts: UKF-Steigung $raten nicht zwischen Kanalkonstante und Fallgrenze"
        }
        assertTrue(leicht.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }.o.erwartungskonto!!.zyklus!!.state.startStable)
        assertTrue(fruehe(leicht).filter { it.minute <= anfangMinute(leicht) }.none { "FALLING" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) {
            "Pause wegen leichten Fallens vor der Anfangsportion"
        }
        val anfang = leicht.first { it.frueh()?.initial == true && it.dErw() > 0.0 }
        assertTrue(anfang.minute <= 5) { "Anfangsportion wartet trotz leichten Fallens bis Minute ${anfang.minute}" }
        // Nach der Anfangsportion, mit Insulin an Bord: dasselbe leichte Fallen pausiert die weiteren Portionen.
        val danach = leicht.filter { it.minute in (anfang.minute + 1)..15 && it.o.erwartungskonto?.zyklus != null }
        assertTrue(danach.any { "FALLING" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) { "nach der Anfangsportion keine strenge Pause" }
        assertEquals(0.0, danach.filter { "FALLING" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }.sumOf { it.dErw() }, 0.0)

        // Deutlich fallend: kein stabiler Start, Pause wegen Fallens, keine Portion, solange es faellt.
        val deutlich = v2(fDeutlichFallend, "deutlich")
        pruefeMechanik(deutlich, 0.5, 1.0, 2.0)
        assertTrue(fruehe(deutlich).mapNotNull { it.o.signal?.ukfRatePerMin }.any { it < -fallGrenze }) { "Probe zeigt nichts: faellt nicht deutlich" }
        val startDeutlich = deutlich.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }.o.erwartungskonto!!.zyklus!!.state
        assertFalse(startDeutlich.startStable)
        // Toni: ein Fallen bei hohem Zucker ist kein Start nach Tief - stabiler Skalierer, nur die Freigabe wartet.
        assertEquals(1.0, startDeutlich.phi0, 0.0)
        assertFalse(startDeutlich.released)
        val fallend = deutlich.filter { it.o.erwartungskonto?.zyklus?.pausenGruende?.contains("FALLING") == true }
        assertTrue(fallend.isNotEmpty()) { "deutliches Fallen pausiert nicht" }
        assertEquals(0.0, fallend.sumOf { it.dErw() }, 0.0)

        // Ohne fruehe Versorgung (Konsolidierung 2.0: statt des Kontos v1 ein Konto mit Budget 0 und neutralen Pins):
        // dieselbe leichte Folge bringt keine Portion - die Pause nennt den ausgeschalteten Hauptschalter.
        val ohne = fruehLauf(fLeichtFallend, File(dir, "ohne")) {
            fruehKonfiguration()
            whenever(preferences.get(FuseBooleanKey.EarlySupplyEnabled)).thenReturn(false)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(fallGrenze)
        }
        // Neutrale Pins; der ausgeschaltete Hauptschalter entzieht sie zudem fuer die Mahlzeit (withdrawn).
        assertTrue(fruehe(ohne).all {
            it.konto?.early?.copy(withdrawn = false) == app.aaps.fuse.core.controller.EarlySupply.Pins.NEUTRAL
        }) { "ohne fruehe Versorgung kein Konto mit neutralen Pins" }
        assertTrue(fruehe(ohne).all { "EARLY_SUPPLY_OFF" in it.o.erwartungskonto!!.zyklus!!.pausenGruende })
        assertEquals(0.0, ohne.sumOf { it.dErw() }, 0.0)
    }

    /** Langsames Abdriften vor dem Druck bei normalem Zucker; ein Bolus davor sorgt fuer Bolus-IOB. */
    private val fTieftor = Fuse20Folgen.Folge(
        "TIEFTOR", 1_701_252_000_000L, -90, 180,
        listOf(-90 to 128.0, -45 to 118.0, 0 to 100.0, 8 to 97.0, 20 to 101.0, 45 to 150.0, 90 to 200.0, 180 to 140.0),
        markerMin = 0,
    )

    /**
     * Toni (Fruehstueck): das Tieftor meldete "fallend mit Bolus-Ueberdeckung" bei flachem Abdriften und normalem
     * Zucker - das setzte die Stabilitaetsstrecke zurueck, der Start galt als instabil, und die Anfangsportion kam erst
     * nach einer neuen vollen Strecke. Jetzt entscheidet fuer den Start die Fallgrenze: die Strecke bleibt, die
     * Anfangsportion fliesst am Druck. Nach ihr bremst derselbe Befund die weiteren Portionen wie bisher.
     * Synthetische Werte.
     */
    @Test
    fun `der Ueberdeckungsbefund des Tieftors haelt den Mahlzeitstart nicht auf, danach bremst er`(@TempDir dir: File) {
        val zyklen = fruehLauf(fTieftor, dir, vorZyklus = { m ->
            if (m == -60) boliSetzen(
                boluses + BS(
                    id = ++bolusId, timestamp = clock, amount = 1.5, type = BS.Type.NORMAL,
                    ids = IDs(pumpId = clock, pumpType = PumpType.GENERIC_AAPS, pumpSerial = Fuse20Folgen.PUMPE_SERIAL),
                ),
            )
        }) {
            fruehKonfiguration(g1 = true, g2 = true, anteil = 0.5)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityBgMgdl)).thenReturn(80.0)
            whenever(preferences.get(FuseIntKey.ExpectationStabilityMin)).thenReturn(5)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(1.0)
        }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val start = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        // Die Probe zeigt nur etwas, wenn am Druck genau dieser Befund vorliegt - leicht fallend, kein Tief.
        assertEquals(LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE, start.o.lowThreat?.verdict) {
            "Probe zeigt nichts: kein Ueberdeckungsbefund am Druck (${start.o.lowThreat})"
        }
        val rate = requireNotNull(start.o.signal?.ukfRatePerMin)
        assertTrue(rate < 0.0 && rate > -1.0) { "Probe zeigt nichts: Trend $rate nicht leicht fallend" }
        assertTrue(start.o.step?.safetyReasons?.isEmpty() == true) { "Probe zeigt nichts: gemessenes Tief am Druck" }
        // Der Start gilt als stabil, und die Anfangsportion fliesst am Druck.
        assertTrue(start.o.erwartungskonto!!.zyklus!!.state.startStable) { "Start trotz Strecke als instabil gewertet" }
        val anfang = zyklen.first { it.frueh()?.initial == true && it.dErw() > 0.0 }
        assertTrue(anfang.minute <= start.minute + 1) { "Anfangsportion erst in Minute ${anfang.minute}" }
        assertTrue(zyklen.filter { it.minute <= anfang.minute && it.o.erwartungskonto?.zyklus != null }
            .none { "LOW_THREAT" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) { "Pause wegen Tieftor vor der Anfangsportion" }
        // Danach, mit Insulin an Bord: derselbe Befund pausiert die weiteren Portionen.
        val danach = zyklen.filter {
            it.minute > anfang.minute && it.o.erwartungskonto?.zyklus != null &&
                it.o.lowThreat?.verdict == LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE
        }
        assertTrue(danach.isNotEmpty()) { "Probe zeigt nichts: nach der Anfangsportion kein Ueberdeckungsbefund" }
        assertTrue(danach.all { "LOW_THREAT" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) { "Tieftor bremst nach der Anfangsportion nicht" }
        assertEquals(0.0, danach.sumOf { it.dErw() }, 0.0)
    }

    /** Steiler Fall vor dem Druck mit Bolus-IOB (setzt den Abwaertsriegel), am Druck flach. */
    private val fAlterRiegel = Fuse20Folgen.Folge(
        "ALTER_RIEGEL", 1_701_338_400_000L, -90, 180,
        listOf(-90 to 160.0, -40 to 150.0, -15 to 105.0, -10 to 98.0, -5 to 96.0, 0 to 95.0, 10 to 96.0,
            20 to 104.0, 45 to 150.0, 90 to 200.0, 180 to 140.0),
        markerMin = 0,
    )

    /** Fallen durch den Druck mit Bolus-IOB nahe am Boden (aktuelles Abwaertsrisiko), langsamer als die Fallgrenze. */
    private val fAktuellesRisiko = Fuse20Folgen.Folge(
        "AKTUELLES_RISIKO", 1_701_424_800_000L, -90, 180,
        listOf(-90 to 160.0, -30 to 131.0, 0 to 95.0, 10 to 88.0, 20 to 92.0, 45 to 140.0, 90 to 190.0, 180 to 130.0),
        markerMin = 0,
    )

    /** Rampen-Unterkante ueber jedem r der Proben: die Mahlzeit gilt nie als laufend (Schritt 2 greift nicht). */
    private fun rampeUeberR() {
        whenever(preferences.get(FuseDoubleKey.RiseRampLowR)).thenReturn(FuseDoubleKey.RiseRampLowR.max)
        whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenReturn(FuseDoubleKey.RiseRampLowR.max + 1.0)
    }

    private fun bolusVorDemDruck(m: Int, menge: Double) {
        if (m == -60) boliSetzen(
            boluses + BS(
                id = ++bolusId, timestamp = clock, amount = menge, type = BS.Type.NORMAL,
                ids = IDs(pumpId = clock, pumpType = PumpType.GENERIC_AAPS, pumpSerial = Fuse20Folgen.PUMPE_SERIAL),
            ),
        )
    }

    /**
     * Toni ("auch das Druecken des Markers ist meine Entscheidung"; "die erste Dosis ist die wichtigste und die
     * muss frueh kommen"): ein ALTER Abwaertsriegel - die Gefahr ist vorbei, die Hysterese wartet auf +0,20 mg/dl/min -
     * haelt die Anfangsportion nicht mehr auf, auch nicht ueber die Strecke. Sie fliesst am Druck. Die weiteren
     * Portionen haelt derselbe Riegel wie bisher, solange die Mahlzeit nicht laeuft - hier liegt die Rampen-Unterkante
     * ueber r (laeuft sie, s. Schritt 2 unten). Synthetische Werte.
     */
    @Test
    fun `ein alter Abwaertsriegel haelt die Anfangsportion nicht auf, die weiteren Portionen ohne laufende Mahlzeit schon`(@TempDir dir: File) {
        val zyklen = fruehLauf(fAlterRiegel, dir, vorZyklus = { bolusVorDemDruck(it, 3.0) }) {
            fruehKonfiguration(g1 = true, g2 = true, anteil = 0.5)
            whenever(preferences.get(FuseIntKey.ExpectationStabilityMin)).thenReturn(5)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(1.0)
            rampeUeberR()
        }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val start = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        // Die Probe zeigt nur etwas, wenn am Druck ein ALTER Riegel steht und sonst nichts sperrt.
        assertTrue(start.o.descentLatchActive) { "Probe zeigt nichts: kein Riegel am Druck" }
        assertEquals("WAITING_RATE", start.o.descentLatchReason) { "Probe zeigt nichts: kein alter Riegel am Druck" }
        assertFalse(start.o.descentRiskActive) { "Probe zeigt nichts: aktuelles Risiko statt altem Riegel" }
        assertTrue(start.o.step?.safetyReasons?.isEmpty() == true) { "Probe zeigt nichts: gemessenes Tief am Druck" }
        // Freigabe am Druck, die Anfangsportion fliesst sofort.
        assertTrue(start.o.erwartungskonto!!.zyklus!!.state.startStable) { "Start trotz altem Riegel als instabil gewertet" }
        val anfang = zyklen.first { it.frueh()?.initial == true && it.dErw() > 0.0 }
        assertTrue(anfang.minute <= start.minute + 1) { "Anfangsportion erst in Minute ${anfang.minute}" }
        assertTrue((anfang.o.smbActuatedU ?: 0.0) > 0.0) { "Anfangsportion vom Endriegel genullt" }
        // Danach, solange der Riegel steht: keine weitere Portion.
        val danach = zyklen.filter { it.minute > anfang.minute && it.o.descentLatchActive && it.o.erwartungskonto?.zyklus != null }
        assertTrue(danach.isNotEmpty()) { "Probe zeigt nichts: nach der Anfangsportion kein Riegel mehr" }
        assertTrue(danach.none { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }) { "Mahlzeit gilt trotz Rampe ueber r als laufend" }
        assertTrue(danach.all { "LATCH" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) { "Riegel bremst nach der Anfangsportion nicht" }
        assertEquals(0.0, danach.sumOf { it.dErw() }, 0.0)
    }

    /**
     * Toni (ersetzt fuer die Anfangsportion die fruehere Regel "niemals umgehbar"): auch ein AKTUELLES Abwaertsrisiko haelt sie
     * nicht auf, solange der Zucker nicht schneller faellt als die eingestellte Fallgrenze und kein Tief gemessen ist.
     * Synthetische Werte.
     */
    @Test
    fun `ein aktuelles Abwaertsrisiko haelt die Anfangsportion unter der Fallgrenze nicht auf`(@TempDir dir: File) {
        val zyklen = fruehLauf(fAktuellesRisiko, dir, vorZyklus = { bolusVorDemDruck(it, 3.0) }) {
            fruehKonfiguration(g1 = true, g2 = true, anteil = 0.5)
            whenever(preferences.get(FuseIntKey.ExpectationStabilityMin)).thenReturn(5)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(2.0)
        }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val start = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        assertTrue(start.o.descentRiskActive) { "Probe zeigt nichts: kein aktuelles Abwaertsrisiko am Druck" }
        val rate = requireNotNull(start.o.signal?.ukfRatePerMin)
        assertTrue(rate < 0.0 && rate > -2.0) { "Probe zeigt nichts: Trend $rate nicht unter der Fallgrenze fallend" }
        assertTrue(start.o.step?.safetyReasons?.isEmpty() == true) { "Probe zeigt nichts: gemessenes Tief am Druck" }
        val anfang = zyklen.first { it.frueh()?.initial == true && it.dErw() > 0.0 }
        assertTrue(anfang.minute <= start.minute + 1) { "Anfangsportion erst in Minute ${anfang.minute}" }
        assertTrue((anfang.o.smbActuatedU ?: 0.0) > 0.0) { "Anfangsportion vom Endriegel genullt" }
        // Weitere Portionen haelt das Risiko wie bisher - auch wenn die Mahlzeit laeuft (Schritt 2, Stapelschutz).
        val danach = zyklen.filter { it.minute > anfang.minute && it.o.descentRiskActive && it.o.erwartungskonto?.zyklus != null }
        assertEquals(0.0, danach.sumOf { it.dErw() }, 0.0)
        val imLauf = danach.filter { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }
        assertTrue(imLauf.isNotEmpty()) { "Probe zeigt nichts: das Risiko faellt in keinen Zyklus mit laufender Mahlzeit" }
        assertTrue(imLauf.all { "DESCENT_RISK" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) { "aktuelles Risiko haelt nicht" }
    }

    /** Ein alter Riegel ueber lange Zeit: steiler Fall vor dem Druck, danach flach, waehrend das Insulin wirkt - r steigt. */
    private val fLaufRiegel = Fuse20Folgen.Folge(
        "LAUF_RIEGEL", 1_701_511_200_000L, -90, 180,
        listOf(-90 to 160.0, -40 to 150.0, -15 to 105.0, -10 to 98.0, -5 to 96.0, 0 to 95.0, 10 to 96.0,
            30 to 97.0, 50 to 99.0, 70 to 120.0, 100 to 190.0, 180 to 150.0),
        markerMin = 0,
    )

    /**
     * SCHRITT 2 (Toni): laeuft die Mahlzeit - r ueber der Rampen-Unterkante -, haelt der ALTE Abwaertsriegel (Gefahr
     * vorbei, die Hysterese laeuft) auch die weiteren Portionen nicht mehr auf, weder als Pause noch am Endriegel.
     * Gegenprobe: dieselbe Folge mit der Rampen-Unterkante ueber r - die Mahlzeit gilt nicht als laufend, der Riegel
     * haelt wie bisher. Synthetische Werte.
     */
    @Test
    fun `laeuft die Mahlzeit, haelt der alte Riegel auch die weiteren Portionen nicht auf - Gegenprobe ohne`(@TempDir dir: File) {
        fun lauf(name: String, ohneLauf: Boolean) = fruehLauf(fLaufRiegel, File(dir, name), vorZyklus = { bolusVorDemDruck(it, 3.0) }) {
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true, anteil = 0.5)
            whenever(preferences.get(FuseIntKey.ExpectationStabilityMin)).thenReturn(5)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(1.0)
            if (ohneLauf) rampeUeberR()
        }
        fun alterRiegel(z: Zyklus) = z.o.descentLatchReason == "WAITING_RATE" || z.o.descentLatchReason == "WAITING_RAW"
        val mit = lauf("mit", ohneLauf = false)
        pruefeMechanik(mit, 0.5, 1.0, 2.0)
        val weitere = mit.filter { it.frueh()?.initial == false && alterRiegel(it) && it.o.erwartungskonto?.zyklus != null }
        assertTrue(weitere.size >= 10) { "Probe zeigt nichts: kaum weitere Portionen unter altem Riegel (${weitere.size})" }
        assertTrue(weitere.all { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }) {
            "Mahlzeit gilt nicht als laufend: " + weitere.filterNot { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }
                .joinToString { "${it.minute}:r=${it.o.signal?.rSigned}" }
        }
        assertTrue(weitere.none { "LATCH" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) { "alter Riegel pausiert trotz laufender Mahlzeit" }
        val vorbei = weitere.filter { it.dErw() > 0.0 }
        assertTrue(vorbei.isNotEmpty()) { "keine weitere Portion am alten Riegel vorbei" }
        assertTrue(vorbei.all { (it.o.smbActuatedU ?: 0.0) > 0.0 && it.o.erwartungskonto!!.fruehAmRiegelVorbeiU > 0.0 }) {
            "weitere Portion vom Endriegel genullt: " + vorbei.joinToString { "${it.minute}:${it.o.smbActuatedU}" }
        }

        val ohne = lauf("ohne", ohneLauf = true)
        pruefeMechanik(ohne, 0.5, 1.0, 2.0)
        val gehalten = ohne.filter { it.frueh()?.initial == false && it.o.descentLatchActive && it.o.erwartungskonto?.zyklus != null }
        assertTrue(gehalten.isNotEmpty()) { "Probe zeigt nichts: ohne laufende Mahlzeit kein Riegel nach dem Anfang" }
        assertTrue(gehalten.none { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft })
        assertTrue(gehalten.all { "LATCH" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) { "Riegel haelt ohne laufende Mahlzeit nicht" }
        assertEquals(0.0, gehalten.sumOf { it.dErw() }, 0.0)
        assertTrue(gehalten.all { it.o.erwartungskonto!!.fruehAmRiegelVorbeiU == 0.0 })
    }

    /**
     * Toni: die Schwelle "Mahlzeit laeuft ab r" ist eine eigene Einstellung - sie entscheidet allein, und 0 folgt der
     * Rampen-Unterkante. So bleibt die Rampe (SMB-Anteil, Korrekturweg) unberuehrt, wenn die Schwelle verstellt wird.
     * Synthetische Werte.
     */
    @Test
    fun `die Schwelle Mahlzeit laeuft ab r entscheidet allein, 0 folgt der Rampe`(@TempDir dir: File) {
        fun lauf(name: String, einstellen: () -> Unit) =
            fruehLauf(fLaufRiegel, File(dir, name), vorZyklus = { bolusVorDemDruck(it, 3.0) }) {
                fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true, anteil = 0.5)
                whenever(preferences.get(FuseIntKey.ExpectationStabilityMin)).thenReturn(5)
                whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(1.0)
                einstellen()
            }
        fun weitere(z: List<Zyklus>) = z.filter { it.frueh()?.initial == false && it.o.erwartungskonto?.zyklus != null }
        fun laufend(z: List<Zyklus>) = weitere(z).count { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }

        // Eigene Schwelle ueber jedem r: die Mahlzeit laeuft nie - obwohl r ueber der (unveraenderten) Rampe liegt.
        val hoch = lauf("hoch") { whenever(preferences.get(FuseDoubleKey.MealRunningMinR)).thenReturn(FuseDoubleKey.MealRunningMinR.max) }
        assertTrue(weitere(hoch).any { (it.o.signal?.rSigned ?: 0.0) >= FuseDoubleKey.RiseRampLowR.defaultValue }) {
            "Probe zeigt nichts: r nie ueber der Rampe"
        }
        assertEquals(0, laufend(hoch)) { "Mahlzeit laeuft trotz eigener Schwelle ueber r" }
        assertTrue(weitere(hoch).all { it.o.erwartungskonto!!.mahlzeitLaeuftAbR == FuseDoubleKey.MealRunningMinR.max })

        // Rampe ueber r, eigene Schwelle darunter: die Mahlzeit laeuft - die Rampe entscheidet hier nicht.
        val unter = lauf("unter") {
            rampeUeberR()
            whenever(preferences.get(FuseDoubleKey.MealRunningMinR)).thenReturn(FuseDoubleKey.RiseRampLowR.defaultValue)
        }
        assertTrue(laufend(unter) > 0) { "Mahlzeit laeuft trotz eigener Schwelle unter r nicht" }

        // 0 = wie die Rampe: mit der Rampe ueber r laeuft sie nicht, die geltende Schwelle ist die Rampe.
        val nullWert = lauf("null") { rampeUeberR() }
        assertEquals(0, laufend(nullWert))
        assertTrue(weitere(nullWert).all { it.o.erwartungskonto!!.mahlzeitLaeuftAbR == FuseDoubleKey.RiseRampLowR.max })
    }

    /** Stetiger Anstieg, danach leichtes Fallen, waehrend die Mahlzeit noch laeuft (r hoch, das Insulin wirkt). */
    private val fLaufFallend = Fuse20Folgen.Folge(
        "LAUF_FALLEND", 1_701_597_600_000L, -90, 180,
        listOf(-90 to 150.0, 0 to 150.0, 10 to 160.0, 30 to 190.0, 50 to 200.0, 70 to 194.0, 90 to 188.0,
            120 to 180.0, 180 to 140.0),
        markerMin = 0,
    )

    /**
     * SCHRITT 2 (Toni): laeuft die Mahlzeit, pausiert leichtes Fallen die weiteren Portionen erst ab der eingestellten
     * Fallgrenze, nicht schon ab der Kanalkonstante. Gegenprobe ohne laufende Mahlzeit: dieselbe Folge pausiert wie
     * bisher. Synthetische Fallgrenze.
     */
    @Test
    fun `laeuft die Mahlzeit, pausiert leichtes Fallen die weiteren Portionen erst ab der Fallgrenze - Gegenprobe ohne`(@TempDir dir: File) {
        val fallGrenze = 1.0
        fun lauf(name: String, ohneLauf: Boolean) = fruehLauf(fLaufFallend, File(dir, name)) {
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true, anteil = 0.5)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(fallGrenze)
            if (ohneLauf) rampeUeberR()
        }
        fun leichtFallend(z: Zyklus) =
            z.o.signal?.ukfRatePerMin?.let { it < LivenessChannel.UKF_FLOOR_MGDL_PER_MIN && it > -fallGrenze } == true
        val mit = lauf("mit", ohneLauf = false)
        pruefeMechanik(mit, 0.5, 1.0, 2.0)
        val fallend = mit.filter { it.frueh()?.initial == false && leichtFallend(it) && it.o.erwartungskonto?.zyklus != null }
        assertTrue(fallend.size >= 10) { "Probe zeigt nichts: kaum leichtes Fallen nach dem Anfang (${fallend.size})" }
        assertTrue(fallend.all { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }) { "Mahlzeit gilt nicht als laufend" }
        assertTrue(fallend.none { "FALLING" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) {
            "leichtes Fallen pausiert trotz laufender Mahlzeit"
        }
        assertTrue(fallend.any { it.dErw() > 0.0 }) { "keine weitere Portion im leichten Fallen - Probe zeigt nichts" }

        val ohne = lauf("ohne", ohneLauf = true)
        pruefeMechanik(ohne, 0.5, 1.0, 2.0)
        val fallendOhne = ohne.filter { it.frueh()?.initial == false && leichtFallend(it) && it.o.erwartungskonto?.zyklus != null }
        assertTrue(fallendOhne.isNotEmpty()) { "Probe zeigt nichts: ohne laufende Mahlzeit kein leichtes Fallen" }
        assertTrue(fallendOhne.all { "FALLING" in it.o.erwartungskonto!!.zyklus!!.pausenGruende }) { "leichtes Fallen pausiert nicht" }
        assertEquals(0.0, fallendOhne.sumOf { it.dErw() }, 0.0)
    }

    /**
     * SCHRITT 2 (Toni): die Anforderung des Kontos endet nicht mehr bei Minute 90, solange die Mahlzeit laeuft - die
     * beobachtete Fortsetzung traegt sie; das Budget deckelt. Gegenprobe ohne laufende Mahlzeit: dort bleibt sie 0.
     */
    @Test
    fun `nach Minute 90 fordert das Konto weiter, solange die Mahlzeit laeuft - Gegenprobe ohne`(@TempDir dir: File) {
        fun lauf(name: String, ohneLauf: Boolean) = fruehLauf(fSpaet, File(dir, name)) {
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
            if (ohneLauf) rampeUeberR()
        }
        fun spaet(z: List<Zyklus>) = z.filter { it.minute > 90 && it.o.erwartungskonto?.zyklus?.eval?.expired == false }
        val mit = lauf("mit", ohneLauf = false)
        pruefeMechanik(mit, 0.5, 1.0, 2.0)
        val laeuft = spaet(mit).filter { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }
        assertTrue(laeuft.size >= 10) { "Probe zeigt nichts: nach Minute 90 kaum laufende Mahlzeit (${laeuft.size})" }
        assertTrue(laeuft.all { (it.frueh()?.continuationUPerMin ?: 0.0) > 0.0 }) { "Fortsetzung zaehlt nicht" }
        assertTrue(laeuft.any { (it.frueh()?.demandU ?: 0.0) > 0.0 }) { "nach Minute 90 keine Anforderung trotz laufender Mahlzeit" }
        // Geraetebefund: kein Einbruch, wenn der letzte Deckungspunkt die Kurve verlaesst (vorher 0 bis Minute 94).
        val gleichDanach = laeuft.filter { it.minute in 91..93 }
        assertTrue(gleichDanach.isNotEmpty()) { "Probe zeigt nichts: kurz nach Minute 90 laeuft die Mahlzeit nicht" }
        assertTrue(gleichDanach.any { (it.frueh()?.demandU ?: 0.0) > 0.0 }) {
            "Anforderung bricht nach Minute 90 ein: " + gleichDanach.joinToString { "${it.minute}:${it.frueh()?.demandU}" }
        }
        // Guard und Schwanz rechnen die beobachtete Fortsetzung an: das Konto liefert nach Minute 90 weiter.
        // Guard und Schwanz rechnen die beobachtete Fortsetzung an - jenseits des Kurvenendes, bestaetigt.
        assertTrue(laeuft.filter { (it.frueh()?.guardScale ?: 0.0) > 0.0 }.all { (it.o.erwartungskonto?.fruehFortsetzungMgdl ?: 0.0) > 0.0 }) {
            "Fortsetzung zaehlt in Guard und Schwanz nicht: " + laeuft.joinToString { "${it.minute}:${it.o.erwartungskonto?.fruehFortsetzungMgdl}" }
        }
        // Graph und Trail: der Schwanz mit Mahlzeit steht in diesen Zyklen bereit, wo es einen Schwanz gibt.
        assertTrue(laeuft.filter { it.o.decision.tail != null }.all { it.o.erwartungskonto?.fruehSchwanzMitMahlzeit != null }) {
            "Schwanz mit Mahlzeit fehlt"
        }

        val ohne = lauf("ohne", ohneLauf = true)
        pruefeMechanik(ohne, 0.5, 1.0, 2.0)
        val nicht = spaet(ohne).filter { !it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }
        assertTrue(nicht.isNotEmpty()) { "Probe zeigt nichts: ohne laufende Mahlzeit kein Zyklus nach Minute 90" }
        assertTrue(nicht.all { it.frueh()?.demandU == 0.0 && it.frueh()?.continuationUPerMin == 0.0 }) {
            "ohne laufende Mahlzeit Anforderung nach Minute 90: " + nicht.joinToString { "${it.minute}:${it.frueh()?.demandU}" }
        }
        assertTrue(nicht.all { (it.o.erwartungskonto?.fruehFortsetzungMgdl ?: 0.0) == 0.0 }) { "Fortsetzung ohne laufende Mahlzeit" }

        // Mit Budget fuer die zweite Haelfte (synthetischer Skalierer): die laufende Mahlzeit bekommt nach Minute 90 weiter
        // Portionen - Guard und Schwanz lassen sie durch.
        val gross = fruehLauf(fSpaet, File(dir, "gross")) {
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true, budgetU = app.aaps.fuse.core.controller.EarlySupply.MAX_BUDGET_U)
            whenever(preferences.get(FuseDoubleKey.EarlySupplyScaleStable)).thenReturn(app.aaps.fuse.core.controller.EarlySupply.MAX_SCALE)
        }
        pruefeMechanik(gross, 0.5, 1.0, 2.0)
        val grossSpaet = spaet(gross).filter { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }
        assertTrue(grossSpaet.isNotEmpty()) { "Probe zeigt nichts: mit grossem Budget laeuft nach Minute 90 keine Mahlzeit" }
        assertTrue((grossSpaet.first().o.erwartungskonto?.zyklus?.eval?.restEffU ?: 0.0) > 0.0) {
            "Probe zeigt nichts: auch das grosse Budget ist vor Minute 90 aufgebraucht"
        }
        assertTrue(grossSpaet.sumOf { it.dErw() } > 0.0) {
            "nach Minute 90 keine Portion trotz Budget: " + grossSpaet.take(12).joinToString(" | ") { z ->
                val e = z.o.erwartungskonto!!
                "${z.minute}: X=${z.frueh()?.demandU} rest=${e.zyklus?.eval?.restEffU} gS=${z.frueh()?.guardScale} " +
                    "schwanzE=${e.fruehSchwanzMitErwartungU} p=${e.zyklus?.pausenGruende} bind=${z.o.decision.bindingLimit}"
            }
        }
    }

    /** Zucker am Druck knapp unter der Stabilitaetsgrenze, ohne gemessenes Tief. */
    private val fUnterGrenze = Fuse20Folgen.Folge(
        "UNTERGRENZE", 1_701_165_600_000L, -90, 180,
        listOf(-90 to 96.0, -30 to 88.0, 0 to 86.0, 30 to 130.0, 90 to 190.0, 180 to 130.0),
        markerMin = 0,
    )

    /** Toni: "nach Tief" gilt nur unter der Stabilitaetsgrenze (oder nach einem Tief im Rebound-Fenster). */
    @Test
    fun `unter der Stabilitaetsgrenze gilt der Start als nach Tief, auch ohne gemessenes Tief`(@TempDir dir: File) {
        val zyklen = fruehLauf(fUnterGrenze, dir) { fruehKonfiguration(g1 = true, g2 = true, skalaTief = 0.5) }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val start = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        assertTrue(start.o.step?.safetyReasons?.isEmpty() == true) { "Probe zeigt nichts: gemessenes Tief am Druck" }
        val bg = requireNotNull(start.o.signal?.q1)
        assertTrue(bg < FuseDoubleKey.ExpectationStabilityBgMgdl.defaultValue) { "Probe zeigt nichts: $bg nicht unter der Grenze" }
        assertEquals(0.5, start.o.erwartungskonto!!.zyklus!!.state.phi0, 0.0)
    }

    /**
     * Toni (Gegenfaelle der Tief-Sperre): fallender Start, also ohne Anfangsportion; ein Tief, WAEHREND die Freigabe
     * noch auf die Strecke wartet; Rescue-Anstieg. Danach entweder die echte Mahlzeit ([fTiefDannMahlzeit]) oder nur
     * die Hypo-Behandlung ([fTiefNurHypo]).
     */
    private val fTiefDannMahlzeit = Fuse20Folgen.Folge(
        "TIEFDANNMAHLZEIT", 1_700_820_000_000L, -90, 240,
        listOf(
            -90 to 150.0, -20 to 115.0, 0 to 95.0, 10 to 62.0, 16 to 60.0, 24 to 95.0, 35 to 120.0,
            75 to 200.0, 120 to 210.0, 180 to 160.0, 240 to 140.0,
        ),
        markerMin = 0,
    )
    private val fTiefNurHypo = Fuse20Folgen.Folge(
        "TIEFNURHYPO", 1_700_906_400_000L, -90, 240,
        listOf(
            -90 to 150.0, -20 to 115.0, 0 to 95.0, 10 to 62.0, 16 to 60.0, 24 to 95.0, 35 to 120.0,
            60 to 135.0, 120 to 125.0, 240 to 115.0,
        ),
        markerMin = 0,
    )

    /** Gemeinsame Pflichten beider Gegenfaelle: Sperre vor der Freigabe, keine Anfangsportion. */
    private fun pruefeTiefVorFreigabe(zyklen: List<Zyklus>): Zyklus {
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val start = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }.o.erwartungskonto!!.zyklus!!
        assertFalse(start.state.startStable) { "Probe zeigt nichts: der Start ist stabil" }
        val sperre = zyklen.firstOrNull { it.konto?.earlyLowLocked == true }
            ?: throw AssertionError("das Tief vor der Freigabe sperrt nicht")
        assertFalse(zyklen.filter { it.minute < sperre.minute }.any { it.o.erwartungskonto?.zyklus?.eval?.released == true }) {
            "die Freigabe kam vor dem Tief - die Probe prueft den Fall nicht"
        }
        assertTrue(zyklen.none { it.frueh()?.initial == true && it.dErw() > 0.0 }) { "Anfangsportion trotz Tief" }
        return sperre
    }

    /** Gegenfall 1: Tief, dann echte Mahlzeit, ausdruecklich bestaetigt - sie bleibt versorgbar. */
    @Test
    fun `Gegenfall Tief mit anschliessender echter Mahlzeit bleibt nach Bestaetigung versorgbar`(@TempDir dir: File) {
        val bestaetigungMin = 18
        val zyklen = fruehLauf(fTiefDannMahlzeit, File(dir, "mit"), vorZyklus = { m ->
            if (m == bestaetigungMin) bestaetigtNachTief = fTiefDannMahlzeit.ts(m)
        }) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        val sperre = pruefeTiefVorFreigabe(zyklen)
        assertTrue(sperre.minute < bestaetigungMin) { "bestaetigt vor dem Sperrbeginn - die Probe prueft den Fall nicht" }
        val frei = zyklen.firstOrNull { it.minute > sperre.minute && it.konto?.earlyLowLocked == false }
            ?: throw AssertionError("die Bestaetigung hebt die Sperre nie auf")
        // Waehrend des Tiefs und bis zur Wiederfreigabe kein Erwartungsanteil.
        assertEquals(0.0, zyklen.filter { it.minute < frei.minute }.sumOf { it.dErw() }, 0.0)
        assertTrue(zyklen.filter { it.o.step?.safetyReasons?.isNotEmpty() == true }.none { it.dErw() > 0.0 }) { "Portion im Tief" }
        assertTrue(frei.o.step?.safetyReasons?.isEmpty() == true) { "frei, obwohl das Tief noch misst" }
        // Versorgbar: nach der Bestaetigung steht der Rest des gebuchten Budgets wieder zur Verfuegung, und die Mahlzeit
        // wird versorgt - aus dem Konto oder, wo er mehr traegt, aus dem Normalbedarf (A).
        val konto = zyklen.mapNotNull { it.konto }.last()
        val nachFrei = zyklen.filter { it.minute >= frei.minute }
        assertTrue(nachFrei.any { (it.o.erwartungskonto?.zyklus?.eval?.restEffU ?: 0.0) > 0.0 }) {
            "der Rest steht nach der Bestaetigung nicht zur Verfuegung"
        }
        val nachher = nachFrei.sumOf { it.geliefertU }
        assertTrue(nachher >= 1.0) { "nach der Bestaetigung nur $nachher U" }
        assertTrue(nachFrei.sumOf { it.dErw() } <= konto.a0U + 1e-9)
        assertTrue(konto.earlyAfterLow)
        // Option A (Toni): nach der Bestaetigung rechnet der Guard die Mahlzeit wieder an - ab +20, soweit sie sich zeigt.
        assertTrue(nachFrei.any { (it.frueh()?.guardScale ?: 0.0) > 0.0 }) { "nach der Bestaetigung keine Anrechnung im Guard" }
        assertTrue(zyklen.filter { it.konto?.earlyLowLocked == true }.all { (it.frueh()?.guardScale ?: 0.0) == 0.0 }) {
            "Anrechnung im Guard, solange die Sperre gilt"
        }
        // Gegenprobe: dieselbe Folge OHNE Bestaetigung liefert keinen Erwartungsanteil, der Rest bleibt gesperrt.
        val ohne = fruehLauf(fTiefDannMahlzeit, File(dir, "ohne")) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        assertEquals(0.0, ohne.sumOf { it.dErw() }, 0.0)
        assertTrue(ohne.none { (it.o.erwartungskonto?.zyklus?.eval?.restEffU ?: 0.0) > 0.0 }) {
            "ohne Bestaetigung steht der Rest zur Verfuegung"
        }
        assertTrue(ohne.any { it.o.erwartungskonto?.zyklus?.eval?.released == true }) { "Probe zeigt nichts: keine Strecke" }
        // Ohne Bestaetigung rechnet der Guard die Mahlzeit nach dem Tief nie an.
        assertTrue(ohne.filter { it.konto?.earlyAfterLow == true }.all { (it.frueh()?.guardScale ?: 0.0) == 0.0 }) {
            "ohne Bestaetigung Anrechnung im Guard"
        }
    }

    /** Gegenfall 2: Tief, nur Hypo-Behandlung, keine Bestaetigung - keine erwartungsbasierte Abgabe. */
    @Test
    fun `Gegenfall Tief mit ausschliesslich Hypo-Behandlung loest keine erwartungsbasierte Abgabe aus`(@TempDir dir: File) {
        val zyklen = fruehLauf(fTiefNurHypo, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        val sperre = pruefeTiefVorFreigabe(zyklen)
        assertEquals(0.0, zyklen.sumOf { it.dErw() }, 0.0)
        val danach = zyklen.filter { it.minute >= sperre.minute && it.o.erwartungskonto?.zyklus != null }
        assertTrue(danach.all { it.konto?.earlyLowLocked == true }) { "die Sperre faellt ohne Bestaetigung" }
        assertTrue(danach.any { it.o.erwartungskonto!!.zyklus!!.eval.released }) {
            "Probe zeigt nichts: der Rescue-Anstieg erfuellt die Strecke nie"
        }
        // Derselbe Fall, das Tief nur in Abbruchzyklen (wie der Safety-Halt der Bank): der Vermerk sperrt auch vor der
        // Freigabe - bisher hinterliess er dort keine Spur.
        val abbruch = 5..40
        val imAbbruch = fruehLauf(fTiefNurHypo, File(dir, "abbruch"), vorZyklus = { m ->
            whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(
                if (m in abbruch) UNGUELTIGE_REBOUND_DAUER else FuseIntKey.ReboundWindowMin.defaultValue,
            )
        }) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        assertTrue(imAbbruch.none { it.minute in abbruch }) { "ein Zyklus im Abbruchbereich lief bis zum Konto" }
        val erster = imAbbruch.first { it.minute > abbruch.last && it.o.erwartungskonto?.zyklus != null }
        assertEquals("LOW_ON_ABORT", erster.o.erwartungskonto!!.zyklus!!.tiefGrund)
        assertTrue(erster.konto?.earlyLowLocked == true) { "das Tief im Abbruch sperrt nicht" }
        assertEquals(0.0, imAbbruch.sumOf { it.dErw() }, 0.0)
        assertTrue(imAbbruch.any { it.o.erwartungskonto?.zyklus?.eval?.released == true }) { "Probe zeigt nichts: keine Strecke" }
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

    /**
     * A (Toni): Anfangsgabe, Bedarfsrechnung und Pruefung muessen zusammen einen Versorgungsweg ergeben, auch nach Ende
     * des Vorgriffs. Steigt der Zucker erst nach dem Deckungsfenster der Kurve (Minute 90), traegt im Kontofenster der
     * Normalbedarf - gebucht als normal, ohne Budget. (Seit Schritt 2 fordert das Konto dort weiter, solange die Mahlzeit
     * laeuft; hier ist sein Budget dann verbraucht.)
     */
    @Test
    fun `nach dem Vorgriff traegt im Kontofenster der Normalbedarf, ohne Budget zu verbrauchen`(@TempDir dir: File) {
        val zyklen = fruehLauf(fSpaet, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val normal = zyklen.filter {
            it.o.erwartungskonto?.gilt == true && it.o.decision.normalDemandInAccountWindow &&
                it.o.decision.grant == null && it.geliefertU > 0.0
        }
        assertTrue(normal.isNotEmpty()) {
            "kein Normalbedarf im Kontofenster: " + zyklen.filter { it.minute in 85..125 }.joinToString { z ->
                "${z.minute}:${z.o.decision.block}/${z.o.decision.bindingLimit}/${z.geliefertU}"
            }
        }
        for (z in normal) {
            val split = z.o.erwartungskonto!!.buchung!!.split!!
            assertEquals(z.geliefertU, split.dNormU, 1e-9) { "Minute ${z.minute}: Normalmenge nicht als normal gebucht" }
            assertEquals(0.0, split.dErwU, 1e-9) { "Minute ${z.minute}: Normalmenge verbraucht Budget" }
            assertEquals(0.0, split.violationU, 1e-9)
        }
        // Mindestens eine davon nach dem Deckungsfenster der Kurve - genau die Luecke, die A schliesst.
        assertTrue(normal.any { it.minute > 90 }) { "keine Normalmenge nach dem Ende der Kurve" }
    }

    /** Budget und Anfangsanteil gelten ab dem Druck fest - eine Aenderung der Einstellung wirkt erst am naechsten Marker. */
    @Test
    fun `eine Einstellungsaenderung im Lauf aendert Budget und Anteil der laufenden Mahlzeit nicht`(@TempDir dir: File) {
        val zyklen = fruehLauf(fStabil, dir, vorZyklus = { m ->
            if (m == 2) {
                whenever(preferences.get(FuseDoubleKey.EarlySupplyInitialShare)).thenReturn(1.0)
                whenever(preferences.get(FuseDoubleKey.EarlySupplyBudgetMaxU)).thenReturn(8.0)
            }
        }) { fruehKonfiguration(g1 = true, g2 = true, anteil = 0.5, budgetU = 3.0) }
        val k0 = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }.konto!!
        // Die Kurve verlangt mehr als 3 U: das Budget sitzt an B_max, also zeigte eine Neuberechnung mit 8 U sofort.
        assertEquals(3.0, k0.a0U, 1e-9) { "Budget nicht gedeckelt - die Probe zeigt fuer B_max nichts" }
        val danach = zyklen.filter { it.minute >= 2 }.mapNotNull { it.konto }
        assertTrue(danach.isNotEmpty())
        for (k in danach) {
            assertEquals(k0.a0U, k.a0U, 0.0)
            assertEquals(0.5, k.early!!.initialShare, 0.0)
        }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
    }

    /**
     * Konsolidierung: jeder Druck bucht ein v2-Konto. Ein Marker ohne Wahl laeuft wie "Nur Erwartung", "Nur Marker"
     * bucht Budget 0 - dann fliesst nur der gemessene Anteil.
     */
    @Test
    fun `jeder Druck bucht ein v2-Konto, Nur Marker mit Budget 0`(@TempDir dir: File) {
        fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
        val ohneWahl = lauf(fStabil, File(dir, "ohneWahl")).filter { it.o.abortReason == null }
        val k1 = ohneWahl.mapNotNull { it.konto }.last()
        assertNotNull(k1.early) { "ohne Wahl kein v2-Konto" }
        assertTrue(k1.a0U > 0.0) { "ohne Wahl kein Budget" }
        ohneVorschuss = true
        try {
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
            val nurMarker = lauf(fStabil, File(dir, "nurMarker")).filter { it.o.abortReason == null }
            val k2 = nurMarker.mapNotNull { it.konto }.last()
            assertNotNull(k2.early) { "Nur Marker ohne v2-Konto" }
            assertEquals(0.0, k2.a0U, 0.0)
            assertEquals(0.0, nurMarker.sumOf { it.dErw() }, 0.0)
        } finally {
            ohneVorschuss = false
        }
    }

    private companion object {

        /** Synthetisches maxIOB des Gestells. */
        const val MAX_IOB = 7.0

        /** Synthetische Rebound-Dauer der Probe [min]: das Tief reicht ueber das Ende der Sonderrechte. */
        const val REBOUND_FENSTER_MIN = 90
        /** Synthetische Stabilitaetsgrenze der Probe TIEF_AM_DRUCK: die Tief-Schwelle des Codes. */
        const val GRENZE_AM_TIEF_MGDL = app.aaps.fuse.core.controller.FuseController.REBOUND_LOW_MGDL

        /** Unter der Kerngrenze: die Konfiguration scheitert, der Zyklus bricht NACH dem Observer-Schritt ab. */
        const val UNGUELTIGE_REBOUND_DAUER = 10

        /** Synthetische Obergrenze des v2-Budgets [U]. */
        const val FRUEH_BUDGET_U = 5.0

        /** Synthetischer Zeitraum der v2-Proben [min]. */
        const val ZEITRAUM_MIN = 10
    }
}

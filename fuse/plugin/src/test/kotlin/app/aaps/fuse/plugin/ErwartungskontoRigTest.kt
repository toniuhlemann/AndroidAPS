package app.aaps.fuse.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.fuse.core.controller.AuthorizedLift
import app.aaps.fuse.core.controller.EarlySupply
import app.aaps.fuse.core.controller.ExpectationAccount
import app.aaps.fuse.core.controller.LivenessChannel
import app.aaps.fuse.core.controller.LowThreatGate
import app.aaps.fuse.core.controller.InterventionStamp
import app.aaps.fuse.core.controller.MarkerReauthorization
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.fuse.plugin.ledger.LedgerFacts
import app.aaps.fuse.plugin.ledger.LedgerPublicationGate
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.runBlocking
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

    /** v4: die Insulinkurve des Pruefstands - die des Fork-Rigs (Lyumjev, DIA des Testprofils). */
    private val insulin: ICfg = rigICfg()

    /** v4: das Profil traegt die Kurve (FUSE-Vertrag 3); getProfile liefert ein EffectiveProfile. */
    private lateinit var rigProfil: EffectiveProfile
    private lateinit var ledger: FuseLedgerAdapter
    private lateinit var runner: FuseCycleRunner

    private var folge: Fuse20Folgen.Folge = Fuse20Folgen.F1
    private var clock = 0L
    private var markerPress = 0L
    private var markerAt = 0L
    /** Der globale SMB-Schalter von AAPS, wie ihn das Plugin dem Runner gibt; umlegbar je Minute ueber `vorZyklus`. */
    private var smbSchalterAn = true
    private var ohneVorschuss = false
    private var nurErwartung = false
    private var snack = false

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
        val dia = insulin.dia
        var iob = 0.0
        var akt = 0.0
        for (b in boluses) if (b.isValid && b.timestamp < key && b.timestamp >= key - (dia * 3_600_000.0).toLong()) {
            val r = insulin.iobCalcForTreatment(b, key)
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
    fun setup() = runBlocking<Unit> {
        rigProfil = ProfileSealed.EPS(effectiveProfileSwitch.copy(iCfg = insulin), activePlugin)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)
        testPumpPlugin.pumpDescription.bolusStep = 0.05

        whenever(dateUtil.now()).thenAnswer { clock }
        whenever(profileFunction.getProfile()).thenReturn(rigProfil)
        whenever(profileFunction.getProfile(any())).thenReturn(rigProfil)
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
    private suspend fun konfiguration(
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
        whenever(preferences.get(LongNonKey.FslCalibrationStart)).thenReturn(-1L)
        FuseLongKey.entries.forEach { k -> whenever(preferences.get(k)).thenReturn(0L) }
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenAnswer { markerAt }
        whenever(preferences.get(FuseLongKey.MealMarkerNoPrime)).thenAnswer { if (ohneVorschuss) 1L else 0L }
        whenever(preferences.get(FuseLongKey.MealMarkerExpectationOnly)).thenAnswer { if (nurErwartung) 1L else 0L }
        whenever(preferences.get(FuseLongKey.MealMarkerSnack)).thenAnswer { if (snack) 1L else 0L }
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

    private suspend fun lauf(
        f: Fuse20Folgen.Folge,
        dir: File,
        arm: FuseCycleRunner.Pruefarm? = null,
        /** Vor jedem Zyklus, mit seiner Minute - fuer Eingriffe mitten in der Folge. */
        vorZyklus: suspend (Int) -> Unit = {},
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
            val o = runner.run(false, testPumpe(), smbSchalterAn = smbSchalterAn)
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
                        iCfg = rigICfg(),
                    ),
                )
            }
            o.tbr?.takeIf { o.gate.allowed }?.let { req ->
                pumpe.anwenden(o.computeTs, req.rateUPerH, req.durationMin, testPumpe().baseBasalRateUPerH, rigProfil.getBasal(o.computeTs))
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
    fun `ohne Konto gibt es keinen Kontozustand und im Mahlzeitkontext nur den Rueckfall`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `das Konto oeffnet auch ohne Marker-Autorisierung`(@TempDir dir: File) = runBlocking<Unit> {
        konfiguration(kontoAn = true, markerAutorisiert = false)
        val zyklen = lauf(fStabil, dir).filter { it.o.abortReason == null }
        assertTrue(zyklen.filter { it.minute < 0 }.none { it.o.erwartungskonto!!.gilt }) { "Konto vor dem Druck" }
        assertTrue(zyklen.any { it.minute >= 0 && it.o.erwartungskonto!!.gilt }) { "Konto ohne Autorisierung nicht eroeffnet" }
        assertTrue(zyklen.any { it.o.erwartungskonto!!.zyklus?.armiertInDiesemZyklus == true }) { "keine Buchung am Druck" }
    }

    /** Ohne gueltige Kurve gilt kein Konto - der alte Schalter ist entfernt (FuseRetiredKey) und wird nie gelesen. */
    @Test
    fun `ohne gueltige Kurve gilt kein Konto, gleich was der alte Schalter sagt`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `Rueckfall ohne Konto - dieselbe Dosierung wie ohne Marker, gemeldet, Ankuendigung sichtbar`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `Neustart mit gueltigem Konto - Konto samt Verbrauch und Sperren zurueck, kein Rueckfall`(@TempDir dir: File) = runBlocking<Unit> {
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
            if (f === fTiefNachDruck) assertTrue(k.earlyAfterLow && k.earlyLowLockSinceTs > 0L) { "${f.name}: Vorbedingung - Tief vermerkt" }
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
                assertTrue(n.earlyAfterLow || !k.earlyAfterLow) { "${f.name} Minute ${z.minute}: Tief-Vermerk verloren" }
                // Im ersten Zyklus danach nimmt der Neustart die Freigabe - eine Sperre kann dort nicht fallen.
                if (k.earlyLowLocked && z === danach.first()) assertTrue(n.earlyLowLocked) { "${f.name}: Sperre beim Neustart verloren" }
            }
        }
    }

    /**
     * (c) NEUSTART MIT UNBRAUCHBAREM GESPEICHERTEM KONTO: der Codec verwirft es, die Mahlzeit bleibt angekuendigt -
     * Rueckfall mit Grund, und es wird KEIN neues Budget gebucht (die Beobachtung des Drucks ist mit dem Prozess weg).
     */
    @Test
    fun `Neustart mit unbrauchbarem Konto - Rueckfall ausgewiesen, keine Neubuchung`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `Reparatur mitten in der Mahlzeit - keine Neubuchung, Rueckfall mit Meldung, Vollmacht sichtbar`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `ohne gueltige fruehe Versorgung bucht der Druck ein v2-Konto mit Budget 0`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `mit Konto - Buchung am Druck, Kontobedarf im Fenster, Budget nie aufgefuellt`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `ohne Vorschuss gibt es kein Budget und keinen Erwartungsanteil`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `Arm REF ist der Kandidat mit Schalter aus`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `ein verlorenes Konto wird nicht neu eroeffnet und der Rueckfall ist ausgewiesen`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `das Rebound-Fenster blockiert die Mahlzeit im Kontokontext nie`(@TempDir dir: File) = runBlocking<Unit> {
        val nachSonderrechten = 50..90
        suspend fun laufMit(f: Fuse20Folgen.Folge, arm: FuseCycleRunner.Pruefarm?, name: String): List<Zyklus> {
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
    fun `im Rueckfall bremst das Rebound-Fenster die Mahlzeit nicht, solange die Vollmacht laeuft`(@TempDir dir: File) = runBlocking<Unit> {
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
     * Ein zuegiger Anstieg unter der Nachtschwelle im Fenster, Ruecklauf, und ein zweiter zuegiger Anstieg nach dem
     * Fensterende - wie vorbeugendes Gegenessen Stunden nach dem Marker. Zuegig, weil ein langsamer Anstieg im
     * Evidenzbestand verfaellt, bevor er versiegelt (die Episode bliebe DORMANT).
     */
    private val fNacht = Fuse20Folgen.Folge(
        "NACHT", 1_700_560_800_000L, -90, 240,
        listOf(
            -90 to 96.0, -10 to 96.0, 0 to 96.0, 5 to 98.0, 23 to 134.0, 40 to 134.0, 70 to 112.0, 110 to 98.0,
            128 to 96.0, 133 to 98.0, 151 to 134.0, 170 to 134.0, 240 to 110.0,
        ),
        markerMin = 0,
    )

    /**
     * NACHT-TOTBAND UND MAHLZEITFENSTER (Toni): Kredit entwaffnet das Nacht-Totband nur, solange das Fenster des
     * Kontos laeuft. Im Fenster gilt "Rebound-Totfenster darf das Mahlzeitenfenster niemals blocken" unveraendert;
     * danach bleibt das Band trotz Kredit scharf, unter der Schwelle wird nichts gegeben. Boost aus, sonst entwaffnete
     * `markerBoost` das Band kreditunabhaengig.
     */
    @Test
    fun `Kredit entwaffnet das Nacht-Totband nur im Mahlzeitfenster`(@TempDir dir: File) = runBlocking<Unit> {
        konfiguration(kontoAn = true)
        whenever(preferences.get(FuseIntKey.NightStartMin)).thenReturn(0)
        whenever(preferences.get(FuseIntKey.NightEndMin)).thenReturn(1439)
        whenever(preferences.get(FuseIntKey.MarkerBoostMaxMin)).thenReturn(0)
        val zyklen = lauf(fNacht, dir).filter { it.o.abortReason == null && it.o.state != null && it.o.signal != null }
        fun unterSchwelle(z: Zyklus) = z.o.signal!!.q1 < z.o.state!!.targetMgdl + z.o.state!!.nightDeadbandMgdl
        fun mitKredit(z: Zyklus) = (z.o.evidenceCreditMgdlPerMin ?: 0.0) > 0.0
        val diagnose = {
            zyklen.filter { it.minute % 5 == 0 }.joinToString(" ") {
                "%d:%.0f/%s/%.2f/%s".format(
                    it.minute, it.o.signal!!.q1, it.o.evidencePhase, it.o.evidenceCreditMgdlPerMin ?: 0.0, it.o.decision.bindingLimit,
                )
            }
        }
        val fensterEnde = zyklen.firstNotNullOfOrNull { it.konto?.windowEndTs } ?: throw AssertionError("kein Konto gebucht")
        val imFenster = zyklen.filter { it.o.computeTs < fensterEnde && mitKredit(it) && unterSchwelle(it) }
        val danach = zyklen.filter { it.o.computeTs >= fensterEnde && mitKredit(it) && unterSchwelle(it) }

        assertTrue(imFenster.isNotEmpty()) { "kein Kreditzyklus unter der Schwelle im Fenster: " + diagnose() }
        assertTrue(imFenster.all { it.o.evidenceMayOverrideNight }) { "im Fenster fehlt das Nacht-Recht: " + diagnose() }
        assertTrue(imFenster.none { it.o.decision.bindingLimit == "nightDeadband" }) { "Nacht-Totband im Fenster: " + diagnose() }

        assertTrue(danach.isNotEmpty()) { "kein Kreditzyklus unter der Schwelle nach dem Fenster: " + diagnose() }
        assertTrue(danach.none { it.o.evidenceMayOverrideNight }) { "Nacht-Recht nach dem Fenster: " + diagnose() }
        assertTrue(danach.all { it.o.decision.smbU == 0.0 }) { "nach dem Fenster dosiert: " + diagnose() }
        assertTrue(danach.any { it.o.decision.bindingLimit == "nightDeadband" }) { "das Nacht-Totband bindet nicht: " + diagnose() }
    }

    /**
     * Derselbe Unterschied als Menge: Rueckfall ohne Konto (keine Kurve) gegen dieselbe Folge ohne Druck. Ohne Vollmacht
     * haelt das Rebound-Totband den Bedarf zurueck; im Rueckfall dosiert FUSE in denselben Zyklen.
     */
    @Test
    fun `Rueckfall im Rebound-Fenster dosiert, derselbe Lauf ohne Vollmacht nicht`(@TempDir dir: File) = runBlocking<Unit> {
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
     * und ab da SPERRT das Tief den Erwartungsanteil der Mahlzeit, bis die Freigabe wieder erreicht ist - auch ueber
     * einen Neustart. Toni 22.10.: dann faellt die Sperre von selbst, ohne Bestaetigung, und nie, solange das Tief misst.
     */
    private suspend fun pruefeTief(dir: File, abbruch: IntRange?, neustartMin: Int?, grund: String) {
        nurErwartung = true
        var vorNeustart: ExpectationAccount.State? = null
        var nachNeustart: ExpectationAccount.State? = null
        val alle = try {
            markerAt = 0L
            markerPress = 0L
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
            lauf(fTiefNachDruck, dir, vorZyklus = { m ->
                whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(
                    if (abbruch != null && m in abbruch) UNGUELTIGE_REBOUND_DAUER else FuseIntKey.ReboundWindowMin.defaultValue,
                )
                if (m == neustartMin) {
                    vorNeustart = ledger.episodes.expectation
                    neustart(dir, fTiefNachDruck, null)
                    nachNeustart = ledger.episodes.expectation
                }
            })
        } finally {
            nurErwartung = false
        }
        if (neustartMin != null) {
            val k = vorNeustart ?: throw AssertionError("vor dem Neustart kein Konto")
            assertTrue(k.earlyAfterLow) { "Probe zeigt nichts: das Tief ist vor dem Neustart nicht vermerkt" }
            assertEquals(k, nachNeustart, "das Konto samt Tief-Vermerk und Sperre kommt unveraendert zurueck")
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
        assertTrue(danach.all { it.konto?.earlyAfterLow == true }) { "das Tief ist nicht vermerkt" }
        assertTrue(danach.first().konto?.earlyLowLocked == true) { "nicht gesperrt" }
        // Toni 22.10.: die Sperre faellt von selbst - mit der Wiederfreigabe, nie im gemessenen Tief.
        val frei = danach.firstOrNull { it.konto?.earlyLowLocked == false } ?: throw AssertionError("die Sperre faellt nie")
        assertTrue(frei.o.erwartungskonto!!.zyklus!!.eval.released) { "Sperre ohne Freigabe gefallen" }
        assertTrue(frei.o.step?.safetyReasons?.isEmpty() == true) { "Sperre gefallen, obwohl das Tief noch misst" }
        val gesperrt = danach.filter { it.o.computeTs < frei.o.computeTs }
        assertTrue(gesperrt.all { it.konto?.earlyLowLocked == true }) { "Sperre zwischendurch gefallen" }
        assertEquals(0.0, gesperrt.sumOf { it.dErw() }, 0.0)
        assertTrue(alle.filter { it.o.step?.safetyReasons?.isNotEmpty() == true }.none { it.dErw() > 0.0 }) { "Portion im Tief" }
    }

    @Test
    fun `ein gemessenes Tief nimmt die Freigabe zurueck und sperrt, bis die Freigabe wieder erreicht ist`(@TempDir dir: File) =
        runBlocking<Unit> { pruefeTief(dir, abbruch = null, neustartMin = null, grund = "LOW") }

    /** Codex-Befund P0: das Tief faellt nur in Abbruchzyklen vor dem Kontoschritt. */
    @Test
    fun `ein Tief in Abbruchzyklen sperrt ebenso`(@TempDir dir: File) =
        runBlocking<Unit> { pruefeTief(dir, abbruch = 5..13, neustartMin = null, grund = "LOW_ON_ABORT") }

    /** Codex-Befund P0: Tief in Abbruchzyklen, danach ein Neustart - Tief-Vermerk und Sperre gehen nicht verloren. */
    @Test
    fun `nach Tief im Abbruch und Neustart geht nichts verloren`(@TempDir dir: File) =
        runBlocking<Unit> { pruefeTief(dir, abbruch = 5..13, neustartMin = 16, grund = "LOW_ON_ABORT") }

    /**
     * Codex-Befund P0: auch ohne Tief verlangt eine nach dem Neustart noch freigegebene Episode die Strecke neu - oder,
     * seit Toni 22.10., eine steigende Mahlzeit (zwei steigende Messwerte nach dem Neustart).
     */
    @Test
    fun `nach einem Neustart braucht eine freigegebene Episode die Strecke neu`(@TempDir dir: File) = runBlocking<Unit> {
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
        // Die volle Strecke - oder (Toni 22.10.) die Mahlzeit steigt: zwei steigende Messwerte NACH dem Neustart.
        val steigt = wieder.o.erwartungskonto!!.zyklus!!.mahlzeitSteigt
        assertTrue(steigt || wieder.o.computeTs - fStabil.ts(neustartMin) >= FuseIntKey.ExpectationStabilityMin.defaultValue * 60_000L) {
            "Freigabe ohne volle Strecke und ohne Anstieg nach dem Neustart"
        }
        assertTrue(wieder.minute > erster.minute) { "Freigabe schon im ersten Zyklus nach dem Neustart" }
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
    private suspend fun fruehKonfiguration(
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
    private suspend fun fruehLauf(
        f: Fuse20Folgen.Folge, dir: File, vorZyklus: suspend (Int) -> Unit = {}, einstellen: suspend () -> Unit,
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
        // Konsolidierung 2.0: die Proben auf v1-Ausnahme und Direktdosis-Quellen sind entfallen - es gibt beide nicht mehr.
        // Budget nie ueberschritten - mit Nachschlag (Toni 23.10.) hoechstens Budget plus Nachschlag.
        val summe = zyklen.sumOf { it.dErw() }
        val nachschlag = zyklen.mapNotNull { it.konto?.topUpU }.maxOrNull() ?: 0.0
        assertTrue(nachschlag <= konto.early!!.topUpShare * konto.a0U + 1e-9) { "Nachschlag $nachschlag ueber dem Deckel" }
        assertTrue(summe <= konto.a0U + nachschlag + 1e-9) { "Budget ueberschritten: $summe > ${konto.a0U} + $nachschlag" }
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
        // Erwartungsanteil nur, wenn das Konto ihn erlaubt (nicht pausiert, freigegeben, nicht eingefroren - ausser die
        // Mahlzeit steigt, Toni 22.10. -, nicht gesperrt) und nie in einem gemessenen Tief.
        for (z in zyklen.filter { it.dErw() > 0.0 }) {
            val k = z.o.erwartungskonto!!.zyklus!!
            assertFalse(k.pausiert, "Minute ${z.minute}: Portion in Pause ${k.pausenGruende}")
            assertTrue(k.eval.released && (!k.eval.frozen || k.mahlzeitSteigt) && !k.eval.expired) {
                "Minute ${z.minute}: Portion ohne Freigabe"
            }
            assertFalse(k.state.earlyLowLocked) { "Minute ${z.minute}: Portion trotz Tief-Sperre" }
            assertTrue(z.o.step?.safetyReasons?.isEmpty() == true) { "Minute ${z.minute}: Portion im gemessenen Tief" }
        }
        // Toni 22.10.: "die Mahlzeit steigt" nur fuer weitere Portionen, ueber der Stabilitaetsgrenze, ohne gemessenes
        // Tief, mit zwei steigenden Messwerten hintereinander.
        for ((i, z) in zyklen.withIndex()) {
            val k = z.o.erwartungskonto?.zyklus?.takeIf { it.mahlzeitSteigt } ?: continue
            assertFalse(k.eval.early?.initial == true) { "Minute ${z.minute}: steigt in der Anfangsportion" }
            assertTrue((z.o.signal?.q1 ?: 0.0) > k.state.stabilityBgMgdl) { "Minute ${z.minute}: steigt unter der Grenze" }
            assertTrue(z.o.step?.safetyReasons?.isEmpty() == true) { "Minute ${z.minute}: steigt im gemessenen Tief" }
            val vorher = zyklen.getOrNull(i - 1)
            assertTrue((z.o.signal?.ukfRatePerMin ?: 0.0) >= ExpectationAccount.RISING_MGDL_PER_MIN) { "Minute ${z.minute}: Trend" }
            assertTrue(vorher != null && (vorher.o.signal?.ukfRatePerMin ?: 0.0) >= ExpectationAccount.RISING_MGDL_PER_MIN) {
                "Minute ${z.minute}: nur ein steigender Messwert"
            }
        }
    }

    @Test
    fun `fruehe Versorgung - Budget aus Kurve und Insulinwirkung, Mechanik haelt fuer jede Probe`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `G1 und G2 heben die Anfangsportion - Gegenprobe ohne sie`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `weitere Portionen - G3 und G4 pruefen mit Erwartung, Gegenprobe ohne sie`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `harte Sperren - in der Pause keine Portion, nach der Pause kein Nachholstoss`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `der Hauptschalter entzieht die fruehe Versorgung fuer den Rest der Mahlzeit`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `vor +20 rechnen Guard und Schwanz keine Erwartung an`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `ein Tief vor dem Druck macht den Start nicht zu nach Tief`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `ein gemessenes Tief am Druck gilt als Start nach Tief, auch knapp ueber der Grenze`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `die Fallgrenze der Strecke entscheidet ueber die Startlage bei leichtem Abdriften`(@TempDir dir: File) = runBlocking<Unit> {
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

    /**
     * Toni 22.10. (ersetzt Bank N2, W3 "Rescue-KH nach einem Tief sind keine bestaetigte Mahlzeit"): nach einem
     * gemessenen Tief geht die Mahlzeit von selbst weiter, sobald das Tief vorbei und die Freigabe wieder erreicht ist -
     * wie vor dem Tief: der Rest steht zur Verfuegung, Schritt 2 (laufende Mahlzeit, Fortsetzung) gilt wieder.
     */
    @Test
    fun `nach einem gemessenen Tief geht die Mahlzeit von selbst weiter - wie vor dem Tief`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = fruehLauf(fTiefNachDruck, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val tief = zyklen.firstOrNull { it.o.erwartungskonto?.zyklus?.ruecknahmeGrund == "LOW" }
        assertNotNull(tief) { "die Probe erreicht kein gemessenes Tief nach der Freigabe" }
        val danach = zyklen.filter { it.minute >= tief!!.minute && it.o.erwartungskonto?.zyklus != null }
        assertEquals("LOCKED_BY_LOW", danach.first().o.erwartungskonto!!.zyklus!!.eval.status)
        val frei = danach.firstOrNull { it.konto?.earlyLowLocked == false } ?: throw AssertionError("die Sperre faellt nie")
        assertEquals(0.0, danach.filter { it.minute < frei.minute }.sumOf { it.dErw() }, 0.0)
        val spaeter = danach.filter { it.minute >= frei.minute }
        assertTrue(spaeter.none { it.o.erwartungskonto!!.zyklus!!.eval.status == "LOCKED_BY_LOW" }) { "neu gesperrt ohne Tief" }
        assertTrue(spaeter.any { (it.o.erwartungskonto!!.zyklus!!.eval.restEffU) > 0.0 }) { "der Rest steht nicht zur Verfuegung" }
        // Schritt 2 gilt nach dem Tief wieder: laufende Mahlzeit und Fortsetzung, sobald r ueber der Rampe liegt.
        val laufend = spaeter.filter { (it.o.signal?.rSigned ?: 0.0) >= FuseDoubleKey.RiseRampLowR.defaultValue }
        assertTrue(laufend.isNotEmpty()) { "Probe zeigt nichts: nach dem Tief kein r ueber der Rampe" }
        assertTrue(laufend.all { it.o.erwartungskonto!!.zyklus!!.mahlzeitLaeuft }) { "Schritt 2 gilt nach dem Tief nicht" }
        assertTrue(laufend.any { (it.frueh()?.continuationUPerMin ?: 0.0) > 0.0 }) { "keine Fortsetzung nach dem Tief" }
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
    fun `leichtes Fallen haelt die Anfangsportion nicht auf, danach und bei deutlichem Fallen bleibt es streng`(@TempDir dir: File) = runBlocking<Unit> {
        val fallGrenze = 0.8
        val skalaTief = 0.5
        suspend fun v2(f: Fuse20Folgen.Folge, name: String) = fruehLauf(f, File(dir, name)) {
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
    fun `der Ueberdeckungsbefund des Tieftors haelt den Mahlzeitstart nicht auf, danach bremst er`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = fruehLauf(fTieftor, dir, vorZyklus = { m ->
            if (m == -60) boliSetzen(
                boluses + BS(
                    id = ++bolusId, timestamp = clock, amount = 1.5, type = BS.Type.NORMAL,
                    ids = IDs(pumpId = clock, pumpType = PumpType.GENERIC_AAPS, pumpSerial = Fuse20Folgen.PUMPE_SERIAL),
                    iCfg = rigICfg(),
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
                iCfg = rigICfg(),
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
    fun `ein alter Abwaertsriegel haelt die Anfangsportion nicht auf, die weiteren Portionen ohne laufende Mahlzeit schon`(@TempDir dir: File) = runBlocking<Unit> {
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
        // Danach, solange der Riegel steht: keine weitere Portion - ausser die Mahlzeit steigt (Toni 22.10., eigene Probe).
        val danach = zyklen.filter {
            it.minute > anfang.minute && it.o.descentLatchActive && it.o.erwartungskonto?.zyklus?.mahlzeitSteigt == false
        }
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
    fun `ein aktuelles Abwaertsrisiko haelt die Anfangsportion unter der Fallgrenze nicht auf`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `laeuft die Mahlzeit, haelt der alte Riegel auch die weiteren Portionen nicht auf - Gegenprobe ohne`(@TempDir dir: File) = runBlocking<Unit> {
        suspend fun lauf(name: String, ohneLauf: Boolean) = fruehLauf(fLaufRiegel, File(dir, name), vorZyklus = { bolusVorDemDruck(it, 3.0) }) {
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
        // Ohne laufende Mahlzeit - und ohne Anstieg (Toni 22.10.: steigt die Mahlzeit, haelt der alte Riegel ebenfalls nicht).
        val gehalten = ohne.filter {
            it.frueh()?.initial == false && it.o.descentLatchActive && it.o.erwartungskonto?.zyklus?.mahlzeitSteigt == false
        }
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
    fun `die Schwelle Mahlzeit laeuft ab r entscheidet allein, 0 folgt der Rampe`(@TempDir dir: File) = runBlocking<Unit> {
        suspend fun lauf(name: String, einstellen: suspend () -> Unit) =
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
    fun `laeuft die Mahlzeit, pausiert leichtes Fallen die weiteren Portionen erst ab der Fallgrenze - Gegenprobe ohne`(@TempDir dir: File) = runBlocking<Unit> {
        val fallGrenze = 1.0
        suspend fun lauf(name: String, ohneLauf: Boolean) = fruehLauf(fLaufFallend, File(dir, name)) {
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
    fun `nach Minute 90 fordert das Konto weiter, solange die Mahlzeit laeuft - Gegenprobe ohne`(@TempDir dir: File) = runBlocking<Unit> {
        suspend fun lauf(name: String, ohneLauf: Boolean) = fruehLauf(fSpaet, File(dir, name)) {
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

    /** Synthetisch: steiler Anstieg schon am Druck - "steigt" gilt in den ersten Minuten, vor jeder Anrechnung. */
    private val fSteilSofort = Fuse20Folgen.Folge(
        "STEIL_SOFORT", 1_700_733_600_000L, -90, 240,
        listOf(
            -90 to 115.0, -10 to 116.0, 0 to 122.0, 5 to 138.0, 10 to 154.0, 20 to 174.0, 30 to 184.0, 45 to 182.0,
            60 to 166.0, 90 to 130.0, 120 to 110.0, 180 to 112.0, 240 to 116.0,
        ),
        markerMin = 0,
    )

    /**
     * TONI 22.10.: "waehrend aktiver Mahlzeiten darf nichts blockieren, solange Bedarf da ist und BG steigt". Steigt der
     * Zucker (UKF mindestens +1 in zwei Messwerten hintereinander), kommt die weitere Portion ohne Strecke, Einfrieren und
     * alten Riegel, und mit G2 prueft der Schwanz sie nicht - wie die Anfangsportion. TONI 25.10.: den Guard prueft sie
     * wieder, MIT der angekuendigten Mahlzeit (wie G3); sieht er damit eine Unterschreitung, gibt es keine Portion (vorher
     * fiel mit G1 auch der Guard weg). Steigt er nicht, gilt G3/G4 wie bisher. Gegenprobe ohne G1/G2: auch der Schwanz
     * prueft beim Anstieg mit Erwartung. Synthetisches Budget und synthetischer Skalierer; die Schwanzpruefung ist an (im
     * Gestell sonst aus), damit ihre Kappe zeigt, welche Pruefung galt.
     */
    @Test
    fun `steigt die Mahlzeit, prueft der Guard die weitere Portion mit der Mahlzeit, der Schwanz nicht - Gegenprobe ohne G1 und G2`(@TempDir dir: File) = runBlocking<Unit> {
        suspend fun lauf(f: Fuse20Folgen.Folge, name: String, g12: Boolean) = fruehLauf(f, File(dir, name)) {
            fruehKonfiguration(g1 = g12, g2 = g12, g3 = true, g4 = true, budgetU = EarlySupply.MAX_BUDGET_U)
            whenever(preferences.get(FuseDoubleKey.EarlySupplyScaleStable)).thenReturn(EarlySupply.MAX_SCALE)
            whenever(preferences.get(FuseBooleanKey.TailGuardEnabled)).thenReturn(true)
        }
        fun steigt(z: Zyklus) = z.o.erwartungskonto?.zyklus?.mahlzeitSteigt == true
        fun mitPortion(z: Zyklus) = z.o.erwartungskonto?.fruehHebung != null && z.frueh()?.initial == false
        fun schwanzMitErwartung(z: Zyklus) = z.o.erwartungskonto!!.fruehSchwanzMitErwartungU
        val boden = FuseDoubleKey.GuardFloorMgdl.defaultValue
        fun tabelle(z: List<Zyklus>) = z.joinToString(" | ") {
            val x = it.o.erwartungskonto!!
            "${it.minute}: gS=${it.frueh()?.guardScale} unten=${x.fruehUntenMitErwartungMgdl} grund=${x.fruehEndgrund} " +
                "dErw=${it.dErw()} geliefert=${it.geliefertU}"
        }

        var guardHielt = false
        for ((f, name) in listOf(fSpaet to "spaet", fSteilSofort to "steil")) {
            val mit = lauf(f, "$name-mit", g12 = true)
            pruefeMechanik(mit, 0.5, 1.0, 2.0)
            val steigend = mit.filter { steigt(it) && mitPortion(it) }
            assertTrue(steigend.isNotEmpty()) { "$name: Probe zeigt nichts - keine weitere Portion beim Anstieg" }
            for (z in steigend) {
                val x = z.o.erwartungskonto!!
                assertEquals(AuthorizedLift.EarlyLift(guardLift = true, tailLift = true), x.fruehHebung, "$name Minute ${z.minute}")
                // Der Schwanz prueft die steigende Portion nicht (G2) ...
                assertNull(x.fruehSchwanzKappeU, "$name Minute ${z.minute}: Schwanzkappe beim Anstieg")
                assertTrue(x.fruehEndgrund != FuseCycleRunner.TAIL_VETO) { "$name Minute ${z.minute}: Schwanz-Veto beim Anstieg" }
                // ... der Guard mit Mahlzeit schon: sieht er die Unterschreitung schon ohne die Portion, fliesst nichts.
                val unten = x.fruehUntenMitErwartungMgdl
                assertNotNull(unten) { "$name Minute ${z.minute}: Guard mit Mahlzeit nicht gerechnet" }
                if (unten!! < boden) assertEquals(0.0, z.dErw(), 0.0, "$name Minute ${z.minute}: Portion trotz Guard mit Mahlzeit $unten")
                if (x.fruehEndgrund != null) guardHielt = true
            }
            assertTrue(steigend.sumOf { it.dErw() } > 0.0) { "$name: beim Anstieg kein Erwartungsanteil - " + tabelle(steigend) }
            // Steigt der Zucker nicht, prueft der Schwanz mit Erwartung wie bisher (G4).
            for (z in mit.filter { !steigt(it) && mitPortion(it) && schwanzMitErwartung(it) != null }) {
                assertEquals(schwanzMitErwartung(z), z.o.erwartungskonto!!.fruehSchwanzKappeU, "$name Minute ${z.minute}")
            }
        }
        // Gegenprobe zur Regel vom 22.10. (dort war die Endpruefung beim Anstieg immer leer): der Guard hat beim Anstieg
        // mindestens einmal gehalten oder gekuerzt.
        assertTrue(guardHielt) { "Probe zeigt nichts: der Guard mit Mahlzeit hat beim Anstieg nie gehalten" }

        // Gegenprobe ohne G1/G2: auch beim Anstieg die Pruefung mit Erwartung (G3/G4), nichts uebersprungen.
        val ohne = lauf(fSpaet, "ohne", g12 = false)
        pruefeMechanik(ohne, 0.5, 1.0, 2.0)
        val ohneSteigend = ohne.filter { steigt(it) && mitPortion(it) && schwanzMitErwartung(it) != null }
        assertTrue(ohneSteigend.isNotEmpty()) { "Probe zeigt nichts: ohne G1/G2 keine weitere Portion beim Anstieg" }
        for (z in ohneSteigend) {
            assertEquals(schwanzMitErwartung(z), z.o.erwartungskonto!!.fruehSchwanzKappeU, "Minute ${z.minute}")
        }
    }

    /**
     * TONI 23.10.: "Mahlzeit steigt ab" - die Mahlzeit steigt erst ueber der eingestellten Schwelle (ein Hochfedern
     * knapp ueber der Stabilitaetsgrenze nach einem Abfall ist kein Anstieg der Mahlzeit). Gegenprobe ab Werk (0 = die
     * Stabilitaetsgrenze): dort steigt sie schon darunter.
     */
    @Test
    fun `die Mahlzeit steigt erst ueber der eingestellten Schwelle - Gegenprobe ab Werk`(@TempDir dir: File) = runBlocking<Unit> {
        val schwelleBg = 180.0
        suspend fun lauf(name: String, schwelle: Double) = fruehLauf(fSpaet, File(dir, name)) {
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
            whenever(preferences.get(FuseDoubleKey.MealRisingMinBgMgdl)).thenReturn(schwelle)
        }
        fun steigtBei(z: List<Zyklus>) =
            z.filter { it.o.erwartungskonto?.zyklus?.mahlzeitSteigt == true }.map { requireNotNull(it.o.signal?.q1) }

        val abWerk = lauf("abWerk", 0.0)
        pruefeMechanik(abWerk, 0.5, 1.0, 2.0)
        assertTrue(steigtBei(abWerk).any { it <= schwelleBg }) { "Probe zeigt nichts: ab Werk steigt sie nie unter $schwelleBg" }

        val mit = lauf("mit", schwelleBg)
        pruefeMechanik(mit, 0.5, 1.0, 2.0)
        val bg = steigtBei(mit)
        assertTrue(bg.isNotEmpty()) { "ueber der Schwelle steigt die Mahlzeit nie" }
        assertTrue(bg.all { it > schwelleBg }) { "steigt unter der Schwelle: $bg" }
    }

    /**
     * TONI 23.10.: der NACHSCHLAG. Zeigt sich die Mahlzeit groesser als die Kurve, gibt das Konto ueber das Budget hinaus,
     * hoechstens den eingestellten Anteil des Budgets ([pruefeMechanik]). Kleines synthetisches Budget, damit es sicher
     * aufgebraucht wird. Gegenprobe ab Werk (0 = kein Nachschlag): das Budget bleibt die Grenze. Herunterstellen wirkt
     * sofort, schon im selben Zyklus.
     */
    @Test
    fun `mit Nachschlag gibt das Konto ueber das Budget hinaus, hoechstens den Anteil - Gegenprobe ab Werk`(@TempDir dir: File) = runBlocking<Unit> {
        suspend fun lauf(name: String, anteil: Double, vorZyklus: suspend (Int) -> Unit = {}) =
            fruehLauf(fSpaet, File(dir, name), vorZyklus = vorZyklus) {
                fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true, budgetU = 2.0)
                whenever(preferences.get(FuseDoubleKey.EarlySupplyTopUpShare)).thenReturn(anteil)
            }

        val ohne = lauf("ohne", 0.0)
        pruefeMechanik(ohne, 0.5, 1.0, 2.0)
        val kontoOhne = ohne.mapNotNull { it.konto }.last()
        assertEquals(0.0, kontoOhne.topUpU, 0.0)
        val summeOhne = ohne.sumOf { it.dErw() }
        assertTrue(summeOhne >= kontoOhne.a0U - 0.1) { "Probe zeigt nichts: Budget nicht aufgebraucht ($summeOhne von ${kontoOhne.a0U})" }

        val mit = lauf("mit", 0.5)
        pruefeMechanik(mit, 0.5, 1.0, 2.0)
        val konto = mit.mapNotNull { it.konto }.last()
        assertEquals(0.5, konto.early!!.topUpShare, 0.0)
        assertTrue(konto.topUpU > 0.0) { "kein Nachschlag, obwohl die Mahlzeit ueber der Kurve liegt" }
        val summe = mit.sumOf { it.dErw() }
        assertTrue(summe > konto.a0U + 1e-9) { "nicht ueber das Budget: $summe von ${konto.a0U}" }

        // Herunterstellen: ab dieser Minute gilt der kleinere Deckel - im selben Zyklus, ohne neuen Marker.
        val abMin = requireNotNull(mit.firstOrNull { (it.konto?.topUpU ?: 0.0) > 0.2 * konto.a0U }?.minute) {
            "Probe zeigt nichts: der Nachschlag waechst nie ueber den kleineren Deckel"
        }
        val runter = lauf("runter", 0.5) { min ->
            if (min >= abMin) whenever(preferences.get(FuseDoubleKey.EarlySupplyTopUpShare)).thenReturn(0.2)
        }
        val danach = runter.filter { it.minute >= abMin && it.konto != null }
        assertTrue(danach.isNotEmpty()) { "Probe zeigt nichts: nach dem Herunterstellen kein Zyklus" }
        for (z in danach) {
            val k = z.konto!!
            assertEquals(0.2, k.early!!.topUpShare, 0.0, "Minute ${z.minute}")
            assertTrue(k.topUpU <= 0.2 * k.a0U + 1e-9) { "Minute ${z.minute}: Nachschlag ${k.topUpU} ueber dem kleineren Deckel" }
        }
        // Was danach noch fliesst, passt unter den kleineren Deckel.
        val a0U = danach.first().konto!!.a0U
        val vorher = runter.filter { it.minute < abMin }.sumOf { it.dErw() }
        val nachher = danach.sumOf { it.dErw() }
        assertTrue(nachher <= maxOf(0.0, 1.2 * a0U - vorher) + 1e-9) { "nach dem Herunterstellen $nachher, vorher $vorher, Budget $a0U" }
    }

    /** Zucker am Druck knapp unter der Stabilitaetsgrenze, ohne gemessenes Tief. */
    private val fUnterGrenze = Fuse20Folgen.Folge(
        "UNTERGRENZE", 1_701_165_600_000L, -90, 180,
        listOf(-90 to 96.0, -30 to 88.0, 0 to 86.0, 30 to 130.0, 90 to 190.0, 180 to 130.0),
        markerMin = 0,
    )

    /** Toni: "nach Tief" gilt nur unter der Stabilitaetsgrenze (oder nach einem Tief im Rebound-Fenster). */
    @Test
    fun `unter der Stabilitaetsgrenze gilt der Start als nach Tief, auch ohne gemessenes Tief`(@TempDir dir: File) = runBlocking<Unit> {
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

    /** Gegenfall 1: Tief, dann echte Mahlzeit - sie bleibt versorgbar, Toni 22.10.: ohne Bestaetigung. */
    @Test
    fun `Gegenfall Tief mit anschliessender echter Mahlzeit bleibt versorgbar - ohne Bestaetigung`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = fruehLauf(fTiefDannMahlzeit, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        val sperre = pruefeTiefVorFreigabe(zyklen)
        val frei = zyklen.firstOrNull { it.minute > sperre.minute && it.konto?.earlyLowLocked == false }
            ?: throw AssertionError("die Sperre faellt nie")
        // Waehrend des Tiefs und bis zur Wiederfreigabe kein Erwartungsanteil.
        assertEquals(0.0, zyklen.filter { it.minute < frei.minute }.sumOf { it.dErw() }, 0.0)
        assertTrue(zyklen.filter { it.o.step?.safetyReasons?.isNotEmpty() == true }.none { it.dErw() > 0.0 }) { "Portion im Tief" }
        assertTrue(frei.o.step?.safetyReasons?.isEmpty() == true) { "frei, obwohl das Tief noch misst" }
        assertTrue(frei.o.erwartungskonto!!.zyklus!!.eval.released) { "frei ohne Freigabe" }
        // Versorgbar: danach steht der Rest des gebuchten Budgets wieder zur Verfuegung, und die Mahlzeit wird versorgt -
        // aus dem Konto oder, wo er mehr traegt, aus dem Normalbedarf (A).
        val konto = zyklen.mapNotNull { it.konto }.last()
        val nachFrei = zyklen.filter { it.minute >= frei.minute }
        assertTrue(nachFrei.any { (it.o.erwartungskonto?.zyklus?.eval?.restEffU ?: 0.0) > 0.0 }) {
            "der Rest steht nach der Sperre nicht zur Verfuegung"
        }
        val nachher = nachFrei.sumOf { it.geliefertU }
        assertTrue(nachher >= 1.0) { "nach der Sperre nur $nachher U" }
        assertTrue(nachFrei.sumOf { it.dErw() } > 0.0) { "nach der Sperre kein Erwartungsanteil" }
        assertTrue(nachFrei.sumOf { it.dErw() } <= konto.a0U + 1e-9)
        assertTrue(konto.earlyAfterLow)
        // Danach rechnet der Guard die Mahlzeit wieder an - ab +20, soweit sie sich zeigt; solange die Sperre gilt, nie.
        assertTrue(nachFrei.any { (it.frueh()?.guardScale ?: 0.0) > 0.0 }) { "nach der Sperre keine Anrechnung im Guard" }
        assertTrue(zyklen.filter { it.konto?.earlyLowLocked == true }.all { (it.frueh()?.guardScale ?: 0.0) == 0.0 }) {
            "Anrechnung im Guard, solange die Sperre gilt"
        }
    }

    /**
     * Gegenfall 2: Tief, nur Hypo-Behandlung. Toni 22.10. nimmt bewusst in Kauf, dass das Konto nach dem Tief weiterlaeuft
     * ("nach Traubenzucker doset FUSE dagegen, sobald der Zucker steigt"). Es bleibt: waehrend des Tiefs und bis zur
     * Wiederfreigabe kein Erwartungsanteil, keine Anfangsportion, und das Budget deckelt.
     */
    @Test
    fun `Gegenfall Tief mit ausschliesslich Hypo-Behandlung - kein Erwartungsanteil bis zur Wiederfreigabe`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = fruehLauf(fTiefNurHypo, dir) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        val sperre = pruefeTiefVorFreigabe(zyklen)
        val frei = zyklen.firstOrNull { it.minute > sperre.minute && it.konto?.earlyLowLocked == false }
            ?: throw AssertionError("die Sperre faellt nie")
        assertTrue(frei.o.erwartungskonto!!.zyklus!!.eval.released && frei.o.step?.safetyReasons?.isEmpty() == true) {
            "Sperre ohne Freigabe oder im Tief gefallen"
        }
        assertEquals(0.0, zyklen.filter { it.minute < frei.minute }.sumOf { it.dErw() }, 0.0)
        // Derselbe Fall, das Tief nur in Abbruchzyklen (wie der Safety-Halt der Bank): der Vermerk sperrt auch vor der
        // Freigabe - bisher hinterliess er dort keine Spur.
        val abbruch = 5..40
        val imAbbruch = fruehLauf(fTiefNurHypo, File(dir, "abbruch"), vorZyklus = { m ->
            whenever(preferences.get(FuseIntKey.ReboundWindowMin)).thenReturn(
                if (m in abbruch) UNGUELTIGE_REBOUND_DAUER else FuseIntKey.ReboundWindowMin.defaultValue,
            )
        }) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        pruefeMechanik(imAbbruch, 0.5, 1.0, 2.0)
        assertTrue(imAbbruch.none { it.minute in abbruch }) { "ein Zyklus im Abbruchbereich lief bis zum Konto" }
        val erster = imAbbruch.first { it.minute > abbruch.last && it.o.erwartungskonto?.zyklus != null }
        assertEquals("LOW_ON_ABORT", erster.o.erwartungskonto!!.zyklus!!.tiefGrund)
        assertTrue(erster.konto?.earlyLowLocked == true) { "das Tief im Abbruch sperrt nicht" }
        assertEquals(0.0, erster.dErw(), 0.0)
        assertTrue(imAbbruch.none { it.frueh()?.initial == true && it.dErw() > 0.0 }) { "Anfangsportion trotz Tief" }
    }

    @Test
    fun `das spaete Abwaertsrisiko sperrt jede weitere Portion der fruehen Versorgung`(@TempDir dir: File) = runBlocking<Unit> {
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
     * Leichtes Fallen am Druck mit Bolus-IOB: das LANGE Abwaertsrisiko des Marker-Aufschubs (Horizont 60) greift, das
     * kurze nicht; die Mahlzeit steigt erst spaeter. Synthetische Werte.
     */
    private val fFallAmDruck = Fuse20Folgen.Folge(
        "FALL_AM_DRUCK", 1_701_511_200_000L, -90, 180,
        listOf(-90 to 150.0, -20 to 128.0, -10 to 122.0, 0 to 110.0, 8 to 101.0, 15 to 100.0, 30 to 118.0, 60 to 170.0,
            120 to 190.0, 180 to 140.0),
        markerMin = 0,
    )

    /**
     * Toni (Anfangsportion = bewusstes Risiko wie ein Pen-Bolus): auch das lange Abwaertsrisiko des Marker-Aufschubs haelt
     * die Anfangsportion nicht auf. Geraetebefund: beim Druck bereit, am Ende der Kette als expectationDescentRisk
     * gestrichen, solange der Zucker leicht fiel. Weitere Portionen behalten den Schnitt. Synthetische Werte.
     */
    @Test
    fun `das lange Abwaertsrisiko des Marker-Aufschubs haelt die Anfangsportion nicht auf`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = fruehLauf(fFallAmDruck, dir, vorZyklus = { bolusVorDemDruck(it, 2.0) }) {
            fruehKonfiguration(g1 = true, g2 = true, anteil = 0.5)
            whenever(preferences.get(FuseBooleanKey.DeferredPrimeEnabled)).thenReturn(true)
            whenever(preferences.get(FuseIntKey.ExpectationStabilityMin)).thenReturn(0)
            whenever(preferences.get(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin)).thenReturn(2.0)
        }
        pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
        val start = zyklen.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        val rate = requireNotNull(start.o.signal?.ukfRatePerMin)
        assertTrue(rate < 0.0 && rate > -2.0) { "Probe zeigt nichts: Trend $rate nicht leicht fallend" }
        assertTrue(start.o.step?.safetyReasons?.isEmpty() == true) { "Probe zeigt nichts: gemessenes Tief am Druck" }
        val anfang = zyklen.first { it.frueh()?.initial == true && it.dErw() > 0.0 }
        assertTrue(anfang.minute <= start.minute + 1) { "Anfangsportion erst in Minute ${anfang.minute}" }
        assertTrue((anfang.o.smbActuatedU ?: 0.0) > 0.0) { "Anfangsportion vom Schnitt des Aufschubs genullt" }
        // Die Probe zeigt nur etwas, wenn der Schnitt an der Anfangsportion gegriffen haette.
        assertTrue("initialPastDescentRisk" in anfang.o.decision.bindingLimit) {
            "Probe zeigt nichts: kein langes Abwaertsrisiko an der Anfangsportion (bind=${anfang.o.decision.bindingLimit})"
        }
        assertTrue(zyklen.none { it.frueh()?.initial == true && it.o.decision.bindingLimit == "expectationDescentRisk" }) {
            "eine Anfangsportion wurde als expectationDescentRisk gestrichen"
        }
        // Weitere Portionen unter demselben Risiko: keine (Pause DESCENT_RISK_MARKER oder Schnitt).
        val danach = zyklen.filter {
            it.minute > anfang.minute && it.frueh()?.initial == false &&
                "DESCENT_RISK_MARKER" in (it.o.erwartungskonto?.zyklus?.pausenGruende ?: emptyList())
        }
        assertTrue(danach.isNotEmpty()) { "Probe zeigt nichts: das lange Risiko faellt in keine weitere Portion" }
        assertEquals(0.0, danach.sumOf { it.dErw() }, 0.0)
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
    fun `bleibt die Mahlzeit aus, friert die Ruecknahme bei +30 die fruehe Versorgung ein`(@TempDir dir: File) = runBlocking<Unit> {
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

    /**
     * DER GLOBALE SMB-SCHALTER (Toni): steht er beim Druck aus, etwa durch eine Szene "SMB aus", geht nichts hinaus und
     * das Konto bucht nichts. Wird er erst nach dem Fenster der Anfangsportion wieder eingeschaltet, kommt sie NICHT
     * verspaetet - sie ist verfallen, danach gibt es nur die weiteren, gepruefte Portionen.
     */
    @Test
    fun `SMB-Schalter aus am Druck - keine Abgabe und danach keine spaete Anfangsportion`(@TempDir dir: File) = runBlocking<Unit> {
        val mit = fruehLauf(fStabil, File(dir, "mit")) { fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true) }
        assertTrue(mit.any { it.frueh()?.initial == true && it.dErw() > 0.0 }) { "Gegenprobe: keine Anfangsportion mit Schalter an" }

        val einMin = app.aaps.fuse.core.controller.EarlySupply.INITIAL_WINDOW_MIN.toInt() + 5
        val ohne = try {
            fruehLauf(fStabil, File(dir, "ohne"), vorZyklus = { m -> smbSchalterAn = m >= einMin }) {
                fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
            }
        } finally {
            smbSchalterAn = true
        }
        val aus = ohne.filter { it.minute < einMin }
        assertTrue(aus.isNotEmpty())
        assertTrue(aus.all { it.geliefertU == 0.0 && it.dErw() == 0.0 }) {
            "Abgabe oder Buchung bei Schalter aus: " + aus.filter { it.geliefertU > 0.0 || it.dErw() > 0.0 }.map { it.minute }
        }
        assertTrue(aus.any { it.o.smbFinalBlock == FuseTbrTranslator.SMB_SWITCH_OFF_BLOCK }) { "Schalter nicht als Endgrund gemeldet" }
        assertTrue(aus.none { it.o.smbSwitchOn == true }) { "Export meldet den Schalter an" }
        assertTrue(ohne.none { it.minute >= einMin && it.frueh()?.initial == true && it.dErw() > 0.0 }) { "spaete Anfangsportion" }
    }

    @Test
    fun `nach Tief gibt es keine Anfangsportion ausserhalb ihres Fensters`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `Abschalten von G3 wirkt sofort, Einschalten erst ab dem naechsten Marker`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `nach dem Vorgriff traegt im Kontofenster der Normalbedarf, ohne Budget zu verbrauchen`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `eine Einstellungsaenderung im Lauf aendert Budget und Anteil der laufenden Mahlzeit nicht`(@TempDir dir: File) = runBlocking<Unit> {
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
    fun `jeder Druck bucht ein v2-Konto, Nur Marker mit Budget 0`(@TempDir dir: File) = runBlocking<Unit> {
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

    /**
     * Toni 24.10.2026: "Snack" bucht mit dem Snack-Skalierer - das Budget im Verhaeltnis kleiner, die Anfangsportion ihr
     * Anteil davon. Ein Snack-Skalierer ueber dem der Mahlzeit gibt nicht mehr als eine Mahlzeit.
     */
    @Test
    fun `Snack bucht mit dem Snack-Skalierer, nie mehr als eine Mahlzeit`(@TempDir dir: File) = runBlocking<Unit> {
        fun armiert(z: List<Zyklus>) = z.first { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }
        val mahlzeit = armiert(fruehLauf(fStabil, File(dir, "mahlzeit")) {
            fruehKonfiguration(g1 = true, g2 = true, budgetU = SNACK_PROBE_BUDGET_U)
        }).konto!!
        assertTrue(mahlzeit.a0U > 0.0 && mahlzeit.a0U < SNACK_PROBE_BUDGET_U - 1e-9) { "Probe zeigt nichts: Budget ${mahlzeit.a0U}" }
        assertEquals(1.0, mahlzeit.phi0, 0.0)
        snack = true
        try {
            val zyklen = fruehLauf(fStabil, File(dir, "snack")) {
                fruehKonfiguration(g1 = true, g2 = true, budgetU = SNACK_PROBE_BUDGET_U)
                whenever(preferences.get(FuseDoubleKey.EarlySupplySnackScale)).thenReturn(SNACK_SKALA)
            }
            val gebucht = armiert(zyklen)
            assertTrue(gebucht.o.state?.markerSnack == true) { "die Wahl Snack steht nicht im Zustand" }
            val k = gebucht.konto!!
            assertEquals(SNACK_SKALA, k.phi0, 0.0)
            assertEquals(mahlzeit.a0U * SNACK_SKALA, k.a0U, 1e-6)
            val anfang = zyklen.filter { it.frueh()?.initial == true }.sumOf { it.dErw() }
            assertTrue(anfang > 0.0 && anfang <= 0.5 * k.a0U + 1e-9) { "Anfangsportion $anfang bei Snack-Budget ${k.a0U}" }
            pruefeMechanik(zyklen, 0.5, 1.0, 2.0)
            // Groesser als der Skalierer der Mahlzeit: es gilt der der Mahlzeit.
            val gross = armiert(fruehLauf(fStabil, File(dir, "gross")) {
                fruehKonfiguration(g1 = true, g2 = true, budgetU = SNACK_PROBE_BUDGET_U)
                whenever(preferences.get(FuseDoubleKey.EarlySupplySnackScale)).thenReturn(EarlySupply.MAX_SCALE)
            }).konto!!
            assertEquals(1.0, gross.phi0, 0.0)
            assertEquals(mahlzeit.a0U, gross.a0U, 1e-9)
        } finally {
            snack = false
        }
    }

    // ---- HCL STUFE 2 (Toni 25.10.2026, Entwurf B): DER BOLUSRECHNER ---------------------------------------------
    //
    // Gegenproben zur Anrechnung einer Mahlzeit aus dem Bolusrechner bei Guard und Schwanz. Der Rig spielt nach, was
    // FusePlugin.hclMarkerUnterSperre tut: ein Rechner-Bolus (NORMAL) zur Essenszeit, dazu ein Marker "Nur Marker" mit der
    // Rechnung als Kennung. Alle Zahlen synthetisch.

    private var hclCalcTs = 0L
    private var hclZusammen = false
    private var hclGespeichert = ""

    /** Eine HCL-Mahlzeit, die die Kurve deutlich ueberragt: der Rechner-Bolus deckt sie nicht, der Zucker steigt. */
    private val fHcl = Fuse20Folgen.Folge(
        "HCL", 1_700_474_400_000L, -90, 240,
        listOf(
            -90 to 112.0, -10 to 115.0, 0 to 118.0, 15 to 126.0, 30 to 150.0, 45 to 190.0, 60 to 225.0, 90 to 240.0,
            120 to 215.0, 180 to 160.0, 240 to 130.0,
        ),
        markerMin = 0,
    )

    /** Der Zucker faellt nach dem Bolus deutlich (die Mahlzeit kommt spaet) - das Tieftor muss die Null setzen koennen. */
    private val fHclTief = Fuse20Folgen.Folge(
        "HCLTIEF", 1_700_560_800_000L, -90, 240,
        listOf(
            -90 to 125.0, -10 to 125.0, 0 to 124.0, 15 to 108.0, 30 to 90.0, 40 to 82.0, 50 to 86.0, 70 to 120.0,
            100 to 180.0, 150 to 195.0, 240 to 140.0,
        ),
        markerMin = 0,
    )

    /** Die Mahlzeit bleibt aus: der Zucker faellt, wie das Insulin es erwarten laesst - es zeigt sich keine Mahlzeit. */
    private val fHclAus = Fuse20Folgen.Folge(
        "HCLAUS", 1_700_647_200_000L, -90, 240,
        listOf(-90 to 120.0, 0 to 120.0, 20 to 105.0, 40 to 88.0, 60 to 74.0, 80 to 68.0, 120 to 72.0, 240 to 100.0),
        markerMin = 0,
    )

    private suspend fun hclKonfiguration(schalter: Boolean) {
        konfiguration(kontoAn = true)
        whenever(preferences.get(FuseBooleanKey.HclMealsEnabled)).thenReturn(schalter)
        whenever(preferences.get(FuseLongKey.MealMarkerHclCalcTs)).thenAnswer { hclCalcTs }
        whenever(preferences.get(FuseLongKey.MealMarkerHclMerged)).thenAnswer { if (hclZusammen) 1L else 0L }
        whenever(preferences.get(FuseStringNonKey.HclMeals)).thenAnswer { hclGespeichert }
    }

    private fun hclRechnung(calcTs: Long, khInsulinU: Double, bolusU: Double) = app.aaps.core.interfaces.overview.FuseOverviewSource.WizardBolus(
        calcTs = calcTs, mealTs = calcTs, carbsG = khInsulinU * 9.0, icGPerU = 9.0, isfMgdlPerU = 61.0,
        carbsInsulinU = khInsulinU, glucoseInsulinU = null, trendInsulinU = null, cobInsulinU = null, iobU = 0.0,
        superbolusU = null, directCorrectionU = 0.0, percentage = 70, calcTotalU = khInsulinU, requestedU = bolusU,
        deliveredU = bolusU, success = true,
    )

    private fun normalBolus(ts: Long, u: Double) = BS(
        id = ++bolusId, timestamp = ts, amount = u, type = BS.Type.NORMAL,
        ids = IDs(pumpId = ts + 7, pumpType = PumpType.GENERIC_AAPS, pumpSerial = Fuse20Folgen.PUMPE_SERIAL),
        iCfg = rigICfg(),
    )

    /**
     * Ein HCL-Lauf: in Minute 0 der Rechner-Bolus [bolusU] und der Marker "Nur Marker" mit der Rechnung (KH-Insulin
     * [khInsulinU]). [mitMarkerKennung] = false: derselbe Lauf mit einem gewoehnlichen "Nur Marker" (Gegenprobe).
     */
    private suspend fun hclLauf(
        f: Fuse20Folgen.Folge,
        dir: File,
        bolusU: Double = HCL_BOLUS_U,
        khInsulinU: Double = HCL_KH_INSULIN_U,
        mitMarkerKennung: Boolean = true,
        mitRechnung: Boolean = true,
        vorZyklus: suspend (Int) -> Unit = {},
        einstellen: suspend () -> Unit,
    ): List<Zyklus> {
        ohneVorschuss = true
        try {
            einstellen()
            hclGespeichert = if (!mitRechnung) "" else
                org.json.JSONArray(listOf(HclMahlzeiten.zuJson(hclRechnung(f.ts(0), khInsulinU, bolusU)))).toString()
            return lauf(f, dir, vorZyklus = { m ->
                if (m == 0) {
                    if (mitMarkerKennung) hclCalcTs = f.ts(0)
                    boliSetzen(boluses + normalBolus(f.ts(0), bolusU))
                }
                vorZyklus(m)
            }).filter { it.o.abortReason == null }
        } finally {
            ohneVorschuss = false
            hclCalcTs = 0L
            hclZusammen = false
            hclGespeichert = ""
        }
    }

    /** Die Bremsbahn-pessimistische Freigabe-Mittelbahn ohne Mahlzeit - daraus rechnet der Regler den Bedarf. */
    private fun Zyklus.releaseOhneMahlzeit(): Double? {
        val h = FuseIntKey.ReleaseHorizonMin.defaultValue
        val p = o.prediction?.points?.firstOrNull { it.offsetMin == h }?.meanBg ?: return null
        val r = o.restraint?.points?.firstOrNull { it.offsetMin == h }?.meanBg
        return if (r == null) p else minOf(p, r)
    }

    private fun hclTabelle(name: String, zyklen: List<Zyklus>) {
        println("---- $name")
        for (z in zyklen.filter { it.minute in -5..200 }) {
            val h = z.o.hcl
            println(
                "${z.minute} bg=${z.o.bgMgdl?.let { "%.0f".format(it) }} block=${z.o.decision.block} tbr=${z.o.decision.tbr} " +
                    "bind=${z.o.decision.bindingLimit} iob=${z.o.state?.capIobU?.let { "%.2f".format(it) }} " +
                    "smb=${z.o.decision.smbU} geliefert=${z.geliefertU} req=${z.o.decision.insulinReqU?.let { "%.2f".format(it) }} " +
                    "hcl=${h?.applied}/${h?.reason} gS=${h?.guardScale?.let { "%.2f".format(it) }} " +
                    "unten=${h?.minLowerWithoutMgdl?.let { "%.0f".format(it) }}->${h?.minLowerWithMgdl?.let { "%.0f".format(it) }} " +
                    "schwanz=${h?.tailHeadroomWithoutU?.let { "%.2f".format(it) }}->${h?.tailHeadroomWithU?.let { "%.2f".format(it) }} " +
                    "nurAnrechnung=${h?.onlyByCreditU} low=${z.o.lowThreat?.verdict}",
            )
        }
    }

    /**
     * Gegenprobe 1 - SCHALTER AUS: ein Marker aus dem Bolusrechner rechnet wie ein gewoehnlicher "Nur Marker" (dieselbe
     * Entscheidung in JEDEM Zyklus), die Anrechnung steht nur als Grund im Trail, und der Politik-Hash ist der ohne Schalter.
     */
    @Test
    fun `HCL Schalter aus - dieselbe Dosierung wie Nur Marker, keine Anrechnung, Hash unveraendert`(@TempDir dir: File) = runBlocking<Unit> {
        val aus = hclLauf(fHcl, File(dir, "aus").also(File::mkdirs)) { hclKonfiguration(schalter = false) }
        val nurMarker = hclLauf(fHcl, File(dir, "nurMarker").also(File::mkdirs), mitMarkerKennung = false) { hclKonfiguration(schalter = false) }
        assertEquals(nurMarker.map { it.minute }, aus.map { it.minute })
        for ((a, b) in aus.zip(nurMarker)) {
            val k = "Minute ${a.minute}"
            assertEquals(b.o.decision.smbU, a.o.decision.smbU, 0.0, k)
            assertEquals(b.o.decision.block, a.o.decision.block, k)
            assertEquals(b.o.decision.tbr, a.o.decision.tbr, k)
            assertEquals(b.o.decision.insulinReqU, a.o.decision.insulinReqU, k)
            assertEquals(b.o.tbr?.rateUPerH, a.o.tbr?.rateUPerH, k)
            assertEquals(b.geliefertU, a.geliefertU, 0.0, k)
            if (a.minute >= 0) assertEquals("SWITCH_OFF", a.o.hcl?.reason, k)
            assertNull(b.o.hcl, k)
            assertEquals(b.o.configGeneration, a.o.configGeneration, k)
        }
        assertTrue(nurMarker.sumOf { it.geliefertU } > 0.0) { "die Folge dosiert nicht - die Paarung vergleicht nichts" }
        // Eingeschaltet ist es ein anderer Regler - anderer Hash.
        val an = hclLauf(fHcl, File(dir, "an").also(File::mkdirs)) { hclKonfiguration(schalter = true) }
        assertTrue(an.first().o.configGeneration != aus.first().o.configGeneration) { "der Schalter steht nicht im Hash" }
    }

    /**
     * Gegenprobe 2 - KEIN REST, KEINE ANFANGSPORTION: das Konto des Bolusrechners hat kein Budget, keine Portion, keinen
     * Schalter G1..G4; in keinem Zyklus fliesst ein Erwartungsanteil, und der Skalierer ist KH-Insulin durch Kurvenende.
     */
    @Test
    fun `HCL kein automatischer Rest, keine Anfangsportion, kein Erwartungsanteil`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = hclLauf(fHcl, dir) { hclKonfiguration(schalter = true) }
        hclTabelle("HCL", zyklen)
        val konto = zyklen.mapNotNull { it.konto }.last()
        assertEquals(0.0, konto.a0U, 0.0)
        assertEquals(HCL_KH_INSULIN_U / 3.0, konto.phi0, 1e-9)
        // Ohne Hauptschalter der fruehen Versorgung ist sie dem Konto zudem entzogen (withdrawn) - wie bei jedem Marker.
        assertEquals(
            EarlySupply.Pins(0.0, 0.0, 0.0, EarlySupply.MAX_PERIOD_MIN, EarlySupply.Switches.NONE),
            konto.early?.copy(withdrawn = false),
        )
        assertEquals(0.0, zyklen.sumOf { it.dErw() }, 0.0)
        assertTrue(zyklen.none { it.o.decision.grant != null }) { "eine Anhebung aus dem Konto" }
        assertTrue(zyklen.all { (it.o.erwartungskonto?.zyklus?.eval?.restEffU ?: 0.0) == 0.0 })
        // Das Konto bucht nur den gemessenen Anteil - nie mehr, als (BG - Ziel) / ISF - IOB hergibt.
        for (z in zyklen) {
            val e = z.o.erwartungskonto?.zyklus?.eval ?: continue
            assertEquals(ExpectationAccount.measuredShareU(e), ExpectationAccount.deliverableU(e), 1e-9, "Minute ${z.minute}")
        }
    }

    /**
     * Gegenprobe 3 - KEINE ANRECHNUNG VOR +20 MIN, NIE UEBER DER BEOBACHTUNG: angerechnet wird hoechstens der Anteil der
     * Kurve, den die beobachtete Stoerung bis jetzt belegt. Bleibt die Mahlzeit aus (der Zucker faellt mit dem Insulin),
     * gibt die Anrechnung nichts frei.
     */
    @Test
    fun `HCL keine Anrechnung vor 20 Minuten, nie ueber der Beobachtung, nichts ohne Mahlzeit`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = hclLauf(fHcl, File(dir, "hcl").also(File::mkdirs)) { hclKonfiguration(schalter = true) }
        val frueh = zyklen.filter { it.minute in 0 until EarlySupply.ADAPT_FROM_MIN.toInt() }
        assertTrue(frueh.isNotEmpty())
        assertTrue(frueh.none { it.o.hcl?.applied == true }) { "Anrechnung vor +20 min" }
        assertTrue(zyklen.any { it.o.hcl?.applied == true }) { "Vorbedingung: die Mahlzeit wird ueberhaupt angerechnet" }
        val aus = hclLauf(fHclAus, File(dir, "aus").also(File::mkdirs)) { hclKonfiguration(schalter = true) }
        hclTabelle("HCLAUS", aus)
        for (z in zyklen + aus) {
            val h = z.o.hcl?.takeIf { it.applied } ?: continue
            val k = z.konto ?: throw AssertionError("Minute ${z.minute}: Anrechnung ohne Konto")
            val alter = (z.o.computeTs - k.authId) / 60_000.0
            val belegt = kotlin.math.max(0.0, k.disturbanceU) / ExpectationAccount.Curve.of(k.curveValues)!!.at(alter)
            assertTrue(h.guardScale <= belegt + 1e-9) { "Minute ${z.minute}: Anrechnung ${h.guardScale} ueber der Beobachtung $belegt" }
        }
        assertEquals(0.0, aus.filter { it.minute >= 0 }.sumOf { it.geliefertU }, 0.0)
        assertEquals(0.0, aus.sumOf { it.o.hcl?.onlyByCreditU ?: 0.0 }, 0.0)
    }

    /**
     * Gegenprobe 4 - DER BEDARF BLEIBT: mit Anrechnung rechnet der Regler den Bedarf auf der Bahn OHNE Mahlzeit (kein
     * zweites Einrechnen, kein zweiter IOB-Abzug), und die Kante mit Mahlzeit liegt nie unter der ohne.
     */
    @Test
    fun `HCL die Anrechnung hebt nur Guard und Schwanz - der Bedarf bleibt`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = hclLauf(fHcl, dir) { hclKonfiguration(schalter = true) }
        val angerechnet = zyklen.filter { it.o.hcl?.applied == true }
        assertTrue(angerechnet.isNotEmpty())
        for (z in angerechnet) {
            val h = z.o.hcl!!
            val k = "Minute ${z.minute}"
            assertTrue(h.minLowerWithMgdl!! >= h.minLowerWithoutMgdl!! - 1e-9) { "$k: die Anrechnung senkt die Kante" }
            // Zur einzigen Ausnahme beim Schwanz (unphysiologische Kante) s. `HCL der Schwanz rechnet dieselbe Mahlzeit an`.
            val mit = h.tailHeadroomWithU
            val ohne = h.tailHeadroomWithoutU
            if (mit != null && ohne != null) assertTrue(mit >= ohne - 1e-9 || (ohne <= 0.0 && mit <= 0.0)) { "$k: Schwanz" }
            assertTrue(h.guardScale > 0.0 && h.guardScale <= 1.0) { "$k: Anrechnung ${h.guardScale}" }
            // Der Freigabepunkt des Bedarfs ist der der Bahn ohne Mahlzeit.
            val r = z.releaseOhneMahlzeit()
            if (z.o.decision.predAtReleaseMgdl != null && r != null) assertEquals(r, z.o.decision.predAtReleaseMgdl!!, 1e-9, k)
        }
    }

    /**
     * Gegenprobe 5 - DIE ANRECHNUNG GIBT FREI, WAS OHNE SIE GESPERRT WAERE - und der Trail weist genau diese Menge aus
     * (Codex). Nie mehr als die Endmenge.
     */
    @Test
    fun `HCL die Anrechnung hebt eine Sperre ohne Mahlzeit und weist die Menge aus`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = hclLauf(fHcl, dir) { hclKonfiguration(schalter = true) }
        for (z in zyklen) {
            val nur = z.o.hcl?.onlyByCreditU ?: 0.0
            assertTrue(nur <= z.o.decision.smbU + 1e-9) { "Minute ${z.minute}: $nur ueber der Endmenge" }
        }
        val gehoben = zyklen.filter { z ->
            val h = z.o.hcl ?: return@filter false
            h.applied && z.geliefertU > 0.0 && (h.minLowerWithoutMgdl ?: 999.0) < FuseDoubleKey.GuardFloorMgdl.defaultValue
        }
        assertTrue(gehoben.isNotEmpty()) { "keine Abgabe, die ohne Anrechnung am Guard gescheitert waere" }
        for (z in gehoben) assertEquals(z.geliefertU, z.o.hcl!!.onlyByCreditU, 1e-9, "Minute ${z.minute}")
        assertTrue(zyklen.sumOf { it.o.hcl?.onlyByCreditU ?: 0.0 } > 0.0)
    }

    /**
     * Gegenprobe 6 - LOW-BEFUND UND GEMESSENE SPERREN: dort keine Anrechnung, und die Null des Tieftors kommt wie ohne
     * Schalter - bis zur ersten Anrechnung ist JEDE Entscheidung dieselbe wie mit Schalter aus.
     */
    @Test
    fun `HCL Low-Befund - keine Anrechnung, die Null bleibt wie ohne Schalter`(@TempDir dir: File) = runBlocking<Unit> {
        val an = hclLauf(fHclTief, File(dir, "an").also(File::mkdirs), bolusU = HCL_TIEF_BOLUS_U) { hclKonfiguration(schalter = true) }
        val aus = hclLauf(fHclTief, File(dir, "aus").also(File::mkdirs), bolusU = HCL_TIEF_BOLUS_U) { hclKonfiguration(schalter = false) }
        hclTabelle("HCLTIEF", an)
        val low = an.filter { it.o.hcl?.reason == "LOW_THREAT" || it.o.hcl?.reason == "MEASURED_LOCK" || it.o.hcl?.reason == "LOW_LOCK" }
        assertTrue(low.isNotEmpty()) { "Vorbedingung: ein Low-Befund nach dem Bolus" }
        assertTrue(an.any { it.o.decision.tbr == app.aaps.fuse.core.controller.FuseController.TbrAction.ZERO_TEMP }) { "keine Null" }
        val ersteAnrechnung = an.firstOrNull { it.o.hcl?.applied == true }?.minute ?: Int.MAX_VALUE
        for ((a, b) in an.zip(aus).filter { it.first.minute < ersteAnrechnung }) {
            val k = "Minute ${a.minute}"
            assertEquals(b.o.decision.smbU, a.o.decision.smbU, 0.0, k)
            assertEquals(b.o.decision.block, a.o.decision.block, k)
            assertEquals(b.o.decision.tbr, a.o.decision.tbr, k)
            assertEquals(b.o.tbr?.rateUPerH, a.o.tbr?.rateUPerH, k)
        }
        // In Zyklen mit Low-Befund wird nie angerechnet, auch nach der ersten Anrechnung nicht.
        assertTrue(an.filter { it.o.lowThreat?.verdict != LowThreatGate.Verdict.NONE }.none { it.o.hcl?.applied == true })
    }

    /**
     * Gegenprobe 7 - DIE GEMEINSAMEN GRENZEN BINDEN WEITER: mit Anrechnung bleibt jede Abgabe unter maxIOB, und die
     * IOB-Grenze ist in angerechneten Zyklen die bindende - die Anrechnung hebt Guard und Schwanz, nie maxIOB oder iobTH.
     */
    @Test
    fun `HCL maxIOB und iobTH binden auch mit Anrechnung`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = hclLauf(fHcl, dir) {
            hclKonfiguration(schalter = true)
            whenever(preferences.get(DoubleKey.ApsSmbMaxIob)).thenReturn(HCL_ENGES_MAX_IOB)
            whenever(constraintsChecker.getMaxIOBAllowed()).thenAnswer { ConstraintObject(HCL_ENGES_MAX_IOB, aapsLogger) }
        }
        hclTabelle("HCL eng", zyklen)
        val angerechnet = zyklen.filter { it.o.hcl?.applied == true }
        assertTrue(angerechnet.isNotEmpty()) { "Vorbedingung: Anrechnung" }
        val iobGrenze = angerechnet.filter { z ->
            z.o.decision.block in setOf(
                app.aaps.fuse.core.controller.FuseController.Block.MAX_IOB_REACHED,
                app.aaps.fuse.core.controller.FuseController.Block.IOB_TH_REACHED,
            ) || z.o.decision.bindingLimit.let { it.contains("maxIob") || it.contains("iobTh") }
        }
        assertTrue(iobGrenze.isNotEmpty()) { "Vorbedingung: die IOB-Grenze bindet bei Anrechnung" }
        for (z in zyklen) {
            val s = z.o.state ?: continue
            assertTrue(z.geliefertU == 0.0 || s.capIobU + z.geliefertU <= s.maxIobU + 1e-9) {
                "Minute ${z.minute}: IOB ${s.capIobU} + ${z.geliefertU} ueber maxIOB ${s.maxIobU}"
            }
        }
    }

    /**
     * Gegenprobe 8 - DER SCHWANZ MIT DERSELBEN MAHLZEIT: mit Schwanz-Guard rechnet der Schwanz dieselbe bestaetigte
     * Mahlzeit an wie der Guard - sein Spielraum steigt nur, und was nur dadurch frei wurde, steht im Trail.
     */
    @Test
    fun `HCL der Schwanz rechnet dieselbe Mahlzeit an`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = hclLauf(fHcl, dir) {
            hclKonfiguration(schalter = true)
            whenever(preferences.get(FuseBooleanKey.TailGuardEnabled)).thenReturn(true)
        }
        hclTabelle("HCL Schwanz", zyklen)
        val mitSchwanz = zyklen.filter { it.o.hcl?.applied == true && it.o.hcl?.tailHeadroomWithU != null }
        assertTrue(mitSchwanz.isNotEmpty()) { "Vorbedingung: Anrechnung mit Schwanzbericht" }
        for (z in mitSchwanz) {
            val h = z.o.hcl!!
            val ohne = h.tailHeadroomWithoutU!!
            val mit = h.tailHeadroomWithU!!
            // Die Anrechnung hebt die Kante nur. Einzige Ausnahme, und sie sperrt in beiden Faellen: unter 20 mg/dl setzt
            // TailLiability das Budget auf 0 (unphysiologisch), knapp darueber ist es negativ - eine von unter 20 auf knapp
            // unter den Schwanzboden gehobene Kante zeigt deshalb einen etwas kleineren, ebenfalls negativen Spielraum.
            assertTrue(mit >= ohne - 1e-9 || (ohne <= 0.0 && mit <= 0.0)) { "Minute ${z.minute}: Schwanz $ohne -> $mit" }
            assertTrue(h.onlyByCreditU <= z.geliefertU + 1e-9)
        }
        // Wo der Schwanz ohne Mahlzeit sperrt und mit ihr traegt, geht die Abgabe ganz auf die Anrechnung.
        for (z in mitSchwanz.filter { it.o.hcl!!.tailHeadroomWithoutU!! <= 0.0 && it.geliefertU > 0.0 })
            assertEquals(z.geliefertU, z.o.hcl!!.onlyByCreditU, 1e-9, "Minute ${z.minute}")
        assertTrue(zyklen.sumOf { it.geliefertU } > 0.0) { "mit Schwanz-Guard fliesst nichts" }
    }

    /**
     * Gegenprobe 9 - ZUSAMMENGEFUEHRT: kommt die Rechnung zu einem laufenden Marker "Mahlzeit", entfallen ab dann seine
     * weiteren Portionen, und es gibt keine Anrechnung. Was schon gegeben ist, bleibt (es zaehlt als IOB).
     */
    @Test
    fun `HCL zusammengefuehrt - keine weiteren Portionen, keine Anrechnung`(@TempDir dir: File) = runBlocking<Unit> {
        val mergeMin = 10
        nurErwartung = true
        val zyklen = try {
            markerAt = 0L
            markerPress = 0L
            fruehKonfiguration(g1 = true, g2 = true, g3 = true, g4 = true)
            whenever(preferences.get(FuseBooleanKey.HclMealsEnabled)).thenReturn(true)
            whenever(preferences.get(FuseLongKey.MealMarkerHclCalcTs)).thenAnswer { hclCalcTs }
            whenever(preferences.get(FuseLongKey.MealMarkerHclMerged)).thenAnswer { if (hclZusammen) 1L else 0L }
            whenever(preferences.get(FuseStringNonKey.HclMeals)).thenAnswer { hclGespeichert }
            hclGespeichert = org.json.JSONArray(listOf(HclMahlzeiten.zuJson(hclRechnung(fStabil.ts(mergeMin), 4.0, 2.0)))).toString()
            lauf(fStabil, dir, vorZyklus = { m ->
                if (m == mergeMin) {
                    hclCalcTs = fStabil.ts(mergeMin)
                    hclZusammen = true
                    boliSetzen(boluses + normalBolus(fStabil.ts(mergeMin), 2.0))
                }
            }).filter { it.o.abortReason == null }
        } finally {
            nurErwartung = false
            hclCalcTs = 0L
            hclZusammen = false
            hclGespeichert = ""
        }
        assertTrue(zyklen.filter { it.minute < mergeMin }.sumOf { it.dErw() } > 0.0) { "Vorbedingung: vorher flossen Portionen" }
        val danach = zyklen.filter { it.minute >= mergeMin }
        assertEquals(0.0, danach.sumOf { it.dErw() }, 0.0)
        for (z in danach.filter { it.minute < 180 }) {
            assertEquals("MERGED", z.o.hcl?.reason, "Minute ${z.minute}")
            assertFalse(z.o.hcl!!.applied)
            assertEquals(true, z.o.state?.markerHclMerged, "Minute ${z.minute}")
        }
        assertEquals(true, danach.mapNotNull { it.konto }.last().early?.withdrawn)
    }

    /** Gegenprobe 10 - NEUSTART: das HCL-Konto kommt unveraendert zurueck, die Anrechnung laeuft danach weiter. */
    @Test
    fun `HCL Neustart - Konto und Anrechnung kommen zurueck`(@TempDir dir: File) = runBlocking<Unit> {
        val neustartMin = 40
        var vorher: ExpectationAccount.State? = null
        val zyklen = hclLauf(fHcl, dir, vorZyklus = { m ->
            if (m == neustartMin) {
                vorher = ledger.episodes.expectation
                neustart(dir, fHcl, null)
            }
        }) { hclKonfiguration(schalter = true) }
        val k = vorher ?: throw AssertionError("Vorbedingung: ein Konto vor dem Neustart")
        val danach = zyklen.filter { it.minute >= neustartMin }
        assertTrue(danach.isNotEmpty())
        assertTrue(danach.none { it.o.erwartungskonto?.zyklus?.armiertInDiesemZyklus == true }) { "Neubuchung nach dem Neustart" }
        assertEquals(k.phi0, danach.mapNotNull { it.konto }.first().phi0, 0.0)
        assertTrue(danach.any { it.o.hcl?.applied == true }) { "nach dem Neustart keine Anrechnung mehr" }
    }

    /** Gegenprobe 11 - FEHLT DIE RECHNUNG (nicht mehr gespeichert), rechnet der Marker nichts an: weniger, nie mehr. */
    @Test
    fun `HCL ohne gespeicherte Rechnung keine Anrechnung`(@TempDir dir: File) = runBlocking<Unit> {
        val zyklen = hclLauf(fHcl, dir, mitRechnung = false) { hclKonfiguration(schalter = true) }
        assertEquals(0.0, zyklen.mapNotNull { it.konto }.last().phi0, 0.0)
        assertTrue(zyklen.none { it.o.hcl?.applied == true })
    }

    private companion object {

        /** Synthetisches maxIOB des Gestells. */
        const val MAX_IOB = 7.0

        /** HCL-Proben: KH-Insulin der Rechnung [U] (Skalierer 1,5 bei Kurvenende 3 U) und der gegebene Bolus [U]. */
        const val HCL_KH_INSULIN_U = 4.5
        const val HCL_BOLUS_U = 3.0
        /** Die Tief-Probe: ein Bolus, der die fruehe Lage deutlich ueberdeckt [U]. */
        const val HCL_TIEF_BOLUS_U = 4.0
        /** Ein enges maxIOB fuer die Grenz-Probe [U]. */
        const val HCL_ENGES_MAX_IOB = 3.6

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

        /** Synthetischer Snack-Skalierer der Probe. */
        const val SNACK_SKALA = 0.6

        /** Synthetische Budgetgrenze der Snack-Probe [U]: so hoch, dass sie die Mahlzeit nicht deckelt. */
        const val SNACK_PROBE_BUDGET_U = 15.0
    }
}

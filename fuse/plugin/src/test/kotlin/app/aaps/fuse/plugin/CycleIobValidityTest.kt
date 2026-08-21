package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertTrue
import app.aaps.fuse.core.controller.ExpectationLedger
import app.aaps.fuse.core.controller.EvidenceStock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.fuse.plugin.ledger.FuseLedgerAdapter
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever

/**
 * ZYKLUS N LIEFERT U1 - ZYKLUS N+1 MIT UNGUELTIGEM IOB LIEFERT NICHTS.
 *
 * Der von Codex geforderte Pflichtbeweis zum P0 (Re-Audit 3b5cadbf, F2).
 *
 * DER FEHLERMODUS, gegen den dieser Test steht: die zyklusweise
 * Prime-Clearance und jede Mengenkappe setzen voraus, dass die Dosis des
 * Vorzyklus im naechsten IOB-Snapshot ERSCHEINT. Genau diese Annahme brach die
 * erfundene Null: ohne Profil gab der Rechner ein genulltes, aber ENDLICHES
 * `IobTotal` zurueck, jede Endlichkeitspruefung ging durch, und der Folgezyklus
 * rechnete mit "kein Insulin an Bord" - obwohl U1 gerade unterwegs war. U2 waere
 * ohne die Haftung von U1 freigegeben worden.
 *
 * WARUM DIE ABFOLGE UND NICHT EIN EINZELWERT: die erfundene Null ist numerisch
 * identisch mit einer echten. Ein Zyklus allein kann den Unterschied nicht
 * zeigen - erst die Folge "positive Dosis, dann ungueltige Lesung" macht
 * sichtbar, ob der zweite Zyklus die Haftung des ersten kennt oder sie
 * verloren hat.
 *
 * Der Pruefstand faehrt den ECHTEN Runner mit einer echten Zustandsmaschine
 * ueber eine synthetische 1-min-Rohreihe. Nichts an der Entscheidungskette ist
 * nachgebaut; einzige Steuergroesse des Tests ist [iobValid].
 */
class CycleIobValidityTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var ads: AutosensDataStore
    @Mock lateinit var insulinProfileFunction: ProfileFunction
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var insulin: Insulin
    private lateinit var ledger: FuseLedgerAdapter
    private lateinit var runner: FuseCycleRunner

    /** Der in "diesem Prozess" beobachtete Markerdruck - im Rig steuerbar. */
    private var markerPress = 0L

    /** Testschalter: ist die IOB-Lesung dieses Zyklus gueltig? */
    private var iobValid = true

    /** Testschalter fuer den TOCTOU-Fall: nur die BOLUS-Aktivitaetslesung ist
     *  ungueltig, die Gesamt-IOB-Lesung nicht. Genau diese Kombination hat
     *  Codex als F1 gemeldet - das Profil ist bei der einen Lesung kurz weg
     *  und bei der anderen wieder da. */
    private var bolusIobValid = true

    /** Wandert je Zyklus um eine Minute weiter. */
    private var clock = 0L

    private val start = 1_700_000_000_000L / 60_000L * 60_000L

    /** Ruhige, hohe Reihe: 180 mg/dl flach. Hoch genug fuer echten Bedarf
     *  (Profilziel ~103 mg/dl), flach genug, dass der Observer nach
     *  `rearmMin` = 5 min Ruhe auf ARMED geht. Ein Mahlzeiten-Anstieg waere
     *  fuer diesen Beweis nur zusaetzliches Rauschen. */
    /**
     * DIE BG-KURVE, steuerbar (18.08.).
     *
     * Bis hierher war sie konstant 180 und flach. Fuer den
     * DORMANT-Uebergangstest braucht es einen echten ANSTIEG - nur daraus
     * entsteht die unbezahlte, BGI-bereinigte Stoerung, die [EvidenceStock]
     * als Evidenz verbucht. Eine von Hand gesetzte Phase wuerde wieder nur
     * den Klassifikator pruefen, und der ist anderswo gedeckt.
     */
    private var bgKurve: (Long) -> Double = { 180.0 }

    private fun series(untilTs: Long): List<GV> =
        generateSequence(start) { it + 60_000L }
            .takeWhile { it <= untilTs }
            .map { ts ->
                val v = bgKurve(ts)
                GV(
                    timestamp = ts, value = v, raw = v, noise = 0.0,
                    sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT
                )
            }
            .toList()

    /** Wie der echte Rechner: der Zeitstempel des Ergebnisses ist der auf die
     *  volle Minute AUFgerundete Anforderungszeitpunkt. Der Runner baut seine
     *  ISF-Slots ueber genau dieses `time` (nicht ueber die angeforderten
     *  Zeiten), und die muessen streng steigen. */
    private fun iob(atTs: Long, valid: Boolean) = IobTotal(roundUp(atTs)).also {
        it.iob = 0.0
        it.basaliob = 0.0
        it.activity = 0.0
        it.valid = valid
    }

    private fun roundUp(t: Long) = if (t % 60_000L == 0L) t else (t / 60_000L + 1) * 60_000L

    @BeforeEach
    fun setup() {
        insulin = InsulinLyumjevPlugin(rh, insulinProfileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
        whenever(activePlugin.activeInsulin).thenReturn(insulin)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)
        testPumpPlugin.pumpDescription.bolusStep = 0.05

        whenever(dateUtil.now()).thenAnswer { clock }
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(profileFunction.getProfile(any())).thenReturn(validProfile)
        // Geht in den Uebertrags-Fingerabdruck (SubStepAccumulator.context) und
        // ist dort als nicht-nullbar deklariert - ein unstubbed Mock gaebe null.
        whenever(profileFunction.getProfileName()).thenReturn(TESTPROFILENAME)

        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.roundUpTime(any())).thenAnswer { inv -> roundUp(inv.getArgument(0)) }
        whenever(ads.getBgReadingsDataTableCopy()).thenAnswer { series(clock) }
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any()))
            .thenAnswer { inv -> iob(inv.getArgument(0), iobValid) }
        whenever(iobCobCalculator.calculateIobFromBolus()).thenAnswer { iob(clock, iobValid && bolusIobValid) }

        whenever(constraintsChecker.getMaxIOBAllowed()).thenAnswer { ConstraintObject(8.0, aapsLogger) }
        whenever(commandQueue.bolusInQueue()).thenReturn(false)
        whenever(commandQueue.isRunning(any())).thenReturn(false)

        whenever(persistenceLayer.getLastTherapyRecordUpToNow(any())).thenReturn(null)
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenReturn(null)
        whenever(persistenceLayer.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(any())).thenReturn(null)

        stubPolicy()

        ledger = FuseLedgerAdapter()
        runner = FuseCycleRunner(
            iobCobCalculator, profileFunction, activePlugin, constraintsChecker, commandQueue,
            preferences, persistenceLayer, processedTbrEbData, dateUtil, ledger, "test-epoch", { markerPress }
        )
    }

    /** Die Politik des Test-Rigs vom 09.08. - bewusst die LIVE-Werte, damit
     *  der Beweis an derselben Konfiguration haengt wie die Messung. */
    private fun stubPolicy() {
        // AUFFANG ZUERST (Codex-Re-Review 10.08.). Mockito laesst den ZULETZT
        // passenden Stub gewinnen. Am Ende stehend ueberschrieb dieser Aufruf
        // JEDEN spezifischen FuseBooleanKey mit `false` - Tail-, Restraint-,
        // Onset- und Prime-Flag waren also aus, obwohl sie unten ausdruecklich
        // auf true gesetzt werden. Das Rig lief damit nicht in der
        // Konfiguration, die es zu fahren behauptet.
        whenever(preferences.get(anyOrNull<BooleanKey>())).thenReturn(false)
        whenever(preferences.get(FuseDoubleKey.SmbRatio)).thenReturn(0.15)
        whenever(preferences.get(FuseDoubleKey.SmbRatioRise)).thenReturn(0.35)
        whenever(preferences.get(DoubleKey.ApsSmbMaxIob)).thenReturn(8.0)
        whenever(preferences.get(FuseDoubleKey.RiseRampLowR)).thenReturn(0.5)
        whenever(preferences.get(FuseDoubleKey.RiseRampHighR)).thenReturn(2.0)
        whenever(preferences.get(FuseDoubleKey.MaxSmbU)).thenReturn(0.3)
        whenever(preferences.get(FuseDoubleKey.GuardFloorMgdl)).thenReturn(70.0)
        whenever(preferences.get(FuseDoubleKey.PositiveDescentHorizonMin)).thenReturn(30.0)
        whenever(preferences.get(FuseIntKey.IobThPercent)).thenReturn(100)
        whenever(preferences.get(FuseIntKey.ReleaseHorizonMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.LiabilityHorizonMin)).thenReturn(120)
        whenever(preferences.get(FuseIntKey.DriveTauMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.AbsorptionCreditWindowMin)).thenReturn(60)
        whenever(preferences.get(FuseIntKey.MarkerBoostMaxMin)).thenReturn(45)
        whenever(preferences.get(FuseIntKey.NightStartMin)).thenReturn(1380)
        whenever(preferences.get(FuseIntKey.NightEndMin)).thenReturn(480)
        whenever(preferences.get(FuseDoubleKey.NightDeadbandMgdl)).thenReturn(45.0)
        whenever(preferences.get(FuseBooleanKey.NightDeadbandEnabled)).thenReturn(false)
        whenever(preferences.get(FuseDoubleKey.ReboundDeadbandMgdl)).thenReturn(25.0)
        whenever(preferences.get(FuseBooleanKey.ReboundDeadbandEnabled)).thenReturn(true)
        whenever(preferences.get(FuseIntKey.DriveLowerQuantilePct)).thenReturn(50)
        whenever(preferences.get(FuseBooleanKey.TailGuardEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.TailFloorMgdl)).thenReturn(70.0)
        whenever(preferences.get(FuseDoubleKey.TailRecoveryU)).thenReturn(0.0)
        whenever(preferences.get(FuseBooleanKey.FastRestraintEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.BolusShareLambda)).thenReturn(1.0)
        whenever(preferences.get(FuseBooleanKey.OnsetChannelEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.OnsetEnvelopeU)).thenReturn(1.5)
        whenever(preferences.get(FuseBooleanKey.PrimeReleaseEnabled)).thenReturn(true)
        whenever(preferences.get(FuseDoubleKey.PrimeEnvelopeU)).thenReturn(1.2)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(-1L)
        whenever(preferences.get(FuseLongKey.MealMarkerStamp)).thenReturn(0L)
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenReturn(0L)
    }

    /** Einen Zyklus fahren: Uhr eine Minute weiter, dann rechnen. */
    private fun cycle(): FuseCycleRunner.Outcome {
        clock += 60_000L
        return runner.run(false, FuseActivePump("GENERIC_AAPS", virtualPump = true, bolusStepU = 0.05, basalStepUPerH = 0.05))
    }

    /** Bis zur ersten positiven Dosis fahren. Der Observer braucht
     *  `rearmMin` = 5 min Ruhe, die Rohreihe ihren Vorlauf. */
    private fun driveUntilDose(maxCycles: Int = 60): FuseCycleRunner.Outcome {
        clock = start
        val trail = StringBuilder()
        repeat(maxCycles) {
            val o = cycle()
            if (o.decision.smbU > 0.0) return o
            trail.append("\n  #$it ").append(o.abortReason ?: "phase=${o.step?.phase} block=${o.decision.block} reason=${o.reason}")
        }
        throw AssertionError("keine positive Dosis in $maxCycles Zyklen:$trail")
    }

    @Test
    fun `Zyklus N dosiert, Zyklus N+1 mit ungueltigem IOB dosiert nicht`() {
        val u1 = driveUntilDose()
        assertThat(u1.decision.smbU).isGreaterThan(0.0)
        assertThat(u1.abortReason).isNull()

        // ZYKLUS N+1: der Provider kann das IOB nicht mehr rechnen. Frueher kam
        // hier eine erfundene Null - die Haftung von U1 verschwand, und die
        // naechste Menge waere aus einem scheinbar leeren Spielraum gekommen.
        iobValid = false
        val u2 = cycle()
        assertThat(u2.decision.smbU).isEqualTo(0.0)
        assertThat(u2.abortReason).isNotNull()

        // RECOVERY: derselbe Runner, dieselbe Reihe, nur wieder gueltig - der
        // Abbruch darf kein Dauerzustand sein, sonst waere der fail-closed-Pfad
        // eine Stilllegung statt einer Sperre.
        iobValid = true
        assertThat(cycle().abortReason).isNull()
    }

    @Test
    fun `nur die Bolus-Aktivitaet ungueltig reicht schon fuer den Abbruch`() {
        // F1 (Codex Re-Audit 3b5cadbf, P0): der TOCTOU-Fall. Die Gesamt-IOB-
        // Lesung ist GUELTIG - die Kappenpruefung greift also nicht -, nur die
        // Bolus-Aktivitaet ist unbekannt. Bis zu diesem Fix lief der Zyklus mit
        // einer erfundenen Aktivitaet von 0 weiter. Sie speist den Deckungs-
        // Abschlag, also ERHOEHT die Null den Bedarf: die Richtung ist nicht
        // konservativ, und keine Endlichkeitspruefung sieht daran etwas.
        driveUntilDose()
        bolusIobValid = false
        val o = cycle()
        assertThat(o.decision.smbU).isEqualTo(0.0)
        assertThat(o.abortReason).isEqualTo("bolus iob unknown (no profile)")

        bolusIobValid = true
        assertThat(cycle().abortReason).isNull()
    }

    @Test
    fun `der Abbruch nennt die Ursache und ist keine stille Null`() {
        // Eine fail-closed Entscheidung ohne Namen waere von einem
        // rechnerischen "kein Bedarf" nicht zu unterscheiden - im Trail
        // saehe beides gleich aus.
        driveUntilDose()
        iobValid = false
        val o = cycle()
        assertThat(o.abortReason).contains("iob")
        assertThat(o.decision.smbU).isEqualTo(0.0)
    }

    /**
     * DIE LAGE FUER DEN ERWARTUNGS-LEDGER KOMMT AUS DER PHASE, nicht aus der
     * Episoden-ID (Toni 18.08.).
     *
     * Die Verdrahtung im Runner war bis hierher nur durch das Kompilieren
     * gedeckt: eine Mutationsprobe, die wieder `episodeId > 0` einsetzt,
     * blieb gruen. Ein Feld, das der Compiler prueft und sonst niemand, ist
     * genau die Art Verdrahtung, die in dieser Sitzung schon zweimal nur
     * scheinbar da war (decodeStamp, MAX_AGE_MIN).
     *
     * GEPRUEFT WIRD DIESELBE QUELLE, nicht ein erwarteter Wert: die Lage muss
     * exakt die Phase tragen, die derselbe Zyklus in den Export schreibt.
     * Damit bricht der Test bei JEDER zweiten Herleitung - unabhaengig davon,
     * welche Phase im Testszenario gerade gilt.
     *
     * Gefahren wird bis zur ersten Dosis: nur ein VOLLSTAENDIG gerechneter
     * Zyklus traegt eine Lage. Abbruchpfade und der Marker-Rueckfall lassen
     * sie ausdruecklich offen - ohne Regellauf gibt es keine belastbare
     * Aussage ueber die Lage, und `null` ergibt beim Klassifizieren EXCLUDED.
     *
     * GRENZE DIESES TESTS, ausdruecklich benannt: im ruhigen Szenario ohne
     * Marker liefern BEIDE Herleitungen - die richtige (`evidenz?.phase`) und
     * die falsche (`episodeId > 0`) - denselben Wert NONE. Eine
     * Mutationsprobe auf die Episoden-ID bleibt hier deshalb gruen. Der Test
     * deckt die Verdrahtung, nicht ihre Unterscheidbarkeit; unterscheidbar
     * wird sie erst bei einer OFFENEN Episode in DORMANT, und dafuer braeuchte
     * es hier ein vollstaendiges Mahlzeitenszenario (Marker, Evidenzzufluss,
     * Abklingen bis DORMANT).
     *
     * Die REGEL selbst ist gedeckt: FuseExpectationRecorderTest spielt alle
     * sieben Phasen durch, und die Proben darauf beissen.
     */
    @Test
    fun `der Zyklus liefert die typisierte Evidenzphase an den Erwartungs-Ledger`() {
        val o = driveUntilDose()
        val lage = o.expectationSituation
        assertNotNull(lage, "ein vollstaendig gerechneter Zyklus traegt eine Lage")
        assertEquals(
            o.evidencePhase, lage!!.evidencePhase?.name,
            "die Lage und der Export muessen DIESELBE Phase zeigen",
        )
    }

    /**
     * DER LETZTE FEHLENDE NACHWEIS (Toni 18.08.):
     *
     *   Marker -> EvidenceStock ACTIVE -> Abklingen zu DORMANT
     *   -> Episode weiterhin OFFEN -> ExpectationContext.CORRECTION
     *
     * WARUM ER NICHT DURCH DIE VORHANDENEN TESTS GEDECKT IST. Der
     * Klassifikator ist mit allen sieben Phasen geprueft, der Recorder mit
     * einer von Hand gesetzten Phase. Was fehlte: dass der RUNNER in genau
     * diesem Zustand CORRECTION an die Buchfuehrung gibt, waehrend die
     * Episoden-ID gesetzt ist. Eine Mutationsprobe zurueck auf `episodeId > 0`
     * blieb in den ruhigen Szenarien gruen, weil dort beide Herleitungen
     * dasselbe liefern.
     *
     * DIE PHASE WIRD GEFAHREN, NICHT GESETZT: erst ein Anstieg, der echte
     * BGI-bereinigte Stoerung erzeugt, dann Ruhe, bis der Kredit unter die
     * Schwelle verfaellt. Nur so ist der Uebergang derselbe wie im Betrieb.
     */
    @Test
    fun `nach dem Abklingen einer Mahlzeit meldet der Runner CORRECTION bei offener Episode`() {
        clock = start
        // DIE GANZE KURVE VON ANFANG AN.
        //
        // `series()` erzeugt die vollstaendige Reihe bei JEDER Abfrage neu -
        // ein Kurvenwechsel mitten im Lauf schreibt damit die VERGANGENHEIT
        // um, die Sprungerkennung sieht einen Bruch, und die Evidenz landet
        // dauerhaft in SUSPENDED. Genau das ist beim ersten Anlauf passiert
        // (PHASENVERLAUF {SUSPENDED=120}).
        //
        // Form: 20 min flach, 20 min Anstieg mit 3 mg/dl je Minute, dann
        // Plateau. Der Anstieg erzeugt die BGI-bereinigte Stoerung, das
        // Plateau laesst sie verfallen.
        val flachBis = start + 20 * 60_000L
        val anstiegBis = flachBis + 20 * 60_000L
        bgKurve = { ts ->
            when {
                ts <= flachBis   -> 180.0
                ts <= anstiegBis -> 180.0 + (ts - flachBis) / 60_000.0 * 3.0
                else             -> 180.0 + 20 * 3.0
            }
        }

        // 1) Vorlauf ueber den flachen Teil.
        repeat(20) { cycle() }

        // 2) Marker druecken, genau am Beginn des Anstiegs.
        markerPress = clock
        whenever(preferences.get(FuseLongKey.MealMarkerArmedTs)).thenReturn(clock)
        whenever(preferences.get(FuseLongKey.MealMarkerStamp)).thenReturn(clock)

        // 3) Den Anstieg fahren - hier entsteht die Evidenz.
        var mitEvidenz: FuseCycleRunner.Outcome? = null
        val anstiegPhasen = java.util.LinkedHashMap<String, Int>()
        repeat(25) {
            val o = cycle()
            anstiegPhasen.merge(o.evidencePhase ?: "null", 1, Int::plus)
            if (o.evidencePhase == EvidenceStock.Phase.ACTIVE.name ||
                o.evidencePhase == EvidenceStock.Phase.PENDING_SEAL.name
            ) mitEvidenz = o
        }
        assertNotNull(
            mitEvidenz,
            "der Anstieg muss Evidenz erzeugen - sonst prueft der Test nichts. Phasen: $anstiegPhasen",
        )
        assertTrue(
            mitEvidenz!!.evidenceEpisodeId > 0L,
            "und eine Episode eroeffnen: id=${mitEvidenz!!.evidenceEpisodeId}",
        )

        // 4) DER MARKER BLEIBT GESETZT - nur die Kurve flacht ab.
        //
        // Ein Zuruecksetzen der Marker-Preference wirkt wie eine RUECKNAHME,
        // und die widerruft den Evidenzkredit: die Phase bleibt dann dauerhaft
        // SUSPENDED/CREDIT_REVOKED statt nach DORMANT zu wandern (gemessen im
        // ersten Anlauf, 150 Zyklen). Im Betrieb laeuft der Marker durch sein
        // 90-Minuten-Fenster aus, ohne widerrufen zu werden - das ist der Weg,
        // auf dem DORMANT ueberhaupt entsteht.

        // 5) Fahren, bis die Phase DORMANT meldet UND das Markerfenster
        //    abgelaufen ist.
        //
        // BEIDE Bedingungen zusammen sind der gesuchte Zustand. Ein noch
        // laufender Marker macht die Lage zu Recht MEAL - unabhaengig von der
        // Phase; genau das hat der vorige Anlauf gezeigt (DORMANT erreicht,
        // Kontext MEAL). Der Marker laeuft nach OnsetChannel.MARKER_WINDOW_MIN
        // = 90 min von selbst aus, ohne widerrufen zu werden. Wird er dagegen
        // zurueckgesetzt, gilt das als RUECKNAHME und widerruft den
        // Evidenzkredit - die Phase bleibt dann dauerhaft
        // SUSPENDED/CREDIT_REVOKED (ebenfalls gemessen, 150 Zyklen).
        var ruhig: FuseCycleRunner.Outcome? = null
        val ruhePhasen = java.util.LinkedHashMap<String, Int>()
        repeat(200) {
            val o = cycle()
            ruhePhasen.merge(
                (o.evidencePhase ?: "null") + "/marker=" + (o.expectationSituation?.mealMarkerActive), 1, Int::plus,
            )
            if (o.evidencePhase == EvidenceStock.Phase.DORMANT.name &&
                o.expectationSituation?.mealMarkerActive == false &&
                ruhig == null
            ) ruhig = o
        }
        assertNotNull(ruhig, "DORMANT bei abgelaufenem Marker muss erreichbar sein. Phasen: $ruhePhasen")

        // 6) DER NACHWEIS: Episode offen, Lage CORRECTION.
        val o = ruhig!!
        assertTrue(o.evidenceEpisodeId > 0L, "die Episode ist NOCH OFFEN: id=${o.evidenceEpisodeId}")
        val lage = o.expectationSituation
        assertNotNull(lage, "und der Zyklus traegt eine Lage")
        assertEquals(
            EvidenceStock.Phase.DORMANT, lage!!.evidencePhase,
            "die Lage traegt die GEFAHRENE Phase",
        )
        assertEquals(
            ExpectationLedger.ExpectationContext.CORRECTION,
            ExpectationLedger.classify(lage.copy(ledgerSealed = true)).context,
            "eine offene Episode in DORMANT ist Korrekturbetrieb",
        )
    }
}

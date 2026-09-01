package app.aaps.fuse.plugin.replay

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.fuse.core.controller.BasalRecoverySearch
import app.aaps.fuse.core.insulin.KernelOutcome
import app.aaps.fuse.core.insulin.UnitInsulinKernel
import app.aaps.fuse.core.insulin.UnitInsulinKernelBuilder
import app.aaps.fuse.core.predictor.InsulinModelProvenance
import app.aaps.fuse.plugin.AapsUnitInsulinSampler
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

/**
 * DER RIG SELBST UNTER VERTRAG.
 *
 * Der wichtigste Test ist [die flache Bahn ist eine UNTERE Schranke]:
 * er haelt das Vorzeichen der Naeherung fest. Faellt er, ist jede Zahl
 * aus diesem Rig moeglicherweise zu gross - und genau das war der P0
 * der Vorgaengerfassung.
 */
class TeilbasalRigTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var config: Config
    @Mock lateinit var hardLimits: HardLimits
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var insulin: InsulinLyumjevPlugin
    private val anchor = 1_700_000_000_000L
    private val dia = 9.0

    @BeforeEach
    fun setup() {
        whenever(rh.gs(org.mockito.kotlin.any<Int>())).thenReturn("")
        insulin = InsulinLyumjevPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
    }

    /** Der ECHTE Kern des aktiven Plugins - nicht nachgebaut. */
    private fun kernel(ts: Long): UnitInsulinKernel? {
        val model = InsulinModelProvenance("OREF_LYUMJEV", dia, insulin.peak, "InsulinLyumjevPlugin")
        val out = UnitInsulinKernelBuilder.build(
            AapsUnitInsulinSampler(insulin, dia, ts), ts, model, "OREF_LYUMJEV"
        )
        return (out as? KernelOutcome.Ok)?.kernel
    }

    private fun z(
        min: Int,
        zero: Boolean = true,
        verdictNone: Boolean = true,
        gesund: Boolean = true,
        tief: Boolean = false,
        abwaerts: Boolean = false,
        ukf: Double? = 0.0,
        minLower: Double? = 120.0,
        profil: Double? = 0.60,
        smb: Double = 0.0,
    ) = TeilbasalRig.RigZyklus(
        computeTs = anchor + min * 60_000L, sourceTs = anchor + min * 60_000L,
        zeroActive = zero, verdictNone = verdictNone, signalHealthy = gesund,
        measuredLow = tief, descentRiskActive = abwaerts, ukfRatePerMin = ukf,
        minLowerMgdl = minLower, baselineBindenderOffsetMin = 120, timeToFloorMin = null,
        guardFloorMgdl = 70.0, isfMgdlPerU = 77.0, liabilityHorizonMin = 120,
        profilbasalUph = profil, smbPublishedU = smb,
    )

    // ---- DAS VORZEICHEN DER NAEHERUNG (der entscheidende Test) -----------

    @Test
    fun `die flache Bahn ist eine UNTERE Schranke - nie eine Ueberschaetzung`() {
        // Eine ECHTE Bahn, die ueberall mindestens L0 ist und nur an einem
        // Punkt genau L0 erreicht. Die Suche auf ihr muss MINDESTENS so
        // viel freigeben wie die Suche auf der flachgelegten Bahn.
        val l0 = 120.0
        val flach = TeilbasalRig.flacheBahn(l0, anchor, 120)
        val echt = TeilbasalRig.flacheBahn(l0, anchor, 120).let { p ->
            p.copy(points = p.points.map {
                // ueberall hoeher, nur bei Offset 120 auf L0
                val v = if (it.offsetMin == 120) l0 else l0 + 60.0
                it.copy(meanBg = v, lowerBg = v)
            })
        }
        val k = kernel(anchor)!!
        fun r(p: app.aaps.fuse.core.predictor.PredictorResult) =
            BasalRecoverySearch.hoechsteSichereRate(
                prediction = p, kernel = k,
                isfSlots = listOf(app.aaps.fuse.core.predictor.IsfSlot(anchor - 86_400_000L, anchor + 86_400_000L, 77.0)),
                band = app.aaps.fuse.core.controller.CandidateSearch.Band(
                    releaseTargetLowMgdl = 100.0, releaseTargetHighMgdl = 140.0,
                    demandDeadbandMgdl = 10.0, guardFloorMgdl = 70.0,
                    releaseHorizonMin = 30, liabilityHorizonMin = 120,
                ),
                basalSlots = listOf(app.aaps.fuse.core.profile.BasalSlot(anchor - 86_400_000L, anchor + 86_400_000L, 3.0)),
                basalStepUPerH = 0.05, tbrDurationMin = 30, pruefHorizontMin = 120,
            )
        val rFlach = r(flach)
        val rEcht = r(echt)
        assertNull(rFlach.reject); assertNull(rEcht.reject)
        assertTrue(rEcht.rateUPerH >= rFlach.rateUPerH) {
            "die flache Bahn darf NIE mehr freigeben als die echte: flach=${rFlach.rateUPerH} echt=${rEcht.rateUPerH}"
        }
        assertTrue(rFlach.rateUPerH > 0.0) { "und sie muss ueberhaupt etwas tragen, sonst prueft der Test nichts" }
    }

    @Test
    fun `ein Bahnminimum unter dem Boden traegt nichts - auch nicht flachgelegt`() {
        val r = TeilbasalRig.rate(z(0, minLower = 60.0), ::kernel, 0.05, 30)
        assertNotNull(r)
        assertEquals(0.0, r!!.rateUPerH, 1e-12)
        assertEquals(BasalRecoverySearch.Begrenzung.KEINE_RATE, r.begrenzung)
    }

    // ---- DAS EINTRITTSTOR -------------------------------------------------

    @Test
    fun `drei zusammenhaengende offene Zyklen oeffnen die Teilstufe - zwei nicht`() {
        val e = TeilbasalRig.lauf((0..4).map { z(it) }, ::kernel)
        assertEquals(
            listOf(TeilbasalRig.Zustand.ZERO, TeilbasalRig.Zustand.ZERO, TeilbasalRig.Zustand.PARTIAL,
                   TeilbasalRig.Zustand.PARTIAL, TeilbasalRig.Zustand.PARTIAL),
            e.map { it.second.zustand },
        )
    }

    @Test
    fun `jede einzelne Schutzbedingung faellt im SELBEN Zyklus auf ZERO zurueck`() {
        for ((was, stoerer) in listOf<Pair<String, (Int) -> TeilbasalRig.RigZyklus>>(
            "Verdikt zurueck" to { m -> z(m, verdictNone = false) },
            "Signal krank" to { m -> z(m, gesund = false) },
            "gemessenes Tief" to { m -> z(m, tief = true) },
            "Abwaertsrisiko" to { m -> z(m, abwaerts = true) },
            "UKF faellt" to { m -> z(m, ukf = -0.20) },
            "UKF fehlt" to { m -> z(m, ukf = null) },
        )) {
            val zyklen = (0..3).map { z(it) } + stoerer(4) + (5..7).map { z(it) }
            val e = TeilbasalRig.lauf(zyklen, ::kernel)
            assertEquals(TeilbasalRig.Zustand.PARTIAL, e[3].second.zustand, was)
            assertEquals(TeilbasalRig.Zustand.ZERO, e[4].second.zustand, "$was: Rueckfall im selben Zyklus")
            assertEquals(0, e[4].second.streak, "$was: der Streak wird genullt")
            assertEquals(TeilbasalRig.Zustand.ZERO, e[6].second.zustand, "$was: und der Wiedereintritt kostet drei Zyklen")
            assertEquals(TeilbasalRig.Zustand.PARTIAL, e[7].second.zustand, was)
        }
    }

    @Test
    fun `eine Luecke ueber 90 Sekunden auf der Signaluhr nullt den Streak`() {
        val zyklen = listOf(z(0), z(1), z(2).copy(sourceTs = anchor + 4 * 60_000L), z(5), z(6))
        val e = TeilbasalRig.lauf(zyklen, ::kernel)
        assertEquals(1, e[2].second.streak) { "3 min Abstand ist kein Anschluss" }
        assertEquals(TeilbasalRig.Zustand.ZERO, e[2].second.zustand)
        assertEquals(TeilbasalRig.Zustand.PARTIAL, e[4].second.zustand) { "danach kostet der Eintritt wieder drei" }
    }

    @Test
    fun `eine ZURUECKSPRINGENDE Signaluhr nullt den Streak ebenfalls`() {
        // Der Zyklus laeuft weiter, aber der Messpunkt ist derselbe oder
        // aelter - dann ist es kein neuer Beleg, sondern derselbe zweimal.
        // Die SIGNAL-Uhr steht still, die Zyklusuhr laeuft: 0,1,1,2,3.
        val zyklen = listOf(0, 1, 1, 2, 3).mapIndexed { i, s ->
            z(i).copy(sourceTs = anchor + s * 60_000L)
        }
        val e = TeilbasalRig.lauf(zyklen, ::kernel)
        assertEquals(1, e[2].second.streak) { "nicht streng steigend = kein Anschluss" }
        assertEquals(TeilbasalRig.Zustand.ZERO, e[2].second.zustand)
        assertEquals(TeilbasalRig.Zustand.PARTIAL, e[4].second.zustand)
    }

    @Test
    fun `ohne laufende Null gibt es keine Teilstufe und keinen Streak`() {
        val e = TeilbasalRig.lauf((0..4).map { z(it, zero = false) }, ::kernel)
        assertTrue(e.all { it.second.zustand == TeilbasalRig.Zustand.KEINE_NULL })
        assertTrue(e.all { it.second.streak == 0 })
    }

    // ---- FAIL-CLOSED BEI LUECKIGEM TRAIL ---------------------------------

    @Test
    fun `ein unvollstaendiger Trail ergibt keine Rate, sondern gar keine Aussage`() {
        for ((was, zyk) in listOf(
            "kein Bahnminimum" to z(0, minLower = null),
            "kein Profilbasal" to z(0, profil = null),
        )) {
            assertNull(TeilbasalRig.rate(zyk, ::kernel, 0.05, 30), was)
        }
        // und in der Zustandsmaschine bedeutet das ZERO, nicht PARTIAL
        val e = TeilbasalRig.lauf((0..4).map { z(it, minLower = null) }, ::kernel)
        assertTrue(e.all { it.second.zustand == TeilbasalRig.Zustand.ZERO })
    }

    @Test
    fun `ohne Kern gibt es keine Rate`() {
        val e = TeilbasalRig.lauf((0..4).map { z(it) }, { null })
        assertTrue(e.all { it.second.zustand == TeilbasalRig.Zustand.ZERO })
    }
    // ---- OHNE UKF-TOR (Review-Variante) ----------------------------------

    @Test
    fun `ohne UKF-Tor bleiben ALLE anderen Schutzbedingungen bestehen`() {
        // Der Fall, den Toni ausdruecklich mitgemessen haben will: kein
        // zusaetzliches UKF-Tor, aber LowThreat NONE, kein Abwaertsrisiko,
        // kein gemessenes Tief, Health READY und die volle Suche bleiben.
        val steilFallend = (0..4).map { z(it, ukf = -0.80) }
        assertEquals(
            List(5) { TeilbasalRig.Zustand.ZERO },
            TeilbasalRig.lauf(steilFallend, ::kernel).map { it.second.zustand },
        ) { "mit dem gebauten Tor bleibt es bei ZERO" }
        val ohne = TeilbasalRig.lauf(steilFallend, ::kernel, ukfSchwelle = null)
        assertEquals(TeilbasalRig.Zustand.PARTIAL, ohne[4].second.zustand) { "ohne UKF-Tor traegt die Bahn" }

        // aber JEDE andere Bedingung sperrt weiterhin
        for ((was, stoerer) in listOf<Pair<String, (Int) -> TeilbasalRig.RigZyklus>>(
            "Verdikt" to { m -> z(m, ukf = -0.80, verdictNone = false) },
            "Health" to { m -> z(m, ukf = -0.80, gesund = false) },
            "Tief" to { m -> z(m, ukf = -0.80, tief = true) },
            "Abwaertsrisiko" to { m -> z(m, ukf = -0.80, abwaerts = true) },
            "Bahn unter Boden" to { m -> z(m, ukf = -0.80, minLower = 60.0) },
        )) {
            val e = TeilbasalRig.lauf((0..4).map(stoerer), ::kernel, ukfSchwelle = null)
            assertTrue(e.all { it.second.zustand == TeilbasalRig.Zustand.ZERO }, was)
        }
        // und ein FEHLENDER UKF sperrt dann NICHT mehr - das ist der Preis
        val ohneWert = TeilbasalRig.lauf((0..4).map { z(it, ukf = null) }, ::kernel, ukfSchwelle = null)
        assertEquals(TeilbasalRig.Zustand.PARTIAL, ohneWert[4].second.zustand) {
            "ohne UKF-Tor ist ein fehlender UKF kein Hindernis mehr - bewusst so gemessen"
        }
    }

    // ---- ERSATZDECKEL -----------------------------------------------------

    @Test
    fun `ohne Profilbasal gibt es nur mit ausdruecklichem Ersatzdeckel ein Ergebnis`() {
        val ohneProfil = z(0, profil = null)
        assertNull(TeilbasalRig.rate(ohneProfil, ::kernel, 0.05, 30)) { "ohne Deckel keine Aussage" }
        val r = TeilbasalRig.rate(ohneProfil, ::kernel, 0.05, 30, null, 3.0)
        assertNotNull(r)
        assertTrue(r!!.rateUPerH > 0.0)
        assertEquals(3.0, r.profildeckelUPerH, 1e-9) { "der Ersatzdeckel steht im Ergebnis - nachpruefbar" }
    }

    @Test
    fun `ein vorhandenes Profilbasal schlaegt den Ersatzdeckel`() {
        val r = TeilbasalRig.rate(z(0, profil = 0.30), ::kernel, 0.05, 30, null, 3.0)
        assertEquals(0.30, r!!.profildeckelUPerH, 1e-9) { "der Ersatzdeckel darf ein echtes Profil nie ueberschreiben" }
    }

    // ---- AKTUATIONSKANTEN NACH KOMMANDO-UNTERDRUECKUNG --------------------

    @Test
    fun `eine gleichbleibende Rate kostet erst nach 20 Minuten ein neues Kommando`() {
        // TBR 30 min, Erneuerung ab Restlaufzeit unter 10 min: das erste
        // Kommando bei Minute 0, das naechste bei Minute 20.
        val lauf = (0..44).map { m ->
            z(m) to TeilbasalRig.RigErgebnis(TeilbasalRig.Zustand.PARTIAL, 0.30, 3, true, null)
        }
        val b = TeilbasalRig.bilanz(lauf, 0.05, 30)
        assertEquals(3, b.kanten) { "Minute 0, 20 und 40 - nicht 45 Kommandos" }
    }

    @Test
    fun `jeder Ratenwechsel und jeder Rueckfall auf Null ist eine Kante`() {
        val zust = listOf(0.30, 0.30, 0.35, 0.35, 0.0, 0.0, 0.35)
        val lauf = zust.mapIndexed { m, r ->
            z(m) to TeilbasalRig.RigErgebnis(
                if (r > 0.0) TeilbasalRig.Zustand.PARTIAL else TeilbasalRig.Zustand.ZERO, r, 3, true, null)
        }
        val b = TeilbasalRig.bilanz(lauf, 0.05, 30)
        assertEquals(4, b.kanten) { "0,30 | 0,35 | Null | 0,35" }
        assertEquals(2, b.eintritte)
        assertEquals(1, b.rueckfaelle)
    }

    @Test
    fun `ein Ratensprung unter einem halben Pumpenschritt ist KEINE Kante`() {
        val lauf = listOf(0.30, 0.31, 0.30).mapIndexed { m, r ->
            z(m) to TeilbasalRig.RigErgebnis(TeilbasalRig.Zustand.PARTIAL, r, 3, true, null)
        }
        assertEquals(1, TeilbasalRig.bilanz(lauf, 0.05, 30).kanten) {
            "die Pumpe kann 0,01 gar nicht darstellen - das darf kein Kommando ausloesen"
        }
    }

    @Test
    fun `die Menge ist ohne Profilbasal ausdruecklich nicht ausgewiesen`() {
        val mitProfil = (0..3).map { m ->
            z(m) to TeilbasalRig.RigErgebnis(TeilbasalRig.Zustand.PARTIAL, 0.60, 3, true, null)
        }
        assertNotNull(TeilbasalRig.bilanz(mitProfil).basalU)
        val ohne = (0..3).map { m ->
            z(m, profil = null) to TeilbasalRig.RigErgebnis(TeilbasalRig.Zustand.PARTIAL, 0.60, 3, true, null)
        }
        assertNull(TeilbasalRig.bilanz(ohne).basalU) { "ohne Profil darf keine Mengenzahl entstehen" }
    }
}

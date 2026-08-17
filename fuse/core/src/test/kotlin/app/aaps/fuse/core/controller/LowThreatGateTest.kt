package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

/**
 * DAS LOW-TOR, ohne Runner (Tonis Vertrag 17.08.).
 *
 * Der Anlass steht im Klassenkopf von [LowThreatGate]: 677 von 1129 Zyklen
 * mit laufender Null, 60 % eines Tages ohne Fundament. Jeder Testfall hier
 * ist eine Zeile aus dem Vertrag - und die Nutzenprobe ist an derselben
 * oref-Kurve gerechnet, mit der auch die Entscheidung faellt.
 */
class LowThreatGateTest {

    /** Die oref-Kurve fuer Tonis Profil (peak 45, DIA 9 h) als
     *  Wirkungsanteil - dieselbe Parametrisierung wie der Einheitskern. */
    private val wirkung: (Double) -> Double = run {
        val td = 9.0 * 60; val tp = 45.0
        val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
        val a = 2 * tau / td
        val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
        { t: Double ->
            when {
                t <= 0.0  -> 0.0
                t >= td   -> 1.0
                else      -> s * (1 - a) * ((t * t / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1)
            }
        }
    }

    private fun tor(
        measuredLow: Boolean = false,
        healthy: Boolean = true,
        bg: Double? = 130.0,
        rate: Double? = -1.0,
        bolus: Double? = 2.0,
        isf: Double? = 63.0,
        boden: Double = 70.0,
        basal: Double = 0.60,
    ) = LowThreatGate.evaluate(
        measuredLow = measuredLow, signalHealthy = healthy, bgMgdl = bg,
        fallRatePerMin = rate, bolusIobU = bolus, isfMgdlPerU = isf,
        guardFloorMgdl = boden, scheduledBasalUPerH = basal, remainingEffect = wirkung,
    ).verdict

    /** Die volle Rechenspur - fuer die Telemetriepruefungen. */
    private fun spur(
        bg: Double? = 130.0,
        rate: Double? = -1.0,
        bolus: Double? = 2.0,
        healthy: Boolean = true,
    ) = LowThreatGate.evaluate(
        measuredLow = false, signalHealthy = healthy, bgMgdl = bg,
        fallRatePerMin = rate, bolusIobU = bolus, isfMgdlPerU = 63.0,
        guardFloorMgdl = 70.0, scheduledBasalUPerH = 0.60, remainingEffect = wirkung,
    )

    // ---- Die Wirklichkeit zuerst -----------------------------------------

    /** Ein GEMESSENES Tief laeuft an jeder Rechnung vorbei - bei BG 55
     *  laesst man nichts unversucht, auch wenn die Wirkung klein ist. */
    @Test
    fun `ein gemessenes Tief braucht keine Nutzenrechnung`() {
        assertEquals(LowThreatGate.Verdict.MEASURED_LOW, tor(measuredLow = true))
        // auch wenn ALLES andere dagegen spricht
        assertEquals(
            LowThreatGate.Verdict.MEASURED_LOW,
            tor(measuredLow = true, healthy = false, bg = null, rate = null, bolus = null, isf = null),
        )
    }

    /** Ohne brauchbares Signal gibt es keinen positiven Nachweis - und ohne
     *  Nachweis keine Null. */
    @Test
    fun `ohne gesundes Signal bleibt das Tor zu`() {
        assertEquals(LowThreatGate.Verdict.NONE, tor(healthy = false))
    }

    // ---- Die drei Bedingungen, einzeln ------------------------------------

    /**
     * DER HAEUFIGSTE FALL DES ALTEN VERHALTENS: die Bahn sagt tief, der
     * gemessene Verlauf steigt. Genau daraus entstand die 60-%-Nullzeit.
     *
     * DIESER TEST WAR IM ERSTEN WURF STUMPF, und die Mutationsprobe hat es
     * gezeigt: bei 0,0 faengt die Division (Infinity), bei +1,5 die
     * Nutzenprobe (40 min -> ~2 mg/dl). Beide Faelle liefen also gar nicht
     * ueber die Fall-Bedingung, und deren Entfernung blieb gruen.
     *
     * Der scharfe Fall ist der LANGSAME Anstieg: `abs()` macht aus +0,5
     * mg/dl/min eine "Zeit bis Boden" von 120 Minuten und damit einen
     * Nutzen von rund 28 mg/dl - ohne die Fall-Bedingung wuerde das Tor bei
     * STEIGENDEM Zucker oeffnen.
     */
    @Test
    fun `ein flacher oder steigender Verlauf traegt keine Null`() {
        assertEquals(LowThreatGate.Verdict.NONE, tor(rate = 0.0), "flach ist nicht fallend")
        assertEquals(LowThreatGate.Verdict.NONE, tor(rate = 1.5), "steigend erst recht nicht")
        assertEquals(
            LowThreatGate.Verdict.NONE, tor(bg = 130.0, rate = 0.5),
            "ein LANGSAMER Anstieg darf nicht ueber abs() zu einer Bodennaehe werden",
        )
        // Gegenprobe mit demselben Betrag nach unten: dort ist die Null richtig.
        assertEquals(
            LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE, tor(bg = 130.0, rate = -0.5),
            "derselbe Betrag FALLEND traegt sie sehr wohl - sonst prueft der Test nur die Nutzenprobe",
        )
    }

    /**
     * OHNE BOLUS-UEBERDECKUNG keine Null: faellt der BG ohne dass genug
     * Bolus im Spiel ist, ist es kein Insulinproblem - dann hilft auch kein
     * Basalstopp.
     */
    @Test
    fun `ohne Bolus-Ueberdeckung bleibt das Tor zu`() {
        // Strecke zum Boden 60 mg/dl, Bolus 0,5 U x 63 = 31,5 -> deckt nicht
        assertEquals(LowThreatGate.Verdict.NONE, tor(bolus = 0.5))
        // unbekannt ist kein Nachweis
        assertEquals(LowThreatGate.Verdict.NONE, tor(bolus = null))
    }

    /**
     * NUR DER BOLUSANTEIL, und das ist der Punkt: ein negativer Basalanteil
     * aus vorheriger Zurueckhaltung wuerde die Ueberdeckung rechnerisch
     * verdecken und genau dann eine Null verhindern, wenn ohnehin schon zu
     * wenig Basal lief. Der Aufrufer muss den Bolusanteil liefern - dieser
     * Test haelt fest, dass die Groesse das Ergebnis wirklich bestimmt.
     */
    @Test
    fun `die Ueberdeckung haengt am Bolusanteil`() {
        assertEquals(LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE, tor(bolus = 2.0))
        assertEquals(LowThreatGate.Verdict.NONE, tor(bolus = 0.9), "0,9 x 63 = 57 < 60 Strecke")
    }

    /** Liegt der Bodenkontakt jenseits des Fensters, ist es keine NAHE
     *  Gefahr - eine Extrapolation ueber Stunden ist genau das, was der
     *  120-min-Bahn vorgeworfen wird. */
    @Test
    fun `ein sehr langsamer Abfall ist keine nahe Gefahr`() {
        // -0,1/min: 600 min bis zum Boden
        assertEquals(LowThreatGate.Verdict.NONE, tor(rate = -0.1))
    }

    // ---- Die Nutzenprobe - der Kern von Tonis Forderung -------------------

    /**
     * "0 tbr muss auch einen messbaren nutzen haben und eine sich anbahnende
     * hypo tatsaechlich rechnerisch ausbremsen koennen" (Toni).
     *
     * DIE KONTRAINTUITIVE UMKEHRUNG, und sie ist physikalisch richtig: beim
     * SCHNELLEN Sturz hilft Basal nicht, beim LANGSAMEN schon. Die alte
     * Regel machte es genau falsch herum - sie feuerte spaet und wirkungslos.
     */
    @Test
    fun `beim schnellen Sturz lohnt die Null nicht, beim langsamen schon`() {
        // BG 130, Strecke 60. Bei -3,0/min sind das 20 min -> ~0,4 mg/dl.
        assertEquals(
            LowThreatGate.Verdict.NONE, tor(bg = 130.0, rate = -3.0),
            "20 min Vorlauf bringen ein Zehntel des Sensorrauschens",
        )
        // Bei -0,6/min sind es 100 min -> ~19 mg/dl.
        assertEquals(
            LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE, tor(bg = 130.0, rate = -0.6),
            "100 min Vorlauf sind eine wirksame Massnahme",
        )
    }

    /** Die gerechneten Groessenordnungen, an Tonis Profil festgehalten -
     *  sie sind die Begruendung fuer die 5-mg/dl-Schwelle. */
    @Test
    fun `der Nutzen waechst stark ueberproportional mit dem Vorlauf`() {
        fun n(min: Double) = LowThreatGate.nutzenMgdl(min, 0.60, 63.0, wirkung)
        assertTrue(n(20.0) < 1.0, "20 min: ${n(20.0)}")
        assertTrue(n(30.0) < 2.0, "30 min: ${n(30.0)}")
        assertTrue(n(60.0) in 5.0..8.0, "60 min: ${n(60.0)}")
        assertTrue(n(120.0) in 25.0..33.0, "120 min: ${n(120.0)}")
        // Monoton - eine laengere Vorwarnung kann nie weniger bringen.
        assertTrue(n(90.0) > n(60.0) && n(60.0) > n(30.0))
    }

    /** Eine hoehere Basalrate macht die Null wirksamer - dieselbe Lage kann
     *  bei kraeftigem Profil zulaessig sein und bei schwachem nicht. */
    @Test
    fun `die Basalrate entscheidet mit`() {
        assertEquals(LowThreatGate.Verdict.NONE, tor(bg = 115.0, rate = -0.8, basal = 0.20))
        assertEquals(
            LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE,
            tor(bg = 115.0, rate = -0.8, basal = 1.20),
        )
    }

    /** Ohne Einheitskern gibt es keine Nutzenrechnung - und damit kein Tor.
     *  Der Runner reicht dann eine Nullfunktion herein. */
    @Test
    fun `ohne Wirkungskurve bleibt das Tor zu`() {
        val d = LowThreatGate.evaluate(
            measuredLow = false, signalHealthy = true, bgMgdl = 130.0,
            fallRatePerMin = -0.6, bolusIobU = 2.0, isfMgdlPerU = 63.0,
            guardFloorMgdl = 70.0, scheduledBasalUPerH = 0.60, remainingEffect = { 0.0 },
        )
        assertEquals(LowThreatGate.Verdict.NONE, d.verdict)
        assertEquals(LowThreatGate.DENY_NO_BENEFIT, d.denial)
    }

    // ---- Die Rechenspur (Tonis Auflage vor dem Produktiv-Flash) -----------

    /**
     * "Ohne diese Telemetrie waeren die neuen proaktiven Zero-TBRs spaeter
     * nicht nachvollziehbar" (Toni 17.08.).
     *
     * Bei OFFENEM Tor muss jede Zahl dastehen, die zur Null gefuehrt hat -
     * sonst ist die Entscheidung im Nachhinein nicht pruefbar.
     */
    @Test
    fun `ein offenes Tor exportiert seine vollstaendige Rechnung`() {
        val r = spur(bg = 130.0, rate = -0.6)
        assertEquals(LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE, r.verdict)
        assertNull(r.denial, "ein offenes Tor hat keinen Ablehnungsgrund")
        assertEquals(-0.6, r.fallRatePerMin!!, 1e-9)
        assertEquals(2.0, r.bolusIobU!!, 1e-9)
        assertEquals(60.0, r.distanceToFloorMgdl!!, 1e-9)
        assertEquals(100.0, r.minutesToFloor!!, 1e-9)
        assertTrue(r.benefitMgdl!! >= LowThreatGate.MIN_BENEFIT_MGDL, "Nutzen: ${r.benefitMgdl}")
    }

    /**
     * UND BEI ABGELEHNTEM TOR ERST RECHT - das ist der eigentliche Zweck.
     * Eine Null, die NICHT kam, ist im Trail sonst von einem Zyklus ohne
     * Befund nicht zu unterscheiden. Jeder Ablehnungsgrund muss seine eigene
     * Kennung tragen, und die bis dahin gerechneten Zahlen muessen dastehen.
     */
    @Test
    fun `jede Ablehnung nennt ihren Grund und die gerechneten Zahlen`() {
        val steigend = spur(rate = 0.5)
        assertEquals(LowThreatGate.DENY_NOT_FALLING, steigend.denial)
        assertEquals(60.0, steigend.distanceToFloorMgdl!!, 1e-9, "die Strecke ist schon gerechnet")
        assertNull(steigend.minutesToFloor, "die Zeit aber noch nicht - null heisst 'nicht gerechnet'")

        val ohneDeckung = spur(bolus = 0.5)
        assertEquals(LowThreatGate.DENY_NO_OVERCOVERAGE, ohneDeckung.denial)
        assertEquals(0.5, ohneDeckung.bolusIobU!!, 1e-9)

        val zuWeit = spur(rate = -0.1)
        assertEquals(LowThreatGate.DENY_TOO_FAR, zuWeit.denial)
        assertEquals(600.0, zuWeit.minutesToFloor!!, 1e-6, "auch der abgelehnte Wert steht da")

        val zuSpaet = spur(rate = -3.0)
        assertEquals(LowThreatGate.DENY_NO_BENEFIT, zuSpaet.denial)
        assertTrue(zuSpaet.benefitMgdl!! < LowThreatGate.MIN_BENEFIT_MGDL, "${zuSpaet.benefitMgdl}")
        assertEquals(20.0, zuSpaet.minutesToFloor!!, 1e-9)

        assertEquals(LowThreatGate.DENY_UNHEALTHY, spur(healthy = false).denial)
        assertEquals(LowThreatGate.DENY_INPUT, spur(bg = Double.NaN).denial)
    }

    /** Unbrauchbare Eingaben ergeben NIE eine Null - fail-closed heisst hier
     *  ausdruecklich "kein Eingriff", nicht "sicherheitshalber nullen". */
    @Test
    fun `unbrauchbare Eingaben halten das Tor zu`() {
        assertEquals(LowThreatGate.Verdict.NONE, tor(bg = Double.NaN))
        assertEquals(LowThreatGate.Verdict.NONE, tor(rate = Double.NaN))
        assertEquals(LowThreatGate.Verdict.NONE, tor(isf = 0.0))
        assertEquals(LowThreatGate.Verdict.NONE, tor(isf = null))
        assertEquals(LowThreatGate.Verdict.NONE, tor(basal = 0.0), "ohne Basal gibt es nichts zurueckzuhalten")
    }
}

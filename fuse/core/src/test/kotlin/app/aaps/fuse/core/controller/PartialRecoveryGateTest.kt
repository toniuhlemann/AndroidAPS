package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DAS EINTRITTSTOR DER TEILBASAL-RUECKKEHR.
 *
 * DIE AUFLAGE, DIE DIESE DATEI TRAEGT (Toni): kein Test darf einen
 * steilen Fall durch einen HANDGESETZTEN Boolean sperren. Ein fallender
 * Verlauf muss durch die ECHTE Kette laufen - [LowThreatGate.
 * measuredDescentRisk] und [LowThreatGate.evaluate] mit echten
 * Messwerten - und DORT sperren. Sonst prueft der Test nur, dass eine
 * Konjunktion eine Konjunktion ist, und die eigentliche Frage - faengt
 * die verbleibende Kette den Fall wirklich ab, nachdem das UKF-Tor
 * entfernt wurde? - bliebe offen.
 */
class PartialRecoveryGateTest {

    private val guardFloor = 70.0
    private val isf = 100.0

    /** Der Bezugsfall: alles offen. */
    private fun offen(
        enabled: Boolean = true,
        zero: Boolean = true,
        tief: Boolean = false,
        abwaerts: Boolean = false,
        ready: Boolean = true,
        verdictNone: Boolean = true,
        bodenNah: Boolean = false,
    ) = PartialRecoveryGate.open(enabled, zero, tief, abwaerts, ready, verdictNone, bodenNah)

    // =====================================================================
    // DER STEILE FALL DURCH DIE ECHTE KETTE
    // =====================================================================

    /**
     * Die Lage, fuer die das entfernte UKF-Tor zustaendig WAR: BG faellt
     * steil. Hier wird NICHTS gesetzt - `descentRisk` und `verdict`
     * kommen aus dem Produktionstor, und das Ergebnis muss die Sperre
     * sein.
     */
    @Test
    fun `ein steil fallender Verlauf sperrt ueber die echte LowThreat-Kette`() {
        // BG 100, Boden 70 -> Strecke 30. Fall 2 mg/dl je min -> Boden in
        // 15 min, also weit im 120-min-Fenster. Bolus 0,5 U x ISF 100 = 50
        // > 30, also Ueberdeckung positiv. Das ist genau der Fall, den
        // FALLING_WITH_BOLUS_OVERCOVERAGE beschreibt.
        val risiko = LowThreatGate.measuredDescentRisk(
            signalHealthy = true, bgMgdl = 100.0, fallRatePerMin = -2.0,
            bolusIobU = 0.5, isfMgdlPerU = isf, guardFloorMgdl = guardFloor,
        )
        assertTrue(risiko.active) { "die echte Kette muss hier ein Abwaertsrisiko sehen: ${risiko.denial}" }

        val urteil = LowThreatGate.evaluate(
            measuredLow = false, signalHealthy = true, bgMgdl = 100.0,
            fallRatePerMin = -2.0, bolusIobU = 0.5, isfMgdlPerU = isf,
            guardFloorMgdl = guardFloor, scheduledBasalUPerH = 0.60,
            // Ueber ein 15-Minuten-Fenster steht die Wirkung von Lyumjev
            // (Peak 45 min) praktisch noch vollstaendig aus; 1,0 ist die
            // grosszuegige, aber der Sache nach richtige Naeherung.
            remainingEffect = { 1.0 },
        )
        assertEquals(LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE, urteil.verdict) {
            "Nutzen=${urteil.benefitMgdl} Grund=${urteil.denial}"
        }

        // UND JETZT DAS TOR - mit den ERRECHNETEN Werten, nicht mit
        // gesetzten. Es muss zu sein, obwohl es kein UKF-Tor mehr gibt.
        assertFalse(
            offen(abwaerts = risiko.active, verdictNone = urteil.verdict == LowThreatGate.Verdict.NONE)
        ) { "ohne UKF-Tor faengt descentRisk/LowThreat den steilen Fall - sonst waere die Entfernung falsch" }
    }

    /**
     * Der Fall, fuer den die Stufe GEBAUT ist und den das alte UKF-Tor
     * mitgesperrt hat: milder Restabfall, keine Bolusueberdeckung. Die
     * echte Kette sieht hier KEIN Risiko - und genau deshalb darf das Tor
     * offen sein.
     */
    @Test
    fun `ein milder Restabfall ohne Ueberdeckung laesst die echte Kette offen`() {
        val risiko = LowThreatGate.measuredDescentRisk(
            signalHealthy = true, bgMgdl = 140.0, fallRatePerMin = -0.15,
            bolusIobU = 0.0, isfMgdlPerU = isf, guardFloorMgdl = guardFloor,
        )
        assertFalse(risiko.active) { "kein Bolus, keine Ueberdeckung: ${risiko.denial}" }
        val urteil = LowThreatGate.evaluate(
            measuredLow = false, signalHealthy = true, bgMgdl = 140.0,
            fallRatePerMin = -0.15, bolusIobU = 0.0, isfMgdlPerU = isf,
            guardFloorMgdl = guardFloor, scheduledBasalUPerH = 0.60,
            remainingEffect = { 0.0 },
        )
        assertEquals(LowThreatGate.Verdict.NONE, urteil.verdict)
        assertTrue(
            offen(abwaerts = risiko.active, verdictNone = urteil.verdict == LowThreatGate.Verdict.NONE)
        ) { "das ist der Fall, den das alte UKF-Tor mitgesperrt hat (-0,15 < -0,03)" }
    }

    /**
     * Ein gemessenes Tief laeuft NICHT ueber die Rechnung, sondern vor
     * ihr - und muss trotzdem sperren.
     */
    @Test
    fun `ein gemessenes Tief sperrt ohne jede Rechnung`() {
        val urteil = LowThreatGate.evaluate(
            measuredLow = true, signalHealthy = true, bgMgdl = 62.0,
            fallRatePerMin = -0.5, bolusIobU = 0.0, isfMgdlPerU = isf,
            guardFloorMgdl = guardFloor, scheduledBasalUPerH = 0.60,
            remainingEffect = { 0.0 },
        )
        assertEquals(LowThreatGate.Verdict.MEASURED_LOW, urteil.verdict)
        assertFalse(offen(tief = true, verdictNone = false))
    }

    /**
     * DIE GRENZE DER ENTFERNUNG, ehrlich benannt: ein steiler Fall OHNE
     * Bolusueberdeckung erzeugt in der echten Kette KEIN Abwaertsrisiko
     * und KEIN Verdikt - das alte UKF-Tor haette hier gesperrt, das
     * heutige nicht.
     *
     * Zustaendig ist dann allein die [BasalRecoverySearch]: sie gibt eine
     * Rate nur frei, wenn die Bahn MIT ihr den Boden noch traegt. Dieser
     * Test haelt die Luecke fest, damit sie nicht unbemerkt bleibt -
     * nicht, weil sie harmlos waere.
     */
    @Test
    fun `steiler Fall ohne Ueberdeckung - hier traegt allein die Suche`() {
        val risiko = LowThreatGate.measuredDescentRisk(
            signalHealthy = true, bgMgdl = 200.0, fallRatePerMin = -2.0,
            bolusIobU = 0.0, isfMgdlPerU = isf, guardFloorMgdl = guardFloor,
        )
        assertFalse(risiko.active) { "ohne Bolus keine Ueberdeckung, also kein Risiko-Verdikt" }
        assertTrue(offen(abwaerts = risiko.active)) { "das Tor allein sperrt hier NICHT" }
        // Die Suche muss es fangen: eine Bahn, die im Fenster unter den
        // Boden faellt, traegt keine Rate.
        assertTrue(200.0 - 2.0 * 120 < guardFloor) {
            "bei -2 mg/dl/min liegt die Bahn nach 120 min weit unter dem Boden - " +
                "genau das prueft BasalRecoverySearch, s. dortige Vertragstests"
        }
    }

    // =====================================================================
    // JE VERBLEIBENDEM TOR EIN SCHARFER GEGENFALL
    // =====================================================================

    @Test
    fun `jede einzelne verbleibende Bedingung sperrt fuer sich allein`() {
        assertTrue(offen()) { "der Bezugsfall MUSS offen sein, sonst prueft nichts davon etwas" }
        assertFalse(offen(enabled = false)) { "Schalter aus" }
        assertFalse(offen(zero = false)) { "keine laufende Null" }
        assertFalse(offen(tief = true)) { "gemessenes Tief" }
        assertFalse(offen(abwaerts = true)) { "Abwaertsrisiko" }
        assertFalse(offen(ready = false)) { "Signal nicht READY" }
        assertFalse(offen(verdictNone = false)) { "Schutzgrund liegt an" }
        assertFalse(offen(bodenNah = true)) { "Bodenannaeherung im Nahhorizont" }
    }

    // =====================================================================
    // DIE BOLUSUNABHAENGIGE BODENANNAEHERUNG (Review-P1.2)
    // =====================================================================

    /**
     * DIE LUECKE, DIE DIESE PRUEFUNG SCHLIESST - durch die ECHTE Kette
     * belegt: `measuredDescentRisk` verlangt eine Bolusueberdeckung und
     * sieht einen steilen Fall OHNE Bolus deshalb nicht. Genau dort
     * greift jetzt die Bodenannaeherung.
     */
    @Test
    fun `starker Fall OHNE Bolusueberdeckung - die echte Kette sieht nichts, die Bodenannaeherung sperrt`() {
        val risiko = LowThreatGate.measuredDescentRisk(
            signalHealthy = true, bgMgdl = 100.0, fallRatePerMin = -2.0,
            bolusIobU = 0.0, isfMgdlPerU = isf, guardFloorMgdl = guardFloor,
        )
        assertFalse(risiko.active) { "ohne Bolus keine Ueberdeckung: ${risiko.denial}" }
        val urteil = LowThreatGate.evaluate(
            measuredLow = false, signalHealthy = true, bgMgdl = 100.0,
            fallRatePerMin = -2.0, bolusIobU = 0.0, isfMgdlPerU = isf,
            guardFloorMgdl = guardFloor, scheduledBasalUPerH = 0.60,
            remainingEffect = { 1.0 },
        )
        assertEquals(LowThreatGate.Verdict.NONE, urteil.verdict) { "auch kein Verdikt" }

        // 30 mg/dl bis zum Boden bei 2 je min = 15 min, im 30-min-Horizont.
        val nah = PartialRecoveryGate.floorApproachBlocks(
            signalHealthy = true, bgMgdl = 100.0, fallRatePerMin = -2.0,
            guardFloorMgdl = guardFloor, horizonMin = 30.0,
        )
        assertTrue(nah) { "genau hier muss die bolusunabhaengige Pruefung greifen" }
        assertFalse(
            offen(abwaerts = risiko.active,
                  verdictNone = urteil.verdict == LowThreatGate.Verdict.NONE,
                  bodenNah = nah)
        ) { "und das Tor muss dann zu sein" }
    }

    @Test
    fun `ein milder Restabfall ausserhalb des Nahhorizonts kommt bis zur Guard-Suche`() {
        val risiko = LowThreatGate.measuredDescentRisk(
            signalHealthy = true, bgMgdl = 140.0, fallRatePerMin = -0.15,
            bolusIobU = 0.0, isfMgdlPerU = isf, guardFloorMgdl = guardFloor,
        )
        val nah = PartialRecoveryGate.floorApproachBlocks(
            signalHealthy = true, bgMgdl = 140.0, fallRatePerMin = -0.15,
            guardFloorMgdl = guardFloor, horizonMin = 30.0,
        )
        assertFalse(nah) { "70 mg/dl bei 0,15 je min = 467 min - weit ausserhalb" }
        assertTrue(offen(abwaerts = risiko.active, bodenNah = nah)) {
            "der Fall, fuer den die Stufe gebaut ist, darf bis zur Bahnpruefung kommen"
        }
    }

    @Test
    fun `die Bodenannaeherung ist KEINE Flachheitsschwelle - der Abstand entscheidet mit`() {
        // Dieselbe steile Rate, zwei Abstaende: nah sperrt, fern nicht.
        // Genau das konnte das alte UKF-Tor nicht unterscheiden.
        fun nah(bg: Double, rate: Double) = PartialRecoveryGate.floorApproachBlocks(
            signalHealthy = true, bgMgdl = bg, fallRatePerMin = rate,
            guardFloorMgdl = guardFloor, horizonMin = 30.0,
        )
        assertTrue(nah(100.0, -2.0)) { "15 min bis zum Boden" }
        assertFalse(nah(200.0, -2.0)) { "65 min - hier uebernimmt die Bahnpruefung" }
        assertFalse(nah(200.0, -0.05)) { "das alte Tor haette hier gesperrt, ohne jeden Anlass" }
        assertTrue(nah(100.0, -1.0)) { "genau 30 min ist noch im Horizont" }
        assertFalse(nah(101.0, -1.0)) { "31 min nicht mehr" }
    }

    @Test
    fun `steigende oder fehlende Rate sperrt hier nicht - dafuer sind Health und LowThreat da`() {
        fun nah(rate: Double?, gesund: Boolean = true) = PartialRecoveryGate.floorApproachBlocks(
            signalHealthy = gesund, bgMgdl = 100.0, fallRatePerMin = rate,
            guardFloorMgdl = guardFloor, horizonMin = 30.0,
        )
        assertFalse(nah(+2.0)) { "steigend" }
        assertFalse(nah(0.0)) { "flach" }
        assertFalse(nah(null)) { "kein Messwert - eine Sperre hier waere das alte Tor durch die Hintertuer" }
        assertFalse(nah(Double.NaN))
        assertFalse(nah(-2.0, gesund = false)) { "ohne gesundes Signal urteilt diese Pruefung nicht" }
    }

    @Test
    fun `steht der Boden schon ueber uns, sperrt die Annaeherung erst recht`() {
        assertTrue(PartialRecoveryGate.floorApproachBlocks(
            signalHealthy = true, bgMgdl = 65.0, fallRatePerMin = -0.5,
            guardFloorMgdl = guardFloor, horizonMin = 30.0,
        )) { "negative Restzeit ist im Horizont enthalten" }
    }

    /**
     * DER WAECHTER GEGEN EIN WIEDEREINGEFUEHRTES FLACHHEITSTOR.
     *
     * Kein Struktur-, sondern ein Verhaltenstest: bei einem milden
     * Restabfall (-0,15 mg/dl je min, deutlich unter der alten Schwelle
     * -0,03) MUSS das Tor offen sein. Baut jemand eine Flachheitsschwelle
     * wieder ein, faellt dieser Test - und zwar unabhaengig davon, wie
     * die Bedingung geschrieben wird.
     */
    @Test
    fun `eine wiedereingefuehrte Flachheitsschwelle faellt hier auf`() {
        listOf(-0.05, -0.15, -0.30, -0.80).forEach { rate ->
            val risiko = LowThreatGate.measuredDescentRisk(
                signalHealthy = true, bgMgdl = 200.0, fallRatePerMin = rate,
                bolusIobU = 0.0, isfMgdlPerU = isf, guardFloorMgdl = guardFloor,
            )
            assertTrue(offen(abwaerts = risiko.active)) {
                "bei $rate mg/dl je min darf KEINE Flachheitsschwelle mehr sperren"
            }
        }
    }

    // =====================================================================
    // SCHALTER AUS = BITGLEICH
    // =====================================================================

    @Test
    fun `bei ausgeschaltetem Schalter ist die Antwort in JEDER Lage dieselbe`() {
        // Bitgleichheit heisst hier: kein Zustand, in dem der ausgeschaltete
        // Schalter etwas anderes ergibt als "zu". Durchprobiert wird der
        // GESAMTE Zustandsraum, nicht ein Beispiel.
        var geprueft = 0
        for (zero in listOf(true, false))
            for (tief in listOf(true, false))
                for (ab in listOf(true, false))
                    for (rd in listOf(true, false))
                        for (vn in listOf(true, false)) {
                            for (bn in listOf(true, false)) {
                                assertFalse(PartialRecoveryGate.open(false, zero, tief, ab, rd, vn, bn)) {
                                    "aus muss aus bleiben: zero=$zero tief=$tief ab=$ab ready=$rd verdictNone=$vn bodenNah=$bn"
                                }
                                geprueft++
                            }
                        }
        assertEquals(64, geprueft)
    }

    // =====================================================================
    // EINTRITT UND RUECKFALL
    // =====================================================================

    @Test
    fun `der Eintritt kostet FUENF zusammenhaengende Zyklen`() {
        assertEquals(5, PartialRecoveryGate.ENTRY_CYCLES)
        var s = 0
        var letzter = 0L
        val ergebnis = (1..7).map { m ->
            val ts = m * 60_000L
            s = PartialRecoveryGate.streak(true, s, letzter, ts)
            letzter = ts
            s >= PartialRecoveryGate.ENTRY_CYCLES
        }
        assertEquals(listOf(false, false, false, false, true, true, true), ergebnis)
    }

    @Test
    fun `ein geschlossenes Tor nullt den Streak im SELBEN Zyklus`() {
        var s = 4
        s = PartialRecoveryGate.streak(false, s, 60_000L, 120_000L)
        assertEquals(0, s) { "kein Auslaufen, kein Nachhall - sofort zu" }
        // und der Wiedereintritt kostet wieder volle fuenf
        var letzter = 120_000L
        val bis = (1..5).map { m ->
            val ts = 120_000L + m * 60_000L
            s = PartialRecoveryGate.streak(true, s, letzter, ts); letzter = ts; s
        }
        assertEquals(listOf(1, 2, 3, 4, 5), bis)
    }

    @Test
    fun `der Anschluss braucht eine streng steigende Signaluhr mit hoechstens 90 Sekunden`() {
        assertTrue(PartialRecoveryGate.anschluss(60_000L, 120_000L)) { "60 s" }
        assertTrue(PartialRecoveryGate.anschluss(60_000L, 150_000L)) { "genau 90 s" }
        assertFalse(PartialRecoveryGate.anschluss(60_000L, 151_000L)) { "91 s" }
        assertFalse(PartialRecoveryGate.anschluss(60_000L, 60_000L)) { "derselbe Messpunkt" }
        assertFalse(PartialRecoveryGate.anschluss(120_000L, 60_000L)) { "zurueckspringend" }
        assertFalse(PartialRecoveryGate.anschluss(0L, 60_000L)) { "ohne Vorgaenger kein Anschluss" }
    }

    @Test
    fun `eine Luecke beginnt den Zaehler bei EINS, nicht bei null`() {
        // Der Unterschied zaehlt: bei 0 haette der naechste Zyklus nach
        // einer Luecke sechs statt fuenf gebraucht.
        assertEquals(1, PartialRecoveryGate.streak(true, 4, 60_000L, 300_000L))
    }
}

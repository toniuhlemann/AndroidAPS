package app.aaps.fuse.core.controller

import app.aaps.fuse.core.predictor.PredictorResult
import app.aaps.fuse.core.predictor.TrajectoryPoint
import app.aaps.fuse.core.predictor.minSafetyLowerOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrimeReleaseTest {

    private val t0 = 1_700_000_000_000L

    private fun input(
        enabled: Boolean = true,
        marker: Boolean = true,
        armedTs: Long = t0,
        now: Long = t0,
        envelope: Double = 1.2,
        spent: Double = 0.0,
        minLower: Double = 100.0,
        guardFloor: Double = 70.0,
        isf: Double = 80.0,
        step: Double = 0.05,
    ) = PrimeRelease.Input(enabled, marker, armedTs, 0L, now, envelope, spent, minLower, guardFloor, isf, step)

    // ---- Das Fenster ------------------------------------------------------

    /** Ein Fruehstuecksfall: Marker gesetzt, BG flach, Bahn ~100.
     *  Die Freigabe beginnt SOFORT - nicht erst bei der spaeteren CGM-Regung. */
    @Test
    fun `am Marker beginnt die Freigabe sofort`() {
        val p = PrimeRelease.plan(input())
        assertTrue(p.active)
        assertEquals(0.05, p.floorU, 1e-12) // 1,2 U / 15 min, auf Schritt gerundet
        assertEquals("PRIME", p.reason)
    }

    @Test
    fun `ohne Marker gibt es nichts`() {
        assertEquals("NO_MARKER", PrimeRelease.plan(input(marker = false)).reason)
        assertEquals("DISABLED", PrimeRelease.plan(input(enabled = false)).reason)
    }

    @Test
    fun `nach dem Fenster ist Schluss`() {
        val p = PrimeRelease.plan(input(now = t0 + 15 * 60_000L))
        assertFalse(p.active)
        assertEquals("WINDOW_OVER", p.reason)
    }

    /** Die ganze Huelle kommt im Fenster an: 15 simulierte Minuten, jede
     *  Minute wird der Floor geliefert und verbucht. */
    @Test
    fun `die Verteilung liefert die Huelle im Fenster aus`() {
        var spent = 0.0
        for (m in 0 until 15) {
            val p = PrimeRelease.plan(input(now = t0 + m * 60_000L, spent = spent))
            if (p.active) spent += p.floorU
        }
        assertTrue(spent >= 1.15 && spent <= 1.2 + 1e-9) { "geliefert: $spent" }
    }

    /** Evidenz beendet die Wette statt sie zu verdoppeln: was der normale
     *  Pfad im Fenster liefert, zaehlt gegen die Huelle. */
    @Test
    fun `gelieferte Evidenzdosen verbrauchen die Huelle`() {
        val p = PrimeRelease.plan(input(now = t0 + 5 * 60_000L, spent = 1.18))
        assertFalse(p.active)
        assertEquals("ENVELOPE_SPENT", p.reason)
    }

    // ---- Das Clearance-Gate ----------------------------------------------

    /** Die Nachtlage: Bahn nahe am Boden. 100 - 0,2*1,2*80 = 80,8 >= 70 ist
     *  offen; bei Bahn 88 ist 88 - 19,2 < 70 -> gesperrt. */
    @Test
    fun `nahe am Boden bleibt die Freigabe zu`() {
        // Seit 09.08. rechnet die Clearance gegen den ZYKLUS-ANTEIL
        // (Tonis Entscheidung): Huelle 1,2 / 15 min -> 0,05 U je Zyklus,
        // Bedarf 0,2 * 0,05 * 80 = 0,8 mg/dl. Die ABSICHT des Tests bleibt -
        // dicht am Boden ist zu, darueber offen -, nur die Kante liegt jetzt
        // dort, wo sie hingehoert: knapp ueber dem Boden statt 18 mg/dl daneben.
        assertEquals("CLEARANCE", PrimeRelease.plan(input(minLower = 70.5)).reason)
        assertTrue(PrimeRelease.plan(input(minLower = 71.0)).active)
    }

    /** Das Gate rechnet gegen den REST: spaet im Fenster, wenig Rest, darf
     *  die Bahn tiefer stehen. */
    @Test
    fun `das Clearance-Gate schrumpft mit der Resthuelle`() {
        // Rest 0,2 U -> Clearance 3,2 -> Bahn 75 reicht.
        val p = PrimeRelease.plan(input(now = t0 + 10 * 60_000L, spent = 1.0, minLower = 75.0))
        assertTrue(p.active)
    }

    // Konsolidierung 2.0: die Anhebung (lift) ist entfallen - ihre Tests ebenso; der Plan bleibt.

    // ---- C1/C2: gegen WELCHE Bahn die Clearance rechnet --------------------

    private fun traj(minLower: Double, priorFree: Double? = null) = PredictorResult(
        points = (1..120).map {
            TrajectoryPoint(it, t0 + it * 60_000L, 200.0, minLower, 0.0, 0.0, 0.0, priorFree)
        },
        predictionAnchorTs = t0, bgAtAnchor = 200.0,
        minMeanBg = 200.0, minLowerBg = minLower, timeToMinLowerMin = 1,
        bgAtHorizonMean = 200.0, bgAtHorizonLower = minLower,
        lineageKind = "ACTUAL", trajectoryContentHash = "h",
        iobArraySpanMin = 235.0, iobArrayGridMin = 5.0, modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
        minLowerBgPriorFree = priorFree, bgAtHorizonLowerPriorFree = priorFree,
    )

    /**
     * DAS Gegenbeispiel aus der Codex-Adjudication, uebersetzt auf die
     * Sofort-Freigabe (C1 + C2, Punkte 6/8):
     *
     *   Huelle 1,2 U, ISF 80 -> Clearance = 0,2 * 1,2 * 80 = 19,2 mg/dl
     *   Hauptbahn 95     -> 95 - 19,2 = 75,8 >= 70  -> offen
     *   Bremsbahn 74     -> 74 - 19,2 = 54,8 <  70  -> muss sperren
     *   Prior hebt 74 auf 95 -> derselbe Fall, nur ueber den Marker
     *
     * Die Minimierung macht der Aufrufer; getestet wird, dass sie die
     * Entscheidung traegt und dass beide Wege dasselbe ergeben.
     */
    @Test
    fun `C1-C2 die Clearance rechnet gegen die pessimistischste prior-freie Bahn`() {
        val main = traj(95.0)
        val brake = traj(74.0)
        val liftedByPrior = traj(minLower = 95.0, priorFree = 74.0)

        assertEquals(95.0, minSafetyLowerOf(main), 1e-12)
        assertEquals(74.0, minSafetyLowerOf(main, brake), 1e-12)
        assertEquals(74.0, minSafetyLowerOf(liftedByPrior), 1e-12)
        // Bremse abgeschaltet = null aendert nichts (Einseitigkeit).
        assertEquals(95.0, minSafetyLowerOf(main, null), 1e-12)

        // Die WEITERGABE ist der Punkt dieses Tests, nicht die Kantenhoehe:
        // dieselbe Eingabe muss je nach gewaehlter Bahn anders entscheiden.
        // Die Bremse steht deshalb knapp UNTER der Clearance-Kante (Boden 70 +
        // 0,8 mg/dl fuer den Zyklus-Anteil), die Hauptbahn deutlich darueber.
        val brakeTight = traj(70.5)
        assertTrue(PrimeRelease.plan(input(minLower = minSafetyLowerOf(main))).active)
        assertEquals("CLEARANCE", PrimeRelease.plan(input(minLower = minSafetyLowerOf(main, brakeTight))).reason)
        assertEquals("CLEARANCE", PrimeRelease.plan(input(minLower = minSafetyLowerOf(traj(95.0, priorFree = 70.5)))).reason)
    }

    /** Ohne jede Bahn gibt es keinen Ersatzwert: NaN -> NOT_FINITE, nicht
     *  "offen". */
    @Test
    fun `C1 ohne Bahn faellt die Clearance geschlossen aus`() {
        assertEquals("NOT_FINITE", PrimeRelease.plan(input(minLower = minSafetyLowerOf())).reason)
    }

    /**
     * DIE EPISODEN-WAHL AUS DEM MARKER-DIALOG (Toni 15.08.): "es gibt
     * Situationen, wo 3 Einheiten jetzt zuviel waeren". Abgewaehlt heisst
     * USER_NO_PRIME - nicht ENVELOPE_SPENT, die Huelle ist ja nicht weg -
     * und es gilt auch im markerautorisierten Pfad: die Autorisierung
     * entsteht aus der Deckung durch den Druck, und dieser Druck hat die
     * Deckung ausdruecklich nicht erteilt.
     */
    @Test
    fun `die Wahl ohne Vorschuss schaltet die Freigabe dieser Episode ab`() {
        val an = input()
        check(PrimeRelease.plan(an).active) { "Vorbedingung: ohne Wahl ist die Freigabe aktiv" }
        val abgewaehlt = PrimeRelease.plan(an.copy(declinedByUser = true))
        org.junit.jupiter.api.Assertions.assertFalse(abgewaehlt.active)
        org.junit.jupiter.api.Assertions.assertEquals(0.0, abgewaehlt.floorU)
        org.junit.jupiter.api.Assertions.assertEquals("USER_NO_PRIME", abgewaehlt.reason)
        val autorisiert = PrimeRelease.plan(an.copy(declinedByUser = true, markerAuthorized = true, safetyMinLowerMgdl = null))
        org.junit.jupiter.api.Assertions.assertEquals("USER_NO_PRIME", autorisiert.reason)
    }
}

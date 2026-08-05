package app.aaps.fuse.core.observer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Testpflichten aus Spec v0.5 §15 / Addendum A11, soweit sie die
 * Zustandsmaschine betreffen. Jeder Test bezeichnet die Nummer, damit ein
 * spaeterer Review die Abdeckung pruefen kann.
 */
class ObserverStateMachineTest {

    private fun m() = ObserverStateMachine(sessionId = "s1")

    private var t = 1_700_000_000_000L

    private fun input(
        r: Double?,
        bg: Double = 120.0,
        stepMin: Double = 1.0,
        q1: Double? = bg,
        act: ActivityValidity = ActivityValidity.VALID,
        isf: Boolean = true,
        sensorEpoch: Long = 0L,
        calibEpoch: Long = 0L,
        gap: Boolean = false,
    ): ObserverInput {
        t += (stepMin * 60_000).toLong()
        return ObserverInput(
            sourceTs = t, computeTs = t + 500, signalInputBg = bg, q1 = q1, rSigned = r,
            sensorEpoch = sensorEpoch, calibrationEpoch = calibEpoch,
            activity = act, profileIsfValid = isf, inputGap = gap,
        )
    }

    /** Bringt die Maschine ueber WARMUP und den vollen Rearm nach ARMED. */
    private fun ObserverStateMachine.arm(): ObserverStep {
        var last = step(input(0.0))
        repeat(7) { last = step(input(0.0)) }
        return last
    }

    // ---- Grundablauf ----------------------------------------------------

    @Test
    fun `WARMUP endet erst mit vollstaendiger Eingabe`() {
        val sm = m()
        val s1 = sm.step(input(r = null, q1 = null))
        assertEquals(Health.WARMUP, s1.health)
        assertEquals(Phase.REARMING, s1.phase)
        val s2 = sm.step(input(0.0))
        assertEquals(Health.READY, s2.health)
    }

    @Test
    fun `voller Rearm fuehrt nach ARMED, nicht frueher`() {
        val sm = m()
        sm.step(input(0.0))                       // READY, quiet 0
        repeat(4) { sm.step(input(0.0)) }         // quiet 4
        assertEquals(Phase.REARMING, sm.currentPhase)
        val s = sm.step(input(0.0))               // quiet 5 -> ARMED
        assertEquals(Phase.ARMED, s.phase)
    }

    @Test
    fun `CONFIRM_N Ueberschreitungen erzeugen RISE_CONFIRMED mit eventId`() {
        val sm = m(); sm.arm()
        val c = sm.step(input(0.8))
        assertEquals(Phase.CANDIDATE, c.phase)
        assertNotNull(c.candidateId)
        assertNull(c.eventId)
        val r = sm.step(input(0.8))
        assertEquals(Phase.RISE_ACTIVE, r.phase)
        assertEquals(TransitionType.RISE_CONFIRMED, r.transition?.type)
        assertNotNull(r.eventId)
    }

    /** T33: hot-unarmed — ein beim Start heisses Signal wird nie CARRY. */
    @Test
    fun `T33 heisses Signal beim Start bleibt REARMING und erzeugt keinen Onset`() {
        val sm = m()
        repeat(20) { sm.step(input(1.5)) }
        assertEquals(Phase.REARMING, sm.currentPhase)
        assertNull(sm.currentEventId)
    }

    // ---- Carry (Holdout-Kernbefund) --------------------------------------

    /** T2/T22: nach Ablauf des Ereignisfensters folgt CARRY mit DERSELBEN
     *  eventId — kein zweiter Onset. */
    @Test
    fun `T2 CARRY setzt dasselbe Ereignis fort und feuert keinen Onset`() {
        val sm = m(); sm.arm()
        sm.step(input(0.8)); val rise = sm.step(input(0.8))
        val id = rise.eventId
        var last = rise
        repeat(35) { last = sm.step(input(0.8)) }
        assertEquals(Phase.CARRY, last.phase)
        assertEquals(id, last.eventId)
        assertNotEquals(TransitionType.RISE_CONFIRMED, last.transition?.type)
    }

    // ---- Gaps (kumulativ) ------------------------------------------------

    /** T24a: 1,5 < dt <= 3,0 bricht die Phase, aber nicht das R-Segment. */
    @Test
    fun `T24a 2-min-Luecke in CANDIDATE bricht die Phase ohne Segmentbruch`() {
        val sm = m(); sm.arm()
        val c = sm.step(input(0.8))
        assertEquals(Phase.CANDIDATE, c.phase)
        val g = sm.step(input(0.8, stepMin = 2.0))
        assertEquals(Phase.REARMING, g.phase)
        assertEquals(TransitionType.ABORT, g.transition?.type)
        assertTrue(ResetCause.CONTINUITY_BREAK in g.resetCauses)
        assertTrue(ResetCause.R_SEGMENT_BREAK !in g.resetCauses)
    }

    /** T25a: dt > 60 wirkt KUMULATIV — alle drei Bruchklassen zugleich. */
    @Test
    fun `T25a 61-min-Luecke ist Continuity- Segment- UND Reinit-Bruch`() {
        val sm = m(); sm.arm()
        val g = sm.step(input(0.0, stepMin = 61.0))
        assertTrue(ResetCause.CONTINUITY_BREAK in g.resetCauses)
        assertTrue(ResetCause.R_SEGMENT_BREAK in g.resetCauses)
        assertTrue(ResetCause.FULL_REINIT in g.resetCauses)
        assertEquals(Health.WARMUP, g.health)
    }

    // ---- Health-Gruende --------------------------------------------------

    /** T27: fehlende Activity -> DEGRADED, kein Fortschritt, keine Null. */
    @Test
    fun `T27 fehlende Activity blockiert jeden Phasenfortschritt`() {
        val sm = m(); sm.arm()
        val s = sm.step(input(0.8, act = ActivityValidity.MISSING))
        assertEquals(Health.DEGRADED, s.health)
        assertTrue(HealthReason.ACTIVITY_MISSING in s.healthReasons)
        assertEquals(Phase.REARMING, s.phase)
    }

    /** T28b: Activity aus der Zukunft ist kein gueltiges LOCF. */
    @Test
    fun `T28b Activity aus der Zukunft ergibt ACTIVITY_FUTURE`() {
        val sm = m()
        val s = sm.step(input(0.0, act = ActivityValidity.FUTURE))
        assertTrue(HealthReason.ACTIVITY_FUTURE in s.healthReasons)
        assertNotEquals(Health.READY, s.health)
    }

    /** T26/§2: R nicht berechenbar -> INSUFFICIENT_SIGNAL_HISTORY. */
    @Test
    fun `R null ergibt INSUFFICIENT_SIGNAL_HISTORY und keinen Fortschritt`() {
        val sm = m(); sm.arm()
        val s = sm.step(input(r = null))
        assertTrue(HealthReason.INSUFFICIENT_SIGNAL_HISTORY in s.healthReasons)
        assertEquals(Phase.REARMING, s.phase)
    }

    // ---- Safety ----------------------------------------------------------

    /** T42: LOW haelt bei 4 min ueber 80 und loest erst bei 5 min. */
    @Test
    fun `T42 LOW-Exit braucht volle 5 Minuten und laedt keinen Rearm vor`() {
        val sm = m(); sm.arm()
        val low = sm.step(input(0.0, bg = 70.0))
        assertTrue(SafetyReason.LOW in low.safetyReasons)
        var s = low
        repeat(4) { s = sm.step(input(0.0, bg = 85.0)) }
        assertTrue(SafetyReason.LOW in s.safetyReasons)
        s = sm.step(input(0.0, bg = 85.0))
        assertTrue(SafetyReason.LOW !in s.safetyReasons)
        assertEquals(Phase.REARMING, s.phase)
        assertEquals(0.0, s.quietAccumMin)
    }

    // ---- TURN ------------------------------------------------------------

    /** T21: TURN hat Vorrang vor dem regulaeren Ende bei R < THR. */
    @Test
    fun `T21 TURN gewinnt gegen thresholdLost im selben Sample`() {
        val sm = m(); sm.arm()
        // BG-Rampe in Schritten < INPUT_STEP_MGDL, sonst loest der Aufstieg selbst
        // einen INPUT_STEP_SUSPECTED aus und bricht die Phase (Fixture-Falle).
        sm.step(input(0.8, bg = 130.0)); sm.step(input(0.8, bg = 145.0))
        // Peak muss TURN_PEAK_AGE_MIN (3) alt sein, bevor der Abfall zaehlt.
        listOf(160.0, 172.0, 175.0, 175.0, 175.0).forEach { sm.step(input(0.8, bg = it)) }
        val s = sm.step(input(-0.5, bg = 160.0))        // Peak 175 alt genug, Abfall 15
        assertEquals(Phase.TURN, s.phase)
    }

    // ---- Idempotenz ------------------------------------------------------

    /** T12/T13: Duplikat und Out-of-order aendern den Zustand nicht. */
    @Test
    fun `T12 Duplikat und Rueckwaertssprung machen keinen Schritt`() {
        val sm = m(); sm.arm()
        val before = sm.step(input(0.0))
        val dup = sm.step(
            ObserverInput(before.let { t }, t + 900, 120.0, 120.0, 0.0, 0L, 0L, ActivityValidity.VALID, true)
        )
        assertTrue(!dup.accepted)
        assertEquals(before.phase, dup.phase)
        assertEquals(before.quietAccumMin, dup.quietAccumMin)
    }

    /** T34: bleibt der sourceTs stehen, entsteht nach STALE_MIN genau EIN Gate. */
    @Test
    fun `T34 stehender sourceTs erzeugt genau ein STALE-Gate`() {
        val sm = m(); sm.arm()
        sm.step(input(0.0))
        val stale = ObserverInput(t, t + 6 * 60_000L, 120.0, 120.0, 0.0, 0L, 0L, ActivityValidity.VALID, true)
        val s1 = sm.step(stale)
        assertTrue(HealthReason.STALE in s1.healthReasons)
        val s2 = sm.step(stale)
        assertNull(s2.transition)     // keine zweite Flanke
        val fresh = sm.step(input(0.0))
        assertTrue(HealthReason.STALE !in fresh.healthReasons)
    }

    // ---- Eingangssprung ---------------------------------------------------

    /** R64-F1: unklassifizierter Sprung setzt State/R zurueck, aber ohne
     *  Kalibrierflanke — und Q1 bleibt davon unberuehrt (Adapter-Sache). */
    @Test
    fun `Eingangssprung ohne Epochwechsel ergibt INPUT_STEP_SUSPECTED`() {
        val sm = m(); sm.arm()
        val s = sm.step(input(0.0, bg = 150.0))   // Sprung 120 -> 150
        assertTrue(ResetCause.INPUT_STEP_SUSPECTED in s.resetCauses)
        assertTrue(HealthReason.INPUT_STEP_RECOVERY in s.healthReasons)
    }

    /** Derselbe Sprung MIT Kalibrierflanke heisst CALIBRATION_RESET, nicht
     *  INPUT_STEP — sonst verfaelscht er die Ursachenstatistik. */
    @Test
    fun `Sprung mit Kalibrierflanke heisst CALIBRATION_RESET`() {
        val sm = m(); sm.arm()
        sm.step(input(0.0, calibEpoch = 0L))
        val s = sm.step(input(0.0, bg = 150.0, calibEpoch = t))
        assertTrue(ResetCause.CALIBRATION_RESET in s.resetCauses)
        assertTrue(ResetCause.INPUT_STEP_SUSPECTED !in s.resetCauses)
    }

    // ---- Determinismus ---------------------------------------------------

    @Test
    fun `gleiche Eingabefolge ergibt gleiche Phasenfolge`() {
        fun run(): List<Phase> {
            t = 1_700_000_000_000L
            val sm = ObserverStateMachine(sessionId = "fix")
            val out = mutableListOf<Phase>()
            repeat(8) { out += sm.step(input(0.0)).phase }
            repeat(4) { out += sm.step(input(0.9)).phase }
            return out
        }
        assertEquals(run(), run())
    }
}

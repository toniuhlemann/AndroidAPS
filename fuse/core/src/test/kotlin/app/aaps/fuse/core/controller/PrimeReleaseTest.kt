package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
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
    ) = PrimeRelease.Input(enabled, marker, armedTs, now, envelope, spent, minLower, guardFloor, isf, step)

    private fun state(iobTh: Double = 8.0, maxIob: Double = 8.0, netIob: Double = 1.0, maxSmb: Double = 0.3) =
        FuseController.State(
            health = Health.READY, safetyHold = false, phase = Phase.REARMING,
            netIobU = netIob, bolusIobU = netIob, basalIobU = 0.0,
            iobThU = iobTh, maxIobU = maxIob, targetMgdl = 98.0, isfMgdlPerU = 80.0,
            smbRatioCorrection = 0.15, smbRatioRise = 0.35,
            rSignedMgdlPerMin = 0.2, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
            pumpIncrementU = 0.05, maxSmbU = maxSmb, pumpBusy = false,
        )

    private fun base(block: FuseController.Block, smb: Double = 0.0) =
        FuseController.Decision(smb, FuseController.TbrAction.KEEP_CURRENT, block, 0.1, 105.0, 100.0, "x")

    // ---- Das Fenster ------------------------------------------------------

    /** Der Fruehstuecksfall 07.08.: Marker 08:50, BG 106 flach, Bahn ~100.
     *  Die Freigabe beginnt SOFORT - nicht erst bei der CGM-Regung 09:09. */
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
        assertEquals("CLEARANCE", PrimeRelease.plan(input(minLower = 88.0)).reason)
        assertTrue(PrimeRelease.plan(input(minLower = 90.0)).active)
    }

    /** Das Gate rechnet gegen den REST: spaet im Fenster, wenig Rest, darf
     *  die Bahn tiefer stehen. */
    @Test
    fun `das Clearance-Gate schrumpft mit der Resthuelle`() {
        // Rest 0,2 U -> Clearance 3,2 -> Bahn 75 reicht.
        val p = PrimeRelease.plan(input(now = t0 + 10 * 60_000L, spent = 1.0, minLower = 75.0))
        assertTrue(p.active)
    }

    // ---- lift: Sperren gewinnen ------------------------------------------

    @Test
    fun `keine echte Sperre wird angehoben`() {
        val plan = PrimeRelease.plan(input())
        for (b in listOf(
            FuseController.Block.GUARD_FLOOR, FuseController.Block.TAIL,
            FuseController.Block.IOB_TH_REACHED, FuseController.Block.MAX_IOB_REACHED,
            FuseController.Block.HEALTH_NOT_READY, FuseController.Block.SAFETY_HOLD,
            FuseController.Block.PUMP_BUSY, FuseController.Block.HORIZON_MISSING,
        )) {
            val d = PrimeRelease.lift(base(b), plan, state())
            assertEquals(0.0, d.smbU) { "angehoben trotz $b" }
            assertEquals(b, d.block)
        }
    }

    @Test
    fun `fehlender Bedarf wird auf den Floor gehoben`() {
        val d = PrimeRelease.lift(base(FuseController.Block.NO_DEMAND), PrimeRelease.plan(input()), state())
        assertEquals(0.05, d.smbU, 1e-12)
        assertEquals(FuseController.Block.NONE, d.block)
        assertEquals("primeRelease", d.bindingLimit)
    }

    @Test
    fun `eine groessere Evidenzdosis bleibt unangetastet`() {
        val d = PrimeRelease.lift(base(FuseController.Block.NONE, smb = 0.3), PrimeRelease.plan(input()), state())
        assertEquals(0.3, d.smbU, 0.0)
        assertEquals("x", d.bindingLimit)
    }

    /** Auch die Freigabe respektiert iobTH und maxIOB - sie ist kein
     *  Seitenkanal an den Deckeln vorbei. */
    @Test
    fun `die Deckel gelten auch fuer die Freigabe`() {
        val d = PrimeRelease.lift(
            base(FuseController.Block.NO_DEMAND),
            PrimeRelease.plan(input()),
            state(iobTh = 1.02, netIob = 1.0), // Spielraum 0,02 < ein Schritt
        )
        assertEquals(0.0, d.smbU, 0.0)
    }

    @Test
    fun `die Huelle selbst deckelt die Anhebung`() {
        // Rest 0,04 < Schritt -> plan inaktiv -> kein Lift.
        val p = PrimeRelease.plan(input(spent = 1.17))
        val d = PrimeRelease.lift(base(FuseController.Block.NO_DEMAND), p, state())
        assertEquals(0.0, d.smbU, 0.0)
    }
}

package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.EarlySupply
import app.aaps.fuse.core.controller.ExpectationAccount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * KI-221: der Storno des Erwartungskontos - ueber das Publikationsgate ([FuseLedgerAdapter.resolveReservation]) und
 * ueber den Nicht-Sende-Beweis im Folgezyklus ([FuseLedgerAdapter.revokeSettled]) - nimmt auch die Portion und die
 * Marke der Anfangsportion zurueck. Vorher blieben beide stehen: die Mahlzeit verlor ihre Anfangsportion, und eine nie
 * gesendete Portion belegte das Zeitraumbuch. Synthetische Werte.
 */
class ErwartungskontoStornoTest {

    private val t0 = 1_786_000_000_000L
    private val wirkung: (Double) -> Double = { t -> EarlySupply.orefActionFraction(t, 45.0, 540.0) }
    private val werte = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0)).values()
    private val params = requireNotNull(ExpectationAccount.Params.of(werte, 120, 0.5, 0.5, 90.0, 15, 0.3))
    private val einstellung = requireNotNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.0, 2.0, 10))

    private fun konto() = ExpectationAccount.armEarly(
        authId = t0, windowEndTs = t0 + 120 * 60_000L, params = params, settings = einstellung,
        switches = EarlySupply.Switches(true, true, true, true), startStable = true, nowTs = t0, bgMgdl = 100.0,
        isfMgdlPerU = 50.0, unitAction = wirkung,
    )

    private fun schritt(s: ExpectationAccount.State, min: Int, tief: Boolean = false) = ExpectationAccount.step(
        s,
        ExpectationAccount.Inputs(
            nowTs = t0 + min * 60_000L, ageMin = min.toDouble(), bgMgdl = 100.0, targetMgdl = 100.0, isfMgdlPerU = 50.0,
            iobTotalU = 0.0, activityUPerMin = 0.0, paused = false, stableNow = true,
            measuredLow = tief, lowLocksEarlySupply = tief,
            early = ExpectationAccount.EarlyInputs(iobU = 0.0, freeIobU = 0.0, existingActionU = { 0.0 }, unitAction = wirkung),
        ),
    )

    /** Ein Zyklus wie im Runner (ekBuchen): das Konto bucht, die Reservierung traegt die Anteile fuer den Storno. */
    private fun bucht(a: FuseLedgerAdapter, min: Int, mengeU: Double? = null): Double {
        val (vorher, e) = schritt(a.episodes.expectation ?: konto(), min)
        val menge = mengeU ?: e.restEffU
        val (neu, split) = ExpectationAccount.book(vorher, e, menge)
        a.episodes.expectation = neu
        a.episodes.pendingReservation = EpisodeBudgets.Reservation(
            computeTs = t0 + min * 60_000L, amountU = menge, prime = false, onset = false, mealTs = 0L,
        ).apply {
            ekAuthId = vorher.authId
            ekErwU = split.dErwU
            ekAtTs = e.atTs
            ekAnfang = !vorher.initialDone && neu.initialDone
        }
        return menge
    }

    @Test
    fun `entfernt das Gate die Anfangsportion ganz, ist sie wieder offen`() {
        val a = FuseLedgerAdapter()
        val menge = bucht(a, 0)
        assertTrue(a.episodes.expectation!!.initialDone)
        a.resolveReservation(t0, publishedU = 0.0, proposalId = "z0")
        val k = a.episodes.expectation!!
        assertFalse(k.initialDone)
        assertEquals(0.0, k.bookedU, 1e-12)
        assertEquals(menge, k.cancelledU, 1e-12)
        assertTrue(schritt(k, 1).second.early!!.initial)
    }

    @Test
    fun `teilweise publiziert ist sie gegeben - bis der Beweis zeigt, dass auch der Rest nie hinausging`() {
        val a = FuseLedgerAdapter()
        val menge = bucht(a, 0)
        a.resolveReservation(t0, publishedU = menge / 2, proposalId = "z0")
        assertTrue(a.episodes.expectation!!.initialDone)
        // Folgezyklus: der Nicht-Sende-Beweis fuer die publizierte Haelfte.
        a.revokeSettled("z0")
        val k = a.episodes.expectation!!
        assertFalse(k.initialDone)
        assertEquals(0.0, k.bookedU, 1e-12)
    }

    @Test
    fun `kommt der Beweis nach einem gemessenen Tief, bleibt die Anfangsportion zu`() {
        val a = FuseLedgerAdapter()
        val menge = bucht(a, 0)
        a.resolveReservation(t0, publishedU = menge, proposalId = "z0")
        // Folgezyklus: erst das gemessene Tief, dann der Beweis.
        a.episodes.expectation = schritt(a.episodes.expectation!!, 1, tief = true).first
        a.revokeSettled("z0")
        val k = a.episodes.expectation!!
        assertTrue(k.initialDone)
        assertEquals(0.0, k.bookedU, 1e-12) { "die Menge geht trotzdem zurueck - nur die Marke bleibt" }
    }

    @Test
    fun `eine nie gesendete Portion verlaesst ueber den Beweis das Zeitraumbuch`() {
        val a = FuseLedgerAdapter()
        val anfang = bucht(a, 0)
        a.resolveReservation(t0, publishedU = anfang, proposalId = "z0")
        bucht(a, 1, mengeU = 1.0)
        a.resolveReservation(t0 + 60_000L, publishedU = 1.0, proposalId = "z1")
        assertEquals(1.0, a.episodes.expectation!!.portions.sumOf { it.amountU }, 1e-12)
        a.revokeSettled("z1")
        val k = a.episodes.expectation!!
        assertTrue(k.portions.isEmpty())
        assertTrue(k.initialDone) { "die Anfangsportion floss - nur die Portion ging nie hinaus" }
        assertEquals(anfang, k.bookedU, 1e-12)
    }
}

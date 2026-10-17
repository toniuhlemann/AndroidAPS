package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SignalTimeGateTest {

    private val now = 1_786_200_000_000L

    @Test
    fun `vergangenheit und kleiner jitter passieren`() {
        assertNull(SignalTimeGate.futureReason(now - 5 * 60_000L, now))
        assertNull(SignalTimeGate.futureReason(now, now))
        assertNull(SignalTimeGate.futureReason(now + 59_000L, now))
        assertNull(SignalTimeGate.futureReason(now + 60_000L, now))
    }

    @Test
    fun `zukunft jenseits der toleranz blockt mit benanntem grund`() {
        val r = SignalTimeGate.futureReason(now + 61_000L, now)
        assertNotNull(r)
        assertEquals(true, r!!.startsWith("FUTURE_GLUCOSE"))
        // Der Ladefenster-Kappen-Fall (+2 min) faellt IMMER durch das Tor -
        // genau der Kopf-klebt-an-der-Kappe-Mechanismus der R95-Gegenpruefung.
        assertNotNull(SignalTimeGate.futureReason(now + 2 * 60_000L, now))
        // Codex' woertliches Szenario (+10 min) ebenso.
        assertNotNull(SignalTimeGate.futureReason(now + 10 * 60_000L, now))
    }

    @Test
    fun `blockierte weit-zukunft erholt sich wenn die zeitachse wieder stimmt`() {
        val ahead = now + 10 * 60_000L
        assertNotNull(SignalTimeGate.futureReason(ahead, now))
        // 10 min spaeter ist derselbe Stempel nicht mehr in der Zukunft.
        assertNull(SignalTimeGate.futureReason(ahead, now + 10 * 60_000L))
    }
}

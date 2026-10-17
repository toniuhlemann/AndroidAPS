package app.aaps.core.interfaces.aps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Fork (FUSE KI-171): the list of SMB requests that provably never reached the pump.
 *
 * The APS plugin matches an entry to its own request by reference, possibly runs later, so the list
 * must keep the identity of every entry, keep the newest, and stay bounded.
 */
class LastRunSmbNotSentTest {

    private fun entry(request: Any, atMs: Long) = Loop.SmbNotSent(request = request, requestedU = 0.1, atMs = atMs, reason = "test")

    @Test
    fun startsEmpty() {
        assertTrue(Loop.LastRun().smbNotSent.isEmpty())
    }

    @Test
    fun keepsTheIdentityOfTheRequest() {
        val lastRun = Loop.LastRun()
        val request = Any()

        lastRun.recordSmbNotSent(entry(request, 1L))

        assertSame(request, lastRun.smbNotSent.single().request)
    }

    @Test
    fun keepsTheNewestEntriesAndStaysBounded() {
        val lastRun = Loop.LastRun()
        val total = Loop.LastRun.SMB_NOT_SENT_MAX + 5

        for (i in 1..total) lastRun.recordSmbNotSent(entry(Any(), i.toLong()))

        assertEquals(Loop.LastRun.SMB_NOT_SENT_MAX, lastRun.smbNotSent.size)
        assertEquals(6L, lastRun.smbNotSent.first().atMs)
        assertEquals(total.toLong(), lastRun.smbNotSent.last().atMs)
    }
}

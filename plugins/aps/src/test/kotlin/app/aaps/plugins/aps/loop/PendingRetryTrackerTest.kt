package app.aaps.plugins.aps.loop

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 0055 v3 state-machine tests (Codex review round 2) — the pending-retry transitions, pure JVM,
 * no WorkManager/Robolectric. Covers the five cases Codex listed, expressed on the tracker that
 * backs Loop.markPendingRetry / clearPendingRetryThrough / pendingRetryBgTs.
 */
class PendingRetryTrackerTest {

    private val bgA = 1_000_000L
    private val bgB = 1_060_000L   // one minute after A

    @Test fun `fresh tracker has nothing pending`() {
        assertThat(PendingRetryTracker().value()).isEqualTo(0L)
    }

    @Test fun `busy-skip marks exactly one pending, claim clears it (busy-skip then retry)`() {
        val t = PendingRetryTracker()
        t.mark(bgA)                       // InvokeLoopWorker pump-busy branch
        assertThat(t.value()).isEqualTo(bgA)
        t.clearThrough(bgA)               // later claim of bgA
        assertThat(t.value()).isEqualTo(0L)
    }

    @Test fun `newer BG concurrent with claim is not lost (mark B after A, clear through A keeps B)`() {
        val t = PendingRetryTracker()
        t.mark(bgA)
        t.mark(bgB)                       // a newer busy-skip arrived
        assertThat(t.value()).isEqualTo(bgB)   // monotonic: newest wins
        t.clearThrough(bgA)               // an OLDER worker claims only up through A
        assertThat(t.value()).isEqualTo(bgB)   // B survives — not lost
    }

    @Test fun `already-looped BG produces no second pending (idempotent clear)`() {
        val t = PendingRetryTracker()
        t.mark(bgA)
        t.clearThrough(bgA)
        t.clearThrough(bgA)               // repeated claim / already looped
        assertThat(t.value()).isEqualTo(0L)   // no resurrection, no double
    }

    @Test fun `retry that itself doses does not strand the next skip (claim A clears, mark B pends)`() {
        val t = PendingRetryTracker()
        t.mark(bgA)
        t.clearThrough(bgA)               // retry loop claimed A and dosed
        assertThat(t.value()).isEqualTo(0L)
        t.mark(bgB)                       // a BG skipped DURING that dose's command
        assertThat(t.value()).isEqualTo(bgB)   // B legitimately pends → next drain retries it
    }

    @Test fun `read-command-only path never marks (no unnecessary retry)`() {
        val t = PendingRetryTracker()
        // A ReadStatus/LoadEvents cycle performs a command but InvokeLoopWorker never hits the
        // busy branch for it → mark() is simply not called. Nothing pends.
        assertThat(t.value()).isEqualTo(0L)
    }

    @Test fun `mark ignores non-positive and clearThrough handles empty`() {
        val t = PendingRetryTracker()
        t.mark(0L); t.mark(-5L)
        assertThat(t.value()).isEqualTo(0L)
        t.clearThrough(bgA)               // clear when nothing pending
        assertThat(t.value()).isEqualTo(0L)
    }
}

package app.aaps.plugins.aps.loop

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 0055 v3: atomic, MONOTONIC holder for the "BG skipped by pump-busy, awaiting a prompt retry"
 * timestamp. Codex review round 2: @Volatile alone gives visibility but not atomic check-then-set,
 * so an OLDER InvokeLoopWorker run could clear a pending ts a NEWER run just set. AtomicLong with
 * compare-and-update closes that:
 *  - [mark] only ever RAISES the pending ts (max) → an older mark can't lower a newer one.
 *  - [clearThrough] clears only if the pending ts is <= the claimed ts → an older claim can't
 *    erase a newer pending; a newer claim clears everything up to itself.
 * Pure JVM (no Android deps) → unit-testable directly (see PendingRetryTrackerTest).
 */
class PendingRetryTracker {
    private val pending = AtomicLong(0L)

    /** Record a busy-skipped BG. Monotonic: keeps the newest (largest) pending ts. */
    fun mark(bgTs: Long) {
        if (bgTs <= 0L) return
        pending.updateAndGet { current -> max(current, bgTs) }
    }

    /** Clear the pending marker IFF it points at [claimedTs] or older (i.e. it has been handled).
     *  A pending set for a NEWER BG survives. No-op when nothing is pending. */
    fun clearThrough(claimedTs: Long) {
        pending.updateAndGet { current -> if (current in 1L..claimedTs) 0L else current }
    }

    /** Current pending ts, or 0 when nothing awaits a retry. */
    fun value(): Long = pending.get()
}

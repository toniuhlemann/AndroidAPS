package app.aaps.core.interfaces.rx.events

/**
 * 0055: fired by QueueWorker when the pump command queue goes idle after having PERFORMED at
 * least one command AND a BG is actually pending a retry (Loop.pendingRetryBgTs > 0, set only in
 * InvokeLoopWorker's pump-busy branch). Prompts ONE loop re-evaluation so a BG the pump-busy
 * guard skipped (patch 0035) is retried at once instead of waiting for the next natural trigger
 * (~45s onset latency at boost start, Codex perf audit 2026-07-17). The retry recalc reloads BG
 * data (bgDataReload=true) so a concurrently-arrived newer BG can never be displaced by a stale
 * cache, and ends in the SAME guarded InvokeLoopWorker — an already-looped BG is a no-op.
 *
 * @param bgTs the skipped BG timestamp (informational / logging; the actual claim uses the
 *             freshest DB BG after reload).
 */
class EventQueueIdleRetryLoop(val bgTs: Long) : Event()

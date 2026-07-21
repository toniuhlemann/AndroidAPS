package app.aaps.plugins.aps.iobaction

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import app.aaps.core.interfaces.aps.AutoIsfValueLeaseInvalidator
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Capability-Matrix A1 — DER ValueLeaseCoordinator (Spec v1.1 §2 + R9-F1/F2/F3 + R10-F2):
 * RAM-Wahrheit des Wert-Overlays. Implementiert BEIDE Core-Vertraege:
 * [EffectiveAutoIsfSettingsProvider] (read-only Snapshot, eine Quelle fuer APS/Trigger/
 * Export/Status) und [AutoIsfValueLeaseInvalidator] (Writer-Port fuer ActionSetIobTH).
 *
 * Architektur-Invarianten:
 *  - APS-Hotpath liest NUR RAM (AtomicReference + Basis-Preference) — nie Room/IO/Locks
 *    mit Wartezeit (der Coordinator-Lock wird ausschliesslich von Command-/Writer-Pfaden
 *    gehalten, die selbst kein APS sind; snapshot() nimmt ihn NICHT).
 *  - Room-Commit ist der historische Linearization Point (R10-F2): executeArmedSet
 *    publiziert NACH dem Commit aktiv ODER revoked — ein ACK existiert erst nach Publish.
 *  - Gate-/TTL-/Generation-Verletzungen werden am READ-Pfad irreversibel GELATCHT
 *    (R10-F1): einmal unsicher gesehen = diese Lease ist dauerhaft widerrufen; nur ein
 *    NEUER SET (neues CAS) aktiviert wieder.
 *  - Doppelte TTL-Frist (R9-F3): wall (Room/Status) UND elapsedRealtime (RAM, monoton);
 *    now == deadline ist bereits abgelaufen; Arithmetik add/multiplyExact.
 *
 * A1-Scope: nur IOBTH; im OFF-/Forced-VO-Pilot entsteht NIE eine Lease → snapshot()
 * liefert IMMER die Basis (OFF-Diff-Garantie, R11-Grenzen).
 */
@Singleton
class AutoIsfValueLeaseCoordinator @Inject constructor(
    private val preferences: Preferences,
    private val context: Context,
) : EffectiveAutoIsfSettingsProvider, AutoIsfValueLeaseInvalidator {

    data class Gates(val channelEnabled: Boolean, val iobthCapabilityEnabled: Boolean, val forcedValidateOnly: Boolean) {
        val unsafe: Boolean get() = !channelEnabled || !iobthCapabilityEnabled || forcedValidateOnly
        val unsafeReason: AutoIsfOverrideState get() = if (forcedValidateOnly && channelEnabled && iobthCapabilityEnabled) AutoIsfOverrideState.VO_FORCED else AutoIsfOverrideState.DISABLED
    }

    @VisibleForTesting internal data class PublishedLease(
        val leaseId: String,
        val leaseVersion: Long,
        val setPercent: Int,
        val basePercent: Int,
        val baseGeneration: Long,
        val gateGeneration: Long,
        val createdAtWallMs: Long,
        val expiresAtWallMs: Long,
        val expiresAtElapsedMs: Long,
        val revokedReason: AutoIsfOverrideState? = null,   // != null ⇒ dauerhaft widerrufen
    )

    // ---- injizierbare Umwelt (Tests ueberschreiben; Produktion = echte Quellen) ----
    @VisibleForTesting internal var basePercentReader: () -> Int = { preferences.get(IntKey.ApsAutoIsfIobThPercent) }
    @VisibleForTesting internal var gatesReader: () -> Gates = {
        val sp = context.getSharedPreferences("local_command_channel", Context.MODE_PRIVATE)
        Gates(sp.getBoolean("channel_enabled", false), sp.getBoolean("iobth_capability_enabled", false), sp.getBoolean("forced_validate_only", false))
    }
    @VisibleForTesting internal var wallClock: () -> Long = { System.currentTimeMillis() }
    @VisibleForTesting internal var elapsedClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    private val lock = ReentrantLock()
    @VisibleForTesting internal val published = AtomicReference<PublishedLease?>(null)
    @VisibleForTesting internal val baseGeneration = AtomicLong(0)

    /** Externe Basis-Writes (SP-Listener-Fangnetz, R9-F5): Generation invalidiert auch wertgleiche Writes. */
    fun onExternalBaseWrite() {
        baseGeneration.incrementAndGet()
    }

    // ---- READ-Pfad (APS/Trigger/Export/Status) — lockfrei, latcht irreversibel ----

    override fun snapshot(): EffectiveAutoIsfSettingsProvider.Snapshot {
        val base = basePercentReader()
        val p = published.get() ?: return none(base)
        p.revokedReason?.let { return revokedSnapshot(base, p, it) }

        val gates = gatesReader()
        if (gates.unsafe) return latchAndReturn(base, p, gates.unsafeReason)
        val wallNow = wallClock()
        val elapsedNow = elapsedClock()
        if (wallNow < p.createdAtWallMs - CLOCK_ANOMALY_TOLERANCE_MS) return latchAndReturn(base, p, AutoIsfOverrideState.CLOCK_ANOMALY)
        if (wallNow >= p.expiresAtWallMs || elapsedNow >= p.expiresAtElapsedMs) return latchAndReturn(base, p, AutoIsfOverrideState.EXPIRED)
        if (p.baseGeneration != baseGeneration.get() || p.basePercent != base) return latchAndReturn(base, p, AutoIsfOverrideState.FOREIGN_MODIFIED)

        return EffectiveAutoIsfSettingsProvider.Snapshot(
            iobThPercentBase = base, iobThPercentEffective = p.setPercent,
            overrideState = AutoIsfOverrideState.ACTIVE,
            leaseId = p.leaseId, leaseVersion = p.leaseVersion, expiresAtWallMs = p.expiresAtWallMs,
        )
    }

    override fun isIobThLeaseActive(): Boolean = snapshot().overrideState == AutoIsfOverrideState.ACTIVE

    /** R10-F1: Latch per CAS — schnelles Re-enable/Uhr-Vorwaerts belebt NIE wieder. */
    private fun latchAndReturn(base: Int, p: PublishedLease, reason: AutoIsfOverrideState): EffectiveAutoIsfSettingsProvider.Snapshot {
        published.compareAndSet(p, p.copy(revokedReason = reason))
        pendingRoomTerminal.compareAndSet(null, reason.name)
        return revokedSnapshot(base, p, reason)
    }

    private fun none(base: Int) = EffectiveAutoIsfSettingsProvider.Snapshot(base, base, AutoIsfOverrideState.NONE, null, null, null)

    private fun revokedSnapshot(base: Int, p: PublishedLease, reason: AutoIsfOverrideState) =
        EffectiveAutoIsfSettingsProvider.Snapshot(base, base, reason, p.leaseId, p.leaseVersion, p.expiresAtWallMs)

    /** Nachlaufende Room-Terminalisierung (R10-F2 §5: rein metadatisch, nie therapeutisch):
     *  der Service konsumiert das beim naechsten Kommando/Status ausserhalb des APS-Pfads. */
    @VisibleForTesting internal val pendingRoomTerminal = AtomicReference<String?>(null)
    fun consumePendingRoomTerminal(): String? = pendingRoomTerminal.getAndSet(null)

    // ---- WRITER-Port (ActionSetIobTH, R10-G1) ----

    override fun invalidateBeforeExternalWrite(capability: AutoIsfCapability, reason: String): Boolean = lock.withLock {
        // Generation IMMER bumpen (faengt wertgleiche Schutz-Writes, R9-F5) …
        baseGeneration.incrementAndGet()
        // … und eine aktive Lease sofort im RAM widerrufen (Schutz gewinnt vor dem
        // naechsten APS-Snapshot, beweisbar statt eventual-consistent).
        val p = published.get()
        if (p != null && p.revokedReason == null) {
            published.set(p.copy(revokedReason = AutoIsfOverrideState.FOREIGN_MODIFIED))
            pendingRoomTerminal.compareAndSet(null, AutoIsfOverrideState.FOREIGN_MODIFIED.name)
        }
        true   // RAM-Widerruf kann nicht fehlschlagen; Room-Terminalisierung laeuft nach.
    }

    // ---- COMMAND-Pfad (ARMED SET/CLEAR — in A1 live unerreichbar, aber vollstaendig) ----

    data class BaseCapture(val basePercent: Int, val baseGeneration: Long, val gateGeneration: Long, val wallNow: Long, val expiresAtWallMs: Long)
    data class RoomSetResult(
        val outcome: String, val errorCode: String?, val replayed: Boolean,
        val resultJson: String?, val leaseId: String?, val leaseVersion: Long?,
    )
    data class ArmedResult(val room: RoomSetResult, val currentLeaseState: AutoIsfOverrideState)

    /**
     * R10-F2-Ablauf: Basis-Capture → Room-Commit (txn) → ZWEITE Basis-Pruefung → Publish
     * aktiv ODER revoked → erst dann Ergebnis (= ACK-Grundlage). Historisches APPLIED
     * bleibt APPLIED; currentLeaseState traegt die Gegenwart (R11-P1).
     */
    fun executeArmedSet(setPercent: Int, ttlMin: Int, txn: (BaseCapture) -> RoomSetResult): ArmedResult = lock.withLock {
        val ttlMs = Math.multiplyExact(ttlMin.toLong(), 60_000L)
        val wallNow = wallClock()
        val elapsedDeadline = Math.addExact(elapsedClock(), ttlMs)
        val capture = BaseCapture(
            basePercent = basePercentReader(), baseGeneration = baseGeneration.get(),
            gateGeneration = 0L, wallNow = wallNow, expiresAtWallMs = Math.addExact(wallNow, ttlMs),
        )
        val room = txn(capture)
        if (room.outcome != "APPLIED" || room.replayed) {
            // Reject/VO/Replay publizieren nichts — Gegenwart ist der aktuelle Snapshot.
            return ArmedResult(room, snapshot().overrideState)
        }
        // Zweite Pruefung (R9-F2 §3): Fremd-Write zwischen Capture und Publish?
        val base2 = basePercentReader()
        val gen2 = baseGeneration.get()
        val lease = PublishedLease(
            leaseId = room.leaseId!!, leaseVersion = room.leaseVersion!!, setPercent = setPercent,
            basePercent = capture.basePercent, baseGeneration = capture.baseGeneration, gateGeneration = 0L,
            createdAtWallMs = wallNow, expiresAtWallMs = capture.expiresAtWallMs, expiresAtElapsedMs = elapsedDeadline,
        )
        return if (base2 != capture.basePercent || gen2 != capture.baseGeneration) {
            // Post-Commit-Fremdaenderung (R10-F2 §4): ATOMAR revoked publizieren; historisch
            // bleibt das Kommando APPLIED, Room-Terminalisierung laeuft nach.
            published.set(lease.copy(revokedReason = AutoIsfOverrideState.FOREIGN_MODIFIED))
            pendingRoomTerminal.set(AutoIsfOverrideState.FOREIGN_MODIFIED.name)
            ArmedResult(room, AutoIsfOverrideState.FOREIGN_MODIFIED)
        } else {
            published.set(lease)
            ArmedResult(room, AutoIsfOverrideState.ACTIVE)
        }
    }

    /** CLEAR (eigene Lease, CAS in der txn): erfolgreiches APPLIED publiziert NONE. */
    fun executeArmedClear(txn: () -> RoomSetResult): ArmedResult = lock.withLock {
        val room = txn()
        if (room.outcome == "APPLIED" && !room.replayed) {
            published.set(null)
            return ArmedResult(room, AutoIsfOverrideState.NONE)
        }
        return ArmedResult(room, snapshot().overrideState)
    }

    /** v1.1: AAPS-Prozessneustart beendet aktive Value-Leases BEVOR APS sie je nutzt.
     *  RAM startet ohnehin leer; der Aufrufer terminalisiert die Room-Zeile nachlaufend. */
    fun onProcessRestart(): String = AutoIsfOverrideState.PROCESS_RESTART.name

    private companion object {

        const val CLOCK_ANOMALY_TOLERANCE_MS = 5L * 60_000
    }
}

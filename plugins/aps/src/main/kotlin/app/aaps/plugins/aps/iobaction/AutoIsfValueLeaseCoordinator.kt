package app.aaps.plugins.aps.iobaction

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import app.aaps.core.interfaces.aps.AutoIsfValueLeaseInvalidator
import app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Capability-Matrix A1 — DER ValueLeaseCoordinator (Spec v1.1 §2 + R9-F1/F2/F3 + R10-F2 +
 * R12-F1/F2/F5): RAM-Wahrheit des Wert-Overlays. Implementiert BEIDE Core-Vertraege.
 *
 * Architektur-Invarianten:
 *  - APS-Hotpath liest NUR RAM — snapshot() nimmt NIE den Coordinator-Lock; die Bewertung
 *    laeuft in einer CAS-Schleife ueber FRISCHE Reads (R12-F1: ein verlorener CAS bewertet
 *    neu statt einen veralteten Zustand zu melden).
 *  - Room-Commit ist der historische Linearization Point (R10-F2).
 *  - Gate-/TTL-/Generation-Verletzungen werden am READ-Pfad irreversibel GELATCHT; die
 *    ECHTE monotone gateGeneration (R12-F2) faengt auch AUS→AN ZWISCHEN zwei Snapshots:
 *    jede unsichere Transition bumpt die Generation, jede Lease traegt ihre
 *    Erstellungs-Generation — Abweichung = dauerhaft widerrufen.
 *  - Nachlaufende Room-Terminalisierung ist IDENTITAETSGEBUNDEN (R12-F1): Queue von
 *    {leaseId, leaseVersion, reason}; ein Nachfolger kann nie getroffen werden.
 *  - Doppelte TTL-Frist wall+elapsed, now==deadline abgelaufen, add/multiplyExact (R9-F3).
 *
 * A1-Scope: nur IOBTH; im OFF-/Forced-VO-Pilot entsteht NIE eine Lease → snapshot()
 * liefert IMMER die Basis (OFF-Diff-Garantie).
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

    @VisibleForTesting internal data class GateObservation(
        val gates: Gates?, val generation: Long,
        /** R13-F4: Grund der LETZTEN unsicheren Transition — Terminalgruende bleiben ehrlich,
         *  auch wenn kein Snapshot den unsicheren Moment selbst gesehen hat. */
        val lastUnsafeReason: AutoIsfOverrideState? = null,
    )

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

    /** R12-F1: identitaetsgebundener Terminalisierungs-Auftrag — nie nur ein Reason-String. */
    data class PendingTerminal(val capability: String, val leaseId: String, val leaseVersion: Long, val reason: String)

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
    private val gateObservation = AtomicReference(GateObservation(null, 0L))
    @VisibleForTesting internal val pendingTerminals = ConcurrentLinkedQueue<PendingTerminal>()

    /** Externe Basis-Writes (SP-Listener-Fangnetz, R9-F5): Generation invalidiert auch wertgleiche Writes. */
    fun onExternalBaseWrite() {
        baseGeneration.incrementAndGet()
    }

    /** Fangnetz-Pfad (SP-Listener/sonstige Writer): beobachtet die Gates nachtraeglich. */
    fun onGateWrite() {
        observeGates()
    }

    /**
     * R13-F1: SYNCHRONER Gate-Writer-Pfad — nimmt den Coordinator-Lock VOR dem SP-Write.
     * Damit sind Gate-Write und Command-Publish echt linearisiert: waehrend executeArmedSet
     * den Lock haelt, kann kein Gate-Write dazwischen; ein Gate-Write VOR dem Command
     * revoked die Lease, bevor der Command je publiziert. Der Aufrufer (Settings-Switch)
     * schreibt den SP-Wert erst NACH Rueckkehr dieser Methode.
     */
    fun beforeGateWrite(
        newChannel: Boolean? = null, newIobth: Boolean? = null, newForcedVo: Boolean? = null,
        // R14-F2: der eigentliche SP-Write laeuft als Callback UNTER DEMSELBEN Lock — zwischen
        // Bump/Revoke und sichtbarem neuen Gate-Wert existiert damit KEIN Fenster mehr, in dem
        // ein neuer SET den Lock bekommen, die alten sicheren Gates lesen und vollstaendig
        // publizieren koennte (executeArmedSet haelt denselben Lock).
        write: () -> Unit = {},
    ): Unit = lock.withLock {
        val cur = gatesReader()
        val next = Gates(
            newChannel ?: cur.channelEnabled,
            newIobth ?: cur.iobthCapabilityEnabled,
            newForcedVo ?: cur.forcedValidateOnly,
        )
        val becameUnsafe = (cur.channelEnabled && !next.channelEnabled) ||
            (cur.iobthCapabilityEnabled && !next.iobthCapabilityEnabled) ||
            (!cur.forcedValidateOnly && next.forcedValidateOnly)
        if (becameUnsafe) {
            val reason = if (!cur.forcedValidateOnly && next.forcedValidateOnly && next.channelEnabled && next.iobthCapabilityEnabled)
                AutoIsfOverrideState.VO_FORCED else AutoIsfOverrideState.DISABLED
            // Generation-Bump + Reason VOR dem eigentlichen SP-Write (gates=null zwingt die
            // naechste Beobachtung zum frischen Read ohne Doppel-Bump).
            while (true) {
                val curObs = gateObservation.get()
                if (gateObservation.compareAndSet(curObs, GateObservation(null, Math.addExact(curObs.generation, 1L), reason))) break
            }
            val p = published.get()
            if (p != null && p.revokedReason == null) {
                published.set(p.copy(revokedReason = reason))
                pendingTerminals.add(PendingTerminal("IOBTH", p.leaseId, p.leaseVersion, reason.name))
            }
        }
        // Auch SICHERE Transitionen schreiben unter dem Lock (ein AUS->AN zwischen Snapshots
        // faengt weiterhin observeGates via Transitions-Bump).
        write()
    }

    /**
     * R12-F2: monotone Gate-Generation. Jede UNSICHERE Transition (channel AN→AUS,
     * iobth-Capability AN→AUS, forcedVO AUS→AN) bumpt die Generation — eine Lease mit
     * aelterer Erstellungs-Generation ist damit dauerhaft widerrufen, auch wenn kein
     * Snapshot den AUS-Moment je gesehen hat.
     */
    @VisibleForTesting internal fun observeGates(): GateObservation {
        while (true) {
            val cur = gateObservation.get()
            val gates = gatesReader()
            if (cur.gates == gates) return cur
            val becameUnsafe = cur.gates != null && (
                (cur.gates.channelEnabled && !gates.channelEnabled) ||
                    (cur.gates.iobthCapabilityEnabled && !gates.iobthCapabilityEnabled) ||
                    (!cur.gates.forcedValidateOnly && gates.forcedValidateOnly)
                )
            // R13-F4: VO-Puls und Schalter-AUS bekommen ihren ECHTEN Grund.
            val reason = when {
                !becameUnsafe -> cur.lastUnsafeReason
                cur.gates != null && !cur.gates.forcedValidateOnly && gates.forcedValidateOnly &&
                    gates.channelEnabled && gates.iobthCapabilityEnabled -> AutoIsfOverrideState.VO_FORCED
                else -> AutoIsfOverrideState.DISABLED
            }
            val next = GateObservation(gates, if (becameUnsafe) Math.addExact(cur.generation, 1L) else cur.generation, reason)
            if (gateObservation.compareAndSet(cur, next)) return next
        }
    }

    // ---- READ-Pfad (APS/Trigger/Export/Status) — lockfrei, latcht irreversibel ----

    override fun snapshot(): EffectiveAutoIsfSettingsProvider.Snapshot {
        while (true) {
            val base = basePercentReader()
            val obs = observeGates()
            val p = published.get() ?: return none(base)
            p.revokedReason?.let { return revokedSnapshot(base, p, it) }

            val wallNow = wallClock()
            val elapsedNow = elapsedClock()
            val reason: AutoIsfOverrideState? = when {
                obs.gates!!.unsafe -> obs.gates.unsafeReason
                // R12-F2: Gate war ZWISCHEN den Snapshots unsicher (AUS→AN) — Generation verraet es.
                p.gateGeneration != obs.generation -> obs.lastUnsafeReason ?: AutoIsfOverrideState.DISABLED
                wallNow < p.createdAtWallMs - CLOCK_ANOMALY_TOLERANCE_MS -> AutoIsfOverrideState.CLOCK_ANOMALY
                wallNow >= p.expiresAtWallMs || elapsedNow >= p.expiresAtElapsedMs -> AutoIsfOverrideState.EXPIRED
                p.baseGeneration != baseGeneration.get() || p.basePercent != base -> AutoIsfOverrideState.FOREIGN_MODIFIED
                else -> null
            }
            if (reason == null) return EffectiveAutoIsfSettingsProvider.Snapshot(
                iobThPercentBase = base, iobThPercentEffective = p.setPercent,
                overrideState = AutoIsfOverrideState.ACTIVE,
                leaseId = p.leaseId, leaseVersion = p.leaseVersion, expiresAtWallMs = p.expiresAtWallMs,
            )
            // R12-F1: Pending NUR nach ERFOLGREICHEM CAS fuer GENAU diese Lease; verlorener
            // CAS (Command hat parallel ersetzt) ⇒ Schleife bewertet den FRISCHEN Zustand.
            if (published.compareAndSet(p, p.copy(revokedReason = reason))) {
                pendingTerminals.add(PendingTerminal("IOBTH", p.leaseId, p.leaseVersion, reason.name))
                return revokedSnapshot(base, p, reason)
            }
        }
    }

    override fun isIobThLeaseActive(): Boolean = snapshot().overrideState == AutoIsfOverrideState.ACTIVE

    private fun none(base: Int) = EffectiveAutoIsfSettingsProvider.Snapshot(base, base, AutoIsfOverrideState.NONE, null, null, null)

    private fun revokedSnapshot(base: Int, p: PublishedLease, reason: AutoIsfOverrideState) =
        EffectiveAutoIsfSettingsProvider.Snapshot(base, base, reason, p.leaseId, p.leaseVersion, p.expiresAtWallMs)

    /** Nachlaufende, IDENTITAETSGEBUNDENE Room-Terminalisierung (R10-F2 §5 + R12-F1):
     *  der Service draint die Queue ausserhalb des APS-Pfads; jeder Auftrag trifft nur
     *  exakt seine Lease (CAS in der Transaktion), nie einen Nachfolger. */
    fun drainPendingTerminals(): List<PendingTerminal> {
        val out = mutableListOf<PendingTerminal>()
        while (true) out.add(pendingTerminals.poll() ?: return out)
    }

    /** R13-F2 peek/ack: Auftrag bleibt in der Queue, bis die Room-Terminalisierung
     *  ERFOLGREICH war (bzw. beweisbar stale) — ein transienter DB-Fehler verliert nichts. */
    fun peekPendingTerminal(): PendingTerminal? = pendingTerminals.peek()
    fun ackPendingTerminal(pt: PendingTerminal) {
        if (pendingTerminals.peek() == pt) pendingTerminals.poll()
    }

    // ---- WRITER-Port (ActionSetIobTH, R10-G1) ----

    override fun invalidateBeforeExternalWrite(capability: AutoIsfCapability, reason: String): Boolean = lock.withLock {
        // Generation IMMER bumpen (faengt wertgleiche Schutz-Writes, R9-F5) …
        baseGeneration.incrementAndGet()
        // … und eine aktive Lease sofort im RAM widerrufen (Schutz gewinnt vor dem
        // naechsten APS-Snapshot, beweisbar statt eventual-consistent).
        val p = published.get()
        if (p != null && p.revokedReason == null) {
            published.set(p.copy(revokedReason = AutoIsfOverrideState.FOREIGN_MODIFIED))
            pendingTerminals.add(PendingTerminal("IOBTH", p.leaseId, p.leaseVersion, AutoIsfOverrideState.FOREIGN_MODIFIED.name))
        }
        true   // RAM-Widerruf kann nicht fehlschlagen; Room-Terminalisierung laeuft nach.
    }

    // ---- COMMAND-Pfad (ARMED SET/CLEAR — in A1 live unerreichbar, aber vollstaendig) ----

    data class BaseCapture(val basePercent: Int, val baseGeneration: Long, val gateGeneration: Long, val wallNow: Long, val expiresAtWallMs: Long)
    data class RoomSetResult(
        val outcome: String, val errorCode: String?, val replayed: Boolean,
        val resultJson: String?, val leaseId: String?, val leaseVersion: Long?,
        /** R12-F5: die TATSAECHLICH persistierten Basis-/Generationswerte (bei OWNED aus der
         *  ersetzten Lease GEERBT) — der RAM-Snapshot publiziert exakt diese. */
        val basePercentUsed: Int? = null,
        val baseGenerationUsed: Long? = null,
        val gateGenerationUsed: Long? = null,
    )
    data class ArmedResult(val room: RoomSetResult, val currentLeaseState: AutoIsfOverrideState)

    /**
     * R10-F2 + R12-F2/F5-Ablauf: Gates+Capture → Room-Commit (txn) → ZWEITE Pruefung
     * (Basis UND Gates) → Publish aktiv ODER revoked → erst dann Ergebnis. Historisches
     * APPLIED bleibt APPLIED; currentLeaseState traegt die Gegenwart (R11-P1). Nach
     * zwischenzeitlicher Gate-Unsicherheit wird NIEMALS ACTIVE publiziert.
     */
    fun executeArmedSet(setPercent: Int, ttlMin: Int, txn: (BaseCapture) -> RoomSetResult): ArmedResult = lock.withLock {
        val preObs = observeGates()
        if (preObs.gates == null || preObs.gates.unsafe) {
            // Belt zum Service-Gate: ohne sicheres Gate keine Transaktion (kein Outcome —
            // identisch zur Service-Gate-Ablehnung, idempotenzfrei wiederholbar).
            return ArmedResult(
                RoomSetResult("REJECTED", LocalCommandProtocol.E_CAPABILITY_DISABLED, false, null, null, null),
                snapshot().overrideState,
            )
        }
        val ttlMs = Math.multiplyExact(ttlMin.toLong(), 60_000L)
        val wallNow = wallClock()
        val elapsedDeadline = Math.addExact(elapsedClock(), ttlMs)
        val capture = BaseCapture(
            basePercent = basePercentReader(), baseGeneration = baseGeneration.get(),
            gateGeneration = preObs.generation, wallNow = wallNow, expiresAtWallMs = Math.addExact(wallNow, ttlMs),
        )
        val room = txn(capture)
        if (room.outcome != "APPLIED" || room.replayed) {
            return ArmedResult(room, snapshot().overrideState)
        }
        val lease = PublishedLease(
            leaseId = room.leaseId!!, leaseVersion = room.leaseVersion!!, setPercent = setPercent,
            basePercent = room.basePercentUsed ?: capture.basePercent,
            baseGeneration = room.baseGenerationUsed ?: capture.baseGeneration,
            gateGeneration = room.gateGenerationUsed ?: capture.gateGeneration,
            createdAtWallMs = wallNow, expiresAtWallMs = capture.expiresAtWallMs, expiresAtElapsedMs = elapsedDeadline,
        )
        // ZWEITE Pruefung (R9-F2 §3 + R12-F2): Basis UND Gates gegen die persistierten Werte.
        val postObs = observeGates()
        val base2 = basePercentReader()
        val gen2 = baseGeneration.get()
        val revokeReason: AutoIsfOverrideState? = when {
            postObs.gates == null || postObs.gates.unsafe -> postObs.gates?.unsafeReason ?: AutoIsfOverrideState.DISABLED
            postObs.generation != lease.gateGeneration -> AutoIsfOverrideState.DISABLED
            base2 != lease.basePercent || gen2 != lease.baseGeneration -> AutoIsfOverrideState.FOREIGN_MODIFIED
            else -> null
        }
        return if (revokeReason != null) {
            // Post-Commit-Konflikt (R10-F2 §4): ATOMAR revoked publizieren; historisch bleibt
            // das Kommando APPLIED, Room-Terminalisierung laeuft identitaetsgebunden nach.
            published.set(lease.copy(revokedReason = revokeReason))
            pendingTerminals.add(PendingTerminal("IOBTH", lease.leaseId, lease.leaseVersion, revokeReason.name))
            ArmedResult(room, revokeReason)
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

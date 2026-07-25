package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.automation.AutomationStateInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Capability AUTOSTATE — Lease-Koordinator fuer AAPS-Automation-States.
 *
 * BEWUSST SCHLANKER ALS [AutoIsfValueLeaseCoordinator], und zwar aus einem sachlichen Grund:
 * der iobTH-Koordinator bewacht eine PREFERENCE, die der APS-Hotpath jeden Zyklus liest. Er
 * braucht dafuer SP-Generationen, Gate-Generationen und eine sperrfreie CAS-Leseschleife.
 * Ein Automation-State ist nichts davon — er wird ueber [AutomationStateInterface] gelesen und
 * geschrieben, nicht ueber Preferences, und kein Dosier-Hotpath haengt an ihm. Diese Maschinerie
 * hier nachzubauen waere Zeremonie ohne Schutzwirkung.
 *
 * Was dagegen sehr wohl noetig ist:
 *
 *  - TTL mit DOPPELTER Frist (Wall + elapsedRealtime). Stellt jemand die Uhr, laeuft die Lease
 *    trotzdem aus; now == Frist gilt als abgelaufen.
 *  - FREMDSCHREIBER gewinnen. AAPS-Automationen schreiben dieselben States (MEAL_ACTIVE_RESET
 *    und Verwandte). Steht beim Auslaufen oder beim CLEAR nicht mehr unser Wert, wird die Basis
 *    NICHT zurueckgeschrieben — wer nach uns geschrieben hat, besitzt den State. Sonst wuerde
 *    eine auslaufende Lease einen Schutz-Reset rueckgaengig machen.
 *  - KEIN automatisches Nachsetzen. Waehrend die Lease laeuft, wird der Wert nicht zyklisch
 *    wieder durchgedrueckt. Wer nachsetzen will, muss ein neues Kommando schicken — sichtbar in
 *    den Entscheidungen des Viewers statt unsichtbar im Fork. Automatisches Nachsetzen wuerde
 *    auch die Schutz-Resets ueberstimmen (Dip unter 85 ist einer davon).
 *  - Room bleibt der historische Linearization Point: der State wird erst NACH einem
 *    APPLIED-Commit geschrieben. Scheitert die Transaktion, bleibt der State unangetastet.
 */
@Singleton
class AutoStateLeaseCoordinator @Inject constructor(
    private val automationState: AutomationStateInterface,
) {

    /** Was beim SET als Herkunft festgehalten wurde. known=false: der State existierte nicht. */
    data class BaseCapture(
        val stateName: String,
        val baseKnown: Boolean,
        val baseValue: String?,
        val wallNow: Long,
        val expiresAtWallMs: Long,
    )

    data class RoomSetResult(
        val outcome: String,
        val errorCode: String?,
        val replayed: Boolean,
        val resultJson: String?,
        val leaseId: String?,
        val leaseVersion: Long?,
    )

    data class ArmedResult(val room: RoomSetResult, val currentLeaseState: String)

    data class PendingTerminal(val capability: String, val leaseId: String, val leaseVersion: Long, val reason: String)

    private data class Published(
        val leaseId: String,
        val leaseVersion: Long,
        val stateName: String,
        val baseKnown: Boolean,
        val baseValue: String?,
        val setValue: String,
        val expiresAtWallMs: Long,
        val expiresAtElapsedMs: Long,
    )

    private val lock = ReentrantLock()
    private val published = AtomicReference<Published?>(null)
    private val pendingTerminals = ConcurrentLinkedQueue<PendingTerminal>()

    companion object {
        const val CAPABILITY = "AUTOSTATE"
        const val STATE_NONE = "NONE"
        const val STATE_ACTIVE = "ACTIVE"
        const val REASON_EXPIRED = "EXPIRED"
        const val REASON_CLEARED = "CLEARED"
        const val REASON_FOREIGN = "FOREIGN_MODIFIED"
        const val REASON_PROCESS_RESTART = "PROCESS_RESTART"
    }

    /** Existenz + erlaubte Werte kommen vom State SELBST — hier liegt die inhaltliche Grenze. */
    fun isValueAllowed(stateName: String, stateValue: String): Boolean = runCatching {
        automationState.hasStateValues(stateName) && stateValue in automationState.getStateValues(stateName)
    }.getOrDefault(false)

    fun captureBase(stateName: String, ttlMin: Int, nowWall: Long): BaseCapture {
        val snap = runCatching { automationState.getStateSnapshot(stateName) }.getOrNull()
        return BaseCapture(
            stateName = stateName,
            baseKnown = snap?.known == true,
            baseValue = snap?.value,
            wallNow = nowWall,
            expiresAtWallMs = Math.addExact(nowWall, Math.multiplyExact(ttlMin.toLong(), 60_000L)),
        )
    }

    /**
     * SET: Basis erfassen, Room committen, erst dann den State schreiben und die Lease
     * veroeffentlichen. Eine bereits laufende Lease wird vorher als REPLACED terminalisiert
     * (die Room-Transaktion fuehrt dafuer selbst Buch).
     */
    fun executeArmedSet(
        stateName: String,
        stateValue: String,
        ttlMin: Int,
        nowWall: Long,
        nowElapsed: Long,
        txn: (BaseCapture) -> RoomSetResult,
    ): ArmedResult = lock.withLock {
        enforceLocked(nowWall, nowElapsed)
        val capture = captureBase(stateName, ttlMin, nowWall)
        val room = txn(capture)
        if (room.outcome == "APPLIED" && !room.replayed && room.leaseId != null && room.leaseVersion != null) {
            val written = runCatching { automationState.setState(stateName, stateValue) }.isSuccess
            if (written) {
                published.set(
                    Published(
                        leaseId = room.leaseId, leaseVersion = room.leaseVersion, stateName = stateName,
                        baseKnown = capture.baseKnown, baseValue = capture.baseValue, setValue = stateValue,
                        expiresAtWallMs = capture.expiresAtWallMs,
                        expiresAtElapsedMs = Math.addExact(nowElapsed, Math.multiplyExact(ttlMin.toLong(), 60_000L)),
                    )
                )
            } else {
                // Der State liess sich nicht schreiben (existiert nicht / Wert ungueltig). Die
                // Room-Zeile ist bereits committet, also SOFORT terminalisieren statt eine
                // Lease zu fuehren, die nichts haelt.
                pendingTerminals.add(PendingTerminal(CAPABILITY, room.leaseId, room.leaseVersion, REASON_FOREIGN))
            }
        }
        ArmedResult(room, currentState())
    }

    /** CLEAR: Basis nur zurueckschreiben, wenn noch UNSER Wert steht. */
    fun executeArmedClear(nowWall: Long, nowElapsed: Long, txn: () -> RoomSetResult): ArmedResult = lock.withLock {
        enforceLocked(nowWall, nowElapsed)
        val p = published.get()
        val room = txn()
        if (room.outcome == "APPLIED" && !room.replayed && p != null) {
            restoreIfStillOurs(p)
            published.set(null)
        }
        ArmedResult(room, currentState())
    }

    /**
     * Zyklischer Wachposten: Ablauf und Fremdschreiber erkennen. Muss regelmaessig laufen —
     * ohne Aufruf laeuft die Frist nicht ab (dieselbe Eigenschaft wie beim iobTH-Snapshot).
     */
    fun enforce(nowWall: Long, nowElapsed: Long) = lock.withLock { enforceLocked(nowWall, nowElapsed) }

    private fun enforceLocked(nowWall: Long, nowElapsed: Long) {
        val p = published.get() ?: return
        val expired = nowWall >= p.expiresAtWallMs || nowElapsed >= p.expiresAtElapsedMs
        val current = runCatching { automationState.getStateSnapshot(p.stateName) }.getOrNull()
        val stillOurs = current?.known == true && current.value == p.setValue
        when {
            !stillOurs -> {
                // Fremdschreiber hat uebernommen: Lease stirbt, Basis bleibt UNANGETASTET.
                pendingTerminals.add(PendingTerminal(CAPABILITY, p.leaseId, p.leaseVersion, REASON_FOREIGN))
                published.set(null)
            }
            expired -> {
                restoreIfStillOurs(p)
                pendingTerminals.add(PendingTerminal(CAPABILITY, p.leaseId, p.leaseVersion, REASON_EXPIRED))
                published.set(null)
            }
        }
    }

    /** Nur wiederherstellen, wenn unser Wert noch steht UND es eine Basis gab. */
    private fun restoreIfStillOurs(p: Published) {
        val current = runCatching { automationState.getStateSnapshot(p.stateName) }.getOrNull()
        if (current?.known != true || current.value != p.setValue) return
        if (!p.baseKnown || p.baseValue == null) return
        runCatching { automationState.setState(p.stateName, p.baseValue) }
    }

    fun currentState(): String = if (published.get() == null) STATE_NONE else STATE_ACTIVE

    /** Status fuer GET_SERVICE_STATUS; ohne Seiteneffekt. */
    fun statusMap(): Map<String, Any> {
        val p = published.get()
        return buildMap {
            put("serverAutoStatePolicyHash", LocalCommandAutoStatePolicy.hash())
            put("autoStateLeaseState", if (p == null) STATE_NONE else STATE_ACTIVE)
            p?.let {
                put("autoStateLeaseId", it.leaseId)
                put("autoStateLeaseVersion", it.leaseVersion)
                put("autoStateLeaseExpiresAt", it.expiresAtWallMs)
                put("autoStateName", it.stateName)
                put("autoStateValue", it.setValue)
            }
        }
    }

    fun peekPendingTerminal(): PendingTerminal? = pendingTerminals.peek()

    fun ackPendingTerminal(pt: PendingTerminal) {
        if (pendingTerminals.peek() === pt) pendingTerminals.poll()
    }

    /** Prozessneustart: die RAM-Lease ist weg — der State bleibt stehen, wie ihn AAPS fand. */
    fun onProcessRestart(): String = REASON_PROCESS_RESTART
}

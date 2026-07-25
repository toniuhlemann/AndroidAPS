package app.aaps.plugins.aps.iobaction

import app.aaps.core.interfaces.aps.AutoIsfCapability
import app.aaps.core.interfaces.aps.AutoIsfOverrideState
import app.aaps.core.interfaces.automation.AutomationStateInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capability AUTOSTATE — Lease-Koordinator fuer AAPS-Automation-States.
 *
 * SCHLANKER als [AutoIsfValueLeaseCoordinator], aber NICHT ohne dessen Sicherheitsnetze — das
 * war der Fehler der ersten Fassung. Richtig bleibt: ein Automation-State ist keine Preference
 * im APS-Hotpath, also braucht er keine SP-Basis-Generation und keine sperrfreie CAS-Leseschleife.
 * FALSCH war der Schluss, damit ganz ohne Gates und ohne Herzschlag auszukommen (Review 25.07.,
 * Befunde 3 und 4): Master-Schalter, Capability-Schalter und Validate-only beendeten eine
 * laufende Lease nicht, und die TTL lief nur, solange der Viewer selbst anrief. Gates kommen
 * jetzt aus DERSELBEN Beobachtung wie beim Wert-Overlay, und der APS-Lauf treibt enforce() von
 * innen.
 *
 * Was dagegen sehr wohl noetig ist:
 *
 *  - TTL mit DOPPELTER Frist (Wall + elapsedRealtime). Stellt jemand die Uhr, laeuft die Lease
 *    trotzdem aus; now == Frist gilt als abgelaufen.
 *  - FREMDSCHREIBER gewinnen. AAPS-Automationen schreiben dieselben States (MEAL_ACTIVE_RESET
 *    und Verwandte). Steht beim Auslaufen oder beim CLEAR nicht mehr unser Wert, wird die Basis
 *    NICHT zurueckgeschrieben — wer nach uns geschrieben hat, besitzt den State. Sonst wuerde
 *    eine auslaufende Lease einen Schutz-Reset rueckgaengig machen.
 *    AUSNAHME, ehrlich benannt: erkannt wird nur ueber den WERT. Schreibt eine Automation
 *    exakt den Wert, den wir ohnehin halten, bleibt sie unsichtbar und wir kassieren beim
 *    Ablauf ihren Write. Kostet einen Automations-Write pro Lease; warum kein Generations-
 *    Netz dagegen steht, erklaert der Block weiter unten.
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
    /**
     * Befund 3/4 des Reviews vom 25.07.: dieser Koordinator hatte KEINE Gate-Logik. Master-
     * Schalter, Capability-Schalter und Validate-only beendeten eine laufende Lease nicht — und
     * ein abgeschalteter Kanal blockierte zusaetzlich das CLEAR, der State blieb also bis zu
     * 12h eingefroren OHNE Weg zurueck. Die Gates kommen jetzt aus DERSELBEN Beobachtung wie
     * beim Wert-Overlay; zwei getrennte Gate-Leser wuerden auseinanderlaufen.
     */
    private val gateSource: AutoIsfValueLeaseCoordinator,
) {

    /** Was beim SET als Herkunft festgehalten wurde. known=false: der State existierte nicht. */
    data class BaseCapture(
        val stateName: String,
        val baseKnown: Boolean,
        val baseValue: String?,
        val wallNow: Long,
        val expiresAtWallMs: Long,
        /** Befund 1: Room verlangt beim ERSTEN SET nicht-nullable Generationen. */
        val gateGeneration: Long,
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
        val gateGeneration: Long,
    )

    private val published = AtomicReference<Published?>(null)

    /**
     * KEIN GENERATIONS-NETZ FUER AUTOSTATE — bewusst, nach Runde 4.
     *
     * Der Versuch (F4) zaehlte jede Aenderung am persistierten Automation-State-Blob und
     * wertete eine Abweichung als Fremdschreiber. Das war in drei Punkten falsch:
     *
     *  1. Der SP-Listener feuert fuer UNSEREN eigenen Write asynchron auf dem Main-Thread,
     *     waehrend executeArmedSet auf einem Binder-Thread bereits publiziert hat. Jede Lease
     *     stufte sich damit Sekunden spaeter selbst als FOREIGN ein — und FOREIGN stellt die
     *     Basis absichtlich NICHT zurueck. Der Overlay blieb dauerhaft stehen, TTL, CLEAR,
     *     Gate-AUS und Prozessneustart griffen alle nicht mehr.
     *  2. Der Zaehler war blob-weit. Jede AAPS-Automation, die IRGENDEINEN State schreibt,
     *     haette unsere Lease getoetet — im Normalbetrieb also staendig.
     *  3. Er loeste den Fall, fuer den er gebaut war, gar nicht: Android benachrichtigt keinen
     *     Listener, wenn sich der Wert nicht aendert. Genau der WERTGLEICHE Fremd-Write blieb
     *     unsichtbar.
     *
     * Ich hatte den Rest-Race als "sichere Richtung" bewertet. Das war falsch: die ganze
     * Sicherheitsargumentation dieser Capability haengt an "TTL-begrenzt", und genau die fiel
     * damit weg. Der reine Wertvergleich unten ist unvollstaendig — ein wertgleicher
     * Fremd-Write bleibt unerkannt — aber er laesst TTL und Rueckstellung intakt, und das ist
     * die deutlich kleinere Luecke.
     *
     * Ein tragfaehiges Netz muesste PRO STATE arbeiten (Blob im Listener parsen) und den
     * eigenen Write quittieren, BEVOR publiziert wird. Bis dahin: keins.
     */
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

    /**
     * Befund 2: bei einer VERLAENGERUNG darf die Basis NICHT neu erfasst werden — sonst wird der
     * bereits ueberlagerte Wert zur neuen "Basis" und der State kehrt nie zum Original zurueck.
     * Bei 30-min-TTL ist Verlaengern der Normalfall, nicht der Randfall.
     */
    fun captureBase(stateName: String, ttlMin: Int, nowWall: Long): BaseCapture {
        val running = published.get()?.takeIf { it.stateName == stateName }
        val snap = if (running == null) runCatching { automationState.getStateSnapshot(stateName) }.getOrNull() else null
        return BaseCapture(
            stateName = stateName,
            baseKnown = running?.baseKnown ?: (snap?.known == true),
            baseValue = if (running != null) running.baseValue else snap?.value,
            wallNow = nowWall,
            expiresAtWallMs = Math.addExact(nowWall, Math.multiplyExact(ttlMin.toLong(), 60_000L)),
            gateGeneration = gateSource.gateSnapshot(AutoIsfCapability.AUTOSTATE).third,
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
    ): ArmedResult = gateSource.withGateLock {
        enforceLocked(nowWall, nowElapsed)
        val (unsafe, _, _) = gateSource.gateSnapshot(AutoIsfCapability.AUTOSTATE)
        if (unsafe) return@withGateLock ArmedResult(
            RoomSetResult("REJECTED", LocalCommandProtocol.E_CAPABILITY_DISABLED, false, null, null, null),
            currentState(),
        )
        // Befund 2: eine laufende Lease wird NICHT auf einen anderen State umgebogen — sonst
        // bliebe der alte State ueberlagert, ohne dass ihn noch irgendetwas haelt.
        published.get()?.let { running ->
            if (running.stateName != stateName) return@withGateLock ArmedResult(
                RoomSetResult("REJECTED", "REJECTED_STATE_CONFLICT", false, null, null, null),
                currentState(),
            )
        }
        val capture = captureBase(stateName, ttlMin, nowWall)
        // Runde 3 / F5: hat der State noch NIE einen Wert getragen, gibt es keinen Rueckweg —
        // AutomationStateInterface kennt kein "Wert entfernen". Eine Lease darauf wuerde den
        // Wert PERMANENT festschreiben, den weder TTL noch CLEAR noch Prozess-Neustart
        // zuruecknehmen koennten. Also gar nicht erst anfangen.
        if (!capture.baseKnown || capture.baseValue == null) return@withGateLock ArmedResult(
            RoomSetResult("REJECTED", "REJECTED_POLICY", false, null, null, null),
            currentState(),
        )
        val room = txn(capture)
        if (room.outcome == "APPLIED" && !room.replayed && room.leaseId != null && room.leaseVersion != null) {
            // ZWEITE PRUEFUNG nach dem Commit (Re-Review B3) — der iobTH-Zwilling macht sie seit
            // R10-F2, hier fehlte sie. Die Room-Transaktion laeuft ueber I/O; in dieser Zeit kann
            // der Nutzer den Kanal- oder Capability-Schalter umlegen. Ohne diese Pruefung wuerde
            // der Fork den Automation-State DANACH trotzdem schreiben und bis zum naechsten
            // Herzschlag halten. Historisch bleibt das Kommando APPLIED, publiziert wird nichts.
            val (unsafeNow, unsafeNowReason, generationNow) = gateSource.gateSnapshot(AutoIsfCapability.AUTOSTATE)
            if (unsafeNow || generationNow != capture.gateGeneration) {
                // Runde 3 / F1: hier fehlte das Aufraeumen. Bei einer VERLAENGERUNG hat Room die
                // alte Zeile bereits als REPLACED terminalisiert — bleibt die alte Published im
                // RAM stehen, meldet currentState() ACTIVE fuer eine Lease, die es nicht mehr
                // gibt, und die naechste Verlaengerung laeuft in REJECTED_STATE_CONFLICT.
                // Dieselbe Fehlerklasse, die zwoelf Zeilen tiefer schon behoben ist.
                published.get()?.let { restoreIfStillOurs(it) }
                published.set(null)
                pendingTerminals.add(
                    PendingTerminal(
                        CAPABILITY, room.leaseId, room.leaseVersion,
                        (unsafeNowReason ?: AutoIsfOverrideState.DISABLED).name,
                    )
                )
                return@withGateLock ArmedResult(room, currentState())
            }
            val written = runCatching { automationState.setState(stateName, stateValue) }.isSuccess
            if (written) {
                published.set(
                    Published(
                        leaseId = room.leaseId, leaseVersion = room.leaseVersion, stateName = stateName,
                        baseKnown = capture.baseKnown, baseValue = capture.baseValue, setValue = stateValue,
                        expiresAtWallMs = capture.expiresAtWallMs,
                        expiresAtElapsedMs = Math.addExact(nowElapsed, Math.multiplyExact(ttlMin.toLong(), 60_000L)),
                        gateGeneration = capture.gateGeneration,
                    )
                )
            } else {
                // Der State liess sich nicht schreiben (existiert nicht / Wert ungueltig). Die
                // Room-Zeile ist bereits committet, also SOFORT terminalisieren statt eine
                // Lease zu fuehren, die nichts haelt.
                pendingTerminals.add(PendingTerminal(CAPABILITY, room.leaseId, room.leaseVersion, REASON_FOREIGN))
                // Re-Review B5: bei einer VERLAENGERUNG stand hier noch die alte Published mit
                // alter leaseId, waehrend Room sie bereits als REPLACED terminalisiert hatte —
                // currentState() haette dauerhaft ACTIVE fuer eine Lease gemeldet, die es nicht
                // mehr gibt, und restoreIfStillOurs haette gegen den alten Wert verglichen.
                published.set(null)
            }
        }
        ArmedResult(room, currentState())
    }

    /** CLEAR: Basis nur zurueckschreiben, wenn noch UNSER Wert steht. */
    fun executeArmedClear(nowWall: Long, nowElapsed: Long, txn: () -> RoomSetResult): ArmedResult = gateSource.withGateLock {
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
    fun enforce(nowWall: Long, nowElapsed: Long) = gateSource.withGateLock { enforceLocked(nowWall, nowElapsed) }

    private fun enforceLocked(nowWall: Long, nowElapsed: Long) {
        val p = published.get() ?: return
        val (unsafe, unsafeReason, generation) = gateSource.gateSnapshot(AutoIsfCapability.AUTOSTATE)
        val expired = nowWall >= p.expiresAtWallMs || nowElapsed >= p.expiresAtElapsedMs
        // Reihenfolge zaehlt (Re-Review B4): Gate und Frist haengen NICHT davon ab, ob der State
        // gerade lesbar ist. Frueher stand der Snapshot-Read ueber dem when und ein
        // Lesefehler hat damit auch Gate- und TTL-Pruefung unterdrueckt — eine Lease mit
        // abgeschaltetem Gate haette ueberlebt, solange der State nicht lesbar war.
        if (unsafe || p.gateGeneration != generation) {
            restoreIfStillOurs(p)
            pendingTerminals.add(
                PendingTerminal(CAPABILITY, p.leaseId, p.leaseVersion, (unsafeReason ?: AutoIsfOverrideState.DISABLED).name)
            )
            published.set(null)
            return
        }
        if (expired) {
            restoreIfStillOurs(p)
            pendingTerminals.add(PendingTerminal(CAPABILITY, p.leaseId, p.leaseVersion, REASON_EXPIRED))
            published.set(null)
            return
        }
        // NUR das Fremdschreiber-Urteil braucht den Wert. Ist er nicht lesbar, wissen wir nichts —
        // dann nicht widerrufen, sondern beim naechsten Durchlauf erneut versuchen.
        val current = runCatching { automationState.getStateSnapshot(p.stateName) }.getOrNull() ?: return
        if (!(current.known && current.value == p.setValue)) {
            // Fremdschreiber hat uebernommen: Lease stirbt, Basis bleibt UNANGETASTET.
            pendingTerminals.add(PendingTerminal(CAPABILITY, p.leaseId, p.leaseVersion, REASON_FOREIGN))
            published.set(null)
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

    /**
     * Status fuer GET_SERVICE_STATUS. Reiner RAM-Read — der Aufrufer ruft allerdings vorher
     * enforce(), das sehr wohl schreiben kann (Re-Review B6: der Kommentar behauptete frueher
     * Seiteneffektfreiheit fuer den GESAMTEN Statusabruf).
     */
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

    /**
     * Prozessneustart: die RAM-Lease ist weg, die Room-Zeile traegt die Basis aber noch.
     *
     * Re-Review B7: frueher wurde nur die Zeile terminalisiert und der ueberlagerte Wert blieb
     * DAUERHAFT stehen — ein OOM-Kill oder ein Update mitten in einer Lease konnte also z.B.
     * MEAL_ACTIVE=true fuer immer festschreiben, und das steuert Automationen. Zurueckgestellt
     * wird nur, wenn noch UNSER Wert steht; hat inzwischen ein Automat geschrieben, gehoert ihm
     * der State (dieselbe Regel wie beim Ablauf).
     */
    fun restoreAfterProcessRestart(stateName: String, ourValue: String, baseValue: String?): Boolean {
        if (baseValue == null) return false
        val current = runCatching { automationState.getStateSnapshot(stateName) }.getOrNull() ?: return false
        if (!current.known || current.value != ourValue) return false
        return runCatching { automationState.setState(stateName, baseValue) }.isSuccess
    }

    fun onProcessRestart(): String = REASON_PROCESS_RESTART
}

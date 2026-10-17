package app.aaps.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * LocalCommandChannel — Persistenz (Spec v1.2 B1/B2, R5-Pilot-Bau, 21.07.2026).
 * BEIDE Tabellen leben bewusst in DERSELBEN AppDatabase wie TemporaryTarget (R2-B1:
 * Atomizitaet von Ownership-Pruefung, TT-Mutation und Outcome ist nur innerhalb EINER
 * Room-Transaktion moeglich). Lokal-only: KEIN TraceableDBEntry, kein NS-Sync, keine
 * InterfaceIDs. Tabellennamen-Konstanten liegen HIER statt in TableNames.kt —
 * merge-freundlich (eigene Datei, kein Upstream-Konflikt).
 */

const val TABLE_LOCAL_COMMAND_OUTCOME = "localCommandOutcome"
const val TABLE_LOCAL_COMMAND_OWNERSHIP = "localCommandOwnership"
const val TABLE_LOCAL_COMMAND_VALUE_LEASE = "localCommandValueLease"

/**
 * Terminales Ergebnis je requestId (persistente Idempotenz, R1-F4/R2-A2): Retry derselben
 * ID+Hash liefert exakt dieses Ergebnis zurueck (replayed=true), gleiche ID mit anderem
 * Hash wird abgelehnt. VALIDATED ist terminal. Aufbewahrung 24h (Prune ueber createdAt).
 */
@Entity(
    tableName = TABLE_LOCAL_COMMAND_OUTCOME,
    indices = [Index("requestId", unique = true), Index("createdAt")]
)
data class LocalCommandOutcome(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var requestId: String,
    var requestHash: String,      // SHA-256 des kanonischen Strings (R2-A2: ID an Payload gebunden)
    var cmd: String,
    var outcome: String,          // APPLIED | VALIDATED | REJECTED
    var errorCode: String? = null,
    var appliedAt: Long? = null,
    var ttDbId: Long? = null,
    var ttEntityVersion: Int? = null,
    var validateOnly: Boolean,
    var createdAt: Long,
    var retainUntil: Long,
    /** R10-F7: kanonisches, servererzeugtes Resultat (Value-Kommandos) — Replay liefert
     *  EXAKT dieses historische Resultat, unabhaengig vom spaeteren Lease-Zustand. */
    var resultJson: String? = null,
)

/**
 * Datensatzgenaue TT-Ownership (R2-B2/R3-F3): der Kanal darf NUR das TT ersetzen/beenden,
 * das exakt diesem Datensatz entspricht (DB-Id + Entity-Version + Therapie-Fingerprint).
 * Aktiv = terminatedAt IS NULL (die Transaktion erzwingt max. eine aktive Zeile).
 * NS-ID-Echo-Ausnahme (R2-B3): identischer Therapieinhalt bei anderer Version wird beim
 * naechsten Kommando atomar als Version-Fortschreibung uebernommen; jede inhaltliche
 * Fremdaenderung terminiert die Ownership (FOREIGN_MODIFIED).
 */
@Entity(
    tableName = TABLE_LOCAL_COMMAND_OWNERSHIP,
    indices = [Index("terminatedAt"), Index("ttDbId")]
)
data class LocalCommandOwnership(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var requestId: String,        // der SET-Request, der dieses TT erzeugt hat
    var ttDbId: Long,
    var ttEntityVersion: Int,
    var ttTimestamp: Long,        // Therapie-Fingerprint ab hier:
    var lowTarget: Double,
    var highTarget: Double,
    var durationMs: Long,
    var reasonKey: String,        // Protokoll-ReasonKey (nicht das TT.Reason-Enum)
    var ownerPolicyHash: String,  // Policy-Hash der ERSTELLUNG (R4 §2: CANCEL prueft diesen)
    var createdAt: Long,
    var terminatedAt: Long? = null,
    var terminalReason: String? = null,   // REPLACED | CANCELLED | FOREIGN_MODIFIED | TT_GONE | EXPIRED
)

/**
 * Capability-Wert-Lease (Capability-Matrix A1, Spec v1.1 + R9/R10): zeitbegrenztes
 * Overlay UEBER einer Basis-Preference — die Basis wird vom Kanal NIE beschrieben.
 * TTL/Kill-Switch/Fremdaenderung wirken am READ-Pfad (Resolver); diese Zeile ist die
 * persistente Wahrheit dazu.
 *
 * DB-Invarianten (R10-F4, Fresh-DB-sicher via normale Room-Indizes statt Partial-Index):
 *  - activeSlot = 1 solange aktiv, NULL nach Terminalisierung → unique(capability,
 *    activeSlot) erlaubt beliebig viele terminale Zeilen, aber nur EINE aktive je
 *    Capability (SQLite: NULLs kollidieren nicht).
 *  - unique(capability, leaseVersion): ein alter CAS-Token kann nie wieder passen.
 * leaseVersion ist monoton je Capability UEBER Terminalisierungen hinweg (max+1 in der
 * Transaktion). gateGeneration = Gate-Stand bei Erstellung (R9-F1: Abweichung = dauerhaft
 * unwirksam; Schalter-Reaktivierung belebt nie).
 */
@Entity(
    tableName = TABLE_LOCAL_COMMAND_VALUE_LEASE,
    indices = [
        Index(value = ["capability", "activeSlot"], unique = true),
        Index(value = ["capability", "leaseVersion"], unique = true),
        Index("terminatedAt"),
    ]
)
data class LocalCommandValueLease(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var capability: String,            // "IOBTH" (A1); WEIGHTS/SMBR erst B1/B2
    var leaseId: String,               // ownerRequestId des erzeugenden SET
    var leaseVersion: Long,            // monoton je Capability, nie wiederverwendet
    var ownerPolicyHash: String,       // Policy-Hash der ERSTELLUNG (CLEAR prueft DIESEN)
    var basePayload: String,           // kanonischer Basis-Payload (skalierte Int) beim ersten SET
    var baseGeneration: Long,          // SP-Generation beim Basis-Capture (R9-F5)
    var setPayload: String,            // kanonischer Override-Payload
    var gateGeneration: Long,          // Gate-Stand bei Erstellung (R9-F1)
    var createdAt: Long,
    var expiresAtWallMs: Long,         // Wall-Frist (Room/Status); elapsedRealtime-Frist lebt NUR im RAM
    var activeSlot: Int? = 1,          // 1 = aktiv, NULL = terminal (Unique-Traeger)
    var terminatedAt: Long? = null,
    var terminalReason: String? = null, // EXPIRED | CLEARED | REPLACED | FOREIGN_MODIFIED | DISABLED | VO_FORCED | CLOCK_ANOMALY | PROCESS_RESTART
)

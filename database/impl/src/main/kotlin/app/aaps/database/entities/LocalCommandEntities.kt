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

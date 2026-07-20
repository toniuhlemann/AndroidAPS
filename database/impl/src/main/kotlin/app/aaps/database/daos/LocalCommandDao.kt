package app.aaps.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.aaps.database.entities.LocalCommandOutcome
import app.aaps.database.entities.LocalCommandOwnership
import app.aaps.database.entities.TABLE_LOCAL_COMMAND_OUTCOME
import app.aaps.database.entities.TABLE_LOCAL_COMMAND_OWNERSHIP

/**
 * LocalCommandChannel-DAO (lokal-only, kein NS-Sync → bewusst OHNE Delegated-Wrapper/
 * NewEntries-Tracking). Wird ausschliesslich aus ExecuteLocalTtCommandTransaction genutzt
 * (eine Room-Transaktion = die R2-B1-Atomizitaetsgarantie).
 */
@Dao
internal interface LocalCommandDao {

    @Insert
    fun insertOutcome(outcome: LocalCommandOutcome): Long

    @Query("SELECT * FROM $TABLE_LOCAL_COMMAND_OUTCOME WHERE requestId = :requestId LIMIT 1")
    fun findOutcome(requestId: String): LocalCommandOutcome?

    /** Rate-Basis (R2-B8): erfolgreiche Mutationen der letzten Stunde; Replays zaehlen nicht
     *  (sie erzeugen keine neue Zeile). */
    @Query("SELECT COUNT(*) FROM $TABLE_LOCAL_COMMAND_OUTCOME WHERE createdAt >= :sinceMs AND outcome = 'APPLIED' AND validateOnly = 0")
    fun countAppliedSince(sinceMs: Long): Int

    @Query("DELETE FROM $TABLE_LOCAL_COMMAND_OUTCOME WHERE retainUntil < :nowMs")
    fun pruneOutcomes(nowMs: Long)

    @Insert
    fun insertOwnership(ownership: LocalCommandOwnership): Long

    @Query("SELECT * FROM $TABLE_LOCAL_COMMAND_OWNERSHIP WHERE terminatedAt IS NULL ORDER BY id DESC LIMIT 1")
    fun activeOwnership(): LocalCommandOwnership?

    @Update
    fun updateOwnership(ownership: LocalCommandOwnership)
}

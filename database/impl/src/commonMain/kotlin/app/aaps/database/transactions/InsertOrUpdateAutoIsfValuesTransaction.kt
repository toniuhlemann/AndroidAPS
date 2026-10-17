package app.aaps.database.transactions

import app.aaps.database.entities.AutoIsfValues

class InsertOrUpdateAutoIsfValuesTransaction(private val autoIsfValues: AutoIsfValues) :
    Transaction<InsertOrUpdateAutoIsfValuesTransaction.TransactionResult>() {

    override suspend fun run(): TransactionResult {
        val existing = if (autoIsfValues.id == 0L) null else database.autoIsfValuesDao.findById(autoIsfValues.id)
        return if (existing == null) {
            database.autoIsfValuesDao.insertNewEntry(autoIsfValues)
            TransactionResult(listOf(autoIsfValues), emptyList())
        } else {
            database.autoIsfValuesDao.updateExistingEntry(autoIsfValues)
            TransactionResult(emptyList(), listOf(autoIsfValues))
        }
    }

    data class TransactionResult(val inserted: List<AutoIsfValues>, val updated: List<AutoIsfValues>)
}

package io.horizontalsystems.nearkit.database

import io.horizontalsystems.nearkit.models.AccountState
import io.horizontalsystems.nearkit.models.ChainState
import io.horizontalsystems.nearkit.models.FtBalance
import io.horizontalsystems.nearkit.models.FtMetadata
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.models.TransactionSyncState
import io.horizontalsystems.nearkit.models.TransactionTag

internal class Storage(private val database: MainDatabase) {

    fun getAccountState(): AccountState? = database.accountStateDao().get()
    fun saveAccountState(state: AccountState) = database.accountStateDao().save(state)

    fun getChainState(): ChainState? = database.chainStateDao().get()
    fun saveChainState(state: ChainState) = database.chainStateDao().save(state)

    fun getFtBalances(): List<FtBalance> = database.ftBalanceDao().getAll()
    fun replaceFtBalances(balances: List<FtBalance>) = database.runInTransaction {
        database.ftBalanceDao().deleteAll()
        database.ftBalanceDao().insertAll(balances)
    }

    fun getFtMetadata(contractId: String): FtMetadata? = database.ftMetadataDao().get(contractId)
    fun saveFtMetadata(metadata: FtMetadata) = database.ftMetadataDao().save(metadata)

    fun getTransaction(hash: String): Transaction? = database.transactionDao().get(hash)
    fun getPendingTransactions(): List<Transaction> = database.transactionDao().getPending()
    fun indexedHashes(hashes: List<String>): Set<String> =
        hashes.chunked(500).flatMap { database.transactionDao().indexedHashes(it) }.toSet()

    fun getTransactions(token: String?, beforeTimestamp: Long?, beforeHash: String?, limit: Int): List<Transaction> {
        // without a hash, everything at beforeTimestamp is excluded ("" sorts before any hash)
        val hash = beforeHash ?: ""
        return if (token == null) database.transactionDao().getPage(beforeTimestamp, hash, limit)
        else database.transactionDao().getPageForToken(token, beforeTimestamp, hash, limit)
    }

    fun saveTransactions(transactions: List<Pair<Transaction, Set<String>>>) = database.runInTransaction {
        for ((transaction, tags) in transactions) {
            database.transactionDao().insert(transaction)
            database.transactionDao().deleteTags(transaction.hash)
            database.transactionDao().insertTags(tags.map { TransactionTag(transaction.hash, it) })
        }
    }

    fun getTransactionSyncState(): TransactionSyncState? = database.transactionSyncStateDao().get()
    fun saveTransactionSyncState(state: TransactionSyncState) = database.transactionSyncStateDao().save(state)
}

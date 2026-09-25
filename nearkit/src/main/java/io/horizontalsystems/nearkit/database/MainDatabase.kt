package io.horizontalsystems.nearkit.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.horizontalsystems.nearkit.models.AccountState
import io.horizontalsystems.nearkit.models.ChainState
import io.horizontalsystems.nearkit.models.FtBalance
import io.horizontalsystems.nearkit.models.FtMetadata
import io.horizontalsystems.nearkit.models.FtTransfer
import io.horizontalsystems.nearkit.models.NearTransfer
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.models.TransactionSyncState
import io.horizontalsystems.nearkit.models.TransactionTag
import io.horizontalsystems.nearkit.models.TxAction
import io.horizontalsystems.nearkit.network.Network
import java.math.BigInteger

@Database(
    entities = [
        AccountState::class, ChainState::class, FtBalance::class, FtMetadata::class,
        Transaction::class, TransactionTag::class, TransactionSyncState::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(RoomTypeConverters::class)
internal abstract class MainDatabase : RoomDatabase() {
    abstract fun accountStateDao(): AccountStateDao
    abstract fun chainStateDao(): ChainStateDao
    abstract fun ftBalanceDao(): FtBalanceDao
    abstract fun ftMetadataDao(): FtMetadataDao
    abstract fun transactionDao(): TransactionDao
    abstract fun transactionSyncStateDao(): TransactionSyncStateDao

    companion object {
        fun getInstance(context: Context, name: String): MainDatabase =
            Room.databaseBuilder(context, MainDatabase::class.java, name)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .allowMainThreadQueries()
                .build()
    }
}

internal object NearDatabaseManager {
    private fun name(network: Network, walletId: String) = "Near-${network.name}-$walletId"

    fun getDatabase(context: Context, network: Network, walletId: String): MainDatabase =
        MainDatabase.getInstance(context, name(network, walletId))

    fun clear(context: Context, network: Network, walletId: String) {
        context.deleteDatabase(name(network, walletId))
    }
}

internal class RoomTypeConverters {
    private val gson = Gson()

    @TypeConverter
    fun bigIntegerToString(value: BigInteger?): String? = value?.toString()

    @TypeConverter
    fun stringToBigInteger(value: String?): BigInteger? = value?.let { BigInteger(it) }

    @TypeConverter
    fun actionsToJson(value: List<TxAction>?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToActions(value: String?): List<TxAction>? = value?.let { gson.fromJson(it, object : TypeToken<List<TxAction>>() {}.type) }

    @TypeConverter
    fun nearTransfersToJson(value: List<NearTransfer>?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToNearTransfers(value: String?): List<NearTransfer>? = value?.let { gson.fromJson(it, object : TypeToken<List<NearTransfer>>() {}.type) }

    @TypeConverter
    fun ftTransfersToJson(value: List<FtTransfer>?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToFtTransfers(value: String?): List<FtTransfer>? = value?.let { gson.fromJson(it, object : TypeToken<List<FtTransfer>>() {}.type) }
}

@Dao
internal interface AccountStateDao {
    @Query("SELECT * FROM AccountState WHERE id = 1")
    fun get(): AccountState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(state: AccountState)
}

@Dao
internal interface ChainStateDao {
    @Query("SELECT * FROM ChainState WHERE id = 1")
    fun get(): ChainState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(state: ChainState)
}

@Dao
internal interface FtBalanceDao {
    @Query("SELECT * FROM FtBalance")
    fun getAll(): List<FtBalance>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(balances: List<FtBalance>)

    @Query("DELETE FROM FtBalance")
    fun deleteAll()
}

@Dao
internal interface FtMetadataDao {
    @Query("SELECT * FROM FtMetadata WHERE contractId = :contractId")
    fun get(contractId: String): FtMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(metadata: FtMetadata)
}

@Dao
internal interface TransactionDao {
    @Query("SELECT * FROM `Transaction` WHERE hash = :hash")
    fun get(hash: String): Transaction?

    /** Records built from the RPC or predicted at send time have no block height yet; the index fills it in. */
    @Query("SELECT hash FROM `Transaction` WHERE hash IN (:hashes) AND blockHeight IS NOT NULL")
    fun indexedHashes(hashes: List<String>): List<String>

    // Ties on timestamp are broken by hash so that paging with (timestamp, hash) is stable
    @Query("SELECT * FROM `Transaction` WHERE (:beforeTimestamp IS NULL OR timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND hash < :beforeHash)) ORDER BY timestamp DESC, hash DESC LIMIT :limit")
    fun getPage(beforeTimestamp: Long?, beforeHash: String, limit: Int): List<Transaction>

    @Query("SELECT t.* FROM `Transaction` t INNER JOIN TransactionTag g ON g.hash = t.hash WHERE g.token = :token AND (:beforeTimestamp IS NULL OR t.timestamp < :beforeTimestamp OR (t.timestamp = :beforeTimestamp AND t.hash < :beforeHash)) ORDER BY t.timestamp DESC, t.hash DESC LIMIT :limit")
    fun getPageForToken(token: String, beforeTimestamp: Long?, beforeHash: String, limit: Int): List<Transaction>

    @Query("SELECT * FROM `Transaction` WHERE status = 'Pending'")
    fun getPending(): List<Transaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(transaction: Transaction)

    @Query("DELETE FROM TransactionTag WHERE hash = :hash")
    fun deleteTags(hash: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTags(tags: List<TransactionTag>)
}

@Dao
internal interface TransactionSyncStateDao {
    @Query("SELECT * FROM TransactionSyncState WHERE id = 1")
    fun get(): TransactionSyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(state: TransactionSyncState)
}

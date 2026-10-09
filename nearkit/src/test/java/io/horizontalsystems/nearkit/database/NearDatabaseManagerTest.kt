package io.horizontalsystems.nearkit.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.nearkit.network.Network
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NearDatabaseManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun open(walletId: String, accountId: String) {
        NearDatabaseManager.getDatabase(context, Network.MainNet, walletId, accountId).apply {
            openHelper.writableDatabase
            close()
        }
    }

    private fun databases() = context.databaseList()
        .filter { name -> listOf("-journal", "-wal", "-shm").none { name.endsWith(it) } }
        .toSet()

    @Test
    fun accountsOfOneWalletGetSeparateDatabases() {
        open("w1", "alice.near")
        open("w1", "f7f5")

        assertEquals(setOf("Near-MainNet-w1-alice.near", "Near-MainNet-w1-f7f5"), databases())
    }

    @Test
    fun legacyDatabaseIsDeletedOnOpen() {
        context.openOrCreateDatabase("Near-MainNet-w1", Context.MODE_PRIVATE, null).close()

        open("w1", "alice.near")

        assertEquals(setOf("Near-MainNet-w1-alice.near"), databases())
    }

    @Test
    fun clearDeletesEveryAccountOfTheWalletOnly() {
        open("w1", "alice.near")
        open("w1", "f7f5")
        open("w2", "bob.near")
        open("w1x", "carol.near")
        context.openOrCreateDatabase("Near-MainNet-w1", Context.MODE_PRIVATE, null).close()

        NearDatabaseManager.clear(context, Network.MainNet, "w1")

        assertEquals(setOf("Near-MainNet-w2-bob.near", "Near-MainNet-w1x-carol.near"), databases())
    }
}

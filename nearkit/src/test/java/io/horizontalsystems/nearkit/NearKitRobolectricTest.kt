package io.horizontalsystems.nearkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.nearkit.models.NearAmount
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.network.Network
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.math.BigDecimal

/**
 * The whole kit against live networks, with Room storage and the sync timer: a watch-only
 * mainnet sync, then a testnet send through NearKit itself. Runs only with NEARKIT_INTEGRATION=true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NearKitRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        assumeTrue(System.getenv("NEARKIT_INTEGRATION") == "true")
        context = ApplicationProvider.getApplicationContext()

        // Robolectric starts without a validated network; the kit only syncs on one
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
    }

    @Test
    fun watchOnlyMainnetSync() = runBlocking {
        val accountId = "5510e2b44cae6eb807e3e0e45d579dda058c274abcba15e5cb84636f5d1ee412"
        val kit = NearKit.getInstance(context, NearWallet.WatchOnly(accountId), Network.MainNet, "watch", syncInterval = 5)
        kit.watchTokens(setOf("usdt.tether-token.near"))
        kit.start()
        try {
            waitSynced(kit)
            println("balance ${NearAmount.toNear(kit.balance)} tokens ${kit.ftBalances.size} txs ${kit.getTransactions(limit = 500).size}")
            assertTrue(kit.balance.signum() > 0)
            assertTrue(kit.getFtBalance("usdt.tether-token.near")!!.signum() > 0)
            assertEquals("USDt", kit.ftMetadata("usdt.tether-token.near").symbol)
            val all = kit.getTransactions(limit = 10)
            assertEquals(10, all.size)
            assertTrue(all.zipWithNext().all { (a, b) -> a.timestamp >= b.timestamp })
            val next = kit.getTransactions(beforeTimestamp = all.last().timestamp, beforeHash = all.last().hash, limit = 10)
            assertTrue(next.none { tx -> all.any { it.hash == tx.hash } })
            assertTrue(kit.getTransactions(token = NearKit.TOKEN_NATIVE, limit = 10).isNotEmpty())
            println(kit.statusInfo())
        } finally {
            kit.stop()
        }
    }

    @Test
    fun testnetSendThroughTheKit() = runBlocking {
        val seed = Mnemonic().toSeed(Mnemonic().generate())
        val publicKey = NearKit.publicKey(seed)
        val accountId = "nearkit-kit-${System.currentTimeMillis()}.testnet"
        val response = OkHttpClient().newCall(
            Request.Builder()
                .url("https://helper.testnet.near.org/account")
                .post("""{"newAccountId":"$accountId","newAccountPublicKey":"$publicKey"}""".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        // the helper rate-limits account creation; that says nothing about the kit
        assumeTrue("testnet helper rate limit", response.code != 429)
        assertTrue("helper HTTP ${response.code}", response.isSuccessful)

        val kit = NearKit.getInstance(context, NearWallet.Seed(seed, accountId), Network.TestNet, "send", syncInterval = 3)
        kit.start()
        try {
            withTimeout(60_000) {
                while (!kit.isAccountActive) delay(500)
            }
            val receiver = publicKey.implicitAccountId
            val estimate = kit.estimateNearTransfer(receiver)
            val max = kit.maxSendableNear(receiver)
            println("fee ${estimate.fee} required ${estimate.requiredBalance} max $max of ${kit.availableBalance}")
            assertEquals(kit.availableBalance - estimate.requiredBalance, max)

            val pending = kit.sendNear(receiver, NearAmount.toYocto(BigDecimal("1")))
            println("sent ${pending.hash} ${pending.status}")

            val final = withTimeout(60_000) {
                var tx = kit.getTransaction(pending.hash)!!
                while (tx.isPending) {
                    delay(1000)
                    tx = kit.getTransaction(pending.hash)!!
                }
                tx
            }
            println("final ${final.status} fee ${final.fee} block ${final.blockHeight}")
            assertEquals(Transaction.Status.Success, final.status)
            assertTrue(final.fee!! <= estimate.fee)
            assertTrue(kit.doesAccountExist(receiver))

            // the index adds the block height a few seconds later and replaces the record
            val indexed = withTimeout(60_000) {
                var tx = kit.getTransaction(pending.hash)!!
                while (tx.blockHeight == null) {
                    delay(1000)
                    tx = kit.getTransaction(pending.hash)!!
                }
                tx
            }
            println("indexed $indexed")
            assertEquals(Transaction.Status.Success, indexed.status)

            // a transfer to a named account that does not exist is refused before signing
            val refused = try {
                kit.sendNear("no-such-account-${System.currentTimeMillis()}.testnet", java.math.BigInteger.ONE); null
            } catch (e: Exception) {
                e
            }
            println("refused: $refused")
            assertTrue(refused is io.horizontalsystems.nearkit.transaction.TransactionSender.SendError.ReceiverNotFound)
        } finally {
            kit.stop()
        }
    }

    private suspend fun waitSynced(kit: NearKit) = withTimeout(90_000) {
        while (kit.syncState !is NearKit.SyncState.Synced || kit.transactionsSyncState !is NearKit.SyncState.Synced) {
            delay(500)
        }
    }
}

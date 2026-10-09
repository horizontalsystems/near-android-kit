package io.horizontalsystems.nearkit

import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.nearkit.crypto.PublicKey
import io.horizontalsystems.nearkit.network.FastNearProvider
import io.horizontalsystems.nearkit.network.Network
import io.horizontalsystems.nearkit.network.RpcProvider
import io.horizontalsystems.nearkit.sync.TransactionConverter
import io.horizontalsystems.nearkit.transaction.FtActions
import io.horizontalsystems.nearkit.transaction.Signer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only mainnet checks with the well-known "abandon … about" test mnemonic, whose implicit
 * account strangers keep funding. Runs only with NEARKIT_INTEGRATION=true.
 */
class MainnetReadTest {

    private val network = Network.MainNet

    @Test
    fun readsAccountTokensAndHistory() = runBlocking {
        assumeTrue(System.getenv("NEARKIT_INTEGRATION") == "true")

        val seed = Mnemonic().toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "))
        val publicKey = Signer.getInstance(seed).publicKey
        val accountId = publicKey.implicitAccountId

        for (url in network.rpcUrls) {
            val single = RpcProvider.create(listOf(url))
            val view = single.viewAccount(accountId)
            println("$url: ${view?.amount} yocto, block ${view?.blockHeight}")
            assertNotNull(view)
        }

        val rpc = RpcProvider.create(network.rpcUrls)
        assertNull(rpc.viewAccount("this-account-does-not-exist-nearkit.near"))
        // the account's owner has since rotated this key away, which is why it is not asserted
        println("key on account: ${rpc.viewAccessKey(accountId, publicKey.toString()) != null}")
        assertNotNull(rpc.viewAccessKey("near", "ed25519:5zset1JX4qp4PcR3N9KDSY6ATdgkrbBW5wFBGgz7vnAQ") ?: rpc.viewAccount("near"))
        val block = rpc.latestBlock()
        println("block ${block.height}, gas price ${block.gasPrice}")

        val fastNear = FastNearProvider.create(network.apiUrl, network.txApiUrl)
        val tokens = fastNear.ftBalances(accountId)
        println("tokens: ${tokens.map { it.contractId to it.balance }}")
        assertTrue(tokens.any { it.contractId == "usdt.tether-token.near" })

        assertNull(FtActions.storageDepositRequired(rpc, "usdt.tether-token.near", accountId))
        assertNotNull(FtActions.storageDepositRequired(rpc, "usdt.tether-token.near", "0000000000000000000000000000000000000000000000000000000000000001"))

        val page = fastNear.accountTransactions(accountId, newestFirst = true, limit = 20)
        val converted = fastNear.transactions(page.rows.take(20).map { it.hash }).map { TransactionConverter.convert(it, accountId, 0) }
        converted.forEach { (tx, tags) -> println("${tx.hash} ${tx.status} near=${tx.nearNetChange(accountId)} ft=${tx.ftTransfers.size} tags=$tags") }
        assertEquals(page.rows.take(20).size, converted.size)

        val accounts = NearKit.findAccounts(publicKey, network)
        println("accounts for key: $accounts")
        assertEquals(accountId, accounts.first())
    }

    // root.near's full-access keys as of 2026-10-09 (view_access_key_list)
    @Test
    fun readsNamedAccountForPicker() = runBlocking {
        assumeTrue(System.getenv("NEARKIT_INTEGRATION") == "true")
        val rootKey = PublicKey.fromString("ed25519:bN6etqmzLFHuhdrT1Mzd2cHWH5ZjEHfFq2LdEnLZ9GD")
        val otherKey = Signer.getInstance(Mnemonic().toSeed(List(11) { "abandon" } + "about")).publicKey

        val state = NearKit.accountState("root.near", network)
        assertTrue(state.exists)
        assertTrue(state.amount.signum() > 0)
        assertEquals(false, NearKit.accountState("no-such-account-hs-test.near", network).exists)

        assertTrue(NearKit.hasFullAccess("root.near", rootKey, network))
        assertEquals(false, NearKit.hasFullAccess("root.near", otherKey, network))
        assertEquals(false, NearKit.hasFullAccess("no-such-account-hs-test.near", rootKey, network))
    }
}

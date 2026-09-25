package io.horizontalsystems.nearkit

import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.nearkit.crypto.Base58
import io.horizontalsystems.nearkit.models.FtTransfer
import io.horizontalsystems.nearkit.models.NearAmount
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.network.FastNearProvider
import io.horizontalsystems.nearkit.network.Network
import io.horizontalsystems.nearkit.network.RpcError
import io.horizontalsystems.nearkit.network.RpcProvider
import io.horizontalsystems.nearkit.sync.TransactionConverter
import io.horizontalsystems.nearkit.transaction.Action
import io.horizontalsystems.nearkit.transaction.FeeCalculator
import io.horizontalsystems.nearkit.transaction.FtActions
import io.horizontalsystems.nearkit.transaction.SignedTransaction
import io.horizontalsystems.nearkit.transaction.Signer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64
import io.horizontalsystems.nearkit.transaction.Transaction as UnsignedTransaction

/**
 * Live check against NEAR testnet: create a funded account through the testnet helper, send NEAR
 * to a new implicit account, wrap NEAR and send the token with a storage deposit, then find the
 * transactions in the FastNEAR index. Runs only with NEARKIT_INTEGRATION=true.
 */
class TestnetIntegrationTest {

    private val network = Network.TestNet
    private val rpc = RpcProvider.create(network.rpcUrls)
    private val fastNear = FastNearProvider.create(network.apiUrl, network.txApiUrl)

    @Test
    fun fundSendWrapAndIndex() = runBlocking {
        assumeTrue(System.getenv("NEARKIT_INTEGRATION") == "true")

        val sender = Signer.getInstance(Mnemonic().toSeed(Mnemonic().generate()))
        val receiver = Signer.getInstance(Mnemonic().toSeed(Mnemonic().generate())).publicKey.implicitAccountId
        val senderId = "nearkit-${System.currentTimeMillis()}.testnet"
        println("sender $senderId (${sender.publicKey}), receiver $receiver")

        createAccount(senderId, sender.publicKey.toString())
        var account = rpc.viewAccount(senderId)
        var waited = 0
        while (account == null && waited < 30) {
            delay(2000); waited += 2
            account = rpc.viewAccount(senderId)
        }
        assertNotNull("helper did not create the account", account)
        println("sender balance ${NearAmount.toNear(account!!.amount)} NEAR, storage ${account.storageUsage} bytes")
        assertNull(rpc.viewAccount(receiver))

        // 1. NEAR to a fresh implicit account creates it
        val amount = NearAmount.toYocto(BigDecimal("1.5"))
        val block = rpc.latestBlock()
        val transfer = sendAndWait(sender, senderId, receiver, listOf(Action.Transfer(amount)))
        assertEquals(Transaction.Status.Success, transfer.status)
        assertEquals(amount.negate(), transfer.nearNetChange(senderId))
        assertEquals(amount, rpc.viewAccount(receiver)!!.amount)
        val estimate = FeeCalculator().estimate(listOf(Action.Transfer(amount)), receiverIsImplicit = true, createsAccount = true, gasPrice = block.gasPrice)
        println("transfer ${transfer.hash}: fee ${transfer.fee} (estimate ${estimate.fee}, required ${estimate.requiredBalance})")
        assertTrue("fee ${transfer.fee} above estimate ${estimate.fee}", transfer.fee!! <= estimate.fee)

        // 2. Reusing a nonce is refused by the node before it reaches a block
        val stale = UnsignedTransaction(senderId, sender.publicKey, BigInteger.ONE, receiver, Base58.decode(block.hash), listOf(Action.Transfer(BigInteger.ONE)))
        val rejected = try {
            rpc.sendTransaction(base64(sender.sign(stale)))
            null
        } catch (e: RpcError) {
            e
        }
        assertNotNull(rejected)
        println("stale nonce rejected: ${rejected!!.name} ${rejected.data}")
        assertEquals(RpcProvider.INVALID_TRANSACTION, rejected.name)

        // 3. Wrap NEAR, then send the token to the receiver, who is not registered yet
        val wrap = "wrap.testnet"
        val wrapActions = mutableListOf<Action>()
        FtActions.storageDepositRequired(rpc, wrap, senderId)?.let { deposit ->
            wrapActions += Action.FunctionCall("storage_deposit", """{"account_id":"$senderId","registration_only":true}""".toByteArray(), FtActions.GAS, deposit)
        }
        wrapActions += Action.FunctionCall("near_deposit", "{}".toByteArray(), FtActions.GAS, NearAmount.toYocto(BigDecimal("0.5")))
        val wrapped = sendAndWait(sender, senderId, wrap, wrapActions)
        assertEquals(Transaction.Status.Success, wrapped.status)
        println("wrap ${wrapped.hash}: ${wrapped.ftTransfers}")

        val tokenAmount = NearAmount.toYocto(BigDecimal("0.1"))
        val storageDeposit = FtActions.storageDepositRequired(rpc, wrap, receiver)
        assertNotNull("receiver should not be registered", storageDeposit)
        val ftSend = sendAndWait(sender, senderId, wrap, FtActions.transfer(receiver, tokenAmount, "nearkit", storageDeposit))
        println("ft_transfer ${ftSend.hash}: ${ftSend.ftTransfers}, fee ${ftSend.fee}")
        assertEquals(Transaction.Status.Success, ftSend.status)
        assertEquals(listOf(FtTransfer(wrap, senderId, receiver, tokenAmount, "nearkit")), ftSend.ftTransfers)
        val receiverBalance = rpc.callFunctionJson(wrap, "ft_balance_of", """{"account_id":"$receiver"}""").asString
        assertEquals(tokenAmount.toString(), receiverBalance)
        assertNull(FtActions.storageDepositRequired(rpc, wrap, receiver))

        // 4. The index picks the transactions up within seconds
        val wanted = setOf(transfer.hash, wrapped.hash, ftSend.hash)
        var indexed = emptySet<String>()
        waited = 0
        while (!indexed.containsAll(wanted) && waited < 90) {
            delay(3000); waited += 3
            indexed = fastNear.accountTransactions(senderId, newestFirst = true).rows.map { it.hash }.toSet()
        }
        println("indexed after ${waited}s: $indexed")
        assertTrue(indexed.containsAll(wanted))
        val fromIndex = fastNear.transactions(wanted.toList()).map { TransactionConverter.convert(it, senderId, 0).first }
        fromIndex.forEach { println("index ${it.hash}: ${it.status} fee ${it.fee} near ${it.nearNetChange(senderId)} ft ${it.ftTransfers}") }
        assertTrue(fromIndex.all { it.isSuccess })
        assertEquals(ftSend.ftTransfers, fromIndex.first { it.hash == ftSend.hash }.ftTransfers)

        // 5. The named account is found from its key (the implicit one always comes first)
        val accounts = NearKit.findAccounts(sender.publicKey, network)
        println("accounts for key: $accounts")
        assertEquals(sender.publicKey.implicitAccountId, accounts.first())
    }

    /** The same steps TransactionSender takes, then polls the RPC until the outcome is final. */
    private suspend fun sendAndWait(signer: Signer, signerId: String, receiverId: String, actions: List<Action>): Transaction {
        val key = rpc.viewAccessKey(signerId, signer.publicKey.toString())!!
        val tx = UnsignedTransaction(signerId, signer.publicKey, key.nonce + BigInteger.ONE, receiverId, Base58.decode(key.blockHash), actions)
        val signed = signer.sign(tx)
        rpc.sendTransaction(base64(signed), waitUntil = "INCLUDED")

        var waited = 0
        while (waited < 60) {
            val status = rpc.transactionStatus(signed.hash, signerId)
            val raw = status?.raw
            if (status != null && status.isExecuted && raw != null && status.finalExecutionStatus == "FINAL") {
                return TransactionConverter.convert(raw, signerId, 0).first
            }
            delay(1500); waited += 2
        }
        throw AssertionError("transaction ${signed.hash} did not finalize")
    }

    private fun base64(signed: SignedTransaction) = Base64.getEncoder().encodeToString(signed.encode())

    private fun createAccount(accountId: String, publicKey: String) {
        val response = OkHttpClient().newCall(
            Request.Builder()
                .url("https://helper.testnet.near.org/account")
                .post("""{"newAccountId":"$accountId","newAccountPublicKey":"$publicKey"}""".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        println("helper HTTP ${response.code}")
        // the helper rate-limits account creation; that says nothing about the kit
        assumeTrue("testnet helper rate limit", response.code != 429)
        assertTrue(response.isSuccessful)
    }
}

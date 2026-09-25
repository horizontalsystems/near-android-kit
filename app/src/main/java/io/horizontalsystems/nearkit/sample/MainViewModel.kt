package io.horizontalsystems.nearkit.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.nearkit.NearKit
import io.horizontalsystems.nearkit.NearWallet
import io.horizontalsystems.nearkit.models.FtBalance
import io.horizontalsystems.nearkit.models.NearAmount
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.network.Network
import io.horizontalsystems.nearkit.transaction.Signer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal

class MainViewModel : ViewModel() {

    var network by mutableStateOf(Network.TestNet)
    var mnemonic by mutableStateOf("")
    var secretKey by mutableStateOf("")
    var watchAccount by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    /** Accounts found for the entered key; the user picks one before the kit starts. */
    var accountChoices by mutableStateOf<List<String>>(emptyList())
    private var pendingWallet: ((String?) -> NearWallet)? = null

    var kit by mutableStateOf<NearKit?>(null)
        private set

    var syncState by mutableStateOf("")
    var transactionsSyncState by mutableStateOf("")
    var blockHeight by mutableStateOf(0L)
    var active by mutableStateOf(false)
    var balance by mutableStateOf("0")
    var available by mutableStateOf("0")
    var storageUsage by mutableStateOf(0L)
    var tokens by mutableStateOf<List<Pair<FtBalance, String>>>(emptyList())
    var transactions by mutableStateOf<List<Transaction>>(emptyList())
    var sendResult by mutableStateOf<String?>(null)
    var helperResult by mutableStateOf<String?>(null)

    private val jobs = mutableListOf<Job>()

    fun findFromMnemonic() {
        val words = mnemonic.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        findAccounts {
            Mnemonic().validate(words)
            val seed = Mnemonic().toSeed(words)
            Signer.getInstance(seed) to { accountId: String? -> NearWallet.Seed(seed, accountId) }
        }
    }

    fun findFromSecretKey() {
        findAccounts {
            val signer = Signer.fromSecretKey(secretKey)
            val privateKey = io.horizontalsystems.nearkit.crypto.Base58.decode(signer.secretKeyString.removePrefix("ed25519:")).copyOfRange(0, 32)
            signer to { accountId: String? -> NearWallet.PrivateKey(privateKey, accountId) }
        }
    }

    private fun findAccounts(parse: () -> Pair<Signer, (String?) -> NearWallet>) {
        error = null
        viewModelScope.launch {
            try {
                val (signer, walletFor) = parse()
                pendingWallet = walletFor
                val accounts = withContext(Dispatchers.IO) { NearKit.findAccounts(signer.publicKey, network) }
                // on testnet the picker also offers creating a funded account, so always show it there
                if (accounts.size == 1 && network.isMainNet) {
                    start(walletFor(accounts.single()))
                } else {
                    accountChoices = accounts
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
    }

    fun chooseAccount(accountId: String) {
        val walletFor = pendingWallet ?: return
        accountChoices = emptyList()
        start(walletFor(accountId))
    }

    /** Testnet only: the NEAR testnet helper creates `<name>.testnet` for the key, funded with 10 NEAR. */
    fun createTestnetAccount() {
        val walletFor = pendingWallet ?: return
        val publicKey = NearKit.signer(walletFor(null))?.publicKey ?: return
        val accountId = "nearkit-sample-${System.currentTimeMillis()}.testnet"
        helperResult = "Creating $accountId…"
        viewModelScope.launch {
            helperResult = try {
                withContext(Dispatchers.IO) {
                    val body = """{"newAccountId":"$accountId","newAccountPublicKey":"$publicKey"}""".toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url("https://helper.testnet.near.org/account").post(body).build()
                    OkHttpClient().newCall(request).execute().use { "Helper: HTTP ${it.code}" }
                }.also { accountChoices = accountChoices + accountId }
            } catch (e: Exception) {
                "Helper error: ${e.message}"
            }
        }
    }

    fun startWatch() {
        error = null
        try {
            start(NearWallet.WatchOnly(watchAccount.trim()))
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    private fun start(wallet: NearWallet) {
        try {
            val kit = NearKit.getInstance(App.instance, wallet, network, walletId = "sample-${network.name}-${NearKit.accountId(wallet)}")
            this.kit = kit
            observe(kit)
            kit.start()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    private fun observe(kit: NearKit) {
        jobs += viewModelScope.launch { kit.syncStateFlow.collect { syncState = it.toString(); blockHeight = kit.lastBlockHeight } }
        jobs += viewModelScope.launch { kit.transactionsSyncStateFlow.collect { transactionsSyncState = it.toString() } }
        jobs += viewModelScope.launch {
            kit.accountStateFlow.collect {
                active = it.exists
                balance = NearAmount.toNear(it.amount).toPlainString()
                available = NearAmount.toNear(it.available).toPlainString()
                storageUsage = it.storageUsage
            }
        }
        jobs += viewModelScope.launch {
            kit.ftBalancesFlow.collect { balances ->
                tokens = withContext(Dispatchers.IO) {
                    balances.map { b ->
                        val text = try {
                            val meta = kit.ftMetadata(b.contractId)
                            "${BigDecimal(b.balance).movePointLeft(meta.decimals).stripTrailingZeros().toPlainString()} ${meta.symbol}"
                        } catch (e: Exception) {
                            "${b.balance} (no metadata)"
                        }
                        b to text
                    }
                }
            }
        }
        jobs += viewModelScope.launch {
            transactions = withContext(Dispatchers.IO) { kit.getTransactions(limit = 30) }
            kit.transactionsFlow.collect { transactions = withContext(Dispatchers.IO) { kit.getTransactions(limit = 30) } }
        }
    }

    fun send(to: String, amount: String, contractId: String) {
        val kit = kit ?: return
        sendResult = "Sending…"
        viewModelScope.launch {
            sendResult = try {
                val tx = withContext(Dispatchers.IO) {
                    if (contractId.isBlank()) {
                        val estimate = kit.estimateNearTransfer(to.trim())
                        sendResult = "Fee ≈ ${NearAmount.toNear(estimate.fee).toPlainString()} NEAR, sending…"
                        kit.sendNear(to.trim(), NearAmount.toYocto(BigDecimal(amount.trim())))
                    } else {
                        val decimals = kit.ftMetadata(contractId.trim()).decimals
                        kit.sendFt(contractId.trim(), to.trim(), BigDecimal(amount.trim()).movePointRight(decimals).toBigIntegerExact())
                    }
                }
                "Submitted ${tx.hash}"
            } catch (e: Exception) {
                "Error: ${e.javaClass.simpleName} ${e.message}"
            }
        }
    }

    fun refresh() {
        kit?.refresh()
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        kit?.stop()
        kit = null
    }

    override fun onCleared() {
        stop()
    }
}

package io.horizontalsystems.nearkit.network

import java.net.URL

enum class Network(
    val id: Int,
    val isMainNet: Boolean,
    /** `near:<networkId>` is the chain id WalletConnect uses. */
    val networkId: String,
    /** JSON-RPC endpoints in failover order. rpc.*.near.org are rate-limited legacy endpoints, kept last. */
    val rpcUrls: List<URL>,
    /** FastNEAR REST API: token balances and key-to-account lookup. */
    val apiUrl: URL,
    /** FastNEAR Transactions API: indexed account history (the RPC has none). */
    val txApiUrl: URL,
    val explorerUrl: String,
) {
    MainNet(
        id = 0,
        isMainNet = true,
        networkId = "mainnet",
        rpcUrls = listOf(
            URL("https://free.rpc.fastnear.com/"),
            URL("https://near.drpc.org/"),
            URL("https://rpc.shitzuapes.xyz/"),
            URL("https://rpc.mainnet.near.org/"),
        ),
        apiUrl = URL("https://api.fastnear.com/"),
        txApiUrl = URL("https://tx.main.fastnear.com/"),
        explorerUrl = "https://nearblocks.io",
    ),
    TestNet(
        id = 1,
        isMainNet = false,
        networkId = "testnet",
        rpcUrls = listOf(
            URL("https://rpc.testnet.fastnear.com/"),
            URL("https://rpc.testnet.near.org/"),
        ),
        apiUrl = URL("https://test.api.fastnear.com/"),
        txApiUrl = URL("https://tx.test.fastnear.com/"),
        explorerUrl = "https://testnet.nearblocks.io",
    );

    fun transactionUrl(hash: String) = "$explorerUrl/txns/$hash"
    fun accountUrl(accountId: String) = "$explorerUrl/address/$accountId"
}

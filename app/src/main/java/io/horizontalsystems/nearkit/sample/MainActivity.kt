package io.horizontalsystems.nearkit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.horizontalsystems.nearkit.NearKit
import io.horizontalsystems.nearkit.models.NearAmount
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.network.Network

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val kit = viewModel.kit
        when {
            kit != null -> AccountSection(viewModel, kit)
            viewModel.accountChoices.isNotEmpty() -> AccountChoiceSection(viewModel)
            else -> SetupSection(viewModel)
        }
    }
}

@Composable
private fun SetupSection(viewModel: MainViewModel) {
    Text("NearKit Sample", style = MaterialTheme.typography.h6)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Network.values().forEach { network ->
            OutlinedButton(onClick = { viewModel.network = network }) {
                Text(if (network == viewModel.network) "✓ ${network.name}" else network.name)
            }
        }
    }

    OutlinedTextField(value = viewModel.mnemonic, onValueChange = { viewModel.mnemonic = it }, label = { Text("Mnemonic") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { viewModel.findFromMnemonic() }, modifier = Modifier.fillMaxWidth()) { Text("Restore") }

    OutlinedTextField(value = viewModel.secretKey, onValueChange = { viewModel.secretKey = it }, label = { Text("Secret key (ed25519:…)") }, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { viewModel.findFromSecretKey() }, modifier = Modifier.fillMaxWidth()) { Text("Import key") }

    OutlinedTextField(value = viewModel.watchAccount, onValueChange = { viewModel.watchAccount = it }, label = { Text("Watch account (alice.near or 64 hex)") }, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { viewModel.startWatch() }, modifier = Modifier.fillMaxWidth()) { Text("Watch") }

    viewModel.error?.let { Text("Error: $it", color = MaterialTheme.colors.error) }
}

@Composable
private fun AccountChoiceSection(viewModel: MainViewModel) {
    Text("Accounts for this key", style = MaterialTheme.typography.h6)
    Text("Tap an account to open it", style = MaterialTheme.typography.caption)
    viewModel.accountChoices.forEachIndexed { index, accountId ->
        OutlinedButton(onClick = { viewModel.chooseAccount(accountId) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (index == 0) "$accountId (implicit)" else accountId)
        }
    }
    if (viewModel.network == Network.TestNet) {
        OutlinedButton(onClick = { viewModel.createTestnetAccount() }, modifier = Modifier.fillMaxWidth()) {
            Text("Create funded .testnet account")
        }
        viewModel.helperResult?.let { Text(it, style = MaterialTheme.typography.caption) }
    }
}

@Composable
private fun AccountSection(viewModel: MainViewModel, kit: NearKit) {
    Text("${kit.network.name} · block ${viewModel.blockHeight}", style = MaterialTheme.typography.caption)
    Text(kit.accountId, style = MaterialTheme.typography.body2)
    kit.publicKey?.let { Text(it.toString(), style = MaterialTheme.typography.caption) }
    Text("Sync: ${viewModel.syncState}", style = MaterialTheme.typography.caption)
    Text("Tx sync: ${viewModel.transactionsSyncState}", style = MaterialTheme.typography.caption)

    Divider()
    if (!viewModel.active) {
        Text("Account does not exist yet: it is created by the first NEAR sent to it", color = MaterialTheme.colors.error)
    }
    LabeledRow("Balance", "${viewModel.balance} NEAR")
    LabeledRow("Available", "${viewModel.available} NEAR")
    LabeledRow("Storage", "${viewModel.storageUsage} bytes")
    viewModel.tokens.forEach { (balance, text) -> LabeledRow(balance.contractId, text) }

    OutlinedButton(onClick = { viewModel.refresh() }) { Text("Refresh") }

    Divider()
    if (!kit.isWatchOnly) {
        SendSection(viewModel)
        Divider()
    }

    Text("Transactions", style = MaterialTheme.typography.h6)
    viewModel.transactions.forEach { tx -> TransactionRow(tx, kit.accountId) }

    Divider()
    OutlinedButton(onClick = { viewModel.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.body2)
        Text(value, style = MaterialTheme.typography.body2)
    }
}

@Composable
private fun SendSection(viewModel: MainViewModel) {
    var to by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var contract by rememberSaveable { mutableStateOf("") }

    Text("Send", style = MaterialTheme.typography.h6)
    OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text("To (account id)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = contract, onValueChange = { contract = it }, label = { Text("Token contract (empty for NEAR)") }, modifier = Modifier.fillMaxWidth())
    Button(
        onClick = { viewModel.send(to, amount, contract) },
        enabled = to.isNotBlank() && amount.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Send")
    }
    viewModel.sendResult?.let { Text(it, style = MaterialTheme.typography.caption) }
}

@Composable
private fun TransactionRow(tx: Transaction, accountId: String) {
    val near = tx.nearNetChange(accountId)
    val parts = mutableListOf<String>()
    if (near.signum() != 0) parts += "${NearAmount.toNear(near).toPlainString()} NEAR"
    tx.ftTransfers.forEach { t ->
        val sign = if (t.to == accountId) "+" else "-"
        parts += "$sign${t.amount} ${t.contractId}"
    }
    val action = tx.actions.firstOrNull()?.let { it.methodName ?: it.type } ?: ""
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$action ${parts.joinToString()} · ${tx.status}", style = MaterialTheme.typography.body2)
        Text(tx.hash.take(16) + "… " + (tx.failure?.take(60) ?: ""), style = MaterialTheme.typography.caption)
    }
}

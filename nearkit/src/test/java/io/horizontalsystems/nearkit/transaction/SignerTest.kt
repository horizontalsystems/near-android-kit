package io.horizontalsystems.nearkit.transaction

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.nearkit.crypto.PublicKey
import io.horizontalsystems.nearkit.crypto.hexToBytes
import io.horizontalsystems.nearkit.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/**
 * Vectors in near-js-vectors.json come from near-seed-phrase 0.2 (the derivation MyNearWallet and
 * near-cli use), @near-js/transactions 2.5.1 and, for NEP-413 messages, @near-js/signers 2.5.1
 * (`KeyPairSigner.signNep413Message`) run with borsh 2.0.0; see the file for the generating inputs.
 * The borsh 1.0.0 near-js ships with writes one byte per UTF-16 unit instead of UTF-8, so its
 * signatures of non-ASCII messages match neither Rust borsh nor the NEP.
 */
class SignerTest {

    private val vectors: JsonObject = JsonParser.parseString(
        javaClass.classLoader!!.getResource("near-js-vectors.json")!!.readText()
    ).asJsonObject

    @Test
    fun derivesTheSameKeysAsNearSeedPhrase() {
        for (element in vectors.getAsJsonArray("keys")) {
            val v = element.asJsonObject
            val seed = Mnemonic().toSeed(v["mnemonic"].asString.split(" "))
            val signer = Signer.getInstance(seed)

            assertEquals(v["publicKey"].asString, signer.publicKey.toString())
            assertEquals(v["secretKey"].asString, signer.secretKeyString)
            assertEquals(v["implicitAccount"].asString, signer.publicKey.implicitAccountId)
        }
    }

    @Test
    fun importsSecretKeyString() {
        val v = vectors.getAsJsonArray("keys")[1].asJsonObject
        val signer = Signer.fromSecretKey(v["secretKey"].asString)
        assertEquals(v["publicKey"].asString, signer.publicKey.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSecretKeyWithForeignPublicKey() {
        val keys = vectors.getAsJsonArray("keys")
        val a = Signer.fromSecretKey(keys[0].asJsonObject["secretKey"].asString)
        val b = Signer.fromSecretKey(keys[1].asJsonObject["secretKey"].asString)
        val mixed = io.horizontalsystems.nearkit.crypto.Base58.encode(
            io.horizontalsystems.nearkit.crypto.Base58.decode(a.secretKeyString.removePrefix("ed25519:")).copyOfRange(0, 32) + b.publicKey.data
        )
        Signer.fromSecretKey("ed25519:$mixed")
    }

    @Test
    fun keyWithLeadingZeroByteStays32Bytes() {
        val key = BigInteger("00ff" + "11".repeat(30), 16)
        val fixed = Signer.toFixed32(key)
        assertEquals(32, fixed.size)
        assertEquals("00ff" + "11".repeat(30), fixed.toHex())
    }

    @Test
    fun transferMatchesNearJs() = assertVector("transfer") { v ->
        listOf(Action.Transfer(BigInteger("1500000000000000000000000")))
    }

    @Test
    fun ftTransferWithStorageDepositMatchesNearJs() = assertVector("ft_transfer_with_storage") {
        listOf(
            Action.FunctionCall(
                "storage_deposit",
                """{"account_id":"bob.near","registration_only":true}""".toByteArray(),
                BigInteger("30000000000000"),
                BigInteger("1250000000000000000000"),
            ),
            Action.FunctionCall(
                "ft_transfer",
                """{"receiver_id":"bob.near","amount":"1000000"}""".toByteArray(),
                BigInteger("30000000000000"),
                BigInteger.ONE,
            ),
        )
    }

    @Test
    fun decodesSignedTransactionBackToTheSameBytes() {
        for (element in vectors.getAsJsonArray("txs")) {
            val v = element.asJsonObject
            val bytes = java.util.Base64.getDecoder().decode(v["signedTxBase64"].asString)
            val decoded = SignedTransaction.decode(bytes)
            assertArrayEquals(bytes, decoded.encode())
            assertEquals(v["hash"].asString, decoded.hash)
            assertEquals(v["receiverId"].asString, decoded.transaction.receiverId)
        }
    }

    @Test
    fun signsNep413MessagesLikeNearJs() {
        val signer = Signer.fromSecretKey(vectors.getAsJsonArray("keys")[0].asJsonObject["secretKey"].asString)
        for (element in vectors.getAsJsonArray("nep413")) {
            val v = element.asJsonObject
            val payload = MessagePayload(
                message = v["message"].asString,
                nonce = v["nonceHex"].asString.hexToBytes(),
                recipient = v["recipient"].asString,
                callbackUrl = v["callbackUrl"].takeUnless { it.isJsonNull }?.asString,
            )
            assertEquals(v["publicKey"].asString, signer.publicKey.toString())
            assertEquals(v["signatureBase64"].asString, java.util.Base64.getEncoder().encodeToString(signer.sign(payload)))
        }
    }

    @Test
    fun nep413PayloadStartsWithTagNoTransactionCanHave() {
        val payload = MessagePayload("hi", ByteArray(32), "app.near")
        // 2^31 + 413, little-endian: as a transaction's signer id length it would be over 2 GB
        assertEquals("9d010080", payload.encode().copyOfRange(0, 4).toHex())
    }

    private fun assertVector(name: String, actions: (JsonObject) -> List<Action>) {
        val v = vectors.getAsJsonArray("txs").map { it.asJsonObject }.first { it["name"].asString == name }
        val signer = Signer.fromSecretKey(vectors.getAsJsonArray("keys")[0].asJsonObject["secretKey"].asString)
        val tx = Transaction(
            signerId = v["signerId"].asString,
            publicKey = PublicKey.fromString(v["publicKey"].asString),
            nonce = BigInteger(v["nonce"].asString),
            receiverId = v["receiverId"].asString,
            blockHash = io.horizontalsystems.nearkit.crypto.Base58.decode(v["blockHash"].asString),
            actions = actions(v),
        )
        assertEquals(v["txHex"].asString, tx.encode().toHex())
        assertEquals(v["hash"].asString, tx.hashString())

        val signed = signer.sign(tx)
        // Ed25519 signatures are deterministic, so the whole signed payload must match
        assertEquals(v["signatureHex"].asString, signed.signature.toHex())
        assertEquals(v["signedTxBase64"].asString, java.util.Base64.getEncoder().encodeToString(signed.encode()))
        assertArrayEquals(v["txHex"].asString.hexToBytes(), Transaction.decode(tx.encode()).encode())
    }
}

package io.horizontalsystems.nearkit.network

import io.horizontalsystems.nearkit.FakeNear
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** Which errors move on to the next endpoint, and which endpoint is asked first. */
class RpcProviderTest {

    private val near = FakeNear()
    private var now = 0L
    private val provider = near.rpcProvider { now }

    private val account = FakeNear.result("""{"amount":"1","locked":"0","storage_usage":182,"code_hash":"11111111111111111111111111111111","block_height":1}""")

    @After
    fun noUnexpectedRequests() {
        assertEquals(emptyList<String>(), near.unexpected)
    }

    private fun viewAccount() = runBlocking { provider.viewAccount("alice.near") }

    @Test
    fun unknownErrorIsAskedOfTheNextEndpoint() {
        near.answer("query", FakeNear.error("SOMETHING_NEW"), account)
        assertEquals(1L, viewAccount()!!.blockHeight)
        assertEquals(listOf("rpc1.test", "rpc2.test"), near.hosts)
    }

    @Test
    fun errorAboutTheRequestIsNotAskedAgain() {
        near.answer("query", FakeNear.error("UNKNOWN_ACCOUNT"))
        assertNull(viewAccount())
        assertEquals(listOf("rpc1.test"), near.hosts)
    }

    @Test
    fun fallbackEndpointIsPreferredOnlyForAWhile() {
        near.answer("query", IOException("down"), account, account)
        viewAccount()
        viewAccount()
        assertEquals(listOf("rpc1.test", "rpc2.test", "rpc2.test"), near.hosts)

        now += RpcProvider.PREFERRED_ENDPOINT_TTL_MS + 1
        near.answer("query", account)
        viewAccount()
        assertEquals("rpc1.test", near.hosts.last())
    }
}

/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx

import kotlinx.coroutines.runBlocking
import no.nordicsemi.android.mcumgr.McuMgrErrorCode
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.ktx.managers.DefaultManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.StatsManagerKtx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManagersTest {

    @Test
    fun `echo returns the response`() = runBlocking {
        val transport = FakeTransport { _, _ -> mapOf("r" to "Hello") }
        val response = DefaultManagerKtx(transport).echo("Hello")
        assertEquals("Hello", response.r)
    }

    @Test
    fun `memory pools are parsed`() = runBlocking {
        val transport = FakeTransport { _, _ ->
            mapOf(
                "mpools" to mapOf(
                    "0" to mapOf("blksiz" to 1, "nblks" to 316, "nfree" to 300, "min" to 290),
                    "1" to mapOf("nblks" to 444, "nfree" to 444, "min" to 444),
                )
            )
        }
        val pools = DefaultManagerKtx(transport).memoryPoolStats().mpools
        assertEquals(2, pools.size)
        assertEquals(316, pools.getValue("0").nblks)
        assertEquals(1, pools.getValue("0").blksiz)
        // The block size is optional.
        assertEquals(-1, pools.getValue("1").blksiz)
    }

    @Test
    fun `memory pools are parsed legacy`() = runBlocking {
        val transport = FakeTransport { _, _ ->
            mapOf(
                "0" to mapOf("blksiz" to 1, "nblks" to 316, "nfree" to 300, "min" to 290),
                "1" to mapOf("nblks" to 444, "nfree" to 444, "min" to 444),
            )
        }
        val pools = DefaultManagerKtx(transport).memoryPoolStats().mpools
        assertEquals(2, pools.size)
        assertEquals(316, pools.getValue("0").nblks)
        assertEquals(1, pools.getValue("0").blksiz)
        // The block size is optional.
        assertEquals(-1, pools.getValue("1").blksiz)
    }

    @Test
    fun `error response is thrown`() {
        val transport = FakeTransport { _, _ -> mapOf("rc" to 8) }
        val e = assertFailsWith<McuMgrErrorException> {
            runBlocking { StatsManagerKtx(transport).list() }
        }
        assertEquals(McuMgrErrorCode.NOT_SUPPORTED, e.code)
    }

    @Test
    fun `custom command is sent`() = runBlocking {
        val transport = FakeTransport { _, _ -> mapOf("r" to "Hi") }
        val manager = no.nordicsemi.android.mcumgr.managers.DefaultManager(transport)
        val response = manager.sendAndAwait(
            0, 0, mapOf("d" to "Hi"), 1000,
            no.nordicsemi.android.mcumgr.response.dflt.McuMgrEchoResponse::class.java
        )
        assertEquals("Hi", response.r)
        assertEquals(1, transport.requests.size)
    }
}

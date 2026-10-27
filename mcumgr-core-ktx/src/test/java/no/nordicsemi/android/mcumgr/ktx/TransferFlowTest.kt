/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.ktx.transfer.TransferState
import no.nordicsemi.android.mcumgr.ktx.transfer.downloadFlow
import no.nordicsemi.android.mcumgr.transfer.TransferController
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TransferFlowTest {
    private class FakeController : TransferController {
        var cancelled = false
        override fun pause() = Unit
        override fun resume() = Unit
        override fun cancel() {
            cancelled = true
        }
    }

    @Test
    fun `started is the first event even if the progress is reported earlier`() = runBlocking {
        val states = downloadFlow { callback ->
            // The transfer reports the progress before it returns the controller.
            val worker = thread {
                callback.onDownloadProgressChanged(10, 100, 1L)
                callback.onDownloadCompleted(byteArrayOf(1, 2, 3))
            }
            worker.join()
            FakeController()
        }.toList()

        assertEquals(3, states.size)
        assertIs<TransferState.Started>(states[0])
        assertEquals(0.1f, (states[1] as TransferState.InProgress).progress)
        assertContentEquals(byteArrayOf(1, 2, 3), (states[2] as TransferState.Completed).result)
    }

    @Test
    fun `failure is thrown`() {
        val flow = downloadFlow { callback ->
            callback.onDownloadFailed(McuMgrException("Failed"))
            FakeController()
        }
        val e = assertFailsWith<McuMgrException> { runBlocking { flow.toList() } }
        assertEquals("Failed", e.message)
    }

    @Test
    fun `cancelling the controller reports cancelled`() = runBlocking {
        val states = downloadFlow { callback ->
            callback.onDownloadCanceled()
            FakeController()
        }.toList()
        assertEquals(TransferState.Cancelled, states.last())
    }

    @Test
    fun `cancelling the collection cancels the transfer`() = runBlocking {
        val controller = FakeController()
        val flow = downloadFlow { controller }
        kotlinx.coroutines.withTimeoutOrNull(200.milliseconds) { flow.toList() }
        assertTrue(controller.cancelled)
    }
}

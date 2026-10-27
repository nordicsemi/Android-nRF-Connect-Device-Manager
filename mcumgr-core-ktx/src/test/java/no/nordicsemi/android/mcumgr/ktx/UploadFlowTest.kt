/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.ktx.managers.FsManagerKtx
import no.nordicsemi.android.mcumgr.ktx.transfer.TransferState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests the whole upload, with the library sending the packets to a fake device. */
class UploadFlowTest {
    private val file = ByteArray(5000) { it.toByte() }

    /** A device storing the data of the file. */
    private class FakeFileSystem {
        val data = java.io.ByteArrayOutputStream()

        fun write(request: Map<String, Any>): Map<String, Any> {
            // Let the progress be reported before the upload ends.
            Thread.sleep(5)
            val offset = request.getValue("off") as Int
            val chunk = request.getValue("data") as ByteArray
            if (offset == data.size()) {
                data.write(chunk)
            }
            return mapOf("rc" to 0, "off" to data.size())
        }
    }

    @Test
    fun `file is uploaded`() = runBlocking {
        val device = FakeFileSystem()
        val transport = FakeTransport { _, request -> device.write(request) }

        val states = FsManagerKtx(transport).upload("/lfs/file.bin", file).toList()

        assertIs<TransferState.Started>(states.first())
        assertEquals(TransferState.Completed(Unit), states.last())
        val progress = states.filterIsInstance<TransferState.InProgress>()
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.zipWithNext().all { (a, b) -> b.current >= a.current })
        assertEquals(file.size, progress.last().current)
        assertContentEquals(file, device.data.toByteArray())
    }

    @Test
    fun `upload is cancelled with the controller`() = runBlocking {
        val device = FakeFileSystem()
        val transport = FakeTransport { _, request ->
            Thread.sleep(20)
            device.write(request)
        }
        val fs = FsManagerKtx(transport)
        // Small packets, so that the upload takes many of them.
        fs.manager.setUploadMtu(200)

        val states = mutableListOf<TransferState<Unit>>()
        fs.upload("/lfs/file.bin", ByteArray(20_000)).collect {
            states += it
            if (it is TransferState.InProgress && it.current > 0) {
                (states.first() as TransferState.Started).controller.cancel()
            }
        }

        assertEquals(TransferState.Cancelled, states.last())
        assertTrue(device.data.size() < 20_000)
    }

    @Test
    fun `failing upload throws`() {
        val transport = FakeTransport { _, _ -> mapOf("rc" to 2) }
        val flow = FsManagerKtx(transport).upload("/lfs/file.bin", file)
        assertFailsWith<McuMgrException> { runBlocking { flow.toList() } }
    }
}

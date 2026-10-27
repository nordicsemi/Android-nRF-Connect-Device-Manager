/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.State
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.ktx.dfu.FirmwareUpgradeException
import no.nordicsemi.android.mcumgr.ktx.dfu.FirmwareUpgradeState
import no.nordicsemi.android.mcumgr.ktx.dfu.UpgradeCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpgradeCallbackTest {

    @Test
    fun `events are passed in order and the flow completes`() = runBlocking {
        val channel = Channel<FirmwareUpgradeState<State>>(Channel.UNLIMITED)
        val callback = UpgradeCallback(channel)

        callback.onStateChanged(State.NONE, State.VALIDATE)
        callback.onUploadProgressChanged(10, 100, 5L)
        assertFalse(callback.isFinished)
        callback.onUpgradeCompleted()
        assertTrue(callback.isFinished)

        val states = channel.receiveAsFlow().toList()
        assertEquals(
            listOf(
                FirmwareUpgradeState.StateChanged(State.NONE, State.VALIDATE),
                FirmwareUpgradeState.UploadProgress(10, 100, 5L),
                FirmwareUpgradeState.Completed,
            ),
            states
        )
        assertEquals(0.1f, (states[1] as FirmwareUpgradeState.UploadProgress).progress)
    }

    @Test
    fun `failure carries the state`() {
        val channel = Channel<FirmwareUpgradeState<State>>(Channel.UNLIMITED)
        val callback = UpgradeCallback(channel)
        val cause = McuMgrException("Failed")

        callback.onUpgradeFailed(State.UPLOAD, cause)

        val e = assertFailsWith<FirmwareUpgradeException> {
            runBlocking { channel.receiveAsFlow().toList() }
        }
        assertEquals(State.UPLOAD, e.state)
        assertEquals(cause, e.cause)
    }

    @Test
    fun `cancellation is reported`() = runBlocking {
        val channel = Channel<FirmwareUpgradeState<State>>(Channel.UNLIMITED)
        UpgradeCallback(channel).onUpgradeCanceled(State.TEST)
        assertEquals(
            listOf<FirmwareUpgradeState<State>>(FirmwareUpgradeState.Cancelled(State.TEST)),
            channel.receiveAsFlow().toList()
        )
    }
}

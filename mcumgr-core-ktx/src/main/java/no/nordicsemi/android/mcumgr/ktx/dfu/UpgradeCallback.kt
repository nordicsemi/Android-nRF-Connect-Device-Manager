/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.dfu

import kotlinx.coroutines.channels.SendChannel
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeCallback
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeController
import no.nordicsemi.android.mcumgr.exception.McuMgrException

/**
 * Passes the events of a firmware upgrade to a channel.
 *
 * The callbacks are called from the thread performing the upgrade, in order.
 */
internal class UpgradeCallback<S : Enum<S>>(
    private val channel: SendChannel<FirmwareUpgradeState<S>>,
) : FirmwareUpgradeCallback<S> {
    /** Whether the upgrade has ended, successfully or not. */
    @Volatile
    var isFinished = false
        private set

    override fun onUpgradeStarted(controller: FirmwareUpgradeController) {
        channel.trySend(FirmwareUpgradeState.Started(controller))
    }

    override fun onStateChanged(prevState: S, newState: S) {
        channel.trySend(FirmwareUpgradeState.StateChanged(prevState, newState))
    }

    override fun onUpgradeCompleted() {
        isFinished = true
        channel.trySend(FirmwareUpgradeState.Completed)
        channel.close()
    }

    override fun onUpgradeFailed(state: S, error: McuMgrException) {
        isFinished = true
        channel.close(FirmwareUpgradeException(state, error))
    }

    override fun onUpgradeCanceled(state: S) {
        isFinished = true
        channel.trySend(FirmwareUpgradeState.Cancelled(state))
        channel.close()
    }

    override fun onUploadProgressChanged(bytesSent: Int, imageSize: Int, timestamp: Long) {
        channel.trySend(FirmwareUpgradeState.UploadProgress(bytesSent, imageSize, timestamp))
    }
}

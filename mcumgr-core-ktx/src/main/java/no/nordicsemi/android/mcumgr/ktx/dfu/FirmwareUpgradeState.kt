/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.dfu

import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeController
import no.nordicsemi.android.mcumgr.exception.McuMgrException

/**
 * The state of a firmware upgrade, as reported by the flow returned by the `upgrade` methods of
 * [FirmwareUpgradeManagerKtx] and [SUITUpgradeManagerKtx].
 *
 * The flow is cold: the upgrade starts when the flow is collected, and is cancelled when
 * the collection is cancelled. The first emitted state is always [Started]. The flow completes
 * after [Completed] or [Cancelled], and fails with a [FirmwareUpgradeException] if the upgrade
 * fails.
 *
 * @param S The type of the state of the upgrade process, which is
 * [FirmwareUpgradeManager.State][no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.State]
 * or [SUITUpgradeManager.State][no.nordicsemi.android.mcumgr.dfu.suit.SUITUpgradeManager.State].
 */
sealed interface FirmwareUpgradeState<out S : Enum<*>> {

    /**
     * The upgrade has been started.
     *
     * @property controller Allows to pause, resume or cancel the upgrade. Cancelling the upgrade
     * with it emits [Cancelled].
     */
    data class Started(val controller: FirmwareUpgradeController) : FirmwareUpgradeState<Nothing>

    /**
     * The upgrade process has moved to another state.
     *
     * @property previous The previous state.
     * @property current The new state.
     */
    data class StateChanged<out S : Enum<*>>(
        val previous: S,
        val current: S,
    ) : FirmwareUpgradeState<S>

    /**
     * The progress of the upload of the image, or of the envelope or resource, to the device.
     *
     * @property bytesSent The number of bytes sent.
     * @property imageSize The total number of bytes to send.
     * @property timestamp The time of the report, in milliseconds since the epoch.
     */
    data class UploadProgress(
        val bytesSent: Int,
        val imageSize: Int,
        val timestamp: Long,
    ) : FirmwareUpgradeState<Nothing> {
        /** The progress, from 0.0 to 1.0, or 0.0 if the size is not known. */
        val progress: Float
            get() = if (imageSize > 0) bytesSent.toFloat() / imageSize else 0f
    }

    /** The upgrade has been completed successfully. */
    data object Completed : FirmwareUpgradeState<Nothing>

    /**
     * The upgrade has been cancelled using the [controller][Started.controller].
     *
     * @property state The state of the upgrade process when it was cancelled.
     */
    data class Cancelled<out S : Enum<*>>(val state: S) : FirmwareUpgradeState<S>
}

/**
 * Thrown by the flow of a firmware upgrade when the upgrade fails.
 *
 * @property state The state of the upgrade process in which it failed.
 * @param cause The reason of the failure.
 */
class FirmwareUpgradeException(
    val state: Enum<*>,
    cause: McuMgrException,
) : McuMgrException(cause) {
    override val cause: McuMgrException
        get() = super.cause as McuMgrException
}

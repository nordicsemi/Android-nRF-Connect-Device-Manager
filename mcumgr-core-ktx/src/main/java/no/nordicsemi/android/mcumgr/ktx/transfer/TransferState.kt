/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.transfer

import no.nordicsemi.android.mcumgr.transfer.TransferController

/**
 * The state of an upload or a download, as reported by the flow returned by the `upload` and
 * `download` methods.
 *
 * The flow is cold: the transfer starts when the flow is collected, and is cancelled when
 * the collection is cancelled. The first emitted state is always [Started]. The flow completes
 * after [Completed] or [Cancelled], and fails with an
 * [McuMgrException][no.nordicsemi.android.mcumgr.exception.McuMgrException] if the transfer
 * fails.
 *
 * @param R The type of the result of the transfer: `Unit` for uploads and downloads to a stream,
 * `ByteArray` for downloads.
 */
sealed interface TransferState<out R> {

    /**
     * The transfer has been started.
     *
     * @property controller Allows to pause, resume or cancel the transfer. Cancelling the transfer
     * with it emits [Cancelled].
     */
    data class Started(val controller: TransferController) : TransferState<Nothing>

    /**
     * The progress of the transfer.
     *
     * @property current The number of bytes transferred.
     * @property total The total number of bytes to transfer. For some downloads this value may be
     * not known until the first chunk is received.
     * @property timestamp The time of the report, in milliseconds since the epoch.
     */
    data class InProgress(
        val current: Int,
        val total: Int,
        val timestamp: Long,
    ) : TransferState<Nothing> {
        /** The progress, from 0.0 to 1.0, or 0.0 if the total size is not known yet. */
        val progress: Float
            get() = if (total > 0) current.toFloat() / total else 0f
    }

    /**
     * The transfer has been completed successfully.
     *
     * @property result The result of the transfer.
     */
    data class Completed<out R>(val result: R) : TransferState<R>

    /** The transfer has been cancelled using the [controller][Started.controller]. */
    data object Cancelled : TransferState<Nothing>
}

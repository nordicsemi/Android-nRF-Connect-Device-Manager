/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.transfer

import kotlinx.coroutines.flow.Flow
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.transfer.DownloadCallback
import no.nordicsemi.android.mcumgr.transfer.StreamDownloadCallback
import no.nordicsemi.android.mcumgr.transfer.TransferController
import no.nordicsemi.android.mcumgr.transfer.UploadCallback
import no.nordicsemi.android.mcumgr.transfer.Uploader

/** Uploads the data of this uploader. The result is `Unit`. */
internal fun Uploader.asFlow(): Flow<TransferState<Unit>> = transferFlow<TransferState<Unit>>(
    started = { TransferState.Started(it) },
) { sink, scope ->
    uploadAsync(sink.uploadCallback(), scope)
}

/** Starts an upload using the given function, which is one of the `startUpload` family. */
internal fun uploadFlow(
    start: (UploadCallback) -> TransferController,
): Flow<TransferState<Unit>> = transferFlow<TransferState<Unit>>(
    started = { TransferState.Started(it) },
) { sink, _ ->
    start(sink.uploadCallback())
}

/** Starts a download to memory using the given function. The result is the downloaded data. */
internal fun downloadFlow(
    start: (DownloadCallback) -> TransferController,
): Flow<TransferState<ByteArray>> = transferFlow<TransferState<ByteArray>>(
    started = { TransferState.Started(it) },
) { sink, _ ->
    start(object : DownloadCallback {
        override fun onDownloadProgressChanged(current: Int, total: Int, timestamp: Long) {
            sink.emit(TransferState.InProgress(current, total, timestamp))
        }

        override fun onDownloadFailed(error: McuMgrException) = sink.finish(error)

        override fun onDownloadCanceled() {
            sink.emit(TransferState.Cancelled)
            sink.finish()
        }

        override fun onDownloadCompleted(data: ByteArray) {
            sink.emit(TransferState.Completed(data))
            sink.finish()
        }
    })
}

/** Starts a download to a stream using the given function. The result is `Unit`. */
internal fun streamDownloadFlow(
    start: (StreamDownloadCallback) -> TransferController,
): Flow<TransferState<Unit>> = transferFlow<TransferState<Unit>>(
    started = { TransferState.Started(it) },
) { sink, _ ->
    start(object : StreamDownloadCallback {
        override fun onDownloadProgressChanged(current: Int, total: Int, timestamp: Long) {
            sink.emit(TransferState.InProgress(current, total, timestamp))
        }

        override fun onDownloadFailed(error: McuMgrException) = sink.finish(error)

        override fun onDownloadCanceled() {
            sink.emit(TransferState.Cancelled)
            sink.finish()
        }

        override fun onDownloadCompleted() {
            sink.emit(TransferState.Completed(Unit))
            sink.finish()
        }
    })
}

private fun EventSink<TransferState<Unit>>.uploadCallback() = object : UploadCallback {
    override fun onUploadProgressChanged(current: Int, total: Int, timestamp: Long) {
        emit(TransferState.InProgress(current, total, timestamp))
    }

    override fun onUploadFailed(error: McuMgrException) = finish(error)

    override fun onUploadCanceled() {
        emit(TransferState.Cancelled)
        finish()
    }

    override fun onUploadCompleted() {
        emit(TransferState.Completed(Unit))
        finish()
    }
}

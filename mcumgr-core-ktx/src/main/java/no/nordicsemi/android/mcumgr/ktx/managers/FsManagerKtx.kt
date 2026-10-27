/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.managers

import kotlinx.coroutines.flow.Flow
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.ktx.awaitResponse
import no.nordicsemi.android.mcumgr.ktx.transfer.TransferState
import no.nordicsemi.android.mcumgr.ktx.transfer.asFlow
import no.nordicsemi.android.mcumgr.ktx.transfer.downloadFlow
import no.nordicsemi.android.mcumgr.ktx.transfer.streamDownloadFlow
import no.nordicsemi.android.mcumgr.ktx.transfer.uploadFlow
import no.nordicsemi.android.mcumgr.managers.FsManager
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsCrc32Response
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsDownloadResponse
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsSha256Response
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsStatusResponse
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsUploadResponse
import no.nordicsemi.android.mcumgr.transfer.FileUploader
import java.io.InputStream
import java.io.OutputStream

/**
 * A manager of the File System group of Mcu Manager, with suspending methods and flows. It wraps
 * [FsManager].
 *
 * Every suspending method waits until the response is received, and throws a [McuMgrException] if
 * the request fails, or a [McuMgrErrorException] if the device responds with an error, that is
 * with a non-zero return code. Cancelling the calling coroutine stops
 * waiting for the response, but does not cancel the request, which has already been sent.
 *
 * The upload and the download of a file are long-running, and report their state using a [Flow].
 * See [TransferState].
 *
 * Use the [manager] to set a logger, the upload MTU, etc.
 *
 * @property manager The manager of the Mcu Manager library, which sends the requests.
 */
class FsManagerKtx(val manager: FsManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(FsManager(transport))

    /**
     * Reads a single packet of a file, starting from the given offset.
     *
     * The size of the packet is limited by the MTU. Use [download] to read a whole file.
     *
     * @param name The path of the file.
     * @param offset The offset, from which the packet is read.
     */
    suspend fun downloadChunk(name: String, offset: Int): McuMgrFsDownloadResponse =
        awaitResponse { manager.download(name, offset, it) }

    /**
     * Sends a single packet of a file, starting from the given offset.
     *
     * The size of the packet is limited by the MTU. Use [upload] to send a whole file.
     *
     * @param name The path of the file.
     * @param data The data of the file.
     * @param offset The offset, from which the packet is sent.
     */
    suspend fun uploadChunk(name: String, data: ByteArray, offset: Int): McuMgrFsUploadResponse =
        awaitResponse { manager.upload(name, data, offset, it) }

    /**
     * Reads the status of a file, such as its size.
     *
     * @param name The path of the file.
     */
    suspend fun status(name: String): McuMgrFsStatusResponse =
        awaitResponse { manager.status(name, it) }

    /**
     * Calculates the CRC32 of a file.
     *
     * @param name The path of the file.
     */
    suspend fun crc32(name: String): McuMgrFsCrc32Response =
        awaitResponse { manager.crc32(name, it) }

    /**
     * Calculates the CRC32 of a part of a file.
     *
     * @param name The path of the file.
     * @param offset The offset, from which the CRC is calculated.
     * @param length The number of bytes to take into account.
     */
    suspend fun crc32(name: String, offset: Int, length: Int): McuMgrFsCrc32Response =
        awaitResponse { manager.crc32(name, offset, length, it) }

    /**
     * Calculates the SHA-256 of a file.
     *
     * @param name The path of the file.
     */
    suspend fun sha256(name: String): McuMgrFsSha256Response =
        awaitResponse { manager.sha256(name, it) }

    /**
     * Calculates the SHA-256 of a part of a file.
     *
     * @param name The path of the file.
     * @param offset The offset, from which the hash is calculated.
     * @param length The number of bytes to take into account.
     */
    suspend fun sha256(name: String, offset: Int, length: Int): McuMgrFsSha256Response =
        awaitResponse { manager.sha256(name, offset, length, it) }

    /** Closes all the files opened by the manager on the device. */
    suspend fun closeAll(): McuMgrResponse = awaitResponse { manager.closeAll(it) }

    /**
     * Uploads a file to the device.
     *
     * The upload starts when the flow is collected, and is cancelled when the collection is.
     * The upload may be paused, resumed and cancelled using the controller from
     * [TransferState.Started].
     *
     * @param name The path of the file on the device.
     * @param data The data of the file.
     * @param windowCapacity The number of packets sent without waiting for the response. Use 1
     * if the device does not support the window upload.
     * @param memoryAlignment The alignment of the packets, used when the window capacity is 2 or
     * more. Each packet is trimmed to a size that is a multiple of this value.
     * @throws McuMgrException if the upload fails.
     */
    fun upload(
        name: String,
        data: ByteArray,
        windowCapacity: Int = 1,
        memoryAlignment: Int = 1,
    ): Flow<TransferState<Unit>> =
        FileUploader(manager, name, data, windowCapacity, memoryAlignment).asFlow()

    /**
     * Uploads a file to the device, reading it from the given stream.
     *
     * The upload starts when the flow is collected, and is cancelled when the collection is.
     * The stream is not closed.
     *
     * @param name The path of the file on the device.
     * @param data The stream to read the file from.
     * @param totalBytes The number of bytes to read from the stream.
     * @throws McuMgrException if the upload fails.
     */
    fun upload(name: String, data: InputStream, totalBytes: Int): Flow<TransferState<Unit>> =
        uploadFlow { manager.fileUpload(name, data, totalBytes, it) }

    /**
     * Downloads a file from the device.
     *
     * The download starts when the flow is collected, and is cancelled when the collection is.
     * The data is delivered with [TransferState.Completed].
     *
     * @param name The path of the file on the device.
     * @throws McuMgrException if the download fails.
     */
    fun download(name: String): Flow<TransferState<ByteArray>> =
        downloadFlow { manager.fileDownload(name, it) }

    /**
     * Downloads a file from the device, writing it to the given stream.
     *
     * The download starts when the flow is collected, and is cancelled when the collection is.
     * The stream is not closed.
     *
     * @param name The path of the file on the device.
     * @param output The stream to write the file to.
     * @throws McuMgrException if the download fails.
     */
    fun download(name: String, output: OutputStream): Flow<TransferState<Unit>> =
        streamDownloadFlow { manager.fileDownload(name, output, it) }
}

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
import no.nordicsemi.android.mcumgr.managers.ImageManager
import no.nordicsemi.android.mcumgr.response.img.McuMgrCoreLoadResponse
import no.nordicsemi.android.mcumgr.response.img.McuMgrImageResponse
import no.nordicsemi.android.mcumgr.response.img.McuMgrImageSlotResponse
import no.nordicsemi.android.mcumgr.response.img.McuMgrImageStateResponse
import no.nordicsemi.android.mcumgr.response.img.McuMgrImageUploadResponse
import no.nordicsemi.android.mcumgr.transfer.ImageUploader

/**
 * A manager of the Image group of Mcu Manager, with suspending methods and flows. It wraps
 * [ImageManager].
 *
 * Every suspending method waits until the response is received, and throws a [McuMgrException] if
 * the request fails, or a [McuMgrErrorException] if the device responds with an error, that is
 * with a non-zero return code. Cancelling the calling coroutine stops
 * waiting for the response, but does not cancel the request, which has already been sent.
 *
 * The upload and the download are long-running, and report their state using a [Flow].
 * See [TransferState]. To update the firmware of a device, with all the steps it takes, use
 * [no.nordicsemi.android.mcumgr.ktx.dfu.FirmwareUpgradeManagerKtx].
 *
 * Use the [manager] to set a logger, the upload MTU, etc.
 *
 * @property manager The manager of the Mcu Manager library, which sends the requests.
 */
class ImageManagerKtx(val manager: ImageManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(ImageManager(transport))

    /** Reads the state of the images on the device. */
    suspend fun list(): McuMgrImageStateResponse = awaitResponse { manager.list(it) }

    /**
     * Sends a single packet of the image, starting from the given offset.
     *
     * The size of the packet is limited by the MTU. Use [upload] to send a whole image.
     *
     * @param data The image data.
     * @param offset The offset, from which the packet is sent.
     * @param image The index of the image, 0 for the first core, 1 for the second, etc.
     */
    suspend fun uploadChunk(data: ByteArray, offset: Int, image: Int = 0): McuMgrImageUploadResponse =
        awaitResponse { manager.upload(data, offset, image, it) }

    /**
     * Uploads the whole image to the device.
     *
     * The upload starts when the flow is collected, and is cancelled when the collection is.
     * The upload may be paused, resumed and cancelled using the controller from
     * [TransferState.Started]. If the device already has a part of the same image, the upload
     * continues from there.
     *
     * Note, that this only uploads the image. To test, confirm and reset the device after, use
     * [no.nordicsemi.android.mcumgr.ktx.dfu.FirmwareUpgradeManagerKtx].
     *
     * @param data The image data.
     * @param image The index of the image, 0 for the first core, 1 for the second, etc.
     * @param windowCapacity The number of packets sent without waiting for the response. Use 1
     * if the device does not support the window upload, or a number up to the number of buffers
     * on the device (see `buf_count` in the response of the `params` command).
     * @param memoryAlignment The alignment of the packets, used when the window capacity is 2 or
     * more. Each packet is trimmed to a size that is a multiple of this value.
     * @throws McuMgrException if the upload fails.
     */
    fun upload(
        data: ByteArray,
        image: Int = 0,
        windowCapacity: Int = 1,
        memoryAlignment: Int = 1,
    ): Flow<TransferState<Unit>> =
        ImageUploader(manager, data, image, windowCapacity, memoryAlignment).asFlow()

    /**
     * Requests the image with the given hash to be tested, that is run once, on the next reset.
     *
     * @param hash The hash of the image.
     */
    suspend fun test(hash: ByteArray): McuMgrImageStateResponse =
        awaitResponse { manager.test(hash, it) }

    /**
     * Confirms the image with the given hash, or the running image, so that it is run after
     * every reset.
     *
     * @param hash The hash of the image, or null to confirm the one that is running.
     */
    suspend fun confirm(hash: ByteArray? = null): McuMgrImageStateResponse =
        awaitResponse { manager.confirm(hash, it) }

    /**
     * Erases the image in the secondary slot, or in the given slot.
     *
     * @param slot The slot to erase, or null for the secondary slot.
     */
    suspend fun erase(slot: Int? = null): McuMgrImageResponse = awaitResponse {
        if (slot == null) manager.erase(it) else manager.erase(slot, it)
    }

    /** Reads the slots of the images on the device. */
    suspend fun slots(): McuMgrImageSlotResponse = awaitResponse { manager.slots(it) }

    /** Reads the core list. */
    suspend fun coreList(): McuMgrImageResponse = awaitResponse { manager.coreList(it) }

    /**
     * Reads a chunk of the core dump.
     *
     * @param offset The offset, from which the chunk is read.
     */
    suspend fun coreLoad(offset: Int): McuMgrCoreLoadResponse =
        awaitResponse { manager.coreLoad(offset, it) }

    /** Erases the core dump. */
    suspend fun coreErase(): McuMgrImageResponse = awaitResponse { manager.coreErase(it) }

    /**
     * Downloads the core dump.
     *
     * The download starts when the flow is collected, and is cancelled when the collection is.
     * The data is delivered with [TransferState.Completed].
     *
     * @throws McuMgrException if the download fails.
     */
    fun coreDownload(): Flow<TransferState<ByteArray>> =
        downloadFlow { manager.coreDownload(it) }
}

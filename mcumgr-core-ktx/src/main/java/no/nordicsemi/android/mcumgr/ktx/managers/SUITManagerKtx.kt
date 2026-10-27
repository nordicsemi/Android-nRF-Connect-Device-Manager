/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.managers

import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.ktx.awaitResponse
import no.nordicsemi.android.mcumgr.managers.SUITManager
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import no.nordicsemi.android.mcumgr.response.suit.McuMgrManifestListResponse
import no.nordicsemi.android.mcumgr.response.suit.McuMgrManifestStateResponse
import no.nordicsemi.android.mcumgr.response.suit.McuMgrPollResponse
import no.nordicsemi.android.mcumgr.response.suit.McuMgrUploadResponse

/**
 * A manager of the SUIT group of Mcu Manager, with suspending methods. It wraps [SUITManager].
 *
 * These are the single requests of the group. Use [no.nordicsemi.android.mcumgr.ktx.dfu.SUITUpgradeManagerKtx]
 * to perform the whole SUIT upgrade.
 *
 * Every method suspends until the response is received, and throws a [McuMgrException] if the
 * request fails, or a [McuMgrErrorException] if the device responds with an error, that is
 * with a non-zero return code. Cancelling the calling coroutine stops
 * waiting for the response, but does not cancel the request, which has already been sent.
 *
 * Use the [manager] to set a logger, the upload MTU, etc.
 *
 * @property manager The manager of the Mcu Manager library, which sends the requests.
 */
class SUITManagerKtx(val manager: SUITManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(SUITManager(transport))

    /**
     * Lists the manifests on the device.
     */
    suspend fun listManifests(): McuMgrManifestListResponse = awaitResponse { manager.listManifests(it) }

    /**
     * Reads the state of the manifest of the given role.
     */
    suspend fun getManifestState(role: Int): McuMgrManifestStateResponse = awaitResponse { manager.getManifestState(role, it) }

    /**
     * Sends a chunk of the SUIT envelope.
     *
     * @param data The envelope.
     * @param offset The offset, from which the chunk is sent.
     * @param deferInstall Whether the install should be deferred until [beginDeferredInstall].
     */
    suspend fun upload(data: ByteArray, offset: Int, deferInstall: Boolean = false): McuMgrUploadResponse = awaitResponse { manager.upload(data, offset, deferInstall, it) }

    /**
     * Begins the deferred install of the uploaded envelope.
     */
    suspend fun beginDeferredInstall(): McuMgrResponse = awaitResponse { manager.beginDeferredInstall(it) }

    /**
     * Polls the device for the image or resource it requires.
     */
    suspend fun poll(): McuMgrPollResponse = awaitResponse { manager.poll(it) }

    /**
     * Sends a chunk of a resource requested by the device.
     */
    suspend fun uploadResource(sessionId: Int, data: ByteArray, offset: Int): McuMgrUploadResponse = awaitResponse { manager.uploadResource(sessionId, data, offset, it) }

    /**
     * Sends a chunk of an image of the cache partition.
     */
    suspend fun uploadCache(partition: Int, data: ByteArray, offset: Int): McuMgrUploadResponse = awaitResponse { manager.uploadCache(partition, data, offset, it) }

    /**
     * Cleans up the SUIT state on the device.
     */
    suspend fun cleanup(): McuMgrResponse = awaitResponse { manager.cleanup(it) }
}

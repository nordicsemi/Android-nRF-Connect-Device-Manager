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
import no.nordicsemi.android.mcumgr.managers.BasicManager
import no.nordicsemi.android.mcumgr.response.zephyr.basic.McuMgrZephyrBasicResponse

/**
 * A manager of the Zephyr Basic group of Mcu Manager, with suspending methods. It wraps [BasicManager].
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
class BasicManagerKtx(val manager: BasicManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(BasicManager(transport))

    /**
     * Erases the storage of the application (a factory reset).
     */
    suspend fun eraseStorage(): McuMgrZephyrBasicResponse = awaitResponse { manager.eraseStorage(it) }
}

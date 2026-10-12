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
import no.nordicsemi.android.mcumgr.managers.StatsManager
import no.nordicsemi.android.mcumgr.response.stat.McuMgrStatListResponse
import no.nordicsemi.android.mcumgr.response.stat.McuMgrStatResponse

/**
 * A manager of the Statistics group of Mcu Manager, with suspending methods. It wraps [StatsManager].
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
class StatsManagerKtx(val manager: StatsManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(StatsManager(transport))

    /**
     * Reads the statistics of the given module.
     *
     * @param module The name of the module, one of those returned by [list].
     */
    suspend fun read(module: String?): McuMgrStatResponse = awaitResponse { manager.read(module, it) }

    /**
     * Lists the names of the modules, for which statistics are available.
     */
    suspend fun list(): McuMgrStatListResponse = awaitResponse { manager.list(it) }
}

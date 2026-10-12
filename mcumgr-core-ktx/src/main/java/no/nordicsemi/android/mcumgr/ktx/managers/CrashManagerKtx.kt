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
import no.nordicsemi.android.mcumgr.managers.CrashManager
import no.nordicsemi.android.mcumgr.response.McuMgrResponse

/**
 * A manager of the Crash group of Mcu Manager, with suspending methods. It wraps [CrashManager].
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
class CrashManagerKtx(val manager: CrashManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(CrashManager(transport))

    /**
     * Crashes the device on purpose, to test the crash handling. Not supported in Zephyr or nRF Connect SDK.
     */
    suspend fun test(test: CrashManager.Test): McuMgrResponse = awaitResponse { manager.test(test, it) }
}

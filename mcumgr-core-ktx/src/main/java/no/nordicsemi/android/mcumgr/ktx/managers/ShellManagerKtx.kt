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
import no.nordicsemi.android.mcumgr.managers.ShellManager
import no.nordicsemi.android.mcumgr.response.shell.McuMgrExecResponse

/**
 * A manager of the Shell group of Mcu Manager, with suspending methods. It wraps [ShellManager].
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
class ShellManagerKtx(val manager: ShellManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(ShellManager(transport))

    /**
     * Executes a shell command.
     *
     * @param cmd The command.
     * @param argv The arguments of the command, or null.
     */
    suspend fun exec(cmd: String, argv: Array<String>? = null): McuMgrExecResponse = awaitResponse { manager.exec(cmd, argv, it) }

    /**
     * Executes a shell command, waiting for the response for the given time.
     *
     * @param cmd The command.
     * @param argv The arguments of the command, or null.
     * @param timeout The timeout, in milliseconds.
     */
    suspend fun exec(cmd: String, argv: Array<String>?, timeout: Long): McuMgrExecResponse = awaitResponse { manager.exec(cmd, argv, timeout, it) }
}

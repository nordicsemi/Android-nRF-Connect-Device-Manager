/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.managers

import java.util.Date
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.ktx.awaitResponse
import no.nordicsemi.android.mcumgr.managers.LogManager
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import no.nordicsemi.android.mcumgr.response.log.McuMgrLevelListResponse
import no.nordicsemi.android.mcumgr.response.log.McuMgrLogListResponse
import no.nordicsemi.android.mcumgr.response.log.McuMgrLogResponse
import no.nordicsemi.android.mcumgr.response.log.McuMgrModuleListResponse

/**
 * A manager of the Logs group of Mcu Manager, with suspending methods. It wraps [LogManager].
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
class LogManagerKtx(val manager: LogManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(LogManager(transport))

    /**
     * Reads the log entries.
     *
     * @param logName The name of the log, or null for all.
     * @param minIndex The minimum index of the entries, or null for all.
     * @param minTimestamp The minimum timestamp of the entries, or null for all.
     */
    suspend fun show(logName: String? = null, minIndex: Long? = null, minTimestamp: Date? = null): McuMgrLogResponse = awaitResponse { manager.show(logName, minIndex, minTimestamp, it) }

    /**
     * Clears the logs on the device.
     */
    suspend fun clear(): McuMgrResponse = awaitResponse { manager.clear(it) }

    /**
     * Lists the log modules.
     */
    suspend fun moduleList(): McuMgrModuleListResponse = awaitResponse { manager.moduleList(it) }

    /**
     * Lists the log levels.
     */
    suspend fun levelList(): McuMgrLevelListResponse = awaitResponse { manager.levelList(it) }

    /**
     * Lists the logs.
     */
    suspend fun logsList(): McuMgrLogListResponse = awaitResponse { manager.logsList(it) }
}

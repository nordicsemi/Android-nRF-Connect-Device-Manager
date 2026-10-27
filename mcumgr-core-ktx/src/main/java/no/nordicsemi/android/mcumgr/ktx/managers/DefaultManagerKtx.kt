/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.managers

import java.util.Date
import java.util.TimeZone
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.ktx.awaitResponse
import no.nordicsemi.android.mcumgr.managers.DefaultManager
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrAppInfoResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrBootloaderInfoResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrEchoResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrMpStatResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrOsResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrParamsResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrReadDateTimeResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrTaskStatResponse

/**
 * A manager of the OS (default) group of Mcu Manager, with suspending methods. It wraps [DefaultManager].
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
class DefaultManagerKtx(val manager: DefaultManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(DefaultManager(transport))

    /**
     * Sends an echo request. The device responds with the same string.
     */
    suspend fun echo(echo: String?): McuMgrEchoResponse = awaitResponse { manager.echo(echo, it) }

    /**
     * Enables or disables the console echo on the device.
     */
    suspend fun consoleEcho(echo: Boolean): McuMgrOsResponse = awaitResponse { manager.consoleEcho(echo, it) }

    /**
     * Reads the statistics of the tasks (threads) running on the device.
     */
    suspend fun taskStats(): McuMgrTaskStatResponse = awaitResponse { manager.taskstats(it) }

    /**
     * Reads the statistics of the memory pools of the device.
     */
    suspend fun memoryPoolStats(): McuMgrMpStatResponse = awaitResponse { manager.mpstat(it) }

    /**
     * Reads the date and time on the device.
     */
    suspend fun readDateTime(): McuMgrReadDateTimeResponse = awaitResponse { manager.readDatetime(it) }

    /**
     * Sets the date and time on the device.
     *
     * @param date The date to set, or null to use the current time.
     * @param timeZone The time zone, or null to use the default one.
     */
    suspend fun writeDateTime(date: Date? = null, timeZone: TimeZone? = null): McuMgrOsResponse = awaitResponse { manager.writeDatetime(date, timeZone, it) }

    /**
     * Resets the device.
     */
    suspend fun reset(): McuMgrOsResponse = awaitResponse { manager.reset(it) }

    /**
     * Resets the device in the given boot mode.
     *
     * @param bootMode The boot mode, see [DefaultManager.BOOT_MODE_TYPE_NORMAL].
     * @param force Whether to reset even if the device does not want to.
     */
    suspend fun reset(bootMode: Int, force: Boolean): McuMgrOsResponse = awaitResponse { manager.reset(bootMode, force, it) }

    /**
     * Reads the parameters of Mcu Manager on the device, such as the buffer size and count.
     */
    suspend fun params(): McuMgrParamsResponse = awaitResponse { manager.params(it) }

    /**
     * Reads the application info.
     *
     * @param format The format of the response, see Zephyr documentation, or null for the default.
     */
    suspend fun appInfo(format: String? = null): McuMgrAppInfoResponse = awaitResponse { manager.appInfo(format, it) }

    /**
     * Reads the bootloader info.
     *
     * @param query The query, e.g. `"bootloader"` or `"mode"`, or null for the default.
     */
    suspend fun bootloaderInfo(query: String? = null): McuMgrBootloaderInfoResponse = awaitResponse { manager.bootloaderInfo(query, it) }
}

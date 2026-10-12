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
import no.nordicsemi.android.mcumgr.managers.SettingsManager
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import no.nordicsemi.android.mcumgr.response.settings.McuMgrSettingsReadResponse

/**
 * A manager of the Settings group of Mcu Manager, with suspending methods. It wraps [SettingsManager].
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
class SettingsManagerKtx(val manager: SettingsManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(SettingsManager(transport))

    /**
     * Reads the value of a setting.
     *
     * @param name The name of the setting.
     * @param maxSize The maximum size of the value to read, or null for no limit.
     */
    suspend fun read(name: String, maxSize: Int? = null): McuMgrSettingsReadResponse = awaitResponse { manager.read(name, maxSize, it) }

    /**
     * Writes the value of a setting.
     */
    suspend fun write(name: String, value: ByteArray): McuMgrResponse = awaitResponse { manager.write(name, value, it) }

    /**
     * Deletes a setting.
     */
    suspend fun delete(name: String): McuMgrResponse = awaitResponse { manager.delete(name, it) }

    /**
     * Commits the settings.
     */
    suspend fun commit(): McuMgrResponse = awaitResponse { manager.commit(it) }

    /**
     * Loads the settings from the persistent storage.
     */
    suspend fun load(): McuMgrResponse = awaitResponse { manager.load(it) }

    /**
     * Saves the settings to the persistent storage.
     */
    suspend fun save(): McuMgrResponse = awaitResponse { manager.save(it) }
}

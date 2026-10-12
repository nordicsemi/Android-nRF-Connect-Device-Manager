/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.dfu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeSettings
import no.nordicsemi.android.mcumgr.dfu.suit.SUITUpgradeManager
import no.nordicsemi.android.mcumgr.dfu.suit.SUITUpgradeManager.State
import no.nordicsemi.android.mcumgr.dfu.suit.model.CacheImageSet
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import java.net.URI

/**
 * Performs the firmware upgrade of a device using SUIT (Software Update for the Internet of
 * Things), reporting its state using a [Flow]. It wraps [SUITUpgradeManager].
 *
 * The upgrade starts with sending the SUIT envelope. The device may then request resources,
 * for example images, which are provided by the `resourceProvider`, and the process is repeated
 * until the device reboots.
 *
 * The [manager] can only perform one upgrade at a time, and keeps one callback. The upgrade
 * flow takes it over for the time of the upgrade, and also makes the callbacks to be called on
 * a background thread, instead of the UI thread. Do not use a callback set on the same manager
 * at the same time.
 *
 * @property manager The manager of the Mcu Manager library, which performs the upgrade.
 */
class SUITUpgradeManagerKtx(val manager: SUITUpgradeManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(SUITUpgradeManager(transport))

    /**
     * Upgrades the firmware using the given SUIT envelope.
     *
     * The upgrade starts when the flow is collected, and is cancelled when the collection is.
     * It may be paused, resumed and cancelled using the controller from
     * [FirmwareUpgradeState.Started].
     *
     * @param envelope The SUIT candidate envelope.
     * @param settings The settings of the upgrade.
     * @param cacheImages The images to upload to the cache partitions of the device, or null.
     * @param resourceProvider Provides the resource with the given URI, when the device requests
     * it. It is called in the coroutine collecting the flow, and cancelled when the device does not
     * need the resource any more. If it throws, the upgrade fails. The default implementation
     * does not provide any resources.
     * @throws FirmwareUpgradeException if the upgrade fails.
     * @throws IllegalStateException if another upgrade is in progress.
     */
    fun upgrade(
        envelope: ByteArray,
        settings: FirmwareUpgradeSettings = FirmwareUpgradeSettings.Builder().build(),
        cacheImages: CacheImageSet? = null,
        resourceProvider: suspend (uri: URI) -> ByteArray = {
            throw McuMgrException("The device requested a resource, but none can be provided: $it")
        },
    ): Flow<FirmwareUpgradeState<State>> = callbackFlow {
        check(!manager.isInProgress && !manager.isPaused) {
            "Firmware upgrade is already in progress"
        }

        val callback = UpgradeCallback<State>(channel)
        val resourceJobs = mutableSetOf<Job>()
        manager.setCallbackOnUiThread(false)
        manager.setFirmwareUpgradeCallback(callback)
        manager.setResourceCallback(object : SUITUpgradeManager.OnResourceRequiredCallback {
            override fun onResourceRequired(
                uri: URI,
                callback: SUITUpgradeManager.ResourceCallback,
            ) {
                val job = launch {
                    try {
                        callback.provide(resourceProvider(uri))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        callback.error(e)
                    }
                }
                synchronized(resourceJobs) { resourceJobs += job }
                job.invokeOnCompletion { synchronized(resourceJobs) { resourceJobs -= job } }
            }

            override fun onUploadCancelled() {
                synchronized(resourceJobs) { resourceJobs.toList() }.forEach { it.cancel() }
            }
        })
        manager.start(settings, envelope, cacheImages)

        awaitClose {
            // The upgrade is still running if the collection has been cancelled.
            if (!callback.isFinished) {
                manager.cancel()
            }
            manager.setFirmwareUpgradeCallback(null)
            manager.setResourceCallback(null)
        }
    }.buffer(Channel.UNLIMITED)
}

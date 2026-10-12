/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.dfu

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.Mode
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.Settings
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.State
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.ImageSet
import no.nordicsemi.android.mcumgr.exception.McuMgrException

/**
 * Performs the firmware upgrade of a device running McuBoot, reporting its state using a [Flow].
 * It wraps [FirmwareUpgradeManager].
 *
 * The upgrade is a process of validating the images, uploading them, testing or confirming, and
 * resetting the device, depending on the [Mode]. See the documentation of
 * [FirmwareUpgradeManager] for details.
 *
 * The [manager] can only perform one upgrade at a time, and keeps one callback. The upgrade
 * flow takes it over for the time of the upgrade, and also makes the callbacks to be called on
 * a background thread, instead of the UI thread. Do not use a callback set on the same manager
 * at the same time.
 *
 * @property manager The manager of the Mcu Manager library, which performs the upgrade.
 */
class FirmwareUpgradeManagerKtx(val manager: FirmwareUpgradeManager) {

    /** Creates the manager using the given transport. */
    constructor(transport: McuMgrTransport) : this(FirmwareUpgradeManager(transport))

    /**
     * Upgrades the firmware using a single image.
     *
     * The upgrade starts when the flow is collected, and is cancelled when the collection is.
     * It may be paused, resumed and cancelled using the controller from
     * [FirmwareUpgradeState.Started].
     *
     * @param image The image data, in the format of McuBoot.
     * @param settings The settings of the upgrade.
     * @param mode The mode of the upgrade, or null to leave the one set in the [manager]
     * (the default is [Mode.TEST_AND_CONFIRM]).
     * @throws FirmwareUpgradeException if the upgrade fails.
     * @throws McuMgrException if the image is not valid.
     * @throws IllegalStateException if another upgrade is in progress.
     */
    fun upgrade(
        image: ByteArray,
        settings: Settings = Settings.Builder().build(),
        mode: Mode? = null,
    ): Flow<FirmwareUpgradeState<State>> = flow {
        // Parsing the image throws if it is not valid, which is reported when the flow is collected.
        emitAll(upgrade(ImageSet().add(image), settings, mode))
    }

    /**
     * Upgrades the firmware using multiple images, for example for a device with many cores.
     *
     * The upgrade starts when the flow is collected, and is cancelled when the collection is.
     * It may be paused, resumed and cancelled using the controller from
     * [FirmwareUpgradeState.Started].
     *
     * @param images The images, each with the index of the core it is for.
     * @param settings The settings of the upgrade.
     * @param mode The mode of the upgrade, or null to leave the one set in the [manager]
     * (the default is [Mode.TEST_AND_CONFIRM]).
     * @throws FirmwareUpgradeException if the upgrade fails.
     * @throws IllegalStateException if another upgrade is in progress.
     */
    fun upgrade(
        images: ImageSet,
        settings: Settings = Settings.Builder().build(),
        mode: Mode? = null,
    ): Flow<FirmwareUpgradeState<State>> = callbackFlow {
        check(!manager.isInProgress && !manager.isPaused) {
            "Firmware upgrade is already in progress"
        }

        val callback = UpgradeCallback<State>(channel)
        manager.setCallbackOnUiThread(false)
        manager.setFirmwareUpgradeCallback(callback)
        mode?.let { manager.setMode(it) }
        manager.start(images, settings)

        awaitClose {
            // The upgrade is still running if the collection has been cancelled.
            if (!callback.isFinished) {
                manager.cancel()
            }
            manager.setFirmwareUpgradeCallback(null)
        }
    }.buffer(Channel.UNLIMITED)
}

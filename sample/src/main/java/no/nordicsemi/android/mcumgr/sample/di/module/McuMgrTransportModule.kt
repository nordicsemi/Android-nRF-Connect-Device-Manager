/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("DEPRECATION")

package no.nordicsemi.android.mcumgr.sample.di.module

import android.bluetooth.BluetoothDevice
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.ble.McuMgrBleTransport
import no.nordicsemi.android.mcumgr.sample.di.McuMgrScope
import no.nordicsemi.android.mcumgr.sample.log.LogController
import no.nordicsemi.android.mcumgr.sample.profile.LegacyDeviceInfoProfile
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.ConnectionState
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.log.Log

@Module
class McuMgrTransportModule {

    /**
     * Provides a profile that reads the device information from the Device Information Service (DIS)
     * and Monitoring and Diagnostics Service (MDS).
     *
     * This method was replaced by the Memfault Group, available in `:ota` module.
     *
     * This profile provides backward compatibility for devices that are using DIS to provide
     * required information.
     */
    @Provides
    @McuMgrScope
    fun providesLegacyDeviceInfoProfile(): LegacyDeviceInfoProfile = LegacyDeviceInfoProfile()

    /**
     * The peripheral this Mcu Manager scope talks to.
     *
     * Nothing is connected here: the transport connects the device on the first request. The
     * legacy profile is registered right away though, as a profile runs by itself once the
     * services it needs are discovered, whenever that happens to be.
     */
    @Provides
    @McuMgrScope
    fun providesPeripheral(
        centralManager: CentralManager,
        device: BluetoothDevice,
        legacyDeviceInfoProfile: LegacyDeviceInfoProfile,
        scope: CoroutineScope,
    ): Peripheral {
        val peripheral = requireNotNull(centralManager.getPeripheralById(device.address)) {
            "Unknown device: ${device.address}"
        }
        // Optional, so a device without those services is left alone.
        peripheral.profile(scope = scope, profile = legacyDeviceInfoProfile, required = false)
        return peripheral
    }

    /**
     * The Mcu Manager transport, which owns the connection to the device.
     *
     * Only the interface is provided: the app is written against [McuMgrTransport], and the
     * parts that need Bluetooth LE check for [McuMgrBleTransport] themselves.
     */
    @Provides
    @McuMgrScope
    fun providesMcuMgrTransport(
        centralManager: CentralManager,
        peripheral: Peripheral,
        scope: CoroutineScope,
        logger: @JvmSuppressWildcards Log.Sink<Log.Category>,
        logController: LogController,
    ): McuMgrTransport = McuMgrBleTransport(centralManager, peripheral, scope)
        .also { transport ->
            transport.setLogger(logger)
            transport.state
                .onEach { logController.linkReady = it == McuMgrBleTransport.State.Connected }
                .launchIn(scope)
        }
}

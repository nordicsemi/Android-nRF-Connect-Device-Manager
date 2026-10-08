/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.mcumgr.McuMgrCallback
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.ble.McuMgrBleTransport
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.managers.DefaultManager
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrAppInfoResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrBootloaderInfoResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrParamsResponse
import no.nordicsemi.android.mcumgr.sample.profile.LegacyDeviceInfoProfile
import no.nordicsemi.android.observability.ObservabilityManager
import no.nordicsemi.android.observability.data.ChunksEmitter
import no.nordicsemi.android.ota.DeviceInfo
import no.nordicsemi.android.ota.mcumgr.MemfaultDeviceInfoResponse
import no.nordicsemi.android.ota.mcumgr.MemfaultManager
import no.nordicsemi.android.ota.mcumgr.MemfaultProjectKeyResponse
import no.nordicsemi.kotlin.ble.core.BondState
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import no.nordicsemi.kotlin.ble.core.ConnectionState as BleConnectionState

sealed class FeatureState<out T> {
    class Supported<T>(val result: T) : FeatureState<T>()
    object NotSupported : FeatureState<Nothing>()
    object Unknown : FeatureState<Nothing>()
}

enum class ConnectionState {
    CONNECTING,
    INITIALIZING,
    READY,
    DISCONNECTING,
    DISCONNECTED,
    TIMEOUT,
    NOT_SUPPORTED
}

class DeviceStatusViewModel @Inject internal constructor(
    private val defaultManager: DefaultManager,
    private val memfaultManager: MemfaultManager,
    private val legacyDeviceInfo: LegacyDeviceInfoProfile,
    observabilityManager: ObservabilityManager,
    @Named("busy") state: MutableLiveData<Boolean?>?
) : McuMgrViewModel(state) {
    val connectionState: LiveData<ConnectionState>

    private val bondStateLiveData = MutableLiveData(BondState.NONE)
    private val bufferLiveData = MutableLiveData<McuMgrBufferParams?>()
    private val bootloaderNameLiveData = MutableLiveData<String?>()
    private val bootloaderModeLiveData = MutableLiveData<Int?>()
    private val activeB0SlotLiveData = MutableLiveData<Int?>()
    private val appInfoLiveData = MutableLiveData<String?>()
    private val otaLiveData = MutableLiveData<FeatureState<DeviceInfo>>(FeatureState.Unknown)
    private val observabilityLiveData = MutableLiveData<ChunksEmitter.State?>()
    private val connectionStateObserver = Observer { connectionState: ConnectionState? ->
        if (connectionState == ConnectionState.READY) {
            // Read sequentially:
            // 1. MCU Manager parameters
            // 2. Application info (parameter: "sv" will return the kernel name and version)
            // 3. Read Memfault OTA device information
            // 4. Bootloader name
            // 5. Active b0 slot
            // and, if the bootloader is "MCUboot":
            // 6. Bootloader mode
            readMcuMgrParams {
                readAppInfo("sv") {
                    readOta {
                        readBootloaderName { name ->
                            if ("MCUboot" == name) {
                                readActiveSlot {
                                    readMcuBootMode(null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        val transport = defaultManager.transporter
        val connectionStateLiveData = MutableLiveData<ConnectionState>()
        if (transport is McuMgrBleTransport) {
            // A Bluetooth LE transport can report the state of the link in detail. The service
            // counts as ready only once SMP is usable, not merely when the device is connected.
            transport.peripheral.state
                .combine(transport.state) { peripheralState, transportState ->
                    when (peripheralState) {
                        is BleConnectionState.Connecting -> ConnectionState.CONNECTING
                        is BleConnectionState.Connected -> when (transportState) {
                            is McuMgrBleTransport.State.Connected -> ConnectionState.READY
                            is McuMgrBleTransport.State.Initializing -> ConnectionState.INITIALIZING
                            // A device without a usable SMP service is not disconnected, so
                            // a disconnected transport on a connected device means just that.
                            is McuMgrBleTransport.State.Disconnected -> ConnectionState.NOT_SUPPORTED
                        }
                        is BleConnectionState.Disconnecting -> ConnectionState.DISCONNECTING
                        is BleConnectionState.Disconnected -> when (peripheralState.reason) {
                            is BleConnectionState.Disconnected.Reason.Timeout ->
                                ConnectionState.TIMEOUT
                            is BleConnectionState.Disconnected.Reason.RequiredServiceNotFound ->
                                ConnectionState.NOT_SUPPORTED
                            else -> ConnectionState.DISCONNECTED
                        }
                    }
                }
                .onEach { connectionStateLiveData.postValue(it) }
                .launchIn(viewModelScope)
        } else {
            // Any other transport only tells us whether it is connected.
            transport.addObserver(object : McuMgrTransport.ConnectionObserver {
                override fun onConnected() {
                    connectionStateLiveData.postValue(ConnectionState.READY)
                }

                override fun onDisconnected() {
                    connectionStateLiveData.postValue(ConnectionState.DISCONNECTED)
                }
            })
        }
        this.connectionState = connectionStateLiveData
        connectionState.observeForever(connectionStateObserver)

        observabilityManager.state
            .onEach { observabilityLiveData.postValue(it.state) }
            .launchIn(viewModelScope)

        (transport as? McuMgrBleTransport)?.peripheral?.bondState
            ?.onEach { bondStateLiveData.postValue(it) }
            ?.launchIn(viewModelScope)
    }

    override fun onCleared() {
        connectionState.removeObserver(connectionStateObserver)
        super.onCleared()
    }

    val bondState: LiveData<BondState>
        get() = bondStateLiveData

    val bufferParams: LiveData<McuMgrBufferParams?>
        get() = bufferLiveData

    val bootloaderName: LiveData<String?>
        get() = bootloaderNameLiveData

    val bootloaderMode: LiveData<Int?>
        get() = bootloaderModeLiveData

    val activeB0Slot: LiveData<Int?>
        get() = activeB0SlotLiveData

    val appInfo: LiveData<String?>
        get() = appInfoLiveData

    val otaInfo: LiveData<FeatureState<DeviceInfo>?>
        get() = otaLiveData

    val observabilityState: LiveData<ChunksEmitter.State?>
        get() = observabilityLiveData

    class McuMgrBufferParams {
        val size: Int
        val count: Int

        constructor(response: McuMgrParamsResponse) {
            size = response.bufSize
            count = response.bufCount
        }

        constructor(maxPacketLength: Int) {
            size = maxPacketLength
            count = 1
        }
    }

    /**
     * Reads the MCU Manager parameters.
     *
     * @param then a callback to be invoked when the parameters are read.
     */
    private fun readMcuMgrParams(then: Runnable?) {
        defaultManager.params(object : McuMgrCallback<McuMgrParamsResponse> {
            override fun onResponse(response: McuMgrParamsResponse) {
                bufferLiveData.postValue(McuMgrBufferParams(response))
                then?.run()
            }

            override fun onError(error: McuMgrException) {
                val maxPacketLength = (defaultManager.transporter as? McuMgrBleTransport)
                    ?.maxPacketLength
                bufferLiveData.postValue(maxPacketLength?.let { McuMgrBufferParams(it) })
                then?.run()
            }
        })
    }

    /**
     * Reads application info.
     *
     * @param format See [DefaultManager.appInfo] for details.
     * @noinspection SameParameterValue
     * @see DefaultManager.appInfo
     */
    @Suppress("SameParameterValue")
    private fun readAppInfo(format: String?, then: Runnable?) {
        defaultManager.appInfo(format, object : McuMgrCallback<McuMgrAppInfoResponse> {
            override fun onResponse(response: McuMgrAppInfoResponse) {
                appInfoLiveData.postValue(response.output)
                then?.run()
            }

            override fun onError(error: McuMgrException) {
                appInfoLiveData.postValue(null)
                then?.run()
            }
        })
    }

    /**
     * A callback to be invoked when the bootloader name is read.
     */
    private fun interface BootloaderNameCallback {
        fun onBootloaderNameReceived(bootloaderName: String?)
    }

    /**
     * Reads the name of the bootloader.
     *
     * @param then a callback to be invoked when the name is read.
     */
    private fun readBootloaderName(then: BootloaderNameCallback?) {
        defaultManager.bootloaderInfo(
            DefaultManager.BOOTLOADER_INFO_QUERY_BOOTLOADER,
            object : McuMgrCallback<McuMgrBootloaderInfoResponse> {
                override fun onResponse(response: McuMgrBootloaderInfoResponse) {
                    bootloaderNameLiveData.postValue(response.bootloader)
                    then?.onBootloaderNameReceived(response.bootloader)
                }

                override fun onError(error: McuMgrException) {
                    bootloaderNameLiveData.postValue(null)
                    then?.onBootloaderNameReceived(null)
                }
            })
    }

    /**
     * Reads the mode of the bootloader.
     * This method is only supported by MCUboot bootloader.
     *
     * @param then a callback to be invoked when the mode is read.
     * @noinspection SameParameterValue
     */
    @Suppress("SameParameterValue")
    private fun readMcuBootMode(then: Runnable?) {
        defaultManager.bootloaderInfo(
            DefaultManager.BOOTLOADER_INFO_MCUBOOT_QUERY_MODE,
            object : McuMgrCallback<McuMgrBootloaderInfoResponse> {
                override fun onResponse(response: McuMgrBootloaderInfoResponse) {
                    bootloaderModeLiveData.postValue(response.mode)
                    then?.run()
                }

                override fun onError(error: McuMgrException) {
                    bootloaderModeLiveData.postValue(null)
                    then?.run()
                }
            })
    }

    /**
     * Reads the ID active slot of the bootloader.
     * This method is only supported by MCUboot bootloader.
     *
     * @param then a callback to be invoked when the active slot is read.
     * @noinspection SameParameterValue
     */
    private fun readActiveSlot(then: Runnable?) {
        defaultManager.bootloaderInfo(
            DefaultManager.BOOTLOADER_INFO_QUERY_ACTIVE_B0_SLOT,
            object : McuMgrCallback<McuMgrBootloaderInfoResponse> {
                override fun onResponse(response: McuMgrBootloaderInfoResponse) {
                    activeB0SlotLiveData.postValue(response.activeB0Slot)
                    then?.run()
                }

                override fun onError(error: McuMgrException) {
                    activeB0SlotLiveData.postValue(null)
                    then?.run()
                }
            })
    }

    /**
     * For the purpose of nRF Cloud OTA read the device properties:
     * - Serial number
     * - Hardware version
     * - Software type
     * - Current software version
     * - Memfault Project key
     */
    private fun readOta(then: Runnable?) {
        memfaultManager.projectKey(object : McuMgrCallback<MemfaultProjectKeyResponse?> {
            override fun onResponse(response: MemfaultProjectKeyResponse) {
                val projectKey = response.projectKey

                memfaultManager.info(object : McuMgrCallback<MemfaultDeviceInfoResponse?> {
                    override fun onResponse(response: MemfaultDeviceInfoResponse) {
                        val deviceInfo = response.toDeviceInfo()

                        Timber.i("nRF Cloud OTA Supported: $deviceInfo, Project Key: $projectKey")
                        otaLiveData.postValue(FeatureState.Supported(deviceInfo))
                        then?.run()
                    }

                    override fun onError(error: McuMgrException) {
                        otaLiveData.postValue(FeatureState.NotSupported)
                        then?.run()
                    }
                })
            }

            override fun onError(error: McuMgrException) {
                // If Memfault group is not supported, check if the data were read
                // using Device Information Service (temporary, legacy solution, will be removed).
                val deviceInfo = legacyDeviceInfo.deviceInfo.value
                val featureState = if (legacyDeviceInfo.projectKey.value != null && deviceInfo != null) {
                    FeatureState.Supported(deviceInfo)
                } else {
                    FeatureState.NotSupported
                }
                otaLiveData.postValue(featureState)
                then?.run()
            }
        })

    }
}
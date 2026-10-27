/*
 * Copyright (c) 2022, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

@file:Suppress("unused")

package no.nordicsemi.android.observability.bluetooth

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.observability.ObservabilityManager
import no.nordicsemi.android.observability.data.ChunksEmitter
import no.nordicsemi.android.observability.data.ChunksConfig
import no.nordicsemi.android.observability.internal.map
import no.nordicsemi.android.observability.log.Category
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.android.BondState
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Manager
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment
import no.nordicsemi.kotlin.log.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A helper class that connects to a remote Bluetooth LE device using a [CentralManager], and
 * attaches [MonitoringAndDiagnosticsProfile] to it once connected.
 *
 * This class owns the whole connection lifecycle: connecting, auto-reconnecting, and monitoring
 * the bond state. Use it either:
 *  - with an existing [CentralManager] and [Peripheral], e.g. a mock manager for testing, or one
 *    that isn't otherwise connected by the application, or
 *  - with a [NativeAndroidEnvironment] and a [BluetoothDevice], for applications don't yet
 *    use Kotlin BLE Library.
 *
 * Applications that already connect and manage their own `Peripheral` elsewhere should instead
 * attach [MonitoringAndDiagnosticsProfile] directly to it, and don't need this class.
 *
 * @see MonitoringAndDiagnosticsProfile
 * @see ObservabilityManager
 */
class MonitoringAndDiagnosticsConnection : Log.IdentifiableEmitter<String> {

    /**
     * Creates a new instance of [MonitoringAndDiagnosticsConnection] with the given
     * [CentralManager] and [Peripheral].
     *
     * This constructor can be used with a 'native' or 'mock' [CentralManager], or with a
     * [Peripheral] that is not otherwise connected by the application.
     *
     * @param centralManager The central manager to use for connection.
     * @param peripheral The peripheral to connect to.
     * @param scope The coroutine scope.
     */
    constructor(
        centralManager: CentralManager,
        peripheral: Peripheral,
        scope: CoroutineScope,
    ) {
        this.scope = scope
        this.centralManager = centralManager
        this.peripheral = peripheral
    }

    /**
     * Creates a new instance of [MonitoringAndDiagnosticsConnection] with the given
     * [NativeAndroidEnvironment] and [BluetoothDevice].
     *
     * This constructor is for legacy applications that use the Android Bluetooth API.
     *
     * @param environment The native Android Environment object.
     * @param bluetoothDevice The Bluetooth device to connect to.
     * @param scope The coroutine scope.
     */
    constructor(
        environment: NativeAndroidEnvironment,
        bluetoothDevice: BluetoothDevice,
        scope: CoroutineScope,
    ) {
        this.scope = scope
        this.centralManager = CentralManager.native(environment, scope)
        this.peripheral = centralManager.getPeripheralById(bluetoothDevice.address)!!
    }

    /**
     * Represents the state of the device Bluetooth LE connection.
     */
    sealed class State {
        /** The device is currently connecting. */
        data object Connecting : State()
        /** The device is setting up Monitoring & Diagnostic Service. */
        data object Initializing : State()
        /**
         * The device is connected and set up to receive Diagnostic data.
         *
         * @property config The configuration obtained from the device using GATT.
         */
        data class Connected(val config: ChunksConfig) : State()
        /** The device is currently disconnecting. */
        data object Disconnecting : State()
        /** The device is disconnected. */
        data class Disconnected(val reason: Reason? = null) : State() {

            /**
             * Represents the reason for disconnection or failure to connect.
             */
            enum class Reason {
                FAILED_TO_CONNECT,
                NOT_SUPPORTED,
                BONDING_FAILED,
                CONNECTION_LOST,
                TIMEOUT,
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Disconnected) return false
                return true
            }

            override fun hashCode(): Int {
                return reason?.hashCode() ?: 0
            }
        }
    }

    /** The MAC address of the associated Peripheral. */
    override val identifier: String
        get() = peripheral.identifier

    /** The coroutine scope used for launching flows and coroutines. */
    private val scope: CoroutineScope
    private var job: Job? = null
    /** The central manager used to connect to the device. */
    private val centralManager: CentralManager
    /** The peripheral representing the device to connect to. */
    private val peripheral: Peripheral
    /** A flag set when no MDS service was found. */
    private var notSupported = true
    /** A flag set when the bonding process failed. */
    private var bondingFailed = false

    /** The Monitoring & Diagnostics Service profile attached to the peripheral. */
    private val mdsProfile = MonitoringAndDiagnosticsProfile()

    /**
     * The Monitoring & Diagnostics Service profile, exposing [no.nordicsemi.android.observability.data.ChunksEmitter.state] and
     * [no.nordicsemi.android.observability.data.ChunksEmitter.chunks], decoupled from this connection.
     *
     * This can be passed directly to [ObservabilityManager.connect].
     */
    val profile: ChunksEmitter = mdsProfile

    private val _state = MutableStateFlow<State>(State.Disconnected())

    /** The current state of the device, including the Bluetooth LE connection. */
    val state = _state.asStateFlow()
    /** A flow of streamed data received from the device. */
    val chunks = mdsProfile.chunks

    /**
     * The log sink for events produced by this connection and the attached
     * [MonitoringAndDiagnosticsProfile].
     */
    var logger: Log.Sink<Category>? = null
        set(value) {
            field = value
            mdsProfile.logger = value
        }

    /**
     * Starts the connection to the device and begins observing the Monitoring & Diagnostics Service.
     *
     * Use [close] to stop the connection and cancel all observers.
     */
    fun start() {
        if (job != null) { return }
        job = scope.launch {
            var connection: Job? = null

            // Start observing the central manager state.
            // If Bluetooth gets disabled, or the manager is closed, we cancel the inner scope
            // to cancel all flow observers.
            centralManager.state
                .onEach {
                    when (it) {
                        Manager.State.POWERED_ON -> {
                            // Central Manager is ready, connect or reconnect to the peripheral.
                            assert(connection == null) { "Connection already started" }
                            val handler = CoroutineExceptionHandler { _, throwable ->
                                logger?.error(Category.MDS, throwable)
                                _state.update { State.Disconnected(State.Disconnected.Reason.FAILED_TO_CONNECT) }
                                cancel()
                            }
                            connection = launch(handler) {
                                connect()
                            }
                        }
                        Manager.State.UNKNOWN -> {
                            // Central Manager was closed, cancel the scope.
                            // This will also cancel the connection if it was started.
                            cancel()
                        }
                        else -> {
                            // Cancel the connection.
                            // It will be restarted when the Central Manager state changes to POWERED_ON.
                            connection?.cancel()
                            connection = null
                        }
                    }
                }
                .launchIn(this)

            try { awaitCancellation() }
            finally {
                job = null
            }
        }
    }

    /**
     * Closes the open connection.
     *
     * If the connection is not started, this method does nothing.
     */
    fun close() {
        job?.cancel()
        job = null
    }

    /**
     * This method connects the peripheral and starts observing its state.
     *
     * It suspends until the scope is canceled.
     */
    private suspend fun CoroutineScope.connect(): Nothing {
        // Observe the peripheral bond state to catch bonding failures.
        var wasBonding = false
        peripheral.bondState
            .onEach { bondState ->
                when (bondState) {
                    // If bond state transitions from any of these...
                    BondState.Bonded,
                    BondState.Bonding -> wasBonding = true
                    // ... to None, it means that the bonding failed.
                    is BondState.None -> {
                        if (wasBonding) {
                            logger?.warn(Category.MDS) {
                                val reason = bondState.reason?.let { ": $it" } ?: ""
                                "Bonding failed: $reason"
                            }
                            // This will be reported as bond failure on disconnection.
                            bondingFailed = true
                            wasBonding = false
                        }
                    }
                }

            }
            .launchIn(this)

        // Observe the MDS profile state to detect whether reading its characteristics failed due
        // to a bonding issue, and to forward the Initializing / Connected states.
        mdsProfile.state
            .onEach { profileState ->
                when (profileState) {
                    ChunksEmitter.State.Initializing ->
                        _state.emit(State.Initializing)
                    is ChunksEmitter.State.Ready -> {
                        // Mark the peripheral as supported.
                        notSupported = false
                        _state.emit(State.Connected(profileState.config))
                    }
                    is ChunksEmitter.State.Disconnected -> {
                        // The Disconnected state itself is emitted by the peripheral state
                        // collector below, once the underlying BLE connection is dropped.
                    }
                }
            }
            .launchIn(this)

        // Start observing the peripheral state.
        peripheral.state
            // Skip the initial state.
            .drop(1)
            .onEach { state ->
                when (state) {
                    // Disconnected state is emitted when the connection is lost, when the device
                    // is not supported (disconnect() method called), or the connection was canceled
                    // by the user.
                    is ConnectionState.Disconnected -> {
                        if (state.reason is ConnectionState.Disconnected.Reason.UnsupportedAddress) {
                            // This error is thrown in AutoConnect connection when there is no
                            // bonding. The library will transition to Direct connection automatically.
                            // Don't report this state.
                            return@onEach
                        }
                        _state.emit(state.map(notSupported, bondingFailed))
                        if (state.isUserInitiated /* (includes not supported) */ ||
                            state.reason is ConnectionState.Disconnected.Reason.UnsupportedConfiguration) {
                            // If the disconnection was initiated using disconnect() method,
                            // it might have been canceled, or the device is not supported.
                            // Either way, cancel auto-reconnection by cancelling the scope.
                            cancel()
                        }
                    }

                    // For all other states, we just emit the state.
                    // Note, that ConnectionState.Connecting and ConnectionState.Connected
                    // emit State.Connecting state.
                    // States State.Initializing and State.Connected are emitted later,
                    // when the service is initialized. This is to make sure that not supported
                    // devices are not reported as connected.
                    else -> {
                        _state.emit(state.map())
                    }
                }
            }
            .launchIn(this)

        // Connect to the peripheral automatically when the manager is created.
        try {
            // If a device is not bonded, but is advertising with resolvable private address (RPA),
            // the AutoConnect option will fail throwing ConnectionFailedException.
            // On older phones it may just hang forever, hence the timeout.
            val isBonded = peripheral.hasBondInformation
            val timeout = if (isBonded) Duration.INFINITE else 5.seconds
            withTimeout(timeout) {
                centralManager.connect(
                    peripheral,
                    options = CentralManager.ConnectionOptions.AutoConnect(
                        automaticallyRequestHighestValueLength = true
                    )
                )
            }
        } catch (e: Exception) {
            logger?.warn(Category.MDS) { "Connection attempt failed, retrying" }

            // Try to connect directly. This should work with RPA.
            centralManager.connect(
                peripheral,
                options = CentralManager.ConnectionOptions.Direct(
                    automaticallyRequestHighestValueLength = true
                )
            )
        }

        // Attach the MDS profile. This connection exists solely to stream diagnostic data, so
        // the service is required: if it's missing, the library will disconnect with
        // RequiredServiceNotFound, which is mapped to Disconnected(NOT_SUPPORTED) above.
        peripheral.profile(
            scope = this,
            profile = mdsProfile,
            required = true,
        )

        try { awaitCancellation() }
        finally {
            // Make sure the device is disconnected when the scope is canceled.
            // When it was already disconnected, this is a no-op.
            withContext(NonCancellable) {
                peripheral.disconnect()

                // The state collection was canceled together with the scope. Emit the state manually.
                _state.emit(peripheral.state.value.map(notSupported, bondingFailed))
            }
        }
    }
}

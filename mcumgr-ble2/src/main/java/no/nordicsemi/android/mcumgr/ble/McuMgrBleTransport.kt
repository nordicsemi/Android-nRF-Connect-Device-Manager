@file:Suppress("unused")

package no.nordicsemi.android.mcumgr.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.mcumgr.McuMgrCallback
import no.nordicsemi.android.mcumgr.McuMgrHeader
import no.nordicsemi.android.mcumgr.McuMgrScheme
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.ble.exception.McuMgrBluetoothDisabledException
import no.nordicsemi.android.mcumgr.ble.exception.McuMgrDisconnectedException
import no.nordicsemi.android.mcumgr.ble.exception.McuMgrInsufficientAuthenticationException
import no.nordicsemi.android.mcumgr.ble.exception.McuMgrNotSupportedException
import no.nordicsemi.android.mcumgr.ble.exception.McuMgrUnsupportedConfigurationException
import no.nordicsemi.android.mcumgr.ble.util.ResultCondition
import no.nordicsemi.android.mcumgr.exception.InsufficientMtuException
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.exception.McuMgrTimeoutException
import no.nordicsemi.android.mcumgr.log.Category
import no.nordicsemi.android.mcumgr.log.McuMgrLogger
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.exception.ConnectionFailedException
import no.nordicsemi.kotlin.ble.client.exception.PeripheralNotConnectedException
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.exception.BluetoothUnavailableException
import no.nordicsemi.kotlin.log.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An implementation of [McuMgrTransport] for the [BLE][McuMgrScheme.BLE] scheme, built on the
 * Kotlin BLE Library.
 *
 * The transport owns the connection: it connects the device on the first request, attaches
 * [SmpProfile] to it, and disconnects on [release].
 *
 * An application that manages the same `Peripheral` for other purposes can keep doing so - the
 * profiles of the Kotlin BLE Library are independent of each other, so it may attach its own
 * alongside this one. It cannot, however, hand the SMP service to this transport: switching
 * a device to the Firmware Loader makes it advertise under a different address, which the
 * transport has to find and connect itself, so the connection has to be its own.
 *
 * ### Example
 * ```kotlin
 * val peripheral = centralManager.getPeripheralById(address)!!
 * val transport = McuMgrBleTransport(centralManager, peripheral, scope)
 * val manager = DefaultManager(transport)
 * ```
 *
 * @param centralManager The central manager used to connect to the device.
 * @param peripheral The peripheral to connect to and communicate with.
 * @param scope The coroutine scope the connection and the requests run in.
 */
class McuMgrBleTransport @JvmOverloads constructor(
    private val centralManager: CentralManager,
    peripheral: Peripheral,
    private val scope: CoroutineScope,
    uuidConfig: UuidConfig = DefaultMcuMgrUuidConfig,
) : McuMgrTransport {
    /**
     * The state of the transport.
     *
     * A Bluetooth LE transport reports more than the [ConnectionObserver][McuMgrTransport.ConnectionObserver]
     * does, as setting up a connection takes a noticeable amount of time: the device has to be
     * connected, its services discovered, and the SMP service set up.
     */
    sealed interface State {
        /** The transport cannot exchange SMP packets. */
        data object Disconnected : State
        /** The device is being connected, or its SMP service is being set up. */
        data object Initializing : State
        /** The device is connected and its SMP service is ready to exchange SMP packets. */
        data object Connected : State
    }

    /** The SMP service implementation, attached to the [target] once connected. */
    private val profile = SmpProfile(uuidConfig)

    /**
     * Whether the transport is establishing a connection, that is connecting the device,
     * discovering its services, or setting up the SMP service.
     *
     * The state of the SMP service alone cannot tell [Initializing][State.Initializing] from
     * [Disconnected][State.Disconnected], as a service that is closed does not say whether it
     * is still coming up; this does. It is set before connecting and cleared when the connection
     * ends, or as soon as the device turns out to have no usable SMP service.
     */
    private val initializing = MutableStateFlow(false)

    /** The current state of the transport. */
    val state: StateFlow<State> = combine(initializing, profile.state) { initializing, smp ->
        when {
            smp is SmpProfile.State.Open -> State.Connected
            initializing -> State.Initializing
            else -> State.Disconnected
        }
    }.stateIn(scope, SharingStarted.Eagerly, State.Disconnected)

    private companion object {
        /** How long to look for a device advertising under a given name in [changeMode]. */
        val SCAN_TIMEOUT = 20.seconds
    }

    /**
     * The device to talk to.
     *
     * This is the device given in the constructor, until [changeMode] points the transport
     * at the same device advertising under a different address, for example as the Firmware Loader.
     */
    private var target: Peripheral = peripheral

    /**
     * The device this transport is talking to.
     *
     * An application can use it to observe the connection, or to request a connection priority
     * and await the result. Note, that [changeMode] replaces it with the same device advertising
     * under a different address.
     */
    val peripheral: Peripheral
        get() = target

    /**
     * The logger of this transport, reporting under [Category.TRANSPORT], with the address of the
     * device given in the constructor as the source.
     */
    private val LOG = McuMgrLogger(Category.TRANSPORT, peripheral.identifier)

    private val observers = CopyOnWriteArrayList<McuMgrTransport.ConnectionObserver>()

    /** The connection attempt in progress, or the established connection. */
    private var connection: Connection? = null

    private class Connection(
        val job: Job,
        /** Completes when the SMP service is ready, or fails when the connection does. */
        val ready: CompletableDeferred<Unit>,
    )

    /**
     * The maximum length of an SMP packet the device can accept, or null when the transport
     * is [Disconnected][State.Disconnected].
     *
     * This is the size of the reassembly buffer on the device side, read with
     * [DefaultManager.params()][no.nordicsemi.android.mcumgr.managers.DefaultManager.params]
     * when the connection opens. Devices that do not report it accept a single write, that is
     * *ATT MTU - 3* bytes. Packets longer than this value fail with [InsufficientMtuException].
     */
    val maxPacketLength: Int?
        get() = profile.bufferSize

    /**
     * Sets the sink receiving the log entries emitted by this transport and the SMP service
     * attached to the device.
     *
     * The entries are reported under [Category.TRANSPORT] and [Category.PROTOCOL], with the
     * Bluetooth LE address of the device as the source. Set to null (the default) to stop logging.
     */
    fun setLogger(logger: Log.Sink<Category>?) {
        LOG.sink = logger
        profile.logger = logger
    }

    override fun getScheme(): McuMgrScheme = McuMgrScheme.BLE

    /**
     * Requests a connection priority, which is used to speed up a transfer.
     *
     * The request is sent on a best-effort basis and this method returns immediately: a failure
     * only means the transfer runs at the current connection interval. Callers that want to await
     * the result can use [Peripheral.requestConnectionPriority] instead.
     *
     * @param priority The requested priority, [ConnectionPriority.HIGH] by default.
     */
    @JvmOverloads
    fun requestConnectionPriority(priority: ConnectionPriority = ConnectionPriority.HIGH) {
        connect(object : McuMgrTransport.ConnectionCallback {
            override fun onConnected() {
                scope.launch {
                    try {
                        target.requestConnectionPriority(priority)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn { "Failed to request $priority connection priority: ${e.message}" }
                    }
                }
            }
            override fun onError(t: Throwable) {
                LOG.warn { "Failed to request $priority connection priority: ${t.message}" }
            }
        })
    }

    //*******************************************************************************************
    // Sending requests
    //*******************************************************************************************

    @Throws(McuMgrException::class)
    override fun <T : McuMgrResponse> send(
        payload: ByteArray,
        timeout: Long,
        responseType: Class<T>,
    ): T {
        val condition = ResultCondition<T>(false)
        send(payload, timeout, responseType, object : McuMgrCallback<T> {
            override fun onResponse(response: T) {
                condition.open(response)
            }

            override fun onError(error: McuMgrException) {
                condition.openExceptionally(error)
            }
        })
        return condition.block()
    }

    override fun <T : McuMgrResponse> send(
        payload: ByteArray,
        timeout: Long,
        responseType: Class<T>,
        callback: McuMgrCallback<T>,
    ) {
        require(payload.size >= McuMgrHeader.HEADER_LENGTH) { "Packet must contain at least 8-byte SMP header, but has ${payload.size} bytes" }
        // If the device is not connected, connect. When it is already connected, this returns
        // the established connection and awaiting it below completes immediately.
        val connection = ensureConnected()
        scope.launch {
            try {
                // Bounded by the caller's timeout, so that a request cannot wait for
                // a connection indefinitely. Note, that awaiting also throws when the device
                // turns out to have no usable SMP service.
                withTimeout(timeout.milliseconds) { connection.ready.await() }

                val data = profile.send(payload, timeout.milliseconds)
                val response = McuMgrResponse.buildResponse(McuMgrScheme.BLE, data, responseType)
                if (response.isSuccess) {
                    callback.onResponse(response)
                } else {
                    callback.onError(McuMgrErrorException(response))
                }
            } catch (e: TimeoutCancellationException) {
                callback.onError(McuMgrTimeoutException())
                throw e
            } catch (e: CancellationException) {
                callback.onError(McuMgrDisconnectedException())
                throw e
            } catch (e: Exception) {
                callback.onError(e.toMcuMgrException())
            }
        }
    }

    //*******************************************************************************************
    // Connection
    //*******************************************************************************************

    override fun connect(callback: McuMgrTransport.ConnectionCallback?) {
        // If the service is already up, report success without touching the connection.
        if (state.value is State.Connected) {
            callback?.onConnected()
            return
        }

        val connection = ensureConnected()
        if (callback == null) {
            return
        }
        scope.launch {
            try {
                connection.ready.await()
                callback.onConnected()
            } catch (e: CancellationException) {
                callback.onError(McuMgrDisconnectedException())
                throw e
            } catch (e: Exception) {
                callback.onError(e.toMcuMgrException())
            }
        }
    }

    /**
     * Returns the current connection, starting one if it is not running.
     */
    private fun ensureConnected(): Connection = synchronized(this) {
        connection?.takeIf { it.job.isActive }?.let { return it }

        val ready = CompletableDeferred<Unit>()
        val job = scope.launch {
            val peripheral = target
            // Set once the observers have been told the device is connected, so that a failed
            // connection attempt does not report a disconnection that never happened. The DFU
            // tasks treat onDisconnected() as a signal that a reset completed.
            var wasConnected = false
            try {
                // Report the transport as initializing before connecting, so that it never
                // looks disconnected while the device is connected - which is how a device
                // without a usable SMP service is told apart.
                initializing.value = true
                centralManager.connect(
                    peripheral = peripheral,
                    options = CentralManager.ConnectionOptions.Direct(
                        automaticallyRequestHighestValueLength = true,
                    ),
                )

                // Attach the SMP service handler. The profile is optional, so that a device
                // without the SMP service is not disconnected by the library: the transport
                // just stays closed and fails the requests.
                peripheral.profile(scope = this, profile = profile, required = false)

                // Open the transport when the SMP service becomes ready. This runs as a child
                // job, so that the disconnection below is awaited from the moment the device
                // connects, no matter how long resolving the service takes.
                launch {
                    try {
                        profile.awaitOpen()
                        wasConnected = true
                        notifyConnected()
                        ready.complete(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: McuMgrNotSupportedException) {
                        // The device has no usable SMP service. It stays connected, so that the
                        // application can still use it for other purposes, but every request
                        // fails until the transport is released.
                        ready.completeExceptionally(e)
                    } catch (e: Exception) {
                        LOG.error(e) { "Failed to open the SMP service" }
                        ready.completeExceptionally(e.toMcuMgrException())
                    } finally {
                        initializing.value = false
                    }
                }

                // Suspend until the device disconnects.
                val reason = peripheral.awaitDisconnection()
                ready.completeExceptionally(reason.toMcuMgrException())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ready.completeExceptionally(e.toMcuMgrException())
            } finally {
                // Fail anyone still waiting for the service. This is a no-op when it is
                // already completed.
                ready.completeExceptionally(McuMgrDisconnectedException())
                if (wasConnected) {
                    notifyDisconnected()
                }
                initializing.value = false
                peripheral.disconnect()
                // Make sure all child coroutines are closed.
                cancel("Connection closed")
            }
        }
        return Connection(job, ready).also { connection = it }
    }

    override fun release() {
        synchronized(this) {
            // Cancelling the job disconnects the device.
            connection?.job?.cancel()
            connection = null
        }
    }

    override fun changeMode(name: String, callback: McuMgrTransport.ModeChangeCallback?): Boolean {
        // The transport has to be disconnected in order to change the mode, as the device
        // advertises under a different address in the new mode.
        if (target.isConnected) {
            // The Scan task ignores the value returned here and waits only for the callback,
            // so a failure has to be reported through it as well, or the upgrade would stall.
            LOG.warn { "Cannot change mode while connected" }
            callback?.onError(McuMgrException("Cannot change mode while the device is connected"))
            return false
        }

        scope.launch {
            try {
                LOG.info { "Looking for a device advertising as '$name'..." }
                val result = centralManager
                    .scan(timeout = SCAN_TIMEOUT) { Name(name) }
                    .first()
                LOG.info { "Found '$name' at ${result.peripheral.identifier}" }
                target = result.peripheral
                callback?.onModeChanged()
            } catch (e: CancellationException) {
                callback?.onError(McuMgrDisconnectedException())
                throw e
            } catch (e: Exception) {
                LOG.error(e) { "Device advertising as 'name' not found" }
                callback?.onError(e.toMcuMgrException())
            }
        }
        return true
    }

    //*******************************************************************************************
    // Connection observers
    //*******************************************************************************************

    override fun addObserver(observer: McuMgrTransport.ConnectionObserver) {
        observers.add(observer)
    }

    override fun removeObserver(observer: McuMgrTransport.ConnectionObserver) {
        observers.remove(observer)
    }

    private fun notifyConnected() {
        observers.forEach { val _ = runCatching { it.onConnected() } }
    }

    private fun notifyDisconnected() {
        observers.forEach { val _ = runCatching { it.onDisconnected() } }
    }

    /**
     * Maps a Kotlin BLE Library failure to the matching Mcu Manager exception, so that callers
     * can tell a device that does not support SMP from one that dropped the link.
     */
    private fun Throwable.toMcuMgrException(): McuMgrException = when (this) {
        is McuMgrException -> this
        is CancellationException -> McuMgrDisconnectedException()
        is BluetoothUnavailableException -> McuMgrBluetoothDisabledException()
        is ConnectionFailedException -> reason.toMcuMgrException()
        is PeripheralNotConnectedException -> McuMgrDisconnectedException()
        else -> McuMgrException(this)
    }

    /**
     * Maps a disconnection reason to the matching Mcu Manager exception.
     */
    private fun ConnectionState.Disconnected.Reason?.toMcuMgrException(): McuMgrException =
        when (this) {
            is ConnectionState.Disconnected.Reason.InsufficientAuthentication ->
                McuMgrInsufficientAuthenticationException()
            is ConnectionState.Disconnected.Reason.UnsupportedConfiguration ->
                McuMgrUnsupportedConfigurationException()
            is ConnectionState.Disconnected.Reason.RequiredServiceNotFound ->
                McuMgrNotSupportedException()
            is ConnectionState.Disconnected.Reason.Timeout ->
                McuMgrTimeoutException()
            else -> McuMgrDisconnectedException()
        }
}

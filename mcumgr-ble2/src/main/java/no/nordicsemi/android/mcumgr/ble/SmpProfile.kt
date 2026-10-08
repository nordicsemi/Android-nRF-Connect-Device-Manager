@file:Suppress("unused")

package no.nordicsemi.android.mcumgr.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import no.nordicsemi.android.mcumgr.McuMgrHeader
import no.nordicsemi.android.mcumgr.McuMgrScheme
import no.nordicsemi.android.mcumgr.ble.callback.SmpProtocolSession
import no.nordicsemi.android.mcumgr.ble.callback.SmpTransaction
import no.nordicsemi.android.mcumgr.ble.exception.McuMgrDisconnectedException
import no.nordicsemi.android.mcumgr.ble.exception.McuMgrNotSupportedException
import no.nordicsemi.android.mcumgr.exception.InsufficientMtuException
import no.nordicsemi.android.mcumgr.log.Category
import no.nordicsemi.android.mcumgr.log.McuMgrLogger
import no.nordicsemi.android.mcumgr.managers.DefaultManager
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrParamsResponse
import no.nordicsemi.android.mcumgr.util.CBOR
import no.nordicsemi.kotlin.ble.client.Peripheral
import no.nordicsemi.kotlin.ble.client.Profile
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.core.util.MergeResult
import no.nordicsemi.kotlin.ble.core.util.chunked
import no.nordicsemi.kotlin.ble.core.util.mergeIndexed
import no.nordicsemi.kotlin.log.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * An implementation of the Simple Management Protocol (SMP) service.
 *
 * Once attached, it subscribes to the SMP characteristic, reassembles the responses that the
 * device splits over several notifications, and matches each of them to the request awaiting it.
 *
 * @param uuidConfig A custom set of SMP UUIDs, [DefaultMcuMgrUuidConfig] by default.
 */
internal class SmpProfile @JvmOverloads constructor(
    private val uuidConfig: UuidConfig = DefaultMcuMgrUuidConfig,
) : Profile.Simple(
    serviceUuid = uuidConfig.serviceUuid,
    name = "SMP",
) {
    /** The state of the SMP service. */
    sealed interface State {
        /** The SMP service is ready to exchange packets. */
        data object Open : State
        /** The SMP service is not available. */
        data object Closed : State
    }

    /** The state of the SMP service on the device this profile is attached to. */
    val state: StateFlow<State>
        field = MutableStateFlow<State>(State.Closed)

    /** The maximum length of an SMP packet the device can accept, or null when [Closed][State.Closed]. */
    val bufferSize: Int?
        get() = openService?.bufferSize

    /**
     * The sink receiving the log entries emitted by this profile.
     *
     * Entries are reported under [Category.TRANSPORT] for the Bluetooth LE events, and under
     * [Category.PROTOCOL] for the SMP framing. Set to null (the default) to stop logging.
     */
    var logger: Log.Sink<Category>? = null
        set(value) {
            field = value
            transportLog?.sink = value
        }

    /** The outcome of resolving the SMP service on the connected device, see [resolution]. */
    private enum class Resolution {
        /** The services of the device are not known yet. */
        Unknown,
        /** The SMP service is ready to exchange packets. */
        Open,
        /** The device does not have a usable SMP service. */
        Unsupported,
    }

    /**
     * The outcome of resolving the SMP service on the connected device.
     *
     * Unlike [state], this tells a device that has no usable SMP service from one whose services
     * are not known yet, which is what [awaitOpen] needs in order to fail instead of waiting for
     * a service that is never going to appear. It is [Unknown][Resolution.Unknown] again as soon
     * as the device disconnects or its services get invalidated, so that the outcome for one
     * connection is never reported for the next one.
     */
    private val resolution = MutableStateFlow(Resolution.Unknown)

    /**
     * The SMP service, while it is open.
     *
     * @property peripheral The device the service belongs to.
     * @property characteristic The SMP characteristic, used to send the requests.
     * @property session The session matching the SMP responses to the requests.
     * @property bufferSize The maximum length of an SMP packet the device can reassemble.
     */
    private class OpenService(
        private val peripheral: Peripheral<*, *>,
        val characteristic: RemoteCharacteristic,
        val session: SmpProtocolSession,
        val bufferSize: Int,
    ) {
        /** The maximum number of bytes that can be written at once, that is *ATT MTU - 3*. */
        val maxWriteLength: Int
            get() = peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE)
    }

    /**
     * The bytes of [DefaultManager.params] command.
     */
    private val readMcuMgrParams = byteArrayOf(
        0x00,           // McuManager.OP_READ
        0x00,           // Flags
        0x00, 0x01,     // Len
        0x00, 0x00,     // McuManager.GROUP_DEFAULT
        0xFF.toByte(),  // Seq
        0x06,           // DefaultManager.ID_MCUMGR_PARAMS
        0xA0.toByte(),  // Empty map(0) - an empty CBOR may be required for some implementations,
                        // otherwise the request is ignored and no notification is replied.
    )
    /** The open SMP service, or null when it is not available. */
    private var openService: OpenService? = null
    /** The SMP characteristic, if the service has a usable one, see [prepare]. */
    private lateinit var smpCharacteristic: RemoteCharacteristic
    /** Logger used by the profile. */
    private var transportLog: McuMgrLogger? = null

    /**
     * Suspends until the SMP service is open and ready to exchange packets.
     *
     * When the device is not connected, or its services have not been resolved yet, this
     * suspends until they are.
     *
     * @throws McuMgrNotSupportedException if the connected device does not have a usable
     * SMP service.
     */
    @Throws(McuMgrNotSupportedException::class)
    suspend fun awaitOpen() {
        if (resolution.first { it != Resolution.Unknown } == Resolution.Unsupported) {
            throw McuMgrNotSupportedException()
        }
    }

    override fun prepare(
        peripheral: Peripheral<*, *>,
        service: RemoteService
    ) {
        smpCharacteristic = service.characteristics
            .find { it.uuid == uuidConfig.characteristicUuid }
            .let { requireNotNull(it) { "SMP characteristic not found" } }
            .also { require(it.isWritable()) { "SMP characteristic does not have WRITE property" } }
            .also { require(it.isSubscribable()) { "SMP characteristic does not have NOTIFY property" } }

        val _ = logFor(peripheral)
    }

    override suspend fun CoroutineScope.initialize(peripheral: Peripheral<*, *>) {
        // SMP session is used to match SMP responses to requests.
        // This is used if multiple requests are sent (with different SEQ numbers) before
        // the previous response is received.
        val session = SmpProtocolSession(this, logger)

        // The SMP packets can be reassembled using the Length field in the SMP Header.
        // Note: SAR = Segmentation and Reassembly
        val sar: suspend (ByteArray, ByteArray, Int) -> MergeResult = { accumulated, received, index ->
            // In case of the first packet, check if it contains at least the SMP header.
            if (index == 0 && received.size < McuMgrHeader.HEADER_LENGTH) {
                transportLog?.error { "SMP packet is too short: ${received.size} < ${McuMgrHeader.HEADER_LENGTH}; dropping" }
                // Note, that this will effectively drop the entire message,
                // as the empty arrays are filtered out below.
                MergeResult.Completed(byteArrayOf())
            } else {
                // Merge the new packet with the accumulated one.
                val merged = accumulated + received
                // The expected length is encoded in Big Endian from offset 2 in the header.
                val expectedLength = expectedLengthOf(merged) + McuMgrHeader.HEADER_LENGTH
                if (merged.size >= expectedLength) {
                    if (merged.size > expectedLength) {
                        transportLog?.warn { "SMP packet is larger than expected: ${merged.size} > $expectedLength" }
                        // TODO Should it be trimmed? Dropped?
                    }
                    MergeResult.Completed(merged)
                } else {
                    MergeResult.Accumulate(merged)
                }
            }
        }

        try {
            val outcome = try {
                // To initialize the SMP protocol we have to read the Mcu Manager params and enable
                // notifications on the SMP characteristic. The Mcu Manager params contain the size
                // of the reassembly buffer on the device side. With this information, the sender
                // can use SAR to increase throughput. Long messages, like files or firmware images,
                // can be split into long packets, so less time is used to send SMP headers. The longer
                // packets, the better data-metadata ratio.
                transportLog?.trace { "Obtaining Mcu Manager parameters..." }
                val bufferSize = smpCharacteristic
                    .waitForValueChange(
                        merge = sar,
                        trigger = {
                            smpCharacteristic.write(readMcuMgrParams, WriteType.WITHOUT_RESPONSE)
                        }
                    )
                    .let { bytes ->
                        try {
                            val response = McuMgrResponse.buildResponse(
                                McuMgrScheme.BLE,
                                bytes,
                                McuMgrParamsResponse::class.java
                            )
                            transportLog?.info { "SMP reassembly supported with buffer size: ${response.bufSize} bytes, and count: ${response.bufCount}" }
                            response.bufSize
                        } catch (e: Exception) {
                            transportLog?.warn { "Failed to parse Mcu Manager parameters response: ${e.message}" }
                            peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE)
                        }
                    }

                transportLog?.trace { "Enabling SMP notifications..." }
                val deferred = CompletableDeferred<Unit>()
                smpCharacteristic
                    .subscribe {
                        transportLog?.info { "SMP notifications enabled" }
                        deferred.complete(Unit)
                    }
                    // Each SMP message may come segmented into multiple notifications.
                    // Reassemble SMP packets from notifications, if needed.
                    .mergeIndexed(sar)
                    // This will catch an exception thrown when subscribe fails,
                    // i.e. OperationFailedException(reason=Subscribe not permitted)
                    .catch { deferred.completeExceptionally(it) }
                    .filter { it.isNotEmpty() }
                    .onEach { session.receive(it) }
                    .launchIn(this)

                // Make sure the notifications are enabled before reporting the service as open.
                deferred.await()

                openService = OpenService(peripheral, smpCharacteristic, session, bufferSize)
                transportLog?.info { "SMP service ready" }
                Resolution.Open
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The service is there, but it cannot be used. As this profile is attached as
                // optional, the device is not disconnected: report the same outcome as for
                // a device that does not have the service at all.
                transportLog?.warn(e) { "SMP service failed to open" }
                Resolution.Unsupported
            }

            // Hold the outcome until the device disconnects, or its services get invalidated.
            resolve(outcome)
        } finally {
            val wasOpen = openService != null
            openService = null
            withContext(NonCancellable) {
                // Fail every request that is still awaiting a response.
                session.close(McuMgrDisconnectedException())
                if (wasOpen) {
                    transportLog?.info { "SMP Service stopped" }
                }
            }
        }
    }

    override suspend fun unsupported(peripheral: Peripheral<*, *>) {
        // Either the SMP service is not there, or prepare() rejected it, in which case the
        // reason has already been logged by the Bluetooth LE library.
        logFor(peripheral).warn { "SMP service not supported" }
        resolve(Resolution.Unsupported)
    }

    override suspend fun failed(
        peripheral: Peripheral<*, *>,
        reason: RemoteServices.Failed.Reason
    ) {
        // The device may well have the SMP service, but as long as its services cannot be
        // discovered it cannot be used, which for a caller is the same as not having it.
        logFor(peripheral).warn { "Service discovery failed: $reason" }
        resolve(Resolution.Unsupported)
    }

    /**
     * Reports the given outcome of resolving the SMP service and holds it until this profile gets
     * detached, that is until the device disconnects or its services get invalidated.
     */
    private suspend fun resolve(outcome: Resolution) {
        // Publish the state before the resolution, as the latter resumes whoever awaits it:
        // a caller woken by awaitOpen() must find the state already reported.
        state.value = if (outcome == Resolution.Open) State.Open else State.Closed
        resolution.value = outcome
        try {
            awaitCancellation()
        } finally {
            state.value = State.Closed
            resolution.value = Resolution.Unknown
        }
    }

    /**
     * Returns the logger reporting under [Category.TRANSPORT], with the address of the given
     * device as the source, so that entries coming from several devices can be told apart.
     */
    private fun logFor(peripheral: Peripheral<*, *>): McuMgrLogger =
        McuMgrLogger(Category.TRANSPORT, peripheral.identifier.toString())
            .also { it.sink = logger }
            .also { transportLog = it }

    /**
     * Reads the length of the SMP packet from the SMP header.
     *
     * The byte array must be a valid partial or complete SMP packet, that is it must start from
     * 8-byte SMP header.
     * @see McuMgrHeader
     */
    private fun expectedLengthOf(packet: ByteArray): Int {
        // Length is encoded in Big Endian from offset 2.
        return (packet[2].toInt() and 0xFF shl 8) or (packet[3].toInt() and 0xFF)
    }

    /**
     * Sends an SMP request and awaits a response with a matching sequence number.
     *
     * @param payload The request packet, including the SMP header.
     * @param timeout The response timeout.
     * @throws McuMgrDisconnectedException if the service is not open.
     * @throws InsufficientMtuException if the packet is longer than the device can accept.
     * @throws IllegalStateException if the queue is full.
     */
    @Throws(
        McuMgrDisconnectedException::class,
        InsufficientMtuException::class,
        IllegalStateException::class,
    )
    suspend fun send(
        payload: ByteArray,
        timeout: Duration,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val service = openService ?: run {
            continuation.resumeWithException(McuMgrDisconnectedException())
            return@suspendCancellableCoroutine
        }
        // A packet longer than the reassembly buffer on the device side cannot be sent at all.
        if (payload.size > service.bufferSize) {
            continuation.resumeWithException(
                InsufficientMtuException(payload.size, service.bufferSize)
            )
            return@suspendCancellableCoroutine
        }

        transportLog?.info {
            val headerAsString = McuMgrHeader.fromBytes(payload)
            try {
                "Sending (${payload.size} bytes) $headerAsString CBOR ${CBOR.toString(payload, McuMgrHeader.HEADER_LENGTH)}"
            } catch (e: Exception) {
                "Sending (${payload.size} bytes) $headerAsString CBOR invalid"
            }
        }

        service.session.send(payload, timeout, object : SmpTransaction {
            override suspend fun send(data: ByteArray) {
                try {
                    // A single write cannot be longer than ATT MTU - 3 bytes, so longer packets
                    // are split here and reassembled by the device. Note, that write() trims
                    // the value instead of splitting it, hence the chunks.
                    for (chunk in data.chunked(service.maxWriteLength)) {
                        service.characteristic.write(chunk, WriteType.WITHOUT_RESPONSE)
                    }
                } catch (e: CancellationException) {
                    continuation.cancel(e)
                    return
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                    return
                }
            }

            override fun onResponse(data: ByteArray) {
                transportLog?.info {
                    val headerAsString = McuMgrHeader.fromBytes(data)
                    try {
                        "Received (${data.size} bytes) $headerAsString CBOR ${CBOR.toString(data, McuMgrHeader.HEADER_LENGTH)}"
                    } catch (e: Exception) {
                        "Received (${data.size} bytes) $headerAsString CBOR invalid"
                    }
                }

                continuation.resume(data)
            }

            override fun onFailure(e: Throwable) = continuation.resumeWithException(e)
        })
    }
}

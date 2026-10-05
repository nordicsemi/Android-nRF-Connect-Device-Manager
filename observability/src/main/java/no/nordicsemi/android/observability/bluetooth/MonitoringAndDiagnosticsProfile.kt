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

@file:OptIn(ExperimentalUuidApi::class)
@file:Suppress("unused")

package no.nordicsemi.android.observability.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import no.nordicsemi.android.observability.ObservabilityManager
import no.nordicsemi.android.observability.data.ChunksConfig
import no.nordicsemi.android.observability.data.ChunksEmitter
import no.nordicsemi.android.observability.internal.AuthorisationHeader
import no.nordicsemi.android.observability.internal.shortened
import no.nordicsemi.android.observability.log.Category
import no.nordicsemi.kotlin.ble.client.Profile
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.Peripheral
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.log.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Monitoring & Diagnostics Service UUID.
 *
 * Find specification ih the [Documentation](https://docs.memfault.com/docs/mcu/mds).
 */
val MDS_SERVICE_UUID = Uuid.parse("54220000-f6a5-4007-a371-722f4ebd8436")

private val MDS_SUPPORTED_FEATURES_CHARACTERISTIC_UUID = Uuid.parse("54220001-f6a5-4007-a371-722f4ebd8436")
private val MDS_DEVICE_ID_CHARACTERISTIC_UUID          = Uuid.parse("54220002-f6a5-4007-a371-722f4ebd8436")
private val MDS_DATA_URI_CHARACTERISTIC_UUID           = Uuid.parse("54220003-f6a5-4007-a371-722f4ebd8436")
private val MDS_AUTHORISATION_CHARACTERISTIC_UUID      = Uuid.parse("54220004-f6a5-4007-a371-722f4ebd8436")
private val MDS_DATA_EXPORT_CHARACTERISTIC_UUID        = Uuid.parse("54220005-f6a5-4007-a371-722f4ebd8436")

/**
 * An implementation of the Monitoring & Diagnostics Service (MDS) that streams
 * diagnostic data from the collector over Bluetooth Low Energy.
 *
 * This profile can be attached to any connected `Peripheral` using [Peripheral.profile].
 * It does not create or manage the Bluetooth LE connection itself.
 *
 * Once attached, it reads the device configuration, subscribes to the data export characteristic,
 * and exposes the result using [state] and [chunks], which can be consumed directly, or passed to
 * [ObservabilityManager.connect].
 *
 * ### Example
 * ```kotlin
 * // Create the profile and attach it to the Peripheral object.
 * val profile = MonitoringAndDiagnosticsProfile()
 * peripheral.profile(profile)
 * // Connect the profile with observability manager to upload chunks to nRF Cloud.
 * observabilityManager.connect(profile)
 * ```
 */
open class MonitoringAndDiagnosticsProfile : Profile.Simple(
    serviceUuid = MDS_SERVICE_UUID,
    name = "MDS",
), ChunksEmitter {
    private lateinit var deviceIdCharacteristic: RemoteCharacteristic
    private lateinit var dataUriCharacteristic: RemoteCharacteristic
    private lateinit var authorisationCharacteristic: RemoteCharacteristic
    private lateinit var dataExportCharacteristic: RemoteCharacteristic

    private var scope: CoroutineScope? = null

    private val _state =
        MutableStateFlow<ChunksEmitter.State>(ChunksEmitter.State.Disconnected)
    private val _chunks = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 25,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    override val state: StateFlow<ChunksEmitter.State> = _state.asStateFlow()
    override val chunks: SharedFlow<ByteArray> = _chunks.asSharedFlow()
    override var logger: Log.Sink<Category>? = null

    /**
     * Closes the
     */
    internal fun close() {
        scope?.cancel()
    }

    override fun prepare(peripheral: Peripheral<*, *>, service: RemoteService) {
        deviceIdCharacteristic = service.deviceIdCharacteristic
        dataUriCharacteristic = service.dataUriCharacteristic
        authorisationCharacteristic = service.authorisationCharacteristic
        dataExportCharacteristic = service.dataExportCharacteristic
    }

    override suspend fun CoroutineScope.initialize(peripheral: Peripheral<*, *>) {
        scope = this
        _state.value = ChunksEmitter.State.Initializing

        try {
            logger?.v(peripheral) { "Reading Monitoring & Diagnostics Service configuration..." }
            // Read and emit device configuration.
            val deviceId = deviceIdCharacteristic.read().let { String(it) }
            logger?.i(peripheral) { "Serial number: $deviceId" }
            val url = dataUriCharacteristic.read().let { String(it) }
            logger?.i(peripheral) { "Data URL: $url" }
            val authorisationToken = authorisationCharacteristic.read().let { AuthorisationHeader.parse(it) }
            // Note: If debug logging is enabled in the Peripheral, the key may be logged as bytes
            //       on DEBUG level. However, this is a public, write-only key. No big deal.
            logger?.i(peripheral) { "Project Key: ${authorisationToken.shortened()}" }

            // Start listening to data collected by the device.
            logger?.v(peripheral) { "Enabling Data export notifications..." }
            val deferred = CompletableDeferred<Unit>()
            dataExportCharacteristic
                // Subscribe and enable notifications (on collection).
                .subscribe {
                    logger?.i(peripheral) { "Data export notifications enabled" }
                    deferred.complete(Unit)
                }
                // This will catch an exception thrown when subscribe fails,
                // i.e. OperationFailedException(reason=Subscribe not permitted)
                .catch { deferred.completeExceptionally(it) }
                // Emit the chunks to the flow.
                .onEach { _chunks.emit(it) }
                .launchIn(this)

            // Make sure the notifications are enabled and subscribed to before proceeding.
            deferred.await()

            // Set the state to Ready, so the chunk observer is set up before streaming is started.
            _state.value = ChunksEmitter.State.Ready(ChunksConfig(authorisationToken, url, deviceId))

            // Enable notifications for the data export characteristic.
            logger?.v(peripheral) { "Enabling steaming..." }
            val enableStreamingCommand = byteArrayOf(0x01)
            dataExportCharacteristic.write(enableStreamingCommand, WriteType.WITH_RESPONSE)

            logger?.i(peripheral) { "Monitoring & Diagnostics Service started successfully" }

            // Await scope cancellation to reset the state to Disconnected.
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.w(peripheral) { "Monitoring & Diagnostics Service failed to start" }
            cancel(CancellationException(e))
        } finally {
            // If the Project Key was invalid, the MDS profile will be closed.
            // The underlying peripheral may still be connected, so let's make sure
            // the Data Export characteristic is disabled.
            withContext(NonCancellable) {
                try {
                    if (peripheral.isConnected) {
                        logger?.v(peripheral) { "Disabling Data export notifications..." }
                        dataExportCharacteristic.setNotifying(false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Ignore
                }
                logger?.i(peripheral) { "Monitoring & Diagnostics Service stopped" }
                _state.value = ChunksEmitter.State.Disconnected
            }
        }
    }

    private fun Log.Sink<Category>.v(peripheral: Peripheral<*, *>, t: Throwable? = null, message: () -> String) {
        log(Category.MDS, Log.Level.TRACE, peripheral.identifier.toString(), t, message)
    }

    private fun Log.Sink<Category>.i(peripheral: Peripheral<*, *>, t: Throwable? = null, message: () -> String) {
        log(Category.MDS, Log.Level.INFO, peripheral.identifier.toString(), t, message)
    }

    private fun Log.Sink<Category>.w(peripheral: Peripheral<*, *>, t: Throwable? = null, message: () -> String) {
        log(Category.MDS, Log.Level.WARN, peripheral.identifier.toString(), t, message)
    }

    @Suppress("unused")
    private val RemoteService.supportedFeaturesCharacteristic
        get() = characteristics
            .find { it.uuid == MDS_SUPPORTED_FEATURES_CHARACTERISTIC_UUID }
            .let { requireNotNull(it) { "Supported Features characteristic not found" } }
            .also { require(it.isReadable()) { "Supported Features characteristic does not have READ property" } }

    private val RemoteService.deviceIdCharacteristic
        get() = characteristics
            .find { it.uuid == MDS_DEVICE_ID_CHARACTERISTIC_UUID }
            .let { requireNotNull(it) { "Device ID characteristic not found" } }
            .also { require(it.isReadable()) { "Device ID characteristic does not have READ property" } }

    private val RemoteService.dataUriCharacteristic
        get() = characteristics
            .find { it.uuid == MDS_DATA_URI_CHARACTERISTIC_UUID }
            .let { requireNotNull(it) { "Data URI characteristic not found" } }
            .also { require(it.isReadable()) { "Data URI characteristic does not have READ property" } }

    private val RemoteService.authorisationCharacteristic
        get() = characteristics
            .find { it.uuid == MDS_AUTHORISATION_CHARACTERISTIC_UUID }
            .let { requireNotNull(it) { "Authorisation characteristic not found" } }
            .also { require(it.isReadable()) { "Authorisation characteristic does not have READ property" } }

    private val RemoteService.dataExportCharacteristic
        get() = characteristics
            .find { it.uuid == MDS_DATA_EXPORT_CHARACTERISTIC_UUID }
            .let { requireNotNull(it) { "Data Export characteristic not found" } }
            .also { require(it.isWritable()) { "Data Export characteristic does not have WRITE property" } }
            .also { require(it.isSubscribable()) { "Data Export characteristic does not have NOTIFY property" } }
}

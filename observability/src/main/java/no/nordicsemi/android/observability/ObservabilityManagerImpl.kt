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

package no.nordicsemi.android.observability

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nordicsemi.android.observability.bluetooth.MonitoringAndDiagnosticsService
import no.nordicsemi.android.observability.bluetooth.MonitoringAndDiagnosticsService.State.Connected
import no.nordicsemi.android.observability.bluetooth.MonitoringAndDiagnosticsService.State.Connecting
import no.nordicsemi.android.observability.bluetooth.MonitoringAndDiagnosticsService.State.Disconnected
import no.nordicsemi.android.observability.data.PersistentChunkQueue
import no.nordicsemi.android.observability.internal.Scope
import no.nordicsemi.android.observability.internet.ChunkManager
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import kotlin.time.Duration.Companion.milliseconds

internal class ObservabilityManagerImpl(
    context: Context,
) : ObservabilityManager {
    /** The Application Context. */
    private val context = context.applicationContext

    private val _state = MutableStateFlow(ObservabilityManager.State())
    override val state: StateFlow<ObservabilityManager.State> = _state.asStateFlow()

    private var service: MonitoringAndDiagnosticsService? = null
    private var chunkQueue: PersistentChunkQueue? = null
    private var uploadManager: ChunkManager? = null

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override fun connect(peripheral: Peripheral, centralManager: CentralManager) {
        check(service == null) { "Already connected to a peripheral" }

        // Set up the collector for observable chunks from the device.
        Scope.launch {
            service = MonitoringAndDiagnosticsService(centralManager, peripheral, this)
                .apply {
                    var connection: Job? = null
                    // Collect the state of the device and update the state flow.
                    state
                        .drop(1) // Skip initial Disconnected state.
                        .onEach { state ->
                            _state.value = _state.value.copy(state = state)

                            if (state is Connected) {
                                assert(connection?.isCancelled ?: true) {
                                    "Connection scope should be null or cancelled when the config is received"
                                }

                                connection = launch {
                                    chunkQueue = PersistentChunkQueue(
                                        context = context,
                                        deviceId = state.config.deviceId
                                    ).also { queue ->
                                        queue.chunks
                                            .onEach {
                                                _state.value = _state.value.copy(chunks = it)
                                            }
                                            .launchIn(this)
                                    }
                                    uploadManager = ChunkManager(
                                        config = state.config,
                                        chunkQueue = chunkQueue
                                    ).also { manager ->
                                        manager.status
                                            .onEach {
                                                _state.value = _state.value.copy(uploadingState = it)
                                            }
                                            .launchIn(this)
                                        // Upload any chunks that were already in the queue.
                                        manager.uploadChunks()
                                    }

                                    try {
                                        awaitCancellation()
                                    } finally {
                                        // Manager is closing. Flush any remaining chunks.
                                        withContext(NonCancellable) {
                                            uploadManager?.uploadChunks()
                                            uploadManager?.close()
                                            uploadManager = null
                                        }

                                        // Remove all uploaded chunks from the queue.
                                        chunkQueue?.deleteUploaded()
                                        chunkQueue = null
                                    }
                                }
                            }
                            if (state is Connecting) {
                                // Otherwise, the device must have been disconnected.
                                connection?.cancel()
                                connection = null
                            }
                            if (state is Disconnected) {
                                cancel()
                            }
                        }
                        .launchIn(this@launch)

                    // Collect the chunks received from the device and upload them to the cloud.
                    chunks
                        .onEach {
                            // Mind, that that has to be called from a non-main Dispatcher
                            withContext(NonCancellable) {
                                chunkQueue?.addChunks(listOf(it))
                            }
                        }
                        // Don't upload chunks immediately, but debounce the flow to avoid
                        // multiple uploads in a short time.
                        .debounce(300.milliseconds)
                        .onEach {
                            uploadManager?.uploadChunks()
                        }
                        .launchIn(this@launch)
                }

            // Start the MDS service handler to connect to the device and start receiving data.
            service?.start()

            // Wait for disconnection.
            try { awaitCancellation() }
            finally {
                // Clean up the BLE manager when the scope is cancelled.
                service?.close()
                service = null
            }
        }
    }

    override fun disconnect() {
        service?.close()
        service = null
    }
}

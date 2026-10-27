/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx

import no.nordicsemi.android.mcumgr.McuMgrCallback
import no.nordicsemi.android.mcumgr.McuMgrHeader
import no.nordicsemi.android.mcumgr.McuMgrScheme
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import no.nordicsemi.android.mcumgr.util.CBOR
import java.util.concurrent.Executors

/**
 * A transport answering the requests with the maps returned by the handler, on another thread.
 */
class FakeTransport(
    private val handler: (header: McuMgrHeader, request: Map<String, Any>) -> Map<String, Any>,
) : McuMgrTransport {
    private val executor = Executors.newSingleThreadExecutor()

    /** The headers of the requests received. */
    val requests = mutableListOf<McuMgrHeader>()

    override fun getScheme() = McuMgrScheme.BLE

    override fun <T : McuMgrResponse> send(
        payload: ByteArray,
        timeout: Long,
        responseType: Class<T>,
    ): T {
        val header = McuMgrHeader.fromBytes(payload)
        synchronized(requests) { requests += header }
        val request = CBOR.toObjectMap(payload.copyOfRange(McuMgrHeader.HEADER_LENGTH, payload.size))
        val body = CBOR.toBytes(handler(header, request))
        val responseHeader = McuMgrHeader(
            header.version, header.op + 1, 0, body.size,
            header.groupId, header.sequenceNum, header.commandId
        )
        return McuMgrResponse.buildResponse(
            McuMgrScheme.BLE, responseHeader.toBytes() + body, responseType
        )
    }

    override fun <T : McuMgrResponse> send(
        payload: ByteArray,
        timeout: Long,
        responseType: Class<T>,
        callback: McuMgrCallback<T>,
    ) {
        executor.execute {
            try {
                callback.onResponse(send(payload, timeout, responseType))
            } catch (e: McuMgrException) {
                callback.onError(e)
            } catch (e: Exception) {
                callback.onError(McuMgrException(e))
            }
        }
    }

    override fun connect(callback: McuMgrTransport.ConnectionCallback?) = Unit
    override fun changeMode(name: String, callback: McuMgrTransport.ModeChangeCallback?) = false
    override fun release() = Unit
    override fun addObserver(observer: McuMgrTransport.ConnectionObserver) = Unit
    override fun removeObserver(observer: McuMgrTransport.ConnectionObserver) = Unit
}

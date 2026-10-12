/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx

import kotlinx.coroutines.suspendCancellableCoroutine
import no.nordicsemi.android.mcumgr.McuManager
import no.nordicsemi.android.mcumgr.McuMgrCallback
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Starts a request using the given asynchronous call and suspends until its response arrives.
 *
 * Cancelling the coroutine does not cancel the request, as a command that has been sent to
 * the device cannot be taken back. The response is just dropped.
 *
 * @throws McuMgrException if the request fails.
 * @throws McuMgrErrorException if the device responds with an error, that is with a non-zero
 * return code, as the library reports it in the response instead of failing the request.
 */
internal suspend inline fun <T : McuMgrResponse> awaitResponse(
    crossinline request: (McuMgrCallback<T>) -> Unit
): T = suspendCancellableCoroutine { continuation ->
    request(object : McuMgrCallback<T> {
        override fun onResponse(response: T) {
            if (response.isSuccess) {
                continuation.resume(response)
            } else {
                continuation.resumeWithException(McuMgrErrorException(response))
            }
        }

        override fun onError(error: McuMgrException) {
            continuation.resumeWithException(error)
        }
    })
}

/**
 * Sends a command to the device and suspends until the response is received.
 *
 * This is the suspending counterpart of [McuManager.send] with a callback, which can be used to
 * implement commands of custom groups. The managers provided by this library have dedicated
 * methods in their wrappers.
 *
 * Cancelling the coroutine does not cancel the request, only the wait for the response.
 *
 * @param op The operation: 0 for read, or 2 for write.
 * @param commandId The ID of the command.
 * @param payload The map of values to send along, or null if the header is the only required field.
 * @param timeout The timeout, in milliseconds.
 * @param responseType The type of the response.
 * @return The response.
 * @throws McuMgrException if the request fails.
 * @throws McuMgrErrorException if the device responds with an error, that is with a non-zero
 * return code.
 */
suspend fun <T : McuMgrResponse> McuManager.sendAndAwait(
    op: Int,
    commandId: Int,
    payload: Map<String, Any>?,
    timeout: Long,
    responseType: Class<T>,
): T = awaitResponse { callback ->
    send(op, commandId, payload, timeout, responseType, callback)
}

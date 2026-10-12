/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.job
import no.nordicsemi.android.mcumgr.transfer.TransferController

/**
 * Passes events from the callbacks of the library, which may be called from any thread, to a
 * channel.
 *
 * The first event, which depends on the controller returned when the transfer is started, must be
 * delivered before any other, but the library may report progress before it returns. The events
 * reported before [begin] is called are therefore kept until then.
 */
internal class EventSink<S>(private val channel: SendChannel<S>) {
    private val lock = Any()
    private var begun = false
    private var finished = false
    private var finishCause: Throwable? = null
    private val pending = mutableListOf<S>()

    /** Whether the transfer has finished, successfully or not. */
    val isFinished: Boolean
        get() = synchronized(lock) { finished }

    /** Delivers the first event, followed by those reported in the meantime. */
    fun begin(first: S) = synchronized(lock) {
        channel.trySend(first)
        pending.forEach { channel.trySend(it) }
        pending.clear()
        begun = true
        if (finished) channel.close(finishCause)
    }

    /** Delivers an event. Events reported after [finish] are ignored. */
    fun emit(event: S) = synchronized(lock) {
        if (finished) return@synchronized
        if (begun) channel.trySend(event) else pending += event
    }

    /** Finishes the flow, with the given error, if any. Only the first call has an effect. */
    fun finish(cause: Throwable? = null) = synchronized(lock) {
        if (finished) return@synchronized
        finished = true
        finishCause = cause
        if (begun) channel.close(cause)
    }
}

/**
 * Creates a flow of a transfer, which is started when the flow is collected and cancelled when
 * the collection is cancelled.
 *
 * @param started Creates the first event of the flow from the controller of the transfer.
 * @param start Starts the transfer, reporting its events to the given sink, and returns its
 * controller. The given scope runs on a background thread, is cancelled when the collection is,
 * and does not fail when a child does.
 */
internal fun <S> transferFlow(
    started: (TransferController) -> S,
    start: (sink: EventSink<S>, scope: CoroutineScope) -> TransferController,
): Flow<S> = callbackFlow {
    val sink = EventSink(channel)
    // The upload runs in the background, regardless of the thread collecting the flow.
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob(coroutineContext.job))
    val controller = start(sink, scope)
    sink.begin(started(controller))
    awaitClose {
        // The transfer is still running if the collection has been cancelled.
        if (!sink.isFinished) {
            controller.cancel()
        }
    }
}.buffer(Channel.UNLIMITED)

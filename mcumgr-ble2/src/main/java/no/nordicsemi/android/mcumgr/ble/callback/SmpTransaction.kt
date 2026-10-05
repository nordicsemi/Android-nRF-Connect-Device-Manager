package no.nordicsemi.android.mcumgr.ble.callback

import java.util.concurrent.TimeoutException

/**
 * Thrown when no response with a matching sequence number arrived within the request timeout.
 *
 * @property id The sequence number of the request that timed out.
 */
class TransactionTimeoutException internal constructor(
    val id: Int
) : TimeoutException("Transaction $id timed out without receiving a response") {

    override fun toString(): String {
        return message!!
    }
}

/**
 * Thrown when a request reused a sequence number that was still awaiting a response, which means
 * more than 256 requests were in flight at once.
 *
 * @property id The sequence number that was reused.
 */
class TransactionOverwriteException internal constructor(
    val id: Int
) : Exception("Transaction $id has been overwritten") {

    override fun toString(): String {
        return message!!
    }
}

/**
 * A single SMP request and its response, matched by the sequence number in the SMP header.
 */
internal interface SmpTransaction {

    /**
     * Writes the given packet to the device.
     *
     * This is a suspending call, so that the protocol session applies backpressure while the
     * packet is being split into chunks and written.
     */
    suspend fun send(data: ByteArray)

    /** Called when a response with a matching sequence number has been received. */
    fun onResponse(data: ByteArray)

    /** Called when the request failed, timed out, or the session was closed. */
    fun onFailure(e: Throwable)
}

package no.nordicsemi.android.mcumgr.ble.callback

import android.os.Handler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.android.mcumgr.McuMgrHeader
import no.nordicsemi.android.mcumgr.ble.util.RotatingCounter
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private const val SMP_SEQ_NUM_MAX = 255

internal class SmpProtocolSession @JvmOverloads constructor(
    private val handler: Handler? = null,
    // The context in which the reader, the writer and the timeouts run. Tests use it to run the
    // session in virtual time.
    context: CoroutineContext = EmptyCoroutineContext,
) {
    internal companion object {
        const val TIMEOUT: Long = 30_000
    }

    private data class Outgoing(val data: ByteArray,
                                val timeout: Long,
                                val transaction: SmpTransaction) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Outgoing

            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }

    private val scope = CoroutineScope(context)
    private val txChannel: Channel<Outgoing> = Channel(SMP_SEQ_NUM_MAX + 1)
    private val rxChannel: Channel<ByteArray> = Channel(SMP_SEQ_NUM_MAX + 1)
    private val sequenceCounter = RotatingCounter(SMP_SEQ_NUM_MAX)
    private val transactions: Array<Pair<SmpTransaction, Job>?> = arrayOfNulls(SMP_SEQ_NUM_MAX + 1)
    private val transactionsMutex = Mutex()

    /**
     * Launches the main coroutine and channel consumers.
     */
    init {
        scope.launch(
            // When the session is closed, fail all remaining transactions.
            // Exception is propagated from close through the channels.
            CoroutineExceptionHandler { _, throwable ->
                transactions.forEach {
                    it?.second?.cancel()
                    it?.first?.onFailure(throwable)
                }
            }
        ) {
            // Launch the reader and writer
            launch { reader() }
            launch { writer() }
        }
    }

    fun send(data: ByteArray, timeout: Long, transaction: SmpTransaction) {
        check(txChannel.trySend(Outgoing(data, timeout, transaction)).isSuccess) {
            "Cannot send request, transmit channel buffer is full."
        }
    }

    fun receive(data: ByteArray) {
        check(rxChannel.trySend(data).isSuccess) {
            "Cannot receive response, receive channel buffer is full."
        }
    }

    fun close(e: Exception) {
        txChannel.close(e)
        rxChannel.close(e)
    }

    /**
     * Consumes messages off the tx channel until the channel is closed.
     */
    private suspend fun writer() {
        txChannel.consumeEach { outgoing ->
            // Set sequence number in outgoing data
            val sequenceNumber = sequenceCounter.getAndRotate()
            outgoing.data.setSequenceNumber(sequenceNumber)

            val job = scope.launch {
                delay(outgoing.timeout)
                val transaction = getAndSetTransaction(sequenceNumber, null)
                transaction?.first?.onFailure(handler, TransactionTimeoutException(sequenceNumber))
            }

            // Add transaction to store. Fail an existing transaction on overwrite
            val oldTransaction = getAndSetTransaction(sequenceNumber, outgoing.transaction to job)
            oldTransaction?.second?.cancel()
            oldTransaction?.first?.onFailure(handler, TransactionOverwriteException(sequenceNumber))

            // Send the transaction and launch timeout coroutine
            outgoing.transaction.send(handler, outgoing.data)
        }
    }

    /**
     * Consumes messages of the rx channel until the channel is closed.
     */
    private suspend fun reader() {
        rxChannel.consumeEach { data ->
            // Parse header to get sequence number
            val sequenceNumber = data.getSequenceNumber()

            // Get the transaction from the store, clear the entry, and call
            // the callback
            val transaction = getAndSetTransaction(sequenceNumber, null)
            transaction?.second?.cancel()
            transaction?.first?.onResponse(handler, data)
        }
    }

    private suspend fun getAndSetTransaction(
        id: Int,
        transaction: Pair<SmpTransaction, Job>?,
    ): Pair<SmpTransaction, Job>? = transactionsMutex.withLock {
        val oldTransaction = transactions[id]
        transactions[id] = transaction
        return oldTransaction
    }

    private fun ByteArray.setSequenceNumber(value: Int) {
        this[6] = (value and 0xff).toByte()
    }

    private fun ByteArray.getSequenceNumber(): Int {
        if (size < McuMgrHeader.HEADER_LENGTH) {
            throw IllegalArgumentException("Failed to parse mcumgr header from bytes; too short - length=$size")
        }
        return this[6].toInt() and 0xFF
    }
}

private fun SmpTransaction.send(handler: Handler?, data: ByteArray) {
    when (handler) {
        null -> send(data)
        else -> handler.post { send(data) }
    }
}

private fun SmpTransaction.onResponse(handler: Handler?, data: ByteArray) {
    when (handler) {
        null -> onResponse(data)
        else -> handler.post { onResponse(data) }
    }
}

private fun SmpTransaction.onFailure(handler: Handler?, e: Throwable) {
    when (handler) {
        null -> onFailure(e)
        else -> handler.post { onFailure(e) }
    }
}

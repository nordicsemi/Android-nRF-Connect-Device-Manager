package no.nordicsemi.android.mcumgr.ble.callback

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
import no.nordicsemi.android.mcumgr.log.Category
import no.nordicsemi.android.mcumgr.log.McuMgrLogger
import no.nordicsemi.kotlin.log.Log
import kotlin.time.Duration

/**
 * Index of the SEQ field in the SMP header.
 *
 * The SMP header is composed of:
 *
 * | Bytes | Description                              | Comment            |
 * |-------|------------------------------------------|--------------------|
 * | 0     | Reserved (3), Version (2), Operation (3) |                    |
 * | 1     | Flags                                    | Not used, set to 0 |
 * | 2-3   | Length (Big Endian)                      | Payload length     |
 * | 4-5   | Group ID (Big Endian)                    |                    |
 * | 6     | Sequence Number                          | 0-255              |
 * | 7     | Command ID                               |                    |
 */
private const val SMP_SN_INDEX = 6
/**
 * Sequence number is a UINT8.
 */
private const val SMP_SEQ_NUM_MAX = 255

/**
 * The SMP framing layer.
 *
 * Requests are assigned a rotating sequence number, which is used to match each incoming response
 * back to the request that is waiting for it. This allows several requests to be in flight at
 * once, without waiting for a response before sending the next one.
 *
 * The session lives in the scope of the [no.nordicsemi.android.mcumgr.ble.SmpProfile] that owns
 * it, so it is closed together with the connection.
 *
 * @param scope The scope the reader and the writer run in.
 * @param sink The logger of the profile, reporting under
 * [PROTOCOL][no.nordicsemi.android.mcumgr.log.Category.PROTOCOL].
 */
internal class SmpProtocolSession(
    private val scope: CoroutineScope,
    private val sink: Log.Sink<Category>?,
) {
    private data class Outgoing(
        val data: ByteArray,
        val timeout: Duration,
        val transaction: SmpTransaction,
    ) {
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

    private var log = McuMgrLogger(Category.PROTOCOL).also { it.sink = sink }

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
            // The channels are closed with an exception by [close], which propagates here.
            // The pending requests are failed by [close] itself, so that they are reported even
            // when these coroutines have already been canceled along with the profile scope.
            CoroutineExceptionHandler { _, throwable ->
                log.warn { "Session closed: ${throwable.message}" }
            }
        ) {
            // Launch the reader and writer
            launch { reader() }
            launch { writer() }
        }
    }

    fun send(data: ByteArray, timeout: Duration, transaction: SmpTransaction) {
        check(txChannel.trySend(Outgoing(data, timeout, transaction)).isSuccess) {
            "Cannot send request, transmit channel buffer is full."
        }
    }

    fun receive(data: ByteArray) {
        check(rxChannel.trySend(data).isSuccess) {
            "Cannot receive response, receive channel buffer is full."
        }
    }

    /**
     * Closes the session and fails every request that is still awaiting a response.
     *
     * This suspends only to take the transaction lock, so it should be called from a
     * [kotlinx.coroutines.NonCancellable] context while the connection is being torn down.
     */
    suspend fun close(e: Throwable) {
        txChannel.close(e)
        rxChannel.close(e)
        transactionsMutex.withLock {
            transactions.indices.forEach { id ->
                transactions[id]?.let { (transaction, job) ->
                    // Clear the entry first, so that a response arriving concurrently cannot
                    // report the same request twice.
                    transactions[id] = null
                    job.cancel()
                    log.warn { "Request $id failed, session closed" }
                    transaction.onFailure(e)
                }
            }
        }
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
                transaction?.let {
                    log.warn { "Request $sequenceNumber timed out after ${outgoing.timeout} ms" }
                    it.first.onFailure(TransactionTimeoutException(sequenceNumber))
                }
            }

            // Add transaction to store. Fail an existing transaction on overwrite
            val oldTransaction = getAndSetTransaction(sequenceNumber, outgoing.transaction to job)
            oldTransaction?.let {
                log.warn { "Request $sequenceNumber overwritten, too many requests in flight" }
                it.second.cancel()
                it.first.onFailure(TransactionOverwriteException(sequenceNumber))
            }

            // Send the transaction and launch timeout coroutine
            outgoing.transaction.send(outgoing.data)
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
            if (transaction == null) {
                log.warn { "Response $sequenceNumber received with no request awaiting it" }
                return@consumeEach
            }
            transaction.second.cancel()
            transaction.first.onResponse(data)
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
        this[SMP_SN_INDEX] = (value and 0xff).toByte()
    }

    private fun ByteArray.getSequenceNumber(): Int {
        if (size < McuMgrHeader.HEADER_LENGTH) {
            throw IllegalArgumentException("Failed to parse mcumgr header from bytes; too short - length=$size")
        }
        return this[SMP_SN_INDEX].toInt() and 0xFF
    }
}

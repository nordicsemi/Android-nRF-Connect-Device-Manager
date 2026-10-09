package no.nordicsemi.android.mcumgr.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.android.mcumgr.McuMgrScheme
import no.nordicsemi.android.mcumgr.exception.InsufficientMtuException
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.exception.McuMgrTimeoutException
import no.nordicsemi.android.mcumgr.log.Category
import no.nordicsemi.android.mcumgr.log.McuMgrLogger
import no.nordicsemi.android.mcumgr.util.CBOR
import no.nordicsemi.kotlin.log.Log
import java.security.DigestException
import kotlin.math.min

const val MAX_CHUNK_FAILURES = 5

data class UploadProgress(
    val offset: Int,
    val size: Int,
    val timestamp: Long = System.currentTimeMillis()
)

private data class Chunk(val data: ByteArray, val offset: Int, val isLast: Boolean) {
    override fun toString(): String {
        return "Chunk(offset=$offset, size=${data.size})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Chunk

        if (!data.contentEquals(other.data)) return false
        if (offset != other.offset) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + offset
        return result
    }
}

abstract class Uploader(
    private val data: ByteArray,
    private val windowCapacity: Int,
    private val memoryAlignment: Int,
    internal var mtu: Int,
    private val protocol: McuMgrScheme,
    /**
     * The sink assigned to the manager performing the upload, or `null` to log nothing.
     * Upload entries are reported under [Category.TRANSFER].
     */
    logger: Log.Sink<Category>? = null,
) {
    private val log = McuMgrLogger(Category.TRANSFER).apply { sink = logger }

    private val _progress: MutableSharedFlow<UploadProgress> = MutableSharedFlow(
        replay = 2,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var currentOffset = 0

    val progress: Flow<UploadProgress> = _progress
    private val resumed = Semaphore(1)

    /**
     * This method should send the request with given parameters.
     */
    @Throws
    internal abstract fun write(
        requestMap: Map<String, Any>,
        timeout: Long,
        callback: (UploadResult) -> Unit
    )

    /**
     * Uploads the data.
     */
    @Throws
    suspend fun upload() = coroutineScope {
        // Tracks the number of failures experienced for any given chunk,
        // identified by the offset.
        val failureDirectory = mutableMapOf<Int, Int>()
        val failureDirectoryMutex = Mutex()

        // Bounds number of in-progress requests within window capacity.
        val window = Semaphore(windowCapacity)

        val next: Channel<Chunk> = Channel(CONFLATED)
        val failures: Channel<Chunk> = Channel(CONFLATED)
        val close: Channel<Unit> = Channel(CONFLATED)

        val initialTimestamp = System.currentTimeMillis()
        next.send(newChunk(0))

        while (true) {
            window.acquire()

            // Try acquiring resumed lock. If worked, release it immediately.
            resumed.acquire()
            resumed.release()

            // Select the next chunk to send, prioritizing failed chunks.
            val (chunk, resend) = select<Pair<Chunk, Boolean>?> {
                failures.onReceive { it to true }
                next.onReceive { it to false }
                close.onReceive { null }
            } ?: break

            val nextChunk = writeInternal(chunk, resend, this) { result ->
                result.onSuccess { response ->
                    if (!resend && response.off < chunk.offset + chunk.data.size) {
                        // An unexpected offset means that the message was
                        // somehow lost or the device could not accept the
                        // chunk. We need to resend the chunk at the offset
                        // requested by the device.
                        log.warn { "Chunk with offset ${chunk.offset} has been lost (expected offset=${chunk.offset + chunk.data.size}, received=${response.off})" }
                        val fails = failureDirectoryMutex.withLock {
                            val fails = (failureDirectory[chunk.offset] ?: 0) + 1
                            failureDirectory[chunk.offset] = fails
                            fails
                        }
                        if (fails >= MAX_CHUNK_FAILURES) {
                            throw McuMgrException("Chunk with offset ${chunk.offset} has not been acknowledged too many times")
                        }
                        failures.send(newChunk(response.off))
                    } else {
                        // Success, update the progress.
                        if (chunk.offset == 0 && response.off == chunk.data.size) {
                            _progress.tryEmit(UploadProgress(0, data.size, initialTimestamp))
                            // The upload has (re)started from the beginning, e.g. after the device
                            // rebooted and lost the data it had received. Forget the offset
                            // reached so far, otherwise no progress would be reported until the
                            // old offset is exceeded.
                            currentOffset = 0
                        }
                        if (currentOffset < response.off) {
                            _progress.tryEmit(UploadProgress(response.off, data.size))
                            currentOffset = response.off
                        }
                        if (response.off == data.size) {
                            close.send(Unit)
                        }
                    }
                }.onErrorOrFailure { failure ->
                    // If a non-success response was returned abort sending the file.
                    if (failure is McuMgrErrorException) {
                        throw failure
                    }

                    // On insufficient MTU, the uploader will be restarted with proper MTU set.
                    // The proper MTU value is embedded in the exception.
                    if (failure is InsufficientMtuException) {
                        throw failure
                    }

                    // This error may be thrown after sending all data when reported digest does
                    // not match the data sent.
                    if (failure is DigestException) {
                        throw failure
                    }

                    // If a packet times out, the notification might have been lost, but the
                    // packet delivery could have, actually, succeed. Let's check if the current
                    // offset (which wouldn't increase if the packet was lost indeed) got bigger.
                    if (failure is McuMgrTimeoutException) {
                        // As the timeout for sending a packet is quite long (1 sec), some following
                        // packets may have been sent before the time run out, and those could have
                        // got the ack.
                        if (currentOffset > chunk.offset) {
                            log.warn { "A notification for chunk with offset=${chunk.offset} was lost, but the chunk was ack-ed by later chunk (confirmed offset=$currentOffset)" }
                            return@onErrorOrFailure
                        } else {
                            log.warn { "A notification for chunk with offset=${chunk.offset} was lost, current offset: $currentOffset" }
                        }
                    }

                    // Request failure, resend failed chunk.
                    log.warn { "Uploader write failure for chunk with offset=${chunk.offset}: $failure" }
                    // Track the number of times a chunk has failed. If the
                    // chunk has failed more times than the threshold,
                    // throw the exception to fail the upload entirely.
                    val fails = failureDirectoryMutex.withLock {
                        val fails = (failureDirectory[chunk.offset] ?: 0) + 1
                        failureDirectory[chunk.offset] = fails
                        fails
                    }
                    if (fails >= MAX_CHUNK_FAILURES) {
                        throw failure
                    }
                    failures.send(newChunk(chunk.offset))
                }

                // Release the semaphore.
                window.release()
            }

            // Only send the next chunk if the we still have more data to upload.
            if (nextChunk.offset < data.size) {
                next.send(nextChunk)
            }
        }
        window.release()
    }

    /**
     * Uploads the data asynchronously.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @JvmOverloads fun uploadAsync(
        callback: UploadCallback,
        scope: CoroutineScope = GlobalScope,
    ): TransferController {
        val exceptionHandler = CoroutineExceptionHandler { _, t ->
            log.error(t) { "Upload failed: ${t.message}" }
        }
        val job = scope.launch(exceptionHandler) {
            val progress = progress.onEach { progress ->
                callback.onUploadProgressChanged(
                    progress.offset,
                    progress.size,
                    progress.timestamp,
                )
            }.launchIn(this)

            val size = data.size
            val start = System.currentTimeMillis()
            uploadCatchMtu()
            val duration = System.currentTimeMillis() - start
            log.info { "Upload completed. $size bytes sent in $duration ms with avg speed: ${size.toFloat() / (duration.toFloat() + 1f)} kBytes/s" } // + 1 to prevent division by zero
            progress.cancel()
        }

        job.invokeOnCompletion { throwable ->
            when (throwable) {
                null -> callback.onUploadCompleted()
                is CancellationException -> callback.onUploadCanceled()
                is McuMgrException -> callback.onUploadFailed(throwable)
                else -> callback.onUploadFailed(McuMgrException(throwable))
            }
        }

        val uploader = this
        return object : TransferController {
            var paused: Job? = null

            override fun pause() {
                paused = scope.launch {
                    uploader.pause()
                    paused = null
                }
            }
            override fun resume() {
                uploader.resume()
                paused = null
            }
            override fun cancel() {
                paused?.cancel()
                job.cancel()
            }
        }
    }

    // Catches an mtu exception, sets the new mtu and restarts the upload.
    private suspend fun uploadCatchMtu() {
        try {
            upload()
        } catch (e: InsufficientMtuException) {
            mtu = e.mtu
            upload()
        }
    }

    /**
     * Pauses upload.
     */
    suspend fun pause() {
        resumed.acquire()
    }

    /**
     * Resumes upload.
     */
    fun resume() {
        resumed.release()
    }

    private suspend fun writeInternal(
        chunk: Chunk,
        resend: Boolean,
        scope: CoroutineScope,
        callback: suspend (UploadResult) -> Unit
    ): Chunk {
        val resultChannel: Channel<UploadResult> = Channel(1)
        val timeout = when {
            // Timeout for the initial chunk is long, as the device may need to erase the flash.
            chunk.offset == 0 -> 40_000L
            // Also, the last chunk may take a while to process, so we give it more time as well.
            chunk.isLast -> 20_000L
            else -> 2_500L
        }
        write(prepareWrite(chunk.data, chunk.offset), timeout) { result ->
            resultChannel.trySend(result)
        }

        return if (chunk.offset == 0) {
            // Send the first chunk synchronously, to get the last offset.
            val result = resultChannel.receive()
            callback(result)

            result.onSuccess {
                return newChunk(it.off)
            }
            return nextChunk(chunk)
        } else if (resend) {
            // Failed and resent chunks should suspend the current coroutine
            // and await the result.
            val result = resultChannel.receive()
            callback(result)

            // When the result is successful response with an offset, return
            // a new chunk with the requested offset.
            when (result) {
                is UploadResult.Response -> {
                    newChunk(result.body.off)
                }
                else -> nextChunk(chunk)
            }
        } else {
            // Regular send should launch the result handling on a child coroutine.
            scope.launch {
                val result = resultChannel.receive()
                callback(result)
            }

            // Return the next logical chunk.
            nextChunk(chunk)
        }
    }

    private fun newChunk(offset: Int): Chunk {
        // SMP pipelining may require data to be aligned to some number of bytes.
        // In Zephyr, since https://github.com/zephyrproject-rtos/zephyr/pull/41959 has been merged
        // this is not required, but memory aligning here makes even older devices to work.
        val maxChunkSize = getMaxChunkSize(offset)
        val alignedSize =
            if (offset + maxChunkSize < data.size) maxChunkSize / memoryAlignment * memoryAlignment else maxChunkSize
        val chunkData = data.copyOfRange(offset, offset + alignedSize)
        val isLast = offset + alignedSize >= data.size
        return Chunk(chunkData, offset, isLast)
    }

    private fun nextChunk(chunk: Chunk): Chunk {
        return newChunk(chunk.offset + chunk.data.size)
    }

    private fun prepareWrite(
        data: ByteArray,
        offset: Int,
    ): Map<String, Any> = mutableMapOf<String, Any>(
        "data" to data,
        "off" to offset
    ).also {
        if (offset == 0) {
            it["len"] = this.data.size // NOT data.size, as data is just a chunk of this.data
        }
        getAdditionalData(this.data, offset, it)
    }

    /**
     * Returns the maximum amount of upload data which can fit into an upload request with the given
     * data and offset.
     *
     * This calculation is optimal, and takes into account the transport scheme and size of data and
     * offset since CBOR will make the integers as efficient as possible. In order to avoid an index
     * out of bounds on the last chunk, if the calculated chunk size is greater than data.size -
     * offset, then the latter value is returned.
     */
    private fun getMaxChunkSize(offset: Int): Int {
        // The size of the header is based on the scheme. CoAP scheme is larger because there are
        // 4 additional bytes of CBOR.
        val headerSize = when (protocol) {
            McuMgrScheme.BLE -> 8
            else -> 8 + 4
        }

        // A map token with 5-bit length (A0-AF or B0-B7) - map size of max 23 pairs
        val mapSize = 1

        // Size of the field name "data" utf8 string
        val dataStringSize = CBOR.stringLength("data")

        // Size of the string "off" plus the length of the offset integer
        val offsetSize = CBOR.stringLength("off") + CBOR.uintLength(offset)

        // Size of the string "len" plus the length of the data size integer
        // "len" is sent only in the initial packet.
        val lengthSize = if (offset == 0) {
            CBOR.stringLength("len") + CBOR.uintLength(data.size)
        } else {
            0
        }

        // Implementation specific size
        val implSpecificSize = getAdditionalSize(offset)

        val combinedSize = headerSize + mapSize + offsetSize + lengthSize + implSpecificSize + dataStringSize

        // Now we calculate the max amount of data that we can fit given the MTU.
        val maxDataLength = mtu - combinedSize
        // We have to take into account the few bytes of CBOR which describe the length of the data.
        // Even though we don't know the actual length at this point, the maxDataLength is guaranteed
        // to be larger than what we will eventually send, making this calculation always correct.
        val maxDataUIntTokenSize = CBOR.uintLength(maxDataLength)

        // Final data chunk size
        val maxChunkSize = mtu - combinedSize - maxDataUIntTokenSize
        return min(maxChunkSize, data.size - offset)
    }

    /**
     * This method should add additional parameters to the map.
     * The "data", "len" and "off" parameters are already added.
     */
    internal open fun getAdditionalData(
        data: ByteArray,
        offset: Int,
        map: MutableMap<String, Any>
    ) {
        // Empty default implementation.
    }

    /**
     * This method should return an additional size of the CBOR payload, which will be placed to the
     * packet with the given offset.
     * The "data", "len" and "off" parameters are already calculated.
     */
    internal open fun getAdditionalSize(offset: Int) = 0
}
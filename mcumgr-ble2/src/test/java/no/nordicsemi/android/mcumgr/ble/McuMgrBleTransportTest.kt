package no.nordicsemi.android.mcumgr.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.mcumgr.McuManager
import no.nordicsemi.android.mcumgr.McuMgrCallback
import no.nordicsemi.android.mcumgr.McuMgrHeader
import no.nordicsemi.android.mcumgr.McuMgrScheme
import no.nordicsemi.android.mcumgr.ble.callback.TransactionTimeoutException
import no.nordicsemi.android.mcumgr.ble.exception.McuMgrDisconnectedException
import no.nordicsemi.android.mcumgr.exception.InsufficientMtuException
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.exception.McuMgrTimeoutException
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrEchoResponse
import no.nordicsemi.android.mcumgr.util.CBOR
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.mock.mock
import no.nordicsemi.kotlin.ble.client.mock.AddressType
import no.nordicsemi.kotlin.ble.client.mock.MockPeripheral
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.Permission
import org.junit.After
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class McuMgrBleTransportTest {

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val OP_WRITE = 2
        const val GROUP_DEFAULT = 0
        const val ID_ECHO = 0
        const val ID_MCUMGR_PARAMS = 6
        const val BUFFER_SIZE = 256
        const val REQUESTS = 50
    }

    /**
     * Runs coroutines on a single thread in an order that is legal for a dispatcher, but the
     * worst for the order of requests: a task dispatched from the dispatcher's own thread runs
     * before the ones dispatched earlier, like the task in the "last scheduled" slot of
     * kotlinx.coroutines' scheduler, while tasks dispatched from other threads run in order.
     *
     * Every coroutine of the transport runs here, so with a transport that does not keep
     * the order of the requests itself, the tests below fail every time instead of now and then.
     * Code running on this thread also knows that no coroutine of the transport runs until
     * it returns.
     */
    private class ReorderingDispatcher : CoroutineDispatcher(), AutoCloseable {
        @Volatile
        private var thread: Thread? = null
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "transport").also { thread = it }
        }
        private val nested = ArrayDeque<Runnable>()
        private val external = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(this) {
                if (Thread.currentThread() === thread) nested.addLast(block) else external.addLast(block)
            }
            executor.execute {
                val next = synchronized(this) { nested.removeLastOrNull() ?: external.removeFirstOrNull() }
                next?.run()
            }
        }

        override fun close() {
            executor.shutdownNow()
        }
    }

    /**
     * An SMP server that answers the Mcu Manager params command and echoes the echo command,
     * recording the echoed strings in the order the requests arrived.
     */
    private class EchoDevice : PeripheralSpecEventHandler {
        val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /**
         * When false, the device ignores the params command. The SMP service opens only when the
         * request for the params times out, after a second.
         */
        @Volatile
        var answersParams = true

        /**
         * When not null, the device answers the params command with this return code, like one
         * that does not support it (8 is not supported), instead of the buffer size.
         */
        @Volatile
        var paramsReturnCode: Int? = null

        /** When false, the device records the echo command, but does not answer it. */
        @Volatile
        var answersEcho = true

        /** When not null, the device answers the echo command with this return code. */
        @Volatile
        var echoReturnCode: Int? = null

        /** When true, writing the echo command fails on the client side. */
        @Volatile
        var failsEcho = false

        /** When true, the device holds its answers to the echo command until [answerHeld]. */
        @Volatile
        var holdsAnswers = false

        /** When true, the device holds its answer to the params command until [answerHeld]. */
        @Volatile
        var holdsParams = false

        /** Completes when the device has received the params command. */
        val paramsReceived = CompletableDeferred<Unit>()

        private val held = mutableListOf<() -> Unit>()

        /** Sends the answers held so far. */
        fun answerHeld() {
            val answers = synchronized(held) { held.toList().also { held.clear() } }
            answers.forEach { it() }
        }

        override fun onWriteCommand(
            peripheral: MockPeripheral,
            characteristic: MockRemoteCharacteristic,
            value: ByteArray
        ) {
            val header = McuMgrHeader.fromBytes(value)
            val answer = { response: Map<String, Any> ->
                peripheral.simulateValueUpdate(
                    handle = characteristic.instanceId,
                    value = McuManager.buildPacket(
                        McuMgrScheme.BLE, header.op + 1, header.flags, header.groupId,
                        header.sequenceNum, header.commandId, response
                    )
                )
            }
            when (header.commandId) {
                ID_MCUMGR_PARAMS -> {
                    val params = paramsReturnCode?.let { mapOf("rc" to it) }
                        ?: mapOf("buf_size" to BUFFER_SIZE, "buf_count" to 4)
                    when {
                        holdsParams -> synchronized(held) { held += { answer(params) } }
                        answersParams -> answer(params)
                    }
                    paramsReceived.complete(Unit)
                }
                ID_ECHO -> {
                    // The mock fails the write with it.
                    if (failsEcho) throw IllegalStateException("Write failed")
                    val echo = CBOR.getString(value.copyOfRange(McuMgrHeader.HEADER_LENGTH, value.size), "d")
                    val response = echoReturnCode?.let { mapOf("rc" to it) } ?: mapOf("r" to echo)
                    when {
                        !answersEcho -> received += echo
                        holdsAnswers -> {
                            // Held before the request is recorded, so that answerHeld() sends
                            // the answer once a test sees the request.
                            synchronized(held) { held += { answer(response) } }
                            received += echo
                        }
                        else -> {
                            // Recorded before it is answered, so that a test sees the request
                            // once it gets the response.
                            received += echo
                            answer(response)
                        }
                    }
                }
            }
        }
    }

    /** The callback of one request, which records how many times it is called. */
    private class Outcome : McuMgrCallback<McuMgrEchoResponse> {
        private val calls = AtomicInteger()
        private val first = CompletableDeferred<Result<String>>()

        /** How many times the callback has been called. */
        val count: Int
            get() = calls.get()

        override fun onResponse(response: McuMgrEchoResponse) = record(Result.success(response.r))

        override fun onError(error: McuMgrException) = record(Result.failure(error))

        private fun record(result: Result<String>) {
            calls.incrementAndGet()
            first.complete(result)
        }

        /** Awaits the first call: the echoed string, or the error. */
        suspend fun await(): Result<String> = first.await()
    }

    private val device = EchoDevice()
    private val mockScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatcher = ReorderingDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val manager = CentralManager.Factory.mock(scope = mockScope).apply {
        simulatePeripherals(
            listOf(
                PeripheralSpec.simulatePeripheral(
                    identifier = ADDRESS,
                    // Android assumes PUBLIC address type for devices that weren't scanned.
                    addressType = AddressType.PUBLIC,
                ) {
                    advertising {
                        CompleteLocalName("SMP")
                    }
                    connectable(
                        name = "SMP",
                        maxAttMtu = 247,
                        eventHandler = device,
                    ) {
                        Service(DefaultMcuMgrUuidConfig.serviceUuid) {
                            Characteristic(
                                uuid = DefaultMcuMgrUuidConfig.characteristicUuid,
                                properties = setOf(
                                    CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
                                    CharacteristicProperty.NOTIFY,
                                ),
                                permissions = setOf(Permission.WRITE),
                            )
                        }
                    }
                }
            )
        )
    }
    private val peripheral = checkNotNull(manager.getPeripheralById(ADDRESS))
    private val transport = McuMgrBleTransport(manager, peripheral, scope)

    /** The callbacks of all requests sent in a test. */
    private val outcomes: MutableList<Outcome> = Collections.synchronizedList(mutableListOf())

    @After
    fun tearDown() {
        try {
            transport.release()
            scope.cancel()
            // Once the scope has completed, the transport cannot call a callback anymore.
            runBlocking { withTimeout(5.seconds) { scope.coroutineContext.job.join() } }
            outcomes.forEach { assertEquals(1, it.count, "Each request is reported once") }
        } finally {
            mockScope.cancel()
            dispatcher.close()
        }
    }

    /** Runs a test, which fails if it does not end within 20 seconds. */
    private fun transportTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        withTimeout(20.seconds, block)
    }

    private fun echo(message: String): ByteArray = McuManager.buildPacket(
        McuMgrScheme.BLE, OP_WRITE, 0, GROUP_DEFAULT, 0, ID_ECHO, mapOf("d" to message)
    )

    private fun McuMgrBleTransport.sendEcho(message: String, timeout: Long = 5_000): Outcome =
        Outcome().also {
            outcomes += it
            send(echo(message), timeout, McuMgrEchoResponse::class.java, it)
        }

    @Test
    fun `requests sent back to back while connected reach the device in order`() = transportTest {
        transport.connect()
        device.holdsAnswers = true

        // Like an uploader running on a dispatcher thread, which sends a request for every
        // free window slot without suspending in between.
        val requests = withContext(dispatcher) {
            List(REQUESTS) { transport.sendEcho("$it") }
        }

        // All the requests reach the device before it answers any, so the transport does
        // not wait for a response before sending the next request.
        while (device.received.size < REQUESTS) delay(10)
        assertEquals(List(REQUESTS) { "$it" }, device.received)

        device.answerHeld()
        assertEquals(List(REQUESTS) { "$it" }, requests.map { it.await().getOrThrow() })
    }

    @Test
    fun `requests sent while connecting reach the device in order`() = transportTest {
        device.holdsParams = true

        // The transport connects on the first request. The SMP service does not open before
        // the device answers the params command, so all these requests wait for it.
        val requests = List(REQUESTS) { transport.sendEcho("$it") }
        device.paramsReceived.await()
        device.answerHeld()

        assertEquals(List(REQUESTS) { "$it" }, requests.map { it.await().getOrThrow() })
        assertEquals(List(REQUESTS) { "$it" }, device.received)
    }

    @Test
    fun `a request waiting for the connection times out on time behind one that waits longer`() = transportTest {
        device.answersParams = false

        // The SMP service opens only after a second, when the params request times out.
        val sentAt = TimeSource.Monotonic.markNow()
        val slow = transport.sendEcho("slow", timeout = 800)
        val quick = transport.sendEcho("quick", timeout = 300)

        assertIs<McuMgrTimeoutException>(quick.await().exceptionOrNull())
        val elapsed = sentAt.elapsedNow()
        assertTrue(elapsed < 1.seconds, "The 300 ms request timed out after $elapsed")
        assertEquals(0, slow.count, "The request waiting longer timed out first")
        assertIs<McuMgrTimeoutException>(slow.await().exceptionOrNull())
        assertTrue(device.received.isEmpty())
    }

    @Test
    fun `a device without the params command is used with single writes`() = transportTest {
        // As a Zephyr device with CONFIG_MCUMGR_GRP_OS_MCUMGR_PARAMS=n: {"rc": 8}, and no buffer.
        device.paramsReturnCode = 8
        transport.connect()

        // The reply is not taken for a reassembly buffer of 0 bytes.
        assertTrue(transport.maxPacketLength!! > 0, "Packet length: ${transport.maxPacketLength}")
        assertEquals("1", transport.sendEcho("1").await().getOrThrow())
    }

    @Test
    fun `a device not answering the params command is used with single writes`() = transportTest {
        device.answersParams = false

        // The request waits for the params to time out, which takes a second.
        val sentAt = TimeSource.Monotonic.markNow()
        assertEquals("1", transport.sendEcho("1").await().getOrThrow())
        val elapsed = sentAt.elapsedNow()

        assertTrue(elapsed >= 1.seconds, "The service opened after $elapsed")
        assertTrue(transport.maxPacketLength!! > 0, "Packet length: ${transport.maxPacketLength}")
    }

    @Test
    fun `release fails the requests waiting for the connection`() = transportTest {
        device.answersParams = false

        val requests = List(3) { transport.sendEcho("$it") }
        transport.state.first { it == McuMgrBleTransport.State.Initializing }
        transport.release()

        requests.forEach { assertIs<McuMgrDisconnectedException>(it.await().exceptionOrNull()) }
        assertTrue(device.received.isEmpty())
    }

    @Test
    fun `release right after sending fails the requests`() = transportTest {
        // On the transport's thread, the connection cannot start before release().
        val requests = withContext(dispatcher) {
            List(3) { transport.sendEcho("$it") }.also { transport.release() }
        }

        requests.forEach { assertIs<McuMgrDisconnectedException>(it.await().exceptionOrNull()) }
        assertTrue(device.received.isEmpty())
    }

    @Test
    fun `a released transport stops its coroutines`() = transportTest {
        transport.connect()
        assertEquals("0", transport.sendEcho("0").await().getOrThrow())
        transport.release()

        // Only the coroutine sharing the state of the transport is left, so that a released
        // transport is not kept by an application using one scope for many transports.
        while (scope.coroutineContext.job.children.count() > 1) delay(10)
    }

    @Test
    fun `canceling the scope fails the requests awaiting a response`() = transportTest {
        transport.connect()
        device.answersEcho = false

        val requests = List(3) { transport.sendEcho("$it") }
        while (device.received.size < requests.size) delay(10)
        scope.cancel()

        requests.forEach { assertIs<McuMgrDisconnectedException>(it.await().exceptionOrNull()) }
    }

    @Test
    fun `canceling the scope right after sending fails the request`() = transportTest {
        transport.connect()

        // On the transport's thread, the transport cannot handle the request before
        // the scope is canceled.
        val request = withContext(dispatcher) {
            transport.sendEcho("0").also { scope.cancel() }
        }

        assertIs<McuMgrDisconnectedException>(request.await().exceptionOrNull())
    }

    @Test
    fun `a request in a scope that times out reports a timeout`() = transportTest {
        device.answersParams = false
        val request = transport.sendEcho("0")

        // As when the scope belongs to withTimeout(), which times out. Canceled on the transport's
        // thread, so that the connection cannot close and fail the request before it is canceled.
        val timeout = assertFailsWith<TimeoutCancellationException> {
            withTimeout(1) { awaitCancellation() }
        }
        withContext(dispatcher) { scope.cancel(timeout) }

        assertIs<McuMgrTimeoutException>(request.await().exceptionOrNull())
    }

    @Test
    fun `a transport with a canceled scope fails the requests`() = transportTest {
        val canceled = CoroutineScope(SupervisorJob() + dispatcher).apply { cancel() }
        val orphan = McuMgrBleTransport(manager, peripheral, canceled)

        val request = orphan.sendEcho("0")

        assertIs<McuMgrDisconnectedException>(request.await().exceptionOrNull())
        assertTrue(device.received.isEmpty())
    }

    @Test
    fun `errors are reported as before`() = transportTest {
        transport.connect()

        // A packet longer than the buffer on the device cannot be sent. On the transport's
        // thread, a callback called from send() itself would have been called already.
        val tooLong = withContext(dispatcher) {
            transport.sendEcho("x".repeat(BUFFER_SIZE)).also { assertEquals(0, it.count) }
        }
        assertIs<InsufficientMtuException>(tooLong.await().exceptionOrNull())

        // A response with an error code fails the request.
        device.echoReturnCode = 1
        val rejected = transport.sendEcho("rc")
        assertIs<McuMgrErrorException>(rejected.await().exceptionOrNull())
    }

    @Test
    fun `a request without a response times out once`() = transportTest {
        val lateResponseIgnored = transport.awaitLog("no request awaiting it")
        transport.connect()
        device.holdsAnswers = true

        // The SMP session times out the response.
        val sentAt = TimeSource.Monotonic.markNow()
        val request = transport.sendEcho("0", timeout = 300)
        val error = request.await().exceptionOrNull()
        val elapsed = sentAt.elapsedNow()
        assertIs<McuMgrTimeoutException>(error)
        assertIs<TransactionTimeoutException>(error.cause)
        assertTrue(elapsed < 2.seconds, "The 300 ms request timed out after $elapsed")

        // The late response reaches the session, which ignores it.
        device.answerHeld()
        lateResponseIgnored.await()
        assertEquals(1, request.count)
    }

    @Test
    fun `a request whose write fails is reported once`() = transportTest {
        val timedOut = transport.awaitLog("timed out")
        transport.connect()
        device.failsEcho = true

        // The write fails, and the SMP session takes the request out, so it does not time out.
        val request = transport.sendEcho("0", timeout = 300)
        val error = request.await().exceptionOrNull()
        assertIs<McuMgrException>(error)
        assertTrue(error.cause !is TransactionTimeoutException, "The write failure was not reported")
        delay(600)
        assertFalse(timedOut.isCompleted, "The request timed out after its write failed")

        // The request is not reported again, and the transport goes on.
        device.failsEcho = false
        assertEquals("1", transport.sendEcho("1").await().getOrThrow())
        assertEquals(1, request.count)
    }

    /**
     * Sets a logger on the transport, and returns a deferred completed once it logs a message
     * containing the given text.
     */
    private fun McuMgrBleTransport.awaitLog(text: String): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { logged ->
            setLogger { _, _, _, _, message ->
                if (text in message()) logged.complete(Unit)
            }
        }
}

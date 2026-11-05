package no.nordicsemi.android.mcumgr.ble.callback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** The session runs in virtual time, on the scheduler of the test. */
class SmpProtocolSessionTest {
    private val testScope = TestScope()
    private val session = SmpProtocolSession(
        scope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler)),
        sink = null,
    )

    /** A request, which records how it was completed. */
    private open inner class Request : SmpTransaction {
        val sent = mutableListOf<ByteArray>()
        val responses = mutableListOf<ByteArray>()
        val failures = mutableListOf<Throwable>()

        override suspend fun send(data: ByteArray) { sent += data }
        override fun onResponse(data: ByteArray) { responses += data }
        override fun onFailure(e: Throwable) { failures += e }
    }

    private val packet get() = ByteArray(8)

    /** The sequence number is the 7th byte of the SMP header. */
    private fun sequenceNumberOf(data: ByteArray) = data[6].toInt() and 0xFF

    /** A packet from the device, with the given sequence number. */
    private fun packetWith(sequenceNumber: Int) = ByteArray(8).also { it[6] = sequenceNumber.toByte() }

    @Test
    fun `cancelled request is not failed by the timeout`() = testScope.runTest {
        val request = Request()
        session.send(packet, 1.seconds, request)
        runCurrent()

        assertTrue(session.cancel(request))
        advanceTimeBy(2.seconds)
        runCurrent()

        assertTrue(request.failures.isEmpty())
        assertTrue(request.responses.isEmpty())
    }

    @Test
    fun `cancel after the timeout returns false`() = testScope.runTest {
        val request = Request()
        session.send(packet, 1.seconds, request)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertIs<TransactionTimeoutException>(request.failures.single())

        assertFalse(session.cancel(request))
        assertEquals(1, request.failures.size)
    }

    @Test
    fun `cancel after the response returns false`() = testScope.runTest {
        val request = object : Request() {
            // The device echoes the packet, which has the sequence number set.
            override suspend fun send(data: ByteArray) = session.receive(data.copyOf())
        }
        session.send(packet, 1.seconds, request)
        runCurrent()
        assertEquals(1, request.responses.size)

        assertFalse(session.cancel(request))
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(request.failures.isEmpty())
    }

    @Test
    fun `request failing to send is completed once`() = testScope.runTest {
        // Like the request of SmpProfile: when the write fails, it is cancelled in the session
        // and reported by the request itself, only if it was still pending.
        val request = object : Request() {
            override suspend fun send(data: ByteArray) {
                if (session.cancel(this)) {
                    onFailure(IllegalArgumentException("write failed"))
                }
            }
        }
        session.send(packet, 1.seconds, request)
        runCurrent()
        // The timeout does not fail it again, nor does closing the session.
        advanceTimeBy(2.seconds)
        runCurrent()
        session.close(IllegalStateException("closed"))

        assertIs<IllegalArgumentException>(request.failures.single())
    }

    @Test
    fun `request timing out while the write is blocked is not reported again`() = testScope.runTest {
        // The write fails after the timeout, so the request has been completed already.
        val request = object : Request() {
            override suspend fun send(data: ByteArray) {
                kotlinx.coroutines.delay(2.seconds)
                if (session.cancel(this)) {
                    onFailure(IllegalArgumentException("write failed"))
                }
            }
        }
        session.send(packet, 1.seconds, request)
        advanceTimeBy(3.seconds)
        runCurrent()

        assertIs<TransactionTimeoutException>(request.failures.single())
    }

    @Test
    fun `request that was not sent can not be cancelled`() = testScope.runTest {
        assertFalse(session.cancel(Request()))
    }

    @Test
    fun `responses are matched to the requests by the sequence number`() = testScope.runTest {
        val requests = List(3) { Request() }
        requests.forEach { session.send(packet, 5.seconds, it) }
        runCurrent()
        assertEquals(listOf(0, 1, 2), requests.map { sequenceNumberOf(it.sent.single()) })

        // The device answers in the reverse order.
        requests.reversed().forEach { session.receive(it.sent.single().copyOf()) }
        runCurrent()

        requests.forEach {
            assertContentEquals(it.sent.single(), it.responses.single())
        }
        // The response cancels the timeout of its request.
        advanceTimeBy(6.seconds)
        runCurrent()
        requests.forEach { assertTrue(it.failures.isEmpty()) }
    }

    @Test
    fun `the timeout of a request that got a response does not fail the one reusing its number`() = testScope.runTest {
        // The first request gets its response, with a timeout much shorter than the others.
        val first = Request()
        session.send(packet, 1.seconds, first)
        runCurrent()
        session.receive(first.sent.single().copyOf())
        runCurrent()
        assertEquals(1, first.responses.size)

        // After the sequence numbers 1..255 the next request reuses the number 0.
        repeat(255) {
            session.send(packet, 1.hours, Request())
            runCurrent()
        }
        val reusing = Request()
        session.send(packet, 1.hours, reusing)
        runCurrent()
        assertEquals(0, sequenceNumberOf(reusing.sent.single()))

        // The timeout of the first request would elapse now, if it was not cancelled.
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(reusing.failures.isEmpty())
        assertTrue(first.failures.isEmpty())
    }

    @Test
    fun `a response with no request awaiting it is ignored`() = testScope.runTest {
        val request = Request()
        session.send(packet, 5.seconds, request)
        runCurrent()

        session.receive(packetWith(5))
        runCurrent()
        assertTrue(request.responses.isEmpty())
        assertTrue(request.failures.isEmpty())

        // The request still gets its response, once, also when it is repeated.
        session.receive(request.sent.single().copyOf())
        session.receive(request.sent.single().copyOf())
        runCurrent()
        assertEquals(1, request.responses.size)
    }

    @Test
    fun `a request without a response times out with its sequence number`() = testScope.runTest {
        repeat(2) { session.send(packet, 5.seconds, Request()) }
        val request = Request()
        session.send(packet, 1.seconds, request)
        advanceTimeBy(2.seconds)
        runCurrent()

        val timeout = assertIs<TransactionTimeoutException>(request.failures.single())
        assertEquals(2, timeout.id)
    }

    @Test
    fun `sequence numbers rotate`() = testScope.runTest {
        val requests = List(300) { Request() }
        requests.forEach {
            session.send(packet, 1.hours, it)
            // The queue holds 256 requests, so let the writer take each of them.
            runCurrent()
        }
        assertEquals(List(300) { it % 256 }, requests.map { sequenceNumberOf(it.sent.single()) })
    }

    @Test
    fun `a request is failed when its sequence number is reused`() = testScope.runTest {
        val requests = List(257) { Request() }
        requests.forEach {
            session.send(packet, 1.hours, it)
            runCurrent()
        }

        // The 257th request got the sequence number of the first one, which is still waiting.
        assertEquals(0, sequenceNumberOf(requests.last().sent.single()))
        val overwritten = assertIs<TransactionOverwriteException>(requests.first().failures.single())
        assertEquals(0, overwritten.id)
        requests.drop(1).forEach { assertTrue(it.failures.isEmpty()) }

        // The timeout of the failed request has been cancelled, it is not reported again.
        advanceTimeBy(2.hours)
        runCurrent()
        assertEquals(1, requests.first().failures.size)
    }

    @Test
    fun `closing the session fails the pending requests once`() = testScope.runTest {
        val requests = List(3) { Request() }
        requests.forEach { session.send(packet, 1.seconds, it) }
        runCurrent()
        val closed = IllegalStateException("closed")

        session.close(closed)
        advanceTimeBy(2.seconds)
        runCurrent()

        requests.forEach { assertEquals(listOf<Throwable>(closed), it.failures) }
        // A closed session does not take requests.
        assertFailsWith<IllegalStateException> { session.send(packet, 1.seconds, Request()) }
    }

    @Test
    fun `requests are not accepted when the transmit queue is full`() = testScope.runTest {
        // The writer blocks on the first request, so the next 256 fill the queue.
        val unblock = CompletableDeferred<Unit>()
        val blocking = object : Request() {
            override suspend fun send(data: ByteArray) = unblock.await()
        }
        session.send(packet, 1.hours, blocking)
        runCurrent()
        repeat(256) { session.send(packet, 1.hours, Request()) }

        assertFailsWith<IllegalStateException> { session.send(packet, 1.hours, Request()) }

        unblock.complete(Unit)
        runCurrent()
    }

    @Test
    fun `responses are not accepted when the receive queue is full`() = testScope.runTest {
        // Nothing runs until the test suspends, so the reader has not taken any of them.
        repeat(256) { session.receive(packetWith(it)) }

        assertFailsWith<IllegalStateException> { session.receive(packetWith(0)) }
    }
}

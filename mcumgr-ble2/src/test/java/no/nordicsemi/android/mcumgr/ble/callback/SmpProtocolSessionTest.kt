package no.nordicsemi.android.mcumgr.ble.callback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
        val responses = mutableListOf<ByteArray>()
        val failures = mutableListOf<Throwable>()

        override suspend fun send(data: ByteArray) {}
        override fun onResponse(data: ByteArray) { responses += data }
        override fun onFailure(e: Throwable) { failures += e }
    }

    private val packet get() = ByteArray(8)

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
}

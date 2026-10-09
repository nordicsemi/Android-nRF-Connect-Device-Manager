package no.nordicsemi.android.mcumgr.ble.util

import android.os.ConditionVariable
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import java.util.concurrent.TimeoutException

internal class ResultCondition<T>(state: Boolean) {
    private var result: T? = null
    private var exception: McuMgrException? = null
    private val lock: ConditionVariable = ConditionVariable(state)

    @Throws(McuMgrException::class)
    fun block(): T {
        lock.block()
        val exception = this.exception
        val result = this.result
        when {
            exception != null -> throw exception
            result == null -> throw IllegalStateException("Condition result must not be null.")
            else -> return result
        }
    }

    @Throws(McuMgrException::class, TimeoutException::class)
    fun block(timeout: Long): T? {
        return if (lock.block(timeout)) {
            result
        } else {
            throw TimeoutException("Condition timed out!")
        }
    }

    fun close() {
        lock.close()
    }

    fun open(result: T) {
        this.result = result
        lock.open()
    }

    fun openExceptionally(exception: McuMgrException) {
        this.exception = exception
        lock.open()
    }

}

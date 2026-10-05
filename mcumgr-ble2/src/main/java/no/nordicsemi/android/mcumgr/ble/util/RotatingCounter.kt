package no.nordicsemi.android.mcumgr.ble.util

/**
 * Helper class for managing a counter which rotates between 0 and a max value.
 * Equivalent to unsigned int overflow. This class is not thread safe.
 */
internal class RotatingCounter(private val max: Int) {

    private var value = 0

    fun getAndRotate(): Int {
        val tmp = value
        value = value.rotate()
        return tmp
    }

    private fun Int.rotate(): Int {
        return if (this == max) {
            0
        } else {
            this + 1
        }
    }
}

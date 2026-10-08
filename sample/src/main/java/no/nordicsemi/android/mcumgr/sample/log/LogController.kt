package no.nordicsemi.android.mcumgr.sample.log

/**
 * Controls how much the Bluetooth LE stack logs.
 *
 * The low-level GATT events are the most numerous entries in the log, and writing them while an
 * upload is running slows the transfer down measurably. The view models therefore turn them off
 * for the duration of a transfer, which is what the old transport's `setLoggingEnabled` did.
 *
 * Unlike then, the entries no longer come from the Mcu Manager transport but from the Kotlin BLE
 * Library, so the switch is consulted by the sink assigned to the `CentralManager`.
 */
class LogController {

    /** Whether the Bluetooth LE stack should log everything, or only warnings and errors. */
    @Volatile
    var verbose: Boolean = true
}
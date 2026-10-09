package no.nordicsemi.android.mcumgr.sample.log

import no.nordicsemi.kotlin.log.Log

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

    /**
     * Whether the Bluetooth LE stack should log everything, or only warnings and errors.
     *
     * This is what the view models request. It is a request for the *connected* link only, see
     * [linkReady].
     */
    @Volatile
    var verbose: Boolean = true

    /**
     * Whether the link to the device is up and ready to use.
     *
     * The upload keeps its state across a disconnection: the library reconnects and resends the
     * packet, with the firmware upgrade still in the "upload" state, so the view models have no
     * event they could use to turn the logs back on. Everything that happens while the link is
     * not ready (the disconnection, reconnection, service discovery...) is therefore always logged,
     * regardless of [verbose], and the quiet mode takes effect again once the link is ready.
     */
    @Volatile
    var linkReady: Boolean = false

    /** Whether an entry of the given level should be logged. */
    fun isLoggable(level: Log.Level): Boolean = verbose || !linkReady || level >= Log.Level.WARN
}

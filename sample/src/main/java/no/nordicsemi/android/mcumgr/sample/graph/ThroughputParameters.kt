package no.nordicsemi.android.mcumgr.sample.graph

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.ble.McuMgrBleTransport
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse

/**
 * Everything the throughput graph shows about the link: the connection parameters, the packet
 * sizes and the PHY in use.
 *
 * The connection parameters are the Bluetooth LE library's own
 * [no.nordicsemi.kotlin.ble.core.ConnectionParameters.Specified]; this class only adds the values that come from elsewhere,
 * and presents the PHY as the plain integers the graph renders.
 *
 * @property parameters The connection parameters of the link.
 * @property mtu The ATT MTU, in bytes.
 * @property bufferSize The largest SMP packet the device accepts, in bytes.
 */
class ThroughputParameters(
    val parameters: ConnectionParameters,
    private val phy: PhyInUse?,
    val mtu: Int,
    val bufferSize: Int,
) {
    /** Connection interval in 1.25 ms units. */
    val interval: Int?
        get() = (parameters as? ConnectionParameters.Specified)?.connectionInterval

    /** Connection interval in milliseconds. */
    val intervalInMs: Float?
        get() = interval?.times(1.25f)

    /** Slave latency, as a number of connection events. */
    val latency: Int?
        get() = (parameters as? ConnectionParameters.Specified)?.latency

    /** Supervision timeout in 10 ms units. */
    val timeout: Int?
        get() = (parameters as? ConnectionParameters.Specified)?.supervisionTimeout

    /** Supervision timeout in milliseconds. */
    val timeoutInMs: Long?
        get() = timeout?.times(10L)

    /** The TX PHY in use, or null if unknown (e.g. disconnected). */
    val txPhy: Phy?
        get() = phy?.txPhy

    /** The RX PHY in use, or null if unknown (e.g. disconnected). */
    val rxPhy: Phy?
        get() = phy?.rxPhy

    companion object {
        /**
         * Observes the link behind the given transport.
         *
         * @param transport The transport in use.
         * @param scope The coroutine scope the observers run in.
         * @return A live data with the parameters of the link, which holds null while they are not
         * available (e.g. the device is disconnected), or null if the transport is not a Bluetooth LE one
         * and therefore has no link to report on.
         */
        @JvmStatic
        fun observe(
            transport: McuMgrTransport,
            scope: CoroutineScope,
        ): LiveData<ThroughputParameters?>? {
            if (transport !is McuMgrBleTransport) {
                return null
            }
            val peripheral = transport.peripheral
            val liveData = MutableLiveData<ThroughputParameters?>()
            combine(
                peripheral.connectionParameters,
                peripheral.phy,
                transport.state,
            ) { parameters, phy, state ->
                if (state != McuMgrBleTransport.State.Connected) {
                    return@combine null
                }
                val connectionParameters = parameters as? ConnectionParameters.Specified
                    ?: return@combine null
                val mtu = peripheral.mtu.value ?: return@combine null
                val bufferSize = transport.maxPacketLength ?: (mtu - 3)
                ThroughputParameters(connectionParameters, phy, mtu, bufferSize)
            }
                .onEach { parameters -> liveData.postValue(parameters) }
                .launchIn(scope)
            return liveData
        }
    }
}
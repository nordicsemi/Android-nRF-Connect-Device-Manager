@file:Suppress("unused")

package no.nordicsemi.android.observability.internal

import no.nordicsemi.android.observability.bluetooth.MonitoringAndDiagnosticsConnection.State
import no.nordicsemi.android.observability.bluetooth.MonitoringAndDiagnosticsConnection.State.Disconnected.Reason
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.ConnectionState.Connected
import no.nordicsemi.kotlin.ble.core.ConnectionState.Connecting
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.Cancelled
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.InsufficientAuthentication
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.LinkLoss
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.Success
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.TerminateLocalHost
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.TerminatePeerUser
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.Timeout
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.UnacceptableConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.Unknown
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.UnsupportedAddress
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnected.Reason.UnsupportedConfiguration
import no.nordicsemi.kotlin.ble.core.ConnectionState.Disconnecting

internal fun ConnectionState.map(
	notSupported: Boolean = false,
	bondingFailed: Boolean = false,
): State = when (this) {
	// Note, that both Connecting and Connected translate to State.Connecting.
	// The DeviceState.Connected state is emitted after service discovery and initialization.
	Connecting,	Connected -> State.Connecting
	Disconnecting -> State.Disconnecting
	// For Disconnected state, we map the reasons.
	is Disconnected ->
		when (reason) {
			null, Success, Cancelled ->
				State.Disconnected(
					reason = when {
						bondingFailed -> Reason.BONDING_FAILED
						notSupported -> Reason.NOT_SUPPORTED
						else -> null
					}
				)
			is Timeout ->
				State.Disconnected(Reason.TIMEOUT)
			Disconnected.Reason.RequiredServiceNotFound ->
				State.Disconnected(Reason.NOT_SUPPORTED)
			TerminateLocalHost -> if (bondingFailed)
				State.Disconnected(Reason.BONDING_FAILED)
				else State.Disconnected(Reason.CONNECTION_LOST)
			TerminatePeerUser, LinkLoss ->
				State.Disconnected(Reason.CONNECTION_LOST)
			UnsupportedAddress, InsufficientAuthentication ->
				State.Disconnected(Reason.BONDING_FAILED)
			is Unknown, UnsupportedConfiguration, UnacceptableConnectionParameters ->
				State.Disconnected(Reason.FAILED_TO_CONNECT)
		}
}
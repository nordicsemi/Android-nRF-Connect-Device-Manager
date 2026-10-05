package no.nordicsemi.android.mcumgr.ble.exception

import no.nordicsemi.android.mcumgr.exception.McuMgrException

/** Thrown when the connection attempt failed because Bluetooth is disabled. */
class McuMgrBluetoothDisabledException : McuMgrException()

/** Thrown when the device disconnected, or the connection attempt was cancelled. */
class McuMgrDisconnectedException : McuMgrException()

/**
 * Thrown when the link could not be encrypted, usually because the bond information was removed
 * from the peer device.
 */
class McuMgrInsufficientAuthenticationException : McuMgrException()

/** Thrown when the device does not support the SMP service. */
class McuMgrNotSupportedException : McuMgrException()

/**
 * Thrown when the Android device failed to set up the requested connection, for example when it
 * did not reply to a PHY request.
 */
class McuMgrUnsupportedConfigurationException : McuMgrException()

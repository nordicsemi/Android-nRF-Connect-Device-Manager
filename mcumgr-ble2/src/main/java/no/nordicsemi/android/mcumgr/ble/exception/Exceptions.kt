package no.nordicsemi.android.mcumgr.ble.exception

import no.nordicsemi.android.mcumgr.exception.McuMgrException

/** Thrown when the connection attempt failed because Bluetooth is disabled. */
class McuMgrBluetoothDisabledException : McuMgrException("Bluetooth is disabled")

/** Thrown when the device disconnected, or the connection attempt was canceled. */
class McuMgrDisconnectedException : McuMgrException("Device disconnected")

/**
 * Thrown when the link could not be encrypted, usually because the bond information was removed
 * from the peer device.
 */
class McuMgrInsufficientAuthenticationException : McuMgrException("Insufficient authentication")

/** Thrown when the device does not support the SMP service. */
class McuMgrNotSupportedException : McuMgrException("SMP service is not supported")

/**
 * Thrown when the Android device failed to set up the requested connection, for example when it
 * did not reply to a PHY request.
 */
class McuMgrUnsupportedConfigurationException : McuMgrException("Unsupported configuration")

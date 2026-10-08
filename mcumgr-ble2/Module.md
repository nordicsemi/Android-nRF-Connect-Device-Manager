# Module mcumgr-ble2

Bluetooth LE transport implementation for Mcu Manager.

This module uses the [Kotlin-BLE-Library](https://github.com/nordicsemi/Kotlin-BLE-Library) to
handle Bluetooth LE communication. It is a replacement for `mcumgr-ble`, which is built on the
older, Java [Android-BLE-Library](https://github.com/nordicsemi/Android-BLE-Library). Both modules
declare the same classes in the same package, so exactly one of them can be used at a time. The old
module is still supported; migrating is not required. The APIs of the two do not match, so
switching an existing app needs code changes. This module is intended for apps built on the Kotlin
BLE Library and for new apps.

## Key Components

- **`McuMgrBleTransport`**: Implements `McuMgrTransport` for Bluetooth LE. It owns the connection
  to the peripheral and attaches `SmpProfile` to it once connected.
- **`SmpProfile`**: An implementation of the Simple Management Protocol (SMP) service, as a
  `Profile.Simple`. It subscribes to the SMP characteristic, reassembles responses split over
  several notifications, and matches each of them to the request awaiting it.
- **`UuidConfig`** / **`DefaultMcuMgrUuidConfig`**: The SMP service and characteristic UUIDs,
  which can be customized.

## State

`McuMgrBleTransport.state` is a `StateFlow` of `Disconnected`, `Initializing` (connecting, discovering
services and setting up the SMP service) and `Connected`. The `peripheral` property exposes the
Kotlin BLE Library `Peripheral` in use, which also provides the MTU, PHY and connection parameters.

## Observability and other profiles

The transport attaches `SmpProfile` to the `Peripheral`; profiles of the Kotlin BLE Library are
independent, so the application, or the `observability` module, can attach their own to the same
`Peripheral`. For example, `ObservabilityManager.connect(peripheral, required = false)` attaches
`MonitoringAndDiagnosticsProfile` to the transport's `Peripheral` (as the sample app does), and both
features share one connection. The transport keeps the connection to itself, as it needs to find
the device again when it changes to the Firmware Loader and advertises under a different address.

## Layers and logging

The transport reports under two of the Mcu Manager log categories:

- `Category.TRANSPORT` — connecting, service discovery, and enabling notifications.
- `Category.PROTOCOL` — SMP framing: sequence numbers, matching responses to requests, and the
  timeouts and overwrites that result when a response does not arrive.

Low-level GATT events come from the Kotlin BLE Library itself, and are reported to the sink
assigned to the `CentralManager` or the `Peripheral`, using that library's own `Layer` category.

## Example

```kotlin
// The application owns the CentralManager and the coroutine scope.
val peripheral = centralManager.getPeripheralById(address)!!

val transport = McuMgrBleTransport(centralManager, peripheral, scope)
transport.setLogger(logSink)

// Optional: observe connection state changes.
transport.addObserver(object : McuMgrTransport.ConnectionObserver {
    override fun onConnected() { /* connected */ }
    override fun onDisconnected() { /* disconnected */ }
})

// Use the transport with a manager. The transport connects on demand.
val manager = DefaultManager(transport)
manager.setLogger(logSink)

// ...

// Disconnect when done.
transport.release()
```

# Package no.nordicsemi.android.mcumgr.ble

Contains `McuMgrBleTransport`, `SmpProfile` and the SMP UUID configuration.

# Package no.nordicsemi.android.mcumgr.ble.callback

Contains the SMP framing layer: `SmpProtocolSession` (internal), which assigns sequence numbers to outgoing
requests and matches responses back to them, and the exceptions it reports —
`TransactionTimeoutException` and `TransactionOverwriteException`.

# Package no.nordicsemi.android.mcumgr.ble.exception

Contains Bluetooth-specific exceptions, such as `McuMgrDisconnectedException` and
`McuMgrBluetoothDisabledException`.

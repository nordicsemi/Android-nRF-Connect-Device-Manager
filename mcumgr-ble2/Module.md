# Module mcumgr-ble2

Bluetooth LE transport implementation for Mcu Manager.

This module uses the [Kotlin-BLE-Library](https://github.com/nordicsemi/Kotlin-BLE-Library) to
handle Bluetooth LE communication. It is a replacement for `mcumgr-ble`, which is built on the
older, Java [Android-BLE-Library](https://github.com/nordicsemi/Android-BLE-Library). Both modules
declare the same classes in the same package, so exactly one of them can be used at a time.

## Key Components

- **`McuMgrBleTransport`**: Implements `McuMgrTransport` for Bluetooth LE. It owns the connection
  to the peripheral and attaches `SmpProfile` to it once connected.
- **`SmpProfile`**: An implementation of the Simple Management Protocol (SMP) service, as a
  `Profile.Simple`. It subscribes to the SMP characteristic, reassembles responses split over
  several notifications, and matches each of them to the request awaiting it.
- **`UuidConfig`** / **`DefaultMcuMgrUuidConfig`**: The SMP service and characteristic UUIDs,
  which can be customized.

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

Contains the SMP framing layer: `SmpProtocolSession`, which assigns sequence numbers to outgoing
requests and matches responses back to them, and the exceptions it reports —
`TransactionTimeoutException` and `TransactionOverwriteException`.

# Package no.nordicsemi.android.mcumgr.ble.exception

Contains Bluetooth-specific exceptions, such as `McuMgrDisconnectedException` and
`McuMgrBluetoothDisabledException`.

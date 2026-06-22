# Module mcumgr-ble

Bluetooth LE transport implementation for Mcu Manager. This module uses the [Android-BLE-Library](https://github.com/NordicSemiconductor/Android-BLE-Library) to handle BLE communication.

## Key Components

- **`McuMgrBleTransport`**: Implements `McuMgrTransport` for Bluetooth LE. It manages the connection to a peripheral and handles SMP characteristic discovery and communication.
- **`DefaultMcuMgrUuidConfig`**: Provides default SMP Service and Characteristic UUIDs.

## Example

```java
// Create the transport
McuMgrBleTransport transport = new McuMgrBleTransport(context, bluetoothDevice);

// Optional: Set connection observers
transport.addObserver(new McuMgrTransport.ConnectionObserver() {
    @Override
    public void onConnected() {
        // Connected to the device
    }

    @Override
    public void onDisconnected() {
        // Disconnected
    }
});

// Use the transport with a manager
DefaultManager manager = new DefaultManager(transport);
// ...
```

# Package no.nordicsemi.android.mcumgr.ble

This package contains the `McuMgrBleTransport` class.

# Package no.nordicsemi.android.mcumgr.ble.callback

This package contains the `SmpMerger` helper class for reassembling fragmented SMP packets.

# Package no.nordicsemi.android.mcumgr.ble.exception

Contains Bluetooth-specific exceptions, such as `McuMgrDisconnectedException` and `McuMgrBluetoothDisabledException`.

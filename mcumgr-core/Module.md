# Module mcumgr-core

The `mcumgr-core` module provides the core logic for the Mcu Manager library. It defines the base transport interface, the protocol managers, and the response data structures used to communicate with devices running nRF Connect SDK, Zephyr, or Apache Mynewt.

## Key Components

- **`McuMgrTransport`**: The interface for sending and receiving SMP packets.
- **Managers**: Specialized classes for different Mcu Manager groups:
    - `DefaultManager`: OS-level commands like echo, reset, and task statistics.
    - `ImageManager`: Firmware image management (list, upload, test, confirm).
    - `FsManager`: File system operations (download, upload).
    - `StatsManager`: Statistics retrieval.
    - `LogManager`: Log retrieval.
- **`McuMgrResponse`**: Base class for all Mcu Manager responses, handling CBOR/JSON decoding.

## Example

To use a manager, you need an implementation of `McuMgrTransport` (e.g., `McuMgrBleTransport` from the `:mcumgr-ble` module).

```java
// Initialize a manager (e.g., DefaultManager)
DefaultManager manager = new DefaultManager(transport);

// Send an echo command asynchronously
manager.echo("Hello World!", new McuMgrCallback<McuMgrEchoResponse>() {
    @Override
    public void onResponse(@NonNull McuMgrEchoResponse response) {
        // Handle success
        String echo = response.r;
    }

    @Override
    public void onError(@NonNull McuMgrException error) {
        // Handle error
    }
});
```

# Package no.nordicsemi.android.mcumgr

Contains core classes and interfaces, such as `McuManager`, `McuMgrTransport`, and `McuMgrCallback`.

# Package no.nordicsemi.android.mcumgr.dfu

Contains classes for firmware upgrade, including `FirmwareUpgradeManager` for McuBoot and SUIT.

# Package no.nordicsemi.android.mcumgr.exception

Defines library-specific exceptions, like `McuMgrException` and `McuMgrTimeoutException`.

# Package no.nordicsemi.android.mcumgr.managers

Contains all group managers used to interact with the device.

# Package no.nordicsemi.android.mcumgr.response

Contains the base response class and sub-packages for group-specific response DTO.

- `no.nordicsemi.android.mcumgr.response.dflt`: Default group responses.
- `no.nordicsemi.android.mcumgr.response.img`: Image group responses.
- `no.nordicsemi.android.mcumgr.response.fs`: File system group responses.
- `no.nordicsemi.android.mcumgr.response.log`: Log group responses.
- `no.nordicsemi.android.mcumgr.response.stat`: Stats group responses.

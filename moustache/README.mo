![Maven Central Version](https://img.shields.io/maven-central/v/no.nordicsemi.android/mcumgr-core?link=https%3A%2F%2Fcentral.sonatype.com%2Fsearch%3Fq%3Dno.nordicsemi.android)

# nRF Connect Device Manager

nRF Connect Device Manager library is compatible with Mcu Manager, a management subsystem supported
by nRF Connect SDK, Zephyr and Apache Mynewt.

The library provides a transport agnostic implementation of the McuManager protocol.
It contains two implementations of the BLE transport: one based on the
[Android-BLE-Library](https://github.com/nordicsemi/Android-BLE-Library) and one based on the
[Kotlin-BLE-Library](https://github.com/nordicsemi/Kotlin-BLE-Library).

> Minimum required Android version is 5.0 (Android Lollipop) due to a requirement for high MTU.

The sample application has been named **nRF Connect Device Manager** and is available on
[Google Play](https://play.google.com/store/apps/details?id=no.nordicsemi.android.nrfconnectdevicemanager).

## Documentation

Dokka documentation can be found [here](https://nordicsemi.github.io/Android-nRF-Connect-Device-Manager/html/index.html).

## Introduction

McuManager is an application layer protocol used to manage and monitor microcontrollers running
Apache Mynewt and Zephyr. More specifically, McuManager implements over-the-air (OTA) firmware upgrades,
log and stat collection, and file-system and configuration management.

### Command Groups

Commands in Mcu Manager are organized by functionality into command groups.
In this Android library, command groups are called managers and extend the `McuManager` class.

The managers (groups) implemented in the library are:

* **`DefaultManager`**: Contains commands relevant to the OS. This includes task and memory pool
  statistics, device time read & write, and device reset.
* **`ImageManager`**: Manage image state on the device and perform image uploads.
* **`BasicManager`**: Allows erasing application storage (factory reset) (NCS 2.0+).
* **`StatsManager`**: Read stats from the device.
* **`CrashManager`**: Read crash logs from the device (not supported in Zephyr or NCS).
* **`SettingsManager`**: Read/Write settings values on the device.
* **`LogManager`**: Collect logs from the device.
* **`FsManager`**: Download/upload files from the device file system.
* **`ShellManager`**: Execute shell commands.

In `ota` module:
* **`MemfaultManager`**: Read device metadata required for Memfault OTA updates (id = 128).

## Importing

> [!Important]
> In version 3.0 the package name was changed from `io.runtime.mcumgr` to `no.nordicsemi.android.mcumgr`.
> Use Find and Replace tool in Android Studio to replace all occurrences in your project.

For versions 2.x use *version2* branch or tags.

### McuManager BLE (Recommended)
Contains the core and a BLE transport implementation using Nordic's [Android-BLE-Library v2](https://github.com/nordicsemi/Android-BLE-Library).

```groovy
implementation 'no.nordicsemi.android:mcumgr-ble:{{VERSION}}'
```

The core module will be included automatically.

> Latest version targeting API 30 (Android 11) is 0.13.0-beta07.

### McuManager BLE 2 (Kotlin BLE Library) - Experimental
> [!Warning]
> This module is experimental and available since version 3.5.0. Its API may change in future
> releases, and it depends on a pre-release version of the Kotlin BLE Library.

An alternative BLE transport implementation, using Nordic's
[Kotlin-BLE-Library](https://github.com/nordicsemi/Kotlin-BLE-Library) (Kotlin coroutines and flows).
It is intended for apps built on the Kotlin BLE Library, to ease integration, and for new apps:
the transport uses the app's `CentralManager`, and the `Peripheral` can be used for other purposes at
the same time, for example by the [Observability](#observability) module.

```groovy
implementation 'no.nordicsemi.android:mcumgr-ble2:{{VERSION}}'
```

The core module will be included automatically.

> [!Important]
> `mcumgr-ble2` declares the same classes in the same package as `mcumgr-ble`, so only one of them
> can be added to an app. Their APIs do not match (for example, the transport is created from
> a `CentralManager` and a `Peripheral` instead of a `Context` and a `BluetoothDevice`), so switching
> an existing app requires code changes. The original `mcumgr-ble` is still supported, and apps
> using it do not need to migrate.

```kotlin
val peripheral = centralManager.getPeripheralById(address)!!
val transport = McuMgrBleTransport(centralManager, peripheral, scope)
```

See the [module documentation](mcumgr-ble2/Module.md) for details.

### McuManager Core
Core dependency only. Use if you want to provide your own transport implementation.

```groovy
implementation 'no.nordicsemi.android:mcumgr-core:{{VERSION}}'
```

> Latest version targeting API 30 (Android 11) is 0.13.0-beta07.

### McuManager OTA
Integration with nRF Cloud Over-the-Air (OTA) firmware update services.

```groovy
implementation 'no.nordicsemi.android:ota:{{VERSION}}'
```

### McuManager Observability
Integration with nRF Cloud Monitoring & Diagnostics Service (MDS).

```groovy
implementation 'no.nordicsemi.android:observability:{{VERSION}}'
```

### Migration from the original repo

The library was initially released as [McuManager Android Library](https://github.com/JuulLabs-OSS/mcumgr-android).
When migrating from the original version, change:

```groovy
implementation 'io.runtime.mcumgr:mcumgr-ble:0.XX.X'
```
to
```groovy
implementation 'no.nordicsemi.android:mcumgr-ble:{{VERSION}}'
```

Starting from version 3.0.0, the package name has changed from `io.runtime.mcumgr` to `no.nordicsemi.android.mcumgr`.
See [Importing](#importing) for more details.

---

## Firmware Upgrade

Firmware upgrade is generally a four-step process performed using commands from the `image` and
`default` commands groups: `upload`, `test`, `confirm`, and `reset`.

This library provides a `FirmwareUpgradeManager` as a convenience for upgrading the image running on a device.
It acts as a simple, mostly linear state machine which is determined by the `Mode`.
As the manager moves through the firmware upgrade process, state changes are provided through the
`FirmwareUpgradeCallback`'s `onStateChanged` method.

### Example
```java
// Initialize the BLE transporter with context and a BluetoothDevice
// (with `mcumgr-ble2`, use `new McuMgrBleTransport(centralManager, peripheral, scope)` instead).
McuMgrTransport transport = new McuMgrBleTransport(context, bluetoothDevice);

// Initialize the Firmware Upgrade Manager.
FirmwareUpgradeManager dfuManager = new FirmwareUpgradeManager(transport, dfuCallback)

FirmwareUpgradeManager.Settings advancedSettings = FirmwareUpgradeManager.Settings.Builder()
    // Set estimated swap time, in milliseconds. This is an approximate time required by the McuBoot
    // to swap images after a successful upgrade.
    .setEstimatedSwapTime(swapTime)
    // Since version 1.1 the window upload is stable. It allows to send multiple packets concurrently,
    // without the need to wait for a notification. This may speed up the upload process significantly,
    // but it needs to be supported on the device side. See MCUMGR_BUF_COUNT in Zephyr KConfig file.
    .setWindowCapacity(mcumgrBuffers)
    // The memory alignment is read when window upload capacity was set to 2+, otherwise is ignored.
    // For devices built on NCS 1.8 or older this may need to be set to 4 (4-byte alignment) on nRF5
    // devices. Each packet sent will be trimmed to have number of bytes dividable by given value.
    // Since NCS 1.9 the flash implementation can buffer unaligned data instead of discarding.
    .setMemoryAlignment(memoryAlignment)
    // This setting allows to erase application data before swapping images, and is
    // useful when switching to a different, incompatible application, or when upgrading by a major
    // version, when app storage is structured differently. Set to false by default.
    .setEraseAppSettings(false)
    .build();

// Set a mode: Confirm only, Test only, Test & Confirm or None.
// For multicore update only the first one is supported. See details below.
dfuManager.setMode(mode);

// Start the firmware upgrade with the image data.
dfuManager.start(image, advancedSettings);
```

To update multi-core device, use:
```java
ImageSet images = new ImageSet();
images.add(new TargetImage(0 /* image 0 */, appCoreImage));
images.add(new TargetImage(1 /* image 1 */, netCoreImage));
dfuManager.start(images, advancedSettings);
```
You may also use [`ZipPackage`](https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager/blob/main/sample/src/main/java/no/nordicsemi/android/mcumgr/sample/utils/ZipPackage.java)
class from the Sample app, which can unpack the ZIP file generated by `west` in Zephyr or nRF Connect SDK
(see [example](https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager/blob/main/sample/src/main/java/no/nordicsemi/android/mcumgr/sample/viewmodel/mcumgr/ImageUpgradeViewModel.java#L287-L311)).

### Firmware Upgrade Mode

The `FirmwareUpgradeManager` can be configured to perform different methods using `setMode(FirmwareUpgradeManager.Mode mode)`:

* **`TEST_AND_CONFIRM`**: **Recommended mode**. The process is `UPLOAD`, `TEST`, `RESET`, `CONFIRM`.
  Note that the device must support this feature (currently, multicore devices like nRF5340 do not).
* **`CONFIRM_ONLY`**: **Default mode**. Recommended for devices that don't support reverting firmware.
  The process is `UPLOAD`, `CONFIRM`, `RESET`.
* **`TEST_ONLY`**: Useful if you want to run tests manually before confirming.
  The process is `UPLOAD`, `TEST`, `RESET`.
* **`NONE`**: The process is `UPLOAD`, `RESET`. Automatically selected if the bootloader is in *DirectXIP* without revert mode.

> [!Note]
> Devices based on nRF5340 SoC support only `CONFIRM_ONLY` mode because the image from the
  Network Core cannot be read from the Application Core, making it impossible to temporarily save it.

The `FirmwareUpgradeManager` contains an additional state, `VALIDATE`, which precedes the upload.
It checks the current image state of the device in an attempt to bypass certain states.
For example, if the image to upload is already in slot 1, it will skip `UPLOAD` and move directly to `TEST` or `CONFIRM`.

### Software Update for Internet of Things (SUIT)

Starting from version 1.9, the library supports SUIT files. In this case, the selected mode is ignored.
The update is always started by sending a SUIT Envelope. The process is repeated until the device reboots.

## Observability

Integration with **nRF Cloud Monitoring & Diagnostics Service (MDS)**.

This module allows devices to upload binary "chunks" of data (e.g., logs, core dumps) to the
nRF Cloud via the mobile app using Bluetooth LE as transport protocol.

`ObservabilityManager` is decoupled from the underlying Bluetooth LE connection via the
`ChunksEmitter` interface. It works with both Mcu Manager transports. With `mcumgr-ble2` the same
Kotlin BLE Library `Peripheral` can be shared: `observabilityManager.connect(peripheral)` attaches
`MonitoringAndDiagnosticsProfile` to it, so the transport and the observability manager use one
connection.

### Key Components

- **`ObservabilityManager`**: The main interface for retrieving chunks and uploading them to nRF Cloud.
- **`ChunksEmitter`**: Interface exposing the diagnostics chunks, regardless of connection method.
- **`MonitoringAndDiagnosticsProfile`**: The `ChunksEmitter` implementation for the MDS service, 
  based on `Profile` from the [Kotlin BLE Library](https://github.com/nordicsemi/Kotlin-BLE-Library).
- **`MonitoringAndDiagnosticsConnection`**: A helper class for managing Bluetooth LE connection with 
  the above profile, for apps that do not use connection based on `Peripheral`.
- **`ChunksUploader`**: Handles uploading enqueued chunks to nRF Cloud.

## OTA

Integration with **nRF Cloud Over-the-Air (OTA)** firmware update services.

It allows checking for the latest firmware releases for a device and downloading them.

### Key Components

- **`OtaManager`**: Used to query nRF Cloud for available updates and download firmware binaries.
- **`MemfaultManager`**: An Mcu Manager group (id = 128) used to retrieve device metadata (serial number, current software version, etc.) required for update checks.

---

## License

This library is licensed under the Apache 2.0 license. For more info, see the `LICENSE` file.

## Related libraries

* **Flutter**: Available as [mcumgr_flutter](https://pub.dev/packages/mcumgr_flutter) on pub.dev.
* **.NET MAUI**: Available as [Laerdal.McuMgr](https://www.nuget.org/packages/Laerdal.McuMgr) on NuGet.
* **React Native**: Currently not supported. If you create one, let us know!

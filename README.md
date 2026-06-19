![Maven Central Version](https://img.shields.io/maven-central/v/no.nordicsemi.android/mcumgr-core?link=https%3A%2F%2Fcentral.sonatype.com%2Fsearch%3Fq%3Dno.nordicsemi.android)

# nRF Connect Device Manager

nRF Connect Device Manager library is compatible with Mcu Manager, a management subsystem supported
by nRF Connect SDK, Zephyr and Apache Mynewt.

The library provides a transport agnostic implementation of the McuManager protocol.
It contains a default implementation for BLE transport.

> Minimum required Android version is 5.0 (Android Lollipop) due to a requirement for high MTU.

The sample application has been named **nRF Connect Device Manager** and is available on
[Google Play](https://play.google.com/store/apps/details?id=no.nordicsemi.android.nrfconnectdevicemanager).

#### Note

This repository is a fork of the [McuManager Android Library](https://github.com/JuulLabs-OSS/mcumgr-android),
which has been deprecated. All new features and bug fixes will be added here. Please, migrate to the
new version to get future updates. See [migration guide](#migration-from-the-original-repo).

## Importing

#### McuManager BLE (Recommended)
Contains the core and a BLE transport implementation using Nordic's [Android-BLE-Library v2](https://github.com/nordicsemi/Android-BLE-Library).

```groovy
implementation 'no.nordicsemi.android:mcumgr-ble:2.7.4'
```

The core module will be included automatically.

> Latest version targeting API 30 (Android 11) is 0.13.0-beta07.

#### McuManager Core
Core dependency only. Use if you want to provide your own transport implementation.

```groovy
implementation 'no.nordicsemi.android:mcumgr-core:2.7.4'
```

> Latest version targeting API 30 (Android 11) is 0.13.0-beta07.

### Migration from the original repo

The library was initially released as [McuManager Android Library](https://github.com/JuulLabs-OSS/mcumgr-android).

When migrating from the original version, change
```groovy
implementation 'io.runtime.mcumgr:mcumgr-ble:0.XX.X'
```
to the above.

The API and package names have not been changed to ease migration.

# Introduction

McuManager is an application layer protocol used to manage and monitor microcontrollers running
Apache Mynewt and Zephyr. More specifically, McuManager implements over-the-air (OTA) firmware upgrades,
log and stat collection, and file-system and configuration management.

## Command Groups

McuManager are organized by functionality into command groups. In this Android library, command groups
are called managers and extend the `McuManager` class. The managers (groups) implemented in
the library are:

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

# Firmware Upgrade

Firmware upgrade is generally a four step process performed using commands from the `image` and
`default` commands groups: `upload`, `test`, `reset`, and `confirm`.

This library provides a `FirmwareUpgradeManager` as a convenience for upgrading the image running on a device.

### Example
```java
// Initialize the BLE transporter with context and a BluetoothDevice
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
    .build()

// Set a mode: Confirm only, Test only, Test & Confirm or None.
// For multi-core update only the first one is supported. See details below.
dfuManager.setMode(mode);

// Start the firmware upgrade with the image data.
dfuManager.start(image, advancedSettings)
```

To update multi-core device, use:
```java
ImageSet images = new ImageSet();
images.add(new TargetImage(0 /* image 0 */, appCoreImage));
images.add(new TargetImage(1 /* image 1 */, netCoreImage));
dfuManager.start(images, advancedSettings);
```
You may also use [`ZipPackage`](https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager/blob/main/sample/src/main/java/io/runtime/mcumgr/sample/utils/ZipPackage.java)
class from the Sample app, which can unpack the ZIP file generated by `west` in Zephyr or nRF Connect SDK
(see [example](https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager/blob/6009c574cc38420fcfa810d2df26d982005c5ac7/sample/src/main/java/io/runtime/mcumgr/sample/viewmodel/mcumgr/ImageUpgradeViewModel.java#L287-L311)).

## Firmware Upgrade Manager

A `FirmwareUpgradeManager` provides an easy way to perform firmware upgrades on a device.
A `FirmwareUpgradeManager` must be initialized with an `McuMgrTransport` which defines the transport
scheme and device. Once initialized, a `FirmwareUpgradeManager` can perform one firmware upgrade at a time.
Firmware upgrades are started using the `start(byte[] imageData, boolean eraseStorage)` or
`start(ImageSet images, FirmwareUpgradeManager.Settings settings)` methods and can be paused,
resumed, and canceled using `pause()`, `resume()`, and `cancel()` respectively.

### Firmware Upgrade Mode

McuManager firmware upgrades can actually be performed in few different ways. These different upgrade
modes determine the commands sent after the upload step. The `FirmwareUpgradeManager` can be
configured to perform these different methods using `setMode(FirmwareUpgradeManager.Mode mode)`.
The different firmware upgrade modes are as follows:

* **`TEST_AND_CONFIRM`**: This mode is the **recommended mode** for performing upgrades
  due to it's ability to recover from a bad firmware upgrade. Note, that the device must support
  this feature. Currently, multi-core devices (based on nRF5340) do not support this mode.
  The process for this mode is `UPLOAD`, `TEST`, `RESET`, `CONFIRM`.
* **`CONFIRM_ONLY`**: This mode may be used for devices with revert disabled. If the device fails
  to boot into the new image, it will not be able to recover and will need to be re-flashed.
  The process for this mode is `UPLOAD`, `CONFIRM`, `RESET`. This is the **default** mode,
  as some devices don't support reverting firmware (test mode).
* **`TEST_ONLY`**: This mode is useful if you want to run tests on the new image running before
  confirming it manually as the primary boot image.
  This mode is recommended for devices that do not support reverting images, i.e. multi core devices.
  The process for this mode is `UPLOAD`, `TEST`, `RESET`.
* **`NONE`**: This mode should be used if the bootloader does not support reverting images.
  The process for this mode is `UPLOAD`, `RESET`. If the device supports bootloader information
  command, and the bootloader is in DirectXIP without revert mode, this mode will be selected
  automatically. This mode was added in library version 1.8.

> [!Note]
> Devices based on nRF5340 SoC support only `CONFIRM_ONLY` mode because the image from the
  Network Core cannot be read from the Application Core, making it impossible to temporarily save it.

> [!Note]
> Read about MCUboot modes [here](https://docs.mcuboot.com/design.html#image-slots).

### Firmware Upgrade State

`FirmwareUpgradeManager` acts as a simple, mostly linear state machine which is determined by the `Mode`.
As the manager moves through the firmware upgrade process, state changes are provided through the
`FirmwareUpgradeCallback`'s `onStateChanged` method.

The `FirmwareUpgradeManager` contains an additional state, `VALIDATE`, which precedes the upload.
The `VALIDATE` state checks the current image state of the device in an attempt to bypass certain
states of the firmware upgrade. For example, if the image to upload is already in slot 1 on the
device, the `State` will skip `UPLOAD` and move directly to `TEST` (or `CONFIRM` if `Mode.CONFIRM_ONLY`
has been set). If the uploaded image is already active, and confirmed in slot 0, the upgrade will
succeed immediately. The `VALIDATE` state makes it easy to reattempt an upgrade without needing to
re-upload the image or manually determine where to start.

### Software Update for Internet of Things (SUIT)

Starting from version 1.9, the library supports SUIT (Software Update for Internet of Things) files.
In this case the selected mode is ignored. The process of upgrading is embedded in the SUIT file.

A new firmware can be delivered using:
- single .suit file with SUIT Envelope
- a ZIP file with .suit file, cache images and additional binary files.

The update is always started by sending a SUIT Envelope. When cache images are present in the ZIP
file, they are sent afterwards, each with a target partition ID. After sending a confirm command,
the library will poll every few seconds for additional resources. If the device requests a new
resource, it will be sent. The process is repeated until the device reboots, assuming successful
upgrade.

## License

This library is licensed under the Apache 2.0 license. For more info, see the `LICENSE` file.

## Related libraries

### Flutter

Flutter version of this library is available [here](https://github.com/nordicsemi/Flutter-nRF-Connect-Device-Manager)
and released as [mcumgr_flutter](https://pub.dev/packages/mcumgr_flutter) on pub.dev.

### .NET MAUI

.NET MAUI (old Xamarin) version of this library is available [here](https://github.com/Laerdal/Laerdal.McuMgr)
and released as [Laerdal.McuMgr](https://www.nuget.org/packages/Laerdal.McuMgr) on NuGet.

### React Native

As of now we are not aware of any React Native library that supports McuManager protocol.
If you are interested in creating one, please let us know and we will link it here.

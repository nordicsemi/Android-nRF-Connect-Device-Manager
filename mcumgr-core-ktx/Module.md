# Module mcumgr-core-ktx

Kotlin Coroutines and `Flow` API for the Mcu Manager library (`mcumgr-core`).

The managers in `mcumgr-core` are written in Java: their methods either block, or take a callback.
This module wraps them, so that they can be used naturally from Kotlin:

- single requests are `suspend` functions, which return the response, or throw an exception,
- long-running operations, that is uploading and downloading images and files, and the firmware
  upgrade, report their state using a `Flow`.

It works with any transport, including `mcumgr-ble` and `mcumgr-ble2`.

## Wrappers

The managers of the library have blocking and callback based methods named the same as the
suspending ones, so extension functions on them would never be called. Instead, each manager has a
wrapper, with `Ktx` at the end of its name, which holds the manager in the `manager` property. It can
be created from the manager, from the transport, or using the `ktx` extension property:

| Manager                  | Wrapper                      |
|--------------------------|------------------------------|
| `DefaultManager`         | `DefaultManagerKtx`          |
| `ImageManager`           | `ImageManagerKtx`            |
| `FsManager`              | `FsManagerKtx`               |
| `StatsManager`           | `StatsManagerKtx`            |
| `SettingsManager`        | `SettingsManagerKtx`         |
| `ShellManager`           | `ShellManagerKtx`            |
| `LogManager`             | `LogManagerKtx`              |
| `CrashManager`           | `CrashManagerKtx`            |
| `BasicManager`           | `BasicManagerKtx`            |
| `SUITManager`            | `SUITManagerKtx`             |
| `FirmwareUpgradeManager` | `FirmwareUpgradeManagerKtx`  |
| `SUITUpgradeManager`     | `SUITUpgradeManagerKtx`      |

Commands of custom groups can be sent using `McuManager.sendAndAwait(...)`.

## Requests

```kotlin
val os = DefaultManagerKtx(transport)

try {
    val response = os.echo("Hello")
    println(response.r)
} catch (e: McuMgrErrorException) {
    // The device responded with an error, for example "not supported".
    println(e.code)
} catch (e: McuMgrException) {
    // The request failed, for example because the device disconnected.
}
```

A response with a non-zero return code is thrown as `McuMgrErrorException`, instead of being
returned. Cancelling the coroutine stops waiting for the response, but does not cancel the request,
as it has already been sent to the device.

## Uploads and downloads

The flows are cold: the transfer starts when the flow is collected, and is cancelled when the
collection is. The first state is `TransferState.Started`, with a controller to pause, resume or
cancel the transfer. The flow completes after `Completed` (with the result, which is the data in
case of a download) or `Cancelled`, and fails with a `McuMgrException` if the transfer fails.

```kotlin
val fs = FsManagerKtx(transport)

fs.upload("/lfs/file.bin", data, windowCapacity = 4)
    .collect { state ->
        when (state) {
            is TransferState.Started -> controller = state.controller
            is TransferState.InProgress -> progress = state.progress
            is TransferState.Completed -> println("Done")
            TransferState.Cancelled -> println("Cancelled")
        }
    }

val bytes = fs.download("/lfs/file.bin")
    .filterIsInstance<TransferState.Completed<ByteArray>>()
    .first().result
```

## Firmware upgrade

`FirmwareUpgradeManagerKtx` (McuBoot) and `SUITUpgradeManagerKtx` (SUIT) perform the whole upgrade,
and report `FirmwareUpgradeState`: the state changes, the upload progress and the result. If the
upgrade fails, the flow throws a `FirmwareUpgradeException`, which contains the state in which it
failed.

```kotlin
val dfu = FirmwareUpgradeManagerKtx(transport)

dfu.upgrade(image, mode = FirmwareUpgradeManager.Mode.CONFIRM_ONLY)
    .collect { state ->
        when (state) {
            is FirmwareUpgradeState.Started -> controller = state.controller
            is FirmwareUpgradeState.StateChanged -> println(state.current)
            is FirmwareUpgradeState.UploadProgress -> progress = state.progress
            FirmwareUpgradeState.Completed -> println("Done")
            is FirmwareUpgradeState.Cancelled -> println("Cancelled")
        }
    }
```

A manager can perform one upgrade at a time, and keeps one callback, which the flow takes over for
the time of the upgrade. It also makes the manager call it on a background thread.

# Package no.nordicsemi.android.mcumgr.ktx

Contains `sendAndAwait` and the `ktx` extension properties of the managers.

# Package no.nordicsemi.android.mcumgr.ktx.managers

Contains the wrappers of the managers of Mcu Manager groups.

# Package no.nordicsemi.android.mcumgr.ktx.transfer

Contains `TransferState`, reported by the uploads and downloads.

# Package no.nordicsemi.android.mcumgr.ktx.dfu

Contains the wrappers of the firmware upgrade managers, and `FirmwareUpgradeState`.

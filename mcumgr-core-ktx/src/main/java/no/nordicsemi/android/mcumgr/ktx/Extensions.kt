/*
 * Copyright (c) 2026, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.ktx

import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager
import no.nordicsemi.android.mcumgr.dfu.suit.SUITUpgradeManager
import no.nordicsemi.android.mcumgr.ktx.dfu.FirmwareUpgradeManagerKtx
import no.nordicsemi.android.mcumgr.ktx.dfu.SUITUpgradeManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.BasicManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.CrashManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.DefaultManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.FsManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.ImageManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.LogManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.SUITManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.SettingsManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.ShellManagerKtx
import no.nordicsemi.android.mcumgr.ktx.managers.StatsManagerKtx
import no.nordicsemi.android.mcumgr.managers.BasicManager
import no.nordicsemi.android.mcumgr.managers.CrashManager
import no.nordicsemi.android.mcumgr.managers.DefaultManager
import no.nordicsemi.android.mcumgr.managers.FsManager
import no.nordicsemi.android.mcumgr.managers.ImageManager
import no.nordicsemi.android.mcumgr.managers.LogManager
import no.nordicsemi.android.mcumgr.managers.SUITManager
import no.nordicsemi.android.mcumgr.managers.SettingsManager
import no.nordicsemi.android.mcumgr.managers.ShellManager
import no.nordicsemi.android.mcumgr.managers.StatsManager

// The managers of the library have blocking and callback based methods with the same names as the
// suspending ones, so the suspending ones, being extensions, would never be called. That is why they
// are provided by wrappers, which are available using the properties below.

/** The suspending API of this manager. */
val DefaultManager.ktx: DefaultManagerKtx get() = DefaultManagerKtx(this)

/** The suspending API of this manager. */
val BasicManager.ktx: BasicManagerKtx get() = BasicManagerKtx(this)

/** The suspending API of this manager. */
val StatsManager.ktx: StatsManagerKtx get() = StatsManagerKtx(this)

/** The suspending API of this manager. */
val SettingsManager.ktx: SettingsManagerKtx get() = SettingsManagerKtx(this)

/** The suspending API of this manager. */
val CrashManager.ktx: CrashManagerKtx get() = CrashManagerKtx(this)

/** The suspending API of this manager. */
val LogManager.ktx: LogManagerKtx get() = LogManagerKtx(this)

/** The suspending API of this manager. */
val ShellManager.ktx: ShellManagerKtx get() = ShellManagerKtx(this)

/** The suspending API of this manager. */
val SUITManager.ktx: SUITManagerKtx get() = SUITManagerKtx(this)

/** The suspending and flow API of this manager. */
val ImageManager.ktx: ImageManagerKtx get() = ImageManagerKtx(this)

/** The suspending and flow API of this manager. */
val FsManager.ktx: FsManagerKtx get() = FsManagerKtx(this)

/** The flow API of this manager. */
val FirmwareUpgradeManager.ktx: FirmwareUpgradeManagerKtx get() = FirmwareUpgradeManagerKtx(this)

/** The flow API of this manager. */
val SUITUpgradeManager.ktx: SUITUpgradeManagerKtx get() = SUITUpgradeManagerKtx(this)

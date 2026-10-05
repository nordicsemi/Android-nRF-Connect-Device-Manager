package no.nordicsemi.android.mcumgr.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The UUID config allows providing a custom set of UUIDs for the SMP service.
 *
 * Use [DefaultMcuMgrUuidConfig] for the UUIDs defined by Mcu Manager.
 */
interface UuidConfig {

    /** The SMP service UUID. */
    val serviceUuid: Uuid

    /** The SMP characteristic UUID. */
    val characteristicUuid: Uuid
}

/**
 * The SMP service UUIDs defined by Mcu Manager.
 */
object DefaultMcuMgrUuidConfig : UuidConfig {

    /** The SMP service UUID. */
    val SMP_SERVICE_UUID: Uuid = Uuid.parse("8d53dc1d-1db7-4cd3-868b-8a527460aa84")

    /** The SMP characteristic UUID. */
    val SMP_CHARACTERISTIC_UUID: Uuid = Uuid.parse("da2e7828-fbce-4e01-ae9e-261174997c48")

    override val serviceUuid: Uuid = SMP_SERVICE_UUID
    override val characteristicUuid: Uuid = SMP_CHARACTERISTIC_UUID
}

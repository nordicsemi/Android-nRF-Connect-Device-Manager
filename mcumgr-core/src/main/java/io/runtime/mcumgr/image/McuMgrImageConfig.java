/*
 * Copyright (c) 2024 Nordic Semiconductor ASA
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.image;

import no.nordicsemi.android.mcumgr.image.tlv.McuMgrImageTlv;

/**
 * Global configuration for the magic numbers used when parsing McuBoot firmware image headers
 * and TLV trailers.
 * <p>
 * The default values match the standard McuBoot specification. If a device uses custom magic
 * numbers, call the appropriate setters <b>before</b> starting the SMP OTA upgrade.
 * <p>
 * Example usage:
 * <pre>
 *     // Set custom magic numbers before starting OTA
 *     McuMgrImageConfig.setHeaderMagic(0xCustomMagic);
 *     McuMgrImageConfig.setTlvInfoMagic(0xCustomTlvMagic);
 *     McuMgrImageConfig.setTlvProtectedInfoMagic(0xCustomProtectedTlvMagic);
 *
 *     // Start the OTA upgrade ...
 *
 *     // Optionally restore defaults afterwards
 *     McuMgrImageConfig.resetToDefaults();
 * </pre>
 */
@SuppressWarnings("unused")
public final class McuMgrImageConfig {

    // Default magic number values (standard McuBoot specification).
    private static final int DEFAULT_HEADER_MAGIC       = 0x96f3b83d;
    private static final int DEFAULT_HEADER_MAGIC_V1    = 0x96f3b83c;
    private static final int DEFAULT_TLV_INFO_MAGIC     = McuMgrImageTlv.IMG_TLV_INFO_MAGIC;
    private static final int DEFAULT_TLV_PROTECTED_INFO_MAGIC = McuMgrImageTlv.IMG_TLV_PROTECTED_INFO_MAGIC;

    // Volatile to ensure visibility across threads.
    private static volatile int sHeaderMagic            = DEFAULT_HEADER_MAGIC;
    private static volatile int sHeaderMagicV1          = DEFAULT_HEADER_MAGIC_V1;
    private static volatile int sTlvInfoMagic           = DEFAULT_TLV_INFO_MAGIC;
    private static volatile int sTlvProtectedInfoMagic  = DEFAULT_TLV_PROTECTED_INFO_MAGIC;

    private McuMgrImageConfig() {
        // Not instantiable.
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /**
     * Returns the expected magic number for the current McuBoot image header.
     * Default: {@code 0x96f3b83d}
     */
    public static int getHeaderMagic() {
        return sHeaderMagic;
    }

    /**
     * Returns the expected magic number for the legacy (V1) McuBoot image header.
     * Default: {@code 0x96f3b83c}
     */
    public static int getHeaderMagicV1() {
        return sHeaderMagicV1;
    }

    /**
     * Returns the expected magic number for the unprotected TLV info block.
     * Default: {@code 0x6907}
     */
    public static int getTlvInfoMagic() {
        return sTlvInfoMagic;
    }

    /**
     * Returns the expected magic number for the protected TLV info block.
     * Default: {@code 0x6908}
     */
    public static int getTlvProtectedInfoMagic() {
        return sTlvProtectedInfoMagic;
    }

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    /**
     * Sets a custom magic number for the current McuBoot image header.
     * <p>
     * Call this before starting the SMP OTA upgrade if the target device uses a non-standard
     * header magic value.
     *
     * @param magic The expected 32-bit header magic number.
     */
    public static void setHeaderMagic(int magic) {
        sHeaderMagic = magic;
    }

    /**
     * Sets a custom magic number for the legacy (V1) McuBoot image header.
     * <p>
     * Call this before starting the SMP OTA upgrade if the target device uses a non-standard
     * legacy header magic value.
     *
     * @param magic The expected 32-bit legacy header magic number.
     */
    public static void setHeaderMagicV1(int magic) {
        sHeaderMagicV1 = magic;
    }

    /**
     * Sets a custom magic number for the unprotected TLV info block.
     * <p>
     * Call this before starting the SMP OTA upgrade if the target device uses a non-standard
     * TLV info magic value.
     *
     * @param magic The expected 16-bit TLV info magic number.
     */
    public static void setTlvInfoMagic(int magic) {
        sTlvInfoMagic = magic;
    }

    /**
     * Sets a custom magic number for the protected TLV info block.
     * <p>
     * Call this before starting the SMP OTA upgrade if the target device uses a non-standard
     * protected TLV info magic value.
     *
     * @param magic The expected 16-bit protected TLV info magic number.
     */
    public static void setTlvProtectedInfoMagic(int magic) {
        sTlvProtectedInfoMagic = magic;
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    /**
     * Resets all magic numbers to the standard McuBoot default values.
     */
    public static void resetToDefaults() {
        sHeaderMagic           = DEFAULT_HEADER_MAGIC;
        sHeaderMagicV1         = DEFAULT_HEADER_MAGIC_V1;
        sTlvInfoMagic          = DEFAULT_TLV_INFO_MAGIC;
        sTlvProtectedInfoMagic = DEFAULT_TLV_PROTECTED_INFO_MAGIC;
    }
}

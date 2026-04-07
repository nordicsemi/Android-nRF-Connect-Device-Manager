/*
 * Copyright (c) Intellinium SAS, 2014-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.image.tlv;

import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.image.McuMgrImageConfig;
import no.nordicsemi.android.mcumgr.util.ByteUtil;
import no.nordicsemi.android.mcumgr.util.Endian;

/**
 * Represents a type-length-value info table for firmware images using McuBoot or the legacy Apache
 * Mynewt bootloader.
 * <p>
 * For more info about McuBoot and image format see:
 * <a href="https://juullabs-oss.github.io/mcuboot/design.html">https://juullabs-oss.github.io/mcuboot/design.html</a>
 */
@SuppressWarnings("unused")
public class McuMgrImageTlvInfo {
    private short mMagic;
    private short mTotal;

    private McuMgrImageTlvInfo() {
        // empty private constructor
    }

    public static McuMgrImageTlvInfo fromBytes(byte[] b) throws McuMgrException {
        return fromBytes(b, 0);
    }

    public static McuMgrImageTlvInfo fromBytes(byte[] b, int offset) throws McuMgrException {
        if (b.length - offset < getSize())
            throw new McuMgrException("The byte array is too short to be a McuMgrImageTlvInfo: " +
                    "length= " + b.length + ", offset= " + offset + ", min size= " + getSize());

        McuMgrImageTlvInfo info = new McuMgrImageTlvInfo();
        info.mMagic = (short) ByteUtil.byteArrayToUnsignedInt(b, offset, Endian.LITTLE, 2);
        info.mTotal = (short) ByteUtil.byteArrayToUnsignedInt(b, offset + 2, Endian.LITTLE, 2);

        if (info.mMagic != McuMgrImageConfig.getTlvInfoMagic() &&
                info.mMagic != McuMgrImageConfig.getTlvProtectedInfoMagic()) {
            throw new McuMgrException("Wrong magic number, magic=" + info.mMagic);
        }
        return info;
    }

    public static int getSize() {
        return 4;
    }

    public short getMagic() {
        return mMagic;
    }

    public short getTotal() {
        return mTotal;
    }

    public boolean isProtected() {
        return mMagic == McuMgrImageConfig.getTlvProtectedInfoMagic();
    }
}

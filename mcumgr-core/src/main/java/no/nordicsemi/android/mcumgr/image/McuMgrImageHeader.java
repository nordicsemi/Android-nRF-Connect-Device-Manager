/*
 * Copyright (c) Intellinium SAS, 2014-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.image;


import org.jetbrains.annotations.NotNull;

import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.util.ByteUtil;
import no.nordicsemi.android.mcumgr.util.Endian;

/**
 * Represents a firmware image header for devices using McuBoot or the legacy Apache Mynewt
 * bootloader.
 * <p>
 * For more info about McuBoot and image format see:
 * <a href="https://juullabs-oss.github.io/mcuboot/design.html">https://juullabs-oss.github.io/mcuboot/design.html</a>
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class McuMgrImageHeader {

    private static final int HEADER_LENGTH = 24;
    private final int mMagic;
    private final int mLoadAddr;
    private final short mHdrSize;
    private final int mImgSize;
    private final int mFlags;
    @NotNull
    private final McuMgrImageVersion mVersion;

    private McuMgrImageHeader(int magic,
                              int loadAddr,
                              short hdrSize,
                              int imgSize,
                              int flags,
                              @NotNull McuMgrImageVersion version) {
        mMagic = magic;
        mLoadAddr = loadAddr;
        mHdrSize = hdrSize;
        mImgSize = imgSize;
        mFlags = flags;
        mVersion = version;
    }

    @NotNull
    public static McuMgrImageHeader fromBytes(byte @NotNull [] b) throws McuMgrException {
        return fromBytes(b, 0);
    }

    @NotNull
    public static McuMgrImageHeader fromBytes(byte @NotNull [] b, int offset) throws McuMgrException {
        if (b.length - offset < getSize()) {
            throw new McuMgrException("The byte array is too short read McuMgr header");
        }

        int magic = ByteUtil.byteArrayToUnsignedInt(b, offset, Endian.LITTLE, 4);

        int expectedMagic   = McuMgrImageConfig.getHeaderMagic();
        int expectedMagicV1 = McuMgrImageConfig.getHeaderMagicV1();
        if (magic != expectedMagic && magic != expectedMagicV1) {
            throw new McuMgrException(String.format("Wrong magic number (found 0x%08X, expected 0x%08X or 0x%08X)", magic, expectedMagic, expectedMagicV1));
        }

        int loadAddr = ByteUtil.byteArrayToUnsignedInt(b, 4 + offset, Endian.LITTLE, 4);
        short hdrSize = (short) ByteUtil.byteArrayToUnsignedInt(b, 8 + offset, Endian.LITTLE, 2);
        int imgSize = ByteUtil.byteArrayToUnsignedInt(b, 12 + offset, Endian.LITTLE, 4);
        int flags = ByteUtil.byteArrayToUnsignedInt(b, 16 + offset, Endian.LITTLE, 4);
        McuMgrImageVersion version = McuMgrImageVersion.fromBytes(b, 20 + offset);

        return new McuMgrImageHeader(magic, loadAddr, hdrSize, imgSize, flags, version);
    }

    public static int getSize() {
        return McuMgrImageVersion.getSize() + HEADER_LENGTH;
    }

    public int getMagic() {
        return mMagic;
    }

    public int getLoadAddr() {
        return mLoadAddr;
    }

    public short getHdrSize() {
        return mHdrSize;
    }

    public int getImgSize() {
        return mImgSize;
    }

    public int getFlags() {
        return mFlags;
    }

    @NotNull
    public McuMgrImageVersion getVersion() {
        return mVersion;
    }

    public boolean isLegacy() {
        return mMagic == McuMgrImageConfig.getHeaderMagicV1();
    }
}

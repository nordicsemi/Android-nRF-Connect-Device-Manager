/*
 * Copyright (c) 2017-2018 Runtime Inc.
 * Copyright (c) Intellinium SAS, 2014-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.managers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

import no.nordicsemi.android.mcumgr.McuMgrCallback;
import no.nordicsemi.android.mcumgr.McuMgrErrorCode;
import no.nordicsemi.android.mcumgr.McuMgrGroupReturnCode;
import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.exception.InsufficientMtuException;
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.response.DownloadResponse;
import no.nordicsemi.android.mcumgr.response.HasReturnCode;
import no.nordicsemi.android.mcumgr.response.McuMgrResponse;
import no.nordicsemi.android.mcumgr.response.UploadResponse;
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsCrc32Response;
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsDownloadResponse;
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsSha256Response;
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsStatusResponse;
import no.nordicsemi.android.mcumgr.response.fs.McuMgrFsUploadResponse;
import no.nordicsemi.android.mcumgr.transfer.Download;
import no.nordicsemi.android.mcumgr.transfer.DownloadCallback;
import no.nordicsemi.android.mcumgr.transfer.StreamDownload;
import no.nordicsemi.android.mcumgr.transfer.StreamDownloadCallback;
import no.nordicsemi.android.mcumgr.transfer.StreamUpload;
import no.nordicsemi.android.mcumgr.transfer.TransferController;
import no.nordicsemi.android.mcumgr.transfer.TransferManager;
import no.nordicsemi.android.mcumgr.transfer.Upload;
import no.nordicsemi.android.mcumgr.transfer.UploadCallback;
import no.nordicsemi.android.mcumgr.util.CBOR;

@SuppressWarnings({"WeakerAccess", "unused", "DeprecatedIsStillUsed"})
public class FsManager extends TransferManager {

    public enum ReturnCode implements McuMgrGroupReturnCode {
        /** No error, this is implied if there is no ret value in the response */
        OK(0),

        /** Unknown error occurred. */
        UNKNOWN(1),

        /** The specified file name is not valid. */
        INVALID_NAME(2),

        /** The specified file does not exist. */
        NOT_FOUND(3),

        /** The specified file is a directory, not a file. */
        IS_DIRECTORY(4),

        /** Error occurred whilst attempting to open a file. */
        OPEN_FAILED(5),

        /** Error occurred whilst attempting to seek to an offset in a file. */
        SEEK_FAILED(6),

        /** Error occurred whilst attempting to read data from a file. */
        READ_FAILED(7),

        /** Error occurred whilst trying to truncate file. */
        TRUNCATE_FAILED(8),

        /** Error occurred whilst trying to delete file. */
        DELETE_FAILED(9),

        /** Error occurred whilst attempting to write data to a file. */
        WRITE_FAILED(10),

        /**
         * The specified data offset is not valid, this could indicate that the file on the device
         * has changed since the previous command. The length of the current file on the device is
         * returned as "len", the user application needs to decide how to handle this (e.g. the
         * hash of the file could be requested and compared with the hash of the length of the
         * file being uploaded to see if they match or not).
         */
        OFFSET_NOT_VALID(11),

        /** The requested offset is larger than the size of the file on the device. */
        OFFSET_LARGER_THAN_FILE(12),

        /** The requested checksum or hash type was not found or is not supported by this build. */
        CHECKSUM_HASH_NOT_FOUND(13),

        /** The specified mount point was not found or is not mounted. */
        MOUNT_POINT_NOT_FOUND(14),

        /** The specified mount point is that of a read-only filesystem. */
        READ_ONLY_FILESYSTEM(15),

        /** The operation cannot be performed because the file is empty with no contents. */
        FILE_EMPTY(16),

        /** Error occurred whilst attempting to close a file. */
        FILE_CLOSE_FAILED(17);

        private final int mCode;

        ReturnCode(int code) {
            mCode = code;
        }

        public int value() {
            return mCode;
        }

        public static @Nullable ReturnCode valueOf(@Nullable McuMgrResponse.GroupReturnCode returnCode) {
            if (returnCode == null || returnCode.group != GROUP_FS) {
                return null;
            }
            for (ReturnCode code : values()) {
                if (code.value() == returnCode.rc) {
                    return code;
                }
            }
            return UNKNOWN;
        }
    }

    public interface Response extends HasReturnCode {

        @Nullable
        default FsManager.ReturnCode getFsReturnCode() {
            McuMgrResponse.GroupReturnCode groupReturnCode = getGroupReturnCode();
            if (groupReturnCode == null) {
                if (getReturnCodeValue() == McuMgrErrorCode.OK.value()) {
                    return FsManager.ReturnCode.OK;
                }
                return FsManager.ReturnCode.UNKNOWN;
            }
            return FsManager.ReturnCode.valueOf(groupReturnCode);
        }
    }

    private final static int ID_FILE = 0;
    private final static int ID_STAT = 1;
    private final static int ID_HASH_CHECKSUM = 2;
    private final static int ID_SUPPORTED_HASH_CHECKSUM = 3;
    private final static int ID_FILE_CLOSE = 4;

    /**
     * Construct a McuManager instance.
     *
     * @param transporter the transporter to use to send commands.
     */
    public FsManager(@NotNull McuMgrTransport transporter) {
        super(GROUP_FS, transporter);
    }

    /**
     * Read a packet of a file with given name from the specified offset from the device
     * (asynchronous).
     * <p>
     * Use {@link #fileDownload} to download the whole file asynchronously using one command.
     *
     * @param name   the file name.
     * @param offset the offset, from which the chunk will be requested.
     * @param callback the asynchronous callback.
     * @see #fileDownload(String, DownloadCallback)
     */
    public void download(@NotNull String name, int offset,
                         @NotNull McuMgrCallback<McuMgrFsDownloadResponse> callback) {
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        payloadMap.put("off", offset);
        send(OP_READ, ID_FILE, payloadMap, SHORT_TIMEOUT, McuMgrFsDownloadResponse.class, callback);
    }

    /**
     * Read a packet of a file with given name from the specified offset from the device
     * (synchronous).
     * <p>
     * Use {@link #fileDownload} to download the whole file asynchronously using one command.
     *
     * @param name   the file name.
     * @param offset the offset, from which the chunk will be requested.
     * @return The upload response.
     * @see #fileDownload(String, DownloadCallback)
     */
    @NotNull
    public McuMgrFsDownloadResponse download(@NotNull String name, int offset) throws McuMgrException {
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        payloadMap.put("off", offset);
        return send(OP_READ, ID_FILE, payloadMap, SHORT_TIMEOUT, McuMgrFsDownloadResponse.class);
    }

    /**
     * Send a packet of given data from the specified offset to the device (asynchronous).
     * <p>
     * The chunk size is limited by the current MTU. If the current MTU set by
     * {@link #setUploadMtu(int)} is too large, the {@link InsufficientMtuException} error will be
     * thrown. Use {@link InsufficientMtuException#getMtu()} to get the current MTU and
     * pass it to {@link #setUploadMtu(int)} and try again.
     * <p>
     * Use {@link #fileUpload} to upload the whole file asynchronously using one command.
     *
     * @param name   the file name.
     * @param data   the file data.
     * @param offset the offset, from which the chunk will be sent.
     * @param callback the asynchronous callback.
     * @see #fileUpload(String, byte[], UploadCallback)
     */
    public void upload(@NotNull String name, byte @NotNull [] data, int offset,
                       @NotNull McuMgrCallback<McuMgrFsUploadResponse> callback) {
        HashMap<String, Object> payloadMap = buildUploadPayload(name, data, offset);
        send(OP_WRITE, ID_FILE, payloadMap, SHORT_TIMEOUT, McuMgrFsUploadResponse.class, callback);
    }

    /**
     * Send a packet of given data from the specified offset to the device (synchronous).
     * <p>
     * The chunk size is limited by the current MTU. If the current MTU set by
     * {@link #setUploadMtu(int)} is too large, the {@link InsufficientMtuException} error will be
     * thrown. Use {@link InsufficientMtuException#getMtu()} to get the current MTU and
     * pass it to {@link #setUploadMtu(int)} and try again.
     * <p>
     * Use {@link #fileUpload} to upload the whole file asynchronously using one command.
     *
     * @param name   the file name.
     * @param data   the file data.
     * @param offset the offset, from which the chunk will be sent.
     * @return The upload response.
     * @see #fileUpload(String, byte[], UploadCallback)
     */
    @NotNull
    public McuMgrFsUploadResponse upload(@NotNull String name, byte @NotNull [] data, int offset)
            throws McuMgrException {
        HashMap<String, Object> payloadMap = buildUploadPayload(name, data, offset);
        return send(OP_WRITE, ID_FILE, payloadMap, SHORT_TIMEOUT, McuMgrFsUploadResponse.class);
    }

    /**
     * Send a packet of given data from the specified offset to the device (synchronous).
     * <p>
     * The chunk size is limited by the current MTU. If the current MTU set by
     * {@link #setUploadMtu(int)} is too large, the {@link InsufficientMtuException} error will be
     * thrown. Use {@link InsufficientMtuException#getMtu()} to get the current MTU and
     * pass it to {@link #setUploadMtu(int)} and try again.
     * <p>
     * Use {@link #fileUpload} to upload the whole file asynchronously using one command.
     *
     * @param name   the file name.
     * @param data   the file data.
     * @param offset the offset, from which the chunk will be sent.
     * @return The upload response.
     * @see #fileUpload(String, byte[], UploadCallback)
     */
    @NotNull
    public McuMgrFsUploadResponse upload(@NotNull String name, @NotNull InputStream data, int offset, int totalBytes)
            throws McuMgrException {
        HashMap<String, Object> payloadMap = buildUploadPayload(name, data, offset, totalBytes);
        return send(OP_WRITE, ID_FILE, payloadMap, SHORT_TIMEOUT, McuMgrFsUploadResponse.class);
    }

    /*
     * Build the upload payload map.
     */
    @NotNull
    private HashMap<String, Object> buildUploadPayload(@NotNull String name, byte @NotNull [] data, int offset) {
        // Get the length of data (in bytes) to put into the upload packet. This calculated as:
        // min(MTU - packetOverhead, imageLength - uploadOffset)
        int dataLength = Math.min(mMtu - calculatePacketOverhead(name, data.length, offset),
                data.length - offset);

        // Copy the data from the image into a buffer.
        byte[] sendBuffer = new byte[dataLength];
        System.arraycopy(data, offset, sendBuffer, 0, dataLength);

        // Create the map of key-values for the McuManager payload
        HashMap<String, Object> payloadMap = new HashMap<>();
        // Put the name, data and offset
        payloadMap.put("name", name);
        payloadMap.put("data", sendBuffer);
        payloadMap.put("off", offset);
        if (offset == 0) {
            // Only send the length of the image in the first packet of the upload
            payloadMap.put("len", data.length);
        }
        return payloadMap;
    }

    /*
     * Build the upload payload map.
     */
    @NotNull
    private HashMap<String, Object> buildUploadPayload(@NotNull String name, @NotNull InputStream data, int offset, int totalBytes)
    throws McuMgrException {
        // Get the length of data (in bytes) to put into the upload packet. This calculated as:
        // min(MTU - packetOverhead, imageLength - uploadOffset)
        int dataLength = Math.min(mMtu - calculatePacketOverhead(name, totalBytes, offset),
                totalBytes - offset);

        // Copy the data from the image into a buffer.
        byte[] sendBuffer = new byte[dataLength];
        try {
            int totalRead = 0;
            while(totalRead < dataLength) {
                int read = data.read(sendBuffer, totalRead, dataLength - totalRead);
                if (read < 0) {
                    throw new McuMgrException("Data InputStream reached end of file. Expected " +
                            (totalBytes - offset - totalRead) + " more bytes."
                    );
                }
                totalRead += read;
            }
        } catch(IOException e) {
            throw new McuMgrException("Failed to read data for packet.", e);
        }

        // Create the map of key-values for the McuManager payload
        HashMap<String, Object> payloadMap = new HashMap<>();
        // Put the name, data and offset
        payloadMap.put("name", name);
        payloadMap.put("data", sendBuffer);
        payloadMap.put("off", offset);
        if (offset == 0) {
            // Only send the length of the image in the first packet of the upload
            payloadMap.put("len", totalBytes);
        }
        return payloadMap;
    }

    /**
     * Command allows to retrieve status of an existing file from specified path of a target device
     * (asynchronous).
     *
     * @param name the file name.
     * @param callback the asynchronous callback.
     */
    public void status(@NotNull String name, @NotNull McuMgrCallback<McuMgrFsStatusResponse> callback) {
        LOG.trace("Getting status of file '{}'", name);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        send(OP_READ, ID_STAT, payloadMap, SHORT_TIMEOUT, McuMgrFsStatusResponse.class, callback);
    }

    /**
     * Command allows to retrieve status of an existing file from specified path of a target device
     * (synchronous).
     *
     * @param name the file name.
     */
    public McuMgrFsStatusResponse status(@NotNull String name) throws McuMgrException {
        LOG.trace("Getting status of file '{}'", name);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        return send(OP_READ, ID_STAT, payloadMap, SHORT_TIMEOUT, McuMgrFsStatusResponse.class);
    }

    /**
     * Command allows to generate a checksum of an existing file at a specified path on a target
     * device (asynchronous).
     *
     * @param name the file name.
     * @param callback the asynchronous callback.
     */
    public void crc32(@NotNull String name,
                      @NotNull McuMgrCallback<McuMgrFsCrc32Response> callback) {
        crc32(name, 0, 0, callback);
    }

    /**
     * Command allows to generate a checksum of an existing file at a specified path on a target
     * device (asynchronous).
     *
     * @param name the file name.
     * @param offset offset to start checksum calculation at.
     * @param length maximum length of data to read from file to generate checksum with
     *               (optional, full file size if set to 0).
     * @param callback the asynchronous callback.
     */
    public void crc32(@NotNull String name,
                      int offset, int length,
                      @NotNull McuMgrCallback<McuMgrFsCrc32Response> callback) {
        if (length > 0) {
            LOG.trace("Requesting CRC32 of file '{}' from offset: {} and length: {}", name, offset, length);
        } else {
            LOG.trace("Requesting CRC32 of file '{}' from offset: {}", name, offset);
        }
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        payloadMap.put("type", "crc32");
        if (offset > 0) {
            payloadMap.put("off", offset);
        }
        if (length > 0) {
            payloadMap.put("len", length);
        }
        send(OP_READ, ID_HASH_CHECKSUM, payloadMap, MEDIUM_TIMEOUT, McuMgrFsCrc32Response.class, callback);
    }

    /**
     * Command allows to generate a checksum of an existing file at a specified path on a target
     * device (synchronous).
     *
     * @param name the file name.
     */
    public McuMgrFsStatusResponse crc32(@NotNull String name) throws McuMgrException {
        return crc32(name, 0, 0);
    }

    /**
     * Command allows to generate a checksum of an existing file at a specified path on a target
     * device (synchronous).
     *
     * @param name the file name.
     * @param offset offset to start checksum calculation at.
     * @param length maximum length of data to read from file to generate checksum with
     *               (optional, full file size if set to 0).
     */
    public McuMgrFsStatusResponse crc32(@NotNull String name, int offset, int length) throws McuMgrException {
        if (length > 0) {
            LOG.trace("Requesting CRC32 of file '{}' from offset: {} and length: {}", name, offset, length);
        } else {
            LOG.trace("Requesting CRC32 of file '{}' from offset: {}", name, offset);
        }
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        payloadMap.put("type", "crc32");
        if (offset > 0) {
            payloadMap.put("off", offset);
        }
        if (length > 0) {
            payloadMap.put("len", length);
        }
        return send(OP_READ, ID_HASH_CHECKSUM, payloadMap, SHORT_TIMEOUT, McuMgrFsStatusResponse.class);
    }

    /**
     * Command allows to generate a hash of an existing file at a specified path on a target
     * device (asynchronous).
     *
     * @param name the file name.
     * @param callback the asynchronous callback.
     */
    public void sha256(@NotNull String name,
                       @NotNull McuMgrCallback<McuMgrFsSha256Response> callback) {
        sha256(name, 0, 0, callback);
    }

    /**
     * Command allows to generate a hash of an existing file at a specified path on a target
     * device (asynchronous).
     *
     * @param name the file name.
     * @param offset offset to start hash calculation at.
     * @param length maximum length of data to read from file to generate hash with
     *               (optional, full file size if set to 0).
     * @param callback the asynchronous callback.
     */
    public void sha256(@NotNull String name,
                       int offset, int length,
                       @NotNull McuMgrCallback<McuMgrFsSha256Response> callback) {
        if (length > 0) {
            LOG.trace("Requesting SHA256 of file '{}' from offset: {} and length: {}", name, offset, length);
        } else {
            LOG.trace("Requesting SHA256 of file '{}' from offset: {}", name, offset);
        }
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        payloadMap.put("type", "sha256");
        if (offset > 0) {
            payloadMap.put("off", offset);
        }
        if (length > 0) {
            payloadMap.put("len", length);
        }
        send(OP_READ, ID_HASH_CHECKSUM, payloadMap, MEDIUM_TIMEOUT, McuMgrFsSha256Response.class, callback);
    }

    /**
     * Command allows to generate a hash of an existing file at a specified path on a target
     * device (synchronous).
     *
     * @param name the file name.
     */
    public McuMgrFsSha256Response sha256(@NotNull String name) throws McuMgrException {
        return sha256(name, 0, 0);
    }

    /**
     * Command allows to generate a hash of an existing file at a specified path on a target
     * device (synchronous).
     *
     * @param name the file name.
     * @param offset offset to start checksum calculation at.
     * @param length maximum length of data to read from file to generate hash with
     *               (optional, full file size if set to 0).
     */
    public McuMgrFsSha256Response sha256(@NotNull String name, int offset, int length) throws McuMgrException {
        if (length > 0) {
            LOG.trace("Requesting SHA256 of file '{}' from offset: {} and length: {}", name, offset, length);
        } else {
            LOG.trace("Requesting SHA256 of file '{}' from offset: {}", name, offset);
        }
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        payloadMap.put("type", "sha256");
        if (offset > 0) {
            payloadMap.put("off", offset);
        }
        if (length > 0) {
            payloadMap.put("len", length);
        }
        return send(OP_READ, ID_HASH_CHECKSUM, payloadMap, SHORT_TIMEOUT, McuMgrFsSha256Response.class);
    }

    // Supported file hash/checksum types commands are not available due to the dynamic types
    // they use.
    // Link: https://docs.zephyrproject.org/latest/services/device_mgmt/smp_groups/smp_group_8.html#supported-file-hash-checksum-types

    /**
     * Command allows closing any open file handles held by fs_mgmt upload/download requests that
     * might have stalled or be incomplete (asynchronous).
     *
     * @param callback the asynchronous callback.
     */
    public void closeAll(@NotNull McuMgrCallback<McuMgrResponse> callback) {
        LOG.trace("Closing all open files");
        send(OP_WRITE, ID_FILE_CLOSE, null, SHORT_TIMEOUT, McuMgrResponse.class, callback);
    }

    /**
     * Command allows closing any open file handles held by fs_mgmt upload/download requests that
     * might have stalled or be incomplete (synchronous).
     */
    public McuMgrResponse closeAll() throws McuMgrException {
        LOG.trace("Closing all open files");
        return send(OP_WRITE, ID_FILE_CLOSE, null, SHORT_TIMEOUT, McuMgrResponse.class);
    }

    //******************************************************************
    // File Upload
    //******************************************************************

    /**
     * Start image upload.
     * <p>
     * Multiple calls will queue multiple uploads, executed sequentially. This includes file
     * downloads executed from {@link #fileDownload}.
     * <p>
     * The upload may be controlled using the {@link TransferController} returned by this method.
     *
     * @param data The file data to upload.
     * @param callback  Receives callbacks from the upload.
     * @return The object used to control this upload.
     * @see TransferController
     */
    @NotNull
    public TransferController fileUpload(@NotNull String name, byte @NotNull [] data, @NotNull UploadCallback callback) {
        LOG.trace("Initiating file upload '{}' ({} bytes)", name, data.length);
        return startUpload(new FileUpload(name, data, callback));
    }

    /**
     * File Upload Implementation.
     */
    public class FileUpload extends Upload {

        @NotNull
        private final String mName;

        protected FileUpload(@NotNull String name, byte @NotNull [] data, @NotNull UploadCallback callback) {
            super(data, callback);
            mName = name;
        }

        @Override
        protected UploadResponse write(byte @NotNull [] data, int offset) throws McuMgrException {
            return upload(mName, data, offset);
        }
    }

    /**
     * Start image upload.
     * <p>
     * Multiple calls will queue multiple uploads, executed sequentially. This includes file
     * downloads executed from {@link #fileDownload}.
     * <p>
     * The upload may be controlled using the {@link TransferController} returned by this method.
     *
     * @param data The file data to upload.
     * @param callback  Receives callbacks from the upload.
     * @return The object used to control this upload.
     * @see TransferController
     */
    @NotNull
    public TransferController fileUpload(@NotNull String name, @NotNull InputStream data, int totalBytes, @NotNull UploadCallback callback) {
        LOG.trace("Initiating file upload '{}' ({} bytes)", name, totalBytes);
        return startUpload(new FileStreamUpload(name, data, totalBytes, callback));
    }

    /**
     * File Upload Implementation.
     */
    public class FileStreamUpload extends StreamUpload {

        @NotNull
        private final String mName;

        protected FileStreamUpload(@NotNull String name, @NotNull InputStream data, int totalBytes, @NotNull UploadCallback callback) {
            super(data, totalBytes, callback);
            mName = name;
        }

        @Override
        protected UploadResponse write(@NotNull InputStream data, int offset, int totalBytes) throws McuMgrException {
            return upload(mName, data, offset, totalBytes);
        }
    }

    //******************************************************************
    // File Download
    //******************************************************************

    /**
     * Start image download.
     * <p>
     * Multiple calls will queue multiple downloads, executed sequentially. This includes file
     * downloads executed from {@link #fileDownload}.
     * <p>
     * The download may be controlled using the {@link TransferController} returned by this method.
     *
     * @param callback Receives callbacks from the download.
     * @return The object used to control this download.
     * @see TransferController
     */
    @NotNull
    public TransferController fileDownload(@NotNull String name, @NotNull DownloadCallback callback) {
        LOG.trace("Initiating file download '{}'", name);
        return startDownload(new FileDownload(name, callback));
    }

    /**
     * File Download Implementation
     */
    public class FileDownload extends Download {

        @NotNull
        private final String mName;

        protected FileDownload(@NotNull String name, @NotNull DownloadCallback callback) {
            super(callback);
            mName = name;
        }

        @Override
        protected DownloadResponse read(int offset) throws McuMgrException {
            return download(mName, offset);
        }
    }

    /**
     * Start image download.
     * <p>
     * Multiple calls will queue multiple downloads, executed sequentially. This includes file
     * downloads executed from {@link #fileDownload}.
     * <p>
     * The download may be controlled using the {@link TransferController} returned by this method.
     *
     * @param callback Receives callbacks from the download.
     * @return The object used to control this download.
     * @see TransferController
     */
    @NotNull
    public TransferController fileDownload(
            @NotNull String name,
            @NotNull OutputStream dataOutput,
            @NotNull StreamDownloadCallback callback
    ) {
        LOG.trace("Initiating file download '{}'", name);
        return startDownload(new FileStreamDownload(name, dataOutput, callback));
    }

    public class FileStreamDownload extends StreamDownload {
        @NotNull
        private final String mName;

        protected FileStreamDownload(
                @NotNull String name,
                @NotNull OutputStream dataOutput,
                @NotNull StreamDownloadCallback callback
        ) {
            super(dataOutput, callback);
            mName = name;
        }

        @Override
        protected DownloadResponse read(int offset) throws McuMgrException {
            return download(mName, offset);
        }
    }

    //******************************************************************
    // File Upload / Download (OLD, DEPRECATED)
    //******************************************************************

    // Upload / Download states
    public final static int STATE_NONE = 0;
    public final static int STATE_UPLOADING = 1;
    public final static int STATE_DOWNLOADING = 2;
    public final static int STATE_PAUSED = 3;

    // Upload / Download variables
    private int mTransferState = STATE_NONE;
    private String mFileName = null;
    private int mOffset = 0;
    private byte[] mFileData;
    private FileUploadCallback mUploadCallback;
    private FileDownloadCallback mDownloadCallback;

    /**
     * Begin a file download.
     * <p>
     * Only one download can occur per FsManager.
     *
     * @param name     the file name.
     * @param callback the file download callback.
     * @deprecated Use {@link #fileDownload(String, DownloadCallback)} instead.
     */
    @Deprecated
    public synchronized void download(@NotNull String name, @NotNull FileDownloadCallback callback) {
        if (mTransferState == STATE_NONE) {
            mTransferState = STATE_DOWNLOADING;
        } else {
            LOG.warn("FsManager is not ready");
            return;
        }

        mFileName = name;
        mDownloadCallback = callback;
        requestNext(0);
    }

    /**
     * Begin a file upload.
     * <p>
     * Only one upload can occur per FsManager.
     *
     * @param name     the file name.
     * @param data     the file data to upload.
     * @param callback the file upload callback.
     * @deprecated Use {@link #fileUpload(String, byte[], UploadCallback)} instead.
     */
    @Deprecated
    public synchronized void upload(@NotNull String name, byte @NotNull [] data,
                                    @NotNull FileUploadCallback callback) {
        if (mTransferState == STATE_NONE) {
            mTransferState = STATE_UPLOADING;
        } else {
            LOG.warn("FsManager is not ready");
            return;
        }

        mFileName = name;
        mFileData = data;
        mUploadCallback = callback;
        sendNext(0);
    }

    /**
     * Get the current upload state ({@link #STATE_NONE},
     * {@link #STATE_UPLOADING}, {@link #STATE_DOWNLOADING}, {@link #STATE_PAUSED}).
     *
     * @return The current upload state.
     * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
     */
    @Deprecated
    public synchronized int getState() {
        return mTransferState;
    }

    /**
     * Cancel an undergoing file transfer. Does nothing if no transfer is in progress.
     * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
     */
    @Deprecated
    public synchronized void cancelTransfer() {
        if (mTransferState == STATE_NONE) {
            LOG.debug("File transfer is not in progress");
        } else if (mTransferState == STATE_PAUSED) {
            LOG.info("Upload canceled");
            resetTransfer();
            if (mUploadCallback != null) {
                mUploadCallback.onUploadCanceled();
                mUploadCallback = null;
            }
            if (mDownloadCallback != null) {
                mDownloadCallback.onDownloadCanceled();
                mDownloadCallback = null;
            }
        } else {
            // Transfer will be cancelled
            resetTransfer();
        }
    }

    /**
     * Pause an in progress transfer.
     * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
     */
    @Deprecated
    public synchronized void pauseTransfer() {
        if (mTransferState == STATE_NONE) {
            LOG.debug("File transfer is not in progress.");
        } else {
            LOG.info("Upload paused");
            mTransferState = STATE_PAUSED;
        }
    }

    /**
     * Continue a paused file transfer.
     * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
     */
    @Deprecated
    public synchronized void continueTransfer() {
        if (mTransferState == STATE_PAUSED) {
            LOG.info("Continuing transfer...");
            if (mDownloadCallback != null) {
                mTransferState = STATE_DOWNLOADING;
                requestNext(mOffset);
            } else {
                mTransferState = STATE_UPLOADING;
                sendNext(mOffset);
            }
        } else {
            LOG.debug("Transfer is not paused.");
        }
    }

    private synchronized void fail(McuMgrException error) {
        if (mUploadCallback != null) {
            mUploadCallback.onUploadFailed(error);
        }else if (mDownloadCallback != null) {
            mDownloadCallback.onDownloadFailed(error);
        }
        resetTransfer();
        mUploadCallback = null;
        mDownloadCallback = null;
    }

    private synchronized void restartTransfer() {
        mTransferState = STATE_NONE;
        if (mUploadCallback != null) {
            upload(mFileName, mFileData, mUploadCallback);
        } else if (mDownloadCallback != null) {
            download(mFileName, mDownloadCallback);
        }
    }

    private synchronized void resetTransfer() {
        mTransferState = STATE_NONE;
        mFileName = null;
        mOffset = 0;
        mFileData = null;
    }

    /**
     * Send a packet of upload data from the specified offset.
     *
     * @param offset the image data offset to send data from.
     */
    private synchronized void sendNext(int offset) {
        // Check that the state is STATE_UPLOADING
        if (mTransferState != STATE_UPLOADING) {
            LOG.warn("Fs Manager is not in the UPLOADING state.");
            return;
        }
        upload(mFileName, mFileData, offset, mUploadCallbackImpl);
    }

    /**
     * Send a packet of upload data from the specified offset.
     *
     * @param offset the image data offset to send data from.
     */
    private synchronized void requestNext(int offset) {
        // Check that the state is STATE_UPLOADING
        if (mTransferState != STATE_DOWNLOADING) {
            LOG.warn("Fs Manager is not in the DOWNLOADING state.");
            return;
        }
        download(mFileName, offset, mDownloadCallbackImpl);
    }

    /**
     * The upload callback which is called after a {@link #sendNext(int)}'s response has been
     * received or an error has occurred. On success, this callback parses the response, calls the
     * upload progress callback and sends the next packet of image data from the offset specified
     * in the response. On error, the upload is failed unless the error specifies that the packet
     * sent to the transporter was too large to send ({@link InsufficientMtuException}).
     * In this case, the MTU is set to the MTU in the exception and the upload is restarted.
     */
    private final McuMgrCallback<McuMgrFsUploadResponse> mUploadCallbackImpl =
            new McuMgrCallback<>() {
                @Override
                public void onResponse(@NotNull McuMgrFsUploadResponse response) {
                    // Check for a McuManager error
                    if (response.rc != 0) {
                        LOG.error("Upload failed: {}", response.rc);
                        fail(new McuMgrErrorException(McuMgrErrorCode.valueOf(response.rc)));
                        return;
                    }

                    // Check if upload hasn't been cancelled.
                    if (mTransferState == STATE_NONE) {
                        LOG.warn("Upload canceled");
                        resetTransfer();
                        mUploadCallback.onUploadCanceled();
                        mUploadCallback = null;
                        return;
                    }

                    // Get the next offset to send image data from.
                    mOffset = response.off;

                    // Call the progress callback.
                    mUploadCallback.onProgressChanged(mOffset, mFileData.length,
                            System.currentTimeMillis());

                    // Check if the upload has finished.
                    if (mOffset >= mFileData.length) {
                        LOG.info("Upload finished");
                        resetTransfer();
                        mUploadCallback.onUploadFinished();
                        mUploadCallback = null;
                        return;
                    }

                    // Send the next packet of upload data from the offset provided in the response.
                    sendNext(mOffset);
                }

                @Override
                public void onError(@NotNull McuMgrException error) {
                    // Check if the exception is due to an insufficient MTU.
                    if (error instanceof InsufficientMtuException) {
                        final InsufficientMtuException mtuErr = (InsufficientMtuException) error;

                        // Set the MTU to the value specified in the error response.
                        int mtu = mtuErr.getMtu();
                        if (mMtu == mtu)
                            mtu -= 1;
                        boolean isMtuSet = setUploadMtu(mtu);

                        if (isMtuSet) {
                            // If the MTU has been set successfully, restart the upload.
                            restartTransfer();
                            return;
                        }
                    }
                    // If the exception is not due to insufficient MTU fail the upload.
                    fail(error);
                }
            };

    /**
     * The download callback which is called after a {@link #requestNext(int)}'s response has been
     * received or an error has occurred. On success, this callback parses the response, calls the
     * download progress callback and requests the next packet of file data from the offset specified
     * in the response. On error, the transfer is failed.
     */
    private final McuMgrCallback<McuMgrFsDownloadResponse> mDownloadCallbackImpl =
            new McuMgrCallback<>() {
                @Override
                public void onResponse(@NotNull McuMgrFsDownloadResponse response) {
                    // Check for a McuManager error.
                    if (response.rc != 0) {
                        LOG.error("Download failed: {}", response.rc);
                        fail(new McuMgrErrorException(McuMgrErrorCode.valueOf(response.rc)));
                        return;
                    }

                    // Check if download hasn't been cancelled.
                    if (mTransferState == STATE_NONE) {
                        LOG.warn("Download canceled");
                        resetTransfer();
                        mDownloadCallback.onDownloadCanceled();
                        mDownloadCallback = null;
                        return;
                    }

                    // Get the next offset to request data from.
                    mOffset = response.off;

                    // The first packet contains the file length.
                    if (mOffset == 0) {
                        mFileData = new byte[response.len];
                    }

                    // Copy received data to the buffer.
                    System.arraycopy(response.data, 0, mFileData, mOffset, response.data.length);
                    mOffset += response.data.length;

                    // Call the progress callback.
                    mDownloadCallback.onProgressChanged(mOffset, mFileData.length,
                            System.currentTimeMillis());

                    // Check if the download has finished.
                    if (mOffset >= mFileData.length) {
                        LOG.info("Download finished");
                        byte[] data = mFileData;
                        String fileName = mFileName;
                        resetTransfer();
                        mDownloadCallback.onDownloadFinished(fileName, data);
                        mDownloadCallback = null;
                        return;
                    }

                    // Send the next packet of upload data from the offset provided in the response.
                    requestNext(mOffset);
                }

                @Override
                public void onError(@NotNull McuMgrException error) {
                    // Check if the exception is due to an insufficient MTU.
                    if (error instanceof InsufficientMtuException) {
                        final InsufficientMtuException mtuErr = (InsufficientMtuException) error;

                        // Set the MTU to the value specified in the error response.
                        int mtu = mtuErr.getMtu();
                        if (mMtu == mtu)
                            mtu -= 1;
                        boolean isMtuSet = setUploadMtu(mtu);

                        if (isMtuSet) {
                            // If the MTU has been set successfully, restart the upload.
                            restartTransfer();
                            return;
                        }
                    }
                    // If the exception is not due to insufficient MTU fail the upload.
                    fail(error);
                }
            };

    private int calculatePacketOverhead(@NotNull String name, int totalBytes, int offset) {
        try {
            if (getScheme().isCoap()) {
                HashMap<String, Object> overheadTestMap = new HashMap<>();
                overheadTestMap.put("name", name);
                overheadTestMap.put("data", new byte[0]);
                overheadTestMap.put("off", offset);
                if (offset == 0) {
                    overheadTestMap.put("len", totalBytes);
                }
                byte[] header = {0, 0, 0, 0, 0, 0, 0, 0};
                overheadTestMap.put("_h", header);
                byte[] cborData = CBOR.toBytes(overheadTestMap);
                // 20 byte estimate of CoAP Header; 5 bytes for good measure
                return cborData.length + 20 + 5;
            } else {
                // The code below removes the need of calling an expensive method CBOR.toBytes(..)
                // by calculating the overhead manually. Mind, that the data itself are not added.
                int size = 2; // map: 0xBF at the beginning and 0xFF at the end
                size += 5 + CBOR.stringLength(name); // "name": 0x646E616D65 + name length + name
                size += 5 + 3; // "data": 0x6464617461 + 3 for encoding length (as 16-bin positive int, worse case scenario) + NO DATA
                size += 4 + CBOR.uintLength(offset); // "off": 0x636F6666 + offset
                if (offset == 0) {
                    // len parameter contains the full length of the file, which at this moment
                    // is unknown. This method is called only for a chunk.
                    size += 4 + 5; // "len": 0x636C656E + len (as 32-bit positive integer, worse case scenario)
                }
                return size + 8; // 8 additional bytes for the SMP header
            }
        } catch (IOException e) {
            LOG.error("Error while calculating packet overhead", e);
        }
        return -1;
    }

    //******************************************************************
    // File Upload Callback
    //******************************************************************

    /**
     * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
     */
    @Deprecated
    public interface FileUploadCallback {

        /**
         * Called when a response has been received successfully.
         *
         * @param bytesSent the number of bytes sent so far.
         * @param imageSize the size of the image in bytes.
         * @param timestamp the timestamp of when the response was received.
         */
        void onProgressChanged(int bytesSent, int imageSize, long timestamp);

        /**
         * Called when the upload has failed.
         *
         * @param error the error. See the cause for more info.
         */
        void onUploadFailed(McuMgrException error);

        /**
         * Called when the upload has been canceled.
         */
        void onUploadCanceled();

        /**
         * Called when the upload has finished successfully.
         */
        void onUploadFinished();
    }

    //******************************************************************
    // File Download Callback
    //******************************************************************

    /**
     * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
     */
    @Deprecated
    public interface FileDownloadCallback {

        /**
         * Called when a response has been received successfully.
         *
         * @param bytesDownloaded the number of bytes downloaded so far.
         * @param imageSize       the size of the image in bytes.
         * @param timestamp       the timestamp of when the response was received.
         * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
         */
        @Deprecated
        void onProgressChanged(int bytesDownloaded, int imageSize, long timestamp);

        /**
         * Called when the download has failed.
         *
         * @param error the error. See the cause for more info.
         * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
         */
        @Deprecated
        void onDownloadFailed(@NotNull McuMgrException error);

        /**
         * Called when the download has been canceled.
         * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
         */
        @Deprecated
        void onDownloadCanceled();

        /**
         * Called when the download has finished successfully.
         *
         * @param name file name.
         * @param data file data.
         * @deprecated Old implementation. See {@link #fileUpload} and {@link #fileDownload}
         */
        @Deprecated
        void onDownloadFinished(@NotNull String name, byte @NotNull [] data);
    }
}

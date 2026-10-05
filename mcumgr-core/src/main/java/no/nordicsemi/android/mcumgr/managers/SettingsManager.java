/*
 * Copyright (c) 2017-2018 Runtime Inc.
 * Copyright (c) Intellinium SAS, 2014-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.managers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

import no.nordicsemi.android.mcumgr.McuManager;
import no.nordicsemi.android.mcumgr.McuMgrCallback;
import no.nordicsemi.android.mcumgr.McuMgrErrorCode;
import no.nordicsemi.android.mcumgr.McuMgrGroupReturnCode;
import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.response.HasReturnCode;
import no.nordicsemi.android.mcumgr.response.McuMgrResponse;
import no.nordicsemi.android.mcumgr.response.settings.McuMgrSettingsReadResponse;

/**
 * Config command group manager.
 */
@SuppressWarnings("unused")
public class SettingsManager extends McuManager {
    public enum ReturnCode implements McuMgrGroupReturnCode {
        /** No error, this is implied if there is no ret value in the response. */
        OK(0),

        /** Unknown error occurred. */
        UNKNOWN(1),

        /** The provided key name is too long to be used. */
        KEY_TOO_LONG(2),

        /** The provided key name does not exist. */
        KEY_NOT_FOUND(3),

        /** The provided key name does not support being read. */
        READ_NOT_SUPPORTED(4),

        /** The provided root key name does not exist. */
        ROOT_KEY_NOT_FOUND(5),

        /** The provided key name does not support being written. */
        WRITE_NOT_SUPPORTED(6),

        /** The provided key name does not support being deleted. */
        DELETE_NOT_SUPPORTED(7),

        /** The provided key name does not support being saved. */
        SAVE_NOT_SUPPORTED(8),

        /**
         * The provided key cannot be saved before the value is longer than the size of the
         * largest value that can safely be read
         * (CONFIG_SETTINGS_SAVE_SINGLE_SUBTREE_WITHOUT_MODIFICATION_VALUE_SIZE).
         */
        SAVE_FAILED_VALUE_TOO_LONG_TO_READ(9);

        private final int mCode;

        ReturnCode(int code) {
            mCode = code;
        }

        public int value() {
            return mCode;
        }

        public static @Nullable SettingsManager.ReturnCode valueOf(@Nullable McuMgrResponse.GroupReturnCode returnCode) {
            if (returnCode == null || returnCode.group != GROUP_BASIC) {
                return null;
            }
            for (SettingsManager.ReturnCode code : values()) {
                if (code.value() == returnCode.rc) {
                    return code;
                }
            }
            return UNKNOWN;
        }
    }

    public interface Response extends HasReturnCode {

        @Nullable
        default SettingsManager.ReturnCode getSettingsReturnCode() {
            McuMgrResponse.GroupReturnCode groupReturnCode = getGroupReturnCode();
            if (groupReturnCode == null) {
                if (getReturnCodeValue() == McuMgrErrorCode.OK.value()) {
                    return SettingsManager.ReturnCode.OK;
                }
                return SettingsManager.ReturnCode.UNKNOWN;
            }
            return SettingsManager.ReturnCode.valueOf(groupReturnCode);
        }
    }
    private final static int ID_READ_WRITE = 0;
    private final static int ID_DELETE = 1;
    private final static int ID_COMMIT = 2;
    private final static int ID_LOAD_SAVE = 3;

    /**
     * Construct an config manager.
     *
     * @param transport the transport to use to send commands.
     */
    public SettingsManager(@NotNull McuMgrTransport transport) {
        super(GROUP_SETTINGS, transport);
    }

    /**
     * Read a config variable (asynchronous).
     *
     * @param name     the name of the config variable.
     * @param callback the asynchronous callback.
     */
    public void read(@NotNull String name,
                     @NotNull McuMgrCallback<McuMgrSettingsReadResponse> callback) {
        read(name, null, callback);
    }

    /**
     * Read a config variable (asynchronous).
     *
     * @param name     the name of the config variable.
     * @param maxSize  optional maximum size of data to return.
     * @param callback the asynchronous callback.
     */
    public void read(@NotNull String name, @Nullable Integer maxSize,
                     @NotNull McuMgrCallback<McuMgrSettingsReadResponse> callback) {
        LOG.trace("Reading '{}'", name);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        if (maxSize != null) {
            payloadMap.put("max_size", maxSize);
        }
        send(OP_READ, ID_READ_WRITE, payloadMap, SHORT_TIMEOUT, McuMgrSettingsReadResponse.class, callback);
    }

    /**
     * Read a config variable (synchronous).
     *
     * @param name the name of the config variable.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrSettingsReadResponse read(@NotNull String name) throws McuMgrException {
        return read(name, (Integer) null);
    }

    /**
     * Read a config variable (synchronous).
     *
     * @param name the name of the config variable.
     * @param maxSize  optional maximum size of data to return.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrSettingsReadResponse read(@NotNull String name, @Nullable Integer maxSize) throws McuMgrException {
        LOG.trace("Reading '{}'", name);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        if (maxSize != null) {
            payloadMap.put("max_size", maxSize);
        }
        return send(OP_READ, ID_READ_WRITE, payloadMap, SHORT_TIMEOUT, McuMgrSettingsReadResponse.class);
    }

    /**
     * Write a value to a config variable (asynchronous).
     *
     * @param name     the name of the config variable.
     * @param value    the value to write.
     * @param callback the asynchronous callback.
     */
    public void write(@NotNull String name, byte @NotNull [] value,
                      @NotNull McuMgrCallback<McuMgrResponse> callback) {
        LOG.trace("Setting '{}' ({} bytes)", name, value.length);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        payloadMap.put("val", value);
        send(OP_WRITE, ID_READ_WRITE, payloadMap, SHORT_TIMEOUT, McuMgrResponse.class, callback);
    }

    /**
     * Write a value to a config variable (synchronous).
     *
     * @param name  the name of the config variable.
     * @param value the value to write.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrResponse write(@Nullable String name, byte @NotNull [] value) throws McuMgrException {
        LOG.trace("Setting '{}' ({} bytes)", name, value.length);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        payloadMap.put("val", value);
        return send(OP_WRITE, ID_READ_WRITE, payloadMap, SHORT_TIMEOUT, McuMgrResponse.class);
    }

    /**
     * Delete setting command allows deleting a setting on a device.
     *
     * @param name     the name of the config variable.
     * @param callback the asynchronous callback.
     */
    public void delete(@NotNull String name,
                       @NotNull McuMgrCallback<McuMgrResponse> callback) {
        LOG.trace("Deleting '{}'", name);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        send(OP_WRITE, ID_DELETE, payloadMap, SHORT_TIMEOUT, McuMgrResponse.class, callback);
    }

    /**
     * Delete setting command allows deleting a setting on a device (synchronous).
     *
     * @param name the name of the config variable.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrResponse delete(@NotNull String name) throws McuMgrException {
        LOG.trace("Deleting '{}'", name);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("name", name);
        return send(OP_WRITE, ID_DELETE, payloadMap, SHORT_TIMEOUT, McuMgrResponse.class);
    }

    /**
     * Commit settings command allows committing all settings that have been set but not yet applied on a device.
     *
     * @param callback the asynchronous callback.
     */
    public void commit(@NotNull McuMgrCallback<McuMgrResponse> callback) {
        LOG.trace("Committing settings");
        send(OP_WRITE, ID_COMMIT, null, SHORT_TIMEOUT, McuMgrResponse.class, callback);
    }

    /**
     * Commit settings command allows committing all settings that have been set but not yet applied on a device (synchronous).
     *
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrResponse commit() throws McuMgrException {
        LOG.trace("Committing settings");
        return send(OP_WRITE, ID_COMMIT, null, SHORT_TIMEOUT, McuMgrResponse.class);
    }

    /**
     * Load settings command allows loading all serialized items from persistent storage on a device.
     *
     * @param callback the asynchronous callback.
     */
    public void load(@NotNull McuMgrCallback<McuMgrResponse> callback) {
        LOG.trace("Loading settings");
        send(OP_READ, ID_LOAD_SAVE, null, SHORT_TIMEOUT, McuMgrResponse.class, callback);
    }

    /**
     * Load settings command allows loading all serialized items from persistent storage on a device (synchronous).
     *
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrResponse load() throws McuMgrException {
        LOG.trace("Loading settings");
        return send(OP_READ, ID_LOAD_SAVE, null, SHORT_TIMEOUT, McuMgrResponse.class);
    }

    /**
     * Save settings command allows saving all serialized items to persistent storage on a device.
     *
     * @param callback the asynchronous callback.
     */
    public void save(@NotNull McuMgrCallback<McuMgrResponse> callback) {
        LOG.trace("Saving settings");
        send(OP_WRITE, ID_LOAD_SAVE, null, SHORT_TIMEOUT, McuMgrResponse.class, callback);
    }

    /**
     * Save settings command allows saving all serialized items to persistent storage on a device (synchronous).
     *
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrResponse save() throws McuMgrException {
        LOG.trace("Saving settings");
        return send(OP_WRITE, ID_LOAD_SAVE, null, SHORT_TIMEOUT, McuMgrResponse.class);
    }
}

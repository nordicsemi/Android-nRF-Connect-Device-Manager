/*
 * Copyright (c) 2017-2018 Runtime Inc.
 * Copyright (c) Intellinium SAS, 2014-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.log.Category;
import no.nordicsemi.android.mcumgr.log.McuMgrLogger;
import no.nordicsemi.android.mcumgr.response.McuMgrResponse;
import no.nordicsemi.android.mcumgr.util.CBOR;
import no.nordicsemi.kotlin.log.Log;

/**
 * The base class for managers handling MCU Manager groups.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public abstract class McuManager {

    // Transport constants

    /** Maximum length of a single SMP packet. */
    private final static int DEFAULT_MTU = 0xFFFF + 8; // Header size + max packet size.

    protected final static long DEFAULT_TIMEOUT = 40_000; // ms
    protected final static long SHORT_TIMEOUT = 2_500; // ms
    protected final static long MEDIUM_TIMEOUT = 5_000; // ms

    // Date format
    private final static String MCUMGR_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSZZZZZ";

    // CoAP constants
    private final static String COAP_URI = "/omgr";
    private final static String HEADER_KEY = "_h";

    // Mcu Manager operation codes
    protected final static int OP_READ = 0;
    protected final static int OP_READ_RSP = 1;
    protected final static int OP_WRITE = 2;
    protected final static int OP_WRITE_RSP = 3;

    private final static int SMP_VERSION = 0b01;

    // Mcu Manager groups
    protected final static int GROUP_DEFAULT = 0;
    protected final static int GROUP_IMAGE = 1;
    protected final static int GROUP_STATS = 2;
    protected final static int GROUP_SETTINGS = 3;
    protected final static int GROUP_LOGS = 4;
    protected final static int GROUP_CRASH = 5;
    protected final static int GROUP_SPLIT = 6;
    protected final static int GROUP_RUN = 7;
    protected final static int GROUP_FS = 8;
    protected final static int GROUP_SHELL = 9;
    // https://github.com/nrfconnect/sdk-zephyr/blob/master/include/mgmt/mcumgr/zephyr_groups.h
    protected final static int GROUP_BASIC = 63;

    // SUIT group was used for SUIT bootloaders on nRF54H before IronSide.
    // This group number may be reused, as it belongs to Per User group range.
    protected final static int GROUP_SUIT = 66;

    // User defined groups should start from GROUP_PERUSER.
    protected final static int GROUP_PERUSER = 64;

    /**
     * This manager's group ID.
     */
    private final int mGroupId;

    /**
     * Handles sending the McuManager command data over the transport specified by its scheme.
     */
    @NotNull
    private final McuMgrTransport mTransporter;

    /**
     * The MTU used to send data to the device.
     * <p>
     * Initially, the MTU is set to the highest possible value for the SMP protocol.
     * It can be lowered with {@link #setUploadMtu(int)} by the transport if required.
     */
    protected int mMtu = DEFAULT_MTU;

    /**
     * The logger of this manager, reporting under {@link Category#COMMAND}.
     * <p>
     * Nothing is logged until a sink is assigned using {@link #setLogger(Log.Sink)}.
     */
    @NotNull
    protected final McuMgrLogger LOG = new McuMgrLogger(Category.COMMAND);

    /**
     * Construct a McuManager instance.
     *
     * @param groupId     the group ID of this Mcu Manager instance.
     * @param transporter the transporter to use to send commands.
     */
    protected McuManager(int groupId, @NotNull McuMgrTransport transporter) {
        mGroupId = groupId;
        mTransporter = transporter;
    }

    /**
     * Sets the sink receiving the log entries emitted by this manager.
     * <p>
     * The entries are reported under {@link Category#COMMAND}. Set to null (the default) to
     * stop logging.
     *
     * @param logger the sink to receive the log entries, or null.
     * @since 4.0
     */
    public void setLogger(@Nullable Log.Sink<? super Category> logger) {
        LOG.setSink(logger);
    }

    /**
     * Returns the sink assigned to this manager, so that transfers started by it report to
     * the same place. The transfer reports under its own {@link Category#TRANSFER}.
     * <p>
     * This is an implementation detail of the library, not intended to be used by applications.
     * Use {@link #setLogger(Log.Sink)} to receive the log entries.
     *
     * @since 4.0
     */
    @ApiStatus.Internal
    @Nullable
    public Log.Sink<Category> getLogSink() {
        return LOG.getSink();
    }

    /**
     * Get the group ID for this manager.
     *
     * @return The group ID for this manager.
     */
    public int getGroupId() {
        return mGroupId;
    }

    /**
     * Get the transporter's scheme.
     *
     * @return The transporter's scheme.
     */
    @NotNull
    public McuMgrScheme getScheme() {
        return mTransporter.getScheme();
    }

    /**
     * Get the transporter.
     *
     * @return Transporter for this new manager instance.
     */
    @NotNull
    public McuMgrTransport getTransporter() {
        return mTransporter;
    }

    /**
     * Sets the upload MTU.
     * This is transport independent value, so should be equal to the maximum length of a packet
     * with selected transport.
     *
     * @param mtu the MTU to use for packets.
     * @return True if the upload has been set, false otherwise.
     */
    public synchronized boolean setUploadMtu(int mtu) {
        if (mtu < 20) {
            LOG.error("MTU is too small! Must be >= 20.");
            return false;
        } else if (mtu > DEFAULT_MTU) {
            LOG.error("MTU is too large! Must be <= 65543.");
            return false;
        } else {
            mMtu = mtu;
            return true;
        }
    }

    /**
     * Returns the upload MTU.
     *
     * @return The MTU.
     */
    public synchronized int getMtu() {
        return mMtu;
    }

    /**
     * Send an asynchronous Mcu Manager command.
     * <p>
     * Additionally builds the Mcu Manager header and formats the packet based on scheme before
     * sending it to the transporter.
     *
     * @param op         the operation ({@link McuManager#OP_READ}, {@link McuManager#OP_WRITE}).
     * @param commandId  the ID of the command.
     * @param payloadMap the map of values to send along. This argument can be null if the header is
     *                   the only required field.
     * @param respType   the response type.
     * @param callback   the response callback.
     * @param <T>        the response type.
     * @deprecated Use {@link #send(int, int, Map, long, Class, McuMgrCallback)} instead.
     */
    @Deprecated
    public <T extends McuMgrResponse> void send(int op, int commandId,
                                                @Nullable Map<String, Object> payloadMap,
                                                @NotNull Class<T> respType,
                                                @NotNull McuMgrCallback<T> callback) {
        send(op, 0, 0, commandId, payloadMap, DEFAULT_TIMEOUT, respType, callback);
    }

    /**
     * Send synchronous Mcu Manager command.
     * <p>
     * Additionally builds the Mcu Manager header and formats the packet based on scheme before
     * sending it to the transporter.
     *
     * @param op         the operation ({@link McuManager#OP_READ}, {@link McuManager#OP_WRITE}).
     * @param commandId  the ID of the command.
     * @param payloadMap the map of values to send along. This argument can be null if the header is
     *                   the only required field.
     * @param respType   the response type.
     * @param <T>        the response type.
     * @return The McuMgrResponse or null if an error occurred.
     * @throws McuMgrException on transport error. See exception cause for more info.
     * @deprecated Use {@link #send(int, int, Map, long, Class)} instead.
     */
    @Deprecated
    @NotNull
    public <T extends McuMgrResponse> T send(int op, int commandId,
                                             @Nullable Map<String, Object> payloadMap,
                                             @NotNull Class<T> respType)
            throws McuMgrException {
        return send(op, 0, 0, commandId, payloadMap, DEFAULT_TIMEOUT, respType);
    }

    /**
     * Send an asynchronous Mcu Manager command.
     * <p>
     * Additionally builds the Mcu Manager header and formats the packet based on scheme before
     * sending it to the transporter.
     *
     * @param op          the operation ({@link McuManager#OP_READ}, {@link McuManager#OP_WRITE})
     * @param flags       additional flags.
     * @param sequenceNum sequence number.
     * @param commandId   ID of the command in the group.
     * @param payloadMap  map of command's key-value pairs to construct a CBOR payload.
     * @param respType    the response type.
     * @param callback    asynchronous callback.
     * @param <T>         the response type.
     * @deprecated Use {@link #send(int, int, int, int, Map, long, Class, McuMgrCallback)} instead.
     */
    @Deprecated
    public <T extends McuMgrResponse> void send(int op, int flags, int sequenceNum, int commandId,
                                                @Nullable Map<String, Object> payloadMap,
                                                @NotNull Class<T> respType,
                                                @NotNull McuMgrCallback<T> callback) {
        send(op, flags, sequenceNum, commandId, payloadMap, DEFAULT_TIMEOUT, respType, callback);
    }

    /**
     * Send synchronous Mcu Manager command.
     * <p>
     * Additionally builds the Mcu Manager header and formats the packet based on scheme before
     * sending it to the transporter.
     *
     * @param op          the operation ({@link McuManager#OP_READ},
     *                    {@link McuManager#OP_WRITE}).
     * @param flags       additional flags.
     * @param sequenceNum sequence number.
     * @param commandId   ID of the command in the group.
     * @param respType    the response type.
     * @param payloadMap  map of payload key-value pairs.
     * @param <T>         the response type.
     * @return The Mcu Manager response.
     * @throws McuMgrException on transport error. See exception cause for more info.
     * @deprecated Use {@link #send(int, int, int, int, Map, long, Class)} instead.
     */
    @Deprecated
    @NotNull
    public <T extends McuMgrResponse> T send(int op, int flags, int sequenceNum,
                                             int commandId, @NotNull Class<T> respType,
                                             @Nullable Map<String, Object> payloadMap)
            throws McuMgrException {
        return send(op, flags, sequenceNum, commandId, payloadMap, DEFAULT_TIMEOUT, respType);
    }

    /**
     * Send an asynchronous Mcu Manager command.
     * <p>
     * Additionally builds the Mcu Manager header and formats the packet based on scheme before
     * sending it to the transporter.
     *
     * @param op         the operation ({@link McuManager#OP_READ}, {@link McuManager#OP_WRITE}).
     * @param commandId  the ID of the command.
     * @param timeout     timeout for the request, by default {@link #DEFAULT_TIMEOUT} ms.
     * @param payloadMap the map of values to send along. This argument can be null if the header is
     *                   the only required field.
     * @param respType   the response type.
     * @param callback   the response callback.
     * @param <T>        the response type.
     */
    public <T extends McuMgrResponse> void send(int op, int commandId,
                                                @Nullable Map<String, Object> payloadMap,
                                                long timeout,
                                                @NotNull Class<T> respType,
                                                @NotNull McuMgrCallback<T> callback) {
        send(op, 0, 0, commandId, payloadMap, timeout, respType, callback);
    }

    /**
     * Send synchronous Mcu Manager command.
     * <p>
     * Additionally builds the Mcu Manager header and formats the packet based on scheme before
     * sending it to the transporter.
     *
     * @param op         the operation ({@link McuManager#OP_READ}, {@link McuManager#OP_WRITE}).
     * @param commandId  the ID of the command.
     * @param timeout     timeout for the request, by default {@link #DEFAULT_TIMEOUT} ms.
     * @param payloadMap the map of values to send along. This argument can be null if the header is
     *                   the only required field.
     * @param respType   the response type.
     * @param <T>        the response type.
     * @return The McuMgrResponse or null if an error occurred.
     * @throws McuMgrException on transport error. See exception cause for more info.
     */
    @NotNull
    public <T extends McuMgrResponse> T send(int op, int commandId,
                                             @Nullable Map<String, Object> payloadMap,
                                             long timeout,
                                             @NotNull Class<T> respType)
            throws McuMgrException {
        return send(op, 0, 0, commandId, payloadMap, timeout, respType);
    }

    /**
     * Send an asynchronous Mcu Manager command.
     * <p>
     * Additionally builds the Mcu Manager header and formats the packet based on scheme before
     * sending it to the transporter.
     *
     * @param op          the operation ({@link McuManager#OP_READ}, {@link McuManager#OP_WRITE})
     * @param flags       additional flags.
     * @param sequenceNum sequence number.
     * @param commandId   ID of the command in the group.
     * @param payloadMap  map of command's key-value pairs to construct a CBOR payload.
     * @param timeout     timeout for the request, by default {@link #DEFAULT_TIMEOUT} ms.
     * @param respType    the response type.
     * @param callback    asynchronous callback.
     * @param <T>         the response type.
     */
    public <T extends McuMgrResponse> void send(int op, int flags, int sequenceNum, int commandId,
                                                @Nullable Map<String, Object> payloadMap,
                                                long timeout,
                                                @NotNull Class<T> respType,
                                                @NotNull McuMgrCallback<T> callback) {
        try {
            byte[] packet = buildPacket(getScheme(), op, flags, mGroupId, sequenceNum,
                    commandId, payloadMap);
            send(packet, timeout, respType, callback);
        } catch (McuMgrException e) {
            callback.onError(e);
        }
    }

    /**
     * Send synchronous Mcu Manager command.
     * <p>
     * Additionally builds the Mcu Manager header and formats the packet based on scheme before
     * sending it to the transporter.
     *
     * @param op          the operation ({@link McuManager#OP_READ},
     *                    {@link McuManager#OP_WRITE}).
     * @param flags       additional flags.
     * @param sequenceNum sequence number.
     * @param commandId   ID of the command in the group.
     * @param payloadMap  map of payload key-value pairs.
     * @param timeout     timeout for the request, by default {@link #DEFAULT_TIMEOUT} ms.
     * @param respType    the response type.
     * @param <T>         the response type.
     * @return The Mcu Manager response.
     * @throws McuMgrException on transport error. See exception cause for more info.
     */
    @NotNull
    public <T extends McuMgrResponse> T send(int op, int flags, int sequenceNum,
                                             int commandId,
                                             @Nullable Map<String, Object> payloadMap,
                                             long timeout,
                                             @NotNull Class<T> respType)
            throws McuMgrException {
        byte[] packet = buildPacket(getScheme(), op, flags, mGroupId, sequenceNum,
                commandId, payloadMap);
        return send(packet, timeout, respType);
    }

    /**
     * Send data asynchronously using the transporter.
     *
     * @param data     the data to send.
     * @param respType the response type.
     * @param callback the response callback.
     * @param <T>      the response type.
     * @deprecated Use {@link #send(byte[], long, Class, McuMgrCallback)} instead.
     */
    @Deprecated
    public <T extends McuMgrResponse> void send(byte @NotNull [] data,
                                                @NotNull Class<T> respType,
                                                @NotNull McuMgrCallback<T> callback) {
        send(data, DEFAULT_TIMEOUT, respType, callback);
    }

    /**
     * Send data synchronously using the transporter.
     *
     * @param data     the data to send.
     * @param respType the response type.
     * @param <T>      the response type.
     * @return The Mcu Manager response.
     * @throws McuMgrException when an error occurs while sending the data.
     * @deprecated Use {@link #send(byte[], long, Class)} instead.
     */
    @Deprecated
    @NotNull
    public <T extends McuMgrResponse> T send(byte @NotNull [] data, @NotNull Class<T> respType)
            throws McuMgrException {
        return send(data, DEFAULT_TIMEOUT, respType);
    }

    /**
     * Send data asynchronously using the transporter.
     *
     * @param data     the data to send.
     * @param timeout  timeout for the request.
     * @param respType the response type.
     * @param callback the response callback.
     * @param <T>      the response type.
     */
    public <T extends McuMgrResponse> void send(byte @NotNull [] data,
                                                long timeout,
                                                @NotNull Class<T> respType,
                                                @NotNull McuMgrCallback<T> callback) {
        mTransporter.send(data, timeout, respType, callback);
    }

    /**
     * Send data synchronously using the transporter.
     *
     * @param data     the data to send.
     * @param timeout  timeout for the request.
     * @param respType the response type.
     * @param <T>      the response type.
     * @return The Mcu Manager response.
     * @throws McuMgrException when an error occurs while sending the data.
     */
    @NotNull
    public <T extends McuMgrResponse> T send(byte @NotNull [] data,
                                             long timeout,
                                             @NotNull Class<T> respType)
            throws McuMgrException {
        return mTransporter.send(data, timeout, respType);
    }

    /**
     * Build a Mcu Manager packet based on the transport scheme.
     *
     * @param scheme      the transport scheme.
     * @param op          the operation ({@link McuManager#OP_READ}, {@link McuManager#OP_WRITE}).
     * @param flags       additional flags.
     * @param groupId     group ID of the command.
     * @param sequenceNum sequence number.
     * @param commandId   ID of the command in the group.
     * @param payloadMap  map of payload key-value pairs.
     * @return The packet data.
     * @throws McuMgrException if the payload map could not be serialized into CBOR. See cause.
     */
    public static byte @NotNull [] buildPacket(@NotNull McuMgrScheme scheme,
                                               int op, int flags, int groupId,
                                               int sequenceNum, int commandId,
                                               @Nullable Map<String, Object> payloadMap)
            throws McuMgrException {
        byte[] packet;
        try {
            // If the payload map is null initialize an empty payload map
            if (payloadMap == null) {
                payloadMap = new HashMap<>();
            }

            // Copy the payload map to remove the header key
            HashMap<String, Object> payloadMapCopy = new HashMap<>(payloadMap);
            // Remove the header if present (for CoAP schemes)
            payloadMapCopy.remove(HEADER_KEY);

            // Get the length
            byte[] cborPayload = CBOR.toBytes(payloadMapCopy);
            int len = cborPayload.length;

            // Build header
            byte[] header = McuMgrHeader.build(SMP_VERSION, op, flags, len, groupId, sequenceNum, commandId);

            // Build the packet based on scheme
            if (scheme.isCoap()) {
                // CoAP Scheme puts the header as a key-value pair in the payload
                if (payloadMap.get(HEADER_KEY) == null) {
                    payloadMap.put(HEADER_KEY, header);
                }
                packet = CBOR.toBytes(payloadMap);
            } else {
                // Standard scheme appends the CBOR payload to the header.
                packet = new byte[header.length + cborPayload.length];
                System.arraycopy(header, 0, packet, 0, header.length);
                System.arraycopy(cborPayload, 0, packet, header.length, cborPayload.length);
            }
        } catch (IOException e) {
            throw new McuMgrException("An error occurred serializing CBOR payload", e);
        }
        return packet;
    }

    //******************************************************************
    // Utilities
    //******************************************************************

    /**
     * Format a Date and a TimeZone into a String which McuManager will accept.
     *
     * @param date     the date to format. If null, the current date on the device will be used.
     * @param timeZone the timezone of the given date. If null, the timezone on the device will be used.
     * @return A formatted string of the provided date and timezone.
     */
    @NotNull
    public static String dateToString(@Nullable Date date, @Nullable TimeZone timeZone) {
        if (date == null) {
            date = new Date();
        }
        if (timeZone == null) {
            timeZone = TimeZone.getDefault();
        }
        SimpleDateFormat mcumgrFormat = new SimpleDateFormat(MCUMGR_DATE_FORMAT, Locale.US);
        mcumgrFormat.setTimeZone(timeZone);
        return mcumgrFormat.format(date);
    }

    /**
     * Parse a date string returned by a McuMgr response.
     *
     * @param dateString the string to parse.
     * @return The Date of the string, null on error.
     */
    @Nullable
    public static Date stringToDate(@Nullable String dateString) {
        if (dateString == null) {
            return null;
        }
        SimpleDateFormat mcumgrFormat = new SimpleDateFormat(MCUMGR_DATE_FORMAT, Locale.US);
        try {
            return mcumgrFormat.parse(dateString);
        } catch (ParseException e) {
            // This method is static and has no logger to report to. As documented, an
            // unparsable date is reported by returning null.
            return null;
        }
    }
}

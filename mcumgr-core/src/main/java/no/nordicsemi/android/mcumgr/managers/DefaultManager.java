/*
 * Copyright (c) 2017-2018 Runtime Inc.
 * Copyright (c) Intellinium SAS, 2014-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.managers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;

import no.nordicsemi.android.mcumgr.McuManager;
import no.nordicsemi.android.mcumgr.McuMgrCallback;
import no.nordicsemi.android.mcumgr.McuMgrErrorCode;
import no.nordicsemi.android.mcumgr.McuMgrGroupReturnCode;
import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.response.HasReturnCode;
import no.nordicsemi.android.mcumgr.response.McuMgrResponse;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrAppInfoResponse;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrBootloaderInfoResponse;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrEchoResponse;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrMpStatResponse;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrOsResponse;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrParamsResponse;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrReadDateTimeResponse;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrTaskStatResponse;

/**
 * The manager for the OS group.
 * <p>
 * See: <a href="https://github.com/nrfconnect/sdk-zephyr/tree/main/subsys/mgmt/mcumgr/grp/os_mgmt">GitHub</a>
 */
@SuppressWarnings("unused")
public class DefaultManager extends McuManager {
    public enum ReturnCode implements McuMgrGroupReturnCode {
        /** No error, this is implied if there is no ret value in the response */
        OK(0),

        /** Unknown error occurred. */
        UNKNOWN(1),

        /** The provided format value is not valid. */
        INVALID_FORMAT(2),

        /** Query was not recognized. */
        QUERY_YIELDS_NO_ANSWER(3),

        /** RTC is not set */
        RTC_NOT_SET(4),

        /** RTC command failed */
        RTC_COMMAND_FAILED(5),

        /** Query was recognized but there is no valid value for the response. */
        QUERY_RESPONSE_VALUE_NOT_VALID(6),

        /** Heap statistic fetch failed. */
        HEAP_STATS_FETCH_FAILED(7);

        private final int mCode;

        ReturnCode(int code) {
            mCode = code;
        }

        public int value() {
            return mCode;
        }

        public static @Nullable DefaultManager.ReturnCode valueOf(@Nullable McuMgrResponse.GroupReturnCode returnCode) {
            if (returnCode == null || returnCode.group != GROUP_DEFAULT) {
                return null;
            }
            for (DefaultManager.ReturnCode code : values()) {
                if (code.value() == returnCode.rc) {
                    return code;
                }
            }
            return UNKNOWN;
        }
    }

    public interface Response extends HasReturnCode {

        @Nullable
        default DefaultManager.ReturnCode getOsReturnCode() {
            McuMgrResponse.GroupReturnCode groupReturnCode = getGroupReturnCode();
            if (groupReturnCode == null) {
                if (getReturnCodeValue() == McuMgrErrorCode.OK.value()) {
                    return DefaultManager.ReturnCode.OK;
                }
                return DefaultManager.ReturnCode.UNKNOWN;
            }
            return DefaultManager.ReturnCode.valueOf(groupReturnCode);
        }
    }

    // Command IDs
    private final static int ID_ECHO = 0;
    private final static int ID_CONS_ECHO_CTRL = 1;
    private final static int ID_TASKSTATS = 2;
    private final static int ID_MPSTATS = 3;
    private final static int ID_DATETIME_STR = 4;
    private final static int ID_RESET = 5;
    private final static int ID_MCUMGR_PARAMS = 6;
    private final static int ID_APP_INFO = 7;
    private final static int ID_BOOTLOADER_INFO = 8;

    /** This mode (default) reboots the device to the application. */
    public static final int BOOT_MODE_TYPE_NORMAL = 0;
    /** On supported devices this mode reboots the device into a bootloader or firmware loader mode. */
    public static final int BOOT_MODE_TYPE_BOOTLOADER = 1;

    @Retention(RetentionPolicy.SOURCE)
    public @interface BootMode {
        /**
         * Normal boot mode, the device will boot into the application.
         */
        int NORMAL = BOOT_MODE_TYPE_NORMAL;

        /**
         * Bootloader mode, the device will boot into a bootloader or firmware loader.
         */
        int BOOTLOADER = BOOT_MODE_TYPE_BOOTLOADER;
    }
    /**
     * Construct the OS manager.
     *
     * @param transport the transport to use to send commands.
     */
    public DefaultManager(@NotNull McuMgrTransport transport) {
        super(GROUP_DEFAULT, transport);
    }

    //******************************************************************
    // Default Commands
    //******************************************************************

    /**
     * Echo a string (asynchronous).
     *
     * @param echo     the string to echo.
     * @param callback the asynchronous callback.
     */
    public void echo(@Nullable String echo, @NotNull McuMgrCallback<McuMgrEchoResponse> callback) {
        LOG.trace("Echo: {}", echo);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("d", echo);
        send(OP_WRITE, ID_ECHO, payloadMap, SHORT_TIMEOUT, McuMgrEchoResponse.class, callback);
    }

    /**
     * Echo a string (synchronous).
     *
     * @param echo the string to echo.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrEchoResponse echo(@Nullable String echo) throws McuMgrException {
        LOG.trace("Echo: {}", echo);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("d", echo);
        return send(OP_WRITE, ID_ECHO, payloadMap, SHORT_TIMEOUT, McuMgrEchoResponse.class);
    }

    /**
     * Set the console echo on the device (synchronous).
     *
     * @param echo     whether or not to echo to the console.
     * @param callback the asynchronous callback.
     */
    public void consoleEcho(boolean echo, @NotNull McuMgrCallback<McuMgrOsResponse> callback) {
        // TODO This is not supported on Zephyr and should be removed? The param is most prob invalid.
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("echo", echo);
        send(OP_WRITE, ID_CONS_ECHO_CTRL, payloadMap, SHORT_TIMEOUT, McuMgrOsResponse.class, callback);
    }

    /**
     * Set the console echo on the device (synchronous).
     *
     * @param echo whether or not to echo to the console.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrOsResponse consoleEcho(boolean echo) throws McuMgrException {
        // TODO This is not supported on Zephyr and should be removed? The param is most prob invalid.
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("echo", echo);
        return send(OP_WRITE, ID_CONS_ECHO_CTRL, payloadMap, SHORT_TIMEOUT, McuMgrOsResponse.class);
    }

    /**
     * Get task statistics from the device (asynchronous).
     *
     * @param callback the asynchronous callback.
     */
    public void taskstats(@NotNull McuMgrCallback<McuMgrTaskStatResponse> callback) {
        LOG.trace("Reading task stats");
        send(OP_READ, ID_TASKSTATS, null, SHORT_TIMEOUT, McuMgrTaskStatResponse.class, callback);
    }

    /**
     * Get task statistics from the device (synchronous).
     *
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrTaskStatResponse taskstats() throws McuMgrException {
        LOG.trace("Reading task stats");
        return send(OP_READ, ID_TASKSTATS, null, SHORT_TIMEOUT, McuMgrTaskStatResponse.class);
    }

    /**
     * Get memory pool statistics from the device (asynchronous).
     *
     * @param callback the asynchronous callback.
     */
    public void mpstat(@NotNull McuMgrCallback<McuMgrMpStatResponse> callback) {
        LOG.trace("Reading memory pool stats");
        send(OP_READ, ID_MPSTATS, null, SHORT_TIMEOUT, McuMgrMpStatResponse.class, callback);
    }

    /**
     * Get memory pool statistics from the device (synchronous).
     *
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrMpStatResponse mpstat() throws McuMgrException {
        LOG.trace("Reading memory pool stats");
        return send(OP_READ, ID_MPSTATS, null, SHORT_TIMEOUT, McuMgrMpStatResponse.class);
    }

    /**
     * Read the date and time on the device (asynchronous).
     *
     * @param callback the asynchronous callback.
     */
    public void readDatetime(@NotNull McuMgrCallback<McuMgrReadDateTimeResponse> callback) {
        LOG.trace("Reading date and time");
        send(OP_READ, ID_DATETIME_STR, null, SHORT_TIMEOUT, McuMgrReadDateTimeResponse.class, callback);
    }

    /**
     * Read the date and time on the device (synchronous).
     *
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrReadDateTimeResponse readDatetime() throws McuMgrException {
        LOG.trace("Reading date and time");
        return send(OP_READ, ID_DATETIME_STR, null, SHORT_TIMEOUT, McuMgrReadDateTimeResponse.class);
    }

    /**
     * Write the date and time on the device (asynchronous).
     * <p>
     * If date or timeZone are null, the current value will be used.
     *
     * @param date     the date to set the device to.
     * @param timeZone the timezone to use with the date.
     * @param callback the asynchronous callback.
     */
    public void writeDatetime(@Nullable Date date, @Nullable TimeZone timeZone,
                              @NotNull McuMgrCallback<McuMgrOsResponse> callback) {
        String dateAsString = dateToString(date, timeZone);
        LOG.trace("Writing date and time: {}", dateAsString);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("datetime", dateAsString);
        send(OP_WRITE, ID_DATETIME_STR, payloadMap, SHORT_TIMEOUT, McuMgrOsResponse.class, callback);
    }

    /**
     * Write the date and time on the device (synchronous).
     * <p>
     * If date or timeZone are null, the current value will be used.
     *
     * @param date     the date to set the device to.
     * @param timeZone the timezone to use with the date.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrOsResponse writeDatetime(@Nullable Date date, @Nullable TimeZone timeZone)
            throws McuMgrException {
        String dateAsString = dateToString(date, timeZone);
        LOG.trace("Writing date and time: {}", dateAsString);
        HashMap<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("datetime", dateAsString);
        return send(OP_WRITE, ID_DATETIME_STR, payloadMap, SHORT_TIMEOUT, McuMgrOsResponse.class);
    }

    /**
     * Reset the device (asynchronous).
     * <p>
     * As <code>boot_mode</code> and <code>force</code> parameters are not provided, the device will
     * reboot into the application mode (normal boot mode) and the reset will not be forced
     * (assuming <code>CONFIG_MCUMGR_GRP_OS_RESET_HOOK</code> enabled).
     *
     * @param callback the asynchronous callback.
     * @see <a href="https://docs.nordicsemi.com/bundle/ncs-latest/page/zephyr/services/device_mgmt/smp_groups/smp_group_0.html#system_reset">Documentation</a>
     */
    public void reset(@NotNull McuMgrCallback<McuMgrOsResponse> callback) {
        reset(BOOT_MODE_TYPE_NORMAL, false, callback);
    }

    /**
     * Reset the device (synchronous).
     * <p>
     * As <code>boot_mode</code> and <code>force</code> parameters are not provided, the device will
     * reboot into the application mode (normal boot mode) and the reset will not be forced
     * (assuming <code>CONFIG_MCUMGR_GRP_OS_RESET_HOOK</code> enabled).
     *
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     * @see <a href="https://docs.nordicsemi.com/bundle/ncs-latest/page/zephyr/services/device_mgmt/smp_groups/smp_group_0.html#system_reset">Documentation</a>
     */
    @NotNull
    public McuMgrOsResponse reset() throws McuMgrException {
        return reset(BOOT_MODE_TYPE_NORMAL, false);
    }

    /**
     * Reset the device (asynchronous).
     * <p>
     * By default, this command is accepted in all conditions, however if the
     * <code>CONFIG_MCUMGR_GRP_OS_RESET_HOOK</code> is enabled and an application
     * registers a callback, the callback will be called when this command is issued
     * and can be used to perform any necessary tidy operations prior to the module
     * rebooting, or to reject the reset request outright altogether with an error
     * response.
     *
     * @param bootMode The boot mode to use for the reset, defaults to {@link #BOOT_MODE_TYPE_NORMAL}.
     * @param force Should the reset be forced not allowing application to reject it, defaults to false.
     * @param callback the asynchronous callback.
     * @see <a href="https://docs.nordicsemi.com/bundle/ncs-latest/page/zephyr/services/device_mgmt/smp_groups/smp_group_0.html#system_reset">Documentation</a>
     */
    public void reset(@BootMode int bootMode, boolean force, @NotNull McuMgrCallback<McuMgrOsResponse> callback) {
        LOG.trace("Resetting (boot mode: {}, force: {})", bootMode, force);
        HashMap<String, Object> payloadMap = null;
        if (bootMode > BOOT_MODE_TYPE_NORMAL || force) {
            payloadMap = new HashMap<>();
            if (bootMode > BOOT_MODE_TYPE_NORMAL) {
                payloadMap.put("boot_mode", bootMode);
            }
            if (force) {
                payloadMap.put("force", true);
            }
        }
        send(OP_WRITE, ID_RESET, payloadMap, SHORT_TIMEOUT, McuMgrOsResponse.class, callback);
    }

    /**
     * Reset the device (synchronous).
     * <p>
     * By default, this command is accepted in all conditions, however if the
     * <code>CONFIG_MCUMGR_GRP_OS_RESET_HOOK</code> is enabled and an application
     * registers a callback, the callback will be called when this command is issued
     * and can be used to perform any necessary tidy operations prior to the module
     * rebooting, or to reject the reset request outright altogether with an error
     * response.
     *
     * @param bootMode The boot mode to use for the reset, defaults to {@link #BOOT_MODE_TYPE_NORMAL}.
     * @param force Should the reset be forced not allowing application to reject it, defaults to false.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     * @see <a href="https://docs.nordicsemi.com/bundle/ncs-latest/page/zephyr/services/device_mgmt/smp_groups/smp_group_0.html#system_reset">Documentation</a>
     */
    @NotNull
    public McuMgrOsResponse reset(@BootMode int bootMode, boolean force) throws McuMgrException {
        LOG.trace("Resetting (boot mode: {}, force: {})", bootMode, force);
        HashMap<String, Object> payloadMap = null;
        if (bootMode > BOOT_MODE_TYPE_NORMAL || force) {
            payloadMap = new HashMap<>();
            if (bootMode > BOOT_MODE_TYPE_NORMAL) {
                payloadMap.put("boot_mode", bootMode);
            }
            if (force) {
                payloadMap.put("force", true);
            }
        }
        return send(OP_WRITE, ID_RESET, payloadMap, SHORT_TIMEOUT, McuMgrOsResponse.class);
    }

    /**
     * Reads McuMgr parameters (asynchronous).
     *
     * @param callback the asynchronous callback.
     */
    public void params(@NotNull McuMgrCallback<McuMgrParamsResponse> callback) {
        LOG.trace("Reading McuMgr parameters");
        send(OP_READ, ID_MCUMGR_PARAMS, null, SHORT_TIMEOUT, McuMgrParamsResponse.class, callback);
    }

    /**
     * Reads McuMgr parameters (synchronous).
     *
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrParamsResponse params() throws McuMgrException {
        LOG.trace("Reading McuMgr parameters");
        return send(OP_READ, ID_MCUMGR_PARAMS, null, SHORT_TIMEOUT, McuMgrParamsResponse.class);
    }

    /**
     * Reads OS/Application Info (asynchronous).
     *
     * @param format Format specifier of returned response, fields are appended in their natural
     *               ascending index order, not the order of characters that are received by the command.
     *               Format specifiers:
     *               <ul>
     *                   <li><b>s</b> Kernel name</li>
     *                   <li><b>n</b> Node name</li>
     *                   <li><b>r</b> Kernel release</li>
     *                   <li><b>v</b> Kernel version</li>
     *                   <li><b>b</b> Build date and time</li>
     *                   <li><b>m</b> Machine</li>
     *                   <li><b>p</b> Processor</li>
     *                   <li><b>i</b> Hardware platform</li>
     *                   <li><b>o</b> Operating system</li>
     *                   <li><b>a</b> All fields (shorthand for all above options)</li>
     *               </ul>
     *               If this option is not provided, the <b>s</b> Kernel name option will be used.
     * @param callback the asynchronous callback.
     */
    public void appInfo(@Nullable String format, @NotNull McuMgrCallback<McuMgrAppInfoResponse> callback) {
        LOG.trace("Reading app info (format: {})", format);
        HashMap<String, Object> payloadMap = null;
        if (format != null) {
            payloadMap = new HashMap<>();
            payloadMap.put("format", format);
        }
        send(OP_READ, ID_APP_INFO, payloadMap, SHORT_TIMEOUT, McuMgrAppInfoResponse.class, callback);
    }

    /**
     * Reads OS/Application Info (synchronous).
     *
     * @param format Format specifier of returned response, fields are appended in their natural
     *               ascending index order, not the order of characters that are received by the command.
     *               Format specifiers:
     *               <ul>
     *                   <li><b>s</b> Kernel name</li>
     *                   <li><b>n</b> Node name</li>
     *                   <li><b>r</b> Kernel release</li>
     *                   <li><b>v</b> Kernel version</li>
     *                   <li><b>b</b> Build date and time</li>
     *                   <li><b>m</b> Machine</li>
     *                   <li><b>p</b> Processor</li>
     *                   <li><b>i</b> Hardware platform</li>
     *                   <li><b>o</b> Operating system</li>
     *                   <li><b>a</b> All fields (shorthand for all above options)</li>
     *               </ul>
     *               If this option is not provided, the <b>s</b> Kernel name option will be used.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrAppInfoResponse appInfo(@Nullable String format) throws McuMgrException {
        LOG.trace("Reading app info (format: {})", format);
        HashMap<String, Object> payloadMap = null;
        if (format != null) {
            payloadMap = new HashMap<>();
            payloadMap.put("format", format);
        }
        return send(OP_READ, ID_APP_INFO, payloadMap, SHORT_TIMEOUT, McuMgrAppInfoResponse.class);
    }

    /**
     * When used as query in {@link #bootloaderInfo(String)} command, returns string representing
     * bootloader name.
     */
    public static final String BOOTLOADER_INFO_QUERY_BOOTLOADER = null;
    /**
     * For bootloader with name "MCUboot" returns the bootloader mode.
     */
    public static final String BOOTLOADER_INFO_MCUBOOT_QUERY_MODE = "mode";
    /**
     * For bootloader with name "MCUboot" returns the id of a b0 slot with higher version.
     * <p>
     * The b0 is a bootloader that boots MCUboot and is used to upgrade MCUboot itself. It has 2
     * slots: primary (0) and secondary (1) and can boot from either of them.
     * @see <a href="https://docs.nordicsemi.com/bundle/ncs-latest/page/nrf/samples/bootloader/README.html">nRF Secure Immutable Bootloader B0</a>
     */
    public static final String BOOTLOADER_INFO_QUERY_ACTIVE_B0_SLOT = "active_b0_slot";

    /**
     * Reads the Bootloader info (asynchronous).
     *
     * @param query Allows to query MCUmgr about bootloader used by device and various bootloader
     * 	            parameters. Use {@link #BOOTLOADER_INFO_QUERY_BOOTLOADER} to get string
     * 	            representing bootloader name.
     * 	            For bootloader named "MCUboot" use {@link #BOOTLOADER_INFO_MCUBOOT_QUERY_MODE} to get
     * 	            the bootloader mode. If query yields no answer, the response will contain
     * 	            an {@link ReturnCode#QUERY_YIELDS_NO_ANSWER} error.
     * 	            Use {@link #BOOTLOADER_INFO_QUERY_ACTIVE_B0_SLOT} to get the ID of an active B0 slot.
     * @param callback the asynchronous callback.
     */
    public void bootloaderInfo(@Nullable String query, @NotNull McuMgrCallback<McuMgrBootloaderInfoResponse> callback) {
        LOG.trace("Reading Bootloader info (query: {})", query);
        HashMap<String, Object> payloadMap = null;
        if (query != null) {
            payloadMap = new HashMap<>();
            payloadMap.put("query", query);
        }
        send(OP_READ, ID_BOOTLOADER_INFO, payloadMap, SHORT_TIMEOUT, McuMgrBootloaderInfoResponse.class, callback);
    }

    /**
     * Reads the Bootloader info (synchronous).
     *
     * @param query Allows to query MCUmgr about bootloader used by device and various bootloader
     * 	            parameters. Use {@link #BOOTLOADER_INFO_QUERY_BOOTLOADER} to get string
     * 	            representing bootloader name.
     * 	            For bootloader named "MCUboot" use {@link #BOOTLOADER_INFO_MCUBOOT_QUERY_MODE} to get
     * 	            the bootloader mode. If query yields no answer, the response will contain
     * 	            an {@link ReturnCode#QUERY_YIELDS_NO_ANSWER} error.
     * 	            Use {@link #BOOTLOADER_INFO_QUERY_ACTIVE_B0_SLOT} to get the ID of an active B0 slot.
     * @return The response.
     * @throws McuMgrException Transport error. See cause.
     */
    @NotNull
    public McuMgrBootloaderInfoResponse bootloaderInfo(@Nullable String query) throws McuMgrException {
        LOG.trace("Reading Bootloader info (query: {})", query);
        HashMap<String, Object> payloadMap = null;
        if (query != null) {
            payloadMap = new HashMap<>();
            payloadMap.put("query", query);
        }
        return send(OP_READ, ID_BOOTLOADER_INFO, payloadMap, SHORT_TIMEOUT, McuMgrBootloaderInfoResponse.class);
    }
}

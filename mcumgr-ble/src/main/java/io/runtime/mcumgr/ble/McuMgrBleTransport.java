/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package io.runtime.mcumgr.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import io.runtime.mcumgr.McuMgrCallback;
import io.runtime.mcumgr.McuMgrHeader;
import io.runtime.mcumgr.McuMgrScheme;
import io.runtime.mcumgr.McuMgrTransport;
import io.runtime.mcumgr.ble.callback.SmpMerger;
import io.runtime.mcumgr.ble.callback.SmpProtocolSession;
import io.runtime.mcumgr.ble.callback.SmpTransaction;
import io.runtime.mcumgr.ble.callback.TransactionTimeoutException;
import io.runtime.mcumgr.ble.exception.McuMgrBluetoothDisabledException;
import io.runtime.mcumgr.ble.exception.McuMgrDisconnectedException;
import io.runtime.mcumgr.ble.exception.McuMgrInsufficientAuthenticationException;
import io.runtime.mcumgr.ble.exception.McuMgrNotSupportedException;
import io.runtime.mcumgr.ble.exception.McuMgrUnsupportedConfigurationException;
import io.runtime.mcumgr.ble.util.ResultCondition;
import io.runtime.mcumgr.exception.InsufficientMtuException;
import io.runtime.mcumgr.exception.McuMgrErrorException;
import io.runtime.mcumgr.exception.McuMgrException;
import io.runtime.mcumgr.exception.McuMgrTimeoutException;
import io.runtime.mcumgr.managers.DefaultManager;
import io.runtime.mcumgr.response.McuMgrResponse;
import io.runtime.mcumgr.response.dflt.McuMgrParamsResponse;
import io.runtime.mcumgr.util.CBOR;
import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.annotation.ConnectionPriority;
import no.nordicsemi.android.ble.callback.FailCallback;
import no.nordicsemi.android.ble.data.DataMerger;
import no.nordicsemi.android.ble.error.GattError;

/**
 * The McuMgrBleTransport is an implementation for the {@link McuMgrScheme#BLE} transport scheme.
 * This class extends {@link BleManager}, which handles the BLE state machine and owns the
 * {@link BluetoothGatt} object that executes BLE actions.
 * <p>
 * Starting from version 1.1 it is possible to extend the functionality to support additional
 * services. For that extend the following methods:
 * <ul>
 *     <li>{@link #isAdditionalServiceSupported(BluetoothGatt)}</li>
 *     <li>{@link #initializeAdditionalServices()}</li>
 *     <li>{@link #onServicesInvalidated()}</li>
 * </ul>
 */
@SuppressWarnings("unused")
public class McuMgrBleTransport extends BleManager implements McuMgrTransport {

    private static final Logger LOG = LoggerFactory.getLogger(McuMgrBleTransport.class);

    /**
     * The SMP service UUID.
     *
     * @deprecated Use {@link DefaultMcuMgrUuidConfig#SMP_SERVICE_UUID} instead.
     */
    @Deprecated
    public final static UUID SMP_SERVICE_UUID = DefaultMcuMgrUuidConfig.SMP_SERVICE_UUID;

    // Use a separate characteristic object for writes vs notifications.
    //
    // We must clone the characteristic object in order to ensure no race
    // conditions with BluetoothGattCharacteristic's getValue() function when
    // asynchronously writing to and receiving notifications from the same
    // characteristic.
    //
    // Me must write to the clone and receive from the original in order to
    // ensure that the OS selects the correct characteristic object from the
    // service's list.
    //
    // More info:
    // https://stackoverflow.com/questions/38922639/how-could-i-achieve-maximum-thread-safety-with-a-read-write-ble-gatt-characteris

    /**
     * Simple Management Protocol write characteristic.
     */
    private BluetoothGattCharacteristic mSmpCharacteristicWrite;

    /**
     * Simple Management Protocol notify characteristic.
     */
    private BluetoothGattCharacteristic mSmpCharacteristicNotify;

    /**
     * The Bluetooth device for this transporter.
     */
    private final BluetoothDevice mDevice;

    /**
     * An instance of a merger used to merge SMP packets that are split into multiple BLE packets.
     */
    private final DataMerger mSMPMerger = new SmpMerger();

    /**
     * The maximum packet length supported by the target device.
     * This may be greater than MTU size.
     * For packets longer than this value an {@link InsufficientMtuException} will be thrown.
     * Packets longer than MTU, but shorter than this value will be split.
     * Splitting packets must be supported by SMP Server on the target device.
     */
    private int mMaxPacketLength;

    /**
     * The initial MTU size to be requested upon connection.
     */
    private int mInitialMtu = 498;

    /**
     * Flag indicating should low-level logging be enabled. Default to false.
     * Call {@link #setLoggingEnabled(boolean)} to change.
     */
    private boolean mLoggingEnabled;

    /**
     * The protocol layer session allows for asynchronous requests and responses
     * by using the sequence number to match transactions.
     * The session object is set when the device connects and the SMP service is
     * initialized. When the device disconnects, the protocol session is closed
     * and this variable is set to null.
     */
    private SmpProtocolSession mSmpProtocol;

    /**
     * The handler used to initialize {@link BleManager} and
     * {@link SmpProtocolSession}. The protocol session will call callbacks on
     * the handler.
     */
    private final Handler mHandler;

    /**
     * The UUID configuration. This object allows for using custom UUIDs.
     */
    private final UuidConfig mUUIDConfig;

    /**
     * Construct a McuMgrBleTransport object.
     * <p>
     * Uses the main thread for callbacks.
     *
     * @param context the context used to connect to the device.
     * @param device  the device to connect to and communicate with.
     */
    public McuMgrBleTransport(@NonNull Context context, @NonNull BluetoothDevice device) {
        this(context, device, new Handler(Looper.getMainLooper()), new DefaultMcuMgrUuidConfig());
    }

    /**
     * Construct a McuMgrBleTransport object with a handler to run the
     * {@link BleManager} and asynchronous callbacks.
     *
     * @param context the context used to connect to the device.
     * @param device  the device to connect to and communicate with.
     * @param handler the handler to run the {@link BleManager} and {@link McuMgrCallback}s.
     */
    public McuMgrBleTransport(@NonNull Context context,
                              @NonNull BluetoothDevice device,
                              @NonNull Handler handler) {
        this(context, device, handler, new DefaultMcuMgrUuidConfig());
    }

    /**
     * Construct a McuMgrBleTransport object with a handler to run the
     * {@link BleManager} and asynchronous callbacks.
     *
     * @param context the context used to connect to the device.
     * @param device  the device to connect to and communicate with.
     * @param handler the handler to run the {@link BleManager} and {@link McuMgrCallback}s.
     * @param uuidConfig custom UUID configuration.
     */
    public McuMgrBleTransport(@NonNull Context context,
                              @NonNull BluetoothDevice device,
                              @NonNull Handler handler,
                              @NonNull UuidConfig uuidConfig) {
        super(context, handler);
        mHandler = handler;
        mDevice = device;
        mUUIDConfig = uuidConfig;
    }

    /**
     * Returns the device set in the constructor.
     *
     * @return The device to connect to and communicate with.
     */
    @NonNull
    @Override
    public BluetoothDevice getBluetoothDevice() {
        return mDevice;
    }

    //*******************************************************************************************
    // Maximum SMP packet length.
    //*******************************************************************************************

    /**
     * In order to send packets longer than MTU size, this library supports automatic segmentation
     * of packets into at-most-MTU size chunks. This feature must be also supported by the target
     * device, as it must reassembly received chunks into full SMP packet, based on the length field
     * from the {@link io.runtime.mcumgr.McuMgrHeader}, included in the first segment.
     * <p>
     * This method sets the maximum packet length supported by the target device.
     * By default, this is be set to MTU - 3, which means that each BLE packet will contain the full
     * SMP packet (header + CBOR-encoded data). For devices supporting reading McuMgr parameters
     * (nRF Connect SDK 2.0+) this value is automatically obtained after connection using
     * {@link DefaultManager#params()}.
     * <p>
     * Keep in mind, that before Android 5 requesting higher MTU was not supported. Setting the
     * maximum length to a greater value is required on those devices in order to upgrade
     * the firmware, send file or send any other SMP packet that is longer than 20 bytes.
     *
     * @since 1.3
     * @param maxLength the maximum packet length.
     */
    public final void setMaxPacketLength(final int maxLength) {
        mMaxPacketLength = maxLength;
    }

    /**
     * Returns the maximum length of a SMP packet that can be transmitted over by the transport.
     * @return the maximum
     */
    public final int getMaxPacketLength() {
        return mMaxPacketLength;
    }

    /**
     * Sets the initial MTU size to be requested upon connection.
     * <p>
     * In general, it is the device side that should decide the MTU size. However, if the device
     * claims support for higher MTU than in fact it does, this method can be used to lower the MTU.
     * <p>
     * By default, this is set to 498. This MTU will be requested when connecting to the device.
     * If the device supports lower MTU, it will be used instead.
     * <p>
     * This method should be called before connecting to the device and has no affect after.
     * @param mtu The initial MTU size to be requested upon connection.
     */
    public void setInitialMtu(final @IntRange(from = 23, to = 517) int mtu) {
        this.mInitialMtu = mtu;
    }

    /**
     * Sets the maximum packet length for the transport.
     * <p>
     * Starting from version 1.3 the library can automatically obtain the value from a device build
     * on nRF Connect SDK 2.0+ using the new {@link DefaultManager#params()} command. If the feature
     * is not supported on the device the maximum length defaults to MTU-3 bytes.
     *
     * @param maxLength the maximum packet length.
     * @deprecated Use {@link #setMaxPacketLength(int)} instead.
     */
    @Deprecated
    public void setDeviceSidePacketMergingSupported(int maxLength) {
        setMaxPacketLength(maxLength);
    }

    //*******************************************************************************************
    // Logging
    //*******************************************************************************************

    /**
     * Allows to enable low-level logging. If enabled, all BLE events will be logged.
     *
     * @param enabled true to enable logging, false to disable (default).
     */
    public void setLoggingEnabled(boolean enabled) {
        mLoggingEnabled = enabled;
    }

    @Override
    public int getMinLogPriority() {
        return mLoggingEnabled ? super.getMinLogPriority() : Log.WARN;
    }

    @Override
    public void log(int priority, @NonNull String message) {
        switch (priority) {
            case Log.DEBUG: {
                LOG.debug(message);
                break;
            }
            case Log.INFO: {
                LOG.info(message);
                break;
            }
            case Log.WARN: {
                LOG.warn(message);
                break;
            }
            case Log.ASSERT:
            case Log.ERROR: {
                LOG.error(message);
                break;
            }
            case Log.VERBOSE: {
                LOG.trace(message);
                break;
            }
        }
    }

    //*******************************************************************************************
    // Mcu Manager Transport
    //*******************************************************************************************

    @NonNull
    @Override
    public McuMgrScheme getScheme() {
        return McuMgrScheme.BLE;
    }

    @NonNull
    @Override
    public <T extends McuMgrResponse> T send(@NonNull final byte[] payload,
                                             long timeout,
                                             @NonNull final Class<T> responseType)
            throws McuMgrException {
        final ResultCondition<T> condition = new ResultCondition<>(false);
        send(payload, timeout, responseType, new McuMgrCallback<>() {
            @Override
            public void onResponse(@NonNull T response) {
                condition.open(response);
            }

            @Override
            public void onError(@NonNull McuMgrException error) {
                condition.openExceptionally(error);
            }
        });
        return condition.block();
    }

    @Override
    public <T extends McuMgrResponse> void send(@NonNull final byte[] payload,
                                                final long timeout,
                                                @NonNull final Class<T> responseType,
                                                @NonNull final McuMgrCallback<T> callback) {

        // If device is not connected, connect.
        // If the device was already connected, the completion callback will be called immediately.
        final boolean wasConnected = isConnected();
        connect(mDevice)
                .done(device -> {
                    if (!wasConnected) {
                        notifyConnected();
                    }

                    // Ensure the MTU is sufficient. Packets longer than MTU, but shorter
                    // then few MTU lengths can be split automatically.
                    if (mMaxPacketLength < payload.length) {
                        callback.onError(new InsufficientMtuException(payload.length, mMaxPacketLength));
                        return;
                    }

                    // Send a new transaction to the protocol layer
                    final SmpProtocolSession session = mSmpProtocol;
                    session.send(payload, timeout, new SmpTransaction() {
                        @Override
                        public void send(@NonNull byte[] data) {
                            if (getMinLogPriority() <= Log.INFO) {
                                try {
                                    log(Log.INFO, "Sending (" + payload.length + " bytes) "
                                            + McuMgrHeader.fromBytes(payload) + " CBOR "
                                            + CBOR.toString(payload, McuMgrHeader.HEADER_LENGTH));
                                } catch (Exception e) {
                                    // Ignore
                                }
                            }

                            // As the write is done without response, it will finish successfully
                            // even if the device is unreachable. There is no need to catch any
                            // failures. In the device gets disconnected, the SMP protocol
                            // session will be closed and all requests will be cancelled.

                            // Note: waitForNotification is not uses, as the library supports
                            //       asynchronous writes, that is can send multiple requests
                            //       before receiving a notification and will match responses
                            //       to the callbacks based on the Sequence number in each packet.
                            writeCharacteristic(mSmpCharacteristicWrite, payload,
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                                    .split()
                                    .enqueue();
                        }

                        @Override
                        public void onResponse(@NonNull byte[] data) {
                            try {
                                T response = McuMgrResponse.buildResponse(McuMgrScheme.BLE, data, responseType);
                                if (response.isSuccess()) {
                                    callback.onResponse(response);
                                } else {
                                    callback.onError(new McuMgrErrorException(response));
                                }
                            } catch (final Exception e) {
                                callback.onError(new McuMgrException(e));
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Throwable e) {
                            if (e instanceof McuMgrException) {
                                callback.onError((McuMgrException) e);
                            } else if (e instanceof TransactionTimeoutException) {
                                callback.onError(new McuMgrTimeoutException(e));
                            } else {
                                callback.onError(new McuMgrException(e));
                            }
                        }
                    });
                }).fail((device, status) -> {
                    switch (status) {
                        // This could be thrown only if the manager was requested to connect for
                        // a second time and to a different device than the one that's already
                        // connected. This may not happen here, as the device is given in the
                        // constructor.
                        // This may also happen if service discovery ends with an error, which
                        // will trigger disconnection.
                        case FailCallback.REASON_REQUEST_FAILED:
                        case FailCallback.REASON_DEVICE_DISCONNECTED:
                        case FailCallback.REASON_CANCELLED: {
                            callback.onError(new McuMgrDisconnectedException());
                            break;
                        }
                        case FailCallback.REASON_UNSUPPORTED_CONFIGURATION: {
                            log(Log.ERROR, "Android device failed to reply to PHY request, disable PHY LE 2M on the peripheral.");
                            callback.onError(new McuMgrUnsupportedConfigurationException());
                            break;
                        }
                        case FailCallback.REASON_DEVICE_NOT_SUPPORTED: {
                            callback.onError(new McuMgrNotSupportedException());
                            break;
                        }
                        case FailCallback.REASON_TIMEOUT: {
                            // Called after receiving error 133 after 30 seconds.
                            callback.onError(new McuMgrTimeoutException());
                            break;
                        }
                        case FailCallback.REASON_BLUETOOTH_DISABLED: {
                            callback.onError(new McuMgrBluetoothDisabledException());
                            break;
                        }
                        default: {
                            if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {
                                log(Log.ERROR, "Unable to resume encryption, pairing removed from peer");
                                callback.onError(new McuMgrInsufficientAuthenticationException());
                            } else {
                                callback.onError(new McuMgrException(GattError.parseConnectionError(status)));
                            }
                            break;
                        }                    }
                })
        .retry(3, 500)
        .enqueue();
    }

    @Override
    public void connect(@Nullable final ConnectionCallback callback) {
        if (isReady()) {
            if (callback != null) {
                callback.onConnected();
            }
            return;
        }
        connect(mDevice)
                .retry(3, 500)
                .done(device -> {
                    notifyConnected();
                    if (callback == null) {
                        return;
                    }
                    callback.onConnected();
                })
                .fail((device, status) -> {
                    switch (status) {
                        case FailCallback.REASON_UNSUPPORTED_CONFIGURATION:
                            log(Log.ERROR, "Android device failed to reply to PHY request, disable PHY LE 2M on the peripheral.");
                            break;
                        case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:
                            log(Log.ERROR, "Unable to resume encryption, pairing removed from peer");
                            break;
                    }
                    if (callback == null) {
                        return;
                    }
                    switch (status) {
                        // This could be thrown only if the manager was requested to connect for
                        // a second time and to a different device than the one that's already
                        // connected. This may not happen here, as the device is given in the
                        // constructor.
                        // This may also happen if service discovery ends with an error, which
                        // will trigger disconnection.
                        case FailCallback.REASON_REQUEST_FAILED:
                        case FailCallback.REASON_DEVICE_DISCONNECTED:
                        case FailCallback.REASON_CANCELLED: {
                            callback.onError(new McuMgrDisconnectedException());
                            break;
                        }
                        case FailCallback.REASON_UNSUPPORTED_CONFIGURATION: {
                            callback.onError(new McuMgrUnsupportedConfigurationException());
                            break;
                        }
                        case FailCallback.REASON_DEVICE_NOT_SUPPORTED: {
                            callback.onError(new McuMgrNotSupportedException());
                            break;
                        }
                        case FailCallback.REASON_TIMEOUT: {                            // Called after receiving error 133 after 30 seconds.
                            callback.onError(new McuMgrTimeoutException());
                            break;
                        }
                        case FailCallback.REASON_BLUETOOTH_DISABLED: {
                            callback.onError(new McuMgrBluetoothDisabledException());
                            break;
                        }
                        case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION: {
                            callback.onError(new McuMgrInsufficientAuthenticationException());
                            break;
                        }
                        default: {
                            callback.onError(new McuMgrException(GattError.parseConnectionError(status)));
                            break;
                        }
                    }
                })
                .enqueue();
    }

    @Override
    public void release() {
        cancelQueue();
        disconnect().enqueue();
    }

    /**
     * Requests the given connection priority. On Android, the connection priority is the
     * equivalent of connection parameters. Acceptable values are:
     * <ol>
     * <li>{@link BluetoothGatt#CONNECTION_PRIORITY_HIGH}
     * - Interval: 11.25 -15 ms, latency: 0, supervision timeout: 20 sec,</li>
     * <li>{@link BluetoothGatt#CONNECTION_PRIORITY_BALANCED}
     * - Interval: 30 - 50 ms, latency: 0, supervision timeout: 20 sec,</li>
     * <li>{@link BluetoothGatt#CONNECTION_PRIORITY_LOW_POWER}
     * - Interval: 100 - 125 ms, latency: 2, supervision timeout: 20 sec.</li>
     * </ol>
     * Calling this method with priority {@link BluetoothGatt#CONNECTION_PRIORITY_HIGH} may
     * improve file transfer speed.
     * <p>
     * Similarly to {@link #send(byte[], long, Class)}, this method will connect automatically
     * to the device if not connected.
     *
     * @param priority one of: {@link BluetoothGatt#CONNECTION_PRIORITY_HIGH},
     *                 {@link BluetoothGatt#CONNECTION_PRIORITY_BALANCED},
     *                 {@link BluetoothGatt#CONNECTION_PRIORITY_LOW_POWER}.
     */
    public void requestConnPriority(@ConnectionPriority final int priority) {
        connect(mDevice)
                .retry(3, 500)
                .done(device -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        McuMgrBleTransport.super.requestConnectionPriority(priority).enqueue();
                    } // else ignore... :(
                })
                .enqueue();
    }

    //*******************************************************************************************
    // Ble Manager Callbacks
    //*******************************************************************************************

    /**
     * This method is called when the target device has connected and the service discovery
     * is complete. The {@link BluetoothGatt#getService(UUID)} method can be used to obtain
     * required services.
     * <p>
     * This method allows expanding the Mcu Mgr BLE Transport object to support other services.
     * <p>
     * When implementing this method {@link #initializeAdditionalServices()} and
     * {@link #onServicesInvalidated()} should also be implemented.
     *
     * @param gatt The Bluetooth GATT object with services discovered.
     * @return True if any additional services were found; false otherwise.
     * @since 1.1
     */
    protected boolean isAdditionalServiceSupported(@NonNull BluetoothGatt gatt) {
        // By default no extra services are supported.
        return true;
    }

    /**
     * This method should initialize additional services. The SMP services requests have already
     * been enqueued.
     * @since 1.1
     */
    protected void initializeAdditionalServices() {
        // Empty default implementation.
    }

    /**
     * This method should nullify all services and characteristics of the device.
     * <p>
     * It's called when the services were invalidated and can no longer be used. Most probably the
     * device has disconnected, Service Changed indication was received, or
     * {@link BleManager#refreshDeviceCache()} request was executed, which has invalidated cached
     * services.
     * <p>
     * In version 1.6.0 this method was renamed from {@link #onServicesInvalidated()}, which is now
     * final. This is due to migration to Android BLE Library 2.6.0.
     * @since 1.6.0
     */
    protected void onAdditionalServicesInvalidated() {
        // Empty default implementation.
    }

    /** The bytes of {@link io.runtime.mcumgr.managers.DefaultManager#params()} command. */
    private final byte[] READ_MCU_MGR_PARAMS = new byte[] {
            0x00, // McuManager.OP_READ
            0x00, // Flags
            0x00, 0x01, // Len
            0x00, 0x00, // McuManager.GROUP_DEFAULT
            (byte) 0xFF, // Seq
            0x06, // DefaultManager.ID_MCUMGR_PARAMS
            (byte) 0xA0, // Empty map(0) - an empty CBOR may be required for some implementations,
                         // otherwise the request is ignored and no notification is replied.
    };

    // Determines whether the device supports the SMP Service
    @Override
    protected final boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
        BluetoothGattService smpService = gatt.getService(mUUIDConfig.getServiceUuid());
        if (smpService == null) {
            LOG.error("Device does not support SMP service");
            return false;
        }
        mSmpCharacteristicNotify = smpService.getCharacteristic(mUUIDConfig.getCharacteristicUuid());
        if (mSmpCharacteristicNotify == null) {
            LOG.error("Device does not support SMP characteristic");
            return false;
        } else {
            final int rxProperties = mSmpCharacteristicNotify.getProperties();
            boolean write = (rxProperties &
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0;
            boolean notify = (rxProperties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0;
            if (!write || !notify) {
                LOG.error("SMP characteristic does not support either write ({}) or notify ({})", write, notify);
                return false;
            }
        }

        // We must clone the characteristic in order to ensure no race conditions with
        // BluetoothGattCharacteristic's getValue() function when writing to and receiving
        // notifications from the same characteristic. Me must write to the clone and
        // receive from the original in order to ensure that the OS selects the correct
        // characteristic object from the service's list.
        //
        // More info:
        // https://stackoverflow.com/questions/38922639/how-could-i-achieve-maximum-thread-safety-with-a-read-write-ble-gatt-characteris
        mSmpCharacteristicWrite = cloneCharacteristic(mSmpCharacteristicNotify);
        return isAdditionalServiceSupported(gatt);
    }

    // Called once the connection has been established and services discovered. This method
    // adds a queue of requests necessary to set up the SMP service to begin writing
    // commands and receiving responses. Once these actions have completed onDeviceReady is
    // called.
    @Override
    protected final void initialize() {
        mSmpProtocol = new SmpProtocolSession(mHandler);

        // Request as high MTU as possible. As SMP protocol is fairly slow, requires a
        // notification for each packet sent, make sure the packets are as big as possible.
        // If Data Length Extension (DLE) is enabled, a single Link Layer packet can contain 251
        // bytes, which give 247 bytes for the payload. MTU equal to 498 allows sending max
        // data that fits into two LL packets of maximum size.
        // Maximum supported MTU is 517, but that would mean the third packet is small.
        // If the packet could not be sent in the same connection interval as 2 big packets,
        // that would waste the whole connection interval for just around 20 bytes.
        final boolean samsungS8Tab = Build.HARDWARE.equals("ums512_25c10");
        requestMtu(mInitialMtu)
                .with((device, mtu) -> {
                    // Note: When Samsung A8 Tab requests higher MTU it only requests RX MTU to be > 23,
                    //       leaving TX equal to 23 bytes. However, later it tries to send longer
                    //       packets, causing the target device to disconnect.
                    //       See: https://github.com/nordicsemi/Android-DFU-Library/pull/408
                    //       For that device set the max packet length unless the SMP reassembly is
                    //       supported (see MCU Params request below). Some features, like
                    //       DFU require sending longer packets.
                    mMaxPacketLength = Math.max(samsungS8Tab ? 20 : (mtu - 3), mMaxPacketLength);
                    if (samsungS8Tab) {
                        log(Log.WARN, "Samsung A8 Tab detected, setting TX MTU to 23");
                        overrideMtu(23);
                    }
                })
                .fail((device, status) -> {
                    if (getMinLogPriority() <= Log.WARN) {
                        log(Log.WARN, "Failed to negotiate MTU, disconnecting...");
                    }
                    disconnect().enqueue();
                })
                .enqueue();

        // Enable notifications on the clone of SMP characteristic. This is a hack that
        // allows having a single characteristic for writing and receiving, as Android API
        // would lead to race conditions and the outgoing data being overwritten by incoming
        // data.
        enableNotifications(mSmpCharacteristicNotify).enqueue();

        // Before we set the notification callback, let's first read the McuMgr params.
        // See: https://github.com/zephyrproject-rtos/zephyr/pull/44643
        // This allows the transport layer to send SMP packets longer than MTU-3.
        // The longer packets are split into MTU-3 chunks in Ble Library by using MtuSlitter.

        // Let's set one time notification callback...
        waitForNotification(mSmpCharacteristicNotify)
                // ...and send the hardcoded request.
                .trigger(
                        writeCharacteristic(mSmpCharacteristicWrite, READ_MCU_MGR_PARAMS,
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                )
                // The response should be received immediately.
                .timeout(1000 /* ms */)
                .merge(new SmpMerger())
                .with((device, data) -> {
                    final byte[] bytes = data.getValue();
                    // If the response is 14 bytes or shorter, that means the McuMgr Params
                    // request is not supported. Let's pretend nothing happened.
                    if (bytes != null && bytes.length > 14) {
                        try {
                            final McuMgrParamsResponse response = McuMgrResponse
                                    .buildResponse(McuMgrScheme.BLE, bytes, McuMgrParamsResponse.class);
                            if (getMinLogPriority() <= Log.INFO) {
                                log(Log.INFO, "SMP reassembly supported with buffer size: " + response.bufSize + " bytes and count: " + response.bufCount);
                            }
                            mMaxPacketLength = response.bufSize;
                        } catch (final Exception e) {
                            // Ignore
                        }
                    }
                    if (mMaxPacketLength < 70) {
                        // First Image Upload packet has a overhead of 69 bytes. To send at least 1
                        // byte of data, the buffer must be at least 70 bytes long.
                        if (getMinLogPriority() <= Log.WARN) {
                            log(Log.WARN, "Maximum packet size too small for some features i.e. DFU");
                        }
                    }
                })
                .enqueue();

        // Registered as a callback for all notifications from the SMP characteristic.
        // Forwards the merged data packets to the protocol layer to be matched to a request.
        setNotificationCallback(mSmpCharacteristicNotify)
                .merge(mSMPMerger)
                .with((device, data) -> {
                    final SmpProtocolSession session = mSmpProtocol;
                    final byte[] bytes = data.getValue();
                    if (bytes == null || session == null) {
                        return;
                    }
                    if (getMinLogPriority() <= Log.INFO) {
                        try {
                            log(Log.INFO, "Received "
                                    + McuMgrHeader.fromBytes(bytes) + " CBOR "
                                    + CBOR.toString(bytes, McuMgrHeader.HEADER_LENGTH));
                        } catch (Exception e) {
                            // Ignore
                        }
                    }
                    session.receive(bytes);
                });

        // Initialize additional services.
        initializeAdditionalServices();
    }

    // Called when the device has disconnected. This method nulls the services and
    // characteristic variables.
    @Override
    protected final void onServicesInvalidated() {
        if (mSmpProtocol != null) {
            mSmpProtocol.close(new McuMgrDisconnectedException());
        }
        mSmpProtocol = null;
        mSmpCharacteristicWrite = null;
        mSmpCharacteristicNotify = null;
        mMaxPacketLength = 0;
        onAdditionalServicesInvalidated();
        runOnCallbackThread(this::notifyDisconnected);
    }

    //*******************************************************************************************
    // Manager Connection Observers
    //*******************************************************************************************

    private final List<ConnectionObserver> mConnectionObservers = new LinkedList<>();

    @Override
    public synchronized void addObserver(@NonNull final ConnectionObserver observer) {
        mConnectionObservers.add(observer);
    }

    @Override
    public synchronized void removeObserver(@NonNull final ConnectionObserver observer) {
        mConnectionObservers.remove(observer);
    }

    private synchronized void notifyConnected() {
        for (ConnectionObserver o : mConnectionObservers) {
            o.onConnected();
        }
    }

    private synchronized void notifyDisconnected() {
        for (ConnectionObserver o : mConnectionObservers) {
            o.onDisconnected();
        }
    }

    //*******************************************************************************************
    // An Android hack to allow sending and receiving on the same characteristic.
    // The characteristic is cloned, and data sent and received do not share the same value,
    // which otherwise could lead to race conditions.
    //*******************************************************************************************

    private final static UUID CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static BluetoothGattDescriptor getNotifyCccd(@NonNull final BluetoothGattCharacteristic characteristic) {
        // Check characteristic property
        final int properties = characteristic.getProperties();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            return null;
        }
        return characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
    }

    @NonNull
    @SuppressLint("DiscouragedPrivateApi")
    private static BluetoothGattCharacteristic cloneCharacteristic(@NonNull BluetoothGattCharacteristic characteristic) {
        BluetoothGattCharacteristic clone;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) {
            // On older versions of android we have to use reflection in order
            // to set the instance ID and the service.
            clone = new BluetoothGattCharacteristic(
                    characteristic.getUuid(),
                    characteristic.getProperties(),
                    characteristic.getPermissions());
            try {
                Method initCharacteristic = characteristic.getClass()
                        .getDeclaredMethod("initCharacteristic", BluetoothGattService.class, UUID.class, int.class, int.class, int.class);
                initCharacteristic.setAccessible(true);
                initCharacteristic.invoke(clone,
                        characteristic.getService(),
                        characteristic.getUuid(),
                        characteristic.getInstanceId(),
                        characteristic.getProperties(),
                        characteristic.getPermissions()
                );
            } catch (Exception e) {
                LOG.error("SMP characteristic clone failed", e);
                clone = characteristic;
            }
        } else {
            // Newer versions of android have this bug fixed as long as a
            // handler is used in connectGatt().
            clone = characteristic;
        }
        return clone;
    }
}

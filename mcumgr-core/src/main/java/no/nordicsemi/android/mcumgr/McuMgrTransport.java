/*
 * Copyright (c) 2017-2018 Runtime Inc.
 * Copyright (c) Intellinium SAS, 2014-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.response.McuMgrResponse;

/**
 * An McuMgrTransport is tasked with sending requests to, and receiving responses from a device.
 * Transport implementations should conform to one of the {@link McuMgrScheme}s. Furthermore, the
 * McuManager does not maintain the state of the transporter, and so the transport must set-up and
 * tear-down connections on its own accord.
 * @noinspection ALL
 */
public interface McuMgrTransport {

    /**
     * Receives connection state changes independent of explicit calls to
     * {@link #connect} or {@link #release}.
     * <p>
     * To add or remove an observer, use {@link #addObserver} and
     * {@link #removeObserver} respectively.
     */
    interface ConnectionObserver {
        /**
         * Called when the connection to the device has been established.
         */
        void onConnected();

        /**
         * Called when the connection to the device has been lost.
         */
        void onDisconnected();
    }

    /**
     * Receives callbacks from an explicit call to {@link #connect}.
     */
    interface ConnectionCallback {
        /**
         * Called when connection attempt succeeds or is already open when {@link #connect} is
         * called.
         */
        void onConnected();

        /**
         * Called when the connection attempt has failed.
         *
         * @param t The connection failure reason.
         */
        void onError(@NotNull Throwable t);
    }

    /**
     * Receives callbacks from an explicit call to {@link #changeMode}.
     */
    interface ModeChangeCallback {
        /**
         * Called when mode change attempt succeeds.
         */
        void onModeChanged();

        /**
         * Called when the mode change attempt has failed.
         *
         * @param t The mode change failure reason.
         */
        void onError(@NotNull Throwable t);
    }

    /**
     * Gets the scheme for this transport (see {@link McuMgrScheme}).
     *
     * @return The transport's scheme.
     */
    @NotNull
    McuMgrScheme getScheme();

    /**
     * Send a synchronous Mcu Manager request. This method implementation should block until a
     * response has been received or a error has occurred.
     *
     * @param payload      the request packet data to send to the device.
     * @param timeout      the timeout for receiving a response for the packet, in milliseconds.
     * @param responseType the response type.
     * @param <T>          the response type.
     * @return The response.
     * @throws McuMgrException thrown on error. Set the cause of the error if caused by a different
     *                         type of exception.
     */
    @NotNull <T extends McuMgrResponse> T send(byte @NotNull [] payload,
                                               long timeout,
                                               @NotNull Class<T> responseType)
            throws McuMgrException;

    /**
     * Send an asynchronous Mcu Manager request. This method should not be blocked. When the
     * response has been received or an error occurs, the appropriate method of the callback should
     * be called.
     *
     * @param payload      the request packet data to send to the device.
     * @param timeout      the timeout for receiving a response for the packet, in milliseconds.
     * @param responseType the response type.
     * @param callback     the callback to call on response or error.
     * @param <T>          the response type.
     */
    <T extends McuMgrResponse> void send(byte @NotNull [] payload,
                                         long timeout,
                                         @NotNull Class<T> responseType,
                                         @NotNull McuMgrCallback<T> callback);

    /**
     * Connect the transporter to the remote device. The callback must be called if supplied, even
     * if the transport connection is already opened.
     *
     * @param callback An optional callback to receive the result of the connection attempt.
     */
    void connect(@Nullable ConnectionCallback callback);

    /**
     * Changes the transport mode.
     *
     * The change is transport-specific and may not be supported by all transport schemes.
     *
     * An example may be to connect to the same device using a different Device Address.
     *
     * @param name the mode name.
     * @param callback An optional callback to receive the result of the mode change.
     * @return true if the mode change was initiated successfully, false otherwise.
     */
    boolean changeMode(@NotNull String name, @Nullable ModeChangeCallback callback);

    /**
     * Releases the transport connection. When the connection is already closed this method does
     * nothing.
     */
    void release();

    /**
     * Adds the connection observer. An observer will be notified whenever the connected device
     * gets connected or disconnected.
     *
     * @param observer the observer.
     */
    void addObserver(@NotNull ConnectionObserver observer);

    /**
     * Removes previously registered observer.
     *
     * @param observer the observer.
     */
    void removeObserver(@NotNull ConnectionObserver observer);
}

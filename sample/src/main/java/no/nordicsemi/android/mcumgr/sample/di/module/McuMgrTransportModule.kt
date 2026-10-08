/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.di.module;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;

import java.util.Objects;

import dagger.Module;
import dagger.Provides;
import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.log.Category;
import no.nordicsemi.android.mcumgr.sample.di.McuMgrScope;
import no.nordicsemi.android.mcumgr.sample.observable.ObservableMcuMgrBleTransport;
import no.nordicsemi.kotlin.ble.client.android.CentralManager;
import no.nordicsemi.kotlin.ble.client.android.Peripheral;
import no.nordicsemi.kotlin.log.Log;

@Module
public class McuMgrTransportModule {

    @Provides
    @McuMgrScope
    @NonNull
    static McuMgrTransport provideMcuMgrTransport(@NonNull final Context context,
                                                  @NonNull final BluetoothDevice device,
                                                  @NonNull final HandlerThread handlerThread,
                                                  @NonNull final Log.Sink<Category> logger) {
        final Handler handler = new Handler(handlerThread.getLooper());
        final ObservableMcuMgrBleTransport transport = new ObservableMcuMgrBleTransport(context, device, handler);
        transport.setLogger(logger);
        transport.setOnReleasedCallback(handlerThread::quitSafely);
        // This is where we stopped lazy connection.
        // Before, the device started connection on user action (button pressed).
        // Now, it starts immediately when the transport is created.
        transport.connect((McuMgrTransport.ConnectionCallback) null);
        return transport;
    }

    @Provides
    @McuMgrScope
    @NonNull
    static HandlerThread provideTransportHandlerThread() {
        final HandlerThread handlerThread = new HandlerThread("McuMgrTransport");
        handlerThread.start(); // The handler thread is stopped in MainViewModel#onCleard().
        return handlerThread;
    }

    @Provides
    @McuMgrScope
    @NonNull
    static Peripheral providePeripheral(@NonNull final CentralManager centralManager,
                                        @NonNull final BluetoothDevice device) {
        return Objects.requireNonNull(centralManager.getPeripheralById(device.getAddress()));
    }
}

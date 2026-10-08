/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.di.component;

import android.bluetooth.BluetoothDevice;
import android.net.Uri;

import androidx.annotation.Nullable;

import dagger.BindsInstance;
import dagger.Subcomponent;
import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.sample.MainActivity;
import no.nordicsemi.android.mcumgr.sample.application.Dagger2Application;
import no.nordicsemi.android.mcumgr.sample.di.McuMgrScope;
import no.nordicsemi.android.mcumgr.sample.di.module.CentralManagerModule;
import no.nordicsemi.android.mcumgr.sample.di.module.EnvironmentModule;
import no.nordicsemi.android.mcumgr.sample.di.module.McuMgrActivitiesModule;
import no.nordicsemi.android.mcumgr.sample.di.module.McuMgrFragmentBuildersModule;
import no.nordicsemi.android.mcumgr.sample.di.module.McuMgrManagerModule;
import no.nordicsemi.android.mcumgr.sample.di.module.McuMgrTransportModule;
import no.nordicsemi.android.mcumgr.sample.di.module.McuMgrViewModelModule;
import no.nordicsemi.android.mcumgr.sample.di.module.ObservabilityModule;

@Subcomponent(modules = {
        McuMgrActivitiesModule.class,
        McuMgrFragmentBuildersModule.class,
        McuMgrViewModelModule.class,
        McuMgrTransportModule.class,
        CentralManagerModule.class,
        EnvironmentModule.class,
        ObservabilityModule.class,
        McuMgrManagerModule.class,
})
@McuMgrScope
public interface McuMgrSubComponent {
    @Subcomponent.Builder
    interface Builder {
        /**
         * Sets the connection target.
         *
         * @param device teh target Bluetooth device.
         * @return The builder instance.
         */
        @BindsInstance
        Builder target(final BluetoothDevice device);

        @BindsInstance
        Builder logSessionUri(final @Nullable Uri uri);

        McuMgrSubComponent build();
    }

    /**
     * Adds the {@link MainActivity} to the
     * {@link Dagger2Application#androidInjector()}.
     * The {@link MainActivity} requires the
     * {@link McuMgrTransport} to be instantiated before injecting.
     *
     * @param application the application.
     */
    void update(final Dagger2Application application);
}

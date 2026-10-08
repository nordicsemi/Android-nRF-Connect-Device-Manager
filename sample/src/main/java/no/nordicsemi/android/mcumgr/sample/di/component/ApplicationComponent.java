/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.di.component;

import javax.inject.Singleton;

import dagger.Component;
import dagger.android.AndroidInjectionModule;
import dagger.android.support.AndroidSupportInjectionModule;
import no.nordicsemi.android.mcumgr.sample.application.Dagger2Application;
import no.nordicsemi.android.mcumgr.sample.di.module.ActivitiesModule;
import no.nordicsemi.android.mcumgr.sample.di.module.ContextModule;
import no.nordicsemi.android.mcumgr.sample.di.module.FragmentsModule;
import no.nordicsemi.android.mcumgr.sample.di.module.McuMgrLoggerModule;
import no.nordicsemi.android.mcumgr.sample.di.module.McuMgrModule;
import no.nordicsemi.android.mcumgr.sample.di.module.ViewModelModule;

/**
 * Check <a href="https://github.com/googlesamples/android-architecture-components">Architecture Components</a>
 * for more details.
 */
@Component(modules = {
        AndroidInjectionModule.class,
        AndroidSupportInjectionModule.class,
        ContextModule.class,
        ActivitiesModule.class,
        FragmentsModule.class,
        ViewModelModule.class,
        McuMgrModule.class,
        McuMgrLoggerModule.class,
})
@Singleton
public interface ApplicationComponent {
    @Component.Builder
    interface Builder {
        Builder contextModule(final ContextModule module);

        ApplicationComponent build();
    }

    void inject(final Dagger2Application application);
}


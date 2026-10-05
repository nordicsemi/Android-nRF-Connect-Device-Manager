/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.di.module;

import android.app.Application;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import no.nordicsemi.android.mcumgr.sample.di.component.ViewModelSubComponent;
import no.nordicsemi.android.mcumgr.sample.viewmodel.ViewModelFactory;

@Module(subcomponents = ViewModelSubComponent.class)
public class ViewModelModule {

    @Provides
    @Singleton
    static ViewModelFactory provideViewModelFactory(final Application application,
                                                    final ViewModelSubComponent.Builder viewModelSubComponent) {
        return new ViewModelFactory(application, viewModelSubComponent.build());
    }
}

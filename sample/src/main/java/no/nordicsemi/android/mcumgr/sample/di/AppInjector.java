/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.di;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import dagger.android.AndroidInjection;
import dagger.android.HasAndroidInjector;
import dagger.android.support.AndroidSupportInjection;
import no.nordicsemi.android.mcumgr.sample.application.Dagger2Application;
import no.nordicsemi.android.mcumgr.sample.di.component.DaggerApplicationComponent;
import no.nordicsemi.android.mcumgr.sample.di.module.ContextModule;

/**
 * Helper class to automatically inject fragments if they implement {@link Injectable}.
 */
public class AppInjector {
    private AppInjector() {
    }

    public static void init(@NonNull final Dagger2Application application) {
        DaggerApplicationComponent.builder()
                .contextModule(new ContextModule(application))
                .build()
                .inject(application);

        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull final Activity activity,
                                          @Nullable final Bundle savedInstanceState) {
                handleActivity(activity);
            }

            @Override
            public void onActivityStarted(@NonNull final Activity activity) {
                // empty
            }

            @Override
            public void onActivityResumed(@NonNull final Activity activity) {
                // empty
            }

            @Override
            public void onActivityPaused(@NonNull final Activity activity) {
                // empty
            }

            @Override
            public void onActivityStopped(@NonNull final Activity activity) {
                // empty
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull final Activity activity,
                                                    @NonNull final Bundle outState) {
                // empty
            }

            @Override
            public void onActivityDestroyed(@NonNull final Activity activity) {
                // empty
            }
        });
    }

    private static void handleActivity(@NonNull final Activity activity) {
        // An Activity that finishes before it is created, see MainActivity, is not injected.
        if (activity.isFinishing()) {
            return;
        }
        if (activity instanceof Injectable || activity instanceof HasAndroidInjector) {
            AndroidInjection.inject(activity);
        }
        if (activity instanceof FragmentActivity) {
            ((AppCompatActivity) activity).getSupportFragmentManager()
                    .registerFragmentLifecycleCallbacks(
                            new FragmentManager.FragmentLifecycleCallbacks() {
                                @Override
                                public void onFragmentPreCreated(@NonNull final FragmentManager fm,
                                                                 @NonNull final Fragment f,
                                                                 final Bundle savedInstanceState) {
                                    if (f instanceof Injectable) {
                                        AndroidSupportInjection.inject(f);
                                    }
                                }
                            }, true);
        }
    }
}

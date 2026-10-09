/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.di.module;

import dagger.Module;
import dagger.Provides;
import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager;
import no.nordicsemi.android.mcumgr.dfu.suit.SUITUpgradeManager;
import no.nordicsemi.android.mcumgr.managers.BasicManager;
import no.nordicsemi.android.mcumgr.managers.DefaultManager;
import no.nordicsemi.android.mcumgr.managers.FsManager;
import no.nordicsemi.android.mcumgr.managers.ImageManager;
import no.nordicsemi.android.mcumgr.managers.LogManager;
import no.nordicsemi.android.mcumgr.managers.SUITManager;
import no.nordicsemi.android.mcumgr.managers.SettingsManager;
import no.nordicsemi.android.mcumgr.managers.ShellManager;
import no.nordicsemi.android.mcumgr.managers.StatsManager;
import no.nordicsemi.android.mcumgr.sample.di.McuMgrScope;
import no.nordicsemi.android.ota.mcumgr.MemfaultManager;
import no.nordicsemi.kotlin.log.Log;

@Module
public class McuMgrManagerModule {

    @Provides
    @McuMgrScope
    static SettingsManager provideConfigManager(final McuMgrTransport transport,
                                                final Log.Sink<Log.Category> logger) {
        final SettingsManager manager = new SettingsManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static DefaultManager provideDefaultManager(final McuMgrTransport transport,
                                                final Log.Sink<Log.Category> logger) {
        final DefaultManager manager = new DefaultManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static FsManager provideFsManager(final McuMgrTransport transport,
                                      final Log.Sink<Log.Category> logger) {
        final FsManager manager = new FsManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static LogManager provideLogManager(final McuMgrTransport transport,
                                        final Log.Sink<Log.Category> logger) {
        final LogManager manager = new LogManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static ImageManager provideImageManager(final McuMgrTransport transport,
                                            final Log.Sink<Log.Category> logger) {
        final ImageManager manager = new ImageManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static BasicManager provideBasicManager(final McuMgrTransport transport,
                                            final Log.Sink<Log.Category> logger) {
        final BasicManager manager = new BasicManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static StatsManager provideStatsManager(final McuMgrTransport transport,
                                            final Log.Sink<Log.Category> logger) {
        final StatsManager manager = new StatsManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static ShellManager provideShellManager(final McuMgrTransport transport,
                                            final Log.Sink<Log.Category> logger) {
        final ShellManager manager = new ShellManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static FirmwareUpgradeManager provideFirmwareUpgradeManager(final McuMgrTransport transport,
                                                                final Log.Sink<Log.Category> logger) {
        final FirmwareUpgradeManager manager = new FirmwareUpgradeManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static SUITManager provideSUITManager(final McuMgrTransport transport,
                                          final Log.Sink<Log.Category> logger) {
        final SUITManager manager = new SUITManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static SUITUpgradeManager provideSUITUpgradeManager(final McuMgrTransport transport,
                                                        final Log.Sink<Log.Category> logger) {
        final SUITUpgradeManager manager = new SUITUpgradeManager(transport);
        manager.setLogger(logger);
        return manager;
    }

    @Provides
    @McuMgrScope
    static MemfaultManager provideMemfaultManager(final McuMgrTransport transport,
                                                  final Log.Sink<Log.Category> logger) {
        final MemfaultManager manager = new MemfaultManager(transport);
        manager.setLogger(logger);
        return manager;
    }
}

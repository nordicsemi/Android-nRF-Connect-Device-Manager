/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.di.module

import dagger.Module
import dagger.Provides
import no.nordicsemi.android.mcumgr.sample.log.LogController
import no.nordicsemi.kotlin.log.Log
import no.nordicsemi.kotlin.log.timber.Timber
import javax.inject.Singleton

@Module
class McuMgrLoggerModule {

    /**
     * The switch the view models use to quieten the Bluetooth LE stack during a transfer.
     */
    @Provides
    @Singleton
    fun providesLogController(): LogController = LogController()

    /**
     * The sink receiving log entries from the Mcu Manager library, that is from the managers,
     * the firmware upgrade and the transport.
     *
     * Entries are forwarded to Timber, which the application forwards to Logcat and, when
     * a log session is given, to the nRF Logger.
     */
    @Provides
    @Singleton
    fun providesMcuMgrLogger(
        logController: LogController,
    ): Log.Sink<Log.Category> = Log.Sink.Timber { _, level ->
        logController.verbose || level >= Log.Level.WARN
    }
}

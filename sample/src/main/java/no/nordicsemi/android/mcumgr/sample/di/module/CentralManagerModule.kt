package no.nordicsemi.android.mcumgr.sample.di.module

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import no.nordicsemi.android.mcumgr.sample.di.McuMgrScope
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment
import no.nordicsemi.kotlin.log.Log

@Module
class CentralManagerModule {

    @Provides
    @McuMgrScope
    fun providesIoScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Provides
    @McuMgrScope
    fun providesEnvironment(context: Context): NativeAndroidEnvironment =
        NativeAndroidEnvironment.getInstance(context, isNeverForLocationFlagSet = true)

    @Provides
    @McuMgrScope
    fun providesCentralManager(
        environment: NativeAndroidEnvironment,
        scope: CoroutineScope,
        logger: @JvmSuppressWildcards Log.Sink<Log.Category>,
    ): CentralManager = CentralManager.native(environment, scope)
        .also { it.logger = logger }
}

@Module
abstract class EnvironmentModule {
    @Binds
    abstract fun providesAndroidEnvironment(nativeEnvironment: NativeAndroidEnvironment): AndroidEnvironment
}

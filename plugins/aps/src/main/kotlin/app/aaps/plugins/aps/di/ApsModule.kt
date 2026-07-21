package app.aaps.plugins.aps.di

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.autotune.Autotune
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.autotune.AutotunePlugin
import app.aaps.plugins.aps.loop.LoopPlugin
import dagger.Binds
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module(
    includes = [
        AutotuneModule::class,
        LoopModule::class,
        ApsModule.Bindings::class
    ]
)

@Suppress("unused")
abstract class ApsModule {

    @ContributesAndroidInjector abstract fun contributesOpenAPSFragment(): OpenAPSFragment

    @Module
    interface Bindings {

        @Binds fun bindLoop(loopPlugin: LoopPlugin): Loop
        @Binds fun bindAutotune(autotunePlugin: AutotunePlugin): Autotune

        // Capability-Matrix A1 (R9-G1/R10-G1): EIN Coordinator hinter BEIDEN Core-Ports —
        // read-only Provider (APS/Trigger/Export) + Writer-Invalidator (ActionSetIobTH).
        @Binds fun bindEffectiveAutoIsfSettingsProvider(coordinator: app.aaps.plugins.aps.iobaction.AutoIsfValueLeaseCoordinator): app.aaps.core.interfaces.aps.EffectiveAutoIsfSettingsProvider
        @Binds fun bindAutoIsfValueLeaseInvalidator(coordinator: app.aaps.plugins.aps.iobaction.AutoIsfValueLeaseCoordinator): app.aaps.core.interfaces.aps.AutoIsfValueLeaseInvalidator
    }
}
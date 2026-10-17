package app.aaps.di.metro

import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.iobaction.LocalCommandService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides

/**
 * Fork (IOB-Action viewer): Android builds the local command channel service itself, so it injects its
 * own fields, like every other service (see AppReceiversBindings). Its own file, so an upstream merge
 * of AppReceiversBindings never conflicts with it.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object IobActionChannelBindings {

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(LocalCommandService::class)
    fun bindLocalCommandService(injector: MembersInjector<LocalCommandService>): MembersInjector<*> = injector
}

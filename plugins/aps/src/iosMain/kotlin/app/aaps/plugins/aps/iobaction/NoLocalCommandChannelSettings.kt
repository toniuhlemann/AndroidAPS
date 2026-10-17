package app.aaps.plugins.aps.iobaction

import app.aaps.core.ui.compose.ComposeScreenContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** iOS has no local command channel, so there is no settings screen for it either. */
@ContributesBinding(AppScope::class)
@Inject
class NoLocalCommandChannelSettings : LocalCommandChannelSettings {

    override fun screen(): ComposeScreenContent? = null
}

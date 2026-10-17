package app.aaps.plugins.aps.iobaction

import app.aaps.core.ui.compose.ComposeScreenContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** The desktop has no local command channel (it is an Android service), so there is no settings screen for it either. */
@ContributesBinding(AppScope::class)
@Inject
class DesktopLocalCommandChannelSettings : LocalCommandChannelSettings {

    override fun screen(): ComposeScreenContent? = null
}

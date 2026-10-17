package app.aaps.plugins.aps.iobaction

import app.aaps.core.ui.compose.ComposeScreenContent

/**
 * Fork (IOB-Action viewer): the settings screen of the local command channel - the master switch,
 * one switch per capability, validate only, the shared secret and the trusted viewer signature.
 *
 * The channel is an Android service (its own SharedPreferences namespace, the secret under
 * noBackupFilesDir, the signing digest of the installed viewer), so only Android has a screen. The
 * other targets return null and the entry is not shown at all - visibly absent rather than a page
 * whose switches do nothing.
 */
interface LocalCommandChannelSettings {

    /** The screen, or null where the channel does not exist. */
    fun screen(): ComposeScreenContent?
}

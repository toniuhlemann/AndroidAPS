package app.aaps.core.ui.compose.preference

import androidx.compose.runtime.compositionLocalOf
import app.aaps.core.keys.interfaces.VisibilityContext
import app.aaps.core.ui.compose.ComposeScreenContent

/**
 * CompositionLocal for providing PreferenceVisibilityContext to preference composables.
 * Used by AdaptivePreferenceList and PreferenceContentExtensions to evaluate visibility conditions.
 */
val LocalVisibilityContext = compositionLocalOf<VisibilityContext?> { null }

/**
 * CompositionLocal for password verification function.
 * Used by password preference dialogs to verify entered passwords.
 * Signature: (enteredPassword: String, storedHash: String) -> Boolean
 */
val LocalCheckPassword = compositionLocalOf<((String, String) -> Boolean)?> { null }

/**
 * CompositionLocal for password hashing function.
 * Used by password preference dialogs to hash passwords before storing.
 * Signature: (password: String) -> String
 */
val LocalHashPassword = compositionLocalOf<((String) -> String)?> { null }

/**
 * CompositionLocal for clearing the stored unattended-export password.
 * Invoked when the master password changes so a stale export password can't outlive the change.
 */
val LocalClearExportPasswordStore = compositionLocalOf<(() -> Unit)?> { null }

/**
 * CompositionLocal for highlighting a specific preference key.
 * Used when navigating from search to highlight the found preference.
 */
val LocalHighlightKey = compositionLocalOf<String?> { null }

/**
 * CompositionLocal for navigating to an inline Compose screen from a preference.
 * Used by IntentPreferenceKey with composeScreen attached via withCompose().
 * The lambda receives a composable content that takes an onBack callback.
 */
val LocalNavigateToCompose = compositionLocalOf<((ComposeScreenContent) -> Unit)?> { null }

/**
 * CompositionLocal for opening a preference screen as a page of its own, by its key
 * ([PreferenceSubScreenDef.pages]). The second argument is the key of a value to bring into view and to tint on
 * that page, or null. Provided by the navigation of the app; where it is missing, sub-screens unfold as sections.
 */
val LocalPreferenceNavigator = compositionLocalOf<((String, String?) -> Unit)?> { null }

/**
 * True on a page ([PreferencePageScreen]): a value row shows its name left and its value right, with at most one
 * line below. Everywhere else a row keeps its usual shape.
 */
val LocalCompactPreferenceRows = compositionLocalOf { false }

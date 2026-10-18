package app.aaps.plugins.automation.states

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Preferences that keep only strings, in a map the test can write too - the way a settings import writes
 * the store behind the service's back.
 */
internal class StatePreferences {

    val stored = mutableMapOf<String, String>()

    val preferences: Preferences = mock<Preferences>().also { p ->
        whenever(p.get(any<StringNonPreferenceKey>())).thenAnswer { call ->
            val key = call.getArgument<StringNonPreferenceKey>(0)
            stored[key.key] ?: key.defaultValue
        }
        doAnswer { call ->
            stored[call.getArgument<StringNonPreferenceKey>(0).key] = call.getArgument(1)
            null
        }.whenever(p).put(any<StringNonPreferenceKey>(), any<String>())
    }
}

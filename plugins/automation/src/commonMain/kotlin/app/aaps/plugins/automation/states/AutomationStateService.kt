package app.aaps.plugins.automation.states

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.automation.AutomationStateSnapshot
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.Json

/**
 * Named automation states with a fixed list of allowed values (autoISF 3.2.x, Tobias/ga-zelle).
 *
 * Automations set them with an action and test them with a trigger; the autoISF activity monitor reads
 * the "Sleeping" state. The states and their allowed values are stored as JSON in two preferences, under
 * the same names as the automation-state plugin of the 3.4 fork, so existing states carry over.
 *
 * In 3.4 this was a plugin of its own with a tab. In v4 the service lives here and
 * [AutomationStatePlugin] is the tab; the tab watches the two preferences.
 *
 * Every call reads the preferences again instead of keeping a copy from the start. A settings import
 * no longer restarts the app, so a copy would keep showing the old states after an import, and the next
 * write would put the old states back over the imported ones.
 */
@ContributesBinding(AppScope::class, binding = binding<AutomationStateInterface>())
@SingleIn(AppScope::class)
@Inject
class AutomationStateService(
    private val preferences: Preferences
) : AutomationStateInterface {

    /** Was `@Synchronized` on the JVM in the fork. A read and the write after it stay one step. */
    private val lock = AapsLock()

    private fun storedStates(): HashMap<String, String> =
        runCatching { Json.decodeFromString<HashMap<String, String>>(preferences.get(StringNonKey.AutomationCurrentStates)) }.getOrElse { HashMap() }

    private fun storedValues(): HashMap<String, List<String>> =
        runCatching { Json.decodeFromString<HashMap<String, List<String>>>(preferences.get(StringNonKey.AutomationStateValues)) }.getOrElse { HashMap() }

    private fun storeStates(states: HashMap<String, String>) =
        preferences.put(StringNonKey.AutomationCurrentStates, Json.encodeToString(states))

    private fun storeValues(values: HashMap<String, List<String>>) =
        preferences.put(StringNonKey.AutomationStateValues, Json.encodeToString(values))

    override fun inState(stateName: String, state: String): Boolean = lock.withLock {
        storedStates()[stateName.trim()]?.let { it == state.trim() } ?: false
    }

    override fun setState(stateName: String, state: String) = lock.withLock {
        val trimmedName = stateName.trim()
        val trimmedState = state.trim()
        val values = storedValues()
        // Validate that the state value is in the allowed list
        require(values.containsKey(trimmedName)) { "Invalid state name: $trimmedName" }
        require(values[trimmedName]!!.contains(trimmedState)) { "Invalid state value: $trimmedState" }
        val states = storedStates()
        states[trimmedName] = trimmedState
        storeStates(states)
    }

    override fun getState(stateName: String): String = lock.withLock {
        storedStates()[stateName.trim()] ?: ""
    }

    override fun getAllStates(): List<Pair<String, String>> = lock.withLock {
        val states = storedStates()
        storedValues().keys.map { stateName -> Pair(stateName, states[stateName] ?: "") }
    }

    fun clearStates() = lock.withLock {
        preferences.put(StringNonKey.AutomationCurrentStates, "{}")
    }

    override fun getStateValues(stateName: String): List<String> = lock.withLock {
        storedValues()[stateName.trim()] ?: emptyList()
    }

    override fun setStateValues(stateName: String, values: List<String>) = lock.withLock {
        val trimmedName = stateName.trim()
        val trimmedValues = values.map { it.trim() }
        // If the current value is not in the new list, the current state is cleared
        val states = storedStates()
        val currentState = states[trimmedName]
        if (currentState != null && !trimmedValues.contains(currentState)) {
            states.remove(trimmedName)
            storeStates(states)
        }
        val allValues = storedValues()
        allValues[trimmedName] = trimmedValues
        storeValues(allValues)
    }

    override fun getStateSnapshot(stateName: String): AutomationStateSnapshot = lock.withLock {
        val trimmedName = stateName.trim()
        if (!storedValues().containsKey(trimmedName)) AutomationStateSnapshot(known = false, value = null)
        else storedStates()[trimmedName].let { AutomationStateSnapshot(known = it != null, value = it) }
    }

    override fun hasStateValues(stateName: String): Boolean = lock.withLock {
        storedValues().containsKey(stateName.trim())
    }

    override fun deleteState(stateName: String) = lock.withLock {
        val trimmedName = stateName.trim()
        val states = storedStates()
        val values = storedValues()
        states.remove(trimmedName)
        values.remove(trimmedName)
        storeStates(states)
        storeValues(values)
    }
}

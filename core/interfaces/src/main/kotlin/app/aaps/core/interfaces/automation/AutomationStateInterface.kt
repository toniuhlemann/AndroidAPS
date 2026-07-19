package app.aaps.core.interfaces.automation

/**
 * Interface for the AutomationStateService to allow independent compilation
 * of automation and automation-state plugins
 */
interface AutomationStateInterface {
    /**
     * Check if a state has a specific value
     * @param stateName The name of the state to check
     * @param state The value to check for
     * @return true if the state has the specified value, false otherwise
     */
    fun inState(stateName: String, state: String): Boolean

    /**
     * Set a state to a specific value
     * @param stateName The name of the state to set
     * @param state The value to set
     * @throws IllegalStateException if the state doesn't exist
     * @throws IllegalStateException if the value is not valid for the state
     */
    fun setState(stateName: String, state: String)

    /**
     * Get current value of a specific state
     * @param stateName The name of the state
     * @throws IllegalStateException if the state doesn't exist
     */
    fun getState(stateName: String): String

    /**
     * Get all states and their current values
     * @return List of pairs containing state names and their values
     */
    fun getAllStates(): List<Pair<String, String>>
    
    /**
     * Get the possible values for a state
     * @param stateName The name of the state
     * @return List of possibe values for the state
     */
    fun getStateValues(stateName: String): List<String>
    
    /**
     * Set the possible values for a state
     * @param stateName The name of the state
     * @param values List of possible values for the state
     */
    fun setStateValues(stateName: String, values: List<String>)
    
    /**
     * Check if a state exists
     * @param stateName The name of the state to check
     * @return true if the state exists, false otherwise
     */
    fun hasStateValues(stateName: String): Boolean

    /**
     * Delete a state and its values
     * @param stateName The name of the state to delete
     */
    fun deleteState(stateName: String)

    /**
     * DynamicMealIobTH shadow (spec v1.3 / Bauauflage B): read state DEFINITION and current
     * VALUE atomically under one lock. inState()/getState() as two independent reads cannot
     * distinguish "state missing" (unknown) from "false", and can race a concurrent write.
     * Read-only; existing triggers/actions keep their behavior.
     */
    fun getStateSnapshot(stateName: String): AutomationStateSnapshot
}

/** known=false means the state is not defined (unknown != false). */
data class AutomationStateSnapshot(
    val known: Boolean,
    val value: String?,
)
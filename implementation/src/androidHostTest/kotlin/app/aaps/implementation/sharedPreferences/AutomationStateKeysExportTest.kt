package app.aaps.implementation.sharedPreferences

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.StringNonKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Fork: the automation states and their allowed values go out with a settings export. The export keeps
 * only the keys [PreferencesImpl.isExportableKey] accepts, so this is the gate both keys have to pass.
 * The import side is in `PreferenceImportApplierTest`.
 */
class AutomationStateKeysExportTest {

    private val preferences = PreferencesImpl(
        sp = mock<KeyValueStore>(),
        profileUtil = { mock<ProfileUtil>() },
        profileFunction = { mock<ProfileFunction>() },
        hardLimits = { mock<HardLimits>() },
        persistenceLayer = mock<PersistenceLayer>(),
        config = mock<Config>(),
        dateUtil = mock<DateUtil>()
    )

    @Test fun `the automation states and their allowed values are exported`() {
        assertThat(preferences.isExportableKey(StringNonKey.AutomationCurrentStates.key)).isTrue()
        assertThat(preferences.isExportableKey(StringNonKey.AutomationStateValues.key)).isTrue()
    }

    /** Files and phones from the 3.4 fork carry these names. */
    @Test fun `the stored names are the ones of the fork`() {
        assertThat(StringNonKey.AutomationCurrentStates.key).isEqualTo("automation_state_service")
        assertThat(StringNonKey.AutomationStateValues.key).isEqualTo("automation_state_values")
    }
}

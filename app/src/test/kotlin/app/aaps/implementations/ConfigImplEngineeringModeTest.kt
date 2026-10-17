package app.aaps.implementations

import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.di.ExternalOptionsOverride
import app.aaps.plugins.constraints.safety.SafetyPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

/**
 * Fork: engineering mode is always on (Toni: "ich moechte alles auswaehlen koennen").
 *
 * The fork's version name counts as a dev build in v4 (`isDev`: a hyphen or letters, and no -beta or -rc). Without
 * engineering mode SafetyPlugin refuses the closed loop on a dev build, and Autotune and the engineering-only
 * settings stay hidden. The production phone has no `engineering_mode` file, so the mode must not depend on one.
 * This runs the real [ConfigImpl] with this build's version name and no file at all.
 */
class ConfigImplEngineeringModeTest : TestBaseWithProfile() {

    @Mock lateinit var fileListProvider: FileListProvider
    @Mock lateinit var persistenceLayer: PersistenceLayer

    private lateinit var appConfig: ConfigImpl

    @BeforeEach
    fun setup() {
        // No extra directory, so no option file can be found.
        whenever(fileListProvider.ensureExtraDirExists()).thenReturn(null)
        appConfig = ConfigImpl({ fileListProvider }, ExternalOptionsOverride.NONE)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)
    }

    @Test
    fun `this version counts as a dev build - the reason the mode must be on`() {
        assertThat(appConfig.isDev()).isTrue()
    }

    @Test
    fun `engineering mode is on without an engineering_mode file`() {
        assertThat(appConfig.isEnabled(ExternalOptions.ENGINEERING_MODE)).isFalse()
        assertThat(appConfig.isEngineeringMode()).isTrue()
        assertThat(appConfig.isEngineeringModeOrRelease()).isTrue()
    }

    @Test
    fun `the safety plugin allows the closed loop with this version`() = runTest {
        val safety = SafetyPlugin(
            aapsLogger, rh, preferences, constraintsChecker, activePlugin, hardLimits, appConfig, persistenceLayer,
            dateUtil, notificationManager, decimalFormatter
        )

        val allowed = safety.isClosedLoopAllowed(ConstraintObject(true, aapsLogger))

        assertThat(allowed.value()).isTrue()
    }
}

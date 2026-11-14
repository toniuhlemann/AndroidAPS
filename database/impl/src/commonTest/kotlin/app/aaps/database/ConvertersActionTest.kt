package app.aaps.database

import app.aaps.database.entities.UserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `action` column of the user entries holds the entry name. A name this build does not know - written by a
 * fork, an older or a newer version - must read as `UNKNOWN`. With `valueOf` it threw, and one such row made every
 * read of the user entries fail.
 *
 * In `commonTest`, so this runs through Kotlin/Native as well as the JVM.
 */
class ConvertersActionTest {

    private val converters = Converters()

    @Test
    fun `current names read back unchanged`() {
        UserEntry.Action.entries.forEach { entry ->
            assertEquals(entry, converters.toAction(converters.fromAction(entry)), entry.name)
        }
    }

    @Test
    fun `an unknown name reads as UNKNOWN and does not throw`() {
        assertEquals(UserEntry.Action.UNKNOWN, converters.toAction("SOMETHING_FROM_ANOTHER_BUILD"))
        assertEquals(UserEntry.Action.UNKNOWN, converters.toAction(""))
        assertNull(converters.toAction(null))
    }

    @Test
    fun `the name has to match exactly`() {
        assertEquals(UserEntry.Action.UNKNOWN, converters.toAction("bolus"))
        assertEquals(UserEntry.Action.BOLUS, converters.toAction("BOLUS"))
    }
}

package app.aaps.ui.compose.quickLaunch

import app.aaps.core.ui.compose.fuse.FuseMealButtonState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fork (FUSE): the toolbar must show at the meal button whether a meal runs, not only in the long-press tooltip. */
class FuseMealMarkTest {

    @Test
    fun aRunningMarkerShowsADot() {
        assertEquals(FuseMealMark.DOT, fuseMealMark(FuseMealButtonState.ACTIVE))
        assertEquals(FuseMealMark.DOT, fuseMealMark(FuseMealButtonState.ACTIVE_NO_ACCOUNT))
    }

    @Test
    fun anOpenMealWindowShowsARing() {
        assertEquals(FuseMealMark.RING, fuseMealMark(FuseMealButtonState.NEW_MEAL))
    }

    @Test
    fun noMealAndOtherButtonsShowNoMark() {
        assertEquals(FuseMealMark.NONE, fuseMealMark(FuseMealButtonState.IDLE))
        assertEquals(FuseMealMark.NONE, fuseMealMark(null))
    }
}

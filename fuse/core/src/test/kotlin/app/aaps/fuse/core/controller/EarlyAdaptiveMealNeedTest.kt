package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Frueher adaptiver MEAL-Bedarf (H8). Konsolidierung 2.0: H8 dosiert nicht mehr; es bleibt die Horizont-Auswahl, die
 * Einstellung, Politik und Politik-Hash lesen. Die Tests der Entscheidung und der Bahnwahl sind mit ihr entfallen.
 */
class EarlyAdaptiveMealNeedTest {

    @Test
    fun `nur 0, 6, 8 und 10 sind wirksam - alles andere faellt auf AUS`() {
        assertEquals(listOf(0, 6, 8, 10, 0, 0, 0, 0), listOf(0, 6, 8, 10, -1, 7, 60, Int.MAX_VALUE).map(EarlyAdaptiveMealNeed::effectiveHorizonMin))
    }
}

package app.aaps.fuse.core.predictor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Die ZUSAMMENSETZUNG, nicht die einzelne Bahn.
 *
 * `ConditionalTrajectoryTest` prueft, dass eine gehobene Bahn hoeher liegt. Das
 * war gruen, waehrend das Feature wirkungslos war: der Schwanz rechnet gegen
 * das MINIMUM aus Haupt- und Bremsbahn, und gehoben war nur eine von beiden.
 *
 * Diese Datei prueft deshalb genau das, was dort fehlte - und die beiden Faelle,
 * an denen zwei Anlaeufe nacheinander gescheitert sind.
 */
class ConditionalDriveTest {

    private val METHOD = "UKF_RATE_RESTRAINT_V1"

    /** Hauptbahn mit Luft nach oben: Sicherheitskante 0,2 unter der Anzeige 0,9. */
    private fun haupt(safety: Double = 0.2, lower: Double = 0.9, mean: Double = 1.6) =
        DriveEstimate(mean, lower, 0.8, "test", safety)

    // ---- Fall 1: Bremse dominiert -----------------------------------------

    /**
     * DER LIVEFALL VOM 11.08.: die Bremsbahn ist die bindende. Gemessen waren
     * Haupt 114,5 gegen kombiniert 91,8 - das Minimum kam aus der Bremse.
     *
     * Wird nur die Hauptbahn gehoben, aendert sich am Minimum NICHTS, und
     * genau so lief es 14 Minuten lang weiter, waehrend der BG von 139 auf 163
     * stieg. Der Test verlangt deshalb ausdruecklich, dass die BREMSBAHN
     * gehoben wird.
     */
    @Test
    fun `bei dominierender Bremse wird auch die Bremsbahn gehoben`() {
        val l = ConditionalDrive.of(
            mainDrive = haupt(),
            restraintMean = 1.2,
            restraintLower = 0.1,       // deutlich unter der Hauptbahn -> sie bindet
            restraintMethodId = METHOD,
            declaredDriveMgdlPerMin = 0.5,
        )
        assertNotNull(l.main, "die Hauptbahn muss gehoben sein")
        val r = assertNotNull2(l.restraint, "DIE BREMSBAHN MUSS GEHOBEN SEIN - sonst verpufft alles")
        assertEquals(0.6, r.lowerMgdlPerMin, 1e-9, "0,1 + 0,5 Kredit")
        assertEquals(0.6, r.safetyLowerMgdlPerMin, 1e-9, "ohne Prior ist die Sicherheitskante die untere")
        assertTrue(r.lowerMgdlPerMin > 0.1, "die bindende Kante muss wirklich steigen")
    }

    // ---- Fall 2: Hauptbahn am Deckel --------------------------------------

    /**
     * DER ZWEITE ANLAUF SCHEITERTE HIER: `conditionalRestraint` haing an
     * `conditional != null`. Steht die Hauptbahn schon an ihrem Deckel
     * (Sicherheitskante == Anzeigekante), ist sie nicht hebbar - und die
     * Bremsbahn wurde dadurch gar nicht mehr gerechnet, obwohl sie es waere.
     *
     * Die beiden Bahnen sind UNABHAENGIG. Das ist die Zusicherung.
     */
    @Test
    fun `Hauptbahn am Deckel - die Bremsbahn wird trotzdem gehoben`() {
        val l = ConditionalDrive.of(
            mainDrive = haupt(safety = 0.9, lower = 0.9),   // kein Zwischenraum mehr
            restraintMean = 1.2,
            restraintLower = 0.1,
            restraintMethodId = METHOD,
            declaredDriveMgdlPerMin = 0.5,
        )
        assertNull(l.main, "die Hauptbahn kann nicht weiter")
        val r = assertNotNull2(l.restraint, "die Bremsbahn haengt NICHT an der Hauptbahn")
        assertEquals(0.6, r.lowerMgdlPerMin, 1e-9)
        assertTrue(l.any, "eine gehobene Bahn reicht")
    }

    /** Und umgekehrt: Bremse am Deckel, Hauptbahn hebbar. */
    @Test
    fun `Bremse am Deckel - die Hauptbahn wird trotzdem gehoben`() {
        val l = ConditionalDrive.of(
            mainDrive = haupt(),
            restraintMean = 0.4,
            restraintLower = 0.4,       // schon am eigenen Mittel
            restraintMethodId = METHOD,
            declaredDriveMgdlPerMin = 0.5,
        )
        assertNotNull(l.main)
        assertNull(l.restraint, "sie steht an ihrem eigenen Mittel")
    }

    // ---- Die Deckel --------------------------------------------------------

    @Test
    fun `die Hauptbahn kommt nie ueber ihre Anzeigekante`() {
        val l = ConditionalDrive.of(haupt(safety = 0.2, lower = 0.9), 1.2, 0.1, METHOD, 99.0)
        assertEquals(0.9, assertNotNull2(l.main, "gehoben").safetyLowerMgdlPerMin, 1e-9)
    }

    @Test
    fun `die Bremsbahn kommt nie ueber ihr eigenes Mittel`() {
        val l = ConditionalDrive.of(haupt(), restraintMean = 1.2, restraintLower = 0.1, METHOD, 99.0)
        assertEquals(1.2, assertNotNull2(l.restraint, "gehoben").lowerMgdlPerMin, 1e-9)
    }

    // ---- Kein Kredit = kein Unterschied ------------------------------------

    @Test
    fun `ohne Kredit wird nichts gehoben`() {
        for (kredit in listOf(0.0, -1.0, Double.NaN)) {
            val l = ConditionalDrive.of(haupt(), 1.2, 0.1, METHOD, kredit)
            assertNull(l.main, "Kredit $kredit")
            assertNull(l.restraint, "Kredit $kredit")
            assertTrue(!l.any)
        }
    }

    @Test
    fun `ohne Bremsbahn bleibt die Hauptbahn allein`() {
        val l = ConditionalDrive.of(haupt(), null, null, METHOD, 0.5)
        assertNotNull(l.main)
        assertNull(l.restraint)
    }

    /** Nicht-endliche Bremswerte duerfen keine Bahn erfinden. */
    @Test
    fun `nicht endliche Bremswerte heben nichts`() {
        assertNull(ConditionalDrive.of(haupt(), Double.NaN, 0.1, METHOD, 0.5).restraint)
        assertNull(ConditionalDrive.of(haupt(), 1.2, Double.NaN, METHOD, 0.5).restraint)
    }

    /** Die Methodenkennung sagt, dass hier bedingt gerechnet wurde - sonst
     *  waere im Export nicht zu unterscheiden, welche Bremsbahn gemeint ist. */
    @Test
    fun `die gehobene Bremsbahn ist an ihrer Kennung erkennbar`() {
        val r = assertNotNull2(ConditionalDrive.of(haupt(), 1.2, 0.1, METHOD, 0.5).restraint, "gehoben")
        assertTrue(r.uncertaintyMethodId.endsWith("+COND"), r.uncertaintyMethodId)
    }

    private fun <T> assertNotNull2(v: T?, msg: String): T = v ?: throw AssertionError(msg)
}

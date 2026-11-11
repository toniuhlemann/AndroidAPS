package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.EarlySupply.MealTime
import app.aaps.fuse.core.controller.EarlySupply.TimeOfDayScales
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER SKALIERER JE TAGESZEIT (Kandidat, Toni 11.11.2026): die Fenster nach der Uhrzeit des Markers, der Skalierer je
 * Fenster und was er an den Einstellungen und an der Buchung aendert. Die Verdrahtung prueft das Gestell
 * (`ErwartungskontoRigTest`). Synthetische Werte, keine Messdaten.
 */
class TimeOfDayScalesTest {

    private fun skalen(
        morgens: Double = 0.0, mittags: Double = 0.0, abends: Double = 0.0,
        mittagAb: Int = TimeOfDayScales.MIDDAY_START_DEFAULT_MIN, abendAb: Int = TimeOfDayScales.EVENING_START_DEFAULT_MIN,
    ) = requireNotNull(TimeOfDayScales.of(morgens, mittags, abends, mittagAb, abendAb))

    private val basis = requireNotNull(EarlySupply.Settings.of(1.5, 1.0, 20.0, 0.5, 1.0, 2.0, 10, 0.5))

    /** 04:00 beginnt das Fruehstueck, 10:30 der Mittag, 15:00 der Abend - und der reicht ueber Mitternacht bis 04:00. */
    @Test
    fun `die drei Fenster nach der Uhrzeit des Markers`() {
        val s = skalen(1.4, 1.2, 1.3)
        val erwartet = listOf(
            0 to MealTime.EVENING, 239 to MealTime.EVENING,
            240 to MealTime.MORNING, 629 to MealTime.MORNING,
            630 to MealTime.MIDDAY, 899 to MealTime.MIDDAY,
            900 to MealTime.EVENING, 1439 to MealTime.EVENING,
        )
        for ((minute, fenster) in erwartet) assertEquals(fenster, s.mealTimeAt(minute)) { "Minute $minute" }
        assertEquals(1.4, s.scaleAt(300))
        assertEquals(1.2, s.scaleAt(700))
        assertEquals(1.3, s.scaleAt(1200))
        assertEquals(1.3, s.scaleAt(100))
    }

    @Test
    fun `0 heisst nicht gesetzt - dort gilt der allgemeine Skalierer`() {
        val nurMorgens = skalen(morgens = 1.4)
        assertTrue(nurMorgens.active)
        assertEquals(1.4, nurMorgens.scaleAt(300))
        assertNull(nurMorgens.scaleAt(700))
        assertNull(nurMorgens.scaleAt(1200))
        // Nichts gesetzt: nicht aktiv, und die Uhrzeiten allein aendern nichts.
        val nichts = skalen(mittagAb = 700, abendAb = 1000)
        assertFalse(nichts.active)
        for (minute in 0..1439) assertNull(nichts.scaleAt(minute)) { "Minute $minute" }
    }

    /**
     * DIE UHRZEITEN MUESSEN AUFSTEIGEN: 04:00 < Mittag beginnt < Abend beginnt. Ein verdrehtes Paar liesse ein Fenster
     * still verschwinden (etwa "Mittag ab 15:00, Abend ab 10:30": bis 15:00 gaelte der Skalierer des Fruehstuecks) - es
     * gilt deshalb nicht.
     */
    @Test
    fun `verdrehte Uhrzeiten gelten nicht, sobald ein Skalierer gesetzt ist`() {
        val verdreht = listOf(900 to 630, 630 to 630, 240 to 900, 100 to 900, 0 to 0, 1439 to 1439, 0 to 1439)
        for ((mittagAb, abendAb) in verdreht) {
            for ((morgens, mittags, abends) in listOf(Triple(1.4, 0.0, 0.0), Triple(0.0, 1.2, 0.0), Triple(0.0, 0.0, 1.3), Triple(1.4, 1.2, 1.3))) {
                assertEquals(
                    TimeOfDayScales.Violation.TIMES_OUT_OF_ORDER,
                    TimeOfDayScales.violation(morgens, mittags, abends, mittagAb, abendAb),
                ) { "$mittagAb/$abendAb mit $morgens/$mittags/$abends" }
                assertNull(TimeOfDayScales.of(morgens, mittags, abends, mittagAb, abendAb)) { "$mittagAb/$abendAb" }
            }
        }
        // Die Grenzen selbst: eine Minute nach 04:00 und eine Minute Mittag genuegen, der Abend darf um 23:59 beginnen.
        assertNull(TimeOfDayScales.violation(1.4, 1.2, 1.3, 241, 242))
        assertNull(TimeOfDayScales.violation(1.4, 1.2, 1.3, 241, 1439))
        assertNotNull(TimeOfDayScales.of(1.4, 1.2, 1.3, 630, 900))
    }

    /** AUS BLEIBT AUS: ohne gesetzten Skalierer sind die Uhrzeiten ohne Wirkung - auch verdrehte machen nichts ungueltig. */
    @Test
    fun `ohne gesetzten Skalierer werden die Uhrzeiten nicht geprueft`() {
        for ((mittagAb, abendAb) in listOf(900 to 630, 630 to 630, 0 to 0, 1439 to 1439)) {
            assertNull(TimeOfDayScales.violation(0.0, 0.0, 0.0, mittagAb, abendAb)) { "$mittagAb/$abendAb" }
            val s = requireNotNull(TimeOfDayScales.of(0.0, 0.0, 0.0, mittagAb, abendAb))
            assertFalse(s.active)
            for (minute in 0..1439) assertNull(s.scaleAt(minute)) { "Minute $minute bei $mittagAb/$abendAb" }
        }
    }

    @Test
    fun `Werte ausserhalb der Grenzen gelten nicht`() {
        assertNotNull(TimeOfDayScales.of(EarlySupply.MAX_SCALE, 0.0, 0.0, 630, 900))
        for (schlecht in listOf(-0.1, EarlySupply.MAX_SCALE + 0.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            for ((morgens, mittags, abends) in listOf(Triple(schlecht, 1.0, 1.0), Triple(1.0, schlecht, 1.0), Triple(1.0, 1.0, schlecht))) {
                // Der Skalierer zaehlt vor den Uhrzeiten - auch bei verdrehten.
                assertEquals(TimeOfDayScales.Violation.SCALE_OUT_OF_RANGE, TimeOfDayScales.violation(morgens, mittags, abends, 900, 630)) { "$schlecht" }
                assertNull(TimeOfDayScales.of(morgens, mittags, abends, 630, 900)) { "$schlecht" }
            }
        }
        for (schlecht in listOf(-1, 1440)) {
            assertEquals(TimeOfDayScales.Violation.TIME_OUT_OF_RANGE, TimeOfDayScales.violation(1.0, 1.0, 1.0, schlecht, 900)) { "Mittag ab $schlecht" }
            assertEquals(TimeOfDayScales.Violation.TIME_OUT_OF_RANGE, TimeOfDayScales.violation(0.0, 0.0, 0.0, 630, schlecht)) { "Abend ab $schlecht" }
            assertNull(TimeOfDayScales.of(1.0, 1.0, 1.0, schlecht, 900))
            assertNull(TimeOfDayScales.of(1.0, 1.0, 1.0, 630, schlecht))
        }
    }

    /** Letzte Verteidigung: auch ein ungeprueft gebautes Paar ergibt fuer jede Minute genau ein Fenster. */
    @Test
    fun `jede Minute hat genau ein Fenster - auch ohne Pruefung`() {
        for ((mittagAb, abendAb) in listOf(630 to 900, 0 to 0, 100 to 900, 900 to 630, 630 to 630, 1439 to 1439, 0 to 1439)) {
            val s = TimeOfDayScales(1.4, 1.2, 1.3, mittagAb, abendAb)
            for (minute in 0..1439) assertNotNull(s.scaleAt(minute)) { "Minute $minute bei $mittagAb/$abendAb" }
        }
    }

    @Test
    fun `der Skalierer des Fensters ersetzt den stabilen und deckelt den nach Tief`() {
        val hoeher = basis.forTimeOfDay(1.8)
        // Sonst aendert sich nichts - und der "nach Tief" steigt nicht mit.
        assertEquals(basis.copy(scaleStable = 1.8), hoeher)
        val kleiner = basis.forTimeOfDay(0.8)
        assertEquals(basis.copy(scaleStable = 0.8, scaleAfterLow = 0.8), kleiner)
    }

    @Test
    fun `nicht gesetzt oder ungueltig laesst die Einstellungen unveraendert`() {
        for (x in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, EarlySupply.MAX_SCALE + 0.01)) {
            assertSame(basis, basis.forTimeOfDay(x)) { "bei $x" }
        }
    }

    /** Die Regel des Snacks bleibt: er bucht nie mehr als eine Mahlzeit - jetzt als die Mahlzeit dieser Tageszeit. */
    @Test
    fun `ein Snack bucht nie mehr als die Mahlzeit der Tageszeit`() {
        assertEquals(1.0, basis.forTimeOfDay(1.8).forSnack(1.0).scaleStable)
        assertEquals(0.8, basis.forTimeOfDay(0.8).forSnack(1.0).scaleStable)
        // Ohne Skalierer im Fenster wie bisher.
        assertEquals(basis.forSnack(1.0), basis.forTimeOfDay(null).forSnack(1.0))
    }

    /** Am Marker: Skalierer und Budget des Kontos folgen dem Fenster; das Budget-Max deckelt weiter. */
    @Test
    fun `die Buchung traegt den Skalierer des Fensters`() {
        val t0 = 1_700_000_000_000L
        val wirkung: (Double) -> Double = { t -> EarlySupply.orefActionFraction(t, 45.0, 540.0) }
        val werte = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0)).values()
        val kurve = requireNotNull(ExpectationAccount.Curve.of(werte))
        val params = requireNotNull(ExpectationAccount.Params.of(werte, 120, 90.0, 15, 0.3))
        fun konto(e: EarlySupply.Settings, nachTief: Boolean = false) = ExpectationAccount.armEarly(
            authId = t0, windowEndTs = t0 + 120 * 60_000L, params = params, settings = e, switches = EarlySupply.Switches.NONE,
            startStable = !nachTief, nowTs = t0, bgMgdl = 100.0, isfMgdlPerU = 50.0, unitAction = wirkung,
        )
        val allgemein = konto(basis)
        val morgens = konto(basis.forTimeOfDay(1.8))
        val abends = konto(basis.forTimeOfDay(1.2))
        assertEquals(1.5, allgemein.phi0, 0.0)
        assertEquals(1.8, morgens.phi0, 0.0)
        assertEquals(1.2, abends.phi0, 0.0)
        // Ohne Deckel waechst das Budget mit dem Skalierer.
        assertEquals(EarlySupply.budgetU(kurve, 120, 1.8, basis.budgetMaxU, wirkung), morgens.a0U, 1e-12)
        assertEquals(allgemein.a0U * 1.8 / 1.5, morgens.a0U, 1e-9)
        assertEquals(allgemein.a0U * 1.2 / 1.5, abends.a0U, 1e-9)
        // Das Budget-Max bleibt die Grenze - ein hoeherer Skalierer hebt es nicht.
        val gedeckelt = basis.copy(budgetMaxU = allgemein.a0U)
        assertEquals(allgemein.a0U, konto(gedeckelt.forTimeOfDay(1.8)).a0U, 1e-12)
        // Start im Tief: nie mehr als der eigene "nach Tief", und nie mehr als das Fenster.
        assertEquals(1.0, konto(basis.forTimeOfDay(1.8), nachTief = true).phi0, 0.0)
        assertEquals(0.8, konto(basis.forTimeOfDay(0.8), nachTief = true).phi0, 0.0)
    }
}

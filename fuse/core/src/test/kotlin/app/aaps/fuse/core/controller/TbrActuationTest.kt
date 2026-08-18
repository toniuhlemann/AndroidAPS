package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TbrActuationTest {

    private val PROFIL = 0.8
    private val STEP = 0.05

    private fun pruefe(
        current: TbrActuation.Current? = null,
        rate: Double? = null,
        dauer: Int? = null,
        profil: Double = PROFIL,
        step: Double = STEP,
    ) = TbrActuation.changed(current, rate, dauer, profil, step)

    // ---- Keine Anforderung ----------------------------------------------

    /**
     * DAS AUSLAUFEN EINER TBR IST KEIN EINGRIFF DIESES ZYKLUS.
     *
     * Es ist die Folge eines frueheren, der damals gezaehlt wurde. Ihn hier
     * erneut zu zaehlen hiesse, denselben Eingriff zweimal zu verbuchen - und
     * jede Prognose zu entwerten, die zufaellig ueber das Ende einer TBR
     * hinausreicht.
     */
    @Test
    fun `ohne Anforderung aendert dieser Zyklus nichts`() {
        assertEquals(false, pruefe(), "kein Lauf, keine Anforderung")
        assertEquals(false, pruefe(current = TbrActuation.Current(0.0, 12)), "laufende TBR laeuft weiter")
    }

    // ---- Neue Rate -------------------------------------------------------

    @Test
    fun `eine andere Rate als die laufende ist eine Aenderung`() {
        assertEquals(true, pruefe(current = TbrActuation.Current(0.0, 20), rate = 0.8, dauer = 30), "Rueckkehr zum Profil")
        assertEquals(true, pruefe(current = TbrActuation.Current(0.8, 20), rate = 0.0, dauer = 30), "Zero-TBR")
    }

    /**
     * OHNE LAUFENDE TBR ZAEHLT JEDE ANFORDERUNG - auch eine auf Profilhoehe.
     *
     * Sie nagelt die Rate fuer ihre Laufzeit fest, auch gegen einen
     * Profilwechsel; Tonis Profil wechselt stuendlich. Das Urteil darf dabei
     * NICHT von der Dauer abhaengen - genau das war der erste Wurf, in dem
     * zufaellig die Laufzeitregel entschied.
     */
    @Test
    fun `ohne laufende TBR zaehlt jede Anforderung`() {
        assertEquals(true, pruefe(rate = PROFIL, dauer = 30), "auf Profilhoehe")
        assertEquals(true, pruefe(rate = PROFIL, dauer = 3), "und zwar unabhaengig von der Dauer")
        assertEquals(true, pruefe(rate = 0.0, dauer = 30), "erst recht eine Null")
    }

    /** Ein Abbruch (Dauer 0) aendert nur, wenn ueberhaupt etwas lief. */
    @Test
    fun `ein Abbruch zaehlt nur bei laufender TBR`() {
        assertEquals(true, pruefe(current = TbrActuation.Current(0.0, 20), rate = 0.0, dauer = 0))
        assertEquals(false, pruefe(rate = 0.0, dauer = 0), "nichts abzubrechen")
    }

    /**
     * WAS DIE PUMPE NICHT UNTERSCHEIDEN KANN, HAT SIE NICHT GEAENDERT.
     *
     * Die Toleranz ist die halbe Schrittweite - feiner rastert die Pumpe
     * nicht. Ohne sie zaehlte jede Rundungsdifferenz als Eingriff, und der
     * Nachweis waere in einem Ein-Minuten-Takt sofort tot.
     */
    @Test
    fun `Unterschiede unterhalb der halben Pumpenrasterung zaehlen nicht`() {
        assertEquals(false, pruefe(current = TbrActuation.Current(0.8, 20), rate = 0.82, dauer = 20), "0,02 < 0,025")
        assertEquals(true, pruefe(current = TbrActuation.Current(0.8, 20), rate = 0.83, dauer = 20), "0,03 > 0,025")
    }

    // ---- Laufzeit --------------------------------------------------------

    /**
     * EINE VERLAENGERUNG IST EIN EIGENER EINGRIFF.
     *
     * Eine Zero-TBR mit 3 Minuten Rest auf 30 zu setzen ist keine
     * Bestaetigung, sondern 27 zusaetzliche Minuten Zurueckhaltung.
     */
    @Test
    fun `eine relevante Verlaengerung bei gleicher Rate zaehlt`() {
        val laeuft = TbrActuation.Current(0.0, 3)
        assertEquals(true, pruefe(current = laeuft, rate = 0.0, dauer = 30))
        assertEquals(false, pruefe(current = laeuft, rate = 0.0, dauer = 8), "3+5 ist die Grenze, nicht darueber")
        assertEquals(true, pruefe(current = laeuft, rate = 0.0, dauer = 9), "eine Minute darueber")
    }

    /** DIE ANDERE RICHTUNG ZAEHLT NICHT: eine kuerzere Anforderung nimmt
     *  Zurueckhaltung zurueck - danach gilt die urspruengliche Behauptung
     *  eher wieder als weniger. */
    @Test
    fun `eine Verkuerzung ist keine Aenderung`() {
        assertEquals(false, pruefe(current = TbrActuation.Current(0.0, 25), rate = 0.0, dauer = 10))
    }

    // ---- Nicht beurteilbar ----------------------------------------------

    /** IM ZWEIFEL KEINE ANTWORT - der Aufrufer wertet `null` als Eingriff. */
    @Test
    fun `unbrauchbare Zahlen ergeben null`() {
        assertNull(pruefe(rate = Double.NaN, dauer = 30), "NaN-Rate")
        assertNull(pruefe(rate = 0.0, dauer = 30, profil = Double.NaN), "NaN-Profil")
        assertNull(pruefe(rate = 0.0, dauer = 30, step = 0.0), "Schrittweite null")
        assertNull(pruefe(rate = 0.0, dauer = 30, step = Double.NaN), "NaN-Schrittweite")
        assertNull(pruefe(rate = 0.0, dauer = -1), "negative Dauer")
        assertNull(pruefe(current = TbrActuation.Current(Double.NaN, 10), rate = 0.0, dauer = 30), "laufende Rate NaN")
        assertNull(pruefe(current = TbrActuation.Current(0.0, -1), rate = 0.0, dauer = 30), "negative Restzeit")
    }

    /** Eine unbrauchbare LAUFENDE Rate darf nicht erst nach der
     *  Rueckgabe auffallen - auch ohne neue Anforderung. */
    @Test
    fun `unbrauchbare laufende Werte ergeben auch ohne Anforderung null`() {
        assertNull(pruefe(current = TbrActuation.Current(Double.NaN, 10)))
    }
}

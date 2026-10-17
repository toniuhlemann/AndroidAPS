package app.aaps.fuse.core.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SignalWindowTest {

    private val anchor = 1_000_000_000L
    private val min = 60_000L

    @Test
    fun `ohne bekannte Grenzen beginnt die Reihe beim vollen Vorlauf`() {
        val w = SignalWindow.of(anchor, 0L, 0L)
        assertEquals(anchor - SignalWindow.LOOKBACK_MS, w.fromTs)
        assertEquals(SignalWindow.Bound.NONE, w.bound)
    }

    /**
     * DER WAECHTER gegen den teuersten Fehler dieser Portierung.
     *
     * Der Vorlauf sind NICHT 180 Minuten. Der aelteste Punkt des 18-min-
     * R-Fensters bekommt sein eigenes 180-Proben-Praefix und reicht damit 18
     * Minuten weiter zurueck als der Anker. Wer hier 180 min einsetzt, nimmt
     * genau diesem Punkt ~18 Proben weg — anderer UKF-Startzustand, anderes
     * gelerntes R, anderes q1, anderes rSigned. Und zwar OHNE dass eine Grenze
     * gegriffen haette, also unsichtbar.
     */
    @Test
    fun `der Vorlauf deckt Filterfenster UND R-Fenster ab - 198 min, nicht 180`() {
        assertEquals(180L * min + BgiAdjustedSeries.WINDOW_MS, SignalWindow.LOOKBACK_MS)
        assertEquals(198L * min, SignalWindow.LOOKBACK_MS)
    }

    @Test
    fun `ein Sensorwechsel im Fenster setzt den Anfang`() {
        val sensor = anchor - 40 * min
        val w = SignalWindow.of(anchor, sensor, 0L)
        assertEquals(sensor, w.fromTs)
        assertEquals(SignalWindow.Bound.SENSOR_CHANGE, w.bound)
    }

    @Test
    fun `eine Kalibrierung im Fenster setzt den Anfang`() {
        val cal = anchor - 5 * min
        val w = SignalWindow.of(anchor, 0L, cal)
        assertEquals(cal, w.fromTs)
        assertEquals(SignalWindow.Bound.CALIBRATION_START, w.bound)
    }

    /** Tie-Regel 1:1 vom Fork: bei Gleichstand gewinnt der Sensorwechsel. */
    @Test
    fun `bei Gleichstand gewinnt der Sensorwechsel`() {
        val t = anchor - 30 * min
        assertEquals(SignalWindow.Bound.SENSOR_CHANGE, SignalWindow.of(anchor, t, t).bound)
    }

    @Test
    fun `die juengere der beiden Grenzen gewinnt`() {
        val sensor = anchor - 90 * min
        val cal = anchor - 12 * min
        val w = SignalWindow.of(anchor, sensor, cal)
        assertEquals(cal, w.fromTs)
        assertEquals(SignalWindow.Bound.CALIBRATION_START, w.bound)
    }

    /** Der Default des Keys ist -1, nicht 0 — er darf nie als Zeitstempel
     *  durchgehen, sonst laege der Kalibrierbeginn vor 1970. */
    @Test
    fun `negative Marker gelten als unbekannt`() {
        val w = SignalWindow.of(anchor, -1L, -1L)
        assertEquals(anchor - SignalWindow.LOOKBACK_MS, w.fromTs)
        assertEquals(SignalWindow.Bound.NONE, w.bound)
    }

    /** Eine Grenze aelter als der Vorlauf aendert nichts — sie ist laengst aus
     *  der Reihe heraus. */
    @Test
    fun `eine alte Grenze bindet nicht`() {
        val w = SignalWindow.of(anchor, anchor - 300 * min, 0L)
        assertEquals(anchor - SignalWindow.LOOKBACK_MS, w.fromTs)
        assertEquals(SignalWindow.Bound.NONE, w.bound)
    }

    /**
     * Eine Grenze in der ZUKUNFT des Ankers wuerde die Reihe leeren statt sie zu
     * trennen. Das kommt real vor: der Sensorstart kann aus dem Bundle einer
     * Quelle stammen, deren Uhr vorgeht.
     */
    @Test
    fun `eine Grenze hinter dem Anker wird ignoriert`() {
        val w = SignalWindow.of(anchor, anchor + 10 * min, 0L)
        assertEquals(anchor - SignalWindow.LOOKBACK_MS, w.fromTs)
        assertEquals(SignalWindow.Bound.NONE, w.bound)
    }

    /** Genau am Anker ist noch gueltig: der Wechsel ist gerade passiert, die
     *  Reihe besteht dann aus einem Punkt. Das ist kein Fehler, sondern der
     *  ehrliche Zustand — und der Aufrufer meldet ihn benannt. */
    @Test
    fun `eine Grenze exakt am Anker bindet noch`() {
        val w = SignalWindow.of(anchor, anchor, 0L)
        assertEquals(anchor, w.fromTs)
        assertEquals(SignalWindow.Bound.SENSOR_CHANGE, w.bound)
    }
}

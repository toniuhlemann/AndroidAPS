package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.predictor.PredictorReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * WELCHE PREDICTOR-ABLEHNUNG UEBERSTIMMBAR IST - und vor allem: welche nicht.
 *
 * Die Liste ist die eigentliche Sicherheitsaussage dieses Pfades, deshalb steht
 * hier JEDER der zehn Gruende einzeln. Eine Schleife ueber `entries` mit einer
 * Ausnahmeliste haette denselben Denkfehler wiederholt, den sie pruefen soll.
 *
 * Vorgeschichte, ehrlich: mein erster Vorschlag nannte sechs Gruende als
 * "modellhaft" und hatte DRIVE_OUT_OF_BOUNDS darunter - Toni hat das korrigiert.
 * Ein Antrieb ausserhalb der Policy-Grenzen ist kein pessimistisches Modell,
 * sondern eine unglaubwuerdige EINGABE. Der Marker bestaetigt Kohlenhydrate,
 * nicht die Integritaet der Eingaben.
 */
class MarkerFallbackTest {

    private fun denial(
        reason: PredictorReason,
        markerAuthorized: Boolean = true,
        mealMarkerActive: Boolean = true,
        health: Health = Health.READY,
        transportCommitmentU: Double = 0.0,
    ) = MarkerFallback.denial(
        reason, markerAuthorized, mealMarkerActive, health, transportCommitmentU
    )

    // ---- Die zwei offenen ---------------------------------------------------

    /** Beide sagen dasselbe: "die Reichweite meiner Rechnung endet vor dem
     *  Horizont". Eine Aussage ueber das WERKZEUG, nicht ueber die Daten. */
    @Test
    fun `ARRAY_TOO_SHORT ist ueberstimmbar`() =
        assertNull(denial(PredictorReason.ARRAY_TOO_SHORT))

    @Test
    fun `PENDING_MODEL_TOO_SHORT ist ueberstimmbar`() =
        assertNull(denial(PredictorReason.PENDING_MODEL_TOO_SHORT))

    /**
     * TONIS AUFLAGE, und sie gilt NUR fuer diesen einen Grund: er sagt, dass der
     * Kern der bereits publizierten, im IOB noch nicht sichtbaren Menge das
     * Fenster nicht deckt. Ihre BAHNwirkung ist damit unbekannt - ihre MENGE
     * nicht. Wird sie nicht mehr von beiden Spielraeumen abgezogen, kann sie ein
     * zweites Mal finanziert werden, und dann ist der Grund nicht ueberstimmbar.
     */
    @Test
    fun `PENDING_MODEL_TOO_SHORT faellt bei unbekannter Transportmenge zu`() =
        assertEquals(
            MarkerFallback.Denial.TRANSPORT_NOT_ACCOUNTED,
            denial(PredictorReason.PENDING_MODEL_TOO_SHORT, transportCommitmentU = Double.NaN),
        )

    /** ARRAY_TOO_SHORT haengt NICHT daran - dort fehlt das IOB-Array, nicht der
     *  Kern der Transportmenge. Ohne diesen Fall waere die Auflage entweder zu
     *  eng oder zufaellig richtig. */
    @Test
    fun `ARRAY_TOO_SHORT haengt nicht an der Transportmenge`() =
        assertNull(denial(PredictorReason.ARRAY_TOO_SHORT, transportCommitmentU = Double.NaN))

    /**
     * NULL IST EIN GUELTIGER WERT, kein fehlender. Ist nichts unterwegs, ist
     * die Transportmenge 0,0 - und die Freigabe bleibt zulaessig. Ein Boolean
     * `transportAccounted` haette hier zwar dasselbe gesagt, aber aus dem
     * falschen Grund: er war fuer 0,0 ebenso wahr wie fuer eine ordentlich
     * abgezogene Menge, ohne beide unterscheiden zu koennen.
     */
    @Test
    fun `eine Transportmenge von null ist kein Hinderungsgrund`() =
        assertNull(denial(PredictorReason.PENDING_MODEL_TOO_SHORT, transportCommitmentU = 0.0))

    // ---- Die acht geschlossenen, einzeln ------------------------------------

    @Test
    fun `NON_FINITE_INPUT bleibt zu`() = zu(PredictorReason.NON_FINITE_INPUT)

    @Test
    fun `NON_MONOTONIC_TIMESTAMPS bleibt zu`() = zu(PredictorReason.NON_MONOTONIC_TIMESTAMPS)

    @Test
    fun `GRID_MISMATCH bleibt zu`() = zu(PredictorReason.GRID_MISMATCH)

    @Test
    fun `SKEW_BEFORE_ARRAY_START bleibt zu`() = zu(PredictorReason.SKEW_BEFORE_ARRAY_START)

    @Test
    fun `ISF_OUT_OF_BOUNDS bleibt zu`() = zu(PredictorReason.ISF_OUT_OF_BOUNDS)

    @Test
    fun `MISSING_ISF_SLOT bleibt zu`() = zu(PredictorReason.MISSING_ISF_SLOT)

    @Test
    fun `ACTIVITY_OUT_OF_BOUNDS bleibt zu`() = zu(PredictorReason.ACTIVITY_OUT_OF_BOUNDS)

    /** Der verfuehrerischste von allen: er sieht aus wie eine pessimistische
     *  Prognose. Er ist eine unglaubwuerdige Eingabe - und dann ist auch die
     *  ISF unglaubwuerdig, mit der die Freigabe gerechnet wuerde. */
    @Test
    fun `DRIVE_OUT_OF_BOUNDS bleibt zu`() = zu(PredictorReason.DRIVE_OUT_OF_BOUNDS)

    private fun zu(r: PredictorReason) =
        assertEquals(MarkerFallback.Denial.REASON_NOT_OVERRIDABLE, denial(r), r.name)

    /** VOLLSTAENDIGKEIT: genau zwei von zehn. Ein neuer Grund faellt hier auf
     *  und landet per Mengendefinition auf der geschlossenen Seite. */
    @Test
    fun `genau zwei von zehn Gruenden sind offen`() {
        assertEquals(10, PredictorReason.entries.size)
        assertEquals(2, MarkerFallback.OVERRIDABLE.size)
        assertEquals(
            8, PredictorReason.entries.count { denial(it) == MarkerFallback.Denial.REASON_NOT_OVERRIDABLE }
        )
    }

    // ---- Die uebrigen Vorbedingungen ---------------------------------------

    @Test
    fun `ohne Einstellung kein Fallback`() = assertEquals(
        MarkerFallback.Denial.SETTING_OFF,
        denial(PredictorReason.ARRAY_TOO_SHORT, markerAuthorized = false),
    )

    @Test
    fun `ohne laufenden Marker kein Fallback`() = assertEquals(
        MarkerFallback.Denial.NO_MARKER,
        denial(PredictorReason.ARRAY_TOO_SHORT, mealMarkerActive = false),
    )

    /**
     * EIN GEMESSENES TIEF IST KEINE BEDINGUNG MEHR - und dieser Test stand
     * einen Commit lang andersherum.
     *
     * Er verlangte `NO_MEASURED_LOW`, weil ich das Tief zur Voraussetzung der
     * Autorisierung gemacht hatte. Es war nur der Anlass. Der Livefall vom
     * 11.08. - BG 105 fallend, Marker seit 3 min, alle technischen Tore frei,
     * 0 U - ist der HAUPTFALL einer Mahlzeit, und er hatte keine
     * SafetyReason. Die Menge begrenzt jetzt die Huelle, nicht das Tief.
     */
    @Test
    fun `ein gemessenes Tief ist keine Bedingung`() =
        assertNull(denial(PredictorReason.ARRAY_TOO_SHORT))

    /** READY deckt frisches Signal, monotone Zeitachse, gueltige ISF und
     *  Aktivitaet in EINER bereits gepflegten Aussage. */
    @Test
    fun `ohne READY kein Fallback`() {
        for (h in listOf(Health.WARMUP, Health.DEGRADED))
            assertEquals(
                MarkerFallback.Denial.HEALTH_NOT_READY,
                denial(PredictorReason.ARRAY_TOO_SHORT, health = h), h.name,
            )
    }

    /** REIHENFOLGE: der Grund schlaegt alles andere. Sonst stuende im Export
     *  bei einem Datenfehler "kein Marker" und die eigentliche Ursache waere
     *  verdeckt. */
    @Test
    fun `der nicht ueberstimmbare Grund wird zuerst genannt`() = assertEquals(
        MarkerFallback.Denial.REASON_NOT_OVERRIDABLE,
        denial(
            PredictorReason.NON_FINITE_INPUT,
            markerAuthorized = false, mealMarkerActive = false, health = Health.WARMUP,
        ),
    )
}

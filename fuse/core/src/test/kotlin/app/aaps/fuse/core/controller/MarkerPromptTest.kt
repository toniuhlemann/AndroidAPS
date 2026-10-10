package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Die Rueckfrage-Regel, damit sie nicht in zwei Fragmenten auseinanderlaeuft.
 *
 * Sie ist winzig - aber sie sass vorher NIRGENDS: der FUSE-Tab schaltete den
 * Marker ohne jede Nachfrage um, und der Uebersichtsknopf haette leicht eine
 * eigene Fassung bekommen. Genau so entstehen zwei Sicherheitsniveaus fuer
 * denselben Knopf.
 */
class MarkerPromptTest {

    private fun facts(low: Boolean = false, auth: Boolean = true) = MarkerPrompt.Facts(
        upfrontPlannedU = 0.0, phaseARemainderU = 1.8, phaseBBudgetU = 1.2,
        foundationEndMin = 60, envelopeU = 3.0, alreadyDeliveredU = 0.0,
        authorizesAgainstModel = auth, measuredLow = low,
    )

    /**
     * DIE MENGENZEILEN DES DIALOGS (Tonis UI-P0 25.08. abends).
     *
     * Gerechnet wird mit einer Beispiellage: Huelle 3,0 U, Phase-A-Anteil 0,6
     * (also 1,80 U Phase A und 1,20 U Fundament), Freigabe-Fenster 30 min
     * - ausdruecklich NICHT die frueher fest verdrahteten 15 -, Fundament
     * bis 60 min. Geprueft wird der Sofortanteil 0,0 / 0,5 / 1,0.
     */
    private fun mengen(
        sofortAnteil: Double,
        huelleU: Double = 3.0,
        phaseAAnteil: Double = 0.6,
        fensterMin: Int? = 30,
        fundamentEndeMin: Int? = 60,
        geliefertU: Double = 0.0,
    ): List<MarkerPrompt.Line> {
        val phaseA = huelleU * phaseAAnteil
        return MarkerPrompt.lines(
            MarkerPrompt.Facts(
                upfrontPlannedU = phaseA * sofortAnteil,
                phaseARemainderU = phaseA - phaseA * sofortAnteil,
                phaseBBudgetU = huelleU - phaseA,
                foundationEndMin = fundamentEndeMin,
                envelopeU = huelleU,
                alreadyDeliveredU = geliefertU,
                authorizesAgainstModel = true,
                measuredLow = false,
                windowMin = fensterMin,
            )
        )
    }

    /** Betrags-Vergleich mit Toleranz - die Anteilsrechnung traegt
     *  Gleitkommareste (0,6 x 3,0 ist nicht exakt 1,8, 3,0 - 1,8 nicht exakt 1,2). */
    private fun pruefe(zeile: MarkerPrompt.Line, erwartet: MarkerPrompt.Line) {
        assertEquals(erwartet::class, zeile::class, "Zeilenart")
        when (erwartet) {
            is MarkerPrompt.Line.Upfront    ->
                assertEquals(erwartet.amountU, (zeile as MarkerPrompt.Line.Upfront).amountU, 1e-9)

            is MarkerPrompt.Line.Spread     -> {
                zeile as MarkerPrompt.Line.Spread
                assertEquals(erwartet.amountU, zeile.amountU, 1e-9)
                assertEquals(erwartet.windowMin, zeile.windowMin, "das Fenster kommt aus der Einstellung")
            }

            is MarkerPrompt.Line.Foundation -> {
                zeile as MarkerPrompt.Line.Foundation
                assertEquals(erwartet.amountU, zeile.amountU, 1e-9)
                assertEquals(erwartet.untilMin, zeile.untilMin)
            }

            is MarkerPrompt.Line.Total      ->
                assertEquals(erwartet.amountU, (zeile as MarkerPrompt.Line.Total).amountU, 1e-9)

            is MarkerPrompt.Line.Deferred   ->
                assertEquals(erwartet.reason, (zeile as MarkerPrompt.Line.Deferred).reason)
        }
    }

    @Test
    fun `Sofortanteil 1,0 nennt die ganze Phase A sofort und keine Verteilung`() {
        // Sofortanteil 1,0: der Druck fordert die ganze Phase A SOFORT an -
        // der alte Dialog nannte hier nur die Prime-Schrittmenge.
        val z = mengen(sofortAnteil = 1.0)
        assertEquals(3, z.size, "sofort + Fundament + Gesamtlimit, keine Verteilzeile: $z")
        pruefe(z[0], MarkerPrompt.Line.Upfront(1.80))
        pruefe(z[1], MarkerPrompt.Line.Foundation(1.20, 60))
        pruefe(z[2], MarkerPrompt.Line.Total(3.00))
    }

    @Test
    fun `Sofortanteil 0,5 teilt Phase A in sofort und verteilt`() {
        val z = mengen(sofortAnteil = 0.5)
        assertEquals(4, z.size, "alle vier Zeilen: $z")
        pruefe(z[0], MarkerPrompt.Line.Upfront(0.90))
        // DAS FENSTER KOMMT AUS DER EINSTELLUNG - 30, nicht 15.
        pruefe(z[1], MarkerPrompt.Line.Spread(0.90, 30))
        pruefe(z[2], MarkerPrompt.Line.Foundation(1.20, 60))
        pruefe(z[3], MarkerPrompt.Line.Total(3.00))
    }

    @Test
    fun `Sofortanteil 0,0 nennt gar keine Sofortzeile`() {
        val z = mengen(sofortAnteil = 0.0)
        assertEquals(3, z.size, "keine Sofortzeile: $z")
        pruefe(z[0], MarkerPrompt.Line.Spread(1.80, 30))
        pruefe(z[1], MarkerPrompt.Line.Foundation(1.20, 60))
        pruefe(z[2], MarkerPrompt.Line.Total(3.00))
    }

    @Test
    fun `ohne bekanntes Fenster wird keine Dauer genannt`() {
        val z = mengen(sofortAnteil = 0.5, fensterMin = null)
        pruefe(z[1], MarkerPrompt.Line.Spread(0.90, null))
    }

    @Test
    fun `ohne Fundament entfaellt die Fundamentzeile`() {
        // Fundament aus: die ganze Huelle laeuft verteilt, kein Phase B.
        val z = MarkerPrompt.lines(
            MarkerPrompt.Facts(
                upfrontPlannedU = 0.0, phaseARemainderU = 3.0, phaseBBudgetU = 0.0,
                foundationEndMin = null, envelopeU = 3.0, alreadyDeliveredU = 0.0,
                authorizesAgainstModel = true, measuredLow = false, windowMin = 30,
            )
        )
        assertEquals(listOf(MarkerPrompt.Line.Spread(3.0, 30), MarkerPrompt.Line.Total(3.0)), z)
    }

    @Test
    fun `ein stehender Riegel wird als Zustand genannt`() {
        // Tonis Korrektur 25.08. abends: steht beim Druck ein Riegel,
        // werden 0 U angefordert und der Sofortanteil aufgeschoben. Der
        // Dialog sagt die Menge als VORGESEHEN und nennt den Zustand.
        val z = MarkerPrompt.lines(
            MarkerPrompt.Facts(
                upfrontPlannedU = 1.80, phaseARemainderU = 0.0, phaseBBudgetU = 1.20,
                foundationEndMin = 60, envelopeU = 3.0, alreadyDeliveredU = 0.0,
                authorizesAgainstModel = true, measuredLow = false, windowMin = 30,
                deferredReason = "Abwaertsriegel",
            )
        )
        assertEquals(MarkerPrompt.Line.Deferred("Abwaertsriegel"), z.last())
        // Ohne Riegel gibt es die Zeile nicht.
        assertTrue(mengen(sofortAnteil = 1.0).none { it is MarkerPrompt.Line.Deferred })
    }

    @Test
    fun `das Gesamtlimit zieht bereits Geliefertes ab`() {
        val z = mengen(sofortAnteil = 1.0, geliefertU = 1.5)
        pruefe(z.last(), MarkerPrompt.Line.Total(1.50))
    }

    /** ARMEN fragt - das ist die folgenreiche Richtung. */
    @Test
    fun `das Armen verlangt eine Rueckfrage`() =
        assertNotNull(MarkerPrompt.required(armed = false, facts = facts()))

    /** Auch ohne Tief: der Fehldruck kostet Einheiten, unabhaengig vom BG. */
    @Test
    fun `das Armen verlangt sie auch ohne Tief`() =
        assertNotNull(MarkerPrompt.required(armed = false, facts = facts(low = false)))

    /** Und auch dann, wenn die Autorisierung aus ist - dann ist der Text
     *  harmloser, aber der Druck bleibt eine Handlung. */
    @Test
    fun `das Armen verlangt sie auch ohne eingeschaltete Autorisierung`() =
        assertNotNull(MarkerPrompt.required(armed = false, facts = facts(auth = false)))

    /** DIE RUECKNAHME NICHT. Sie kann nur Insulin sparen; eine Huerde vor der
     *  sicheren Handlung waere die falsche Richtung. */
    @Test
    fun `die Ruecknahme verlangt keine Rueckfrage`() {
        assertNull(MarkerPrompt.required(armed = true, facts = facts()))
        assertNull(MarkerPrompt.required(armed = true, facts = facts(low = true)))
    }
}

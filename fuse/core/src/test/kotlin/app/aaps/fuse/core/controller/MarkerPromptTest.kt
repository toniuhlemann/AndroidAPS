package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
        firstStepU = 0.20, envelopeU = 3.0, alreadyDeliveredU = 0.0,
        authorizesAgainstModel = auth, measuredLow = low,
    )

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

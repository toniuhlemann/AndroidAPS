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

    private fun facts(low: Boolean = false) = MarkerPrompt.Facts(measuredLow = low)

    /** ARMEN fragt - das ist die folgenreiche Richtung. */
    @Test
    fun `das Armen verlangt eine Rueckfrage`() =
        assertNotNull(MarkerPrompt.required(armed = false, facts = facts()))

    /** Auch ohne Tief: der Fehldruck kostet Einheiten, unabhaengig vom BG. */
    @Test
    fun `das Armen verlangt sie auch ohne Tief`() =
        assertNotNull(MarkerPrompt.required(armed = false, facts = facts(low = false)))

    /** Und mit Tief erst recht - der Text nennt es dann ausdruecklich. */
    @Test
    fun `das Armen verlangt sie auch im Tief`() =
        assertNotNull(MarkerPrompt.required(armed = false, facts = facts(low = true)))

    /** DIE RUECKNAHME NICHT. Sie kann nur Insulin sparen; eine Huerde vor der
     *  sicheren Handlung waere die falsche Richtung. */
    @Test
    fun `die Ruecknahme verlangt keine Rueckfrage`() {
        assertNull(MarkerPrompt.required(armed = true, facts = facts()))
        assertNull(MarkerPrompt.required(armed = true, facts = facts(low = true)))
    }
}

package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.EarlySupply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Die kompakten Zusammenfassungen (Toni): je Feld der eigene Wert und seine Wirkung auf die Dosierung, kein Feld
 * wiederholt den Text eines anderen. Alle Zahlen synthetisch.
 */
class FruehVersorgungTextTest {

    private fun lage(
        bedarfU: Double = 5.5,
        scaleAfterLow: Double = 0.8,
        ecken: List<Double> = listOf(0.5, 2.0, 3.0, 4.0),
        kurveGueltig: Boolean = true,
        kontoAn: Boolean = true,
        fruehAn: Boolean = true,
    ) = FruehVersorgungText.Lage(
        kontoAn = kontoAn,
        fruehAn = fruehAn,
        ecken = ecken,
        kurveGueltig = kurveGueltig,
        rollen = listOf(EarlySupply.CornerRole.From(0.77), EarlySupply.CornerRole.Never, EarlySupply.CornerRole.From(3.2), EarlySupply.CornerRole.Binding),
        bedarf = EarlySupply.Need(bedarfU, 120),
        bedarfJeZehntelU = listOf(null, null, null, 0.13),
        einstellung = requireNotNull(EarlySupply.Settings.of(1.2, scaleAfterLow, 8.0, 0.4, 1.0, 2.0, 15)),
        expositionU = 2.5,
    )

    @Test
    fun `Kurvenpunkte - der bestimmende zeigt die Wirkung von plus einem Zehntel, die anderen ab wann`() {
        val l = lage()
        assertEquals("4,000 U - bestimmt das Budget: je +0,1 U hier +0,16 U (stabil, jetzt 6,6 U)", FruehVersorgungText.kurvenpunkt(3, l))
        assertEquals("0,500 U - bestimmt das Budget erst ab 0,77 U", FruehVersorgungText.kurvenpunkt(0, l))
        assertEquals(
            "2,000 U - bestimmt das Budget nicht (dafuer muesste er ueber den naechsten Punkt steigen)",
            FruehVersorgungText.kurvenpunkt(1, l),
        )
        // Kein Feld wiederholt den Text eines anderen.
        assertEquals(4, (0..3).map { FruehVersorgungText.kurvenpunkt(it, l) }.toSet().size)
        // Deckelt Budget-Max, aendert der Punkt das Budget nicht.
        assertEquals("4,000 U - bestimmt das Budget, aber Budget-Max deckelt (8,0 U)", FruehVersorgungText.kurvenpunkt(3, lage(bedarfU = 8.0)))
    }

    @Test
    fun `Kurvenpunkte - nicht gesetzt, ungueltig, fruehe Versorgung aus`() {
        assertEquals(
            "0,000 U - Kurve nicht gesetzt (dann gilt die Expertenzeile)",
            FruehVersorgungText.kurvenpunkt(0, lage(ecken = listOf(0.0, 0.0, 0.0, 0.0))),
        )
        assertEquals(
            "0,500 U - Kurve ungueltig: die Werte muessen steigen, Erwartung aus",
            FruehVersorgungText.kurvenpunkt(0, lage(kurveGueltig = false)),
        )
        assertEquals("0,500 U", FruehVersorgungText.kurvenpunkt(0, lage(fruehAn = false)))
    }

    @Test
    fun `Budget, Skalierer und Anfangsanteil rechnen sichtbar`() {
        val l = lage()
        assertEquals("8,0 U - greift nicht (Budget 6,6 U)", FruehVersorgungText.budgetMax(8.0, l))
        assertEquals("8,0 U - deckelt: Budget statt 9,6 U nur 8,0 U", FruehVersorgungText.budgetMax(8.0, lage(bedarfU = 8.0)))
        assertEquals("1,2 - frueher Bedarf 5,5 U x 1,2 = Budget 6,6 U", FruehVersorgungText.skalierer(1.2, false, l))
        assertEquals("0,8 - frueher Bedarf 5,5 U x 0,8 = Budget 4,4 U", FruehVersorgungText.skalierer(0.8, true, l))
        assertEquals("0,0 - nach Tief keine fruehe Versorgung", FruehVersorgungText.skalierer(0.0, true, lage(scaleAfterLow = 0.0)))
        assertEquals(
            "0,40 - am Druck 2,6 U (nach Tief 1,8 U), Rest 4,0 U ab +20 min; Expositionsgrenze 2,5 U begrenzt",
            FruehVersorgungText.anfangsanteil(0.4, l),
        )
    }

    @Test
    fun `Snack-Skalierer zeigt Budget und Anfangsportion, nie mehr als eine Mahlzeit`() {
        val l = lage()
        assertEquals("1,0 - Snack-Budget 5,5 U, am Druck 2,2 U", FruehVersorgungText.snackSkalierer(1.0, l))
        // Groesser als der Mahlzeit-Skalierer: es gilt der der Mahlzeit.
        assertEquals(
            "1,4 - Snack-Budget 6,6 U, am Druck 2,6 U (hoechstens wie Mahlzeit, Skalierer 1,2)",
            FruehVersorgungText.snackSkalierer(1.4, l),
        )
        assertEquals("0,0 - kein Snack-Knopf: ein Snack bekaeme kein Budget", FruehVersorgungText.snackSkalierer(0.0, l))
    }

    @Test
    fun `Portion und je Zeitraum sagen, was sie erlauben`() {
        val l = lage()
        assertEquals("1,0 U je Zyklus - Rest 4,0 U in mindestens 4 Gaben", FruehVersorgungText.portion(1.0, l))
        assertEquals("0,0 U - keine weiteren Portionen", FruehVersorgungText.portion(0.0, l))
        assertEquals(
            "2,0 U je 15 min - hoechstens 2 volle Portionen darin (gleitend, kein Ansparen)",
            FruehVersorgungText.jeZeitraum(2.0, 15, 1.0),
        )
        assertEquals("0,5 U je 15 min - kleiner als eine Portion, begrenzt jede auf 0,5 U", FruehVersorgungText.jeZeitraum(0.5, 15, 1.0))
        assertEquals("0,0 U - keine weiteren Portionen", FruehVersorgungText.jeZeitraum(0.0, 15, 1.0))
    }

    @Test
    fun `Hauptschalter nennt das Budget von heute`() {
        assertEquals("An - Budget heute 6,6 U stabil, 4,4 U nach Tief", FruehVersorgungText.hauptschalter(lage()))
        assertEquals("An, aber wirkungslos: das Erwartungskonto ist aus", FruehVersorgungText.hauptschalter(lage(kontoAn = false)))
        assertEquals(
            "Aus - 'Mahlzeit' und 'Snack' laufen ueber das Konto ohne fruehe Versorgung",
            FruehVersorgungText.hauptschalter(lage(fruehAn = false)),
        )
    }
}

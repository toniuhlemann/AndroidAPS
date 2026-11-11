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
        tageszeit: EarlySupply.TimeOfDayScales? = null,
        tageszeitFehler: String? = null,
    ) = FruehVersorgungText.Lage(
        kontoAn = kontoAn,
        fruehAn = fruehAn,
        ecken = ecken,
        kurveGueltig = kurveGueltig,
        rollen = listOf(EarlySupply.CornerRole.From(0.77), EarlySupply.CornerRole.Never, EarlySupply.CornerRole.From(3.2), EarlySupply.CornerRole.Binding),
        bedarf = EarlySupply.Need(bedarfU, 120),
        bedarfJeZehntelU = listOf(null, null, null, 0.13),
        // Wie im Plugin: ein ungueltiger Tageszeit-Wert macht die Einstellungen ungueltig.
        einstellung = if (tageszeitFehler != null) null else requireNotNull(EarlySupply.Settings.of(1.2, scaleAfterLow, 8.0, 0.4, 1.0, 2.0, 15)),
        expositionU = 2.5,
        tageszeit = tageszeit,
        tageszeitFehler = tageszeitFehler,
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

    /** Kandidat, Toni 11.11.2026: je Fenster die Uhrzeiten, das Budget und - weil er sie mit aendert - die Anfangsportion. */
    @Test
    fun `Skalierer je Tageszeit zeigt Fenster, Budget und Anfangsportion`() {
        val l = lage(tageszeit = requireNotNull(EarlySupply.TimeOfDayScales.of(1.4, 0.0, 1.0, 630, 900)))
        assertEquals(
            "1,40 - 04:00 bis 10:30: Budget 7,7 U, am Druck 3,1 U",
            FruehVersorgungText.tageszeitSkalierer(1.4, EarlySupply.MealTime.MORNING, l),
        )
        assertEquals(
            "0,00 - 10:30 bis 15:00 nicht gesetzt: es gilt der allgemeine Skalierer (1,20)",
            FruehVersorgungText.tageszeitSkalierer(0.0, EarlySupply.MealTime.MIDDAY, l),
        )
        assertEquals(
            "1,00 - 15:00 bis 04:00: Budget 5,5 U, am Druck 2,2 U",
            FruehVersorgungText.tageszeitSkalierer(1.0, EarlySupply.MealTime.EVENING, l),
        )
        // Budget-Max bleibt die Grenze - ein hoeherer Skalierer hebt es nicht.
        assertEquals(
            "1,60 - 04:00 bis 10:30: Budget 8,0 U, am Druck 3,2 U (Budget-Max deckelt)",
            FruehVersorgungText.tageszeitSkalierer(1.6, EarlySupply.MealTime.MORNING, l),
        )
        assertEquals("10:30 - ab hier Mittag; davor, ab 04:00, Fruehstueck", FruehVersorgungText.tageszeitBeginn(630, abend = false, l))
        assertEquals("15:00 - ab hier Abend, bis 04:00 am Morgen", FruehVersorgungText.tageszeitBeginn(900, abend = true, l))
    }

    @Test
    fun `mit Skalierern je Tageszeit sagen die allgemeinen Zeilen, wo sie noch gelten`() {
        val teils = lage(tageszeit = requireNotNull(EarlySupply.TimeOfDayScales.of(1.4, 0.0, 1.0, 630, 900)))
        assertEquals(
            "1,2 - frueher Bedarf 5,5 U x 1,2 = Budget 6,6 U; gilt, wo je Tageszeit keiner gesetzt ist",
            FruehVersorgungText.skalierer(1.2, false, teils),
        )
        assertEquals(
            "0,8 - frueher Bedarf 5,5 U x 0,8 = Budget 4,4 U; hoechstens der Skalierer der Tageszeit",
            FruehVersorgungText.skalierer(0.8, true, teils),
        )
        assertEquals(
            "An - Budget heute 6,6 U stabil, 4,4 U nach Tief (allgemeiner Skalierer; je Tageszeit steht es am Feld)",
            FruehVersorgungText.hauptschalter(teils),
        )
        assertEquals(
            "1,0 - Snack-Budget 5,5 U, am Druck 2,2 U; je Tageszeit hoechstens der Skalierer des Fensters",
            FruehVersorgungText.snackSkalierer(1.0, teils),
        )
        val ueberall = lage(tageszeit = requireNotNull(EarlySupply.TimeOfDayScales.of(1.4, 1.3, 1.0, 630, 900)))
        assertEquals(
            "1,2 - frueher Bedarf 5,5 U x 1,2 = Budget 6,6 U; gilt nicht mehr, je Tageszeit ist ueberall einer gesetzt",
            FruehVersorgungText.skalierer(1.2, false, ueberall),
        )
    }

    /** Aus bleibt aus: ohne gesetzten Skalierer stehen die alten Zeilen, und die Uhrzeiten sagen, dass sie nichts bewirken. */
    @Test
    fun `ohne gesetzten Skalierer je Tageszeit bleiben die Zeilen wie bisher`() {
        val aus = lage(tageszeit = requireNotNull(EarlySupply.TimeOfDayScales.of(0.0, 0.0, 0.0, 630, 900)))
        val alt = lage()
        assertEquals(FruehVersorgungText.skalierer(1.2, false, alt), FruehVersorgungText.skalierer(1.2, false, aus))
        assertEquals(FruehVersorgungText.skalierer(0.8, true, alt), FruehVersorgungText.skalierer(0.8, true, aus))
        assertEquals(FruehVersorgungText.hauptschalter(alt), FruehVersorgungText.hauptschalter(aus))
        assertEquals(
            "0,00 - 04:00 bis 10:30 nicht gesetzt: es gilt der allgemeine Skalierer (1,20)",
            FruehVersorgungText.tageszeitSkalierer(0.0, EarlySupply.MealTime.MORNING, aus),
        )
        assertEquals(
            "10:30 - ohne Wirkung, solange kein Skalierer je Tageszeit gesetzt ist",
            FruehVersorgungText.tageszeitBeginn(630, abend = false, aus),
        )
    }

    /** Verdrehte Uhrzeiten: der Fehler steht an jedem betroffenen Feld und am Hauptschalter - die fruehe Versorgung ist aus. */
    @Test
    fun `verdrehte Uhrzeiten stehen als Fehler am Feld`() {
        val grund = TageszeitSkalierer.fehlerText(EarlySupply.TimeOfDayScales.Violation.TIMES_OUT_OF_ORDER)
        assertEquals("die Uhrzeiten muessen aufsteigen (04:00, dann 'Mittag beginnt', dann 'Abend beginnt')", grund)
        val l = lage(tageszeitFehler = grund)
        assertEquals(
            "1,40 - gilt nicht: $grund. Fruehe Versorgung aus",
            FruehVersorgungText.tageszeitSkalierer(1.4, EarlySupply.MealTime.MORNING, l),
        )
        assertEquals("15:00 - gilt nicht: $grund. Fruehe Versorgung aus", FruehVersorgungText.tageszeitBeginn(900, abend = false, l))
        assertEquals("10:30 - gilt nicht: $grund. Fruehe Versorgung aus", FruehVersorgungText.tageszeitBeginn(630, abend = true, l))
        assertEquals("An, aber ohne fruehe Versorgung: $grund", FruehVersorgungText.hauptschalter(l))
        // Jeder Fehler hat seinen eigenen Text.
        val texte = EarlySupply.TimeOfDayScales.Violation.entries.map(TageszeitSkalierer::fehlerText)
        assertEquals(texte.size, texte.toSet().size)
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

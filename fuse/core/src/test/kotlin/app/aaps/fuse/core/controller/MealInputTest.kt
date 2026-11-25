package app.aaps.fuse.core.controller

import app.aaps.fuse.core.controller.EarlySupply.MealCourse
import app.aaps.fuse.core.controller.EarlySupply.MealInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE MAHLZEIT-EINGABE (Toni 25.11.2026): KH-Menge und Absorptionszeit am Marker - der Verlauf dieser Mahlzeit (Hoehe
 * aus den KH, Zeitachse aus der Absorptionszeit) und was die Buchung daraus macht. Ohne Eingabe bleibt alles, wie es
 * ist. Die Verdrahtung prueft das Gestell (`ErwartungskontoRigTest`). Synthetische Werte, keine Messdaten.
 */
class MealInputTest {

    private val basis = requireNotNull(EarlySupply.Settings.of(1.5, 1.0, 20.0, 0.5, 1.0, 2.0, 10, 0.5))
    private val wirkung: (Double) -> Double = { t -> EarlySupply.orefActionFraction(t, 45.0, 540.0) }

    /** Synthetisch: Eckwerte 1 / 2 / 3 / 4 U - das Kurvenende ist 4 U. */
    private val werte = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0)).values()
    private val kurve = requireNotNull(ExpectationAccount.Curve.of(werte))
    private val params = requireNotNull(ExpectationAccount.Params.of(werte, 120, 90.0, 15, 0.3))

    /** Der fruehe Bedarf je Skalierer 1 - der eingestellten Kurve und eines Verlaufs. */
    private fun bedarf(streckung: Double = 1.0) = EarlySupply.budgetU(kurve.stretched(streckung), 120, 1.0, Double.MAX_VALUE, wirkung)

    private fun buchung(e: EarlySupply.Settings, nachTief: Boolean = false) = EarlySupply.booking(kurve, 120, e, nachTief, wirkung)

    private fun konto(e: EarlySupply.Settings, nachTief: Boolean = false): ExpectationAccount.State {
        val t0 = 1_700_000_000_000L
        return ExpectationAccount.armEarly(
            authId = t0, windowEndTs = t0 + 120 * 60_000L, params = params, settings = e, switches = EarlySupply.Switches.NONE,
            startStable = !nachTief, nowTs = t0, bgMgdl = 100.0, isfMgdlPerU = 50.0, unitAction = wirkung,
        )
    }

    // ---- Die Eingabe ------------------------------------------------------------------------------------------------

    @Test
    fun `die Eingabe - KH durch IC, je 0 heisst keine Angabe`() {
        val beide = MealInput.of(carbsG = 60, icGPerU = 10.0, absorptionMin = 240)
        assertEquals(6.0, beide.carbsInsulinU, 1e-12)
        assertEquals(240, beide.absorptionMin)
        assertTrue(beide.hasCarbs)
        assertTrue(beide.hasAbsorption)
        assertFalse(beide.isEmpty)
        val nurKh = MealInput.of(60, 10.0, 0)
        assertTrue(nurKh.hasCarbs)
        assertFalse(nurKh.hasAbsorption)
        val nurZeit = MealInput.of(0, 10.0, 150)
        assertFalse(nurZeit.hasCarbs)
        assertTrue(nurZeit.hasAbsorption)
        assertEquals(MealInput.NONE, MealInput.of(0, 10.0, 0))
        assertTrue(MealInput.NONE.isEmpty)
    }

    /** Eine Angabe ausserhalb ihrer Grenzen gilt als nicht gemacht - nie ein geratener Wert. Die andere bleibt. */
    @Test
    fun `eine Angabe ausserhalb der Grenzen gilt als nicht gemacht`() {
        for (kh in listOf(-5, 0, EarlySupply.MAX_INPUT_CARBS_G + 1)) {
            assertEquals(MealInput(0.0, 200), MealInput.of(kh, 10.0, 200)) { "KH $kh" }
        }
        assertEquals(30.0, MealInput.of(EarlySupply.MAX_INPUT_CARBS_G, 10.0, 0).carbsInsulinU, 1e-12)
        // Ohne brauchbares IC gibt es kein KH-Insulin.
        for (ic in listOf(null, 0.0, -8.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(MealInput(0.0, 200), MealInput.of(60, ic, 200)) { "IC $ic" }
        }
        for (zeit in listOf(-30, 0, EarlySupply.MAX_INPUT_ABSORPTION_MIN + 1)) {
            assertEquals(MealInput(6.0, 0), MealInput.of(60, 10.0, zeit)) { "Zeit $zeit" }
        }
        assertEquals(EarlySupply.MAX_INPUT_ABSORPTION_MIN, MealInput.of(0, null, EarlySupply.MAX_INPUT_ABSORPTION_MIN).absorptionMin)
    }

    // ---- Die Hoehe --------------------------------------------------------------------------------------------------

    /** KH-Insulin durch Kurvenende - die Kurve erwartet bis zu ihrem Ende die Wirkung von KH / IC. */
    @Test
    fun `der KH-Skalierer hebt die Kurve auf das KH-Insulin`() {
        assertEquals(4.0, kurve.endU, 0.0)
        for (khInsulin in listOf(0.4, 2.0, 4.0, 6.0, 7.9)) {
            val s = requireNotNull(EarlySupply.carbsScale(khInsulin, kurve)) { "bei $khInsulin" }
            assertEquals(khInsulin / 4.0, s, 1e-12)
            assertEquals(khInsulin, s * kurve.endU, 1e-12) { "Kurvenende mal Skalierer ist nicht das KH-Insulin" }
            // Dieselbe Rechnung wie bei der Mahlzeit aus dem Bolusrechner.
            assertEquals(EarlySupply.hclScale(khInsulin, kurve), s, 0.0)
        }
        assertEquals(EarlySupply.MAX_SCALE, EarlySupply.carbsScale(8.0, kurve))
        assertEquals(EarlySupply.MAX_SCALE, EarlySupply.carbsScale(30.0, kurve))
        for (x in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(EarlySupply.carbsScale(x, kurve)) { "bei $x" }
    }

    @Test
    fun `der KH-Skalierer ersetzt den stabilen und deckelt den nach Tief`() {
        assertEquals(basis.copy(scaleStable = 1.8), basis.forCarbs(1.8))
        assertEquals(basis.copy(scaleStable = 0.4, scaleAfterLow = 0.4), basis.forCarbs(0.4))
        for (x in listOf(null, 0.0, -1.0, Double.NaN, EarlySupply.MAX_SCALE + 0.01)) assertSame(basis, basis.forCarbs(x)) { "bei $x" }
    }

    // ---- Der Verlauf ------------------------------------------------------------------------------------------------

    /** Absorptionszeit durch Bezugszeit, in den Grenzen des Verlaufs. Keine Angabe und die Bezugszeit: die Kurve, wie sie ist. */
    @Test
    fun `die Absorptionszeit streckt oder staucht die Zeitachse`() {
        val bezug = EarlySupply.ABSORPTION_REFERENCE_MIN
        for (zeit in listOf(-10, 0, bezug)) assertEquals(1.0, EarlySupply.courseStretch(zeit), 0.0) { "Zeit $zeit" }
        assertEquals(2.0 / 3.0, EarlySupply.courseStretch(120), 1e-12)
        assertEquals(4.0 / 3.0, EarlySupply.courseStretch(240), 1e-12)
        // Schneller als eine Stunde und langsamer als fuenf rechnet das Konto nicht.
        assertEquals(1.0 / 3.0, EarlySupply.courseStretch(EarlySupply.MIN_COURSE_ABSORPTION_MIN), 1e-12)
        assertEquals(1.0 / 3.0, EarlySupply.courseStretch(20), 1e-12)
        assertEquals(5.0 / 3.0, EarlySupply.courseStretch(EarlySupply.MAX_COURSE_ABSORPTION_MIN), 1e-12)
        assertEquals(5.0 / 3.0, EarlySupply.courseStretch(EarlySupply.MAX_INPUT_ABSORPTION_MIN), 1e-12)
    }

    /** Dieselbe Form, derselbe Beginn - nur die Zeitachse: F_T(a) = F(a / Streckung), das Ende entsprechend. */
    @Test
    fun `die gestreckte Kurve ist dieselbe Form auf einer anderen Zeitachse`() {
        assertSame(kurve, kurve.stretched(1.0))
        for (x in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertSame(kurve, kurve.stretched(x)) { "bei $x" }
        for (k in listOf(1.0 / 3.0, 2.0 / 3.0, 4.0 / 3.0, 5.0 / 3.0)) {
            val v = kurve.stretched(k)
            assertEquals(k, v.stretch, 0.0)
            assertEquals(120.0 * k, v.endMin, 1e-12)
            assertEquals(kurve.endU, v.endU, 0.0)
            assertEquals(kurve.values(), v.values())
            // Kein spaeterer Beginn: ab der ersten Minute steigt sie, nur langsamer oder schneller.
            assertEquals(0.0, v.at(0.0), 0.0)
            assertTrue(v.at(1.0) > 0.0) { "Streckung $k: keine Wirkung in der ersten Minute" }
            for (a in listOf(5.0, 17.0, 30.0, 60.0, 90.0, 119.0, 150.0, 200.0, 400.0)) {
                assertEquals(kurve.at(a / k), v.at(a), 1e-12) { "Streckung $k, Alter $a" }
            }
            assertEquals(kurve.endU, v.at(120.0 * k), 1e-12)
            assertEquals(k * 90.0, v.endWithin(90), 1e-12)
            assertEquals(k * 120.0, v.endWithin(120), 1e-12)
        }
        // Ohne Streckung bleibt jede Zahl, wie sie war.
        assertEquals(120.0, kurve.endMin, 0.0)
        assertEquals(90.0, kurve.endWithin(90), 0.0)
    }

    /** Eine langsamere Mahlzeit braucht frueh weniger, eine schnellere mehr - und die Erwartung reicht bis zu ihrem Ende. */
    @Test
    fun `fruehbedarf und Erwartung folgen dem Verlauf`() {
        val bezug = bedarf()
        assertTrue(bedarf(4.0 / 3.0) < bezug) { "langsam: ${bedarf(4.0 / 3.0)} gegen $bezug" }
        assertTrue(bedarf(5.0 / 3.0) < bedarf(4.0 / 3.0))
        assertTrue(bedarf(2.0 / 3.0) > bezug) { "schnell: ${bedarf(2.0 / 3.0)} gegen $bezug" }
        assertTrue(bedarf(1.0 / 3.0) > bedarf(2.0 / 3.0))
        // Die erwartete Wirkung laeuft bis zum Ende des Verlaufs: bei 4 h auch in Minute 140, ohne Streckung nicht.
        val langsam = kurve.stretched(4.0 / 3.0)
        assertTrue(EarlySupply.expectedDriveMgdlPerMin(langsam, 140.0, 1.0, 50.0) > 0.0)
        assertEquals(0.0, EarlySupply.expectedDriveMgdlPerMin(kurve, 140.0, 1.0, 50.0), 0.0)
        assertEquals(0.0, EarlySupply.expectedDriveMgdlPerMin(langsam, 160.0, 1.0, 50.0), 0.0)
        // Die Anforderung nach Minute 120: mit gestrecktem Verlauf noch da, mit der Kurve allein nicht mehr.
        fun anforderung(k: ExpectationAccount.Curve) =
            EarlySupply.demandU(k, 120, 110.0, 1.0, iobU = 0.0, freeIobU = 0.0, existingActionU = { 0.0 }, action = wirkung)
        assertEquals(0.0, anforderung(kurve), 0.0)
        assertTrue(anforderung(langsam) > 0.0)
        // Guard und Spaetwirkung rechnen dieselbe gestreckte Mahlzeit an.
        assertTrue(EarlySupply.expectedRiseMgdl(langsam, 120, 110.0, 40.0, 1.0, 50.0) > 0.0)
        assertEquals(0.0, EarlySupply.expectedRiseMgdl(kurve, 120, 125.0, 40.0, 1.0, 50.0), 0.0)
    }

    /** Das Fenster folgt der Absorptionszeit - begrenzt; ohne Angabe gilt die Einstellung. */
    @Test
    fun `das Mahlzeitfenster folgt der Absorptionszeit in ihren Grenzen`() {
        for (einstellung in listOf(15, 120, 180, 360)) {
            assertEquals(einstellung, EarlySupply.mealWindowMin(0, einstellung))
            assertEquals(einstellung, EarlySupply.mealWindowMin(-5, einstellung))
            // Mit Angabe zaehlt die Einstellung nicht mehr.
            assertEquals(EarlySupply.MIN_ABSORPTION_WINDOW_MIN, EarlySupply.mealWindowMin(60, einstellung))
            assertEquals(150, EarlySupply.mealWindowMin(150, einstellung))
            assertEquals(240, EarlySupply.mealWindowMin(240, einstellung))
            assertEquals(EarlySupply.MAX_ABSORPTION_WINDOW_MIN, EarlySupply.mealWindowMin(480, einstellung))
        }
        assertEquals(ExpectationAccount.CURVE_END_MIN, EarlySupply.MIN_ABSORPTION_WINDOW_MIN)
        // Das Fenster ist nie kuerzer als der Verlauf, dessen Erwartung es traegt.
        for (zeit in EarlySupply.MIN_COURSE_ABSORPTION_MIN..EarlySupply.MAX_INPUT_ABSORPTION_MIN) {
            val ende = kurve.stretched(EarlySupply.courseStretch(zeit)).endMin
            assertTrue(EarlySupply.mealWindowMin(zeit, 180) + 1e-9 >= ende) { "Zeit $zeit: Fenster kuerzer als die Kurve ($ende)" }
        }
    }

    // ---- Die Einstellungen der Mahlzeit -----------------------------------------------------------------------------

    /** OHNE EINGABE WIE BISHER: genau die Einstellungen der Tageszeit - und ohne Tageszeit dasselbe Objekt. */
    @Test
    fun `ohne Eingabe ist die Mahlzeit genau die der Tageszeit`() {
        assertSame(basis, basis.forMeal(null, MealInput.NONE, kurve))
        for (tageszeit in listOf(0.8, 1.2, 1.8)) {
            assertEquals(basis.forTimeOfDay(tageszeit), basis.forMeal(tageszeit, MealInput.NONE, kurve)) { "bei $tageszeit" }
        }
        // Die Bezugszeit ohne KH-Menge ist die Kurve, wie sie ist - keine Eingabe, die etwas aendert.
        assertSame(basis, basis.forMeal(null, MealInput(0.0, EarlySupply.ABSORPTION_REFERENCE_MIN), kurve))
        assertFalse(MealCourse.NONE.byInput)
    }

    @Test
    fun `mit KH zaehlt die Tageszeit nicht mehr, die Absorptionszeit bestimmt den Verlauf`() {
        // 6 U KH-Insulin bei Kurvenende 4 U: Skalierer 1,5.
        val mitKh = basis.forMeal(1.8, MealInput(6.0, 0), kurve)
        assertEquals(basis.forCarbs(1.5).copy(course = MealCourse(1.0, carbsKnown = true)), mitKh)
        assertEquals(mitKh, basis.forMeal(null, MealInput(6.0, 0), kurve))
        val nurZeit = basis.forMeal(1.8, MealInput(0.0, 240), kurve)
        assertEquals(basis.forTimeOfDay(1.8).copy(course = MealCourse(4.0 / 3.0, carbsKnown = false)), nurZeit)
        val beide = basis.forMeal(1.8, MealInput(6.0, 120), kurve)
        assertEquals(basis.forCarbs(1.5).copy(course = MealCourse(2.0 / 3.0, carbsKnown = true)), beide)
        // Der Anteil der Anfangsportion in den Einstellungen bleibt, wie er eingestellt ist - was daraus wird, rechnet die Buchung.
        for (e in listOf(mitKh, nurZeit, beide)) assertEquals(basis.initialShare, e.initialShare, 0.0)
    }

    // ---- Die Buchung ------------------------------------------------------------------------------------------------

    /** OHNE EINGABE BITGLEICH: Budget nach der bisherigen Regel, der Anteil wie eingestellt. */
    @Test
    fun `ohne Eingabe bucht das Konto wie bisher`() {
        for ((einstellung, nachTief) in listOf(basis to false, basis to true, basis.forTimeOfDay(1.8) to false, basis.copy(budgetMaxU = 3.0) to false)) {
            val b = buchung(einstellung, nachTief)
            val skalierer = if (nachTief) einstellung.scaleAfterLow else einstellung.scaleStable
            assertEquals(skalierer, b.scale, 0.0)
            assertEquals(EarlySupply.budgetU(kurve, 120, skalierer, einstellung.budgetMaxU, wirkung), b.budgetU, 0.0)
            assertEquals(einstellung.initialShare, b.initialShare, 0.0)
            assertEquals(MealCourse.NONE, b.course)
            val k = konto(einstellung, nachTief)
            assertEquals(b.budgetU, k.a0U, 0.0)
            assertEquals(1.0, k.curveStretch, 0.0)
            assertFalse(k.byInput)
        }
    }

    /**
     * MIT KH-MENGE: auf die Ankuendigung hin hoechstens die Mahlzeitmenge (KH / IC) - nicht mehr der fruehe Bedarf, der
     * darueber liegt. Die Anfangsportion ist der eingestellte Anteil am fruehen Bedarf, hoechstens die Mahlzeitmenge.
     */
    @Test
    fun `mit KH ist das Budget die Mahlzeitmenge`() {
        val b = buchung(basis.forMeal(1.2, MealInput(6.0, 0), kurve))
        assertEquals(1.5, b.scale, 0.0)
        assertEquals(6.0, b.budgetU, 1e-12)
        assertTrue(b.budgetU < 1.5 * bedarf()) { "die Probe zeigt nichts: der fruehe Bedarf liegt nicht ueber der Mahlzeitmenge" }
        // Die Anfangsportion wie ohne Eingabe bei demselben Skalierer: Anteil mal frueher Bedarf.
        val anfang = b.initialShare * b.budgetU
        assertEquals(0.5 * 1.5 * bedarf(), anfang, 1e-9)
        assertEquals(buchung(basis).initialShare * buchung(basis).budgetU, anfang, 1e-9)
        assertTrue(anfang < 6.0)
        // Eine kleine und eine grosse Mahlzeit: die Menge folgt den KH, der hoechste Skalierer und das Budget-Max halten.
        assertEquals(2.0, buchung(basis.forMeal(1.2, MealInput(2.0, 0), kurve)).budgetU, 1e-12)
        val gross = buchung(basis.forMeal(1.2, MealInput(50.0, 0), kurve))
        assertEquals(EarlySupply.MAX_SCALE, gross.scale, 0.0)
        assertEquals(EarlySupply.MAX_SCALE * 4.0, gross.budgetU, 1e-12)
        assertEquals(3.0, buchung(basis.copy(budgetMaxU = 3.0).forMeal(1.2, MealInput(6.0, 0), kurve)).budgetU, 1e-12)
        // Start im Tief: nie mehr als der eigene "nach Tief" (1,0), und nie mehr als die KH-Menge.
        assertEquals(4.0, buchung(basis.forMeal(1.2, MealInput(50.0, 0), kurve), nachTief = true).budgetU, 1e-12)
        assertEquals(2.0, buchung(basis.forMeal(1.2, MealInput(2.0, 0), kurve), nachTief = true).budgetU, 1e-12)
    }

    /**
     * SCHNELL HEISST FRUEHER, NICHT MEHR: bei kurzer Absorptionszeit waechst die Anfangsportion - bis zur ganzen
     * Mahlzeitmenge, nie darueber -, das Budget nicht. LANGSAM: die Anfangsportion folgt dem kleineren fruehen Bedarf.
     */
    @Test
    fun `eine schnelle Mahlzeit bekommt mehr vorne, eine langsame weniger`() {
        fun anfang(zeit: Int) = buchung(basis.forMeal(1.2, MealInput(6.0, zeit), kurve)).let { it.initialShare * it.budgetU }
        val normal = anfang(0)
        assertEquals(normal, anfang(EarlySupply.ABSORPTION_REFERENCE_MIN), 1e-12)
        // Stetig: je kuerzer, desto mehr - und nie ueber die Mahlzeitmenge.
        var vorher = Double.MAX_VALUE
        for (zeit in 60..300 step 15) {
            val a = anfang(zeit)
            assertTrue(a <= vorher + 1e-9) { "Zeit $zeit: $a nach $vorher" }
            assertTrue(a <= 6.0 + 1e-12) { "Zeit $zeit: Anfangsportion $a ueber der Mahlzeitmenge" }
            vorher = a
        }
        assertEquals(6.0, anfang(60), 1e-12)
        assertTrue(anfang(150) > normal)
        assertTrue(anfang(240) < normal)
        assertEquals(0.5 * 1.5 * bedarf(4.0 / 3.0), anfang(240), 1e-9)
        // Das Budget bleibt die Mahlzeitmenge, gleich wie schnell.
        for (zeit in listOf(60, 120, 180, 240, 300)) {
            assertEquals(6.0, buchung(basis.forMeal(1.2, MealInput(6.0, zeit), kurve)).budgetU, 1e-12) { "Zeit $zeit" }
        }
    }

    /**
     * NUR DIE ABSORPTIONSZEIT (ohne KH-Menge): die Hoehe bleibt die eingestellte. Das Budget ist der fruehe Bedarf des
     * Verlaufs, hoechstens der der eingestellten Kurve - eine schnellere Mahlzeit laesst sich nicht besser decken, indem
     * man mehr gibt. Die Anfangsportion hoechstens die Mahlzeit, fuer die der Skalierer steht.
     */
    @Test
    fun `nur mit Absorptionszeit folgt das Budget dem Verlauf, hoechstens der Kurve`() {
        val ohne = buchung(basis)
        val langsam = buchung(basis.forMeal(null, MealInput(0.0, 240), kurve))
        assertEquals(1.5 * bedarf(4.0 / 3.0), langsam.budgetU, 1e-9)
        assertTrue(langsam.budgetU < ohne.budgetU)
        assertEquals(0.5, langsam.initialShare, 1e-12)
        val schnell = buchung(basis.forMeal(null, MealInput(0.0, 120), kurve))
        assertEquals(ohne.budgetU, schnell.budgetU, 1e-9)
        assertTrue(schnell.initialShare > 0.5)
        assertTrue(schnell.initialShare * schnell.budgetU <= 1.5 * 4.0 + 1e-12) { "Anfangsportion ueber der Mahlzeit des Skalierers" }
        val sehrSchnell = buchung(basis.forMeal(null, MealInput(0.0, 60), kurve))
        assertEquals(1.5 * 4.0, sehrSchnell.initialShare * sehrSchnell.budgetU, 1e-9)
    }

    /** Das Konto haelt den Verlauf fest und liest die Kurve mit ihm. */
    @Test
    fun `das Konto haelt Streckung und Herkunft fest`() {
        val k = konto(basis.forMeal(1.2, MealInput(6.0, 240), kurve))
        assertEquals(4.0 / 3.0, k.curveStretch, 1e-12)
        assertTrue(k.byInput)
        assertEquals(6.0, k.a0U, 1e-12)
        assertEquals(1.5, k.phi0, 0.0)
        assertEquals(werte, k.curveValues)
        val gelesen = requireNotNull(k.curve())
        assertEquals(160.0, gelesen.endMin, 1e-9)
        assertEquals(kurve.at(90.0), gelesen.at(120.0), 1e-12)
        // Ohne Eingabe liest das Konto die Kurve, wie sie eingestellt ist.
        assertEquals(120.0, requireNotNull(konto(basis).curve()).endMin, 0.0)
    }
}

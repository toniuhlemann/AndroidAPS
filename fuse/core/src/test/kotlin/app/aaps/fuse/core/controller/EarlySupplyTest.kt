package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Die fruehe Versorgung (E-034 v2): reine Rechnung. Synthetische Kurve und Modellwerte. */
class EarlySupplyTest {

    private val gipfel = 45.0
    private val wirkdauer = 540.0
    private val wirkung: (Double) -> Double = { t -> EarlySupply.orefActionFraction(t, gipfel, wirkdauer) }

    /** Synthetische Kurve: gleichmaessig 1 U je 30 min bis 120 min. */
    private val kurve = requireNotNull(ExpectationAccount.Curve.ausEckwerten(1.0, 2.0, 3.0, 4.0))

    @Test
    fun `die Insulinwirkung beginnt bei 0, steigt monoton und ist am Ende vollstaendig`() {
        assertEquals(0.0, wirkung(0.0), 0.0)
        var vorher = 0.0
        for (t in 5..540 step 5) {
            val c = wirkung(t.toDouble())
            assertTrue(c + 1e-12 >= vorher, "nicht monoton bei $t")
            vorher = c
        }
        assertEquals(1.0, wirkung(540.0), 1e-9)
        // Bei Gipfel 45 und neun Stunden Wirkdauer wirkt nach einer Stunde etwa zwei Fuenftel.
        assertEquals(0.39, wirkung(60.0), 0.02)
    }

    @Test
    fun `das Budget ist die fruehe Gabe, die die Kurve ab 30 min rechtzeitig deckt - skaliert und gedeckelt`() {
        val roh = (30..120 step 5).maxOf { t -> kurve.at(t.toDouble()) / wirkung(t.toDouble()) }
        assertEquals(roh, EarlySupply.budgetU(kurve, 120, 1.0, 20.0, wirkung), 1e-9)
        assertEquals(1.5 * roh, EarlySupply.budgetU(kurve, 120, 1.5, 20.0, wirkung), 1e-9)
        assertEquals(3.0, EarlySupply.budgetU(kurve, 120, 1.0, 3.0, wirkung), 1e-9)
        assertEquals(0.0, EarlySupply.budgetU(kurve, 120, 0.0, 20.0, wirkung), 0.0)
        // Die fruehe Gabe ist groesser als die Wirkung bis 120 min, weil Insulin verzoegert wirkt.
        assertTrue(roh > kurve.endU)
    }

    @Test
    fun `am Druck ohne vorhandenes Insulin ist die Anforderung das ungedeckelte Budget`() {
        val x = EarlySupply.demandU(kurve, 120, 0.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung)
        assertEquals(EarlySupply.budgetU(kurve, 120, 1.0, 20.0, wirkung), x, 1e-9)
    }

    @Test
    fun `freies vorhandenes Insulin senkt die Anforderung, Insulin fuer den Zielabstand nicht`() {
        val ohne = EarlySupply.demandU(kurve, 120, 0.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung)
        // 2 U an Bord, deren Wirkung wie eine Gabe jetzt verlaeuft.
        val vorhanden: (Double) -> Double = { t -> 2.0 * wirkung(t) }
        val frei = EarlySupply.freeIobU(2.0, 100.0, 100.0, 50.0)
        assertEquals(2.0, frei, 1e-12)
        val mitFrei = EarlySupply.demandU(kurve, 120, 0.0, 1.0, 2.0, frei, vorhanden, wirkung)
        assertEquals(ohne - 2.0, mitFrei, 1e-9)
        // Liegt der Zucker 100 mg/dl ueber Ziel (ISF 50 = 2 U), gehoert das ganze IOB dem Zielabstand.
        val gebunden = EarlySupply.freeIobU(2.0, 200.0, 100.0, 50.0)
        assertEquals(0.0, gebunden, 0.0)
        assertEquals(ohne, EarlySupply.demandU(kurve, 120, 0.0, 1.0, 2.0, gebunden, vorhanden, wirkung), 1e-9)
    }

    @Test
    fun `weniger als 30 Minuten bis zum Ende gibt es nichts mehr rechtzeitig abzudecken`() {
        assertEquals(0.0, EarlySupply.demandU(kurve, 120, 95.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung), 0.0)
        assertTrue(EarlySupply.demandU(kurve, 120, 85.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung) > 0.0)
    }

    @Test
    fun `die Anpassung verkleinert nur, nie vor 20 Minuten und nie ueber den Skalierer`() {
        assertEquals(1.5, EarlySupply.effectiveScale(1.5, 10.0, 0.0, true, kurve), 0.0)
        // Bei 30 min erwartet 1 U, beobachtet 0,5 U: halber Skalierer.
        assertEquals(0.75, EarlySupply.effectiveScale(1.5, 30.0, 0.5, true, kurve), 1e-9)
        // Mehr als erwartet: bleibt beim Skalierer.
        assertEquals(1.5, EarlySupply.effectiveScale(1.5, 30.0, 3.0, true, kurve), 0.0)
        // Ungueltige Beobachtung: unveraendert (die Ruecknahme bei +30 ist eigens zustaendig).
        assertEquals(1.5, EarlySupply.effectiveScale(1.5, 30.0, 0.0, false, kurve), 0.0)
    }

    @Test
    fun `Portionen - Anfangsanteil und jede weitere Portion nehmen das Kleinste, nie negativ`() {
        assertEquals(3.0, EarlySupply.initialPortionU(6.0, 0.5, 10.0), 1e-12)
        assertEquals(2.0, EarlySupply.initialPortionU(6.0, 0.5, 2.0), 1e-12)
        assertEquals(0.0, EarlySupply.initialPortionU(6.0, 0.5, -1.0), 0.0)
        assertEquals(1.0, EarlySupply.portionU(5.0, 4.0, 1.0, 3.0), 1e-12)
        assertEquals(0.5, EarlySupply.portionU(5.0, 0.5, 1.0, 3.0), 1e-12)
        assertEquals(0.0, EarlySupply.portionU(5.0, 4.0, 1.0, 0.0), 0.0)
    }

    @Test
    fun `die Kante mit Erwartung zaehlt positiven Antrieb nicht doppelt und behaelt das Fallen`() {
        // Ohne Antrieb: die ganze Erwartung kommt hinzu.
        assertEquals(130.0, EarlySupply.liftedEdge(100.0, 100.0, 30.0), 1e-12)
        // Positiver Antrieb schon in der Kante: nur, was die Erwartung darueber hinaus bringt.
        assertEquals(130.0, EarlySupply.liftedEdge(120.0, 100.0, 30.0), 1e-12)
        assertEquals(140.0, EarlySupply.liftedEdge(140.0, 100.0, 30.0), 1e-12)
        // Gemessenes Fallen bleibt voll erhalten.
        assertEquals(110.0, EarlySupply.liftedEdge(80.0, 100.0, 30.0), 1e-12)
        // Keine Erwartung: unveraendert.
        assertEquals(80.0, EarlySupply.liftedEdge(80.0, 100.0, 0.0), 0.0)
    }

    @Test
    fun `Guard und Schwanz sehen die Mahlzeit hoechstens wie eingestellt`() {
        assertEquals(1.0, EarlySupply.guardScale(1.8), 0.0)
        assertEquals(0.6, EarlySupply.guardScale(0.6), 0.0)
        assertEquals(0.0, EarlySupply.guardScale(-1.0), 0.0)
        assertEquals(0.0, EarlySupply.guardScale(Double.NaN), 0.0)
        // Anstieg nach der Kurve endet am Ende der Vorschau.
        val bis120 = EarlySupply.expectedRiseMgdl(kurve, 120, 100.0, 60.0, 1.0, 50.0)
        assertEquals((4.0 - kurve.at(100.0)) * 50.0, bis120, 1e-9)
        assertEquals(0.0, EarlySupply.expectedRiseMgdl(kurve, 60, 70.0, 30.0, 1.0, 50.0), 0.0)
    }

    @Test
    fun `die Bahn mit Erwartung hebt jede Punktkante, der Anker bleibt`() {
        val anker = 1_700_000_000_000L
        // Kein Antrieb, Insulin senkt 0,5 mg/dl je Minute; die untere Kante liegt 0,2 mg/dl je Minute tiefer.
        val punkte = (1..120).map { i ->
            app.aaps.fuse.core.predictor.TrajectoryPoint(
                offsetMin = i, tsMs = anker + i * 60_000L, meanBg = 120.0 - 0.5 * i, lowerBg = 120.0 - 0.7 * i,
                bgiRate = -0.5, driveMean = 0.0, driveLower = -0.2, lowerBgPriorFree = 120.0 - 0.7 * i,
            )
        }
        val bahn = app.aaps.fuse.core.predictor.PredictorResult(
            points = punkte, predictionAnchorTs = anker, bgAtAnchor = 120.0,
            minMeanBg = punkte.last().meanBg, minLowerBg = punkte.last().lowerBg, timeToMinLowerMin = 120,
            bgAtHorizonMean = punkte.last().meanBg, bgAtHorizonLower = punkte.last().lowerBg,
            lineageKind = "VIRTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 240.0, iobArrayGridMin = 1.0, modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
            minLowerBgPriorFree = punkte.last().lowerBg, bgAtHorizonLowerPriorFree = punkte.last().lowerBg,
        )
        val mit = EarlySupply.withExpectation(bahn, 0.0, kurve, 120, 1.0, 50.0)
        assertEquals(120.0, mit.bgAtAnchor, 0.0)
        for (p in mit.points) {
            // Kurve 1 U je 30 min, ISF 50: Anstieg 5/3 mg/dl je Minute; die Kante faellt um 0,7.
            val erwartet = 120.0 - 0.7 * p.offsetMin + 50.0 * kurve.at(p.offsetMin.toDouble())
            assertEquals(erwartet, p.safetyLowerBg, 1e-9)
            assertTrue(p.lowerBg >= p.safetyLowerBg && p.meanBg >= p.lowerBg)
        }
        assertEquals(120.0, mit.minSafetyLowerBg, 1e-9)
        assertEquals(mit.points.last().safetyLowerBg, mit.bgAtHorizonSafetyLower, 0.0)
        // Schwanzkante am Horizont: dieselbe Regel.
        assertEquals(
            mit.points.last().safetyLowerBg,
            EarlySupply.tailLowerWithExpectation(bahn.bgAtHorizonSafetyLower, bahn, 0.0, kurve, 120, 1.0, 50.0), 1e-9,
        )
    }

    @Test
    fun `Guard und Schwanz rechnen nur die bestaetigte Erwartung an`() {
        // Vor +20 nichts, auch mit grossem Skalierer.
        assertEquals(0.0, EarlySupply.confirmedGuardScale(1.5, 10.0, 5.0, true, kurve), 0.0)
        // Bei 30 min erwartet 1 U: beobachtet 0,5 U = halbe Anrechnung; mehr als erwartet = hoechstens die Kurve.
        assertEquals(0.5, EarlySupply.confirmedGuardScale(1.0, 30.0, 0.5, true, kurve), 1e-12)
        assertEquals(1.0, EarlySupply.confirmedGuardScale(1.5, 30.0, 3.0, true, kurve), 0.0)
        // Ausbleibende Mahlzeit: nichts.
        assertEquals(0.0, EarlySupply.confirmedGuardScale(1.0, 30.0, -0.2, true, kurve), 0.0)
        // Ungueltige Beobachtung bestaetigt nichts.
        assertEquals(0.0, EarlySupply.confirmedGuardScale(1.0, 30.0, 1.0, false, kurve), 0.0)
    }

    @Test
    fun `Bedarf und bestimmender Punkt - und ab wann ein anderer Eckpunkt bestimmt`() {
        // Synthetische Kurve, flacher Beginn: der 120er-Punkt bestimmt den Bedarf.
        val ecken = doubleArrayOf(0.5, 2.0, 3.0, 4.0)
        val k = requireNotNull(ExpectationAccount.Curve.ausEckwerten(0.5, 2.0, 3.0, 4.0))
        val n = requireNotNull(EarlySupply.need(k, 120, wirkung))
        assertEquals(120, n.bindingTauMin)
        assertEquals(EarlySupply.budgetU(k, 120, 1.0, 20.0, wirkung), n.rawU, 1e-9)
        assertEquals(EarlySupply.CornerRole.Binding, EarlySupply.cornerRole(ecken, 3, 120, wirkung))
        // Der 30er-Punkt bestimmt erst, wenn er den 120er-Bedarf einholt.
        val r30 = EarlySupply.cornerRole(ecken, 0, 120, wirkung)
        assertTrue(r30 is EarlySupply.CornerRole.From) { "$r30" }
        val ab = (r30 as EarlySupply.CornerRole.From).valueU
        assertEquals(n.rawU * wirkung(30.0), ab, 1e-3)
        val darueber = requireNotNull(ExpectationAccount.Curve.ausEckwerten(ab + 0.05, 2.0, 3.0, 4.0))
        assertTrue(requireNotNull(EarlySupply.need(darueber, 120, wirkung)).rawU > n.rawU)
        val darunter = requireNotNull(ExpectationAccount.Curve.ausEckwerten(ab - 0.05, 2.0, 3.0, 4.0))
        assertEquals(n.rawU, requireNotNull(EarlySupply.need(darunter, 120, wirkung)).rawU, 1e-9)
        // Steiler Beginn: der 30er-Punkt bestimmt, der 120er nicht mehr.
        val steil = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(30, requireNotNull(EarlySupply.need(kurve, 120, wirkung)).bindingTauMin)
        assertEquals(EarlySupply.CornerRole.Binding, EarlySupply.cornerRole(steil, 0, 120, wirkung))
        assertTrue(EarlySupply.cornerRole(steil, 3, 120, wirkung) is EarlySupply.CornerRole.From)
        // Kann er bis zum naechsten Eckwert nie aufholen: nie.
        assertEquals(EarlySupply.CornerRole.Never, EarlySupply.cornerRole(doubleArrayOf(0.1, 0.2, 3.0, 4.0), 0, 120, wirkung))
        // Ungueltige Kurve (nicht steigend): keine Rolle.
        assertNull(EarlySupply.cornerRole(doubleArrayOf(3.0, 2.0, 3.0, 4.0), 0, 120, wirkung))
    }

    @Test
    fun `die beobachtete Fortsetzung - Rate mal Abklingen zwischen zwei Zeitpunkten, nie negativ`() {
        val f = EarlySupply.Continuation(0.05, 60.0)
        assertEquals(0.05 * 60.0 * (1.0 - kotlin.math.exp(-1.0)), f.amountU(0.0, 60.0), 1e-12)
        assertEquals(0.05 * 60.0 * (kotlin.math.exp(-0.5) - kotlin.math.exp(-2.0)), f.amountU(30.0, 120.0), 1e-12)
        assertEquals(0.0, f.amountU(60.0, 30.0), 0.0)
        assertEquals(0.0, EarlySupply.Continuation(-0.05, 60.0).amountU(0.0, 60.0), 0.0)
        assertEquals(0.0, EarlySupply.Continuation(Double.NaN, 60.0).amountU(0.0, 60.0), 0.0)
        assertEquals(0.0, EarlySupply.Continuation(0.05, 0.0).amountU(0.0, 60.0), 0.0)
    }

    /** Schritt 2 (Toni): solange die Mahlzeit laeuft, endet die Anforderung nicht bei Minute 90. */
    @Test
    fun `laeuft die Mahlzeit, endet die Anforderung nicht bei Minute 90 - sie folgt der Beobachtung`() {
        val f = EarlySupply.Continuation(0.05, 60.0)
        // Ohne Fortsetzung: ab Minute 90 gibt es nichts mehr rechtzeitig abzudecken.
        assertEquals(0.0, EarlySupply.demandU(kurve, 120, 100.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung), 0.0)
        // Mit: jenseits der Kurve der Rest der Kurve bis zu ihrem Ende plus die Fortsetzung danach (ab Minute 120),
        // gedeckt ab 30 min, Vorschau bis 120 min.
        val rest = kurve.at(120.0) - kurve.at(100.0)
        val erwartet = (30..120 step 5).maxOf { t -> (rest + f.amountU(20.0, t.toDouble())) / wirkung(t.toDouble()) }
        assertEquals(erwartet, EarlySupply.demandU(kurve, 120, 100.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung, f), 1e-9)
        // Nach dem Kurvenende traegt sie allein, ab jetzt.
        val spaet = (30..120 step 5).maxOf { t -> f.amountU(0.0, t.toDouble()) / wirkung(t.toDouble()) }
        assertEquals(spaet, EarlySupply.demandU(kurve, 120, 150.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung, f), 1e-9)
        // Freies vorhandenes Insulin zaehlt wie bisher.
        val vorhanden: (Double) -> Double = { t -> 1.0 * wirkung(t) }
        assertTrue(EarlySupply.demandU(kurve, 120, 150.0, 1.0, 1.0, 1.0, vorhanden, wirkung, f) < spaet - 1e-9)
        // Faellt die Fortsetzung weg (die Mahlzeit ist vorbei), faellt die Anforderung mit.
        assertEquals(
            0.0, EarlySupply.demandU(kurve, 120, 150.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung, EarlySupply.Continuation(0.0, 60.0)), 0.0,
        )
    }

    /**
     * Geraetebefund: laeuft die Mahlzeit, darf die Anforderung nicht einbrechen, wenn der letzte Deckungspunkt die
     * Kurve verlaesst (Minute 90) - jenseits des Kurvenendes traegt der Rest der Kurve weiter, dazu die Fortsetzung.
     */
    @Test
    fun `bei Minute 90 bricht die Anforderung einer laufenden Mahlzeit nicht ein`() {
        val f = EarlySupply.Continuation(0.05, 60.0)
        val werte = listOf(85.0, 89.0, 90.0, 90.5, 91.0, 92.0, 95.0).map { a ->
            a to EarlySupply.demandU(kurve, 120, a, 1.0, 0.0, 0.0, { 0.0 }, wirkung, f)
        }
        for ((a, x) in werte.zipWithNext()) {
            assertTrue(x.second >= 0.85 * a.second) { "Einbruch zwischen Minute ${a.first} (${a.second}) und ${x.first} (${x.second})" }
        }
        // Mit Skalierer: der Rest der Kurve skaliert wie innerhalb, die Fortsetzung nicht.
        val halb = EarlySupply.demandU(kurve, 120, 91.0, 0.5, 0.0, 0.0, { 0.0 }, wirkung, f)
        val voll = EarlySupply.demandU(kurve, 120, 91.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung, f)
        assertTrue(halb < voll && halb > 0.0)
    }

    /** Guard und Schwanz rechnen jenseits des Kurvenendes dieselbe Fortsetzung an wie der Bedarf - nur bestaetigt. */
    @Test
    fun `Guard und Schwanz rechnen die beobachtete Fortsetzung jenseits der Kurve an`() {
        val f = EarlySupply.Continuation(0.05, 60.0)
        val isf = 50.0
        // Innerhalb der Kurve unveraendert.
        assertEquals(
            EarlySupply.expectedRiseMgdl(kurve, 120, 60.0, 30.0, 1.0, isf),
            EarlySupply.expectedRiseMgdl(kurve, 120, 60.0, 30.0, 1.0, isf, f), 0.0,
        )
        // Jenseits: der Rest der Kurve plus die Fortsetzung ab dem Kurvenende.
        val ohne = EarlySupply.expectedRiseMgdl(kurve, 120, 100.0, 60.0, 1.0, isf)
        assertEquals(ohne + f.amountU(20.0, 60.0) * isf, EarlySupply.expectedRiseMgdl(kurve, 120, 100.0, 60.0, 1.0, isf, f), 1e-9)
        // Hoechstens 120 min nach jetzt - wie im Bedarf.
        assertEquals(
            ohne + f.amountU(20.0, 120.0) * isf, EarlySupply.expectedRiseMgdl(kurve, 120, 100.0, 300.0, 1.0, isf, f), 1e-9,
        )
        // Vor der Bestaetigung (Skala 0) zaehlt auch die Fortsetzung nicht.
        assertEquals(0.0, EarlySupply.expectedRiseMgdl(kurve, 120, 100.0, 60.0, 0.0, isf, f), 0.0)
        // Die Fortsetzung zaehlt nicht mit der Skala - sie ist schon Beobachtung.
        assertEquals(
            0.5 * (kurve.at(120.0) - kurve.at(100.0)) * isf + f.amountU(20.0, 60.0) * isf,
            EarlySupply.expectedRiseMgdl(kurve, 120, 100.0, 60.0, 0.5, isf, f), 1e-9,
        )
    }

    @Test
    fun `die Bahn und die Schwanzkante mit Erwartung tragen die Fortsetzung jenseits der Kurve`() {
        val anker = 1_700_000_000_000L
        val punkte = (1..120).map { i ->
            app.aaps.fuse.core.predictor.TrajectoryPoint(
                offsetMin = i, tsMs = anker + i * 60_000L, meanBg = 120.0 - 0.5 * i, lowerBg = 120.0 - 0.7 * i,
                bgiRate = -0.5, driveMean = 0.0, driveLower = -0.2, lowerBgPriorFree = 120.0 - 0.7 * i,
            )
        }
        val bahn = app.aaps.fuse.core.predictor.PredictorResult(
            points = punkte, predictionAnchorTs = anker, bgAtAnchor = 120.0,
            minMeanBg = punkte.last().meanBg, minLowerBg = punkte.last().lowerBg, timeToMinLowerMin = 120,
            bgAtHorizonMean = punkte.last().meanBg, bgAtHorizonLower = punkte.last().lowerBg,
            lineageKind = "VIRTUAL", trajectoryContentHash = "h",
            iobArraySpanMin = 240.0, iobArrayGridMin = 1.0, modelTailBeyondArrayMin = 0.0, inputSkewMs = 0L,
            minLowerBgPriorFree = punkte.last().lowerBg, bgAtHorizonLowerPriorFree = punkte.last().lowerBg,
        )
        val f = EarlySupply.Continuation(0.05, 60.0)
        // Markeralter 100: die Kurve endet 20 min nach dem Anker, danach traegt die Fortsetzung.
        val ohne = EarlySupply.withExpectation(bahn, 100.0, kurve, 120, 1.0, 50.0)
        val mit = EarlySupply.withExpectation(bahn, 100.0, kurve, 120, 1.0, 50.0, f)
        for ((a, b) in ohne.points.zip(mit.points)) {
            val zusatz = f.amountU(20.0, a.offsetMin.toDouble()) * 50.0
            assertEquals(a.safetyLowerBg + zusatz, b.safetyLowerBg, 1e-9)
        }
        assertEquals(
            mit.points.last().safetyLowerBg,
            EarlySupply.tailLowerWithExpectation(bahn.bgAtHorizonSafetyLower, bahn, 100.0, kurve, 120, 1.0, 50.0, f), 1e-9,
        )
        // Ohne Fortsetzung: bitgleich zur bisherigen Rechnung.
        assertEquals(
            ohne.points.last().safetyLowerBg,
            EarlySupply.tailLowerWithExpectation(bahn.bgAtHorizonSafetyLower, bahn, 100.0, kurve, 120, 1.0, 50.0, null), 0.0,
        )
    }

    @Test
    fun `innerhalb der Kurve bleibt der Kurvenanteil - ohne Fortsetzung wie bisher`() {
        val f = EarlySupply.Continuation(0.05, 60.0)
        for (a in listOf(0.0, 12.5, 37.0, 60.0, 85.0, 89.9)) {
            val ohne = EarlySupply.demandU(kurve, 120, a, 1.0, 0.0, 0.0, { 0.0 }, wirkung)
            assertEquals(ohne, EarlySupply.demandU(kurve, 120, a, 1.0, 0.0, 0.0, { 0.0 }, wirkung, null), 0.0)
            // Mit Fortsetzung nie weniger: die Kurvenpunkte bleiben, dazu kommen Punkte jenseits der Kurve.
            assertTrue(EarlySupply.demandU(kurve, 120, a, 1.0, 0.0, 0.0, { 0.0 }, wirkung, f) >= ohne)
        }
        // Am Druck reicht keine Deckung ueber das Kurvenende hinaus - dieselbe Anforderung.
        assertEquals(
            EarlySupply.demandU(kurve, 120, 0.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung),
            EarlySupply.demandU(kurve, 120, 0.0, 1.0, 0.0, 0.0, { 0.0 }, wirkung, f), 0.0,
        )
    }

    /** Anzeige (Graph F.DRV): die vom Konto erwartete Mahlzeitwirkung. */
    @Test
    fun `die erwartete Mahlzeitwirkung ist die Steigung der Kurve mal Skalierer mal ISF, ausserhalb 0`() {
        // Synthetische Kurve: 1 U je 30 min, also Steigung 1/30 U/min.
        assertEquals(50.0 / 30.0, EarlySupply.expectedDriveMgdlPerMin(kurve, 45.0, 1.0, 50.0), 1e-9)
        assertEquals(25.0 / 30.0, EarlySupply.expectedDriveMgdlPerMin(kurve, 45.0, 0.5, 50.0), 1e-9)
        assertEquals(0.0, EarlySupply.expectedDriveMgdlPerMin(kurve, 120.0, 1.0, 50.0), 0.0)
        assertEquals(0.0, EarlySupply.expectedDriveMgdlPerMin(kurve, -1.0, 1.0, 50.0), 0.0)
        assertEquals(0.0, EarlySupply.expectedDriveMgdlPerMin(kurve, 45.0, 0.0, 50.0), 0.0)
        assertEquals(0.0, EarlySupply.expectedDriveMgdlPerMin(kurve, 45.0, 1.0, Double.NaN), 0.0)
    }

    @Test
    fun `Einstellungen ausserhalb der Grenzen schalten die fruehe Versorgung aus`() {
        assertNotNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.5, 4.0, 10))
        assertNull(EarlySupply.Settings.of(2.5, 0.5, 8.0, 0.5, 1.5, 4.0, 10))
        assertNull(EarlySupply.Settings.of(1.0, 0.5, 25.0, 0.5, 1.5, 4.0, 10))
        assertNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 1.2, 1.5, 4.0, 10))
        assertNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.5, 4.0, 2))
        assertNull(EarlySupply.Settings.of(Double.NaN, 0.5, 8.0, 0.5, 1.5, 4.0, 10))
        // Nachschlag (Toni 23.10.): Anteil des Budgets 0..1.
        assertEquals(0.5, EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.5, 4.0, 10, 0.5)?.topUpShare)
        assertNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.5, 4.0, 10, 1.5))
        assertNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.5, 4.0, 10, -0.1))
        assertNull(EarlySupply.Settings.of(1.0, 0.5, 8.0, 0.5, 1.5, 4.0, 10, Double.NaN))
    }
}

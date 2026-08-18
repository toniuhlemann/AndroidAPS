package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER ERWARTUNGS-LEDGER, erster Baustein des Sackgassenwaechters.
 *
 * Er misst und entscheidet nichts - deshalb pruefen diese Tests keine
 * Dosierung, sondern die BEWEISFUEHRUNG: was zaehlt als Nachweis, dass die
 * versprochene Senkung ausgeblieben ist, und was ausdruecklich nicht.
 */
class ExpectationLedgerTest {

    private val t0 = 1_787_000_000_000L
    private val H = 30
    private val SEG = 1L
    private val CFG = "cfg#1"
    private val REV = 100L
    private val KORR = ExpectationLedger.ExpectationContext.CORRECTION

    private fun eintrag(
        source: Long = t0,
        seg: Long = SEG,
        anchor: Double = 200.0,
        mean: Double = 150.0,
        rev: Long = REV,
        kontext: ExpectationLedger.ExpectationContext = ExpectationLedger.ExpectationContext.CORRECTION,
    ) = ExpectationLedger.issue(
        source, seg, anchor, mean, H, configGeneration = CFG, interventionRevision = rev,
        context = kontext,
    )!!

    private fun probe(
        ts: Long,
        mgdl: Double,
        seg: Long = SEG,
        healthy: Boolean = true,
        rev: Long = REV,
        cfg: String = CFG,
    ) = ExpectationLedger.Sample(ts, mgdl, seg, healthy, rev, cfg)

    private fun rechne(
        entries: List<ExpectationLedger.Entry>,
        now: Long,
        samples: List<ExpectationLedger.Sample>,
        consumed: Set<ExpectationLedger.SampleId> = emptySet(),
    ) = ExpectationLedger.settle(entries, now, samples, consumed)

    // ---- Einreihen ------------------------------------------------------

    /** NUR SENKUNGEN DER MITTELBAHN sind widerlegbar. */
    @Test
    fun `nur eine behauptete Senkung wird eingereiht`() {
        assertTrue(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, H, CFG, REV, KORR) != null)
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 200.0, H, CFG, REV, KORR), "unveraendert")
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 240.0, H, CFG, REV, KORR), "ein Anstieg")
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 194.0, H, CFG, REV, KORR), "6 mg/dl sind Rauschen")
    }

    @Test
    fun `unbrauchbare Eingaben werden nicht eingereiht`() {
        assertNull(ExpectationLedger.issue(t0, SEG, null, 150.0, H, CFG, REV, KORR))
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, null, H, CFG, REV, KORR))
        assertNull(ExpectationLedger.issue(t0, SEG, Double.NaN, 150.0, H, CFG, REV, KORR))
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, 0, CFG, REV, KORR), "ohne Horizont")
    }

    /** OHNE VERGLEICHBARKEITSKENNUNG KEIN EINTRAG. Eine Garantie, die man
     *  weglassen darf, ist keine - sonst steht spaeter ein Ergebnis in der
     *  Datei, das mit nichts vergleichbar ist. */
    @Test
    fun `ohne Konfigurationskennung wird nicht eingereiht`() {
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, H, "", REV, KORR))
        assertNull(ExpectationLedger.issue(t0, SEG, 200.0, 150.0, H, "   ", REV, KORR))
    }

    /**
     * DIE SICHERHEITSUNTERGRENZE IST KEIN VERSPRECHEN. Die Mittelbahn soll
     * eintreten, die Untergrenze gerade NICHT. Sie faehrt als Kontext mit
     * und geht in kein Urteil ein.
     */
    @Test
    fun `die Sicherheitsuntergrenze faehrt als Kontext mit, nicht als Versprechen`() {
        val e = ExpectationLedger.issue(
            t0, SEG, 200.0, 150.0, H, CFG, REV, KORR,
            safetyLowerPredictedMgdl = 40.0, lambda = 1.0, discountMgdl = -110.8, bgiMgdl = -127.7,
        )!!
        assertEquals(50.0, e.promisedDropMgdl, 1e-9, "das Versprechen ist die MITTELBAHN")
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 180.0)))
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
        assertEquals(30.0, out[0].meanErrorMgdl!!, 1e-9, "gemessen gegen die Mittelbahn")
        assertEquals(140.0, out[0].distanceFromSafetyLowerMgdl!!, 1e-9, "reine Diagnose")
    }

    // ---- Abrechnen: ZEITLICHE ZUORDNUNG ----------------------------------

    @Test
    fun `vor der Faelligkeit wird nichts abgerechnet`() {
        val e = eintrag()
        val (out, offen, _) = rechne(listOf(e), t0 + 10 * 60_000L, listOf(probe(t0 + 10 * 60_000L, 190.0)))
        assertTrue(out.isEmpty())
        assertEquals(listOf(e), offen)
    }

    @Test
    fun `eine ausgebliebene Senkung wird als MISSED verbucht`() {
        val e = eintrag()
        val (out, offen, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0)))
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
        assertEquals(55.0, out[0].meanErrorMgdl!!, 1e-9)
        assertTrue(offen.isEmpty())
    }

    @Test
    fun `eine eingetroffene Senkung wird als MET verbucht`() {
        val e = eintrag()
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 148.0)))
        assertEquals(ExpectationLedger.Verdict.MET, out[0].verdict)
    }

    @Test
    fun `ein knappes Verfehlen gilt noch als eingetroffen`() {
        val e = eintrag()
        assertEquals(
            ExpectationLedger.Verdict.MET,
            rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 153.0))).outcomes[0].verdict,
        )
        assertEquals(
            ExpectationLedger.Verdict.MISSED,
            rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 158.0))).outcomes[0].verdict,
        )
    }

    /** Nach einer Luecke darf kein spaeterer Wert rueckwirkend gelten. */
    @Test
    fun `nach einer Luecke wird kein spaeterer Wert rueckwirkend verwendet`() {
        val e = listOf(eintrag(t0), eintrag(t0 + 5 * 60_000L), eintrag(t0 + 10 * 60_000L))
        val spaet = probe(t0 + 60 * 60_000L, 205.0)
        val (out, _, _) = rechne(e, t0 + 60 * 60_000L, listOf(spaet))
        assertTrue(
            out.all { it.verdict == ExpectationLedger.Verdict.UNVERIFIABLE },
            "keine darf am spaeten Wert abgerechnet werden: ${out.map { it.verdict }}",
        )
    }

    /**
     * EIN MESSWERT WIRD HOECHSTENS EINMAL VERBRAUCHT (Tonis Befund).
     *
     * Bei 1-min-Prognosen und 150 s Toleranz kann derselbe Punkt fuer bis zu
     * fuenf benachbarte Faelligkeiten der naechste Treffer sein - und wuerde
     * fuenf voneinander unabhaengige Widerlegungen erzeugen, die es nicht
     * gibt. Genau EINE darf ihn bekommen, die mit dem kleinsten Abstand.
     */
    @Test
    fun `ein Messwert bedient hoechstens eine Faelligkeit`() {
        // Drei Faelligkeiten im Minutenabstand, EIN Messwert bei der mittleren.
        val a = eintrag(t0)
        val b = eintrag(t0 + 60_000L)
        val c = eintrag(t0 + 120_000L)
        val einer = probe(b.dueTs, 205.0)
        val (out, _, _) = rechne(listOf(a, b, c), c.dueTs, listOf(einer))
        val bewertet = out.filter { it.actualMgdl != null }
        assertEquals(1, bewertet.size, "nur EINE Faelligkeit darf den Wert bekommen")
        assertEquals(b.dueTs, bewertet[0].entry.dueTs, "und zwar die naechstgelegene")
        assertEquals(
            2, out.count { it.verdict == ExpectationLedger.Verdict.UNVERIFIABLE },
            "die beiden anderen bleiben unbewertet",
        )
    }

    /** Ein Messwert aus einem ANDEREN Segment zaehlt nicht - ueber einen
     *  Bruch hinweg ist er nicht vergleichbar. */
    @Test
    fun `ein Messwert aus fremdem Segment zaehlt nicht`() {
        val e = eintrag(seg = 1L)
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0, seg = 2L)))
        assertEquals(ExpectationLedger.Verdict.UNVERIFIABLE, out[0].verdict)
    }

    /**
     * DIE GESUNDHEIT GEHOERT AN DEN MESSWERT, nicht an den
     * Abrechnungszeitpunkt: geprueft wird ein historischer Punkt.
     */
    @Test
    fun `ein ungesunder Messwert zaehlt nicht`() {
        val e = eintrag()
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0, healthy = false)))
        assertEquals(ExpectationLedger.Verdict.UNVERIFIABLE, out[0].verdict)
        assertNull(out[0].meanErrorMgdl)
    }

    @Test
    fun `die Zuordnung hat eine enge Toleranz`() {
        val e = eintrag()
        assertEquals(
            ExpectationLedger.Verdict.MISSED,
            rechne(listOf(e), e.dueTs + 5 * 60_000L, listOf(probe(e.dueTs + 60_000L, 205.0))).outcomes[0].verdict,
            "eine Minute daneben ist zuordenbar",
        )
        assertEquals(
            ExpectationLedger.Verdict.UNVERIFIABLE,
            rechne(listOf(e), e.dueTs + 10 * 60_000L, listOf(probe(e.dueTs + 8 * 60_000L, 205.0))).outcomes[0].verdict,
            "acht Minuten sind es nicht",
        )
    }

    // ---- Eingriffe -------------------------------------------------------

    /**
     * EIN EINGRIFF MACHT DIE PROGNOSE UNVERGLEICHBAR (Tonis dritter Befund),
     * und zwar in BEIDE Richtungen gefaehrlich: ein manueller Bolus koennte
     * ein MET erzeugen und einen echten Nachweis loeschen; Kohlenhydrate
     * koennten ein MISSED erzeugen und spaeter lambda lockern, obwohl das
     * Modell recht hatte.
     */
    @Test
    fun `ein Eingriff zwischen Ausgabe und Faelligkeit macht das Urteil ungueltig`() {
        val e = eintrag(rev = 100L)
        // Der BG steht auf 205 - ohne Eingriff waere das ein klares MISSED.
        val (missed, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0, rev = 100L)))
        assertEquals(ExpectationLedger.Verdict.MISSED, missed[0].verdict)

        // Mit Eingriff: kein Urteil, obwohl die Zahlen dieselben sind.
        val (eingriff, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0, rev = 101L)))
        assertEquals(ExpectationLedger.Verdict.INTERVENED, eingriff[0].verdict)
        assertNull(eingriff[0].actualMgdl, "ein ungueltiges Urteil traegt keine Zahl")
        assertTrue(!eingriff[0].isLambdaEvidence(MARGE))
    }

    /** Auch die andere Richtung: ein Eingriff darf kein MET erzeugen und
     *  damit einen Nachweis loeschen. */
    @Test
    fun `ein Eingriff erzeugt auch kein MET`() {
        val e = eintrag(rev = 100L)
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 140.0, rev = 101L)))
        assertEquals(ExpectationLedger.Verdict.INTERVENED, out[0].verdict)
    }

    /**
     * TONIS GEGENBEISPIEL, nachgerechnet und als Test festgehalten.
     *
     * Faelligkeiten 0 und 4, Messwerte 3 und 7, Toleranz 4. Der gierige
     * Ansatz nimmt zuerst das kuerzeste Paar 4->3 (Abstand 1) und laesst
     * damit die Faelligkeit 0 leer ausgehen - EINE Zuordnung statt zweier.
     * Richtig ist 0->3 und 4->7.
     *
     * Ein verlorener Nachweis ist teurer als ein paar Sekunden Abstand.
     */
    @Test
    fun `die Zuordnung maximiert die Anzahl, nicht die Naehe des ersten Paars`() {
        // Zahlen innerhalb der echten Toleranz (150 s), Tonis Struktur:
        //   Faelligkeiten 0 / 120 s, Messwerte 90 / 210 s
        //   zulaessig: b-s1 (30 s), a-s1 (90 s), b-s2 (90 s)
        //   gierig nimmt b-s1 zuerst -> a geht leer aus (a-s2 = 210 s > 150)
        val a = eintrag(source = t0)                        // faellig t0+30 min
        val b = eintrag(source = t0 + 120_000L)             // faellig +120 s
        val s1 = probe(a.dueTs + 90_000L, 205.0)
        val s2 = probe(a.dueTs + 210_000L, 205.0)
        val (out, _, _) = rechne(listOf(a, b), b.dueTs + 10 * 60_000L, listOf(s1, s2))
        val zugeordnet = out.filter { it.actualTs != null }
        assertEquals(2, zugeordnet.size, "beide Faelligkeiten muessen einen Wert bekommen")
        val nach = out.associateBy { it.entry.dueTs }
        assertEquals(s1.ts, nach[a.dueTs]!!.actualTs, "die fruehere bekommt den frueheren Wert")
        assertEquals(s2.ts, nach[b.dueTs]!!.actualTs, "die spaetere den spaeteren")
    }

    /**
     * DER EINGRIFFSSTAND AM MESSWERT ENTSCHEIDET, nicht der heutige.
     *
     * Ein Eingriff NACH der Faelligkeit, aber vor einem verspaeteten
     * `settle`, darf eine damals saubere Prognose nicht nachtraeglich
     * entwerten - der erste Wurf verglich gegen die Gegenwart und tat genau
     * das.
     */
    @Test
    fun `ein Eingriff nach der Faelligkeit entwertet die Prognose nicht`() {
        val e = eintrag(rev = 100L)
        // Der Messwert zur Faelligkeit traegt noch den alten Stand; erst
        // danach wurde eingegriffen, und `settle` laeuft verspaetet.
        val sauber = probe(e.dueTs, 205.0, rev = 100L)
        val (out, _, _) = rechne(listOf(e), e.dueTs + 30 * 60_000L, listOf(sauber))
        assertEquals(
            ExpectationLedger.Verdict.MISSED, out[0].verdict,
            "zum Faelligkeitszeitpunkt war die Lage sauber - das Urteil gilt",
        )
    }

    /** Auch ein Konfigurationswechsel wird am Messwert geprueft. */
    @Test
    fun `ein Konfigurationswechsel macht das Urteil ungueltig`() {
        val e = eintrag()
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0, cfg = "cfg#2")))
        assertEquals(ExpectationLedger.Verdict.INTERVENED, out[0].verdict)
    }

    // ---- Duplikate -------------------------------------------------------

    /**
     * DIESELBE PROGNOSE DARF NICHT ZWEIMAL IN DER LISTE STEHEN. Sie doppelt
     * zu fuehren hiesse, denselben Nachweis zweimal zu zaehlen - und die
     * frueher nach `dueTs` indizierte Zuordnung haette beiden denselben
     * Messwert gegeben, obwohl er nur einmal als verbraucht galt.
     */
    @Test
    fun `ein Duplikat wird beim Einreihen verworfen`() {
        val e = eintrag()
        val einmal = ExpectationLedger.add(emptyList(), e)
        val zweimal = ExpectationLedger.add(einmal, eintrag())
        assertEquals(1, zweimal.size, "dieselbe EntryId nur einmal")
        // Ein Eintrag mit anderer Quelle ist ein anderer Eintrag.
        val anders = ExpectationLedger.add(zweimal, eintrag(source = t0 + 60_000L))
        assertEquals(2, anders.size)
        assertEquals(zweimal, ExpectationLedger.add(zweimal, null), "null aendert nichts")
    }

    /**
     * BEI NEUEM STAND WIRD ERSETZT, nicht behalten (Toni, P1).
     *
     * Die Kennung enthaelt bewusst weder Revision noch Konfiguration - sie
     * soll ueber Neustarts stabil bleiben. Damit kollidieren nach einem
     * Eingriff die alte und die neue Prognose. Die ALTE zu behalten hiesse,
     * gegen eine ueberholte Ausgangsannahme zu pruefen; relevant ist die,
     * die den neuen Stand kennt.
     */
    @Test
    fun `bei neuem Eingriffsstand wird der Eintrag ersetzt`() {
        val alt = eintrag(rev = 100L)
        val liste = ExpectationLedger.add(emptyList(), alt)
        val neu = eintrag(rev = 101L)
        val ersetzt = ExpectationLedger.add(liste, neu)
        assertEquals(1, ersetzt.size, "die Kennung ist dieselbe - kein zweiter Eintrag")
        assertEquals(101L, ersetzt[0].interventionRevision, "aber der NEUE Stand muss gewinnen")
        // Dasselbe fuer die Konfiguration.
        val andereCfg = ExpectationLedger.issue(
            t0, SEG, 200.0, 150.0, H, configGeneration = "cfg#2", interventionRevision = 101L,
            context = KORR,
        )!!
        val ersetzt2 = ExpectationLedger.add(ersetzt, andereCfg)
        assertEquals(1, ersetzt2.size)
        assertEquals("cfg#2", ersetzt2[0].configGeneration)
    }

    /**
     * UND AUCH BEI GEAENDERTER PROGNOSE (Tonis eigentlicher Punkt).
     *
     * Der erste Anlauf verglich nur Revision und Konfiguration. Aendert sich
     * bei wiederholtem `sourceTs` die PROGNOSE selbst - Mittelbahn,
     * Untergrenze, lambda -, blieb der alte Eintrag stehen, und der Nachweis
     * liefe gegen eine ueberholte Behauptung.
     */
    @Test
    fun `bei geaenderter Prognose wird der Eintrag ersetzt`() {
        val alt = ExpectationLedger.issue(
            t0, SEG, 200.0, 150.0, H, CFG, REV, KORR, safetyLowerPredictedMgdl = 40.0, lambda = 1.0,
        )!!
        val liste = ExpectationLedger.add(emptyList(), alt)

        // Gleiche Kennung, gleicher Stand - nur die Mittelbahn ist eine andere.
        val andereMittelbahn = ExpectationLedger.issue(
            t0, SEG, 200.0, 120.0, H, CFG, REV, KORR, safetyLowerPredictedMgdl = 40.0, lambda = 1.0,
        )!!
        val r1 = ExpectationLedger.add(liste, andereMittelbahn)
        assertEquals(1, r1.size)
        assertEquals(120.0, r1[0].meanPredictedMgdl, 1e-9, "die neue Mittelbahn muss gewinnen")

        // Dasselbe fuer lambda - die Groesse, um die es spaeter geht.
        val anderesLambda = ExpectationLedger.issue(
            t0, SEG, 200.0, 120.0, H, CFG, REV, KORR, safetyLowerPredictedMgdl = 40.0, lambda = 0.5,
        )!!
        val r2 = ExpectationLedger.add(r1, anderesLambda)
        assertEquals(1, r2.size)
        assertEquals(0.5, r2[0].lambda!!, 1e-9)

        // Ein in JEDEM Feld gleicher Eintrag bleibt ein echtes Duplikat.
        assertEquals(r2, ExpectationLedger.add(r2, anderesLambda))
    }

    /** Die Kennung ist stabil - sie haengt nur an Quelle, Faelligkeit und
     *  Segment, nicht an einem Zaehler, der nach einem Neustart neu begaenne. */
    @Test
    fun `die Kennung ist stabil und unterscheidet gleiche Faelligkeiten`() {
        assertEquals(eintrag().id, eintrag().id)
        assertTrue(eintrag(seg = 1L).id != eintrag(seg = 2L).id, "anderes Segment, andere Kennung")
        assertTrue(eintrag(source = t0).id != eintrag(source = t0 + 60_000L).id)
    }

    // ---- P0: Verbrauch ueber Aufrufgrenzen -------------------------------

    /**
     * EINE PROBE DARF AUCH IN SPAETEREN AUFRUFEN NICHT WIEDER GELTEN
     * (Toni, P0).
     *
     * Der Verbrauch lebte nur innerhalb eines `settle`. Bei minuetlichen
     * Zyklen und 150 s Toleranz bediente derselbe Messwert nacheinander bis
     * zu fuenf Faelligkeiten - aus EINEM Punkt waere eine vier Minuten lange
     * MISSED-Strecke geworden. Eine Persistenz haette diesen mehrfach
     * gezaehlten Nachweis dauerhaft beglaubigt.
     */
    @Test
    fun `eine verbrauchte Probe gilt auch im naechsten Aufruf nicht mehr`() {
        val a = eintrag(source = t0)
        val b = eintrag(source = t0 + 60_000L)
        val einer = probe(a.dueTs, 205.0)

        // Zyklus 1: a bekommt die Probe.
        val z1 = rechne(listOf(a, b), a.dueTs, listOf(einer))
        assertEquals(ExpectationLedger.Verdict.MISSED, z1.outcomes[0].verdict)
        assertEquals(setOf(einer.id), z1.consumed, "der Verbrauch muss hinausgereicht werden")

        // Zyklus 2: b ist faellig, dieselbe Probe liegt noch in Reichweite -
        // darf aber nicht mehr zaehlen.
        val z2 = rechne(z1.remaining, b.dueTs, listOf(einer), consumed = z1.consumed)
        assertEquals(
            ExpectationLedger.Verdict.UNVERIFIABLE, z2.outcomes[0].verdict,
            "dieselbe Probe darf keine zweite Faelligkeit bedienen",
        )

        // Gegenprobe: OHNE den weitergereichten Verbrauch waere es ein MISSED
        // gewesen - das ist genau der Fehler, um den es geht.
        val ohne = rechne(z1.remaining, b.dueTs, listOf(einer))
        assertEquals(ExpectationLedger.Verdict.MISSED, ohne.outcomes[0].verdict)
    }

    /** Der Verbrauch waechst nicht unbegrenzt: was aelter ist als die
     *  aelteste offene Faelligkeit minus Toleranz, kann nichts mehr
     *  zuordnen und faellt heraus. */
    @Test
    fun `der Verbrauch wird auf das Noetige beschnitten`() {
        val alt = ExpectationLedger.SampleId(SEG, t0 - 60 * 60_000L)
        val e = eintrag(source = t0 + 60 * 60_000L)   // faellig weit spaeter
        val z = rechne(listOf(eintrag(source = t0), e), eintrag(source = t0).dueTs,
                       listOf(probe(eintrag(source = t0).dueTs, 205.0)), consumed = setOf(alt))
        assertTrue(alt !in z.consumed, "der uralte Verbrauch ist nicht mehr noetig")
    }

    /**
     * IDENTISCHE DUPLIKATE FALLEN ZUSAMMEN, schon vor der Zuordnung
     * (Toni, P0).
     *
     * Zwei gleiche Exportzeilen sind zwei Listenpositionen und koennten
     * zwei Faelligkeiten bedienen. Dass `consumed` sie hinterher zu einer
     * Kennung zusammenzieht, kommt zu spaet - der doppelte Nachweis waere
     * dann schon entstanden.
     */
    @Test
    fun `zwei identische Exportzeilen bedienen nur eine Faelligkeit`() {
        val a = eintrag(source = t0)
        val b = eintrag(source = t0 + 60_000L)
        val doppelt = probe(a.dueTs, 205.0)
        val (out, _, _) = rechne(listOf(a, b), b.dueTs, listOf(doppelt, doppelt))
        assertEquals(
            1, out.count { it.actualMgdl != null },
            "dieselbe Zeile zweimal ist derselbe Messwert, nicht zwei",
        )
    }

    /**
     * WIDERSPRUECHLICHE Duplikate fallen RAUS, nicht auf einen davon
     * zurueck. Welcher der richtige waere, ist nicht entscheidbar - und an
     * einer Beweisgrundlage wird nicht geraten.
     */
    @Test
    fun `widerspruechliche Duplikate machen den Zeitpunkt unbewertbar`() {
        val e = eintrag()
        val einer = probe(e.dueTs, 205.0)
        val anderer = probe(e.dueTs, 140.0)   // gleiche Kennung, anderer Wert
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(einer, anderer))
        assertEquals(
            ExpectationLedger.Verdict.UNVERIFIABLE, out[0].verdict,
            "bei Widerspruch wird nicht geraten",
        )
    }

    /**
     * DER WIDERSPRUCH MUSS SICHTBAR BLEIBEN, auch wenn eine der Zeilen
     * unbrauchbar ist (Toni, P0).
     *
     * Wuerden Gesundheit und Endlichkeit VOR dem Vergleich gefiltert,
     * verschwaende die ungesunde Zeile - und aus zwei einander
     * widersprechenden Exportzeilen wuerde ein sauberes MISSED. Der Export
     * behauptet aber zweierlei ueber denselben Zeitpunkt; das ist ein
     * Integritaetsproblem und keine gueltige Messung.
     */
    @Test
    fun `ein Widerspruch zaehlt auch dann, wenn eine Zeile unbrauchbar ist`() {
        val e = eintrag()
        for (gegenstueck in listOf(
            probe(e.dueTs, 140.0, healthy = false),          // ungesund
            probe(e.dueTs, Double.NaN),                       // nicht endlich
            probe(e.dueTs, 140.0, rev = 999L),                // anderer Stand
        )) {
            val gesund = probe(e.dueTs, 205.0)
            val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(gesund, gegenstueck))
            assertEquals(
                ExpectationLedger.Verdict.UNVERIFIABLE, out[0].verdict,
                "widersprechende Zeilen duerfen kein Urteil tragen: $gegenstueck",
            )
        }
    }

    /** Die Gegenprobe: EINE gesunde Zeile allein bleibt verwertbar - der
     *  Filter darf nicht pauschal alles verwerfen. */
    @Test
    fun `eine einzelne gesunde Zeile bleibt verwertbar`() {
        val e = eintrag()
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0)))
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
    }

    /** Und zwei identische UNBRAUCHBARE Zeilen sind kein Widerspruch,
     *  sondern schlicht unbrauchbar - kein Urteil, aber auch kein Alarm. */
    @Test
    fun `zwei identische unbrauchbare Zeilen ergeben schlicht kein Urteil`() {
        val e = eintrag()
        val kaputt = probe(e.dueTs, 205.0, healthy = false)
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(kaputt, kaputt))
        assertEquals(ExpectationLedger.Verdict.UNVERIFIABLE, out[0].verdict)
    }

    /**
     * EINE UNBRAUCHBARE MARGE IST KEIN FREIBRIEF. Negativ hiesse, dass sogar
     * Werte UNTERHALB der damaligen Sicherheitsuntergrenze als Beleg fuer
     * mehr Insulin durchgingen - die Umkehrung des Begriffs.
     */
    @Test
    fun `eine unbrauchbare Marge ergibt nie lambda-Evidenz`() {
        val treffer = ergebnis(t0, ExpectationLedger.Verdict.MISSED)
        assertTrue(treffer.isLambdaEvidence(20.0), "eine brauchbare Marge greift")
        for (schlecht in listOf(0.0, -1.0, -1000.0, Double.NaN, Double.NEGATIVE_INFINITY)) {
            assertTrue(!treffer.isLambdaEvidence(schlecht), "Marge $schlecht")
        }
    }

    // ---- Der lambda-Begriff ----------------------------------------------

    /**
     * NICHT JEDES MISSED IST EIN BELEG GEGEN LAMBDA (Toni, P1).
     *
     * Ein ausgebliebener Senkungsschritt allein widerlegt den Abschlag nicht -
     * lag die reale Bahn knapp ueber der damaligen Untergrenze, hatte er
     * moeglicherweise recht. Als Beleg taugt er nur mit deutlichem Abstand.
     */
    @Test
    fun `nur ein MISSED mit Abstand zur Untergrenze ist lambda-Evidenz`() {
        val e = ExpectationLedger.issue(
            t0, SEG, 200.0, 150.0, H, CFG, REV, KORR, safetyLowerPredictedMgdl = 190.0,
        )!!
        // Gemessen 205: MISSED, aber nur 15 mg/dl ueber der Untergrenze.
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0)))
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
        assertEquals(15.0, out[0].distanceFromSafetyLowerMgdl!!, 1e-9)
        assertTrue(out[0].isLambdaEvidence(10.0), "10 mg/dl Marge: reicht")
        assertTrue(!out[0].isLambdaEvidence(20.0), "20 mg/dl Marge: reicht nicht")
    }

    /** OHNE damalige Untergrenze ist nichts belegt - `null` heisst nicht
     *  "war weit genug weg". */
    @Test
    fun `ohne Untergrenze gibt es keine lambda-Evidenz`() {
        val e = eintrag()   // ohne safetyLowerPredictedMgdl
        val (out, _, _) = rechne(listOf(e), e.dueTs, listOf(probe(e.dueTs, 205.0)))
        assertEquals(ExpectationLedger.Verdict.MISSED, out[0].verdict)
        assertTrue(!out[0].isLambdaEvidence(MARGE), "auch bei Marge 0 nicht")
    }

    /** MET und INTERVENED sind nie Evidenz, egal wie gross der Abstand. */
    @Test
    fun `nur MISSED kann ueberhaupt Evidenz sein`() {
        for (v in listOf(
            ExpectationLedger.Verdict.MET,
            ExpectationLedger.Verdict.INTERVENED,
            ExpectationLedger.Verdict.UNVERIFIABLE,
        )) assertTrue(!ergebnis(t0, v).isLambdaEvidence(MARGE), "$v")
        assertTrue(ergebnis(t0, ExpectationLedger.Verdict.MISSED).isLambdaEvidence(MARGE))
    }

    // ---- Die Strecke: BELEGTE Dauer --------------------------------------

    /** MARGE: die Untergrenze liegt bei 40, der gemessene Wert bei 205 -
     *  Abstand 165 mg/dl, also klar lambda-Evidenz. */
    private val MARGE = 20.0

    private fun ergebnis(due: Long, v: ExpectationLedger.Verdict, seg: Long = SEG) =
        ExpectationLedger.Outcome(
            ExpectationLedger.Entry(
                due - H * 60_000L, due, seg, 200.0, 150.0, CFG, REV, KORR,
                safetyLowerPredictedMgdl = 40.0,
            ),
            v,
            if (v == ExpectationLedger.Verdict.MISSED || v == ExpectationLedger.Verdict.MET) due else null,
            if (v == ExpectationLedger.Verdict.MISSED || v == ExpectationLedger.Verdict.MET) 205.0 else null,
        )

    /** Zehn Prognosen im Minutentakt sind neun Minuten Strecke, nicht "zehn
     *  Widerlegungen". */
    @Test
    fun `gemessen wird die Dauer der Strecke, nicht die Anzahl`() {
        val m = ExpectationLedger.Verdict.MISSED
        val zehn = (0..9).map { ergebnis(t0 + it * 60_000L, m) }
        assertEquals(9, ExpectationLedger.lambdaEvidenceStreakMin(zehn, SEG, MARGE))
    }

    /**
     * UNBEOBACHTETE ZEIT IST KEIN BELEG (Tonis zweiter Befund) - und der
     * Vorgaengertest schrieb genau das Gegenteil fest ("zehn Ereignisse ueber
     * eine Stunde sind ein staerkerer Beleg").
     *
     * Zwei MISSED mit 58 Minuten Luecke dazwischen ergaben dort eine
     * 60-Minuten-Strecke. Ohne belegte Zwischenzeit stimmt das nicht.
     */
    @Test
    fun `eine Luecke zwischen zwei Ausbleibern bricht die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        val mitLuecke = listOf(ergebnis(t0, m), ergebnis(t0 + 58 * 60_000L, m))
        assertEquals(
            0, ExpectationLedger.lambdaEvidenceStreakMin(mitLuecke, SEG, MARGE),
            "58 unbeobachtete Minuten sind keine 58 Minuten Nachweis",
        )
        // Dieselben zwei Punkte, aber lueckenlos belegt: das zaehlt.
        val dicht = (0..58).map { ergebnis(t0 + it * 60_000L, m) }
        assertEquals(58, ExpectationLedger.lambdaEvidenceStreakMin(dicht, SEG, MARGE))
    }

    /** Ein Eintreffen beendet die Strecke - der Nachweis beginnt von vorn. */
    @Test
    fun `ein Eintreffen beendet die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        val t = ExpectationLedger.Verdict.MET
        val reihe = listOf(
            ergebnis(t0, m), ergebnis(t0 + 60_000, m),
            ergebnis(t0 + 120_000, t),
            ergebnis(t0 + 180_000, m), ergebnis(t0 + 240_000, m),
        )
        assertEquals(1, ExpectationLedger.lambdaEvidenceStreakMin(reihe, SEG, MARGE), "nur die beiden juengsten")
        assertEquals(0, ExpectationLedger.lambdaEvidenceStreakMin(listOf(ergebnis(t0, t)), SEG, MARGE))
        assertEquals(0, ExpectationLedger.lambdaEvidenceStreakMin(emptyList(), SEG, MARGE))
    }

    /** Ein Segmentbruch beendet sie ebenfalls. */
    @Test
    fun `ein Segmentbruch beendet die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        val reihe = listOf(
            ergebnis(t0, m, seg = 1L), ergebnis(t0 + 60_000, m, seg = 1L),
            ergebnis(t0 + 120_000, m, seg = 2L), ergebnis(t0 + 180_000, m, seg = 2L),
        )
        assertEquals(1, ExpectationLedger.lambdaEvidenceStreakMin(reihe, currentSegmentId = 2L, minSafetyMarginMgdl = MARGE))
        assertEquals(0, ExpectationLedger.lambdaEvidenceStreakMin(reihe, currentSegmentId = 3L, minSafetyMarginMgdl = MARGE))
    }

    /**
     * UNVERIFIABLE UND INTERVENED BRECHEN JETZT EBENFALLS - konservativ.
     * Beide bedeuten, dass der Nachweis an dieser Stelle nicht gefuehrt
     * wurde, und ein nicht gefuehrter Nachweis darf keine Strecke
     * ueberbruecken.
     */
    @Test
    fun `nicht gefuehrte Nachweise brechen die Strecke`() {
        val m = ExpectationLedger.Verdict.MISSED
        for (luecke in listOf(ExpectationLedger.Verdict.UNVERIFIABLE, ExpectationLedger.Verdict.INTERVENED)) {
            val reihe = listOf(ergebnis(t0, m), ergebnis(t0 + 60_000, luecke), ergebnis(t0 + 120_000, m))
            assertEquals(
                0, ExpectationLedger.lambdaEvidenceStreakMin(reihe, SEG, MARGE),
                "$luecke darf nicht ueberbrueckt werden",
            )
        }
    }

    // ---- advance: die geschlossene Klammer -------------------------------

    /**
     * EINE BEREITS ABGERECHNETE PROGNOSE WIRD NICHT ERNEUT EINGEREIHT
     * (Toni, P0 - zweite Haelfte).
     *
     * Die Restore-Pruefung faengt eine Datei ab, in der dieselbe Kennung
     * offen UND abgerechnet steht. Sie kann aber nicht verhindern, dass zur
     * LAUFZEIT eine schon abgerechnete Prognose noch einmal angeboten wird -
     * etwa wenn der Aufrufer nach einem Wiederanlauf denselben Zyklus
     * wiederholt. Dann liefe sie ein zweites Mal durch `settle` und
     * erzeugte doppelte Evidenz aus einer einzigen Prognose.
     */
    @Test
    fun `advance reiht eine bereits abgerechnete Prognose nicht erneut ein`() {
        val e = eintrag()
        // Zyklus 1: einreihen und abrechnen.
        val nachEins = ExpectationLedger.advance(
            ExpectationLedger.State.empty(), e.dueTs, e, listOf(probe(e.dueTs, 205.0)),
        )
        assertEquals(1, nachEins.outcomes.size, "abgerechnet")
        assertTrue(nachEins.entries.isEmpty(), "und nicht mehr offen")

        // Zyklus 2: DIESELBE Prognose wird noch einmal angeboten.
        val nachZwei = ExpectationLedger.advance(
            nachEins, e.dueTs + 60_000L, e, listOf(probe(e.dueTs + 60_000L, 205.0)),
        )
        assertTrue(
            nachZwei.entries.none { it.id == e.id },
            "sie darf nicht wieder offen werden",
        )
        assertEquals(
            1, nachZwei.outcomes.count { it.entry.id == e.id },
            "und genau EIN Ergebnis tragen, nicht zwei",
        )
    }

    /** Die Gegenprobe: eine NEUE Prognose wird selbstverstaendlich
     *  eingereiht - der Filter darf nicht alles blocken. */
    @Test
    fun `advance reiht eine neue Prognose ein`() {
        val e = eintrag()
        val nachEins = ExpectationLedger.advance(
            ExpectationLedger.State.empty(), e.dueTs, e, listOf(probe(e.dueTs, 205.0)),
        )
        val neu = eintrag(source = t0 + 10 * 60_000L)
        val nachZwei = ExpectationLedger.advance(nachEins, e.dueTs + 60_000L, neu, emptyList())
        assertTrue(nachZwei.entries.any { it.id == neu.id }, "die neue muss offen sein")
    }

    // ---- Kontext: nur CORRECTION traegt lambda ---------------------------

    /**
     * TONIS PFLICHTTEST (18.08.): dieselbe MISSED-Folge, einmal als
     * Korrektur und einmal als Mahlzeit.
     *
     * In einer Mahlzeitenepisode ist eine ausbleibende Senkung der
     * NORMALFALL - Kohlenhydrate laufen gegen das Insulin. Solche Eintraege
     * in die lambda-Strecke zu zaehlen hiesse, den Abschlag ausgerechnet
     * dann zu lockern, wenn das Modell recht hatte.
     */
    @Test
    fun `dieselbe Folge traegt lambda nur im Korrekturbetrieb`() {
        fun folge(kontext: ExpectationLedger.ExpectationContext) = (0..9).map { i ->
            val due = t0 + i * 60_000L
            ExpectationLedger.Outcome(
                ExpectationLedger.Entry(
                    due - H * 60_000L, due, SEG, 200.0, 150.0, CFG, REV, kontext,
                    safetyLowerPredictedMgdl = 40.0,
                ),
                ExpectationLedger.Verdict.MISSED, due, 205.0,
            )
        }
        assertEquals(
            9, ExpectationLedger.lambdaEvidenceStreakMin(folge(KORR), SEG, MARGE),
            "der Korrekturbetrieb traegt den Nachweis",
        )
        assertEquals(
            0,
            ExpectationLedger.lambdaEvidenceStreakMin(
                folge(ExpectationLedger.ExpectationContext.MEAL), SEG, MARGE,
            ),
            "dieselbe Folge als Mahlzeit traegt ihn NICHT",
        )
    }

    /** Und einzeln: ein MEAL-Ergebnis ist nie lambda-Evidenz, egal wie gross
     *  der Abstand zur Untergrenze ist. */
    @Test
    fun `ein Mahlzeiten-Ergebnis ist nie lambda-Evidenz`() {
        val meal = ExpectationLedger.Outcome(
            ExpectationLedger.Entry(
                t0, t0 + H * 60_000L, SEG, 200.0, 150.0, CFG, REV,
                ExpectationLedger.ExpectationContext.MEAL,
                safetyLowerPredictedMgdl = 40.0,
            ),
            ExpectationLedger.Verdict.MISSED, t0 + H * 60_000L, 205.0,
        )
        assertEquals(ExpectationLedger.Verdict.MISSED, meal.verdict, "auswertbar bleibt es")
        assertEquals(165.0, meal.distanceFromSafetyLowerMgdl!!, 1e-9, "und die Diagnose steht")
        assertTrue(!meal.isLambdaEvidence(1.0), "aber kein Beleg gegen lambda")
    }

    /** Eine MEAL-Strecke unterbricht eine laufende CORRECTION-Strecke -
     *  konservativ, wie jedes andere Nicht-Ereignis auch. */
    @Test
    fun `ein Mahlzeiten-Ergebnis unterbricht die Korrekturstrecke`() {
        fun erg(due: Long, kontext: ExpectationLedger.ExpectationContext) =
            ExpectationLedger.Outcome(
                ExpectationLedger.Entry(
                    due - H * 60_000L, due, SEG, 200.0, 150.0, CFG, REV, kontext,
                    safetyLowerPredictedMgdl = 40.0,
                ),
                ExpectationLedger.Verdict.MISSED, due, 205.0,
            )
        val reihe = listOf(
            erg(t0, KORR),
            erg(t0 + 60_000L, ExpectationLedger.ExpectationContext.MEAL),
            erg(t0 + 120_000L, KORR),
        )
        assertEquals(
            0, ExpectationLedger.lambdaEvidenceStreakMin(reihe, SEG, MARGE),
            "die Mahlzeit in der Mitte darf nicht ueberbrueckt werden",
        )
    }

    // ---- Der Klassifikator: CORRECTION ist der schwere Fall --------------

    /** Reine Korrekturlage - ALLES ausdruecklich belegt. */
    private fun reineKorrektur() = ExpectationLedger.Situation(
        mealMarkerActive = false,
        evidenceEpisodeActive = false,
        onsetActive = false,
        mealWindow = false,
        reboundWindow = false,
        signalHealthy = true,
    )

    @Test
    fun `nur eine vollstaendig belegte Korrekturlage ergibt CORRECTION`() {
        assertEquals(
            ExpectationLedger.ExpectationContext.CORRECTION,
            ExpectationLedger.classify(reineKorrektur()),
        )
    }

    /**
     * JEDE EINZELNE LAGE-GROESSE KIPPT AUF MEAL - adversariell durchgespielt.
     *
     * Tonis Grenze: "Marker, Onset, laufende Evidenzepisode,
     * Mahlzeitenfenster, Rebound oder unklare Lage -> keine
     * CORRECTION-lambda-Evidenz."
     */
    @Test
    fun `jede Mahlzeiten- oder Reboundlage kippt auf MEAL`() {
        val faelle = mapOf(
            "Marker aktiv" to reineKorrektur().copy(mealMarkerActive = true),
            "Evidenzepisode laeuft" to reineKorrektur().copy(evidenceEpisodeActive = true),
            "Onset aktiv" to reineKorrektur().copy(onsetActive = true),
            "Mahlzeitenfenster offen" to reineKorrektur().copy(mealWindow = true),
            "Rebound-Fenster" to reineKorrektur().copy(reboundWindow = true),
            "Signal ungesund" to reineKorrektur().copy(signalHealthy = false),
        )
        for ((name, lage) in faelle) assertEquals(
            ExpectationLedger.ExpectationContext.MEAL,
            ExpectationLedger.classify(lage), name,
        )
    }

    /**
     * DER WICHTIGSTE GRENZFALL (Toni 18.08.): der MARKER endet frueher als
     * eine langsame Absorption.
     *
     * Nur `mealMarkerActive` zu pruefen wuerde die Nachlaufphase einer
     * Mahlzeit als reine Korrektur verbuchen - und genau dort ist eine
     * ausbleibende Senkung der Normalfall. Die laufende Evidenzepisode muss
     * den Kontext weiter auf MEAL halten.
     */
    @Test
    fun `der beendete Marker allein macht noch keine Korrekturlage`() {
        val nachlauf = reineKorrektur().copy(
            mealMarkerActive = false,      // der Marker ist abgelaufen
            evidenceEpisodeActive = true,  // die Absorption laeuft weiter
        )
        assertEquals(
            ExpectationLedger.ExpectationContext.MEAL,
            ExpectationLedger.classify(nachlauf),
            "die Nachlaufphase ist keine Korrektur",
        )
    }

    /** UNBEKANNT IST NICHT "NEIN". Ein einziges `null` genuegt fuer MEAL -
     *  ein vergessenes Merkmal darf hoechstens Nachweis kosten, nie welchen
     *  erfinden. */
    @Test
    fun `eine unklare Lage ergibt nie CORRECTION`() {
        val unbekannt = listOf(
            reineKorrektur().copy(mealMarkerActive = null),
            reineKorrektur().copy(evidenceEpisodeActive = null),
            reineKorrektur().copy(onsetActive = null),
            reineKorrektur().copy(mealWindow = null),
            reineKorrektur().copy(reboundWindow = null),
            reineKorrektur().copy(signalHealthy = null),
        )
        for (lage in unbekannt) assertEquals(
            ExpectationLedger.ExpectationContext.MEAL,
            ExpectationLedger.classify(lage), "$lage",
        )
    }
}

package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER MARKER UEBERSTIMMT DAS MODELL, NICHT DIE WIRKLICHKEIT.
 *
 * DIESE DATEI HIESS BIS ZUM 18.08. "DER MARKER AUTORISIERT INSULIN BEI
 * GEMESSENEM TIEF" und schrieb genau das fest. Tonis Entscheidung vom
 * 18.08. kehrt das um:
 *
 *   "Der Marker autorisiert eine Mahlzeit, aber kein Insulin bei aktuell
 *    gemessenem Tief. Das entspricht unserem Vertrag: Modell ueberstimmbar,
 *    Wirklichkeit nicht."
 *
 * WARUM DIE ALTE FASSUNG ENTSTAND, denn der Irrtum war verstaendlich: der
 * Kommentar an `LIFTABLE_ON_MARKER` hielt `SAFETY_HOLD` fuer einen
 * Modell-Block wie `GUARD_FLOOR`. Er ist es nicht - er traegt
 * `SafetyReason.LOW`, und der entsteht aus `bg < lowEnterMgdl` mit
 * `signalInputBg = signal.rawBg`, also aus dem ROHEN Messwert. [PrimeRelease.plan]
 * seinerseits sieht den gemessenen BG ueberhaupt nicht: seine Eingabe
 * `safetyMinLowerMgdl` ist die PROGNOSTIZIERTE Unterkante. Wer nur auf den
 * Plan schaut, sieht lauter Modell und haelt den Block fuer eines davon.
 *
 * WAS HEUTE GILT:
 *
 *   hebbar         NO_DEMAND, BELOW_PUMP_INCREMENT, GUARD_FLOOR
 *                  sowie die Schwanzkappe fuer den autorisierten Anteil
 *   NICHT hebbar   SAFETY_HOLD (gemessenes Tief), Signal-, IOB-, Ledger- und
 *                  Pumpenfehler, alle nachgelagerten Mengengrenzen
 *
 * Freigegeben wird AUSSCHLIESSLICH der markerfinanzierte Anteil. Das ist keine
 * zweite Regel, sondern strukturell: bei GUARD_FLOOR ist die Basisdosis 0,
 * also ist alles, was danach herauskommt, der Lift aus der Marker-Huelle.
 *
 * Die Mengengrenzen-Zusicherungen weiter unten sind vom Wechsel unberuehrt
 * geblieben - sie gelten fuer den gehobenen Block, nicht fuer einen
 * bestimmten. Sie stehen jetzt auf `GUARD_FLOOR` statt auf `SAFETY_HOLD`.
 */
class MarkerAuthorizationTest {

    private val step = 0.05

    private fun state(iobTh: Double = 8.0, maxIob: Double = 8.0) = FuseController.State(
        health = Health.READY, safetyHold = true, phase = Phase.REARMING,
        netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
        iobThU = iobTh, maxIobU = maxIob, targetMgdl = 100.0, isfMgdlPerU = 55.0,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = 2.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = step, maxSmbU = 0.3, pumpBusy = false,
    )

    /**
     * Eine Lage, in der die PROGNOSTIZIERTE Unterkante unter dem Guard-Boden
     * liegt - also eine Modellaussage, kein gemessenes Tief.
     *
     * Der Name hiess frueher `planImTief` und war damit die sprachliche
     * Wurzel der Verwechslung: [PrimeRelease.plan] kennt den gemessenen BG
     * gar nicht.
     */
    private fun planUnterBoden(
        markerAuthorized: Boolean,
        markerActive: Boolean = true,
        spentU: Double = 0.0,
        envelopeU: Double = 1.2,
    ) = PrimeRelease.plan(
        PrimeRelease.Input(
            enabled = true,
            mealMarkerActive = markerActive,
            armedTsMs = 1_000_000L,
            windowStartTsMs = 0L,
            nowMs = 1_000_000L + 3 * 60_000L,
            envelopeU = envelopeU,
            spentU = spentU,
            safetyMinLowerMgdl = 62.0,      // BG im Tief, weit unter dem Boden
            guardFloorMgdl = 70.0,
            isfMgdlPerU = 55.0,
            pumpIncrementU = step,
            markerAuthorized = markerAuthorized,
        )
    )

    private fun blockiert(block: FuseController.Block) = FuseController.Decision(
        smbU = 0.0, tbr = FuseController.TbrAction.ZERO_TEMP, block = block,
        insulinReqU = 0.0, predAtReleaseMgdl = 90.0, minLowerMgdl = 62.0,
        bindingLimit = block.name,
    )

    // ---- Die Gegenproben ---------------------------------------------------

    /** LOW OHNE MARKER -> 0 U. Der Tiefschutz bleibt absolut. */
    @Test
    fun `LOW ohne Marker gibt nichts frei`() {
        val p = planUnterBoden(markerAuthorized = false, markerActive = false)
        assertTrue(!p.active, "ohne Marker darf kein Plan entstehen: ${p.reason}")
        val d = PrimeRelease.lift(blockiert(FuseController.Block.SAFETY_HOLD), p, state())
        assertEquals(0.0, d.smbU, "keine Freigabe ohne Marker")
    }

    /**
     * LOW MIT MARKER, aber Einstellung AUS -> weiterhin 0 U. Der Schalter muss
     * wirken, sonst waere der Default egal.
     */
    @Test
    fun `LOW mit Marker aber ausgeschalteter Einstellung gibt nichts frei`() {
        val p = planUnterBoden(markerAuthorized = false)
        assertTrue(!p.active, "die Freigangsprobe muss sperren: ${p.reason}")
        assertEquals(
            0.0,
            PrimeRelease.lift(blockiert(FuseController.Block.SAFETY_HOLD), p, state()).smbU,
        )
    }

    /**
     * DIE UMGEKEHRTE ENTSCHEIDUNG (Toni 18.08.).
     *
     * Hier stand bis zum 18.08. das Gegenteil: "LOW MIT MARKER und Einstellung
     * AN -> begrenzter markerExtra-Anteil", und der Test verlangte
     * ausdruecklich `d.smbU > 0.0`. Ein gemessenes Tief wurde also vom
     * Markerdruck ueberstimmt.
     *
     * Der Plan STEHT weiterhin - er kennt den gemessenen BG nicht und hat
     * nichts falsch gemacht. Der Lift verweigert: `SAFETY_HOLD` ist nicht
     * mehr in `LIFTABLE_ON_MARKER`.
     */
    @Test
    fun `LOW mit Marker gibt trotzdem nichts frei`() {
        val p = planUnterBoden(markerAuthorized = true)
        assertTrue(p.active, "der Plan darf stehen - er sieht den gemessenen BG nicht: ${p.reason}")
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorized = true,
        )
        assertEquals(0.0, d.smbU, "gemessenes Tief ist Wirklichkeit, nicht Modell")
        assertEquals(
            FuseController.Block.SAFETY_HOLD, d.block,
            "und der Block bleibt stehen, statt still zu verschwinden",
        )
    }

    /**
     * DIE GEGENPROBE, die den Test oben erst aussagekraeftig macht: DIESELBE
     * Lage, nur mit einem prognostizierten statt gemessenen Grund, gibt frei.
     *
     * Ohne sie waere nicht unterscheidbar, ob der Lift die Wirklichkeit
     * respektiert oder einfach gar nichts mehr hebt.
     */
    @Test
    fun `dieselbe Lage mit prognostiziertem Grund gibt frei`() {
        val p = planUnterBoden(markerAuthorized = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
            markerAuthorized = true,
        )
        assertTrue(d.smbU > 0.0, "der markerfinanzierte Anteil muss durchkommen")
        assertTrue(d.smbU <= p.floorU + 1e-9, "und nicht mehr als der Plan vorsieht")
        assertEquals("primeRelease", d.bindingLimit, "es ist der Marker-Kanal, kein anderer")
    }

    /** Dasselbe fuer den GUARD_FLOOR - das zweite der beiden Tore. */
    @Test
    fun `LOW mit Marker hebt auch den Guard-Floor-Block`() {
        val p = planUnterBoden(markerAuthorized = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
            markerAuthorized = true,
        )
        assertTrue(d.smbU > 0.0)
    }

    /**
     * KEINE FREIGABE EINER NORMALEN KORREKTURDOSIS. Der Lift hebt auf den
     * Prime-Floor, nicht auf den rechnerischen Bedarf - und der Floor kommt
     * ausschliesslich aus der Marker-Huelle.
     */
    @Test
    fun `nur der Marker-Anteil kommt durch, nicht der Korrekturbedarf`() {
        val p = planUnterBoden(markerAuthorized = true)
        // Eine Basisentscheidung mit GROSSEM Bedarf, aber im Tief blockiert.
        val basis = blockiert(FuseController.Block.GUARD_FLOOR).copy(insulinReqU = 3.0)
        val d = PrimeRelease.lift(basis, p, state(), markerAuthorized = true)
        assertTrue(d.smbU <= p.floorU + 1e-9, "der Bedarf von 3,0 U darf nicht durchschlagen")
        assertTrue(d.smbU < 0.5, "die Menge kommt aus der Huelle, nicht aus insulinReq: ${d.smbU}")
    }

    /** DAS BUDGET WIRD VERBRAUCHT: eine ausgeschoepfte Huelle gibt nichts mehr. */
    @Test
    fun `eine verbrauchte Huelle gibt auch mit Marker nichts frei`() {
        val p = planUnterBoden(markerAuthorized = true, spentU = 1.2, envelopeU = 1.2)
        assertTrue(!p.active, "verbrauchte Huelle: ${p.reason}")
        assertEquals(
            0.0,
            PrimeRelease.lift(
                blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
                markerAuthorized = true,
            ).smbU,
        )
    }

    /**
     * TECHNISCHE SPERREN UND HARTE MENGENGRENZEN BLEIBEN HART. Kein Marker
     * der Welt hebt einen Ledger-Hold, eine belegte Pumpe, eine fehlende
     * Eingabe oder eine IOB-Obergrenze auf.
     *
     * TAIL STAND HIER UND IST AM 18.08. HERAUSGENOMMEN WORDEN - er gehoerte
     * nie in diese Liste. Er ist keine technische Sperre, sondern eine
     * Haftungsprognose ueber 120 Minuten, also dieselbe Sorte Modellaussage
     * wie GUARD_FLOOR. Dass er hier stand, erzeugte am Nullpunkt eine
     * Kante: Headroom +0,001 U war ueber die Kappe hebbar, -0,001 U ueber
     * den Block nicht. Er steht jetzt in `die Aufteilung deckt jeden Block
     * genau einmal ab` auf der hebbaren Seite.
     */
    @Test
    fun `technische Sperren und Mengengrenzen sind nicht uebersteuerbar`() {
        val p = planUnterBoden(markerAuthorized = true)
        for (b in listOf(
            FuseController.Block.LEDGER_HOLD,
            FuseController.Block.PUMP_BUSY,
            FuseController.Block.HEALTH_NOT_READY,
            FuseController.Block.NO_INPUT,
            FuseController.Block.HORIZON_MISSING,
            FuseController.Block.CANDIDATE,
            FuseController.Block.MAX_IOB_REACHED,
            FuseController.Block.IOB_TH_REACHED,
        )) {
            val d = PrimeRelease.lift(blockiert(b), p, state(), markerAuthorized = true)
            assertEquals(0.0, d.smbU, "$b darf NICHT uebersteuerbar sein")
        }
    }

    /**
     * DIE KANTE AM NULLPUNKT, ausdruecklich als Zusicherung (Toni 18.08.).
     *
     * Beide Vorzeichen des Schwanz-Headrooms muessen DASSELBE ergeben. Waere
     * nur die Kappe hebbar, haette ein Unterschied von 0,002 U ueber das
     * ganze Fundament entschieden - und die negative Seite ist nach Phase A
     * der Normalfall, nicht der Randfall.
     */
    @Test
    fun `beide Vorzeichen des Schwanz-Headrooms geben dasselbe frei`() {
        val p = planUnterBoden(markerAuthorized = true)
        // Der BLOCK entsteht bei headroom <= 0 - hier vertreten durch
        // Block.TAIL, den der Controller in genau dieser Lage setzt.
        val negativ = PrimeRelease.lift(
            blockiert(FuseController.Block.TAIL), p, state(), markerAuthorized = true,
        )
        // Bei headroom > 0 erreicht der Fluss die Kappenliste; der Block ist
        // dann NONE und die Kappe wird bei Autorisierung uebersprungen.
        val positiv = PrimeRelease.lift(
            blockiert(FuseController.Block.NONE), p, state(),
            markerAuthorized = true, tailHeadroomU = 0.01,
        )
        assertTrue(negativ.smbU > 0.0, "negativer Headroom darf das Fundament nicht toeten")
        assertEquals(
            positiv.smbU, negativ.smbU, 1e-9,
            "beide Vorzeichen MUESSEN dasselbe ergeben - sonst bleibt die Kante",
        )
    }

    /**
     * MARKERABLAUF -> LOW SPERRT WIEDER. Ohne aktiven Marker gibt es keinen
     * Plan, und damit auch keinen Weg an den Bloecken vorbei.
     */
    @Test
    fun `nach Markerablauf sperrt das Tief wieder`() {
        val p = planUnterBoden(markerAuthorized = true, markerActive = false)
        assertTrue(!p.active)
        assertEquals(
            0.0,
            PrimeRelease.lift(
                blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
                markerAuthorized = true,
            ).smbU,
            "ohne laufenden Marker ist die Autorisierung erloschen",
        )
    }

    /**
     * DER SCHWANZ-HEADROOM KAPPT DEN AUTORISIERTEN LIFT NICHT MEHR.
     *
     * Diese Stelle liegt VOR allem anderen, und deshalb hilft kein Boden
     * weiter unten: der Lift ERZEUGT die Autorisierungsgrenze. Kappt der
     * Schwanz ihn hier auf 0, gibt es weiter unten nichts mehr zu schuetzen.
     * Gemessen am 11.08. ist der Schwanz-Headroom bei BG 62 genau das: <= 0.
     */
    @Test
    fun `der Schwanz-Headroom kappt den autorisierten Anteil nicht`() {
        val p = planUnterBoden(markerAuthorized = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
            markerAuthorized = true,
            tailHeadroomU = -5.0,          // der Schwanz sagt: gar nichts
        )
        assertTrue(d.smbU > 0.0, "eine Modellannahme darf den autorisierten Anteil nicht nullen")
        assertEquals(d.smbU, d.markerAuthorizedU, 1e-9)
    }

    /** OHNE Autorisierung kappt derselbe Headroom wie bisher. */
    @Test
    fun `ohne Autorisierung kappt der Schwanz-Headroom weiterhin`() {
        val p = planUnterBoden(markerAuthorized = false, markerActive = true)
        // Ein Plan, der ohne die Autorisierung steht: hoher BG, kein Tief.
        val offen = PrimeRelease.plan(
            PrimeRelease.Input(
                enabled = true, mealMarkerActive = true,
                armedTsMs = 1_000_000L, windowStartTsMs = 0L,
                nowMs = 1_000_000L + 3 * 60_000L,
                envelopeU = 1.2, spentU = 0.0,
                safetyMinLowerMgdl = 160.0, guardFloorMgdl = 70.0,
                isfMgdlPerU = 55.0, pumpIncrementU = step,
                markerAuthorized = false,
            )
        )
        assertTrue(offen.active, "der Aufbau braucht einen stehenden Plan: ${offen.reason}")
        assertTrue(!p.active, "und im Tief steht ohne Autorisierung keiner")
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.NONE), offen, state(),
            tailHeadroomU = -5.0,
        )
        assertEquals(0.0, d.smbU, 1e-9, "ohne Autorisierung bleibt der Schwanz bindend")
    }

    // ---- DIE GRENZE ENTSTEHT AUCH OHNE ANHEBUNG ---------------------------

    /**
     * RANDFALL 1 (Toni 11.08.): die Basisdosis ist bereits GROESSER als der
     * Markerboden.
     *
     * Der Lift gibt dann unveraendert zurueck - richtig, er soll nicht
     * senken. Er stempelte aber auch nicht, und damit war `authCapU` null.
     * Verwarf das finale Veto danach die groessere Basisdosis, blieben 0 U
     * statt der autorisierten 0,20 U: der Markerdruck verlor seine Wirkung
     * gerade dadurch, dass FUSE ohnehin dosieren wollte.
     *
     * Die Grenze sagt, WIEVIEL der Knopfdruck deckt - nicht, ob dieser
     * Aufruf die Menge erhoeht hat.
     */
    @Test
    fun `die Autorisierungsgrenze entsteht auch wenn die Basis groesser ist`() {
        val p = planUnterBoden(markerAuthorized = true)
        // Basis deutlich ueber dem Plan-Boden.
        val basis = blockiert(FuseController.Block.NONE).copy(smbU = p.floorU + 0.15)
        val d = PrimeRelease.lift(basis, p, state(), markerAuthorized = true)

        assertEquals(basis.smbU, d.smbU, 1e-9, "der Lift darf die groessere Basis nicht senken")
        assertTrue(
            d.markerAuthorizedU > 0.0,
            "die Grenze muss trotzdem entstehen - sonst ist sie beim naechsten Veto weg",
        )
        assertTrue(
            d.markerAuthorizedU <= p.floorU + 1e-9,
            "und sie ist der Markerboden, nicht die Basis: ${d.markerAuthorizedU}",
        )
    }

    /** OHNE Autorisierung entsteht auch auf diesem Weg keine Grenze. */
    @Test
    fun `ohne Autorisierung entsteht auch bei groesserer Basis keine Grenze`() {
        val p = planUnterBoden(markerAuthorized = true)
        val basis = blockiert(FuseController.Block.NONE).copy(smbU = p.floorU + 0.15)
        assertEquals(0.0, PrimeRelease.lift(basis, p, state()).markerAuthorizedU, 1e-9)
    }

    // ---- DIE TRANSPORT-KOPPLUNG -------------------------------------------

    /**
     * DER TEST, AN DEM TONIS AUFLAGE ZU PENDING_MODEL_TOO_SHORT HAENGT.
     *
     * Die Freigabe bei diesem Ablehnungsgrund ist NUR zulaessig, solange die
     * offene Transportmenge - publiziertes, im IOB noch nicht sichtbares
     * Insulin - von BEIDEN Spielraeumen abgezogen wird. Sonst wird sie ein
     * zweites Mal finanziert: der Grund sagt ja gerade, dass ihre Bahnwirkung
     * unbekannt ist.
     *
     * KEINE SIGNATUR KANN DAS HALTEN, und deshalb steht es hier. Der Runner
     * reicht dieselbe Zahl an Politik und Lift - das verhindert VERSCHIEDENE
     * Zahlen, nicht einen fehlenden Abzug. Ein Boolean `transportAccounted`
     * war noch schwaecher: "endlich" gilt auch fuer 0,0.
     *
     * DER AUFBAU ist so gewaehlt, dass der Abzug ALLEIN entscheidet:
     *   capIob 0,50, iobTH = maxIOB = 0,60  ->  Spielraum 0,10 = zwei Schritte
     *   Transportmenge 0,05                 ->  Spielraum 0,05 = ein Schritt
     *   Transportmenge 0,10                 ->  Spielraum 0,00 = nichts
     * Die MITTLERE Zeile ist die wichtige: sie zeigt, dass der Abzug der Menge
     * nach wirkt und nicht als An-/Aus-Schalter. Ein Test mit nur den beiden
     * Endpunkten waere auch mit einem groben `if (transport > 0) caps = 0` gruen.
     *
     * MUTATIONSPROBE, nachgemessen: entfernt man `- transportCommitmentU` aus
     * dem iobTH-Term ODER aus dem maxIOB-Term in PrimeRelease.lift, liefert
     * dieser Test 0,05 U statt 0,0 und wird rot. Beide einzeln geprueft.
     */
    @Test
    fun `die Transportmenge kappt den autorisierten Anteil ueber BEIDE Spielraeume`() {
        val p = planUnterBoden(markerAuthorized = true)
        val eng = FuseController.State(
            health = Health.READY, safetyHold = true, phase = Phase.REARMING,
            netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
            iobThU = 0.6, maxIobU = 0.6, targetMgdl = 100.0, isfMgdlPerU = 55.0,
            smbRatioCorrection = 0.15, smbRatioRise = 0.35,
            rSignedMgdlPerMin = 2.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
            pumpIncrementU = step, maxSmbU = 0.3, pumpBusy = false,
        )

        // ZUERST der Gegenbeleg: OHNE Transportmenge reicht der Spielraum. Ohne
        // ihn koennte der Test auch dann gruen sein, wenn er aus einem ganz
        // anderen Grund nichts freigibt.
        val ohne = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, eng,
            markerAuthorized = true, transportCommitmentU = 0.0,
        )
        assertEquals(
            2 * step, ohne.smbU, 1e-9,
            "der Aufbau muss ohne Transportmenge zwei Schritte hergeben",
        )

        // DIE MITTLERE STUFE: der Abzug wirkt der MENGE NACH.
        assertEquals(
            step,
            PrimeRelease.lift(
                blockiert(FuseController.Block.GUARD_FLOOR), p, eng,
                markerAuthorized = true, transportCommitmentU = 0.05,
            ).smbU, 1e-9,
            "die halbe Transportmenge muss den halben Spielraum kosten",
        )

        // UND JETZT mit unterwegs befindlichem Insulin: nichts mehr.
        val mit = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, eng,
            markerAuthorized = true, transportCommitmentU = 0.10,
        )
        assertEquals(
            0.0, mit.smbU, 1e-9,
            "unterwegs befindliches Insulin darf nicht ein zweites Mal finanziert werden",
        )
        assertEquals(
            0.0, mit.markerAuthorizedU, 1e-9,
            "und es entsteht auch keine Autorisierungsgrenze, die ein Boden spaeter hebt",
        )
    }

    /**
     * BEIDE Spielraeume EINZELN. Der Test oben laesst iobTH und maxIOB gleich
     * gross - dort deckt eine Mutation beide auf einmal ab. Hier bindet
     * jeweils nur EINER, damit ein entfernter Abzug im ANDEREN nicht
     * unbemerkt bleibt.
     */
    @Test
    fun `die Transportmenge kappt auch wenn nur eine der beiden Grenzen bindet`() {
        val p = planUnterBoden(markerAuthorized = true)
        fun eng(iobTh: Double, maxIob: Double) = FuseController.State(
            health = Health.READY, safetyHold = true, phase = Phase.REARMING,
            netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
            iobThU = iobTh, maxIobU = maxIob, targetMgdl = 100.0, isfMgdlPerU = 55.0,
            smbRatioCorrection = 0.15, smbRatioRise = 0.35,
            rSignedMgdlPerMin = 2.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
            pumpIncrementU = step, maxSmbU = 0.3, pumpBusy = false,
        )
        // capIob 0,50; die jeweils ANDERE Grenze ist weit offen.
        for ((name, st) in listOf(
            "nur iobTH bindet" to eng(iobTh = 0.6, maxIob = 8.0),
            "nur maxIOB bindet" to eng(iobTh = 8.0, maxIob = 0.6),
        )) {
            assertEquals(
                2 * step,
                PrimeRelease.lift(
                    blockiert(FuseController.Block.GUARD_FLOOR), p, st,
                    markerAuthorized = true, transportCommitmentU = 0.0,
                ).smbU, 1e-9, "$name: ohne Transportmenge zwei Schritte",
            )
            assertEquals(
                0.0,
                PrimeRelease.lift(
                    blockiert(FuseController.Block.GUARD_FLOOR), p, st,
                    markerAuthorized = true, transportCommitmentU = 0.10,
                ).smbU, 1e-9, "$name: mit Transportmenge nichts",
            )
        }
    }

    // ---- OHNE BAHN (predictorfreier Markerpfad) ---------------------------

    /**
     * DIE DOSIERLOGIK DES PREDICTORFREIEN PFADES, in derselben
     * Zusammensetzung, die `markerFallbackCycle` baut: plan(null) + lift ohne
     * Schwanz-Headroom. Wird die Bahn verworfen, ist das hier die GANZE
     * Rechnung - es gibt keine Kandidatensuche, keinen Guard, keinen Schwanz.
     */
    @Test
    fun `ohne Bahn entsteht mit Autorisierung eine Menge`() {
        val p = PrimeRelease.plan(ohneBahn(markerAuthorized = true))
        assertTrue(p.active, "der Plan muss ohne Bahn stehen koennen: ${p.reason}")
        assertEquals("PRIME", p.reason)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
            markerAuthorized = true,
            tailHeadroomU = null,          // ohne Bahn gibt es keinen
        )
        assertTrue(d.smbU > 0.0, "ohne Bahn und mit Autorisierung muss etwas herauskommen")
        assertEquals(d.smbU, d.markerAuthorizedU, 1e-9, "und alles davon ist autorisiert")
    }

    /**
     * OHNE AUTORISIERUNG IST DIE FEHLENDE BAHN EIN NEIN, und zwar unter
     * eigenem Namen. NO_TRAJECTORY heisst "es gab nichts zu pruefen",
     * NOT_FINITE hiesse "die Zahl war kaputt" - zwei verschiedene Lagen, die
     * unter einem Grund im Export nicht mehr aufloesbar waeren. Genau
     * deshalb ist das Feld nullbar und nicht NaN.
     */
    @Test
    fun `ohne Bahn und ohne Autorisierung sperrt der Plan`() {
        val p = PrimeRelease.plan(ohneBahn(markerAuthorized = false))
        assertTrue(!p.active)
        assertEquals("NO_TRAJECTORY", p.reason)
    }

    /** Eine KAPUTTE Bahn bleibt NOT_FINITE - die beiden duerfen nicht
     *  zusammenfallen, sonst hat die Nullbarkeit nichts gebracht. */
    @Test
    fun `eine nicht endliche Bahn bleibt NOT_FINITE`() {
        val p = PrimeRelease.plan(ohneBahn(markerAuthorized = true).copy(safetyMinLowerMgdl = Double.NaN))
        assertTrue(!p.active)
        assertEquals("NOT_FINITE", p.reason)
    }

    private fun ohneBahn(markerAuthorized: Boolean) = PrimeRelease.Input(
        enabled = true, mealMarkerActive = true,
        armedTsMs = 1_000_000L, windowStartTsMs = 0L,
        nowMs = 1_000_000L + 3 * 60_000L,
        envelopeU = 1.2, spentU = 0.0,
        safetyMinLowerMgdl = null,
        guardFloorMgdl = 70.0, isfMgdlPerU = 55.0, pumpIncrementU = step,
        markerAuthorized = markerAuthorized,
    )

    /**
     * DIE HARTEN MENGENDECKEL BLEIBEN. maxIOB und iobTH kappen den Lift auch
     * im autorisierten Fall - sie sind keine Tiefschutz-Tore.
     */
    @Test
    fun `maxIOB kappt auch den autorisierten Anteil`() {
        val p = planUnterBoden(markerAuthorized = true)
        val eng = state(iobTh = 0.5, maxIob = 0.5)   // capIob 0,5 -> Spielraum 0
        val d = PrimeRelease.lift(blockiert(FuseController.Block.SAFETY_HOLD), p, eng, markerAuthorized = true)
        assertEquals(0.0, d.smbU, "ein erschoepftes IOB-Budget bleibt bindend")
    }

    /**
     * Und das SCHUETZENDE Zero-Temp bleibt. Es wird nicht "LOW abgeschaltet" -
     * die Basalabsenkung laeuft parallel weiter, waehrend der Marker-Anteil
     * hinausgeht. Beides zugleich ist kein Widerspruch: das eine ist die
     * erklaerte Mahlzeit, das andere der Schutz gegen ihr Ausbleiben.
     */
    @Test
    fun `das schuetzende Zero-Temp bleibt neben der Freigabe bestehen`() {
        val p = planUnterBoden(markerAuthorized = true)
        val d = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
            markerAuthorized = true,
        )
        assertTrue(d.smbU > 0.0)
        assertEquals(FuseController.TbrAction.ZERO_TEMP, d.tbr, "die Basalabsenkung darf nicht verlorengehen")
    }

    // ---- Die gemeinsame Autorisierungspolitik A/B (Toni 18.08.) -----------

    /**
     * DER PFLICHTTEST: gemessenes Tief + Marker + offene Huelle ergibt in
     * BEIDEN Phasen exakt 0 U.
     *
     * Phase A und Phase B stammen aus DERSELBEN Autorisierung, also muss auch
     * die Politik dieselbe sein. Waere sie es nicht, entstuende genau die
     * Sorte Sonderfall, die spaeter niemand mehr erklaeren kann: derselbe
     * Knopfdruck, dasselbe Budget, aber je nach Minute ein anderer
     * Sicherheitsbegriff.
     *
     * Fuer Phase A ist der Nachweis der Lift. Fuer Phase B ist er die
     * Politik-Tabelle - dort gibt es noch keinen Lift, und genau deshalb
     * steht die Tabelle schon hier: sie ist die Vorgabe, gegen die der
     * kommende Phase-B-Lift gebaut wird.
     */
    @Test
    fun `gemessenes Tief gibt in beiden Phasen exakt null frei`() {
        val p = planUnterBoden(markerAuthorized = true)
        assertTrue(p.active, "die Huelle ist offen - der Plan steht")

        // Phase A: der Lift verweigert.
        val a = PrimeRelease.lift(
            blockiert(FuseController.Block.SAFETY_HOLD), p, state(),
            markerAuthorized = true,
        )
        assertEquals(0.0, a.smbU, "Phase A: gemessenes Tief ist nicht hebbar")

        // Phase B: dieselbe Politik, hier als Tabelle geprueft.
        assertTrue(
            !MarkerAuthorization.lifts(FuseController.Block.SAFETY_HOLD),
            "Phase B: gemessenes Tief ist nicht hebbar",
        )
    }

    /**
     * UND DIE GEGENRICHTUNG: eine ausschliesslich PROGNOSTIZIERTE Blockade
     * darf in beiden Phasen bis zum jeweiligen Soll angehoben werden.
     *
     * Ohne diese Haelfte waere der Test oben mit einem Lift erfuellbar, der
     * gar nichts mehr hebt - und das Fundament waere sinnlos.
     */
    @Test
    fun `eine prognostizierte Blockade ist in beiden Phasen hebbar`() {
        val p = planUnterBoden(markerAuthorized = true)
        val a = PrimeRelease.lift(
            blockiert(FuseController.Block.GUARD_FLOOR), p, state(),
            markerAuthorized = true,
        )
        assertTrue(a.smbU > 0.0, "Phase A: der Guard-Boden ist eine Prognose")
        assertTrue(a.smbU <= p.floorU + 1e-9, "und nicht mehr als der Plan vorsieht")

        for (block in listOf(
            FuseController.Block.GUARD_FLOOR,
            FuseController.Block.NO_DEMAND,
            FuseController.Block.BELOW_PUMP_INCREMENT,
            FuseController.Block.NONE,
        )) assertTrue(
            MarkerAuthorization.lifts(block),
            "Phase B: $block ist eine Modellaussage und MUSS hebbar sein",
        )
    }

    /**
     * DIE HARTEN BLOECKE, EINZELN AUFGEZAEHLT.
     *
     * DIESER TEST WAR VORHER WIRKUNGSLOS (Toni 18.08., P1). Er verglich
     * `lifts(block)` gegen `block in setOf(...hebbare...)` und behauptete im
     * Kommentar, damit jeden neuen Enumwert zu einer Entscheidung zu zwingen.
     * Tatsaechlich ergab ein neuer Wert auf BEIDEN Seiten `false` - der Test
     * blieb gruen, und die Zusicherung stand nur im Kommentar.
     *
     * Der eigentliche Riegel sitzt jetzt im Compiler: [MarkerAuthorization.lifts]
     * ist ein `when` ohne `else`, ein neuer Enumwert laesst das Modul nicht
     * mehr uebersetzen. Beim ersten Uebersetzen hat er prompt `NO_INPUT`
     * eingefordert - einen Wert, der in keiner der beiden frueheren Mengen
     * stand.
     *
     * Dieser Test haelt zusaetzlich die AUFTEILUNG fest, und zwar so, dass
     * ein Vergessen auffliegt:
     *
     *   beide Mengen einzeln aufgezaehlt, hebbar UND hart;
     *   ihre VEREINIGUNG muss alle Enumwerte ergeben;
     *   ihr SCHNITT muss leer sein.
     */
    @Test
    fun `die Aufteilung deckt jeden Block genau einmal ab`() {
        val hebbar = setOf(
            FuseController.Block.NONE,
            FuseController.Block.NO_DEMAND,
            FuseController.Block.BELOW_PUMP_INCREMENT,
            FuseController.Block.GUARD_FLOOR,
            // TAIL in BEIDEN Gestalten: der fruehe Block bei headroom <= 0
            // und die spaetere Kappe bei headroom > 0 sind dieselbe
            // Haftungsprognose. Nur die Kappe zu heben ergaebe am Nullpunkt
            // eine Kante, und die negative Seite ist nach Phase A der
            // Normalfall (Toni 18.08.).
            FuseController.Block.TAIL,
        )
        val hart = setOf(
            FuseController.Block.SAFETY_HOLD,
            FuseController.Block.HEALTH_NOT_READY,
            FuseController.Block.HORIZON_MISSING,
            FuseController.Block.NO_INPUT,
            FuseController.Block.IOB_TH_REACHED,
            FuseController.Block.MAX_IOB_REACHED,
            FuseController.Block.LEDGER_HOLD,
            FuseController.Block.PUMP_BUSY,
            // CANDIDATE bleibt hart: ein SAMMELBLOCK aus modellbasierten UND
            // technischen Ablehnungen. Pauschal zu heben waere fail-open.
            FuseController.Block.CANDIDATE,
        )

        assertEquals(
            FuseController.Block.entries.toSet(), hebbar + hart,
            "die Vereinigung MUSS alle Blockwerte ergeben - ein neuer Wert faellt hier auf",
        )
        assertTrue(
            (hebbar intersect hart).isEmpty(),
            "kein Block darf auf beiden Seiten stehen: ${hebbar intersect hart}",
        )

        for (block in hebbar) assertTrue(
            MarkerAuthorization.lifts(block), "$block MUSS hebbar sein",
        )
        for (block in hart) assertTrue(
            !MarkerAuthorization.lifts(block), "$block MUSS hart bleiben",
        )
    }

    /**
     * OHNE Autorisierung ist NUR der Bedarfsteil hebbar - `GUARD_FLOOR` nicht.
     *
     * Das ist der Unterschied, den der Markerdruck ueberhaupt macht. Ohne
     * diesen Test waere eine Politik moeglich, die den Guard-Boden IMMER
     * hebt, und die Autorisierung waere folgenlos.
     */
    @Test
    fun `ohne Autorisierung ist der Guard-Boden nicht hebbar`() {
        assertTrue(
            !MarkerAuthorization.lifts(FuseController.Block.GUARD_FLOOR, authorized = false),
            "ohne Autorisierung bleibt der Guard-Boden stehen",
        )
        for (block in listOf(
            FuseController.Block.NONE,
            FuseController.Block.NO_DEMAND,
            FuseController.Block.BELOW_PUMP_INCREMENT,
        )) assertTrue(
            MarkerAuthorization.lifts(block, authorized = false),
            "$block braucht keine Autorisierung - hier fehlte nur Bedarf",
        )
    }

    /** Und die abgeleiteten Mengen stimmen mit der Funktion ueberein. */
    @Test
    fun `die abgeleiteten Mengen folgen der Funktion`() {
        for (block in FuseController.Block.entries) {
            assertEquals(
                MarkerAuthorization.lifts(block, authorized = true),
                block in MarkerAuthorization.LIFTABLE_ON_AUTHORIZATION, "$block mit Autorisierung",
            )
            assertEquals(
                MarkerAuthorization.lifts(block, authorized = false),
                block in MarkerAuthorization.LIFTABLE_WITHOUT_AUTHORIZATION, "$block ohne",
            )
        }
    }
}

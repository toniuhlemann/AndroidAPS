package app.aaps.fuse.core.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER FINALE RIEGEL GEGEN NEUES POSITIVES INSULIN (Toni 19.08., P0).
 *
 * DER GEMESSENE ABEND, aus dem Trail:
 *
 *     17:49        Marker
 *     17:50-18:13  24 positive Zyklen, zusammen 3,70 U
 *     ab 17:55     FUSE meldet FALLING_WITH_BOLUS_OVERCOVERAGE
 *     danach       trotzdem 19 SMBs mit zusammen 2,95 U
 *     zeitweise    gleichzeitig TBR = ZERO_TEMP und SMB = 0,15 U
 *     18:13        BG 98, UKF -3,13/min, IOB 4,73 U - und nochmals 0,20 U
 *     18:47        Minimum 58,2 mg/dl bei 3,20 U IOB
 *
 * DIE 3,70 U SPRENGTEN DIE HUELLE NICHT - der Build trug damals
 * `primeEnvelopeU = 3,90 U`. Das Problem ist also kein Budgetueberlauf,
 * sondern dass die Autorisierung trotz klar gemessener Abwaertslage fast
 * vollstaendig ausgeschoepft wurde.
 *
 * DER ARCHITEKTURFEHLER. [LowThreatGate.evaluate] beantwortete zwei Fragen in
 * einem Verdikt - "faellt es gemessen und ist es durch Bolus ueberdeckt?" und
 * "bringt eine Zero-TBR noch 5 mg/dl?" - und das Ergebnis steuerte NUR die
 * TBR. Vier Minuten Zero-TBR halten bei 0,50 U/h rund 0,033 U zurueck,
 * waehrend gleichzeitig 0,60 U SMB dazukamen.
 *
 * Um 18:13 wurde es deutlicher: die Null galt wegen BENEFIT_BELOW_THRESHOLD
 * als nutzlos - und daraus folgte faktisch, dass zusaetzliche SMBs wieder
 * erlaubt waren. "Basal zurueckhalten hilft nicht mehr" und "mehr Bolus ist
 * sicher" sind zwei vollstaendig verschiedene Aussagen.
 */
class MeasuredDescentRiskTest {

    private val boden = 70.0
    private val isf = 60.0

    private fun risiko(
        bg: Double,
        rate: Double,
        bolus: Double?,
        gesund: Boolean = true,
        horizon: Double = 120.0,
    ) = LowThreatGate.measuredDescentRisk(
        signalHealthy = gesund,
        bgMgdl = bg,
        fallRatePerMin = rate,
        bolusIobU = bolus,
        isfMgdlPerU = isf,
        guardFloorMgdl = boden,
        horizonMin = horizon,
    )

    // ---- Der gemessene Abend ---------------------------------------------

    /**
     * 17:54 - der Boden liegt NOCH ausserhalb des Nahhorizonts. Der Marker
     * darf hier liefern; die Aenderung greift nicht zu frueh.
     */
    @Test
    fun `vor der Lage bleibt die Markerfreigabe unberuehrt`() {
        // BG 210, langsam fallend: bis zum Boden dauert es ueber zwei Stunden.
        val r = risiko(bg = 210.0, rate = -1.0, bolus = 4.0)
        assertFalse(r.active, "Bodenkontakt erst in ${r.minutesToFloor} min - keine nahe Gefahr")
        assertEquals(LowThreatGate.DENY_TOO_FAR, r.denial)
    }

    /**
     * 17:55 - ab hier steht die Lage fest: gemessen fallend, vom Bolus
     * ueberdeckt, Boden im Nahhorizont. Positives Insulin ist damit 0.
     */
    @Test
    fun `bei gemessener Abwaertslage ist positives Insulin null`() {
        val r = risiko(bg = 140.0, rate = -2.0, bolus = 4.0)
        assertTrue(r.active, "die Lage MUSS erkannt werden")
        assertNull(r.denial)
        assertTrue(r.overcoverageMgdl!! > 0.0, "der Bolus deckt ueber den Boden hinaus")
        assertTrue(r.minutesToFloor!! <= 120.0)
    }

    /**
     * 18:13 - der schaerfste Fall. BG 98, UKF -3,13/min, IOB 4,73 U.
     *
     * HIER GILT DER RIEGEL AUCH DANN, wenn eine Zero-TBR nichts mehr bringt:
     * das Risiko haengt an den Schritten 1-3 und kennt den Basalnutzen gar
     * nicht. Genau diese Entkopplung ist der P0.
     */
    @Test
    fun `der 18-13-Fall bleibt gesperrt, auch wenn die Null nichts mehr bringt`() {
        val r = risiko(bg = 98.0, rate = -3.13, bolus = 4.73, horizon = 30.0)
        assertTrue(r.active, "gemessen fallend, ueberdeckt, Boden in ${r.minutesToFloor} min")
        // 28 mg/dl bis zum Boden bei 3,13/min: rund 9 Minuten.
        assertTrue(r.minutesToFloor!! < 10.0, "der Boden ist zum Greifen nah: ${r.minutesToFloor}")
    }

    @Test
    fun `Fruehstueck ist bei 30 Minuten noch nicht akut aber bei 120 voll gesperrt`() {
        // Live 21.08. 09:18: q1 112,6, UKF -0,49, Bolus-IOB 1,21.
        // Mit dem spaeteren q1 um 88 lag der lineare Bodenkontakt weiterhin
        // jenseits 30, aber klar innerhalb 120 Minuten. Genau diese Kopplung
        // hat die ganze Phase A statt nur die akute Kante gesperrt.
        val nah = risiko(bg = 88.0, rate = -0.49, bolus = 1.21, horizon = 30.0)
        val tbrFenster = risiko(bg = 88.0, rate = -0.49, bolus = 1.21, horizon = 120.0)

        assertFalse(nah.active)
        assertEquals(LowThreatGate.DENY_TOO_FAR, nah.denial)
        assertTrue(tbrFenster.active)
        assertTrue(tbrFenster.minutesToFloor!! > 30.0)
    }

    // ---- Was den Riegel NICHT ausloest ------------------------------------

    /**
     * EINE STEIGENDE SCHNELLE MAHLZEIT bleibt unberuehrt - das ist die
     * Gegenkontrolle, ohne die der Riegel jede Mahlzeit aushungern koennte.
     */
    @Test
    fun `eine steigende Mahlzeit loest den Riegel nicht aus`() {
        val r = risiko(bg = 160.0, rate = +2.5, bolus = 3.0)
        assertFalse(r.active)
        assertEquals(LowThreatGate.DENY_NOT_FALLING, r.denial)
    }

    /**
     * FALLEND, ABER NICHT UEBERDECKT: der Bolus reicht nicht bis zum Boden.
     * Dann ist der Fall keine selbstgemachte Gefahr, und der normale Pfad
     * darf weiter arbeiten.
     */
    @Test
    fun `fallend ohne Bolus-Ueberdeckung ist kein Risiko`() {
        val r = risiko(bg = 200.0, rate = -2.0, bolus = 1.0)
        assertFalse(r.active, "1 U x 60 = 60 mg/dl gegen 130 mg/dl Strecke")
        assertEquals(LowThreatGate.DENY_NO_OVERCOVERAGE, r.denial)
    }

    /** Unbekanntes Bolus-IOB ist KEIN Nachweis - aber auch kein Freibrief:
     *  der Riegel greift nicht, der Rest des Schutzes bleibt. */
    @Test
    fun `unbekanntes Bolus-IOB erzeugt kein Risiko`() {
        assertFalse(risiko(bg = 140.0, rate = -2.0, bolus = null).active)
    }

    @Test
    fun `ohne gesundes Signal gibt es keinen Nachweis`() {
        val r = risiko(bg = 140.0, rate = -2.0, bolus = 4.0, gesund = false)
        assertFalse(r.active)
        assertEquals(LowThreatGate.DENY_UNHEALTHY, r.denial)
    }

    // ---- Die Entkopplung selbst -------------------------------------------

    /**
     * DIE KERNZUSICHERUNG: das Risiko haengt NICHT am Basalnutzen.
     *
     * Derselbe Zustand, einmal mit wirksamer und einmal mit unwirksamer
     * Zero-TBR - das Risiko ist beide Male dasselbe. Waeren die Fragen noch
     * gekoppelt, koennte die zweite Lage den Riegel oeffnen, und genau das
     * geschah um 18:13.
     */
    @Test
    fun `das Risiko ist unabhaengig vom Basalnutzen`() {
        val nah = risiko(bg = 98.0, rate = -3.13, bolus = 4.73)
        val weiter = risiko(bg = 140.0, rate = -2.0, bolus = 4.0)
        assertTrue(nah.active && weiter.active, "beide Lagen tragen dasselbe Risiko")

        // Und die Nutzenfrage wird hier gar nicht gestellt - es gibt in
        // [LowThreatGate.DescentRisk] kein Feld dafuer. Das ist die Trennung.
        assertTrue(
            LowThreatGate.DescentRisk::class.java.declaredFields.none {
                it.name.contains("benefit", ignoreCase = true)
            },
            "der Risiko-Baustein darf den Basalnutzen nicht einmal kennen",
        )
    }

    /**
     * DIE MUTATION AUF `unsafeSituation = true` BRAUCHT EINE SICHERE BASIS.
     *
     * Der Runner-Fall kommt ueber GUARD_FLOOR und traegt deshalb schon vor
     * dem Endriegel `unsafeSituation = true`. Entfernt man die Setzung im
     * Riegel, bleibt jener Test folglich gruen. Hier beginnt die positive
     * Entscheidung dagegen ausdruecklich als sicher; nur der Endriegel kann
     * den Sicherheitsstempel setzen.
     */
    @Test
    fun `der Endriegel setzt den Sicherheitsstempel selbst`() {
        val grant = AuthorizedLift.AuthorizedGrant.of(
            0.25,
            AuthorizedLift.Source.PRIME,
        )!!
        val vorher = FuseController.Decision(
            smbU = 0.25,
            tbr = FuseController.TbrAction.KEEP_CURRENT,
            block = FuseController.Block.NONE,
            insulinReqU = 0.25,
            predAtReleaseMgdl = 140.0,
            minLowerMgdl = 100.0,
            bindingLimit = "primeRelease",
            grant = grant,
            unsafeSituation = false,
        )
        val risk = risiko(bg = 140.0, rate = -2.0, bolus = 4.0)
        assertTrue(risk.active, "die Vorbedingung muss den Riegel oeffnen")

        val nachher = MeasuredDescentGate.apply(vorher, blocksPositive = risk.active)

        assertEquals(0.0, nachher.smbU, 1e-12)
        assertEquals(FuseController.Block.MEASURED_DESCENT_RISK, nachher.block)
        assertTrue(nachher.unsafeSituation, "nur der Endriegel setzt diesen Stempel")
        assertEquals(vorher.tbr, nachher.tbr, "der Riegel entscheidet nicht ueber Basal")
        assertEquals(grant, nachher.grant, "die gestoppte Autorisierung bleibt diagnostisch sichtbar")
    }

    /**
     * RISIKO UND TBR-VERDIKT STAMMEN AUS DERSELBEN RECHNUNG.
     *
     * `evaluate` ruft [LowThreatGate.measuredDescentRisk] fuer die Schritte
     * 1-3 und prueft danach nur noch den Nutzen. Diese Zusicherung haelt fest,
     * dass es EINE Implementierung bleibt: sagt das Verdikt
     * FALLING_WITH_BOLUS_OVERCOVERAGE, MUSS das Risiko aktiv sein - und
     * umgekehrt darf ein inaktives Risiko nie zu diesem Verdikt fuehren.
     *
     * Zwei Kopien wuerden sonst auseinanderlaufen, und der Insulinriegel
     * sperrte bei einer anderen Lage als die Basalantwort.
     *
     * ABSICHTLICH OHNE ZAHLENWERT FUER DEN NUTZEN: den prueft
     * `LowThreatGateTest` seit jeher, und dass jene Tests nach dem Umbau
     * unveraendert gruen blieben, ist der Beleg, dass die TBR-Antwort gleich
     * geblieben ist.
     */
    @Test
    fun `Verdikt und Risiko stammen aus derselben Rechnung`() {
        val faelle = listOf(
            Triple(140.0, -2.0, 4.0),    // fallend, ueberdeckt, nah
            Triple(210.0, -1.0, 4.0),    // Boden zu weit
            Triple(160.0, +2.5, 3.0),    // steigt
            Triple(200.0, -2.0, 1.0),    // nicht ueberdeckt
            Triple(98.0, -3.13, 4.73),   // der 18:13-Fall
        )
        for ((bg, rate, bolus) in faelle) {
            val r = risiko(bg, rate, bolus)
            val v = LowThreatGate.evaluate(
                measuredLow = false,
                signalHealthy = true,
                bgMgdl = bg,
                fallRatePerMin = rate,
                bolusIobU = bolus,
                isfMgdlPerU = isf,
                guardFloorMgdl = boden,
                scheduledBasalUPerH = 0.7,
                remainingEffect = { t -> (t / 120.0).coerceIn(0.0, 1.0) },
            ).verdict
            if (v == LowThreatGate.Verdict.FALLING_WITH_BOLUS_OVERCOVERAGE) assertTrue(
                r.active,
                "BG $bg: das Verdikt setzt das Risiko voraus",
            )
            if (!r.active) assertEquals(
                LowThreatGate.Verdict.NONE, v,
                "BG $bg: ohne Risiko darf es das Verdikt nicht geben",
            )
        }
    }
}

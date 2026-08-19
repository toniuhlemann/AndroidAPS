package app.aaps.fuse.core.controller

import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase as ObserverPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DIE FUENF PFLICHTFAELLE DES PHASE-B-LIFTS (Toni 18.08.).
 *
 *     TAIL headroom +0,01      -> Fundament-Soll kommt durch
 *     TAIL headroom -0,01      -> DASSELBE Soll kommt durch
 *     SAFETY_HOLD              -> 0 U
 *     CANDIDATE technisch      -> 0 U
 *     normal 0,15 / Soll 0,05  -> weiterhin 0,15 U
 *
 * Sie laufen hier durch die VOLLSTAENDIGE Phase-B-Kette - Snapshot, Politik,
 * Kappen, Rasterung -, nicht nur durch die Politikfunktion. Ein Test auf der
 * Tabelle allein wuerde nicht merken, wenn eine Kappe dazwischen den Boden
 * wieder wegnimmt.
 *
 * DIE BEIDEN TAIL-FAELLE SIND DER KERN. Sie pruefen, dass am Nullpunkt des
 * Schwanz-Headrooms keine Kante entsteht: bei -0,001 U setzt der Controller
 * `Block.TAIL`, bei +0,001 U erreicht der Fluss die Kappenliste. Waere nur
 * die Kappe hebbar, entschiede ein Unterschied von 0,002 U ueber das ganze
 * Fundament - und die negative Seite ist nach Phase A der Normalfall.
 */
class MealFoundationLiftTest {

    private val t0 = 1_786_000_000_000L
    private val STEP = 0.05

    private fun state(
        maxSmb: Double = 0.30,
        iobTh: Double = 8.0,
        maxIob: Double = 8.0,
    ) = FuseController.State(
        health = Health.READY, safetyHold = false, phase = ObserverPhase.REARMING,
        netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
        iobThU = iobTh, maxIobU = maxIob, targetMgdl = 100.0, isfMgdlPerU = 90.0,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = 2.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = STEP, maxSmbU = maxSmb, pumpBusy = false,
    )

    private fun basis(block: FuseController.Block, smbU: Double = 0.0) = FuseController.Decision(
        smbU = smbU, tbr = FuseController.TbrAction.NO_NEW_POSITIVE, block = block,
        insulinReqU = 0.0, predAtReleaseMgdl = 150.0, minLowerMgdl = 95.0,
        bindingLimit = block.name,
    )

    /**
     * Ein Fundament mitten in Phase B mit offenem Soll: 3,0 U gesamt, 75/25,
     * bei T+30 sind zwei Drittel des Fensters herum und nichts geflossen.
     */
    private fun snapshot(
        minuten: Double = 30.0,
        ausBudgetU: Double = 2.25,
        seitUebergabeU: Double = 0.0,
        autorisiert: Boolean = true,
    ): MealFoundation.Snapshot {
        val auth = MealFoundation.arm(
            markerTs = t0, foundationEnabled = true, totalBudgetU = 3.0, phaseAShare = 0.75,
            primeWindowMin = 15, wallCeilingMin = 45, phaseBUntilMin = 60, pressObservedInThisProcess = true, primeDeclinedByUser = false, markerAuthorized = autorisiert,
        )
        return MealFoundation.snapshot(
            auth, t0 + (minuten * 60_000).toLong(), 0L,
            deliveredFromBudgetU = ausBudgetU, deliveredSinceHandoverU = seitUebergabeU,
            deliveredPhaseAU = ausBudgetU - seitUebergabeU,
            confirmedNotSentPhaseAU = 0.0, bolusStepU = STEP,
        )
    }

    private fun lift(
        block: FuseController.Block,
        basisMenge: Double = 0.0,
        tailHeadroomU: Double? = null,
        snap: MealFoundation.Snapshot = snapshot(),
        st: FuseController.State = state(),
    ) = MealFoundation.lift(
        base = basis(block, basisMenge), snapshot = snap, state = st, tailHeadroomU = tailHeadroomU,
    )

    // ---- Pflichtfall 1 und 2: die Kante am Nullpunkt ----------------------

    /**
     * BEIDE VORZEICHEN DES SCHWANZ-HEADROOMS GEBEN DASSELBE FREI.
     *
     * Negativer Headroom erscheint als `Block.TAIL` (frueher Return im
     * Controller), positiver als Kappe in der Kandidatenliste. Beide sind
     * dieselbe Haftungsprognose ueber 120 Minuten.
     */
    @Test
    fun `beide Vorzeichen des Schwanz-Headrooms geben dasselbe frei`() {
        val negativ = lift(FuseController.Block.TAIL, tailHeadroomU = -0.01)
        val positiv = lift(FuseController.Block.NONE, tailHeadroomU = 0.01)

        assertTrue(negativ.smbU > 0.0, "negativer Headroom darf das Fundament nicht toeten")
        assertEquals(
            positiv.smbU, negativ.smbU, 1e-9,
            "beide Vorzeichen MUESSEN dasselbe ergeben - sonst bleibt die Kante",
        )
        assertEquals(snapshot().dueU, negativ.smbU, 1e-9, "und zwar genau das Soll")
    }

    /**
     * UND DIE PROBE, dass die Kappe ohne Autorisierung sehr wohl bindet -
     * sonst waere der Test oben mit einer Fassung erfuellbar, die den Schwanz
     * gar nicht mehr kennt.
     */
    @Test
    fun `ohne Autorisierung bindet die Schwanzkappe weiterhin`() {
        val d = MealFoundation.lift(
            base = basis(FuseController.Block.NONE),
            snapshot = snapshot(autorisiert = false), state = state(), tailHeadroomU = 0.01,
        )
        assertEquals(0.0, d.smbU, 1e-9, "0,01 U liegt unter einem Pumpenschritt")
    }

    // ---- Pflichtfall 3: gemessenes Tief ------------------------------------

    @Test
    fun `bei gemessenem Tief gibt Phase B nichts frei`() {
        val d = lift(FuseController.Block.SAFETY_HOLD)
        assertEquals(0.0, d.smbU, 1e-9, "gemessenes Tief ist Wirklichkeit")
        assertEquals(0.0, d.markerAuthorizedU, 1e-9, "auch keine autorisierte Teilmenge")
        assertNull(d.authorizedSource, "und keine Herkunft")
        assertEquals(FuseController.Block.SAFETY_HOLD, d.block, "der Block bleibt stehen")
    }

    // ---- Pflichtfall 4: technische Ablehnung -------------------------------

    @Test
    fun `bei einer technischen Ablehnung gibt Phase B nichts frei`() {
        for (block in listOf(
            FuseController.Block.CANDIDATE,
            FuseController.Block.LEDGER_HOLD,
            FuseController.Block.PUMP_BUSY,
            FuseController.Block.HEALTH_NOT_READY,
            FuseController.Block.NO_INPUT,
            FuseController.Block.IOB_TH_REACHED,
            FuseController.Block.MAX_IOB_REACHED,
        )) {
            val d = lift(block)
            assertEquals(0.0, d.smbU, 1e-9, "$block darf nicht hebbar sein")
            assertEquals(block, d.block, "$block muss stehen bleiben")
        }
    }

    // ---- Pflichtfall 5: Mindestversorgung, kein Aufschlag ------------------

    /**
     * LIEFERT DER NORMALE PFAD SCHON MEHR, bleibt es dabei - das Fundament
     * legt NICHTS drauf. 0,15 U Basis gegen 0,05 U Soll ergibt 0,15 U, nicht
     * 0,20 U.
     */
    @Test
    fun `auf eine hoehere Basis legt Phase B nichts drauf`() {
        val snap = snapshot(minuten = 16.0)   // frueh in Phase B: Soll ~0,02
        val d = lift(FuseController.Block.NONE, basisMenge = 0.15, snap = snap)
        assertEquals(0.15, d.smbU, 1e-9, "die hoehere Basis bleibt unveraendert")
        assertEquals("NONE", d.bindingLimit, "und der Lift hat nicht gebunden")
    }

    // ---- Die Herkunft ist typisiert ---------------------------------------

    /**
     * PRIME UND FUNDAMENT SIND IM DATENSATZ UNTERSCHEIDBAR.
     *
     * Ohne das fuehrte der Export beide als "primeRelease", und im Replay
     * waere nicht mehr auszumachen, welche Phase geliefert hat - genau die
     * Frage, um die es dort geht.
     */
    @Test
    fun `die Herkunft ist als FOUNDATION typisiert`() {
        val d = lift(FuseController.Block.GUARD_FLOOR)
        assertTrue(d.smbU > 0.0)
        assertEquals(AuthorizedLift.Source.FOUNDATION, d.authorizedSource)
        assertEquals("mealFoundation", d.bindingLimit, "nicht primeRelease")
        assertEquals(FuseController.STAGE_FOUNDATION, d.capsStage)
        assertEquals(d.smbU, d.markerAuthorizedU, 1e-9, "die Menge ist vollstaendig autorisiert")
    }

    // ---- Die harten Mengengrenzen gelten weiter ---------------------------

    /**
     * MAXSMB, IOBTH UND MAXIOB BINDEN AUCH DEN AUTORISIERTEN ANTEIL.
     *
     * Sie sagen nichts ueber eine Prognose, sondern ueber eine Obergrenze -
     * keine Autorisierung hebt sie.
     */
    @Test
    fun `harte Mengengrenzen binden auch den autorisierten Anteil`() {
        // maxSMB unter dem Soll.
        val eng = lift(FuseController.Block.GUARD_FLOOR, st = state(maxSmb = 0.05))
        assertTrue(eng.smbU <= 0.05 + 1e-9, "maxSMB muss binden: ${eng.smbU}")

        // iobTH praktisch ausgeschoepft.
        val voll = lift(FuseController.Block.GUARD_FLOOR, st = state(iobTh = 0.5, maxIob = 8.0))
        assertEquals(0.0, voll.smbU, 1e-9, "ein erschoepftes iobTH laesst nichts durch")
    }

    /** Die Transporthaftung ebenso - sie ist bereits unterwegs. */
    @Test
    fun `die Transporthaftung bindet den autorisierten Anteil`() {
        val d = MealFoundation.lift(
            base = basis(FuseController.Block.GUARD_FLOOR), snapshot = snapshot(),
            state = state(iobTh = 1.0), transportCommitmentU = 0.6,
        )
        assertEquals(0.0, d.smbU, 1e-9, "offene Transportmenge zaehlt gegen die Grenze")
    }

    // ---- Ausserhalb von Phase B passiert nichts ---------------------------

    @Test
    fun `ausserhalb von Phase B hebt der Lift nicht`() {
        for (minuten in listOf(5.0, 10.0, 70.0, 90.0)) {
            val snap = snapshot(minuten = minuten)
            assertTrue(
                snap.phase != MealFoundation.Phase.PHASE_B,
                "T+$minuten sollte nicht Phase B sein, ist aber ${snap.phase}",
            )
            val d = lift(FuseController.Block.GUARD_FLOOR, snap = snap)
            assertEquals(0.0, d.smbU, 1e-9, "T+$minuten: ausserhalb des Fensters kein Lift")
        }
    }

    /**
     * DER PHASENRIEGEL WIRKT AUCH BEI WIDERSPRUECHLICHEM SNAPSHOT.
     *
     * MEIN TEST DARUEBER PRUEFT IHN NICHT. Ausserhalb des Fensters ist `dueU`
     * ohnehin 0, also greift schon `floorU <= 0` - eine Mutationsprobe
     * (Phasenriegel entfernt) blieb gruen. Genau der Fall, den mein eigener
     * Kommentar am Riegel behauptet zu verhindern: "Ein Riegel, der sich auf
     * eine andere Rechnung verlaesst, ist einer, der beim naechsten Umbau
     * still verschwindet."
     *
     * Hier steht deshalb ein Snapshot, dessen Phase und `dueU` sich
     * WIDERSPRECHEN. Er kann heute nicht entstehen - aber genau davor
     * schuetzt ein Riegel: vor dem Zustand, den eine spaetere Aenderung
     * moeglich macht.
     */
    @Test
    fun `ein widerspruechlicher Snapshot hebt trotz offenem Soll nicht`() {
        val echt = snapshot()
        assertTrue(echt.dueU > 0.0, "der Aufbau MUSS ein offenes Soll haben")

        for (phase in listOf(
            MealFoundation.Phase.NONE,
            MealFoundation.Phase.PHASE_A,
            MealFoundation.Phase.AFTER_WINDOW,
        )) {
            val d = MealFoundation.lift(
                base = basis(FuseController.Block.GUARD_FLOOR),
                snapshot = echt.copy(phase = phase), state = state(),
            )
            assertEquals(0.0, d.smbU, 1e-9, "$phase darf trotz dueU=${echt.dueU} nicht heben")
        }

        // Und dasselbe fuer eine nicht armierte Autorisierung.
        val d = MealFoundation.lift(
            base = basis(FuseController.Block.GUARD_FLOOR),
            snapshot = echt.copy(armed = false), state = state(),
        )
        assertEquals(0.0, d.smbU, 1e-9, "ohne Armierung kein Lift, egal was dueU sagt")
    }

    @Test
    fun `ohne Autorisierung hebt der Lift den Guard-Boden nicht`() {
        val d = MealFoundation.lift(
            base = basis(FuseController.Block.GUARD_FLOOR),
            snapshot = snapshot(autorisiert = false), state = state(),
        )
        assertEquals(0.0, d.smbU, 1e-9)
    }

    // ---- Die drei Vertragsluecken (Toni 18.08.) ---------------------------

    /**
     * KEINE QUELLE OHNE MENGE.
     *
     * Der Lift setzte die Herkunft frueher AUCH bei Betrag 0 - es konnte also
     * "Quelle FOUNDATION ohne autorisierte Menge" entstehen. Eine Herkunft
     * ohne Menge ist keine Aussage, sondern ein Widerspruch, und ein Leser,
     * der auf die Quelle statt auf den Betrag prueft, haette daraus eine
     * Autorisierung gelesen, die es nicht gab.
     */
    @Test
    fun `es entsteht nie eine Quelle ohne Menge`() {
        // Ein Soll unter dem Pumpenschritt ergibt keinen Grant.
        val winzig = snapshot(minuten = 15.1)
        val d = lift(FuseController.Block.GUARD_FLOOR, snap = winzig)
        if (d.markerAuthorizedU <= 0.0) assertNull(
            d.authorizedSource, "Betrag 0 darf keine Herkunft tragen",
        )
        // Und der Typ selbst laesst es gar nicht zu.
        for (betrag in listOf(0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(
            AuthorizedLift.AuthorizedGrant.of(betrag, AuthorizedLift.Source.FOUNDATION),
            "betrag=$betrag",
        )
    }

    /**
     * DIE QUELLE UEBERLEBT DEN AUTHORIZED-FLOOR.
     *
     * `MarkerFloor` kannte nur den Betrag und schrieb `capsStage =
     * STAGE_PRIME` fest - eine Phase-B-Menge kam nach dem `finalVerify` also
     * als PRIME heraus.
     */
    @Test
    fun `nach dem Floor bleibt die Quelle FOUNDATION`() {
        val gehoben = lift(FuseController.Block.GUARD_FLOOR)
        assertEquals(AuthorizedLift.Source.FOUNDATION, gehoben.authorizedSource)

        // Das Veto hat die Menge verworfen - der Floor stellt sie her.
        val verworfen = basis(FuseController.Block.CANDIDATE).copy(
            bindingLimit = "finalVerify:GUARD_FLOOR",
        )
        val wieder = MarkerFloor.apply(verworfen, gehoben.grant, kernelValid = true)
        assertEquals(gehoben.markerAuthorizedU, wieder.smbU, 1e-9)
        assertEquals(
            AuthorizedLift.Source.FOUNDATION, wieder.authorizedSource,
            "die Herkunft MUSS die Wiederherstellung ueberleben",
        )
        assertEquals(FuseController.STAGE_FOUNDATION, wieder.capsStage, "nicht STAGE_PRIME")
    }

    /**
     * DIE AUTORISIERUNG IST GEPINNT.
     *
     * Der Lift liest sie aus dem Snapshot, nicht aus einer aktuellen
     * Preference. Eine Aenderung waehrend der laufenden Mahlzeit darf ihr das
     * Recht, Modellriegel zu ueberstimmen, weder geben noch nehmen.
     */
    @Test
    fun `die Autorisierung kommt aus der gepinnten Momentaufnahme`() {
        assertTrue(lift(FuseController.Block.GUARD_FLOOR).smbU > 0.0, "gepinnt AN")
        assertEquals(
            0.0,
            lift(FuseController.Block.GUARD_FLOOR, snap = snapshot(autorisiert = false)).smbU, 1e-9,
            "gepinnt AUS - und keine Preference der Welt aendert das nachtraeglich",
        )
    }

    /**
     * UNBRAUCHBARE ZAHLEN GEBEN DIE BASIS UNVERAENDERT ZURUECK.
     *
     * NaN in einer Kappe wuerde durch `min` durchschlagen und am Ende eine
     * Menge ergeben, die auf einer Zahl beruht, die es nicht gibt. Ein
     * negatives Restbudget kann kein Schreiber dieses Codes erzeugen.
     */
    @Test
    fun `unbrauchbare Zahlen lassen die Basis unveraendert`() {
        val basis = basis(FuseController.Block.NONE, smbU = 0.10)
        val snap = snapshot()
        val faelle = listOf<Pair<String, () -> FuseController.Decision>>(
            "tailHeadroom NaN" to {
                MealFoundation.lift(basis, snap, state(), tailHeadroomU = Double.NaN)
            },
            "Transport NaN" to {
                MealFoundation.lift(basis, snap, state(), transportCommitmentU = Double.NaN)
            },
            "Transport negativ" to {
                MealFoundation.lift(basis, snap, state(), transportCommitmentU = -1.0)
            },
            "Restbudget NaN" to {
                MealFoundation.lift(basis, snap.copy(remainingInWindowU = Double.NaN), state())
            },
            "Restbudget negativ" to {
                MealFoundation.lift(basis, snap.copy(remainingInWindowU = -0.5), state())
            },
            "Soll NaN" to {
                MealFoundation.lift(basis, snap.copy(dueU = Double.NaN), state())
            },
        )
        // KEIN State-Fall in dieser Liste, und das ist ein Befund: der
        // State-Konstruktor prueft pumpIncrementU, maxSmbU, iobThU und maxIobU
        // selbst auf isFinite und Bereich, netIobU/bolusIobU ueber ihre
        // Betragsgrenzen (abs(NaN) <= 100 ist false), und capIobU ist daraus
        // abgeleitet. Ein unbrauchbarer State laesst sich gar nicht bauen.
        // Die Pruefungen dafuer stehen im Lift als Verteidigung in der Tiefe -
        // sie sind nicht erreichbar, und ein Test, der das behauptet, waere
        // eine Attrappe.
        for ((name, f) in faelle) {
            val d = f()
            assertEquals(basis.smbU, d.smbU, 1e-9, "$name: die Basis MUSS unveraendert bleiben")
            assertNull(d.grant, "$name: und es darf kein Grant entstehen")
        }
    }
}

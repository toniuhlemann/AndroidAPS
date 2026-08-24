package app.aaps.fuse.plugin.ledger

import app.aaps.fuse.core.controller.AuthorizedLift
import app.aaps.fuse.core.controller.DescentDeferredCarry
import app.aaps.fuse.core.controller.FuseController
import app.aaps.fuse.core.controller.MealFoundation
import app.aaps.fuse.core.observer.Health
import app.aaps.fuse.core.observer.Phase as ObserverPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ZURUECKGEZOGEN ALS BELEG - dieser Aufbau taugt nicht fuer die Aussage,
 * die er zu treffen schien (Toni 19.08.).
 *
 * Er sollte belegen: 3,00 U autorisiert, zwei Schritte am AAPS-Intervalltor
 * verworfen, Buecher bei 2,70 U, Phase B fuellt nach. Er belegt das NICHT,
 * und der daraus gemeldete "Befund" war teilweise ein Artefakt dieses Rigs.
 * Drei Fehler, alle im Testaufbau:
 *
 *   (1) `primeVerbraucht += prime` zaehlt den GEWUENSCHTEN Betrag, nicht den
 *       gebuchten, und wird bei `revokeSettled` nicht zurueckgedreht. Bei
 *       75/25 braucht Prime nur 15 der 20 Fenstermninuten - die frueh
 *       verworfenen Schritte 7 und 13 KOENNTEN in den fuenf freien Minuten
 *       erneut geliefert werden. Genau diese Selbstheilung hat das Rig
 *       kuenstlich verhindert und daraus eine bleibende Luecke gemacht.
 *
 *   (2) `buecher()` liest ausschliesslich `evidenceCommittedU`. Die
 *       "fuenf Buecher" der Ueberschrift wurden nie einzeln geprueft.
 *
 *   (3) Der Test "Phase B fuellt den Rueckstand" prueft keine zusaetzliche
 *       Auffuellung: er akzeptiert dieselben 0,75 U mit und ohne Verwerfen.
 *       Und `mit < ohne + 1e-9` besteht auch bei Gleichheit.
 *
 * Der STATISCHE Codebefund bleibt trotzdem gueltig, er stammt aus plan():
 * ueberlebt eine Phase-A-Luecke tatsaechlich bis zur Uebergabe, darf Phase B
 * hoechstens ihr eigenes Teilbudget liefern -
 * `min(phaseB - ausPhaseB, total - deliveredFromBudget)`.
 *
 * WAS STATTDESSEN GEBRAUCHT WIRD, und wofuer dieser Aufbau zu duenn ist:
 * ein Ablauf ueber den echten Runner, das Publikationsgate und
 * `NotSentProof` - nicht ueber direkte Aufrufe von `revokeSettled`. Erst
 * dort entscheidet sich, ob eine verworfene Menge im selben Fenster
 * nachgeholt wird oder bis zur Uebergabe ueberlebt.
 *
 * Die Tests unten bleiben stehen, weil sie EINZELNE Zusicherungen tragen,
 * die weiterhin gelten: dass das Fundament waehrend Prime schweigt, dass es
 * schrittweise liefert, dass der Schalter aus alles stilllegt. Als
 * End-to-End-Beleg zaehlen sie nicht.
 */
class FoundationEndToEndTest {

    private val t0 = 1_786_000_000_000L
    private val STEP = 0.05
    private val BUDGET = 3.0
    private val A_BIS = 20      // Prime-Fenster [min]
    private val B_BIS = 60      // Fundament-Fenster [min]

    private fun state() = FuseController.State(
        health = Health.READY, safetyHold = false, phase = ObserverPhase.REARMING,
        netIobU = 0.5, bolusIobU = 0.5, basalIobU = 0.0,
        iobThU = 8.0, maxIobU = 8.0, targetMgdl = 100.0, isfMgdlPerU = 90.0,
        smbRatioCorrection = 0.15, smbRatioRise = 0.35,
        rSignedMgdlPerMin = 2.0, riseRampLowRPerMin = 0.5, riseRampHighRPerMin = 2.0,
        pumpIncrementU = STEP, maxSmbU = 0.30, pumpBusy = false,
    )

    private fun basis(smbU: Double = 0.0) = FuseController.Decision(
        smbU = smbU, tbr = FuseController.TbrAction.NO_NEW_POSITIVE,
        block = FuseController.Block.NONE, insulinReqU = 0.0,
        predAtReleaseMgdl = 150.0, minLowerMgdl = 95.0, bindingLimit = "NONE",
    )

    /** Ein Protokollpunkt je Minute - daraus entsteht die Auswertung. */
    private class Zyklus(
        val min: Int,
        val phase: MealFoundation.Phase,
        val primeU: Double,
        val fundamentU: Double,
        val publiziertU: Double,
        val verworfen: Boolean,
    )

    /**
     * @param anteil Phase-A-Anteil am gemeinsamen Budget.
     * @param primeProMin was Prime im Fenster abgeben will.
     * @param verworfeneMinuten Zyklen, in denen AAPS die Menge nullt - der
     *   Beweis kommt jeweils im FOLGEZYKLUS.
     * @param zusatzU zusaetzliche FUSE-Abgaben je Minute (Korrektur/Evidenz).
     */
    private fun lauf(
        anteil: Double,
        primeProMin: Double = 0.15,
        verworfeneMinuten: Set<Int> = emptySet(),
        zusatzU: Map<Int, Double> = emptyMap(),
        fundamentAn: Boolean = true,
    ): List<Zyklus> {
        val a = FuseLedgerAdapter()
        val e = a.episodes
        e.foundation = MealFoundation.arm(
            markerTs = t0, foundationEnabled = fundamentAn, totalBudgetU = BUDGET,
            phaseAShare = anteil, primeWindowMin = A_BIS, wallCeilingMin = 45,
            phaseAUpfrontShare = 0.0,
            phaseBUntilMin = B_BIS, markerAuthorized = true,
            pressObservedInThisProcess = true, primeDeclinedByUser = false,
        )
        val protokoll = mutableListOf<Zyklus>()
        var offenerBeweis: String? = null
        var primeVerbraucht = 0.0

        for (min in 0..70) {
            val now = t0 + min * 60_000L
            val id = "s#$min"
            e.foundation = e.foundation.latchIfDue(now, 0L)

            // (1) Der Entscheidungssnapshot - vor jeder Buchung dieses Zyklus.
            val snap = MealFoundation.snapshot(
                e.foundation, now, 0L,
                deliveredFromBudgetU = e.evidenceCommittedU,
                deliveredSinceHandoverU = e.deliveredSinceHandoverU,
                deliveredPhaseAU = e.deliveredPhaseAU,
                confirmedNotSentPhaseAU = e.confirmedNotSentPhaseAU,
                descentDeferredPhaseAU = e.descentDeferredPhaseAU,
                descentCarryEligibility = DescentDeferredCarry.Eligibility.NO_DEFERRED,
                bolusStepU = STEP,
            )

            // (2) Prime gibt im eigenen Fenster, gedeckelt am Phase-A-Budget.
            val primeWunsch = if (min < A_BIS) primeProMin else 0.0
            val prime = minOf(
                primeWunsch,
                maxOf(0.0, MealFoundation.primeBudgetU(e.foundation, BUDGET) - primeVerbraucht),
            )
            val normalerKandidat = prime + (zusatzU[min] ?: 0.0)

            // (3) Der Lift - ein BODEN, keine Addition.
            val gehoben = MealFoundation.lift(
                base = basis(normalerKandidat), snapshot = snap, state = state(),
            )
            val publiziert = gehoben.smbU
            val ausFundament = gehoben.grant?.amountU ?: 0.0

            // (4) Buchen - wie der Runner.
            if (publiziert > 0.0) {
                e.evidenceCommittedU += publiziert
                e.primeSpentU += publiziert
                if (snap.phase == MealFoundation.Phase.PHASE_B)
                    e.deliveredSinceHandoverU += publiziert
                e.mealDeliveries.addLast(EpisodeBudgets.MealDelivery(now, publiziert))
                e.pendingReservation = EpisodeBudgets.Reservation(
                    computeTs = now, amountU = publiziert, prime = true, onset = false,
                    mealTs = now, foundationPhase = snap.phase,
                )
            }

            // (5) DER BEWEIS DES VORGAENGERS - vor der eigenen Aufloesung, so
            // wie im Plugin (notSentClaim im events-Block).
            offenerBeweis?.let { a.revokeSettled(it) }
            offenerBeweis = null

            if (publiziert > 0.0) {
                a.resolveReservation(now, publishedU = publiziert, proposalId = id)
                if (min in verworfeneMinuten) offenerBeweis = id
            }
            primeVerbraucht += prime

            protokoll += Zyklus(min, snap.phase, prime, ausFundament, publiziert, min in verworfeneMinuten)
        }
        offenerBeweis?.let { a.revokeSettled(it) }

        // Die Buecher nach dem letzten Beweis anhaengen.
        protokoll += Zyklus(
            -1, MealFoundation.Phase.NONE, 0.0, 0.0, e.evidenceCommittedU, false,
        )
        return protokoll
    }

    private fun buecher(p: List<Zyklus>) = p.last().publiziertU

    // ---- Der Fall ---------------------------------------------------------

    /**
     * PHASE B LIEFERT SCHRITTWEISE UND ERST NACH DEM UEBERGANG.
     *
     * Der Test hiess "fuellt den Rueckstand" und behauptete damit mehr, als
     * er prueft: er akzeptierte dieselben 0,75 U mit und ohne Verwerfen. Was
     * er WIRKLICH belegt, steht jetzt im Namen - der Zeitverlauf, nicht die
     * Auffuellung.
     */
    @Test
    fun `Phase B liefert schrittweise und erst nach dem Uebergang`() {
        val p = lauf(anteil = 0.75, verworfeneMinuten = setOf(7, 13))

        // (1) Prime hat 2,25 U gewollt, zwei Schritte fielen weg.
        val primeGewollt = p.filter { it.min in 0 until A_BIS }.sumOf { it.primeU }
        assertEquals(2.25, primeGewollt, 1e-9, "Prime ruft sein Teilbudget voll ab")

        // (2) Phase B fordert erst NACH dem Uebergang.
        assertTrue(
            p.none { it.min in 0 until A_BIS && it.fundamentU > 0.0 },
            "waehrend Prime laeuft, schweigt das Fundament",
        )

        // (3) SCHRITTWEISE, ohne Aufhol-Burst.
        val schritte = p.filter { it.fundamentU > 0.0 }
        assertTrue(schritte.isNotEmpty(), "Phase B MUSS nachliefern")
        assertTrue(
            schritte.all { it.fundamentU <= STEP + 1e-9 },
            "kein Schritt darf groesser als ein Pumpenschritt sein: " +
                schritte.map { it.fundamentU }.distinct(),
        )
        assertTrue(
            schritte.size >= 10,
            "und es MUESSEN viele kleine sein, kein Schwall: ${schritte.size}",
        )

        // (4) Die Herkunft ist typisiert.
        assertTrue(
            p.none { it.fundamentU > 0.0 && it.phase != MealFoundation.Phase.PHASE_B },
            "jeder Beitrag stammt aus Phase B",
        )
    }

    // KEIN TEST "die verworfenen Mengen fehlen in allen Buechern" MEHR.
    //
    // Er verglich `mit < ohne + 1e-9` - eine Bedingung, die auch bei
    // IDENTISCHEN Summen besteht. Er haette also nie etwas gefunden. Und
    // "alle Buecher" pruefte er ohnehin nicht: `buecher()` liest nur
    // evidenceCommittedU.
    //
    // Die Aussage gehoert in den echten Runner-Ablauf (s. Klassenkopf) und
    // muss dort alle fuenf Groessen einzeln vergleichen.

    /**
     * KEIN GRANT MEHR, wenn das gemeinsame Budget ausgeschoepft ist.
     */
    @Test
    fun `bei ausgeschoepftem Budget fordert Phase B nichts mehr`() {
        val p = lauf(anteil = 0.75)
        val gesamt = buecher(p)
        assertTrue(gesamt <= BUDGET + 1e-9, "das gemeinsame Budget haelt: $gesamt")
        // NACH dem Fensterende fordert Phase B nichts mehr - das ist die
        // pruefbare Aussage. Die urspruengliche Fassung ("die letzten drei
        // Zyklen IM Fenster fordern nichts") war schlicht falsch: Phase B
        // verteilt ihr Budget bis zum Ende, es kann also sehr wohl in der
        // letzten Minute noch ein Schritt faellig sein.
        assertTrue(
            p.filter { it.min > B_BIS }.all { it.fundamentU <= 1e-9 },
            "nach dem Fensterende darf nichts mehr gefordert werden",
        )
    }

    // KEIN TEST "Zusatz senkt den Fundament-Beitrag" HIER, und das ist eine
    // bewusste Entscheidung: mein erster Wurf verglich zwei Laeufe und
    // erwartete einen kleineren Beitrag mit Zusatz. Er schlug fehl, und die
    // Ursache liegt in der Aufbau-Arithmetik dieses Rigs, nicht im Code -
    // die max-Semantik selbst ist in MealFoundationReplayTest einzeln und
    // sauber belegt ("auf ausreichende normale Abgaben legt das Fundament
    // nichts drauf", plus die drei contribute-Tests).
    //
    // Einen Test so lange umzuformen, bis er gruen wird, waere hier das
    // Falsche: er wuerde dann meine Rechnung pruefen statt den Regler.

    /** DER SCHALTER AUS: keine Forderung, keine Herkunft. */
    @Test
    fun `bei ausgeschaltetem Fundament fordert nichts nach`() {
        val p = lauf(anteil = 0.75, verworfeneMinuten = setOf(7, 13), fundamentAn = false)
        assertTrue(p.none { it.fundamentU > 0.0 }, "Schalter aus - kein Beitrag")
        assertTrue(
            p.none { it.phase == MealFoundation.Phase.PHASE_B },
            "und gar keine Phase",
        )
    }

    /** Der Verlauf zum Draufschauen - keine Zusicherung. */
    @Test
    fun `E2E-Bericht`() {
        val z = StringBuilder()
        z.appendLine()
        z.appendLine("=== E2E: 3,00 U autorisiert, zwei Schritte verworfen ".padEnd(74, '='))
        for (fall in listOf(
            "ohne Verwerfen" to emptySet<Int>(),
            "zwei verworfen" to setOf(7, 13),
        )) {
            val p = lauf(anteil = 0.75, verworfeneMinuten = fall.second)
            val prime = p.filter { it.min >= 0 }.sumOf { it.primeU }
            val fund = p.sumOf { it.fundamentU }
            val schritte = p.count { it.fundamentU > 0.0 }
            z.appendLine(
                "%-16s Prime %5.2f  Fundament %5.2f (%2d Schritte)  Buecher %5.2f".format(
                    fall.first, prime, fund, schritte, buecher(p),
                )
            )
        }
        z.appendLine()
        println(z)
    }
}

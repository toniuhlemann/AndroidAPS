package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DER EINSTELLUNGSDIALOG UND DIE LAUFZEITPRUEFUNG MUESSEN DASSELBE ERLAUBEN.
 *
 * Der Anlass ist ein P0 vom 11.08.: die Obergrenze von `PrimeEnvelopeU` wurde im
 * Key von 2,0 auf 3,0 angehoben, die Laufzeitpruefung in `readConfig()` blieb
 * bei `in 0.0..2.0` stehen. Wer im Dialog 2,5 einstellt - also genau das, wofuer
 * die Anhebung gemacht wurde -, laesst die Pruefung in JEDEM Zyklus werfen. Der
 * Eingangs-Guard macht daraus einen benannten Abbruch, und FUSE gibt danach
 * nichts mehr ab: nicht nur keinen Prime, den ganzen Regler, dauerhaft, ohne
 * Dialogfehler und ohne Alarm. Der einzige Hinweis waere `abortReason` im Trail.
 *
 * Gefunden hat es ein Sweep, nicht ein Test - obwohl der Fehler eine Zeile lang
 * und trivial pruefbar ist. Das ist die Luecke, die diese Datei schliesst.
 *
 * DIE RICHTUNG IST NICHT SYMMETRISCH:
 *  - Laufzeit STRENGER als der Dialog: verbietet legale Einstellungen und
 *    legt den Regler still. Das ist der gefaehrliche Fall.
 *  - Laufzeit WEITER als der Dialog: laesst Werte durch, die nur ueber einen
 *    Import kommen koennen. Unschoen, aber der Kern hat eigene Grenzen.
 * Geprueft wird deshalb vor allem die erste Richtung - an den RAENDERN, denn
 * dort und nur dort unterscheiden sich zwei Intervalle.
 */
class ConfigBoundsTest {

    /** DER CENTRAL-STARTSATZ (Toni 29.08. nachts) als Waechter: die
     *  echten Runtime-/Migrationsdefaults der Profilwerte und der
     *  MEAL-Regler. Wer einen Default aendert, aendert Therapieverhalten
     *  auf jedem Geraet ohne gesetzten Wert - das soll hier auffallen. */
    @Test
    fun `die Startsatz-Defaults stehen fest`() {
        org.junit.jupiter.api.Assertions.assertEquals(3.0, FuseDoubleKey.CorrectionExposureLimitU.defaultValue, 1e-12)
        org.junit.jupiter.api.Assertions.assertEquals(7.0, FuseDoubleKey.MealExposureLimitU.defaultValue, 1e-12)
        org.junit.jupiter.api.Assertions.assertEquals(0.20, FuseDoubleKey.CorrectionDemandRatioCap.defaultValue, 1e-12)
        org.junit.jupiter.api.Assertions.assertEquals(0.35, FuseDoubleKey.MealDemandRatioCap.defaultValue, 1e-12)
        org.junit.jupiter.api.Assertions.assertEquals(140.0, FuseDoubleKey.LivenessBgMinDayMgdl.defaultValue, 1e-12)
        org.junit.jupiter.api.Assertions.assertEquals(160.0, FuseDoubleKey.LivenessBgMinNightMgdl.defaultValue, 1e-12)
        org.junit.jupiter.api.Assertions.assertEquals(110.0, FuseDoubleKey.LivenessBgMinMealMgdl.defaultValue, 1e-12)
        org.junit.jupiter.api.Assertions.assertEquals(1, FuseIntKey.MealArmCycles.defaultValue)
    }

    /** Ein Satz, der durchgehen MUSS - die Mitte des Erlaubten. */
    private fun mitte() = FuseCycleRunner.Config(
        smbRatio = 0.2, smbRatioRise = 0.35, sharedMaxIobU = 7.0,
        riseRampLowR = 0.5, riseRampHighR = 2.0, bolusShareLambda = 1.0,
        onsetChannelEnabled = true, onsetEnvelopeU = 1.5,
        primeReleaseEnabled = true, primeWindowMin = 15, primeEnvelopeU = 1.2,
        maxSmbU = 0.3, guardFloorMgdl = 70.0, lowGateMinBenefitMgdl = 5.0, zeroLatchEnabled = false, zeroLatchCalmExitMin = 20, zeroLatchReasonGoneExitCycles = 0, zeroLatchCalmDistanceMgdl = 30.0, reversalGuardEnabled = false, reversalFallUkf = 2.0, reversalLookbackMin = 20, reversalReboundUkf = 1.0, reversalConfirmCycles = 2, correctionRearmEnabled = false, rearmHoldMin = 5, rearmConfirmCycles = 2, rearmUpUkf = 0.3, lowGateHorizonMin = 120.0, positiveDescentHorizonMin = 30.0, deferredPrimeEnabled = false, markerPrimeDescentHorizonMin = 60.0, deferredPrimeEndMin = 120, livenessChannelEnabled = false, livenessMealPowerMin = 120, correctionExposureLimitU = 3.0, mealExposureLimitU = 7.0, correctionDemandRatioCap = 0.20, mealDemandRatioCap = 0.35, livenessBgMinDayMgdl = 140.0, livenessBgMinNightMgdl = 160.0, livenessBgMinMealMgdl = 110.0, mealArmCycles = 1, livenessReArmMin = 10, iobThPercent = 100,
        releaseHorizonMin = 30, liabilityHorizonMin = 120, driveTauMin = 60, signalRejoinEnabled = false, theilSenWindowMin = 18,
        absorptionCreditWindowMin = 60, markerBoostMaxMin = 45, evidenceReboundOverrideMaxMin = 120,
        nightStartMin = 1380, nightEndMin = 420,
        nightDeadbandMgdl = 45.0, nightDeadbandEnabled = true,
        reboundDeadbandMgdl = 25.0, reboundDeadbandEnabled = true, reboundWindowMin = 45,
        driveLowerQuantilePct = 50, tailGuardEnabled = false, conditionalTailEnabled = true, markerAuthorized = false,
        mealFoundationEnabled = false, mealFoundationPhaseAShare = 1.0, mealFoundationPhaseAUpfrontShare = 0.0, mealFoundationEndMin = 60, tailFloorMgdl = 70.0, tailRecoveryU = 0.0, fastRestraintEnabled = true, endZeroWhenReasonGone = true,
    )

    private fun ok(cfg: FuseCycleRunner.Config, was: String) {
        runCatching { FuseCycleRunner.validate(cfg) }
            .onFailure { throw AssertionError("$was wurde abgelehnt: ${it.message}", it) }
    }

    @Test
    fun `die Mitte des Erlaubten geht durch`() = ok(mitte(), "Standardkonfiguration")

    /**
     * DER FALL, DER DEN REGLER STILLGELEGT HAETTE.
     *
     * Der Dialog bietet bis `PrimeEnvelopeU.max`. Genau dieser Wert muss durch
     * die Laufzeitpruefung kommen - sonst ist die Anhebung des Bereichs eine
     * Falle statt einer Freiheit.
     */
    @Test
    fun `die Marker-Huelle am oberen Rand des Dialogs geht durch`() {
        assertTrue(FuseDoubleKey.PrimeEnvelopeU.max >= 3.0, "der Bereich wurde zurueckgedreht?")
        ok(mitte().copy(primeWindowMin = 15, primeEnvelopeU = FuseDoubleKey.PrimeEnvelopeU.max), "primeEnvelopeU am Maximum")
        ok(mitte().copy(primeWindowMin = 15, primeEnvelopeU = FuseDoubleKey.PrimeEnvelopeU.min), "primeEnvelopeU am Minimum")
    }

    /**
     * Und dasselbe fuer JEDEN Double-Wert, den der Dialog stellen kann.
     *
     * Ein Feld nach dem anderen auf sein Key-Maximum und -Minimum, alle
     * uebrigen in der Mitte. So bleibt bei einem Fehlschlag sofort sichtbar,
     * WELCHES Feld die Laufzeit enger sieht als der Dialog.
     */
    @Test
    fun `jeder Dialogwert an seinen Raendern geht durch`() {
        data class Feld(val name: String, val key: FuseDoubleKey, val setze: (FuseCycleRunner.Config, Double) -> FuseCycleRunner.Config)
        val felder = listOf(
            Feld("smbRatio", FuseDoubleKey.SmbRatio) { c, v -> c.copy(smbRatio = v) },
            Feld("smbRatioRise", FuseDoubleKey.SmbRatioRise) { c, v -> c.copy(smbRatioRise = v) },
            Feld("maxSmbU", FuseDoubleKey.MaxSmbU) { c, v -> c.copy(maxSmbU = v) },
            Feld("guardFloorMgdl", FuseDoubleKey.GuardFloorMgdl) { c, v -> c.copy(guardFloorMgdl = v) },
            Feld("positiveDescentHorizonMin", FuseDoubleKey.PositiveDescentHorizonMin) { c, v -> c.copy(positiveDescentHorizonMin = v) },
            Feld("nightDeadbandMgdl", FuseDoubleKey.NightDeadbandMgdl) { c, v -> c.copy(nightDeadbandMgdl = v) },
            Feld("reboundDeadbandMgdl", FuseDoubleKey.ReboundDeadbandMgdl) { c, v -> c.copy(reboundDeadbandMgdl = v) },
            Feld("tailFloorMgdl", FuseDoubleKey.TailFloorMgdl) { c, v -> c.copy(tailFloorMgdl = v) },
            Feld("tailRecoveryU", FuseDoubleKey.TailRecoveryU) { c, v -> c.copy(tailRecoveryU = v) },
            Feld("bolusShareLambda", FuseDoubleKey.BolusShareLambda) { c, v -> c.copy(bolusShareLambda = v) },
            Feld("onsetEnvelopeU", FuseDoubleKey.OnsetEnvelopeU) { c, v -> c.copy(onsetEnvelopeU = v) },
            Feld("primeEnvelopeU", FuseDoubleKey.PrimeEnvelopeU) { c, v -> c.copy(primeWindowMin = 15, primeEnvelopeU = v) },
        )
        for (f in felder) {
            ok(f.setze(mitte(), f.key.max), "${f.name} = ${f.key.max} (Dialog-Maximum)")
            ok(f.setze(mitte(), f.key.min), "${f.name} = ${f.key.min} (Dialog-Minimum)")
        }
    }

    /** Dieselbe Probe fuer die ganzzahligen Einstellungen. `riseRamp` und die
     *  Horizonte haben ZUSAETZLICH eine Beziehung zueinander - die wird unten
     *  eigens geprueft, hier stehen sie deshalb in vertraeglicher Lage. */
    @Test
    fun `jeder ganzzahlige Dialogwert an seinen Raendern geht durch`() {
        data class Feld(val name: String, val key: FuseIntKey, val setze: (FuseCycleRunner.Config, Int) -> FuseCycleRunner.Config)
        val felder = listOf(
            Feld("iobThPercent", FuseIntKey.IobThPercent) { c, v -> c.copy(iobThPercent = v) },
            Feld("driveTauMin", FuseIntKey.DriveTauMin) { c, v -> c.copy(driveTauMin = v) },
            Feld("absorptionCreditWindowMin", FuseIntKey.AbsorptionCreditWindowMin) { c, v -> c.copy(absorptionCreditWindowMin = v) },
            Feld("markerBoostMaxMin", FuseIntKey.MarkerBoostMaxMin) { c, v -> c.copy(markerBoostMaxMin = v) },
            Feld("driveLowerQuantilePct", FuseIntKey.DriveLowerQuantilePct) { c, v -> c.copy(driveLowerQuantilePct = v) },
            Feld("theilSenWindowMin", FuseIntKey.TheilSenWindowMin) { c, v -> c.copy(signalRejoinEnabled = false, theilSenWindowMin = v) },
            Feld("reboundWindowMin", FuseIntKey.ReboundWindowMin) { c, v -> c.copy(reboundWindowMin = v) },
        )
        for (f in felder) {
            ok(f.setze(mitte(), f.key.max), "${f.name} = ${f.key.max} (Dialog-Maximum)")
            ok(f.setze(mitte(), f.key.min), "${f.name} = ${f.key.min} (Dialog-Minimum)")
        }
        // Der Freigabehorizont am Rand, mit einem Haftungshorizont, der die
        // Beziehung einhaelt - sonst pruefte der Fall die falsche Regel.
        ok(mitte().copy(releaseHorizonMin = FuseIntKey.ReleaseHorizonMin.max, liabilityHorizonMin = 360), "releaseHorizon am Maximum")
        ok(mitte().copy(releaseHorizonMin = FuseIntKey.ReleaseHorizonMin.min), "releaseHorizon am Minimum")
    }

    // ---- Die Pruefung muss auch wirklich pruefen -------------------------

    /**
     * Gegenprobe: ohne sie waere `validate` moeglicherweise leer und alle
     * Zusicherungen oben bestuenden trotzdem.
     */
    @Test
    fun `Werte jenseits des Dialogs werden abgewiesen`() {
        assertThrows(IllegalArgumentException::class.java) {
            FuseCycleRunner.validate(mitte().copy(primeWindowMin = 15, primeEnvelopeU = FuseDoubleKey.PrimeEnvelopeU.max + 0.5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FuseCycleRunner.validate(mitte().copy(guardFloorMgdl = Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FuseCycleRunner.validate(mitte().copy(iobThPercent = FuseIntKey.IobThPercent.max + 1))
        }
    }

    /** Die BEZIEHUNGEN, die kein einzelner Key ausdruecken kann - sie bleiben
     *  bewusst als Literale in der Pruefung und muessen erhalten bleiben. */
    @Test
    fun `die Beziehungen zwischen Feldern gelten weiter`() {
        assertThrows(IllegalArgumentException::class.java) {
            FuseCycleRunner.validate(mitte().copy(riseRampLowR = 3.0, riseRampHighR = 1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FuseCycleRunner.validate(mitte().copy(releaseHorizonMin = 90, liabilityHorizonMin = 60))
        }
    }

    /** CENTRAL-only: die vier Profilwerte sind immer gesetzt (echte
     *  Defaults); gehalten wird die RELATION - CORRECTION nie offener als
     *  MEAL, typisierte Ablehnung statt stillem Tausch. */
    @Test
    fun `die Profilwerte halten die Relation fail-closed`() {
        ok(
            mitte().copy(
                correctionExposureLimitU = 3.0, mealExposureLimitU = 6.0,
                correctionDemandRatioCap = 0.15, mealDemandRatioCap = 0.35,
            ),
            "vollstaendiger Profilsatz",
        )
        // Relation verletzt (Exposure): CORRECTION offener als MEAL.
        assertThrows(IllegalArgumentException::class.java) {
            FuseCycleRunner.validate(
                mitte().copy(correctionExposureLimitU = 7.0, mealExposureLimitU = 6.0)
            )
        }
        // Relation verletzt (Ratio).
        assertThrows(IllegalArgumentException::class.java) {
            FuseCycleRunner.validate(
                mitte().copy(correctionDemandRatioCap = 0.5, mealDemandRatioCap = 0.35)
            )
        }
        // Rahmenverletzung: ein importierter Ausreisser wirft benannt.
        assertThrows(IllegalArgumentException::class.java) {
            FuseCycleRunner.validate(mitte().copy(mealExposureLimitU = 25.0))
        }
    }
}

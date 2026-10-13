package app.aaps.fuse.plugin

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import java.util.Locale

/**
 * DER EINE VERTRAG ueber die einstellbaren FUSE-Werte.
 *
 * Drei Verbraucher, EINE Menge: der Einstellungsbildschirm (Inventar-Wache in
 * `addPreferenceScreen`), der Settings-Bericht im FUSE-Reiter (unten in den
 * technischen Details) und der Vollstaendigkeits-Test. Zwei von Hand
 * gepflegte Listen waren schon einmal die Ursache eines schwarzen
 * Unterbildschirms - deshalb lebt die Menge hier und nirgendwo
 * sonst.
 */
internal val fuseEinstellbareKeys: Set<String> = setOf(
    DoubleKey.ApsSmbMaxIob.key,
    FuseDoubleKey.MaxSmbU.key,
    FuseDoubleKey.SmbRatio.key,
    FuseDoubleKey.SmbRatioRise.key,
    FuseDoubleKey.RiseRampLowR.key,
    FuseDoubleKey.RiseRampHighR.key,
    FuseDoubleKey.GuardFloorMgdl.key,
    FuseDoubleKey.PositiveDescentHorizonMin.key,
    // Das Tieftor war dosierwirksam, aber in keinem Bildschirm (Inventur der Konsolidierung) - jetzt im Tiefschutz.
    FuseDoubleKey.LowGateMinBenefitMgdl.key,
    FuseDoubleKey.LowGateHorizonMin.key,
    FuseDoubleKey.BolusShareLambda.key,
    FuseDoubleKey.OnsetEnvelopeU.key,
    FuseDoubleKey.PrimeEnvelopeU.key,
    FuseDoubleKey.MealFoundationPhaseAShare.key,
    FuseDoubleKey.MealFoundationPhaseAUpfrontShare.key,
    FuseIntKey.PrimeWindowMin.key,
    FuseIntKey.MealFoundationEndMin.key,
    FuseBooleanKey.DeferredPrimeEnabled.key,
    FuseDoubleKey.MarkerPrimeDescentHorizonMin.key,
    FuseIntKey.DeferredPrimeEndMin.key,
    // DER RUHE-AUSGANG AUS PHASE A (v32). Dosierwirksam im Modus
    // CALM_BATCH - er gehoert damit in den Bericht des Reiters, sonst
    // waere eine Einstellung verstellbar, die im Bericht fehlt.
    FuseBooleanKey.CalmRecoveryEnabled.key,
    FuseBooleanKey.PartialRecoveryEnabled.key,
    FuseIntKey.CalmRecoveryCycles.key,
    FuseIntKey.CalmTreatmentMode.key,
    FuseDoubleKey.CalmRecoveryMinUkf.key,
    FuseDoubleKey.CalmRecoveryGuardDistanceMgdl.key,
    FuseBooleanKey.LivenessChannelEnabled.key,
    FuseBooleanKey.LivenessReboundEvidenceExceptionEnabled.key,
    FuseBooleanKey.LivenessBookingExitWithoutReArmEnabled.key,
    FuseBooleanKey.LivenessDriveHoldEnabled.key,
    FuseIntKey.EarlyAdaptiveMealHorizonMin.key,
    FuseBooleanKey.SignalRejoinEnabled.key,
    FuseIntKey.LivenessMealPowerMin.key,
    // Die vier zentralen Profilwerte (CENTRAL-only, echte Defaults).
    FuseDoubleKey.CorrectionExposureLimitU.key,
    FuseDoubleKey.MealExposureLimitU.key,
    FuseDoubleKey.CorrectionDemandRatioCap.key,
    FuseDoubleKey.MealDemandRatioCap.key,
    FuseBooleanKey.ZeroLatchEnabled.key,
    FuseIntKey.ZeroLatchCalmExitMin.key,
    FuseIntKey.ZeroLatchReasonGoneExitCycles.key,
    // 2.0 (Stufe 1, K2): CorrectionSeriesWindowMin und CorrectionSeriesCapU
    // sind nicht mehr einstellbar (Variante 2 entfernt).
    FuseDoubleKey.ZeroLatchCalmDistanceMgdl.key,
    FuseBooleanKey.CorrectionReversalGuardEnabled.key,
    FuseDoubleKey.ReversalFallUkf.key,
    FuseIntKey.ReversalLookbackMin.key,
    FuseDoubleKey.ReversalReboundUkf.key,
    FuseIntKey.ReversalConfirmCycles.key,
    FuseBooleanKey.PositiveCorrectionRearmEnabled.key,
    FuseIntKey.RearmHoldMin.key,
    FuseIntKey.RearmConfirmCycles.key,
    FuseDoubleKey.RearmUpUkf.key,
    FuseDoubleKey.LivenessBgMinDayMgdl.key,
    FuseDoubleKey.LivenessBgMinNightMgdl.key,
    FuseDoubleKey.LivenessBgMinMealMgdl.key,
    FuseIntKey.MealArmCycles.key,
    FuseIntKey.LivenessReArmMin.key,
    FuseDoubleKey.TailFloorMgdl.key,
    FuseDoubleKey.TailRecoveryU.key,
    FuseDoubleKey.NightDeadbandMgdl.key,
    FuseDoubleKey.ReboundDeadbandMgdl.key,
    FuseIntKey.IobThPercent.key,
    FuseIntKey.ReleaseHorizonMin.key,
    FuseIntKey.DriveTauMin.key,
    FuseIntKey.DriveLowerQuantilePct.key,
    FuseIntKey.TheilSenWindowMin.key,
    FuseIntKey.ReboundWindowMin.key,
    FuseIntKey.AbsorptionCreditWindowMin.key,
    FuseIntKey.MarkerBoostMaxMin.key,
    FuseIntKey.EvidenceReboundOverrideMaxMin.key,
    FuseIntKey.LiabilityHorizonMin.key,
    FuseIntKey.NightStartMin.key,
    FuseIntKey.NightEndMin.key,
    // 2.0 (Stufe 1, K3): FastRestraintEnabled, ConditionalTailEnabled,
    // NightDeadbandEnabled und ReboundDeadbandEnabled sind nicht mehr
    // einstellbar (Funktion fest an); die Schluessel bleiben registriert.
    FuseBooleanKey.OnsetChannelEnabled.key,
    FuseBooleanKey.PrimeReleaseEnabled.key,
    FuseBooleanKey.MealFoundationEnabled.key,
    FuseBooleanKey.ExpectationLedgerEnabled.key,
    // 2.0 (Stufe 1, K1): ForecastShadowCollectionEnabled ist nicht mehr einstellbar.
    FuseBooleanKey.MarkerAuthorisesRelease.key,
    FuseBooleanKey.TailGuardEnabled.key,
    FuseBooleanKey.TbrEndZeroWhenReasonGone.key,
    // Das Erwartungskonto (Testkandidat E-034) - eigener Abschnitt.
    FuseBooleanKey.ExpectationAccountEnabled.key,
    FuseBooleanKey.ExpectationGuardExceptionEnabled.key,
    FuseDoubleKey.ExpectationCurveAt30U.key,
    FuseDoubleKey.ExpectationCurveAt60U.key,
    FuseDoubleKey.ExpectationCurveAt90U.key,
    FuseDoubleKey.ExpectationCurveAt120U.key,
    FuseDoubleKey.ExpectationPhi0Stable.key,
    FuseDoubleKey.ExpectationPhi0AfterLow.key,
    FuseDoubleKey.ExpectationStabilityBgMgdl.key,
    FuseIntKey.ExpectationStabilityMin.key,
    FuseDoubleKey.ExpectationStabilityFallMgdlPerMin.key,
    FuseDoubleKey.ExpectationReleaseThresholdU.key,
    FuseIntKey.ExpectationHorizonMin.key,
    FuseStringKey.ExpectationCurve.key,
    // Die fruehe Versorgung (E-034 v2) - eigener Abschnitt.
    FuseBooleanKey.EarlySupplyEnabled.key,
    FuseDoubleKey.EarlySupplyBudgetMaxU.key,
    FuseDoubleKey.EarlySupplyScaleStable.key,
    FuseDoubleKey.EarlySupplyScaleAfterLow.key,
    FuseDoubleKey.EarlySupplyInitialShare.key,
    FuseDoubleKey.EarlySupplyMaxPortionU.key,
    FuseDoubleKey.EarlySupplyMaxPerPeriodU.key,
    FuseIntKey.EarlySupplyPeriodMin.key,
    FuseBooleanKey.EarlySupplyInitialLiftsGuard.key,
    FuseBooleanKey.EarlySupplyInitialLiftsTail.key,
    FuseBooleanKey.EarlySupplyGuardWithExpectation.key,
    FuseBooleanKey.EarlySupplyTailWithExpectation.key,
)

/**
 * Alle Einstellwerte als menschenlesbarer Bericht fuer den FUSE-Reiter.
 *
 * Der Sinn ist nicht Vollstaendigkeit um ihrer selbst willen, sondern der
 * schnelle Blick: WAS WEICHT VOM STANDARD AB. Abweichende Zeilen tragen
 * einen `*` und den Standardwert - ein maxSmb, das nach einem Testlauf auf
 * einem Testwert stehen blieb, soll ins Auge springen, nicht in einer Elf-Kategorien-
 * Navigation versteckt sein (die Lehre aus dem Rig-Lauf, der mit erhoehtem Wert statt
 * 0,3 weiterlief).
 *
 * Gruppiert wie die sieben Einstellungsbereiche und ihre Unterseiten, damit der Bericht gleichzeitig die
 * Landkarte des Einstellungsbildschirms ist.
 */
object FuseSettingsReport {

    fun build(preferences: Preferences): FuseScreenModel.SettingsReport {
        fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
        fun uhr(v: Int) = String.format(Locale.US, "%02d:%02d", v / 60, v % 60)

        // TOLERANZ statt ==: Double-Preferences kommen ueber eine
        // String-/Float-Konvertierung zurueck, und am Geraet stand "0.15
        // [Standard 0.15]" - dieselbe Zahl, faelschlich als Abweichung
        // markiert. Eine Marke, die auf
        // Gleichem feuert, entwertet alle echten Marken.
        fun abweicht(wert: Double, standard: Double) = kotlin.math.abs(wert - standard) > 1e-6

        fun zahl(k: FuseDoubleKey, label: String, einheit: String) = FuseScreenModel.SettingRow(
            key = k.key, label = label, value = "${f2(preferences.get(k))} $einheit".trim(),
            standard = f2(k.defaultValue).takeIf { abweicht(preferences.get(k), k.defaultValue) }?.let { "$it $einheit".trim() },
        )

        fun ganz(k: FuseIntKey, label: String, einheit: String) = FuseScreenModel.SettingRow(
            key = k.key, label = label, value = "${preferences.get(k)} $einheit".trim(),
            standard = k.defaultValue.toString().takeIf { preferences.get(k) != k.defaultValue }?.let { "$it $einheit".trim() },
        )

        fun zeit(k: FuseIntKey, label: String) = FuseScreenModel.SettingRow(
            key = k.key, label = label, value = uhr(preferences.get(k)),
            standard = uhr(k.defaultValue).takeIf { preferences.get(k) != k.defaultValue },
        )

        /**
         * DIE RUHE-BEHANDLUNG ALS NAME, nicht als Zahl.
         *
         * Der Modus liegt als 0/1/2 im Speicher. Im Bericht stand bis eben
         * genau diese Zahl - der Leser haette sie nachschlagen muessen, und
         * eine Umnummerierung waere niemandem aufgefallen. Der Name kommt
         * aus derselben typisierten Abbildung, die der Regler benutzt.
         */
        fun behandlung(k: FuseIntKey, label: String): FuseScreenModel.SettingRow {
            fun name(v: Int) = when (app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.ofSetting(v)) {
                app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.DEMAND_LIMITED -> "bedarfsbegrenzt"
                app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.SHIFT_TO_DEFERRED -> "schrittweise verschieben"
                app.aaps.fuse.core.controller.UpfrontRecovery.CalmTreatment.CALM_BATCH -> "Sofortbatch nach Ruhe"
            }
            return FuseScreenModel.SettingRow(
                key = k.key, label = label, value = name(preferences.get(k)),
                standard = name(k.defaultValue)
                    .takeIf { preferences.get(k) != k.defaultValue },
            )
        }

        /**
         * H8 WIE IM DIALOG, nicht als nackte Zahl (Review 18.09.): wirksam sind
         * nur 0/6/8/10. Ein anderer gespeicherter Wert (Altbestand, Backup)
         * steht ausdruecklich als "wirkt als AUS" da, statt still als "7 min".
         */
        fun horizont(k: FuseIntKey, label: String) = FuseScreenModel.SettingRow(
            key = k.key, label = label, value = EarlyAdaptiveMealHorizonText.zustand(preferences.get(k)),
            standard = EarlyAdaptiveMealHorizonText.eintrag(k.defaultValue).takeIf { preferences.get(k) != k.defaultValue },
        )

        fun schalter(k: FuseBooleanKey, label: String) = FuseScreenModel.SettingRow(
            key = k.key, label = label, value = if (preferences.get(k)) "an" else "aus",
            standard = (if (k.defaultValue) "an" else "aus").takeIf { preferences.get(k) != k.defaultValue },
        )

        /** Die Erwartungskurve als Wirkung, nicht als Zahlenkette. */
        fun kurve(k: FuseStringKey, label: String) = FuseScreenModel.SettingRow(
            key = k.key, label = label, value = ExpectationCurveText.zustand(preferences.get(k)),
            standard = ExpectationCurveText.zustand(k.defaultValue).takeIf { preferences.get(k) != k.defaultValue },
        )

        val maxIob = FuseScreenModel.SettingRow(
            key = DoubleKey.ApsSmbMaxIob.key, label = "max Gesamt-IOB [U]",
            value = f2(preferences.get(DoubleKey.ApsSmbMaxIob)),
            standard = f2(DoubleKey.ApsSmbMaxIob.defaultValue)
                .takeIf { abweicht(preferences.get(DoubleKey.ApsSmbMaxIob), DoubleKey.ApsSmbMaxIob.defaultValue) },
        )

        return FuseScreenModel.SettingsReport(
            gruppen = listOf(
                "Mahlzeit" to listOf(
                    ganz(FuseIntKey.LivenessMealPowerMin, "Mahlzeiten-Frist", "min"),
                    zahl(FuseDoubleKey.MealExposureLimitU, "MEAL Exposure-Limit", "U"),
                    schalter(FuseBooleanKey.MarkerAuthorisesRelease, "Marker-Autorisierung"),
                ),
                "Mahlzeit: Direktdosis (Rueckfall)" to listOf(
                    schalter(FuseBooleanKey.PrimeReleaseEnabled, "Sofort-Freigabe"),
                    ganz(FuseIntKey.PrimeWindowMin, "Freigabe-Fenster", "min"),
                    zahl(FuseDoubleKey.MealFoundationPhaseAUpfrontShare, "Phase A Sofortanteil", ""),
                    // Der Ruhe-Ausgang. Der Modus steht als Zahl im Speicher, im Bericht aber ausgeschrieben.
                    schalter(FuseBooleanKey.CalmRecoveryEnabled, "Ruhe-Ausgang Phase A"),
                    ganz(FuseIntKey.CalmRecoveryCycles, "Ruhezyklen", ""),
                    behandlung(FuseIntKey.CalmTreatmentMode, "Ruhe-Behandlung"),
                    zahl(FuseDoubleKey.CalmRecoveryMinUkf, "Ruhe: Mindestrate", "mg/dl/min"),
                    zahl(FuseDoubleKey.CalmRecoveryGuardDistanceMgdl, "Ruhe: Bodenabstand", "mg/dl"),
                    zahl(FuseDoubleKey.ExpectationPhi0Stable, "Konto v1: Vorgriff stabil", ""),
                    zahl(FuseDoubleKey.ExpectationPhi0AfterLow, "Konto v1: Vorgriff nach Tief", ""),
                    schalter(FuseBooleanKey.ExpectationGuardExceptionEnabled, "Konto v1: Guard-Ausnahme"),
                ),
                "Mahlzeit: Alter Rueckfallweg" to listOf(
                    ganz(FuseIntKey.MealFoundationEndMin, "Fundament-Fenster", "min"),
                    zahl(FuseDoubleKey.MealDemandRatioCap, "MEAL Demand-Ratio-Cap", ""),
                    zahl(FuseDoubleKey.LivenessBgMinMealMgdl, "Druck-Schwelle MEAL", "mg/dl"),
                    ganz(FuseIntKey.MealArmCycles, "Bewaffnung MEAL", "Zyklen"),
                    schalter(FuseBooleanKey.LivenessReboundEvidenceExceptionEnabled, "Rebound-Evidenz-Ausnahme"),
                    schalter(FuseBooleanKey.LivenessBookingExitWithoutReArmEnabled, "Buchungsausgang ohne Sperre"),
                    schalter(FuseBooleanKey.LivenessDriveHoldEnabled, "Halte-Anhebung im Kanalbedarf"),
                    horizont(FuseIntKey.EarlyAdaptiveMealHorizonMin, "Frueher MEAL-Horizont"),
                ),
                "Erwartungskonto" to listOf(
                    schalter(FuseBooleanKey.ExpectationAccountEnabled, "Erwartungskonto"),
                    zahl(FuseDoubleKey.ExpectationCurveAt30U, "Kurve nach 30 min", "U"),
                    zahl(FuseDoubleKey.ExpectationCurveAt60U, "Kurve nach 60 min", "U"),
                    zahl(FuseDoubleKey.ExpectationCurveAt90U, "Kurve nach 90 min", "U"),
                    zahl(FuseDoubleKey.ExpectationCurveAt120U, "Kurve nach 120 min", "U"),
                    ganz(FuseIntKey.ExpectationHorizonMin, "Vorausschau", "min"),
                    zahl(FuseDoubleKey.ExpectationStabilityBgMgdl, "Stabilitaet BG", "mg/dl"),
                    ganz(FuseIntKey.ExpectationStabilityMin, "Stabilitaet Dauer", "min"),
                    zahl(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin, "Stabilitaet Fallgrenze", "mg/dl/min"),
                    zahl(FuseDoubleKey.ExpectationReleaseThresholdU, "Ruecknahme-Schwelle", "U"),
                    kurve(FuseStringKey.ExpectationCurve, "Expertenzeile Kurve"),
                ),
                "Erwartungskonto: Fruehe Versorgung" to listOf(
                    schalter(FuseBooleanKey.EarlySupplyEnabled, "Fruehe Versorgung"),
                    zahl(FuseDoubleKey.EarlySupplyBudgetMaxU, "Budget hoechstens", "U"),
                    zahl(FuseDoubleKey.EarlySupplyScaleStable, "Skalierer stabil", ""),
                    zahl(FuseDoubleKey.EarlySupplyScaleAfterLow, "Skalierer nach Tief", ""),
                    zahl(FuseDoubleKey.EarlySupplyInitialShare, "Anfangsanteil", ""),
                    zahl(FuseDoubleKey.EarlySupplyMaxPortionU, "Weitere Portion hoechstens", "U"),
                    zahl(FuseDoubleKey.EarlySupplyMaxPerPeriodU, "Je Zeitraum hoechstens", "U"),
                    ganz(FuseIntKey.EarlySupplyPeriodMin, "Zeitraum", "min"),
                    schalter(FuseBooleanKey.EarlySupplyInitialLiftsGuard, "G1 Anfang hebt Guard"),
                    schalter(FuseBooleanKey.EarlySupplyInitialLiftsTail, "G2 Anfang hebt Schwanz"),
                    schalter(FuseBooleanKey.EarlySupplyGuardWithExpectation, "G3 Guard mit Erwartung"),
                    schalter(FuseBooleanKey.EarlySupplyTailWithExpectation, "G4 Schwanz mit Erwartung"),
                ),
                "Erwartungskonto: wirkt mit" to listOf(
                    zahl(FuseDoubleKey.PrimeEnvelopeU, "Freigabe-Huelle", "U"),
                    schalter(FuseBooleanKey.MealFoundationEnabled, "Mahlzeitenfundament"),
                    zahl(FuseDoubleKey.MealFoundationPhaseAShare, "Anteil Phase A", ""),
                    ganz(FuseIntKey.AbsorptionCreditWindowMin, "Absorption", "min"),
                    ganz(FuseIntKey.MarkerBoostMaxMin, "Sonderrechte", "min"),
                    schalter(FuseBooleanKey.DeferredPrimeEnabled, "Marker-Prime-Aufschub"),
                    zahl(FuseDoubleKey.MarkerPrimeDescentHorizonMin, "Marker-Horizont", "min"),
                    ganz(FuseIntKey.DeferredPrimeEndMin, "Aufschub-Frist", "min"),
                ),
                "Dosierung und Grenzen" to listOf(
                    zahl(FuseDoubleKey.SmbRatio, "Anteil Korrektur", ""),
                    zahl(FuseDoubleKey.SmbRatioRise, "Anteil Anstieg", ""),
                    zahl(FuseDoubleKey.MaxSmbU, "max Einzel-SMB", "U"),
                    zahl(FuseDoubleKey.RiseRampLowR, "Rampe unten", "r"),
                    zahl(FuseDoubleKey.RiseRampHighR, "Rampe oben", "r"),
                    maxIob,
                    ganz(FuseIntKey.IobThPercent, "iobTH", "% maxIOB"),
                    zahl(FuseDoubleKey.CorrectionExposureLimitU, "CORR Exposure-Limit", "U"),
                    zahl(FuseDoubleKey.CorrectionDemandRatioCap, "CORR Demand-Ratio-Cap", ""),
                ),
                "Dosierung: Tiefschutz" to listOf(
                    zahl(FuseDoubleKey.GuardFloorMgdl, "Guard-Boden", "mg/dl"),
                    zahl(FuseDoubleKey.PositiveDescentHorizonMin, "SMB-Abwaerts-Horizont", "min"),
                    zahl(FuseDoubleKey.LowGateMinBenefitMgdl, "Tieftor Mindestnutzen", "mg/dl"),
                    zahl(FuseDoubleKey.LowGateHorizonMin, "Tieftor Horizont", "min"),
                    schalter(FuseBooleanKey.TailGuardEnabled, "Schwanz-Guard"),
                    ganz(FuseIntKey.LiabilityHorizonMin, "Haftung", "min"),
                    zahl(FuseDoubleKey.TailFloorMgdl, "Schwanz-Boden", "mg/dl"),
                    zahl(FuseDoubleKey.TailRecoveryU, "Schwanz-Erholung", "U"),
                ),
                "Korrektur und Liveness" to listOf(
                    schalter(FuseBooleanKey.LivenessChannelEnabled, "Liveness-Kanal"),
                    zahl(FuseDoubleKey.LivenessBgMinDayMgdl, "Druck-Schwelle Tag", "mg/dl"),
                    zahl(FuseDoubleKey.LivenessBgMinNightMgdl, "Druck-Schwelle Nacht", "mg/dl"),
                    ganz(FuseIntKey.LivenessReArmMin, "Re-Arm-Sperre", "min"),
                    schalter(FuseBooleanKey.OnsetChannelEnabled, "Onset-Kanal"),
                    zahl(FuseDoubleKey.OnsetEnvelopeU, "Onset-Huelle", "U"),
                    schalter(FuseBooleanKey.CorrectionReversalGuardEnabled, "V-Reversal-Schutz"),
                    zahl(FuseDoubleKey.ReversalFallUkf, "Reversal-Fall", "mg/dl/min"),
                    ganz(FuseIntKey.ReversalLookbackMin, "Reversal-Rueckblick", "min"),
                    zahl(FuseDoubleKey.ReversalReboundUkf, "Reversal-Gegenzug", "mg/dl/min"),
                    ganz(FuseIntKey.ReversalConfirmCycles, "Reversal-Bestaetigung", "Zyk"),
                    schalter(FuseBooleanKey.PositiveCorrectionRearmEnabled, "Freigabe-Nachlauf"),
                    ganz(FuseIntKey.RearmHoldMin, "Nachlauf-Dauer", "min"),
                    ganz(FuseIntKey.RearmConfirmCycles, "Nachlauf-Zyklen", "Zyk"),
                    zahl(FuseDoubleKey.RearmUpUkf, "Nachlauf-Schwelle", "mg/dl/min"),
                ),
                "Signal und Prognose" to listOf(
                    zahl(FuseDoubleKey.BolusShareLambda, "Bolus-Lambda", ""),
                    ganz(FuseIntKey.ReleaseHorizonMin, "Horizont", "min"),
                    ganz(FuseIntKey.DriveTauMin, "Tau", "min"),
                    ganz(FuseIntKey.DriveLowerQuantilePct, "Guard-Quantil", "%"),
                    ganz(FuseIntKey.TheilSenWindowMin, "TS-Fenster", "min"),
                    schalter(FuseBooleanKey.SignalRejoinEnabled, "Wiedereinstieg nach Funkluecke"),
                ),
                "Nacht, Rebound und Basal" to listOf(
                    zahl(FuseDoubleKey.NightDeadbandMgdl, "Nacht-Band", "mg/dl"),
                    zeit(FuseIntKey.NightStartMin, "Nacht Beginn"),
                    zeit(FuseIntKey.NightEndMin, "Nacht Ende"),
                    zahl(FuseDoubleKey.ReboundDeadbandMgdl, "Rebound-Band", "mg/dl"),
                    ganz(FuseIntKey.ReboundWindowMin, "Rebound-Dauer", "min"),
                    ganz(FuseIntKey.EvidenceReboundOverrideMaxMin, "Rebound-Sonderrecht", "min"),
                    schalter(FuseBooleanKey.TbrEndZeroWhenReasonGone, "Null sofort beenden"),
                    schalter(FuseBooleanKey.ZeroLatchEnabled, "Zero-Latch"),
                    ganz(FuseIntKey.ZeroLatchCalmExitMin, "Latch-Ruhe", "Zyk"),
                    // Die Einheit heisst ehrlich "Zyk" - gezaehlt werden Zyklen.
                    ganz(FuseIntKey.ZeroLatchReasonGoneExitCycles, "Latch-Grund-weg", "Zyk"),
                    schalter(FuseBooleanKey.PartialRecoveryEnabled, "Teilbasal-Rueckkehr"),
                    zahl(FuseDoubleKey.ZeroLatchCalmDistanceMgdl, "Latch-Abstand", "mg/dl"),
                ),
                // Eigene Gruppe: der Beobachter dosiert nichts - wer die Einstellungen liest, soll auf einen Blick
                // sehen, dass hier nur gemessen wird.
                "Diagnose" to listOf(
                    schalter(FuseBooleanKey.ExpectationLedgerEnabled, "Erwartungs-Beobachter"),
                ),
            ),
        )
    }
}

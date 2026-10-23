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
    FuseBooleanKey.DeferredPrimeEnabled.key,
    FuseDoubleKey.MarkerPrimeDescentHorizonMin.key,
    FuseIntKey.DeferredPrimeEndMin.key,
    FuseBooleanKey.PartialRecoveryEnabled.key,
    FuseBooleanKey.SlowChannelEnabled.key,
    FuseDoubleKey.SlowChannelFactor.key,
    FuseDoubleKey.SlowChannelThresholdMgdl.key,
    FuseIntKey.SlowChannelEntryMin.key,
    FuseBooleanKey.LivenessChannelEnabled.key,
    FuseBooleanKey.SignalRejoinEnabled.key,
    FuseIntKey.LivenessMealPowerMin.key,
    // Die zentralen Profilwerte (CENTRAL-only, echte Defaults).
    FuseDoubleKey.CorrectionExposureLimitU.key,
    FuseDoubleKey.MealExposureLimitU.key,
    FuseDoubleKey.MealDemandRatioCap.key,
    FuseDoubleKey.CorrectionDemandRatioCap.key,
    FuseBooleanKey.ZeroLatchEnabled.key,
    FuseIntKey.ZeroLatchCalmExitMin.key,
    FuseIntKey.ZeroLatchReasonGoneExitCycles.key,
    // 2.0 (Stufe 1, K2): der Serien-Deckel ist entfernt (Variante 2).
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
    // 2.0 (Stufe 1, K3): schnelle Bremsbahn, bedingte Schwanzkante und beide Totbaender sind fest an.
    FuseBooleanKey.OnsetChannelEnabled.key,
    FuseBooleanKey.ExpectationLedgerEnabled.key,
    // Nur Anzeige (Toni 21.10.2026): FUSE-Ebenen im Hauptgraphen.
    FuseBooleanKey.MainGraphLayers.key,
    // Konsolidierung 2.0: die Einstellungen ohne Wirkung sind entfernt (s. FuseRetiredKey - registriert nur fuer die
    // Sicherung). Nicht einstellbar, aber wirksam: MarkerAuthorisesRelease (praediktorfreier Markerpfad); gesichert
    // wird er weiter.
    FuseBooleanKey.TailGuardEnabled.key,
    FuseBooleanKey.TbrEndZeroWhenReasonGone.key,
    // Das Erwartungskonto (Testkandidat E-034) - eigener Abschnitt.
    FuseDoubleKey.ExpectationCurveAt30U.key,
    FuseDoubleKey.ExpectationCurveAt60U.key,
    FuseDoubleKey.ExpectationCurveAt90U.key,
    FuseDoubleKey.ExpectationCurveAt120U.key,
    FuseDoubleKey.ExpectationStabilityBgMgdl.key,
    FuseIntKey.ExpectationStabilityMin.key,
    FuseDoubleKey.ExpectationStabilityFallMgdlPerMin.key,
    FuseDoubleKey.MealRunningMinR.key,
    FuseDoubleKey.MealRisingMinBgMgdl.key,
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
    FuseDoubleKey.EarlySupplyTopUpShare.key,
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
                    zahl(FuseDoubleKey.MealDemandRatioCap, "MEAL Bedarfsanteil", ""),
                ),
                "Erwartungskonto" to listOf(
                    zahl(FuseDoubleKey.ExpectationCurveAt30U, "Kurve nach 30 min", "U"),
                    zahl(FuseDoubleKey.ExpectationCurveAt60U, "Kurve nach 60 min", "U"),
                    zahl(FuseDoubleKey.ExpectationCurveAt90U, "Kurve nach 90 min", "U"),
                    zahl(FuseDoubleKey.ExpectationCurveAt120U, "Kurve nach 120 min", "U"),
                    ganz(FuseIntKey.ExpectationHorizonMin, "Vorausschau", "min"),
                    zahl(FuseDoubleKey.ExpectationStabilityBgMgdl, "Stabilitaet BG", "mg/dl"),
                    ganz(FuseIntKey.ExpectationStabilityMin, "Stabilitaet Dauer", "min"),
                    zahl(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin, "Stabilitaet Fallgrenze", "mg/dl/min"),
                    zahl(FuseDoubleKey.MealRunningMinR, "Mahlzeit laeuft ab r (0 = Rampe unten)", "mg/dl/min"),
                    zahl(FuseDoubleKey.MealRisingMinBgMgdl, "Mahlzeit steigt ab (0 = Stabilitaetsgrenze)", "mg/dl"),
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
                    zahl(FuseDoubleKey.EarlySupplyTopUpShare, "Nachschlag hoechstens (Anteil Budget)", ""),
                    schalter(FuseBooleanKey.EarlySupplyInitialLiftsGuard, "G1 Anfang hebt Guard"),
                    schalter(FuseBooleanKey.EarlySupplyInitialLiftsTail, "G2 Anfang hebt Schwanz"),
                    schalter(FuseBooleanKey.EarlySupplyGuardWithExpectation, "G3 Guard mit Erwartung"),
                    schalter(FuseBooleanKey.EarlySupplyTailWithExpectation, "G4 Schwanz mit Erwartung"),
                ),
                "Erwartungskonto: wirkt mit" to listOf(
                    zahl(FuseDoubleKey.PrimeEnvelopeU, "Freigabe-Huelle", "U"),
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
                    schalter(FuseBooleanKey.SlowChannelEnabled, "Langsamer Korrekturkanal"),
                    zahl(FuseDoubleKey.SlowChannelFactor, "Kanal-Max-Multiplikator", "x Basal"),
                    zahl(FuseDoubleKey.SlowChannelThresholdMgdl, "Kanal-Schwelle", "mg/dl"),
                    ganz(FuseIntKey.SlowChannelEntryMin, "Kanal-Einstieg", "min"),
                ),
                // Eigene Gruppe: der Beobachter dosiert nichts - wer die Einstellungen liest, soll auf einen Blick
                // sehen, dass hier nur gemessen wird.
                "Diagnose" to listOf(
                    schalter(FuseBooleanKey.ExpectationLedgerEnabled, "Erwartungs-Beobachter"),
                    schalter(FuseBooleanKey.MainGraphLayers, "FUSE im Hauptgraphen"),
                ),
            ),
        )
    }
}

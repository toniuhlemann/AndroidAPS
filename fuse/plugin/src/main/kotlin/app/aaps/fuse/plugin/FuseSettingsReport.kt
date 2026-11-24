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
    // Das Tieftor war dosierwirksam, aber in keinem Bildschirm (Inventur der Konsolidierung) - jetzt bei Schutz, Erweitert.
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
    FuseDoubleKey.SlowChannelNightFactor.key,
    FuseIntKey.SlowChannelNightStartMin.key,
    FuseIntKey.SlowChannelNightEndMin.key,
    FuseBooleanKey.LivenessChannelEnabled.key,
    FuseBooleanKey.SignalRejoinEnabled.key,
    FuseIntKey.LivenessMealPowerMin.key,
    // HCL Stufe 2 (Toni 25.10.2026): Bolusrechner-Mahlzeiten - bei Mahlzeiten, Besondere Situationen.
    FuseBooleanKey.HclMealsEnabled.key,
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
    FuseDoubleKey.ZeroLatchMaxBgMgdl.key,
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
    FuseDoubleKey.LivenessBgMinMarkerOnlyMgdl.key,
    FuseIntKey.LivenessReArmMin.key,
    FuseDoubleKey.TailFloorMgdl.key,
    FuseDoubleKey.TailRecoveryU.key,
    FuseDoubleKey.NightDeadbandMgdl.key,
    FuseDoubleKey.CorrectionDeadbandMgdl.key,
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
    FuseDoubleKey.EarlySupplySnackScale.key,
    FuseDoubleKey.EarlySupplyScaleMorning.key,
    FuseDoubleKey.EarlySupplyScaleMidday.key,
    FuseDoubleKey.EarlySupplyScaleEvening.key,
    FuseIntKey.EarlySupplyMiddayStartMin.key,
    FuseIntKey.EarlySupplyEveningStartMin.key,
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
                "Mahlzeiten: Menge" to listOf(
                    schalter(FuseBooleanKey.EarlySupplyEnabled, "Mahlzeit-Versorgung"),
                    zahl(FuseDoubleKey.ExpectationCurveAt30U, "Kurve nach 30 min", "U"),
                    zahl(FuseDoubleKey.ExpectationCurveAt60U, "Kurve nach 60 min", "U"),
                    zahl(FuseDoubleKey.ExpectationCurveAt90U, "Kurve nach 90 min", "U"),
                    zahl(FuseDoubleKey.ExpectationCurveAt120U, "Kurve nach 120 min", "U"),
                    zahl(FuseDoubleKey.EarlySupplyScaleStable, "Skalierer allgemein", ""),
                    zahl(FuseDoubleKey.EarlySupplyScaleMorning, "Skalierer Frühstück (0 = allgemeiner)", ""),
                    zahl(FuseDoubleKey.EarlySupplyScaleMidday, "Skalierer Mittag (0 = allgemeiner)", ""),
                    zahl(FuseDoubleKey.EarlySupplyScaleEvening, "Skalierer Abend (0 = allgemeiner)", ""),
                    zeit(FuseIntKey.EarlySupplyMiddayStartMin, "Mittag beginnt"),
                    zeit(FuseIntKey.EarlySupplyEveningStartMin, "Abend beginnt"),
                    zahl(FuseDoubleKey.EarlySupplyBudgetMaxU, "Budget höchstens", "U"),
                ),
                "Mahlzeiten: Ablauf" to listOf(
                    zahl(FuseDoubleKey.EarlySupplyInitialShare, "Anfangsanteil", ""),
                    zahl(FuseDoubleKey.ExpectationStabilityBgMgdl, "Stabilitätsgrenze", "mg/dl"),
                    zahl(FuseDoubleKey.ExpectationStabilityFallMgdlPerMin, "Fallgrenze", "mg/dl/min"),
                    schalter(FuseBooleanKey.EarlySupplyInitialLiftsGuard, "G1 Anfang hebt Guard"),
                    schalter(FuseBooleanKey.EarlySupplyInitialLiftsTail, "G2 Anfang hebt Spätwirkung"),
                    zahl(FuseDoubleKey.EarlySupplyMaxPortionU, "Weitere Portion höchstens", "U"),
                    zahl(FuseDoubleKey.EarlySupplyMaxPerPeriodU, "Je Zeitraum höchstens", "U"),
                    ganz(FuseIntKey.EarlySupplyPeriodMin, "Zeitraum", "min"),
                    zahl(FuseDoubleKey.MealRisingMinBgMgdl, "Mahlzeit steigt ab (0 = Stabilitätsgrenze)", "mg/dl"),
                    schalter(FuseBooleanKey.EarlySupplyGuardWithExpectation, "G3 Guard mit Erwartung"),
                    schalter(FuseBooleanKey.EarlySupplyTailWithExpectation, "G4 Spätwirkung mit Erwartung"),
                    zahl(FuseDoubleKey.EarlySupplyTopUpShare, "Nachschlag höchstens (Anteil Budget)", ""),
                    ganz(FuseIntKey.LivenessMealPowerMin, "Mahlzeitfenster", "min"),
                ),
                "Mahlzeiten: Besondere Situationen" to listOf(
                    zahl(FuseDoubleKey.EarlySupplySnackScale, "Skalierer bei Snack", ""),
                    zahl(FuseDoubleKey.EarlySupplyScaleAfterLow, "Skalierer bei Start im Tief", ""),
                    schalter(FuseBooleanKey.HclMealsEnabled, "Bolusrechner-Mahlzeiten (HCL)"),
                    schalter(FuseBooleanKey.OnsetChannelEnabled, "Frühe Reaktion (Onset)"),
                    zahl(FuseDoubleKey.OnsetEnvelopeU, "Onset höchstens je Episode", "U"),
                ),
                "Mahlzeiten: Erweitert" to listOf(
                    ganz(FuseIntKey.ExpectationHorizonMin, "Vorausschau", "min"),
                    zahl(FuseDoubleKey.ExpectationReleaseThresholdU, "Mahlzeit bleibt aus (Schwelle)", "U"),
                    kurve(FuseStringKey.ExpectationCurve, "Expertenzeile Kurve"),
                    ganz(FuseIntKey.ExpectationStabilityMin, "Stabil für", "min"),
                    zahl(FuseDoubleKey.MealRunningMinR, "Mahlzeit läuft ab r (0 = Rampe unten)", "mg/dl/min"),
                    zahl(FuseDoubleKey.PrimeEnvelopeU, "Marker-Kredit Größe", "U"),
                    ganz(FuseIntKey.AbsorptionCreditWindowMin, "Marker-Kredit Fenster", "min"),
                    ganz(FuseIntKey.MarkerBoostMaxMin, "Sonderrechte", "min"),
                    schalter(FuseBooleanKey.DeferredPrimeEnabled, "Aufschub bei Fallen"),
                    zahl(FuseDoubleKey.MarkerPrimeDescentHorizonMin, "Aufschub Horizont", "min"),
                    ganz(FuseIntKey.DeferredPrimeEndMin, "Aufschub-Frist", "min"),
                    ganz(FuseIntKey.EvidenceReboundOverrideMaxMin, "Rebound-Sonderrecht", "min"),
                ),
                "Korrekturen: Normalweg (SMB)" to listOf(
                    zahl(FuseDoubleKey.SmbRatio, "SMB-Anteil hoch", ""),
                    zahl(FuseDoubleKey.SmbRatioRise, "SMB-Anteil Anstieg", ""),
                    zahl(FuseDoubleKey.MaxSmbU, "max Einzel-SMB", "U"),
                    zahl(FuseDoubleKey.CorrectionDeadbandMgdl, "Korrektur-Totband (0 = aus)", "mg/dl"),
                    zahl(FuseDoubleKey.NightDeadbandMgdl, "Nacht-Totband", "mg/dl"),
                    zeit(FuseIntKey.NightStartMin, "Nachtfenster Beginn"),
                    zeit(FuseIntKey.NightEndMin, "Nachtfenster Ende"),
                ),
                "Korrekturen: Druck-Kanal" to listOf(
                    schalter(FuseBooleanKey.LivenessChannelEnabled, "Druck-Kanal"),
                    zahl(FuseDoubleKey.LivenessBgMinDayMgdl, "Druck-Schwelle Tag", "mg/dl"),
                    zahl(FuseDoubleKey.LivenessBgMinNightMgdl, "Druck-Schwelle Nacht", "mg/dl"),
                    zahl(FuseDoubleKey.LivenessBgMinMarkerOnlyMgdl, "Druck-Schwelle Nur Marker (0 = Tag/Nacht)", "mg/dl"),
                ),
                "Korrekturen: Langsamer Kanal" to listOf(
                    schalter(FuseBooleanKey.SlowChannelEnabled, "Langsamer Kanal"),
                    zahl(FuseDoubleKey.SlowChannelThresholdMgdl, "Kanal Start ab Zucker", "mg/dl"),
                    ganz(FuseIntKey.SlowChannelEntryMin, "Kanal Einstiegszeit", "min"),
                    zahl(FuseDoubleKey.SlowChannelFactor, "Kanal Basalrate max (Faktor)", "x Basal"),
                    zahl(FuseDoubleKey.SlowChannelNightFactor, "Kanal Faktor nachts (0 = wie tags)", "x Basal"),
                    zeit(FuseIntKey.SlowChannelNightStartMin, "Kanal-Nacht Beginn"),
                    zeit(FuseIntKey.SlowChannelNightEndMin, "Kanal-Nacht Ende"),
                ),
                "Korrekturen: Erweitert" to listOf(
                    zahl(FuseDoubleKey.RiseRampLowR, "Rampe unten", "r"),
                    zahl(FuseDoubleKey.RiseRampHighR, "Rampe oben", "r"),
                    zahl(FuseDoubleKey.CorrectionDemandRatioCap, "SMB-Anteil max außerhalb", ""),
                    zahl(FuseDoubleKey.MealDemandRatioCap, "SMB-Anteil max Mahlzeit", ""),
                    ganz(FuseIntKey.LivenessReArmMin, "Druck-Kanal Pause", "min"),
                    schalter(FuseBooleanKey.CorrectionReversalGuardEnabled, "Gegenbewegungs-Schutz"),
                    zahl(FuseDoubleKey.ReversalFallUkf, "Gegenbewegung Fall", "mg/dl/min"),
                    ganz(FuseIntKey.ReversalLookbackMin, "Gegenbewegung Rückblick", "min"),
                    zahl(FuseDoubleKey.ReversalReboundUkf, "Gegenbewegung Erholung", "mg/dl/min"),
                    ganz(FuseIntKey.ReversalConfirmCycles, "Gegenbewegung Bestätigung", "Zyk"),
                    schalter(FuseBooleanKey.PositiveCorrectionRearmEnabled, "Freigabe-Nachlauf"),
                    ganz(FuseIntKey.RearmHoldMin, "Nachlauf-Dauer", "min"),
                    ganz(FuseIntKey.RearmConfirmCycles, "Nachlauf-Zyklen", "Zyk"),
                    zahl(FuseDoubleKey.RearmUpUkf, "Nachlauf-Schwelle", "mg/dl/min"),
                ),
                "Schutz: Insulingrenzen" to listOf(
                    maxIob,
                    ganz(FuseIntKey.IobThPercent, "iobTH", "% maxIOB"),
                    zahl(FuseDoubleKey.MealExposureLimitU, "Exposition Mahlzeit", "U"),
                    zahl(FuseDoubleKey.CorrectionExposureLimitU, "Exposition außerhalb", "U"),
                ),
                "Schutz: Vorhergesagte Tiefs" to listOf(
                    zahl(FuseDoubleKey.GuardFloorMgdl, "Guard-Untergrenze", "mg/dl"),
                    schalter(FuseBooleanKey.TailGuardEnabled, "Spätwirkungs-Prüfung"),
                    zahl(FuseDoubleKey.TailFloorMgdl, "Spätwirkung Untergrenze", "mg/dl"),
                ),
                "Schutz: Basal-Abschaltung und Rückkehr" to listOf(
                    schalter(FuseBooleanKey.TbrEndZeroWhenReasonGone, "Null sofort beenden"),
                    schalter(FuseBooleanKey.PartialRecoveryEnabled, "Stufenweise Basalrückkehr"),
                    schalter(FuseBooleanKey.ZeroLatchEnabled, "Null verriegeln"),
                    zahl(FuseDoubleKey.ZeroLatchMaxBgMgdl, "Verriegelung nur unter BG (0 = immer)", "mg/dl"),
                ),
                "Schutz: Nach einem gemessenen Tief" to listOf(
                    zahl(FuseDoubleKey.ReboundDeadbandMgdl, "Rebound-Totband", "mg/dl"),
                    ganz(FuseIntKey.ReboundWindowMin, "Rebound-Dauer", "min"),
                ),
                "Schutz: Erweitert" to listOf(
                    zahl(FuseDoubleKey.PositiveDescentHorizonMin, "Fall-Sperre Horizont", "min"),
                    ganz(FuseIntKey.LiabilityHorizonMin, "Haftungs-Horizont", "min"),
                    zahl(FuseDoubleKey.TailRecoveryU, "Spätwirkung Erholung", "U"),
                    zahl(FuseDoubleKey.LowGateMinBenefitMgdl, "Tieftor Mindestnutzen", "mg/dl"),
                    zahl(FuseDoubleKey.LowGateHorizonMin, "Tieftor Horizont", "min"),
                    ganz(FuseIntKey.ZeroLatchCalmExitMin, "Verriegelung Ruhe", "Zyk"),
                    ganz(FuseIntKey.ZeroLatchReasonGoneExitCycles, "Verriegelung Grund weg", "Zyk"),
                    zahl(FuseDoubleKey.ZeroLatchCalmDistanceMgdl, "Verriegelung Abstand", "mg/dl"),
                ),
                "Signal und Prognose" to listOf(
                    ganz(FuseIntKey.ReleaseHorizonMin, "Freigabe-Horizont", "min"),
                    ganz(FuseIntKey.DriveTauMin, "Tau", "min"),
                    zahl(FuseDoubleKey.BolusShareLambda, "Bolus-Lambda", ""),
                    ganz(FuseIntKey.DriveLowerQuantilePct, "Guard-Quantil", "%"),
                    ganz(FuseIntKey.TheilSenWindowMin, "Trend-Fenster", "min"),
                    schalter(FuseBooleanKey.SignalRejoinEnabled, "Wiedereinstieg nach Funklücke"),
                ),
                "Anzeige und Diagnose" to listOf(
                    schalter(FuseBooleanKey.MainGraphLayers, "FUSE im Hauptgraphen"),
                    schalter(FuseBooleanKey.ExpectationLedgerEnabled, "Erwartungs-Beobachter"),
                ),
            ),
        )
    }
}

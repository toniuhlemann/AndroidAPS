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
 * Unterbildschirms (10.08.) - deshalb lebt die Menge hier und nirgendwo
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
    FuseIntKey.CalmRecoveryCycles.key,
    FuseIntKey.CalmTreatmentMode.key,
    FuseDoubleKey.CalmRecoveryMinUkf.key,
    FuseDoubleKey.CalmRecoveryGuardDistanceMgdl.key,
    FuseBooleanKey.LivenessChannelEnabled.key,
    FuseBooleanKey.SignalRejoinEnabled.key,
    FuseIntKey.LivenessMealPowerMin.key,
    FuseDoubleKey.LivenessMealRatioCap.key,
    FuseDoubleKey.LivenessMealIobCapPercent.key,
    FuseDoubleKey.LivenessCorrectionRatioCap.key,
    FuseDoubleKey.LivenessCorrectionIobCapPercent.key,
    FuseBooleanKey.ZeroLatchEnabled.key,
    FuseIntKey.ZeroLatchCalmExitMin.key,
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
    FuseIntKey.AbsorptionCreditWindowMin.key,
    FuseIntKey.MarkerBoostMaxMin.key,
    FuseIntKey.EvidenceReboundOverrideMaxMin.key,
    FuseIntKey.LiabilityHorizonMin.key,
    FuseIntKey.NightStartMin.key,
    FuseIntKey.NightEndMin.key,
    FuseBooleanKey.FastRestraintEnabled.key,
    FuseBooleanKey.OnsetChannelEnabled.key,
    FuseBooleanKey.PrimeReleaseEnabled.key,
    FuseBooleanKey.MealFoundationEnabled.key,
    FuseBooleanKey.ExpectationLedgerEnabled.key,
    FuseBooleanKey.ForecastShadowCollectionEnabled.key,
    FuseBooleanKey.MarkerAuthorisesRelease.key,
    FuseBooleanKey.TailGuardEnabled.key,
    FuseBooleanKey.ConditionalTailEnabled.key,
    FuseBooleanKey.NightDeadbandEnabled.key,
    FuseBooleanKey.ReboundDeadbandEnabled.key,
    FuseBooleanKey.TbrEndZeroWhenReasonGone.key,
)

/**
 * Alle Einstellwerte als menschenlesbarer Bericht fuer den FUSE-Reiter.
 *
 * Der Sinn ist nicht Vollstaendigkeit um ihrer selbst willen, sondern der
 * schnelle Blick: WAS WEICHT VOM STANDARD AB. Abweichende Zeilen tragen
 * einen `*` und den Standardwert - ein maxSmb, das nach einem Testlauf auf
 * 0,55 stehen blieb, soll ins Auge springen, nicht in einer Elf-Kategorien-
 * Navigation versteckt sein (die Lehre aus dem Rig-Lauf, der mit 0,55 statt
 * 0,3 weiterlief).
 *
 * Gruppiert wie die fuenf Einstellungs-Einstiegspunkte, damit der Bericht
 * gleichzeitig die Landkarte des Einstellungsbildschirms ist.
 */
object FuseSettingsReport {

    fun build(preferences: Preferences): FuseScreenModel.SettingsReport {
        fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
        fun uhr(v: Int) = String.format(Locale.US, "%02d:%02d", v / 60, v % 60)

        // TOLERANZ statt ==: Double-Preferences kommen ueber eine
        // String-/Float-Konvertierung zurueck, und am Geraet stand "0.15
        // [Standard 0.15]" - dieselbe Zahl, faelschlich als Abweichung
        // markiert (gesehen 15.08. auf raven). Eine Marke, die auf
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

        val maxIob = FuseScreenModel.SettingRow(
            key = DoubleKey.ApsSmbMaxIob.key, label = "max Gesamt-IOB [U]",
            value = f2(preferences.get(DoubleKey.ApsSmbMaxIob)),
            standard = f2(DoubleKey.ApsSmbMaxIob.defaultValue)
                .takeIf { abweicht(preferences.get(DoubleKey.ApsSmbMaxIob), DoubleKey.ApsSmbMaxIob.defaultValue) },
        )

        return FuseScreenModel.SettingsReport(
            gruppen = listOf(
                "Mahlzeit und Marker" to listOf(
                    schalter(FuseBooleanKey.OnsetChannelEnabled, "Onset-Kanal"),
                    zahl(FuseDoubleKey.OnsetEnvelopeU, "Onset-Huelle", "U"),
                    schalter(FuseBooleanKey.PrimeReleaseEnabled, "Sofort-Freigabe"),
                    zahl(FuseDoubleKey.PrimeEnvelopeU, "Freigabe-Huelle", "U"),
                    ganz(FuseIntKey.PrimeWindowMin, "Freigabe-Fenster", "min"),
                    ganz(FuseIntKey.AbsorptionCreditWindowMin, "Absorption", "min"),
                    ganz(FuseIntKey.MarkerBoostMaxMin, "Sonderrechte", "min"),
                    ganz(FuseIntKey.EvidenceReboundOverrideMaxMin, "Sonderrechte", "min"),
                    schalter(FuseBooleanKey.MarkerAuthorisesRelease, "Marker-Autorisierung"),
                    // Das Mahlzeitenfundament - drei Zeilen, weil alle drei die
                    // Dosierung bestimmen: OB verteilt wird, WIE (Anteil) und
                    // BIS WANN. Ein Schalter allein saehe harmlos aus.
                    schalter(FuseBooleanKey.MealFoundationEnabled, "Mahlzeitenfundament"),
                    zahl(FuseDoubleKey.MealFoundationPhaseAShare, "Anteil Phase A", ""),
                    zahl(FuseDoubleKey.MealFoundationPhaseAUpfrontShare, "Phase A Sofortanteil", ""),
                    ganz(FuseIntKey.MealFoundationEndMin, "Fundament-Fenster", "min"),
                    // Der Marker-Prime-Aufschub - aus demselben Grund drei
                    // Zeilen: OB aufgeschoben wird, mit welchem gepinnten
                    // Horizont und bis zu welcher gepinnten Frist.
                    schalter(FuseBooleanKey.DeferredPrimeEnabled, "Marker-Prime-Aufschub"),
                    zahl(FuseDoubleKey.MarkerPrimeDescentHorizonMin, "Marker-Horizont", "min"),
                    ganz(FuseIntKey.DeferredPrimeEndMin, "Aufschub-Frist", "min"),
                    // Der Ruhe-Ausgang. Der Modus steht als Zahl im
                    // Speicher, im Bericht aber ausgeschrieben - sonst
                    // muesste der Leser 0/1/2 nachschlagen, und eine
                    // Umnummerierung fiele niemandem auf.
                    schalter(FuseBooleanKey.CalmRecoveryEnabled, "Ruhe-Ausgang Phase A"),
                    ganz(FuseIntKey.CalmRecoveryCycles, "Ruhezyklen", ""),
                    ganz(FuseIntKey.CalmTreatmentMode, "Ruhe-Behandlung", ""),
                    zahl(FuseDoubleKey.CalmRecoveryMinUkf, "Ruhe: Mindestrate", "mg/dl/min"),
                    zahl(
                        FuseDoubleKey.CalmRecoveryGuardDistanceMgdl,
                        "Ruhe: Bodenabstand", "mg/dl",
                    ),
                ),
                "Dosierung und Grenzen" to listOf(
                    zahl(FuseDoubleKey.SmbRatio, "Anteil Korrektur", ""),
                    zahl(FuseDoubleKey.SmbRatioRise, "Anteil Anstieg", ""),
                    zahl(FuseDoubleKey.MaxSmbU, "max Einzel-SMB", "U"),
                    zahl(FuseDoubleKey.RiseRampLowR, "Rampe unten", "r"),
                    zahl(FuseDoubleKey.RiseRampHighR, "Rampe oben", "r"),
                    maxIob,
                    ganz(FuseIntKey.IobThPercent, "iobTH", "% maxIOB"),
                    // Der Liveness-Kanal gehoert HIERHER, nicht zu Mahlzeit/
                    // Marker (Toni 22.08.): er ist markerunabhaengig und
                    // mengenbasiert - seine Grenzen sind Dosiergrenzen.
                    schalter(FuseBooleanKey.LivenessChannelEnabled, "Liveness-Kanal"),
                    schalter(FuseBooleanKey.SignalRejoinEnabled, "Wiedereinstieg nach Funkluecke"),
                    ganz(FuseIntKey.LivenessMealPowerMin, "M-Frist", "min"),
                    zahl(FuseDoubleKey.LivenessMealRatioCap, "M-Ratio-Deckel", ""),
                    zahl(FuseDoubleKey.LivenessMealIobCapPercent, "M-Kanaldeckel", "%"),
                    zahl(FuseDoubleKey.LivenessCorrectionRatioCap, "K-Ratio-Deckel", ""),
                    zahl(FuseDoubleKey.LivenessCorrectionIobCapPercent, "K-Kanaldeckel", "%"),
                    zahl(FuseDoubleKey.LivenessBgMinDayMgdl, "Druck-Schwelle Tag", "mg/dl"),
                    // Die Nachtschwelle EHRLICH anzeigen: solange sie nie
                    // gesetzt wurde, folgt sie zur Laufzeit der Tagesschwelle
                    // (Lese-Migration v20) - der Bildschirm-Default 160 waere
                    // dann eine Falschaussage.
                    preferences.getIfExists(FuseDoubleKey.LivenessBgMinNightMgdl)
                        ?.takeIf { it.isFinite() && it in FuseDoubleKey.LivenessBgMinNightMgdl.min..FuseDoubleKey.LivenessBgMinNightMgdl.max }
                        .let { nacht ->
                        FuseScreenModel.SettingRow(
                            key = FuseDoubleKey.LivenessBgMinNightMgdl.key,
                            label = "Druck-Schwelle Nacht",
                            value = nacht?.let { "${f2(it)} mg/dl" }
                                ?: "folgt Tag (${f2(preferences.get(FuseDoubleKey.LivenessBgMinDayMgdl))} mg/dl)",
                            standard = nacht?.takeIf { abweicht(it, FuseDoubleKey.LivenessBgMinNightMgdl.defaultValue) }
                                ?.let { "${f2(FuseDoubleKey.LivenessBgMinNightMgdl.defaultValue)} mg/dl" },
                        )
                    },
                    ganz(FuseIntKey.LivenessReArmMin, "Re-Arm-Sperre", "min"),
                ),
                "Schutz und Prognose" to listOf(
                    zahl(FuseDoubleKey.GuardFloorMgdl, "Guard-Boden", "mg/dl"),
                    zahl(FuseDoubleKey.PositiveDescentHorizonMin, "SMB-Abwaerts-Horizont", "min"),
                    schalter(FuseBooleanKey.FastRestraintEnabled, "Schnelle Bremsbahn"),
                    zahl(FuseDoubleKey.BolusShareLambda, "Bolus-Lambda", ""),
                    ganz(FuseIntKey.ReleaseHorizonMin, "Horizont", "min"),
                    ganz(FuseIntKey.DriveTauMin, "Tau", "min"),
                    ganz(FuseIntKey.DriveLowerQuantilePct, "Guard-Quantil", "%"),
                    ganz(FuseIntKey.TheilSenWindowMin, "TS-Fenster", "min"),
                    schalter(FuseBooleanKey.TbrEndZeroWhenReasonGone, "Null sofort beenden"),
                    schalter(FuseBooleanKey.ZeroLatchEnabled, "Zero-Latch"),
                    ganz(FuseIntKey.ZeroLatchCalmExitMin, "Latch-Ruhe", "Zyk"),
                    zahl(FuseDoubleKey.ZeroLatchCalmDistanceMgdl, "Latch-Abstand", "mg/dl"),
                    schalter(FuseBooleanKey.CorrectionReversalGuardEnabled, "V-Reversal-Schutz"),
                    zahl(FuseDoubleKey.ReversalFallUkf, "Reversal-Fall", "mg/dl/min"),
                    ganz(FuseIntKey.ReversalLookbackMin, "Reversal-Rueckblick", "min"),
                    zahl(FuseDoubleKey.ReversalReboundUkf, "Reversal-Gegenzug", "mg/dl/min"),
                    ganz(FuseIntKey.ReversalConfirmCycles, "Reversal-Bestaetigung", "Zyk"),
                    schalter(FuseBooleanKey.PositiveCorrectionRearmEnabled, "Freigabe-Nachlauf"),
                    ganz(FuseIntKey.RearmHoldMin, "Nachlauf-Dauer", "min"),
                    ganz(FuseIntKey.RearmConfirmCycles, "Nachlauf-Zyklen", "Zyk"),
                    zahl(FuseDoubleKey.RearmUpUkf, "Nachlauf-Schwelle", "mg/dl/min"),
                    schalter(FuseBooleanKey.TailGuardEnabled, "Schwanz-Guard"),
                    schalter(FuseBooleanKey.ConditionalTailEnabled, "Mahlzeit im Schwanz"),
                    ganz(FuseIntKey.LiabilityHorizonMin, "Haftung", "min"),
                    zahl(FuseDoubleKey.TailFloorMgdl, "Schwanz-Boden", "mg/dl"),
                    zahl(FuseDoubleKey.TailRecoveryU, "Schwanz-Erholung", "U"),
                ),
                "Nacht und Rebound" to listOf(
                    schalter(FuseBooleanKey.NightDeadbandEnabled, "Nacht-Totband"),
                    zahl(FuseDoubleKey.NightDeadbandMgdl, "Nacht-Band", "mg/dl"),
                    zeit(FuseIntKey.NightStartMin, "Nacht Beginn"),
                    zeit(FuseIntKey.NightEndMin, "Nacht Ende"),
                    schalter(FuseBooleanKey.ReboundDeadbandEnabled, "Rebound-Totband"),
                    zahl(FuseDoubleKey.ReboundDeadbandMgdl, "Rebound-Band", "mg/dl"),
                ),
                // Eigene Gruppe: er dosiert nichts und gehoert deshalb in
                // keine der Regelgruppen - wer die Einstellungen liest, soll
                // auf einen Blick sehen, dass hier nur gemessen wird.
                "Messen und Beobachten" to listOf(
                    schalter(FuseBooleanKey.ExpectationLedgerEnabled, "Erwartungs-Beobachter"),
                    schalter(FuseBooleanKey.ForecastShadowCollectionEnabled, "Prognose-Shadow"),
                ),
            ),
        )
    }
}

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
    FuseDoubleKey.BolusShareLambda.key,
    FuseDoubleKey.OnsetEnvelopeU.key,
    FuseDoubleKey.PrimeEnvelopeU.key,
    FuseDoubleKey.TailFloorMgdl.key,
    FuseDoubleKey.TailRecoveryU.key,
    FuseDoubleKey.NightDeadbandMgdl.key,
    FuseDoubleKey.ReboundDeadbandMgdl.key,
    FuseIntKey.IobThPercent.key,
    FuseIntKey.ReleaseHorizonMin.key,
    FuseIntKey.DriveTauMin.key,
    FuseIntKey.DriveLowerQuantilePct.key,
    FuseIntKey.AbsorptionCreditWindowMin.key,
    FuseIntKey.MarkerBoostMaxMin.key,
    FuseIntKey.LiabilityHorizonMin.key,
    FuseIntKey.NightStartMin.key,
    FuseIntKey.NightEndMin.key,
    FuseBooleanKey.FastRestraintEnabled.key,
    FuseBooleanKey.OnsetChannelEnabled.key,
    FuseBooleanKey.PrimeReleaseEnabled.key,
    FuseBooleanKey.MarkerAuthorisesRelease.key,
    FuseBooleanKey.TailGuardEnabled.key,
    FuseBooleanKey.ConditionalTailEnabled.key,
    FuseBooleanKey.NightDeadbandEnabled.key,
    FuseBooleanKey.ReboundDeadbandEnabled.key,
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

        fun zahl(k: FuseDoubleKey, label: String, einheit: String) = FuseScreenModel.SettingRow(
            key = k.key, label = label, value = "${f2(preferences.get(k))} $einheit".trim(),
            standard = f2(k.defaultValue).takeIf { preferences.get(k) != k.defaultValue }?.let { "$it $einheit".trim() },
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
                .takeIf { preferences.get(DoubleKey.ApsSmbMaxIob) != DoubleKey.ApsSmbMaxIob.defaultValue },
        )

        return FuseScreenModel.SettingsReport(
            gruppen = listOf(
                "Mahlzeit und Marker" to listOf(
                    schalter(FuseBooleanKey.OnsetChannelEnabled, "Onset-Kanal"),
                    zahl(FuseDoubleKey.OnsetEnvelopeU, "Onset-Huelle", "U"),
                    schalter(FuseBooleanKey.PrimeReleaseEnabled, "Sofort-Freigabe"),
                    zahl(FuseDoubleKey.PrimeEnvelopeU, "Freigabe-Huelle", "U"),
                    ganz(FuseIntKey.AbsorptionCreditWindowMin, "Absorption", "min"),
                    ganz(FuseIntKey.MarkerBoostMaxMin, "Sonderrechte", "min"),
                    schalter(FuseBooleanKey.MarkerAuthorisesRelease, "Marker-Autorisierung"),
                ),
                "Dosierung und Grenzen" to listOf(
                    zahl(FuseDoubleKey.SmbRatio, "Anteil Korrektur", ""),
                    zahl(FuseDoubleKey.SmbRatioRise, "Anteil Anstieg", ""),
                    zahl(FuseDoubleKey.MaxSmbU, "max Einzel-SMB", "U"),
                    zahl(FuseDoubleKey.RiseRampLowR, "Rampe unten", "r"),
                    zahl(FuseDoubleKey.RiseRampHighR, "Rampe oben", "r"),
                    maxIob,
                    ganz(FuseIntKey.IobThPercent, "iobTH", "% maxIOB"),
                ),
                "Schutz und Prognose" to listOf(
                    zahl(FuseDoubleKey.GuardFloorMgdl, "Guard-Boden", "mg/dl"),
                    schalter(FuseBooleanKey.FastRestraintEnabled, "Schnelle Bremsbahn"),
                    zahl(FuseDoubleKey.BolusShareLambda, "Bolus-Lambda", ""),
                    ganz(FuseIntKey.ReleaseHorizonMin, "Horizont", "min"),
                    ganz(FuseIntKey.DriveTauMin, "Tau", "min"),
                    ganz(FuseIntKey.DriveLowerQuantilePct, "Guard-Quantil", "%"),
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
            ),
        )
    }
}

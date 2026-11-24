package app.aaps.fuse.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceInfoDef
import app.aaps.core.ui.compose.preference.PreferenceRowSummaries
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.preference.rememberPreferenceBooleanState
import app.aaps.core.ui.compose.preference.rememberPreferenceDoubleState
import app.aaps.core.ui.compose.preference.rememberPreferenceIntState
import app.aaps.core.ui.compose.preference.rememberPreferenceStringState
import app.aaps.core.ui.compose.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Die eigenen Zeilen der FUSE-Einstellungen, die kein Wert sind: die Ledger-Handlungen.
 * Aktionen, also nichts zu exportieren (s. [IntentPreferenceKey.exportable]).
 */
enum class FuseIntentKey(
    override val key: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.ACTIVITY,
) : IntentPreferenceKey {

    LedgerSperre("fuse_ledger_sperre", TextRef.AndroidRes(R.string.fuse_ledger_sperre_title), TextRef.AndroidRes(R.string.fuse_ledger_sperre_summary)),
    LedgerWiederherstellung("fuse_ledger_wiederherstellung", TextRef.AndroidRes(R.string.fuse_ledger_wiederherstellung_title), TextRef.AndroidRes(R.string.fuse_ledger_wiederherstellung_summary)),
    LedgerReparatur("fuse_ledger_reparatur", TextRef.AndroidRes(R.string.fuse_ledger_reparatur_title), TextRef.AndroidRes(R.string.fuse_ledger_reparatur_summary)),
}

/**
 * Die Schluessel, deren kurze Zeile am Feld laufend gerechnet wird ([FuseLiveZeilen]): Kurve, Stabilitaet und die
 * Versorgung der Mahlzeit. Die Zeilen haengen an diesen Schluesseln, nicht an einem Bereich - so behalten sie ihre
 * Zeile, gleich in welchem Bereich sie stehen.
 */
internal val fuseLiveZeilenSchluessel: List<String> = listOf(
    FuseDoubleKey.ExpectationCurveAt30U.key, FuseDoubleKey.ExpectationCurveAt60U.key,
    FuseDoubleKey.ExpectationCurveAt90U.key, FuseDoubleKey.ExpectationCurveAt120U.key,
    FuseDoubleKey.ExpectationStabilityBgMgdl.key, FuseIntKey.ExpectationStabilityMin.key,
    FuseDoubleKey.ExpectationStabilityFallMgdlPerMin.key, FuseDoubleKey.ExpectationReleaseThresholdU.key,
    FuseDoubleKey.MealRunningMinR.key, FuseDoubleKey.MealRisingMinBgMgdl.key,
    FuseIntKey.ExpectationHorizonMin.key, FuseStringKey.ExpectationCurve.key,
    FuseBooleanKey.EarlySupplyEnabled.key, FuseDoubleKey.EarlySupplyBudgetMaxU.key,
    FuseDoubleKey.EarlySupplyScaleStable.key, FuseDoubleKey.EarlySupplyScaleAfterLow.key,
    FuseDoubleKey.EarlySupplySnackScale.key,
    FuseDoubleKey.EarlySupplyScaleMorning.key, FuseDoubleKey.EarlySupplyScaleMidday.key,
    FuseDoubleKey.EarlySupplyScaleEvening.key,
    FuseIntKey.EarlySupplyMiddayStartMin.key, FuseIntKey.EarlySupplyEveningStartMin.key,
    FuseDoubleKey.EarlySupplyInitialShare.key, FuseDoubleKey.EarlySupplyMaxPortionU.key,
    FuseDoubleKey.EarlySupplyMaxPerPeriodU.key, FuseIntKey.EarlySupplyPeriodMin.key,
    FuseDoubleKey.EarlySupplyTopUpShare.key,
    FuseBooleanKey.EarlySupplyInitialLiftsGuard.key, FuseBooleanKey.EarlySupplyInitialLiftsTail.key,
    FuseBooleanKey.EarlySupplyGuardWithExpectation.key, FuseBooleanKey.EarlySupplyTailWithExpectation.key,
)

/**
 * Die FUSE-Einstellungen fuer v4.
 *
 * GLIEDERUNG (Toni 24.11.2026): fuenf Hauptbereiche nach der Frage des Nutzers, nicht nach den Bauteilen des Reglers -
 * Mahlzeiten, Korrekturen, Schutz und Insulingrenzen, Signal und Prognose, Anzeige und Diagnose. Vier Regeln:
 *  - Jeder Wert hat genau einen Ort. Wo eine zweite Stelle naheliegt, steht dort eine Info-Zeile als Verweis.
 *  - "Erweitert" gehoert zum Thema: jeder der drei grossen Hauptbereiche hat am Ende einen eigenen Bereich dafuer.
 *    Zugeklappt heisst nur nicht angezeigt - die Werte wirken weiter.
 *  - Ein Wort je Sache (Druck-Kanal, Langsamer Kanal, Mahlzeitfenster, Spaetwirkungs-Pruefung); der alte Titel steht
 *    am Ende der Erklaerung ("Bisher: ..."), damit die Suche ihn weiter findet.
 *  - Die erste Zeile jeder Erklaerung sagt, wofuer der Wert gilt.
 * Schluessel, Werte, Vorgaben und Dosierverhalten haengen nicht an dieser Gliederung.
 *
 * v4 zeichnet in einer Karte genau eine Ebene von Bereichen; ein Bereich im Bereich wird nicht gezeichnet. Die
 * Hauptbereiche stehen deshalb als Ueberschrift-Zeilen in der Karte ([hauptbereiche]), darunter ihre Bereiche zum
 * Aufklappen. Unter dem Titel eines Bereichs steht ein Satz, der sagt, was darin ist.
 *
 * Die Info-Zeilen gliedern einen Bereich als Zwischenueberschriften. Die Felder von Kurve, Stabilitaet und
 * Mahlzeit-Versorgung zeigen die laufend berechnete kurze Zeile ([FuseLiveZeilen]). In ALLEN Bereichen (Toni, wie
 * autoISF) steht am Feld nur die Live-Zeile; die Erklaerung kommt beim Hineingehen oder Antippen (liveLinesOnly).
 *
 * Die Vollstaendigkeitswache des Forks gilt weiter: [einstellbareKeys] muss genau [fuseEinstellbareKeys]
 * sein, jeder Wert genau einmal (Test).
 */
object FuseSettings {

    /** Ein Hauptbereich: Titel, ein Satz dazu und die Schluessel seiner Bereiche in ihrer Reihenfolge. */
    class Hauptbereich(val titel: String, val text: String, val bereiche: List<String>)

    /**
     * Ein Bereich mit seinem Satz unter dem Titel. Toni: in ALLEN FUSE-Bereichen am Feld nur die Live-Zeile, die
     * Erklaerung erst im Eingabedialog, bei Schaltern und Absprung-Zeilen hinter dem (i) am Titel. Die Info-Zeilen
     * bleiben kurz mit (i).
     */
    private fun section(key: String, title: Int, summary: Int, vararg items: PreferenceItem) =
        PreferenceSubScreenDef(
            key = key, title = TextRef.AndroidRes(title), items = items.toList(), summary = TextRef.AndroidRes(summary),
            liveLinesOnly = true, summaryInHeader = true,
        )

    /** Eine Zwischenueberschrift in einem Bereich mit ihrem Satz; auch ein Verweis auf den Ort eines Werts. */
    private fun info(titel: String, text: String) = PreferenceInfoDef(text = TextRef.Literal(text), title = TextRef.Literal(titel))

    /** Die Beschreibung eines Bereichs ohne eigene Ueberschrift, ueber seinen Feldern (v3: blockBeschreibung). */
    private fun absatz(text: String) = PreferenceInfoDef(text = TextRef.Literal(text))

    /**
     * Der Einstellungsbaum - die Bereiche mit ihren Werten, Zwischenueberschriften und Verweisen, ohne die eigenen
     * Seiten. Fuer Plugin UND Wache. Die Reihenfolge folgt [hauptbereiche].
     */
    internal fun abschnitte(): List<PreferenceSubScreenDef> = listOf(
        section(
            "fuse_meal_amount", R.string.fuse_section_meal_amount, R.string.fuse_section_meal_amount_summary,
            absatz(FruehVersorgungText.BLOCK_FRUEH),
            FuseBooleanKey.EarlySupplyEnabled,
            info("Erwartete Mahlzeit (Kurve)", "Gilt, sobald vier steigende Werte eingetragen sind – ohne eigenen Schalter."),
            absatz(FruehVersorgungText.BLOCK_KONTO),
            FuseDoubleKey.ExpectationCurveAt30U, FuseDoubleKey.ExpectationCurveAt60U, FuseDoubleKey.ExpectationCurveAt90U,
            FuseDoubleKey.ExpectationCurveAt120U,
            info("Skalierer", "Vervielfachen die erwartete Mahlzeit: Budget, Anfangsportion und weitere Portionen."),
            FuseDoubleKey.EarlySupplyScaleStable, FuseDoubleKey.EarlySupplyScaleMorning,
            FuseDoubleKey.EarlySupplyScaleMidday, FuseDoubleKey.EarlySupplyScaleEvening,
            FuseIntKey.EarlySupplyMiddayStartMin, FuseIntKey.EarlySupplyEveningStartMin,
            info("Budget", "Obergrenze, gleich welcher Skalierer gilt. maxIOB gilt immer; es steht bei Schutz und Insulingrenzen."),
            FuseDoubleKey.EarlySupplyBudgetMaxU,
        ),
        section(
            "fuse_meal_flow", R.string.fuse_section_meal_flow, R.string.fuse_section_meal_flow_summary,
            info(
                "Anfangsportion",
                "Fließt sofort am Knopf. Sie wartet nur bei einem Start im Tief, bei Fallen über die Fallgrenze und aus " +
                    "technischen Gründen.",
            ),
            FuseDoubleKey.EarlySupplyInitialShare, FuseDoubleKey.ExpectationStabilityBgMgdl,
            FuseDoubleKey.ExpectationStabilityFallMgdlPerMin, FuseBooleanKey.EarlySupplyInitialLiftsGuard,
            FuseBooleanKey.EarlySupplyInitialLiftsTail,
            info("Weitere Portionen", "Jede wird neu geprüft: Anforderung, vorhandenes Insulin, Budgetrest und alle Schutzregeln."),
            FuseDoubleKey.EarlySupplyMaxPortionU, FuseDoubleKey.EarlySupplyMaxPerPeriodU, FuseIntKey.EarlySupplyPeriodMin,
            FuseDoubleKey.MealRisingMinBgMgdl, FuseBooleanKey.EarlySupplyGuardWithExpectation,
            FuseBooleanKey.EarlySupplyTailWithExpectation, FuseDoubleKey.EarlySupplyTopUpShare,
            info(
                "Guard und Spätwirkungs-Prüfung",
                "Die Prüfungen selbst, auf die sich G1 bis G4 beziehen, stehen bei Schutz und Insulingrenzen – Vorhergesagte " +
                    "Tiefs.",
            ),
            info("Mahlzeitfenster", "So lange arbeitet die Mahlzeit-Versorgung; danach gilt der Korrekturweg."),
            FuseIntKey.LivenessMealPowerMin,
        ),
        section(
            "fuse_meal_special", R.string.fuse_section_meal_special, R.string.fuse_section_meal_special_summary,
            info("Snack", "Für kleinere Mahlzeiten: kleineres Budget und kleinere Anfangsportion, sonst wie „Mahlzeit“."),
            FuseDoubleKey.EarlySupplySnackScale,
            info("Start im Tief", "Wenn der Zucker am Knopf unter der Stabilitätsgrenze liegt oder dort ein Tief gemessen ist."),
            FuseDoubleKey.EarlySupplyScaleAfterLow,
            info(
                "Bolusrechner (HCL)",
                "Ein Bolus aus dem AAPS-Bolusrechner mit KH: FUSE kennt dann die Mahlzeit, gibt aber nichts dazu, solange der " +
                    "Zucker es nicht zeigt.",
            ),
            FuseBooleanKey.HclMealsEnabled,
            info(
                "Früher Anstieg ohne laufende Mahlzeit-Versorgung",
                "Frühe Reaktion auf einen gemessenen Anstieg, bevor er bestätigt ist. Ruht, solange die Mahlzeit-Versorgung " +
                    "gilt.",
            ),
            FuseBooleanKey.OnsetChannelEnabled, FuseDoubleKey.OnsetEnvelopeU,
            info(
                "Nur Marker",
                "Am Knopf fließt kein Insulin; der Druck-Kanal bleibt im Fenster an. Seine Schwelle steht bei Korrekturen – " +
                    "Druck-Kanal.",
            ),
        ),
        section(
            "fuse_meal_advanced", R.string.fuse_section_meal_advanced, R.string.fuse_section_meal_advanced_summary,
            info("Kurve und Vorgriff", "Wie weit vorausgeschaut wird und was gilt, wenn die Mahlzeit ausbleibt."),
            FuseIntKey.ExpectationHorizonMin, FuseDoubleKey.ExpectationReleaseThresholdU, FuseStringKey.ExpectationCurve,
            info("Freigabe weiterer Portionen", "Wann sich die Mahlzeit gezeigt hat und wie lange der Zucker stabil sein muss."),
            FuseIntKey.ExpectationStabilityMin, FuseDoubleKey.MealRunningMinR,
            info(
                "Marker-Kredit",
                "Aus dem alten Mahlzeitenweg, wirkt auch bei „Mahlzeit“ und „Snack“: Kredit und Fenster bilden die Kante, bis " +
                    "zu der die Mahlzeit die Spätwirkungs-Prüfung heben darf. Die Sonderrechte halten Nacht-Totband und " +
                    "Rebound-Bremsen offen.",
            ),
            FuseDoubleKey.PrimeEnvelopeU, FuseIntKey.AbsorptionCreditWindowMin, FuseIntKey.MarkerBoostMaxMin,
            info("Aufschub bei gemessenem Fallen", "Pausiert weitere Portionen bei gemessenem Abwärtsrisiko."),
            FuseBooleanKey.DeferredPrimeEnabled, FuseDoubleKey.MarkerPrimeDescentHorizonMin,
            FuseIntKey.DeferredPrimeEndMin,
            info(
                "Mahlzeit nach einem Tief",
                "Das Rebound-Fenster selbst steht bei Schutz und Insulingrenzen – Nach einem gemessenen Tief.",
            ),
            FuseIntKey.EvidenceReboundOverrideMaxMin,
        ),
        section(
            "fuse_corr_smb", R.string.fuse_section_corr_smb, R.string.fuse_section_corr_smb_summary,
            info(
                "SMB-Anteile",
                "Wie viel vom fehlenden Insulin jede Minute fließt. Im Mahlzeitfenster versorgt die Mahlzeit; dessen Dauer " +
                    "steht bei Mahlzeiten – Ablauf.",
            ),
            FuseDoubleKey.SmbRatio, FuseDoubleKey.SmbRatioRise, FuseDoubleKey.MaxSmbU,
            info(
                "Totbänder",
                "Schwellen über dem Ziel für Korrektur-SMB; wofür jedes Totband gilt, steht an seinem Wert. Eine laufende " +
                    "Basalabsenkung bleibt als Schutz bestehen. Ein Mahlzeit-Marker hebt das Nacht-Totband auf.",
            ),
            FuseDoubleKey.CorrectionDeadbandMgdl, FuseDoubleKey.NightDeadbandMgdl,
            info(
                "Nachtfenster",
                "Beginn und Ende als Uhrzeit. Das Fenster darf Mitternacht überschreiten; Beginn gleich Ende schaltet es aus.",
            ),
            FuseIntKey.NightStartMin, FuseIntKey.NightEndMin,
        ),
        section(
            "fuse_corr_pressure", R.string.fuse_section_corr_pressure, R.string.fuse_section_corr_pressure_summary,
            absatz(
                "Mengenbegrenzter Zusatzweg bei anhaltend steigendem Zucker über der Druck-Schwelle, wenn Guard oder " +
                    "Spätwirkungs-Prüfung den Normalweg deckeln. Im Fenster von „Mahlzeit“, „Snack“ und Bolusrechner aus (dort " +
                    "versorgt die Mahlzeit); bei „Nur Marker“ an, mit den Grenzen des Mahlzeitfensters.",
            ),
            FuseBooleanKey.LivenessChannelEnabled, FuseDoubleKey.LivenessBgMinDayMgdl,
            FuseDoubleKey.LivenessBgMinNightMgdl, FuseDoubleKey.LivenessBgMinMarkerOnlyMgdl,
            info("Nachtfenster", "Die Zeiten der Nacht stehen bei Korrekturen – Normalweg (SMB)."),
        ),
        section(
            "fuse_corr_slow", R.string.fuse_section_corr_slow, R.string.fuse_section_corr_slow_summary,
            absatz(
                "Positive TBR für Tage mit erhöhtem Bedarf (Infekt, Resistenz) – ein Schalter, den du an solchen Tagen " +
                    "einschaltest. Die Rate folgt dem Bedarf, der Faktor ist nur die Obergrenze. Einstieg und Stopp nur über den " +
                    "gemessenen Verlauf; darf an Guard, Spätwirkungs-Prüfung und Expositionsgrenze vorbei, nie über maxIOB. Läuft " +
                    "wie das Profilbasal nebenher – SMB bleiben unberührt.",
            ),
            FuseBooleanKey.SlowChannelEnabled,
            info(
                "Wann und wie stark",
                "Schwelle, Einstiegszeit und Faktor bestimmen zusammen, wann und wie stark der Kanal eingreift.",
            ),
            FuseDoubleKey.SlowChannelThresholdMgdl, FuseIntKey.SlowChannelEntryMin, FuseDoubleKey.SlowChannelFactor,
            info("Nachts", "Eigener Faktor mit eigenen Zeiten – unabhängig vom Nachtfenster des Normalwegs."),
            FuseDoubleKey.SlowChannelNightFactor, FuseIntKey.SlowChannelNightStartMin, FuseIntKey.SlowChannelNightEndMin,
        ),
        section(
            "fuse_corr_advanced", R.string.fuse_section_corr_advanced, R.string.fuse_section_corr_advanced_summary,
            info("Normalweg", "Der Übergang zwischen den beiden SMB-Anteilen und ihre Deckel."),
            FuseDoubleKey.RiseRampLowR, FuseDoubleKey.RiseRampHighR, FuseDoubleKey.CorrectionDemandRatioCap,
            FuseDoubleKey.MealDemandRatioCap,
            info("Druck-Kanal", "Gegen schnelles Aus und Wieder-Ein."),
            FuseIntKey.LivenessReArmMin,
            info(
                "Schutz des Korrekturwegs",
                "Zwei Riegel für unbelegte Korrekturlagen. Sie greifen im Korrekturkontext und bei nur vermuteter Mahlzeit, nie " +
                    "bei bestätigter Mahlzeit. Sie sperren positive Korrektur-SMB einschließlich Druck-Kanal, verändern aber weder " +
                    "TBR noch Basal.",
            ),
            FuseBooleanKey.CorrectionReversalGuardEnabled, FuseDoubleKey.ReversalFallUkf, FuseIntKey.ReversalLookbackMin,
            FuseDoubleKey.ReversalReboundUkf, FuseIntKey.ReversalConfirmCycles,
            FuseBooleanKey.PositiveCorrectionRearmEnabled, FuseIntKey.RearmHoldMin, FuseIntKey.RearmConfirmCycles,
            FuseDoubleKey.RearmUpUkf,
        ),
        section(
            "fuse_protect_limits", R.string.fuse_section_protect_limits, R.string.fuse_section_protect_limits_summary,
            info(
                "Gemeinsame Grenzen",
                "maxIOB gilt gemeinsam für alle Wege: SMB, Mahlzeit-Portionen und Zusatzbasal. Der FUSE-Reiter zeigt den " +
                    "aktuell verbleibenden Spielraum. iobTH ist der schnelle Reserve-Anker.",
            ),
            DoubleKey.ApsSmbMaxIob, FuseIntKey.IobThPercent,
            info(
                "Expositionsgrenzen",
                "Insulin an Bord höchstens – im Mahlzeitfenster und außerhalb. Der Wert im Mahlzeitfenster darf nie kleiner " +
                    "sein.",
            ),
            FuseDoubleKey.MealExposureLimitU, FuseDoubleKey.CorrectionExposureLimitU,
        ),
        section(
            "fuse_protect_predicted", R.string.fuse_section_protect_predicted, R.string.fuse_section_protect_predicted_summary,
            info("Guard – die Nahzone", "Sperrt Dosen, deren Bahn in den nächsten Minuten unter den Boden liefe."),
            FuseDoubleKey.GuardFloorMgdl,
            info("Spätwirkungs-Prüfung – die späte Wirkung", "Haftung für Insulin, das erst hinter dem Haftungs-Horizont wirkt."),
            FuseBooleanKey.TailGuardEnabled, FuseDoubleKey.TailFloorMgdl,
        ),
        section(
            "fuse_protect_basal", R.string.fuse_section_protect_basal, R.string.fuse_section_protect_basal_summary,
            info("Null-Basal", "Null-Basal bei Abwärtslage: wann sie endet, ob sie verriegelt und wie sie zurückkehrt."),
            FuseBooleanKey.TbrEndZeroWhenReasonGone, FuseBooleanKey.PartialRecoveryEnabled,
            FuseBooleanKey.ZeroLatchEnabled, FuseDoubleKey.ZeroLatchMaxBgMgdl,
        ),
        section(
            "fuse_protect_after_low", R.string.fuse_section_protect_after_low, R.string.fuse_section_protect_after_low_summary,
            info(
                "Rebound-Fenster",
                "Nach einem Tief dämpft FUSE die Reaktion auf den Wiederanstieg. Im Mahlzeitfenster ruht die Dämpfung. Für " +
                    "Mahlzeiten nach einem Tief: Mahlzeiten – Besondere Situationen und Erweitert.",
            ),
            FuseDoubleKey.ReboundDeadbandMgdl, FuseIntKey.ReboundWindowMin,
        ),
        section(
            "fuse_protect_advanced", R.string.fuse_section_protect_advanced, R.string.fuse_section_protect_advanced_summary,
            info("Vorhersage", "Wie weit Guard und Spätwirkungs-Prüfung schauen."),
            FuseDoubleKey.PositiveDescentHorizonMin, FuseIntKey.LiabilityHorizonMin, FuseDoubleKey.TailRecoveryU,
            info("Tieftor", "Wann eine gemessene Abwärtslage mit Bolus-Überdeckung die Basal-Null auslöst."),
            FuseDoubleKey.LowGateMinBenefitMgdl, FuseDoubleKey.LowGateHorizonMin,
            info("Verriegelung der Null lösen", "Zwei Wege aus der verriegelten Null."),
            FuseIntKey.ZeroLatchCalmExitMin, FuseIntKey.ZeroLatchReasonGoneExitCycles,
            FuseDoubleKey.ZeroLatchCalmDistanceMgdl,
        ),
        section(
            "fuse_signal", R.string.fuse_section_model, R.string.fuse_section_model_summary,
            FuseIntKey.ReleaseHorizonMin, FuseIntKey.DriveTauMin, FuseDoubleKey.BolusShareLambda,
            FuseIntKey.DriveLowerQuantilePct, FuseIntKey.TheilSenWindowMin, FuseBooleanKey.SignalRejoinEnabled,
        ),
        section(
            "fuse_display", R.string.fuse_section_display, R.string.fuse_section_display_summary,
            FuseBooleanKey.MainGraphLayers, FuseBooleanKey.ExpectationLedgerEnabled,
            info(
                "Regler-Takt: 1 Minute",
                "Jeder neue 1-min-CGM-Wert ist ein Zyklus. Fest - kein Legacy-SMB-Intervall. Geregelt wird mit der insulinbereinigten Stoerungsrate r; Q1 und Rohwert stehen im Reiter nebeneinander.",
            ),
            info(
                "Positive TBR: nur langsamer Kanal",
                "Der schnelle Kanal ist der 1-min-SMB. Ohne den Schalter \"Langsamer Kanal\" setzt FUSE nur " +
                    "Null-Temps oder bricht ab. Mit ihm gelten fuer die positive TBR die AAPS-Grenzen der Basalrate " +
                    "(Hard-Limit, Pumpe).",
            ),
            info(
                "ISF: Profil, zeitabhaengig",
                "Keine Autosens-/DynISF-Modulation. Sensitivitaet ist als eigener langsamer Beobachter geplant (Shadow zuerst).",
            ),
            info(
                "COB/UAM: nicht verwendet",
                "Mahlzeiten erscheinen im insulinbereinigten Stoerungssignal; eigener Onset-Pfad + Marker statt UAM.",
            ),
            info(
                "Pumpen-Gate",
                "Aktuation ist nur fuer die VirtualPump und den belegten Medtrum Nano freigegeben. Der aktuelle Gate-Grund steht oben im FUSE-Reiter.",
            ),
        ),
    )

    /**
     * Die fuenf Hauptbereiche in ihrer Reihenfolge: Titel, ein Satz und die Schluessel ihrer Bereiche. Der Bildschirm
     * zeichnet sie als Ueberschrift-Zeilen in der Karte, darunter die Bereiche zum Aufklappen.
     */
    internal val hauptbereiche: List<Hauptbereich> = listOf(
        Hauptbereich(
            "Mahlzeiten",
            "Alles vom Knopf bis zum Ende des Mahlzeitfensters. In den Erklärungen und im FUSE-Reiter heißt die " +
                "Mahlzeit-Versorgung auch „Erwartungskonto“ oder kurz „Konto“.",
            listOf("fuse_meal_amount", "fuse_meal_flow", "fuse_meal_special", "fuse_meal_advanced"),
        ),
        Hauptbereich(
            "Korrekturen",
            "Alles außerhalb der Mahlzeit-Versorgung: drei Wege mit eigenen Schwellen.",
            listOf("fuse_corr_smb", "fuse_corr_pressure", "fuse_corr_slow", "fuse_corr_advanced"),
        ),
        Hauptbereich(
            "Schutz und Insulingrenzen",
            "Was Insulin begrenzt oder sperrt, gleich auf welchem Weg.",
            listOf("fuse_protect_limits", "fuse_protect_predicted", "fuse_protect_basal", "fuse_protect_after_low", "fuse_protect_advanced"),
        ),
        Hauptbereich(
            "Signal und Prognose",
            "Modellwerte. Sie wirken auf alle Wege.",
            listOf("fuse_signal"),
        ),
        Hauptbereich(
            "Anzeige und Diagnose",
            "Graph, Beobachter und – deutlich getrennt – die Wartung.",
            listOf("fuse_display"),
        ),
    )

    /** Hat ein Bereich einen Wert mit laufend berechneter Zeile am Feld (v3: updatePreferenceSummary)? */
    internal fun hatLiveZeilen(bereich: PreferenceSubScreenDef): Boolean =
        bereich.items.any { it is PreferenceKey && it.key in fuseLiveZeilenSchluessel }

    /**
     * Die Eingaben der Live-Zeilen: alle Werte mit eigener Zeile ([fuseLiveZeilenSchluessel]), dazu die
     * Expositionsgrenze und die untere Rampenkante, die einzelne Zeilen nennen. Jede Aenderung rechnet ALLE Zeilen
     * neu - in v3 veralteten Nachbarzeilen, wenn nur die geaenderte nachgezogen wurde.
     */
    internal fun liveZeilenEingaben(): List<PreferenceKey> =
        abschnitte().flatMap { it.items }.filterIsInstance<PreferenceKey>().filter { it.key in fuseLiveZeilenSchluessel } +
            listOf(FuseDoubleKey.MealExposureLimitU, FuseDoubleKey.RiseRampLowR)

    /** Alle Werte-Schluessel des Baums, in Reihenfolge - fuer die Vollstaendigkeitswache. */
    internal fun einstellbareKeys(): List<String> =
        abschnitte().flatMap { it.items }.filterIsInstance<PreferenceKey>().filter { it !is IntentPreferenceKey }.map { it.key }

    /**
     * DIE WARTUNG: die drei Ledger-Handlungen, je auf einer eigenen Seite mit Rueckfrage. Ein eigener Bereich am
     * Ende, deutlich getrennt von der Anzeige - das sind keine Einstellwerte, sondern Eingriffe.
     */
    private fun wartung(plugin: FusePlugin) = PreferenceSubScreenDef(
        key = "fuse_maintenance",
        title = TextRef.AndroidRes(R.string.fuse_section_maintenance),
        summary = TextRef.AndroidRes(R.string.fuse_section_maintenance_summary),
        items = listOf(
            info(
                "Ledger",
                "Offene Transporthaftung wird von iobTH- und maxIOB-Spielraum abgezogen. Hold, offene Zeilen und Haftung stehen im Reiter.",
            ),
            FuseIntentKey.LedgerSperre.withCompose(ComposeScreenContent { onBack ->
                FuseLedgerAktionScreen(R.string.fuse_ledger_sperre_title, plugin::holdQuittungLage, showStatus = plugin, onBack = onBack)
            }),
            FuseIntentKey.LedgerWiederherstellung.withCompose(ComposeScreenContent { onBack ->
                FuseLedgerAktionScreen(R.string.fuse_ledger_wiederherstellung_title, plugin::wiederherstellungLage, showStatus = null, onBack = onBack)
            }),
            // Die Reparatur bleibt GANZ unten - sie ist kein Einstellwert, sondern ein Eingriff.
            FuseIntentKey.LedgerReparatur.withCompose(ComposeScreenContent { onBack ->
                FuseLedgerAktionScreen(R.string.fuse_ledger_reparatur_title, plugin::ledgerReparaturLage, showStatus = null, onBack = onBack)
            }),
        ),
        liveLinesOnly = true,
        summaryInHeader = true,
    )

    /**
     * Die Zeilen der Karte in ihrer Reihenfolge: je Hauptbereich eine Ueberschrift-Zeile, darunter seine Bereiche.
     * Ohne Plugin (Test) fehlen die Live-Zeilen und die Wartung; die Gliederung ist dieselbe.
     */
    internal fun gliederung(plugin: FusePlugin? = null): List<PreferenceItem> {
        val bereiche = abschnitte().associateBy { it.key }
        return buildList {
            for (h in hauptbereiche) {
                add(PreferenceInfoDef(text = TextRef.Literal(h.text), title = TextRef.Literal(h.titel), groupHeading = true))
                for (key in h.bereiche) {
                    val b = bereiche.getValue(key)
                    add(if (plugin != null && hatLiveZeilen(b)) b.copy(rowSummaries = plugin.liveZeilen) else b)
                }
            }
            if (plugin != null) add(wartung(plugin))
        }
    }

    fun content(plugin: FusePlugin): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "fuse_settings",
            title = TextRef.AndroidRes(R.string.fuse_settings),
            items = gliederung(plugin),
            liveLinesOnly = true,
        )
}

/**
 * Die laufend berechneten Zeilen von Kurve, Stabilitaet und Mahlzeit-Versorgung am Feld (v3: updatePreferenceSummary):
 * am Feld steht, was der Wert gerade bewirkt, die ausfuehrliche Erklaerung im Dialog beim Antippen.
 *
 * Gerechnet wird abseits des Hauptthreads (die Lage liest Profil und Insulinmodell), neu bei jeder Aenderung einer
 * Eingabe ([FuseSettings.liveZeilenEingaben]). Bis zur ersten Rechnung stehen die Zeilen des letzten Oeffnens, beim
 * ersten Oeffnen "..." - sonst blitzte die lange Erklaerung kurz auf. Scheitert die Rechnung, steht wieder die
 * Erklaerung am Feld.
 */
internal class FuseLiveZeilen(private val plugin: FusePlugin) : PreferenceRowSummaries {

    private val eingaben by lazy { FuseSettings.liveZeilenEingaben() }

    @Volatile private var zuletzt: Map<String, String> = emptyMap()

    @Composable
    override fun lines(): Map<String, String> {
        val stand = eingaben.map { key ->
            when (key) {
                is DoublePreferenceKey  -> rememberPreferenceDoubleState(key).value
                is IntPreferenceKey     -> rememberPreferenceIntState(key).value
                is BooleanPreferenceKey -> rememberPreferenceBooleanState(key).value
                is StringPreferenceKey  -> rememberPreferenceStringState(key).value
                else                    -> null
            }
        }
        val start = zuletzt.ifEmpty { plugin.kontoUndFruehSchluessel.associateWith { "..." } }
        val zeilen by produceState(initialValue = start, stand) {
            val neu = withContext(Dispatchers.Default) {
                runCatching {
                    val lage = plugin.fruehLage()
                    plugin.kontoUndFruehSchluessel.mapNotNull { k -> plugin.fruehZusammenfassung(k, lage)?.let { k to it } }.toMap()
                }.getOrNull()
            }
            value = neu ?: emptyMap()
            if (neu != null) zuletzt = neu
        }
        return zeilen
    }
}

/**
 * Eine Ledger-Handlung: die Lage VOR der Zustimmung, dann "Vormerken" mit Rueckfrage. Ausgefuehrt wird nie
 * hier, sondern am naechsten Zyklusanfang (s. Plugin).
 */
@Composable
private fun FuseLedgerAktionScreen(title: Int, lage: () -> FusePlugin.LedgerAktionLage, showStatus: FusePlugin?, onBack: () -> Unit) {
    var aktuell by remember { mutableStateOf<FusePlugin.LedgerAktionLage?>(null) }
    var status by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fragen by remember { mutableStateOf(false) }
    var ergebnis by remember { mutableStateOf<String?>(null) }
    var neuLaden by remember { mutableIntStateOf(0) }
    LaunchedEffect(neuLaden) {
        aktuell = withContext(Dispatchers.IO) { runCatching(lage).getOrNull() }
        status = showStatus?.let { p -> withContext(Dispatchers.IO) { p.ledgerSperreText() to p.letzteReparaturText() } }
    }
    FuseSeite(title = title, onBack = onBack) {
        status?.let { (sperre, reparatur) ->
            Text(stringResource(TextRef.AndroidRes(R.string.fuse_ledger_aktuelle_sperre)), style = MaterialTheme.typography.titleSmall)
            Text(sperre, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(TextRef.AndroidRes(R.string.fuse_ledger_letzte_reparatur)), style = MaterialTheme.typography.titleSmall)
            Text(reparatur, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
        val l = aktuell
        if (l == null) Text("...", style = MaterialTheme.typography.bodySmall)
        else {
            Text(l.titel, style = MaterialTheme.typography.titleMedium)
            Text(l.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (l.vormerken != null)
                Button(onClick = { fragen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(TextRef.AndroidRes(R.string.fuse_ledger_vormerken)))
                }
        }
    }
    val l = aktuell
    if (fragen && l?.vormerken != null) {
        AlertDialog(
            onDismissRequest = { fragen = false },
            title = { Text(l.titel) },
            text = { Text(l.text) },
            confirmButton = {
                TextButton(onClick = {
                    fragen = false
                    ergebnis = runCatching { l.vormerken.invoke() }.getOrElse { "Fehler: ${it.message}" }
                    neuLaden++
                }) { Text(stringResource(TextRef.AndroidRes(R.string.fuse_ledger_vormerken))) }
            },
            dismissButton = { TextButton(onClick = { fragen = false }) { Text(stringResource(CoreUiStrings.cancel)) } },
        )
    }
    ergebnis?.let { text ->
        AlertDialog(
            onDismissRequest = { ergebnis = null },
            title = { Text(l?.titel ?: "") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { ergebnis = null }) { Text(stringResource(CoreUiStrings.ok)) } },
        )
    }
}

@Composable
private fun FuseSeite(title: Int, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(TextRef.AndroidRes(title))) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = stringResource(CoreUiStrings.close)) }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) { content() }
    }
}

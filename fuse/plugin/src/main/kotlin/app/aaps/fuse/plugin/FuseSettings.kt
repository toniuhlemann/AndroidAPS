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
 * Die FUSE-Einstellungen fuer v4.
 *
 * Gleiche Bereiche, gleiche Reihenfolge und gleiche Werte wie im Fork - mit zwei Anpassungen an den
 * v4-Bildschirm:
 *  - v4 zeichnet eine Ebene unter einem Bereich nicht mehr. Die drei Unterseiten des Forks (Fruehe
 *    Versorgung, Wirkt mit aufs Konto, Tiefschutz) stehen deshalb als eigene Bereiche direkt hinter
 *    ihrem Elternbereich.
 *  - Die Ledger-Handlungen stehen auf je einer eigenen Seite mit Rueckfrage.
 *
 * Wie in v3 stehen die Info-Zeilen des Forks an ihren Plaetzen zwischen den Werten, und die Felder von
 * Erwartungskonto und frueher Versorgung zeigen die laufend berechnete kurze Zeile ([FuseLiveZeilen]). In ALLEN
 * Bereichen (Toni, wie autoISF) steht am Feld nur die Live-Zeile; die Erklaerung kommt beim Hineingehen oder
 * Antippen (liveLinesOnly). Die Info-Zeilen bleiben kurz, die ganze Erklaerung beim Antippen.
 *
 * Die Vollstaendigkeitswache des Forks gilt weiter: [einstellbareKeys] muss genau [fuseEinstellbareKeys]
 * sein, jeder Wert genau einmal (Test).
 */
object FuseSettings {

    /**
     * Ein Bereich. Toni: in ALLEN FUSE-Bereichen am Feld nur die Live-Zeile, die Erklaerung erst im Eingabedialog,
     * bei Schaltern und Absprung-Zeilen hinter dem (i) am Titel. Die Info-Zeilen bleiben kurz mit (i).
     */
    private fun section(key: String, title: Int, vararg items: PreferenceItem) =
        PreferenceSubScreenDef(key = key, title = TextRef.AndroidRes(title), items = items.toList(), liveLinesOnly = true)

    /** Eine Info-Zeile des Forks mit Ueberschrift, an ihrem v3-Platz - wortgleich. */
    private fun info(titel: String, text: String) = PreferenceInfoDef(text = TextRef.Literal(text), title = TextRef.Literal(titel))

    /** Die Beschreibung eines Bereichs ohne eigene Ueberschrift, ueber seinen Feldern (v3: blockBeschreibung). */
    private fun absatz(text: String) = PreferenceInfoDef(text = TextRef.Literal(text))

    /**
     * Der Einstellungsbaum - die Werte mit den Info-Zeilen an ihren v3-Plaetzen, ohne die eigenen Seiten. Fuer
     * Plugin UND Wache.
     */
    internal fun abschnitte(): List<PreferenceSubScreenDef> = listOf(
        section(
            "fuse_meal", R.string.fuse_section_meal,
            info(
                "Mahlzeiten-Frist",
                "So lange nach dem Marker gilt die Mahlzeit: das Erwartungskonto arbeitet in dieser Zeit. " +
                    "Danach gilt der Korrekturweg.",
            ),
            FuseIntKey.LivenessMealPowerMin, FuseDoubleKey.MealExposureLimitU, FuseDoubleKey.MealDemandRatioCap,
            info(
                "Bolusrechner (HCL)",
                "Ein Bolus aus dem AAPS-Bolusrechner mit KH: FUSE kennt dann die Mahlzeit, gibt aber nichts dazu, " +
                    "solange der Zucker es nicht zeigt.",
            ),
            FuseBooleanKey.HclMealsEnabled,
        ),
        section(
            "fuse_expectation", R.string.fuse_section_expectation,
            absatz(FruehVersorgungText.BLOCK_KONTO),
            info("Konto an", "Das Konto gilt, sobald eine gueltige Kurve eingetragen ist - ohne eigenen Schalter."),
            FuseDoubleKey.ExpectationCurveAt30U, FuseDoubleKey.ExpectationCurveAt60U,
            FuseDoubleKey.ExpectationCurveAt90U, FuseDoubleKey.ExpectationCurveAt120U,
            FuseIntKey.ExpectationHorizonMin,
            info("Fenster", "Das Konto arbeitet so lange, wie die Mahlzeiten-Frist laeuft (Bereich Mahlzeit)."),
            info("Freigabe und Schutz", "Ob der Start stabil ist und wann sich die Mahlzeit gezeigt hat."),
            FuseDoubleKey.ExpectationStabilityBgMgdl, FuseIntKey.ExpectationStabilityMin,
            FuseDoubleKey.ExpectationStabilityFallMgdlPerMin, FuseDoubleKey.MealRunningMinR,
            FuseDoubleKey.MealRisingMinBgMgdl,
            FuseDoubleKey.ExpectationReleaseThresholdU,
            // Leer ist erlaubt und der Standard: Erwartung aus.
            FuseStringKey.ExpectationCurve,
        ),
        section(
            "fuse_expectation_early", R.string.fuse_section_expectation_early,
            absatz(FruehVersorgungText.BLOCK_FRUEH),
            FuseBooleanKey.EarlySupplyEnabled, FuseDoubleKey.EarlySupplyBudgetMaxU, FuseDoubleKey.EarlySupplyScaleStable,
            FuseDoubleKey.EarlySupplyScaleAfterLow, FuseDoubleKey.EarlySupplySnackScale,
            FuseDoubleKey.EarlySupplyInitialShare, FuseDoubleKey.EarlySupplyMaxPortionU,
            FuseDoubleKey.EarlySupplyMaxPerPeriodU, FuseIntKey.EarlySupplyPeriodMin,
            FuseDoubleKey.EarlySupplyTopUpShare,
            info("Anrechnung bei Guard und Schwanzpruefung", "Wann die Mahlzeit Guard und Schwanz heben darf."),
            FuseBooleanKey.EarlySupplyInitialLiftsGuard, FuseBooleanKey.EarlySupplyInitialLiftsTail,
            FuseBooleanKey.EarlySupplyGuardWithExpectation, FuseBooleanKey.EarlySupplyTailWithExpectation,
        ),
        section(
            "fuse_expectation_coupled", R.string.fuse_section_expectation_coupled,
            absatz(
                "Aus dem alten Mahlzeitenweg, wirkt aber auch bei \"Mahlzeit\" und \"Snack\": Huelle und " +
                    "Kreditfenster bilden die Kante, bis zu der das Konto die Schwanzpruefung heben darf. Der " +
                    "Aufschub pausiert das Konto bei Abwaertsrisiko. Der Marker-Boost haelt Nacht-Totband und " +
                    "Fenster offen.",
            ),
            FuseDoubleKey.PrimeEnvelopeU, FuseIntKey.AbsorptionCreditWindowMin, FuseIntKey.MarkerBoostMaxMin,
            FuseBooleanKey.DeferredPrimeEnabled, FuseDoubleKey.MarkerPrimeDescentHorizonMin, FuseIntKey.DeferredPrimeEndMin,
        ),
        section(
            "fuse_dosing", R.string.fuse_section_dosing,
            FuseDoubleKey.SmbRatio, FuseDoubleKey.SmbRatioRise, FuseDoubleKey.MaxSmbU,
            FuseDoubleKey.RiseRampLowR, FuseDoubleKey.RiseRampHighR,
            info(
                "Insulingrenzen",
                "Der FUSE-Reiter zeigt zu diesen Grenzen den aktuell verbleibenden Spielraum. " +
                    "Beide Werte begrenzen dieselbe Endsumme; iobTH ist der schnelle Reserve-Anker.",
            ),
            // Die GETEILTE maxIOB-Grenze: eine Sicherheitsgrenze, eine Zahl - kein eigener FUSE-Wert.
            DoubleKey.ApsSmbMaxIob, FuseIntKey.IobThPercent,
            FuseDoubleKey.CorrectionExposureLimitU, FuseDoubleKey.CorrectionDemandRatioCap,
        ),
        section(
            "fuse_dosing_low", R.string.fuse_section_dosing_low,
            info("Guard - die Nahzone", "Sperrt Dosen, deren Bahn in den naechsten Minuten unter den Boden liefe."),
            FuseDoubleKey.GuardFloorMgdl, FuseDoubleKey.PositiveDescentHorizonMin,
            info("Tieftor", "Wann eine gemessene Abwaertslage mit Bolus-Ueberdeckung die Basal-Null ausloest."),
            FuseDoubleKey.LowGateMinBenefitMgdl, FuseDoubleKey.LowGateHorizonMin,
            info("Schwanz - die spaete Wirkung", "Haftung fuer Insulin, das erst hinter dem Freigabe-Horizont wirkt."),
            FuseBooleanKey.TailGuardEnabled, FuseIntKey.LiabilityHorizonMin, FuseDoubleKey.TailFloorMgdl, FuseDoubleKey.TailRecoveryU,
        ),
        section(
            "fuse_correction", R.string.fuse_section_correction,
            info(
                "Liveness-Kanal",
                "Mengenbegrenzter Zusatzkanal bei anhaltend steigendem Zucker ueber der " +
                    "Druck-Schwelle, wenn Guard oder Schwanz den Normalpfad deckeln. " +
                    "Im Fenster von Mahlzeit, Snack und Bolusrechner aus (dort dosiert das Konto); " +
                    "bei \"Nur Marker\" an, mit den Mahlzeit-Deckeln.",
            ),
            FuseBooleanKey.LivenessChannelEnabled, FuseDoubleKey.LivenessBgMinDayMgdl, FuseDoubleKey.LivenessBgMinNightMgdl,
            FuseDoubleKey.LivenessBgMinMarkerOnlyMgdl,
            FuseIntKey.LivenessReArmMin,
            info(
                "Langsamer Korrekturkanal",
                "Positive TBR fuer Tage mit erhoehtem Bedarf (Infekt, Resistenz) - ein Schalter, den du an solchen " +
                    "Tagen einschaltest. Rate nach Bedarf, der Faktor ist nur die Obergrenze. Einstieg und Stopp nur " +
                    "ueber den gemessenen Verlauf; darf an Guard, Schwanz und Exposition vorbei, nie ueber maxIOB. " +
                    "Laeuft wie das Profilbasal nebenher - SMB bleiben unberuehrt.",
            ),
            FuseBooleanKey.SlowChannelEnabled, FuseDoubleKey.SlowChannelFactor, FuseDoubleKey.SlowChannelThresholdMgdl,
            FuseIntKey.SlowChannelEntryMin, FuseDoubleKey.SlowChannelNightFactor, FuseIntKey.SlowChannelNightStartMin,
            FuseIntKey.SlowChannelNightEndMin,
            info("Onset", "Fruehe Reaktion auf einen kinematisch erkannten Mahlzeitbeginn ohne Marker."),
            FuseBooleanKey.OnsetChannelEnabled, FuseDoubleKey.OnsetEnvelopeU,
            info(
                "Korrekturpfad-Schutz",
                "Zwei Riegel fuer unbelegte Korrekturlagen. Sie greifen im " +
                    "Korrekturkontext sowie bei ausschliesslich kinematisch erkanntem " +
                    "Mahlzeitenverdacht. Bei marker- oder evidenzbestaetigter Mahlzeit " +
                    "greifen sie nie. Sie sperren positive Korrektur-SMBs einschliesslich " +
                    "Liveness-K, veraendern aber weder TBR noch Basalantwort.",
            ),
            FuseBooleanKey.CorrectionReversalGuardEnabled, FuseDoubleKey.ReversalFallUkf, FuseIntKey.ReversalLookbackMin,
            FuseDoubleKey.ReversalReboundUkf, FuseIntKey.ReversalConfirmCycles,
            FuseBooleanKey.PositiveCorrectionRearmEnabled, FuseIntKey.RearmHoldMin, FuseIntKey.RearmConfirmCycles, FuseDoubleKey.RearmUpUkf,
        ),
        section(
            "fuse_signal", R.string.fuse_section_signal,
            FuseDoubleKey.BolusShareLambda, FuseIntKey.ReleaseHorizonMin, FuseIntKey.DriveTauMin,
            FuseIntKey.DriveLowerQuantilePct, FuseIntKey.TheilSenWindowMin, FuseBooleanKey.SignalRejoinEnabled,
        ),
        section(
            "fuse_night_rebound", R.string.fuse_section_night_rebound,
            info(
                "Welche Kanaele werden gesperrt?",
                "Beide Totbaender sperren den SMB-Kanal. Eine laufende Basalabsenkung bleibt als Schutz bestehen; " +
                    "positive TBR setzt FUSE nur ueber den langsamen Korrekturkanal (Schalter, ab Werk aus). Ein " +
                    "erklaerter Mahlzeitenmarker kann die Markerregeln oeffnen.",
            ),
            FuseDoubleKey.NightDeadbandMgdl,
            info("Nachtfenster", "Beginn und Ende stehen in Minuten ab Mitternacht (1380 = 23:00). Beginn gleich Ende schaltet es aus."),
            FuseIntKey.NightStartMin, FuseIntKey.NightEndMin,
            FuseDoubleKey.ReboundDeadbandMgdl, FuseIntKey.ReboundWindowMin, FuseIntKey.EvidenceReboundOverrideMaxMin,
            info("Basal", "Null-Basal bei Abwaertslage: wann sie endet, ob sie verriegelt und wie sie zurueckkehrt."),
            FuseBooleanKey.TbrEndZeroWhenReasonGone, FuseBooleanKey.ZeroLatchEnabled, FuseIntKey.ZeroLatchCalmExitMin,
            FuseIntKey.ZeroLatchReasonGoneExitCycles, FuseBooleanKey.PartialRecoveryEnabled, FuseDoubleKey.ZeroLatchCalmDistanceMgdl,
        ),
        section(
            "fuse_system", R.string.fuse_section_system,
            // DER ERWARTUNGS-BEOBACHTER dosiert nichts - deshalb hier und in keinem Regelbereich.
            FuseBooleanKey.ExpectationLedgerEnabled,
            // Ebenso die Anzeige im Hauptgraphen (Toni 21.10.2026).
            FuseBooleanKey.MainGraphLayers,
            info(
                "Regler-Takt: 1 Minute",
                "Jeder neue 1-min-CGM-Wert ist ein Zyklus. Fest - kein Legacy-SMB-Intervall. Geregelt wird mit der insulinbereinigten Stoerungsrate r; Q1 und Rohwert stehen im Reiter nebeneinander.",
            ),
            info(
                "Positive TBR: nur langsamer Korrekturkanal",
                "Der schnelle Kanal ist der 1-min-SMB. Ohne den Schalter \"Langsamer Korrekturkanal\" setzt FUSE nur " +
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
            info(
                "Ledger",
                "Offene Transporthaftung wird von iobTH- und maxIOB-Spielraum abgezogen. Hold, offene Zeilen und Haftung stehen im Reiter.",
            ),
        ),
    )

    /** Die Bereiche mit laufend berechneten Zeilen am Feld (v3: updatePreferenceSummary). */
    private val bereicheMitLiveZeilen = setOf("fuse_expectation", "fuse_expectation_early")

    /**
     * Die Eingaben der Live-Zeilen: alle Werte von Konto und frueher Versorgung, dazu die Expositionsgrenze und die
     * untere Rampenkante, die einzelne Zeilen nennen. Jede Aenderung rechnet ALLE Zeilen neu - in v3 veralteten
     * Nachbarzeilen, wenn nur die geaenderte nachgezogen wurde.
     */
    internal fun liveZeilenEingaben(): List<PreferenceKey> =
        abschnitte().filter { it.key in bereicheMitLiveZeilen }.flatMap { it.items }.filterIsInstance<PreferenceKey>() +
            listOf(FuseDoubleKey.MealExposureLimitU, FuseDoubleKey.RiseRampLowR)

    /** Alle Werte-Schluessel des Baums, in Reihenfolge - fuer die Vollstaendigkeitswache. */
    internal fun einstellbareKeys(): List<String> =
        abschnitte().flatMap { it.items }.filterIsInstance<PreferenceKey>().filter { it !is IntentPreferenceKey }.map { it.key }

    fun content(plugin: FusePlugin): PreferenceSubScreenDef {
        val bereiche = abschnitte().map { b ->
            if (b.key in bereicheMitLiveZeilen) b.copy(rowSummaries = plugin.liveZeilen)
            else if (b.key != "fuse_system") b
            else b.copy(
                items = b.items + listOf(
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
            )
        }
        return PreferenceSubScreenDef(
            key = "fuse_settings",
            title = TextRef.AndroidRes(R.string.fuse_settings),
            items = bereiche,
            liveLinesOnly = true,
        )
    }
}

/**
 * Die laufend berechneten Zeilen von Erwartungskonto und frueher Versorgung am Feld (v3: updatePreferenceSummary):
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

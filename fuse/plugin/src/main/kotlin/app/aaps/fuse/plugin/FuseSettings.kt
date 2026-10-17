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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Die eigenen Zeilen der FUSE-Einstellungen, die kein Wert sind: Hinweise und Ledger-Handlungen.
 * Aktionen, also nichts zu exportieren (s. [IntentPreferenceKey.exportable]).
 */
enum class FuseIntentKey(
    override val key: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.ACTIVITY,
) : IntentPreferenceKey {

    Hinweise("fuse_hinweise", TextRef.AndroidRes(R.string.fuse_hinweise_title), TextRef.AndroidRes(R.string.fuse_hinweise_summary)),
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
 *  - Ein Bereich zeigt nur Werte. Die Info-Zeilen des Forks und die laufend berechneten Zeilen des
 *    Erwartungskontos stehen gesammelt auf einer eigenen Seite ([FuseIntentKey.Hinweise]), die
 *    Ledger-Handlungen auf je einer Seite mit Rueckfrage.
 *
 * Die Vollstaendigkeitswache des Forks gilt weiter: [einstellbareKeys] muss genau [fuseEinstellbareKeys]
 * sein, jeder Wert genau einmal (Test).
 */
object FuseSettings {

    private fun section(key: String, title: Int, vararg items: PreferenceItem) =
        PreferenceSubScreenDef(key = key, title = TextRef.AndroidRes(title), items = items.toList())

    /** Der Einstellungsbaum - die Werte allein, ohne die eigenen Seiten. Fuer Plugin UND Wache. */
    internal fun abschnitte(): List<PreferenceSubScreenDef> = listOf(
        section(
            "fuse_meal", R.string.fuse_section_meal,
            FuseIntKey.LivenessMealPowerMin, FuseDoubleKey.MealExposureLimitU, FuseDoubleKey.MealDemandRatioCap,
        ),
        section(
            "fuse_expectation", R.string.fuse_section_expectation,
            FuseDoubleKey.ExpectationCurveAt30U, FuseDoubleKey.ExpectationCurveAt60U,
            FuseDoubleKey.ExpectationCurveAt90U, FuseDoubleKey.ExpectationCurveAt120U,
            FuseIntKey.ExpectationHorizonMin,
            FuseDoubleKey.ExpectationStabilityBgMgdl, FuseIntKey.ExpectationStabilityMin,
            FuseDoubleKey.ExpectationStabilityFallMgdlPerMin, FuseDoubleKey.MealRunningMinR,
            FuseDoubleKey.ExpectationReleaseThresholdU,
            // Leer ist erlaubt und der Standard: Erwartung aus.
            FuseStringKey.ExpectationCurve,
        ),
        section(
            "fuse_expectation_early", R.string.fuse_section_expectation_early,
            FuseBooleanKey.EarlySupplyEnabled, FuseDoubleKey.EarlySupplyBudgetMaxU, FuseDoubleKey.EarlySupplyScaleStable,
            FuseDoubleKey.EarlySupplyScaleAfterLow, FuseDoubleKey.EarlySupplyInitialShare, FuseDoubleKey.EarlySupplyMaxPortionU,
            FuseDoubleKey.EarlySupplyMaxPerPeriodU, FuseIntKey.EarlySupplyPeriodMin,
            FuseBooleanKey.EarlySupplyInitialLiftsGuard, FuseBooleanKey.EarlySupplyInitialLiftsTail,
            FuseBooleanKey.EarlySupplyGuardWithExpectation, FuseBooleanKey.EarlySupplyTailWithExpectation,
        ),
        section(
            "fuse_expectation_coupled", R.string.fuse_section_expectation_coupled,
            FuseDoubleKey.PrimeEnvelopeU, FuseIntKey.AbsorptionCreditWindowMin, FuseIntKey.MarkerBoostMaxMin,
            FuseBooleanKey.DeferredPrimeEnabled, FuseDoubleKey.MarkerPrimeDescentHorizonMin, FuseIntKey.DeferredPrimeEndMin,
        ),
        section(
            "fuse_dosing", R.string.fuse_section_dosing,
            FuseDoubleKey.SmbRatio, FuseDoubleKey.SmbRatioRise, FuseDoubleKey.MaxSmbU,
            FuseDoubleKey.RiseRampLowR, FuseDoubleKey.RiseRampHighR,
            // Die GETEILTE maxIOB-Grenze: eine Sicherheitsgrenze, eine Zahl - kein eigener FUSE-Wert.
            DoubleKey.ApsSmbMaxIob, FuseIntKey.IobThPercent,
            FuseDoubleKey.CorrectionExposureLimitU, FuseDoubleKey.CorrectionDemandRatioCap,
        ),
        section(
            "fuse_dosing_low", R.string.fuse_section_dosing_low,
            FuseDoubleKey.GuardFloorMgdl, FuseDoubleKey.PositiveDescentHorizonMin,
            FuseDoubleKey.LowGateMinBenefitMgdl, FuseDoubleKey.LowGateHorizonMin,
            FuseBooleanKey.TailGuardEnabled, FuseIntKey.LiabilityHorizonMin, FuseDoubleKey.TailFloorMgdl, FuseDoubleKey.TailRecoveryU,
        ),
        section(
            "fuse_correction", R.string.fuse_section_correction,
            FuseBooleanKey.LivenessChannelEnabled, FuseDoubleKey.LivenessBgMinDayMgdl, FuseDoubleKey.LivenessBgMinNightMgdl,
            FuseIntKey.LivenessReArmMin,
            FuseBooleanKey.OnsetChannelEnabled, FuseDoubleKey.OnsetEnvelopeU,
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
            FuseDoubleKey.NightDeadbandMgdl, FuseIntKey.NightStartMin, FuseIntKey.NightEndMin,
            FuseDoubleKey.ReboundDeadbandMgdl, FuseIntKey.ReboundWindowMin, FuseIntKey.EvidenceReboundOverrideMaxMin,
            FuseBooleanKey.TbrEndZeroWhenReasonGone, FuseBooleanKey.ZeroLatchEnabled, FuseIntKey.ZeroLatchCalmExitMin,
            FuseIntKey.ZeroLatchReasonGoneExitCycles, FuseBooleanKey.PartialRecoveryEnabled, FuseDoubleKey.ZeroLatchCalmDistanceMgdl,
        ),
        section(
            "fuse_system", R.string.fuse_section_system,
            // DER ERWARTUNGS-BEOBACHTER dosiert nichts - deshalb hier und in keinem Regelbereich.
            FuseBooleanKey.ExpectationLedgerEnabled,
        ),
    )

    /** Alle Werte-Schluessel des Baums, in Reihenfolge - fuer die Vollstaendigkeitswache. */
    internal fun einstellbareKeys(): List<String> =
        abschnitte().flatMap { it.items }.filterIsInstance<PreferenceKey>().filter { it !is IntentPreferenceKey }.map { it.key }

    fun content(plugin: FusePlugin): PreferenceSubScreenDef {
        val bereiche = abschnitte().map { b ->
            if (b.key != "fuse_system") b
            else b.copy(
                items = b.items + listOf(
                    FuseIntentKey.Hinweise.withCompose(ComposeScreenContent { onBack -> FuseHinweiseScreen(plugin, onBack) }),
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
        )
    }

    /** Die Info-Zeilen des Forks, je Bereich - wortgleich. */
    internal val hinweise: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Mahlzeit" to listOf(
            "Mahlzeiten-Frist" to "So lange nach dem Marker gilt die Mahlzeit: das Erwartungskonto arbeitet in dieser Zeit. " +
                "Danach gilt der Korrekturweg.",
        ),
        "Erwartungskonto" to listOf(
            "Erwartungskonto" to FruehVersorgungText.BLOCK_KONTO,
            "Konto an" to "Das Konto gilt, sobald eine gueltige Kurve eingetragen ist - ohne eigenen Schalter.",
            "Fenster" to "Das Konto arbeitet so lange, wie die Mahlzeiten-Frist laeuft (Bereich Mahlzeit).",
            "Freigabe und Schutz" to "Ob der Start stabil ist und wann sich die Mahlzeit gezeigt hat.",
        ),
        "Fruehe Versorgung" to listOf(
            "Fruehe Versorgung" to FruehVersorgungText.BLOCK_FRUEH,
            "Anrechnung bei Guard und Schwanzpruefung" to "Wann die Mahlzeit Guard und Schwanz heben darf.",
        ),
        "Wirkt mit aufs Konto" to listOf(
            "Wirkt mit aufs Konto" to "Aus dem alten Mahlzeitenweg, wirkt aber auch bei \"Nur Erwartung\": Huelle und " +
                "Kreditfenster bilden die Kante, bis zu der das Konto die Schwanzpruefung heben darf. Der " +
                "Aufschub pausiert das Konto bei Abwaertsrisiko. Der Marker-Boost haelt Nacht-Totband und " +
                "Fenster offen.",
        ),
        "Dosierung und Grenzen" to listOf(
            "Insulingrenzen" to "Der FUSE-Reiter zeigt zu diesen Grenzen den aktuell verbleibenden Spielraum. " +
                "Beide Werte begrenzen dieselbe Endsumme; iobTH ist der schnelle Reserve-Anker.",
        ),
        "Tiefschutz" to listOf(
            "Guard - die Nahzone" to "Sperrt Dosen, deren Bahn in den naechsten Minuten unter den Boden liefe.",
            "Tieftor" to "Wann eine gemessene Abwaertslage mit Bolus-Ueberdeckung die Basal-Null ausloest.",
            "Schwanz - die spaete Wirkung" to "Haftung fuer Insulin, das erst hinter dem Freigabe-Horizont wirkt.",
        ),
        "Korrektur und Liveness" to listOf(
            "Liveness-Kanal" to "Mengenbegrenzter Zusatzkanal bei anhaltend steigendem Zucker ueber der " +
                "Druck-Schwelle, wenn Guard oder Schwanz den Normalpfad deckeln. " +
                "Unabhaengig von Marker und Mahlzeitenfenster.",
            "Onset" to "Fruehe Reaktion auf einen kinematisch erkannten Mahlzeitbeginn ohne Marker.",
            "Korrekturpfad-Schutz" to "Zwei Riegel fuer unbelegte Korrekturlagen. Sie greifen im " +
                "Korrekturkontext sowie bei ausschliesslich kinematisch erkanntem " +
                "Mahlzeitenverdacht. Bei marker- oder evidenzbestaetigter Mahlzeit " +
                "greifen sie nie. Sie sperren positive Korrektur-SMBs einschliesslich " +
                "Liveness-K, veraendern aber weder TBR noch Basalantwort.",
        ),
        "Nacht, Rebound und Basal" to listOf(
            "Welche Kanaele werden gesperrt?" to "Beide Totbaender sperren den SMB-Kanal. Eine laufende Basalabsenkung bleibt als Schutz bestehen; " +
                "positive TBR verwendet FUSE derzeit nicht. Ein erklaerter Mahlzeitenmarker kann die Markerregeln oeffnen.",
            "Nachtfenster" to "Beginn und Ende stehen in Minuten ab Mitternacht (1380 = 23:00). Beginn gleich Ende schaltet es aus.",
            "Basal" to "Null-Basal bei Abwaertslage: wann sie endet, ob sie verriegelt und wie sie zurueckkehrt.",
        ),
        "Diagnose und System" to listOf(
            "Regler-Takt: 1 Minute" to "Jeder neue 1-min-CGM-Wert ist ein Zyklus. Fest - kein Legacy-SMB-Intervall. Geregelt wird mit der insulinbereinigten Stoerungsrate r; Q1 und Rohwert stehen im Reiter nebeneinander.",
            "Positive TBR: nicht verwendet" to "Der schnelle Kanal ist der 1-min-SMB; FUSE setzt nur Null-Temps oder bricht ab. Max-TBR/Basal-Multiplikatoren greifen deshalb nicht.",
            "ISF: Profil, zeitabhaengig" to "Keine Autosens-/DynISF-Modulation. Sensitivitaet ist als eigener langsamer Beobachter geplant (Shadow zuerst).",
            "COB/UAM: nicht verwendet" to "Mahlzeiten erscheinen im insulinbereinigten Stoerungssignal; eigener Onset-Pfad + Marker statt UAM.",
            "Pumpen-Gate" to "Aktuation ist nur fuer die VirtualPump und den belegten Medtrum Nano freigegeben. Der aktuelle Gate-Grund steht oben im FUSE-Reiter.",
            "Ledger" to "Offene Transporthaftung wird von iobTH- und maxIOB-Spielraum abgezogen. Hold, offene Zeilen und Haftung stehen im Reiter.",
        ),
    )
}

/** Die Hinweise je Bereich und - laufend berechnet - was die Werte von Konto und frueher Versorgung gerade bewirken. */
@Composable
private fun FuseHinweiseScreen(plugin: FusePlugin, onBack: () -> Unit) {
    var werte by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        werte = withContext(Dispatchers.Default) {
            runCatching {
                val lage = plugin.fruehLage()
                plugin.kontoUndFruehSchluessel.mapNotNull { k -> plugin.fruehZusammenfassung(k, lage)?.let { "$k: $it" } }
            }.getOrNull().orEmpty()
        }
    }
    FuseSeite(title = R.string.fuse_hinweise_title, onBack = onBack) {
        for ((bereich, zeilen) in FuseSettings.hinweise) {
            Text(bereich, style = MaterialTheme.typography.titleMedium)
            for ((titel, text) in zeilen) {
                Text(titel, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        Text(stringResource(TextRef.AndroidRes(R.string.fuse_hinweise_werte_title)), style = MaterialTheme.typography.titleMedium)
        val w = werte
        if (w == null) Text("...", style = MaterialTheme.typography.bodySmall)
        else w.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
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

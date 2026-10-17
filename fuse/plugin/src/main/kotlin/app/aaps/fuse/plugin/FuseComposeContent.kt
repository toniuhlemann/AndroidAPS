package app.aaps.fuse.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.stringResource
import app.aaps.fuse.core.controller.MarkerRuecknahme
import app.aaps.fuse.core.controller.OnsetChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Der FUSE-Reiter (v4: Compose statt FuseFragment). Inhalt und Bedienung wie im Fork: Betriebssicht aus
 * [FuseDashboardModel], Mahlzeiten-Knopf mit Rueckfrage und Ruecknahme-Frist, "Mahlzeit nach Tief
 * bestaetigen", Einstellungen, und die Details aus [FuseScreenModel].
 */
class FuseComposeContent(
    private val plugin: FusePlugin,
    private val dateUtil: DateUtil,
    private val rxBus: RxBus,
) : ComposablePluginContent {

    @Composable
    override fun Render(setToolbarConfig: (ToolbarConfig) -> Unit, onNavigateBack: () -> Unit, onSettings: (() -> Unit)?) {
        FuseScreen(plugin, dateUtil, rxBus, onSettings)
    }
}

/** Was der Reiter zeigt - einmal je Aktualisierung gebaut, aus derselben Lesung. */
private class FuseAnsicht(
    val dashboard: FuseDashboardModel.View,
    val details: String,
    val markerAktiv: Boolean,
    /** Das Mahlzeitfenster des Kontos laeuft noch (nach den ersten 90 min startet ein Druck dann eine NEUE Mahlzeit). */
    val fensterLaeuft: Boolean,
    val fruehGesperrt: Boolean,
    val fruehBestaetigt: Boolean,
)

private suspend fun ansicht(plugin: FusePlugin, now: Long): FuseAnsicht {
    // Konsolidierung 2.0: die Karte zeigt das Mahlzeitfenster des Kontos (Tonis Frist), nicht das 90-min-Markerfenster
    // aus 1.x - sonst stand dort "abgelaufen", waehrend die Mahlzeit noch lief. Ohne Konto wie bisher.
    val armedTs = plugin.mealMarkerArmedTs()
    val fensterEnde = plugin.mahlzeitFensterEndeTs()
    val marker = FuseScreenModel.MarkerInfo(
        armedTs = armedTs,
        windowMin = if (fensterEnde > armedTs && armedTs > 0L) ((fensterEnde - armedTs) / 60_000L).toInt()
        else OnsetChannel.MARKER_WINDOW_MIN,
        envelopeU = plugin.mealMarkerEnvelopeU(),
    )
    val ledger = plugin.ledgerInfo()
    val dashboard = FuseDashboardModel.build(
        plugin.lastOutcome, plugin.lastAPSResult, now, marker, ledger, plugin.dashboardProfileInfo(now),
    )
    val details = FuseScreenModel.render(
        plugin.lastOutcome, plugin.lastAPSResult, now, marker, ledger,
        settings = FuseSettingsReport.build(plugin.preferences),
    )
    val gesperrt = plugin.fruehGesperrtNachTief(now)
    return FuseAnsicht(dashboard, details, plugin.mealMarkerActive(now), fensterEnde > now, gesperrt, gesperrt && plugin.fruehNachTiefBestaetigt())
}

@Composable
private fun FuseScreen(plugin: FusePlugin, dateUtil: DateUtil, rxBus: RxBus, onSettings: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var aktualisierung by remember { mutableIntStateOf(0) }
    var stand by remember { mutableStateOf<FuseAnsicht?>(null) }
    var detailsOffen by remember { mutableStateOf(false) }
    var rueckfrage by remember { mutableStateOf<Pair<FuseOverviewSource.MarkerPromptFacts, String>?>(null) }
    var ruecknahmeFaellig by remember { mutableStateOf<Long?>(null) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var tiefBestaetigen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { rxBus.toFlow(EventAPSCalculationFinished::class).collect { aktualisierung++ } }
    LaunchedEffect(aktualisierung) {
        stand = withContext(Dispatchers.Default) { runCatching { ansicht(plugin, dateUtil.now()) }.getOrNull() }
    }
    // Die Ruecknahme-Leiste zaehlt herunter; nach der Frist (plus Nachlauf) ist der Marker zurueckgenommen.
    LaunchedEffect(ruecknahmeFaellig) {
        val faellig = ruecknahmeFaellig ?: return@LaunchedEffect
        while (dateUtil.now() < faellig + 500L) delay(1_000L)
        ruecknahmeFaellig = null
        aktualisierung++
    }

    val quelle = MarkerRuecknahme.Quelle.FUSE_TAB
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        val s = stand
        Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                scope.launch {
                    val now = dateUtil.now()
                    // Die Ereigniskennung beim ANZEIGEN der Rueckfrage, nicht beim Bestaetigen (s. Plugin).
                    val ereignis = plugin.markerEreignisKennung()
                    val fakten = plugin.fuseMarkerPrompt(now)
                    if (fakten == null) {
                        // Laeuft ein Marker: Ruecknahme mit Frist statt sofort.
                        val faellig = plugin.markerRuecknahmeVormerken(now, ereignis, quelle)
                        if (faellig != null) ruecknahmeFaellig = faellig
                        aktualisierung++
                    } else rueckfrage = fakten to ereignis
                }
            }) {
                // Nach den ersten 90 min startet ein Druck eine NEUE Mahlzeit (neues Konto), auch wenn das Fenster der
                // laufenden noch offen ist - der Knopf sagt das, statt wie ohne Mahlzeit auszusehen.
                Text(
                    when {
                        s?.markerAktiv == true -> "◉ MAHLZEIT AKTIV"
                        s?.fensterLaeuft == true -> stringResource(TextRef.AndroidRes(R.string.fuse_marker_new_meal))
                        else -> stringResource(TextRef.AndroidRes(R.string.fuse_marker_m))
                    }
                )
            }
            if (onSettings != null) OutlinedButton(onClick = onSettings) { Text(stringResource(TextRef.AndroidRes(R.string.fuse_open_settings))) }
        }
        ruecknahmeFaellig?.let { faellig ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(AapsSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                    val restS = ((faellig - dateUtil.now()) / 1000L).coerceAtLeast(0L).toInt()
                    Text(stringResource(TextRef.AndroidRes(R.string.fuse_meal_withdraw_pending), restS), modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            val ok = plugin.markerRuecknahmeRueckgaengig(dateUtil.now(), quelle)
                            ruecknahmeFaellig = null
                            meldung = if (ok) plugin.rh.gs(TextRef.AndroidRes(R.string.fuse_meal_withdraw_undone))
                            else plugin.rh.gs(TextRef.AndroidRes(R.string.fuse_meal_withdraw_too_late))
                            aktualisierung++
                        }
                    }) { Text(stringResource(TextRef.AndroidRes(R.string.fuse_meal_withdraw_undo))) }
                }
            }
        }
        if (s?.fruehGesperrt == true) {
            Button(onClick = { tiefBestaetigen = true }, enabled = !s.fruehBestaetigt, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(TextRef.AndroidRes(if (s.fruehBestaetigt) R.string.fuse_early_low_confirm_waiting else R.string.fuse_early_low_confirm)))
            }
        }
        if (s == null) Text("...")
        else {
            val d = s.dashboard
            Text(d.status, style = MaterialTheme.typography.titleMedium)
            Text(d.statusDetail, style = MaterialTheme.typography.bodySmall)
            Text(d.signal, style = MaterialTheme.typography.bodyMedium)
            Text(d.action, style = MaterialTheme.typography.bodyMedium)
            d.smbStatus?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium,
                    color = when (d.smbStatusTone) {
                        "FREE" -> Color(0xFF66BB6A)
                        "STOP" -> Color(0xFFEF5350)
                        else   -> Color(0xFF9CA3AF)
                    }
                )
            }
            Text(d.decisionReason, style = MaterialTheme.typography.bodySmall)
            Text(d.gate, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(stringResource(TextRef.AndroidRes(R.string.fuse_meal_evidence)), style = MaterialTheme.typography.titleSmall)
            Text(d.marker, style = MaterialTheme.typography.bodySmall)
            Text(d.evidence, style = MaterialTheme.typography.bodySmall)
            d.windows?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text(stringResource(TextRef.AndroidRes(R.string.fuse_budget_profile)), style = MaterialTheme.typography.titleSmall)
            Text(d.iobLine, style = MaterialTheme.typography.bodySmall)
            d.transport?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(d.limits, style = MaterialTheme.typography.bodySmall)
            Text(d.profile, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            TextButton(onClick = { detailsOffen = !detailsOffen }) { Text(stringResource(TextRef.AndroidRes(R.string.fuse_show_details))) }
            if (detailsOffen) Text(s.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }

    rueckfrage?.let { (fakten, ereignis) ->
        MarkerRueckfrage(
            fakten = fakten,
            onNurErwartung = {
                rueckfrage = null
                scope.launch {
                    plugin.toggleMealMarker(dateUtil.now(), ereignisId = ereignis, quelle = quelle.text, nurErwartung = true)
                    aktualisierung++
                }
            },
            onOhneVorschuss = {
                rueckfrage = null
                scope.launch {
                    plugin.toggleMealMarker(dateUtil.now(), ohneVorschuss = true, ereignisId = ereignis, quelle = quelle.text)
                    aktualisierung++
                }
            },
            onAbbrechen = { rueckfrage = null },
        )
    }
    if (tiefBestaetigen) {
        AlertDialog(
            onDismissRequest = { tiefBestaetigen = false },
            title = { Text(stringResource(TextRef.AndroidRes(R.string.fuse_early_low_confirm_title))) },
            text = { Text(stringResource(TextRef.AndroidRes(R.string.fuse_early_low_confirm_message))) },
            confirmButton = {
                TextButton(onClick = {
                    tiefBestaetigen = false
                    plugin.bestaetigeMahlzeitNachTief(dateUtil.now())
                    aktualisierung++
                }) { Text(stringResource(CoreUiStrings.ok)) }
            },
            dismissButton = { TextButton(onClick = { tiefBestaetigen = false }) { Text(stringResource(CoreUiStrings.cancel)) } },
        )
    }
    meldung?.let { text ->
        AlertDialog(
            onDismissRequest = { meldung = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { meldung = null }) { Text(stringResource(CoreUiStrings.ok)) } },
        )
    }
}

/**
 * Die Rueckfrage am Mahlzeiten-Knopf (Fork: FuseMarkerDialog). Mit Konto: "Nur Erwartung" oder "Nur Marker - 0 U";
 * ohne Konto nur "Nur Marker - 0 U". Tief, manuelles Insulin und ablaufendes Evidenz-Fenster stehen dabei.
 * Eine Wahl wirkt genau einmal (Doppeltipp-Schutz wie im Fork).
 */
@Composable
private fun MarkerRueckfrage(
    fakten: FuseOverviewSource.MarkerPromptFacts,
    onNurErwartung: () -> Unit,
    onOhneVorschuss: () -> Unit,
    onAbbrechen: () -> Unit,
) {
    var gewaehlt by remember { mutableStateOf(false) }
    val bisU = fakten.expectationOnlyMaxU
    val text = buildString {
        append(
            if (bisU != null) stringResource(TextRef.AndroidRes(R.string.fuse_meal_confirm_expectation_only_text), bisU)
            else stringResource(TextRef.AndroidRes(R.string.fuse_meal_confirm_no_account))
        )
        if (fakten.measuredLow) append("\n\n").append(stringResource(TextRef.AndroidRes(R.string.fuse_meal_confirm_low)))
        fakten.foreignBolusU?.takeIf { it > 0.0 }?.let { append("\n\n").append(stringResource(TextRef.AndroidRes(R.string.fuse_meal_confirm_foreign), it)) }
        fakten.episodeRestMin?.let { append("\n\n").append(stringResource(TextRef.AndroidRes(R.string.fuse_meal_confirm_episode), it)) }
    }
    fun einmal(aktion: () -> Unit) {
        if (gewaehlt) return
        gewaehlt = true
        aktion()
    }
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(TextRef.AndroidRes(R.string.fuse_meal_confirm_title))) },
        text = { Text(text) },
        confirmButton = {
            if (bisU != null) TextButton(onClick = { einmal(onNurErwartung) }) { Text(stringResource(TextRef.AndroidRes(R.string.fuse_meal_confirm_expectation_only))) }
            else TextButton(onClick = { einmal(onOhneVorschuss) }) { Text(stringResource(TextRef.AndroidRes(R.string.fuse_meal_confirm_no_prime))) }
        },
        dismissButton = {
            Row {
                if (bisU != null) TextButton(onClick = { einmal(onOhneVorschuss) }) { Text(stringResource(TextRef.AndroidRes(R.string.fuse_meal_confirm_no_prime))) }
                TextButton(onClick = { einmal(onAbbrechen) }) { Text(stringResource(CoreUiStrings.cancel)) }
            }
        },
    )
}

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
import app.aaps.core.ui.compose.fuse.FuseMealPromptDialog
import app.aaps.core.ui.compose.stringResource
import app.aaps.fuse.core.controller.MarkerRuecknahme
import app.aaps.fuse.core.controller.OnsetChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Der FUSE-Reiter (v4: Compose statt FuseFragment). Inhalt und Bedienung wie im Fork: Betriebssicht aus
 * [FuseDashboardModel], Mahlzeiten-Knopf mit Rueckfrage und Ruecknahme-Frist, Einstellungen, und die Details aus
 * [FuseScreenModel]. Toni 22.10.: "Mahlzeit nach Tief bestaetigen" ist entfallen - die Tief-Sperre faellt von selbst.
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
    /** HCL Stufe 2: den laufenden Marker hat der Bolusrechner gesetzt (nicht zusammengefuehrt). */
    val ausBolusrechner: Boolean = false,
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
    val aktiv = plugin.mealMarkerActive(now)
    val ausBolusrechner = aktiv && plugin.preferences.get(FuseLongKey.MealMarkerHclCalcTs) > 0L &&
        plugin.preferences.get(FuseLongKey.MealMarkerHclMerged) == 0L
    return FuseAnsicht(dashboard, details, aktiv, fensterEnde > now, ausBolusrechner)
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
                        // HCL Stufe 2: die Mahlzeit laeuft ueber den Bolusrechner - ein Druck nimmt auch sie zurueck.
                        s?.markerAktiv == true && s.ausBolusrechner -> "◉ MAHLZEIT AKTIV (BOLUSRECHNER)"
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
                    Text(stringResource(CoreUiStrings.fuse_meal_withdraw_pending, restS), modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            val ok = plugin.markerRuecknahmeRueckgaengig(dateUtil.now(), quelle)
                            ruecknahmeFaellig = null
                            meldung = if (ok) plugin.rh.gs(CoreUiStrings.fuse_meal_withdraw_undone)
                            else plugin.rh.gs(CoreUiStrings.fuse_meal_withdraw_too_late)
                            aktualisierung++
                        }
                    }) { Text(stringResource(CoreUiStrings.fuse_meal_withdraw_undo)) }
                }
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
        // Derselbe Dialog wie am Knopf auf dem Hauptbildschirm (core:ui) - zwei Fassungen waeren zwei Sicherheitsniveaus.
        FuseMealPromptDialog(
            facts = fakten,
            onExpectationOnly = {
                rueckfrage = null
                scope.launch {
                    plugin.toggleMealMarker(dateUtil.now(), ereignisId = ereignis, quelle = quelle.text, nurErwartung = true)
                    aktualisierung++
                }
            },
            onNoPrime = {
                rueckfrage = null
                scope.launch {
                    plugin.toggleMealMarker(dateUtil.now(), ohneVorschuss = true, ereignisId = ereignis, quelle = quelle.text)
                    aktualisierung++
                }
            },
            onCancel = { rueckfrage = null },
            onSnack = {
                rueckfrage = null
                scope.launch {
                    plugin.toggleMealMarker(dateUtil.now(), ereignisId = ereignis, quelle = quelle.text, nurErwartung = true, snack = true)
                    aktualisierung++
                }
            },
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


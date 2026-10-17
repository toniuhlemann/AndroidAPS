package app.aaps.plugins.aps.iobaction

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Android settings screen of the local command channel (fork; there a category in the autoISF
 * preferences, here a sub screen behind ApsIntentKey.LocalCommandChannel).
 *
 * Everything is stored in the channel's own namespace ([LocalCommandChannelPrefs]), never in the app
 * preferences, and no channel command can change it.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class AndroidLocalCommandChannelSettings(
    private val context: Context,
    private val coordinator: AutoIsfValueLeaseCoordinator,
) : LocalCommandChannelSettings {

    override fun screen(): ComposeScreenContent = ComposeScreenContent { onBack ->
        LocalCommandChannelScreen(context, coordinator, onBack)
    }
}

@Composable
internal fun LocalCommandChannelScreen(appContext: Context, coordinator: AutoIsfValueLeaseCoordinator, onBack: () -> Unit) {
    // The secret is shown on this screen: no screenshots, no preview in the recent apps list.
    val activity = LocalContext.current.findActivity()
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val sp = remember { appContext.getSharedPreferences(LocalCommandChannelPrefs.PREFS, Context.MODE_PRIVATE) }
    // Bumped after every action so the status line is read again.
    var statusVersion by remember { mutableIntStateOf(0) }
    var shownSecret by remember { mutableStateOf<String?>(null) }
    var trustedDigest by remember { mutableStateOf<String?>(null) }
    var viewerMissing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(ApsStrings.local_command_channel_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(CoreUiStrings.close))
                    }
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
        ) {
            Text(stringResource(ApsStrings.local_command_channel_summary), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            GateSwitch(sp, LocalCommandChannelPrefs.KEY_CHANNEL, ApsStrings.lcc_channel_enabled, ApsStrings.lcc_channel_enabled_summary) { v, write ->
                coordinator.beforeGateWrite(newChannel = v, write = write)
            }
            // The temp target capability has no lease, so no coordinator transition: a plain write.
            GateSwitch(sp, LocalCommandChannelPrefs.KEY_TT, ApsStrings.lcc_tt, ApsStrings.lcc_tt_summary) { _, write -> write() }
            GateSwitch(sp, LocalCommandChannelPrefs.KEY_IOBTH, ApsStrings.lcc_iobth, ApsStrings.lcc_iobth_summary) { v, write ->
                coordinator.beforeGateWrite(newIobth = v, write = write)
            }
            GateSwitch(sp, LocalCommandChannelPrefs.KEY_AUTOSTATE, ApsStrings.lcc_autostate, ApsStrings.lcc_autostate_summary) { v, write ->
                coordinator.beforeGateWrite(newAutoState = v, write = write)
            }
            GateSwitch(sp, LocalCommandChannelPrefs.KEY_SMBRATIO, ApsStrings.lcc_smbratio, ApsStrings.lcc_smbratio_summary) { v, write ->
                coordinator.beforeGateWrite(newSmbRatio = v, write = write)
            }
            GateSwitch(sp, LocalCommandChannelPrefs.KEY_WEIGHTS, ApsStrings.lcc_weights, ApsStrings.lcc_weights_summary) { v, write ->
                coordinator.beforeGateWrite(newWeights = v, write = write)
            }
            GateSwitch(sp, LocalCommandChannelPrefs.KEY_FORCED_VALIDATE, ApsStrings.lcc_forced_vo, ApsStrings.lcc_forced_vo_summary) { v, write ->
                coordinator.beforeGateWrite(newForcedVo = v, write = write)
            }
            HorizontalDivider()
            ActionRow(ApsStrings.lcc_secret, ApsStrings.lcc_secret_summary) {
                shownSecret = runCatching { createSecret(appContext) }.getOrNull()
                statusVersion++
            }
            ActionRow(ApsStrings.lcc_trust_viewer, ApsStrings.lcc_trust_viewer_summary) {
                val digests = runCatching { viewerDigests(appContext) }.getOrDefault(emptyList())
                if (digests.isEmpty()) viewerMissing = true
                else {
                    sp.edit().putString(LocalCommandChannelPrefs.KEY_TRUSTED, digests.joinToString(",")).apply()
                    trustedDigest = digests.first().take(16)
                }
                statusVersion++
            }
            HorizontalDivider()
            StatusRow(appContext, sp, statusVersion)
        }
    }

    shownSecret?.let { hex ->
        AlertDialog(
            onDismissRequest = { shownSecret = null },
            title = { Text(stringResource(ApsStrings.lcc_secret_dialog_title)) },
            text = { Text(hex.chunked(16).joinToString("\n"), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { shownSecret = null }) { Text(stringResource(CoreUiStrings.ok)) } },
            dismissButton = { TextButton(onClick = { copySensitive(appContext, hex) }) { Text(stringResource(ApsStrings.lcc_copy)) } }
        )
    }
    trustedDigest?.let { digest ->
        AlertDialog(
            onDismissRequest = { trustedDigest = null },
            title = { Text(stringResource(ApsStrings.lcc_trusted_title)) },
            text = { Text(stringResource(ApsStrings.lcc_trusted_message, digest)) },
            confirmButton = { TextButton(onClick = { trustedDigest = null }) { Text(stringResource(CoreUiStrings.ok)) } }
        )
    }
    if (viewerMissing) {
        AlertDialog(
            onDismissRequest = { viewerMissing = false },
            title = { Text(stringResource(ApsStrings.lcc_viewer_missing_title)) },
            text = { Text(stringResource(ApsStrings.lcc_viewer_missing_message)) },
            confirmButton = { TextButton(onClick = { viewerMissing = false }) { Text(stringResource(CoreUiStrings.ok)) } }
        )
    }
}

/**
 * One gate switch. R13-F1 + R14-F2 (fork): the write runs as a callback INSIDE the coordinator lock, so
 * the revoke of a running lease and the new gate value are one step that no parallel SET can split. If
 * the coordinator fails before the write, the plain write still runs - a switch must never do nothing.
 */
@Composable
private fun GateSwitch(sp: SharedPreferences, key: String, title: TextRef, summary: TextRef, gateWrite: (Boolean, () -> Unit) -> Unit) {
    var checked by remember { mutableStateOf(sp.getBoolean(key, false)) }
    val onChange: (Boolean) -> Unit = { value ->
        val write = { sp.edit().putBoolean(key, value).apply() }
        runCatching { gateWrite(value, write) }.onFailure { runCatching { write() } }
        checked = sp.getBoolean(key, false)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(title: TextRef, summary: TextRef, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusRow(context: Context, sp: SharedPreferences, @Suppress("UNUSED_PARAMETER") version: Int) {
    val secretSet = File(context.noBackupFilesDir, LocalCommandChannelPrefs.SECRET_FILE).exists()
    val trusted = !sp.getString(LocalCommandChannelPrefs.KEY_TRUSTED, "").isNullOrBlank()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(ApsStrings.lcc_status), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                ApsStrings.lcc_status_summary,
                stringResource(if (secretSet) ApsStrings.lcc_status_present else ApsStrings.lcc_status_missing),
                stringResource(if (trusted) ApsStrings.lcc_status_set else ApsStrings.lcc_status_empty),
                sp.getInt(LocalCommandChannelPrefs.KEY_UNAUTH_COUNT, 0)
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** A new 32 byte secret from a CSPRNG, written atomically to noBackupFilesDir. Returns its hex form. */
private fun createSecret(context: Context): String {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val hex = bytes.joinToString("") { "%02x".format(it) }
    val dir = context.noBackupFilesDir
    val target = File(dir, LocalCommandChannelPrefs.SECRET_FILE)
    val tmp = File(dir, LocalCommandChannelPrefs.SECRET_FILE + ".tmp")
    tmp.writeText(hex)
    if (!tmp.renameTo(target)) {
        target.writeText(hex)
        tmp.delete()
    }
    return hex
}

/** SHA-256 digests of the signers of the installed viewer; empty when it is not installed. */
private fun viewerDigests(context: Context): List<String> {
    val info = context.packageManager.getPackageInfo(LocalCommandAuth.EXPECTED_VIEWER_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
    return info.signingInfo?.apkContentsSigners.orEmpty().map { signer ->
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Copy instead of typing 64 characters; marked sensitive, so Android 13+ shows no clipboard preview. */
private fun copySensitive(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("secret", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity       -> this
    is ContextWrapper -> baseContext.findActivity()
    else              -> null
}

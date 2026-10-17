package app.aaps.plugins.aps.iobaction

/**
 * Where the local command channel keeps its switches and its secret (fork, Spec v1.2 C2).
 *
 * Its own SharedPreferences namespace, NOT the app preferences: nothing here is in the settings
 * export, and no channel command can set any of it (R2-A4) - only the AAPS settings screen does.
 * All switches are off by default. The coordinator reads the same names (gatesReader).
 */
object LocalCommandChannelPrefs {

    const val PREFS = "local_command_channel"
    const val KEY_CHANNEL = "channel_enabled"
    const val KEY_TT = "tt_capability_enabled"
    const val KEY_IOBTH = "iobth_capability_enabled"
    const val KEY_AUTOSTATE = "autostate_capability_enabled"
    const val KEY_SMBRATIO = "smbratio_capability_enabled"
    const val KEY_WEIGHTS = "weights_capability_enabled"
    const val KEY_FORCED_VALIDATE = "forced_validate_only"

    /** Comma separated SHA-256 hex digests of the trusted signers, empty = deny everybody. */
    const val KEY_TRUSTED = "trusted_signer_sha256"
    const val KEY_UNAUTH_COUNT = "unauth_count"
    const val KEY_UNAUTH_LAST = "unauth_last_ms"

    /** The shared secret, hex, in noBackupFilesDir (never in a backup or an export). */
    const val SECRET_FILE = "local_command_secret"
}

package app.aaps.plugins.aps.iobaction

import java.security.MessageDigest

/**
 * Capability AUTOSTATE — Policy fuer das Setzen von AAPS-Automation-States ueber den Kanal.
 *
 * KEINE NAMENSLISTE. Welche States existieren und welche Werte sie annehmen duerfen, steht in
 * Tonis AAPS-Konfiguration, nicht in diesem Code — eine hier gepinnte Liste waere genau die Art
 * erfundener Zahl, die bei der IOBTH-Policy zweimal falsch war. Diese Policy prueft deshalb nur
 * die FORM (Laenge, ASCII-Zeichenvorrat, TTL-Bereich). Die inhaltliche Pruefung passiert zur
 * Laufzeit gegen die Definition des States selbst:
 *
 *   - der State muss existieren            -> AutomationStateInterface.hasStateValues(name)
 *   - der Wert muss einer SEINER Werte sein -> value in getStateValues(name)
 *
 * Das ist dieselbe Preference-Paritaet wie bei IOBTH: der Kanal darf exakt das, was der Regler
 * selbst kann. Legt Toni einen State an oder aendert dessen Wertliste, wandert die Grenze
 * automatisch mit, ohne Fork-Aenderung und ohne neue Kohorte.
 *
 * TTL ist kanal-eigene Substanz (ein Automation-State kennt keine Laufzeit): [30..720] min,
 * uebernommen aus [LocalCommandIobthPolicy] statt neu erfunden.
 *
 * Kanonische Form (UTF-8, keine Whitespaces):
 *   ["AUTOSTATE",[3,32],[1,32],[30,720,1],"runtime-valuelist"]
 */
object LocalCommandAutoStatePolicy {

    const val NAME_MIN_LEN = 3
    const val NAME_MAX_LEN = 32
    const val VALUE_MIN_LEN = 1
    const val VALUE_MAX_LEN = 32
    const val TTL_MIN = LocalCommandIobthPolicy.TTL_MIN
    const val TTL_MAX = LocalCommandIobthPolicy.TTL_MAX
    const val TTL_STEP = LocalCommandIobthPolicy.TTL_STEP

    /** ASCII-only: der kanonische String traegt Strings unescaped, Nicht-ASCII bricht den HMAC. */
    private val NAME_RE = Regex("^[A-Za-z][A-Za-z0-9_]{2,31}$")
    private val VALUE_RE = Regex("^[A-Za-z0-9_]{1,32}$")

    fun canonical(): String =
        "[\"AUTOSTATE\",[$NAME_MIN_LEN,$NAME_MAX_LEN],[$VALUE_MIN_LEN,$VALUE_MAX_LEN]," +
            "[$TTL_MIN,$TTL_MAX,$TTL_STEP],\"runtime-valuelist\"]"

    fun hash(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * Schema-Form von Name und Wert. Getrennt vom TTL-Bereich, weil die Fehlercodes getrennt
     * sind: eine kaputte Zeichenkette ist REJECTED_MALFORMED (Schema), ein TTL ausserhalb des
     * Bereichs ist REJECTED_BOUNDS (reject-not-clamp) — dieselbe Trennung wie bei IOBTH.
     */
    fun isIdentifierWellFormed(stateName: String, stateValue: String): Boolean =
        NAME_RE.matches(stateName) && VALUE_RE.matches(stateValue)

    fun isTtlAllowed(ttlMin: Int): Boolean =
        ttlMin in TTL_MIN..TTL_MAX && (ttlMin - TTL_MIN) % TTL_STEP == 0

    /** Vollstaendige FORM-Pruefung. Existenz des States und Gueltigkeit des Werts prueft der Service. */
    fun isWellFormed(stateName: String, stateValue: String, ttlMin: Int): Boolean =
        isIdentifierWellFormed(stateName, stateValue) && isTtlAllowed(ttlMin)
}

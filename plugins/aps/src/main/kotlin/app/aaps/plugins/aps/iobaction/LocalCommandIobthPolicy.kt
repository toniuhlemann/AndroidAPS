package app.aaps.plugins.aps.iobaction

import app.aaps.core.keys.IntKey
import java.security.MessageDigest

/**
 * Capability-Matrix A1 — IOBTH-Policy (Spec v1.1 §5 + R8-F7 "Policy-Hash PRO Capability").
 *
 * PREFERENCE-PARITAET statt erfundener Zahlen (Tonis Grundsatz 21.07., zweifach
 * nachgeschaerft): Erst war {60,70,80,90} gepinnt (seine INITIALE Button-Liste — Tuning,
 * nicht Substanz), dann eine 90er-Kappe (MEINE erfundene Zahl). Beides falsch. Die einzige
 * nicht-erfundene Wahrheit ueber diesen Regler ist seine bestehende Preference-Definition:
 * der Kanal darf EXAKT das, was der Regler selbst kann — Grenzen werden aus
 * [IntKey.ApsAutoIsfIobThPercent] ABGELEITET, nicht dupliziert. Aendert ein kuenftiger
 * Upstream-Merge die Preference-Grenzen, wandert der Policy-Hash mit = sichtbare neue
 * Kohorte (gewollt; der Test-Pin macht es laut).
 *
 * TTL bleibt kanal-eigene Substanz (der Regler kennt keine Laufzeit): [30..720] min
 * Schritt 1 — untere Grenze gegen Flatter-Leases, obere = 12h-Sicherheitsdeckel, aus
 * v1.1 §5 uebernommen und von R9-F3 als kanonische Bereichsform verlangt.
 *
 * Kanonische Form (UTF-8, keine Whitespaces): ["IOBTH",[min,max,1],[30,720,1]]
 */
object LocalCommandIobthPolicy {

    val PERCENT_MIN: Int get() = IntKey.ApsAutoIsfIobThPercent.min
    val PERCENT_MAX: Int get() = IntKey.ApsAutoIsfIobThPercent.max
    const val PERCENT_STEP = 1
    const val TTL_MIN = 30
    const val TTL_MAX = 720
    const val TTL_STEP = 1

    fun canonical(): String =
        """["IOBTH",[$PERCENT_MIN,$PERCENT_MAX,$PERCENT_STEP],[$TTL_MIN,$TTL_MAX,$TTL_STEP]]"""

    fun hash(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun isAllowed(percent: Int, ttlMin: Int): Boolean =
        percent in PERCENT_MIN..PERCENT_MAX && (percent - PERCENT_MIN) % PERCENT_STEP == 0 &&
            ttlMin in TTL_MIN..TTL_MAX && (ttlMin - TTL_MIN) % TTL_STEP == 0
}

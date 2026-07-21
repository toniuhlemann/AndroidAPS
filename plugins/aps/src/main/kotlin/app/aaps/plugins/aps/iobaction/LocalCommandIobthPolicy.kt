package app.aaps.plugins.aps.iobaction

import java.security.MessageDigest

/**
 * Capability-Matrix A1 — IOBTH-Policy (Spec v1.1 §5 + R8-F7 "Policy-Hash PRO Capability").
 *
 * BEREICH statt Werteliste (Tonis Einwand 21.07., konsequente TT-Incident-Lehre): Die
 * therapeutische SUBSTANZ ist der sichere BEREICH des Reglers, nicht die aktuellen
 * Rasterpunkte. Eine geschlossene Liste {60,70,80,90} haette jeden neuen Button (55/65)
 * und vor allem die kuenftige DynMeal-Leiter (Basis+Delta = beliebige Prozente) hinter
 * einen Fork-Flash gezwungen — exakt die Fehlerklasse des TT-Dauern-Incidents.
 *
 * Gepinnt sind daher NUR die Grenzen: percent [10..90] Schritt 1 (obere Grenze 90 =
 * bewusste Aggressions-Kappe: 100 hiesse Gate praktisch offen; untere 10 = IntKey-Minimum),
 * ttl [30..720] Schritt 1. Grenzen-Aenderung = neuer Hash = neue Kohorte; Werte INNERHALB
 * sind Tuning und brauchen nie einen Flash.
 *
 * Kanonische Form (UTF-8, keine Whitespaces): ["IOBTH",[10,90,1],[30,720,1]]
 */
object LocalCommandIobthPolicy {

    const val PERCENT_MIN = 10
    const val PERCENT_MAX = 90
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

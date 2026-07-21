package app.aaps.plugins.aps.iobaction

import java.security.MessageDigest

/**
 * Capability-Matrix A1 — IOBTH-Policy (Spec v1.1 §5 + R8-F7 "Policy-Hash PRO Capability"):
 * geschlossene Prozent-Liste + TTL-Bereich mit Schrittweite, hash-fixiert. Eine
 * WEIGHTS-/TT-Policy-Aenderung blockiert damit nie einen IOBTH-CLEAR (und umgekehrt).
 *
 * LEHRE aus dem TT-Policy-Incident (20.07. abends): die Prozent-Liste ist die
 * therapeutische SUBSTANZ (entspricht Tonis TH-Buttons 60/70/80/90 — live-config-
 * auditiert, nicht aus Defaults geraten); die TTL ist der Tuning-Rahmen und lebt als
 * BEREICH [30..720] Schritt 1 in der kanonischen Form (R10-F3: Min/Max/Schritt gehoeren
 * in die gehashte Policy). Aenderung der Liste = neuer Hash = neue Kohorte.
 *
 * Kanonische Form (UTF-8, keine Whitespaces): ["IOBTH",[60,70,80,90],[30,720,1]]
 */
object LocalCommandIobthPolicy {

    val PERCENTS: List<Int> = listOf(60, 70, 80, 90)
    const val TTL_MIN = 30
    const val TTL_MAX = 720
    const val TTL_STEP = 1

    fun canonical(): String =
        """["IOBTH",[${PERCENTS.sorted().joinToString(",")}],[$TTL_MIN,$TTL_MAX,$TTL_STEP]]"""

    fun hash(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun isAllowed(percent: Int, ttlMin: Int): Boolean =
        percent in PERCENTS && ttlMin in TTL_MIN..TTL_MAX && (ttlMin - TTL_MIN) % TTL_STEP == 0
}

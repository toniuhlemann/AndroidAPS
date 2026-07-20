package app.aaps.plugins.aps.iobaction

import java.security.MessageDigest

/**
 * LocalCommandChannel — Policy-Matrix (Spec v1.3 §6 / v1.4 §4, R4 quellcode-paritaetisch
 * abgenommen): geschlossene Positivliste der Auto-Executor-TT-Tupel. Kanonische Form exakt
 * nach R4 §6 (UTF-8, keine Whitespaces, aeussere JSON-Liste, Tupel [reason,target,duration],
 * sortiert Reason lexikographisch → Target numerisch → Dauer). Der SHA-256 der aktuellen
 * Liste wurde von Codex R4 unabhaengig berechnet und stimmt mit unserem Wert ueberein
 * (gemeinsamer Fork-/Viewer-Testvektor, im Unit-Test gepinnt).
 *
 * VOR dem Pilot: maschinell gegen den dann aktuellen TtConfig-Default regenerieren (R3/R4);
 * jede Tupel-Aenderung MUSS den Hash aendern → APPLIED blockiert bis zum AAPS-Update.
 */
object LocalCommandPolicy {

    /** (reasonKey, targetMgdl, durationMin) — Stand TtConfig-Default 20.07.2026 (R4 §6). */
    val TUPLES: List<Triple<LocalCommandProtocol.ReasonKey, Int, Int>> = listOf(
        Triple(LocalCommandProtocol.ReasonKey.BRAKE, 120, 15),
        Triple(LocalCommandProtocol.ReasonKey.BRAKE, 130, 15),
        Triple(LocalCommandProtocol.ReasonKey.BRAKE, 140, 15),
        Triple(LocalCommandProtocol.ReasonKey.CORRECTION, 90, 12),
        Triple(LocalCommandProtocol.ReasonKey.LOW_PROTECT, 101, 30),
        Triple(LocalCommandProtocol.ReasonKey.LOW_PROTECT, 141, 20),
        Triple(LocalCommandProtocol.ReasonKey.LOW_PROTECT, 161, 20),
        Triple(LocalCommandProtocol.ReasonKey.MEAL, 76, 5),
        Triple(LocalCommandProtocol.ReasonKey.MEAL, 88, 40),
        Triple(LocalCommandProtocol.ReasonKey.PEAK_STOP, 101, 30),
        Triple(LocalCommandProtocol.ReasonKey.REBOUND, 140, 15),
    )

    fun canonical(): String = TUPLES
        .sortedWith(compareBy({ it.first.name }, { it.second }, { it.third }))
        .joinToString(",", prefix = "[", postfix = "]") { (r, t, d) -> """["${r.name}",$t,$d]""" }

    fun hash(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun isAllowed(reason: LocalCommandProtocol.ReasonKey, targetMgdl: Int, durationMin: Int): Boolean =
        TUPLES.any { it.first == reason && it.second == targetMgdl && it.third == durationMin }
}

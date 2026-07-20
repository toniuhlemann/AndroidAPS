package app.aaps.plugins.aps.iobaction

import java.security.MessageDigest

/**
 * LocalCommandChannel — Policy-Matrix (Spec v1.3 §6 / v1.4 §4; Incident-Fix 20.07. abends):
 * geschlossene Positivliste der Auto-Executor-TT-PAARE (reason, target).
 *
 * WICHTIG (Live-Incident 20.07., erster ARMED-Abend): Die DAUER gehoert NICHT in die
 * Matrix. Dauern sind Live-Tuning-Knoepfe der Viewer-TtConfig (real verstellt: 90/12→10,
 * 76/5→10, 88/40→60) — die aus den Defaults gepinnten Dauern liessen den Kanal legitime
 * Engine-TTs terminal verwerfen (REJECTED_POLICY ohne NS-Fallback = Therapie-Luecke).
 * Die Dauer bleibt durch die Protokoll-Bounds [5..120] min begrenzt (parseAndVerify,
 * reject-not-clamp) und ist Teil der signierten Payload. Die therapeutische IDENTITAET
 * (welcher Reason darf welches Ziel) bleibt gepinnt — eine Ziel-Aenderung ist bewusst
 * ein neuer Policy-Hash (neue Kohorte, APPLIED blockiert bis zum AAPS-Update).
 *
 * Kanonische Form: aeussere JSON-Liste, Paare ["REASON",target], sortiert Reason
 * lexikographisch → Target numerisch, UTF-8, keine Whitespaces. Hash im Unit-Test gepinnt.
 */
object LocalCommandPolicy {

    /** (reasonKey, targetMgdl) — Ziel-Identitaet je Reason (tt-decision-rules, Stand 20.07.2026). */
    val PAIRS: List<Pair<LocalCommandProtocol.ReasonKey, Int>> = listOf(
        LocalCommandProtocol.ReasonKey.BRAKE to 120,
        LocalCommandProtocol.ReasonKey.BRAKE to 130,
        LocalCommandProtocol.ReasonKey.BRAKE to 140,
        LocalCommandProtocol.ReasonKey.CORRECTION to 90,
        LocalCommandProtocol.ReasonKey.LOW_PROTECT to 101,
        LocalCommandProtocol.ReasonKey.LOW_PROTECT to 141,
        LocalCommandProtocol.ReasonKey.LOW_PROTECT to 161,
        LocalCommandProtocol.ReasonKey.MEAL to 76,
        LocalCommandProtocol.ReasonKey.MEAL to 88,
        LocalCommandProtocol.ReasonKey.PEAK_STOP to 101,
        LocalCommandProtocol.ReasonKey.REBOUND to 140,
    )

    const val DURATION_MIN = 5
    const val DURATION_MAX = 120

    fun canonical(): String = PAIRS
        .sortedWith(compareBy({ it.first.name }, { it.second }))
        .joinToString(",", prefix = "[", postfix = "]") { (r, t) -> """["${r.name}",$t]""" }

    fun hash(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun isAllowed(reason: LocalCommandProtocol.ReasonKey, targetMgdl: Int, durationMin: Int): Boolean =
        PAIRS.any { it.first == reason && it.second == targetMgdl } &&
            durationMin in DURATION_MIN..DURATION_MAX
}

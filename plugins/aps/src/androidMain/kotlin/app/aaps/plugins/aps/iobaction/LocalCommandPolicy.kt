package app.aaps.plugins.aps.iobaction

import java.security.MessageDigest

/**
 * LocalCommandChannel — Policy-Matrix (Spec v1.3 §6 / v1.4 §4; Vollaudit 20.07. abends):
 * geschlossene Positivliste der Auto-Executor-TT-ZIELE.
 *
 * ENTWICKLUNG DER MATRIX (beide Live-Erkenntnisse vom ersten ARMED-Abend):
 *  1. Dauern raus (Incident TT90): Dauern sind Live-Tuning-Knoepfe der Viewer-TtConfig
 *     (90/12→10, 76/5→10, 88/40→60) — gepinnte Dauern verwarfen legitime Engine-TTs
 *     terminal (REJECTED_POLICY ohne NS-Fallback = Therapie-Luecke). Dauer bleibt durch
 *     die Protokoll-Bounds begrenzt und Teil der signierten Payload.
 *  2. Reasons raus (Vollaudit): Reason-Strings entstehen aus Titeln/Locks und kreuzen
 *     sich bei RENEWALS mit jedem aktiven Ziel (z.B. TT90-Renewal unter "Schutz:
 *     nahe-Low" → LOW_PROTECT(90); Brems-TT140 in der Nacht → LOW_PROTECT(140)) — das
 *     Kreuzprodukt ist nicht pinbar, und ein Reason ist ANZEIGE-Metadatum (Treatments),
 *     keine Sicherheitseigenschaft. Der Parser validiert ihn weiterhin als Enum.
 *
 * Die therapeutische Substanz — WELCHE Ziele der Kanal ueberhaupt setzen darf — bleibt
 * eine geschlossene, gepinnte Liste: eine Ziel-Aenderung ist bewusst ein neuer
 * Policy-Hash (neue Kohorte, APPLIED blockiert bis zum AAPS-Update).
 *
 * Kanonische Form: JSON-Liste der Ziele, numerisch aufsteigend, keine Whitespaces.
 * Hash im Unit-Test gepinnt.
 */
object LocalCommandPolicy {

    /** Erlaubte TT-Ziele (mg/dl) — Stand tt-decision-rules + Engine-Vollaudit 20.07.2026:
     *  72/74/78 manuelle L/M/S-Boost-Brücken · 76 Meal · 88 Welle · 90 Korrektur ·
     *  101 Soft-Protect/Peak-Stop · 120/130/140 Bremsen (140 auch Rebound) · 141/161 Hard-Protect. */
    val TARGETS: List<Int> = listOf(72, 74, 76, 78, 88, 90, 101, 120, 130, 140, 141, 161)

    const val DURATION_MIN = 5
    const val DURATION_MAX = 120

    fun canonical(): String = TARGETS.sorted().joinToString(",", prefix = "[", postfix = "]")

    fun hash(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun isAllowed(reason: LocalCommandProtocol.ReasonKey, targetMgdl: Int, durationMin: Int): Boolean =
        targetMgdl in TARGETS && durationMin in DURATION_MIN..DURATION_MAX
}

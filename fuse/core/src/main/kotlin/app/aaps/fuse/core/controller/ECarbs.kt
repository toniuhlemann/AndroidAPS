package app.aaps.fuse.core.controller

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DIE eCARBS DES BOLUSRECHNERS - Entwurf B (ein Entwurfsdokument ausserhalb des Repos), fuer die gemeinsame
 * Bilanz der Rechner-Mahlzeit ([MealBalance]).
 *
 * Die Knoepfe Kuchen und Pizza des AAPS-Rechners tragen einen Teil der KH als eCarbs ein: ein KH-Eintrag mit Dauer,
 * ohne Insulin. AAPS verlaesst sich darauf, dass der Loop sie abdeckt. FUSE hat bewusst keinen COB-Term im Praediktor
 * (`TrajectoryCore`, "KEIN COB-TERM") - die eCarbs bleiben deshalb AUSSERHALB des Kerns: sie zaehlen nur als
 * angekuendigter Rest in der Bilanz G, die den SMB-Weg und den langsamen Kanal begrenzt und die Basal-Ruecknahme ausloest.
 *
 * Was hier steht:
 *  - die Teile wie AAPS ([parts]) und was davon angekommen ist ([arrivedU], [arrivedEffectU]) - ein Teil zaehlt erst ab
 *    seinem Zeitpunkt, wie COB in AAPS;
 *  - die Aufnahmezuordnung ([CobAttribution]): der Rest wie COB mit Verfall und die Bestaetigung c_e aus derselben
 *    Zuordnung;
 *  - die Ruecknahme w ([withdrawal]): solange sich ein Ausbleiben nicht beurteilen laesst, gilt die Ankuendigung voll.
 *
 * Verworfene Varianten stehen nur im Entwurf, nicht im Code.
 */
object ECarbs {

    /** AAPS zerlegt eCarbs in Teile zu 15 min (`AppRepository.expandCarbs`). */
    const val PART_MIN = 15

    /** MODELLANNAHME: ein Teil wirkt linear ueber so viele Minuten ab seinem Zeitpunkt. Die Verteilung der eCarbs
     *  garantiert keine tatsaechliche Aufnahme (AAPS-Doku Extended Carbs). */
    const val ABSORPTION_MIN = 30.0

    /** Vor so vielen Minuten nach dem eCarbs-Beginn bestaetigt nichts - wie [EarlySupply.ADAPT_FROM_MIN]. */
    const val CONFIRM_FROM_MIN = EarlySupply.ADAPT_FROM_MIN

    /** Erst ab so viel erwarteter Wirkung der angekommenen Teile laesst sich ein Ausbleiben beurteilen [U-Aeq]. */
    const val JUDGE_FROM_U = 0.2

    /**
     * VERFALL DES RESTS (Entwurf B 17.8, Toni 05.11.: "Verfall des Rests offline bauen und pruefen") [min]: so lange nach
     * seinem Eintreffen zaehlt ein angekommener, nicht aufgenommener Teil als ausstehend - seine Aufnahmezeit plus die Zeit,
     * die eine Beurteilung braucht. Danach ist seine Aufnahme ueberfaellig. Keine neue Einstellung.
     */
    const val REST_LAPSE_MIN = ABSORPTION_MIN + CONFIRM_FROM_MIN

    private const val EPS = 1e-9

    /** Ein Teil der eCarbs: Zeitpunkt [ms] und Gramm. */
    data class Part(val ts: Long, val grams: Double)

    /**
     * DIE TEILE WIE AAPS (`AppRepository.expandCarbs`): k = Dauer / 15 (mindestens 1), Teil i bei [startTs] + 15 i,
     * Menge round(Rest / (k - i)) in ganzen Gramm. Bei ganzen Gramm ist die Summe genau [totalG]; Teile mit 0 g entfallen.
     */
    fun parts(totalG: Double, startTs: Long, durationMin: Int): List<Part> {
        if (!(totalG > 0.0) || !totalG.isFinite() || durationMin < 0) return emptyList()
        val ticks = max(1, durationMin / PART_MIN)
        var rest = totalG
        return (0 until ticks).mapNotNull { i ->
            val teil = (rest / (ticks - i)).roundToInt()
            rest -= teil
            if (teil == 0) null else Part(startTs + i * PART_MIN * 60_000L, teil.toDouble())
        }
    }

    /** Aufnahmeanteil eines Teils [xMin] Minuten nach seinem Zeitpunkt. */
    private fun k(xMin: Double, absorptionMin: Double): Double =
        if (absorptionMin <= EPS) (if (xMin > 0.0) 1.0 else 0.0) else min(1.0, max(0.0, xMin / absorptionMin))

    /** Bis [nowTs] erwartete Wirkung der ANGEKOMMENEN Teile [U-Aeq] - die Vergleichsgroesse der Bestaetigung. */
    fun arrivedEffectU(parts: List<Part>, icGPerU: Double, nowTs: Long, absorptionMin: Double = ABSORPTION_MIN): Double {
        if (!icGPerU.isFinite() || icGPerU <= 0.0) return 0.0
        return parts.filter { it.ts <= nowTs }.sumOf { it.grams / icGPerU * k((nowTs - it.ts) / 60_000.0, absorptionMin) }
    }

    /** Die angekommene eCarbs-Menge [U-Aeq] nach der Ankunft wie AAPS (Teile mit u_i <= jetzt, die volle Menge). */
    fun arrivedU(parts: List<Part>, icGPerU: Double, nowTs: Long): Double =
        if (!icGPerU.isFinite() || icGPerU <= 0.0) 0.0 else parts.filter { it.ts <= nowTs }.sumOf { it.grams } / icGPerU

    /**
     * DIE AUFNAHMEZUORDNUNG DES eCARBS-RESTS (Toni 05.11.: "Frueherer Aufnahmeueberschuss darf spaeter ankommende eCarbs
     * nicht automatisch vorab verbrauchen"). Je Zyklus wird nur der ZUWACHS des beobachteten Ueberschusses O - F_0
     * zugeordnet (O die beobachtete Mahlzeitwirkung, F_0 die Erwartung der Sofort-KH), und nur bis zur angekommenen Menge:
     * was darueber hinausgeht, verfaellt und wird nicht fuer kuenftige Teile angespart. Die zugeordnete Aufnahme sinkt nie
     * (wie der COB-Abbau). Ein reiner Wert ohne Nebenwirkung - der Aufrufer fuehrt ihn von Zyklus zu Zyklus.
     */
    data class CobAttribution(
        val absorbedU: Double = 0.0,
        /**
         * Die HOCHWASSERMARKE des Ueberschusses O - F_0 [U-Aeq] (Toni 05.11.): neu zugeordnet wird nur, was darueber
         * hinausgeht. Eine Messdelle mit anschliessender Rueckkehr bringt so nichts Neues - die Rueckkehr ist dieselbe
         * Aufnahme, kein zweites Mal -, und ein frueherer Sofort-KH-Ueberschuss setzt nur die Marke. `null` = noch keine
         * gueltige Beobachtung.
         */
        val maxExcessU: Double? = null,
        /** Die beim letzten Schritt angekommene Menge - nur sie konnte im Intervall seitdem aufgenommen werden. */
        val lastArrivedU: Double = 0.0,
    ) {

        /** Der naechste Stand bei Ueberschuss [excessNowU] (O - F_0) und angekommener Menge [arrivedU] [U-Aeq]. */
        fun step(excessNowU: Double, arrivedU: Double): CobAttribution {
            val angekommen = if (arrivedU.isFinite()) max(0.0, arrivedU) else 0.0
            val gueltig = excessNowU.isFinite()
            val zuwachs = if (gueltig) maxExcessU?.let { max(0.0, excessNowU - it) } ?: 0.0 else 0.0
            // Nie sinkend; zugeordnet hoechstens, was zu Beginn des Intervalls schon angekommen war - ein Teil, der
            // gerade erst ankommt, hatte noch keine Zeit zur Aufnahme. Der Ueberschuss darueber verfaellt.
            val aufgenommen = max(absorbedU, min(lastArrivedU, absorbedU + zuwachs))
            val marke = if (gueltig) max(maxExcessU ?: excessNowU, excessNowU) else maxExcessU
            return CobAttribution(aufgenommen, marke, angekommen)
        }

        /**
         * DER REST WIE COB MIT VERFALL (Entwurf B 17.8, Toni 05.11.) [U-Aeq]: angekommen minus zugeordnet, nie negativ -
         * aber ein angekommener Teil zaehlt [lapseMin] nach seinem Eintreffen nicht mehr als ausstehend, seine Aufnahme ist
         * dann ueberfaellig. Die zugeordnete Aufnahme fuellt zuerst die aelteren Teile (zuerst angekommen, zuerst
         * aufgenommen); was von ihnen offen bleibt, verfaellt. Kuenftige Teile zaehlen nicht.
         *
         * Bei halber Wirkung blieb die nie aufgenommene Haelfte sonst fuer immer "ausstehend" (17.7).
         */
        fun remainingWithLapseU(parts: List<Part>, icGPerU: Double, nowTs: Long, lapseMin: Double = REST_LAPSE_MIN): Double {
            if (!icGPerU.isFinite() || icGPerU <= 0.0) return 0.0
            var alt = 0.0
            var neu = 0.0
            for (p in parts) {
                if (p.ts > nowTs) continue
                val u = p.grams / icGPerU
                if (lapseMin.isFinite() && nowTs - p.ts >= lapseMin * 60_000.0) alt += u else neu += u
            }
            return max(0.0, neu - max(0.0, absorbedU - alt))
        }

        /**
         * DIE BESTAETIGUNG c_e AUS DERSELBEN ZUORDNUNG wie der Rest (Toni 05.11.: Restbestand und Bestaetigung
         * konsistent): zugeordnete Aufnahme / erwartete Wirkung der angekommenen Teile, erst wenn beurteilbar
         * ([judgeable]), hoechstens 1. Fruehere Sofort-KH-Abweichungen bestaetigen damit keine spaeteren eCarbs, und
         * eine Delle mit Rueckkehr bestaetigt nicht doppelt.
         */
        fun confirmation(expectedArrivedU: Double, minutesSinceStart: Double): Double =
            if (!judgeable(minutesSinceStart, expectedArrivedU)) 0.0 else min(1.0, max(0.0, absorbedU) / expectedArrivedU)
    }

    /** Laesst sich ein Ausbleiben schon beurteilen? */
    fun judgeable(minutesSinceStart: Double, expectedArrivedU: Double): Boolean =
        minutesSinceStart.isFinite() && minutesSinceStart >= CONFIRM_FROM_MIN &&
            expectedArrivedU.isFinite() && expectedArrivedU >= JUDGE_FROM_U

    /**
     * DIE RUECKNAHME w (0..1) des angekuendigten eCarbs-Rests in der Bilanz:
     *  - solange sich ein Ausbleiben nicht beurteilen laesst, gilt die Ankuendigung voll: w = 1;
     *  - danach folgt w der Bestaetigung: w = c_e.
     *
     * FESTGELEGT (Codex 4.4): Obergrenze ist der ANGEKUENDIGTE Umfang (w <= 1); die Ruecknahme ist NICHT unumkehrbar -
     * zeigt sich die Wirkung spaeter doch, steigt w wieder. Grund: Sensor-Dellen sind haeufig, eine unumkehrbare
     * Ruecknahme schnitte auf einen Ausreisser hin die ganze Mahlzeit ab.
     */
    fun withdrawal(confirmation: Double, minutesSinceStart: Double, expectedArrivedU: Double): Double =
        if (!judgeable(minutesSinceStart, expectedArrivedU)) 1.0 else min(1.0, max(0.0, confirmation))
}

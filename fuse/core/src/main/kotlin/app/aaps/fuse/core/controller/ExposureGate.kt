package app.aaps.fuse.core.controller

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * DIE VERBINDLICHE ENDPRUEFUNG (Bauauftrag 5.1, Schritt B1 - Toni 29.08.).
 *
 * Die LETZTE HEBENDE MENGENSTUFE des Zyklus prueft die endgueltige
 * Zusatzdosis gegen den gemeinsamen kontextabhaengigen Expositionsraum:
 *
 *     effectiveLimit = min(iobTH, maxIOB, contextExposureLimit)
 *     headroom       = max(0, effectiveLimit - capIob - Transporthaftung)
 *
 * mit contextExposureLimit = MealExposureLimit unter gueltiger
 * MEAL-Vollmacht, sonst CorrectionExposureLimit. Sie sitzt an ZWEI
 * Einbaustellen - Hauptpfad NACH dem Liveness-Merge und Fallback-Pfad
 * NACH dessen MeasuredDescentGate - und nach ihr veraendert KEINE Stufe
 * die Menge mehr nach oben (MarkerFloor laeuft nie erneut; danach nur
 * Reduzierer). Anlass: der Korrektur-Burst vom 27.08. (2,50 U in 12
 * tailHeadroom-Zyklen, waehrend iobTH/maxIOB 4-6 U Luft liessen - eine
 * kontextabhaengige Grenze existierte im Normalpfad nicht).
 *
 * VERTRAGSPUNKTE:
 *  - REINE MENGENPRUEFUNG: kein erneuter Guard-/Tail-/finalVeto-Lauf auf
 *    der gemergten Menge - sonst kehrte der Saegezahn zurueck, den der
 *    Liveness-Kanal per Vertrag umgeht.
 *  - Die Grenze ist eine ABSOLUTE Mengengrenze (Block EXPOSURE_LIMIT,
 *    MarkerAuthorization.lifts = false, TbrAction NO_NEW_POSITIVE,
 *    GUARD_CHAIN_PASSED ja, UNSAFE nein - Invariante 7: erzeugt nie
 *    eigenstaendig Zero-TBR).
 *  - TEILKAPPUNG senkt die Menge und benennt die Grenze; der publizierte
 *    Grant bleibt dennoch konsistent, weil [AuthorizedLift] denselben
 *    Kontext-Headroom bereits in der GRANT-BILDUNG traegt - ein Grant
 *    entsteht nie oberhalb des Raums, die Endpruefung faengt nur noch
 *    stufenuebergreifende Hebungen (SubStep, DeferredPrime-Release,
 *    Liveness). Der nicht angeforderte autorisierte Rest bleibt in
 *    Huelle/Upfront-Bilanz ABRECHENBAR offen (verschieben, nie
 *    verwerfen - die Buchung laeuft ohnehin auf actuatedU).
 *  - Rasterung NACH UNTEN aufs Pumpenraster - die Endpruefung rundet nie
 *    auf.
 *
 * Seit dem CENTRAL-only-Cleanup (v44) laeuft sie in JEDEM Zyklus - die
 * zentrale Dosierpolitik ist die einzige.
 */
object ExposureGate {

    private const val TICK_EPS = 1e-9

    /**
     * DIE TYPISIERTE QUELLEN-PROVENIENZ der Endmenge (Bauauftrag 7.3) -
     * identisch fuer Haupt- und Fallbackpfad, an den hebenden Stufen
     * TYPISIERT gesetzt, nie aus Texten geraten. Die vier Nutzerlabels
     * NORMAL/LIVENESS/MEAL_UPFRONT/FOUNDATION behalten ihre Untertypen
     * (PRIME, SubStep-Uebertrag, Aufschub-Freigabe, Ruhe-Pfade).
     */
    enum class FinalSource {
        NONE, NORMAL, NORMAL_SUBSTEP, PRIME, FOUNDATION, MEAL_UPFRONT,
        DEFERRED_RELEASE, LIVENESS, CALM_BATCH, CALM_DEMAND,
    }

    data class Result(
        /** Die gekappte Endmenge [U] - hoechstens die Anforderung. */
        val cappedU: Double,
        /** Die Grenze hat die Anforderung real gesenkt. */
        val bindet: Boolean,
        /** Anforderung > 0 vollstaendig genullt -> Block EXPOSURE_LIMIT. */
        val blocked: Boolean,
        /** min(iobTH, maxIOB, Kontextgrenze). */
        val effectiveLimitU: Double,
        /** Die Kontextgrenze dieses Zyklus (MEAL oder CORRECTION). */
        val contextLimitU: Double,
        /** max(0, effectiveLimit - capIob - transport), ungerastert. */
        val headroomU: Double,
        /** Name der bindenden Grenze fuer bindingLimit/Export. */
        val binding: String,
    )

    fun pruefe(
        requestedU: Double,
        mealAuthorized: Boolean,
        correctionLimitU: Double,
        mealLimitU: Double,
        iobThU: Double,
        maxIobU: Double,
        capIobU: Double,
        transportU: Double,
        pumpIncrementU: Double,
    ): Result {
        val kontext = if (mealAuthorized) mealLimitU else correctionLimitU
        val kontextName = if (mealAuthorized) "mealExposureLimit" else "correctionExposureLimit"
        val grenzen = listOf(
            kontextName to kontext,
            "iobThHeadroom" to iobThU,
            "maxIobHeadroom" to maxIobU,
        )
        val (name, grenze) = grenzen.minByOrNull { it.second }!!
        // Dieselbe Belegungs-Semantik wie die Dosier-Headrooms (A2):
        // Grenze - capIob - transport, dann der Boden bei 0.
        val headroom = max(0.0, grenze - capIobU - transportU)
        // Dieselbe tickEps-Rasterung wie AuthorizedLift - ohne sie macht
        // die Gleitkomma-Darstellung aus 0,30/0,05 ein floor(5,999...) = 5.
        val erlaubt =
            if (pumpIncrementU > 0.0) floor(headroom / pumpIncrementU + TICK_EPS) * pumpIncrementU
            else headroom
        val capped = min(requestedU, erlaubt)
        val bindet = capped < requestedU - 1e-12
        return Result(
            cappedU = max(0.0, capped),
            bindet = bindet,
            blocked = bindet && capped <= 1e-12 && requestedU > 0.0,
            effectiveLimitU = grenze,
            contextLimitU = kontext,
            headroomU = headroom,
            binding = name,
        )
    }
}

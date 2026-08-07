package app.aaps.fuse.core.controller

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Die Sofort-Freigabe am Mahlzeiten-Marker - verteilte Abgabe AB KNOPFDRUCK,
 * ohne auf CGM-Evidenz zu warten.
 *
 * WARUM ES SIE GIBT, gemessen am 07.08.2026: Essen 08:50 (Marker), CGM-Regung
 * 09:09, Peak 256 um ~09:50, danach reale Hypo < 70 - AUCH auf prod mit
 * Marker und TT74. Die ~19 Minuten zwischen Essen und CGM holt kein Schaetzer;
 * Insulin ab 08:50 wirkt maximal um ~09:35-09:45, also AM Peak statt danach.
 *
 * WAS SIE IST: eine BEZIFFERTE, RUECKHOLBARE Wette. Die Huelle ist nach dem
 * Reversibilitaetsmass KC2-53 bemessen: per Basal-Null sind an Tonis Profil
 * 0,9-1,4 U ueber 120 min zurueckholbar - Default 1,2 U. Ein Fehl-Marker ohne
 * Mahlzeit ist damit durch Zurueckhalten vollstaendig korrigierbar, WENN die
 * Wache frueh dreht. Das unterscheidet sie von tim2000s' Prime-Boli, die
 * Toni am Boost-Port bewusst NICHT uebernommen hatte: dort ungedeckelt am
 * Signal, hier gedeckelt an der gemessenen Rueckholkapazitaet. Die alte Linie
 * wurde am 07.08. von Toni selbst neu gezogen ("die Insulinabgabe koennte
 * frueher beginnen sobald das Fruehstueck startet oder zumindest eine Art
 * Freigabe").
 *
 * WAS SIE NICHT DARF, und zwar strukturell statt per Disziplin:
 *  - Sie HEBT nur eine Entscheidung an, deren einzige Schwaeche fehlender
 *    BEDARF war ([FuseController.Block.NONE], NO_DEMAND, BELOW_PUMP_INCREMENT).
 *    Jede echte Sperre - GUARD_FLOOR, TAIL, IOB_TH, MAX_IOB, HEALTH, SAFETY,
 *    PUMP_BUSY, HORIZON_MISSING - gewinnt unveraendert.
 *  - Sie respektiert dieselben Mengen-Deckel wie jede andere Freigabe
 *    (maxSmb, iobTH-Spielraum, maxIOB-Spielraum).
 *  - CLEARANCE-Gate: die Guardbahn muss den Boden um die 60-min-Wirkung der
 *    RESTLICHEN Huelle ueberragen (Anteil [CLEARANCE_60MIN_FRACTION] bei
 *    DIA 9). Das ist die ehrliche Ersatzpruefung, solange CandidateSearch
 *    nicht verdrahtet ist - konservativ genug fuer die Nacht (Bahn nahe am
 *    Boden -> gesperrt), durchlaessig am Mahlzeitenbeginn (Bahn ~100 bei
 *    Boden 70 -> offen).
 *  - Verteilung statt Klumpen: der Rest der Huelle wird gleichmaessig ueber
 *    das restliche Fenster gestreckt. JEDE im Fenster gelieferte Einheit
 *    zaehlt gegen die Huelle - auch evidenzgetriebene; sobald die Mahlzeit
 *    im CGM ankommt und der normale Pfad mehr fordert, ist die Wette
 *    beendet, nicht verdoppelt.
 */
object PrimeRelease {

    /** Abgabefenster ab Knopfdruck [min]. 15 statt laenger: die Wette gilt
     *  dem CGM-blinden Kopf der Mahlzeit; danach traegt Evidenz oder nichts. */
    const val WINDOW_MIN = 15

    /** Realisierter Wirkanteil einer Dosis nach 60 min bei DIA 9 (~20 %,
     *  aus dem Einheitskern vermessen). Das Clearance-Gate rechnet damit:
     *  minLower - Anteil*restU*ISF >= guardFloor. */
    const val CLEARANCE_60MIN_FRACTION = 0.2

    private const val TICK_EPS = 1e-9

    data class Input(
        val enabled: Boolean,
        val mealMarkerActive: Boolean,
        /** Zeitpunkt des Knopfdrucks [ms] - Fensteranker. */
        val armedTsMs: Long,
        val nowMs: Long,
        val envelopeU: Double,
        val spentU: Double,
        val minLowerMgdl: Double,
        val guardFloorMgdl: Double,
        val isfMgdlPerU: Double,
        val pumpIncrementU: Double,
    )

    data class Plan(
        val active: Boolean,
        /** Mindest-Freigabe dieses Zyklus [U], auf Pumpenschritte gerundet. */
        val floorU: Double,
        val remainingU: Double,
        val reason: String,
    )

    fun plan(input: Input): Plan {
        val remaining = max(0.0, input.envelopeU - input.spentU)
        fun off(reason: String) = Plan(false, 0.0, remaining, reason)

        if (!input.enabled) return off("DISABLED")
        if (!input.mealMarkerActive || input.armedTsMs <= 0) return off("NO_MARKER")
        val ageMin = (input.nowMs - input.armedTsMs) / 60_000.0
        if (ageMin < 0.0) return off("CLOCK_SKEW")
        if (ageMin >= WINDOW_MIN) return off("WINDOW_OVER")
        if (!input.minLowerMgdl.isFinite() || !input.isfMgdlPerU.isFinite() || input.isfMgdlPerU <= 0.0)
            return off("NOT_FINITE")
        if (input.pumpIncrementU <= 0.0 || !input.pumpIncrementU.isFinite()) return off("NO_PUMP_STEP")
        if (remaining < input.pumpIncrementU) return off("ENVELOPE_SPENT")

        // Clearance: die 60-min-Wirkung des RESTES darf die Guardbahn nicht
        // unter den Boden druecken. Konservativ gegen den Rest, nicht gegen
        // die Einzeldosis - die Wette wird als Ganzes bewertet.
        val clearance = CLEARANCE_60MIN_FRACTION * remaining * input.isfMgdlPerU
        if (input.minLowerMgdl - clearance < input.guardFloorMgdl) return off("CLEARANCE")

        // Gleichmaessig ueber das Restfenster; mindestens ein Pumpenschritt,
        // sonst schoebe die Rundung alles ans Fensterende.
        val minutesLeft = max(1.0, WINDOW_MIN - ageMin)
        val target = remaining / minutesLeft
        val stepped = floor(target / input.pumpIncrementU + TICK_EPS) * input.pumpIncrementU
        val floorU = min(remaining, max(input.pumpIncrementU, stepped))
        return Plan(true, floorU, remaining, "PRIME")
    }

    /** Bloecke, die die Freigabe anheben darf: hier fehlte nur BEDARF, keine
     *  Sicherheit. */
    private val LIFTABLE = setOf(
        FuseController.Block.NONE,
        FuseController.Block.NO_DEMAND,
        FuseController.Block.BELOW_PUMP_INCREMENT,
    )

    /**
     * Hebt die Basisentscheidung auf die Mindest-Freigabe an - oder laesst sie
     * unveraendert, wenn sie ohnehin groesser ist oder eine echte Sperre
     * traegt. Kappt zusaetzlich an denselben Deckeln wie der Regler.
     */
    fun lift(base: FuseController.Decision, p: Plan, state: FuseController.State): FuseController.Decision {
        if (!p.active || p.floorU <= 0.0) return base
        if (base.block !in LIFTABLE) return base

        val caps = min(
            min(state.maxSmbU, p.remainingU),
            min(state.maxIobU - state.netIobU, state.iobThU - state.capIobU),
        )
        val stepped = floor(min(p.floorU, caps) / state.pumpIncrementU + TICK_EPS) * state.pumpIncrementU
        if (stepped < state.pumpIncrementU || stepped <= base.smbU) return base

        return base.copy(
            smbU = stepped,
            block = FuseController.Block.NONE,
            bindingLimit = "primeRelease",
        )
    }
}

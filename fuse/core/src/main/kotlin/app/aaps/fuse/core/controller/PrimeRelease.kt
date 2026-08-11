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
 *    WELCHE Guardbahn, seit C1/C2 (Codex-Adjudication H1/H2, K2 Punkte 6/8):
 *    die pessimistischste ueber ALLE glaubwuerdigen Bahnen - Haupt UND Bremse -
 *    und PRIOR-FREI. S. [Input.safetyMinLowerMgdl].
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

    /**
     * Absolute Wanduhr-Kappe ab Knopfdruck [min].
     *
     * GEMESSEN 09.08. (Schoko-Muesli, L-Marker): der Knopfdruck um 10:46 fiel
     * in ein CLEARANCE-Nein, das 15 Minuten lang stand - danach war das
     * Fenster VORBEI und die gesamte 2,00-U-Huelle verfallen, ohne dass sie
     * je erteilbar gewesen waere. Eine Freigabe, die abgelaufen ist, weil sie
     * nie erteilt werden KONNTE, ist keine Sicherheitsentscheidung, sondern
     * ein Buchungsfehler.
     *
     * Deshalb zaehlt das Fenster LIEFERBARE Minuten: solange die Clearance
     * sperrt, schiebt der Aufrufer den Fensterstart nach. Ohne eine zweite,
     * absolute Grenze liefe die Wette aber beliebig lange - und ihre
     * Begruendung ist ausdruecklich der CGM-BLINDE KOPF der Mahlzeit. Nach
     * dieser Kappe traegt Evidenz oder nichts, so wie vorher auch.
     *
     * 45 min = dieselbe Groesse wie das Marker-Boost-Fenster.
     */
    const val WALL_CEILING_MIN = 45

    /** Realisierter Wirkanteil einer Dosis nach 60 min bei DIA 9 (~20 %,
     *  aus dem Einheitskern vermessen). Das Clearance-Gate rechnet damit:
     *  minLower - Anteil*restU*ISF >= guardFloor. */
    const val CLEARANCE_60MIN_FRACTION = 0.2

    /**
     * MARKER-PRIOR (08.08., Antwort auf Tonis Vorrang-Frage): Der Knopf ist
     * eine bewusste ERKLAERUNG "Kohlenhydrate kommen" - FUSE ist COB-blind,
     * seine Waechterbahn rechnet also das Worst-Case "es kommen KEINE Carbs",
     * das im Marker-Fenster nachweislich falsch ist (Abendessen 07.08.:
     * 58 min GUARD_FLOOR + 12 min CLEARANCE gegen die deklarierte Mahlzeit,
     * Kopf 33 min zu spaet). Der Prior schreibt der UNTEREN Bahn einen
     * minimalen deklarierten Carb-Antrieb gut (~10-15 g ueber 45 min bei
     * ISF 80) - Gates bleiben souveraen, rechnen aber mit korrekten Fakten.
     * Gekappt an der Mittelbahn (lower <= mean bleibt erhalten).
     *
     * ENTZIRKULARISIERT SEIT C2 (Codex H2 "a marker may create demand evidence,
     * not protection"): der Prior hebt weiterhin die ANGEZEIGTE untere Bahn,
     * aber KEIN Sicherheitszertifikat rechnet mehr gegen die gehobene Kante -
     * Guard, Clearance und Schwanz nehmen die punktweise prior-freie Zwillings-
     * bahn ([app.aaps.fuse.core.predictor.PredictorResult.minSafetyLowerBg]).
     * Die frueher hier angesetzte ANALYTISCHE Korrektur (Hub am Release-
     * Horizont abziehen) war unvollstaendig: das Minimum liegt typisch am
     * Haftungshorizont, und dort ist der Hub bei tau 60 fast doppelt so gross
     * (30 min 16,53 mg/dl gegen 120 min 36,32 mg/dl).
     */
    const val MARKER_PRIOR_MGDL_PER_MIN = 0.7

    private const val TICK_EPS = 1e-9

    data class Input(
        val enabled: Boolean,
        val mealMarkerActive: Boolean,
        /** Zeitpunkt des Knopfdrucks [ms] - Anker der WANDUHR-Kappe. */
        val armedTsMs: Long,
        /**
         * Beginn des LIEFERBAREN Fensters [ms]; `<= armedTsMs` heisst "noch
         * nie gesperrt gewesen", dann gilt der Knopfdruck.
         *
         * Der Aufrufer schiebt diesen Stempel vor, solange die Clearance
         * sperrt - s. [WALL_CEILING_MIN]. Er darf NIE hinter [armedTsMs]
         * zurueckfallen und wird durch die Wanduhr-Kappe begrenzt.
         */
        val windowStartTsMs: Long,
        val nowMs: Long,
        val envelopeU: Double,
        val spentU: Double,
        /**
         * Die Bahn, gegen die die Clearance rechnet [mg/dl] - BEREITS
         * MINIMIERT vom Aufrufer (C1/C2).
         *
         * Der Name traegt die Auflage: hier gehoert das punktweise Minimum
         * ueber ALLE glaubwuerdigen Bahnen hinein (Haupt- UND Bremsbahn), je
         * Bahn die PRIOR-FREIE Kante - im Runner
         * `minSafetyLowerOf(prediction, restraint)`. Frueher hiess das Feld
         * `minLowerMgdl` und bekam die Hauptbahn samt Marker-Prior; beides war
         * zu guenstig (Codex C1/C2).
         *
         * Die Minimierung steht bewusst beim Aufrufer und nicht hier: nur er
         * weiss, welche Bahnen dieser Zyklus ueberhaupt hat.
         */
        val safetyMinLowerMgdl: Double,
        val guardFloorMgdl: Double,
        val isfMgdlPerU: Double,
        val pumpIncrementU: Double,
        /** Der Marker autorisiert Insulin trotz gemessenem Tief - dann
         *  entfaellt die Freigangsprobe gegen den Guard-Boden. Der Aufrufer
         *  hat geprueft, dass der Hold aus dem TIEF stammt. */
        val markerAuthorisesLow: Boolean = false,
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
        val wallAgeMin = (input.nowMs - input.armedTsMs) / 60_000.0
        if (wallAgeMin < 0.0) return off("CLOCK_SKEW")
        if (wallAgeMin >= WALL_CEILING_MIN) return off("WINDOW_OVER_WALL")
        // Der spaetere der beiden Anker: ein zurueckgefallener Stempel (Uhr,
        // beschaedigter Zustand) darf das Fenster nicht verlaengern.
        val start = max(input.armedTsMs.toDouble(), input.windowStartTsMs.toDouble())
        val ageMin = (input.nowMs - start) / 60_000.0
        if (ageMin < 0.0) return off("CLOCK_SKEW")
        if (ageMin >= WINDOW_MIN) return off("WINDOW_OVER")
        if (!input.safetyMinLowerMgdl.isFinite() || !input.isfMgdlPerU.isFinite() || input.isfMgdlPerU <= 0.0)
            return off("NOT_FINITE")
        if (input.pumpIncrementU <= 0.0 || !input.pumpIncrementU.isFinite()) return off("NO_PUMP_STEP")
        // TICKZAHL statt `<`: `remaining` ist eine Differenz aufsummierter
        // Tickvielfacher, und die trifft die Huelle nicht exakt. Gemessen bei
        // Huelle 0,8: spent 0,7500000000000001, remaining 0,04999999999999993 -
        // ein voller Schritt, der als "verbraucht" durchfiel. Die Richtung war
        // konservativ (es wurde zurueckgehalten), aber es war ein stiller
        // Dosisverlust, keine Telemetriefrage.
        if (FuseController.ticksOf(remaining, input.pumpIncrementU) < 1) return off("ENVELOPE_SPENT")

        // Gleichmaessig ueber das Restfenster; mindestens ein Pumpenschritt,
        // sonst schoebe die Rundung alles ans Fensterende.
        val minutesLeft = max(1.0, WINDOW_MIN - ageMin)
        val target = remaining / minutesLeft
        val stepped = floor(target / input.pumpIncrementU + TICK_EPS) * input.pumpIncrementU
        val floorU = min(remaining, max(input.pumpIncrementU, stepped))

        // CLEARANCE gegen den ZYKLUS-ANTEIL, nicht gegen die ganze Huelle
        // (Tonis Entscheidung 09.08. nach dem gemessenen Fall).
        //
        // WAS SCHIEF WAR: das Tor verlangte Reserve fuer die gesamte
        // Resthuelle. Bei einer Huelle von 2,0 U sind das 0,2 x 2,0 x 90 = 36 mg/dl, also
        // minLower >= 106 bei einem BG von 141 - die SCHNELLSTE Mahlzeit bekam
        // damit den SCHWERSTEN Start, obwohl sie den Vorschuss am dringendsten
        // braucht. Am 09.08. fehlten so 4 mg/dl und die komplette Huelle
        // verfiel ungenutzt.
        //
        // WAS DIE AUSSAGE JETZT IST, ehrlich benannt: gedeckt ist die Dosis
        // DIESES Zyklus, nicht mehr die Wette als Ganzes. Das ist schwaecher.
        // Was es traegt: die Pruefung laeuft JEDE Minute neu und gegen die
        // dann aktuelle Bahn - in der die vorherigen Teildosen bereits als IOB
        // stecken. Die Huelle wird also nicht auf einmal riskiert, sondern
        // Schritt fuer Schritt gegen eine jedesmal neu gemessene Lage; sobald
        // die Bahn faellt, endet die Serie sofort. Zusaetzlich bleiben
        // Wanduhr-Kappe, Huelle, maxSmb, iobTH und maxIOB unveraendert
        // (s. [lift]), und der Schwanz-Waechter kappt weiterhin unabhaengig.
        // Die Freigangsprobe gegen den Guard-Boden. Sie ENTFAELLT, wenn der
        // Marker das Tief ausdruecklich autorisiert - sonst waere die
        // Freigabe dort schon hier tot, und die Lockerung der beiden Bloecke
        // oben bliebe wirkungslos. Genau diese Falle (ein Tor geoeffnet, das
        // naechste uebernimmt) ist in dieser Reihe schon zweimal
        // zugeschnappt.
        val clearance = CLEARANCE_60MIN_FRACTION * floorU * input.isfMgdlPerU
        if (!input.markerAuthorisesLow &&
            input.safetyMinLowerMgdl - clearance < input.guardFloorMgdl
        ) return off("CLEARANCE")

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
     * Zusaetzlich hebbar, wenn der Marker Insulin bei gemessenem Tief
     * autorisiert (Tonis Entscheidung 11.08., Einstellung
     * `MarkerAuthorisesLow`).
     *
     * Nur diese beiden, und beide nur wegen des TIEFS: `SAFETY_HOLD` traegt
     * heute ausschliesslich `SafetyReason.LOW`, und `GUARD_FLOOR` ist
     * derselbe Befund eine Ebene tiefer. Alles Uebrige - Signalfehler,
     * unbekanntes IOB, Ledger-Hold, Pumpe, Schwanz - bleibt hart und steht
     * bewusst NICHT hier.
     *
     * Der Aufrufer muss zusaetzlich pruefen, dass der Hold wirklich aus dem
     * TIEF stammt: kaeme je ein zweiter `SafetyReason` dazu, wuerde er sonst
     * stillschweigend miterlaubt.
     */
    private val LIFTABLE_ON_LOW = LIFTABLE + setOf(
        FuseController.Block.SAFETY_HOLD,
        FuseController.Block.GUARD_FLOOR,
    )

    /**
     * Hebt die Basisentscheidung auf die Mindest-Freigabe an - oder laesst sie
     * unveraendert, wenn sie ohnehin groesser ist oder eine echte Sperre
     * traegt. Kappt zusaetzlich an denselben Deckeln wie der Regler.
     *
     * Audit R95 (NEU-04): die Zusage "dieselben Deckel wie der Regler" war
     * unvollstaendig - tailHeadroom und onsetEnvelope fehlten, ein
     * tail-gedeckelter Basis-SMB konnte per Lift ueber den Schwanz-Deckel
     * gehoben werden. Beide sind jetzt Pflicht-Kappen, wenn der Aufrufer sie
     * kennt (null = Waechter nicht aktiv).
     */
    fun lift(
        base: FuseController.Decision, p: Plan, state: FuseController.State,
        /** s. [LIFTABLE_ON_LOW]. Der Aufrufer hat bereits geprueft, dass der
         *  Hold aus dem TIEF stammt. */
        markerAuthorisesLow: Boolean = false,
        tailHeadroomU: Double? = null, onsetCapU: Double? = null,
        // Fix-Pass 2 Nr. 2 (NEU-BS-01, doppelt unabhaengig gefunden): die
        // Suche kappt an LEDGER-korrigierten Headrooms, der Lift kappte an
        // den NOMINALEN - ueber den NO_DEMAND-Pfad (Suche laeuft nie) konnte
        // eine In-Flight-Menge doppelt in den iobTH-/maxIOB-Spielraum.
        transportCommitmentU: Double = 0.0,
    ): FuseController.Decision {
        if (!p.active || p.floorU <= 0.0) return base
        if (base.block !in (if (markerAuthorisesLow) LIFTABLE_ON_LOW else LIFTABLE)) return base

        var caps = min(
            min(state.maxSmbU, p.remainingU),
            // Tonis IOB-Referenz-Regel: Dosier-Grenzen rechnen mit capIob,
            // nie mit net - zurueckgehaltenes Basal ist kein SMB-Budget.
            min(
                state.maxIobU - state.capIobU - transportCommitmentU,
                state.iobThU - state.capIobU - transportCommitmentU,
            ),
        )
        tailHeadroomU?.let { caps = min(caps, it) }
        onsetCapU?.let { caps = min(caps, it) }
        val stepped = floor(min(p.floorU, caps) / state.pumpIncrementU + TICK_EPS) * state.pumpIncrementU
        if (stepped < state.pumpIncrementU || stepped <= base.smbU) return base

        return base.copy(
            smbU = stepped,
            block = FuseController.Block.NONE,
            bindingLimit = "primeRelease",
            // S0: die Basiskappen haben diese Menge nicht bestimmt. Leere
            // Liste mit eigener Stufe sagt das - eine stehengebliebene
            // Basisliste behauptete das Gegenteil.
            caps = emptyList(),
            capsStage = FuseController.STAGE_PRIME,
            // Die Herkunft, nicht der Betrag: nur wenn der Aufrufer ein
            // GEMESSENES Tief festgestellt und die Einstellung sie autorisiert
            // hat, traegt diese Menge eine manuelle Autorisierung. Sonst 0 -
            // ein gewoehnlicher Prime-Release bleibt von jedem Schutz-Null
            // vollstaendig gedeckelt.
            markerLowAuthorizedU = if (markerAuthorisesLow) stepped else 0.0,
        )
    }
}

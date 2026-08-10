package app.aaps.fuse.core.controller

import kotlin.math.exp

/**
 * Wirkungsbereich der Marker-SONDERRECHTE (Audit R95, NEU-01/NEU-02;
 * Tonis Entscheid 08.08. nachmittags: "bis zur Wende, max 45 min").
 *
 * Der Marker traegt zwei getrennte Dinge:
 *  1. KONTEXT (Anzeige, Freigabe-Huelle des Prime-Fensters, Onset-Evidenz):
 *     gilt weiter fuer das volle MARKER_WINDOW von 90 min.
 *  2. SONDERRECHTE (Rebound-Entwaffnung, Marker-Prior auf der unteren Bahn,
 *     Marker-Zweig des Mahlzeit-Fensters): die decken sonst nachweislich die
 *     Post-Peak-Hypo-Phase ab - beim Fruehstueckstest 08.08. fiel der Sturz
 *     auf 117 bei 1,6 U IOB noch in die 90 Marker-Minuten, waehrend alle
 *     drei Nacht-Bremsen entwaffnet und der Guard-Boden angehoben waren.
 *
 * Sonderrechte enden deshalb mit der NACHHALTIGEN WENDE (ukf unter die
 * negative Rampen-Unterkante - dieselbe Kante, die das Mahlzeit-Fenster
 * schliesst) und spaetestens nach [BOOST_MAX_MIN] Minuten. Der Start-ins-
 * Tief-Nutzen (Entwaffnung beim Essensbeginn) bleibt damit voll erhalten.
 */
object MarkerScope {

    /** Harte Obergrenze der Sonderrechte ab Marker-Druck. PROVISORISCH bis
     *  zur Messung mehrerer Marker-Mahlzeiten. */
    const val BOOST_MAX_MIN = 45

    /**
     * @param markerTs Zeitpunkt des Marker-Drucks (0 = kein Marker)
     * @param nowTs aktueller Anker
     * @param turnLatchedTs Zeitpunkt der ersten nachhaltigen Wende NACH dem
     *  Druck (0 = noch keine); der Aufrufer latcht ihn je Marker-Episode.
     */
    fun boostActive(markerTs: Long, nowTs: Long, turnLatchedTs: Long, boostMaxMin: Int = BOOST_MAX_MIN): Boolean {
        if (markerTs <= 0L || nowTs < markerTs) return false
        if (boostMaxMin <= 0) return false
        if (turnLatchedTs in 1..nowTs) return false
        return nowTs - markerTs <= boostMaxMin * 60_000L
    }

    /**
     * Entzirkularisierung der Prime-Clearance (NEU-01): der Prior hebt die
     * untere Bahn, und dieselbe gehobene Bahn diente der Clearance-Pruefung
     * der Prime-Dosis als Deckung - die Erklaerung "Carbs kommen" hat ihre
     * eigene Dosis lizenziert. Fuer die CLEARANCE wurde der Prior-Anteil
     * deshalb wieder abgezogen: sein Bahn-Hub am Horizont H mit Zerfall tau
     * ist prior * tau * (1 - e^(-H/tau)) (Integral des exponentiell
     * zerfallenden Zusatz-Drives).
     *
     * SEIT C2 (Codex-Adjudication, K2 Punkt 8) NICHT MEHR IM SICHERHEITSPFAD.
     * Die analytische Subtraktion war unvollstaendig: sie lief am
     * RELEASE-Horizont (30 min), das Minimum der unteren Bahn liegt aber
     * typisch am HAFTUNGS-Horizont (120 min) - bei prior 0,7 / tau 60 sind das
     * 16,53 gegen 36,32 mg/dl, also bis ~19,8 mg/dl selbstlizenzierter Kredit.
     * Und ein Kandidat kann den Ort des Minimums zusaetzlich verschieben.
     * Ersetzt durch die PUNKTWEISE prior-freie Zwillingsbahn aus
     * [app.aaps.fuse.core.predictor.TrajectoryCore]. Die Funktion bleibt fuer
     * Anzeige/Bezifferung des Hubs, ist aber KEIN Sicherheitsbaustein mehr.
     */
    fun priorLiftAtHorizonMgdl(priorPerMin: Double, tauMin: Double, horizonMin: Int): Double {
        if (priorPerMin <= 0.0 || tauMin <= 0.0 || horizonMin <= 0) return 0.0
        return priorPerMin * tauMin * (1.0 - exp(-horizonMin / tauMin))
    }

    /** Fenster, ueber das die angekuendigte Kohlenhydrat-Menge als Anstieg
     *  erwartet wird. PROVISORISCH - eine Mahlzeit ist nicht rechteckig; die
     *  Groesse ist bewusst konservativ lang gewaehlt, weil ein kuerzeres
     *  Fenster den Kredit je Minute erhoeht. */
    const val CREDIT_WINDOW_MIN = 60.0

    /**
     * ERKLAERTE ABSORPTION als BEDARF (Toni 09.08., nach Codex H2).
     *
     * Der Marker-Prior auf der unteren Bahn ist tot: eine Erklaerung darf
     * keine Sicherheit vortaeuschen. Ein Regler, der auf den Anstieg wartet,
     * ist im FCL aber strukturell zu spaet - Insulin braucht ~20 min Anlauf,
     * Kohlenhydrate nicht. Der Marker wirkt deshalb dort, wo er hingehoert:
     * auf der MITTELBAHN, als erwarteter Anstieg. Er erzeugt BEDARF; das Veto
     * der (prior-freien) Guardbahn bleibt unangetastet - bei vollem
     * Insulinbuch bleibt die Freigabe zu, genau wie am Abend des 08.08.
     *
     * SELBSTBEGRENZUNG (loest zugleich das Audit-Finding "keine Episoden-
     * Gesamthuelle"): der Kredit rechnet mit dem REST der Freigabe-Huelle. Was
     * die Episode schon geliefert hat - egal ob ueber die Sofort-Freigabe
     * oder ueber die Rampe -, zieht den Kredit herunter. Beide Pfade teilen
     * sich damit EINE Huelle, und die Erklaerung verbraucht sich selbst.
     *
     * @param envelopeU Die Freigabe-Huelle [U] - EINE, seit dem Wegfall von S/M/L.
     * @param deliveredU in dieser Episode bereits gate-wirksam abgegeben [U]
     * @param isfMgdlPerU Profil-ISF am Anker
     * @return erwarteter Anstieg [mg/dl/min], 0 wenn die Huelle aufgebraucht
     *   oder eine Eingabe unbrauchbar ist (fail-closed nach unten)
     */
    fun declaredAbsorptionDriveMgdlPerMin(
        envelopeU: Double,
        deliveredU: Double,
        isfMgdlPerU: Double,
        windowMin: Double = CREDIT_WINDOW_MIN,
    ): Double {
        if (!envelopeU.isFinite() || !deliveredU.isFinite() || !isfMgdlPerU.isFinite() || !windowMin.isFinite()) return 0.0
        if (envelopeU <= 0.0 || isfMgdlPerU <= 0.0 || windowMin <= 0.0) return 0.0
        val remaining = (envelopeU - maxOf(0.0, deliveredU)).coerceAtLeast(0.0)
        return remaining * isfMgdlPerU / windowMin
    }
}

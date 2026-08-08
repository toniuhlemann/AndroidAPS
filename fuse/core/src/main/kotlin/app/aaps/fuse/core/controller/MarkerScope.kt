package app.aaps.fuse.core.controller

import kotlin.math.exp

/**
 * Wirkungsbereich der Marker-SONDERRECHTE (Audit R95, NEU-01/NEU-02;
 * Tonis Entscheid 08.08. nachmittags: "bis zur Wende, max 45 min").
 *
 * Der Marker traegt zwei getrennte Dinge:
 *  1. KONTEXT (Anzeige, Stufen-Huelle des Prime-Fensters, Onset-Evidenz):
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
    fun boostActive(markerTs: Long, nowTs: Long, turnLatchedTs: Long): Boolean {
        if (markerTs <= 0L || nowTs < markerTs) return false
        if (turnLatchedTs in 1..nowTs) return false
        return nowTs - markerTs <= BOOST_MAX_MIN * 60_000L
    }

    /**
     * Entzirkularisierung der Prime-Clearance (NEU-01): der Prior hebt die
     * untere Bahn, und dieselbe gehobene Bahn diente der Clearance-Pruefung
     * der Prime-Dosis als Deckung - die Erklaerung "Carbs kommen" hat ihre
     * eigene Dosis lizenziert. Fuer die CLEARANCE wird der Prior-Anteil
     * deshalb wieder abgezogen: sein Bahn-Hub am Horizont H mit Zerfall tau
     * ist prior * tau * (1 - e^(-H/tau)) (Integral des exponentiell
     * zerfallenden Zusatz-Drives).
     */
    fun priorLiftAtHorizonMgdl(priorPerMin: Double, tauMin: Double, horizonMin: Int): Double {
        if (priorPerMin <= 0.0 || tauMin <= 0.0 || horizonMin <= 0) return 0.0
        return priorPerMin * tauMin * (1.0 - exp(-horizonMin / tauMin))
    }
}

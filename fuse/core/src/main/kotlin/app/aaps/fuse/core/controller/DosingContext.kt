package app.aaps.fuse.core.controller

/**
 * DER ZENTRALE DOSIERKONTEXT (Bauauftrag §4, Schritt A1 - Toni 29.08.).
 *
 * EINE Entscheidung pro Zyklus, aus einem konsistenten Snapshot: gilt fuer
 * diesen Zyklus der erweiterte MEAL-Rahmen oder der begrenzte
 * CORRECTION-Rahmen? Traeger ist AUSSCHLIESSLICH die gepinnte
 * Marker-Leistungsautorisierung (markerPowerPinnedFor/-DeadlineTs,
 * persistiert, halb offene Frist) - Tonis Kontexttraeger-Entscheid:
 * Foundation-/Upfront-/Deferred-Fristen begrenzen ihre QUELLEN, definieren
 * aber nie den Kontext. Kinematische Fenster, hoher r, RISE oder eine noch
 * lebende Evidenzepisode reichen fuer MEAL ausdruecklich NICHT (§4.1;
 * Livefall 27.08.: der Korrektur-Burst lief auf einem KINEMATIC_ONLY-
 * Fenster mit Rise-Rampe bei leerer Autorisierungslage - genau dieser Fall
 * darf nie ins MEAL-Profil rutschen). `ExpectationLedger.classify` ist als
 * Profilquelle ungeeignet, weil es das kinematische Fenster als MEAL
 * einstuft - es bleibt Beobachtungs-, nie Berechtigungssprache.
 *
 * WARUM EIGENE KOMPONENTE STATT DER LIVENESS-PROFILWAHL: die identische
 * Logik lebte im Liveness-Block und wurde bei ausgeschaltetem Kanal GAR
 * NICHT berechnet - ein woertlicher Verstoss gegen die eigene §4-Forderung
 * "Die Kontextwahl darf nicht davon abhaengen, ob Liveness eingeschaltet
 * ist". Der Runner berechnet die Entscheidung jetzt UNBEDINGT je Zyklus;
 * der Liveness-Kanal ist nur noch ein KONSUMENT.
 *
 * REINE FUNKTION, fail-closed durch Konstruktion: ohne gueltigen Pin gibt
 * es kein MEAL - unbekannte oder ungueltige Voraussetzungen erzeugen also
 * strukturell keine erweiterten Rechte (§4.3). Die Pin-PFLEGE (Setzen beim
 * beobachteten Druck, Loeschen bei Ruecknahme) bleibt beim Runner - sie ist
 * Zustandsfuehrung, keine Kontextentscheidung.
 */
object DosingContext {

    enum class Profile { CORRECTION, MEAL }

    /**
     * Der Grund als TYPISIERTES Feld (§4 "reason"). Die Namen sind die
     * bisherigen `livenessProfileReason`-Texte - Trail-Auswertungen lesen
     * dieselben Woerter weiter.
     */
    enum class Reason {
        /** Gueltige, gepinnte Leistungsautorisierung - MEAL. */
        MARKER_POWER,

        /** Der Pin existiert fuer DIESEN Marker, die Frist ist um (halb
         *  offen: exakt an der Deadline gilt bereits CORRECTION). */
        POWER_EXPIRED,

        /** Ein Marker steht, traegt aber keinen passenden Pin - z. B. beim
         *  Warmstart bloss vorgefunden, nie im Prozess gedrueckt. Kein
         *  rueckwirkendes MEAL. */
        MARKER_NOT_PINNED,

        /** Kein Marker - der gewoehnliche Korrekturzustand. */
        NO_MARKER,
    }

    data class Decision(
        val profile: Profile,
        val reason: Reason,
        /** §4 "authorizationId": die gepinnte Markeridentitaet; 0 = keine. */
        val authorizationId: Long,
        /** §4 "authorizationExpiresAt": die beim Druck eingefrorene
         *  Deadline; 0 = keine. Nach POWER_EXPIRED bleibt sie als
         *  Berichtsgroesse stehen - die RECHTE sind trotzdem weg. */
        val authorizationExpiresAt: Long,
    ) {
        /** Bequemer Lesezugriff fuer Konsumenten, die nur die Frage
         *  "erweiterter Rahmen ja/nein" stellen (z. B. die Befreiung der
         *  Mahlzeitenpfade von den Korrektur-Riegeln). */
        val mealAuthorized: Boolean get() = profile == Profile.MEAL
    }

    /**
     * @param nowMs Zyklusuhr.
     * @param markerTs der aktuell wirksame Marker (0 = keiner/zurueckgenommen).
     * @param pinnedFor die persistierte gepinnte Identitaet (0 = kein Pin).
     * @param deadlineTs die persistierte, beim Druck eingefrorene Frist.
     */
    fun decide(nowMs: Long, markerTs: Long, pinnedFor: Long, deadlineTs: Long): Decision {
        // EXAKT die bisherige markerPowerActive-Bedingung (bit-identische
        // Extraktion, Schritt-A-Neutralitaet): Pin vorhanden, Pin gehoert zu
        // DIESEM Marker, die Uhr steht im halb offenen Fenster [pin, deadline).
        val active = pinnedFor > 0L && pinnedFor == markerTs &&
            nowMs >= pinnedFor && nowMs < deadlineTs
        val reason = when {
            active -> Reason.MARKER_POWER
            pinnedFor > 0L && pinnedFor == markerTs -> Reason.POWER_EXPIRED
            markerTs > 0L -> Reason.MARKER_NOT_PINNED
            else -> Reason.NO_MARKER
        }
        return Decision(
            profile = if (active) Profile.MEAL else Profile.CORRECTION,
            reason = reason,
            authorizationId = pinnedFor,
            authorizationExpiresAt = deadlineTs,
        )
    }
}

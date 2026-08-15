package app.aaps.fuse.core.controller

/**
 * WANN EIN KNOPFDRUCK EINE EVIDENZ-EPISODE EROEFFNET.
 *
 * Die Episode ist der Anker fuer den Stoerungsbestand: sie traegt den
 * Verbrauchszaehler und den harten 240-Minuten-Deckel. Sie zweimal fuer
 * dieselbe Mahlzeit zu eroeffnen heisst, dieselbe Stoerung zweimal unbezahlt
 * zu haben - genau die Doppelfinanzierung, gegen die die Episodenbudgets
 * existieren.
 *
 * DER MARKERZEITPUNKT ALLEIN GENUEGT DAFUER NICHT, und das ist der Kern
 * dieser Regel. Er liegt in den Preferences, ueberlebt Neustarts und
 * Aufraeumlaeufe und sieht danach wieder aus wie frisch. Drei Wege, auf denen
 * dieselbe Zahl eine zweite Episode eroeffnet haette:
 *
 *  - Neustart: der Wert steht noch da, die Episode im Ledger ist abgelaufen
 *    oder bereinigt.
 *  - Warmstart des Marker-Rings: er fuellt sich aus dem Trail nach, ein Druck
 *    von vor zwei Stunden saehe damit aus wie beobachtet.
 *  - Wiederholtes Lesen desselben Werts nach dem Deckel.
 *
 * DESHALB ZWEI ZUSAETZLICHE BEDINGUNGEN:
 *
 *  1. `lastConsumedMarkerTs` - ein dauerhaft VERBRAUCHTER Anker im Ledger. Er
 *     ueberlebt den Deckel und die Episodenbereinigung; sonst wuerde derselbe
 *     Preference-Wert nach dem Aufraeumen wieder "neu".
 *  2. `observedPressTs` - der Druck muss IN DIESEM PROZESS beobachtet worden
 *     sein, gesetzt ausschliesslich beim Umschalten. NICHT aus dem
 *     Warmstart-Ring: der kennt alte Druecke aus dem Trail und wuerde sie als
 *     Ereignis ausgeben.
 *
 * WAS DAS KOSTET, ausdruecklich und als Alpha-Entscheidung gewaehlt: ein
 * Absturz zwischen Knopfdruck und Ledger-Persist verliert die Freigabe. Die
 * Marker-Preference bleibt dann wahrscheinlich aktiv, die Oberflaeche zeigt
 * "Marker aktiv", und es gibt trotzdem keine Episode und keinen Kredit. Der
 * naechste Druck nimmt dann erst zurueck, der uebernaechste armt neu - zwei
 * Tastendruecke.
 *
 * Das ist die fail-closed Richtung und bewusst NICHT automatisch
 * rekonstruiert: eine Rekonstruktion muesste raten, ob der Druck vor oder
 * nach dem letzten Persist lag, und im Zweifel eine Episode erfinden. Damit
 * der Zustand nicht raetselhaft ist, traegt er einen eigenen Grund
 * ([Denial.MARKER_EVENT_NOT_DURABLE]) bis in den Export.
 */
object MarkerEpisodeGate {

    /** Wie weit ein Zeitstempel in der Zukunft liegen darf, bevor er als
     *  Uhrensprung gilt. Dieselbe Toleranz wie im Signalpfad. */
    const val FUTURE_TOLERANCE_MS = 60_000L

    enum class Denial {
        NO_MARKER,
        /** Zeitstempel aus der Zukunft - Uhrensprung oder kaputter Zustand. */
        MARKER_IN_FUTURE,
        /** Aelter als der Episodendeckel. */
        MARKER_STALE,
        /** GENAU dieser Druck hat schon einmal eine Episode eroeffnet.
         *  Handlungsanweisung: nichts tun, das ist die alte Mahlzeit. */
        MARKER_ALREADY_CONSUMED,

        /**
         * Der Druck liegt VOR dem verbrauchten Anker - die Uhr ist
         * rueckwaerts gesprungen oder ein Zustand wurde eingespielt.
         *
         * EIGENER GRUND, weil die Handlungsanweisung eine andere ist: gegen
         * `MARKER_ALREADY_CONSUMED` hilft erneutes Druecken (der neue Druck
         * ist neuer), gegen einen Uhrenruecksprung hilft es NICHT - jeder
         * weitere Druck liegt ebenfalls vor dem Anker, bis die Uhr den
         * Rueckstand aufgeholt hat. Beides unter einem Namen zu fuehren
         * hiesse, den Nutzer in eine wirkungslose Handlung zu schicken.
         */
        MARKER_CLOCK_ROLLBACK,
        /**
         * Der Druck wurde in diesem Prozess nicht beobachtet - er stammt aus
         * einer Preference, die einen Neustart ueberlebt hat.
         *
         * Sichtbar machen, nicht verschweigen: die Oberflaeche zeigt in diesem
         * Fall womoeglich weiterhin "Marker aktiv", waehrend es keinen Kredit
         * gibt. Ohne benannten Grund waere das nicht erklaerbar.
         */
        MARKER_EVENT_NOT_DURABLE,
    }

    data class Result(
        /** Identitaet der laufenden Episode; 0 = keine. */
        val episodeId: Long,
        /** Ob DIESER Aufruf sie eroeffnet hat - dann muss der Aufrufer
         *  `lastConsumedMarkerTs` fortschreiben. */
        val opened: Boolean,
        /** Warum keine Episode entstand; `null` = es gibt eine. */
        val denial: Denial?,
        /**
         * Ob der ZUSATZKREDIT dieser Episode widerrufen ist.
         *
         * GETRENNT von [episodeId], und das ist der ganze Punkt: die Episode
         * ist Identitaet und Buchfuehrung, der Kredit ist eine Lizenz. Die
         * Ruecknahme beendet die Lizenz sofort, laesst Anker und bezahlte
         * Menge aber stehen - sonst begaenne die naechste Armierung wieder
         * bei null und dieselbe Stoerung waere ein zweites Mal unbezahlt.
         */
        val creditRevoked: Boolean = false,
        /** Ob sich der Widerrufsstand in DIESEM Zyklus geaendert hat - dann
         *  muss der Aufrufer ihn festschreiben, bevor er weiterdosiert. */
        val revocationChanged: Boolean = false,
        /**
         * OPTION A (Tonis Entscheid 15.08.): ein waehrend der laufenden
         * Episode gedrueckter Marker wird SOFORT verbraucht - der Aufrufer
         * schreibt `lastConsumedMarkerTs` auf diesen Wert fort.
         *
         * Ohne das war der 360-Minuten-NOTAUS weich: der geerbte, nie
         * konsumierte Druck eroeffnete nach Deckelende des Vorgaengers
         * automatisch eine NEUE Episode - Stunden nach dem Druck, ohne
         * bewusste Handlung in dem Moment (2-Tage-Lauf: dreimal, ageMin
         * sprang 359->124/20/161, Kredit lebte 3 Minuten nach dem Notaus
         * wieder auf). Jetzt endet die Mahlzeitenunterstuetzung hart am
         * Deckel; eine neue Episode verlangt einen NEUEN bewussten Druck.
         *
         * Was der verbrauchte Druck WEITER darf: den Widerruf aufheben
         * (erneutes Armen gibt den Kredit frei, [widerruf] prueft die
         * Beobachtung, nicht den Verbrauch) und Prime-/Onset-Fenster sowie
         * den Viewer-Mahlzeitenzaehler neu verankern - die zweite Mahlzeit
         * wird also weiter bedient, nur eben INNERHALB der laufenden Episode
         * und ohne stillen neuen Deckel.
         */
        val consumeInheritedPressTs: Long? = null,
    )

    fun decide(
        nowMs: Long,
        /** Markerzeitpunkt aus den Preferences; 0 = keiner ODER ausdruecklich
         *  zurueckgenommen - der Unterschied steht unten. */
        markerTs: Long,
        /** Episode im Ledger; 0 = keine. */
        ledgerEpisodeId: Long,
        /** Dauerhaft verbrauchter Anker im Ledger. */
        lastConsumedMarkerTs: Long,
        /** Im AKTUELLEN Prozess beim Umschalten beobachteter Druck; 0 = keiner. */
        observedPressTs: Long,
        capMs: Long,
        /** Persistierter Widerrufsstand der laufenden Episode. */
        revokedPersisted: Boolean = false,
    ): Result {
        // EINE LAUFENDE EPISODE BLEIBT, was auch immer der Marker gerade sagt.
        // Ruecknahme beendet die Autorisierung, nicht die Episode - und ein
        // erneuter Druck erbt sie samt Zaehler.
        //
        // DER KREDIT IST DAVON UNABHAENGIG und wird hier entschieden, denn
        // "die Episode bleibt" haette sonst geheissen: der Nutzer nimmt
        // zurueck und der Zusatzkredit dosiert weiter.
        if (ledgerEpisodeId > 0L && nowMs - ledgerEpisodeId in 0..capMs)
            return widerruf(markerTs, observedPressTs, revokedPersisted).let { w ->
                Result(
                    ledgerEpisodeId, opened = false, denial = null,
                    creditRevoked = w, revocationChanged = w != revokedPersisted,
                    // Option A: der geerbte Druck ist mit dem Erben verbraucht.
                    // NICHT bei einem Zukunfts-Zeitstempel: der wuerde den
                    // monotonen Anker vergiften und JEDEN weiteren Druck bis
                    // zum Aufholen der Uhr als MARKER_CLOCK_ROLLBACK sperren.
                    consumeInheritedPressTs = markerTs.takeIf {
                        it > lastConsumedMarkerTs && it - nowMs <= FUTURE_TOLERANCE_MS
                    },
                )
            }

        if (markerTs <= 0L) return Result(0L, false, Denial.NO_MARKER)
        if (markerTs - nowMs > FUTURE_TOLERANCE_MS) return Result(0L, false, Denial.MARKER_IN_FUTURE)
        if (nowMs - markerTs > capMs) return Result(0L, false, Denial.MARKER_STALE)
        if (markerTs < lastConsumedMarkerTs) return Result(0L, false, Denial.MARKER_CLOCK_ROLLBACK)
        if (markerTs == lastConsumedMarkerTs) return Result(0L, false, Denial.MARKER_ALREADY_CONSUMED)
        if (markerTs != observedPressTs) return Result(0L, false, Denial.MARKER_EVENT_NOT_DURABLE)

        // Eine frisch eroeffnete Episode ist nie widerrufen - und wenn der
        // Stand aus einer alten Episode noch `true` sagte, ist das jetzt eine
        // Aenderung, die festgeschrieben werden muss.
        return Result(markerTs, opened = true, denial = null, creditRevoked = false, revocationChanged = revokedPersisted)
    }

    /**
     * AUSDRUECKLICHE RUECKNAHME GEGEN NATUERLICHEN ABLAUF - der Unterschied
     * steht im Markerzeitpunkt selbst und braucht keinen zweiten Zustand:
     *
     *   Ruecknahme:  `toggleMealMarker` NULLT die Preference. Nur dieser eine
     *                Ort tut das, und nur, solange der Marker aktiv ist -
     *                nach Ablauf armt derselbe Knopf naemlich neu.
     *   Ablauf:      der Zeitpunkt BLEIBT stehen, nur `active` wird mit der
     *                Zeit falsch. Die Episode laeuft bis 240 min weiter, und
     *                genau dafuer wurde sie vom 90-Minuten-Fenster geloest.
     *
     * Die Regel ist damit zustandslos und heilt sich nach einem Neustart
     * selbst: steht dort 0, ist zurueckgenommen worden. Der persistierte
     * Stand traegt den Fall, in dem spaeter wieder ein Zeitpunkt auftaucht,
     * ohne dass jemand gedrueckt hat.
     */
    private fun widerruf(markerTs: Long, observedPressTs: Long, revokedPersisted: Boolean): Boolean = when {
        markerTs <= 0L                                       -> true
        // BEWUSSTES ERNEUTES ARMEN gibt frei - aber nur ein in diesem Prozess
        // beobachteter Druck. Ein aus den Preferences vorgefundener Zeitpunkt
        // ist keine Willenserklaerung.
        observedPressTs > 0L && markerTs == observedPressTs  -> false
        else                                                 -> revokedPersisted
    }
}

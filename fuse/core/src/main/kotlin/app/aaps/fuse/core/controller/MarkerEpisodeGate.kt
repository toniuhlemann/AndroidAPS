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
        /** Dieser Druck hat schon einmal eine Episode eroeffnet. */
        MARKER_ALREADY_CONSUMED,
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
    )

    fun decide(
        nowMs: Long,
        /** Markerzeitpunkt aus den Preferences; 0 = keiner. */
        markerTs: Long,
        /** Episode im Ledger; 0 = keine. */
        ledgerEpisodeId: Long,
        /** Dauerhaft verbrauchter Anker im Ledger. */
        lastConsumedMarkerTs: Long,
        /** Im AKTUELLEN Prozess beim Umschalten beobachteter Druck; 0 = keiner. */
        observedPressTs: Long,
        capMs: Long,
    ): Result {
        // EINE LAUFENDE EPISODE BLEIBT, was auch immer der Marker gerade sagt.
        // Ruecknahme beendet die Autorisierung, nicht die Episode - und ein
        // erneuter Druck erbt sie samt Zaehler.
        if (ledgerEpisodeId > 0L && nowMs - ledgerEpisodeId in 0..capMs)
            return Result(ledgerEpisodeId, opened = false, denial = null)

        if (markerTs <= 0L) return Result(0L, false, Denial.NO_MARKER)
        if (markerTs - nowMs > FUTURE_TOLERANCE_MS) return Result(0L, false, Denial.MARKER_IN_FUTURE)
        if (nowMs - markerTs > capMs) return Result(0L, false, Denial.MARKER_STALE)
        if (markerTs <= lastConsumedMarkerTs) return Result(0L, false, Denial.MARKER_ALREADY_CONSUMED)
        if (markerTs != observedPressTs) return Result(0L, false, Denial.MARKER_EVENT_NOT_DURABLE)

        return Result(markerTs, opened = true, denial = null)
    }
}

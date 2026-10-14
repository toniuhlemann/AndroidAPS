package app.aaps.fuse.core.controller

/**
 * DER RUHE-AUSGANG DES PHASE-A-SOFORTBATCHES - was davon bleibt.
 *
 * Konsolidierung 2.0: Sofortanteil und Ruhe-Ausgang sind samt ihren Einstellungen entfallen, mit ihnen die
 * Entscheidung (Gefahrenliste, Ruhezaehlung, Freigabetypen), die Ruheparameter und die Behandlungswahl. Hier
 * stehen nur noch [Track] und [TrackMode] fuer den Ledger: Feld und Codec bleiben, damit der Rueckweg auf 1.x den
 * Zaehler lesen kann. Kein Dosierpfad liest sie.
 */
object UpfrontRecovery {

    /** Welcher Weg den letzten Trackeintrag erzeugt hat. */
    enum class TrackMode { NONE, RISING, CALM }

    /**
     * DER PERSISTIERTE RUHEZAEHLER - nur noch fuer den Ledger: der Codec
     * liest und schreibt ihn, kein Dosierpfad wertet ihn aus (Konsolidierung
     * 2.0). [ofPersisted] prueft geladene Staende weiter fail-closed.
     */
    class Track(
        /** `armedTs` des Markers, zu dem dieser Zaehler gehoert. */
        val markerIdentity: Long = 0L,
        val streak: Int = 0,
        /** Der Signalpunkt, auf dem der letzte Ruhezyklus beruhte. */
        val lastAcceptedSourceTs: Long = 0L,
        /** Wann zuletzt ausgewertet wurde. */
        val lastEvaluationTs: Long = 0L,
        val mode: TrackMode = TrackMode.NONE,
        /**
         * Die Regel-/Konfigurationsgeneration, unter der dieser Zaehler
         * entstanden ist. Ein Wechsel verwirft ihn - er wird niemals
         * umgedeutet.
         */
        val fingerprint: String = "",
    ) {

        /**
         * Ein Zaehler ohne vollstaendige Identitaet ist kein Zaehler.
         * Entweder alles leer, oder alles gesetzt.
         */
        val consistent: Boolean
            get() = if (streak <= 0) markerIdentity == 0L && lastAcceptedSourceTs == 0L &&
                lastEvaluationTs == 0L && mode == TrackMode.NONE && fingerprint.isEmpty()
            else markerIdentity > 0L && lastAcceptedSourceTs > 0L &&
                lastEvaluationTs > 0L && mode != TrackMode.NONE && streak <= MAX_STREAK &&
                fingerprint.isNotEmpty()

        companion object {

            val EMPTY = Track()

            /** Obergrenze gegen einen davongelaufenen Zaehler. */
            const val MAX_STREAK = 1000

            /**
             * FAIL-CLOSED: was der Codec liefert, wird geprueft, nicht
             * geglaubt. Jede unvollstaendige oder widerspruechliche
             * Kombination ergibt [EMPTY] - der konservative Zustand, weil
             * ein fortgesetzter Zaehler mehr Insulin freigeben kann als ein
             * neu begonnener.
             */
            fun ofPersisted(
                markerIdentity: Long,
                streak: Int,
                lastAcceptedSourceTs: Long,
                lastEvaluationTs: Long,
                mode: TrackMode,
                fingerprint: String,
            ): Track {
                val t = Track(markerIdentity, streak, lastAcceptedSourceTs, lastEvaluationTs,
                              mode, fingerprint)
                return if (t.consistent) t else EMPTY
            }
        }
    }
}

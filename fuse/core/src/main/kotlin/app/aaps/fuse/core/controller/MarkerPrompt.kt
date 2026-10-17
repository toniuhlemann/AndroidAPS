package app.aaps.fuse.core.controller

/**
 * WANN DER MAHLZEITEN-KNOPF NACHFRAGEN MUSS - und was die Frage sagen muss.
 *
 * Tonis Anlass war ein Versehen: "was wenn man versehentlich den marker
 * drueckt?" Der Druck ist eine INSULIN-AUTORISIERUNG - ein Fehldruck kostet
 * Einheiten, die keine Kohlenhydrate treffen.
 *
 * DREI REGELN, und alle drei aus einem Grund:
 *
 *  1. ARMEN fragt nach. Es ist die folgenreiche Richtung.
 *  2. RUECKNAHME fragt NICHT. Sie kann nur Insulin sparen, und eine
 *     Rueckfrage waere eine Huerde genau vor der sicheren Handlung.
 *  3. SIE GILT AUF JEDEM SCHIRM. Der Uebersichtsknopf und der FUSE-Tab bekommen
 *     denselben Dialog - sonst haenge die Sicherheit davon ab, welchen Knopf man
 *     erwischt. Deshalb steht die Regel hier und nicht im Fragment.
 *
 * Ein gemessenes Tief macht die Frage nicht noetiger, sondern nur
 * dringlicher zu LESEN: der Text nennt es dann ausdruecklich.
 *
 * Die Mengen der Direktdosis (Sofortanteil, Verteilung, Fundament, Gesamtlimit) sind mit der Konsolidierung
 * entfallen: die Groessenordnung nennt jetzt das Erwartungskonto (Budget nach Kurve), und das rechnet der Aufrufer
 * mit derselben Regel wie der Regler.
 */
object MarkerPrompt {

    /**
     * @param measuredLow ob JETZT ein gemessenes Tief vorliegt.
     */
    data class Facts(
        val measuredLow: Boolean,
        /**
         * MANUELLES Insulin der letzten [FOREIGN_WINDOW_MIN] Minuten [U],
         * `null` = nicht ermittelbar.
         *
         * Auditbefund P0-2: wer von Hand bolt und danach aus Gewohnheit den Marker drueckt, kuendigt dieselbe
         * Mahlzeit zweimal an. TONIS ENTSCHEIDUNG: nichts wird still verrechnet - der Fremdbolus wird BEZIFFERT,
         * die Wahl trifft der Mensch. Das Konto rechnet ihn als vorhandenes Insulin an (Anforderung), das Budget
         * bleibt.
         */
        val foreignBolusU: Double? = null,
        /**
         * Rest der laufenden Evidenz-Episode [min], `null` = keine, unbekannt
         * oder noch reichlich Zeit.
         *
         * Aus Fall 1 des Audit-Nachtrags: eine zweite Mahlzeit erbt den Topf
         * der ersten samt Uhr. Laeuft die Uhr in Kuerze ab, bekommt die
         * Mahlzeit KEIN Evidenzprivileg mehr - das gehoert in den Moment der
         * Entscheidung, nicht in die nachtraegliche Auswertung.
         */
        val episodeRestMin: Int? = null,
    )

    /** Fenster, in dem ein manueller Bolus als "zu dieser Mahlzeit" gilt. */
    const val FOREIGN_WINDOW_MIN = 60

    /**
     * Ab wann das Restalter der Episode im Dialog erscheint. Darueber ist es
     * eine Zahl ohne Handlungsbezug und wuerde den Dialog nur fuellen.
     */
    const val EPISODE_WARN_MIN = 60

    /**
     * `null` = ohne Rueckfrage ausfuehren.
     *
     * @param armed ob der Marker gerade laeuft (dann ist der Druck eine
     *   Ruecknahme).
     */
    fun required(armed: Boolean, facts: Facts): Facts? =
        // Die Ruecknahme kann nur Insulin SPAREN. Eine Rueckfrage waere eine
        // Huerde genau vor der sicheren Handlung - und im Zweifel druckt man
        // sie im Tief weg, statt sie zu lesen.
        if (armed) null else facts
}

package app.aaps.fuse.core.controller

/**
 * WANN DER MAHLZEITEN-KNOPF NACHFRAGEN MUSS - und was die Frage sagen muss.
 *
 * Tonis Anlass war ein Versehen: "was wenn man versehentlich den marker
 * drueckt?" Solange der Knopf nur die Evidenzschwelle senkte, war das
 * verschmerzbar. Seit dem 11.08. ist er eine INSULIN-AUTORISIERUNG - ein
 * Fehldruck kostet dann Einheiten, die keine Kohlenhydrate treffen.
 *
 * DREI REGELN, und alle drei aus einem Grund:
 *
 *  1. ARMEN fragt nach. Es ist die folgenreiche Richtung.
 *  2. RUECKNAHME fragt NICHT. Sie kann nur Insulin sparen, und eine
 *     Rueckfrage waere eine Huerde genau vor der sicheren Handlung.
 *  3. SIE GILT AUF JEDEM SCHIRM. Der Uebersichtsknopf bekommt den Dialog,
 *     der FUSE-Tab hatte bisher keinen - ohne diese Regel haenge die
 *     Sicherheit davon ab, welchen Knopf man erwischt. Deshalb steht die
 *     Regel hier und nicht im Fragment.
 *
 * Ein gemessenes Tief macht die Frage nicht noetiger, sondern nur
 * dringlicher zu LESEN: der Text nennt es dann ausdruecklich.
 *
 * WAS DIE FRAGE NENNEN MUSS, damit sie eine Entscheidung ist und kein
 * Klickhindernis: den moeglichen ERSTEN Schritt, die GANZE Huelle - und dass
 * eine Ruecknahme bereits abgegebenes Insulin NICHT zurueckholt. Der letzte
 * Punkt ist der, den man ohne Hinweis falsch annimmt.
 */
object MarkerPrompt {

    /**
     * @param firstStepU was der erste Zyklus hoechstens freigeben kann - der
     *   gerasterte Zyklusanteil der Huelle, nicht die Huelle selbst.
     * @param envelopeU die GANZE Huelle ueber das Fenster.
     * @param alreadyDeliveredU was diese Episode bereits geliefert hat. > 0
     *   heisst: der Nutzer armt gerade NACH einer Teillieferung nach, und die
     *   Huelle ist entsprechend kleiner.
     * @param authorizesAgainstModel ob die Einstellung an ist. Nur dann
     *   ueberstimmt der Druck Modell-Einwaende - ohne sie ist er die alte,
     *   harmlose Evidenzabsenkung, und der Text darf nicht mehr behaupten.
     * @param measuredLow ob JETZT ein gemessenes Tief vorliegt.
     */
    data class Facts(
        val firstStepU: Double,
        val envelopeU: Double,
        val alreadyDeliveredU: Double,
        val authorizesAgainstModel: Boolean,
        val measuredLow: Boolean,
    )

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

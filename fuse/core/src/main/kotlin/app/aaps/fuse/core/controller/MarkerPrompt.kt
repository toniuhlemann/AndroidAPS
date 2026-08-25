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
     * @param upfrontPlannedU der SOFORTANTEIL von Phase A [U] - was der
     *   Druck unmittelbar anfordert (iLet-Prinzip). 0 = kein Sofortanteil,
     *   dann laeuft alles verteilt.
     * @param phaseARemainderU der ueber das Prime-Fenster VERTEILTE Rest
     *   von Phase A [U].
     * @param phaseBBudgetU das Fundament-Budget [U] bis zum Fensterende.
     * @param foundationEndMin Ende des Fundament-Fensters [min ab Druck].
     * @param envelopeU die GANZE Huelle = Gesamtlimit dieser Episode.
     * @param alreadyDeliveredU was diese Episode bereits geliefert hat. > 0
     *   heisst: der Nutzer armt gerade NACH einer Teillieferung nach, und die
     *   Huelle ist entsprechend kleiner.
     * @param authorizesAgainstModel ob die Einstellung an ist. Nur dann
     *   ueberstimmt der Druck Modell-Einwaende - ohne sie ist er die alte,
     *   harmlose Evidenzabsenkung, und der Text darf nicht mehr behaupten.
     * @param measuredLow ob JETZT ein gemessenes Tief vorliegt.
     */
    data class Facts(
        /**
         * DIE DREI MENGEN DER GEPINNTEN AUTORISIERUNG (Tonis UI-P0 vom
         * 25.08. abends). Sie ersetzen den frueheren `firstStepU`.
         *
         * DER BEFUND: der Dialog nannte "0,27 U" - den Zyklusanteil aus
         * der alten Prime-Schrittrechnung -, waehrend bei Sofortanteil
         * 1,0 tatsaechlich 3,20 U unmittelbar angefordert wurden. Der
         * Nutzer bestaetigte also eine Groessenordnung, die der Dialog
         * nicht nannte. Zusaetzlich teilte der Ersatzweg durch feste 15
         * Minuten, obwohl das Fenster auf 20 stand.
         *
         * ALLE DREI SIND ANFORDERUNGEN, keine Zusagen: Sicherheitsriegel,
         * IOB-Spielraum, Aufschub und Pumpen-Gates koennen sie kuerzen
         * oder verschieben. Der Text muss das sagen.
         */
        val upfrontPlannedU: Double,
        val phaseARemainderU: Double,
        val phaseBBudgetU: Double,
        val foundationEndMin: Int?,
        val envelopeU: Double,
        val alreadyDeliveredU: Double,
        val authorizesAgainstModel: Boolean,
        val measuredLow: Boolean,
        /**
         * MANUELLES Insulin der letzten [FOREIGN_WINDOW_MIN] Minuten [U],
         * `null` = nicht ermittelbar.
         *
         * Auditbefund P0-2 (16.08.): Huelle, Kredit und Evidenzbestand rechnen
         * ausschliesslich gegen FUSE-EIGENE Abgaben. Wer von Hand bolt und
         * danach aus Gewohnheit den Marker drueckt, erzeugt ZWEI
         * Autorisierungen fuer dieselbe Mahlzeit - und der Dialog sagte dabei
         * "bereits geliefert: 0,00 U".
         *
         * TONIS ENTSCHEIDUNG (16.08.): die Huelle wird NICHT gekuerzt. Der
         * Fremdbolus wird nur BEZIFFERT, die Wahl trifft der Mensch. Damit
         * bleibt das Verhalten unveraendert und die stille Nichtanrechnung
         * endet. Der manuelle Bolus bremst ohnehin schon doppelt - ueber
         * capIob/iobTH/maxIOB/Schwanz UND ueber den Bolus-Deckungs-Abschlag
         * auf `r`; eine dritte Verrechnung waere die Doppelbuchung, die beim
         * Nacht-Audit herausoperiert wurde.
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
        /**
         * Laenge des Freigabe-Fensters [min] - die EINGESTELLTE, nicht die
         * einmal voreingestellte.
         *
         * Der Dialogtext trug sie bis 17.08.2026 als feste "15 min" im
         * Ressourcen-String, waehrend die Einstellung auf 25 stand (Toni am
         * Geraet: "Die Dauer ist nicht dynamisch entsprechend dem gewaehlten
         * setting"). Der Satz nennt eine Menge UND eine Zeit; stimmt die Zeit
         * nicht, ist die genannte Menge im falschen Zeitraum gedacht - und
         * genau darauf gruendet der Nutzer seine Zustimmung.
         */
        val windowMin: Int? = null,
    )

    /**
     * DIE MENGENZEILEN DES DIALOGS - typisiert, damit die Auswahl
     * pruefbar ist und nicht in einer Android-Klasse ohne Test steckt
     * (Tonis UI-P0 25.08.). Die Uebersetzung in Text bleibt in der
     * Bedienoberflaeche; WELCHE Zeilen mit WELCHEN Mengen erscheinen,
     * entscheidet sich hier.
     */
    sealed interface Line {

        /** Sofort angeforderter Phase-A-Anteil. */
        data class Upfront(val amountU: Double) : Line

        /** Ueber das Freigabe-Fenster verteilter Rest von Phase A.
         *  `windowMin = null` heisst: Fenster unbekannt, dann wird KEINE
         *  Dauer genannt statt einer erfundenen. */
        data class Spread(val amountU: Double, val windowMin: Int?) : Line

        /** Fundament-Budget bis zum Fensterende. */
        data class Foundation(val amountU: Double, val untilMin: Int) : Line

        /** Das Gesamtlimit dieser Episode. */
        data class Total(val amountU: Double) : Line
    }

    /**
     * Welche Mengenzeilen der Dialog zeigt. NULLTEILE ENTFALLEN - bei
     * Sofortanteil 1,0 gibt es keine "verteilt"-Zeile, bei 0,0 keine
     * "sofort"-Zeile, und ohne Fundament keine Fundament-Zeile. Das
     * Gesamtlimit steht IMMER, es ist die Zahl, gegen die der Nutzer
     * seine Zustimmung abwaegt.
     */
    fun lines(f: Facts): List<Line> = buildList {
        if (f.upfrontPlannedU > 0.0) add(Line.Upfront(f.upfrontPlannedU))
        if (f.phaseARemainderU > 0.0) add(Line.Spread(f.phaseARemainderU, f.windowMin))
        if (f.phaseBBudgetU > 0.0 && f.foundationEndMin != null)
            add(Line.Foundation(f.phaseBBudgetU, f.foundationEndMin))
        add(Line.Total((f.envelopeU - f.alreadyDeliveredU).coerceAtLeast(0.0)))
    }

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

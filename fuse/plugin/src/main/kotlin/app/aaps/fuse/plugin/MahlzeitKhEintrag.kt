package app.aaps.fuse.plugin

/**
 * DER KH-EINTRAG ZUR MAHLZEIT (Toni 26.11.2026): drueckt Toni "Mahlzeit" mit KH-Menge, schreibt FUSE einen normalen
 * KH-Eintrag in die Datenbank - wie der Bolusrechner -, damit die Mahlzeit in der Behandlungsliste, im COB der Startseite
 * und in Nightscout steht. Hier steht nur die Entscheidung; geschrieben wird in `FusePlugin`, ausserhalb der Laufsperre.
 *
 * - OHNE DAUER: FUSE liest KH-Eintraege nur als eCarbs einer Rechner-Mahlzeit, und dort nur solche MIT Dauer
 *   ([ECarbsQuelle]). So zaehlt die Mahlzeit fuer FUSE nie doppelt; FUSE selbst dosiert ohne COB (`NoCobDependencyTest`).
 * - Die Absorptionszeit steht in der Notiz. Den COB-Abbau von AAPS stellt sie NICHT um.
 * - RUECKNAHME (Kodex 26.11.2026): wer die Mahlzeit kurz nach dem Druck zuruecknimmt, hat sie versehentlich angesagt -
 *   dann faellt der Eintrag mit ([VERSEHEN_MS]). Spaeter ist gegessen: das Konto stoppt, die KH bleiben stehen.
 * - Kein doppelter Eintrag: geschrieben wird nur beim Setzen eines neuen Markers, nie im Zyklus und nie nach einem
 *   Neustart; jeder neue Marker vergisst die Kennung des vorigen Eintrags.
 *
 * Ausserhalb von FUSE wirkt der Eintrag wie jeder andere: der AAPS-Bolusrechner rechnet diese KH als COB mit, wenn dort
 * das COB-Haekchen gesetzt ist.
 */
internal object MahlzeitKhEintrag {

    /** Bis so lange nach dem Druck gilt eine Ruecknahme als Versehen [ms]: der Eintrag faellt mit. */
    const val VERSEHEN_MS = 15 * 60_000L

    /** Was nach dem Umschalten, ausserhalb der Laufsperre, mit dem KH-Eintrag geschieht. */
    sealed class Aktion {

        /** Ein neuer Marker "Mahlzeit" mit KH-Menge: den Eintrag schreiben. */
        data class Schreiben(val markerTs: Long, val carbsG: Int, val absorptionMin: Int) : Aktion()

        /** Ruecknahme im Versehen-Fenster: den Eintrag ungueltig machen. */
        data class Loeschen(val eintragId: Long, val markerTs: Long, val carbsG: Int) : Aktion()

        /** Ruecknahme nach dem Versehen-Fenster: der Eintrag bleibt - gegessen ist gegessen. Nur fuer das Log. */
        data class Behalten(val eintragId: Long, val markerTs: Long, val alterMin: Long) : Aktion()
    }

    /** Beim Setzen eines Markers zur Zeit [markerTs]: geschrieben wird nur mit KH-Menge [carbsG]. */
    fun beimSetzen(markerTs: Long, carbsG: Int, absorptionMin: Int): Aktion? =
        if (carbsG > 0) Aktion.Schreiben(markerTs, carbsG, absorptionMin) else null

    /**
     * Bei der Ruecknahme des Markers [markerTs] zur Zeit [now]: was mit seinem Eintrag [eintragId] (0 = keiner)
     * geschieht. Im Versehen-Fenster faellt er, danach bleibt er.
     */
    fun beiRuecknahme(eintragId: Long, markerTs: Long, now: Long, carbsG: Int): Aktion? = when {
        eintragId <= 0L -> null
        now - markerTs in 0..VERSEHEN_MS -> Aktion.Loeschen(eintragId, markerTs, carbsG)
        else -> Aktion.Behalten(eintragId, markerTs, (now - markerTs) / 60_000L)
    }
}

package app.aaps.fuse.core.controller

/**
 * RUECKNAHME MIT RUECKGAENGIG-FRIST (Toni 04.10.).
 *
 * Ein Tipp auf den laufenden Marker nahm ihn bisher SOFORT und ohne
 * Rueckfrage zurueck ("die Ruecknahme kann nur Insulin sparen"). Ein
 * versehentlicher Tipp mitten in der Mahlzeit kostet aber genau das, wofuer
 * der Marker da ist: die Mahlzeiten-Vollmacht. Und ein neuer Druck danach ist
 * KEIN Ersatz - er eroeffnet eine neue Autorisierung mit voller Huelle.
 *
 * DESHALB WIRD DIE RUECKNAHME VORGEMERKT, NICHT VOLLZOGEN. Bis [FRIST_MS]
 * bleibt der Marker voll wirksam und die Vormerkung laesst sich aufheben;
 * danach laeuft die gewohnte Ruecknahme unveraendert.
 *
 * WARUM NICHT SOFORT ZURUECKNEHMEN UND SPAETER WIEDERHERSTELLEN. Die
 * Ruecknahme beruehrt Ledger-Autorisierung, Widerrufsmarke, Ereignisordnung,
 * Preferences und Markerlinie, und ein Zyklus dazwischen raeumt weitere
 * Episodenfelder ab. Eine Wiederherstellung muesste all das exakt
 * zurueckdrehen - jede Luecke waere eine Autorisierung, die es so nie gab.
 * Eine aufgeschobene Ruecknahme braucht dagegen gar nichts zurueckzudrehen.
 *
 * DER PREIS: die Ruecknahme wirkt [FRIST_MS] spaeter. Faellt ein Zyklus in
 * die Frist, rechnet er noch mit Marker - so, als waere der Tipp ein paar
 * Sekunden spaeter gekommen.
 */
object MarkerRuecknahme {

    const val FRIST_MS = 10_000L

    /** Woher der Tipp kam - fuer Log und Trail. */
    enum class Quelle(val code: Long, val text: String) {
        UNBEKANNT(0L, "unbekannt"),
        UEBERSICHT(1L, "Uebersicht"),
        FUSE_TAB(2L, "FUSE-Tab"),
        ;

        companion object {
            fun von(code: Long): Quelle = entries.firstOrNull { it.code == code } ?: UNBEKANNT
        }
    }

    /**
     * @param markerTs der Marker, dem die Ruecknahme gilt. Ein ANDERER Marker
     *        zur Faelligkeit heisst: die Vormerkung ist gegenstandslos.
     * @param ordnung Ereignisordnung des Tipps (0 = keine Kennung).
     */
    data class Vormerkung(
        val faelligTs: Long,
        val markerTs: Long,
        val ordnung: Long,
        val quelle: Quelle,
    )

    sealed interface Entscheid {
        data object Nichts : Entscheid
        data class Ausfuehren(val vormerkung: Vormerkung) : Entscheid
        data class Verwerfen(val vormerkung: Vormerkung, val grund: String) : Entscheid
    }

    /**
     * Eine Ruecknahme vormerken.
     *
     * `null` = es gibt keinen laufenden Marker, den man zuruecknehmen koennte.
     * Ein zweiter Tipp waehrend der Frist VERLAENGERT sie nicht: sonst liesse
     * sich eine gewollte Ruecknahme durch Weitertippen beliebig aufschieben.
     */
    fun vormerken(
        now: Long,
        markerTs: Long,
        markerAktiv: Boolean,
        ordnung: Long?,
        quelle: Quelle,
        bestehend: Vormerkung?,
    ): Vormerkung? = when {
        markerTs <= 0L || !markerAktiv -> null
        bestehend != null && bestehend.markerTs == markerTs -> bestehend
        else -> Vormerkung(now + FRIST_MS, markerTs, ordnung ?: 0L, quelle)
    }

    /** Was mit einer Vormerkung jetzt geschieht. */
    fun pruefe(now: Long, vormerkung: Vormerkung?, markerTs: Long, markerAktiv: Boolean): Entscheid = when {
        vormerkung == null -> Entscheid.Nichts
        // Ein neuer Marker oder ein abgelaufener: es gibt nichts mehr
        // zurueckzunehmen, und schon gar nicht darf die Ruecknahme einen
        // NEUEN Marker treffen.
        vormerkung.markerTs != markerTs -> Entscheid.Verwerfen(vormerkung, "MARKER_CHANGED")
        !markerAktiv -> Entscheid.Verwerfen(vormerkung, "MARKER_NOT_ACTIVE")
        now < vormerkung.faelligTs -> Entscheid.Nichts
        else -> Entscheid.Ausfuehren(vormerkung)
    }
}

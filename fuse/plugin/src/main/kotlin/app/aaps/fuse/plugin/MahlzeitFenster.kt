package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.ExpectationAccount

/**
 * Konsolidierung 2.0: das MAHLZEITFENSTER des laufenden Kontos - das Ende seiner Autorisierung (Tonis Mahlzeitfrist),
 * solange der Marker, auf den es gebucht wurde, noch gesetzt ist. Massgeblich fuer die Anzeige des Fensters und das
 * Nacht-Sonderrecht der Evidenz; NICHT mehr das 90-min-Markerfenster aus 1.x (`OnsetChannel.MARKER_WINDOW_MIN`).
 * (Toni 22.10.: die Bestaetigung nach Tief, fuer die es auch galt, ist entfallen.)
 */
object MahlzeitFenster {

    /** Ende des Fensters [ms]; 0 = kein laufendes Konto zum gesetzten Marker (keins, widerrufen oder neuer Druck). */
    fun endeTs(konto: ExpectationAccount.State?, markerTs: Long): Long =
        if (konto != null && markerTs > 0L && konto.authId == markerTs) konto.windowEndTs else 0L

    /** Laeuft das Fenster des Kontos zum gesetzten Marker jetzt noch? */
    fun laeuft(konto: ExpectationAccount.State?, markerTs: Long, now: Long): Boolean = now < endeTs(konto, markerTs)
}

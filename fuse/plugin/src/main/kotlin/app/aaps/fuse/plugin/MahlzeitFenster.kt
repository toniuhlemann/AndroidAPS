package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.ExpectationAccount

/**
 * Konsolidierung 2.0: das MAHLZEITFENSTER des laufenden Kontos - das Ende seiner Autorisierung (Tonis Mahlzeitfrist),
 * solange der Marker, auf den es gebucht wurde, noch gesetzt ist. Massgeblich fuer die Bestaetigung nach Tief und die
 * Anzeige des Fensters; NICHT mehr das 90-min-Markerfenster aus 1.x (`OnsetChannel.MARKER_WINDOW_MIN`), mit dem der
 * Bestaetigungsknopf nach 90 min verschwand, waehrend das Konto noch gesperrt war und das Fenster weiterlief.
 */
object MahlzeitFenster {

    /** Ende des Fensters [ms]; 0 = kein laufendes Konto zum gesetzten Marker (keins, widerrufen oder neuer Druck). */
    fun endeTs(konto: ExpectationAccount.State?, markerTs: Long): Long =
        if (konto != null && markerTs > 0L && konto.authId == markerTs) konto.windowEndTs else 0L

    /** Laeuft das Fenster des Kontos zum gesetzten Marker jetzt noch? */
    fun laeuft(konto: ExpectationAccount.State?, markerTs: Long, now: Long): Boolean = now < endeTs(konto, markerTs)

    /**
     * Die fruehe Versorgung der laufenden Mahlzeit ist durch ein gemessenes Tief gesperrt, und es gibt ein Budget, das die
     * Bestaetigung wieder freigeben koennte - im GANZEN Fenster. Ein Konto mit Budget 0 hat nichts freizugeben.
     */
    fun gesperrtNachTief(konto: ExpectationAccount.State?, markerTs: Long, now: Long): Boolean =
        konto != null && laeuft(konto, markerTs, now) && konto.early != null && konto.earlyLowLocked && konto.a0U > 0.0
}

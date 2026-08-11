package app.aaps.fuse.core.controller

/**
 * Wann eine Marker-Episode WIRKLICH neu beginnt.
 *
 * Vorher war die Episode am `markerTs` festgemacht, und eine Ruecknahme nullte
 * ihn. Der naechste Druck erzeugte damit einen neuen Zeitstempel und eine neue
 * Episode mit VOLLER Huelle: "versehentlich druecken, zuruecknehmen, richtig
 * druecken" finanzierte die Huelle ein zweites Mal, obendrauf auf das bereits
 * Abgegebene. Genau die Doppelfinanzierung, gegen die die Episodenbudgets
 * existieren - und sie wiegt schwer, seit der Marker bei gemessenem Tief
 * dosieren darf.
 *
 * AKTIVITAET UND BUDGET SIND ZWEI DINGE. Die Ruecknahme beendet die
 * Aktivitaet; die Buchfuehrung laeuft weiter, bis das Fenster der letzten
 * Armierung abgelaufen ist. Erst dann ist es eine andere Mahlzeit.
 *
 * Als eigene Funktion, weil dieselbe Bedingung an zwei Stellen im Zyklus
 * gebraucht wird und weil sie durch das Rig nicht pruefbar waere: dort ist das
 * Pumpen-Gate zu, die Budgets werden nie belastet, und ein Runner-Test koennte
 * die Regel gar nicht sehen.
 */
object MarkerEpisode {

    /**
     * @param armedTs der aktuell gesetzte Marker; 0 = zurueckgenommen oder nie.
     * @param episodeTs die Armierung, an der die laufende Episode haengt.
     * @param nowMs jetzt.
     * @param windowMin Laenge des Markerfensters.
     *
     * `true` heisst: Budget zuruecksetzen. Das ist NUR der Fall, wenn ein
     * Marker gesetzt ist, er nicht der der laufenden Episode ist, UND die
     * laufende Episode abgelaufen ist.
     */
    fun startsNewEpisode(armedTs: Long, episodeTs: Long, nowMs: Long, windowMin: Int): Boolean {
        if (armedTs <= 0L) return false                 // Ruecknahme beendet nichts
        if (armedTs == episodeTs) return false          // dieselbe Episode
        val laeuftNoch = episodeTs > 0L && nowMs - episodeTs < windowMin * 60_000L
        return !laeuftNoch
    }
}

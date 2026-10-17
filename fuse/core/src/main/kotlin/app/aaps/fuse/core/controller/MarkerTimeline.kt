package app.aaps.fuse.core.controller

/**
 * Welche Markerdrucke im Graphen zu sehen sind.
 *
 * WARUM DAS HIER STEHT UND NICHT IM PLUGIN: die Kette vom Knopfdruck bis zum
 * Symbol im AAPS-Uebersichtsgraphen laeuft ueber vier Module
 * (`FusePlugin` -> `FuseOverviewSource` -> `GraphData` -> `MealMarkerDataPoint`)
 * und war bis hierher an KEINER Stelle getestet. Sie ist beim Umbau auf einen
 * Marker um ein Haar unbemerkt zerbrochen, und niemandem waere es aufgefallen,
 * bis das Symbol im Graphen fehlt.
 *
 * `FusePlugin` ist wegen seiner Abhaengigkeiten nicht instanziierbar, also war
 * die Logik faktisch untestbar. Hier ist sie eine reine Funktion - was im
 * Plugin bleibt, ist eine Delegation, und die kann man ansehen.
 *
 * ZWEI ENTSCHEIDUNGEN, DIE MAN KENNEN MUSS:
 *
 * 1. DIE LINIE MARKIERT ABGEGEBENES INSULIN, NICHT DIE ABSICHT (11.08.).
 *
 *    Frueher stand hier das Gegenteil: ein zurueckgenommener Marker blieb
 *    sichtbar, weil "der Graph ein Protokoll dessen ist, was passiert ist".
 *    Das war richtig, solange der Knopf nur die Evidenzschwelle senkte - ein
 *    Fehldruck hinterliess dann tatsaechlich nichts als den Druck.
 *
 *    Seit der Knopf Insulin autorisiert, ist die Frage am Graphen eine
 *    andere: "wann kam hier Mahlzeiteninsulin?" Eine Linie ohne Insulin
 *    beantwortet sie falsch, und zwar genau in dem Moment, in dem man den
 *    Graphen am schnellsten lesen will. Deshalb: wurde bis zur Ruecknahme
 *    NICHTS geliefert, verschwindet der Druck; war schon etwas draussen,
 *    bleibt er - dieses Insulin wirkt weiter und muss zuzuordnen sein.
 *
 *    Die Auswertung verliert dabei nichts: der Trail
 *    (fuse_state_history.jsonl) ist das Journal, der Ring nur eine
 *    Anzeigequelle. Das stand schon vorher an [RING_CAPACITY].
 *
 * 2. DER AKTUELLE MARKER KOMMT AUS DER PREFERENCE, DER REST AUS DEM RING.
 *    Der Ring lebt im Prozess und ist nach jedem Start leer; `armedTs`
 *    ueberlebt. Ohne die Vereinigung beider ueberlebte kein einziger Marker
 *    einen Neustart - mit ihr genau einer, und mit dem Trail-Warmstart
 *    (s. `FusePlugin.warmGraphRingOnce`) alle der letzten 24 Stunden.
 */
object MarkerTimeline {

    /**
     * Die sichtbaren Druckzeitpunkte im Fenster `[fromTime, endTime]`.
     *
     * @param pressRing Druckzeitpunkte dieses Prozesses (und, nach dem
     *   Warmstart, die aus dem Trail nachgefuellten).
     * @param armedTs der aktuell gesetzte Marker aus der Preference; 0 = keiner.
     *
     * Aufsteigend sortiert und doppelfrei: `armedTs` steht nach einem Druck
     * IMMER auch im Ring, und ein zweimal gezeichnetes Symbol an derselben
     * Minute waere ein Artefakt der Datenhaltung, keine Information.
     *
     * Nicht-endliche oder nicht-positive Zeitpunkte fallen raus - 0 heisst
     * "kein Marker" und ist kein Zeitpunkt am 1.1.1970.
     */
    fun visible(pressRing: List<Long>, armedTs: Long, fromTime: Long, endTime: Long): List<Long> =
        (pressRing.asSequence() + sequenceOf(armedTs))
            .filter { it > 0L && it in fromTime..endTime }
            .distinct()
            .sorted()
            .toList()

    /** Obergrenze des Rings. Er ist eine Anzeigequelle, kein Journal - der
     *  Trail ist das Journal. */
    const val RING_CAPACITY = 40

    /**
     * Nimmt einen Druck auf und haelt den Ring auf [RING_CAPACITY].
     *
     * Als Funktion und nicht als drei Zeilen an der Druckstelle, damit der
     * Warmstart aus dem Trail DIESELBE Regel benutzt - zwei Fassungen von
     * "hinten anhaengen, vorne abschneiden" laufen sonst irgendwann
     * auseinander.
     */
    fun add(ring: ArrayDeque<Long>, ts: Long) {
        if (ts <= 0L || ring.contains(ts)) return
        ring.addLast(ts)
        while (ring.size > RING_CAPACITY) ring.removeFirst()
    }

    /**
     * Nimmt einen Druck ZURUECK - aber nur, wenn er folgenlos blieb.
     *
     * @param deliveredU was diese Episode bis jetzt geliefert hat. > 0 heisst:
     *   die Linie bleibt, denn das Insulin wirkt weiter und muss am Graphen
     *   zuzuordnen sein. Eine Ruecknahme stoppt kuenftige Abgaben, sie macht
     *   vergangene nicht ungeschehen - genau das sagt auch der Dialog.
     * @return ob der Druck entfernt wurde.
     */
    fun retract(ring: ArrayDeque<Long>, ts: Long, deliveredU: Double): Boolean {
        if (ts <= 0L || deliveredU > 0.0) return false
        return ring.remove(ts)
    }
}

package app.aaps.fuse.core.controller

/**
 * DER INTERVENTIONSZAEHLER - eine Zahl, die nur steigt.
 *
 * Der ExpectationLedger kann eine ausgebliebene Senkung nur dann als
 * Widerlegung des Modells lesen, wenn zwischen Behauptung und Faelligkeit
 * NICHTS eingegriffen hat. Jede publizierte Menge - SMB oder geaenderte TBR -
 * veraendert den weiteren Verlauf und macht die laufende Behauptung
 * unpruefbar. Genau das zaehlt diese Zahl.
 *
 * SIE STEIGT BEIM PUBLIZIEREN, NICHT BEI DER BESTAETIGUNG. Das ist der
 * inhaltliche Kern und keine Bequemlichkeit: eine Ablehnung der Pumpe beweist
 * NICHT, dass nichts geflossen ist. Die Medtrum-Lebenszyklusmessung (765
 * Zyklen, 09.08.) zeigt eine Sichtbarkeit von p90 56 s und im Maximum 854 s -
 * in diesem Fenster ist ein Auftrag unterwegs, ohne dass irgendeine Abfrage
 * ihn sieht. Wuerde die Zahl erst bei der Bestaetigung steigen, entstuende aus
 * genau diesen Faellen eine MISSED-Evidenz fuer eine Strecke, in der sehr wohl
 * Insulin gewirkt hat - also ein Beleg gegen das Modell aus einem Ereignis,
 * das das Modell nie behauptet hat.
 *
 * SIE WIRD NIE ZURUECKGEDREHT. Es gibt keine Gegenbuchung und keinen
 * Rueckgabepfad, der kleiner wird - [next] liefert `current` oder
 * `current + 1`, nie weniger. Ein spaeter eintreffendes Reject korrigiert
 * deshalb nichts: die betroffene Erwartung bleibt INTERVENED. Der Preis ist
 * ein verlorener Nachweis, der Gegenpreis waere ein falscher.
 *
 * IM ZWEIFEL WIRD GEZAEHLT. Eine faelschlich angenommene Intervention KOSTET
 * Nachweis; eine uebersehene ERFINDET welchen. Deshalb zaehlt jede Lage, die
 * sich nicht ausdruecklich als "nichts publiziert" ausweist.
 */
object InterventionRevision {

    /**
     * WAS DIESER ZYKLUS HINAUSGEGEBEN HAT.
     *
     * `null` heisst hier ueberall "nicht bekannt", nicht "nichts" - und wird
     * als Eingriff gewertet. Kein Default an dieser Klasse: jede Aufrufstelle
     * muss sich zu jedem Feld erklaeren, sonst ist ein vergessenes Feld ein
     * stiller Nachweisfehler statt eines Kompilierfehlers.
     */
    data class Published(

        /** Publizierte Bolusmenge. 0.0 heisst belegt "kein Bolus". */
        val smbU: Double?,

        /**
         * Ob dieser Zyklus die laufende Rate VERAENDERT hat.
         *
         * Bewusst ein Ja/Nein des Aufrufers und kein Ratenvergleich hier
         * drin: ob eine gesetzte Rate die laufende ersetzt oder nur
         * bestaetigt, weiss allein die Stelle, die beides kennt. Ein
         * Nachbau in diesem Baustein waere eine zweite, driftende Wahrheit.
         */
        val tbrChanged: Boolean?,
    )

    /**
     * Die naechste Revision - `current` oder `current + 1`, nie weniger.
     *
     * @param current die zuletzt persistierte Revision.
     */
    fun next(current: Long, published: Published): Long {
        val smbUnbekannt = published.smbU == null || !published.smbU.isFinite()
        val smbGeflossen = published.smbU != null && published.smbU.isFinite() && published.smbU > 0.0
        val tbrUnbekannt = published.tbrChanged == null
        val eingriff = smbUnbekannt || smbGeflossen || tbrUnbekannt || published.tbrChanged == true
        // Der Ueberlauf ist kein realistischer Fall (ein Zyklus je Minute
        // braucht 1,7e13 Jahre bis Long.MAX_VALUE), aber ein Umschlag ins
        // Negative waere ein stiller Rueckwaertssprung - und Monotonie ist
        // die einzige Zusicherung, die dieser Baustein gibt.
        if (eingriff && current < Long.MAX_VALUE) return current + 1
        return current
    }
}

package app.aaps.fuse.core.controller

/**
 * DIE IDENTITAET EINES EINGRIFFSSTANDES - Epoche und Folgenummer.
 *
 * Der ExpectationLedger kann eine ausgebliebene Senkung nur dann als
 * Widerlegung des Modells lesen, wenn zwischen Behauptung und Faelligkeit
 * NICHTS eingegriffen hat. Jede publizierte Menge - SMB oder tatsaechlich
 * geaenderte TBR - veraendert den weiteren Verlauf und macht die laufende
 * Behauptung unpruefbar. Genau das haelt dieser Stempel fest.
 *
 * WARUM ZWEI FELDER UND NICHT EINE ZAHL (Toni 18.08.). Der erste Wurf war ein
 * blosser Zaehler. Er kann zurueckfallen - nach einer Quarantaene, nach
 * FuseLedgerRepair (das ausdruecklich nur `revision` fortschreibt), nach einem
 * Rollback auf eine APK, die das Feld nicht kennt. Und der Verbraucher
 * vergleicht auf GLEICHHEIT, nicht auf Ordnung: ein zurueckgefallener und
 * wieder hochlaufender Zaehler trifft irgendwann erneut auf seine alten
 * Werte, und dann sieht ein Eingriff aus wie keiner.
 *
 * Der naheliegende Gegenzug - den Stand beim Laden als Maximum ueber die
 * ueberlebenden Eintraege rekonstruieren - traegt nicht: genau diese Eintraege
 * werden beschnitten (MAX_ENTRIES/MAX_OUTCOMES), von einer alten APK nicht
 * geschrieben und von der Reparatur geleert. Eine Identitaet, die man aus
 * ihren eigenen Spuren zurueckrechnen muss, ist keine.
 *
 * Deshalb: die EPOCHE sagt, aus welchem Lauf der Stand stammt, die SEQUENZ
 * zaehlt darin. Ein Bruch irgendwo im Persistenzpfad eroeffnet eine neue
 * Epoche, und alle Eintraege der alten werden dadurch AUTOMATISCH
 * [ExpectationLedger.Verdict.INTERVENED] - unabhaengig von ihren Zahlen und
 * ohne dass irgendetwas verworfen werden muesste.
 *
 * ER STEIGT BEIM PUBLIZIEREN, NICHT BEI DER BESTAETIGUNG. Eine Ablehnung der
 * Pumpe beweist NICHT, dass nichts geflossen ist. Die
 * Medtrum-Lebenszyklusmessung (765 Zyklen, 09.08.) zeigt eine Sichtbarkeit von
 * p90 56 s und im Maximum 854 s - in diesem Fenster ist ein Auftrag unterwegs,
 * ohne dass irgendeine Abfrage ihn sieht. Wuerde erst die Bestaetigung zaehlen,
 * entstuende aus genau diesen Faellen eine MISSED-Evidenz fuer eine Strecke, in
 * der sehr wohl Insulin gewirkt hat.
 *
 * ER WIRD NIE ZURUECKGEDREHT. Es gibt keine Gegenbuchung: [next] liefert
 * dieselbe Epoche mit gleicher oder um eins hoeherer Sequenz. Ein spaet
 * eintreffendes Reject korrigiert nichts, und auch die bekannten
 * SMB-Strip-Pfade lassen einen bereits erhoehten Stempel stehen. Der Preis ist
 * ein verlorener Nachweis, der Gegenpreis waere ein falscher.
 */
data class InterventionStamp(

    /**
     * WOHER DIESER STAND STAMMT.
     *
     * Wird NICHT hier erzeugt: die Ereignisse, die eine neue Epoche
     * rechtfertigen - Reparatur, Quarantaene, fehlendes Feld, unklarer
     * Persistausgang -, kennt allein die Persistenzschicht. Ein Erzeuger in
     * diesem reinen Baustein waere eine zweite, driftende Vorstellung davon,
     * wann ein Lauf abreisst.
     */
    val epochId: String,

    /** Zaehlt INNERHALB der Epoche monoton. Ueber Epochengrenzen hinweg
     *  bedeutungslos - deshalb darf sie nie allein verglichen werden. */
    val sequence: Long,
) {

    /** Traegt der Stempel eine brauchbare Identitaet? Ein leerer Epochenname
     *  waere eine Wildcard, die jede fremde Epoche traefe. */
    val valid: Boolean get() = epochId.isNotBlank() && sequence >= 0L

    /**
     * WAS DIESER ZYKLUS HINAUSGEGEBEN HAT.
     *
     * `null` heisst hier ueberall "nicht bekannt", nicht "nichts" - und
     * wird als Eingriff gewertet. Kein Default an dieser Klasse: jede
     * Aufrufstelle muss sich zu jedem Feld erklaeren, sonst ist ein
     * vergessenes Feld ein stiller Nachweisfehler statt eines
     * Kompilierfehlers.
     */
    data class Published(

        /** Publizierte Bolusmenge. 0.0 heisst belegt "kein Bolus". */
        val smbU: Double?,

        /**
         * Ob dieser Zyklus die Pumpe tatsaechlich anders fahren laesst
         * als zuvor (Toni 18.08.: "echte Aktuation - neue Rate,
         * Abbruch/Rueckkehr zum Profil oder relevante
         * Laufzeitverlaengerung, nicht lediglich ein nicht-null
         * TBR-Feld").
         *
         * Bewusst ein Ja/Nein des Aufrufers und kein Ratenvergleich hier
         * drin: ob eine gesetzte Rate die laufende ersetzt oder nur
         * bestaetigt, weiss allein die Stelle, die beides kennt. Ein
         * Nachbau in diesem Baustein waere eine zweite, driftende
         * Wahrheit.
         */
        val tbrChanged: Boolean?,
    )

    companion object {

        /**
         * Der naechste Stempel - gleiche Epoche, gleiche oder um eins hoehere
         * Sequenz.
         *
         * IM ZWEIFEL WIRD GEZAEHLT. Eine faelschlich angenommene Intervention
         * KOSTET Nachweis; eine uebersehene ERFINDET welchen. Deshalb zaehlt
         * jede Lage, die sich nicht ausdruecklich als "nichts publiziert"
         * ausweist.
         */
        fun next(current: InterventionStamp, published: Published): InterventionStamp {
            val smbUnbekannt = published.smbU == null || !published.smbU.isFinite()
            val smbGeflossen = published.smbU != null && published.smbU.isFinite() && published.smbU > 0.0
            val tbrUnbekannt = published.tbrChanged == null
            val eingriff = smbUnbekannt || smbGeflossen || tbrUnbekannt || published.tbrChanged == true
            // Der Ueberlauf ist kein realistischer Fall (ein Zyklus je Minute
            // braucht 1,7e13 Jahre bis Long.MAX_VALUE), aber ein Umschlag ins
            // Negative waere ein stiller Rueckwaertssprung - und Monotonie
            // innerhalb der Epoche ist die einzige Zusicherung, die dieser
            // Baustein gibt.
            if (eingriff && current.sequence < Long.MAX_VALUE)
                return current.copy(sequence = current.sequence + 1)
            return current
        }

        /**
         * DER VERGLEICH, AN DEM ALLES HAENGT - beide Felder oder nichts.
         *
         * Als eigene Funktion statt als `==` an der Aufrufstelle, damit die
         * Regel "nie die Sequenz allein" EINE pruefbare Stelle hat. Ein
         * ungueltiger Stempel ist mit KEINEM gleich, auch nicht mit einem
         * anderen ungueltigen: zwei unbekannte Herkuenfte sind kein Beleg
         * dafuer, dass nichts dazwischen lag.
         */
        fun same(a: InterventionStamp?, b: InterventionStamp?): Boolean {
            if (a == null || b == null) return false
            if (!a.valid || !b.valid) return false
            return a.epochId == b.epochId && a.sequence == b.sequence
        }
    }
}

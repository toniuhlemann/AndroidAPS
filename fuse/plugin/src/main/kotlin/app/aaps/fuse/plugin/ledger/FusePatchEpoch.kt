package app.aaps.fuse.plugin.ledger

import app.aaps.core.data.model.TE

/**
 * DIE PATCH-EPOCHE (B3).
 *
 * Das Problem, gegen das dieser Klassifikator steht: `PumpType` + Serial
 * erkennen einen PATCHWECHSEL derselben Pumpe NICHT. Die Medtrum-Seriennummer
 * ist die der BASIS und ueberlebt den Wechsel; ein alter Vorschlag koennte also
 * einen Bolus des neuen Patches binden und darueber geschlossen werden.
 * Gehalten hat das bisher nur Wahrscheinlichkeit - 5-min-Fenster plus exakte
 * Mengengleichheit.
 *
 * DIE QUELLE IST DER BEHANDLUNGSDATENSATZ, nicht der Treiber. `patchId` waere
 * ein Feld im Medtrum-Modul; es zu lesen hiesse, eine FUSE-Sicherheitsinvariante
 * an eine private Treiberstelle zu haengen - genau das verbietet die
 * Mergefaehigkeits-Auflage. Stattdessen wird der neueste gueltige
 * `CANNULA_CHANGE` genommen: er entsteht beim Patchwechsel, steht in der
 * Datenbank und ist ueber oeffentliche Schnittstellen lesbar.
 *
 * ER MUSS ABER AUS DER PUMPE STAMMEN. Ein von Hand oder aus Nightscout
 * eingetragener Wechsel traegt keine Pumpenhistorie - er koennte von einem
 * anderen Geraet, aus einem Import oder schlicht aus einem Tippfehler kommen.
 * Ein solcher Datensatz darf keine Epoche definieren, sonst wuerde eine fremde
 * Eintragung die Bindung eigener Zeilen umdeuten.
 */
object FusePatchEpoch {

    /**
     * `null` heisst UNBEKANNT - und unbekannt ist nicht "keine Epoche".
     *
     * Der Unterschied ist der ganze Punkt: "es gab keinen Patchwechsel" waere
     * eine Aussage, "wir konnten keinen lesen" ist keine. Der Aufrufer muss
     * beide auseinanderhalten koennen, deshalb gibt es hier nur den einen
     * Rueckgabewert und daneben [Reason].
     */
    data class Result(val epochTs: Long?, val reason: Reason) {

        val known: Boolean get() = epochTs != null
    }

    enum class Reason {
        /** Ein gueltiger, pumpeneigener Wechsel zur aktiven Pumpe. */
        PUMP_ORIGIN,

        /** Gar kein Datensatz vorhanden. */
        NO_EVENT,

        /** Vorhanden, aber ohne Pumpenhistorie (Hand-/Nightscout-Eintrag). */
        NOT_PUMP_ORIGIN,

        /** Vorhanden und pumpeneigen, aber von einer ANDEREN Pumpe. */
        FOREIGN_PUMP,

        /** Als ungueltig markiert. */
        INVALID,
    }

    /**
     * @param event der neueste [TE.Type.CANNULA_CHANGE] bis jetzt, oder `null`.
     * @param activePumpTypeName Name des AKTIVEN Pumpentyps, `null` = unbekannt.
     * @param activeSerialHash Hash der AKTIVEN Seriennummer, `null` = unbekannt.
     * @param serialHashOf dieselbe Normalisierung wie im Ledger - sie muss
     *   EINE sein, sonst vergleicht man zwei verschiedene Schreibweisen
     *   derselben Nummer (die Medtrum-Gross-/Kleinschreibungsfalle).
     */
    fun of(
        event: TE?,
        activePumpTypeName: String?,
        activeSerialHash: String?,
        serialHashOf: (String?, String?) -> String?,
    ): Result {
        if (event == null) return Result(null, Reason.NO_EVENT)
        if (!event.isValid) return Result(null, Reason.INVALID)
        // Pumpeneigen heisst: die Pumpe hat den Datensatz erzeugt und traegt
        // ihn in ihrer Historie. Alles andere ist eine Behauptung von aussen.
        if (!event.ids.isPumpHistory()) return Result(null, Reason.NOT_PUMP_ORIGIN)

        val evTyp = event.ids.pumpType?.name
        val evSerial = serialHashOf(event.ids.pumpSerial, evTyp)
        // Unbekannte Seite -> unbekannt. Ein fehlender Wert darf nicht als
        // "passt schon" durchgehen; das ist in diesem Projekt die
        // wiederkehrende Falle.
        if (evTyp == null || evSerial == null || activePumpTypeName == null || activeSerialHash == null)
            return Result(null, Reason.FOREIGN_PUMP)
        if (evTyp != activePumpTypeName || evSerial != activeSerialHash)
            return Result(null, Reason.FOREIGN_PUMP)
        if (event.timestamp <= 0L) return Result(null, Reason.INVALID)

        return Result(event.timestamp, Reason.PUMP_ORIGIN)
    }

    /**
     * Gehoert ein Behandlungsdatensatz in DIESELBE Patch-Epoche wie ein
     * Vorschlag?
     *
     * Beide Unbekannten sperren: ist die Epoche des Vorschlags unbekannt, darf
     * er nichts binden (er koennte aus einer anderen stammen); ist die aktuelle
     * unbekannt, weiss niemand, ob inzwischen gewechselt wurde.
     *
     * Und ein Datensatz VOR dem Wechsel gehoert nie zu einem Vorschlag DANACH -
     * das ist die eigentliche Trennlinie.
     */
    fun sameEpoch(pinnedEpochTs: Long?, currentEpochTs: Long?, treatmentTs: Long): Boolean {
        if (pinnedEpochTs == null || currentEpochTs == null) return false
        if (pinnedEpochTs != currentEpochTs) return false
        return treatmentTs >= pinnedEpochTs
    }
}

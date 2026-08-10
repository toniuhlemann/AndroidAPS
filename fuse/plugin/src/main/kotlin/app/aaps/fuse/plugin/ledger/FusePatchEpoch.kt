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

        /** Falscher Ereignistyp - kein CANNULA_CHANGE. */
        WRONG_TYPE,

        /** Vorhanden, aber ohne Pumpenkennung (Handeintrag). */
        NOT_PUMP_ORIGIN,

        /** Der Datensatz nennt Typ oder Serial nicht vollstaendig. */
        EVENT_IDENTITY_INCOMPLETE,

        /** Die AKTIVE Pumpe ist nicht bestimmbar - liegt nicht am Datensatz. */
        ACTIVE_PUMP_UNKNOWN,

        /** Vollstaendig auf beiden Seiten, aber eine ANDERE Pumpe. */
        FOREIGN_PUMP,

        /** Als ungueltig markiert oder mit unbrauchbarem Zeitstempel. */
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
        // Der Typ wird HIER geprueft, nicht nur beim Aufrufer. Eine
        // Sicherheitsgrenze, die auf einer Vorbedingung des Anrufers beruht,
        // ist keine.
        if (event.type != TE.Type.CANNULA_CHANGE) return Result(null, Reason.WRONG_TYPE)
        if (!event.isValid) return Result(null, Reason.INVALID)

        val evTyp = event.ids.pumpType?.name
        val evSerial = serialHashOf(event.ids.pumpSerial, evTyp)
        // HERKUNFT: Typ UND Serial muessen am Datensatz stehen.
        //
        // BEWUSST SCHWAECHER ALS `IDs.isPumpHistory()`, und zwar aus einem
        // belegten Grund: jenes verlangt zusaetzlich `pumpId != null`, der
        // Medtrum-Treiber setzt beim Wechsel aber KEINE
        // (`MedtrumPump.handleNewPatch` ruft
        // `insertTherapyEventIfNewWithTimestamp(timestamp, type, pumpType,
        // pumpSerial)` - vier Argumente, kein pumpId). Mit `isPumpHistory()`
        // waere die Epoche im Feld IMMER unbekannt gewesen und die
        // Publikationssperre haette SMBs dauerhaft blockiert.
        //
        // Den Treiber zu ergaenzen waere der staerkere Weg - er faellt aber
        // unter die Mergefaehigkeits-Auflage (keine Aenderung in `pump/**`).
        //
        // WAS DIESE SCHWAECHUNG KOSTET, ausdruecklich: ein Datensatz, der
        // Typ und Serial der aktiven Pumpe traegt, aber nicht von ihr erzeugt
        // wurde - etwa aus Nightscout von einer zweiten AAPS-Instanz am
        // SELBEN Geraet - wird angenommen. Bei gleicher Seriennummer ist das
        // sachlich meist auch dieselbe Pumpe. Ein HANDEINTRAG traegt keine
        // Pumpenkennung und bleibt abgewiesen; das war der eigentliche Zweck.
        if (evTyp == null || evSerial == null) {
            // Gar keine Kennung = Handeintrag; halbe Kennung = unvollstaendig.
            val garNichts = event.ids.pumpType == null && event.ids.pumpSerial == null
            return Result(null, if (garNichts) Reason.NOT_PUMP_ORIGIN else Reason.EVENT_IDENTITY_INCOMPLETE)
        }
        // Die AKTIVE Pumpe nicht bestimmen zu koennen ist ein ANDERER Befund
        // als ein fremder Datensatz - er liegt nicht am Ereignis.
        if (activePumpTypeName == null || activeSerialHash == null)
            return Result(null, Reason.ACTIVE_PUMP_UNKNOWN)
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

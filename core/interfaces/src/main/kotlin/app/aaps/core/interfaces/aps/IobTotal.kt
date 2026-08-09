package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

@Suppress("SpellCheckingInspection")
@Serializable
data class IobTotal(
    val time: Long,
    var iob: Double = 0.0,
    var activity: Double = 0.0,
    var bolussnooze: Double = 0.0,
    var basaliob: Double = 0.0,
    var netbasalinsulin: Double = 0.0,
    var hightempinsulin: Double = 0.0,

    // oref1
    var lastBolusTime: Long = 0,
    var iobWithZeroTemp: IobTotal? = null,
    var netInsulin: Double = 0.0, // for calculations from temp basals only
    var extendedBolusInsulin: Double = 0.0, // total insulin for extended bolus

    /**
     * War die Rechnung ueberhaupt DURCHFUEHRBAR?
     *
     * `false` heisst UNBEKANNT, NICHT "null". Der Unterschied ist
     * sicherheitsrelevant: `calculateIobFromBolusToTime` gab bei fehlendem
     * Profil bisher ein vollstaendig genulltes, ENDLICHES Objekt zurueck, und
     * der Aufrufer legte es auch noch im `iobTable`-Cache ab. Ein Verbraucher
     * kann eine erfundene Null nicht von einer echten unterscheiden - jede
     * Plausibilitaetspruefung auf dem WERT scheitert daran prinzipiell. Also
     * muss die Groesse selbst sagen, ob sie eine Aussage ist.
     *
     * Voreinstellung `true`: bestehende Aufrufer und alle erfolgreichen
     * Rechenpfade bleiben unveraendert; wer die Unterscheidung nicht kennt,
     * verhaelt sich exakt wie vorher.
     */
    var valid: Boolean = true,
) {

    companion object
}
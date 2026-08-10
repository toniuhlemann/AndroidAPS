package app.aaps.fuse.plugin

/**
 * WANN MELDET SICH DER LEDGER-HOLD - und wann nimmt er sich zurueck.
 *
 * ## Zwei Fehler, die diese Regel geschlossen hat (Audit 10.08.2026)
 *
 * **S1: die Anzeige las die falsche Wahrheit.** Gesperrt wird ueber
 * `LedgerView.hold`, und das ist eine ODER-Verknuepfung aus VIER Quellen:
 * `holdActuation || persistFailed || recoveryHold || migrationPending`. Meldung
 * und Tab-Zeile lasen nur `state.holdActuation`. Drei Quellen stoppten die
 * Abgabe also lautlos - darunter ausgerechnet `recoveryHold`, die einzige, die
 * einen Neustart ueberlebt, und die, fuer die der Reparaturweg gebaut wurde.
 * Der Schirm sagte dann "Ledger frei".
 *
 * **S2: der Alarm verstummte nach dem ersten Mal.** `NotificationStore.add`
 * frischt bei gleicher Kennung nur Datum und Gueltigkeit auf - kein neuer Text,
 * keine neue Stufe, kein Ton, keine Systemmeldung. Loeste sich ein Hold, blieb
 * die alte Meldung stehen; ein zweiter Hold Tage spaeter fand die Kennung
 * belegt und meldete sich gar nicht.
 *
 * ## Warum die Regel hier steht und nicht im Plugin
 *
 * Im Plugin waere sie nur ueber den ganzen Android-Aufbau erreichbar, also
 * praktisch ungeprueft - und genau in dieser Ecke lagen beide Fehler. Hier ist
 * sie ein reiner Zustandsuebergang und direkt pruefbar, inklusive der Folge,
 * auf die es ankommt: **Hold 1 -> Aufloesung -> Hold 2 meldet sich WIEDER.**
 */
object FuseHoldAlarm {

    /**
     * Woran ein Hold als DERSELBE erkannt wird.
     *
     * Nicht an der Generation allein: `state.holdGeneration` zaehlt nur fuer
     * Fehler AUS dem Zustand. Ein `recoveryHold` laesst sie auf 0 stehen, und
     * zwei aufeinanderfolgende Recovery-Holds waeren ununterscheidbar - der
     * zweite haette sich nie gemeldet. Der GRUND gehoert deshalb zum
     * Schluessel.
     */
    data class Kennung(val generation: Long, val reason: String?)

    sealed interface Aktion {

        /** Neu melden. Der Aufrufer muss die alte Meldung ZUERST zuruecknehmen -
         *  sonst frischt der Store nur das Datum auf und schweigt. */
        data class Melden(val kennung: Kennung, val text: String) : Aktion

        /** Der Hold ist weg: Meldung entfernen. Ohne das bliebe sie stehen und
         *  belegte die Kennung fuer den naechsten Hold. */
        data object Zuruecknehmen : Aktion

        data object Nichts : Aktion
    }

    /**
     * @param hold der ZUSAMMENGESETZTE Wert aus `LedgerView.hold`, nicht
     *   `state.holdActuation`. Wer hier die falsche Groesse einsetzt, baut S1
     *   erneut ein.
     * @param zuletzt was zuletzt gemeldet wurde, `null` = nichts steht.
     */
    fun naechste(
        hold: Boolean,
        kennung: Kennung,
        ursachen: Map<String, Int>,
        zuletzt: Kennung?,
    ): Aktion = when {
        !hold && zuletzt == null -> Aktion.Nichts
        !hold                    -> Aktion.Zuruecknehmen
        kennung == zuletzt       -> Aktion.Nichts
        else                     -> Aktion.Melden(kennung, text(kennung, ursachen))
    }

    /**
     * Der Grund steht VOR den Einzelfehlern: bei `recoveryHold` und
     * `persistFailed` gibt es gar keine Fehlerliste, und "kein Fehler benannt"
     * allein waere dort irrefuehrend - es gibt sehr wohl einen Grund.
     */
    fun text(kennung: Kennung, ursachen: Map<String, Int>): String {
        val grund = kennung.reason ?: "Grund unbekannt"
        val details = if (ursachen.isEmpty()) ""
        else " (" + ursachen.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} x${it.value}" } + ")"
        return "FUSE gibt nichts ab: $grund$details. Ausweg: Einstellungen -> FUSE -> Reparatur."
    }
}

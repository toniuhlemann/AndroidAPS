package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.EarlyAdaptiveMealNeed

/**
 * DER FRUEHE MEAL-HORIZONT (H8) IN WORTEN - eine Quelle fuer die Auswahlliste
 * des Einstellungsdialogs, deren Zusammenfassung und den Einstellungsbericht.
 *
 * Review 18.09.: das freie Zahlenfeld nahm 0..10 an, wirksam sind aber nur
 * [EarlyAdaptiveMealNeed.ALLOWED_HORIZONS_MIN] - eine 7 waere still AUS
 * gewesen. Der Dialog bietet deshalb nur diese Werte an. Ein anderer Wert kann
 * trotzdem gespeichert sein (Altbestand, Backup); er wird nie als blosse
 * Minutenzahl gezeigt, sondern mit dem, was er bewirkt: AUS.
 */
object EarlyAdaptiveMealHorizonText {

    /** Die Auswahl des Dialogs, aufsteigend - AUS zuerst. */
    val auswahl: List<Int> = EarlyAdaptiveMealNeed.ALLOWED_HORIZONS_MIN.sorted()

    fun eintrag(horizonMin: Int): String = if (horizonMin == 0) "AUS" else "$horizonMin min"

    /** Der gespeicherte Wert und seine Wirkung. */
    fun zustand(konfiguriertMin: Int): String =
        if (konfiguriertMin in EarlyAdaptiveMealNeed.ALLOWED_HORIZONS_MIN) eintrag(konfiguriertMin)
        else "$konfiguriertMin min - unzulaessig, wirkt als AUS"

    /** Die Zeile im Einstellungsbildschirm: Zustand, darunter die Erklaerung. */
    fun zusammenfassung(konfiguriertMin: Int, erklaerung: String): String =
        "Aktuell: " + zustand(konfiguriertMin) + "\n" + erklaerung
}

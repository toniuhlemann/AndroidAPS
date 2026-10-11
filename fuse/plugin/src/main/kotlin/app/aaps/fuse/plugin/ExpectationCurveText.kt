package app.aaps.fuse.plugin

import app.aaps.fuse.core.controller.ExpectationAccount
import java.util.Locale

/**
 * DIE ERWARTUNGSKURVE IN WORTEN (Erwartungskonto, Vorregistrierung E-034) -
 * eine Quelle fuer die Zeile im Einstellungsbildschirm und den
 * Einstellungsbericht.
 *
 * Gezeigt wird nie nur der gespeicherte Text, sondern was er bewirkt: eine
 * leere oder ungueltige Kurve schaltet die Erwartung ab, das Konto rechnet
 * dann nur die Ersetzung. Dieselbe Pruefung wie im Regler
 * ([ExpectationAccount.Curve.parse]) - die Zeile kann nicht "gueltig" sagen,
 * waehrend der Zyklus die Kurve verwirft.
 */
object ExpectationCurveText {

    fun zustand(text: String): String {
        if (text.isBlank()) return "leer - Erwartung aus (nur Ersetzung)"
        val kurve = ExpectationAccount.Curve.parse(text) ?: return "ungueltig - Erwartung aus (nur Ersetzung)"
        return String.format(
            Locale.US, "gueltig - %d Stuetzwerte bis %d min, Ende %.2f U",
            ExpectationAccount.CURVE_POINTS - 1, ExpectationAccount.CURVE_END_MIN, kurve.endU,
        )
    }

    /** Die Zeile im Einstellungsbildschirm: Zustand, darunter die Erklaerung. */
    fun zusammenfassung(text: String, erklaerung: String): String =
        "Aktuell: " + zustand(text) + "\n" + erklaerung
}

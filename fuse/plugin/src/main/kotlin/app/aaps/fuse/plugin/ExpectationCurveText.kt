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

    /** Welche Kurve gilt (als Kurventext) und woher. */
    data class Wirksam(val text: String, val quelle: String)

    /**
     * WELCHE KURVE GILT (Toni): die vier Eckwerte haben Vorrang; sind
     * sie nicht gesetzt (alle 0), gilt die Expertenzeile. Sind sie gesetzt,
     * aber ungueltig (nicht steigend, Ende 0), ist die Erwartung aus - es gibt
     * keinen stillen Rueckfall auf die Zeile. Eine Regel fuer Regler,
     * Bildschirm und Marker-Dialog.
     */
    fun wirksam(zeile: String, f30U: Double, f60U: Double, f90U: Double, f120U: Double): Wirksam {
        if (listOf(f30U, f60U, f90U, f120U).all { it == 0.0 })
            return Wirksam(zeile, if (zeile.isBlank()) "nicht gesetzt" else "Expertenzeile")
        val kurve = ExpectationAccount.Curve.ausEckwerten(f30U, f60U, f90U, f120U)
            ?: return Wirksam(UNGUELTIG, "Kurvenwerte ungueltig - sie muessen steigen und am Ende ueber 0 liegen")
        return Wirksam(kurve.values().joinToString(";") { String.format(Locale.US, "%.4f", it) }, "Kurvenwerte")
    }

    /** Die wirksame Kurve in Worten: die Wirkung an den vier Eckpunkten, oder warum keine gilt. */
    fun inWorten(w: Wirksam): String {
        val kurve = ExpectationAccount.Curve.parse(w.text)
            ?: return "keine gueltige Kurve (" + w.quelle + ") - Erwartung aus, nur gemessener Bedarf"
        return String.format(
            Locale.GERMANY, "gilt (%s): nach 30 min %.2f U, 60 min %.2f U, 90 min %.2f U, 120 min %.2f U",
            w.quelle, kurve.at(30.0), kurve.at(60.0), kurve.at(90.0), kurve.endU,
        )
    }

    /** Die Zeile unter den Kurvenwerten: was gilt, darunter die Erklaerung. */
    fun zusammenfassungWirksam(w: Wirksam, erklaerung: String): String =
        "Aktuell: " + inWorten(w) + "\n" + erklaerung

    private const val UNGUELTIG = "ungueltig"
}

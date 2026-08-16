package app.aaps.core.ui.dialogs

import android.content.DialogInterface
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * DIE RUECKFRAGE ZUM MAHLZEITEN-KNOPF - einmal, fuer beide Knoepfe.
 *
 * Es gibt zwei: einen auf dem Uebersichtsschirm und einen im FUSE-Tab. Haetten
 * sie eigene Dialoge, haetten sie frueher oder spaeter verschiedene Texte -
 * und damit verschiedene Sicherheitsniveaus fuer dieselbe Handlung. Deshalb
 * steht der Text hier und die Regel, OB gefragt wird, in
 * `app.aaps.fuse.core.controller.MarkerPrompt`.
 *
 * SO KURZ WIE MOEGLICH, und das ist eine Anforderung und keine Nachlaessigkeit:
 * der Knopf wird mehrmals taeglich gedrueckt. Ein Dialog, den man vier Mal am
 * Tag wegwischt, wird nicht gelesen - und dann schuetzt er nicht mehr, er
 * gewoehnt nur ans Wegwischen. Die erste Fassung war ein Aufsatz; Toni hat sie
 * am Geraet gesehen und gestrichen.
 *
 * GEBLIEBEN ist genau das, was sich VON DRUCK ZU DRUCK aendert:
 *  - die Zahlen (erster Schritt, Rest der Huelle) - die Groessenordnung
 *    gehoert vor das Ja, nicht ins Log,
 *  - ein gemessenes Tief, die einzige Lage, die diesen Druck von einem
 *    gewoehnlichen unterscheidet.
 *
 * WEGGEFALLEN ist alles Gleichbleibende - dass der Druck die Prognose
 * ueberstimmt und dass eine Ruecknahme Abgegebenes nicht zurueckholt. Das
 * steht jetzt in der Einstellungsbeschreibung, wo man es EINMAL liest.
 */
object FuseMarkerDialog {

    /**
     * DREI AUSGAENGE seit dem 15.08. (Tonis Fall: "es gibt Situationen, wo 3
     * Einheiten jetzt zuviel waeren" - etwa reichlich aktiver Bolus vor der
     * Mahlzeit): JA mit Vorschuss (wie immer, der Normalfall bleibt zwei
     * Beruehrungen), OHNE VORSCHUSS (Mahlzeit nur erklaeren, Huelle 0 fuer
     * diese Episode), Abbrechen. Die Wahl faellt IM MOMENT des Drucks -
     * eine Einstellung, die man je Lage umschalten muesste, wuerde nicht
     * umgeschaltet.
     *
     * Nicht OKDialog: der kennt nur zwei Knoepfe. Entprellung und
     * runOnUiThread sind von dort uebernommen - gleiche Handlung, gleiches
     * Sicherheitsniveau.
     */
    fun show(
        activity: FragmentActivity,
        rh: ResourceHelper,
        facts: FuseOverviewSource.MarkerPromptFacts,
        onConfirm: Runnable,
        onConfirmNoPrime: Runnable? = null,
    ) {
        val rest = (facts.envelopeU - facts.alreadyDeliveredU).coerceAtLeast(0.0)
        // EINE Zeile Zahlen. Der Rest steht in der Einstellungsbeschreibung -
        // dort liest man ihn EINMAL, hier saehe man ihn mehrmals taeglich.
        val text = StringBuilder(
            rh.gs(R.string.overview_fuse_meal_confirm_body, facts.firstStepU, rest)
        )
        // Das gemessene Tief ist die dringlichste Lage, die den Druck von einem
        // gewoehnlichen unterscheidet - deshalb zuerst.
        if (facts.measuredLow)
            text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_low))

        // FREMDES INSULIN (Auditbefund P0-2). Die Huelle wird davon NICHT
        // gekuerzt - das ist Tonis ausdrueckliche Entscheidung vom 16.08. Der
        // Dialog beziffert es, damit die Wahl beim Menschen liegt statt still
        // zu unterbleiben. `null` heisst unbekannt und schweigt; nur ein
        // wirklich vorhandener Bolus erzeugt die Zeile.
        facts.foreignBolusU?.takeIf { it > 0.0 }?.let {
            text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_foreign, it))
        }

        // Laeuft die Evidenzuhr ab, bekommt diese Mahlzeit kein Privileg mehr.
        // Das gehoert in den Moment der Entscheidung.
        facts.episodeRestMin?.let {
            text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_episode, it))
        }

        if (onConfirmNoPrime == null) {
            OKDialog.showConfirmation(
                activity,
                rh.gs(R.string.overview_fuse_meal_confirm_title),
                text.toString(),
                onConfirm,
            )
            return
        }
        var gewaehlt = false
        fun waehle(dialog: DialogInterface, aktion: Runnable) {
            if (gewaehlt) return
            gewaehlt = true
            dialog.dismiss()
            SystemClock.sleep(100)
            activity.runOnUiThread(aktion)
        }
        MaterialAlertDialogBuilder(activity, R.style.DialogTheme)
            .setMessage(text.toString())
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(activity, rh.gs(R.string.overview_fuse_meal_confirm_title)))
            .setPositiveButton(android.R.string.ok) { d: DialogInterface, _: Int -> waehle(d, onConfirm) }
            .setNeutralButton(R.string.fuse_meal_confirm_no_prime) { d: DialogInterface, _: Int -> waehle(d, onConfirmNoPrime) }
            .setNegativeButton(android.R.string.cancel) { d: DialogInterface, _: Int ->
                if (!gewaehlt) { gewaehlt = true; d.dismiss() }
            }
            .show()
            .setCanceledOnTouchOutside(false)
    }
}

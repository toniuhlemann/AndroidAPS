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
 * gewoehnt nur ans Wegwischen.
 *
 * GEBLIEBEN ist genau das, was sich VON DRUCK ZU DRUCK aendert:
 *  - die Groessenordnung des Kontos - sie gehoert vor das Ja, nicht ins Log,
 *  - ein gemessenes Tief, die einzige Lage, die diesen Druck von einem
 *    gewoehnlichen unterscheidet,
 *  - manuelles Insulin der letzten Stunde und eine ablaufende Evidenzuhr.
 *
 * ZWEI WEGE seit der Konsolidierung (Toni: die Direktdosis entfaellt, "sollte dennoch eine Direktdosis noetig
 * sein, mache ich das manuell"): "Nur Erwartung" - das Erwartungskonto traegt die Mahlzeit - und "Nur Marker - 0 U"
 * fuer Snacks oder unsichere Mahlzeiten: angekuendigt, aber ohne Erwartungsinsulin.
 */
object FuseMarkerDialog {

    /**
     * Nicht OKDialog: der kennt nur zwei Knoepfe. Entprellung und
     * runOnUiThread sind von dort uebernommen - gleiche Handlung, gleiches
     * Sicherheitsniveau.
     *
     * Bietet FUSE "Nur Erwartung" nicht an (keine gueltige Kurve, kein Profil oder Insulinmodell), sagt der Dialog
     * das ausdruecklich und laesst nur "Nur Marker" zu - die Ankuendigung bleibt moeglich, ein Konto nicht.
     */
    fun show(
        activity: FragmentActivity,
        rh: ResourceHelper,
        facts: FuseOverviewSource.MarkerPromptFacts,
        /** "Nur Erwartung" - wirkt nur, wenn FUSE es anbietet ([FuseOverviewSource.MarkerPromptFacts.expectationOnlyMaxU]). */
        onConfirmExpectationOnly: Runnable,
        /** "Nur Marker - 0 U": angekuendigt, Konto ohne Budget - nur der gemessene Anteil. */
        onConfirmNoPrime: Runnable,
    ) {
        val nurErwartungBisU = facts.expectationOnlyMaxU
        val text = StringBuilder(
            if (nurErwartungBisU != null) rh.gs(R.string.overview_fuse_meal_confirm_expectation_only, nurErwartungBisU)
            else rh.gs(R.string.overview_fuse_meal_confirm_no_account)
        )
        // Das gemessene Tief ist die dringlichste Lage, die den Druck von einem
        // gewoehnlichen unterscheidet.
        if (facts.measuredLow)
            text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_low))

        // FREMDES INSULIN (Auditbefund P0-2): der Dialog beziffert es, damit die Wahl beim Menschen liegt statt
        // still zu unterbleiben. `null` heisst unbekannt und schweigt; nur ein wirklich vorhandener Bolus erzeugt
        // die Zeile.
        facts.foreignBolusU?.takeIf { it > 0.0 }?.let {
            text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_foreign, it))
        }

        // Laeuft die Evidenzuhr ab, bekommt diese Mahlzeit kein Privileg mehr.
        // Das gehoert in den Moment der Entscheidung.
        facts.episodeRestMin?.let {
            text.append("\n\n").append(rh.gs(R.string.overview_fuse_meal_confirm_episode, it))
        }

        var gewaehlt = false
        fun waehle(dialog: DialogInterface, aktion: Runnable) {
            if (gewaehlt) return
            gewaehlt = true
            dialog.dismiss()
            SystemClock.sleep(100)
            activity.runOnUiThread(aktion)
        }
        val builder = MaterialAlertDialogBuilder(activity, R.style.DialogTheme)
            .setMessage(text.toString())
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(activity, rh.gs(R.string.overview_fuse_meal_confirm_title)))
            .setNegativeButton(android.R.string.cancel) { d: DialogInterface, _: Int ->
                if (!gewaehlt) { gewaehlt = true; d.dismiss() }
            }
        // DIE KNOEPFE BENENNEN DIE HANDLUNG (Tonis UI-P0): "Nur Marker" nennt ausdruecklich 0 U.
        if (nurErwartungBisU != null) {
            builder
                .setPositiveButton(R.string.fuse_meal_confirm_expectation_only) { d: DialogInterface, _: Int -> waehle(d, onConfirmExpectationOnly) }
                .setNeutralButton(R.string.fuse_meal_confirm_no_prime) { d: DialogInterface, _: Int -> waehle(d, onConfirmNoPrime) }
        } else {
            builder.setPositiveButton(R.string.fuse_meal_confirm_no_prime) { d: DialogInterface, _: Int -> waehle(d, onConfirmNoPrime) }
        }
        builder.show().setCanceledOnTouchOutside(false)
    }
}
